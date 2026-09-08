@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.common.net

import com.transfer.flash.core.common.annotation.FlashInternalApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the roam-hint contract (ERROR-033). Every case here is a shape the platform actually
 * produces on a mesh SSID, and each of them was a way to get this wrong:
 *
 * - the very first report is an initial association, not a roam;
 * - `onCapabilitiesChanged` re-fires with identical content constantly;
 * - a marginal link re-reports *changed* content constantly, which is why the rate limit exists;
 * - a new network must not inherit the previous one's fingerprint.
 */
class LinkChangeTrackerTest {

    private class FakeClock(var nowMs: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun tracker(clock: FakeClock, minIntervalMs: Long = LinkChangeTracker.DEFAULT_MIN_INTERVAL_MS) =
        LinkChangeTracker(nowMs = clock, minIntervalMs = minIntervalMs)

    @Test
    fun `the first fingerprint is an association and never a change`() {
        val clock = FakeClock()
        val tracker = tracker(clock)

        // Registering a callback immediately replays the current capabilities and link properties.
        // Treating that as a roam would fire a probe round on every single start().
        assertFalse(tracker.onFingerprint("ap-1"))
    }

    @Test
    fun `an unchanged fingerprint is not a change however often it repeats`() {
        val clock = FakeClock()
        val tracker = tracker(clock)
        tracker.onFingerprint("ap-1")

        repeat(20) {
            clock.advance(10_000L)
            assertFalse(tracker.onFingerprint("ap-1"))
        }
    }

    @Test
    fun `a different fingerprint is a change`() {
        val clock = FakeClock()
        val tracker = tracker(clock)
        tracker.onFingerprint("ap-1")

        assertTrue(tracker.onFingerprint("ap-2"))
    }

    @Test
    fun `the first change is not itself rate limited`() {
        val clock = FakeClock()
        val tracker = tracker(clock)
        tracker.onFingerprint("ap-1")

        // No verdict has been rendered yet, so there is no interval to wait out. A roam that happens
        // one millisecond after start() still deserves an answer.
        clock.advance(1L)
        assertTrue(tracker.onFingerprint("ap-2"))
    }

    @Test
    fun `a second change inside the interval is suppressed`() {
        val clock = FakeClock()
        val tracker = tracker(clock, minIntervalMs = 5_000L)
        tracker.onFingerprint("ap-1")
        assertTrue(tracker.onFingerprint("ap-2"))

        clock.advance(4_999L)
        assertFalse(tracker.onFingerprint("ap-3"))
    }

    @Test
    fun `a change once the interval has elapsed fires again`() {
        val clock = FakeClock()
        val tracker = tracker(clock, minIntervalMs = 5_000L)
        tracker.onFingerprint("ap-1")
        assertTrue(tracker.onFingerprint("ap-2"))

        clock.advance(5_000L)
        assertTrue(tracker.onFingerprint("ap-3"))
    }

    @Test
    fun `a suppressed change still updates the baseline`() {
        val clock = FakeClock()
        val tracker = tracker(clock, minIntervalMs = 5_000L)
        tracker.onFingerprint("ap-1")
        assertTrue(tracker.onFingerprint("ap-2"))

        clock.advance(1_000L)
        assertFalse(tracker.onFingerprint("ap-3")) // suppressed, but ap-3 is now what we know

        // Re-reporting the fingerprint we are already on is not a change even after the rate limit
        // expires. If suppression had left the baseline at ap-2, this would fire a phantom roam
        // several seconds after the radio had already settled.
        clock.advance(10_000L)
        assertFalse(tracker.onFingerprint("ap-3"))
    }

    @Test
    fun `a flap back to the previous fingerprint is a change`() {
        val clock = FakeClock()
        val tracker = tracker(clock, minIntervalMs = 5_000L)
        tracker.onFingerprint("ap-1")
        assertTrue(tracker.onFingerprint("ap-2"))

        // Roaming back is just as much a reassociation as roaming away; the tracker compares against
        // the last fingerprint, not against a history.
        clock.advance(6_000L)
        assertTrue(tracker.onFingerprint("ap-1"))
    }

    @Test
    fun `reset makes the next fingerprint an association again`() {
        val clock = FakeClock()
        val tracker = tracker(clock, minIntervalMs = 5_000L)
        tracker.onFingerprint("ap-1")
        assertTrue(tracker.onFingerprint("ap-2"))

        // onAvailable/onLost: a genuinely new network. Its first report must not be diffed against
        // the old network's, and its first real roam must not be rate limited by the old one's clock.
        tracker.reset()
        clock.advance(1L)
        assertFalse(tracker.onFingerprint("other-net-1"))
        assertTrue(tracker.onFingerprint("other-net-2"))
    }

    @Test
    fun `the default interval is long enough to absorb a burst of platform reports`() {
        val clock = FakeClock()
        val tracker = tracker(clock)
        tracker.onFingerprint("start")
        assertTrue(tracker.onFingerprint("roam"))

        // A reassociation produces several capabilities/link-properties updates in quick succession
        // (bandwidth estimate settling, IPv6 addresses being re-acquired). At most one probe round
        // may come out of that burst, because each round costs a PING to every peer.
        var fired = 0
        repeat(10) { index ->
            clock.advance(200L)
            if (tracker.onFingerprint("roam-settling-$index")) fired++
        }
        assertTrue("burst produced $fired extra probe rounds", fired == 0)
    }
}
