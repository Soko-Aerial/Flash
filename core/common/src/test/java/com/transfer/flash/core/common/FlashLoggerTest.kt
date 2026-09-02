@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common

import com.transfer.flash.core.common.logging.FlashLogEntry
import com.transfer.flash.core.common.logging.FlashLogLevel
import com.transfer.flash.core.common.logging.FlashLogger
import com.transfer.flash.core.common.time.FakeTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FlashLoggerTest {

    private fun entryAt(index: Int, capacity: Int = FlashLogger.DEFAULT_CAPACITY): FlashLogger =
        FlashLogger(tag = "TEST", capacity = capacity, timeSource = FakeTimeSource(currentMs = index.toLong()))

    @Test
    fun entries_stored_with_level_tag_message_timestamp() {
        val time = FakeTimeSource(currentMs = 42L)
        val logger = FlashLogger(tag = "TRANSFER", timeSource = time)

        logger.i("chunk written")

        val recent = logger.recent()
        assertEquals(1, recent.size)
        assertEquals(FlashLogLevel.INFO, recent[0].level)
        assertEquals("TRANSFER", recent[0].tag)
        assertEquals("chunk written", recent[0].message)
        assertEquals(42L, recent[0].timestampMs)
    }

    @Test
    fun levels_i_w_e_are_recorded() {
        val logger = entryAt(0)

        logger.i("info")
        logger.w("warn")
        logger.e("error")

        val levels = logger.recent().map { it.level }
        assertEquals(listOf(FlashLogLevel.INFO, FlashLogLevel.WARN, FlashLogLevel.ERROR), levels)
    }

    @Test
    fun throwable_overloads_record_message() {
        val logger = entryAt(0)

        logger.e("failed", IllegalStateException("boom"))

        val recent = logger.recent()
        assertEquals(1, recent.size)
        assertEquals("failed", recent[0].message)
        assertEquals(FlashLogLevel.ERROR, recent[0].level)
    }

    @Test
    fun ring_buffer_evicts_oldest_when_full() {
        val capacity = 3
        val logger = FlashLogger(tag = "TEST", capacity = capacity, timeSource = FakeTimeSource())

        for (i in 1..5) {
            logger.i("msg-$i")
        }

        val messages = logger.recent().map { it.message }
        assertEquals(listOf("msg-3", "msg-4", "msg-5"), messages)
    }

    @Test
    fun recent_limit_returns_most_recent_entries_in_chronological_order() {
        val logger = FlashLogger(tag = "TEST", capacity = 10, timeSource = FakeTimeSource())

        for (i in 1..6) {
            logger.i("msg-$i")
        }

        val limited = logger.recent(limit = 2).map { it.message }
        assertEquals(listOf("msg-5", "msg-6"), limited)

        assertEquals(6, logger.recent(limit = 100).size)
        assertTrue(logger.recent(limit = 0).isEmpty())
    }

    @Test
    fun clear_drops_all_entries() {
        val logger = entryAt(0)

        logger.i("one")
        logger.i("two")
        logger.clear()

        assertTrue(logger.recent().isEmpty())
    }

    @Test
    fun timestamps_come_from_injected_time_source() {
        val time = FakeTimeSource(currentMs = 0L)
        val logger = FlashLogger(tag = "TEST", timeSource = time)

        logger.i("first")
        time.advance(500)
        logger.i("second")

        val timestamps = logger.recent().map { it.timestampMs }
        assertEquals(listOf(0L, 500L), timestamps)
    }

    @Test
    fun concurrent_writes_never_exceed_capacity_and_keep_order() {
        val capacity = 512
        val logger = FlashLogger(tag = "TEST", capacity = capacity, timeSource = FakeTimeSource())
        val writers = 8
        val perWriter = 200
        val ready = CountDownLatch(writers)
        val done = CountDownLatch(writers)
        val pool = Executors.newFixedThreadPool(writers)

        repeat(writers) { writerIndex ->
            pool.execute {
                ready.countDown()
                ready.await()
                repeat(perWriter) { i -> logger.i("w$writerIndex-$i") }
                done.countDown()
            }
        }

        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdown()

        val recent = logger.recent()
        assertEquals(capacity, recent.size)

        val timestamps = recent.map { it.timestampMs }
        assertEquals(timestamps, timestamps.sorted())

        val lastMessage = recent.last().message
        assertTrue(
            "last entry should be from some writer's final message, was: $lastMessage",
            Regex("w\\d-${perWriter - 1}$").containsMatchIn(lastMessage)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun zero_capacity_throws() {
        FlashLogger(tag = "TEST", capacity = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blank_tag_throws() {
        FlashLogger(tag = "  ")
    }
}
