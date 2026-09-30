package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A conversation. List ordering is `pinned DESC, sortOrder DESC` — [sortOrder] is the
 * recency/activity rank maintained by the repository layer (C6), not by SQL triggers.
 */
@Entity(tableName = "conversations")
public data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
    val sortOrder: Long = 0L,
    val lastReadCursor: String? = null,
    /** Non-null only for a group conversation; immutable provenance for the membership log. */
    val groupCreatedBy: String? = null,
    val groupCreatedAt: Long? = null,
    /**
     * v6 (ADR-044 V1): the group protocol of this conversation. 1 = legacy (unsigned membership),
     * 2 = a v2 group whose id is derived from [groupOwnerKey]. Rows written before v6 read as 1.
     */
    val groupProto: Int = 1,
    /** v2 only: the owner's public key (base64 X.509 SPKI) from the signed charter. */
    val groupOwnerKey: String? = null,
    /** v2 only: the 16-byte charter nonce (base64) the group id was derived with. */
    val groupNonce: String? = null,
    /** v2 only: the owner's signature over the charter; [title] and [groupCreatedAt] are signed fields. */
    val groupCharterSig: String? = null,
)
