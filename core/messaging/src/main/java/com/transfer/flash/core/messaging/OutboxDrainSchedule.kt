package com.transfer.flash.core.messaging

/**
 * How long the durable-outbox drain loop should wait before its next pass.
 *
 * Split out of [RealFlashChatRepository] as a pure function for the same reason
 * `FlashMediaDecoder.cacheTrimFor` is one: the loop it drives runs forever inside a coroutine that
 * no test can step, so the only way to hold its timing to account is to assert the arithmetic
 * separately.
 *
 * The drain used to wake on a fixed 1 s grid — `while (true) { drain(); delay(1000) }` — which cost
 * a query against an encrypted database every second for the life of the process whether or not a
 * single row was pending, and *still* left every retry up to a second late, because the grid had
 * nothing to do with the backoff ladder `backoffDelayMs` had already computed. Waking on the ladder
 * instead is both cheaper and tighter.
 */
internal object OutboxDrainSchedule {

    /**
     * Floor on any wait.
     *
     * Two jobs: it stops a full batch (more rows already due) from becoming a hot loop, and it keeps
     * a deadline that has just passed — or passed while the previous pass was running — from
     * producing a zero-length wait.
     */
    internal const val MIN_WAIT_MS: Long = 25L

    /**
     * Ceiling on any wait, and the whole wait when nothing is known to be pending.
     *
     * This is a safety net rather than the mechanism. Work can only *appear* by a write to the
     * `outbox` table, and `OutboxDao.observeCount()` is a Room `Flow`, so any such write invalidates
     * it and wakes the loop immediately. A minute is therefore the interval at which the loop
     * double-checks a table it has every reason to believe is empty.
     */
    internal const val IDLE_WAIT_MS: Long = 60_000L

    /**
     * @param nextDueAt earliest retry deadline this process has scheduled, or null when nothing is
     *   known to be pending.
     * @param now current wall clock, the same source the deadlines were computed from.
     */
    internal fun waitMs(nextDueAt: Long?, now: Long): Long =
        if (nextDueAt == null) IDLE_WAIT_MS else (nextDueAt - now).coerceIn(MIN_WAIT_MS, IDLE_WAIT_MS)
}
