package com.transfer.flash.server.mailbox

import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.relay.RelayFrameRouter
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.fakeSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** SRVHUB-02 (RLY-01, ADR-222 decision 3): a row that can not be opened is quarantined, skipped and reported, never a stuck drain. */
class MailboxQuarantineTest {

    private val now = AtomicLong(1_800_000_000_000L)
    private val clock: () -> Long = { now.get() }
    private val databases = mutableListOf<ServerDatabase>()
    private val dirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun keyOf(seed: Int) = SecretBox.ofKey(ByteArray(32) { (it + seed).toByte() })

    private fun fileDb(): ServerDatabase {
        val dir = Files.createTempDirectory("flash-quarantine").also { dirs.add(it) }
        return ServerDatabase("jdbc:sqlite:${dir.resolve("hub.db").toString().replace('\\', '/')}", 2).also { databases.add(it) }
    }

    private fun queue(db: ServerDatabase, key: SecretBox) = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, key)

    private fun msg(id: String, origin: String = "alice", target: String = "bob") =
        MailboxMessage(origin, target, id, "MSG_TEXT", "{\"text\":\"$id\"}", now.get(), null)

    /** Three rows sealed under a key the reader does not have, then three it can read. */
    private suspend fun mixed(db: ServerDatabase): MailboxQueue {
        val old = queue(db, keyOf(9))
        listOf("bad1", "bad2", "bad3").forEach { old.enqueue(msg(it)) }
        val current = queue(db, keyOf(1))
        listOf("good1", "good2", "good3").forEach { current.enqueue(msg(it)) }
        return current
    }

    private fun count(db: ServerDatabase, where: String): Int = db.withConnection { conn ->
        conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM mailbox_envelopes WHERE $where").use { it.next(); it.getInt(1) } }
    }

    @Test
    fun unreadableRowsAreQuarantinedAndThePageIsFilledFromTheRowsBehindThem() = runTest {
        val db = fileDb()
        val q = mixed(db)

        val page = q.fetchPageDetailed("bob", 0, 3)

        assertEquals(listOf("good1", "good2", "good3"), page.messages.map { it.msgId }, "LIMIT counts only rows that can be delivered")
        assertEquals(listOf("bad1", "bad2", "bad3"), page.quarantined.map { it.msgId })
        assertEquals(3, count(db, "undeliverable_reason IS NOT NULL"))
        assertEquals(6, count(db, "1 = 1"), "quarantined, not deleted")
        assertEquals(3L, q.quarantinedTotal())

        val again = q.fetchPageDetailed("bob", 0, 3)
        assertEquals(listOf("good1", "good2", "good3"), again.messages.map { it.msgId })
        assertTrue(again.quarantined.isEmpty(), "a quarantined row is not read, counted or reported twice")
        assertEquals(3L, q.quarantinedTotal())
    }

    @Test
    fun aFullPageOfUnreadableRowsDoesNotEndTheDrain() = runTest {
        val db = fileDb()
        val old = queue(db, keyOf(9))
        repeat(250) { old.enqueue(msg("bad$it")) }
        val current = queue(db, keyOf(1))
        current.enqueue(msg("good"))

        val page = current.fetchPageDetailed("bob", 0, 100)

        assertEquals(listOf("good"), page.messages.map { it.msgId }, "the one readable row behind 250 unreadable ones is still delivered")
        assertEquals(250, page.quarantined.size)
    }

    @Test
    fun aResendOfAQuarantinedMessageIdIsNotSwallowedAsADuplicate() = runTest {
        val db = fileDb()
        val q = mixed(db)
        q.fetchPageDetailed("bob", 0, 10)

        val resend = q.enqueue(msg("bad1"))

        assertTrue(resend is EnqueueResult.Queued, "the sender was told DROPPED, so its resend is a new message: $resend")
        assertEquals(listOf("good1", "good2", "good3", "bad1"), q.fetchPageDetailed("bob", 0, 10).messages.map { it.msgId })
    }

    @Test
    fun theTtlSweeperRemovesQuarantinedRowsLikeAnyOther() = runTest {
        val db = fileDb()
        val q = mixed(db)
        q.fetchPageDetailed("bob", 0, 10)

        now.addAndGet(MailboxLimits.DEFAULT_TTL_MS + 1_000)
        val removed = q.deleteExpired(100)

        assertEquals(6, removed.size)
        assertEquals(0, count(db, "1 = 1"))
    }

    @Test
    fun aFailedQuarantineWriteReachesTheCallerInsteadOfLoopingSilently() = runTest {
        val db = fileDb()
        val q = mixed(db)
        db.withConnection { conn ->
            conn.createStatement().use {
                it.execute(
                    "CREATE TRIGGER refuse_quarantine BEFORE UPDATE OF undeliverable_reason ON mailbox_envelopes BEGIN SELECT RAISE(ABORT, 'disk full'); END",
                )
            }
        }

        assertFailsWith<Exception> { q.fetchPageDetailed("bob", 0, 10) }
        assertEquals(0, count(db, "undeliverable_reason IS NOT NULL"))
    }

    @Test
    fun quarantinedRowsDoNotCountAgainstTheRecipientQuota() = runTest {
        val db = fileDb()
        val limits = MailboxLimits(perRecipientRows = 3)
        val old = MailboxQueue(db, limits, clock, DeviceRegistry.AcceptAll, null, keyOf(9))
        listOf("bad1", "bad2", "bad3").forEach { old.enqueue(msg(it)) }
        val q = MailboxQueue(db, limits, clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        q.fetchPageDetailed("bob", 0, 10)
        assertEquals(3, count(db, "undeliverable_reason IS NOT NULL"))
        assertEquals(0, q.pendingCount("bob"), "nothing in this mailbox can still be delivered")

        // F1: before the fix the three unopenable rows used up the whole recipient quota, and every legitimate message was refused
        // with ERR_QUOTA_EXCEEDED until the 7-day TTL swept them.
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("fresh1")))
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("fresh2")))
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("fresh3")))
        val fourth = q.enqueue(msg("fresh4"))
        assertEquals(QuotaScope.RECIPIENT, assertIs<EnqueueResult.QuotaExceeded>(fourth).scope, "the live rows still cap the mailbox")
    }

    @Test
    fun quarantinedRowsDoNotCountAgainstTheSendersQuota() = runTest {
        val db = fileDb()
        val limits = MailboxLimits(perSenderRows = 2)
        val old = MailboxQueue(db, limits, clock, DeviceRegistry.AcceptAll, null, keyOf(9))
        listOf("bad1", "bad2", "bad3").forEach { old.enqueue(msg(it)) }
        val q = MailboxQueue(db, limits, clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        q.fetchPageDetailed("bob", 0, 10)

        // Alice's three unopenable rows were told DROPPED, so they must not stop her from writing to anyone else.
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("carol1", target = "carol")))
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("carol2", target = "carol")))
        val refused = q.enqueue(msg("carol3", target = "carol"))
        assertEquals(QuotaScope.SENDER, assertIs<EnqueueResult.QuotaExceeded>(refused).scope)
    }

    @Test
    fun quarantinedRowsAreNotWaitingInTheStatsAndStillCountOnDisk() = runTest {
        val db = fileDb()
        val old = queue(db, keyOf(9))
        listOf("bad1", "bad2").forEach { old.enqueue(msg(it)) }
        now.addAndGet(3_600_000L) // an hour later the hub was restarted with another data key
        val q = queue(db, keyOf(1))
        q.enqueue(msg("good"))
        q.fetchPageDetailed("bob", 0, 10)

        assertEquals(1, q.pendingCount("bob"), "only the readable row is waiting")
        assertEquals(0L, q.oldestAgeMs("bob"), "the quarantined rows are an hour old, but nobody is waiting for them")
        assertEquals(mapOf("bob" to 0L), q.oldestAgesMs(10))
        // The disk budget is a different question: the rows are still stored (their triggers keep the totals honest) until the sweep.
        assertEquals(3L, q.queuedCount())
    }

    @Test
    fun theDrainDeliversTheReadableRowsInTheSameDrainAndTellsEachSenderDropped() = runBlocking<Unit> {
        val db = fileDb()
        val q = mixed(db)
        val router = RelayFrameRouter(RelayConnectionPool(), mailboxQueue = q, clock = clock)
        val (alice, aliceWs) = fakeSession("alice")
        val (bob, bobWs) = fakeSession("bob")
        router.register(alice)
        alice.draining = false
        router.register(bob)

        val pushed = router.drainMailboxForDevice(bob)

        assertEquals(3, pushed)
        assertEquals(listOf("good1", "good2", "good3"), List(3) { bobWs.nextOf<RelayFrame.Deliver>().id })
        val dropped = List(3) { aliceWs.nextOf<RelayFrame.QueueState> { it.state == QueueEvent.DROPPED } }
        assertEquals(setOf("bad1", "bad2", "bad3"), dropped.map { it.id }.toSet())
        assertTrue(dropped.all { it.to == "bob" })
    }
}
