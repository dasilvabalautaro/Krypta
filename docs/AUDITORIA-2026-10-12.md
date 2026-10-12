# Auditoría de arquitectura, patrones y código — Krypta

**Fecha:** 12 de octubre de 2026
**Commit auditado:** `0c054eb` (rama `main`, árbol limpio)
**Alcance:** los cinco módulos Gradle, el puente Go (`native-bridge/libp2p`), el nodo
(`infra/node`) y la coherencia entre lo que prometen los documentos de objetivos y lo que hace
el código. Es la continuación de la [auditoría del 7 sep](AUDITORIA-2026-09-07.md), cuyo plan
se ejecutó (§9 de aquel informe), y no repite la del diseño criptográfico del
[11 oct](AUDITORIA-interna-2026-10-11.md).

> **Qué es y qué no es.** Es una auditoría **interna**: la hace el mismo equipo que escribe el
> código, con IA a los dos lados. Tiene los mismos puntos ciegos correlacionados que el resto
> del proyecto y **no sustituye a una revisión independiente**, que sigue sin existir.

---

## 1. Método

Lectura de: `ChatService` completo, `RatchetSessions`, `ContactDao` y repositorios de `:data`,
`DataModule`, `Libp2pNode`, el manejador de mensajes y el filtro de conexiones del puente Go,
`ChatViewModel`, `IncomingNotifier`, `KryptaNotifications`, `HeartbeatReceiver`,
`KryptaApplication`, el manifiesto y las partes de `ChatScreens.kt` que deciden qué se envía.
Lectura en diagonal del resto de la UI, de `CallService` y del nodo (cubiertos por auditorías
anteriores). Contraste con `PLAN-senalizacion-descentralizada.md`, `PLAY-STORE.md`,
`PRUEBAS-PENDIENTES.md`, `architecture.md` y `security-model.md`.

| Comprobación | Resultado |
|---|---|
| `./gradlew testDebugUnitTest` | ✅ **320** tests, 0 fallos (246 `:p2p-signaling`, 49 `:app`, 18 `:data`, 7 `:native-bridge`) |
| `go vet ./...` (puente y nodo) | ✅ sin avisos |
| `go test -race ./...` en `native-bridge/libp2p` | ✅ verde |
| `go test ./...` en `infra/node` | ✅ verde |
| **Sondas temporales** en `ChatServiceTest` (dos, retiradas después) | ❌ confirman O-1 y O-2 (§4) |
| Instrumentados | No ejecutados: desinstalan la app y borran la identidad del móvil del autor |
| `:app:lint` | No ejecutado |

Las sondas se añadieron a `ChatServiceTest`, se ejecutaron y se retiraron con
`git checkout`; el árbol quedó limpio. Lo que midieron está en cada hallazgo.

---

## 2. Veredicto

El proyecto sigue haciendo lo que dice, y en un mes ha cerrado casi todo lo que la auditoría
de septiembre dejó abierto: cifrado en reposo completo, ratchet con relleno y KDF v4, filtro de
conexiones, buzón ciego, límites de retirada, migraciones probadas, modelos formales. La
disciplina de pruebas es alta (sondas que fallan antes del arreglo, tests de propiedades,
fuzzing de parsers, SQL probado en JVM) y la documentación registra hasta las omisiones.

Esta auditoría encuentra **dos defectos confirmados que contradicen una garantía escrita**, y
el primero es grave:

1. **O-1: la garantía «ack solo de lo persistido» del buzón ya no se cumple en el camino del
   ratchet**, que es el de todo contacto con protocolo ≥ 2, o sea, casi todo el tráfico real. Si
   falla el guardado de un sobre de ratchet, el error se traga, el sobre se confirma al nodo y
   el mensaje **se pierde en silencio**. Es la misma familia de fallo que costó las notas de voz
   del 5 de julio, y el test que la vigila solo cubre el camino v1.
2. **O-2: el acuse de lectura crece con todo el historial.** Con 2.000 mensajes recibidos, abrir
   el chat manda 74 KB (más de los 64 KiB que admite el buzón), cada mensaje nuevo vuelve a
   mandar 55 KB, y tras cada reinicio del proceso se repite el envío completo. Además deja ver al
   nodo el tamaño del historial, que es justo lo que el relleno intenta ocultar.

Lo demás es de severidad media o baja: una carrera que puede deshacer una verificación o un
bloqueo (O-3), el texto descifrado saliendo por las notificaciones sin documentarlo (O-4), y la
entrega directa, que marca «enviado» sin ninguna confirmación del otro lado (O-7). En el plano
de los objetivos, el bloqueante de entrega en segundo plano **volvió a reproducirse el 3-4 oct**
y `PLAY-STORE.md` no lo refleja, con la app en la última verificación antes de producción (O-6).

Ningún hallazgo permite a un tercero leer mensajes.

---

## 3. Objetivos: qué cambió desde el 7 de septiembre

Solo el delta. El estado por fases del plan está en el informe anterior, §3.

| Objetivo | Estado al 12 oct |
|---|---|
| A-1…A-12 de la auditoría del 7 sep | ✅ cerrados (ver su §9) |
| A-5 — secreto hacia adelante | 🟡 en el código (ratchet v4); probado con dos móviles solo en conversación normal (PRUEBAS §16, §19 pendientes) |
| A-13 — `:app` depende de clases concretas | ⬜ sin cambios (ver §5) |
| A-14 — `ChatService` clase-Dios | ⬜ **peor**: 1.012 → 2.035 líneas (ver §5) |
| Paginación del historial (parte diferida de A-2) | ⬜ sin hacer; ahora pesa más (O-9) |
| Fase 0 — gate de NAT con dos SIM (% DCUtR vs relay) | ⬜ sin medir (PRUEBAS §10, bloqueado por hardware) |
| **Promesa central: entrega con la app cerrada** | ❌ **falla en el móvil del autor**: 8 h sin ciclo WAN la noche del 3-4 oct; la prueba de no regresión que `PRUEBAS-PENDIENTES` §13 exige antes de publicar no se ha hecho (O-6) |
| Revisión externa del protocolo | Descartada el 11 oct; sustituida por auditorías internas publicadas |

**Lectura:** el producto y la criptografía avanzan mucho más rápido que la verificación en
dispositivos reales. El riesgo dominante ya no es el diseño, es la **fiabilidad de entrega**: los
dos hallazgos confirmados y el bloqueante de §13 son, los tres, mensajes que no llegan sin que
nadie se entere.

---

## 4. Hallazgos

Ordenados por severidad. Los identificadores `O-n` son de este informe, para no chocar con los
`A-n` (septiembre), `A-0n` (auditoría criptográfica), `H-n` y `W-n`.

### O-1 (Alto, confirmado) — El camino del ratchet confirma al nodo lo que no pudo guardar

**Dónde:** [ChatService.kt:1296](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt#L1296)
(`openRatchet`).

```kotlin
val recibido = runCatching {
    sessions.receive(contact, ciphertext, kdf4(contact)) { plain -> persistEnvelope(...) }
}.getOrElse { return openLegacy(contact, ciphertext, mailboxId, ts, parecíaRatchet = true) }
```

El `runCatching` envuelve **también la persistencia**, que va dentro de `sessions.receive`. Si
Room lanza (disco lleno, base bloqueada) o `DiskFileStore.onChunk` lanza (fallo de E/S al poner
un trozo en staging), la transacción se deshace, el error se traga y se cae a `openLegacy`. El
sobre es de ratchet, así que la clave estática no lo abre: se registra «mensaje ilegible», se
devuelve `null` y **`onReceived` termina sin excepción**. El procesador del buzón interpreta eso
como «persistido o descartable», devuelve `true` y el nodo **borra el sobre**.

Consecuencias:

- **Pérdida silenciosa** de cualquier mensaje, trozo de archivo o señal de llamada de un contacto
  con ratchet, justo en las condiciones (disco lleno, proceso moribundo) para las que existe el
  ack-tras-persistir del 5 de julio.
- Un archivo troceado queda incompleto para siempre: el trozo no vuelve.
- Efecto secundario: la caída a `openLegacy` dispara `relayIfBehind`/`rehook`, que emiten
  anuncios `V` por un fallo que no tiene nada que ver con los linajes.

**Evidencia:** una sonda con un contacto en ratchet y `FakeMessages.failOnSave = true` obtiene
`ack=true` del procesador del buzón. Los tests existentes (`mailbox processor acks after
persist…`, `file chunk that fails to stage is redelivered…`) usan un contacto con
`peerProtocol = 0`, así que **solo cubren el camino v1**.

**Corrección:** distinguir «no se pudo abrir» de «se abrió y no se pudo guardar». La segunda
debe propagarse para que el procesador devuelva `false`. Por ejemplo, que `RatchetSessions`
envuelva el fallo de `persist` en un tipo propio (`PersistFailed`) y que `openRatchet` solo caiga
a `openLegacy` ante `RatchetException`. Los dos tests citados deberían repetirse con un contacto
en ratchet, y el primero tiene que fallar contra el código actual.

### O-2 (Medio-Alto, confirmado) — El acuse de lectura escala con todo el historial

**Dónde:** [ChatService.kt:1628](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt#L1628)
(`markConversationRead`) y su disparador en
[ChatScreens.kt:514](../app/src/main/java/chat/neto/krypta/ui/ChatScreens.kt#L514)
(`LaunchedEffect(contact.id, messages.size)`).

El acuse se calcula como «todos los ids entrantes de la conversación, menos los que hay en
`ackedReceipts`». Esa memoria es un LRU de **500** entradas que **no sobrevive al proceso**.

**Medido con la sonda** (2.000 mensajes recibidos, sobre de 37 bytes por id):

| Momento | Tamaño del acuse |
|---|---|
| Primera apertura del chat | **74.029 B** |
| Siguiente llamada (cualquier mensaje nuevo) | **55.529 B**: los 1.500 ids que el LRU ya desalojó |
| Tras reiniciar el proceso | **74.029 B** otra vez |

Consecuencias:

- **No cabe en el buzón** a partir de unos 1.770 mensajes recibidos (el blob máximo es 64 KiB).
  Si el contacto está desconectado, el acuse falla, no se apunta y se reintenta en cada cambio
  de `messages.size`, gastando una clave de ratchet cada vez.
- **Metadato:** por encima de 64 KiB no hay relleno, y entre 4 y 64 KiB el tramo es de 1 KiB.
  El tamaño del acuse le dice al nodo (o al relay) **cuántos mensajes ha recibido el usuario de
  ese contacto**, y se lo repite en cada arranque.
- **Coste en el receptor:** `markOutgoingRead` hace un `findById` por id, **dentro de la
  transacción del ratchet**; unas 2.000 consultas con la base bloqueada.
- **Coste local:** para sacar los ids carga la conversación entera, imágenes incluidas
  (`observeConversation(...).first()`), y lo hace en cada mensaje nuevo con el chat abierto.

**Corrección:** acusar solo lo que estaba sin leer. Hay que leer los ids entrantes en
`DELIVERED` **antes** de `markIncomingRead` (una consulta de ids, sin `payload`), y con eso
`ackedReceipts` sobra. Si se quiere un tope duro, trocear el acuse en lotes de ~100 ids. Un test
que fije el tamaño del acuse tras un reinicio lo protege.

### O-3 (Medio) — Escribir el contacto entero desde una copia vieja puede deshacer una verificación o un bloqueo

**Dónde:** [ChatService.kt:444](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt#L444)
(`announceCapabilities`), `setVerified` y `setBlocked`
([ChatService.kt:1726](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt#L1726)).

El arreglo de H-2 (14 sep) hizo que `peerProtocol` no pueda bajar, pero **solo ese campo**. El
alta sigue siendo un `INSERT OR REPLACE` de la fila completa, así que `verified`, `blocked`,
`displayName` y `announcedProtocol` se escriben tal como vengan en la copia.

`announceCapabilities` lee una instantánea de los contactos, envía un `V` a cada uno (directo y,
si falla, buzón; hasta 8 s de presupuesto para el paso) y luego escribe
`contact.copy(announcedProtocol = …)` **desde esa instantánea**. Si en esa ventana el usuario
verifica al contacto por QR o lo bloquea, la escritura posterior **lo deja otra vez sin verificar
o desbloqueado**, sin ningún aviso. La ventana es real en el caso más natural: `addContact`
llama a `kickWan`, así que el anuncio a un contacto recién añadido sale justo cuando la otra
persona está delante para verificar. También al instalar una versión nueva, porque entonces se
reanuncia a todos.

No lo he reproducido; es lectura de código. **Corrección:** sentencias de una sola columna, como
`raisePeerProtocol`: `markAnnounced(id, v)`, `setVerified(id, b)` y `setBlocked(id, b)`. Así
ninguna operación reescribe campos que no le corresponden.

### O-4 (Medio) — El texto descifrado sale de la app por las notificaciones, y no está documentado

**Dónde:** [KryptaNotifications.kt:105](../app/src/main/java/chat/neto/krypta/KryptaNotifications.kt#L105).

Cada aviso lleva el nombre del contacto y el **texto descifrado** (`MessagingStyle`, últimos 6
mensajes). No se fija `setVisibility` ni `setPublicVersion`, así que se aplica el valor del
sistema, que en la mayoría de móviles muestra el contenido **en la pantalla de bloqueo**. Además,
el texto pasa al `NotificationManager` y de ahí:

- a cualquier app con acceso a notificaciones (`NotificationListenerService`: relojes,
  «asistentes», launchers);
- al historial de notificaciones del sistema, si el usuario lo tiene activado;
- por encima del **bloqueo de la app**: con `AppLock` activo, la persona que tiene el móvil
  bloqueado lee los mensajes entrantes desde la cortina.

`security-model.md` documenta con cuidado las otras salidas del perímetro E2EE (portapapeles,
capturas, «abrir con otra app»), pero **no esta**, que es la más frecuente. Y no hay ajuste para
ocultar el contenido. Signal lo tiene con tres opciones: nombre y mensaje / solo nombre / nada.

**Corrección:** como mínimo, un `setPublicVersion` sin contenido («Mensaje nuevo») para la
pantalla de bloqueo, y una línea en `security-model.md` y en la ayuda. Mejor: el ajuste de tres
opciones, con el modo «solo nombre» forzado cuando `AppLock` está activo.

### O-5 (Medio) — El latido puede exceder los plazos del sistema

**Dónde:** [HeartbeatReceiver.kt:42](../app/src/main/java/chat/neto/krypta/HeartbeatReceiver.kt#L42)
y [ChatService.kt:494](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt#L494).

El receptor pide un `PARTIAL_WAKE_LOCK` de **25 s** (`WAKELOCK_MS`) y llama a `goAsync()`. Pero
`pollOnce` puede tardar hasta **105 s** (30 s de `start` + 30 s de DHT + 45 s de buzón):

- pasados los 25 s, el wake lock caduca y la CPU puede dormirse a mitad de la retirada;
- un broadcast en segundo plano tiene unos 60 s para llamar a `finish()`. Pasado ese plazo, el
  sistema lo trata como ANR, y en segundo plano eso suele acabar con el proceso muerto. Justo lo
  contrario de lo que el latido intenta.

Solo pasa en el peor caso (red colgada), pero ese es precisamente el escenario del latido.
**Corrección:** que el presupuesto total de `pollOnce` quede por debajo del wake lock y de ~50 s
(por ejemplo 10 + 15 + 20 s), o pasar el trabajo al FGS, que el receptor ya relanza.

### O-6 (Medio, objetivos) — El bloqueante de entrega en segundo plano volvió y `PLAY-STORE.md` no lo dice

`PRUEBAS-PENDIENTES` §13 registra que la noche del 3 al 4 de octubre el TECNO pasó **8 horas sin
un solo ciclo WAN**, con 43 disparos del latido en 13 días donde tocaban unos 9.000. Y concluye
que «la muestra buena del 10 sep no se sostiene». Sin embargo, el recuadro de bloqueante de
[PLAY-STORE.md](PLAY-STORE.md) termina en «✅ 10 sep 2026: funcionó en el TECNO», y la app está
en la **última verificación antes de salir a producción** (11 oct).

Las propias reglas del proyecto dicen que, antes de publicar, hay que (a) repetir la medición de
CPU con la app cerrada (§13, «Prueba de no regresión», «obligatoria») y (b) confirmar en 2-3 móviles de otras
marcas si el fallo es de Transsion o general. No consta ninguna de las dos. Detalle menor: la
lista de esa sección está numerada 1, 2, 3, 2, 3, 4.

**Corrección:** actualizar el recuadro de `PLAY-STORE.md` con la regresión de octubre y decidir
de forma explícita si la salida a producción espera a (a) y (b). Si no espera, al menos que la
ficha y la ayuda avisen de que en algunos móviles los mensajes llegan al abrir la app.

### O-7 (Medio) — La entrega directa marca «enviado» sin que el otro lado confirme nada

**Dónde:** [bridge.go:428](../native-bridge/libp2p/bridge.go#L428) (`SendMessage`) y el manejador
de entrada justo encima.

`SendMessage` escribe, hace `CloseWrite` y vuelve: el emisor marca `SENT` cuando los bytes
salieron hacia el multiplexor, sin leer respuesta. En el receptor, el manejador Go entrega los
bytes a Kotlin, que los mete en un `Channel` y los persiste **después**, de forma asíncrona.
Entre medias, si la conexión se cae o el proceso del receptor muere, si el guardado falla (O-1
por el camino directo) o si el receptor no reconoce el PeerID (contacto borrado), **el mensaje se
pierde y el emisor no lo sabe**: no hay reintento ni caída al buzón.

El buzón sí tiene ack-tras-persistir desde julio. El camino directo, que es el preferido, nunca
lo tuvo, y `architecture.md` y `security-model.md` no lo dicen. Es una garantía asimétrica que
el usuario no puede distinguir.

**Corrección:** un ack de aplicación en el stream directo. El receptor escribe un byte tras
persistir y el emisor lo espera con plazo; si no llega, cae al buzón (la deduplicación por id y
por huella ya hace inofensivo un duplicado). Necesita un protocolo nuevo (`/krypta/msg/1.1.0`,
negociado por multistream con caída a 1.0.0). La congelación del formato se levantó el 11 oct.

### O-8 (Bajo-Medio) — Trabajo de CPU y de E/S en el hilo principal

- `ChatService` no fija su despachador: `send`, `sendFile` (cifrado de hasta 170 trozos) y
  `markConversationRead` se lanzan desde `viewModelScope` (Main), así que el sellado AES-GCM,
  HMAC y X25519 por JNI corren en el hilo de la UI entre suspensiones. Hoy no se nota; con
  archivos grandes y épocas que avanzan, sí.
- `KryptaApplication.onCreate` inyecta `IncomingNotifier` → `ChatService` → repositorios →
  `provideDatabase`, que desenvuelve la frase-clave con el Keystore y, la primera vez, convierte
  la base entera (`DatabaseEncryption.encryptInPlace`). **Todo en el hilo principal, al arrancar
  el proceso**, también cuando lo arranca un `BroadcastReceiver`.

**Corrección:** `withContext(Dispatchers.Default)` en los puntos de entrada públicos de
`ChatService` que cifran, y abrir la base de forma perezosa (por ejemplo, `dagger.Lazy` en
`IncomingNotifier` o inicializar el gancho fuera del hilo principal).

### O-9 (Bajo-Medio) — El chat abierto se recarga entero con cada mensaje de cualquier conversación

La paginación quedó diferida en septiembre. Hoy se suma la invalidación de Room, que es **por
tabla**: cualquier mensaje nuevo, de cualquier conversación, o cualquier cambio de estado (un
✓✓) reemite `observeConversation` del chat abierto, con todas sus filas y todos sus
`payload`. Eso incluye las fotos en línea, de hasta 58 KiB cada una. La caché de decodificado
evita volver a descifrar, pero no la lectura ni la memoria. Con O-2, además, cada mensaje nuevo
dispara otra carga completa para el acuse.

**Corrección:** paginar (`PagingSource` o ventana de los últimos N + «cargar más») y cargar el
`payload` de las imágenes a demanda, cuando la burbuja entra en pantalla.

### O-10 (Bajo) — Estado mutable compartido sin sincronizar en `ChatService`

`onReceived` corre en dos sitios: en hilos de Go (procesador del buzón, con `runBlocking`) y en
el `scope` de IO (eventos directos). Varios mapas que toca no son seguros entre hilos:

- `lastRehook` es un `mutableMapOf` usado como limitador por `launchHello`, mientras sus
  hermanos `lastHello`, `lastRelay` y `lastRelayHello` son `ConcurrentHashMap`;
- `ackedReceipts` es un `LinkedHashMap` en orden de acceso;
- `labelIndex` y `wanJob` se escriben sin `@Volatile` ni cerrojo.

El peor efecto realista es un limitador que deja pasar un anuncio de más o una
`ConcurrentModificationException` tragada por un `runCatching`; no hay riesgo criptográfico,
porque el ratchet tiene su `Mutex`. **Corrección:** `ConcurrentHashMap` en `lastRehook`
(una línea), y `@Volatile` o confinamiento para el resto.

### O-11 (Bajo) — `setAllowedPeers` falla abierto por omisión

[ISignalingService.kt:31](../core/src/main/java/chat/neto/krypta/core/ISignalingService.kt#L31)
le da a `setAllowedPeers` un cuerpo vacío por defecto, «para que los dobles de test compilen».
Es el método que cierra la fuga de IP de §5.1 del modelo de seguridad, y con lista vacía el
filtro queda **abierto**. Una implementación nueva de `ISignalingService` que olvide sobrescribirlo
compila y desactiva la protección sin avisar. **Corrección:** hacerlo abstracto y poner el
cuerpo vacío en los dobles de test.

---

## 5. Arquitectura y patrones

**Lo que se sostiene.** El grafo de módulos es el declarado; `:core` sigue sin Android ni DI; el
dominio entero se prueba en JVM con dobles. Los patrones que el proyecto ha ido descubriendo
están aplicados de forma consistente:

- ganchos directos en vez de flujos sin suscriptor;
- persistencia como lambda dentro de la transacción del ratchet;
- un umbral de protocolo por capacidad;
- SQL en constantes probadas en JVM;
- presupuestos por paso en el bucle WAN.

**Lo que se ha degradado.**

- **`ChatService`: 2.035 líneas**, el doble que en septiembre, cuando ya era A-14. Ahora mezcla
  también la negociación de capacidades, el reengache, el relevo de linaje, la conversión del
  historial, el buzón ciego, el filtro de conexiones y el reenvío de archivos. O-1, O-3 y O-10
  nacen ahí: cada función nueva añade estado y caminos de error a una clase donde ya no se ven
  todos a la vez. El corte propuesto en septiembre sigue valiendo, con una pieza más:
  - `WanLoop`: ciclo, presupuestos, latido y kick;
  - `FileTransfer`: trocear, reenviar y recibir;
  - `ContactService`: alta, verificación, bloqueo y borrado, y con él O-3;
  - `CapabilityNegotiator`: anuncio `V`, reengache y relevo;
  - `ChatService` queda como el lazo cifrar → persistir → enviar/recibir.
- **`ChatViewModel` es uno solo para toda la app**: chat, lista, llamadas, vídeo, copia de
  seguridad, ajustes y diagnóstico, con siete dependencias concretas. Sumado a A-13, `:app` no
  tiene ni un test de presentación: sus 49 tests cubren piezas hoja. Partirlo por pantalla
  (`ChatViewModel`, `ConversationsViewModel`, `CallViewModel`, `SettingsViewModel`) es barato y
  hace testeable lo que hoy no lo es.
- **`ChatScreens.kt` (1.904 líneas)**: `ChatScreen` ocupa ~630 líneas y `MessageBubble` ~300. Es
  deuda de mantenimiento, no un fallo.

**Prioridad:** ninguno de estos refactors es urgente por sí mismo, pero el de `ChatService`
debería ir **antes** de la próxima función de protocolo (rotación de identidad,
post-cuántico), porque esas tocan precisamente los caminos de error donde está O-1.

---

## 6. Pruebas

320 tests JVM, suites Go con `-race`, migraciones probadas en dispositivo, propiedades del
ratchet con caos, fuzzing de parsers y modelos Tamarin. Es una base muy buena. Los huecos que
deja ver esta auditoría:

1. **Los tests de fiabilidad del buzón no se repitieron al encender el ratchet** (O-1). Cada
   garantía de entrega debería probarse en los dos caminos, con un contacto v1 y uno v4.
2. **Ningún test fija el tamaño de lo que se envía** en función del historial (O-2).
3. **Sin tests de concurrencia fuera del ratchet** (O-3, O-10). El de H-0 enseñó el patrón:
   leer, `yield()`, escribir.
4. **`:app` sin tests de ViewModel** (A-13, §5).

---

## 7. Documentación

- **`architecture.md` está desfasado en la cabecera y en la tabla de módulos.** «Última
  actualización: 10 sep 2026». El resumen dice que el proyecto está en «fase de esqueleto» con la
  red «como `TODO`». Además: `:data` en «DB v9» (es v10), `:native-bridge` con «+ Foreground
  Service» (está en `:app` desde julio) y `:p2p-signaling` como «Parcial». Quien llegue nuevo lee
  primero lo que ya no es verdad.
- **`PLAY-STORE.md`** no recoge la regresión de octubre (O-6).
- **`CLAUDE.md` tiene ~2.100 líneas** y funciona como diario de cambios. El estado actual
  convive con el histórico, por ejemplo «`DEFAULT_BOOTSTRAP` now carries all three nodes» junto a
  su sustitución. Separar un `CLAUDE.md` corto (estado, reglas, comandos) de un
  `docs/HISTORIAL.md` reduciría el riesgo de actuar sobre un párrafo viejo.
- **Faltan en `security-model.md`** las notificaciones (O-4) y la asimetría de entrega directa
  frente a buzón (O-7).

---

## 8. Plan de acción sugerido

**Antes de salir a producción:**

1. **O-1**: distinguir «no abre» de «no se guardó» en `openRatchet`, y repetir los dos tests de
   ack-tras-persistir con un contacto en ratchet.
2. **O-6**: actualizar `PLAY-STORE.md` y hacer la prueba de no regresión de §13, o decidir por
   escrito publicar sin ella y avisar en la ficha.
3. **O-2**: acusar solo lo no leído.
4. **O-3**: escrituras de una columna para verificar, bloquear y marcar anunciado.

**Siguiente ventana:**

5. **O-4**: versión pública de la notificación y ajuste de contenido; documentarlo.
6. **O-5**: presupuesto del latido por debajo de los plazos del sistema.
7. **O-7**: ack de aplicación en la entrega directa (protocolo 1.1.0).
8. **O-10, O-11**: cambios de una línea cada uno.
9. **§7**: `architecture.md` al día.

**Cuando toque, y antes de la próxima función de protocolo:**

10. **§5**: partir `ChatService` y `ChatViewModel`.
11. **O-8, O-9**: despachador propio en `ChatService`, base perezosa y paginación.
12. **Fase 0**: el gate de NAT con dos SIM, que sigue sin medirse.

---

## 9. Estado de implementación (12 de octubre de 2026)

El plan se ejecutó el mismo día. Cada arreglo de código llegó con un test que **fallaba antes**,
salvo donde se indica. JVM: **320 → 340** tests, todos verdes. Go: 5 tests nuevos en el puente
(suite completa con `-race` verde). `:data`: un test instrumentado nuevo, ejecutado en el TECNO.
El AAR se regeneró con `build-aar.sh` (páginas de 16 KB comprobadas en las cuatro ABI).

| # | Hallazgo | Estado | Dónde |
|---|---|---|---|
| O-1 | El ratchet confirmaba lo que no pudo guardar | ✅ Hecho | `openRatchet` solo cae a v1 ante `RatchetException`; el resto se propaga y el buzón no confirma. `ChatServiceTest`: los dos tests de ack-tras-persistir, ahora también por ratchet |
| O-2 | Acuse de lectura proporcional al historial | ✅ Hecho | `MessageRepository.markIncomingRead` devuelve lo que acaba de marcar (en una transacción); lotes de 100 ids; los que no salen se reintentan con el siguiente; `markOutgoingRead` en una sentencia por lote. Ya no hay caché de acuses. 2 tests |
| O-3 | Copia vieja que deshacía verificar o bloquear | ✅ Hecho | `setVerified` / `setBlocked` / `markAnnounced` de una sola columna (`ContactDao`, SQL en constantes). 2 tests en `ChatServiceTest` + 3 en `ContactUpsertSqlTest` |
| O-4 | Texto descifrado en los avisos, sin documentar | ✅ Hecho | `NotificationPrivacy` (*Todo* / *Solo nombre* / *Nada*), versión pública sin contenido, sin texto con el bloqueo de la app; tarjeta en Ajustes; `security-model.md` §7, ayuda, manual y política. `NotificationPrivacyTest` (4). Falta verlo con mensajes reales (PRUEBAS §20.4) |
| O-5 | El latido podía exceder los plazos del sistema | ✅ Hecho | `pollOnce` acotado a 45 s en total (10 + 15 + 20); wake lock de 55 s. 1 test |
| O-6 | Bloqueante de segundo plano no reflejado en `PLAY-STORE.md` | 🟡 Documentado | Recuadro de `PLAY-STORE.md` al día, README y manual ya no prometen «un par de minutos», la ayuda avisa. **Lo que no es código sigue abierto**: la medición de no regresión y la prueba en otras marcas, y la decisión de publicar, que es del autor |
| O-7 | Entrega directa sin acuse | ✅ Hecho (falta dos móviles) | `/krypta/msg/1.1.0` con acuse de un byte tras guardar, caída a `1.0.0` con receptores anteriores; `MessageHandler.OnMessage` devuelve `bool`; `setDirectProcessor` síncrono; un id existente no se reescribe. Go `TestEntregaDirecta*` (4) y `TestUnEmisorAnteriorSigueEntregando`; 2 tests Kotlin. Especificación §3.1 y P16 en las dos lenguas. PRUEBAS §20.1-2 |
| O-8 | CPU y E/S en el hilo principal | ✅ Hecho | Cifrado en `CpuContext` (`Dispatchers.Default`, inyectado); la base se abre en la primera consulta (`DeferredOpenHelperFactory`, test instrumentado en el TECNO). Sin test JVM del despachador: los tests usan el constructor que se queda en el hilo del test |
| O-9 | Chat recargado entero con cada cambio | 🟡 Hecho en lo esencial | Ventana de 100 que crece al subir y al saltar a una cita antigua (`observeRecent`, `countSince`, `MessageWindowSqlTest`). **No** se cargan las fotos a demanda: con la ventana, la memoria queda acotada a ~100 filas y no compensaba. PRUEBAS §20.5 |
| O-10 | Mapas compartidos sin sincronizar | ✅ Hecho | `ConcurrentHashMap` en `lastRehook` y `lastFound`, `@Volatile` donde faltaba, `wanJob` bajo cerrojo. Sin test propio (carrera de hilos reales) |
| O-11 | `setAllowedPeers` abierto por omisión | ✅ Hecho | Sin cuerpo por defecto; los dobles de test lo implementan ellos |
| §5 | `ChatService`, `ChatViewModel` y `ChatScreens.kt` demasiado grandes | ✅ Hecho | `ChatService` 2.122 → 1.313 líneas, con `Outbox`, `FileTransfer`, `ReadReceipts`, `CapabilityNegotiator`, `WanLoop`, `ContactService`, `DiagnosticsLog` y `ProtocolGates` (bloques movidos tal cual, API y tests sin tocar). `ChatViewModel` → `ChatViewModel` + `CallViewModel` + `SettingsViewModel`. `ChatScreens.kt` → `KryptaApp.kt` + `ChatScreens.kt` + `MessageBubble.kt` |
| §6.1-3 | Huecos de test | ✅ Hecho | Tests por ratchet de las garantías de entrega, tamaño del acuse, carrera del anuncio |
| §6.4 | Sin tests de ViewModel | ⬜ Abierto | El reparto los hace posibles, pero `ChatService` es una clase concreta de otro módulo y sus dobles viven en el `test` de `:p2p-signaling`; falta compartirlos (`testFixtures`) o una interfaz en `:core` |
| §7 | Documentación desfasada | ✅ Hecho | `architecture.md` reescrito en cabecera, resumen, módulos y próximos pasos; `security-model.md`, `PLAY-STORE.md`, `PRUEBAS-PENDIENTES.md` (§13 renumerada, §20 nueva), `MANUAL.md`, política de privacidad, README y ayuda. **`CLAUDE.md` pasa de 2.103 a ~420 líneas**: la narrativa se movió literal a [HISTORIAL.md](HISTORIAL.md) (comprobado párrafo a párrafo: solo cambian los dos que se reescribieron), y lo que enseñaba quedó como «Rules learned the hard way» |

**Instalado en el TECNO** como actualización (identidad y datos intactos), antes y después del
reparto de `ChatService`: arranca, lista las conversaciones con sus vistas previas descifradas y
dice «conectado»; Ajustes enseña la tarjeta «Contenido de los avisos», y el Diagnóstico, `DHT:
conectado`, `relay: OK`, el rendezvous encontrando al contacto y `wake activo`. Lint y las suites Go
(puente con `-race`, nodo) en verde. APK arm64 de depuración: `22bdc8d8…8c30`. Lo que solo se puede ver con dos móviles está en
[PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md) §20.
