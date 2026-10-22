@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.FakeWebSocketSession
import com.transfer.flash.server.testsupport.fakeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RLY-02 (a sender's egress is bounded by bytes, not only frames) and RLY-03 (the drain starts inside the hand-off gate), ADR-222. */
class RelayByteBudgetTest {

    private val databases = mutableListOf<ServerDatabase>()

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
    }

    private fun mailbox(): MailboxQueue {
        val db = ServerDatabase.inMemory(poolSize = 1).also { databases += it }
        return MailboxQueue(db, MailboxLimits())
    }

    private fun bigGroup(members: Int): OrganizationDirectory {
        val directory = OrganizationDirectory()
        directory.createDepartment("eng", "Engineering", isOrgAdmin = true)
        directory.createGroup(
            id = "grp-big", name = "Everyone", departmentId = "eng", visibility = GroupVisibility.LISTED,
            joinMode = JoinMode.OPEN_IN_SCOPE, callerDeviceId = "alice", isOrgAdmin = true,
        )
        directory.addMemberToGroup("grp-big", "alice", isOrgAdmin = true)
        for (i in 1 until members) directory.addMemberToGroup("grp-big", "m$i", isOrgAdmin = true)
        return directory
    }

    private fun router(policy: RelayPolicy, directory: OrganizationDirectory? = null) =
        RelayFrameRouter(connectionPool = RelayConnectionPool(), mailboxQueue = mailbox(), directory = directory, policy = policy)

    /** The next [n] acks the hub wrote (the session's writer runs on its own coroutine, so a read right after `processFrame` can be early). */
    private suspend fun FakeWebSocketSession.acks(n: Int): List<RelayFrame.Ack> = List(n) { nextOf<RelayFrame.Ack>() }

    private fun groupSend(id: String, bytes: Int) =
        RelayFrameCodec.encode(RelayFrame.Send(id = id, type = "MSG_TEXT", sentAt = 1L, payload = "x".repeat(bytes), group = "grp-big"))

    @Test
    fun aGroupFloodWithinTheFrameLimitIsRefusedByTheByteLimit() = runBlocking<Unit> {
        // 60 KB to 99 members is about 6 MB of copies: the first frame fits the 8 MiB burst, the second does not. The frame bucket alone allows both.
        val r = router(RelayPolicy(senderBytesPerSecond = 1), bigGroup(100))
        val (alice, aliceWs) = fakeSession("alice")
        r.register(alice)
        alice.draining = false

        r.processFrame(alice, groupSend("g1", 60_000))
        r.processFrame(alice, groupSend("g2", 60_000))

        val acks = aliceWs.acks(2)
        assertEquals(AckStatus.QUEUED, acks[0].status, "the first frame is within the burst")
        assertEquals(AckStatus.REJECTED, acks[1].status)
        assertEquals(HubErrorCodes.ERR_RATE_LIMIT, acks[1].reason)
        assertEquals("g2", acks[1].id)
        assertEquals("grp-big", acks[1].to)
    }

    @Test
    fun aSmallGroupMessageIsNeverAffected() = runBlocking<Unit> {
        val r = router(RelayPolicy(senderBytesPerSecond = 1), bigGroup(100))
        val (alice, aliceWs) = fakeSession("alice")
        r.register(alice)
        alice.draining = false

        repeat(20) { r.processFrame(alice, groupSend("s$it", 200)) }

        val acks = aliceWs.acks(20)
        assertTrue(acks.none { it.status == AckStatus.REJECTED }, "20 x 200 B x 99 copies is under 400 KB")
    }

    @Test
    fun oneFrameLargerThanTheBurstIsStillSentOnceFromAFullBucket() = runBlocking<Unit> {
        // 60 KB x 99 copies is above this 1 MiB burst, but a frame must never be unsendable: it is capped at the burst.
        val r = router(RelayPolicy(senderBytesPerSecond = 1, senderByteBurst = 1024 * 1024), bigGroup(100))
        val (alice, aliceWs) = fakeSession("alice")
        r.register(alice)
        alice.draining = false

        r.processFrame(alice, groupSend("g1", 60_000))
        r.processFrame(alice, groupSend("g2", 60_000))

        val acks = aliceWs.acks(2)
        assertEquals(AckStatus.QUEUED, acks[0].status)
        assertEquals(AckStatus.REJECTED, acks[1].status)
    }

    @Test
    fun theLimitIsPerSenderNotPerHub() = runBlocking<Unit> {
        val r = router(RelayPolicy(senderBytesPerSecond = 1), bigGroup(100))
        val (alice, aliceWs) = fakeSession("alice")
        val (m1, m1Ws) = fakeSession("m1")
        r.register(alice); r.register(m1)
        alice.draining = false; m1.draining = false

        r.processFrame(alice, groupSend("a1", 60_000))
        r.processFrame(alice, groupSend("a2", 60_000))
        r.processFrame(m1, groupSend("b1", 60_000))

        assertEquals(AckStatus.REJECTED, aliceWs.acks(2).last().status)
        assertEquals(AckStatus.QUEUED, m1Ws.acks(1).single().status, "a neighbour's flood does not spend my budget")
    }

    @Test
    fun aSenderThatKeepsFloodingIsClosed() = runBlocking<Unit> {
        val r = router(RelayPolicy(senderBytesPerSecond = 1, abusiveRejectionsBeforeClose = 3), bigGroup(100))
        val (alice, _) = fakeSession("alice")
        r.register(alice)
        alice.draining = false

        repeat(8) { r.processFrame(alice, groupSend("g$it", 60_000)) }

        var waited = 0
        while (alice.isOpen && waited < 5_000) { delay(50); waited += 50 }
        assertFalse(alice.isOpen)
        assertEquals("rate_limit", alice.reasonClosed)
    }

    @Test
    fun aDirectMessageIsChargedItsOwnSizeOnly() = runBlocking<Unit> {
        val r = router(RelayPolicy(senderBytesPerSecond = 1, senderByteBurst = 100_000))
        val (alice, aliceWs) = fakeSession("alice")
        val (bob, _) = fakeSession("bob")
        r.register(alice); r.register(bob)
        alice.draining = false; bob.draining = false

        fun send(id: String) = RelayFrameCodec.encode(RelayFrame.Send(to = "bob", id = id, type = "MSG_TEXT", sentAt = 1L, payload = "x".repeat(40_000)))
        r.processFrame(alice, send("d1"))
        r.processFrame(alice, send("d2"))
        r.processFrame(alice, send("d3"))

        val statuses = aliceWs.acks(3).map { it.status }
        assertEquals(listOf(AckStatus.DELIVERED, AckStatus.DELIVERED, AckStatus.REJECTED), statuses, "100 000 B burst: two 40 000 B messages, not a third")
    }

    @Test
    fun theBucketTakesACostAndRefillsOverTime() {
        var nanos = 0L
        val bucket = TokenBucket(1000, 100.0) { nanos }
        assertTrue(bucket.tryTake(600L))
        assertFalse(bucket.tryTake(600L), "only 400 left")
        nanos += 2_000_000_000L // +200 tokens
        assertTrue(bucket.tryTake(600L))
        nanos += 1_000_000_000L // +100 tokens
        assertTrue(bucket.tryTake(), "the one-token form still works")
        assertTrue(TokenBucket(10, 1.0) { 0L }.tryTake(1_000_000L), "an oversize cost is capped at the capacity")
    }

    // ---- RLY-03 ---------------------------------------------------------------------------------------------

    @Test
    fun beginDrainTakesTheBatonAndMarksDrainingAsOneStep() {
        val (session, _) = fakeSession("alice")
        session.draining = false
        assertTrue(session.beginDrain())
        assertEquals(true to true, session.drainFlags())
        assertFalse(session.beginDrain(), "a second request while one runs is a no-op")
        session.abortDrain()
        assertEquals(false to false, session.drainFlags())
    }

    @Test
    fun theDrainBatonIsNeverHeldWithoutTheDrainingFlagAtAGateBoundary() {
        val (session, _) = fakeSession("alice")
        session.draining = false
        val running = AtomicBoolean(true)
        val violations = AtomicInteger()
        val reader = thread {
            while (running.get()) {
                val (batonTaken, draining) = session.drainFlags()
                if (batonTaken && !draining) violations.incrementAndGet()
            }
        }
        repeat(300_000) {
            session.beginDrain()
            session.abortDrain()
        }
        running.set(false)
        reader.join()
        assertEquals(0, violations.get(), "a live frame could have been handed over between the baton and the flag (RLY-03)")
    }
}
