package com.transfer.flash.core.messaging

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealFlashChatRepositoryTest {

    private val executor = Executors.newFixedThreadPool(4)
    private val testDispatcher = executor.asCoroutineDispatcher()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    // In-memory fake DAOs for deterministic JVM testing
    private class FakeMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()
        val flow = MutableStateFlow<List<MessageEntity>>(emptyList())

        override suspend fun insert(message: MessageEntity): Long {
            if (messages.containsKey(message.localId)) return -1L
            messages[message.localId] = message
            flow.value = messages.values.toList().sortedByDescending { it.sentAt }
            return 1L
        }

        override fun observeConversation(conversationId: String): Flow<List<MessageEntity>> = flow

        override suspend fun historyBefore(
            conversationId: String,
            cursorSentAt: Long,
            cursorLocalId: String,
            limit: Int,
        ): List<MessageEntity> = messages.values.filter { it.conversationId == conversationId }.take(limit)

        override suspend fun getByLocalId(localId: String): MessageEntity? = messages[localId]

        override suspend fun updateStatus(localId: String, status: String) {
            messages[localId]?.let {
                val updated = it.copy(status = status)
                messages[localId] = updated
                flow.value = messages.values.toList().sortedByDescending { m -> m.sentAt }
            }
        }

        override suspend fun newestLocalId(conversationId: String): String? =
            messages.values.filter { it.conversationId == conversationId }
                .maxByOrNull { it.sentAt }?.localId

        override fun observeUnreadCounts(selfId: String): Flow<List<ConversationUnread>> =
            MutableStateFlow(
                messages.values
                    .filter { it.senderId != selfId && it.deletedAt == null }
                    .groupBy { it.conversationId }
                    .map { (conversationId, rows) -> ConversationUnread(conversationId, rows.size) },
            ).asStateFlow()

        override fun observeLatestPreviews(): Flow<List<ConversationPreview>> =
            MutableStateFlow(
                messages.values
                    .filter { it.deletedAt == null }
                    .groupBy { it.conversationId }
                    .map { (conversationId, rows) ->
                        val newest = rows.maxByOrNull { it.sentAt }!!
                        ConversationPreview(conversationId, newest.text, newest.sentAt)
                    },
            ).asStateFlow()

        override suspend fun markEdited(localId: String, editedAt: Long) {
            messages[localId]?.let { messages[localId] = it.copy(editedAt = editedAt) }
        }

        override suspend fun markDeleted(localId: String, deletedAt: Long) {
            messages[localId]?.let { messages[localId] = it.copy(deletedAt = deletedAt) }
        }

        override suspend fun deleteByConversations(ids: List<String>) {
            messages.values.filter { it.conversationId in ids }.forEach { messages.remove(it.localId) }
            flow.value = messages.values.toList().sortedByDescending { it.sentAt }
        }

        override suspend fun searchMessages(query: String, limit: Int): List<MessageEntity> =
            messages.values
                .filter { it.deletedAt == null && it.text.contains(query, ignoreCase = true) }
                .sortedByDescending { it.sentAt }
                .take(limit)

        override suspend fun existsAttachment(transferId: String): Boolean =
            messages.values.any { it.attachmentTransferId == transferId }

        override suspend fun markReadUpTo(conversationId: String, selfId: String, upToMessageId: String) {
            val threshold = messages[upToMessageId]?.sentAt ?: return
            messages.values
                .filter {
                    it.conversationId == conversationId && it.senderId == selfId &&
                        it.status != "READ" && it.sentAt <= threshold
                }
                .forEach { messages[it.localId] = it.copy(status = "READ") }
            flow.value = messages.values.toList().sortedByDescending { it.sentAt }
        }
    }

    private class FakeConversationDao : ConversationDao {
        val conversations = ConcurrentHashMap<String, ConversationEntity>()
        val flow = MutableStateFlow<List<ConversationEntity>>(emptyList())

        override suspend fun upsert(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
            flow.value = conversations.values.toList()
        }

        override fun observeAll(): Flow<List<ConversationEntity>> = flow

        override suspend fun setArchived(id: String, archived: Boolean) {
            conversations[id]?.let { conversations[id] = it.copy(archived = archived); flow.value = conversations.values.toList() }
        }

        override suspend fun setPinned(id: String, pinned: Boolean) {
            conversations[id]?.let { conversations[id] = it.copy(pinned = pinned); flow.value = conversations.values.toList() }
        }

        override suspend fun setMuted(id: String, muted: Boolean) {
            conversations[id]?.let { conversations[id] = it.copy(muted = muted); flow.value = conversations.values.toList() }
        }

        override suspend fun updateLastReadCursor(id: String, cursor: String) {
            conversations[id]?.let { conversations[id] = it.copy(lastReadCursor = cursor) }
        }

        override suspend fun deleteConversations(ids: List<String>) {
            ids.forEach { conversations.remove(it) }
            flow.value = conversations.values.toList()
        }
    }

    private class FakeOutboxDao : OutboxDao {
        val queue = ConcurrentHashMap<String, OutboxEntity>()
        val countFlow = MutableStateFlow(0)

        override suspend fun enqueue(item: OutboxEntity): Long {
            queue[item.localId] = item
            countFlow.value = queue.size
            return 1L
        }

        override suspend fun dueForDelivery(now: Long, limit: Int): List<OutboxEntity> =
            queue.values
                .filter { it.nextAttemptAt <= now }
                .sortedBy { it.nextAttemptAt }
                .take(limit)

        override suspend fun incrementAttempts(localId: String) {
            queue[localId]?.let { queue[localId] = it.copy(attempts = it.attempts + 1) }
        }

        override suspend fun rescheduleAttempt(localId: String, nextAttemptAt: Long) {
            queue[localId]?.let {
                queue[localId] = it.copy(attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt)
            }
        }

        override suspend fun delete(localId: String) {
            queue.remove(localId)
            countFlow.value = queue.size
        }

        override fun observeCount(): Flow<Int> = countFlow
    }

    private class FakeReceiptDao : ReceiptDao {
        val receipts = mutableListOf<ReceiptEntity>()
        val flow = MutableStateFlow<List<ReceiptEntity>>(emptyList())

        override suspend fun insert(receipt: ReceiptEntity): Long {
            receipts.add(receipt)
            flow.value = receipts.toList()
            return 1L
        }

        override fun observeForMessage(messageId: String): Flow<List<ReceiptEntity>> = flow

        override suspend fun countForMessage(messageId: String): Int =
            receipts.count { it.messageId == messageId }
    }

    private class FakeDraftDao : DraftDao {
        val drafts = ConcurrentHashMap<String, DraftEntity>()
        val flow = MutableStateFlow<DraftEntity?>(null)

        override suspend fun upsert(draft: DraftEntity) {
            drafts[draft.conversationId] = draft
            flow.value = draft
        }

        override fun observeDraft(conversationId: String): Flow<DraftEntity?> = flow

        override suspend fun clear(conversationId: String) {
            drafts.remove(conversationId)
            flow.value = null
        }
    }

    private class FakeReactionDao : ReactionDao {
        val rows = ConcurrentHashMap<Pair<String, String>, ReactionEntity>()
        val flow = MutableStateFlow<List<ReactionEntity>>(emptyList())

        override suspend fun upsert(reaction: ReactionEntity) {
            rows[reaction.messageId to reaction.emoji] = reaction
            flow.value = rows.values.toList()
        }

        override fun observeForMessage(messageId: String): Flow<List<ReactionEntity>> = flow

        override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> = flow

        override suspend fun get(messageId: String, emoji: String): ReactionEntity? =
            rows[messageId to emoji]

        override suspend fun remove(messageId: String, emoji: String) {
            rows.remove(messageId to emoji)
            flow.value = rows.values.toList()
        }
    }

    private class FakeRecentSearchDao : RecentSearchDao {
        val recents = mutableListOf<RecentSearchEntity>()
        val flow = MutableStateFlow<List<RecentSearchEntity>>(emptyList())

        override suspend fun upsert(search: RecentSearchEntity) {
            recents.removeAll { it.query == search.query }
            recents.add(0, search)
            flow.value = recents.toList()
        }

        override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> = flow

        override suspend fun remove(query: String) {
            recents.removeAll { it.query == query }
            flow.value = recents.toList()
        }

        override suspend fun clearAll() {
            recents.clear()
            flow.value = emptyList()
        }
    }

    @Test
    fun `sendText writes message to Room and enqueues in outbox`() = runBlocking {
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        val receiptDao = FakeReceiptDao()
        val draftDao = FakeDraftDao()
        val recentSearchDao = FakeRecentSearchDao()
        val reactionDao = FakeReactionDao()

        val sentFrames = mutableListOf<MessageWireFrame>()
        val transportSink = MessageTransportSink { _, frame ->
            sentFrames.add(frame)
            true
        }

        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            receiptDao = receiptDao,
            draftDao = draftDao,
            recentSearchDao = recentSearchDao,
            reactionDao = reactionDao,
            transportSink = transportSink,
            ioDispatcher = testDispatcher,
        )

        repository.openConversation("conv-alex")
        repository.sendText("Hello over LAN!")

        // Allow IO execution
        kotlinx.coroutines.delay(100)

        // Verify message was stored in DB
        assertEquals(1, messageDao.messages.size)
        val stored = messageDao.messages.values.first()
        assertEquals("Hello over LAN!", stored.text)
        assertEquals("my-device-id", stored.senderId)

        // Verify wire frame was dispatched
        assertEquals(1, sentFrames.size)
        val frame = sentFrames.first() as MessageWireFrame.TextMessage
        assertEquals("Hello over LAN!", frame.text)

        // Verify outbox was cleared after successful dispatch
        assertEquals(0, outboxDao.queue.size)
        assertEquals("SENT", stored.status)
    }

    @Test
    fun `onInboundWireFrame TextMessage stores message and replies with DeliveryReceipt`() = runBlocking {
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        val receiptDao = FakeReceiptDao()
        val draftDao = FakeDraftDao()
        val recentSearchDao = FakeRecentSearchDao()
        val reactionDao = FakeReactionDao()

        val replyFrames = mutableListOf<MessageWireFrame>()
        val replyTargets = mutableListOf<String>()
        val transportSink = MessageTransportSink { target, frame ->
            replyTargets.add(target)
            replyFrames.add(frame)
            true
        }

        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            receiptDao = receiptDao,
            draftDao = draftDao,
            recentSearchDao = recentSearchDao,
            reactionDao = reactionDao,
            transportSink = transportSink,
            ioDispatcher = testDispatcher,
        )

        val inbound = MessageWireFrame.TextMessage(
            localId = "inbound-123",
            conversationId = "conv-peer",
            senderId = "peer-device-id",
            senderName = "Alex Chen",
            text = "Received fine!",
            sentAt = System.currentTimeMillis(),
        )

        repository.onInboundWireFrame(inbound)

        // Verify message stored
        assertEquals(1, messageDao.messages.size)
        val stored = messageDao.messages["inbound-123"]
        assertNotNull(stored)
        assertEquals("Received fine!", stored!!.text)

        // Regression: inbound messages must be threaded under the AUTHOR's device id
        // (frame.senderId), NOT frame.conversationId — which is our OWN id (the sender addressed us
        // by it). Threading under our own id keyed the reply's transport routing to ourselves, so
        // the responder could receive but never send back. The conversation row must key the same.
        assertEquals("peer-device-id", stored.conversationId)
        assertEquals("peer-device-id", conversationDao.conversations.values.first().id)

        // Verify delivery receipt reply was sent
        assertEquals(1, replyFrames.size)
        val receipt = replyFrames.first() as MessageWireFrame.DeliveryReceipt
        assertEquals("inbound-123", receipt.messageId)
        assertEquals("my-device-id", receipt.memberId)

        // Regression: the receipt must route to the message AUTHOR (senderId), not to
        // frame.conversationId — which on this side resolves to our own device id, so the
        // sender would never receive its DELIVERED tick.
        assertEquals("peer-device-id", replyTargets.first())
    }

    @Test
    fun `outbox drain preserves the composing conversationId after the active conversation changes`() =
        runBlocking {
            val messageDao = FakeMessageDao()
            val conversationDao = FakeConversationDao()
            val outboxDao = FakeOutboxDao()
            val receiptDao = FakeReceiptDao()
            val draftDao = FakeDraftDao()
            val recentSearchDao = FakeRecentSearchDao()
            val reactionDao = FakeReactionDao()

            // The transport refuses until `deliver` flips true, so the message composed in conv-A
            // stays queued and is only actually dispatched by a later background drain — by which
            // point the active conversation has moved to conv-B. The recovered conversationId must
            // still be conv-A (from the durable message row), never conv-B or "general".
            val deliver = java.util.concurrent.atomic.AtomicBoolean(false)
            val dispatched = java.util.Collections.synchronizedList(mutableListOf<MessageWireFrame.TextMessage>())
            val transportSink = MessageTransportSink { _, frame ->
                if (frame is MessageWireFrame.TextMessage && deliver.get()) {
                    dispatched.add(frame)
                    true
                } else {
                    false
                }
            }

            val repository = RealFlashChatRepository(
                localDeviceId = "my-device-id",
                localDisplayName = "Kali",
                messageDao = messageDao,
                conversationDao = conversationDao,
                outboxDao = outboxDao,
                receiptDao = receiptDao,
                draftDao = draftDao,
                recentSearchDao = recentSearchDao,
                reactionDao = reactionDao,
                transportSink = transportSink,
                ioDispatcher = testDispatcher,
            )

            repository.openConversation("conv-A")
            repository.sendText("message for A")
            kotlinx.coroutines.delay(100)
            // Immediate drain refused delivery, so the row is still queued.
            assertEquals(1, outboxDao.queue.size)

            // User navigates away; a deferred drain will now run with a different active conversation.
            repository.openConversation("conv-B")
            deliver.set(true)

            val deadline = System.currentTimeMillis() + 6_000
            while (dispatched.isEmpty() && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(50)
            }

            assertEquals(1, dispatched.size)
            assertEquals("conv-A", dispatched.first().conversationId)
            assertEquals("message for A", dispatched.first().text)
            assertEquals(0, outboxDao.queue.size)
        }

    @Test
    fun `searchMessageBodies returns distinct conversation ids for body matches`() = runBlocking {
        val messageDao = FakeMessageDao()
        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = FakeConversationDao(),
            outboxDao = FakeOutboxDao(),
            receiptDao = FakeReceiptDao(),
            draftDao = FakeDraftDao(),
            recentSearchDao = FakeRecentSearchDao(),
            reactionDao = FakeReactionDao(),
            transportSink = null,
            ioDispatcher = testDispatcher,
        )

        // Two messages in conv-A (only one matches), one match in conv-B, one non-match in conv-C,
        // and a tombstoned match that must be excluded.
        messageDao.insert(msg("m1", "conv-A", "let's ship the release build tonight"))
        messageDao.insert(msg("m2", "conv-A", "unrelated chatter"))
        messageDao.insert(msg("m3", "conv-B", "the BUILD is green"))
        messageDao.insert(msg("m4", "conv-C", "lunch?"))
        messageDao.insert(msg("m5", "conv-D", "old build note").copy(deletedAt = 1L))

        val matches = repository.searchMessageBodies("build")

        // conv-A and conv-B match (case-insensitively); conv-C never matched; conv-D is tombstoned.
        // conv-A appears once despite two rows.
        assertEquals(setOf("conv-A", "conv-B"), matches)

        // Blank query short-circuits to empty without touching the DAO.
        assertTrue(repository.searchMessageBodies("   ").isEmpty())
    }

    @Test
    fun `failed outbox delivery backs off instead of retrying every tick`() = runBlocking {
        val messageDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = FakeConversationDao(),
            outboxDao = outboxDao,
            receiptDao = FakeReceiptDao(),
            draftDao = FakeDraftDao(),
            recentSearchDao = FakeRecentSearchDao(),
            reactionDao = FakeReactionDao(),
            // Transport always refuses, so every drain attempt fails.
            transportSink = MessageTransportSink { _, _ -> false },
            ioDispatcher = testDispatcher,
        )
        val now = System.currentTimeMillis()
        messageDao.insert(msg("m-bo", "conv-A", "later").copy(status = "PENDING"))
        outboxDao.enqueue(
            OutboxEntity(localId = "m-bo", attempts = 0, nextAttemptAt = now, payloadJson = "later", createdAt = now),
        )

        // Wait for the ~1 Hz drain loop to make its first failed attempt.
        val deadline = System.currentTimeMillis() + 3_000
        while ((outboxDao.queue["m-bo"]?.attempts ?: 0) == 0 && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        val row = outboxDao.queue["m-bo"]!!
        assertTrue("attempt should have been recorded", row.attempts >= 1)
        // Backoff pushed the next retry into the future rather than leaving it due every tick.
        assertTrue("next attempt should be scheduled ahead", row.nextAttemptAt > now)
        // Not yet at the cap, so the message is still pending (not Failed).
        assertEquals("PENDING", messageDao.messages["m-bo"]!!.status)
    }

    @Test
    fun `outbox delivery gives up after max attempts and marks the message failed`() = runBlocking {
        val messageDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        val repository = RealFlashChatRepository(
            localDeviceId = "my-device-id",
            localDisplayName = "Kali",
            messageDao = messageDao,
            conversationDao = FakeConversationDao(),
            outboxDao = outboxDao,
            receiptDao = FakeReceiptDao(),
            draftDao = FakeDraftDao(),
            recentSearchDao = FakeRecentSearchDao(),
            reactionDao = FakeReactionDao(),
            transportSink = MessageTransportSink { _, _ -> false },
            ioDispatcher = testDispatcher,
        )
        val now = System.currentTimeMillis()
        messageDao.insert(msg("m-cap", "conv-A", "never delivers").copy(status = "PENDING"))
        // Seed one attempt short of the cap (OUTBOX_MAX_ATTEMPTS = 8), due immediately, so the very
        // next failed drain trips the give-up path without waiting through the full backoff ladder.
        outboxDao.enqueue(
            OutboxEntity(localId = "m-cap", attempts = 7, nextAttemptAt = now, payloadJson = "never delivers", createdAt = now),
        )

        val deadline = System.currentTimeMillis() + 4_000
        while (outboxDao.queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        // Row dropped so the drain stops re-claiming it; message surfaced as Failed.
        assertEquals(0, outboxDao.queue.size)
        assertEquals("FAILED", messageDao.messages["m-cap"]!!.status)
    }

    private fun msg(localId: String, conversationId: String, text: String) = MessageEntity(
        localId = localId,
        conversationId = conversationId,
        senderId = "peer",
        senderName = "Peer",
        text = text,
        sentAt = System.currentTimeMillis(),
        status = "DELIVERED",
    )
}
