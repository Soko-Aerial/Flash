package com.transfer.flash.core.network.tls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SEC-D02-02: an unsolicited inbound connection must not write a first-use pin. A stranger who claimed a contact's id
 * used to be recorded under it, after which the real contact was refused as "key changed".
 */
class TofuPinVerifierInboundTest {

    private val stranger = "AA".repeat(32)
    private val contact = "BB".repeat(32)

    @Test
    fun anInboundFirstContactIsAcceptedButNothingIsRecorded() {
        val pins = mutableMapOf<String, String>()
        val verifier = TofuPinVerifier({ pins[it] }, { id, pin -> pins[id] = pin })

        assertTrue(verifier.verifyWithoutRecording("colleague", stranger))

        assertNull(pins["colleague"])
        // The real device can still be first-used afterwards, because the stranger left nothing behind.
        assertTrue(verifier.isPinned("colleague", contact))
        assertEquals(contact, pins["colleague"])
    }

    @Test
    fun anInboundConnectionStillHasToMatchAnExistingPin() {
        val pins = mutableMapOf("colleague" to contact)
        val verifier = TofuPinVerifier({ pins[it] }, { id, pin -> pins[id] = pin })

        assertTrue(verifier.verifyWithoutRecording("colleague", contact))
        assertFalse(verifier.verifyWithoutRecording("colleague", stranger))
        assertEquals(contact, pins["colleague"])
    }

    @Test
    fun anInboundConnectionPresentingThisDevicesOwnKeyIsStillRefused() {
        val verifier = TofuPinVerifier({ null }, ownFingerprintHex = stranger)

        assertFalse(verifier.verifyWithoutRecording("colleague", stranger))
    }

    @Test
    fun aVerifierThatNeverRecordsKeepsTheOldAnswerForInboundChecks() {
        val verifier = FlashPinVerifier { _, fp -> fp == contact }

        assertTrue(verifier.verifyWithoutRecording("colleague", contact))
        assertFalse(verifier.verifyWithoutRecording("colleague", stranger))
    }
}
