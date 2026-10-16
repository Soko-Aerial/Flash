# Owner Runbook: Hardware Radio Spike BT-R11

**Document:** `logs/streams/R-spike/runbook-BT-R11.md`  
**Purpose:** Numbered manual test procedure for the repository owner with the physical Vero Telecom VR-N76 / BTECH UV-Pro radio, Windows 11 PC, and Android test phone.  
**Safety Notice:** Strictly NO KISS data frames or serial bytes are transmitted by these tools. RF keying will NOT occur during this runbook.

---

## 1. Objectives

This runbook answers the five questions of `BT-R11` (`docs/plans/RADIO-GATEWAY-PLAN.md` §6 and §11):
1. Can the radio be found by an active Bluetooth inquiry scan, and how is it placed into discoverable mode?
2. What pairing mechanism does the radio require (fixed PIN e.g. 0000/1234, SSP Numeric Comparison / Just Works, or none)?
3. Does Windows inquiry, pairing (`BluetoothAuthenticateDevice`), and service activation (`BluetoothSetServiceState`) succeed through Win32 JNA, and does Windows spawn a virtual COM port?
4. In Android, does the radio appear during discovery when using `BLUETOOTH_SCAN` with `neverForLocation`? Does in-app pairing via `BluetoothDevice.createBond()` work without opening OS settings?
5. Does Windows Registry enumeration (`HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM`) reliably map the assigned virtual COM port to the radio's physical Bluetooth address?

---

## 2. Hardware Preparation

1. **VR-N76 Handheld Transceiver:**
   - Power on radio.
   - Verify battery > 50%.
   - Tune to a legal VHF/UHF frequency (or attach 50-ohm dummy load).
   - Enter radio menu: *Menu -> General Settings -> KISS TNC -> Enable*.
   - **Discoverability state:**
     *VGC VR-N76 manual section on Bluetooth settings states Bluetooth is enabled in General Settings; whether a dedicated discoverable mode exists is unknown: try (1) Menu -> General Settings -> Bluetooth -> Enable/Search, (2) if not found, power-cycle the radio while not connected to any phone/PC, (3) note whether it is automatically discoverable whenever disconnected.*
2. **Windows 11 PC:**
   - Ensure PC Bluetooth is turned ON in Windows Settings.
   - Keep command line in `E:\FlashWork\R-spike`.
3. **Android Test Phone:**
   - Ensure phone Bluetooth is turned ON.
   - Do NOT pair the radio in phone Bluetooth settings beforehand (or unpair it if already paired, so in-app discovery/bonding can be validated).

---

## 3. Numbered Execution Steps

### Part A: Windows PC Verification

#### Step A1 — Read-Only Baseline Scan
1. Open PowerShell in `E:\FlashWork\R-spike`.
2. Run the read-only spike tool:
   ```cmd
   tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--json --out report-readonly.json"
   ```
3. Observe console output:
   - Check section `[1/3] Enumerating Serial Ports`: Confirm existing COM ports are listed.
   - Check section `[2/3] Enumerating Known/Remembered Bluetooth Devices`: Record if VR-N76 appears in Windows remembered devices.
   - Check section `[3/3] Mapping COM Ports`: Observe if `COM16` (or current radio port) maps to MAC `**:**:**:01:35:B5` via `REGISTRY_BTHENUM`.
4. Copy the JSON report or save `report-readonly.json`.

#### Step A2 — Active Windows Inquiry, Pairing & Service Enable (Owner Only)
*Note: This step requires the explicit owner safety flag. It will prompt and wait for ENTER before each operation.*
1. Put VR-N76 into discoverable/pairing state (as identified in prep).
2. Run the active spike tool:
   ```cmd
   tools\flash-gradle.cmd :desktop:radioSpike -PradioArgs="--active --i-am-the-owner-and-the-radio-is-on"
   ```
3. Follow the interactive steps:
   - **Prompt 1 (Inquiry scan):** Press ENTER. Observe if the VR-N76 is detected during the 5.12s inquiry scan. Record its listed name and masked address.
   - **Prompt 2 (Device index):** Enter the index of the radio.
   - **Prompt 3 (Authenticate/Pair):** Press ENTER. Observe whether Windows prompts for a PIN or completes automatically. If a PIN is prompted on the PC or radio, record what PIN was requested (try `1234` or `0000` if prompted).
   - **Prompt 4 (Enable SPP Service):** Press ENTER. Win32 `BluetoothSetServiceState` is called for UUID `00001101-0000-1000-8000-00805F9B34FB`.
   - **Step 4 (Port re-enumeration):** Observe the output table. Verify whether a new virtual COM port was created and assigned to the radio.
4. Record the outcome and copy console log lines.

---

### Part B: Android Phone Verification

#### Step B1 — Launch Android Debug Radio Spike Screen
1. Connect test phone via USB.
2. Launch the debug spike activity:
   ```cmd
   adb shell am start -n com.transfer.flash/com.transfer.flash.debug.radiospike.RadioSpikeActivity
   ```
3. Observe the screen titled **"Radio Spike (BT-R11 Debug Tool)"**.

#### Step B2 — Test Permission Request at Point of Use
1. Tap the **"Permissions"** button.
2. Observe system permission dialog:
   - On Android 12+ (API 31+): Verify dialog asks for **"Nearby devices"** (BLUETOOTH_SCAN and BLUETOOTH_CONNECT).
   - Verify that **NO location permission** dialog appears.
   - Grant permission.
3. Observe the top status card:
   - Must show: `"Permissions: Granted"`
   - Bonded devices list immediately updates with existing paired devices and their SDP UUIDs.

#### Step B3 — Active Discovery Scan with `neverForLocation`
1. Ensure VR-N76 is powered on and discoverable.
2. Tap **"Scan Radios"**.
3. Observe:
   - Button turns red and shows countdown timer: `"Stop (12s)"` -> `11s` -> ... -> `0s`.
   - Status card displays `"Scan: Running active discovery..."`.
   - Discovered devices list populates.
4. Verify Discovery Result:
   - Does VR-N76 appear in the list?
   - Does it display **Rank 1 [RADIO]** with a highlighted container?
   - Is its address masked to the last 3 bytes (`**:**:**:01:35:B5`)?
   - Does it display signal RSSI (e.g. `-45 dBm`)?
5. Verify Scan Lifecycle:
   - Tap "Scan Radios" again, then immediately press the Android Back/Home button to leave the screen.
   - Check `adb logcat -s RadioSpikeActivity`: Confirm log line `"Stopped: scan aborted and receivers unregistered on leaving screen"`.

#### Step B4 — In-App Pairing via `BluetoothDevice.createBond()`
1. From the discovered list in `RadioSpikeActivity`, find the VR-N76 entry.
2. Tap the **"Pair"** button next to it.
3. Observe the OS overlay:
   - Note if a system dialog prompts for PIN / passkey confirmation.
   - Enter PIN if prompted (or confirm numeric comparison).
4. Observe Status Card:
   - Status updates: `"Pairing: In progress with VR-N76..."` -> `"Pairing: Successfully paired with VR-N76."`
   - The radio automatically moves into the **Bonded Devices** section.
   - Verify whether SPP UUID `00001101-0000-1000-8000-00805F9B34FB [SPP]` is listed under its Service UUIDs.

---

## 4. Evidence to Collect

1. **Windows Tool Logs:**
   - Console text output from `tools\flash-gradle.cmd :desktop:radioSpike` (Read-Only).
   - Console text output from Active Mode pairing execution.
   - File `report-readonly.json`.
2. **Android Screen Capture:**
   - Screenshot of `RadioSpikeActivity` showing the discovered VR-N76 with Rank 1 badge.
   - Screenshot of the Bonded Devices section showing SPP UUID.
3. **Photographs / Observations:**
   - Any PIN code prompt appearing on radio LCD or phone/PC screen.

---

## 5. Results Table (Fill in by Owner)

| # | Question / Scenario | Result (PASS / FAIL / UNKNOWN) | Evidence / Observed Log Lines / Notes |
|---|---|---|---|
| **Q1** | Can VR-N76 be found by inquiry scan, and how is it made discoverable? | *UNKNOWN (awaiting runbook run)* | Menu path: ____________________ |
| **Q2** | What pairing authentication does VR-N76 require? | *UNKNOWN (awaiting runbook run)* | PIN required: [ ] None [ ] 1234 [ ] 0000 [ ] Other: _____ |
| **Q3** | Windows: Do JNA Win32 inquiry, pairing, and SPP enable work, and does virtual COM port appear? | *UNKNOWN (awaiting runbook run)* | COM port assigned: _____ |
| **Q4** | Android: Does VR-N76 appear with `neverForLocation`? Does `createBond()` work in-app? | *UNKNOWN (awaiting runbook run)* | Nearby prompt observed: [ ] Yes [ ] No; Bond status: _____ |
| **Q5** | Windows: Does registry enumeration (`BTHENUM`) reliably map COM port to Bluetooth MAC? | **PASS (verified on this PC)** | `COM16` mapped to `**:**:**:01:35:B5` via `REGISTRY_BTHENUM` Device Parameters |
| **PIN** | What is the default PIN code of the VR-N76? | *UNKNOWN (awaiting runbook run)* | PIN: _____ |
