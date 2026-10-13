@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.server.testsupport.fakeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RelayConnectionPoolTest {

    @Test
    fun registerAndGet() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        assertEquals(0, pool.activeCount)

        val (session1, _) = fakeSession("dev-1")
        assertNull(pool.register(session1))

        assertEquals(1, pool.activeCount)
        assertTrue(pool.isConnected("dev-1"))
        assertTrue(pool.isOnline("dev-1"))
        assertEquals(setOf("dev-1"), pool.onlineDeviceIds())
        assertSame(session1, pool.get("dev-1"))
    }

    @Test
    fun unregisterRemovesOnlyTheSessionItWasGiven() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val (session1, _) = fakeSession("dev-1")
        pool.register(session1)

        assertTrue(pool.unregister(session1))
        assertEquals(0, pool.activeCount)
        assertFalse(pool.isConnected("dev-1"))
        assertFalse(pool.unregister(session1), "a second unregister is a no-op")
    }

    /** REL-01: the old session's delayed cleanup must not remove the connection that replaced it. */
    @Test
    fun aDisplacedSessionsCleanupCanNotDeregisterItsReplacement() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val (a1, _) = fakeSession("dev-1")
        val (a2, _) = fakeSession("dev-1")
        pool.register(a1)
        val displaced = pool.register(a2)
        assertSame(a1, displaced)

        assertFalse(pool.unregister(a1), "A1's cleanup runs late and must find A2 in its place")
        assertSame(a2, pool.get("dev-1"))
        assertTrue(pool.isConnected("dev-1"))
        assertEquals(1, pool.activeCount)
    }

    @Test
    fun theDisplacedSessionIsToldItWasSupersededAndClosed() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val (a1, ws1) = fakeSession("dev-1")
        val (a2, _) = fakeSession("dev-1")
        pool.register(a1)
        pool.register(a2)

        val kick = ws1.nextOf<RelayFrame.Kick>()
        assertEquals(RelayConnectionPool.KICK_SUPERSEDED, kick.reason)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (a1.isOpen && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(a1.isOpen)
        assertTrue(a2.isOpen, "the new connection is untouched")
    }

    @Test
    fun broadcastQueuesWithoutWaitingAndHonoursTheFilter() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val (s1, ws1) = fakeSession("dev-1")
        val (s2, ws2) = fakeSession("dev-2")
        val (s3, ws3) = fakeSession("dev-3")
        pool.register(s1); pool.register(s2); pool.register(s3)

        val ping = com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec.encode(RelayFrame.Ping(ts = 1))
        assertEquals(3, pool.broadcastText(ping))
        assertEquals(2, pool.broadcastText(ping, filter = { it.deviceId != "dev-2" }))

        assertIs<RelayFrame.Ping>(ws1.next())
        assertIs<RelayFrame.Ping>(ws1.next())
        assertIs<RelayFrame.Ping>(ws2.next())
        assertIs<RelayFrame.Ping>(ws3.next())
        assertIs<RelayFrame.Ping>(ws3.next())
    }

    /** REL-05: one stuck client must not block a broadcast; it is cut off instead. */
    @Test
    fun aStalledClientIsFailedNotWaitedFor() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val (stuck, _) = fakeSession("stuck", outgoingCapacity = Channel.RENDEZVOUS, outboundQueue = 2, sendTimeoutMs = 60_000)
        val (healthy, healthyWs) = fakeSession("healthy")
        pool.register(stuck); pool.register(healthy)

        val ping = com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec.encode(RelayFrame.Ping(ts = 1))
        val started = System.nanoTime()
        repeat(10) { pool.broadcastText(ping) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 2_000, "broadcast must not wait for the stuck client (took $tookMs ms)")
        assertFalse(stuck.isOpen, "a full outbound queue closes the slow consumer")
        assertEquals("slow_consumer", stuck.reasonClosed)
        assertTrue(healthy.isOpen)
        repeat(10) { assertIs<RelayFrame.Ping>(healthyWs.next()) }
    }

    @Test
    fun closeAllKicksEveryoneInParallelAndEmptiesThePool() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val sessions = (1..20).map { fakeSession("dev-$it") }
        sessions.forEach { pool.register(it.first) }

        val started = System.nanoTime()
        pool.closeAll("server_shutdown", timeoutMs = 5_000)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(0, pool.activeCount)
        assertTrue(sessions.all { !it.first.isOpen })
        sessions.forEach { (_, ws) -> assertEquals("server_shutdown", ws.nextOf<RelayFrame.Kick>().reason) }
        assertTrue(tookMs < 4_000, "took $tookMs ms")
    }

    @Test
    fun closeAllGivesUpOnAStuckSocketAfterTheTimeoutAndStillClosesTheRest() = runBlocking<Unit> {
        val pool = RelayConnectionPool()
        val (stuck, _) = fakeSession("stuck", outgoingCapacity = Channel.RENDEZVOUS, sendTimeoutMs = 60_000)
        val (fine, fineWs) = fakeSession("fine")
        pool.register(stuck); pool.register(fine)

        val started = System.nanoTime()
        pool.closeAll("server_shutdown", timeoutMs = 400)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 3_000, "must not wait the stuck client's 60 s send timeout (took $tookMs ms)")
        assertEquals(0, pool.activeCount)
        assertFalse(stuck.isOpen)
        assertFalse(fine.isOpen)
        assertEquals("server_shutdown", fineWs.nextOf<RelayFrame.Kick>().reason)
    }
}
