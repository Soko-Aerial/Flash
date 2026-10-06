package com.transfer.flash.core.messaging

import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.ConversationPreview
import com.transfer.flash.core.persistence.db.dao.ConversationUnread
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.GroupDeliveryCount
import com.transfer.flash.core.persistence.db.dao.GroupDeliveryDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReadCursorDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReadCursorEntity
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.dao.GroupInviteDao
import com.transfer.flash.core.persistence.db.dao.GroupJoinRequestDao
import com.transfer.flash.core.persistence.db.dao.GroupRotationDao
import com.transfer.flash.core.persistence.db.dao.GroupSecretDao
import com.transfer.flash.core.persistence.db.dao.GroupSettingsDao
import com.transfer.flash.core.persistence.db.dao.GroupPreferencesDao
import com.transfer.flash.core.persistence.db.entity.GroupInviteEntity
import com.transfer.flash.core.persistence.db.entity.GroupJoinRequestEntity
import com.transfer.flash.core.persistence.db.entity.GroupRotationEntity
import com.transfer.flash.core.persistence.db.entity.GroupSecretEntity
import com.transfer.flash.core.persistence.db.entity.GroupSettingsEntity
import com.transfer.flash.core.persistence.db.entity.GroupPreferencesEntity
import com.transfer.flash.core.messaging.group.GroupSecretStore
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// In-memory DAOs shared by the multi-repository group tests (GroupLateJoinDiagnosticTest,
// SignedGroupsTest). Faithful to the Room SQL for everything those scenarios exercise; the small
// chat-only DAOs (receipt/reaction/search/draft) are inert because no group test touches them.

internal class InMemoryMessageDao : MessageDao {
    val messages = ConcurrentHashMap<String, MessageEntity>()
    private val flow = MutableStateFlow<List<MessageEntity>>(emptyList())

    private fun publish() {
        flow.value = messages.values
            .filter { it.deletedAt == null }
            .sortedWith(compareByDescending<MessageEntity> { it.sentAt }.thenByDescending { it.localId })
    }

    override suspend fun insert(message: MessageEntity): Long {
        if (messages.containsKey(message.localId)) return -1L
        messages[message.localId] = message
        publish()
        return 1L
    }

    override fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
        flow.map { rows ->
            rows.filter { it.conversationId == conversationId }
                .sortedWith(compareByDescending<MessageEntity> { it.sentAt }.thenByDescending { it.localId })
        }

    /** `... AND deletedAt IS NULL AND (sentAt < :c OR (sentAt = :c AND localId < :id)) ORDER BY ... DESC`. */
    override suspend fun historyBefore(
        conversationId: String,
        cursorSentAt: Long,
        cursorLocalId: String,
        limit: Int,
    ): List<MessageEntity> = messages.values
        .filter {
            it.conversationId == conversationId && it.deletedAt == null &&
                (it.sentAt < cursorSentAt || (it.sentAt == cursorSentAt && it.localId < cursorLocalId))
        }
        .sortedWith(compareByDescending<MessageEntity> { it.sentAt }.thenByDescending { it.localId })
        .take(limit)

    /** Mirror query: `... AND (sentAt > :c OR (sentAt = :c AND localId > :id)) ORDER BY ... ASC`. */
    override suspend fun historyAfter(
        conversationId: String,
        cursorSentAt: Long,
        cursorLocalId: String,
        limit: Int,
    ): List<MessageEntity> = messages.values
        .filter {
            it.conversationId == conversationId && it.deletedAt == null &&
                (it.sentAt > cursorSentAt || (it.sentAt == cursorSentAt && it.localId > cursorLocalId))
        }
        .sortedWith(compareBy<MessageEntity> { it.sentAt }.thenBy { it.localId })
        .take(limit)

    override suspend fun getByLocalId(localId: String): MessageEntity? = messages[localId]

    override suspend fun existsAttachment(transferId: String): Boolean =
        messages.values.any { it.attachmentTransferId == transferId }

    override suspend fun updateAttachmentPath(transferId: String, path: String): Int = 0

    override suspend fun updateGroupContext(
        transferId: String,
        groupId: String,
        messageId: String,
        senderId: String,
        senderName: String,
    ): Int = 0

    override suspend fun updateStatus(localId: String, status: String) {
        messages[localId]?.let { messages[localId] = it.copy(status = status); publish() }
    }

    override suspend fun updateStatusIfUnacknowledged(localId: String, status: String) {
        val current = messages[localId] ?: return
        if (current.status == "DELIVERED" || current.status == "READ") return
        messages[localId] = current.copy(status = status)
        publish()
    }

    /**
     * Mirrors the Room query: our own rows in the thread that are not READ yet and were sent at or before the
     * named message. A name that resolves to no row bounds nothing (SQL `sentAt <= NULL` is never true).
     */
    override suspend fun markReadUpTo(conversationId: String, selfId: String, upToMessageId: String) {
        val bound = messages[upToMessageId]?.sentAt ?: return
        messages.values
            .filter {
                it.conversationId == conversationId && it.senderId == selfId && it.status != "READ" && it.sentAt <= bound
            }
            .forEach { messages[it.localId] = it.copy(status = "READ") }
        publish()
    }

    override suspend fun newestLocalId(conversationId: String): String? =
        messages.values.filter { it.conversationId == conversationId }.maxByOrNull { it.sentAt }?.localId

    override fun observeUnreadCounts(selfId: String): Flow<List<ConversationUnread>> =
        MutableStateFlow(
            messages.values
                .filter { it.senderId != selfId && it.deletedAt == null }
                .groupBy { it.conversationId }
                .map { (conversationId, rows) -> ConversationUnread(conversationId, rows.size) },
        ).asStateFlow()

    override fun observeLatestPreviews(): Flow<List<ConversationPreview>> =
        MutableStateFlow(emptyList())

    override suspend fun markEdited(localId: String, editedAt: Long) {
        messages[localId]?.let { messages[localId] = it.copy(editedAt = editedAt) }
    }

    override suspend fun markDeleted(localId: String, deletedAt: Long) {
        messages[localId]?.takeIf { it.deletedAt == null }?.let {
            messages[localId] = it.copy(deletedAt = deletedAt, text = "")
            publish()
        }
    }

    override suspend fun clearReplyPreviews(localId: String) {
        messages.values.filter { it.replyToId == localId }.forEach {
            messages[it.localId] = it.copy(replyToPreview = "")
        }
        publish()
    }

    override suspend fun deleteByConversations(ids: List<String>) {
        messages.values.filter { it.conversationId in ids }.forEach { messages.remove(it.localId) }
        publish()
    }

    override suspend fun searchMessages(query: String, limit: Int): List<MessageEntity> =
        messages.values.filter { it.deletedAt == null && it.text.contains(query, ignoreCase = true) }.take(limit)

    override suspend fun searchConversationMessages(
        conversationId: String,
        query: String,
        limit: Int,
    ): List<MessageEntity> =
        messages.values.filter {
            it.conversationId == conversationId && it.deletedAt == null && it.text.contains(query, ignoreCase = true)
        }.take(limit)
}

internal class InMemoryConversationDao : ConversationDao {
    val conversations = ConcurrentHashMap<String, ConversationEntity>()
    private val flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

    private fun publish() {
        flow.value = conversations.values.toList()
    }

    override suspend fun upsert(conversation: ConversationEntity) {
        conversations[conversation.id] = conversation
        publish()
    }

    override fun observeAll(): Flow<List<ConversationEntity>> = flow

    override suspend fun get(id: String): ConversationEntity? = conversations[id]

    override suspend fun setArchived(id: String, archived: Boolean) {
        conversations[id]?.let { conversations[id] = it.copy(archived = archived); publish() }
    }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        conversations[id]?.let { conversations[id] = it.copy(pinned = pinned); publish() }
    }

    override suspend fun setMuted(id: String, muted: Boolean) {
        conversations[id]?.let { conversations[id] = it.copy(muted = muted); publish() }
    }

    override suspend fun updateLastReadCursor(id: String, cursor: String) {
        conversations[id]?.let { conversations[id] = it.copy(lastReadCursor = cursor); publish() }
    }

    override suspend fun clearLastReadCursor(id: String) {
        conversations[id]?.let { conversations[id] = it.copy(lastReadCursor = null); publish() }
    }

    override suspend fun deleteConversations(ids: List<String>) {
        ids.forEach { conversations.remove(it) }
        publish()
    }

    override suspend fun updateDirectTitle(id: String, title: String) {
        conversations[id]?.takeIf { !it.isGroup }?.let { conversations[id] = it.copy(title = title); publish() }
    }
}

internal class InMemoryOutboxDao : OutboxDao {
    val queue = ConcurrentHashMap<String, OutboxEntity>()
    private val countFlow = MutableStateFlow(0)

    override suspend fun enqueue(item: OutboxEntity): Long {
        queue[item.localId] = item
        countFlow.value = queue.size
        return 1L
    }

    override suspend fun dueForDelivery(now: Long, limit: Int): List<OutboxEntity> =
        queue.values.filter { it.nextAttemptAt <= now }.sortedBy { it.nextAttemptAt }.take(limit)

    override suspend fun makePendingDue(now: Long) {
        queue.keys.forEach { localId ->
            queue[localId]?.let { queue[localId] = it.copy(attempts = 0, nextAttemptAt = now) }
        }
    }

    override suspend fun incrementAttempts(localId: String) {
        queue[localId]?.let { queue[localId] = it.copy(attempts = it.attempts + 1) }
    }

    override suspend fun rescheduleAttempt(localId: String, nextAttemptAt: Long) {
        queue[localId]?.let { queue[localId] = it.copy(attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt) }
    }

    override suspend fun delete(localId: String) {
        queue.remove(localId)
        countFlow.value = queue.size
    }

    override fun observeCount(): Flow<Int> = countFlow
}

internal class InMemoryGroupMemberDao : GroupMemberDao {
    val members = ConcurrentHashMap<Pair<String, String>, GroupMemberEntity>()

    /** Bumped on every upsert so observeMembers re-emits like a Room table flow does. */
    private val changes = MutableStateFlow(0)

    override suspend fun upsert(member: GroupMemberEntity) {
        members[member.groupId to member.deviceId] = member
        changes.value = changes.value + 1
    }

    override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> =
        changes.map {
            members.values.filter { it.groupId == groupId }
                .sortedWith(compareBy({ it.joinedAt }, { it.deviceId }))
        }

    override suspend fun activeMembers(groupId: String): List<GroupMemberEntity> =
        members.values.filter { it.groupId == groupId && it.isActive }
            .sortedWith(compareBy({ it.joinedAt }, { it.deviceId }))

    override suspend fun allMembers(groupId: String): List<GroupMemberEntity> =
        members.values.filter { it.groupId == groupId }
            .sortedWith(compareBy({ it.joinedAt }, { it.deviceId }))

    override suspend fun member(groupId: String, deviceId: String): GroupMemberEntity? =
        members[groupId to deviceId]

    override suspend fun activeCount(groupId: String): Int =
        members.values.count { it.groupId == groupId && it.isActive }

    override suspend fun activeGroupIdsFor(deviceId: String): List<String> =
        members.values.filter { it.deviceId == deviceId && it.isActive }.map { it.groupId }.distinct()

    /** `UPDATE group_members SET displayName = :newName WHERE deviceId = :deviceId AND certSig IS NULL`. */
    override suspend fun updateMemberDisplayName(deviceId: String, newName: String) {
        members.replaceAll { key, entity ->
            if (key.second == deviceId && entity.certSig == null) entity.copy(displayName = newName) else entity
        }
    }
}

internal class InMemoryGroupDeliveryDao : GroupDeliveryDao {
    val rows = ConcurrentHashMap<Pair<String, String>, GroupDeliveryEntity>()
    private val counts = MutableStateFlow<List<GroupDeliveryCount>>(emptyList())
    private val version = MutableStateFlow(0L)

    private fun publish() {
        version.update { it + 1 }
        counts.value = rows.values.groupBy { it.messageId }.map { (messageId, deliveries) ->
            GroupDeliveryCount(
                messageId = messageId,
                deliveredTo = deliveries.count { it.state == "DELIVERED" },
                deliveredTotal = deliveries.size,
            )
        }
    }

    override suspend fun insertAll(deliveries: List<GroupDeliveryEntity>) {
        deliveries.forEach { rows.putIfAbsent(it.messageId to it.memberId, it) }
        publish()
    }

    override suspend fun pendingForMessage(messageId: String): List<GroupDeliveryEntity> =
        rows.values.filter { it.messageId == messageId && it.state != "DELIVERED" }
            .sortedWith(compareBy({ it.nextAttemptAt }, { it.memberId }))

    override fun observeForMessage(messageId: String): Flow<List<GroupDeliveryEntity>> =
        version.map { rows.values.filter { it.messageId == messageId }.sortedBy { it.memberId } }

    override suspend fun memberCount(messageId: String): Int = rows.values.count { it.messageId == messageId }

    override suspend fun deliveredCount(messageId: String): Int =
        rows.values.count { it.messageId == messageId && it.state == "DELIVERED" }

    override fun observeDeliveryCounts(
        conversationId: String,
        selfId: String,
    ): Flow<List<GroupDeliveryCount>> = counts

    override suspend fun markDelivered(messageId: String, memberId: String, deliveredAt: Long): Int {
        val key = messageId to memberId
        val row = rows[key] ?: return 0
        if (row.state == "DELIVERED") return 0
        rows[key] = row.copy(state = "DELIVERED", deliveredAt = deliveredAt)
        publish()
        return 1
    }

    override suspend fun reschedule(messageId: String, memberId: String, state: String, nextAttemptAt: Long) {
        val key = messageId to memberId
        rows[key]?.let {
            rows[key] = it.copy(state = state, attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt)
        }
        publish()
    }

    override suspend fun makePendingDueForMember(memberId: String, now: Long) {
        rows.values.filter { it.memberId == memberId && it.state != "DELIVERED" }
            .forEach { rows[it.messageId to it.memberId] = it.copy(attempts = 0, nextAttemptAt = now) }
    }

    override suspend fun deleteForMessage(messageId: String) {
        rows.keys.removeAll { it.first == messageId }
        publish()
    }
}

internal class NoopReceiptDao : ReceiptDao {
    override suspend fun insert(receipt: ReceiptEntity): Long = 1L
    override fun observeForMessage(messageId: String): Flow<List<ReceiptEntity>> =
        MutableStateFlow<List<ReceiptEntity>>(emptyList()).asStateFlow()
    override suspend fun countForMessage(messageId: String): Int = 0
}

internal class NoopDraftDao : DraftDao {
    override suspend fun upsert(draft: DraftEntity) = Unit
    override fun observeDraft(conversationId: String): Flow<DraftEntity?> =
        MutableStateFlow<DraftEntity?>(null).asStateFlow()
    override suspend fun clear(conversationId: String) = Unit
}

internal class NoopRecentSearchDao : RecentSearchDao {
    override suspend fun upsert(search: RecentSearchEntity) = Unit
    override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> =
        MutableStateFlow<List<RecentSearchEntity>>(emptyList()).asStateFlow()
    override suspend fun remove(query: String) = Unit
    override suspend fun clearAll() = Unit
}

internal class NoopReactionDao : ReactionDao {
    override suspend fun upsert(reaction: ReactionEntity) = Unit
    override fun observeForMessage(messageId: String): Flow<List<ReactionEntity>> =
        MutableStateFlow<List<ReactionEntity>>(emptyList()).asStateFlow()
    override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> =
        MutableStateFlow<List<ReactionEntity>>(emptyList()).asStateFlow()
    override suspend fun get(messageId: String, emoji: String): ReactionEntity? = null
    override suspend fun remove(messageId: String, emoji: String) = Unit
}

/**
 * In-memory read cursors. `advanceFurthest` is deliberately NOT overridden: the interface's own monotonic
 * read-compare-write runs on top of these three primitives, so a test exercises the production ordering rule.
 */
internal class InMemoryReadCursorDao : ReadCursorDao {
    val cursors = ConcurrentHashMap<Pair<String, String>, ReadCursorEntity>()

    override suspend fun get(conversationId: String, memberId: String): ReadCursorEntity? =
        cursors[conversationId to memberId]

    override suspend fun insertSeed(cursor: ReadCursorEntity) {
        cursors.putIfAbsent(cursor.conversationId to cursor.memberId, cursor)
    }

    override suspend fun updateCursor(conversationId: String, memberId: String, upToMessageId: String, upToSentAt: Long) {
        cursors[conversationId to memberId]?.let {
            cursors[conversationId to memberId] = it.copy(upToMessageId = upToMessageId, upToSentAt = upToSentAt)
        }
    }

    override fun observeCursors(conversationId: String): Flow<List<ReadCursorEntity>> =
        MutableStateFlow(cursors.values.filter { it.conversationId == conversationId }.sortedBy { it.memberId }).asStateFlow()
}

internal class InMemoryGroupInviteDao : GroupInviteDao {
    val invites = ConcurrentHashMap<String, GroupInviteEntity>()

    override suspend fun upsert(entity: GroupInviteEntity) {
        invites[entity.groupId] = entity
    }

    override suspend fun getByGroupId(groupId: String): GroupInviteEntity? = invites[groupId]

    override suspend fun getByState(state: String): List<GroupInviteEntity> =
        invites.values.filter { it.state == state }.sortedByDescending { it.acceptedAtMs }

    override suspend fun updateState(groupId: String, state: String) {
        invites[groupId]?.let { invites[groupId] = it.copy(state = state) }
    }

    override suspend fun delete(groupId: String) {
        invites.remove(groupId)
    }

    override suspend fun getAll(): List<GroupInviteEntity> = invites.values.toList()
}

internal class InMemoryGroupJoinRequestDao : GroupJoinRequestDao {
    val requests = ConcurrentHashMap<Triple<String, String, String>, GroupJoinRequestEntity>()

    override suspend fun upsert(entity: GroupJoinRequestEntity) {
        requests[Triple(entity.groupId, entity.subjectId, entity.subjectKey)] = entity
    }

    override suspend fun get(groupId: String, subjectId: String, subjectKey: String): GroupJoinRequestEntity? =
        requests[Triple(groupId, subjectId, subjectKey)]

    override suspend fun getAllForGroup(groupId: String): List<GroupJoinRequestEntity> =
        requests.values.filter { it.groupId == groupId }.sortedByDescending { it.requestedAtMs }

    override suspend fun updateDecision(
        groupId: String,
        subjectId: String,
        subjectKey: String,
        state: String,
        decidedBy: String?,
        decidedAtMs: Long?,
    ) {
        val key = Triple(groupId, subjectId, subjectKey)
        requests[key]?.let {
            requests[key] = it.copy(state = state, decidedBy = decidedBy, decidedAtMs = decidedAtMs)
        }
    }

    override suspend fun deleteExpired(olderThanMs: Long) {
        requests.values.filter { it.requestedAtMs < olderThanMs }.forEach {
            requests.remove(Triple(it.groupId, it.subjectId, it.subjectKey))
        }
    }

    override suspend fun deleteForGroup(groupId: String) {
        requests.values.filter { it.groupId == groupId }.forEach {
            requests.remove(Triple(it.groupId, it.subjectId, it.subjectKey))
        }
    }

    override suspend fun getAll(): List<GroupJoinRequestEntity> = requests.values.toList()
}

internal class InMemoryGroupSecretDao : GroupSecretDao {
    val secrets = ConcurrentHashMap<Pair<String, Long>, GroupSecretEntity>()

    override suspend fun upsert(entity: GroupSecretEntity) {
        secrets[entity.groupId to entity.epoch] = entity
    }

    override suspend fun getByGroupAndEpoch(groupId: String, epoch: Long): GroupSecretEntity? =
        secrets[groupId to epoch]

    override suspend fun getLatestForGroup(groupId: String): GroupSecretEntity? =
        secrets.values.filter { it.groupId == groupId }.maxByOrNull { it.epoch }

    override suspend fun getAllForGroup(groupId: String): List<GroupSecretEntity> =
        secrets.values.filter { it.groupId == groupId }.sortedBy { it.epoch }

    override suspend fun deleteForGroup(groupId: String) {
        secrets.keys.removeAll { it.first == groupId }
    }

    override suspend fun getAll(): List<GroupSecretEntity> = secrets.values.toList()
}

internal class InMemoryGroupSecretStore : GroupSecretStore {
    val records = ConcurrentHashMap<Pair<String, Long>, StoredGroupSecret>()
    val currentEpochs = ConcurrentHashMap<String, Long>()

    override suspend fun current(groupId: String): StoredGroupSecret? {
        val cur = currentEpochs[groupId] ?: return null
        return records[groupId to cur]
    }

    override suspend fun get(groupId: String, epoch: Long): StoredGroupSecret? =
        records[groupId to epoch]

    override suspend fun put(record: StoredGroupSecret) {
        records[record.groupId to record.epoch] = record
        val cur = currentEpochs[record.groupId] ?: 0L
        if (record.epoch >= cur) currentEpochs[record.groupId] = record.epoch
    }

    override suspend fun forget(groupId: String) {
        records.keys.removeAll { it.first == groupId }
        currentEpochs.remove(groupId)
    }
}

internal class InMemoryGroupRotationDao : GroupRotationDao {
    val rotations = ConcurrentHashMap<Pair<String, Long>, GroupRotationEntity>()

    override suspend fun upsert(entity: GroupRotationEntity) {
        rotations[entity.groupId to entity.newEpoch] = entity
    }

    override suspend fun getByGroupAndEpoch(groupId: String, newEpoch: Long): GroupRotationEntity? =
        rotations[groupId to newEpoch]

    override suspend fun getLatestForGroup(groupId: String): GroupRotationEntity? =
        rotations.values.filter { it.groupId == groupId }
            .sortedWith(compareByDescending<GroupRotationEntity> { it.newEpoch }.thenBy { it.rotationId })
            .firstOrNull()

    override suspend fun getAllForGroup(groupId: String): List<GroupRotationEntity> =
        rotations.values.filter { it.groupId == groupId }.sortedBy { it.newEpoch }

    override suspend fun deleteForGroup(groupId: String) {
        rotations.keys.removeAll { it.first == groupId }
    }

    override suspend fun getAll(): List<GroupRotationEntity> = rotations.values.toList()
}

internal class InMemoryGroupSettingsDao : GroupSettingsDao {
    val settings = ConcurrentHashMap<String, GroupSettingsEntity>()

    override suspend fun upsert(entity: GroupSettingsEntity) {
        settings[entity.groupId] = entity
    }

    override suspend fun getByGroupId(groupId: String): GroupSettingsEntity? =
        settings[groupId]

    override suspend fun deleteForGroup(groupId: String) {
        settings.remove(groupId)
    }

    override suspend fun getAll(): List<GroupSettingsEntity> = settings.values.toList()
}

internal class InMemoryGroupPreferencesDao : GroupPreferencesDao {
    val preferences = ConcurrentHashMap<String, GroupPreferencesEntity>()

    override suspend fun upsert(entity: GroupPreferencesEntity) {
        preferences[entity.groupId] = entity
    }

    override suspend fun getByGroupId(groupId: String): GroupPreferencesEntity? =
        preferences[groupId]

    override suspend fun deleteForGroup(groupId: String) {
        preferences.remove(groupId)
    }

    override suspend fun getAll(): List<GroupPreferencesEntity> = preferences.values.toList()
}



