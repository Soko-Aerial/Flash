package com.transfer.flash.core.persistence.retention

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetentionPolicyTest {

    private val day = RetentionPolicy.MILLIS_PER_DAY

    @Test
    fun `cutoff is now minus retentionDays in millis`() {
        val now = 1_800_000_000_000L
        assertEquals(now - 30 * day, RetentionPolicy.cutoffMsOrNull(now, 30))
        assertEquals(now - day, RetentionPolicy.cutoffMsOrNull(now, 1))
        assertEquals(now - 365 * day, RetentionPolicy.cutoffMsOrNull(now, 365))
    }

    @Test
    fun `cutoff handles large day counts without int overflow`() {
        val now = Long.MAX_VALUE / 4
        val cutoff = RetentionPolicy.cutoffMsOrNull(now, Int.MAX_VALUE)
        assertEquals(now - Int.MAX_VALUE.toLong() * day, cutoff)
    }

    @Test
    fun `retentionDays zero disables pruning`() {
        assertNull(RetentionPolicy.cutoffMsOrNull(1000L, 0))
        assertEquals(
            emptyList<String>(),
            RetentionPolicy.eligibleForDeletion(1000L, 0, listOf(entry("a", 0L))),
        )
    }

    @Test
    fun `retentionDays negative disables pruning`() {
        assertNull(RetentionPolicy.cutoffMsOrNull(1000L, -5))
        assertEquals(emptyList<String>(), RetentionPolicy.eligibleForDeletion(1000L, -5, emptyList()))
    }

    @Test
    fun `entries strictly older than cutoff are eligible`() {
        val now = 10_000_000L
        val retentionDays = 7
        val old = entry("old", now - retentionDays * day - 1)
        val fresh = entry("fresh", now - day)
        assertEquals(listOf("old"), RetentionPolicy.eligibleForDeletion(now, retentionDays, listOf(old, fresh)))
    }

    @Test
    fun `entry exactly at cutoff boundary is spared`() {
        val now = 10_000_000L
        val retentionDays = 7
        val cutoff = now - retentionDays * day
        val atBoundary = entry("boundary", cutoff)
        val justAfterBoundary = entry("just-after", cutoff + 1)
        val justBeforeBoundary = entry("just-before", cutoff - 1)

        assertEquals(
            listOf("just-before"),
            RetentionPolicy.eligibleForDeletion(now, retentionDays, listOf(atBoundary, justAfterBoundary, justBeforeBoundary)),
        )
    }

    @Test
    fun `protected entries are spared regardless of age`() {
        val now = 10_000_000L
        val protectedOld = PrunableEntry("pinned", now - 400 * day, protected = true)
        val unprotectedOld = entry("plain", now - 400 * day)
        assertEquals(
            listOf("plain"),
            RetentionPolicy.eligibleForDeletion(now, 365, listOf(protectedOld, unprotectedOld)),
        )
    }

    @Test
    fun `empty entries list yields empty result`() {
        assertEquals(emptyList<String>(), RetentionPolicy.eligibleForDeletion(1000L, 30, emptyList()))
    }

    @Test
    fun `result preserves input order and deduplicates nothing`() {
        val now = 10_000_000L
        val a = entry("b", now - 2 * day)
        val b = entry("a", now - 3 * day)
        val c = entry("c", now - 5 * day)
        assertEquals(listOf("b", "a", "c"), RetentionPolicy.eligibleForDeletion(now, 1, listOf(a, b, c)))
    }

    private fun entry(id: String = "id-${Random.nextInt()}", createdAt: Long) =
        PrunableEntry(localId = id, createdAt = createdAt, protected = false)
}
