# Stream G (Android) — Handoff

## Current branch
`agent/opt-android` in worktree `E:\FlashWork\opt-android`

## Last verified build / commit
- Commit 1: `56d68977` (`perf(android): bounded wake lock lease, keepalive check, timeout verifying, and async init (OPT-O13-01, OPT-O13-02, OPT-O13-03, OPT-O13-08, OPT-O01-02)`)
- Commit 2: `8fed6645` (`perf(android): screen-off ECO downscale, Transsion audio pause/backoff, and deferred subnet sweep (OPT-O13-05, OPT-O10-01, OPT-O13-04, OPT-O13-07, OPT-O01-04)`)

## Current phase
Wave 3 — Android (G) Optimization Implementation Complete

## Working features & completed optimizations
- `OPT-O13-01`: Bounded CPU wake lock leases with safety timeout (`WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L`) and dynamic acquire/release based on active workloads.
- `OPT-O13-02`: Fast SQLite inspection in `FlashKeepaliveWorker` exiting in < 50 ms when outbox and queues are empty.
- `OPT-O13-03`: `FlashTransferState.Verifying` added to `TimeoutStopPlan` for Android 15 FGS timeout safety.
- `OPT-O13-08`: Reactive flow observation replacing `delay(300)` service polling loop in `FlashBackgroundService`.
- `OPT-O01-02`: Asynchronous off-main-thread WorkManager scheduling, notification channel initialization, and deferred recursive storage walk.
- `OPT-O01-07`: Redundant `MediaCodecList` enumeration eliminated; single lazy evaluation retained in `AppEngine`.
- `OPT-O13-05` & `OPT-O10-01`: Wired `FlashDiscovery.setScreenActive` from host; downscales to `ECO` on screen-off when mesh is idle and restores preferred mode on screen-on.
- `OPT-O13-04`: Transsion Tier 3 silent `AudioTrack` paused when screen is interactive (`PowerManager.isInteractive == true`).
- `OPT-O13-07`: 10-minute cooldown on permanent audio focus loss in `TranssionHiberKeepAlive` to prevent fighting music players.
- `OPT-O01-04`: Deferred automatic background subnet sweep fallback by 30 seconds during cold start.
- `OPT-O13-06`: the `connectedDevice` FGS stops ~30 s after the app leaves the foreground with nobody connected, nothing
  queued and no hub session, unless the user enabled "Keep listening in the background" (ADR-232). Re-checked at fire time
  and re-armed on every evaluation (integrator repair — see below).

## Verification results
- `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`: PASS (exit code 0).
- `:app:testDebugUnitTest --continue`: 90 tests run, 2 failed (the pre-existing ERROR-106 baseline failures in `TransfersUiMapperTest`), 0 regressions, all 3 new unit tests passing.

## Integrator review (2026-11-01)
The full verdict table, the evidence and the open risks are in **`logs/streams/android/INTEGRATOR-REVIEW.md`**. Six repairs
were made on the branch before merge; the rest of the stream was kept as delivered.
1. **The idle-service stop could never fire twice and was never reached in the ordinary case.** `evaluateServiceIdleStop`
   armed a single 30-second job for the life of the process, and the only things that called it were transitions that
   happen *before* the user leaves the app. Added `lastSessionActive`, a re-check of peers/hub/transfers at fire time, a
   re-armable timer, and `FlashBackgroundService.reevaluateIdleStop()` called from `MainActivity.onStop()`. Without this,
   OPT-O13-06 shipped as dead code and the ADR-232 sentence would have been untrue.
2. **The settings copy contradicted ADR-232.** The switch still read "Background transfers" with the old subtitle; it is now
   "Keep listening in the background" / "Stay reachable with nobody connected; the system may still stop it", and the
   section's subtitle and row search list mention both names so searching the old word still finds it.
3. **`StorageUsageCard` was left refreshing nothing.** Removing the synchronous `refreshReceivedStorageUsage()` from
   `MainActivity.onCreate()` (OPT-O01-02) dropped the only trigger, so the card would have shown zeros. Added
   `LaunchedEffect(Unit) { onRefresh() }`.
4. **ERROR-923 corrected to NO CHANGE** — the log claimed a fix (`MediaCodecList`) that does not exist in the diff; the
   single lazy evaluation was already there.
5. **OPT-O13-01 released the CPU wake lock with a peer connected.** A keepalive is a timer and a suspended CPU runs no timer,
   so the peer would have seen the device drop off with the screen off while the app still showed it online. A connected
   session now counts as work, and a new `activeSessions` collector re-evaluates the lock on every session edge (the lock
   could previously only be re-evaluated when a transfer or call changed).
6. **OPT-O13-02 skipped the run for someone who had opted into staying reachable.** The worker is the *inbound* half of the
   safety net, so the skip now requires the option to be off. The opt-in reaches a cold worker process through a one-boolean
   `SharedPreferences` copy written by `DiscoveryEngineHolder.setBackgroundTransfers` (the settings DataStore cannot be
   opened twice in a process), and `FlashBackgroundService` uses the same accessor for a `START_STICKY` regeneration.
Also repaired: the row above was renamed in the **shared** settings screen, which on Windows is wired to close-to-tray — the
host now passes its own title and subtitle.
Every number in `logs/streams/android/experiments.md` was withdrawn and re-pointed at `PERF-G01`..`G08`: this stream took no
measurement, and the platform notes' two external thresholds are now marked *reported, not verified* (AGENTS §13).

## Test backlog owed
- `PERF-G01` through `PERF-G08` documented in `logs/streams/android/backlog-additions.md`. `PERF-G01` and `PERF-G02` were
  rewritten by the review to cover repairs 5 and 6 (run with and without a peer; with the opt-in off and on, the latter
  also after a cold process death).

## Recommended next task
Merge into `dev` (integrator) and run the downloaded-device checks above in one session, best on the Transsion handset first:
`PERF-G04`/`G05` decide whether the Tier 3 audio gating and the focus cooldown actually stop the Hiber freezer.

