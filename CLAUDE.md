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
Cloudflare**, with the Mac and Windows home nodes demoted to **backup** (and on 10 Sep 2026 replaced
altogether by an InterServer VPS in Dallas, see below): the bridge puts to
the first live node and fetches/listens on *all* of them, so any single node dying (the VPS
included) doesn't stop delivery. Validated live from the dev Mac before promotion: mailbox,
wake, a full round-trip (`TestMailboxRoundTripAgainstLiveNode`, added the same day: A puts →
node persists → B fetches, payload byte-exact **and** `from` == A's real PeerID, which
exercises the node's non-spoofable-sender property against production) and latency
**p50 = 107 ms / p95 = 119 ms** from La Paz vs. 146–163 ms through Cloudflare. Provider note:
**DigitalOcean has no South American region at all** (NYC, SFO, Toronto, Atlanta, Richmond,
Kansas City, Amsterdam, London, Frankfurt, Singapore, Bangalore, Sydney), and for a
voice/video relay the region outranks the brand — hence Vultr. Its early open items are
closed: `node.key` backed up off-box (8 Aug), finite relay caps (8 Sep), and — since 10 Sep —
`deploy-vps.sh` sets the QUIC `sysctl` (`rmem_max`/`wmem_max` = 7 500 000, so quic-go no longer
warns about the receive buffer) plus a 30-day journal retention. The box is **1 vCPU / 1 GB**,
not 2 GB as the docs used to say. A **backup VPS** to replace the two home nodes was bought
and **deployed 10 Sep**: InterServer **Dallas**, `163.245.192.235`, PeerID
`12D3KooWQf7ZM3kXxc76XEN3Aj8gxhYorSKViPEGF4zepQuMQVCM` (KVM, 1 vCPU / 1.9 GB, IPv4 only). Hardened
before deploying — SSH key-only via `sshd_config.d/00-krypta-hardening.conf` (the `00-` prefix
matters: first value wins and `50-cloud-init.conf` enables passwords; 14 brute-force attempts
had already arrived in its first 4 minutes) and `ufw` (22, 4001/tcp+udp, 443). Probes green, p50
136 ms TCP / 130 ms QUIC; `node.key` backed up to `~/keystores/krypta/krypta-node-dallas.key`.
**It replaced the two home nodes in `DEFAULT_BOOTSTRAP` the same day**, which is now São
Paulo + Dallas only (`check-nodes.sh` reads the constant, so it follows automatically). This only
reaches phones on a newer build, and a phone with a saved bootstrap pref keeps its own list — so
the Mac and Windows nodes must **stay up** (and be drained) until older clients are gone. The
two-phone failover test is now "stop São Paulo, deliver via Dallas" (PRUEBAS-PENDIENTES).
**Removing the home nodes also removed the only `wss/443` path** (they were the Cloudflare-tunnel
ones), stranding users on networks that only allow 443. Fixed the same day **without a tunnel**:
DNS A records in Cloudflare with the proxy **off** (gray cloud) — `krypta-sp.neto.chat`,
`krypta-dal.neto.chat` — and **Caddy** on both VPS in front of the local `ws` (installed by
`infra/node/deploy-caddy.sh`: official Caddy repo, cert via TLS-ALPN so port 80 stays closed, no
access log and a log filter deleting client IP/port/headers — verified the source IP is absent from
Caddy's journal and syslog after real wss traffic). Each node now appears **twice** in
`DEFAULT_BOOTSTRAP` (tcp/4001 lines first, then wss/443); that's safe because the bridge groups
lines by PeerID. **Gotcha that needed an AAR change**: go-libp2p's default dial ranker treats `wss`
as TCP and dials the **lowest port first**, so 443 beat 4001 and phones would have gone through
Caddy — where the node sees everyone as `127.0.0.1` (libp2p doesn't limit loopback per IP, and the
relay's 256-reservations-per-IP cap becomes shared). `native-bridge/libp2p/dial_ranker.go` adds
`libp2p.DialRanker(directFirstDialRanker)`: direct and WebSocket addrs ranked separately, WebSocket
1 s after the last direct dial (immediately if a peer has only WebSocket). Go `TestDialRanker*`
caught a wrong first version (it only delayed wss, leaving tcp 250 ms behind). Verified live on
the TECNO with the regenerated AAR: after a cold start it holds `tcp/4001` connections to both VPS
and none through Caddy (checked with `ss` on each box). **That was not always true (found 12 Sep
2026)**: twice (once with the app already running, once on a cold start; another cold start was
clean) the phone held a `tcp/4001` **and** a `wss/443` connection to the same VPS at once. The ranker only decides the *order*; a single libp2p dial never leaves two (the
worker cancels the dials still in flight — checked in-process with a slow-TCP proxy, which yields a
lone `ws` conn instead), so the pair comes from overlapping dial episodes (`StartDHT` returns on
the first node while the app's cycle already `Connect`s to the other for relay/mailbox/wake). The
exact trigger was not pinned down; the fix does not depend on it: `native-bridge/libp2p/conn_prune.go`
installs a `Notifiee` that **closes any WebSocket conn to a peer that also has a direct one**
(relayed `/p2p-circuit` conns excluded on both sides of the rule) — **unless it is busy**: a conn to
a node carries the relay circuits to contacts (`/libp2p/circuit/relay/` hop/stop streams) and,
inside them, calls; closing it would cut a call in progress, so a conn with relay/call/video
streams is re-checked every 30 s (max 20 times) and closed once free. `StartDHT` now groups bootstrap
lines by PeerID (it built one `AddrInfo` per line, so the wss-only request reached the ranker with
no direct addr to wait behind), and the relay diagnostics line shows the conns per node
(`1 conn: tcp` / `2 conns: tcp+ws`) plus `wss redundantes cerradas: N` when the pruner acted. Go
`TestWebsocketRedundanteSeCierra` (two hosts with the **same identity** entering via ws and tcp —
the deterministic way to give one swarm two conns from one peer), `TestPodaEsperaAQueLaWebsocketQuedeLibre`
(a ws conn carrying a `/krypta/call/` stream survives until the call hangs up),
`TestPodaNoTocaLoQueNoSobra`, `TestStartDHTUnaConexionPorNodoConDosVias`; Kotlin `RelayStateTest`
pins that the diagnostics line keeps the per-node conn summary but not the reservation expiry
(it is logged only on change). Verified on the TECNO with the regenerated AAR: 6 cold starts, all
with only `tcp/4001` to both VPS (sampled with `ss` every 0.2–0.5 s), and the diagnostics reading
`relay: OK (alcanzable por circuit; 1 conn: tcp | 1 conn: tcp)`. Honest limit: the pruner never had
to act in those runs (no `wss redundantes cerradas` line), so on-device this confirms the end
state, not the pruner itself — that is covered by the Go tests. The wss path still
weakens per-IP limits for anyone who deliberately uses it — accepted for a fallback, noted in
`security-model.md` §8. Located by RTT, not geo-IP (1.3 ms to Vultr's Dallas ping host vs 38.7 ms to NJ) —
which also showed Nyx's "Secaucus" node is in fact in Dallas, the same datacenter. Details in
[infra/node/README.md](infra/node/README.md) "Nodo de respaldo". Both home nodes were
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
**Copy a message + tappable links (2 Sep 2026)**: chat bubbles had **no way to copy text** —
a plain `Text`, no `SelectionContainer`, no long-press, and no link detection, so received text
was a dead end (a code, an address, a URL). This was never a deliberate security decision:
nothing in the docs recorded it (and this repo *does* record its deliberate omissions), and the
clipboard was already used in Settings for the PeerID. Now a **long-press on a text bubble**
opens a small round **icon-only copy button** in a `Popup`, anchored to the side the bubble sits
on (`combinedClickable`; the tap branch still does the FAILED-retry, in **one** modifier —
chaining two gesture modifiers means the second never sees the event). It started as a
`DropdownMenu` with a "Copiar" item and the author rejected it as "un grito, muy grande": a
single-action menu still pays M3's 112dp minimum menu width plus padding, so it landed as a
placard over the message (224×96 px measured). The button is 40dp visually, and M3 still gives
it the 48dp (80 px) touch target. With the label gone the icon's `contentDescription` is the
only thing left for TalkBack, so it must stay. Copy goes through `ui/MessageText.kt`'s `copyMessageText`, which uses the **platform**
`ClipboardManager` rather than Compose's `LocalClipboardManager` because only the former can set
`ClipDescription.EXTRA_IS_SENSITIVE` (API 33+, guarded) — without it Android 13+ paints the copied
message in the clipboard preview, which is exactly what `FLAG_SECURE` prevents on that screen.
Long-press is only wired when `message.text` is non-blank (image/file/audio/GIF bubbles carry an
empty `text`). Links come from `linkifyText` using `android.util.Patterns.WEB_URL` (no new
dependency) into an `AnnotatedString` with `LinkAnnotation.Url`; a URL without a scheme gets
`https://` prepended. The link colour is passed in, since the readable colour differs between the
own bubble (`primary`) and the received one. The pattern is an injectable parameter **only** so
the offset arithmetic is testable on the JVM (`Patterns` is Android-only and `:app` has no
Robolectric) — `MessageTextTest` pins that the visible text survives linkifying unchanged.
Verified live on the TECNO: long-press shows "Copiar", and pasting into the input field returns
the text.
**Reply to a specific message (3 Sep 2026)**: chat had no way to answer a message other than
the last one. Shipped with **no Room migration and no copy of the quoted text on the wire**.
Transport: a new envelope type `Y` that is a **wrapper**, not a content type —
`"Y\n<replyToId>\n" ++ <the normal envelope>` — so replying works for text, photo, file and
voice note without one new type per content (`MessageEnvelope.wrapReply`/`encodeReply`; a
reply inside a reply decodes to null, so the recursion has a floor). Only the **id** travels:
both ends already store every message under the same id (the one in the envelope), so each
side resolves the quote against its own Room — which also means a quote can't resurrect
content the peer already deleted (a deliberate difference from WhatsApp/Signal, which embed a
copy; the cost is a "Mensaje no disponible" quote when the original is gone, and it heals by
itself when the original merely hasn't arrived yet, since the quote resolves at paint time
over the conversation Flow). `send`/`sendImage`/`sendFile` take an optional `replyTo`;
`onReceived` unwraps before branching; `ChatService.decodeMessage` returns the new
`DecodedMessage(content, replyTo)` (`content()` delegates to it) so the UI decrypts once per
message. For **chunked files** the quote rides in the **meta**, not the chunks
(`IncomingFileMeta.replyTo` → 5th line of the on-disk staging meta, tolerant of a 4-line meta
from an older build → `AssembledFile.replyTo` → local descriptor): the receiver's bubble isn't
born until every chunk is in, and the process can die in between. `decode` also gained
`Unsupported` for a well-formed envelope of an unknown type (a newer client), so its raw
header is never painted as if it were legacy text. UI (`ChatScreens.kt`): **swipe the bubble
right** (`detectHorizontalDragGestures` on the row's Box — deliberately *not* chained onto the
bubble's `combinedClickable`, which still carries tap+long-press together — 56 dp threshold,
haptic on crossing, animated snap-back) **or long-press** → the popup that used to hold only
"Copiar" is now a pill with **Responder** (every bubble, including photo/audio/file) +
**Copiar** (text only); a quote bar sits above the input with an ✕, and **back** dismisses the
reply instead of leaving the chat; the quote renders inside the bubble (`QuotedPreview`) and
**tapping it jumps to the original**, which flashes (`animateColorAsState` blended toward
`tertiary`). Every send path carries it: text, photo, keyboard GIF/sticker, file and voice
note. Covered by `MessageEnvelopeTest` (wrapper round-trip over T/I/F, nesting rejected,
unknown type), `ChatServiceTest` (id travels end to end and the quoted **text** does not; a
voice note keeps its quote through reassembly), `DiskFileStoreTest` (quote survives the
staging across "process death"; a 4-line meta still assembles) and verified live on the TECNO
(long-press and swipe both open the reply bar, the sent reply renders its quote, ✕ and back
dismiss it).
**Two finishing fixes the same day, both cases of "the detail is the product"**: (a) **the
action pill's colour must contrast with BOTH bubbles.** It was `surfaceContainerHighest` —
which *is* the received bubble's background, i.e. **1.0:1** over a received message, invisible.
No flat fill can fix it: in the dark theme the own bubble is bright teal (`#53DBC9`) and the
received one dark grey (`#303635`), and the best possible equidistant colour tops out at
**2.84:1** against both, under WCAG's 3:1 for non-text UI. So the contrast now comes in **two
layers** — `inverseSurface` fill plus a 1.5dp **ring** of `inverseOnSurface` (inverse tones of
each other): over the dark bubble the fill carries it (9.5:1) and over the bright one the ring
does (7.7:1); in the light theme the roles swap (10.7:1 / 5.7:1). Whatever is underneath, one
of the two layers separates the pill from it. (b) **the bubble's silhouette must not be
dictated by its content.** Bubbles had *no* width constraint, so a long text, a wide image or —
new with replies — the quote of a long message stretched them edge to edge and consecutive
messages came out with wildly different shapes. The row is now a `BoxWithConstraints` and the
bubble is capped at `BUBBLE_MAX_WIDTH_RATIO` (0.78) of the row width, so content wraps inside a
stable shape instead of defining it (and the opposite margin always stays visible, which is
half of what makes sender-side legible at a glance). The quote block inside also `fillMaxWidth`s
— it reads as a header of the message rather than a floating chip leaving a ragged interior —
and its coloured bar uses `height(IntrinsicSize.Min)` + `fillMaxHeight` so it spans the real
height of a two-line preview instead of a fixed 34dp. Verified live on the TECNO with a
throwaway contact (in-app ⋮ capture: long text wraps at the cap, the reply's quote spans the
bubble and its bar runs full height). Note for future UI verification: **the action pill cannot
be screenshotted at all** — it's a `Popup`, whose `PopupProperties.securePolicy` inherits the
chat's `FLAG_SECURE`, so `screencap` is black and the in-app capture only draws the decor view,
not other windows. Its colours were verified by computing the contrast ratios from the scheme
values in `ui/theme/Color.kt`.
**The bubble's silhouette now scales with it (6 Sep 2026)** — closing the item deferred on
3 Sep: with a long message the bubble **lost its shape**. The 18dp corners, the 1dp
`outlineVariant` border and the 1.5dp shadow read fine on a short bubble, but on a tall
multi-line one the same radius is a tiny fraction of the silhouette and the shadow vanishes at
that size, so the message stopped reading as a bubble and became a block of text with a hairline
around it. Fix: **what draws the silhouette now grows with it** — a private `BubbleShape`
(`Shape`) plus `bubbleBorderPx`/`bubbleShadowPx`, all three interpolating from the current
values at a one-line bubble (`BUBBLE_SHORT_HEIGHT` = 48dp, below which **nothing changes** —
short bubbles look exactly as before) up to a cap: corner 18→28dp, border 1→1.75dp, shadow
1.5→3dp. It scales with **height**, not area or width: a long single-line message is still
short and its 18dp corners read fine; the contour is only lost growing downwards. Everything is
computed **at draw time from the measured size**, which is the point of doing it as a `Shape`
(`createOutline` receives the size, so the right radius lands on the **first frame**), of moving
the shadow from `Modifier.shadow` to `graphicsLayer { shadowElevation = … }` (its block reads
`size`), and of painting the received bubble's border in a `drawWithCache` instead of
`Modifier.border` — measuring with `onSizeChanged` would need a recomposition and each bubble
would paint one frame with the short-bubble values, a visible corner pop while scrolling. The
border stroke is drawn at **double** width because it is centred on the outline and the
`clip(shape)` above eats the outer half, leaving exactly the intended width inside. The tail
corner stays a fixed 4dp — it is the identity of who wrote the message, not something to scale.
Verified live on the TECNO with a throwaway contact (in-app ⋮ capture, since `screencap` is
black in a chat): a short and a long own bubble in the same run, the tall one visibly rounder
and still reading as a bubble, the 4dp tail intact and the two consecutive bubbles still
separable.
**Bounded read on the inbound message stream (6 Sep 2026)**: the Go handler for
`/krypta/msg/1.0.0` did `io.ReadAll(s)` with no cap — and **any** peer that can dial the phone
can open that stream, because who sent it is not checked in Go but later in Kotlin
(`ChatService.onReceived` resolves the contact by PeerID and drops the unknown one). So a
stranger could make the app allocate as much memory as they cared to write. Now it reads
`io.LimitReader(s, maxIncomingMessage+1)` and resets the stream past `maxIncomingMessage`
(**1 MiB**) — a safety cap, not a product limit: the mailbox refuses blobs over 64 KiB, a file
chunk is 48 KiB and an inline photo ≤58 KiB, so no legitimate send comes near it. The `+1` is
what separates "exactly at the cap" from "went over": with a plain `LimitReader` the two are
indistinguishable and a truncated message would be handed up as if complete (AES-GCM would
reject it, but as "unreadable message", not as what it is). Same one-line fix in
[infra/node/main.go](infra/node/main.go), whose handler had the same unbounded read on a
**public** box (it only logs the bytes, but it is shared infrastructure) — that side takes
effect on the next node redeploy. Covered by Go `TestIncomingMessageIsBounded`, which pins both
edges (exactly at the cap is delivered whole; one byte over delivers nothing) and was checked
to **fail** against the old handler. Note this is about *memory*, not about text length: a
message has no length limit in the UI, and over the mailbox the real ceiling is ~65 KB of text
(64 KiB blob minus the envelope and the GCM nonce+tag). **The two line-based readers were
bounded in the same pass**: `MailboxFetch` and the wake session read the node's answer with
`bufio.ReadBytes('\n')`, which grows without limit if the far end never sends the newline. A
`LimitReader` is the wrong tool there — a legitimate fetch can carry 200 envelopes (several MB)
and would be cut in half — so they now use a **fixed-size buffer + `ReadSlice`**
(`bufio.ErrBufferFull` when a single line overflows it): `mbxMaxLine` 128 KiB, sized for the
largest possible envelope (64 KiB blob → ~87 KiB of base64 plus JSON and the sender's PeerID,
and the same cap the node uses when reading a deposit) and `wakeMaxLine` 4 KiB for the tiny
wake/keepalive lines. Note `ReadSlice`'s slice is only valid until the next read: it is consumed
right there by `json.Unmarshal`, which copies the strings into the struct. An overflow resets
the stream — the unread envelopes stay unacked at the node and come back on the next fetch,
which is the same behaviour as any other interruption. Severity here is lower than the message
handler (the counterparty is a node from your own bootstrap list, not any peer on the internet),
which is why it was worth re-testing rather than skipping. **The AAR was regenerated** twice for
this (`build-aar.sh`; 16 KB pages re-verified on the four ABIs each time). Verified: Go suites
green on both sides; live probes against **all three** nodes (fetch on VPS/Mac/Windows, wake and
a full mailbox round trip on the VPS); and on the TECNO the diagnostics show `DHT: conectado` /
`relay: OK` / `rendezvous: anunciando` / `wake activo`, i.e. both bounded readers work against
production. **The VPS node was redeployed on 6 Sep 2026** (`deploy-vps.sh`, after 29 days of
uptime) so its half of the fix is live: same PeerID (`node.key` untouched), listening again on
`tcp/4001` + `udp/4001` + `ws/8081`, probes green and latency p50 = 104 ms from La Paz. **The two
home nodes (Mac/Windows) still run the older binary** — their `io.ReadAll` is unbounded until
someone runs `deploy-catalina.sh` / copies the new `.exe` on those machines.
**Block a contact (6 Sep 2026)**: the last code-level item Play's user-generated-content
policy asked for (block *or* report; there is no server to receive a report, since Krypta is
E2EE and account-less, so blocking is the measure that can actually be enforced on the
device). All **local, no protocol change**: `Contact.blocked` (Room **v5** + `MIGRATION_4_5`)
and `ChatService.setBlocked`, enforced at four points — (a) `announceAndFind` filters blocked
contacts out, so the rendezvous shared with them stops being advertised and they can no longer
even locate the device; (b) `onReceived` drops them **before decrypting**: nothing is
persisted, no notifier fires, and it never reaches `CallService` (no ring, no "missed call"
row) — returning `null` is also what **acks the mailbox envelope**, so the node deletes it
instead of redelivering it every cycle and eating the recipient's quota; (c) a single
`requireNotBlocked` guard covers **every** outgoing path (`send`/`sendImage`/`sendFile`/
`sendRaw`/`retry` — and through `sendRaw` the call signals and file chunks, through `retry`
the tap on an old FAILED bubble); (d)
`markConversationRead` still clears the local badge but **skips the receipt** — a ✓✓ would
tell them you are still reading. The blocked peer gets **no signal at all**: their messages
stay "sent" for them, exactly as if the phone were off. History is kept (clearing/deleting are
still separate actions). The flag rides in the `.krbk` backup as its own `b=<peerId>` lines
rather than a 4th field of the contact line, so a **previous** Krypta can still read the file
(its parser requires exactly 3 fields and ignores lines it doesn't know) — restoring on a new
phone must not quietly bring a blocked person back unblocked. UI: **long-press a
conversation** and **⋮ in the chat**, both without a destructive confirmation (it's
reversible); a 🚫 badge in `error` next to the name, never "en línea", and in the chat the
input strip is replaced by `BlockedInputBar` ("Has bloqueado a X…" + **Desbloquear**) with the
call button disabled ("Contacto bloqueado"). A pinned voice recording is cancelled when
blocking, or the mic would stay held with no on-screen control left to release it. Covered by
`ChatServiceTest` (dropped + acked, nothing sent by any path, no receipt, out of the
rendezvous, unblocking restores delivery), `IdentityBackupTest` (round-trip + an old backup
still reads) and `HelpContentTest`; verified live on the TECNO with a throwaway contact
(block → 🚫 badge, chat shows the notice and the disabled call button, ⋮ → Desbloquear brings
the input bar back, contact deleted afterwards) — and the **v4→v5 migration on the real
device**: `user_version = 5`, both real contacts intact with `verified` preserved.
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

**Audit follow-through (8 Sep 2026)** — the [7 Sep audit](docs/AUDITORIA-2026-09-07.md)'s
action plan, implemented; tests went 116 → **136** JVM plus 4 new Go ones, and `:data` and
`:native-bridge` got their first tests ever. Six things worth knowing:
(a) **`Node.Advertise` was leaking, and the leak was silently breaking a privacy property.**
It called `dutil.Advertise`, which is *not* a one-shot publish: it spawns a goroutine that
re-announces until its context dies — and the context was `n.ctx`, the node's lifetime. Since
`announceAndFind` calls it once per contact per WAN cycle (30–180 s), goroutines piled up
(~8.600/day with 3 contacts) **and every past day's rendezvous kept being published forever**,
so the daily rotation stopped bounding correlation at all — the exact thing Risk 1 of the plan
exists to prevent. Now it's `n.disc.Advertise(ctx, key)` with a 30 s budget; the WAN loop is
what re-announces. `TestAdvertiseIsSinglePass` counts goroutines and was checked to **fail**
against the old code. (b) That fix would have opened a **discovery hole at UTC midnight** —
the stale announcements had been masking it — so `RendezvousService.rendezvousWindow` landed in
the same change: today's key plus the neighbouring day's during 2 h either side of midnight
(the "ventana de solape" Fase 3 asked for and never got). (c) **The chat re-decrypted its whole
history on every keystroke**: `viewModel.messages(contact)` builds a *new* Flow per call and
`collectAsState` is keyed by instance, so each recomposition restarted collection (and blinked
the list to empty); the mapping also ran on Main with no `flowOn`, deriving an HKDF per message.
Fixed with `remember(contact.id)`, `flowOn(Dispatchers.Default)`, a decoded-message LRU in the
ViewModel and a session-key cache in `AesGcmMessageCipher`. Paging the history is deliberately
still open (it changes what the user sees; it needs a "load more"). (d) **The identity is now
wrapped by an Android Keystore AES key** (`IdentityStore` + `KeystoreKeyWrapper` in
`:native-bridge`): it used to sit in plaintext Base64 in SharedPreferences, and it is the root
of every contact's shared secret. The migration is automatic and the rule is *never lose the
identity*: the plaintext copy is deleted only after a verified wrap→unwrap round-trip, a
Keystore failure falls back to plaintext rather than leaving the user with no identity, and an
unreadable wrapped blob never generates a fresh one. No user auth on the key on purpose —
delivery has to work with the phone locked; this protects cold extraction, not a running
attacker. Covered by 6 JVM tests with a fake wrapper. (e) **The node got its anti-abuse layer**
(Fase 6): the mailbox now enforces a *fair share* per sender — one sender may fill a box while
it's the only one depositing (the legitimate big chunked file to an offline contact), nobody
exceeds half once a second sender appears, and a full box evicts the oldest mail of whoever
exceeded their share. That's the real fix for "a stranger who knows your PeerID can deny you
delivery", which is what the quota-only design allowed. The sender is identified by a short
hash in the filename (`<id>.<tag>.json`; the old `<id>.json` is still read and deleted), so
quota accounting never opens an envelope. Relay limits went from `WithInfiniteLimits()` to
finite-but-generous (8 GiB / 6 h per relayed conn) and the *reservation* caps were raised from
the stock 128/8/**32-per-ASN** — an ASN being a whole mobile carrier, that one was a latent
production bug `WithInfiniteLimits()` never touched — to 4096/256/2048; wake caps at 2000
subscriptions. **None of this is live until the three nodes are redeployed.** (f) Smaller ones:
`ChatService.retryFailed` reconciles FAILED messages each WAN cycle (Fase 4's WorkManager item,
done without WorkManager — the loop already wakes on network change and on the alarm); incoming
messages can no longer overwrite a row of a *different* conversation, nor can a read receipt
mark someone else's message (the id is chosen by the sender and is the Room primary key);
`DiskFileStore` validates incoming metas/chunk indices/chunk sizes and sweeps abandoned staging
(a receiver had no size cap at all, and half-finished transfers stayed on disk forever);
`Libp2pNode.dial()` (dead) removed, its stale "STUB" KDoc fixed, `findPeers` deduped and
`ackedReceipts` bounded. **Docs**: [docs/security-model.md](docs/security-model.md) finally
exists — the oldest outstanding deliverable in the plan — and it says plainly what the node
learns (who deposits for whom and when, presence via the wake stream, single-operator
concentration) and what Krypta does not protect (no PFS, unencrypted Room, traffic analysis);
the privacy policy and the in-app help were updated to match rather than left promising more
than the code delivers. AAR bumped to `0.0.18-rdv1pass`.

**The shared secret left the database (8 Sep 2026, DB v6).** `contacts.sharedSecret` was
stored in the clear, and since each message's key is `HKDF(sharedSecret, "krypta-msg-key-v1")`,
**the `krypta.db` file alone decrypted the entire history** — no identity needed, no code
running inside the app. That made the Keystore work of the same day narrower than it looked:
it protects impersonation and key agreement with *new* contacts, not the stored history. The
fix is not to encrypt the column but to stop writing it: the secret is a pure function of the
identity and the contact's PeerID (`Bridge.SharedSecretFor`, ECDH X25519), so
`RoomContactRepository` now derives it when mapping to domain, `Libp2pKeyExchange` caches it in
memory, and `MIGRATION_5_6` drops the column. Nothing else changed — every consumer still sees
a populated `Contact.sharedSecret`. Two details worth keeping: the migration turns on
`PRAGMA secure_delete` first, or SQLite would just mark the old pages free and **leave the
secrets readable inside the file**, which is the whole point; and that pragma **returns a row**,
so it must run as a query — `execSQL` rejects anything that returns results
("Queries can be performed using query or rawQuery methods only") and the app crashed on launch
on the first try. That crash is also the argument for `:data:connectedDebugAndroidTest`, which
catches it in seconds and, unlike `:app`'s, is safe: its APK is `chat.neto.krypta.data.test`
and cannot touch Krypta's data. Verified on the TECNO: `user_version = 6`, the string
`sharedSecret` no longer appears in `krypta.db` **or its WAL**, contacts intact and previews
still decrypting — which is the proof that derivation works, since the value is no longer on
disk. `:data` also got its first JVM tests (the repository mapping) on top of the migration ones.

**The database is encrypted (9 Sep 2026, SQLCipher 4.6.1).** With the shared secret out of the
DB, what `krypta.db` still leaked was the **local metadata** — contact names, PeerIDs,
timestamps, who talks to whom, message sizes — and that is what this closes. The passphrase is
32 random bytes rendered as hex (`DatabaseKey`), wrapped by an AndroidKeyStore AES key and kept
in the `krypta_db` prefs; it is hex on purpose, because the same value has to travel both
through `SupportOpenHelperFactory` and inside a SQL statement during the one-time conversion,
and a binary string there is a quoting accident waiting to happen. Same "never lose it"
discipline as the identity, one notch stricter: if the wrapped passphrase cannot be unwrapped it
**fails loudly** instead of minting a new one, because a new one would leave the history
encrypted under a lost key and silently start an empty database.

**The conversion of the existing plaintext DB is the part that needed care**, since the message
history has no backup of any kind (`.krbk` carries identity + contacts only). Two things are
worth remembering. (a) **The canonical `sqlcipher_export` recipe hung on the TECNO** — process
alive, no error, no progress, with and without WAL — so `DatabaseEncryption` reads with the
framework SQLite and writes with SQLCipher instead: more code, but every step is observable.
(b) It writes to a temp file and **only swaps after re-opening the copy and checking table and
row counts match**; a first draft swapped unconditionally and an instrumented test caught it
producing an *empty* encrypted DB that would have destroyed the user's history. `sqlcipher_export`
also does not carry `user_version`, which Room needs — the manual copy sets it explicitly.
Verified on the TECNO over the real database: header goes from `SQLite format 3` to random
bytes, `grep -c Lucia databases/krypta.db` returns **0** where it previously matched, contacts
and previews intact. Cost: about +5 MB per ABI (the arm64 debug APK went 61 → 66 MB); SQLCipher
4.6.1 ships all four ABIs 16 KB-aligned, so the Play requirement is unaffected. **Testing gotcha:**
running `:data`'s whole instrumented suite in one process hangs after the SQLCipher tests; each
class passes when run on its own (`adb shell am instrument -e class <FQCN>#<method>`), and a
stale `-journal` left by a killed run will block the next READONLY open until deleted.

**Forward secrecy: design decided, core built (9 Sep 2026, fases 1-2 of
[docs/DISENO-ratchet.md](docs/DISENO-ratchet.md)).** Nothing is wired yet — the app still
encrypts exactly as before — but the crypto core exists and the decisions are closed. The
starting fact that shapes everything: `S = X25519(identity, PeerID)` is a **pure function of the
two identities**, so a symmetric-only ratchet would buy nothing (whoever steals the identity
recomputes `S`, recomputes `CK₀` and unrolls the chain); **all the value is in the DH ratchet**.
The construction is a double ratchet **by epochs**: an epoch is the *pair* of live ephemeral
X25519 public keys and you advance «as soon as you hold both», with no initiator/responder —
which is what removes the root-chain fork that Signal's design avoids only because X3DH and a
prekey server assign the roles. Epoch 0 is derivable from `S` (first message with no round trip,
and deliberately no PFS, exactly like Signal's pre-reply message); from there
`RK(e) = HKDF(X25519(…), salt = RK(e-1))`, HMAC chain per message, derived nonce, `MAX_SKIP`
1000, 3 retired chains kept. A `lineage` (unix millis) in the header handles state loss: the
higher one wins, and because epoch 0 is always derivable **a broken session is never permanent** —
the property Signal cannot have without its server. `encrypt`/`decrypt` are **pure functions**
`state → (state', bytes)`: that is what will let fase 3 commit the ratchet advance in the *same*
Room transaction as the message (decrypting mutates state and burns the message key, so dying
between decrypt and persist would lose a mailbox redelivery **for good** — the 5 Jul voice-note
failure class), and it is also why a forged header can move nothing. Three things implementing
it taught, pinned in `RatchetTest`: the epoch advances **per message received**, not per turn (a
DH ratchet per message, one X25519 each — more than designed, not less); a 85-chunk file burst
still costs **one** epoch, not 85; and the two sides never drift more than one epoch apart, which
is the invariant `decrypt` relies on. **The structural consequence, which is the bulk of the
remaining work**: `Message.ciphertext` stores the *wire* bytes and every read path (`decodeMessage`,
`content`, `notificationText`, the conversation-list preview) decrypts on the fly with the static
key — with a ratchet the key is gone after use, so the whole history, **own bubbles included**,
would go unreadable on the next repaint. PFS forces separating transport keys from at-rest keys;
decided: the history moves to **plaintext envelopes inside the SQLCipher-encrypted DB** (which is
why the 9 Sep DB encryption was the prerequisite), lazily migrated row by row. Also decided:
in-band capability announcement per contact (envelope `V`, ignored cleanly by current clients as
`Decoded.Unsupported`) instead of a `BLIND_DEPOSIT`-style global flag; per-call random key inside
the ratcheted invite (calls get PFS with zero streaming changes); and `krypta_files/` attachments
encrypted at rest. Fase 1 = `Ratchet` + `RatchetState` in `:p2p-signaling` (Kotlin puro, 17
tests). Fase 2 = `Bridge.RatchetKeyPair`/`RatchetAgree` in Go (AAR regenerated, 16 KB pages
re-verified on the four ABIs) + `BridgeCurve25519`; `Curve25519` is an interface in `:core`
because Android has no `XDH` until API 33 and `minSdk` is 30, with `JdkCurve25519` for JVM tests.
**Fase 3 (same day)** = the state on disk, **DB v7**: `ratchet_sessions` (opaque blob, so `:data`
never sees the crypto) + `ratchet_seen` (pre-decrypt dedup, pruned to 500 digests per
conversation), `MIGRATION_6_7` (additive; verified on the TECNO with the real database, contacts
and previews intact), and `RatchetSessions`, which is where the atomicity guarantee lives:
`send`/`receive` **take the persistence as a lambda** and run it inside
`TransactionRunner.inTransaction` together with the ratchet advance, so there is no way to write
one without the other; decryption stays outside the transaction on purpose (pure, and may cost an
X25519). An unreadable state blob **re-hooks the conversation instead of breaking it** (fresh
lineage = now, which the peer adopts). 5 more JVM tests, whose fake runner **rolls back** on
throw — without that the rollback test would pass by accident.

**Fase 4 — the history left the transport key (9 Sep 2026, DB v8).** The structural consequence
described above, done: `messages.ciphertext` is now `payload` and holds the **plaintext
`MessageEnvelope`**, with a per-row `encrypted` flag marking the pre-v8 rows that are still
ciphertext under the static key. Reading tolerates both **forever** (not just during the
transition: a row that cannot be converted must never vanish from the conversation), and
`ChatService.unsealHistory` converts in the background at startup. Three things worth keeping:
(a) the pass goes in batches and **skips with an offset** what it cannot open (a deleted contact,
a corrupt row) — without that, one unreadable row would make the query return it forever and the
rest of the history would never convert; it has its own test; (b) `MIGRATION_7_8` **renames**
(`ALTER TABLE … RENAME COLUMN`) instead of copying the table, so the same bytes stay put and there
is no window where a history that has **no backup of any kind** exists only half-written;
(c) `retry` now **re-encrypts** from the stored envelope (same id, so the receiver still dedups),
while a pre-v8 row is resent byte-for-byte as before. `Message.payload`/`encrypted` ripple through
`RoomMessageRepository`, `MessageDao` (`observeLastMessages` selects the new columns) and
`ChatViewModel`'s decode cache key. Verified on the TECNO **over the real database**: the
diagnostics logged "🗄 historial convertido: 47 mensaje(s)", both conversations render intact, and
`grep` for the contact names and message content in `krypta.db` **and its `-wal`** returns 0 with
a random file header — the check that matters now that the plaintext lives inside. 4 new JVM tests
plus a `MigrationTest` case (v7→v8 keeps the bytes and marks the old rows).

**Fase 5 — the client can receive v2, and says so (9 Sep 2026, DB v9).** `onReceived` now
accepts **both** wire formats: `openRatchet` (through `RatchetSessions`, so persistence and the
ratchet advance commit together) with a fallback to `openLegacy` (static key). Sending is still
v1 — `ChatService.RATCHET_SEND = false` — because the version that knows how to receive has to be
out there first. Capability discovery is **in band and per contact**: a new `V` envelope
(`MessageEnvelope.encodeHello`) that current clients ignore cleanly as `Decoded.Unsupported`,
`contacts.peerProtocol` (what they announced) and `contacts.announcedProtocol` (what we told
them), `MIGRATION_8_9`, and a `capacidades` step in the WAN cycle that announces **once per
contact per version** — marked only if the send actually succeeded, so an offline contact gets at
most one mailbox deposit, not one per launch. That is what will let fase 6 turn sending on per
contact instead of shipping a follow-up release to flip a constant. Three things worth keeping:
(a) **the version byte is a hint, not a guarantee** — a v1 ciphertext is `nonce(12) ‖ ct+tag` with
a random nonce, so **1 in 256 v1 messages over 86 bytes starts with the ratchet's version byte**
(photos, file chunks, any two-line text ≈ 0.4% of traffic); hence the v2→v1 fallback, with a test
that forges the disguise on purpose — written first with a short message, where it failed, because
short ciphertexts never reach the minimum header size and can't be confused; (b) **an
undecryptable message no longer paints a garbage bubble** — it used to persist the raw ciphertext
as "legacy text"; now it is dropped with a diagnostics line, which is also what makes the v2→v1
fallback safe; (c) the user-facing alert moved **out** of the transaction and to a single exit
point in `onReceived` (`persistFile` used to notify on its own, which would now have notified
twice for a file). Verified on the TECNO: the app runs on v9, the WAN cycle is healthy, and the
announcement is sent once — the second and third launches log neither "anunciado" nor "pendiente",
which is only possible if the flag persisted (both outcomes are logged precisely so that "nothing
appears" is not ambiguous).

**Fase 6 — sending, gated per contact (9 Sep 2026).** Every outgoing path now goes through two
functions, `seal` (bytes only: file chunks and meta, call signals, read receipts, the capability
announcement itself) and `sealAndPersist` (bytes + the Message, committed in one transaction with
the ratchet advance), so "does this contact get v2?" is **one decision, not seven**. The gate is
`usesRatchet(contact)` = the contact announced `peerProtocol >= 2` — it depends on *them*, not on
which release is out, which is the whole point of fase 5. **`ChatService.RATCHET_SEND` is still
`false`**: the design's own §10 condition (no enabling without a two-phone test of state loss,
mailbox redelivery and a big chunked file crossing an epoch change) stands, and the steps are now
written down in [docs/PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md) §16. It is an `internal
var` rather than a `const` so the tests can exercise the send path production still has off —
nothing in production writes it. Two things worth keeping: (a) **the state is persisted before the
bytes go out**; saving after would mean that a failed send leaves the next message encrypting from
the same state — same message key, same AES-GCM nonce, which is the textbook way to break an AEAD
completely (there's a test: a send that fails both ways still advances the ratchet); (b) the
session is forgotten on `deleteContact` but **not** on `clearConversation` — emptying a chat is
not breaking the session. New tests cover a full two-client conversation over the ratchet and a
120 KB chunked file through it; one of them found a self-inflicted trap worth remembering — a test
contact whose `id` was `"self"` collided with the outgoing-sender marker and made both halves of
the conversation indistinguishable.

**Fase 7 — the call key is negotiated (9 Sep 2026).** It used to be `HKDF(static secret, callId)`,
so whoever stole the identity could decrypt **any recorded call**, past or future. Now each side
draws 32 random bytes and sends them inside the `C` envelope — the invite carries the caller's
half, the accept the callee's — and the key is `HKDF(k_caller ‖ k_callee, salt = shared secret)`.
The streaming path does not change by a single byte. Three things worth keeping: (a) **the extra
line can't be sent to just anyone** — the previous parser splits the `C` header into three, so
five lines make `toLongOrNull` fail, the whole signal is dropped and **the call doesn't even
ring**; it rides the same `peerProtocol` gate as the ratchet, and everyone else keeps the old
path; (b) the callee's half is drawn **when the invite arrives**, not when the user accepts, so
the key is settled before the stream opens — the first media byte already uses it; (c) there is a
real gain today even with `RATCHET_SEND` off (an attacker now needs that call's signalling
envelope too), and once sending is on, the invite has forward secrecy and the call inherits it.
The test asserts the consequence rather than the mechanism: the frames on the wire **no longer
open** with the key that used to be derived from the identity — and it was checked to fail
against a non-negotiating peer. It also caught me hand-copying the salt literal (`krypta-call-v1`)
wrong, which made the assertion pass for the wrong reason; it now uses the real constant, which is
why `CallService`'s companion is `internal`.

**Fase 8 — the attachments left the clear (9 Sep 2026).** `krypta_files/` was the last thing in
the clear on the device, and with a ratchet the mismatch was glaring: protecting a photo's
*journey* and leaving its *rest* readable. `FileVault` (`:app`, `data/`) encrypts everything the
store writes — staging chunks and meta, the assembled file and the sender's own copy — with
AES-256-GCM under a 32-byte key wrapped by the Keystore, same "never lose the key" discipline as
the DB (an unreadable stored key **fails loudly** instead of minting a new one). Files carry a
`KFV1` magic and **reads tolerate anything without it**: attachments already on the phone stay
readable (verified live on the TECNO — the pre-existing GIF still animates, and reading it doesn't
even need the key). The read side is where the work was: `FileStore` gained `read(path)`/
`saveSent(name, bytes)`; `MediaRecorder` can only write plaintext, so voice notes now record into
`cacheDir` and move into the store encrypted with the temp deleted; `MediaPlayer` can't open an
encrypted file, so a voice note plays through a `MediaDataSource` over the decrypted bytes in
memory (~360 KB per minute) instead of leaving a plaintext copy on disk; GIFs decode from a
`ByteBuffer`; and the UI reaches all of it through a `LocalAttachmentReader` composition local
provided from the ViewModel, because those bubbles sit deep in the tree. **Opening an attachment
with another app hands it over in the clear** — unavoidable — so it stages a copy in
`cacheDir/krypta_abrir/` (now the only path the FileProvider exposes) which is cleared at process
start, not on resume: yanking a PDF out from under an open viewer would be worse. Old attachments
are deliberately **not** converted: rewriting a user's whole store to cover what was already
exposed isn't worth the risk, and clearing the chat deletes them.

**Fase 9 — the user-facing docs (10 Sep 2026).** The rule while `RATCHET_SEND` is `false`: the
help and the privacy policy **do not claim forward secrecy**. Both still warn that the key does
not change over time and that whoever extracts the identity can decrypt the stored history —
promising it before switching it on is exactly what this project has spent a year not doing. What
was added is what is already true: the database and the attachments are encrypted at rest under
Keystore-held keys, opening an attachment with another app hands that app a plaintext copy, and
each call now uses its own randomly drawn key. `security-model.md` §7/§9/§10 updated to match,
including that pre-existing attachments stay in the clear. One test lesson: `HelpContentTest`
rejected the new answer with "respuesta demasiado corta" when it was in fact too **long** — the
assertion covers a range but its message only described one end, and it sent me looking the wrong
way; it now reports the actual length.

**The ratchet is ON (10 Sep 2026): `ChatService.RATCHET_SEND = true`.** Turned on by the author's
explicit decision **before** the two-phone test the design's §10 asked for — the collaborator who
lends the second phone wasn't answering and it had the work stopped. What it actually means
matters: who gets v2 is decided by `contact.peerProtocol`, so **nothing changes until the other
end updates**; the moment it does, that pair goes to the ratchet without having passed the live
test. The exposure while that's true: a v2 message the peer cannot open is **dropped and acked**,
i.e. lost. It is bounded to pairs where *both* run this build, and `RATCHET_SEND = false` in a
later publish returns everything to v1. The test is partly paid — **10 Sep 2026, two
phones both on that day's build: a real bidirectional conversation over the ratchet, no losses**
(the TECNO's diagnostics show `← mensaje` 08:34:24 / 08:35:58 and `→ enviado` 08:36:13, and both
nodes saw two phones on two different La Paz carriers at once). What is **still owed** is the part
that could actually break: mailbox redelivery, a big chunked file crossing an epoch change, state
loss + `.krbk` import, and a call — [docs/PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md) §16.
Note `peerProtocol = 2` could not be read from outside (SQLCipher), so "v2 was in use" is inferred
from both phones running that build, not observed directly. Flipping the default also exposed a latent trap in the
test suite: `conRatchet { }` restored the switch to a hardcoded `false`, so with production now
`true` it would have silently turned sending off for every test after it; it saves and restores
the previous value. **User-facing wording deliberately unchanged**: the help and the privacy
policy still say the key does not change over time, which remains true for every real contact
today, and understating protection is the safe direction to be wrong in — that text moves when the
live test passes, not before.

**The ratchet under chaos, and the two things it found (10 Sep 2026).** `RatchetPropertyTest`
draws random sequences of sends, out-of-order deliveries, drops, duplicates and state losses from
fixed seeds and pins what must never happen: nothing opens as another message (across directions,
epochs or lineages), no `(lineage, epoch, N)` triple ever repeats for a sender — the observable
form of "no message key or nonce is ever reused" — epochs never drift more than one apart, and
after the chaos the conversation recovers **within one round**. Failures print the seed and the
action script, because a property test that fails without the sequence is useless. It found two
things on its first runs, both in `DISENO-ratchet.md` §1.9: (a) **an epoch-0 message can be
replayed** — that epoch is re-derivable from the shared secret, so once its chain is evicted from
the retired ones `openOld` re-derives it and opens the message again; replay protection therefore
rests on the **pre-decrypt dedup** (`RoomRatchetStore`), which is a security component, not a
convenience — its pruning was count-only (newest 500 per conversation), which is backwards for
the real threat: for a chatty pair 500 messages can be half a day, so it stopped protecting
exactly the people who talk most. Fixed the same day to the **union** of two rules — keep a digest
if it is newer than **8 days** (margin over the mailbox's 7-day TTL, which is what actually bounds
legitimate redelivery) **or** among the newest 500 — in `RatchetDao.pruneSeen` (deletes only rows
that are *both* old and surplus; no schema change, the table already had `seenAt` indexed).
**Testing it surfaced a production bug**: `markSeen` runs once per received message and the prune
carries an `ORDER BY seenAt DESC LIMIT 500`, so a 600-message burst (a chunked file) **killed the
test process on the TECNO** — a cost the user was paying per message. It now prunes **one in 64**
inserts (`RoomRatchetStore.PRUNE_EVERY`), which changes nothing about what is kept (the window is
still "8 days or newest 500"; the table just runs up to 64 rows over the cap). Verified by
`RatchetSeenPruneSqlTest`, a **JVM** test that runs the *same string* as the `@Query`
(`RatchetDao.PRUNE_SEEN_SQL`) against sqlite-jdbc in 0.6 s. It started as an instrumented test and
verifying four lines of SQL cost two runs and hours of wall clock: the first died because the
phone dropped off USB — Gradle waited 1h 21m and reported `FAILED` with an **empty** message, the
real cause (`device not found`) buried in `system-err` — and the second killed the process on the
device. **Rule of thumb earned: if the logic is SQL, test it where it can be repeated**; and (b) **the lineage rule lost
messages silently** after a reinstall: the peer who hasn't noticed keeps writing in the old
lineage and everything he sends is dropped (and acked) until the reinstalled side writes.
(b) was fixed the same day with `ChatService.rehook`: the receiver that *fails* to open a
ratchet-looking envelope knows the other side is behind, so it sends the `V` capability envelope
back — reusing an envelope older clients already ignore — **launched off the mailbox path** (that
path is synchronous; the ack waits on `onReceived` returning) and capped at one per contact every
5 minutes, since any contact could feed garbage on purpose. The message that triggered it is
still lost; the window shrinks from "until the other person writes" to one message. **Test-harness
gotcha worth keeping**: work launched into `runTest`'s `backgroundScope` did **not** run under
`advanceUntilIdle()` here — three wrong hypotheses went by before measuring it with a throwaway
probe instead of reasoning about it; those tests now inject a
`CoroutineScope(Dispatchers.Unconfined)` and put the service's own diagnostics log in the failure
message, which is what finally distinguished "not called" from "called and failed".

**The size no longer says what it is (11 Sep 2026, protocol v3).** Fase 2.3 of the privacy plan:
the plaintext is now **padded into bands** inside the encryption (`Padding`, `DISENO-ratchet.md`
§1.10). What it fixes: the node never sees content but always saw **how many bytes**, and that
alone separated a read receipt (~60 B) from a capability announcement (~4 B) from a short "ok" —
i.e. the *structure* of the conversation, who read what and when, legible without decrypting
anything. All of those now measure 160 bytes. Bands are **160 B up to 4 KiB** (Signal's grain,
and for the same reason: that is where control traffic lives) and **1 KiB up to 64 KiB** (covers
the inline photo ≤58 KiB and the 48 KiB file chunk at <2% overhead); **above 64 KiB nothing is
padded**, deliberately — nothing legitimate goes there (the mailbox refuses larger blobs) and the
only thing that can is a huge direct text, where the receiver cuts at 1 MiB, so padding would
risk crossing that cap to hide nothing. Three placement decisions are what make it cheap:
(a) **inside the ratchet, not the envelope**, so one decision covers every type (receipt, hello,
call signal, file meta and chunks, text, photo) — the seven send paths already funnelled through
`seal`/`sealAndPersist` from fase 6, so none of them changed; (b) **the marker is bit 0 of the
header flags**, a byte that was already reserved *and already the AAD*, so it costs nothing and
is authenticated for free — turning it on in transit would eat the message's tail, turning it off
would deliver padding as content, and **both break the AEAD** (there's a test); (c) format
`texto ‖ 0x80 ‖ 0x00…`, Signal's scheme, chosen for its reason too: no explicit length field,
which would be one more thing in the clear. It survives content that ends in zeros (its own stay
*before* the 0x80) and content ending in 0x80 itself.

**The lesson from this one is the version gate, which came within one line of a silent security
regression.** Padding needs the peer to know how to strip it, so it needs a new version —
`PROTOCOL_VERSION = 3`. But `usesRatchet` and the negotiated call key compared
`peerProtocol >= PROTOCOL_VERSION`, so bumping that constant would have **switched the ratchet and
the per-call key negotiation off for every contact that announced 2**, falling back to the static
key: a security regression caused by adding a privacy feature, and invisible, because the v1 path
works fine. Hence a **per-capability minimum** (`RATCHET_MIN_PROTOCOL = 2`,
`PADDING_MIN_PROTOCOL = 3`) with `PROTOCOL_VERSION` now meaning only *what we announce*. **Rule: a
new protocol version is not the threshold of anything — each capability owns its own.** What
padding does **not** fix, said plainly in `security-model.md` §4: a chunked file is still
recognizable (48 KiB padded to a 1 KiB band is still 48 KiB, and a burst still looks like a file)
and a photo still differs from a text; hiding that needs cover traffic and batching, which do not
exist. Covered by `PaddingTest` (8: band edges, content that mimics padding, bounded overhead,
and that a padded chunk **still fits** the mailbox's 64 KiB blob) and `RatchetPaddingTest` (6: the
bit across **both** decrypt paths — the normal one and `openOld` for a retired epoch, which is
separate code — padded and unpadded mixed in one session, and tampering with the bit), plus
`RatchetPropertyTest` now **drawing padding per message**, so the chaos covers it where it can
actually break: out-of-order delivery, duplicates, retired epochs and state loss. JVM suite
**237 tests, 0 failures**. Also corrected while in there: `DISENO-ratchet.md` §1.7 documented an
86-byte header as 70 bytes and put `next_pub` at the wrong offset — the real overhead is 102
bytes per message (header + GCM tag) against v1's 28. **Verified live on the TECNO** right after
installing: the diagnostics log `03:01:57 ↔ protocolo v3 anunciado a 2 contacto(s)` — the
re-announcement fires by itself on a version bump (`announcedProtocol` 2 < 3) and only once —
alongside `DHT: conectado`, `relay: OK`, rendezvous to both contacts and `wake activo`, i.e. the
bump broke nothing. Note **no real contact is padded yet**: `pads()` needs the peer to announce 3,
so padding only engages when the second phone updates — same shape as the ratchet rollout.

**Post-quantum: designed and measured, deliberately not built (11 Sep 2026).**
[docs/DISENO-postcuantico.md](docs/DISENO-postcuantico.md) closes fase 3.2 of the privacy plan as
a *reviewable document with no code*, because the plan's own §3.1 asks for an external protocol
review **before** touching post-quantum and that does not depend on us. The threat is concrete
here and worse than elsewhere: **the PeerID *is* the public key**, `S = X25519(identity, PeerID)`
is a pure function of two long-lived identities, epoch 0 derives from `S` and every later epoch
chains from it with the same curve — so a conversation recorded today is decryptable end to end by
a future quantum adversary without stealing a single device. Measured (Go 1.26.4 `crypto/mlkem`,
JDK 25 `SunJCE`): ML-KEM-768 is **1184 B** of encapsulation key + **1088 B** of ciphertext, and
costs **40–60 µs** — the same order as X25519. **So CPU is a non-issue and size is the entire
problem**: at 2272 B per message a read receipt goes 162 → 2434 B, **×15**, and receipts are not a
rare case (they are what advances epochs just by opening a chat). That kills the plan's original
"re-encapsulate per epoch" wording. The design instead is a **slow PQ ratchet decoupled from the
epoch ratchet**, resting on a property the ratchet already has: the root *chains*
(`salt = RK(e-1)`), so **one successful PQ injection protects everything after it, forever** —
enter early, refresh every ~20 epochs, 2272 B per round instead of per message. Hybrid is
**concatenation, never replacement** (`ikm = X25519(…) ‖ pq`), so a broken ML-KEM leaves exactly
today's security. Feasibility checked rather than assumed: the JDK has ML-KEM-768 natively so JVM
tests keep working (`JdkKem` mirroring `JdkCurve25519`), the **public key is portable** between Go
and the JDK as raw 1184 B plus a fixed 22-byte SPKI prefix (verified both ways), and the **private
key is not** (Go gives a 64 B seed, the JDK a 2400 B expanded key) — which turned out not to
matter, because the ratchet state never travels: it is written and read by the same implementation
on the same device, so the private half stays opaque exactly like `Curve25519.KeyPair` today. Two
gaps stated plainly in the doc: **epoch 0 stays classical** (Signal *does* cover the initial
agreement via PQXDH, because it has a prekey server and we deliberately don't), and
**authentication does not become post-quantum** (that needs ML-DSA and a new PeerID format; Signal
lacks it too). The uncomfortable lesson that shapes its phase 1: **being hybrid hides bugs** — if
ML-KEM were broken, or one AAR ABI shipped garbage, the app would work perfectly and be exactly as
secure as today, with nothing failing and nothing warning, so the property you think you bought
simply wouldn't exist. Hence a FIPS 203 known-answer test is **not optional**; it is the only thing
separating "hybrid" from "classical with 2 KB of expensive padding".

**And its phases 1–2 are built (11 Sep 2026), deliberately: they encode no protocol decision.**
Only the primitive and its vector, so they prejudge nothing the design's §7 leaves open. `Kem` in
`:core` (interface, because **Android has ML-KEM at no API level** — X25519 at least arrives at
33), `BridgeKem` over Go `crypto/mlkem` for the device, `JdkKem` over JDK 25's `SunJCE` for tests,
bound in `SignalingModule` (nothing injects it yet; the consumer is fase 4). JVM suite **246
tests, 0 failures** (`KemTest` 9) plus 4 Go tests, AAR regenerated and re-verified: the three
`kem*` functions are in the generated API and all four ABIs still link at `0x4000`. Four things
worth keeping: (a) **the KAT turned out better than designed** — it is a *cross-implementation*
vector (`native-bridge/libp2p/testdata/mlkem-kat.txt`): the ciphertext was produced by the **JDK**
encapsulating against the raw key **Go** derives from a fixed seed, and the Go test checks Go
recovers the same secret, so if the wire format ever stops matching between the two
implementations the test fails — which is exactly the claim the whole design rests on; (b) **the
unit tests were not running on the JDK everyone assumed**: `KemTest` failed wholesale with
`NoSuchAlgorithmException: ML-KEM-768 KeyPairGenerator not available`, which reads as "this JDK
lacks it", and a throwaway probe showed the test task was on an **autodetected Temurin 21** even
though Gradle's own launcher is 25 — **rule: when an algorithm "doesn't exist", first check which
JVM you are looking at**; (c) fixing that needs `serviceOf<JavaToolchainService>()`, because an
**Android library module under AGP 9 does not apply the `java` plugin**, so
`extensions.getByType<JavaToolchainService>()` dies with "Currently registered extension types:
[ExtraPropertiesExtension]"; (d) **ML-KEM is implicit-rejection** and that is pinned on both sides
on purpose — a tampered ciphertext yields a *different* secret, never an error, so wrapping
`decapsulate` in a `runCatching` validates nothing.

**A crash the ratchet work surfaced (9 Sep 2026): Krypta started once and never again.**
`System.loadLibrary("sqlcipher")` sat *inside* `DatabaseEncryption.encryptInPlace`, **after its
early return**. On the launch that converted the plaintext DB it loaded; on every launch after
that — DB already encrypted, early return — it did not, and Room died opening the database with
`UnsatisfiedLinkError: No implementation found for … nativeOpen`. `net.zetetic:sqlcipher-android`
does not self-load. The bug is invisible to a verification done right after the conversion, which
is exactly how the SQLCipher change was checked, and it had left the author's phone unable to open
the app. Fixed **by construction** rather than by adding a call: a new `SqlCipher` object in
`:data/crypto` whose `openHelperFactory(passphrase)` loads before building the factory, and that
is now the only way `DatabaseModule` gets one. Regression test `SqlCipherTest` (instrumented,
`:data`, so it never touches the app) reproduces the *second* launch — it was checked to fail with
the real `UnsatisfiedLinkError` against the pre-fix code — and the app was re-verified live on the
TECNO: it launches, the conversation list renders with both contacts and their decrypted previews,
and the status reads "conectado".

**Anyone who knows your PeerID can get your public IP (verified 10 Sep 2026).** Two probes in
`native-bridge/libp2p/ip_leak_probe_test.go`, run from an ephemeral identity whose only advantage
is knowing the nodes (they ship in the APK) and the victim's PeerID — which is not a secret:
`TestIPLeakAgainstLiveNode` asks a node `FindPeer(<peerID>)` and it hands over the phone's
addresses (kad-dht's `handleFindPeer` returns peerstore addrs of any connected peer, unfiltered);
`TestIPLeakViaRelayDial` then dials the phone through one of those `/p2p-circuit` addrs — **the
connection is accepted** (there is no `ConnectionGater`; `ChatService.onReceived`'s unknown-peer
drop sits a layer above and is too late) and libp2p **starts hole punching on its own** for any
inbound relayed connection, handing a stranger `/ip4/<phone's public IP>/udp/<port>/quic-v1` in
1.7 s. Contributing factors: `circuitAddrsFactory` **appends** relay addrs without filtering any
of the host's own, and mDNS is always started. Also corrected the same day: `security-model.md` §5
claimed a DHT observer "cannot know who your contacts are" — false, both sides of a pair advertise
the **same** rendezvous key and phones are DHT clients, so the infra nodes hold the daily pair
graph (in memory) for every active pair, blind deposit or not.

**The relay-dial vector was closed the same day** with `native-bridge/libp2p/gater.go`: a
`ConnectionGater` where **outbound is never filtered** (we must be able to dial nodes and
contacts) and **inbound only passes if the PeerID is on a list** the app fills with its
non-blocked contacts plus the infra nodes — `ChatService.pushAllowedPeers`, called from `start()`
and from `announceAndFind` every WAN cycle, so adds/removes/blocks are picked up with no extra
plumbing (blocked contacts are excluded, so they can't harvest the IP either). It cuts at
`InterceptSecured`, i.e. as soon as the handshake reveals who is calling and **before** a
connection exists, so no identify and no hole punching. Wired through `ISignalingService`
(default no-op body so test doubles keep compiling) and exposed for diagnostics as
`filtro de conexiones: N permitido(s) · permitidos=N entrantes=N rechazadas=N`. Verified live on
the TECNO: 4 allowed (2 contacts + 2 nodes), probe now reports *"el filtro cortó al extraño"*,
and no regression (`relay: OK`, rendezvous announcing, both nodes still seeing the phone). Go
tests `TestGater*` include the real shape of the attack — a stranger dialing **through the
relay** — and read the gater's own counters so a failure says which of three things happened
(never consulted / consulted but allowed / list empty = open). Two lessons worth keeping: **a
live probe that judges by its own `Connect` result lies** — the dialer can see success a moment
before the far side closes, which made the first post-fix run look like the leak was still open;
and kad-dht's `PublicQueryFilter` drops peers whose addresses are **all** relay addrs, which is
why `FindPeer` answers `routing: not found` while the phone is on cellular. **mDNS is opt-in since the same day**: it used to start always and announce the PeerID and LAN
address to the whole WiFi (a stable identifier, visible to anyone sharing a café/office network),
while Krypta's real discovery is WAN. The pref (`lan_discovery` in `krypta_settings`, same pattern
as the bootstrap) is toggled from Ajustes → "Red local" and takes effect **immediately in both
directions**: `StartMdns` now stores the service on the Go `Node` (it was a local variable, so it
could never be stopped) and there is `StopMdns`; the Kotlin side also **releases the
`MulticastLock`**, which until now stayed held for the life of the process. Verified on the TECNO:
switch off on a fresh start, toggling logs `descubrimiento LAN activado` / `desactivado`, and WAN
is unaffected. **Still open from §5.1**: the node hands out phone addresses via `FindPeer` — and
note the obvious fix does **not** work: kad-dht applies the same `AddressFilter` to provider
records (`handlers.go:325`), i.e. to the rendezvous, so filtering there would leave contacts
unable to find each other; separating the two needs a kad-dht patch, and the no-patch alternative
(never publish public direct addrs, everything via relay) depends on DCUtR, which is the NAT gate
nobody has measured. Also open: LAN addrs are still advertised, and there is no relay-only mode
(contacts see the IP by design).

**The node's log was keeping user identifiers (found 10 Sep 2026).** The `/krypta/msg` handler in
`infra/node/main.go` printed the sender's PeerID and ciphertext of every direct message, and on the
VPS stdout lands in a **persistent** journal *and* — through rsyslog — `/var/log/syslog`: 12 such
lines were on disk since 7 Aug, contradicting the privacy policy's "no record is kept". Setting
journald to volatile would not have fixed it (rsyslog). Fixed at the source: `msgHandler` writes
nothing unless the new `-debugmsg` flag is passed (local test nodes only); pinned by Go
`TestMsgHandlerNoRegistraIdentificadores` plus a debug-mode twin so the first can't pass by a broken
capture. **Rule: a node log line never carries PeerIDs, IPs or mailbox labels.** **Live on the VPS
since 10 Sep** (redeployed, same PeerID, probes green) and its `/var/log/syslog*` purged, with a
`HUP` to rsyslog so it reopens the rewritten file. Its **journal was deliberately left to rotate**
rather than vacuumed (that would also erase SSH/system logs), and with the 30-day retention set the same day
the old lines age out around **10 Oct 2026**. The Mac and Windows nodes still run the old binary,
and the Mac's `~/krypta/node.log` (never rotated) is unpurged. The operational-trust roadmap this
came out of is in `security-model.md` §10.

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
- **The AAR's consumer ProGuard rules are narrowed by the build script** (2 Sep 2026):
  gomobile writes a `proguard.txt` *inside* the AAR derived from `-javapkg`, containing
  `-keep class chat.neto.krypta.** { *; }` — the `-javapkg` prefix, which covers the **whole
  app**, not just the bound package. Because consumer rules live inside the AAR they never
  appear in [app/proguard-rules.pro](app/proguard-rules.pro), so the effect was invisible: R8
  was **not obfuscating, optimizing or shrinking any of Krypta's own code** (member names
  included), only the libraries'. `build-aar.sh` now rewrites that entry to
  `chat.neto.krypta.bridge.**` after `gomobile bind`. Own-code obfuscation went 12.8% → 92.7%
  and the DEX 3.03 → 2.73 MB. **If you ever regenerate the AAR by calling `gomobile bind`
  directly instead of via the script, the wide rule comes back.** Runtime-verified under R8 on
  device (node starts, valid PeerID, real connections, "conectado").
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
- **Room migrations, not destructive.** `KryptaDatabase` is at **v9** with real migrations
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

**Privacy/trust roadmap:** what is still missing for Krypta's bet to be *true and checkable* —
metadata (the DHT pair graph, blind deposit, padding, the IP), external crypto review and
post-quantum, transparency (open source, reproducible builds, operator page), identity rotation,
and the product gaps — is ordered in
[docs/PLAN-privacidad-y-confianza.md](docs/PLAN-privacidad-y-confianza.md) (10 Sep 2026). It came
out of verifying an external Signal comparison against the code; the comparison itself is
deliberately not versioned here (circumstantial, others will follow) — the plan is. A **second
comparison was reviewed on 12 Sep 2026** the same way, with conclusions (errors on both sides, what
it misses, proposed plan changes) in
[docs/REVISION-comparacion-signal-2026-09-12.md](docs/REVISION-comparacion-signal-2026-09-12.md).
Two things came out of it the same day. **(a) Blind deposit is ON, per contact**: it had been
waiting for "the receive-capable version to be out there" as a global flag, but the git history
shows blind *receive* (9 Sep, `63222d1`) landed **before** the `V` capability announce (10 Sep,
`afb576a`), so any contact announcing protocol ≥ 2 already fetches by label — `outboxLabel` now
gates on `peerProtocol >= BLIND_MIN_PROTOCOL` (= 2), same shape as the ratchet/padding gates, and
`BLIND_DEPOSIT` stays as the global rollback switch. Both VPS were probed first
(`TestBlindMailboxAgainstLiveNode`). The two-phone check is PRUEBAS-PENDIENTES §16.10. **(b) A
finding about epoch 0**: a test meant to pin "after the capability exchange the first user message
already has PFS" showed it does **not** — each side creates its session with its local clock as
lineage, the receiver creates it later (higher lineage), opens the peer's hello via `openOld`
without adopting the lower lineage, and its first message goes out in epoch 0; only the peer's
first reply moves both. The obvious fix (a session that has encrypted nothing adopts the lower
lineage) was **killed by `RatchetPropertyTest` on its first run** (seed 102): after a reinstall the
session is also "virgin", and adopting an old lineage reuses `(lineage, epoch, N)` triples this
identity already spent — lineages must be monotonic per identity, so it was reverted. Today the
first message's PFS **depends on the clocks** (receiver's clock behind → adopts → epoch 1); the
real fix (receiver answers with a control envelope) is written down in `DISENO-ratchet.md` §1.8.4
and deliberately not built before the external review. Pinned by two `ChatService` tests (one per
clock case, deterministic by seeding A's lineage) and one `RatchetTest`. **(c) `ParserFuzzTest`**:
seeded mutation fuzzing (no new deps, runs in every `testDebugUnitTest`) of everything that parses
outside bytes — `MessageEnvelope.decode`, `Ratchet.decrypt`/`Header.decode`, `Padding.strip`,
`RatchetState.decode`, `IdentityBackup.decode` — each against its **contract** (which exception
types are allowed, mutated envelopes never open, state untouched). It found a real one in
`RatchetState.decode`, and not the expected one: the per-blob bounds check `pos + n <= bytes.size`
**overflows** with `n = 2³¹−1` (the sum goes negative and passes; `Arrays.copyOfRange` then computes
`to − from` = `n` → `new byte[2³¹−1]` → `OutOfMemoryError`). Fixed as `n <= bytes.size − pos`, and
the list counts were bounded by remaining bytes in the same pass (`List(n)` reserves `n` slots
before reading anything). Low severity — the blob lives inside SQLCipher and `stateFor` recovers —
but an eyeball review had passed that line. Go readers were already bounded before allocating.

**Security contact (12 Sep 2026):** [SECURITY.md](SECURITY.md) (Spanish + English) —
`info@4000msnm.com`, the same address as the privacy policy; ack within 7 days, coordinated
disclosure at 90 days, no bounty, no load testing against the public nodes. The public copy is
`security.txt` (RFC 9116), source `infra/node/security.txt`, served at
`https://krypta-{sp,dal}.neto.chat/.well-known/security.txt` by Caddy (`deploy-caddy.sh` now copies it
and routes only that path to `file_server`; everything else still reverse-proxies to the node's ws).
Deployed to both VPS the same day and verified: HTTP 200 `text/plain`, libp2p over `wss/443` and
`tcp/4001` still green (`check-nodes.sh`), and the curl's source IP absent from Caddy's journal and
syslog. **`Expires: 2027-09-01`** — renew the field and re-run `deploy-caddy.sh` on both VPS before
then. **The GitHub repo (`dasilvabalautaro/Krypta`) is public since 12 Sep 2026**, so `SECURITY.md`
is public too — and so is the whole git history, which was scanned for secrets and personal data
the same day (see `docs/REVISION-comparacion-signal-2026-09-12.md` §4.8).

**Node monitoring is actually running (12 Sep 2026).** `infra/node/chat.neto.krypta.check.plist`
had existed since 8 Sep but was **never loaded** — there was no monitoring at all. It is now loaded
on the dev Mac (every 15 min, log `/tmp/krypta-check.log`). Two fixes to `check-nodes.sh` first:
(a) **cost** — each probe was a `go test` relinking libp2p: measured 70 s wall / 84 s CPU per run,
~10 % of a core sustained; the probes are now compiled once to `$TMPDIR/krypta-check/probes.test`
(rebuilt only when a `.go`/`go.mod`/`go.sum` is newer), ~23 s / 1.2 s CPU; (b) **alerts on state
change only** (starts failing — naming the node —, what fails changes, recovers), state in
`~/Library/Caches/krypta-check.state` (`KRYPTA_CHECK_STATE` overrides it). Verified with a simulated
failure (closed local port) and recovery. **Real limit**: the Mac sleeps (`pmset sleep 1`) and
launchd runs nothing while asleep, so a night-time outage alerts only when the Mac wakes; a real
alert must come from off the Mac (e.g. each VPS checking the other) over a channel still to be chosen.
**Decided the same day: keep the Mac from sleeping** instead (check `pmset -g`, `sleep` must be `0`).
It proved itself immediately — the launchd run at 12:01 UTC caught Dallas down mid-redeploy. And it
exposed a bug: **only `--notify` (the unattended mode) may touch the state file**. A manual
single-node verification run seconds later overwrote the recorded outage with "all good" (0-byte
state at 12:01:49), so the next unattended run would never have announced the recovery; a manual
run with explicit nodes would also compare against the full-list signature. Fixed by guarding the
whole state block with `NOTIFY`.

**Mailbox fetch is rate-limited (12 Sep 2026, deployed to both VPS).** `handleGet`/`handleGetV2` had
no limit, so anyone could make a node list and read files at will (a v2 GET asks for up to 1024
labels). `infra/node/mailbox.go` now has a token bucket per stream PeerID (burst 60, one token per
5 s; v1 and v2 share it, since the client opens both per fetch) **plus a global bucket** (burst
2000, 200/s), because libp2p identities are free and a per-peer bucket alone doesn't stop someone
rotating them. Over the limit the node answers an **empty fetch that still follows the protocol**
(`{"done":true}` + reads the ack) — closing without reading the ack would make the client see a
write error and count the node as down; this way mail simply stays and comes out on the next fetch.
The deposit limiter was refactored onto the same helpers (`bucketFor`/`topUp`/`pruneMap`) with its
tests unchanged and green. Tests `TestRetirada*` (5) use **real streams**, so any protocol deviation
makes the `mbxGet`/`mbxGetV2` helpers abort; one of them first failed for a test-only reason (the
helper returns right after sending the ack, before the node deletes) and now waits for the delete.
Cost to know: the client does one fetch per wake notice, not coalesced, so during a chunked-file
burst some fetches come back empty and chunks arrive a few seconds later — delay, not loss. Built
with `go1.22.12` (`dist/krypta-node-linux-amd64`, sha256 `9126536c…`), suite green under Go 1.22
and 1.26, deployed São Paulo then Dallas; both hashes match, `check-nodes.sh` green over `tcp` and
`wss`, PeerIDs unchanged, no foreign PeerIDs in either journal. **Verification gotcha found
doing it:** after a node restart the phone reconnects over **QUIC** (`udp/4001/quic-v1`, learned via
identify even though `DEFAULT_BOOTSTRAP` only lists tcp/wss, and libp2p ranks QUIC first), and QUIC
uses an unconnected UDP socket, so `adb shell ss -tn` shows **no** connection to the VPS at all while
the app is perfectly connected. Confirmed with `tcpdump` on the VPS (UDP 4001 to the home IP). Don't
read "no sockets in `ss`" as "disconnected"; the diagnostics `relay:` line says the real transport.

**License, README and commit identity (12 Sep 2026).** Dual-licensed **MIT OR Apache-2.0** at the
user's option (`LICENSE-MIT`, "Krypta contributors"; `LICENSE-APACHE` is the canonical text from
apache.org, sha256 `cfc7749b…`), with a root `README.md` (Spanish + English summary) that states
the beta status, the unreviewed custom protocol and the metadata limits up front. Commits in this
repo now use `info@4000msnm.com` (`git config --local user.email`); the old personal address
remains in the existing history, which was deliberately not rewritten (see
`docs/REVISION-comparacion-signal-2026-09-12.md` §4.8). For GitHub to link new commits to the
account, that address has to be added to it.

**Identity rotation: designed, not built (12 Sep 2026).**
[docs/DISENO-rotacion-identidad.md](docs/DISENO-rotacion-identidad.md), reviewable and code-free like
the post-quantum one. The shape: an `M` envelope inside E2EE carrying a canonical text
(`krypta-rotation-v1`, old/new PeerID, `seq`, `ts`) signed by **both** the old key (authorizes) and
the new one (proves possession, so a key holder can't point contacts at a third party's PeerID); a
14-day grace period where the old key is kept only to fetch mail under the old **blind labels** (bearer
credentials, so no second host is needed), open late messages under the old `S` and re-send the
notice; receivers keep `Contact.id` as the local conversation id and swap `peerId` (network lookups
already go through `findByPeerId`; only `addContact` assumes `id == PeerID`), drop verification
(recommended), forget the ratchet session and answer with a control envelope to leave epoch 0.
**Fork detection** (two valid rotations of the same key → conversation frozen as "disputed") and
revocation without successor are part of it. Stated plainly: it does not help if the key is lost, and
against a thief it only helps if both notices reach the contact. Phases 1–3 don't touch the wire;
4–5 wait for PRUEBAS-PENDIENTES §16 and the external review.

**Audit:** an architecture/code audit against the plan's objectives (7 Sep 2026) lives in
[docs/AUDITORIA-2026-09-07.md](docs/AUDITORIA-2026-09-07.md) — findings A-1…A-14 with a
prioritized action plan; update it (or supersede it with a newer one) as items close.

**Live-test tracking:** features that are implemented + unit/probe-tested but not yet
confirmed with two real phones go in [docs/PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md)
(the collaborator's second phone isn't always available). Add the exact steps + expected
result there instead of blocking on the live test; move items to its "ya verificado" section
once confirmed. **Deploy routine:** keep the author's phone on the latest build
(`:app:installDebug`) and refresh `~/Desktop/krypta-arm64-debug.apk`
(`:app:assembleDebug -PslimAbi` + copy) after any code change, so the author can share it.
