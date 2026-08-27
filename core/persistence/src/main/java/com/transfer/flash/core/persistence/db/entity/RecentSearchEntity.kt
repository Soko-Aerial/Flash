package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Recent search term. PK on the normalized query string; [usedAt] drives recency ordering. */
@Entity(tableName = "recent_searches")
public data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val usedAt: Long,
)
