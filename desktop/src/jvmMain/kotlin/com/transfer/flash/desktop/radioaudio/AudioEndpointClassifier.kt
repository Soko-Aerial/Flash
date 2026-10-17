package com.transfer.flash.desktop.radioaudio

import com.transfer.flash.core.radio.connect.KnownRadioNames

/**
 * Pure-logic classifier for audio endpoints and Windows Core Audio states.
 * Evaluates whether an endpoint corresponds to a radio Hands-Free device
 * and diagnoses its connectivity status without touching audio hardware.
 */
public object AudioEndpointClassifier {

    private val RADIO_PATTERNS = listOf(
        "VR-N76", "VR N76", "VRN76",
        "UV-PRO", "UV PRO", "UVPRO",
        "VERO", "BTECH", "GA-5WB", "BHM-79", "BHM-88"
    )

    private val HANDS_FREE_PATTERNS = listOf(
        "HANDS-FREE", "HANDSFREE", "HFP", "BTHHFENUM", "HEADSET", "TELEPHONY"
    )

    private val STEREO_PATTERNS = listOf(
        "STEREO", "A2DP", "HEADPHONES"
    )

    /**
     * Classifies an endpoint given its friendly name, description, and bus/driver identifiers.
     */
    public fun classify(
        name: String,
        description: String = "",
        hardwareId: String? = null,
        isBluetooth: Boolean = false
    ): AudioDeviceClassification {
        val combined = buildString {
            append(name).append(" ")
            append(description).append(" ")
            if (hardwareId != null) append(hardwareId)
        }.uppercase()

        // 1. Radio check: matches known radio names
        for (pattern in RADIO_PATTERNS) {
            if (combined.contains(pattern)) {
                return AudioDeviceClassification.RADIO_HANDS_FREE
            }
        }
        if (KnownRadioNames.isKnownRadio(name) || KnownRadioNames.isKnownRadio(description)) {
            return AudioDeviceClassification.RADIO_HANDS_FREE
        }
        for (known in KnownRadioNames.KNOWN_PATTERNS) {
            if (combined.contains(known.uppercase())) {
                return AudioDeviceClassification.RADIO_HANDS_FREE
            }
        }

        // 2. Generic Bluetooth Hands-Free check
        val isHf = HANDS_FREE_PATTERNS.any { combined.contains(it) }
        if (isHf && (isBluetooth || combined.contains("BLUETOOTH") || combined.contains("BTH"))) {
            return AudioDeviceClassification.BLUETOOTH_HANDS_FREE
        }

        // 3. Bluetooth Stereo / A2DP check
        val isStereo = STEREO_PATTERNS.any { combined.contains(it) }
        if (isStereo && (isBluetooth || combined.contains("BLUETOOTH") || combined.contains("BTH"))) {
            return AudioDeviceClassification.BLUETOOTH_STEREO
        }

        // If explicitly flagged as Bluetooth but not matched above
        if (isBluetooth || combined.contains("BLUETOOTH")) {
            return if (isHf) AudioDeviceClassification.BLUETOOTH_HANDS_FREE else AudioDeviceClassification.BLUETOOTH_STEREO
        }

        return AudioDeviceClassification.SYSTEM_AUDIO
    }

    /**
     * Determines whether the endpoint is connectable and provides an explanatory diagnosis sentence.
     */
    public fun evaluateConnectability(
        state: AudioEndpointState,
        classification: AudioDeviceClassification
    ): Pair<Boolean, String> = when (state) {
        AudioEndpointState.ACTIVE -> {
            true to "Endpoint is ACTIVE (0x1) in Windows Core Audio and ready for WASAPI stream initialization."
        }
        AudioEndpointState.UNPLUGGED -> {
            val note = if (classification == AudioDeviceClassification.RADIO_HANDS_FREE ||
                classification == AudioDeviceClassification.BLUETOOTH_HANDS_FREE) {
                "Endpoint is UNPLUGGED (0x8). In Windows Bluetooth HFP, the SCO voice channel is closed on idle. " +
                    "Windows establishes the physical SCO audio bearer only while an active communications/VoIP call stream is open."
            } else {
                "Endpoint is UNPLUGGED (0x8). Physical jack or wireless link is disconnected."
            }
            false to note
        }
        AudioEndpointState.DISABLED -> {
            false to "Endpoint is DISABLED (0x2) in Windows Sound Control Panel or Device Manager."
        }
        AudioEndpointState.NOT_PRESENT -> {
            false to "Endpoint is NOT_PRESENT (0x4). Device hardware is disconnected, powered off, or uncoupled."
        }
        AudioEndpointState.UNKNOWN -> {
            false to "Endpoint state is UNKNOWN (0x0)."
        }
    }
}
