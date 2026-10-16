package com.transfer.flash.core.radio.connect

/**
 * Kind of device interface or radio link capability.
 */
public enum class CandidateKind {
    BLUETOOTH_SERIAL,
    USB_SERIAL,
    BLUETOOTH_LE,
    BLUETOOTH_OTHER,
    OTHER;

    public val isSerialCapable: Boolean
        get() = this == BLUETOOTH_SERIAL || this == USB_SERIAL
}

/**
 * Ranking tier for radio candidates.
 */
public enum class CandidateCategory {
    /** High confidence: device name matches a known radio model (VR-N76, UV-Pro, etc.). */
    KNOWN_RADIO,

    /** Medium confidence: serial-capable interface (SPP, RFCOMM, USB serial) without a known name. */
    SERIAL_CAPABLE,

    /** Low confidence / general device: other Bluetooth or peripheral device. */
    OTHER,
}

/**
 * A device discovered or listed as a candidate for radio connection.
 */
public data class RadioCandidateDevice(
    /** Device display name if available, e.g. "VR-N76", "Standard Serial over Bluetooth link (COM16)". */
    val name: String?,
    /** Bluetooth MAC address or system port name (e.g. "COM16", "/dev/rfcomm0"). */
    val addressOrPort: String,
    /** Interface or hardware kind. */
    val kind: CandidateKind,
    /** True if the device is already paired / bonded in the OS. */
    val isPaired: Boolean,
    /** Signal strength in dBm if known, or null. */
    val rssi: Int? = null,
)

/**
 * A candidate paired with its ranking tier.
 */
public data class RankedRadioCandidate(
    val device: RadioCandidateDevice,
    val category: CandidateCategory,
)

/**
 * Ranks discovered or listed devices and handles auto-detection rules (Plan R §6).
 */
public object RadioCandidateRanker {

    /**
     * Categorizes and ranks [devices]:
     * 1. Known radio names first ([CandidateCategory.KNOWN_RADIO])
     * 2. Serial-capable devices second ([CandidateCategory.SERIAL_CAPABLE])
     * 3. Everything else under [CandidateCategory.OTHER]
     *
     * Within each tier, paired devices rank above unpaired, followed by signal strength (stronger first).
     */
    public fun rank(devices: List<RadioCandidateDevice>): List<RankedRadioCandidate> {
        return devices.map { device ->
            val category = categorize(device)
            RankedRadioCandidate(device, category)
        }.sortedWith(
            compareBy<RankedRadioCandidate> { it.category.ordinal }
                .thenByDescending { it.device.isPaired }
                .thenByDescending { it.device.rssi ?: Int.MIN_VALUE }
                .thenBy { it.device.name ?: "" }
                .thenBy { it.device.addressOrPort }
        )
    }

    /**
     * Evaluates automatic detection of an already-paired radio.
     *
     * Rules from Plan R §6:
     * - Only considers paired devices.
     * - Preselects a candidate ONLY when EXACTLY ONE candidate matches.
     * - If exactly one known radio matches: returns that radio.
     * - If multiple known radios match: returns null (ambiguous; user must choose from list).
     * - If zero known radios match, but exactly one serial-capable device matches: returns that device.
     * - If multiple serial-capable devices match: returns null (ambiguous; user must choose from list).
     * - Never selects an [CandidateCategory.OTHER] device under any circumstance.
     */
    public fun autoDetectCandidate(devices: List<RadioCandidateDevice>): RadioCandidateDevice? {
        val pairedCandidates = devices.filter { it.isPaired }
        val knownRadios = pairedCandidates.filter { categorize(it) == CandidateCategory.KNOWN_RADIO }

        if (knownRadios.size == 1) {
            return knownRadios.single()
        }
        if (knownRadios.size > 1) {
            // Ambiguous: multiple known radios paired, user must confirm from list
            return null
        }

        // Zero known radios: look for serial-capable devices
        val serialCapable = pairedCandidates.filter { categorize(it) == CandidateCategory.SERIAL_CAPABLE }
        if (serialCapable.size == 1) {
            return serialCapable.single()
        }

        // Either multiple serial devices, zero serial devices, or only OTHER devices
        return null
    }

    /**
     * Categorizes a single candidate device.
     */
    public fun categorize(device: RadioCandidateDevice): CandidateCategory {
        return when {
            KnownRadioNames.isKnownRadio(device.name) -> CandidateCategory.KNOWN_RADIO
            device.kind.isSerialCapable -> CandidateCategory.SERIAL_CAPABLE
            else -> CandidateCategory.OTHER
        }
    }
}
