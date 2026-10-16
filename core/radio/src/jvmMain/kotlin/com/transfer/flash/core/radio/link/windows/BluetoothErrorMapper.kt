package com.transfer.flash.core.radio.link.windows

/**
 * Maps Windows Win32 error codes to plain-language, single-sentence descriptions
 * with actionable guidance as required by the radio gateway plan (§6 Rule 5).
 */
public object BluetoothErrorMapper {

    public fun mapErrorCode(errorCode: Int): String {
        return when (errorCode) {
            0 -> "Success."
            5 -> "Access denied. Administrative privileges or user permission may be required."
            87 -> "Invalid parameter passed to the Windows Bluetooth subsystem."
            170 -> "The COM port is in use by another program. Close it and try again."
            259 -> "No matching Bluetooth devices were found."
            1058 -> "The Windows Bluetooth support service is disabled in system services."
            1167 -> "The Bluetooth device is not connected or out of range."
            1223 -> "The operation was cancelled by the user."
            1244 -> "The device is not paired or rejected the authentication credentials."
            1245 -> "The Bluetooth device cannot be contacted."
            else -> "Windows Bluetooth error ($errorCode): An unexpected platform condition occurred."
        }
    }

    public fun getNextStep(errorCode: Int): String {
        return when (errorCode) {
            0 -> "Proceed with connection."
            5 -> "Try running Flash or the tool with necessary user permissions."
            87 -> "Verify device parameters and retry."
            170 -> "Close other terminal software, APRS tools, or vendor applications using the port."
            259 -> "Ensure the radio is powered on and Bluetooth is discoverable."
            1058 -> "Open Windows Services (services.msc) and enable Bluetooth Support Service."
            1167 -> "Bring the radio closer to the PC and check its power state."
            1223 -> "Retry pairing and confirm the system dialog prompt."
            1244 -> "Verify the radio PIN code or initiate pairing from Windows settings."
            else -> "Restart Bluetooth on the PC or cycle the radio power."
        }
    }
}
