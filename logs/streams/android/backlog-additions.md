# Stream G (Android) — Test Backlog Additions

Every test in this file must be executed on physical hardware during a device test session (AGENTS.md §35).
The integrator will fold these into `docs/testing/TEST-BACKLOG.md`.

---

## PERF-G01 — Standby CPU WakeLock Release and Deep Sleep (OPT-O13-01)
- **Area:** Battery / Wake Locks / AP Suspend
- **Requirement:** When no file transfer, WebRTC call, or PTT session is active and mesh is idle, CPU partial wake lock must be released, allowing AP suspend.
- **Hardware needed:** Physical Android device (e.g. Pixel 8 / Samsung Galaxy / BelFone SCP810).
- **Procedure:**
  1. Open Flash, pair with a nearby device, confirm connection is established.
  2. Put app in background and turn screen off.
  3. Let device sit idle for 60 minutes with screen off.
  4. Run `adb shell dumpsys power | grep -A 10 "Wake Locks"` and check `flash:ws-mesh`.
  5. Run `adb shell dumpsys batterystats --charged com.transfer.flash`.
- **Pass criteria:**
  - `flash:ws-mesh` wake lock is NOT held continuously during idle period.
  - Deep sleep / AP suspend percentage exceeds 90% over the screen-off interval.

---

## PERF-G02 — Keepalive Worker Fast-Path with Empty Outbox (OPT-O13-02)
- **Area:** Battery / WorkManager / SQLite
- **Requirement:** `FlashKeepaliveWorker` must exit in < 100 ms without starting network sockets or wake locks when outbox and transfer queues are empty.
- **Hardware needed:** Physical Android device.
- **Procedure:**
  1. Ensure all chat outboxes and file transfer queues are empty.
  2. Force run the keepalive worker via `adb shell cmd jobscheduler run -f com.transfer.flash <jobId>`.
  3. Inspect logcat: `adb logcat -d | grep "FlashKeepalive"`.
- **Pass criteria:**
  - Logcat shows: `keepalive: outbox and transfer queues empty; skipping headless engine boot (OPT-O13-02)`.
  - Execution completes in < 100 ms; no WebSocket server or NSD sockets are opened.

---

## PERF-G03 — Android 15 FGS Timeout During Verification (OPT-O13-03)
- **Area:** Storage / Transfer Resilience / Android 15 Timeout
- **Requirement:** When Android 15 invokes `Service.onTimeout()`, active file verification (`FlashTransferState.Verifying`) must be paused cleanly with `SERVICE_TIMEOUT` and resume cleanly.
- **Hardware needed:** Android 15 (API 35) physical device or emulator.
- **Procedure:**
  1. Initiate transfer of a large 10 GB file.
  2. Wait until chunk transfer completes and state enters `Verifying` (SHA-256 hash calculation).
  3. Trigger service timeout via `adb shell cmd activity timeout-service com.transfer.flash/.debug.FlashBackgroundService`.
  4. Verify service pauses the transfer cleanly and calls `stopSelf()`.
  5. Reopen app and tap Resume.
- **Pass criteria:**
  - Transfer is saved in database with paused reason `SERVICE_TIMEOUT`.
  - Reopening app allows resuming hash verification without corrupted data or missing chunks.

---

## PERF-G04 — Transsion Tier 3 Silent AudioTrack Screen-Off Gating (OPT-O13-04)
- **Area:** Battery / Transsion OEM Hiber Freezer / Audio
- **Requirement:** On Transsion devices (Infinix, Tecno, itel), silent `AudioTrack` must pause when screen is ON and play only when screen is OFF.
- **Hardware needed:** Physical Transsion device (e.g. Infinix X6882B / Tecno Camon 20).
- **Procedure:**
  1. Revoke Bluetooth scan permission to force Tier 3 (AUDIO) keepalive.
  2. Connect to a peer; verify Tier 3 starts in `FlashBackgroundService`.
  3. Turn screen ON: inspect logcat for `Paused Tier 3 silent AudioTrack: screen is interactive`.
  4. Turn screen OFF: inspect logcat for `Resumed Tier 3 silent AudioTrack: screen is off`.
- **Pass criteria:**
  - `AudioTrack.playState` is `PLAYSTATE_PAUSED` while screen is interactive.
  - `AudioTrack.playState` is `PLAYSTATE_PLAYING` only while screen is non-interactive.
  - Hiber does not freeze Flash process during screen-off sleep (`adb logcat | grep Hiber` shows 0 freeze events).

---

## PERF-G05 — Transsion AudioFocus Loss Cooldown with Spotify (OPT-O13-07)
- **Area:** Audio / UX / Transsion Hiber Keepalive
- **Requirement:** Transsion keepalive must not fight for audio focus when external media playback (Spotify) is active.
- **Hardware needed:** Physical Transsion device with Spotify or YouTube Music installed.
- **Procedure:**
  1. Start Spotify music playback in background.
  2. Open Flash and trigger background session activity.
  3. Inspect logcat: `adb logcat | grep "TranssionHiber"`.
- **Pass criteria:**
  - Flash receives `AUDIOFOCUS_LOSS`, logs 10-minute cooldown arming, and does NOT re-request audio focus.
  - Spotify music playback is never interrupted or stuttered.

---

## PERF-G06 — Discovery ECO Mode Downscale on Screen-Off (OPT-O13-05, OPT-O10-01)
- **Area:** Network / Discovery / Screen Lifecycle
- **Requirement:** Turning screen off drops discovery to `ECO` mode unless active transfer or call is in progress; turning screen on restores preferred mode (`STANDARD`/`BOOST`).
- **Hardware needed:** Any physical Android device.
- **Procedure:**
  1. Open Flash with preferred mode set to `STANDARD`.
  2. Turn screen OFF: verify logcat shows `downscaling discovery to ECO`.
  3. Turn screen ON: verify logcat shows `restores preferredMode STANDARD` and `re-arming discovery`.
  4. Start a file transfer, turn screen OFF: verify discovery mode stays active until transfer finishes.
- **Pass criteria:**
  - `CompositeDiscovery` receives `setScreenActive(false)` on screen off and `setScreenActive(true)` on screen on.
  - Discovery broadcast frequency and ticker wakeups drop by > 80% while screen is off.
