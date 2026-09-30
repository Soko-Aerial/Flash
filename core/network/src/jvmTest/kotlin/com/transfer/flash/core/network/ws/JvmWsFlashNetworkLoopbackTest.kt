package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.common.result.FlashResult
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15-5: the smallest test that proves the DUPLICATED desktop plumbing actually
 * interoperates with itself, executed on the desktop tier — the same tier Phase 16's
 * desktop↔Android interop gate runs on.
 *
 * Two [JvmWsFlashNetwork] instances in one process dial each other over real loopback sockets:
 * the full path runs — [WsTransferServer] accepts, the duplicated [WsTransferClient] performs the
 * HTTP upgrade, both sides exchange `FLASH_WS_HELLO`, the session registry admits both
 * directions, and a text frame crosses after registration. That exercises every jvmMain
 * duplicate introduced in 15-3/15-4 except the TLS pair (which needs a software keystore; see
 * Known issues in the migration log).
 *
 * The connect-glare path is covered too: B also dials A, so the deterministic originator-id
 * tiebreaker (ERROR-023) must converge both instances onto ONE session per peer rather than
 * letting the sockets cross-wire and die.
 */
@OptIn(FlashInternalApi::class)
class JvmWsFlashNetworkLoopbackTest {

    private val testScope = CoroutineScope(Dispatchers.IO)

    private fun newNetwork(deviceId: String, name: String): JvmWsFlashNetwork =
        JvmWsFlashNetwork(localDeviceId = deviceId, localFriendlyName = name)

    @Test
    fun `two desktop instances handshake and exchange a text frame over loopback`() {
        val a = newNetwork("device-A", "Desktop A")
        val b = newNetwork("device-B", "Desktop B")
        try {
            val portA = (runBlocking { a.start(0) } as FlashResult.Success).value
            assertTrue("server should bind a real port", portA > 0)
            val portB = (runBlocking { b.start(0) } as FlashResult.Success).value
            assertTrue(portB > 0)

            // A dials B. connectManual takes host + port directly.
            val connectResult = runBlocking {
                withTimeout(15_000) { a.connectManual("127.0.0.1", portB) }
            }
            assertTrue("A dialing B must succeed: $connectResult", connectResult is FlashResult.Success)

            // Wait until B's inbound side has registered A's session.
            assertTrue(
                "B must register A's session",
                awaitTrue(10_000) { b.activeSessions.value.isNotEmpty() },
            )

            // Drain B's inbound text frames while A sends one.
            val received = ConcurrentLinkedQueue<String>()
            val sessionB = b.activeSessions.value.values.first() as WsSession
            val job: Job = testScope.launch {
                sessionB.incomingText.collect { received.add(it) }
            }

            val sessionA = (connectResult as FlashResult.Success).value
            runBlocking { sessionA.sendText("ping-from-A") }

            assertTrue("B must receive the text frame", awaitTrue(10_000) { received.isNotEmpty() })
            assertEquals("ping-from-A", received.first())
            job.cancel()
        } finally {
            runBlocking { a.stop() }
            runBlocking { b.stop() }
        }
    }

    @Test
    fun `both HELLOs carry the group protocol level to each side (ADR-044 V1)`() {
        val a = newNetwork("device-A", "Desktop A")
        val b = newNetwork("device-B", "Desktop B")
        try {
            runBlocking { a.start(0) }
            val portB = (runBlocking { b.start(0) } as FlashResult.Success).value
            val dial = runBlocking { withTimeout(15_000) { a.connectManual("127.0.0.1", portB) } }
            assertTrue("dial must succeed: $dial", dial is FlashResult.Success)
            assertTrue("B must register A", awaitTrue(10_000) { b.activeSessions.value.isNotEmpty() })

            // The dialer learned it from the accepting side's reply, the acceptor from the dialer's HELLO.
            val peerAsSeenByA = (dial as FlashResult.Success).value.peer
            val peerAsSeenByB = b.activeSessions.value.values.single().peer
            assertEquals(FlashProtocol.GROUP_PROTOCOL_LEVEL, peerAsSeenByA.groupProtocol)
            assertEquals(FlashProtocol.GROUP_PROTOCOL_LEVEL, peerAsSeenByB.groupProtocol)
            assertTrue("this build must advertise at least level 2", peerAsSeenByA.groupProtocol >= 2)
        } finally {
            runBlocking { a.stop() }
            runBlocking { b.stop() }
        }
    }

    @Test
    fun `connect glare between two instances converges on one session per peer`() {
        val a = newNetwork("device-A", "Desktop A")
        val b = newNetwork("device-B", "Desktop B")
        try {
            val portA = (runBlocking { a.start(0) } as FlashResult.Success).value
            val portB = (runBlocking { b.start(0) } as FlashResult.Success).value
            assertTrue(portA > 0 && portB > 0)

            // Both dial each other at once — the classic glare setup.
            val jobA = testScope.launch { a.connectManual("127.0.0.1", portB) }
            val jobB = testScope.launch { b.connectManual("127.0.0.1", portA) }

            // The registries must converge: exactly one live session per peer on each side.
            assertTrue(
                "both sides must reach a session",
                awaitTrue(15_000) {
                    a.activeSessions.value.isNotEmpty() && b.activeSessions.value.isNotEmpty()
                },
            )
            assertTrue(
                "A must end with exactly one session for B (had ${a.activeSessions.value.size})",
                awaitTrue(5_000) { a.activeSessions.value.size == 1 },
            )
            assertTrue(
                "B must end with exactly one session for A (had ${b.activeSessions.value.size})",
                awaitTrue(5_000) { b.activeSessions.value.size == 1 },
            )
            jobA.cancel(); jobB.cancel()
        } finally {
            runBlocking { a.stop() }
            runBlocking { b.stop() }
        }
    }

    @Test
    fun `both HELLOs settle one pinger per pair (PC1)`() {
        // Equal tiers: the smaller id pings. Then B on the LOW tier: A's shorter interval wins.
        val cases = listOf(
            Triple(FlashPerformanceMode.HIGH, FlashPerformanceMode.HIGH, "equal tiers"),
            Triple(FlashPerformanceMode.HIGH, FlashPerformanceMode.LOW, "B slower"),
        )
        cases.forEach { (tierA, tierB, label) ->
            val a = JvmWsFlashNetwork(localDeviceId = "device-A", localFriendlyName = "A", transportProfile = { tierA.transport })
            val b = JvmWsFlashNetwork(localDeviceId = "device-B", localFriendlyName = "B", transportProfile = { tierB.transport })
            try {
                runBlocking { a.start(0) }
                val portB = (runBlocking { b.start(0) } as FlashResult.Success).value
                // B dials, so the dialer is not simply the pinger.
                val dial = runBlocking { withTimeout(15_000) { b.connectManual("127.0.0.1", a.serverPort) } }
                assertTrue("$label: dial must succeed: $dial", dial is FlashResult.Success)
                assertTrue(portB > 0)
                assertTrue("$label: A must register B", awaitTrue(10_000) { a.activeSessions.value.isNotEmpty() })

                val roleA = (a.activeSessions.value.values.single() as WsSession).connection.pingRole
                val roleB = (b.activeSessions.value.values.single() as WsSession).connection.pingRole
                assertEquals("$label: A", WsKeepalive.PingRole.PINGER, roleA)
                assertEquals("$label: B", WsKeepalive.PingRole.ANSWERER, roleB)
            } finally {
                runBlocking { a.stop() }
                runBlocking { b.stop() }
            }
        }
    }

    /** Bounded spin for assertions that cross real-socket thread boundaries. */
    private fun awaitTrue(timeoutMs: Long = 10_000L, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
