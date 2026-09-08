package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashMessageUi
import kotlin.test.Test
import kotlin.test.assertEquals

class FlashDateSeparatorLogicTest {
    @Test
    fun message_key_remains_message_id_when_separator_is_present() {
        val message = message("m1", "Today")

        assertEquals("m1", flashMessageKey(message))
    }

    @Test
    fun separator_has_an_explicit_accessibility_announcement() {
        assertEquals("Messages from Yesterday", daySeparatorContentDescription("Yesterday"))
    }

    private fun message(id: String, separator: String?) = FlashMessageUi(
        id = id,
        senderName = "Peer",
        senderInitials = "P",
        timeLabel = "12:00 PM",
        text = id,
        isMine = false,
        daySeparator = separator,
    )
}
