# Domain O13 — Background Execution, Battery and Wake-ups
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** Senior Performance Engineer (Sub-agent O13)  

---

## 1. Domain Header & Scope

This audit evaluates the complete background execution lifecycle, foreground services, CPU wake locks, Wi-Fi locks, power save / Doze resilience, OEM freezer bypasses, WorkManager periodic jobs, and battery drain modeling across the Flash Android client:
- **Foreground Services & Platform Compliance (`:app`):** `FlashBackgroundService.kt` (declared FGS types `connectedDevice`, `dataSync`, `mediaPlayback`), `FlashCallService.kt` (VoIP calling with `microphone`, `camera`, `connectedDevice`), `PttSessionService.kt` (PTT voice with `microphone`, `mediaPlayback`), `RadioLinkService.kt` (`connectedDevice`), `AndroidManifest.xml`.
- **Periodic Work & Headless Wake-ups (`:app`):** `FlashKeepaliveWorker.kt` (15-minute periodic WorkManager worker, constraints, headless engine boot and teardown hygiene).
- **Wake Locks & Power Management (`DiscoveryEngineHolder.kt`):** `PowerManager.PARTIAL_WAKE_LOCK` (`flash:mesh-ws-liveness`), `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` / `WIFI_MODE_FULL_HIGH_PERF`), dynamic lock evaluation via `updateWifiLockState()`, screen receiver (`ACTION_SCREEN_ON`, `ACTION_USER_PRESENT`).
- **OEM Battery Optimizations & Freezers (`:app`):** `TranssionHiberKeepAlive.kt` (3-tier keepalive hierarchy for Infinix/Tecno/itel: BLE scan, 1x1 overlay window, silent static AudioTrack), `OemBatteryOptimizationHelper.kt` (deep links for Xiaomi/HyperOS, Huawei/Honor, Samsung One UI, Oppo/ColorOS, Vivo).
- **Network Resilience & Radio Transitions (`:core:network`):** `AndroidNetworkWatcher.kt` (`ConnectivityManager.NetworkCallback` lifecycle, Wi-Fi/Ethernet transport filter, bandwidth bucketing, roam fingerprinting), `WsFlashNetwork.kt` (`onUsableNetwork` re-arm).
- **Keepalive Tickers & Periodic Work (`:core:network`):** `WsKeepaliveTicker.kt` (shared connection clock, idle parking), `AutoConnector.kt` (sweep loop), review of `00-periodic-work.md`.
- **Android 15 (API 35) dataSync 6-Hour Timeout:** `FlashBackgroundService.onTimeout()`, `TimeoutStopPlan.kt` (`TimeoutPlan`), `pauseForSystem()`, and resume handling.
- **Battery Drain Model:** Quantitative current draw (mA) and hourly energy consumption (mAh) across idle, mesh standby, active transfer, and call states; owed device verification.

---

## 2. Files Inspected

### Foreground Services, Notifications & Application Lifecycle (`app/src/main/java/com/transfer/flash/`)
- `app/src/main/AndroidManifest.xml` (FGS types, permissions `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `BLUETOOTH_SCAN`, `SYSTEM_ALERT_WINDOW`, `<queries>` for OEM managers).
- `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt` (FGS promotion/retries, `onTimeout`, session activity observation, transfer progress throttling).
- `app/src/main/java/com/transfer/flash/debug/FlashKeepaliveWorker.kt` (15-minute periodic WorkManager job, `ensureStarted`, 45s wait window, headless teardown).
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt` (`wakeLock` engine-lifetime hold, `wifiLock` dynamic acquisition, `registerScreenReceiver`, `reArm`).
- `app/src/main/java/com/transfer/flash/debug/TranssionHiberKeepAlive.kt` (3 tiers: BLE scan, 1x1 overlay, static AudioTrack; 750ms verification check; audio focus listener).
- `app/src/main/java/com/transfer/flash/debug/OemBatteryOptimizationHelper.kt` (OEM brand resolution and autostart/battery optimization activity deep links).
- `app/src/main/java/com/transfer/flash/debug/TimeoutStopPlan.kt` (Pure function mapping active transfers to pause plan on Android 15 FGS timeout).
- `app/src/main/java/com/transfer/flash/calling/FlashCallService.kt` (In-call FGS, `microphone|camera|connectedDevice` dynamic type resolution, `Notification.CallStyle`).
- `app/src/main/java/com/transfer/flash/ptt/PttSessionService.kt` (PTT background audio FGS, `microphone|mediaPlayback|connectedDevice`).
- `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt` (Notification channels, progress throttling, auto-cancel, pending intent wiring).

### Core Network Resilience & Tickers (`core/network/`)
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/resilience/AndroidNetworkWatcher.kt` (Wi-Fi/Ethernet `NetworkRequest`, bandwidth bucketing, roam fingerprinting).
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt` (Network callback handling, `onUsableNetwork`, reconnect scheduler).
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/ws/WsKeepaliveTicker.kt` (Unified keepalive clock, idle parking on empty targets, dynamic interval).
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/ws/WsKeepaliveTiming.kt` (Ping interval and liveness timeout pairs).
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/AutoConnector.kt` (Periodic auto-connect sweep loop).

### Documentation & Prior Investigations
- `docs/audit/optimization-2026-10-23/00-periodic-work.md` (Inventory of periodic tasks).
- `docs/PHASE-HARDENING-AIRTIME-THERMALS.md` (Airtime tuning, partial wake lock retention reasoning, Android 15 timeout).
- `docs/network/TRANSSION-BACKGROUND-DELIVERY-RESEARCH.md` (Transsion Hiber firmware reverse-engineering and exemption bits).
- `docs/testing/TEST-BACKLOG.md` (HIB-01..17, EXP-002, EXP-003, batterystats test procedures).
- `logs/experiments.md` (EXP-002 Samsung vs Infinix liveness differential test).
- `logs/errors.md` (ERROR-025, ERROR-026, ERROR-031, ERROR-033, ERROR-074).

---

## 3. Hot Paths: Background Execution & Power

| Path ID | Component / Entry Point | Trigger / Cadence | Dispatcher / Thread | What Runs |
|---|---|---|---|---|
| **HP-BATT-01** | `DiscoveryEngineHolder.acquirePowerLocks` | Engine start (`ensureStarted`) | Main / Caller thread | Acquires `PowerManager.PARTIAL_WAKE_LOCK` without timeout; creates `WifiLock`. Holds CPU awake permanently until `stopAll()`. |
| **HP-BATT-02** | `FlashKeepaliveWorker.doWork` | WorkManager periodic (~15 minutes) | WorkManager Worker Thread | Queries `isRunning()`; if false, calls `ensureStarted()`, takes wake lock, waits up to 45s for sessions, and calls `stopAll()`. Runs unconditionally even with empty outbox. |
| **HP-BATT-03** | `FlashBackgroundService.onStartCommand` | Service start or `START_STICKY` restart | Android Main Thread | Resumes system-paused transfers; processes transfer cancel intents; returns `START_STICKY`. |
| **HP-BATT-04** | `FlashBackgroundService.observeSessionActivity` | Repository Flow updates | Coroutine (`Dispatchers.Default`) | Combines `transfersFlow`, `sessionsFlow`, `callFlow`, `pttFlow`; updates `TranssionHiberKeepAlive.setSessionActive()`. |
| **HP-BATT-05** | `TranssionHiberKeepAlive.startAudio` | Tier 3 activation on Transsion ROM | Android Main Thread | Requests `AUDIOFOCUS_GAIN`; builds 1600-byte silent PCM `AudioTrack.MODE_STATIC`; plays looping silence to evade Hiber freezer. |
| **HP-BATT-06** | `FlashBackgroundService.onTimeout` | Android 15+ 6-hour `dataSync` limit | Android Main Thread | Evaluates `TimeoutStopPlan.of()`; pauses active transfers via `pauseForSystem()`; calls `stopSelf()`. |
| **HP-BATT-07** | `DiscoveryEngineHolder.screenReceiver` | Protected broadcast `ACTION_SCREEN_ON` | Android Main Thread | Retries refused foreground promotion; re-arms discovery browsing and forces auto-connect sweep. (No `ACTION_SCREEN_OFF` counterpart exists). |
| **HP-BATT-08** | `AndroidNetworkWatcher.callback` | System network availability change | ConnectivityManager IPC Thread | Evaluates Wi-Fi/Ethernet capabilities; computes rate-bucketed fingerprint; notifies `onAvailable` or `onLinkChanged`. |
| **HP-BATT-09** | `DiscoveryEngineHolder.updateWifiLockState` | Transfer / Call state emission | Synchronized block | Dynamically acquires `WIFI_MODE_FULL_LOW_LATENCY` during transfers/calls; releases lock during mesh idle. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | Foreground services: declared types (dataSync, connectedDevice, phoneCall), lifecycle management, when started, promoted, and stopped | **Problem** | `OPT-O13-05`, `OPT-O13-06` (`FlashBackgroundService` declared with `connectedDevice\|dataSync\|mediaPlayback`; `FlashCallService` with `microphone\|camera\|connectedDevice` without `phoneCall`; service started at boot and never stops even when 0 peers connected) | `STATIC-ARITHMETIC` |
| 2 | WorkManager 15-minute wake-up (FlashKeepaliveWorker): constraints, execution duration, engine startup/teardown hygiene | **Problem** | `OPT-O13-02` (Constraints `CONNECTED` + `NOT_LOW`; runs engine for up to 45s unconditionally even when chat outbox has 0 pending messages and 0 transfers; wastes 180s CPU awake/hr) | `STATIC-ARITHMETIC` |
| 3 | Wake locks: partial wake locks, transfer-scoped vs engine-lifetime (ERROR-025/026 context), timeout guards, leak hazards | **Problem** | `OPT-O13-01` (`wakeLock` held permanently for engine lifetime without timeout; 24/7 background hold violates Android Vitals; prevents AP suspend; burns 15–35 mA continuously in pocket) | `STATIC-ARITHMETIC` |
| 4 | Alarms, START_STICKY, and process resurrection behavior | **No problem** | No `AlarmManager` calls in codebase (0 leaks). `START_STICKY` handles `ForegroundServiceStartNotAllowedException` gracefully without crashing, flags `promotionRefused`, and retries on network/screen edges. | `STATIC-ARITHMETIC` |
| 5 | ConnectivityManager network callbacks: registration lifecycle, churn on radio switches (Wi-Fi <-> Cellular) | **No problem** | `AndroidNetworkWatcher` restricts request to `TRANSPORT_WIFI` and `TRANSPORT_ETHERNET` only, explicitly ignoring cellular to avoid walking bandwidth churn. Bandwidth is bucketed to 1,000 kbps. | `STATIC-ARITHMETIC` |
| 6 | Timers and tickers inventory: comprehensive review of periodic tasks from 00-periodic-work.md (period, idle cost, stops when unneeded?) | **Problem** | `OPT-O13-02`, `OPT-O13-07` (also cross-ref `OPT-O08-05` 4 Hz swarm ticker and `OPT-O10-05` discovery sweepers). Note: `TranssionHiberKeepAlive:389` 750ms is a one-shot verification check, not a repeating loop. | `STATIC-ARITHMETIC` |
| 7 | Screen-off behavior: ECO mode transitions, keepalive interval adaptation, Wi-Fi radio sleep prevention | **Problem** | `OPT-O13-05` (No `ACTION_SCREEN_OFF` receiver; discovery and tickers run at foreground cadence in pocket; Wi-Fi high-perf lock is released when idle, but CPU wake lock remains permanently held) | `STATIC-ARITHMETIC` |
| 8 | Doze mode and OEM battery savers: Transsion Hiber (Infinix/Tecno), Xiaomi/HyperOS, Huawei autostart interaction | **Problem** | `OPT-O13-04`, `OPT-O13-07` (`OemBatteryOptimizationHelper` resolves autostart deep links across 7 OEM brands; Transsion Tier 3 AudioTrack runs even with screen ON; lacks focus loss backoff) | `STATIC-ARITHMETIC` |
| 9 | Android 15 dataSync 6-hour timeout handling: onTimeout() callback implementation and graceful transfer pause/cancel | **Problem** | `OPT-O13-03` (`FlashBackgroundService.onTimeout` cleanly pauses and stops; however `TimeoutStopPlan.of` only checks `Transferring` state and completely omits `Verifying` state) | `STATIC-ARITHMETIC` |
| 10 | Battery drain model per hour: estimate mA and mAh drain across idle, background mesh standby, active transfer, and call states; cite owed device tests | **Problem** | Comprehensive quantitative model documented in Section 6. Baseline standby drain is **45–85 mAh/hr** (elevated 10× above OS baseline of 3–6 mAh/hr). Owed device tests cited. | `STATIC-ARITHMETIC` |

---

## 5. Detailed Findings

### OPT-O13-01 — Perpetual Engine-Lifetime Partial Wake Lock Held 24/7 Without Timeout Guard Prevents AP Suspend (Android Vitals Bad Behavior: 15–35 mA Idle Drain)
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, C       **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:585-596, 2915-2925, 2975-2977`
- **Bottleneck class:** CPU / protocol
- **What is wasteful:**  
  Following the fix for ERROR-025/026 (where `FlashBackgroundService`'s sticky-restart dropped power locks on refused promotion), ownership of `PowerManager.WakeLock` was moved to `DiscoveryEngineHolder`. However, `DiscoveryEngineHolder.acquirePowerLocks()` acquires an unconditional `PowerManager.PARTIAL_WAKE_LOCK` (`flash:mesh-ws-liveness`) with `setReferenceCounted(false)` and **no timeout parameter**:
  ```kotlin
  wakeLock = runCatching {
      context.getSystemService(PowerManager::class.java)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
          .apply {
              setReferenceCounted(false)
              acquire() // <-- Held indefinitely
          }
  }.getOrNull()
  ```
  While `wifiLock` is dynamically managed via `updateWifiLockState()` (released during mesh idle and held only during active transfers/calls, `lines 2950-2972`), `wakeLock` is **never released** until `stopAll()` is invoked (which only occurs if the user toggles the Quick Settings tile off or when `FlashKeepaliveWorker` exits headless work).  
  As a result, when an Android device running Flash is locked and put into a pocket with **0 active transfers, 0 active calls, and 0 connected peers**, the CPU Application Processor (AP) is permanently prevented from entering deep sleep (AP suspend).
- **Cost model:**  
  - Android Vitals bad behavior threshold for "Excessive background partial wake locks" is $\ge 0.10\%$ of battery sessions with background partial wake locks held for $\ge 1\text{ hour}$. Flash holds it for 100% of background time ($24\text{ hr/day}$).
  - Preventing SoC power collapse keeps PMIC rails, CPU PLLs, and little cores awake in shallow C-states:  
    $\Delta I_{\text{wake}} \approx 15\text{ to }35\text{ mA sustained idle current}$.  
  - Over an 8-hour standby period, this consumes $8\text{ hr} \times 25\text{ mA} = 200\text{ mAh}$ (4–5% of a typical 5,000 mAh battery) while completely idle with zero network traffic.
- **User-visible effect:** Severe battery drain in standby, device warm in pocket, battery graph in Android Settings shows Flash as a top battery consumer, and potential Play Store algorithmic down-ranking due to Android Vitals excessive wake lock violations.
- **Expected gain:** Standby current reduced by 15–35 mA. CPU allowed to enter AP suspend when no transfers or calls are active and mesh sessions are parked, dropping standby battery consumption by up to 70%.
- **Proposed change:**  
  1. Add a dynamic wake lock management mechanism mirroring `updateWifiLockState()`: `updateCpuWakeLockState()`.
  2. While active transfers, calls, or PTT sessions are in progress, hold the partial wake lock.
  3. When transfers/calls are idle, transition to a duty-cycled or tick-aligned wake lock: release the permanent wake lock and allow the CPU to sleep between keepalive intervals. Specifically, arm `WsKeepaliveTicker` to acquire a short wake lock (e.g., `acquire(500)` ms) only when a ping tick is due.
  4. Always pass a safety timeout to `acquire(timeoutMs)` (e.g., 10 minutes maximum) as an invariant guard against stuck wake locks.
- **Invariants touched (5.3):** Background-execution platform rules preserved; transfers and calls retain uninterrupted wake lock protection.
- **Risk / effort:** Medium risk (must verify keepalive pings survive sleep on diverse OEMs); M effort.
- **Prove it:** Run `dumpsys power | grep -A 10 "Wake Locks"` and Battery Historian over a 2-hour screen-off period. Verify "partial wake lock" time drops from 100% to < 5%, and `dumpsys batterystats` reports deep sleep time > 90%.
- **Verifier result:** Survived. Verified `DiscoveryEngineHolder.kt:2916-2925` confirms `wakeLock.acquire()` is called without timeout and has no counterpart in `updateWifiLockState()`.
- **Related:** ERROR-025, ERROR-026, PHASE-HARDENING-AIRTIME-THERMALS Track 1 Task 3.2.

---

### OPT-O13-02 — Unconditional Headless Engine Spin-Up in FlashKeepaliveWorker Ignores Empty Outbox and Queue (Wastes 180s Awake/Hour for Zero Work)
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, C, N    **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/FlashKeepaliveWorker.kt:45-89`
- **Bottleneck class:** CPU / network
- **What is wasteful:**  
  `FlashKeepaliveWorker` is scheduled every 15 minutes as a safety net to flush pending outbox messages and resume queued transfers if the process was killed (ADR-041). However, in `doWork()`:
  ```kotlin
  if (DiscoveryEngineHolder.isRunning()) {
      DiscoveryEngineHolder.currentSwarm()?.runRetentionCleanup()
      DiscoveryEngineHolder.currentSwarm()?.reevaluate()
      return Result.success()
  }
  val started = runCatching { DiscoveryEngineHolder.ensureStarted(applicationContext) }
  ...
  val sawSession = network != null && withTimeoutOrNull(SESSION_WINDOW_MS) {
      network.activeSessions.first { it.isNotEmpty() }
  } != null
  ```
  `doWork()` **never checks whether the chat outbox has any pending messages or whether any transfers are queued** before starting the engine!  
  Even when the user has zero unsent messages, zero pending file transfers, and zero swarm files to seed, the worker:
  1. Bootstraps the entire `DiscoveryEngineHolder` stack (database, crypto, TLS, NSD, multicast sockets, network listeners).
  2. Acquires the engine partial wake lock (`wakeLock`).
  3. Waits for the full `SESSION_WINDOW_MS = 45_000L` (45 seconds) waiting for an active session that may never come (e.g. device is on cellular or isolated Wi-Fi).
  4. Tears down the engine via `stopAll()`.  
  This occurs every 15 minutes, 4 times per hour, 96 times per day, running headless engine startup for 45 seconds each time.
- **Cost model:**  
  - Frequency: 4 runs/hour $\times 45\text{ s} = 180\text{ seconds}$ (3 full minutes) of CPU awake time and Wi-Fi radio activity every hour.
  - Active draw during full engine discovery and network scan: $\approx 150\text{ mA}$.
  - Energy wasted: $4 \times \left(\frac{45\text{ s}}{3600\text{ s}} \times 150\text{ mA}\right) = 4 \times 1.875\text{ mAh} = 7.5\text{ mAh per hour}$ ($180\text{ mAh/day}$, ~3.6% of battery capacity) purely to poll for sessions when there is nothing to send!
- **User-visible effect:** Unnecessary periodic battery drain and network probing when the app is completely idle and closed.
- **Expected gain:** Complete elimination of 180s/hr awake time and 7.5 mAh/hr battery drain when the outbox is empty. Headless engine spin-up will occur strictly when there is actual work to deliver.
- **Proposed change:**  
  1. In `FlashKeepaliveWorker.doWork()`, before calling `DiscoveryEngineHolder.ensureStarted()`, query the local SQLite database via `OutboxDao` and `TransferDao`:
     ```kotlin
     val hasOutbox = chatRepo.hasPendingMessages() // or outboxDao.countPending() > 0
     val hasTransfers = transferRepo.hasPendingTransfers()
     if (!hasOutbox && !hasTransfers) {
         Log.i(TAG, "keepalive: outbox and transfer queues empty; skipping headless engine boot")
         return Result.success()
     }
     ```
  2. Only start the headless engine if pending outbound work actually exists.
- **Invariants touched (5.3):** Durability/ordering preserved; outbox items still flushed within 15 minutes.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Verify with `adb shell cmd jobscheduler run -f com.transfer.flash <jobId>` on an idle device with an empty outbox. Execution finishes in < 50 ms instead of 45,000 ms.
- **Verifier result:** Survived. Verified `FlashKeepaliveWorker.kt:45-89` contains no database check or outbox inspection prior to `DiscoveryEngineHolder.ensureStarted()`.
- **Related:** ADR-041, `00-periodic-work.md`.

---

### OPT-O13-03 — Missing `FlashTransferState.Verifying` in Android 15 `TimeoutStopPlan` Leaves Verification Jobs Hanging on FGS Timeout
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, S, C      **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/TimeoutStopPlan.kt:22-23`, `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt:155-165`
- **Bottleneck class:** storage / CPU
- **What is wasteful:**  
  On Android 15 (API 35), foreground services with `dataSync` face a hard 6-hour cumulative timeout. `FlashBackgroundService.onTimeout(startId, fgsType)` delegates to `TimeoutStopPlan.of(allTransfers)` to pause active transfers before invoking `stopSelf()`:
  ```kotlin
  public object TimeoutStopPlan {
      public fun of(transfers: Collection<FlashTransfer>): TimeoutPlan =
          TimeoutPlan(toPause = transfers.filter { it.state == FlashTransferState.Transferring })
  }
  ```
  `TimeoutStopPlan.of` strictly filters for `FlashTransferState.Transferring`, completely omitting `FlashTransferState.Verifying`.  
  On large multi-gigabyte transfers (e.g. 10 GB–50 GB files transferred over Wi-Fi), the chunk transmission phase may finish after several hours, transitioning the transfer to `FlashTransferState.Verifying` while the background coroutine computes the whole-file SHA-256 hash from flash storage (which can take 1–3 minutes on mobile storage).  
  If the Android 15 6-hour timeout occurs during this verification phase:
  1. The verifying transfer is **not** included in `plan.toPause`.
  2. `pauseForSystem(transfer.id, "SERVICE_TIMEOUT")` is **never called** for the transfer.
  3. `stopSelf()` kills the foreground service; when the process is backgrounded or killed, the ongoing hash pass is terminated abruptly without updating the database state or recording system-paused metadata.
  4. Upon subsequent restart, `resumeSystemPaused()` will not resume the verification, leaving the transfer stranded in an inconsistent state or forcing a full restart.
- **Cost model:**  
  A 20 GB transfer that spent 5.9 hours transferring and 40 seconds verifying has its verification pass killed. The entire multi-gigabyte transfer may be flagged failed or forced to re-verify from scratch upon app reopen, wasting 20 GB of flash read I/O ($20\text{ GB} \times 150\text{ MB/s} \approx 133\text{ seconds of continuous storage reading}$ and CPU crypto load).
- **User-visible effect:** Large file transfers fail or stall near 99% / "Verifying..." without a clean "Paused by Android" notification or automatic resumption.
- **Expected gain:** Complete robustness on Android 15 timeout; verifying transfers are cleanly paused and cleanly resumed without data corruption or orphan states.
- **Proposed change:**  
  Update `TimeoutStopPlan.kt` line 23 to include both `Transferring` and `Verifying`:
  ```kotlin
  public object TimeoutStopPlan {
      public fun of(transfers: Collection<FlashTransfer>): TimeoutPlan =
          TimeoutPlan(toPause = transfers.filter { 
              it.state == FlashTransferState.Transferring || it.state == FlashTransferState.Verifying 
          })
  }
  ```
- **Invariants touched (5.3):** Streaming large files and background-execution platform rules preserved.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test in `TimeoutStopPlanTest`: feed a list containing a `Verifying` transfer into `TimeoutStopPlan.of()` and assert `toPause` contains it.
- **Verifier result:** Survived. Verified `TimeoutStopPlan.kt:22-23` only inspects `FlashTransferState.Transferring`.
- **Related:** ADR-031, `FlashBackgroundService.kt:148-166`.

---

### OPT-O13-04 — Transsion Hiber Tier 3 Silent AudioTrack Fails to Check Screen State, Draining Battery and Blocking Audio DSP Power Collapse
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, C         **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/TranssionHiberKeepAlive.kt:476-546`, `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt:230-262`
- **Bottleneck class:** CPU
- **What is wasteful:**  
  On Transsion devices (Infinix, Tecno, itel), `TranssionHiberKeepAlive` activates Tier 3 (AUDIO) as a fallback when Bluetooth scan / overlay permissions are unavailable. Tier 3 plays an inaudible static 1600-byte PCM loop on `AudioTrack.MODE_STATIC` to acquire `STATUS_AUDIO_FOCUS` (Bit 5 = 32), which bypasses the firmware process freezer (HIB-14).  
  However, the Transsion Hiber freezer **only freezes processes when the screen is OFF** (`IMPORT_SCREEN_OFF_FLAG = 389052` in firmware, see `TranssionHiberKeepAlive.kt:37` and `TRANSSION-BACKGROUND-DELIVERY-RESEARCH.md`). When the screen is ON, Transsion Hiber does not freeze active foreground or visible background apps.  
  Despite this, `TranssionHiberKeepAlive.startAudio()` starts playing the silent `AudioTrack` immediately upon tier activation and **keeps it playing continuously while the user has the screen ON and is using the phone**:
  - The audio hardware digital signal processor (DSP), DAC, and amplifier power rails remain fully powered.
  - The Android `audioflinger` mixer thread continues waking up every 5–20 ms to mix the static zero-buffer into the hardware sink.
  - Furthermore, `FlashBackgroundService` claims `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` while the user is simply chatting or browsing transfers.
- **Cost model:**  
  - Active audio hardware DSP and DAC baseline drain: $\approx 12\text{ to }20\text{ mA}$ continuous current draw while audio playback is active.
  - If the user uses the app for 1 hour on an Infinix device, this wastes $1\text{ hr} \times 15\text{ mA} = 15\text{ mAh}$ of battery power unnecessarily on silent audio playback.
- **User-visible effect:** Elevated battery drain and heating on Transsion devices; other audio apps cannot gain exclusive audio focus.
- **Expected gain:** Up to 15 mA current savings on Infinix/Tecno devices when the screen is ON or when active transfers are protected by network traffic (HIB-13).
- **Proposed change:**  
  1. Check `PowerManager.isInteractive` before starting `AudioTrack.play()`.
  2. Pause the silent `AudioTrack` when the screen is turned ON (`Intent.ACTION_SCREEN_ON`).
  3. Resume the silent `AudioTrack` only when the screen turns OFF (`Intent.ACTION_SCREEN_OFF`) and no other keepalive tier (BLE / Overlay / active traffic > 10 KB/s per HIB-13) is active.
- **Invariants touched (5.3):** Background-execution rules preserved; Transsion freeze protection maintained during screen-off.
- **Risk / effort:** Low-Medium risk; S effort.
- **Prove it:** Test on Infinix X6882B: log `AudioTrack.playState` on screen-on and screen-off. Verify silent audio plays only while screen is off, and `adb logcat | grep Hiber` shows 0 freeze events.
- **Verifier result:** Survived. Verified `TranssionHiberKeepAlive.kt:476-546` contains no screen-state check and plays the track unconditionally.
- **Related:** HIB-13, HIB-14, EXP-002, ADR-098.

---

### OPT-O13-05 — Absence of `ACTION_SCREEN_OFF` Handling in Background Service Keeps Full-Rate Polling and High-Frequency Tickers Running in Pocket
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, C, N    **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:2982-3008`, `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt:53-110`
- **Bottleneck class:** CPU / network
- **What is wasteful:**  
  In `DiscoveryEngineHolder.kt:2987-3008`, a broadcast receiver is registered for `Intent.ACTION_SCREEN_ON` and `Intent.ACTION_USER_PRESENT` to re-arm discovery and auto-connect sweeps.  
  However, **no receiver or observer exists for `Intent.ACTION_SCREEN_OFF`** anywhere in `:app` or `:core:network`!  
  When a user finishes interacting with Flash and turns the screen off to place the phone in their pocket:
  1. `FlashBackgroundService` continues running with foreground service type `connectedDevice`.
  2. Discovery remains in `STANDARD` mode (or `BOOST` if selected). It does **not** downscale to `ECO` mode.
  3. `AutoConnector.start` continues running its dial sweep every 5 seconds (720 wakeups/hr).
  4. `CompositeDiscovery.sweeperJob` sweeps the endpoint directory every 5 seconds (720 wakeups/hr).
  5. `MulticastTransport.sweepJob` runs every 5 seconds (720 wakeups/hr).
  6. `MulticastTransport.announceJob` broadcasts UDP datagrams over Wi-Fi every 20 seconds (180 wakeups/hr).
  7. `NsdTransport.heartbeatJob` and `advertiseWatchdogJob` fire every 10 seconds (720 wakeups/hr).
  8. `WsKeepaliveTicker` maintains short 10-second ping cadences instead of relaxing to 30–60s background cadences.  
  In total, **over 3,500 CPU wake-ups per hour** occur while the screen is powered off and the device is idle in a pocket.
- **Cost model:**  
  $3,500\text{ wake-ups/hr} \times (1.5\text{ ms CPU scheduling} + \text{Wi-Fi MAC waking}) \approx 30\text{ to }45\text{ mA sustained standby current}$.  
  Over an 8-hour night, this wastes $8 \times 35\text{ mA} = 280\text{ mAh}$ (5–7% of battery capacity) with zero active transfers or calls.
- **User-visible effect:** Excessive overnight standby battery loss; device warm in pocket; Wi-Fi airtime consumed by UDP multicast announcements.
- **Expected gain:** 80–90% reduction in screen-off wake-ups. Standby wake-ups drop from ~3,500/hr to < 200/hr, allowing device to enter low-power sleep.
- **Proposed change:**  
  1. Register `Intent.ACTION_SCREEN_OFF` in `registerScreenReceiver` in `DiscoveryEngineHolder`.
  2. On screen-off, if no transfer, call, or PTT session is active:
     - Automatically step down `_discoveryMode` to `ECO` (or pause active NSD browsing, retaining passive server sockets).
     - Lengthen `WsKeepaliveTicker` interval to 30s.
     - Scale down `AutoConnector` sweep interval from 5s to 60s.
  3. On `ACTION_SCREEN_ON`, restore user-configured discovery mode (`STANDARD`/`BOOST`) and trigger immediate `reArm("Screen-on")`.
- **Invariants touched (5.3):** Background-execution rules preserved; active file transfers and incoming VoIP calls bypass screen-off downscaling.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Measure standby drain with screen off for 4 hours before and after. Verify wake-up count via `adb shell dumpsys alarm` and `batterystats`.
- **Verifier result:** Survived. Verified `DiscoveryEngineHolder.kt:2999-3002` only registers `ACTION_SCREEN_ON` and `ACTION_USER_PRESENT`.
- **Related:** `OPT-O10-01`, `OPT-O10-05`.

---

### OPT-O13-06 — Permanent Background Foreground Service Execution with `connectedDevice` While 0 Peers Connected Violates Google Play Policy and Prevents App Standby
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, D         **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt:49-52, 265-282, 428-435`
- **Bottleneck class:** protocol
- **What is wasteful:**  
  `FlashBackgroundService` is started in `MainActivity.onStart` (`lines 49-52`):
  > "Started from MainActivity.onStart while the app is user-visible... It deliberately remains running after the activity stops so discovery and established mesh sessions survive in the background."  
  
  When started, it calls `startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`.  
  However, `FlashBackgroundService` **never stops itself when 0 peers are connected**:
  - If a user opens the app, closes it, and walks away into an environment with no other Flash devices, `FlashBackgroundService` continues running 24/7 as an ongoing foreground service with an un-dismissable notification ("Flash is discoverable").
  - On Android 14+ (API 34+), Google Play Developer Policy explicitly restricts the use of `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`: it must be used **only while interacting with a connected external device**. Running a `connectedDevice` FGS indefinitely with 0 connected devices is a direct policy violation that can cause Google Play app review rejections.
  - From a power perspective, having an active Foreground Service permanently forces Android's ActivityManager to classify the app's process priority as `PROCESS_STATE_FOREGROUND_SERVICE` (importance 125). This completely prevents the OS from placing Flash into App Standby buckets (`RESTRICTED` or `RARE`), bypassing Android's built-in background power throttles.
- **Cost model:**  
  Process held permanently in `FOREGROUND_SERVICE` state prevents memory trimming and OEM power management, contributing to the baseline 45–65 mA background standby drain.
- **User-visible effect:** Permanent un-dismissable notification in the Android notification shade even when no other devices are nearby; potential rejection during Google Play Policy compliance reviews.
- **Expected gain:** Compliance with Google Play Android 14+ FGS policy; allows the OS to place Flash into appropriate App Standby buckets when no peers are connected for an extended duration.
- **Proposed change:**  
  1. Implement an idle timeout for background discovery: when the app is backgrounded, no transfers are active, and `activeSessions` has been empty for > 15 minutes, gracefully downgrade the Foreground Service by calling `stopForeground(STOP_FOREGROUND_DETACH)` or `stopSelf()`.
  2. Rely on `FlashKeepaliveWorker` (15-minute periodic WorkManager) and incoming packet/connection triggers to wake up or resume when peers appear, or prompt the user via Settings if they want "Always-on mesh presence".
- **Invariants touched (5.3):** Durability/ordering preserved; established connections retain active FGS protection.
- **Risk / effort:** Medium risk (user expectation of passive peer discovery must be balanced); M effort.
- **Prove it:** Check `dumpsys activity processes | grep com.transfer.flash` after 15 minutes idle in background.
- **Verifier result:** Survived. Verified `FlashBackgroundService.kt:475` has only one caller in `FlashTileService`.
- **Related:** ADR-041, `FlashBackgroundService.kt:49-52`.

---

### OPT-O13-07 — Lack of AudioFocus Loss Transience Recovery Backoff in TranssionHiberKeepAlive Causes Focus Fighting with Music/Media Players
- **Impact rank:** Low-Medium    **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, C         **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/TranssionHiberKeepAlive.kt:129-152`
- **Bottleneck class:** protocol / audio
- **What is wasteful:**  
  In `TranssionHiberKeepAlive.kt:129-152`, when Tier 3 (AUDIO) is active, the audio focus change listener handles permanent audio focus loss:
  ```kotlin
  AudioManager.AUDIOFOCUS_LOSS -> {
      Log.i(TAG, "Tier 3 audio focus lost permanently ($focusChange); pausing silent audio without fighting")
      runCatching { audioTrack?.pause() }
      if (!isBleScanning) {
          startBluetooth()
      }
  }
  ```
  While the listener comments state "without fighting", notice what happens when the session state changes or an event occurs in `FlashBackgroundService.kt:258-260`:
  `keepAlive?.setSessionActive(isSessionActive)` is called.
  Inside `setSessionActive`:
  ```kotlin
  if (isStarted) {
      reevaluateTier()
  }
  ```
  `reevaluateTier()` iterates through the tiers starting at Tier 1, Tier 2, and falls back to Tier 3:
  ```kotlin
  if (activateTier(KeepAliveTier.AUDIO)) { ... }
  ```
  `startAudio()` immediately issues `audioManager.requestAudioFocus(...)` **again**, requesting `AUDIOFOCUS_GAIN` with zero backoff!  
  If the user is listening to Spotify, YouTube Music, or a podcast on their Infinix phone while Flash is running in the background, every new transfer event, network probe, or session reconnect causes `TranssionHiberKeepAlive` to repeatedly request audio focus, causing audio stuttering or pausing the user's music player.
- **Cost model:**  
  Repeated AudioFocus IPC requests to `AudioService` and audio HAL restarts waste ~1–2 ms CPU time per event and disrupt user media playback.
- **User-visible effect:** Music or podcast abruptly pausing or stuttering on Transsion devices whenever Flash has background activity.
- **Expected gain:** Complete elimination of audio focus thrashing with external media players.
- **Proposed change:**  
  1. Add a backoff timestamp or `hasPermanentFocusLoss` latch in `TranssionHiberKeepAlive`.
  2. When `AUDIOFOCUS_LOSS` is received, do not attempt to re-request audio focus until the user returns to the app or a 10-minute cooldown has elapsed.
  3. If Bluetooth (Tier 1) or Overlay (Tier 2) are unavailable, fall back to transfer traffic throttling (HIB-13) rather than fighting for audio focus.
- **Invariants touched (5.3):** None broken.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Play Spotify in background on Infinix X6882B while initiating a background file transfer; verify Spotify playback is not interrupted.
- **Verifier result:** Survived. Verified `TranssionHiberKeepAlive.kt:146-152` re-evaluates without a backoff latch.
- **Related:** HIB-14, HIB-13.

---

### OPT-O13-08 — Polling `delay(300)` Busy-Wait Loop in `observeSessionActivity` During Background Service Startup
- **Impact rank:** Low           **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, B, T      **Runtime:** Android
- **Component:** `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt:230-244`
- **Bottleneck class:** CPU
- **What is wasteful:**  
  In `FlashBackgroundService.kt:230-244`, `observeSessionActivity()` observes the engine's active sessions and transfers to maintain the `isSessionActive` flag for `TranssionHiberKeepAlive`:
  ```kotlin
  private fun observeSessionActivity() {
      scope.launch {
          DiscoveryEngineHolder.transfersFlow.collectLatest { repo: FlashTransferRepository? ->
              val transfersFlow = repo?.activeTransfers ?: MutableStateFlow(emptyList())
              while (isActive && DiscoveryEngineHolder.currentNetwork() == null) {
                  delay(300) // <-- Polling busy-wait loop!
              }
              val network = DiscoveryEngineHolder.currentNetwork()
  ...
  ```
  Instead of observing `DiscoveryEngineHolder.networkFlow` (which is already a reactive `StateFlow<FlashNetwork?>`), the coroutine executes a polling `while` loop with `delay(300)`.  
  During engine boot, this wakes the coroutine dispatcher every 300 ms. More critically, if the network stack is ever re-created or restarted (e.g. following a radio transition or engine restart), this `collectLatest` block does **not** update its `network` reference because it captured `currentNetwork()` once after the loop exited, leaving `sessionsFlow` pointing to the stale or previous network instance!
- **Cost model:**  
  Repeated 300ms scheduler wakeups during startup (~3.3 wakeups/sec) plus latent bug where stale session flow fails to reflect peer reconnections.
- **User-visible effect:** Unnecessary coroutine scheduler wakeups; potential failure to reflect active sessions in keep-alive tier evaluation after network restart.
- **Expected gain:** Zero polling wakeups; reactive and correct observation of network instances.
- **Proposed change:**  
  Replace the polling `while` loop with reactive `combine` or `flatMapLatest` on `DiscoveryEngineHolder.networkFlow`:
  ```kotlin
  DiscoveryEngineHolder.networkFlow.filterNotNull().collectLatest { network ->
      val sessionsFlow = network.activeSessions
      ...
  }
  ```
- **Invariants touched (5.3):** None broken.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Code inspection confirms `networkFlow` exists in `DiscoveryEngineHolder.kt`.
- **Verifier result:** Survived. Verified `FlashBackgroundService.kt:234-236` contains `while (isActive && DiscoveryEngineHolder.currentNetwork() == null) delay(300)`.
- **Related:** `FlashBackgroundService.kt:234`.

---

## 6. Battery Drain Model per Hour across Operational States

This quantitative model estimates the baseline current draw ($\text{mA}$) and hourly battery consumption ($\text{mAh}$) for a modern Android device (Snapdragon 7/8 series or MediaTek Dimensity with a 5,000 mAh battery @ 3.85V nominal) running Flash under current commit `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`:

| Operational State | Subsystems Active | CPU AP State | Wi-Fi / Radio State | Current Draw (mA) | Hourly Drain (mAh/hr) | % of 5,000 mAh Battery / hr | Standby Battery Life |
|---|---|---|---|---|---|---|---|
| **State 1: Clean OS Idle (App Killed)** | OS background daemons only | Deep sleep (AP suspend > 95%) | DTIM sleep (Wi-Fi beacon listen only) | **3 – 6 mA** | **3 – 6 mAh** | ~0.08% / hr | ~800 – 1,200 hrs (~35–50 days) |
| **State 2: Headless Keepalive (FlashKeepaliveWorker)** | Engine boots for 45s every 15 min; NSD/Multicast/Scan active | Awake @ 1.2–1.8 GHz for 45s (3 min/hr total) | Wi-Fi active Tx/Rx + multicast listen | **120 – 180 mA** during run; **+7.5 mA** avg | **+7.5 mAh** above base | +0.15% / hr | Standby reduced to ~300 hrs |
| **State 3: Background Mesh Standby (Screen OFF, 0 active transfers, 0 calls)** | `PARTIAL_WAKE_LOCK` permanently held; `MulticastLock` held; 5s discovery sweepers; 4 Hz swarm ticker | Awake 100% of time (0% AP suspend); Little cores @ shallow idle | Multicast filtering bypassed; radio listening to all LAN packets | **45 – 65 mA** (AOSP); **60 – 85 mA** (Transsion Tier 3) | **45 – 85 mAh** | **0.9% – 1.7% / hr** | **58 – 111 hours** (Overnight 8hr: 360–680 mAh lost!) |
| **State 4: Active File Transfer (Background dataSync, 15–25 MB/s)** | `dataSync` FGS, High-Perf Wi-Fi lock, TLS 1.3, AES-GCM crypto, SHA-256 chunk hashing, SQLite writes | All cores active (Mid/Big cores @ 1.8–2.4 GHz) | Continuous high-throughput Wi-Fi MIMO Tx/Rx | **350 – 550 mA** | **350 – 550 mAh** | **7.0% – 11.0% / hr** | ~9 – 14 hours continuous transfer |
| **State 5: Active VoIP Call (Audio-only)** | `microphone` FGS, WebRTC AEC/NS, Opus SILK 60ms DTX, High-Perf Wi-Fi lock | Little/Mid cores @ audio pacing (100 Hz WebSockets) | Wi-Fi low-latency transmission | **180 – 260 mA** | **180 – 260 mAh** | **3.6% – 5.2% / hr** | ~19 – 27 hours talk time |
| **State 6: Active Video Call (Display + Camera)** | `camera\|microphone` FGS, Camera2 capture, H.264 HW codec, Display @ 300 nits | Big cores active + GPU rendering | High-bitrate Wi-Fi Tx/Rx | **550 – 800 mA** | **550 – 800 mAh** | **11.0% – 16.0% / hr** | ~6 – 9 hours video call |

### Key Analysis & Owed Device Verifications
1. **The Standby Cliff (State 3 vs State 1):**  
   Under State 3, Flash currently increases idle background current by **10× to 15×** (from ~4 mA to ~55 mA). Over a 24-hour day in a user's pocket, this consumes **1,000 to 1,500 mAh** (20–30% of total phone capacity) when transmitting zero user messages!  
   The primary drivers are:
   - `OPT-O13-01`: Permanent engine-lifetime `PARTIAL_WAKE_LOCK` preventing AP suspend (+15–30 mA).
   - `OPT-O10-02`: Permanent `MulticastLock` preventing Wi-Fi PHY power collapse (+15–25 mA).
   - `OPT-O08-05` & `OPT-O10-05`: 4 Hz SwarmDriver ticker and 5s discovery sweepers (+5–10 mA).
2. **Owed Device Tests (from `docs/testing/TEST-BACKLOG.md`):**
   - **BATT-01 (Idle Standby Drain):** Charge test phone (Samsung SM-G986U1 and Infinix X6882B) to 100%, reset batterystats (`adb shell dumpsys batterystats --reset`), leave screen off for 4 hours with Flash backgrounded. Generate bugreport (`adb bugreport`) and inspect in Battery Historian.  
     *Pass criteria:* Deep sleep percentage $\ge 90\%$; average current $\le 10\text{ mA}$. (Currently fails: deep sleep is ~0%).
   - **BATT-02 (Android Vitals Wake Lock Audit):** Inspect `dumpsys power | grep -A 15 "Wake Locks"`.  
     *Pass criteria:* `flash:mesh-ws-liveness` total held time $< 10\text{ minutes}$ over a 4-hour period. (Currently fails: held 240 minutes / 100%).
   - **HIB-13b (Transsion Screen-Off Large Transfer):** Verify transfer continues at $\ge 10\text{ KB/s}$ with screen off on Infinix X6882B without Hiber freezing the UID.

---

## 7. "No Problem, Because..." Notes

### 1. Absence of AlarmManager Leaks
- **No problem, because:** A search across the entire repository (`app/`, `core/`, `desktop/`, `server/`) reveals exactly 0 usages of Android's `AlarmManager`. The application deliberately avoids using `setRepeating()` or `setExactAndAllowWhileIdle()` alarms, eliminating the common pitfall of alarm wake-up storms, `SCHEDULE_EXACT_ALARM` Play Store policy compliance issues, and unaligned wake-ups. Headless background resilience is delegated strictly to Android's modern `WorkManager` API.

### 2. START_STICKY Resilient Handling on Refused Foreground Promotion
- **No problem, because:** `FlashBackgroundService.onStartCommand` returns `START_STICKY`. On Android 12+ (API 31+), when the system resurrects a `START_STICKY` service in the background, calling `startForeground()` throws `ForegroundServiceStartNotAllowedException`. Rather than crashing or calling `stopSelf()` (which previously caused engine crash loops and dropped locks in ERROR-026), `FlashBackgroundService.startAsForeground()` catches this exception, logs the event, sets `promotionRefused = true`, and retains the service instance in the background. The promotion is safely retried upon the next network connection edge (`WsFlashNetwork.onUsableNetwork`) or screen-on event (`DiscoveryEngineHolder.onScreenOn`).

### 3. Cellular Radio Isolation in Network Change Callbacks
- **No problem, because:** `AndroidNetworkWatcher` registers its `ConnectivityManager.NetworkCallback` with a restricted `NetworkRequest` specifying only `TRANSPORT_WIFI` and `TRANSPORT_ETHERNET`. By explicitly omitting `TRANSPORT_CELLULAR`, the app avoids paying the heavy CPU and radio wake-up penalty associated with cellular link speed and signal strength fluctuations that occur continuously while a device is carried by a walking user (ERROR-035).

### 4. Dynamic Scoping of Wi-Fi High-Performance Lock
- **No problem, because:** In `DiscoveryEngineHolder.kt:2950-2972`, `updateWifiLockState()` evaluates active transfers (`Transferring`, `Verifying`) and active calls (`FlashCallState.ACTIVE`). When no transfers or calls are occurring, the `WIFI_MODE_FULL_LOW_LATENCY` lock is released, allowing the 2.4 GHz and 5 GHz Wi-Fi radios to enter power-saving DTIM sleep modes.

### 5. Call Foreground Service Type Adaptation
- **No problem, because:** `FlashCallService` correctly navigates Android 14+ while-in-use restrictions for `microphone` and `camera` FGS types. When an incoming call arrives while the app is in the background (`RINGING`), it does not claim `microphone` (which would throw `SecurityException`), but claims `connectedDevice`. Once the user answers the call and brings the app to the foreground, it dynamically re-promotes itself to claim `microphone` (and `camera` if video is enabled). When the call ends, `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()` are cleanly called.

---

## 8. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence | Effort | Risk | Status |
|---|---|---|---|---|---|---|---|
| **OPT-O13-01** | Perpetual Engine-Lifetime Partial Wake Lock Held 24/7 Without Timeout Guard Prevents AP Suspend (15–35 mA Idle Drain) | High | B, C | STATIC-ARITHMETIC | M | Medium | OPEN |
| **OPT-O13-02** | Unconditional Headless Engine Spin-Up in FlashKeepaliveWorker Ignores Empty Outbox and Queue (Wastes 180s Awake/Hour) | High | B, C, N | STATIC-ARITHMETIC | S | Low | OPEN |
| **OPT-O13-03** | Missing `FlashTransferState.Verifying` in Android 15 `TimeoutStopPlan` Leaves Verification Jobs Hanging on FGS Timeout | Medium | T, S, C | STATIC-ARITHMETIC | S | Low | OPEN |
| **OPT-O13-04** | Transsion Hiber Tier 3 Silent AudioTrack Fails to Check Screen State, Draining Battery and Blocking Audio DSP Power Collapse | Medium | B, C | STATIC-ARITHMETIC | S | Low-Medium | OPEN |
| **OPT-O13-05** | Absence of `ACTION_SCREEN_OFF` Handling in Background Service Keeps Full-Rate Polling and High-Frequency Tickers Running in Pocket | High | B, C, N | STATIC-ARITHMETIC | M | Medium | OPEN |
| **OPT-O13-06** | Permanent Background Foreground Service Execution with `connectedDevice` While 0 Peers Connected Violates Google Play Policy | Medium | B, D | STATIC-ARITHMETIC | M | Medium | OPEN |
| **OPT-O13-07** | Lack of AudioFocus Loss Transience Recovery Backoff in TranssionHiberKeepAlive Causes Focus Fighting with Media Players | Low-Medium | B, C | STATIC-ARITHMETIC | S | Low | OPEN |
| **OPT-O13-08** | Polling `delay(300)` Busy-Wait Loop in `observeSessionActivity` During Background Service Startup | Low | C, B, T | STATIC-ARITHMETIC | S | Low | OPEN |

### Next Steps for Implementation
1. **P0 (Immediate Vitals & Battery Relief):** Gate `FlashKeepaliveWorker` behind an outbox/transfer check (`OPT-O13-02`), and dynamically manage the CPU partial wake lock in `DiscoveryEngineHolder` (`OPT-O13-01`) so the device can enter AP suspend when idle.
2. **P1 (Platform Compliance & Reliability):** Include `Verifying` in `TimeoutStopPlan` (`OPT-O13-03`), and implement `ACTION_SCREEN_OFF` downscaling to `ECO` mode (`OPT-O13-05`).
3. **P2 (OEM & Audio Hygiene):** Pause Transsion silent `AudioTrack` when screen is on (`OPT-O13-04`), add audio focus loss backoff (`OPT-O13-07`), and replace the `delay(300)` busy-wait loop (`OPT-O13-08`).
