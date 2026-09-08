package com.transfer.flash.core.network.resilience

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moved to `commonTest` in Phase 15-1. Every clock reading is passed in by the test, so the suite
 * never touched a platform clock and the only edit is the JUnit 4 → `kotlin.test` import swap. The
 * one `assertTrue` here takes no message, so no argument order moved.
 */
class HeartbeatTrackerTest {

    private val interval = 10_000L
    private val threshold = 3

    private fun tracker() = HeartbeatTracker(HeartbeatPolicy(interval, threshold))

    @Test
    fun `idle within interval awaits`() {
        val t = tracker()
        t.onPongReceived(0)
        assertEquals(HeartbeatAction.AwaitPong, t.onTick(5_000))
        assertEquals(HeartbeatState.Alive, t.state)
    }

    @Test
    fun `first interval boundary requests ping`() {
        val t = tracker()
        t.onPongReceived(0)
        // Exactly-at-interval is due (>= comparison).
        assertEquals(HeartbeatAction.PingNow, t.onTick(10_000))
        assertEquals(HeartbeatState.Alive, t.state)

        t.onPingSent(10_000)
        assertEquals(HeartbeatAction.AwaitPong, t.onTick(19_999))
    }

    @Test
    fun `exactly-at-threshold miss declares dead`() {
        val t = tracker()
        t.onPongReceived(0)

        // Miss 1: ping at 10_000 times out at 20_000.
        assertEquals(HeartbeatAction.PingNow, t.onTick(10_000))
        t.onPingSent(10_000)
        assertEquals(HeartbeatAction.PingNow, t.onTick(20_000)) // miss 1 → Suspect + re-probe
        assertEquals(HeartbeatState.Suspect, t.state)

        t.onPingSent(20_000)
        assertEquals(HeartbeatAction.PingNow, t.onTick(30_000)) // miss 2 → still Suspect
        assertEquals(HeartbeatState.Suspect, t.state)

        t.onPingSent(30_000)
        assertEquals(HeartbeatAction.DeclareDead, t.onTick(40_000)) // miss 3 = threshold
        assertEquals(HeartbeatState.Dead, t.state)
    }

    @Test
    fun `one millisecond before timeout does not count a miss`() {
        val t = tracker()
        t.onPongReceived(0)
        assertEquals(HeartbeatAction.PingNow, t.onTick(10_000))
        t.onPingSent(10_000)
        assertEquals(HeartbeatAction.AwaitPong, t.onTick(19_999))
        assertEquals(HeartbeatState.Alive, t.state)
        assertEquals(0, t.missedCountValue)
    }

    @Test
    fun `pong resets misses and state`() {
        val t = tracker()
        t.onPongReceived(0)
        repeat(2) { i ->
            val base = i * 20_000L
            assertEquals(HeartbeatAction.PingNow, t.onTick(base + 10_000))
            t.onPingSent(base + 10_000)
            t.onTick(base + 20_000) // one miss
        }
        assertEquals(HeartbeatState.Suspect, t.state)
        assertEquals(2, t.missedCountValue)

        // Pong arrives just before the third miss would land.
        t.onPingSent(40_000)
        t.onPongReceived(45_000)
        assertEquals(HeartbeatState.Alive, t.state)
        assertEquals(0, t.missedCountValue)
        assertEquals(HeartbeatAction.AwaitPong, t.onTick(46_000))
    }

    @Test
    fun `dead stays dead on further ticks and ignores pings`() {
        val t = tracker()
        t.onPongReceived(0)
        repeat(threshold) { i ->
            val base = i * 20_000L
            t.onTick(base + 10_000)
            t.onPingSent(base + 10_000)
            t.onTick(base + 20_000)
        }
        assertEquals(HeartbeatState.Dead, t.state)
        assertEquals(HeartbeatAction.DeclareDead, t.onTick(100_000))
        t.onPingSent(100_000) // ignored
        t.onPongReceived(101_000) // too late — a Dead peer stays Dead
        assertEquals(HeartbeatState.Dead, t.state)
        assertEquals(HeartbeatAction.DeclareDead, t.onTick(102_000))
    }

    @Test
    fun `reset returns to clean alive slate`() {
        val t = tracker()
        t.onPongReceived(0)
        t.onTick(10_000); t.onPingSent(10_000); t.onTick(20_000)
        assertTrue(t.missedCountValue > 0 || t.state == HeartbeatState.Suspect)
        t.reset(nowMs = 500)
        assertEquals(HeartbeatState.Alive, t.state)
        assertEquals(0, t.missedCountValue)
        assertEquals(HeartbeatAction.AwaitPong, t.onTick(6_000))
        assertEquals(HeartbeatAction.PingNow, t.onTick(10_500))
    }

    @Test
    fun `custom policy thresholds respected`() {
        val t = HeartbeatTracker(HeartbeatPolicy(intervalMs = 1_000, missedThreshold = 1))
        t.onPongReceived(0)
        t.onPingSent(1_000)
        assertEquals(HeartbeatAction.DeclareDead, t.onTick(2_000))
        assertEquals(HeartbeatState.Dead, t.state)
    }

    @Test
    fun `policy defaults are research-informed values`() {
        val p = HeartbeatPolicy()
        assertEquals(10_000L, p.intervalMs)
        assertEquals(3, p.missedThreshold)
    }
}
