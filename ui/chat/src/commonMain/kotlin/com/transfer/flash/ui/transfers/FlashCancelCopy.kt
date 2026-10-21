package com.transfer.flash.ui.transfers

/**
 * Words of the "cancel a file I am sending" confirmation, shared by the Transfers screen and the
 * conversation screen. A group file stops for every member; a 1:1 file stops for one other device
 * (the old wording said "everyone" for both, which read oddly in a chat with one person).
 */
internal object FlashCancelCopy {
    fun title(group: Boolean): String = if (group) "Cancel for everyone?" else "Cancel and tell the other device?"

    fun body(group: Boolean): String =
        if (group) "Members who already have the file keep it." else "What the other device already received stays there."

    fun confirm(group: Boolean): String = if (group) "Cancel for everyone" else "Cancel transfer"
}
