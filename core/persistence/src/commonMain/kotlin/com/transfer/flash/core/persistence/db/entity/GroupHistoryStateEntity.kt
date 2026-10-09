package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Device-local group history state (ADR-100, GSY): the one-time "how much to catch up" decision and the last time a
 * holder served this device. Never sent to a peer. A group with no row here is one this device joined before the
 * feature existed, or one whose host does not ask; both read as "decided, no card".
 *
 * @property cardState `PENDING` (joined, the new member has not chosen yet: no history is requested), `DECIDED` or
 *   `SKIPPED` (the member chose "none" or dismissed the card).
 * @property windowMs the messages window the member chose (0 = none); already clamped to the ceiling when it was stored.
 * @property includeFiles whether the member wants file offers of the last 7 days.
 * @property lastContactAtMs when a holder last finished serving this device a catch-up round (0 = never / unknown).
 */
@Entity(tableName = "group_history_state")
public data class GroupHistoryStateEntity(
    @PrimaryKey val groupId: String,
    val cardState: String,
    val windowMs: Long,
    val includeFiles: Boolean,
    val decidedAtMs: Long,
    val lastContactAtMs: Long,
    val createdAtMs: Long,
)
