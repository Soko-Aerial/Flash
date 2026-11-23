package com.transfer.flash.ui.shims

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashVideoSurfaceJvmTest {

    @Test
    fun `unconstrained layout uses decoded frame dimensions instead of collapsing to 0x0`() {
        val size = measureVideoSurfaceSize(
            constraints = Constraints(),
            frameWidth = 1280,
            frameHeight = 720,
        )
        assertEquals(IntSize(1280, 720), size)
    }

    @Test
    fun `unconstrained layout before first frame uses non-zero fallback size`() {
        val size = measureVideoSurfaceSize(
            constraints = Constraints(),
            frameWidth = 0,
            frameHeight = 0,
        )
        assertTrue(size.width > 0 && size.height > 0)
    }

    @Test
    fun `fixed constraints fill parent bounds`() {
        val size = measureVideoSurfaceSize(
            constraints = Constraints.fixed(800, 600),
            frameWidth = 1280,
            frameHeight = 720,
        )
        assertEquals(IntSize(800, 600), size)
    }

    @Test
    fun `bounded wrap constraints clamp decoded frame within bounds`() {
        val size = measureVideoSurfaceSize(
            constraints = Constraints(maxWidth = 640, maxHeight = 480),
            frameWidth = 1280,
            frameHeight = 720,
        )
        assertEquals(IntSize(640, 480), size)
    }
}
