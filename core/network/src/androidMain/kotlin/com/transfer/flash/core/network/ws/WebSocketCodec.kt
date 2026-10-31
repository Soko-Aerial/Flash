package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.perf.ByteArrayPool
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal RFC 6455 WebSocket codec for the experimental LAN transfer track.
 *
 * Intentional scope limits (this is not a general-purpose WebSocket stack):
 * - Client-to-server frames are masked, server-to-client frames are not (RFC requirement).
 * - Outgoing messages are never fragmented; incoming fragmented messages are reassembled.
 * - No extensions (no permessage-deflate) and no TLS — `ws://` on trusted LAN only.
 *
 * Pure JVM (no Android imports) so it is covered by local unit tests.
 */
@FlashInternalApi
public object WebSocketCodec {

    public const val OPCODE_CONTINUATION: Int = 0x0
    public const val OPCODE_TEXT: Int = 0x1
    public const val OPCODE_BINARY: Int = 0x2
    public const val OPCODE_CLOSE: Int = 0x8
    public const val OPCODE_PING: Int = 0x9
    public const val OPCODE_PONG: Int = 0xA

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_BYTES = 16 * 1024
    /**
     * Cap on one reassembled message once the peer's HELLO has been accepted (audit S5). The largest
     * legitimate message is a chunk frame: `ChunkFrame.MAX_CHUNK_DATA_BYTES` (1 MiB) plus header and
     * `FLASH_SEC` overhead. 4 MiB leaves room for long chat text. It used to be 512 MB, which let any
     * LAN device make a 2 GB phone allocate past its heap before pairing.
     */
    public const val MAX_MESSAGE_BYTES: Long = 4L * 1024L * 1024L

    /** Cap before the peer's HELLO: a HELLO is well under 1 KiB, so nothing larger is legitimate. */
    public const val PRE_HANDSHAKE_MAX_MESSAGE_BYTES: Long = 64L * 1024L

    /** RFC 6455 §5.5: control frames carry at most 125 payload bytes. */
    private const val MAX_CONTROL_PAYLOAD_BYTES = 125L

    private val random = SecureRandom()

    /**
     * A socket read timeout that expired with ZERO bytes of a frame consumed, i.e. the stream is
     * still sitting exactly on a frame boundary and — per the `SocketTimeoutException` contract —
     * the socket is still valid. Callers may simply read again.
     *
     * This is deliberately a distinct type from a plain [IOException]: "no frame arrived for 30 s"
     * and "the stream broke" used to be indistinguishable in the read loop, so a peer that was
     * merely frozen by Doze got its healthy session torn down. A timeout raised anywhere *after*
     * the first byte is NOT this exception — the stream is desynchronized there and retrying would
     * misparse the remainder.
     */
    public class IdleTimeout internal constructor(cause: SocketTimeoutException) :
        IOException("No WebSocket frame within the read timeout", cause)

    public sealed interface Message {
        public data class Text(val text: String) : Message
        public data class Binary(val data: ByteArray) : Message
        public data class Close(val code: Int, val reason: String) : Message
        public data class Ping(val payload: ByteArray) : Message
        public data class Pong(val payload: ByteArray) : Message
    }

    private data class FrameHeader(
        val fin: Boolean,
        val opcode: Int,
        val masked: Boolean,
        val length: Long,
    )

    public fun newClientKey(): String {
        val key = ByteArray(16)
        random.nextBytes(key)
        return base64Encode(key)
    }

    /** RFC 6455 §4.2.2 accept value: base64( SHA-1( key + GUID ) ). */
    public fun acceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey.trim() + WS_GUID).toByteArray(Charsets.US_ASCII))
        return base64Encode(digest)
    }

    /**
     * Writes a single unfragmented frame. Client frames must pass [masked] = true.
     *
     * When [maskPayloadInPlace] is true the caller transfers ownership of [payload]: it is masked
     * in place and must not be retained or reused afterwards. That skips one full-size payload
     * copy per client frame — the transfer hot loop's frames are single-use `ChunkFrame.serialize`
     * output, so the copy was pure churn (see EXP-001's LOS finding).
     */
    public fun writeFrame(
        output: OutputStream,
        opcode: Int,
        payload: ByteArray,
        masked: Boolean,
        maskPayloadInPlace: Boolean = false,
    ) {
        val maskKey = if (masked) ByteArray(4).also(random::nextBytes) else null
        val headerBuf = ByteArray(14)
        var headerLen = 0
        headerBuf[headerLen++] = (0x80 or opcode).toByte()
        val length = payload.size
        val maskBit = if (masked) 0x80 else 0x00
        when {
            length < 126 -> {
                headerBuf[headerLen++] = (maskBit or length).toByte()
            }
            length <= 0xFFFF -> {
                headerBuf[headerLen++] = (maskBit or 126).toByte()
                headerBuf[headerLen++] = ((length ushr 8) and 0xFF).toByte()
                headerBuf[headerLen++] = (length and 0xFF).toByte()
            }
            else -> {
                headerBuf[headerLen++] = (maskBit or 127).toByte()
                for (shift in 56 downTo 0 step 8) {
                    headerBuf[headerLen++] = (((length.toLong() ushr shift) and 0xFFL).toInt()).toByte()
                }
            }
        }
        if (maskKey != null) {
            System.arraycopy(maskKey, 0, headerBuf, headerLen, 4)
            headerLen += 4
        }
        output.write(headerBuf, 0, headerLen)
        if (maskKey != null) {
            if (maskPayloadInPlace) {
                for (index in payload.indices) {
                    payload[index] = (payload[index].toInt() xor maskKey[index and 3].toInt()).toByte()
                }
                output.write(payload)
            } else {
                val chunkSize = minOf(payload.size, 4096)
                val chunk = ByteArray(chunkSize)
                var offset = 0
                while (offset < payload.size) {
                    val count = minOf(chunk.size, payload.size - offset)
                    for (i in 0 until count) {
                        val g = offset + i
                        chunk[i] = (payload[g].toInt() xor maskKey[g and 3].toInt()).toByte()
                    }
                    output.write(chunk, 0, count)
                    offset += count
                }
            }
        } else {
            output.write(payload)
        }
        output.flush()
    }

    /**
     * Reads one complete WebSocket message, reassembling continuation frames.
     *
     * Stateless convenience over a fresh [MessageReader]: a control frame (ping / pong / close) arriving between the
     * fragments of a message drops the partial message. A long-lived connection must use ONE [MessageReader] for its
     * whole lifetime instead (R-11).
     *
     * @throws IdleTimeout when the read timeout expires before the first byte of the message —
     *   retryable on the same stream (see [IdleTimeout]).
     */
    public fun readMessage(input: InputStream, maxMessageBytes: Long = MAX_MESSAGE_BYTES): Message =
        MessageReader().read(input, maxMessageBytes)

    /**
     * Reassembles messages from one connection's frames and KEEPS a partly received fragmented message across
     * interleaved control frames.
     *
     * RFC 6455 section 5.4: control frames "MAY be injected in the middle of a fragmented message". The old stateless
     * [readMessage] kept the partial message in locals and returned the ping at once, so the next call began with an
     * empty buffer and the following continuation frame failed with "Continuation frame without a started message"
     * (R-11, sweep 2026-10-09). Flash's own writer never fragments, but any peer that does and also pings (a browser
     * or proxy-fronted client of a future version) would have closed the session. Not thread-safe: one reader loop.
     */
    public class MessageReader {
        private val messageBuffer = ByteArrayOutputStream()
        private var messageOpcode = -1

        /** True while a fragmented message is partly received. */
        public val inFragmentedMessage: Boolean get() = messageOpcode != -1

        /**
         * Reads frames until one message (data or control) is complete.
         *
         * @throws IdleTimeout when the read timeout expires at a frame boundary with no message in progress. Inside a
         *   fragmented message a stall is a plain timeout IOException (the stream is mid-message).
         */
        public fun read(input: InputStream, maxMessageBytes: Long = MAX_MESSAGE_BYTES): Message {
            var atMessageStart = !inFragmentedMessage
            while (true) {
                val header = readFrameHeader(input, retryableIdle = atMessageStart, maxMessageBytes = maxMessageBytes)
                atMessageStart = false
                val inbound = readFramePayload(input, header)
                when (header.opcode) {
                    OPCODE_CLOSE -> {
                        val code = if (inbound.length >= 2) {
                            ((inbound.buffer[0].toInt() and 0xFF) shl 8) or (inbound.buffer[1].toInt() and 0xFF)
                        } else {
                            1000
                        }
                        val reason = if (inbound.length > 2) {
                            String(inbound.buffer, 2, inbound.length - 2, Charsets.UTF_8)
                        } else {
                            ""
                        }
                        if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                        return Message.Close(code, reason)
                    }

                    OPCODE_PING -> {
                        val payload = if (inbound.isPooled) {
                            val copy = inbound.buffer.copyOf(inbound.length)
                            ByteArrayPool.release(inbound.buffer)
                            copy
                        } else {
                            inbound.buffer
                        }
                        return Message.Ping(payload)
                    }
                    OPCODE_PONG -> {
                        val payload = if (inbound.isPooled) {
                            val copy = inbound.buffer.copyOf(inbound.length)
                            ByteArrayPool.release(inbound.buffer)
                            copy
                        } else {
                            inbound.buffer
                        }
                        return Message.Pong(payload)
                    }

                    OPCODE_TEXT, OPCODE_BINARY, OPCODE_CONTINUATION -> {
                        if (header.opcode != OPCODE_CONTINUATION) {
                            if (messageOpcode != -1) {
                                reset()
                                if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                                throw IOException("New WebSocket message started before previous one finished")
                            }
                            messageOpcode = header.opcode
                        } else if (messageOpcode == -1) {
                            if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                            throw IOException("Continuation frame without a started message")
                        }
                        // Outbound messages are never fragmented, so nearly every message completes in
                        // its first frame: return that payload directly instead of copying it through
                        // the reassembly buffer (one full-size copy per chunk on the receive side).
                        if (header.fin && messageBuffer.size() == 0) {
                            val opcode = messageOpcode
                            reset()
                            return if (opcode == OPCODE_TEXT) {
                                val text = String(inbound.buffer, 0, inbound.length, Charsets.UTF_8)
                                if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                                Message.Text(text)
                            } else {
                                val payload = if (inbound.isPooled) {
                                    val copy = inbound.buffer.copyOf(inbound.length)
                                    ByteArrayPool.release(inbound.buffer)
                                    copy
                                } else {
                                    inbound.buffer
                                }
                                Message.Binary(payload)
                            }
                        }
                        // Checked BEFORE the write, so a fragmented message cannot grow past the cap.
                        if (messageBuffer.size().toLong() + inbound.length > maxMessageBytes) {
                            reset()
                            if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                            throw IOException("WebSocket message exceeds size guard ($maxMessageBytes bytes)")
                        }
                        messageBuffer.write(inbound.buffer, 0, inbound.length)
                        if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                        if (header.fin) {
                            val data = messageBuffer.toByteArray()
                            val opcode = messageOpcode
                            reset()
                            return if (opcode == OPCODE_TEXT) {
                                Message.Text(String(data, Charsets.UTF_8))
                            } else {
                                Message.Binary(data)
                            }
                        }
                    }

                    else -> {
                        // Give the pooled buffer back on the way out. An unknown opcode is rejected
                        // only here, AFTER readFramePayload has already taken one from the pool - the
                        // header check cannot reject it, because 0x3..0x7 are valid 4-bit values.
                        // Skipping the release is not a pool drain ([ByteArrayPool.acquire] allocates
                        // when the pool is empty) and not a disclosure, but it does drop a 16/32 KB
                        // buffer as garbage instead of wiping and reusing it, on a frame a peer
                        // chooses to send (integrator review, 2026-10-31).
                        if (inbound.isPooled) ByteArrayPool.release(inbound.buffer)
                        throw IOException("Unsupported WebSocket opcode ${header.opcode}")
                    }
                }
            }
        }

        private fun reset() {
            messageBuffer.reset()
            messageOpcode = -1
        }
    }

    /**
     * Reads an HTTP header block (request or response) terminated by CRLFCRLF.
     * Reads byte-by-byte so no bytes past the header are consumed before frame parsing.
     */
    public fun readHttpHeaderBlock(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        var matched = 0
        while (matched < terminator.size) {
            val next = input.read()
            if (next < 0) throw EOFException("Stream closed during WebSocket handshake")
            bytes.write(next)
            if (bytes.size() > MAX_HEADER_BYTES) throw IOException("WebSocket handshake header too large")
            matched = when {
                next == terminator[matched].toInt() -> matched + 1
                next == terminator[0].toInt() -> 1
                else -> 0
            }
        }
        return String(bytes.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Splits a header block into the start line and a lowercase-keyed header map. */
    public fun parseHeaders(block: String): Pair<String, Map<String, String>> {
        val lines = block.split("\r\n").filter { it.isNotEmpty() }
        val startLine = lines.firstOrNull().orEmpty()
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) {
                null
            } else {
                line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
        }.toMap()
        return startLine to headers
    }

    /** Standard-alphabet Base64 encoder (encode-only; keeps this class free of Android/java.util.Base64). */
    public fun base64Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val result = StringBuilder((data.size + 2) / 3 * 4)
        var index = 0
        while (index < data.size) {
            val b0 = data[index].toInt() and 0xFF
            val b1 = if (index + 1 < data.size) data[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < data.size) data[index + 2].toInt() and 0xFF else 0
            result.append(alphabet[b0 ushr 2])
            result.append(alphabet[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            result.append(if (index + 1 < data.size) alphabet[((b1 and 0x0F) shl 2) or (b2 ushr 6)] else '=')
            result.append(if (index + 2 < data.size) alphabet[b2 and 0x3F] else '=')
            index += 3
        }
        return result.toString()
    }

    private fun readFrameHeader(
        input: InputStream,
        retryableIdle: Boolean,
        maxMessageBytes: Long = MAX_MESSAGE_BYTES,
    ): FrameHeader {
        val first = if (retryableIdle) {
            try {
                readByte(input)
            } catch (timeout: SocketTimeoutException) {
                // Nothing consumed yet: the stream is intact and the caller can read again.
                throw IdleTimeout(timeout)
            }
        } else {
            readByte(input)
        }
        val second = readByte(input)
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        val masked = second and 0x80 != 0
        val length = when (val length7 = second and 0x7F) {
            126 -> {
                val b = readFully(input, 2)
                (((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)).toLong()
            }
            127 -> {
                val b = readFully(input, 8)
                var value = 0L
                for (i in 0 until 8) {
                    value = (value shl 8) or (b[i].toLong() and 0xFFL)
                }
                value
            }
            else -> length7.toLong()
        }
        // Rejected from the header alone, before any payload buffer is allocated.
        if (length < 0 || length > maxMessageBytes) {
            throw IOException("Invalid WebSocket frame length $length (cap $maxMessageBytes)")
        }
        if (opcode >= OPCODE_CLOSE && length > MAX_CONTROL_PAYLOAD_BYTES) {
            throw IOException("Control frame payload $length exceeds $MAX_CONTROL_PAYLOAD_BYTES bytes")
        }
        return FrameHeader(fin = fin, opcode = opcode, masked = masked, length = length)
    }

    private class InboundPayload(val buffer: ByteArray, val length: Int, val isPooled: Boolean)

    private fun readFramePayload(input: InputStream, header: FrameHeader): InboundPayload {
        val length = header.length.toInt()
        val maskKey = if (header.masked) readFully(input, 4) else null
        val (buffer, isPooled) = if (length <= ByteArrayPool.SIZE_32KB) {
            val pooled = ByteArrayPool.acquire(length)
            try {
                readFullyInto(input, pooled, 0, length)
            } catch (e: Throwable) {
                ByteArrayPool.release(pooled)
                throw e
            }
            pooled to true
        } else {
            val unpooled = ByteArray(length)
            readFullyInto(input, unpooled, 0, length)
            unpooled to false
        }
        if (maskKey != null) {
            for (index in 0 until length) {
                buffer[index] = (buffer[index].toInt() xor maskKey[index and 3].toInt()).toByte()
            }
        }
        return InboundPayload(buffer, length, isPooled)
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        readFullyInto(input, buffer, 0, size)
        return buffer
    }

    private fun readFullyInto(input: InputStream, buffer: ByteArray, offset: Int, size: Int) {
        var readTotal = 0
        while (readTotal < size) {
            val read = input.read(buffer, offset + readTotal, size - readTotal)
            if (read < 0) throw EOFException("Stream closed mid-frame")
            readTotal += read
        }
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("Stream closed mid-frame")
        return value
    }
}
