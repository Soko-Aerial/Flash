package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashAttachmentProgress
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
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
import org.junit.Assert.assertFalse
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

        /** Mirrors the SQL guard `AND status NOT IN ('DELIVERED','READ')` (ERROR-031). */
        override suspend fun updateStatusIfUnacknowledged(localId: String, status: String) {
            val current = messages[localId] ?: return
            if (current.status == "DELIVERED" || current.status == "READ") return
            messages[localId] = current.copy(status = status)
            flow.value = messages.values.toList().sortedByDescending { m -> m.sentAt }
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

        override suspend fun updateAttachmentPath(transferId: String, path: String): Int {
            // Mirrors the SQL guard: only rows whose stored path actually differs are counted.
            val stale = messages.values.filter {
                it.attachmentTransferId == transferId && it.attachmentPath != path
            }
            stale.forEach { messages[it.localId] = it.copy(attachmentPath = path) }
            if (stale.isNotEmpty()) {
                flow.value = messages.values.toList().sortedByDescending { it.sentAt }
            }
            return stale.size
        }

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

        override suspend fun makePendingDue(now: Long) {
            queue.keys.forEach { localId ->
                queue[localId]?.let { queue[localId] = it.copy(attempts = 0, nextAttemptAt = now) }
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

        // ERROR-031: a successful socket write does NOT retire the row. It stays claimed — single
        // tick on screen, resend armed — until the peer's DeliveryReceipt proves the message really
        // landed. A write into a half-open socket succeeds, so deleting the row here is what made
        // that loss permanent (ticked once, never arrived, force-stop the only cure).
        assertEquals(1, outboxDao.queue.size)
        assertEquals("SENT", stored.status)

        // The receipt is the commit point: it retires the row and double-ticks the bubble.
        repository.onInboundWireFrame(
            MessageWireFrame.DeliveryReceipt(
                messageId = frame.localId,
                conversationId = "conv-alex",
                memberId = "peer-device-id",
                deliveredAt = System.currentTimeMillis(),
            ),
        )
        assertEquals(0, outboxDao.queue.size)
        assertEquals("DELIVERED", messageDao.messages[frame.localId]!!.status)
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
    fun `onInboundTextMessage fires once per new row and stays silent for replayed frames`() =
        runBlocking {
            val messageDao = FakeMessageDao()
            val conversationDao = FakeConversationDao()
            val outboxDao = FakeOutboxDao()
            val receiptDao = FakeReceiptDao()
            val draftDao = FakeDraftDao()
            val recentSearchDao = FakeRecentSearchDao()
            val reactionDao = FakeReactionDao()

            val notified = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String?, String>>())

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
                transportSink = null,
                ioDispatcher = testDispatcher,
                onInboundTextMessage = { conversationId, senderName, text ->
                    notified.add(Triple(conversationId, senderName, text))
                },
            )

            val frame = MessageWireFrame.TextMessage(
                localId = "notify-1",
                conversationId = "my-device-id",
                senderId = "peer-device-id",
                senderName = "Alex Chen",
                text = "Ping while backgrounded",
                sentAt = System.currentTimeMillis(),
            )

            // First sighting: row inserted → host notified (Bug 7 wiring).
            repository.onInboundWireFrame(frame)
            kotlinx.coroutines.delay(50)
            assertEquals(1, notified.size)
            assertEquals(Triple("peer-device-id", "Alex Chen", "Ping while backgrounded"), notified.first())

            // Replay (peer reconnect redelivers the same localId): Room IGNORE-conflicts,
            // so the notification callback must NOT fire again.
            repository.onInboundWireFrame(frame)
            repository.onInboundWireFrame(frame)
            kotlinx.coroutines.delay(50)
            assertEquals(1, notified.size)
        }

    @Test
    fun `onInboundAttachment fires only when the attachment row is newly inserted`() =
        runBlocking {
            val messageDao = FakeMessageDao()
            val conversationDao = FakeConversationDao()
            val outboxDao = FakeOutboxDao()
            val receiptDao = FakeReceiptDao()
            val draftDao = FakeDraftDao()
            val recentSearchDao = FakeRecentSearchDao()
            val reactionDao = FakeReactionDao()

            val notified = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())

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
                transportSink = null,
                ioDispatcher = testDispatcher,
                onInboundAttachment = { conversationId, _, fileName, _ ->
                    notified.add(conversationId to fileName)
                },
            )

            // First accept: row minted → notified.
            repository.onInboundAttachment(
                peerDeviceId = "peer-device-id",
                transferId = "transfer-1",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024,
            )
            kotlinx.coroutines.delay(100)
            assertEquals(listOf("peer-device-id" to "photo.jpg"), notified)

            // Replay of the same transferId (existsAttachment guard): silent.
            repository.onInboundAttachment(
                peerDeviceId = "peer-device-id",
                transferId = "transfer-1",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024,
            )
            kotlinx.coroutines.delay(100)
            assertEquals(1, notified.size)
        }

    /**
     * The image branch of `applyAttachment` used to render a tile for any status, so a received photo
     * arrived as a gradient placeholder with nothing to decode and — because a tile has no
     * Accept/Decline row — no way to fetch the bytes either. It must behave like the video branch:
     * file card until the file is local, tile afterwards, with the path stamped onto the row so the
     * preview survives process death (live progress is in-memory only).
     */
    @Test
    fun `an inbound image offer stays a file card until its bytes land, then becomes a thumbnail`() =
        runBlocking {
            val messageDao = FakeMessageDao()
            val progress = MutableStateFlow<Map<String, FlashAttachmentProgress>>(emptyMap())

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
                attachmentProgress = progress,
            )

            repository.onInboundAttachment(
                peerDeviceId = "peer-device-id",
                transferId = "transfer-1",
                fileName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1024,
            )
            repository.openConversation("peer-device-id")
            progress.value = mapOf(
                "transfer-1" to FlashAttachmentProgress(
                    progress = 0f,
                    status = FlashFileTransferStatus.AwaitingAcceptance,
                ),
            )
            kotlinx.coroutines.delay(200)

            val offered = repository.conversationState.value.messages
                .single { it.fileAttachments.isNotEmpty() || it.images.isNotEmpty() }
            assertTrue("an un-accepted offer must not render as a tile", offered.images.isEmpty())
            assertEquals(
                FlashFileTransferStatus.AwaitingAcceptance,
                offered.fileAttachments.single().transferStatus,
            )

            val received = "/storage/FlashReceived/transfer-1/photo.jpg"
            progress.value = mapOf(
                "transfer-1" to FlashAttachmentProgress(
                    progress = 1f,
                    status = FlashFileTransferStatus.Downloaded,
                    localPath = received,
                ),
            )
            kotlinx.coroutines.delay(200)

            val downloaded = repository.conversationState.value.messages
                .single { it.fileAttachments.isNotEmpty() || it.images.isNotEmpty() }
            assertTrue("a received image must render as a tile", downloaded.fileAttachments.isEmpty())
            assertEquals(received, downloaded.images.single().uri)
            // Stamped on the row: without this the tile reverts to an undecodable placeholder as
            // soon as the in-memory transfer list is gone.
            assertEquals(
                received,
                messageDao.messages.values.single { it.attachmentTransferId == "transfer-1" }.attachmentPath,
            )
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
            // ERROR-031: the row outlives the successful write and waits for the peer's receipt.
            assertTrue("row should await acknowledgement", outboxDao.queue.containsKey(dispatched.first().localId))
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
    fun `notifyPeerSessionUp flushes a queued outbox message stuck in backoff`() = runBlocking {
        val messageDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        val sentFrames = mutableListOf<MessageWireFrame>()

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
            // Sink is live again (peer reconnected): delivery now succeeds.
            transportSink = MessageTransportSink { _, frame ->
                sentFrames.add(frame)
                true
            },
            ioDispatcher = testDispatcher,
        )
        val now = System.currentTimeMillis()
        messageDao.insert(msg("m-retry", "conv-A", "queued while offline").copy(status = "PENDING"))
        // Mid-backoff state: attempts accumulated, next attempt far in the future — the 1 Hz drain
        // would skip this row (not due) and the FAILED cap was in reach.
        outboxDao.enqueue(
            OutboxEntity(
                localId = "m-retry",
                attempts = 3,
                nextAttemptAt = now + 60_000L,
                payloadJson = "queued while offline",
                createdAt = now,
            ),
        )

        // A peer session comes up: kick the outbox.
        repository.notifyPeerSessionUp()
        kotlinx.coroutines.delay(150)

        // The row was reset to due + immediately drained and delivered. ERROR-031: it stays claimed
        // until the peer acknowledges, so the proof of the flush is the frame on the wire and the
        // single tick — not an empty queue.
        assertTrue("row should await acknowledgement", outboxDao.queue.containsKey("m-retry"))
        assertEquals("SENT", messageDao.messages["m-retry"]!!.status)
        assertEquals(1, sentFrames.size)
    }

    @Test
    fun `outbox delivery gives up once the wall-clock budget expires and marks the message failed`() = runBlocking {
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
        // ERROR-026: give-up is a wall-clock budget, not an attempt count. Seed a row whose
        // createdAt is already past the budget and due immediately, so the very next failed drain
        // trips the give-up path. `attempts` is deliberately low — it must NOT be what decides.
        outboxDao.enqueue(
            OutboxEntity(
                localId = "m-cap",
                attempts = 1,
                nextAttemptAt = now,
                payloadJson = "never delivers",
                createdAt = now - OUTBOX_GIVE_UP_BUDGET_MS - 1,
            ),
        )

        val deadline = System.currentTimeMillis() + 4_000
        while (outboxDao.queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        // Row dropped so the drain stops re-claiming it; message surfaced as Failed.
        assertEquals(0, outboxDao.queue.size)
        assertEquals("FAILED", messageDao.messages["m-cap"]!!.status)
    }

    @Test
    fun `outbox keeps retrying a young message that has failed many times`() = runBlocking {
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
        messageDao.insert(msg("m-young", "conv-A", "peer is dozing").copy(status = "PENDING"))
        // The exact state the old 8-attempt cap turned into a permanent FAILED: a long screen-off
        // window burns attempts fast, but the message is seconds old and the peer is coming back.
        outboxDao.enqueue(
            OutboxEntity(
                localId = "m-young",
                attempts = 20,
                nextAttemptAt = now,
                payloadJson = "peer is dozing",
                createdAt = now,
            ),
        )

        val deadline = System.currentTimeMillis() + 3_000
        while ((outboxDao.queue["m-young"]?.attempts ?: 0) <= 20 && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        // Still queued and still PENDING: attempts alone can no longer fail a message.
        assertTrue("row should still be queued", outboxDao.queue.containsKey("m-young"))
        assertEquals("PENDING", messageDao.messages["m-young"]!!.status)
    }

    @Test
    fun `a written but unacknowledged message is resent until the peer acknowledges it`() = runBlocking {
        val messageDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        // The sink reports success on every call — exactly what a half-open socket does: the kernel
        // accepts the bytes and nothing ever surfaces an error, while the peer receives nothing.
        val writes = java.util.concurrent.atomic.AtomicInteger(0)
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
            transportSink = MessageTransportSink { _, _ -> writes.incrementAndGet(); true },
            ioDispatcher = testDispatcher,
        )
        val now = System.currentTimeMillis()
        messageDao.insert(msg("m-zombie", "conv-A", "into the void").copy(status = "PENDING"))
        outboxDao.enqueue(
            OutboxEntity(
                localId = "m-zombie",
                attempts = 0,
                nextAttemptAt = now,
                payloadJson = "into the void",
                createdAt = now,
            ),
        )

        // ERROR-031: the write "succeeds" and the bubble single-ticks, but with no receipt the row
        // must stay claimed and go out again on the backoff ladder. Two writes prove the resend.
        val deadline = System.currentTimeMillis() + 6_000
        while (writes.get() < 2 && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        assertTrue("frame should have been resent, got ${writes.get()} write(s)", writes.get() >= 2)
        assertEquals("SENT", messageDao.messages["m-zombie"]!!.status)
        assertTrue("row must survive an unacknowledged write", outboxDao.queue.containsKey("m-zombie"))

        // The peer finally answers: the row retires and the bubble double-ticks.
        repository.onInboundWireFrame(
            MessageWireFrame.DeliveryReceipt(
                messageId = "m-zombie",
                conversationId = "conv-A",
                memberId = "peer",
                deliveredAt = System.currentTimeMillis(),
            ),
        )
        assertFalse("receipt must retire the row", outboxDao.queue.containsKey("m-zombie"))
        assertEquals("DELIVERED", messageDao.messages["m-zombie"]!!.status)

        // And the resend stops: no further write once the row is gone.
        val settled = writes.get()
        kotlinx.coroutines.delay(1_500)
        assertEquals(settled, writes.get())
    }

    @Test
    fun `an unacknowledged message fails once the budget expires even though every write succeeded`() = runBlocking {
        val messageDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        val writes = java.util.concurrent.atomic.AtomicInteger(0)
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
            transportSink = MessageTransportSink { _, _ -> writes.incrementAndGet(); true },
            ioDispatcher = testDispatcher,
        )
        val now = System.currentTimeMillis()
        messageDao.insert(msg("m-stale", "conv-A", "written, never acked").copy(status = "SENT"))
        // ERROR-031: keeping the row past a successful write must not make it immortal. The
        // wall-clock budget is therefore tested BEFORE the send, so a row whose writes keep
        // succeeding into a socket nobody reads still surfaces the 1-tap Retry after 30 minutes.
        outboxDao.enqueue(
            OutboxEntity(
                localId = "m-stale",
                attempts = 4,
                nextAttemptAt = now,
                payloadJson = "written, never acked",
                createdAt = now - OUTBOX_GIVE_UP_BUDGET_MS - 1,
            ),
        )

        val deadline = System.currentTimeMillis() + 4_000
        while (outboxDao.queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        assertEquals(0, outboxDao.queue.size)
        assertEquals("FAILED", messageDao.messages["m-stale"]!!.status)
        // Give-up short-circuits ahead of the send, so the doomed frame is not re-transmitted.
        assertEquals(0, writes.get())
    }

    @Test
    fun `a resend can never walk an already delivered message back to SENT or FAILED`() = runBlocking {
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
            transportSink = MessageTransportSink { _, _ -> true },
            ioDispatcher = testDispatcher,
        )
        val now = System.currentTimeMillis()
        // ERROR-031: rows now outlive the socket write, so a resend can race a receipt that already
        // landed. An unconditional status write would turn a double-ticked bubble back into a single
        // tick — and an expired budget would mark a delivered message Failed. Both are excluded.
        messageDao.insert(msg("m-acked", "conv-A", "already delivered").copy(status = "DELIVERED"))
        outboxDao.enqueue(
            OutboxEntity(
                localId = "m-acked",
                attempts = 1,
                nextAttemptAt = now,
                payloadJson = "already delivered",
                createdAt = now - OUTBOX_GIVE_UP_BUDGET_MS - 1,
            ),
        )

        val deadline = System.currentTimeMillis() + 4_000
        while (outboxDao.queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(25)
        }
        assertEquals(0, outboxDao.queue.size)
        assertEquals("DELIVERED", messageDao.messages["m-acked"]!!.status)
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

    private companion object {
        /** Mirrors `RealFlashChatRepository.OUTBOX_GIVE_UP_AFTER_MS` (private to the repository). */
        const val OUTBOX_GIVE_UP_BUDGET_MS = 30 * 60 * 1000L
    }
}
