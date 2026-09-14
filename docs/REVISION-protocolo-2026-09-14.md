# Revisión del protocolo (14 de septiembre de 2026)

**Origen.** Una observación de la comparación con Signal
([REVISION-comparacion-signal-2026-09-12.md](REVISION-comparacion-signal-2026-09-12.md), y ya
antes el §0.1 de [PLAN-privacidad-y-confianza.md](PLAN-privacidad-y-confianza.md)):

> **Protocolo maduro, auditado y analizado formalmente**, frente a uno propio, de días,
> deliberadamente sin revisión externa antes de encenderlo. Es el mayor riesgo de Krypta: las
> primitivas criptográficas no son caseras (X25519, AES-256-GCM, HMAC, HKDF), pero el protocolo
> que las combina sí lo es.

El autor pidió **absolver cada parte de la observación** y documentarlo en detalle. Este documento
hace tres cosas:

1. Recoge una **revisión interna** del protocolo: leer el código contra lo que prometen sus propios
   documentos de diseño, buscando los fallos que los tests no ven. **No sustituye a la revisión
   externa**; su valor es llegar a ella con lo evidente ya arreglado y lo no evidente declarado.
2. Deja escritos los **hallazgos**. Cada uno se reprodujo con un test **antes** de arreglarlo, y
   se comprobó que ese test fallaba.
3. Dice qué se hace con **cada parte** de la observación (§3) y deja el **plan** del análisis
   formal (§4) y de la revisión externa (§5).

La especificación normativa que se escribió para esto está en
[ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md).

---

## 0. Resumen

| Id | Gravedad | Qué | Estado |
|---|---|---|---|
| **H-0** | **Crítica** | Dos operaciones simultáneas sobre la misma conversación **reutilizaban clave y nonce de AES-GCM** | ✅ Arreglado: cerrojo por conversación |
| **H-1** | **Alta** | Borrar y volver a añadir un contacto, o importar un `.krbk`, hacía que **todo lo que ese contacto escribía se perdiera para siempre** | ✅ Arreglado: el anuncio `V` dice qué tienes apuntado del otro |
| **H-2** | Media | Guardar una **copia vieja** del contacto (verificar, bloquear, renombrar, importar) lo devolvía a la clave estática **para siempre** | ✅ Arreglado: la versión no baja, y un sobre v2 la vuelve a subir |
| **H-3** | Media (exige `S`) | Un anuncio `V` **forjado** con una versión menor degradaba la pareja a v1 y dejaba **leer en pasivo** lo que viniera | ✅ Arreglado: misma regla que H-2 |
| **H-4** | Media (exige `S`) | Un sobre con un **linaje forjado** secuestra la sesión, sin vuelta atrás | 📌 Limitación de diseño; fijada en un test y llevada a la revisión externa |
| **H-5** | Baja–media (exige `S`) | La autenticación del remitente es la de `S`: incluye **suplantar a un contacto ante quien perdió su propia clave** (KCI) | 📌 Limitación de diseño; llevada a la revisión externa |
| **H-6** | Baja | Volver a añadir a un contacto **bloqueado** lo desbloqueaba | ✅ Arreglado |

**Verificación tras los arreglos.**

- **Suite JVM: 279 tests, 0 fallos** en todos los módulos (app 49, data 17, native-bridge 7,
  p2p-signaling 206).
- **Instalado en el TECNO sobre la base real**: arranca, pinta las dos conversaciones con su vista
  previa descifrada y dice «conectado».
- **El autor confirmó** el mismo 14 sep que la verificación en el móvil es correcta.
- **Lo que no se pudo ejercitar ahí**: la escritura de contactos con el SQL nuevo y el intercambio
  de anuncios. Los dos necesitan el segundo móvil ([PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md)
  §16.11). El SQL sí lo validan Room al compilar y `ContactUpsertSqlTest` contra SQLite.

**Lo que no se puede absolver desde aquí**, y conviene no fingir lo contrario: la **madurez**, que
es tiempo y uso; la **revisión externa**, que la tienen que hacer otras personas; y el **análisis
formal**, que necesita un modelo y una herramienta. Para las tres, este cambio deja el terreno
preparado (§3 a §5), no el trabajo hecho.

Y una lectura de conjunto que hay que decir en voz alta: **un protocolo con 237 tests, pruebas de
propiedades y fuzzing tenía un fallo crítico que una lectura atenta encontró en una tarde.** Eso no
es un argumento para dejar la revisión externa; es el mejor argumento que había para pedirla.

---

## 1. Método

Se leyó `Ratchet`, `RatchetState`, `RatchetSessions`, `ChatService` (recepción, anuncio,
reengache, envío, contactos), `MessageEnvelope`, `CallService`, `MailboxLabel`, `IdentityBackup`,
`BackupManager`, el repositorio y el DAO de contactos, y `SharedSecretFor` en Go, contra
[DISENO-ratchet.md](DISENO-ratchet.md) y [security-model.md](security-model.md). Se buscaron cuatro
familias de fallo, que son las que los tests existentes no podían ver:

- **Estado compartido sin exclusión**: los tests del ratchet son secuenciales.
- **Datos que un extremo puede perder y el otro no vuelve a mandar**: los tests arrancan con los
  dos extremos bien configurados.
- **Reglas que se deciden con datos que no están autenticados más allá de `S`**: el modelo del
  diseño da por perdido al que tiene `S`, pero no mide *cuánto* gana.
- **Escrituras que pisan campos que no pretendían tocar.**

**Regla de trabajo**: un hallazgo solo cuenta si hay un test que lo reproduce y **falla** con el
código anterior. Una anécdota que lo justifica: el primer test de H-0 **pasó**. No porque la carrera
no existiera, sino porque estaba mal escrito: suspendía *antes* de leer el estado, así que cada
operación leía lo que había guardado la anterior. En Room la consulta ya ha leído cuando la corrutina
se reanuda, y la ventana está *entre leer y guardar*. Con el test corregido, falló como se esperaba.
Si se hubiera dado por buena la primera ejecución, H-0 figuraría aquí como «descartado».

---

## 2. Hallazgos

### H-0 (Crítica): reutilización de clave y nonce con operaciones simultáneas

**Qué pasaba.** `RatchetSessions.send` y `receive` hacen tres pasos: cargar el estado, cifrar o
descifrar, y guardar el estado avanzado. Cargar suspende, porque en Room es una consulta. Nada
impedía que dos operaciones sobre **la misma conversación** se cruzaran en esa ventana, y entonces
las dos partían del **mismo estado**:

- **Dos envíos** cifraban con la misma terna `(L, e, N)`: la misma clave de mensaje y, como el
  nonce se deriva de ella, **el mismo nonce de AES-GCM** para dos textos distintos.
- **Una recepción cruzada con un envío** guardaba su estado encima del del envío y **devolvía
  `sendN` hacia atrás**, así que el mensaje siguiente repetía la clave del que acababa de salir.

**Consecuencias.** Reutilizar clave y nonce en GCM es la forma clásica de romperlo del todo:

- expone el **XOR de los dos textos en claro** a quien haya capturado los dos sobres (el nodo o el
  relay, A1);
- permite **recuperar la subclave de autenticación** de esa clave, con lo que en principio se
  podría fabricar un sobre válido con esa cabecera;
- y el receptor **descarta el segundo mensaje** por clave gastada: además de lo anterior, se pierde.

**Cuándo pasa de verdad.** La app tiene varias vías que llegan a la misma conversación a la vez: el
procesador del buzón (hilo de Go, con `runBlocking`), el colector de eventos (`Dispatchers.IO`), la
UI (`viewModelScope`), `CallService`, los reengaches lanzados y `retryFailed` en el ciclo WAN. Casos
concretos:

- **abrir un chat** manda el acuse de lectura mientras el buzón entrega;
- escribir un texto **mientras sale un archivo troceado**;
- una **señal de llamada** en medio de mensajes.

**Exposición.** Solo afecta a parejas donde los dos extremos anuncian ≥ 2, porque solo a esas se les
envía por ratchet (encendido el 10 sep 2026). En la práctica es la pareja del autor y la colaboradora
desde ese día. **No hay forma de saber desde fuera si llegó a ocurrir**: la base va cifrada y el
diagnóstico no registra ternas. Si ocurrió, lo expuesto es el XOR de dos mensajes concretos para
quien los hubiera capturado, y no hay nada que recuperar.

**Por qué no lo vio nadie.** `RatchetPropertyTest` es secuencial por construcción, y el almacén de
`RatchetSessionsTest` tiene funciones `suspend` que en realidad nunca suspenden.

**Reproducción** (`RatchetSessionsTest`):

- `envios simultaneos a la misma conversacion no repiten clave de mensaje`: con el código anterior,
  **los 8 envíos salieron como `(L, 0, 1)`**;
- `una recepcion que se cruza con un envio no hace retroceder el contador`: **dos envíos con
  `(L, 0, 0)`**.

**Arreglo.** Un `Mutex` por conversación en `RatchetSessions`, que cubre `send`, `receive` (con la
deduplicación dentro, porque dos entregas del mismo sobre a la vez la pasarían las dos) y `forget`.

**Análisis de interbloqueo.** El cerrojo no es reentrante, y no hace falta que lo sea: nada de lo
que se ejecuta dentro (los `persist` de `ChatService`) espera a otra operación del ratchet. Lo que
envía como reacción a lo recibido (reengaches, anuncios) va **lanzado** y espera su turno. Los dos
tests pasan con el arreglo.

### H-1 (Alta): tras borrar y volver a añadir un contacto, o importar un `.krbk`, sus mensajes se perdían para siempre

**Qué pasaba.** Bob borra a Ana y la vuelve a añadir; o estrena móvil e importa su `.krbk`. Para el
protocolo es lo mismo: el contacto de Ana vuelve con `peerProtocol = 0`, sin sesión de ratchet, y el
respaldo no lleva ninguna de las dos cosas. Entonces:

1. Bob se anuncia a Ana. Ana ya tenía apuntado que Bob habla v3, así que no hace nada. **El anuncio
   sale una vez por versión** y Ana no tiene por qué repetir el suyo.
2. Bob sigue creyendo que Ana habla v1.
3. Ana escribe **por ratchet**, en una sesión que Bob ya no tiene. Bob no puede abrirlo, lo descarta
   y **lo confirma en el buzón**.
4. El reengache de Bob, que es el mecanismo que existe justo para esto, **no sale**, porque está
   condicionado a que Bob «use ratchet» con Ana, y cree que no.

Resultado: **todo lo que Ana le escribiera a Bob se perdía**, sin fin, hasta que Ana borrara y
volviera a añadir a Bob o saliera una versión nueva del protocolo. Bob a Ana seguía funcionando por
v1, así que la conversación parecía viva desde un lado.

**Reproducción.** `ChatServiceTest` → `borrar y volver a anadir a un contacto no pierde para siempre
lo que te escriba`. Con el código anterior falló con el diagnóstico exacto:
`⚠ mensaje ilegible de …KooWSelf (descartado, venía con cabecera de ratchet)` seguido de
`↔ sin reengache para …KooWSelf: no usa ratchet (v0)`.

**Por qué importa tanto.**

- «Eliminar contacto» por error y volverlo a añadir es una acción corriente de la UI.
- Es literalmente el punto 5 de [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md) §16 (pérdida de
  estado con importación de `.krbk`), que **habría fallado en el móvil**.
- Y contradecía la propiedad de la que el diseño está más orgulloso: «una sesión rota nunca es
  permanente».

**Arreglo**, sin cambio incompatible de formato:

1. El sobre `V` lleva una segunda línea con **la versión que tengo apuntada de ti**
   (`V\n3\n<apuntada>`). Un cliente anterior lee solo la primera línea.
2. Quien recibe un `V` que **le tiene por debajo de lo que ya anunció** lo repite, **por la clave
   estática**: sin sesión es lo único que el otro seguro puede abrir.
3. Cuando un contacto **pasa a constar como v2**, se le manda un primer sobre **por ratchet**, para
   que adopte nuestro linaje nuevo antes de escribirnos.
4. El reengache, ante un contacto que consta como < 2 y escribe por ratchet, ya no se calla: manda
   un `V` estático con lo apuntado.
5. `addContact` adelanta el ciclo WAN para que el anuncio salga cuanto antes.

**Lo que no arregla.**

- Lo que Ana escriba **entre** que Bob la vuelve a añadir y el intercambio termina (un ciclo WAN
  más la entrega) se sigue perdiendo: esas claves ya no existen en ningún sitio.
- Hace falta que **los dos** tengan este build: un cliente anterior ignora la segunda línea y no
  responde.

**Tests** (`ChatServiceTest`):

- el de volver a añadir, y el de importar un `.krbk` en un móvil nuevo;
- `a quien nos tiene atrasados se le repite el anuncio por la clave estatica y una sola vez`;
- `sin anuncio previo no se responde a quien nos tiene atrasados`;
- `al saber que un contacto habla ratchet se le manda un primer sobre por ratchet`;
- `un contacto que consta como v1 y escribe por ratchet recibe un anuncio por la clave estatica`,
  que sustituye a un test que afirmaba exactamente lo contrario («un contacto que aún no habla v2
  no recibe reengache»). **Ese test antiguo fijaba el fallo**, y conviene decirlo.
- Más `MessageEnvelopeTest`, que comprueba el formato nuevo y que un parser anterior sigue leyendo
  la versión.

### H-2 (Media): una copia vieja del contacto devolvía la pareja a la clave estática para siempre

**Qué pasaba.** El DAO guardaba contactos con `@Insert(onConflict = REPLACE)`, es decir, la fila
entera tal como llegara. Varias rutas escribían una copia del contacto leída **antes** de que llegara
su anuncio:

- `setVerified` y `setBlocked`, con la copia que tenía la pantalla;
- `announceCapabilities`, con la lista leída antes de un envío que tarda segundos;
- `addContact`, que construía el contacto sin esos campos;
- `BackupManager.import`, que escribe contactos nuevos encima de los que haya.

El caso natural es **añadir un contacto y verificarlo por QR enseguida**, justo cuando se cruzan los
anuncios de capacidad.

**Consecuencia.** Silenciosa y permanente: con ese contacto, **sin ratchet, sin relleno, sin
depósito ciego y sin clave de llamada negociada**, porque el anuncio no se repite. No se pierden
mensajes, y no hay nada en la UI que lo delate.

**Arreglo.**

- `ContactDao.UPSERT_SQL`: un `INSERT OR REPLACE` que toma `MAX(nueva, la guardada)` para
  `peerProtocol`, en **una sola sentencia**, sin ventana entre leer y escribir.
- `ContactRepository.raisePeerProtocol`, que solo sube y no toca nada más.
- `ChatService.learnFromRatchet`: un sobre v2 que **abre** demuestra que el contacto habla ≥ 2, y si
  va relleno, ≥ 3. Esto **cura a quien ya esté afectado** en cuanto el otro escriba, sin hacer nada.
- `addContact` conserva lo que había.

Desde fuera no se puede saber si ya le pasó a alguna pareja (la base va cifrada), y por eso importa
que el arreglo cure solo.

**Tests.** `ContactUpsertSqlTest` (5, en la JVM contra SQLite, ejecutando la misma cadena que el
`@Query`), y en `ChatServiceTest`: `verificar desde una copia vieja del contacto no lo devuelve a la
clave estatica` y `un sobre de ratchet que abre sube la version apuntada del contacto`.

### H-3 (Media, exige `S`): degradación forzada con un anuncio forjado

**Qué pasaba.** Un `V` con una versión **menor** bajaba `peerProtocol`. El `V` va cifrado con `S`,
así que quien tenga cualquiera de las dos identidades (A4 en la especificación) podía, **con un solo
depósito en el buzón**:

- devolver la pareja a v1;
- y leer **en pasivo** todo lo que el otro escribiera desde entonces,

sin romper la conversación y sin más rastro que una línea en el panel de diagnóstico.

**Por qué importa aunque exija `S`.** [DISENO-ratchet.md](DISENO-ratchet.md) §0 promete que tras un
robo, «una vez que ambos giran claves, el atacante pasivo se queda fuera». Esto convertía **un único
acto activo y barato** en lectura pasiva permanente, es decir, anulaba en la práctica esa promesa.

**Arreglo.** La versión apuntada no baja nunca: es la misma regla que H-2. No hay caso legítimo que
lo necesite, porque Android no instala una versión menor encima sin desinstalar, y desinstalar
cambia la identidad. La vuelta atrás de emergencia sigue siendo `RATCHET_SEND = false` en una
publicación.

**Test.** `un anuncio con una version menor no rebaja la del contacto`.

### H-4 (Media, exige `S`, no arreglado): secuestro de la sesión con un linaje forjado

**Qué pasa.** La regla que recupera una pérdida de estado dice: «un linaje mayor se adopta». Quien
tenga `S` fabrica un sobre de época 0 con un linaje enorme y se lo manda a Bob, y entonces:

- Bob lo adopta y avanza con la propuesta efímera que venía dentro.
- **Todo lo que Bob escriba desde ahí va con material que conoce el atacante**, que pasa a leer en
  pasivo.
- Ana, la de verdad, deja de poder leer a Bob y de ser leída por él.
- Y **no hay vuelta atrás**: Ana nunca crea un linaje mayor que uno forjado muy alto, así que ni
  perdiendo el estado vuelve.

**En qué se diferencia de H-3.**

- **Se nota**: la conversación se rompe para Ana.
- **Es estructural**: sale de la misma regla que da la recuperación automática.

Signal tiene el problema de fondo (quien tiene la clave de identidad puede abrir una sesión nueva),
pero allí, cuando el extremo legítimo vuelve a escribir, se recupera la sesión archivada. Aquí no.

**Por qué no se arregla en este cambio.** Toca la regla del linaje, que es la parte más frágil del
ratchet (la prueba de propiedades ya tumbó un arreglo «obvio» el 12 sep, semilla 102), y
DISENO-ratchet decidió no añadir código a ese camino antes de la revisión externa. Hay candidatas en
la especificación (§15.2): acotar `L` a «ahora + margen», exigir prueba de época ≥ 1 para adoptar, o
pedir confirmación antes de adoptar un linaje sobre una sesión con épocas avanzadas.

**Test que lo fija** (`RatchetTest`): `con el secreto compartido un linaje forjado secuestra la
sesion y no hay vuelta atras`. Si algún cambio lo cierra, ese test tiene que cambiar con él.

### H-5 (Baja–media, exige `S`, no arreglado): la autenticación del remitente es la de `S`

**Qué pasa.** Quien tenga cualquiera de las dos identidades puede **inyectar mensajes como el otro**
por tres puertas:

- **v1**, que se acepta siempre;
- la **época 0 de un linaje ajeno**, que se abre sin adoptarlo;
- y **un linaje nuevo** (H-4).

Cerrar solo una no compra nada. Con el **depósito ciego** se añade una variante: el remitente se
atribuye **por la etiqueta**, y la etiqueta sale de `S`. Así que quien obtenga **tu** identidad (por
ejemplo, tu `.krbk` y su frase) puede **ponerte palabras en boca de cualquiera de tus contactos**.
Es lo que se llama suplantación ante el compromiso de la propia clave (KCI). Por el camino antiguo
del buzón no pasaba, porque el nodo fija `from` con la identidad libp2p del que deposita.

**Por qué no se arregla aquí.** Lo que lo resuelve es firmar los sobres con la identidad del
emisor, y eso es un cambio de formato con coste: se pierde la negación, que Krypta no promete pero
tampoco ha decidido tirar. Es la pregunta 4 de la especificación.

### H-6 (Baja): volver a añadir a un contacto bloqueado lo desbloqueaba

`addContact` construía el contacto desde cero, con `blocked = false`. Renombrar a alguien bloqueado
desde «Nuevo contacto» lo desbloqueaba sin avisar. **Arreglo**: se conserva lo que había. **Test**:
`volver a anadir a un contacto bloqueado no lo desbloquea ni olvida su version`.

### Observaciones menores, llevadas a la especificación

- **W-8**: los frames de llamada no llevan contador, y la clave es la misma en los dos sentidos.
- **W-9**: `PN` viaja en la cabecera y no se usa.
- **W-12**: la misma semilla Ed25519 firma y hace X25519.

No se ha visto un ataque concreto con ninguna de las tres; son preguntas para quien revise.

---

## 3. Cada parte de la observación, y qué se ha hecho con ella

| Parte | Qué significa | Qué se ha hecho (14 sep 2026) | Qué falta, y de quién depende |
|---|---|---|---|
| **«Protocolo maduro»** | Años de uso real que destapan los casos límite | No se compra. Lo que la sustituye en parte, **hecho**: pruebas de propiedades (10 sep), fuzzing del parseo (12 sep), pruebas de concurrencia (**hoy**), esta revisión interna (**hoy**) | Tiempo, y [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md) §16 con dos móviles |
| **«Auditado»** | Terceros revisan diseño y código | **No lo está.** Queda el **paquete listo**: especificación normativa, diseños, modelo de seguridad, mapa de código y tests, debilidades y preguntas (§5) | Una revisión externa: OTF o pagada (§5) |
| **«Analizado formalmente»** | Un modelo del protocolo y pruebas de sus propiedades | **No lo está.** Queda un **plan** con alcance, lemas y comprobación de cordura (§4) | Decidir si se hace en casa o se encarga; instalar Tamarin |
| **«Deliberadamente sin revisión externa»** | La decisión de DISENO-ratchet §8.6 | **Revertida hoy**: se busca, y §8.6 lo dice | El autor: hacer la solicitud |
| **«Las primitivas no son caseras»** | X25519, AES-GCM, HMAC y HKDF son estándar | **Confirmado, con un matiz**: HKDF sí está implementado a mano (`Hkdf.kt`, 38 líneas). Desde hoy lo fijan los **vectores de RFC 5869**, calculados aparte con una implementación independiente (`HkdfTest`) | — |
| **«El protocolo que las combina sí lo es»** | La composición es propia y sin revisar | **Especificado normativamente** ([ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md)), con 14 propiedades numeradas y 13 debilidades declaradas. **La revisión interna encontró 7 hallazgos**, 5 arreglados | La revisión externa |
| **«Es el mayor riesgo»** | — | **Sigue siéndolo.** Hoy está más acotado y hay evidencia de que revisar encuentra cosas | — |

---

## 4. Análisis formal: plan

**Herramienta: Tamarin.** El ratchet tiene cuatro rasgos que Tamarin maneja bien: estado persistente
que se actualiza, número de épocas sin límite, Diffie-Hellman con su teoría ecuacional, y
propiedades de compromiso (secreto hacia adelante y recuperación tras compromiso). Es además la
herramienta habitual para analizar ratchets.

- **ProVerif** es más automático, pero le cuesta el estado que se reescribe en bucle.
- **Verifpal** sirve de borrador rápido y no sustituye a ninguno de los dos.
- **Una prueba computacional** (CryptoVerif, o de juegos a mano) no toca ahora: son meses, y solo
  compensa con el formato congelado.

**Qué modelar, por fases**, en `docs/formal/` (no existe todavía):

| Fase | Modelo | Qué tiene que salir |
|---|---|---|
| **M1** | Núcleo: `S` estático, época 0, ratchet DH por mensaje recibido, cadenas simétricas. Sin linaje, sin pérdidas | L1 a L4 de la tabla de abajo |
| **M2** | Añadir el linaje y su reinicio | **Tiene que encontrar H-4 solo**. Si no lo encuentra, el modelo no está modelando lo que creemos |
| **M3** | Añadir la negociación (`V`, la vía v1 y la regla de la versión) | Con la regla anterior, **tiene que encontrar H-3**; con la actual, L5 |
| **M4** | Clave de llamada negociada | L6 |

| Lema | Enunciado |
|---|---|
| **L1** Secreto | Ninguna clave de mensaje de época ≥ 1 es derivable por un atacante que no ha comprometido a ningún extremo |
| **L2** Secreto hacia adelante | Comprometer identidad y estado en *t* no revela claves de mensajes de época ≥ 1 ya recibidos antes de *t* (excluyendo saltadas y retiradas guardadas) |
| **L3** Recuperación frente a pasivo | Tras un compromiso en *t*, si después hay un intercambio DH que el atacante no altera, las claves siguientes vuelven a ser secretas |
| **L4** Acuerdo inyectivo | Si B acepta un mensaje de A con `(L, e, N)`, A lo envió, y B no lo acepta dos veces (modelando la tabla de vistos) |
| **L5** Sin degradación | Un atacante sin `S` no puede hacer que un extremo que consta como ≥ 2 envíe por v1 |
| **L6** Clave de llamada | Comprometer la identidad sin la señalización de la llamada no revela `K_call` |

**Lo que un modelo simbólico no verá**, y por eso no se le debe pedir: **H-0**. Los modelos tratan
cada paso como atómico, y la carrera estaba entre pasos. Eso es trabajo de la auditoría de código y
de los tests de concurrencia, que ya existen.

**Estado:** plan escrito; **nada modelado**. Tamarin no está instalado en la Mac de desarrollo.
Queda por decidir si lo hace el autor (la curva de aprendizaje es de semanas) o se encarga junto con
la revisión.

---

## 5. Revisión externa y auditoría: plan

### 5.1 El paquete

| Pieza | Estado |
|---|---|
| Especificación normativa con propiedades, debilidades y preguntas | ✅ [ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md) |
| Diseños con sus porqués | ✅ [DISENO-ratchet.md](DISENO-ratchet.md), [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md), [DISENO-postcuantico.md](DISENO-postcuantico.md), [DISENO-rotacion-identidad.md](DISENO-rotacion-identidad.md) |
| Modelo de seguridad | ✅ [security-model.md](security-model.md) |
| Esta revisión interna | ✅ |
| Código abierto y licencia | ✅ desde el 12 sep 2026 |
| Mapa de código y tests | ✅ especificación §14 |
| Comparación explícita del ratchet PQ lento con SPQR | ⬜ [REVISION-comparacion-signal-2026-09-12.md](REVISION-comparacion-signal-2026-09-12.md) §4.6 |
| §16 con dos móviles, para que el formato que se revise esté congelado | ⬜ No bloquea el envío: una revisión tarda meses en empezar. Si el formato cambia antes, se etiqueta `revision-externa-2` y se avisa |
| Etiqueta del commit que se revisa | ✅ `revision-externa-1` (14 sep 2026). No se mueve nunca |
| Binarios de la etiqueta | ✅ AAR y APK de depuración arm64 compilados **desde un clon limpio** de la etiqueta y **publicados en su release de GitHub** el 14 sep 2026, con sha256 (SOLICITUD §5) que coinciden con los que calcula GitHub. Son los que lleva el móvil del autor. **No son reproducibles todavía** (plan §4.3). Hacerlo destapó que `build-aar.sh` fallaba en un clon limpio; se arregló en el commit siguiente |
| Textos de la solicitud (OTF, correo de seguimiento, presupuesto y alcance técnico) | ✅ [SOLICITUD-revision-externa.md](SOLICITUD-revision-externa.md). **La tramita el autor** |
| Canal cifrado para recibir los hallazgos | ⬜ No hay clave PGP publicada ([SECURITY.md](../SECURITY.md)); conviene tenerla antes de enviar |
| Solicitud enviada (fecha, vía, identificador) | ⬜ |

### 5.2 Qué pedir, en este orden

1. **Revisión de diseño** del protocolo por un criptógrafo, de pocos días. Es lo que más aporta por
   lo que cuesta. Cubre §5 a §9 de la especificación, las debilidades W-1 a W-12 y los diseños
   post-cuántico y de rotación.
2. **Auditoría del código del camino criptográfico**, no de toda la app:
   - **Kotlin**: `Ratchet`, `RatchetState`, `RatchetSessions`, `RoomRatchetStore` y `RatchetDao`,
     `Padding`, `MessageEnvelope`, `ChatService` (`seal`, `sealAndPersist`, `onReceived`,
     `onHello`, `rehook`), `CallService` (claves y hello), `MailboxLabel`, `IdentityBackup`,
     `IdentityStore`/`KeystoreKeyWrapper`, `DatabaseKey`/`DatabaseEncryption`, `FileVault`.
   - **Go**: `SharedSecretFor`, `RatchetKeyPair`, `RatchetAgree`, `kem*`, el `ConnectionGater`, y la
     lectura acotada de streams.
3. **Más adelante, un pentest** de la app y de los nodos. Importa, pero no es lo que resuelve el
   riesgo del protocolo propio.

### 5.3 Vías

- **Security Lab del Open Technology Fund.** Audita gratis proyectos abiertos de libertad en
  internet. El requisito de código abierto ya se cumple; hay que confirmar la elegibilidad y los
  plazos, que pueden ser de meses.
- **Una revisión pagada acotada** a los puntos 1 y 2, con una empresa de criptografía aplicada.

En los dos casos hay que pedir un **informe publicable**: es lo que convierte la revisión en algo
comprobable y no en otra afirmación.

### 5.4 Reglas mientras dura

- **No se cambia el formato de red.**
- Lo que salga se arregla **con un test que lo reproduzca antes** (la regla de §1), en su propio
  cambio.
- **No se empieza** el post-cuántico, las fases 4 y 5 de la rotación, ni ningún cambio a la regla
  del linaje (H-4) hasta tener el informe.

---

## 6. Orden

1. ✅ H-0, H-1, H-2, H-3 y H-6 arreglados con tests (14 sep 2026); H-4 y H-5 fijados y declarados.
2. ⬜ Este build en los dos móviles, y §16 entero, incluidos los puntos nuevos 11 y 12.
3. ⬜ La comparación con SPQR en DISENO-postcuantico.
4. 🟡 Solicitud al OTF, o presupuesto de una revisión acotada. **Textos listos y commit etiquetado
   (`revision-externa-1`) el 14 sep 2026**, en [SOLICITUD-revision-externa.md](SOLICITUD-revision-externa.md).
   La tramita el autor. Antes de enviar conviene publicar una clave PGP en SECURITY.md.
5. ⬜ Modelo formal M1 y M2: decidir quién lo hace.
6. ⬜ Revisión, informe publicado y correcciones.
7. ⬜ Solo después: rediseño de H-4 y H-5, post-cuántico y rotación.

---

## 7. Qué cambió en el repositorio

- `p2p-signaling`:
  - `RatchetSessions` (cerrojo por conversación);
  - `ChatService` (`onHello`, `launchHello`, `sendStatic`, `deliver`, `learnFromRatchet`, el
    reengache para contactos que constan como v1, el anuncio con la versión apuntada, y `addContact`
    que conserva lo que había y adelanta el ciclo WAN);
  - `MessageEnvelope` (`Hello.knows`).
- `core`: `ContactRepository.raisePeerProtocol` y el contrato de que la versión no baja.
- `data`: `ContactDao.UPSERT_SQL`, `RAISE_PEER_PROTOCOL_SQL` y `RoomContactRepository`. **Sin cambio
  de esquema**: la base sigue en v9.
- Tests: `RatchetSessionsTest` (+2), `ChatServiceTest` (+9, 1 sustituido), `RatchetTest` (+1),
  `MessageEnvelopeTest` (+1), `ContactUpsertSqlTest` (nuevo, 5), `HkdfTest` (nuevo).
- Docs: esta revisión, [ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md) (nueva), y las
  actualizaciones de DISENO-ratchet (§1.11 y §8.6), security-model, PLAN-privacidad-y-confianza,
  PRUEBAS-PENDIENTES (§16.11 y §16.12), architecture y CLAUDE.md.
