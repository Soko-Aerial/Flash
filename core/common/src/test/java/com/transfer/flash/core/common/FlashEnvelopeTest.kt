@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.protocol.FlashEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

class FlashEnvelopeTest {

    @Test
    fun envelope_holds_all_fields() {
        val envelope = FlashEnvelope(
            id = "env-1",
            type = "MSG_TEXT",
            payloadJson = """{"text":"hello"}""",
            senderId = "dev-1",
            sentAt = 1_000L
        )

        assertEquals("env-1", envelope.id)
        assertEquals("MSG_TEXT", envelope.type)
        assertEquals("""{"text":"hello"}""", envelope.payloadJson)
        assertEquals("dev-1", envelope.senderId)
        assertEquals(1_000L, envelope.sentAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelope_blank_id_throws() {
        FlashEnvelope(id = "  ", type = "MSG_TEXT", payloadJson = "{}", senderId = "dev-1", sentAt = 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelope_blank_type_throws() {
        FlashEnvelope(id = "env-1", type = "", payloadJson = "{}", senderId = "dev-1", sentAt = 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelope_blank_sender_id_throws() {
        FlashEnvelope(id = "env-1", type = "MSG_TEXT", payloadJson = "{}", senderId = "   ", sentAt = 1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelope_zero_sent_at_throws() {
        FlashEnvelope(id = "env-1", type = "MSG_TEXT", payloadJson = "{}", senderId = "dev-1", sentAt = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelope_negative_sent_at_throws() {
        FlashEnvelope(id = "env-1", type = "MSG_TEXT", payloadJson = "{}", senderId = "dev-1", sentAt = -5L)
    }

    @Test
    fun envelope_empty_payload_json_is_allowed() {
        val envelope = FlashEnvelope(id = "env-1", type = "PING", payloadJson = "", senderId = "dev-1", sentAt = 1L)
        assertEquals("", envelope.payloadJson)
    }

    @Test
    fun envelope_equality_and_copy() {
        val a = FlashEnvelope(id = "env-1", type = "MSG_TEXT", payloadJson = "{}", senderId = "dev-1", sentAt = 1L)
        val b = FlashEnvelope(id = "env-1", type = "MSG_TEXT", payloadJson = "{}", senderId = "dev-1", sentAt = 1L)
        val c = a.copy(sentAt = 2L)

        assertEquals(a, b)
        assertEquals(2L, c.sentAt)
    }
}
