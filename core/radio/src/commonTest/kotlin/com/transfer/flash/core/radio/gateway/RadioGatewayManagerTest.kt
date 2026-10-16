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

    @Test
    fun radlnkU09_requestTestMessageSuppressedOnRealRfAndTransmitsOnTestLink() = runTest {
        val remembered = RememberedRadio("38:D2:00:01:35:B5", "VR-N76")

        // 1. Real link (non-in-memory): must suppress test message
        class RealMockByteLink : ByteLink {
            override val description: String = "COM16 (Silicon Labs CP210x)"
            override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
                delay(timeoutMs)
                return 0
            }
            override suspend fun write(data: ByteArray) {
                // Command frames (port parameters) are allowed, data frames forbidden!
                val cmd = data.getOrNull(1)?.toInt()?.and(0x0F) ?: 0
                if (cmd == 0) { // Command 0 is DATA
                    throw AssertionError("Rule 4 violation: test bytes written to real RF link!")
                }
            }
            override fun close() {}
        }

        val discoveryReal = FakeRadioDeviceDiscovery(linkSupplier = { RealMockByteLink() })
        val authStore = InMemoryRadioAuthorisationStore(
            AuthorisationConfirmation(1729000000000L, "W1AW")
        )

        val managerReal = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discoveryReal,
            rememberedStore = InMemoryRememberedRadioStore(remembered),
            authorisationStore = authStore,
            driverFactory = { openLink -> KissTncDriver(scope = backgroundScope, openLink = openLink) }
        )

        managerReal.enable()
        runCurrent()

        val sentOnReal = managerReal.requestTestMessage("Hello Radio")
        assertFalse(sentOnReal, "Rule 4: test message MUST be suppressed on real RF link")
        managerReal.disable()
        runCurrent()

        // 2. In-memory test link: allowed to transmit
        val testPair = inMemoryLinkPair("mem-test")
        val discoveryTest = FakeRadioDeviceDiscovery(linkSupplier = { testPair.first })

        val managerTest = RadioGatewayManager(
            scope = backgroundScope,
            discovery = discoveryTest,
            rememberedStore = InMemoryRememberedRadioStore(remembered),
            authorisationStore = authStore,
            driverFactory = { openLink -> KissTncDriver(scope = backgroundScope, openLink = openLink) }
        )

        managerTest.enable()
        runCurrent()

        assertTrue(managerTest.state.value.isConnectedAndReady, "In-memory test link must be connected")
        val sentOnTest = managerTest.requestTestMessage("Test message in-memory")
        assertTrue(sentOnTest, "Test message must succeed on in-memory test link")
        managerTest.disable()
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
