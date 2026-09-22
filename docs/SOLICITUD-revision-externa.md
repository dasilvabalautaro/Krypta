# Solicitud de revisión externa

**Estado:** preparada el 14 de septiembre de 2026. **Enviada el 22 sep 2026** por el formulario del
OTF Security Lab; en estado **OTF Review**.

**Qué se somete a revisión:** el commit con la etiqueta
[`revision-externa-1`](https://github.com/dasilvabalautaro/Krypta/tree/revision-externa-1).

**Qué es este documento:** **lo que hay que pedir, exactamente**, con los textos listos para
copiar. Van en inglés, que es el idioma del OTF y de las empresas de auditoría, y cada bloque lleva
delante un resumen en español. El porqué y las reglas mientras dura están en
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §5. Aquí solo va lo que se
envía.

---

## 0. Qué se pide, en una frase

Una **revisión del diseño criptográfico** del protocolo de sesión de Krypta —el ratchet por épocas,
la negociación de capacidades, las claves de llamada y las etiquetas del buzón— y una **revisión del
código del camino criptográfico** (Kotlin y Go), con un **informe que se pueda publicar**.

> *A cryptographic design review of Krypta's session protocol (epoch-based double ratchet,
> capability negotiation, call keys and blind-mailbox labels) and a code review of the
> cryptographic path (Kotlin and Go), with a publishable report.*

---

## 1. Vías

### 1.1 OTF Security Lab (primera opción)

Lo que se ha comprobado el 14 sep 2026 en la
[guía oficial del Security Lab](https://docs.opentech.fund/otf-application-guidebook/our-labs/security-lab):

- Ofrece, entre otras cosas, *«early-stage security assessments of their technical design,
  cryptographic design reviews, and code reviews»*: exactamente lo que se pide aquí.
- *«Projects that are not receiving OTF support but are otherwise relevant to internet freedom may
  apply for an audit»*: Krypta no necesita tener financiación del OTF.
- *«Audit findings are made publicly available»* tras un periodo de divulgación responsable para
  corregir lo encontrado. Encaja con lo que Krypta quiere, que es un informe público.
- Se solicita con un **formulario en línea** en
  [apply.opentech.fund/security-lab](https://apply.opentech.fund/security-lab/). Hay además un
  contacto: **security_lab@opentech.fund**.

**Lo que no se ha podido comprobar del todo**: los campos concretos del formulario (los que ya
se han visto, con su número, están en §3.4) y los plazos de
respuesta. Las páginas del formulario y de opentech.fund devuelven 403 a una lectura automática. Por
eso el §3 da las respuestas **agrupadas por los temas que suelen preguntar estos formularios**:
se copian en el campo que corresponda y, si un campo tiene límite de caracteres, se usa la versión
corta (§3.1).

Un dato de contexto que conviene tener presente al presentarlo: el OTF se financia con fondos del
Gobierno de EE. UU. (a través de la USAGM). La auditoría la hace un proveedor independiente y el
informe es público, así que no toca ni datos ni infraestructura de Krypta.

### 1.2 Revisión pagada (alternativa o complemento)

El texto del §4 sirve para pedir presupuesto. Conviene pedir **dos cifras por separado**: solo el
alcance A (diseño) y A + B (diseño y código).

Algunas empresas con práctica de revisión criptográfica, **sin contacto previo**: NCC Group
(Cryptography Services), Trail of Bits, Cure53, Radically Open Security, Least Authority y
Quarkslab. Para un modelo formal, Cryspen.

---

## 2. Antes de enviar

- [x] **Commit etiquetado** `revision-externa-1` publicado en GitHub.
- [x] **Binarios de la etiqueta compilados**: el AAR y el APK de depuración arm64, desde un clon
  limpio de `revision-externa-1`, con su sha256 (§5, «Binaries built from the tag»). Son los mismos
  que lleva el móvil del autor.
- [x] **Release publicada** el 14 sep 2026:
  [`revision-externa-1`](https://github.com/dasilvabalautaro/Krypta/releases/tag/revision-externa-1),
  marcada como *prerelease*, con los dos ficheros y `SHA256SUMS.txt`. Los sha256 que calcula GitHub
  para cada fichero coinciden con los de §5.
- [x] **Canal cifrado para recibir los hallazgos** (22 sep 2026): clave PGP de
  `info@4000msnm.com` (Ed25519 + subclave cv25519, caduca el 21 sep 2028), huella
  `A2E4 6557 81A7 6D9C 6D32  F668 6775 D859 A5A1 23A8`. La pública está en [`pgp-key.asc`](../pgp-key.asc) y la huella en
  [SECURITY.md](../SECURITY.md) y en `security.txt` (campo `Encryption`). La privada y el
  certificado de revocación están en `~/keystores/krypta/krypta-security-pgp-*`. Falta una copia
  fuera de la Mac, y renovar la caducidad antes de sep 2028.
- [ ] **Disponibilidad**: cuántas horas por semana hay para responder dudas durante la revisión.
  La zona horaria es la de Bolivia (UTC−4).
- [ ] **Alcance del nodo**: se recomienda **no** incluir `infra/node` en la primera ronda, salvo
  `mailbox.go` si el proveedor lo acepta como opcional (§5).
- [x] **Después de enviar**: anotada la fecha y la vía en
  [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §5.1 (22 sep 2026, OTF Security
  Lab, estado OTF Review). Falta el identificador de la solicitud.

---

## 3. Para el OTF Security Lab

### 3.1 Descripción corta

*Para un campo con límite de caracteres (≈ 600).*

> Krypta is an open-source, end-to-end encrypted Android messenger with no accounts, no phone
> numbers and no central server. Identity is a key pair generated on the device; phones find each
> other over libp2p and use an encrypted store-and-forward mailbox, without Google services. Its
> session cryptography is custom (an epoch-based double ratchet, needed because there is no prekey
> server) and has not been independently reviewed. We request a cryptographic design review and a
> code review of the cryptographic path, with a public report.

### 3.2 Respuestas por tema

**Nombre, enlaces y licencia**

> **Krypta** — https://github.com/dasilvabalautaro/Krypta (public since 12 Sep 2026).
> Commit to review: tag `revision-externa-1`. License: MIT or Apache-2.0, at the user's option.
> Contact: info@4000msnm.com.

**Qué es**

> Krypta is an end-to-end encrypted messenger for Android (text, photos, files, voice notes, voice
> and video calls). There are no accounts and no phone numbers: a user's identity is an Ed25519 key
> pair generated on the device, and the public key (the libp2p PeerID) is their address. Two
> contacts derive a shared secret by X25519 from their identities, meet at a rendezvous point in a
> Kademlia DHT that rotates daily, and connect directly or through a Circuit Relay v2 node. When the
> recipient is offline, messages are left in an encrypted mailbox on an infrastructure node under
> opaque labels, with no sender or recipient stored on disk, and a lightweight wake stream replaces
> push notifications, so it works without Google Play Services. On the device, the database,
> attachments and identity are encrypted with Android Keystore-held keys.

**Por qué es relevante para la libertad en internet**

> Krypta is built for people who cannot, or do not want to, tie their communications to a phone
> number, an account or a company's server. There is no user directory to seize or subpoena, and no
> account to block. It does not depend on Google services, so it works on de-Googled phones. Anyone
> can run their own infrastructure node and use it instead of, or alongside, the default ones. It
> also offers a WebSocket-over-443 path for networks that only allow HTTPS. It is developed in
> Bolivia with a Spanish-language interface. We are explicit about its limits: the infrastructure
> nodes see metadata, and this is documented in the project's threat model. They see users' IP
> addresses and who is online. They also learn which pairs of users talk to each other, even when a
> conversation goes directly between phones: both ends publish the same rendezvous key on the DHT.
> Today both default nodes have the same operator.

**Usuarios y fase**

> Beta, not yet published in app stores. It is used by the author and a small group of testers.
> We do not claim a user base; the review is requested before wider distribution, precisely so that
> it does not reach more people with unreviewed cryptography.

**Equipo**

> Krypta is developed by 4000MSNM S.R.L. (Bolivia), with one developer writing the code. The
> company also operates the two default infrastructure nodes (VPS in São Paulo and Dallas).

**Qué hay que revisar**

> Two things, in this order of priority:
>
> **A. Cryptographic design review** of the session protocol: the epoch-based double ratchet (an
> epoch is the pair of live ephemeral X25519 keys, advanced as soon as both are known, with no
> initiator/responder, because there is no prekey server), lineage-based recovery from state loss,
> padding, in-band capability negotiation and downgrade resistance, negotiated per-call keys, and
> mailbox labels and rendezvous keys derived from the shared secret.
>
> **B. Code review of the cryptographic path**, about 5,000 lines of Kotlin and the relevant
> functions of a Go bridge.
>
> Details, file list, known issues and specific questions are in the attached scope (§5).

**Por qué ahora**

> The ratchet was enabled on 10 Sep 2026. On 14 Sep 2026 an internal review against the project's
> own design documents found two serious bugs, both reproduced with tests before being fixed: a race
> in which two concurrent operations on the same conversation reused the same AES-GCM key and nonce,
> and a case in which deleting and re-adding a contact (or restoring a backup) made that contact's
> messages silently undecryptable forever. That a careful reading found this in a protocol that
> already had property-based tests and fuzzing is the strongest reason to ask for an independent
> review. Post-quantum hybridisation and identity rotation are designed but on hold until the
> review.

**Material**

> All at tag `revision-externa-1`:
>
> - Normative protocol specification, with numbered properties P1–P15, declared weaknesses
>   W-1–W-14 (P15 and W-14 added after the tag) and questions for the reviewer:
>   `docs/ESPECIFICACION-protocolo.md`
> - Internal review and its findings: `docs/REVISION-protocolo-2026-09-14.md`
> - Design rationale: `docs/DISENO-ratchet.md`, `docs/DISENO-buzon-ciego.md`,
>   `docs/DISENO-postcuantico.md`, `docs/DISENO-rotacion-identidad.md`
> - Threat model: `docs/security-model.md`
> - Disclosure policy: `SECURITY.md`
>
> The documents are in Spanish. The specification is written to be read with the code side by
> side, and we can translate any section on request.

**Publicación**

> We want the report to be public. We agree to publication after the responsible disclosure
> period, and we will publish it in the repository.

**Plazos y disponibilidad**

> No hard deadline. The wire format is frozen while the review is pending. The developer is
> available to answer questions (time zone UTC−4, English or Spanish).

### 3.3 Correo a security_lab@opentech.fund

*Para usar si el formulario no está disponible, o como seguimiento si en unas semanas no hay
respuesta.*

> **Subject:** Security Lab audit request — Krypta (E2EE serverless messenger, custom ratchet)
>
> Hello,
>
> I would like to request a Security Lab audit for Krypta, an open-source, end-to-end encrypted
> Android messenger with no accounts, no phone numbers and no central server
> (https://github.com/dasilvabalautaro/Krypta, MIT/Apache-2.0).
>
> Its session cryptography is custom: an epoch-based double ratchet, needed because the design has
> no prekey server. It has not been independently reviewed. An internal review on 14 Sep 2026 found
> and fixed a key/nonce reuse race and a permanent message-loss bug, which is why I believe an
> independent cryptographic design review and a code review of the cryptographic path are needed
> before wider distribution.
>
> The commit to review is tagged `revision-externa-1`. It includes a normative specification with
> numbered properties, declared weaknesses and specific questions (`docs/ESPECIFICACION-protocolo.md`),
> plus the internal review and the design documents. I agree to the report being made public after
> the disclosure period.
>
> [Enviado también por el formulario el DD/MM/AAAA. — borrar si no aplica]
>
> Thank you,
> [nombre]
> Krypta — info@4000msnm.com

### 3.4 Preguntas numeradas del formulario

*Las que ya se han visto en el formulario, con su número. Se añaden aquí a medida que aparecen.*

**13. What problems are you hoping to solve with this engagement - for users, your organization,
or the internet freedom community?**

> **The problem for users.** Most secure messengers tie a person to a phone number, and in many
> countries, Bolivia included, a SIM card is registered to a national ID. They also depend on a
> central server that can be blocked, seized or compelled, and often on Google Play Services.
> Krypta removes all three. There are no accounts and no phone numbers: identity is a key pair
> generated on the device. There is no central server and no user directory to hand over. Offline
> delivery uses an encrypted mailbox under opaque labels, and it works on de-Googled phones and on
> networks that only allow HTTPS. Anyone can run their own infrastructure node.
>
> This design removes the server that other messengers rely on, and that has a cost. Without a
> server to distribute prekeys, Krypta cannot use the Signal protocol as it is. Its session
> cryptography is custom: an epoch-based double ratchet with in-band capability negotiation,
> negotiated call keys and mailbox labels derived from a shared secret. It has not been
> independently reviewed. For the people Krypta is meant for (journalists, activists and anyone
> who cannot safely link their communications to their legal identity), flawed cryptography is
> worse than none. They change what they say and whom they talk to because they believe they are
> protected. Our own internal review on 14 Sep 2026 showed the risk is real. In a protocol that
> already had property-based tests and fuzzing, it found a race that reused the same AES-GCM key
> and nonce, and a bug that silently made a contact's messages undecryptable forever. Both are
> fixed. We do not know what a careful reading by someone else would find, and that is the problem
> this engagement solves. We want the protocol verified before Krypta reaches more people, not
> after.
>
> **The problem for our organization.** Krypta is developed by 4000MSNM S.R.L., a small company in
> Bolivia, with a single developer writing the code, and it cannot pay for a cryptographic audit.
> We have frozen the wire format while the review is pending, and further protocol work
> (post-quantum hybridisation and identity rotation) is designed but on hold until then. We will
> not build on a foundation nobody else has checked. A public report would tell us what to fix and
> let users judge our claims against independent evidence rather than our own word.
>
> **The problem for the internet freedom community.** Serverless, account-less messengers are an
> important design space, and every project in it faces the same question: how to get forward
> secrecy and recover from lost state without a prekey server. We have written a normative
> specification with numbered security properties, declared weaknesses and open questions. A
> published review of it would be useful to other projects facing the same constraints, whatever
> it finds. It would also support a tool built in and for Latin America, with a Spanish-language
> interface.
>
> We are explicit about the limits. The review would not solve metadata exposure: infrastructure
> nodes can learn who talks to whom, and our threat model documents this. It would establish
> whether the cryptography protecting message content does what we claim.

**16. Please provide links to any helpful documentation or repositories**

*Los documentos enlazan a `main` (tienen las correcciones posteriores a la etiqueta); el código, a
la etiqueta. Comprobados el 22 sep 2026: todos responden 200.*

> **Repository** (public, MIT OR Apache-2.0): https://github.com/dasilvabalautaro/Krypta
>
> **Commit to review:** tag `revision-externa-1`. The tag never moves.
> https://github.com/dasilvabalautaro/Krypta/tree/revision-externa-1
>
> **Binaries built from that tag** (AAR and arm64 debug APK, with `SHA256SUMS.txt`):
> https://github.com/dasilvabalautaro/Krypta/releases/tag/revision-externa-1
>
> **Protocol and security documentation.** The links point to `main`, which has the latest
> corrections, including properties and weaknesses added after the tag. The documents are in
> Spanish, and we can translate any section on request.
>
> - Normative protocol specification (bytes, derivations, numbered properties P1–P15, declared
>   weaknesses W-1–W-14, questions for the reviewer):
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/ESPECIFICACION-protocolo.md
> - Threat model (what is protected, what the infrastructure nodes learn, what Krypta does not
>   protect): https://github.com/dasilvabalautaro/Krypta/blob/main/docs/security-model.md
> - Internal protocol review (findings, each reproduced by a test before its fix):
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/REVISION-protocolo-2026-09-14.md
> - Ratchet design rationale:
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/DISENO-ratchet.md
> - Blind mailbox design (metadata at the store-and-forward node):
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/DISENO-buzon-ciego.md
> - Post-quantum design (measured, on hold until the review):
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/DISENO-postcuantico.md
> - Identity rotation design (on hold until the review):
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/DISENO-rotacion-identidad.md
> - Architecture overview:
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/architecture.md
> - Privacy and trust roadmap:
>   https://github.com/dasilvabalautaro/Krypta/blob/main/docs/PLAN-privacidad-y-confianza.md
>
> **Disclosure policy:** https://github.com/dasilvabalautaro/Krypta/blob/main/SECURITY.md. The same
> contact is also published at https://krypta-sp.neto.chat/.well-known/security.txt
>
> **PGP key for sending findings** (fingerprint
> `A2E4 6557 81A7 6D9C 6D32  F668 6775 D859 A5A1 23A8`):
> https://github.com/dasilvabalautaro/Krypta/blob/main/pgp-key.asc
>
> **Privacy policy:**
> https://github.com/dasilvabalautaro/Krypta/blob/main/docs/politica-privacidad.html

---

## 4. Para pedir presupuesto a una empresa

> **Subject:** Quote request — cryptographic design and code review (Krypta, open-source messenger)
>
> Hello,
>
> I am looking for a quote for an independent review of Krypta, an open-source, end-to-end
> encrypted Android messenger with no accounts and no central server
> (https://github.com/dasilvabalautaro/Krypta, MIT/Apache-2.0, commit tagged
> `revision-externa-1`).
>
> Please quote two options separately:
>
> - **Option 1 — design review only (scope A):** the custom session protocol, an epoch-based double
>   ratchet with lineage recovery, capability negotiation, padding and negotiated call keys.
> - **Option 2 — design and code review (scopes A + B):** option 1 plus the cryptographic code path,
>   about 5,000 lines of Kotlin and selected functions of a Go bridge (about 1,700 lines in the
>   files involved).
>
> An optional item: a symbolic model of the ratchet core in Tamarin (or ProVerif). Please price it
> separately if you offer it.
>
> The attached scope describes the components, known issues that should not be re-reported, and the
> questions we most want answered. We expect a written report with severity ratings and
> reproduction steps, a retest of fixes, and permission to publish the report after fixes (or 90
> days).
>
> Useful context: this is a single-developer project of a small company with a limited budget, so please indicate the
> estimated effort (person-days) for each option and your earliest start date.
>
> Thank you,
> [nombre]
> Krypta — info@4000msnm.com

---

## 5. Alcance técnico

*Para adjuntar a la solicitud del OTF o al correo del §4.*

> ### Krypta — scope for an independent review (tag `revision-externa-1`)
>
> **Background.** Two contacts share `S = X25519(identity_A, PeerID_B)`, a pure function of two
> long-lived Ed25519 identities (the PeerID embeds the public key). There is no prekey server and no
> online key agreement. The normative description of everything below is
> `docs/ESPECIFICACION-protocolo.md` (Spanish; section numbers are cited as §).
>
> #### Scope A — Protocol design review
>
> 1. **Epoch ratchet (§5).** Epoch 0 derived from `S` and a lineage (unix millis). Epochs ≥ 1 use
>    `RK(e) = HKDF(X25519(priv, pub), RK(e-1))`. An epoch is the pair of live ephemeral keys and
>    advances per received message; there are symmetric HMAC chains, derived nonces, skipped keys,
>    retired chains and an 86-byte authenticated header. Does it keep forward secrecy and
>    post-compromise security?
> 2. **Lineage rule (§5.7).** A strictly greater lineage is adopted, a lower one never is. This
>    gives automatic recovery from state loss, but an `S` holder can hijack a session (known issue
>    W-4, pinned in a test). Is there a rule that keeps recovery without the hijack? The same rule
>    decides W-6: after losing state with the clock behind the current lineage, one direction stays
>    broken and does not recover on its own (pinned in a test added after the tag).
> 3. **Replay (§5.9).** Epoch 0 is re-derivable, so replay protection depends on a deduplication
>    table (8 days ∪ newest 500). Is that acceptable?
> 4. **Capability negotiation and downgrade (§7).** An in-band `V` envelope, a per-contact version
>    that never decreases, inference from authenticated v2 traffic, re-announcement over the static
>    key when a peer lost state, and v1 (static key) still accepted on receive. Can a network
>    attacker, or an `S` holder, force a downgrade?
> 5. **Padding (§6)**: bands, and a flag bit carried in the AAD.
> 6. **Calls (§8)**: a negotiated per-call key `HKDF(k_caller ‖ k_callee, salt = S)`; media frames
>    are AES-GCM with random nonces and no counter (W-8). Replay, reordering and reflection by a relay
>    are currently stopped by libp2p's transport security (TLS 1.3 or Noise) running end to end
>    through the circuit, which Go tests added after the tag check (see the note on H-7 below). Is a
>    per-frame counter with per-direction keys worth it?
> 7. **Derived identifiers (§9, §10)**: mailbox labels and rendezvous keys from `S`, and sender
>    attribution by label (the KCI concern, W-3).
> 8. **Key reuse (W-12)**: the Ed25519 seed is used both for signing (libp2p Noise) and, converted,
>    for X25519.
> 9. *(If time allows)* the designs on hold: a slow post-quantum ratchet
>    (`docs/DISENO-postcuantico.md`) and identity rotation (`docs/DISENO-rotacion-identidad.md`).
>
> #### Scope B — Code review of the cryptographic path
>
> | Component | Files | Lines |
> |---|---|---|
> | Ratchet core | `p2p-signaling/.../Ratchet.kt`, `RatchetState.kt` | 649 |
> | Persistence, per-conversation lock, dedup | `RatchetSessions.kt`, `data/.../RoomRatchetStore.kt`, `RatchetDao.kt` | 281 |
> | Envelope, padding, v1 cipher, HKDF | `MessageEnvelope.kt`, `Padding.kt`, `AesGcmMessageCipher.kt`, `Hkdf.kt` | 438 |
> | Protocol wiring | `ChatService.kt` (`seal`, `sealAndPersist`, `onReceived`, `openRatchet`, `openLegacy`, `persistEnvelope`, `onHello`, `learnFromRatchet`, `rehook`, `announceCapabilities`, `outboxLabel`) | 1,761 (file) |
> | Contacts: version never decreases | `data/.../ContactDao.kt`, `RoomContactRepository.kt` | 131 |
> | Calls | `CallService.kt` (key derivation, hello validation, frame encryption) | 627 (file) |
> | Labels, rendezvous, safety number, backup | `MailboxLabel.kt`, `RendezvousService.kt`, `SafetyNumber.kt`, `IdentityBackup.kt`, `BackupManager.kt` | 390 |
> | At-rest keys | `native-bridge/.../IdentityStore.kt`, `KeystoreKeyWrapper.kt`, `data/.../DatabaseKey.kt`, `DatabaseEncryption.kt`, `SqlCipher.kt`, `app/.../FileVault.kt` | 577 |
> | Curve bindings | `BridgeCurve25519.kt`, `BridgeKem.kt`, `Libp2pKeyExchange.kt` | 124 |
> | **Kotlin total** | 27 files | **≈ 4,980** |
> | Go bridge | `native-bridge/libp2p/bridge.go` (`SharedSecretFor`, `RatchetKeyPair`, `RatchetAgree`, bounded stream reads, mailbox client), `gater.go` (inbound connection filter), `kem.go` (ML-KEM-768, not yet used by the protocol) | 1,461 + 130 + 78 (files) |
> | *Optional* — node mailbox | `infra/node/mailbox.go` (blind deposits, v1 sender authentication, quotas, rate limits) | 845 |
>
> **Out of scope:** the UI and notifications; libp2p, Noise and SQLCipher themselves; node operations;
> any load, denial-of-service or intrusive testing against the public nodes
> (`krypta-sp.neto.chat`, `krypta-dal.neto.chat`) — run a local node from `infra/node` instead.
>
> **Known issues, please do not re-report** (spec §13 and internal review): no forward secrecy in
> epoch 0 (W-1); replay of epoch-0 envelopes beyond the deduplication window (W-2); sender
> authentication at the level of `S`, including KCI through mailbox labels (W-3 / H-5, pinned in a
> test after the tag); session
> hijack through a forged lineage (W-4 / H-4); v1 always accepted on receive (W-5); lineage
> monotonicity relying on the clock (W-6); no post-quantum protection (W-7); call frames without a
> counter (W-8); `PN` unused (W-9); labels and rendezvous derivable from `S` forever (W-10); `.krbk`
> backup protected only by a passphrase (W-11); Ed25519 seed reuse (W-12); metadata visible to the
> node operator (W-13); and, declared after the tag, a replayed call invite that rings once more
> after an app restart (W-14). **Resistance to KCI is not claimed.** A cheaper or new way to exploit
> them *is* in scope.
>
> **Already found and fixed by the internal review** (worth re-checking): H-0 (per-conversation
> exclusion; key/nonce reuse under concurrency), H-1 (capability loss after re-adding a contact or
> restoring a backup), H-2 and H-3 (the recorded peer version could decrease), H-6.
>
> **Fixed after the tag, with no wire-format change:** H-7, found by an independent dynamic check on
> 15 Sep 2026. On the v1 path a replayed call invite rang again, repeated `busy`, and added one
> "missed call" row per delivery. Invites are now handled once per `(contact, callId)`, with a
> 10-minute future bound and an idempotent missed-call row. The same check led to Go tests showing
> that tampering with call-stream bytes in transit kills the connection, and that a relay cannot read
> what crosses the circuit.
>
> **Deliverables:** a written report with severity ratings and reproduction steps; a retest of the
> fixes; permission to publish after fixes or 90 days. *Optional:* a symbolic model (Tamarin or
> ProVerif) of the ratchet core and the lineage rule, as outlined in
> `docs/REVISION-protocolo-2026-09-14.md` §4.
>
> **Logistics:** build from source (`native-bridge/libp2p/build-aar.sh`, then
> `./gradlew :app:assembleDebug`); at this tag, create `native-bridge/libs/` first, because the
> script fails on a fresh clone (fixed in the next commit). JVM tests with
> `./gradlew testDebugUnitTest` (279 tests); Go tests in `native-bridge/libp2p` and `infra/node`.
> Contact: info@4000msnm.com (English or Spanish, UTC−4).
>
> **Binaries built from the tag.** The GitHub release
> [`revision-externa-1`](https://github.com/dasilvabalautaro/Krypta/releases/tag/revision-externa-1)
> holds the AAR and the arm64 debug APK, built on 14 Sep 2026 from a clean clone of the tag. These
> are the binaries installed on the developer's phone.
>
> | File | sha256 |
> |---|---|
> | `krypta-p2p-revision-externa-1.aar` | `4b7dcd5130d8bb0c89b4e5bcd2661fea4cbd2e267b777303b2a5d412fb6e49b4` |
> | `krypta-arm64-debug-revision-externa-1.apk` | `a30ab852127a6cfde2bcf38d1b56ee10be3a03397d9d6bd7ff0bc76d695576ea` |
>
> Toolchain: Go 1.26.4, gomobile (`golang.org/x/mobile v0.0.0-20260611195102-4dd8f1dbf5d2`),
> **NDK 25.2.9519653 (clang 14.0.7)**, JDK 25.0.1, Gradle 9.4.1, AGP 9.2.1, Kotlin 2.2.10. An earlier
> version of this text said NDK 26.1: the `.comment` section of the libraries shows otherwise,
> because the build machine's `ANDROID_NDK_HOME` overrode the script's default.
>
> **This tag's build is not reproducible**: its native libraries embed local build paths and no
> commit identifier, so rebuilding the tag will not match these hashes byte for byte.
>
> **From commit `8d02875` on, `build-aar.sh` is reproducible.** The same commit gives the same AAR
> bytes from any folder and with an empty Go cache (verified on one Mac with two clones), and the
> commit is embedded in the library (`Bridge.version()`). If the review uses a later commit, its AAR
> can be checked by rebuilding it. The `libgojni.so` inside an APK is, byte for byte, the AAR's
> library passed through `llvm-strip --strip-unneeded` (the Android Gradle Plugin strips native
> libraries when packaging), so it can be tied to the AAR too.

---

## 6. Después de enviar

- **Anotar** la fecha, la vía y el identificador en
  [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §5.1.
- **Mientras dura, rigen las reglas de §5.4 de la revisión**: no se cambia el formato de red, y no
  se tocan el linaje, el post-cuántico ni la rotación.
- **Si el formato tiene que cambiar antes de que empiece la revisión** (por ejemplo, por lo que
  salga de PRUEBAS-PENDIENTES §16): se etiqueta `revision-externa-2` y se avisa a quien vaya a
  revisar. **La etiqueta `revision-externa-1` no se mueve nunca.**
- **Cuando llegue el informe**: cada hallazgo se reproduce con un test antes de arreglarlo, y el
  informe se publica en `docs/` cuando termine el plazo de divulgación.
