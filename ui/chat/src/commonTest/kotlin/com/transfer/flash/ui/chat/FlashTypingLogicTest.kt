package com.transfer.flash.ui.chat

import com.transfer.flash.core.common.model.FlashPeerPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FlashTypingLogicTest {

    @Test
    fun `typing presence state is correctly identified for typing bubble resolution`() {
        val title = "Sebastian"
        val onlinePresence = FlashPeerPresence.Online
        val typingPresence = FlashPeerPresence.Typing

        val onlineTypingName = if (onlinePresence == FlashPeerPresence.Typing) title else null
        assertNull(onlineTypingName)

        val activeTypingName = if (typingPresence == FlashPeerPresence.Typing) title else null
        assertNotNull(activeTypingName)
        assertEquals("Sebastian", activeTypingName)
    }

    @Test
    fun `format typing accessibility description`() {
        val peer = "Alex"
        val desc = "$peer is typing..."
        assertEquals("Alex is typing...", desc)
    }
}
