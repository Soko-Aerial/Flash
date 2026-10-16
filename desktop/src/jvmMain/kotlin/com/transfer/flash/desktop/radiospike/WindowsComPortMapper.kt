package com.transfer.flash.desktop.radiospike

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/**
 * Interface to read Windows registry entries for Bluetooth COM ports.
 * Allows pure in-memory testing of registry mapping logic.
 */
public interface WindowsRegistryPortReader {
    public fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry>
}

public data class RegistryPortEntry(
    val portName: String, // e.g. "COM16"
    val rawAddressHex: String, // e.g. "38D2000135B5"
    val formattedAddress: String, // e.g. "38:D2:00:01:35:B5"
    val isIncomingPort: Boolean,
    val registryPath: String
)

/**
 * Real production implementation reading HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM
 * via JNA Advapi32Util.
 */
public class RealWindowsRegistryPortReader : WindowsRegistryPortReader {

    private val BTHENUM_ROOT = "SYSTEM\\CurrentControlSet\\Enum\\BTHENUM"
    private val MAC_HEX_REGEX = Regex("(?i)([0-9A-F]{12})")

    override fun readBluetoothComPortRegistry(): Map<String, RegistryPortEntry> {
        val mappings = mutableMapOf<String, RegistryPortEntry>()
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, BTHENUM_ROOT)) {
                return emptyMap()
            }

            val classKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, BTHENUM_ROOT)
            for (classKey in classKeys) {
                val classPath = "$BTHENUM_ROOT\\$classKey"
                val instanceKeys = try {
                    Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, classPath)
                } catch (_: Throwable) {
                    continue
                }

                for (instKey in instanceKeys) {
                    val devParamPath = "$classPath\\$instKey\\Device Parameters"
                    val keyExists = try {
                        Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, devParamPath)
                    } catch (_: Throwable) {
                        false
                    }
                    if (!keyExists) continue

                    val hasPortName = try {
                        Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, devParamPath, "PortName")
                    } catch (_: Throwable) {
                        false
                    }
                    if (!hasPortName) continue

                    val portName = try {
                        Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, devParamPath, "PortName").trim()
                    } catch (_: Throwable) {
                        continue
                    }
                    if (portName.isBlank()) continue

                    val uniqueId = try {
                        if (Advapi32Util.registryValueExists(WinReg.HKEY_LOCAL_MACHINE, devParamPath, "Bluetooth_UniqueID")) {
                            Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, devParamPath, "Bluetooth_UniqueID")
                        } else ""
                    } catch (_: Throwable) {
                        ""
                    }

                    // Extract 12-hex MAC address:
                    // UniqueId format: "{00001101-0000-1000-8000-00805f9b34fb}#38D2000135B5_C00000000"
                    // Instance key format: "7&342a290&0&38D2000135B5_C00000000"
                    // Notice the GUID suffix (00805f9b34fb) is also 12 hex chars, so we must extract specifically
                    // from the part after '#' in UniqueId, or after '&' and before '_' in instKey.
                    val targetSection = if (uniqueId.contains('#')) {
                        uniqueId.substringAfter('#')
                    } else {
                        instKey.substringAfterLast('&')
                    }
                    val match = MAC_HEX_REGEX.find(targetSection) ?: MAC_HEX_REGEX.find(instKey)
                    val rawHex = match?.value?.uppercase() ?: "000000000000"
                    val isIncoming = rawHex == "000000000000"
                    val formatted = if (isIncoming) {
                        "00:00:00:00:00:00 (Incoming listener)"
                    } else {
                        formatBluetoothAddress(rawHex.toLong(16))
                    }

                    mappings[portName.uppercase()] = RegistryPortEntry(
                        portName = portName,
                        rawAddressHex = rawHex,
                        formattedAddress = formatted,
                        isIncomingPort = isIncoming,
                        registryPath = devParamPath
                    )
                }
            }
        } catch (t: Throwable) {
            System.err.println("[RealWindowsRegistryPortReader] Error reading BTHENUM: ${t.message}")
        }
        return mappings
    }
}

/**
 * Core mapper associating serial ports with remote Bluetooth devices.
 */
public class WindowsComPortMapper(
    private val registryReader: WindowsRegistryPortReader = RealWindowsRegistryPortReader()
) {

    public fun mapPorts(
        ports: List<SerialPortInfo>,
        devices: List<DiscoveredBluetoothDevice>
    ): List<PortDeviceMapping> {
        val registryEntries = registryReader.readBluetoothComPortRegistry()

        return ports.map { port ->
            val upperPort = port.systemPortName.uppercase()
            val regEntry = registryEntries[upperPort]

            if (regEntry != null) {
                if (regEntry.isIncomingPort) {
                    PortDeviceMapping(
                        portName = port.systemPortName,
                        matchedDeviceAddress = null,
                        matchedDeviceName = null,
                        method = MappingMethod.REGISTRY_BTHENUM,
                        detail = "Windows incoming Bluetooth serial port listener (${regEntry.rawAddressHex})"
                    )
                } else {
                    val matchingDevice = devices.firstOrNull { dev ->
                        dev.addressFormatted.equals(regEntry.formattedAddress, ignoreCase = true)
                    }
                    PortDeviceMapping(
                        portName = port.systemPortName,
                        matchedDeviceAddress = regEntry.formattedAddress,
                        matchedDeviceName = matchingDevice?.name ?: "Unknown paired device",
                        method = MappingMethod.REGISTRY_BTHENUM,
                        detail = "Mapped via BTHENUM registry Device Parameters: ${regEntry.registryPath}"
                    )
                }
            } else {
                // Try secondary heuristics: service SPP match if single candidate exists
                val sppDevices = devices.filter { dev ->
                    dev.installedServiceGuids.any { it.equals(SPP_SERVICE_GUID_STRING, ignoreCase = true) }
                }
                val isBluetoothDesc = port.descriptivePortName.contains("Bluetooth", ignoreCase = true) ||
                        port.portDescription.contains("Bluetooth", ignoreCase = true)

                if (isBluetoothDesc && sppDevices.size == 1) {
                    val singleCandidate = sppDevices.first()
                    PortDeviceMapping(
                        portName = port.systemPortName,
                        matchedDeviceAddress = singleCandidate.addressFormatted,
                        matchedDeviceName = singleCandidate.name,
                        method = MappingMethod.SERVICE_SPP_MATCH,
                        detail = "Single Bluetooth SPP candidate matched by description heuristic"
                    )
                } else if (port.descriptivePortName.contains("USB", ignoreCase = true) ||
                    port.portDescription.contains("USB", ignoreCase = true)
                ) {
                    PortDeviceMapping(
                        portName = port.systemPortName,
                        matchedDeviceAddress = null,
                        matchedDeviceName = null,
                        method = MappingMethod.PORT_DESCRIPTION,
                        detail = "USB / non-Bluetooth serial port: ${port.descriptivePortName}"
                    )
                } else {
                    PortDeviceMapping(
                        portName = port.systemPortName,
                        matchedDeviceAddress = null,
                        matchedDeviceName = null,
                        method = MappingMethod.NOT_FOUND,
                        detail = "No Bluetooth device mapping found for this port"
                    )
                }
            }
        }
    }
}
