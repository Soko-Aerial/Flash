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

    @Test
    fun tofuPinVerifierTrustsAndPinsOnFirstConnect() {
        val store = mutableMapOf<String, String>()
        val verifier = TofuPinVerifier(
            lookupPin = { store[it] },
            recordPin = { id, pin -> store[id] = pin },
        )

        // First connection: no pin stored yet
        val firstResult = verifier.isPinned("device-a", "aa:bb:cc:dd")
        assertTrue("first connection must be trusted in TOFU", firstResult)
        assertEquals("AABBCCDD", store["device-a"])

        // Second connection with same key: matches
        val secondResult = verifier.isPinned("device-a", "AA:BB:CC:DD")
        assertTrue("subsequent connection with same key must match", secondResult)

        // Impersonation attempt with changed key: rejected
        val attackResult = verifier.isPinned("device-a", "11:22:33:44")
        assertFalse("key change must be rejected (fail closed)", attackResult)
    }

    @Test
    fun tofuPinVerifierObservesFirstConnectKeyWhenThePinWriteIsRefused() {
        // ADR-353: a revoked peer's automatic pin write is refused until a deliberate pairing clears
        // the tombstone. The first outbound dial after that revoke must still report the key this
        // connection presented, so the pairing numeric comparison covers the TLS-authenticated key
        // and the user can reach the consent card (ERROR-1013).
        val store = mutableMapOf<String, String>()
        val observed = mutableMapOf<String, String>()
        val verifier = TofuPinVerifier(
            lookupPin = { store[it] },
            recordPin = { _, _ -> /* revoked: refused */ },
            observeInbound = { id, pin -> observed[id] = pin },
        )

        assertTrue(verifier.isPinned("device-a", "aa:bb:cc:dd"))
        assertEquals("AABBCCDD", observed["device-a"])
        assertTrue("a refused pin write must not populate the trust store", store.isEmpty())
    }
}

