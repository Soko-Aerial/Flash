package com.transfer.flash.ui.radio

/**
 * Security profiles for the radio gateway (Plan R §8, ADR-173, ADR-178).
 */
public enum class RadioSecurityProfile {
    /**
     * Tactical / Military: Encrypted payload using AES-256-GCM, counter nonce, rotating station tags,
     * replay window, no cleartext callsign. Primary and default profile.
     * Requires user confirmation of regulatory authorization.
     */
    PROFILE_M,

    /**
     * Civilian / Amateur: Signed cleartext with station callsign identification in AX.25 frames.
     * Selectable without special encryption authorization.
     */
    PROFILE_A,
}

/**
 * Operational modes of the radio gateway (Plan R §5, ADR-173).
 */
public enum class RadioGatewayMode {
    /** Mode 1: Individual operator chat with cryptographic delivery receipts over VHF/UHF. */
    FIELD_COMPANION,

    /** Mode 2: Base station relay between Wi-Fi LAN mesh and RF radio stations. */
    BASE_STATION_RELAY,

    /** Mode 3: Analog FM walkie-talkie audio bridge (gated behind audio spike; hidden in UI). */
    WALKIE_TALKIE_BRIDGE,

    /** Mode 4: Tactical GPS radar showing offline location reports as distance and bearing. */
    TACTICAL_RADAR,

    /** Mode 5: All-in-one simultaneous data chat and voice bridge. */
    ALL_IN_ONE,
}

/**
 * Device classification rank for Bluetooth peripherals discovered in range (Plan R §6).
 */
public enum class RadioDeviceCategory {
    /** Rank 1: Known compatible radios (e.g. Vero Telecom VR-N76, BTECH UV-Pro). */
    KNOWN_RADIO,

    /** Rank 2: Generic serial communication peripherals (Bluetooth Serial Port Profile / SPP). */
    SERIAL_SPP,

    /** Rank 3: Other Bluetooth devices (headphones, phones, computers). */
    OTHER,
}

/**
 * Represents a nearby Bluetooth peripheral discovered during an inquiry scan.
 */
public data class DiscoveredRadioDevice(
    public val id: String,
    public val name: String,
    public val category: RadioDeviceCategory,
    public val isPaired: Boolean = false,
    public val rssi: Int? = null,
    public val isPairing: Boolean = false,
)

/**
 * Connection states for the radio gateway state machine (Plan R §6, ADR-178).
 */
public enum class RadioConnectionStatus {
    OFF,
    CHECKLIST_READY,
    DETECTING,
    CONFIRMING_CANDIDATE,
    SCANNING,
    PAIRING,
    CONNECTING,
    CONNECTED,
    LINK_LOST,
    FAILED,
}

/**
 * Explicit failure reasons with one plain-English sentence each (Plan R §6).
 */
public enum class RadioFailureReason {
    NONE,
    NO_ADAPTER,
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    PERMISSION_DENIED,
    RADIO_NOT_FOUND,
    PAIRING_REFUSED,
    WRONG_PIN,
    PORT_IN_USE,
    KISS_NOT_ENABLED,

    /**
     * The radio accepted the Bluetooth link and then closed the serial channel straight away. Distinct from
     * [LINK_LOST] (a link that carried traffic for a while) and from [KISS_NOT_ENABLED] (a link that stays up but
     * never delivers KISS frames): the two different causes need different advice.
     */
    RADIO_CLOSED_LINK,
    LINK_LOST,
}

/**
 * Complete immutable UI state for the radio gateway screens.
 * Contains only plain Kotlin types and ready-to-display text; zero dependency on `:core:radio`.
 */
public data class RadioGatewayUiState(
    public val enabled: Boolean = false,
    public val connectionStatus: RadioConnectionStatus = RadioConnectionStatus.OFF,
    public val failureReason: RadioFailureReason = RadioFailureReason.NONE,
    public val statusSentence: String = "",
    public val statusNextStep: String = "",
    public val candidateRadio: DiscoveredRadioDevice? = null,
    public val rememberedRadioName: String? = null,
    public val rememberedRadioAddress: String? = null,
    public val isScanning: Boolean = false,
    public val scanCountdownSeconds: Int = 0,
    public val discoveredDevices: List<DiscoveredRadioDevice> = emptyList(),
    public val selectedProfile: RadioSecurityProfile = RadioSecurityProfile.PROFILE_M,
    public val isAuthorisedForEncryption: Boolean = false,
    public val authorisationConfirmedAtEpochMs: Long = 0L,
    /** The station ID recorded with the authorisation; null until one is entered (ERROR-684). */
    public val operatorCallsign: String? = null,
    public val selectedMode: RadioGatewayMode = RadioGatewayMode.FIELD_COMPANION,
    public val availableModes: Set<RadioGatewayMode> = setOf(RadioGatewayMode.FIELD_COMPANION),
    public val isSendingTestMessage: Boolean = false,
    public val testMessageSentSuccess: Boolean? = null,
    public val nearbyDevicesPermissionGranted: Boolean = true,
)
