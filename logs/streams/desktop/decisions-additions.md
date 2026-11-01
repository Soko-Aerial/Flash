# Stream E (Desktop) — Decisions Additions

The ADR entries in this file record architectural decisions made during Wave 4 Desktop (Stream E) optimization work.
The integrator will fold these into `docs/decisions.md`.

---

## ADR-300 — Window-Scoped DisposableEffect for Drag-and-Drop (OPT-O19-01)

### Context
`DesktopShell.kt` previously keyed its `DisposableEffect(nav, conversationHeader, twoPane, selectedChatId)` for registering the AWT `DropTarget`.
Whenever navigation destination, active conversation header, or selected chat changed, AWT drop target deregistration and recursive component hierarchy traversal (`registerAwtDropTarget`) occurred across the entire window hierarchy. On navigation transitions or chat selection, this caused unnecessary AWT hierarchy traversal overhead and garbage allocation.

### Decision
1. Key the `DisposableEffect(window)` strictly on the AWT `ComposeWindow` instance.
2. Capture mutable states accessed by drag-and-drop callbacks (`nav`, `conversationHeader`, `twoPane`, `selectedChatId`) using Compose `rememberUpdatedState` (`currentNav`, `currentConversationHeader`, `currentTwoPane`, `currentSelectedChatId`).
3. Ensure recursive hierarchy traversal runs only once per window attachment lifecycle.

---

## ADR-301 — Distinct Active Transfer State Observation in Desktop Shell & Toast (OPT-O19-02)

### Context
`activeTransfers` StateFlow emits at high frequency (up to 100 Hz during active file transfers or chunk progress ticks).
In `DesktopMain.kt` and `DesktopNotificationManager.kt`, collecting this flow without filtering caused redundant evaluations:
- `DesktopTransferToastWindow` recomposed continuously while minimized or during chunk progress even when no toast-actionable transfers were present.
- `DesktopNotificationManager` re-evaluated notifications on every progress byte update rather than state/metadata transitions.

### Decision
1. In `DesktopMain.kt`, filter `activeTransfers` to states requiring toast notifications (`Offered` or `Failed(isRetryable = true)`), wrapped with `distinctUntilChanged` on transfer identifiers, states, and filenames.
2. In `DesktopNotificationManager.kt`, apply `distinctUntilChanged` using a structural equality snapshot comparator (`id`, `state`, `fileName`, `peerName`, `direction`, `errorMessage`) to suppress redundant notification evaluations during bulk progress ticks.

---

## ADR-302 — Derived Window Size Class Caching During Desktop Shell Resizing (OPT-O19-03)

### Context
During window resize drag gestures on desktop, AWT fires resize events for every single pixel increment.
In `DesktopAdaptive.kt`, `rememberFlashDesktopWindowSize` recalculated the layout breakpoint directly from `window.width` changes. Because the composable returned a newly computed size class on every pixel change, `DesktopShell` and its child trees recomposed continuously during window drag resizing.

### Decision
Wrap `rememberFlashDesktopWindowSize` calculation in `derivedStateOf`.
Because `FlashWindowWidthSizeClass` (Compact, Medium, Expanded) changes only at discrete breakpoints (600dp, 840dp), `DesktopShell` recomposes only when crossing a breakpoint boundary rather than on every pixel change.

---

## ADR-303 — Isolated Settings Destination Subtree to Eliminate Shell Root Recompositions (OPT-O03-10)

### Context
`DesktopShell.kt` root previously collected eight StateFlows primarily consumed only inside Settings:
- `radioUiState`
- `hubDropped`
- `hubSealingKeyState`
- `orgLease`
- `orgIdentityMismatch`
- `hubPreviousLinks`
- storage scanning state (`isStorageScanning`)
- active transfer badge counts

Whenever any of these flows emitted (such as radio telemetry, org lease refreshes, or hub dropped updates), the entire desktop root shell recomposed.

### Decision
1. Extract `DesktopSettingsDestination` into a self-contained composable scope that collects settings-specific flows only when the Settings destination is actively rendered.
2. Keep root shell parameters decoupled. Pass only lightweight top-level state flows to child screens.
3. For enterprise enrolled status and department info, project values only to sheet composables (`FlashCreateGroupSheet`, `FlashBrowseOrgGroupsSheet`) that require them.

---

## ADR-304 — Lazy Desktop Engine Cryptographic Key Generation and Off-Main-Thread Warming (OPT-O19-06, OPT-O01-05)

### Context
In `DesktopEngine.kt`, the constructor eagerly generated EC P-256 key pairs, DPAPI-backed master keys, and initialized pairing/crypto state synchronously on the UI thread creating `DesktopEngine`. This introduced noticeable latency on cold start before Compose Desktop could render its first window frame.

### Decision
1. Convert `crypto`, `ephemeralKeyPair`, and `pairing` to lazy properties (`by lazy`) in `DesktopEngine.kt`.
2. Asynchronously warm cryptographic initialization on `Dispatchers.IO` during `assemble()` before network discovery starts.
3. Allow the Compose Desktop window to display its initial skeleton immediately without waiting on cryptographic key generation or DPAPI disk reads.

---

## ADR-305 — Desktop Idle Discovery Downscaling to ECO Mode with Active Workload Guards (OPT-O19-07)

### Context
When the desktop window is minimized to the system tray, discovery previously remained at full power (`HIGH` or `BALANCED`).
Android (Stream G) introduced `FlashDiscovery.setScreenActive(false|true)` which downscales discovery to `FlashDiscoveryMode.ECO` (reducing mDNS beacon frequency and socket probing). A TODO on the interface named `OPT-O19-07` for desktop host wiring.

Downscaling discovery indiscriminately while workloads are active drops peers, aborts transfers, or disconnects active calls.

### Decision
1. Wire `engine.setWindowActive(!isMinimized && isWindowVisible)` from `DesktopMain.kt`.
2. In `DesktopEngine.kt`, implement `evaluateDiscoveryActiveState()` with caller workload guards:
   - Active transfers: downscale is blocked if any transfer is in `Transferring` or `Verifying`.
   - Active calls: downscale is blocked if an active call is present (`callState != CallState.ENDED`).
   - Active PTT: downscale is blocked if PTT floor is active (`pttState !is PttFloorState.Idle`).
3. Add reactive completion listeners in `DesktopEngine.start()`: as soon as all active transfers, calls, and PTT sessions end while the window is inactive, discovery automatically downscales to `ECO`.
4. Restoring window visibility or focus immediately restores the user's preferred discovery mode.
5. In accordance with ADR-298, Enterprise Hub REST presence polling is NOT suppressed, delayed, or gated on window visibility.

---

## ADR-306 — Asynchronous Log Sink with Ring Buffer and Daemon Flusher Thread (OPT-O20-03)

### Context
In `DesktopMain.kt`, debug logging wrote synchronously to console/files using unbuffered I/O on whichever thread emitted the log (including Compose rendering and IO coroutines). Under high log volume or transfer bursts, this induced file I/O latency and UI stutter.

### Decision
1. Replace synchronous writes with a background daemon worker thread consuming an `ArrayBlockingQueue<String>(4096)`.
2. Buffer file writes using an 8 KB `BufferedWriter`.
3. Register a JVM shutdown hook (`Runtime.getRuntime().addShutdownHook`) to flush remaining buffered entries upon exit.
4. If queue fills under extreme saturation, drop excess logs safely without blocking calling threads.

---

## ADR-307 — Bounded IO Parallelism and Dedicated Engine Coroutine Scope (OPT-O14-06)

### Context
Default `Dispatchers.IO` on desktop JVM allocates up to 64 threads or `max(64, CPU cores)`. Multiple independent coroutines, Ktor, OkHttp, and background jobs spawned 28–34 threads at idle, causing excessive thread stack allocations and thread contention.

### Decision
1. Cap `kotlinx.coroutines.io.parallelism` to 16 in `DesktopMain.kt` before coroutine dispatchers initialize.
2. Bind `DesktopEngine.scope` to `Dispatchers.IO.limitedParallelism(8)` for internal background routines.
3. Keep core networking thread pools untouched, bounding thread allocations at the desktop host integration layer.

---

## ADR-308 — Desktop OS Accessibility Reduce-Motion Preference Querying and TTL Caching (OPT-O24-08)

### Context
Users with vestibular disorders or accessibility preferences configure "Show animations in Windows" to disabled in Windows Settings (or "Reduce motion" in macOS). Desktop Compose UI previously did not detect or honor OS-level accessibility reduce-motion settings, relying solely on in-app performance mode.

### Decision
1. Implement `DesktopOsReduceMotion` which queries:
   - Windows: `win.clientareaAnimation.enabled` via `Toolkit.getDefaultToolkit().getDesktopProperty(...)` (false = animations disabled).
   - macOS: `apple.awt.reduceMotion`.
2. Register an AWT property change listener for reactive updates.
3. Provide a 5-second TTL cache to prevent JNI/AWT toolkit query overhead during Compose animation frame rendering.
4. Resolve effective motion settings in `DesktopMain.kt` as:
   `effectivePerformanceMode.reduceMotion || DesktopOsReduceMotion.isReduceMotion()`.

---

## ADR-309 — Desktop Taskbar Raster Badge Lazy Loading and Resource Optimization (OPT-O19-06, OPT-O01-05)

### Context
`DesktopTaskbarBadgeManager` eagerly loaded and rasterized all taskbar icon sizes (16, 24, 32, 48, 64, 96, 128, 256) into memory during startup, consuming image buffers for large display densities rarely used for taskbar overlay badges.

### Decision
1. Eagerly load only the standard small taskbar icon sizes (16, 24, 32, 48, 64).
2. Lazily load 96, 128, and 256 sizes on demand if requested by high-DPI platform taskbars.
