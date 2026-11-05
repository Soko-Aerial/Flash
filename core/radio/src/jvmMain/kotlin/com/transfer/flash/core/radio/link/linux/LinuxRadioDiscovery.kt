package com.transfer.flash.core.radio.link.linux

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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Linux implementation of [RadioDeviceDiscovery] (Plan R §6, ADR-188).
 *
 * Discovers and connects to radio peripherals on Linux:
 * 1. Serial and USB radios (e.g. `/dev/ttyUSB*`, `/dev/ttyACM*`, `/dev/rfcomm*`) via [SerialPortCatalog] (jSerialComm).
 * 2. Paired Bluetooth radios via BlueZ / `bluetoothctl devices`.
 * 3. Opening link: resolves serial ports or Bluetooth RFCOMM channels cleanly without Win32 DLLs or registry calls.
 */
public class LinuxRadioDiscovery(
    private val serialPortCatalog: SerialPortCatalog = JvmSerialPortCatalog(),
    private val commandRunner: (List<String>) -> List<String> = ::defaultLinuxCommandRunner,
) : RadioDeviceDiscovery {

    private val bluetoothDeviceRegex = Regex("""^Device\s+([0-9A-Fa-f:]{17})\s+(.*)$""")

    override suspend fun pairedDevices(): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredDevice>()
        val seenAddresses = mutableSetOf<String>()

        // 1. Enumerate serial and USB-serial ports (jSerialComm)
        for (port in serialPortCatalog.listPorts()) {
            val (kind, category) = classifyLinuxPort(port.systemName, port.description)
            val name = port.description.ifBlank { port.systemName }
            val dev = DiscoveredDevice(
                name = name,
                bluetoothAddress = port.systemName,
                isPaired = true,
                kind = kind,
                category = category,
            )
            if (seenAddresses.add(dev.bluetoothAddress.uppercase())) {
                results.add(dev)
            }
        }

        // 2. Enumerate paired Bluetooth devices from bluetoothctl
        val btDevices = readBluetoothCtlDevices()
        for (dev in btDevices) {
            if (seenAddresses.add(dev.bluetoothAddress.uppercase())) {
                results.add(dev)
            }
        }

        results.sortedWith(
            compareBy<DiscoveredDevice> {
                when (it.category) {
                    CandidateCategory.KNOWN_RADIO -> 0
                    CandidateCategory.SERIAL_CAPABLE -> 1
                    CandidateCategory.OTHER -> 2
                }
            }.thenBy { it.name }
        )
    }

    override fun scan(timeoutMs: Long): Flow<DiscoveredDevice> = flow {
        val seen = LinkedHashMap<String, DiscoveredDevice>()

        suspend fun emitNew(devices: List<DiscoveredDevice>) {
            for (d in devices) {
                val key = d.bluetoothAddress.uppercase()
                if (!seen.containsKey(key)) {
                    seen[key] = d
                    emit(d)
                }
            }
        }

        emitNew(pairedDevices())

        val pollIntervalMs = 1_000L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            currentCoroutineContext().ensureActive()
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
            emitNew(pairedDevices())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun pair(device: DiscoveredDevice): PairResult = withContext(Dispatchers.IO) {
        if (device.kind == CandidateKind.USB_SERIAL) {
            return@withContext PairResult.Success
        }

        val output = commandRunner(listOf("bluetoothctl", "pair", device.bluetoothAddress))
        val text = output.joinToString("\n")
        if (text.contains("successful", ignoreCase = true) || text.contains("AlreadyExists", ignoreCase = true)) {
            PairResult.Success
        } else {
            val paired = readBluetoothCtlDevices().any {
                it.bluetoothAddress.equals(device.bluetoothAddress, ignoreCase = true)
            }
            if (paired) {
                PairResult.Success
            } else {
                PairResult.Failed(RadioConnectFailureReason.PAIRING_REFUSED)
            }
        }
    }

    override suspend fun openLink(remembered: RememberedRadio): ByteLink = withContext(Dispatchers.IO) {
        val target = remembered.bluetoothAddress.trim()
        val fallback = remembered.lastKnownPortName?.trim()

        val ports = serialPortCatalog.listPorts()

        // 1. Check fallback port if available
        if (!fallback.isNullOrBlank()) {
            val match = ports.firstOrNull { it.systemName.equals(fallback, ignoreCase = true) }
            if (match != null) {
                return@withContext serialPortCatalog.open(match.systemName, SerialSettings(baud = 115200))
            }
        }

        // 2. Check if target directly names an existing port (e.g. ttyUSB0 or /dev/ttyUSB0)
        val directMatch = ports.firstOrNull {
            it.systemName.equals(target, ignoreCase = true) ||
                "/dev/${it.systemName}".equals(target, ignoreCase = true)
        }
        if (directMatch != null) {
            return@withContext serialPortCatalog.open(directMatch.systemName, SerialSettings(baud = 115200))
        }

        // 3. If target is a MAC address: check for an rfcomm port bound to it or matching description
        val cleanMac = target.replace(":", "").replace("-", "").uppercase()
        val rfcommPort = ports.firstOrNull { port ->
            val desc = port.description.uppercase()
            val sys = port.systemName.uppercase()
            sys.contains("RFCOMM") || desc.contains(cleanMac) || desc.contains(target.uppercase())
        }
        if (rfcommPort != null) {
            return@withContext serialPortCatalog.open(rfcommPort.systemName, SerialSettings(baud = 115200))
        }

        // 4. If target is a Bluetooth MAC address, attempt 'rfcomm bind' if utility is present
        if (target.contains(":") && target.length == 17) {
            runCatching {
                commandRunner(listOf("rfcomm", "bind", "0", target))
            }
            val freshPorts = serialPortCatalog.listPorts()
            val freshRfcomm = freshPorts.firstOrNull { it.systemName.contains("rfcomm0", ignoreCase = true) }
            if (freshRfcomm != null) {
                return@withContext serialPortCatalog.open(freshRfcomm.systemName, SerialSettings(baud = 115200))
            }
        }

        throw LinkException(
            "${RadioConnectFailureReason.RADIO_NOT_FOUND.sentence} " +
                "Could not connect to radio '${remembered.displayName}' at $target. " +
                "Ensure the radio is powered on, paired in Bluetooth settings, and connected via USB (/dev/ttyUSB*) or bound to an RFCOMM port (/dev/rfcomm0)."
        )
    }

    override val supportsAudioLink: Boolean get() = false

    override suspend fun openAudioLink(remembered: RememberedRadio): ByteLink =
        throw LinkException("Linux does not support the proprietary RFCOMM audio channel yet.")

    private fun readBluetoothCtlDevices(): List<DiscoveredDevice> {
        val lines = commandRunner(listOf("bluetoothctl", "devices"))
        return lines.mapNotNull { line ->
            val match = bluetoothDeviceRegex.find(line.trim()) ?: return@mapNotNull null
            val mac = match.groupValues[1]
            val name = match.groupValues[2].trim()
            val category = classifyLinuxName(name)
            DiscoveredDevice(
                name = name.ifBlank { mac },
                bluetoothAddress = mac,
                isPaired = true,
                kind = CandidateKind.BLUETOOTH_SERIAL,
                category = category,
            )
        }
    }

    private fun classifyLinuxPort(systemName: String, description: String): Pair<CandidateKind, CandidateCategory> {
        val d = description.lowercase()
        val n = systemName.lowercase()
        val isBt = "bluetooth" in d || "rfcomm" in n || "rfcomm" in d
        val kind = if (isBt) CandidateKind.BLUETOOTH_SERIAL else CandidateKind.USB_SERIAL

        val category = if (isKnownRadioName(description) || isKnownRadioName(systemName)) {
            CandidateCategory.KNOWN_RADIO
        } else if (isBt || "usb" in d || "ttyusb" in n || "ttyacm" in n || "cp210" in d || "ftdi" in d || "ch340" in d) {
            CandidateCategory.SERIAL_CAPABLE
        } else {
            CandidateCategory.OTHER
        }
        return Pair(kind, category)
    }

    private fun classifyLinuxName(name: String): CandidateCategory = when {
        isKnownRadioName(name) -> CandidateCategory.KNOWN_RADIO
        isSerialSppName(name) -> CandidateCategory.SERIAL_CAPABLE
        else -> CandidateCategory.OTHER
    }

    private fun isKnownRadioName(text: String): Boolean {
        val lower = text.lowercase()
        return KNOWN_RADIO_KEYWORDS.any { it in lower }
    }

    private fun isSerialSppName(text: String): Boolean {
        val lower = text.lowercase()
        return SERIAL_SPP_KEYWORDS.any { it in lower }
    }

    private companion object {
        val KNOWN_RADIO_KEYWORDS: List<String> = listOf(
            "vr-n76", "uv-pro", "btech", "kiss", "tnc", "aprs", "mobilinkd", "yaesu", "kenwood", "icom"
        )
        val SERIAL_SPP_KEYWORDS: List<String> = listOf(
            "serial", "spp", "cp210", "ftdi", "ch340", "uart"
        )
    }
}

private fun defaultLinuxCommandRunner(command: List<String>): List<String> {
    return runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor()
        lines
    }.getOrDefault(emptyList())
}
