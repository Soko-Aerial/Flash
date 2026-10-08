package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashFileRecipientUi
import com.transfer.flash.core.messaging.model.FlashRecipientProgress

/**
 * The sender's "who has the file" list for a group file bubble (ERROR-119 follow-up). Pure so the rules can be
 * unit-tested; the repository only supplies names.
 *
 * Order: members that have the whole file first (by name), then the rest by progress, furthest first, then by name.
 * A member's name falls back to the start of its device id so a row is never blank.
 */
internal data class FileRecipientView(
    val rows: List<FlashFileRecipientUi>,
    /** Members that have the whole file. */
    val haveAll: Int,
    /** Members the sender is waiting on in total: the rows seen, or the group's recipient count when that is larger. */
    val total: Int,
    /** Mean share of the file across [total] members (a member not seen yet counts as 0). */
    val meanProgress: Float,
) {
    /** One short line for the bubble. */
    fun summaryLine(canGoOffline: Boolean): String = when {
        total > 0 && haveAll >= total -> "Everyone has the file"
        canGoOffline -> "$haveAll of $total have it · You can go offline now"
        else -> "$haveAll of $total have it"
    }
}

internal fun buildFileRecipientView(
    recipients: List<FlashRecipientProgress>,
    knownRecipientCount: Int,
    nameOf: (String) -> String?,
): FileRecipientView {
    val rows = recipients.map {
        val name = nameOf(it.peerId)?.trim()?.ifEmpty { null } ?: it.peerId.take(ID_FALLBACK_LENGTH)
        FlashFileRecipientUi(
            id = it.peerId,
            name = name,
            progress = if (it.hasAll) 1f else it.progress.coerceIn(0f, 1f),
            hasAll = it.hasAll,
            online = it.online,
            speedMbps = if (it.hasAll || !it.online) 0f else it.bytesPerSec / 1_000_000f,
        )
    }
    val byName = compareBy<FlashFileRecipientUi>({ it.name.lowercase() }, { it.id })
    val sorted = rows.filter { it.hasAll }.sortedWith(byName) +
        rows.filter { !it.hasAll }.sortedWith(compareByDescending<FlashFileRecipientUi> { it.progress }.then(byName))
    val total = maxOf(rows.size, knownRecipientCount)
    val mean = if (total > 0) rows.sumOf { it.progress.toDouble() }.toFloat() / total else 0f
    return FileRecipientView(sorted, rows.count { it.hasAll }, total, mean)
}

private const val ID_FALLBACK_LENGTH = 8
