package com.transfer.flash.debug.radioaudio

/**
 * Representation of an audio device in Android AudioManager.
 */
public data class AndroidAudioDeviceItem(
    val id: Int,
    val name: String,
    val type: Int,
    val typeName: String,
    val isSink: Boolean,
    val isSource: Boolean,
    val isBluetoothSco: Boolean
)

/**
 * Snapshot of the Bluetooth Headset profile proxy state.
 */
public data class HeadsetProfileStatus(
    val proxyAvailable: Boolean,
    val connectedDevices: List<String>,
    val isAudioConnected: Boolean,
    val connectionStateName: String
)

/**
 * Safety evaluation for triggering SCO connection on Android.
 */
public object AndroidAudioRoutingProbe {

    // AudioDeviceInfo constant mappings (replicated for pure JVM testing)
    public const val TYPE_BUILTIN_EARPIECE: Int = 1
    public const val TYPE_BUILTIN_SPEAKER: Int = 2
    public const val TYPE_WIRED_HEADSET: Int = 3
    public const val TYPE_WIRED_HEADPHONES: Int = 4
    public const val TYPE_BLUETOOTH_SCO: Int = 7
    public const val TYPE_BLUETOOTH_A2DP: Int = 8
    public const val TYPE_BUILTIN_MIC: Int = 15
    public const val TYPE_BLE_HEADSET: Int = 26
    public const val TYPE_BLE_SPEAKER: Int = 27

    public fun resolveAudioDeviceTypeName(type: Int): String = when (type) {
        TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        TYPE_BLE_HEADSET -> "BLE_HEADSET"
        TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        else -> "UNKNOWN_TYPE_$type"
    }

    public fun findBluetoothScoDevice(devices: List<AndroidAudioDeviceItem>): AndroidAudioDeviceItem? {
        return devices.firstOrNull { it.isBluetoothSco || it.type == TYPE_BLUETOOTH_SCO }
    }

    public fun evaluateScoActivationPreconditions(
        hasBluetoothConnectPermission: Boolean,
        hasModifyAudioSettingsPermission: Boolean,
        scoDeviceAvailable: Boolean
    ): Pair<Boolean, String> {
        if (!hasBluetoothConnectPermission) {
            return false to "Missing BLUETOOTH_CONNECT permission (required on Android 12+)."
        }
        if (!hasModifyAudioSettingsPermission) {
            return false to "Missing MODIFY_AUDIO_SETTINGS permission."
        }
        if (!scoDeviceAvailable) {
            return false to "No Bluetooth SCO audio device found in system audio devices list."
        }
        return true to "Preconditions met. Ready to test SCO routing on explicit user tap."
    }

    public fun mapHeadsetConnectionState(state: Int): String = when (state) {
        0 -> "STATE_DISCONNECTED"
        1 -> "STATE_CONNECTING"
        2 -> "STATE_CONNECTED"
        3 -> "STATE_DISCONNECTING"
        else -> "STATE_UNKNOWN_$state"
    }
}
