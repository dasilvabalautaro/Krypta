# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

It holds the **current state, the rules learned the hard way, and how to operate the project**. The
full narrative of how each piece was built, what broke live and why each decision is what it is —
which used to fill ~2,000 lines of this file — lives in [docs/HISTORIAL.md](docs/HISTORIAL.md),
moved there verbatim on 12 Oct 2026. Search it before re-deciding something: most "why is it like
this?" questions are answered there.

## What Krypta is (state at 12 Oct 2026)

A **WAN, decentralized, E2EE P2P messenger for Android** (Kotlin + Jetpack Compose) with no central
server, no accounts and no phone number. Design and roadmap:
[docs/PLAN-senalizacion-descentralizada.md](docs/PLAN-senalizacion-descentralizada.md); current
architecture: [docs/architecture.md](docs/architecture.md); threat model, unsoftened:
[docs/security-model.md](docs/security-model.md); normative protocol spec:
[docs/ESPECIFICACION-protocolo.md](docs/ESPECIFICACION-protocolo.md) (English mirror:
`ESPECIFICACION-protocolo.en.md`).

- **Transport**: go-libp2p compiled to `native-bridge/libs/krypta-p2p.aar` with gomobile (TCP +
  QUIC, Circuit Relay v2 on the infra nodes, DCUtR hole punching). Inbound connections pass a
  `ConnectionGater` only from contacts and nodes (closes the IP leak via relay dial). Dial ranker
  puts direct addrs before WebSocket; a pruner closes redundant wss conns unless they carry a call.
- **Discovery**: per-pair daily rendezvous `HKDF(S, date)` on a Kademlia DHT, advertised in a single
  pass each WAN cycle, with a ±2 h overlap window at UTC midnight. mDNS is opt-in (off by default).
- **Delivery**: a `MessageEnvelope` (types T/I/R/F/K/C/Y/V; D is local-only) goes **direct** over
  `/krypta/msg/1.1.0` — the receiver stores, then answers a 1-byte ack (`1.0.0` without ack for old
  peers) — else into the node's **E2EE mailbox** (blind labels for contacts announcing ≥ 2), else
  FAILED and retried each WAN cycle. **Both paths ack only what was persisted**. A **wake** stream
  from the node triggers an immediate fetch; a foreground service (`specialUse`) and a heartbeat
  alarm keep it alive.
- **Crypto**: Ed25519 identity, Keystore-wrapped; `S = X25519(identity, PeerID)` is derived, never
  stored. v1 = static key AES-256-GCM; v2+ = **epoch double ratchet** (`RATCHET_SEND = true`, gated
  per contact by `peerProtocol`), v3 = size padding, v4 = independent-output root KDF + epoch 0 bound
  to the sender's proposal (`PROTOCOL_VERSION = 4`). Lineage rules R1 cap / R2 relay / R3 restart
  row. Call keys are negotiated (`k_caller ‖ k_callee`). Capability announcement in band (`V`).
- **At rest**: SQLCipher DB **v10**, opened lazily on the first query; history stored as plaintext
  envelopes inside it; attachments encrypted (`FileVault`); `secure_delete` on every connection.
- **Product**: text, photos, chunked files (≤ 8 MB), voice notes, animated GIF/stickers, replies,
  copy, voice and video calls, safety number + QR, block, clear/delete chat, `.krbk` backup, app
  lock, notification-content setting, light/dark theme, in-app help, `FLAG_SECURE` on the chat.
- **Infra**: two VPS run by **4000MSNM S.R.L.** (São Paulo primary, Dallas backup), see Operations.
- **Status**: Play versionCode 7 (1.6, protocol v3) uploaded 4 Oct 2026, in final verification. **Open
  blocker**: background delivery fails on phones that freeze processes (TECNO/HiOS; 8 h without a
  WAN cycle on 3–4 Oct) — see [docs/PLAY-STORE.md](docs/PLAY-STORE.md) and PRUEBAS-PENDIENTES §13.
  No external review: OTF declined, NLnet dropped, and since 11 Oct the project runs **its own
  audits** (never call them independent). Formal models (Tamarin) in [docs/formal/](docs/formal/).
  Two-phone tests owed: PRUEBAS-PENDIENTES §16, §19, §20.

## Rules learned the hard way

Each of these cost a live failure or a wrong fix once. The story behind each is in
[docs/HISTORIAL.md](docs/HISTORIAL.md) or in the audit/review it names.

**Delivery and alerts**
- An alert must never depend on a collector that may not exist. User alerts go through direct
  hooks (`ChatService.setIncomingNotifier`, owned by `IncomingNotifier` attached from the
  `Application`), and `CallService` is instantiated at process start; a `SharedFlow(replay = 0)` with
  `tryEmit` silently drops what has no subscriber or overflows its buffer.
- **Ack after persist, on every path.** The mailbox processor and the direct processor are
  synchronous and return whether the envelope was stored. A persistence error must propagate; it
  must never be swallowed as "doesn't open" (O-1 of AUDITORIA-2026-10-12: the ratchet path did
  exactly that and acked lost messages). An id that already exists is never rewritten.
- Anything the receiver sends in reaction to an incoming envelope (rehook, hello) is **launched**,
  never awaited on the mailbox path, and rate-limited per contact.
- Read receipts carry **only what just went from unread to read** (`markIncomingRead` returns it), in
  batches of 100 (O-2).
- Budgets: every WAN step has its own; `pollOnce` must fit in 45 s (broadcast ~60 s, wake lock 55 s).

**Ratchet and protocol**
- The ratchet state is **saved before the bytes leave**, and `RatchetSessions` serializes each
  conversation with a `Mutex` (H-0). A concurrency test must read, then `yield()`, then write — a
  yield before the read passes by accident.
- **A new protocol version is not the threshold of anything**: each capability has its own minimum
  (`RATCHET_MIN_PROTOCOL`, `PADDING_MIN_PROTOCOL`, `KDF4_MIN_PROTOCOL`, `BLIND_MIN_PROTOCOL`).
- `peerProtocol` and `announcedProtocol` never decrease, and contact fields are written **one column
  at a time** (`setVerified`, `setBlocked`, `markAnnounced`): a whole-row write from a stale copy
  undoes what the user did meanwhile (H-2, O-3).
- Lineages must be monotonic per identity: a "virgin" session must not adopt a lower lineage
  (`RatchetPropertyTest` seed 102). Relay is v4-only (seed 504).
- Epoch-0 dedup digests are pinned, never pruned (W-2); the rest are kept 8 days or newest 500.
- ML-KEM is implicit-rejection (a tampered ciphertext gives another secret, no exception), and
  "hybrid hides bugs": a known-answer test is mandatory before any PQ code ships.
- Code wins over the spec; fix the spec (both languages) in the same change.

**Persistence and Android**
- Every schema change = a `Migration` + version bump (DB is v10). A PRAGMA that returns a row goes
  through `query`, not `execSQL`. SQLCipher is only obtained through `SqlCipher.openHelperFactory`
  (it loads the native lib; forgetting it crashed every launch after the first).
- **If the logic is SQL, test it on the JVM**: keep the query in a `const` and run the same string
  with sqlite-jdbc (`ContactUpsertSqlTest`, `MessageWindowSqlTest`, `RatchetSeenPruneSqlTest`).
- `:app:connectedDebugAndroidTest` **uninstalls the app and wipes the identity** — ask first.
  `:data`'s instrumented tests are safe (own package `chat.neto.krypta.data.test`) but must run one
  class at a time (`adb shell am instrument -w -e class …`); a stale `-journal` blocks the next run.
- `FLAG_SECURE` is set per screen (chat only). `adb shell screencap` is black there: use
  `uiautomator dump` or the in-app ⋮ → Capturar (it draws the view on a software canvas; `PixelCopy`
  would be black). Popups inherit the flag and cannot be captured at all.
- The TECNO's OEM log limiter mutes logcat: read the in-app Diagnóstico (via `uiautomator dump`).
- Compose gestures: one gesture modifier per element (a chained second one never sees the event);
  keep a `pointerInput` element in composition while the gesture lasts; the mic button has no
  `TooltipBox` (it swallowed the long-press). The chat input is the state-based `TextField` because
  only that stack wires `contentReceiver` (GIF/stickers).
- Notifications: cancel children before the group summary (the system recreates an empty
  `ranker_group`); `CallStyle` without a full-screen intent is rejected at `notify()` time, which only
  an instrumented test catches.

**Tests and tooling**
- In `ChatServiceTest`, work launched into `runTest`'s `backgroundScope` does **not** run under
  `advanceUntilIdle()`: inject `CoroutineScope(Dispatchers.Unconfined)` (`scopeInmediato()`) and put
  the service's diagnostics log in the failure message. `ChatService`'s internal test constructor
  runs crypto on the caller's thread (no `CpuContext`), so virtual time still works.
- When an algorithm "doesn't exist", check which JVM runs the tests (an autodetected Temurin 21 once
  hid JDK 25's ML-KEM). An Android library under AGP 9 has no `java` plugin: use
  `serviceOf<JavaToolchainService>()`.
- A live probe that judges by its own `Connect` result lies: the far side can close a moment later.
  Read the far side's counters.
- macOS has no `timeout`; a `timeout N cmd` fails instantly and leaves stale results.
- `llvm-strings | grep -q` under `pipefail` fails on a match (SIGPIPE): grep a file.
- QUIC connections do not show in `adb shell ss -tn`; the Diagnóstico `relay:` line says the transport.
- Dialing a node with a wrong PeerID makes the Noise error reveal the real one.
- A Tamarin rule that re-produces its own state fact makes reveals chain forever.

**Writing and claims**
- Understating protection is the safe direction to be wrong in. Never claim an unverified property
  (forward secrecy, independence of a review) in user-facing text, and never call an internal audit
  independent or external. `temp/` holds internal review material and is never committed.
- The security docs being current is no evidence the user-facing ones are: when what leaves the app
  or what the node sees changes, check `docs/politica-privacidad.html`, `docs/MANUAL.md` and
  `ui/HelpContent.kt` too (`HelpContentTest` bounds each answer to 40..600 chars).
- A node log line never carries PeerIDs, IPs or mailbox labels.

## Operations

- **Nodes** (`DEFAULT_BOOTSTRAP` in `Libp2pNode`, São Paulo first; each node listed twice, `tcp/4001`
  then `wss/443`; the bridge groups lines by PeerID):
  - São Paulo — Vultr, `216.128.169.83`, PeerID `12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5`,
    `krypta-sp.neto.chat`.
  - Dallas — InterServer, `163.245.192.235`, PeerID `12D3KooWQf7ZM3kXxc76XEN3Aj8gxhYorSKViPEGF4zepQuMQVCM`,
    `krypta-dal.neto.chat`.
  - Deploy with `infra/node/deploy-vps.sh` (keeps `node.key`) and `infra/node/deploy-caddy.sh`
    (wss/443 and `/.well-known/security.txt`, no IPs in logs). `node.key` backups and the SSH keys
    are in `~/keystores/krypta/` on the author's Mac. The node builds with **Go 1.22**
    (`GOTOOLCHAIN=local`). The Mac and Windows home nodes were switched off around 8 Sep 2026.
- **Monitoring**: `infra/node/check-nodes.sh` under launchd every 15 min on the dev Mac (log
  `/tmp/krypta-check.log`); the Mac must not sleep (`pmset -g` → `sleep 0`). Only `--notify` (the
  unattended mode) may touch its state file.
- **Security contact**: `info@4000msnm.com`, PGP `A2E4 6557 81A7 6D9C 6D32  F668 6775 D859 A5A1 23A8`
  ([pgp-key.asc](pgp-key.asc)), **expires 21 Sep 2028**. `security.txt` (`infra/node/security.txt`)
  has `Expires: 2027-09-01`: renew it and re-run `deploy-caddy.sh` on both VPS before then.
- **Releases**: the AAR build is reproducible from commit `8d02875` (same commit → same bytes); tag
  `revision-externa-1` and its GitHub release hold the binaries prepared for review. `gh` lives in
  `~/.local/bin/gh` (not on `PATH`). The Play versionCode history is in
  [docs/PLAY-STORE.md](docs/PLAY-STORE.md).
- **Nyx** is downstream: base fixes flow Krypta → Nyx through `upstream/main`.

## Module structure

```
:app            Compose UI. Three ViewModels, one per screen: ChatViewModel (conversation list +
                chat), CallViewModel (call in progress), SettingsViewModel (identity, nodes,
                backup, diagnostics). KryptaApp.kt (navigation + shared components),
                ChatScreens.kt (chat screen), MessageBubble.kt (bubble + attachments).
                KryptaApplication (@HiltAndroidApp), MainActivity (singleTop for deep links),
                KryptaForegroundService, IncomingNotifier (owns every user-facing alert; attached
                from the Application), KryptaNotifications, NotificationPrivacy,
                CallActionReceiver, HeartbeatReceiver, BootReceiver, AppLock, DiskFileStore +
                FileVault, MediaCodec audio/video engines.
:core           Pure domain: interfaces (ISignalingService, MessageRepository, ContactRepository,
                MessageCipher, KeyExchange, FileStore, RatchetStore, TransactionRunner, Curve25519,
                Kem, …) + models. No Android, no DI. Everything else depends on this.
:data           Room (SQLCipher, v10): entities, DAOs, migrations, repositories, DataModule,
                DeferredOpenHelperFactory, DatabaseKey/KeystoreVault, DatabaseEncryption.
:native-bridge  Kotlin wrapper over the go-libp2p AAR (Libp2pNode) + IdentityStore (Keystore).
:p2p-signaling  ChatService (encrypt → persist → send / receive → open → persist) and its pieces:
                Outbox, FileTransfer, ReadReceipts, CapabilityNegotiator, WanLoop, ContactService,
                DiagnosticsLog, ProtocolGates. Plus Ratchet/RatchetState/RatchetSessions, Padding,
                CallService, RendezvousService, MailboxLabel, SafetyNumber, IdentityBackup,
                BackupManager, SignalingService (implements ISignalingService), DI modules.
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

- **Toolchain:** Go 1.26.4 (Homebrew; pinned by `toolchain go1.26.4` in `go.mod`), NDK
  26.1.10909125, JDK 25. `build-aar.sh` checks all three. It installs gomobile + gobind at the
  `golang.org/x/mobile` version pinned in `go.mod`, into `$TMPDIR/krypta-gomobile-<ver>-<go>`,
  because gomobile looks `gobind` up in `PATH`. `go` is not on PATH in this shell: use
  `export PATH="/usr/local/bin:$PATH"`.
- **`~/.zprofile` exports `ANDROID_NDK_HOME` pointing at NDK 25.2.** Until 14 Sep 2026 the
  script honoured it, so **every AAR up to then was built with NDK 25.2** (clang 14.0.7, visible
  in `.comment`), not 26.1 as this file claimed. The script now ignores that variable.
- **The AAR build is reproducible** (14 Sep 2026, commit `8d02875`): same commit + same tools →
  same bytes, from any folder and with an empty Go cache.
  - **Verified** by building `8d02875` from two clones in different paths, each with its own empty
    `GOCACHE` (1.2 GB each): identical AAR (`d817bae1…f4e0`) and identical files inside, on one Mac.
  - **What it took:**
    - `-trimpath`;
    - building from the fixed path `/tmp/krypta-aar`. gomobile writes a `replace` pointing at the
      module dir into `libgojni.so`, and `-trimpath` doesn't remove it; those were the last 2
      local paths;
    - injecting the commit with `-ldflags -X chat.neto.krypta/nativego.buildCommit=<sha>[-modificado]`.
      `Version()` returns it, and the hand-written `0.0.18-rdv1pass` is gone;
    - rewriting `proguard.txt` with a fixed UTC date and `zip -X`;
    - `go mod tidy -diff` instead of `go mod tidy`;
    - pinned tools.
  - **The script fails** unless every ABI has 16 KB pages, contains the commit and has no local
    paths.
  - **Gotcha:** those checks first used `llvm-strings | grep -q` under `pipefail`. `grep -q` exits
    early, the writer dies of SIGPIPE, and the pipeline fails *even on a match*, while the "no
    local paths" check could pass falsely. They grep a file now.
  - **Limits:** not tested across host OSes (the NDK ships different binaries for macOS and
    Linux). Building a *different* commit changes the embedded string, so compare against the hash
    for that exact commit.
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
  `zipalign -c -P 16 -v 4 app.apk`; the script now checks the four ABIs itself. NDK r27+ would
  default to 16 KB; 26.1 is the pinned NDK, enforced by the script since 14 Sep 2026. Before that
  the AAR was actually built with 25.2 (see the toolchain bullet).
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
- **Room migrations, not destructive.** `KryptaDatabase` is at **v10** with real migrations
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
- **No raw NUL bytes in source files.** Write NUL separators as `Char(0)` / `0.toByte()`. When an
  escape sequence has to be written by a tool, build it with a script: typed into an edit it can land
  as the byte itself.
  - A NUL beyond the first 8000 bytes compiles fine and git does not flag it, but `grep` treats the
    file as binary and silently finds nothing.
  - It happened on 15 Sep 2026.
  - Check with a scan of tracked `.kt/.go/.md` files for byte 0.
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

**Before every commit and push** (the author's standing instruction, 14 Sep 2026): check that
`README.md`, this file and the `docs/` touched by the change are current. That includes the README's
status note and its documentation table whenever a document is added or the security stance
changes. Commits go to `main`: Nyx's upstream check reads `upstream/main`.

**Play Store readiness:** the launch checklist (what's done, what's a store-listing chore,
what's an infra risk) lives in [docs/PLAY-STORE.md](docs/PLAY-STORE.md) — update it as items
close.

**Protocol spec:** [docs/ESPECIFICACION-protocolo.md](docs/ESPECIFICACION-protocolo.md) is
normative and **must change in the same commit as the code** (if they disagree, the code wins and
the spec is wrong). Its English translation,
[docs/ESPECIFICACION-protocolo.en.md](docs/ESPECIFICACION-protocolo.en.md), mirrors every change in
the same commit. Properties are P1–P16, weaknesses W-1–W-16.

**Privacy/trust roadmap:** [docs/PLAN-privacidad-y-confianza.md](docs/PLAN-privacidad-y-confianza.md)
orders what is still missing for Krypta's bet to be true and checkable (metadata, review,
post-quantum, transparency, identity rotation). Designs written but deliberately not built:
[DISENO-postcuantico.md](docs/DISENO-postcuantico.md),
[DISENO-rotacion-identidad.md](docs/DISENO-rotacion-identidad.md). Reviews and audits so far:
[REVISION-protocolo-2026-09-14.md](docs/REVISION-protocolo-2026-09-14.md) (H-0…H-7),
[AUDITORIA-interna-2026-10-11.md](docs/AUDITORIA-interna-2026-10-11.md) (crypto design, A-01…A-09),
[AUDITORIA-2026-10-12.md](docs/AUDITORIA-2026-10-12.md) (architecture and code, O-1…O-11).

**History:** [docs/HISTORIAL.md](docs/HISTORIAL.md) is append-only narrative. When a change is worth
a story (a live failure, a reversed decision, a non-obvious why), add it there with its date, and
distil whatever rule it teaches into "Rules learned the hard way" above. Keep this file about the
present.

**Audit:** an architecture/code audit against the plan's objectives (7 Sep 2026) lives in
[docs/AUDITORIA-2026-09-07.md](docs/AUDITORIA-2026-09-07.md) — findings A-1…A-14 with a
prioritized action plan; update it (or supersede it with a newer one) as items close. Its
successor, [docs/AUDITORIA-2026-10-12.md](docs/AUDITORIA-2026-10-12.md) (internal, 12 Oct 2026,
on `0c054eb`), has findings O-1…O-11, all worked the same day; its §9 says what was done and
what is still owed (two-phone tests, ViewModel tests, the background-delivery decision). The two
worst were confirmed with throwaway probes before fixing: **O-1**, a ratchet envelope whose
persistence failed was acked to the mailbox anyway, and **O-2**, the read receipt carried every
incoming id in the history (74 KB for 2,000 messages, re-sent after every restart).

**Live-test tracking:** features that are implemented + unit/probe-tested but not yet
confirmed with two real phones go in [docs/PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md)
(the collaborator's second phone isn't always available). Add the exact steps + expected
result there instead of blocking on the live test; move items to its "ya verificado" section
once confirmed. **Deploy routine:** keep the author's phone on the latest build
(`:app:installDebug`) and refresh `~/Desktop/krypta-arm64-debug.apk`
(`:app:assembleDebug -PslimAbi` + copy) after any code change, so the author can share it.
