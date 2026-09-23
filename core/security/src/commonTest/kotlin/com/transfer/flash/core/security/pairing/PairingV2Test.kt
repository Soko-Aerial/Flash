package com.transfer.flash.core.security.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Properties the v2 code and commitment must have for the protocol argument in ADR-042 to hold. */
class PairingV2Test {

    private val fpI = "11aa22bb33cc44dd55ee66ff77008811aa22bb33cc44dd55ee66ff7700881122"
    private val fpR = "99ff88ee77dd66cc55bb44aa3322110099ff88ee77dd66cc55bb44aa33221100"
    private val epkI = byteArrayOf(1, 2, 3, 4)
    private val epkR = byteArrayOf(5, 6, 7, 8)
    private val nI = ByteArray(PairingV2.NONCE_BYTES) { 1 }
    private val nR = ByteArray(PairingV2.NONCE_BYTES) { 2 }

    private fun code(
        fi: String = fpI, fr: String = fpR, ei: ByteArray = epkI, er: ByteArray = epkR,
        ni: ByteArray = nI, nr: ByteArray = nR,
    ) = PairingV2.deriveCode(fi, fr, ei, er, ni, nr)

    @Test
    fun codeIsSixDigitsAndDeterministic() {
        assertEquals(6, code().length)
        assertTrue(code().all { it.isDigit() })
        assertEquals(code(), code())
    }

    @Test
    fun codeCoversEveryInput() {
        // If any input were ignored, a MITM could change it without the humans seeing a different code.
        val base = code()
        assertNotEquals(base, code(fi = fpR))
        assertNotEquals(base, code(fr = fpI))
        assertNotEquals(base, code(ei = byteArrayOf(9, 9, 9, 9)), "ephemeral keys are covered (v1 was not)")
        assertNotEquals(base, code(er = byteArrayOf(9, 9, 9, 9)))
        assertNotEquals(base, code(ni = ByteArray(16) { 3 }), "fresh nonces make each pairing's code new")
        assertNotEquals(base, code(nr = ByteArray(16) { 3 }))
    }

    @Test
    fun codeIsRoleOrdered_notSymmetric() {
        // v1 sorted the inputs; v2 fixes initiator-first so a reflected exchange derives a different code.
        assertNotEquals(code(), code(fi = fpR, fr = fpI, ei = epkR, er = epkI, ni = nR, nr = nI))
    }

    @Test
    fun lengthPrefixingPreventsFieldBoundaryShifts() {
        // Moving a byte from one field to its neighbour must not produce the same hash input.
        val a = code(ei = byteArrayOf(1, 2, 3), er = byteArrayOf(4, 5, 6, 7, 8))
        val b = code(ei = byteArrayOf(1, 2, 3, 4), er = byteArrayOf(5, 6, 7, 8))
        assertNotEquals(a, b)
    }

    @Test
    fun commitmentOpensOnlyWithTheCommittedNonceIdentityAndKey() {
        val commit = PairingV2.commitHex(fpI, epkI, nI)
        assertTrue(PairingV2.commitMatches(commit, fpI, epkI, nI))
        assertTrue(PairingV2.commitMatches(commit.uppercase(), fpI, epkI, nI), "hex case-insensitive")
        assertFalse(PairingV2.commitMatches(commit, fpI, epkI, nR), "different nonce")
        assertFalse(PairingV2.commitMatches(commit, fpR, epkI, nI), "different identity")
        assertFalse(PairingV2.commitMatches(commit, fpI, epkR, nI), "different ephemeral key")
    }

    @Test
    fun pinnedIdentityComparisonNormalisesFormattingButNothingElse() {
        val grouped = fpI.uppercase().chunked(4).joinToString(":")
        assertTrue(PairingV2.matchesPinnedIdentity(grouped, fpI))
        assertFalse(PairingV2.matchesPinnedIdentity(fpI, fpR))
        assertFalse(PairingV2.matchesPinnedIdentity(fpI, null), "no pin means no pairing")
        assertFalse(PairingV2.matchesPinnedIdentity(":::", ":::"), "blank never matches")
    }

    @Test
    fun noncesAreFreshAndFullLength() {
        val a = PairingV2.newNonce()
        val b = PairingV2.newNonce()
        assertEquals(PairingV2.NONCE_BYTES, a.size)
        assertFalse(a.contentEquals(b))
    }
}
