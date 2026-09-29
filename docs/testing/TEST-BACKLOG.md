# Test backlog — every test still owed

**Why this file exists (owner, 2026-09-29):** development goes feature-first. Device tests, measurements and
optimisation happen later, one by one, in the owner's spare time. This file is the single list of what has been
built but **not yet tested on real devices**, so nothing is forgotten when work moves on to the next topic.

**Rules (also in `AGENTS.md` §35):**
- Any AI that finishes work without its device/manual test adds the test here **in the same session**, before
  moving to another topic. The owner changing topic is not a reason to skip this.
- When the owner reports a result, record it in the test's **Status** line *and* in the [Results log](#results-log),
  then update the source (the ERROR entry, plan or component doc).
- A **FAIL** gets an `ERROR-NNN` entry in `logs/errors.md`; link it from the test.
- Never delete a test. Mark it `PASS`, `FAIL (ERROR-NNN)`, `BLOCKED (why)` or `OBSOLETE (why)`.
- Status values: `TODO` → `PASS` / `FAIL` / `BLOCKED` / `OBSOLETE`. Add the date, build (commit) and devices.

**Order:** do the functional tests (sections 1–4) first, in any order. Do the measurements (section 5) last,
because their numbers tune the code (owner decision P8, `docs/network/PRESENCE-CONNECTIONS-PLAN.md`).

## 0. Before any test session

- **Build:** install the current Android build on every phone, and use the latest desktop build
  (`dist/Flash-desktop-portable-<date>.zip` or `:desktop:run`). Note the commit (`git log --oneline -1`).
- **Logs:** follow [`docs/calling/CALL-TEST-LOGGING.md`](../calling/CALL-TEST-LOGGING.md) §1.
  - Phone: `adb logcat -v time > phone-<name>.txt`, started before the test.
  - Desktop: copy `%USERPROFILE%\.flash\desktop.log` **before relaunching Flash**, because it is overwritten at
    every launch.
- **Screen-off tests on Infinix/Transsion phones:** the "Hiber" freezer stops Flash about 10 s after screen-off
  (ERROR-074). Grep logcat for `Hiber` before trusting any screen-off result. The user-side workaround is in
  `docs/android-platform-notes.md` (2026-09-28).
- **Devices seen so far:** BelFone SCP810 (Android 8.1, LOW), Infinix (API 34, Transsion), V760, Flash Meerkat
  (W999), Samsung, and two Windows desktops. Use whatever is available; write down which.

---

## 1. Recent call fixes (2026-09-29)

### CALL-01 — Calls from the phone hosting the Wi-Fi hotspot (ERROR-079, ADR-054)
- **Setup:** phone A hosts the hotspot; phone B and a desktop join it.
- **Steps:**
  1. Group video call started from A.
  2. Group video call started from B, with A joining.
  3. 1:1 calls A ↔ B and A ↔ desktop.
  4. If a Samsung is available, repeat with it as the host (its interface is `swlan0`).
- **Pass:** every leg reaches `Connected`. A's logcat shows `WebRTC local network reported: ap0 [<ip>] handle=0` and
  the engine line ends `localNet=true`.
- **On fail:** capture `adb logcat | grep -iE "local network|NetworkMonitor|BindSocket|ice="` from A.
- **Status:** TODO

### CALL-02 — Desktop on webrtc-java 0.19.0 (ADR-054)
- **Setup:** the new portable build on **both** PCs.
- **Steps:** a 1:1 audio call, a 1:1 video call, and a group video call with phones. Switch the camera, microphone
  and speaker during a call. Do three calls in a row without restarting Flash.
- **Pass:**
  - No crash, and audio and video work in both directions.
  - `CALL_DIAG leg=` lines show `vin`/`vout` numbers.
  - Private memory after each hang-up does not climb from call to call.
- **Status:** TODO

### CALL-03 — Desktop memory stays flat, and idle heap is returned (ERROR-075, ERROR-078, EXP-017)
- **Steps:** run a 4-person video call for 5+ minutes, hang up, and wait 1 minute idle. Read Task Manager's
  "Memory (private working set)" at each step.
- **Pass:**
  - `CALL_DIAG proc committed=` stays flat during the call. It was 620–631 MB on 2026-09-29 with the leak fixed,
    versus +52 MB/s before.
  - After a minute idle, memory is below the 527 MB measured before `-Xms64m -XX:G1PeriodicGCInterval=30000`.
- **Record:** the numbers in EXP-017 or a new EXP entry.
- **Status:** TODO

### CALL-04 — Late joiner connects (ERROR-076)
- **Steps:** start a 3-way group call, then have a 4th device join after it is running. Repeat with the joiner
  leaving and rejoining.
- **Pass:** the late joiner's legs connect, with no `CERTIFICATE_UNKNOWN`/DTLS failure, and no second offer within
  3 s of the first in `GROUP_CALL` lines.
- **Status:** TODO

### CALL-05 — "Send smaller video in groups" (ADR-053)
- **Steps:**
  1. Turn the setting on in Settings, then run a 3–4 person video call.
  2. Separately, with the setting off, let the CPU banner appear on a desktop and tap **Send smaller**.
- **Pass:**
  - With the setting on, `CALL_DIAG` shows `sending={…=360…}` with 3+ watchers and 540 with 2. `vout … enc=` times
    drop compared with the setting off.
  - The banner tap turns the setting on, it shows in Settings, and it takes effect within 1–2 s.
- **Status:** TODO

### CALL-06 — Desktop sends 640x360 although 720 was granted (anomaly, EXP-017)
- **Steps:** a group video call from a desktop with one watcher on 5 GHz or Ethernet.
- **Pass:** `vout` height matches the granted height (`sending={…=720}`, `limit=none`). If it stays 360, find out
  whether the camera mode or the encoder scales it down (`[webrtc-jvm] camera … opened at` on the console).
- **Status:** TODO

### CALL-07 — Two desktops pair (ERROR-077)
- **Steps:** two fresh desktop installs on the same LAN; pair them; chat, then make a call between them.
- **Pass:**
  - Pairing completes and each desktop shows a unique animal/fruit default name.
  - No `own key` TOFU refusal for the real peer.
- **Status:** TODO

## 2. Group calling plan (`docs/calling/GROUP-VIDEO-PLAN.md`, G1–G7)

### GRP-01 — One video per participant (G1, UI-050b)
- **Steps:** 3 devices in a video call; then one leaves.
- **Pass:**
  - Each device sees both other videos.
  - When one leaves, only that tile disappears, with no black flash on the others.
  - On Android the local PiP draws above the tiles. If it doesn't, give the PiP `setZOrderMediaOverlay(true)`.
- **Status:** TODO

### GRP-02 — Network band detection (G2)
- **Steps:** open the call stats badge on Android and desktop on 5 GHz, 2.4 GHz and Ethernet (as available), and on
  a phone hosting the hotspot.
- **Pass:**
  - The correct band shows on each device.
  - The hotspot host shows no band, and its peers' links take the other end's band.
  - `WifiInfo.getFrequency()` is not redacted without the location permission on the Infinix (API 34) and the V760.
- **Status:** TODO

### GRP-03 — Video only on request (G3)
- **Steps:** 3 devices on MEDIUM/HIGH tier, all cameras on, nobody focused; then focus one person.
- **Pass:**
  - With nobody requesting, no outbound video appears in the stats.
  - Outbound video appears only on legs that requested it.
  - The desktop honours `active=false`. If it doesn't, the plan's fallback is `replaceTrack`.
  - `media-source` `audioLevel` is present on Android and desktop (the speaking indicator works).
- **Status:** TODO

### GRP-04 — LOW device follows the speaker (G3, G5, UI-050c)
- **Steps:** a LOW phone (e.g. BelFone) in a 3-way video call; the others take turns speaking; tap a chip, then tap
  it again.
- **Pass:**
  - The LOW phone receives one video, and the main tile follows the speaker after about 2 s.
  - A chip tap moves the video within about 1 s (note the keyframe delay). A second tap returns to the speaker.
- **Status:** TODO

### GRP-05 — Pin on MEDIUM/HIGH (G5, UI-050c)
- **Steps:** tap a grid tile; tap someone outside the receive limit; use TalkBack on the tiles and chips.
- **Pass:**
  - A tap pins, and a tap on the pinned tile unpins.
  - Pinning someone outside the limit swaps them in.
  - TalkBack reads the pin/unpin labels.
- **Status:** TODO

### GRP-06 — Old build in the same call (G3, ADR-049)
- **Steps:** one device on a build from before 2026-09-28 joins a group video call with current builds.
- **Pass:** the old device still sends and receives video, and nobody's call breaks.
- **Status:** TODO

### GRP-07 — Budgets by tier and band (G4)
- **Steps:**
  1. A 3-way call on 2.4 GHz with 3+ watchers of one HIGH sender; then one watcher leaves.
  2. The same on 5 GHz.
- **Pass:**
  - On 2.4 GHz, outbound `frameHeight` is 360 on every leg, and returns to 540 about 5 s after one watcher leaves.
  - On 5 GHz, per-leg `frameHeight` follows the requested height.
  - Measured bitrates are within the plan's §4.2 table.
- **Status:** TODO

### GRP-08 — Health warnings (G6, UI-050d)
- **Steps:**
  1. On the BelFone, `adb shell cmd thermalservice override-status 2`: expect the WARM banner. Tap **Show fewer**,
     then **Show all**.
  2. `override-status 3`: expect the HOT banner; another device tries to focus this one.
  3. Reset with `override-status 0` and run a normal call.
- **Pass:**
  - Show fewer → compact layout plus the pill; Show all → back to the grid.
  - HOT → one video, and the other device's request is turned down with `thermal`.
  - A normal call shows no banner.
  - `decoderImplementation` is reported on both Android and desktop.
- **Record:** the CPU % of a normal 3-way call (it feeds MEAS-05).
- **Status:** TODO

### GRP-09 — Call size caps (G7, ADR-050)
- **Steps:** a debug build with `FlashGroupCallLimits` lowered to 3; a 4th device tries to join.
- **Pass:** the 4th device gets a clear "Call is full" end; the 3 in the call are unaffected.
- **Status:** BLOCKED for real group sizes until vouched groups (GV, ADR-044) land; the debug-build check can run
  now.

## 3. Presence & connections plan (`docs/network/PRESENCE-CONNECTIONS-PLAN.md`, PC1–PC5)

### PC-01 — Keepalive efficiency (PC1)
- **Steps:**
  1. Two phones plus the desktop with sessions up; screens off for 1 hour (non-Transsion phones, or Hiber handled).
  2. Separately, pair a current build with an older one and leave them idle.
- **Pass:** no peer flaps offline during the hour, and the mixed-version pair stays connected.
- **Status:** TODO

### PC-02 — Connection planner (PC2, ADR-045)
- **Steps:**
  1. Pair two devices immediately after they discover each other.
  2. Connect over a hotspot (gateway probe).
  3. Start a call (auto-dials go quiet).
  4. Toggle Wi-Fi off/on on a device with several peers.
- **Pass:**
  - Pairing works on the first try, and the hotspot client connects to the host.
  - No auto-dial storms during the call.
  - After the Wi-Fi toggle, reconnects are spread over about 2 s (`Auto-connect`/reconnect timestamps), and everyone
    comes back.
- **Status:** TODO

### PC-03 — Connected / Online / Offline dots and dial on demand (PC3, UI-030b)
- **Steps:** watch a newly discovered peer's dot; send a text to a peer showing the ring; use TalkBack.
- **Pass:**
  - A ring (Online) appears before the session lands, then turns solid (Connected).
  - A message to a ring peer delivers (dial on demand, about 1 s).
  - TalkBack reads "Connected" / "Online".
- **Status:** TODO

### PC-04 — Presence sharing (PC4, ADR-046)
- **Setup:** three phones, where user 3 cannot discover user 2 directly (e.g. user 3 is a hotspot client) but
  both are connected to user 1.
- **Steps:**
  1. Check how user 3 shows user 2.
  2. Switch user 2 to Ghost.
  3. Switch user 2 back, then have user 3 message user 2.
- **Pass:**
  - User 3 sees user 2 as Online through user 1.
  - Ghost on user 2 removes the ring on user 3 within about 1 s.
  - The message delivers through the tip dial.
- **Status:** TODO

### PC-05 — Modes ECO / STANDARD / BOOST (PC5, ADR-048)
- **Steps:**
  1. Put two phones and the desktop in each mode in turn.
  2. In ECO, leave the devices idle for 10 min.
  3. Switch modes during a call, and during a transfer.
  4. Cycle the Quick Settings tile.
  5. Run a 1-hour screen-off test per mode.
- **Pass:**
  - ECO holds ≤ 3 neighbours plus active peers.
  - Switching modes drops no call or transfer.
  - The tile cycles Standard → Ghost → Eco → Boost → Off.
  - No flaps in the screen-off runs.
- **Status:** TODO

## 4. Older device checks still owed

From `logs/errors.md` entries whose status still says a device check is owed. Several may have been covered
by later testing without anyone recording it: confirm, then mark PASS here **and** update the ERROR entry.

| ID | Source | What to check | Status |
|---|---|---|---|
| OLD-01 | ERROR-074 (OPEN) | Transsion Hiber: does the documented user workaround keep Flash reachable with the screen off? | TODO |
| OLD-02 | ERROR-073 (suspected) | After closing Flash on one device, do peers keep dialing a closed port every 5 s? (grep `Auto-connect`) | TODO |
| OLD-03 | ERROR-062 (OPEN) | Phone → desktop image/video send completes (data-port probe) | TODO |
| OLD-04 | ERROR-063 | Video playback in chat on desktop and Android (voice + chat-accept already verified) | TODO |
| OLD-05 | ERROR-064 | The receiver shows progress and speed during an inbound transfer | TODO |
| OLD-06 | ERROR-054–061 | Desktop call audio: two-way audio, AEC, output switch, no buzz (likely covered by recent desktop calls; confirm and close) | TODO |
| OLD-07 | ERROR-046, 047, 050 | PTT: Leave updates the member count on two devices; playout doesn't repeat; a ping reaches every peer | TODO |
| OLD-08 | ERROR-037, 038 | Group media intro and group sync acks on physical devices | TODO |
| OLD-09 | ERROR-035 | All four link-change defects (hotspot host dialing, link changes seen) | TODO |
| OLD-10 | ERROR-018 | Sender pause/resume of a transfer; no hang or deadlock | TODO |
| OLD-11 | ERROR-015 | WS mesh transfer assembles files correctly across devices | TODO |
| OLD-12 | ERROR-009, 010, 011 | Composer: no blank band with the keyboard; voice-record gesture; focus overlay dismisses with one tap | TODO |
| OLD-13 | ERROR-002, 003, 004 (OPEN, old LAN probe) | Still reproducible on current builds? If not, mark OBSOLETE | TODO |
| OLD-14 | `docs/migration/ADAPTIVE-UI-PLAN.md` AD-1–AD-7 | Desktop UI scale, list-pane width, conversation in the detail pane, keyboard shortcuts, 580 dp bubble cap, Android rail + two panes at ≥ 600 dp, desktop keeps the open conversation across tabs | TODO |

### MIG-01 — Android install upgraded across an older release keeps its chats (ERROR-080, ADR-055)
- **Setup:** one phone, the oldest build you can still install (git tag `v1.0.0` or `v1.1.0`), same signing key as the
  current build so it installs over it.
- **Steps:**
  1. On the old build, pair with another device and exchange a few messages (a reply and an attachment if the build
     supports them).
  2. Install the current build **over** it (do not uninstall). Open Flash and open the same chat.
  3. Create a small group and send a group message (uses the v4 tables).
- **Pass:** old messages are all still there; no crash; `adb logcat` has no `A migration from` and no Room
  `IllegalStateException`; the group message sends. `FlashMigrations` now builds its statements from the shared list, so
  this is the one place the Android wrapper is exercised on a real database.
- **Status:** TODO

### MIG-02 — Desktop still opens its existing chat database (ERROR-080, ADR-055)
- **Setup:** a PC that already has chats, i.e. `%USERPROFILE%\.flash\chat\flash.db` exists.
- **Steps:** start the new desktop build over the existing state directory and open an old conversation.
- **Pass:** history is present and `%USERPROFILE%\.flash\desktop.log` has no Room/migration error and no fallback to an
  empty chat. (There is no older desktop file to upgrade, so this is a regression check, not an upgrade check.)
- **Status:** TODO

## 5. Measurements — do these last

They replace every *(measure)* estimate in the plans and decide tuning. Record each in `logs/experiments.md`.

| ID | What | Procedure | Decides | Status |
|---|---|---|---|---|
| MEAS-01 | **PC0** baseline: screen-off battery/hour with 0/1/4/8 idle sessions and 19 peers; reconnect after a Wi-Fi toggle | [`docs/network/PC0-RUNBOOK.md`](../network/PC0-RUNBOOK.md) (peer farm + `tools/pc0/phone-baseline.ps1`) | How aggressive ECO must be | TODO |
| MEAS-02 | **PC6** scale: 20 peers per mode (battery, reconnect storm, delivery latency to a screen-off ECO phone) | Same rig as PC0 | ECO's ~1 min bound; whether groups go to 32; then **PC7** tuning and the default mode | TODO |
| MEAS-03 | **G0** calls: 3-way video CPU, temperature and dropped frames; decoder instances; voice with 4/8/12 connections; 2.4 vs 5 GHz throughput | GROUP-VIDEO-PLAN §6 G0 | The §4.2 budget tables | TODO |
| MEAS-04 | **G0 codecs** C1 (desktop VP9 on webrtc-java 0.19.0), C2 (`MediaCodecList` on the BelFone and a mid-range phone), C3 (VP8 software vs H.264/VP9 hardware, 540p, 10 min) | GROUP-VIDEO-PLAN §4.6 | Whether **G4b** is built | TODO |
| MEAS-05 | CPU warning threshold (40 % of all cores for 30 s) against real calls | `CALL_DIAG proc cpu=` from GRP-08 and CALL-03 | The G6 threshold | TODO |
| MEAS-06 | Desktop render cost: capture + BGRA conversion + Skia upload was ~1.5 of ~2.5 cores (EXP-017) | Profile a 4-person desktop call | Whether hardware video (ADR-052) or render work comes first | TODO |

---

## Results log

Newest first. One entry per test session: date, build, devices, tests run, result, and links (ERROR/EXP).

*(No results yet. Backlog created 2026-09-29 at commit `4e71c5d`.)*
