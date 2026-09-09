package com.transfer.flash.ui.settings

import kotlin.math.roundToLong

/** Pure display and action policy for the F6.3 received-files storage surface. */
object FlashStorageMath {

    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L
    private const val TIB = GIB * 1024L

    /** Formats a non-negative byte count with binary units and at most one decimal place. */
    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        if (safeBytes < KIB) return "$safeBytes B"

        val (unitBytes, suffix) = when {
            safeBytes >= TIB -> TIB to "TB"
            safeBytes >= GIB -> GIB to "GB"
            safeBytes >= MIB -> MIB to "MB"
            else -> KIB to "KB"
        }
        val tenths = ((safeBytes.toDouble() / unitBytes.toDouble()) * 10.0).roundToLong()
        return if (tenths % 10L == 0L) {
            "${tenths / 10L} $suffix"
        } else {
            "${tenths / 10L}.${tenths % 10L} $suffix"
        }
    }

    /**
     * Returns user-safe summary copy without exposing host exception text or pretending an unknown
     * total is zero. A cached total remains visible while an on-demand refresh is in progress.
     */
    fun usageSummary(totalBytes: Long?, isLoading: Boolean, hasError: Boolean): String = when {
        hasError && totalBytes != null -> "Refresh failed · ${formatBytes(totalBytes)} shown from last scan"
        hasError -> "Storage usage unavailable"
        isLoading && totalBytes != null -> "Refreshing · ${formatBytes(totalBytes)} used"
        isLoading -> "Calculating storage usage"
        totalBytes != null -> if (totalBytes <= 0L) "No received files" else "${formatBytes(totalBytes)} used"
        else -> "Storage usage unavailable"
    }

    /** Destructive clear is offered only for a current, successful, non-empty scan. */
    fun canClearReceivedFiles(
        totalBytes: Long?,
        isLoading: Boolean,
        hasError: Boolean,
        isClearing: Boolean,
    ): Boolean =
        totalBytes != null && totalBytes > 0L && !isLoading && !hasError && !isClearing
}
