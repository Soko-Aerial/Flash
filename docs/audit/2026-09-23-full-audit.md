# Full audit — security, bugs, low-end mode, library quality, compliance

**Date:** 2026-09-23 · **Branch:** `dev` @ `04dfaf4` + 51 uncommitted files (the 2026-09-22 hardening work)
**Scope:** whole repo. **Audit only — nothing here was implemented.**
**Method:** read the code, not the docs. Every finding cites the file it was confirmed in. Where a claim
rests on reading rather than a running exploit or a measurement, it says so.

Severity: **Critical** = exploitable by anyone on the same LAN, or silent loss of a security property ·
**High** = real user impact or a blocker for a credible release · **Medium** = should fix before 2.0
final · **Low** = hygiene.

---

## 0. Verdict

The engineering *process* is unusually well documented, but the **security model has three holes that
make the "Encrypted & Verified" promise untrue against an active attacker on the same Wi-Fi**. They are
not subtle: inbound peers are never authenticated, the pairing code can be forced to match, and TLS
silently turns itself off on failure. None of this is visible in the test suite because the suites test
each piece in isolation and all of them pass.

The **low-end mode is mostly a label**: the library facade never applies it, three of its eight knobs are
never read, and the one knob that is read most (chunk size) is likely set the wrong way for CPU-bound
phones. None of the tier numbers were measured (AGENTS.md §23).

As a **library**, it is not yet industry-grade: ~1,560 public declarations with no ABI gate, POMs with no
license/SCM metadata (so Maven Central is impossible), CI that has been red on every `dev` push, and no
third-party license notices anywhere in the app or the installers.

| Area | Grade | One line |
|---|---|---|
| Transport security | **F** | Inbound impersonation + grindable pairing code + TLS fail-open |
| Data at rest | **D** | DB is SQLCipher, but the keys that matter sit in plain SharedPreferences and ride backups |
| Bug hygiene | **C** | Cancellation swallowed systemically; main-thread socket write; red CI |
| Low-end mode | **D** | Mostly unwired / unmeasured |
| Library API & release | **D+** | Huge unguarded public surface, no POM metadata, JitPack-only |
| Licensing / store policy | **D** | No third-party notices shipped; vendored fork unmarked |
| Docs / ADR culture | **A-** | Genuinely strong — but the logs are now too big to be read (§6) |

---

## 1. Critical security findings

### S1 — Any LAN device can impersonate a paired contact on an inbound connection (Critical, confirmed in code)
**Where:** `core/network/.../ws/WsFlashNetwork.kt` inbound HELLO handling (`onTextMessage` → `parseFields(text, HELLO_PREFIX)`);
`SecureSocketUpgrader.wrapAccepted` (no `setNeedClientAuth`/`setWantClientAuth` anywhere in `core/network`);
`app/.../DiscoveryEngineHolder.kt:1788-1800` (`val plainText = if (isSecuredFrame) … else text`). Same in
`JvmWsFlashNetwork`, `Flash.kt`, `DesktopEngine.kt`.

**Chain:**
1. The TLS server never requests a client certificate, so an inbound peer is encrypted but **unauthenticated**.
2. Its identity is whatever `deviceId` it writes into `FLASH_WS_HELLO` — self-asserted, never bound to a pin.
3. Every device's `deviceId` is broadcast in mDNS/multicast TXT (`core/discovery/.../TxtCodec.kt:57`), so the
   target id is free.
4. `registerSession` then **replaces** the real peer's session ("same direction supersedes", ERROR-031).
5. The inbound dispatcher accepts **plaintext** frames even from a peer that has a stored session key — the
   E2E layer is opportunistic, not mandatory. Group frames are processed before the E2E check entirely.

**Impact:** an attacker on the same Wi-Fi can drop the real contact's session and inject chat, group, call
and transfer-offer frames that the app attributes to that trusted contact.
The ADR-040 fix from 2026-09-22 binds identity on **outbound** manual dials only; the inbound side is the
open half.

**Fix:** (a) server side: `setNeedClientAuth(true)` and evaluate the client leaf with the same
`TofuPinVerifier` after HELLO names the peer (the ADR-040 binding, mirrored for inbound); (b) once a peer has
a session key, **reject** non-`FLASH_SEC` frames of every family except pairing/HELLO; (c) put group frames
behind the same E2E rule. Prove it with a loopback test in which a second client claims an existing peer's
id and must be refused.

### S2 — The 6-digit pairing code can be forced to match (Critical, confirmed in code)
**Where:** `core/security/.../pairing/NumericComparisonCode.kt` (`code = SHA-256(sorted(fa, fb))[0..5] mod 10^6`),
called from `PairingSessionStateMachine.kt:183,214` with the two **static** identity fingerprints only.

The KDoc cites Bluetooth numeric comparison, but omits the part that makes Bluetooth safe: **fresh nonces and
a commit-then-reveal step**. Here there is no nonce and no commitment, and both inputs are known in advance (a
fingerprint is exposed in every TLS handshake). A man-in-the-middle presenting key M1 to A and M2 to B only
needs `code(fa, M1) == code(M2, fb)`; that is ~10^6 keypair generations, **seconds on a laptop, and can be
precomputed**. The "10^-6 per attempt" argument assumes the attacker cannot choose its inputs. It can.

**Fix:** Bluetooth/ZRTP-style SAS: each side generates a fresh 128-bit nonce; the responder sends
`commit = H(PKb ‖ Nb)` before seeing `Na`; codes derive from `H(PKa ‖ PKb ‖ Na ‖ Nb)`. That is a protocol
version bump, and **every existing pairing made with the current scheme should be treated as unverified.**

### S3 — TLS fails open, silently, in all three engines (Critical, confirmed in code)
**Where:** `app/.../DiscoveryEngineHolder.kt:549`, `core/engine/.../Flash.kt:242`,
`desktop/.../DesktopEngine.kt:528`: `runCatching { build TlsOptions }.onFailure { log "falling back to plain" }.getOrNull()`.

Any exception in keystore or certificate setup (ERROR-070 showed they happen on real devices) starts the node
with **no TLS at all**: a plaintext server that anyone can connect to, pairing over plaintext, and no
indication in the UI. AGENTS.md §19 forbids exactly this ("never disable TLS … permanently").
The "Encrypted" indicator keys off the *app-layer* session key (`isChannelEncrypted`), so it can still show
Encrypted on a node that has no transport security.

**Fix:** fail closed. Surface a blocking error state ("secure connection unavailable") and retry key
generation, rather than start plaintext. If a plaintext mode must exist for tests, make it an explicit
constructor flag that production code cannot reach.

### S4 — E2E session keys stored in plaintext and included in cloud/device backups (Critical, confirmed in code)
**Where:** `core/security/.../trust/AndroidPreferencesTrustStore.kt:38-50` (session keys and pins as Base64 in
`MODE_PRIVATE` SharedPreferences); `AndroidManifest.xml:65-67` (`allowBackup="true"`, with
`backup_rules.xml` and `data_extraction_rules.xml` still the **untouched Android Studio templates**).

- Every per-peer AES-256 session key is readable on any rooted device and is **copied by Google cloud backup and
  device-to-device transfer**. The message database is SQLCipher-encrypted, but the keys that decrypt
  message traffic are not. The at-rest story is inconsistent.
- **Bug side-effect (High):** backup also copies the SQLCipher passphrase blob, which is wrapped by an
  AndroidKeyStore key that does **not** migrate. After a restore, the database cannot be opened, and
  production forbids destructive migration: that means a crash or lock-out on a restored or new phone. Not
  device-tested; the conclusion follows from the code.

**Fix:** wrap session keys with an AndroidKeyStore AES-GCM key (the pattern already exists in
`KeystorePassphraseProvider`), or move them into the SQLCipher DB. Exclude `sharedpref` and `database`
from both backup rule files, or set `allowBackup="false"`. Handle the "passphrase blob present, keystore key
missing" case explicitly.

---

## 2. High / Medium security findings

| # | Sev | Finding | Where | Fix |
|---|---|---|---|---|
| S5 | **High** | **Pre-auth memory DoS.** The WebSocket codec accepts messages up to **512 MB** (`MAX_MESSAGE_BYTES`) and buffers fragments up to that. Any LAN device can make a 2 GB phone (≈192 MB heap) allocate past its limit, before pairing. | `core/network/.../ws/WebSocketCodec.kt:35,187,284` | Cap at the protocol's real maximum (largest chunk + header ≈ 1.1 MB; far less before HELLO). Tighten the limit per state: tiny before HELLO, chunk-sized after. |
| S6 | **High** | **Desktop IPC is unauthenticated.** Any local process (or another Windows user on the same machine) can connect to the loopback port in `~/.flash/app.port`, send `SEND <path>`, and have Flash open the share sheet pre-loaded with any file (e.g. `~/.ssh/id_rsa`); one hurried click sends it. `readLine()` also has no length cap or timeout. | `desktop/.../SingleInstanceController.kt:139-180` | Random token written to a user-only-ACL file and required per command; cap line length; socket read timeout; show the source path prominently. |
| S7 | Medium | **App-layer E2E has no replay/ordering binding.** AAD is a constant string (`"flash-binary-e2e-v…"`), so frames can be replayed or reordered inside a session. TLS currently covers this on the wire, but not when TLS is off (S3) or on the relay paths. It also allocates the AAD per frame. | `core/security/.../crypto/SecureBinaryFrameCodec.kt:60` | Bind transferId, chunk index and direction into the AAD; keep a counter nonce per direction. |
| S8 | Medium | **No forward secrecy at the app layer.** One static per-peer session key is persisted forever (and backed up, S4). A key leak decrypts every captured app-layer frame, past and future. | trust store / pairing | Derive per-session keys (ECDHE per connection, HKDF from the long-term key), or rely on TLS 1.3 alone once S1/S3 are fixed and drop the second layer (see L5). |
| S9 | Medium | **Persistent tracking identifier.** A stable `deviceId` plus friendly name is broadcast on every network the phone joins (mDNS/multicast TXT). On public Wi-Fi that is a cross-network tracking beacon. | `TxtCodec.kt:57` | Advertise a rotating ephemeral id; reveal the stable id only inside the authenticated session. |
| S10 | Low | ADR-040 (2026-09-22) sends our own HELLO before binding; this is documented and accepted. Revisit together with S1. | `WsFlashNetwork.connectManual` | — |

Closed-but-unmerged automated PRs #17 ("fail-closed fingerprint validation in pairing") and #23 ("group call
author mismatch") should be re-checked against S1/S2. I did **not** verify whether their exact issue still
reproduces.

---

## 3. Bugs

| # | Sev | Bug | Where | Fix |
|---|---|---|---|---|
| B1 | **High** | **Main-thread socket write → ANR.** When a chat frame is sent from the main looper, it is wrapped in `runBlocking(Dispatchers.IO) { connection.sendText(...) }`, which **blocks the UI thread** on a kernel socket write. A zero-window peer (the §1.2 C scenario) freezes the app until the ~45 s watchdog, which is well past the 5 s ANR limit. | `app/.../DiscoveryEngineHolder.kt:937-941` | Never block the main thread: launch on the engine scope and report the result asynchronously. |
| B2 | Medium | Call invite path blocks its caller for up to **2 s** with `runBlocking { withTimeoutOrNull(2000) {…} }`. If a tap on the call button reaches it on the main thread, that is visible jank or an ANR on a slow phone. | `DiscoveryEngineHolder.kt:1063` | Make `sendFrame` suspend. |
| B3 | ~~Medium~~ **Low (corrected 2026-09-23)** | *Correction: measured in Phase 4.3, see FIX-PHASES 4.3: no spin-forever loops exist and only ~20 sites wrap suspend calls. Original text follows.* **Coroutine cancellation is swallowed across the codebase:** 467 `runCatching`, 113 broad `catch (Exception/Throwable)`, but only **13** places rethrow `CancellationException`. Result: work that should stop keeps running (leaked jobs after `stopAll`, stuck loops on shutdown). ERROR-068-class bugs come from this family. | repo-wide | A `runCatchingCancellable` helper plus a detekt rule; fix the suspend call sites first. |
| B4 | Low | `resolvedStreams = if (defaultStreams != 2) defaultStreams else profile.streamCount` — a caller that *explicitly* asks for 2 streams is silently overridden by the tier. | `RealFlashTransferRepository.kt:301` | Make the override nullable (`Int? = null`). |
| B5 | High (process) | **CI is red on every `dev` push**, including the 2.0.0-beta release commits: `DesktopMediaStackSmokeTest` needs native WebRTC and audio devices that the Ubuntu runner lacks, so `:core:calling:allTests` fails and nothing downstream is gated. | `.github/workflows/ci.yml`; run 35437740361 | Tag hardware-dependent tests and exclude them in CI; make CI green, then protect `main`. |
| B6 | Medium | ERROR-062 (phone→desktop image/video send) and ERROR-063 (media playback gaps) are still **OPEN**, and were shipped in 2.0.0-beta. ERROR-002/003/004 are marked OPEN but look superseded. | `logs/errors.md` | Reproduce or close with a reason. |
| B7 | Medium | Restore-from-backup lock-out (see S4). | — | — |
| B8 | Low | Two copies of `KeystorePassphraseProvider` (`:app` and `:core:engine`), and the whole TLS/WS stack duplicated between `androidMain` and `jvmMain` by hand ("do NOT edit one copy without the other"). Divergence is only a matter of time. | `core/network/src/{androidMain,jvmMain}` | Move the JVM-common code into a shared `jvmCommon` source set (revisit D1 = Option B). |

---

## 4. Low-end mode (LOW tier) — why it does less than claimed, and what would actually help

### L1 — The library facade never applies the tier (High)
`core/engine/.../Flash.kt` (what `Flash.create` returns to third-party consumers) has **zero** references to
`FlashPerformanceMode`, `transportProfile` or the classifier. A library consumer on a 2 GB phone gets the HIGH
transfer profile, HIGH keepalive cadence and HIGH voice settings. Only the first-party `:app` and `:desktop` wire it.
**Fix:** classify in `Flash.create` (overridable via config) and pass `performanceMode` into the transfer
repository, `WsFlashNetwork(transportProfile=…)` and calling.

### L2 — Three of the eight tier knobs are never read (High, docs are wrong)
`maxAdaptiveChunkSizeBytes`, `allowVideoThumbnails` and `maxImagePreviewDimension` have **no readers** outside
`FlashTransferProfile.kt`. The README/ADR-022 claims ("skipped heavy video thumbnailing" on LOW, "1 MB adaptive
ceiling" on HIGH) describe behaviour that does not exist. There is no adaptive chunk sizing at all.
**Fix:** wire them (thumbnail extraction and image decode sampling are where LOW phones hurt most), or delete
them and correct the README.

### L3 — LOW uses 32 KB chunks: probably backwards for CPU-bound phones (Medium, unmeasured)
Each chunk costs a SHA-256 finalize, an AES-GCM seal plus a TLS record, a frame header, a done-bit, an ACK and
several allocations. Halving the chunk size **doubles all of those per byte**, on the phones with the weakest
CPUs. The memory argument is negligible: 64 KB × 4 frames = 256 KB. The number was chosen, not measured.
**Fix:** measure 32/64/128/256 KB on the Belfone (EXP entry); the answer is likely 64–128 KB for LOW.

### L4 — Every send reads the whole file twice and hashes it three times (High for large files)
No caller passes `fileSha256Hex`, so both send paths run `chunker.hashOnly(source)`: a **full read and SHA-256 of
the entire file before the first byte is sent** (`SendPipeline.kt:110`, `MultiStreamDispatcher.kt:205`). Then the
chunk pass reads it again, hashing each chunk *and* the whole file again. On a budget phone's eMMC, a 4 GB video
waits roughly 40–80 s before the transfer visibly starts. `hashOnly` also allocates a 64 KB buffer outside the
tier's budget.
**Fix:** drop the pre-pass. Send `FILE_START` without the whole-file digest and send it in `FILE_END`; the
receiver already verifies each chunk. Resume identity can use size + mtime + a first/last-chunk hash.

### L5 — Double encryption (Medium, unmeasured)
Payloads are sealed with app-layer AES-256-GCM (`SecureBinaryFrameCodec`) **and then again by TLS 1.3**. On
Cortex-A53 SoCs without ARMv8 crypto extensions, that doubles the dominant CPU cost of a transfer. Once S1/S3
are fixed, TLS 1.3 with mutual pinned auth provides confidentiality and integrity by itself. **Fix:** after
S1/S3, keep one layer (preferably TLS; ChaCha20-Poly1305 preferred on CPUs without AES instructions), or make
the app layer skip frames already inside an authenticated TLS session.

### L6 — The classifier misreads budget phones (Medium)
Tiers are decided by RAM, API level, screen pixels and **core count** (`FlashPerformanceClassifier.kt`). Budget
SoCs (Helio G25/G35/A22, Unisoc T606) have **8 slow cores**, so a 3–4 GB, 8×A53 phone typically lands in
MEDIUM or HIGH (4 streams, deep queues). No CPU speed, SoC class or benchmark signal is used.
**Fix:** add a 200 ms SHA-256/AES micro-benchmark at first boot (cached) and weight it above core count; use
`Build.SOC_MODEL` (API 31+) and `/proc/cpuinfo` max frequency as tie-breakers.

### L7 — Allocation churn is only partly addressed (Medium)
The 2026-09-22 pool removed one send-side `copyOf`. What remains per chunk: `ChunkFrame.serialize` (a full
copy), `SecureBinaryFrameCodec` (nonce + ciphertext + result arrays), okio's `HashingSink`, which copies every
update into okio segments (`Sha256.kt:159` KDoc admits this), and the **entire receive path** (`copyOfRange`,
plaintext arrays). **Fix (measure first):** serialize straight into a pooled buffer, `Cipher.doFinal` into it,
hash with `MessageDigest` directly on JVM/Android (`expect`/`actual`), receive into pooled buffers.

### L8 — Nothing in the LOW tier has been measured
No `logs/experiments.md` entry validates any LOW number (chunk size, queue depth, stream count, 20 kbps voice, the
thermal thresholds). AGENTS.md §23 is explicit that this is not optional.
**Fix:** one EXP session on the Belfone: throughput and CPU% per chunk size; time-to-first-byte with and without
the pre-hash; 30 min sustained transfer temperature curve.

### What already works on LOW (to be fair)
Voice 60 ms Opus + DTX, the tiered keepalive cadence (in the first-party app), reduced motion, the bitmap cache
trim on `UI_HIDDEN`, and the transfer queue depths.

---

## 5. Library quality vs industry practice

| # | Sev | Finding | Fix |
|---|---|---|---|
| Q1 | **High** | **Public surface is ~1,560 declarations** across `core/*` (network 272, messaging 267, persistence 217, common 179, discovery 163, calling 167, transfer 153, security 124). Transport internals (`WsConnection`, `WsTransferClient`, their constructor parameters — including one added 2026-09-22) and **every Room entity/DAO** in `core:persistence` are public, so any schema tweak is a breaking change. | Mark transport and persistence internals `internal`; expose the engine facade plus ports only. Target: a few hundred symbols. |
| Q2 | **High** | **No ABI-compatibility gate.** BCV was removed (ADR-023); `explicitApi()` only forces a visibility modifier, it does not detect breaking changes. 1.1.0 → 2.0.0-beta shipped with no API diff. | Re-add the Kotlin binary-compatibility validator (or `kotlin { abiValidation {} }` in Kotlin 2.2+) and check `.api` dumps in CI. |
| Q3 | **High** | **POMs carry no `licenses`, `scm` or `developers` metadata**, and there is no signing, sources/javadoc policy or Dokka output. That rules out Maven Central; JitPack-from-a-personal-repo is not what enterprises accept. | Add POM metadata in the root convention, then Dokka, then signing, then Central (vanniktech maven-publish plugin). |
| Q4 | Medium | No `CHANGELOG.md`, `SECURITY.md` (vulnerability disclosure), or `CONTRIBUTING.md`. The version jumped to 2.0.0-beta with no migration notes. | Keep a Changelog; SemVer policy; SECURITY.md with a contact. |
| Q5 | Medium | CI gates nothing (B5) and runs no lint, detekt/ktlint, dependency-vulnerability scan (OWASP/Dependabot), CodeQL, or publish dry-run (`jitpack.yml`'s install line). | Add them incrementally; start with the publish dry-run, which has already bitten this repo (memory: release-dry-run). |
| Q6 | Medium | **Stale AndroidX** against a 2025-12 Compose BOM: `core-ktx 1.10.1`, `lifecycle 2.6.1`, `activity-compose 1.8.0` (all 2023). WebRTC is **M125** (mid-2024); libwebrtc ships security fixes continuously. | Update, pin via the catalog; schedule a WebRTC bump. |
| Q7 | Low | The repo root is cluttered: tracked device screenshots (`first-screen-device*.png`), plus many untracked build logs, including logs of **Stream SDK builds**, which ADR-003 forbids (they appear to be from a rejected experiment). | Move them to `logs/` or delete; add a note that the Stream experiments were abandoned. |
| Q8 | Low | `ui:*` compiles against SDK 37 and `core:*` against 35; `app` targets 36. That is fine, but it is undocumented in the README's compatibility table. | Document it. |

### What already meets industry practice
`explicitApi()` strict everywhere; consumer ProGuard rules in every module; the dependency-closed publish set;
sample consumers used as contract tests; Apache-2.0 LICENSE; the persistence port/adapter inversion
(ADR-024); the WebRTC `compileOnly` seam keeping ~30 MB/ABI out of non-calling consumers (ADR-033); constant-time
pin comparison; SQLCipher with a keystore-wrapped passphrase; the path-traversal sanitizer.

---

## 6. Licensing, store policy, privacy

| # | Sev | Finding | Fix |
|---|---|---|---|
| C1 | **High** | **No third-party notices anywhere:** no "Open-source licenses" screen in the app, and nothing in the EXE/MSI. The APK and installers redistribute WebRTC (BSD-3), libopus/libvpx (BSD), SQLCipher Community (BSD-style; **requires reproducing the notice**), JCodec (BSD-2), BouncyCastle (MIT), sqlite-jdbc-crypt/JmDNS/okio/AndroidX (Apache-2.0, which carries NOTICE obligations). BSD clause 2 is a condition of redistribution. | Generate notices (e.g. AboutLibraries or `oss-licenses-plugin`), show them in Settings → About, and bundle `THIRD_PARTY_NOTICES.txt` in the installers. |
| C2 | **High** | The **vendored `third_party/webrtc-kmp` fork** (Apache-2.0) carries the upstream README unchanged, with no statement of modifications (Apache-2.0 §4(b) requires prominent notices in modified files) and no upstream NOTICE passed through. | Add a `MODIFICATIONS.md` and file headers; carry the upstream NOTICE. |
| C3 | Medium | The root `NOTICE` is two lines and doesn't cover the bundled Apache components' NOTICE contents. | Aggregate them. |
| C4 | Medium | **Play policy exposure:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a restricted permission (it needs a Play Console justification and is often rejected); `USE_FULL_SCREEN_INTENT` must be declared as a calling use case on Android 14+; the `dataSync` FGS has a 6-hour/24 h cap on Android 15, and Google is steering transfers towards user-initiated data transfer jobs. | Prepare the declarations; plan a migration to user-initiated data transfer jobs for large transfers. |
| C5 | Medium | **No privacy policy / Data Safety answers**, yet the app broadcasts a persistent device id and name (S9), and stores contacts' names, messages and files. | Write one before any store listing. |
| C6 | Low | Unsigned Windows installers → SmartScreen "unknown publisher" warnings. | Code-signing certificate. |

---

## 7. Process and documentation

- The ADR/log discipline is a real strength. But `logs/progress.md` is **759 KB** and `logs/handoff.md` **345 KB**,
  far beyond what a new session (human or AI) can read. The "fastest file for a new AI" now takes longer to read
  than the code it describes. **Fix:** keep `handoff.md` to the current state only (~2 pages); archive history by
  month.
- Reports from other AI sessions have overstated completion (2026-09-22: in-memory mutex reported as a DB
  transaction, unwired helpers reported as done, a fixed bug re-introduced). The logs are only as good as the
  last writer's honesty. **Fix:** require every "DONE" to name its production call site and its test.
- `decisions.md` has duplicate numbers (two ADR-022s, two ADR-026s).
- The 51-file hardening batch from 2026-09-22 is still **uncommitted** and **not device-tested**.

---

## 8. Recommended order (effort is rough)

1. **S1 inbound authentication + mandatory E2E for keyed peers** (2–3 d). Everything else is moot while any LAN
   device can pose as a contact.
2. **S3 fail-closed TLS** (0.5 d) and **S5 frame-size cap** (0.5 d): small, high value.
3. **S4 key storage + backup rules** (1 d), including the restore lock-out (B7).
4. **S2 pairing commitment protocol** (3–5 d, protocol bump, re-pair UX).
5. **B1/B2 main-thread blocking** (0.5 d), **B5 green CI** (0.5 d).
6. **C1/C2 license notices** (1 d): a legal precondition for distributing what is already released.
7. **L4 drop the pre-hash** (1–2 d), then **L1 facade tiers** (0.5 d), **L2 wire or delete the dead knobs** (1 d).
8. **EXP session on the Belfone** (L3/L5/L6/L8) before touching any other LOW number.
9. **Q1–Q3 library surface, ABI gate, POM metadata** (1–2 wk) before calling it 2.0 final.
10. S6–S9, B3 cancellation, Q4–Q8, C4–C6 as ongoing hygiene.

## 9. Limits of this audit
- Static reading plus existing test runs only: **no exploit was run live, no device was used, nothing was measured.**
  S1 and S2 are confirmed by code paths, not by an attack demo; a loopback PoC test is the right next step.
- Not reviewed in depth: WebRTC SDP/ICE handling, PTT, Wi-Fi Direct, the UI modules beyond sizing, Room
  migrations, the desktop DPAPI identity store (ADR-035).
- Store-policy items reflect Google Play policy as generally documented; confirm against the current Play Console
  before submitting.
