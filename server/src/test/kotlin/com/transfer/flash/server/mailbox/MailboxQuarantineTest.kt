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

    private fun msg(id: String, origin: String = "alice") = MailboxMessage(origin, "bob", id, "MSG_TEXT", "{\"text\":\"$id\"}", now.get(), null)

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
