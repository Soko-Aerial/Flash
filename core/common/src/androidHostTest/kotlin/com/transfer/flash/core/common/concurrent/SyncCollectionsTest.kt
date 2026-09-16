@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.common.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the promoted lock-guarded collections (Phase 2, slice 4).
 *
 * The load-bearing semantic is [SyncSet.add]'s return value: it IS the group-media claim
 * mechanism (`claimedGroupMedia`) — a second add of the same id must report false so only
 * one path mints the bubble. The `remove(key, value)` case pins the sync-round retirement
 * rule (a stale round object must not evict its replacement).
 */
class SyncCollectionsTest {

    @Test
    fun `set add reports false for duplicates and contains sees inserts`() {
        val set = SyncSet<String>()
        assertTrue(set.add("a"))
        assertFalse(set.add("a"))
        assertTrue("a" in set)
        assertFalse("b" in set)
        assertEquals(setOf("a"), set.toSet())
    }

    @Test
    fun `set remove reports presence and toList follows`() {
        val set = SyncSet<String>()
        set.add("a")
        set.add("b")
        assertTrue(set.remove("a"))
        assertFalse(set.remove("a"))
        assertEquals(listOf("b"), set.toList())
        assertFalse(set.isEmpty())
    }

    @Test
    fun `map put get remove and conditional remove`() {
        val map = SyncMap<String, Int>()
        map["k"] = 1
        assertEquals(1, map["k"])
        assertEquals(1, map.getOrPut("k") { 2 })
        assertEquals(2, map.getOrPut("j") { 2 })
        // A stale value must not evict the live one.
        assertFalse(map.remove("k", 99))
        assertEquals(1, map["k"])
        assertTrue(map.remove("k", 1))
        assertEquals(null, map["k"])
        assertEquals(mapOf("j" to 2), map.toMap())
    }
}
