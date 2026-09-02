package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.model.FlashDeviceId
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
import org.junit.Assert.assertFalse
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

    /**
     * ERROR-026 backup redial: the ACCEPTING side of a session must be able to recover it alone.
     *
     * [reconnectTargets][WsFlashNetwork] is written only by `connectManual`, so over a Wi-Fi hotspot
     * every session the host holds is inbound and the #18 reconnect engine had nothing to redial —
     * the host simply waited for the dialer to notice the drop, which never happens while the
     * dialer's process is frozen by screen-off/Doze. The host now falls back to the discovery-fed
     * endpoint memory.
     *
     * The setup makes the host's own loop the ONLY thing that can restore the session: the client
     * leaves via `disconnect`, which clears the client's reconnect target and records its
     * local-disconnect intent, so the client will never redial. The regained session must therefore
     * be OUTBOUND on the host — inbound would mean the client came back and the test proved nothing.
     */
    @Test(timeout = 20_000L)
    fun testAcceptingSideBackupRedialRecoversInboundOnlySession() {
        runBlocking {
            val hostId = "device-host-backup"
            val clientId = "device-client-backup"

            serverNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = hostId,
                localFriendlyName = "Host Phone",
                // Attempt 0's jitter window is [base, base], so the first backup attempt lands at
                // exactly this value — 150ms here instead of the production 4s floor.
                backupRedialBaseMs = 150L,
            )
            val hostPort = (serverNetwork!!.start(0) as FlashResult.Success).value

            clientNetwork = WsFlashNetwork(null, clientId, "Client Phone")
            val clientPort = (clientNetwork!!.start(0) as FlashResult.Success).value

            // Discovery's job in production (DiscoveryRouteBinder); handed over directly here.
            serverNetwork!!.rememberEndpoint(clientId, "127.0.0.1", clientPort)

            // Client dials, so the host's session is INBOUND and has no reconnect target.
            assertTrue(clientNetwork!!.connectManual("127.0.0.1", hostPort) is FlashResult.Success)
            withTimeout(5_000L) {
                while (serverNetwork!!.activeSessions.value.isEmpty()) delay(25)
            }
            assertFalse(
                "Host's session must be inbound for this regression to mean anything",
                (serverNetwork!!.activeSessions.value.values.first() as WsSession).isOutbound,
            )

            // Deliberate client-side teardown: its target is gone, so only the host can recover.
            clientNetwork!!.disconnect(FlashDeviceId(hostId))

            var regained: WsSession? = null
            withTimeout(10_000L) {
                while (true) {
                    val session = serverNetwork!!.activeSessions.value[FlashDeviceId(clientId)] as? WsSession
                    if (session != null && session.isOutbound) {
                        regained = session
                        break
                    }
                    delay(25)
                }
            }
            assertEquals(clientId, regained!!.peerDeviceId.value)

            // Liveness: the recovered socket has to actually carry traffic.
            var clientSession: WsSession? = null
            withTimeout(5_000L) {
                while (true) {
                    val session = clientNetwork!!.activeSessions.value[FlashDeviceId(hostId)] as? WsSession
                    if (session != null) {
                        clientSession = session
                        break
                    }
                    delay(25)
                }
            }
            var echoed: String? = null
            val job = launch { echoed = clientSession!!.incomingText.first() }
            delay(20)
            regained!!.sendText("backup redial")
            withTimeout(2_000L) { job.join() }
            assertEquals("backup redial", echoed)
        }
    }

    /**
     * The ERROR-026 backup loop must still honour an explicit local teardown. `reconnectTargets`
     * used to encode that intent implicitly — no target meant "do not redial" — but an inbound-only
     * peer has no target to clear, so the intent is now recorded explicitly and consulted by the
     * backup loop. Asserted with the route still present in endpoint memory, so a suppressed loop
     * can only be the veto and not a missing route.
     */
    @Test(timeout = 15_000L)
    fun testLocalDisconnectSuppressesBackupRedialOfInboundPeer() {
        runBlocking {
            val hostId = "device-host-veto"
            val clientId = "device-client-veto"

            serverNetwork = WsFlashNetwork(null, hostId, "Host Phone", backupRedialBaseMs = 150L)
            val hostPort = (serverNetwork!!.start(0) as FlashResult.Success).value
            clientNetwork = WsFlashNetwork(null, clientId, "Client Phone")
            val clientPort = (clientNetwork!!.start(0) as FlashResult.Success).value
            serverNetwork!!.rememberEndpoint(clientId, "127.0.0.1", clientPort)

            assertTrue(clientNetwork!!.connectManual("127.0.0.1", hostPort) is FlashResult.Success)
            withTimeout(5_000L) {
                while (serverNetwork!!.activeSessions.value.isEmpty()) delay(25)
            }

            // The HOST drops its inbound peer on purpose.
            serverNetwork!!.disconnect(FlashDeviceId(clientId))

            // `activeSessions` is republished only by registerSession/onSessionDisconnected, so an
            // empty map proves the host's disconnect callback has already run — the assertions below
            // are therefore not just winning a race against it. (The client, for which this drop was
            // unexpected, does redial after its own ~1s primary backoff; that is #18 behaving
            // correctly and is well outside this window.)
            withTimeout(5_000L) {
                while (serverNetwork!!.activeSessions.value.isNotEmpty()) delay(25)
            }

            assertNotNull(
                "Endpoint memory must still hold the route, or the veto is untested",
                serverNetwork!!.endpointOf(clientId),
            )
            assertFalse(
                "A locally disconnected peer must not be backup-redialed",
                serverNetwork!!.isReconnectInFlight(clientId),
            )
        }
    }

    // -------------------------------------------------------------------------------------------
    // ERROR-031: a stale session must never veto its own replacement.
    // -------------------------------------------------------------------------------------------

    /**
     * The force-stop bug. A peer whose socket died unnoticed reconnects; the new connection completes
     * its HELLO handshake and reaches [WsFlashNetwork] registration — where the incumbent used to win.
     *
     * `resolveGlareTie` compares session ORIGINATORS, which is the right question for the two
     * directions of one TCP pair (ERROR-023) and a meaningless one for two sessions facing the SAME
     * way: they share an originator, so the comparison always tied, and a tie kept the incumbent.
     * The stale session therefore rejected every reconnect the peer made, forever. Presence is
     * computed from `activeSessions`, so the dot said Online; writes into the dead socket returned
     * success, so messages got one tick and never arrived. Force-stopping both apps was the only fix.
     *
     * Two sessions in the same direction are two different TCP pairs, and the side that dialed has
     * already abandoned the first, so the newcomer wins unconditionally.
     */
    @Test(timeout = 20_000L)
    fun testSecondDialInSameDirectionSupersedesStaleSession() {
        runBlocking {
            val hostId = "device-host-supersede"
            val clientId = "device-client-supersede"

            serverNetwork = WsFlashNetwork(null, hostId, "Host Phone")
            val hostPort = (serverNetwork!!.start(0) as FlashResult.Success).value
            clientNetwork = WsFlashNetwork(null, clientId, "Client Phone")
            clientNetwork!!.start(0)

            val first = clientNetwork!!.connectManual("127.0.0.1", hostPort)
            assertTrue(first is FlashResult.Success)
            val firstSession = (first as FlashResult.Success).value as WsSession
            withTimeout(5_000L) {
                while (serverNetwork!!.activeSessions.value.isEmpty()) delay(25)
            }
            val staleOnHost = serverNetwork!!.activeSessions.value[FlashDeviceId(clientId)] as WsSession

            // The peer dials again over a brand-new socket while the host still holds the old one.
            // Pre-fix this returned Failure("Session not admitted") on every attempt.
            val second = clientNetwork!!.connectManual("127.0.0.1", hostPort)
            assertTrue("The peer's fresh reconnect must be admitted", second is FlashResult.Success)
            val secondSession = (second as FlashResult.Success).value as WsSession

            // Exactly one session per side, and on the host it is the NEW one.
            withTimeout(5_000L) {
                while (true) {
                    val onHost = serverNetwork!!.activeSessions.value[FlashDeviceId(clientId)]
                    if (serverNetwork!!.activeSessions.value.size == 1 &&
                        clientNetwork!!.activeSessions.value.size == 1 &&
                        onHost != null && onHost !== staleOnHost
                    ) {
                        break
                    }
                    delay(25)
                }
            }
            val liveOnHost = serverNetwork!!.activeSessions.value[FlashDeviceId(clientId)] as WsSession
            assertFalse("The superseded session must be gone", firstSession.connection.isOpen)
            assertTrue("The admitted session must be the live one", secondSession.connection.isOpen)

            // Liveness: the survivor genuinely carries traffic in both directions.
            var onHostText: String? = null
            val hostJob = launch { onHostText = liveOnHost.incomingText.first() }
            delay(20)
            secondSession.sendText("after reconnect")
            withTimeout(2_000L) { hostJob.join() }
            assertEquals("after reconnect", onHostText)

            var onClientText: String? = null
            val clientJob = launch { onClientText = secondSession.incomingText.first() }
            delay(20)
            liveOnHost.sendText("host reply")
            withTimeout(2_000L) { clientJob.join() }
            assertEquals("host reply", onClientText)

            // And the supersede did not touch the peer's own view: still exactly one session.
            delay(200)
            assertEquals(1, serverNetwork!!.activeSessions.value.size)
            assertEquals(1, clientNetwork!!.activeSessions.value.size)
        }
    }

    /**
     * Frames that cross a same-direction supersede must survive it.
     *
     * Two windows used to drop them: `connectManual` cleared the connection's early-frame queue
     * immediately before `registerSession` could flush it, and a rejected candidate's queue was
     * discarded with the connection. A dropped inbound chat frame is a `DeliveryReceipt` that never
     * comes back — the other half of "ticks once and never arrives".
     *
     * Sending the moment each dial returns lands inside those windows on one side or the other, and
     * the repeat gives the race several chances; every frame must still arrive.
     */
    @Test(timeout = 30_000L)
    fun testFramesAreNotLostAcrossReconnect() {
        runBlocking {
            val hostId = "device-host-frames"
            val clientId = "device-client-frames"

            serverNetwork = WsFlashNetwork(null, hostId, "Host Phone")
            val hostPort = (serverNetwork!!.start(0) as FlashResult.Success).value
            clientNetwork = WsFlashNetwork(null, clientId, "Client Phone")
            clientNetwork!!.start(0)

            var previousOnHost: WsSession? = null
            repeat(5) { attempt ->
                val dial = clientNetwork!!.connectManual("127.0.0.1", hostPort)
                assertTrue("dial #$attempt must be admitted", dial is FlashResult.Success)
                val session = (dial as FlashResult.Success).value as WsSession
                // No settle delay: the host may still be between HELLO and registerSession, which is
                // exactly the window whose queue used to be discarded.
                session.sendText("frame-$attempt")

                var onHost: WsSession? = null
                withTimeout(5_000L) {
                    while (true) {
                        val candidate = serverNetwork!!.activeSessions.value[FlashDeviceId(clientId)] as? WsSession
                        if (candidate != null && candidate !== previousOnHost) {
                            onHost = candidate
                            break
                        }
                        delay(10)
                    }
                }
                val seen = withTimeout(5_000L) { onHost!!.incomingText.first() }
                assertEquals("frame-$attempt must survive the supersede", "frame-$attempt", seen)
                previousOnHost = onHost
            }
        }
    }

    /**
     * [WsFlashNetwork.hasLiveSession] is the predicate every recovery path now gates on instead of
     * mere map presence, so it must answer "is this session carrying traffic", not "does a session
     * object exist". Driven off an injected clock: the session stays real and open throughout, and
     * only the reading of *now* moves.
     */
    @Test(timeout = 15_000L)
    fun testHasLiveSessionRejectsASessionThatStoppedReceiving() {
        runBlocking {
            val hostId = "device-host-fresh"
            val clientId = "device-client-fresh"

            // Same epoch as the connection's own clock — the connection stamps inbound frames with
            // System.currentTimeMillis(), so only an offset from that reading is meaningful.
            var fakeNow = System.currentTimeMillis()
            serverNetwork = WsFlashNetwork(
                context = null,
                localDeviceId = hostId,
                localFriendlyName = "Host Phone",
                nowMs = { fakeNow },
            )
            val hostPort = (serverNetwork!!.start(0) as FlashResult.Success).value
            clientNetwork = WsFlashNetwork(null, clientId, "Client Phone")
            clientNetwork!!.start(0)

            assertFalse("No session at all is not a live session", serverNetwork!!.hasLiveSession(clientId))

            assertTrue(clientNetwork!!.connectManual("127.0.0.1", hostPort) is FlashResult.Success)
            withTimeout(5_000L) {
                while (serverNetwork!!.activeSessions.value.isEmpty()) delay(25)
            }
            assertTrue("A just-handshaked session is live", serverNetwork!!.hasLiveSession(clientId))

            // Wall clock moves on while the socket stays open and silent — the zombie's signature.
            // Nothing about the session object changed; the honest answer did.
            fakeNow += 60_000L
            assertFalse(
                "A session with no inbound frame for a minute must not veto recovery",
                serverNetwork!!.hasLiveSession(clientId),
            )
            fakeNow -= 60_000L
            assertTrue("...and the verdict tracks the clock", serverNetwork!!.hasLiveSession(clientId))

            // A closed connection is never live, however recent its last frame was.
            (clientNetwork!!.activeSessions.value[FlashDeviceId(hostId)] as WsSession)
                .disconnect("peer left")
            withTimeout(5_000L) {
                while (serverNetwork!!.hasLiveSession(clientId)) delay(25)
            }
        }
    }
}
