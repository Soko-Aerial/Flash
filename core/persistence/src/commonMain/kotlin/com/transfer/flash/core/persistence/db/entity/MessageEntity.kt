package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat message, local-first. [localId] is the client-generated UUID and the stable key for
 * dedup on insert (idempotent sync). Tombstoning ([deletedAt]) never removes rows so history
 * pagination stays stable.
 */
@Entity(
    tableName = "messages",
    indices = [
        // Composite seek index matching the keyset pagination order (C1.3):
        // WHERE conversationId = ? AND (sentAt < :c OR sentAt = :c AND localId < :id)
        // ORDER BY sentAt DESC, localId DESC.
        Index(value = ["conversationId", "sentAt", "localId"]),
        // Stand-alone time index serves cross-conversation scans (retention pruning C1.6).
        Index(value = ["sentAt"]),
    ],
)
public data class MessageEntity(
    @PrimaryKey val localId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String?,
    val text: String,
    val sentAt: Long,
    val status: String,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
    /**
     * Attachment metadata (null text-only messages leave these null). [attachmentTransferId] links
     * the row to a live [com.transfer.flash] transfer so the UI can join progress; [attachmentPath]
     * is the openable local file (source URI on send, received path on the receiver). MIME drives
     * whether the bubble renders an image, a video play button, or a generic file card.
     */
    val attachmentTransferId: String? = null,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long = 0L,
    val attachmentPath: String? = null,
    /**
     * Reply/quote metadata (v3). [replyToId] is the quoted message's [localId]; [replyToPreview]
     * is a short snapshot of its text captured at send time so the quoted preview renders without
     * a join and survives the quoted message being tombstoned. Both null for non-reply messages.
     */
    val replyToId: String? = null,
    val replyToPreview: String? = null,
    /**
     * v6 (ADR-044 V1): the author's base64 signature over the canonical message bytes, for a message
     * in a v2 group. Stored because the row is later relayed by offline sync and the receiver must
     * be able to verify it. Null for direct messages, legacy groups and unsigned rows.
     */
    val groupSig: String? = null,
    /**
     * v11 (ERROR-108): the swarm offer a v2-group file message came with, kept so ANY holder can relay it to a
     * member that was offline when it was sent. [swarmRootSig] is the author's signature over the announcement
     * (root, size, name, type, sentAt), so a relay cannot swap the file. All three are null for everything else.
     */
    val swarmRoot: String? = null,
    val swarmPieceSize: Int? = null,
    val swarmRootSig: String? = null,
)
