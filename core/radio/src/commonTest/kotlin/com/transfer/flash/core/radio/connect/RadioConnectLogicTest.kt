package com.transfer.flash.core.radio.connect

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioConnectLogicTest {

    // --- RADCONL-U01: Candidate Ranking --------------------------------------------------------------------------

    @Test
    fun radconlU01CandidateRankingKnownRadiosFirstThenSerialThenOther() {
        val vrN76 = RadioCandidateDevice("VR-N76", "AA:BB:CC:11:22:33", CandidateKind.BLUETOOTH_SERIAL, isPaired = true, rssi = -60)
        val uvProWithSuffix = RadioCandidateDevice("UV-Pro (COM16)", "COM16", CandidateKind.BLUETOOTH_SERIAL, isPaired = false, rssi = -70)
        val plainSerial = RadioCandidateDevice("USB-Serial Controller", "COM3", CandidateKind.USB_SERIAL, isPaired = true, rssi = null)
        val bluetoothSerial = RadioCandidateDevice("Generic SPP Device", "11:22:33:44:55:66", CandidateKind.BLUETOOTH_SERIAL, isPaired = false, rssi = -80)
        val audioHeadset = RadioCandidateDevice("BT Headset", "99:88:77:66:55:44", CandidateKind.BLUETOOTH_OTHER, isPaired = true, rssi = -50)
        val bleTag = RadioCandidateDevice("Smart Tag", "AA:11:22:33:44:55", CandidateKind.BLUETOOTH_LE, isPaired = false, rssi = -40)

        val unranked = listOf(audioHeadset, plainSerial, bleTag, uvProWithSuffix, bluetoothSerial, vrN76)
        val ranked = RadioCandidateRanker.rank(unranked)

        assertEquals(6, ranked.size)

        // Tier 1: Known radios
        assertEquals(CandidateCategory.KNOWN_RADIO, ranked[0].category)
        assertEquals(CandidateCategory.KNOWN_RADIO, ranked[1].category)
        // VR-N76 is paired, so it ranks above unpaired UV-Pro
        assertEquals("VR-N76", ranked[0].device.name)
        assertEquals("UV-Pro (COM16)", ranked[1].device.name)

        // Tier 2: Serial capable devices
        assertEquals(CandidateCategory.SERIAL_CAPABLE, ranked[2].category)
        assertEquals(CandidateCategory.SERIAL_CAPABLE, ranked[3].category)
        // Plain serial is paired, so ranks above unpaired generic SPP
        assertEquals("USB-Serial Controller", ranked[2].device.name)
        assertEquals("Generic SPP Device", ranked[3].device.name)

        // Tier 3: Other devices
        assertEquals(CandidateCategory.OTHER, ranked[4].category)
        assertEquals(CandidateCategory.OTHER, ranked[5].category)
    }

    // --- RADCONL-U02: Auto-detect Exactly-One Rule ----------------------------------------------------------------

    @Test
    fun radconlU02AutoDetectSelectsOnlyWhenExactlyOneCandidateMatches() {
        val vrN76Paired = RadioCandidateDevice("VR-N76", "AA:BB:CC:11:22:33", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)
        val uvProPaired = RadioCandidateDevice("BTECH UV-PRO", "AA:BB:CC:44:55:66", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)
        val genericSerialPaired = RadioCandidateDevice("Standard Serial", "COM5", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)
        val secondSerialPaired = RadioCandidateDevice("USB Serial", "COM8", CandidateKind.USB_SERIAL, isPaired = true)
        val otherDevicePaired = RadioCandidateDevice("Keyboard", "AA:00:11:22:33:44", CandidateKind.OTHER, isPaired = true)
        val unpairedRadio = RadioCandidateDevice("VR-N76", "BB:CC:DD:11:22:33", CandidateKind.BLUETOOTH_SERIAL, isPaired = false)

        // Case 1: Exactly one known radio paired -> returns it
        val auto1 = RadioCandidateRanker.autoDetectCandidate(listOf(vrN76Paired, otherDevicePaired))
        assertNotNull(auto1)
        assertEquals("VR-N76", auto1.name)

        // Case 2: Two known radios paired -> ambiguous, returns null
        val auto2 = RadioCandidateRanker.autoDetectCandidate(listOf(vrN76Paired, uvProPaired))
        assertNull(auto2, "Multiple known radios must not be auto-preselected")

        // Case 3: Zero known radios, exactly one serial-capable paired -> returns it
        val auto3 = RadioCandidateRanker.autoDetectCandidate(listOf(genericSerialPaired, otherDevicePaired))
        assertNotNull(auto3)
        assertEquals("Standard Serial", auto3.name)

        // Case 4: Zero known radios, two serial-capable paired -> ambiguous, returns null
        val auto4 = RadioCandidateRanker.autoDetectCandidate(listOf(genericSerialPaired, secondSerialPaired))
        assertNull(auto4, "Multiple serial devices must not be auto-preselected")

        // Case 5: Known radio exists but is NOT paired -> returns null
        val auto5 = RadioCandidateRanker.autoDetectCandidate(listOf(unpairedRadio, otherDevicePaired))
        assertNull(auto5, "Unpaired radio must not be auto-preselected")

        // Case 6: Only OTHER devices paired -> NEVER selects an OTHER device, returns null
        val auto6 = RadioCandidateRanker.autoDetectCandidate(listOf(otherDevicePaired))
        assertNull(auto6, "OTHER category devices must never be selected by auto-detect")
    }

    // --- RADCONL-U03: Never Open Before Confirm ------------------------------------------------------------------

    @Test
    fun radconlU03NeverOpenLinkBeforeExplicitConfirmation() = runTest {
        val store = InMemoryRememberedRadioStore()
        val sm = RadioConnectStateMachine(store)

        // Step 1: Switch gateway on with no remembered radio -> ChecklistShown
        val cmd1 = sm.transition(RadioConnectEvent.ToggleGateway(true))
        assertEquals(RadioConnectState.ChecklistShown, sm.state)
        assertTrue(cmd1.none { it is RadioConnectCommand.OpenLink }, "Must not open link on gateway toggle")

        // Step 2: User acknowledges checklist -> Detecting
        val cmd2 = sm.transition(RadioConnectEvent.ChecklistAcknowledged)
        assertEquals(RadioConnectState.Detecting, sm.state)
        assertEquals(listOf(RadioConnectCommand.DetectPairedDevices), cmd2)
        assertTrue(cmd2.none { it is RadioConnectCommand.OpenLink }, "Must not open link while detecting")

        // Step 3: Exactly one candidate detected -> ConfirmCandidate (offered as 'probably your radio')
        val candidate = RadioCandidateDevice("VR-N76", "AA:BB:CC:11:22:33", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)
        val cmd3 = sm.transition(RadioConnectEvent.DetectionResult(listOf(candidate)))
        assertEquals(RadioConnectState.ConfirmCandidate(candidate), sm.state)
        assertTrue(cmd3.isEmpty(), "Must not emit commands before user confirmation")
        assertTrue(cmd3.none { it is RadioConnectCommand.OpenLink }, "Rule 3: NEVER open link before confirmation")

        // Step 4: User explicitly confirms -> transitions to Connecting and NOW emits OpenLink
        val cmd4 = sm.transition(RadioConnectEvent.CandidateConfirmed(candidate))
        assertEquals(RadioConnectState.Connecting(candidate), sm.state)
        assertEquals(listOf(RadioConnectCommand.OpenLink(candidate)), cmd4)
    }

    // --- RADCONL-U04: Radio Identity is Bluetooth Address, Not Port ----------------------------------------------

    @Test
    fun radconlU04RadioIdentityIsBluetoothAddressNotPort() {
        val radio1 = RememberedRadio(
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            displayName = "VGC VR-N76",
            lastKnownPortName = "COM16",
        )
        assertEquals("AA:BB:CC:DD:EE:FF", radio1.bluetoothAddress)
        assertEquals("COM16", radio1.lastKnownPortName)

        // When Windows renumbers COM16 to COM7 after unpair/re-pair, the identity is unchanged:
        val radioAfterRenumbering = radio1.copy(lastKnownPortName = "COM7")
        assertEquals(radio1.bluetoothAddress, radioAfterRenumbering.bluetoothAddress)
        assertEquals("VGC VR-N76", radioAfterRenumbering.displayName)
        assertEquals("COM7", radioAfterRenumbering.lastKnownPortName)

        // On Android, lastKnownPortName is naturally null
        val androidRadio = RememberedRadio(
            bluetoothAddress = "11:22:33:44:55:66",
            displayName = "UV-Pro",
            lastKnownPortName = null,
        )
        assertNull(androidRadio.lastKnownPortName)

        // Validation: blank address or display name is rejected
        assertFailsWith<IllegalArgumentException> {
            RememberedRadio(bluetoothAddress = "", displayName = "VR-N76")
        }
        assertFailsWith<IllegalArgumentException> {
            RememberedRadio(bluetoothAddress = "AA:BB:CC:DD:EE:FF", displayName = "")
        }
    }

    // --- RADCONL-U05: Failure Sentences Present and Non-Empty ----------------------------------------------------

    @Test
    fun radconlU05EveryFailureReasonHasSentenceAndNextStep() {
        val reasons = RadioConnectFailureReason.entries
        assertEquals(9, reasons.size, "Must cover all 9 specified failure reasons")

        val expected = setOf(
            RadioConnectFailureReason.NO_ADAPTER,
            RadioConnectFailureReason.BLUETOOTH_OFF,
            RadioConnectFailureReason.PERMISSION_MISSING,
            RadioConnectFailureReason.RADIO_NOT_FOUND,
            RadioConnectFailureReason.PAIRING_REFUSED,
            RadioConnectFailureReason.WRONG_PIN,
            RadioConnectFailureReason.PORT_IN_USE,
            RadioConnectFailureReason.KISS_TNC_NOT_ENABLED,
            RadioConnectFailureReason.LINK_LOST,
        )
        assertEquals(expected, reasons.toSet())

        for (reason in reasons) {
            val sentence = reason.sentence
            val nextStep = reason.nextStep

            assertTrue(sentence.isNotBlank(), "${reason.name} sentence must not be blank")
            assertTrue(sentence.split(" ").size >= 3, "${reason.name} sentence must be at least 3 words")
            assertTrue(sentence.endsWith(".") || sentence.endsWith("!"), "${reason.name} sentence must end with punctuation")

            assertTrue(nextStep.isNotBlank(), "${reason.name} nextStep must not be blank")
            assertTrue(nextStep.split(" ").size >= 3, "${reason.name} nextStep must be at least 3 words")
            assertTrue(nextStep.endsWith(".") || nextStep.endsWith(")"), "${reason.name} nextStep must end with punctuation")
        }
    }

    // --- RADCONL-U06: No Transmit Command Except Authorised Test Message -----------------------------------------

    @Test
    fun radconlU06NoTransmitCommandExceptUserRequestedTestMessage() = runTest {
        val store = InMemoryRememberedRadioStore(
            RememberedRadio("AA:BB:CC:11:22:33", "VR-N76")
        )
        val sm = RadioConnectStateMachine(store)

        // Connect directly via remembered radio
        val cmd1 = sm.transition(RadioConnectEvent.ToggleGateway(true))
        val target = RadioCandidateDevice("VR-N76", "AA:BB:CC:11:22:33", CandidateKind.BLUETOOTH_SERIAL, isPaired = true)
        assertEquals(RadioConnectState.Connecting(target), sm.state)
        assertEquals(listOf(RadioConnectCommand.OpenLink(target)), cmd1)

        val remembered = RememberedRadio("AA:BB:CC:11:22:33", "VR-N76")
        val cmd2 = sm.transition(RadioConnectEvent.ConnectionSucceeded(remembered))
        assertEquals(RadioConnectState.Connected(remembered), sm.state)

        // Attempting to send test message without authorisation flag produces NO command
        val cmdUnauth = sm.transition(RadioConnectEvent.SendTestMessage("Test message", isAuthorised = false))
        assertTrue(cmdUnauth.isEmpty(), "Unauthorized test message must not emit any command")

        // Explicitly authorized user test message emits UserRequestedTestMessage
        val cmdAuth = sm.transition(RadioConnectEvent.SendTestMessage("Test message", isAuthorised = true))
        assertEquals(1, cmdAuth.size)
        val txCmd = cmdAuth.single()
        assertIs<RadioConnectCommand.UserRequestedTestMessage>(txCmd)
        assertEquals("Test message", txCmd.text)
        assertTrue(txCmd.isAuthorised)

        // Command constructor itself enforces authorisation flag
        assertFailsWith<IllegalArgumentException> {
            RadioConnectCommand.UserRequestedTestMessage(remembered, "Test", isAuthorised = false)
        }
    }

    // --- RADCONL-U07: Authorisation Gate -------------------------------------------------------------------------

    @Test
    fun radconlU07AuthorisationGateEnforcesConfirmationAndDate() {
        val gate = RadioAuthorisationGate()

        // Without confirmation: only Profile A (plaintext signed) is allowed
        assertTrue(gate.isProfileAllowed(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, null))
        assertFalse(gate.isProfileAllowed(RadioSecurityProfile.PROFILE_M_ENCRYPTED, null))
        assertEquals(setOf(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED), gate.selectableProfiles(null))

        // requireProfileAllowed throws for Profile M without confirmation
        assertFailsWith<IllegalStateException> {
            gate.requireProfileAllowed(RadioSecurityProfile.PROFILE_M_ENCRYPTED, null)
        }
        gate.requireProfileAllowed(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, null) // ok

        // Confirmation requires a positive timestamp (date is mandatory)
        assertFailsWith<IllegalArgumentException> {
            AuthorisationConfirmation(confirmedAtEpochMs = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AuthorisationConfirmation(confirmedAtEpochMs = -1)
        }

        // With valid confirmation: both Profile A and Profile M are allowed
        val confirmation = AuthorisationConfirmation(confirmedAtEpochMs = 1_790_000_000_000L, operatorCallsign = "W1AW")
        assertTrue(gate.isProfileAllowed(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, confirmation))
        assertTrue(gate.isProfileAllowed(RadioSecurityProfile.PROFILE_M_ENCRYPTED, confirmation))
        assertEquals(
            setOf(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, RadioSecurityProfile.PROFILE_M_ENCRYPTED),
            gate.selectableProfiles(confirmation)
        )
        gate.requireProfileAllowed(RadioSecurityProfile.PROFILE_M_ENCRYPTED, confirmation) // ok
        gate.requireProfileAllowed(RadioSecurityProfile.PROFILE_A_PLAINTEXT_SIGNED, confirmation) // ok
    }
}
