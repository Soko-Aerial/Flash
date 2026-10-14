package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.enterprise.ExpiredReason
import com.transfer.flash.core.enterprise.OrgLease
import com.transfer.flash.core.enterprise.OrgPeer
import com.transfer.flash.core.enterprise.OrgPeerResolver
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.ConversationPreview
import com.transfer.flash.core.persistence.db.dao.ConversationUnread
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
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

        override fun isKnownColleague(deviceId: String): Boolean = members.containsKey(deviceId)

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

    private fun createRepository(
        timeSource: FlashTimeSource,
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        outboxDao: OutboxDao,
        reactionDao: ReactionDao,
        isTrustedPeer: (String) -> Boolean = { false },
        isOrgPeer: (String) -> Boolean = { false },
        orgPeerResolver: OrgPeerResolver? = null,
        transportSink: MessageTransportSink? = null,
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
            isTrustedPeer = isTrustedPeer,
            isOrgPeer = isOrgPeer,
            orgPeerResolver = orgPeerResolver,
            transportSink = transportSink,
            ioDispatcher = Dispatchers.Unconfined,
            timeSource = timeSource,
        )
        reposToClose.add(repo)
        return repo
    }

    @Test
    fun startColleagueConversation_rejectsSelfWithExactSentence() = runTest {
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
    fun startColleagueConversation_rejectsWhenNoSyncOrResolverWithExactSentence() = runTest {
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
    fun startColleagueConversation_rejectsWhenLeaseNoneWithExactSentence() = runTest {
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
    fun startColleagueConversation_rejectsWhenLeaseExpiredWithExactSentence() = runTest {
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
    fun startColleagueConversation_rejectsWhenNotInDirectoryWithExactSentence() = runTest {
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
    fun startColleagueConversation_rejectsWhenRemovedFromDirectoryWithExactSentence() = runTest {
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
    fun startColleagueConversation_succeedsForValidColleagueAndCreatesConversation() = runTest {
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
    fun durableOutbox_unreachableColleague_messagePersistedAndRetained() = runTest {
        val msgDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
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
            outboxDao,
            FakeReactionDao(),
            orgPeerResolver = resolver,
            transportSink = { _, _ -> false }, // Offline/unreachable transport sink
        )

        repo.openConversation(colleagueAlice)
        repo.sendText("Hello offline colleague!")

        // Message is durably written to Room messages table
        assertEquals(1, msgDao.messages.size)
        val msg = msgDao.messages.values.first()
        assertEquals(colleagueAlice, msg.conversationId)
        assertEquals("Hello offline colleague!", msg.text)

        // And retained in durable outbox awaiting delivery
        assertEquals(1, outboxDao.outbox.size)
        val outboxEntry = outboxDao.outbox.values.first()
        assertEquals(msg.localId, outboxEntry.localId)
    }

    @Test
    fun orgDialCandidates_filtersAndCapsAtEight() = runTest {
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

        // 1. Open conversation is colleague-1
        repo.openConversation("colleague-1")

        // 2. Outbox pending for colleague-2 and colleague-3
        msgDao.insert(MessageEntity("m1", "colleague-2", localDeviceId, "Me", "hi", now, "PENDING"))
        outboxDao.enqueue(OutboxEntity("m1", 0, now, "{}", now))
        msgDao.insert(MessageEntity("m2", "colleague-3", localDeviceId, "Me", "hi", now, "PENDING"))
        outboxDao.enqueue(OutboxEntity("m2", 0, now, "{}", now))

        // 3. Recent conversations for colleague-4 through colleague-12 (updated recently)
        for (i in 4..12) {
            convDao.upsert(ConversationEntity("colleague-$i", "Colleague $i", false, sortOrder = now - 1000L))
        }

        val candidates = repo.orgDialCandidates(nowMs = now, limit = 8)

        // Must be capped at exactly 8
        assertEquals(8, candidates.size)
        // Must contain open conversation
        assertTrue(candidates.contains("colleague-1"))
        // Must contain peers with outbox messages
        assertTrue(candidates.contains("colleague-2"))
        assertTrue(candidates.contains("colleague-3"))
    }

    @Test
    fun reactionGate_acceptsOrgColleagueAndRejectsStranger() = runTest {
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

        // 1. Inbound reaction from ORG colleague Alice -> ACCEPTED
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
        assertNotNull(reactionDao.get("msg-1", "👍"))

        // 2. Inbound reaction from stranger Eve (neither paired nor ORG) targeting Alice's chat -> DROPPED by trust gate
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
        assertNull(reactionDao.get("msg-1", "❤️"))
    }

    @Test
    fun groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague() = runTest {
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
    fun defaultIsOrgPeerFalse_preservesExistingBehaviour() = runTest {
        // Default constructor without orgPeerResolver
        val repo = createRepository(
            MutableTimeSource(),
            FakeMessageDao(),
            FakeConversationDao(),
            FakeOutboxDao(),
            FakeReactionDao(),
            isTrustedPeer = { false },
        )
        val result = repo.startColleagueConversation(colleagueAlice)
        assertTrue(result is FlashResult.Failure)
        assertEquals("Connect to the company network once to load your colleagues.", (result as FlashResult.Failure).errorText())
    }
}
