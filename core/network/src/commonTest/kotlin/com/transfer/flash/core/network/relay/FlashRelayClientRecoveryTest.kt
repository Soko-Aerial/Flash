@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-07 / CLI-08 / CLI-13: reconnect discipline, recoverable REVOKED, restart on address change, reliable deliveries. */
class FlashRelayClientRecoveryTest {

    private class Rig(scope: TestScope, jitter: (Long) -> Long = { it }) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val transport = ScriptedRelayTransport { scope.currentTime }
        var serial: Long = 1L
        var enrolled: Boolean = true
        val client = FlashRelayClient(
            deviceId = "dev-1",
            friendlyName = "Phone",
            hubUrl = "wss://hub-a.example.test/flash-relay",
            identityProvider = { if (enrolled) testIdentity(serial) else null },
            transport = transport,
            timeSource = object : FlashTimeSource { override fun nowMs(): Long = scope.currentTime },
            ioDispatcher = dispatcher,
            scope = CoroutineScope(dispatcher),
            jitter = jitter,
        )
    }

    @Test
    fun aHubThatAcceptsAndImmediatelyDropsIsNotRetriedAtTheMinimumInterval() = runTest {
        val rig = Rig(this)
        rig.client.start()
        // Each session lasts 1 s: far below the healthy threshold, so the backoff must keep growing.
        repeat(4) {
            runCurrent()
            advanceTimeBy(1_000L)
            rig.transport.dropSession()
            runCurrent()
            advanceTimeBy(70_000L.coerceAtMost(1_000L * (1L shl it) + 1))
        }
        val gaps = rig.transport.connectTimes.zipWithNext { a, b -> b - a }
        assertTrue(gaps.size >= 3, "expected several reconnects, got ${rig.transport.connectTimes}")
        assertTrue(gaps[1] > gaps[0] && gaps[2] > gaps[1], "gaps must grow while sessions are short: $gaps")
        rig.client.stop()
    }

    @Test
    fun aConnectionThatStaysUpLongEnoughResetsTheBackoff() = runTest {
        val rig = Rig(this)
        rig.client.start()
        runCurrent()
        // First: two failures to grow the backoff.
        rig.transport.dropSession(); runCurrent(); advanceTimeBy(1_100L)
        rig.transport.dropSession(); runCurrent(); advanceTimeBy(2_200L)
        // Now a session that lasts 31 s.
        advanceTimeBy(FlashRelayClient.HEALTHY_AFTER_MS + 1_000L)
        rig.transport.dropSession(); runCurrent()
        val before = rig.transport.connectTimes.size
        advanceTimeBy(FlashRelayClient.INITIAL_BACKOFF_MS + 50L)
        runCurrent()
        assertEquals(before + 1, rig.transport.connectTimes.size, "healthy session: next attempt after the initial backoff")
        rig.client.stop()
    }

    @Test
    fun failedAttemptsBackOffExponentiallyWithinTheJitterBounds() = runTest {
        // jitter = 0 gives the lower bound of the equal-jitter window: base / 2.
        val rig = Rig(this, jitter = { 0L })
        rig.transport.script = { ConnectScript.Fail(RelayFailure(RelayFailureKind.NETWORK)) }
        rig.client.start()
        runCurrent()
        advanceTimeBy(10_000L)
        val gaps = rig.transport.connectTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(500L, 1_000L, 2_000L), gaps.take(3))
        assertNotNull(rig.client.nextRetryAtMs.value)
        rig.client.stop()
    }

    @Test
    fun jitterNeverExceedsTheBase() = runTest {
        val rig = Rig(this, jitter = { max -> max })
        rig.transport.script = { ConnectScript.Fail(RelayFailure(RelayFailureKind.NETWORK)) }
        rig.client.start()
        runCurrent()
        advanceTimeBy(10_000L)
        val gaps = rig.transport.connectTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_000L, 2_000L, 4_000L), gaps.take(3))
        rig.client.stop()
    }

    @Test
    fun changingTheHubAddressDropsTheSessionAndConnectsToTheNewHub() = runTest {
        val rig = Rig(this)
        rig.client.start()
        runCurrent()
        assertEquals(listOf("wss://hub-a.example.test/flash-relay"), rig.transport.connectUrls)
        rig.client.hubUrl = "wss://hub-b.example.test/flash-relay"
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(rig.transport.disconnectReasons.isNotEmpty(), "the old session must be closed")
        assertEquals("wss://hub-b.example.test/flash-relay", rig.transport.connectUrls.last())
        rig.client.stop()
    }

    @Test
    fun revokedStopsRetryingUntilAnotherCertificateOrRetryNow() = runTest {
        val rig = Rig(this)
        rig.transport.script = { n ->
            if (n == 1) ConnectScript.Fail(RelayFailure(RelayFailureKind.REVOKED, "ERR_REVOKED")) else ConnectScript.Up
        }
        rig.client.start()
        runCurrent()
        advanceTimeBy(120_000L)
        assertEquals(1, rig.transport.connectTimes.size, "no automatic retry for a revoked certificate")
        assertEquals(RelayFailureKind.REVOKED, rig.client.failure.value?.kind)
        assertNull(rig.client.nextRetryAtMs.value)

        // The same client recovers when the device enrols again (another certificate serial).
        rig.serial = 2L
        advanceTimeBy(FlashRelayClient.IDLE_POLL_MS + 100L)
        runCurrent()
        assertEquals(2, rig.transport.connectTimes.size)
        assertEquals(RelaySessionState.CONNECTED, rig.client.state.value)
        assertNull(rig.client.failure.value, "the old REVOKED failure is cleared by the new certificate")
        rig.client.stop()
    }

    @Test
    fun retryNowAsksTheHubAgainAfterARevokedAnswer() = runTest {
        val rig = Rig(this)
        rig.transport.script = { n ->
            if (n == 1) ConnectScript.Fail(RelayFailure(RelayFailureKind.REVOKED)) else ConnectScript.Up
        }
        rig.client.start()
        runCurrent()
        rig.client.retryNow()
        runCurrent()
        assertEquals(2, rig.transport.connectTimes.size)
        rig.client.stop()
    }

    @Test
    fun startAfterStopWorksAgain() = runTest {
        val rig = Rig(this)
        rig.client.start(); runCurrent()
        assertEquals(RelaySessionState.CONNECTED, rig.client.state.value)
        rig.client.stop(); runCurrent()
        assertEquals(RelaySessionState.DISCONNECTED, rig.client.state.value)
        rig.client.start(); runCurrent()
        assertEquals(RelaySessionState.CONNECTED, rig.client.state.value)
        assertEquals(2, rig.transport.connectTimes.size)
        rig.client.stop()
    }

    @Test
    fun notEnrolledClientIdlesInsteadOfDialling() = runTest {
        val rig = Rig(this)
        rig.enrolled = false
        rig.client.start()
        runCurrent()
        advanceTimeBy(30_000L)
        assertTrue(rig.transport.connectTimes.isEmpty())
        rig.enrolled = true
        advanceTimeBy(FlashRelayClient.IDLE_POLL_MS + 100L)
        runCurrent()
        assertEquals(1, rig.transport.connectTimes.size)
        rig.client.stop()
    }

    @Test
    fun deliveriesThatArriveBeforeAnyoneCollectsAreKept() = runTest {
        val rig = Rig(this)
        rig.client.start()
        runCurrent()
        repeat(3) { rig.transport.deliver(RelayFrame.Deliver(from = "dev-2", id = "m-$it", sentAt = 1L, payload = "p")) }
        val received = mutableListOf<String>()
        val job = launch(rig.dispatcher) { rig.client.incomingDeliveries.collect { received += it.id } }
        runCurrent()
        assertEquals(listOf("m-0", "m-1", "m-2"), received)
        job.cancel()
        rig.client.stop()
    }

    @Test
    fun receiptAcknowledgementIsSentOnlyWhileConnected() = runTest {
        val rig = Rig(this)
        assertFalse(rig.client.acknowledgeReceipt("m-1"))
        rig.client.start()
        runCurrent()
        assertTrue(rig.client.acknowledgeReceipt("m-1"))
        assertEquals(RelayFrame.RecvAck("m-1"), rig.transport.sent.last())
        rig.client.stop()
    }

    @Test
    fun aSilentHubIsDroppedAfterTheKeepaliveTimeout() = runTest {
        val rig = Rig(this)
        rig.client.start()
        runCurrent()
        advanceTimeBy(FlashRelayClient.PONG_TIMEOUT_MS + FlashRelayClient.PING_INTERVAL_MS + 500L)
        runCurrent()
        assertTrue(rig.transport.disconnectReasons.contains("Keepalive timeout"))
        assertEquals(RelayFailureKind.KEEPALIVE_TIMEOUT, rig.client.failure.value?.kind)
        rig.client.stop()
    }

    @Test
    fun serverMismatchBacksOffAtTheMaximumInterval() = runTest {
        val rig = Rig(this, jitter = { 0L })
        rig.transport.script = { ConnectScript.Fail(RelayFailure(RelayFailureKind.SERVER_MISMATCH)) }
        rig.client.start()
        runCurrent()
        advanceTimeBy(29_000L)
        assertEquals(1, rig.transport.connectTimes.size, "a wrong hub is retried slowly")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, rig.transport.connectTimes.size)
        assertTrue(rig.transport.connectTimes[1] - rig.transport.connectTimes[0] >= FlashRelayClient.MAX_BACKOFF_MS / 2)
        assertEquals(RelayFailureKind.SERVER_MISMATCH, rig.client.failure.value?.kind)
        rig.client.stop()
    }
}
