package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashPairingLogicTest {

    // --- formatCode ---

    @Test
    fun `format code splits six digits into two groups`() {
        assertEquals("123 456", FlashPairingMath.formatCode("123456"))
        assertEquals("427 913", FlashPairingMath.formatCode("427913"))
    }

    @Test
    fun `format code keeps only the first six digits`() {
        assertEquals("987 654", FlashPairingMath.formatCode("9876543210"))
    }

    @Test
    fun `format code strips non-digit characters`() {
        assertEquals("042 550", FlashPairingMath.formatCode("a0-4.2x55 0"))
    }

    @Test
    fun `format code pads short input with zeros`() {
        assertEquals("120 000", FlashPairingMath.formatCode("12"))
        assertEquals("000 000", FlashPairingMath.formatCode(""))
    }

    // --- canConfirm ---

    @Test
    fun `confirm is allowed only while a request awaits decision`() {
        assertTrue(FlashPairingMath.canConfirm(FlashPairingPhase.RequestReceived))
    }

    @Test
    fun `confirm is denied outside the decision window`() {
        val phases = FlashPairingPhase.entries.filter { it != FlashPairingPhase.RequestReceived }
        phases.forEach { phase -> assertFalse(FlashPairingMath.canConfirm(phase)) }
    }

    // --- tickCountdown ---

    @Test
    fun `countdown transitions to expired at zero or below`() {
        assertEquals(FlashPairingPhase.Expired, FlashPairingMath.tickCountdown(0))
        assertEquals(FlashPairingPhase.Expired, FlashPairingMath.tickCountdown(-3))
    }

    @Test
    fun `countdown stays active above zero`() {
        assertEquals(FlashPairingPhase.RequestReceived, FlashPairingMath.tickCountdown(1))
        assertEquals(FlashPairingPhase.RequestReceived, FlashPairingMath.tickCountdown(30))
    }

    // --- phaseStatusLabel ---

    @Test
    fun `status labels map per phase`() {
        assertEquals("Not pairing", FlashPairingMath.phaseStatusLabel(FlashPairingPhase.Idle))
        assertEquals("Pairing request", FlashPairingMath.phaseStatusLabel(FlashPairingPhase.RequestReceived))
        assertEquals("Waiting for peer", FlashPairingMath.phaseStatusLabel(FlashPairingPhase.AwaitingPeerConfirmation))
        assertEquals("Paired", FlashPairingMath.phaseStatusLabel(FlashPairingPhase.Paired))
        assertEquals("Declined", FlashPairingMath.phaseStatusLabel(FlashPairingPhase.Declined))
        assertEquals("Request expired", FlashPairingMath.phaseStatusLabel(FlashPairingPhase.Expired))
    }

    @Test
    fun `status labels are distinct and non-blank`() {
        val labels = FlashPairingPhase.entries.map { FlashPairingMath.phaseStatusLabel(it) }
        assertEquals(labels.size, labels.distinct().size)
        labels.forEach { assertTrue(it.isNotBlank()) }
    }

    // --- initialsFor ---

    @Test
    fun `initials use first letters of first two words uppercased`() {
        assertEquals("AR", FlashPairingMath.initialsFor("Alex Rivera"))
        assertEquals("AL", FlashPairingMath.initialsFor("ann lee"))
        assertEquals("MA", FlashPairingMath.initialsFor("Madonna"))
    }

    @Test
    fun `initials collapse whitespace and handle blanks`() {
        assertEquals("AL", FlashPairingMath.initialsFor("  Ann   Lee  "))
        assertEquals("?", FlashPairingMath.initialsFor("   "))
        assertEquals("?", FlashPairingMath.initialsFor(""))
    }

    // --- transportLabel ---

    @Test
    fun `transport labels are human readable and unknown hides row`() {
        assertEquals("LAN", FlashPairingMath.transportLabel(FlashNetworkTransport.Lan))
        assertEquals("Wi-Fi Direct", FlashPairingMath.transportLabel(FlashNetworkTransport.WifiDirect))
        assertEquals("Relay", FlashPairingMath.transportLabel(FlashNetworkTransport.Relay))
        assertEquals(null, FlashPairingMath.transportLabel(FlashNetworkTransport.Unknown))
    }

    // --- defaults ---

    @Test
    fun `pairing request defaults to thirty second expiry`() {
        assertEquals(30, samplePairingRequest().expiresInSeconds)
    }
}
