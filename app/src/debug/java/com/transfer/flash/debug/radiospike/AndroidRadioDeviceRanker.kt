package com.transfer.flash.debug.radiospike

/**
 * Pure-Kotlin classifier and ranking engine for Bluetooth devices on Android.
 * Runs on standard JVM (testable in unit tests without Android device/Robolectric).
 *
 * Implements the ranking logic defined in RADIO-GATEWAY-PLAN.md §6:
 * Radios ranked first, generic serial/SPP devices second, other devices last.
 */

public enum class AndroidRadioCategory {
    RADIO,
    SERIAL_SPP,
    OTHER
}

public data class DiscoveredRadioCandidate(
    val name: String,
    val address: String, // e.g. "38:D2:00:01:35:B5"
    val bondState: Int = BOND_NONE, // 10 = BOND_NONE, 11 = BOND_BONDING, 12 = BOND_BONDED
    val serviceUuids: List<String> = emptyList(),
    val rssi: Short? = null,
    val deviceClass: Int? = null
) {
    val maskedAddress: String
        get() = maskAddress(address)

    public companion object {
        public const val BOND_NONE: Int = 10
        public const val BOND_BONDING: Int = 11
        public const val BOND_BONDED: Int = 12

        public fun maskAddress(addr: String): String {
            val clean = addr.trim()
            val parts = clean.split(':', '-')
            return if (parts.size == 6) {
                "**:**:**:${parts[3]}:${parts[4]}:${parts[5]}"
            } else if (clean.length == 12) {
                "******" + clean.takeLast(6)
            } else {
                "***" + clean.takeLast(8.coerceAtMost(clean.length))
            }
        }
    }
}

public data class RankedRadioCandidate(
    val candidate: DiscoveredRadioCandidate,
    val category: AndroidRadioCategory,
    val rank: Int,
    val reason: String
)

public object AndroidRadioDeviceRanker {

    public const val SPP_UUID_STRING: String = "00001101-0000-1000-8000-00805F9B34FB"

    private val KNOWN_RADIO_PATTERNS = listOf(
        "VR-N76",
        "VRN76",
        "UV-PRO",
        "UVPRO",
        "BTECH",
        "VERO",
        "KISS",
        "TNC",
        "APRS",
        "MOBILINKD",
        "ANYTONE",
        "YAESU",
        "KENWOOD",
        "ICOM"
    )

    private val SERIAL_PATTERNS = listOf(
        "SPP",
        "SERIAL",
        "UART",
        "HC-05",
        "HC-06",
        "BLUETOOTH LINK"
    )

    public fun classify(candidate: DiscoveredRadioCandidate): RankedRadioCandidate {
        val upperName = candidate.name.uppercase()

        // 1. Radio check
        val matchedRadio = KNOWN_RADIO_PATTERNS.firstOrNull { pattern ->
            upperName.contains(pattern)
        }
        if (matchedRadio != null) {
            return RankedRadioCandidate(
                candidate = candidate,
                category = AndroidRadioCategory.RADIO,
                rank = 1,
                reason = "Matched radio model pattern ($matchedRadio)"
            )
        }

        // 2. Serial / SPP check
        val hasSppUuid = candidate.serviceUuids.any { uuid ->
            uuid.equals(SPP_UUID_STRING, ignoreCase = true)
        }
        val matchedSerial = SERIAL_PATTERNS.firstOrNull { pattern ->
            upperName.contains(pattern)
        }
        if (hasSppUuid || matchedSerial != null) {
            val reason = if (hasSppUuid) {
                "Advertises Serial Port Profile (SPP)"
            } else {
                "Matched serial peripheral name ($matchedSerial)"
            }
            return RankedRadioCandidate(
                candidate = candidate,
                category = AndroidRadioCategory.SERIAL_SPP,
                rank = 2,
                reason = reason
            )
        }

        // 3. Other peripheral
        val reason = when {
            upperName.contains("HEADSET") || upperName.contains("HANDS-FREE") || upperName.contains("BUDS") ->
                "Audio / Bluetooth headset"
            upperName.contains("PHONE") || upperName.contains("PIXEL") || upperName.contains("SAMSUNG") ->
                "Smartphone / Mobile device"
            else -> "General Bluetooth peripheral"
        }

        return RankedRadioCandidate(
            candidate = candidate,
            category = AndroidRadioCategory.OTHER,
            rank = 3,
            reason = reason
        )
    }

    public fun rankCandidates(candidates: List<DiscoveredRadioCandidate>): List<RankedRadioCandidate> {
        return candidates.map { classify(it) }
            .sortedWith(
                compareBy<RankedRadioCandidate> { it.rank }
                    .thenByDescending { it.candidate.bondState == DiscoveredRadioCandidate.BOND_BONDED }
                    .thenByDescending { it.candidate.rssi ?: Short.MIN_VALUE }
                    .thenBy { it.candidate.name.lowercase() }
            )
    }
}
