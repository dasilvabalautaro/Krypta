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
| **H-5** | Baja–media (exige `S`) | La autenticación del remitente es la de `S`: incluye **suplantar a un contacto ante quien perdió su propia clave** (KCI) | 📌 Limitación de diseño; fijada en un test (15 sep 2026) y llevada a la revisión externa |
| **H-6** | Baja | Volver a añadir a un contacto **bloqueado** lo desbloqueaba | ✅ Arreglado |
| **H-7** | Baja | Por el camino v1, un **invite de llamada reenviado** volvía a sonar, repetía el `busy` y sumaba **una fila de «llamada perdida» por entrega**; y un invite con la fecha adelantada seguía «fresco». Salió de la verificación dinámica del 15 sep (§8) | ✅ Arreglado el 15 sep 2026: una vez por `callId`, 10 min de margen hacia el futuro, fila idempotente |

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

**Test que lo fija** (15 sep 2026, `ChatServiceTest` → `con tu identidad robada te pueden escribir
como cualquier contacto por el buzon ciego`). Modela la variante KCI con claves X25519 reales: quien
tiene la privada de la víctima y la pública del contacto (su PeerID) calcula el mismo `S` que el
contacto, sin su privada. Y fija por qué vía entra:

- **por el buzón ciego, entra** como del contacto;
- **por el buzón con PeerID y un nodo honrado, no entra**, porque el nodo pone de remitente la
  identidad robada, que es el PeerID de la propia víctima;
- **con un nodo que mienta sobre el remitente, también entra**. Esa negativa depende de la honradez
  del nodo.

Si algún cambio lo cierra, el test tiene que cambiar con él.

### H-6 (Baja): volver a añadir a un contacto bloqueado lo desbloqueaba

`addContact` construía el contacto desde cero, con `blocked = false`. Renombrar a alguien bloqueado
desde «Nuevo contacto» lo desbloqueaba sin avisar. **Arreglo**: se conserva lo que había. **Test**:
`volver a anadir a un contacto bloqueado no lo desbloquea ni olvida su version`.

### H-7 (Baja): señales de llamada reproducidas por el camino v1

**Origen.** No salió de esta revisión, sino de la verificación dinámica del 15 sep 2026 sobre
`a97cbab` (§8), que lo planteó como «invite con timestamp futuro». La causa de fondo era otra.

**Qué pasaba.** Por la clave estática (v1) no hay deduplicación de sobres, y `CallService` no
recordaba qué `callId` había atendido. Quien pudiera devolver un sobre auténtico —el nodo, con lo que
guarda en el buzón (A1)— conseguía:

- que una llamada **ya rechazada o colgada volviera a sonar**, mientras el invite siguiera fresco;
- **un `busy` más** por cada copia que llegara estando en otra llamada;
- **una fila de «📞 Llamada perdida» y una notificación por cada entrega** de un invite rancio, sin
  límite, porque `recordMissedCall` generaba un UUID nuevo cada vez.

Y la ventana no tenía límite hacia el futuro: un invite fechado por delante del reloj del receptor
seguía «fresco» tanto tiempo como el adelanto. La red no puede cambiar el `ts`, que va cifrado, así
que eso solo alargaba la ventana de reproducción cuando el reloj del emisor iba adelantado. Y quien
tiene `S` no necesita reproducir nada: puede llamar cuando quiera.

**Exposición.** Las señales que van por v1: contactos con `peerProtocol < 2`. Por v2, la tabla de
vistos del ratchet descarta una copia idéntica antes de descifrar, salvo la época 0 fuera de su
ventana (W-2).

**Reproducción** (`CallServiceTest`; **las seis fallaron con el código anterior**, con el síntoma
que se indica):

- `un invite reenviado tras rechazar la llamada no vuelve a sonar` → volvía a RINGING;
- `un invite rancio entregado varias veces deja una sola llamada perdida` → 3 filas;
- `colgar mientras suena y recibir despues el mismo invite ni suena ni duplica la perdida` → RINGING;
- `tras reiniciar el proceso el mismo invite rancio no crea otra fila de perdida` → 2 filas;
- `un invite con la fecha muy adelantada no suena` → RINGING;
- `un invite repetido mientras hay otra llamada no manda un segundo busy` → 2 `busy`.

Más una guarda que pasa antes y después: `un invite con el reloj del emisor algo adelantado sigue
sonando`.

**Arreglo**, sin cambio del formato de red:

1. `CallService` atiende un invite **una vez por `(contacto, callId)`**. La memoria vive en RAM, dura
   45 s + 10 min desde que se ve y guarda como mucho 256: ese intervalo es toda la vida en la que un
   mismo invite puede timbrar.
2. **Límite hacia el futuro** de 10 min (`INVITE_FUTURE_MS`). Pasado, no timbra, queda como perdida y
   el Diagnóstico lo dice. Es holgado a propósito: un reloj algo desajustado no puede costar la
   llamada.
3. **La fila de llamada perdida es idempotente**: su id es `SHA-256` de `(contacto, callId)` con
   separación de dominio, y si ya existe no se guarda ni se avisa. Hay que mirar antes, porque `save`
   es un upsert y la volvería a marcar como no leída. El `callId` lo elige el otro, por eso va dentro
   del hash y nunca se usa como id.

Tests del arreglo: los siete de arriba y, en `ChatServiceTest`, `la fila de llamada perdida es una por
llamada y no vuelve a avisar` y `el id de la fila de llamada perdida no lo elige el otro extremo`.

**Lo que no arregla.**

- Si el proceso se reinicia dentro de esos 10 min 45 s, el mismo invite puede sonar **una vez más**.
  La fila de perdida sí resiste el reinicio. Se acepta y se declara como W-14 (§9.3).
- Vaciar el chat borra la fila y, con ella, su memoria: un invite rancio que llegue después deja una
  fila nueva.
- Una llamada legítima de alguien con el reloj adelantado más de 10 min queda como perdida.

**Por qué no la ventana simétrica que proponía el informe** (`|ahora − ts| ≤ 45 s`): un receptor con
el reloj más de 45 s por detrás perdería todas las llamadas, y seguiría sin cubrir la reproducción
dentro de la ventana ni las filas repetidas.

**La etiqueta no se mueve.** No cambia el formato de red, así que `revision-externa-1` sigue valiendo;
a quien revise hay que decirle que H-7 se cerró después de la etiqueta.

### Observaciones menores, llevadas a la especificación

- **W-8**: los frames de llamada no llevan contador, y la clave es la misma en los dos sentidos.
  **Verificado contra el transporte el 15 sep 2026** (§8.1).
- **W-9**: `PN` viaja en la cabecera y no se usa.
- **W-12**: la misma semilla Ed25519 firma y hace X25519.

No se ha visto un ataque concreto con ninguna de las tres; son preguntas para quien revise.

---

## 3. Cada parte de la observación, y qué se ha hecho con ella

| Parte | Qué significa | Qué se ha hecho (a 15 sep 2026) | Qué falta, y de quién depende |
|---|---|---|---|
| **«Protocolo maduro»** | Años de uso real que destapan los casos límite | No se compra. Lo que la sustituye en parte: pruebas de propiedades (10 sep), fuzzing del parseo (12 sep), pruebas de concurrencia y esta revisión interna (14 sep), y una revisión preparatoria independiente con pruebas dinámicas (15 sep, §8 y §9). 291 tests JVM | Tiempo, y [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md) §16 y §17 con dos móviles |
| **«Auditado»** | Terceros revisan diseño y código | **No lo está.** La revisión preparatoria independiente **no es** la auditoría: sirvió para llegar a ella con H-7 arreglado, H-5 y W-6 fijados en tests y la documentación sin afirmaciones de más. El **paquete está listo**: especificación normativa, diseños, modelo de seguridad, mapa de código y tests, debilidades y preguntas (§5) | La auditoría pública oficial: OTF o pagada (§5) |
| **«Analizado formalmente»** | Un modelo del protocolo y pruebas de sus propiedades | **No lo está.** Hay un **plan** con alcance, lemas y comprobación de cordura (§4); nada ejecutado | Decidir quién hace M1 y M2 |
| **«Deliberadamente sin revisión externa»** | La decisión de DISENO-ratchet §8.6 | **Revertida el 14 sep**: se busca, y §8.6 lo dice. El encendido del 10 sep no se puede deshacer, y **tuvo coste**: H-0 estuvo en producción cuatro días. El formato de red está congelado desde el 14 sep | El autor: enviar la solicitud |
| **«Las primitivas no son caseras»** | X25519, AES-GCM, HMAC y HKDF son estándar | **Confirmado, con un matiz**: HKDF sí está implementado a mano (`Hkdf.kt`, 38 líneas). Lo fijan los **vectores de RFC 5869**, calculados aparte con una implementación independiente (`HkdfTest`) | — |
| **«El protocolo que las combina sí lo es»** | La composición es propia y sin revisar | **Especificado normativamente** ([ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md)): 15 propiedades numeradas y 14 debilidades declaradas. **8 hallazgos (H-0 a H-7)**: 6 corregidos, cada uno con un test que lo reproducía antes, y 2 limitaciones de la composición (H-4, H-5) fijadas en tests y llevadas a la auditoría | La auditoría |
| **«Es el mayor riesgo»** | — | **Sigue siéndolo.** Más acotado y medido; lo que queda abierto es de la composición, no de las primitivas | — |

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
| Binarios de la etiqueta | ✅ AAR y APK de depuración arm64 compilados **desde un clon limpio** de la etiqueta y **publicados en su release de GitHub** el 14 sep 2026, con sha256 (SOLICITUD §5) que coinciden con los que calcula GitHub. Fueron los del móvil del autor hasta que se instaló un build del commit `8d02875`. **Esos binarios no son reproducibles**, y se vio después que salieron del NDK 25.2, no del 26.1. Hacerlos destapó que `build-aar.sh` fallaba en un clon limpio (arreglado en `26bd405`). **Desde `8d02875` el AAR es reproducible** (plan §4.3) |
| Textos de la solicitud (OTF, correo de seguimiento, presupuesto y alcance técnico) | ✅ [SOLICITUD-revision-externa.md](SOLICITUD-revision-externa.md). **La tramita el autor** |
| Canal cifrado para recibir los hallazgos | ✅ Clave PGP publicada el 22 sep 2026 ([`pgp-key.asc`](../pgp-key.asc), huella en [SECURITY.md](../SECURITY.md)) |
| Solicitud enviada (fecha, vía, identificador) | ✅ 22 sep 2026, formulario del OTF Security Lab ([apply.opentech.fund/security-lab](https://apply.opentech.fund/security-lab/)). Estado: **OTF Review**. Identificador: **#23596** («Krypta (#23596)») |

### 5.2 Qué pedir, en este orden

1. **Revisión de diseño** del protocolo por un criptógrafo, de pocos días. Es lo que más aporta por
   lo que cuesta. Cubre §5 a §9 de la especificación, las debilidades W-1 a W-14 y los diseños
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
   H-7 arreglado y W-8 verificado contra el transporte el 15 sep 2026 (§8). El mismo día, una
   revisión de diseño independiente (§9): W-6 fijado en un test, W-14 declarado y KCI no prometido.
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

---

## 8. Verificación dinámica independiente (15 de septiembre de 2026)

**Origen.** Un lote de verificación dinámica sobre `a97cbab`, hecho en una copia aislada, con pruebas
temporales que no forman parte de Krypta. El informe no se versiona,
igual que las comparaciones con Signal: lo que se versiona es lo que sale de él. Planteaba dos
observaciones. Las dos se contrastaron primero con el código **sin tocar nada**, y la evaluación y
el plan se discutieron con el autor antes de implementar.

### 8.1 W-8: repetición de frames de llamada

**Lo que decía.** Una prueba descifró dos veces el mismo frame, y concluía que «un relay que duplique
o reordene bytes de un stream puede provocar que el receptor procese de nuevo un frame válido».

**Evaluación.** Que la capa de aplicación no tiene anti-replay es cierto, y ya estaba declarado como
W-8. La prueba no añade nada a la inspección: un AES-GCM sin estado abre dos veces lo mismo por
definición. Lo que no se sostenía era la conclusión sobre el relay. **Tampoco la respuesta inicial de
esta revisión** («TLS o Noise lo impiden»), porque ninguna de las dos estaba comprobada en un stream
relayed. La redacción que se acordó: *impacto limitado si la seguridad de transporte mantiene
autenticidad, orden y anti-replay de extremo a extremo; falta verificarlo en streams relayed*. Y se
verificó:

| Pregunta | Cómo | Resultado |
|---|---|---|
| ¿Un intermediario puede duplicar, reordenar o devolver al emisor bytes de un stream de llamada? | `TestTransporteRechazaBytesManipulados`: un proxy TCP entre dos nodos reales, con un caso de control que no toca nada | **No.** `tls: bad record MAC`: la conexión cae en los dos extremos y nunca se entrega un frame repetido, fuera de orden ni reflejado. El control entrega los dos frames, en orden |
| ¿El cifrado de una conexión por relay termina en el relay? | Código de go-libp2p v0.48: el cliente del circuito pasa la conexión por el mismo upgrader al marcar y al aceptar (`circuitv2/client/transport.go:86` y `:104`) | **No termina en el relay** |
| ¿Y en ejecución? | `TestRelayNoVeLoQueViajaPorElCircuito`: el relay lleva un TLS espía que guarda todo lo que descifra | **No ve** el frame de la llamada ni una negociación de protocolo hecha dentro del circuito. Controles: sí ve su propio protocolo `hop` y un marcador que solo va bajo su TLS |
| ¿La sesión del circuito autentica al otro teléfono o al relay? | `TestRelayMessagingLocal` compara la clave remota de la conexión relayed, en los dos sentidos | Al otro teléfono |
| ¿Qué seguridad se negocia? | Los mismos tests, sobre conexión directa | TLS 1.3 (`/tls/1.0.0`), que go-libp2p ofrece antes que Noise |
| ¿La vía QUIC directa tras DCUtR admite 0-RTT, el caso en que QUIC acepta reenvíos? | Inspección de `p2p/transport/quic` y `quicreuse` | No aparece `ListenEarly`, `DialEarly` ni `Allow0RTT`, y quic-go lo trae apagado. **Sin test** |

Los tres tests de Go pasaron tres veces seguidas, y la suite completa del puente sigue en verde.

**Dos cosas que se aprendieron por el camino**, y que conviene no redescubrir:

- **En una conexión relayed, `ConnState().Security` sale vacío aunque va cifrada.** El cliente del
  circuito envuelve la conexión ya cifrada en un `capableConn` cuyo `ConnState()` solo devuelve el
  transporte (`circuitv2/client/conn.go:160`). La primera versión del test la dio por conexión sin
  cifrar. Consecuencia práctica: **la app no puede comprobar ni mostrar en ejecución** con qué va
  cifrada una conexión por relay. Por eso la prueba mira desde el relay.
- **identify le dice al relay qué protocolos admite cada teléfono**, `/krypta/call/1.0.0` incluido. La
  segunda versión del test lo tomó por una fuga del circuito. La sonda es ahora un protocolo con un
  nonce que no figura en esa lista, y el test fija que el relay ya conoce el nombre de la llamada
  **antes** de abrir ningún stream. Es un metadato menor, porque el nodo ya sabe que habla con un
  teléfono de Krypta, pero es cierto y queda en W-13.

**Qué no se hace.** Un contador por frame y claves separadas por sentido cambian el formato de red, y
§5.4 lo impide hasta tener el informe externo. W-8 queda como pregunta 5 de la especificación, ahora
con la evidencia.

### 8.2 C-001: invite con timestamp futuro, que pasa a ser H-7

**Lo que decía.** Un invite con `ts = ahora + 1 h` timbra; no hay límite superior ni deduplicación
persistente por `callId`. Recomendaba una ventana simétrica `|ahora − ts| ≤ 45 s`.

**Evaluación.** Los hechos, ciertos. Pero:

- el comentario del propio código declaraba la asimetría a propósito, frente a los relojes desfasados;
- el adversario propuesto (un contacto con `S`) no necesita reproducir ni adelantar nada;
- quien reproduce de verdad es el nodo, por el buzón, y no puede tocar el `ts`;
- por v2 la tabla de vistos ya descarta la copia; el informe midió el camino v1 sin decirlo;
- y la ventana simétrica haría perder llamadas a cualquier receptor con el reloj atrasado.

El problema de fondo es la **reproducción de señales de llamada por el camino v1 sin deduplicar por
`callId`**, con un impacto que el informe no llegó a ver: filas de «llamada perdida» sin límite. La
fecha futura es secundaria, pero no irrelevante: amplía la ventana de aceptación, y es lo que acota
cuánto tiene que durar la memoria. Detalle, tests y arreglo en **H-7** (§2).

### 8.3 Tareas y estado

| Tarea | Estado |
|---|---|
| Test de manipulación en tránsito | ✅ `TestTransporteRechazaBytesManipulados` |
| Seguridad del circuito del relay | ✅ `TestRelayNoVeLoQueViajaPorElCircuito` y la autenticación en `TestRelayMessagingLocal`. Leer `ConnState()` resultó imposible (§8.1) |
| Redacción condicionada de W-8 | ✅ especificación §8, §13 y §15.5 |
| Tests que reproducen H-7 antes del arreglo | ✅ seis, y los seis fallaron con el código anterior |
| Memoria de `callId` | ✅ |
| Límite hacia el futuro | ✅ 10 min; pasado, llamada perdida y línea de Diagnóstico |
| Fila de llamada perdida idempotente | ✅ |
| Especificación (§8, P15, W-8, W-13, §14, §15.5) y esta revisión | ✅ |
| Suites y móvil | ✅ JVM 288 tests, 0 fallos; Go del puente en verde; instalado en el TECNO, arranca en frío y dice «conectado» |
| Prueba con dos móviles | ⬜ [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md) §17 |
| Porte a Nyx | ⬜ lo decide el autor |
| Respuesta al informe | ✅ texto en §8.5; la envía el autor |

### 8.4 Qué cambió en el repositorio

- `p2p-signaling`: `CallService` (`firstSighting`, `INVITE_FUTURE_MS`, `INVITE_MEMORY_MS`,
  `SEEN_INVITES_MAX`) y `ChatService` (`recordMissedCall(contact, callId)`, `missedCallId`).
- Tests: `CallServiceTest` (+7), `ChatServiceTest` (+2, 1 adaptado a la firma nueva); en Go,
  `transport_tamper_test.go` y `relay_espia_test.go` (nuevos) y `relay_msg_test.go`.
- Docs: H-7 y esta sección, la especificación, security-model, architecture,
  SOLICITUD-revision-externa, PRUEBAS-PENDIENTES §17 y CLAUDE.md.
- **Sin cambio del formato de red ni del esquema** (la base sigue en v9), y **sin cambio en el AAR**:
  en Go solo se añadieron tests.

### 8.5 Respuesta al informe

> **W-8.** De acuerdo con la redacción condicionada, y ya no queda condicionada a una suposición: el
> 15 sep 2026 se comprobó que un intermediario que duplica, reordena o refleja bytes de un stream de
> llamada provoca `tls: bad record MAC` y la caída de la conexión, sin entregar nada repetido
> (`TestTransporteRechazaBytesManipulados`), y que un relay con un TLS espía no ve ni el frame ni lo
> negociado dentro del circuito, con controles que prueban que sí ve lo que va bajo su propio TLS
> (`TestRelayNoVeLoQueViajaPorElCircuito`). Queda sin test la vía QUIC directa, que por inspección no
> usa 0-RTT. Nota para quien repita la prueba: en go-libp2p v0.48, `ConnState().Security` sale vacío
> en las conexiones relayed aunque van cifradas. W-8 se mantiene abierto como defensa en profundidad:
> un contador por frame cambia el formato de red y queda para después de la revisión externa.
>
> **C-001.** Reformulado como H-7: *reproducción de señales de llamada por el camino v1 sin
> deduplicación por `callId`*. El impacto concreto, además de volver a sonar, era una fila de «llamada
> perdida» y una notificación por cada entrega de un invite rancio. Arreglado sin cambio del formato de
> red: una vez por `(contacto, callId)`, 10 min de margen hacia el futuro y fila idempotente. No se
> adoptó la ventana simétrica de 45 s porque haría perder llamadas legítimas con relojes desfasados.
> Seis tests lo reproducían y fallaban antes del arreglo. La etiqueta `revision-externa-1` no cambia.

---

## 9. Revisión de diseño independiente (15 de septiembre de 2026)

**Origen.** Una revisión del diseño criptográfico sobre `a97cbab`, contrastada con `fe21111`
(no versionada). **No encuentra nada que no estuviera declarado.** Su valor
es confirmar de forma independiente que H-4, H-5 y las debilidades W siguen abiertas, y que H-7
quedó corregido. Cita dos archivos de evidencia que no se entregaron, así que su
matriz de derivaciones y pruebas no se ha podido comprobar. Se contrastó con el código y los
documentos sin tocar nada, y las decisiones las tomó el autor.

### 9.1 Precisiones

| Lo que dice | Lo que hay |
|---|---|
| Quedan abiertas H-4, H-5, W-1, W-2, W-6, W-10, W-12 y W-13 | Faltan **W-7** (sin post-cuántico), **W-11** (el `.krbk` solo lo protege la frase), W-9 y W-8. W-3 y W-5 son H-5 |
| H-5 «afecta también buzón y rendezvous, cuyas etiquetas son recalculables desde `S`» | El buzón sí: el remitente se atribuye por la etiqueta, y eso es KCI. El rendezvous no autentica nada: que sea derivable de `S` es W-10 |
| W-6 afecta a la «unicidad de claves» | Repetir claves exige crear un linaje en el mismo milisegundo que uno anterior. **El efecto real es de disponibilidad**, y no estaba bien escrito (§9.2) |
| La corrección de H-7 «es solo en RAM» | Solo lo es no volver a sonar. La fila de llamada perdida no se repite porque su id va en la base. Lo que queda es un timbre de más tras un reinicio (W-14) |
| La prueba «demuestra que Noise/TLS del circuito relayed rechaza duplicación, orden alterado y reflexión antes de entregar datos» | Se deduce de **dos** tests: la manipulación se probó sobre un tubo TCP directo, y el cifrado de extremo a extremo, sobre el circuito. Se negoció **TLS 1.3**, no Noise. Y el frame genuino sí se entregó, una vez: lo que nunca se entregó fue la copia |

Y un punto en el que **tenía razón y obligaba**: «no deben presentarse como propiedades
demostradas». El README anunciaba sin matices un «doble ratchet por épocas con secreto hacia
adelante», en contra de security-model, que no lo cuenta aún como garantía, y de la regla de no
prometerlo antes de la prueba en vivo; además, la época 0 no lo tiene. **Corregido.**

### 9.2 W-6, con su efecto real

Estaba escrito como «un reloj que retrocede podría reutilizar un linaje, y un linaje nuevo con el
reloj por detrás del viejo no se adopta». Lo primero es despreciable, y lo segundo no decía qué pasa.
Lo fija ahora `RatchetTest` → `tras perder el estado con el reloj atrasado un sentido queda roto y no
se arregla solo`. Si B pierde el estado con el reloj por detrás del linaje vigente de la pareja:

- **B → A llega**, por la época 0 derivable, pero A no adopta un linaje menor, así que B **no sale
  nunca de la época 0**;
- **A → B se pierde**: va en una época > 0 del linaje vigente, que B ya no tiene. El reengache no
  ayuda, porque quien reengancha es B, desde su linaje menor;
- **corregir el reloj no lo arregla**, porque el linaje se fija al crear la sesión. Lo arregla un
  linaje nuevo de A (borrar y volver a añadir el contacto).

El caso realista no es un reloj que retrocede en marcha. Es restaurar con la fecha mal puesta, o que
el linaje vigente lo creara un móvil con el reloj adelantado.

**Un linaje monótono duradero no se hace ahora**:

- toca la regla del linaje, congelada por §5.4;
- no cubriría la importación de un `.krbk` en un móvil nuevo, salvo que el último linaje viajara en
  el respaldo;
- y el rediseño de H-4 puede absorberlo.

Se documenta el límite en la especificación (§5.7 y W-6), en DISENO-ratchet §1.6 y, para el usuario,
en security-model §9.13.

### 9.3 Decisiones

1. **KCI y negación.** La resistencia a KCI **no se promete** hasta la revisión externa: se declara
   como algo que Krypta no protege (security-model §9.11, especificación W-3), no como algo a medio
   arreglar. Security-model decía «No hay negación … por diseño», lo que chocaba con H-5 («se pierde
   la negación, que Krypta no promete pero tampoco ha decidido tirar»). Queda escrito lo que hay: los
   sobres no van firmados y son negables de hecho, pero **no se promete**. Firmarlos la perdería, y
   eso se decide con la revisión.
2. **Lo que queda de H-7 se acepta y se declara como W-14**, en vez de dejarlo solo como condición de
   P15. Persistir la memoria de timbre tenía tres caminos, y ninguno compensa un timbre de más tras
   un reinicio:
   - en SharedPreferences, dejaría metadatos de llamadas fuera de SQLCipher;
   - reutilizando la tabla de vistos del ratchet, tocaría un componente que está en revisión (P7);
   - con una tabla nueva, obligaría a una migración a v10.
3. **El análisis de H-4 se aplaza.** Un documento con las candidatas sería útil para quien revise,
   pero corre el riesgo de condicionarle. Se escribe cuando la revisión arranque.
4. **Tras la revisión, sin cambios** (§6.7): rediseño del linaje (H-4, que puede absorber W-6), firma
   de sobres (H-5) y un linaje monótono duradero.

### 9.4 Qué cambió en el repositorio

- Test: `RatchetTest` (+1, fija W-6).
- Docs:
  - README, que ya no presenta el secreto hacia adelante como garantía;
  - especificación: §5.7, W-3, W-6, W-8, W-14 (nueva), P15, §15.2 y §15.4;
  - DISENO-ratchet §1.6;
  - security-model: la negación, §9.11, §9.13 (nuevo) y las llamadas;
  - SOLICITUD-revision-externa, esta sección y CLAUDE.md.
- **Sin cambios de código de producción, formato de red ni esquema.**

### 9.5 Respuesta a la revisión

> Gracias: coincide con lo declarado, y confirma de forma independiente que H-7 quedó corregido. Cinco
> precisiones: (1) faltan en la lista W-7 y W-11; (2) el rendezvous no es un problema de KCI sino de
> que se pueda ligar a la pareja (W-10); (3) W-6 no compromete la unicidad de claves en la práctica,
> pero sí la disponibilidad —tras restaurar con el reloj atrasado un sentido se pierde y no se
> recupera solo—, lo que ahora está fijado en un test y documentado; (4) en H-7 solo el timbre está
> en RAM, y la fila de llamada perdida es persistente; el resto se acepta y se declara como W-14;
> (5) lo de W-8 se deduce de dos pruebas distintas, se negoció TLS 1.3 y el frame genuino sí se
> entregó una vez. Aplicado: el README ya no presenta el secreto hacia adelante como demostrado, y
> se declara que la resistencia a KCI no se promete. H-4, H-5 y un linaje monótono duradero quedan
> para después de la revisión externa, por la congelación del formato. Los archivos de evidencia que
> cita no llegaron.

### 9.6 Retest, H-5 fijada y un fallo de higiene

**Retest de H-4, H-5 y W-6** (no versionado) sobre `f8d9a75`. Se
contrastó y es correcto:

- los tres tests que cita existen con esos nombres;
- la suite JVM, relanzada, dio **289 tests, 0 fallos**;
- la suite Go del puente pasa con `-race`.

Un matiz en su conclusión: decía que las pruebas «validan que las limitaciones están reproducidas»,
y eso valía para H-4 y W-6, que tenían un test que las fijaba, pero **no para H-5**, que solo estaba
declarada. **Ahora H-5 también está fijada** (§2, H-5).

**Un fallo propio, encontrado al escribir ese test.** El commit `fe21111` metió **tres bytes NUL
literales** en el código fuente, en [CallService.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/CallService.kt)
y [ChatService.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt): los separadores de
`(contacto, callId)` se escribieron como escape, y la herramienta de edición los guardó como el byte.

- **No cambió el comportamiento**: en una cadena de Kotlin un NUL literal es el mismo carácter que
  su escape, y los tests pasaban.
- **Pero `grep` trataba los dos archivos como binarios y no encontraba nada en ellos.**
  `ChatService.kt` entra en el alcance de la revisión externa, y quien lo recorriera con `grep`
  no habría visto ni una coincidencia.
- **Git no lo avisó**: solo mira los primeros 8000 bytes para decidir si un archivo es binario, y
  los NUL estaban en las líneas 592 y 1648.

Se cambiaron por su escape de texto, y un test nuevo (`el id de la fila de llamada perdida es el de
la especificacion`) calcula aparte, byte a byte, el id de §8 de la especificación: garantiza que el
valor no cambió y que ningún cambio en cómo se escribe el separador puede alterarlo sin que se note.
Ningún otro archivo de texto versionado tiene bytes NUL.

### 9.7 Registro de hallazgos de la revisión independiente

**Origen.** Registro de hallazgos y estado de retest de la misma revisión independiente, sobre
`a97cbab`, con las correcciones verificadas hasta `ebf2d43` (no versionado). **Es fiel en lo
esencial**: sus estados coinciden con los de esta revisión, y los hashes que cita son correctos. La
respuesta completa se entregó a quien hizo la revisión (tampoco versionada); en resumen, pide:

1. **Añadir las limitaciones abiertas que faltan**: W-7 (sin post-cuántico, la ausencia más seria),
   W-11 (el `.krbk` solo lo protege la frase, que es además la vía hacia H-5) y W-9. Ya se había
   señalado en §9.5 y no se recogió.
2. **Completar W-13**, que dejaba fuera el grafo diario de parejas en la DHT y la lista de protocolos
   que revela identify.
3. **Precisar W-8**, que repetía la imprecisión de §9.1: la conclusión sale de dos pruebas distintas,
   se negoció TLS 1.3 y la vía QUIC directa no tiene test.
4. **Citar la evidencia concreta**: el test de H-6, y los que fijan H-4, H-5 y W-6, para distinguir
   las limitaciones reproducidas de las solo declaradas.
5. **Recoger el coste de las recomendaciones** de W-12 (cambia el formato de red y todos los secretos
   compartidos) y W-6 (un linaje monótono duradero no cubre la importación de un `.krbk`), y que H-4,
   H-5, W-6 y W-12 están congeladas por §5.4.
6. **Anotar el fallo de higiene de §9.6** (los bytes NUL), ya que el registro se verifica hasta el
   commit que lo corrigió.

Se aceptan sin reparos: C-001 absorbido por H-7 y W-14, las severidades de H-4 y H-5, llamar a H-7
«corregido parcialmente» (aquí figura como corregido, con lo restante declarado como W-14), la
separación entre la procedencia del AAR de `revision-externa-1` y la reproducibilidad posterior, y su
criterio de cierre.
