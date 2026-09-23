@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ONE `FLASH_PAIR` codec (ADR-042) replaces the app's and the desktop's private copies, so it must
 * round-trip every frame, still read a 2.0.0-beta (v1) line, and reject malformed v2 material.
 */
class PairingWireCodecTest {

    private fun roundTrip(frame: FlashPairingFrame): FlashPairingFrame {
        val decoded = PairingWireCodec.decode(PairingWireCodec.encode(frame))
        assertTrue(decoded is PairingWireCodec.Inbound.Frame, "expected a frame, got $decoded")
        return decoded.frame
    }

    private val nonce = ByteArray(PairingV2.NONCE_BYTES) { (it * 13).toByte() }
    // Bytes whose Base64 contains '+', '/' and '=' padding, which the text framing must carry intact.
    private val epk = byteArrayOf(-5, -1, -65, 62, 63, 0x3E, 0x7F)

    @Test
    fun everyV2FrameRoundTrips() {
        val frames = listOf(
            FlashPairingFrame.PairRequest("rid", "dev-a", "Alice's phone", "Pixel 9", "aa:bb", epk, 123L, 2, "c0ffee"),
            FlashPairingFrame.PairNonce("rid", "cc:dd", epk, nonce),
            FlashPairingFrame.PairReveal("rid", nonce),
            FlashPairingFrame.PairAccept("rid"),
            FlashPairingFrame.PairConfirm("rid", "abcdef"),
            FlashPairingFrame.Paired("rid", "cc:dd", epk),
        )
        frames.forEach { assertEquals(it, roundTrip(it)) }
    }

    @Test
    fun helloAdvertisesTheProtocolVersion() {
        val hello = PairingWireCodec.decode(PairingWireCodec.encodeHello("aa11", request = true))
        assertEquals(PairingWireCodec.Inbound.Hello("aa11", request = true, protocolVersion = 2), hello)
    }

    @Test
    fun aBetaHelloAndRequestDecodeAsVersionOne() {
        // Exactly what 2.0.0-beta put on the wire: no `v`, no `cm`.
        assertEquals(
            PairingWireCodec.Inbound.Hello("aa11", request = false, protocolVersion = 1),
            PairingWireCodec.decode("FLASH_PAIR t=hello fp=aa11"),
        )
        val v1 = PairingWireCodec.decode(
            "FLASH_PAIR t=req rid=r1 did=d name=n model=m fp=aa epk=AQI%3D ts=5",
        ) as PairingWireCodec.Inbound.Frame
        val req = v1.frame as FlashPairingFrame.PairRequest
        assertEquals(1, req.protocolVersion)
        assertNull(req.commitHex, "a v1 request carries no commitment, which the responder refuses")
    }

    @Test
    fun malformedNonceLengthIsRejected() {
        val shortNonce = PairingWireCodec.encode(FlashPairingFrame.PairReveal("rid", ByteArray(4)))
        assertNull(PairingWireCodec.decode(shortNonce))
    }

    @Test
    fun foreignPrefixOrUnknownTypeIsIgnored() {
        assertNull(PairingWireCodec.decode("FLASH_MSG t=hello fp=aa"))
        assertNull(PairingWireCodec.decode("FLASH_PAIR t=teleport rid=1"))
    }
}
