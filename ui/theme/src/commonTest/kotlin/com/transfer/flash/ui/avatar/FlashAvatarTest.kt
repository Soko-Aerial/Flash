package com.transfer.flash.ui.avatar

import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashAvatarTest {

    @Test
    fun `initial font size scales proportionally with avatar diameter`() {
        val xs = flashAvatarInitialFontSize(FlashDimensions.avatarXs) // 28.dp
        val sm = flashAvatarInitialFontSize(FlashDimensions.avatarSm) // 32.dp
        val md = flashAvatarInitialFontSize(FlashDimensions.avatarMd) // 36.dp
        val lg = flashAvatarInitialFontSize(FlashDimensions.avatarLg) // 48.dp
        val xl = flashAvatarInitialFontSize(FlashDimensions.avatarXl) // 64.dp
        val call = flashAvatarInitialFontSize(96.dp)

        assertTrue(xs.isSp)
        assertTrue(xs.value < sm.value)
        assertTrue(sm.value < md.value)
        assertTrue(md.value < lg.value)
        assertTrue(lg.value < xl.value)
        assertTrue(xl.value < call.value)

        listOf(
            FlashDimensions.avatarXs to xs,
            FlashDimensions.avatarSm to sm,
            FlashDimensions.avatarMd to md,
            FlashDimensions.avatarLg to lg,
            FlashDimensions.avatarXl to xl,
            96.dp to call,
        ).forEach { (diameter, fontSize) ->
            val ratio = fontSize.value / diameter.value
            assertTrue(
                ratio in 0.36f..0.40f,
                "Expected font ratio 0.36..0.40 for $diameter, got $ratio ($fontSize)",
            )
        }
    }

    @Test
    fun `initial font size enforces positive floor for tiny or zero sizes`() {
        val zero = flashAvatarInitialFontSize(0.dp)
        assertTrue(zero.isSp)
        assertTrue(zero.value >= 6f)
        assertEquals("Alice", avatarSeed("Alice"))
    }
}
