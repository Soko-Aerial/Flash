package com.transfer.flash.ui.shims

import com.sun.jna.Platform
import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsVideoPlayerTest {
    @Test
    fun `packaged Media Foundation decodes seeks and pauses a local video`() {
        val fixture = System.getenv("FLASH_WINDOWS_VIDEO_FIXTURE")
        assumeTrue("Set FLASH_WINDOWS_VIDEO_FIXTURE to a local Windows H.264 clip of at least four seconds", Platform.isWindows() && fixture != null)
        WindowsVideoPlayer.open(File(checkNotNull(fixture)).toURI().toString()).use { player ->
            player.control(playing = true, muted = true, seekToMs = null)
            val first = awaitSample(player) { it.frame != null && it.durationMs >= 4_000 }
            val frame = assertNotNull(first.frame)
            assertTrue((0 until frame.width * frame.height * 4).any { it % 4 != 3 && frame.bytes[it] != 0.toByte() })

            val target = first.durationMs / 2
            player.control(playing = false, muted = true, seekToMs = target)
            val paused = awaitSample(player) { !it.buffering && it.positionMs in (target - 150)..(target + 150) }
            Thread.sleep(220)
            assertTrue(abs(player.poll().positionMs - paused.positionMs) < 100)

            player.control(playing = true, muted = true, seekToMs = null)
            awaitSample(player) { it.positionMs >= target + 125 }

            player.control(playing = true, muted = true, seekToMs = first.durationMs - 200)
            awaitSample(player) { it.ended }
            player.control(playing = true, muted = true, seekToMs = 0)
            awaitSample(player) { !it.ended && it.frame != null && it.positionMs < target }
        }
    }

    private fun awaitSample(
        player: WindowsVideoPlayer,
        matches: (WindowsVideoPlayer.PlaybackSample) -> Boolean,
    ): WindowsVideoPlayer.PlaybackSample {
        val deadline = System.nanoTime() + 8_000_000_000L
        do {
            val sample = player.poll()
            if (matches(sample)) return sample
            Thread.sleep(15)
        } while (System.nanoTime() < deadline)
        error("Windows playback did not reach the expected state")
    }
}
