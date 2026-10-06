package com.transfer.flash.core.engine.group

import com.transfer.flash.core.messaging.group.GroupSecretSource
import com.transfer.flash.core.messaging.group.GroupSecretStore
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.persistence.db.dao.GroupSecretDao
import com.transfer.flash.core.persistence.db.entity.GroupSecretEntity
import com.transfer.flash.core.security.group.GroupSecret
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Room-backed implementation of [GroupSecretStore] (GM-2).
 *
 * Encrypted at rest: Flash database is encrypted with SQLCipher on both Android and Desktop,
 * so the stored bytes in the `group_secret` table are protected by database page encryption.
 */
public class RoomGroupSecretStore(
    private val dao: GroupSecretDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GroupSecretStore {

    override suspend fun current(groupId: String): StoredGroupSecret? = withContext(ioDispatcher) {
        dao.getLatestForGroup(groupId)?.toModel()
    }

    override suspend fun get(groupId: String, epoch: Long): StoredGroupSecret? = withContext(ioDispatcher) {
        dao.getByGroupAndEpoch(groupId, epoch)?.toModel()
    }

    override suspend fun put(record: StoredGroupSecret): Unit = withContext(ioDispatcher) {
        dao.upsert(record.toEntity())
    }

    override suspend fun forget(groupId: String): Unit = withContext(ioDispatcher) {
        dao.deleteForGroup(groupId)
    }

    private companion object {
        private fun GroupSecretEntity.toModel(): StoredGroupSecret? {
            val secret = runCatching { GroupSecret.fromBytes(secretWrapped) }.getOrNull() ?: return null
            val sourceEnum = runCatching { GroupSecretSource.valueOf(source) }.getOrDefault(GroupSecretSource.CREATED)
            return StoredGroupSecret(
                groupId = groupId,
                epoch = epoch,
                secret = secret,
                commit = commit,
                source = sourceEnum,
                receivedAtMs = receivedAtMs,
            )
        }

        private fun StoredGroupSecret.toEntity(): GroupSecretEntity {
            return GroupSecretEntity(
                groupId = groupId,
                epoch = epoch,
                secretWrapped = secret.toByteArray(),
                commit = commit,
                source = source.name,
                receivedAtMs = receivedAtMs,
            )
        }
    }
}
