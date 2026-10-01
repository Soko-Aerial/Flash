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

---

## Results log

Newest first. One entry per test session: date, build, devices, tests run, result, and links (ERROR/EXP).

*(No results yet. Backlog created 2026-09-29 at commit `4e71c5d`.)*
