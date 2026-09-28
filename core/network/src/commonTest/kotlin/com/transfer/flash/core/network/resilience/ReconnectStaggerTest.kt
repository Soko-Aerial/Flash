package com.transfer.flash.core.network.resilience

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PC2 staggered storms: only a mass drop delays reconnects, and only by a bounded, stable offset. */
class ReconnectStaggerTest {

    @Test
    fun `a single drop is never delayed`() {
        val s = ReconnectStagger("local")
        s.onUnexpectedDrop(0)
        assertEquals(0L, s.firstAttemptDelayMs("peer", 0))
    }

    @Test
    fun `three drops are not a storm, four within the window are`() {
        val s = ReconnectStagger("local")
        repeat(3) { s.onUnexpectedDrop(it * 100L) }
        assertFalse(s.inStorm(300))
        s.onUnexpectedDrop(400)
        assertTrue(s.inStorm(400))
    }

    @Test
    fun `drops spread wider than the window are not a storm`() {
        val s = ReconnectStagger("local")
        repeat(10) { s.onUnexpectedDrop(it * 1_500L) }
        assertFalse(s.inStorm(13_500))
    }

    @Test
    fun `the storm holds for the rejoin redial and then ends`() {
        val s = ReconnectStagger("local", holdMs = 30_000)
        repeat(4) { s.onUnexpectedDrop(0) }
        assertTrue(s.inStorm(30_000))
        assertFalse(s.inStorm(30_001))
        assertEquals(0L, s.firstAttemptDelayMs("peer", 30_001))
    }

    @Test
    fun `offsets are stable, inside the spread, and differ across pairs`() {
        val s = ReconnectStagger("device-local", spreadMs = 2_000)
        val offsets = (1..40).map { s.offsetMs("peer-$it") }
        assertTrue(offsets.all { it in 0 until 2_000 })
        assertEquals(offsets, (1..40).map { s.offsetMs("peer-$it") })
        assertTrue(offsets.toSet().size > 20, "40 pairs should not bunch onto a few offsets: $offsets")
        repeat(4) { s.onUnexpectedDrop(0) }
        assertEquals(s.offsetMs("peer-1"), s.firstAttemptDelayMs("peer-1", 0))
    }
}
