package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashCallEventKind
import com.transfer.flash.core.messaging.model.FlashCallEventUi
import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.core.messaging.model.FlashMessageRecipientUi
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashRecipientState
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.core.messaging.model.FlashWaitingReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI-051 Message Info copy and eligibility (`docs/ui/message-info.md`). Pure: the sheet only lays these out. */
class FlashMessageInfoMathTest {

    private fun recipient(
        state: FlashRecipientState,
        name: String = "Ada",
        deliveredAt: String? = null,
        reason: FlashWaitingReason? = null,
    ) = FlashMessageRecipientUi(
        id = "a",
        name = name,
        initials = "AD",
        state = state,
        deliveredAtLabel = deliveredAt,
        waitingReason = reason,
    )

    private fun message(
        text: String = "hi",
        isMine: Boolean = true,
        images: List<FlashImageAttachmentUi> = emptyList(),
        files: List<FlashFileAttachmentUi> = emptyList(),
        voice: List<FlashVoiceAttachmentUi> = emptyList(),
        call: FlashCallEventUi? = null,
    ) = FlashMessageUi(
        id = "m1",
        senderName = "Me",
        senderInitials = "ME",
        timeLabel = "9:41",
        text = text,
        isMine = isMine,
        images = images,
        fileAttachments = files,
        voiceAttachments = voice,
        callEvent = call,
    )

    @Test
    fun sectionTitlesNameTheStateAndCountIt() {
        assertEquals("Read by \u00b7 2", FlashMessageInfoMath.sectionTitle(FlashRecipientState.Read, 2))
        assertEquals("Delivered to \u00b7 3", FlashMessageInfoMath.sectionTitle(FlashRecipientState.Delivered, 3))
        assertEquals("Not delivered yet \u00b7 1", FlashMessageInfoMath.sectionTitle(FlashRecipientState.Waiting, 1))
    }

    @Test
    fun aReadRecipientNeverShowsATime() {
        // Flash stores a read cursor, not when it moved, so a time here would be invented.
        assertEquals("Read", FlashMessageInfoMath.subtitle(recipient(FlashRecipientState.Read, deliveredAt = "9:42")))
    }

    @Test
    fun aDeliveredRecipientShowsWhenThenJustDeliveredWithoutATime() {
        assertEquals("Delivered 9:42", FlashMessageInfoMath.subtitle(recipient(FlashRecipientState.Delivered, deliveredAt = "9:42")))
        assertEquals("Delivered", FlashMessageInfoMath.subtitle(recipient(FlashRecipientState.Delivered)))
    }

    @Test
    fun aWaitingRecipientSaysWhyAndOnlyLeftGroupIsDifferent() {
        val notReached = recipient(FlashRecipientState.Waiting, reason = FlashWaitingReason.DeviceNotReached)
        val left = recipient(FlashRecipientState.Waiting, reason = FlashWaitingReason.LeftGroup)
        val unknown = recipient(FlashRecipientState.Waiting, reason = null)

        assertEquals("Waiting for device to connect", FlashMessageInfoMath.subtitle(notReached))
        assertEquals("No longer in the group", FlashMessageInfoMath.subtitle(left))
        assertEquals("Waiting for device to connect", FlashMessageInfoMath.subtitle(unknown), "a missing reason reads as the common case")
    }

    @Test
    fun theSpokenRowIsOneSentenceStartingWithTheName() {
        assertEquals("Ada, delivered 9:42", FlashMessageInfoMath.description(recipient(FlashRecipientState.Delivered, deliveredAt = "9:42")))
        assertEquals("Ada, read", FlashMessageInfoMath.description(recipient(FlashRecipientState.Read)))
        assertEquals(
            "Bo, no longer in the group",
            FlashMessageInfoMath.description(recipient(FlashRecipientState.Waiting, name = "Bo", reason = FlashWaitingReason.LeftGroup)),
        )
    }

    @Test
    fun previewQuotesTheTextAndOtherwiseNamesTheAttachment() {
        assertEquals("hello there", FlashMessageInfoMath.previewOf(message(text = "hello there")))
        assertEquals(
            "look",
            FlashMessageInfoMath.previewOf(message(text = "look", images = listOf(FlashImageAttachmentUi(id = "i1")))),
            "the text wins over an attachment",
        )
        assertEquals("Photo", FlashMessageInfoMath.previewOf(message(text = " ", images = listOf(FlashImageAttachmentUi(id = "i1")))))
        assertEquals("Video", FlashMessageInfoMath.previewOf(message(text = "", images = listOf(FlashImageAttachmentUi(id = "i1", isVideo = true)))))
        assertEquals(
            "3 photos",
            FlashMessageInfoMath.previewOf(message(text = "", images = List(3) { FlashImageAttachmentUi(id = "i$it") })),
        )
        assertEquals(
            "2 videos",
            FlashMessageInfoMath.previewOf(message(text = "", images = List(2) { FlashImageAttachmentUi(id = "i$it", isVideo = true) })),
        )
        assertEquals(
            "2 items",
            FlashMessageInfoMath.previewOf(
                message(text = "", images = listOf(FlashImageAttachmentUi(id = "p"), FlashImageAttachmentUi(id = "v", isVideo = true))),
            ),
        )
        assertEquals("Voice message", FlashMessageInfoMath.previewOf(message(text = "", voice = listOf(FlashVoiceAttachmentUi(id = "v1")))))
        assertEquals(
            "report.pdf",
            FlashMessageInfoMath.previewOf(message(text = "", files = listOf(FlashFileAttachmentUi(id = "f1", name = "report.pdf", sizeBytes = 10)))),
        )
        assertEquals("", FlashMessageInfoMath.previewOf(message(text = "")))
    }

    @Test
    fun onlyAMessageTheUserSentThatIsNotACallRowOffersInfo() {
        assertTrue(FlashMessageInfoMath.isAvailable(message(isMine = true)))
        assertFalse(FlashMessageInfoMath.isAvailable(message(isMine = false)), "a received message has no recipients of ours")
        assertFalse(
            FlashMessageInfoMath.isAvailable(message(isMine = true, call = FlashCallEventUi(FlashCallEventKind.Outgoing, video = false))),
            "a call log row is not a message anyone receives",
        )
    }

    @Test
    fun theMenuListsMessageInfoOnlyWhenOffered() {
        assertFalse(FlashMessageInfoMath.MENU_LABEL in messageActionLabels(isMine = true))
        assertTrue(FlashMessageInfoMath.MENU_LABEL in messageActionLabels(isMine = true, hasMessageInfo = true))
        assertEquals(
            listOf("Reply", "Copy Text", "Forward", "Select Multiple", "Message Info", "Delete for everyone", "Delete"),
            messageActionLabels(isMine = true, hasMessageInfo = true),
            "it sits with the other read-only actions, before the destructive ones",
        )
    }
}
