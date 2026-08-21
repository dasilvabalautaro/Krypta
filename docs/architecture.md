# Arquitectura de Krypta — estado actual

> Documento vivo. Refleja **lo que existe en el repo ahora**, no el diseño objetivo
> completo (ese está en [PLAN-senalizacion-descentralizada.md](PLAN-senalizacion-descentralizada.md)).
> Última actualización: 2 jul 2026 (wake integrado en el nodo + Foreground Service con
> notificaciones — recibir con la app cerrada; el mismo día: buzón E2EE store-and-forward,
> verificado en vivo).

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
| `:data` | `chat.neto.krypta.data` | Persistencia Room (DB v4 con migraciones reales) + repos + `DataModule` (Hilt) | Funcional |
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
  flag anti-MITM tras cotejar el número de seguridad),
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
    cancelaría y la soltada nunca llegaría). Alta de contacto (solo **nombre + PeerID**).
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
  (`nonce || ct+tag`). v1 sin forward-secrecy (sin ratchet) — trabajo futuro.
- `SafetyNumber` — **número de seguridad anti-MITM** (estilo Signal): 60 dígitos decimales
  de `SHA-256(dominio ‖ peerId_menor ‖ peerId_mayor)`, **simétrico** (ambos ven el mismo) y
  determinista. Como el PeerID *es* la clave pública, el intercambio no tiene MITM en la
  matemática; este número sirve para cotejar fuera de banda que nadie **sustituyó el PeerID**
  en el canal por el que se compartió. `ChatService.safetyNumber(contact)` +
  `setVerified(contact, bool)` → `Contact.verified` (Room). Cubierto por `SafetyNumberTest`.
- `Hkdf` — util HKDF-SHA256 (RFC 5869) compartido por rendezvous y cifrado.
- `IdentityBackup` + `BackupManager` — **respaldo de identidad** (archivo `.krbk`):
  `"KRBK1" ‖ salt(16) ‖ nonce(12) ‖ AES-256-GCM(payload)` con el magic como AAD; clave por
  PBKDF2-HMAC-SHA256 (310k iteraciones) de la frase-clave del usuario. El payload lleva la
  identidad Ed25519 en base64 y una línea por contacto (nombre b64 | PeerID | verificado);
  los **secretos compartidos no viajan** — `BackupManager.import` los re-deriva por ECDH de
  la identidad importada (`Libp2pNode.sharedSecretFor(identityBytes, peerId)`) y upserta
  los contactos. `Libp2pNode.importIdentityBytes` valida con `Bridge.peerIDForIdentity` y
  persiste en las prefs `krypta_identity`; como la identidad en uso es `lazy` y el host ya
  corre, **rige al reiniciar el proceso** (la UI cierra Krypta; el FGS sticky lo revive).
  Cubierto por `IdentityBackupTest` (round-trip, passphrase errónea, manipulación, archivo
  ajeno, nada en claro) y verificado en vivo (export → import → reinicio → mismo PeerID).
- `MessageEnvelope` — sobre de aplicación **dentro** del cifrado E2EE. Tipos: `T` texto,
  `R` acuse de lectura, `I` imagen JPEG en línea, `F` meta de archivo, `K` trozo de archivo,
  `D` descriptor local de archivo (no viaja). `decode` tolera bytes sin sobre (mensajes legado)
  devolviendo null. Cubierto por `MessageEnvelopeTest`.
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
- `ChatService` — **orquestador de dominio** (cierra el lazo): `send(contact, plaintext)`
  cifra un **sobre** (`MessageEnvelope`, que lleva el id del mensaje) → persiste `Message`
  (PENDING→SENT) → `signaling.send`; si el envío directo falla (peer offline / NAT sin ruta),
  **cae al buzón** (`signaling.sendOffline` → `SENT`, log "→ buzón"); solo si el buzón también
  falla queda `FAILED` (nunca crashea). `retry(contact, msgId)` reintenta un FALLIDO reusando
  su ciphertext (mismo id, sin duplicar). **Borrado local (17 jul 2026)**:
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
  `filesDir/krypta_files/sent/`; `sendVoiceNote` llama a `sendFile(..., localPath=…)` (param
  nuevo: el emisor conserva su copia y su burbuja también reproduce); un `File` con mime
  `audio/*` y copia local se pinta como burbuja con play/pausa+progreso (`AudioNote`,
  MediaPlayer por burbuja); el micro sustituye a "Enviar" con el borrador vacío (permiso
  RECORD_AUDIO en el primer uso) y `notificationText` da "🎤 Nota de voz".
  `decrypt()` sigue para texto (fallback a legado sin sobre).
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
- Room: `MessageEntity` + `ContactEntity` (BLOB para `ciphertext`/claves), `MessageDao` /
  `ContactDao` (Flow + suspend), `KryptaDatabase` (**v4**, `exportSchema=true` →
  `data/schemas/`), `Converters` (enum `MessageStatus` ↔ String).
- **Migraciones reales** (`Migrations.kt`): preservan contactos + mensajes al subir de
  versión (antes `fallbackToDestructiveMigration` los borraba). `MIGRATION_2_3` (columna
  `verified`), `MIGRATION_3_4` (índice compuesto `messages(conversationId, timestamp)` que
  cubre el WHERE+ORDER BY de `observeConversation`). `DatabaseModule` usa `addMigrations(...)`
  + `fallbackToDestructiveMigrationFrom(1)` (red de seguridad solo para la v1 antigua). **A
  partir de aquí: cada cambio de esquema = nueva `Migration` + subir la versión.** Verificado
  en dispositivo: un contacto (con su flag `verified`) sobrevive al salto v3→v4.
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

- **Go 1.26.4** (Homebrew) · **gomobile/gobind** (`~/go/bin`) · **NDK 26.1.10909125**.
- Build del AAR: `native-bridge/libp2p/build-aar.sh` (envuelve `gomobile bind`).
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
  envío funcionan minificados. **Decisión abierta:** si versionar el `.aar` en git (repo
  pesado pero build sin Go) o ignorarlo y exigir `build-aar.sh` (repo ligero, requiere
  Go+NDK).

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
6. **Ventana de solape** del rendezvous en el cambio de día (UTC).
7. **Fase 1 (infra)**: desplegar ~5 nodos fijos (hoy hay 1: bootstrap + relay + buzón + wake).
