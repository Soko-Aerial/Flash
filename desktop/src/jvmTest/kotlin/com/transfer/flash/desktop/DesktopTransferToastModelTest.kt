package com.transfer.flash.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C-06: which transfers get an actionable toast, and with which buttons. */
class DesktopTransferToastModelTest {

    private fun transfer(
        id: String,
        state: FlashTransferState,
        direction: FlashTransferDirection,
        peer: String = "Ada",
        file: String = "$id.bin",
        error: String? = null,
        bytesTotal: Long = 10L,
        viaHub: Boolean = false,
    ) = FlashTransfer(
        id = FlashTransferId(id),
        peerName = peer,
        fileName = file,
        direction = direction,
        bytesDone = 0L,
        bytesTotal = bytesTotal,
        state = state,
        errorMessage = error,
        viaHub = viaHub,
    )

    @Test
    fun `an offer gets Decline on left and Accept on right while the app is behind`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(transfer("a", FlashTransferState.Offered, FlashTransferDirection.Receiving)),
            dismissed = emptySet(),
            appInForeground = false,
        )
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(listOf(ToastAction.Decline, ToastAction.Accept), card.actions)
        assertEquals("Incoming file from Ada", card.title)
        assertEquals("a.bin", card.detail)
        assertEquals(10L, card.bytesTotal)
        assertEquals("10 B", card.formattedSize)
        assertEquals("LAN", card.transportLabel)
        assertEquals("A", card.peerInitial)
        assertEquals("BIN", card.fileType)
    }

    @Test
    fun `no card while the app is in front`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(transfer("a", FlashTransferState.Offered, FlashTransferDirection.Receiving)),
            dismissed = emptySet(),
            appInForeground = true,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `an answered or dismissed offer is gone`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(transfer("a", FlashTransferState.Offered, FlashTransferDirection.Receiving)),
            dismissed = setOf(FlashTransferId("a")),
            appInForeground = false,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `a failed send offers Dismiss on left and Retry on right but a failed receive gets no card`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(
                transfer("s", FlashTransferState.Failed, FlashTransferDirection.Sending),
                transfer("r", FlashTransferState.Failed, FlashTransferDirection.Receiving),
            ),
            dismissed = emptySet(),
            appInForeground = false,
        )
        assertEquals(listOf(FlashTransferId("s")), cards.map { it.transferId })
        val card = cards.single()
        assertEquals(listOf(ToastAction.Dismiss, ToastAction.Retry), card.actions)
        assertEquals(10L, card.bytesTotal)
        assertEquals("10 B", card.formattedSize)
        assertEquals("LAN", card.transportLabel)
    }

    @Test
    fun `a cancelled or active transfer never gets a card`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(
                transfer("c", FlashTransferState.Cancelled, FlashTransferDirection.Sending),
                transfer("x", FlashTransferState.Transferring, FlashTransferDirection.Receiving),
            ),
            dismissed = emptySet(),
            appInForeground = false,
        )
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `offers come first and the toast is capped`() {
        val list = listOf(
            transfer("f", FlashTransferState.Failed, FlashTransferDirection.Sending),
            transfer("o1", FlashTransferState.Offered, FlashTransferDirection.Receiving),
            transfer("o2", FlashTransferState.Offered, FlashTransferDirection.Receiving),
            transfer("o3", FlashTransferState.Offered, FlashTransferDirection.Receiving),
        )
        val cards = DesktopTransferToastModel.cards(list, emptySet(), appInForeground = false)
        assertEquals(DesktopTransferToastModel.MAX_CARDS, cards.size)
        assertEquals(listOf("o1", "o2", "o3"), cards.map { it.transferId.value })
    }

    @Test
    fun `toast width is 400 dp`() {
        assertEquals(400, DesktopTransferToastModel.WIDTH_DP)
    }

    @Test
    fun `window height grows with the number of cards using 116 dp baseline`() {
        assertEquals(0, DesktopTransferToastModel.windowHeightDp(0))
        assertEquals(116 + 16, DesktopTransferToastModel.windowHeightDp(1))
        assertEquals(2 * 116 + 8 + 16, DesktopTransferToastModel.windowHeightDp(2))
        assertEquals(3 * 116 + 2 * 8 + 16, DesktopTransferToastModel.windowHeightDp(3))
    }

    @Test
    fun `window height supports dynamic card height clamped to max 144 dp`() {
        assertEquals(130 + 16, DesktopTransferToastModel.windowHeightDp(1, cardHeightDp = 130))
        assertEquals(144 + 16, DesktopTransferToastModel.windowHeightDp(1, cardHeightDp = 200))
    }

    @Test
    fun `hub transfer sets Hub transport label and formats size correctly`() {
        val hubTransfer = transfer(
            id = "hub1",
            state = FlashTransferState.Offered,
            direction = FlashTransferDirection.Receiving,
            peer = "Grace Hopper",
            file = "manual.pdf",
            bytesTotal = 1536L,
            viaHub = true,
        )
        val cards = DesktopTransferToastModel.cards(listOf(hubTransfer), emptySet(), appInForeground = false)
        val card = cards.single()
        assertEquals("Hub", card.transportLabel)
        assertEquals("1.5 KB", card.formattedSize)
        assertEquals("G", card.peerInitial)
        assertEquals("PDF", card.fileType)
        assertEquals(listOf(ToastAction.Decline, ToastAction.Accept), card.actions)
    }

    @Test
    fun `toast window position moves upward as height grows inside maximum bounds`() {
        val bounds = Rectangle(0, 0, 1920, 1040)
        val oneCardHeight = DesktopTransferToastModel.windowHeightDp(1)
        val twoCardHeight = DesktopTransferToastModel.windowHeightDp(2)
        val threeCardHeight = DesktopTransferToastModel.windowHeightDp(3)

        assertEquals(
            WindowPosition.Absolute(x = 1508.dp, y = (1040 - oneCardHeight - 12).dp),
            toastWindowPosition(bounds, windowWidthDp = 400, windowHeightDp = oneCardHeight),
        )
        assertEquals(
            WindowPosition.Absolute(x = 1508.dp, y = (1040 - twoCardHeight - 12).dp),
            toastWindowPosition(bounds, windowWidthDp = 400, windowHeightDp = twoCardHeight),
        )
        assertEquals(
            WindowPosition.Absolute(x = 1508.dp, y = (1040 - threeCardHeight - 12).dp),
            toastWindowPosition(bounds, windowWidthDp = 400, windowHeightDp = threeCardHeight),
        )

        val smallMonitor = Rectangle(100, 50, 300, 180)
        assertEquals(
            WindowPosition.Absolute(x = 100.dp, y = 50.dp),
            toastWindowPosition(smallMonitor, windowWidthDp = 400, windowHeightDp = twoCardHeight),
        )
    }

    @Test
    fun `filterActiveCards respects individual autoDismiss deadlines without starving older cards`() {
        val now = 100_000L
        val olderCard = DesktopToastCard(
            id = "c1",
            title = "Old",
            detail = "8s auto-dismiss",
            actions = listOf(ToastAction.Dismiss),
            autoDismissAtMs = now + 8_000L,
        )
        val newerCard = DesktopToastCard(
            id = "c2",
            title = "New",
            detail = "15s auto-dismiss",
            actions = listOf(ToastAction.Dismiss),
            autoDismissAtMs = now + 15_000L,
        )
        val persistentCard = DesktopToastCard(
            id = "c3",
            title = "Offer",
            detail = "No auto-dismiss",
            actions = listOf(ToastAction.Decline, ToastAction.Accept),
            autoDismissAtMs = null,
        )

        // At t = 105_000L: both cards unexpired
        val at5s = DesktopTransferToastModel.filterActiveCards(
            listOf(olderCard, newerCard, persistentCard),
            dismissed = emptySet(),
            appInForeground = false,
            nowMs = now + 5_000L,
        )
        assertEquals(3, at5s.size)

        // At t = 109_000L: older card expired at 108_000L, newer card and persistent card survive
        val at9s = DesktopTransferToastModel.filterActiveCards(
            listOf(olderCard, newerCard, persistentCard),
            dismissed = emptySet(),
            appInForeground = false,
            nowMs = now + 9_000L,
        )
        assertEquals(2, at9s.size)
        assertEquals(listOf("c2", "c3"), at9s.map { it.id })

        // At t = 116_000L: older card and newer card expired, persistent card survives
        val at16s = DesktopTransferToastModel.filterActiveCards(
            listOf(olderCard, newerCard, persistentCard),
            dismissed = emptySet(),
            appInForeground = false,
            nowMs = now + 16_000L,
        )
        assertEquals(1, at16s.size)
        assertEquals("c3", at16s.single().id)
    }

    @Test
    fun `foreground shows eligible chat and refusal cards and background prioritizes incoming calls`() {
        val message = DesktopToastCard("chat", "New message", "Message body", emptyList(), kind = ToastKind.ChatMessage)
        val refusal = message.copy(id = "refusal", kind = ToastKind.Generic)
        val offer = message.copy(id = "offer", kind = ToastKind.TransferOffer)
        val call = message.copy(id = "call", kind = ToastKind.IncomingCall)
        val cards = listOf(message, refusal, offer, call)

        assertEquals(
            listOf("chat", "refusal"),
            DesktopTransferToastModel.filterActiveCards(cards, emptySet(), true).map { it.id },
        )
        assertEquals(
            listOf("call", "offer", "chat"),
            DesktopTransferToastModel.filterActiveCards(cards, emptySet(), false).map { it.id },
        )
    }

    @Test
    fun `cardHeightDp expands to max height when card is verifying`() {
        val minimalCard = DesktopToastCard(
            id = "c0",
            title = "File",
            detail = "",
            actions = emptyList(),
            formattedSize = "",
            transportLabel = "",
            isVerifying = false,
        )
        assertEquals(DesktopTransferToastModel.CARD_HEIGHT_DP, DesktopTransferToastModel.cardHeightDp(minimalCard))

        val normalCard = DesktopToastCard(
            id = "c1",
            title = "File",
            detail = "detail",
            actions = listOf(ToastAction.Dismiss),
            formattedSize = "",
            transportLabel = "",
            isVerifying = false,
        )
        assertEquals(DesktopTransferToastModel.CARD_EXPANDED_HEIGHT_DP, DesktopTransferToastModel.cardHeightDp(normalCard))

        val verifyingCard = normalCard.copy(isVerifying = true)
        assertEquals(DesktopTransferToastModel.CARD_MAX_HEIGHT_DP, DesktopTransferToastModel.cardHeightDp(verifyingCard))
    }
}
