package com.transfer.flash.core.radio.gateway

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.CandidateKind
import com.transfer.flash.core.radio.connect.InMemoryRadioAuthorisationStore
import com.transfer.flash.core.radio.connect.InMemoryRememberedRadioStore
import com.transfer.flash.core.radio.connect.RadioAuthorisationGate
import com.transfer.flash.core.radio.connect.RadioConnectState
import com.transfer.flash.core.radio.connect.RadioSecurityProfile
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.driver.KissTncConfig
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.driver.TncLinkState
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.FakeRadioDeviceDiscovery
import com.transfer.flash.core.radio.sim.InMemoryLink
import com.transfer.flash.core.radio.sim.inMemoryLinkPair
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadioGatewayManagerTest {

    private class TestClock(var currentMs: Long = 1_000_000L) : FlashTimeSource {
        override fun nowMs(): Long = currentMs
    }

    @Test
    fun radlnkU01_nothingOpenedBeforeUserConfirmation() = runTest {
        val discovery = FakeRadioDeviceDiscovery(
            pairedList = mutableListOf(
                DiscoveredDevice(
                    name = "VR-N76",
                    bluetoothAddress = "38:D2:00:01:35:B5",
                    isPaired = true,
                    category = CandidateCategory.KNOWN_RADIO
                )
            )
        )
        val rememberedStore = InMemoryRememberedRadioStore(null)
        val authStore = InMemoryRadioAuthorisationStore(null)
        var driverStarted = false

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore,
            driverFactory = { openLink ->
                driverStarted = true
                KissTncDriver(scope = backgroundScope, openLink = openLink)
            }
        )

        manager.enable()
        runCurrent()

        // Checklist is shown first
        assertTrue(manager.state.value.connectState is RadioConnectState.ChecklistShown, "Expected ChecklistShown but was ${manager.state.value.connectState}")
        assertFalse(driverStarted, "Driver must not start before checklist acknowledged")
        assertEquals(0, discovery.openedRadios.size, "Rule 3: no link opened before user confirmation")

        manager.acknowledgeChecklist()
        runCurrent()

        // Auto-detect finds exactly one candidate: VR-N76 -> ConfirmCandidate state
        val connectState = manager.state.value.connectState
        assertTrue(connectState is RadioConnectState.ConfirmCandidate, "State must be ConfirmCandidate but was $connectState")
        assertFalse(driverStarted, "Rule 3: driver must NOT start when candidate is only proposed")
        assertEquals(0, discovery.openedRadios.size, "Rule 3: port must NOT be opened before user confirmation")

        // User confirms candidate
        manager.confirmCandidate(discovery.pairedList.first())
        runCurrent()

        assertTrue(driverStarted, "Driver starts only after user confirmation")
        assertEquals(1, discovery.openedRadios.size, "Link opened after user confirmation")
    }

    @Test
    fun radlnkU02_autoDetectPreselectsOnlyWhenExactlyOneCandidate() = runTest {
        val dev1 = DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = true, category = CandidateCategory.KNOWN_RADIO)
        val dev2 = DiscoveredDevice("UV-Pro", "44:55:66:77:88:99", isPaired = true, category = CandidateCategory.KNOWN_RADIO)

        val discovery = FakeRadioDeviceDiscovery(pairedList = mutableListOf(dev1, dev2))
        val rememberedStore = InMemoryRememberedRadioStore(null)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore
        )

        manager.enable()
        runCurrent()
        manager.acknowledgeChecklist()
        runCurrent()

        // Two candidates -> ChooseFromList (NOT ConfirmCandidate)
        val state = manager.state.value.connectState
        assertTrue(state is RadioConnectState.ChooseFromList, "Multiple candidates must show list but was $state")
        assertEquals(2, (state as RadioConnectState.ChooseFromList).candidates.size)
    }

    @Test
    fun radlnkU03_whileSwitchOffNothingOpensScansOrPolls() = runTest {
        val discovery = FakeRadioDeviceDiscovery()
        val rememberedStore = InMemoryRememberedRadioStore(null)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore
        )

        assertFalse(manager.state.value.isEnabled)

        // Attempting scan while off is strictly ignored
        manager.startScan(5_000L)
        runCurrent()

        assertFalse(discovery.isScanning, "Discovery scan must not run while switch is off")
        assertEquals(0, discovery.openedRadios.size, "Nothing opened while switch is off")
        assertEquals(RadioConnectState.Off, manager.state.value.connectState)
    }

    @Test
    fun radlnkU04_scanTimeoutAndCancelStopsDiscoveryImmediately() = runTest {
        val discovery = FakeRadioDeviceDiscovery(
            discoveredOnScan = listOf(
                DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = false)
            )
        )
        val rememberedStore = InMemoryRememberedRadioStore(null)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore
        )

        manager.enable()
        runCurrent()

        manager.startScan(10_000L)
        runCurrent()

        assertTrue(discovery.isScanning)
        assertEquals(1, manager.state.value.scanResults.size)

        // Stopping scan cancels discovery flow
        manager.stopScan()
        runCurrent()

        assertTrue(discovery.scanCancelled, "Discovery flow must be cancelled on stopScan")
        assertFalse(discovery.isScanning)
    }

    @Test
    fun radlnkU05_reconnectLimitStopsRetryingAndPromptsReconnect() = runTest {
        val remembered = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
        var openCount = 0

        val discovery = FakeRadioDeviceDiscovery(
            linkSupplier = {
                openCount++
                throw RuntimeException("Serial port inaccessible")
            }
        )
        val rememberedStore = InMemoryRememberedRadioStore(remembered)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore,
            driverFactory = { openLink ->
                KissTncDriver(
                    scope = backgroundScope,
                    openLink = openLink,
                    config = KissTncConfig(reconnectBaseMs = 10L, reconnectCapMs = 20L)
                )
            },
            maxRetryAttempts = 3
        )

        manager.enable()
        runCurrent()

        // Advance time to allow 3 reconnect retries to exhaust
        advanceTimeBy(200)
        runCurrent()

        // After maxRetryAttempts (3), retries stop
        assertTrue(manager.state.value.isRetryingStopped, "Retries must stop after reaching limit")
        assertTrue(manager.state.value.nextStep.contains("Reconnect"), "Next step must prompt Reconnect")
    }

    @Test
    fun radlnkU06_powerCycledRadioRecoversAutomatically() = runTest {
        val remembered = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
        var currentLinkPair: Pair<InMemoryLink, InMemoryLink>? = inMemoryLinkPair("cycle-test")

        val discovery = FakeRadioDeviceDiscovery(
            linkSupplier = {
                val pair = currentLinkPair ?: inMemoryLinkPair("recovered-radio").also { currentLinkPair = it }
                pair.first
            }
        )
        val rememberedStore = InMemoryRememberedRadioStore(remembered)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore,
            driverFactory = { openLink ->
                KissTncDriver(
                    scope = backgroundScope,
                    openLink = openLink,
                    config = KissTncConfig(reconnectBaseMs = 20L, reconnectCapMs = 50L)
                )
            }
        )

        manager.enable()
        runCurrent()

        assertTrue(manager.state.value.isConnectedAndReady, "Initial connection must be ready")

        // Simulate power-cycle: radio shuts down and drops link
        currentLinkPair?.first?.breakLink()
        currentLinkPair = null // link supplier will supply a fresh link on next open
        advanceTimeBy(30)
        runCurrent()

        // Advance time to allow driver reconnect loop to reopen
        advanceTimeBy(100)
        runCurrent()

        assertTrue(manager.state.value.isConnectedAndReady, "Radio must recover automatically after power-cycle")
    }

    @Test
    fun radlnkU07_flappingLinkResilienceNoLoopCrash() = runTest {
        val remembered = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
        var opens = 0

        val discovery = FakeRadioDeviceDiscovery(
            linkSupplier = {
                opens++
                val pair = inMemoryLinkPair("flapping-$opens")
                if (opens <= 10) {
                    pair.first.breakLink() // close immediately
                }
                pair.first
            }
        )
        val rememberedStore = InMemoryRememberedRadioStore(remembered)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore,
            driverFactory = { openLink ->
                KissTncDriver(
                    scope = backgroundScope,
                    openLink = openLink,
                    config = KissTncConfig(reconnectBaseMs = 1L, reconnectCapMs = 5L)
                )
            },
            maxRetryAttempts = 15
        )

        manager.enable()
        advanceTimeBy(500)
        runCurrent()

        // System stayed stable without crashing or thread exhaustion
        assertTrue(opens >= 10, "Attempted reconnects through flapping")
    }

    @Test
    fun radlnkU08_profileMRequiresValidAuthorizationConfirmationDate() = runTest {
        val discovery = FakeRadioDeviceDiscovery()
        val rememberedStore = InMemoryRememberedRadioStore(null)
        val authStore = InMemoryRadioAuthorisationStore(null)

        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = rememberedStore,
            authorisationStore = authStore
        )

        // Profile A is allowed by default
        manager.setProfile(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED)
        assertEquals(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, manager.state.value.currentProfile)

        // Profile M without authorization throws IllegalStateException
        var failed = false
        try {
            manager.setProfile(RadioSecurityProfile.PROFILE_M_ENCRYPTED)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed, "Profile M must be rejected without recorded authorization")

        // Record valid authorization with positive epoch timestamp
        val confirmation = AuthorisationConfirmation(
            confirmedAtEpochMs = 1729000000000L,
            operatorCallsign = "N0CALL"
        )
        manager.recordAuthorisation(confirmation)

        // Profile M is now selectable
        manager.setProfile(RadioSecurityProfile.PROFILE_M_ENCRYPTED)
        assertEquals(RadioSecurityProfile.PROFILE_M_ENCRYPTED, manager.state.value.currentProfile)
    }

    /** A link that looks like a real COM port (not "in-memory", not "fake") and records every write: no radio is touched. */
    private class RecordingComLink : ByteLink {
        override val description: String = "COM16 (Silicon Labs CP210x)"
        val writes = mutableListOf<ByteArray>()

        /** When set, a DATA frame write waits for it (keeps a send "in flight"). */
        var dataGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
            delay(timeoutMs)
            return 0
        }
        override suspend fun write(data: ByteArray) {
            val isData = (data.getOrNull(1)?.toInt() ?: -1) and 0x0F == 0
            if (isData) dataGate?.await()
            writes += data.copyOf()
        }
        override fun close() {}

        /** KISS command nibble 0 = DATA frame (the kind that makes a radio transmit). */
        fun dataFrames(): Int = writes.count { (it.getOrNull(1)?.toInt() ?: -1) and 0x0F == 0 }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.connectedManager(
        link: ByteLink,
        auth: AuthorisationConfirmation?,
    ): RadioGatewayManager {
        val remembered = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = FakeRadioDeviceDiscovery(linkSupplier = { link }),
            rememberedStore = InMemoryRememberedRadioStore(remembered),
            authorisationStore = InMemoryRadioAuthorisationStore(auth),
            driverFactory = { openLink -> KissTncDriver(scope = backgroundScope, openLink = openLink) }
        )
        manager.enable()
        runCurrent()
        assertTrue(manager.state.value.isConnectedAndReady, "link must be connected for this test")
        return manager
    }

    @Test
    fun radlnkU09a_noDataFrameUnlessConnectedConfirmedAuthorisedAndValid() = runTest {
        // Not connected (gateway never enabled): nothing to write to.
        val offManager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = FakeRadioDeviceDiscovery(),
            rememberedStore = InMemoryRememberedRadioStore(null),
            authorisationStore = InMemoryRadioAuthorisationStore(AuthorisationConfirmation(1729000000000L, "W1AW")),
        )
        assertEquals(TestMessageResult.NOT_CONNECTED, offManager.requestTestMessage("Hello", userConfirmedTransmit = true))

        // Connected, authorised, but the user did not confirm the transmit warning.
        val link1 = RecordingComLink()
        val m1 = connectedManager(link1, AuthorisationConfirmation(1729000000000L, "W1AW"))
        assertEquals(TestMessageResult.NOT_CONFIRMED, m1.requestTestMessage("Hello", userConfirmedTransmit = false))
        assertEquals(0, link1.dataFrames(), "no data frame without the user's confirmation")

        // Connected and confirmed, but no authorisation record.
        val link2 = RecordingComLink()
        val m2 = connectedManager(link2, null)
        assertEquals(TestMessageResult.NOT_AUTHORISED, m2.requestTestMessage("Hello", userConfirmedTransmit = true))
        assertEquals(0, link2.dataFrames(), "no data frame without the authorisation record")

        // Connected, confirmed, authorised, but invalid text.
        val link3 = RecordingComLink()
        val m3 = connectedManager(link3, AuthorisationConfirmation(1729000000000L, "W1AW"))
        assertEquals(TestMessageResult.INVALID_TEXT, m3.requestTestMessage("", userConfirmedTransmit = true))
        val tooLong = "x".repeat(RadioGatewayManager.MAX_TEST_MESSAGE_BYTES + 1)
        assertEquals(TestMessageResult.INVALID_TEXT, m3.requestTestMessage(tooLong, userConfirmedTransmit = true))
        assertEquals(0, link3.dataFrames(), "no data frame for empty or oversized text")
    }

    @Test
    fun radwireU27_testMessageNeedsAValidCallsign_neverGoesOutAsNocall() = runTest {
        for (callsign in listOf(null, "NOCALL", "N0CALL", "W1", "W1AW-99")) {
            val link = RecordingComLink()
            val m = connectedManager(link, AuthorisationConfirmation(1729000000000L, callsign))
            assertEquals(
                TestMessageResult.NO_CALLSIGN,
                m.requestTestMessage("Hello", userConfirmedTransmit = true),
                "callsign <$callsign>",
            )
            assertEquals(0, link.dataFrames(), "no data frame for callsign <$callsign>")
        }
        // A valid one still sends, and the frame carries it as the AX.25 source (not NOCALL).
        val link = RecordingComLink()
        val m = connectedManager(link, AuthorisationConfirmation(1729000000000L, "w1aw-7"))
        assertEquals(TestMessageResult.SENT, m.requestTestMessage("Hello", userConfirmedTransmit = true))
        assertEquals(1, link.dataFrames())
        val frame = link.writes.last { (it.getOrNull(1)?.toInt() ?: -1) and 0x0F == 0 }
        val ax25 = com.transfer.flash.core.radio.kiss.KissStreamDecoder().feed(frame).first().data
        val decoded = com.transfer.flash.core.radio.kiss.Ax25FrameCodec.decode(ax25)
        assertEquals("W1AW-7", (decoded as com.transfer.flash.core.radio.kiss.Ax25DecodeResult.Ok).frame.source.toString())
    }

    @Test
    fun radlnkU09b_confirmedAuthorisedMessageIsWrittenOnAnyLinkType() = runTest {
        // The decision is the user's: a real-looking COM link must NOT be refused by guessing the link type.
        val link = RecordingComLink()
        val manager = connectedManager(link, AuthorisationConfirmation(1729000000000L, "W1AW"))
        val pending = async { manager.requestTestMessage("Hello Radio", userConfirmedTransmit = true) }
        advanceUntilIdle()
        assertEquals(TestMessageResult.SENT, pending.await())
        assertEquals(1, link.dataFrames(), "exactly one data frame is written for one confirmed test message")
        manager.disable()
    }

    @Test
    fun radlnkU09c_secondTestMessageWhileOneIsInFlightIsBusy() = runTest {
        val link = RecordingComLink()
        val manager = connectedManager(link, AuthorisationConfirmation(1729000000000L, "W1AW"))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        link.dataGate = gate // the first data frame stays "in flight" until the gate opens
        val first = async { manager.requestTestMessage("one", userConfirmedTransmit = true) }
        advanceTimeBy(5_000L)
        runCurrent()
        val second = manager.requestTestMessage("two", userConfirmedTransmit = true)
        assertEquals(TestMessageResult.BUSY, second)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(TestMessageResult.SENT, first.await())
        assertEquals(1, link.dataFrames(), "the busy request must not add a second data frame")
        manager.disable()
    }

    @Test
    fun radlnkU09d_stopScanRightAfterStartScanNeverLeavesAScanRunning() = runTest {
        val discovery = FakeRadioDeviceDiscovery(
            discoveredOnScan = listOf(DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = false))
        )
        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = InMemoryRememberedRadioStore(null),
            authorisationStore = InMemoryRadioAuthorisationStore(null)
        )
        manager.enable()
        runCurrent()

        manager.startScan(10_000L)
        manager.stopScan() // before the scan coroutine had a chance to run
        runCurrent()
        advanceTimeBy(20_000L)
        runCurrent()

        assertFalse(discovery.isScanning, "a stop issued immediately after start must win")
    }

    @Test
    fun radlnkU10_plainSentenceAndNextStepNonBlankAcrossAllStates() = runTest {
        val discovery = FakeRadioDeviceDiscovery()
        val manager = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discovery,
            rememberedStore = InMemoryRememberedRadioStore(null),
            authorisationStore = InMemoryRadioAuthorisationStore(null)
        )

        // Off state
        val s1 = manager.state.value
        assertTrue(s1.plainSentence.isNotBlank())
        assertTrue(s1.nextStep.isNotBlank())

        manager.enable()
        runCurrent()

        // Checklist state
        val s2 = manager.state.value
        assertTrue(s2.plainSentence.isNotBlank())
        assertTrue(s2.nextStep.isNotBlank())

        manager.acknowledgeChecklist()
        runCurrent()

        // Detecting / ChooseFromList state
        val s3 = manager.state.value
        assertTrue(s3.plainSentence.isNotBlank())
        assertTrue(s3.nextStep.isNotBlank())

        manager.disable()
    }
}
