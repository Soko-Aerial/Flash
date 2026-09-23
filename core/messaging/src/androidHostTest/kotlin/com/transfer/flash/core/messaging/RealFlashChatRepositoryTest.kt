package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.model.FlashAttachmentProgress
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.GroupTransportSink
import com.transfer.flash.core.messaging.model.FlashMemberRole
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.dao.ConversationPreview
import com.transfer.flash.core.persistence.db.dao.ConversationUnread
import com.transfer.flash.core.persistence.db.dao.DraftDao
import com.transfer.flash.core.persistence.db.dao.GroupDeliveryCount
import com.transfer.flash.core.persistence.db.dao.GroupDeliveryDao
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.persistence.db.dao.MessageDao
import com.transfer.flash.core.persistence.db.dao.OutboxDao
import com.transfer.flash.core.persistence.db.dao.ReactionDao
import com.transfer.flash.core.persistence.db.dao.ReceiptDao
import com.transfer.flash.core.persistence.db.dao.RecentSearchDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.DraftEntity
import com.transfer.flash.core.persistence.db.entity.GroupDeliveryEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import com.transfer.flash.core.persistence.db.entity.ReactionEntity
import com.transfer.flash.core.persistence.db.entity.ReceiptEntity
import com.transfer.flash.core.persistence.db.entity.RecentSearchEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
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
            // Room's `@Insert(onConflict = IGNORE)` is atomic; a check-then-put is not, and this
            // harness runs on a 4-thread pool (see `executor`). Two group-media paths mint the same
            // bubble concurrently (the GMEDIA early-mint branch and the accept path), so a racy fake
            // reported BOTH inserts as wins - i.e. it invented a duplicate callback that production's
            // IGNORE would have rejected. Found while chasing a 1-in-3 flake in
            // `group media callback supplies stored title and attachment metadata`.
            if (messages.putIfAbsent(message.localId, message) != null) return -1L
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
        ): List<MessageEntity> = messages.values
            .filter {
                it.conversationId == conversationId && it.deletedAt == null &&
                    (it.sentAt > cursorSentAt || (it.sentAt == cursorSentAt && it.localId > cursorLocalId))
            }
            .sortedBy { it.sentAt }
            .take(limit)

        override suspend fun getByLocalId(localId: String): MessageEntity? = messages[localId]

        override suspend fun updateStatus(localId: String, status: String) {
            messages[localId]?.let {
                val updated = it.copy(status = status)
                messages[localId] = updated
                flow.value = messages.values.filter { message -> message.deletedAt == null }
                    .sortedByDescending { m -> m.sentAt }
            }
        }

        /** Mirrors the SQL guard `AND status NOT IN ('DELIVERED','READ')` (ERROR-031). */
        override suspend fun updateStatusIfUnacknowledged(localId: String, status: String) {
            val current = messages[localId] ?: return
            if (current.status == "DELIVERED" || current.status == "READ") return
            messages[localId] = current.copy(status = status)
            flow.value = messages.values.filter { it.deletedAt == null }.sortedByDescending { m -> m.sentAt }
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
            messages[localId]?.takeIf { it.deletedAt == null }
                ?.let { messages[localId] = it.copy(deletedAt = deletedAt) }
            flow.value = messages.values.filter { it.deletedAt == null }.sortedByDescending { it.sentAt }
        }

        override suspend fun deleteByConversations(ids: List<String>) {
            messages.values.filter { it.conversationId in ids }.forEach { messages.remove(it.localId) }
            flow.value = messages.values.filter { it.deletedAt == null }.sortedByDescending { it.sentAt }
        }

        override suspend fun searchMessages(query: String, limit: Int): List<MessageEntity> =
            messages.values
                .filter { it.deletedAt == null && it.text.contains(query, ignoreCase = true) }
                .sortedByDescending { it.sentAt }
                .take(limit)

        override suspend fun searchConversationMessages(
            conversationId: String,
            query: String,
            limit: Int,
        ): List<MessageEntity> =
            messages.values
                .filter { it.conversationId == conversationId && it.deletedAt == null && it.text.contains(query, ignoreCase = true) }
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

        override suspend fun updateGroupContext(
            transferId: String,
            groupId: String,
            messageId: String,
            senderId: String,
            senderName: String,
        ): Int {
            val matching = messages.values.filter { it.attachmentTransferId == transferId }
            matching.forEach {
                messages.remove(it.localId)
                messages[messageId] = it.copy(
                    localId = messageId,
                    conversationId = groupId,
                    senderId = senderId,
                    senderName = senderName,
                )
            }
            if (matching.isNotEmpty()) {
                flow.value = messages.values.toList().sortedByDescending { it.sentAt }
            }
            return matching.size
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

        override suspend fun get(id: String): ConversationEntity? = conversations[id]

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
            conversations[id]?.let {
                conversations[id] = it.copy(lastReadCursor = cursor)
                flow.value = conversations.values.toList()
            }
        }

        override suspend fun clearLastReadCursor(id: String) {
            conversations[id]?.let {
                conversations[id] = it.copy(lastReadCursor = null)
                flow.value = conversations.values.toList()
            }
        }

        override suspend fun deleteConversations(ids: List<String>) {
            ids.forEach { conversations.remove(it) }
            flow.value = conversations.values.toList()
        }

        override suspend fun updateDirectTitle(id: String, title: String) {
            conversations[id]?.let {
                if (!it.isGroup) {
                    conversations[id] = it.copy(title = title)
                    flow.value = conversations.values.toList()
                }
            }
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
    fun `onInboundWireFrame drops frames when transportPeerId does not match claim author`() = runBlocking {
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        val receiptDao = FakeReceiptDao()
        val draftDao = FakeDraftDao()
        val recentSearchDao = FakeRecentSearchDao()
        val reactionDao = FakeReactionDao()

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
        )

        val spoofedText = MessageWireFrame.TextMessage(
            localId = "spoof-1",
            conversationId = "my-device-id",
            senderId = "victim-device-id",
            senderName = "Victim",
            text = "Spoofed text",
            sentAt = System.currentTimeMillis(),
        )

        // Transport peer is "attacker-device-id", but claimed senderId is "victim-device-id"
        repository.onInboundWireFrame(spoofedText, transportPeerId = "attacker-device-id")
        assertNull(messageDao.messages["spoof-1"])

        // Valid transport peer matching senderId
        repository.onInboundWireFrame(spoofedText, transportPeerId = "victim-device-id")
        assertNotNull(messageDao.messages["spoof-1"])

        // Spoofed DeliveryReceipt
        val spoofedReceipt = MessageWireFrame.DeliveryReceipt(
            messageId = "msg-1",
            conversationId = "my-device-id",
            memberId = "victim-device-id",
            deliveredAt = System.currentTimeMillis(),
        )
        repository.onInboundWireFrame(spoofedReceipt, transportPeerId = "attacker-device-id")
        assertEquals(0, receiptDao.receipts.size)

        // Spoofed ReactionFrame
        val spoofedReaction = MessageWireFrame.ReactionFrame(
            messageId = "msg-1",
            conversationId = "my-device-id",
            memberId = "victim-device-id",
            emoji = "👍",
            isAdded = true,
        )
        repository.onInboundWireFrame(spoofedReaction, transportPeerId = "attacker-device-id")
        assertNull(reactionDao.get("msg-1", "👍"))
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

    /**
     * The transfer layer publishes progress on a 10 ms watcher tick for the whole duration of a
     * transfer, and progress is an input to the conversation `combine` — so each of those hundred
     * emissions a second used to re-derive every row in the open thread. `pacedAttachmentProgress`
     * throttles that to one value per window.
     *
     * The failure mode a throttle can introduce is losing the LAST value, and here the last value is
     * the one that matters most: it carries the received file's path, both to the bubble and to the
     * row (live progress is in-memory only, so an unstamped row loses the file on restart). This
     * drives a burst at the transfer layer's own cadence and then asserts the terminal value survived
     * it on both surfaces.
     */
    @Test
    fun `a burst of progress ticks still lands its terminal value on the row and on screen`() =
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
                fileName = "clip.bin",
                mimeType = "application/octet-stream",
                sizeBytes = 8_000_000,
            )
            repository.openConversation("peer-device-id")

            // Speed changes on every tick even between ACK batches, which is exactly why the raw flow
            // never went quiet: each of these would have re-mapped the whole conversation.
            repeat(300) { i ->
                progress.value = mapOf(
                    "transfer-1" to FlashAttachmentProgress(
                        progress = i / 300f,
                        status = FlashFileTransferStatus.Transferring,
                        speedMbps = 1f + i * 0.01f,
                    ),
                )
                kotlinx.coroutines.delay(1)
            }

            val received = "/storage/FlashReceived/transfer-1/clip.bin"
            progress.value = mapOf(
                "transfer-1" to FlashAttachmentProgress(
                    progress = 1f,
                    status = FlashFileTransferStatus.Downloaded,
                    localPath = received,
                ),
            )
            kotlinx.coroutines.delay(600)

            val file = repository.conversationState.value.messages
                .single { it.fileAttachments.isNotEmpty() }
                .fileAttachments
                .single()
            assertEquals(FlashFileTransferStatus.Downloaded, file.transferStatus)
            assertEquals(received, file.localUri)
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

    // -----------------------------------------------------------------------------------------
    // ERROR-034: no window in which the UI is shown content that isn't this thread's.
    // -----------------------------------------------------------------------------------------

    /**
     * The constructor used to seed a `"Messages"` / `"FL"` header. Nothing in the app owns that
     * name, so any frame it reached put an invented identity on screen.
     */
    @Test
    fun `initial conversation state invents no identity`() {
        val repository = newRepository()
        val header = repository.conversationState.value.header
        assertEquals("", header.title)
        assertEquals("", header.avatarInitials)
        assertFalse("no thread is open, so there is nobody to call", header.showCallActions)
        assertTrue(repository.conversationState.value.messages.isEmpty())
    }

    /**
     * The reset in [RealFlashChatRepository.openConversation] must land before the method returns.
     * The Room combine that fills the new thread is asynchronous, so anything left behind is
     * rendered under the *new* route: open B straight after A and you read A's name and messages.
     * Asserted with no `delay` on purpose — the point is that the window does not exist.
     */
    @Test
    fun `opening a second conversation never shows the first one's content`() = runBlocking {
        val messageDao = FakeMessageDao()
        val repository = newRepository(messageDao = messageDao)

        messageDao.insert(msg("m-a", "conv-a", "message in thread A"))
        repository.openConversation("conv-a")
        kotlinx.coroutines.delay(100)
        assertEquals("conv-a", repository.conversationState.value.header.title)
        assertTrue(
            "thread A must have loaded, or this test proves nothing",
            repository.conversationState.value.messages.isNotEmpty(),
        )

        repository.openConversation("conv-b")
        // Synchronously, in the same turn: B's identity, and no leftover rows.
        val afterOpen = repository.conversationState.value
        assertEquals("conv-b", afterOpen.header.title)
        assertTrue("A's messages must not appear under B", afterOpen.messages.isEmpty())
        assertEquals("", afterOpen.draftText)
    }

    /**
     * `hasLoaded` is what lets an empty chat list be read as an answer rather than as a gap. The
     * shell shows the skeleton until it flips, so it MUST flip even when Room has nothing — a
     * genuinely fresh install has to be able to reach the first-run panel.
     */
    @Test
    fun `chat list reports hasLoaded once Room answers, even when empty`() = runBlocking {
        val repository = newRepository()
        assertFalse(
            "before the first emission an empty list means 'not known yet'",
            repository.chatListState.value.hasLoaded,
        )

        val state = kotlinx.coroutines.withTimeout(5_000) {
            repository.chatListState.first { it.hasLoaded }
        }
        assertTrue("an empty Room is a real answer and must end the skeleton", state.hasLoaded)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `hasLoaded stays true once a row arrives`() = runBlocking {
        val conversationDao = FakeConversationDao()
        val repository = newRepository(conversationDao = conversationDao)
        kotlinx.coroutines.withTimeout(5_000) { repository.chatListState.first { it.hasLoaded } }

        conversationDao.upsert(
            ConversationEntity(
                id = "conv-alex",
                title = "Alex",
                isGroup = false,
                sortOrder = System.currentTimeMillis(),
            ),
        )

        val state = kotlinx.coroutines.withTimeout(5_000) {
            repository.chatListState.first { it.items.isNotEmpty() }
        }
        assertEquals(1, state.items.size)
        assertTrue(state.hasLoaded)
    }

    @Test
    fun `markConversationUnread clears the cursor on the repository IO dispatcher`() = runBlocking {
        val ioThread = Executors.newSingleThreadExecutor { task -> Thread(task, "mark-unread-io") }
            .asCoroutineDispatcher()
        try {
            val conversationDao = object : ConversationDao {
                val delegate = FakeConversationDao()
                val clearThreadNames = java.util.Collections.synchronizedList(mutableListOf<String>())

                override suspend fun upsert(conversation: ConversationEntity) = delegate.upsert(conversation)
                override fun observeAll(): Flow<List<ConversationEntity>> = delegate.observeAll()
                override suspend fun get(id: String): ConversationEntity? = delegate.get(id)
                override suspend fun setArchived(id: String, archived: Boolean) = delegate.setArchived(id, archived)
                override suspend fun setPinned(id: String, pinned: Boolean) = delegate.setPinned(id, pinned)
                override suspend fun setMuted(id: String, muted: Boolean) = delegate.setMuted(id, muted)
                override suspend fun updateLastReadCursor(id: String, cursor: String) =
                    delegate.updateLastReadCursor(id, cursor)
                override suspend fun clearLastReadCursor(id: String) {
                    clearThreadNames += Thread.currentThread().name
                    delegate.clearLastReadCursor(id)
                }
                override suspend fun deleteConversations(ids: List<String>) = delegate.deleteConversations(ids)
                override suspend fun updateDirectTitle(id: String, title: String) =
                    delegate.updateDirectTitle(id, title)
            }
            conversationDao.upsert(
                ConversationEntity(
                    id = "conv-unread",
                    title = "Alex",
                    isGroup = false,
                    lastReadCursor = "message-2",
                ),
            )
            val repository = RealFlashChatRepository(
                localDeviceId = "my-device-id",
                localDisplayName = "Kali",
                messageDao = FakeMessageDao(),
                conversationDao = conversationDao,
                outboxDao = FakeOutboxDao(),
                receiptDao = FakeReceiptDao(),
                draftDao = FakeDraftDao(),
                recentSearchDao = FakeRecentSearchDao(),
                reactionDao = FakeReactionDao(),
                ioDispatcher = ioThread,
            )

            repository.markConversationUnread("conv-unread")
            kotlinx.coroutines.withTimeout(5_000) {
                conversationDao.observeAll().first { rows -> rows.single().lastReadCursor == null }
            }

            assertEquals(null, conversationDao.get("conv-unread")!!.lastReadCursor)
            assertTrue(conversationDao.clearThreadNames.single().contains("mark-unread-io"))
        } finally {
            ioThread.close()
        }
    }

    // ------------------------------------------------------------------ groups (Phase 1A)

    @Test
    fun `sending an attachment to a group never clobbers the group conversation row`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val conversationDao = FakeConversationDao()
        val messageDao = FakeMessageDao()
        val repository = newRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
        )
        val groupId = (repository.createGroup("Design Team", setOf("peer-a")) as FlashResult.Success).value

        // F1: the attachment path used to upsert ConversationEntity(isGroup=false, title=groupId),
        // a full-row replace that demoted the group to a direct chat titled with its own id.
        repository.sendAttachment(
            conversationId = groupId,
            transferId = "transfer-1",
            fileName = "Voice message.m4a",
            mimeType = "audio/mp4",
            sizeBytes = 1024,
            localPath = "/tmp/v.m4a",
        )
        kotlinx.coroutines.delay(100)

        val row = conversationDao.get(groupId)!!
        assertTrue("group row demoted to direct chat", row.isGroup)
        assertEquals("Design Team", row.title)
        assertEquals("my-device-id", row.groupCreatedBy)
        // Interim honest gate: the attachment row itself must NOT exist for a group (F4 pending).
        assertTrue(messageDao.messages.isEmpty())
    }

    /** Fakes for the two group tables, mirroring the SQL semantics of the Room DAOs. */
    private class FakeGroupMemberDao : GroupMemberDao {
        val members = ConcurrentHashMap<Pair<String, String>, GroupMemberEntity>()
        override suspend fun upsert(member: GroupMemberEntity) {
            members[member.groupId to member.deviceId] = member
        }
        override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> =
            MutableStateFlow(members.values.filter { it.groupId == groupId }.sortedBy { it.joinedAt })
        override suspend fun activeMembers(groupId: String): List<GroupMemberEntity> =
            members.values.filter { it.groupId == groupId && it.isActive }.sortedBy { it.joinedAt }
        override suspend fun member(groupId: String, deviceId: String): GroupMemberEntity? =
            members[groupId to deviceId]
        override suspend fun activeCount(groupId: String): Int =
            members.values.count { it.groupId == groupId && it.isActive }
        override suspend fun activeGroupIdsFor(deviceId: String): List<String> =
            members.values.filter { it.deviceId == deviceId && it.isActive }.map { it.groupId }.distinct()
        override suspend fun updateMemberDisplayName(deviceId: String, newName: String) {
            members.replaceAll { key, entity ->
                if (key.second == deviceId) entity.copy(displayName = newName) else entity
            }
        }
    }

    private class FakeGroupDeliveryDao : GroupDeliveryDao {
        val rows = ConcurrentHashMap<Pair<String, String>, GroupDeliveryEntity>()
        private val counts = MutableStateFlow<List<GroupDeliveryCount>>(emptyList())

        private fun publishCounts() {
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
            publishCounts()
        }
        override suspend fun pendingForMessage(messageId: String): List<GroupDeliveryEntity> =
            rows.values.filter { it.messageId == messageId && it.state != "DELIVERED" }
                .sortedBy { it.nextAttemptAt }
        override suspend fun memberCount(messageId: String): Int =
            rows.values.count { it.messageId == messageId }
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
            publishCounts()
            return 1
        }
        override suspend fun reschedule(messageId: String, memberId: String, state: String, nextAttemptAt: Long) {
            val key = messageId to memberId
            rows[key]?.let { rows[key] = it.copy(state = state, attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt) }
        }
        override suspend fun makePendingDueForMember(memberId: String, now: Long) {
            rows.values.filter { it.memberId == memberId && it.state != "DELIVERED" }
                .forEach { rows[it.messageId to it.memberId] = it.copy(attempts = 0, nextAttemptAt = now) }
        }
        override suspend fun deleteForMessage(messageId: String) {
            rows.keys.removeAll { it.first == messageId }
            publishCounts()
        }
    }

    @Test
    fun `group mutations never send from the caller's thread`() = runBlocking {
        // ERROR-036: createGroup used to run its blocking socket writes on the CALLER's
        // dispatcher; the UI calls it from the main thread, and the send threw
        // NetworkOnMainThreadException (and killed the live sessions). The fix hops to the
        // repository's ioDispatcher. The JVM cannot reproduce Android's main-thread detector,
        // but it CAN observe which thread the sink ran on — and it must not be the caller's.
        val ioThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "repo-io-test")
        }.asCoroutineDispatcher()
        try {
            val memberDao = FakeGroupMemberDao()
            val callerThread = Thread.currentThread()
            val sinkThreads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
            val repository = newRepository(
                groupMemberDao = memberDao,
                trustedPeers = setOf("peer-a"),
                groupSink = { _, _ -> sinkThreads.add(Thread.currentThread()); true },
            )
            val field = RealFlashChatRepository::class.java.getDeclaredField("ioDispatcher")
            field.isAccessible = true
            field.set(repository, ioThread)

            repository.createGroup("Team", setOf("peer-a"))
            kotlinx.coroutines.delay(100)
            assertTrue(sinkThreads.isNotEmpty())
            assertTrue(
                "group send ran on the caller's thread ${callerThread.name}",
                sinkThreads.none { it === callerThread },
            )
        } finally {
            ioThread.close()
        }
    }

    @Test
    fun `state frame bootstraps the group on a device with no local record`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val conversationDao = FakeConversationDao()
        val repository = newRepository(
            conversationDao = conversationDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
        )

        val now = System.currentTimeMillis()
        val state = GroupWireFrame.State(
            groupId = "g-new",
            from = "peer-a",
            operationId = "op-state",
            membershipVersion = now,
            name = "Late Joiners",
            creatorId = "peer-a",
            members = listOf(
                GroupWireFrame.RosterEntry(
                    deviceId = "peer-a", displayName = "Peer A", role = "owner",
                    joinedAt = now, membershipVersion = now, operationId = "op-create", isActive = true,
                ),
                GroupWireFrame.RosterEntry(
                    deviceId = "my-device-id", displayName = "Me", role = "member",
                    joinedAt = now, membershipVersion = now, operationId = "op-create", isActive = true,
                ),
                GroupWireFrame.RosterEntry(
                    deviceId = "peer-b", displayName = "Peer B", role = "member",
                    joinedAt = now, membershipVersion = now, operationId = "op-create", isActive = true,
                ),
            ),
        )
        repository.onInboundGroupWireFrame("peer-a", state)

        val row = conversationDao.get("g-new")!!
        assertTrue(row.isGroup)
        assertEquals("Late Joiners", row.title)
        assertEquals("peer-a", row.groupCreatedBy)
        assertEquals(3, memberDao.activeCount("g-new"))
        assertEquals("owner", memberDao.member("g-new", "peer-a")!!.role)
        assertEquals("member", memberDao.member("g-new", "my-device-id")!!.role)
    }

    @Test
    fun `state frame from an untrusted transport peer is dropped`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val conversationDao = FakeConversationDao()
        val repository = newRepository(
            conversationDao = conversationDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
        )
        val now = System.currentTimeMillis()
        // An untrusted transport peer cannot bootstrap a group even with a self-consistent
        // roster — trust is the outer boundary for every group frame.
        repository.onInboundGroupWireFrame(
            "stranger",
            GroupWireFrame.State(
                "g-fake", "stranger", "op", now, "Fabricated", "stranger",
                listOf(
                    GroupWireFrame.RosterEntry(
                        deviceId = "stranger", displayName = "S", role = "owner",
                        joinedAt = now, membershipVersion = now, operationId = "op", isActive = true,
                    ),
                    GroupWireFrame.RosterEntry(
                        deviceId = "my-device-id", displayName = "Me", role = "member",
                        joinedAt = now, membershipVersion = now, operationId = "op", isActive = true,
                    ),
                ),
            ),
        )
        assertNull(conversationDao.get("g-fake"))
    }

    @Test
    fun `state frame cannot resurrect a newer leave tombstone`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val repository = newRepository(
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-b"),
        )
        val groupId =
            (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value
        val leaveVersion = System.currentTimeMillis() + 1_000_000L
        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.Leave(groupId, "peer-a", "leave-op", leaveVersion, "peer-a"),
        )
        assertEquals(false, memberDao.member(groupId, "peer-a")!!.isActive)

        // A replayed state carrying an OLDER version for peer-a must not reactivate them.
        val now = System.currentTimeMillis()
        repository.onInboundGroupWireFrame(
            "peer-b",
            GroupWireFrame.State(
                groupId, "peer-b", "op-replay", now, "Team", "my-device-id",
                listOf(
                    GroupWireFrame.RosterEntry(
                        deviceId = "my-device-id", displayName = "Kali", role = "owner",
                        joinedAt = now, membershipVersion = now, operationId = "op", isActive = true,
                    ),
                    GroupWireFrame.RosterEntry(
                        deviceId = "peer-a", displayName = "Peer A", role = "member",
                        joinedAt = now, membershipVersion = leaveVersion - 1, operationId = "op-old", isActive = true,
                    ),
                    GroupWireFrame.RosterEntry(
                        deviceId = "peer-b", displayName = "Peer B", role = "member",
                        joinedAt = now, membershipVersion = now, operationId = "op", isActive = true,
                    ),
                ),
            ),
        )
        assertEquals(false, memberDao.member(groupId, "peer-a")!!.isActive)
    }

    @Test
    fun `createGroup rejects untrusted members and persists a six-member bound`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val conversationDao = FakeConversationDao()
        val repository = newRepository(
            conversationDao = conversationDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-b"),
        )

        val bad = repository.createGroup("Team", setOf("peer-a", "stranger"))
        assertTrue(bad is FlashResult.Failure)

        val ok = repository.createGroup("Team", setOf("peer-a", "peer-b"))
        assertTrue(ok is FlashResult.Success)
        val groupId = (ok as FlashResult.Success).value
        assertEquals(3, memberDao.activeCount(groupId))
        assertEquals("Team", conversationDao.get(groupId)!!.title)
        assertTrue(conversationDao.get(groupId)!!.isGroup)
    }

    @Test
    fun `leave tombstone beats a replayed stale add until a newer re-add`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val repository = newRepository(
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-b"),
        )
        val groupId =
            (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value

        // peer-a leaves (inbound frame, version strictly above the create's ms timestamp).
        val leaveVersion = System.currentTimeMillis() + 1_000_000L
        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.Leave(groupId, "peer-a", "leave-op", leaveVersion, "peer-a"),
        )
        assertEquals(false, memberDao.member(groupId, "peer-a")!!.isActive)

        // A replayed STALE add (older than the leave) must NOT resurrect the member. It arrives
        // from peer-b, an active member, so it clears the sender gate and still loses on version.
        repository.onInboundGroupWireFrame(
            "peer-b",
            GroupWireFrame.Add(groupId, "peer-b", "stale-add", 1L, listOf("peer-a")),
        )
        assertEquals(false, memberDao.member(groupId, "peer-a")!!.isActive)

        // A strictly newer add from an active member reactivates the tombstoned peer.
        repository.onInboundGroupWireFrame(
            "peer-b",
            GroupWireFrame.Add(groupId, "peer-b", "re-add", Long.MAX_VALUE, listOf("peer-a")),
        )
        assertEquals(true, memberDao.member(groupId, "peer-a")!!.isActive)
    }

    @Test
    fun `untrusted or non-member group message is dropped without notification`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val notified = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repository = newRepository(
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-c"),
            onInboundTextMessage = { _, _, text -> notified.add(text) },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a")) as FlashResult.Success).value

        // An untrusted transport peer cannot address a group even with valid membership data.
        repository.onInboundGroupWireFrame(
            "stranger",
            GroupWireFrame.Message(groupId, "m1", "stranger", "Stranger", 1L, "hello"),
        )
        assertEquals(0, notified.size)

        // A trusted peer who is not an active member is also dropped (peer-c never joined).
        repository.onInboundGroupWireFrame(
            "peer-c",
            GroupWireFrame.Message(groupId, "m2", "peer-c", "Peer C", 1L, "hello"),
        )
        assertEquals(0, notified.size)
    }

    @Test
    fun `group text callback supplies stored title while direct callback supplies null`() = runBlocking {
        data class Inbound(val conversationId: String, val sender: String?, val text: String, val groupTitle: String?)

        val memberDao = FakeGroupMemberDao()
        val callbacks = java.util.Collections.synchronizedList(mutableListOf<Inbound>())
        val repository = newRepository(
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
            onInboundTextMessageWithGroupTitle = { conversationId, sender, text, groupTitle ->
                callbacks += Inbound(conversationId, sender, text, groupTitle)
            },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a")) as FlashResult.Success).value

        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.Message(groupId, "group-message", "peer-a", "Alex", 1L, "hello team"),
        )
        repository.onInboundWireFrame(
            MessageWireFrame.TextMessage(
                localId = "direct-message",
                conversationId = "my-device-id",
                senderId = "peer-a",
                senderName = "Alex",
                text = "hello direct",
                sentAt = 2L,
            ),
        )
        kotlinx.coroutines.delay(50)

        assertEquals(
            setOf(
                Inbound(groupId, "Alex", "hello team", "Team"),
                Inbound("peer-a", "Alex", "hello direct", null),
            ),
            callbacks.toSet(),
        )
    }

    @Test
    fun `group media callback supplies stored title and attachment metadata`() = runBlocking {
        data class Inbound(
            val conversationId: String,
            val sender: String?,
            val fileName: String,
            val mimeType: String,
            val groupTitle: String?,
        )

        val memberDao = FakeGroupMemberDao()
        val callbacks = java.util.Collections.synchronizedList(mutableListOf<Inbound>())
        val repository = newRepository(
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
            onInboundAttachmentWithGroupTitle = { conversationId, sender, fileName, mimeType, groupTitle ->
                callbacks += Inbound(conversationId, sender, fileName, mimeType, groupTitle)
            },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a")) as FlashResult.Success).value
        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.GroupMedia(
                groupId = groupId,
                messageId = "media-message",
                transferId = "media-transfer",
                wireFileId = "media-wire",
                from = "peer-a",
                senderName = "Alex",
                sentAt = 1L,
                fileName = "voice.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 42L,
            ),
        )
        repository.onInboundAttachment(
            peerDeviceId = "peer-a",
            transferId = "media-transfer",
            fileName = "fallback.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 42L,
        )
        kotlinx.coroutines.withTimeoutOrNull(2000L) {
            while (callbacks.isEmpty()) kotlinx.coroutines.delay(20)
        }

        assertEquals(
            listOf(Inbound(groupId, "Alex", "voice.m4a", "audio/mp4", "Team")),
            callbacks,
        )
    }

    @Test
    fun `group media intro uses explicit identity and rejects inactive member`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val messageDao = FakeMessageDao()
        val frames = mutableListOf<Pair<String, GroupWireFrame.GroupMedia>>()
        val repository = newRepository(
            messageDao = messageDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-b"),
            groupSink = { target, frame ->
                if (frame is GroupWireFrame.GroupMedia) frames += target to frame
                true
            },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value

        val sent = repository.beginGroupAttachment(
            groupId = groupId,
            recipientDeviceId = "peer-a",
            messageId = "shared-message",
            transferId = "recipient-transfer-a",
            wireFileId = "shared-wire-file",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 42L,
        )
        assertTrue(sent)
        assertEquals(1, frames.size)
        assertEquals("peer-a", frames.single().first)
        assertEquals("shared-message", frames.single().second.messageId)
        assertEquals("recipient-transfer-a", frames.single().second.transferId)
        assertEquals("shared-wire-file", frames.single().second.wireFileId)

        val sentToB = repository.beginGroupAttachment(
            groupId = groupId,
            recipientDeviceId = "peer-b",
            messageId = "shared-message",
            transferId = "recipient-transfer-b",
            wireFileId = "shared-wire-file",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 42L,
        )
        assertTrue(sentToB)
        assertEquals(2, frames.size)
        assertEquals(setOf("peer-a", "peer-b"), frames.map { it.first }.toSet())
        assertEquals(setOf("shared-message"), frames.map { it.second.messageId }.toSet())
        assertEquals(setOf("shared-wire-file"), frames.map { it.second.wireFileId }.toSet())
        assertEquals(setOf("recipient-transfer-a", "recipient-transfer-b"), frames.map { it.second.transferId }.toSet())

        repository.sendGroupAttachment(
            conversationId = groupId,
            messageId = "shared-message",
            transferId = "recipient-transfer-a",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 42L,
            localPath = "content://photo",
        )
        kotlinx.coroutines.delay(50)
        assertNotNull(messageDao.messages["shared-message"])
        assertEquals("recipient-transfer-a", messageDao.messages["shared-message"]!!.attachmentTransferId)

        memberDao.upsert(memberDao.member(groupId, "peer-b")!!.copy(isActive = false))
        val rejected = repository.beginGroupAttachment(
            groupId = groupId,
            recipientDeviceId = "peer-b",
            messageId = "shared-message",
            transferId = "rejected-transfer-b",
            wireFileId = "shared-wire-file",
            fileName = "photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 42L,
        )
        assertFalse(rejected)
        assertEquals(2, frames.size)
    }

    @Test
    fun `partial SyncAck retains remaining batch and final ack retires round`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val messageDao = FakeMessageDao()
        val repository = newRepository(
            messageDao = messageDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
            groupSink = { _, _ -> true },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a")) as FlashResult.Success).value
        messageDao.insert(msg("m-1", groupId, "one").copy(senderId = "my-device-id"))
        messageDao.insert(msg("m-2", groupId, "two").copy(senderId = "my-device-id", sentAt = System.currentTimeMillis() + 1))
        val request = GroupWireFrame.SyncRequest(
            groupId = groupId,
            syncId = "sync-batch",
            from = "peer-a",
            sinceSentAt = 0L,
            sinceMessageId = "",
            tier = com.transfer.flash.core.messaging.protocol.GroupSyncTier.MEDIUM,
            maxPerSecond = 20,
            maxTotal = 10,
        )
        repository.onInboundGroupWireFrame("peer-a", request)
        assertEquals(setOf("m-1", "m-2"), repository.pendingSyncMessageIds("sync-batch"))

        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.SyncAck(groupId, "sync-batch", "peer-a", listOf("m-1"), hasMore = true),
        )
        assertEquals(setOf("m-2"), repository.pendingSyncMessageIds("sync-batch"))

        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.SyncAck(groupId, "sync-batch", "peer-a", listOf("m-2"), hasMore = false),
        )
        assertTrue(repository.pendingSyncMessageIds("sync-batch").isEmpty())
    }

    @Test
    fun `SyncPush stores message and acks the frame sender`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val sent = mutableListOf<Pair<String, GroupWireFrame>>()
        val messageDao = FakeMessageDao()
        val repository = newRepository(
            messageDao = messageDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a"),
            groupSink = { target, frame -> sent += target to frame; true },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a")) as FlashResult.Success).value
        sent.clear()

        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.SyncPush(
                groupId = groupId,
                syncId = "sync-1",
                from = "peer-a",
                message = GroupWireFrame.Message(
                    groupId, "message-1", "peer-a", "Peer A", 123L, "catch up",
                ),
            ),
        )

        assertNotNull(messageDao.messages["message-1"])
        val (target, ackFrame) = sent.single()
        assertEquals("peer-a", target)
        val ack = ackFrame as GroupWireFrame.SyncAck
        assertEquals("sync-1", ack.syncId)
        assertEquals(listOf("message-1"), ack.messageIds)
        assertEquals("my-device-id", ack.from)
    }

    @Test
    fun `opening a group conversation renders a group header with the real name and roster`() =
        runBlocking {
            val memberDao = FakeGroupMemberDao()
            val conversationDao = FakeConversationDao()
            val repository = newRepository(
                conversationDao = conversationDao,
                groupMemberDao = memberDao,
                trustedPeers = setOf("peer-a", "peer-b"),
            )
            val groupId =
                (repository.createGroup("Design Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value

            repository.openConversation(groupId)
            // The seed (groupTitleCache) is isGroup=true with no roster — the combine stamps
            // DB truth on its first emission. Await the POPULATED header, not just isGroup.
            val header = kotlinx.coroutines.withTimeout(5_000) {
                repository.conversationState.first { it.header.isGroup && it.header.memberCount > 0 }
            }.header

            // The UUID must never win: the stored group name is the title.
            assertEquals("Design Team", header.title)
            assertTrue(header.isGroup)
            assertEquals(3, header.memberCount)
            assertEquals(true, header.showCallActions)

            // Phase B: the real roster rides the state — names, owner role, and the local device.
            val members = repository.conversationState.value.members
            assertEquals(3, members.size)
            assertEquals(setOf("my-device-id", "peer-a", "peer-b"), members.map { it.id }.toSet())
            assertEquals(FlashMemberRole.Owner, members.single { it.id == "my-device-id" }.role)
            assertEquals(FlashMemberRole.Member, members.single { it.id == "peer-a" }.role)
        }

    @Test
    fun `conversation mapping exposes delivery counts only for outbound group messages with rows`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val deliveryDao = FakeGroupDeliveryDao()
        val conversationDao = FakeConversationDao()
        val messageDao = FakeMessageDao()
        val repository = newRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            groupMemberDao = memberDao,
            groupDeliveryDao = deliveryDao,
            trustedPeers = setOf("peer-a", "peer-b"),
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value
        messageDao.insert(msg("outbound", groupId, "mine").copy(senderId = "my-device-id"))
        messageDao.insert(msg("inbound", groupId, "theirs").copy(senderId = "peer-a"))
        messageDao.insert(
            msg("media-without-deliveries", groupId, "").copy(
                senderId = "my-device-id",
                attachmentTransferId = "transfer-1",
                attachmentName = "photo.jpg",
                attachmentMime = "image/jpeg",
            ),
        )
        deliveryDao.insertAll(
            listOf(
                GroupDeliveryEntity("outbound", "peer-a", state = "DELIVERED", nextAttemptAt = 0L),
                GroupDeliveryEntity("outbound", "peer-b", nextAttemptAt = 0L),
                GroupDeliveryEntity("inbound", "peer-b", state = "DELIVERED", nextAttemptAt = 0L),
            ),
        )

        repository.openConversation(groupId)
        val messages = kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { state -> state.messages.size == 3 && state.header.isGroup }
        }.messages.associateBy { it.id }

        assertEquals(1, messages.getValue("outbound").deliveredTo)
        assertEquals(2, messages.getValue("outbound").deliveredTotal)
        assertNull(messages.getValue("inbound").deliveredTo)
        assertNull(messages.getValue("inbound").deliveredTotal)
        assertNull(messages.getValue("media-without-deliveries").deliveredTo)
        assertNull(messages.getValue("media-without-deliveries").deliveredTotal)

        deliveryDao.markDelivered("outbound", "peer-b", deliveredAt = 5L)
        val completed = kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { state ->
                state.messages.firstOrNull { it.id == "outbound" }?.deliveredTo == 2
            }
        }.messages.single { it.id == "outbound" }
        assertEquals(2, completed.deliveredTotal)
    }

    @Test
    fun `direct conversation mapping remains without group delivery counts`() = runBlocking {
        val messageDao = FakeMessageDao()
        val deliveryDao = FakeGroupDeliveryDao()
        val repository = newRepository(messageDao = messageDao, groupDeliveryDao = deliveryDao)
        messageDao.insert(msg("direct-out", "peer-a", "hello").copy(senderId = "my-device-id"))
        deliveryDao.insertAll(
            listOf(GroupDeliveryEntity("direct-out", "peer-a", state = "DELIVERED", nextAttemptAt = 0L)),
        )

        repository.openConversation("peer-a")
        val direct = kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { it.messages.any { message -> message.id == "direct-out" } }
        }.messages.single { it.id == "direct-out" }

        assertNull(direct.deliveredTo)
        assertNull(direct.deliveredTotal)
    }

    @Test
    fun `group send fans out per member and full quorum retires the outbox`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val deliveryDao = FakeGroupDeliveryDao()
        val outboxDao = FakeOutboxDao()
        val messageDao = FakeMessageDao()
        val groupFrames = java.util.Collections.synchronizedList(mutableListOf<Pair<String, GroupWireFrame>>())
        val repository = newRepository(
            messageDao = messageDao,
            outboxDao = outboxDao,
            groupMemberDao = memberDao,
            groupDeliveryDao = deliveryDao,
            trustedPeers = setOf("peer-a", "peer-b"),
            groupSink = { target, frame -> groupFrames.add(target to frame); true },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value

        repository.openConversation(groupId)
        repository.sendText("hello team")
        kotlinx.coroutines.delay(100)

        // One wire frame per recipient, not one shared frame.
        assertEquals(setOf("peer-a", "peer-b"), groupFrames.map { it.first }.toSet())
        assertEquals(1, outboxDao.queue.size)

        // First receipt: quorum not reached — outbox row stays (ERROR-031 commit rule).
        val messageId = outboxDao.queue.keys.first()
        repository.onInboundGroupWireFrame(
            "peer-a",
            GroupWireFrame.Receipt(groupId, messageId, "peer-a", System.currentTimeMillis()),
        )
        kotlinx.coroutines.delay(50)
        assertEquals(1, outboxDao.queue.size)

        // Second receipt completes the quorum: row retires, message reads DELIVERED.
        repository.onInboundGroupWireFrame(
            "peer-b",
            GroupWireFrame.Receipt(groupId, messageId, "peer-b", System.currentTimeMillis()),
        )
        kotlinx.coroutines.delay(50)
        assertEquals(0, outboxDao.queue.size)
        assertEquals("DELIVERED", messageDao.messages[messageId]!!.status)
        val mapped = kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { state ->
                state.messages.firstOrNull { it.id == messageId }?.deliveredTo == 2
            }
        }.messages.single { it.id == messageId }
        assertEquals(2, mapped.deliveredTotal)
    }

    @Test
    fun `direct typing keeps its single addressed frame behavior`() = runBlocking {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, MessageWireFrame>>())
        val repository = newRepository(
            messageSink = { target, frame -> sent += target to frame; true },
        )

        repository.openConversation("peer-a")
        repository.setTyping(true)
        kotlinx.coroutines.delay(50)

        assertEquals(1, sent.size)
        val (target, frame) = sent.single()
        assertEquals("peer-a", target)
        val typing = frame as MessageWireFrame.TypingFrame
        assertEquals("peer-a", typing.conversationId)
        assertEquals("my-device-id", typing.memberId)
        assertEquals("Kali", typing.memberName)
        assertTrue(typing.isTyping)
    }

    @Test
    fun `group typing fans out to active members except self using message transport`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val sent = java.util.Collections.synchronizedList(mutableListOf<Pair<String, MessageWireFrame>>())
        val groupFrames = java.util.Collections.synchronizedList(mutableListOf<Pair<String, GroupWireFrame>>())
        val repository = newRepository(
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-b"),
            groupSink = { target, frame -> groupFrames += target to frame; true },
            messageSink = { target, frame -> sent += target to frame; true },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value
        memberDao.upsert(memberDao.member(groupId, "peer-b")!!.copy(isActive = false))
        groupFrames.clear()

        repository.openConversation(groupId)
        repository.setTyping(true)
        kotlinx.coroutines.delay(50)

        assertEquals(listOf("peer-a"), sent.map { it.first })
        val typing = sent.single().second as MessageWireFrame.TypingFrame
        assertEquals(groupId, typing.conversationId)
        assertEquals("my-device-id", typing.memberId)
        assertTrue(typing.isTyping)
        assertTrue("TypingFrame must not be forced through GroupTransportSink", groupFrames.isEmpty())
    }

    @Test
    fun `group typing accepts trusted active member and drops inactive untrusted and spoofed senders`() = runBlocking {
        val memberDao = FakeGroupMemberDao()
        val conversationDao = FakeConversationDao()
        val repository = newRepository(
            conversationDao = conversationDao,
            groupMemberDao = memberDao,
            trustedPeers = setOf("peer-a", "peer-b"),
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a", "peer-b")) as FlashResult.Success).value
        repository.openConversation(groupId)
        kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { it.header.isGroup && it.header.memberCount > 0 }
        }

        suspend fun inbound(memberId: String, memberName: String, peerId: String) {
            repository.onInboundWireFrame(
                MessageWireFrame.TypingFrame(groupId, memberId, memberName, true, 1L),
                transportPeerId = peerId,
            )
        }

        inbound("peer-a", "Alex", "peer-a")
        kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { it.header.typingMemberNames == listOf("Alex") }
        }

        memberDao.upsert(memberDao.member(groupId, "peer-b")!!.copy(isActive = false))
        inbound("peer-b", "Inactive", "peer-b")
        inbound("stranger", "Untrusted", "stranger")
        inbound("peer-b", "Spoofed", "peer-a")
        kotlinx.coroutines.delay(50)

        assertEquals(listOf("Alex"), repository.conversationState.value.header.typingMemberNames)
    }

    @Test
    fun `direct inbound typing remains keyed to transport peer`() = runBlocking {
        val repository = newRepository()
        repository.openConversation("peer-a")

        repository.onInboundWireFrame(
            MessageWireFrame.TypingFrame("my-device-id", "peer-a", "Alex", true, 1L),
            transportPeerId = "peer-a",
        )
        kotlinx.coroutines.withTimeout(5_000) {
            repository.conversationState.first { it.header.typingMemberNames == listOf("Alex") }
        }

        assertEquals(listOf("Alex"), repository.conversationState.value.header.typingMemberNames)
    }

    @Test
    fun `conversation mapping assigns separators after tombstones are filtered`() = runBlocking {
        val messageDao = FakeMessageDao()
        val repository = newRepository(messageDao = messageDao)
        val now = System.currentTimeMillis()
        messageDao.insert(msg("today-1", "conv-days", "one").copy(sentAt = now - 1_000L))
        messageDao.insert(msg("today-2", "conv-days", "two").copy(sentAt = now - 2_000L))
        messageDao.insert(
            msg("deleted", "conv-days", "deleted").copy(
                sentAt = now - 86_400_000L,
                deletedAt = now,
            ),
        )
        messageDao.insert(msg("older", "conv-days", "old").copy(sentAt = now - 172_800_000L))

        repository.openConversation("conv-days")
        kotlinx.coroutines.delay(100)

        val messages = repository.conversationState.value.messages
        assertEquals(listOf("older", "today-2", "today-1"), messages.map { it.id })
        assertNotNull(messages[0].daySeparator)
        assertNotNull(messages[1].daySeparator)
        assertNull(messages[2].daySeparator)
        assertFalse(messages.any { it.id == "deleted" })
    }

    @Test
    fun `delete for everyone sends direct action only for the local author`() = runBlocking {
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        conversationDao.upsert(ConversationEntity(id = "peer-a", title = "Peer A", isGroup = false, sortOrder = 1L))
        messageDao.insert(msg("own", "peer-a", "mine").copy(senderId = "my-device-id"))
        messageDao.insert(msg("theirs", "peer-a", "theirs").copy(senderId = "peer-a"))
        outboxDao.enqueue(OutboxEntity("own", attempts = 0, nextAttemptAt = 0L, payloadJson = "mine", createdAt = System.currentTimeMillis()))
        val sent = mutableListOf<Pair<String, MessageWireFrame>>()
        val repository = newRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            messageSink = { target, frame -> sent += target to frame; true },
        )

        repository.deleteMessageForEveryone("theirs")
        repository.deleteMessageForEveryone("own")
        kotlinx.coroutines.delay(100)

        assertNull(messageDao.messages["theirs"]!!.deletedAt)
        assertNotNull(messageDao.messages["own"]!!.deletedAt)
        assertFalse(outboxDao.queue.containsKey("own"))
        val deleteFrames = sent.filter { it.second is MessageWireFrame.DeleteForEveryone }
        assertEquals("peer-a", deleteFrames.single().first)
        assertEquals(
            MessageWireFrame.DeleteForEveryone("own", "peer-a", "my-device-id"),
            deleteFrames.single().second,
        )
    }

    @Test
    fun `direct delete receiver rejects spoof and non-author then tombstones idempotently`() = runBlocking {
        val messageDao = FakeMessageDao()
        val outboxDao = FakeOutboxDao()
        messageDao.insert(msg("m-direct", "peer-a", "hello").copy(senderId = "peer-a"))
        outboxDao.enqueue(OutboxEntity("m-direct", attempts = 0, nextAttemptAt = 0L, payloadJson = "hello", createdAt = System.currentTimeMillis()))
        val repository = newRepository(messageDao = messageDao, outboxDao = outboxDao)
        val valid = MessageWireFrame.DeleteForEveryone("m-direct", "peer-a", "peer-a")

        repository.onInboundWireFrame(valid, transportPeerId = "spoof")
        repository.onInboundWireFrame(valid.copy(from = "peer-b"), transportPeerId = "peer-b")
        assertNull(messageDao.messages["m-direct"]!!.deletedAt)

        repository.onInboundWireFrame(valid, transportPeerId = "peer-a")
        val firstDeletedAt = messageDao.messages["m-direct"]!!.deletedAt
        repository.onInboundWireFrame(valid, transportPeerId = "peer-a")

        assertNotNull(firstDeletedAt)
        assertEquals(firstDeletedAt, messageDao.messages["m-direct"]!!.deletedAt)
        assertFalse(outboxDao.queue.containsKey("m-direct"))
    }

    @Test
    fun `group delete fans out trusted active members and receiver drops spoof untrusted nonmember`() = runBlocking {
        val members = FakeGroupMemberDao()
        val messageDao = FakeMessageDao()
        val conversationDao = FakeConversationDao()
        val outboxDao = FakeOutboxDao()
        val sent = mutableListOf<Pair<String, GroupWireFrame>>()
        val repository = newRepository(
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            groupMemberDao = members,
            trustedPeers = setOf("peer-a", "peer-b", "peer-inactive", "trusted-nonmember"),
            groupSink = { target, frame -> sent += target to frame; true },
        )
        val groupId = (repository.createGroup("Team", setOf("peer-a", "peer-b", "peer-inactive")) as FlashResult.Success).value
        members.upsert(members.member(groupId, "peer-inactive")!!.copy(isActive = false))
        messageDao.insert(msg("own-group", groupId, "mine").copy(senderId = "my-device-id"))

        repository.deleteMessageForEveryone("own-group")
        kotlinx.coroutines.delay(100)

        val deleteTargets = sent.filter { it.second is GroupWireFrame.DeleteForEveryone }.map { it.first }.toSet()
        assertEquals(setOf("peer-a", "peer-b"), deleteTargets)
        assertNotNull(messageDao.messages["own-group"]!!.deletedAt)

        messageDao.insert(msg("remote-group", groupId, "remote").copy(senderId = "peer-a"))
        val valid = GroupWireFrame.DeleteForEveryone(groupId, "remote-group", "peer-a")
        repository.onInboundGroupWireFrame("peer-b", valid)
        repository.onInboundGroupWireFrame("stranger", valid.copy(from = "stranger"))
        repository.onInboundGroupWireFrame(
            "trusted-nonmember",
            valid.copy(from = "trusted-nonmember"),
        )
        repository.onInboundGroupWireFrame("peer-inactive", valid.copy(from = "peer-inactive"))
        assertNull(messageDao.messages["remote-group"]!!.deletedAt)

        repository.onInboundGroupWireFrame("peer-a", valid)
        val firstGroupDeletedAt = messageDao.messages["remote-group"]!!.deletedAt
        repository.onInboundGroupWireFrame("peer-a", valid)
        assertNotNull(firstGroupDeletedAt)
        assertEquals(firstGroupDeletedAt, messageDao.messages["remote-group"]!!.deletedAt)
    }

    // ------------------------------------------------ late-joiner diagnosis (DIAG, no prod change)

    /**
     * DIAGNOSTIC HARNESS — models three devices as three repositories wired by one fake transport.
     *
     * Every `groupTransportSink` send is recorded as a [Hop] and delivered to the target repository
     * (`onInboundGroupWireFrame(peerDeviceId = from)`), but ONLY while the two devices are linked.
     * That mirrors a real `WsSession`: a frame written while the session is down is gone unless
     * something durable re-sends it, and nothing in the harness retries on its own.
     */
    private class FakeMesh {
        data class Hop(
            val from: String,
            val target: String,
            val frame: GroupWireFrame,
            val delivered: Boolean,
        )

        private val repositories = ConcurrentHashMap<String, RealFlashChatRepository>()
        private val links = ConcurrentHashMap.newKeySet<String>()
        private val recorded = java.util.Collections.synchronizedList(mutableListOf<Hop>())

        fun register(deviceId: String, repository: RealFlashChatRepository) {
            repositories[deviceId] = repository
        }

        fun link(a: String, b: String) {
            links.add(linkKey(a, b))
        }

        fun unlink(a: String, b: String) {
            links.remove(linkKey(a, b))
        }

        private fun linkKey(a: String, b: String): String = listOf(a, b).sorted().joinToString("|")

        suspend fun send(from: String, target: String, frame: GroupWireFrame): Boolean {
            val receiver = repositories[target]
            val delivered = receiver != null && linkKey(from, target) in links
            recorded.add(Hop(from, target, frame, delivered))
            if (delivered) receiver!!.onInboundGroupWireFrame(from, frame)
            return delivered
        }

        fun snapshot(): List<Hop> = synchronized(recorded) { recorded.toList() }
    }

    /** One device under test: its repository plus the fakes its state can be read back from. */
    private class Peer(
        val deviceId: String,
        val messageDao: FakeMessageDao,
        val memberDao: FakeGroupMemberDao,
        val deliveryDao: FakeGroupDeliveryDao,
        val repository: RealFlashChatRepository,
    ) {
        fun activeRoster(groupId: String): List<String> = memberDao.members.values
            .filter { it.groupId == groupId && it.isActive }
            .map { it.deviceId }
            .sorted()

        fun history(groupId: String): List<String> = messageDao.messages.values
            .filter { it.conversationId == groupId && it.deletedAt == null }
            .sortedBy { it.sentAt }
            .map { "${it.senderId}:'${it.text}'" }

        fun hasText(groupId: String, text: String): Boolean = messageDao.messages.values
            .any { it.conversationId == groupId && it.text == text && it.deletedAt == null }

        fun deliveryRowsFor(memberId: String): List<GroupDeliveryEntity> =
            deliveryDao.rows.values.filter { it.memberId == memberId }
    }

    private fun newPeer(deviceId: String, mesh: FakeMesh): Peer {
        val messageDao = FakeMessageDao()
        val memberDao = FakeGroupMemberDao()
        val deliveryDao = FakeGroupDeliveryDao()
        val repository = newRepository(
            localDeviceId = deviceId,
            messageDao = messageDao,
            groupMemberDao = memberDao,
            groupDeliveryDao = deliveryDao,
            trustedPeers = setOf("dev-a", "dev-b", "dev-c"),
            groupSink = { target, frame -> mesh.send(deviceId, target, frame) },
        )
        mesh.register(deviceId, repository)
        return Peer(deviceId, messageDao, memberDao, deliveryDao, repository)
    }

    /** Fire-and-forget group sends hop through `scope.launch(ioDispatcher)`, so give them a window. */
    private suspend fun settle(ms: Long = 300L) {
        kotlinx.coroutines.delay(ms)
    }

    private suspend fun awaitText(peer: Peer, groupId: String, text: String) {
        kotlinx.coroutines.withTimeoutOrNull(3_000L) {
            while (!peer.hasText(groupId, text)) kotlinx.coroutines.delay(20L)
        }
    }

    private fun describeHop(hop: FakeMesh.Hop): String {
        val detail = when (val frame = hop.frame) {
            is GroupWireFrame.Create -> "members=${frame.memberIds}"
            is GroupWireFrame.Add -> "added=${frame.memberIds}"
            is GroupWireFrame.State -> "roster=${frame.members.map { it.deviceId }}"
            is GroupWireFrame.Message -> "text='${frame.text}'"
            is GroupWireFrame.SyncRequest -> "sinceSentAt=${frame.sinceSentAt}"
            else -> ""
        }
        return "${hop.frame::class.simpleName}(from=${hop.from}, delivered=${hop.delivered}, $detail)"
    }

    /** The observed-facts dump the diagnosis report is built from. Never asserts anything itself. */
    private fun lateJoinDiagnosis(
        title: String,
        mesh: FakeMesh,
        peers: List<Peer>,
        groupId: String,
    ): String {
        val hops = mesh.snapshot()
        val lines = mutableListOf<String>()
        lines += "----- $title (groupId=$groupId) -----"
        peers.forEach { peer ->
            val inbound = hops.filter { it.target == peer.deviceId && it.frame !is GroupWireFrame.Receipt }
            lines += "[${peer.deviceId}] roster(active)=${peer.activeRoster(groupId)}"
            lines += "[${peer.deviceId}] history=${peer.history(groupId)}"
            lines += "[${peer.deviceId}] frames addressed to it: " + inbound
                .groupBy { describeHop(it) }
                .map { (desc, group) -> if (group.size == 1) desc else "$desc x${group.size}" }
                .joinToString("; ")
            lines += "[${peer.deviceId}] delivery rows it owns for dev-c: " + peer
                .deliveryRowsFor("dev-c")
                .map { row -> "('${peer.messageDao.messages[row.messageId]?.text ?: row.messageId}', state=${row.state})" }
                .joinToString("; ")
        }
        val membership = hops.filter {
            it.frame is GroupWireFrame.Create || it.frame is GroupWireFrame.Add ||
                it.frame is GroupWireFrame.State
        }
        lines += "membership frames: " + membership
            .groupBy { describeHop(it) }
            .map { (desc, group) -> if (group.size == 1) desc else "$desc x${group.size}" }
            .joinToString("; ")
        val syncRequests = hops.filter { it.frame is GroupWireFrame.SyncRequest }
        lines += "SyncRequest frames: " + if (syncRequests.isEmpty()) {
            "NONE issued for any group"
        } else {
            syncRequests.joinToString("; ") { describeHop(it) }
        }
        return lines.joinToString("\n")
    }

    /**
     * VARIANT 1 — A (owner) creates a group with B, then adds C while everyone is connected.
     *
     * Intended: C ends up with the pre-join history and receives the later messages. Observed on
     * this test failing means the F5 join-time catch-up regressed.
     */
    @Test
    // F7 (2026-09-11): intended-behaviour test - live again now that the join-time catch-up lands.
    fun `DIAG variant 1 - late joiner with every device connected`() = runBlocking {
        val mesh = FakeMesh()
        val a = newPeer("dev-a", mesh)
        val b = newPeer("dev-b", mesh)
        val c = newPeer("dev-c", mesh)
        mesh.link("dev-a", "dev-b")
        mesh.link("dev-a", "dev-c")
        mesh.link("dev-b", "dev-c")

        val groupId = (a.repository.createGroup("Team", setOf("dev-b")) as FlashResult.Success).value
        settle()
        // Pre-join history: the messages C is supposed to catch up on.
        a.repository.openConversation(groupId)
        a.repository.sendText("A before join")
        awaitText(a, groupId, "A before join")
        b.repository.openConversation(groupId)
        b.repository.sendText("B before join")
        awaitText(b, groupId, "B before join")
        settle()

        // A adds C: existing member B gets the Add, newcomer C gets the full State bootstrap.
        // F7: the join-time catch-up round pushes after BACKUP_DELAY_MS (2 s).
        a.repository.addGroupMembers(groupId, setOf("dev-c"))
        settle(1500L)

        a.repository.sendText("A after join")
        settle()
        b.repository.sendText("B after join")
        settle()

        val report = lateJoinDiagnosis("VARIANT 1 all connected", mesh, listOf(a, b, c), groupId)
        val failed = mutableListOf<String>()
        val expect: (String, Boolean) -> Unit = { what, ok -> if (!ok) failed += what }
        expect("C's history lacks A's pre-join message", c.hasText(groupId, "A before join"))
        expect("C's history lacks B's pre-join message", c.hasText(groupId, "B before join"))
        expect("C's history lacks A's post-join message", c.hasText(groupId, "A after join"))
        expect("C's history lacks B's post-join message", c.hasText(groupId, "B after join"))
        expect("C's roster is not the full group", c.activeRoster(groupId) == listOf("dev-a", "dev-b", "dev-c"))
        expect("B's roster does not contain C", "dev-c" in b.activeRoster(groupId))
        expect("no delivery row for C from A", a.deliveryRowsFor("dev-c").isNotEmpty())
        expect("no delivery row for C from B", b.deliveryRowsFor("dev-c").isNotEmpty())
        assertTrue(
            "VARIANT 1 intended behaviour violated: ${failed.joinToString("; ")}\n$report",
            failed.isEmpty(),
        )
    }

    /**
     * VARIANT 2 — same, but B's link is down while A adds C; B reconnects afterwards and both hosts
     * run their session-up hooks (Flash.kt:425-427 / DiscoveryEngineHolder.kt:1158-1161).
     *
     * Intended: C still gets the history, and the reconnected B still reaches C with new messages.
     */
    @Test
    // F7 (2026-09-11): intended-behaviour test - live again now that the join-time catch-up lands.
    fun `DIAG variant 2 - late joiner while an existing member is offline`() = runBlocking {
        val mesh = FakeMesh()
        val a = newPeer("dev-a", mesh)
        val b = newPeer("dev-b", mesh)
        val c = newPeer("dev-c", mesh)
        mesh.link("dev-a", "dev-b")
        mesh.link("dev-a", "dev-c")
        mesh.link("dev-b", "dev-c")

        val groupId = (a.repository.createGroup("Team", setOf("dev-b")) as FlashResult.Success).value
        settle()
        a.repository.openConversation(groupId)
        a.repository.sendText("A before join")
        awaitText(a, groupId, "A before join")
        b.repository.openConversation(groupId)
        b.repository.sendText("B before join")
        awaitText(b, groupId, "B before join")
        settle()

        // B drops off the mesh, then A adds C: the Add frame for B has no durable backing.
        mesh.unlink("dev-a", "dev-b")
        a.repository.addGroupMembers(groupId, setOf("dev-c"))
        settle(1500L)

        // B comes back; both hosts fire their session-up hooks exactly as the engine does.
        mesh.link("dev-a", "dev-b")
        b.repository.notifyPeerSessionUp("dev-a")
        b.repository.sendGroupSyncRequests("dev-a")
        b.repository.reconcileGroupMembership("dev-a")
        a.repository.notifyPeerSessionUp("dev-b")
        a.repository.sendGroupSyncRequests("dev-b")
        a.repository.reconcileGroupMembership("dev-b")
        settle(600L) // one sync round's claim window is GroupPolicy.CLAIM_WINDOW_MS = 300ms

        a.repository.sendText("A after join")
        settle()
        b.repository.sendText("B after join")
        settle()

        val report = lateJoinDiagnosis("VARIANT 2 B offline during add", mesh, listOf(a, b, c), groupId)
        val failed = mutableListOf<String>()
        val expect: (String, Boolean) -> Unit = { what, ok -> if (!ok) failed += what }
        expect("C's history lacks A's pre-join message", c.hasText(groupId, "A before join"))
        expect("C's history lacks B's pre-join message", c.hasText(groupId, "B before join"))
        expect("C's history lacks A's post-join message", c.hasText(groupId, "A after join"))
        expect("C's history lacks B's post-join message", c.hasText(groupId, "B after join"))
        expect("B's roster does not contain C after reconnecting", "dev-c" in b.activeRoster(groupId))
        expect("C's roster is not the full group", c.activeRoster(groupId) == listOf("dev-a", "dev-b", "dev-c"))
        expect("no delivery row for C from A", a.deliveryRowsFor("dev-c").isNotEmpty())
        expect("no delivery row for C from B", b.deliveryRowsFor("dev-c").isNotEmpty())
        assertTrue(
            "VARIANT 2 intended behaviour violated: ${failed.joinToString("; ")}\n$report",
            failed.isEmpty(),
        )
    }

    /** Shared construction for the ERROR-034 tests; every DAO is an in-memory fake. */
    private fun newRepository(
        localDeviceId: String = "my-device-id",
        messageDao: MessageDao = FakeMessageDao(),
        conversationDao: ConversationDao = FakeConversationDao(),
        outboxDao: OutboxDao = FakeOutboxDao(),
        groupMemberDao: GroupMemberDao? = null,
        groupDeliveryDao: GroupDeliveryDao? = null,
        trustedPeers: Set<String> = emptySet(),
        onlinePeerIds: Flow<Set<String>> = MutableStateFlow(trustedPeers + setOf("peer-a", "peer-b", "peer-c", "dev-a", "dev-b", "dev-c")),
        groupSink: (suspend (String, GroupWireFrame) -> Boolean)? = null,
        messageSink: suspend (String, MessageWireFrame) -> Boolean = { _, _ -> true },
        onInboundTextMessage: (String, String?, String) -> Unit = { _, _, _ -> },
        onInboundTextMessageWithGroupTitle: (String, String?, String, String?) -> Unit =
            { conversationId, senderName, text, _ ->
                onInboundTextMessage(conversationId, senderName, text)
            },
        onInboundAttachmentWithGroupTitle: (String, String?, String, String, String?) -> Unit =
            { _, _, _, _, _ -> },
    ) = RealFlashChatRepository(
        localDeviceId = localDeviceId,
        localDisplayName = "Kali",
        messageDao = messageDao,
        conversationDao = conversationDao,
        outboxDao = outboxDao,
        receiptDao = FakeReceiptDao(),
        draftDao = FakeDraftDao(),
        recentSearchDao = FakeRecentSearchDao(),
        reactionDao = FakeReactionDao(),
        groupMemberDao = groupMemberDao,
        groupDeliveryDao = groupDeliveryDao,
        isTrustedPeer = { it in trustedPeers || it == localDeviceId },
        groupTransportSink = groupSink?.let { sink -> GroupTransportSink { target, frame -> sink(target, frame) } },
        transportSink = MessageTransportSink { target, frame -> messageSink(target, frame) },
        onlinePeerIds = onlinePeerIds,
        ioDispatcher = testDispatcher,
        onInboundTextMessage = onInboundTextMessage,
        onInboundTextMessageWithGroupTitle = onInboundTextMessageWithGroupTitle,
        onInboundAttachmentWithGroupTitle = onInboundAttachmentWithGroupTitle,
    )

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
