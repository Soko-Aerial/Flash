# Stream G (Android) — Test Backlog Additions

Every test in this file must be executed on physical hardware during a device test session (AGENTS.md §35).
The integrator folds these into `docs/testing/TEST-BACKLOG.md`.

> **Threshold discipline.** The first version of this file wrote pass criteria as invented numbers ("< 50 ms",
> "> 90 % deep sleep", "> 80 % fewer wakeups") that no build had produced. Each test below now passes or fails on
> **observable state or a comparison against the same device on the reverted build**; a numeric target, where one is
> useful, is marked *(target, not evidence)*. None of these numbers may be quoted as a measurement until the test runs.

---

## PERF-G01 — Standby CPU wake lock release (OPT-O13-01)
- **Area:** Battery / wake locks / AP suspend
- **Requirement:** With no transfer, call, PTT floor or outstanding lease, the partial CPU wake lock is not held.
- **Hardware needed:** Physical Android device with a paired peer (Infinix X6882B, a Samsung handset, or the BelFone).
- **Procedure:**
  1. Open Flash, pair with a nearby device, confirm the link is up.
  2. Put the app in the background and turn the screen off.
  3. Leave it idle for 60 minutes.
  4. `adb shell dumpsys power | grep -A 10 "Wake Locks"`, then `adb shell dumpsys batterystats --charged com.transfer.flash`.
  5. Repeat steps 1–4 on a build with `updateCpuWakeLockState()` stubbed to the old unconditional acquire, same device, same day.
- **Pass criteria:**
  - `flash:ws-mesh` (or the current lock tag) does **not** appear as held for the whole idle window; it must be absent or held for a minority of it.
  - Recorded: wake-lock held-time and deep-sleep/AP-suspend time, both builds. The change passes if held-time drops and deep-sleep time rises; the absolute figures are the record, not a gate.
- **Source:** ADR-280, EXP-105
- **Status:** TODO

## PERF-G02 — Keepalive worker fast path with an empty outbox (OPT-O13-02)
- **Area:** Battery / WorkManager / SQLite
- **Requirement:** With an empty outbox and no queued transfers, the worker returns without starting the engine.
- **Hardware needed:** Physical Android device.
- **Procedure:**
  1. Empty every chat outbox and the transfer queue.
  2. `adb shell cmd jobscheduler run -f com.transfer.flash <jobId>` (or wait for the 15-minute run).
  3. `adb logcat -d | grep -i "keepalive"`, and `adb shell dumpsys power | grep -A 5 "Wake Locks"` before and after.
  4. Record the wall-clock time between the job start and the skip log line.
- **Pass criteria:**
  - The skip log line appears (`outbox and transfer queues empty; skipping headless engine boot`).
  - No WebSocket server bind and no NSD registration in logcat for that run, and no `flash:*` wake lock taken.
  - Elapsed time recorded; the earlier "< 100 ms" was a target, not evidence.
- **Source:** ADR-281, EXP-106
- **Status:** TODO

## PERF-G03 — Android 15 FGS timeout during verification (OPT-O13-03)
- **Area:** Storage / transfer resilience / Android 15 timeout
- **Requirement:** A transfer in `Verifying` is paused with `SERVICE_TIMEOUT` on `onTimeout`, not killed mid-hash.
- **Hardware needed:** Android 15 (API 35) device or emulator.
- **Procedure:**
  1. Send or receive a large file (multi-GB) and wait until chunk transfer ends and the row enters `Verifying`.
  2. `adb shell cmd activity timeout-service com.transfer.flash/.debug.FlashBackgroundService`.
  3. Check the row's paused reason and the log; then reopen the app and resume.
- **Pass criteria:**
  - The row is stored `Paused` with reason `SERVICE_TIMEOUT` (not `Failed`), and the verification completes after resuming with the hash intact.
- **Source:** ADR-282
- **Status:** TODO

## PERF-G04 — Transsion Tier 3 silent AudioTrack screen gating (OPT-O13-04)
- **Area:** Battery / Transsion Hiber freezer / audio
- **Requirement:** The silent `AudioTrack` plays only while the screen is off, and the Hiber shield still holds there.
- **Hardware needed:** Transsion handset (Infinix X6882B or Tecno Camon 20).
- **Procedure:**
  1. Force Tier 3 (AUDIO) — revoke the Bluetooth scan path or use a build without it.
  2. Connect a peer so the session is active and the tier starts.
  3. Screen ON: expect `Paused Tier 3 silent AudioTrack: screen is interactive (OPT-O13-04)`.
  4. Screen OFF: expect `Resumed Tier 3 silent AudioTrack: screen is off (OPT-O13-04)`.
  5. Screen off for 30 minutes: `adb logcat | grep -i hiber` must show no freeze of the Flash UID.
- **Pass criteria:**
  - `playState` is `PLAYSTATE_PAUSED` while interactive and `PLAYSTATE_PLAYING` only while non-interactive.
  - No Hiber freeze while the screen is off and a session is active.
- **Source:** ADR-285, EXP-107
- **Status:** TODO

## PERF-G05 — Transsion audio-focus cooldown against a music player (OPT-O13-07)
- **Area:** Audio / UX / Transsion keep-alive
- **Requirement:** Flash does not fight a foreground music player for audio focus.
- **Hardware needed:** Transsion handset with Spotify or YouTube Music installed.
- **Procedure:**
  1. Start music playback.
  2. Trigger background session activity so the tier is re-evaluated repeatedly (connect/disconnect a peer a few times).
  3. `adb logcat | grep -i "TranssionHiber"`.
- **Pass criteria:**
  - `AUDIOFOCUS_LOSS` is followed by the 10-minute cooldown log and **no** further focus request from Flash until the cooldown ends or the app is foregrounded.
  - Music playback is never interrupted.
- **Source:** ADR-285
- **Status:** TODO

## PERF-G06 — Discovery ECO downscale on screen-off (OPT-O13-05, OPT-O10-01)
- **Area:** Network / discovery / screen lifecycle
- **Requirement:** Screen-off drops discovery to ECO unless a call, transfer or PTT session is active; screen-on restores the preferred mode.
- **Hardware needed:** Any physical Android device with a peer nearby.
- **Procedure:**
  1. Set the preferred mode to `STANDARD`.
  2. Screen OFF: expect `downscaling discovery to ECO` (and `setScreenActive(false)` reaching `CompositeDiscovery`).
  3. Screen ON: expect the preferred-mode restore and the re-arm lines.
  4. Start a transfer, screen OFF: discovery must stay active until the transfer ends, then downscale (this is `checkScreenOffDownscale`).
  5. Collect ticker/broadcast counts over 10 minutes screen-off for both modes if the probe is available.
- **Pass criteria:**
  - The two calls arrive on the right edges (the log lines above), and the in-transfer case does **not** downscale.
  - Recorded: whatever wakeup/broadcast count the probes give, ECO versus STANDARD, same device. There is no "%" target.
- **Source:** ADR-283, EXP-? (no experiment: unmeasured), plan §3 `O13-05`/`O10-01`
- **Status:** TODO

## PERF-G07 — Cold-start subnet sweep deferral (OPT-O01-04)
- **Area:** Startup / discovery / sockets
- **Requirement:** The automatic fallback sweep starts 30 s after engine start, not at launch; an explicit scan is still immediate.
- **Hardware needed:** Any physical Android device on a LAN with a peer that mDNS can find.
- **Procedure:**
  1. Force-stop Flash, clear logcat, cold start.
  2. `adb logcat -d | grep -i sweep` — note the first probe timestamp relative to process start.
  3. Press "Scan network" immediately after launch: probes must start at once.
- **Pass criteria:**
  - No subnet probe within the first 30 s of the automatic path; the manual scan probes immediately; the peer is still found by mDNS/NSD in the normal couple of seconds.
- **Source:** ADR-286, EXP-108
- **Status:** TODO

## PERF-G08 — Idle foreground service auto-stop (OPT-O13-06, ADR-232)
- **Area:** Service / Play policy / settings
- **Requirement:** With nobody connected, nothing queued and the switch off, the `connectedDevice` service stops ~30 s after the app leaves the foreground; the opt-in keeps it up; any activity in the window cancels the stop.
- **Hardware needed:** Any physical Android device (best on one that shows the FGS notification).
- **Procedure:**
  1. Fresh session, no peers, no transfers, hub not enrolled or offline. Open Flash, then press Home and let the screen stay on.
  2. Expect `Idle grace expired…` / `Stopping connectedDevice foreground service` ~30 s later, and the service's notification gone (`adb shell dumpsys activity services com.transfer.flash`).
  3. Re-open Flash: the service is running again (`onStart` → `start`).
  4. Repeat, but connect a peer within the 30 s: expect `Idle grace expired but work reappeared; keeping…` and the service alive.
  5. Repeat step 1 with **Settings → Network & Discovery → "Keep listening in the background"** ON: the service must stay up indefinitely.
  6. Check the row reads "Keep listening in the background" with the subtitle "Stay reachable with nobody connected; the system may still stop it", and that searching "background transfers" still finds it.
- **Pass criteria:**
  - All six steps behave as described; step 2 leaves no visible foreground notification.
- **Source:** ADR-284, ADR-232
- **Status:** TODO
