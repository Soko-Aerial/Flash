package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.persistence.db.dao.SwarmDao
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.persistence.db.entity.SwarmTombstoneEntity
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmStateStore
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.swarm.model.SwarmWaitReason

/**
 * Room adapter for the swarm [SwarmStateStore] port (§5.4, SW-6).
 *
 * Implements [SwarmStateStore] backed by [SwarmDao] in `:core:persistence`.
 */
@OptIn(FlashInternalApi::class)
public class RoomSwarmStateStore(
    private val swarmDao: SwarmDao,
) : SwarmStateStore {

    /**
     * One row this build cannot read (an enum value from a newer or older build, a corrupt root) is skipped with a
     * warning; it used to throw out of `valueOf` and abort the restore of every other transfer.
     */
    override suspend fun loadAll(): List<SwarmContentRecord> =
        swarmDao.loadAllContent().mapNotNull { entity ->
            try {
                entity.toRecord()
            } catch (e: IllegalArgumentException) {
                FlashLog.w("SWARM", "swarm restore skipped an unreadable row group=${entity.groupId} msg=${entity.messageId}: ${e.message}")
                null
            }
        }

    override suspend fun upsert(record: SwarmContentRecord) {
        swarmDao.upsertContent(record.toEntity())
    }

    override suspend fun setBits(root: ContentRoot, groupId: String, bits: ByteArray, bytesDone: Long) {
        swarmDao.updateBits(
            root = root.hex,
            groupId = groupId,
            bits = bits,
            bytesDone = bytesDone,
            nowMs = SystemTimeSource.nowMs(),
        )
    }

    override suspend fun putTombstone(tombstone: SwarmTombstone) {
        swarmDao.upsertTombstone(tombstone.toEntity())
    }

    override suspend fun tombstones(): List<SwarmTombstone> =
        swarmDao.loadAllTombstones().mapNotNull { entity ->
            try {
                entity.toModel()
            } catch (e: IllegalArgumentException) {
                FlashLog.w("SWARM", "swarm restore skipped an unreadable tombstone group=${entity.groupId} msg=${entity.messageId}: ${e.message}")
                null
            }
        }

    override suspend fun delete(root: ContentRoot, groupId: String) {
        swarmDao.deleteContent(root.hex, groupId)
    }

    override suspend fun purgeExpired(nowMs: Long) {
        swarmDao.purgeExpiredContent(nowMs)
        swarmDao.purgeExpiredTombstones(nowMs)
    }

    public companion object {
        public fun SwarmContentRecord.toEntity(): SwarmContentEntity = SwarmContentEntity(
            root = root.hex,
            groupId = groupId,
            messageId = messageId,
            role = role.name,
            originId = originId,
            originKey = originKey,
            fileName = fileName,
            mime = mime,
            totalSize = totalSize,
            pieceSize = pieceSize,
            manifest = manifestBytes,
            bits = bits,
            bytesDone = bytesDone,
            state = state.name,
            waitReason = waitReason?.name,
            failReason = failReason,
            localTransferId = localTransferId,
            sourceUri = sourceUri,
            sourcePersistent = sourcePersistent,
            partialKey = partialKey,
            finalPath = finalPath,
            identitySize = identitySize,
            identityModifiedMs = identityModifiedMs,
            deliveredTo = if (deliveredTo.isEmpty()) "" else deliveredTo.joinToString(","),
            createdAtMs = createdAtMs,
            lastProgressAtMs = lastProgressAtMs,
            expiresAtMs = expiresAtMs,
        )

        public fun SwarmContentEntity.toRecord(): SwarmContentRecord = SwarmContentRecord(
            root = ContentRoot(root),
            groupId = groupId,
            messageId = messageId,
            role = SwarmRole.valueOf(role),
            originId = originId,
            originKey = originKey,
            fileName = fileName,
            mime = mime,
            totalSize = totalSize,
            pieceSize = pieceSize,
            manifestBytes = manifest,
            bits = bits,
            bytesDone = bytesDone,
            state = SwarmLifecycleState.valueOf(state),
            waitReason = waitReason?.let { runCatching { SwarmWaitReason.valueOf(it) }.getOrNull() },
            failReason = failReason,
            localTransferId = localTransferId,
            sourceUri = sourceUri,
            sourcePersistent = sourcePersistent,
            partialKey = partialKey,
            finalPath = finalPath,
            identitySize = identitySize,
            identityModifiedMs = identityModifiedMs,
            deliveredTo = parseDeliveredTo(deliveredTo),
            createdAtMs = createdAtMs,
            lastProgressAtMs = lastProgressAtMs,
            expiresAtMs = expiresAtMs,
        )

        public fun SwarmTombstone.toEntity(): SwarmTombstoneEntity = SwarmTombstoneEntity(
            groupId = groupId,
            messageId = messageId,
            root = root.hex,
            originId = originId,
            reason = reason.name,
            cancelledAtMs = cancelledAtMs,
            signature = signature,
            receivedAtMs = cancelledAtMs,
            expiresAtMs = cancelledAtMs + (37L * 24 * 60 * 60 * 1000L), // retention (30d) + 7 days
        )

        public fun SwarmTombstoneEntity.toModel(): SwarmTombstone = SwarmTombstone(
            groupId = groupId,
            root = ContentRoot(root),
            originId = originId,
            messageId = messageId,
            reason = runCatching { SwarmTombstoneReason.valueOf(reason) }.getOrDefault(SwarmTombstoneReason.USER),
            cancelledAtMs = cancelledAtMs,
            signature = signature,
        )

        private fun parseDeliveredTo(raw: String): Set<String> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptySet()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return trimmed.substring(1, trimmed.length - 1)
                    .split(",")
                    .map { it.trim().trim('"', '\'') }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            return trimmed.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }
}
