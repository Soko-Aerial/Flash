# Domain O28 — Observability Gap, Benchmark Plan, and Performance Specification
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** Senior Performance Engineer  

---

## 1. Domain Header & Scope

This audit evaluates the observability gaps, telemetry infrastructure, automated benchmark harness requirements, and foundational performance specifications across all tiers of the Flash project: Android mobile (`:app`, `:core:*`, `:ui:*`), Desktop (`:desktop`), and Enterprise Hub Server (`:server`).

Telemetry and automated benchmarking are the primary safeguards against invisible performance regressions. In accordance with `AGENTS.md` §23, Flash requires evidence-based engineering where bottlenecks are formally classified into `network-limited`, `storage-limited`, `CPU-limited`, `TLS-limited`, `hashing-limited`, or `protocol-limited`. This domain audits the exact telemetry signals currently missing from production code, defines a continuous regression benchmark suite, and provides the reference framework for `docs/performance.md`.

---

## 2. Files Inspected

- `app/src/main/java/com/transfer/flash/di/FlashApplication.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `app/src/main/java/com/transfer/flash/debug/FlashLogContext.kt`
- `gradle/libs.versions.toml`
- `settings.gradle.kts`
- `app/build.gradle.kts`
- `desktop/build.gradle.kts`
- `server/build.gradle.kts`
- `docs/testing/PROBES.md`
- `docs/audit/optimization-2026-10-23/00-baseline.md`
- `docs/audit/optimization-2026-10-23/00-hot-path-map.md`
- `docs/audit/optimization-2026-10-23/bench/TransferLoopbackBenchmark.kt`
- `docs/audit/optimization-2026-10-23/bench/db_benchmark.py`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/logging/FlashProbe.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/logging/FlashLog.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallDiagnostics.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/PiecePicker.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/TransferScheduler.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmRowProbes.kt`
- `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt`
- `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt`
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`
- `server/src/main/kotlin/com/transfer/flash/server/observability/RelayMetrics.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayConnectionPool.kt`
- `server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/TtlSweeper.kt`

---

## 3. Hot Paths: Observability, Telemetry & Benchmark Execution

| Path ID | Trigger / Cadence | Thread / Dispatcher | Operation / Mechanism | What Runs |
|---|---|---|---|---|
| **HP-O28-01** | Cold start & Activity launch | Main Looper / UI | Activity window attachment & initial composition | `FlashApplication.onCreate` -> `MainActivity.onCreate` -> Compose `setContent`. Currently executes with **zero trace markers** or Macrobenchmark instrumentation. |
| **HP-O28-02** | Active chunk transmission (10–1,000 Hz) | `Dispatchers.Default` | `MultiStreamDispatcher.runWorker` & `feed.send()` | Materializer reads chunk -> feeds channel -> worker sends frame -> socket write. In-flight chunks and channel backpressure stalls execute **uninstrumented**. |
| **HP-O28-03** | Call stats collection (every 5 seconds) | WebRTC worker coroutine | `CallDiagnostics.legLine` stats gathering | Reads `RtcStatsReport` from native WebRTC; formats plaintext `CALL_DIAG` string. Jitter buffer delay, A/V drift, and concealment ratio are **omitted from telemetry**. |
| **HP-O28-04** | Swarm request scheduling tick (1–10 Hz) | Swarm driver coroutine | `TransferScheduler.scheduleRequests` & `checkTimeouts` | Evaluates rarest-first bitfields, detects timeouts, backs off peers. Piece contention, duplicate endgame races, and scheduler stalls run **with zero probe events**. |
| **HP-O28-05** | Room / SQLite write transactions (1–50 Hz) | `Dispatchers.IO` | `RoomDatabase.useWriterConnection` / `SupportSQLiteDatabase` | SQLite statements execute without `QueryCallback` timing; transactions holding exclusive write locks execute with **zero hold-time tracking**. |
| **HP-O28-06** | Hub Server Prometheus Scrape (`/metrics`) | Ktor Netty IO selector | `RelayMetrics.renderPrometheus` & `gauges()` | Aggregates `LongAdder` counters and database probes. Outbound channel queue saturation and SQLite WAL checkpoint execution duration are **unmeasured**. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | Cold start and frame render latency telemetry (Macrobenchmark / Jetpack Tracing) | **Problem** | `OPT-O28-01` (`libs.versions.toml` lacks `androidx.benchmark` and `androidx.tracing`; `FlashApplication` and `MainActivity` have zero `Trace.beginSection` markers) | `STATIC-ARITHMETIC` |
| 2 | Transfer pipeline telemetry: per-stream chunk flight & backpressure stalls | **Problem** | `OPT-O28-02` (`MultiStreamDispatcher.kt:225-265, 517-601` tracks aggregate bytes only; no per-stream in-flight counter or backpressure suspension timer) | `STATIC-ARITHMETIC` |
| 3 | WebRTC / calling telemetry: jitter buffer delay, A/V drift, packet loss concealment | **Problem** | `OPT-O28-03` (`CallDiagnostics.kt:42-133` omits `jitterBufferDelay`, `totalSamplesDuration`, A/V playout skew, and has no structured MOS/telemetry export) | `STATIC-ARITHMETIC` |
| 4 | Swarm piece scheduler: piece contention, stall metrics, and endgame race waste | **Problem** | `OPT-O28-04` (`TransferScheduler.kt:291-325` silently discards timed-out requests; no metrics on duplicate bytes wasted or peer contention) | `STATIC-ARITHMETIC` |
| 5 | SQLite / Room telemetry: slow-query logging and transaction lock hold-time | **Problem** | `OPT-O28-05` (`FlashDatabaseOpener.kt:55-75` and `JvmFlashDatabaseOpener.kt:68-78` do not configure Room `QueryCallback`; zero transaction hold-time metrics) | `STATIC-ARITHMETIC` |
| 6 | Hub Server capacity telemetry: connection scale, queue saturation, WAL checkpoint latency | **Problem** | `OPT-O28-06` (`RelaySession.kt:122-162` drops sessions on `OfferResult.FULL` without saturation gauge; `ServerDatabase.kt:333-339` does not time WAL checkpoints) | `STATIC-ARITHMETIC` |
| 7 | Continuous regression prevention: transfer loopback benchmark harness | **Implemented (In Audit)** | `docs/audit/optimization-2026-10-23/bench/TransferLoopbackBenchmark.kt` implements synthetic loopback pipeline measuring throughput and memory allocation. | `MEASURED` |
| 8 | Continuous regression prevention: SQLite query benchmark harness | **Implemented (In Audit)** | `docs/audit/optimization-2026-10-23/bench/db_benchmark.py` validates `EXPLAIN QUERY PLAN` and measures search/history query timings at 1k, 10k, and 100k messages. | `MEASURED` |
| 9 | Cryptographic & hashing benchmark harness (AES-GCM, BLAKE3, SHA-256) | **Problem** | Described in Section 8.4; repository currently lacks automated microbenchmarks for comparing Conscrypt vs JDK AES/SHA-256 throughput. | `STATIC-ARITHMETIC` |
| 10 | Hub Server relay throughput & connection scale load test harness | **Problem** | Described in Section 8.5; `:server` has extensive unit tests (656 tests) but no automated load harness simulating 1,000+ concurrent WebSocket connections. | `STATIC-ARITHMETIC` |
| 11 | Foundational performance document (`docs/performance.md`) | **Drafted** | Created `docs/audit/optimization-2026-10-23/performance-doc-draft.md` establishing 10-dimensional budgets, bottleneck taxonomy, and hardware profiles. | `STATIC-ARITHMETIC` |

---

## 5. Detailed Findings

### OPT-O28-01 — Complete Absence of AndroidX Macrobenchmark, Microbenchmark, and Jetpack Tracing Harnesses
- **Impact rank:** High
- **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, J, D
- **Runtime:** Android | Desktop | Shared
- **Component:** `gradle/libs.versions.toml:1-230`, `settings.gradle.kts:53-95`, `app/src/main/java/com/transfer/flash/di/FlashApplication.kt:20-28`, `app/src/main/java/com/transfer/flash/MainActivity.kt:173-300`
- **What is wasteful:**  
  The repository contains zero benchmark modules and zero tracing dependencies. `gradle/libs.versions.toml` defines no references to `androidx.benchmark:benchmark-macro-junit4`, `androidx.benchmark:benchmark-junit4`, `androidx.tracing:tracing`, or `androidx.tracing:tracing-ktx`. Neither `FlashApplication.onCreate` nor `MainActivity.onCreate` contains a single `Trace.beginSection()` or `trace("...")` call. Consequently:
  1. Cold startup latency (time to first frame / time to interactive) cannot be measured in CI or automated tests.
  2. Main-thread jank, Compose recomposition storms, and frame deadlines cannot be tracked across commits.
  3. Perfetto / Android Studio system traces yield opaque, unlabelled blocks for app startup, database initialization, Keystore unwrap, and engine bring-up.
- **Cost model:**  
  1 cold launch x 0 tracing observability x 100% of performance regressions = undetected regression risk. Regressions like `OPT-O01-01` (3.7s splash delay) and `OPT-O01-02` (250 ms main-thread I/O) lingered unnoticed because no automated startup benchmark existed to flag them.
- **User-visible effect:** Unmonitored degradation of startup speed and scrolling smoothness; developers cannot isolate whether a release is slower without manual device profiling.
- **Expected gain:** Continuous, automated CI assertion of cold start ($\le 600\text{ ms}$ budget) and frame jank ($\le 1\%$ frame drop budget); instant identification of offending methods in Perfetto traces.
- **Proposed change:**  
  1. Add `:benchmark` module to `settings.gradle.kts` using `androidx.benchmark.macro` and `androidx.test.uiautomator`.
  2. Add `androidx.tracing:tracing-ktx` to `libs.versions.toml` and `:core:common`.
  3. Wrap critical startup sections (`FlashApplication.onCreate`, `AppEngine.init`, `FlashDatabaseOpener.open`, `MainActivity.setContent`) with `trace("...")` blocks.
- **Invariants touched (5.3):** Invariant 6 (User-visible behaviour): Tracing has zero overhead when system tracing is inactive.
- **Risk / effort:** Low risk; M effort.
- **Prove it:** Run `./gradlew :benchmark:connectedCheck` on connected test device; assert `StartupBenchmark` produces exact p50/p95 cold start timings in JSON.
- **Verifier result:** Survived. Inspection of `libs.versions.toml` and all `build.gradle.kts` confirms neither benchmark plugin nor tracing dependencies exist.

---

### OPT-O28-02 — Transfer Pipeline Lacks In-Flight Chunk Flight Telemetry and Backpressure Stall Duration Instrumentation
- **Impact rank:** High
- **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, N, D
- **Runtime:** Android | Desktop | Shared
- **Component:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt:225-275, 517-605`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt:37-98`
- **What is wasteful:**  
  `MultiStreamDispatcher` manages parallel stream workers and bounded feed channels (`val feeds = List(effectiveStreams) { Channel<PreparedFrame>(feedBufferFrames) }`), but provides **zero telemetry on chunk flight or channel backpressure**:
  1. It tracks only global aggregate counters (`chunksSentTotal`, `confirmedCount`, `bytesSentTotal`). It does not track how many chunks are currently in flight per stream (`chunksInFlight[workerId]`).
  2. When the materializer blocks in `feeds[index % effectiveStreams].send()`, or when a worker suspends in `wire.sendFrame(prepared.frameBytes)`, there is no timing probe. If the transfer stalls, engineers cannot determine whether the stall was caused by slow disk read (`ChunkStream.next()`), channel backpressure (receiver not processing), or TCP socket buffer saturation.
  3. The `FlashProbe` vocabulary in `docs/testing/PROBES.md` has no events for transfer pipeline stalls or round-trip ACK latency.
- **Cost model:**  
  ~100–1,000 chunks/sec during high-speed transfers x uninstrumented backpressure events = impossible to diagnose whether a 15 MB/s transfer plateau is `network-limited`, `storage-limited`, or `protocol-limited`.
- **User-visible effect:** Transfers stall or underperform without diagnostic data; field logs cannot explain why a transfer achieved only 10 MB/s on an 866 Mbps Wi-Fi link.
- **Expected gain:** Immediate identification of the bottleneck component (disk read vs wire send vs receiver ACK lag) within structured debug logs and test exports.
- **Proposed change:**  
  1. Add an atomic `inFlightCount` per `StreamChannel` in `MultiStreamDispatcher`.
  2. Instrument backpressure suspension in the materializer: if `feed.send()` takes $> 100\text{ ms}`, increment a `stallDurationMs` accumulator.
  3. Emit a structured probe `transfer.summary` upon completion: `transfer=<id> durationMs=<ms> diskReadMs=<ms> wireStallMs=<ms> ackLagMs=<ms> avgInFlight=<n>`.
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): Internal telemetry does not alter the wire protocol.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run loopback benchmark with a simulated 50 ms disk delay; verify `transfer.summary` reports `diskReadMs` as dominant over `wireStallMs`.
- **Verifier result:** Survived. Code inspection of `MultiStreamDispatcher.kt` lines 225–277 and 554–601 confirms zero timing or per-stream in-flight tracking.

---

### OPT-O28-03 — WebRTC / Calling Telemetry Missing Jitter Buffer Delay, Playout Sync Drift, and Packet Concealment Structured Probes
- **Impact rank:** Medium-High
- **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, J, D
- **Runtime:** Android | Desktop | Shared
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallDiagnostics.kt:42-133, 195-199`, `docs/testing/PROBES.md:49-87`
- **What is wasteful:**  
  `CallDiagnostics.kt` periodically formats plaintext strings (`CALL_DIAG`) every 5 seconds for Logcat, but suffers from severe observability deficiencies:
  1. It ignores critical standard WebRTC quality metrics present in `RtcStatsReport`:
     - `jitterBufferDelay` / `jitterBufferEmittedCount`: The actual time audio/video packets spend in the jitter buffer before playout.
     - `totalSamplesDuration` vs video render timestamps: Necessary to calculate audio/video synchronization drift (lip-sync error).
     - Packet loss concealment ratio: It tracks total `concealedSamples` as an unnormalized raw counter, but does not compute the concealment percentage ($\text{concealed} / \text{totalSamples}$).
  2. It writes purely unstructured string lines (`CALL_DIAG leg=... | vin ... | ain ...`). None of these metrics are exported as structured `FlashProbe` events in `docs/testing/PROBES.md`. Automated tests cannot assert call quality thresholds (e.g. $\text{MOS} \ge 4.0$ or $\text{A/V drift} \le 50\text{ ms}$) without parsing brittle log strings.
- **Cost model:**  
  1 call x 12 `CALL_DIAG` string lines/min x 0 structured probes = automated end-to-end call tests cannot programmaticallly assert call audio/video health or detect lip-sync degradation.
- **User-visible effect:** Audio stutter, robotic voice artifacts, and lip-sync drift occur in production without actionable telemetry in exported diagnostics.
- **Expected gain:** Structured call quality evaluation; ability to compute standard Mean Opinion Score (MOS) and jitter delay in automated test suites.
- **Proposed change:**  
  1. Extract `jitterBufferDelay` and calculate average jitter buffer delay in milliseconds: `(delta(jitterBufferDelay) / delta(jitterBufferEmittedCount)) * 1000`.
  2. Compute audio/video playout skew: compare `estimatedPlayoutTimestamp` between audio and video tracks.
  3. Add structured probe `call.quality` to `PROBES.md`: `peer=<id> mos=<score> jbDelayMs=<ms> avDriftMs=<ms> plcRatio=<pct> rtt=<ms>`.
- **Invariants touched (5.3):** Invariant 1 (security): Probes never contain media content or cryptographic keys.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run a simulated high-jitter call test; assert `call.quality` probe is emitted with exact `jbDelayMs` matching introduced jitter.
- **Verifier result:** Survived. Inspection of `CallDiagnostics.kt:87-133` confirms `jitterBufferDelay` and A/V drift are completely absent from the stats parser.

---

### OPT-O28-04 — Swarm Piece Scheduler Operates Without Piece Contention, Duplicate Race Waste, or Scheduler Stall Telemetry
- **Impact rank:** Medium
- **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, T, K, D
- **Runtime:** Android | Desktop | Shared
- **Component:** `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/TransferScheduler.kt:37-98, 291-325`, `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/PiecePicker.kt:39-97`
- **What is wasteful:**  
  The group swarm transfer engine (`:core:swarm`) coordinates peer-to-peer distribution using a rarest-first scheduler with endgame duplicate requests, but operates with an **observability blind spot**:
  1. **Piece Contention:** When multiple peers request the same piece or when an endgame duplicate race occurs (`TransferScheduler.kt:135-149`), the loser peer's transfer is cancelled via `Unrequest`, but the number of redundant/wasted bytes transferred over the air is never tracked or logged.
  2. **Scheduler Stalls:** In `scheduleRequests()`, if `requestWindow.canRequestBytes(...)` returns false or `availablePeers` is empty while pieces remain missing, the scheduler exits silently. There is no counter or probe recording how many milliseconds the swarm remained stalled waiting for peer request windows to open.
  3. **Silent Timeouts:** In `checkTimeouts()` (`TransferScheduler.kt:308-323`), when a piece request exceeds the timeout threshold ($4 \times \text{expectedPieceMs}$), the request is removed and the peer is penalized with a 5-second backoff, but **zero probe events** are emitted. Developers inspecting logs have no visibility into which peer stalled which piece.
- **Cost model:**  
  1 swarm transfer x multi-peer contention x 0 metrics = invisible bandwidth waste and unexplained swarm download stalls.
- **User-visible effect:** Swarm transfers take longer than 1:1 transfers in certain topologies without logs explaining whether stalls were due to slow peers, congestion, or piece starvation.
- **Expected gain:** Real-time visibility into swarm efficiency; exact measurement of duplicate wire byte overhead (target: $\le 3\%$).
- **Proposed change:**  
  1. Emit `FlashProbe` on piece timeout in `checkTimeouts`: `swarm.piece.timeout root=<root> piece=<idx> peer=<peerId> elapsedMs=<ms>`.
  2. Track duplicate bytes received from loser peers in endgame and emit in `swarm.recv.done`: `wastedBytes=<n> duplicateRatio=<pct>`.
  3. Instrument scheduler stall duration when missing pieces $> 0$ but 0 requests can be scheduled.
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): Internal metrics do not change SwarmFrame formats.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run a 3-node swarm test with one artificially throttled peer; verify `swarm.piece.timeout` and `wastedBytes` are emitted in the test log.
- **Verifier result:** Survived. Code inspection of `TransferScheduler.kt:291-325` confirms requests are silently cleared from `inFlightRequests` without logging or probe emission.

---

### OPT-O28-05 — Room and SQLite Lack Query Execution Callbacks, Slow-Query Logging, and Write-Transaction Hold-Time Metrics
- **Impact rank:** Medium-High
- **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, S, J, D
- **Runtime:** Android | Desktop | Shared
- **Component:** `core/persistence/src/androidMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpener.kt:55-75`, `core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt:68-78`
- **What is wasteful:**  
  Both Android and Desktop Room database openers build `FlashDatabase` without configuring query or transaction telemetry:
  1. Neither builder invokes `RoomDatabase.Builder.setQueryCallback(RoomDatabase.QueryCallback, Executor)`. Consequently, no query execution times are measured. Un-indexed full table scans (e.g. `OPT-O06-01` taking 697 ms on 100k messages) run completely silently until a user reports UI freezing.
  2. Neither opener instruments write-transaction lock hold-time. In SQLite WAL mode, write transactions acquire an exclusive `BEGIN IMMEDIATE` lock. When a background operation (such as directory sync or outbox flush) holds a write transaction for hundreds of milliseconds, all concurrent writes block, causing UI thread stalls without any diagnostic warning.
- **Cost model:**  
  10–100 SQLite queries/sec during active chat x 0 query duration metrics = zero visibility into query performance regressions.
- **User-visible effect:** Periodic UI micro-freezes and slow conversation loading without slow-query warnings in debug logs.
- **Expected gain:** Automatic detection and logging of any SQLite query exceeding $15\text{ ms}$ and any transaction held longer than $50\text{ ms}$.
- **Proposed change:**  
  1. Configure Room `setQueryCallback` in debug and automated test builds: log warning on any query exceeding `SLOW_QUERY_THRESHOLD_MS` ($15\text{ ms}$).
  2. Wrap `runInTransaction` / `withTransaction` blocks with duration timers and log a warning if lock hold-time exceeds $50\text{ ms}$.
- **Invariants touched (5.3):** Invariant 3 (durability/ordering): Query callback is read-only and does not alter database execution semantics.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Execute `searchMessages` with 100,000 rows in test; assert log records `DATABASE: Slow query (142 ms): SELECT * FROM messages...`.
- **Verifier result:** Survived. Confirmed: `FlashDatabaseOpener.kt` and `JvmFlashDatabaseOpener.kt` contain zero references to `setQueryCallback` or transaction timing wrappers.

---

### OPT-O28-06 — Hub Server Lacks Outbound Queue Saturation Gauges and SQLite WAL Checkpoint Latency Instrumentation
- **Impact rank:** Medium
- **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** K, T, S, D
- **Runtime:** Server
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt:305-330`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt:122-162`, `server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt:333-339`
- **What is wasteful:**  
  While the Enterprise Hub Server exposes Prometheus metrics via `/metrics` (`RelayMetrics.kt`), two critical capacity and storage metrics are completely unmeasured:
  1. **Outbound Queue Saturation:** `RelaySession` buffers frames in a bounded channel (`Channel<String>(outboundCapacity)`). When a client connection is slow, the channel fills. In `RelayConnectionPool.broadcastText`, hitting `OfferResult.FULL` immediately terminates the session with `target.fail("slow_consumer")`. However, there is **no metric tracking the queue depth or saturation percentage** of active sessions. Operators cannot monitor queue fill trends before connections are dropped.
  2. **WAL Checkpoint Latency:** `ServerDatabase.checkpointTruncate()` executes `PRAGMA wal_checkpoint(TRUNCATE)` during shutdown and maintenance, but does not measure its duration. On servers with multi-gigabyte databases or high write volume, `wal_checkpoint` can block disk I/O for hundreds of milliseconds to seconds. The Prometheus metrics expose `flash_db_wal_pages`, but have **zero histogram or gauge for WAL checkpoint execution latency**.
- **Cost model:**  
  1 hub server under high load x 0 queue saturation visibility = surprise disconnections of active clients ("slow_consumer") without predictive alerts.
- **User-visible effect:** Enterprise users unexpectedly disconnected from hub during high-throughput broadcasts; server latency spikes during unmonitored WAL checkpoints.
- **Expected gain:** Predictive Prometheus alerting on queue saturation ($> 80\%$ full) before disconnections occur; exact tracking of SQLite WAL checkpoint disk impact.
- **Proposed change:**  
  1. Add Prometheus gauge `flash_relay_queue_saturation_ratio` tracking the maximum queue occupancy across active sessions.
  2. Add Prometheus histogram `flash_db_wal_checkpoint_duration_seconds` in `ServerDatabase` measuring `wal_checkpoint` execution time.
- **Invariants touched (5.3):** Invariant 3 (durability): Monitoring does not affect database transaction durability.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Trigger a 500-message broadcast to a stalled mock client; verify Prometheus `/metrics` reports queue saturation reaching 1.0 prior to disconnection.
- **Verifier result:** Survived. Inspection of `FlashServerEngine.kt:305-330` and `ServerDatabase.kt:333-339` confirms neither metric exists.

---

## 6. "No Problem, Because..." Notes

1. **RelayMetrics Prometheus Series Cardinality:**  
   *No problem, because* `RelayMetrics.kt:10-12` strictly restricts label values to closed enums (frame kinds, outcome codes, error types). User IDs, device IDs, and message IDs are never used as labels, guaranteeing $O(1)$ series memory consumption regardless of server uptime or user scale.
2. **FlashProbe PII and Key Material Sanitization:**  
   *No problem, because* `FlashProbe.sanitize()` (`FlashProbe.kt`) truncates values to 80 characters, strips newlines, and replaces whitespace. Private keys, auth tokens, file paths, and message plaintext are prohibited by design and tested in `FlashProbeTest.kt`.
3. **CallDiagnostics Native Overhead:**  
   *No problem, because* `CallDiagnostics.kt` does not trigger additional native WebRTC stats queries. It passively consumes the `RtcStatsReport` already fetched by the call session's existing stats polling loop.
4. **Desktop Log Rotation Disk Impact:**  
   *No problem, because* `RotatingFileLogSink` on Android bounds log files to $5 \times 1\text{ MB}$, and Desktop logs to `~/.flash/desktop.log` overwritten on each launch, preventing unbounded disk accumulation.

---

## 7. Telemetry & Observability Gap Analysis (Detailed)

The following matrix identifies what can and cannot currently be observed across Flash subsystems:

```
+-------------------------------------------------------------------------------------------------------+
|                                  FLASH TELEMETRY COVERAGE MATRIX                                      |
+-------------------------------------------------------------------------------------------------------+
| Subsystem              | Currently Observable                   | Observability Gap (Unmeasurable)     |
+------------------------+----------------------------------------+-------------------------------------+
| Cold Start & UI        | None (ad-hoc Logcat lines only)        | - Time to interactive / first frame |
|                        |                                        | - Frame render deadline misses      |
|                        |                                        | - Startup method trace slices       |
+------------------------+----------------------------------------+-------------------------------------+
| File Transfer          | - Aggregate bytes done & speed         | - Per-stream chunks in flight       |
|                        | - Final transfer completion / fail     | - Channel backpressure stall time   |
|                        |                                        | - Disk read vs socket write latency |
+------------------------+----------------------------------------+-------------------------------------+
| WebRTC / Calling       | - Plaintext CALL_DIAG (loss, rtt, fps) | - Jitter buffer delay (ms)          |
|                        | - Process CPU & memory snapshot        | - Audio / Video playout sync drift  |
|                        |                                        | - Structured MOS / quality export   |
+------------------------+----------------------------------------+-------------------------------------+
| Swarm Piece Scheduler  | - Piece arrive / verify fail log       | - Piece contention & winner/loser   |
|                        | - SwarmRowProbes UI transition timing  | - Endgame duplicate byte waste      |
|                        |                                        | - Scheduler stall & timeout peer ID |
+------------------------+----------------------------------------+-------------------------------------+
| SQLite / Room          | - PRAGMA shrink_memory log             | - Slow query duration (> 15 ms)     |
|                        | - Migration completion step            | - Write transaction lock hold-time  |
|                        |                                        | - Un-indexed full table scan alert  |
+------------------------+----------------------------------------+-------------------------------------+
| Hub Server             | - Open WebSockets & active sessions    | - Outbound queue saturation ratio   |
|                        | - Mailbox queued count & age           | - WAL checkpoint execution latency  |
|                        | - Database ping probe latency          | - Thread pool / selector saturation |
+------------------------+----------------------------------------+-------------------------------------+
```

---

## 8. Benchmark Harness Specification & Continuous Regression Suite

To ensure performance regressions are caught before PR merge, Flash requires five automated benchmark suites:

### 8.1 Transfer Pipeline Throughput Benchmark
- **File:** `core/transfer/src/jvmTest/kotlin/com/transfer/flash/core/transfer/TransferLoopbackBenchmarkTest.kt` (Prototype available in `docs/audit/optimization-2026-10-23/bench/TransferLoopbackBenchmarkTest.kt`).
- **Target:** Measures sustained throughput (MB/s) and JVM memory allocations per gigabyte transferred.
- **Scenarios:**
  1. **Direct Loopback (0 ms RTT):** Baseline pipeline efficiency with in-memory sink.
  2. **Simulated LAN (10 ms RTT, 0.1% loss):** Pacing and ACK batching efficiency.
  3. **High-Latency P2P (50 ms RTT):** Multi-stream window saturation test.
- **Pass Gate:** Throughput $\ge 150\text{ MB/s}$ on modern x86_64; heap allocation $\le 35\text{ MB}$ per 100 MB payload.

### 8.2 Compose Frame Timing & Scrolling Benchmark
- **Target Module:** `:benchmark` (Android Macrobenchmark).
- **Test:** `ConversationScrollBenchmark.kt`
- **Mechanism:**
  ```kotlin
  @RunWith(AndroidJUnit4::class)
  class ConversationScrollBenchmark {
      @get:Rule val benchmarkRule = MacrobenchmarkRule()

      @Test
      fun scrollConversationList() = benchmarkRule.measureRepeated(
          packageName = "com.transfer.flash",
          metrics = listOf(FrameTimingMetric()),
          compilationMode = CompilationMode.Partial(),
          iterations = 5,
          startupMode = StartupMode.WARM
      ) {
          pressHome()
          startActivityAndWait()
          val list = device.findObject(By.res("conversation_lazy_column"))
          list.fling(Direction.DOWN)
          device.waitForIdle()
      }
  }
  ```
- **Pass Gate:** 99th percentile frame duration $\le 16.6\text{ ms}$; zero frame drops on 60 Hz display.

### 8.3 SQLite Query & Search Benchmark
- **File:** `core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/DatabaseBenchmarkTest.kt`
- **Dataset:** Pre-populated synthetic database containing 1,000, 10,000, and 100,000 messages across 50 conversations.
- **Queries Evaluated:**
  1. `observeConversation` (Recent 50 messages reverse chronologically).
  2. `observeUnreadCounts` (Group by conversation with read cursor subquery).
  3. `searchMessages` (FTS5 search vs `LIKE` scan).
- **Pass Gate:** 100k history fetch $\le 10\text{ ms}$; 100k full-text search $\le 15\text{ ms}$.

### 8.4 Crypto & Hashing Microbenchmarks
- **Target:** Microbenchmark comparing throughput (MB/s) across implementations:
  1. AES-128-GCM (Conscrypt BoringSSL vs JDK default provider).
  2. SHA-256 (Conscrypt vs JDK `MessageDigest`).
  3. BLAKE3 (Native Rust JNI vs pure Kotlin/Java fallback).
- **Pass Gate:** AES-GCM $\ge 400\text{ MB/s}$ per core on x86_64; SHA-256 $\ge 350\text{ MB/s}$.

### 8.5 Hub Server Relay Scale & Throughput Benchmark
- **Tooling:** Ktor Test Engine / Gatling simulation (`server/src/test/kotlin/.../RelayScaleLoadTest.kt`).
- **Load Profile:**
  - 1,000 concurrent persistent WebSocket connections.
  - 50 active group channels broadcasting 100 msgs/sec aggregate.
- **Pass Gate:** Server CPU $\le 25\%$ of 4 cores; p99 message relay latency $\le 5\text{ ms}$; 0 dropped frames.

---

## 9. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Verifier Result | Status |
|---|---|---|---|---|---|---|---|---|---|
| `OPT-O28-01` | Complete Absence of AndroidX Macrobenchmark, Microbenchmark, and Jetpack Tracing Harnesses | High | T, J, D | STATIC-ARITHMETIC | Android, Desktop | M | Low | Survived | OPEN |
| `OPT-O28-02` | Transfer Pipeline Lacks In-Flight Chunk Flight Telemetry and Backpressure Stall Duration Instrumentation | High | T, N, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| `OPT-O28-03` | WebRTC / Calling Telemetry Missing Jitter Buffer Delay, Playout Sync Drift, and Packet Concealment Structured Probes | Medium-High | T, J, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| `OPT-O28-04` | Swarm Piece Scheduler Operates Without Piece Contention, Duplicate Race Waste, or Scheduler Stall Telemetry | Medium | N, T, K, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| `OPT-O28-05` | Room and SQLite Lack Query Execution Callbacks, Slow-Query Logging, and Write-Transaction Hold-Time Metrics | Medium-High | T, S, J, D | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| `OPT-O28-06` | Hub Server Lacks Outbound Queue Saturation Gauges and SQLite WAL Checkpoint Latency Instrumentation | Medium | K, T, S, D | STATIC-ARITHMETIC | Server | S | Low | Survived | OPEN |
