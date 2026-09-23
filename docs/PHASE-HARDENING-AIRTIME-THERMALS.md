# PHASE: Minor Bug Hardening, Low-Mode Airtime Tuning, and Memory & Thermal Governor

## Overview

This implementation phase addresses three core reliability and performance tracks identified in our comprehensive systems investigation:

1. **Track 1: Minor Bug Hardening**
   - Connection handshakes: session cap check order during connect-glare/reconnect, TLS alert propagation, and native socket tracking safety.
   - Outbox drain concurrency: atomic transactional message insertion + outbox enqueue, scoped per-conversation reconnect reset, and safe transport exception handling.
   - Background process retention: resilient `START_STICKY` restart recovery, transfer-scoped partial wake locks to prevent Android 14+ vitals defects, and Android 15 `dataSync` 6-hour timeout handling.

2. **Track 2: Low-Mode Airtime Tuning**
   - 802.11 2.4 GHz half-duplex airtime relief (achieving ~85%–88% reduction in channel contention).
   - Opus 60ms frame pacing and DTX silence suppression in `FlashVoiceProfile` and `CallSdp`.
   - Raising `LOW.maxBitrateBps` from 16kbps to 20kbps to enable Opus SILK in-band forward error correction (FEC).
   - Active speaker detection (`isSpeaking`) via WebRTC `audioLevel` stats in `FlashGroupCallSession`.

3. **Track 3: Memory & Thermal Governor**
   - Application-wide `MemoryGovernor` and `ComponentCallbacks2` implementation in `FlashApplication`.
   - SQLite page cache bounds (`PRAGMA cache_size = -${sqliteCacheSizeKb}`) and runtime cache shrinking (`PRAGMA shrink_memory;`) under memory pressure.
   - Zero-copy buffer streaming with `ChunkBufferPool` to eliminate up to 32GB of heap allocations during multi-gigabyte transfers.
   - Adaptive `AndroidThermalGovernor` dynamically throttling chunk rates and gracefully stepping down streams (2 → 1) under SoC thermal pressure on 2GB RAM devices.

---

## Phase Breakdown & Execution Checklist

Status after the 2026-09-22 verification pass (see Progress Log). `[x]` = in the code and wired;
`[~]` = changed from the original task text, reason given; `[ ]` = not done. Nothing here is
device-verified yet.

### Phase 1: Connection Handshake & Network Hardening
- [x] **Task 1.1:** Session cap admission checks for an existing session with the connecting peer (replacement/glare) before rejecting on max session count. Android + JVM.
- [x] **Task 1.2:** `WsTransferServer.kt` tracks the active socket wrapper (`activeSocket`) and closes it on failure, so the `SSLSocket` sends its alert. Android + JVM.
- [x] **Task 1.3:** In-flight handshaking sockets tracked in `WsTransferServer.kt`; `stop()` closes them.
- [ ] **Task 1.4:** No new unit/loopback test was added for 1.1–1.3; existing network suites pass.
- Note: `SecureSocketUpgrader` now hands Conscrypt `TrackedSocket.delegate` (one level, not recursive). The Conscrypt-FD failure it targets is unproven — device TLS handshakes already reached the signing step through this path (ERROR-070).

### Phase 2: Outbox Drain Concurrency Hardening
- [x] **Task 2.1:** Direct `sendText`/`sendReply` write message + conversation + draft clear + outbox in ONE Room write transaction (`FlashDatabase.runInWriteTransaction`, injected as `RealFlashChatRepository.runInTransaction` in all three engines). `sendGroupText` is not yet transactional.
- [~] **Task 2.2:** NOT scoped, deliberately. The global `makePendingDue` on session-up is documented behaviour (Bug 5) and group outbox rows rely on it; per-peer scoping by 1:1 conversation id would strand a returning member's group backlog. A send to an offline peer fails immediately with no I/O, so the "backoff collapse" costs ~nothing. The unused `makePendingDueForConversation` was removed.
- [x] **Task 2.3:** Direct and group sink sends are guarded (log + treat as not sent); `CancellationException` is rethrown.
- [~] **Task 2.4:** Rollback test added (`FlashDatabaseTransactionTest`, jvm driver). No FIFO-ordering test; Android SQLCipher path not covered by a test.

### Phase 3: Background Retention & Android Vitals Safety
- [x] **Task 3.1:** `FlashBackgroundService` no longer calls `stopSelf()` when foreground promotion is refused; `retryPromotionIfRefused` first retries on the retained instance.
- [~] **Task 3.2:** REVERTED. The engine-lifetime partial wake lock is the ERROR-025/026 fix (peer goes offline on screen-off; keepalive/redial loops stop when the CPU sleeps). Replacing it with a 60 s boot hold + transfer/call-only hold would reintroduce that. Revisit only with a device measurement and an ADR.
- [~] **Task 3.3:** Replaced. `onTimeout` now cancels active transfers and calls `stopSelf()`, which is the documented contract (developer.android.com/develop/background-work/services/fgs/timeout); re-typing via `startForeground` is not documented to avoid the `RemoteServiceException`.
- [x] **Task 3.4:** `OemBatteryOptimizationHelper` is reachable: the Settings "Unrestricted battery" row / Background-transfers toggle opens the OEM autostart/battery screen once the AOSP exemption is held. Added the manifest `<queries>` it needs on API 30+ and removed a candidate that was a BroadcastReceiver, not an Activity. OEM component names are unverified on devices.
- [ ] **Task 3.5 (from investigation §1.3 D):** WorkManager periodic fallback heartbeat — not started.

### Phase 4: Low-Mode Airtime Tuning & WebRTC Opus SDP Tuning
- Pre-existing, not new: LOW already had `ptimeMs = 60` + `useDtx = true` (2026-09-03, ADR-028/029). The "85–88% airtime relief" belongs to that earlier work.
- [x] **Task 4.1:** `FlashVoiceProfile.LOW.maxBitrateBps` 16,000 → 20,000 bps (for SILK in-band FEC). Unmeasured.
- [x] **Task 4.2:** `maxaveragebitrate` in Opus fmtp (MIN envelope). The `a=maxptime:` clamp reverses a previously documented choice (libwebrtc emits 120, so it is a no-op in practice) — needs an ADR note or revert.
- [x] **Task 4.3:** `audioLevel` → `leg.isSpeaking` in group calls; the call screen already renders it. Sampled every 1–2 s (`callStatsIntervalMs`), so the indicator lags speech.
- [x] **Task 4.4:** `CallSdpTest` covers `maxaveragebitrate` and the maxptime clamp.

### Phase 5: Memory & Thermal Governor
- [x] **Task 5.1:** `MemoryGovernor` / `MemoryTrimLevel` in `:core:common`.
- [x] **Task 5.2:** `FlashApplication` forwards `onTrimMemory` / `onLowMemory`.
- [x] **Task 5.3:** `PRAGMA cache_size` on open + `PRAGMA shrink_memory` on low memory. Applies to the connection `onOpen` ran on; HIGH (16 MB) is larger than SQLite's ~2 MB default.
- [~] **Task 5.4:** `ChunkBufferPool` removes the per-chunk `copyOf` on the SEND side only. `ChunkFrame.serialize`, the secure codec and the whole receive path still allocate per chunk — not "zero-copy", heap is not "bounded under 1 MB".
- [x] **Task 5.5:** `AndroidThermalGovernor` installed in `FlashApplication` (API 29+ listener, battery fallback with hysteresis).
- [~] **Task 5.6:** Stream step-down (→ 1 at SEVERE, evaluated at transfer start) kept. Per-chunk pacing delays REMOVED: 10 ms/chunk at MODERATE capped 64 KB chunks near 5 MB/s and 35 ms at SEVERE near 1.8 MB/s, unmeasured (AGENTS.md §23). Re-add only with an EXP entry on the Belfone.
- [x] **Task 5.7:** `MemoryThermalGovernorTest` (pool reuse/caps/trim eviction, in-place SHA-256, governor transitions).

### Phase 6: Remaining investigation items (2026-09-22 continuation)
- [x] **§1.1 E — glare early-frame loss.** `handOffEarlyFrames` no longer drops frames when no
  session owns the peer yet: they are parked in a bounded per-peer mailbox
  (`pendingPeerFrames`, 64 frames, oldest evicted) and drained by `registerSession`. Android + JVM.
  No regression test — reproducing the race deterministically needs a glare harness.
- [~] **§1.2 C — drain stall.** The premise is partly wrong: the message row is committed before the
  drain, so the UI is not blocked; what stalls is delivery for every peer in the batch while one
  peer's socket write blocks (until the ~45 s keepalive close). Bounded with a per-item
  `SEND_TIMEOUT_MS = 10 s` (`sendWithTimeout`); a timed-out row stays in the outbox and retries,
  which is safe because ingestion is idempotent on `localId` (C6.2). The structural fix (claim rows,
  release `drainMutex`, dispatch off-lock) is NOT done.
- [x] **§3.1 C — UI_HIDDEN cache purge: ALREADY SATISFIED before this phase.** The investigation is
  wrong here. `FlashImageDecoder.android.kt` registers its own `ComponentCallbacks2` on first decode
  and `cacheTrimFor` evicts the whole thumbnail cache at `TRIM_MEMORY_UI_HIDDEN`.
- [~] **§2.3 C — speech-onset clipping: NOT ACTIONABLE in code.** The APM exposes booleans only
  (`noiseSuppression(true)`); there is no aggressiveness setting on this API, and Android prefers the
  hardware suppressor (`setUseHardwareNoiseSuppressor`). Would need a libwebrtc field trial.

- [x] **§1.3 D — WorkManager heartbeat: DONE (owner-approved, ADR-041).** `FlashKeepaliveWorker`,
  15-minute unique periodic work (network-connected + battery-not-low), scheduled from
  `FlashApplication`. Starts the engine ONLY if nothing else owns it, waits ≤45 s for a session so
  the outbox drains, then stops it again unless the service or the UI adopted it meanwhile — so the
  engine-lifetime wake lock is never left held headless. Not unit-tested (`:app` has no Robolectric);
  device verification owed via `adb shell cmd jobscheduler run -f com.transfer.flash <jobId>`.
- [x] **§1.1 D — TOFU manual-IP dial: DONE (owner-approved, ADR-040).** A dial with no known peer id
  now defers pin evaluation: the handshake accepts the leaf, records its SPKI, and `connectManual`
  runs the same `isPinned` check the instant `FLASH_WS_HELLO` names the peer — closing the
  connection before registration if it fails. Opt-in per dial, so every discovery-driven dial and
  both server paths still fail closed. "Connect by IP" now reaches an undiscovered peer.
  Tests: 3 in `TofuX509TrustManagerTest`, 2 TLS-loopback in `SecureWsTransferLoopbackTest`.
  Not device-verified.

### Open — needs an owner decision (not silently implemented)
- **§1.2 D — `BoundedSendQueue` into `WsConnection`:** the class exists but only the chaos harness
  uses it. Wiring it changes the write path for chat AND transfer frames (ordering, latency,
  flush-before-disconnect) — ADR-sized.
- **§1.2 D — SHELVED by the owner (2026-09-22).** Revisit with device evidence that write stalls
  actually happen; chat is bounded meanwhile by `SEND_TIMEOUT_MS`.
- **§2.3 D — LOW-tier video lockout in >2-peer meshes / call quality governor:** a product policy
  change (a user's camera would silently not publish); `isSpeaking` from §4.3 is the part that was
  safe to ship.
- **§3.2 B — receive-side allocations, positional `FileChannel.write`:** perf work with no
  measurement yet; AGENTS.md §23 wants an EXP entry first.
- **§3.3 B — thermal chunk-size step-down and EMERGENCY pause:** deliberately parked with the pacing
  delays until a Belfone measurement exists.

---

## Progress Log & History

* **2026-09-22:** Phase created and committed to track implementation across all three domains.
* **2026-09-22:** A first implementation pass marked all 5 phases done.
* **2026-09-22 (verification):** Claims checked against the diff. Corrected: 2.1 was an in-memory mutex (now a real transaction), 2.2/3.4 were unwired, 3.2 regressed ERROR-025/026 (reverted), 3.3 violated the documented timeout contract (now `stopSelf()`), 5.6 pacing was unmeasured (removed). Checklist above reflects the corrected state.
