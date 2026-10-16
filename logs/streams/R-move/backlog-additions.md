# Test Backlog Additions — Stream R-move

The following tests require physical hardware (Android phones, Bluetooth/USB KISS TNCs, transceivers) or real serial hardware and cannot be verified in an automated CI/mock environment. Per stream guidelines, these are recorded with `Status: TODO`. No code in this stream has been marked "device-verified".

---

## RADMOVE-01 — Desktop Serial Port Enumeration with Real Hardware

### Area
Hardware Radio / Desktop Serial Catalog (`:core:radio` / `:desktop`)

### Setup
- Windows 10/11 desktop machine.
- Physical USB-to-UART / serial adapter (e.g. CP2102, FTDI, or CH340) or paired Bluetooth SPP TNC (e.g. VR-N76 / Mobilinkd TNC3/4).
- Device connected to USB or paired in Windows Bluetooth settings, assigned a COM port (e.g. `COM16`).

### Steps
1. Execute `tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"`.
2. Inspect stdout output table for detected serial and Bluetooth ports.

### Pass
- The CLI lists the physical COM port with its port description (e.g. `COM16\tOTHER\tSPP Dev` or `USB-to-UART Bridge`).
- Exit code is 0.
- No serial port is opened or held in an exclusive lock during enumeration.

### Source
Plan R §4.4, prompt Task 2.g.

### Status
TODO

---

## RADMOVE-02 — Android RFCOMM Socket Connection to Physical Radio

### Area
Hardware Radio / Android Bluetooth Catalog (`:core:radio` / `:app`)

### Setup
- Android device running Android 12+ (API 31+) with Bluetooth enabled and `BLUETOOTH_CONNECT` permission granted.
- Physical handheld radio with built-in Bluetooth KISS TNC (e.g. VERO VR-N76, BTECH UV-Pro).
- Radio paired in Android OS Settings.

### Steps
1. Open Flash app on Android device.
2. Navigate to Radio settings / gateway.
3. Initiate connection to the paired radio.
4. Verify logcat for `BluetoothSocket.connect()` completion.

### Pass
- Logcat shows RFCOMM connection established over SPP UUID `00001101-0000-1000-8000-00805F9B34FB`.
- State transitions to `Connected`.
- Zero unhandled `SecurityException` or socket timeout exceptions.

### Source
Plan R §3, §4.1 (`AndroidBluetoothCatalog.kt`).

### Status
TODO

---

## RADMOVE-03 — Over-the-Air / Loopback KISS Frame Exchange with Physical TNC

### Area
Hardware Radio / Framing & TNC Driver (`:core:radio`)

### Setup
- Two physical KISS TNCs (or one TNC connected to a dummy load or attenuated RF channel).
- Radio link configured on authorized amateur frequency with legal power and callsign.

### Steps
1. Establish link between Flash and physical TNC.
2. Send a single diagnostic test frame with explicit user authorization.
3. Observe raw bytes exchanged on the serial/Bluetooth stream.

### Pass
- TNC responds with KISS data frame or acknowledgment.
- Received frames are framed by `0xC0` (FEND), escape characters `0xDB 0xDC`/`0xDD` are decoded transparently by `KissFrameCodec`.
- CRC-16 (or frame checksum) validates 100%.

### Source
Plan R §3, `KissTncDriver.kt`, `KissFrameCodec.kt`.

### Status
TODO

---

## RADCONL-01 — End-to-End Auto-Detect Flow with Single Paired Radio

### Area
Hardware Radio / Connect State Machine (`:core:radio.connect`)

### Setup
- Android phone or Windows desktop with exactly ONE paired radio (e.g. "VR-N76").
- Gateway switch initially in `Off` state.

### Steps
1. Toggle Radio Gateway switch ON.
2. Complete first-run checklist (e.g. amateur license acknowledgment, battery check).
3. Observe candidate detection.

### Pass
- Because exactly one known radio matches, state machine transitions to `ConfirmCandidate` displaying "VR-N76".
- State machine does NOT open the link or transmit any data prior to user confirmation (Rule 3 & Rule 4).
- Upon user tapping "Connect", link opens and transitions to `Connected`.
- Radio identity (Bluetooth hardware address) is persisted to `RememberedRadioStore`.

### Source
Plan R §6, `RadioConnectStateMachine.kt`, `RadioCandidateRanker.kt`.

### Status
TODO

---

## RADCONL-02 — Ambiguous Candidate Resolution (Multiple Radios Paired)

### Area
Hardware Radio / Candidate Ranker (`:core:radio.connect`)

### Setup
- Physical device with two or more paired radio candidates (e.g. "VR-N76" and "UV-Pro", or multiple Bluetooth serial devices).

### Steps
1. Toggle Radio Gateway switch ON.
2. Complete checklist.
3. Trigger paired device detection.

### Pass
- Auto-detect returns `null` (ambiguous).
- State machine transitions to `ChooseFromList` displaying ranked candidates (known radios at top, serial-capable second, other last).
- User explicitly selects desired radio from list.
- Only the selected radio is connected.

### Source
Plan R §6, `RadioCandidateRanker.kt`.

### Status
TODO

---

## RADCONL-03 — Remembered Radio Reconnect on App Startup

### Area
Hardware Radio / Persistence & Reconnect (`:core:radio.connect`)

### Setup
- Previously paired and connected radio saved in `RememberedRadioStore`.
- Radio powered on and within Bluetooth range.

### Steps
1. Restart Flash app with Radio Gateway switch ON.

### Pass
- State machine bypasses the discovery checklist and immediately initiates `Connecting` to the remembered Bluetooth hardware address (never COM port name).
- On link established, transitions to `Connected`.
- Zero scanning or device picker prompts shown.

### Source
Plan R §6 (Reconnecting flow).

### Status
TODO

---

## RADCONL-04 — Security Profile M Authorization Modal Enforcement

### Area
Hardware Radio / Security Authorisation Gate (`:core:radio.connect`, Plan R §8)

### Setup
- Device connected to radio gateway.
- Security settings in UI.

### Steps
1. View available radio transmission security profiles.
2. Attempt to select Profile M (Encrypted, AES-256-GCM).
3. Observe authorization gate modal prompting for operator callsign/authorization date.
4. Reject or dismiss modal without confirmation; attempt to send message.
5. Re-open modal, enter valid operator confirmation with current timestamp; confirm.

### Pass
- In step 2 & 4: Profile M is disallowed; radio strictly transmits in Profile A (Plaintext signed).
- In step 5: Authorization confirmation is recorded with positive epoch timestamp (`confirmedAtEpochMs > 0`). Profile M is unlocked and selectable.

### Source
Plan R §8, `RadioAuthorisationGate.kt`.

### Status
TODO

---

## RADCONL-05 — Radio Link Lost & Recovery Flow

### Area
Hardware Radio / Connection State Machine (`:core:radio.connect`)

### Setup
- Active link connected to physical radio.

### Steps
1. Power off radio or walk out of Bluetooth range.
2. Observe state machine transition.
3. Inspect displayed error message and next step.
4. Power radio back on and tap Retry.

### Pass
- State transitions to `Failed(LINK_LOST)`.
- UI displays: "Bluetooth connection to radio was lost." with next step: "Ensure the radio is powered on, in range, and tap Retry."
- Tapping Retry transitions to `Connecting` and re-establishes link without requiring re-pairing.

### Source
Plan R §6 (Failure reasons and next steps), `RadioConnectStateMachine.kt`.

### Status
TODO
