package com.transfer.flash.core.radio.wire

/**
 * Minimal message framing for a Flash-to-Flash session over a Bluetooth RFCOMM byte stream (ADR-101 scaffolding). RFCOMM is a
 * byte stream with no message boundaries, so each message is sent as `u16 length (big-endian) || payload`.
 *
 * This is deliberately NOT the security layer. The recommended session design (ADR-101) is: this framer under a
 * pairing-derived AEAD (FSEC-style) so that Bluetooth pairing is never the only authentication; Flash trust still comes from
 * its own pinned identity. Not wired into the live engine; see the report for the seam.
 */
public object StreamFramer {
    /** Largest payload; a frame header claiming more is a protocol error. */
    public const val MAX_PAYLOAD: Int = 16 * 1024

    /** `u16 length || payload`. @throws IllegalArgumentException if [payload] exceeds [MAX_PAYLOAD]. */
    public fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} exceeds $MAX_PAYLOAD" }
        val out = ByteArray(2 + payload.size)
        out[0] = (payload.size ushr 8).toByte()
        out[1] = payload.size.toByte()
        payload.copyInto(out, 2)
        return out
    }
}

/**
 * Streaming decoder for [StreamFramer] frames: feed it whatever the link delivered (any fragmentation), get whole payloads.
 * After a protocol error ([failed]) the stream cannot be resynchronised (there is no marker), so the owner must close the link.
 *
 * OPT-O26-04: the partial frame lives in a [ByteQueue], so a byte-at-a-time link costs O(n) copying instead of
 * re-copying the partial buffer on every byte.
 */
public class StreamFrameDecoder {
    private val buf = ByteQueue()

    /** True after a header announced a payload above [StreamFramer.MAX_PAYLOAD]; further input is ignored. */
    public var failed: Boolean = false
        private set

    /** Bytes buffered waiting for the rest of a frame. */
    public val buffered: Int get() = buf.size

    /** Appends [data] and returns every payload completed by it, in order. */
    public fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        // Same overflow as the KISS decoder: `offset + length` wraps for a huge length, so the guard
        // passed and the failure surfaced from copyOfRange instead of here.
        require(offset >= 0 && length >= 0 && offset <= data.size - length) { "bad slice" }
        if (failed) return emptyList()
        buf.append(data, offset, length)
        val out = ArrayList<ByteArray>(2)
        while (buf.size >= 2) {
            val n = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            if (n > StreamFramer.MAX_PAYLOAD) {
                failed = true
                buf.clear()
                return out
            }
            if (buf.size - 2 < n) break
            out += buf.copyOut(2, 2 + n)
            buf.discard(2 + n)
        }
        return out
    }
}
