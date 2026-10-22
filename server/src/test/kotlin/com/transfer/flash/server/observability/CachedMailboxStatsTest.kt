package com.transfer.flash.server.observability

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SRVHUB-12 part (OPS-01, ADR-222): a stats read never waits for the database. */
class CachedMailboxStatsTest {

    private class Counting(var count: Long = 10, var bytes: Long = 1000) : MailboxStats {
        val loads = AtomicInteger()
        @Volatile var gate: CountDownLatch? = null
        @Volatile var failing = false
        @Volatile var oldest: Map<String, Long> = mapOf("dev-a" to 5_000L, "dev-b" to 1_000L)

        private fun touch() {
            loads.incrementAndGet()
            gate?.await(10, TimeUnit.SECONDS)
            if (failing) error("database busy")
        }
        override fun queuedCount(): Long { touch(); return count }
        override fun queuedBytes(): Long = bytes
        override fun oldestAgeMs(deviceId: String): Long? = oldest[deviceId]
        override fun oldestAgesMs(limit: Int): Map<String, Long> = oldest.entries.take(limit).associate { it.key to it.value }
        override fun deliveredTotal(): Long = 7
        override fun expiredTotal(): Long = 0
        override fun droppedTotal(): Long = 0
    }

    @Test
    fun theFirstSnapshotIsTakenAtConstructionAndReadsDoNotTouchTheDatabaseAgain() {
        val db = Counting()
        val stats = CachedMailboxStats(db, ttlMs = 5_000, clock = { 100L })
        assertEquals(1, db.loads.get())
        repeat(50) { assertEquals(10L, stats.queuedCount()); assertEquals(1000L, stats.queuedBytes()) }
        assertEquals(1, db.loads.get(), "fresh snapshot: no further queries")
        assertEquals(7L, stats.deliveredTotal(), "the in-memory counters pass straight through")
    }

    @Test
    fun aStaleReadReturnsTheOldNumbersAtOnceWhileOneRefreshRuns() {
        var now = 0L
        val db = Counting()
        val stats = CachedMailboxStats(db, ttlMs = 1_000, clock = { now })
        db.count = 99
        db.gate = CountDownLatch(1) // the refresh will block on the "database"
        now = 5_000

        var seen = -1L
        val reader = thread { repeat(20) { seen = stats.queuedCount() } }
        reader.join(2_000)
        assertTrue(!reader.isAlive, "the reader was not blocked by the slow database")
        assertEquals(10L, seen, "the old snapshot is served while the refresh is pending")
        assertEquals(2, db.loads.get(), "exactly one refresh was started, not twenty")

        db.gate!!.countDown()
        val deadline = System.currentTimeMillis() + 5_000
        while (stats.queuedCount() != 99L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(99L, stats.queuedCount(), "the refreshed numbers arrive")
    }

    @Test
    fun aFailingRefreshKeepsTheLastNumbersAndIsRetried() {
        var now = 0L
        val db = Counting()
        val stats = CachedMailboxStats(db, ttlMs = 1_000, clock = { now })
        db.failing = true
        now = 5_000
        assertEquals(10L, stats.queuedCount())
        Thread.sleep(200)
        assertEquals(10L, stats.queuedCount(), "the last good numbers stay")
        db.failing = false
        db.count = 11
        val deadline = System.currentTimeMillis() + 5_000
        while (stats.queuedCount() != 11L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(11L, stats.queuedCount())
    }

    @Test
    fun theOldestAgesGrowWithTheSnapshotAgeAndHonourTheLimit() {
        var now = 0L
        val db = Counting()
        val stats = CachedMailboxStats(db, ttlMs = 60_000, clock = { now })
        now = 2_000
        assertEquals(mapOf("dev-a" to 7_000L), stats.oldestAgesMs(1))
        assertEquals(2, stats.oldestAgesMs(10).size)
    }

    @Test
    fun aDatabaseThatFailsAtStartStillBuildsAndRecovers() {
        val db = Counting().also { it.failing = true }
        val stats = CachedMailboxStats(db, ttlMs = 1_000, clock = { 0L })
        assertEquals(0L, stats.queuedCount(), "nothing known yet")
        db.failing = false
        val deadline = System.currentTimeMillis() + 5_000
        while (stats.queuedCount() != 10L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(10L, stats.queuedCount())
    }
}
