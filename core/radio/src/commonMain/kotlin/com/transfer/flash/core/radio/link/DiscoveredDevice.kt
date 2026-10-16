package com.transfer.flash.core.radio.link

import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioCandidateDevice

/**
 * A Bluetooth or serial peripheral discovered or listed as a candidate for radio connection (Plan R §6).
 */
public data class DiscoveredDevice(
    val name: String,
    val bluetoothAddress: String,
    val isPaired: Boolean,
    val rssi: Int? = null,
    val kind: CandidateKind = CandidateKind.BLUETOOTH_SERIAL,
    val category: CandidateCategory = CandidateCategory.OTHER,
    val rawAddressHex: String? = null,
) {
    /**
     * Preserves only the last 3 bytes of the Bluetooth address for logging and privacy (Plan R §8).
     */
    public val maskedAddress: String
        get() = maskBluetoothAddress(bluetoothAddress)

    /**
     * Converts this discovered device into a [RadioCandidateDevice] for ranking and state machine evaluation.
     */
    public fun toCandidateDevice(): RadioCandidateDevice {
        return RadioCandidateDevice(
            name = name,
            addressOrPort = bluetoothAddress,
            kind = kind,
            isPaired = isPaired,
            rssi = rssi,
        )
    }
}

/**
 * Masks a Bluetooth address to expose only the last 3 octets (6 hex chars) to protect privacy.
 * Example: "38:D2:00:01:35:B5" -> "**:**:**:01:35:B5".
 */
public fun maskBluetoothAddress(address: String): String {
    val clean = address.trim()
    val parts = clean.split(':', '-')
    return if (parts.size == 6) {
        "**:**:**:${parts[3]}:${parts[4]}:${parts[5]}"
    } else if (clean.length == 12) {
        "******" + clean.takeLast(6)
    } else {
        "***" + clean.takeLast(8.coerceAtMost(clean.length))
    }
}
