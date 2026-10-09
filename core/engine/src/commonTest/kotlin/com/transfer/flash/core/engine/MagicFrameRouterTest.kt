package com.transfer.flash.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MagicFrameRouterTest {

    @Test
    fun `frames shorter than 4 bytes never throw and return false`() {
        val router = MagicFrameRouter()
        assertFalse(router.dispatch("peer-1", byteArrayOf(), reply = { true }))
        assertFalse(router.dispatch("peer-1", byteArrayOf(1), reply = { true }))
        assertFalse(router.dispatch("peer-1", byteArrayOf(1, 2), reply = { true }))
        assertFalse(router.dispatch("peer-1", byteArrayOf(1, 2, 3), reply = { true }))
    }

    @Test
    fun `with no handler an FLSH frame returns false so it reaches transfer route`() {
        val router = MagicFrameRouter()
        val flshFrame = "FLSH\u0000\u0001\u0000\u0000".encodeToByteArray()
        val consumed = router.dispatch("peer-1", flshFrame, reply = { true })
        assertFalse(consumed)
    }

    @Test
    fun `an FSW1 frame without handler is dropped as reserved magic and returns true`() {
        val router = MagicFrameRouter()
        val fsw1Frame = "FSW1\u0001\u0001\u0000\u0000".encodeToByteArray()
        val consumed = router.dispatch("peer-1", fsw1Frame, reply = { true })
        assertTrue(consumed)
    }

    @Test
    fun `a registered handler consumes its magic`() {
        val router = MagicFrameRouter()
        var handledPeer: String? = null
        var handledBytes: ByteArray? = null

        val customMagic = "TEST".encodeToByteArray()
        router.register(customMagic) { peer, frame, reply ->
            handledPeer = peer
            handledBytes = frame
            true
        }

        val frame = "TEST12345".encodeToByteArray()
        val consumed = router.dispatch("peer-42", frame, reply = { true })
        assertTrue(consumed)
        assertEquals("peer-42", handledPeer)
        assertEquals("TEST12345", handledBytes?.decodeToString())

        // After unregister, custom magic is no longer consumed
        router.unregister(customMagic)
        val consumedAfter = router.dispatch("peer-42", frame, reply = { true })
        assertFalse(consumedAfter)
    }

    @Test
    fun `a registered handler for FSW1 overrides reserved drop`() {
        val router = MagicFrameRouter()
        var customHandled = false

        router.register(MagicFrameRouter.FSW1_MAGIC) { _, _, _ ->
            customHandled = true
            true
        }

        val fsw1Frame = "FSW1payload".encodeToByteArray()
        val consumed = router.dispatch("peer-1", fsw1Frame, reply = { true })
        assertTrue(consumed)
        assertTrue(customHandled)
    }

    @OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)
    private fun captureWarnings(block: () -> Unit): Int {
        var warnings = 0
        com.transfer.flash.core.common.logging.FlashLog.installSink { level, tag, message, _ ->
            if (level == com.transfer.flash.core.common.logging.FlashLogLevel.WARN && tag == "SWARM" && message.startsWith("Dropped reserved magic")) warnings++
        }
        try {
            block()
        } finally {
            com.transfer.flash.core.common.logging.FlashLog.installSink { _, _, _, _ -> }
        }
        return warnings
    }

    @Test
    fun `the first dropped reserved frame is logged even when the clock reads zero`() {
        val warnings = captureWarnings {
            val router = MagicFrameRouter(timeSource = { 0L })
            router.dispatch("peer-1", "FSW1payload".encodeToByteArray(), reply = { true })
        }
        assertEquals(1, warnings)
    }

    @Test
    fun `dropped reserved frames are rate limited and logged again after the interval`() {
        var clock = 5L
        val warnings = captureWarnings {
            val router = MagicFrameRouter(timeSource = { clock })
            val frame = "FSW1payload".encodeToByteArray()
            router.dispatch("p", frame, reply = { true })
            clock += 9_999L
            router.dispatch("p", frame, reply = { true })
            clock += 1L
            router.dispatch("p", frame, reply = { true })
        }
        assertEquals(2, warnings)
    }

    @Test
    fun `the default clock advances (it was always about zero)`() {
        val clock = MagicFrameRouter.monotonicMillis()
        val first = clock()
        val deadline = first + 30
        var later = clock()
        while (later < deadline && later - first < 2_000) {
            // busy-wait a few ms without a platform sleep (common test source set)
            later = clock()
        }
        assertTrue(later - first >= 30, "default clock must increase over a small delay: $first -> $later")
    }
}
