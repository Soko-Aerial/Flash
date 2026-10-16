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
  7. Verify ONE persistent foreground notification is posted: title "Radio Gateway Active", text "Connected to VR-N76" (`adb shell dumpsys notification` shows a single entry for channel `flash_radio_gateway_channel`).
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
- **Setup (THIS TRANSMITS; owner only, never an agent):** Phone or PC connected to VR-N76 on a frequency you are licensed for, ideally into a dummy load. An external HT or SDR monitor tuned to the frequency. KNOWN LIMIT (ERROR-684): the frame is sent with source callsign `NOCALL` because the app has no field for your callsign yet, so on an amateur band it does not identify the station. Decide yourself whether one short burst is acceptable; the callsign field is an open owner decision.
- **Steps:**
  1. Open Radio Gateway screen when connected to VR-N76.
  2. Confirm the regulatory authorisation dialog (a single confirmation; there is no callsign field).
  3. In Diagnostics card, tap "Send test message".
  4. Observe the transmit warning dialog (it says the test message will key the transmitter and to connect an antenna or 50-ohm dummy load).
  5. Tap its transmit button. Cancelling must send nothing.
  6. Observe radio PTT LED turns red (transmitting) for ~200ms.
  7. Verify the Diagnostics card reports the test message as sent (success state). No TX counter is promised.
  8. Verify receiving SDR / HT hears AX.25 UI frame packet burst.
- **Pass:** Test message transmits RF only after explicit user confirmation, the UI shows success, and the monitor hears one short AX.25 UI frame (destination FLASH, source NOCALL, text `FLASH TEST DE RADIO`). Log shows `PROBE radio.tx.test_message result=SENT`.
- **Source:** Plan R §8 (Task 6, Rule 4).
- **Status:** TODO

## RADWIRE-D04: Remembered Radio Preserved Across Upgrade Install (adb install -r)
- **Setup (the owner runs the adb step; an agent must not touch the phone):** Android phone with Flash app installed and paired/connected to VR-N76. Radio is remembered in `flash_radio_settings`.
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
  4. Confirm the authorisation dialog (single confirmation; no callsign entry exists).
  5. Select Profile M. Verify Profile M is now active.
  6. Power cycle the radio. Verify radio reconnects with Profile M active.
- **Pass:** Profile M cannot be selected without prior authorization confirmation, and authorization persists across radio disconnects.
- **Source:** Plan R §8, ADR-178, Task 6.
- **Status:** TODO

## RADWIRE-D06: The gateway survives its Activity (rotate, back out)
- **Setup:** Android phone, VR-N76 connected, gateway ON, notification "Radio Gateway Active" visible. Added by the integrator after ERROR-681.
- **Steps:**
  1. Rotate the phone twice (or change the font size so the Activity recreates). Status must stay "Connected to VR-N76" with no new pairing and no "port in use".
  2. Press Back until Flash leaves the screen (Activity destroyed, process alive). Wait 30 s. Reopen Flash: still connected.
  3. Switch the gateway OFF in Settings: the notification disappears within 2 s and `adb shell dumpsys activity services com.transfer.flash` no longer lists RadioLinkService.
  4. Switch ON again, turn the radio off: the notification text changes to the retrying sentence (one entry, not several), then disappears when retries stop.
  5. Background the app and watch `adb logcat -s RadioGateway`: no crash and no ForegroundServiceStartNotAllowedException escapes (a refusal is logged as a warning).
- **Pass:** exactly one RadioLinkService notification while connecting or connected, none otherwise; no orphaned notification after the Activity is gone; no crash.
- **Source:** ERROR-681, ERROR-682 (RadioGatewayHolder, RadioServiceTracker).
- **Status:** TODO

