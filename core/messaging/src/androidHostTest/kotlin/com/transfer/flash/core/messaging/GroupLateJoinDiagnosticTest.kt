package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashResult
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * DIAGNOSTIC HARNESS (2026-09-11) — **investigation only, no production code was changed.**
 *
 * Reproduces the owner-reported defect *"after adding a user to a group later, it doesn't sync
 * messages, and new messages don't arrive [for that user]"* against THREE live
 * [RealFlashChatRepository] instances wired to each other through their real
 * `groupTransportSink`, i.e. the same ingress the hosts use (`onInboundGroupWireFrame`).
 *
 * What this harness models faithfully, and what it deliberately does not:
 * - Faithful: three devices with independent Room-shaped DAO fakes, the real outbox +
 *   `group_deliveries` retry fan-out, the real `FLASH_GROUP`/`FLASH_GMSG` frames, session-up
 *   edges (`notifyPeerSessionUp` + `sendGroupSyncRequests`, exactly what `Flash.kt:425-427` and
 *   `DiscoveryEngineHolder.kt:1158-1161` do), and the "no active session → sink returns false
 *   and the frame is gone" behaviour of both hosts (`Flash.kt:334-341`,
 *   `DiscoveryEngineHolder.kt:819-825`).
 * - Not modelled: real sockets/WS, the `sendTextAsync` fire-and-forget hop, mDNS/roaming, the
 *   app-layer UI. Frames are delivered by [drain] in FIFO order (the queue stands in for the
 *   wire), so no repository coroutine ever re-enters itself synchronously.
 *
 * Every test prints a `[DIAG]` report of the frames each device observed, which is the actual
 * evidence (see the `system-out` of the JUnit XML).
 *
 * Test status meanings:
 * - A test asserting INTENDED behaviour that FAILS on this tree is marked `@Ignore` with the
 *   finding in the comment (the build must stay green; the reproduction survives for the fix).
 * - `DIAGNOSTIC:`-prefixed tests pin the *currently observed* (defective) behaviour so the
 *   mechanism is verifiable in CI. **They are not desired behaviour — rewrite or delete them
 *   when the corresponding fix lands.**
 */
class GroupLateJoinDiagnosticTest {

    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val names = mapOf("dev-a" to "Ada", "dev-b" to "Bo", "dev-c" to "Cy")
    private val nodes = LinkedHashMap<String, Node>()

    /** The wire: frames a sender handed to its transport sink, awaiting delivery. */
    private val wire = ConcurrentLinkedQueue<Envelope>()

    /** Frames handed to a sink whose target had NO live session — dropped by the host. */
    private val droppedFrames = ConcurrentLinkedQueue<Envelope>()

    /** Every frame a sink was asked to send (delivered or dropped). */
    private val outbound = ConcurrentLinkedQueue<Envelope>()

    /** Every frame a repository actually ingested (what the receiver really saw). */
    private val inbound = ConcurrentLinkedQueue<Envelope>()

    /** Symmetric "live session" set; without it the sink reports false, like both hosts. */
    private val liveSessions = ConcurrentHashMap.newKeySet<String>()

    /** Every repository scope this test created — cancelled in [tearDown] so the outbox drain
     *  loops and armed sync timers of a finished test cannot load the JVM the next class runs in
     *  (ERROR-019: this suite's timing tests flake under CPU pressure). */
    private val repoScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        repoScopes.forEach { it.cancel() }
        dispatcher.close()
    }

    // ---------------------------------------------------------------- scenario 1+2: late join

    @Test
    // F7 (2026-09-11): intended-behaviour test - live again now that the join-time catch-up lands.
    fun `late joiner ends up with the pre-join history and receives post-join messages`() =
        runBlocking {
            val scenario = lateJoinScenario()

            // The F2 State bootstrap works: the newcomer materializes the group.
            val cGroup = nodes.getValue("dev-c").conversationDao.conversations[scenario.groupId]
            assertTrue("newcomer must have materialized the group conversation", cGroup != null)
            assertTrue("newcomer's conversation must be a group", cGroup!!.isGroup)

            val observed = textsOn("dev-c", scenario.groupId)
            // The "new messages don't arrive" half — this one is satisfied on this tree.
            assertEquals(
                "newcomer must receive every message sent after it was added",
                setOf(POST_FROM_B, POST_FROM_A),
                observed.filter { it != PRE_JOIN }.toSet(),
            )
            // The "it doesn't sync messages" half — this is the defect.
            assertEquals(
                "newcomer must end up with the pre-join history too " +
                    "(observed=$observed, " +
                    "inboundFrames=${inbound.filter { it.to == "dev-c" }.map { it.frame.javaClass.simpleName }}, " +
                    "syncRequests=${outbound.count { it.from == "dev-c" && it.frame is GroupWireFrame.SyncRequest }})",
                setOf(PRE_JOIN, POST_FROM_B, POST_FROM_A),
                observed.toSet(),
            )
        }

    @Test
    fun `late joiner is given the pre-join history by the join itself`() = runBlocking {
        val scenario = lateJoinScenario()
        report("late joiner, NO session-up edge after the add")

        // OBSERVED: post-join fan-out reaches the newcomer...
        assertTrue(
            "post-join message from the original member must arrive",
            POST_FROM_B in textsOn("dev-c", scenario.groupId),
        )
        assertTrue(
            "post-join message from the adder must arrive",
            POST_FROM_A in textsOn("dev-c", scenario.groupId),
        )
        // F7: the join itself triggers the catch-up - no session-up edge is required.
        assertTrue(
            "pre-join history must be synced by the join itself",
            PRE_JOIN in textsOn("dev-c", scenario.groupId),
        )
        assertTrue(
            "the newcomer emits catch-up requests for the group it has just learned",
            syncRequestsFrom("dev-c").isNotEmpty(),
        )
        assertTrue(
            "the adder DID send the State bootstrap",
            outbound.any { it.from == "dev-a" && it.to == "dev-c" && it.frame is GroupWireFrame.State },
        )
    }

    @Test
    fun `a session-up edge after post-join traffic still finds the history already complete`() =
        runBlocking {
            val scenario = lateJoinScenario()
            assertTrue(
                "precondition: the join-time catch-up already delivered the history",
                PRE_JOIN in textsOn("dev-c", scenario.groupId),
            )

            disconnect("dev-a", "dev-c")
            connect("dev-a", "dev-c")
            settleLong()
            report("late joiner AFTER a session-up edge")

            assertTrue(
                "the session-up edge still requests catch-up",
                syncRequestsFrom("dev-c").isNotEmpty(),
            )
            assertEquals(
                "the newcomer holds the full history exactly once",
                setOf(PRE_JOIN, POST_FROM_B, POST_FROM_A),
                textsOn("dev-c", scenario.groupId).toSet(),
            )
        }

    @Test
    fun `DIAGNOSTIC an immediate session-up edge right after the add DOES sync the history`() =
        runBlocking {
            // Same code path, same edge type — the only difference from the test above is that
            // the newcomer still holds ZERO group messages when it reconnects, so its catch-up
            // cursor is (0, ""). That isolates the CURSOR from the TRIGGER.
            val scenario = lateJoinScenario(flapRightAfterAdd = true)
            report("late joiner, session-up edge BEFORE any post-join message")

            assertEquals(
                "the newcomer's cursor was empty when it asked",
                0L,
                syncRequestsFrom("dev-c")
                    .map { (it.frame as GroupWireFrame.SyncRequest).sinceSentAt }
                    .minOrNull(),
            )
            assertTrue(
                "with an empty cursor the holder DOES return the pre-join history",
                PRE_JOIN in textsOn("dev-c", scenario.groupId),
            )
            // The same newcomer still receives the later fan-out, exactly once each.
            assertTrue(POST_FROM_B in textsOn("dev-c", scenario.groupId))
            assertTrue(POST_FROM_A in textsOn("dev-c", scenario.groupId))
        }

    // ---------------------------------------------- scenario 3: newcomer has no session at add

    @Test
    fun `newcomer that was offline at add time is healed by the session-up edge`() =
        runBlocking {
            val a = node("dev-a")
            node("dev-b")
            node("dev-c")
            connect("dev-a", "dev-b")
            settle()

            val groupId = (a.repo.createGroup("Team", setOf("dev-b")) as FlashResult.Success).value
            settle()

            a.repo.addGroupMembers(groupId, setOf("dev-c"))
            settle()
            assertEquals(
                "the State bootstrap is attempted exactly once",
                1,
                outbound.count { it.from == "dev-a" && it.to == "dev-c" && it.frame is GroupWireFrame.State },
            )
            assertEquals(
                "the sink dropped it - exactly what Flash.kt:337 / DiscoveryEngineHolder.kt:821 do",
                1,
                droppedFrames.count { it.to == "dev-c" && it.frame is GroupWireFrame.State },
            )

            // F7: the newcomer comes online; the session-up edge re-sends the roster.
            connect("dev-a", "dev-c")
            settleLong()

            assertTrue(
                "the newcomer materializes the group on reconnect",
                nodes.getValue("dev-c").conversationDao.conversations[groupId] != null,
            )
            assertTrue(
                "the roster is re-sent to the newcomer",
                inbound.count { it.to == "dev-c" && it.frame is GroupWireFrame.Membership } >= 1,
            )
            assertTrue(
                "the newcomer holds the roster",
                "dev-c" in activeMembers("dev-c", groupId),
            )
            report("newcomer offline at add time, healed on reconnect")

            a.repo.openConversation(groupId)
            a.repo.sendText("after-came-online")
            settle()
            assertTrue(
                "a later message now reaches the healed newcomer",
                "after-came-online" in textsOn("dev-c", groupId),
            )
        }

    // ------------------------------------------------ scenario 4: member offline at add time

    @Test
    fun `member offline at add time converges on reconnect and reaches the newcomer`() =
        runBlocking {
            val a = node("dev-a")
            val b = node("dev-b")
            val c = node("dev-c")
            connect("dev-a", "dev-b")
            connect("dev-a", "dev-c")
            connect("dev-b", "dev-c")
            settle()

            val groupId = (a.repo.createGroup("Team", setOf("dev-b")) as FlashResult.Success).value
            settle()

            // dev-b goes offline, then the adder adds dev-c.
            disconnect("dev-a", "dev-b")
            a.repo.addGroupMembers(groupId, setOf("dev-c"))
            settle()
            assertEquals(
                "dev-b's Add frame was dropped (no session)",
                1,
                droppedFrames.count { it.to == "dev-b" && it.frame is GroupWireFrame.Add },
            )
            assertEquals(
                "the newcomer DID get the State bootstrap",
                setOf("dev-a", "dev-b", "dev-c"),
                activeMembers("dev-c", groupId),
            )

            // F7: dev-b returns and the session-up edge reconciles the roster.
            connect("dev-a", "dev-b")
            settleLong()
            assertEquals(
                "dev-b's roster converges after reconnecting",
                setOf("dev-a", "dev-b", "dev-c"),
                activeMembers("dev-b", groupId),
            )

            b.repo.openConversation(groupId)
            b.repo.sendText("from-b")
            a.repo.openConversation(groupId)
            a.repo.sendText("from-a")
            c.repo.openConversation(groupId)
            c.repo.sendText("from-c")
            settle()
            report("member offline at add time, healed on reconnect")

            assertTrue("dev-a's message reaches the newcomer", "from-a" in textsOn("dev-c", groupId))
            assertTrue("dev-b's message now reaches the newcomer", "from-b" in textsOn("dev-c", groupId))
            assertTrue("the newcomer's message reaches dev-a", "from-c" in textsOn("dev-a", groupId))
            assertTrue(
                "the newcomer's message reaches the converged member",
                "from-c" in textsOn("dev-b", groupId),
            )
        }

    // ------------------------------------------------------------------------- the scenario

    private suspend fun lateJoinScenario(flapRightAfterAdd: Boolean = false): Scenario {
        val a = node("dev-a")
        node("dev-b")
        node("dev-c")
        // Production ordering: LAN sessions come up when peers connect, long before this group.
        connect("dev-a", "dev-b")
        connect("dev-a", "dev-c")
        connect("dev-b", "dev-c")
        settle()

        val groupId = (a.repo.createGroup("Team", setOf("dev-b")) as FlashResult.Success).value
        settle()

        node("dev-b").repo.openConversation(groupId)
        node("dev-b").repo.sendText(PRE_JOIN)
        settle()
        assertEquals(
            "precondition: the original member's message reached the adder",
            listOf(PRE_JOIN),
            textsOn("dev-a", groupId),
        )

        // The add. No session changes from here on unless the caller makes them.
        // F7: the join-time catch-up round is pushed after GroupPolicy.BACKUP_DELAY_MS (2 s),
        // so this window has to be long enough for the round to land.
        a.repo.addGroupMembers(groupId, setOf("dev-c"))
        settleLong()

        if (flapRightAfterAdd) {
            // The newcomer's only chance to catch up before it holds any post-join traffic.
            disconnect("dev-a", "dev-c")
            connect("dev-a", "dev-c")
            settleLong()
        }

        node("dev-b").repo.sendText(POST_FROM_B)
        a.repo.openConversation(groupId)
        a.repo.sendText(POST_FROM_A)
        settle()
        return Scenario(groupId)
    }

    // ------------------------------------------------------------------------ the wire fakes

    private class Node(
        val id: String,
        val messageDao: DiagnosticMessageDao = DiagnosticMessageDao(),
        val conversationDao: DiagnosticConversationDao = DiagnosticConversationDao(),
        val outboxDao: DiagnosticOutboxDao = DiagnosticOutboxDao(),
        val memberDao: DiagnosticGroupMemberDao = DiagnosticGroupMemberDao(),
        val deliveryDao: DiagnosticGroupDeliveryDao = DiagnosticGroupDeliveryDao(),
    ) {
        lateinit var repo: RealFlashChatRepository
    }

    private data class Envelope(val from: String, val to: String, val frame: GroupWireFrame)

    private inner class Scenario(val groupId: String)

    private fun node(id: String): Node = nodes.getOrPut(id) {
        val created = Node(id)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        repoScopes += scope
        created.repo = RealFlashChatRepository(
            localDeviceId = id,
            localDisplayName = names.getValue(id),
            messageDao = created.messageDao,
            conversationDao = created.conversationDao,
            outboxDao = created.outboxDao,
            receiptDao = NoopReceiptDao(),
            draftDao = NoopDraftDao(),
            recentSearchDao = NoopRecentSearchDao(),
            reactionDao = NoopReactionDao(),
            groupMemberDao = created.memberDao,
            groupDeliveryDao = created.deliveryDao,
            isTrustedPeer = { it in names.keys },
            groupTransportSink = GroupTransportSink { target, frame -> transmit(id, target, frame) },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
        )
        created
    }

    /** The host sink: no live session → the frame is dropped and `false` is reported. */
    private fun transmit(from: String, to: String, frame: GroupWireFrame): Boolean {
        val envelope = Envelope(from, to, frame)
        outbound.add(envelope)
        if (!isConnected(from, to)) {
            droppedFrames.add(envelope)
            return false
        }
        wire.add(envelope)
        return true
    }

    private fun pairKey(x: String, y: String): String = listOf(x, y).sorted().joinToString("|")

    private fun isConnected(x: String, y: String): Boolean = pairKey(x, y) in liveSessions

    private fun disconnect(x: String, y: String) {
        liveSessions.remove(pairKey(x, y))
    }

    /**
     * A live session, plus the session-up edge both hosts run: `Flash.kt:425-427` and the F7
     * membership reconciliation that ships beside it (`reconcileGroupMembership`).
     */
    private suspend fun connect(x: String, y: String) {
        liveSessions.add(pairKey(x, y))
        nodes.getValue(x).repo.notifyPeerSessionUp(y)
        nodes.getValue(x).repo.sendGroupSyncRequests(y)
        nodes.getValue(x).repo.reconcileGroupMembership(y)
        nodes.getValue(y).repo.notifyPeerSessionUp(x)
        nodes.getValue(y).repo.sendGroupSyncRequests(x)
        nodes.getValue(y).repo.reconcileGroupMembership(x)
    }

    /** Deliver every queued frame (in FIFO order) until the wire is quiet. */
    private suspend fun settle(pauseMs: Long = 150) {
        repeat(3) {
            drain()
            delay(pauseMs)
        }
        drain()
    }

    /** [settle] long enough for `GroupPolicy.BACKUP_DELAY_MS` (2 s) pushes to land. */
    private suspend fun settleLong() {
        repeat(9) {
            drain()
            delay(400)
        }
        drain()
    }

    private suspend fun drain() {
        var guard = 0
        while (true) {
            val envelope = wire.poll() ?: return
            if (guard++ > 500) error("frame storm: the harness did not quiesce")
            inbound.add(envelope)
            nodes.getValue(envelope.to).repo.onInboundGroupWireFrame(envelope.from, envelope.frame)
        }
    }

    // ------------------------------------------------------------------------- observations

    private fun textsOn(deviceId: String, groupId: String): List<String> =
        nodes.getValue(deviceId).messageDao.messages.values
            .filter { it.conversationId == groupId && it.deletedAt == null && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.sentAt }, { it.localId }))
            .map { it.text }

    private fun syncRequestsFrom(deviceId: String): List<Envelope> =
        outbound.filter { it.from == deviceId && it.frame is GroupWireFrame.SyncRequest }

    private suspend fun activeMembers(deviceId: String, groupId: String): Set<String> =
        nodes.getValue(deviceId).memberDao.activeMembers(groupId).map { it.deviceId }.toSet()

    private fun report(title: String) {
        val builder = StringBuilder("[DIAG] $title\n")
        nodes.forEach { (id, node) ->
            builder.append("  [$id] conversations=")
                .append(node.conversationDao.conversations.keys.toList())
                .append(" groupMessages=")
                .append(
                    node.messageDao.messages.values
                        .filter { it.deletedAt == null && it.text.isNotBlank() }
                        .map { "${it.senderId}:${it.text}" },
                )
                .append(" members=")
                .append(node.memberDao.members.values.map { "${it.groupId.take(8)}/${it.deviceId}/a=${it.isActive}" })
                .append('\n')
        }
        builder.append("  droppedFrames=\n")
        droppedFrames.forEach { builder.append("    ${it.from} -> ${it.to} ${it.frame.javaClass.simpleName}\n") }
        builder.append("  inboundMembershipFrames=\n")
        inbound.filter { it.frame is GroupWireFrame.Membership }
            .forEach { builder.append("    ${it.from} -> ${it.to} ${it.frame.javaClass.simpleName}\n") }
        builder.append("  syncRequests=\n")
        outbound.filter { it.frame is GroupWireFrame.SyncRequest }
            .forEach { builder.append("    ${it.from} -> ${it.to} $it.frame\n") }
        println(builder)
    }

    private companion object {
        const val PRE_JOIN = "PRE-join"
        const val POST_FROM_B = "POST-from-B"
        const val POST_FROM_A = "POST-from-A"
    }

    // ------------------------------------------------------------------------------- fakes
    // Faithful to the Room SQL for everything this scenario exercises; the small chat-only DAOs
    // (receipt/reaction/search/draft) are inert because no test here touches them.

    private class DiagnosticMessageDao : MessageDao {
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

        override suspend fun markReadUpTo(conversationId: String, selfId: String, upToMessageId: String) = Unit

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
                messages[localId] = it.copy(deletedAt = deletedAt)
                publish()
            }
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

    private class DiagnosticConversationDao : ConversationDao {
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

    private class DiagnosticOutboxDao : OutboxDao {
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

    private class DiagnosticGroupMemberDao : GroupMemberDao {
        val members = ConcurrentHashMap<Pair<String, String>, GroupMemberEntity>()

        override suspend fun upsert(member: GroupMemberEntity) {
            members[member.groupId to member.deviceId] = member
        }

        override fun observeMembers(groupId: String): Flow<List<GroupMemberEntity>> =
            MutableStateFlow(
                members.values.filter { it.groupId == groupId }
                    .sortedWith(compareBy({ it.joinedAt }, { it.deviceId })),
            ).asStateFlow()

        override suspend fun activeMembers(groupId: String): List<GroupMemberEntity> =
            members.values.filter { it.groupId == groupId && it.isActive }
                .sortedWith(compareBy({ it.joinedAt }, { it.deviceId }))

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

    private class DiagnosticGroupDeliveryDao : GroupDeliveryDao {
        val rows = ConcurrentHashMap<Pair<String, String>, GroupDeliveryEntity>()
        private val counts = MutableStateFlow<List<GroupDeliveryCount>>(emptyList())

        private fun publish() {
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

    private class NoopReceiptDao : ReceiptDao {
        override suspend fun insert(receipt: ReceiptEntity): Long = 1L
        override fun observeForMessage(messageId: String): Flow<List<ReceiptEntity>> =
            MutableStateFlow<List<ReceiptEntity>>(emptyList()).asStateFlow()
        override suspend fun countForMessage(messageId: String): Int = 0
    }

    private class NoopDraftDao : DraftDao {
        override suspend fun upsert(draft: DraftEntity) = Unit
        override fun observeDraft(conversationId: String): Flow<DraftEntity?> =
            MutableStateFlow<DraftEntity?>(null).asStateFlow()
        override suspend fun clear(conversationId: String) = Unit
    }

    private class NoopRecentSearchDao : RecentSearchDao {
        override suspend fun upsert(search: RecentSearchEntity) = Unit
        override fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>> =
            MutableStateFlow<List<RecentSearchEntity>>(emptyList()).asStateFlow()
        override suspend fun remove(query: String) = Unit
        override suspend fun clearAll() = Unit
    }

    private class NoopReactionDao : ReactionDao {
        override suspend fun upsert(reaction: ReactionEntity) = Unit
        override fun observeForMessage(messageId: String): Flow<List<ReactionEntity>> =
            MutableStateFlow<List<ReactionEntity>>(emptyList()).asStateFlow()
        override fun observeForConversation(conversationId: String): Flow<List<ReactionEntity>> =
            MutableStateFlow<List<ReactionEntity>>(emptyList()).asStateFlow()
        override suspend fun get(messageId: String, emoji: String): ReactionEntity? = null
        override suspend fun remove(messageId: String, emoji: String) = Unit
    }
}
