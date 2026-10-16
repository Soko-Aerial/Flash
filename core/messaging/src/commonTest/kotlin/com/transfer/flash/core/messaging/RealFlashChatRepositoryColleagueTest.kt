package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.OrgPeer
import com.transfer.flash.core.enterprise.OrgPeerResolver
import com.transfer.flash.core.messaging.model.FlashGroupJoinRequestUi
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.ConversationPreview
import com.transfer.flash.core.persistence.db.dao.ConversationUnread
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.GroupJoinRequestDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.GroupJoinRequestEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Phase B2 Colleague Chat and Capability Gate Test Suite (Plan B, ADR-134).
 * Exercises [RealFlashChatRepository] colleague conversation flows (ORGT-U27),
 * durable outbox handling (ORGT-U28), ORG dial candidate generation (ORGT-U29),
 * 1:1 reaction and group creation gates (ORGT-U25), and default regression safety (ORGT-U26).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealFlashChatRepositoryColleagueTest {

    private val reposToClose = mutableListOf<RealFlashChatRepository>()

    @AfterTest
    fun tearDown() {
        reposToClose.forEach { runCatching { it.close() } }
        reposToClose.clear()
    }

    private class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()
        private val flow = MutableStateFlow<List<MessageEntity>>(emptyList())

        override suspend fun insert(message: MessageEntity): Long {
            if (messages.containsKey(message.localId)) return -1L
            messages[message.localId] = message
            flow.value = messages.values.filter { it.deletedAt == null }.sortedByDescending { it.sentAt }
            return 1L
        }

        override fun observeConversation(conversationId: String): Flow<List<MessageEntity>> = flow

        override suspend fun historyBefore(
            conversationId: String,
            cursorSentAt: Long,
            cursorLocalId: String,
            limit: Int,
        ): List<MessageEntity> = messages.values.filter { it.conversationId == conversationId }.take(limit)

        override suspend fun historyAfter(
            conversationId: String,
            cursorSentAt: Long,
            cursorLocalId: String,
            limit: Int,
        ): List<MessageEntity> = messages.values.filter { it.conversationId == conversationId }.take(limit)

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
            messages[localId]?.let { messages[localId] = it.copy(status = status) }
        }

        override suspend fun updateStatusIfUnacknowledged(localId: String, status: String) {
            messages[localId]?.let { messages[localId] = it.copy(status = status) }
        }

        override suspend fun markReadUpTo(conversationId: String, selfId: String, upToMessageId: String) {}

        override suspend fun newestLocalId(conversationId: String): String? =
            messages.values.filter { it.conversationId == conversationId }.maxByOrNull { it.sentAt }?.localId

        override fun observeUnreadCounts(selfId: String): Flow<List<ConversationUnread>> = flowOf(emptyList())

        override fun observeLatestPreviews(): Flow<List<ConversationPreview>> = flowOf(emptyList())

        override suspend fun markEdited(localId: String, editedAt: Long) {
            messages[localId]?.let { messages[localId] = it.copy(editedAt = editedAt) }
        }

        override suspend fun markDeleted(localId: String, deletedAt: Long) {
            messages[localId]?.let { messages[localId] = it.copy(deletedAt = deletedAt, text = "") }
        }

        override suspend fun clearReplyPreviews(localId: String) {}

        override suspend fun deleteByConversations(ids: List<String>) {
            messages.entries.removeAll { it.value.conversationId in ids }
        }

        override suspend fun searchMessages(query: String, limit: Int): List<MessageEntity> = emptyList()

        override suspend fun searchConversationMessages(
            conversationId: String,
            query: String,
            limit: Int,
        ): List<MessageEntity> = emptyList()
    }

    private class FakeConversationDao : ConversationDao {
        val conversations = mutableMapOf<String, ConversationEntity>()
        private val flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

        override suspend fun upsert(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            flow.value = conversations.values.toList()
        }

        override fun observeAll(): Flow<List<ConversationEntity>> = flow
        override suspend fun get(id: String): ConversationEntity? = conversations[id]
        override suspend fun setArchived(id: String, archived: Boolean) {}
        override suspend fun setPinned(id: String, pinned: Boolean) {}
        override suspend fun setMuted(id: String, muted: Boolean) {}
        override suspend fun updateLastReadCursor(id: String, cursor: String) {}
        override suspend fun clearLastReadCursor(id: String) {}
        override suspend fun deleteConversations(ids: List<String>) {
            ids.forEach { conversations.remove(it) }
        }
        override suspend fun updateDirectTitle(id: String, title: String) {
            conversations[id]?.let { conversations[id] = it.copy(title = title) }
        }
    }

    private class FakeOutboxDao : OutboxDao {
        val outbox = mutableMapOf<String, OutboxEntity>()

        override suspend fun enqueue(item: OutboxEntity): Long {
            outbox[item.localId] = item
            return 1L
        }

        override suspend fun dueForDelivery(now: Long, limit: Int): List<OutboxEntity> =
            outbox.values.filter { it.nextAttemptAt <= now }.take(limit)

        override suspend fun makePendingDue(now: Long) {
            outbox.replaceAll { _, v -> v.copy(attempts = 0, nextAttemptAt = now) }
        }

        override suspend fun incrementAttempts(localId: String) {
            outbox[localId]?.let { outbox[localId] = it.copy(attempts = it.attempts + 1) }
        }

        override suspend fun rescheduleAttempt(localId: String, nextAttemptAt: Long) {
            outbox[localId]?.let { outbox[localId] = it.copy(attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt) }
        }

        override suspend fun delete(localId: String) {
            outbox.remove(localId)
        }

        override fun observeCount(): Flow<Int> = MutableStateFlow(outbox.size)
    }

    private class FakeReceiptDao : ReceiptDao {
        override suspend fun insert(receipt: ReceiptEntity): Long = 1L
        override fun observeForMessage(messageId: String): Flow<List<ReceiptEntity>> = flowOf(emptyList())
        override suspend fun countForMessage(messageId: String): Int = 0
    }

    private class FakeDraftDao : DraftDao {
        override suspend fun upsert(draft: DraftEntity) {}
        override fun observeDraft(conversationId: String): Flow<DraftEntity?> = flowOf(null)
        override suspend fun clear(conversationId: String) {}
    }

    private class FakeRecentSearchDao : RecentSearchDao {
        override suspend fun upsert(search: RecentSearchEntity) {}
        override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> = flowOf(emptyList())
        override suspend fun remove(query: String) {}
        override suspend fun clearAll() {}
    }

    private class FakeReactionDao : ReactionDao {
        val reactions = mutableMapOf<Pair<String, String>, ReactionEntity>()

        override suspend fun upsert(reaction: ReactionEntity) {
            reactions[reaction.messageId to reaction.emoji] = reaction
        }

        override fun observeForMessage(messageId: String): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> = flowOf(emptyList())
        override suspend fun get(messageId: String, emoji: String): ReactionEntity? = reactions[messageId to emoji]
        override suspend fun remove(messageId: String, emoji: String) {
            reactions.remove(messageId to emoji)
        }
    }

    private class FakeOrgPeerResolver(
        private val members: Map<String, OrgPeer> = emptyMap(),
        leaseState: OrgLease = OrgLease.None,
    ) : OrgPeerResolver {
        private val _lease = MutableStateFlow(leaseState)
        override val lease: StateFlow<OrgLease> = _lease.asStateFlow()

        fun updateLease(newLease: OrgLease) {
            _lease.value = newLease
        }

        override fun resolve(deviceId: String): OrgPeer? {
            if (_lease.value !is OrgLease.Valid) return null
            return members[deviceId]
        }

        override fun isKnownColleague(deviceId: String): Boolean =
            _lease.value is OrgLease.Valid && members.containsKey(deviceId)

        override fun searchColleagueIds(query: String, limit: Int): Set<String> {
            if (_lease.value !is OrgLease.Valid) return emptySet()
            val q = query.trim().lowercase()
            if (q.isEmpty()) return emptySet()
            return members.values
                .filter { it.displayName.lowercase().contains(q) || it.departmentId.lowercase().contains(q) || it.role.lowercase().contains(q) }
                .take(limit)
                .map { it.deviceId }
                .toSet()
        }

        override fun displayName(deviceId: String): String? = members[deviceId]?.displayName

        override suspend fun refreshFromSnapshot() {}
        override suspend fun clear() {
            _lease.value = OrgLease.None
        }
    }

    private class MutableTimeSource(var timeMs: Long = 1_000_000L) : FlashTimeSource {
        override fun nowMs(): Long = timeMs
    }

    private val localDeviceId = "local-device-self"
    private val colleagueAlice = "colleague-alice"
    private val colleagueBob = "colleague-bob"
    private val strangerEve = "stranger-eve"

    private fun FlashResult.Failure.errorText(): String = when (val e = error) {
        is FlashError.Unknown -> e.message
        else -> e.toString()
    }

    private class FakeGroupJoinRequestDao : GroupJoinRequestDao {
        val requests = mutableListOf<GroupJoinRequestEntity>()
        override suspend fun upsert(entity: GroupJoinRequestEntity) { requests.add(entity) }
        override suspend fun get(groupId: String, subjectId: String, subjectKey: String): GroupJoinRequestEntity? =
            requests.firstOrNull { it.groupId == groupId && it.subjectId == subjectId && it.subjectKey == subjectKey }
        override suspend fun getAllForGroup(groupId: String): List<GroupJoinRequestEntity> =
            requests.filter { it.groupId == groupId }
        override suspend fun updateDecision(groupId: String, subjectId: String, subjectKey: String, state: String, decidedBy: String?, decidedAtMs: Long?) {}
        override suspend fun deleteExpired(olderThanMs: Long) {}
        override suspend fun deleteForGroup(groupId: String) {}
        override suspend fun getAll(): List<GroupJoinRequestEntity> = requests
    }

    private class FakeGroupMemberDao : GroupMemberDao {
        val members = mutableMapOf<Pair<String, String>, GroupMemberEntity>()
        override suspend fun upsert(member: GroupMemberEntity) {
            members[member.groupId to member.deviceId] = member
        }
        override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> =
            flowOf(members.values.filter { it.groupId == groupId })
        override suspend fun activeMembers(groupId: String): List<GroupMemberEntity> =
            members.values.filter { it.groupId == groupId && it.isActive }
        override suspend fun allMembers(groupId: String): List<GroupMemberEntity> =
            members.values.filter { it.groupId == groupId }
        override suspend fun member(groupId: String, deviceId: String): GroupMemberEntity? =
            members[groupId to deviceId]
        override suspend fun activeCount(groupId: String): Int =
            members.values.count { it.groupId == groupId && it.isActive }
        override suspend fun activeGroupIdsFor(deviceId: String): List<String> =
            members.values.filter { it.deviceId == deviceId && it.isActive }.map { it.groupId }.distinct()
        override suspend fun updateMemberDisplayName(deviceId: String, newName: String) {}
    }

    private fun createRepository(
        timeSource: FlashTimeSource,
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        outboxDao: OutboxDao,
        reactionDao: ReactionDao,
        groupMemberDao: GroupMemberDao? = null,
        groupJoinRequestDao: GroupJoinRequestDao? = null,
        peerNameResolver: (String) -> String? = { null },
        isTrustedPeer: (String) -> Boolean = { false },
        isOrgPeer: (String) -> Boolean = { false },
        orgPeerResolver: OrgPeerResolver? = null,
        transportSink: MessageTransportSink? = null,
        enterpriseTrustStore: com.transfer.flash.core.security.enterprise.EnterpriseTrustStore? = null,
        hubGroupRosters: com.transfer.flash.core.enterprise.HubGroupRosterView? = null,
        pairVerifiedColleague: ((String, String) -> Boolean)? = null,
        groupTransportSink: GroupTransportSink? = null,
        peerFeatures: (String) -> Set<String> = { emptySet() },
    ): RealFlashChatRepository {
        val repo = RealFlashChatRepository(
            localDeviceId = localDeviceId,
            localDisplayName = "My Device",
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            receiptDao = FakeReceiptDao(),
            draftDao = FakeDraftDao(),
            recentSearchDao = FakeRecentSearchDao(),
            reactionDao = reactionDao,
            groupMemberDao = groupMemberDao,
            groupJoinRequestDao = groupJoinRequestDao,
            peerNameResolver = peerNameResolver,
            isTrustedPeer = isTrustedPeer,
            isOrgPeer = isOrgPeer,
            orgPeerResolver = orgPeerResolver,
            transportSink = transportSink,
            enterpriseTrustStore = enterpriseTrustStore,
            hubGroupRosters = hubGroupRosters,
            pairVerifiedColleague = pairVerifiedColleague,
            groupTransportSink = groupTransportSink,
            peerFeatures = peerFeatures,
            ioDispatcher = Dispatchers.Unconfined,
            timeSource = timeSource,
        )
        reposToClose.add(repo)
        return repo
    }

    @Test
    fun ORGT_U27_startColleagueConversation_rejectsSelfWithExactSentence() = runTest {
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
        )
        val result = repo.startColleagueConversation(localDeviceId)
        assertTrue(result is FlashResult.Failure)
        assertEquals("That is you.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U27_startColleagueConversation_rejectsWhenNoSyncOrResolverWithExactSentence() = runTest {
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            orgPeerResolver = null,
        )
        val result = repo.startColleagueConversation(colleagueAlice)
        assertTrue(result is FlashResult.Failure)
        assertEquals("Connect to the company network once to load your colleagues.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U27_startColleagueConversation_rejectsWhenLeaseNoneWithExactSentence() = runTest {
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.None,
        )
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )
        val result = repo.startColleagueConversation(colleagueAlice)
        assertTrue(result is FlashResult.Failure)
        assertEquals("Connect to the company network once to load your colleagues.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U27_startColleagueConversation_rejectsWhenLeaseExpiredWithExactSentence() = runTest {
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Expired(sinceMs = 500_000L, reason = ExpiredReason.TIME),
        )
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )
        val result = repo.startColleagueConversation(colleagueAlice)
        assertTrue(result is FlashResult.Failure)
        assertEquals("Reconnect to your company network to start a chat with this colleague.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U27_startColleagueConversation_rejectsWhenNotInDirectoryWithExactSentence() = runTest {
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )
        val result = repo.startColleagueConversation(strangerEve)
        assertTrue(result is FlashResult.Failure)
        assertEquals("This person is not in your company directory.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U27_startColleagueConversation_rejectsWhenRemovedFromDirectoryWithExactSentence() = runTest {
        val convDao = FakeConversationDao()
        // Prior conversation existed with Bob
        convDao.upsert(ConversationEntity(id = colleagueBob, title = "Bob", isGroup = false, sortOrder = 100L))

        // But directory snapshot no longer has Bob
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            convDao,
            FakeOutboxDao(),
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )
        val result = repo.startColleagueConversation(colleagueBob)
        assertTrue(result is FlashResult.Failure)
        assertEquals("This person is no longer part of your organisation.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U27_startColleagueConversation_succeedsForValidColleagueAndCreatesConversation() = runTest {
        val convDao = FakeConversationDao()
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice Engineer", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val timeSource = MutableTimeSource(1_234_567L)
        val repo = createRepository(
            timeSource,
            FakeMessageDao(),
            convDao,
            FakeOutboxDao(),
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )
        val result = repo.startColleagueConversation(colleagueAlice)
        assertTrue(result is FlashResult.Success)
        assertEquals(colleagueAlice, (result as FlashResult.Success).value)

        val storedConv = convDao.get(colleagueAlice)
        assertNotNull(storedConv)
        assertEquals("Alice Engineer", storedConv.title)
        assertFalse(storedConv.isGroup)
        assertEquals(1_234_567L, storedConv.sortOrder)
    }

    @Test
    fun ORGT_U28_durableOutbox_survivesRestartWithUnreachableColleague() = runTest {
        val msgDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        val convDao = FakeConversationDao()
        val reactionDao = FakeReactionDao()
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val timeSource = MutableTimeSource(1_000_000L)

        // Session 1: Send message while colleague is unreachable
        val repo1 = createRepository(
            timeSource,
            msgDao,
            convDao,
            outboxDao,
            reactionDao,
            orgPeerResolver = resolver,
            transportSink = { _, _ -> false }, // Offline/unreachable transport sink
        )

        repo1.openConversation(colleagueAlice)
        repo1.sendText("Hello offline colleague!")

        assertEquals(1, msgDao.messages.size)
        val msgBeforeRestart = msgDao.messages.values.first()
        assertEquals(colleagueAlice, msgBeforeRestart.conversationId)
        assertEquals("Hello offline colleague!", msgBeforeRestart.text)
        assertEquals(1, outboxDao.outbox.size)
        val outboxBeforeRestart = outboxDao.outbox.values.first()
        assertEquals(msgBeforeRestart.localId, outboxBeforeRestart.localId)

        // Simulate app kill / restart: close repo1 and create repo2 using same persistent DAOs
        repo1.close()

        val repo2 = createRepository(
            timeSource,
            msgDao,
            convDao,
            outboxDao,
            reactionDao,
            orgPeerResolver = resolver,
            transportSink = { _, _ -> false },
        )

        // Post-restart verification: outbox and message survive
        assertEquals(1, msgDao.messages.size, "Message must survive restart")
        assertEquals(1, outboxDao.outbox.size, "Outbox item must survive restart")
        val msgAfterRestart = msgDao.getByLocalId(msgBeforeRestart.localId)
        assertNotNull(msgAfterRestart)
        assertEquals("Hello offline colleague!", msgAfterRestart.text)
        timeSource.timeMs += 10_000L
        val outboxAfterRestart = outboxDao.dueForDelivery(timeSource.nowMs(), 10)
        assertEquals(1, outboxAfterRestart.size, "Outbox entry must remain due for delivery after restart")
        assertEquals(msgBeforeRestart.localId, outboxAfterRestart.first().localId)
    }

    @Test
    fun ORGT_U29_orgDialCandidates_cappedAtEightAndPrioritizesOpenOutboxRecent() = runTest {
        val msgDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        val convDao = FakeConversationDao()
        val members = mutableMapOf<String, OrgPeer>()
        for (i in 1..15) {
            val id = "colleague-$i"
            members[id] = OrgPeer(id, "Colleague $i", "eng", "dev", "FP_$i", true)
        }
        val resolver = FakeOrgPeerResolver(
            members = members,
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val now = 1_000_000_000L
        val repo = createRepository(
            MutableTimeSource(now),
            msgDao,
            convDao,
            outboxDao,
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )

        // 1. Open conversation is colleague-1 (Priority 1)
        repo.openConversation("colleague-1")

        // 2. Outbox pending for colleague-2 and colleague-3 (Priority 2)
        msgDao.insert(MessageEntity("m1", "colleague-2", localDeviceId, "Me", "hi", now, "PENDING"))
        outboxDao.enqueue(OutboxEntity("m1", 0, now, "{}", now))
        msgDao.insert(MessageEntity("m2", "colleague-3", localDeviceId, "Me", "hi", now, "PENDING"))
        outboxDao.enqueue(OutboxEntity("m2", 0, now, "{}", now))

        // 3. Recent conversations for colleague-4 through colleague-15 (Priority 3)
        for (i in 4..15) {
            convDao.upsert(ConversationEntity("colleague-$i", "Colleague $i", false, sortOrder = now - (i * 1000L)))
        }

        val candidates = repo.orgDialCandidates(nowMs = now, limit = 8)

        // Cap verification: strictly capped at limit (8)
        assertEquals(8, candidates.size, "orgDialCandidates must be capped at 8")

        // Priority 1 verification: open conversation MUST be first
        assertEquals("colleague-1", candidates.first(), "Priority 1 must be open conversation")

        // Priority 2 verification: outbox peers must precede recent conversations
        assertTrue(candidates.contains("colleague-2"), "Must contain outbox peer colleague-2")
        assertTrue(candidates.contains("colleague-3"), "Must contain outbox peer colleague-3")

        // Priority 3 verification: remaining slots filled by most recent conversations
        assertTrue(candidates.contains("colleague-4"))
        assertTrue(candidates.contains("colleague-5"))
        assertTrue(candidates.contains("colleague-6"))
        assertTrue(candidates.contains("colleague-7"))
        assertTrue(candidates.contains("colleague-8"))

        // Older conversations (colleague-9..15) must be truncated by the cap
        assertFalse(candidates.contains("colleague-9"), "Peers beyond cap of 8 must be excluded")
        assertFalse(candidates.contains("colleague-15"), "Peers beyond cap of 8 must be excluded")
    }

    @Test
    fun ORGT_U25_reactionGate_acceptsOrgColleagueAndRejectsStranger() = runTest {
        val reactionDao = FakeReactionDao()
        val msgDao = FakeMessageDao()
        val convDao = FakeConversationDao()
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            msgDao,
            convDao,
            FakeOutboxDao(),
            reactionDao,
            isTrustedPeer = { it == "some-paired-device" }, // Alice is NOT paired
            orgPeerResolver = resolver,
        )

        // Setup message
        msgDao.insert(MessageEntity("msg-1", colleagueAlice, colleagueAlice, "Alice", "hi", 100L, "DELIVERED"))

        // 1. Inbound reaction from authenticated ORG colleague Alice -> ACCEPTED
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "👍",
                memberId = colleagueAlice,
                isAdded = true,
            ),
            transportPeerId = colleagueAlice,
        )
        assertNotNull(reactionDao.get("msg-1", "👍"), "Reaction from verified ORG colleague must be accepted")

        // 2. Inbound reaction from stranger Eve (transportPeerId = strangerEve) -> REJECTED & DROPPED
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "❤️",
                memberId = strangerEve,
                isAdded = true,
            ),
            transportPeerId = strangerEve,
        )
        assertNull(reactionDao.get("msg-1", "❤️"), "Reaction from stranger transport must be rejected")

        // 3. Hostile spoofing: Stranger Eve connected over TLS claiming to be Alice in memberId -> REJECTED
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "🔥",
                memberId = colleagueAlice, // Spoofed frame memberId
                isAdded = true,
            ),
            transportPeerId = strangerEve, // Authentic TLS transport peer is stranger
        )
        assertNull(reactionDao.get("msg-1", "🔥"), "Hostile spoofed reaction from stranger transport must be rejected")

        // 4. Missing transportPeerId with stranger memberId -> REJECTED
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "🎉",
                memberId = strangerEve,
                isAdded = true,
            ),
            transportPeerId = null,
        )
        assertNull(reactionDao.get("msg-1", "🎉"), "Reaction with stranger memberId must be rejected")
    }

    @Test
    fun ORGT_U25_groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague() = runTest {
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false }, // Not paired
            orgPeerResolver = resolver,
        )

        // Creating group with unpaired colleague Alice must be rejected
        val createResult = repo.createGroup("Engineering", setOf(colleagueAlice))
        assertTrue(createResult is FlashResult.Failure)
        assertEquals("Every group member must be trusted", (createResult as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U26_defaultIsOrgPeerFalse_preservesExistingBehaviour() = runTest {
        // Default constructor without orgPeerResolver
        val reactionDao = FakeReactionDao()
        val msgDao = FakeMessageDao()
        val repo = createRepository(
            MutableTimeSource(),
            msgDao,
            FakeConversationDao(),
            FakeOutboxDao(),
            reactionDao,
            isTrustedPeer = { false },
        )

        // 1. Conversation start fails with connect once
        val result = repo.startColleagueConversation(colleagueAlice)
        assertTrue(result is FlashResult.Failure)
        assertEquals("Connect to the company network once to load your colleagues.", (result as FlashResult.Failure).errorText())

        // 2. Inbound reaction from unpaired colleague is dropped under default isOrgPeer = false
        msgDao.insert(MessageEntity("msg-1", colleagueAlice, colleagueAlice, "Alice", "hi", 100L, "DELIVERED"))
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "👍",
                memberId = colleagueAlice,
                isAdded = true,
            ),
            transportPeerId = colleagueAlice,
        )
        assertNull(reactionDao.get("msg-1", "👍"), "Default repository must drop reactions when isOrgPeer defaults to false")
    }

    @Test
    fun ORGT_U30_revokedColleague_staysChatableOfflineUntilSync_refusedAfterSync() = runTest {
        val reactionDao = FakeReactionDao()
        val msgDao = FakeMessageDao()
        val convDao = FakeConversationDao()
        val members = mutableMapOf(
            colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
        )
        val resolver = FakeOrgPeerResolver(
            members = members,
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            msgDao,
            convDao,
            FakeOutboxDao(),
            reactionDao,
            isTrustedPeer = { false },
            orgPeerResolver = resolver,
        )

        convDao.upsert(ConversationEntity(id = colleagueAlice, title = "Alice", isGroup = false, sortOrder = 100L))
        msgDao.insert(MessageEntity("msg-1", colleagueAlice, colleagueAlice, "Alice", "hi", 100L, "DELIVERED"))

        // Phase 1: Colleague Alice is active in offline snapshot -> chat and reactions work
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "👍",
                memberId = colleagueAlice,
                isAdded = true,
            ),
            transportPeerId = colleagueAlice,
        )
        assertNotNull(reactionDao.get("msg-1", "👍"), "Alice must be accepted before revocation")

        // Phase 2: Alice is revoked on server, but local device is offline and has not synced yet.
        // Documented Plan B behavior (§3.1): Revoked colleague stays chatable offline until sync.
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "🚀",
                memberId = colleagueAlice,
                isAdded = true,
            ),
            transportPeerId = colleagueAlice,
        )
        assertNotNull(reactionDao.get("msg-1", "🚀"), "Alice remains chat-able offline before local directory sync")

        // Phase 3: Phone connects to server and completes directory sync; Alice is removed (tombstoned).
        members.remove(colleagueAlice)

        // After sync: Alice is no longer in directory
        assertFalse(resolver.isKnownColleague(colleagueAlice), "Alice must no longer be in directory after sync")
        assertNull(resolver.resolve(colleagueAlice), "Alice must not resolve after sync")

        // Phase 4: Inbound frames from revoked colleague are now strictly refused
        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = colleagueAlice,
                messageId = "msg-1",
                emoji = "⛔",
                memberId = colleagueAlice,
                isAdded = true,
            ),
            transportPeerId = colleagueAlice,
        )
        assertNull(reactionDao.get("msg-1", "⛔"), "Reactions from revoked colleague must be refused after sync")

        // Phase 5: Starting conversation with revoked colleague fails with exact sentence
        val startResult = repo.startColleagueConversation(colleagueAlice)
        assertTrue(startResult is FlashResult.Failure)
        assertEquals(
            "This person is no longer part of your organisation.",
            (startResult as FlashResult.Failure).errorText(),
        )
    }

    @Test
    fun ORGT_MUTATION_4_defaultIsOrgPeer_failsIfDefaultIsTrue() = runTest {
        // Mutation check (4): isOrgPeer default must stay false (must fail if default is true)
        // If constructor default were mutated to `isOrgPeer: (String) -> Boolean = { true }`,
        // any stranger would bypass gates and become an ORG colleague!
        val reactionDao = FakeReactionDao()
        val msgDao = FakeMessageDao()
        val repo = createRepository(
            MutableTimeSource(),
            msgDao,
            FakeConversationDao(),
            FakeOutboxDao(),
            reactionDao,
            isTrustedPeer = { false }, // Stranger
            // isOrgPeer parameter omitted: relies strictly on default value
        )

        msgDao.insert(MessageEntity("msg-1", strangerEve, strangerEve, "Eve", "hi", 100L, "DELIVERED"))

        repo.onInboundWireFrame(
            MessageWireFrame.ReactionFrame(
                conversationId = strangerEve,
                messageId = "msg-1",
                emoji = "💥",
                memberId = strangerEve,
                isAdded = true,
            ),
            transportPeerId = strangerEve,
        )

        assertNull(
            reactionDao.get("msg-1", "💥"),
            "MUTATION CHECK 4: Reaction must be dropped under default isOrgPeer; fails if default is mutated to true",
        )
    }

    @Test
    fun ORGT_U38_conversationHeader_populates_isColleague_for_colleague_and_suppresses_for_paired() = runTest {
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )

        // 1. Unpaired colleague: isColleague = true
        val repoColleague = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false },
            orgPeerResolver = resolver,
        )
        repoColleague.openConversation(colleagueAlice)
        assertTrue(repoColleague.conversationState.value.header.isColleague, "Unpaired colleague must have isColleague=true")

        // 2. Paired colleague: isColleague = false (paired outranks colleague)
        val repoPaired = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { it == colleagueAlice },
            orgPeerResolver = resolver,
        )
        repoPaired.openConversation(colleagueAlice)
        assertFalse(repoPaired.conversationState.value.header.isColleague, "Paired status must suppress isColleague badge")

        // 3. Stranger: isColleague = false
        repoColleague.openConversation(strangerEve)
        assertFalse(repoColleague.conversationState.value.header.isColleague, "Stranger must have isColleague=false")
    }

    @Test
    fun `ORGT_U44_inboundColleagueMessageNotificationOmitsColleagueTagForLockScreenPrivacy`() = runTest {
        val colleagueAlice = "colleague-alice-id"
        var notifiedSenderName: String? = null
        var notifiedText: String? = null
        var notifiedGroupTitle: String? = null

        val resolver = FakeOrgPeerResolver(
            members = mapOf(colleagueAlice to OrgPeer(colleagueAlice, "Alice Engineer", "Engineering", "Developer", "fp-alice", true)),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )

        val repo = RealFlashChatRepository(
            localDeviceId = "me",
            localDisplayName = "Me",
            messageDao = FakeMessageDao(),
            conversationDao = FakeConversationDao(),
            outboxDao = FakeOutboxDao(),
            receiptDao = FakeReceiptDao(),
            draftDao = FakeDraftDao(),
            recentSearchDao = FakeRecentSearchDao(),
            reactionDao = FakeReactionDao(),
            isTrustedPeer = { false },
            isOrgPeer = { it == colleagueAlice },
            orgPeerResolver = resolver,
            onInboundTextMessageWithGroupTitle = { _, senderName, text, groupTitle ->
                notifiedSenderName = senderName
                notifiedText = text
                notifiedGroupTitle = groupTitle
            },
        )

        // Inbound message from colleague Alice
        repo.onInboundWireFrame(
            MessageWireFrame.TextMessage(
                localId = "msg-alice-1",
                conversationId = colleagueAlice,
                senderId = colleagueAlice,
                senderName = "Alice Engineer",
                text = "Secret corporate plans",
                sentAt = 1000L,
            ),
            transportPeerId = colleagueAlice,
        )

        assertEquals("Alice Engineer", notifiedSenderName)
        assertEquals("Secret corporate plans", notifiedText)
        assertNull(notifiedGroupTitle)
        assertFalse(
            notifiedSenderName?.contains("Colleague", ignoreCase = true) == true,
            "Notification title must never leak '(Colleague)' on lock screens visible to bystanders",
        )
        assertFalse(
            notifiedText?.contains("Colleague", ignoreCase = true) == true,
            "Notification body must not contain colleague tag",
        )
    }

    @Test
    fun `ORGT_U41_expiredLeaseSuppressesColleagueChipInChatListAndHeader`() = runTest {
        val colleagueAlice = "colleague-alice-id"
        val expiredResolver = FakeOrgPeerResolver(
            members = mapOf(colleagueAlice to OrgPeer(colleagueAlice, "Alice", "Eng", "Dev", "fp-alice", true)),
            leaseState = OrgLease.Expired(sinceMs = 1000L, reason = ExpiredReason.TIME),
        )

        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false },
            orgPeerResolver = expiredResolver,
        )

        // Expired lease must evaluate isKnownColleague to false
        assertFalse(expiredResolver.isKnownColleague(colleagueAlice), "isKnownColleague must return false when lease is expired")

        repo.openConversation(colleagueAlice)
        assertFalse(
            repo.conversationState.value.header.isColleague,
            "Expired lease must suppress isColleague badge in conversation header",
        )
    }

    @Test
    fun `ORGT_U40_searchColleaguesQueriesResolver`() = runTest {
        val colleagueAlice = "colleague-alice-id"
        val validResolver = FakeOrgPeerResolver(
            members = mapOf(colleagueAlice to OrgPeer(colleagueAlice, "Alice Executive", "Finance", "CFO", "fp-alice", true)),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )

        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false },
            orgPeerResolver = validResolver,
        )

        // Matching query
        val matches = repo.searchColleagues("Alice")
        assertTrue(matches.contains(colleagueAlice), "searchColleagues must return colleague device ID matching name")

        // Unmatched query
        val noMatches = repo.searchColleagues("Zack")
        assertTrue(noMatches.isEmpty(), "searchColleagues must return empty for unmatched query")

        // Expired lease resolver
        val expiredResolver = FakeOrgPeerResolver(
            members = mapOf(colleagueAlice to OrgPeer(colleagueAlice, "Alice Executive", "Finance", "CFO", "fp-alice", true)),
            leaseState = OrgLease.Expired(sinceMs = 1000L, reason = ExpiredReason.TIME),
        )
        val expiredRepo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false },
            orgPeerResolver = expiredResolver,
        )
        assertTrue(expiredRepo.searchColleagues("Alice").isEmpty(), "searchColleagues must return empty when lease is expired")
    }

    @Test
    fun ORGT_U27_startColleagueConversation_pairedPeerWithExistingConversation_notInDirectory_saysNotMemberNotRemoved() = runTest {
        val convDao = FakeConversationDao()
        val pairedFriend = "paired-charlie"
        // Paired friend has an existing direct conversation
        convDao.upsert(ConversationEntity(id = pairedFriend, title = "Charlie", isGroup = false, sortOrder = 100L))

        // Directory snapshot has only Alice
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            convDao,
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { it == pairedFriend },
            orgPeerResolver = resolver,
        )
        val result = repo.startColleagueConversation(pairedFriend)
        assertTrue(result is FlashResult.Failure)
        // Defect 15: Must return "This person is not in your company directory.", NOT "This person is no longer part of your organisation."
        assertEquals("This person is not in your company directory.", (result as FlashResult.Failure).errorText())
    }

    @Test
    fun ORGT_U29_orgDialCandidates_notStarvedByUnrelatedOutboxEntries() = runTest {
        val outboxDao = FakeOutboxDao()
        val msgDao = FakeMessageDao()
        val resolver = FakeOrgPeerResolver(
            members = mapOf(
                colleagueAlice to OrgPeer(colleagueAlice, "Alice", "eng", "dev", "FP_ALICE", true),
            ),
            leaseState = OrgLease.Valid(untilMs = 9_999_999L),
        )
        val repo = createRepository(
            MutableTimeSource(),
            msgDao,
            FakeConversationDao(),
            outboxDao,
            FakeReactionDao(),
            orgPeerResolver = resolver,
        )

        // Populate outbox with 25 messages for non-colleague / groups
        for (i in 1..25) {
            val localId = "msg-unrelated-$i"
            val target = if (i % 2 == 0) "v2:group-$i" else "stranger-$i"
            msgDao.insert(MessageEntity(localId, target, localDeviceId, "Me", "hi", i * 1000L, "QUEUED"))
            outboxDao.enqueue(OutboxEntity(localId = localId, attempts = 0, nextAttemptAt = 0L, payloadJson = "{}", createdAt = i * 1000L))
        }

        // Message 26 is for colleagueAlice
        val aliceMsgId = "msg-alice-outbox"
        msgDao.insert(MessageEntity(aliceMsgId, colleagueAlice, localDeviceId, "Me", "Colleague update", 26_000L, "QUEUED"))
        outboxDao.enqueue(OutboxEntity(localId = aliceMsgId, attempts = 0, nextAttemptAt = 0L, payloadJson = "{}", createdAt = 26_000L))

        // Call orgDialCandidates with default limit = 8
        val candidates = repo.orgDialCandidates(nowMs = 30_000L, limit = 8)

        // Verifier defect 15: scan of maxOf(limit * 20, 200) prevents preceding non-colleague rows from starving Alice
        assertTrue(candidates.contains(colleagueAlice), "orgDialCandidates must find Alice even when preceding outbox entries are for non-colleagues")
    }

    @Test
    fun ORGT_CALLSITE_1975_getPendingJoinRequests_distinguishes_paired_peer_from_stranger() = runTest {
        val joinDao = FakeGroupJoinRequestDao()
        val pairedFriend = "paired-charlie"
        val stranger = "stranger-dave"
        val groupId = "v2:group-alpha"

        joinDao.upsert(
            GroupJoinRequestEntity(
                groupId = groupId,
                subjectId = pairedFriend,
                subjectKey = "KEY_CHARLIE",
                label = "Charlie Requested Name",
                requestSig = "SIG_1",
                viaPeerId = null,
                requestedAtMs = 1000L,
                state = "PENDING",
                decidedBy = null,
                decidedAtMs = null,
            ),
        )
        joinDao.upsert(
            GroupJoinRequestEntity(
                groupId = groupId,
                subjectId = stranger,
                subjectKey = "KEY_DAVE",
                label = "Dave Requested Name",
                requestSig = "SIG_2",
                viaPeerId = null,
                requestedAtMs = 2000L,
                state = "PENDING",
                decidedBy = null,
                decidedAtMs = null,
            ),
        )

        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            groupJoinRequestDao = joinDao,
            isTrustedPeer = { it == pairedFriend },
            peerNameResolver = { id -> if (id == pairedFriend) "Charlie Trusted Friend" else null },
        )

        val requests = repo.getPendingJoinRequests(groupId)
        assertEquals(2, requests.size)

        val pairedReq = requests.first { it.subjectId == pairedFriend }
        assertTrue(pairedReq.isKnownDevice, "Paired friend must be recognized as known device (line 1975)")
        assertEquals("Charlie Trusted Friend", pairedReq.label, "Paired friend must use friendly name from peerNameResolver")

        val strangerReq = requests.first { it.subjectId == stranger }
        assertFalse(strangerReq.isKnownDevice, "Stranger must NOT be recognized as known device (mutation S1 check)")
        assertEquals("Dave Requested Name", strangerReq.label, "Stranger must keep requested label, not peerNameResolver")
    }

    @Test
    fun ORGT_CALLSITE_5348_isGroupCallMember_returnsFalseForActiveMemberNeitherPairedNorVouched() = runTest {
        val memberDao = FakeGroupMemberDao()
        val groupId = "v2:group-beta"
        val stranger = "stranger-untrusted"
        val pairedFriend = "paired-trusted"

        // Both are active members in the group
        memberDao.upsert(
            GroupMemberEntity(
                groupId = groupId,
                deviceId = stranger,
                displayName = "Stranger Dave",
                joinedAt = 1000L,
                membershipVersion = 1L,
                operationId = "op-1",
                isActive = true,
            ),
        )
        memberDao.upsert(
            GroupMemberEntity(
                groupId = groupId,
                deviceId = pairedFriend,
                displayName = "Paired Bob",
                joinedAt = 1000L,
                membershipVersion = 1L,
                operationId = "op-2",
                isActive = true,
            ),
        )

        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            groupMemberDao = memberDao,
            isTrustedPeer = { it == pairedFriend },
        )

        // Paired active member is accepted
        assertTrue(repo.isGroupCallMember(groupId, pairedFriend), "Paired active group member must be accepted for group calls")

        // Active member who is neither paired nor vouched MUST return false (mutation S3 check: return true || survived)
        assertFalse(
            repo.isGroupCallMember(groupId, stranger),
            "Active group member who is neither paired nor vouched must NOT be a group call member (line 5348)",
        )
    }

    @Test
    fun ORGT_CALLSITE_3558_inboundGroupFrame_fromUnpairedStranger_dropsUnusableSwarmOffer() = runTest {
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false },
            isOrgPeer = { false },
        )

        // Frame from stranger where swarm == 1, but missing required pieceSize / rootSig
        val frame = GroupWireFrame.GroupMedia(
            groupId = "v2:group-swarm",
            messageId = "swarm-msg-1",
            transferId = "transfer-1",
            wireFileId = "wire-1",
            from = strangerEve,
            senderName = "Eve",
            fileName = "payload.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 1024L,
            sentAt = 1000L,
            swarm = 1,
            root = "ROOT_HASH",
            pieceSize = null,
            rootSig = null,
        )

        // Should return early at line 3558 because PeerTrustPolicy.mayServeSwarm is false for unpaired/non-org stranger
        repo.onInboundGroupWireFrame(strangerEve, frame)
        assertTrue(repo.chatListState.value.items.none { it.id == "v2:group-swarm" })
    }

    // ---- Phase B4: hub groups on the LAN (plan section 10; ORGT-U42..U50) ----

    private class FakeRosterView(var roster: com.transfer.flash.core.enterprise.HubGroupRoster?) : com.transfer.flash.core.enterprise.HubGroupRosterView {
        var adoptResult = com.transfer.flash.core.enterprise.RosterAdoption.INVALID
        var adoptCalls = 0
        var onAdopt: (() -> Unit)? = null

        // Deliberately ignores the validity window: the repository's own gate must refuse an expired roster.
        override fun current(groupId: String, nowMs: Long) = roster?.takeIf { it.groupId == groupId }
        override fun isHubGroup(groupId: String) = roster?.groupId == groupId
        override fun offerFor(groupId: String, nowMs: Long) =
            roster?.takeIf { it.groupId == groupId }?.let { com.transfer.flash.core.enterprise.HubRosterOfferPayload(groupId, "{st}", "[]") }
        override fun groupIds(): Set<String> = setOfNotNull(roster?.groupId)
        override suspend fun refresh() {}
        override suspend fun clear() { roster = null }
        override suspend fun adopt(offer: com.transfer.flash.core.enterprise.HubRosterOfferPayload, offeredBy: String?): com.transfer.flash.core.enterprise.RosterAdoption {
            adoptCalls++
            if (adoptResult == com.transfer.flash.core.enterprise.RosterAdoption.ADOPTED) onAdopt?.invoke()
            return adoptResult
        }
    }

    private val hubGroup = "hub-grp-1"

    private fun roster(version: Long, members: Set<String>, validUntil: Long = 5_000_000L, admins: Set<String> = emptySet()) =
        com.transfer.flash.core.enterprise.HubGroupRoster(hubGroup, "Platform", version, members, admins, validUntil)

    private class B4Rig(
        val repo: RealFlashChatRepository,
        val members: FakeGroupMemberDao,
        val view: FakeRosterView,
        val trust: com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore,
        val sent: MutableList<Pair<String, GroupWireFrame>>,
        val time: MutableTimeSource,
        val conversations: FakeConversationDao,
        val messages: FakeMessageDao,
    )

    private suspend fun b4Rig(
        roster: com.transfer.flash.core.enterprise.HubGroupRoster? = roster(1L, setOf(localDeviceId, colleagueAlice, colleagueBob)),
        orgPeers: Set<String> = setOf(colleagueAlice, colleagueBob, strangerEve),
        linked: Boolean = true,
        features: Set<String> = setOf("hrs1"),
        paired: Set<String> = emptySet(),
        withConversation: Boolean = true,
    ): B4Rig {
        val trust = com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore()
        if (linked) trust.linkGroupToHub(hubGroup)
        val convDao = FakeConversationDao()
        if (withConversation) convDao.upsert(ConversationEntity(id = hubGroup, title = "Platform", isGroup = true, sortOrder = 1L))
        val messageDao = FakeMessageDao()
        val members = FakeGroupMemberDao()
        val view = FakeRosterView(roster)
        val sent = mutableListOf<Pair<String, GroupWireFrame>>()
        val time = MutableTimeSource(1_000_000L)
        val repo = createRepository(
            time, messageDao, convDao, FakeOutboxDao(), FakeReactionDao(),
            groupMemberDao = members,
            isTrustedPeer = { it in paired },
            isOrgPeer = { it in orgPeers },
            enterpriseTrustStore = trust,
            hubGroupRosters = view,
            groupTransportSink = GroupTransportSink { to, f -> sent += to to f; true },
            peerFeatures = { features },
        )
        return B4Rig(repo, members, view, trust, sent, time, convDao, messageDao)
    }

    @Test
    fun ORGT_B4_gate_acceptsOnlyOrgColleaguesNamedByCurrentRosterWhileLocalIsAlsoNamed() = runTest {
        val rig = b4Rig()
        assertTrue(rig.repo.isGroupPeerTrusted(hubGroup, colleagueAlice), "ORG colleague in the roster")
        assertFalse(rig.repo.isGroupPeerTrusted(hubGroup, strangerEve), "ORG colleague NOT in the roster")
        assertFalse(rig.repo.isGroupPeerTrusted(hubGroup, "paired-not-org-not-in-roster"), "not a colleague")
        rig.view.roster = roster(1L, setOf(colleagueAlice, colleagueBob))
        assertFalse(rig.repo.isGroupPeerTrusted(hubGroup, colleagueAlice), "this device is not in the roster")
        rig.view.roster = roster(1L, setOf(localDeviceId, colleagueAlice))
        rig.time.timeMs = 5_000_001L
        assertFalse(rig.repo.isGroupPeerTrusted(hubGroup, colleagueAlice), "the statement window is closed")
        rig.time.timeMs = 1_000_000L
        assertTrue(rig.repo.isGroupPeerTrusted(hubGroup, colleagueAlice))
        rig.trust.unlinkGroupFromHub(hubGroup)
        assertFalse(rig.repo.isGroupPeerTrusted(hubGroup, colleagueAlice), "a group not linked to the hub uses no roster")
    }

    @Test
    fun ORGT_B4_gate_neverGrantsAnOrgOnlyMemberPairedPowers() = runTest {
        val rig = b4Rig()
        assertFalse(PeerTrustPolicy.mayPushToTalk(isPaired = false, isOrg = true))
        assertFalse(PeerTrustPolicy.mayCreateGroupWith(isPaired = false, isOrg = true))
        assertFalse(PeerTrustPolicy.mayAdministerGroup(isPaired = false, isOrg = true))
        val add = rig.repo.addGroupMembers(hubGroup, setOf(colleagueAlice))
        assertTrue(add is FlashResult.Failure)
        assertEquals("Members of a company group are set by your administrator.", add.errorText())
        assertTrue(rig.repo.isHubRosterGroup(hubGroup))
        assertFalse(rig.repo.isHubRosterGroup("g-local"))
    }

    @Test
    fun ORGT_B4_rosterRowsAreFilledFromTheStatementAndRemovedMembersAreRefused() = runTest {
        val rig = b4Rig(roster = roster(1L, setOf(localDeviceId, colleagueAlice, colleagueBob), admins = setOf(colleagueAlice)))
        rig.repo.refreshHubGroupRosters()
        val active = rig.members.activeMembers(hubGroup).map { it.deviceId }.toSet()
        assertEquals(setOf(localDeviceId, colleagueAlice, colleagueBob), active)
        assertEquals("admin", rig.members.member(hubGroup, colleagueAlice)?.role)
        assertTrue(rig.repo.isGroupCallPeer(hubGroup, colleagueBob), "gate and rows both pass")

        // Version 2 drops Bob: the next projection tombstones his row, he is refused, nobody else changes.
        rig.view.roster = roster(2L, setOf(localDeviceId, colleagueAlice))
        rig.repo.refreshHubGroupRosters()
        assertEquals(false, rig.members.member(hubGroup, colleagueBob)?.isActive)
        assertFalse(rig.repo.isGroupCallPeer(hubGroup, colleagueBob), "a removed member is refused")
        assertTrue(rig.repo.isGroupCallPeer(hubGroup, colleagueAlice))

        // Removing THIS device makes it a removed member here (the composer is replaced by the notice).
        rig.view.roster = roster(3L, setOf(colleagueAlice))
        rig.repo.refreshHubGroupRosters()
        assertEquals(false, rig.members.member(hubGroup, localDeviceId)?.isActive)
        assertFalse(rig.repo.isGroupCallPeer(hubGroup, colleagueAlice))
    }

    @Test
    fun ORGT_B4_unlinkAndOrganisationLeaveClearTheRosterRows() = runTest {
        val rig = b4Rig()
        rig.repo.refreshHubGroupRosters()
        assertEquals(3, rig.members.activeMembers(hubGroup).size)
        rig.repo.unlinkGroupFromHub(hubGroup)
        assertEquals(0, rig.members.activeMembers(hubGroup).size, "unlinking clears the rows the roster produced")

        val rig2 = b4Rig()
        rig2.repo.refreshHubGroupRosters()
        rig2.repo.clearHubGroupRosters()
        assertEquals(0, rig2.members.activeMembers(hubGroup).size, "leaving the organisation clears them too")
        assertNull(rig2.view.roster)
    }

    @Test
    fun ORGT_B4_aRosterOfferFromAMemberColleagueIsAdoptedAndAnythingElseIsIgnored() = runTest {
        val rig = b4Rig(roster = null)
        val offer = { from: String -> GroupWireFrame.HubRosterOffer(hubGroup, from, "{st}", "[]") }

        // The statement is not verified by the repository: the view says INVALID (tampered) -> nothing changes.
        rig.view.adoptResult = com.transfer.flash.core.enterprise.RosterAdoption.INVALID
        rig.repo.onInboundGroupWireFrame(colleagueAlice, offer(colleagueAlice))
        assertEquals(1, rig.view.adoptCalls)
        assertEquals(0, rig.members.activeMembers(hubGroup).size)

        // A forged `from` and a non-colleague never reach the verifier.
        rig.repo.onInboundGroupWireFrame(colleagueBob, offer(colleagueAlice))
        rig.repo.onInboundGroupWireFrame("not-a-colleague", offer("not-a-colleague"))
        assertEquals(1, rig.view.adoptCalls)

        // One offer per peer and group per minute: the repeat inside the window is not even looked at.
        rig.repo.onInboundGroupWireFrame(colleagueAlice, offer(colleagueAlice))
        assertEquals(1, rig.view.adoptCalls)
        rig.time.timeMs += 61_000L

        // Verified and newer -> adopted, rows projected, and the other colleagues that speak hrs1 are told.
        rig.view.adoptResult = com.transfer.flash.core.enterprise.RosterAdoption.ADOPTED
        rig.view.onAdopt = { rig.view.roster = roster(4L, setOf(localDeviceId, colleagueAlice, colleagueBob), validUntil = 99_000_000L) }
        rig.repo.onInboundGroupWireFrame(colleagueAlice, offer(colleagueAlice))
        assertEquals(2, rig.view.adoptCalls)
        assertEquals(3, rig.members.activeMembers(hubGroup).size)
        assertEquals(listOf(colleagueBob), rig.sent.filter { it.second is GroupWireFrame.HubRosterOffer }.map { it.first })
    }

    @Test
    fun ORGT_B4_sessionUpOffersTheRosterOnlyToHrs1ColleaguesInTheGroup() = runTest {
        val withFeature = b4Rig(features = setOf("hrs1"))
        withFeature.repo.notifyPeerSessionUp(colleagueAlice)
        assertEquals(listOf(colleagueAlice), withFeature.sent.filter { it.second is GroupWireFrame.HubRosterOffer }.map { it.first })
        withFeature.repo.notifyPeerSessionUp(strangerEve)
        assertEquals(1, withFeature.sent.count { it.second is GroupWireFrame.HubRosterOffer }, "Eve is a colleague but not in the group")

        val noFeature = b4Rig(features = emptySet())
        noFeature.repo.notifyPeerSessionUp(colleagueAlice)
        assertEquals(0, noFeature.sent.count { it.second is GroupWireFrame.HubRosterOffer }, "a peer without hrs1 gets no frame")
    }

    private fun pairRepo(
        paired: MutableList<Pair<String, String>>,
        orgLive: Boolean = true,
        alreadyTrusted: Boolean = false,
        saveOk: Boolean = true,
        withPairer: Boolean = true,
    ) = createRepository(
        MutableTimeSource(),
        FakeMessageDao(),
        FakeConversationDao(),
        FakeOutboxDao(),
        FakeReactionDao(),
        isTrustedPeer = { alreadyTrusted },
        isOrgPeer = { orgLive },
        pairVerifiedColleague = if (withPairer) { id, name -> paired.add(id to name); saveOk } else null,
    )

    @Test
    fun ORGPAIR_U01_pairsALiveVerifiedColleagueOnce() = runTest {
        val paired = mutableListOf<Pair<String, String>>()
        val result = pairRepo(paired).pairColleagueFromDirectory(colleagueAlice)
        assertTrue(result is FlashResult.Success)
        assertEquals(1, paired.size)
        assertEquals(colleagueAlice, paired[0].first)
    }

    @Test
    fun ORGPAIR_U02_refusesAColleagueWhoseKeyIsNotVerifiedNow() = runTest {
        val paired = mutableListOf<Pair<String, String>>()
        val result = pairRepo(paired, orgLive = false).pairColleagueFromDirectory(colleagueAlice)
        assertTrue(result is FlashResult.Failure)
        assertTrue(paired.isEmpty(), "nothing may be trusted without a live key binding")
    }

    @Test
    fun ORGPAIR_U03_alreadyPairedIsASuccessWithoutRewriting() = runTest {
        val paired = mutableListOf<Pair<String, String>>()
        val result = pairRepo(paired, alreadyTrusted = true, orgLive = false).pairColleagueFromDirectory(colleagueAlice)
        assertTrue(result is FlashResult.Success)
        assertTrue(paired.isEmpty())
    }

    @Test
    fun ORGPAIR_U04_selfBlankAndMissingPairerFail() = runTest {
        val paired = mutableListOf<Pair<String, String>>()
        val repo = pairRepo(paired)
        assertEquals("That is you.", (repo.pairColleagueFromDirectory(localDeviceId) as FlashResult.Failure).errorText())
        assertTrue(repo.pairColleagueFromDirectory(" ") is FlashResult.Failure)
        assertTrue(pairRepo(paired, withPairer = false).pairColleagueFromDirectory(colleagueAlice) is FlashResult.Failure)
        assertTrue(pairRepo(paired, saveOk = false).pairColleagueFromDirectory(colleagueAlice) is FlashResult.Failure)
    }

    // ---- ADR-174: a colleague chat becomes a pairing once both sides have written ----

    private class AutoPairRig(
        val repo: RealFlashChatRepository,
        val paired: MutableList<Pair<String, String>>,
        val time: MutableTimeSource,
    )

    private fun autoPairRig(orgLive: Boolean = true, alreadyTrusted: Boolean = false, saveOk: Boolean = true): AutoPairRig {
        val paired = mutableListOf<Pair<String, String>>()
        val time = MutableTimeSource()
        val repo = createRepository(
            time,
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { alreadyTrusted },
            isOrgPeer = { orgLive },
            pairVerifiedColleague = { id, name -> paired.add(id to name); saveOk },
        )
        return AutoPairRig(repo, paired, time)
    }

    private suspend fun AutoPairRig.aliceWrites(localId: String = "alice-1") = repo.onInboundWireFrame(
        MessageWireFrame.TextMessage(
            localId = localId,
            conversationId = localDeviceId,
            senderId = colleagueAlice,
            senderName = "Alice",
            text = "hello",
            sentAt = time.timeMs,
        ),
        transportPeerId = colleagueAlice,
    )

    @Test
    fun ORGPAIR_U05_weStartTheyReplyAndBothSidesPair() = runTest {
        val rig = autoPairRig()
        rig.repo.sendTextTo(colleagueAlice, "hi Alice")
        assertTrue(rig.paired.isEmpty(), "our own message alone pairs nobody")
        rig.aliceWrites()
        assertEquals(listOf(colleagueAlice), rig.paired.map { it.first }, "the reply completes the exchange: pair once")
    }

    @Test
    fun ORGPAIR_U06_theyStartWeReplyAndPairWhenWeSend() = runTest {
        val rig = autoPairRig()
        rig.aliceWrites()
        assertTrue(rig.paired.isEmpty(), "a message from them alone pairs nobody")
        rig.repo.sendTextTo(colleagueAlice, "hello back")
        assertEquals(listOf(colleagueAlice), rig.paired.map { it.first })
    }

    @Test
    fun ORGPAIR_U07_oneWayTrafficNeverPairs() = runTest {
        val outOnly = autoPairRig()
        repeat(3) { outOnly.repo.sendTextTo(colleagueAlice, "message $it") }
        assertTrue(outOnly.paired.isEmpty())
        val inOnly = autoPairRig()
        inOnly.aliceWrites("a1")
        inOnly.aliceWrites("a2")
        assertTrue(inOnly.paired.isEmpty())
    }

    @Test
    fun ORGPAIR_U08_aColleagueWhoseKeyIsNotVerifiedNowIsNeverPaired() = runTest {
        val rig = autoPairRig(orgLive = false)
        rig.repo.sendTextTo(colleagueAlice, "hi")
        rig.aliceWrites()
        rig.repo.sendTextTo(colleagueAlice, "again")
        assertTrue(rig.paired.isEmpty(), "no live key binding, no trust")
    }

    @Test
    fun ORGPAIR_U09_anAlreadyPairedContactIsLeftAlone() = runTest {
        val rig = autoPairRig(alreadyTrusted = true)
        rig.repo.sendTextTo(colleagueAlice, "hi")
        rig.aliceWrites()
        assertTrue(rig.paired.isEmpty())
    }

    @Test
    fun ORGPAIR_U10_aFailedAttemptIsRetriedOnlyAfterTheWaitingTime() = runTest {
        val rig = autoPairRig(saveOk = false)
        rig.repo.sendTextTo(colleagueAlice, "hi")
        rig.aliceWrites("a1")
        assertEquals(1, rig.paired.size, "one attempt")
        rig.aliceWrites("a2")
        rig.repo.sendTextTo(colleagueAlice, "still there?")
        assertEquals(1, rig.paired.size, "no second attempt inside the waiting time")
        rig.time.timeMs += 61_000L
        rig.aliceWrites("a3")
        assertEquals(2, rig.paired.size, "after the waiting time it tries again")
    }

    @Test
    fun ORGT_B4_aHubGroupTheAdminAddedUsToGetsAChatAndALinkOnTheNextRosterRefresh() = runTest {
        val rig = b4Rig(linked = false, withConversation = false)
        assertEquals(null, rig.conversations.get(hubGroup))
        rig.repo.refreshHubGroupRosters()
        val conversation = rig.conversations.get(hubGroup)
        assertTrue(conversation?.isGroup == true)
        assertEquals("Platform", conversation?.title)
        assertTrue(rig.trust.isGroupLinkedToHub(hubGroup))
        assertEquals(setOf(localDeviceId, colleagueAlice, colleagueBob), rig.members.activeMembers(hubGroup).map { it.deviceId }.toSet())
    }

    @Test
    fun ORGT_B4_noChatIsCreatedWhenThisDeviceIsNotNamedByTheRoster() = runTest {
        val rig = b4Rig(roster = roster(1L, setOf(colleagueAlice, colleagueBob)), linked = false, withConversation = false)
        rig.repo.refreshHubGroupRosters()
        assertEquals(null, rig.conversations.get(hubGroup))
        assertFalse(rig.trust.isGroupLinkedToHub(hubGroup))
    }

    @Test
    fun ORGT_B4_aGroupMessageIsStoredOnlyFromAMemberOfTheCurrentRoster() = runTest {
        val rig = b4Rig()
        rig.repo.refreshHubGroupRosters()
        fun msg(id: String, from: String) = GroupWireFrame.Message(hubGroup, id, from, "Sender", 2_000L, "hello $id")
        rig.repo.onInboundGroupWireFrame(colleagueAlice, msg("m-alice", colleagueAlice))
        rig.repo.onInboundGroupWireFrame(strangerEve, msg("m-eve", strangerEve))
        assertEquals(setOf("m-alice"), rig.messages.messages.values.map { it.localId }.toSet(), "only the roster member is stored")

        // Bob is removed in version 2: his next message is refused.
        rig.view.roster = roster(2L, setOf(localDeviceId, colleagueAlice))
        rig.repo.refreshHubGroupRosters()
        rig.repo.onInboundGroupWireFrame(colleagueBob, msg("m-bob", colleagueBob))
        assertFalse(rig.messages.messages.values.any { it.localId == "m-bob" }, "a removed member is refused")

        // The statement window closes: even Alice is refused.
        rig.time.timeMs = 5_000_001L
        rig.repo.onInboundGroupWireFrame(colleagueAlice, msg("m-late", colleagueAlice))
        assertFalse(rig.messages.messages.values.any { it.localId == "m-late" }, "an expired roster accepts nobody")
    }
}
