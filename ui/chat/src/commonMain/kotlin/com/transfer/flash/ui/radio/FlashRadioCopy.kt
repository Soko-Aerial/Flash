package com.transfer.flash.ui.radio

/**
 * Every user-facing sentence and string of the Radio Gateway UI (UI-070..UI-074),
 * in one place shared across Android and desktop hosts (mirroring [com.transfer.flash.ui.hub.FlashHubCopy]).
 *
 * Exact texts follow Plan R Revision 3 §6 and §8.
 * No inline literals are allowed in screens, components, or accessibility state descriptions.
 */
public object FlashRadioCopy {

    // --- Settings Row & Top Bar --------------------------------------------------------------------------
    public const val SETTINGS_ROW_TITLE: String = "Radio gateway (beta)"
    public const val SETTINGS_ROW_SUBTITLE: String =
        "Connect a handheld radio with KISS TNC over Bluetooth for off-grid chat"
    public const val VALUE_ON: String = "On"
    public const val VALUE_OFF: String = "Off"
    public const val SCREEN_TITLE: String = "Radio gateway (beta)"

    // --- Master Switch & Overview -------------------------------------------------------------------------
    public const val MASTER_SWITCH_TITLE: String = "Enable radio gateway"
    public const val MASTER_SWITCH_SUBTITLE: String =
        "Use a handheld transceiver as an off-grid packet radio modem over VHF/UHF frequencies"

    // --- Hardware Readiness Checklist --------------------------------------------------------------------
    public const val CHECKLIST_TITLE: String = "Is your radio ready?"
    public const val CHECKLIST_SUBTITLE: String = "Complete these three steps before connecting:"
    public const val CHECKLIST_STEP_1: String = "1. Turn the radio on and verify battery is charged (> 50%)"
    public const val CHECKLIST_STEP_2: String =
        "2. In the radio menu: Menu -> General Settings -> KISS TNC -> Enable"
    public const val CHECKLIST_STEP_3: String = "3. Ensure Bluetooth is enabled on this phone or PC"

    // --- Candidate Confirmation ---------------------------------------------------------------------------
    public const val CANDIDATE_TITLE: String = "Paired radio found"
    public fun candidateQuestion(name: String): String = "Use $name for your radio gateway?"
    public const val ACTION_USE_CANDIDATE: String = "Use this radio"
    public const val ACTION_DIFFERENT_RADIO: String = "Different radio"

    // --- Remembered Radio Card ----------------------------------------------------------------------------
    public const val REMEMBERED_CARD_TITLE: String = "Connected radio"
    public const val ACTION_FORGET: String = "Forget"
    public const val ACTION_RECONNECT: String = "Reconnect"
    public fun addressHint(address: String): String = "Hardware address: $address"

    // --- "Find my radio" Live Scan & Pairing --------------------------------------------------------------
    public const val FIND_SECTION_TITLE: String = "Find my radio"
    public const val FIND_SECTION_SUBTITLE: String = "Nearby Bluetooth devices found in range"
    public const val ACTION_SCAN_RADIOS: String = "Scan for radios"
    public fun actionStopScan(seconds: Int): String = "Stop ($seconds s)"
    public const val ACTION_PAIR: String = "Pair"
    public const val STATUS_PAIRING_IN_PROGRESS: String = "Pairing…"
    public const val ACTION_OPEN_BT_SETTINGS: String = "Open Bluetooth settings"
    public const val ACTION_CHECK_AGAIN: String = "Check again"
    public const val SCAN_NO_DEVICES: String =
        "No nearby devices found yet. Make sure your radio is powered on and discoverable."
    public const val SCAN_BATTERY_NOTE: String = "Scanning uses extra battery and slows active links."

    // Device Category Badges
    public const val BADGE_KNOWN_RADIO: String = "RADIO"
    public const val BADGE_SERIAL_SPP: String = "SERIAL SPP"
    public const val BADGE_OTHER: String = "OTHER"

    // --- Connection Status Sentences & Next Steps (Plan R §6) ---------------------------------------------
    public const val SENTENCE_OFF: String = "Radio gateway is off."
    public const val NEXT_STEP_OFF: String = "Turn on to discover or connect to your radio."

    public const val SENTENCE_CHECKLIST: String = "Radio gateway is on."
    public const val NEXT_STEP_CHECKLIST: String = "Make sure your radio is on and KISS TNC is enabled."

    public const val SENTENCE_DETECTING: String = "Looking for paired radios…"
    public const val NEXT_STEP_DETECTING: String = "Checking known devices."

    public fun sentenceConfirmCandidate(name: String): String = "Found $name."
    public const val NEXT_STEP_CONFIRM_CANDIDATE: String = "Confirm if you want to use this radio."

    public const val SENTENCE_SCANNING: String = "Scanning for nearby radios…"
    public const val NEXT_STEP_SCANNING: String = "Make sure your radio is discoverable."

    public fun sentencePairing(name: String): String = "Pairing with $name…"
    public const val NEXT_STEP_PAIRING: String = "Confirm the pairing request on your screen if prompted."

    public fun sentenceConnecting(name: String): String = "Connecting to $name…"
    public const val NEXT_STEP_CONNECTING: String = "Opening KISS TNC serial link."

    public fun sentenceConnected(name: String): String = "Connected to $name."
    public const val NEXT_STEP_CONNECTED: String = "Ready to send and receive radio chat."

    public const val SENTENCE_LINK_LOST: String = "Radio disconnected, trying again…"
    public const val NEXT_STEP_LINK_LOST: String = "Keeping link open. Check radio battery and range."

    public const val SENTENCE_NO_ADAPTER: String = "No Bluetooth adapter found."
    public const val NEXT_STEP_NO_ADAPTER: String = "This device cannot connect to Bluetooth radios."

    public const val SENTENCE_BLUETOOTH_OFF: String = "Bluetooth is turned off."
    public const val NEXT_STEP_BLUETOOTH_OFF: String = "Turn Bluetooth on in system settings to connect."

    public const val SENTENCE_PERMISSION_MISSING: String = "Nearby devices permission required."
    public const val NEXT_STEP_PERMISSION_MISSING: String = "Allow Flash to find nearby radios in settings."

    public const val SENTENCE_PERMISSION_DENIED: String = "Nearby devices permission denied."
    public const val NEXT_STEP_PERMISSION_DENIED: String = "Tap to open settings and grant Nearby devices permission."

    public const val SENTENCE_RADIO_NOT_FOUND: String = "No compatible radio was found."
    public const val NEXT_STEP_RADIO_NOT_FOUND: String =
        "Verify KISS TNC is enabled in the radio menu and try again."

    public const val SENTENCE_PAIRING_REFUSED: String = "Pairing was refused or timed out."
    public const val NEXT_STEP_PAIRING_REFUSED: String = "Put the radio into pairing mode and try again."

    public const val SENTENCE_WRONG_PIN: String = "Wrong PIN or passkey entered."
    public const val NEXT_STEP_WRONG_PIN: String = "Check the radio PIN and try pairing again."

    public const val SENTENCE_PORT_IN_USE: String = "Serial port is in use by another program."
    public const val NEXT_STEP_PORT_IN_USE: String = "Close other radio software and tap Reconnect."

    public const val SENTENCE_KISS_NOT_ENABLED: String = "Connected, but no KISS data received."
    public const val NEXT_STEP_KISS_NOT_ENABLED: String =
        "In the radio menu: Menu -> General Settings -> KISS TNC -> Enable."

    // --- Security Profiles (Plan R §8) --------------------------------------------------------------------
    public const val SECURITY_SECTION_TITLE: String = "Security profile"

    public const val PROFILE_M_TITLE: String = "Profile M (Encrypted, tactical)"
    public const val PROFILE_M_SUBTITLE: String =
        "AES-256-GCM encryption with rotating station tags. Requires frequency authorization."
    public const val PROFILE_M_RECOMMENDED_BADGE: String = "Required for military"

    public const val PROFILE_A_TITLE: String = "Profile A (Signed cleartext)"
    public const val PROFILE_A_SUBTITLE: String =
        "Plaintext AX.25 frames with digital signatures and callsign identification for amateur bands."

    public const val AUTHORISATION_DIALOG_TITLE: String = "Authorisation required for encrypted RF"
    public const val AUTHORISATION_DIALOG_BODY: String =
        "Transmitting encrypted messages on radio frequencies is strictly regulated by law. In many civilian amateur bands, encrypted transmissions are prohibited and require cleartext callsigns.\n\nOnly confirm if you are operating under military, government, or other licensed authority permitted to use encryption on this frequency."
    public const val AUTHORISATION_STATEMENT: String =
        "I confirm that I am authorised to transmit encrypted traffic on the frequency in use."
    public const val CALLSIGN_LABEL: String = "Your callsign (station ID)"
    public const val CALLSIGN_SUPPORTING: String =
        "Sent with every transmission so other stations know who is on the air. Example: W1AW or W1AW-7."
    public const val CALLSIGN_INVALID: String = "Enter 3 to 6 letters and digits, with at least one of each."
    public const val ACTION_CONFIRM_AUTHORISATION: String = "Confirm authorisation"
    public const val ACTION_CANCEL_AUTHORISATION: String = "Cancel"
    public const val BADGE_AUTHORISED: String = "Authorised"

    // --- Operational Modes (Plan R §5) --------------------------------------------------------------------
    public const val MODES_SECTION_TITLE: String = "Operational mode"

    public const val MODE_1_TITLE: String = "Mode 1: Field Companion"
    public const val MODE_1_SUBTITLE: String =
        "Individual operator text chat with cryptographic delivery receipts over VHF/UHF."

    public const val MODE_2_TITLE: String = "Mode 2: Base Station Relay"
    public const val MODE_2_SUBTITLE: String =
        "Forwards messages between local Wi-Fi mesh and distant radio stations."

    public const val MODE_4_TITLE: String = "Mode 4: Tactical Radar"
    public const val MODE_4_SUBTITLE: String =
        "Periodic offline location reports shown as distance and bearing on radar."

    public const val MODE_5_TITLE: String = "Mode 5: All-in-One Dual Mode"
    public const val MODE_5_SUBTITLE: String = "Simultaneous data chat and voice bridge."

    public const val BADGE_ACTIVE: String = "Active"
    public const val BADGE_COMING_LATER: String = "Coming later"

    // --- Diagnostic Test Message (Plan R §6, Rule 4) ------------------------------------------------------
    public const val DIAGNOSTICS_SECTION_TITLE: String = "Radio diagnostics"
    public const val ACTION_SEND_TEST_MESSAGE: String = "Send a test message"
    public const val TEST_MESSAGE_SENT_SUCCESS: String = "Test frame sent successfully."
    public const val TEST_MESSAGE_SENDING: String = "Transmitting…"
    public const val TEST_MESSAGE_SENT_FAILED: String = "Could not send test message."

    public const val TRANSMIT_WARNING_TITLE: String = "RF Transmission Warning"
    public const val TRANSMIT_WARNING_BODY: String =
        "Sending a test message will key the transmitter and broadcast RF over the air on your tuned frequency. Ensure an antenna or 50-ohm dummy load is connected."
    public const val ACTION_TRANSMIT_NOW: String = "Transmit now"
    public const val ACTION_CANCEL_TRANSMIT: String = "Cancel"

    // --- Point of Use Permissions ------------------------------------------------------------------------
    public const val PERMISSION_CARD_TITLE: String = "Permission required"
    public const val PERMISSION_REASON_TEXT: String =
        "Flash needs Nearby devices permission to find and connect to your radio over Bluetooth."
    public const val ACTION_GRANT_PERMISSION: String = "Allow Nearby devices"

    // --- Accessibility Content & State Descriptions -------------------------------------------------------
    public const val A11Y_BACK: String = "Navigate back"
    public const val A11Y_CLOSE: String = "Close dialog"
    public const val A11Y_MASTER_SWITCH: String = "Radio gateway master toggle"
    public const val A11Y_STOP_SCAN: String = "Stop scanning for radios"
    public const val A11Y_FORGET_RADIO: String = "Forget remembered radio"
    public const val A11Y_RECONNECT_RADIO: String = "Reconnect to radio"
    public fun a11yPairDevice(name: String): String = "Pair with $name"
    public const val A11Y_SELECT_PROFILE_M: String = "Select Profile M encrypted"
    public const val A11Y_SELECT_PROFILE_A: String = "Select Profile A cleartext"
    public fun a11ySelectMode(name: String): String = "Select $name"
    public const val A11Y_STATUS_CHIP: String = "Radio connection status"
    public const val A11Y_TEST_MESSAGE_BTN: String = "Send diagnostic radio test message"
}
