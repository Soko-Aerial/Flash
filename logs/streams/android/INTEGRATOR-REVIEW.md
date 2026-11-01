# Integrator review — Android (G) stream, `agent/opt-android`

Reviewer: integration session, 2026-11-01. Scope: `dev..d8d28236` plus my own repair commit on the branch, verified
against **the code, the callers and the tests**, not against the stream's report. Every verdict below was reached by
reading the branch's commits and the surrounding call sites; where the report cited a test as evidence I read the test and
recorded what it actually asserts.

Test evidence produced on this branch tree (after the repairs in §3):

| Command | Result |
|---|---|
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:desktop:compileKotlinJvm` | **BUILD SUCCESSFUL** |
| `:ui:chat:jvmTest` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` | 90 tests, 2 failed — **both are stale tests on `dev` itself**, not regressions (see §4) |

The two `app` test failures are `TransfersUiMapperTest.retryableComesFromTheModelPredicate` and `.aHubRowSaysSoAndOffersNoRetry`.
They fail on a clean `dev` checkout too: they assert the pre-ADR-220 `canRetry` predicate, which open item C-07 changed
deliberately. Fixed on `dev` in `432ceb52` (tests + the `FlashTransfer.canRetry` KDoc, recorded as **ERROR-935**), so the
merged tree carries the corrected tests. **They are not the pre-existing "ERROR-106 desktop test failure" the stream's logs
blamed** — that is a different failure in a different module.

## 1. Verdict table

| # | Finding | Commit | Verdict |
|---|---|---|---|
| 1 | OPT-O13-01 bounded, ref-counted CPU wake-lock lease | `56d68977` | **KEEP, FIXED** (integrator: a live session is work) |
| 2 | OPT-O13-02 pre-boot SQLite check in the keepalive worker | `56d68977` | **KEEP, FIXED** (integrator: must not defeat the ADR-232 opt-in) |
| 3 | OPT-O13-03 `Verifying` in `TimeoutStopPlan` | `56d68977` | **KEEP** (unit test added) |
| 4 | OPT-O13-08 reactive flow replaces the `delay(300)` poll | `56d68977` | **KEEP** |
| 5 | OPT-O01-02 async scheduling / channel init / storage scan | `56d68977` | **KEEP, FIXED** (integrator: the storage card lost its refresh trigger) |
| 6 | OPT-O01-07 single lazy `MediaCodecList` | — | **NO CHANGE NEEDED** (already lazy in `AppEngine`) |
| 7 | OPT-O13-05 + OPT-O10-01 screen-off ECO via `setScreenActive` | `8fed6645` | **KEEP** — and it closes the Wave 2 open handoff |
| 8 | OPT-O13-04 Transsion silent-audio screen gating | `8fed6645` | **KEEP** |
| 9 | OPT-O13-07 audio-focus 10-minute cooldown | `8fed6645` | **KEEP**, with the shield consequence recorded |
| 10 | OPT-O01-04 deferred automatic subnet sweep | `8fed6645` | **KEEP** |
| 11 | OPT-O13-06 idle foreground-service auto-stop | `8fed6645` | **KEEP, FIXED** (integrator: it was unreachable and one-shot) |
| 12 | OPT-O01-08 (`MediaCodecList` warm-up ordering) | — | **DEFERRED to Stream D**, as reported |

Ledger: 10 kept (4 of them repaired by the integrator), 1 needed no change (verified), 1 handed off. Nothing was rejected and
nothing was reverted — unlike Wave 2, none of these findings contradicted a documented invariant. Three of them, however,
were *incomplete in a way that would have shipped a behaviour regression* rather than merely a missed optimisation; those are
§3.1–§3.3.

## 2. What was verified rather than believed

- **OPT-O10-01 has a caller now.** Wave 2's review left `setScreenActive` wired into nothing. `DiscoveryEngineHolder.kt`
  calls `composite?.setScreenActive(true)` from `onScreenOn()` and `(false)` from the new `onScreenOff()`, which is reached
  from the `ACTION_SCREEN_OFF` receiver (`RECEIVER_NOT_EXPORTED`, same registration as the screen-on actions) and from
  `checkScreenOffDownscale()` when a transfer finishes with the screen already off. Its guard really does skip the downscale
  with a transfer, a call or a PTT floor open. So the Wave 2 handoff `OPT-O10-01` is **closed**.
- **OPT-O01-04 cannot cost a discovery.** `SweepController.scanNow()` — the manual "Scan network" path — never goes through
  `start()`, so the 30 s deferral only affects the automatic fallback. `SweepController` is constructed with `appScope`, so
  `stopAll()`'s `appScope.cancel()` still tears the sweep loop down even though `sweepJob` now holds the wrapper coroutine
  rather than the loop itself.
- **OPT-O13-03 is a real gap, not a cosmetic one.** `FlashTransferState.Verifying` is a genuine post-transfer state (the
  whole-file hash), and the Android 15 `dataSync` timeout fires `MemoryThermalGovernor`-independent of transfer state, so a
  multi-GB receive could be killed mid-hash with nothing written. The new test asserts `Verifying` is in the plan; the pause
  path then restarts verification on resume.
- **OPT-O13-08 replaced the poll correctly.** `networkFlow` / `transfersFlow` are new `StateFlow`s set in `startEngineLocked`
  and nulled in `stopAll`, so `combine` re-runs `observeSessionActivity` on both edges instead of a 300 ms poll.
- **OPT-O13-04/07 on Transsion**: the screen receiver is registered in `start()` and unregistered in the teardown, both
  gated by a `screenReceiverRegistered` flag, and the `AUDIOFOCUS_GAIN`/`startAudio()` paths both consult
  `isScreenInteractive`, so the track cannot be left playing with the screen on.

## 3. What the integrator repaired (all on this branch, one commit)

### 3.1 OPT-O13-01 — the wake lock was released while a peer was connected

As delivered, `needsWakeLock` was `transfer || call || ptt || lease`. That omits the case the lock existed for: the old
KDoc says it is "held to ensure keepalive pings are scheduled". A keepalive is a timer, and a suspended CPU runs no timer,
so a phone with a connected peer and no transfer would have gone to AP suspend with the screen off and the peer would have
timed it out while the app still showed it online — a background-delivery regression sold as a battery win. Nothing in the
stream's tests could see it; only the caller could.

Fixed by adding `hasConnectedPeers` (`network.activeSessions` not empty) to the condition, **and** a collector on
`activeSessions` that re-evaluates the lock — without the collector the lock could only be re-evaluated when a transfer or
a call changed, so a phone that connected to a peer and then went quiet would never have taken it. The lock now follows the
session rather than the process, which is what the optimisation was always about: an isolated phone suspends.

### 3.2 OPT-O13-02 — the skip would have cancelled the setting the user just switched on

The pre-check returned `Result.success()` whenever the outbox and the transfer queue were empty. But this worker is the
**inbound** half of the safety net as well: the session window it opens (`SESSION_WINDOW_MS = 45_000`) is the only time an
otherwise-idle phone can be reached. Skipping every run for someone who switched on "Keep listening in the background"
would defeat ADR-232 from inside the worker whose job is to restore what the system stopped.

The condition is now `hasPendingOutboundWork || keepListeningInBackground(context)`. Reading the opt-in in a cold worker
process was not free:
- the `AppEngine` mirror `DiscoveryEngineHolder.backgroundTransfers` is still `false` there, because nothing calls
  `AppEngine.start()` in a worker process;
- the worker cannot open `FlashSettingsDataStore` itself — a second DataStore instance for the same file throws *inside a
  process*, and the UI can be started in that same process later.

So the opt-in now has exactly one writer (`DiscoveryEngineHolder.setBackgroundTransfers`, called by AppEngine's initial read
and its collector), which also leaves a one-boolean `SharedPreferences` copy (`flash_flags` /
`keep_listening_in_background`). `keepListeningInBackground(context)` answers "live mirror, else that copy", and a read
failure answers `true` — a broken read costs one engine boot rather than silently switching the opt-in off.
`FlashBackgroundService.evaluateServiceIdleStop` uses the same accessor for the same reason: a `START_STICKY` regeneration
also starts in a process with an empty mirror.

### 3.3 OPT-O13-06 — the idle stop could not happen in the ordinary case

The stream armed one job per process and evaluated "is anything still happening?" only at the moment of arming; the only
callers were mesh transitions, which happen while the user is still in the app, and `stopSelf()` never re-enters it. The
sequence "open Flash with nobody nearby, then leave it" — the exact case the finding is about — therefore never stopped the
service, and if the first round had fired once, it could not fire again.

Fixed by recording `lastSessionActive`, re-checking peers / relay / transfers / foreground **at fire time**, re-arming after
each expiry, and calling the new `FlashBackgroundService.reevaluateIdleStop()` from `MainActivity.onStop()`.

### 3.4 Three smaller repairs

- **The settings row said the wrong thing on Windows.** ADR-232 renamed "Background transfers" to "Keep listening in the
  background" with the subtitle "Stay reachable with nobody connected; the system may still stop it" — but the screen is
  shared, and `DesktopShell` wires the same model field to `closeToTray`. Windows would have described close-to-tray as an
  Android foreground-service opt-in. `FlashSettingsScreen` now takes `backgroundListenTitle` / `backgroundListenSubtitle`
  (defaults = the Android copy, and the search index still matches "Background transfers"), and the desktop passes
  "Close to tray" / "Closing the window keeps Flash running in the notification area".
- **The storage card lost its refresh trigger.** OPT-O01-02 removed the synchronous `refreshReceivedStorageUsage()` from
  `MainActivity.onCreate()`, genuinely taking a directory walk off the launch path — but nothing replaced the call, so the
  Settings card would have shown `0 B` until Refresh was tapped. It now refreshes from a `LaunchedEffect(Unit)`.
- **ERROR-923 claimed a fix the diff does not contain.** OPT-O01-07 was recorded as a `MediaCodecList` change; the branch
  has no such change because none was needed (the list is already behind a `by lazy` in `AppEngine`). Recorded as
  "no change needed" instead.

## 4. Numbers withdrawn

`EXP-105`..`EXP-108` are design expectations. The first draft asserted currents ("15–35 mA", "12–20 mA"), durations
("< 35 ms") and duty cycles ("< 5 %", "> 90 % deep sleep") that no device produced; all of them are withdrawn and each entry
now names the `PERF-G*` test that has to establish it. The two external Android thresholds in the platform notes are marked
*reported, not verified*.

## 5. Open risks carried into `dev`

- **OPT-O13-07 spends the Hiber shield while it waits.** After a permanent audio-focus loss, `resolveTier` returns `NONE`
  for ten minutes (Bluetooth is checked first, so a BLE-scan-capable handset still gets a tier). On a Transsion handset with
  a music player running and no BLE path, Flash therefore has **no** freezer shield for those ten minutes. This is the
  intended trade — it only arms when the shield has already failed, and re-requesting focus in that state is what the
  previous code did — but it must not be read as "the shield is always on".
- **The FGS `isActive` answer can be stale for the hub.** `observeSessionActivity` re-evaluates `isHubConnected` only when a
  transfer, session, call or PTT changes; the hub relay's own state changes are not a source of that flow. The idle stop
  re-reads it at fire time and `onStop` asks again, so the window is bounded, but a hub that connects and nothing else does
  will not re-arm the timer.
- **Everything above is unit-tested only.** No device has run any of it.

## 6. Owed device tests

`PERF-G01`..`PERF-G08` in this stream's `backlog-additions.md`, folded into `docs/testing/TEST-BACKLOG.md` as the
`PERF-G01..G08` section at the end of the file (§4zp was already the radio link tooling). `PERF-G01`
and `PERF-G02` were rewritten to cover the two repairs above — `PERF-G01` now runs with **and** without a connected peer, and
`PERF-G02` runs with the opt-in **off** (skip) and **on**, including after a cold process death (boot).
