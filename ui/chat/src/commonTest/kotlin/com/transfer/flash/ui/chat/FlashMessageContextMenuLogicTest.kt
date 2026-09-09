package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashMessageContextMenuLogicTest {
    @Test
    fun deleteForEveryoneIsVisibleOnlyForOwnMessages() {
        assertTrue("Delete for everyone" in messageActionLabels(isMine = true))
        assertFalse("Delete for everyone" in messageActionLabels(isMine = false))
        assertTrue("Delete" in messageActionLabels(isMine = true))
        assertTrue("Delete" in messageActionLabels(isMine = false))
    }
}
