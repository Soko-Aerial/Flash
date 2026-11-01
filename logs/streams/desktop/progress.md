# Stream E (Desktop) — Progress Log

## 2026-11-01 — Wave 4 Optimization: Desktop Host, Compose Shell, Startup & Threading

### Worked on
Completed full implementation and test verification of Wave 4 Desktop (E) stream optimization findings in the prescribed order:
1. `OPT-O19-01`: Keyed recursive AWT `DropTarget` registration strictly on window lifecycle in `DesktopShell.kt`.
2. `OPT-O19-02`: Filtered and debounced `activeTransfers` StateFlow observation in `DesktopMain.kt` and `DesktopNotificationManager.kt`.
3. `OPT-O19-03`: Wrapped window-width container size class calculation in `derivedStateOf` in `DesktopAdaptive.kt` during resize gestures.
4. `OPT-O03-10`: Decoupled eight settings and telemetry StateFlows from `DesktopShell.kt` root by extracting `DesktopSettingsDestination`.
5. `OPT-O19-06` + `OPT-O01-05`: Made DPAPI, EC P-256 cryptographic key generation, and pairing state initialization lazy off the main thread in `DesktopEngine.kt`, warmed on `Dispatchers.IO` in `assemble()`; trimmed eager taskbar icon rasterization in `DesktopTaskbarBadgeManager.kt`.
6. `OPT-O19-07`: Implemented idle discovery downscaling to `ECO` mode when minimized to tray with caller workload guards (active transfers, calls, PTT) in `DesktopEngine.kt` and `DesktopMain.kt`.
7. `OPT-O20-03`: Replaced synchronous unbuffered console/file log sink with asynchronous background daemon worker thread consuming an `ArrayBlockingQueue(4096)` and writing to an 8 KB `BufferedWriter`.
8. `OPT-O14-06`: Bounded desktop background thread allocation by setting `kotlinx.coroutines.io.parallelism = 16` and scoping `DesktopEngine.scope` to `Dispatchers.IO.limitedParallelism(8)`.
9. `OPT-O24-08`: Implemented OS-level accessibility reduce-motion detection (`DesktopOsReduceMotion.kt`) querying Windows (`win.clientareaAnimation.enabled`) and macOS with TTL caching and property change listener.

### Changed
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt`:
  - Scoped `DisposableEffect(window)` strictly to window lifecycle; captured mutable states (`currentNav`, `currentConversationHeader`, `currentTwoPane`, `currentSelectedChatId`) via `rememberUpdatedState`.
  - Extracted `DesktopSettingsDestination` composable to isolate `radioUiState`, `hubDropped`, `hubSealingKeyState`, `orgLease`, `orgIdentityMismatch`, `hubPreviousLinks`, and storage scanning from shell root.
  - Decoupled `isEnterpriseEnrolled` and `enterpriseDepartment` in `conversationPaneBody`, `FlashCreateGroupSheet`, and `FlashBrowseOrgGroupsSheet`.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopAdaptive.kt`:
  - Wrapped `rememberFlashDesktopWindowSize` calculation in `derivedStateOf` to prevent pixel-by-pixel `DesktopShell` recomposition during window resize.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`:
  - Applied `filter` and `distinctUntilChanged` on `activeTransfers` for `toastTransfers`.
  - Wired `LaunchedEffect(isWindowVisible, windowState.isMinimized) { engine.setWindowActive(...) }`.
  - Replaced synchronous console logging with asynchronous bounded queue and daemon worker thread with JVM shutdown hook.
  - Configured `kotlinx.coroutines.io.parallelism = 16`.
  - Resolved `reduceMotionResolved = effectivePerformanceMode.reduceMotion || DesktopOsReduceMotion.isReduceMotion()`.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopNotificationManager.kt`:
  - Applied `distinctUntilChanged` on `activeTransfers` collection using notification-relevant fields comparator.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`:
  - Made `crypto`, `ephemeralKeyPair`, and `pairing` `by lazy`, pre-warmed on `Dispatchers.IO` in `assemble()`.
  - Bound `engine.scope` concurrency to `Dispatchers.IO.limitedParallelism(8)`.
  - Added `isWindowActive()`, `setWindowActive(active: Boolean)`, and `evaluateDiscoveryActiveState()` with workload guards (`hasActiveTransfer`, `hasActiveCall`, `hasActivePtt`).
  - Added reactive completion listeners in `DesktopEngine.start()` to automatically downscale when active workloads complete while window is inactive.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/EngineProxies.kt`:
  - Added `screenActive` state tracking to `SwitchingDiscovery`.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopTaskbarBadgeManager.kt`:
  - Trimmed eager base icons cache to common sizes (16, 24, 32, 48, 64) and lazy-loaded 96, 128, 256px raster icons. Updated `DesktopTaskbarBadgeManagerTest.kt`.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopOsReduceMotion.kt`:
  - Created desktop accessibility helper detecting OS animation preferences with AWT property change listener and 5s TTL cache.
- `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopDiscoveryDownscaleTest.kt`:
  - Added test suite verifying idle downscaling, active mode restoration, and workload guards.
- `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopOsReduceMotionTest.kt`:
  - Added test suite verifying OS motion preference querying, exception safety, and TTL caching.

### Verification
- `tools\flash-gradle.cmd :desktop:compileKotlinJvm :desktop:jvmTest`:
  - Verified all tests in `:desktop:jvmTest` pass with 0 failures.
- `DesktopDiscoveryDownscaleTest`:
  - Verified idle downscale to `ECO`, restore to preferred mode, and workload guards.
- `DesktopOsReduceMotionTest`:
  - Verified exception safety, query execution, and TTL caching.

### Remaining
- Run Integration Gate 1 and Gate 2 from worktree root.
- Integrator merge into `dev`.

### Next AI
Integrate Stream E commits into `dev` after reviewing caller behavior invariants.
