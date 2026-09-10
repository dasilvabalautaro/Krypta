# Plan: Capa de Señalización Descentralizada de Krypta (v1 Android nativo / Kotlin)

> **Nota de revisión (25 jun 2026).** Este plan se redactó originalmente para un cliente
> **React Native + TypeScript** (paquetes TS, DI con inversify, bridge TurboModule, tests
> Jest). El entorno cambió: ahora Krypta es una **app Android nativa en Kotlin con Jetpack
> Compose** (módulo `:app`, `namespace chat.neto.myapplication`, Kotlin 2.2.10, AGP 9.2.1,
> Compose BOM 2026.02.01, `minSdk 30` / `targetSdk 36`). Se han reescrito las capas de
> cliente a Kotlin/Android. **Las decisiones de arquitectura, criptografía e infra Go se
> mantienen** porque son independientes del lenguaje del cliente.

## Contexto

La especificación técnica v1.0 de Krypta (PDF) describe una mensajería P2P E2EE, pero su
descubrimiento se basa en **mDNS** (`_krypta._tcp.local`), que **solo funciona en la misma
red WiFi**. `cardumen.md` identifica el problema real: hace falta una capa de
**descubrimiento y señalización descentralizada a través de internet, sin servidor
central**, para sustituir el clásico servidor de señalización WebRTC.

Este plan diseña esa capa para **Android nativo**. Decisiones cerradas con el usuario
(siguen vigentes):

- **v1 es solo Android** (se aplaza iOS y Huawei).
- **Riesgo 1 (privacidad del descubrimiento)** — descartamos `SHA-256(teléfono)+salt`
  (enumerable y rompe el grafo social). Se usa **rendezvous por pares**: el punto de
  encuentro se deriva del secreto compartido que dos contactos ya tienen tras intercambiar
  claves públicas → `rendezvous = HKDF(shared_secret, fecha_del_día)`. Rotativo, no
  enumerable; la DHT nunca ve identificadores reales.
- **Riesgo 2 (CGNAT/relays)** — infraestructura mínima auto-hospedada: bootstrap + DHT
  server + **Circuit Relay v2** como fallback garantizado. Relays = tuberías tontas (todo
  E2EE): descentralización de *confianza*, no de infraestructura.
- **Entrega offline** — **buzón cifrado store-and-forward** (E2EE, TTL ~7 días) en los
  nodos de infraestructura.
- **Riesgo 3 (clientes efímeros + despertador)** — los móviles operan como **DHT client**
  (no server). Un **Foreground Service** mantiene el nodo libp2p pre-calentado. Wake
  mediante **servidor propio compatible UnifiedPush + websocket por FG service** (sin
  Google). El push solo lleva un token silencioso, nunca payload.
- **Runtime libp2p** — **go-libp2p compilado con gomobile a un `.aar`**, consumido
  **directamente desde Kotlin/JNI** (ya no hay bridge RN). Implementación más madura para
  Relay v2 / DCUtR / AutoNAT.

Objetivo: reemplazar `DiscoveryService (mDNS)` por una capa P2P real, manteniendo
arquitectura SOLID e inyección de dependencias (ahora con **Hilt**), interfaces en el
módulo `:core`.

## Cambios de entorno respecto al plan original (RN/TS → Kotlin/Android)

| Antes (React Native + TS) | Ahora (Android nativo + Kotlin) |
|---|---|
| `packages/core` en TypeScript | módulo Gradle **`:core`** en Kotlin |
| DI con **inversify** | DI con **Hilt** (`@Module`/`@Provides`, `@HiltViewModel`) |
| `packages/p2p-signaling` (TS) | módulo **`:p2p-signaling`** (Kotlin + coroutines/Flow) |
| Bridge **TurboModule** RN ↔ nativo | **wrapper Kotlin/JNI directo** sobre el AAR de gomobile |
| Promesas/Observables JS | **coroutines + `Flow`/`StateFlow`** |
| SQLite genérico / `ContactRepository` | **Room** (`@Entity`/`@Dao`) en módulo `:data` |
| Reintentos ad-hoc | **WorkManager** para reconciliación/reintento |
| UI React Native | **Jetpack Compose** (módulo `:app` ya existente) |
| Tests **Jest** | **JUnit + `kotlinx-coroutines-test`** (unit) + **androidTest** (instrumentado) |

La capa **infra (Go)** no cambia: sigue siendo go-libp2p server + mailbox + wake-server.

## Riesgos abiertos a mitigar durante la ejecución (críticas a `update.md`)

- **Relay para medios (audio/vídeo) es caro y añade latencia.** Texto/señalización por
  relay: OK. Llamadas CGNAT↔CGNAT: pueden degradar o requerir TURN. Documentar límite.
- **DCUtR no perfora NAT simétrico↔simétrico de forma fiable** → relay siempre como
  fallback, no como excepción.
- **OEM battery killers** (Xiaomi/Samsung/Oppo): matan incluso FG services. Pedir exención
  de batería (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) + reconexión + push como red de
  seguridad.
- **Ventana de wake "10s" optimista**: mantener el FG service pre-calentado; el cold-start
  de libp2p + handshake + pull de buzón no cabe en 10s.
- **El wake server filtra metadatos de timing** aunque el token sea silencioso. Mitigar con
  heartbeats/batching y documentarlo; no prometer "privacidad intacta 100%".
- **Anti-abuso**: relays, buzón y canal de wake necesitan rate-limit + acceso autenticado.
- **Máquina de estados del mensaje**: `SENT` = guardado en buzón, `DELIVERED` = el receptor
  lo retiró, `READ` = visto.
- **Tamaño del AAR / ABIs**: go-libp2p vía gomobile genera binarios por ABI (arm64-v8a,
  armeabi-v7a, x86_64). Usar splits/ABI filters para no inflar el APK; vigilar tiempo de
  arranque del runtime Go en dispositivos de gama baja.

## Arquitectura objetivo

```
[ App Android (Kotlin) ]                          [ Infra mínima auto-hospedada (los "5 nodos") ]
 ┌─────────────────────────────┐                   ┌──────────────────────────────────────────┐
 │ :app  Jetpack Compose + VM  │                   │ go-libp2p en modo SERVER:                 │
 │ :core interfaces SOLID (DI  │                   │  • Bootstrap + Kademlia DHT server        │
 │       Hilt) + modelos       │   libp2p (Noise)  │  • Circuit Relay v2 (fallback NAT)        │
 │ :p2p-signaling (coroutines) │ ◀───────────────▶ │  • Mailbox store-and-forward (E2EE, TTL)  │
 │   ▲ rendezvous (HKDF)       │                   │ Wake server (UnifiedPush-compat, WS/HTTP2)│
 │ :native-bridge Kotlin/JNI   │ ◀── wake token ── │  • token silencioso, sin payload          │
 │   └ go-libp2p AAR (gomobile)│                   └──────────────────────────────────────────┘
 │ :data Room (SQLite)         │
 │ ForegroundService (Kotlin)  │
 └─────────────────────────────┘
```

- **Señalización + texto/archivos**: streams libp2p directos (autenticados/cifrados con
  Noise). **WebRTC solo para media** de llamadas (SRTP), señalizado por esos streams —
  en Android vía `stream-webrtc-android` (libwebrtc).
- **Móvil = DHT client**; solo la infra corre DHT server.

## Estructura de módulos Gradle (sustituye la estructura `packages/` de la spec)

```
Krypta/
  app/                       # EXISTE: Jetpack Compose UI + ViewModels (@HiltViewModel)
  core/                      # NUEVO módulo: interfaces SOLID, modelos, DI Hilt
    ISignalingService.kt     #   abstracción que reemplaza IDiscoveryService (mDNS)
    model/ (Message, Contact, MessageStatus, …)
  p2p-signaling/             # NUEVO módulo (Kotlin + coroutines/Flow)
    RendezvousService.kt     #   HKDF(shared_secret, día) → topic/clave de provider
    SignalingService.kt      #   orquesta bridge nativo, mailbox, estados; expone Flow
  native-bridge/             # NUEVO módulo
    libp2p/                  #   módulo Go (go-libp2p) → gomobile → libs/krypta-p2p.aar
    Libp2pNode.kt            #   wrapper Kotlin/JNI: start/stop, dial, streams, eventos
    KryptaForegroundService.kt  # mantiene nodo pre-calentado + WS al wake-server
  data/                      # NUEVO módulo: Room (@Entity/@Dao), repositorios
  infra/                     # NUEVO (backend Go, no es módulo Gradle)
    node/                    #   go-libp2p server: bootstrap + DHT + relay v2 + mailbox
    wake-server/             #   servidor de despertar (UnifiedPush-compatible)
    deploy/                  #   docker-compose / terraform / ansible para los nodos fijos
  docs/
    PLAN-senalizacion-descentralizada.md   # este archivo
    architecture.md          # actualizar: reconciliar spec + cardumen + update + este plan
    security-model.md        # actualizar: rendezvous, metadatos de wake, anti-abuso
```

Dependencias entre módulos: `:app` → `:p2p-signaling`, `:data` → `:core`;
`:p2p-signaling` → `:native-bridge`, `:core`. Las interfaces (`:core`) permiten DI y tests.

Reemplazos/ediciones sobre lo que la spec ya define:
- `DiscoveryService (mDNS)` → **sustituido** por `:p2p-signaling`. Mantener
  `IDiscoveryService`/`ISignalingService` en `:core` para DI.
- `ConnectionManager` → usar relay/DCUtR en vez de IP directa de mDNS.
- `Message` + `MessageService` → mapear estados al modelo de buzón (Room + WorkManager).

## Fases de implementación (spike-first)

### Fase 0 — SPIKE de viabilidad (gate go/no-go)
PoC mínima: **2 teléfonos Android con SIMs de operadora distintas (CGNAT real)** que se
encuentran vía bootstrap + DHT, intentan DCUtR, caen a Relay v2 e intercambian un mensaje
por un stream libp2p.
- Compilar go-libp2p a `krypta-p2p.aar` con **gomobile** e integrarlo en una app Android
  de prueba (consumo directo desde Kotlin, sin RN).
- **Métricas de aceptación**: % de éxito de conexión directa (DCUtR) vs relay, latencia de
  establecimiento, tamaño del AAR por ABI, viabilidad del runtime Go en background. Si DCUtR
  es muy bajo, confirmar que el fallback a relay es aceptable en coste/latencia.

> **Estado (25 jun 2026) — pipeline, host y descubrimiento validados; falta el gate de NAT.**
> ✅ go-libp2p compila vía gomobile a `native-bridge/libs/krypta-p2p.aar` (~70 MB, 4 ABIs)
>    con `-ldflags="-checklinkname=0"` (workaround de `wlynxg/anet`/`net.zoneCache`).
> ✅ Integrado en Kotlin (`Libp2pNode` → `Bridge`/`Node`); un host real (Ed25519, TCP+QUIC)
>    arranca **en dispositivo** (TECNO KM5s, Android 15) y expone PeerID + multiaddrs.
> ✅ **Descubrimiento por rendezvous en DHT** validado: (a) `go test` determinista en
>    proceso (`TestRendezvousDiscovery`); (b) **en vivo en el móvil** — el teléfono
>    bootstrapea a un `infra/node` (bootstrap + DHT server, semilla de Fase 1) en el Mac
>    vía `adb reverse`, se anuncia bajo el rendezvous y descubre al nodo por la DHT.
> ✅ **Mensaje por stream libp2p** (`/krypta/msg/1.0.0`): `go test` (`TestMessageExchange`)
>    y **en vivo** — el móvil abre un stream al `infra/node` y le entrega un mensaje (visto
>    en el log del nodo). `Libp2pNode.sendMessage` + handler de recepción → `Flow` de eventos.
> ✅ **Cifrado E2EE del payload** (`MessageCipher` = AES-256-GCM, clave vía HKDF del
>    `sharedSecret`): unit tests (round-trip, manipulación/secreto-incorrecto fallan) y
>    **en vivo** — el móvil envía ciphertext y el `infra/node` solo ve bytes opacos.
> ✅ **Lazo de dominio cerrado** (`ChatService`): cifra→persiste(Room, PENDING→SENT)→envía;
>    los entrantes se resuelven por PeerID, se persisten DELIVERED y se descifran bajo demanda.
>    `Contact` lleva `peerId`+`sharedSecret`; `SignalingService.send` entrega al `peerId`.
>    Persistencia Room (mensajes + contactos, DB v2). Unit-tested (`ChatServiceTest`).
> ✅ **UI de chat (Compose)**: `ChatViewModel` + pantallas (contactos · alta · chat) sobre
>    `ChatService`; muestra "Mi PeerID". Verificada en dispositivo (sin crash).
> ✅ **Acuerdo de claves X25519 (ECDH desde el PeerID)**: identidad Ed25519 **persistente**;
>    el secreto compartido se deriva de tu privada + la pública embebida en el PeerID del
>    contacto. Alta solo con **nombre + PeerID** (sin passphrase). `go test` de simetría ✅.
> ✅ **Arranque desde la app + descubrimiento LAN (pruebas)**: el `ChatViewModel` arranca el
>    host al iniciar y activa **mDNS** (auto-conexión + `MulticastLock`); los peers conectados
>    salen como "● en línea". **mDNS es solo atajo de prueba LAN**; el descubrimiento WAN real
>    sigue siendo DHT + rendezvous (en el bridge, pendiente de infra + orquestación por contacto).
> ✅ **WAN cableado de punta a punta**: la app tiene campo "Nodo WAN (bootstrap)"; `ChatService`
>    se une a la DHT por ese nodo y corre un **bucle de rendezvous por contacto**
>    (`advertise`/`findPeers` de `HKDF(sharedSecret, día)`). El **`infra/node`** ya hace
>    **bootstrap + DHT + Circuit Relay v2**, compilado para **macOS Catalina** (go-libp2p v0.38
>    + Go 1.22, `minos 10.13`; interopera con los móviles v0.48). Guía sin Docker (launchd) en
>    `infra/node/README.md`.
> ✅ **Modo diagnóstico** en la app (estado WAN/DHT + log en vivo: rendezvous, peers, mensajes,
>    errores). Probado de punta a punta en 1 móvil contra el binario Catalina (v0.48↔v0.38):
>    "WAN (DHT): conectado".
> ✅ **WAN vía Cloudflare Tunnel (sin IP pública)**: el host (Mac Catalina) no tiene IP pública;
>    se expone por cloudflared. Como Cloudflare solo transporta WebSocket (no TCP/UDP crudo), el
>    nodo escucha también en `/tcp/8081/ws` y se publica como `wss` por 443
>    (`/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>`); la app no se recompila. `ChatService`
>    tiene **bucle WAN auto-reparable** (reconecta cada 30 s; `StartDHT` idempotente en Go) por
>    el reciclado de WebSockets de Cloudflare (~10 min). Verificado en 1 móvil (v0.48↔v0.38).
> ✅ **(2 jul 2026) Buzón E2EE store-and-forward (Fase 1 infra + Fase 4 cliente, parcial)**:
>    el nodo guarda blobs opacos por destinatario (`/krypta/mbx/put|get/1.0.0`, JSON por
>    líneas; autenticación por identidad del stream, ack→borrado, cuotas, TTL 7 días) y el
>    móvil cae al buzón cuando el envío directo falla (`SENT`) y retira el suyo en cada ciclo
>    del `wanLoop` (dedup por id de sobre → Room REPLACE). Tests Go en ambos lados + JUnit.
>    Nota: la mensajería WAN por **Circuit Relay v2 entre 2 móviles tras NAT ya está verificada
>    en vivo** (26 jun-1 jul 2026) — 4 fixes documentados en CLAUDE.md/architecture.md.
>    **Verificado en vivo** (2 jul 2026): con el móvil B cerrado, A envía → `SENT` (buzón) →
>    al abrir B el mensaje llega; sonda `TestMailboxFetchAgainstLiveNode` contra producción ✅.
> ✅ **(2 jul 2026) Wake + recepción con la app cerrada (Fase 5, decisión revisada)**: en vez
>    del wake-server UnifiedPush separado, el wake va **integrado en el nodo** (el buzón vive
>    allí, así que el nodo ya sabe el instante del depósito): stream ligero
>    `/krypta/wake/1.0.0` con keepalive 50 s + reconexión en Go, y en Android un **Foreground
>    Service real** (`:app`, dataSync, START_STICKY) que mantiene nodo+wake vivos y notifica
>    cada mensaje (descifrado al vuelo, omitido con la UI visible). Tests Go (nodo y bridge) +
>    JUnit. La compatibilidad UnifiedPush queda como opción futura si algún día interesa
>    interoperar con distribuidores de terceros.
>    **Verificado en vivo** (3 jul 2026): depósito → retirada en ~3 s; con la app cerrada,
>    la notificación del mensaje suena en segundos en el receptor ✅.
> ✅ **(3 jul 2026) Endurecimiento de segundo plano**: FGS `specialUse` (evita el corte a las
>    6 h de `dataSync` en Android 15 y permite arrancar en `BOOT_COMPLETED`), **exención de
>    batería** (verificada en la whitelist de deviceidle), `BootReceiver` (rearma tras
>    reinicio) y `NetworkCallback`→`kickWan()` (reconexión inmediata al cambiar de red; la
>    espera del `wanLoop` es ahora interrumpible).
> ✅ **(3 jul 2026) Verificación de identidad (anti-MITM)**: como el PeerID *es* la clave
>    pública Ed25519 (el secreto ECDH se deriva de ella), el intercambio no tiene MITM; el
>    riesgo es sustituir el PeerID en el canal de compartición. Krypta muestra un **número de
>    seguridad** estilo Signal (`SafetyNumber`, 60 dígitos, simétrico) para cotejarlo fuera de
>    banda; `Contact.verified` (Room v3) + insignia de escudo. Tests + verificado en móvil.
> ⏳ Pendiente: DCUtR upgrade directo (gate NAT, 2 SIMs CGNAT); ahorro de batería fino;
>    QR del número de seguridad (mejora UX, requiere CameraX).
> ✅ **(12 jul 2026) Rediseño de UI — fase UI-1/UI-2**: identidad Material 3 verde-teal
>    (claro/oscuro, icono propio, tipografía, back predictivo) + lista de conversaciones
>    (avatares, vista previa descifrada, hora, no leídos) + pantalla de Ajustes. Ver
>    docs/architecture.md (§ `:app`).
> ✅ **(12 jul 2026, tarde) UI-3 + 7d parcial**: chat pulido (separadores por día,
>    agrupación de burbujas, hora+checks en burbuja, hoja de adjuntos, nota de voz
>    mantener-y-soltar) — verificado en el TECNO; 7d: **sensor de proximidad** (wake lock
>    verificado ACQ/REL), **FGS `specialUse|microphone` en llamada** y **cambio de cámara**
>    (`switchCamera`, botón 🔄) — la verificación física de los tres va en la próxima
>    llamada real de dos móviles (PRUEBAS-PENDIENTES §9.9-10 y §11.7). El `phoneCall` real
>    queda con la integración Telecom.
> ✅ **(12 jul 2026, tarde) UI-4 + endurecimiento de release**: pantalla de llamada
>    rediseñada (botones redondos con etiqueta, avatar grande, controles que se auto-ocultan
>    en vídeo, PiP arrastrable) — verificada en voz en el TECNO (modo vídeo en
>    PRUEBAS-PENDIENTES §11.8); y **R8 activo en release** (reglas keep para el puente
>    gomobile; APK arm64 ~44 MB vs ~74 MB debug; smoke test en dispositivo: nodo + WAN +
>    cifrado + envío OK minificados; firma debug hasta tener keystore de publicación).
> ✅ **(12 jul 2026, noche) Respaldo de identidad**: export/import de identidad + contactos
>    en archivo `.krbk` cifrado por frase-clave (PBKDF2 310k + AES-256-GCM; los secretos
>    ECDH se re-derivan al importar, no viajan). UI en Ajustes ("Copia de seguridad", SAF);
>    la identidad importada rige al reiniciar (diálogo "Cerrar Krypta"). `IdentityBackupTest`
>    + ciclo completo verificado en el TECNO (mismo PeerID, contactos intactos).
> ✅ **(12 jul 2026, noche) Multi-nodo, lado cliente**: el bootstrap es una lista (uno por
>    línea, validada todo-o-nada). Bridge Go `0.0.17-multinode`: DHT y reserva de relay en
>    todos los nodos, depósito de buzón con failover al primero vivo, retirada de todos los
>    alcanzables (convergencia ante split-brain) y un stream de wake por nodo. Go
>    `TestMailboxMultiNode` + tests JVM; regresión de un solo nodo verificada en el TECNO.
> 🗺 **Siguiente**: **desplegar el 2.º nodo de infra** (otro host; al existir, añadir su
>    multiaddr a `Libp2pNode.DEFAULT_BOOTSTRAP` y probar el failover en vivo) → resto de
>    **7d** (adaptación de bitrate, orientación/espejo, Telecom/`phoneCall`).
> 📐 Tamaño del AAR (~34 MB/ABI): para release, ABI splits / App Bundle. Decisión abierta:
>    versionar el `.aar` en git vs. regenerarlo con `build-aar.sh`.

### Fase 1 — Infraestructura (Go, sin cambios de lenguaje)
- Desplegar nodo go-libp2p server (`infra/node`): bootstrap + DHT server + Relay v2.
- **Mailbox** store-and-forward: API para depositar/retirar blobs E2EE por clave de
  destinatario, con TTL, cuotas y rate-limit. El nodo no puede descifrar.
- **wake-server** (`infra/wake-server`): registro de tokens, push silencioso, rate-limit
  anti-drenaje. Compatible con el distribuidor UnifiedPush propio.
- `infra/deploy`: provisión de ~5 nodos fijos (IP estática).

### Fase 2 — Capa nativa en el móvil (`:native-bridge`)
- `libp2p/`: nodo go-libp2p en **modo client** (Noise, DHT client, relay client, DCUtR,
  AutoNAT), empaquetado como AAR con ABI splits.
- `Libp2pNode.kt`: wrapper Kotlin sobre el AAR — start/stop, dial, abrir/leer streams,
  eventos expuestos como `Flow`/callbacks → coroutines.
- `KryptaForegroundService.kt`: mantiene el nodo pre-calentado y el WebSocket al
  wake-server; flujo de exención de batería + reconexión (mitigación OEM-kill).

### Fase 3 — Descubrimiento privado + señalización (`:p2p-signaling`)
- `RendezvousService`: `HKDF(shared_secret_pareja, YYYY-MM-DD)` → clave de provider/topic.
  Anunciarse y buscar contactos por su rendezvous derivado (rotación diaria + ventana de
  solape al cambiar de día). Usar `javax.crypto`/Tink o BouncyCastle para HKDF.

> ✅ **(8 sep 2026) Ventana de solape implementada** —era lo único que faltaba de esta fase—
> junto con la corrección del anuncio: `Node.Advertise` usaba `dutil.Advertise`, que deja una
> goroutine reanunciando **para siempre**, así que las claves de días pasados nunca dejaban de
> publicarse y la rotación diaria no acotaba nada (auditoría A-1). Ahora el anuncio es de una
> sola pasada —quien reanuncia es el bucle WAN— y `rendezvousWindow` usa las dos claves
> contiguas 2 h a cada lado de la medianoche UTC, para que el arreglo no abra un agujero de
> descubrimiento en el cambio de fecha.
- `SignalingService`: intercambio de SDP/ICE para llamadas; texto/archivos directos por
  stream. Integra con repositorios Room de `:data`.

### Fase 4 — Buzón + integración de mensajería (`:data`)
- Online: entrega directa por stream. Offline: depositar blob E2EE en Mailbox.
- Estados en Room: `PENDING → SENT (en buzón) → DELIVERED (retirado) → READ`.
- Reintento/reconciliación con **WorkManager** al recuperar conexión (alineado con §2.5 de
  la spec).

> ✅ **(8 sep 2026) Reconciliación implementada, sin WorkManager**: el paso `reintentos` del
> ciclo WAN (`ChatService.retryFailed`) reenvía hasta 10 mensajes FALLIDOS por ciclo reusando
> su ciphertext ya persistido. WorkManager sobraba: el bucle WAN ya se despierta al cambiar de
> red (`kickWan`) y por el latido de AlarmManager, que es más fino que las restricciones de
> WorkManager para este caso. Hasta ahora un FAILED se quedaba así para siempre salvo que el
> usuario tocara la burbuja (auditoría A-7).

### Fase 5 — Wake end-to-end
- A envía con B offline → Mailbox notifica al wake-server → push silencioso (UnifiedPush)
  despierta a B → FG service levanta libp2p → B retira del buzón y descifra → `DELIVERED`.
- Endurecer ventana de despertar (pre-warm, batching, heartbeats).

### Fase 6 — Endurecimiento
- Anti-spam/abuso en relay, mailbox y wake (auth anónima + rate-limit).
- Análisis de metadatos del wake-server y mitigaciones (constant traffic/batching).
- Actualizar `docs/architecture.md` y `docs/security-model.md` reconciliando spec +
  cardumen + update + este plan. Marcar mDNS como descubrimiento *opcional en LAN*.

> 🟡 **(8 sep 2026) Mayormente hecha.** Tras la auditoría del 7 sep:
> - **Anti-abuso**: reparto justo del buzón por remitente con desalojo del acaparador
>   (lo importante: un desconocido ya no puede dejarte sin entrega), límites finitos en el
>   relay y tope de suscripciones de wake. Ver `infra/node/mailbox.go` y `wake.go`.
>   **Requiere redesplegar los nodos para estar en producción.**
> - **Análisis de metadatos**: escrito por fin en [security-model.md](security-model.md) §6 —
>   el nodo ve `from`, `to` y hora de cada depósito, y la presencia por el stream de wake. La
>   mitigación de fondo (**depósito ciego**, etiqueta derivada del secreto compartido en vez
>   de PeerIDs en claro) queda como el siguiente trabajo de privacidad.
> - **`docs/security-model.md`**: creado. Era el entregable más antiguo pendiente del plan.
> - Falta: rate-limit **temporal** (hoy solo de ocupación), ACL, y monitorización de nodos.

> ✅ **(9-10 sep 2026) Cerrado el hueco criptográfico que quedaba.** Tres trabajos seguidos, con
> su propio documento cada uno:
> - **Depósito ciego en el buzón** ([DISENO-buzon-ciego.md](DISENO-buzon-ciego.md)): el cliente
>   ya recibe por etiqueta y los tres nodos lo sirven; el **envío** sigue tras interruptor
>   (`BLIND_DEPOSIT`) esperando a que la versión que sabe recibir esté repartida.
> - **Cifrado en reposo**: base entera con SQLCipher y adjuntos con `FileVault` — con esto se
>   cierra el A-4 de la auditoría.
> - **Secreto hacia adelante** ([DISENO-ratchet.md](DISENO-ratchet.md)): doble ratchet **por
>   épocas** (variante propia: sin servidor de prekeys no hay forma de fijar quién inicia sin
>   que las raíces se bifurquen si los dos escriben a la vez), historial guardado en claro
>   dentro de la base cifrada porque una clave de un solo uso no puede reabrir lo guardado,
>   anuncio de capacidad por contacto, clave de llamada negociada. **Envío encendido el 10 sep**,
>   pero por pareja y **sin prueba en dos móviles** (`PRUEBAS-PENDIENTES` §16), que es lo que
>   falta para poder contarlo como garantía de cara al usuario.

### Fase 7 — Llamadas de voz/vídeo (diseño 4 jul 2026, pendiente de decisión)

**Contexto que condiciona el diseño**: el nodo infra está detrás de Cloudflare Tunnel
(solo HTTP/WebSocket, **sin UDP**), así que no podemos hospedar un TURN clásico; y ya
tenemos un camino que atraviesa NAT: la conexión libp2p (DCUtR directo cuando se puede,
Circuit Relay v2 vía wss/443 cuando no).

**Opción A (recomendada): audio sobre un stream libp2p** `/krypta/call/1.0.0`.
- **Señalización**: sobres E2EE nuevos tipo `C` por el camino de mensajes existente
  (directo→buzón): `C:invite <callId>` (suena), `C:accept`, `C:reject`, `C:hangup`.
  Un invite que llega por buzón ya caducado (> ~45 s) se pinta como **llamada perdida**.
- **Media**: `AudioRecord` con `VOICE_COMMUNICATION` (AEC/NS **hardware** de Android)
  → Opus por `MediaCodec` (fallback AAC-LD si el chip no codifica Opus) → frames de
  ~20 ms cifrados (clave por llamada = `HKDF(sharedSecret, callId)`, AES-GCM, contador
  como nonce/orden) → stream libp2p → jitter buffer (~80 ms) → `AudioTrack`.
- **Por qué encaja**: cero dependencias nuevas pesadas, cero infra nueva, reusa DCUtR
  (baja latencia cuando hay directo) y el relay wss como fallback universal (latencia
  mayor pero funciona tras cualquier NAT — justo donde un WebRTC sin TURN fallaría).
- **UI/plataforma**: `CallActivity` + notificación **CallStyle** (full-screen intent),
  FGS con tipo `microphone`/`phoneCall` mientras dura la llamada, altavoz/auricular,
  sensor de proximidad. Integración Telecom (`ConnectionService`) = v2.
- **Vídeo (7c)**: mismo esquema con Camera2 → `MediaCodec` H.264 → stream (otro canal
  lógico), render en `SurfaceView`. Solo tras validar el audio.

**Opción B (plan B): WebRTC** (`stream-webrtc-android`, libwebrtc FOSS mantenida) con
señalización SDP/ICE por sobres libp2p. Calidad superior (FEC, bitrate adaptativo, PLC),
pero: +~12 MB por ABI, y **sin TURN** (Cloudflare no da UDP) solo funcionaría donde el
STUN hole-punching funcione — cobertura *peor* que la Opción A salvo que se alquile un
VPS para coturn (infra nueva, en contra del espíritu del proyecto).

**Plan de ejecución (spike-first, como Fase 0)**:
1. **7a Spike (gate go/no-go)**: medir RTT/jitter y probar si el TECNO codifica Opus
   por MediaCodec. Criterio: latencia one-way relayed < ~300 ms sostenida.
2. **7b Audio MVP**: sobres `C` + stream + cifrado por llamada + CallActivity mínima.
3. **7c Vídeo**; **7d pulido** (tonos, BT, Telecom). Si 7a falla → Opción B + decidir
   dónde vive el TURN.

> ✅ **(4 jul 2026) Gate 7a SUPERADO (en WiFi)** — decisión: **Opción A, adelante con 7b**.
> Sonda: `PingProbe` en el puente Go (ping estándar `/ipfs/ping/1.0.0` contra el nodo,
> un ping cada 20 ms ≈ cadencia de frames de audio; RTT móvil→CF→nodo→CF→móvil ≈ latencia
> one-way de un frame relayed A→CF→nodo→CF→B). Expuesta como botón **"📞 Latencia"** junto a
> "Probar aviso" (códecs + RTT al Diagnóstico). Resultados:
> - Mac→nodo vía Cloudflare: `n=50/50 min=143 p50=151 p95=159 max=167 ms`.
> - **TECNO (WiFi)→nodo**: `n=50/50 min=139 p50=146 p95=163 max=175 ms` (y una segunda
>   corrida p50=163/p95=173) — **muy por debajo del gate de 300 ms, sin pérdidas**.
> - **Códecs del TECNO**: `Opus enc: sí · AAC enc: sí` (MediaCodec) → Opus disponible.
> - **LTE pendiente**: la SIM del autor estaba sin plan de datos (red Tigo en CAPTIVE_PORTAL,
>   resets de la operadora); repetir la sonda con datos activos antes de dar por cerrado 7a
>   en celular. Tests: `TestPingProbeLocal` (in-process) y `TestPingAgainstLiveNode`
>   (on-demand, `PING_ADDR=…`).

> ✅ **(4 jul 2026) 7b Audio MVP IMPLEMENTADO** (falta la prueba en vivo 2 móviles):
> - **Go**: `CallStream` (`/krypta/call/1.0.0`, framing uint16, duplex, relayed-friendly con
>   `WithAllowLimitedConn`) + `SetCallHandler`/`OpenCallStream`; test `TestCallStreamEcho`.
> - **Señalización**: sobre `C` (`invite/accept/reject/hangup/busy` + ts anti-rancios) por el
>   camino de mensajes (directo→buzón). `ChatService.sendCallSignal`/`callSignals`/
>   `recordMissedCall` (fila local "📞 Llamada perdida").
> - **`CallService`** (`:p2p-signaling`): máquina de estados IDLE→CALLING/RINGING→CONNECTING→
>   ACTIVE→ENDED; clave por llamada `HKDF(sharedSecret, callId)`; el que llama abre el stream
>   tras el accept y manda un hello cifrado que el receptor valida; timeouts (45 s ring, 20 s
>   connect); ocupado si ya hay llamada; colgado remoto al cerrar el stream. Cubierto por
>   `CallServiceTest` (2 extremos en memoria: llamada completa con audio E2EE bidireccional,
>   rechazo, invite rancio→perdida, colgar-mientras-timbra→perdida, busy).
> - **Audio** (`:app`, `MediaCodecAudioEngine`): AudioRecord VOICE_COMMUNICATION (AEC/NS) →
>   MediaCodec **Opus 48k/24kbps** (o **AMR-WB 16k** si el chip no codifica Opus; el frame `H`
>   anuncia el códec por sentido, sin negociación) → 20 ms/paquete → AudioTrack de voz con
>   ~120 ms de colchón. Mute (envía silencio) y altavoz.
> - **UI**: botón 📞 en el chat (pide micro), `CallScreen` a pantalla completa (aceptar/
>   rechazar/colgar/mute/altavoz/cronómetro), timbre en bucle + notificación (canal
>   `krypta_calls_v1`, sin sonido propio) desde el FGS, que además instancia `CallService`
>   temprano para recibir invites con la UI cerrada.
> - Smoke en el TECNO: 📞 → "Llamando…" → Colgar → "cancelada" → vuelve al chat. **Siguiente**:
>   llamada real entre 2 móviles (PRUEBAS-PENDIENTES §9) — ambos con el APK de hoy (un invite a
>   un APK viejo se pinta como texto crudo). Luego 7c vídeo / 7d pulido (tonos, BT, Telecom,
>   FGS `phoneCall` para llamadas con pantalla apagada).

> ✅ **(6 jul 2026) 7b VERIFICADO EN VIVO 2 móviles**: llamada clara, sin eco, sin cortes
> (tras arreglar el límite por defecto del relay v2: `WithInfiniteLimits` en el nodo — los
> 128 KiB/2 min por conexión relayada cortaban la llamada a los ~20 s), y la notificación de
> llamada entrante llega con la app en 2.º plano/pantalla apagada. Gate 7a también superado
> en celular: p50=180 ms / p95=220 ms / 0 pérdidas.

> ✅ **(6 jul 2026) 7c VÍDEO IMPLEMENTADO** — y **VERIFICADO en vivo 2 móviles el 16 jul
> 2026** (funcionó bien con los ajustes del 6 jul, en red mixta WiFi ↔ datos móviles;
> PRUEBAS-PENDIENTES §11):
> **vídeo como toggle dentro de la llamada de voz** — la llamada arranca
> como audio y cualquiera de los dos enciende su cámara con 🎥 (canales independientes por
> sentido; si el vídeo falla, la voz sigue).
> - **Go**: `VideoStream` (`/krypta/video/1.0.0`, framing **uint32** — un keyframe H.264 no
>   cabe en el uint16 del audio — tope 1 MiB, `WithAllowLimitedConn`); `TestVideoStreamEcho`
>   (incluye frame de 200 KiB).
> - **`CallService`**: `startVideo()` (solo en ACTIVE; abre el stream, manda `VHELLO:<callId>`
>   cifrado con la clave de la llamada — hello distinto del de audio para que no sean
>   intercambiables), `sendVideoFrame` (TX con DROP_OLDEST: un frame perdido se recompone en
>   el siguiente keyframe), `stopVideo()`, y validación del stream entrante (solo el de la
>   llamada activa, hello E2EE). `CallState.videoSending/videoReceiving` para la UI;
>   `remoteVideoFrames` para el decoder. Cubierto por `CallServiceTest` (vídeo E2EE
>   bidireccional con keyframe >64 KiB, encendido/apagado independiente, rechazo fuera de
>   ACTIVE, limpieza al colgar).
> - **`MediaCodecVideoEngine`** (`:app`): cámara frontal Camera2 → MediaCodec **H.264
>   640×480 ~500 kbps, 15 fps, keyframe/2 s** (superficie de entrada) → frames tipados
>   (`R` rotación del sensor, `C` SPS/PPS, `F` frame); a la inversa, decoder H.264 →
>   `Surface` del UI (frames pre-config retenidos hasta tener superficie+SPS/PPS; un fallo
>   del decoder lo re-crea en el siguiente config/keyframe).
> - **UI**: botón **🎥 Vídeo** en la llamada activa (pide CAMERA la 1.ª vez; el permiso ya
>   estaba en el manifest por el QR); remoto a pantalla completa (TextureView girada según la
>   rotación anunciada) + PiP propio; la cámara arranca cuando la Surface del PiP está lista.
> - Bitrate 500 kbps ≈ 62 KB/s: pasa por el relay wss/Cloudflare (sin límites tras el fix);
>   DCUtR directo cuando se pueda. Sin adaptación de bitrate ni cambio de cámara (7d).
>
> **(6 jul tarde) Ajustes tras la 1.ª videollamada real** (pixelado/congelones y la voz
> también se congelaba; y al apagarse la pantalla se caía la llamada): vídeo a **320×240 /
> 12 fps / 250 kbps, keyframe 1 s**; descarte de congestión **por grupos** en
> `CallService.sendVideoFrame` (frames tipados `VideoFrame` en `:core`; pasado el umbral de
> ~12 en vuelo se tira todo hasta el próximo keyframe → congela limpio y se recompone, y la
> cola corta no ahoga al audio que comparte el túnel wss); y `keepScreenOn` durante toda la
> llamada (pantalla apagada → el OEM suspende la red → mueren los streams). **El retest
> del 16 jul pasó: la videollamada funcionó bien con estos ajustes.** Si en el futuro se
> entrecortara: bajar a 8 fps/180 kbps; la adaptación dinámica de bitrate es 7d.

### Fase 8 — Gestión de datos locales: eliminar contactos y chats (decidido 17 jul 2026)

> ✅ **(17 jul 2026) Implementada y verificada en vivo** (TECNO, contacto desechable:
> long-press → vaciar → eliminar; los contactos reales intactos). `ChatService.
> clearConversation` (borra mensajes de Room + pide a `FileStore.deleteLocal` el borrado
> de staging/ensamblado/copia propia de cada archivo) y `ChatService.deleteContact`
> (vacía y borra el contacto; `announceAndFind` relee Room cada ciclo, así que el
> rendezvous cesa solo). UI: pulsación larga en la lista → diálogo de acciones, y menú ⋮
> en el chat; ambas con confirmación destructiva. `DiskFileStore.deleteLocal` se niega a
> borrar fuera de `krypta_files/` (el path viene de un descriptor persistido). Cubierto
> por `ChatServiceTest` (vaciar conserva el contacto; eliminar borra ambos) y
> `DiskFileStoreTest` (borra ensamblado/staging/copia propia; nunca fuera del almacén).

Hoy no existe forma de quitar nada desde la app (ni contactos ni mensajes; solo
desinstalar borra lo local). Alcance acordado — **todo local, sin cambio de protocolo ni
del nodo**:

- **Eliminar un contacto**: borrar de Room el contacto **y su conversación** (mensajes
  incluidos), con diálogo de confirmación. UI: opción en el chat o long-press en la lista.
  Ojo al estado en memoria: `ChatService.wanLoop` mantiene el rendezvous por contacto —
  el contacto eliminado debe salir del ciclo sin reiniciar el host.
- **Vaciar/eliminar un chat completo**: `DELETE` de los mensajes de la conversación
  (el contacto se conserva). También borrar los ficheros locales asociados
  (`krypta_files/`, copias de adjuntos/notas de voz) para no dejar huérfanos.

**Excluido deliberadamente (17 jul 2026)**: borrar un mensaje concreto (tanto "para mí"
como "para todos" estilo WhatsApp) — el "para todos" exigiría un nuevo tipo de envelope y
política de ventana temporal; se descarta por ahora.

## Verificación

- **Spike (Fase 0)**: ejecutar la app de prueba en 2 teléfonos con datos móviles de
  operadores distintos; registrar tasa DCUtR vs relay, latencia y estabilidad en
  background. Es el gate que valida toda la arquitectura.
- **Unit (JUnit + `kotlinx-coroutines-test`, en `:p2p-signaling`)**: `RendezvousService`
  rota por día y no es enumerable sin el secreto; lógica de estados del buzón; expiración
  por TTL. Tests de Room con `@Dao` in-memory.
- **Infra (Go)**: tests de integración del Mailbox (depósito/retiro/TTL/rate-limit) y del
  wake-server (token silencioso, rate-limit anti-drenaje).
- **Instrumentado (androidTest)**: arranque/parada del FG service y del `Libp2pNode`;
  reconexión tras kill.
- **E2E**: A envía mensaje con **B con la app cerrada** → push despierta a B → B retira del
  buzón → estado `DELIVERED`. Repetir forzando fallback a relay (NAT simétrico) y verificar
  una llamada de audio WebRTC señalizada por libp2p.
- **Batería/OEM**: prueba prolongada en al menos un dispositivo con OEM agresivo (p. ej.
  Xiaomi) verificando reconexión tras kill del FG service.
