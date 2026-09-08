package com.transfer.flash.core.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OutboxDrainSchedule] — the wait the durable-outbox drain loop takes between passes.
 *
 * The loop itself is `while (true)` inside a coroutine launched from a constructor, so no test can
 * step it; these assertions are the only place its timing is held to account. What they are really
 * protecting is the pair of properties that replaced the old fixed 1 s poll: the loop must never
 * sleep past a deadline it already knows about, and it must never come back so fast that it becomes
 * a hot loop against an encrypted database.
 */
class OutboxDrainScheduleTest {

    @Test
    fun `nothing pending waits the whole idle interval`() {
        // No deadline means no known work. The only thing left to do is double-check a table that
        // OutboxDao.observeCount() has already promised to report writes to, so this is a safety
        // net, not the mechanism.
        assertEquals(
            OutboxDrainSchedule.IDLE_WAIT_MS,
            OutboxDrainSchedule.waitMs(nextDueAt = null, now = 1_000_000L),
        )
    }

    @Test
    fun `a future deadline is waited out exactly`() {
        // The point of the change: sleep until the ladder backoffDelayMs computed, not until the
        // next tick of an unrelated grid. 1 s and 8 s are two real rungs of that ladder.
        val now = 1_000_000L
        assertEquals(1_000L, OutboxDrainSchedule.waitMs(nextDueAt = now + 1_000L, now = now))
        assertEquals(8_000L, OutboxDrainSchedule.waitMs(nextDueAt = now + 8_000L, now = now))
    }

    @Test
    fun `a deadline that has already passed still yields a positive wait`() {
        // Reachable whenever a pass takes longer than the gap to its own next deadline — a slow
        // socket write on a low-end handset is enough. A zero or negative wait here would spin the
        // loop against SQLCipher as fast as the CPU allows.
        val now = 1_000_000L
        assertEquals(OutboxDrainSchedule.MIN_WAIT_MS, OutboxDrainSchedule.waitMs(now - 1L, now))
        assertEquals(OutboxDrainSchedule.MIN_WAIT_MS, OutboxDrainSchedule.waitMs(now - 60_000L, now))
        assertEquals(OutboxDrainSchedule.MIN_WAIT_MS, OutboxDrainSchedule.waitMs(now, now))
    }

    @Test
    fun `a deadline further out than the idle interval is capped at it`() {
        // The backoff ladder caps at OUTBOX_MAX_BACKOFF_MS, which is exactly IDLE_WAIT_MS, so this
        // only bites if a row is somehow scheduled further out than the ladder allows. Waking early
        // and finding nothing due is cheap; sleeping through a deadline is not.
        val now = 1_000_000L
        assertEquals(
            OutboxDrainSchedule.IDLE_WAIT_MS,
            OutboxDrainSchedule.waitMs(nextDueAt = now + 10 * 60 * 1000L, now = now),
        )
    }

    @Test
    fun `the wait is always inside the bounds for any deadline`() {
        // Sweep both sides of `now`, including the extremes, because the inputs are wall-clock
        // values from two different reads of System.currentTimeMillis() and nothing constrains
        // their order. The two saturated values overflow the subtraction — deliberately included:
        // real deadlines are always `now + backoff` so they are unreachable, and the point is that
        // even then the result is a legal wait rather than a negative one that would spin the loop.
        val now = 1_000_000L
        val deadlines = listOf(
            Long.MIN_VALUE, now - 1_000_000L, now - 1L, now, now + 1L,
            now + 25L, now + 59_999L, now + 60_001L, Long.MAX_VALUE,
        )
        for (deadline in deadlines) {
            val wait = OutboxDrainSchedule.waitMs(deadline, now)
            assertTrue(
                "wait for deadline $deadline was $wait, outside the bounds",
                wait >= OutboxDrainSchedule.MIN_WAIT_MS && wait <= OutboxDrainSchedule.IDLE_WAIT_MS,
            )
        }
    }

    @Test
    fun `the floor is small enough to be invisible and the ceiling matches the backoff cap`() {
        // Guards the two constants against a well-meaning "round number" edit. The floor has to stay
        // far below the 1 s first rung or a retry would be measurably late; the ceiling is pinned to
        // the backoff cap so a maximally-backed-off row is never woken late by the safety net.
        assertTrue(OutboxDrainSchedule.MIN_WAIT_MS in 1L..100L)
        assertEquals(OUTBOX_MAX_BACKOFF_MS, OutboxDrainSchedule.IDLE_WAIT_MS)
    }

    private companion object {
        /** Mirrors `RealFlashChatRepository.OUTBOX_MAX_BACKOFF_MS` (private to the repository). */
        const val OUTBOX_MAX_BACKOFF_MS = 60_000L
    }
}
