package com.transfer.flash.debug.radioaudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioRoutingProbeTest {

    @Test
    fun testRADAUD_U10_resolveDeviceTypeNames() {
        assertEquals("BLUETOOTH_SCO", AndroidAudioRoutingProbe.resolveAudioDeviceTypeName(7))
        assertEquals("BUILTIN_SPEAKER", AndroidAudioRoutingProbe.resolveAudioDeviceTypeName(2))
        assertEquals("WIRED_HEADSET", AndroidAudioRoutingProbe.resolveAudioDeviceTypeName(3))
        assertEquals("BLUETOOTH_A2DP", AndroidAudioRoutingProbe.resolveAudioDeviceTypeName(8))
        assertEquals("UNKNOWN_TYPE_999", AndroidAudioRoutingProbe.resolveAudioDeviceTypeName(999))
    }

    @Test
    fun testRADAUD_U11_findBluetoothScoDeviceInList() {
        val devices = listOf(
            AndroidAudioDeviceItem(
                id = 1,
                name = "Speaker",
                type = AndroidAudioRoutingProbe.TYPE_BUILTIN_SPEAKER,
                typeName = "BUILTIN_SPEAKER",
                isSink = true,
                isSource = false,
                isBluetoothSco = false
            ),
            AndroidAudioDeviceItem(
                id = 2,
                name = "VR-N76 Hands-Free",
                type = AndroidAudioRoutingProbe.TYPE_BLUETOOTH_SCO,
                typeName = "BLUETOOTH_SCO",
                isSink = true,
                isSource = true,
                isBluetoothSco = true
            )
        )

        val found = AndroidAudioRoutingProbe.findBluetoothScoDevice(devices)
        assertNotNull(found)
        assertEquals("VR-N76 Hands-Free", found!!.name)
        assertTrue(found.isBluetoothSco)

        val emptyList = listOf(
            AndroidAudioDeviceItem(
                id = 1,
                name = "Speaker",
                type = AndroidAudioRoutingProbe.TYPE_BUILTIN_SPEAKER,
                typeName = "BUILTIN_SPEAKER",
                isSink = true,
                isSource = false,
                isBluetoothSco = false
            )
        )
        assertNull(AndroidAudioRoutingProbe.findBluetoothScoDevice(emptyList))
    }

    @Test
    fun testRADAUD_U12_evaluateScoPreconditionsMissingPermissions() {
        val (readyNoBt, reasonNoBt) = AndroidAudioRoutingProbe.evaluateScoActivationPreconditions(
            hasBluetoothConnectPermission = false,
            hasModifyAudioSettingsPermission = true,
            scoDeviceAvailable = true
        )
        assertFalse(readyNoBt)
        assertTrue(reasonNoBt.contains("BLUETOOTH_CONNECT"))

        val (readyNoAudio, reasonNoAudio) = AndroidAudioRoutingProbe.evaluateScoActivationPreconditions(
            hasBluetoothConnectPermission = true,
            hasModifyAudioSettingsPermission = false,
            scoDeviceAvailable = true
        )
        assertFalse(readyNoAudio)
        assertTrue(reasonNoAudio.contains("MODIFY_AUDIO_SETTINGS"))
    }

    @Test
    fun testRADAUD_U13_evaluateScoPreconditionsMissingDevice() {
        val (ready, reason) = AndroidAudioRoutingProbe.evaluateScoActivationPreconditions(
            hasBluetoothConnectPermission = true,
            hasModifyAudioSettingsPermission = true,
            scoDeviceAvailable = false
        )
        assertFalse(ready)
        assertTrue(reason.contains("No Bluetooth SCO"))
    }

    @Test
    fun testRADAUD_U14_evaluateScoPreconditionsSuccess() {
        val (ready, reason) = AndroidAudioRoutingProbe.evaluateScoActivationPreconditions(
            hasBluetoothConnectPermission = true,
            hasModifyAudioSettingsPermission = true,
            scoDeviceAvailable = true
        )
        assertTrue(ready)
        assertTrue(reason.contains("Preconditions met"))
    }

    @Test
    fun testRADAUD_U15_mapHeadsetConnectionStates() {
        assertEquals("STATE_DISCONNECTED", AndroidAudioRoutingProbe.mapHeadsetConnectionState(0))
        assertEquals("STATE_CONNECTING", AndroidAudioRoutingProbe.mapHeadsetConnectionState(1))
        assertEquals("STATE_CONNECTED", AndroidAudioRoutingProbe.mapHeadsetConnectionState(2))
        assertEquals("STATE_DISCONNECTING", AndroidAudioRoutingProbe.mapHeadsetConnectionState(3))
        assertEquals("STATE_UNKNOWN_42", AndroidAudioRoutingProbe.mapHeadsetConnectionState(42))
    }
}
