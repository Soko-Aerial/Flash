@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F37 of the 2026-10-22 review: the length guard in [E2eFrameCodec.decrypt] was `frame.size > 28`, so the
 * one legal frame size that carries an **empty** plaintext — exactly nonce (12) + GCM tag (16) — was
 * rejected as "too short" even though GCM authenticates it like any other. Nothing in the protocol
 * promises a non-empty body, and the check is the only place that decides what a plausible frame is, so
 * the boundary belongs in a test.
 *
 * The companion [E2eFrameCodecTest] lives in `androidHostTest` and pins the same codec through JCA; this
 * one runs on every target, including the JVM the desktop host uses.
 */
class E2eFrameCodecEmptyPlaintextTest {

    private fun freshSessionKey(): ByteArray = secureRandomBytes(FlashCrypto.SESSION_KEY_SIZE_BYTES)

    /** nonce + tag: the smallest frame that can be authentic, and the exact size of an empty plaintext. */
    private val smallestFrameBytes = E2eFrameCodec.NONCE_BYTES + E2eFrameCodec.TAG_BITS / Byte.SIZE_BITS

    @Test
    fun `an empty plaintext round-trips in exactly nonce plus tag bytes`() {
        val key = freshSessionKey()

        val frame = E2eFrameCodec.encrypt("", key)

        assertEquals(smallestFrameBytes, frame.size, "an empty body is nonce + tag and nothing else")
        assertEquals("", E2eFrameCodec.decrypt(frame, key), "and it must decrypt back to the empty string")
    }

    @Test
    fun `an empty plaintext survives the FLASH_SEC wire round trip`() {
        val key = freshSessionKey()

        val wire = E2eFrameCodec.encryptToWireFrame("", key)

        assertTrue(E2eFrameCodec.isSecuredFrame(wire))
        assertEquals("", E2eFrameCodec.decryptWireFrame(wire, key))
    }

    @Test
    fun `a frame below nonce plus tag is rejected as too short`() {
        val key = freshSessionKey()

        val failure = assertFailsWith<IllegalArgumentException> {
            E2eFrameCodec.decrypt(ByteArray(smallestFrameBytes - 1), key)
        }
        assertTrue(
            failure.message?.contains("too short") == true,
            "the guard must still name the problem, was: ${failure.message}",
        )
    }

    @Test
    fun `a frame of exactly nonce plus tag reaches authentication rather than the length guard`() {
        val key = freshSessionKey()

        // Zero bytes of the right length are not a valid frame, but they must be rejected by GCM
        // authentication — not by the length guard. That is the whole difference between `>` and `>=`.
        val failure = assertFails { E2eFrameCodec.decrypt(ByteArray(smallestFrameBytes), key) }

        assertTrue(
            failure !is IllegalArgumentException,
            "length ${smallestFrameBytes} is legal; only authentication may fail, was: $failure",
        )
    }
}
