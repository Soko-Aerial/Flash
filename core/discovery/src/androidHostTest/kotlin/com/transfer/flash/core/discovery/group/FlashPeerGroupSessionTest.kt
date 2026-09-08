package com.transfer.flash.core.discovery.group

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic plain-JVM tests for [FlashPeerGroupSession] — NO
 * kotlinx-coroutines-test dependency.
 *
 * Determinism strategy: [DirectDispatcher] executes every `dispatch` call
 * INLINE on the calling thread, so coroutines launched into the session's
 * injected scope run eagerly to their first suspension point, and
 * `retryDelayMs = { 0 }` collapses backoff sleeps (kotlinx `delay(<= 0)`
 * returns without suspending). All blocking in these tests therefore happens
 * inside the FAKE CONNECTOR on worker threads, controlled by latches — the
 * session itself never sees wall-clock time.
 *
 * Overlap note: a strictly inline dispatcher serializes fan-out jobs at launch
 * time; genuine mid-send overlap is produced by having the fake connector
 * block inside `send` on a latch while the test observes [FlashPeerGroupSession.peerStates]
 * from the main thread. The invariant observed — one peer terminal-Failed
 * while another sits mid-Sending, untouched — is exactly the isolation contract.
 */
private class DirectDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        block.run()
    }
}

/** Minimal scripted fake: tests override via delegation or use send hooks. */
private class FakeConnector : GroupPeerConnector {
    val disconnects = mutableListOf<String>()

    /** Per-endpoint send hooks returning success/failure; default = succeed. */
    val sendHooks = ConcurrentHashMap<String, (Long, (Long) -> Unit) -> Boolean>()

    override suspend fun connect(endpointId: String): Boolean = true

    override fun send(endpointId: String, payloadSizeBytes: Long, onProgress: (Long) -> Unit): Boolean {
        val hook = sendHooks[endpointId]
        if (hook != null) return hook(payloadSizeBytes, onProgress)
        onProgress(payloadSizeBytes)
        return true
    }

    override suspend fun disconnect(endpointId: String) {
        disconnects.add(endpointId)
    }
}

private class TestHarness(
    connector: GroupPeerConnector,
    maxConnectAttempts: Int = 3,
    retryDelayMs: (Int) -> Long = { 0 },
) {
    val scope = CoroutineScope(DirectDispatcher() + Job())
    val session = FlashPeerGroupSession(connector, scope, maxConnectAttempts, retryDelayMs)

    fun shutdown() = scope.cancel()
}

class FlashPeerGroupSessionTest {

    @Test
    fun `happy path - all peers Online then Done with byte totals`() {
        val connector = FakeConnector()
        val harness = TestHarness(connector)
        try {
            runBlocking {
                harness.session.connectAll(listOf("a", "b", "c"))

                assertEquals(PeerLinkStateKind.Online, harness.session.peerStates.value.getValue("a").kind)
                assertEquals(PeerLinkStateKind.Online, harness.session.peerStates.value.getValue("b").kind)
                assertEquals(PeerLinkStateKind.Online, harness.session.peerStates.value.getValue("c").kind)
                assertEquals(1, harness.session.peerStates.value.getValue("a").attempt)

                assertTrue(harness.session.sendToAll(TOTAL_BYTES))

                val states = harness.session.peerStates.value
                assertEquals(setOf("a", "b", "c"), states.keys)
                states.values.forEach { state ->
                    assertEquals(PeerLinkStateKind.Done, state.kind)
                    assertEquals(TOTAL_BYTES, state.bytesTotal)
                    assertEquals(TOTAL_BYTES, state.bytesSent)
                    assertNull(state.error)
                }
            }
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun `partial failure isolation - exhausted peer stays Failed and untouched while others complete`() {
        val connector = FakeConnector()
        // victim always fails to connect; others delegate (succeed).
        val scripted = object : GroupPeerConnector by connector {
            override suspend fun connect(endpointId: String): Boolean =
                if (endpointId == "victim") false else connector.connect(endpointId)

            override fun send(endpointId: String, payloadSizeBytes: Long, onProgress: (Long) -> Unit): Boolean =
                if (endpointId == "survivor") {
                    onProgress(payloadSizeBytes / 2)
                    onProgress(payloadSizeBytes)
                    true
                } else {
                    error("victim must never be sent to — it never came Online")
                }
        }
        val harness = TestHarness(scripted, maxConnectAttempts = 2)
        try {
            runBlocking {
                harness.session.connectAll(listOf("victim", "survivor"))

                val afterConnect = harness.session.peerStates.value
                assertEquals(PeerLinkStateKind.Failed, afterConnect.getValue("victim").kind)
                assertEquals(2, afterConnect.getValue("victim").attempt)
                assertTrue(afterConnect.getValue("victim").error!!.contains("2 attempts"))
                assertEquals(PeerLinkStateKind.Online, afterConnect.getValue("survivor").kind)

                // Only ONLINE peers are targeted: survivor alone → all succeeded.
                assertTrue(harness.session.sendToAll(TOTAL_BYTES))

                val afterSend = harness.session.peerStates.value
                // Survivor completed normally...
                assertEquals(PeerLinkStateKind.Done, afterSend.getValue("survivor").kind)
                assertEquals(TOTAL_BYTES, afterSend.getValue("survivor").bytesSent)
                // ...while the failed peer's row is EXACTLY as before the burst.
                assertEquals(afterConnect.getValue("victim"), afterSend.getValue("victim"))
            }
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun `mid-burst failure - victim Failed during survivor's send does not disturb it`() {
        val connector = FakeConnector()
        val survivorMidSend = CountDownLatch(1)
        val releaseSurvivor = CountDownLatch(1)
        val scripted = object : GroupPeerConnector by connector {
            override fun send(endpointId: String, payloadSizeBytes: Long, onProgress: (Long) -> Unit): Boolean {
                if (endpointId == "survivor") {
                    onProgress(payloadSizeBytes / 2)
                    survivorMidSend.countDown()
                    releaseSurvivor.await(5, TimeUnit.SECONDS)
                    onProgress(payloadSizeBytes)
                    return true
                }
                return false // victim's send fails immediately, mid-burst
            }
        }
        val harness = TestHarness(scripted)
        try {
            // victim listed first: with the inline dispatcher the fan-out's
            // jobs are serialized at launch time, so victim fails terminal
            // BEFORE survivor's job parks mid-send on the latch below.
            runBlocking { harness.session.connectAll(listOf("victim", "survivor")) }

            // Drive the fan-out on a worker thread so the main thread can observe mid-send.
            val outcome = CompletableFuture<Boolean>()
            Thread({ outcome.complete(runBlocking { harness.session.sendToAll(TOTAL_BYTES) }) }, "fanout-driver")
                .start()

            assertTrue("survivor never reached mid-send", survivorMidSend.await(5, TimeUnit.SECONDS))

            val midBurst = harness.session.peerStates.value
            assertEquals(PeerLinkStateKind.Sending, midBurst.getValue("survivor").kind)
            assertEquals(TOTAL_BYTES / 2, midBurst.getValue("survivor").bytesSent)
            assertNull(midBurst.getValue("survivor").error)
            assertEquals(PeerLinkStateKind.Failed, midBurst.getValue("victim").kind)

            releaseSurvivor.countDown()
            assertFalse(outcome.get(5, TimeUnit.SECONDS)) // not ALL online peers succeeded
            assertEquals(PeerLinkStateKind.Done, harness.session.peerStates.value.getValue("survivor").kind)
            assertEquals(PeerLinkStateKind.Failed, harness.session.peerStates.value.getValue("victim").kind)
        } finally {
            releaseSurvivor.countDown() // safety net against wedging the worker
            harness.shutdown()
        }
    }

    @Test
    fun `retry-then-success - only failing peer retries, delays requested only for its gaps`() {
        val connector = FakeConnector()
        var flakyAttempts = 0
        val scripted = object : GroupPeerConnector by connector {
            override suspend fun connect(endpointId: String): Boolean {
                if (endpointId != "flaky") return true
                flakyAttempts++
                return flakyAttempts > 2 // fails twice, succeeds on attempt 3
            }
        }
        val requestedDelays = mutableListOf<Int>()
        val harness = TestHarness(scripted, maxConnectAttempts = 3, retryDelayMs = { attempt ->
            requestedDelays += attempt
            0L
        })
        try {
            runBlocking {
                harness.session.connectAll(listOf("stable", "flaky"))

                val states = harness.session.peerStates.value
                assertEquals(PeerLinkStateKind.Online, states.getValue("flaky").kind)
                assertEquals(3, states.getValue("flaky").attempt)
                assertEquals(PeerLinkStateKind.Online, states.getValue("stable").kind)
                assertEquals(1, states.getValue("stable").attempt) // never retried
                assertEquals(listOf(1, 2), requestedDelays) // backoff ONLY between flaky's gaps
                assertEquals(3, flakyAttempts)
            }
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun `retry exhaustion is a terminal Failed with full attempt history`() {
        val scripted = object : GroupPeerConnector {
            override suspend fun connect(endpointId: String): Boolean = false
            override fun send(endpointId: String, payloadSizeBytes: Long, onProgress: (Long) -> Unit): Boolean =
                error("unreachable")
            override suspend fun disconnect(endpointId: String) = Unit
        }
        val harness = TestHarness(scripted, maxConnectAttempts = 3)
        try {
            runBlocking {
                harness.session.connectAll(listOf("dead"))

                val state = harness.session.peerStates.value.getValue("dead")
                assertEquals(PeerLinkStateKind.Failed, state.kind)
                assertEquals(3, state.attempt)
                assertTrue(state.error!!.contains("3 attempts"))
            }
        } finally {
            harness.shutdown()
        }
    }

    @Test
    fun `connectAll while a previous one is running is an idempotent no-op`() {
        val connector = FakeConnector()
        val slowPeerConnecting = CountDownLatch(1)
        val releaseSlowPeer = CountDownLatch(1)
        val scripted = object : GroupPeerConnector by connector {
            override suspend fun connect(endpointId: String): Boolean {
                if (endpointId == "slow") {
                    slowPeerConnecting.countDown()
                    releaseSlowPeer.await(5, TimeUnit.SECONDS)
                }
                return true
            }
        }
        val harness = TestHarness(scripted)
        try {
            val first = CompletableFuture<Unit>()
            Thread({ first.complete(runBlocking { harness.session.connectAll(listOf("slow", "quick")) }) }, "connector-driver")
                .start()
            assertTrue(slowPeerConnecting.await(5, TimeUnit.SECONDS))

            runBlocking {
                val duringFirstRun = harness.session.peerStates.value
                assertEquals(setOf("slow", "quick"), duringFirstRun.keys)
                harness.session.connectAll(listOf("intruder")) // must be a silent no-op
                assertEquals(duringFirstRun, harness.session.peerStates.value)
                assertFalse(harness.session.peerStates.value.containsKey("intruder"))
            }

            releaseSlowPeer.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertFalse(harness.session.peerStates.value.containsKey("intruder"))
            assertEquals(PeerLinkStateKind.Online, harness.session.peerStates.value.getValue("slow").kind)
        } finally {
            releaseSlowPeer.countDown()
            harness.shutdown()
        }
    }

    @Test
    fun `disconnectAll during send cancels in-flight sends, disconnects peers, resets state`() {
        val connector = FakeConnector()
        val survivorMidSend = CountDownLatch(1)
        val releaseSurvivor = CountDownLatch(1)
        val scripted = object : GroupPeerConnector by connector {
            override fun send(endpointId: String, payloadSizeBytes: Long, onProgress: (Long) -> Unit): Boolean {
                if (endpointId == "survivor") {
                    onProgress(payloadSizeBytes / 2)
                    survivorMidSend.countDown()
                    releaseSurvivor.await(5, TimeUnit.SECONDS)
                    return true
                }
                return true
            }
        }
        val harness = TestHarness(scripted)
        try {
            runBlocking { harness.session.connectAll(listOf("survivor")) }

            val outcome = CompletableFuture<Boolean>()
            Thread({ outcome.complete(runBlocking { harness.session.sendToAll(TOTAL_BYTES) }) }, "fanout-driver")
                .start()
            assertTrue(survivorMidSend.await(5, TimeUnit.SECONDS))
            assertEquals(PeerLinkStateKind.Sending, harness.session.peerStates.value.getValue("survivor").kind)

            runBlocking { harness.session.disconnectAll() }

            // Unblock the blocked connector; the cancelled job must NOT write
            // Done over the cleared map (no resurrection race).
            releaseSurvivor.countDown()
            outcome.get(5, TimeUnit.SECONDS)

            assertEquals(emptyMap<String, PeerLinkState>(), harness.session.peerStates.value)
            assertTrue(connector.disconnects.contains("survivor"))
        } finally {
            releaseSurvivor.countDown()
            harness.shutdown()
        }
    }

    private companion object {
        const val TOTAL_BYTES: Long = 10_000L
    }
}
