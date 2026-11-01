# Stream E (Desktop) — Handoff Document

## Current Branch
`agent/opt-desktop` (in worktree `E:\FlashWork\opt-desktop`)

## Base Commit
Branched from `dev` at `44eed960` (where Waves 0–3 are merged, gate 1 and gate 2 green).

## Commits Landed on `agent/opt-desktop`
1. `61f5833c` `perf(desktop): key DropTarget registration on window lifecycle only (OPT-O19-01)`
2. `6b197407` `perf(desktop): throttle transfer toast and notification collection (OPT-O19-02)`
3. `1018254b` `perf(desktop): derive window size class to prevent resize recomposition thrash (OPT-O19-03)`
4. `eaca6d98` `perf(desktop): decouple settings and telemetry flows from desktop shell root (OPT-O03-10)`
5. `8eacea68` `perf(desktop): make crypto and pairing init lazy off main thread (OPT-O19-06, OPT-O01-05)`
6. `0217eba8` `perf(desktop): idle discovery downscaling to ECO when minimized with workload guards (OPT-O19-07)`
7. `17610e8d` `perf(desktop): asynchronous buffered desktop log writer (OPT-O20-03)`
8. `4c402d26` `perf(desktop): bound background dispatcher thread pools (OPT-O14-06)`
9. `cd9f96bd` `feat(desktop): honor OS reduce-motion accessibility settings (OPT-O24-08)`

## Summary of Findings Addressed
- **OPT-O19-01**: Keyed `DisposableEffect(window)` on window lifecycle only; captured dynamic states via `rememberUpdatedState`, eliminating repeated recursive AWT component tree walks on navigation or conversation change.
- **OPT-O19-02**: Throttled `activeTransfers` collection in `DesktopMain.kt` (filtered to Offered/Failed-retryable with `distinctUntilChanged`) and debounced `DesktopNotificationManager` updates with a structural equality comparator on notification-relevant fields.
- **OPT-O19-03**: Wrapped `rememberFlashDesktopWindowSize` calculation in `derivedStateOf`, preventing root `DesktopShell` recomposition on every pixel of window drag resizing.
- **OPT-O03-10**: Extracted `DesktopSettingsDestination` in `DesktopShell.kt`, isolating 8 settings StateFlows (`radioUiState`, `hubDropped`, `hubSealingKeyState`, `orgLease`, `orgIdentityMismatch`, `hubPreviousLinks`, storage scanning, badge counts) from the shell root. Decoupled enterprise credentials into child sheets.
- **OPT-O19-06 + OPT-O01-05**: Made `crypto`, `ephemeralKeyPair`, and `pairing` lazy in `DesktopEngine.kt`, pre-warmed on `Dispatchers.IO` during `assemble()`; trimmed eager taskbar badge icon rasterization to common sizes (16–64px) with lazy on-demand rasterization for larger sizes in `DesktopTaskbarBadgeManager.kt`.
- **OPT-O19-07**: Wired `FlashDiscovery.setScreenActive` from `DesktopMain.kt` via `engine.setWindowActive(!isMinimized && isWindowVisible)` with workload guards in `DesktopEngine.kt` (active transfer `Transferring`/`Verifying`, active call `!= ENDED`, active PTT `!is Idle`). When active workloads complete while minimized, discovery automatically downscales to `ECO`. Restoring window restores user preferred mode.
- **OPT-O20-03**: Replaced synchronous unbuffered console logging in `DesktopMain.kt` with a background daemon flusher thread consuming an `ArrayBlockingQueue(4096)` and writing to an 8 KB `BufferedWriter`, with a JVM shutdown hook.
- **OPT-O14-06**: Bounded desktop background thread allocation: set `kotlinx.coroutines.io.parallelism = 16` and bounded `DesktopEngine.scope` concurrency via `Dispatchers.IO.limitedParallelism(8)`.
- **OPT-O24-08**: Implemented `DesktopOsReduceMotion.kt` querying Windows `win.clientareaAnimation.enabled` (and macOS `apple.awt.reduceMotion`) with AWT property change listener and 5s TTL cache, wired into `DesktopMain.kt` motion resolution.

## Caller Behavior & Invariant Audits
- **Workload Guards (OPT-O19-07)**: Callers own the guard. Downscaling discovery during an active transfer or call drops peer sessions. `DesktopEngine.evaluateDiscoveryActiveState()` strictly blocks `ECO` downscale while any transfer is in `Transferring` or `Verifying`, a call is active, or PTT is engaged.
- **ADR-298 Invariant**: Hub presence REST polling is NOT suppressed or slowed down when window is inactive/minimized. `EnterpriseHub` continues regular 15-second polling regardless of window state.
- **Thread Safety on First Frame (OPT-O19-06)**: Cryptographic properties remain thread-safe (`by lazy`) and are pre-warmed in `assemble()` on `Dispatchers.IO` before discovery start, while allowing Compose Desktop window to render its initial loading skeleton immediately.

## Recommended Next Steps for Integrator
1. Verify integration gates 1 and 2 pass on worktree `E:\FlashWork\opt-desktop`.
2. Merge `agent/opt-desktop` into `dev`.
3. Stop Gradle daemons.
