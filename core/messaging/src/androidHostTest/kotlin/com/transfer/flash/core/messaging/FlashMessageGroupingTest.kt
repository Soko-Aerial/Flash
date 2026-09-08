package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashMessageGroupPosition
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.util.computeMessageGroupPositions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashMessageGroupingTest {

    private fun message(id: String, sender: String, mine: Boolean = false) = FlashMessageUi(
        id = id,
        senderName = sender,
        senderInitials = sender.take(1),
        timeLabel = "12:00",
        text = "text-$id",
        isMine = mine,
    )

    @Test
    fun `single message is SINGLE`() {
        val result = computeMessageGroupPositions(listOf(message("1", "Ana")))
        assertEquals(FlashMessageGroupPosition.SINGLE, result.single().groupPosition)
    }

    @Test
    fun `consecutive same sender forms TOP MIDDLE BOTTOM`() {
        val result = computeMessageGroupPositions(
            listOf(
                message("1", "Ana"),
                message("2", "Ana"),
                message("3", "Ana"),
            ),
        )
        assertEquals(FlashMessageGroupPosition.TOP, result[0].groupPosition)
        assertEquals(FlashMessageGroupPosition.MIDDLE, result[1].groupPosition)
        assertEquals(FlashMessageGroupPosition.BOTTOM, result[2].groupPosition)
    }

    @Test
    fun `sender change breaks grouping`() {
        val result = computeMessageGroupPositions(
            listOf(
                message("1", "Ana"),
                message("2", "Ana"),
                message("3", "Bo"),
                message("4", "You", mine = true),
            ),
        )
        assertEquals(FlashMessageGroupPosition.TOP, result[0].groupPosition)
        assertEquals(FlashMessageGroupPosition.BOTTOM, result[1].groupPosition)
        assertEquals(FlashMessageGroupPosition.SINGLE, result[2].groupPosition)
        assertEquals(FlashMessageGroupPosition.SINGLE, result[3].groupPosition)
    }

    @Test
    fun `same name but different direction does not group`() {
        val result = computeMessageGroupPositions(
            listOf(
                message("1", "You", mine = true),
                message("2", "You", mine = false),
            ),
        )
        assertEquals(FlashMessageGroupPosition.SINGLE, result[0].groupPosition)
        assertEquals(FlashMessageGroupPosition.SINGLE, result[1].groupPosition)
    }

    @Test
    fun `sender header only at incoming group start`() {
        val result = computeMessageGroupPositions(
            listOf(
                message("1", "Ana"),
                message("2", "Ana"),
                message("3", "You", mine = true),
            ),
        )
        assertTrue(result[0].showSenderHeader)
        assertFalse(result[1].showSenderHeader)
        assertFalse(result[2].showSenderHeader)
    }
}
