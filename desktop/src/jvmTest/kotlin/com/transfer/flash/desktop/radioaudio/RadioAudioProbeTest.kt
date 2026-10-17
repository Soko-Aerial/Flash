package com.transfer.flash.desktop.radioaudio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RadioAudioProbeTest {

    @Test
    fun testRADAUD_U01_classifyKnownRadioEndpoints() {
        val names = listOf(
            "VR-N76 Hands-Free HF",
            "BTECH UV-PRO Audio",
            "VERO Telecom Bluetooth Headset",
            "GA-5WB Radio Audio"
        )
        for (name in names) {
            val result = AudioEndpointClassifier.classify(
                name = name,
                description = "Bluetooth Hands-Free Audio",
                isBluetooth = true
            )
            assertEquals(
                AudioDeviceClassification.RADIO_HANDS_FREE,
                result,
                "Expected $name to be classified as RADIO_HANDS_FREE"
            )
        }
    }

    @Test
    fun testRADAUD_U02_classifyGenericBluetoothHandsFreeEndpoints() {
        val result = AudioEndpointClassifier.classify(
            name = "Bluetooth Hands-Free Audio",
            description = "BthHfenum.sys driver",
            isBluetooth = true
        )
        assertEquals(AudioDeviceClassification.BLUETOOTH_HANDS_FREE, result)
    }

    @Test
    fun testRADAUD_U03_classifyBluetoothStereoEndpoints() {
        val result = AudioEndpointClassifier.classify(
            name = "Headphones (WH-1000XM4 Stereo)",
            description = "A2DP Bluetooth stereo playback",
            isBluetooth = true
        )
        assertEquals(AudioDeviceClassification.BLUETOOTH_STEREO, result)
    }

    @Test
    fun testRADAUD_U04_classifySystemAudioEndpoints() {
        val result = AudioEndpointClassifier.classify(
            name = "Speakers (Realtek(R) Audio)",
            description = "High Definition Audio Device",
            isBluetooth = false
        )
        assertEquals(AudioDeviceClassification.SYSTEM_AUDIO, result)
    }

    @Test
    fun testRADAUD_U05_connectabilityEvaluationBasedOnState() {
        // ACTIVE -> connectable
        val (activeConn, activeNote) = AudioEndpointClassifier.evaluateConnectability(
            AudioEndpointState.ACTIVE,
            AudioDeviceClassification.RADIO_HANDS_FREE
        )
        assertTrue(activeConn)
        assertTrue(activeNote.contains("ACTIVE"))

        // UNPLUGGED -> not connectable with telephony explanation
        val (unpluggedConn, unpluggedNote) = AudioEndpointClassifier.evaluateConnectability(
            AudioEndpointState.UNPLUGGED,
            AudioDeviceClassification.RADIO_HANDS_FREE
        )
        assertFalse(unpluggedConn)
        assertTrue(unpluggedNote.contains("UNPLUGGED"))
        assertTrue(unpluggedNote.contains("SCO"))

        // DISABLED -> not connectable
        val (disabledConn, disabledNote) = AudioEndpointClassifier.evaluateConnectability(
            AudioEndpointState.DISABLED,
            AudioDeviceClassification.RADIO_HANDS_FREE
        )
        assertFalse(disabledConn)
        assertTrue(disabledNote.contains("DISABLED"))
    }

    @Test
    fun testRADAUD_U06_generateReportWithRadioFoundAndActive() {
        val fakeEnumerator = object : WindowsAudioEndpointEnumerator {
            override fun listJdkMixers(): List<JdkMixerInfo> = emptyList()
            override fun listEndpoints(): List<AudioEndpointInfo> = listOf(
                AudioEndpointInfo(
                    id = "{0.0.0.00000000}.{radio-guid}",
                    name = "Headset (VR-N76 Hands-Free HF)",
                    description = "Microsoft Bluetooth Hands-Free Audio",
                    kind = AudioEndpointKind.RENDER,
                    state = AudioEndpointState.ACTIVE,
                    classification = AudioDeviceClassification.RADIO_HANDS_FREE,
                    isBluetoothAssociated = true,
                    bluetoothAddressFormatted = "38:D2:00:01:35:B5",
                    isConnectable = true,
                    diagnosisNote = "Active"
                )
            )
        }

        val report = RadioAudioProbeRunner.generateReport(fakeEnumerator)
        assertEquals(1, report.radioAudioEndpointsFound.size)
        assertTrue(report.anyRadioEndpointActive)
        assertTrue(report.generalDiagnosis.contains("ACTIVE"))
        assertEquals("**:**:**:01:35:B5", report.radioAudioEndpointsFound[0].maskedBluetoothAddress)
    }

    @Test
    fun testRADAUD_U07_generateReportWithRadioUnpluggedTelephonyRestriction() {
        val fakeEnumerator = object : WindowsAudioEndpointEnumerator {
            override fun listJdkMixers(): List<JdkMixerInfo> = emptyList()
            override fun listEndpoints(): List<AudioEndpointInfo> = listOf(
                AudioEndpointInfo(
                    id = "{0.0.0.00000000}.{radio-guid}",
                    name = "Headset (VR-N76 Hands-Free HF)",
                    description = "Microsoft Bluetooth Hands-Free Audio",
                    kind = AudioEndpointKind.RENDER,
                    state = AudioEndpointState.UNPLUGGED,
                    classification = AudioDeviceClassification.RADIO_HANDS_FREE,
                    isBluetoothAssociated = true,
                    bluetoothAddressFormatted = "38:D2:00:01:35:B5",
                    isConnectable = false,
                    diagnosisNote = "Unplugged"
                )
            )
        }

        val report = RadioAudioProbeRunner.generateReport(fakeEnumerator)
        assertEquals(1, report.radioAudioEndpointsFound.size)
        assertFalse(report.anyRadioEndpointActive)
        assertTrue(report.generalDiagnosis.contains("UNPLUGGED"))
        assertTrue(report.generalDiagnosis.contains("EXP-023"))
    }

    @Test
    fun testRADAUD_U08_generateReportWithoutRadio() {
        val fakeEnumerator = object : WindowsAudioEndpointEnumerator {
            override fun listJdkMixers(): List<JdkMixerInfo> = emptyList()
            override fun listEndpoints(): List<AudioEndpointInfo> = listOf(
                AudioEndpointInfo(
                    id = "speakers",
                    name = "Speakers (Realtek Audio)",
                    description = "Realtek",
                    kind = AudioEndpointKind.RENDER,
                    state = AudioEndpointState.ACTIVE,
                    classification = AudioDeviceClassification.SYSTEM_AUDIO,
                    isBluetoothAssociated = false,
                    bluetoothAddressFormatted = null,
                    isConnectable = true,
                    diagnosisNote = "Active"
                )
            )
        }

        val report = RadioAudioProbeRunner.generateReport(fakeEnumerator)
        assertEquals(0, report.radioAudioEndpointsFound.size)
        assertFalse(report.anyRadioEndpointActive)
        assertTrue(report.generalDiagnosis.contains("No dedicated radio Hands-Free"))
    }

    @Test
    fun testRADAUD_U09_runnerExecutesReadOnlyAndProducesOutputs() {
        val fakeEnumerator = object : WindowsAudioEndpointEnumerator {
            override fun listJdkMixers(): List<JdkMixerInfo> = listOf(
                JdkMixerInfo(
                    name = "Port Speakers",
                    description = "DirectSound playback",
                    vendor = "Oracle",
                    version = "1.0",
                    isRender = true,
                    isCapture = false,
                    maxSourceLines = 2,
                    maxTargetLines = 0
                )
            )
            override fun listEndpoints(): List<AudioEndpointInfo> = listOf(
                AudioEndpointInfo(
                    id = "mic-1",
                    name = "Microphone (Realtek)",
                    description = "Internal mic",
                    kind = AudioEndpointKind.CAPTURE,
                    state = AudioEndpointState.ACTIVE,
                    classification = AudioDeviceClassification.SYSTEM_AUDIO,
                    isBluetoothAssociated = false,
                    bluetoothAddressFormatted = null,
                    isConnectable = true,
                    diagnosisNote = "Internal mic active"
                )
            )
        }

        val exitCode = RadioAudioProbeRunner.run(arrayOf("--json"), fakeEnumerator)
        assertEquals(0, exitCode)
    }
}
