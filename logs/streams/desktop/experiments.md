# Stream E (Desktop) — Experiments

The experiments recorded here document desktop performance characteristics targeted by Wave 4 Stream E optimizations.
In accordance with AGENTS.md §9, §23, and §35, unmeasured wall-clock numbers or synthetic CI estimates are marked **NOT MEASURED** and linked to the reproducible test procedure in `docs/testing/TEST-BACKLOG.md` / `backlog-additions.md` (`PERF-E*`).

---

## EXP-115 — Desktop Cold Start to First Frame Latency (OPT-O19-06, OPT-O01-05)

### Status
NOT MEASURED (Device/Host test owed: `PERF-E01`)

### Setup
- Target: Windows 11 / macOS desktop host.
- Measure cold process start to first Compose window frame presentation.
- Compare synchronous EC P-256 key generation, DPAPI disk read, and eager taskbar icon rasterization against asynchronous background warming on `Dispatchers.IO` and lazy icon rasterization.

### Hypothesis
Moving crypto initialization off the UI thread and lazy-loading non-essential taskbar icon sizes will eliminate UI thread blocking during initial window composition, rendering the initial frame skeleton immediately.

### Verification Test
See `PERF-E01` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-116 — Desktop Shell Recomposition Rate During Window Resizing and Navigation (OPT-O19-01, OPT-O19-03, OPT-O03-10)

### Status
NOT MEASURED (Host test owed: `PERF-E02`)

### Setup
- Measure Compose layout pass and recomposition count during continuous window drag resizing (100px width increment) and navigation destination switching.
- Compare raw `window.width` breakpoint calculations and shell-level StateFlow collections against `derivedStateOf` breakpoint caching and isolated `DesktopSettingsDestination`.

### Hypothesis
Wrapping window width size class in `derivedStateOf` prevents per-pixel recompositions of the root `DesktopShell`. Isolating 8 settings StateFlows prevents root recompositions when settings telemetry updates. Window-scoped `DisposableEffect` prevents recursive AWT hierarchy traversal on navigation.

### Verification Test
See `PERF-E02` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-117 — Desktop Idle Discovery Power and CPU Consumption When Minimized (OPT-O19-07)

### Status
NOT MEASURED (Host test owed: `PERF-E03`)

### Setup
- Measure CPU utilization and mDNS/NSD socket network traffic when the desktop app is minimized to the system tray at idle.
- Compare full-power discovery (`HIGH`/`BALANCED`) against `FlashDiscoveryMode.ECO` downscaling.
- Verify active workload guards prevent downscaling during active transfers, calls, or PTT sessions.

### Hypothesis
Downscaling discovery to `ECO` when minimized reduces idle mDNS beaconing and socket polling without dropping connected peers or interrupting active transfers.

### Verification Test
See `PERF-E03` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-118 — Desktop Log Writer Throughput and Caller Latency Under High Load (OPT-O20-03)

### Status
NOT MEASURED (Host test owed: `PERF-E04`)

### Setup
- Emit 10,000 log statements from concurrent worker threads simulating peak chunk transfer activity.
- Compare synchronous unbuffered `println`/file appender against bounded asynchronous queue (`ArrayBlockingQueue(4096)`) and 8 KB `BufferedWriter`.

### Hypothesis
Asynchronous bounded log writer decouples callers from disk I/O latency, preventing frame drops and thread pauses during heavy transfer logging.

### Verification Test
See `PERF-E04` in `logs/streams/desktop/backlog-additions.md`.

---

## EXP-119 — Desktop Idle Thread Count and Memory Footprint (OPT-O14-06)

### Status
NOT MEASURED (Host test owed: `PERF-E05`)

### Setup
- Inspect JVM thread count via JMX / VisualVM at idle after startup.
- Compare default unbounded `Dispatchers.IO` pool with `kotlinx.coroutines.io.parallelism = 16` and `limitedParallelism(8)` on `DesktopEngine.scope`.

### Hypothesis
Capping IO parallelism reduces idle thread count from 28–34 threads down to under 20 threads, saving thread stack overhead (~1 MB per thread).

### Verification Test
See `PERF-E05` in `logs/streams/desktop/backlog-additions.md`.
