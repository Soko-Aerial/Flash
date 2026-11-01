# Current Handoff — NET (N) Stream

> **SUPERSEDED (§27) — the review is `logs/streams/net/INTEGRATOR-REVIEW.md`; the live handoff is `dev`'s
> `logs/handoff.md`.** The branch was merged as `8c331229` with 11 of 15 findings kept (6 repaired), `OPT-O10-05` and
> `OPT-O10-07` reverted, and `OPT-O14-03` handed to Stream D. The interface requests this handoff makes to Streams G and D
> are still open and are recorded in `dev`'s `logs/handoff.md`; the owed device checks are `PERF-N01`..`N03` in `dev`'s
> `docs/testing/TEST-BACKLOG.md`.

## Current Branch
`agent/opt-net` in worktree `E:\FlashWork\opt-net` (branched from `dev`).

## Last Verified Build
Commit `3114dd40` (and clean integration gate runs).

## Status Summary
All assigned optimization batches (Batches 1, 2, and 3) are complete, committed to `agent/opt-net`, and verified through both integration gates.

## Completed Tasks
- **OPT-O09-06 / OPT-O14-02**: Suspending channel send replacing busy-poll `Thread.sleep(25)` in `WsSession`.
- **OPT-O09-08**: Conditional `UUID.randomUUID()` generation in `WsSession`.
- **OPT-O09-05**: Direct buffer serialization & `ByteArrayPool` reuse in `WebSocketCodec`.
- **OPT-O10-03**: Route snapshot caching in `LanRouteChooser` during `/24` subnet sweeps.
- **OPT-O10-06**: Debounced connectivity restart triggers in `NsdTransport`.
- **OPT-O09-01 / OPT-O14-01**: Priority-aware `BoundedSendQueue` in `WsConnection` with background `writerLoop` on `Dispatchers.IO`.
- **OPT-O10-04**: Legacy NSD resolve queue timeout reduced from 6s to 2s.
- **OPT-O10-05**: Idle sweeper intervals aligned to 15s across transports.
- **OPT-O10-07**: Adaptive directed broadcast suppression when multicast is healthy.
- **OPT-O09-03**: Single-pass JSON decoding in `RelayFrameCodec`.
- **OPT-O09-02**: `SSLContext` caching per peer identity enabling TLS session resumption across reconnects.
- **OPT-O10-02**: Leased `MulticastLock` with 5-minute maximum bound and release during ECO duty-cycle idle gaps.
- **OPT-O10-01**: `FlashDiscovery.setScreenActive(active: Boolean)` API to downscale discovery mode to `ECO` on screen-off and restore preferred mode on screen-on.

## Cross-Stream Interface Requirements

### 1. For Stream G (Android Lifecycle / Service / App):
- **Finding OPT-O10-01 (Screen-off Discovery Downscale)**:
  - Stream N has implemented `setScreenActive(active: Boolean)` on `FlashDiscovery` (and `CompositeDiscovery`).
  - Stream G should register a `BroadcastReceiver` in `:app` for `Intent.ACTION_SCREEN_OFF` / `Intent.ACTION_SCREEN_ON` (or observe `ProcessLifecycleOwner`).
  - When screen turns off and no transfer or call is in flight, call `engine.discovery.setScreenActive(false)`.
  - When screen turns back on, call `engine.discovery.setScreenActive(true)`.

### 2. For Stream D / Integrator (Messaging / RealFlashChatRepository):
- **Finding OPT-O14-03 (Global drainMutex serialising peers)**:
  - `drainMutex` is in `core/messaging/src/.../RealFlashChatRepository.kt` (`drainOutboxInternal`), which is owned by Stream D. Stream N did not touch this file per ownership boundaries.
  - Recommendation for Stream D: Partition `drainMutex` by destination peer ID using `ConcurrentHashMap<String, Mutex>`:
    `val peerDrainMutex = peerDrainMutexes.computeIfAbsent(peerId) { Mutex() }`
    This ensures slow or offline peers do not block outbox message delivery to other active peers.

## Device Tests Owed (Added to Backlog)
- **PERF-N01**: Two-device file transfer while `BoundedSendQueue` and `writerLoop` are exercised concurrently with chat messaging and keepalive PINGs. Verify that high-priority text messages leapfrog bulk transfer chunks without blocking.

## Next Steps for Integrator
- Merge `agent/opt-net` into `dev` when wave integration begins.
- Delete worktree `E:\FlashWork\opt-net` after merge: `git worktree remove E:\FlashWork\opt-net`.
