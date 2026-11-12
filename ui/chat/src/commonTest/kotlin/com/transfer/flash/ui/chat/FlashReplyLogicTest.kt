package com.transfer.flash.ui.chat

import androidx.compose.ui.graphics.Color
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashQuotedReplyUi
import com.transfer.flash.ui.theme.FlashColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashReplyLogicTest {

    private val messages = listOf(
        FlashMessageUi(
            id = "msg-1",
            senderName = "Alex",
            senderInitials = "A",
            timeLabel = "10:00 AM",
            text = "Are you sending the files?",
            isMine = false,
        ),
        FlashMessageUi(
            id = "msg-2",
            senderName = "You",
            senderInitials = "Y",
            timeLabel = "10:01 AM",
            text = "Yes, transferring now!",
            isMine = true,
            replyTo = FlashQuotedReplyUi(
                messageId = "msg-1",
                senderName = "Alex",
                textSnippet = "Are you sending the files?",
            ),
        ),
    )

    @Test
    fun `message with replyTo correctly stores quoted metadata`() {
        val replyMessage = messages[1]
        assertNotNull(replyMessage.replyTo)
        assertEquals("msg-1", replyMessage.replyTo?.messageId)
        assertEquals("Alex", replyMessage.replyTo?.senderName)
        assertEquals("Are you sending the files?", replyMessage.replyTo?.textSnippet)
    }

    @Test
    fun `jump target index in reversed list resolves correctly`() {
        val reversedList = messages.asReversed() // [msg-2, msg-1]

        val targetIndex1 = reversedList.indexOfFirst { it.id == "msg-1" }
        assertEquals(1, targetIndex1)

        val targetIndex2 = reversedList.indexOfFirst { it.id == "msg-2" }
        assertEquals(0, targetIndex2)

        val nonExistentIndex = reversedList.indexOfFirst { it.id == "msg-unknown" }
        assertEquals(-1, nonExistentIndex)
    }

    @Test
    fun `quoted reply snippet creation from message`() {
        val original = messages[0]
        val quote = FlashQuotedReplyUi(
            messageId = original.id,
            senderName = original.senderName,
            textSnippet = original.text,
            isMine = original.isMine,
        )

        assertEquals("msg-1", quote.messageId)
        assertEquals("Alex", quote.senderName)
        assertEquals("Are you sending the files?", quote.textSnippet)
        assertEquals(false, quote.isMine)
    }

    @Test
    fun `quoted reply card palette achieves AA contrast in light and dark bubbles`() {
        val themes = listOf(FlashColors.light(), FlashColors.dark())
        for (colors in themes) {
            for (isOutgoing in listOf(true, false)) {
                val bubbleBg = if (isOutgoing) colors.chatBgOutgoing else colors.chatBgIncoming
                val palette = quotedReplyPalette(colors, isParentOutgoing = isOutgoing)
                val resolvedCardBg = compositeOver(palette.cardBg, bubbleBg)
                val resolvedBar = compositeOver(palette.barColor, resolvedCardBg)

                assertTrue(
                    contrastRatio(palette.senderColor, resolvedCardBg) >= 4.5f,
                    "Sender contrast too low for outgoing=$isOutgoing",
                )
                assertTrue(
                    contrastRatio(palette.snippetColor, resolvedCardBg) >= 4.5f,
                    "Snippet contrast too low for outgoing=$isOutgoing",
                )
                assertTrue(
                    contrastRatio(resolvedBar, resolvedCardBg) >= 3.0f,
                    "Accent bar contrast too low for outgoing=$isOutgoing",
                )
            }
        }
        val lightOutgoing = quotedReplyPalette(FlashColors.light(), isParentOutgoing = true)
        assertNotEquals(Color.White, lightOutgoing.senderColor)
    }

    private fun compositeOver(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(
            red = fg.red * a + bg.red * (1f - a),
            green = fg.green * a + bg.green * (1f - a),
            blue = fg.blue * a + bg.blue * (1f - a),
            alpha = 1f,
        )
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    private fun relativeLuminance(color: Color): Float =
        0.2126f * linearize(color.red) +
            0.7152f * linearize(color.green) +
            0.0722f * linearize(color.blue)

    private fun linearize(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
}
