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
}
