@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-102: the "You are sharing" strip is really drawn on top of a live video call while this device presents, and is
 * absent when it does not. The looks are a device check (SHARE-02); this guards the one thing that must never
 * regress: a person who shares always sees it.
 */
class FlashShareIndicatorRenderTest {

    private val width = 900
    private val height = 700

    private fun call(sharing: Boolean) = FlashCallUiState(
        callId = "c", peerId = "p", peerName = "Peer",
        direction = FlashCallDirection.OUTGOING, video = true, state = FlashCallState.ACTIVE,
        sharing = sharing, shareSourceTitle = "Screen 1", canShareScreen = true,
    )

    private fun renderScreen(state: FlashCallUiState): Bitmap {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            FlashTheme(darkTheme = true) {
                FlashCallScreen(
                    state = state, session = null,
                    onAccept = {}, onDecline = {}, onHangUp = {}, onToggleMute = {}, onToggleSpeaker = {},
                    onToggleCamera = {}, onSwitchCamera = {}, onDismiss = {},
                    share = FlashCallShareHost({ emptyList() }, false, { _, _, _ -> }, {}),
                )
            }
        }
        try {
            val t0 = 1_000_000_000L
            scene.render(nanoTime = t0)
            val image = scene.render(nanoTime = t0 + 50_000_000L)
            return Bitmap.makeFromImage(image)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the strip is drawn across the top of the call while sharing and not otherwise`() {
        val idle = renderScreen(call(sharing = false))
        val sharing = renderScreen(call(sharing = true))
        // Count the pixels of the top band that the share changed: the strip covers the whole width.
        var changed = 0
        var total = 0
        for (y in 0 until 24) for (x in 0 until width step 3) {
            total++
            if (idle.getColor(x, y) != sharing.getColor(x, y)) changed++
        }
        assertTrue(changed > total * 9 / 10, "the strip must colour the top of the call while sharing: $changed of $total")
        // Idle: the same band is not the strip's colour.
        assertTrue(idle.getColor(2, 4) != sharing.getColor(2, 4))
        assertEquals(sharing.getColor(2, 4), sharing.getColor(width - 3, 4), "full-width strip")
    }
}
