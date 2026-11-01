# Stream G (Android) — Decisions Additions

The ADR entries in this file record architectural decisions made during Wave 3 optimization work.
The integrator will fold these into `docs/decisions.md`.

---

## ADR-280 — Bounded, Ref-Counted CPU WakeLock Lease Management (OPT-O13-01)

### Context
`DiscoveryEngineHolder` previously acquired a `PowerManager.PARTIAL_WAKE_LOCK` unconditionally at engine startup (`wakeLock.acquire()`) without a safety timeout and never released it until full engine teardown. While active mesh sockets and transfers require CPU execution, permanent wake lock holding blocks Application Processor (AP) deep sleep / power collapse during idle periods. The size of the consequent drain is **not measured here** — `PERF-G01` is the device test that establishes it (`dumpsys power` wake-lock time and `dumpsys batterystats`).

### Decision
1. Introduce dynamic wake lock management mirroring `updateWifiLockState()`: `updateCpuWakeLockState()`.
2. Partial wake lock is held strictly while active workloads are present: `hasActiveTransfer` (Transferring or Verifying), `hasActiveCall` (non-ended WebRTC call), active PTT session floor, or active ref-counted leases.
3. Leases (`WakeLockLease`) are bounded by a mandatory safety timeout: `WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L`. A 5-minute watchdog refresher refreshes the timeout if work remains active.
4. When all active workloads and leases complete, `wakeLock.release()` is immediately invoked, allowing the SoC to enter low-power AP suspend.

---

## ADR-281 — Pre-Boot SQLite Inspection in FlashKeepaliveWorker (OPT-O13-02)

### Context
`FlashKeepaliveWorker` is scheduled every 15 minutes via WorkManager as a durability safety net to flush unsent outbox messages if the process was terminated. However, `doWork()` unconditionally spun up the full `DiscoveryEngineHolder` stack (crypto, TLS, sockets, NSD, AutoConnector), acquired wake locks, and waited 45 seconds even when the outbox was completely empty and 0 transfers were queued. Four runs an hour at up to 45 s awake each is a 180 s/hr ceiling by arithmetic; the actual awake time per run has not been measured (`PERF-G02`).

### Decision
1. In `FlashKeepaliveWorker.doWork()`, inspect SQLite before starting the headless engine:
   - Check if database file exists.
   - Execute fast SQLite scalar queries `SELECT COUNT(*) FROM outbox` and `SELECT COUNT(*) FROM transfers WHERE status IN ('Transferring', 'Verifying', 'Queued', 'Paused') AND dismissed = 0`.
2. If both counts are 0, return `Result.success()` immediately, without spinning up the engine, binding sockets, or taking wake locks. Both queries hit the rowid B-tree (no scan of the payload), so the cost is two scalar reads on an openable database plus the open itself. **`< 50 ms` was an estimate, not a measurement**; `PERF-G02` records the real per-run duration.
3. If pending outbound work exists, acquire a bounded lease `DiscoveryEngineHolder.acquireWakeLockLease("keepalive-worker", ...)` and start the engine to drain.

---

## ADR-282 — Inclusion of Verifying State in Android 15 TimeoutStopPlan (OPT-O13-03)

### Context
On Android 15 (API 35), foreground services with `dataSync` face a hard cumulative 6-hour execution timeout. `FlashBackgroundService.onTimeout` delegates to `TimeoutStopPlan.of(transfers)` to identify transfers that must be paused before `stopSelf()`. `TimeoutStopPlan` only inspected `FlashTransferState.Transferring`, omitting `FlashTransferState.Verifying`. Large file transfers entering verification (computing whole-file SHA-256) upon timeout were terminated abruptly without updating state or saving system-paused metadata.

### Decision
Update `TimeoutStopPlan.of` to include both `FlashTransferState.Transferring` and `FlashTransferState.Verifying`. Verifying transfers are cleanly paused with `SERVICE_TIMEOUT` and saved, allowing clean automatic resumption upon subsequent app reopen.

---

## ADR-283 — Screen-Off Discovery Downscale to ECO Mode (OPT-O13-05, OPT-O10-01)

### Context
`DiscoveryEngineHolder` previously registered a broadcast receiver strictly for `Intent.ACTION_SCREEN_ON` and `Intent.ACTION_USER_PRESENT`, leaving discovery in `STANDARD` or `BOOST` mode indefinitely while the phone was locked in a pocket, where its tickers and sweep loops kept firing. (The "> 3,500 wakeups/hr" figure first written here was an estimate with no measurement behind it and is withdrawn; `PERF-G06` is the device test that counts them.) Stream N implemented `FlashDiscovery.setScreenActive` (wave 2) but left it without an Android host caller.

### Decision
1. Register `Intent.ACTION_SCREEN_OFF` in `DiscoveryEngineHolder.registerScreenReceiver` (using `RECEIVER_NOT_EXPORTED`).
2. On screen-off, if no transfer, call, or PTT session is active: invoke `composite?.setScreenActive(false)`, downscaling discovery to `ECO` mode (relaxing tickers, reducing broadcast frequency).
3. If a call, transfer, or PTT session is active when the screen turns off, preserve active discovery. When that workload completes while the screen remains non-interactive (`!PowerManager.isInteractive`), automatically downscale to `ECO`.
4. On `Intent.ACTION_SCREEN_ON` / `USER_PRESENT`, invoke `composite?.setScreenActive(true)` to restore the user's preferred discovery mode (`STANDARD` or `BOOST`) and re-arm discovery.

---

## ADR-284 — ConnectedDevice FGS Idle Auto-Stop with 30s Grace Period (OPT-O13-06, ADR-232)

### Context
`FlashBackgroundService` maintained `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` indefinitely even when 0 peers were connected, 0 transfers were running, and 0 enterprise relay connections existed. This violated Google Play Android 14+ FGS policy and prevented Android from placing Flash into App Standby buckets (`RESTRICTED`/`RARE`).

### Decision
1. When all sessions, active transfers, calls, PTT, and hub connections reach 0, arm a 30-second grace timer in `FlashBackgroundService.evaluateServiceIdleStop`.
2. The question is asked on every mesh-activity change **and again from `MainActivity.onStop`** (`reevaluateIdleStop()`): backgrounding is not a mesh change, so without that call the ordinary sequence "open Flash with nobody nearby, then leave it" would never reach the idle stop — the only round would have happened while the app was on screen and been declined.
3. At expiry the **live** state decides, not the emission that armed the timer: no active session (`lastSessionActive`), no transfers, no online hub relay, `activeSessions` empty, and the process no longer foreground-interactive. A finished timer is rearmable (`idleStopJob?.isActive`), so a declined round does not permanently disarm the mechanism — the integrator-review repair of the first implementation, which could only ever attempt once per process.
4. If the idle state still holds, invoke `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`; `onStart` restarts the service on return to the app.
5. Provide an opt-in bypass: if the user explicitly enabled the "Keep listening in the background" setting (`settingsStore.backgroundTransfers`), the foreground service continues running to support passive background mesh listening. The Settings row carries that name (it used to say "Background transfers") and the honest sentence that the system may still stop it (ADR-232, EXP-002).

---

## ADR-285 — Transsion Tier 3 AudioTrack Screen-Off Gating & AudioFocus Loss Cooldown (OPT-O13-04, OPT-O13-07)

### Context
On Transsion devices (Infinix, Tecno, itel), `TranssionHiberKeepAlive` activates Tier 3 (AUDIO) playing silent static PCM on `AudioTrack` to acquire `STATUS_AUDIO_FOCUS` and bypass the Hiber process freezer. However:
1. The Transsion Hiber freezer only freezes apps when the screen is OFF (`IMPORT_SCREEN_OFF_FLAG = 389052`). Playing audio while the screen is ON keeps the audio path powered for no benefit — the drain it costs has not been measured (`PERF-G04` covers it).
2. Upon permanent audio focus loss (`AUDIOFOCUS_LOSS`), background session state changes re-requested audio focus repeatedly without backoff, causing active music players (Spotify) to pause or stutter.

### Decision
1. In `TranssionHiberKeepAlive`, observe screen interactive state (`PowerManager.isInteractive`):
   - Silent `AudioTrack` only calls `.play()` when the screen is OFF.
   - When screen turns ON, `audioTrack?.pause()` is invoked, allowing audio DSP power collapse.
   - When screen turns OFF, `audioTrack?.play()` resumes if the session remains active.
2. Arm a 10-minute cooldown latch (`PERMANENT_FOCUS_LOSS_COOLDOWN_MS = 10 * 60 * 1000L`) when `AUDIOFOCUS_LOSS` or an audio focus refusal is received. While cooldown is active, `determineBestTier()` skips Tier 3 (AUDIO) and falls back to Bluetooth scan or network traffic shields.
3. Reset the cooldown latch immediately when the user returns to the app (`onAppForegrounded()`).

---

## ADR-286 — Deferred Automatic Subnet Sweep During Cold Start (OPT-O01-04)

### Context
During cold start, `DiscoveryEngineHolder.ensureStarted` immediately launched `SweepController.start()`, creating probe jobs and scheduling TCP connection attempts against up to 254 subnet hosts while the app was concurrently reading SQLite databases and rendering the first Compose frame.

### Decision
Defer `sweep.start()` in `DiscoveryEngineHolder` by 30 seconds (`INITIAL_SWEEP_START_DELAY_MS = 30_000L`) during engine startup. Manual sweeps via "Scan network" (`scanNow()`) and normal mDNS/NSD discovery remain immediate; only the automatic background fallback sweep is deferred, eliminating TCP socket churn during app launch.
