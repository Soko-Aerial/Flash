package com.transfer.flash.desktop.radioaudio

import java.io.File
import java.time.Instant

/**
 * Command-Line Interface and Runner for the BT-R10 Windows Audio Endpoint Probe.
 *
 * Safety contract:
 * - 100% READ-ONLY enumeration of audio endpoints and mixers.
 * - Zero audio render/playback lines opened; no tones or signals emitted.
 * - Zero audio capture lines opened; no microphone recording.
 * - Zero serial bytes or PTT signals sent to any port or device.
 * - Bluetooth MAC addresses in all saved or emitted outputs are masked (e.g. `**:**:**:01:35:B5`).
 */
public object RadioAudioProbeRunner {

    public fun run(
        args: Array<String>,
        enumerator: WindowsAudioEndpointEnumerator = RealWindowsAudioEndpointEnumerator()
    ): Int {
        val argList = args.toList()
        val jsonOutput = argList.contains("--json")
        val outFile = argList.windowed(2, 1).firstOrNull { it[0] == "--out" }?.get(1)

        val report = generateReport(enumerator)

        if (jsonOutput) {
            val json = buildJsonReport(report)
            if (outFile != null) {
                File(outFile).writeText(json)
                println("JSON report written to: $outFile")
            } else {
                println(json)
            }
        } else {
            printHumanReport(report)
            if (outFile != null) {
                File(outFile).writeText(buildTextReport(report))
                println("\nText report written to: $outFile")
            }
        }

        return 0
    }

    public fun generateReport(enumerator: WindowsAudioEndpointEnumerator): RadioAudioProbeReport {
        val jdkMixers = enumerator.listJdkMixers()
        val endpoints = enumerator.listEndpoints()

        val radioEndpoints = endpoints.filter {
            it.classification == AudioDeviceClassification.RADIO_HANDS_FREE
        }
        val anyActive = radioEndpoints.any { it.state == AudioEndpointState.ACTIVE }

        val diagnosis = if (radioEndpoints.isEmpty()) {
            "No dedicated radio Hands-Free audio endpoint was found in Windows Core Audio. " +
                "The radio may not be paired, or its Bluetooth audio profile (HFP) may be disabled."
        } else if (anyActive) {
            "Radio Hands-Free endpoint is ACTIVE. An audio communications stream is active or supported."
        } else {
            "Radio Hands-Free endpoint was discovered, but is currently UNPLUGGED (0x8) or NOT_PRESENT (0x4). " +
                "In Windows 10/11, Bluetooth Hands-Free (HFP) audio endpoints remain disconnected until a communications " +
                "session or call stream is actively opened by an application. This confirms why the Windows audio settings " +
                "connection failed during EXP-023 when no telephony call was active."
        }

        val recommendation = if (radioEndpoints.isEmpty()) {
            "Pair the radio in Windows Bluetooth settings with Hands-Free audio enabled before running the BT-R10 runbook."
        } else if (anyActive) {
            "Radio audio endpoint is active. Proceed with owner runbook Step 2 to test simplex audio loopback."
        } else {
            "To connect the radio's Hands-Free audio on Windows, Flash must initialize an active WASAPI communications " +
                "stream (eCommunications / Voice Duplex). Plain idle connection is rejected by Windows HFP stack."
        }

        return RadioAudioProbeReport(
            timestampUtc = Instant.now().toString(),
            jdkMixers = jdkMixers,
            windowsEndpoints = endpoints,
            radioAudioEndpointsFound = radioEndpoints,
            anyRadioEndpointActive = anyActive,
            generalDiagnosis = diagnosis,
            recommendation = recommendation
        )
    }

    private fun printHumanReport(report: RadioAudioProbeReport) {
        println("==================================================================")
        println(" Flash Windows Radio Audio Endpoint Probe (BT-R10 Diagnostic)")
        println(" Mode: READ-ONLY Audio Endpoint Enumeration")
        println(" Timestamp: ${report.timestampUtc}")
        println(" Safety: NO audio played, NO mic captured, NO PTT keyed")
        println("==================================================================")

        println("\n[1/3] JDK Audio Mixers (javax.sound.sampled)")
        if (report.jdkMixers.isEmpty()) {
            println("  (No JDK audio mixers found)")
        } else {
            for (m in report.jdkMixers) {
                val dir = when {
                    m.isRender && m.isCapture -> "DUPLEX"
                    m.isRender -> "PLAYBACK"
                    m.isCapture -> "CAPTURE "
                    else -> "CONTROL "
                }
                println("  - [$dir] ${m.name} (${m.description}) [Vendor: ${m.vendor}]")
            }
        }

        println("\n[2/3] Windows Core Audio Endpoints (MMDevices & BTHHFENUM)")
        if (report.windowsEndpoints.isEmpty()) {
            println("  (No Windows Core Audio endpoints enumerated)")
        } else {
            for (ep in report.windowsEndpoints) {
                val tag = when (ep.classification) {
                    AudioDeviceClassification.RADIO_HANDS_FREE -> "[RADIO HFP]"
                    AudioDeviceClassification.BLUETOOTH_HANDS_FREE -> "[BTH HFP  ]"
                    AudioDeviceClassification.BLUETOOTH_STEREO -> "[BTH A2DP ]"
                    AudioDeviceClassification.SYSTEM_AUDIO -> "[SYSTEM   ]"
                }
                val dir = if (ep.kind == AudioEndpointKind.RENDER) "OUT" else "IN "
                val stateStr = ep.state.name
                val connStr = if (ep.isConnectable) "CONNECTABLE" else "NOT_READY"
                println("  - $tag ($dir) [State: $stateStr / $connStr] ${ep.name}")
                println("      Diagnosis: ${ep.diagnosisNote}")
            }
        }

        println("\n[3/3] Radio Gateway Audio Diagnosis (BT-R10 Question 1)")
        println("  Radio Endpoints Found: ${report.radioAudioEndpointsFound.size}")
        for (rEp in report.radioAudioEndpointsFound) {
            println("    * ${rEp.name} (${rEp.kind}) -> State: ${rEp.state}")
        }
        println("  Any Active: ${report.anyRadioEndpointActive}")
        println("  Diagnosis: ${report.generalDiagnosis}")
        println("  Recommendation: ${report.recommendation}")
        println("==================================================================")
    }

    private fun buildTextReport(report: RadioAudioProbeReport): String = buildString {
        appendLine("Flash Windows Radio Audio Probe Report")
        appendLine("Timestamp: ${report.timestampUtc}")
        appendLine("Diagnosis: ${report.generalDiagnosis}")
        appendLine("Recommendation: ${report.recommendation}")
        appendLine("Radio Endpoints: ${report.radioAudioEndpointsFound.size}")
        for (rEp in report.radioAudioEndpointsFound) {
            appendLine(" - ${rEp.name} [${rEp.kind}] State: ${rEp.state} Connectable: ${rEp.isConnectable}")
        }
    }

    private fun buildJsonReport(report: RadioAudioProbeReport): String = buildString {
        appendLine("{")
        appendLine("  \"timestampUtc\": \"${report.timestampUtc}\",")
        appendLine("  \"radioAudioEndpointsFoundCount\": ${report.radioAudioEndpointsFound.size},")
        appendLine("  \"anyRadioEndpointActive\": ${report.anyRadioEndpointActive},")
        appendLine("  \"generalDiagnosis\": \"${escapeJson(report.generalDiagnosis)}\",")
        appendLine("  \"recommendation\": \"${escapeJson(report.recommendation)}\",")
        appendLine("  \"radioEndpoints\": [")
        val eps = report.radioAudioEndpointsFound
        for ((idx, ep) in eps.withIndex()) {
            val comma = if (idx < eps.size - 1) "," else ""
            appendLine("    {")
            appendLine("      \"name\": \"${escapeJson(ep.name)}\",")
            appendLine("      \"kind\": \"${ep.kind}\",")
            appendLine("      \"state\": \"${ep.state}\",")
            appendLine("      \"classification\": \"${ep.classification}\",")
            appendLine("      \"isConnectable\": ${ep.isConnectable},")
            appendLine("      \"diagnosisNote\": \"${escapeJson(ep.diagnosisNote)}\"")
            appendLine("    }$comma")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

public fun main(args: Array<String>) {
    val exitCode = RadioAudioProbeRunner.run(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
