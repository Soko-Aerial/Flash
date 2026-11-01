# Stream E (Desktop) — Test Backlog Additions

Every test in this file must be executed on a host test machine or during a desktop profiling session (AGENTS.md §35).
The integrator folds these into `docs/testing/TEST-BACKLOG.md`.

> **Threshold discipline.** In accordance with AGENTS.md §23 and ERROR-938, pass criteria are based on
> observable state, relative comparisons, or invariants rather than brittle wall-clock assertions on variable CI hardware.

---

## PERF-E01 — Desktop Cold Start to First Frame Profiler Run (OPT-O19-06, OPT-O01-05)
- **Area:** Desktop startup / crypto offloading / first frame latency
- **Requirement:** First Compose window frame presents immediately without blocking the UI thread on EC P-256 key generation, DPAPI disk reads, or non-essential taskbar icon rasterization.
- **Hardware needed:** Desktop host (Windows 11 / macOS / Linux).
- **Procedure:**
  1. Launch desktop app with JProfiler or async-profiler attached.
  2. Measure time from `main()` invocation to initial window frame draw.
  3. Inspect UI thread stack trace during startup window display to verify `FlashEngineCrypto` and `PairingCrypto` initialization run on `Dispatchers.IO` (or lazy access upon discovery start) rather than on the AWT Event Dispatch Thread (EDT) or Compose initialization thread.
- **Pass criteria:**
  - Initial skeleton/window frame renders without waiting on cryptographic key generation or DPAPI disk operations.
  - No blocking `ECKeyPairGenerator.generateKeyPair` calls on EDT/Compose thread.
  - Base taskbar icon cache rasterizes only common sizes (16–64px) at startup.
- **Source:** ADR-304, ADR-309, EXP-115
- **Status:** TODO

---

## PERF-E02 — Desktop Shell Recomposition Under Resize and Navigation (OPT-O19-01, OPT-O19-03, OPT-O03-10)
- **Area:** Compose Desktop UI / recomposition thrash / drag-and-drop
- **Requirement:** Root `DesktopShell` does not recompose on pixel-by-pixel window resize increments, does not re-register AWT `DropTarget` on navigation, and does not recompose on settings telemetry updates.
- **Hardware needed:** Desktop host.
- **Procedure:**
  1. Run desktop app with Compose Layout Inspector / Recomposition Counter enabled.
  2. Continuously drag-resize the window width by 100 pixels within a single size-class breakpoint (e.g. 900px to 1000px).
  3. Switch navigation destinations (e.g. Chat $\to$ Transfers $\to$ Settings).
  4. Trigger background telemetry updates (radio state change, hub lease refresh).
- **Pass criteria:**
  - Drag-resizing within the same breakpoint triggers 0 root `DesktopShell` recompositions (`rememberFlashDesktopWindowSize` wrapped in `derivedStateOf`).
  - Switching navigation destinations does not invoke recursive AWT hierarchy traversal (`DisposableEffect` keyed on `window`).
  - Radio or hub telemetry emissions do not recompose `DesktopShell` outside the `DesktopSettingsDestination` composable.
- **Source:** ADR-300, ADR-302, ADR-303, EXP-116
- **Status:** TODO

---

## PERF-E03 — Desktop Idle Discovery Downscaling When Minimized (OPT-O19-07)
- **Area:** Discovery / power consumption / tray minimization
- **Requirement:** Discovery scales down to `FlashDiscoveryMode.ECO` when the window is minimized or hidden in tray with no active workloads, but remains active if transfers, calls, or PTT sessions are ongoing.
- **Hardware needed:** Desktop host with Wi-Fi network interface.
- **Procedure:**
  1. Launch desktop app with network packet capture (Wireshark) filtering mDNS (`port 5353`) and Flash discovery beacons (`port 45823`).
  2. Minimize window to system tray with 0 active transfers, calls, or PTT.
  3. Verify discovery downscales to `ECO` mode (verified by `DesktopDiscoveryDownscaleTest`).
  4. Restore window from tray: verify discovery immediately returns to preferred user mode.
  5. Start an active file transfer, then minimize window: verify discovery does NOT downscale to `ECO` while transfer is in progress.
  6. Allow transfer to finish while minimized: verify discovery downscales to `ECO` upon transfer completion.
- **Pass criteria:**
  - Discovery mode transitions to `ECO` when minimized at idle.
  - Active workloads (transfers, calls, PTT) keep discovery active despite minimization.
  - Discovery restores to user-configured mode immediately upon un-minimizing.
- **Source:** ADR-305, EXP-117
- **Status:** Automated unit test passed (`DesktopDiscoveryDownscaleTest`); device packet capture TODO.

---

## PERF-E04 — Asynchronous Log Writer Throughput and Caller Latency (OPT-O20-03)
- **Area:** Desktop logging / disk I/O / thread latency
- **Requirement:** High-volume logging does not block caller threads (such as Compose frame renderers or transfer loops).
- **Hardware needed:** Desktop host.
- **Procedure:**
  1. Emit 10,000 log entries concurrently from 8 threads simulating rapid chunk progress events.
  2. Measure elapsed time and thread blocking time on caller threads.
  3. Verify all log lines are flushed cleanly to file without truncation or file corruption upon application exit.
- **Pass criteria:**
  - Caller threads return immediately into the queue (`ArrayBlockingQueue(4096)`) without disk write stalls.
  - Background flusher worker thread processes and writes chunks with buffered 8 KB writes.
  - Shutdown hook flushes pending items on JVM exit.
- **Source:** ADR-306, EXP-118
- **Status:** TODO

---

## PERF-E05 — Desktop Idle Thread Count Footprint (OPT-O14-06)
- **Area:** JVM thread management / coroutine pool bounding
- **Requirement:** Desktop app spawns no more than 20 threads at idle after full engine startup.
- **Hardware needed:** Desktop host.
- **Procedure:**
  1. Launch desktop app; allow engine startup and discovery initialization to settle.
  2. Query active JVM thread count and thread names via JMX / `jstack <pid>`.
  3. Compare against unbounded thread baseline.
- **Pass criteria:**
  - `kotlinx.coroutines.io.parallelism` is capped at 16.
  - `DesktopEngine.scope` uses `limitedParallelism(8)`.
  - Idle thread count is reduced and no runaway coroutine dispatcher threads are created.
- **Source:** ADR-307, EXP-119
- **Status:** TODO
