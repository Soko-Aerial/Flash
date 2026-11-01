# Stream G (Android) — Progress Log

## 2026-11-01 — Wave 3 Optimization: Battery, Startup, and Platform Hardening

### Worked on
Completed full implementation and test verification of Wave 3 Android (G) stream optimization findings:
- **Batch 1 (Battery & Startup):**
  - `OPT-O13-01`: Replaced unconditional perpetual partial CPU wake lock with dynamic, ref-counted, bounded wake lock lease system (`DiscoveryEngineHolder.kt`).
  - `OPT-O13-02`: Implemented fast SQLite outbox and active transfer inspection in `FlashKeepaliveWorker.kt` before headless engine boot.
  - `OPT-O13-03`: Added `FlashTransferState.Verifying` to Android 15 `TimeoutStopPlan.kt` and verified with unit test.
  - `OPT-O13-08`: Replaced `delay(300)` service polling loop in `FlashBackgroundService.kt` with reactive `combine(transfersFlow, networkFlow)` flow.
  - `OPT-O01-02`: Moved synchronous `WorkManager.schedule` and `FlashNotificationManager.initChannels` off main UI thread; removed synchronous storage folder walk from `MainActivity.onCreate`.
  - `OPT-O01-07`: Verified single lazy evaluation of `MediaCodecList` in `AppEngine.kt`.
- **Batch 2 (Lifecycle, Transsion, Discovery & Service Management):**
  - `OPT-O13-05` & `OPT-O10-01`: Wired `FlashDiscovery.setScreenActive` from Android host in `DiscoveryEngineHolder.kt` on `Intent.ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`; guarded so ECO downscale only fires when no calls, transfers, or PTT sessions are active.
  - `OPT-O13-04`: Gated Transsion Tier 3 silent `AudioTrack` playback on `PowerManager.isInteractive == false`, pausing audio DSP power collapse when the screen is active.
  - `OPT-O13-07`: Added 10-minute cooldown backoff latch in `TranssionHiberKeepAlive.kt` upon permanent audio focus loss (`AUDIOFOCUS_LOSS`) to eliminate focus fighting with music players (Spotify).
  - `OPT-O01-04`: Deferred automatic background subnet sweep fallback by 30 seconds (`INITIAL_SWEEP_START_DELAY_MS`) in `DiscoveryEngineHolder.kt` during cold start.
  - `OPT-O13-06`: Implemented idle foreground service shutdown after 30 seconds with 0 peers, 0 transfers, and 0 hub connection in `FlashBackgroundService.kt`, with user opt-in setting `backgroundTransfers` ("Keep listening in the background") preserved per ADR-232.

### Changed
- `DiscoveryEngineHolder.kt`:
  - Added bounded lease mechanism: `acquireWakeLockLease(tag, timeoutMs)`, `updateCpuWakeLockState()`, and 5-min watchdog refresher.
  - Added `_networkFlow` (`StateFlow<FlashNetwork?>`) for reactive observation.
  - Added `Intent.ACTION_SCREEN_OFF` receiver to `registerScreenReceiver`.
  - Added `onScreenOff()`, calling `composite?.setScreenActive(false)` when idle.
  - Added `checkScreenOffDownscale()` to `updatePowerLocksState()` so finishing transfers downscale to ECO if screen is off.
  - Deferred `sweep.start()` by 30 seconds during engine startup.
- `FlashBackgroundService.kt`:
  - Replaced `while (isActive && currentNetwork() == null) delay(300)` with reactive flow collection.
  - Added `evaluateServiceIdleStop` (30s grace timer stopping FGS when idle unless `backgroundTransfers` enabled).
  - Updated `reevaluateKeepAlive()` to invoke `keepAlive?.onAppForegrounded()`.
- `FlashKeepaliveWorker.kt`:
  - Added direct SQLite inspection (`COUNT(*) FROM outbox` and `COUNT(*) FROM transfers WHERE ...`) before engine spin-up; exits immediately with `Result.success()` when queues are empty.
  - Added bounded lease during headless drain runs.
- `TimeoutStopPlan.kt` & `TimeoutStopPlanTest.kt`:
  - Filtered `FlashTransferState.Verifying` alongside `Transferring`.
  - Added test `verifyingRowIsAlsoPausedOnTimeout`.
- `TranssionHiberKeepAlive.kt` & `TranssionHiberKeepAliveTest.kt`:
  - Added `screenInteractiveChecker`, `isScreenInteractive`, and `timeSource`.
  - Gated `track.play()` to screen-off periods; pauses on screen-on (`onScreenStateChanged`).
  - Added `lastPermanentFocusLossMs` and 10-minute cooldown on `AUDIOFOCUS_LOSS`.
  - Added unit tests: `permanent audio focus loss cooldown blocks AUDIO tier until cooldown elapses or foregrounded` and `screen interactive checker reflects screen state`.
- `FlashApplication.kt`:
  - Dispatched `FlashKeepaliveWorker.schedule` via `CoroutineScope(Dispatchers.IO).launch`.
- `MainActivity.kt`:
  - Dispatched `FlashNotificationManager.initChannels` via `lifecycleScope.launch(Dispatchers.Default)`.
  - Removed synchronous `refreshReceivedStorageUsage()` from `onCreate()`.
- `AppEngine.kt`:
  - Mirrored `settingsStore.backgroundTransfers` into `DiscoveryEngineHolder.backgroundTransfers`.

### Verification
- **Compilation Gate:**
  `cmd /c "cd /d E:\FlashWork\opt-android && tools\flash-gradle.cmd :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin"`
  $\rightarrow$ `BUILD SUCCESSFUL in 38s` (exit 0).
- **Unit Test Gate:**
  `cmd /c "cd /d E:\FlashWork\opt-android && tools\flash-gradle.cmd :app:testDebugUnitTest --continue"`
  $\rightarrow$ 90 tests completed, 2 failed (`TransfersUiMapperTest.retryableComesFromTheModelPredicate` and `.aHubRowSaysSoAndOffersNoRetry`, pre-existing ERROR-106 baseline). Exactly 3 new tests added (`TimeoutStopPlanTest` + 2 in `TranssionHiberKeepAliveTest`), all 3 passed.
- **Caller Audit:**
  - `git grep "setScreenActive(" app/` $\rightarrow$ called in `DiscoveryEngineHolder.kt:onScreenOn` and `onScreenOff`.
  - `git grep "onScreenStateChanged(" app/` $\rightarrow$ called in `TranssionHiberKeepAlive.kt` screenReceiver.
  - `git grep "onAppForegrounded(" app/` $\rightarrow$ called in `FlashBackgroundService.kt:reevaluateKeepAlive`.
  - `git grep "acquireWakeLockLease(" app/` $\rightarrow$ called in `FlashKeepaliveWorker.kt`.

### Remaining
- Device testing owed for `PERF-G01` through `PERF-G06` recorded in `logs/streams/android/backlog-additions.md`.

### Next AI / Integrator
Fold log additions into root documentation (`docs/android-platform-notes.md`, `docs/decisions.md`, `docs/testing/TEST-BACKLOG.md`) and run the integration gate.
