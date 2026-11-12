package com.transfer.flash.ui.transfers

/**
 * Words of the "cancel an active transfer" confirmation, shared by the Transfers screen and the
 * conversation screen. Outbound sends stop for the recipient(s); inbound receives stop downloading
 * and discard partial bytes.
 */
internal object FlashCancelCopy {
    fun title(group: Boolean): String = if (group) "Cancel for everyone?" else "Cancel and tell the other device?"

    fun body(group: Boolean): String =
        if (group) "Members who already have the file keep it." else "What the other device already received stays there."

    fun confirm(group: Boolean): String = if (group) "Cancel for everyone" else "Cancel transfer"

    fun title(direction: FlashTransferDirection, group: Boolean): String = when (direction) {
        FlashTransferDirection.Send -> title(group)
        FlashTransferDirection.Receive -> "Stop receiving this file?"
    }

    fun body(direction: FlashTransferDirection, group: Boolean): String = when (direction) {
        FlashTransferDirection.Send -> body(group)
        FlashTransferDirection.Receive -> "What's been saved so far won't be kept. The sender can still offer it again."
    }

    fun confirm(direction: FlashTransferDirection, group: Boolean): String = when (direction) {
        FlashTransferDirection.Send -> confirm(group)
        FlashTransferDirection.Receive -> "Stop receiving"
    }
}
