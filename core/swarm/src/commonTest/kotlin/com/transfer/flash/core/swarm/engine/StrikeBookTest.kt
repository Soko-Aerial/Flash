package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.ContentRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrikeBookTest {
    private val root1 = ContentRoot("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val root2 = ContentRoot("2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40")

    @Test
    fun `initial strikes are zero and peer is not banned`() {
        val book = StrikeBook(maxStrikes = 3)
        assertEquals(0, book.getStrikes("peerA", root1))
        assertFalse(book.isBanned("peerA", root1))
    }

    @Test
    fun `strikes accumulate and trigger ban at threshold`() {
        val book = StrikeBook(maxStrikes = 3)
        assertEquals(1, book.recordStrike("peerA", root1))
        assertFalse(book.isBanned("peerA", root1))

        assertEquals(2, book.recordStrike("peerA", root1))
        assertFalse(book.isBanned("peerA", root1))

        assertEquals(3, book.recordStrike("peerA", root1))
        assertTrue(book.isBanned("peerA", root1))

        // Beyond max still banned
        assertEquals(4, book.recordStrike("peerA", root1))
        assertTrue(book.isBanned("peerA", root1))
    }

    @Test
    fun `strikes are isolated by peer and by root`() {
        val book = StrikeBook(maxStrikes = 3)
        book.recordStrike("peerA", root1)
        book.recordStrike("peerA", root1)
        book.recordStrike("peerA", root1)

        assertTrue(book.isBanned("peerA", root1))
        assertFalse(book.isBanned("peerA", root2))
        assertFalse(book.isBanned("peerB", root1))
    }

    @Test
    fun `clear resets all strikes and bans`() {
        val book = StrikeBook(maxStrikes = 3)
        book.recordStrike("peerA", root1)
        book.recordStrike("peerA", root1)
        book.recordStrike("peerA", root1)
        assertTrue(book.isBanned("peerA", root1))

        book.clear()
        assertFalse(book.isBanned("peerA", root1))
        assertEquals(0, book.getStrikes("peerA", root1))
    }
}
