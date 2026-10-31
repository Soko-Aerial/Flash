package com.transfer.flash.core.persistence.db.dao

import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity

/**
 * Lightweight projection for swarm content listings that avoids loading
 * large manifest (up to 131 KB) and bits BLOBs from overflow pages (OPT-O06-08).
 */
public data class SwarmContentSummary(
    val root: String,
    val groupId: String,
    val messageId: String,
    val role: String,
    val originId: String,
    val originKey: String,
    val fileName: String,
    val mime: String,
    val totalSize: Long,
    val pieceSize: Int,
    val bytesDone: Long,
    val state: String,
    val waitReason: String?,
    val failReason: String?,
    val localTransferId: String,
    val sourceUri: String?,
    val sourcePersistent: Boolean,
    val partialKey: String,
    val finalPath: String?,
    val identitySize: Long,
    val identityModifiedMs: Long,
    val deliveredTo: String,
    val createdAtMs: Long,
    val lastProgressAtMs: Long,
    val expiresAtMs: Long,
)

public fun SwarmContentEntity.toSummary(): SwarmContentSummary =
    SwarmContentSummary(
        root = root,
        groupId = groupId,
        messageId = messageId,
        role = role,
        originId = originId,
        originKey = originKey,
        fileName = fileName,
        mime = mime,
        totalSize = totalSize,
        pieceSize = pieceSize,
        bytesDone = bytesDone,
        state = state,
        waitReason = waitReason,
        failReason = failReason,
        localTransferId = localTransferId,
        sourceUri = sourceUri,
        sourcePersistent = sourcePersistent,
        partialKey = partialKey,
        finalPath = finalPath,
        identitySize = identitySize,
        identityModifiedMs = identityModifiedMs,
        deliveredTo = deliveredTo,
        createdAtMs = createdAtMs,
        lastProgressAtMs = lastProgressAtMs,
        expiresAtMs = expiresAtMs,
    )

