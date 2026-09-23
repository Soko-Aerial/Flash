# Audit fix phases

Source: [`2026-09-23-full-audit.md`](2026-09-23-full-audit.md). Finding IDs (S1, L4, Q2, …) refer to it.
Rule for every item: **DONE means a production call site plus a test that fails without the fix.** Name both
in the checklist line. Device verification is listed separately and is not implied by DONE.

**Phases 1–3 device-verified by the owner on 2026-09-23** (pairing, chat, calls, transfers, and upgrading an
install with v1 pairings).

Status: `[ ]` not started · `[~]` in progress · `[x]` done (code + test) · `[D]` device-verified

---

## Phase 1: Transport trust (S3, S5, S1)
Why first: while any LAN device can pose as a contact, every other security property is moot. S3 comes
first because inbound client authentication (S1) means nothing if TLS can silently switch itself off.

- [x] **1.1 S3: TLS fails closed.** `requireTransportSecurity` (`core/network/.../tls/TransportSecurity.kt`) retries
  once, then throws `TransportSecurityUnavailableException`; there is no path that returns "no TLS". Call sites:
  `DiscoveryEngineHolder.startEngineLocked` (hoisted before power locks and receivers, so nothing is half-started;
  shown by AppEngine's start-error + retry), `Flash.create` (documented `@throws`, before the DB opens) and
  `DesktopEngine` assemble (lands in `startError`). Test: `TransportSecurityTest` (host + JVM).
- [x] **1.2 S5: WebSocket message-size cap.** `WebSocketCodec.readMessage(input, maxMessageBytes)`: 64 KiB until the
  peer's HELLO is accepted (`WsConnection.markPeerHelloAccepted`), 4 MiB after (was 512 MB). The oversized length is
  rejected from the header before any allocation, fragments are checked before they are appended, and control frames
  are held to 125 bytes (RFC 6455 §5.5). A frame before HELLO now closes the connection (it used to be buffered
  without limit); after HELLO the early-frame queue is capped at 64 frames and 8 MiB (`bufferEarlyFrame`).
  Android + JVM. Tests: 5 new in `WebSocketCodecTest`; the existing loopback suites prove legitimate handshakes
  still work.
- [x] **1.3 S1a: inbound client authentication.** `SecureSocketUpgrader.wrapAccepted(requireClientCertificate = true)`
  sets `needClientAuth`; the server trust manager defers the pin (no id is known yet) and reports the client leaf,
  which `WsTransferServer` carries on the `WsConnection`. `WsFlashNetwork`/`JvmWsFlashNetwork.handleInboundConnection`
  run `inboundIdentityFailure` (the same `TofuPinVerifier.isPinned` check an outbound dial runs in its handshake)
  after HELLO and **before** replying or registering; a mismatch closes the socket. Compatible with 2.0.0-beta
  clients, which already present their identity certificate when asked. Residual: TOFU still pins a never-seen id
  on first contact (id squatting), same as the outbound path; pairing (Phase 3) is the human check.
- [x] **1.4 S1b: no plaintext downgrade for the direct-chat family (scope narrowed).** Once a peer has a session key,
  a plaintext `FLASH_MSG/RCPT/READ/REACT/TYPING/DACT` frame is dropped in all three engines (`DirectChatFamily.matches`,
  tokenised exactly like `FlashTextFraming.parseFields`). Safe because every engine's `MessageTransportSink`, including
  2.0.0-beta's, always encrypts that family for a keyed peer.
  **Why narrowed:** the original text ("drop every non-`FLASH_SEC` frame") would break calls, groups, transfer control
  and PTT, which have never carried app-layer encryption. With 1.3 in place they ride a mutually authenticated TLS
  session instead. Whether group chat should get app-layer E2E, or whether the app layer should go entirely (audit
  L5), is an open decision.
  Tests: `DirectChatFamilyTest` (host + JVM). **Gap:** the three-line engine wiring has no end-to-end test; the
  engines' inbound dispatch has no harness yet.
- [x] **1.5 Impersonation regression test.** `InboundIdentityBindingTest` (Android, 3 cases) and
  `JvmInboundIdentityBindingTest` (desktop, 2 cases): real TLS loopback. An attacker key claiming a pinned
  contact's id is refused and the pin is untouched; the genuine contact is admitted; TOFU pins a first contact and
  then refuses a second key. **Verified to fail with the binding disabled** (2 of 3 red).

## Phase 2: Keys at rest (S4, B7)
- [x] **2.1 Session keys sealed at rest (Android and desktop).** Android: `AndroidPreferencesTrustStore` seals with
  `KeystoreSecretSealer` (AndroidKeyStore AES-256-GCM, lazy) and stores `s1:`-prefixed values. Legacy plaintext is
  re-sealed on first read; a key that can't be sealed is **not stored** (the save fails); a sealed key that can no
  longer be opened is removed (re-pair). Desktop: `DesktopTrustStore` seals with `IdentityKeyVault.Dpapi` (the ADR-035
  vault), rewrites legacy plaintext on load, and when DPAPI is unavailable keeps the key for the current run but never
  writes it. Pins stay plaintext on purpose: a fingerprint is public; keeping it on-device is the backup rules' job.
  Tests: 4 new in `FlashTrustStoreTest` (with `SoftwareSecretSealer`), 3 new in `DesktopTrustStoreTest`.
  Not device-verified against a real AndroidKeyStore.
- [x] **2.2 Backup rules.** `data_extraction_rules.xml` (Android 12+) and `backup_rules.xml` (≤11), which were
  untouched templates, now exclude `sharedpref` and `database` (plus the `device_*` variants) from BOTH cloud backup and
  device transfer. `allowBackup="false"` alone would not have been enough: on Android 12+ it does not stop
  device-to-device transfer on every OEM (developer.android.com/guide/topics/data/autobackup, checked 2026-09-23).
  `external` (received files) is out of cloud backup so large media cannot blow the 25 MB quota. Only the settings
  DataStore is still backed up. Verified: resources compile and `:app:lintDebug` raises no backup-rule issue.
- [x] **2.3 Restore lock-out (B7).** `KeystorePassphraseProvider.mintedNewPassphrase` reports when a new passphrase
  had to be minted (the unwrap failed, or the blob was missing). `EncryptedDatabaseRecovery.openRecoveringLostKey` then
  moves an existing `flash.db` and its `-wal/-shm/-journal` sidecars to `flash.db.unrecoverable-<ts>*` (renamed, never
  deleted) before opening, and logs an error. It used to crash on every launch, since destructive migration is forbidden.
  Both engines use it. The duplicate `:app` provider was deleted (audit B8); it used the same prefs file and alias,
  so existing installs keep their DB. Test: `EncryptedDatabaseRecoveryTest` (3). Gaps: the user is not told their
  history was lost (log only); the keystore side is device-only.

## Phase 3: Pairing protocol v2 (S2)
- [x] **3.1 Pairing v2 (ADR-042).** Commit-then-reveal nonces; the code covers both TLS-pinned identity keys,
  both ephemeral keys (the session key) and both nonces; each side refuses a fingerprint that is not the key TLS
  pinned; `PAIRED` must match what the code covered. **Wider than the audit's S2:** implementing it exposed that
  v1's code covered neither the ephemeral keys nor the TLS identity, so a relay MITM needed no grinding at all.
  One shared `PairingWireCodec` replaced the app's and the desktop's private codecs. Tests: `PairingV2Test`,
  `PairingWireCodecTest`, `DefaultFlashPairingProtocolTest` (14), `PairingSessionStateMachineTest` (33),
  `FlashPairingCoordinatorTest` (6), and `DesktopPairingLoopbackTest` over real TLS (the fixtures now run TLS).
- [x] **3.2 Migration (owner decisions 2026-09-23).** v1 pairings are kept but `isVerified = false`: the Nearby row
  shows "Not verified" with a **Verify** action (Android + desktop) that runs v2. Pairing with a v1 peer is refused
  with "update Flash on the other device" (a hello without `v=2`, or a request with no commitment).
  Not device-verified; the Verify affordance has had no UI review against docs/ui (§34).

## Phase 4: Stability and CI (B1, B2, B5, B3)
- [x] **4.1 B1/B2: no main-thread blocking.** The Android chat sink now suspends onto IO (`withContext`) for the
  keystore unseal and the socket write, instead of `runBlocking` on the main looper, which caused an ANR with a stalled
  peer. The call-invite wait on Android and desktop is a plain suspend `withTimeoutOrNull` (both `sendFrame`s were
  already `suspend`). The remaining `runBlocking` calls are desktop engine start/stop and `Flash.kt` close, which run
  off the UI thread at lifecycle edges and were left as they are.
- [~] **4.2 B5: CI.** `DesktopMediaStackSmokeTest` self-skips only when `CI=true` (`FLASH_HW_TESTS=1` forces it; a
  load-failure probe was rejected because it would hide a real native break locally). The registry test is
  Windows-only. CI runs with `--continue` and gates `:app:lintDebug`. **Not yet proven green:** needs a real Actions run
  on Linux (requires pushing `dev`); protecting `main` is the owner's GitHub setting.
- [x] **4.3 B3, re-scoped after measuring.** A scan for the dangerous shape (a loop whose only suspension point sits
  inside `runCatching`, which would spin forever after cancellation) found **none**. Only ~20 `runCatching` blocks wrap
  suspend calls; most are deliberate cleanup (stop/close/timeout paths) or false positives (blocking I/O). Added
  `runSuspendCatching` (core:common) and used it where swallowing mattered: the WS handshake waits and reconnect
  loops (Android + JVM) and the three auto-connect sweeps (now `try/finally`, so the dial gate is always released
  and a cancelled sweep stops). **Retracted claim:** I first said the handshake waits reported timeouts as
  "rejected"; `SuspendCatchingTest` proved that `withTimeoutOrNull` discards the block's result after its own
  timeout, so that bug never existed. The test now pins the real behaviour. No detekt rule: new tooling belongs in
  Phase 7.
- [x] **4.4 lint errors to zero.** Real fixes: `FlashBackgroundService` now uses `NotificationCompat` and gates
  `createNotificationChannel` on API 26 (it would have crashed on Android 7.x); the manifest declares
  `uses-feature camera required=false` (Play was hiding the app from camera-less devices). Suppressed with a
  justification (verified false positives): 5 × `MissingPermission` (already inside `runCatching`; a pre-check would
  be wrong below API 33) and 3 × `StateFlowValueCalledInComposition` (`.value` is only `collectAsState`'s initial
  seed). `:app:lintDebug` 0 errors (89 warnings, mostly dependency versions → Phase 7).
  Original finding: **(found by lint, 2026-09-23):** `FlashBackgroundService.kt:137,147,148,213` calls API 26
  `Notification.Builder(ctx, channel)` / `NotificationChannel` without a version guard while minSdk is 24, so the
  background service would crash on Android 7.x. Also five `MissingPermission` warnings on notification posting
  (`FlashNotificationManager`, `FlashCallService`, `PttSessionService`): posting without `POST_NOTIFICATIONS` on 13+.

## Phase 5: Licensing (C1, C2, C3)
- [ ] 5.1 Generated third-party notices, shown in Settings → About and bundled in the installers.
- [ ] 5.2 `MODIFICATIONS.md` and file headers for the vendored webrtc-kmp fork; carry the upstream NOTICE.

## Phase 6: Low-end mode (L4, L1, L2, then measure)
- [ ] 6.1 L4: drop the whole-file pre-hash; send the digest in `FILE_END`.
- [ ] 6.2 L1: the `Flash.create` facade classifies and applies tiers.
- [ ] 6.3 L2: wire or delete the three dead profile knobs; correct the README.
- [ ] 6.4 L8: Belfone EXP session (chunk size, time-to-first-byte, 30-minute thermal curve) before touching L3/L5/L6/L7.

## Phase 7: Library readiness (Q1–Q6)
- [ ] 7.1 Q2: ABI validation with `.api` dumps checked in CI.
- [ ] 7.2 Q1: shrink the public surface (transport and persistence internals become `internal`).
- [ ] 7.3 Q3: POM metadata, Dokka, signing, Maven Central.
- [ ] 7.4 Q4–Q6: CHANGELOG, SECURITY.md, dependency updates, vulnerability scanning.

## Phase 8: Hygiene (S6–S9, Q7, docs)
- [ ] Desktop IPC token (S6), AAD binding (S7), forward secrecy (S8), rotating discovery id (S9), repo clutter
  (Q7), trim `handoff.md` and archive the logs.
