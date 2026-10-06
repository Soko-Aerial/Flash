@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashMotion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat

/**
 * Draws the dock in a real Skia scene at both motion behaviours and checks the animation really is a
 * HIGH-only thing. Looks are a device check (CALLDOCK-01); env `FLASH_DOCK_SHOTS=<dir>` also writes PNGs.
 */
class FlashCallControlDockRenderTest {

    private val width = 840
    private val height = 280

    private fun callState(muted: Boolean = false, cameraOff: Boolean = false, speaker: Boolean = false) =
        FlashCallUiState(
            callId = "c", peerId = "p", peerName = "Peer",
            direction = FlashCallDirection.OUTGOING, video = true, state = FlashCallState.ACTIVE,
            micMuted = muted, cameraOff = cameraOff, speakerOn = speaker,
        )

    private fun frameAfterMute(reduceMotion: Boolean, atMillis: Long, shot: String?): List<Int> {
        var state by mutableStateOf(callState())
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            FlashTheme(darkTheme = true, motion = rememberFlashMotion(reduceMotion = reduceMotion)) {
                FlashCallControlDock(state, {}, {}, {}, {}, {})
            }
        }
        try {
            val t0 = 1_000_000_000L
            scene.render(nanoTime = t0)
            state = callState(muted = true)
            // The scene advances its animation clock one frame per render call, so step it like a display
            // would (16 ms) up to the moment we want to look at.
            var image = scene.render(nanoTime = t0 + 1_000_000L)
            var elapsed = 0L
            while (elapsed < atMillis) {
                elapsed += 16
                image = scene.render(nanoTime = t0 + (1 + elapsed) * 1_000_000L)
            }
            val bitmap = Bitmap.makeFromImage(image)
            val pixels = IntArray(width * height) { i ->
                bitmap.getColor(i % width, i / width)
            }.toList()
            val dir = System.getenv("FLASH_DOCK_SHOTS")
            if (shot != null && dir != null) {
                File(dir).mkdirs()
                File(dir, "$shot.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
            return pixels
        } finally {
            scene.close()
        }
    }

    @Test
    fun `at HIGH the mute toggle is mid animation shortly after the tap and settled later`() {
        val early = frameAfterMute(reduceMotion = false, atMillis = 60, shot = "high-60ms")
        val settled = frameAfterMute(reduceMotion = false, atMillis = 2_000, shot = "high-settled")
        assertTrue(early != settled, "no animation was visible 60 ms after the toggle")
    }

    @Test
    fun `at MEDIUM and LOW the toggle is already in its final frame at once`() {
        val early = frameAfterMute(reduceMotion = true, atMillis = 60, shot = "reduced-60ms")
        val settled = frameAfterMute(reduceMotion = true, atMillis = 2_000, shot = "reduced-settled")
        assertTrue(early == settled, "reduce-motion still animated: the frame changed after the tap")
    }

    @Test
    fun `every dock state draws`() {
        val states = mapOf(
            "video-camera-off-speaker" to callState(cameraOff = true, speaker = true),
            "video-default" to callState(),
            "audio-muted" to callState(muted = true).copy(video = false),
            "audio-speaker" to callState(speaker = true).copy(video = false),
            "audio-can-add-camera" to callState().copy(video = false, canUpgradeToVideo = true),
            "video-joined-without-camera" to callState(cameraOff = true).copy(canUpgradeToVideo = true),
        )
        for (dark in listOf(true, false)) {
            for ((name, st) in states) {
                val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
                    FlashTheme(darkTheme = dark, motion = rememberFlashMotion(reduceMotion = true)) {
                        FlashCallControlDock(st, {}, {}, {}, {}, {}, onUpgradeToVideo = {})
                    }
                }
                try {
                    scene.render(nanoTime = 1_000_000_000L)
                    val image = scene.render(nanoTime = 1_100_000_000L)
                    val bytes = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
                    assertTrue(bytes.size > 2_000, "$name (dark=$dark) drew nothing")
                    val dir = System.getenv("FLASH_DOCK_SHOTS")
                    if (dir != null) File(dir, "state-$name-${if (dark) "dark" else "light"}.png").writeBytes(bytes)
                } finally {
                    scene.close()
                }
            }
        }
    }

    private fun dockBytes(st: FlashCallUiState, upgrade: (() -> Unit)?): ByteArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(2f)) {
            FlashTheme(darkTheme = true, motion = rememberFlashMotion(reduceMotion = true)) {
                FlashCallControlDock(st, {}, {}, {}, {}, {}, onUpgradeToVideo = upgrade)
            }
        }
        try {
            scene.render(nanoTime = 1_000_000_000L)
            return scene.render(nanoTime = 1_100_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a voice call draws the add camera button only while it is on offer and the host can take it`() {
        val voice = callState().copy(video = false)
        val plain = dockBytes(voice, upgrade = {})
        val offered = dockBytes(voice.copy(canUpgradeToVideo = true), upgrade = {})
        val hostCannot = dockBytes(voice.copy(canUpgradeToVideo = true), upgrade = null)
        assertTrue(!plain.contentEquals(offered), "the add camera button did not appear")
        assertTrue(plain.contentEquals(hostCannot), "a host with no upgrade callback must show no button")
    }
}
