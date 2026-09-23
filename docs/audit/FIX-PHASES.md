# Audit fix phases

Source: [`2026-09-23-full-audit.md`](2026-09-23-full-audit.md). Finding IDs (S1, L4, Q2, …) refer to it.
Rule for every item: **DONE means a production call site plus a test that fails without the fix.** Name both
in the checklist line. Device verification is listed separately and is not implied by DONE.

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
- [ ] 2.2 Backup rules: exclude `sharedpref` and `database` from cloud backup and device transfer (or `allowBackup=false`).
- [ ] 2.3 Restore lock-out: handle "passphrase blob present, keystore key missing" explicitly.

## Phase 3: Pairing protocol v2 (S2)
- [ ] 3.1 Commit-then-reveal with fresh nonces; the code derives from both keys and both nonces.
- [ ] 3.2 Protocol version bump; mark pairings made with v1 as unverified and prompt re-pairing.

## Phase 4: Stability and CI (B1, B2, B5, B3)
- [ ] 4.1 B1/B2: no `runBlocking` on the main thread in the send and call-invite paths.
- [ ] 4.2 B5: tag hardware-dependent tests and exclude them in CI; get CI green; protect `main`.
- [ ] 4.3 B3: a `runCatchingCancellable` helper and a detekt rule; fix the suspend call sites.

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
