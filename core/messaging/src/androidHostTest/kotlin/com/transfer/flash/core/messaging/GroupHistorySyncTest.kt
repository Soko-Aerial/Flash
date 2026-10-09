package com.transfer.flash.core.messaging

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.messaging.model.FlashGroupHistoryUi
import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
import com.transfer.flash.core.messaging.protocol.GroupHistoryPolicy
import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSyncTier
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.persistence.db.dao.GroupHistoryDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.GroupHistoryStateEntity
import com.transfer.flash.core.persistence.db.entity.GroupMemberEntity
import com.transfer.flash.core.persistence.db.entity.GroupSettingsEntity
import com.transfer.flash.core.persistence.db.entity.GroupSyncWatermarkEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-100 end to end between repositories: the window and files a request carries, paged catch-up that continues to the
 * end, a watermark that moves only over a fully received page, the ceiling every holder enforces, the join card's
 * deferral, and an older holder that never sends a page marker. Legacy (unsigned) groups, so rows can be dated freely.
 *
 * Real clock and real delays: a catch-up round waits [com.transfer.flash.core.messaging.protocol.GroupPolicy.BACKUP_DELAY_MS]
 * (2 s) before its first push, so each paged scenario takes a few seconds.
 */
class GroupHistorySyncTest {
    private val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    private val names = mapOf("dev-a" to "Ada", "dev-b" to "Bo", "dev-c" to "Cy", "dev-d" to "Di")
    private val nodes = LinkedHashMap<String, Node>()
    private val wire = ConcurrentLinkedQueue<Envelope>()
    private val log = java.util.Collections.synchronizedList(mutableListOf<Envelope>())
    private val repoScopes = mutableListOf<CoroutineScope>()

    /** Frames matching this are lost on the way (a dropped push, or a holder that never heard of page markers). */
    @Volatile
    private var dropIf: (Envelope) -> Boolean = { false }

    /** Targets without a live session: the sink reports false and queues nothing (a holder that is offline). */
    @Volatile
    private var noSession: Set<String> = emptySet()

    private data class Envelope(val from: String, val to: String, val frame: GroupWireFrame)

    private class Node(
        val id: String,
        val messageDao: InMemoryMessageDao = InMemoryMessageDao(),
        val conversationDao: InMemoryConversationDao = InMemoryConversationDao(),
        val memberDao: InMemoryGroupMemberDao = InMemoryGroupMemberDao(),
        val historyDao: InMemoryGroupHistoryDao = InMemoryGroupHistoryDao(),
        val settingsDao: InMemoryGroupSettingsDao = InMemoryGroupSettingsDao(),
    ) {
        lateinit var repo: RealFlashChatRepository
    }

    @After
    fun tearDown() {
        repoScopes.forEach { it.cancel() }
        dispatcher.close()
    }

    private fun node(id: String, history: Boolean = true): Node = nodes.getOrPut(id) {
        val created = Node(id)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        repoScopes += scope
        created.repo = RealFlashChatRepository(
            localDeviceId = id,
            localDisplayName = names[id] ?: id,
            messageDao = created.messageDao,
            conversationDao = created.conversationDao,
            outboxDao = InMemoryOutboxDao(),
            receiptDao = NoopReceiptDao(),
            draftDao = NoopDraftDao(),
            recentSearchDao = NoopRecentSearchDao(),
            reactionDao = NoopReactionDao(),
            groupMemberDao = created.memberDao,
            groupDeliveryDao = InMemoryGroupDeliveryDao(),
            groupSettingsDao = created.settingsDao,
            groupHistoryDao = if (history) created.historyDao else null,
            isTrustedPeer = { true },
            groupTransportSink = GroupTransportSink { target, frame ->
                if (target in noSession) return@GroupTransportSink false
                val env = Envelope(id, target, frame)
                log.add(env)
                if (!dropIf(env)) wire.add(env)
                true
            },
            transportSink = MessageTransportSink { _, _ -> true },
            scope = scope,
            ioDispatcher = dispatcher,
            peerNameResolver = { names[it] },
        )
        created
    }

    /** Delivers queued frames in order until [done] holds or [timeoutMs] passes; true when it held. */
    private suspend fun pumpUntil(timeoutMs: Long = 20_000L, done: () -> Boolean): Boolean {
        val ok = withTimeoutOrNull(timeoutMs) {
            while (!done()) {
                val env = wire.poll()
                if (env == null) delay(15) else nodes[env.to]?.repo?.onInboundGroupWireFrame(env.from, env.frame)
            }
            true
        }
        return ok == true
    }

    /** Pumps for a fixed time (also proves that something does NOT happen). */
    private suspend fun pumpFor(ms: Long) {
        val end = System.currentTimeMillis() + ms
        pumpUntil(ms + 100) { System.currentTimeMillis() >= end }
    }

    private fun framesOf(vararg kinds: Class<*>): List<GroupWireFrame> =
        synchronized(log) { log.map { it.frame }.filter { f -> kinds.any { it.isInstance(f) } } }

    /** A (owner, holder) and B (member) in one legacy group. */
    private suspend fun twoMemberGroup(bHistory: Boolean = true): String {
        node("dev-a")
        node("dev-b", history = bHistory)
        val groupId = (node("dev-a").repo.createGroup("Hist", setOf("dev-b")) as FlashResult.Success).value
        pumpFor(300)
        assertTrue("B joined", node("dev-b").memberDao.members.values.any { it.groupId == groupId && it.deviceId == "dev-b" && it.isActive })
        log.clear()
        wire.clear()
        return groupId
    }

    private fun Node.addRow(groupId: String, id: String, sentAt: Long, sender: String = "dev-a") {
        runBlocking {
            messageDao.insert(
                MessageEntity(
                    localId = id, conversationId = groupId, senderId = sender, senderName = names[sender], text = "text $id",
                    sentAt = sentAt, status = "DELIVERED",
                ),
            )
        }
    }

    private fun Node.rowIds(groupId: String): Set<String> =
        messageDao.messages.values.filter { it.conversationId == groupId }.map { it.localId }.toSet()

    // ------------------------------------------------------------------ paging

    @Test
    fun aWindowedRequestIsAnsweredInPagesThatContinueToTheEndAndMoveTheWatermark() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        for (i in 0 until 105) a.addRow(groupId, "m%03d".format(i), now - 3 * hour + i * 1000L)

        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue("all 105 rows arrived", pumpUntil { b.rowIds(groupId).size == 105 })
        pumpFor(300)

        val requests = framesOf(GroupWireFrame.SyncRequest::class.java).map { it as GroupWireFrame.SyncRequest }
        assertEquals("a first request and one continuation", 2, requests.size)
        assertEquals(GroupHistoryPolicy.RETURNING_FLOOR_MS, requests[0].windowMs)
        assertEquals(true, requests[0].includeFiles)
        assertFalse(requests[0].continuation)
        assertTrue(requests[1].continuation)
        val pages = framesOf(GroupWireFrame.SyncPage::class.java).map { it as GroupWireFrame.SyncPage }
        assertEquals(listOf(100, 5), pages.map { it.count })
        assertEquals(listOf(5, 0), pages.map { it.remaining })
        assertEquals(listOf(true, false), pages.map { it.more })
        // The continuation starts exactly where the first page ended.
        assertEquals(pages[0].lastSentAt, requests[1].sinceSentAt)
        assertEquals(pages[0].lastMessageId, requests[1].sinceMessageId)

        val mark = b.historyDao.watermarks["$groupId|dev-a"]
        assertNotNull(mark)
        assertEquals("m104", mark!!.messageId)
        assertTrue("a holder finished serving: last contact recorded", (b.historyDao.states[groupId]?.lastContactAtMs ?: 0L) > 0L)
    }

    @Test
    fun aPageThatLostAFrameLeavesTheWatermarkAndTheNextSessionRecoversTheGap() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        for (i in 0 until 12) a.addRow(groupId, "m%02d".format(i), now - 3 * hour + i * 1000L)

        // The 5th push never arrives.
        dropIf = { env -> (env.frame as? GroupWireFrame.SyncPush)?.message?.messageId == "m04" }
        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue("11 of 12 arrived", pumpUntil { b.rowIds(groupId).size == 11 })
        // The marker waits for the missing push, gives up, and does not move the watermark nor ask for more.
        pumpFor(6_500)
        assertNull("no watermark over an incomplete page", b.historyDao.watermarks["$groupId|dev-a"])
        assertEquals(1, framesOf(GroupWireFrame.SyncRequest::class.java).size)
        assertFalse(b.rowIds(groupId).contains("m04"))

        // Next session: the newest local row (m11) would skip m04 (defect S1); the request starts at the window floor.
        dropIf = { false }
        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue("the gap row arrived", pumpUntil { b.rowIds(groupId).contains("m04") })
        assertTrue("the complete page moves the watermark", pumpUntil { b.historyDao.watermarks["$groupId|dev-a"] != null })
        val second = framesOf(GroupWireFrame.SyncRequest::class.java).map { it as GroupWireFrame.SyncRequest }.last()
        assertTrue("second request starts below the newest local row", second.sinceSentAt < now - 3 * hour + 11 * 1000L)
        assertEquals("m11", b.historyDao.watermarks["$groupId|dev-a"]?.messageId)
    }

    // ------------------------------------------------------------------ S1 and S2 through the real repositories

    @Test
    fun s1TheOldRequestSkipsARowMissedBelowTheNewestOneAndTheWatermarkedRequestDoesNot() = runBlocking {
        node("dev-a")
        node("dev-b", history = true)
        node("dev-c", history = false)
        val groupId = (node("dev-a").repo.createGroup("S1", setOf("dev-b", "dev-c")) as FlashResult.Success).value
        pumpFor(300)
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        a.addRow(groupId, "missed", now - 3 * hour)
        a.addRow(groupId, "live", now - hour)
        // B and C both hold only the later row (they were disconnected when "missed" was sent).
        for (n in listOf(node("dev-b"), node("dev-c"))) n.addRow(groupId, "live", now - hour)
        log.clear()
        wire.clear()

        node("dev-b").repo.sendGroupSyncRequests("dev-a")
        node("dev-c").repo.sendGroupSyncRequests("dev-a")
        assertTrue("B (ADR-100) recovered the missed row", pumpUntil { "missed" in node("dev-b").rowIds(groupId) })
        pumpFor(2_500)
        assertFalse("C (pre-ADR-100 request) still never gets it: defect S1", "missed" in node("dev-c").rowIds(groupId))
    }

    @Test
    fun s2AReturningMemberGetsTheMiddleDayThatThe24HourWindowLost() = runBlocking {
        node("dev-a")
        node("dev-b", history = true)
        node("dev-c", history = false)
        val groupId = (node("dev-a").repo.createGroup("S2", setOf("dev-b", "dev-c")) as FlashResult.Success).value
        pumpFor(300)
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        a.addRow(groupId, "t-40h", now - 40 * hour)
        a.addRow(groupId, "t-30h", now - 30 * hour)
        a.addRow(groupId, "t-1h", now - hour)
        // Both were away two days: their newest row is 2 days old.
        for (n in listOf(node("dev-b"), node("dev-c"))) n.addRow(groupId, "old", now - 2 * day)
        log.clear()
        wire.clear()

        node("dev-b").repo.sendGroupSyncRequests("dev-a")
        node("dev-c").repo.sendGroupSyncRequests("dev-a")
        assertTrue("B received all three", pumpUntil { node("dev-b").rowIds(groupId).containsAll(listOf("t-40h", "t-30h", "t-1h")) })
        pumpFor(2_500)
        assertEquals("C only gets the last 24 hours", setOf("old", "t-1h"), node("dev-c").rowIds(groupId))
    }

    // ------------------------------------------------------------------ the ceiling

    private fun Node.setCeiling(groupId: String, ceiling: GroupHistoryCeiling) {
        runBlocking {
            settingsDao.upsert(
                GroupSettingsEntity(
                    groupId = groupId, version = 2L, joinPolicy = "APPROVE", inviteSharers = "ALL", maxMembers = 20,
                    swarmServing = true, membersMayAdd = false, opId = "op", signerId = "dev-a", sig = "s",
                    // ADR-105: a non-default ceiling is stored with its own signature (`D7~<sig>`); an unsigned one reads as D30.
                    historyCeiling = if (ceiling == GroupHistoryCeiling.DEFAULT) ceiling.name else ceiling.name + "~c2ln",
                ),
            )
        }
    }

    @Test
    fun aHolderServesOnlyInsideItsCeilingWhateverTheRequesterAsks() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        a.addRow(groupId, "d10", now - 10 * day)
        a.addRow(groupId, "d3", now - 3 * day)
        a.addRow(groupId, "h1", now - hour)
        a.setCeiling(groupId, GroupHistoryCeiling.D7)
        // B decided to load 30 days.
        b.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 30 * day, true, now - 1000L, 0L, now - 1000L)

        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue("the 7-day rows arrived", pumpUntil { b.rowIds(groupId).containsAll(listOf("d3", "h1")) })
        pumpFor(500)
        assertFalse("the 10-day row is outside the ceiling", "d10" in b.rowIds(groupId))
    }

    @Test
    fun aNoneCeilingServesNothingEvenToAnOlderRequester() = runBlocking {
        val groupId = twoMemberGroup(bHistory = false)
        val a = node("dev-a")
        a.addRow(groupId, "h1", System.currentTimeMillis() - hour)
        a.setCeiling(groupId, GroupHistoryCeiling.NONE)

        node("dev-b").repo.sendGroupSyncRequests("dev-a")
        pumpFor(2_800)
        assertTrue(node("dev-b").rowIds(groupId).isEmpty())
        assertTrue(framesOf(GroupWireFrame.SyncPush::class.java).isEmpty())
    }

    @Test
    fun noFilesWhenTheMemberDidNotAskForThem() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        a.addRow(groupId, "text", now - hour)
        runBlocking {
            a.messageDao.insert(
                MessageEntity(
                    localId = "file", conversationId = groupId, senderId = "dev-a", senderName = "Ada", text = "",
                    sentAt = now - 2 * hour, status = "DELIVERED", attachmentTransferId = "file", attachmentName = "f.bin",
                    attachmentMime = "application/octet-stream", attachmentSize = 10L,
                    swarmRoot = "ab".repeat(32), swarmPieceSize = 1024, swarmRootSig = "sig",
                ),
            )
        }
        b.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", day, false, now - 1000L, 0L, now - 1000L)

        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue(pumpUntil { "text" in b.rowIds(groupId) })
        pumpFor(500)
        val request = framesOf(GroupWireFrame.SyncRequest::class.java).first() as GroupWireFrame.SyncRequest
        assertEquals(false, request.includeFiles)
        assertFalse("a file row is not served when files were declined", "file" in b.rowIds(groupId))
    }

    // ------------------------------------------------------------------ the join card

    @Test
    fun aNewMemberAsksNothingUntilItChoosesAndTheChoiceIsClampedToTheCeiling() = runBlocking {
        val a = node("dev-a")
        // D does not exist yet when A creates the group, so its Create frame is lost and D learns the group from a State.
        val groupId = (a.repo.createGroup("Card", setOf("dev-d")) as FlashResult.Success).value
        pumpFor(200)
        val d = node("dev-d")
        a.setCeiling(groupId, GroupHistoryCeiling.D7)
        a.addRow(groupId, "d3", System.currentTimeMillis() - 3 * day)
        d.setCeiling(groupId, GroupHistoryCeiling.D7)
        log.clear()
        wire.clear()

        // D learns the group from a roster it did not know (the bootstrap State).
        val state = GroupWireFrame.State(
            groupId = groupId, from = "dev-a", operationId = "op-state", membershipVersion = System.currentTimeMillis(),
            name = "Card", creatorId = "dev-a",
            members = listOf("dev-a", "dev-d").map {
                GroupWireFrame.RosterEntry(it, names.getValue(it), "member", 1L, System.currentTimeMillis(), "op-$it", true)
            },
        )
        d.repo.onInboundGroupWireFrame("dev-a", state)
        pumpFor(500)
        assertEquals("PENDING", d.historyDao.states[groupId]?.cardState)
        assertTrue("no catch-up request while the card is up", framesOf(GroupWireFrame.SyncRequest::class.java).isEmpty())
        d.repo.sendGroupSyncRequests("dev-a")
        pumpFor(300)
        assertTrue("a session-up request is deferred too", framesOf(GroupWireFrame.SyncRequest::class.java).isEmpty())
        d.repo.openConversation(groupId)
        val shown = withTimeoutOrNull(3_000) { while (d.repo.conversationState.value.groupHistory == null) delay(20); true }
        assertEquals("the screen is told to show the card", true, shown)
        assertEquals(FlashGroupHistoryUi(GroupHistoryCeiling.D7), d.repo.conversationState.value.groupHistory)

        // The member asks for 30 days; the card could not offer it, and the repository clamps whatever arrives.
        assertTrue(d.repo.chooseGroupHistory(groupId, 30 * day, includeFiles = true) is FlashResult.Success)
        assertTrue(pumpUntil { "d3" in d.rowIds(groupId) })
        val request = framesOf(GroupWireFrame.SyncRequest::class.java).first() as GroupWireFrame.SyncRequest
        assertTrue("the request is inside the 7-day ceiling", request.windowMs!! <= 7 * day + 60_000L)
        assertEquals("DECIDED", d.historyDao.states[groupId]?.cardState)
        assertEquals(7 * day, d.historyDao.states[groupId]?.windowMs)
        val gone = withTimeoutOrNull(3_000) {
            while (d.repo.conversationState.value.groupHistory != null) delay(20)
            true
        }
        assertEquals("the card is gone once decided", true, gone)
    }

    @Test
    fun skippingHistoryStoresASkippedStateAndAsksForNoFiles() = runBlocking {
        val groupId = twoMemberGroup()
        val b = node("dev-b")
        b.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "PENDING", 0L, false, 0L, 0L, 1L)
        assertTrue(b.repo.skipGroupHistory(groupId) is FlashResult.Success)
        assertEquals("SKIPPED", b.historyDao.states[groupId]?.cardState)
        assertEquals(false, b.historyDao.states[groupId]?.includeFiles)
        assertEquals(0L, b.historyDao.states[groupId]?.windowMs)
        pumpFor(400)
        // A request, if one is sent at all (a zero-length window sends none), never asks for files.
        framesOf(GroupWireFrame.SyncRequest::class.java).forEach { assertEquals(false, (it as GroupWireFrame.SyncRequest).includeFiles) }
    }

    @Test
    fun loadingOlderMessagesResetsTheWatermarksSoTheOlderRowsAreAskedFor() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        a.addRow(groupId, "d20", now - 20 * day)
        a.addRow(groupId, "h1", now - hour)
        b.addRow(groupId, "h1", now - hour)
        b.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 7 * day, true, now - day, now - hour, now - day)
        b.historyDao.watermarks["$groupId|dev-a"] = GroupSyncWatermarkEntity(groupId, "dev-a", now - hour, "h1", now)

        assertTrue(b.repo.loadOlderGroupHistory(groupId, 30 * day) is FlashResult.Success)
        assertTrue(pumpUntil { "d20" in b.rowIds(groupId) })
        assertEquals(30 * day, b.historyDao.states[groupId]?.windowMs)
    }

    // ------------------------------------------------------------------ an older holder

    @Test
    fun aHolderThatNeverSendsAPageMarkerCostsNoWatermarkAndNoRunawayRequests() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        for (i in 0 until 5) a.addRow(groupId, "m$i", now - 2 * hour + i * 1000L)
        // An older build does not know the marker frame at all: its codec returns null, so nothing ever arrives.
        dropIf = { it.frame is GroupWireFrame.SyncPage }

        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue(pumpUntil { b.rowIds(groupId).size == 5 })
        pumpFor(1_000)
        assertTrue("no watermark without a marker", b.historyDao.watermarks.isEmpty())
        assertEquals("and no automatic continuation", 1, framesOf(GroupWireFrame.SyncRequest::class.java).size)
        b.repo.sendGroupSyncRequests("dev-a")
        pumpFor(300)
        assertEquals("the next session still asks, as before", 2, framesOf(GroupWireFrame.SyncRequest::class.java).size)
    }

    // ------------------------------------------------------------------ review 2026-10-09: G1, G3 and the Lows

    /** A (nothing to give), B (one 10-day-old row) and C (the requester) in one legacy group; C chose 30 days a second ago. */
    private suspend fun requesterWithTwoHolders(): String {
        node("dev-a")
        node("dev-b")
        node("dev-c")
        val groupId = (node("dev-a").repo.createGroup("G1", setOf("dev-b", "dev-c")) as FlashResult.Success).value
        pumpFor(300)
        val now = System.currentTimeMillis()
        node("dev-b").addRow(groupId, "d10", now - 10 * day)
        node("dev-c").historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 30 * day, true, now - 1000L, 0L, now - 1000L)
        log.clear()
        wire.clear()
        return groupId
    }

    @Test
    fun g1AHolderAskedAfterAnotherOneFinishedStillGetsTheWindowTheMemberChose() = runBlocking {
        val groupId = requesterWithTwoHolders()
        val c = node("dev-c")

        c.repo.sendGroupSyncRequests("dev-a")
        assertTrue("A answered with an empty last page", pumpUntil {
            framesOf(GroupWireFrame.SyncPage::class.java).any { (it as GroupWireFrame.SyncPage).from == "dev-a" }
        })
        pumpFor(400)
        c.repo.sendGroupSyncRequests("dev-b")
        assertTrue("B's 10-day-old row arrived: the card promised 30 days", pumpUntil { "d10" in c.rowIds(groupId) })
        val toB = framesOf(GroupWireFrame.SyncRequest::class.java).map { it as GroupWireFrame.SyncRequest }
        assertTrue("B was asked for the chosen 30 days, not the 7-day floor", toB.last().windowMs!! > 29 * day)
    }

    /** [count] members of one legacy group, every node seeded directly (a legacy group created by the app caps at 6). */
    private suspend fun bigGroup(count: Int, groupId: String): List<String> {
        val ids = (0 until count).map { "dev-%02d".format(it) }
        val now = System.currentTimeMillis()
        for (id in ids) {
            val n = node(id)
            n.conversationDao.upsert(ConversationEntity(groupId, "Sim", isGroup = true, groupCreatedBy = ids[0], groupCreatedAt = now))
            ids.forEachIndexed { i, member ->
                n.memberDao.upsert(GroupMemberEntity(groupId, member, member, joinedAt = i.toLong(), membershipVersion = 1L, operationId = "op-$member"))
            }
        }
        return ids
    }

    @Test
    fun g3ANewMemberOfATwentyMemberGroupGetsEachRowFromOneHolderNotNineteen() = runBlocking {
        val groupId = "g-sim-20"
        val ids = bigGroup(20, groupId)
        val now = System.currentTimeMillis()
        val rows = 30
        // Nineteen holders, every one of them holds the same thirty rows.
        for (holder in ids.drop(1)) for (i in 0 until rows) node(holder).addRow(groupId, "m%02d".format(i), now - 3 * hour + i * 1000L)
        val newcomer = node(ids[0])
        newcomer.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 30 * day, true, now - 1000L, 0L, now - 1000L)
        log.clear()
        wire.clear()

        newcomer.repo.requestGroupCatchUp(groupId, newlyJoined = false)
        assertTrue("all rows arrived", pumpUntil(40_000L) { newcomer.rowIds(groupId).size == rows })
        pumpFor(3_000)

        val pushes = framesOf(GroupWireFrame.SyncPush::class.java).size
        val requests = framesOf(GroupWireFrame.SyncRequest::class.java).size
        assertEquals("every row crossed the wire once, not once per holder", rows, pushes)
        assertEquals("one holder was asked", 1, requests)
    }

    @Test
    fun g3AHolderWithoutASessionIsSkippedAndTheNextOneIsAsked() = runBlocking {
        val groupId = "g-failover"
        val ids = bigGroup(5, groupId)
        val now = System.currentTimeMillis()
        for (holder in ids.drop(1)) for (i in 0 until 5) node(holder).addRow(groupId, "m$i", now - 3 * hour + i * 1000L)
        val requester = node(ids[0])
        requester.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 30 * day, true, now - 1000L, 0L, now - 1000L)
        // dev-04 served this device most recently, so the lane tries the three offline holders first.
        requester.historyDao.watermarks["$groupId|dev-04"] = GroupSyncWatermarkEntity(groupId, "dev-04", 0L, "", now)
        noSession = setOf("dev-01", "dev-02", "dev-03")
        log.clear()
        wire.clear()

        requester.repo.requestGroupCatchUp(groupId, newlyJoined = false)
        assertTrue("the one holder with a session delivered", pumpUntil(20_000L) { requester.rowIds(groupId).size == 5 })
        pumpFor(500)
        val toHolders = synchronized(log) { log.filter { it.frame is GroupWireFrame.SyncRequest }.map { it.to } }
        assertEquals("the unreachable holders took no request and the lane moved on to dev-04", listOf("dev-04"), toHolders)
    }

    @Test
    fun g3AHolderThatNeverAnswersIsReplacedAfterTheStallTime() = runBlocking {
        val groupId = "g-stall"
        val ids = bigGroup(3, groupId)
        val now = System.currentTimeMillis()
        for (holder in ids.drop(1)) for (i in 0 until 4) node(holder).addRow(groupId, "m$i", now - 3 * hour + i * 1000L)
        val requester = node(ids[0])
        requester.repo.catchUpStallMs = 400L
        requester.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 30 * day, true, now - 1000L, 0L, now - 1000L)
        // dev-02 was served more recently than dev-01, so dev-01 leads and is the one that goes quiet.
        requester.historyDao.watermarks["$groupId|dev-02"] = GroupSyncWatermarkEntity(groupId, "dev-02", 0L, "", now)
        dropIf = { it.frame is GroupWireFrame.SyncRequest && it.to == "dev-01" }
        log.clear()
        wire.clear()

        requester.repo.requestGroupCatchUp(groupId, newlyJoined = false)
        assertTrue("the second holder was asked once the first went quiet", pumpUntil(20_000L) { requester.rowIds(groupId).size == 4 })
        val to = synchronized(log) { log.filter { it.frame is GroupWireFrame.SyncRequest }.map { it.to } }
        assertEquals(listOf("dev-01", "dev-02"), to)
    }

    @Test
    fun g6TombstonedRowsDoNotMakeAPageShortOrTheChainLonger() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val a = node("dev-a")
        val b = node("dev-b")
        for (i in 0 until 100) {
            a.messageDao.insert(
                MessageEntity(
                    localId = "t%03d".format(i), conversationId = groupId, senderId = "dev-a", senderName = "Ada", text = "",
                    sentAt = now - 3 * hour + i * 1000L, status = "DELIVERED", deletedAt = now,
                ),
            )
        }
        for (i in 0 until 50) a.addRow(groupId, "l%03d".format(i), now - 2 * hour + i * 1000L)

        b.repo.sendGroupSyncRequests("dev-a")
        assertTrue("the 50 live rows arrived", pumpUntil { b.rowIds(groupId).count { it.startsWith("l") } == 50 })
        pumpFor(500)
        assertEquals("one request: the 100 deleted rows read first are not counted", 1, framesOf(GroupWireFrame.SyncRequest::class.java).size)
        val pages = framesOf(GroupWireFrame.SyncPage::class.java).map { it as GroupWireFrame.SyncPage }
        assertEquals(listOf(50), pages.map { it.count })
        assertEquals(listOf(false), pages.map { it.more })
        assertTrue("no deleted row was relayed", b.rowIds(groupId).none { it.startsWith("t") })
    }

    @Test
    fun g7AReturningMemberWithNoRowsIsNotShownTheJoinCardAndAsksAtOnce() = runBlocking {
        val groupId = twoMemberGroup()
        val a = node("dev-a")
        val b = node("dev-b")
        a.addRow(groupId, "h1", System.currentTimeMillis() - hour)
        // B knows the group (it has a conversation and a roster row) but holds no message and has no history state.
        b.historyDao.states.remove(groupId)
        log.clear()
        wire.clear()

        a.repo.reconcileGroupMembership("dev-b")
        assertTrue("B asked for the missed history straight away", pumpUntil { "h1" in b.rowIds(groupId) })
        assertTrue("a returning member gets no card", b.historyDao.states[groupId]?.cardState != "PENDING")
    }

    @Test
    fun g8LoadOlderNeverLowersTheStoredWindow() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val b = node("dev-b")
        b.historyDao.states[groupId] = GroupHistoryStateEntity(groupId, "DECIDED", 30 * day, true, now - day, now - hour, now - day)
        assertTrue(b.repo.loadOlderGroupHistory(groupId, 7 * day) is FlashResult.Success)
        assertEquals(30 * day, b.historyDao.states[groupId]?.windowMs)
    }

    @Test
    fun g11ARequesterThatHammersAHolderIsAnsweredOnlyUpToTheBudget() = runBlocking {
        val groupId = twoMemberGroup()
        val a = node("dev-a")
        repeat(45) { i ->
            a.repo.onInboundGroupWireFrame(
                "dev-b",
                GroupWireFrame.SyncRequest(
                    groupId = groupId, syncId = "flood-$i", from = "dev-b", sinceSentAt = 0L, sinceMessageId = "",
                    tier = GroupSyncTier.HIGH, maxPerSecond = 20, maxTotal = 500,
                    windowMs = 7 * day, includeFiles = true, continuation = true,
                ),
            )
        }
        val answered = framesOf(GroupWireFrame.SyncPage::class.java).size
        assertEquals("answered up to the budget and no further", GroupPolicy.SYNC_REQUESTS_PER_WINDOW, answered)
    }

    /** A request B really made to A, with A's answer dropped on the wire, so a test can hand B its own page frames. */
    private suspend fun outstandingRequestOfB(groupId: String): GroupWireFrame.SyncRequest {
        dropIf = { it.frame is GroupWireFrame.SyncPage }
        node("dev-b").repo.sendGroupSyncRequests("dev-a")
        pumpFor(300)
        val request = framesOf(GroupWireFrame.SyncRequest::class.java).first() as GroupWireFrame.SyncRequest
        dropIf = { false }
        return request
    }

    @Test
    fun g12AMarkerCannotMoveTheWatermarkPastTheRowsItActuallySent() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val b = node("dev-b")
        val request = outstandingRequestOfB(groupId)

        val row = GroupWireFrame.Message(groupId, "m1", "dev-a", "Ada", sentAt = now - hour, text = "hi")
        b.repo.onInboundGroupWireFrame("dev-a", GroupWireFrame.SyncPush(groupId, request.syncId, "dev-a", row))
        // The holder claims the page ended 400 days from now.
        b.repo.onInboundGroupWireFrame(
            "dev-a",
            GroupWireFrame.SyncPage(
                groupId, request.syncId, "dev-a", count = 1, remaining = 0, more = false,
                lastSentAt = now + 400 * day, lastMessageId = "zzzz",
            ),
        )
        val mark = b.historyDao.watermarks["$groupId|dev-a"]
        assertNotNull("a watermark was stored", mark)
        assertEquals("cut back to the row that was sent", now - hour, mark!!.sentAt)
        assertEquals("m1", mark.messageId)
    }

    @Test
    fun g12AFutureScanEndWithNoRowsIsNotBelievedAndNoContinuationFollows() = runBlocking {
        val groupId = twoMemberGroup()
        val now = System.currentTimeMillis()
        val b = node("dev-b")
        val request = outstandingRequestOfB(groupId)

        b.repo.onInboundGroupWireFrame(
            "dev-a",
            GroupWireFrame.SyncPage(
                groupId, request.syncId, "dev-a", count = 0, remaining = 5, more = true,
                lastSentAt = now + 400 * day, lastMessageId = "zzzz",
            ),
        )
        pumpFor(600)
        assertNull("no watermark", b.historyDao.watermarks["$groupId|dev-a"])
        assertEquals("no continuation request", 1, framesOf(GroupWireFrame.SyncRequest::class.java).size)
    }

    @Test
    fun g13TwoRefreshesInTheSameMillisecondStillChangeTheTrigger() {
        val b = node("dev-b")
        val seen = (1..5).map {
            b.repo.touchConversationRefresh()
            b.repo.conversationRefreshValue()
        }
        assertEquals("strictly increasing", seen.sorted().distinct(), seen)
    }
}

/** In-memory [GroupHistoryDao] for the tests above. */
internal class InMemoryGroupHistoryDao : GroupHistoryDao {
    val states = ConcurrentHashMap<String, GroupHistoryStateEntity>()
    val watermarks = ConcurrentHashMap<String, GroupSyncWatermarkEntity>()

    override suspend fun upsertState(entity: GroupHistoryStateEntity) {
        states[entity.groupId] = entity
    }

    override suspend fun state(groupId: String): GroupHistoryStateEntity? = states[groupId]

    override suspend fun deleteState(groupId: String) {
        states.remove(groupId)
    }

    override suspend fun upsertWatermark(entity: GroupSyncWatermarkEntity) {
        watermarks["${entity.groupId}|${entity.holderId}"] = entity
    }

    override suspend fun watermark(groupId: String, holderId: String): GroupSyncWatermarkEntity? =
        watermarks["$groupId|$holderId"]

    override suspend fun watermarks(groupId: String): List<GroupSyncWatermarkEntity> =
        watermarks.values.filter { it.groupId == groupId }

    override suspend fun deleteWatermarks(groupId: String) {
        watermarks.keys.filter { it.startsWith("$groupId|") }.forEach { watermarks.remove(it) }
    }
}
