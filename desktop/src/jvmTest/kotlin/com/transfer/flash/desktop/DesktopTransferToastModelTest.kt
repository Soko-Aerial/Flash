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
    ) = FlashTransfer(
        id = FlashTransferId(id),
        peerName = peer,
        fileName = file,
        direction = direction,
        bytesDone = 0L,
        bytesTotal = 10L,
        state = state,
        errorMessage = error,
    )

    @Test
    fun `an offer gets Accept and Decline while the app is behind`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(transfer("a", FlashTransferState.Offered, FlashTransferDirection.Receiving)),
            dismissed = emptySet(),
            appInForeground = false,
        )
        assertEquals(1, cards.size)
        assertEquals(listOf(ToastAction.Accept, ToastAction.Decline), cards.single().actions)
        assertEquals("Incoming file from Ada", cards.single().title)
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
    fun `a failed send offers Retry and Dismiss but a failed receive gets no card`() {
        val cards = DesktopTransferToastModel.cards(
            listOf(
                transfer("s", FlashTransferState.Failed, FlashTransferDirection.Sending),
                transfer("r", FlashTransferState.Failed, FlashTransferDirection.Receiving),
            ),
            dismissed = emptySet(),
            appInForeground = false,
        )
        assertEquals(listOf(FlashTransferId("s")), cards.map { it.transferId })
        assertEquals(listOf(ToastAction.Retry, ToastAction.Dismiss), cards.single().actions)
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
    fun `window height grows with the number of cards`() {
        assertEquals(0, DesktopTransferToastModel.windowHeightDp(0))
        assertEquals(96 + 16, DesktopTransferToastModel.windowHeightDp(1))
        assertEquals(2 * 96 + 8 + 16, DesktopTransferToastModel.windowHeightDp(2))
    }

    @Test
    fun `toast window position moves upward as height grows inside maximum bounds`() {
        val bounds = Rectangle(0, 0, 1920, 1040)
        val oneCardHeight = DesktopTransferToastModel.windowHeightDp(1)
        val twoCardHeight = DesktopTransferToastModel.windowHeightDp(2)
        val threeCardHeight = DesktopTransferToastModel.windowHeightDp(3)

        assertEquals(
            WindowPosition.Absolute(x = 1548.dp, y = (1040 - oneCardHeight - 12).dp),
            toastWindowPosition(bounds, windowWidthDp = 360, windowHeightDp = oneCardHeight),
        )
        assertEquals(
            WindowPosition.Absolute(x = 1548.dp, y = (1040 - twoCardHeight - 12).dp),
            toastWindowPosition(bounds, windowWidthDp = 360, windowHeightDp = twoCardHeight),
        )
        assertEquals(
            WindowPosition.Absolute(x = 1548.dp, y = (1040 - threeCardHeight - 12).dp),
            toastWindowPosition(bounds, windowWidthDp = 360, windowHeightDp = threeCardHeight),
        )

        val smallMonitor = Rectangle(100, 50, 300, 180)
        assertEquals(
            WindowPosition.Absolute(x = 100.dp, y = 50.dp),
            toastWindowPosition(smallMonitor, windowWidthDp = 360, windowHeightDp = twoCardHeight),
        )
    }
}
