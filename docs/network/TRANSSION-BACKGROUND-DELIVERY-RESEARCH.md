# Receiving messages while a Transsion phone's screen is off — research (2026-10-08)

Status: **RESEARCH, nothing built.** Source request: the owner asked (2026-10-08) whether Flash can receive messages in
the background "like WhatsApp" (through a system process such as Google Play services) and for a solution for Transsion
phones (Infinix, Tecno, itel). Related: ERROR-074, EXP-002, `docs/android-platform-notes.md` (addendum 2026-10-02 and
2026-10-08), ADR-041, `docs/HYBRID-ENTERPRISE-SERVER-RELAY-PLAN.md`, backlog HIB-01…HIB-04 and HIB-05…HIB-09.

Source labels follow AGENTS.md §13: **measured** (this project, on a device), **verified** (official documentation read on
the date above), **reported** (a third party says so), **unverified** (reasoning only).

## 1. The problem, precisely

- **Measured (ERROR-074, Infinix X6882B, Android 14, XOS):** `Hiber/sceneManager` in `system_server` freezes the whole app
  UID about 6–10 s after screen-off and unfreezes it only when Flash comes to the top (`reason:appToTop`). It ignored the
  foreground service (`types=0x10`), `dumpsys deviceidle whitelist +pkg`, `RUN_ANY_IN_BACKGROUND=allow` and standby
  bucket ACTIVE. EXP-002: delivery 4 % on that Infinix against 90 % on a Samsung.
- A frozen process runs no code: no socket reads, no timers, no WorkManager, no alarms. The kernel still holds the TCP
  connection, but Flash cannot answer a ping, so the peer's liveness timeout closes it.
- **Language and process layout are irrelevant.** The freezer acts per UID. A Rust/C++ core, or a second native process,
  has the same UID and is frozen with the app. (Answered to the owner 2026-10-08.)

## 2. What WhatsApp actually does (and why it cannot be copied for a LAN-only app)

- WhatsApp does not keep its own socket alive through the freeze. The **Google Play services** process keeps one
  persistent connection to Google, and a sender's phone asks Google to deliver a **high-priority FCM message**. OEM
  freezers and battery managers whitelist Google Play services; the push wakes (unfreezes) the app for a short window.
  The official FGS documentation lists "high-priority FCM message" as an exemption that lets an app start a foreground
  service from the background — **verified** (developer.android.com, restrictions-bg-start, read 2026-10-08).
- I found **no Play Services feature that wakes an app because of a LAN peer.** Nearby Connections needs the app running.
  FCM needs: internet on the device, a Firebase project, Google Play services on the phone, and a **sender with
  credentials** (a server, because a service-account key cannot be shipped in the app). That is the opposite of the
  closed, no-internet, no-account fleet the owner builds for.
- Whether Hiber unfreezes an app on a high-priority FCM message is **unverified** here. It is widely assumed (WhatsApp
  works on Infinix), but nobody has measured it for Flash. Test HIB-07.

## 3. Options, honestly ranked

| # | Option | Needs | Works offline / LAN-only | Evidence it beats Hiber | Verdict |
|---|---|---|---|---|---|
| A | **Make Hiber exempt Flash** with Transsion's own per-app switches, guided in-app, plus a fleet kitting checklist | A person taps through once per phone | Yes | Reported by third parties; **not yet verified on this device** (HIB-01 owed) | **Do first.** The only route with no new dependency |
| B | **Stay-awake while charging** on desk/charging-station phones (Device Owner can set the global setting; a user can set it in developer options) | Phone plugged in | Yes | Hiber freezes at *screen-off*; screen on = no freeze. **Unverified** (HIB-09) | Good for fixed devices, useless for pocket phones |
| C | **Wake by Bluetooth LE presence** (Companion Device Manager; the system binds a `CompanionDeviceService` and raises the process priority) | A one-time association per peer; BLE hardware; phones within ~10–30 m | Yes | Official: binding "automatically managed" by BLE presence; app gets `REQUEST_COMPANION_RUN_IN_BACKGROUND`. **Whether Hiber honours it is unverified** (HIB-04) | **Second.** Server-free; carries no messages, only a wake-up |
| D | **FCM high-priority wake-up** (data-only, empty payload; content stays on the LAN/relay) | Internet, Google Play services, a small server (fits ADR-095) | No, internet needed at wake time | Industry practice (WhatsApp). **Not measured for Flash** (HIB-07) | Only if a deployment has internet and accepts Google seeing "a wake-up happened" (metadata). Needs its own ADR. Owner currently rules FCM out |
| E | **Ask Transsion for an enterprise whitelist or build** (fleet purchase leverage) | A commercial contact | Yes | None; **unverified** that such a programme exists | Worth one email if the fleet is Transsion |
| F | **Silent audio loop in a `mediaPlayback` FGS** | Nothing | Yes | None found; battery cost; store-policy risk | Experiment only (HIB-02). Not recommended |
| G | **Buy phones whose maker does not freeze this way** (Samsung measured 90 %) | Procurement | Yes | Measured (EXP-002) | The strongest fix there is, and it costs no code |
| H | Rust/C++ rewrite; hidden daemon; `fork` tricks | Months | n/a | None; Android 12+ kills phantom processes; Play policy forbids | **No** |
| I | Exact alarms / WorkManager keep-alive (exists: ADR-041, 15 min) | — | Yes | Measured to fail on Infinix (frozen processes run no alarms) | Keep as a cheap extra; do not rely on it |
| J | AccessibilityService / VPN-service / default-role tricks | Policy exposure | Yes | None; fragile | **No** |

**What MDM can and cannot do (reported by MDM vendors and the Android Management API docs read 2026-10-08):** a Device
Owner can pre-grant runtime permissions, stop the user force-stopping the app, install it and pin it, and set
"stay awake while plugged in". The Android Management API has **no field for an OEM battery/freezer exemption**; even the
standard "ignore battery optimisation" dialog stays interactive. So OEM exemptions become a **one-time kitting checklist
per handset**, not an MDM policy.

## 4. Recommended order of work (no code until the owner picks)

1. **HIB-01 first (no code):** on the Infinix apply the reported switches and re-run the screen-off test while grepping
   `adb logcat | findstr Hiber`. Everything below depends on the answer.
   - Settings → App management → App list → Flash → Battery → *Allow background activity* **on**, *Stop app when idle*
     **off** (reported, dont-kill-my-app issue #4394, HiOS/XOS 14/15).
   - Phone Master → Accelerate → Auto-start management → Flash on; Phone Master → Settings → *Protected apps* → Flash.
   - Recents → lock Flash. Settings → Battery → turn off sleep-mode optimisation / automatic power saving.
   - Home screen long-press → Desktop settings → Other settings → **Freezer**: make sure Flash is not in the list.
   - Menu names change between firmware builds, so the in-app guide must be generated from what the screen resolves to.
2. **If the switches work (likely):** build the guided flow (HIB-03) — detect Transsion (`OemBatteryOptimizationHelper`
   already does), walk the user through each switch with screenshots of their own firmware, and add a **freeze detector**:
   a monotonic-clock gap > 60 s while the foreground service claimed to be running ⇒ show "Your phone paused Flash for N
   minutes" with a button to the guide. Add the kitting checklist to the fleet documentation.
3. **If the switches do not hold:** run HIB-04 (Companion Device Manager + BLE) next, then HIB-05/06 below. Only then
   consider FCM (D) as an optional, off-by-default wake channel with its own ADR.
4. **Independent of all that:** B for charging-desk devices, G/E at procurement.

## 5. Facts worth keeping (verified 2026-10-08, developer.android.com)

- Companion Device presence is **BLE and Bluetooth only; Wi-Fi presence is not supported.** A bound
  `CompanionDeviceService` lets the app start foreground services from the background
  (`REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`). From Android 16 the old string-based
  `startObservingDevicePresence` / `onDeviceAppeared` are deprecated in favour of `ObservingDevicePresenceRequest`
  (which also allows UUID-based presence — Android 16 only; the Infinix Hot 50 is Android 14).
- Exemptions that allow starting a foreground service from the background: high-priority FCM, Companion Device
  Manager, exact alarms for user-requested actions, boot broadcasts, battery optimisation disabled by the user, device
  owner/profile owner. None of them unfreezes a process that an OEM freezer holds — that is exactly the open question.
- Camera/microphone/location foreground services cannot be created from the background on Android 14+ even when an
  exemption applies (matters for answering a call after a wake-up).

## 6. Sources

- Android Developers, *Restrictions on starting a foreground service from the background* (read 2026-10-08).
- Android Developers, *Companion device pairing* (read 2026-10-08).
- dont-kill-my-app issue #4394, *Tecno page: HiOS 14/15 and Infinix XOS paths* (reported).
- tiktask.ai, *How to keep automation apps running on Xiaomi, Infinix, and Tecno* (reported; admits screen-off
  execution stays inconsistent even after the settings).
- MDM-vendor and Android Management API notes on OEM battery exemptions (reported).
- Project evidence: ERROR-074, EXP-002, `Hiber` logcat lines.
