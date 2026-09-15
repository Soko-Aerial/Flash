package com.transfer.flash.pairing

import com.transfer.flash.core.security.pairing.FlashPairingFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the [PairingFraming] text wire codec: every frame (plus the extra hello)
 * must round-trip through encode → decode, including the Base64-carried ephemeral public key
 * and values containing framing-sensitive characters. Pure string work, no Android runtime.
 */
class PairingFramingTest {

    private fun decodeFrame(text: String): FlashPairingFrame {
        val inbound = PairingFraming.decode(text)
        assertTrue("expected a Frame, got $inbound", inbound is PairingFraming.Inbound.Frame)
        return (inbound as PairingFraming.Inbound.Frame).frame
    }

    @Test
    fun hello_roundTrips() {
        val fp = "AB:CD:EF:01:23:45"
        val inbound = PairingFraming.decode(PairingFraming.encodeHello(fp))
        assertEquals(PairingFraming.Inbound.Hello(fp), inbound)
    }

    @Test
    fun hello_doesNotAskByDefault() {
        // The session-up announcement is a plain hello. If it asked, every session edge would draw an
        // answer from the peer, and every answer would draw... nothing — but the *announcement* would
        // still have provoked a reply it has no use for. `beginPair` is the only asker.
        val inbound = PairingFraming.decode(PairingFraming.encodeHello("AB"))
        assertEquals(PairingFraming.Inbound.Hello("AB", request = false), inbound)
    }

    @Test
    fun hello_withRequest_roundTripsAndAsks() {
        val fp = "AB:CD:EF:01:23:45"
        val inbound = PairingFraming.decode(PairingFraming.encodeHello(fp, request = true))
        assertEquals(PairingFraming.Inbound.Hello(fp, request = true), inbound)
    }

    @Test
    fun hello_flagIsOnlyHonouredAtExactlyOne() {
        // Forward-compat and hostile-input both: the flag is a request iff it carries `1`. A peer
        // that sends `hrq=0`, or garbage, must NOT draw an answer from us — otherwise a malformed or
        // future-peer hello could put two devices into a reply exchange neither of them expects.
        val text = PairingFraming.encodeHello("AB").replace("fp=AB", "fp=AB hrq=banana")
        assertEquals(PairingFraming.Inbound.Hello("AB", request = false), PairingFraming.decode(text))
    }

    @Test
    fun hello_requestDoesNotEchoTheFlagIntoTheAnswer() {
        // The termination proof, asserted rather than argued: a request carries `hrq`, and the answer
        // to it (`encodeHello` with the default) does not. If this ever inverts, two peers would
        // answer each other forever.
        val request = PairingFraming.encodeHello("AB", request = true)
        val answer = PairingFraming.encodeHello("CD")
        assertTrue("the request must carry the flag: $request", request.contains("hrq=1"))
        assertTrue("the answer must not: $answer", !answer.contains("hrq"))
    }

    @Test
    fun pairRequest_roundTripsIncludingBase64Key() {
        // Length 4 → Base64 emits '=' padding, which FlashTextFraming escapes as %3D.
        val key = byteArrayOf(0, 127, -1, 64)
        val frame = FlashPairingFrame.PairRequest(
            requestId = "req-1",
            senderDeviceId = "device-42",
            senderName = "Kai's Pixel = fast",
            senderModel = "Pixel 9 Pro",
            senderFingerprintHex = "AB:CD:EF",
            senderEphemeralPublicKey = key,
            createdAt = 1_724_500_000_000L,
        )
        assertEquals(frame, decodeFrame(PairingFraming.encode(frame)))
    }

    @Test
    fun pairAccept_roundTrips() {
        val frame = FlashPairingFrame.PairAccept(requestId = "req-2")
        assertEquals(frame, decodeFrame(PairingFraming.encode(frame)))
    }

    @Test
    fun pairConfirm_roundTrips() {
        val frame = FlashPairingFrame.PairConfirm(requestId = "req-3", codeHashHex = "deadbeef")
        assertEquals(frame, decodeFrame(PairingFraming.encode(frame)))
    }

    @Test
    fun paired_roundTripsIncludingBase64Key() {
        val key = ByteArray(65) { it.toByte() } // realistic uncompressed EC P-256 point length.
        val frame = FlashPairingFrame.Paired(
            requestId = "req-4",
            peerFingerprintHex = "11:22:33:44",
            peerEphemeralPublicKey = key,
        )
        assertEquals(frame, decodeFrame(PairingFraming.encode(frame)))
    }

    @Test
    fun decode_returnsNullForWrongPrefix() {
        // Fields are space-delimited (FlashTextFraming); a non-FLASH_PAIR prefix must not decode.
        assertNull(PairingFraming.decode("FLASH_MSG t=hello fp=AB"))
    }

    @Test
    fun decode_returnsNullForUnknownType() {
        // Correct prefix but an unrecognized discriminator.
        val text = PairingFraming.encodeHello("AB").replace("hello", "bogus")
        assertNull(PairingFraming.decode(text))
    }

    @Test
    fun decode_returnsNullWhenRequiredFieldMissing() {
        // A request frame stripped of its ephemeral-key field must not decode.
        val full = PairingFraming.encode(
            FlashPairingFrame.PairRequest(
                requestId = "r",
                senderDeviceId = "d",
                senderName = "n",
                senderModel = "m",
                senderFingerprintHex = "AB",
                senderEphemeralPublicKey = byteArrayOf(1, 2, 3),
                createdAt = 1L,
            ),
        )
        // FlashTextFraming joins fields with a space; drop the epk token entirely.
        val withoutEpk = full.split(" ").filterNot { it.startsWith("epk=") }.joinToString(" ")
        assertNull(PairingFraming.decode(withoutEpk))
    }
}
