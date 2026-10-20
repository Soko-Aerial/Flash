package com.transfer.flash.ui.radio

import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.RadioCandidateDevice
import com.transfer.flash.core.radio.connect.RadioConnectFailureReason
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.gateway.RadioGatewayState
import com.transfer.flash.core.radio.link.DiscoveredDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RADWIRE-U19..U25: the mapping both host controllers share (the Android controller needs a Context, this does not). */
class RadioGatewayUiMapperTest {
    private val radio = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
    private fun cand(name: String, addr: String, paired: Boolean = true) =
        RadioCandidateDevice(name, addr, CandidateKind.BLUETOOTH_SERIAL, isPaired = paired)

    private fun map(m: RadioGatewayState, perm: Boolean = true) =
        RadioGatewayUiMapper.map(RadioGatewayUiState(), m, RadioGatewayUiMapper.HostFacts(nearbyPermissionGranted = perm))

    @Test
    fun radwireU19_disabledGatewayIsOff() {
        val ui = map(RadioGatewayState(isEnabled = false, connectState = RadioConnectState.Connected(radio)))
        assertEquals(RadioConnectionStatus.OFF, ui.connectionStatus)
        assertEquals(false, ui.enabled)
    }

    @Test
    fun radwireU20_connectedWaitingAndRetriesStopped() {
        val connected = RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Connected(radio),
            linkState = TncLinkState.Connected("COM16", 0L), rememberedRadio = radio)
        assertEquals(RadioConnectionStatus.CONNECTED, map(connected).connectionStatus)
        val waiting = connected.copy(linkState = TncLinkState.Waiting(1, 1000L, "lost"))
        assertEquals(RadioConnectionStatus.LINK_LOST, map(waiting).connectionStatus)
        val stopped = connected.copy(isRetryingStopped = true)
        assertEquals(RadioFailureReason.LINK_LOST, map(stopped).failureReason)
    }

    @Test
    fun radfixU02_aLinkTheRadioClosedImmediatelyIsNotALostRadio() {
        val connected = RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Connected(radio),
            linkState = TncLinkState.Connected("rfcomm 38:D2:00:01:35:B5", 0L), rememberedRadio = radio)
        // The radio accepted the socket and closed it 300 ms later: the KISS TNC / wrong-entry case.
        val refused = connected.copy(linkState = TncLinkState.Waiting(1, 1000L, "eof", upMs = 300L))
        assertEquals(RadioFailureReason.RADIO_CLOSED_LINK, map(refused).failureReason)
        assertEquals(RadioConnectionStatus.LINK_LOST, map(refused).connectionStatus)
        assertTrue(map(refused).statusSentence.isNotBlank())
        // A link that carried the session for a while and then closed is a lost radio, not a refused channel.
        val lost = connected.copy(linkState = TncLinkState.Waiting(1, 1000L, "eof", upMs = 60_000L))
        assertEquals(RadioFailureReason.NONE, map(lost).failureReason)
        // No uptime information must never claim KISS is off.
        val unknown = connected.copy(linkState = TncLinkState.Waiting(1, 1000L, "eof"))
        assertEquals(RadioFailureReason.NONE, map(unknown).failureReason)
        // A link that never opened is an open failure, not an EOF.
        val openFailed = connected.copy(linkState = TncLinkState.Waiting(1, 1000L, "open_failed:no port", upMs = -1L))
        assertEquals(RadioFailureReason.NONE, map(openFailed).failureReason)
    }

    @Test
    fun radwireU21_everyCoreFailureReasonMapsToItsOwnReason() {
        val expected = mapOf(
            RadioConnectFailureReason.NO_ADAPTER to RadioFailureReason.NO_ADAPTER,
            RadioConnectFailureReason.BLUETOOTH_OFF to RadioFailureReason.BLUETOOTH_OFF,
            RadioConnectFailureReason.PERMISSION_MISSING to RadioFailureReason.PERMISSION_MISSING,
            RadioConnectFailureReason.RADIO_NOT_FOUND to RadioFailureReason.RADIO_NOT_FOUND,
            RadioConnectFailureReason.PAIRING_REFUSED to RadioFailureReason.PAIRING_REFUSED,
            RadioConnectFailureReason.WRONG_PIN to RadioFailureReason.WRONG_PIN,
            RadioConnectFailureReason.PORT_IN_USE to RadioFailureReason.PORT_IN_USE,
            RadioConnectFailureReason.KISS_TNC_NOT_ENABLED to RadioFailureReason.KISS_NOT_ENABLED,
            RadioConnectFailureReason.LINK_LOST to RadioFailureReason.LINK_LOST,
        )
        assertEquals(RadioConnectFailureReason.entries.toSet(), expected.keys, "a new core reason needs a mapping")
        for ((core, ui) in expected) {
            val m = RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Failed(core))
            assertEquals(ui, map(m).failureReason, "$core")
            assertEquals(RadioConnectionStatus.FAILED, map(m).connectionStatus)
        }
    }

    @Test
    fun radwireU22_missingNearbyPermissionWinsOverEverything() {
        val m = RadioGatewayState(isEnabled = true, connectState = RadioConnectState.Failed(RadioConnectFailureReason.WRONG_PIN))
        assertEquals(RadioFailureReason.PERMISSION_MISSING, map(m, perm = false).failureReason)
        assertEquals(false, map(m, perm = false).nearbyDevicesPermissionGranted)
    }

    @Test
    fun radwireU23_chooseFromListCandidatesAreListedWhenThereIsNoScan() {
        val choose = RadioGatewayState(
            isEnabled = true,
            connectState = RadioConnectState.ChooseFromList(listOf(cand("VR-N76", "AA:AA"), cand("UV-K5", "BB:BB"))),
        )
        val ui = map(choose)
        assertEquals(listOf("AA:AA", "BB:BB"), ui.discoveredDevices.map { it.id })
        assertEquals(listOf("VR-N76", "UV-K5"), ui.discoveredDevices.map { it.name })
        assertEquals(RadioConnectionStatus.CHECKLIST_READY, ui.connectionStatus)
    }

    @Test
    fun radwireU24_scanResultsWinOverChooseCandidates_andPairingIsMarked() {
        val scan = DiscoveredDevice("Scanned", "CC:CC", isPaired = false, category = CandidateCategory.SERIAL_CAPABLE)
        val m = RadioGatewayState(
            isEnabled = true,
            connectState = RadioConnectState.Pairing(cand("Scanned", "CC:CC", paired = false)),
            scanResults = listOf(scan),
        )
        val ui = map(m)
        assertEquals(listOf("CC:CC"), ui.discoveredDevices.map { it.id })
        assertTrue(ui.discoveredDevices.single().isPairing)
        assertEquals(RadioDeviceCategory.SERIAL_SPP, ui.discoveredDevices.single().category)
    }

    @Test
    fun radwireU25_deviceForIdFindsScanConfirmAndChooseEntries_caseInsensitive() {
        val scan = DiscoveredDevice("Scanned", "CC:CC", isPaired = false, category = CandidateCategory.SERIAL_CAPABLE)
        val inScan = RadioGatewayState(isEnabled = true, scanResults = listOf(scan))
        assertEquals("Scanned", RadioGatewayUiMapper.deviceForId(inScan, "cc:cc")?.name)

        val confirm = RadioGatewayState(isEnabled = true, connectState = RadioConnectState.ConfirmCandidate(cand("VR-N76", "AA:AA")))
        assertEquals("AA:AA", RadioGatewayUiMapper.deviceForId(confirm, "AA:AA")?.bluetoothAddress)
        assertNull(RadioGatewayUiMapper.deviceForId(confirm, "ZZ:ZZ"), "an id that is not the candidate is not a device")

        val choose = RadioGatewayState(isEnabled = true,
            connectState = RadioConnectState.ChooseFromList(listOf(cand("A", "AA:AA"), cand("B", "BB:BB"))))
        val b = RadioGatewayUiMapper.deviceForId(choose, "bb:bb")
        assertNotNull(b)
        assertEquals("B", b.name)
        assertEquals("BBBB", b.rawAddressHex)
        assertNull(RadioGatewayUiMapper.deviceForId(choose, "CC:CC"))
    }
}
