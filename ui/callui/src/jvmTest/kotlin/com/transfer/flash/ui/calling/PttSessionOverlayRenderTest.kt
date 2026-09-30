@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.ptt.PttPingEvent
import com.transfer.flash.core.ptt.PttPressOutcome
import com.transfer.flash.core.ptt.PttSessionStats
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jetbrains.skia.Bitmap

/**
 * Draws the shared PTT card in a real Skia scene (what the desktop shell shows, ADR-058) and presses its
 * one button. This is a render smoke test, not a visual-design review: it proves the composable draws
 * when the floor state says Talking/Listening, draws nothing when Idle, and that the Stop/Leave target
 * reaches `FlashPtt.stopLocal()`. Looks, reading order and scaling are device checks (PTTD-07).
 */
class PttSessionOverlayRenderTest {

    private val width = 720
    private val height = 480
    private val accent = FlashColors.dark().accentPrimary.toArgb()

    @Test
    fun `talking draws the card and its button stops the session`() {
        val ptt = StubPtt()
        val state = PttFloorState.Talking(sessionId = "s", startedAtMs = 0L, holderId = "me")
        val scene = scene(ptt, state)
        try {
            val bitmap = scene.renderBitmap()
            val button = accentCentroid(bitmap)
            assertTrue(button != null, "no accent-coloured Stop button was drawn")
            scene.sendPointerEvent(PointerEventType.Press, button)
            scene.sendPointerEvent(PointerEventType.Release, button)
            scene.renderBitmap()
            assertEquals(1, ptt.stops.get(), "tapping Stop must call FlashPtt.stopLocal exactly once")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `listening draws the card and its button leaves`() {
        val ptt = StubPtt()
        val state = PttFloorState.Listening(
            sessionId = "s",
            holderId = "peer",
            holderName = "Flash Penguin",
            startedAtMs = 0L,
            lastActivityMs = 0L,
        )
        val scene = scene(ptt, state)
        try {
            val button = accentCentroid(scene.renderBitmap())
            assertTrue(button != null, "no accent-coloured Leave button was drawn")
            scene.sendPointerEvent(PointerEventType.Press, button)
            scene.sendPointerEvent(PointerEventType.Release, button)
            scene.renderBitmap()
            assertEquals(1, ptt.stops.get(), "Leave goes through stopLocal too")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `idle draws nothing`() {
        val scene = scene(StubPtt(), PttFloorState.Idle)
        try {
            val bitmap = scene.renderBitmap()
            // Nothing drawn: no accent button and the centre is still the scene's transparent clear colour
            // (a drawn scrim would make it non-zero).
            assertEquals(null, accentCentroid(bitmap))
            assertEquals(0, bitmap.getColor(width / 2, height / 2))
        } finally {
            scene.close()
        }
    }

    private fun scene(ptt: FlashPtt, state: PttFloorState): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            FlashTheme(darkTheme = true) {
                PttSessionOverlayContent(engine = ptt, state = state, animateLevels = false)
            }
        }

    private fun ImageComposeScene.renderBitmap(): Bitmap {
        val image = render(nanoTime = System.nanoTime())
        return Bitmap.makeFromImage(image)
    }

    /** Centre of the pixels whose colour is exactly the theme accent, i.e. the Stop/Leave chip (no meter). */
    private fun accentCentroid(bitmap: Bitmap): Offset? {
        var sx = 0L
        var sy = 0L
        var n = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bitmap.getColor(x, y) == accent) {
                    sx += x
                    sy += y
                    n++
                }
            }
        }
        return if (n < MIN_BUTTON_PIXELS) null else Offset(sx.toFloat() / n, sy.toFloat() / n)
    }

    private class StubPtt : FlashPtt {
        val stops = AtomicInteger()
        override val state: StateFlow<PttFloorState> = MutableStateFlow(PttFloorState.Idle)
        override val stats: StateFlow<PttSessionStats?> = MutableStateFlow(null)
        override val notices: SharedFlow<String> = MutableSharedFlow()
        override val pings: Flow<PttPingEvent> = emptyFlow()
        override fun onPttButton(): PttPressOutcome = PttPressOutcome.ACCEPTED
        override fun sendPing(): Boolean = false
        override fun postNotice(text: String) = Unit
        override fun stopLocal() {
            stops.incrementAndGet()
        }
        override fun onCallStarted() = Unit
        override fun acquireVoiceNoteLease(): String? = null
        override fun releaseVoiceNoteLease(leaseId: String) = Unit
        override fun onInboundText(peerId: String, text: String): Boolean = false
        override fun onInboundBinary(peerId: String?, data: ByteArray): Boolean = false
        override fun shutdown() = Unit
    }

    private companion object {
        const val MIN_BUTTON_PIXELS = 500L
    }
}
