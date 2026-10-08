package com.transfer.flash.ui.chat

import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransferWaitReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlashSwarmUiMathTest {

    @Test
    fun receiverStatusLine_returnsFailureMessage_whenPresent() {
        val line = FlashSwarmUiMath.receiverStatusLine(
            waitReason = FlashTransferWaitReason.WaitingForSender,
            failureMessage = "Custom failure message",
        )
        assertEquals("Custom failure message", line)
    }

    @Test
    fun receiverStatusLine_waitingForSender_formatsWithProgress() {
        val line = FlashSwarmUiMath.receiverStatusLine(
            waitReason = FlashTransferWaitReason.WaitingForSender,
            senderName = "Alex",
            bytesDone = 50_000_000L,
            bytesTotal = 100_000_000L,
        )
        assertTrue(line != null && line.contains("Alex") && line.contains("50") && line.contains("100"))
    }

    @Test
    fun receiverStatusLine_waitingForSender_formatsWithoutProgressWhenZero() {
        val line = FlashSwarmUiMath.receiverStatusLine(
            waitReason = FlashTransferWaitReason.WaitingForSender,
            senderName = "Alex",
            bytesDone = 0L,
            bytesTotal = 100L * 1024L * 1024L,
        )
        assertEquals(TransferFailureText.waitingForSender("Alex"), line)
    }

    @Test
    fun receiverStatusLine_coversAllWaitReasons() {
        assertEquals(
            TransferFailureText.WAITING_FOR_MISSING_PARTS,
            FlashSwarmUiMath.receiverStatusLine(waitReason = FlashTransferWaitReason.WaitingForHolders),
        )
        assertEquals(
            TransferFailureText.WAITING_FOR_WIFI,
            FlashSwarmUiMath.receiverStatusLine(waitReason = FlashTransferWaitReason.WaitingForNetwork),
        )
        assertEquals(
            "Not enough free space",
            FlashSwarmUiMath.receiverStatusLine(waitReason = FlashTransferWaitReason.WaitingForSpace),
        )
        assertEquals(
            TransferFailureText.STORAGE_UNAVAILABLE,
            FlashSwarmUiMath.receiverStatusLine(waitReason = FlashTransferWaitReason.WaitingForStorage),
        )
        assertEquals(
            TransferFailureText.SYSTEM_TIMEOUT,
            FlashSwarmUiMath.receiverStatusLine(waitReason = FlashTransferWaitReason.WaitingForSystem),
        )
        assertEquals(
            TransferFailureText.CONNECTING_MEMBERS,
            FlashSwarmUiMath.receiverStatusLine(waitReason = FlashTransferWaitReason.WaitingForSession),
        )
    }

    @Test
    fun receiverStatusLine_multipleHoldersOnline_showsGettingFromDevices() {
        val line = FlashSwarmUiMath.receiverStatusLine(
            waitReason = null,
            holdersOnline = 3,
        )
        assertEquals("Getting it from 3 devices", line)
    }

    @Test
    fun receiverStatusLine_singleHolder_returnsNullForDefaultSpeedEta() {
        val line = FlashSwarmUiMath.receiverStatusLine(
            waitReason = null,
            holdersOnline = 1,
        )
        assertNull(line)
    }

    @Test
    fun senderStatusLine_sourceLost_showsPickAgain() {
        val line = FlashSwarmUiMath.senderStatusLine(isSourceLost = true)
        assertEquals(FlashSwarmUiMath.PICK_AGAIN_TO_SHARE, line)
    }

    @Test
    fun senderStatusLine_isComplete_returnsNull() {
        val line = FlashSwarmUiMath.senderStatusLine(
            deliveredTo = 3,
            deliveredTotal = 3,
            isComplete = true,
        )
        assertNull(line)
    }

    @Test
    fun senderStatusLine_canGoOffline_withRecipientCount() {
        val line = FlashSwarmUiMath.senderStatusLine(
            deliveredTo = 7,
            deliveredTotal = 9,
            canGoOffline = true,
        )
        assertEquals("Delivered to 7 of 9 · You can go offline now", line)
    }

    @Test
    fun senderStatusLine_canGoOffline_withoutRecipientCount() {
        val line = FlashSwarmUiMath.senderStatusLine(
            canGoOffline = true,
        )
        assertEquals("You can go offline now", line)
    }

    @Test
    fun senderStatusLine_deliveredToProgress() {
        val line = FlashSwarmUiMath.senderStatusLine(
            deliveredTo = 2,
            deliveredTotal = 5,
            canGoOffline = false,
        )
        assertEquals("Delivered to 2 of 5", line)
    }

    @Test
    fun senderStatusLine_holdersNeedingParts() {
        val line = FlashSwarmUiMath.senderStatusLine(
            holdersNeedingParts = 2,
            canGoOffline = false,
        )
        assertEquals("2 devices still need parts only you have", line)
    }

    @Test
    fun formatSubtitle_withDetailLine_appendsPercentageWhenInProgress() {
        val formatted = FlashSwarmUiMath.formatSubtitle(
            formattedSize = "100 MB",
            pct = 48,
            speedStr = " · 5.0 MB/s",
            etaStr = " · 10s left",
            detailLine = "Getting it from 3 devices",
        )
        assertEquals("Getting it from 3 devices · 48%", formatted)
    }

    @Test
    fun formatSubtitle_withoutDetailLine_returnsStandardFormattedSizeAndSpeed() {
        val formatted = FlashSwarmUiMath.formatSubtitle(
            formattedSize = "100 MB",
            pct = 48,
            speedStr = " · 5.0 MB/s",
            etaStr = " · 10s left",
            detailLine = null,
        )
        assertEquals("100 MB · 48% · 5.0 MB/s · 10s left", formatted)
    }

    @Test
    fun recipientStatusLine_coversEveryState() {
        assertEquals("Has the file", FlashSwarmUiMath.recipientStatusLine(hasAll = true, online = false, progress = 1f, speedMbps = 0f))
        assertEquals("Offline", FlashSwarmUiMath.recipientStatusLine(hasAll = false, online = false, progress = 0f, speedMbps = 0f))
        assertEquals("Offline · 40%", FlashSwarmUiMath.recipientStatusLine(hasAll = false, online = false, progress = 0.4f, speedMbps = 0f))
        assertEquals("Waiting to start", FlashSwarmUiMath.recipientStatusLine(hasAll = false, online = true, progress = 0f, speedMbps = 0f))
        assertEquals("40% · 2.5 MB/s", FlashSwarmUiMath.recipientStatusLine(hasAll = false, online = true, progress = 0.4f, speedMbps = 2.5f))
        assertEquals("40%", FlashSwarmUiMath.recipientStatusLine(hasAll = false, online = true, progress = 0.4f, speedMbps = 0f))
    }
}
