# Owner Runbook: Hardware Radio Audio Bridge Spike (BT-R10)

**Document:** `logs/streams/R-audio/runbook-BT-R10.md`  
**Purpose:** Numbered manual test procedure for the repository owner with the physical Vero Telecom VR-N76 / BTECH UV-Pro radio, Windows 11 PC, and Android test phone to evaluate Bluetooth walkie-talkie audio bridging (`docs/plans/RADIO-GATEWAY-PLAN.md` §9 and §10 Phase R4).  
**Status:** DRAFT / PENDING OWNER EXECUTION (Device checks owed: `BT-R10`, `RADAUD-01`..`RADAUD-04`).  

---

## ⚠️ Safety, Regulatory & Legal Notice (MANDATORY)

1. **RF Transmission & Licensing:**
   - Any RF transmission from the VR-N76 must strictly adhere to local telecommunications regulations.
   - If operating on amateur radio bands (e.g. 144.625 MHz used in `EXP-023`), transmission is legal **only** with a valid amateur radio operator license and callsign in clear text (Profile A).
   - Encrypted transmissions (Profile M) are **strictly prohibited** on amateur frequencies in most jurisdictions.
   - For bench testing without off-air radiation, terminate the radio's antenna port into a **50-ohm RF dummy load** with appropriate power rating (≥ 10 W).
2. **Software Safety Contract:**
   - The diagnostic tools provided in this stream (`radioAudioProbe` on desktop, `RadioAudioActivity` on Android) **NEVER** play audio tones, pipe microphone streams, or key the transmitter automatically.
   - Any action capable of keying the transmitter (e.g., testing VOX with voice audio) is strictly a manual step executed by the human owner in this runbook.

---

## 1. Objectives

This runbook provides reproducible steps to answer the four core questions of Plan R §9 (`BT-R10`):
1. **Question 1 (HFP Connection & Roles):** Does the phone or PC establish a working Bluetooth Hands-Free audio connection to the radio, and in which role (Audio Gateway vs. Hands-Free unit)?
2. **Question 2 (Transmitter Keying):** How can the transceiver be keyed into transmit (TX) from Flash (VOX on Bluetooth audio, Bluetooth command, or hardware button)?
3. **Question 3 (Audio Quality & Inbound Audio Routing):** What is the audio quality of the narrow Bluetooth voice channel (8 kHz CVSD / 16 kHz mSBC), and does incoming analog RF audio received by the radio reach the phone/PC app?
4. **Question 4 (Turn-Taking & PTT Floor Arbitration):** How does analog simplex radio traffic fit Flash's distributed digital push-to-talk turn-taking?

---

## 2. Hardware and Software Setup

1. **Vero Telecom VR-N76 Handheld Transceiver:**
   - Power on radio. Verify battery > 50%.
   - Tune to an authorized frequency or attach 50-ohm dummy load.
   - Verify Bluetooth is enabled: *Menu -> Settings -> Bluetooth -> ON*.
   - Verify VOX settings: *Menu -> Settings -> VOX -> ON* (note the VOX sensitivity level 1–9).
2. **Windows 11 PC:**
   - Ensure PC Bluetooth is turned ON.
   - Open command line in `E:\FlashWork\R-audio`.
3. **Android Test Phone:**
   - Ensure phone Bluetooth is turned ON.
   - Ensure developer mode and USB debugging are enabled.
4. **Secondary Receiver / Monitor (Optional but Recommended):**
   - A second analog receiver or Software Defined Radio (SDR) tuned to the test frequency to monitor transmitted audio quality without acoustic feedback.

---

## 3. Numbered Execution Steps

### Part A: Windows PC Audio Investigation

#### Step A1 — Read-Only Audio Endpoint Probe
1. Ensure the VR-N76 is powered on and within Bluetooth range of the PC.
2. In PowerShell, execute the desktop audio probe:
   ```cmd
   tools\flash-gradle.cmd :desktop:radioAudioProbe -PradioArgs="--json --out report-audio-probe.json"
   ```
3. Observe console output:
   - Inspect **[2/3] Windows Core Audio Endpoints**:
     - Does `VR-N76 Hands-Free HF` appear in the endpoint list?
     - What is its reported `State`: `ACTIVE (0x1)`, `UNPLUGGED (0x8)`, or `NOT_PRESENT (0x4)`?
     - What is its `Connectable` status?
   - Inspect **[3/3] Radio Gateway Audio Diagnosis**:
     - Record the diagnosis line explaining why the HFP SCO connection is idle/unplugged.
4. Save `report-audio-probe.json`.

#### Step A2 — Windows Sound Control Panel Inspection
1. Press `Win + R`, type `mmsys.cpl`, and press Enter.
2. Under the **Playback** tab:
   - Check if `Headset (VR-N76 Hands-Free HF Audio)` is present.
   - Observe its status: Is it "Default Device", "Ready", or "Disconnected"?
3. Under the **Recording** tab:
   - Check if `Headset Microphone (VR-N76 Hands-Free HF Audio)` is present.
   - Observe its status.
4. Right-click the VR-N76 Playback entry and select **Connect**:
   - Record whether Windows connects immediately or displays "Failed to connect" / no change.

#### Step A3 — Active WASAPI Communications Stream Test (Owner Explicit Action)
*Note: Windows HFP endpoints remain UNPLUGGED until an application requests a communications stream.*
1. Open Windows Sound Recorder or a test VoIP application (e.g. Windows Voice Recorder).
2. Set the input and output device to `VR-N76 Hands-Free HF`.
3. Start recording or open the audio stream:
   - Does Windows Audio Endpoint Builder transition the endpoint to `ACTIVE`?
   - Listen to the radio loudspeaker: Does Windows system audio or microphone sidetone play through the radio?
4. Record observation in Table §5 under Q1.

#### Step A4 — VOX Transmitter Keying Test on Windows
*CAUTION: RF transmission will occur if VOX triggers. Confirm dummy load or legal frequency.*
1. With the VR-N76 VOX enabled (*Menu -> Settings -> VOX -> ON*):
2. Play a test spoken phrase ("Flash radio audio test one two three") through the VR-N76 Hands-Free playback device at normal volume.
3. Observe the VR-N76:
   - Does the top red TX LED illuminate?
   - How long is the syllabic delay before the transmitter keys (estimated ms clipped from first syllable)?
   - When speech stops, how long does the transmitter hang before unkeying (hang time / squelch tail)?
4. Record observation in Table §5 under Q2 and Q3.

---

### Part B: Android Phone Audio Investigation

#### Step B1 — Launch Android Diagnostic Screen
1. Connect test phone via USB.
2. Launch the debug audio probe activity:
   ```cmd
   adb shell am start -n com.transfer.flash/com.transfer.flash.debug.radioaudio.RadioAudioActivity
   ```
3. Observe screen titled **"Radio Audio Probe (BT-R10)"**.

#### Step B2 — Grant Permissions
1. Tap the **"Grant Perms"** button.
2. Accept the runtime permissions: "Nearby devices" (`BLUETOOTH_CONNECT`) and "Microphone" (`RECORD_AUDIO`).
3. Verify status card displays `"Permissions: Granted"`.

#### Step B3 — Inspect Headset Profile and Audio Devices
1. Observe the **"Connected Headset Devices"** section:
   - Is `VR-N76 Hands-Free HF` listed under connected headset profile devices?
   - Does `Headset Audio Connected` show `YES (SCO Link Up)` or `NO`?
2. Observe the **"Audio Devices"** list:
   - Is there an entry with `Type: BLUETOOTH_SCO` marked with the green badge `[SCO READY]`?

#### Step B4 — Test Bluetooth SCO Routing (Owner Explicit Tap)
1. Tap the green **"Route SCO"** button.
2. Observe Status Card updates:
   - On Android 12+ (API 31+): Does it show `"Active: Routed via setCommunicationDevice(VR-N76)"`?
   - Does `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` broadcast show `"CONNECTED"`?
3. Tap **"Clear Route"**:
   - Does it return cleanly to `"Idle (Normal Audio Mode - Route Cleared)"`?
4. Record observation in Table §5 under Q1.

#### Step B5 — Inbound RF Discriminator Audio Routing Test
*Tests whether analog RF received by the radio passes over Bluetooth SCO to the phone.*
1. While SCO is routed (Step B4 Active):
2. Using a second radio (or opening squelch on the test frequency), transmit a test voice transmission to the VR-N76.
3. Observe:
   - Does incoming analog voice play out of the VR-N76 body speaker, OR does it route into the Bluetooth SCO stream?
   - Open a voice memo or sound recording app on the phone: Does the recorded track contain the incoming RF audio received by the radio?
4. Record observation in Table §5 under Q3.

#### Step B6 — Outbound Voice & VOX Keying on Android
1. While SCO is routed:
2. Speak clearly into the phone microphone.
3. Observe the VR-N76:
   - Does Bluetooth VOX trigger? Does the red TX LED illuminate?
   - If VOX keys, listen on the secondary monitor receiver: Is speech clear, muffled, or distorted?
   - What is the subjectively perceived latency (by ear) between speaking and hearing the transmission on the monitor receiver?
4. Tap **"Clear Route"** immediately upon finishing.

---

### Part C: Turn-Taking & Turn-Around Evaluation (Question 4)

1. **Simplex Collision (Doubling) Scenario:**
   - While Flash digital PTT is routed to the radio, what happens if an external station transmits on RF simultaneously?
   - Does the radio prioritize its local RF receiver, or does local Bluetooth audio force transmitter keying over the incoming signal?
2. **Squelch Tail & Turn-Around Delay:**
   - When releasing PTT in Flash, measure the delay (in milliseconds) before the radio unkeys and its receiver is ready to receive an incoming reply.
   - If turn-around delay exceeds 300 ms, rapid digital turn-taking in Flash PTT will experience clipped initial responses.

---

## 4. Evidence to Collect

1. Output of `tools\flash-gradle.cmd :desktop:radioAudioProbe` (saved as `report-audio-probe.json`).
2. Screenshot of `RadioAudioActivity` on Android showing SCO routing result.
3. Logcat capture: `adb logcat -s RadioAudioActivity AudioManager:V AudioService:V`.
4. Audio recording from secondary monitor receiver during VOX keying (if available).

---

## 5. Results Table (BT-R10)

| # | Question / Scenario | Result (PASS / FAIL / UNKNOWN) | Evidence / Observed Log Lines / Notes |
|---|---|---|---|
| **Q1** | Does the phone or PC get a working Bluetooth Hands-Free audio connection to the radio, and in which role? | **UNKNOWN (Pending Owner Runbook)** | Diagnostic tools ready. Windows probe shows VR-N76 audio endpoint in BTHHFENUM as UNPLUGGED (0x8); requires active WASAPI communications stream. Android `setCommunicationDevice` ready for test. |
| **Q2** | How is the transmitter keyed from Flash (VOX, Bluetooth command, or nothing)? | **UNKNOWN (Pending Owner Runbook)** | No standard Bluetooth HFP PTT command exists. VOX is supported in firmware v0.8.0+, but requires empirical test for syllabic clipping and latency. |
| **Q3** | What is the audio quality of the narrow Bluetooth voice channel (8 kHz / 16 kHz), and does incoming radio audio reach the app? | **UNKNOWN (Pending Owner Runbook)** | Narrowband CVSD 8 kHz expected; empirical test required to determine if received RF discriminator audio routes to Bluetooth SCO uplink. |
| **Q4** | How does analog radio traffic fit Flash's push-to-talk turn-taking? | **FAIL (Architectural Incompatibility)** | Uncoordinated simplex analog RF lacks floor arbitration, collision avoidance, and identity tokens. Bridging creates severe glare, clipping, and acoustic feedback risks. |

---

## 6. Decision Template for the Owner

Based on the empirical findings of this runbook, select one of the following paths:

### [ ] Option 1: BUILD Modes 3 and 5 (Not Recommended)
- Build a full analog walkie-talkie bridge cross-patching Flash PTT into the VR-N76 Bluetooth audio channel.
- Requires: Dedicated VoIP/SCO background routing service, VOX threshold calibration, and simplex half-duplex arbitration state machine.
- Risk: High complexity, VOX syllabic clipping, unencrypted open RF broadcasts, acoustic loopback.

### [ ] Option 2: RESCOPE Mode 3 (Peripheral Audio Accessories Only)
- Re-scope Mode 3 away from analog RF cross-patching. Instead, support Bluetooth PTT accessories (e.g. BHM-88 wireless speaker mic or BS-PTT finger button) as external input devices for Flash's **digital LAN/Wi-Fi PTT sessions** only.
- Analog RF remains separate from Flash digital audio.

### [ ] Option 3: DROP Modes 3 and 5 (RECOMMENDED)
- Drop Modes 3 (Walkie-Talkie Bridge) and 5 (All-in-One Dual Mode) from Plan R.
- Focus strictly on digital data modes: **Mode 1** (Field Companion Chat over KISS TNC), **Mode 2** (Base Station Relay), and **Mode 4** (Tactical GPS Radar).
- Rationale: Flash's core value is encrypted, verified, peer-to-peer data and digital PTT. Bridging raw analog FM over 8 kHz SCO introduces severe regulatory and user-experience compromises without providing cryptographic security.
