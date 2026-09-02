package com.transfer.flash.core.network.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keepalive verdict is a pure function of (clock, inbound traffic, tick arrivals), so the
 * Android behaviour that caused ERROR-025 — a process frozen for minutes by screen-off / Doze —
 * is reproducible here by simply handing [WsKeepalive.onTick] a clock reading that jumped.
 */
class WsKeepaliveTest {

    private val pingIntervalMs = WsConnection.DEFAULT_PING_INTERVAL_MS
    private val livenessTimeoutMs = WsConnection.DEFAULT_LIVENESS_TIMEOUT_MS

    private fun keepalive(startedAtMs: Long = 1_000L) =
        WsKeepalive(pingIntervalMs, livenessTimeoutMs, startedAtMs)

    @Test
    fun `on-time tick within the liveness window pings`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(11_000L) is WsKeepalive.Verdict.Ping)
    }

    @Test
    fun `silence across on-time ticks closes the connection`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(11_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(21_000L) is WsKeepalive.Verdict.Ping)

        val verdict = keepalive.onTick(31_000L)

        assertTrue("30s of measured silence must close", verdict is WsKeepalive.Verdict.Close)
        assertEquals(30_000L, (verdict as WsKeepalive.Verdict.Close).silentForMs)
    }

    @Test
    fun `inbound traffic keeps a busy connection open indefinitely`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        var now = 1_000L
        repeat(20) {
            now += pingIntervalMs
            keepalive.onInbound(now - 500L) // a pong arrived just before the tick
            assertTrue(keepalive.onTick(now) is WsKeepalive.Verdict.Ping)
        }
    }

    @Test
    fun `silence exactly at the liveness timeout is not yet fatal`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(11_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(21_000L) is WsKeepalive.Verdict.Ping)

        // silentForMs == livenessTimeoutMs exactly: the rule is strictly greater-than.
        assertTrue(keepalive.onTick(1_000L + livenessTimeoutMs) is WsKeepalive.Verdict.Ping)
    }

    @Test
    fun `a tick that slipped past the stall factor is forgiven and rebased`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        // Exactly at the threshold: the scheduler is already too far off to trust.
        val stalledGap = pingIntervalMs * WsKeepalive.STALL_FACTOR
        assertTrue(keepalive.onTick(1_000L + stalledGap) is WsKeepalive.Verdict.Ping)

        // Proof the window was rebased to the stalled tick rather than left at start:
        // without the rebase this tick would measure 30s of "silence" and close.
        assertTrue(keepalive.onTick(1_000L + stalledGap + pingIntervalMs) is WsKeepalive.Verdict.Ping)
    }

    @Test
    fun `three minute screen-off freeze does not close a healthy session`() {
        // The Infinix case: both peers' ping loops are frozen simultaneously, so both would
        // otherwise close on their first resumed tick and each would render the other offline.
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(11_000L) is WsKeepalive.Verdict.Ping)
        keepalive.onInbound(11_050L) // peer's pong

        // Screen off. delay(10_000) returns three minutes late.
        val resumedAt = 191_000L
        assertTrue(
            "a frozen process must not condemn its peer",
            keepalive.onTick(resumedAt) is WsKeepalive.Verdict.Ping,
        )

        // The PING sent on that tick is answered, and the session carries on.
        keepalive.onInbound(resumedAt + 120L)
        assertTrue(keepalive.onTick(resumedAt + pingIntervalMs) is WsKeepalive.Verdict.Ping)
    }

    @Test
    fun `a genuinely dead link still closes shortly after the process wakes`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        val resumedAt = 191_000L
        assertTrue(keepalive.onTick(resumedAt) is WsKeepalive.Verdict.Ping)

        // No pong ever comes back. On-time ticks now measure real silence.
        assertTrue(keepalive.onTick(resumedAt + 10_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(resumedAt + 20_000L) is WsKeepalive.Verdict.Ping)
        val verdict = keepalive.onTick(resumedAt + 30_000L)

        assertTrue(verdict is WsKeepalive.Verdict.Close)
        assertEquals(30_000L, (verdict as WsKeepalive.Verdict.Close).silentForMs)
    }

    @Test
    fun `a clock that jumped backwards is forgiven`() {
        val keepalive = keepalive(startedAtMs = 100_000L)

        // NTP correction / manual time change between two ticks.
        assertTrue(keepalive.onTick(40_000L) is WsKeepalive.Verdict.Ping)

        // Rebased to the new reading, so measurement resumes from there.
        assertTrue(keepalive.onTick(50_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(60_000L) is WsKeepalive.Verdict.Ping)

        // A second stalled gap inside the SAME silence episode is not forgiven again (ERROR-031):
        // 40s of silence, half of it measured by ticks that ran on time, is a verdict.
        val verdict = keepalive.onTick(80_000L)
        assertTrue(verdict is WsKeepalive.Verdict.Close)
        assertTrue((verdict as WsKeepalive.Verdict.Close).needsConfirmation)
    }

    @Test
    fun `inbound timestamp from the future does not close the connection`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        // The read loop stamped an inbound frame with a clock reading ahead of the tick's.
        keepalive.onInbound(60_000L)

        assertTrue(keepalive.onTick(11_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(21_000L) is WsKeepalive.Verdict.Ping)
    }

    // ---------------------------------------------------------------------------------------
    // ERROR-031: forgiveness is per stall episode, not per tick.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `chronically late ticks cannot keep a dead session alive forever`() {
        // The zombie case: this coroutine is throttled so hard that EVERY tick looks stalled, and
        // the peer never answers. Unbounded forgiveness rebased the window on each tick, so the
        // watchdog never rendered a verdict and the session stayed in activeSessions forever.
        val keepalive = keepalive(startedAtMs = 1_000L)

        var now = 1_000L
        var closed: WsKeepalive.Verdict.Close? = null
        repeat(10) {
            now += 3 * 60_000L // three minutes between ticks, every time
            val verdict = keepalive.onTick(now)
            if (verdict is WsKeepalive.Verdict.Close && closed == null) closed = verdict
        }

        assertNotNull("a chronically late loop must still render a verdict", closed)
        assertEquals(WsKeepalive.REASON_STALL_PROBE, closed!!.reason)
        assertTrue("a stalled tick's verdict must be confirmable", closed!!.needsConfirmation)
    }

    @Test
    fun `the second stalled tick of an episode renders the verdict`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        // First stall: forgiven, probe armed.
        assertTrue(keepalive.onTick(181_000L) is WsKeepalive.Verdict.Ping)
        // Second stall with nothing inbound in between: the window is no longer rebased.
        val verdict = keepalive.onTick(361_000L)

        assertTrue(verdict is WsKeepalive.Verdict.Close)
        assertEquals(180_000L, (verdict as WsKeepalive.Verdict.Close).silentForMs)
    }

    @Test
    fun `a stalled verdict is withdrawn when the read loop stamps a frame late`() {
        // Both coroutines resume from the same freeze. If the keepalive tick is dispatched first,
        // the PONG is in the socket buffer but not yet stamped — closing there would resurrect
        // ERROR-025. The caller stays awake for a moment and asks again.
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(181_000L) is WsKeepalive.Verdict.Ping)
        val verdict = keepalive.onTick(361_000L)
        assertTrue((verdict as WsKeepalive.Verdict.Close).needsConfirmation)

        // The read loop got scheduled and stamped the queued PONG.
        keepalive.onInbound(361_400L)

        assertTrue(keepalive.confirmClose(363_000L) is WsKeepalive.Verdict.Ping)
        // The episode ended, so the next stall is entitled to its own forgiveness again.
        assertTrue(keepalive.onTick(543_000L) is WsKeepalive.Verdict.Ping)
    }

    @Test
    fun `a stalled verdict stands when nothing arrives during the awake window`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(181_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(361_000L) is WsKeepalive.Verdict.Close)

        val confirmed = keepalive.confirmClose(363_000L)

        assertTrue(confirmed is WsKeepalive.Verdict.Close)
        assertEquals(WsKeepalive.REASON_STALL_PROBE, (confirmed as WsKeepalive.Verdict.Close).reason)
    }

    @Test
    fun `a peer that answers every probe is forgiven every time`() {
        // A phone that is repeatedly frozen but whose link is fine must never be closed.
        val keepalive = keepalive(startedAtMs = 1_000L)

        var now = 1_000L
        repeat(20) {
            now += 3 * 60_000L
            assertTrue(
                "a live peer must survive freeze #$it",
                keepalive.onTick(now) is WsKeepalive.Verdict.Ping,
            )
            keepalive.onInbound(now + 150L) // the PONG comes back
            now += pingIntervalMs
            assertTrue(keepalive.onTick(now) is WsKeepalive.Verdict.Ping)
        }
    }

    @Test
    fun `the two reap causes are distinguishable in logs`() {
        val keepalive = keepalive(startedAtMs = 1_000L)

        assertTrue(keepalive.onTick(11_000L) is WsKeepalive.Verdict.Ping)
        assertTrue(keepalive.onTick(21_000L) is WsKeepalive.Verdict.Ping)
        val onTime = keepalive.onTick(31_000L)

        assertEquals(WsKeepalive.REASON_SILENT, (onTime as WsKeepalive.Verdict.Close).reason)
        assertTrue("a verdict from on-time ticks needs no second look", !onTime.needsConfirmation)
    }
}
