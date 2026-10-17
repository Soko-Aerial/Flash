package com.transfer.flash.desktop.radioaudio

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Mixer

/**
 * Interface for enumerating audio endpoints across the JDK and Windows Core Audio.
 */
public interface WindowsAudioEndpointEnumerator {
    public fun listJdkMixers(): List<JdkMixerInfo>
    public fun listEndpoints(): List<AudioEndpointInfo>
}

/**
 * Real implementation querying JDK Sound and Windows Registry (MMDevices & BTHHFENUM) via JNA.
 */
public class RealWindowsAudioEndpointEnumerator : WindowsAudioEndpointEnumerator {

    override fun listJdkMixers(): List<JdkMixerInfo> {
        val results = mutableListOf<JdkMixerInfo>()
        try {
            val mixerInfos = AudioSystem.getMixerInfo() ?: emptyArray()
            for (info in mixerInfos) {
                val mixer = try {
                    AudioSystem.getMixer(info)
                } catch (_: Throwable) {
                    null
                }
                val sourceLines = mixer?.sourceLineInfo ?: emptyArray()
                val targetLines = mixer?.targetLineInfo ?: emptyArray()

                results.add(
                    JdkMixerInfo(
                        name = info.name ?: "",
                        description = info.description ?: "",
                        vendor = info.vendor ?: "",
                        version = info.version ?: "",
                        isRender = sourceLines.isNotEmpty(),
                        isCapture = targetLines.isNotEmpty(),
                        maxSourceLines = sourceLines.size,
                        maxTargetLines = targetLines.size
                    )
                )
            }
        } catch (_: Throwable) {
            // Silently return what we have on failure
        }
        return results
    }

    override fun listEndpoints(): List<AudioEndpointInfo> {
        if (!Platform.isWindows()) {
            return fallbackJdkToEndpoints()
        }

        val endpoints = mutableListOf<AudioEndpointInfo>()

        // 1. Enumerate Render (playback) endpoints
        endpoints.addAll(enumerateMmDevices(AudioEndpointKind.RENDER))

        // 2. Enumerate Capture (recording) endpoints
        endpoints.addAll(enumerateMmDevices(AudioEndpointKind.CAPTURE))

        // 3. Query BTHHFENUM for Bluetooth Hands-Free devices
        val bthHfDevices = queryBthHfEnumDevices()

        // 4. Correlate BTHHFENUM entries with MMDevices or add any unmatched radio HF devices
        for (bthDev in bthHfDevices) {
            val alreadyMatched = endpoints.any {
                it.name.contains(bthDev.friendlyName, ignoreCase = true) ||
                    (bthDev.cleanRadioName.isNotEmpty() && it.name.contains(bthDev.cleanRadioName, ignoreCase = true))
            }
            if (!alreadyMatched && bthDev.cleanRadioName.isNotEmpty()) {
                val classification = AudioEndpointClassifier.classify(
                    name = bthDev.friendlyName,
                    description = bthDev.deviceDesc,
                    hardwareId = bthDev.hardwareId,
                    isBluetooth = true
                )
                val (connectable, note) = AudioEndpointClassifier.evaluateConnectability(
                    AudioEndpointState.UNPLUGGED,
                    classification
                )
                endpoints.add(
                    AudioEndpointInfo(
                        id = bthDev.deviceKey,
                        name = bthDev.friendlyName,
                        description = bthDev.deviceDesc,
                        kind = AudioEndpointKind.RENDER,
                        state = AudioEndpointState.UNPLUGGED,
                        classification = classification,
                        isBluetoothAssociated = true,
                        bluetoothAddressFormatted = null,
                        isConnectable = connectable,
                        diagnosisNote = "$note (Found in BTHHFENUM registry)"
                    )
                )
            }
        }

        if (endpoints.isEmpty()) {
            return fallbackJdkToEndpoints()
        }

        return endpoints
    }

    private fun enumerateMmDevices(kind: AudioEndpointKind): List<AudioEndpointInfo> {
        val rootPath = if (kind == AudioEndpointKind.RENDER) {
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Render"
        } else {
            "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\MMDevices\\Audio\\Capture"
        }

        val results = mutableListOf<AudioEndpointInfo>()

        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, rootPath)) {
                return emptyList()
            }

            val childKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, rootPath)
            for (keyName in childKeys) {
                val devicePath = "$rootPath\\$keyName"
                val stateMask = try {
                    Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, devicePath, "DeviceState")
                } catch (_: Throwable) {
                    0
                }
                val state = AudioEndpointState.fromMask(stateMask)

                val propsPath = "$devicePath\\Properties"
                var friendlyName = ""
                var interfacePath = ""

                if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, propsPath)) {
                    // PKEY_Device_FriendlyName or DeviceDesc
                    friendlyName = try {
                        Advapi32Util.registryGetStringValue(
                            WinReg.HKEY_LOCAL_MACHINE,
                            propsPath,
                            "{a45c254e-df1c-4efd-8020-67d146a850e0},2"
                        )
                    } catch (_: Throwable) {
                        try {
                            Advapi32Util.registryGetStringValue(
                                WinReg.HKEY_LOCAL_MACHINE,
                                propsPath,
                                "{a45c254e-df1c-4efd-8020-67d146a850e0},14"
                            )
                        } catch (_: Throwable) {
                            ""
                        }
                    }

                    interfacePath = try {
                        Advapi32Util.registryGetStringValue(
                            WinReg.HKEY_LOCAL_MACHINE,
                            propsPath,
                            "{a45c254e-df1c-4efd-8020-67d146a850e0},14"
                        )
                    } catch (_: Throwable) {
                        ""
                    }
                }

                if (friendlyName.isBlank()) {
                    friendlyName = keyName
                }

                val isBluetooth = interfacePath.contains("BTH", ignoreCase = true) ||
                    friendlyName.contains("Bluetooth", ignoreCase = true) ||
                    friendlyName.contains("Hands-Free", ignoreCase = true)

                val classification = AudioEndpointClassifier.classify(
                    name = friendlyName,
                    description = interfacePath,
                    hardwareId = null,
                    isBluetooth = isBluetooth
                )

                val (connectable, note) = AudioEndpointClassifier.evaluateConnectability(state, classification)

                results.add(
                    AudioEndpointInfo(
                        id = keyName,
                        name = friendlyName,
                        description = interfacePath,
                        kind = kind,
                        state = state,
                        classification = classification,
                        isBluetoothAssociated = isBluetooth,
                        bluetoothAddressFormatted = null,
                        isConnectable = connectable,
                        diagnosisNote = note
                    )
                )
            }
        } catch (_: Throwable) {
            // Log/ignore and return what we got
        }

        return results
    }

    private data class BthHfDeviceRecord(
        val deviceKey: String,
        val friendlyName: String,
        val deviceDesc: String,
        val hardwareId: String,
        val cleanRadioName: String
    )

    private fun queryBthHfEnumDevices(): List<BthHfDeviceRecord> {
        val rootPath = "SYSTEM\\CurrentControlSet\\Enum\\BTHHFENUM\\BthHFPAudio"
        val list = mutableListOf<BthHfDeviceRecord>()
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, rootPath)) {
                return emptyList()
            }
            val subkeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, rootPath)
            for (sub in subkeys) {
                val path = "$rootPath\\$sub"
                val rawFriendlyName = try {
                    Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, path, "FriendlyName")
                } catch (_: Throwable) {
                    ""
                }
                val deviceDesc = try {
                    Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, path, "DeviceDesc")
                } catch (_: Throwable) {
                    ""
                }
                val hardwareId = try {
                    Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, path, "HardwareID")
                } catch (_: Throwable) {
                    ""
                }

                // If FriendlyName looks like: "@System32\drivers\bthhfenum.sys,#4;%1 Hands-Free HF Audio%0\n;(VR-N76)"
                val cleanRadioName = if (rawFriendlyName.contains(";(")) {
                    rawFriendlyName.substringAfter(";(").substringBefore(")").trim()
                } else if (rawFriendlyName.contains("VR-N76", ignoreCase = true)) {
                    "VR-N76"
                } else {
                    ""
                }

                val displayFriendlyName = if (cleanRadioName.isNotEmpty()) {
                    "$cleanRadioName Hands-Free HF Audio"
                } else if (rawFriendlyName.isNotBlank()) {
                    rawFriendlyName.substringAfterLast(";")
                } else {
                    "Bluetooth Hands-Free Audio"
                }

                list.add(
                    BthHfDeviceRecord(
                        deviceKey = sub,
                        friendlyName = displayFriendlyName,
                        deviceDesc = deviceDesc,
                        hardwareId = hardwareId,
                        cleanRadioName = cleanRadioName
                    )
                )
            }
        } catch (_: Throwable) {
            // Ignore
        }
        return list
    }

    private fun fallbackJdkToEndpoints(): List<AudioEndpointInfo> {
        val mixers = listJdkMixers()
        return mixers.mapIndexed { idx, m ->
            val kind = if (m.isCapture) AudioEndpointKind.CAPTURE else AudioEndpointKind.RENDER
            val classification = AudioEndpointClassifier.classify(
                name = m.name,
                description = m.description,
                isBluetooth = m.name.contains("Bluetooth", ignoreCase = true)
            )
            val (connectable, note) = AudioEndpointClassifier.evaluateConnectability(
                AudioEndpointState.ACTIVE,
                classification
            )
            AudioEndpointInfo(
                id = "JDK-$idx",
                name = m.name,
                description = m.description,
                kind = kind,
                state = AudioEndpointState.ACTIVE,
                classification = classification,
                isBluetoothAssociated = classification != AudioDeviceClassification.SYSTEM_AUDIO,
                bluetoothAddressFormatted = null,
                isConnectable = connectable,
                diagnosisNote = "$note (Enumerated via JDK AudioSystem)"
            )
        }
    }
}
