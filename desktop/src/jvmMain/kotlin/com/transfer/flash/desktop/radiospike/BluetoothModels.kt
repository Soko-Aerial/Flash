package com.transfer.flash.desktop.radiospike

/**
 * Common models for BT-R11 Windows Bluetooth radio spike tool.
 */

public enum class DeviceCategory {
    RADIO,
    SERIAL_SPP,
    OTHER
}

public data class DiscoveredBluetoothDevice(
    val name: String,
    val addressLong: Long,
    val addressFormatted: String, // e.g. "38:D2:00:01:35:B5"
    val classOfDevice: Int,
    val isConnected: Boolean,
    val isRemembered: Boolean,
    val isAuthenticated: Boolean,
    val installedServiceGuids: List<String> = emptyList(),
    val lastSeenEpochMs: Long? = null,
    val lastUsedEpochMs: Long? = null
) {
    val maskedAddress: String
        get() = maskBluetoothAddress(addressFormatted)
}

public data class RankedBluetoothDevice(
    val device: DiscoveredBluetoothDevice,
    val category: DeviceCategory,
    val rank: Int,
    val reason: String
)

public data class BluetoothSearchParams(
    val returnAuthenticated: Boolean = true,
    val returnRemembered: Boolean = true,
    val returnUnknown: Boolean = false,
    val returnConnected: Boolean = true,
    val issueInquiry: Boolean = false,
    val timeoutMultiplier: Byte = 4 // 4 * 1.28s = 5.12s
)

public sealed interface AuthenticateOutcome {
    public data object Success : AuthenticateOutcome
    public data object AlreadyAuthenticated : AuthenticateOutcome
    public data object Cancelled : AuthenticateOutcome
    public data class Failed(val win32ErrorCode: Int, val message: String) : AuthenticateOutcome
}

public sealed interface ServiceStateOutcome {
    public data object Success : ServiceStateOutcome
    public data object AlreadyInDesiredState : ServiceStateOutcome
    public data class Failed(val win32ErrorCode: Int, val message: String) : ServiceStateOutcome
}

public data class SerialPortInfo(
    val systemPortName: String, // e.g. "COM16"
    val descriptivePortName: String, // e.g. "Standard Serial over Bluetooth link (COM16)"
    val portDescription: String = ""
)

public enum class MappingMethod {
    REGISTRY_BTHENUM,
    SERVICE_SPP_MATCH,
    PORT_DESCRIPTION,
    NOT_FOUND
}

public data class PortDeviceMapping(
    val portName: String,
    val matchedDeviceAddress: String?, // Masked in outputs
    val matchedDeviceName: String?,
    val method: MappingMethod,
    val detail: String
)

public data class RadioSpikeReport(
    val mode: String, // "READ_ONLY" or "ACTIVE"
    val timestampUtc: String,
    val serialPorts: List<SerialPortInfo>,
    val bluetoothDevices: List<MaskedDeviceReport>,
    val portMappings: List<PortDeviceMapping>,
    val notes: List<String>
)

public data class MaskedDeviceReport(
    val name: String,
    val maskedAddress: String,
    val category: String,
    val rank: Int,
    val isConnected: Boolean,
    val isRemembered: Boolean,
    val isAuthenticated: Boolean,
    val installedServices: List<String>
)

/**
 * Truncate/mask Bluetooth address to preserve only the last 3 bytes (6 hex chars)
 * in compliance with safety rules for persistent outputs.
 * Example: "38:D2:00:01:35:B5" -> "**:**:**:01:35:B5"
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

/**
 * Format a 64-bit raw Bluetooth address into standard colon-separated hex string.
 */
public fun formatBluetoothAddress(addrLong: Long): String {
    val b0 = ((addrLong ushr 40) and 0xFF).toInt()
    val b1 = ((addrLong ushr 32) and 0xFF).toInt()
    val b2 = ((addrLong ushr 24) and 0xFF).toInt()
    val b3 = ((addrLong ushr 16) and 0xFF).toInt()
    val b4 = ((addrLong ushr 8) and 0xFF).toInt()
    val b5 = (addrLong and 0xFF).toInt()
    return String.format("%02X:%02X:%02X:%02X:%02X:%02X", b0, b1, b2, b3, b4, b5)
}

/**
 * Parse a colon-separated or plain 12-char hex Bluetooth address into Long.
 */
public fun parseBluetoothAddress(address: String): Long {
    val clean = address.replace(":", "").replace("-", "").trim()
    return clean.toLong(16)
}
