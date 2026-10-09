package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity

/**
 * The contiguous catch-up watermark of one group per holder (ADR-100): this device holds everything [holderId] owns up to
 * `(sentAt, messageId)` inside the window it asked for. It advances only when a whole page was received, never because a
 * live message arrived out of order, so a gap below the newest local row is still asked for (defect S1 of
 * `docs/group/GROUP-SYNC-REVAMP-PLAN.md`). A holder that never sends a page marker (an older build) has no row.
 */
@Entity(tableName = "group_sync_watermark", primaryKeys = ["groupId", "holderId"])
public data class GroupSyncWatermarkEntity(
    val groupId: String,
    val holderId: String,
    val sentAt: Long,
    val messageId: String,
    val updatedAtMs: Long,
)
