package com.transfer.flash.desktop.radiospike

import com.transfer.flash.core.radio.link.maskBluetoothAddress
import com.transfer.flash.core.radio.link.windows.*
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Scanner

/**
 * Command-Line Interface and Runner for the BT-R11 Windows Bluetooth Radio Spike.
 *
 * Safety contract:
 * - Default mode is strictly READ-ONLY.
 * - Active mode (inquiry, pairing, enabling serial service) is disabled unless
 *   `--i-am-the-owner-and-the-radio-is-on` is present on the command line.
 * - No data bytes or KISS frames are EVER transmitted to serial ports.
 * - Bluetooth addresses in all saved or emitted reports are masked to preserve only
 *   the last 3 bytes (e.g. `**:**:**:01:35:B5`).
 */
public object RadioSpikeRunner {

    public fun run(
        args: Array<String>,
        bluetoothApi: WindowsBluetoothApi = RealWindowsBluetoothApi(),
        portMapper: WindowsComPortMapper = WindowsComPortMapper(),
        consoleScanner: Scanner = Scanner(System.`in`),
        portProvider: () -> List<SerialPortInfo> = { enumerateSerialPorts() }
    ): Int {
        val argList = args.toList()
        val isActive = argList.contains("--active")
        val ownerFlag = argList.contains("--i-am-the-owner-and-the-radio-is-on")
        val jsonOutput = argList.contains("--json")
        val outFile = argList.windowed(2, 1).firstOrNull { it[0] == "--out" }?.get(1)
        val targetAddr = argList.windowed(2, 1).firstOrNull { it[0] == "--target" }?.get(1)
        val pin = argList.windowed(2, 1).firstOrNull { it[0] == "--pin" }?.get(1)

        println("==================================================================")
        println(" Flash Windows Bluetooth Radio Spike Tool (BT-R11)")
        println(" Mode: " + if (isActive) "ACTIVE (Inquiry / Pair / Enable SPP)" else "READ-ONLY (Enum ports & devices)")
        println(" Timestamp: ${Instant.now()}")
        println("==================================================================")

        if (isActive) {
            if (!ownerFlag) {
                System.err.println()
                System.err.println("[SAFETY ERROR] Active mode requested, but the mandatory safety flag:")
                System.err.println("  --i-am-the-owner-and-the-radio-is-on")
                System.err.println("was NOT provided. Execution refused.")
                System.err.println()
                return 1
            }
            return runActiveMode(bluetoothApi, targetAddr, pin, consoleScanner, portProvider)
        } else {
            return runReadOnlyMode(bluetoothApi, portMapper, jsonOutput, outFile, portProvider)
        }
    }

    private fun runReadOnlyMode(
        bluetoothApi: WindowsBluetoothApi,
        portMapper: WindowsComPortMapper,
        jsonOutput: Boolean,
        outFile: String?,
        portProvider: () -> List<SerialPortInfo>
    ): Int {
        println("\n[1/3] Enumerating Serial Ports (jSerialComm)...")
        val serialPorts = portProvider()
        if (serialPorts.isEmpty()) {
            println("  (No serial COM ports found on this system)")
        } else {
            for (p in serialPorts) {
                println("  - ${p.systemPortName}: ${p.descriptivePortName} [${p.portDescription}]")
            }
        }

        println("\n[2/3] Enumerating Known/Remembered Bluetooth Devices (Win32 JNA)...")
        val devices = try {
            bluetoothApi.listRememberedDevices()
        } catch (t: Throwable) {
            println("  [ERROR] Failed to query Win32 Bluetooth devices: ${t.message}")
            emptyList()
        }

        val rankedDevices = DeviceClassifier.rankDevices(devices)
        if (rankedDevices.isEmpty()) {
            println("  (No remembered/paired Bluetooth devices found in Windows)")
        } else {
            for (rd in rankedDevices) {
                val dev = rd.device
                println("  - [Rank ${rd.rank} ${rd.category}] ${dev.name}")
                println("      Address: ${dev.maskedAddress} (authenticated=${dev.isAuthenticated}, connected=${dev.isConnected})")
                println("      Category Reason: ${rd.reason}")
                if (dev.installedServiceGuids.isNotEmpty()) {
                    println("      Services (${dev.installedServiceGuids.size}):")
                    for (guid in dev.installedServiceGuids) {
                        val isSpp = guid.equals(SPP_SERVICE_GUID_STRING, ignoreCase = true)
                        val label = if (isSpp) " [Serial Port Profile (SPP)]" else ""
                        println("        * $guid$label")
                    }
                }
            }
        }

        println("\n[3/3] Mapping COM Ports to Remote Bluetooth Device Addresses (BT-R11 Q5)...")
        val mappings = portMapper.mapPorts(serialPorts, devices)
        for (m in mappings) {
            val maskedAddr = m.matchedDeviceAddress?.let { maskBluetoothAddress(it) } ?: "N/A"
            println("  - Port ${m.portName} -> Device: ${m.matchedDeviceName ?: "None"} ($maskedAddr)")
            println("      Method: ${m.method}")
            println("      Detail: ${m.detail}")
        }

        val report = RadioSpikeReport(
            mode = "READ_ONLY",
            timestampUtc = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            serialPorts = serialPorts,
            bluetoothDevices = rankedDevices.map { rd ->
                MaskedDeviceReport(
                    name = rd.device.name,
                    maskedAddress = rd.device.maskedAddress,
                    category = rd.category.name,
                    rank = rd.rank,
                    isConnected = rd.device.isConnected,
                    isRemembered = rd.device.isRemembered,
                    isAuthenticated = rd.device.isAuthenticated,
                    installedServices = rd.device.installedServiceGuids
                )
            },
            portMappings = mappings.map {
                it.copy(matchedDeviceAddress = it.matchedDeviceAddress?.let { a -> maskBluetoothAddress(a) })
            },
            notes = listOf(
                "Read-only execution completed.",
                "Zero data bytes or KISS frames were transmitted.",
                "Bluetooth addresses masked to last 3 bytes."
            )
        )

        val jsonStr = serializeReportToJson(report)
        if (jsonOutput) {
            println("\n--- Machine-Readable JSON Report ---")
            println(jsonStr)
        }

        if (outFile != null) {
            try {
                File(outFile).writeText(jsonStr)
                println("\n[OK] Report written to: $outFile")
            } catch (t: Throwable) {
                System.err.println("Failed to write report file $outFile: ${t.message}")
            }
        }

        println("\n[SUMMARY]")
        println("  Total COM Ports: ${serialPorts.size}")
        println("  Total Bluetooth Devices: ${devices.size}")
        val mappedCount = mappings.count { it.method == MappingMethod.REGISTRY_BTHENUM && it.matchedDeviceAddress != null }
        println("  Bluetooth COM Ports Mapped to MAC: $mappedCount")
        println("  Done.")
        return 0
    }

    private fun runActiveMode(
        bluetoothApi: WindowsBluetoothApi,
        targetAddrStr: String?,
        pin: String?,
        scanner: Scanner,
        portProvider: () -> List<SerialPortInfo>
    ): Int {
        println("\n>>> CAUTION: ACTIVE INQUIRY & PAIRING MODE <<<")
        println("This mode initiates Bluetooth radio frequency inquiry and OS bonding.")
        println("It will NOT send any KISS data frames or serial bytes.\n")

        print("Step 1: Run active Bluetooth inquiry scan for nearby radios? [Press ENTER to continue, or 'q' to abort]: ")
        val step1Confirm = scanner.nextLine().trim()
        if (step1Confirm.equals("q", ignoreCase = true)) {
            println("Aborted by user.")
            return 0
        }

        println("\nScanning for Bluetooth devices (timeout multiplier 4 = ~5.12s)...")
        val searchParams = BluetoothSearchParams(
            returnAuthenticated = true,
            returnRemembered = true,
            returnUnknown = true,
            returnConnected = true,
            issueInquiry = true,
            timeoutMultiplier = 4
        )
        val discovered = bluetoothApi.searchDevices(searchParams)
        val ranked = DeviceClassifier.rankDevices(discovered)

        println("Discovered ${discovered.size} devices:")
        for ((idx, rd) in ranked.withIndex()) {
            println("  [$idx] [${rd.category}] ${rd.device.name} (${rd.device.maskedAddress})")
        }

        if (ranked.isEmpty()) {
            println("No devices found. Ensure the radio is powered on and in pairing/discoverable state.")
            return 0
        }

        val targetDevice: DiscoveredBluetoothDevice = if (targetAddrStr != null) {
            ranked.map { it.device }.firstOrNull { it.addressFormatted.equals(targetAddrStr, ignoreCase = true) }
                ?: run {
                    System.err.println("Specified target address $targetAddrStr not found in scan results.")
                    return 1
                }
        } else {
            print("\nEnter index of device to pair/enable (default 0): ")
            val inputIdx = scanner.nextLine().trim()
            val chosenIdx = inputIdx.toIntOrNull() ?: 0
            ranked.getOrNull(chosenIdx)?.device ?: run {
                System.err.println("Invalid selection index.")
                return 1
            }
        }

        println("\nSelected Target: ${targetDevice.name} [${targetDevice.maskedAddress}]")
        print("Step 2: Authenticate (pair) with this device? (PIN: ${pin ?: "Wizard mode"}) [Press ENTER to continue, or 'q' to abort]: ")
        val step2Confirm = scanner.nextLine().trim()
        if (step2Confirm.equals("q", ignoreCase = true)) {
            println("Aborted before pairing.")
            return 0
        }

        println("Calling BluetoothAuthenticateDevice...")
        val authResult = bluetoothApi.authenticateDevice(targetDevice.addressLong, pin)
        println("Pairing Result: $authResult")

        print("\nStep 3: Enable Serial Port Profile (SPP) service on device? [Press ENTER to continue, or 'q' to abort]: ")
        val step3Confirm = scanner.nextLine().trim()
        if (step3Confirm.equals("q", ignoreCase = true)) {
            println("Aborted before enabling service.")
            return 0
        }

        println("Calling BluetoothSetServiceState for SPP ($SPP_SERVICE_GUID_STRING)...")
        val srvResult = bluetoothApi.setServiceState(targetDevice.addressLong, SPP_SERVICE_GUID_STRING, true)
        println("Service State Result: $srvResult")

        println("\nStep 4: Checking if Windows assigned a new virtual COM port...")
        Thread.sleep(1500) // Brief delay for driver installation
        val portsAfter = portProvider()
        for (p in portsAfter) {
            println("  - ${p.systemPortName}: ${p.descriptivePortName}")
        }

        println("\nActive spike sequence finished.")
        return 0
    }

    private fun enumerateSerialPorts(): List<SerialPortInfo> {
        return try {
            val clazz = Class.forName("com.fazecast.jSerialComm.SerialPort")
            val getCommPortsMethod = clazz.getMethod("getCommPorts")
            val rawPorts = getCommPortsMethod.invoke(null) as? Array<*> ?: emptyArray<Any>()
            val result = mutableListOf<SerialPortInfo>()
            for (p in rawPorts) {
                if (p == null) continue
                val pClass = p.javaClass
                val sysName = (pClass.getMethod("getSystemPortName").invoke(p) as? String) ?: ""
                val descName = (pClass.getMethod("getDescriptivePortName").invoke(p) as? String) ?: sysName
                val portDesc = (pClass.getMethod("getPortDescription").invoke(p) as? String) ?: ""
                result.add(
                    SerialPortInfo(
                        systemPortName = sysName,
                        descriptivePortName = descName,
                        portDescription = portDesc.ifBlank { descName }
                    )
                )
            }
            result.sortedBy { it.systemPortName }
        } catch (t: Throwable) {
            System.err.println("Serial port enumeration (jSerialComm) unavailable: ${t.message}")
            emptyList()
        }
    }

    private fun serializeReportToJson(report: RadioSpikeReport): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"mode\": \"${report.mode}\",\n")
        sb.append("  \"timestampUtc\": \"${report.timestampUtc}\",\n")

        sb.append("  \"serialPorts\": [\n")
        for ((idx, p) in report.serialPorts.withIndex()) {
            val comma = if (idx < report.serialPorts.size - 1) "," else ""
            sb.append("    {\"systemPortName\": \"${escapeJson(p.systemPortName)}\", \"descriptivePortName\": \"${escapeJson(p.descriptivePortName)}\", \"portDescription\": \"${escapeJson(p.portDescription)}\"}$comma\n")
        }
        sb.append("  ],\n")

        sb.append("  \"bluetoothDevices\": [\n")
        for ((idx, d) in report.bluetoothDevices.withIndex()) {
            val comma = if (idx < report.bluetoothDevices.size - 1) "," else ""
            val srvs = d.installedServices.joinToString(", ") { "\"$it\"" }
            sb.append("    {\"name\": \"${escapeJson(d.name)}\", \"maskedAddress\": \"${d.maskedAddress}\", \"category\": \"${d.category}\", \"rank\": ${d.rank}, \"isConnected\": ${d.isConnected}, \"isRemembered\": ${d.isRemembered}, \"isAuthenticated\": ${d.isAuthenticated}, \"installedServices\": [$srvs]}$comma\n")
        }
        sb.append("  ],\n")

        sb.append("  \"portMappings\": [\n")
        for ((idx, m) in report.portMappings.withIndex()) {
            val comma = if (idx < report.portMappings.size - 1) "," else ""
            val addr = m.matchedDeviceAddress?.let { "\"$it\"" } ?: "null"
            val devName = m.matchedDeviceName?.let { "\"${escapeJson(it)}\"" } ?: "null"
            sb.append("    {\"portName\": \"${m.portName}\", \"matchedDeviceAddress\": $addr, \"matchedDeviceName\": $devName, \"method\": \"${m.method}\", \"detail\": \"${escapeJson(m.detail)}\"}$comma\n")
        }
        sb.append("  ],\n")

        sb.append("  \"notes\": [\n")
        for ((idx, n) in report.notes.withIndex()) {
            val comma = if (idx < report.notes.size - 1) "," else ""
            sb.append("    \"${escapeJson(n)}\"$comma\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

public fun main(args: Array<String>) {
    val exitCode = RadioSpikeRunner.run(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
