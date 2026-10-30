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
import com.transfer.flash.core.radio.link.RadioServiceIdentity
import com.transfer.flash.core.radio.link.SerialPortCatalog
import com.transfer.flash.core.radio.link.SerialSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Windows implementation of [RadioDeviceDiscovery] (Plan R §6, ADR-182).
 *
 * Combines Win32 Bluetooth APIs (JNA) for discovery and pairing with Windows Registry
 * BTHENUM interrogation for deterministic, dynamic COM port to Bluetooth address resolution.
 *
 * Facts this class relies on (learn.microsoft.com, BLUETOOTH_DEVICE_INFO, checked 2026-10-23): `fRemembered` means only that
 * Windows knows the device — "Not all remembered devices are authenticated" — while `fAuthenticated` means paired. So a
 * device is offered as paired only when it is authenticated; a remembered, unauthenticated one needs pairing again.
 */
public class WindowsRadioDiscovery(
    private val windowsBluetoothApi: WindowsBluetoothApi = RealWindowsBluetoothApi(),
    private val registryReader: WindowsRegistryPortReader = RealWindowsRegistryPortReader(),
    private val serialPortCatalog: SerialPortCatalog = JvmSerialPortCatalog(),
    /** How long [pair] waits for Windows to create the outgoing COM port after it enabled the serial service. */
    private val portWaitMs: Long = 4_000L,
) : RadioDeviceDiscovery {

    override suspend fun pairedDevices(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        mapAndDemote(windowsBluetoothApi.listRememberedDevices().filter { it.isAuthenticated })
    }

    /**
     * Streams devices as they are found. `BluetoothFindFirstDevice` blocks for the whole inquiry and only then hands out
     * its list (measured on the owner's PC, 2026-10-23: 6.5 s for a 6.4 s inquiry, three devices at once), so one long
     * inquiry would show nothing until it ended. Instead: what Windows already knows is emitted at once, then short
     * inquiry rounds follow, and every round emits what is new or changed.
     */
    override fun scan(timeoutMs: Long): Flow<DiscoveredDevice> = flow {
        val seen = LinkedHashMap<String, DiscoveredBluetoothDevice>()
        val emitted = HashMap<String, DiscoveredDevice>()

        suspend fun publish(devices: List<DiscoveredBluetoothDevice>) {
            for (d in devices) seen[d.addressFormatted.uppercase()] = d
            for (device in mapAndDemote(seen.values.toList())) {
                val key = device.bluetoothAddress.uppercase()
                if (emitted[key] != device) {
                    emitted[key] = device
                    emit(device)
                }
            }
        }

        publish(windowsBluetoothApi.listRememberedDevices())

        val params = BluetoothSearchParams(
            returnAuthenticated = true,
            returnRemembered = true,
            returnUnknown = true,
            returnConnected = true,
            issueInquiry = true,
            timeoutMultiplier = SCAN_ROUND_MULTIPLIER,
        )
        var remainingMs = timeoutMs
        do {
            currentCoroutineContext().ensureActive()
            publish(windowsBluetoothApi.searchDevices(params))
            remainingMs -= SCAN_ROUND_MS
        } while (remainingMs > 0)
    }.flowOn(Dispatchers.IO)

    private fun mapAndDemote(devices: List<DiscoveredBluetoothDevice>): List<DiscoveredDevice> =
        demoteRadiosWithoutSerialPort(
            DeviceClassifier.rankDevices(devices).mapNotNull { item ->
                val dev = item.device
                val cleanHex = hexOf(dev)
                // Never offer incoming listener ports / all-zero addresses
                if (cleanHex == ZERO_ADDRESS) return@mapNotNull null

                DiscoveredDevice(
                    name = dev.name,
                    bluetoothAddress = dev.addressFormatted,
                    isPaired = dev.isAuthenticated,
                    rssi = null,
                    kind = CandidateKind.BLUETOOTH_SERIAL,
                    category = when (item.category) {
                        DeviceCategory.RADIO -> CandidateCategory.KNOWN_RADIO
                        DeviceCategory.SERIAL_SPP -> CandidateCategory.SERIAL_CAPABLE
                        DeviceCategory.OTHER -> CandidateCategory.OTHER
                    },
                    rawAddressHex = cleanHex,
                )
            },
        )

    /**
     * Windows lists one handheld twice (owner's PC, 2026-10-23: both entries are called VR-N76). Only the control entry
     * has an outgoing Serial Port Profile COM port; the other is its audio endpoint and can never open a serial link.
     * When at least one radio entry has such a port, the radio entries without one are demoted to
     * [CandidateCategory.OTHER], so auto-detect sees exactly one radio and offers the right one. They stay listed.
     */
    private fun demoteRadiosWithoutSerialPort(devices: List<DiscoveredDevice>): List<DiscoveredDevice> {
        val withPort = outgoingPortAddresses()
        if (withPort.isEmpty()) return devices
        val radios = devices.filter { it.category == CandidateCategory.KNOWN_RADIO }
        if (radios.none { it.rawAddressHex?.uppercase() in withPort }) return devices
        return devices.map { d ->
            if (d.category == CandidateCategory.KNOWN_RADIO && d.rawAddressHex?.uppercase() !in withPort) {
                d.copy(category = CandidateCategory.OTHER)
            } else {
                d
            }
        }
    }

    /** Raw addresses (12 hex digits) that own an outgoing, non-listener COM port right now. */
    private fun outgoingPortAddresses(): Set<String> = runCatching {
        registryReader.readBluetoothComPortRegistry().values
            .filter { !it.isIncomingPort && it.rawAddressHex.uppercase() != ZERO_ADDRESS }
            .map { it.rawAddressHex.uppercase() }
            .toSet()
    }.getOrDefault(emptySet())

    override suspend fun pair(device: DiscoveredDevice): PairResult = withContext(Dispatchers.IO) {
        val addrLong = try {
            parseBluetoothAddress(device.bluetoothAddress)
        } catch (_: Throwable) {
            return@withContext PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
        }

        when (windowsBluetoothApi.authenticateDevice(addrLong, pin = null)) {
            is AuthenticateOutcome.Success, is AuthenticateOutcome.AlreadyAuthenticated -> {
                // Enabling the serial service is what makes Windows create the virtual COM port; the port appears a moment later.
                windowsBluetoothApi.setServiceState(addrLong, SPP_SERVICE_GUID_STRING, enable = true)
                awaitOutgoingPort(hexOf(addrLong))
                PairResult.Success
            }
            // 1244 (ERROR_NOT_AUTHENTICATED) is the radio refusing the bond, not a wrong PIN: no PIN is ever sent here.
            // Windows logs it as BTHUSB 16 "mutual authentication ... failed" (owner's PC, 2026-10-23).
            is AuthenticateOutcome.Cancelled, is AuthenticateOutcome.Failed ->
                PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
        }
    }

    /** Polls the registry until [rawHex] owns an outgoing COM port, or [portWaitMs] has passed. */
    private suspend fun awaitOutgoingPort(rawHex: String) {
        var waited = 0L
        while (rawHex !in outgoingPortAddresses() && waited < portWaitMs) {
            delay(PORT_POLL_MS)
            waited += PORT_POLL_MS
        }
    }

    override val supportsAudioLink: Boolean get() = true

    /**
     * The radio's vendor audio channel. Windows lists the handheld twice (control entry and audio entry). On the owner's
     * PC the control entry (the one with the serial port) accepted it and the other refused, but that is one radio, so
     * every entry with the same name is tried, serial-port owner first; the first that accepts wins.
     */
    override suspend fun openAudioLink(remembered: RememberedRadio): ByteLink = withContext(Dispatchers.IO) {
        val targetHex = remembered.bluetoothAddress.replace(":", "").replace("-", "").trim().uppercase()
        require(targetHex != ZERO_ADDRESS && targetHex.isNotBlank()) { "Cannot connect to invalid or all-zero Bluetooth address" }
        val known = runCatching { windowsBluetoothApi.listRememberedDevices() }.getOrDefault(emptyList())
        val twins = known.filter { hexOf(it) != targetHex && it.name.equals(remembered.displayName, ignoreCase = true) }
        // Measured 2026-10-23: the entry that owns the serial port accepted the audio channel in 2 s, its twin refused
        // after 5 s. So that entry goes first, whichever one was chosen.
        val withPort = outgoingPortAddresses()
        val attempts = (listOf(remembered.bluetoothAddress) + twins.map { it.addressFormatted })
            .sortedBy { if (it.replace(":", "").uppercase() in withPort) 0 else 1 }
        val failures = ArrayList<String>()
        for (address in attempts) {
            try {
                return@withContext windowsBluetoothApi.openRfcomm(
                    parseBluetoothAddress(address),
                    RadioServiceIdentity.VENDOR_SERVICE_UUID,
                    "audio $address",
                )
            } catch (e: LinkException) {
                failures += "$address: ${e.message}"
            }
        }
        throw LinkException(failures.firstOrNull()?.substringAfter(": ") ?: AUDIO_UNAVAILABLE_MESSAGE)
    }

    override suspend fun openLink(remembered: RememberedRadio): ByteLink = withContext(Dispatchers.IO) {
        val targetHex = remembered.bluetoothAddress.replace(":", "").replace("-", "").trim().uppercase()
        require(targetHex != ZERO_ADDRESS && targetHex.isNotBlank()) {
            "Cannot connect to invalid or all-zero Bluetooth address"
        }

        val registry = registryReader.readBluetoothComPortRegistry()
        val known = runCatching { windowsBluetoothApi.listRememberedDevices() }.getOrDefault(emptyList())
        val self = known.firstOrNull { hexOf(it) == targetHex }

        // Windows lists the handheld twice with the same name; the control entry owns the outgoing port. If the chosen
        // entry has none (or is not paired), its authenticated twin is the one to open.
        fun twinPort(): RegistryPortEntry? = known
            .filter { it.isAuthenticated && hexOf(it) != targetHex && it.name.equals(remembered.displayName, ignoreCase = true) }
            .firstNotNullOfOrNull { outgoingEntry(registry, hexOf(it), it.addressFormatted) }

        // Windows forgot the pairing (or the radio did): a COM port may still exist, but opening it fails with
        // "The network location cannot be reached", so say what is wrong instead.
        if (self != null && !self.isAuthenticated) {
            val twin = twinPort() ?: throw LinkException(NOT_PAIRED_MESSAGE)
            return@withContext serialPortCatalog.open(twin.portName, SerialSettings(baud = 115200))
        }

        // 1. Resolve CURRENT virtual COM port from Windows Registry BTHENUM
        outgoingEntry(registry, targetHex, remembered.bluetoothAddress)?.let { entry ->
            return@withContext serialPortCatalog.open(entry.portName, SerialSettings(baud = 115200))
        }

        // 2. The chosen entry is the audio device of a radio Windows lists twice.
        twinPort()?.let { entry ->
            return@withContext serialPortCatalog.open(entry.portName, SerialSettings(baud = 115200))
        }

        // 3. Fallback to remembered port name hint if specified (Plan R §6)
        val fallbackPort = remembered.lastKnownPortName
        if (!fallbackPort.isNullOrBlank()) {
            val regForFallback = registry[fallbackPort.uppercase()]
            if (regForFallback != null) {
                // If the fallback port is an incoming listener or belongs to a DIFFERENT device, refuse it!
                if (regForFallback.isIncomingPort || regForFallback.rawAddressHex == ZERO_ADDRESS) {
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

        // A radio that is bonded but has no outgoing serial port is almost always the wrong one of its two entries:
        // Windows pairs the audio endpoint first, and only the control device exposes Serial Port Profile.
        throw LinkException(
            "${RadioConnectFailureReason.RADIO_NOT_FOUND.sentence} " +
                "${RadioConnectFailureReason.RADIO_NOT_FOUND.nextStep} " +
                "If Windows lists the radio twice, this entry is its audio device: remove it and pair the control " +
                "device with the same name.",
        )
    }

    private fun outgoingEntry(
        registry: Map<String, RegistryPortEntry>,
        rawHex: String,
        formatted: String,
    ): RegistryPortEntry? = registry.values.firstOrNull { entry ->
        !entry.isIncomingPort &&
            entry.rawAddressHex.uppercase() != ZERO_ADDRESS &&
            (entry.rawAddressHex.uppercase() == rawHex || entry.formattedAddress.equals(formatted, ignoreCase = true))
    }

    private fun hexOf(device: DiscoveredBluetoothDevice): String =
        device.addressFormatted.replace(":", "").replace("-", "").uppercase()

    private fun hexOf(addressLong: Long): String = formatBluetoothAddress(addressLong).replace(":", "")

    private companion object {
        const val ZERO_ADDRESS = "000000000000"

        /** One inquiry round: 2 x 1.28 s. Short on purpose, so a found device shows up after one round, not at the end. */
        const val SCAN_ROUND_MULTIPLIER: Byte = 2
        const val SCAN_ROUND_MS: Long = 2_560L
        const val PORT_POLL_MS: Long = 250L

        const val AUDIO_UNAVAILABLE_MESSAGE: String = "The radio's audio channel could not be opened."

        const val NOT_PAIRED_MESSAGE: String =
            "Windows is not paired with this radio any more (it was removed, or the radio forgot the pairing). " +
                "Put the radio into pairing mode and choose Pair in Flash."
    }
}
