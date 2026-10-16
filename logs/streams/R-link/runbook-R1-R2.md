# Owner Runbook: Hardware Radio Gateway R1 & R2

**Document:** `logs/streams/R-link/runbook-R1-R2.md`  
**Purpose:** Numbered manual test procedure for the repository owner with the physical Vero Telecom VR-N76 / BTECH UV-Pro radio, Windows 11 PC, and Android test phone.  
**Safety Notice:** Everything in this runbook except Step W6 sends nothing on air. Step W6 TRANSMITS once and needs a frequency and an authorisation that allow the profile you use (the test radio's 144.625 MHz is an amateur band: use Profile A with your callsign there, never Profile M). Corrected 2026-10-16: the earlier text said test messages are suppressed on real links; that rule was replaced (ADR-189 amendment).

---

## 1. Objectives

This runbook validates the end-to-end Phase R1 ("Find, pair and connect") and R2 gateway logic on physical hardware:
1. **Rule 3 Confirmation Gate:** Radio candidate is discovered and preselected, but the port/link is strictly NOT opened until user confirms.
2. **Dynamic COM Mapping (Windows):** Windows registry BTHENUM interrogation resolves the radio's virtual COM port dynamically even if port numbers change.
3. **In-App Discovery & Bonding (Android):** Radio appears during in-app scan without location permission, and bonds via `BluetoothDevice.createBond()`.
4. **Automatic Reconnection after Power-Cycle:** Radio is turned off, dropped link is detected with backoff, radio is turned back on, and link recovers automatically without user intervention.
5. **Reconnect Limit:** Sustained disconnect reaches limit (5 attempts) and displays a clean "Reconnect" button.
6. **Background Service Compliance (Android 14/15):** `RadioLinkService` displays an honest, ongoing foreground notification (`connectedDevice` FGS type) while connected, and cleanly terminates when disconnected.
7. **Rule 4 Transmission Safety:** Test message button suppresses live transmission and logs a PROBE line instead of transmitting over the air.

---

## 2. Hardware Preparation

1. **VR-N76 Handheld Transceiver:**
   - Power on radio. Battery > 50%.
   - Terminate antenna port with a 50-ohm dummy load (or tuned antenna on authorized frequency).
   - Menu -> General Settings -> KISS TNC -> Enable.
2. **Windows 11 PC:**
   - Bluetooth ON.
3. **Android Test Phone:**
   - Bluetooth ON, Android 12+ (tested on Android 14/15 if available).

---

## 3. Numbered Execution Steps

### Part A: Windows Desktop Gateway Verification

#### Step W1 — Zero-Config Cold Start & Checklist
1. Launch Flash Desktop on the PC.
2. Open Settings -> Radio Gateway.
3. Toggle switch ON.
4. **Pass:** Screen displays checklist: "Ensure your radio is ready: Turn on your radio and enable KISS TNC in the radio menu, then continue." Port is NOT opened (check with Process Explorer / serial monitor: COM port is free).

#### Step W2 — Rule 3 Candidate Confirmation Gate
1. Tap "Checklist Acknowledged" / "Find my radio".
2. If the VR-N76 is already paired in Windows:
   - Screen displays single candidate: "Found radio: VR-N76. Confirm to connect to this radio."
3. Inspect Windows COM port state.
4. **Pass:** COM port remains CLOSED. Link is NOT opened until the user clicks "Confirm".

#### Step W3 — Initial Connection & Serialization
1. Click "Confirm".
2. Observe status line.
3. **Pass:** Status changes to "Opening the radio serial link" -> "Connected to VR-N76. Ready for radio communication." `radio.properties` in `~/.flash/` records `remembered.bluetooth_address` and `remembered.display_name`. No keys are present in the file.

#### Step W4 — Power-Cycle Recovery
1. While connected, turn the VR-N76 power switch OFF.
2. Observe status line in Flash:
   - Changes to: "Radio disconnected, trying again (attempt 1)..."
3. Turn the VR-N76 power switch back ON.
4. Wait 5–10 seconds.
5. **Pass:** Flash automatically reconnects without any button press. Status returns to: "Connected to VR-N76. Ready for radio communication."

#### Step W5 — Sustained Disconnect Limit
1. Turn the VR-N76 power switch OFF and leave it off.
2. Observe retry attempts 1 through 5 with exponential backoff.
3. **Pass:** After attempt 5, retries cease. Status displays: "Radio disconnected after repeated attempts. Ensure your radio is turned on and in range, then tap Reconnect."

#### Step W6 — Test message gates, then one real transmit (THIS TRANSMITS; ask before running)
1. With NO authorisation recorded, request a test message with the warning confirmed. **Pass:** the radio does not key; probe `radio.tx.test_message result=NOT_AUTHORISED`.
2. Record the authorisation, request a test message WITHOUT confirming the warning. **Pass:** the radio does not key; probe `result=NOT_CONFIRMED`.
3. Only on a legal frequency with Profile A: confirm the warning and send. **Pass:** the radio keys once; probe `result=SENT`; a second radio or scanner may hear the short frame.

---

### Part B: Android Phone Gateway Verification

#### Step M1 — Nearby Devices Permission at Point of Use
1. Launch Flash on the Android phone.
2. Open Settings -> Radio Gateway.
3. Toggle switch ON and proceed to Find Radio.
4. Tap "Permissions".
5. **Pass:** System dialog requests "Nearby devices" permission (Android 12+). NO location permission is requested.

#### Step M2 — In-App Discovery & Bonding
1. Unpair VR-N76 if previously paired in phone OS settings.
2. Put radio in discoverable state.
3. In Flash, tap "Scan Radios".
4. **Pass:** 12-second countdown begins. VR-N76 appears in discovered list ranked #1 [RADIO]. Tapping "Pair" triggers bonding and pairs successfully without leaving Flash.

#### Step M3 — Android 14/15 FGS Notification (`connectedDevice`)
1. Connect to the paired VR-N76.
2. Background the Flash app.
3. Pull down Android notification shade.
4. **Pass:** Ongoing notification appears: "Radio Gateway Active — Connected to VR-N76". Notification channel is "Radio Gateway" (Low importance, non-audible). App process is not killed by Android memory manager while radio link is alive.

#### Step M4 — Gateway Disconnect & FGS Termination
1. Open Flash and toggle the Radio Gateway switch OFF.
2. **Pass:** Foreground service immediately shuts down (`stopSelf()`); notification vanishes from shade. Bluetooth link is cleanly closed.

---

## 4. Hardware Results Table

| Step | Platform | Description | Expected Result | Actual Result | Status |
|---|---|---|---|---|---|
| W1 | Windows | Switch On & Checklist | Checklist displayed, COM port not opened | *Pending Owner Run* | TODO |
| W2 | Windows | Rule 3 Candidate Gate | Candidate shown, port remains closed until confirm | *Pending Owner Run* | TODO |
| W3 | Windows | Dynamic Port Connect | Dynamic registry BTHENUM resolution & connect | *Pending Owner Run* | TODO |
| W4 | Windows | Power-Cycle Recovery | Auto-reconnect after radio turned off and on | *Pending Owner Run* | TODO |
| W5 | Windows | Reconnect Limit | Retrying stops after 5 attempts, shows Reconnect | *Pending Owner Run* | TODO |
| W6 | Windows | Rule 4 Safety | Live test frame suppressed on real RF | *Pending Owner Run* | TODO |
| M1 | Android | Permission Prompt | Nearby devices only, no location | *Pending Owner Run* | TODO |
| M2 | Android | Scan & Pair | Discovers radio, in-app pairing succeeds | *Pending Owner Run* | TODO |
| M3 | Android | FGS Notification | Honest connectedDevice notification while active | *Pending Owner Run* | TODO |
| M4 | Android | Clean Shutdown | Switch off removes FGS notification and closes link | *Pending Owner Run* | TODO |
