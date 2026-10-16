package com.transfer.flash.ui.chat

/**
 * UI-058 / H5: pure text rules for the Transfers action in the chat-list top bar (enrolled devices only).
 * Kept out of the composable so every state is unit-tested without Compose.
 */
object FlashChatListTopBarMath {

    /** Largest count shown on the indicator; anything above reads "99+". */
    const val MAX_BADGE = 99

    /**
     * ADR-160 (owner, 2026-10-16): a transfer is "active" for the indicator while it is unfinished work the person may need to act on.
     * Offered, Queued, Transferring, Verifying and Paused count (a paused big file must not look like nothing is pending);
     * Completed, Failed and Cancelled do not.
     */
    fun countsAsActive(state: com.transfer.flash.core.transfer.model.FlashTransferState): Boolean = when (state) {
        com.transfer.flash.core.transfer.model.FlashTransferState.Offered,
        com.transfer.flash.core.transfer.model.FlashTransferState.Queued,
        com.transfer.flash.core.transfer.model.FlashTransferState.Transferring,
        com.transfer.flash.core.transfer.model.FlashTransferState.Verifying,
        com.transfer.flash.core.transfer.model.FlashTransferState.Paused,
        -> true
        com.transfer.flash.core.transfer.model.FlashTransferState.Completed,
        com.transfer.flash.core.transfer.model.FlashTransferState.Failed,
        com.transfer.flash.core.transfer.model.FlashTransferState.Cancelled,
        -> false
    }

    /** Negative counts (a bad upstream value) are treated as none. */
    fun normalisedCount(active: Int): Int = if (active > 0) active else 0

    /** Indicator text, or null when nothing is active. */
    fun badgeText(active: Int): String? {
        val n = normalisedCount(active)
        return when {
            n == 0 -> null
            n > MAX_BADGE -> "$MAX_BADGE+"
            else -> n.toString()
        }
    }

    /** Accessibility label: "Transfers" or "Transfers, N active" (the exact count, never capped). */
    fun contentDescription(active: Int): String {
        val n = normalisedCount(active)
        return if (n == 0) "Transfers" else "Transfers, $n active"
    }
}
