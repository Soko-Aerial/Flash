@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R-11 (HARD-23): RFC 6455 section 5.4 lets a control frame arrive between the fragments of a data message. A
 * connection's [WebSocketCodec.MessageReader] must keep the partial message across it. (Twin: `androidHostTest`
 * `WebSocketFragmentControlTest`; keep the two identical.)
 */
class JvmWebSocketFragmentControlTest {

    private fun fragment(out: ByteArrayOutputStream, firstByte: Int, payload: ByteArray) {
        out.write(firstByte)
        out.write(payload.size) // unmasked, length < 126
        out.write(payload)
    }

    private fun stream(build: (ByteArrayOutputStream) -> Unit): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        build(out)
        return ByteArrayInputStream(out.toByteArray())
    }

    @Test
    fun `a ping between two fragments does not lose the message`() {
        val input = stream { out ->
            fragment(out, WebSocketCodec.OPCODE_TEXT, "Hello ".toByteArray()) // FIN=0
            fragment(out, 0x80 or WebSocketCodec.OPCODE_PING, "hb".toByteArray())
            fragment(out, 0x80 or WebSocketCodec.OPCODE_CONTINUATION, "world".toByteArray())
        }
        val reader = WebSocketCodec.MessageReader()

        val ping = reader.read(input)
        assertTrue(ping is WebSocketCodec.Message.Ping, "the ping is delivered first: $ping")
        assertContentEquals("hb".toByteArray(), (ping as WebSocketCodec.Message.Ping).payload)
        assertTrue(reader.inFragmentedMessage, "the partial message is still pending")

        val text = reader.read(input)
        assertTrue(text is WebSocketCodec.Message.Text, "the message completes after the ping: $text")
        assertEquals("Hello world", (text as WebSocketCodec.Message.Text).text)
        assertFalse(reader.inFragmentedMessage)
    }

    @Test
    fun `pong and several control frames between fragments are all tolerated`() {
        val input = stream { out ->
            fragment(out, WebSocketCodec.OPCODE_BINARY, byteArrayOf(1, 2, 3))
            fragment(out, 0x80 or WebSocketCodec.OPCODE_PONG, ByteArray(0))
            fragment(out, WebSocketCodec.OPCODE_CONTINUATION, byteArrayOf(4, 5))
            fragment(out, 0x80 or WebSocketCodec.OPCODE_PING, ByteArray(0))
            fragment(out, 0x80 or WebSocketCodec.OPCODE_CONTINUATION, byteArrayOf(6))
        }
        val reader = WebSocketCodec.MessageReader()

        assertTrue(reader.read(input) is WebSocketCodec.Message.Pong)
        assertTrue(reader.read(input) is WebSocketCodec.Message.Ping)
        val binary = reader.read(input)
        assertTrue(binary is WebSocketCodec.Message.Binary)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), (binary as WebSocketCodec.Message.Binary).data)
    }

    @Test
    fun `the reader is reusable for the next message after a fragmented one`() {
        val input = stream { out ->
            fragment(out, WebSocketCodec.OPCODE_TEXT, "a".toByteArray())
            fragment(out, 0x80 or WebSocketCodec.OPCODE_CONTINUATION, "b".toByteArray())
            fragment(out, 0x80 or WebSocketCodec.OPCODE_TEXT, "c".toByteArray())
        }
        val reader = WebSocketCodec.MessageReader()

        assertEquals("ab", (reader.read(input) as WebSocketCodec.Message.Text).text)
        assertEquals("c", (reader.read(input) as WebSocketCodec.Message.Text).text)
    }

    @Test
    fun `a new data message while one is unfinished is still a protocol error`() {
        val input = stream { out ->
            fragment(out, WebSocketCodec.OPCODE_TEXT, "a".toByteArray()) // FIN=0
            fragment(out, 0x80 or WebSocketCodec.OPCODE_TEXT, "b".toByteArray())
        }
        val error = runCatching { WebSocketCodec.MessageReader().read(input) }.exceptionOrNull()
        assertTrue(error?.message?.contains("before previous one finished") == true, "expected a protocol error, got $error")
    }

    @Test
    fun `the stateless readMessage keeps its old behaviour for whole messages`() {
        val input = stream { out -> fragment(out, 0x80 or WebSocketCodec.OPCODE_TEXT, "whole".toByteArray()) }
        assertEquals("whole", (WebSocketCodec.readMessage(input) as WebSocketCodec.Message.Text).text)
    }
}
