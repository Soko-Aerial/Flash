package com.transfer.flash.core.swarm.engine

import com.transfer.flash.core.swarm.model.ContentRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestWindowTest {
    private val root = ContentRoot("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")

    @Test
    fun `initial window starts at 4 and scales additively to max 64`() {
        val rw = RequestWindow(budgetBytes = 8L * 1024 * 1024)
        assertEquals(4, rw.getWindow("peerA", root))

        rw.onPieceCompleted("peerA", root)
        assertEquals(5, rw.getWindow("peerA", root))

        repeat(100) {
            rw.onPieceCompleted("peerA", root)
        }
        assertEquals(64, rw.getWindow("peerA", root))
    }

    @Test
    fun `congestion halves window multiplicatively down to min 1`() {
        val rw = RequestWindow(budgetBytes = 8L * 1024 * 1024)
        repeat(10) { rw.onPieceCompleted("peerA", root) } // 4 + 10 = 14
        assertEquals(14, rw.getWindow("peerA", root))

        rw.onCongestion("peerA", root)
        assertEquals(7, rw.getWindow("peerA", root))

        rw.onCongestion("peerA", root)
        assertEquals(3, rw.getWindow("peerA", root))

        rw.onCongestion("peerA", root)
        assertEquals(1, rw.getWindow("peerA", root))

        rw.onCongestion("peerA", root)
        assertEquals(1, rw.getWindow("peerA", root))
    }

    @Test
    fun `call active halves effective window size`() {
        val rw = RequestWindow(budgetBytes = 8L * 1024 * 1024)
        repeat(12) { rw.onPieceCompleted("peerA", root) } // 16
        assertEquals(16, rw.getWindow("peerA", root))

        rw.setCallActive(true)
        assertEquals(8, rw.getWindow("peerA", root))

        rw.setCallActive(false)
        assertEquals(16, rw.getWindow("peerA", root))
    }

    @Test
    fun `global in-flight byte budget enforces bounds`() {
        val budget = 1024L * 1024L // 1 MiB
        val rw = RequestWindow(budgetBytes = budget)

        assertTrue(rw.canRequestBytes(512 * 1024))
        rw.addInFlightBytes(512 * 1024)
        assertEquals(512 * 1024L, rw.currentInFlightBytes)

        assertTrue(rw.canRequestBytes(512 * 1024))
        assertFalse(rw.canRequestBytes(512 * 1024 + 1))

        rw.addInFlightBytes(512 * 1024)
        assertFalse(rw.canRequestBytes(1))

        rw.removeInFlightBytes(256 * 1024)
        assertTrue(rw.canRequestBytes(256 * 1024))
        assertFalse(rw.canRequestBytes(256 * 1024 + 1))
    }
}
