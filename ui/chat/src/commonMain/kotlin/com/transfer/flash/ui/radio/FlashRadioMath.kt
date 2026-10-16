package com.transfer.flash.ui.radio

/**
 * Enumerates every discrete user or host action on the Radio Gateway screens.
 * Backs the no-dead-actions checklist in tests (mirroring FlashHubAction).
 */
public enum class FlashRadioAction {
    TOGGLE_ENABLED,
    START_SCAN,
    STOP_SCAN,
    CONFIRM_CANDIDATE,
    DISMISS_CANDIDATE,
    PAIR_DEVICE,
    RECONNECT,
    FORGET_RADIO,
    SELECT_PROFILE_M,
    SELECT_PROFILE_A,
    OPEN_AUTHORISATION_DIALOG,
    CONFIRM_AUTHORISATION,
    CANCEL_AUTHORISATION,
    SELECT_MODE,
    REQUEST_SEND_TEST_MESSAGE,
    CONFIRM_SEND_TEST_MESSAGE,
    CANCEL_SEND_TEST_MESSAGE,
    OPEN_BT_SETTINGS,
    REQUEST_PERMISSION,
}

/**
 * Pure, JVM-testable view logic and truth tables for the Radio Gateway UI.
 */
public object FlashRadioMath {

    /**
     * Resolves the primary status sentence for the given [status], [reason], and optional [radioName].
     */
    public fun statusSentence(
        status: RadioConnectionStatus,
        reason: RadioFailureReason,
        radioName: String? = null,
    ): String {
        val name = radioName?.takeIf { it.isNotBlank() } ?: "radio"
        if (reason != RadioFailureReason.NONE) {
            return when (reason) {
                RadioFailureReason.NO_ADAPTER -> FlashRadioCopy.SENTENCE_NO_ADAPTER
                RadioFailureReason.BLUETOOTH_OFF -> FlashRadioCopy.SENTENCE_BLUETOOTH_OFF
                RadioFailureReason.PERMISSION_MISSING -> FlashRadioCopy.SENTENCE_PERMISSION_MISSING
                RadioFailureReason.PERMISSION_DENIED -> FlashRadioCopy.SENTENCE_PERMISSION_DENIED
                RadioFailureReason.RADIO_NOT_FOUND -> FlashRadioCopy.SENTENCE_RADIO_NOT_FOUND
                RadioFailureReason.PAIRING_REFUSED -> FlashRadioCopy.SENTENCE_PAIRING_REFUSED
                RadioFailureReason.WRONG_PIN -> FlashRadioCopy.SENTENCE_WRONG_PIN
                RadioFailureReason.PORT_IN_USE -> FlashRadioCopy.SENTENCE_PORT_IN_USE
                RadioFailureReason.KISS_NOT_ENABLED -> FlashRadioCopy.SENTENCE_KISS_NOT_ENABLED
                RadioFailureReason.LINK_LOST -> FlashRadioCopy.SENTENCE_LINK_LOST
                RadioFailureReason.NONE -> ""
            }
        }
        return when (status) {
            RadioConnectionStatus.OFF -> FlashRadioCopy.SENTENCE_OFF
            RadioConnectionStatus.CHECKLIST_READY -> FlashRadioCopy.SENTENCE_CHECKLIST
            RadioConnectionStatus.DETECTING -> FlashRadioCopy.SENTENCE_DETECTING
            RadioConnectionStatus.CONFIRMING_CANDIDATE -> FlashRadioCopy.sentenceConfirmCandidate(name)
            RadioConnectionStatus.SCANNING -> FlashRadioCopy.SENTENCE_SCANNING
            RadioConnectionStatus.PAIRING -> FlashRadioCopy.sentencePairing(name)
            RadioConnectionStatus.CONNECTING -> FlashRadioCopy.sentenceConnecting(name)
            RadioConnectionStatus.CONNECTED -> FlashRadioCopy.sentenceConnected(name)
            RadioConnectionStatus.LINK_LOST -> FlashRadioCopy.SENTENCE_LINK_LOST
            RadioConnectionStatus.FAILED -> FlashRadioCopy.SENTENCE_RADIO_NOT_FOUND
        }
    }

    /**
     * Resolves the actionable next step / subtitle for the given [status] and [reason].
     */
    public fun statusNextStep(
        status: RadioConnectionStatus,
        reason: RadioFailureReason,
    ): String {
        if (reason != RadioFailureReason.NONE) {
            return when (reason) {
                RadioFailureReason.NO_ADAPTER -> FlashRadioCopy.NEXT_STEP_NO_ADAPTER
                RadioFailureReason.BLUETOOTH_OFF -> FlashRadioCopy.NEXT_STEP_BLUETOOTH_OFF
                RadioFailureReason.PERMISSION_MISSING -> FlashRadioCopy.NEXT_STEP_PERMISSION_MISSING
                RadioFailureReason.PERMISSION_DENIED -> FlashRadioCopy.NEXT_STEP_PERMISSION_DENIED
                RadioFailureReason.RADIO_NOT_FOUND -> FlashRadioCopy.NEXT_STEP_RADIO_NOT_FOUND
                RadioFailureReason.PAIRING_REFUSED -> FlashRadioCopy.NEXT_STEP_PAIRING_REFUSED
                RadioFailureReason.WRONG_PIN -> FlashRadioCopy.NEXT_STEP_WRONG_PIN
                RadioFailureReason.PORT_IN_USE -> FlashRadioCopy.NEXT_STEP_PORT_IN_USE
                RadioFailureReason.KISS_NOT_ENABLED -> FlashRadioCopy.NEXT_STEP_KISS_NOT_ENABLED
                RadioFailureReason.LINK_LOST -> FlashRadioCopy.NEXT_STEP_LINK_LOST
                RadioFailureReason.NONE -> ""
            }
        }
        return when (status) {
            RadioConnectionStatus.OFF -> FlashRadioCopy.NEXT_STEP_OFF
            RadioConnectionStatus.CHECKLIST_READY -> FlashRadioCopy.NEXT_STEP_CHECKLIST
            RadioConnectionStatus.DETECTING -> FlashRadioCopy.NEXT_STEP_DETECTING
            RadioConnectionStatus.CONFIRMING_CANDIDATE -> FlashRadioCopy.NEXT_STEP_CONFIRM_CANDIDATE
            RadioConnectionStatus.SCANNING -> FlashRadioCopy.NEXT_STEP_SCANNING
            RadioConnectionStatus.PAIRING -> FlashRadioCopy.NEXT_STEP_PAIRING
            RadioConnectionStatus.CONNECTING -> FlashRadioCopy.NEXT_STEP_CONNECTING
            RadioConnectionStatus.CONNECTED -> FlashRadioCopy.NEXT_STEP_CONNECTED
            RadioConnectionStatus.LINK_LOST -> FlashRadioCopy.NEXT_STEP_LINK_LOST
            RadioConnectionStatus.FAILED -> FlashRadioCopy.NEXT_STEP_RADIO_NOT_FOUND
        }
    }

    /**
     * Authorisation Gate (Plan R §8, ADR-178): Profile M (encrypted) may only be selected if the user
     * has confirmed regulatory authorization with a strictly positive timestamp.
     */
    public fun isProfileMSelectable(isAuthorised: Boolean): Boolean = isAuthorised

    /**
     * Modes visible to the user.
     * Modes 1, 2, 4, 5 are defined in Plan R §5.
     * Mode 3 (Walkie-talkie bridge) is HIDDEN until the audio spike decision.
     */
    public fun visibleModes(): List<RadioGatewayMode> = listOf(
        RadioGatewayMode.FIELD_COMPANION,
        RadioGatewayMode.BASE_STATION_RELAY,
        RadioGatewayMode.TACTICAL_RADAR,
        RadioGatewayMode.ALL_IN_ONE,
    )

    /**
     * A mode can only be selected if its engine is built and available.
     * If false, the card is non-interactive and displays "Coming later".
     */
    public fun isModeSelectable(
        mode: RadioGatewayMode,
        availableModes: Set<RadioGatewayMode>,
    ): Boolean = mode in availableModes

    /**
     * Resolves the badge text for a mode card.
     */
    public fun modeBadgeText(
        mode: RadioGatewayMode,
        currentMode: RadioGatewayMode,
        availableModes: Set<RadioGatewayMode>,
    ): String = when {
        mode == currentMode -> FlashRadioCopy.BADGE_ACTIVE
        mode !in availableModes -> FlashRadioCopy.BADGE_COMING_LATER
        else -> ""
    }

    /**
     * Resolves the badge text for a peripheral device category.
     */
    public fun categoryBadgeText(category: RadioDeviceCategory): String = when (category) {
        RadioDeviceCategory.KNOWN_RADIO -> FlashRadioCopy.BADGE_KNOWN_RADIO
        RadioDeviceCategory.SERIAL_SPP -> FlashRadioCopy.BADGE_SERIAL_SPP
        RadioDeviceCategory.OTHER -> FlashRadioCopy.BADGE_OTHER
    }

    /**
     * Sorts discovered devices:
     * 1. Category rank (KNOWN_RADIO [0], then SERIAL_SPP [1], then OTHER [2])
     * 2. Paired devices first
     * 3. Signal strength descending (nulls last)
     * 4. Alphabetical by display name
     */
    public fun sortDiscoveredDevices(devices: List<DiscoveredRadioDevice>): List<DiscoveredRadioDevice> =
        devices.sortedWith(
            compareBy<DiscoveredRadioDevice> { it.category.ordinal }
                .thenByDescending { it.isPaired }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )

    /**
     * Whether "Send a test message" button is enabled.
     * Requires active connection and no in-flight transmission.
     */
    public fun canSendTestMessage(
        status: RadioConnectionStatus,
        isSending: Boolean,
    ): Boolean = status == RadioConnectionStatus.CONNECTED && !isSending

    /**
     * Whether the Settings entry for the radio gateway is shown at all: only when the host provides a real
     * controller or its own opener. Without a backend the entry is hidden (no fake states in real builds).
     */
    public fun isGatewayEntryAvailable(hasController: Boolean, hasOpenHandler: Boolean): Boolean =
        hasController || hasOpenHandler

    /**
     * Whether "Scan for radios" is enabled.
     */
    public fun canScan(enabled: Boolean, isScanning: Boolean): Boolean =
        enabled && !isScanning

    /**
     * Whether "Stop scan" is enabled.
     */
    public fun canStopScan(isScanning: Boolean): Boolean = isScanning

    /**
     * Whether manual reconnect action is allowed.
     */
    public fun canReconnect(
        status: RadioConnectionStatus,
        hasRememberedRadio: Boolean,
    ): Boolean = hasRememberedRadio &&
        (status == RadioConnectionStatus.LINK_LOST ||
            status == RadioConnectionStatus.FAILED ||
            status == RadioConnectionStatus.CHECKLIST_READY)
}
