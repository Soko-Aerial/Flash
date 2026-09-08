package com.transfer.flash.core.messaging.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [throttleLatest], the operator that keeps the transfer layer's 10 ms progress
 * tick from re-deriving the whole open conversation a hundred times a second.
 *
 * These are wall-clock tests on purpose: the operator's whole job is a timing property, and a
 * virtual-time dispatcher would let a broken implementation pass. Every bound is therefore derived
 * from the *measured* elapsed time rather than from an assumed schedule, so a slow machine makes the
 * test slower, not flaky.
 */
class ProgressThrottleTest {

    @Test
    fun `the first value is emitted immediately rather than one window late`() = runBlocking {
        val source = MutableStateFlow(7)

        // A window far longer than any plausible scheduling delay: if the operator were trailing-edge
        // (kotlinx's own `sample`), this would take five seconds. It feeds a `combine` that cannot
        // emit until every input has, so a trailing edge would hold the conversation blank on open.
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        val firstValue = source.throttleLatest(windowMs = 5_000L).first()
        val elapsedMs = startedAt.elapsedNow().inWholeMilliseconds

        assertEquals(7, firstValue)
        assertTrue(elapsedMs < 1_000L, "the leading edge waited ${elapsedMs}ms for a 5000ms window")
    }

    @Test
    fun `a burst collapses to at most one value per window and the newest value always lands`() = runBlocking {
        val windowMs = 100L
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Int>()

        val collector = launch {
            source.throttleLatest(windowMs).collect { seen.add(it) }
        }
        // Let the leading edge through before the churn starts, so the count below measures the
        // throttled window and not the (deliberately un-throttled) first value.
        while (seen.isEmpty()) delay(5)
        val leadingEdgeCount = seen.size

        val churnStartedAt = kotlin.time.TimeSource.Monotonic.markNow()
        repeat(200) { i ->
            source.value = i + 1
            delay(1)
        }
        // Long enough for the final window to close and deliver the trailing value.
        delay(windowMs * 3)
        val elapsedMs = churnStartedAt.elapsedNow().inWholeMilliseconds
        collector.cancel()

        val emitted = seen.toList()
        // The newest value is never lost. This is the property the whole change rests on: a terminal
        // progress value (transfer complete, received path known) is simply the last one written, and
        // an operator that dropped it would strip the file off the row — the ERROR-034 outcome.
        assertEquals(200, emitted.last())
        // Values arrive in the order the source produced them; the source counts up.
        assertEquals(emitted.sorted(), emitted)
        // One value per window, plus the leading edge, plus one for a partially-elapsed final window.
        val budget = elapsedMs / windowMs + leadingEdgeCount + 1L
        assertTrue(
            emitted.size <= budget,
            "emitted ${emitted.size} values over ${elapsedMs}ms of ${windowMs}ms windows (budget $budget)",
        )
        // And it really did collapse: 200 source values must not have produced 200 emissions.
        assertTrue(emitted.size < 50, "the burst was not throttled at all: ${emitted.size} of 200")
        Unit
    }

    @Test
    fun `a non-positive window disables throttling entirely`() = runBlocking {
        assertEquals(
            listOf(1, 2, 3, 4, 5),
            flowOf(1, 2, 3, 4, 5).throttleLatest(windowMs = 0L).toList(),
        )
        assertEquals(
            listOf(1, 2, 3),
            flowOf(1, 2, 3).throttleLatest(windowMs = -50L).toList(),
        )
        Unit
    }

    @Test
    fun `a non-conflating upstream is spaced out but never loses or reorders a value`() = runBlocking {
        // The KDoc's precondition, pinned: throttling by suspending upstream only *skips* values when
        // that upstream conflates (every production wiring is StateFlow-rooted). Against a cold
        // finite flow the operator degrades to "slower", not to "wrong" — nothing is dropped.
        assertEquals(
            listOf(10, 20, 30),
            flowOf(10, 20, 30).throttleLatest(windowMs = 20L).toList(),
        )
        Unit
    }
}
