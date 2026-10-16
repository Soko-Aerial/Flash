package com.transfer.flash.core.radio.link.windows

/**
 * Pure-Kotlin classifier for Bluetooth devices.
 * Ranks discovered devices strictly prioritizing known radios first,
 * generic serial SPP modems second, and other peripherals (audio, phones) last.
 */
public object DeviceClassifier {

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

    public fun classify(device: DiscoveredBluetoothDevice): RankedBluetoothDevice {
        val upperName = device.name.uppercase()

        // 1. Check known radio signatures in name
        val matchedRadio = KNOWN_RADIO_PATTERNS.firstOrNull { pattern ->
            upperName.contains(pattern)
        }
        if (matchedRadio != null) {
            return RankedBluetoothDevice(
                device = device,
                category = DeviceCategory.RADIO,
                rank = 1,
                reason = "Matched known handheld radio pattern ($matchedRadio)"
            )
        }

        // 2. Check SPP service GUID or serial name pattern
        val hasSppService = device.installedServiceGuids.any { guid ->
            guid.equals(SPP_SERVICE_GUID_STRING, ignoreCase = true)
        }
        val matchedSerial = SERIAL_PATTERNS.firstOrNull { pattern ->
            upperName.contains(pattern)
        }

        if (hasSppService || matchedSerial != null) {
            val reason = if (hasSppService) {
                "Advertises Bluetooth Serial Port Profile (SPP)"
            } else {
                "Matched generic serial device name ($matchedSerial)"
            }
            return RankedBluetoothDevice(
                device = device,
                category = DeviceCategory.SERIAL_SPP,
                rank = 2,
                reason = reason
            )
        }

        // 3. Other peripheral
        val otherReason = when {
            upperName.contains("HEADSET") || upperName.contains("HANDS-FREE") || upperName.contains("AUDIO") ->
                "Audio / Hands-Free peripheral"
            upperName.contains("PHONE") || upperName.contains("PIXEL") || upperName.contains("GALAXY") ->
                "Smartphone / Mobile device"
            else -> "General Bluetooth peripheral"
        }

        return RankedBluetoothDevice(
            device = device,
            category = DeviceCategory.OTHER,
            rank = 3,
            reason = otherReason
        )
    }

    public fun rankDevices(devices: List<DiscoveredBluetoothDevice>): List<RankedBluetoothDevice> {
        return devices.map { classify(it) }
            .sortedWith(
                compareBy<RankedBluetoothDevice> { it.rank }
                    .thenByDescending { it.device.isConnected }
                    .thenByDescending { it.device.isAuthenticated }
                    .thenBy { it.device.name.lowercase() }
            )
    }
}
