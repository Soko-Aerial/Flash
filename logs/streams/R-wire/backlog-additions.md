# Stream R-wire Backlog Additions

Reserved test ID prefix: `RADWIRE-`

## RADWIRE-D01: Complete Gateway Lifecycle on Android Phone with VR-N76
- **Setup:** Android test device (Pixel / Samsung / Belfone) with Bluetooth ON. Physical Vero Telecom VR-N76 turned on, battery >50%, KISS TNC enabled in radio menu (Menu -> KISS TNC -> ON). Radio paired or unpaired in phone Bluetooth settings.
- **Steps:**
  1. Open Flash app -> Settings -> Radio Gateway.
  2. Toggle master switch ON. If prompted for Nearby devices permission (Android 12+), tap Allow.
  3. Verify checklist card is displayed. Tap "Scan for radios" (or acknowledge checklist).
  4. Observe VR-N76 auto-detected or listed in scan results with Bluetooth address and category `KNOWN_RADIO`.
  5. Tap "Use this radio" / Confirm. If unpaired, accept pairing dialog (PIN 1234 or auto SSP).
  6. Observe state transitions: `CONNECTING` -> `CONNECTED`. Status line displays "Connected to VR-N76".
  7. Verify persistent foreground notification is posted: "Flash Radio Gateway" / "Connected to VR-N76".
  8. Toggle master switch OFF. Observe disconnect, notification dismissed, state transitions to OFF.
  9. Force-stop Flash app (`am force-stop` or swipe away from Recents). Reopen Flash.
  10. Verify Radio Gateway switch is OFF at boot; no background service or Bluetooth connection is opened.
- **Pass:** Gateway connects to VR-N76 over SPP/RFCOMM, shows honest connected status and notification, disconnects cleanly, and does not wake at boot while OFF.
- **Source:** Plan R §6 & §7, Task 2 & Task 6.
- **Status:** TODO

## RADWIRE-D02: Complete Gateway Lifecycle on Windows Desktop with VR-N76
- **Setup:** Windows 10/11 PC with Bluetooth adapter. VR-N76 radio turned on, KISS TNC enabled. Radio paired in Windows Settings (`ms-settings:bluetooth`).
- **Steps:**
  1. Launch Flash Desktop app -> Settings -> Radio Gateway.
  2. Toggle master switch ON.
  3. Observe auto-detect finding the paired VR-N76 peripheral with resolved outgoing SPP COM port (e.g. COM16).
  4. Tap "Confirm" to connect.
  5. Observe state transition to `CONNECTED`. Status displays "Connected to VR-N76" with active COM port and zero failure reasons.
  6. Toggle master switch OFF. Observe clean serial port closure, state transitions to OFF.
  7. Close Flash Desktop app completely. Inspect Task Manager to ensure no zombie background processes.
  8. Reopen Flash Desktop app. Verify radio gateway remains OFF and COM port is not opened.
- **Pass:** Desktop connects to VR-N76 over Win32 dynamic COM port, shows connected status, closes cleanly without port lock, and remains OFF on restart.
- **Source:** Plan R §6, Task 3 & Task 6.
- **Status:** TODO

## RADWIRE-D03: Send Test Message RF Transmission with Regulatory Warning
- **Setup:** Phone or PC connected to VR-N76 on authorized VHF/UHF test frequency (e.g. amateur 2m simplex 144.390 MHz or 433.000 MHz with dummy load / operator license). An external HT or SDR monitor tuned to the frequency.
- **Steps:**
  1. Open Radio Gateway screen when connected to VR-N76.
  2. Confirm regulatory authorization dialog (record operator callsign or authorization affirmation).
  3. In Diagnostics card, tap "Send test message".
  4. Observe explicit transmit warning modal displayed: "This will transmit RF on your current radio frequency. Ensure you are authorized to transmit on this frequency."
  5. Tap "Confirm & Transmit".
  6. Observe radio PTT LED turns red (transmitting) for ~200ms.
  7. Verify UI displays "Test message sent successfully" and TX counter increments by 1.
  8. Verify receiving SDR / HT hears AX.25 UI frame packet burst.
- **Pass:** Test message transmits RF only after explicit user confirmation, increments TX counter, and displays success in UI.
- **Source:** Plan R §8 (Task 6, Rule 4).
- **Status:** TODO

## RADWIRE-D04: Remembered Radio Preserved Across Upgrade Install (adb install -r)
- **Setup:** Android phone with Flash app installed and paired/connected to VR-N76. Radio is remembered in `flash_radio_settings`.
- **Steps:**
  1. Confirm VR-N76 is remembered in Flash app.
  2. Build update APK: `./gradlew :app:assembleDebug` or use existing build.
  3. Perform upgrade install preserving app data: `adb install -r app-debug.apk`.
  4. Launch Flash app.
  5. Navigate to Settings -> Radio Gateway.
- **Pass:** `rememberedRadioName` ("VR-N76") and `rememberedRadioAddress` are retained across the upgrade without re-pairing or re-scanning.
- **Source:** Task 6, Plan R §6.
- **Status:** TODO

## RADWIRE-D05: Profile M Tactical Encryption Authorization Enforcement on Hardware
- **Setup:** Phone or PC connected to VR-N76.
- **Steps:**
  1. Open Radio Gateway screen.
  2. Without confirming regulatory authorization, attempt to select "Profile M (Encrypted)".
  3. Verify selection is refused or prompts the authorization dialog.
  4. Enter operator callsign/license affirmation and confirm.
  5. Select Profile M. Verify Profile M is now active.
  6. Power cycle the radio. Verify radio reconnects with Profile M active.
- **Pass:** Profile M cannot be selected without prior authorization confirmation, and authorization persists across radio disconnects.
- **Source:** Plan R §8, ADR-178, Task 6.
- **Status:** TODO
