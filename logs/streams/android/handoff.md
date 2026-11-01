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
- `OPT-O13-06`: ConnectedDevice FGS auto-stop after 30s idle with 0 peers/transfers/hub unless user enabled background listening setting.

## Verification results
- `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`: PASS (exit code 0).
- `:app:testDebugUnitTest --continue`: 90 tests run, 2 failed (the pre-existing ERROR-106 baseline failures in `TransfersUiMapperTest`), 0 regressions, all 3 new unit tests passing.

## Test backlog owed
- `PERF-G01` through `PERF-G06` documented in `logs/streams/android/backlog-additions.md`.

## Recommended next task
Integrator review and merge of `agent/opt-android` into `dev`.
