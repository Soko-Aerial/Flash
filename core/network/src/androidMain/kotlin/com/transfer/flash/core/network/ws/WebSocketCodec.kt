package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
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
    private const val MAX_MESSAGE_BYTES = 512L * 1024L * 1024L

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

    /** Writes a single unfragmented frame. Client frames must pass [masked] = true. */
    public fun writeFrame(output: OutputStream, opcode: Int, payload: ByteArray, masked: Boolean) {
        val maskKey = if (masked) ByteArray(4).also(random::nextBytes) else null
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or opcode)
        val length = payload.size
        val maskBit = if (masked) 0x80 else 0x00
        when {
            length < 126 -> header.write(maskBit or length)
            length <= 0xFFFF -> {
                header.write(maskBit or 126)
                header.write((length ushr 8) and 0xFF)
                header.write(length and 0xFF)
            }
            else -> {
                header.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) {
                    header.write(((length.toLong() ushr shift) and 0xFFL).toInt())
                }
            }
        }
        if (maskKey != null) {
            header.write(maskKey)
        }
        output.write(header.toByteArray())
        if (maskKey != null) {
            val maskedPayload = ByteArray(payload.size)
            for (index in payload.indices) {
                maskedPayload[index] = (payload[index].toInt() xor maskKey[index and 3].toInt()).toByte()
            }
            output.write(maskedPayload)
        } else {
            output.write(payload)
        }
        output.flush()
    }

    /**
     * Reads one complete WebSocket message, reassembling continuation frames.
     *
     * @throws IdleTimeout when the read timeout expires before the first byte of the message —
     *   retryable on the same stream (see [IdleTimeout]).
     */
    public fun readMessage(input: InputStream): Message {
        val messageBuffer = ByteArrayOutputStream()
        var messageOpcode = -1
        var atMessageStart = true
        while (true) {
            val header = readFrameHeader(input, retryableIdle = atMessageStart)
            atMessageStart = false
            val payload = readFramePayload(input, header)
            when (header.opcode) {
                OPCODE_CLOSE -> {
                    val code = if (payload.size >= 2) {
                        ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    } else {
                        1000
                    }
                    val reason = if (payload.size > 2) {
                        String(payload, 2, payload.size - 2, Charsets.UTF_8)
                    } else {
                        ""
                    }
                    return Message.Close(code, reason)
                }

                OPCODE_PING -> return Message.Ping(payload)
                OPCODE_PONG -> return Message.Pong(payload)

                OPCODE_TEXT, OPCODE_BINARY, OPCODE_CONTINUATION -> {
                    if (header.opcode != OPCODE_CONTINUATION) {
                        if (messageOpcode != -1) {
                            throw IOException("New WebSocket message started before previous one finished")
                        }
                        messageOpcode = header.opcode
                    } else if (messageOpcode == -1) {
                        throw IOException("Continuation frame without a started message")
                    }
                    messageBuffer.write(payload)
                    if (messageBuffer.size() > MAX_MESSAGE_BYTES) {
                        throw IOException("WebSocket message exceeds size guard")
                    }
                    if (header.fin) {
                        val data = messageBuffer.toByteArray()
                        return if (messageOpcode == OPCODE_TEXT) {
                            Message.Text(String(data, Charsets.UTF_8))
                        } else {
                            Message.Binary(data)
                        }
                    }
                }

                else -> throw IOException("Unsupported WebSocket opcode ${header.opcode}")
            }
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

    private fun readFrameHeader(input: InputStream, retryableIdle: Boolean): FrameHeader {
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
            126 -> (readByte(input).toLong() shl 8) or readByte(input).toLong()
            127 -> {
                var value = 0L
                repeat(8) { value = (value shl 8) or readByte(input).toLong() }
                value
            }
            else -> length7.toLong()
        }
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw IOException("Invalid WebSocket frame length $length")
        }
        return FrameHeader(fin = fin, opcode = opcode, masked = masked, length = length)
    }

    private fun readFramePayload(input: InputStream, header: FrameHeader): ByteArray {
        val maskKey = if (header.masked) readFully(input, 4) else null
        val payload = readFully(input, header.length.toInt())
        if (maskKey != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor maskKey[index and 3].toInt()).toByte()
            }
        }
        return payload
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw EOFException("Stream closed mid-frame")
            offset += read
        }
        return buffer
    }

    private fun readByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw EOFException("Stream closed mid-frame")
        return value
    }
}
