package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WsFlashNetworkTest {

    private var serverNetwork: WsFlashNetwork? = null
    private var clientNetwork: WsFlashNetwork? = null

    @After
    fun tearDown() {
        runBlocking {
            serverNetwork?.stop()
            clientNetwork?.stop()
        }
    }

    @Test(timeout = 10_000L)
    fun testServerAndClientMeshSession() {
        runBlocking {
            serverNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = "device-server-123",
                localFriendlyName = "Server Phone",
            )
            val serverStart = serverNetwork!!.start(0)
            assertTrue(serverStart is FlashResult.Success)
            val serverPort = (serverStart as FlashResult.Success).value
            assertTrue(serverPort > 0)

            clientNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = "device-client-456",
                localFriendlyName = "Client Phone",
            )
            val clientStart = clientNetwork!!.start(0)
            assertTrue(clientStart is FlashResult.Success)

            // Connect client to server
            val connectResult = clientNetwork!!.connectManual("127.0.0.1", serverPort)
            assertTrue(connectResult is FlashResult.Success)
            val clientSession = (connectResult as FlashResult.Success).value as WsSession

            assertEquals("device-server-123", clientSession.peerDeviceId.value)
            assertEquals("Server Phone", clientSession.peer.friendlyName)

            // Wait for server to observe inbound session
            var serverSession: WsSession? = null
            for (i in 1..50) {
                val sessions = serverNetwork!!.activeSessions.value
                if (sessions.isNotEmpty()) {
                    serverSession = sessions.values.first() as WsSession
                    break
                }
                kotlinx.coroutines.delay(50)
            }
            assertNotNull("Server should have registered inbound session", serverSession)
            assertEquals("device-client-456", serverSession!!.peerDeviceId.value)
            assertEquals("Client Phone", serverSession.peer.friendlyName)

            // Test Text message send from client to server
            var receivedText: String? = null
            val serverTextJob = launch {
                receivedText = serverSession!!.incomingText.first()
            }
            kotlinx.coroutines.delay(20)
            clientSession.sendText("Hello Server!")
            serverTextJob.join()
            assertEquals("Hello Server!", receivedText)

            // Test Binary message send from server to client
            val sampleBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            var receivedBytes: ByteArray? = null
            val clientBinaryJob = launch {
                receivedBytes = clientSession.incomingBinary.first()
            }
            kotlinx.coroutines.delay(20)
            serverSession!!.send(sampleBytes)
            clientBinaryJob.join()
            assertNotNull(receivedBytes)
            assertEquals(8, receivedBytes!!.size)
            assertEquals(1.toByte(), receivedBytes!![0])
            assertEquals(8.toByte(), receivedBytes!![7])

            // Test Disconnect
            clientSession.disconnect("Test finished")
            kotlinx.coroutines.delay(100)
            assertEquals(0, clientNetwork!!.activeSessions.value.size)
        }
    }

    /**
     * Health is derived from live signals, not hardcoded: Offline before any peer session, Connected
     * once a session exists (both ends), back to Offline after the last session drops. Regression
     * against the old wiring that pinned `connectionHealth` to Connected on start() / Offline on stop().
     */
    @Test(timeout = 10_000L)
    fun testConnectionHealthReflectsSessionLifecycle() {
        runBlocking {
            serverNetwork = WsFlashNetwork(null, "device-server-h", "Server Phone")
            val serverStart = serverNetwork!!.start(0)
            val serverPort = (serverStart as FlashResult.Success).value

            clientNetwork = WsFlashNetwork(null, "device-client-h", "Client Phone")
            clientNetwork!!.start(0)

            // Server merely listening + client started, but no peer session yet → Offline.
            assertEquals(FlashConnectionHealth.Offline, clientNetwork!!.connectionHealth.value)

            val connect = clientNetwork!!.connectManual("127.0.0.1", serverPort)
            val clientSession = (connect as FlashResult.Success).value as WsSession

            // A live session drives Connected on the dialing side immediately after connectManual returns.
            assertEquals(FlashConnectionHealth.Connected, clientNetwork!!.connectionHealth.value)

            // The accepting side reaches Connected once the inbound session registers.
            for (i in 1..50) {
                if (serverNetwork!!.connectionHealth.value == FlashConnectionHealth.Connected) break
                delay(50)
            }
            assertEquals(FlashConnectionHealth.Connected, serverNetwork!!.connectionHealth.value)

            // Dropping the only session returns the dialing side to Offline (no peers, no attempts).
            clientSession.disconnect("done")
            for (i in 1..50) {
                if (clientNetwork!!.connectionHealth.value == FlashConnectionHealth.Offline) break
                delay(50)
            }
            assertEquals(FlashConnectionHealth.Offline, clientNetwork!!.connectionHealth.value)
        }
    }

    /**
     * The concurrency cap ([SessionHardeningPolicy.maxConcurrentSessions]) is enforced at
     * registration: with a cap of 1, the server admits the first peer and rejects the second, so its
     * active-session set never exceeds the cap even under two simultaneous inbound peers.
     */
    @Test(timeout = 15_000L)
    fun testConcurrencyCapRejectsExcessInboundSessions() {
        runBlocking {
            serverNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = "device-server-cap",
                localFriendlyName = "Server Phone",
                hardeningPolicy = SessionHardeningPolicy(maxConcurrentSessions = 1),
            )
            val serverPort = (serverNetwork!!.start(0) as FlashResult.Success).value

            val clientA = WsFlashNetwork(null, "device-client-A", "Client A")
            val clientB = WsFlashNetwork(null, "device-client-B", "Client B")
            try {
                clientA.start(0)
                clientB.start(0)

                val a = clientA.connectManual("127.0.0.1", serverPort)
                assertTrue(a is FlashResult.Success)
                for (i in 1..50) {
                    if (serverNetwork!!.activeSessions.value.size == 1) break
                    delay(50)
                }
                assertEquals(1, serverNetwork!!.activeSessions.value.size)

                // The second distinct peer's HELLO round-trips, but the server must reject it at the
                // cap. Give it a generous window to (fail to) register, then assert the cap held.
                clientB.connectManual("127.0.0.1", serverPort)
                delay(500)
                assertEquals(1, serverNetwork!!.activeSessions.value.size)
            } finally {
                clientA.stop()
                clientB.stop()
            }
        }
    }

    /**
     * Connect-glare regression (ERROR-023): A and B both run servers AND both dial each other
     * simultaneously — the exact storm that produced the ~2s "WS connecting" flap loop on device.
     *
     * Pre-fix, each side kept whichever session registered first (a coin flip), and ~50% of the
     * time the two ends cross-wired: A kept its outbound, B kept its outbound, both sockets dead →
     * both schedules reconnect → glare again forever. The fix is a deterministic tiebreaker that
     * converges both ends on ONE socket: keep the session whose ORIGINATOR id is lexicographically
     * smaller (A's outbound IS B's inbound — the same TCP pair — so both sides compute the same winner).
     *
     * Asserts:
     * - exactly one live session per side for the peer,
     * - the surviving sessions are ONE pair (A keeps its outbound since A is the smaller id, so B
     *   must end up holding the inbound — never two outbounds = the cross-wired dead pair),
     * - a message still round-trips over the survivor (the socket is genuinely live).
     */
    @Test(timeout = 15_000L)
    fun testConnectGlareConvergesOnSingleLivePair() {
        runBlocking {
            // "device-a" < "device-b" lexicographically, so the tiebreaker keeps A's outbound
            // (originator A) on A and B's inbound (originator A, mirrored) on B.
            val aId = "device-a-glare"
            val bId = "device-b-glare"

            serverNetwork = WsFlashNetwork(null, aId, "Phone A")
            clientNetwork = WsFlashNetwork(null, bId, "Phone B")
            val aPort = (serverNetwork!!.start(0) as FlashResult.Success).value
            val bPort = (clientNetwork!!.start(0) as FlashResult.Success).value

            // Simultaneous dials — both peers dial each other at the same moment.
            val dialA = launch { serverNetwork!!.connectManual("127.0.0.1", bPort) }
            val dialB = launch { clientNetwork!!.connectManual("127.0.0.1", aPort) }
            dialA.join()
            dialB.join()

            // Settle: both sides must converge to exactly one session each, with the surviving
            // sessions forming ONE socket pair (not two cross-wired outbounds).
            var aSession: WsSession? = null
            var bSession: WsSession? = null
            withTimeout(5_000L) {
                while (true) {
                    val aSessions = serverNetwork!!.activeSessions.value
                    val bSessions = clientNetwork!!.activeSessions.value
                    if (aSessions.size == 1 && bSessions.size == 1) {
                        aSession = aSessions.values.first() as WsSession
                        bSession = bSessions.values.first() as WsSession
                        // Anti-cross-wire check: A (smaller id) must hold the OUTBOUND side and B the
                        // INBOUND side of the same TCP pair. Two outbounds = dead cross-wired pair.
                        if (aSession!!.isOutbound && !bSession!!.isOutbound) break
                    }
                    delay(25)
                }
            }

            assertEquals("device-b-glare", aSession!!.peerDeviceId.value)
            assertEquals("device-a-glare", bSession!!.peerDeviceId.value)
            assertTrue("A keeps the outbound side of the converged pair", aSession!!.isOutbound)
            assertTrue("B holds the inbound side of the converged pair", !bSession!!.isOutbound)

            // Liveness: the survivor must actually carry traffic.
            var echoed: String? = null
            val job = launch { echoed = bSession!!.incomingText.first() }
            delay(20)
            aSession!!.sendText("glare survivor")
            withTimeout(2_000L) { job.join() }
            assertEquals("glare survivor", echoed)

            // And neither side rescheduled a redundant reconnect for the peer (no storm).
            delay(200)
            assertEquals(1, serverNetwork!!.activeSessions.value.size)
            assertEquals(1, clientNetwork!!.activeSessions.value.size)
        }
    }
}
