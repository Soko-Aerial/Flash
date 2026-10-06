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
- **Status:** PARTIAL (owner-reported 2026-10-02). The owner ran a video call with **3 devices** and another with
  **4 devices** and puts the realistic limit at 3 to 4 devices. Not recorded: which build, which devices, the log
  lines, how the call looked on each. Still TODO from this test: the "one leaves, only that tile disappears, no
  black flash" check, the Android PiP z-order check, and a log review for the `video sender tuned` /
  `CALL_DIAG` lines. The pass lines above are not marked done until those are reported.

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

### GRP-10 — Where group video stops being usable (owner report 2026-10-02)
- **Why:** the owner tested 3 and 4 devices in a video call and calls 3 to 4 the realistic limit (GRP-01). The
  code still allows 8 video participants (`FlashGroupCallLimits`) and the per-tier budgets in
  `GROUP-VIDEO-PLAN.md` are design estimates. One owner observation on unrecorded devices is not a measured limit.
- **Steps:** on a hotspot or a 5 GHz network, run video calls of 3, 4, 5, 6 and 8 devices if that many are
  available, cameras on, everyone watching everyone for 5 minutes each. Record the build and every device with
  its tier and band.
- **Pass (this is a measurement; the result may move the figure):**
  - For each size: received fps and freeze count per tile, `CALL_DIAG proc cpu=`, battery drop and temperature
    on the weakest phone, and whether the health warnings (GRP-08) fire.
  - A written answer: the largest size that stays usable on a LOW, a MEDIUM and a HIGH phone. Compare it with
    the 3 to 4 figure; if they differ, update the deck/README wording and decide whether to lower the code cap.
- **Source:** GRP-01 owner report; `docs/calling/GROUP-VIDEO-PLAN.md` section 8; feeds MEAS-05 and MEAS-09.
- **Status:** TODO

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
  4. Pair with a device and let it connect once (writes a DR1 route into the v5 `remembered_endpoints` table; see DR-01).
- **Pass:** old messages are all still there; no crash; `adb logcat` has no `A migration from` and no Room
  `IllegalStateException`; the group message sends; `Remembered route saved peer=` appears after step 4 (the v5 table
  exists and is writable). `FlashMigrations` now builds its statements from the shared list, so
  this is the one place the Android wrapper is exercised on a real database.
- **Status:** TODO

### MIG-02 — Desktop still opens its existing chat database (ERROR-080, ADR-055)
- **Setup:** a PC that already has chats, i.e. `%USERPROFILE%\.flash\chat\flash.db` exists.
- **Steps:** start the new desktop build over the existing state directory and open an old conversation.
- **Pass:** history is present and `%USERPROFILE%\.flash\desktop.log` has no Room/migration error and no fallback to an
  empty chat. (There is no older desktop file to upgrade, so this is a regression check, not an upgrade check.)
- **Status:** TODO

## 4b. Discovery resilience plan (`docs/network/DISCOVERY-RESILIENCE-PLAN.md`, DR1)

### DR-01 — A paired peer reconnects after a restart with multicast blocked (DR1, ADR-047)
- **Setup:** the desktop plus one phone that are already paired, on the same Wi-Fi. Admin PowerShell on the desktop.
  Logs: phone `adb logcat -v time -s WS:I`, desktop `%USERPROFILE%\.flash\desktop.log` (copy before relaunch).
- **Steps:**
  1. With discovery working, let the two connect normally. Note who dialed: the lower device id dials first, and only
     the dialing side stores the route (an inbound session does not tell you the peer's listening port).
  2. Look for `Remembered route saved peer=<id> at <ip>:45822` on the dialing side. Do the same with an **unpaired**
     device: it must not produce that line.
  3. Break multicast on the desktop (admin PowerShell), one rule per direction. TCP 45822 stays open:
     `New-NetFirewallRule -DisplayName flash-dr1-in -Direction Inbound -Protocol UDP -LocalPort 5353,45823 -Action Block`
     `New-NetFirewallRule -DisplayName flash-dr1-out -Direction Outbound -Protocol UDP -RemotePort 5353,45823 -Action Block`
  4. Force-stop Flash on the phone and quit it on the desktop; start both again. Wait 30 s.
  5. Remove the rule: `Remove-NetFirewallRule -DisplayName flash-dr1-in,flash-dr1-out`.
- **Pass:**
  - After step 4 the peers show Connected without a Wi-Fi toggle. The dialing side's log has
    `Auto-connect dialing peer=<id> id=<id> at <ip>:45822` and `Auto-connect result peer=<id> success=true`, and has **no**
    `Discovered endpoints:` line naming that peer before it (discovery really was silent).
  - `Remembered routes: loaded 1 route(s) for 1 paired peer(s)` appears at startup.
  - The unpaired device never appears in a `Remembered route` line.
- **Also note (does not fail the test):** whether the peer showed Online/ring during the silent period. It should not:
  a remembered route is a dial hint and never makes a peer look present.
- **Source:** ADR-047, plan §3.3 A and §4 DR1. Unit coverage: `RememberedRoutesTest`, `JvmRouteObserverTest`,
  `RouteObserverTest`, `FlashJvmMigrationsTest`.
- **Status:** TODO

### DR-02 — A phone finds the desktop through the broadcast beacon when multicast is blocked (DR2, ADR-047)
- **Setup:** the desktop plus one phone on the same Wi-Fi (a /24, e.g. `192.168.1.x`), both on the DR2 build. Admin
  PowerShell on the desktop. Phone log: `adb logcat -v time -s MulticastTransport:I DISCOVERY:I`; desktop log
  `%USERPROFILE%\.flash\desktop.log` (copy before relaunch).
- **Steps:**
  1. Start both apps with no rules. In each log find the bind line
     `Broadcast beacon to <iface>=<a.b.c.255> ... (port 45823)`. The phone's Wi-Fi interface (`wlan0`) must show its
     subnet's `.255` address, **not** `none`.
  2. Block only **multicast sends** on the desktop (broadcast is not covered by this range). This also stops JmDNS, so
     the phone cannot learn the desktop by mDNS:
     `New-NetFirewallRule -DisplayName flash-dr2-out -Direction Outbound -Protocol UDP -RemoteAddress 224.0.0.0/4 -Action Block`
  3. Force-stop Flash on the phone and quit it on the desktop, then start both again. Wait 30 s.
  4. Optional: repeat step 3 with the phone's screen off for 2 minutes (some devices filter broadcast with the screen
     off: reported, unverified). Note the result; it does not fail the test.
  5. Remove the rule: `Remove-NetFirewallRule -DisplayName flash-dr2-out`.
- **Pass:**
  - Step 1: both logs have the bind line, and the phone's Wi-Fi interface has a real `.255` address.
  - After step 3 the phone's log has `MulticastTransport: Found <desktop name> at <desktop ip>:45822 via <iface>`
    (the desktop's multicast is blocked, so only the broadcast can have carried it), and **no** `DISCOVERY` line
    `Peer capabilities ... name=<desktop name>` (NSD resolving it) in that window: the control that the rule really
    silenced mDNS.
- **Also note:** whether the desktop found the phone (it should: the phone's multicast still reaches it), and whether
  the desktop's bind line lists adapters that are not the LAN (Hyper-V, VPN). DR5 filters those.
- **A FAIL means:** the bind line says `none` (address detection on Android: look at `getInterfaceAddresses()` prefix
  length, see the fallback in the factory), or the bind line is right and nothing arrives (the network or the phone
  drops broadcast: an OEM filter, or client isolation). Either is a new `ERROR-NNN`, and the second is a DR0 datum.
- **Source:** ADR-047, plan §3.3 B and §4 DR2. Unit coverage: `DirectedBroadcastTest`, `MulticastTransportTest`
  (broadcast cases), and the real-socket `JvmDirectedBroadcastSocketTest` (Windows JVM only).
- **Status:** TODO

### DR-03 — "Scan network" finds a peer that discovery cannot see (DR3, ADR-047)
- **Setup:** the desktop plus one phone on the same network, both on the DR3 build, both already paired. Best case: the
  desktop and a phone as **clients of a third phone's Wi-Fi hotspot** (hotspot clients cannot see each other's multicast).
  Otherwise the home Wi-Fi with multicast blocked on the desktop (admin PowerShell, this also stops JmDNS and the beacon):
  `New-NetFirewallRule -DisplayName flash-dr3-out -Direction Outbound -Protocol UDP -RemoteAddress 224.0.0.0/4 -Action Block`
  `New-NetFirewallRule -DisplayName flash-dr3-bcast -Direction Outbound -Protocol UDP -RemoteAddress 255.255.255.255,192.168.0.255,192.168.1.255 -Action Block`
  (adjust the broadcast address to the real subnet). Phone log: `adb logcat -v time -s WS:I`; desktop log
  `%USERPROFILE%\.flash\desktop.log` (copy before relaunch).
- **Steps:**
  1. Start both apps, wait 30 s. Nearby should show no device (the paired peer is Offline).
  2. On the phone open Nearby and tap **Scan network** (header text, or the pill in the empty panel). Watch the caption.
  3. Repeat on the desktop.
  4. Tap **Scan network** twice within 5 s: the second must do nothing visible ("Scanned a moment ago" or ignored).
  5. Leave both apps alone on the blocked network, **no live session**, for about 2 minutes without tapping anything.
  6. Optional: tap **Scan network** on a large network (a /16 such as `10.0.0.0/16`, if you have one) and read the caption.
  7. Remove the rules: `Remove-NetFirewallRule -DisplayName flash-dr3-out,flash-dr3-bcast`.
- **Pass:**
  - Step 2: the caption reads "Scanning this network… n%" then "Found 1 device to connect to."; the log has
    `Sweep started hosts=… manual=true` and `Sweep finished probed=… answered=1 hosts=<ip>`, then
    `Auto-connect dialing sweep hit at <ip>:45822 (subnet sweep)` and `Auto-connect result sweep hit <ip> success=true`;
    the peer becomes Connected without a Wi-Fi toggle. The log has **no** `Discovered endpoints:` line naming that peer before the hit.
  - Step 3: the same from the desktop.
  - Step 4: only one `Sweep started` line for the two taps.
  - Step 5: an automatic sweep runs once (`Sweep started … manual=false`, no caption result shown on screen) and
    **not again** for 10 minutes.
  - Step 6 (if run): the caption ends with "Only this device's part of a large network was checked."
- **Also note (does not fail the test):** how long a /24 scan takes (log timestamps), and whether the phone's screen-off
  state stops the automatic sweep from completing.
- **A FAIL means:** no `Sweep started` (the action is not wired, or `SweepPolicy` refused: read the caption reason);
  `Sweep finished` with 0 answered although the peer is reachable by TCP (the probe left by the wrong route: Android
  `LanRouteChooser`, ERROR-035; or the peer's server is on an ephemeral port, which DR1 covers); a hit that is dialed but
  never connects (planner rule 9 or `hasSessionAtHost`). Each is a new `ERROR-NNN`.
- **Also confirms (regression):** an ordinary connect on home Wi-Fi and on the hotspot still works, because
  `WsTransferClient`'s route logic moved to `LanRouteChooser` in the same change.
- **Source:** ADR-047 "DR3 implementation notes", plan §3.3 C and §4 DR3, `docs/ui/nearby-page.md` addendum. Unit coverage:
  `SubnetSweepPlanTest`, `SubnetSweeperTest`, `SweepPolicyTest`, `SweepControllerTest`, `TcpHostProbeTest`,
  `ConnectionPlannerTest` / `AutoConnectorTest` (rule 9), `FlashNearbyLogicTest`, `DesktopNearbyStateTest`.
- **Status:** TODO

### DR-04 — Desktop advertises only reachable adapters, and the quiet-network hint (DR5, ADR-047)
- **Setup:** a Windows desktop with at least one virtual adapter besides the real Wi-Fi/Ethernet (Hyper-V `vEthernet`,
  WSL, a VPN tunnel, VirtualBox/VMware host-only: `Get-NetAdapter` lists them). One paired phone on the same Wi-Fi. Both on
  the DR5 build. Desktop log `%USERPROFILE%\.flash\desktop.log` (copy before relaunch); phone log
  `adb logcat -v time -s WS:I MulticastTransport:I`.
- **Steps:**
  1. Start the desktop. Read the two bind lines in its log.
  2. Start the phone, wait 30 s. Confirm the phone finds the desktop and the desktop finds the phone.
  3. Hide the desktop from the phone's discovery: in admin PowerShell
     `New-NetFirewallRule -DisplayName flash-dr4-out -Direction Outbound -Protocol UDP -RemoteAddress 224.0.0.0/4,255.255.255.255 -Action Block`
     (adjust the subnet broadcast as in DR-03), then restart **both** apps so no session exists. Do not tap Scan network.
  4. Open Nearby on the **phone** and leave it on screen for 40 s. Then do the same on the desktop.
  5. Tap **Scan network** in the card; then, on a second try after the peer is Offline again, tap **Connect by IP**.
  6. While the card is showing, get the peer connected (step 5 does it) and watch the card.
  7. Remove the rule: `Remove-NetFirewallRule -DisplayName flash-dr4-out`.
  8. Optional: turn on Windows Mobile Hotspot on the desktop, join it from the phone, and check the phone still discovers the
     desktop (the hotspot adapter must count as real).
  9. Optional: put `include_virtual_adapters=true` in `%USERPROFILE%\.flash\settings.properties`, restart, and read the
     bind lines again.
- **Pass:**
  - Step 1: `Network adapters: using <the real adapter(s)>; skipped virtual/tunnel <the virtual ones>` appears for both
    `JmDNS` and `MulticastTransport`, and the phone's `Found <desktop> at <ip>` names the **real** LAN address, not a
    `172.x`/`10.x` Hyper-V or VPN address.
  - Step 2: also a `Discovery sources: jmdns=[…], multicast=[…]` line naming the peer under at least one source, and no
    further such line until something changes or 5 min pass.
  - Step 4: the card "Can't find your devices / This network may be hiding devices from Flash." is **absent** for the first
    ~30 s and present after it, on both hosts; before it appears the log shows `Discovery sources: jmdns=none, multicast=none`.
  - Step 5: *Scan network* reads "Scanning…" and the peer connects (as DR-03); *Connect by IP* opens the dialog.
  - Step 6: the card leaves by itself once a session is live, even though discovery still lists nothing.
  - Step 8 (if run): the desktop is still discovered over the hotspot.
  - Step 9 (if run): the bind line now lists the virtual adapters as used.
- **A FAIL means:** a virtual address still advertised (heuristic missed the adapter: record its name and description, and
  extend `VirtualAdapters`), the real adapter skipped (worse: record it the same way), the card showing while a session is
  live (the live-session term or the flag wiring), the card never showing (the 30 s timer or the flag), or the hotspot
  desktop not discovered (the hotspot exception). Each is a new `ERROR-NNN`.
- **Also note:** what the adapter names and descriptions actually were, so the heuristic list can be checked against a
  real machine.
- **Source:** ADR-047 "DR5 implementation notes", plan §3.3 E and §4 DR5, `docs/ui/nearby-page.md` addendum. Unit coverage:
  `VirtualAdaptersTest`, `CompositeDiscoveryTest` (source report), `DesktopSettingsStoreTest`, `FlashNearbyLogicTest`,
  `DesktopNearbyStateTest`.
- **Status:** TODO

## 4c. Group trust model (`docs/group/v0-threat-review.md`, ADR-044)

### GT-01 — Honest group flows still work after the V1a hardening (F-1, F-2, F-4, F-5)
- **Setup:** three devices A (creates the group), B and C, all paired with each other, on the V1a build; a fourth device D
  paired with A, B and C for step 6. At least one should be a phone, one the desktop. Phone log
  `adb logcat -v time -s CHAT:I` (V1a rejections are logged at warning level); desktop log
  `%USERPROFILE%\.flash\desktop.log`.
- **Steps:**
  1. A creates "Team" with B and C. Check all three show the group with three members and A as the only owner.
  2. B sends two messages; A and C receive them.
  3. Turn C's Wi-Fi off. A sends three messages and B sends two. Then B leaves the group.
  4. Turn C's Wi-Fi back on. Wait 30 s.
  5. On C, open the group. Then do the same on the desktop if it is a member.
  6. A adds D. Check D gets the group and its history.
  7. C sends a message to the group.
- **Pass:**
  - Step 4/5: C shows the five messages sent while it was away (history sync works: a push is accepted only for a request C
    itself sent), and C's member list shows B as no longer a member (the leave reached C through the reconciled `State`,
    which now carries tombstones).
  - Step 6: D shows the group name, the owner as A, and the earlier history (the bootstrap `State` plus one catch-up
    request to each member).
  - Step 7: A and D receive it; B does not (B left).
  - In none of the steps do the logs contain `Group SyncPush dropped`, `Group State ignored` or `Group Create ignored`, except
    at most one `Group Create ignored` when a `State` beat the `Create` in step 1 (harmless: the `State` created the group).
- **A FAIL means:** history missing on C or D after the wait (a legitimate push was dropped: check the `SyncPush dropped`
  line's `from=` against the peer the request went to, and whether the request/answer crossed a peer reconnect), a member
  that never appears (a `State` from a member this device did not yet know about was ignored; that is the documented
  limit, so note whether the owner was reachable), or the owner shown wrongly. Each is a new `ERROR-NNN`.
- **Also note:** how long C took to show the missed messages after Wi-Fi came back.
- **Source:** `docs/group/v0-threat-review.md` §8 V1a, ADR-044 "V0 findings". The attacks themselves (a paired non-member
  rewriting a group, a repeated roster id, an unsolicited push, an owner swap) cannot be staged from the UI and are covered by
  unit tests only: `RealFlashChatRepositoryTest` (`F-1`, `F-2`, `F-4`, `F-5` cases), `OutgoingSyncRequestTest`.
- **Status:** TODO

### GT-02 — Signed (v2) groups between real devices (ADR-044 V1)
- **Setup:** four devices on the V1 build (`gv=2`): A (creates the group, the owner), B, C and D, all paired with each other; at
  least one phone and the desktop. Optional fifth device E on the **previous** build for step 6. Phone log
  `adb logcat -v time -s CHAT:I` (v2 rejections are logged at warning level with a `SECURITY:` prefix); desktop log
  `%USERPROFILE%\.flash\desktop.log`. Every pair must have a live session before step 1 (a group made with an unconnected
  invitee is legacy by design).
- **Steps:**
  1. A creates "Signed" with B and C. Check all three show the group with three members and A as the only owner.
  2. B and C each send two messages. Check every device shows the four messages under the right names.
  3. Turn C's Wi-Fi off. A sends three messages and B sends two. Then B leaves. Turn C's Wi-Fi back on and wait 30 s.
  4. A adds D. Check D gets the group and its history.
  5. On B (the former member, or on C if B is gone from the UI), try to add a member to the group.
  6. *(Optional, needs E)* A creates "Mixed" with B and E.
- **Pass:**
  - Step 1: A's log has `Group v2 created: group=g2-...`; B's and C's logs have `Group v2 joined: group=g2-... owner=<A's id>`;
    no `Group Create` line for that group.
  - Step 2: no `SECURITY:` line on any device; names are the ones A's device gave B and C (the signed labels).
  - Step 3: C shows the five messages and B as no longer a member within about 30 s of reconnecting.
  - Step 4: D shows the group and the history **written by A and C**. D does **not** show B's messages: B left, and sync does not
    re-deliver a former member's messages (documented limit, `docs/security.md` section 8). That is a pass, not a failure.
  - Step 5: a clear failure ("Only the group owner can add members"), nothing sent.
  - Step 6: "Mixed" is created as a legacy group and E receives it as before; A's log has no `Group v2 created` for it.
  - In none of the steps do the logs contain `Group bundle ignored` (except `no-own-cert` for a bundle that does not concern
    that device), `SECURITY:` or `Group cert dropped`.
- **A FAIL means:** a member that never appears or a message that never arrives (look for `Group bundle ignored ... reason=` and
  `Group cert dropped ... reason=`; the reason names the rule: `subject-key-binding` means the receiver's pin for that device
  is not the key on the wire, i.e. a pairing or TOFU pin problem, not a group bug; `owner-not-paired` means the receiver is not
  paired with the owner); the group created as legacy when every device was connected (check that each invitee's session
  reported level 2: a stale build or a session that came up after the create); the desktop failing to sign or verify (its
  `PersistedFlashCrypto` key). Each is a new `ERROR-NNN`.
- **Also note:** how long B and C took to show the group after A created it; whether D's catch-up needed the 2 s backup push.
- **Source:** `docs/group/v1-signed-membership-plan.md` (D1 to D10), `docs/protocol.md` "v2 groups". The attacks themselves
  (forged charter, cert by a non-owner, key swap, unsigned or wrongly signed message, forged relay, replay, legacy frame for a
  `g2-` id, budget) cannot be staged from the UI and are covered by unit tests only: `SignedGroupsTest`,
  `GroupSignatureRulesTest`, `GroupBundleCodecTest`.
- **Status:** TODO

### GT-03 — Vouched members: two devices that never paired share a v2 group (ADR-044 V2)
- **Setup:** four devices on the V2 build (`gv=2`): A (the owner), B, C and D; at least one phone and the desktop. **A is paired with
  B, C and D; B, C and D are not paired with one another** (fresh installs, or unpair them: Nearby must offer "Pair" between B and C).
  Every device needs a live session with A before step 1 (a group made with an unconnected invitee is legacy by design). B and C must
  be discoverable on the same network. Phone log `adb logcat -v time -s CHAT:I` (V2 refusals are logged at warning level with a
  `SECURITY:` prefix, the vouching one as `SECURITY: vouch refused`); desktop log `%USERPROFILE%\.flash\desktop.log`.
- **Steps:**
  1. A creates "Wide" with B, C and D.
  2. On B open the group's member list. Do the same on C.
  3. B sends two messages, C sends two, D sends one. Check every device shows all five under the right names.
  4. Start a group **voice** call from A, then have B and C join. Check B and C hear each other, not only A.
  5. On B send a photo to the group. Then open a 1:1 chat with C and try to send C a file.
  6. On B tap **Verify** next to C. Complete the pairing on both sides. Then open the member list again.
  7. **Owner removes a member.** On A open the member list. Check which rows show **Remove**, tap it next to D, read the dialog and
     confirm. Then look at D's screen and have D try to send a message to the group.
  8. On C leave the group. On B check that C is no longer listed. Open the group on C and look at its screen.
  9. **Add D back.** On A use Add members and pick D. Check D's screen, then send a message from D.
  10. **Removed while offline.** Turn D's Wi-Fi off (or stop the desktop). On A remove D again and wait until A, B show D gone. Turn D's
      network back on, wait for it to reconnect to any member (no manual action on D), and look at D's screen.
  11. **Calls.** While D is out (after step 10), start a group voice call from B. Check whether D rings, and have D look for a Join
      banner.
- **Pass:**
  - Step 1: A's log has `Group v2 created: group=g2-...`; B, C and D each log `Group v2 joined: group=g2-... owner=<A's id>`; the
    group shows four members with A as the only owner.
  - Step 2: on B, C's row shows **"Added by <A's name> · not verified"** and a **Verify** action; A's and (if it is paired) any paired
    member's rows show neither. The label names the owner, not the member.
  - Step 3: all five messages arrive everywhere; the names are the ones A's device gave (signed labels); no `SECURITY:` line on any device.
  - Step 4: B and C are heard by each other (media legs between two devices that never paired).
  - Step 5: A receives the photo; **C and D do not** (a group attachment goes only to paired members: vouched members get no
    files). The 1:1 file to C is refused like for any unpaired peer until step 6. This is a pass, not a failure.
  - Step 6: after pairing, C's row on B no longer says "Added by" and has no Verify action; group messages still flow.
  - Step 7: A's sheet shows **Remove** on B, C and D but **not on A's own row**; B's and C's sheets show it on no row. The dialog is
    titled "Remove <D's name>?" and says D keeps what it already received. After confirming, D's row disappears from A's sheet at
    once and from B's and C's within seconds; A's log has `Group v2 member removed: group=g2-... member=<D's id>`; nothing D sends
    afterwards appears on A, B or C. **On D** the composer is replaced by "You were removed from this group / You can still read what you
    already received.", there are no voice or video buttons, and the menu has no Add members or Leave group; D's old messages are still
    readable; a message D managed to type before the notice appeared is not stored (no `PENDING` bubble that never clears).
  - Step 8: B's member list drops C (the leave tombstone, which also revokes C's vouch on B). On C the notice reads "You left this group".
  - Step 9: A's Add members offers D; D's composer comes back on its own, D's message reaches A, B and C, and B and C accept D again
    (no `SECURITY:` line on any device).
  - Step 10: D shows the removed notice within about a minute of reconnecting, with no action on D. Until it reconnects D still looks like
    a member: that is expected, write down how long it took. The only log line for a later replay is a warning
    `Group bundle ignored: group=g2-... reason=local-not-member`, at most one per reconnect.
  - Step 11: D does not ring and has no Join banner; a stale Join tap on D leads to no call leg.
  - The logs contain no `SECURITY: vouch refused` (that line means the owner's cert clashed with a paired pin or another group's vouch).
- **A FAIL means:** C's messages never reach B (or the reverse): check that each device has a session with the other (a vouch only
  installs the pin; the planner still has to dial, and ECO dials only neighbours), that the roster arrived (`Group v2 joined`), and
  whether the peer's live key equals the cert key (a stale or reinstalled member: the owner must re-issue the cert); B shows C with
  no label (the row was treated as paired: check the pair state) or with a label but no Verify action; a file that reaches a vouched member
  (a gate bug, serious); group call legs missing between B and C; a removed member still being accepted; **Remove** shown to a
  member, missing on the owner's sheet, or offered on the owner's own row (`canRemoveMembers` / `canRemove`); the removed row still
  listed in the owner's open sheet after confirming (the roster did not re-emit); D still shows a live composer after the removal; D
  never learns of a removal made while it was offline; D rings or joins a group call while out; a message D sent while out stays `PENDING`
  on D. Each is a new
  `ERROR-NNN`.
- **Also note:** how long B took to reach C after the group was created (dial time between two never-paired devices), which mode each
  device was in (STANDARD, BOOST, ECO), and whether B and C were discovered before step 3 or only after. what D's own screen shows after the
  removal, and whether D's device learned of it at once or only when it next reconnected (step 10).
- **Source:** `docs/group/v2-vouched-trust-plan.md`, ADR-044 "V2 built", `docs/security.md` section 9. The attacks themselves (a first-use
  pin squatting a member's id, a paired-key conflict, a second owner vouching a different key, an impersonated session key, a cert
  from a non-owner, removal revoking the vouch) cannot be staged from the UI and are covered by unit tests only: `SignedGroupsTest`
  (V2 cases), `TrustStoreGroupVouchingTest` (desktop), the `core:security` vouching tests, `CallCoordinatorSecurityTest`.
- **Status:** TODO

## 4d. Session ceiling and dial budget (ADR-057, `docs/network/PRESENCE-CONNECTIONS-PLAN.md` "Session ceiling")

### SC-01 — A phone holds a 20-member group's sessions (ceiling 24)
- **Setup:** PC with `./gradlew :core:engine:peerFarm --args="--count=20 --minutes=10"` (see `PC0-RUNBOOK.md` for the Gradle
  environment and the one-network rule), one phone on this build in STANDARD, screen on, same Wi-Fi. Phone log via
  `tools/pc0/phone-baseline.ps1`.
- **Steps:** start the farm, wait 2 minutes, count live sessions on the phone (the Nearby/status screen or `activeSessions`
  lines in the log). Then switch the phone to BOOST and to ECO for 1 minute each.
- **Pass:** 20 sessions up in STANDARD and BOOST; **zero** `session cap reached` lines; in ECO the phone still holds the
  inbound sessions (it never refuses one) and dials only its neighbours.
- **Source:** ADR-057. **Status:** TODO

### SC-02 — Above the ceiling: refuses only the excess, and a crowd does not starve contacts the phone dials itself
- **Setup:** as SC-01 with `--count=30`, plus a second real phone paired with the first (both on this build).
- **Steps:** start the farm, wait 3 minutes, note the sessions on phone 1. Open Nearby on phone 1, then close it.
- **Pass:** phone 1 holds at most 24 sessions; `session cap reached` appears only for peers above that; with Nearby closed the
  dial filter log shows the budget in use (`Dial filter` lines, added with this change). **Expected gap, not a failure:**
  farm peers dial *in* first-come, so the paired phone may be refused if the farm fills all 24 first; record what happened.
  If it does, that is the trigger for priority admission (ADR-057, Revisit when).
- **Source:** ADR-057. **Status:** TODO

## 4e. Audit fixes of 2026-09-30 (`docs/audit/2026-09-28-architectural-audit-and-tasks.md` section 7)

Three small fixes with unit tests, and a CI change. Only the first needs a device.

### AUD-01 — A stopped engine is no longer advertised, and a Wi-Fi reconnect leaves one registration (audit 3.7, ERROR-073)
- **Setup:** one phone with the new build, and a second device that can browse `_flash-transfer._tcp` (the PC0 farm,
  `:core:engine:peerFarm`, or a desktop Flash). Both on the same Wi-Fi.
- **Steps:**
  1. Start Flash on the phone. Browse `_flash-transfer._tcp` from the second device: exactly **one** "Flash <phone>" entry.
  2. Turn the phone's Wi-Fi off and on **three times** (each time wait until it is connected again). Browse again after each.
  3. Stop the engine (Stop in the notification), wait 30 s and browse again. Repeat with a swipe-away from recents.
  4. `adb logcat` on the phone during steps 2-3, filtered for the NSD transport (`Advertisement is down`, `re-registering NSD advertisement`).
- **Pass:** one entry after step 1 and after every reconnect in step 2 (never two entries for the same phone, even briefly
  once the mDNS cache expires); **no** entry 30 s after step 3, and no dial to the phone gets `Connection refused` afterwards
  (grep the farm log for `Auto-connect`). No repeating "Unable to unregister" warnings in logcat.
- **Fail:** a second entry appears after a reconnect, or the phone is still advertised after the engine stopped. Then the leak
  is somewhere else: record a new ERROR and keep ERROR-073 OPEN.
- **Source:** ERROR-073 (update 2026-09-30), audit section 7, `NsdTransportLogicTest` (3 new tests). Related: OLD-02.
- **Status:** TODO

### AUD-02 — Sending to a group moves the thread to the top of the chat list (audit 3.6)
- **Setup:** an existing group with at least one other member; a second 1:1 chat that has a newer message than the group.
- **Steps:** open the chats list (the group sits below the 1:1 chat), open the group, send a text, go back to the list.
- **Pass:** the group is now first. Its name and member count are unchanged. (An archived group also un-archives.)
- **Source:** audit 3.6; `RealFlashChatRepositoryTest` (`group text ...`, 2 tests).
- **Status:** TODO

### AUD-03 — Linux CI is green (audit section 7, "CI on Linux")
- **Setup:** push `dev` (or run the workflow manually) and read the GitHub Actions run.
- **Pass:** `:core:engine:allTests` and `:desktop:allTests` pass on ubuntu. **Expected remaining failure:** the 3
  `DesktopMediaDevicesTest` cases (undiagnosed, probably the WebRTC natives): if only those fail, record them as a new
  ERROR and mark this test PASS for the DPAPI part.
- **Not verifiable locally:** this machine is Windows, and DPAPI works here. The fix was proven only by simulating a failing
  DPAPI vault.
- **Source:** audit section 7; `FixtureIdentityVaultTest`, `TestIdentityVaultTest`.
- **Status:** TODO

## 4f. Push-to-talk on desktop (ADR-058, `docs/ui/ptt-session-overlay.md` Addendum A)

What exists: one `PttSessionEngine` for Android and desktop, `DesktopEngine` wiring, the shared session card, a mic button,
`Ctrl+Shift+T`, `Esc`. What has been run: unit tests and two real `DesktopEngine`s with **fake** audio devices
(`DesktopEnginePttTest`), and a Skia render test of the card. No sound has crossed a real wire.

### PTTD-01 — Desktop <-> phone push-to-talk, both directions, on the LAN (ADR-058)
- **Setup:** one Windows desktop build and one Android phone (build from this commit), paired, on the same Wi-Fi; speakers and a
  microphone on the PC.
- **Steps:** (1) on the desktop click the mic button in the rail (or press `Ctrl+Shift+T`) and speak a sentence for ~5 s; (2) stop
  with the card's Stop; (3) on the phone start PTT (hardware key or the app's control) and speak; (4) the desktop should show
  "Listening - <phone name>" and play it; (5) leave with Leave; (6) repeat with both sides pressing at once.
- **Pass:** the phone hears (1) intelligibly within about a second, the desktop hears (3); the card shows the right role, a running
  clock and an `ms` figure; Stop/Leave end the session on both sides (`PTT_SESS` log lines on both: `Talking`/`Listening`, then
  the stop); with presses within ~1 s of each other exactly one side ends up Talking (the lower device id wins inside the 1.5 s collision window, `PttFloorMachine`).
- **Also look for:** `PTT frame dropped` or `PTT control dropped: no session` in `~/.flash/desktop.log`; clipped first words
  (PTTD-04).
- **Source:** ADR-058; `DesktopEnginePttTest` covers the wiring with fakes only.
- **Status:** TODO

### PTTD-02 — Audio formats on other hardware (16 kHz / 8 kHz; USB headset; Linux later)
- **Setup:** repeat PTTD-01 (desktop side only) with (a) a USB headset as default device, (b) a Bluetooth headset, (c) a PC whose
  default microphone does not support 16 kHz, (d) a LOW performance mode session (8 kHz / 60 ms).
- **Pass:** each either works or degrades with a **logged** rate (`PTT_SESS ... rate=`), never silence or a crash; the phone hears
  the desktop in (d) at the LOW profile. Record what failed in `logs/experiments.md` (EXP-018's follow-up).
- **Source:** ADR-058; EXP-018 (one machine only).
- **Status:** TODO

### PTTD-03 — Windows microphone privacy switch: silent capture
- **Setup:** Windows Settings > Privacy & security > Microphone > "Let desktop apps access your microphone" = Off.
- **Steps:** press talk on the desktop and speak.
- **Pass (desired):** the user is told. **Expected today: FAIL** - the engine opens the line, reads zeros and sends silence without any
  message, because `DesktopEngine` passes `hasMicPermission = { true }` and the capture cannot distinguish silence from a muted
  mic. If it fails as expected, record it as an `ERROR-NNN` (design: detect N seconds of all-zero capture and raise a notice).
- **Source:** ADR-058 consequences; UI-051 Addendum A "No permission step".
- **Status:** TODO

### PTTD-04 — Cold-open latency: are the first words clipped?
- **Setup:** fresh desktop launch, phone listening.
- **Steps:** first press of the session, say a word immediately; repeat after the first session has ended.
- **Pass:** the phone hears the first word in both cases. **If clipped:** measure the gap (Start frame to first audible audio) and
  build the pre-warm described in EXP-018 (open the line once at startup, or keep it warm while a peer is paired and online).
- **Source:** EXP-018 (about 1.0 s cold, about 0.23 s warm on one machine); `JvmPttCapture` (capture opens on the press and packets are
  enabled only after the Start frame is sent, so speech before the line is live is simply not captured).
- **Status:** TODO

### PTTD-05 — Android PTT after the engine re-shape
- **Setup:** two Android phones (a rugged handset with a hardware PTT key if available), paired, same LAN.
- **Steps:** talk and listen both ways; hardware press toggle; Stop from the card; Stop/Leave from the notification; press during a
  call and during a voice-note recording; screen off for 60 s mid-session.
- **Pass:** everything ADR-032's device gate asked for still works; `PttCapture`/`PttPlayout` now sit behind `PttAudioPlatform` and
  the clock is `pttElapsedRealtimeMs()` (same `SystemClock.elapsedRealtime()`), so behaviour should be identical. **Android PTT has
  never passed its own gate** (ADR-032), so a failure here may be old, not new: compare with `git stash`/the parent commit
  `b1d6236` before calling it a regression.
- **Source:** ADR-058 consequences; commit `c8a9aa3`.
- **Status:** TODO

### PTTD-06 — A call and push-to-talk never share the desktop microphone
- **Setup:** desktop paired with a phone.
- **Steps:** (1) start a PTT session on the desktop, then start a call from the phone or the desktop; (2) during a call press the mic
  button / `Ctrl+Shift+T`; (3) while a PTT session is live, have the phone place a call to the desktop.
- **Pass:** (1) the PTT session ends when the call becomes active and the card disappears; (2) the press is refused with the
  snackbar "Call in progress - PTT unavailable" and no capture opens; (3) the call screen paints above the card and the call has
  working audio (the microphone was released).
- **Source:** ADR-032 mutual exclusion; `DesktopEngine` (`isCallActive`, `onCallStarted` collector).
- **Status:** TODO

### PTTD-07 — Card, button and hotkey look and behave right on the desktop (UI-051 Addendum A)
- **Setup:** Windows at 100 %, 125 % and 150 % scale, window at 640x480 dp (compact layout) and wide (rail), dark and light theme,
  the desktop UI-scale setting at its extremes (ADAPTIVE-UI-PLAN AD-1).
- **Steps:** find the mic button (rail footer when wide; floating above the bottom nav on a tab root when narrow); open a session;
  press `Esc` (Stop/Leave); press `Ctrl+Shift+T` with focus in the message composer; click behind the card; keyboard-Tab to the
  button; start a session from a phone and watch the card and the snackbar notices ("<name> stopped talking").
- **Pass:** the button is reachable and not clipped in both layouts; the card is centred and readable at every scale; `Esc` ends the
  session first (before closing a pane); `Ctrl+Shift+T` works from the composer; a click behind the card hits nothing; the button is
  announced "Push to talk" / "Stop talking" by a screen reader. **Known gap:** the button is pointer/hotkey only (no keyboard focus
  stop) - if Tab should reach it, raise it as a follow-up.
- **Source:** UI-051 Addendum A; `PttSessionOverlayRenderTest` proves only that the card draws and Stop reaches the engine.
- **Status:** TODO

## 4g. Chat and group sync audit (`docs/audit/2026-09-28-chat-group-sync-audit-and-plan.md`, 2026-09-30)

Built and unit-tested (SignedGroupsTest, RealFlashChatRepositoryTest, two-real-engine desktop tests, UI math tests); each fix was
mutation-checked. **Nothing here has crossed a real network between real devices.** Two phones and the Windows desktop are
enough for everything below. Keep `adb logcat` running on the phones; "tick" means the marks under your own message.

### CGS-01 — A desktop that comes back online learns its groups and catches up (audit area 2, commit `cd3ca0a1`)
- **Setup:** a v2 group of the desktop and one phone (A). Desktop and phone on the same Wi-Fi.
- **Steps:**
  1. Close the desktop app. On phone A send two messages to the group.
  2. Start the desktop app and wait for phone A to show "Connected" in Nearby.
  3. Open the group on the desktop.
- **Pass:** within about 10 s of the session coming up, the desktop shows the group and **both** messages. Before the fix it
  showed nothing until a new message arrived. Phone A's two messages end on "Delivered" (two ticks) without resending.
- **Source:** `DesktopEngineGroupSessionUpTest`, the desktop session-up edges.
- **Status:** TODO

### CGS-02 — A group message the receiver dropped is retried within seconds, not after a minute (commits `cd3ca0a1`, `031b6ab4`)
- **Setup:** group with a phone A and a phone B, where B does not know the group yet when A's message arrives (a fresh
  install of B, or B removed from the group and re-added).
- **Steps:** A sends one group message while B is connected but has not learned the group yet. Watch A's tick and B's chat.
- **Pass:** B shows the message **within about 10 s** of learning the group (logcat on A: outbox resends at roughly 1, 2, 4 s,
  never a 60 s gap). A's tick turns to Delivered.
- **Fail:** B shows it only after about a minute.
- **Source:** `RealFlashChatRepositoryTest` (group resend ladder; two writes inside one backoff window).
- **Status:** TODO

### CGS-03 — Read ticks in a group (audit area 1, commit `b9b43ece`)
- **Setup:** a group of three devices: sender S and readers R1, R2 (two phones and the desktop in any roles).
- **Steps:**
  1. S sends a message. Keep R1 and R2 on the chats list (not inside the group).
  2. Open the group on R1 only. Look at S.
  3. Open the group on R2. Look at S.
  4. Remove R2 from the group (owner), send another message, read it on R1 only.
- **Pass:** after step 1: S shows Delivered (two grey ticks). After step 2: **still Delivered**. After step 3: Read (ticks take
  the read colour). After step 4: Read as soon as R1 reads (a removed member does not hold it back).
- **Fail:** the message reads Read after only R1 opened it, or never reaches Read.
- **Source:** 7 `SignedGroupsTest` read-cursor cases, `ReadCursorDao`.
- **Status:** TODO

### CGS-04 — A member that catches up tells the author (audit area 3, commit `c31b30cd`)
- **Setup:** a v2 group of S, member M and member L. S and M connected; L powered off (or Flash stopped).
- **Steps:** S sends a message, M receives it. Start L. Wait for L to catch up from M or S.
- **Pass:** L shows the message; S's message ends on **Delivered** (all members), not stuck on a single tick. Message Info
  (CGS-06) lists L under "Delivered" for that message.
- **Source:** `SignedGroupsTest` (relay receipt, the author records a SyncAck, a receipt for a direct message is ignored).
- **Status:** TODO

### CGS-05 — A late member sees a label for an earlier photo or voice note, not an empty bubble (audit area 2, commit `4e843cd9`)
- **Setup:** a **legacy** group (created before v2 groups) of S and M; a third device N not yet in it.
- **Steps:** S sends a photo, a video, a file and a voice note. Add N to the group. Open the group on N.
- **Pass:** N shows one line per item: `[Photo] name`, `[Video] name`, `[File] name`, `[Voice message]`; **no empty bubbles** and no
  raw `vmsg:` text. No file bytes are fetched (attachments of earlier messages are not shared; FO-04 is postponed).
- **Source:** `SignedGroupsTest` catch-up label case.
- **Status:** TODO

### CGS-06 — Message Info sheet (UI-051, commit `5060ac82`)
- **Setup:** a group of S, R1, R2 (R2 offline or off Wi-Fi) and a 1:1 chat.
- **Steps:**
  1. In the group, S sends a message. With R1 having read it and R2 offline, long-press the message (Android) or right-click
     (desktop) and choose **Message Info**. Also tap the ticks under the message.
  2. Bring R2 online, and watch the open sheet.
  3. Remove R2 from the group before it received a later message; open Message Info for that message.
  4. Long-press a **received** message and a call row; open a 1:1 message you sent.
- **Pass:** step 1: title "Message info", R1 under Read, R2 under "Waiting for device to connect"; step 2: R2 moves to
  Delivered **while the sheet is open**, with a time; step 3: R2 reads "No longer in the group"; step 4: a received message and
  a call row have no Message Info entry; the 1:1 sheet shows a single recipient. TalkBack reads each row sensibly.
- **Source:** `MessageInfoBuilderTest`, `FlashMessageInfoMathTest`, SignedGroupsTest message-info cases, `docs/ui/message-info.md`.
- **Status:** TODO

### CGS-07 — Catch-up banner (UI-052)
- **Setup:** a v2 group with at least 10 messages of history; a device D that was away (Flash stopped) while they were sent.
- **Steps:** start D with the group open and watch the top of the conversation as history arrives. Then leave it alone for 5 s.
  Repeat once with "Remove animations" (Android) turned on.
- **Pass:** a strip "Catching up on earlier messages · N" appears under the header when the first earlier message arrives, N
  counts up, a thin line sweeps left to right (static with reduced motion), and the strip **disappears about 3 s after the last
  message**. It never appears in a direct chat, and never for a live message sent while you are looking at the group.
- **Fail:** the strip stays forever, flickers away mid-catch-up, or shows for a normal incoming message.
- **Source:** `SignedGroupsTest` catch-up banner cases, `FlashGroupSyncMathTest`, `docs/ui/group-ui.md` UI-052.
- **Status:** TODO

### DNAME-01 — Renaming the desktop shows everywhere on the desktop (audit area 5, commit `3156e400`)
- **Setup:** the Windows desktop app with a paired phone connected.
- **Steps:** Settings → rename this device. Look at Settings, the sidebar avatar/initials and the Nearby header. Start a new
  pairing with another device straight away. Create a **new** group and send a message in it.
- **Pass:** all three places change at once without restarting; the other device's pairing request shows the **new** name;
  the new group and its message carry the new name. Restart the desktop: the name is unchanged.
- **Known limit (not a failure):** a peer that stays connected keeps the old name until it reconnects; an existing v2 group
  keeps the name signed into its roster.
- **Source:** `DesktopEngineRenameTest`, pairing and signed-groups rename tests.
- **Status:** TODO

### DNAME-02 — The phone sees the new desktop name after a reconnect (audit area 5)
- **Setup:** as DNAME-01, phone A paired with the desktop.
- **Steps:** rename the desktop. Turn the desktop's Wi-Fi off and on (or restart the app). Look at phone A's chat list, the
  chat header and Nearby; also a **legacy** group the desktop belongs to.
- **Pass:** after the reconnect the phone shows the new name in all three and in the legacy group's member list.
- **Source:** `DesktopEngineRenameTest` (transport hello), `SignedGroupsTest` (legacy group row renamed).
- **Status:** TODO

## 4h. Network switch & stale route recovery (ERROR-085, 2026-09-30)

### NET-SW-01 — Automatic reconnection after switching from mobile hotspot to home Wi-Fi (ERROR-085)
- **Setup:** two phones A and B. Phone A connects to a mobile hotspot (e.g. Infinix `10.13.65.x`). Both phones are on the hotspot
  and have established connection or exchanged presence tips/routes.
- **Steps:**
  1. Turn off the hotspot. Connect both phones A and B to a home Wi-Fi network (`192.168.1.x`).
  2. Leave Flash open in the foreground on both phones.
  3. Watch logcat (`adb logcat -v time -s WS:I CHAT:I`).
  4. Send a 1:1 chat message from phone A to phone B.
- **Pass:**
  - Phone A and B discover each other at their new `192.168.1.x` addresses within 5–10 s of joining the Wi-Fi network.
  - The failed dial to the old `10.13.65.x` endpoint does NOT suppress dialing the new `192.168.1.x` endpoint.
  - No need to force-stop or restart either app.
  - The 1:1 chat message delivers immediately with single/double ticks without getting stuck in outbox.
- **Fail:** phones stay Offline or report "Failed to dispatch chat wireFrame: no active session" until force stopped.
- **Also (follow-up fix, 2026-09-30):** while discovery still lists the old `10.13.65.x` address for the peer (it ages out after
  ~30 s) and a presence tip or remembered route offers the new `192.168.1.x` one, the **second** endpoint is now dialed as soon
  as the first is suppressed, not after the old address disappears. Expect Phone A's log to show a dial to each address,
  each at most once per 15 s, and never two dials to the same peer at once.
- **Source:** ERROR-085; `ConnectionPlannerTest` endpoint-aware suppression and urgent-dial tests, and the multi-endpoint tests
  (`a second endpoint of the same peer is dialed while the first is suppressed` and three more);
  `AutoConnectorTest.ensureSession tries a peer's second endpoint when the first one just failed`.
- **Status:** TODO

## 4i. Group call end and recovery (ERROR-086, audit 2026-09-30)

These check the ERROR-086 fix, **implemented and unit-tested 2026-09-30 (not yet on a device)**: `FlashGroupCallEndTest` (20),
`CallCoordinatorGroupEndTest` (3) and `FlashCallBusyPeersTest` (5) cover the state machine, but not the real `PeerConnection`,
camera, audio or foreground service, which is what these tests are for. Run them on at least one Windows desktop and two Android
phones. Logs: `~/.flash/desktop.log` on Windows, `adb logcat -v time -s GROUP_CALL:I` on Android.

### GCALL-01 — Being left alone in a group call does not trap you in it (ERROR-086 root cause)
- **Setup:** desktop D and phones A, B in one group; start a **video** group call from D; A and B join.
- **Steps:** A and B hang up. On D wait **40 s** (past the 30 s grace), then press hang-up. Then start a new 1:1 call from D to A.
- **Pass:** at about 30 s D's call ends by itself (log `Grace timeout expired…` followed by the call screen showing ended and
  going away); the camera light goes off; the new call rings on A. Repeat with a phone as the one left alone: the Android call
  notification disappears and chat to a peer that was not in the call connects within ~10 s (auto-connect is back).
- **Fail:** the call screen stays, hang-up does nothing, or the new call is refused, i.e. the ERROR-086 symptom.
- **Also check:** the log shows `Grace timeout expired with no peers; ending group call …` then `Group call … ending
  reason=NORMAL`, and on Android the call notification goes away (the foreground service stops).
- **Source:** ERROR-086; `FlashGroupCallEndTest.theSoloGraceTimerEndsTheCallInsteadOfCancellingItself` and
  `hangUpEndsAnActiveCallOnceAndIsIdempotent` (mutation-checked).
- **Status:** TODO

### GCALL-02 — A peer rejoining inside the grace window keeps the call (ERROR-086 (b))
- **Setup:** as GCALL-01 with only D and A in the call.
- **Steps:** A hangs up; after ~25 s A rejoins from the group's call banner.
- **Pass:** D's call does **not** end at the 30 s mark; A's video and audio are back within a few seconds; the call continues
  for at least another minute. If A's rejoin never connects, D's call still ends by itself within ~40 s of the rejoin attempt
  (it is not held open forever).
- **Source:** ERROR-086 (b); the desktop log of call `0596ebc3` (rejoin at +27 s, grace timeout at +30 s);
  `aPeerRejoiningInsideTheGraceWindowKeepsTheCall`, `aRejoinThatNeverConnectsDoesNotKeepTheCallForever`.
- **Status:** TODO

### GCALL-03 — Invitees stop ringing (ERROR-086 (c))
- **Setup:** phones A, B and desktop D in one group.
- **Steps:** (1) D starts a group call and hangs up after 5 s before anyone answers. (2) D starts again and nobody answers for 60 s.
- **Pass:** in (1) A and B stop ringing within ~2 s of D's hang-up; in (2) D's call ends with "No answer" after about 30 s
  (log `No members answered outgoing group call … in 30s`), A and B stop ringing at that moment (they receive D's GroupHangup)
  or at their own 45 s ring timeout (`Incoming group call … was not answered in 45s; stopping the ring`), and no call screen is
  left behind. Also: if A and B both decline, D's call ends at once with "Declined".
- **Source:** ERROR-086 (c); `theCallerHangingUpStopsTheRingOfAnInvitee`, `anOutgoingCallNobodyAnswersEndsAndTellsTheInvitees`,
  `everyInviteeDecliningEndsTheOutgoingCallAtOnce`.
- **Status:** TODO

### GCALL-04 — Members who never join are not shown as connecting forever (ERROR-086 (d))
- **Setup:** a group of 5 where only 3 devices are online.
- **Steps:** a joiner (or accepter) builds a connection to **every** group member, because it has no list of who is in the call,
  so right after joining, offline members appear as connecting tiles. Start a video group call, have 2 online members join, and
  watch the participant grid and the `CALL_DIAG … legs=[…]` line for ~30 s.
- **Pass:** within about 15-20 s the offline members' tiles change from connecting to **Invited** (log `Leg … gave no sign of
  life in 15s; not in the call, back to invited`); they stop counting toward the call's size (a 6th member can still join a
  voice call of 12) and stop using a PeerConnection. If one of them then joins, it is brought back within ~4 s (its presence
  frame) and connects. A member who **left** is never brought back by a stale presence frame.
- **Fail:** a never-joined member stays "connecting" for the whole call, or a member who joined late is never connected.
- **Design note:** the audit first proposed "legs only for members that sent a join". That was rejected (a joiner with the
  higher id would wait for an offer that never comes); the leg is pruned instead.
- **Source:** ERROR-086 (d); the three `CONNECTING#1` legs in `~/.flash/desktop.log` for call `0596ebc3`;
  `aMemberWhoNeverAnswersIsGivenUpOnAndShownAsInvited`, `aPresenceFrameBringsAGivenUpLegBack`,
  `aStalePresenceFrameDoesNotUndoAHangup`, `aGivenUpLegNoLongerFillsTheCall`.
- **Status:** TODO

### GCALL-05 — A leg whose Wi-Fi blips comes back without rejoining (ERROR-086 (e))
- **Setup:** a 3-way video group call.
- **Steps:** on one phone toggle Wi-Fi off for 5 s and back on (or walk out of range briefly).
- **Pass:** that phone's tiles recover within ~15 s on every device without anyone pressing anything; the log on the higher-id
  device of the pair shows `Leg … failed; rebuilding (1/3)` (only the offerer rebuilds; after 3 tries `giving up after 3
  rebuilds`); no device ends up alone in a call it cannot close (GCALL-01). Short blips that ICE heals on its own need no rebuild.
- **Source:** ERROR-086 (e); log line `Leg a6400328… pc#2 ice=Disconnected` with no rebuild.
- **Status:** TODO

### GCALL-06 — A call in ECO mode keeps its participants' sessions (ERROR-086 (k))
- **Setup:** three devices in **ECO** connection mode (Settings), one being a phone with the screen on, all in one group; a voice
  group call that lasts longer than ECO's 10-minute idle-park rule.
- **Steps:** start the call, leave it running 12 minutes without typing anything; then hang up and wait ~11 more minutes.
- **Pass:** during the call no `Link park requested peer=<a participant>` / `Link parked peer=<a participant>` appears for a
  participant of the call; once the call has ended and the session has been idle 10 minutes, parking of those sessions resumes
  as before. Before the fix the participants were not protected (the call's `peerId` is the group id) and only the chatter of the
  call frames kept them alive.
- **Source:** ERROR-086 (k); `FlashCallBusyPeersTest` (the rule), Android `DiscoveryEngineHolder`, `Flash.kt`, `DesktopEngine`.
- **Status:** TODO

### GCALL-07 — Joining a call that ended while you were joining does not hang (ERROR-086 (f))
- **Setup:** a group with D and A in a call; a third device B has the group's call banner.
- **Steps:** on B press join, and immediately after D and A hang up (or turn A's and D's Wi-Fi off) before B connects to anyone.
- **Pass:** B's call screen leaves "Connecting…" and ends with a failure message within ~45 s (log `Group call … did not connect
  to anyone in 45s; ending`), the camera/microphone indicators go off, and B can start or join another call. A call that is
  merely slow (a peer that is answering) is given more time, up to ~40 s per connection attempt.
- **Source:** ERROR-086 (f); `aJoinerThatConnectsToNobodyGivesUp`, `aJoinerWithALegThatIsAnsweringIsGivenTimeToConnect`,
  `aLegThatAnswersButNeverConnectsIsWaitedForOnlyAWhile`.
- **Status:** TODO

## 4j. Unread state and calls from unpaired members (ERROR-087 / ERROR-088, audit 2026-10-01)

Both defects were **fixed in code on 2026-10-01 and unit-tested, none of it device-verified** (ERROR-087 / ADR-062, ERROR-088 / ADR-061;
both ERRORs stay OPEN until these pass). Run the tests below on a build that contains the fixes. The text of each test keeps the
pre-fix behaviour it was written against as "Pre-fix", so a FAIL can be told apart from the old defect. The unit proof is
`ConversationReadStateTest` and `ConversationRowReadStateJvmTest` (unread), `CallCoordinatorGroupReachTest`, `FlashGroupCallReachTest`
and `SignedGroupsTest` (calls); a unit test cannot show how often the phones hit the race, so a device PASS is the evidence.

### UNREAD-01 — Leaving a chat never leaves it unread (ERROR-087 A)
- **Setup:** two devices paired, a direct chat with at least 3 messages received from the peer. A also with one more chat open on
  neither side.
- **Steps:** on A open the chat, have B send 1 message while A is looking at it, then leave with the **on-screen back arrow**, look at
  the list; repeat 10 times, sometimes leaving at once after the message lands, sometimes after 10 s; also send a message from A while
  the chat is open and leave.
- **Pass:** the list never shows a badge for that chat after leaving; the badge is absent in all 10 rounds.
- **Also check:** `adb logcat` has no cursor write failing; after each round the chat's row keeps its pin / mute (see `UNREAD-02`).
  Leave it with the system back gesture in some rounds too (`UNREAD-03`).
- **Pre-fix:** the inbound text reset the cursor to NULL, so every message ever received counted as unread.
- **Source:** ERROR-087 (A); fixed 2026-10-01 (ADR-062); unit proof `ConversationReadStateTest`, `ConversationRowReadStateJvmTest`.
- **Status:** TODO

### UNREAD-02 — A new message while the list is showing counts as one, and keeps pin and mute (ERROR-087 A)
- **Setup:** direct chat with 5 messages from the peer, all read; pin the chat and mute it.
- **Steps:** stay on the chat list; the peer sends 1 message.
- **Pass:** the badge shows **1** (not 6), and the chat is still pinned and still muted. Then repeat with the chat archived: the rule is
  now decided (ADR-062): an incoming message **unarchives** the chat, pin and mute stay. Confirm that is what the owner wants on a phone.
- **Pre-fix:** the badge showed the number of all messages ever received from that peer, and the pin / mute were reset.
- **Source:** ERROR-087 (A): the real-Room test showed unread 3 where 1 is right and `pinned`/`muted`/`archived` back to false.
- **Status:** TODO

### UNREAD-03 — Leaving with the system back gesture closes the thread (ERROR-087 B)
- **Setup:** an Android phone in single-pane layout (not a tablet), a direct chat with a peer.
- **Steps:** open the chat, leave it with the **system back gesture / button**; the peer sends a message.
- **Pass:** the chat shows an unread badge of 1 and the notification appears; the **peer's** copy of the message stays at
  Delivered (not Read) until the owner opens the chat. Repeat after leaving by switching tab. (Pressing Home is `UNREAD-05`.)
- **Pre-fix (code reading):** no badge and the sender saw Read at once, because the gesture never called `closeConversation()`.
- **Source:** ERROR-087 (B); fixed 2026-10-01: `MainActivity.kt` closes the conversation when the nav entry leaves `Conversation`.
- **Status:** TODO

### UNREAD-04 — A legacy group's rename or member change does not reset its unread state (ERROR-087 A)
- **Setup:** a legacy (v1, up to 6 members) group with read messages, pinned and muted on the device under test.
- **Steps:** with the chat closed, have another member rename the group or the owner add a member.
- **Pass:** no badge appears and the pin and mute stay, and the group keeps its place in the list. Also run it for a v2 group (it
  passed before the fix too).
- **Source:** ERROR-087 (A): the legacy `Create` / `State` frames upserted a fresh row; now `upsertLegacyGroupConversation` copies it.
- **Status:** TODO

### UNREAD-05 — A message that arrives while the app is in the background with a chat open (ERROR-087, open question)
- **Setup:** an Android phone, a direct chat with a peer, all read.
- **Steps:** open the chat, press **Home** (not back), have the peer send a message, wait 10 s, then open the app and go to the list
  (not into the chat).
- **Observe, then decide:** does the list show a badge of 1, and did the peer's copy go to Read while the phone was in the background?
  The fix did not change this (`onStop` does not close the chat). If the sender sees Read for a message the owner never saw, the
  owner must decide whether `onStop` should close the conversation; record the answer in ADR-062 and file the change.
- **Pass:** a badge of 1 and the sender's copy still Delivered. Anything else is the open question, not a regression.
- **Source:** ERROR-087 "Not fixed / not covered" (b).
- **Status:** TODO

### GCALL-08 — A group call from a member the others are not paired with reaches them (ERROR-088)
- **Setup:** a v2 group of owner O and members X, Y, Z (X, Y, Z are each paired with O only, so X, Y and Z are vouched to each other);
  all online on one Wi-Fi. Run it twice: in **STANDARD** and with X in **ECO**. Enable the logs: `adb logcat -v time -s GROUP_CALL:I`
  or `~/.flash/desktop.log`, and note the "Active sessions" count on X at the moment of the tap.
- **Steps:** X starts a group call (voice, then video). Watch O, Y and Z for 30 s. On Y, if it only shows the banner, tap Join.
- **Pass:** O, Y and Z all ring (or show the ongoing-call banner within ~8 s, one dial plus one presence tick) in both modes, Y and Z can
  join from the banner or the ring, and X's call shows all of them as invited then connected. In the log of X: for a member with no
  session at the tap, a dial (`ensureSession`) and either the invite going out or `Invite for call ... not delivered (no usable
  session yet); will offer it again`, and later the delivery; no `left out` line for Y or Z.
- **Pre-fix (code reading):** only O rang; Y and Z never saw the call, even after X and Y connected a moment later; X got no message.
- **Source:** ERROR-088 (ADR-061); `isGroupCallMember` + dial on demand + invite retry + announce-only members.
- **Status:** TODO

### GCALL-09 — A caller is told when members cannot be reached (ERROR-088, after the fix)
- **Setup:** as `GCALL-08`, with Y's Wi-Fi off.
- **Steps:** X starts a call.
- **Pass:** X's tile for Y reads **"Not reachable yet"** (not "Invited"), the call runs with O and Z, and when Y comes back within the
  ring window (45 s) Y rings and the tile changes to "Invited" then connects. `GROUP_CALL` shows `... not sent yet:` / `... not
  delivered` lines naming the cause for Y. After 30 s with nobody joined the outgoing call ends as before (ERROR-086).
- **Source:** ERROR-088 (ADR-061) fixes 4, 6 and 7.
- **Status:** TODO

### GCALL-10 — A member the caller cannot reach at all still sees the call through another participant (ERROR-088)
- **Setup:** a v2 group of O, X, Y, Z as in `GCALL-08`, but Z can reach O and cannot reach X (a network that isolates clients from each
  other, or X and Z on different subnets with O bridging). Hardest of the ERROR-088 tests; skip it and record BLOCKED if no such
  network is at hand.
- **Steps:** X calls, O accepts, watch Z for 15 s, then tap Join on Z.
- **Pass:** Z shows the ongoing-call banner within ~8 s of O joining (O's presence tick tells it: O got the member list from X's invite);
  Join connects Z to O (and to Y), and X appears in Z's call only if some path exists. X's tile for Z reads "Not reachable yet" until
  then. No crash and no stuck "Connecting…" on Z (the connect deadline ends a join that reaches nobody).
- **Source:** ERROR-088 (ADR-061) announce-only members; `FlashGroupCallReachTest` "an incoming call tells every member of the invite".
- **Status:** TODO

## 4k. Edge-case audit (`docs/audit/2026-10-01-chat-edge-case-audit.md`, ERROR-089…094, 2026-10-01)

The audit read the code and found these gaps; **nothing was run on a device and nothing was fixed**, so each test below is written
against the *expected current behaviour* ("Now") and a target ("Pass once fixed"). Run them on any build to confirm the audit, and again
after a fix. A test that matches "Now" is a confirmed defect, not a failure of the tester. The audit also lists 63 unchecked cases (❔);
those are not tests yet: turn one into a test here when it is picked up.

### EDGE-01 — The group keeps working when its owner is gone (ERROR-090, Option D)
- **Setup:** a v2 group of three: owner O, members A and B. A and B are **not** paired with each other (vouched through O). Then **clear
  data** on O (or uninstall it).
- **Steps:** A and B chat in the group for 5 minutes; A places a group voice call to B; A opens the member sheet and taps **Add members**;
  note what the roster shows for O; B restarts the app. A taps "Continue in a new group".
- **Pass (before fix):** the chat and the call work, O stays listed as an active member and offline ("Not reachable yet" in the
  call), Add members does nothing and says nothing; B still reaches A after the restart (vouched pins persisted).
- **Pass once fixed (built 2026-10-01):** "Add members" is hidden for non-owners in v2 groups; A taps "Continue in a new group", creating a new v2 group owned by A with B, while the old group remains read-only history.
- **Source:** ERROR-090, ADR-063. **Status:** TODO

### EDGE-02 — The owner leaves on purpose (ERROR-090, Option A / ADR-063)
- **Setup:** the same group, O still present.
- **Steps:** on O open **Leave group**; read the dialog; select successor admin from list and confirm; on successor try Add members.
- **Pass (before fix):** the dialog is the same text a member sees (no warning, no successor choice); afterwards nobody can add or remove
  anyone; the O row is a tombstone and message ticks complete normally.
- **Pass once fixed (built 2026-10-01):** the dialog warns the owner and presents eligible members with radio buttons. When confirmed, the successor is promoted to admin before O leaves, and the successor can add and remove regular members.
- **Source:** ERROR-090, ADR-063. **Status:** TODO

### EDGE-02b — Admin promotion and admin member management (ADR-063)
- **Setup:** v2 group with owner O, member A, member B, non-member C.
- **Steps:** O opens Group Members sheet and taps "Make Admin" on A. A opens Group Members sheet and adds C. A tries to remove B. A tries to remove O.
- **Pass once fixed (built 2026-10-01):** A's role updates to Admin for all members. A can add C to the group. A can remove regular member B. A cannot remove owner O ("Remove" option hidden or rejected).
- **Source:** ADR-063. **Status:** TODO

### EDGE-03 — One dead member and the status of every group message (ERROR-090)
- **Setup:** a v2 group of three (O, A, B), all online.
- **Steps:** turn B's Wi-Fi off and leave it off. A sends 3 messages. Check A's and O's bubbles at once, at 10 minutes and at 35 minutes;
  open Message Info on A's first message.
- **Pass (today):** O has the messages; A's ticks stay at sent; after 30 minutes A's messages turn **FAILED** and the failed icon does
  nothing when tapped. Turn B's Wi-Fi back on: B catches up and A's messages may flip to delivered.
- **Pass once fixed:** A's bubble reads delivered to O and pending for B, never FAILED while only unreachable members are missing.
- **Source:** ERROR-090 (2), ERROR-089. **Status:** TODO

### EDGE-04 — A 1:1 message to a peer that is offline for 31 minutes (ERROR-089)
- **Setup:** two paired phones, a direct chat.
- **Steps:** turn B's Wi-Fi off; A sends "hello"; wait 31 minutes; turn B's Wi-Fi on; wait 5 minutes. Tap the failed icon on A.
- **Pass (today):** the message is FAILED on A after 30 minutes, B never receives it, tapping the icon does nothing.
- **Pass once fixed:** B receives it, or A can retry it with one tap.
- **Also check:** the same with 20 minutes offline: B gets it as soon as Wi-Fi returns (expected to work today).
- **Source:** ERROR-089. **Status:** TODO

### EDGE-05 — Oversize text (ERROR-091)
- **Setup:** a v2 group and a direct chat.
- **Steps:** paste **20,000 characters** into the group composer and send; then paste about **3 MB** of text into the direct chat, then
  about **5 MB**. Watch the receivers and `adb logcat` for `exceeds size guard`.
- **Pass (today):** the group message shows sent on the sender and appears on nobody's phone; the 5 MB message makes the session drop and
  reconnect repeatedly (a call or transfer on that session is cut).
- **Pass once fixed:** the sender is told the text is too long and nothing oversize is sent.
- **Source:** ERROR-091. **Status:** TODO

### EDGE-06 — A peer's clock is wrong (ERROR-092)
- **Setup:** two paired phones. Turn off automatic time on B and set it **one day ahead**.
- **Steps:** B sends a message; A sends a message; B sends another. Then set B's clock **one day behind** and repeat. Restore time.
- **Pass (today):** on A, B's messages sort after A's newer message in the first run and into old history in the second; the unread badge
  counts follow that order.
- **Pass once fixed:** the order follows arrival, or the peer's time is clamped.
- **Source:** ERROR-092. **Status:** TODO

### EDGE-07 — The clock steps forward while messages are queued (ERROR-092)
- **Setup:** two paired phones; B's Wi-Fi off; A queues 3 messages.
- **Steps:** on A set the clock forward by one hour; look at the 3 messages.
- **Pass (today):** all three become FAILED at the next drain pass although only seconds went by.
- **Source:** ERROR-092. **Status:** TODO

### EDGE-08 — File names (ERROR-093)
- **Setup:** Android to Android and Android to Windows. Files: `照片 ñ.jpg`, `تقرير.pdf`, a 150-character name ending in `.pdf`; a folder
  containing `a b.txt` and `a_b.txt`; for Windows also `A.txt` with `a.txt` in one folder, and files named `CON.txt` and `NUL`.
- **Steps:** send each, accept, open the received item and look at the stored name and the contents of the folder.
- **Pass (today):** non-ASCII names become underscores, the long name loses `.pdf`, the colliding names end as one file (the second
  overwrites the first), reserved names fail or are altered; no file escapes the transfer folder in any case.
- **Pass once fixed:** names are kept, extensions survive, no overwrite.
- **Source:** ERROR-093. **Status:** TODO

### EDGE-09 — Delete for everyone while the other side is offline (ERROR-094)
- **Setup:** two paired phones (and the same in a v2 group of three).
- **Steps:** B's Wi-Fi off; A sends nothing new, picks an old message and uses **Delete for everyone**; turn B's Wi-Fi on; wait 5 minutes.
- **Pass (today):** the message is gone on A and **still shown on B**. In the group, a member that was offline keeps it too.
- **Source:** ERROR-094. **Status:** TODO

### EDGE-10 — A reaction while the other side is offline (ERROR-094)
- **Steps:** B's Wi-Fi off; A reacts to a message; B's Wi-Fi on; compare the reaction on both phones.
- **Pass (today):** A shows the reaction, B never does.
- **Source:** ERROR-094. **Status:** TODO

### EDGE-11 — The receiver's storage is full (ATT-01, not yet an ERROR)
- **Setup:** fill the receiver's storage to under 100 MB free. Send a 500 MB file.
- **Pass:** a clear error on both sides, no crash, no half file left shown as complete, and the chat keeps working. If it crashes or
  stalls forever, add an ERROR and fix; the audit found no free-space check.
- **Status:** TODO

### EDGE-12 — A zero-byte file (ATT-04)
- **Steps:** send an empty file from Android to Android and to Windows.
- **Pass:** it completes or is refused with a message; no stuck "0 %" card, no crash.
- **Status:** TODO

### EDGE-13 — The database cannot write because storage is nearly full (LIFE-04)
- **Setup:** storage nearly full (under 20 MB). 
- **Steps:** receive 20 messages and send 5.
- **Pass:** no crash; a message that cannot be stored is reported or retried. The audit found no `SQLiteFullException` handling.
- **Status:** TODO

### EDGE-14 — Search with `%` and `_` (MSG-16)
- **Steps:** have messages "100% sure", "1000 sure" and "a_b", "axb"; search `100%`, then `a_b`.
- **Pass (today the audit expects a defect):** `100%` also matches "1000 sure" and `a_b` also matches "axb".
- **Status:** TODO

### EDGE-15 — Delete a group chat without leaving, then a message arrives (GO-18)
- **Steps:** in a v2 group delete the chat from the list without leaving; another member sends a message.
- **Pass:** note what happens: does the group come back, with what title, with only the new message, and is a catch-up run? Documented
  as accepted ("deleting a chat without leaving keeps its rows and vouches"); this records what the user sees.
- **Status:** TODO

### EDGE-16 — Both phones call each other at the same moment (CALL-01)
- **Steps:** A and B tap the call button within one second of each other, 10 times.
- **Pass:** each round ends with one connected call or two clean rings that resolve, never two calls on one pair, never a stuck screen.
- **Status:** TODO

### EDGE-17 — A cellular call or an alarm during a Flash call (CALL-03)
- **Steps:** during a connected call, ring the phone from a third phone; answer it; decline it on another run; let an alarm ring.
- **Pass:** the Flash call mutes or pauses sensibly and resumes or ends cleanly; no one-way audio afterwards.
- **Status:** TODO

### EDGE-18 — Windows: first-run firewall prompt, sleep and resume (NET-09, LIFE-10)
- **Steps:** on a clean Windows profile start Flash and **deny** the firewall prompt; look at Nearby from a phone. Then allow it, connect,
  put the PC to sleep for 10 minutes, wake it, send a message each way.
- **Pass:** a denied prompt is explained somewhere; after resume the session returns and queued messages arrive.
- **Status:** TODO

## 4l. Group call from a caller not paired with every member (ERROR-095, 2026-10-01)

Capture first: `~/.flash/desktop.log` is **overwritten at every launch**, so copy it before restarting the desktop app; on a phone use
`adb logcat -v time -s GROUP_CALL:I CHAT:I`. A FAIL here needs the `GROUP_CALL` lines of the caller **and** of one member that did not ring.

### GCALL-11 — A new group made while every member is online is v2 and every member can be called (ERROR-095)
- **Setup:** a phone P paired with members A, B and C; A, B and C are **not** paired with each other. All four online on one Wi-Fi, all on
  this build. (Not the desktop, unless it is paired with all three.)
- **Steps:** on P create a group with A, B, C. Open each member's group info: the group id is not shown, so read it from the log line
  `Group v2 created: group=g2-...` on P. Then on A start a group call (voice, then video). Watch P, B and C for 30 s.
- **Pass:** the log line says `Group v2 created` (not `Group created as legacy`); P, B and C ring (or show the ongoing-call banner within
  ~8 s) although A is not paired with B or C; all can join; A's screen shows B and C as invited, then connected.
- **Source:** ERROR-095, ADR-064, ADR-061, ADR-044 V2. **Status:** TODO

### GCALL-12 — The old legacy group: the caller sees who is missing, and the callee's log says why it declined (ERROR-095)
- **Setup:** the owner's existing legacy group (UUID id) with Ocelot, Gazelle, Quokka; the desktop is not paired with Quokka. Capture the
  logs of the desktop **and of Gazelle** (`adb logcat -v time -s GROUP_CALL:I`).
- **Steps:** the desktop starts a group call. Look at the desktop's call screen and at Gazelle's log.
- **Pass:** the desktop's screen lists Quokka with "Not paired with you" (not Invited, not missing); the desktop log has
  `N member(s) callable, 1 left out`; if Gazelle declines, its log has `declined: busy in ...` or `declined silently: <reason>`, and
  after a **cold restart** of Gazelle the same call rings it (a cold restart separates "busy zombie" from "untrusted").
- **Pre-fix:** Quokka absent from the screen, no reason anywhere; Gazelle declined in 140 ms with no log line.
- **Source:** ERROR-095. **Status:** TODO

### GCALL-13 — Creating a group while a member is offline is refused with the member's name (ERROR-095)
- **Setup:** a phone P paired with A and B; turn B's Wi-Fi off and wait until P shows B offline (and no session in the log).
- **Steps:** on P create a group with A and B. Then turn B's Wi-Fi on, wait until B shows online, create it again. Repeat on the desktop
  (paired with both).
- **Pass:** the first attempt shows "Wait until <B's name> show as online, then try again..." (toast on Android, snackbar on the
  desktop), nothing is created and no chat appears; the second attempt creates a `g2-` group. A member that runs an **old build** and is
  online still yields a legacy group, with `Group created as legacy: [...]` in the log.
- **Source:** ERROR-095, ADR-064. **Status:** TODO

### GCALL-14 — An ended call never leaves a device answering "busy" (ERROR-095 follow-up of ERROR-086)
- **Setup:** three phones in one group (any kind). Capture `GROUP_CALL` on the callee.
- **Steps:** place a group call, answer it, end it from the caller, then end it from the callee side; repeat 5 times, ending in different
  orders (caller first, callee first, both within a second, the callee's screen off). After each end, start a new call from the other phone.
- **Pass:** every new call rings; no `declined: busy in group call` line appears while no call is on screen; the overlay clears ~2 s after
  each end.
- **Source:** ERROR-095 (zombie slot), ERROR-086. **Status:** TODO

## 4m. A late joiner connects to every member already in the group call (ERROR-096, 2026-10-01)

Capture first, and **start the capture before the call rings**: `adb logcat -v time -s GROUP_CALL:I WS:I > phone-<name>.txt` on every phone
(the owner's last log began after the accept, which hid the one thing still unexplained), and copy `~/.flash/desktop.log` before the next
desktop launch (it is overwritten). Include the **caller's** log.

### GCALL-15 — The second callee to accept connects to the first one (ERROR-096)
- **Setup:** three devices in one v2 group, all paired through the owner (a phone caller C, a phone A, the desktop D); note which of A and D
  has the lower device id (the lower one answers, the higher one offers). Repeat with the roles swapped so each pair is the late joiner once.
- **Steps:** C starts a video group call. A accepts and waits 20 s. Then D accepts. Watch all three screens for 30 s.
- **Pass:** within about 12 s of D accepting, D and A show each other as connected (video or audio flowing) and the logs have a
  `Leg <peer> pc#1 ... Connected` line on both; neither side has a leg stuck in CONNECTING; on the lower-id device the log may show up to three
  `has waited ... for an offer; telling it directly` lines but not more.
- **Pre-fix:** the lower-id device's leg to the other stayed CONNECTING for the whole call (27 s in the owner's log), no offer was sent.
- **Source:** ERROR-096, ADR-065. **Status:** TODO

### GCALL-16 — Capture: does the phone send `GroupPresence` after it accepts? (ERROR-096, unexplained)
- **Setup:** as GCALL-15 with the capture started **before** the call rings, on the phone that accepts first and on the caller.
- **Steps:** place the call, accept on the phone, keep it open 40 s.
- **Pass:** the phone's log has `Call sendFrame action=GroupPresence peer=<caller>` roughly every 4 s and a `VideoRequest` (or
  `video route ... receive={<peer>}`) once a video stream is wanted; **or**, if it does not, the log has a refusal line naming the gate
  (`logRefusal`, ADR-061) that explains why. Either result closes the question; "no frames and no refusal line" means a new bug.
- **Pre-fix:** 54 s of log with no presence, no refusal and no video on the Android↔caller leg (`vin 0.0fps`, `vout active=false`).
- **Source:** ERROR-096 (not proven). **Status:** TODO

### GCALL-17 — A member who joins late in a larger call, and a full call still turns the extra member away (ERROR-096)
- **Setup:** four or more devices in one v2 group, every member on this build.
- **Steps:** one starts the call; two accept at once; a third accepts after 40 s; if the group has 9+ video-capable members, let the
  extra ones join until the cap (8 video, 12 voice) is reached.
- **Pass:** every pair of members that accepted ends up connected (each device's log shows a Connected leg per other member); a member
  past the cap sees "call is full" and is **not** shown as a connected tile; no device logs more than 3 `telling it directly` lines per
  leg; CPU and heat do not spike at the moment of the late accept.
- **Source:** ERROR-096, ADR-065. **Status:** TODO

## 4y. Group call participant list shows only group members (ERROR-103, 2026-10-06)

### GCALL-18 - No tile for a device that is not in the group
- **Setup:** a v2 group with 3 devices (A caller, B, C). Optionally remove C from the group on A while B is offline, then start a call on B.
- **Steps:** place a group call; look at the participant list on every device; capture `adb logcat -v time -s GROUP_CALL:I`.
- **Pass:** every tile is a current group member; no raw device id of a non-member appears; a log line "not an active member of group=" appears if a stale list was received. Members who are unpaired may still show an id (known naming gap).
- **Source:** ERROR-103.
- **Status:** TODO

## 4y2. Group call leftovers and video feedback (ERROR-104, 2026-10-06)

### GCALL-19 - The "join call" banner goes when the call really ends, and stays while it can be joined
- **Setup:** a v2 group of 3 devices (A, B, C), all on this build. C stays out of the call (never taps Join).
- **Steps:** A starts a call, B joins. Watch C's chat. B hangs up (A is still there), then A hangs up.
- **Pass:** C shows the banner while A and B are in the call, stays while only A remains, and loses it within about 2 s of A's hangup (not 12-17 s later). C's log has `GroupHangup`-driven removal, no `Join` possible for the ended call. A device that left a call that others are still in keeps the banner and can rejoin.
- **Source:** ERROR-104.
- **Status:** TODO

### GCALL-20 - A late invite does not ring after the caller hung up
- **Setup:** a group where one member is slow to dial (screen off, or just out of Wi-Fi range for a moment).
- **Steps:** A starts a call and hangs up within 2 s, before the slow member's session is up. Bring the member back.
- **Pass:** the member's phone never rings, or stops ringing at once; log shows `Invite from ... ignored: that call has already ended`. A fresh call afterwards still rings.
- **Source:** ERROR-104.
- **Status:** TODO

### GCALL-21 - No presence or invite is sent after the call ended
- **Setup:** as GCALL-20, capture `adb logcat -v time -s GROUP_CALL:I` on A.
- **Steps:** end a call while an invitee is still being dialed.
- **Pass:** no `GroupInvite` or `GroupPresence` is sent by A after its `ending reason=` line.
- **Source:** ERROR-104.
- **Status:** TODO

### GVID-08 - Tapping a tile whose video cannot come says why
- **Setup:** a video group call of 4 devices.
- **Steps:** (a) tap a tile whose owner turned the camera off; (b) tap someone while their device is at its send limit (3+ other watchers on a 2.4 GHz link); (c) mute that person as well; (d) with the sender's device hot (or `acceptNew=false` test hook), tap them; (e) block the link between you and them for 20 s (airplane mode on them) after tapping.
- **Pass:** (a) "Camera off"; (b) "Video busy"; (c) "Video busy · Muted" (and "Camera off · Muted"); (d) "Too hot to send video"; (e) "Requesting video…" then, after about 12 s, "Video not responding", and the video appears by itself when the link returns. A granted video whose picture has not arrived yet reads "Starting video…". Their own camera-off does not hide "Muted".
- **Source:** ERROR-104, ADR-066.
- **Status:** TODO

## 4y3. Call media failures, camera errors, data-saver words and voice-to-video (ERROR-105, ADR-078, ADR-079, 2026-10-06)

### CALLMEDIA-01 - A busy microphone says so
- **Setup:** two devices, a voice call. On the callee, start another app that holds the microphone (a voice recorder, or a call from the SIM).
- **Steps:** place the call, answer it on the callee; capture `adb logcat -v time -s CALL:I`.
- **Pass:** the callee's screen ends with "Microphone is busy or unavailable" (not "Call failed"); the caller gets a Decline; log has `accept: media start failed (MIC_UNAVAILABLE)`. With the microphone permission revoked in Settings the text is "Microphone permission needed".
- **Source:** ERROR-105 item A, ADR-079.
- **Status:** TODO

### CALLMEDIA-02 - Denying the camera on an incoming video call joins audio-only
- **Setup:** Android callee with CAMERA not granted (revoke it in Settings), microphone granted. Any caller.
- **Steps:** the caller places a video call; the callee taps Accept and denies the camera prompt.
- **Pass:** the callee toast reads "Joining without camera" and the call connects with audio; the call screen shows "Joined without camera: camera permission is off" (dismissible); the callee's camera button reads "Video off"; the caller's tile for the callee shows camera off; the callee can hear and be heard. Declining the microphone prompt still ends the attempt with the microphone message.
- **Source:** ERROR-105 item B, ADR-079.
- **Status:** TODO

### CALLMEDIA-03 - A camera taken by another app mid-call shows a banner and can be retried
- **Setup:** a 1:1 video call between two Android devices.
- **Steps:** on one device open another camera app (or the system camera) so it takes the camera from Flash; watch the call screen; close the other app and tap **Try again**.
- **Pass:** within a few seconds the banner "Camera stopped. Another app may be using it" appears with **Try again**, the dock shows "Video off", the other device shows camera off for that tile and the call audio continues. After Try again the camera is back, the banner goes and the other device sees video again (no renegotiation: log has no new offer). Run it on Windows by unplugging a USB webcam: nothing is shown (no hook there, documented); the toggle still works after replugging.
- **Source:** ERROR-105 item C.
- **Status:** TODO

### CALLMEDIA-04 - A flip that cannot happen says so and does not stop the camera
- **Setup:** a video call on a device with one camera (or a desktop with one webcam).
- **Steps:** tap **Flip**.
- **Pass:** "Couldn't switch camera" shows for about 5 s and clears itself; the camera keeps running; the peer keeps seeing video.
- **Source:** ERROR-105 item C.
- **Status:** TODO

### CALLMEDIA-05 - A group call joined without a camera
- **Setup:** a group video call of 3 devices; one Android device has CAMERA revoked.
- **Steps:** that device accepts the invite and denies the camera prompt.
- **Pass:** it joins with audio, shows "Joined without camera", and the other two show its tile as camera off (not "Requesting video...").
- **Source:** ERROR-105 items A to C (group session), ADR-079.
- **Status:** TODO

### GVID-09 - Tapping a tile under data saver or Show fewer says why nothing appears
- **Setup:** a video group call of 4 devices.
- **Steps:** (a) turn data saver on (More panel) and tap a tile; (b) turn it off, turn on "Show fewer videos" and tap a tile that is not the main one; (c) tap the main one; (d) in compact (phone) mode tap a chip in the strip with TalkBack on.
- **Pass:** (a) "Video is off to save data"; (b) "Showing fewer videos"; (c) no such message (it holds the one video); (d) TalkBack reads the name, shown / not shown and the same words. A muted person still reads "Muted", a camera-off one "Camera off".
- **Source:** ERROR-105 item D.
- **Status:** TODO

### VUP-01 - A voice call becomes a video call (the first real check of the SDP risk)
- **Setup:** two devices on this build, a connected voice call. Run Android to Android (caller adds, then callee adds) and Android to Desktop in both roles. Capture `adb logcat -v time -s CALL:I` on the Androids and the desktop log.
- **Steps:** tap the **Camera** button on one device and allow the camera. Then on the other device tap **Camera** too.
- **Pass:** the first device shows its own preview and the other shows the first's video within about 3 s without the audio dropping; the other device starts with its camera off and a **Camera** button; after it taps, both see both videos. Log shows `camera added to the call`, a `video upgrade offer delivered=true` on the caller, a `remote video track` on the receiver and no `ending`. Repeat once while both are on a hotspot and once with a 2 minute call afterwards (the call must stay up).
- **Source:** ERROR-105 item E, ADR-078.
- **Status:** TODO

### VUP-02 - A peer without `cv1` is never offered the button
- **Setup:** one device on this build, one on an older build (before this change), voice call.
- **Steps:** look at the call screen on the new build.
- **Pass:** no **Camera** button; no `vu` or `m=video` offer is sent (log); the call is unchanged. The HELLO of the new build shows `cv1` in `caps`.
- **Source:** ADR-078.
- **Status:** TODO

### VUP-03 - Upgrading in the background-sensitive path keeps the microphone type
- **Setup:** Android 14 or newer, a connected voice call, Flash in the foreground on device A.
- **Steps:** (a) A taps **Camera**; (b) separately, put A in the background with the screen on and let B add a camera so A's call flips to video; check `adb shell dumpsys activity services com.transfer.flash` for the foreground service type.
- **Pass:** (a) the service type gains `camera` and keeps `microphone`; (b) the type stays `microphone` (no `camera`), the call audio continues, there is no `startForeground(type=...) refused` line, and A's own camera stays off until the user opens the app and taps **Camera**.
- **Source:** ADR-078 (foreground service consequence).
- **Status:** TODO

### VUP-04 - An upgrade during an ICE restart
- **Setup:** a connected voice call on Wi-Fi; Android to Android.
- **Steps:** switch the callee's Wi-Fi off and on to force an ICE restart, and tap **Camera** on either device within a second of the reconnect.
- **Pass:** either the video arrives once the connection is back, or the camera preview shows with no remote video and the call stays up and audio continues (the request is retried when the connection or signaling returns, so the video should arrive; if it does not, that retry failed on this stack); in no case does the call end because of the upgrade. Record which one happened.
- **Source:** ADR-078 (known risk).
- **Status:** TODO

## 4z. Persistent log, export and swarm restart prompt (ADR-077, 2026-10-06)

### LOG-01 - The log survives without adb
- **Setup:** debug or release build installed on a phone; no adb attached.
- **Steps:** use the app for a minute (open chat, start a transfer), force-stop it, reopen it, Settings > Diagnostics > Export logs, send the file to the desktop.
- **Pass:** the file starts with the device header and holds lines from before the force-stop with tags such as `DISCOVERY`, `GROUP_CALL`, `SWARM`; no `secret=`, invite link or 64+ character key text appears.
- **Source:** ADR-077.
- **Status:** TODO

### LOG-02 - A crash is recorded
- **Setup:** a debug build; provoke a crash (a test hook or kill a worker with an exception).
- **Steps:** crash, reopen, export.
- **Pass:** an `E/CRASH: uncaught in thread` entry with the stack trace is in the export.
- **Source:** ADR-077.
- **Status:** TODO

### LOG-03 - The log stays small
- **Setup:** a phone left running with groups and calls for a day.
- **Pass:** `files/logs` holds at most 5 files and under ~5.5 MB; no slowdown while a large transfer runs (compare speed with EXP numbers).
- **Source:** ADR-077.
- **Status:** TODO

### SWM-34 - Restart prompt for the swarm switch
- **Setup:** Android build, swarm switch off.
- **Steps:** Settings > turn "Group file sharing (swarm, experimental)" on; answer "Later", then repeat and answer "Restart now".
- **Pass:** "Later" keeps the app open; "Restart now" closes and reopens Flash within about 3 s on the Home screen; the switch is still on; logcat or export shows `SWARM` lines at start. Repeat on a Transsion handset (a restart killed by the system must not leave a half-started app).
- **Source:** ADR-077.
- **Status:** TODO

## 4n. Group video audit (ERROR-097 / ADR-066, 2026-10-01)

Nothing here was run on a device. Capture on every phone: `adb logcat -v time -s GROUP_CALL:I WS:I`; copy `~/.flash/desktop.log` before the
next desktop launch. Useful log lines: `video route`, `Leg <peer> video send=on|off height=`, `video sender tuned ... concession=`, `Leg <peer>
voice priority: video <from> -> <to>`, `Leg <peer> ... rebuilding`.

### GVID-01 — Turning the camera off stops the video to the people already watching (ERROR-097 #1)
- **Setup:** three devices in a video group call, A sending to B and C (both showing A's tile with video).
- **Steps:** A turns its camera off, waits 10 s, turns it on.
- **Pass:** within about 2 s of "off" both tiles show A's avatar and "Camera off"; A's log has `video send=off` for B and C and the `tuned`
  line shows `active=false`; A's outbound video bytes (the `CALL_DIAG` line) stop growing; after "on" B and C show A's video again within
  about 5 s with no action on their side.
- **Pre-fix:** the existing watchers kept receiving an encoded stream while the UI said the camera was off.
- **Source:** ERROR-097 #1. **Status:** TODO

### GVID-02 — A lost request is asked again (ERROR-097 #4)
- **Setup:** three devices on one Wi-Fi in a video call. On B, turn Wi-Fi off for about 3 s right as the call connects (or while B changes
  which participant is pinned).
- **Steps:** B pins A after the blip and waits 15 s.
- **Pass:** B's log shows a second `VideoRequest` to A with a new sequence (about 5 s after the first) and then A's grant; A's tile on B
  shows video without B doing anything else. No further `VideoRequest` to A once granted (check 60 s of log).
- **Pre-fix:** a request lost in a session gap was never repeated and the tile stayed on "Requested" for the rest of the call.
- **Source:** ERROR-097 #4. **Status:** TODO

### GVID-03 — A pinned peer that turns the request down leaves no ghost video on a one-video phone (ERROR-097 #6)
- **Setup:** one LOW-tier phone P (room for one video) and two other devices X and Y; make X refuse (X at its send cap, or hot).
- **Steps:** on P pin X while X refuses; watch P's log and the thermal/CPU for 30 s.
- **Pass:** P's tile for X says Busy; P's log shows `receive={}`: no video arriving from Y, and no decoder running (CPU not above the
  idle-call level). Unpinning X brings Y's video back.
- **Source:** ERROR-097 #6. **Status:** TODO

### GVID-04 — Native surfaces exist only for tiles with a picture (ERROR-097 #14)
- **Setup:** five or more devices in a video call; one MEDIUM or HIGH phone as the observer.
- **Steps:** on the observer run `adb shell dumpsys SurfaceFlinger --list | grep -c -i <package>` (or the layer list in Developer options)
  while the call shows 5 tiles, then after "Show fewer".
- **Pass:** the layer count for the call screen equals the videos actually arriving plus the local preview, not one per participant;
  a tile whose peer turned the camera off or was turned down shows the avatar and adds no layer; rotation and a pin change do not leave a
  black tile or a frozen frame; a track renegotiation does not flash the tile.
- **Source:** ERROR-097 #14. **Status:** TODO

### GVID-05 — A bad link costs only that leg its video, and the setting turns it off (ERROR-097 #9)
- **Setup:** three devices on a phone hotspot (or any weak link); one device D moves away from the access point until its leg is lossy,
  the other two stay close. "Prioritise voice quality" on (the default).
- **Steps:** hold the call 60 s with D far, then bring D back, then switch "Prioritise voice quality" off in Settings and repeat.
- **Pass:** the log shows `Leg <D> voice priority: video FULL -> REDUCED_BITRATE` (and further rungs if it stays bad) for D's leg **only**;
  the two close devices keep full video to each other; D's audio stays intelligible; about 5 s after the link is clean the rung steps
  back, and the `tuned` line shows the floor (`min=`) only at FULL. With the setting off no `voice priority` line appears and a reduced
  leg returns to full on the next tick.
- **Pre-fix:** the group had no voice priority at all; the 1:1 call did.
- **Source:** ERROR-097 #9. **Status:** TODO

### GVID-06 — A leg that stays Disconnected after a Wi-Fi roam is rebuilt (ERROR-097 #16)
- **Setup:** three devices in a call; on B switch the Wi-Fi network (5 GHz to 2.4 GHz of the same router, or off and on after 3 s).
- **Steps:** wait 30 s. Repeat with the hotspot owner moving.
- **Pass:** if the leg to the higher-id peer stays `Disconnected`, the log shows `pc#N failed; rebuilding (1/3)` about 10 s later (from the
  higher-id device, the offerer) and a new `pc#N+1 created`, then the leg is Connected and video returns; a leg that heals by itself within
  10 s is **not** rebuilt; while signaling to the peer is down no rebuild is logged.
- **Pre-fix:** the leg stayed Disconnected for the rest of the call.
- **Source:** ERROR-097 #16. **Status:** TODO

### GVID-07 — A muted talker does not evict a watcher; a departed peer holds no slot (ERROR-097 #5, #7)
- **Setup:** four devices where the sender S has a send cap of 1 or 2 (a LOW or MEDIUM phone) and two or more watchers.
- **Steps:** (a) S mutes the microphone and makes noise next to it while a third device asks to watch S; then (b) S unmutes and talks; (c) a
  watcher hangs up (during a pin change) and the log is read for the rest of the call.
- **Pass:** (a) no existing watcher is dropped and the new one gets "Busy"; (b) talking makes room by dropping the oldest unpinned watcher
  (owner decision Q5); (c) after the hangup the log shows no `video send=on` for that peer and S's free slots return to the cap.
- **Source:** ERROR-097 #5, #7. **Status:** TODO

### Measurements added to section 5
`MEAS-09` (group video floor value) was added; the CPU threshold is `MEAS-05` and the LOW-tier second watcher / encoder instances are
part of `MEAS-03`. They are not tests of ERROR-097 and do not block it.

## 4o. Transsion / Infinix screen-off freeze: candidate fixes (owner's list, 2026-10-02)

Nothing here was run. Source: `docs/android-platform-notes.md` addendum of 2026-10-02 and ERROR-074. Do these on the Infinix X6882B
(Android 14) with a second phone as the sender. Before trusting any result, capture `adb logcat -v time | findstr Hiber`.

### HIB-01 — Which Phone Master activity opens the autostart screen on the Infinix?
- **Setup:** the Infinix with the current dev build; Phone Master installed (default).
- **Steps:** (a) run `adb shell cmd package resolve-activity --brief -n com.transsion.phonemaster/com.transsion.phonemaster.autostart.AutoStartActivity`
  and the same with `AutoStartManageActivity`; (b) in the app, Settings, "Background transfers", note where the OEM screen lands; (c) enable
  autostart for Flash there, lock the screen for 5 minutes and send a message from the other phone every 30 s.
- **Pass:** at least one class resolves and its screen opens on the autostart list for Flash (record which); and after (c) either the
  logcat shows no `freeze uid` for Flash during the 5 minutes, or it still does and that is written down (then autostart alone is not the fix).
- **Source:** ERROR-074, platform notes 2026-09-28 and 2026-10-02. **Status:** TODO

### HIB-02 — Does a silent audio loop in a `mediaPlayback` service keep Flash running with the screen off?
- **Setup:** a throwaway build (not for release) with a looping silent track under a `mediaPlayback` foreground service, started with the
  mesh service; Infinix with Hiber active; 10 minutes with the screen off.
- **Steps:** send a message every 30 s from the other phone; read `Hiber` lines; note battery % at start and end, and repeat once without the loop.
- **Pass:** a number for each of: messages delivered with the screen off (of 20), `freeze uid` lines for Flash, battery drain per hour with
  and without the loop. The experiment is worth keeping only if delivery is near the Samsung's 90 % (EXP-002) and the drain is acceptable
  to the owner. Record in `logs/experiments.md`.
- **Source:** the owner's option 2, 2026-10-02. **Status:** TODO

### HIB-03 — First-run guide reaches the right screens (permissions, autostart, pairing)
- **Setup:** a fresh install on the Infinix and on a Samsung (no OEM freezer); built only after the owner approves the guided first-run flow.
- **Steps:** follow the guide: notifications, battery exemption, the OEM autostart screen (Infinix only), pair a first device with a code.
- **Pass:** each step opens the right screen or is skipped cleanly where the phone has none; no crash if the OEM activity is missing; pairing completes.
- **Source:** deck slide 11 "First-Run Guide". **Status:** TODO (the flow does not exist yet; only the Settings toggle does)

### HIB-04 — Does a Companion Device Manager association keep Flash running with the screen off? (experiment)
- **Setup:** a throwaway build that associates the Infinix with a Bluetooth device (a spare phone or earbuds) through the Companion Device
  Manager and holds the three `REQUEST_COMPANION_*` permissions; Hiber active; 10 minutes with the screen off.
- **Steps:** as HIB-02 (a message every 30 s from the other phone, read `Hiber` lines).
- **Pass:** a number for messages delivered (of 20) and for `freeze uid` lines, compared with the same build without the association.
  Worth keeping only if it clearly beats 4 % (EXP-002) and the user can be asked to pair a device.
- **Source:** platform notes 2026-10-02 (b). **Status:** TODO

## 4p. Hotspot client isolation (owner report 2026-10-02, FO-06)

Nothing here was run. Source: `docs/FUTURE-OPTIMIZATION.md` FO-06 and the ERROR entry of 2026-09-30.

### HOT-01 — Which hotspot hosts isolate their joined phones?
- **Setup:** three phones (A, B and a host H). Repeat with H = the Infinix, then H = a Samsung, then H = a laptop hotspot if available.
- **Steps:** join A and B to H's hotspot. On A run `adb shell ping -c 3 <B's IP>` and `adb shell ping -c 3 <H's gateway IP>`; the same from B. Open
  Flash on A and B and note whether each sees the other under Nearby and whether Connect by IP works.
- **Pass:** a table per host make: A to B, B to A, A to H, H to A (ping and Flash). The matrix is the result; no pass/fail on the phones.
  Record it in `logs/experiments.md` and in MEAS-07's "Android hotspot with 2+ clients" row.
- **Source:** FO-06. **Status:** TODO

### HOT-02 — Does a local-only hotspot avoid the isolation?
- **Setup:** a throwaway build that calls `startLocalOnlyHotspot` on the Infinix and shows the network name and password; A and B join it by hand.
- **Steps:** as HOT-01 with the local-only hotspot as H.
- **Pass:** A and B can ping each other and Flash finds the peer; or it does not, and that is written down (then option 4 of FO-06 is dead for Infinix).
- **Source:** FO-06 option 4. **Status:** TODO

### HOT-03 — Which of our phones support Wi-Fi Aware?
- **Setup:** each test phone.
- **Steps:** `adb shell pm list features | findstr -i aware`; in a throwaway build call `WifiAwareManager.isAvailable()` with the hotspot on and off.
- **Pass:** a list of phones with `android.hardware.wifi.aware` yes/no, and whether availability changes while a hotspot is running.
- **Source:** FO-06 option 2. **Status:** TODO

## 4q. In-call control dock: icons, state and motion (UI-050e, 2026-10-02)

Built and unit-tested 2026-10-02 (`FlashCallControlDockTest`, `FlashCallControlDockRenderTest`); rendered in a Skia scene on the desktop JVM only.
Nothing here has been seen on a phone. Run each on the HIGH tier, then repeat the "no animation" checks with the performance mode pinned to MEDIUM and LOW
(Settings, performance mode).

### CALLDOCK-01 — Do the new glyphs read correctly on a real screen?
- **Setup:** two phones, a video call, then an audio call; light and dark theme.
- **Steps:** look at the dock in each state: mic live / muted, video on / off, speaker / earpiece, camera off with Flip dimmed, End.
- **Pass:** the mic shows a microphone (not a bell), muted shows the slash; video shows a video camera and off shows the slash; Flip reads as a flip;
  End is a handset on its back (not "blocked"); each toggled state is the inverted solid button plus the new label.
- **Source:** UI-050e. **Status:** TODO

### CALLDOCK-02 — HIGH tier: the motion plays and feels right
- **Setup:** a phone on HIGH, video call.
- **Steps:** tap Mic, Video, Flip, Route, hold End (do not release off the button). On the ringing screen leave the call ringing for 10 s.
- **Pass:** Mic / Route glyph pops and a ring leaves (live) or collapses (muted); Video opens like an eyelid; Flip makes a half turn per tap; End tips
  while held; the Accept handset swings for about half a second every ~2.5 s. No dropped frames (note any), no leftover ring.
- **Source:** UI-050e. **Status:** TODO

### CALLDOCK-03 — MEDIUM and LOW: no animation, same information
- **Setup:** the same phone with the performance mode pinned to MEDIUM, then LOW (or the rugged handset on LOW).
- **Steps:** repeat CALLDOCK-02's taps; ring a call for 10 s.
- **Pass:** every glyph swaps instantly, no ring, no spin, no tip, no wiggle loop, the dock is opaque; the haptic tick still fires; every label and
  description is unchanged.
- **Source:** UI-050e, `FlashPerformanceMode` (reduceMotion / minimalChrome). **Status:** TODO

### CALLDOCK-04 — TalkBack
- **Setup:** TalkBack on, active video call.
- **Steps:** swipe through the dock; toggle each control.
- **Pass:** each control is read once, as the action ("Mute microphone", "Turn camera off", "Switch to earpiece", "End call"); after a toggle the
  next focus reads the opposite action; Flip is announced as disabled while the camera is off.
- **Source:** UI-050e. **Status:** TODO

### CALLDOCK-05 — Five slots fit a small screen
- **Setup:** the smallest handset available (the rugged 480x640 one if possible) and a desktop window at the 640x480 dp minimum; video call.
- **Steps:** open the call; look at the dock and the labels.
- **Pass:** all five buttons and labels visible, no ellipsis on a label, no horizontal overflow.
- **Source:** UI-050e. **Status:** TODO

## 4r. In-call extras (UI-050f, ADR-067, 2026-10-02)

Built and unit-tested 2026-10-02 (`CallStatusBookTest`, `FlashCallStatusTest`, `CallFrameCodecTest`, `FlashCallAudioRoutingTest`,
`FlashCallExtrasTest`); compiled for Android and desktop. **Nothing has run on a device.** Use two builds that both have this change unless a
test says "old build" (an old build ignores the new frame). Repeat the visual checks on HIGH, then MEDIUM / LOW.

### CALLX-01 — Audio output list with a headset
- **Setup:** one phone with Bluetooth earbuds and, separately, wired headphones; a voice call to a second device.
- **Steps:** with nothing connected tap the output button (it flips speaker / earpiece). Connect the earbuds: the button should name Bluetooth.
  Tap it: a list shows Earpiece, Speaker, Bluetooth. Pick each; speak and listen. Pick Bluetooth, then switch the earbuds off. Repeat with wired.
- **Pass:** the sound moves to the chosen output each time; the button label and glyph follow; switching the earbuds off drops back to the
  earpiece (not silence) and the list loses the entry; `adb logcat -s FlashCallAudioRouter` shows `route=... chosen=...` lines and "route pick ... is gone".
- **Source:** ADR-067 item 5. **Status:** TODO

### CALLX-02 — Remote mic / camera badges (1:1 and group)
- **Setup:** two devices (1:1), then three (group video).
- **Steps:** the other device mutes, turns the camera off, and back on, in turn.
- **Pass:** a mic-off badge appears and clears under the name; with the camera off the avatar and "<name>'s camera is off" cover the video and clear
  when it is back; in a group the tile word and badge follow. A joiner who connects after the mute still sees the right state within a second.
- **Source:** ADR-067. **Status:** TODO

### CALLX-03 — Raise hand and reactions
- **Setup:** two devices, then three.
- **Steps:** More, Raise hand on one; the others look. Send like, love, wow, and tap one repeatedly and fast.
- **Pass:** a hand badge shows on the sender's tile or under the name and clears on lowering; each reaction appears on every screen with the
  sender's name, floating at HIGH and as a still stack on MEDIUM / LOW; fast taps produce roughly one per 400 ms, not a flood; a replayed frame
  never shows twice.
- **Source:** ADR-067 items 1, 3. **Status:** TODO

### CALLX-04 — Data saver, 1:1
- **Setup:** two phones, a video call, a data counter on the saver's phone.
- **Steps:** More, Data saver on the first phone; wait 30 s; look at the second phone's note; turn it off.
- **Pass:** the first phone shows "Video paused" and the pill; its inbound video rate drops to ~0 (call stats) while audio continues; the second
  phone shows the "on data saver" note and its own video still shows locally; turning it off restores video within a few seconds.
- **Source:** ADR-067 item 4. **Status:** TODO

### CALLX-05 — Data saver, group
- **Setup:** three or four devices, a group video call.
- **Steps:** one device turns on data saver; the others keep video among themselves.
- **Pass:** the saver receives no video (tiles show avatars), audio from everyone continues, the others' video to each other is unaffected;
  turning it off re-requests video.
- **Source:** ADR-067 item 4. **Status:** TODO

### CALLX-06 — Picture-in-picture
- **Setup:** an Android 12+ phone and an Android 8-11 phone if available; a 1:1 video call.
- **Steps:** press Home during the call; return; use More, Picture-in-picture; resize the window; end the call while in PiP.
- **Pass:** on 12+ the call shrinks to a window showing the remote picture (or the avatar) with no controls, video keeps flowing, returning
  restores the full screen with the call intact and no black surface; on 8-11 the More row does the same; no crash and no activity restart
  (`adb logcat` shows no `onCreate` on entering PiP). Note any Samsung / Transsion difference.
- **Source:** ADR-067 item 6, platform notes. **Status:** TODO

### CALLX-07 — Keep screen on during video
- **Setup:** a phone with a short screen timeout (15 s); a video call.
- **Steps:** leave the phone untouched for 60 s. Then end the call and wait.
- **Pass:** the screen stays on for the call, and times out normally after it ends.
- **Source:** ADR-067 item 6. **Status:** TODO

### CALLX-08 — Proximity screen-off
- **Setup:** a phone with a proximity sensor; a voice call on the earpiece.
- **Steps:** hold the phone to the ear; take it away; switch to speaker and hold it near; start a video call and hold it near; connect earbuds.
- **Pass:** the screen turns off against the ear and on when taken away on the earpiece in a voice call only; not with speaker, a headset or video;
  the lock is released when the call ends (screen behaves normally). Note a phone without the sensor does nothing.
- **Source:** ADR-067 item 6. **Status:** TODO

### CALLX-09 — Mirror my video
- **Setup:** a phone with a front camera, a video call.
- **Steps:** More, Mirror my video on and off; swap the preview to the main tile (tap it) and repeat.
- **Pass:** only the local preview flips left-to-right, in the corner and in the main tile; the other person's view of you is unchanged.
- **Source:** ADR-067. **Status:** TODO

### CALLX-10 — Connection chip and Verified shield
- **Setup:** two paired phones on one Wi-Fi, then on one phone's hotspot; also one unpaired device if a call can be made (otherwise skip).
- **Steps:** look at the chip in the first 10 s and during a good and a degraded link (move away from the router).
- **Pass:** "Local network" with a dot that is green on a quiet LAN, amber or red when RTT / loss rise (record the RTT / loss at each colour:
  these are the first-guess thresholds 60 / 200 ms and 2 / 8 %); the "Verified" shield shows for a paired 1:1 peer. Record the numbers for `MEAS-*`.
- **Source:** ADR-067 item 7. **Status:** TODO

### CALLX-11 — Dock fits and the More panel on small screens, desktop included
- **Setup:** the smallest handset (480x640), a 360 dp phone, and the desktop at its minimum window.
- **Steps:** video call (six buttons) and voice call (four); open the output list and More.
- **Pass:** no overflow or ellipsised label, buttons shrink rather than wrap, the panels fit and scroll-free content is fully visible, the close glyph
  and tapping the dim both close; on desktop the output button stays the speaker toggle (no list).
- **Source:** UI-050f. **Status:** TODO

### CALLX-12 — Old build compatibility and TalkBack
- **Setup:** one build with this change, one without; TalkBack on the new one.
- **Steps:** call between them; mute, raise hand, react on the new one. With TalkBack, swipe through the dock and panels and toggle each.
- **Pass:** the call works exactly as before, the old build shows nothing new and logs no error for the `status` frame; with TalkBack each control
  is read once as an action, switches read on / off, and a reaction is announced as "<name> reacted: <kind>".
- **Source:** ADR-067 item 1. **Status:** TODO

## 4s. Feature-audit checks (docs/audit/2026-10-02-feature-completeness-audit.md, 2026-10-02)

Owed by the audit: the findings were read from code, **nothing was run**. Each test confirms or refutes one finding; a FAIL on the "expected
good" side becomes an ERROR entry (section 35 rule 3). Fixes are not started.

### FA-01 - Whole-file verification on every host (FA-1, ADR-068, ERROR-098)
- **Status of the code:** fixed 2026-10-02, unit-tested, **not device-verified**. This test now checks the FIX, not the defect.
- **Setup:** a phone and the Windows desktop; a 50 MB file; a hex editor. Run once phone to desktop, once desktop to phone, once phone to phone.
- **Steps (a, healthy):** send the file normally; compare the received file's SHA-256 with the original.
- **Steps (b, damaged):** start the transfer, stop the **receiving** app mid-way, flip one byte inside the partial file in the receiving app's
  `FlashReceived/<transferId>/` folder, restart the app and let the transfer resume to the end.
- **Pass (a):** the transfer completes, the hashes are equal, the row reads "Verified", logcat / desktop log shows `wholeFile=MATCH`.
- **Pass (b):** the receiver's row reads **Failed** with "The file arrived damaged and was discarded. Try again to receive it afresh.", the file is gone
  from the folder, logcat shows `wholeFile=MISMATCH`; the **sender's** row reads Failed with "The other device reports the file arrived damaged...";
  then Retry on either side sends the whole file again and ends Verified with an equal hash. Note whether Retry works from the receiver, the sender or both.
  On the desktop, which keeps no chunk state across a restart, step (b) needs the app to stay up: pause the transfer, damage the file, resume.
- **Fail:** any "Verified" label on a damaged file, a surviving damaged file, or a Retry that finishes instantly without resending.
- **Source:** FA-1, ADR-068. **Status:** TODO

### FA-02 - Out of space (FA-2, ADR-069, ERROR-099)
- **Status of the code:** fixed 2026-10-03, unit-tested, **not device-verified**. The original audit expectation was "accepted, then fails mid-way with an exception text".
- **Setup:** a phone (or a small desktop volume) with a few MB free; a sender with a larger file. Run phone to phone, and phone to desktop on a nearly full volume.
- **Steps:** send, then Accept on the receiver (also once with auto-accept on, if the setting exists). Also: with free space just above the file size, accept and
  complete; and a resumed transfer with most of it already written while space is tight.
- **Pass:** the receiver's row turns **Failed** at once with "Not enough free space to receive this file. It needs X and only Y is available...", no file or partial
  folder is left under `FlashReceived/`, logcat shows `inbound refused ... needed=... free=...`; the sender's parked transfer ends (record exactly what its row says: expected
  "cancelled" without the reason). Check that "only Y" matches Settings > Storage within a few MB. The exact-fit and resume cases are admitted and finish.
- **Fail:** the offer is accepted and fails mid-write, a wrong free figure, a refusal when space was enough, or a sender row stuck waiting.
- **Source:** FA-2, ADR-069. **Status:** TODO

### FA-03 - Failure wording (FA-3, ADR-069, ERROR-099)
- **Status of the code:** fixed 2026-10-03, unit-tested, **not device-verified**.
- **Steps:** provoke (a) the source file deleted or its permission revoked while sending, (b) the peer leaves Wi-Fi mid-transfer, (c) cancel by the other side,
  (d) the peer app force-stopped mid-transfer, on both the sender and the receiver row.
- **Pass:** every Failed row reads a plain sentence from `TransferFailureText` ("The file could not be read...", "The connection to the other device was lost...",
  "The other device cancelled the transfer.", or the generic "The transfer stopped unexpectedly. Try again."); none shows `source length mismatch`, `all channels failed`,
  an exception class or a number-heavy string. Record each string and whether it matched the situation (the generic one is acceptable but note when it appears: that is a
  reason to add a mapping). logcat has the raw reason under `TRANSFER ... transfer failed`.
- **Source:** FA-3, ADR-069. **Status:** TODO

### FA-04 - Restart during a transfer (FA-4)
- **Setup:** two phones, a 500 MB file.
- **Steps:** at 40 %, force-stop the **sender** app; reopen it. Repeat force-stopping the **receiver**.
- **Pass (audit expectation = defect):** the Transfers screen lists the earlier transfer or not (record which); the sender can or cannot resume
  without picking the file again (record which); the receiver's partial progress is or is not reused when the file is sent again.
- **Source:** FA-4. **Status:** TODO

### FA-05 - Multiple files and a folder from the app (FA-5)
- **Steps:** in the attach picker on Android and desktop try to select 3 files at once; try a folder. Then share 3 files from the Android gallery
  to Flash.
- **Pass (audit expectation = defect):** the picker takes one file; the share target takes all three. Record what the chat shows for the three.
- **Source:** FA-5. **Status:** TODO

### FA-06 - Empty file (FA-6, ADR-069, ERROR-099)
- **Status of the code:** fixed 2026-10-03, unit-tested, **not device-verified**. The audit expectation was a Failed or stuck-Queued transfer with a technical message.
- **Steps:** create a 0-byte file and send it from Android (from the picker and from the system share sheet) and from desktop. Also send a file from a provider that may
  not report a size (a cloud-drive or "recent" document), and a normal file.
- **Pass:** the 0-byte file shows a **Failed** row (Transfers list and the chat bubble) reading "This file is empty (0 bytes), so there is nothing to send.", and the
  receiver is never asked; the unknown-size file is sent normally and completes with the right size; the normal file is unchanged. logcat shows `send refused ... reason=empty`.
- **Fail:** a Queued row that never moves, a technical message, a receiver offer for the empty file, or a wrongly refused real file.
- **Source:** FA-6, ADR-069. **Status:** TODO

## 4t. Discovery module review (ERROR-100, `docs/audit/2026-10-03-discovery-module-review.md`, 2026-10-03)

All fixes are unit-tested, none device-verified. Tools: the `Discovery sources:` line (DR5) in logcat / the desktop log shows which transport sees which peer and how
long ago; filter logcat on `MulticastTransport` and `DISCOVERY`.

### DISC-01 - The beacon survives a network change (B1)
- **Setup:** one phone and the Windows desktop on the same Wi-Fi; Flash running on both; discovery mode STANDARD.
- **Steps:** on the phone switch Wi-Fi off and on (or move it from the router to a hotspot and back). Wait 2 minutes without touching either app. On the desktop read the
  newest `Discovery sources:` line.
- **Pass:** the desktop still lists the phone, and its `multicast=[...]` entry for the phone shows an age under 30 s; the phone's log has `Forcing multicast rebind`.
  Before the fix the phone fell out of the desktop's multicast view after about 60 s.
- **Fail:** the phone is missing from the `multicast=` part or its age keeps growing.
- **Source:** ERROR-100 B1. **Status:** TODO

### DISC-02 - A changed address is shown (B5)
- **Setup:** as DISC-01, peer visible in Nearby.
- **Steps:** change the phone's address without restarting Flash (turn Wi-Fi off and on so DHCP hands out a different address, or renew the lease on the router).
- **Pass:** within about 30 s the desktop's Nearby entry shows the new address (or the connection re-dials the new one) without the entry disappearing and reappearing.
- **Fail:** the old address stays until Flash is restarted.
- **Source:** ERROR-100 B5. **Status:** TODO

### DISC-03 - A restarted peer does not flicker out (B3)
- **Setup:** phone and desktop paired and visible.
- **Steps:** force-stop Flash on the phone and open it again within a few seconds, three times.
- **Pass:** each time the phone reappears in the desktop's Nearby; after the final start it stays listed for at least 2 minutes with no `Lost` for it in the log while it is running.
- **Fail:** the phone disappears while it is running and only returns after another restart or network change.
- **Source:** ERROR-100 B3 (reproducing the name-conflict suffix on a device is not guaranteed; this is the closest ordinary route). **Status:** TODO

### DISC-04 - One browse loop in ECO after a restart (B4)
- **Setup:** an Android phone in ECO mode (20 s browse / 100 s idle), logcat open on `DISCOVERY`/`NsdTransport`.
- **Steps:** during an idle phase toggle Wi-Fi off and on (forces `restartBrowsing`), then watch 5 minutes.
- **Pass:** the number of `Scanning network` states stays at one per 120 s cycle; no second browse starts inside a scan window.
- **Fail:** scan states arrive more often than once per cycle after the toggle.
- **Source:** ERROR-100 B4 (no unit test exists for this one). **Status:** TODO

### DISC-05 - A flood of fake devices is bounded (B7)
- **Setup:** a phone with Flash running, a PC on the same Wi-Fi with Python.
- **Steps:** send 400 announcements with distinct device ids to `224.0.0.168:45823` (format: `MulticastProtocol.encode`; easiest is a short test harness reusing the repo's encoder).
  Check Nearby and logcat.
- **Pass:** Nearby never lists more than 256 spoofed devices, logcat shows exactly one `Multicast peer table full (256)` warning, the app stays responsive, and real peers that were already
  listed stay listed.
- **Fail:** the list keeps growing, repeated warnings, jank.
- **Source:** ERROR-100 B7. **Status:** TODO

### DISC-06 - Dual-stack peer resolves to IPv4 (B10)
- **Setup:** an Android 14+ phone on a router that gives IPv6 addresses; the desktop on the same router (dual-stack).
- **Steps:** open Nearby on the phone and read the desktop's address in the `Discovery sources:` line for the `NSD` transport.
- **Pass:** the address is IPv4 (`192.168.x.x`), and a transfer or chat connection to the desktop works.
- **Fail:** an `fe80::` address, or no connection. (The old code was never observed failing; this is a defensive change.)
- **Source:** ERROR-100 B10. **Status:** TODO

### DISC-07 - Does the multicast transport hear peers when NSD is idle on Android 14+? (B8, suspected)
- **Setup:** an Android 14+ phone (the Infinix is API 34) in ECO mode so that NSD's browse is stopped for the 100 s idle phase, the desktop announcing; screen on.
- **Steps:** watch the `Discovery sources:` line during an idle phase and note the age of the desktop under `multicast=`.
- **Pass:** the multicast entry's age stays under about 25 s through the idle phase (the beacon is heard without NSD browsing).
- **Fail:** the age grows through the idle phase and resets only when NSD browses again. That confirms B8: `AndroidMulticastSocketFactory` needs its own multicast lock on API 34+ (battery
  trade-off to decide with the owner).
- **Source:** ERROR-100 B8. **Status:** TODO

### DISC-08 - A multicast-only peer survives one lost datagram (B11)
- **Setup:** a network where NSD does not see the peer but multicast does (a hotspot that passes UDP multicast, or temporarily block mDNS 5353 on the router / firewall of one side).
- **Steps:** leave both apps running for 10 minutes; note any `Lost` followed by `Found` for the peer in the log.
- **Pass:** no Lost/Found pair for the peer while both are running. (Before the fix a single dropped announcement, a 40 s gap, caused one.)
- **Fail:** the peer flaps.
- **Source:** ERROR-100 B11. **Status:** TODO

## 4u. Transfer v2 / swarm / multi-file decision measurements (plan only, `docs/transfer/TRANSFER-V2-SWARM-AND-MULTIFILE-PLAN.md`, 2026-10-03)

Nothing is built. These are the measurements that decide whether the swarm (P5) and a transport spike (P6) are worth starting. Record each result in `logs/experiments.md`.

### SWM-01 - Group fan-out baseline today
- **Setup:** one sender phone and 3, 6 and (if available) 10 receivers on the **same router Wi-Fi**; a 100 MB file; same build everywhere.
- **Steps:** send the file to the group; note start, the time the last member completes, per-member completion times, sender CPU and battery drop, sender temperature.
- **Pass (measurement):** a table of N vs total time vs sender cost. The question it answers: does time grow with N-1 (sender-limited) or flatten (airtime / receiver-limited)?
- **Source:** plan section 4.1, FO-04. **Status:** TODO

### SWM-02 - Where the airtime goes
- **Setup:** as `SWM-01` with 6 receivers; one run with the sender next to the router, one with the sender far from it (weak signal).
- **Steps:** send; compare total time near vs far, and compare with the same file sent to one receiver.
- **Pass (measurement):** whether a slow sender link stretches the whole group send (that is the case where a swarm helps) and what the total is relative to N-1 x the one-receiver time.
- **Source:** plan section 4.1 (an unverified airtime argument). **Status:** TODO

### SWM-03 - Can members reach each other?
- **Steps:** repeat `HOT-01`..`HOT-03` (section 4p) with the aim of a **member-to-member** session: on a router, and on a phone hotspot with 3 phones joined.
- **Pass (measurement):** a yes / no per network type per phone model for direct member-to-member sessions. A swarm needs "yes"; a hotspot is expected to say "no" (client isolation).
- **Source:** plan section 4.2 and 4.3, FO-06, ERROR-088 / 095. **Status:** TODO

### SWM-04 - What the sender spends per recipient
- **Steps:** send 100 MB to one receiver on a router; capture sender CPU for (a) the normal build, (b) a debug build with per-chunk hashing timed, (c) a debug build with the FSEC layer disabled (test only, never shipped). Repeat the normal run to 3 receivers.
- **Pass (measurement):** the share of sender CPU in hashing, in the second encryption layer and in the file read, and how it scales with receivers. Decides whether plan stage 0 (hash once) is worth it.
- **Source:** plan section 2.1, 6.1, 7.1. **Status:** TODO

### SWM-05 - Swarm vs direct fan-out (only after P5 exists)
- **Pass:** the swarm finishes a 6-member send faster than `SWM-01`'s direct fan-out on the same network, a removed member stops being served within one request window, and the sender can leave after its two first hops. Not runnable yet.
- **Status:** BLOCKED (nothing built)

### TV2-01 - v2 pull transfer vs v1 push on one pair (only after P4)
- **Pass:** same 500 MB file, same pair, v2 within 5 % of v1 throughput, and a resume from a random position works. Not runnable yet.
- **Status:** BLOCKED (nothing built)

### TV2-02 - The EXP-001 follow-ups
- **Steps:** the three experiments EXP-001 listed and never recorded: 5 GHz router link; chunk-size sweep 64 / 128 / 256 KB; 2 and 4 real sockets (Android to Android) and Windows to phone.
- **Pass (measurement):** which of network / storage / CPU / TLS / hashing / protocol limits each pair (AGENTS section 23). Decides whether any transport work is justified.
- **Source:** EXP-001, plan section 7.2. **Status:** TODO

## 4v. Group secret membership, settings and swarm checks (design only, `docs/transfer/GROUP-SWARM-DESIGN.md`, 2026-10-03)

Nothing is built. These are the checks the design in that document will owe once each phase exists; `SWM-01`..`SWM-03` (section 4u) run now and decide whether the swarm phases happen. Record results in the Results log.

**Update 2026-10-04:** the owner made reliability the goal, so `SWM-01`..`SWM-05` no longer decide whether the swarm is built; they tune it (`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2). The swarm checks of the implementation plan are in section 4w.

**Update 2026-10-04 (b):** the owner accepted the group id + secret (D1) and wants every member to receive regardless of pairing. Plan track GM (section 7B) now owes the `GSEC`/`GSET` checks below, with two changes:
- **Invites are links, not QR codes, in the first version** (plan O-14). QR waits for DR4.
- **The `RekeyBundle` became a signed rotation notice plus a secret handover over live sessions** (plan 5.7). Read "RekeyBundle" in `GSEC-02` and `GSEC-04` that way.

More membership checks are in section 4x (`GMB-01`..`GMB-14`).

### GSEC-01 - Join with the code, never paired (G1)
- **Setup:** 3 devices (two Android, one Windows) that have **never been paired** with each other; device A creates a group and shows the invite.
- **Steps:** B joins by QR, C joins by pasted link; both appear as members; send a group text, a file and start a group call from each.
- **Pass:** chat, file and call work between all three with no pairing; `1:1` chat to a group-only peer is **not** offered; the log shows `GS_` proofs with the group id only (no secret, no key material).
- **Source:** design 1.3, 1.5. **Status:** TODO (nothing built)

### GSEC-02 - Wrong or stale code refused (G1)
- **Steps:** a device with a wrong secret dials a member; a device with the previous epoch's secret dials after a rotation.
- **Pass:** both are refused with no group data exchanged; the stale-epoch device that is **not** on the roster gets no `RekeyBundle`.
- **Source:** design 1.3, 1.6. **Status:** TODO (built in GM-6, needs device run)

### GSEC-03 - Relay (man-in-the-middle) fails the proof (G1, mostly a unit/loopback test)
- **Pass:** a relay holding two TLS sessions between a member and a joiner cannot make `GS_PROOF` verify; replayed and reflected proofs fail.
- **Source:** design 1.3. **Status:** TODO (nothing built)

### GSEC-04 - Removal rotates the code (G1)
- **Setup:** group of 4, one member offline.
- **Steps:** admin removes member X and the app rotates the code; the offline member reconnects later through *another member*; X tries to rejoin with the old code.
- **Pass:** the offline member receives the `RekeyBundle` from the other member and is back in; X is refused and gets nothing sent after the removal; X keeps what it had already received (documented, not a failure).
- **Source:** design 1.6. **Status:** TODO (built in GM-6, needs device run)

### GSEC-05 - Join policy (G1, G2)
- **Steps:** with "admin approves", join from a new device with the code and no admin online, then bring an admin online; with "open with code", repeat.
- **Pass:** approve mode: the joiner stays `PENDING` and read-only until approval; open mode: the admin device signs the cert automatically when it is online; a leaked code alone admits nobody in approve mode.
- **Source:** design 1.4, 2.1. **Status:** TODO (nothing built)

### GSEC-06 - Group beacon in the discovery record (G1, optional)
- **Pass:** the 8-byte rotating tag appears and is matched on every discovery source (NSD, JmDNS, sweep) without the empty-TXT symptom; two devices not in the group cannot link a device to the group for more than ten minutes.
- **Source:** design 1.7. **Status:** TODO (nothing built)

### GSEC-07 - Old version meets a secret group (G1)
- **Pass:** a device without the capability sees "update Flash on that device" and no crash; existing 1:1 and legacy-group behaviour is unchanged (regression of `OLD-` tests).
- **Source:** design 1.4. **Status:** TODO (nothing built)

### GSEC-08 - Gate is evaluated per request (G1/G5)
- **Steps:** while a member is mid-transfer or mid-call, an admin removes it.
- **Pass:** its next request is refused within one request window and the call leg is dropped (this is the gap the design closes; the current gap is documented in AGENTS section 29).
- **Source:** design 1.5. **Status:** TODO (nothing built)

### GSET-01..GSET-04 - Group settings (G2)
- **GSET-01:** an admin changes a signed setting (join policy, serving allowed); every online member shows it within a few seconds; an offline member shows it after reconnecting; an older `settingsVersion` never overwrites a newer one. **Status:** TODO (GM-9 built and unit-tested in `GroupSettingsTest`, pending devices)
- **GSET-02:** a non-admin cannot change a signed setting (the control is hidden **and** a forged update is rejected). **Status:** TODO (GM-9 built and unit-tested in `GroupSettingsTest`, pending devices)
- **GSET-03:** device-local preferences (serve on/off, Wi-Fi-only, battery threshold, keep-available time) persist across restart and never appear in any frame. **Status:** TODO (GM-9 built and unit-tested in `GroupSettingsTest`, pending devices)
- **GSET-04:** "Change group code" shows the new invite, the old invite stops working, all online members stay connected. **Status:** TODO (built in GM-6, needs device run)

### SWM-06 - "Already have it" (G3)
- **Steps:** member A sends file F to the group; member B deletes nothing and later member C sends the same bytes.
- **Pass:** B shows "already on this device" and downloads nothing; the sent bytes counter on C shows no transfer to B; a different file with the same name is **not** treated as a duplicate.
- **Source:** design 3. **Status:** TODO (nothing built)

### SWM-07 - Availability and "You can go offline now" (G6)
- **Pass:** the sender sees the message only when every piece is held by at least one other online device; turning the sender off then does not stop the others from finishing; a stuck state appears when the last holder of a piece leaves and clears when it returns.
- **Source:** design 4.8. **Status:** TODO (SW-11 built and unit-tested in `FlashSwarmUiMathTest`, pending devices)

### SWM-08 - Cross-group privacy (G3/G5)
- **Pass:** a device in groups G1 and G2 that holds file X (announced only in G1) answers a G2 member's request for X's root with `REJECT(UNKNOWN)`.
- **Source:** design 3. **Status:** TODO (SW-4/SW-8 built and unit-tested in `SwarmEngineStepTest`, pending devices)

## 4w. Group swarm reliability checks (plan only, `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md`, 2026-10-04)

Nothing is built. Each check names the plan phase after which it can run. Common setup unless a check says otherwise:
- a v2 group (`g2-`) of the devices named, every device on the same build with the swarm switch on (it applies after a restart);
- capture logcat / the desktop log from **before** the send on every device, filtered on the `SWARM`, `TRANSFER` and `SERVICE` tags.

Record results in the Results log (AGENTS 35).

### SWM-09 - Half sent, sender goes offline, the others sync, the sender returns (SW-8; the owner's scenario)
- **Setup:** 4 devices, A = sender, B, C, D = members (at least one Windows); a 500 MB file on A.
- **Steps:**
  1. A sends the file to the group; B, C, D accept.
  2. When A shows about half delivered, turn A's Wi-Fi off.
  3. Wait until B, C, D stop moving; note each one's MB count.
  4. Wait 2 minutes, then turn A's Wi-Fi back on.
- **Pass:**
  - before waiting, B, C and D reach **the same** MB count (they exchanged the union);
  - each shows "Waiting for A…", and its log shows `SWARM: event=wait reason=WAITING_FOR_SENDER`;
  - **no** `event=request` lines appear while they wait;
  - after A returns, all three complete and the whole-file check passes;
  - A's logged upload total is at most about 1.2 x the file size.
- **Source:** plan 4.2, SIM-02, E-02. **Status:** TODO (SW-8 built and unit-tested in `SwarmInteropTest`, pending devices)

### SWM-10 - Sender offline before anyone got anything (SW-8)
- **Steps:** A sends to B and C, then goes offline (Wi-Fi off) before either accepts; B and C accept; after 2 minutes A comes back.
- **Pass:** B and C show "Waiting for A" and send no requests while waiting; both complete after A returns.
- **Source:** E-01, SIM-03. **Status:** TODO (SW-8 built and unit-tested in `SwarmEngineStepTest`, pending devices)

### SWM-11 - The sender's cancel stops it everywhere, including a member that was offline (SW-9)
- **Setup:** A, B, C, D; a 1 GB file.
- **Steps:**
  1. A sends. When B, C, D have some parts, turn D's Wi-Fi off.
  2. A taps Cancel on its bubble and confirms "Cancel for everyone".
  3. After 1 minute turn D's Wi-Fi on, with A still **offline** (only B and C online).
- **Pass:**
  - B and C show "Cancelled by A" within a few seconds and their partial files are gone;
  - D shows "Cancelled by A" after reconnecting, and **its log shows the tombstone applied before any `event=request`** for that file;
  - a member that had already completed keeps its file (O-2) and never serves it again for this message.
- **Source:** plan 4.3, E-39, SIM-08. **Status:** TODO (SW-9 built and unit-tested in `SwarmCancelLifecycleTest`, pending devices)

### SWM-12 - A receiver's own cancel affects only itself (SW-9)
- **Steps:** A sends to B and C; B taps Cancel on its bubble.
- **Pass:** B shows Cancelled and its partial file is gone; C completes; A still shows C delivered; A gets no cancel.
- **Source:** E-42. **Status:** TODO (SW-9 built and unit-tested in `SwarmCancelLifecycleTest`, pending devices)

### SWM-13 - The sender is killed or rebooted mid-send (SW-10)
- **Steps:**
  1. A (Android) sends a 1 GB file picked through the system picker.
  2. At about 30 %, force-stop Flash on A; restart it.
  3. Repeat with a reboot of A.
  4. Repeat after deleting the file on A (E-19), then pick the same file again (O-3).
- **Pass:**
  - after the restart and the reboot, A serves again without any user action, and B and C complete;
  - with the file deleted, B and C complete what the group holds, the rest ends "A's file is no longer available", and A sees
    "Pick the file again to keep sharing";
  - picking the same file resumes serving (`SOURCE_STATUS(RESTORED)` in A's log).
- **Source:** E-19, E-21, E-22. **Status:** TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices). **2026-10-06:** the restart path itself did not exist until ERROR-102 (item 1) and is now implemented and unit-tested; this test is the first device proof.

### SWM-14 - A receiver is killed, rebooted or updated mid-download (SW-10)
- **Steps:** while B downloads, force-stop B; restart; later reboot B; later install a newer build on B mid-download.
- **Pass:**
  - each time, B continues from its persisted parts: its log shows the restored piece count, not 0;
  - the whole-file check passes at the end;
  - no part is downloaded twice beyond what was not yet synced (at most one second's worth).
- **Source:** E-29, E-32, E-33, INV-4. **Status:** TODO (SW-10 built and unit-tested in `RoomSwarmStateStoreTest`, pending devices)

### SWM-15 - A member removed mid-download (SW-9)
- **Steps:** while D downloads from B and C, the owner removes D from the group.
- **Pass:**
  - D's next request is refused (`REJECT(NOT_MEMBER)` in B's or C's log);
  - D shows "You are no longer in this group" and its partial file is gone;
  - B and C never send D a piece after the removal.
- **Source:** E-34, SIM-07, INV-3. **Status:** TODO (SW-9 built and unit-tested in `SwarmHostLifecycleTest`, pending devices)

### SWM-16 - Mixed versions (SW-8)
- **Setup:** A, B on the swarm build; C on the previous release (no `sw1`).
- **Pass:**
  - C receives the file through today's direct push from A;
  - B never sends C a swarm frame (C's log has no unknown-frame warnings);
  - chat and calls between all three work as before.
- **Source:** E-07, SIM-14, R9. **Status:** TODO (SW-8 built and unit-tested in `ZeroChangeProofTest`, pending devices)

### SWM-17 - Android's six-hour service limit pauses, never cancels (SW-9)
- **Setup:** an Android 15+ phone as A (sender) or as a downloader, plus one other member.
- **Steps:**
  1. Run `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS com.transfer.flash`.
  2. Run `adb shell device_config put activity_manager data_sync_fgs_timeout_duration 120000` (2 minutes).
  3. Start a large group send and put Flash in the background.
- **Pass:**
  - the log shows `Foreground service timeout reached` and **no** cancel or tombstone;
  - the rows show "Paused by Android; continues automatically";
  - opening Flash resumes the transfer, and every member completes.
  - Record how a service declared `connectedDevice|dataSync` is treated (the platform page does not say).
- **After the test:** reset with `adb shell device_config delete activity_manager data_sync_fgs_timeout_duration` and
  `adb shell am compat reset FGS_INTRODUCE_TIME_LIMITS com.transfer.flash`.
- **Source:** ripple 3, E-30, INV-10, developer.android.com/develop/background-work/services/fg-service-timeout (checked
  2026-10-04). **Status:** TODO (SW-9 built and unit-tested in `TimeoutStopPlanTest`, pending devices)

### SWM-18 - Not enough space, then space freed (SW-10)
- **Steps:** fill B's storage until less than the file size is free; A sends; B accepts; then delete something on B to free space.
- **Pass:**
  - B shows "Needs X, Y free" and does not fail;
  - after space is freed (next app open or within 15 minutes), B continues and completes;
  - mid-download filling behaves the same.
- **Source:** E-23, E-24, SIM-18. **Status:** TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)

### SWM-19 - A corrupt holder is detected and avoided (SW-10, needs a debug build flag that flips a byte in served pieces)
- **Steps:** turn the flag on for C; A sends to B, C, D.
- **Pass:**
  - B and D log hash failures against C, then a ban after 3 strikes;
  - both complete and pass the whole-file check;
  - no corrupt piece is ever written.
- **Source:** E-13, E-14, SIM-06, INV-2. **Status:** TODO (SW-10 built and unit-tested in `StrikeBookTest`, pending devices)

### SWM-20 - Windows as sender and as holder, including a restart (SW-8)
- **Steps:** Windows sends to two phones, and is quit and reopened mid-send; then a phone sends and Windows is a holder that serves the other phone while the sender is offline.
- **Pass:** every transfer completes after the restart (desktop swarm rows survive it, unlike desktop 1:1 rows today); the Windows holder serves the phone.
- **Source:** ripple 16, E-22. **Status:** TODO (SW-8/SW-10 built and unit-tested in `JvmPieceStorageTest`, pending devices)

### SWM-21 - ECO device downloads but never serves (SW-8)
- **Pass:** a device in ECO completes its download and its log has no `event=serve` lines; other members never request from it (its SUMMARY says `servingEnabled=false`).
- **Source:** E-10, D9, SIM-13. **Status:** TODO (SW-8 built and unit-tested in `SwarmHostLifecycleTest`, pending devices)

### SWM-22 - Phone hotspot with client isolation (SW-10)
- **Setup:** A runs the hotspot; B, C, D are its clients.
- **Pass:** every member completes (from whichever device it can reach); turning A off mid-send and back on behaves as `SWM-09` for whatever the members could exchange; **no speed claim**; record the time in `logs/experiments.md`.
- **Source:** E-08, plan 1.2 (D11 superseded), FO-06. **Status:** TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)

### SWM-23 - Swarm off changes nothing (SW-8; run first)
- **Setup:** the swarm switch off on every device (and separately: the `sample:consumer` app, which never attaches).
- **Pass:** group sends, 1:1 sends, chat, calls and PTT behave exactly as on the previous build; no `caps` field in the HELLO log lines; no `SWARM` log lines.
- **Source:** R9, SW-8 task 13. **Status:** TODO (SW-8 built and unit-tested in `ZeroChangeProofTest`, pending devices)

### SWM-24 - What the sender sees (SW-11)
- **Pass:** "Delivered to k of n" counts correctly as members finish; "You can go offline now" appears only once every part is held by at least one other online device, and turning the sender off right after it appears does not stop the others from finishing.
- **Source:** plan 4.2, design 4.8, SW-11. **Status:** TODO (SW-11 built and unit-tested in `FlashSwarmUiMathTest`, pending devices)

### SWM-25 - The sender's file changed or deleted after half (SW-10)
- **Steps:** A sends; at about 40 % edit the file on A (or replace it with another file of the same name); separately repeat with deleting it.
- **Pass:** A never serves changed bytes (`SOURCE_STATUS(LOST, CHANGED)` in its log); members complete what the group holds and the rest ends with the named sentence; nobody ends with a file that fails the whole-file check.
- **Source:** E-19, E-20, SIM-20. **Status:** TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)

### SWM-26 - Long run with churn (SW-10)
- **Setup:** 6 devices (Android and Windows), a 2 GB file.
- **Steps:** while it spreads, switch random members' Wi-Fi off and on every minute or two for 20 minutes; include the sender twice.
- **Pass:** all complete; whole-file checks pass; no `Failed` row; note any Transsion `Hiber` lines (E-31) separately.
- **Source:** E-04, E-06, SIM-05. **Status:** TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)

### SWM-27 - A group call during a swarm (SW-10)
- **Steps:** start a group call among 3 members while a 1 GB group file spreads among them.
- **Pass:** call audio stays clear (no more dropouts than the same call without a transfer); the swarm logs the reduced slot count while the call is active and restores it after.
- **Source:** ripple 19, MEAS-*. **Status:** TODO (SW-10 built and unit-tested in `SwarmEngineStepTest`, pending devices)

### SWM-28 - Group send regression after the refactor (SW-1)
- **Pass:** on Android and on Windows, a file sent to a group of 3 arrives at both members exactly as on the previous build (bubbles, progress, accept, cancel from the sender's bubble).
- **Source:** SW-1. **Status:** TODO (SW-1 built and unit-tested in `GroupFileSenderTest`, pending devices)

### SWM-29 - Old and new builds after the seams (SW-2)
- **Pass:** between a device on the previous release and one on the SW-2 build: chat, a 1:1 file, a group file, a call and PTT all work; the new device's HELLO carries no `caps` while nothing is attached.
- **Source:** SW-2. **Status:** TODO (SW-2 built and unit-tested in `MagicFrameRouterTest`, pending devices)

### SW-4 Sans-IO Swarm Engine Verification (Unit & Property Tests)
- **Pass:** Pure state machine verified with table-driven tests (`SwarmEngineStepTest`, `PiecePickerTest`, `SourceSelectorTest`, `ServePolicyTest`, `RequestWindowTest`, `StrikeBookTest`, `WaitClassifierTest`), 1,000-seed property tests verifying all 7 invariants (`SwarmEnginePropertyTest`), and mutation checks on 4f (ServePolicy guards) and 4j (Cancel signature checks).
- **Source:** SW-4. **Status:** Unit & property verified (2026-10-04). No device checks claimed (device testing owed under SWM-06..SWM-27 in SW-7..SW-10).

### SW-6 Persistence Verification (Room Migration, DAO & State Store Tests)
- **Pass:** Schema version 7 (`SwarmContentEntity`, `SwarmTombstoneEntity`, `SwarmDao`), `STEP_6_7` in `FlashSchemaSteps`, `MIGRATION_6_7` in `FlashMigrations`, `FlashDatabaseJvmTest` round-trip and query verification, Room SQLite migration verification in `FlashJvmMigrationsTest`, and `RoomSwarmStateStoreTest` in `:core:engine`.
- **Source:** SW-6. **Status:** Unit verified (2026-10-04). No device checks claimed.

### SW-7 Storage I/O Verification (Unit & Functional Tests)
- **Pass:** `SourceHandle`, `PartialHandle`, `StorageFinalizeResult`, `PieceStorage` interfaces defined in `:core:swarm`; `JvmPieceStorage` and `AndroidPieceStorage` implemented in `:core:engine`. Positional reads, writes, sync, identity change detection, whole-file SHA-256 validation, name collision handling, and path traversal guards verified in `JvmPieceStorageTest`. Android SAF picker updated with `isPersistable`.
- **Source:** SW-7. **Status:** Unit & functional verified (2026-10-04). Device checks owed under SWM-13 (physical content-URI streaming and restart resumption on Android).

### SW-8 Driver and Host Integration Verification (Unit & Interop Tests)
- **Pass:** `SwarmDriver` actor, `FlashSwarm` facade, `SwarmHostBinding` with ports (`SwarmTransport`, `SwarmGroupContext`, `PieceStorage`, `SwarmStateStore`), `attachExternalRows` bridge in `RealFlashTransferRepository`, `attachSwarm` wiring in `FlashEngine`, `MagicFrameRouter` FSW1 routing, GM-5 `GroupGate` integration, `ZeroChangeProofTest` zero-overhead proof, and 3-peer multi-node swarm interop test in `SwarmInteropTest`.
- **Source:** SW-8. **Status:** Unit & interop verified (2026-10-04). Device checks owed under SWM-09, SWM-10, SWM-16, SWM-20, SWM-21, SWM-23.

### SW-9 Cancel Everywhere and Lifecycle Verification (Unit & Functional Tests)
- **Pass:** Cryptographic origin cancel propagation via signed `SwarmTombstone`, `CANCEL_ACK` tracking, `SUMMARY` tombstone gossip, delete-for-everyone integration, local recipient cancellation, removal/leave eviction, Android 15+ FGS timeout suspension without tombstones (`TimeoutStopPlanTest`), 15-minute background retention cleanup for expired records/orphaned `.part` files.
- **Source:** SW-9. **Status:** Unit & lifecycle verified (2026-10-04). Device checks owed under SWM-11, SWM-12, SWM-15, SWM-17.

### SW-10 Errors and Recovery Verification (Unit & Recovery Tests)
- **Pass:** Comprehensive recovery handling for all 7 `SwarmWaitReason` codes (`SwarmWaitReasonRecoveryTest`), wake-up wiring for network changes, peer sessions, incoming frames, background worker intervals, and storage space restoration. User-friendly sentence formatting in `TransferFailureText`, bad piece detection and peer banning in `StrikeBookTest`.
- **Source:** SW-10. **Status:** Unit & recovery verified (2026-10-05). Device checks owed under SWM-13, SWM-14, SWM-18, SWM-19, SWM-22, SWM-25, SWM-26, SWM-27.

### SW-11 UI & Availability Presentation Verification (Unit & Logic Tests)
- **Pass:** Component research doc `docs/ui/group-file-availability.md` (UI-055), mathematical calculations in `FlashSwarmUiMath` tested in `FlashSwarmUiMathTest`, receiver detail lines, sender "Delivered to k of n" and "You can go offline now", `FlashFileMessageCard` bubble badges, `FlashTransfersScreen` status lines (`FlashTransfersLogicTest`), notification origin cancel & detail lines, settings toggles for group file sharing in Android and Desktop hosts (`FlashSettingsLogicTest`).
- **Source:** SW-11. **Status:** Unit & logic verified (2026-10-05). Device checks owed under SWM-24, SWM-07.

### SWM-30 - Partial downloads survive an app restart (ERROR-102 item 2)
- **Steps:**
  1. B starts receiving a large file from A, then force-stop Flash on B at about 40 %.
  2. Restart Flash on B and open Transfers.
- **Pass:**
  - the `.part` file is still there (cleanup did not delete it) and the row shows the persisted progress, not 0;
  - B's log has `SWARM: restore done contents=1 ...` and `SWARM: restored root=... pieces=N/M`;
  - the download continues without any user action and completes with a matching file.
- **Source:** ERROR-102 items 1 and 2, plan R4, E-29. **Status:** TODO

### SWM-31 - The group's serve switches take effect (ERROR-102 item 3)
- **Steps:**
  1. In a group of three, B turns "serve to the group" off in this group's preferences.
  2. A sends a file; C starts, then B finishes.
  3. Repeat with an admin turning the signed group setting "swarm serving" off.
- **Pass:**
  - B (or everyone, for the signed setting) refuses to serve: the requester's log shows a `BUSY` reject and it takes the piece from another holder or waits;
  - turning the switch back on lets serving resume.
- **Source:** ERROR-102 item 3, GM-9, ADR-074. **Status:** TODO

### SWM-32 - ECO mode and calls throttle serving (ERROR-102 item 4)
- **Steps:**
  1. B is a holder. Switch B to ECO; C requests pieces from B.
  2. Back to STANDARD; start a group call involving B while C downloads.
- **Pass:**
  - within about 2 s of the mode change B rejects requests as `BUSY` (ECO) and resumes after;
  - during the call B's serve windows shrink (fewer slots) and recover after the call.
- **Source:** ERROR-102 item 4. **Status:** TODO

### SWM-33 - A cancel while the last piece is in flight, and a paired non-member (ERROR-102 items 6 and 8)
- **Steps:**
  1. A sends a small file; B starts; A deletes the message for everyone while B is on the last piece (repeat 5 times).
  2. Pair D with A and B but do not add D to the group; connect D while the group has swarm content.
- **Pass:**
  - B never ends with a completed file after the delete-for-everyone, and no `.part` file is left;
  - D's log shows no swarm `Summary`, and A's log shows `peer up peer=<D> deniedGroups=1`.
- **Source:** ERROR-102 items 6 and 8. **Status:** TODO

---

## 4x. Group membership by id + secret, implementation plan (`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` section 7B)

GM-1 (cryptographic primitives), GM-2 (invite format & secret storage), GM-3 (proof exchange, `gs1`, membership frames), and GM-5 (group gate integration) are built and unit-tested. On 2026-10-04 the owner made two decisions:
- **every member of a group receives files, whether or not it is paired;**
- **groups get a group id + secret** (D1).

Track GM owes these checks, in addition to `GSEC-01`..`GSEC-08` and `GSET-01`..`GSET-04` (section 4v). Run them in the order of
plan GM-11. Record results in the Results log.

### GMB-01 - Files reach a member that is not paired with the sender (GM-5)
- **Setup:**
  - an existing v2 group of 3: A (the owner) is paired with B and with C;
  - **B and C are not paired with each other** (C is vouched to B).

  This works on today's groups; no secret is needed.
- **Steps:**
  1. B sends a photo and a 200 MB file to the group.
  2. C sends one back.
- **Pass:**
  - C receives B's files and B receives C's (bubbles, progress, Accept as configured).
  - Before GM-5 the log showed C left out of B's recipients. After GM-5 it does not.
  - Legacy `g-` groups behave exactly as before.
- **Source:** plan 5.7, GM-5, owner decision 1.3. **Status:** TODO (nothing built)

### GMB-02 - Group-only peers stay out of 1:1 (GM-5, GINV-8)
- **Steps:** with B and C from `GMB-01` (or a member that joined by invite):
  1. look for C in B's contacts;
  2. try a 1:1 message, a 1:1 call and PTT between them. Sending the 1:1 frame may need a debug build.
- **Pass:**
  - C is not a 1:1 contact and not a PTT recipient;
  - a 1:1 call is refused;
  - a forged 1:1 frame is dropped (a `CHAT` log line);
  - group chat, calls and files between them keep working.
- **Source:** plan ripple 36, D4. **Status:** TODO (nothing built)

### GMB-03 - A live group call leg ends when its member is removed (GM-5 task 6)
- **Steps:**
  1. Start a group call with 3 devices.
  2. An admin removes one participant during the call.
- **Pass:**
  - On every other device, the removed device's legs end within a few seconds, and the log names the reason "removed".
  - The others stay connected.

  This is the same check as the call part of `GSEC-08`.
- **Source:** plan ripple 46, AGENTS 29 gap. **Status:** TODO (nothing built)

### GMB-04 - Invite link round trip (GM-2, GM-4, GM-8, GM-10)
- **Steps:**
  1. On Android, A shares an invite to another app (for example a messaging app or notes). Tap it on Android phone D.
  2. On Windows, A copies the invite to the clipboard. Paste it on Windows E.
  3. Try a truncated link and an edited link.
- **Pass:**
  - D and E both open the join confirmation, with the right group name and inviter.
  - D and E reach A through the address hints (the log shows the hint dial). With A gone, they reach another discovered member.
  - A broken link shows "This invite is not valid" and stores nothing.
- **Source:** plan GM-2, GM-4, GM-8, GM-10, M-01, M-03. **Status:** TODO (GM-10 UI and deep link handling built & verified; ready for physical device test in GM-11)

### GMB-05 - Impostor at the inviter's id (GM-4, ripple 37)
- **Setup:** a debug device configured to answer as the inviter's device id with its own key, or a second install restored under
  the same id.
- **Pass:**
  - The joiner refuses it at TLS and shows M-04.
  - Afterwards, the trust store holds no TOFU pin for that id.
  - The real inviter works later.
- **Source:** plan ripple 37, named-dial TOFU trap. **Status:** TODO (nothing built)

### GMB-06 - Join while no admin is online, approved later by a co-owner (GM-4)
- **Setup:** a group with owner O, co-owner K (ADR-063) and member M. O and K are offline.
- **Steps:**
  1. Newcomer N joins with M's invite.
  2. Later, K comes online. O stays offline.
- **Pass:**
  - N shows "Waiting for an admin…".
  - M forwards the request to K when K appears.
  - K gets one notification and approves.
  - N becomes a member on M and K, and later on O.
  - N chats, calls and sends and receives files with all of them, with no pairing at all.
- **Source:** plan GM-4, M-07. **Status:** TODO (nothing built)

### GMB-07 - A member offline during a removal gets the new code from a non-admin (GM-6)
- **Setup:** a group of 4. Member Y is offline. The admin removes X.
- **Steps:**
  1. The admin goes offline.
  2. Y comes back and meets only member Z.
- **Pass:**
  - Y receives the rotation notice and the secret from Z. The log shows `GsSecret` with no secret bytes.
  - Y's chat never stopped (O-13).
  - An invite Y shares afterwards works, and an old invite is refused (M-06).
  - X never receives the new secret.
- **Source:** plan 5.7 rotation without sealing, GM-6. **Status:** TODO (built in GM-6, needs device run)

### GMB-08 - Two admins rotate at the same time (GM-6)
- **Steps:**
  1. Put the owner and a co-owner on separate network segments.
  2. Each removes a different member, or presses "Change group code".
  3. Reconnect them.
- **Pass:**
  - Every device ends on the same epoch and secret: the smaller `rotationId` wins, and the losing admin rotates once more.
  - Both removed members stay out.
  - Invites made after the merge work.
- **Source:** plan GM-6 task 5, M-16. **Status:** TODO (built in GM-6, needs device run)

### GMB-09 - An existing v2 group gets a secret (GM-7, O-11)
- **Setup:** a v2 group created on today's build. Every member is updated except one.
- **Pass:**
  - After the owner or a co-owner opens the app on the new build, "Invite people" becomes available to every member on the new
    build.
  - The member on the old build keeps chatting, calling and receiving files as before.
- **Source:** plan GM-7. **Status:** TODO (GM-7 built, unit/host-verified in GroupUpgradeTest; owed on physical devices)

### GMB-10 - A member on an old build trusts a member who joined by invite (GM-4 task 10, ripple 40)
- **Pass:**
  - The old-build member shows the new member in the roster, and exchanges group chat and calls with it.
  - Its log shows the vouch installed, and no `owner-not-paired` or `sender-not-member` refusal.
- **Source:** plan ripple 40, M-21. **Status:** TODO (nothing built)

### GMB-11 - The secret never appears in logs (GM-2, GINV-1)
- **Steps:**
  1. Run a full flow: create, invite, join, remove, rotate, handover.
  2. Copy the secret part of the invite.
  3. Collect `adb logcat` from every Android device, and the desktop log files.
- **Pass:**
  - Neither the secret (base64url or hex) nor the invite link appears in any log, crash report or notification.
  - `groupId` and `epoch` may appear.
- **Source:** plan GINV-1, AGENTS 24. **Status:** TODO (nothing built)

### GMB-12 - A removed device asks to rejoin under "open" (GM-4, GINV-5)
- **Steps:**
  1. Set the join policy to "open".
  2. Remove X.
  3. Give X a **new** invite, and X joins.
- **Pass:**
  - X is **not** admitted automatically.
  - Admins see the request, marked as previously removed.
  - X is admitted only after an explicit approval.
- **Source:** plan GINV-5, M-14. **Status:** TODO (nothing built)

### GMB-13 - Secret store lost on one device (GM-6, M-17)
- **Steps:** clear the app's key material the way a reinstall with restore, or a Keystore reset, would. GM-2 writes the exact
  procedure once the storage is known.
- **Pass:**
  - The device is still a member: chat, calls and files work (O-13).
  - For invites, it shows "Getting the group code…".
  - It gets the secret back from another member.
- **Source:** plan M-17. **Status:** TODO (built in GM-6, needs device run)

### GMB-14 - Invite sent inside a 1:1 Flash chat (GM-10, O-14, optional)
- **Pass:**
  - A `flash://g/...` link in a received 1:1 message shows a "Join" action, which opens the same confirmation as `GMB-04`.
  - A malformed link shows no action.
- **Source:** plan O-14, GM-10. **Status:** TODO (built in GM-10 via FlashInlineInviteCard; ready for physical device test in GM-11)

## 4ze. Swarm non-member frames and row naming (ERROR-110, ERROR-111, 2026-10-06)

### SWM-43 - A device outside the group is not counted
- **Setup:** swarm switch on, group of A (origin) and B; device C is paired with A but not in the group (or was removed from it).
- **Steps:** A sends a file to the group; leave C connected to A.
- **Pass:** A's log shows `dropped non-member ...` frames from C at most once a second, C is not in A's delivered set (the "safe to leave" line counts only B), and C gets no file.
- **Source:** ERROR-110.
- **Status:** TODO

### SWM-44 - The Transfers row names the group
- **Setup:** a group named "Family" with the swarm switch on.
- **Steps:** send a file to it, open the Transfers screen on both phones.
- **Pass:** the row on both says "Family" (never an id such as `g2-...`); after renaming the group and sending again the new name shows.
- **Source:** ERROR-111.
- **Status:** TODO

## 4zd. Swarm origin source lost (ERROR-109, 2026-10-06)

### SWM-40 - A deleted source is reported, not silent
- **Setup:** swarm switch on, origin A and members B, C connected, a 200 MB file on A.
- **Steps:** A sends; while B downloads, delete the file on A (file manager) before B finishes.
- **Pass:** B's log shows `Reject` with GONE (no repeated unanswered requests every few seconds), then B's row shows a "waiting for others / sender" line instead of a stuck percentage; C (which has all pieces) completes B's download if C finished first; A's log has `source status` signed once, not once per piece.
- **Source:** ERROR-109.
- **Status:** TODO

### SWM-41 - A member that was offline hears the loss
- **Setup:** as SWM-40; phone C offline during the delete.
- **Steps:** bring C online.
- **Pass:** C's row shows the origin cannot serve, with no request loop to A.
- **Source:** ERROR-109.
- **Status:** TODO

### SWM-42 - Restart keeps the loss
- **Setup:** after SWM-40, force-stop and reopen the app on A.
- **Pass:** A does not offer the lost pieces (`Summary` not complete in the log), B/C do not request them from A.
- **Source:** ERROR-109.
- **Status:** TODO

## 4zc. Swarm file offers and catch-up (ERROR-108, ADR-081, 2026-10-06)

### SWM-36 - A live swarm offer is accepted and the bubble follows the transfer
- **Setup:** swarm switch on for three phones in a v2 group, all connected, a 20 MB file.
- **Steps:** phone A sends the file. On phone B watch the bubble and the Transfers row; tap Pause then Resume on the bubble.
- **Pass:** B's bubble shows progress that matches the Transfers row; Pause / Resume on the bubble take effect on the row; the log has `SWARM announce root=... origin=false` on B and no `file offer dropped` line.
- **Source:** ERROR-108.
- **Status:** TODO

### SWM-37 - A member that was offline gets the file offer
- **Setup:** as SWM-36, phone C switched to airplane mode before A sends.
- **Steps:** A sends; B downloads; C comes back online.
- **Pass:** within about 10 s of C reconnecting, C shows a file bubble named like the file (not empty), and it downloads from A or B; the log shows the catch-up and `SWARM announce ... origin=false` on C.
- **Source:** ERROR-108, plan row 23.
- **Status:** TODO

### SWM-38 - A member relays the offer while the author is offline
- **Setup:** as SWM-37, but A leaves the network after B has finished; C returns.
- **Pass:** C shows the file bubble and downloads it from B alone; the bubble names A as the sender.
- **Source:** ERROR-108.
- **Status:** TODO

### SWM-39 - Upgrade keeps old rows
- **Setup:** an install with group chat history from the previous build.
- **Steps:** install this build over it and open a group.
- **Pass:** the app opens, old messages are intact, a new file send works (migration v10 -> v11).
- **Source:** ADR-081.
- **Status:** TODO

## 4zb. Swarm first contact (ERROR-107, 2026-10-06)

### SWM-35 - A fresh swarm send starts without a reconnect
- **Setup:** swarm switch on for the origin and two members, all connected, a v2 group, a file of 20 MB or more.
- **Steps:** origin sends the file; do not touch anything on the members; watch the member rows.
- **Pass:** each member row leaves 0 % within about 3 s and finishes; the log shows the member's `SWARM` request lines right after the announce line, with no reconnect and no second announce. Repeat once with the origin's Wi-Fi switched on a second after the send (a reconnect is not what starts it).
- **Source:** ERROR-107.
- **Status:** TODO

## 4zf. Join by invite and members-may-add (ERROR-112, ERROR-113, ERROR-114, 2026-10-06)

Capture before you start: export the logs on every device (the new `GROUP` lines show the proof result and each join step).

### GJOIN-01 - A device added by a plain member joins
- **Setup:** three or more devices in one v2 group; the admin turns "Members may add" on in the group settings. The device to add is paired with the member who adds it.
- **Steps:** on a plain member (not the admin) use Add members and pick the new device. Wait 30 s on the new device.
- **Pass:** the new device lists the group with its name and the full member list, with no `Group cert dropped ... reason=issuer` line in its log; the admin and the others show it as a member. With "Members may add" OFF, an add attempt by a plain member is refused.
- **Source:** ERROR-112.
- **Status:** TODO

### GJOIN-02 - Pasting an invite while already connected to the inviter
- **Setup:** desktop paired with and connected to the group's admin; another member's phone nearby.
- **Steps:** the admin copies the invite link; paste it on the desktop and press Join.
- **Pass:** the dialog closes at once; the desktop shows a message ("Connecting to inviter..." or the waiting-for-admin sentence) and does NOT open an empty chat titled with the group id; within 10 s the admin sees the request (members sheet and notification); the desktop log has an `Invite proof: ... result=` line and a join-request-sent line. After the admin approves, the group appears on the desktop with its name and members. Repeat with the joiner on a phone.
- **Source:** ERROR-113, ERROR-114.
- **Status:** TODO

### GJOIN-03 - Approval while the joiner is disconnected
- **Setup:** as GJOIN-02, request sent and visible to the admin.
- **Steps:** switch the desktop's Wi-Fi off; the admin approves; wait a minute; switch Wi-Fi on.
- **Pass:** the admin log shows the approval with the bundle not delivered; once the link is back the desktop joins within 30 s without the admin doing anything; no second notification appears on the admin. If it does NOT join, keep both logs: that is the unproven 09:34 case of ERROR-113.
- **Source:** ERROR-113.
- **Status:** TODO

### GJOIN-04 - An old invite link says it is out of date
- **Setup:** admin and a joiner that is not in the group; both online and connected.
- **Steps:** the admin copies an invite link, then uses "Change group code" (or removes a member). The joiner opens the OLD link and presses Join. Then the admin copies a fresh link and the joiner opens that.
- **Pass:** with the old link the joiner is told "This invite was replaced. Ask for a new one." within about 25 s (not "Connecting..." for ever), the admin gets no request, and the joiner log has `Invite can not complete: ... state=STALE`. With the new link the request reaches the admin and the join completes as in GJOIN-02.
- **Source:** ERROR-115.
- **Status:** TODO

### GJOIN-05 - The invite dialog names the inviter, the admin is warned about strangers
- **Setup:** one joiner that IS paired with the inviter and one that is NOT; an admin that is paired with only the first.
- **Steps:** open the same link on both joiners and read the dialog; both request to join; the admin opens the members sheet.
- **Pass:** the paired joiner's dialog says "You were invited by <the inviter's name>..."; the unpaired one says "You were invited to join ..." with no name. In the admin's sheet the paired requester shows "Requested to join - <time>" under the admin's own name for it; the unpaired one shows "Not paired with you, name chosen by the device - <time>" in the accent colour.
- **Source:** ADR-083.
- **Status:** TODO

### GJOIN-06 - A wrong device at the hinted address is turned away
- **Setup:** a joiner and an admin on one Wi-Fi; a third device (not in the group) that can take the admin's old IP address (stop the admin, give its address to the third device, or edit the link's hint to the third device's `ip:port`).
- **Steps:** the joiner opens the link and presses Join while the admin is offline.
- **Pass:** the joiner does NOT connect to the third device (log: certificate rejection / identity mismatch for the inviter id, no `Invite proof` with it), nothing is sent to it, and the status falls back to "Waiting for a member of <group> to be nearby." after 30 s. This is the case that was only verified by reading the code (the vouch installs the inviter's pin and the named dial checks it).
- **Source:** ADR-083, ERROR-115 notes.
- **Status:** TODO

## 4za. Ink launch splash (UI-056, ADR-080, 2026-10-06)

Built and unit-tested (`FlashLaunchSplashTest` 21/21). Desktop frames were rendered off-screen and checked by eye. Not device-verified.

### SPLASH-01 - Android cold start, light and dark
- **Steps:** Force-stop Flash, then open it with the system in light mode. Repeat in dark mode.
- **Pass:** The system splash colour matches the system theme. It fades into the Ink ground with no white or black flash. "Flash" is written stroke by stroke, then the bolt draws, fills and ripples, and the app fades in after about 3.5 s.
- **Source:** UI-056. **Status:** TODO

### SPLASH-02 - No replay on a warm return
- **Steps:** Open Flash, press Home, and reopen it. Also reopen it while the foreground service keeps the process alive after the task is swiped away.
- **Pass:** The splash does not play either time.
- **Source:** UI-056 (the gate is per process). **Status:** TODO

### SPLASH-03 - Rotation mid-splash
- **Steps:** Rotate the phone at about 1 s into the splash.
- **Pass:** The animation continues from the same moment. It does not restart and does not end early.
- **Source:** UI-056. **Status:** TODO

### SPLASH-04 - Setting off (Android and desktop)
- **Steps:** Turn off Settings > Launch animation, then cold start.
- **Pass:** No Ink splash appears. Android shows only the system splash until the engine is ready.
- **Source:** UI-056. **Status:** TODO

### SPLASH-05 - Answering a call or pressing PTT skips the splash
- **Steps:** With Flash not running, answer an incoming call from the notification. Then do the same with a PTT press.
- **Pass:** The call or PTT screen appears with no splash.
- **Source:** UI-056. **Status:** TODO

### SPLASH-06 - Slow device ceiling (Belfone)
- **Steps:** Cold start on the Belfone SCP810.
- **Pass:** The splash ends within 6 s even if the engine is still booting (ERROR-034 ceiling).
- **Source:** UI-056, ERROR-034. **Status:** TODO

### SPLASH-07 - Reduced motion
- **Steps:** Turn on Remove animations (or the LOW tier), then cold start.
- **Pass:** The finished frame shows still for about 0.8 s.
- **Source:** UI-056. **Status:** TODO

### SPLASH-08 - Desktop start, tray reopen, dynamic accent
- **Steps:** Start the Windows app. Hide it to the tray and reopen it. Switch the accent and cold start again.
- **Pass:** The splash plays once at start and not on the tray reopen. Clicks during it do nothing. The ink and bolt take the accent colour while the ground stays the same.
- **Source:** UI-056. **Status:** TODO

## 4y. UI polish and feature flows (`docs/ui/UI-POLISH-AND-FEATURE-ROADMAP-PLAN.md`, 2026-10-06)

Built and unit-tested in commonTest across `:ui:chat`, `:ui:callui`, and platform-shims. Device tests owed:

### UIP-01 — Native In-App Forwarding Sheet
- **Setup:** A conversation with messages on an Android phone or Desktop.
- **Steps:** Long-press or right-click any message, select "Forward". Verify search, selection up to 5 recipients (direct chats, groups, nearby peers), quote preview, and sending.
- **Pass:** Messages forward cleanly to all selected targets with quoted attribution.
- **Source:** Task 3.1. **Status:** TODO

### UIP-02 — Composer Attachment Pre-Send Staging Tray
- **Setup:** Chat conversation screen.
- **Steps:** Pick multiple files/images or capture a camera photo.
- **Pass:** Attachments appear in horizontal staging tray above composer; individual items can be removed; caption can be typed; clicking send dispatches files and text together.
- **Source:** Task 3.2. **Status:** TODO

### UIP-03 — Per-Conversation Shared Content Viewer
- **Setup:** Conversation with existing media, files, voice notes, and links.
- **Steps:** Open peer details / group members sheet -> tap "Shared Media & Files".
- **Pass:** 4 tabs display (Media 3-column grid, Files with extension badges, Audio with inline play, Links with jump-to-chat).
- **Source:** Task 3.3. **Status:** TODO

### UIP-04 — Pinned Messages Banner
- **Setup:** Conversation screen.
- **Steps:** Long-press message -> Pin. Tap pinned banner.
- **Pass:** Slim pinned banner renders below header; tapping smoothly scrolls to target message with 700ms pulse glow highlight; unpinning clears banner.
- **Source:** Task 3.4. **Status:** TODO

### UIP-05 — Real-Time Audio Speaking Ripple in Calling UI
- **Setup:** 1:1 and group audio/video call between two devices.
- **Steps:** Speak into the microphone and pause.
- **Pass:** Call avatar ripples dynamically with speech volume (silence rests at 1.0f..1.06f, speech expands to 1.22f); active speaker in group video displays pulsing accent border ring.
- **Source:** Task 3.5. **Status:** TODO

### UIP-06 — Swarm Transfer Block Availability Grid Map
- **Setup:** Active or completed swarm file transfer in a group.
- **Steps:** Tap "Show piece map" on file card or inspect in Adaptive Detail Pane.
- **Pass:** Micro-block matrix displays verified, in-flight downloading, peer available, and missing blocks accurately.
- **Source:** Task 3.6. **Status:** TODO

### UIP-07 — Voice Recording Gesture Physics
- **Setup:** Chat composer on phone.
- **Steps:** Hold mic button. Slide left toward trash; release past 100dp. Next recording, slide up to lock hands-free.
- **Pass:** Left-slide shows rubber-band resistance and morphs into trash can; upward slide locks into hands-free bar with pause/resume and send; red dot pulses.
- **Source:** Task 2.3. **Status:** TODO

### UIP-08 — Adaptive Detail Panes Redesign
- **Setup:** Desktop or tablet in expanded two-pane layout.
- **Steps:** Select an active or completed transfer and a peer.
- **Pass:** Modern cards display with category color badge, animated progress bar with shimmer, SHA-256 verification shield, and action dock.
- **Source:** Task 1.1. **Status:** TODO

### UIP-09 — Transfers Screen Refinements
- **Setup:** Transfers screen with multiple completed, failed, and cancelled transfers.
- **Steps:** Verify section separation. Tap "Clear history". Test bulk pause/resume.
- **Pass:** Cancelled transfers are in muted section without retry buttons; "Clear history" removes completed records; bulk pause/resume operates on all active transfers.
- **Source:** Task 1.2. **Status:** TODO

### UIP-10 — Bottom Nav Auto-Hide & Motion Physics
- **Setup:** Phone running chat list or transfers list.
- **Steps:** Scroll down quickly, then scroll up. Switch tabs. Reselect current tab.
- **Pass:** Bottom nav capsule hides smoothly on rapid downward scroll and re-enters on scroll stop / upward scroll; tab hop tilts ±5°; reselecting active tab emits pulse ring.
- **Source:** Tasks 1.4 & 2.5. **Status:** TODO

### Corrections of 2026-10-06 to the section 4y tests above (ERROR-116)
UIP-02 says a caption is sent together with the files: **obsolete in part**, captions are not built (ADR-085); the text goes as a separate message after the files. UIP-04 now covers several pins that survive leaving the chat (see UIP-12). UIP-05 now means the received audio level, see UIP-13. UIP-06 now reads real swarm state, see UIP-11.

### UIP-11 - Swarm piece map shows real state and appears only for a swarm
- **Setup:** A group of 3 devices with swarm on; one sends a 200 MB file. A second chat with a plain 1:1 file transfer.
- **Steps:** On a receiver, open the file card and the detail pane during the download and after it.
- **Pass:** The map appears only for the group file; blocks go from missing to available-on-peers to in-flight to verified as pieces arrive, in no fixed order; the holder count is the number of connected members that really have pieces; the 1:1 transfer shows no map; a finished file is all verified.
- **Source:** ERROR-116, `PieceBlocks`. **Status:** TODO

### UIP-12 - Pins are stored, several, and device-local
- **Setup:** A chat with 5 or more messages, on a phone and on the Windows app.
- **Steps:** Pin 3 messages. Tap the banner repeatedly. Leave the chat, reopen it, then restart the app. Unpin one from the banner. Check the other device.
- **Pass:** The banner reads "Pinned message 1 of 3" and each tap scrolls to the next and pulses it; the pins are still there after leaving and after a restart; unpinning removes only that one; the toast says "Pinned on this device"; the other device shows no pin; deleting the conversation and recreating it shows none.
- **Source:** ERROR-116, ADR-084. **Status:** TODO

### UIP-13 - 1:1 call ripple follows the peer's voice
- **Setup:** A 1:1 voice call between two devices.
- **Steps:** The peer stays silent with the mic on for 10 s, then speaks, then mutes.
- **Pass:** No speaking glow while the peer is silent; the glow appears within about one stats interval (up to 2 to 4 s) of speech and fades after; no glow while muted. Note the lag.
- **Source:** ERROR-116, `FlashCallStats.peerAudioLevel`. **Status:** TODO

### UIP-14 - Camera capture asks for permission and says when it fails
- **Setup:** Android phone with the CAMERA permission denied (App info, Permissions).
- **Steps:** Chat, Attach, Camera. Deny the prompt. Repeat and allow it, take a photo. Then disable the camera app if the phone allows it and retry.
- **Pass:** The system prompt appears; denial shows "Camera permission is required to take a photo"; allowing opens the camera and the photo lands in the staging tray; with no camera app the toast says so. Nothing happens silently. On Windows the Camera tile is not in the Attach sheet.
- **Source:** ERROR-116. **Status:** TODO

### UIP-15 - Forward to several chats and to a group
- **Setup:** Android phone, 3 direct chats and 1 group; a message in a fourth chat.
- **Steps:** Forward the message to two direct chats and the group; repeat on Windows.
- **Pass:** The text arrives once in each target (not repeated in one); the open chat does not move; the group target is a group message, not a pairing prompt; Windows forwards too.
- **Source:** ERROR-116, `sendTextTo`. **Status:** TODO

### UIP-16 - Settings search and reset; Transfers "Clear history"
- **Setup:** Settings screen with a few values changed; Transfers with finished and failed rows.
- **Steps:** Type "battery", then "videos", then nonsense. Reset to defaults. On Transfers, tap Clear history.
- **Pass:** Search shows only matching sections and rows and says when nothing matches; Reset is disabled at defaults and restores them when pressed; Clear history removes completed rows (also finished swarm rows), keeps Failed and running ones, and received files and chat bubbles remain.
- **Source:** ERROR-116. **Status:** TODO

### UIP-17 - Link tap opens the link
- **Setup:** A chat with an https link in a message.
- **Steps:** Shared content, Links tab, tap the link.
- **Pass:** The browser opens the link (no share chooser); a flash:// link that nothing handles shows "No app can open this link".
- **Source:** ERROR-116. **Status:** TODO

### UIP-18 - Unpaired group-call members show their roster names
- **Setup:** A group of 3 where A is not paired with C (C joined through B's vouch). A starts a group call.
- **Steps:** Read the participant list on A, and on C when it receives the invite from A.
- **Pass:** C shows as its group name on A, not "Member (xxxx)"; a paired member keeps the name it has in the contact list.
- **Source:** ERROR-116, `groupRosterNames`. **Status:** TODO

### UIP-19 - Bottom bar hides on a fast flick only
- **Setup:** Phone, a long chat list.
- **Steps:** Scroll slowly down by dragging, then flick fast down, then flick up slowly, then fast, then go to the top.
- **Pass:** A slow drag leaves the bar; a fast flick down hides it; a quick flick up shows it; the top always shows it. If the thresholds feel wrong, record the numbers (`FlashNavAutoHideMath`).
- **Source:** ERROR-116. **Status:** TODO

### UIP-20 - Detail-pane progress shimmer scales with speed
- **Setup:** Tablet or desktop two-pane layout, an active transfer.
- **Steps:** Open the transfer in the detail pane; compare a slow and a fast transfer; enable reduced motion.
- **Pass:** The bar shimmers faster at higher MB/s, matches the Transfers row, and is still with reduced motion.
- **Source:** ERROR-116. **Status:** TODO

### MIG-12 - Upgrade a v11 install to v12
- **Setup:** A phone with the previous build (schema v11) and some chats.
- **Steps:** Install the new build over it; open a chat; pin a message.
- **Pass:** The app opens with all chats intact (no crash on open) and the pin works.
- **Source:** ADR-084, `STEP_11_12`. **Status:** TODO

## 4zg. Group files in a star topology (ERROR-117, 2026-10-06)

### SWM-40 - A vouched member gets a swarm file
- **Setup:** 4 phones, swarm switch on everywhere. Group made by the admin A; B, C, D each paired with A only (not with each other). All connected.
- **Steps:** B sends a 20 MB file to the group.
- **Pass:** C and D show a file bubble named like the file and download it (from B or A); B's bubble goes DELIVERED; the log on C has `SWARM announce root=... origin=false` and no `SyncPush ... dropped`.
- **Source:** ERROR-117, ADR-086. **Status:** TODO

### SWM-41 - A vouched member that connects later is offered the file
- **Setup:** as SWM-40, but C is switched off (or its app killed) while B sends.
- **Steps:** B sends; start C again.
- **Pass:** within about 15 s of C connecting to B or A it shows the file bubble and downloads it. Log: catch-up, then `SWARM announce ... origin=false`.
- **Source:** ERROR-117. **Status:** TODO

### SWM-42 - A member without the swarm switch gets no unusable bubble
- **Setup:** as SWM-40, swarm switch off on D.
- **Steps:** B sends a file.
- **Pass:** D shows no bubble that waits for bytes forever (an offer it cannot use is ignored); A and C behave as in SWM-40. If D should see the file, that is the FO-04 decision, note what D shows.
- **Source:** ERROR-117. **Status:** TODO

### SWM-43 - Voice note reaches a member that was offline (signed group)
- **Setup:** v2 group, C offline.
- **Steps:** A records a voice note; bring C back.
- **Pass:** C shows the voice note row after catch-up and the log has no `SyncPush message dropped, no valid signature`.
- **Source:** ERROR-117 (a). **Status:** TODO

### SWM-44 - Catch-up of a message from a faster clock
- **Setup:** v2 group of 3; set B's clock 3 to 4 s ahead of C's (manual time, automatic time off).
- **Steps:** B sends a text; C relays it to D (D offline when B sent, then back).
- **Pass:** no `no valid signature` line on D; the text appears on D. Restore automatic time afterwards.
- **Source:** ERROR-117 (b); if it passes with a clock more than 5 minutes off, record it (the clamp should then fail it, ADR-086 revisit). **Status:** TODO

### SWM-45 - Why a paired admin got a whole-file push instead of a swarm
- **Setup:** swarm on for all, sender paired with admin only, all connected.
- **Steps:** sender sends a 20 MB file; capture `adb logcat` on the sender and the admin from before the send.
- **Pass:** the admin shows `SWARM announce ... origin=false` (a swarm offer), not only `Data channel joined`. If the admin still gets a whole-file push, capture which `sw1` feature set the sender had for it (the cause is unproven).
- **Source:** ERROR-117 "Bug 3". **Status:** TODO

## 5. Measurements — do these last

They replace every *(measure)* estimate in the plans and decide tuning. Record each in `logs/experiments.md`.

| ID | What | Procedure | Decides | Status |
|---|---|---|---|---|
| MEAS-01 | **PC0** baseline: screen-off battery/hour with 0/1/4/8 idle sessions and 19 peers (held since ADR-057 raised the ceiling to 24; before it: 8 held + 11 refused); reconnect after a Wi-Fi toggle | [`docs/network/PC0-RUNBOOK.md`](../network/PC0-RUNBOOK.md) (peer farm + `tools/pc0/phone-baseline.ps1`) | How aggressive ECO must be | TODO |
| MEAS-02 | **PC6** scale: 20 peers per mode (battery, reconnect storm, delivery latency to a screen-off ECO phone) | Same rig as PC0 | ECO's ~1 min bound; whether groups go to 32; then **PC7** tuning and the default mode | **POSTPONED** (owner 2026-09-29, ADR-056, [FO-05](../FUTURE-OPTIMIZATION.md)). Not deleted, not blocking. Group size stays capped at 20 until it is done. |
| MEAS-03 | **G0** calls: 3-way video CPU, temperature and dropped frames; decoder instances; voice with 4/8/12 connections; 2.4 vs 5 GHz throughput | GROUP-VIDEO-PLAN §6 G0 | The §4.2 budget tables | TODO |
| MEAS-04 | **G0 codecs** C1 (desktop VP9 on webrtc-java 0.19.0), C2 (`MediaCodecList` on the BelFone and a mid-range phone), C3 (VP8 software vs H.264/VP9 hardware, 540p, 10 min) | GROUP-VIDEO-PLAN §4.6 | Whether **G4b** is built | TODO |
| MEAS-05 | CPU warning threshold (40 % of all cores for 30 s) against real calls | `CALL_DIAG proc cpu=` from GRP-08 and CALL-03 | The G6 threshold | TODO |
| MEAS-06 | Desktop render cost: capture + BGRA conversion + Skia upload was ~1.5 of ~2.5 cores (EXP-017) | Profile a 4-person desktop call | Whether hardware video (ADR-052) or render work comes first | TODO |
| MEAS-07 | **DR0** discovery failure matrix: for each of home router, mesh in bridge mode, router with IGMP snooping, client isolation, Android hotspot with 2+ clients, desktop with Hyper-V/VPN adapters: does mDNS work, does the `224.0.0.168` beacon work, does a directed broadcast arrive, is TCP 45822 reachable; screen on and off | Plan §4 DR0 (a small broadcast test sender is enough); log in `logs/experiments.md` | Which network each of DR2 (broadcast) and DR3 (subnet sweep) fixes. DR2/DR3 are built without waiting for it (owner 2026-09-29); DR6 (BLE) is postponed, FO-02 | TODO |
| MEAS-08 | **Signed groups (ADR-044 V1):** identity-key sign latency (StrongBox and TEE phones, desktop) and ECDSA P-256 verify cost per message and per bundle, including a 100-message catch-up round in a 6-member group and a first bundle of a 20-member v2 group (up to 21 verifications; ADR-044 V2) | Add a temporary `PERFORMANCE` timing log around `GroupSigning.signMessage` and `SignedGroups.verifiedAuthorLabel`, run GT-02 step 3 and a 100-message sync; log in `logs/experiments.md` | Whether the per-peer verification budget (120 per minute) and the message path need tuning, and whether signing on the send path needs to move off the caller | TODO |
| MEAS-09 | **Group video floor** (ADR-066): the encoder floor is now at most half the copy ceiling and dropped on every voice-priority rung (HIGH 360p copy: 225 kbps instead of a pinned 450); is half the right ratio, and does a 720p copy want 600 kbps? | 3 devices on a hotspot, record `video sender tuned` lines plus received fps / freeze count at 360p, 540p, 720p copies with the floor at 0, 1/4, 1/2 and 1 of the ceiling | The floor ratio in `groupVideoTuning`; whether the profile floor stays | TODO |

---

## Results log

Newest first. One entry per test session: date, build, devices, tests run, result, and links (ERROR/EXP).

### 2026-10-02 — owner-reported, group video size (GRP-01 partial)

- **Build:** not recorded (the repo HEAD that day was `367167a3`; the owner did not say which build was installed).
- **Devices:** not recorded.
- **Reported:** a video call with 3 devices and a video call with 4 devices. The owner's conclusion is that the
  realistic limit is 3 to 4 devices.
- **Recorded as:** GRP-01 PARTIAL. The "one leaves", Android PiP and log-review parts were not reported and stay
  TODO. GRP-10 added to find where video really stops being usable. GRP-09 (call size caps) is a different test and
  is untouched.
- **Not claimed:** that 5 or more devices fail (nobody reported that), or that the 3 to 4 figure holds on a LOW
  phone or on 2.4 GHz.
- **Links:** `docs/calling/GROUP-VIDEO-PLAN.md` section 8, `logs/experiments.md` (owner observation).

*(Backlog created 2026-09-29 at commit `4e71c5d`.)*
