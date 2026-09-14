# Arquitectura de Krypta — estado actual

> Documento vivo. Refleja **lo que existe en el repo ahora**, no el diseño objetivo
> completo (ese está en [PLAN-senalizacion-descentralizada.md](PLAN-senalizacion-descentralizada.md)).
> Última actualización: 10 sep 2026 — **cifrado en reposo y ratchet**. La base va cifrada
> entera (SQLCipher, DB v6→v9), los adjuntos también (`FileVault`), el historial se guarda
> como sobre en claro dentro de esa base, y el **doble ratchet por épocas**
> ([DISENO-ratchet.md](DISENO-ratchet.md)) está desplegado con el envío **encendido** — pero
> solo con los contactos que lo anuncian, y **sin prueba en dos móviles todavía**
> ([PRUEBAS-PENDIENTES §16](PRUEBAS-PENDIENTES.md)). Antes (8 sep): el secreto compartido sale
> de la base y se deriva al leer; e implementación de la
> [auditoría del 7 sep](AUDITORIA-2026-09-07.md): rendezvous de una sola pasada + ventana de
> solape, identidad en el Android Keystore, reparto justo del buzón y límites finitos del
> relay, topes de recepción de archivos, reconciliación de envíos fallidos; y el modelo de
> seguridad por fin escrito en [security-model.md](security-model.md).

## Resumen

Krypta es una mensajería P2P E2EE para **WAN** en Android nativo (Kotlin + Compose).
El proyecto está en fase de **esqueleto multi-módulo**: compila, la inyección de
dependencias (Hilt) funciona, persiste con Room y arranca en dispositivo. La lógica de
red (libp2p, DHT, relay, buzón, wake) está como `TODO` mapeado a las fases del plan.

## Módulos Gradle

| Módulo | Namespace | Responsabilidad | Estado |
|--------|-----------|-----------------|--------|
| `:app` | `chat.neto.krypta` | UI Compose Material 3 con identidad teal (conversaciones · chat · ajustes), `ChatViewModel` (`@HiltViewModel`), `KryptaApplication`/`MainActivity`, `KryptaForegroundService` (nodo + wake vivos con la app cerrada, notificaciones) | UI rediseñada (jul 2026) |
| `:core` | `chat.neto.krypta.core` | Dominio puro: interfaces SOLID + modelos. Sin Android components ni framework de DI | Definido |
| `:data` | `chat.neto.krypta.data` | Persistencia Room (**DB v9**, cifrada con SQLCipher, migraciones reales) + repos + `DataModule` (Hilt) | Funcional |
| `:native-bridge` | `chat.neto.krypta.nativebridge` | Wrapper Kotlin/JNI sobre el AAR de go-libp2p + Foreground Service | **Host libp2p funcional** (spike) |
| `:p2p-signaling` | `chat.neto.krypta.p2p` | Rendezvous (HKDF real) + orquestación de señalización | Parcial |

### Grafo de dependencias

```
        :app
       /  |  \  \
:p2p-signaling :data  :native-bridge   :core
       |   \            /                ^
       |    `-> :native-bridge ----------'
       `-----------------> :core --------'
:data -----------------------------------'
```

Regla: las **interfaces** viven en `:core`; las implementaciones concretas se inyectan
con Hilt (`@Binds`/`@Provides` en `SingletonComponent`). Esto mantiene los módulos
desacoplados y testeables.

## Componentes clave

### `:core` (contratos del dominio)
- `ISignalingService` — abstracción de la capa P2P que reemplaza al `DiscoveryService`
  mDNS. Expone `events: Flow<SignalingEvent>` + `start/stop/announce/send`.
- `IDiscoveryService` — descubrimiento; mDNS queda como opción LAN.
- `MessageRepository`, `ContactRepository` — persistencia del dominio.
- `MessageCipher` — cifrado autenticado E2EE de los payloads (impl en :p2p-signaling).
- `KeyExchange` — acuerdo de claves: `localPeerId()` + `sharedSecretWith(peerId)` (ECDH).
- `MessageRepository` / `ContactRepository` — persistencia (impl Room en :data);
  `ContactRepository.findByPeerId` resuelve mensajes entrantes.
- Modelos: `Message` (contenido siempre como `ByteArray` cifrado), `Contact`
  (con `peerId` libp2p + `sharedSecret` semilla del rendezvous y del cifrado + `verified`,
  flag anti-MITM tras cotejar el número de seguridad, + `blocked`, bloqueo local del
  contacto),
  `MessageStatus` (`PENDING→SENT→READ`/`FAILED`; la burbuja propia muestra "enviando…/
  enviado/leído/no enviado", y un mensaje **no enviado** es tocable para **reintentar**).

### `:app` (UI)
- `ChatViewModel` (`@HiltViewModel`) sobre `ChatService`: `contacts` (StateFlow),
  `messages(contact)` (descifra para mostrar), `send`, `addContact`.
- `KryptaForegroundService` — **servicio en primer plano real** (Fase 5): `startForeground`
  (tipo **`specialUse`**, `START_STICKY`) y arranca `ChatService.start()` (idempotente).
  Mantiene vivos el nodo y el stream de wake; **ya no postea avisos** — eso es de
  `IncomingNotifier` (ver más abajo), porque este servicio puede no existir en un proceso
  revivido solo por el latido y ahí se perdían mensajes y llamadas en silencio.
  La pantalla de chat tiene un botón **escudo → "Verificar identidad"** con dos vías: el
  **número de seguridad** ([SafetyNumber], para cotejar de viva voz) y **QR** (`QrCode` +
  `zxing-android-embedded`): cada uno muestra su QR —que codifica `krypta:verify:<propio
  PeerID>`— y escanea el del otro; la app compara el PeerID escaneado con el guardado para
  ese contacto → si coincide marca `verified`, si no avisa de posible suplantación. Insignia
  de escudo en la lista y el chat, y un **banner "identidad sin verificar"** en el chat
  (clicable → abre la verificación) mientras el contacto no esté verificado. Re-añadir el
  mismo PeerID conserva la verificación; si el PeerID cambia, se resetea. ZXing es FOSS (sin
  dependencias de Google).
  Las notificaciones se centralizan en `KryptaNotifications`: canal **mensajes** (v2,
  IMPORTANCE_HIGH → heads-up + sonido + vibración, con badge), canal **servicio** (v2,
  IMPORTANCE_LOW, `showBadge=false` para que el ongoing no sume al conteo del icono) y canal
  **llamadas** (v1, sin sonido de canal: el timbre lo pone `IncomingNotifier`). Cada
  notificación de mensaje lleva un **deep-link** (`EXTRA_OPEN_CONTACT`) que abre su
  conversación (MainActivity `singleTop` + `onNewIntent` → estado Compose → `KryptaApp`
  navega al contacto).

  **Quién postea (revisado 13 ago 2026)**: el dueño es `IncomingNotifier` (@Singleton en
  `:app`), enganchado desde `KryptaApplication.onCreate`, **no** desde el servicio en primer
  plano. El motivo es el fallo "llegó el mensaje pero no sonó nada": el aviso lo posteaba el
  FGS coleccionando `ChatService.incoming`, un `SharedFlow` con `replay = 0` que **descarta en
  silencio** lo emitido sin suscriptores — y cuando el OEM mata el proceso y lo revive **solo
  la alarma del latido**, el FGS no existe, así que el mensaje se retiraba del buzón, se
  persistía, se confirmaba al nodo (borrándolo allí) y el aviso se perdía **para siempre**.
  Ahora `ChatService.setIncomingNotifier` es un gancho directo invocado en el sitio tras
  persistir (mismo patrón que `setMailboxProcessor`), y vive en la Application, que existe en
  cualquier arranque del proceso (Activity, servicio o `BroadcastReceiver`). `IncomingNotifier`
  inyecta además `CallService` por lo mismo: era el único suscriptor de `callSignals`, solo lo
  instanciaba el FGS, y un `invite` recibido por buzón en un proceso revivido por la alarma se
  descartaba sin timbrar. Otros dos arreglos del mismo repaso: `ChatService.pollOnce` (el
  latido) llamaba a `connectDht` con `bootstrapAddr` **vacío** en ese proceso revivido —y sin
  host nativo, con lo que `startDht` era un no-op silencioso—, así que ahora hace `start()` y
  cae al bootstrap persistido; y `HeartbeatReceiver` relanza el FGS.

  **Cuándo se calla**: solo si tienes **esa misma** conversación delante
  (`IncomingNotifier.setVisibleConversation`, que fija `ChatScreen` con un `DisposableEffect`).
  Antes bastaba con tener la app abierta en cualquier pantalla (`if (uiVisible) return`), así
  que un mensaje de otro contacto llegaba sin sonar estando en la lista o en otro chat.

  **Contenido**: `Notification.MessagingStyle` acumula los últimos 6 mensajes por contacto en
  una sola notificación (antes cada mensaje nuevo borraba el texto del anterior) y cada uno
  vuelve a sonar (sin `setOnlyAlertOnce`).

  **Limpieza al abrir la app**: `ProcessLifecycleOwner.onStart` → `cancelAllMessages`, que
  barre **todo** el canal de mensajes (bandeja y conteo del icono a cero) sin tocar el
  permanente del servicio —cancelarlo mataría el FGS— ni una llamada sonando. Va en dos
  pasadas, **hijas primero**, porque a partir de 4 avisos el sistema añade una cabecera de
  grupo propia (`ranker_group`) que se recrea si se retira antes que sus hijas. Los **no
  leídos por contacto no se tocan**: viven en Room (`observeUnreadCounts` = entrantes en
  DELIVERED) y solo los borra entrar en la conversación (`markIncomingRead`), así que la lista
  de conversaciones conserva su badge tal cual.

  **Llamada entrante**: `Notification.CallStyle` (API 31+; en API 30, acciones sueltas) con
  **`setFullScreenIntent`** —sin él una llamada con la pantalla apagada solo dejaba un aviso
  discreto en la bandeja— y acciones **contestar/rechazar** que van a `CallActionReceiver` sin
  pasar por desbloquear la app. El timbre usa `AudioAttributes` de
  `USAGE_NOTIFICATION_RINGTONE` explícitos (si no puede salir por el stream de música) y va
  acompañado de **vibración en bucle**, para que en silencio también avise. Cubierto por
  `KryptaNotificationsTest` (instrumentado: `CallStyle` la rechaza el **sistema** en caliente
  si le falta el full-screen intent, cosa que ningún build detectaría).
  Lo lanza `MainActivity` (`startForegroundService`), que también pide `POST_NOTIFICATIONS`
  (Android 13+) y la **exención de batería** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, para
  que Doze no congele el bucle con la pantalla apagada). Sobrevive al swipe de la app →
  junto con el wake, los mensajes llegan (y notifican) **con la app cerrada**.
  - **Tipo `specialUse`, no `dataSync`**: Android 15 corta los FGS `dataSync` a las 6 h
    (fatal para una conexión de mensajería persistente) y `dataSync` no puede arrancarse
    desde `BOOT_COMPLETED`. `specialUse` declara el subtipo en el manifest (property
    `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`).
  - **`BootReceiver`** (`BOOT_COMPLETED`) rearma el servicio tras reiniciar el móvil.
  - **`registerDefaultNetworkCallback`** → `ChatService.kickWan()` al cambiar de red
    (WiFi↔datos): reconecta DHT/relay/wake y retira el buzón **al instante** en vez de
    esperar los 30 s. `kickWan` adelanta el ciclo por un `Channel` CONFLATED que interrumpe
    la espera del `wanLoop` (`withTimeoutOrNull`).
- Compose — **rediseño Material 3 (12 jul 2026)**. Identidad visual propia: paleta M3
  completa **verde-teal** claro/oscuro en `ui/theme/Color.kt` (semilla `#006A60`; dynamic
  color queda como opt-in en `KryptaTheme`), tipografía afinada (`Type.kt`), fondo de
  ventana pre-Compose en `values{,-night}/themes.xml` (sin destello al arrancar), icono de
  launcher propio (burbuja+candado sobre teal, adaptativo + monochrome) y back predictivo
  (`enableOnBackInvokedCallback` + `BackHandler`). Tres pantallas, navegación por estado
  en `KryptaApp` (`ui/ChatScreens.kt`):
  - **Lista de conversaciones** (`ui/ConversationsScreen.kt`): avatar con inicial y color
    estable por PeerID (+ punto "en línea"), **vista previa del último mensaje descifrado**
    (`ChatService.notificationText`) con icono de estado (reloj/✓/✓✓, teal = leído),
    hora relativa, **badge de no leídos**, insignia de verificado, FAB "Nuevo contacto",
    subtítulo con el estado WAN y estado vacío con guía. Alimentada por
    `ChatViewModel.conversations` (combina contactos + `observeLastMessages()` +
    `observeUnreadCounts()`; los **entrantes** pasan a READ local al abrir el chat —
    `markIncomingRead` — reutilizando el estado sin cambio de esquema). **Pulsación larga
    sobre una conversación** (17 jul 2026) → diálogo de acciones **Vaciar chat / Eliminar
    contacto**, cada una con confirmación destructiva (`ConfirmDeleteDialog`); las mismas
    acciones viven en el menú **⋮** de la barra del chat (eliminar navega atrás). Todo es
    **local** (sin cambio de protocolo): ver `ChatService.clearConversation`/`deleteContact`.
    Desde el 6 sep 2026 ese mismo diálogo y ese mismo ⋮ llevan **Bloquear / Desbloquear**
    (`ChatService.setBlocked`), **sin** confirmación destructiva porque es reversible y no
    borra nada. Un bloqueado se marca con un 🚫 en `error` junto al nombre de la lista y
    **nunca se pinta "en línea"** (su rendezvous ya no se anuncia); en su chat, la barra de
    escribir se sustituye por `BlockedInputBar` ("Has bloqueado a X…" + **Desbloquear**), el
    botón de llamar queda deshabilitado con el tooltip "Contacto bloqueado" y una grabación
    fijada en curso se cancela sola al bloquear (si no, el micro se quedaría tomado sin
    ningún control en pantalla para soltarlo).
  - **Ajustes** (`ui/SettingsScreen.kt`): tarjetas de identidad (**PeerID** con
    Copiar/Compartir), **copia de seguridad** (exportar/importar la identidad + contactos a
    un archivo `.krbk` cifrado con frase-clave, vía SAF; al importar, un diálogo muestra el
    PeerID restaurado y cierra Krypta — la identidad nueva rige al reiniciar el proceso),
    **bloqueo de la app** (ver abajo), red (campo **"Nodo WAN (bootstrap)"** pre-relleno con
    `Libp2pNode.DEFAULT_BOOTSTRAP`, error inline si el multiaddr es inválido; estado WAN),
    recepción en 2.º plano (🔔 probar aviso / ⚙ ajustes del sistema) y **Diagnóstico**
    (sonda de latencia + registro en vivo).
  - **Bloqueo de acceso** (16 jul 2026, `AppLock` + `ui/LockScreen.kt`): opcional, con el
    **`BiometricPrompt` del framework** (API 30+, sin dependencias nuevas; permiso normal
    `USE_BIOMETRIC`) y autenticadores `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` — huella, cara o
    el PIN/patrón del móvil; **Krypta no guarda ningún secreto de desbloqueo**. `AppLock`
    (singleton, pref en `krypta_settings`) expone `enabled`/`graceMs`/`locked` como
    StateFlows; el re-bloqueo se decide sobre **`ProcessLifecycleOwner`** (una rotación no
    es "salir de la app") con gracia configurable (al instante / 1 min / 5 min) y el
    arranque en frío nace bloqueado. La puerta está en `KryptaApp` **después** de la rama
    de `CallScreen`: una llamada entrante se atiende sin desbloquear (como el teléfono).
    Cambiar el ajuste exige autenticarse en ambos sentidos, y los fallos del prompt/manager
    van por `runCatching` (nunca crash). Política pura `shouldRelock` testeada en JVM
    (`AppLockTest`).
  - **Bloqueo de captura y grabación de pantalla, solo en el chat** (13 ago 2026, acotado a
    la pantalla de chat el 21 ago 2026,
    [ScreenSecurity.kt](../app/src/main/java/chat/neto/krypta/ScreenSecurity.kt)):
    `FLAG_SECURE` se pone y se quita **por pantalla** — `SecureScreenEffect` (en
    [ChatScreens.kt](../app/src/main/java/chat/neto/krypta/ui/ChatScreens.kt)) llama a
    `ScreenSecurity.setSecure(activity, true)` en un `DisposableEffect` al entrar en el chat y
    a `false` al salir. Antes iba en `MainActivity.onCreate` y, con una sola Activity, cubría
    la app entera: eso estorbaba (no se podía capturar ni la lista, ni ajustes, ni la ayuda —
    ni para soporte ni para la ficha de Play) sin proteger nada más, porque el contenido
    sensible está en la conversación. Los diálogos y `ModalBottomSheet` viven en ventanas
    propias pero **heredan** el flag de la padre al abrirse (`SecureFlagPolicy.Inherit` es el
    valor por defecto de `DialogProperties`, y ModalBottomSheet copia el de la ventana padre),
    así que los del chat quedan cubiertos sin marcarlos uno a uno. Mientras el flag está
    puesto el sistema rechaza la captura, el grabador graba negro, la miniatura de "recientes"
    sale vacía y la ventana no se vuelca a una pantalla no segura. `FLAG_SECURE` solo afecta a
    esta ventana: nunca ha impedido capturar en otras apps.
    **La propia Krypta sí puede capturar** (`captureToGallery`): pinta la jerarquía de vistas
    sobre un `Canvas` **por software** y guarda un PNG en `Pictures/Krypta` vía `MediaStore`
    (con `RELATIVE_PATH` + `IS_PENDING`; sin permiso de almacenamiento en minSdk 30). Tiene que
    ser `view.draw(Canvas)` y **no `PixelCopy`**: PixelCopy lee la superficie a través del
    compositor y con `FLAG_SECURE` devolvería negro, mientras que una app dibujando sus propias
    vistas nunca pasa por ahí. Se ofrece en **⋮ → "Capturar pantalla"** del chat, esperando
    **dos `withFrameNanos`** tras cerrar el menú (si no, el propio desplegable sale en la
    imagen). Nota para depurar: `adb shell screencap` sirve en todas las pantallas **menos el
    chat**; ahí sale negro — usa `uiautomator dump` (lee el árbol de accesibilidad, no se ve
    afectado) o la captura propia.
  - **Chat** (UI-3): barra con avatar + "en línea"; burbujas con esquina-cola asimétrica
    (propias `primaryContainer`, ajenas `surfaceContainerHigh`), **agrupadas** por lado y
    ventana de 3 min (solo la primera del grupo abre esquina) y con **hora + checks de
    estado** dentro; **separadores por día** ("Hoy"/"Ayer"/fecha); **hoja inferior de
    adjuntos** (clip → Foto/Archivo); **nota de voz mantener-y-soltar** (mantener graba y
    soltar envía, <1 s descarta; toque corto = grabación fijada con Cancelar/Enviar — y el
    Box del micro permanece en composición durante la grabación, o su `pointerInput` se
    cancelaría y la soltada nunca llegaría). **Responder citando** (3 sep 2026): deslizar la
    burbuja a la derecha (`detectHorizontalDragGestures` en el Box de la fila, con umbral de
    56 dp, háptico al cruzarlo y vuelta animada al soltar) o mantener pulsado → píldora con
    **Responder** (siempre) y **Copiar** (solo si hay texto); barra de cita sobre el campo de
    escribir con ✕ para descartarla (y **atrás** también la descarta, en vez de salir del
    chat); la cita se pinta dentro de la burbuja (`QuotedPreview`: franja + autor + resumen,
    a todo el ancho de la burbuja y con la franja a la altura real del texto vía
    `height(IntrinsicSize.Min)` + `fillMaxHeight`)
    y **tocarla salta al mensaje original**, que destella (`animateColorAsState` mezclando con
    `tertiary`) para localizarlo. El gesto de deslizar vive en el Box de fuera y no encadenado
    al `combinedClickable` de la burbuja, que sigue llevando toque y pulsación larga juntos.
    **La silueta de la burbuja no la dicta su contenido** (3 sep 2026): la fila es un
    `BoxWithConstraints` y la burbuja se topa en `BUBBLE_MAX_WIDTH_RATIO` (0.78) del ancho
    disponible — antes no había límite y un texto largo, una imagen ancha o la cita de un
    mensaje largo la estiraban de lado a lado, con siluetas distintas en mensajes seguidos.
    **Y lo que dibuja esa silueta escala con ella** (6 sep 2026): `BubbleShape` (un `Shape`
    propio) + `bubbleBorderPx`/`bubbleShadowPx` interpolan esquina **18→28 dp**, borde
    **1→1,75 dp** y sombra **1,5→3 dp** desde la altura de una burbuja de una línea
    (`BUBBLE_SHORT_HEIGHT`, 48 dp; por debajo **nada cambia**) hasta un tope. Escala con la
    **altura** —un mensaje largo de una sola línea sigue siendo bajo y sus 18 dp se leen
    bien— y se calcula **al dibujar, desde el tamaño ya medido**: por eso es un `Shape`
    (`createOutline` recibe el `size`, así que el radio correcto sale en el **primer**
    fotograma), la sombra pasó de `Modifier.shadow` a `graphicsLayer { shadowElevation = … }`
    y el borde de `Modifier.border` a un `drawWithCache`. Con `onSizeChanged` haría falta
    recomponer y cada burbuja se pintaría un fotograma con los valores de burbuja corta: un
    salto de esquina visible al desplazar la lista. El trazo del borde va al **doble** de
    grosor porque está centrado en el contorno y el `clip(shape)` se come la mitad de fuera.
    La esquina-cola sigue fija en 4 dp: es la identidad de quién escribe, no algo que escalar.
    Motivo del cambio: con un mensaje largo la burbuja **perdía el contorno** — el radio era
    una fracción mínima de la silueta y la sombra desaparecía a ese tamaño.
    **La píldora de acciones** usa `inverseSurface` + **aro** de `inverseOnSurface` (1.5 dp):
    tiene que verse sobre las dos orillas y ningún relleno plano lo consigue (en oscuro, entre
    la propia `#53DBC9` y la recibida `#303635` el óptimo equidistante es 2.84:1); con las dos
    capas siempre hay una que contrasta (9.5:1 / 7.7:1 en oscuro, 10.7:1 / 5.7:1 en claro).
    Antes era `surfaceContainerHighest`, **el mismo color que la burbuja recibida** (1.0:1).
    Ojo al verificar: la píldora es un `Popup` y **hereda `FLAG_SECURE`**
    (`PopupProperties.securePolicy` = `Inherit`), así que ni `screencap` ni la captura interna
    (que solo pinta la decorView) la recogen — sus colores se comprueban por cálculo.
    Alta de contacto (solo **nombre + PeerID**).
    Los iconos siguen locales en `ui/KryptaIcons.kt` (`ImageVector`, ahora también vía
    `addPathNodes`) para no depender de `material-icons-*`.
  El `ChatViewModel` arranca el nodo (`ChatService.start()`) al iniciarse.
- El texto plano solo existe en memoria al pintar (`ChatService.decrypt`); en disco y en
  tránsito todo es ciphertext.

### `:p2p-signaling`
- `RendezvousService` — **implementación real** de `HKDF-SHA256` (RFC 5869):
  `rendezvous = HKDF(shared_secret, info="krypta-rdv:"+fecha)` → 32 bytes. Rotativo por
  día y no enumerable sin el secreto. Cubierto por `RendezvousServiceTest`.
- `Libp2pKeyExchange` — implementa `KeyExchange` delegando en `Libp2pNode` (identidad + ECDH).
- `AesGcmMessageCipher` — **E2EE real**: AES-256-GCM con clave de sesión
  `HKDF(sharedSecret, "krypta-msg-key-v1")`; nonce aleatorio de 12 B antepuesto
  (`nonce || ct+tag`). Es el camino v1, **sin secreto hacia adelante**, y sigue siendo el que
  usa la app.
- `Ratchet` / `RatchetState` — **secreto hacia adelante (fases 1-2, 9 sep 2026, aún sin
  cablear)**: doble ratchet **por épocas**, donde una época es el *par* de públicas efímeras
  X25519 vigentes y se avanza «en cuanto se tienen las dos», sin roles ni iniciador — lo que
  evita la bifurcación de raíces cuando los dos extremos escriben a la vez, que es donde el
  ratchet de Signal necesita un servidor de prekeys que aquí no existe. La época 0 se deriva del
  secreto compartido (arranque sin ronda previa, y sin PFS a propósito); de ahí en adelante
  `RK(e) = HKDF(X25519(…), salt = RK(e-1))`, cadena simétrica HMAC por mensaje, nonce derivado y
  claves saltadas acotadas. `encrypt`/`decrypt` son **funciones puras** `estado → (estado', bytes)`
  para poder confirmar el avance en la misma transacción que la persistencia del mensaje. El
  X25519 efímero lo pone el puente Go (`BridgeCurve25519`; Android no trae `XDH` hasta la API 33)
  y el JDK en los tests (`JdkCurve25519`). Diseño completo en
  [DISENO-ratchet.md](DISENO-ratchet.md); cubierto por `RatchetTest` (17) y `TestRatchetKeyPairAgreement`.
- `RatchetSessions` — el ratchet **con su almacén** (`RatchetStore` en `:core`, tablas
  `ratchet_sessions` y `ratchet_seen`, base **v7**). Es donde viven las dos garantías de las que
  depende que el ratchet sea seguro en un móvil de verdad:
  - **Atomicidad.** `send`/`receive` reciben el guardado del mensaje **como lambda** y lo ejecutan
    dentro de la misma transacción que el avance del ratchet (`TransactionRunner`), porque
    descifrar consume la clave del mensaje y guardar el estado sin el mensaje haría que la
    reentrega del buzón fuera indescifrable.
  - **Exclusión por conversación** (desde el 14 sep 2026). Un `Mutex` por contacto rodea cargar →
    cifrar/descifrar → guardar. Sin él, dos operaciones simultáneas repetían clave y nonce de
    AES-GCM (H-0 de [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md)).

  Deduplica por huella del ciphertext **antes** de descifrar y **dentro del cerrojo** (una
  reentrega legítima es indistinguible de una repetición), y un estado ilegible reengancha la
  conversación en la época 0 en vez de romperla. Cubierto por `RatchetSessionsTest`, que desde esa
  fecha incluye carreras reales con un almacén que suspende entre leer y guardar.
- **Transporte v1 + v2 conviviendo** — `ChatService.onReceived` acepta las dos formas:
  `openRatchet` (v2) con **caída a `openLegacy`** (v1, clave estática). El byte de versión es
  una pista y no una garantía: un ciphertext v1 empieza por un nonce aleatorio, así que ~1 de
  cada 256 de más de 86 bytes se disfraza de v2 y hay que reintentarlo por el otro camino. Lo
  que no se puede abrir por ninguna vía se descarta con una línea de diagnóstico (antes se
  persistía como "texto legado" y salía una burbuja de basura).
- **Anuncio de capacidad por contacto** — sobre `V` con la versión de protocolo que habla el
  cliente; los clientes anteriores lo ignoran limpiamente (`Decoded.Unsupported`). Se guarda en
  `contacts.peerProtocol` (lo que él anunció) y `contacts.announcedProtocol` (lo que le
  anunciamos), y el paso `capacidades` del ciclo WAN lo manda **una vez por contacto y
  versión**, marcándolo solo si el envío salió. Es lo que permite encender el ratchet contacto a
  contacto sin esperar a que actualice todo el mundo.
- **Envío con ratchet: encendido el 10 sep 2026** (`ChatService.RATCHET_SEND = true`). A quién
  se le escribe así lo decide `usesRatchet(contact)` = lo que ese contacto haya anunciado, así
  que **no cambia nada hasta que el otro extremo actualiza**. Todos los caminos de salida pasan
  por `seal` (solo bytes: trozos, meta, señales de llamada, acuses, el propio anuncio) y
  `sealAndPersist` (bytes + `Message` en una transacción), de modo que la decisión es una sola.
  El estado avanzado **se guarda antes de que los bytes salgan**: al revés, un envío fallido
  dejaría el siguiente mensaje cifrando desde el mismo estado — misma clave y mismo nonce de
  AES-GCM. Sigue **sin prueba en dos móviles** ([PRUEBAS-PENDIENTES §16](PRUEBAS-PENDIENTES.md));
  ese interruptor es la vuelta atrás.
- `SafetyNumber` — **número de seguridad anti-MITM** (estilo Signal): 60 dígitos decimales
  de `SHA-256(dominio ‖ peerId_menor ‖ peerId_mayor)`, **simétrico** (ambos ven el mismo) y
  determinista. Como el PeerID *es* la clave pública, el intercambio no tiene MITM en la
  matemática; este número sirve para cotejar fuera de banda que nadie **sustituyó el PeerID**
  en el canal por el que se compartió. `ChatService.safetyNumber(contact)` +
  `setVerified(contact, bool)` → `Contact.verified` (Room). Cubierto por `SafetyNumberTest`.
- **Bloqueo de contacto** (6 sep 2026, requisito de la política de contenido de Play):
  `ChatService.setBlocked(contact, bool)` → `Contact.blocked` (Room, **v5**). Todo local y
  **sin cambio de protocolo**, en cuatro puntos: (a) `announceAndFind` filtra a los
  bloqueados, así que se deja de anunciar el rendezvous compartido con ellos; (b)
  `onReceived` los descarta **antes de descifrar** — no persiste, no avisa y no llega a
  `CallService` (ni timbre ni fila de "llamada perdida"); devolver `null` es además lo que
  **ack'ea el sobre en el buzón**, así que el nodo lo borra en vez de reentregarlo cada ciclo
  ocupando el cupo del destinatario; (c) `requireNotBlocked` corta **todos** los caminos de
  salida (`send`/`sendImage`/`sendFile`/`sendRaw`/`retry`, y con `sendRaw` las señales de
  llamada y los trozos de archivo); (d) `markConversationRead` limpia el badge local pero **no manda el
  acuse**: un ✓✓ le diría que sigues leyéndole. El bloqueado no recibe **ninguna** señal —
  sus envíos le quedan como enviados, igual que si el móvil estuviera apagado. El historial
  se conserva (para borrarlo están `clearConversation` / `deleteContact`). Cubierto por
  `ChatServiceTest` (descarte + ack, nada sale, sin acuse, fuera del rendezvous, desbloquear
  restaura) y verificado en vivo en el TECNO.
- `Hkdf` — util HKDF-SHA256 (RFC 5869) compartido por rendezvous y cifrado.
- `IdentityBackup` + `BackupManager` — **respaldo de identidad** (archivo `.krbk`):
  `"KRBK1" ‖ salt(16) ‖ nonce(12) ‖ AES-256-GCM(payload)` con el magic como AAD; clave por
  PBKDF2-HMAC-SHA256 (310k iteraciones) de la frase-clave del usuario. El payload lleva la
  identidad Ed25519 en base64, una línea por contacto (nombre b64 | PeerID | verificado) y
  una línea `b=<peerId>` por contacto **bloqueado** — aparte, y no como cuarto campo de la
  línea del contacto, para que un Krypta anterior siga leyendo el archivo (su parser exige
  3 campos e ignora las líneas que no conoce);
  los **secretos compartidos no viajan** — `BackupManager.import` los re-deriva por ECDH de
  la identidad importada (`Libp2pNode.sharedSecretFor(identityBytes, peerId)`) y upserta
  los contactos. `Libp2pNode.importIdentityBytes` valida con `Bridge.peerIDForIdentity` y
  persiste en las prefs `krypta_identity`; como la identidad en uso es `lazy` y el host ya
  corre, **rige al reiniciar el proceso** (la UI cierra Krypta; el FGS sticky lo revive).
  Cubierto por `IdentityBackupTest` (round-trip, passphrase errónea, manipulación, archivo
  ajeno, nada en claro) y verificado en vivo (export → import → reinicio → mismo PeerID).
- `MessageEnvelope` — sobre de aplicación **dentro** del cifrado E2EE. Tipos: `T` texto,
  `R` acuse de lectura, `I` imagen JPEG en línea, `F` meta de archivo, `K` trozo de archivo,
  `D` descriptor local de archivo (no viaja), `C` señal de llamada, `Y` **cita (respuesta)**.
  `Y` es un **envoltorio**, no un contenido: `Y\n<idCitado>\n` + el sobre normal del mensaje,
  así responder vale para texto, foto, archivo o nota de voz sin duplicar un tipo por cada uno
  (y no anida: una respuesta dentro de otra decodifica a null). Viaja **solo el id** del
  mensaje citado, nunca una copia de su contenido. `decode` tolera bytes sin sobre (mensajes
  legado) devolviendo null, y un tipo desconocido (versión más nueva) da `Unsupported` en vez
  de pintar su cabecera cruda. Cubierto por `MessageEnvelopeTest`.
- `DecodedMessage` (en `:core`) — lo que devuelve `ChatService.decodeMessage(contact, message)`:
  el `MessageContent` **y** `replyTo` (id del mensaje citado, o null). La cita va fuera de
  `MessageContent` porque es ortogonal al tipo — se responde con texto, con una foto o con una
  nota de voz. Quien pinta la resuelve contra su propia conversación.
- `MessageContent` (en `:core`) — contenido descifrado listo para pintar: `Text`, `Image`
  (JPEG) o `File` (nombre/mime/tamaño/ruta local). `ChatService.content(contact, message)` lo
  produce; el ViewModel decide la burbuja.
- `FileStore` (interfaz en `:core`, impl `DiskFileStore` en `:app`) — **reensambla archivos
  troceados**: junta la meta + los trozos (en cualquier orden) y, al completarse, escribe el
  archivo en el almacenamiento interno (`filesDir/krypta_files/<fileId>/<name>`) devolviendo su
  descriptor. **v2 (5 jul): staging en disco** — cada trozo/meta se escribe al llegar en
  `krypta_files/staging/<fileId>/` (tmp+rename atómico), así que una transferencia a medias
  **sobrevive a la muerte del proceso** y las reentregas son idempotentes; al completar se
  concatena desde disco y se borra el staging (`DiskFileStoreTest`).
  **v3 (9 sep 2026): cifrado en reposo** — todo lo que escribe el almacén (trozos y meta del
  staging, archivo ensamblado y la copia propia del emisor en `sent/`) va cifrado con
  `FileVault` (AES-256-GCM, clave de 32 B envuelta por el Keystore, marca `KFV1`). La UI ya no
  lee ficheros: pide los bytes por `FileStore.read(path)` —que descifra y **tolera los adjuntos
  anteriores, sin marca**— a través de `LocalAttachmentReader`. Consecuencias en la UI: la nota
  de voz se reproduce con un `MediaDataSource` sobre los bytes en memoria (`MediaPlayer` no
  abre un fichero cifrado), el GIF se decodifica desde un `ByteBuffer`, y **abrir un adjunto con
  otra app** deja una copia en claro en `cacheDir/krypta_abrir/` (lo único que expone hoy el
  FileProvider), que se limpia al arrancar el proceso.
- `ChatService` — **orquestador de dominio** (cierra el lazo): `send(contact, plaintext)`
  cifra un **sobre** (`MessageEnvelope`, que lleva el id del mensaje) → persiste `Message`
  (PENDING→SENT) → `signaling.send`; si el envío directo falla (peer offline / NAT sin ruta),
  **cae al buzón** (`signaling.sendOffline` → `SENT`, log "→ buzón"); solo si el buzón también
  falla queda `FAILED` (nunca crashea). `retry(contact, msgId)` reintenta un FALLIDO con el
  **mismo id** (por ahí deduplica el receptor): desde la v8 vuelve a cifrar el sobre guardado,
  y una fila anterior —que aún guarda su ciphertext— se reenvía tal cual. **Borrado local (17 jul 2026)**:
  `clearConversation(contact)` vacía el chat **solo en este dispositivo** — borra los
  mensajes de Room y, para cada burbuja de archivo, pide `FileStore.deleteLocal(fileId,
  localPath)` (staging pendiente + ensamblado + copia propia, p. ej. la nota de voz en
  `sent/`; `DiskFileStore` **se niega a borrar fuera de `krypta_files/`** porque el path
  sale de un descriptor persistido); `deleteContact(contact)` además elimina el contacto —
  el bucle WAN relee los contactos de Room en cada ciclo (`announceAndFind`), así que su
  rendezvous cesa solo, sin reiniciar el host (re-añadirlo por PeerID re-deriva el mismo
  secreto; la verificación se repite). Colecta `signaling.events` y en `MessageReceived`
  descifra el sobre: un **texto** o **imagen** se persiste DELIVERED con el id del emisor; un
  **acuse de lectura** (`markConversationRead` lo envía al abrir el chat) marca los mensajes
  salientes citados como **READ**. `content()` clasifica en texto/imagen; `notificationText`
  da "📷 Foto" para imágenes. **Imágenes (v1, en línea)**: `sendImage(contact, jpeg)` — el
  cliente comprime la foto (`ImageCodec`: reduce a ≤1280 px y baja calidad hasta ≤58 KiB para
  caber en el buzón) y la envía como sobre imagen por el mismo camino (directo → buzón → wake →
  notificación). El **formato depende de la transparencia**: JPEG para fotos, **WEBP_LOSSY si
  el bitmap tiene alfa** — los stickers y emoji grandes del teclado son PNG/WebP con fondo
  transparente y el JPEG, sin canal alfa, los entregaba con el fondo en **negro**. El receptor
  usa el mismo `BitmapFactory`, así que no hay cambio de protocolo.
  **Contenido enriquecido del teclado (13 ago 2026)**: la caja de mensaje lleva
  `Modifier.contentReceiver`, que hace que el campo anuncie `*/*` en su `EditorInfo` en vez de
  solo `text/*` — sin él, las pestañas de GIF y stickers del teclado respondían "esta app no
  admite insertar aquí". Lo recibido se `consume` si su mime es `image/*` y va por `sendImage`;
  el resto (texto plano) se devuelve al campo. Compose ya pide el permiso de lectura de la URI
  (`InputContentInfoCompat.requestPermission`). Esto obliga a usar el `TextField` **basado en
  `TextFieldState`**: solo la pila nueva de `BasicTextField`
  (`foundation.text.input.internal`) enchufa `commitContent`; la heredada (`value`/
  `onValueChange`) nunca lo ve.
  **GIF animado**: `sendImage` bifurca por mime — `image/gif` e `image/webp` van **tal cual por
  el camino de archivos troceados** (48 KiB por trozo, staging en disco, reentrega del buzón),
  el único que pasa de los ~58 KiB del sobre en línea; recodificarlos con `ImageCodec` es justo
  lo que los dejaba en su primer fotograma. Tope de 4 MB (por debajo del cupo de 5 MiB del
  buzón, para que un GIF llegue también con el contacto desconectado) y **copia local** en
  `krypta_files/sent/` pasada como `localPath`, para que la burbuja del emisor se anime igual
  que la del receptor. Se pinta con [ui/AnimatedImage.kt](../app/src/main/java/chat/neto/krypta/ui/AnimatedImage.kt):
  `ImageDecoder` + `AnimatedImageDrawable` del propio framework (sin dependencias nuevas),
  dibujado sobre el canvas nativo y repintado con un bucle `withFrameNanos` — `draw()` avanza el
  fotograma según el tiempo, así que basta con redibujar. El `tick` se lee **dentro** del bloque
  de dibujo (invalida el dibujo, no la composición) y el bucle muere con la composición, así que
  un GIF fuera de pantalla deja de animarse. Si el archivo falta o no decodifica, cae a la
  burbuja de archivo. `notificationText` lo rotula **"🎞 GIF"**, no "📎 archivo.gif". **Archivos (v1, troceados)**: `sendFile(contact, name, mime, bytes)` parte el
  archivo en trozos de 48 KiB (`CHUNK_SIZE`, bajo el límite del buzón), envía una **meta** (`F`)
  + cada **trozo** (`K`) con `sendRaw` (cifrado, directo → buzón, sin crear Message), y crea UNA
  burbuja (descriptor). El receptor los pasa a `FileStore`, que al completar escribe el archivo
  y se persiste como Message DELIVERED (abrible con FileProvider). Límite v1: 8 MB (la cuota del
  buzón acota la entrega offline a ~5 MB); resolución/archivos grandes con staging = v2.
  **La fragilidad v1 quedó arreglada el 5 jul** (antes: trozos en memoria + buzón que borra lo
  ack'd → un trozo perdido dejaba el archivo irrecuperable; se perdió un .bin el 4 jul y 1 de 3
  notas de voz el 5 jul): ahora el buzón es **ack-tras-persistir** — el sobre se entrega
  síncrono (`ISignalingService.setMailboxProcessor` → `ChatService.onReceived`, `runBlocking`
  en el hilo del fetch de Go) y solo se ack'ea (borra en el nodo) si persistió sin error; lo
  demás se reentrega en el próximo fetch (dedup por id de sobre). En Go,
  `MailboxHandler.OnMailboxMessage` devuelve `bool` (`TestMailboxRedeliverUnacked`). Además el
  buzón ya no pasa por el SharedFlow de eventos, cuyo `tryEmit` con buffer 64 **descartaba
  sobres en ráfagas de trozos**; el resto de eventos del nodo va ahora por un Channel sin
  límite (`Libp2pNode`). **Notas de voz (v1)**: mismo camino troceado sin
  cambio de protocolo — `AudioRecorder` (`:app`, MediaRecorder AAC mono 48 kbps en MP4) graba en
  `cacheDir/krypta_rec/` (desde el 9 sep 2026: MediaRecorder solo sabe escribir en claro, así
  que la grabación pasa al almacén ya cifrada y el temporal se borra);
  `sendVoiceNote` llama a `sendFile(..., localPath=…)` (param
  nuevo: el emisor conserva su copia y su burbuja también reproduce); un `File` con mime
  `audio/*` y copia local se pinta como burbuja con play/pausa+progreso (`AudioNote`,
  MediaPlayer por burbuja); el micro sustituye a "Enviar" con el borrador vacío (permiso
  RECORD_AUDIO en el primer uso) y `notificationText` da "🎤 Nota de voz".
  `decrypt()` sigue para texto (fallback a legado sin sobre).
  **Responder citando (3 sep 2026)**: `send`/`sendImage`/`sendFile` aceptan un `replyTo`
  opcional y envuelven su sobre en uno de cita (`MessageEnvelope.wrapReply`); al recibir,
  `onReceived` abre el envoltorio antes de ramificar. En los archivos troceados la cita viaja
  en la **meta** (`IncomingFileMeta.replyTo` → staging en disco → `AssembledFile.replyTo` →
  descriptor local), porque la burbuja del receptor no nace hasta tener todos los trozos y el
  proceso puede morir entre medias. **Sin cambio de esquema en Room**: el `replyTo` viaja
  dentro del `ciphertext` que ya se persiste.
  Usa `MessageCipher` + `MessageRepository` + `ContactRepository` + un `CoroutineScope` de app.
- `CallService` (`:p2p-signaling`) — **llamadas de voz (Fase 7b, Opción A)**. Señalización
  por sobres `C` (invite/accept/reject/hangup/busy, con ts para descartar invites rancios →
  "📞 Llamada perdida") por el camino de mensajes; medios por un stream libp2p
  `/krypta/call/1.0.0` (Go `CallStream`, framing uint16, admite conexiones relayed). Máquina
  de estados IDLE→CALLING/RINGING→CONNECTING→ACTIVE→ENDED con timeouts; **clave por llamada**
  `HKDF(sharedSecret, callId)` y cada frame cifrado con `MessageCipher`; el que llama abre el
  stream tras el accept y manda un hello cifrado que el receptor valida. Interfaces en
  `:core`: `CallStream` y `AudioEngine`. Cubierto por `CallServiceTest` (dos extremos en
  memoria, audio E2EE bidireccional) y `TestCallStreamEcho` (Go). **Vídeo (7c)**: toggle 🎥
  dentro de la llamada ACTIVE — `startVideo()` abre un stream aparte `/krypta/video/1.0.0`
  (Go `VideoStream`, framing **uint32**, tope 1 MiB: un keyframe H.264 no cabe en uint16)
  con hello propio `VHELLO:<callId>` (misma clave de llamada); canales independientes por
  sentido (si el vídeo cae, la voz sigue); TX con DROP_OLDEST (la pérdida se recompone en el
  siguiente keyframe); `CallState.videoSending/videoReceiving` + `remoteVideoFrames` hacia
  la app. Cubierto por el caso de vídeo de `CallServiceTest` y `TestVideoStreamEcho` (Go).
- `MediaCodecAudioEngine` (`:app`) — implementa `AudioEngine`: AudioRecord
  VOICE_COMMUNICATION (AEC/NS del chip) → MediaCodec **Opus 48 kHz/24 kbps** (o **AMR-WB
  16 kHz** si el dispositivo no codifica Opus; el primer frame `H` de cada sentido anuncia el
  códec — sin negociación, los DEcodificadores son obligatorios en Android) → paquetes de
  20 ms → AudioTrack de voz con ~120 ms de colchón anti-jitter. Mute = enviar silencio;
  altavoz vía AudioManager. La UI es `CallScreen` (pantalla completa mientras hay llamada);
  el timbre + notificación de entrante los pone el FGS (canal `krypta_calls_v1` sin sonido
  propio; el bucle de timbre es `RingtoneManager`).
- `MediaCodecVideoEngine` (`:app`, Fase 7c) — cámara (Camera2, frontal por defecto) →
  MediaCodec **H.264 320×240 ~250 kbps / 12 fps / keyframe cada 1 s** (afinado el 6 jul: a
  640×480/500kbps el túnel wss relayed se saturaba y congelaba también la voz) → frames
  tipados (`R` rotación del sensor, `C` SPS/PPS, `K`/`F` keyframe/delta) que `CallService`
  cifra y envía; a la inversa, decoder H.264 → `Surface` del UI (frames pre-config
  retenidos; el decoder se re-crea tras un fallo **o al llegar un SPS/PPS distinto** — señal
  de que el emisor reinició su encoder). **7d — `switchCamera()`**: alterna frontal/trasera
  reiniciando cámara+encoder (botón **🔄 Cámara** en `CallScreen` mientras envías vídeo);
  la nueva rotación y config viajan en banda. En `CallScreen`: remoto a pantalla completa
  (TextureView girada según la rotación anunciada) + PiP propio; el permiso CAMERA se pide
  con el botón de vídeo. **UI-4**: la pantalla de llamada usa **botones redondos con
  etiqueta** (silenciar/altavoz/vídeo/cambiar cámara + colgar rojo, aceptar/rechazar en
  RINGING), layout de voz con avatar grande + cronómetro, y en vídeo **un toque
  muestra/oculta los controles** (auto-ocultos a los 4 s) con **PiP arrastrable** acotado a
  la pantalla. **7d — proximidad**: en llamada de voz (sin altavoz ni vídeo) se adquiere
  `PROXIMITY_SCREEN_OFF_WAKE_LOCK` (release con `WAIT_FOR_NO_PROXIMITY`), conviviendo con
  el `keepScreenOn` de la llamada; y el **FGS pasa a `specialUse|microphone`** mientras la
  llamada está CONNECTING/ACTIVE (`updateForegroundType`) para que el micro sobreviva a la
  pantalla apagada. Sin adaptación de bitrate ni pulido de orientación/espejo ni Telecom
  (`phoneCall` real) — resto de 7d.
- `SignalingService` — implementa `ISignalingService`; orquesta `Libp2pNode` y
  `RendezvousService`. `start()` arranca host + mDNS (LAN, pruebas); el arranque de mDNS es
  **best-effort** (`runCatching`): si falla —p. ej. en datos móviles sin interfaz multicast— no
  tumba el host ni el bucle WAN, que es el camino principal. `announce()` delega en
  `advertise()`; los entrantes (`StreamData`) → `MessageReceived` y las conexiones
  (`Connected`) → `PeerFound`. `send()` entrega al `contact.peerId`. Payload siempre cifrado.
- `ChatService` (añadido): `start()` arranca host + mDNS y, como el **bootstrap** trae un
  default (`Libp2pNode.DEFAULT_BOOTSTRAP`), se une a la WAN sola en el primer arranque sin que el
  usuario pegue nada (una pref guardada vacía = solo-LAN; solo la *ausencia* de pref cae al
  default). Con bootstrap, lanza el **bucle WAN auto-reparable** (`wanLoop`, cada 30 s): (re)conecta a la DHT con
  `connectDht` —idempotente en Go: crea la DHT una vez y solo re-conecta al bootstrap, sanando
  la wss que Cloudflare recicla ~cada 10 min— y luego `advertise`/`findPeers` de
  `HKDF(sharedSecret, día)` por contacto, y **retira el buzón** (`fetchMailbox`, log solo
  al cambiar el resultado). El intervalo del bucle es **adaptativo** (batería): si el stream de
  wake está abierto (`signaling.wakeConnected()` → Go `WakeOnline()`), los mensajes llegan
  empujados al instante y el ciclo se espacia a **3 min** (`WAKE_IDLE_MS`); si no (LAN-only o
  wake caído), se mantiene ágil a **30 s** (`REDISCOVER_MS`, por debajo del corte de Cloudflare
  ~100 s) para sondear el buzón y redescubrir. `kickWan()` adelanta el ciclo en cualquier caso.
  Al activar la WAN también se suscribe al **wake** del nodo
  (`startWake`); cada `WakeReceived` dispara una retirada inmediata del buzón (la entrega
  pasa de "hasta 30 s" a segundos), y desactivar la WAN corta la suscripción (`stopWake`).
  Expone `incoming` (Flow de contacto+mensaje ya persistido) para las notificaciones del
  Foreground Service. `setBootstrap(addr): BootstrapResult` valida y
  decide: **vacío** → `CLEARED` (persiste `""` y `stopWan()`, deteniendo el bucle en vivo →
  DISABLED); **inválido** → `INVALID` (ni persiste ni arranca, feedback inmediato en la UI);
  **válido** → `OK` (persiste y (re)arranca; el `wanLoop` recoge los nodos nuevos en su
  próximo ciclo). **Multi-nodo (12 jul 2026)**: el bootstrap es una **lista** (multiaddrs,
  uno por línea; `normalizeBootstrapList` valida cada línea, todo-o-nada). Semántica de
  failover en el bridge Go: la DHT conecta a **todos**, `ReserveRelay` reserva en **todos**
  (hay una addr `/p2p-circuit` anunciada por relay), el depósito de buzón va al **primero
  que acepte**, la retirada drena **todos** los alcanzables (así convergen los depósitos
  aunque emisor y receptor vean nodos distintos) y el wake mantiene **un stream por nodo**
  (`WakeOnline` = alguno vivo). Go `TestMailboxMultiNode`. Con un solo nodo el
  comportamiento es idéntico al previo. **`StartDHT` también es de éxito parcial (fix
  17 jul 2026)**: antes devolvía error si fallaba **cualquier** bootstrap de la lista, y
  un solo nodo caído ponía la app en "sin conexión" con la mensajería funcionando por el
  otro (visto en vivo: krypta2 en dial backoff → estado ERROR, pero el buzón retiraba por
  el nodo del Mac). Ahora ≥1 bootstrap conectado = éxito; error solo si fallan todos
  (`TestStartDHTPartialBootstrapFailure`).
  **Un nodo, varias líneas (10 sep 2026)**: cada nodo de `DEFAULT_BOOTSTRAP` va por
  `tcp/4001` directo **y** por `wss/443` (Caddy en el VPS, para redes que solo dejan salir por
  el 443). No son nodos de más: `parseAddrInfos` agrupa por PeerID, y el orden de preferencia
  lo marca la primera aparición de cada uno. Lo delicado es el **orden de marcado**: el ranker
  estándar de libp2p cuenta `wss` como TCP y marca antes el puerto más bajo (443 antes que
  4001), así que los móviles habrían entrado por Caddy — donde el nodo los ve a todos desde
  `127.0.0.1` y los límites por IP no sirven. El host usa `libp2p.DialRanker(directFirstDialRanker)`
  (`dial_ranker.go`): ordena por separado directas y WebSocket y pone estas 1 s por detrás de la
  última directa; si un peer solo tiene WebSocket, se marcan al instante. Go
  `TestDialRanker*`, incluido uno que fija el comportamiento del ranker estándar para avisar si
  un día deja de hacer falta. **El ranker decide el orden, no garantiza una sola conexión**
  (12 sep 2026): el TECNO tuvo dos veces (con la app en marcha y en un arranque en frío; en otro
  arranque, no) una conexión por `tcp/4001` y otra por `wss/443` con el mismo VPS a la vez. Un solo dial de libp2p nunca deja dos (el worker
  cancela los que quedan en vuelo; comprobado en proceso con un proxy TCP lento), así que
  vienen de episodios solapados — `StartDHT` vuelve con el primer nodo y el ciclo ya hace
  `Connect` al otro para relay/buzón/wake. `conn_prune.go` instala un `Notifiee` que **cierra
  toda WebSocket con un peer que ya tiene conexión directa** (las de relay quedan fuera de la
  regla) **salvo que esté ocupada**: por la conexión con un nodo viajan los circuitos de relay
  hacia los contactos y, dentro, las llamadas, así que una WebSocket con streams de relay
  (`/libp2p/circuit/relay/`), llamada o vídeo se vuelve a mirar cada 30 s (hasta 20 veces) y se
  cierra cuando queda libre. `StartDHT` agrupa las líneas del bootstrap por PeerID (construía un `AddrInfo` por
  línea, y la petición solo-wss llegaba al ranker sin directa que la retrasara) y la línea
  `relay:` del diagnóstico muestra las conexiones por nodo (`1 conn: tcp`) y cuántas WebSocket
  sobrantes se han cerrado. Tests: `TestWebsocketRedundanteSeCierra` (dos hosts con la misma
  identidad entrando por ws y por tcp: la forma determinista de darle a un swarm dos conexiones
  del mismo peer), `TestPodaEsperaAQueLaWebsocketQuedeLibre` (una llamada en curso por la ws la
  mantiene abierta hasta colgar), `TestPodaNoTocaLoQueNoSobra`,
  `TestStartDHTUnaConexionPorNodoConDosVias`.
  **Filtro de conexiones entrantes (10 sep 2026)**: el host instala un `ConnectionGater`
  (`gater.go`). Las salidas no se filtran; las **entradas** solo pasan si el PeerID está en la
  lista que fija la app (`SetAllowedPeers`, contactos no bloqueados + nodos), que se refresca en
  `ChatService.pushAllowedPeers` al arrancar y en cada ciclo WAN. Con la lista vacía queda
  **abierto** a propósito (una ventana corta al arrancar es mejor que perder entregas), y por eso
  el diagnóstico publica `permitidos=N`: con 0 hay que verlo, no deducirlo. Cierra la fuga de IP
  del §5.1 del modelo de seguridad: un extraño que marcaba por la dirección de relay provocaba
  que libp2p le mandara las direcciones públicas por hole punching, antes de que
  `ChatService.onReceived` pudiera descartarlo (está una capa por encima). Go `TestGater*`.
  **mDNS opt-in (10 sep 2026)**: el descubrimiento en LAN ya **no se arranca de serie**
  (`SignalingService.start` lo consulta primero). Anunciarse por mDNS delata el PeerID y la
  dirección local a **toda** la WiFi, y el descubrimiento real de Krypta es WAN; era un atajo de
  pruebas encendido en producción. La preferencia (`lan_discovery` en `krypta_settings`, igual
  que el bootstrap) se cambia desde Ajustes → "Red local" y surte efecto **en el momento en los
  dos sentidos**: para poder apagarlo, `StartMdns` ahora guarda el servicio en el `Node` (antes
  era una variable local) y hay `StopMdns`; el lado Kotlin además **suelta el `MulticastLock`**,
  que hasta ahora se quedaba tomado mientras viviera el proceso.
  Diagnóstico: `onlinePeers`, `wanStatus` (`DISABLED/CONNECTING/CONNECTED/ERROR`) y `log`
  (StateFlow de líneas recientes).
- `SignalingModule` — `@Binds ISignalingService → SignalingService`.

### `:native-bridge` (host libp2p funcional — spike)
- **AAR de go-libp2p** vía gomobile en `native-bridge/libs/krypta-p2p.aar` (~75 MB, 4 ABIs).
  Código Go en `native-bridge/libp2p/` (`bridge.go`, `go.mod`); se regenera con
  `native-bridge/libp2p/build-aar.sh`. API: `Bridge.{ping,sum,version,newNode,
  newNodeWithIdentity,generateIdentity,peerIDForIdentity,sharedSecretFor}` y
  `Node.{peerID,listenAddrs,startDHT,advertise,findPeers,sendMessage,setMessageHandler,close}`.
- **Identidad persistente / acuerdo de claves**: `generateIdentity()` (Ed25519) se persiste
  en `SharedPreferences` (`Libp2pNode`), dando un PeerID estable. `sharedSecretFor(identity,
  peerID)` calcula el secreto por **ECDH X25519** (convierte la Ed25519 propia a X25519 y usa
  la pública embebida en el PeerID del contacto) — base del alta solo-con-PeerID.
- `Libp2pNode` — clase `@Singleton` inyectable (con `@ApplicationContext`). `start()` crea un
  host go-libp2p (identidad persistente, TCP + QUIC) vía `newNodeWithIdentity`; `localPeerId()`
  y `sharedSecretWith(peerId)` exponen identidad/ECDH. `startMdns()` activa descubrimiento
  **mDNS en LAN (solo pruebas)** con auto-conexión + `MulticastLock`; las conexiones se
  notifican por `PeerHandler`→`NodeEvent.Connected`. **DHT/rendezvous (WAN, camino real)**:
  `startDht()` inicializa Kademlia y conecta a bootstrap; `advertise(rdv)` y `findPeers(rdv)`
  publican y descubren bajo la clave de rendezvous (los bytes HKDF se pasan hex-encoded a Go).
  **Mensajería**: `sendMessage(peerId, bytes)` abre un stream libp2p
  (`/krypta/msg/1.0.0`, con `network.WithAllowLimitedConn` para poder abrirlo sobre conexiones
  de relay, que son "limited") y entrega el blob; los mensajes entrantes llegan vía
  `MessageHandler` y se reemiten como `NodeEvent.StreamData` en el `Flow` de eventos.
  **La lectura del stream entrante está acotada** (6 sep 2026): `io.LimitReader(s,
  maxIncomingMessage+1)` y corte por encima de **1 MiB**. Ese stream lo puede abrir cualquier
  peer que sepa marcar al móvil —quién envía no se comprueba en Go, sino en Kotlin
  (`ChatService.onReceived` resuelve el contacto y descarta al desconocido)—, así que el
  `io.ReadAll` sin tope que había dejaba a un extraño reservar memoria sin fin en la app. El
  `+1` distingue "justo en el tope" de "se pasó"; sin él un mensaje cortado subiría como si
  estuviera entero. Es un tope de **seguridad**, no de producto: el buzón no admite blobs de
  más de 64 KiB, un trozo de archivo son 48 KiB y una foto en línea ≤58 KiB. Mismo arreglo en
  el handler del nodo ([infra/node/main.go](../infra/node/main.go)), que es público. Cubierto
  por `TestIncomingMessageIsBounded` (las dos orillas del límite).
  **Y las dos lecturas por líneas** (`MailboxFetch` y la sesión de wake) pasaron de
  `bufio.ReadBytes('\n')` —que crece sin límite si el otro extremo nunca manda el salto— a
  **buffer de tamaño fijo + `ReadSlice`**, que devuelve `bufio.ErrBufferFull` al llenarse:
  `mbxMaxLine` 128 KiB (cabe el sobre más grande: blob de 64 KiB → ~87 KiB en base64 + JSON +
  PeerID; es el mismo tope que usa el nodo al leer un depósito) y `wakeMaxLine` 4 KiB. Un
  `io.LimitReader` no sirve aquí: acotaría el **total** del stream y una retirada legítima de
  200 sobres son varios MB. La rebanada de `ReadSlice` solo vale hasta la siguiente lectura, y
  se consume en el acto con `json.Unmarshal` (que copia las cadenas al struct). Al desbordar se
  resetea el stream: lo no leído queda sin ack'ear y se reentrega, como en cualquier corte.
  **Relay v2 + DCUtR (gate de NAT) — funcionando**: el host del móvil trae `EnableRelay` +
  `EnableHolePunching`, anuncia su propia dirección `/p2p-circuit` (`AddrsFactory` con la addr del
  nodo de infra) y hace `ReserveRelay` explícito cada 30 s. Así dos móviles tras NAT se envían
  mensajes por el relay (verificado en vivo, `SENT`; y por el test `TestRelayMessagingLocal`).
  El nodo de infra debe correr con `ForceReachabilityPublic()` o no ofrece el protocolo `hop`.
  **Cliente de buzón (entrega offline)**: `MailboxPut(addrs, to, blob)` deposita ciphertext en
  el nodo (`/krypta/mbx/put/1.0.0`) y `MailboxFetch(addrs)` retira el buzón propio
  (`/krypta/mbx/get/1.0.0`), entregando cada sobre por `MailboxHandler` (id, from, ts, data) y
  ack'eando para que el nodo borre — cubierto por `TestMailboxPutFetch` con un mini-servidor
  en el test que habla el protocolo del nodo.
- **Cliente de wake**: `StartWake(addrs, handler)` mantiene el stream `/krypta/wake/1.0.0`
  al nodo con **reconexión automática en Go** (backoff 5 s; Cloudflare recicla la wss cada
  ~10 min) y dispara `OnWake` en cada aviso **y en cada (re)conexión** — así un aviso
  perdido durante la desconexión se cubre con la retirada de reconexión. `StopWake()` corta.
  Test: `TestWakeSubscribe` (mini-servidor del protocolo en el test).
- El Foreground Service vive en `:app` (`KryptaForegroundService`): necesita inyectar
  `ChatService` (:p2p-signaling), que este módulo no ve por el grafo de dependencias.

### `:data`
- Room: `MessageEntity` + `ContactEntity` + `RatchetSessionEntity` / `RatchetSeenEntity`,
  `MessageDao` / `ContactDao` / `RatchetDao` (Flow + suspend), `KryptaDatabase` (**v9**,
  `exportSchema=true` → `data/schemas/`), `Converters` (enum `MessageStatus` ↔ String).
  Desde la **v8** `messages.payload` guarda el **sobre en claro** (con `encrypted` marcando las
  filas anteriores, que aún son ciphertext de la clave estática y se convierten en segundo
  plano): lo exige el secreto hacia adelante, porque una clave de un solo uso no puede volver a
  abrir lo guardado. Lo que protege el historial es el cifrado de la base entera.
- **Migraciones reales** (`Migrations.kt`): preservan contactos + mensajes al subir de
  versión (antes `fallbackToDestructiveMigration` los borraba). `MIGRATION_2_3` (columna
  `verified`), `MIGRATION_3_4` (índice compuesto `messages(conversationId, timestamp)` que
  cubre el WHERE+ORDER BY de `observeConversation`), `MIGRATION_4_5` (columna `blocked`), `MIGRATION_5_6` (**quita** `sharedSecret`, con
  `secure_delete` para que las páginas viejas no queden legibles dentro del fichero),
  `MIGRATION_6_7` (tablas del ratchet), `MIGRATION_7_8` (`messages.ciphertext` → `payload` +
  bandera `encrypted`, renombrando en vez de copiar la tabla: el historial no tiene copia de
  seguridad y no puede existir a medias) y `MIGRATION_8_9` (versiones de protocolo por
  contacto). Las cinco últimas están **probadas en dispositivo** (`MigrationTest`, 5 casos).
  `DatabaseModule` usa `addMigrations(...)`
  + `fallbackToDestructiveMigrationFrom(1)` (red de seguridad solo para la v1 antigua). **A
  partir de aquí: cada cambio de esquema = nueva `Migration` + subir la versión.** Verificado
  en dispositivo: un contacto (con su flag `verified`) sobrevive al salto v3→v4, y los dos
  contactos reales del TECNO al v4→v5 (`user_version = 5`, `verified` intacto, `blocked = 0`).
- `RoomMessageRepository` / `RoomContactRepository` implementan los repos del dominio.
- `DataModule`: provee DB/DAOs y enlaza ambos repositorios.

## Toolchain y decisiones de build

- AGP 9.2.1 · Kotlin 2.2.10 · Gradle 9.4.1 · JDK 25 · Compose BOM 2026.02.01.
- `compileSdk 36.1` · `minSdk 30` · `targetSdk 36`.
- DI **Hilt 2.59.2** vía **KSP `2.2.10-2.0.2`** (debe coincidir con la versión de Kotlin).
  Room 2.8.4 · coroutines 1.11.0 · WorkManager 2.11.2 · lifecycle 2.9.4.
- **Gotcha KSP + Kotlin integrado de AGP 9:** `android.disallowKotlinSourceSets=false`
  en `gradle.properties` (ver CLAUDE.md). Sin esto, KSP rompe el build.

## Infra Go (`infra/node`) — semilla de Fase 1

Binario Go independiente (módulo propio, **no** gomobile): **bootstrap + DHT server +
Circuit Relay v2 + buzón store-and-forward + wake integrado** con identidad estable
(`node.key`). No lee mensajes (E2EE): punto de encuentro + relé + buzón de blobs opacos.

**Wake integrado** (`wake.go`; decisión del 2 jul 2026: **sin** servidor UnifiedPush
separado — como el buzón vive aquí, el nodo ya sabe el instante del depósito): el móvil
mantiene un stream `/krypta/wake/1.0.0` y el nodo le escribe `{"wake":true}` cuando le
llega correo (`mailbox.notify`), más `{"ping":true}` cada 50 s como keepalive (el corte por
idle de Cloudflare es ~100 s). El registro es el PeerID del stream (nadie se suscribe al
wake de otro) y el aviso no lleva payload ni remitente. Test: `TestWakeOnDeposit`.

**Buzón** (`mailbox.go`): protocolo JSON por líneas sobre streams libp2p —
`/krypta/mbx/put/1.0.0` (depósito `{v,to,blob}` → `{ok}`/`{err}`) y `/krypta/mbx/get/1.0.0`
(sobres `{id,from,ts,blob}` + `{done}`, el cliente responde `{ack:[ids]}` y solo eso se
borra; sin ack → reentrega, dedup en cliente por `id`). La identidad del stream autentica:
el GET solo entrega los sobres del PeerID remoto y el `from` lo fija el nodo (no
suplantable). Archivos JSON en `-mailboxdir` (default `<dir del key>/mailbox`), blob ≤ 64
KiB, ≤ 200 msgs / 5 MiB por destinatario, TTL 7 días (barrido horario). Tests:
`mailbox_test.go` (in-process, v0.38).

**Reparto justo del buzón** (8 sep 2026, hallazgo A-11 de la auditoría): la cuota global no
bastaba — como el PeerID se comparte abiertamente, cualquier desconocido podía llenar el buzón
de otro y **dejarlo sin entrega** (denegación, no solo spam). Ahora el nombre del fichero lleva
un hash corto del remitente (`<id>.<tag>.json`, y se siguen leyendo/borrando los `<id>.json`
antiguos), lo que permite contar cuota por remitente sin abrir un sobre: (1) un remitente puede
ocupar el buzón entero **mientras sea el único** que ha depositado —el caso legítimo del archivo
troceado grande a un contacto desconectado—; (2) en cuanto hay correo de otro, ninguno pasa de
la mitad; (3) si el buzón se llena, se desaloja lo más antiguo de quien se pasó de su reparto.
Tests: `mailbox_fairshare_test.go`.

**Otros topes del nodo** (mismo día): el **relay** pasó de `WithInfiniteLimits()` —ancho de
banda gratis para cualquier peer de internet— a límites **finitos y holgados** (8 GiB / 6 h por
conexión relayada, muy por encima de una llamada de vídeo, que consume ~112 MB/h), y se
subieron los cupos de plazas, que seguían en los de fábrica y eran **demasiado bajos para
producción**: 128 reservas totales, 8 por IP y **32 por ASN** — y una ASN es una operadora móvil
entera. Ahora 4096 / 256 / 2048. El **wake** acepta como máximo 2000 suscripciones simultáneas
(antes ninguna, y cada una cuesta un stream y dos goroutines).

**Pinneado a go-libp2p v0.38 + Go 1.22** (no v0.48 como el bridge) **a propósito**: v0.48
exige Go ≥1.25, cuyos binarios piden macOS ≥11, pero el host de despliegue es una **Mac
Catalina (10.15)**. v0.38 compila con Go 1.22 → binario `minos 10.13` que corre en Catalina
e **interopera** con los móviles (v0.48), porque los protocolos libp2p son compatibles entre
versiones. Binario en `infra/node/dist/krypta-node-catalina`; build (con `GOTOOLCHAIN=local`)
y despliegue **sin Docker** (launchd + puertos / **Cloudflare Tunnel**) en
[infra/node/README.md](../infra/node/README.md).

**Transportes y exposición.** El nodo escucha TCP (4001) + QUIC + **WebSocket**
(`/ip4/0.0.0.0/tcp/8081/ws`, flag `-wsport`). Como el host **no tiene IP pública** y se expone
por **Cloudflare Tunnel** (que solo transporta HTTP/HTTPS/**WebSocket sobre 443**, ni TCP crudo
ni UDP/QUIC), la vía WAN es **`wss`**: cloudflared mapea `krypta.neto.chat → http://localhost:8081`
y el edge da el TLS. Los móviles marcan `/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>` (su
libp2p ya trae el transporte WebSocket; **no se recompila la app**). El Noise de libp2p va
dentro del WebSocket → Cloudflare no lee (E2EE) ni suplanta.

## Toolchain nativo (Go / gomobile) — Fase 0

- **Herramientas:**
  - **Go 1.26.4**, fijado con `toolchain` en `go.mod`;
  - **gomobile y gobind**, a la versión de `golang.org/x/mobile` de `go.mod` (los instala el
    script);
  - **NDK 26.1.10909125**;
  - **JDK 25**.

  Hasta el 14 sep 2026 los AAR salieron en realidad con el **NDK 25.2**: `~/.zprofile` exporta
  `ANDROID_NDK_HOME` y el script lo respetaba. Ahora lo ignora y comprueba la versión.
- **Build del AAR:** `native-bridge/libp2p/build-aar.sh` (envuelve `gomobile bind`).
  **Reproducible desde el commit `8d02875`**, gracias a:
  - `-trimpath`;
  - compilar desde la ruta fija `/tmp/krypta-aar`;
  - el commit inyectado en `Version()` con `-ldflags -X`;
  - `proguard.txt` con fecha fija;
  - herramientas comprobadas.

  Dos clones de ese commit, en rutas distintas y con cachés vacías, dieron el mismo AAR
  (`d817bae1…f4e0`). El script falla si alguna ABI no tiene páginas de 16 KB, no lleva el commit
  o conserva rutas locales.
- **Gotcha obligatorio:** `-ldflags="-checklinkname=0"`. go-libp2p usa
  `github.com/wlynxg/anet`, que hace `//go:linkname` contra `net.zoneCache`; Go ≥ 1.23 lo
  bloquea y el enlazado falla ("invalid reference to net.zoneCache") sin esa flag.
- **AAR ~65 MB** con `.so` por ABI (~34 MB c/u). **Release endurecido (12 jul 2026)**:
  `assembleRelease` corre **R8** (`optimization { enable = true }` +
  `android.r8.gradual.support=true` en gradle.properties + `app/proguard-rules.pro`, cuyas
  reglas críticas preservan `go.**` y `chat.neto.krypta.bridge.**` — el puente gomobile los
  resuelve por nombre vía JNI). Con `-PslimAbi` el APK release arm64 queda en **~44 MB**
  (vs ~74 MB debug; el suelo es `libgojni.so`). Firmado con la clave debug para smoke-tests
  (publicar exigirá keystore propio); verificado en dispositivo: nodo, WAN, descifrado y
  envío funcionan minificados.
- **El `.aar` no se versiona** (decidido el 31 jul 2026): pesaría ~75 MB en la historia por
  cada regeneración. Cada clon lo genera con `build-aar.sh`, y desde el commit `8d02875` esa
  compilación es reproducible, así que un AAR publicado se puede comprobar compilando su commit.

## Verificación realizada

- `:app:assembleDebug` y `assembleDebug` (todos los módulos) ✅
- `:p2p-signaling:testDebugUnitTest` (4 tests de rendezvous) ✅
- `:app:connectedDebugAndroidTest` en TECNO KM5s (Android 15) ✅:
  - pipeline JNI: `ping`/`version`/`sum` cruzan la frontera gomobile→Kotlin ✅
  - **`libp2pHost_startsAndExposesPeerId`**: un host go-libp2p real arranca en el
    dispositivo, genera identidad Ed25519, abre TCP+QUIC y devuelve PeerID + multiaddrs ✅
- **Descubrimiento por rendezvous (DHT):**
  - `go test` en `native-bridge/libp2p` (`TestRendezvousDiscovery`): 2 nodos en proceso,
    A anuncia y B lo descubre por DHT en ~1s ✅ (prueba determinista del mecanismo)
  - **en vivo, en dispositivo** (`KryptaDiscoveryDeviceTest`): el móvil bootstrapea a un
    `infra/node` corriendo en el Mac (vía `adb reverse tcp:4101`), se anuncia bajo el
    rendezvous y descubre al nodo por la DHT ✅
- **Intercambio de mensaje por stream libp2p:**
  - `go test` (`TestMessageExchange`): B descubre a A y le envía un mensaje que A recibe ✅
  - **en vivo**: el móvil abre un stream al `infra/node` del Mac y le entrega un mensaje ✅
- **Cifrado E2EE del payload:**
  - `:p2p-signaling:testDebugUnitTest` (`AesGcmMessageCipherTest`): round-trip,
    nonce único, secreto incorrecto y ciphertext manipulado fallan ✅
  - **en vivo, en dispositivo**: el móvil cifra con AES-256-GCM y envía el **ciphertext**;
    el `infra/node` registra solo bytes opacos (59 B) — el texto plano nunca aparece ✅
- **Lazo de dominio (`ChatService`)** — `:p2p-signaling:testDebugUnitTest`
  (`ChatServiceTest`, con fakes + cipher real): `send` cifra/persiste(SENT)/transmite
  ciphertext; un entrante se resuelve por PeerID, se persiste DELIVERED y descifra; un
  remitente desconocido se ignora ✅
- **Borrado local (vaciar chat / eliminar contacto, 17 jul 2026)**: `ChatServiceTest`
  (vaciar borra mensajes + pide borrar archivos y conserva el contacto; eliminar borra
  también el contacto) y `DiskFileStoreTest` (`deleteLocal` borra ensamblado + staging +
  copia propia, es idempotente y **nunca** toca rutas fuera de `krypta_files/`) ✅;
  **en vivo** (TECNO, 17 jul): contacto desechable → long-press → vaciar → menú ⋮ →
  eliminar → desaparece de la lista, contactos reales intactos ✅
- **Buzón store-and-forward (entrega offline)**:
  - `go test` en `infra/node` (`TestMailboxStoreAndForward`, `TestMailboxQuotaAndTTL`):
    depósito/retirada autenticada por stream, ack→borrado, reentrega sin ack, `from` no
    suplantable, cuotas y TTL ✅
  - `go test` en `native-bridge/libp2p` (`TestMailboxPutFetch`): el cliente del bridge
    contra un servidor del protocolo del nodo ✅
  - `ChatServiceTest`: fallo directo → buzón → `SENT`; ambos fallan → `FAILED` (sin crash);
    reentrega con el mismo id de sobre no duplica ✅
  - **en vivo** (2 jul 2026): nodo redeployado en la Catalina; con el móvil B cerrado, A
    envía → `SENT` (buzón) → al abrir B el mensaje llega y descifra ✅. Además
    `TestMailboxFetchAgainstLiveNode` (sonda bajo demanda, `MBX_ADDR=…`) confirma desde
    fuera que el nodo en producción atiende `/krypta/mbx/get/1.0.0` vía wss/Cloudflare ✅
- **Wake + recepción con la app cerrada (Fase 5)**:
  - `go test` nodo (`TestWakeOnDeposit`): un depósito despierta al suscrito y solo a él ✅
  - `go test` bridge (`TestWakeSubscribe`): OnWake al (re)conectar y por aviso; StopWake ✅
  - `ChatServiceTest`: `WakeReceived` → retirada inmediata; WAN on/off ↔ suscripción ✅
  - en dispositivo: `KryptaForegroundService` corre como FG service (`isForeground=true`,
    dataSync) con su notificación persistente ✅
  - **en vivo (3 jul 2026)**: nodo redeployado; sonda desde el Mac (`TestWakeAgainstLiveNode`
    y `TestMailboxPutAgainstLiveNode`, bajo demanda): un depósito para el móvil dispara la
    retirada en **~3 s** (vs. 30 s del bucle) ✅; entre 2 móviles, con la app cerrada, el
    receptor **notifica el mensaje en segundos** y el diagnóstico registra
    `← mensaje … (buzón)` ✅. Nota: TECNO silencia el logcat de la app (limitador OEM) —
    para verificar úsese el panel de Diagnóstico in-app, no logcat. Se observó throttling
    del bucle con la UI recién cerrada (~min sin ciclos) → refuerza el pendiente de
    exención de batería/OEM.
- **Acuerdo de claves X25519** — `go test` (`TestSharedSecretSymmetry`): el secreto que A
  deriva con B es igual al que B deriva con A, y un tercero obtiene otro distinto ✅
- **UI de chat en dispositivo**: arranca, muestra **"Mi PeerID"** + campo **"Nodo WAN"**,
  renderiza contactos y arranca host + mDNS (+ DHT si hay bootstrap) en background sin crash
  (`am start -W` + captura) ✅
- **Nodo Catalina**: `infra/node/dist/krypta-node-catalina` compilado con Go 1.22 (`minos
  10.13`), arranca e imprime PeerID con **bootstrap + DHT + relay v2** ✅
- **WAN end-to-end (1 dispositivo)**: pegando la multiaddr del nodo Catalina en "Nodo WAN"
  (vía `adb reverse`), el móvil (v0.48) **se conecta a la DHT del nodo (v0.38)** — diagnóstico
  muestra "WAN (DHT): conectado" + log de rendezvous. Prueba la interop v0.38↔v0.48 y el bucle
  WAN auto-reparable ✅
  - El nodo Catalina ahora publica también `/tcp/8081/ws` (para Cloudflare). Falta la prueba
    real **vía `wss` por Cloudflare Tunnel** y con **2 móviles** (queda para el despliegue).

## Próximos pasos (según el plan)

> **Recordatorio de objetivo:** Krypta es para **WAN**. mDNS es solo un atajo para la prueba
> LAN inmediata; el descubrimiento real es **DHT + rendezvous** (ya implementado en el bridge,
> pendiente de desplegar la infra y de orquestarlo por contacto en el cliente).

1. **Desplegar el nodo en la Mac Catalina vía Cloudflare Tunnel** (sección WSS de
   [infra/node/README.md](../infra/node/README.md)): `krypta.neto.chat` → `localhost:8081`, y
   pegar `/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>` en "Nodo WAN". Pruebas con 2 móviles
   (LAN por mDNS, WAN por el nodo).
2. **DCUtR + endurecer relay (gate NAT)**: con 2 teléfonos en **CGNAT** de operadoras
   distintas, medir conexión directa (DCUtR) vs fallback a relay. El relay ya está activo en
   el nodo; falta validar/instrumentar. Bloqueado por hardware (2 SIMs).
3. **DCUtR en celular (gate NAT)**: medir upgrade directo vs. relay con 2 SIMs CGNAT.
4. **Ahorro de batería fino**: bajar el ritmo del `wanLoop` cuando el wake está conectado
   (hoy 30 s fijos; con wake+exención de Doze podría espaciarse bastante).
5. **QR del número de seguridad** (mejora UX de la verificación anti-MITM ya existente):
   escanear en vez de leer 60 dígitos — requiere CameraX + lector.
6. ~~**Ventana de solape** del rendezvous en el cambio de día (UTC).~~ **Hecho** (8 sep 2026):
   `RendezvousService.rendezvousWindow` usa las dos claves contiguas durante 2 h a cada lado de
   la medianoche UTC. Iba en el mismo cambio que la corrección del anuncio de rendezvous, que
   hasta entonces republicaba las claves viejas para siempre y tapaba el agujero.
7. **Fase 1 (infra)**: desplegar ~5 nodos fijos (hoy hay 3: VPS de São Paulo + Mac + Windows).
8. ~~**Depósito ciego en el buzón**~~: que el nodo deje de ver el PeerID de emisor y destinatario
   en claro (etiqueta derivada del secreto compartido, como el rendezvous). **Hecho y encendido
   por contacto el 12 sep 2026** (`ChatService.BLIND_MIN_PROTOCOL = 2`: se deposita bajo
   etiqueta a quien anuncie protocolo ≥ 2, por PeerID al resto) — ver
   [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) §6. Falta la prueba con dos móviles.
9. **Redespliegue de los nodos**: los cambios de `infra/node` (reparto justo, límites del relay,
   tope de wake, lectura acotada del 6 sep) **no están en producción hasta redesplegar**
   (`deploy-vps.sh`, `deploy-catalina.sh`, copia del `.exe` en el PC).
