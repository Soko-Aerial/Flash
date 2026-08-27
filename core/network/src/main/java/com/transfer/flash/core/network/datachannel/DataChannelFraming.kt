package com.transfer.flash.core.network.datachannel

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Length-prefixed binary framing for dedicated transfer data channels (real N-socket
 * multistream, ADR-017 revisit). Layout: `[4-byte LE payload length][payload bytes]`.
 *
 * Deliberately NOT WebSocket: these sockets carry ONLY ChunkFrame payloads at maximum
 * density — no masking, no opcode overhead, no text/binary multiplexing. Control/chat stays
 * on the main WebSocket session; a join handshake binds each data socket to its session.
 */
internal object DataChannelFraming {

    const val MAX_FRAME_BYTES = 512 * 1024
    const val JOIN_PREFIX = "FLASH_JOIN"
    const val JOIN_OK = "FLASH_OK"
    const val JOIN_REJECT = "FLASH_REJECT"

    /** Writes one length-prefixed frame; caller serializes access. */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        val len = payload.size
        require(len <= MAX_FRAME_BYTES) { "frame too large: $len" }
        output.write(len and 0xFF)
        output.write((len ushr 8) and 0xFF)
        output.write((len ushr 16) and 0xFF)
        output.write((len ushr 24) and 0xFF)
        output.write(payload)
        output.flush()
    }

    /** Reads one length-prefixed frame; returns null on clean EOF at a frame boundary. */
    fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        var off = 0
        while (off < 4) {
            val read = input.read(header, off, 4 - off)
            if (read < 0) {
                if (off == 0) return null
                throw EOFException("stream ended mid-frame-header")
            }
            off += read
        }
        val len = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 24)
        if (len < 0 || len > MAX_FRAME_BYTES) throw IOException("invalid frame length $len")
        val payload = ByteArray(len)
        var p = 0
        while (p < len) {
            val read = input.read(payload, p, len - p)
            if (read < 0) throw EOFException("stream ended mid-frame")
            p += read
        }
        return payload
    }

    /** Reads one CR/LF-terminated ASCII line (join handshake / ack). */
    fun readLine(input: InputStream): String {
        val buf = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("stream ended during handshake")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
            if (buf.length > 256) throw IOException("handshake line too long")
        }
        return buf.toString()
    }

    class IOException(message: String) : java.io.IOException(message)
}
