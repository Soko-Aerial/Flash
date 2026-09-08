package com.transfer.flash.ui

import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the app-side [throttleLatest] — the operator that keeps the transfer layer's
 * 10 ms progress tick from invalidating the whole app shell on every frame.
 *
 * `:core:messaging` carries the same operator for the chat side and has its own copy of these tests;
 * `UiPacing.kt` records why there is no shared home. These are wall-clock tests on purpose: the
 * operator's whole job is a timing property, and a virtual-time dispatcher would let a broken
 * implementation pass. Every bound is derived from the *measured* elapsed time rather than from an
 * assumed schedule, so a slow machine makes the test slower, not flaky.
 */
class UiPacingTest {

    @Test(timeout = 20_000)
    fun `the first value is emitted immediately rather than one window late`() = runBlocking {
        val source = MutableStateFlow(7)

        // A window far longer than any plausible scheduling delay: a trailing-edge operator would
        // take five seconds to produce anything, and the shell would render a tab with no data.
        val startedAt = System.nanoTime()
        val firstValue = source.throttleLatest(windowMs = 5_000L).first()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

        assertEquals(7, firstValue)
        assertTrue("the leading edge waited ${elapsedMs}ms for a 5000ms window", elapsedMs < 1_000L)
    }

    @Test(timeout = 30_000)
    fun `a burst collapses to at most one value per window and the newest value always lands`() = runBlocking {
        val windowMs = 100L
        val source = MutableStateFlow(0)
        val seen = Collections.synchronizedList(mutableListOf<Int>())

        val collector = launch(Dispatchers.Default) {
            source.throttleLatest(windowMs).collect { seen.add(it) }
        }
        // Let the leading edge through before the churn starts, so the count below measures the
        // throttled window and not the (deliberately un-throttled) first value.
        while (seen.isEmpty()) delay(5)
        val leadingEdgeCount = seen.size

        val churnStartedAt = System.nanoTime()
        repeat(200) { i ->
            source.value = i + 1
            delay(1)
        }
        // Long enough for the final window to close and deliver the trailing value.
        delay(windowMs * 3)
        val elapsedMs = (System.nanoTime() - churnStartedAt) / 1_000_000L
        collector.cancel()

        val emitted = seen.toList()
        // The newest value is never lost. A terminal transfer list — the one where a row finally reads
        // Completed and carries its received path — is simply the last one written.
        assertEquals(200, emitted.last())
        // Values arrive in the order the source produced them; the source counts up.
        assertEquals(emitted.sorted(), emitted)
        // One value per window, plus the leading edge, plus one for a partially-elapsed final window.
        val budget = elapsedMs / windowMs + leadingEdgeCount + 1L
        assertTrue(
            "emitted ${emitted.size} values over ${elapsedMs}ms of ${windowMs}ms windows (budget $budget)",
            emitted.size <= budget,
        )
        // And it really did collapse: 200 source values must not have produced 200 emissions.
        assertTrue("the burst was not throttled at all: ${emitted.size} of 200", emitted.size < 50)
        Unit
    }

    @Test(timeout = 20_000)
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

    @Test(timeout = 20_000)
    fun `a non-conflating upstream is spaced out but never loses or reorders a value`() = runBlocking {
        // Throttling by suspending upstream only *skips* values when that upstream conflates, which
        // every production wiring does (StateFlow). Against a cold finite flow the operator degrades
        // to "slower", not to "wrong" — nothing is dropped.
        assertEquals(
            listOf(10, 20, 30),
            flowOf(10, 20, 30).throttleLatest(windowMs = 20L).toList(),
        )
        Unit
    }
}
