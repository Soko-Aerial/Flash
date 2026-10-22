package com.transfer.flash.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FlashVideoPlayerMathTest {

    @Test
    fun `play click toggles play state during normal playback without seeking`() {
        val pausedResult = FlashVideoPlayerMath.onPlayClick(isCompleted = false, isPlaying = true)
        assertEquals(false, pausedResult.isCompleted)
        assertEquals(false, pausedResult.isPlaying)
        assertNull(pausedResult.seekToTarget)

        val resumedResult = FlashVideoPlayerMath.onPlayClick(isCompleted = false, isPlaying = false)
        assertEquals(false, resumedResult.isCompleted)
        assertEquals(true, resumedResult.isPlaying)
        assertNull(resumedResult.seekToTarget)
    }

    @Test
    fun `replay click resets completed, targets 0L seek, and starts playing`() {
        val replayResult = FlashVideoPlayerMath.onPlayClick(isCompleted = true, isPlaying = false)
        assertEquals(false, replayResult.isCompleted)
        assertEquals(true, replayResult.isPlaying)
        assertEquals(0L, replayResult.seekToTarget)
    }

    @Test
    fun `onSeekHandled resets seek target to null`() {
        val reset = FlashVideoPlayerMath.onSeekHandled()
        assertNull(reset)
    }

    @Test
    fun `subsequent replays trigger seek change when reset on handled`() {
        var seekTarget: Long? = null

        // 1. First replay
        val firstClick = FlashVideoPlayerMath.onPlayClick(isCompleted = true, isPlaying = false)
        seekTarget = firstClick.seekToTarget
        assertEquals(0L, seekTarget)

        // 2. Platform surface dispatches seek and clears target
        seekTarget = FlashVideoPlayerMath.onSeekHandled()
        assertNull(seekTarget)

        // 3. Second replay after completing playback again
        val secondClick = FlashVideoPlayerMath.onPlayClick(isCompleted = true, isPlaying = false)
        val previousTarget = seekTarget
        seekTarget = secondClick.seekToTarget

        // State changes from null -> 0L, ensuring LaunchedEffect(seekToMs) fires on subsequent replays
        assertEquals(0L, seekTarget)
        assertNotEquals(previousTarget, seekTarget)
    }

    @Test
    fun `unhandled seek target causes subsequent replay to skip state change`() {
        var seekTarget: Long? = null

        // 1. First replay
        val firstClick = FlashVideoPlayerMath.onPlayClick(isCompleted = true, isPlaying = false)
        seekTarget = firstClick.seekToTarget
        assertEquals(0L, seekTarget)

        // Bug scenario (F47): onSeekHandled is omitted, leaving seekTarget = 0L
        // 2. Second replay
        val secondClick = FlashVideoPlayerMath.onPlayClick(isCompleted = true, isPlaying = false)
        val previousTarget = seekTarget
        seekTarget = secondClick.seekToTarget

        // Bug evidence: target did not change (0L == 0L), skipping seek on subsequent replay
        assertEquals(previousTarget, seekTarget)
    }
}
