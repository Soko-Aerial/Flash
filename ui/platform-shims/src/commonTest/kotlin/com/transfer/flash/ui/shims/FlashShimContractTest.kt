package com.transfer.flash.ui.shims

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The part of the shim surface a test can reach on **every** target.
 *
 * The seams themselves are `@Composable` factories, and this repo has no Compose UI-test harness, so
 * the actuals are exercised per-platform instead (`jvmTest` drives the desktop implementations
 * directly). What is left in common is the contract the two sides share: the data carried across the
 * picker seam, the tile decode budget, and the permission set both actuals must map. Each of these is
 * a value another module or actual depends on, so pinning them here is what makes a silent change
 * loud — R3.1's point that "an `actual` that is only compiled is not verified" applies to constants
 * shared across actuals just as much as to functions.
 */
class FlashShimContractTest {

    @Test
    fun `picked file carries the three fields the transfer layer needs`() {
        val picked = FlashPickedFile(uri = "file:/tmp/holiday.jpg", name = "holiday.jpg", size = 2_048L)

        assertEquals("file:/tmp/holiday.jpg", picked.uri)
        assertEquals("holiday.jpg", picked.name)
        assertEquals(2_048L, picked.size)
    }

    @Test
    fun `picked file compares by value so a re-pick of the same file is not a new event`() {
        val first = FlashPickedFile(uri = "file:/tmp/a.bin", name = "a.bin", size = 10L)
        val second = FlashPickedFile(uri = "file:/tmp/a.bin", name = "a.bin", size = 10L)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertTrue(first != second.copy(size = 11L))
    }

    @Test
    fun `tile decode budget stays at 720 px`() {
        // The in-bubble grid decodes every tile to this long edge, and the Android cache is sized in
        // bytes against it (~2 MB per tile at 4 bytes/px). Raising it silently multiplies the memory
        // a conversation full of photos holds; lowering it makes tiles visibly soft.
        assertEquals(720, FlashImageDecoder.TILE_LONG_EDGE_PX)
    }

    @Test
    fun `microphone is the only permission both actuals have to map`() {
        // Each actual translates this enum to something platform-specific — a manifest name on
        // Android, an unconditional `true` on desktop. A new constant added without touching both
        // would compile and then fail at runtime on whichever platform was forgotten, so the count
        // is the tripwire.
        assertEquals(listOf(FlashPermission.Microphone), FlashPermission.entries.toList())
    }
}
