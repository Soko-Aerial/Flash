package com.transfer.flash.core.radio.link.windows

import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.JvmSerialPortCatalog
import com.transfer.flash.core.radio.link.LinkException
import com.transfer.flash.core.radio.link.PairResult
import com.transfer.flash.core.radio.link.RadioDeviceDiscovery
import com.transfer.flash.core.radio.link.SerialPortCatalog
import com.transfer.flash.core.radio.link.SerialSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Windows implementation of [RadioDeviceDiscovery] (Plan R §6, ADR-182).
 *
 * Combines Win32 Bluetooth APIs (JNA) for discovery and pairing with Windows Registry
 * BTHENUM interrogation for deterministic, dynamic COM port to Bluetooth address resolution.
 */
public class WindowsRadioDiscovery(
    private val windowsBluetoothApi: WindowsBluetoothApi = RealWindowsBluetoothApi(),
    private val registryReader: WindowsRegistryPortReader = RealWindowsRegistryPortReader(),
    private val serialPortCatalog: SerialPortCatalog = JvmSerialPortCatalog(),
) : RadioDeviceDiscovery {

    override suspend fun pairedDevices(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val remembered = windowsBluetoothApi.listRememberedDevices()
        val ranked = DeviceClassifier.rankDevices(remembered)

        ranked.mapNotNull { item ->
            val dev = item.device
            val cleanHex = dev.addressFormatted.replace(":", "").replace("-", "").uppercase()
            // Never offer incoming listener ports / all-zero addresses
            if (cleanHex == "000000000000") return@mapNotNull null

            val category = when (item.category) {
                DeviceCategory.RADIO -> CandidateCategory.KNOWN_RADIO
                DeviceCategory.SERIAL_SPP -> CandidateCategory.SERIAL_CAPABLE
                DeviceCategory.OTHER -> CandidateCategory.OTHER
            }

            DiscoveredDevice(
                name = dev.name,
                bluetoothAddress = dev.addressFormatted,
                isPaired = dev.isRemembered || dev.isAuthenticated,
                rssi = null,
                kind = CandidateKind.BLUETOOTH_SERIAL,
                category = category,
                rawAddressHex = cleanHex
            )
        }
    }

    override fun scan(timeoutMs: Long): Flow<DiscoveredDevice> = flow {
        val multiplier = ((timeoutMs + 1279) / 1280).toInt().coerceIn(1, 48).toByte()
        val searchParams = BluetoothSearchParams(
            returnAuthenticated = true,
            returnRemembered = true,
            returnUnknown = true,
            returnConnected = true,
            issueInquiry = true,
            timeoutMultiplier = multiplier
        )

        val discovered = windowsBluetoothApi.searchDevices(searchParams)
        val ranked = DeviceClassifier.rankDevices(discovered)

        for (item in ranked) {
            val dev = item.device
            val cleanHex = dev.addressFormatted.replace(":", "").replace("-", "").uppercase()
            if (cleanHex == "000000000000") continue

            val category = when (item.category) {
                DeviceCategory.RADIO -> CandidateCategory.KNOWN_RADIO
                DeviceCategory.SERIAL_SPP -> CandidateCategory.SERIAL_CAPABLE
                DeviceCategory.OTHER -> CandidateCategory.OTHER
            }

            emit(
                DiscoveredDevice(
                    name = dev.name,
                    bluetoothAddress = dev.addressFormatted,
                    isPaired = dev.isRemembered || dev.isAuthenticated,
                    rssi = null,
                    kind = CandidateKind.BLUETOOTH_SERIAL,
                    category = category,
                    rawAddressHex = cleanHex
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun pair(device: DiscoveredDevice): PairResult = withContext(Dispatchers.IO) {
        val addrLong = try {
            parseBluetoothAddress(device.bluetoothAddress)
        } catch (_: Throwable) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
        }

        val authResult = windowsBluetoothApi.authenticateDevice(addrLong, pin = null)
        when (authResult) {
            is AuthenticateOutcome.Success, is AuthenticateOutcome.AlreadyAuthenticated -> {
                // Ensure the virtual COM port (SPP service) is enabled
                windowsBluetoothApi.setServiceState(addrLong, SPP_SERVICE_GUID_STRING, enable = true)
                PairResult.Success
            }
            is AuthenticateOutcome.Cancelled -> {
                PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
            }
            is AuthenticateOutcome.Failed -> {
                val reason = when (authResult.win32ErrorCode) {
                    1244 -> RadioConnectFailureReason.WRONG_PIN
                    else -> RadioConnectFailureReason.PAIRING_REFUSED
                }
                PairResult.Failed(reason)
            }
        }
    }

    override suspend fun openLink(remembered: RememberedRadio): ByteLink = withContext(Dispatchers.IO) {
        val targetHex = remembered.bluetoothAddress.replace(":", "").replace("-", "").trim().uppercase()
        require(targetHex != "000000000000" && targetHex.isNotBlank()) {
            "Cannot connect to invalid or all-zero Bluetooth address"
        }

        val registry = registryReader.readBluetoothComPortRegistry()

        // 1. Resolve CURRENT virtual COM port from Windows Registry BTHENUM
        val matchedEntry = registry.values.firstOrNull { entry ->
            !entry.isIncomingPort &&
                entry.rawAddressHex.uppercase() != "000000000000" &&
                (entry.rawAddressHex.uppercase() == targetHex ||
                    entry.formattedAddress.equals(remembered.bluetoothAddress, ignoreCase = true))
        }

        if (matchedEntry != null) {
            return@withContext serialPortCatalog.open(matchedEntry.portName, SerialSettings(baud = 115200))
        }

        // 2. Fallback to remembered port name hint if specified (Plan R §6)
        val fallbackPort = remembered.lastKnownPortName
        if (!fallbackPort.isNullOrBlank()) {
            val regForFallback = registry[fallbackPort.uppercase()]
            if (regForFallback != null) {
                // If the fallback port is an incoming listener or belongs to a DIFFERENT device, refuse it!
                if (regForFallback.isIncomingPort || regForFallback.rawAddressHex == "000000000000") {
                    throw LinkException(
                        "${RadioConnectFailureReason.PORT_IN_USE.sentence} Port $fallbackPort is an incoming listener port."
                    )
                }
                if (regForFallback.rawAddressHex.uppercase() != targetHex) {
                    throw LinkException(
                        "${RadioConnectFailureReason.RADIO_NOT_FOUND.sentence} Port $fallbackPort is now assigned to another device."
                    )
                }
            }
            return@withContext serialPortCatalog.open(fallbackPort, SerialSettings(baud = 115200))
        }

        throw LinkException(
            "${RadioConnectFailureReason.RADIO_NOT_FOUND.sentence} ${RadioConnectFailureReason.RADIO_NOT_FOUND.nextStep}"
        )
    }
}
