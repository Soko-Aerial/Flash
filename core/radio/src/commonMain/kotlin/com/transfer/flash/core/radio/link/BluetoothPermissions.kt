package com.transfer.flash.core.radio.link

/**
 * Which Bluetooth permissions a classic RFCOMM client needs at runtime, by Android API level. Pure data so it is unit-tested on
 * every target; the Android adapter (`AndroidBluetoothCatalog`) asks the OS about exactly these.
 *
 * Source (checked 2026-11-08, official): https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
 *  - API 31 (Android 12) and up: BLUETOOTH_CONNECT is a runtime permission needed to list bonded devices and to connect;
 *    BLUETOOTH_SCAN is needed for discovery and for `cancelDiscovery()`. Both are in the "Nearby devices" group, so the user
 *    sees one prompt.
 *  - API 30 and lower: the install-time permissions BLUETOOTH (list/connect) and BLUETOOTH_ADMIN (the page names it for
 *    "initiate device discovery or manipulate Bluetooth settings") apply, with `android:maxSdkVersion="30"`; there is no
 *    runtime prompt for them. The host must also handle the legacy location permission if it uses active discovery.
 *  - The [BluetoothAdapter reference](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#cancelDiscovery())
 *    lists BLUETOOTH_ADMIN for cancelling discovery on the legacy path and BLUETOOTH_SCAN on the modern path.
 *  - The Flash app declares the Bluetooth permissions for the radio gateway. SDK hosts supply their own manifest
 *    and runtime permission requests.
 */
public object BluetoothPermissions {
    /** First API level with the split Bluetooth permissions (Android 12). */
    public const val API_SPLIT_PERMISSIONS: Int = 31

    /** `android.permission.BLUETOOTH_CONNECT`. */
    public const val CONNECT: String = "android.permission.BLUETOOTH_CONNECT"

    /** `android.permission.BLUETOOTH_SCAN`. */
    public const val SCAN: String = "android.permission.BLUETOOTH_SCAN"

    /** Runtime permissions to request on [sdkInt]; empty below API 31 (nothing to ask). */
    public fun runtimePermissions(sdkInt: Int): List<String> =
        if (sdkInt >= API_SPLIT_PERMISSIONS) listOf(CONNECT, SCAN) else emptyList()

    /** The subset without which listing and connecting cannot work at all. SCAN only improves connect latency. */
    public fun mandatory(sdkInt: Int): List<String> = if (sdkInt >= API_SPLIT_PERMISSIONS) listOf(CONNECT) else emptyList()
}

/** The well-known Bluetooth Serial Port Profile UUID, used to talk to radios and other serial boards. */
public const val SPP_UUID_TEXT: String = "00001101-0000-1000-8000-00805F9B34FB"

/**
 * Flash's own RFCOMM service UUID for Flash-to-Flash links (generated for this project 2026-10-09; random, no meaning). The
 * Android docs advise a unique UUID for peer-to-peer apps and the well-known SPP UUID only for serial boards.
 */
public const val FLASH_RFCOMM_UUID_TEXT: String = "5f1a5c3e-9b24-4d7e-8a61-0c2f4e7b9a13"
