package com.transfer.flash.desktop.radio

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.radio.connect.AuthorisationConfirmation
import com.transfer.flash.core.radio.connect.CandidateCategory
import com.transfer.flash.core.radio.connect.InMemoryRadioAuthorisationStore
import com.transfer.flash.core.radio.connect.InMemoryRememberedRadioStore
import com.transfer.flash.core.radio.connect.RememberedRadio
import com.transfer.flash.core.radio.driver.KissTncConfig
import com.transfer.flash.core.radio.driver.KissTncDriver
import com.transfer.flash.core.radio.link.ByteLink
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.FakeRadioDeviceDiscovery
import com.transfer.flash.core.radio.sim.InMemoryLink
import com.transfer.flash.core.radio.sim.inMemoryLinkPair
import com.transfer.flash.ui.radio.FlashRadioCopy
import com.transfer.flash.ui.radio.FlashRadioMath
import com.transfer.flash.ui.radio.RadioConnectionStatus
import com.transfer.flash.ui.radio.RadioDeviceCategory
import com.transfer.flash.ui.radio.RadioFailureReason
import com.transfer.flash.ui.radio.RadioSecurityProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRadioGatewayControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private class TestClock(var currentMs: Long = 1_729_000_000_000L) : FlashTimeSource {
        override fun nowMs(): Long = currentMs
    }

    private class RecordingComLink : ByteLink {
        override val description: String = "COM16 (Silicon Labs CP210x)"
        val writes = mutableListOf<ByteArray>()
        override suspend fun read(buffer: ByteArray, timeoutMs: Long): Int {
            delay(timeoutMs)
            return 0
        }
        override suspend fun write(data: ByteArray) {
            writes += data.copyOf()
        }
        override fun close() {}
        fun dataFrames(): Int = writes.count { (it.getOrNull(1)?.toInt() ?: -1) and 0x0F == 0 }
    }

    @Test
    fun radwireU01_lifetime_switchStoredOff_managerNotCreated() = runBlocking {
        val clock = TestClock()
        var isStored = false
        var discoveryCreated = false
        val discovery = FakeRadioDeviceDiscovery()

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-off"),
            clock = clock,
            isSwitchEnabledStored = { isStored },
            setSwitchEnabledStored = { isStored = it },
            discoveryFactory = {
                discoveryCreated = true
                discovery
            },
            rememberedStoreFactory = { InMemoryRememberedRadioStore(null) },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
        )
        delay(50)

        // Verifications:
        assertFalse(controller.state.value.enabled, "Gateway UI state must be disabled when switch is stored OFF")
        assertEquals(RadioConnectionStatus.OFF, controller.state.value.connectionStatus)
        assertEquals(RadioFailureReason.NONE, controller.state.value.failureReason)
        assertEquals(FlashRadioCopy.SENTENCE_OFF, controller.state.value.statusSentence)
        assertFalse(discoveryCreated, "Rule: Discovery MUST NOT be initialized when switch is OFF")
        assertEquals(0, discovery.openedRadios.size, "Rule: Zero radios opened when switch is OFF")
    }

    @Test
    fun radwireU02_lifetime_mutationCheck_nothingRunsWhenSwitchOff_startsWhenStoredOn() = runBlocking {
        val clock = TestClock()
        var isStored = true
        var discoveryCreated = false
        val candidate = DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = true, category = CandidateCategory.KNOWN_RADIO)
        val discovery = FakeRadioDeviceDiscovery(pairedList = mutableListOf(candidate))
        val rememberedStore = InMemoryRememberedRadioStore(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-on"),
            clock = clock,
            isSwitchEnabledStored = { isStored },
            setSwitchEnabledStored = { isStored = it },
            discoveryFactory = {
                discoveryCreated = true
                discovery
            },
            rememberedStoreFactory = { rememberedStore },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
        )
        delay(100)

        assertTrue(controller.state.value.enabled, "Gateway UI state must be enabled at startup when stored switch is ON")
        assertTrue(discoveryCreated, "Discovery must be initialized when stored switch is ON")
    }

    @Test
    fun radwireU03_lifecycle_enableAndChecklist() = runBlocking {
        val clock = TestClock()
        var isStored = false
        val discovery = FakeRadioDeviceDiscovery()
        val rememberedStore = InMemoryRememberedRadioStore(null)

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-enable"),
            clock = clock,
            isSwitchEnabledStored = { isStored },
            setSwitchEnabledStored = { isStored = it },
            discoveryFactory = { discovery },
            rememberedStoreFactory = { rememberedStore },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
        )
        delay(50)
        assertFalse(controller.state.value.enabled)

        controller.setEnabled(true)
        delay(100)

        assertTrue(controller.state.value.enabled, "Gateway must be enabled after setEnabled(true)")
        assertTrue(isStored, "Stored preference must be persisted as true")
    }

    @Test
    fun radwireU04_lifecycle_autoDetectAndCandidateConfirmation() = runBlocking {
        val clock = TestClock()
        var isStored = false
        val candidate = DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = true, category = CandidateCategory.KNOWN_RADIO)
        val discovery = FakeRadioDeviceDiscovery(pairedList = mutableListOf(candidate))
        val rememberedStore = InMemoryRememberedRadioStore(null)

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-autodetect"),
            clock = clock,
            isSwitchEnabledStored = { isStored },
            setSwitchEnabledStored = { isStored = it },
            discoveryFactory = { discovery },
            rememberedStoreFactory = { rememberedStore },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
        )
        controller.setEnabled(true)
        delay(150)

        // Auto-detect triggers when checklist is acknowledged: exactly one candidate VR-N76
        val state = controller.state.value
        assertEquals(RadioConnectionStatus.CONFIRMING_CANDIDATE, state.connectionStatus)
        assertEquals("VR-N76", state.candidateRadio?.name)
        assertEquals("38:D2:00:01:35:B5", state.candidateRadio?.id)
        assertEquals(RadioDeviceCategory.KNOWN_RADIO, state.candidateRadio?.category)

        // User confirms candidate: moves to CONNECTING/CONNECTED
        controller.confirmCandidate("38:D2:00:01:35:B5")
        delay(100)

        val afterConfirm = controller.state.value
        assertTrue(
            afterConfirm.connectionStatus == RadioConnectionStatus.CONNECTING ||
            afterConfirm.connectionStatus == RadioConnectionStatus.CONNECTED,
            "State after confirming candidate should be CONNECTING or CONNECTED but was ${afterConfirm.connectionStatus}"
        )
    }

    @Test
    fun radwireU05_lifecycle_connectedStateAndDiagnostics() = runBlocking {
        val clock = TestClock()
        val candidate = DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = true, category = CandidateCategory.KNOWN_RADIO)
        val (hostLink, radioLink) = inMemoryLinkPair("radwire-conn")
        val discovery = FakeRadioDeviceDiscovery(
            pairedList = mutableListOf(candidate),
            linkSupplier = { hostLink }
        )
        val rememberedStore = InMemoryRememberedRadioStore(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-connected"),
            clock = clock,
            isSwitchEnabledStored = { true },
            setSwitchEnabledStored = {},
            discoveryFactory = { discovery },
            rememberedStoreFactory = { rememberedStore },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
            driverFactory = { openLink -> KissTncDriver(scope = testScope, openLink = openLink) }
        )
        delay(150)

        val state = controller.state.value
        assertEquals(RadioConnectionStatus.CONNECTED, state.connectionStatus)
        assertEquals("VR-N76", state.rememberedRadioName)
        assertEquals("38:D2:00:01:35:B5", state.rememberedRadioAddress)
        assertTrue(state.statusSentence.contains("Connected to VR-N76"), "Sentence should indicate connected: ${state.statusSentence}")
    }

    @Test
    fun radwireU06_lifecycle_testMessageHonestRfGate() = runBlocking {
        val clock = TestClock()
        val candidate = DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = true, category = CandidateCategory.KNOWN_RADIO)
        val link = RecordingComLink()
        val discovery = FakeRadioDeviceDiscovery(
            pairedList = mutableListOf(candidate),
            linkSupplier = { link }
        )
        val rememberedStore = InMemoryRememberedRadioStore(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))
        val authStore = InMemoryRadioAuthorisationStore(AuthorisationConfirmation(clock.nowMs(), "W1AW"))

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-testmsg"),
            clock = clock,
            isSwitchEnabledStored = { true },
            setSwitchEnabledStored = {},
            discoveryFactory = { discovery },
            rememberedStoreFactory = { rememberedStore },
            authorisationStoreFactory = { authStore },
            driverFactory = { openLink -> KissTncDriver(scope = testScope, openLink = openLink) }
        )
        delay(150)
        assertEquals(RadioConnectionStatus.CONNECTED, controller.state.value.connectionStatus)

        // Initial state: not sending, no test message success recorded
        assertFalse(controller.state.value.isSendingTestMessage)
        assertNull(controller.state.value.testMessageSentSuccess)

        // Request send test message
        controller.sendTestMessage()
        delay(200)

        // Verification: test message sent successfully and frame written to link
        assertFalse(controller.state.value.isSendingTestMessage)
        assertEquals(true, controller.state.value.testMessageSentSuccess)
        assertTrue(link.dataFrames() >= 1, "At least one KISS DATA frame must be written to link")
    }

    @Test
    fun radwireU07_security_profileMAuthorisationGate_mutationCheck() = runBlocking {
        val clock = TestClock()
        val authStore = InMemoryRadioAuthorisationStore(null)

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-prof-m"),
            clock = clock,
            isSwitchEnabledStored = { true },
            setSwitchEnabledStored = {},
            discoveryFactory = { FakeRadioDeviceDiscovery() },
            rememberedStoreFactory = { InMemoryRememberedRadioStore(null) },
            authorisationStoreFactory = { authStore },
        )
        delay(50)

        // Profile M requires prior regulatory authorization
        assertFalse(controller.state.value.isAuthorisedForEncryption)

        // Confirm regulatory authorisation
        controller.confirmAuthorisation()
        delay(150)

        assertTrue(controller.state.value.isAuthorisedForEncryption, "Authorisation must be marked confirmed in UI state")
        assertNotNull(authStore.load(), "Authorisation must be persisted in store")
        assertEquals(RadioSecurityProfile.PROFILE_M, controller.state.value.selectedProfile)
    }

    @Test
    fun radwireU08_lifecycle_linkLossAndReconnect() = runBlocking {
        val clock = TestClock()
        var failLink = false
        var currentLink: InMemoryLink? = null
        val discovery = FakeRadioDeviceDiscovery(
            linkSupplier = {
                if (failLink) {
                    throw RuntimeException("Radio link broken")
                }
                val pair = inMemoryLinkPair("radwire-loss")
                currentLink = pair.first
                pair.first
            }
        )
        val rememberedStore = InMemoryRememberedRadioStore(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-loss"),
            clock = clock,
            isSwitchEnabledStored = { true },
            setSwitchEnabledStored = {},
            discoveryFactory = { discovery },
            rememberedStoreFactory = { rememberedStore },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
            driverFactory = { openLink ->
                KissTncDriver(
                    scope = testScope,
                    openLink = openLink,
                    config = KissTncConfig(reconnectBaseMs = 10L, reconnectCapMs = 20L)
                )
            }
        )
        delay(150)
        assertEquals(RadioConnectionStatus.CONNECTED, controller.state.value.connectionStatus)

        // Simulate link loss and failure to reopen
        failLink = true
        currentLink?.breakLink()
        delay(100)

        // Link loss reflected in UI
        assertEquals(RadioConnectionStatus.LINK_LOST, controller.state.value.connectionStatus)

        // Simulate link recovered and user taps reconnect
        failLink = false
        controller.reconnect()
        delay(150)
        assertEquals(RadioConnectionStatus.CONNECTED, controller.state.value.connectionStatus)
    }

    @Test
    fun radwireU09_lifecycle_disable() = runBlocking {
        val clock = TestClock()
        var isStored = true
        val discovery = FakeRadioDeviceDiscovery()
        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-disable"),
            clock = clock,
            isSwitchEnabledStored = { isStored },
            setSwitchEnabledStored = { isStored = it },
            discoveryFactory = { discovery },
            rememberedStoreFactory = { InMemoryRememberedRadioStore(RememberedRadio("38:D2:00:01:35:B5", "VR-N76")) },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
        )
        delay(50)
        assertTrue(controller.state.value.enabled)

        controller.setEnabled(false)
        delay(50)

        assertFalse(controller.state.value.enabled, "State must be disabled after setEnabled(false)")
        assertFalse(isStored, "Stored preference must be set to false")
        assertEquals(RadioConnectionStatus.OFF, controller.state.value.connectionStatus)
        assertEquals(FlashRadioCopy.SENTENCE_OFF, controller.state.value.statusSentence)
    }

    @Test
    fun radwireU10_mapping_everyFailureReasonAndNextStep() {
        val reasons = listOf(
            RadioFailureReason.NO_ADAPTER to FlashRadioCopy.SENTENCE_NO_ADAPTER,
            RadioFailureReason.BLUETOOTH_OFF to FlashRadioCopy.SENTENCE_BLUETOOTH_OFF,
            RadioFailureReason.PERMISSION_MISSING to FlashRadioCopy.SENTENCE_PERMISSION_MISSING,
            RadioFailureReason.PERMISSION_DENIED to FlashRadioCopy.SENTENCE_PERMISSION_DENIED,
            RadioFailureReason.RADIO_NOT_FOUND to FlashRadioCopy.SENTENCE_RADIO_NOT_FOUND,
            RadioFailureReason.PAIRING_REFUSED to FlashRadioCopy.SENTENCE_PAIRING_REFUSED,
            RadioFailureReason.WRONG_PIN to FlashRadioCopy.SENTENCE_WRONG_PIN,
            RadioFailureReason.PORT_IN_USE to FlashRadioCopy.SENTENCE_PORT_IN_USE,
            RadioFailureReason.KISS_NOT_ENABLED to FlashRadioCopy.SENTENCE_KISS_NOT_ENABLED,
            RadioFailureReason.LINK_LOST to FlashRadioCopy.SENTENCE_LINK_LOST,
        )

        for ((reason, expectedSentence) in reasons) {
            val sentence = FlashRadioMath.statusSentence(RadioConnectionStatus.FAILED, reason)
            val nextStep = FlashRadioMath.statusNextStep(RadioConnectionStatus.FAILED, reason)

            assertEquals(expectedSentence, sentence, "Sentence mismatch for $reason")
            assertTrue(nextStep.isNotBlank(), "Next step must not be blank for $reason")
            assertFalse(sentence.contains("%s"), "Sentence must not have unresolved placeholders for $reason")
            assertFalse(nextStep.contains("%s"), "Next step must not have unresolved placeholders for $reason")
        }
    }

    @Test
    fun radwireU11_redirect_windowsBluetoothSettings() {
        var settingsOpened = false
        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-settings"),
            openSettingsHandler = { settingsOpened = true }
        )
        controller.openSystemBluetoothSettings()
        assertTrue(settingsOpened, "openSettingsHandler must be called on openSystemBluetoothSettings")
    }

    @Test
    fun radwireU12_candidateListMapping() = runBlocking {
        val clock = TestClock()
        val devices = listOf(
            DiscoveredDevice("VR-N76", "38:D2:00:01:35:B5", isPaired = true, category = CandidateCategory.KNOWN_RADIO),
            DiscoveredDevice("Generic SPP", "11:22:33:44:55:66", isPaired = true, category = CandidateCategory.SERIAL_CAPABLE),
            DiscoveredDevice("Headphones", "99:88:77:66:55:44", isPaired = false, category = CandidateCategory.OTHER),
        )
        val discovery = FakeRadioDeviceDiscovery(
            pairedList = devices.filter { it.isPaired }.toMutableList(),
            discoveredOnScan = devices
        )

        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = tempFolder.newFolder("radwire-candidates"),
            clock = clock,
            isSwitchEnabledStored = { true },
            setSwitchEnabledStored = {},
            discoveryFactory = { discovery },
            rememberedStoreFactory = { InMemoryRememberedRadioStore(null) },
            authorisationStoreFactory = { InMemoryRadioAuthorisationStore(null) },
        )
        delay(50)

        controller.startScan()
        delay(100)

        val scanned = controller.state.value.discoveredDevices
        assertTrue(scanned.any { it.name == "VR-N76" && it.category == RadioDeviceCategory.KNOWN_RADIO })
        assertTrue(scanned.any { it.name == "Generic SPP" && it.category == RadioDeviceCategory.SERIAL_SPP })
        assertTrue(scanned.any { it.name == "Headphones" && it.category == RadioDeviceCategory.OTHER })
    }

    @Test
    fun radwireU18_switchLivesInItsOwnFile_andSurvivesStoreWrites() = runBlocking {
        val dir = tempFolder.newFolder("radwire-own-file")
        val controller = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = dir,
            discoveryFactory = { FakeRadioDeviceDiscovery() },
        )
        controller.setEnabled(true)
        delay(100)

        // The remembered-radio store rewrites radio.properties as a whole; the switch must not be in it.
        com.transfer.flash.core.radio.connect.DesktopPropertiesRadioStore(dir).rememberedStore
            .save(RememberedRadio("38:D2:00:01:35:B5", "VR-N76"))
        val storeFile = java.io.File(dir, "radio.properties")
        assertTrue(storeFile.isFile)
        assertFalse(storeFile.readText().contains("gateway.enabled"), "the switch must not live in the store's file")
        assertTrue(java.io.File(dir, "radio-gateway.properties").readText().contains("gateway.enabled=true"))

        // A fresh controller (next app start) reads the switch back.
        val second = DesktopRadioGatewayController(
            scope = testScope,
            stateDir = dir,
            discoveryFactory = { FakeRadioDeviceDiscovery() },
        )
        delay(100)
        assertTrue(second.state.value.enabled, "stored ON must be read back after a store write")
    }
}
