# Plan R: Hardware Radio KISS TNC Gateway (`:core:radio`)

**Date:** 2026-10-16 (revision 3, same day)
**Status:** APPROVED by the owner on 2026-10-16 (ADR-173 as amended, O-4, O-5, O-6, O-7 answered; code phases are built by the streams of `docs/plans/RADIO-PROMPTS-2.md`). Earlier status was PROPOSED, DESIGN ONLY. Hardware spike `BT-00` passed (`EXP-023`).
**Owner decision target:** approve (1) the dedicated `:core:radio` module, built by **moving** the radio code that already exists, (2) the "find, pair and connect" flow in section 6 (automatic detection, in-app scan and pairing), (3) the open decisions in section 12.
**Decision record:** ADR-173 in `docs/decisions.md` (amended by this revision).
**Parent blueprint:** `Project Goal and Blueprint/android-lan-wifi-direct-transfer-app-plan.md`
**Earlier work this plan builds on:** `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` (BT-0/BT-1 groundwork, ADR-101), `docs/network/RADIO-WIRE-FORMAT.md` (the byte layout), `docs/reports/2026-10-09-bluetooth-radio.md`, `docs/ui/radio-link-test.md`.

## 0. What changed in revision 2

| Change | Why |
|---|---|
| **The codecs, driver, pacing, crypto frame and serial/Bluetooth catalogues are MOVED from `:core:network` into `:core:radio`, not rewritten.** Old phases R0-R2 (create codecs, create link adapters, create driver) are replaced by one move phase. | Revision 1 did not know this code existed (built 2026-10-09, section 3). Rewriting it would throw away tested code and golden vectors. |
| **Voice notes are removed** (owner, 2026-10-16): no recorded clips, no Codec2/Opus, no audio-note codec, no `BT-R06`. | Owner decision. Also removes a new native dependency. |
| **New section 6 "Find, pair and connect":** how the app finds the radio on the PC and on the phone, how the user pairs it, what the user sees when something is wrong. | Revision 1 only said "port picker (`COM16`)". |
| Security profile: **encryption required** (revision 3). Profile M stays the default; the first enable asks the user to confirm they are authorised to use encrypted traffic on the frequency in use (section 8). | Owner: military use. The test radio sits on an amateur band, where encryption is usually not allowed, so the app records the user's confirmation instead of assuming. |
| Walkie-talkie audio bridge (old Modes 3 and 5) is kept but **gated behind a spike** (R4). | Nothing in EXP-023 shows the audio path works; the Windows audio connection failed in the spike. |
| Section 7 says how a message chooses the radio and what cannot go over it. | Revision 1 left the outbox integration undefined. |

**Revision 3 (owner, 2026-10-16), two changes:** (a) the radio may be used by military users, so **encryption is required**: Profile M (encrypted) stays the primary profile and O-1 is answered (section 8); (b) the app must **detect the already-paired radio by itself** and **show nearby devices so the user can pair inside Flash** without opening Bluetooth settings (section 6, spike `BT-R11`).

## 1. Executive summary and rules

Flash is transport-agnostic above the wire: chat, outbox, signatures and PTT floor control do not care whether bytes travel over Wi-Fi, Ethernet or something else. The `BT-00` spike (`EXP-023`, Vero Telecom VGC VR-N76 / BTECH UV-Pro) proved a phone or PC can talk to a handheld radio with a built-in KISS TNC over Bluetooth serial and make it transmit. This plan turns the existing groundwork into a product feature.

1. **Optional, off by default.** A switch in Settings, "Radio gateway". When off, no serial port is opened, no Bluetooth is touched, no radio permission is requested, no service runs.
2. **Own module `:core:radio`.** All radio code lives there: KISS, AX.25, the Flash radio frame, the driver and pacing, and the platform serial/Bluetooth access. `:core:network`, `:core:transfer` and `:core:messaging` contain no radio code afterwards.
3. **Move, don't rewrite.** Section 4 lists what moves. Behaviour and tests stay identical; the move is its own phase and its own commit.
4. **The radio is a slow shared modem, not a network.** 1200 baud, one frequency, everyone hears everyone. Only short text and small control frames go over it (section 7).
5. **No claim without hardware.** Everything not marked *verified* in section 2 is a test to run, not a fact.

## 2. Hardware ground truth (verified in EXP-023, VR-N76, Windows 11)

| Fact | Measured | Rule for the design |
|---|---|---|
| Data link | Bluetooth Classic SPP (`00001101-0000-1000-8000-00805F9B34FB`), shown by Windows as a virtual COM port (it was `COM16` on that PC), 115200 8-N-1 | The port name is per-PC and can change. Never store it as the radio's identity (section 6). |
| Enabling | The radio must have *Menu, General Settings, KISS TNC, Enable* switched on | The connect flow must tell the user this (section 6). |
| Modulation | Fixed 1200 baud Bell 202 AFSK (about 120-150 bytes/s). The radio has no baud setting; 9600 baud does not exist on this radio | All budgets assume 1200 baud. |
| Airtime | A 220-byte payload keeps the transmitter on for about 1.9 s (1.587 s of data plus 300 ms TXDELAY) | MTU is capped (220 bytes); anything longer is segmented. |
| Keying | Any KISS **data** frame sent to the port makes the radio transmit at once | Nothing may send a data frame as a "probe". Section 6 explains the consequence. |
| Radio screen | Outbound KISS frames do not show on the radio's screen; the radio only shows the last received APRS packet | Flash on the phone/PC is the chat UI and database. The radio is a transparent modem (BLUETOOTH-AND-RADIO-TNC-PLAN section 4.5, Option A). |
| Audio profile | The radio also advertises Bluetooth Hands-Free (`VR-N76 Hands-Free HF`). Windows lists it as an "Audio Device"; connecting through the Windows audio UI failed because no call was active | **Unproven.** Section 9. |
| Real-time digital voice | Not possible at 1200 baud | Not planned. Voice notes were considered and removed (section 0). |

## 3. What already exists (inventory, checked in the repo 2026-10-16)

Built 2026-10-09 inside `:core:network`, unit-tested, **never run on a phone or against a radio from the app** (`:desktop:radioLinkTest` exists for the PC spike):

| Area | Files in `core/network/src/...` | Notes |
|---|---|---|
| KISS and AX.25 codecs | `commonMain/.../kiss/KissFrameCodec.kt`, `Ax25FrameCodec.kt` | pure Kotlin; tests `KissFrameCodecTest`, `Ax25FrameCodecTest` |
| Link seam and catalogue interface | `commonMain/.../radio/ByteLink.kt` | `ByteLink`, `PortInfo`, `PortKind`, `SerialSettings`, `SerialPortCatalog` (`listPorts`, `open`), `classifyPort` |
| TNC driver and pacing | `KissTncDriver.kt`, `TxPacing.kt`, `RadioState.kt` | reconnect with backoff, TXDELAY/persistence/slot time only if configured, injectable clock; tests `KissTncDriverTest`, `RadioStateTest`. Uses `ReconnectPolicy` from `:core:network` (see section 4) |
| Flash radio frame | `RadioWire.kt`, `RadioCrypto.kt`, `RadioSession.kt`, `StreamFramer.kt` | AES-256-GCM, counter nonce, replay window, rotating tag, segmentation to 16 segments, signed clear-text mode; golden vectors `RadioGoldenFrameTest`, `RadioCryptoVectorsTest`, `RadioSessionTest`, `StreamFramerTest` |
| Evidence and lock | `RadioEvidence.kt`, `RadioLock.kt` (+ `jvmMain`/`androidMain` actuals), `JdkRadioCrypto.kt` (both targets) | PROBE lines; an `expect` class, so the new module needs `-Xexpect-actual-classes` like `:core:ptt` |
| Desktop serial | `jvmMain/.../radio/JvmSerialPortCatalog.kt` | jSerialComm 2.11.4 (`getCommPorts`) |
| Android Bluetooth | `androidMain/.../radio/AndroidBluetoothCatalog.kt`, `commonMain/.../radio/BluetoothPermissions.kt` | lists **paired** devices only (`listBonded`: no adapter / Bluetooth off / permission missing / devices), opens RFCOMM; permission logic tested in `BluetoothPermissionsTest` |
| Simulation and diagnostics | `commonMain/.../radio/sim/RadioSimulation.kt`, `diag/RadioLinkTester.kt`, `jvmMain/.../radio/diag/RadioLinkTestCli.kt`, `RadioLinkTestHarness.kt` | in-memory link pair, fake TNC, fake air channel; the BT-00 tools |
| Host wiring | `desktop/build.gradle.kts` (tasks `radioLinkTest`, `radioLinkTestCli`, jar excludes), `desktop/.../RadioLinkTestMain.kt`, `app/src/main/AndroidManifest.xml` (Bluetooth permissions) | the only users outside `:core:network` |
| Build | `core/network/build.gradle.kts` line `implementation(libs.jserialcomm)` | the only place jSerialComm is used |

**Not built** (so this plan does build it): the adapter from Flash's pairing key to `RadioSession`; `FlashTransportType.BLUETOOTH` and the planner hook; any chat or UI integration; Profile A operation; segment retransmission; gateway/relay logic; the find/pair/connect flow.

## 4. The move: from `:core:network` to `:core:radio`

### 4.1 Target layout

```
core/radio/
  build.gradle.kts            (KMP: android + jvm, explicitApi(), -Xexpect-actual-classes, maven-publish like :core:ptt)
  src/commonMain/kotlin/com/transfer/flash/core/radio/
      kiss/    KissFrameCodec, Ax25FrameCodec
      link/    ByteLink, PortInfo, SerialPortCatalog, classifyPort, BluetoothPermissions
      driver/  KissTncDriver, TxPacing, RadioState
      wire/    RadioWire, RadioCrypto, RadioSession, StreamFramer
      sim/     RadioSimulation
      diag/    RadioLinkTester
      (RadioEvidence, RadioLock in the package root)
  src/jvmMain/...        JvmSerialPortCatalog, RadioLock.jvm, JdkRadioCrypto, diag/RadioLinkTestCli + Harness
  src/androidMain/...    AndroidBluetoothCatalog, RadioLock.android, JdkRadioCrypto
  src/commonTest, jvmTest   every test listed in section 3, moved unchanged
```

Package rename: `com.transfer.flash.core.network.radio` and `.kiss` become `com.transfer.flash.core.radio` (and sub-packages). New code in later phases goes in the same module (`gateway/`, `router/`, `connect/`).

### 4.2 Dependencies

- `:core:radio` depends on `:core:common` (time source, results). It may depend on `:core:security` only if a later phase needs pairing keys.
- **`:core:network` must not depend on `:core:radio`, and `:core:radio` should not depend on `:core:network`.** The one existing link is `KissTncDriver` using `ReconnectPolicy` (`:core:network/resilience`). At the start of R0 read `ReconnectPolicy`: if it has no network types, move it to `:core:common` and update its other users; if it does, the driver gets its own small backoff and the duplication is recorded as debt. The fallback of making `:core:radio` depend on `:core:network` is allowed only with a written reason, because it brings the whole network stack into a module meant to be light.
- jSerialComm moves to `:core:radio`'s `jvmMain` and **leaves** `:core:network`. The ADR-101 licence note (Apache-2.0 OR LGPL-3.0) moves with it.
- Only `:app` and `:desktop` depend on `:core:radio`, and only through the radio facade added in R3. `:core:engine` stays unaware until the owner approves wiring.

### 4.3 Procedure (R0)

1. **Baseline first.** Run the radio tests in `:core:network` and write down the counts (JVM and Android host). Record the SHA-256 of the golden-vector test resources, if any.
2. `git mv` the files (history is kept), fix packages and imports, add the module to `settings.gradle.kts`, `jitpack.yml` publish list and `.github/workflows/ci.yml` module list, wire `:app` and `:desktop`.
3. Move the `radioLinkTest` and `radioLinkTestCli` task entries to the new classpath (the CLI main class name changes with the package; the jar excludes in `desktop/build.gradle.kts` follow it).
4. Update every doc that names the old path or package (`docs/ui/radio-link-test.md`, `docs/network/RADIO-WIRE-FORMAT.md`, the 2026-10-09 report, `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md` status block): mark them, never delete.
5. One commit for the move, containing no behaviour change.

### 4.4 Acceptance for the move

- Same number of radio tests, all passing, on JVM and Android host; golden vectors byte-identical.
- `core/network` has no `radio` or `kiss` directory and no `jserialcomm` reference.
- `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm`, `:desktop:compileTestKotlinJvm` green; `tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"` still lists ports.
- Manifest permissions unchanged; the released library publish list builds (run the `jitpack.yml` install line, see memory note on release dry-runs).

## 5. The five modes

| Mode | Role | Channels | Needs |
|---|---|---|---|
| 1 Field companion | One operator, phone or PC paired to one radio: text chat with delivery receipts over VHF/UHF | data (serial) | R1-R3 |
| 2 Base station relay | A laptop at base connected to Wi-Fi **and** a base radio; forwards messages between people on Wi-Fi and people reachable only by radio | data + LAN | R5 |
| 3 Walkie-talkie bridge | Flash push-to-talk audio out to the radio so ordinary analog radios hear it, and back | Bluetooth audio | R4 **spike first** |
| 4 Tactical radar | Periodic location reports shown as distance and bearing on an offline screen | data | R6 |
| 5 All-in-one | Modes 1 and 3 together | data + audio | R4 result |

Voice notes (recorded clips over the data link) are **not** part of any mode (section 0).

## 6. Find, pair and connect

This is the part revision 1 left out. Rules first, then the flow per platform.

**Rules**
1. The radio's identity is its **Bluetooth address**, never a COM number or port name. Ports are renumbered by Windows and by re-pairing.
2. **Flash finds and pairs the radio inside its own screen** (revision 3): it detects an already-paired radio by itself, and otherwise lists nearby devices and starts the pairing. The operating system still performs the pairing and may show its own confirmation or PIN box over Flash, but the user does not have to go to Bluetooth settings. Settings stays as a fallback button. OS pairing is **not Flash trust** (ADR-101); the Flash session layer authenticates the peer itself.
3. **Detecting is not trusting and not connecting blindly.** A device is offered as "probably your radio" by name or service, never opened and never sent anything until the user confirms it once. After that confirmation the radio is remembered and reconnected automatically.
4. Flash never sends a data frame to "see if it is a TNC": KISS has no handshake and a data frame makes the radio transmit. Liveness checks use only things that do not transmit (port opens, KISS **command** frames such as TXDELAY, which are not sent on air, and silence versus received frames). Anything that transmits is an explicit user action with a warning (the "Send a test message" button) and only after the acknowledgement in O-1.
5. Every failure shows one plain sentence and one next step, never a stack trace or an error code.

**The flow (same screens on both hosts, Settings, Radio gateway)**

| Step | Screen | What Flash does |
|---|---|---|
| 1 | Switch "Radio gateway" on | Shows what it needs: a compatible radio with a KISS TNC, paired once in system settings. Asks for nothing yet. |
| 2 | "Is your radio ready?" checklist | Radio on; **KISS TNC enabled in the radio menu** (VR-N76: Menu, General Settings, KISS TNC, Enable); radio in a state where it can be found (how this is done on the VR-N76 is unknown and is answered by `BT-R11`). |
| 3 | Find the radio | **Automatic detection first** (next block), then the live list of nearby devices (below). The user confirms one device. |
| 4 | Pair (if needed) and connect | Starts the pairing from inside Flash when the device is not paired yet, then opens the link and shows "Connected to <radio name>". Remembers the radio's Bluetooth address. |
| 5 | Choose the mode and the security profile (O-1) | Only after step 4 succeeds. |
| 6 | Optional "Send a test message" | Transmits once, with a warning; not required. |

**Automatic detection of a paired radio**
- Runs when the switch is turned on and whenever the screen is opened with no remembered radio. It never runs in the background and never scans on its own while the switch is off.
- A paired device is a candidate when its name matches the known radio list (VR-N76, UV-Pro and similar; the list is data, not scattered through the code) **or** it offers a serial service and nothing else claims it. Other Bluetooth serial gadgets (GPS pucks, scanners) look alike, which is why the first use needs the user's confirmation.
- Exactly one candidate: preselected with the question "Use <name>?". Several: a short list. None: go to the live list.
- Once confirmed, the radio's address is remembered and Flash reconnects to it by itself whenever the switch is on and the radio is in range.

**Live list of nearby devices, pairing without leaving Flash**
- A "Find my radio" screen shows devices found by a Bluetooth scan, name first, radios ranked on top, everything else under "Other devices". A paired radio shows as such; an unpaired one shows a "Pair" button. The scan stops when the screen is left or after a time limit (it drains battery and slows the link).
- Pairing is started from that button; the system's own confirmation or PIN box appears over Flash if the radio needs one. If the radio asks for a PIN, Flash asks for it in its own box where the platform allows. The default PIN of the VR-N76 is **not known to this plan**; `BT-R11` records what the radio asks for.
- If scanning or in-app pairing is not possible on a host or the radio is not found: an "Open Bluetooth settings" button (Android settings, or `ms-settings:bluetooth` on Windows), then "Check again".

**Windows (and the PC in general)**
- Candidates come from the existing `JvmSerialPortCatalog.listPorts()` filtered to `PortKind.BLUETOOTH` first, USB serial second (a USB cable to a radio is allowed), other last.
- The current catalogue gives the port name and driver text (for example "Standard Serial over Bluetooth link (COM16)") but **not the Bluetooth address**. R1 must find a way to map a port to a device address (Windows registry device enumeration or PowerShell device query) and **verify it on the owner's PC** before it is relied on. Fallback if it cannot be done: remember the port name plus the driver description, and when the port vanishes or changes, show the list again with the old choice highlighted instead of silently picking another port.
- Windows may expose more than one port for one radio (incoming and outgoing). The rule for which one to open is decided from what the spike PC shows, then recorded.
- **In-app scan and pair on Windows (needs spike `BT-R11` before it is promised).** Windows has no scan/pair call in the Java platform. The candidate route is the Win32 Bluetooth API in `bthprops.cpl` (`bluetoothapis.h`: device enumeration with inquiry, `BluetoothAuthenticateDeviceEx` for pairing, `BluetoothSetServiceState` to switch on the serial service so the COM port appears), called through JNA, which the desktop already ships (`jna` 5.17.0, used for DPAPI, ADR-035). Microsoft's pages confirm the authentication and service-state calls exist (checked 2026-10-16, learn.microsoft.com, Win32 Bluetooth functions); whether the inquiry and pairing work for this radio, what PIN it wants, and whether the COM port really appears afterwards are **unverified**. Alternative if JNA proves brittle: the Windows Runtime device pairing classes through a small helper. Whatever is chosen is recorded as a decision with its dependency (AGENTS section 3).
- "Port busy" (another program, such as a terminal or the BT-00 tool, holds the port) is a named failure: "COM port is in use by another program. Close it and try again."
- Linux (`/dev/rfcomm*`, `/dev/ttyUSB*`) already classifies in `classifyPort`; Linux pairing steps are left to the Linux port plan and are out of scope here.

**Android**
- Paired radios come from `AndroidBluetoothCatalog.listBonded()` (exists). **New in revision 3:** the live list uses a Bluetooth discovery scan and starts pairing from Flash with `BluetoothDevice.createBond()`; the result is read from the bond-state broadcast. This replaces the earlier "paired devices only, no scanning" decision in the 2026-10-09 code.
- Permissions (Android developer documentation, checked 2026-10-16: developer.android.com/develop/connectivity/bluetooth/bt-permissions): on Android 12+ discovery needs `BLUETOOTH_SCAN` and `createBond()` needs `BLUETOOTH_CONNECT`, both runtime "Nearby devices" permissions. Declaring `BLUETOOTH_SCAN` with `usesPermissionFlags="neverForLocation"` (already in the app manifest) avoids the location permission on Android 12+, but the docs warn it can filter some devices from scan results, so `BT-R11` must show the radio still appears; on Android 11 and lower discovery needs location (`BluetoothPermissions` already encodes the version split). Permissions are asked **when the user reaches step 3**, with a one-sentence reason, never at app start. If denied: "Allow Nearby devices to find your radio" with a button to settings.
- Alternative to evaluate in `BT-R11`: the Companion Device Manager (Android 8+), which runs the scan and pairing through a system chooser without location permission. It would give a system-styled dialog instead of Flash's own list; decide after the spike.
- States already modelled in `BondedListing`: no adapter, Bluetooth off, permission missing, devices. Each gets its sentence.
- A held connection while the screen is off needs a foreground service. Check the current Android rules for the service type before writing it (AGENTS section 21), and record the result in `docs/android-platform-notes.md`. Transsion phones freeze background apps (see HIB notes); no claim of background reliability until measured.
- The radio is connected on demand, not at boot, and only while the gateway switch is on.

**Reconnecting**
- Link loss (radio switched off, out of range, battery) is retried with the existing backoff in `KissTncDriver`; the UI shows "Radio disconnected, trying again" and stops trying after a limit with a "Reconnect" button.
- When the app starts with the switch on, it connects to the remembered address only; it never scans or opens other ports.

**Flash-to-Flash trust over radio**
- Encrypted radio frames use keys derived from an existing Flash **pairing**. Two stations that have never paired (on Wi-Fi, by hub, or in a later QR step) cannot exchange encrypted frames. The connect flow therefore says so: "Pair with your teammates while you are on the same network; radio chat works only with paired people."
- The adapter from the real pairing key to `RadioSession` is built in R3.

## 7. Using the radio for messages (R3)

- **When the radio is used:** only when the recipient has no live LAN or hub path and is known to be radio-reachable (a paired contact with a radio station tag). Wi-Fi and hub always win.
- **What can go:** short text messages (segmented as in `RADIO-WIRE-FORMAT.md`, up to its segment limit) and the small control frames: delivery acknowledgements, location reports (Mode 4).
- **What cannot go:** photos, files, voice messages, calls. The composer says it in one sentence ("Photos and files cannot be sent by radio"), the message is not queued.
- **"Delivered" means a cryptographic acknowledgement came back over the radio**, not "the radio transmitted it". Until then the message shows as sent by radio, waiting.
- **Priority:** emergency flag, then text, then acknowledgements, then location reports. Pacing is the existing `TxPacing` plus the channel wait/jitter (to be confirmed on air, BT-R03).
- The planner hook (`FlashTransportType.BLUETOOTH`) and the repository change are small, separate commits, each with the owner's approval for touching shared engine files.

## 8. Security and regulation

- The Flash frame is authenticated and encrypted (Profile M) or signed clear text (Profile A); the details are in `RADIO-WIRE-FORMAT.md`. A replay window exists; R3 must check that the **counter survives an app restart** (a reused counter with the same key breaks AES-GCM).
- **Encryption is required (owner, 2026-10-16: military use).** Profile M (encrypted, rotating station tags, no callsign in the clear) is the default and the primary profile. It requires the radio and frequency to be authorised for encrypted traffic, which a military or other licensed service has and which a hobby (amateur) band usually does not: the test radio is on 144.625 MHz, an amateur band, where many countries forbid encrypted messages and require a callsign. Flash cannot know which case applies, so the **first enable asks the user once to confirm they are authorised to transmit encrypted traffic on the frequency in use**, and records that confirmation (a setting, with date). Without the confirmation, only Profile A (signed clear text with a callsign) can be selected. Tests on 144.625 MHz stay in Profile A or with a licensed operator present. This plan did not research local law; the owner confirms what applies where it will be used.
- A lost or captured radio must not expose traffic: the radio holds no keys (it is a modem); keys are in Flash's own store on the phone/PC.
- No plaintext location beacons. No logging of keys or frame plaintext (AGENTS section 24); PROBE lines carry only counts and short hashes.

## 9. Walkie-talkie audio bridge (R4, spike first)

Revision 1 treated this as a normal feature. It is not yet known to work.

Unknowns to settle with a spike before any design (`BT-R10`):
1. Does the phone or PC get a working Bluetooth Hands-Free audio connection to the radio, and in which role?
2. How is the transmitter keyed from Flash (radio-side voice activation, a Bluetooth command, or nothing)?
3. Audio quality of the narrow Bluetooth voice channel (8 kHz) and whether incoming radio audio reaches the app.
4. How analog radio traffic fits Flash's push-to-talk turn-taking, which it cannot follow.

R4 ends with a short written result and an owner decision: build, rescope, or drop Modes 3 and 5. Nothing else in the plan depends on R4.

## 10. Phases

| Phase | Work | Needs the radio? | Exit |
|---|---|---|---|
| **R0 Move** | Section 4. | no | section 4.4 acceptance; one commit |
| **R1a Spike `BT-R11`** | Before building the screens, on the owner's PC and phone find out: (1) can the radio be found by a scan and how is it made discoverable; (2) what pairing it asks for (PIN or none); (3) Windows: do the inquiry, pairing and serial-service calls work through JNA and does the COM port appear; (4) Android: does the radio appear with `neverForLocation`, does `createBond` work, how does Companion Device Manager compare; (5) how to map a Windows COM port to the radio's address. Result written down; it decides O-6 and O-7. | yes, phone and PC | written result |
| **R1 Find, pair, connect** | Section 6 as the spike allows: automatic detection, live nearby list, in-app pairing, remembered address and automatic reconnect, failure sentences, port-to-radio mapping on Windows, Android permission flow and foreground-service decision. First on-device use of the Android RFCOMM code. | yes, phone and PC | `RADCON-*` checks in section 11 |
| **R2 Driver on real hardware** | Run the moved driver against the radio from the app: parameter frames, pacing under load, reconnect after power cycle. Fix what the hardware shows. | yes | BT-R03, BT-R04 pass |
| **R3 Companion mode** | Pairing-key adapter to `RadioSession`; `FlashTransportType.BLUETOOTH` and planner hook; outbox integration with acknowledgement-based delivery; the limits of section 7; counter persistence. | yes, two radios | BT-R05 pass |
| **R4 Audio bridge spike** | Section 9. | yes | written result and owner decision |
| **R5 Base relay** | Wi-Fi to radio forwarding with a 1-hour dedup cache, hop limit, size cap and a defined route table (who is reachable by radio). Must not forward bulk Wi-Fi traffic onto a 1200-baud channel. | yes, two radios | BT-R07 pass |
| **R6 Settings polish and radar** | Mode selector, security profile selector (per O-1), offline radar screen from location reports. | yes | BT-R08 and radar checks |

## 11. Tests

Unit tests move with their code and are not rewritten (section 3). New and owed device tests; each is added to `docs/testing/TEST-BACKLOG.md` when its phase is built (AGENTS section 35):

| ID | Phase | Scenario | Pass |
|---|---|---|---|
| `RADMOVE-01` | R0 | Test parity after the move | same test count as the baseline, all green, golden vectors identical |
| `RADMOVE-02` | R0 | `:core:network` is clean | no radio/kiss sources, no jSerialComm; `:core:radio` does not depend on `:core:network` (or the exception is recorded) |
| `RADMOVE-03` | R0 | Desktop tools still work | `radioLinkTestCli --list` lists the radio's port |
| `RADCON-01` | R1 | Windows: pick the radio when several serial ports exist | the radio is found by Bluetooth address, not by COM number |
| `RADCON-02` | R1 | Windows: unpair and re-pair so the COM number changes | the remembered radio is still found; or, if the fallback is used, the list reappears with the old choice highlighted and nothing else is opened |
| `RADCON-03` | R1 | Android: first use without the permission | the permission is asked at step 3 only; denial shows the settings sentence |
| `RADCON-04` | R1 | Android: radio not paired, Bluetooth off, KISS TNC disabled, radio off | each shows its own sentence and next step |
| `RADCON-05` | R1 | Port held by another program | the "in use" sentence |
| `RADCON-07` | R1 | Radio already paired, one candidate | switch on shows "Use <name>?"; after confirming, no further question on later starts, reconnects by itself |
| `RADCON-08` | R1 | Two Bluetooth serial devices paired (the radio and another) | both listed; nothing is opened before the user confirms; the wrong one is never auto-selected |
| `RADCON-09` | R1 | Radio not paired yet: scan, press Pair | radio appears in the live list; pairing completes without opening system Bluetooth settings (a system confirmation box is allowed); the radio is then connected |
| `RADCON-10` | R1 | Scan screen left or time limit reached | scan stops (no scan running afterwards, checked by log line) |
| `RADCON-11` | R1 | Pairing refused or wrong PIN | one sentence and a retry; the settings fallback button works |
| `RADCON-06` | R1 | Android connect with screen off | measured, recorded in `logs/experiments.md`; no background claim without it |
| `BT-R11` | R1a | Scan/pair spike on Windows and Android | the five questions of the R1a row answered in writing |
| `BT-R03` | R2 | Many queued frames | transmitter never keyed back-to-back; gaps match the pacing policy |
| `BT-R04` | R2 | Switch the radio off and on | link comes back without the user doing anything |
| `BT-R05` | R3 | Text chat, two Flash stations over two radios | message arrives; a delivery acknowledgement returns; a restart does not reuse a counter |
| `BT-R07` | R5 | Wi-Fi-only phone to a distant patrol through the base | message arrives once; a loop does not form |
| `BT-R08` | R6 | Security profile | Profile M shows only ciphertext on the air; Profile A shows plaintext with the station identification (capture with a second receiver) |
| `BT-R09` | R3 | Try to send a photo to a radio-only contact | refused with the section 7 sentence, nothing queued |
| `BT-R10` | R4 | Audio bridge spike | the four questions of section 9 answered in writing |

The older `BT-00` and `BT-09`..`BT-17` entries in the backlog stay as they are (never delete tests).

## 12. Open decisions for the owner

| ID | Question | Recommendation |
|---|---|---|
| O-1 | **ANSWERED by the owner 2026-10-16:** encryption is needed (military use); Profile M is the default. | Done in section 8. The one-time authorisation confirmation stays, because the same radio can be on an amateur band. |
| O-2 | Is a second radio available for R3 and R5 tests? | **ANSWERED 2026-10-16:** two radios exist but are at the owner's workplace, available later. Until then R3 and R5 are built and simulated only; every radio test stays TODO and nothing is called verified. |
| O-3 | Move `ReconnectPolicy` to `:core:common` (section 4.2)? | Yes if it has no network types. |
| O-4 | May `:core:engine` and the connection planner be touched for `FlashTransportType.BLUETOOTH` (R3)? | **ANSWERED yes by the owner 2026-10-16** ("yes now"): separate small commits, hooks only; the prompt requires proof that users who never enable the radio see no change. |
| O-5 | Approve ADR-173 as amended (revision 3). | **ANSWERED yes by the owner 2026-10-16.** |
| O-6 | How does the PC scan and pair in-app: Win32 Bluetooth API through JNA (already shipped), or a Windows Runtime helper? | **ANSWERED 2026-10-16:** Win32 through JNA + registry COM mapping (ADR-182 ACCEPTED, BT-R11 PASS). |
| O-7 | Android scan/pair UI: Flash's own list (`BLUETOOTH_SCAN`, `createBond`) or the system Companion Device Manager chooser? | **ANSWERED 2026-10-16:** Flash's own list (ADR-183 ACCEPTED, BT-R11 PASS: radio found with `neverForLocation`). |

## 13. Not in this plan

Voice notes and any recorded audio over the data link; photos and files over radio; real-time digital voice; 9600 baud; other radios than the VR-N76 family (the KISS layer is generic, but no other radio is tested); Linux Bluetooth pairing; iOS; Wi-Fi Direct. Parked ideas stay in `docs/FUTURE-OPTIMIZATION.md`.
