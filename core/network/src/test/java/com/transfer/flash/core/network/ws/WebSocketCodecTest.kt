@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
