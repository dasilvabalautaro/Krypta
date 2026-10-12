# Krypta protocol specification

> **English translation** of [ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md), made on
> 11 Oct 2026 and kept in sync with it. The wire format has
> not changed since the tag `revision-externa-1`. Every change to the Spanish document is mirrored
> here in the same commit. **If the two disagree, or either disagrees with the code, the code wins.**
> The other documents it links to (design rationale, threat model, internal review) are in Spanish;
> we can translate any section on request.

**What it is.** The **normative** description of what Krypta's code on `main` does as of
**11 October 2026** (announced protocol: **v4**, §5.10; the last commit with the v3 format carries the tag `protocolo-v3`), written so that an outside reviewer can review
it without reading the 1,600 lines of `ChatService`. The `DISENO-*.md` documents explain **why** each
decision was taken; this document says **what** is done: the bytes, the derivations and the rules.
**If this document and the code disagree, the code wins and the document is wrong**: it is fixed
here, in the same change.

**Why it exists.** It is the core of the external review package requested in
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §5. Claims are **numbered**
(§12) so that a review can say "P4 does not hold", and what is already known not to hold is
**declared** (§13), so nobody spends hours rediscovering it.

---

## 0. Scope, notation and primitives

**In scope:** identity and shared secret (§2), application envelope and delivery (§3), v1 transport (§4) and v2
transport, the epoch-based ratchet (§5), padding (§6), capability negotiation (§7), calls (§8),
blind-mailbox labels (§9), rendezvous (§10), safety number and `.krbk` backup (§11).

**Out of scope** (cited only where they touch the above): libp2p and Noise, the node's logic
(mailbox, wake, relay: [security-model.md](security-model.md) §6 and §8), encryption at rest
(SQLCipher, `FileVault`, Keystore: security-model §7) and the UI.

**Notation.** `‖` is concatenation. Strings are UTF-8, and numbers that appear **inside** an `info`
string are ASCII decimal without leading zeros. Binary integers are big-endian. `∅` is the empty
string. `dir(from, to)` is `0` if PeerID `from` is lexicographically smaller than `to` (as strings),
and `1` otherwise.

| Symbol | Definition | Implementation |
|---|---|---|
| `HKDF(ikm, salt, info, L)` | HKDF-SHA256, RFC 5869. With `salt = ∅`, 32 zero bytes are used, as the RFC requires | `Hkdf.kt` (own code, 38 lines, on top of `javax.crypto.Mac`) |
| `HMAC(k, m)` | HMAC-SHA256 | JCA |
| `AEAD(k, n, p, a)` | AES-256-GCM, 96-bit nonce, 128-bit tag, AAD `a` | JCA `AES/GCM/NoPadding` |
| `X25519(priv, pub)` | RFC 7748 | Phone: `golang.org/x/crypto/curve25519` through the bridge (`Bridge.RatchetAgree`, `SharedSecretFor`), because Android has no `XDH` before API 33 and `minSdk` is 30. JVM tests: the JDK's `KeyAgreement("XDH")` (`JdkCurve25519`) |
| Ed25519 | libp2p identity | go-libp2p |
| Randomness | — | `SecureRandom` (Kotlin), `crypto/rand` (Go) |

---

## 1. Adversaries

| Id | Who | What they have and can do |
|---|---|---|
| **A1** | The network and a node operator | Sees everything in transit: ciphertext, size, time, the PeerIDs of each connection and the mailbox labels. Can delay, drop, duplicate, reorder and replay. **Holds no keys** |
| **A2** | A stranger who knows your PeerID | Can deposit in the mailbox, under your PeerID or under any label. Cannot open a connection to you: the `ConnectionGater` only admits contacts and nodes (security-model §5.1) |
| **A3** | A malicious contact | Shares `S` with you by being the other half of the pair. Whatever is claimed against A3 is what remains against someone who already talks to you |
| **A4** | Whoever obtains **one identity** at time *t* | By extracting the key, or with a `.krbk` and its passphrase. Has `S` with every contact of that identity, **forever**, because there is no revocation |
| **A5** | Whoever obtains **identity and state** at *t* | The whole device: identity, ratchet state and database |
| **A6** | A future quantum adversary | Traffic recorded today (see [DISENO-postcuantico.md](DISENO-postcuantico.md)) |

Outside the model: code running **inside** the process, or with root, while the app is alive.

---

## 2. Identity and shared secret

- **Identity**: an Ed25519 key pair generated on the device. The **PeerID** is the libp2p identity,
  which **embeds the public key**. The private key is stored wrapped by an Android Keystore AES key
  (`IdentityStore`).
- **Shared secret** with contact *C* (`Bridge.SharedSecretFor`):

  ```
  s    = SHA-512(Ed25519_seed)[0..32], clamped as in RFC 7748
         (s[0] &= 248; s[31] &= 127; s[31] |= 64)
  u_C  = Montgomery u-coordinate of the Ed25519 public key inside PeerID_C
  S    = X25519(s, u_C)          32 bytes; an all-zero result is rejected
  ```

  It is **symmetric** (`S_AB = S_BA`) and a **pure function of the two identities**: it is not
  negotiated, does not expire and is not stored on disk (it is derived when the contact is read,
  database v6). Whoever holds **either** identity can recompute it. Almost every property in §12 and
  almost every limit in §13 follows from this fact.
- There is no online key agreement and no prekeys: adding a contact means pasting their PeerID.

---

## 3. Application envelope (`MessageEnvelope`)

Travels **inside** the encryption (v1 or v2). It is line-based text separated by `0x0A`: the first
byte is the type and the second a newline.

| Type | Format | Notes |
|---|---|---|
| `T` text | `T\n<id>\n<body>` | `<id>` is chosen by the sender (a UUID) and is the local primary key on both ends |
| `I` image | `I\n<id>\n<JPEG or WebP>` | Inline, ≤ ~58 KiB |
| `R` read | `R\n<id1>\n<id2>…` | Only marks the recipient's **own** messages in **that** conversation |
| `F` file meta | `F\n<fileId>\n<chunks>\n<size>\n<mime>\n<name>` | |
| `K` file chunk | `K\n<fileId>\n<index>\n<bytes>` | 48 KiB |
| `C` call | `C\n<type>\n<callId>\n<ts>[\n<key half in hex>]` | §8. A half that is not exactly 32 bytes invalidates the signal (`Unsupported`) |
| `Y` quote | `Y\n<replyToId>\n<inner envelope>` | Wrapper; does not nest |
| `V` capability | `V\n<version>[\n<the version I have recorded for you>]` | §7. The second line exists since 14 Sep 2026 |
| `D` descriptor | `D\n<size>\n<mime>\n<path>\n<name>` | **Local**: never sent. One received is dropped (and acknowledged) |

Until 21 Sep 2026 the client **violated** the `D` rule: retrying a failed file resent its row, which
is the descriptor, and the receiver stored it as a file bubble with no file. Since then the sender
retries by sending `F` + every `K` with the same `fileId`, and the receiver drops any `D` that
arrives. No format changed.

A type `A`–`Z` unknown to this version decodes as `Unsupported` and is dropped without rendering
anything. Bytes that are not shaped like an envelope decode as `null`, and the upper layer treats
them as legacy text (messages from before envelopes existed).

### 3.1 Delivery and acknowledgement (since 12 Oct 2026)

A wire envelope (v1 or v2) travels one of two ways: **direct**, one libp2p stream per envelope, or
through the node's **mailbox**. On both, the receiver **stores before acknowledging**:

- **Mailbox**: the receiver acks only the envelopes it stored; the rest come back on the next
  fetch. This already held; since 12 Oct 2026 it also holds when storing fails **inside** the
  ratchet transaction (before, that error was treated as "does not open" and the envelope was acked:
  O-1 of [AUDITORIA-2026-10-12.md](AUDITORIA-2026-10-12.md), Spanish).
- **Direct**: `/krypta/msg/1.1.0`. The sender writes the envelope and closes its half; the receiver
  reads it (at most 1 MiB), stores it and answers **one byte**: `0x01` stored (or discarded on
  purpose: unknown or blocked sender), `0x00` not stored. The sender counts the envelope as sent
  **only** on `0x01`; on `0x00`, no answer within 20 s or a dropped stream, it deposits it in the
  mailbox. The sender offers 1.1.0 and `/krypta/msg/1.0.0` in the same negotiation: with an older
  receiver 1.0.0 is negotiated, which has no ack and counts as sent once written (as before).

Since an envelope can arrive twice (ack lost → also through the mailbox), the receiver **never
rewrites an id it already has** (`T`/`I` by their `<id>`, the rest by the mailbox id): the duplicate
is acked and is not marked unread again nor notified again. With v2, the digest of §5.9 also
discards it before decrypting.

---

## 4. v1 transport: static key

```
K1    = HKDF(S, ∅, "krypta-msg-key-v1", 32)
wire  = n ‖ AEAD(K1, n, envelope, ∅)          n = 12 random bytes
```

No header, no padding and no state. It is **sent** to contacts that have not announced v2 (§7) and
**accepted on receipt from any contact, always** (W-5). History from before database v8 is still
stored this way.

---

## 5. v2 transport: epoch-based ratchet

A double ratchet where the **epoch** is the *pair* of current ephemeral public keys, and the next
epoch is entered **as soon as both are known**, with no initiator and no responder. Why it departs
from Signal's double ratchet is in [DISENO-ratchet.md](DISENO-ratchet.md) §2.

### 5.1 Per-conversation state (`RatchetState`)

| Field | Meaning |
|---|---|
| `lineage` | *L*: unix millis of when the session was created |
| `epoch` | *e* ≥ 0 |
| `sendDir` | `dir(me, contact)` |
| `rootKey` | `RK(e)` |
| `sendChain`, `sendN` | Sending chain key and number of the next message |
| `sendPN` | Messages sent in the previous epoch |
| `recvChain`, `recvN` | Receiving chain key and next expected number |
| `myCurPub` | My public key for the current epoch (32 zero bytes in epoch 0) |
| `nextPriv`, `nextPub` | My proposal for epoch `e+1` |
| `peerNextPub` | The other side's proposal for `e+1`, if it has arrived |
| `past` | Up to 3 retired receiving chains: `(L, e, chain key, n)` |
| `skipped` | Up to 2,000 skipped message keys: `(L, e, N, mk)` |
| `nextV4`, `epochV4`, `peerNextV4` | v4 (§5.10): my proposal is v4; the current epoch uses v4 derivations; the other side's proposal is v4 |
| `recvBound` | v4, epoch 0: the other side's proposal that `recvChain` is bound to (null = unbound). Each chain in `past` carries its own too |

It is serialised (`RatchetState.encode`, format 2 since v4; format 1 is still read) and stored as an opaque blob in the encrypted
database. `nextPriv` is inside it; what is **not** inside is `S`, which is derived when needed.

### 5.2 Derivations

```
RK(L, 0)   = HKDF(S,                   ∅,        "krypta-rtc-root:0:" ‖ L, 32)
RK(e)      = HKDF(X25519(priv_e, pub_e), RK(e−1), "krypta-rtc-root:" ‖ e,   32)    e ≥ 1
CK(e, d)   = HKDF(RK(e),               ∅,        "krypta-rtc-chain:" ‖ e ‖ ":" ‖ d, 32)
mk_N       = HMAC(CK_N, 0x01)          CK_{N+1} = HMAC(CK_N, 0x02)
k ‖ nonce  = HKDF(mk_N, ∅, "krypta-rtc-msg", 44)                (32 + 12 bytes)
```

The lineage only enters `RK(L, 0)`; later epochs inherit it because the root chains. The nonce is
**derived**, not drawn at random: correctness rests entirely on no `mk` ever being used twice (P3).

### 5.3 Header and wire envelope

```
0        version = 0x02
1        flags        bit 0 = padding (§6); bit 1 = next_pub is a v4 proposal; bit 2 = this
                      envelope's epoch uses v4 derivations (§5.10); the others reserved and
                      ignored on receipt
2..9     L            u64 (read as a signed Long; its value is not validated, see W-4)
10..13   e            u32 (read as a signed Int; negative = header rejected)
14..17   N            same
18..21   PN           same; transmitted but not used by the receiver (W-9)
22..53   cur_pub      my public key for epoch e
54..85   next_pub     my proposal for e+1
86..     AEAD(k, nonce, p', AAD = bytes 0..85)
```

`p'` is the envelope, padded if bit 0 is set. Overhead: 86 + 16 = **102 bytes** per message. A
receiver decides whether it **looks like** v2 with `len > 86 ∧ byte[0] = 0x02` (`looksLikeRatchet`).
This is only a hint: 1 in 256 v1 ciphertexts longer than 86 bytes starts the same way, so when v2
fails, v1 is tried (§7.5).

### 5.4 Encrypt

```
encrypt(st, envelope, pad):
    st ← if st.peerNextPub ≠ null: advance(st, st.peerNextPub) else: st
    H  ← header(st.L, st.e, st.sendN, st.sendPN, st.myCurPub, st.nextPub, pad)
    mk ← HMAC(st.sendChain, 0x01)
    return ( st with sendChain = HMAC(st.sendChain, 0x02), sendN = sendN + 1 ,
             H ‖ AEAD(key(mk), nonce(mk), pad ? padded(envelope) : envelope, H) )
```

### 5.5 Advance the epoch

```
advance(st, other_pub):
    dh ← X25519(st.nextPriv, other_pub);  if dh = 0³²: fail
    e' ← st.e + 1
    RK ← HKDF(dh, st.rootKey, "krypta-rtc-root:" ‖ e', 32)
    (priv, pub) ← fresh ephemeral pair
    return st with  e = e', rootKey = RK,
                    sendChain = CK(e', sendDir), sendN = 0, sendPN = st.sendN,
                    recvChain = CK(e', 1 − sendDir), recvN = 0,
                    myCurPub = st.nextPub, nextPriv = priv, nextPub = pub, peerNextPub = null,
                    past = last 3 of (past + (L, st.e, st.recvChain, st.recvN))
```

The consumed private key (`st.nextPriv`) is not kept anywhere: that is what gives forward secrecy
from epoch 1 on. The previous **sending** chain is discarded; the **receiving** chain is retired to
`past` for whatever is still in flight.

### 5.6 Decrypt

```
decrypt(st, S, wire):                        -- pure function: if it throws, the state is unchanged
    H ← header(wire)              (unreadable → fail)
    if H.L > st.L:  st ← reset(st, S, H.L)   -- adopt the higher lineage
    if H.L = st.L and H.e = st.e + 1:  st ← advance(st, H.cur_pub)
    if H.L ≠ st.L or H.e ≠ st.e:  return openOld(st, S, H, wire)
    -- live chain
    if H.N < st.recvN:  mk ← remove from skipped (L, e, N)   (absent → fail: key already used)
    else:  if H.N − st.recvN > 1000: fail
           store in skipped the mk for st.recvN .. H.N−1;  mk ← the one for H.N
    p ← AEAD⁻¹(mk, wire, AAD = H);  if padding flag: p ← strip(p)
    st ← st with recvChain/recvN advanced and peerNextPub = H.next_pub
    return (advance if applicable(st), p)

openOld(st, S, H, wire):
    if (H.L, H.e, H.N) is in skipped: use that mk and remove it
    else  chain ← past[(H.L, H.e)]
          or, if H.e = 0, CK(RK(H.L, 0), 1 − sendDir) derived from S       -- epoch 0 of ANY lineage
          or fail
        if H.N < chain.n: fail;  if H.N − chain.n > 1000: fail
        store the intermediate keys in skipped; open; update that chain in past
    -- does not adopt the lineage, does not advance the epoch and does not record the other side's proposal
```

`reset(st, S, L')` returns to epoch 0 of lineage `L'` with a fresh ephemeral pair, retiring the
current receiving chain to `past` and keeping `skipped`.

The above is the **v3** ratchet, still used with contacts that have not announced v4. What changes
with v4 is in §5.10.

### 5.7 Lineage rules

- A session is created with `L = System.currentTimeMillis()`: on first use, when the contact is
  added, or when the stored state cannot be read.
- A **strictly higher** lineage is adopted. A **lower** one is **never** adopted (see
  DISENO-ratchet §1.8.4: adopting it would reuse `(L, e, N)` triples already spent after a
  reinstall).
- **Requirement**: the lineages an identity emits must be monotonic. Today this is guaranteed by the
  clock, not by the protocol (W-6).
- **If it does not hold** (W-6, pinned in `RatchetTest`): whoever loses their state with the clock
  behind the pair's current lineage creates a lower lineage. What they write arrives through
  `openOld`, but the other side does not adopt it and never leaves epoch 0; what the other side
  writes, in an epoch > 0 of the current lineage, cannot be opened and is lost. The lineage is fixed
  when the session is created, so **fixing the clock does not help**: what helps is a new lineage
  from the other side (deleting and re-adding the contact).
- Recovery: since epoch 0 of any lineage is derivable from `S`, the side that lost its state can
  always talk again, and the other side adopts its lineage (which is higher). If the other side keeps
  writing in the old one, the side that lost its state answers with a `V` (rehook, §7.6).
- **Cap (R1, since 11 Oct 2026).** A higher lineage is adopted only if `L ≤ now + 10 min`
  (`Ratchet.LINEAGE_MAX_AHEAD_MS`), on the receiver's clock. Above that, its epoch 0 is opened
  (`openOld`) but not adopted.
- **Relay (R2), only with v4 contacts.** If an envelope that looks like v2 opens by no path and its
  header, **unauthenticated**, claims an epoch ≥ 1 of a lineage higher than ours, or of ours in a
  later epoch than ours (a sibling session with the same lineage), a new session is created with
  `L' = max(now, L_header + 1)`, if it fits under the cap, and the `V` is sent through it. At most
  once per contact every 5 min (`RatchetSessions.relay`, `ChatService.relayIfBehind`). Not done
  with a v3 contact: after a state loss, a second relay can land on the same lineage as an earlier
  one, and v3's epoch 0 would repeat key and nonce.
- **Notice (R3).** When another lineage is adopted in a session that had already left epoch 0, the
  chat stores a system row (`ChatService.SESSION_RESTART_TEXT`), one per lineage.
- Design and cost in [DISENO-ratchet.md](DISENO-ratchet.md) §1.13 (Spanish); model M2b in
  [formal/](formal/README.md).

### 5.8 Limits

`MAX_SKIP = 1000` keys per gap; `MAX_SKIPPED_KEYS = 2000` in total (oldest discarded first);
`MAX_PAST_CHAINS = 3`.

### 5.9 Persistence, exclusion and deduplication (`RatchetSessions`)

```
send(C, envelope, pad, persist):
    holding C's lock:
        (st', wire) ← encrypt(load(C), envelope, pad)
        in one transaction: persist(wire); save(C, st')
    -- the bytes go out AFTERWARDS: if sending fails, the state has already advanced and no key repeats

receive(C, wire, persist):
    holding C's lock:
        if seen(C, SHA-256(wire)): return Duplicate          -- acknowledged to the mailbox
        (st', p) ← decrypt(load(C), S, wire)                -- if it throws, nothing is saved
        in one transaction: persist(p); save(C, st'); markSeen(C, SHA-256(wire))
```

- **Per-conversation lock** (`Mutex`, since 14 Sep 2026, H-0). Without it, two operations that cross
  start from the same state and **repeat key and nonce**.
- **Atomicity**: the ratchet advance and the persistence of the message go in the same transaction.
  If the process dies in between, the mailbox redelivers and the envelope is still readable.
- **Deduplication**: SHA-256 fingerprint of the wire envelope. Kept if younger than **8 days**
  (margin over the mailbox's 7-day TTL) **or** among the conversation's **500** most recent; pruned
  once every 64 inserts. **Fingerprints of epoch-0 envelopes are never pruned** (`pinned` column,
  database v10, since 11 Oct 2026). It is a **security component**: it is the only thing preventing
  the replay of an epoch-0 envelope (W-2).
- **Unreadable state** → new session in epoch 0 with `L` = now (the conversation rehooks by
  itself). **Deleting the contact** forgets the session; **clearing the chat** does not.

### 5.10 Protocol v4: separate root and bound epoch 0 (since 11 Oct 2026)

Closes W-15 and W-16 with contacts announcing ≥ 4. The design and its rationale are in
[DISENO-ratchet.md](DISENO-ratchet.md) §1.12 (Spanish).

**v4 derivations.**

```
epoch e ≥ 1:  RK(e) ‖ CK(e,0) ‖ CK(e,1) = HKDF(dh, RK(e−1), "krypta-rtc-root4:" ‖ e, 96)
epoch 0:      RK(L, 0) as in v3
              CK(L, 0, d) = HKDF(RK(L, 0), ∅, "krypta-rtc-chain4:0:" ‖ d ‖ ":" ‖ P_d, 32)
```

`P_d` is the 32 bytes of the ephemeral proposal of whoever writes in direction `d` (their
`next_pub`, carried in every epoch-0 header). `mk`, `CK_{N+1}`, key and nonce are as in v3.

**When it is used.**

- `v4(C)` = the contact is recorded as ≥ 4 (`KDF4_MIN_PROTOCOL`). It decides two things: whether a
  new proposal is marked v4 (`nextV4`, which **never changes** afterwards) and whether a new session
  binds its epoch-0 sending chain (`epochV4`). A session created without v4 binds it on its first
  send if it has not sent anything yet.
- Header: bit 1 = `st.nextV4`; bit 2 = `st.epochV4`.
- **Advancing to `e+1`**: v4 if and only if both proposals forming the epoch are v4. Through
  `maybeAdvance`, the other side's is `peerNextV4`, recorded from bit 1 of the header that carried
  it. When entering through the header (`H.e = st.e + 1`), its bit 2 is used.
- **Receiving in epoch 0 of the current lineage**: if bit 2 is set, the other side's chain is bound
  to `H.next_pub`.
  - If `recvBound` is that proposal, it proceeds normally.
  - If nothing is bound and nothing has been received yet, it binds.
  - Otherwise it is **another session of the other side with the same lineage** (W-16 seen from
    here): it is opened by deriving that chain from N = 0, up to `MAX_SKIP`, **without touching the
    state**.

  The same applies the other way round: an envelope without bit 2 when reception is already bound.
- **`openOld` in epoch 0**: looks up in `past` the chain bound to the same proposal, or derives it
  with `H.next_pub` if bit 2 is set.
- Bits 1 and 2 are **never** set towards a contact not recorded as ≥ 4: a v3 client would ignore
  them and derive with the old KDF.

**What does not change**: the header layout (86 bytes), the lineage (W-4, W-6), `S`-level
authentication (W-3) and epoch 0 having no forward secrecy (W-1).

---

## 6. Band padding (v3)

```
padded(p)  = p ‖ 0x80 ‖ 0x00^k,   with total length = band(|p| + 1)
band(n)    = multiple of 160 if n ≤ 4096;  of 1024 if n ≤ 65536;  n if larger
strip(q)   = q without the trailing 0x00 bytes nor the 0x80 before them
             (if that byte is not 0x80: fail, the message is dropped)
```

It only exists in v2 (the bit is in the header, which is AAD). It is applied to contacts that
announce ≥ 3 (§7).

---

## 7. Capability negotiation

### 7.1 Versions and thresholds

`PROTOCOL_VERSION = 4` is **what is announced**, not the threshold for anything. Each capability has
its own:

| Capability | Contact's minimum | Constant |
|---|---|---|
| Ratchet sending (v2) | 2 | `RATCHET_MIN_PROTOCOL` |
| Negotiated call key | 2 | `RATCHET_MIN_PROTOCOL` |
| Blind deposit | 2 | `BLIND_MIN_PROTOCOL` |
| Padding | 3 | `PADDING_MIN_PROTOCOL` |
| v4 root KDF and bound epoch 0 (§5.10) | 4 | `KDF4_MIN_PROTOCOL` |

There are two global emergency switches, `RATCHET_SEND` and `BLIND_DEPOSIT`: set to `false`, they
return everything to the v1 path.

### 7.2 What is stored per contact

- `peerProtocol`: the version the contact has demonstrated or announced. **It never decreases**; it
  only returns to 0 if the contact is deleted. SQL enforces this in a single statement
  (`ContactDao.UPSERT_SQL`, `RAISE_PEER_PROTOCOL_SQL`, since 14 Sep 2026, H-2/H-3).
- `announcedProtocol`: the version we have already announced to them.

### 7.3 Sending

```
usesRatchet(C) = RATCHET_SEND ∧ S ≠ null ∧ C.peerProtocol ≥ 2
pads(C)        = usesRatchet(C) ∧ C.peerProtocol ≥ 3
seal(C, s)     = usesRatchet(C) ? RatchetSessions.send(C, s, pads(C)) : v1(C, s)
```

Every outgoing path goes through `seal`: text, photo, file (meta and chunks), read receipts, call
signals, capability announcements and retries. There is **one single exception**: the `V`
announcement of §7.6, which goes over v1 on purpose.

### 7.4 Announcing

On every WAN cycle, to every non-blocked contact with `announcedProtocol < PROTOCOL_VERSION`:
`seal(C, "V\n4\n" ‖ C.peerProtocol)`. Only if it goes out (direct or mailbox) is
`announcedProtocol = 4` recorded.

### 7.5 Receiving and choosing the version

```
onReceive(C, wire):
    if C blocked: drop (and acknowledge to the mailbox)
    if it looks like v2: try RatchetSessions.receive; if it fails → try v1 (marking "looked like v2")
    else: try v1
    if nothing opens: drop with a diagnostics line; if "looked like v2" → rehook (§7.6)
    if it opened via v2: C.peerProtocol ← max(C.peerProtocol, v4 bits ? 4 : padded ? 3 : 2)  -- what was demonstrated
```

The header of a v2 envelope that opens is authenticated (it is the AAD), so what it demonstrates
cannot be faked without `S`.

### 7.6 `V` announcement received, and rehook

```
onHello(C, version, recorded):
    if version > C.peerProtocol: C.peerProtocol ← version
    if version < C.peerProtocol: ignore (the version never decreases)
    if recorded ≠ null ∧ recorded < 4 ∧ C.announcedProtocol ≥ 4:
        launch V("4", C.peerProtocol) over v1                 -- they lost it: repeat it
    else, if C.peerProtocol crossed from < 2 to ≥ 2 ∧ usesRatchet(C):
        launch V("4", C.peerProtocol) via seal                -- so they adopt our lineage

rehook(C):             -- an envelope that looked like v2 did not open by any path
    if usesRatchet(C):             launch V via seal (carries our lineage)
    if C.peerProtocol < 2:         launch V("4", C.peerProtocol) over v1
```

Everything "launched" runs outside the receive path, which is synchronous with the mailbox
acknowledgement, and at most **once per contact every 5 minutes**. A `V` over v1 carries no user
content, but its size does reveal that it is an announcement.

---

## 8. Calls

- **Signalling**: `C` envelopes over the normal path (§7.3). On receiving an `invite`, in this order
  (`CallService.onInvite`; H-7 of the review):

  1. **Once per call.** If that `(contact, callId)` was already handled, it is ignored. The memory is
     local and in RAM, lasts `45 s + 10 min` from first sight and holds at most 256 entries: that is
     the whole interval in which one invite could ring.
  2. **Future date.** If `ts − now > 10 min`, it does not ring: it is recorded as a missed call.
  3. **Stale.** If `now − ts > 45 s`, it does not ring: it is recorded as a missed call.
  4. If there is no other call, it rings; if there is, `busy` is answered.

  `now` is the **receiver's** clock and `ts` the **sender's**, so both windows absorb the skew
  between them. The **missed-call row is idempotent**: its id is
  `hex(SHA-256("krypta-missed-call-v1" ‖ 0x00 ‖ contact_id ‖ 0x00 ‖ callId))` and, if it already
  exists, it is neither saved nor notified again. `accept`, `reject`, `busy` and `hangup` only act on
  the current call (same `callId`, which is random), so they need no memory.
- **Key** (`CallService`):

  ```
  with contacts announcing ≥ 2:  k_caller, k_callee ← 32 random bytes each
                                 (carried in the 5th line of the invite and of the accept)
                                 K_call = HKDF(k_caller ‖ k_callee, S, "krypta-call-key-v2:" ‖ callId, 32)
  with everyone else:            K_call = HKDF(S, "krypta-call-v1", callId, 32)
  ```

  The callee draws its half **when the invite arrives**, so the first media byte already uses the
  negotiated key. **No silent fallback to the static key** (since 11 Oct 2026; A-09 of the internal
  audit):

  - the caller sends its half if it has the other side recorded as ≥ 2, and only then negotiates:
    if the `accept` comes back without a half, it ends the call; if it did not send one, it uses the
    static key even if a half arrives;
  - the callee negotiates if the `invite` carries a half, and then returns its own **even if it has
    the caller recorded as legacy** (sending one already proves it);
  - an `invite` **without** a half from someone recorded as ≥ 2 does not ring: it becomes a missed
    call and our `V` announcement is repeated over v1, because the honest case is that the other
    side has us recorded as legacy.
- **Media**: libp2p streams `/krypta/call/1.0.0` (audio, `uint16` framing) and `/krypta/video/1.0.0`
  (video, `uint32` framing, 1 MiB). Each frame is `AesGcmMessageCipher.encrypt(K_call, frame)`, i.e.
  `n ‖ AEAD(HKDF(K_call, ∅, "krypta-msg-key-v1", 32), n, frame, ∅)` with a random `n`. The first frame
  of each stream is `HELLO:<callId>` (audio) or `VHELLO:<callId>` (video), encrypted, and the
  receiver validates it. **Frames carry no counter** (W-8). What prevents the network from replaying
  them, reordering them or reflecting them back to the sender is that the stream runs inside libp2p's
  transport security (TLS 1.3 or Noise) from phone to phone, also when it goes through a relay. This
  is checked by tests (Go `TestTransporteRechazaBytesManipulados` — "transport rejects tampered
  bytes", `TestRelayNoVeLoQueViajaPorElCircuito` — "relay does not see what travels through the
  circuit"), not assumed.

---

## 9. Blind-mailbox labels

```
week   = daysSinceEpoch(UTC) / 7            (integer division)
label  = HKDF(S, ∅, "krypta-mbx:" ‖ week ‖ ":" ‖ dir(sender, recipient), 32)   → hex
```

- **Deposit**: whoever writes to a contact that announces ≥ 2 deposits under the current week's
  label. Everyone else gets deposits by PeerID, and there the node sets `from` from the stream's
  identity.
- **Fetch**: each client fetches the labels of the current and the previous week, for all its
  contacts. The node stores `label → envelope, time` and sees neither `from` nor `to`.
- **Attribution**: the receiver assigns the envelope to a contact **by its label**. Since the label
  derives from `S`, **sender authentication on this path is that of `S`**, not that of the libp2p
  identity (W-3).

---

## 10. Rendezvous (discovery)

```
rdv(date) = HKDF(S, ∅, "krypta-rdv:" ‖ YYYY-MM-DD (UTC), 32)
```

Both ends announce and look up the same key in the DHT. During the 2 hours on either side of UTC
midnight, the neighbouring day's key is used as well.

---

## 11. Safety number and `.krbk` backup

- **Safety number** = the 60 least significant decimal digits of
  `SHA-256("krypta-safety-number-v1" ‖ min(PeerID_A, PeerID_B) ‖ max(…))`, in groups of 5. The QR
  code carries `krypta:verify:<own PeerID>`.
- **`.krbk`** = `"KRBK1" ‖ salt(16) ‖ nonce(12) ‖ AEAD(PBKDF2-HMAC-SHA256(passphrase, salt, 310 000, 256 bits), nonce, payload, AAD = "KRBK1")`.
  The payload is lines `v=1`, `id=<Base64 identity>`, `c=<Base64 name>|<PeerID>|<0|1 verified>`
  and `b=<blocked PeerID>`. It does **not** carry `S` (re-derived), the ratchet state or
  `peerProtocol`: on import, contacts start with no session and no version, and §7.6 resolves it.

---

## 12. Claimed properties

"Tested" means there are tests that exercise it, not that it is formally proven: **nothing in this
protocol has yet had formal analysis or external review** (see
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §4 and §5).

| Id | Property | Against | Status | Evidence |
|---|---|---|---|---|
| **P1** | Confidentiality of content | A1, A2 | Tested | `RatchetTest`, `AesGcmMessageCipherTest`, `RatchetPropertyTest` ("nothing opens as another message") |
| **P2** | Integrity of envelope and header against anyone without `S` | A1, A2 | Tested | `RatchetTest` (forged header), `RatchetPaddingTest` (padding bit), `ParserFuzzTest` |
| **P3** | No `(L, e, N)` triple repeats for one sender, and therefore no key or nonce | — | Tested, **conditional**: on no lineage ever repeating with a v3 contact (W-16; in v4 epoch 0 is bound to a fresh proposal) and on the state never rolling back to an earlier copy (requires root). If it fails, it is AES-GCM nonce reuse | `RatchetPropertyTest` (sequential), `RatchetSessionsTest` (concurrent, since 14 Sep 2026); the counterexample is in `RatchetCompromisoTest` |
| **P4** | Forward secrecy for messages of epoch ≥ 1: compromising identity and state at *t* does not open what was delivered before *t* | A5 | Claimed, **with exceptions**: epoch 0 (W-1), **the compromised device's current epoch with a contact still on v3** (W-15; closed in v4) and skipped keys and retired chains still stored at *t*. "Not kept" means not in the logical state; physical erasure (memory, free database pages) is not guaranteed | `RatchetCompromisoTest` pins W-15 on v3 and checks that v4 closes it; compromise at *t* in general is the first thing the formal model must cover |
| **P5** | Post-compromise recovery against a **passive** attacker: after a DH round it did not see, it can no longer read | A5 passive | Claimed, not tested. Requires fresh ephemeral private keys, unknown to the attacker, **on both sides** | Same |
| **P6** | Post-compromise recovery against an **active** attacker | A4, A5 active | **Does not hold** | W-3, W-4 (`RatchetTest`: "a forged lineage hijacks the session") |
| **P7** | A genuine envelope is delivered at most once **within the deduplication window** (8 days ∪ 500); outside it, epoch 0 can be replayed (W-2) | A1 | Tested | `RatchetSessionsTest`, `RatchetSeenPruneSqlTest`, `ChatServiceTest` |
| **P8** | A conversation is never permanently broken because one end loses its state, deletes and re-adds the contact, or imports a `.krbk` | — | Tested, **conditional** on W-6 | `RatchetPropertyTest` (also with clocks behind, forged lineages and relay: 80 seeds), `ChatServiceTest` (re-add, `.krbk`, relay). With v3 contacts, conditional on W-6 |
| **P9** | The network cannot downgrade to v1 a pair **whose version has already been learned**, nor can a stale copy of the contact. **The first negotiation is opportunistic**: a node that suppresses the initial `V` announcements (in v1 they stand out by size) keeps the pair on v1 until something v2 gets through | A1, A2 (and A4 via the announcement path) | Tested since 14 Sep 2026, with that scope | `ContactUpsertSqlTest`, `ChatServiceTest` |
| **P10** | The ratchet advance and the persistence of the message commit together | — | Tested | `RatchetSessionsTest`, `ChatServiceTest` |
| **P11** | With v3, control messages and short texts have the same size: whatever falls in the same band is indistinguishable by size, whatever falls in different bands is not | A1 | Tested | `PaddingTest`, `RatchetPaddingTest` |
| **P12** | A call's key is not derived from the identity alone (with contacts that negotiate). Its forward secrecy is that of the signalling carrying the two halves: if those envelopes travel over v1 or in epoch 0, whoever obtains `S` and has them recorded rebuilds the call | A4 without the signalling | Tested | `CallServiceTest` |
| **P13** | The node's disk does not link mailbox labels to PeerIDs | A1 (dump) | Tested on the node | Go `TestMailboxV2NoGuardaNiRemitenteNiDestinatario` ("stores neither sender nor recipient"), `TestMailboxV2LaEtiquetaEsLaLlave` ("the label is the key"), [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) |
| **P14** | Domain separation between all derivations from `S` | — | By inspection | The `info` labels of §4, §5.2, §8, §9 and §10, all distinct |
| **P15** | A genuine `invite` rings at most once and leaves at most one missed-call row, whoever delivers it and however many times | A1 | Tested since 15 Sep 2026, **conditional**: if the process restarts within 10 min 45 s it can ring once more (W-14), and clearing the chat deletes the row and with it its memory | `CallServiceTest` (7 H-7 tests), `ChatServiceTest` |
| **P16** | An envelope the sender counts as sent was **stored** at the receiver (direct with ack) or in the node's mailbox; if the receiver could not store it, the mailbox delivers it again | — | Tested since 12 Oct 2026, **conditional**: with a receiver that only speaks `/krypta/msg/1.0.0`, "sent" over direct still means "written" | Go `TestEntregaDirecta*`, `TestUnEmisorAnteriorSigueEntregando`; `ChatServiceTest` ("un directo que no se pudo guardar no se acusa", "por ratchet un fallo al guardar no se confirma") |

---

## 13. Known weaknesses

| Id | What | Against | Where it is treated |
|---|---|---|---|
| **W-1** | **Epoch 0** has no forward secrecy, and whether the user's first message goes out in it **depends on the clocks** | A4 | DISENO-ratchet §1.8.4 |
| **W-2** | An epoch-0 envelope **replayed** outside the deduplication window (8 days ∪ 500) is delivered again | A1 | DISENO-ratchet §1.9 |
| **W-3** | **Origin authentication is that of `S`**. Whoever holds *either* identity can inject as the other in three ways: v1, epoch 0 of a foreign lineage (opened without adopting it) and a new lineage. With blind deposit that includes **impersonating any contact to someone who lost their own key** (KCI). **KCI resistance is not promised** (decision of 15 Sep 2026, until the external review). Through the **PeerID** mailbox that variant only gets in if the node lies about the sender | A4 | H-5 of the review; pinned in `ChatServiceTest` (blind mailbox; PeerID mailbox with an honest node and with a lying node) |
| **W-4** | **Lineage hijack**: an epoch-0 envelope with a forged, very high lineage is adopted, the receiver advances with the attacker's proposal and writes to them with material the attacker knows; the legitimate end is locked out **with no way back** until the session is deleted | A4 active | H-4. **Bounded on 11 Oct 2026**: R1 rules out lineages beyond the cap (`Long.MAX_VALUE`), and with v4 contacts the relay (R2) hands the session back to the legitimate end, so the hijack lasts only while the attacker keeps intervening; R3 makes it visible. With v3 contacts, no relay. Pinned in `RatchetTest` and `ChatServiceTest` |
| **W-5** | v1 reception is always accepted, from any contact, even when the pair already uses v2 | A4 | Tied to W-3: closing only this path buys nothing |
| **W-6** | Lineage monotonicity comes from the clock. **The real effect is on availability**: whoever loses their state with the clock behind the current lineage (restoring with the wrong date set, or a current lineage created by a phone whose clock ran ahead) creates a lower lineage; what they write arrives but never leaves epoch 0, what is written to them **is lost**, and fixing the clock does not help (§5.7). Repeating keys would require creating a lineage in the same millisecond as an earlier one (that is W-16) | — | **Fixed with v4 contacts on 11 Oct 2026** by the relay (R2): the message that triggers it is lost, not the following ones (`ChatServiceTest`, `RatchetPropertyTest` with clocks behind). With v3 contacts unchanged; pinned in `RatchetTest` |
| **W-7** | No post-quantum protection: everything is X25519, and the PeerID *is* the public key | A6 | [DISENO-postcuantico.md](DISENO-postcuantico.md) |
| **W-8** | **Call frames** carry no counter or window, and the key is the same in both directions: **the application layer** cannot tell a replayed, reordered or reflected frame. **Limited impact as long as libp2p's transport security (TLS 1.3 or Noise) keeps end-to-end authenticity, ordering and anti-replay**, and this was checked (15 Sep 2026): a middlebox that duplicates, reorders or reflects bytes kills the connection without delivering anything twice, and a relay sees neither the frame nor what is negotiated inside the circuit. The tests negotiated TLS 1.3. Not checked: the direct QUIC path after DCUtR (by inspection, go-libp2p does not use 0-RTT). It gives no freshness against a compromised end nor against code that re-injects frames after the transport, and it does not replace a counter if the transport changes. And the app cannot verify it at runtime: go-libp2p leaves `ConnState().Security` empty on relayed connections | A1, if the transport changed | Question for the review (§15.5); evidence in [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §8 |
| **W-9** | `PN` is transmitted and not used; the previous epoch's keys come from the retired chain, bounded by `MAX_SKIP` | — | Question for the review |
| **W-10** | Mailbox and rendezvous labels are **derivable from `S` forever**: whoever obtains an identity can tell which labels belonged to that pair in any past dump | A4 | DISENO-ratchet §7.2 |
| **W-11** | The `.krbk` is protected only by its passphrase (PBKDF2, 310,000 iterations): whoever obtains it can try passphrases without limit, and success is A4 | A4 | security-model §7 |
| **W-12** | The same Ed25519 seed is used for signing (libp2p's Noise) and, converted, for X25519 | — | Question for the review |
| **W-13** | Metadata: the node sees the day's pair graph (DHT), presence (wake) and who talks to whom live (relay); and, through identify, which protocols each phone supports | A1 | security-model §5 and §6 |
| **W-15** | **The stored root re-opens the current epoch.** `RK(e)` is kept in the state and both chains of the epoch are derived from it starting at N = 0 (§5.2), so whoever obtains the state can re-derive the keys of **everything** sent and received in the current epoch, even though the message keys were discarded. Since the epoch advances per message received, in a back-and-forth conversation this is little; in an **unanswered burst** (a chunked file, several voice notes, texts to an offline contact) it is the whole burst. Earlier roots cannot be re-derived from the current one | A5 | Found by the internal audit of 11 Oct 2026 ([AUDITORIA-interna-2026-10-11.md](AUDITORIA-interna-2026-10-11.md), A-01, Spanish); pinned in `RatchetCompromisoTest`. **Closed with v4 contacts** (§5.10, a root KDF with independent outputs); still present on the v3 path |
| **W-16** | **Repeating a lineage repeats key and nonce in epoch 0.** Epoch 0 depends only on `S`, the lineage and the direction; the ephemeral proposals travel in the header (AAD) and do not enter the key. Two sessions with the same lineage encrypt their first message with the same key and nonce, which with AES-GCM yields the XOR of the plaintexts and breaks authenticity. Precondition: a lineage that identity already used, to the millisecond (a clock going back exactly, or restored or cloned state, which requires root). No way to force it from the network has been found | — | Internal audit of 11 Oct 2026 (A-02); pinned in `RatchetCompromisoTest`. **Closed with v4 contacts** (§5.10, epoch-0 chain bound to the sender's proposal); still present on the v3 path |
| **W-14** | If the app restarts within 10 min 45 s of an `invite`, whoever can resend it (the node, through the mailbox) makes it **ring once more**: the memory of handled invites lives in RAM. The missed-call row does not repeat, because its id is in the database | A1 | Accepted on 15 Sep 2026: persisting that memory would leave call metadata outside SQLCipher or touch the ratchet's seen table (REVISION §9.3) |

---

## 14. Code and test map

| Component | Code | Tests |
|---|---|---|
| Shared secret | `native-bridge/libp2p/bridge.go` (`SharedSecretFor`), `Libp2pKeyExchange` | Go `TestSharedSecret*` |
| Application envelope | `MessageEnvelope.kt` | `MessageEnvelopeTest`, `ParserFuzzTest` |
| v1 | `AesGcmMessageCipher.kt` | `AesGcmMessageCipherTest` |
| Ratchet | `Ratchet.kt`, `RatchetState.kt` | `RatchetTest`, `RatchetPropertyTest` (also with v4 and with the v3→v4 transition), `RatchetPaddingTest`, `RatchetCompromisoTest`, `ParserFuzzTest` |
| Ephemeral X25519 | `bridge.go` (`RatchetKeyPair`, `RatchetAgree`), `BridgeCurve25519` | Go `TestRatchetKeyPairAgreement`, `JdkCurve25519` |
| Persistence, lock, deduplication | `RatchetSessions.kt`, `RoomRatchetStore.kt`, `RatchetDao` | `RatchetSessionsTest`, `RatchetSeenPruneSqlTest` |
| Padding | `Padding.kt` | `PaddingTest`, `RatchetPaddingTest` |
| Capabilities | `ChatService` (`usesRatchet`, `pads`, `seal`, `onHello`, `learnFromRatchet`, `rehook`, `announceCapabilities`), `ContactDao` | `ChatServiceTest`, `ContactUpsertSqlTest` |
| Calls | `CallService.kt`, `ChatService.recordMissedCall` | `CallServiceTest`, `ChatServiceTest`, Go `TestCallStreamEcho`, `TestVideoStreamEcho` |
| Transport under calls (W-8) | go-libp2p: TLS 1.3 or Noise by default, also inside the relay circuit | Go `TestTransporteRechazaBytesManipulados`, `TestRelayNoVeLoQueViajaPorElCircuito`, `TestRelayMessagingLocal` |
| Blind mailbox | `MailboxLabel.kt`, `ChatService` (`outboxLabel`, `inboxLabels`), `infra/node/mailbox.go` | `MailboxLabelTest`, `ChatServiceTest`, Go `TestMailboxV2NoGuardaNiRemitenteNiDestinatario`, `TestMailboxV2LaEtiquetaEsLaLlave` |
| Delivery and ack (§3.1) | `bridge.go` (`SendMessage`, `SetMessageHandler`, `ProtocolIDAck`), `ChatService` (`setDirectProcessor`, `openRatchet`, `persistEnvelope`) | Go `TestEntregaDirecta*`, `TestUnEmisorAnteriorSigueEntregando`, `ChatServiceTest` |
| Rendezvous | `RendezvousService.kt` | `RendezvousServiceTest` |
| Safety number | `SafetyNumber.kt`, `QrCode` | `SafetyNumberTest`, `QrCodeTest` |
| `.krbk` | `IdentityBackup.kt`, `BackupManager.kt` | `IdentityBackupTest`, `ParserFuzzTest` |

Test names are mostly in Spanish, as is most of the code's commentary.

---

## 15. Specific questions for the reviewer

1. Does the **epoch-based** reformulation (§5, DISENO-ratchet §2) preserve the double ratchet's
   forward secrecy and post-compromise recovery? In particular: an epoch advances per **message
   received**, not per turn, and the root of epochs ≥ 1 does not carry the lineage in `info`.
2. **Lineage** (W-4, W-6): since 11 Oct 2026 there is a cap, a relay and a notice (§5.7). Is the
   relay's cost acceptable — it fires on an **unauthenticated** header and lets a node force resets
   to epoch 0 every 5 min, without reading anything? Is there an authenticated trigger that keeps
   recovery after state loss? And is a 10-min cap the right trade-off between the clock skew
   tolerated and how long a hijack lasts?
3. **Derivable epoch 0** (W-1, W-2): is it acceptable for replay protection to depend on a
   deduplication table with a window, or should epoch 0 of a lineage that is no longer current be
   rejected?
4. **`S`-level authentication** (W-3, W-5): is it worth signing envelopes with the identity (KCI
   resistance) at the cost of losing deniability, which Krypta does not promise? When should v1 stop
   being accepted? Today **neither is promised**: neither KCI resistance (W-3) nor deniability.
   Envelopes are not signed and are deniable in practice, but no analysis backs this.
5. **Calls** (W-8, P15): today what prevents replaying, reordering or reflecting frames is libp2p's
   transport, checked by tests. Is a per-frame counter with separate keys per direction worth adding,
   so as not to depend on it? Is the `invite` rule of §8 sufficient (once per `callId` in RAM, 10 min
   into the future, 45 s into the past, idempotent missed-call row)?
6. **Unused `PN`** (W-9) and a global `MAX_SKIPPED_KEYS = 2000` that discards the oldest: is there a
   loss or abuse that has not been seen?
7. **Capability negotiation** (§7): can a downgrade be forced by any path other than the `V`
   announcement?
8. **Reuse of the Ed25519 seed** for signing and for X25519 (W-12).
9. The **slow post-quantum ratchet** of [DISENO-postcuantico.md](DISENO-postcuantico.md) versus
   Signal's SPQR.
10. The **identity rotation** of [DISENO-rotacion-identidad.md](DISENO-rotacion-identidad.md), which
    depends on all of the above.
11. **The v4 root KDF** (W-15): `RK(e) ‖ CK(e,0) ‖ CK(e,1)` as independent outputs of a single HKDF,
    switched on by a bit in both proposals that form the epoch. Is it enough for forward secrecy
    within the epoch, and is the transition between v3 and v4 sessions safe?
12. **Epoch-0 freshness** (W-16): binding its sending chain to the sender's ephemeral proposal.
    Does it close the reuse without opening another path in `openOld` or in the rehook?
