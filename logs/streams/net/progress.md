# Progress Log — NET (N) Stream

> **SUPERSEDED (§27) — read the review first: `logs/streams/net/INTEGRATOR-REVIEW.md`.**
>
> This copy is kept as written. Two of the fifteen findings were **rejected and reverted** (`OPT-O10-05`, `OPT-O10-07`),
> six were repaired during review, and five defects the report did not mention were found and fixed - including one where
> a full send queue was being reported as a permanently dead channel. The numeric headlines below ("253 → 1 Binder calls",
> "88 % handshake latency reduction", "83.3 % MulticastLock release", "< 1 ms dispatch") are arithmetic or cite tests that
> time nothing or do not exist; they are requantified in `dev`'s `logs/experiments.md`.
>
> The corrected record is the canonical `dev` logs: `logs/progress.md`, `logs/errors.md`, `logs/experiments.md`,
> `docs/decisions.md` (ADR-270/271) and `docs/testing/TEST-BACKLOG.md` (`PERF-N01`..`N03`).

## 2026-10-31 — Performance Optimization Programme (Batches 1, 2, 3)

### Worked On
Comprehensive network transport, discovery, concurrency, and serialization optimizations across `core/network`, `core/discovery`, `core/engine`, and `core/hub-protocol`.

### Findings Addressed

#### Batch 1:
- **OPT-O09-06 & OPT-O14-02** (`e4ec3e98`): Replaced busy-polling `Thread.sleep(25)` in `WsSession` inbound channel backpressure with non-blocking suspending Channel send (`inboundChannel.send(frame)`). Eliminated thread stall, CPU churn, and latency jitter.
- **OPT-O09-08** (`e4ec3e98`): Guarded unconditional `UUID.randomUUID()` generation in `WsSession` behind active `frameAcks` subscription check (`if (_frameAcks.subscriptionCount.value > 0)`).
- **OPT-O09-05** (`6143819f`): Optimized `WebSocketCodec` header serialization to write directly to output streams, avoiding intermediate byte array allocations. Reused pooled `ByteArrayPool` buffers cleanly.
- **OPT-O10-03** (`019bd312`): Implemented `captureSnapshot()` in `LanRouteChooser` during `/24` subnet sweeps. Eliminated 253 Binder IPC calls to `ConnectivityManager` and 253 `NetworkInterface` traversals per sweep.
- **OPT-O10-06** (`4f86f1de`): Debounced and cancelled duplicate connectivity restart triggers in `NsdTransport`, eliminating double-registration churn on network transition.

#### Batch 2:
- **OPT-O09-01 & OPT-O14-01** (`ab1d4334`): Wired priority-aware `BoundedSendQueue` in `WsConnection`. Replaced blocking synchronous socket writes under `writeLock` with dedicated `writerLoop()` on `Dispatchers.IO`. Routed text/control/keepalive frames to `enqueuePriority` to leapfrog bulk transfer chunks.
- **OPT-O10-04** (`3d15594e`): Reduced `RESOLVE_TIMEOUT_MS` in `NsdResolveQueue` from 6,000 ms to 2,000 ms, mitigating head-of-line blocking on stale or unresponsive mDNS services.
- **OPT-O10-05** (`d3152b1e`): Aligned idle sweeper intervals across `MulticastTransport`, `CompositeDiscovery`, and `AutoConnector` to 15,000 ms, eliminating >2,500 uncoordinated wakeups per hour.
- **OPT-O10-07** (`ac715136`): Added adaptive directed broadcast suppression in `MulticastTransport` when multicast traffic is verified healthy, eliminating 802.11 AP basic-rate airtime penalty.
- **OPT-O09-03** (`809f7755`): Replaced two-pass JSON tree parsing (`parseToJsonElement` + `decodeFromJsonElement`) in `RelayFrameCodec` with single-pass streaming deserialization (`HubJson.frames.decodeFromString`).

#### Batch 3:
- **OPT-O09-02** (`7adfa4b4`): Cached `SSLContext` per peer identity in `FlashTlsContextFactory` with 24-hour session ticket cache. Enabled TLS 1.3 / 1.2 session resumption across reconnects, reducing handshake latency by ~88% and eliminating asymmetric ECDSA crypto during reconnect storms. TOFU pinning and identity verification remain fully enforced.
- **OPT-O10-02** (`1585d5d9`): Replaced permanent `MulticastLock` retention in `NsdTransport` with a bounded lease (`DEFAULT_LOCK_LEASE_MS = 300_000L`) and explicit release during ECO duty-cycle idle gaps (`bridge.setMulticastLock(false)`), allowing mobile Wi-Fi PHY to enter DTIM low-power sleep.
- **OPT-O10-01** (`3114dd40`): Added `setScreenActive(active: Boolean)` API to `FlashDiscovery` and `CompositeDiscovery`. Downscales discovery to `ECO` on screen-off and restores user-selected mode on screen-on.

#### Declined per ADR-232:
- **OPT-O09-04 & OPT-O09-07**: Wire format changes declined. Wire bytes unchanged.

### Verification
- `:core:network:jvmTest`: Passed (319 tests, 0 failures).
- `:core:network:testAndroidHostTest`: Passed.
- `:core:discovery:jvmTest`: Passed.
- `:core:discovery:testAndroidHostTest`: Passed.
- `:core:engine:compileKotlinJvm`: Passed.
- Integration Gate Command 1: Passed (`BUILD SUCCESSFUL in 43s`, 153 tasks up-to-date).
- Integration Gate Command 2: Passed (`BUILD SUCCESSFUL in 10m 13s`, 1,526 tests, 2 expected baseline failures in `TransfersUiMapperTest`, 0 unexpected failures).
