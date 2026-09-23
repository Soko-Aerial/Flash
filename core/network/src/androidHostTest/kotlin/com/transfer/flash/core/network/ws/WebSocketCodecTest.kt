@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketCodecTest {

    @Test
    fun `acceptKey matches RFC 6455 reference example`() {
        // RFC 6455 §1.3: key "dGhlIHNhbXBsZSBub25jZQ==" -> accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        val accept = WebSocketCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept)
    }

    @Test
    fun `base64Encode matches standard vectors`() {
        assertEquals("", WebSocketCodec.base64Encode(ByteArray(0)))
        assertEquals("Zg==", WebSocketCodec.base64Encode("f".toByteArray()))
        assertEquals("Zm8=", WebSocketCodec.base64Encode("fo".toByteArray()))
        assertEquals("Zm9v", WebSocketCodec.base64Encode("foo".toByteArray()))
        assertEquals("Zm9vYg==", WebSocketCodec.base64Encode("foob".toByteArray()))
        assertEquals("Zm9vYmE=", WebSocketCodec.base64Encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", WebSocketCodec.base64Encode("foobar".toByteArray()))
    }

    // --- Audit S5: size caps -------------------------------------------------------------------

    /** Header for an unmasked binary frame declaring [length] payload bytes via the 64-bit form. */
    private fun oversizedHeader(opcode: Int, length: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        out.write(127)
        for (shift in 56 downTo 0 step 8) out.write(((length shr shift) and 0xFF).toInt())
        return out.toByteArray()
    }

    @Test
    fun `frame declaring more than the cap is rejected from the header alone`() {
        // No payload follows: if the codec tried to allocate/read it, it would fail with EOF instead.
        val error = runCatching {
            WebSocketCodec.readMessage(
                ByteArrayInputStream(oversizedHeader(WebSocketCodec.OPCODE_BINARY, 5L * 1024 * 1024)),
            )
        }.exceptionOrNull()
        assertTrue("expected a cap rejection, got $error", error?.message?.contains("cap") == true)
    }

    @Test
    fun `pre-handshake cap rejects a frame the post-handshake cap would accept`() {
        val output = ByteArrayOutputStream()
        val payload = ByteArray(128 * 1024)
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_BINARY, payload, masked = true)
        val bytes = output.toByteArray()

        val pre = runCatching {
            WebSocketCodec.readMessage(ByteArrayInputStream(bytes), WebSocketCodec.PRE_HANDSHAKE_MAX_MESSAGE_BYTES)
        }.exceptionOrNull()
        assertTrue("128 KiB must be refused before HELLO", pre is java.io.IOException)

        val post = WebSocketCodec.readMessage(ByteArrayInputStream(bytes))
        assertEquals(payload.size, (post as WebSocketCodec.Message.Binary).data.size)
    }

    @Test
    fun `fragmented message cannot grow past the cap`() {
        // Two fragments of 40 KiB each: each fits the 64 KiB pre-handshake cap, together they do not.
        val output = ByteArrayOutputStream()
        val piece = ByteArray(40 * 1024)
        output.write(0x00 or WebSocketCodec.OPCODE_BINARY) // FIN=0, BINARY
        output.write(126); output.write((piece.size shr 8) and 0xFF); output.write(piece.size and 0xFF)
        output.write(piece)
        output.write(0x80 or WebSocketCodec.OPCODE_CONTINUATION) // FIN=1, CONTINUATION
        output.write(126); output.write((piece.size shr 8) and 0xFF); output.write(piece.size and 0xFF)
        output.write(piece)

        val error = runCatching {
            WebSocketCodec.readMessage(
                ByteArrayInputStream(output.toByteArray()),
                WebSocketCodec.PRE_HANDSHAKE_MAX_MESSAGE_BYTES,
            )
        }.exceptionOrNull()
        assertTrue("expected size guard, got $error", error?.message?.contains("size guard") == true)
    }

    @Test
    fun `control frame over 125 bytes is rejected`() {
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_PING, ByteArray(200), masked = true)
        val error = runCatching {
            WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))
        }.exceptionOrNull()
        assertTrue("expected control-frame rejection, got $error", error?.message?.contains("Control frame") == true)
    }

    @Test
    fun `the post-handshake cap is sized for a full chunk frame`() {
        // A maximum chunk (1 MiB data) plus header and FLASH_SEC overhead must still fit.
        assertTrue(WebSocketCodec.MAX_MESSAGE_BYTES >= 1024L * 1024L + 64 * 1024)
        assertTrue(WebSocketCodec.MAX_MESSAGE_BYTES <= 16L * 1024L * 1024L)
    }

    @Test
    fun `masked text frame round trips`() {
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_TEXT, "Hello WebSocket".toByteArray(), masked = true)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Text)
        assertEquals("Hello WebSocket", (message as WebSocketCodec.Message.Text).text)
    }

    @Test
    fun `unmasked binary frame with 16 bit length round trips`() {
        val payload = Random.nextBytes(1_000)
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_BINARY, payload, masked = false)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Binary)
        assertArrayEquals(payload, (message as WebSocketCodec.Message.Binary).data)
    }

    @Test
    fun `masked binary frame with 64 bit length round trips`() {
        val payload = Random.nextBytes(70_000)
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_BINARY, payload, masked = true)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Binary)
        assertArrayEquals(payload, (message as WebSocketCodec.Message.Binary).data)
    }

    @Test
    fun `masked in place write mutates the payload and round trips`() {
        val original = Random.nextBytes(70_000)
        val payload = original.copyOf()
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_BINARY, payload, masked = true, maskPayloadInPlace = true)

        // The ownership contract: the caller's array was masked, not copied.
        assertFalse(original.contentEquals(payload))

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))
        assertTrue(message is WebSocketCodec.Message.Binary)
        assertArrayEquals(original, (message as WebSocketCodec.Message.Binary).data)
    }

    @Test
    fun `masked write with default copy semantics leaves the payload untouched`() {
        val original = Random.nextBytes(1_000)
        val payload = original.copyOf()
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_BINARY, payload, masked = true)

        assertArrayEquals(original, payload)
    }

    @Test
    fun `fragmented message is reassembled`() {
        val output = ByteArrayOutputStream()
        // First frame: TEXT, FIN cleared manually by writing raw bytes.
        output.write(WebSocketCodec.OPCODE_TEXT) // FIN=0, opcode=1
        output.write(6) // unmasked, length 6
        output.write("Hello ".toByteArray())
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_CONTINUATION, "world".toByteArray(), masked = true)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Text)
        assertEquals("Hello world", (message as WebSocketCodec.Message.Text).text)
    }

    @Test
    fun `ping frame passes through with payload`() {
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_PING, "hb".toByteArray(), masked = false)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Ping)
        assertArrayEquals("hb".toByteArray(), (message as WebSocketCodec.Message.Ping).payload)
    }

    @Test
    fun `close frame parses code and reason`() {
        val payload = byteArrayOf(0x03, 0xE8.toByte()) + "bye".toByteArray() // code 1000 + reason
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_CLOSE, payload, masked = true)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Close)
        val close = message as WebSocketCodec.Message.Close
        assertEquals(1000, close.code)
        assertEquals("bye", close.reason)
    }

    @Test
    fun `unicode text survives masking round trip`() {
        val text = "Flash — ünïcödé — 文件传输"
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8), masked = true)

        val message = WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))

        assertTrue(message is WebSocketCodec.Message.Text)
        assertEquals(text, (message as WebSocketCodec.Message.Text).text)
    }

    @Test
    fun `http header block reads to terminator without consuming frame bytes`() {
        val output = ByteArrayOutputStream()
        output.write("GET /flash-ws HTTP/1.1\r\nHost: 10.0.0.2:45822\r\nSec-WebSocket-Key: abc\r\n\r\n".toByteArray())
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_TEXT, "after".toByteArray(), masked = false)

        val input = ByteArrayInputStream(output.toByteArray())
        val (startLine, headers) = WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(input))

        assertEquals("GET /flash-ws HTTP/1.1", startLine)
        assertEquals("10.0.0.2:45822", headers["host"])
        assertEquals("abc", headers["sec-websocket-key"])
        // Frame bytes right after the header must still be readable.
        val message = WebSocketCodec.readMessage(input)
        assertTrue(message is WebSocketCodec.Message.Text)
        assertEquals("after", (message as WebSocketCodec.Message.Text).text)
    }

    @Test(expected = java.io.IOException::class)
    fun `continuation without started message is rejected`() {
        val output = ByteArrayOutputStream()
        WebSocketCodec.writeFrame(output, WebSocketCodec.OPCODE_CONTINUATION, "x".toByteArray(), masked = false)
        WebSocketCodec.readMessage(ByteArrayInputStream(output.toByteArray()))
    }

    // -- Retryable idle timeouts (ERROR-025) ---------------------------------

    @Test
    fun `read timeout before the first byte is a retryable IdleTimeout`() {
        val thrown = runCatching {
            WebSocketCodec.readMessage(TimeoutingStream(ByteArray(0)))
        }.exceptionOrNull()

        assertTrue("expected IdleTimeout, got $thrown", thrown is WebSocketCodec.IdleTimeout)
        assertTrue((thrown as WebSocketCodec.IdleTimeout).cause is SocketTimeoutException)
    }

    @Test
    fun `read timeout after the first byte is not retryable`() {
        // Mid-frame the stream is desynchronized: retrying would misparse the remainder, so this
        // must stay a plain timeout that tears the connection down.
        val thrown = runCatching {
            WebSocketCodec.readMessage(TimeoutingStream(byteArrayOf(0x81.toByte())))
        }.exceptionOrNull()

        assertTrue("expected a raw socket timeout, got $thrown", thrown is SocketTimeoutException)
    }

    @Test
    fun `stream stays aligned across an idle timeout`() {
        // The behaviour the read loop depends on: a frame-boundary timeout consumed nothing, so
        // the very next read still finds an intact frame. A peer frozen by Doze is quiet, not dead.
        val frame = ByteArrayOutputStream().also {
            WebSocketCodec.writeFrame(it, WebSocketCodec.OPCODE_TEXT, "still here".toByteArray(), masked = true)
        }.toByteArray()
        val input = TimeoutThenFrameStream(frame)

        val idle = runCatching { WebSocketCodec.readMessage(input) }.exceptionOrNull()
        assertTrue("expected IdleTimeout, got $idle", idle is WebSocketCodec.IdleTimeout)

        val message = WebSocketCodec.readMessage(input)
        assertTrue(message is WebSocketCodec.Message.Text)
        assertEquals("still here", (message as WebSocketCodec.Message.Text).text)
    }

    /** Serves [prefix], then behaves like a socket whose `soTimeout` expired. */
    private class TimeoutingStream(private val prefix: ByteArray) : InputStream() {
        private var index = 0

        override fun read(): Int {
            if (index >= prefix.size) throw SocketTimeoutException("Read timed out")
            return prefix[index++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (index >= prefix.size) throw SocketTimeoutException("Read timed out")
            val count = minOf(length, prefix.size - index)
            System.arraycopy(prefix, index, buffer, offset, count)
            index += count
            return count
        }
    }

    /** Times out once with nothing consumed, then delivers [frame] — a quiet peer that spoke up. */
    private class TimeoutThenFrameStream(private val frame: ByteArray) : InputStream() {
        private var timedOut = false
        private var index = 0

        override fun read(): Int {
            if (!timedOut) {
                timedOut = true
                throw SocketTimeoutException("Read timed out")
            }
            return if (index >= frame.size) -1 else frame[index++].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!timedOut) {
                timedOut = true
                throw SocketTimeoutException("Read timed out")
            }
            if (index >= frame.size) return -1
            val count = minOf(length, frame.size - index)
            System.arraycopy(frame, index, buffer, offset, count)
            index += count
            return count
        }
    }
}
