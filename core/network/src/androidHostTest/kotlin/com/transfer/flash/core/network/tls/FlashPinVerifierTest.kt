package com.transfer.flash.core.network.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FlashPinVerifierTest {

    @Test
    fun normalizeUppercasesAndStripsColonsAndSpaces() {
        assertEquals("AABBCC", FlashPinVerifier.normalize("aa:bb:cc"))
        assertEquals("AABBCC", FlashPinVerifier.normalize("AA BB CC"))
        assertEquals("AABBCC", FlashPinVerifier.normalize("aA : bB cC"))
        assertEquals("", FlashPinVerifier.normalize(": : "))
    }

    @Test
    fun normalizedFormMatchesContiguousUppercaseHex() {
        val digest = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1, 2, 3))
        val contiguous = digest.joinToString("") { "%02X".format(it) }
        val grouped = digest.joinToString(":") { "%02X".format(it) }
        assertEquals(contiguous, FlashPinVerifier.normalize(grouped))
    }

    @Test
    fun isPinnedIsTheInjectedDecision() {
        val verifier = FlashPinVerifier { _, fp -> fp == "ABCD" }
        assertTrue(verifier.isPinned("device-1", "ABCD"))
        assertFalse(verifier.isPinned("device-1", "FF00"))
    }
}
