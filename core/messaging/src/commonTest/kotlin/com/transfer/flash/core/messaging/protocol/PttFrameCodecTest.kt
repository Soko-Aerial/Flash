package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PttFrameCodecTest {
    @Test
    fun pingRoundTripsEscapedFields() {
        val frame = PttPingFrame(
            eventId = "event-123",
            from = "device = 100%",
            senderName = "Field Phone = 100%",
            sentAt = 1757336400000L,
        )

        val encoded = PttFrameCodec.encode(frame)

        assertTrue(encoded.startsWith("FLASH_PTT action=ping "))
        assertEquals(frame, PttFrameCodec.decode(encoded))
    }

    @Test
    fun unknownActionIsIgnored() {
        assertNull(
            PttFrameCodec.decode(
                "FLASH_PTT action=future eventId=e from=a senderName=n sentAt=1",
            ),
        )
    }

    @Test
    fun missingOrBlankFieldsAreRejected() {
        // Missing eventId.
        assertNull(
            PttFrameCodec.decode("FLASH_PTT action=ping from=a senderName=n sentAt=1"),
        )
        // Blank from.
        assertNull(
            PttFrameCodec.decode("FLASH_PTT action=ping eventId=e senderName=n sentAt=1"),
        )
        // Non-numeric sentAt.
        assertNull(
            PttFrameCodec.decode("FLASH_PTT action=ping eventId=e from=a senderName=n sentAt=now"),
        )
    }

    @Test
    fun wrongPrefixIsIgnored() {
        assertNull(
            PttFrameCodec.decode("FLASH_MSG action=ping eventId=e from=a senderName=n sentAt=1"),
        )
    }
}
