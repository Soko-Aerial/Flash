# Stream E (Desktop) — Experiments

The experiments recorded here document desktop performance characteristics targeted by Wave 4 Stream E optimizations.
In accordance with AGENTS.md §9, §23, and §35, unmeasured wall-clock numbers or synthetic CI estimates are marked **NOT MEASURED** and linked to the reproducible test procedure in `docs/testing/TEST-BACKLOG.md` / `backlog-additions.md` (`PERF-E*`).

> **Integration review, 2026-11-01:** all five entries remain NOT MEASURED. The delivered setup/hypothesis text is
> preserved below as SUPERSEDED, not evidence or a product guarantee. Actual base: `baf77753`; delivered tip: `00a7a51f`.
> Use the reviewed structural results and corrected `PERF-E01..E08` procedures. Host correctness tests do not establish
> startup latency, frame rate, packet savings, power, total thread count or memory gains.

---

## EXP-115 — Desktop Cold Start to First Frame Latency (OPT-O19-06, OPT-O01-05)

### Status
NOT MEASURED (Device/Host test owed: `PERF-E01`)

### Delivered setup — SUPERSEDED
- Target: Windows 11 / macOS desktop host.
- Measure cold process start to first Compose window frame presentation.
- Compare synchronous EC P-256 key generation, DPAPI disk read, and eager taskbar icon rasterization against asynchronous background warming on `Dispatchers.IO` and lazy icon rasterization.

### Delivered hypothesis — SUPERSEDED
Moving crypto initialization off the UI thread and lazy-loading non-essential taskbar icon sizes will eliminate UI thread blocking during initial window composition, rendering the initial frame skeleton immediately.

### Reviewed structural result and owed measurement
Background assembly warms crypto/pairing before readiness. Composition now reads nullable `readyPairing` rather than
initializing or waiting on the lazy coordinator (`ERROR-945`). Both common and large icon caches are lazy; the uncalled
`getAllBaseIcons` is not integrated high-DPI selection. This removes a particular premature access, not every startup
wait or an immediate-first-frame guarantee. Profile base `baf77753` and repaired tree on the same supported Windows host,
recording EDT/Compose/IO stacks, DPAPI/key generation and first-frame latency (`PERF-E01`). Linux needs its own supported
host check; no macOS product/OS integration is established here. Status: NOT MEASURED.

### Verification Test
See `PERF-E01` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-116 — Desktop Shell Recomposition Rate During Window Resizing and Navigation (OPT-O19-01, OPT-O19-03, OPT-O03-10)

### Status
NOT MEASURED (Host test owed: `PERF-E02`)

### Delivered setup — SUPERSEDED
- Measure Compose layout pass and recomposition count during continuous window drag resizing (100px width increment) and navigation destination switching.
- Compare raw `window.width` breakpoint calculations and shell-level StateFlow collections against `derivedStateOf` breakpoint caching and isolated `DesktopSettingsDestination`.

### Delivered hypothesis — SUPERSEDED
Wrapping window width size class in `derivedStateOf` prevents per-pixel recompositions of the root `DesktopShell`. Isolating 8 settings StateFlows prevents root recompositions when settings telemetry updates. Window-scoped `DisposableEffect` prevents recursive AWT hierarchy traversal on navigation.

### Reviewed structural result and owed measurement
The base already reads Compose `LocalWindowInfo.containerSize` and density, with an AWT first-frame fallback. The derived
size class narrows invalidation **for class consumers**; full geometry still observes width and can recompose/layout
inside a breakpoint. Settings-only collection is narrowed without moving operational ownership: the radio controller
remains application-root-owned (`ERROR-944`). Window-owned DropTarget retains an updated **whole sender** to avoid the
pre-ready repository capture (`ERROR-948`). Toast/notification filtering is distinctness, not a timer debounce. Run
`PERF-E02` for class/geometry/navigation/notification measurements, `PERF-E06` for actual drop behavior and `PERF-E07`
for radio startup/tab/tray lifetime. No zero-root-recomposition or measured frame/allocation claim.

### Verification Test
See `PERF-E02` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-117 — Desktop Idle Discovery Power and CPU Consumption When Minimized (OPT-O19-07)

### Status
NOT MEASURED (Host test owed: `PERF-E03`)

### Delivered setup — SUPERSEDED
- Measure CPU utilization and mDNS/NSD socket network traffic when the desktop app is minimized to the system tray at idle.
- Compare full-power discovery (`HIGH`/`BALANCED`) against `FlashDiscoveryMode.ECO` downscaling.
- Verify active workload guards prevent downscaling during active transfers, calls, or PTT sessions.

### Delivered hypothesis — SUPERSEDED
Downscaling discovery to `ECO` when minimized reduces idle mDNS beaconing and socket polling without dropping connected peers or interrupting active transfers.

### Reviewed structural result and owed measurement
Actual modes are STANDARD/BOOST/ECO/GHOST, not HIGH/BALANCED. Window-active, selected GHOST, Transferring/Verifying,
non-ENDED call and non-Idle PTT guard downscale; START, FINISH and selected-mode changes reevaluate while hidden
(`ERROR-943`). Minimized GHOST never becomes advertising ECO. Real host tests cover these transitions, not packets/power.
Desktop connectionPolicy still reads the selected mode; JmDNS/multicast do not honor every Composite ECO browse knob.
An effective-mode transition alone proves neither quieter sweeps nor dropped desktop sessions. Hub REST presence polling
is unchanged and ungated (ADR-298). `PERF-E03` owes real hidden-workload START/FINISH, GHOST packets, liveness and paired
CPU/power measurements. Status: NOT MEASURED.

### Verification Test
See `PERF-E03` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-118 — Desktop Log Writer Throughput and Caller Latency Under High Load (OPT-O20-03)

### Status
NOT MEASURED (Host test owed: `PERF-E04`)

### Delivered setup — SUPERSEDED
- Emit 10,000 log statements from concurrent worker threads simulating peak chunk transfer activity.
- Compare synchronous unbuffered `println`/file appender against bounded asynchronous queue (`ArrayBlockingQueue(4096)`) and 8 KB `BufferedWriter`.

### Delivered hypothesis — SUPERSEDED
Asynchronous bounded log writer decouples callers from disk I/O latency, preventing frame drops and thread pauses during heavy transfer logging.

### Reviewed structural result and owed measurement
Ordinary producers enqueue raw records into a 4,096-entry queue. One worker formats/writes console/file, flushes and closes;
shutdown closes admission and joins that same worker, never becoming a competing writer (`ERROR-946`). The actual
BufferedWriter buffer is **16,384 characters**, not 8 KB. The old FileWriter internally buffered too: the entire baseline
was not unbuffered. Latch tests cover a dequeued record, overflow and timeout without premature close. Overflow drops
newest records/counts them; failures/forced termination/close timeout preclude an all-lines guarantee. `PERF-E04` owes
real-process shutdown and paired caller-latency/output profiling. No FPS, immediate-return or throughput number is measured.

### Verification Test
See `PERF-E04` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-119 — Desktop Idle Thread Count and Memory Footprint (OPT-O14-06)

### Status
NOT MEASURED (Host test owed: `PERF-E05`)

### Delivered setup — SUPERSEDED
- Inspect JVM thread count via JMX / VisualVM at idle after startup.
- Compare default unbounded `Dispatchers.IO` pool with `kotlinx.coroutines.io.parallelism = 16` and `limitedParallelism(8)` on `DesktopEngine.scope`.

### Delivered hypothesis — SUPERSEDED
Capping IO parallelism reduces idle thread count from 28–34 threads down to under 20 threads, saving thread stack overhead (~1 MB per thread).

### Reviewed structural result and owed measurement
The process defaults `kotlinx.coroutines.io.parallelism` to 16 only if unset; the engine uses an IO view with
`limitedParallelism(8)`. These bound concurrent operations in those dispatchers, not all JVM/native/network threads.
The base IO pool was not literally unbounded. The delivered 28–34/under-20/~1 MB estimates are not measured host results
or a total-thread cap. `PERF-E05` inventories thread names, active operation counts, RSS and stack settings under idle
and transfer/call/radio load on paired base/repaired runs. Status: NOT MEASURED.

### Verification Test
See `PERF-E05` in `logs/streams/desktop/backlog-additions.md`.
