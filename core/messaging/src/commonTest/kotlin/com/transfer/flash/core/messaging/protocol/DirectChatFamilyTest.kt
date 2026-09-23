@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.protocol

import com.transfer.flash.core.common.protocol.FlashTextFraming
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectChatFamilyTest {

    @Test
    fun matchesEveryDirectChatPrefix() {
        listOf("FLASH_MSG", "FLASH_RCPT", "FLASH_READ", "FLASH_REACT", "FLASH_TYPING", "FLASH_DACT").forEach {
            assertTrue(DirectChatFamily.matches(FlashTextFraming.encodeFields(it, "k" to "v")), it)
        }
    }

    @Test
    fun whitespaceVariantsThatStillDecodeAreStillCaught() {
        // parseFields trims first, so these decode as chat; the downgrade guard must see them too.
        assertTrue(DirectChatFamily.matches("  FLASH_MSG id=1"))
        assertTrue(DirectChatFamily.matches("FLASH_MSG id=1 "))
        assertTrue(DirectChatFamily.matches("FLASH_TYPING"))
    }

    @Test
    fun otherFamiliesAreNotInScope() {
        // Calls, groups, transfer control, PTT, pairing and the secured envelope itself.
        listOf("FLASH_CALL x=1", "FLASH_GROUP x=1", "FLASH_GMSG x=1", "FLASH_XFER x=1", "FLASH_PTT x=1",
            "FLASH_PAIR x=1", "FLASH_SEC abc", "FLASH_MSGX id=1").forEach {
            assertFalse(DirectChatFamily.matches(it), it)
        }
    }
}
