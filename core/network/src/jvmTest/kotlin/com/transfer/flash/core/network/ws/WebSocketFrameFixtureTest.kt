@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the shared [WebSocketFrameFixtures] table against THIS target's `WebSocketCodec` (brutal-review F10).
 * The identical class exists in the other test source set; a drift between the two codec copies fails exactly one of them.
 */
class WebSocketFrameFixtureTest {

    @Test
    fun `every fixture frame decodes to its expected message`() {
        for (case in WebSocketFrameFixtures.cases) {
            val result = runCatching { WebSocketCodec.readMessage(ByteArrayInputStream(case.wire)) }
            when (val expect = case.expect) {
                is WebSocketFrameFixtures.Expect.Binary -> {
                    val message = result.getOrElse { throw AssertionError("${case.name}: ${it}", it) }
                    assertTrue("${case.name}: expected binary, got $message", message is WebSocketCodec.Message.Binary)
                    assertArrayEquals(case.name, expect.payload, (message as WebSocketCodec.Message.Binary).data)
                }
                is WebSocketFrameFixtures.Expect.Text -> {
                    val message = result.getOrElse { throw AssertionError("${case.name}: ${it}", it) }
                    assertTrue("${case.name}: expected text, got $message", message is WebSocketCodec.Message.Text)
                    assertEquals(case.name, expect.text, (message as WebSocketCodec.Message.Text).text)
                }
                WebSocketFrameFixtures.Expect.Failure ->
                    assertTrue("${case.name}: expected an IOException, got $result", result.exceptionOrNull() is IOException)
            }
        }
    }

    @Test
    fun `two back to back pool sized frames keep their own bytes and never share an array`() {
        // F9: a pooled buffer that escapes the codec must not be handed out again while a caller still holds it.
        for (size in listOf(16_384, 32_768)) {
            val first = WebSocketFrameFixtures.payload(size, seed = 1)
            val second = WebSocketFrameFixtures.payload(size, seed = 2)
            val wire = WebSocketFrameFixtures.frame(WebSocketFrameFixtures.OPCODE_BINARY, first, masked = true) +
                WebSocketFrameFixtures.frame(WebSocketFrameFixtures.OPCODE_BINARY, second, masked = true)
            val input = ByteArrayInputStream(wire)

            val a = (WebSocketCodec.readMessage(input) as WebSocketCodec.Message.Binary).data
            val b = (WebSocketCodec.readMessage(input) as WebSocketCodec.Message.Binary).data

            assertNotSame("size $size", a, b)
            assertArrayEquals("size $size first", first, a)
            assertArrayEquals("size $size second", second, b)
        }
    }
}
