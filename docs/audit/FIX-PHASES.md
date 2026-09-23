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
- [ ] **1.2 S5: WebSocket message-size cap.** Replace the 512 MB `MAX_MESSAGE_BYTES` with a small cap before HELLO
  and a chunk-sized cap after.
- [ ] **1.3 S1a: inbound client authentication.** The server requests the client certificate; the client leaf is
  bound to the HELLO `deviceId` via `TofuPinVerifier` (the ADR-040 binding, mirrored for inbound). A mismatch is
  closed before `registerSession`.
- [ ] **1.4 S1b: mandatory E2E for keyed peers.** Once a peer has a session key, non-`FLASH_SEC` frames (other than
  HELLO and pairing) are dropped in all three engines; group frames are covered too.
- [ ] **1.5 Impersonation regression test.** A loopback test in which a second client claims an existing peer's id
  must be refused (this is the test that proves 1.3).

## Phase 2: Keys at rest (S4, B7)
- [ ] 2.1 Session keys and pins wrapped with an AndroidKeyStore AES-GCM key (migrate existing plaintext entries).
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
