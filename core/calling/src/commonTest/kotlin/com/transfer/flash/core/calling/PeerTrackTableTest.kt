package com.transfer.flash.core.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G1: one remote video per participant, in a stable order. */
class PeerTrackTableTest {

    private class Track(val name: String) {
        override fun toString() = name
    }

    @Test
    fun `each participant keeps its own track and its place`() {
        val table = PeerTrackTable<Track>()
        val a1 = Track("a1")
        val b1 = Track("b1")
        val a2 = Track("a2")
        assertTrue(table.put("a", a1))
        assertTrue(table.put("b", b1))
        assertEquals(listOf("a", "b"), table.snapshot().keys.toList())
        assertEquals(b1, table.newest)

        // A renegotiated track replaces a's video without moving a's tile.
        assertTrue(table.put("a", a2))
        assertEquals(listOf("a", "b"), table.snapshot().keys.toList())
        assertEquals(a2, table.snapshot()["a"])
        assertEquals(a2, table.newest, "the latest arrival is what a one-video caller shows")
        assertFalse(table.put("a", a2), "the same track again is no change")
    }

    @Test
    fun `a late close does not remove the replacement track`() {
        val table = PeerTrackTable<Track>()
        val old = Track("old")
        val new = Track("new")
        table.put("a", old)
        table.put("a", new)
        assertFalse(table.remove("a", expected = old))
        assertEquals(new, table.snapshot()["a"])
        assertTrue(table.remove("a", expected = new))
        assertTrue(table.snapshot().isEmpty())
        assertNull(table.newest)
    }

    @Test
    fun `newest falls back to the previous arrival when the newest leaves`() {
        val table = PeerTrackTable<Track>()
        val a = Track("a")
        val b = Track("b")
        table.put("a", a)
        table.put("b", b)
        table.remove("b")
        assertEquals(a, table.newest)
        table.clear()
        assertNull(table.newest)
        assertFalse(table.remove("a"))
    }

    @Test
    fun `a snapshot does not change when the table does`() {
        val table = PeerTrackTable<Track>()
        table.put("a", Track("a"))
        val snapshot = table.snapshot()
        table.put("b", Track("b"))
        assertEquals(setOf("a"), snapshot.keys)
    }
}
