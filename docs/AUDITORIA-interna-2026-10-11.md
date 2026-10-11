# Auditoría interna del diseño criptográfico (11 oct 2026)

**Qué es.** La respuesta de Krypta a una auditoría **interna** del diseño criptográfico del
protocolo de sesión, sobre el commit `9017e89`, y el plan para corregir lo que encontró.
**No es una auditoría independiente ni externa.** La hizo el propio proyecto, con herramientas de
IA, sobre un código que también se escribió con IA (§4 explica por qué eso importa). Krypta
**no ha tenido revisión externa**.

**Cómo leerlo.** Los identificadores **A-01 … A-09** son de esta auditoría. Donde coinciden con
algo ya declarado se dice (H-x de [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md),
W-x y P-x de la [especificación](ESPECIFICACION-protocolo.md)). Cada hallazgo nuevo tiene un test en
el repositorio que lo reproduce (§1). El estado de cada fase del plan está en §7.

**Cómo refutarlo.** Si algo de aquí está mal, o falta algo, el canal es el de
[SECURITY.md](../SECURITY.md).

---

## 0. Resumen

- **Se acepta el dictamen.** El diseño no cumple todas las propiedades que declara, y P4 está mal
  escrita.
- **Hallazgos nuevos de verdad: dos, más dos precisiones.**
  - **A-01, nuevo y alto.** Confirmado por lectura del código y **reproducido con un test propio**,
    construido desde la especificación y no desde el informe (§1).
  - **A-02, nuevo en su impacto, no en su precondición.** W-6 ya decía que repetir claves exigía
    crear un linaje en el mismo milisegundo, pero lo enterraba en una debilidad de disponibilidad, y
    P3 no lo recogía bien. También **reproducido**.
  - **A-07, precisión nueva.** P9 promete más de lo que da: la resistencia a degradación solo
    protege lo ya aprendido, no la primera negociación.
  - **A-09, precisión nueva en dos puntos de código:** las mitades de clave de llamada no se validan
    en longitud, y la caída a la clave estática es silenciosa incluso con un contacto que negocia.
    Confirmado por lectura.
- **Ya conocidos y revalidados:** A-03 (H-4/W-4), A-04 (H-5/W-3/W-5), A-05 (W-2), A-06 (W-6) y
  A-08 (W-10/W-13).
- **Nada del informe se discute en el fondo.** Hay matices de alcance en A-01 (§2), que no le bajan
  la severidad.

---

## 1. Verificación propia

Regla del proyecto: ningún hallazgo se da por bueno sin un test que lo reproduzca.

| Hallazgo | Cómo se comprobó | Resultado |
|---|---|---|
| A-01 | `RatchetCompromisoTest` → `con el estado guardado se reabre lo ya enviado en la epoca vigente`. A llega a época ≥ 1, envía dos mensajes sin recibir nada, B los recibe, y del estado de A **serializado** (`encode`/`decode`) solo se usan `rootKey`, `epoch` y `sendDir`. La cadena se rederiva con las etiquetas `info` de la especificación §5.2, a mano, y AES-GCM abre los dos mensajes | **Pasa**: el debilitamiento existe |
| A-02 | `RatchetCompromisoTest` → `repetir un linaje repite clave y nonce en la epoca 0`. Dos sesiones con el mismo linaje y dirección, cada una con su par efímero; el XOR de los cuerpos cifrados es el XOR de los textos | **Pasa**: el debilitamiento existe |
| A-09 | Lectura de `CallService.negotiatedKey` y de `onInvite`: devuelve `null` si falta una mitad, sin comprobar 32 bytes, y `onInvite` cae a `deriveCallKey` sin mirar `peerProtocol`. `MessageEnvelope.fromHex` acepta cualquier longitud par | Confirmado |
| A-07 | Lectura de `announceCapabilities`: se marca anunciado cuando sale (directo o buzón), no cuando el otro lo confirma. Un contacto nuevo empieza como v1 | Confirmado |

Los dos tests **fijan lo que hay, no lo que se quiere**, como el del linaje forjado en `RatchetTest`.
Al arreglar cada debilidad, su test tiene que fallar y darse la vuelta. Están en
[`RatchetCompromisoTest.kt`](../p2p-signaling/src/test/java/chat/neto/krypta/p2p/RatchetCompromisoTest.kt).

---

## 2. Respuesta por hallazgo

### A-01 — La raíz guardada reabre la época vigente · **Aceptado, alto**

`advance` guarda `rootKey = RK(e)` y deriva `sendChain` y `recvChain` como
`HKDF(RK(e), "chain:e:dir")`, es decir, desde N = 0 y desde la misma raíz que queda en disco. En el
doble ratchet de Signal la raíz nueva y la cadena son **salidas independientes** del KDF de raíz, y
la raíz no deja volver a la cadena.

**Alcance exacto, para escribirlo bien** (no rebaja la severidad):

- Lo expuesto es lo que el móvil comprometido **envió en su época vigente**, y lo que **recibió**
  en ella, si recibió algo.
- La época avanza **por mensaje recibido**: el receptor de un mensaje que trae la propuesta del otro
  pasa enseguida a la época siguiente, y su copia de la época anterior queda en `past` en la
  posición ya alcanzada, no desde 0. Así que en una conversación de ida y vuelta la ventana es
  pequeña.
- **En una ráfaga sin respuesta la ventana es entera**: un archivo por trozos (cientos de mensajes),
  varias notas de voz seguidas o textos a un contacto desconectado. Ahí está el caso grave.
- Las raíces anteriores no se rederivan desde la actual (HKDF no se invierte), así que el
  compromiso no se propaga a épocas pasadas.

**Qué se hace:**

1. **Hoy, en la documentación:** P4 pasa a decir que quedan fuera la época vigente del dispositivo
   comprometido, además de la época 0 y lo guardado en `skipped`/`past`. Nueva debilidad **W-15**.
   `security-model.md` §«Lo que NO se garantiza» y el README lo recogen.
2. **Arreglo, con cambio de formato** (protocolo v4, fase 2 del plan): el KDF de raíz da tres
   salidas independientes, `RK(e) ‖ CK(e,0) ‖ CK(e,1) = HKDF(dh, salt = RK(e−1), info, 96)`, y la
   raíz no vuelve a usarse para derivar cadenas. Para la época 0 vale lo mismo partiendo de `S`
   (sigue sin PFS, pero el código queda con una sola forma de derivar). Cambiar solo las etiquetas
   `info` no lo arregla, y el informe tiene razón en advertirlo.
3. **Cómo entra sin romper a nadie** (propuesta, a cerrar en el diseño): la derivación de la época
   `e+1` la decide un bit de flags de la cabecera, que es AAD, en las **dos propuestas** que forman
   esa época. Si ambas lo traen, se usa el KDF nuevo; si no, el viejo. Los dos lados evalúan la
   misma condición sobre los mismos datos, que es lo que ya hace el ratchet para decidir cuándo se
   avanza. Las sesiones en curso pasan solas en el siguiente avance, sin migración de estado.

### A-02 — Repetir un linaje repite clave y nonce · **Aceptado, medio**

La precondición (crear una sesión con un linaje que esa identidad ya usó, al milisegundo) ya
aparecía en la última frase de W-6. **El informe acierta en que ahí estaba mal colocada**: W-6 se
describe como un problema de disponibilidad, y P3 dice estar «condicionada a W-6» sin decir que la
condición, si falla, es una reutilización de nonce de AES-GCM.

**Matiz que conviene añadir:** con `allowBackup=false` y la base cifrada por una clave del Keystore,
restaurar o clonar el estado exige root, que está fuera del modelo (§1 de la especificación). El
caso realista es un reloj que vuelve **exactamente** al mismo milisegundo, que es improbable pero
no imposible. **El retroceso del estado a una época avanzada** (un snapshot viejo de la base) da la
misma reutilización en épocas ≥ 1, y también exige root. Se declara igualmente.

**Qué se hace:**

1. **Hoy:** debilidad propia **W-16** (separada de W-6) y P3 reescrita con la condición explícita.
2. **Arreglo en el mismo v4**, sin campos nuevos: la cadena de envío de la época 0 se ata a la
   **propuesta efímera del emisor**, que ya viaja en la cabecera (`next_pub`) y es nueva en cada
   sesión: `CK(L, 0, dir) = HKDF(RK(L, 0), "chain:0:dir" ‖ next_pub_emisor)`. El receptor la deriva
   al abrir, con lo que trae la cabecera. Dos sesiones con el mismo linaje dejan de compartir
   cadena. Hay que comprobar con la prueba de propiedades que no rompe `openOld` ni el reenganche.

### A-03 — Secuestro por linaje superior · **Conocido (H-4/W-4), aceptado**

Nada que añadir al diagnóstico. El caso de `Long.MAX_VALUE`, que deja el linaje fuera del alcance de
cualquier reloj honrado, merece su propio test (fase 1). La corrección va en la fase 3, junto con
A-05 y A-06, porque los tres son la misma regla. Coincidimos en que acotar el linaje a «ahora +
margen» reduce el bloqueo, pero no autentica a nadie.

### A-04 — Autenticación a nivel de `S`, v1 y KCI · **Conocido (H-5/W-3/W-5), aceptado**

Se mantiene la declaración de que **KCI no se garantiza**. Lo que sí se puede hacer sin rediseñar:
dejar de aceptar v1 de un contacto cuyo `peerProtocol` sea ≥ 2 (cierra W-5). El informe advierte,
con razón, que solo esto compra poco, porque quedan la época 0 y el linaje nuevo. Va en la fase 3,
con el resto de la autenticación.

### A-05 — Replay de época 0 fuera de la ventana · **Conocido (W-2), aceptado**

**Mitigación barata, sin tocar el formato (fase 1):** las huellas de los sobres de **época 0** no
se podan nunca. El tráfico honrado en esa época es poco, porque la época avanza con el primer mensaje
recibido. Necesita una columna en `ratchet_seen` (base **v10**, con su migración y su test SQL
en la JVM, como `RatchetSeenPruneSqlTest`). Rechazar la época 0 de linajes retirados queda para la
fase 3, porque hoy es la única vía que funciona en el caso de W-6.

### A-06 — Recuperación con el reloj atrasado · **Conocido (W-6), aceptado**

La reparación explícita para el usuario ya está en la ayuda («Problemas frecuentes»). El rediseño va
en la fase 3.

### A-07 — Degradación en la primera negociación · **Aceptado, medio**

Cierto: P9 promete «la red no puede degradar una pareja», y eso solo vale una vez aprendida la
versión. Un nodo que tire los `V` iniciales, que en v1 se distinguen por tamaño, mantiene a la
pareja en v1 mientras no llegue nada v2.

**Qué se hace:**

1. **Hoy:** P9 reescrita con su alcance real.
2. **Fase 1, en la interfaz:** mostrar en el chat y en «Verificar identidad» qué protección usa ese
   contacto («clave fija» o «clave que cambia»). Que el usuario vea el modo efectivo es la mitigación
   que el propio informe propone, y no cambia el formato.
3. **Fase 3:** que el anuncio cuente como entregado solo cuando el otro lo confirma, y una política
   opcional por contacto de «exigir protección moderna».

### A-08 — Etiquetas sin secreto retrospectivo · **Conocido (W-10/W-13), aceptado**

Se corrigen los dos comentarios que lo exageran: `RendezvousService.kt` («no correlacionable a largo
plazo») y `MailboxLabel.kt` («para que nada sea correlacionable a largo plazo»). La rotación no
protege frente a quien obtiene la identidad. Los límites de disponibilidad (un emisor con el reloj
adelantado que deposita bajo una semana aún no consultada, el solape de dos horas del rendezvous) se
declaran y se prueban (fase 1).

### A-09 — Llamadas · **Aceptado en dos puntos de código, el resto conocido (W-8/W-14)**

**Fase 1, sin cambio de formato:**

1. Una mitad de clave tiene que medir **exactamente 32 bytes**; si no, la señal se descarta.
2. Con un contacto cuyo `peerProtocol` sea ≥ 2, **no hay caída a la clave estática**: si falta la
   mitad, la llamada no se establece y queda un aviso en el diagnóstico. Todo cliente que anuncia 2
   ya negocia (la fase 7 es del 9 sep y el anuncio `V` del 10 sep), así que no rompe a nadie.

El contador por frame y las subclaves por sentido y por medio cambian el formato: van con el v4 si
se decide rehacer el camino de medios, o después.

### §4 del informe — Borrado y semilla compartida · **Aceptado como redacción**

P4 y la documentación dirán «no se conserva en el estado lógico», no «se borra». La memoria de la
JVM, las páginas libres de SQLite y el WAL no se garantizan. **Comprobado al responder:**
`PRAGMA secure_delete` solo se activa dentro de `MIGRATION_5_6`, para borrar el secreto compartido.
Es un pragma por conexión, así que **las conexiones normales no lo tienen**, y el estado del ratchet
que se reescribe deja sus versiones viejas en páginas libres (cifradas por SQLCipher, pero con la
clave en el mismo dispositivo). Se añade a la fase 1: activarlo en cada apertura de la base y
medir su coste. W-12 se queda como pregunta abierta.

---

## 3. Plan de ajustes

### Fase 0 — Documentación honesta (sin cambio de código de producción; hoy)

- Especificación (ES y EN, en el mismo commit): P3, P4, P7, P9, P12 y P15 con el alcance que da el
  informe (§6 de este); nuevas **W-15** (raíz y época vigente) y **W-16** (linaje repetido);
  §15 con las preguntas nuevas.
- `security-model.md`: el apartado de PFS añade la época vigente.
- README (las dos lenguas): «pensado para dar secreto hacia adelante» se queda, y se añade que hay
  una debilidad conocida y en arreglo.
- Comentarios de `RendezvousService.kt` y `MailboxLabel.kt` (A-08).
- Commit de `RatchetCompromisoTest`.
- Comprobar, por la regla del 18 sep, la política de privacidad, el manual y `HelpContent`. Ninguno
  promete PFS hoy, pero hay que mirarlo.

### Fase 1 — Endurecer sin tocar el formato de red (1 a 2 semanas)

1. Llamadas: mitades de 32 bytes y sin caída estática con contactos ≥ 2 (A-09). Tests en
   `CallServiceTest`.
2. Huellas de época 0 sin poda (A-05). Base v10, migración, `MigrationTest` y test SQL en la JVM.
3. Indicador del modo de protección por contacto (A-07).
4. Test del linaje `Long.MAX_VALUE` (A-03) y tests de disponibilidad de etiquetas con relojes
   desfasados (A-08).
5. `PRAGMA secure_delete = ON` en cada apertura de la base, no solo en la migración a v6 (§2,
   «Borrado y semilla compartida»), con su coste medido en el TECNO.

### Fase 2 — Protocolo v4: KDF de raíz y época 0 con frescura (2 a 4 semanas)

Requisito previo: **levantar la congelación del formato**. La regla del 11 oct decía que, si no se
contrataba una revisión, se levantaba; con la decisión del mismo día de no contratarla, se cumple. Antes
del primer cambio se etiqueta el último commit con el formato v3 (§5, decisión 2: `protocolo-v3`).

1. Diseño escrito primero, en `DISENO-ratchet.md`: KDF de tres salidas (A-01), cadena de época 0
   atada a la propuesta del emisor (A-02), bit de activación en las dos propuestas,
   `KDF4_MIN_PROTOCOL = 4` y `PROTOCOL_VERSION = 4`. Recordar la regla del 11 sep: **una versión
   nueva no es el umbral de nada; cada capacidad tiene el suyo**.
2. Los dos tests de `RatchetCompromisoTest` se dan la vuelta: con el estado robado **no** se abre lo
   ya enviado, y dos sesiones con el mismo linaje **no** comparten flujo. Se añaden casos al cruzar
   cada transición (antes y después del avance) y una sesión mixta v3/v4.
3. `RatchetPropertyTest` con el KDF elegido al azar por época, para que el caos cubra la
   transición.
4. Dos móviles: una conversación v3↔v4 y otra v4↔v4 (PRUEBAS-PENDIENTES).

### Fase 3 — Linaje, recuperación y autenticación (diseño primero, sin fecha)

A-03, A-04, A-05 (rechazo de época 0 retirada), A-06 y A-07 (confirmación del anuncio) son una sola
máquina de estados. Antes de escribir código:

1. **Modelo formal** en Tamarin, en `docs/formal/`, como pide REVISION §4: M1 (núcleo) y M2 (linaje).
   M2 tiene que encontrar H-4 por sí solo. Además, que **M1 encuentre A-01**: es la mejor prueba de
   que el modelo modela lo que creemos. Los modelos ProVerif del auditor no tienen épocas DH ni
   compromiso en el tiempo, como el propio informe dice.
2. Con el modelo, elegir entre las candidatas de la especificación §15.2 y la del informe (frescura
   aleatoria en la recuperación, transición autenticada, confirmación del usuario ante una
   sustitución).

### Fase 4 — El documento público de la auditoría

Ver §4 y §5.

---

## 4. Qué vale esta auditoría, y qué no

Lo que la hace seria no es el nombre, sino que **cualquiera pueda comprobarla**.

- **Lo que no puede decir:** «auditoría independiente» ni «auditoría externa». La hizo el propio
  proyecto, con herramientas de IA, sobre un código que también se escribió con IA. Eso tiene un
  sesgo concreto: **puntos ciegos correlacionados**. Lo que se le escapó al que escribió el código
  puede escapársele igual al que lo revisa. El README seguiría diciendo que no ha habido revisión
  externa, porque es verdad.
- **Lo que sí la hace creíble:**
  1. Cada hallazgo con un test en el repositorio que lo reproduce, y que falla y se da la vuelta al
     arreglarlo. Ya es la costumbre del proyecto.
  2. La evidencia ejecutable **dentro del repositorio**, no en una carpeta privada. Los contraejemplos
     y los modelos formales tienen que poder correrse desde un clon.
  3. Publicar también lo que salió mal: A-01 es el mejor argumento de que la revisión es seria,
     porque refuta una propiedad que el proyecto afirmaba.
  4. Método y límites escritos (qué no se miró), y quién la hizo, con nombre.
  5. Una invitación explícita a refutarla, por el canal de `SECURITY.md`.
- **Quién la hizo:** 4000MSNM S.R.L., el equipo de Krypta, con asistentes de IA tanto para auditar
  como para responder.

---

## 5. Decisiones tomadas (11 oct 2026)

1. **Se publica** este documento en `docs/`. Los contraejemplos están en el repositorio
   (`RatchetCompromisoTest`), no en una carpeta privada.
2. **Etiqueta** del último commit con el formato v3: `protocolo-v3`. No habrá `revision-externa-2`,
   porque no habrá revisión externa.
3. **Orden**: fases 0, 1 y 2 ahora; la 3 después del modelo formal.
4. **Llamadas**: el contador por frame y las subclaves por sentido van después del v4.

---

## 6. Dictamen por propiedad, como quedaría escrito

| Propiedad | Redacción nueva propuesta |
|---|---|
| P3 | Ninguna terna `(L, e, N)` se repite para un emisor **mientras ningún linaje se repita** (W-16) **y el estado no retroceda** (exige root). Si falla, es reutilización de nonce de AES-GCM |
| P4 | Secreto hacia adelante para épocas ≥ 1, **salvo**: la época 0 (W-1), **la época vigente del dispositivo comprometido** (W-15) y lo que siga en `skipped`/`past`. «Descartar» significa no conservar en el estado lógico, no borrado físico |
| P5 | Afirmada, sin demostrar. Requiere privadas nuevas que el atacante no conozca en los dos lados |
| P7 | Una vez dentro de la ventana; la época 0, sin límite tras la fase 1 |
| P9 | La versión **aprendida** no baja. La primera negociación es oportunista: suprimir los anuncios mantiene a la pareja en v1 (A-07). El usuario ve el modo efectivo tras la fase 1 |
| P11 | Mismo tamaño dentro de cada tramo, no entre tramos |
| P12 | La clave de llamada no sale solo de la identidad; su secreto hacia adelante es el de la señalización que lleva las mitades |
| P15 | Sin cambios (ya condicionada por W-14) |

---

## 7. Estado de ejecución

| Fase | Estado |
|---|---|
| 0 · Documentación | 🟡 En curso (11 oct 2026) |
| 1 · Endurecer sin cambiar el formato | ⬜ |
| 2 · Protocolo v4 | ⬜ |
| 3 · Linaje, recuperación y autenticación | ⬜ Diseño y modelo formal primero |
