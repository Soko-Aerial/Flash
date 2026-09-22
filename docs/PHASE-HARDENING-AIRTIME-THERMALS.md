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

### Phase 1: Connection Handshake & Network Hardening
- [ ] **Task 1.1:** Fix session cap admission check in `WsFlashNetwork.kt` to inspect existing peer replacement/glare before rejecting on max session count.
- [ ] **Task 1.2:** Enhance `WsTransferServer.kt` to track the active socket wrapper (`activeSocket`), ensuring `SSLSocket.close()` is called on failure to emit TLS alerts and free native SSL contexts.
- [ ] **Task 1.3:** Track in-flight handshaking client sockets in `WsTransferServer.kt` so `stop()` immediately terminates blocking socket reads.
- [ ] **Task 1.4:** Unit and loopback test verification for handshake hardening.

### Phase 2: Outbox Drain Concurrency Hardening
- [ ] **Task 2.1:** Atomic transaction for `sendText()` in `RealFlashChatRepository.kt` enclosing message insert, conversation upsert, draft clear, and outbox enqueue.
- [ ] **Task 2.2:** Add `conversationId` to `OutboxDao.makePendingDue` to scope reconnect resets to the reconnected peer rather than resetting all offline queues.
- [ ] **Task 2.3:** Guard `transportSink?.send()` with `runCatching` to prevent unchecked runtime exceptions from aborting the entire drain batch.
- [ ] **Task 2.4:** Unit tests verifying FIFO ordering and scoped peer reconnect resets.

### Phase 3: Background Retention & Android Vitals Safety
- [ ] **Task 3.1:** Hardened `START_STICKY` restart in `FlashBackgroundService.kt`: do not call `stopSelf()` when foreground promotion is refused while backgrounded; retain service state and retry upon screen-on / network callbacks.
- [ ] **Task 3.2:** Dynamic, pulsed WakeLock management in `DiscoveryEngineHolder.kt`: hold indefinite WakeLock only during active transfers/calls; release or timeout during idle discovery to prevent Android 14+ vitals restrictions.
- [ ] **Task 3.3:** Android 15 (API 35) `dataSync` 6-hour timeout safety in `FlashBackgroundService.kt`: synchronously demote foreground service type to `connectedDevice` before calling `super.onTimeout()`.
- [ ] **Task 3.4:** Add `OemBatteryOptimizationHelper` providing deep-link intent resolution for Xiaomi HyperOS/MIUI, Huawei EMUI, Samsung One UI, and Transsion Phone Master.

### Phase 4: Low-Mode Airtime Tuning & WebRTC Opus SDP Tuning
- [ ] **Task 4.1:** Adjust `FlashVoiceProfile.LOW.maxBitrateBps` from 16,000 to 20,000 bps in `core/common/.../FlashVoiceProfile.kt` to unblock Opus SILK in-band FEC.
- [ ] **Task 4.2:** Inject `maxaveragebitrate` into Opus `a=fmtp:111` in `CallSdp.kt` and ensure `a=ptime:` never exceeds negotiated `a=maxptime:`.
- [ ] **Task 4.3:** Extract `audioLevel` in `FlashGroupCallSession.sampleMeshStats()` to populate `leg.isSpeaking` for active speaker presence.
- [ ] **Task 4.4:** Unit tests in `CallSdpTest.kt` and `FlashVoiceProfileTest.kt` verifying SDP formatting and bitrate envelope.

### Phase 5: Memory & Thermal Governor
- [ ] **Task 5.1:** Create `MemoryGovernor` and `MemoryTrimLevel` in `:core:common`.
- [ ] **Task 5.2:** Implement `ComponentCallbacks2` in `FlashApplication.kt` and forward `onTrimMemory` / `onLowMemory` to `MemoryGovernor`.
- [ ] **Task 5.3:** Wire `PRAGMA cache_size = -${profile.sqliteCacheSizeKb}` in `FlashDatabaseOpener.kt` and register `PRAGMA shrink_memory;` on `MemoryGovernor.RUNNING_LOW`.
- [ ] **Task 5.4:** Implement `ChunkBufferPool` in `:core:transfer` and update `ChunkStream` to borrow pooled buffers and hash in-place, eliminating `buffer.copyOf()`.
- [ ] **Task 5.5:** Implement `AndroidThermalGovernor` supporting API 29+ `OnThermalStatusChangedListener` and legacy battery temperature broadcasts with $2^\circ\text{C}$ / 15s hysteresis.
- [ ] **Task 5.6:** Connect `ThermalGovernor` policy to `MultiStreamDispatcher.kt` to adjust concurrency (2 → 1 stream) and inter-frame pacing under thermal distress.
- [ ] **Task 5.7:** Verification tests for memory trimming, buffer pool reuse, and thermal governor throttle policies.

---

## Progress Log & History

* **2026-09-22:** Phase created and committed to track implementation across all three domains.
