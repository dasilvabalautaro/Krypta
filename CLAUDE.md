# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Krypta is

Krypta is a **WAN, decentralized, E2EE P2P messenger for Android** (Kotlin + Jetpack
Compose). It replaces the spec's mDNS LAN-only discovery with an internet-wide,
server-less signaling layer: per-pair daily **rendezvous** (`HKDF(shared_secret, date)`),
go-libp2p transport (DHT + Circuit Relay v2 + DCUtR), an E2EE store-and-forward mailbox,
and a UnifiedPush-compatible wake server. The full design and phased roadmap live in
[docs/PLAN-senalizacion-descentralizada.md](docs/PLAN-senalizacion-descentralizada.md);
the current-state architecture is in [docs/architecture.md](docs/architecture.md).

**Status:** multi-module skeleton wired end-to-end (builds, DI works, runs on device).
**Phase 0 done:** go-libp2p is compiled to `native-bridge/libs/krypta-p2p.aar` via
gomobile; a real libp2p host (Ed25519, TCP+QUIC) starts on-device, and **rendezvous
discovery over a Kademlia DHT works** — verified both by a deterministic in-process Go test
and live on-device (the phone discovers a self-hosted `infra/node` over `adb reverse`).
**Messaging over a libp2p stream works too** (protocol `/krypta/msg/1.0.0`): the phone
dials the node and delivers a message, verified live. **Payloads are E2EE**
(`MessageCipher` = AES-256-GCM, key via HKDF from the contact's shared secret): verified by
unit tests and live (the phone sends ciphertext; the relay node logs only opaque bytes).
The **domain loop is closed**: `ChatService` encrypts a **`MessageEnvelope`** (carries the
sender's message id, inside the E2EE) → persists (Room, PENDING→SENT) → sends, and incoming
streams are resolved by PeerID, the envelope decoded: a **text** persists (DELIVERED) with the
sender's id, a **read receipt** (sent by `markConversationRead` when a chat opens) marks the
cited outgoing messages **READ**. `decrypt()` unwraps the envelope (falls back to legacy raw
text). A **FAILED** message is tappable to **retry** (`ChatService.retry`, reuses the stored
ciphertext / same id). `decode` tolerating non-enveloped bytes keeps pre-envelope messages
readable. **Images (v1, inline)**: `sendImage` sends an `I`-type envelope; the client
compresses the photo (`ImageCodec`: ≤1280 px + JPEG ≤58 KiB to fit the mailbox blob limit)
and it travels the same direct→mailbox→wake→notification path; `content()` returns
`MessageContent.Text`/`Image`, the chat renders an image bubble, and notifications show
"📷 Foto". Full-resolution (chunking) is v2. (`Contact` carries `peerId` + `sharedSecret`;
`SignalingService.send` delivers to `contact.peerId`). **Files (v1, chunked)**: `sendFile`
splits the file into 48 KiB chunks under the mailbox limit, sends an `F` (meta) + `K` (chunk)
envelopes via `sendRaw` (encrypted, direct→mailbox, no Message), and shows one file bubble;
the receiver's `FileStore` (`DiskFileStore`) rebuilds it and persists a Message. Cap 8 MB
(mailbox quota limits offline to ~5 MB); large files = later. The v1 fragility (chunks in
memory + mailbox envelopes deleted on ack → a chunk lost mid-transfer silently killed the
file; a 4-chunk .bin was lost live on 4 Jul, and 1 of 3 voice notes on 5 Jul) **was fixed
5 Jul with the v2 reliable path**: (a) `DiskFileStore` now **stages every chunk + meta on
disk** (`krypta_files/staging/<fileId>/`, atomic tmp+rename writes, survives process death,
idempotent on redelivery; `DiskFileStoreTest`); (b) the mailbox is **ack-after-persist** —
`MailboxHandler.OnMailboxMessage` (Go) now returns a bool, and only envelopes the Kotlin
side confirms persisted get ack'd/deleted, the rest are redelivered next fetch (Go
`TestMailboxRedeliverUnacked`, plus panic-recover so a Kotlin exception = no ack); (c) the
mailbox path is **synchronous end-to-end**: `ISignalingService.setMailboxProcessor` →
`Libp2pNode.mailboxProcessor` (runBlocking on the Go thread) → `ChatService.onReceived`,
returning true only if persistence didn't throw — it no longer flows through the event
SharedFlow, whose 64-slot `tryEmit` **silently dropped envelopes under a chunk burst**
(likely the actual voice-note loss); `Libp2pNode`'s remaining event stream is now an
unbounded Channel for the same reason. Covered by `ChatServiceTest` (`mailbox processor
acks after persist…`, `file chunk that fails to stage is redelivered…`) and verified live
(self-send → mailbox deposit → wake fetch → "buzón: 1 mensaje(s) recogido(s)"). **Voice notes
(v1)**: reuse the chunked-file path with zero protocol change — `AudioRecorder` (MediaRecorder,
AAC mono 48 kbps in MP4) records into `filesDir/krypta_files/sent/`; `ChatViewModel.sendVoiceNote`
sends via `ChatService.sendFile(..., localPath=…)` (new param: the sender keeps its copy so its own
bubble is playable); any `audio/*` file with a local copy renders as a play/pause+progress bubble
(`AudioNote`, per-bubble MediaPlayer); the mic button replaces "Enviar" when the draft is empty
(runtime RECORD_AUDIO permission on first use) and notifications show "🎤 Nota de voz". The mic
button deliberately has **no TooltipBox** and uses `combinedClickable`: users press-and-hold
(WhatsApp habit) and the tooltip swallowed the long-press without ever recording — a whole
"can't send voice notes" bug hunt (5–6 Jul) ended there; both tap and long-press now start
recording. Recording failures toast instead of failing silently, and the chat screen toasts
`ChatViewModel.error`. The **Compose UI was redesigned to Material 3 (12 Jul 2026)** with an
own **green-teal brand theme** (full M3 light/dark schemes in `ui/theme/Color.kt`, seed
`#006A60`; dynamic color is opt-in). **Light/dark is user-selectable (16 Jul 2026)**: a
`ThemePreference` singleton (pref in `krypta_settings`, same pattern as `AppLock`) holds a
`ThemeMode` (SYSTEM/LIGHT/DARK, **default SYSTEM**); `MainActivity` combines it with
`isSystemInDarkTheme()` via the pure `ThemePreference.resolveDark(mode, systemDark)` and passes
`darkTheme` to `KryptaTheme` — so SYSTEM still tracks the OS live, and a fixed choice switches
**hot** (StateFlow recomposition, no restart). Picked from an "Apariencia" 3-way
`SegmentedButton` card in Settings; covered by `ThemePreferenceTest` (resolve + prefs
round-trip/fallback), hot-switch verified live on the TECNO. **Two contrast fixes shipped with
it (16 Jul 2026)**: (a) **system-bar icons** (clock/battery/network/notifications) are set from
the app's *resolved* `darkTheme` via a `SideEffect` in `KryptaTheme`
(`WindowInsetsController.isAppearanceLight{Status,Navigation}Bars = !darkTheme`) — `enableEdgeToEdge()`
alone colored them from the *system* mode, so forcing "Claro" on a dark phone left white icons on
a light bar (invisible); now they follow the choice and update on hot switch; (b) the top bars +
chat input strip moved from `surfaceContainer` to **`surfaceContainerHigh`** — one tonal step up
so the bar reads as distinct from the near-black (dark) / near-white (light) background instead of
blending in. Both verified live on the TECNO (dark, and forced light while the phone was dark). Also: custom launcher icon
(bubble+padlock on teal), typography,
no-flash window background (`values{,-night}/themes.xml`) and predictive back. Three screens,
state-based nav in `KryptaApp` (`ui/ChatScreens.kt`): a **conversations list**
(`ui/ConversationsScreen.kt`: per-contact avatar whose **color _and_ shape** derive from
the PeerID — the shape is picked from a curated set of rounded geometric `Shape`s
(circle, squircle, hexagon, pentagon, octagon) via `ui/theme/AvatarShape.kt`
(`RegularPolygonShape` = rounded regular polygon drawn with `Outline.Generic`, no new
dep; `avatarShapeFor(peerId)` uses a hash decorrelated from the color's) — all shapes are
**normalized to equal visual area** (`equalAreaScale`: a square inscribed in the same circle
covers only ~64% of it, so without this the squircle looked smaller than the pentagon; the
target area is the pentagon's, the tightest-fitting shape, so nothing overflows the box) and
the **initial is always the same size** (font scales with the fixed avatar box, not the
shape) and is a **single letter** (`name.take(1)`; the contact name is a free user-set alias,
often one word/nickname, so two-letter initials were considered and deliberately not adopted),
so color+shape
together are a stable visual identity fingerprint and the list isn't all circles — added
16 Jul 2026; the online dot is inset to (0.70, 0.84) of the box so it sits on the body of
any shape, not the empty corner of a hexagon/pentagon; `ContactAvatar` is shared by the
chat top bar and `CallScreen`, so each contact keeps its shape everywhere,
**decrypted last-message preview** with status icon (clock/✓/✓✓, teal ✓✓ = read), relative
time, **unread badge**, verified shield, "Nuevo contacto" FAB, WAN status subtitle), a
**Settings screen** (`ui/SettingsScreen.kt`: PeerID copy/share, bootstrap field, WAN status,
🔔 test-notification / ⚙ system settings / 📞 latency probe, diagnostics panel — all the
technical controls moved off the main screen), and the chat (top bar with avatar + "en línea",
asymmetric-tail bubbles; add-contact stays **name + PeerID only**) — verified on device, light
and dark. **In-app help is done (16 Jul 2026)**: a **Help screen** (`ui/HelpScreen.kt`) shows a
curated, task-oriented FAQ (`ui/HelpContent.kt` — plain testable data, not the full manual;
grouped by category, tap-to-expand cards) reachable from a ? action in both the conversations
and Settings top bars (`showHelp` branch in `KryptaApp`, ordered before `showSettings` so it
overlays and "back" returns to wherever it was opened). Plus **contextual help**: `SettingsCard`
takes an optional `onInfo` that renders an ⓘ opening a short dialog — wired on "Tu identidad"
(what a PeerID is) and "Recepción en segundo plano" (why OEM battery settings matter). Content
is **bundled/offline** (privacy stance; no external link to a hosted manual yet). Icons
`KryptaHelpIcon`/`KryptaInfoIcon`/`KryptaExpandMoreIcon` in `KryptaIcons.kt`; the FAQ data is
covered by `HelpContentTest` (non-blank/unique/concise/category-coverage); screens verified
live on the TECNO. Data side: `MessageRepository` gained `observeLastMessages()`/`observeUnreadCounts()`
(SQL over the existing table, **no schema change**) and `markIncomingRead(conversationId)` —
incoming messages are flipped to local READ when their chat opens (READ on incoming = "I saw
it"; on outgoing it still means "the peer read it"), which is what clears the unread badge;
`ChatService.markConversationRead` does this before sending the network receipt. **UI-3
(chat polish, same day)**: day separators ("Hoy"/"Ayer"/full date), grouping of consecutive
same-side bubbles (tight spacing, corner only opens on the group's first), time + status
checks inside every bubble, an **attachments bottom sheet** (single clip button → Foto /
Archivo), and **hold-to-record voice notes**: hold the mic → record, release → send (<1 s
discards as accidental; haptic on start), short tap still gives the pinned recording bar
with Cancelar/Enviar. Gesture gotcha found live: the mic's Box must **stay in composition
while recording** — swapping the input row for a recording bar cancels its `pointerInput`
and the release ("send") never fires; the input row now swaps its left side only. **Key exchange is X25519 ECDH from the libp2p identity**: each device has a
*persistent* Ed25519 identity (stored in SharedPreferences); the shared secret with a
contact is derived from your private key + the public key embedded in their PeerID
(`KeyExchange` / `Bridge.sharedSecretFor`), so onboarding needs only the PeerID — no
passphrase. The app **auto-starts the libp2p host on launch** and enables **mDNS LAN
discovery** (`ChatService.start()` → host + `startMdns`, with a `MulticastLock`); connected
peers show as "● en línea". **mDNS is a LAN-testing shortcut only** — Krypta targets **WAN**,
where the real discovery is **DHT + rendezvous**. mDNS startup is **best-effort** (wrapped in
`runCatching`): on cellular (no multicast interface) it fails, and that must not abort the host
or the WAN loop — verified that a clean install over LTE auto-connects to the DHT. **WAN is now wired end-to-end**: the app
joins the DHT via a bootstrap node and runs a per-contact rendezvous loop
(`advertise`/`findPeers` of `HKDF(sharedSecret, día)`). The bootstrap **defaults to the Krypta
infra node** (`Libp2pNode.DEFAULT_BOOTSTRAP`, the shared public `…/wss/…` multiaddr) so the app
joins WAN on first launch with **no user input**; the "Nodo WAN (bootstrap)" field pre-fills it
and stays editable as an override (a saved empty string = LAN-only; only an *absent* pref falls
to the default). The
**infra node** (`infra/node`, bootstrap + DHT + **Circuit Relay v2**) is built for the user's
**macOS Catalina** host — pinned to **go-libp2p v0.38 + Go 1.22** (v0.48 needs Go ≥1.25 →
macOS ≥11; v0.38 yields `minos 10.13`), interoperating with the v0.48 phones. Binary +
no-Docker deploy guide (launchd) in [infra/node/README.md](infra/node/README.md).
**The Linux/VPS primary node is DEPLOYED (7 Aug 2026)**: **Vultr São Paulo**,
`216.128.169.83`, Ubuntu 24.04, shared-CPU 2 GB, PeerID
`12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5` — installed with the one-command
`deploy-vps.sh` (cross-compiled `dist/krypta-node-linux-{amd64,arm64}` + a hardened
`krypta-node.service`: systemd, `Restart=always`, `LimitNOFILE=65535`; idempotent, keeps
`node.key` so the PeerID survives redeploys). It is now the **first line** of
`Libp2pNode.DEFAULT_BOOTSTRAP` (primary), reached by **direct `/ip4/…/tcp/4001` — no
Cloudflare**, with the Mac and Windows home nodes demoted to **backup**: the bridge puts to
the first live node and fetches/listens on *all* of them, so any single node dying (the VPS
included) doesn't stop delivery. Validated live from the dev Mac before promotion: mailbox,
wake, a full round-trip (`TestMailboxRoundTripAgainstLiveNode`, added the same day: A puts →
node persists → B fetches, payload byte-exact **and** `from` == A's real PeerID, which
exercises the node's non-spoofable-sender property against production) and latency
**p50 = 107 ms / p95 = 119 ms** from La Paz vs. 146–163 ms through Cloudflare. Provider note:
**DigitalOcean has no South American region at all** (NYC, SFO, Toronto, Atlanta, Richmond,
Kansas City, Amsterdam, London, Frankfurt, Singapore, Bangalore, Sydney), and for a
voice/video relay the region outranks the brand — hence Vultr. Open items on that box:
`net.core.rmem_max` is low (quic-go logs "failed to sufficiently increase receive buffer
size" at startup — harmless, may cap QUIC throughput under load), `node.key` still needs an
off-box copy, and the relay needs finite caps before going public. Both home nodes were
single points of failure for the mailbox/wake/relay of every user, which is why the VPS was
the intended primary — see [docs/PLAY-STORE.md](docs/PLAY-STORE.md).
A public IP also unlocks: real QUIC (better DCUtR, less relay traffic) and no Cloudflare
WebSocket recycling. That needs a **stable** QUIC port, so the node gained a `-quicport` flag
(default `0` = the previous ephemeral behavior, correct behind Cloudflare; the systemd unit
passes `4001`). **The host
has no public IP — it's exposed via Cloudflare Tunnel**, which only carries HTTP/WebSocket, not
raw TCP/UDP/QUIC. So the WAN path is **`wss` over 443**: the node also listens on
`/ip4/0.0.0.0/tcp/8081/ws` (flag `-wsport`); cloudflared maps `krypta.neto.chat → localhost:8081`;
phones use `/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>` (no app rebuild — libp2p ws
transport is built in). Because Cloudflare Free recycles WebSockets (~100s idle, ~10min total),
`ChatService.wanLoop` is **self-healing**: each cycle it re-runs `connectDht` (made idempotent in
Go: DHT created once, only re-connects the bootstrap) + rendezvous. The cycle interval is
**adaptive** for battery: 30s when the wake stream is down (aggressive mailbox-poll/rediscover,
under the ~100s Cloudflare idle cut), 180s when the wake stream is up (`WakeOnline()` in Go →
`signaling.wakeConnected()`; delivery is push-instant so polling relaxes). Note: direct DCUtR P2P
bypasses Cloudflare entirely (no timeout there). The app has a **diagnostics panel**
(`wanStatus` + `log`) to debug. Verified on one device (phone v0.48 ↔ Catalina node v0.38 over
`adb reverse` → "WAN (DHT): conectado"). **The wss-via-Cloudflare path is now verified live**:
`wscat -c wss://krypta.neto.chat` returns `101` + the libp2p `/multistream/1.0.0` greeting, i.e.
the full chain `wss/443 → Cloudflare → cloudflared → node tcp/8081/ws` reaches a real libp2p
node. The node runs under **launchd** (`KeepAlive`) on Catalina via
[infra/node/deploy-catalina.sh](infra/node/deploy-catalina.sh) +
[infra/node/chat.neto.krypta.node.plist](infra/node/chat.neto.krypta.node.plist) (one-command
deploy that copies the fresh `-wsport` binary, loads the LaunchAgent, and verifies `:8081`/ws).
**WAN messaging over Circuit Relay v2 now works end-to-end between two phones behind NAT**
(verified live: message goes `SENT` over the relay through Cloudflare; also covered by an
in-process Go test `TestRelayMessagingLocal`). Getting there required four fixes, all subtle:
(1) **node** must `ForceReachabilityPublic()` — behind Cloudflare Tunnel it has no public IP, so
AutoNAT thinks it's private and the relay service refuses to offer the `hop` protocol; (2)
**phone** advertises its own `/p2p-circuit` address via a custom `AddrsFactory` (AutoRelay won't,
because the relay can't vouch public addrs from behind Cloudflare); (3) **phone** makes an
explicit `ReserveRelay` each 30s (`wanLoop`) to keep the relay slot; (4) **`SendMessage`** opens
the stream with `network.WithAllowLimitedConn` — relayed conns are "limited"/transient and
go-libp2p otherwise refuses to open a stream on them (→ "context deadline exceeded"); and a
fifth found live 5 Jul: the node's relay must run
`EnableRelayService(relayv2.WithInfiniteLimits())` — go-libp2p's default caps every relayed
conn at **128 KiB or 2 min**, which killed the first live voice call at ~20 s (~5–6 KB/s of
encrypted Opus exhausts 128 KiB; messages/files are short bursts and never noticed). DCUtR
(`EnableHolePunching`) then tries to upgrade the relayed conn to direct. **Offline delivery
via an E2EE store-and-forward mailbox works**: when the direct send fails, `ChatService.send`
falls back to depositing the ciphertext in the infra node's mailbox (`/krypta/mbx/put/1.0.0`)
→ `SENT`; the recipient's `wanLoop` fetches its mailbox every cycle (`/krypta/mbx/get/1.0.0`;
since 5 Jul the client only acks envelopes **after** they persist — see the reliable-path
note above — and redelivery dedups client-side by using the envelope id as the Room
`Message.id`). The node authenticates both ops with the libp2p stream identity
(GET only returns your envelopes; the sender in the envelope is set by the node, not
spoofable), stores only opaque blobs (files under `-mailboxdir`, default `<key dir>/mailbox`),
and enforces quotas (blob ≤ 64 KiB, ≤ 200 msgs / 5 MiB per recipient, TTL 7 days). Covered by
Go tests on both sides (`infra/node`: `TestMailboxStoreAndForward`/`TestMailboxQuotaAndTTL`;
bridge: `TestMailboxPutFetch`) and `ChatServiceTest`, and **verified live two-phone**
(recipient's app closed → sender gets `SENT` via mailbox → message arrives on open); the
deployed node is probeable on demand with `TestMailboxFetchAgainstLiveNode` (does the node
answer the protocol?) and `TestMailboxRoundTripAgainstLiveNode` (does a message actually make
it there and back intact?), both `MBX_ADDR=…`.
Sends that fail both paths are marked `FAILED` (never crash). **Wake is integrated in the
node** (design change vs. the original UnifiedPush wake-server plan, agreed 2 Jul 2026):
since the mailbox lives in the node, a deposit triggers an instant notice over a lightweight
`/krypta/wake/1.0.0` stream the phone keeps open (node sends `{"ping"}` keepalives every 50s
to beat Cloudflare's ~100s idle cut; the Go bridge auto-reconnects every ~10min recycle and
fires an extra fetch on each (re)connect so no notice is lost). On Android a **real
Foreground Service** (`KryptaForegroundService`, now in `:app` — moved from `:native-bridge`
because it injects `ChatService`) holds the node + wake alive with the UI closed
(`START_STICKY`) and posts a notification per incoming message (decrypted
title/text, suppressed while the UI is visible via `ProcessLifecycleOwner`;
`POST_NOTIFICATIONS` runtime permission requested in `MainActivity`). Notification UX is
centralized in `KryptaNotifications`: a high-importance **messages** channel (heads-up +
sound + vibration + badge) and a low **service** channel with `showBadge=false` so the
ongoing FGS notification never inflates the icon count; each message notification carries a
**deep-link** (`EXTRA_OPEN_CONTACT` → MainActivity `singleTop`/`onNewIntent` → Compose
navigates straight to that chat) and is **cancelled when the chat opens** so the unread
badge clears even when you enter via the launcher icon; chat auto-scroll lands on the newest
message (instant first load, animated after). Covered by Go tests
(`TestWakeOnDeposit` node-side, `TestWakeSubscribe` bridge-side) and `ChatServiceTest` (wake
event → immediate fetch; WAN on/off toggles the subscription), and **verified live
two-phone** (3 Jul 2026): a deposit triggers the recipient's fetch in ~3 s (probes
`TestWakeAgainstLiveNode`/`TestMailboxPutAgainstLiveNode`, on-demand via `WAKE_ADDR`/
`MBX_ADDR`+`MBX_TO`), and with the recipient's app closed the message **notification fires
in seconds**. Debugging note: TECNO's OEM log limiter mutes the app's logcat — use the
in-app Diagnóstico panel (readable via `uiautomator dump`), not logcat. **Background
hardening is done**: the FGS type is `specialUse` (not `dataSync` — Android 15 kills
dataSync FGS after 6h, fatal for a persistent messenger; specialUse also allows start from
`BOOT_COMPLETED`); `MainActivity` requests the Doze **battery-optimization exemption**
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, verified on-device in the deviceidle whitelist);
a `BootReceiver` re-arms the service after reboot; the service registers a
`registerDefaultNetworkCallback` that calls `ChatService.kickWan()` on WiFi↔cellular
changes to reconnect immediately (the WAN loop's 30s wait is now interruptible via a
CONFLATED `wanKick` channel); and it holds a **`WifiLock` (FULL_HIGH_PERF)** so aggressive
OEMs (Transsion/TECNO, Xiaomi) don't power down WiFi when the screen turns off on battery —
the classic cause of "message arrives but no notification until you open the app". Since OEM
autostart/app-freeze settings can't be toggled programmatically, the contacts screen has a
**"Ajustes de recepción en 2.º plano"** button (`ACTION_APPLICATION_DETAILS_SETTINGS`) to
guide the user there. The FGS suppresses per-message notifications only while the UI is
visible, tracked by a **main-thread `ProcessLifecycleOwner` observer** (a `@Volatile
uiVisible` flag — the old off-thread `currentState` read could misreport when the app was
"almost foreground" over USB). Some OEMs (Transsion/TECNO) keep the process **alive but
suspend its network** when the screen is off on battery, so the persistent wake stream can't
deliver until the app is reopened (message "arrives instantly on open", no notification). A
**`HeartbeatReceiver`** (AlarmManager `setAndAllowWhileIdle` every ~2 min, unthrottled thanks
to the battery exemption; scheduled by the FGS) is the delivery **safety net**: it wakes the
device with a brief network window and runs `ChatService.pollOnce()` (connectDht + mailbox
fetch → notification), so messages land in ≤~2 min even when the wake socket is asleep. **Identity verification (anti-MITM) is done**: since a PeerID
*is* the Ed25519 public key (the ECDH shared secret is derived from it), the key exchange
has no MITM — the only vector is PeerID substitution in the channel where it's shared. So
Krypta shows a Signal-style **safety number** (`SafetyNumber` = 60 decimal digits from
`SHA-256(domain ‖ sorted peerIdA ‖ peerIdB)`, symmetric so both sides see the same);
`Contact.verified` (Room, DB **v3**) is set from a "Verificar identidad" dialog after the
two users compare it out of band, and a shield badge marks verified contacts. The dialog
also offers **QR** (`QrCode` + `zxing-android-embedded`, FOSS/no-Google): each side shows a
QR of `krypta:verify:<own PeerID>` and scans the other's; the app compares the scanned
PeerID to the stored one → match sets `verified`, mismatch warns of substitution. Covered by
`SafetyNumberTest` + `QrCodeTest` (payload round-trip) + `ChatServiceTest` (symmetry,
persistence, re-add keeps verification); QR render + scanner-launch verified on-device (the
two-phone scan-match is in [docs/PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md)).
**Voice calls (7b MVP) are implemented** (Option A, decided 4 Jul 2026): audio frames over a
libp2p stream (`/krypta/call/1.0.0`, Go `CallStream` with uint16 framing +
`WithAllowLimitedConn` for relayed conns; `TestCallStreamEcho`), signaling via E2EE `C`
envelopes (invite/accept/reject/hangup/busy + ts; a stale invite → local "📞 Llamada perdida"
row) over the normal direct→mailbox path, `CallService` state machine (per-call key =
`HKDF(sharedSecret, callId)`; caller opens the stream after accept and sends an encrypted
hello the callee validates; ring/connect timeouts; busy; covered by `CallServiceTest` with two
in-memory endpoints incl. bidirectional E2EE audio), and `MediaCodecAudioEngine` in `:app`
(AudioRecord VOICE_COMMUNICATION → MediaCodec **Opus 48k** or **AMR-WB 16k** fallback, codec
announced in-band per direction (`H` frame, no negotiation) → AudioTrack voice stream,
~120 ms jitter cushion; mute sends silence; speakerphone toggle). UI: 📞 button in the chat
top bar (requests mic), full-screen `CallScreen` (accept/reject/hangup/mute/speaker/timer),
looping ringtone + `krypta_calls_v1` notification from the FGS (which also instantiates
CallService early so invites arrive with the UI closed). No WebRTC, no TURN (Cloudflare has
no UDP; DCUtR/relay already traverses NAT). The **7a latency gate PASSED on WiFi and on
mobile data**: `Node.PingProbe` ("📞 Latencia" diagnostics button, also logs MediaCodec
encoders) measured p50≈146–163 ms / p95≤173 ms on WiFi and p50=180 ms / p95=220 ms / 0 loss
on cellular (5 Jul); TECNO encodes Opus. **First live two-phone call (5 Jul): connected,
clear, no echo — but cut at ~20 s** by the relay's default data cap (fix = relay
`WithInfiniteLimits`, see above; node redeploy + retest pending, PRUEBAS-PENDIENTES §9).
The relay fix is deployed and **verified live (6 Jul): calls connect, no echo, no cut, and
the incoming-call notification fires with the app in background/screen off**.
**Video calls (7c) are implemented and verified live two-phone (16 Jul 2026: worked
well with the 6-Jul tuning, on a mixed network — one phone WiFi, the other cellular)**:
video is a **toggle inside the voice call** — either side hits 🎥
during an ACTIVE call; independent per-direction streams (`/krypta/video/1.0.0`, Go
`VideoStream` with **uint32 framing**, 1 MiB cap — H.264 keyframes don't fit the audio's
uint16), so a video failure never kills the voice. `CallService.startVideo()` opens the
stream and sends a distinct `VHELLO:<callId>` E2EE hello (same per-call key; incoming video
streams are validated against the active call); frames go through a DROP_OLDEST TX (loss
heals at the next keyframe); `CallState.videoSending/videoReceiving` + `remoteVideoFrames`
feed the app layer. `MediaCodecVideoEngine` (`:app`): front camera via Camera2 → H.264
encoder input surface → typed frames (`VideoFrame` in `:core`: `R` rotation, `C` SPS/PPS,
`K` keyframe, `F` delta); receive side decodes to the UI's TextureView `Surface`
(pre-config frames buffered; decoder re-created on failure). CallScreen: remote video
full-screen + local PiP; CAMERA runtime permission (already in the manifest for QR).
**Tuned after the first live test (6 Jul: pixelation/freezes and the VOICE froze too —
video saturated the shared relayed wss tunnel; and the screen timing out killed the
call)**: video is now **320×240 / 12 fps / 250 kbps with a 1 s keyframe interval**;
`sendVideoFrame` does **GOP-aware congestion dropping** (past ~12 in-flight frames it
discards everything until the next `K` — clean freeze that recovers, instead of corrupted
H.264, and a short queue keeps audio flowing); and CallScreen holds `keepScreenOn` for the
whole call (screen-off → OEM suspends the network → streams die). Covered by
`TestVideoStreamEcho` (Go, incl. 200 KiB frame) and `CallServiceTest` (E2EE bidirectional
video, on/off, congestion drop, cleanup on hangup). **7d partial (12 Jul 2026)**:
(a) **proximity sensor** — `CallScreen` holds a `PROXIMITY_SCREEN_OFF_WAKE_LOCK` during
voice calls (not with speaker or video; released with `WAIT_FOR_NO_PROXIMITY`), verified
via dumpsys ACQ/REL on device — the cheek can no longer hang up, and it coexists with
`keepScreenOn`; (b) **in-call FGS type** — `KryptaForegroundService.updateForegroundType`
re-declares `specialUse|microphone` while a call is CONNECTING/ACTIVE (manifest declares
both + `FOREGROUND_SERVICE_MICROPHONE`), so the mic survives screen-off and the process
gains priority; the real `phoneCall` type needs Telecom (ConnectionService) and is deferred
with that integration; (c) **camera flip** — `MediaCodecVideoEngine.switchCamera()` restarts
capture on the other lens (🔄 button in CallScreen while sending video); the fresh SPS/PPS
travels in-band and the receiver's decoder is re-created when a **different** CONFIG frame
arrives. **UI-4 (same day)**: `CallScreen` redesigned — voice layout with big avatar +
name + timer; **round icon controls** with labels (mute/speaker/video/flip-camera, red
hang-up, green/red accept-reject; icons added to `KryptaIcons`); on video, **tap toggles
the controls** (auto-hide after 4 s, `AnimatedVisibility`) and the self-preview PiP is
**draggable** (clamped to screen bounds). Verified on device for voice, and the live
two-phone video call passed (16 Jul). Still no bitrate adaptation / orientation-mirror
polish / Telecom (7d rest). **Identity backup is done (12 Jul 2026)**: Settings has a
"Copia de seguridad" card — export writes a `.krbk` file via SAF containing the Ed25519
identity + contacts (name, PeerID, verified; shared secrets are NOT stored — they re-derive
by ECDH on import), encrypted with a user passphrase (`IdentityBackup` in `:p2p-signaling`:
`"KRBK1" ‖ salt ‖ nonce ‖ AES-256-GCM(payload)`, key = PBKDF2-HMAC-SHA256 · 310k iters,
magic as AAD; covered by `IdentityBackupTest` incl. wrong-passphrase and tamper cases).
`BackupManager` orchestrates; `Libp2pNode.importIdentityBytes` validates via
`Bridge.peerIDForIdentity` and persists to the `krypta_identity` prefs — the in-memory
identity is a `lazy` and the host may be running, so **the import only takes effect on
process restart**: the UI shows the imported PeerID and a "Cerrar Krypta" dialog that
`exitProcess(0)`s (the sticky FGS revives the process with the new identity). Verified
live on the TECNO: export → SAF file (289 B) → import same file → restart → same PeerID,
contacts intact and previews decrypting. **Multi-node bootstrap is done (12 Jul 2026,
client side)**: the bootstrap pref/UI now takes a newline-separated **list** of node
multiaddrs (`normalizeBootstrapList` validates every line; all-or-nothing). The Go bridge
(AAR `0.0.17-multinode`) was already list-aware for DHT connect and per-relay
`/p2p-circuit` addresses; now also **`ReserveRelay` reserves on every relay**,
**`MailboxPut` fails over** to the first node that accepts, **`MailboxFetch` drains ALL
reachable nodes** (a deposit may land on any of them, so fetch-all makes split-brain
deliveries converge), and **`StartWake` keeps one wake stream per node** (`WakeOnline` =
any alive). Covered by Go `TestMailboxMultiNode` (failover put + fetch-all + all-down
errors) and `ChatServiceTest` (list normalization/persistence); single-node regression
verified on the TECNO. **`StartDHT` is partial-success too (fixed 17 Jul 2026)**: it used
to return `lastErr` if ANY bootstrap failed, so one downed node flipped the app to "sin
conexión" while messaging kept working through the other (seen live: krypta2 dials in
backoff → status ERROR, yet the mailbox fetched fine via the Mac node). Now ≥1 connected
bootstrap = success; error only when ALL fail (Go
`TestStartDHTPartialBootstrapFailure`); verified live on the TECNO. **The second infra node is DEPLOYED (16 Jul 2026)**: the author's
Windows PC on their LAN, running the cross-compiled
`infra/node/dist/krypta-node-windows-amd64.exe` (pure Go, no Go install on the PC), exposed
via Cloudflare as `krypta2.neto.chat` (runbook: "Segundo nodo en Windows" in
[infra/node/README.md](infra/node/README.md)). Verified from the dev Mac: full libp2p
connect over wss + live probes `TestMailboxFetchAgainstLiveNode` and
`TestWakeAgainstLiveNode` pass against it. Its PeerID
(`12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm`) was extracted without touching the
PC via a dial-with-wrong-PeerID probe (the Noise handshake error reports the real key —
trick worth remembering). **`Libp2pNode.DEFAULT_BOOTSTRAP` now carries all three nodes**
(newline-separated, in preference order — `MailboxPut` deposits in the first live one, so
line 1 *is* the primary: since 7 Aug that's the São Paulo VPS, with these two as backup);
note a phone that ever saved a bootstrap pref keeps it — the TECNO's
stale single-node pref was deleted via `run-as sed` so it falls to the new default. The
live two-phone failover test (kill Mac node → delivery via the Windows node's mailbox) is
in PRUEBAS-PENDIENTES. Still
`TODO`: DCUtR direct-upgrade verification on cellular (NAT gate, 2 SIMs). **Settings screen
polish (14 Jul 2026)**: three M3 fixes to `SettingsScreen.kt`. (a) Its cards were nearly
invisible against the scaffold background — both used tones one step apart in the same
`surfaceContainer*` ramp (`surfaceContainerLow` on `background`); cards now use
`surfaceContainerHigh` + a 1dp `outlineVariant` border + 1dp elevation, a real jump from
`background` in both themes. (b) Action buttons were a mix of icon-only
(`TooltipIconButton`), icon+text, and text-only `TextButton`s in the same screen; a new
`SettingsActionButton` (`FilledTonalButton`, icon+text, `KryptaIcons.kt` gained
`KryptaUploadIcon`/`KryptaDownloadIcon`/`KryptaBellIcon` for Export/Import/Test-notification)
replaces all of them. Two-button rows that fit share the row via `Modifier.weight(1f)`
(Copiar/Compartir, Exportar/Importar); "Probar aviso"/"Ajustes del sistema" don't both fit
that way without truncating, so that pair stacks as full-width buttons instead — text
truncation silently eating a label is worse than an extra row. (c) The PeerID used to sit
inline right after its description in the same text color family and was easy to misread
as part of it; it now renders in its own `surfaceContainerHighest` chip (monospace, full
width) so identity and description are visually distinct at a glance. Verified live on the
TECNO in both light and dark. **Chat screen polish (14 Jul 2026)**: four fixes to
`ChatScreens.kt`. (a) Auto-scroll only reacted to `rows.size` (send/receive), so opening the
keyboard — which shrinks the `LazyColumn` via `imePadding()` — left the last message hidden
behind it, with no re-scroll on open, while-open, or close. Added a second effect that
follows `WindowInsets.ime` frame-by-frame (`snapshotFlow { imeInsets.getBottom(density) }`,
`rememberUpdatedState(rows)` so it always scrolls to the *current* last row) and snaps the
list to bottom on every change — covers open, the animation while it stays open, and close,
not just the two endpoints. (b) Bubble contrast: text/icons were left on the ambient
`LocalContentColor` (inherited as `onBackground` from Scaffold), not the color actually
paired with each bubble's background; own-message bubbles compute a proper
`(bg, onBg)` pair applied via one `CompositionLocalProvider(LocalContentColor provides onBg)`,
so `FileAttachment`/`AudioNote` inherit it too without threading a color param through each.
Received bubbles moved from `surfaceContainerHigh` to `surfaceContainerHighest` + a 1dp
`outlineVariant` border (same fix pattern as the Settings cards). **Accessibility contrast bump
(16 Jul 2026)**: own bubbles now use **`primary`/`onPrimary`** (was `primaryContainer`) —
`primary` is dark in the light theme and bright in the dark theme, so it sits at the **opposite
luminance** from the neutral received bubble in *both* themes; with `primaryContainer` (bright
mint) vs `surfaceContainerHighest` (pale gray) the two were nearly the same luminance in light,
so a low-vision user couldn't tell who sent what. Failed stays `errorContainer`/`onErrorContainer`.
Because `MessageStatusIcon` hardcoded scheme colors (READ = `primary`), it would render teal ✓✓
on the now-teal own bubble = invisible; it gained optional `mutedTint`/`readTint`/`failedTint`
params (default to the scheme colors for the conversations list), and the in-bubble call passes
`onBg`-derived tints (read = opaque `onBg`, rest = `onBg`@60% so ✓✓-read still reads apart from
✓✓-delivered). Verified live on the TECNO in both themes. (c) "Simple rounded rectangle" turned out to mean:
consecutive same-side bubbles are the exact same flat color 2dp apart, so a grouped run
visually fused into one blob — confirmed live (screenshot showed two stacked audio-note
bubbles reading as a single shape). Fixed with a `1.5.dp` shadow per bubble (`clip = false`
so it isn't clipped away) — cheap and enough for each bubble in a group to read as a
distinct message. (d) The message `OutlinedTextField` was borderless except its focus
outline, so it visually merged with the bar around it; replaced with a `TextField`
(`surfaceContainerHighest` fill, transparent indicators, `shape = RoundedCornerShape(24.dp)`)
and the whole input row now sits on a `surfaceContainer` strip, bookending the screen with
the top bar's tone. The attachment `ModalBottomSheet` had the same bug as the pre-fix
Settings cards — default `surfaceContainerLow` container barely distinguishable from the
scaffold background (confirmed live) — fixed by passing `containerColor =
surfaceContainerHigh` explicitly. Verified live on the TECNO in both light and dark: bubbles
read as distinct messages, keyboard open/hold/close all keep the last message in view,
input pill and attachment sheet both stand out from their backgrounds.
**Conversations list date alignment fix (same day)**:
`ConversationRow`'s last-message date used a `Spacer(Modifier.weight(1f))` inside a `Row`
with no `fillMaxWidth()` to push it flush right — that only reliably fills the available
width when the row's own bounded-width plumbing lines up just right, and in practice it
didn't: dates landed at a different x per row depending on the contact name's length (live
screenshot showed "ayer" flush right but "6/7/26" ~70px short of it). Fixed with the
standard trailing-meta pattern instead: the name+shield sits in its own inner `Row` with
`Modifier.weight(1f)` (on the explicitly `fillMaxWidth()` outer Row), and the date `Text`
follows as the un-weighted last child — its right edge is now always the row's right edge,
by construction, regardless of name length. Bonus while in there: the contact name now goes
`FontWeight.Bold` when `unread > 0`, matching the existing bold-preview-text convention so
unread rows are bold end-to-end (name + preview), not just the preview. Verified live on the
TECNO: both dates align to the same margin now. **App lock is done (16 Jul 2026)**: optional
unlock-to-enter via the framework `BiometricPrompt` (API 30+, no new deps; `USE_BIOMETRIC`
in the manifest — its absence crashed the first on-device try) with `BIOMETRIC_WEAK |
DEVICE_CREDENTIAL`, so fingerprint/face/device PIN all work and **Krypta stores no unlock
secret**. `AppLock` (singleton in `:app`, pref in `krypta_settings`) tracks
enabled/grace/locked as StateFlows; re-lock decisions ride `ProcessLifecycleOwner` (a
rotation is not "leaving the app") with a configurable grace period (immediate/1 min/5 min,
M3 segmented buttons in a new Settings card); cold start = born locked. The gate lives in
`KryptaApp` **after** the CallScreen branch, so an incoming call is answerable without
unlocking (like the native phone app); `LockScreen` auto-fires the prompt on show and shows
no user content. Toggling the setting requires authenticating in **both** directions
(enabling proves unlock works; disabling can't be done by whoever grabs the phone), and
enable is preceded by a `canAuthenticate` check with a human toast ("configura un bloqueo
de pantalla primero"). Prompt/manager calls are wrapped in `runCatching` (OEM failure =
failed auth + retry button, never a crash). Pure relock policy split out as
`AppLock.shouldRelock` and covered by `AppLockTest`; verified live on the TECNO (switch →
system sheet with fingerprint + "Usar patrón"; pref forced on via `run-as` → cold start
lands on "Krypta está bloqueada" with the prompt auto-shown, cancel keeps the gate, button
re-launches it). The author's own fingerprint pass succeeded (16 Jul: enable-by-auth +
relock/unlock worked; they keep the lock off by personal preference — the feature is
optional and operative). **Local data deletion (Fase 8) is done (17 Jul 2026)**: **all
local, zero protocol change**. `ChatService.clearConversation` empties a chat (deletes
its Room messages and, per file bubble, calls `FileStore.deleteLocal(fileId, localPath)`
— pending staging + assembled dir + own copy e.g. a sent voice note; `DiskFileStore`
refuses to delete anything outside `krypta_files/`, since the path comes from a persisted
descriptor); `ChatService.deleteContact` clears the chat and removes the contact — no
wanLoop surgery needed because `announceAndFind` re-reads contacts from Room each cycle,
so the rendezvous stops by itself (re-adding by PeerID re-derives the same secret;
verification must be redone). UI: **long-press a conversation** → actions dialog, and a
**⋮ overflow menu in the chat top bar** (new `KryptaMoreIcon`/`KryptaDeleteIcon`), both
behind a destructive `ConfirmDeleteDialog`; deleting from the chat navigates back, and
the ViewModel cancels the contact's notification. "Delete a single message" was
deliberately excluded (17 Jul decision, see the plan). Covered by `ChatServiceTest`
(clear keeps the contact / delete removes both) + `DiskFileStoreTest` (`deleteLocal`
idempotent, never escapes the store), and verified live on the TECNO with a throwaway
contact (long-press → vaciar → ⋮ → eliminar; real contacts untouched).
**Adding yourself is rejected (23 Jul 2026)**: `ChatService.addContact` now `require`s
`peerId != localPeerId()`. Both PeerIDs (yours and the contact's) get copy-pasted through the
same channel, so pasting your own is easy — and since `Contact.id` **is** the PeerID, the
`upsert` silently landed on the self-send contact used for mailbox testing: it inherited that
chat and looked like a real contact while every message went to the phone itself. That's
exactly what killed the 23 Jul two-phone multi-node test (contact "Jimena" held the TECNO's
own PeerID; diagnosed by pulling `databases/krypta.db` with `run-as` and deriving the PeerID
from `krypta_identity.xml`). `ChatViewModel.addContact` surfaces the `IllegalArgumentException`
message verbatim and, when the PeerID already belonged to a differently-named contact, warns
about the rename instead of merging silently. Covered by `ChatServiceTest` and verified live.
Side effect: the one-phone self-send trick for testing the mailbox is gone (Go tests +
`ChatServiceTest` already cover that path).
**Notification reliability audit (13 Aug 2026)** — chasing "the message arrives but nothing
rings". Five real defects, all in the same family: **the alert depended on a collector that
may not exist**. (1) `ChatService._incoming` is a `SharedFlow(replay = 0)` emitted with
`tryEmit`, and its only subscriber was `KryptaForegroundService`. When an OEM kills the
process and **only the heartbeat alarm revives it**, the FGS isn't there — so the mailbox
envelope was fetched, persisted, **acked (deleted at the node)** and the alert silently
dropped, gone for good; you only saw it on next app open. Fixed with
`ChatService.setIncomingNotifier`, a **direct hook** invoked in place right after persisting
(same pattern as `setMailboxProcessor`), owned by a new `IncomingNotifier` (@Singleton in
`:app`) attached from `KryptaApplication.onCreate` — the Application exists in *every* process
start (Activity, service, or `BroadcastReceiver`). (2) Same defect for **calls**, worse:
`_callSignals`' only subscriber is `CallService`, which only the FGS instantiated, so an
`invite` arriving by mailbox in an alarm-revived process was dropped — no ring, no
notification, no missed-call row. `IncomingNotifier` injects `CallService` so the consumer
exists from process start. (3) `pollOnce()` (the heartbeat safety net) read `bootstrapAddr`,
which only `start()` sets — in an alarm-revived process it was **null**, so the net was a
**no-op in exactly its own scenario**; and even with an address, `Libp2pNode.startDht` is
`node?.startDHT(...)`, a silent no-op with no host. Now it calls `start()` first and falls
back to the persisted bootstrap; `HeartbeatReceiver` also relaunches the FGS. (4) Suppression
was `if (uiVisible) return` — having the app open on *any* screen killed every alert, so a
message from another contact never rang while you sat in the conversation list or another
chat. Now it only mutes the **conversation you're actually looking at**
(`IncomingNotifier.setVisibleConversation`, set by `ChatScreen` via `DisposableEffect`).
(5) Each new message **overwrote** the previous notification's text (same id, plain builder);
now `Notification.MessagingStyle` accumulates the last 6 per contact, with `setNumber`. Also:
**incoming-call notifications** gained `Notification.CallStyle` (API 31+) with answer/decline
actions wired to a new `CallActionReceiver`, and — the big one — **`setFullScreenIntent`**
(new `USE_FULL_SCREEN_INTENT` permission, auto-granted to calling apps; verified `granted=true`
on the TECNO): without it a call with the screen off/locked left only a discreet tray entry
instead of taking over the screen. The ringtone moved out of the FGS to `IncomingNotifier`
with explicit `USAGE_NOTIFICATION_RINGTONE` `AudioAttributes` (it could otherwise play on the
music stream) plus **looping vibration** (`VIBRATE`), so silent mode still alerts. **Clearing
(the second half of the ask)**: `ProcessLifecycleOwner.onStart` → `cancelAllMessages`, which
sweeps the whole messages channel — tray and icon count to zero on app open — while leaving
the FGS ongoing notification (cancelling it would kill the service) and a ringing call alone.
It runs in **two passes, children first**: past 4 notifications the system adds its own
`ranker_group` header, which it **recreates** if removed before its children (found live: an
empty header survived the first attempt; it's hidden by the shade, but it was still there).
Per-contact unread is untouched by design — it lives in Room (`observeUnreadCounts` = incoming
DELIVERED) and only opening the chat clears it (`markIncomingRead`), so the conversation list
keeps its badges exactly as before. Covered by `ChatServiceTest` (notifier fires with **no**
subscriber; a throwing notifier still acks; `pollOnce` starts the host and uses the saved
bootstrap) and a new instrumented `KryptaNotificationsTest` — instrumented on purpose because
`CallStyle` is rejected by the **system at `notify()` time**, never by the build. Verified live
on the TECNO: MessagingStyle notification renders and accumulates, app foreground clears the
whole tray while the service notification survives, 4 instrumented tests green on Android 15.
Still pending two phones: a real incoming message with the app killed and a real incoming call
with the screen locked (see PRUEBAS-PENDIENTES §12).
**Keyboard rich content — GIF / stickers / big emoji (13 Aug 2026)**: the IME's GIF and sticker
tabs answered "this app doesn't support inserting here", because a Compose text field only
advertises `text/*` in its `EditorInfo` unless something declares otherwise. Fixed with
**`Modifier.contentReceiver`** on the chat input, which flips the advertised types to `*/*`
(`TextFieldDecoratorModifierNode` picks `mediaTypesAll` when a receive-content config is
present) and hands over a `TransferableContent`; the handler `consume`s any clip item whose
resolved mime is `image/*` and routes it to the existing `sendImage` path, returning the rest
(plain text) to the field. Compose already calls `InputContentInfoCompat.requestPermission()`
before delivering, so the URI is readable — no extra permission plumbing. **This forced the
input off the legacy `TextField(value, onValueChange)` onto the state-based
`TextField(state: TextFieldState)`** (material3 1.4.0 has the overload): only the new
`BasicTextField` stack (`foundation.text.input.internal`) wires `commitContent`, the legacy
`CoreTextField` never sees it. `draft` is now `draftState.text.toString()` and clearing is
`clearText()`. Needs `@OptIn(ExperimentalFoundationApi::class)`. Second half of the fix, in
`ImageCodec`: stickers and big emoji are PNG/WebP **with alpha**, and JPEG has none — they
arrived with a **black** background. `compress` now picks the format from `bitmap.hasAlpha()`:
**`WEBP_LOSSY`** (alpha-capable, compresses at least as well, decoded by the same
`BitmapFactory` on the far side) for stickers, JPEG for photos — no protocol change, no new
dependency. Verified live on the TECNO: the GIF and sticker tabs open instead of refusing,
a Tenor GIF sends, and a transparent heart sticker renders **on the bubble's teal**, not on a
black box.
**Animated GIF (13 Aug 2026)**: shipped, **no protocol change and no new dependency**.
Transport — `ChatViewModel.sendImage` branches on the resolved mime: `image/gif` and
`image/webp` go **byte-for-byte through the chunked file path** (`sendFile`, 48 KiB chunks,
disk staging, mailbox redelivery), the only one that carries more than the inline image
envelope's ~58 KiB — a keyboard GIF is hundreds of KiB to a few MB. **Never re-encoded**:
running one through `ImageCodec` is exactly what flattened it to frame one. Cap
`MAX_ANIMATION_BYTES` = 4 MB, under the node's 5 MiB per-recipient mailbox quota so a GIF still
lands when the contact is offline. A **local copy** goes to `krypta_files/sent/` and rides as
`localPath` (the voice-note pattern) so the sender's own bubble animates too. Rendering — new
`ui/AnimatedImage.kt`: framework-only `ImageDecoder` + `AnimatedImageDrawable` (API 28+, minSdk
is 30; Coil was considered and rejected for one bubble). Compose can't draw a `Drawable`, so it
paints onto the native canvas and drives repaints with a `withFrameNanos` loop —
`AnimatedImageDrawable.draw()` advances by elapsed time, so redrawing is all that's needed and
no `Drawable.Callback`/scheduler is required. The `tick` is read **inside** the draw block, so
it invalidates draw only, not composition, and the loop dies with the composition (scrolling a
GIF off-screen stops it). `setTargetSampleSize` bounds decode to 720 px. Falls back to the
plain file bubble when the file is gone or won't decode. The chat bubble routes
`localPath != null && mime in ANIMATED_IMAGE_MIMES` here (static WebP works too — a non-animated
decode just draws once), and `notificationText` labels it **"🎞 GIF"** instead of
"📎 archivo.gif", which also fixes the conversation-list preview. Covered by `ChatServiceTest`
(GIF goes out chunked, is labelled 🎞 GIF, keeps its `localPath`, and the receiver reassembles
the bytes **identically**) and verified live on the TECNO: a Tenor GIF sent as "archivo enviado
… (2 trozos)", the bubble **animates** (two screenshots a second apart show different frames),
and the list preview reads "🎞 GIF".
**Screenshot/screen-recording block (13 Aug 2026; scoped to the chat screen 21 Aug 2026)**:
**`FLAG_SECURE`** is now set **per screen**, not app-wide — `SecureScreenEffect` in
`ui/ChatScreens.kt` calls `ScreenSecurity.setSecure(activity, true)` from a `DisposableEffect`
when the chat screen enters composition and `false` on dispose. It first lived in
`MainActivity.onCreate`, and with a single Activity that covered the **whole** app: the
conversation list, Settings, Help and the call screen couldn't be captured either — no support
screenshots, no Play-listing assets — while protecting nothing that matters, since the
sensitive content is the conversation. (`FLAG_SECURE` only ever affects *this* window; it never
blocked screenshots in other apps.) Compose dialogs and `ModalBottomSheet` live in their own
windows but **inherit** the parent's flag when they open (`DialogProperties.securePolicy`
defaults to `SecureFlagPolicy.Inherit`, and material3's ModalBottomSheet copies the parent
window's flag via its internal `isFlagSecureEnabled`) — so the chat's dialogs/sheets are
covered with no per-dialog wiring. While the flag is on: system screenshots refuse, screen
recorders capture black, the recents thumbnail is blank, and the window won't mirror to a
non-secure display. **Krypta itself can still capture**, which is the point:
`ScreenSecurity.captureToGallery` draws the decor view onto a **software** `Canvas` and saves a
PNG to `Pictures/Krypta` via MediaStore (`RELATIVE_PATH` + `IS_PENDING`, so no storage
permission on minSdk 30). It must be `view.draw(Canvas)` and **not `PixelCopy`** — PixelCopy
reads the surface through the compositor and would come back black under FLAG_SECURE, whereas
an app drawing its own view hierarchy never touches it. Exposed as **⋮ → "Capturar pantalla"**
in the chat top bar; the handler waits **two `withFrameNanos`** after closing the menu, or the
dropdown itself lands in the image. Verified live on the TECNO (13 Aug, when it was app-wide):
`adb shell screencap` of the app is **fully black** (only the system status/nav bars show),
while ⋮ → Capturar produced a correct full-UI PNG in `Pictures/Krypta`. **Consequence for this
repo's workflow**: `adb shell screencap` works everywhere **except an open chat**, where it
comes back black — there, use `uiautomator dump` (the accessibility tree is unaffected) or the
in-app capture. Two trade-offs to keep in mind: casting
/screen mirroring shows black, and a capture saved to the gallery is outside the E2EE boundary
(said as much in the in-app help).

## Module structure

```
:app            Compose UI + ViewModels. KryptaApplication(@HiltAndroidApp),
                MainActivity(@AndroidEntryPoint, singleTop for notif deep-links),
                KryptaForegroundService (keeps the node + wake alive with the UI closed),
                IncomingNotifier (owns every user-facing alert; attached from the
                Application so it survives a process revived by a receiver alone),
                KryptaNotifications (channels + MessagingStyle/CallStyle builders +
                cancel), CallActionReceiver (answer/decline from the notification).
                Wires all modules together.
:core           Pure domain: interfaces (ISignalingService, IDiscoveryService,
                MessageRepository, ContactRepository) + models (Message, Contact,
                MessageStatus). No Android components, no DI framework. Everything
                else depends on this.
:data           Room persistence: MessageEntity / MessageDao / KryptaDatabase /
                Converters, RoomMessageRepository, DataModule (Hilt).
:native-bridge  Kotlin/JNI wrapper over the go-libp2p AAR (Libp2pNode). The FG service
                lives in :app (it injects ChatService, which this module cannot see).
:p2p-signaling  RendezvousService (real HKDF-SHA256, RFC 5869) + SignalingService
                (implements core's ISignalingService) + SignalingModule (Hilt @Binds).
```

Dependency graph: `:app → :core, :data, :p2p-signaling, :native-bridge`;
`:p2p-signaling → :core, :native-bridge`; `:data → :core`; `:native-bridge → :core`.
Domain interfaces live in `:core` so implementations are swappable via Hilt.

## Commands

Use the Gradle wrapper (`./gradlew`).

- Build the app: `./gradlew :app:assembleDebug` (this is the one to use — it also covers
  all modules, since `:app` depends on them)
- ~~Build everything: `./gradlew assembleDebug`~~ — **broken**, do not use. The bare
  aggregate target asks every module to build its own standalone `bundleDebugAar`,
  including `:native-bridge`, which fails under AGP 9.2.1: `implementation`/`api` on
  `libs/krypta-p2p.aar` (a direct local `.aar` file) is no longer allowed for a module
  that produces its own AAR output (`Direct local .aar file dependencies are not
  supported when building an AAR`). This never affects `:app:assembleDebug`/
  `:app:installDebug` — those consume `:native-bridge` via `project(...)`, through its
  jar tasks, never through `bundleDebugAar`. Fixing the aggregate target would mean
  moving `native-bridge`'s AAR dependency off `files(...)` (e.g. a `flatDir` repo +
  Maven-coordinate notation), which ripples into `settings.gradle.kts` and
  `build-aar.sh` — left alone for now since nothing in the real build/deploy workflow
  needs it.
- Install on device/emulator: `./gradlew :app:installDebug`
- All JVM unit tests: `./gradlew testDebugUnitTest`
- One module's unit tests: `./gradlew :p2p-signaling:testDebugUnitTest`
- One test class/method: `./gradlew :p2p-signaling:testDebugUnitTest --tests "chat.neto.krypta.p2p.RendezvousServiceTest"`
- Instrumented tests (needs device): `./gradlew :app:connectedDebugAndroidTest`
  — ⚠️ **DESTRUCTIVE ON THE AUTHOR'S PHONE. Ask first.** It uninstalls the app afterwards,
  which wipes app data: the **Ed25519 identity** (so the PeerID changes and every contact's
  device now points at a dead one), the contacts, the message history and the attachments.
  `allowBackup="false"` means there is no system backup to fall back on — the only recovery is
  a `.krbk` export made beforehand. This bit for real on 13 Aug 2026: a notification-test run
  wiped the TECNO's identity and its two contacts. **Export a `.krbk` first, or run it on a
  spare device/emulator.** Target one class with
  `-Pandroid.testInstrumentationRunnerArguments.class=<FQCN>`; the uninstall happens either way.
- Lint: `./gradlew :app:lint`
- Clean: `./gradlew clean`

Go (native bridge / infra) — needs `export PATH="/usr/local/bin:$HOME/go/bin:$PATH"`:
- Discovery unit test: `cd native-bridge/libp2p && go test -run TestRendezvousDiscovery -v ./...`
- Run a local DHT node: `cd infra/node && go run . -listen /ip4/0.0.0.0/tcp/4101 -rendezvous <hex>`
  (`infra/node` is pinned to go-libp2p v0.38 + Go 1.22 so it can target macOS Catalina; the
  Catalina release build + deploy steps are in [infra/node/README.md](infra/node/README.md))
- Live on-device discovery: start the node, `adb reverse tcp:4101 tcp:4101`, then run
  `KryptaDiscoveryDeviceTest` with `-Pandroid.testInstrumentationRunnerArguments.{class,bootstrap,rendezvous}`
  (see the test's KDoc). The test self-skips when those args are absent.

ADB lives at `~/Library/Android/sdk/platform-tools/adb` (not on PATH). A physical device
(TECNO KM5s, Android 15) is typically connected over USB for verification. To launch after
install: `adb shell am start -n chat.neto.krypta/.MainActivity`.

## Native Go bridge (`:native-bridge`)

The libp2p transport is written in Go (`native-bridge/libp2p/`, package `bridge`) and
compiled to an AAR with gomobile. Kotlin calls it through generated classes
`chat.neto.krypta.bridge.{Bridge, Node}`, wrapped by `Libp2pNode`.

- **Toolchain:** Go 1.26.4 (Homebrew), gomobile/gobind in `~/go/bin` (not on PATH),
  NDK 26.1.10909125. `go`/`gomobile` are not on PATH — call them with explicit paths or
  `export PATH="/usr/local/bin:$HOME/go/bin:$PATH"`.
- **The AAR is NOT in git** (31 Jul 2026, when the repo was first versioned): at ~75 MB it
  would trip GitHub's 50 MB warning and add another 75 MB of permanent history on every
  regeneration, so `.gitignore` excludes `native-bridge/libs/*.aar` (and its sources jar).
  **A fresh clone must run [native-bridge/libp2p/build-aar.sh](native-bridge/libp2p/build-aar.sh)
  before the first `./gradlew :app:assembleDebug`** — without `libs/krypta-p2p.aar` the build
  fails at `:native-bridge`, which declares `api(files("libs/krypta-p2p.aar"))`. Same deal for
  `infra/node/dist/` and `infra/node/node` (Go binaries, rebuilt per infra/node/README.md).
- **Regenerate the AAR** after editing any `.go`: run
  [native-bridge/libp2p/build-aar.sh](native-bridge/libp2p/build-aar.sh). Gradle does
  *not* rebuild it — it consumes the `libs/krypta-p2p.aar` sitting on disk.
- **Mandatory linker flags:** the build script passes
  `-ldflags="-checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384"`. Both are required:
  (a) go-libp2p pulls `github.com/wlynxg/anet`, which `//go:linkname`s the unexported
  `net.zoneCache`; Go ≥ 1.23 rejects this and linking fails with
  "invalid reference to net.zoneCache" without `-checklinkname=0`. (b) **16 KB page size**
  (added 23 Jul 2026): Play requires it for `targetSdk` ≥ 35 since 1 Nov 2025, and NDK 26
  links segments at 4 KB (`0x1000`) by default — `libgojni.so` was non-compliant and the AAB
  would be rejected. With the flag all four ABIs link at `0x4000`. Verify after rebuilding:
  `llvm-readelf -l <so> | grep LOAD` (last column must be `0x4000`) and, on the APK,
  `zipalign -c -P 16 -v 4 app.apk`. NDK r27+ would default to 16 KB, but 26.1 is pinned here
  to match the AAR's build.
- **gomobile API constraints:** only export functions/structs using gomobile-friendly
  types (string, int→long, bool, []byte, structs-with-methods, error). No maps/slices of
  structs, no channels across the boundary; use callback interfaces for async events.
- **Size:** the AAR is ~75 MB (`libgojni.so` ~34 MB × 4 ABIs). The debug APK bundles all
  ABIs; for release, switch to ABI splits / App Bundle.

## Conventions and gotchas

- **Package:** `chat.neto.krypta` (renamed from the template's `chat.neto.myapplication`).
  Each module has its own namespace under it (`.core`, `.data`, `.nativebridge`, `.p2p`).
- **Bleeding-edge toolchain:** AGP 9.2.1, Kotlin 2.2.10, Gradle 9.4.1, Compose BOM
  `2026.02.01`, `compileSdk = 36.1`, `minSdk = 30`, `targetSdk = 36`, JDK 25. All versions
  are centralized in [gradle/libs.versions.toml](gradle/libs.versions.toml) — add/upgrade
  there, never inline. Pin DI/persistence: Hilt 2.59.2, Room 2.8.4, KSP `2.2.10-2.0.2`
  (must match the Kotlin version exactly), coroutines 1.11.0, WorkManager 2.11.2,
  lifecycle 2.9.4.
- **AGP 9 built-in Kotlin + KSP:** AGP 9 compiles Kotlin without the JetBrains Kotlin
  Gradle plugin (the app applies only `android.application` + `kotlin.compose`; libraries
  apply only `android.library`). KSP registers generated sources via `kotlin.sourceSets`,
  which built-in Kotlin forbids by default — so [gradle.properties](gradle.properties) sets
  `android.disallowKotlinSourceSets=false`. Don't remove it or any KSP build breaks.
- **`compileSdk` ceiling:** with `compileSdk 36.1`, a dependency that requires API 37
  (e.g. lifecycle 2.11.0) fails `checkDebugAarMetadata`. Keep new deps compatible with
  API 36, or bump `compileSdk` deliberately across all modules.
- **DI = Hilt, KSP not kapt.** Modules with Hilt/Room annotations apply both the
  `ksp` and (for Hilt) `hilt` plugins and use `ksp(...)` for the compilers. Put `@Module`
  bindings in a `di/` package. Components install in `SingletonComponent`.
- **Room migrations, not destructive.** `KryptaDatabase` is at **v4** with real migrations
  (`data/Migrations.kt`, wired in `DatabaseModule` via `addMigrations`); `exportSchema=true`
  writes `data/schemas/`. **Every schema change adds a `Migration` + bumps the version** —
  do not reintroduce `fallbackToDestructiveMigration` (it wipes user data). Destructive
  fallback is scoped to the ancient v1 only (`fallbackToDestructiveMigrationFrom(1)`). A
  migration's SQL must reproduce the entity schema exactly or Room throws at runtime.
- **Async = coroutines + Flow.** Services expose `Flow`/`SharedFlow`; repositories expose
  `Flow` + `suspend` functions. No RxJava, no callbacks-as-API.
- **Crypto must stay correct.** `RendezvousService` is real (HKDF-SHA256). The rendezvous
  is intentionally rotating-by-day and non-enumerable without the shared secret — preserve
  those properties (there are unit tests asserting them).
- **Compose-only UI:** no XML layouts/View system. Compose compiler via the
  `kotlin-compose` plugin (no `composeOptions` block). Theme in
  [app/src/main/java/chat/neto/krypta/ui/theme/](app/src/main/java/chat/neto/krypta/ui/theme/).
- **Release builds run R8** (12 Jul 2026): `optimization { enable = true }` (needs
  `android.r8.gradual.support=true` in gradle.properties — AGP 9 requirement) +
  [app/proguard-rules.pro](app/proguard-rules.pro), whose critical rules **keep `go.**` and
  `chat.neto.krypta.bridge.**`** — the gomobile JNI bridge resolves those classes by name
  at runtime and R8 renaming/pruning them breaks the libp2p node with no compile error.
  Release signs with the **production keystore** when `keystore.properties` (git-ignored,
  points at `~/keystores/krypta/krypta.jks`) is filled, and falls back to the debug keystore
  otherwise — `hasReleaseKeystore` in [app/build.gradle.kts](app/build.gradle.kts).
  `assembleRelease -PslimAbi` → ~44 MB arm64 APK (vs ~74 MB debug slim; the floor is
  `libgojni.so` ~34 MB); `:app:bundleRelease` → signed ~82 MB AAB for Play. Verified on
  device: node starts, WAN connects, decrypt/send work under minification.
- **No Google auto-backup** (23 Jul 2026): `android:allowBackup="false"`. The template left
  it `true` with the stock (all-commented) `backup_rules.xml` / `data_extraction_rules.xml`,
  so the **Ed25519 identity** (`krypta_identity.xml`), `krypta.db` and the attachments were
  being uploaded to the user's Drive — outside the E2EE and contradicting §7 of
  [docs/politica-privacidad.html](docs/politica-privacidad.html), which promises the only
  copy is the passphrase-encrypted `.krbk`. Both XMLs now exclude every domain as
  defense-in-depth in case the flag is ever flipped back. `allowBackup=false` also disables
  device-to-device transfer; migration is the `.krbk` export. Verified on device:
  `dumpsys package chat.neto.krypta` no longer lists `ALLOW_BACKUP`.

## Documentation policy

Keep docs current as the project evolves: when structure, conventions, or the toolchain
change, update this file and [docs/architecture.md](docs/architecture.md) in the same
change; when a roadmap decision changes, update
[docs/PLAN-senalizacion-descentralizada.md](docs/PLAN-senalizacion-descentralizada.md).

**Play Store readiness:** the launch checklist (what's done, what's a store-listing chore,
what's an infra risk) lives in [docs/PLAY-STORE.md](docs/PLAY-STORE.md) — update it as items
close.

**Live-test tracking:** features that are implemented + unit/probe-tested but not yet
confirmed with two real phones go in [docs/PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md)
(the collaborator's second phone isn't always available). Add the exact steps + expected
result there instead of blocking on the live test; move items to its "ya verificado" section
once confirmed. **Deploy routine:** keep the author's phone on the latest build
(`:app:installDebug`) and refresh `~/Desktop/krypta-arm64-debug.apk`
(`:app:assembleDebug -PslimAbi` + copy) after any code change, so the author can share it.
