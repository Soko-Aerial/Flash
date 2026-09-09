package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectMessageActionCodecTest {
    @Test
    fun deleteForEveryoneRoundTripsEscapedFields() {
        val frame = MessageWireFrame.DeleteForEveryone(
            messageId = "message = 100%",
            conversationId = "peer = remote",
            from = "author 100%",
        )

        val encoded = DirectMessageActionCodec.encode(frame)

        assertTrue(encoded.startsWith("FLASH_DACT action=delete "))
        assertEquals(frame, DirectMessageActionCodec.decode(encoded))
    }

    @Test
    fun unknownActionIsIgnored() {
        assertNull(
            DirectMessageActionCodec.decode(
                "FLASH_DACT action=future messageId=m conversationId=p from=a",
            ),
        )
    }
}
