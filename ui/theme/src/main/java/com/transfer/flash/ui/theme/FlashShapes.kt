package com.transfer.flash.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Flash corner-radius strategy. Shapes are intentionally soft but not uniformly pill-shaped.
 */
object FlashShapes {
    /** Depth of the concave "pulse scoop" tail on tailed bubbles (UI-005). */
    val bubbleTailSize = 8.dp

    val radius2 = 2.dp
    val radius4 = 4.dp
    val radius8 = 8.dp
    val radius12 = 12.dp
    val radius16 = 16.dp
    val radius20 = 20.dp
    val radius24 = 24.dp
    val radiusFull = 999.dp

    val chip = RoundedCornerShape(radius8)
    val attachment = RoundedCornerShape(radius12)
    val sheet = RoundedCornerShape(topStart = radius24, topEnd = radius24)
    val composerInput = RoundedCornerShape(radius20)
    val composerBar = RoundedCornerShape(radiusFull)
    val avatar = RoundedCornerShape(radiusFull)
    val button = RoundedCornerShape(radius12)

    /** UI-046 hanging bottom-nav capsule, its sliding indicator chip, and tab badges. */
    val navBar = RoundedCornerShape(radiusFull)

    /** Grouped message bubble — all corners rounded. */
    val bubbleGrouped = RoundedCornerShape(radius20)

    /** Last incoming bubble — concave tail scoop on the bottom-start corner. */
    val bubbleIncomingTail: Shape = FlashBubbleShape(outgoing = false)

    /** Last outgoing bubble — concave tail scoop on the bottom-end corner. */
    val bubbleOutgoingTail: Shape = FlashBubbleShape(outgoing = true)

    /**
     * Standalone bubble alias. SINGLE-positioned messages pick a tailed shape
     * by direction in the chat bubble mapping (UI-005).
     */
    val bubbleSingle: Shape = bubbleGrouped
}

/**
 * Flash bubble silhouette (UI-005).
 *
 * Three true circular corners plus one concave cubic-Bézier "pulse scoop" on the
 * sender-facing bottom corner — end edge for outgoing, start edge for incoming.
 * The scoop mirrors automatically in RTL via [LayoutDirection].
 */
class FlashBubbleShape(
    private val outgoing: Boolean,
    private val cornerRadius: Dp = FlashShapes.radius20,
    private val tailSize: Dp = FlashShapes.bubbleTailSize,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { cornerRadius.toPx() }
            .coerceAtMost(minOf(w, h) / 2f)
            .coerceAtLeast(0f)
        val t = with(density) { tailSize.toPx() }
            .coerceAtMost((minOf(w, h) - r).coerceAtLeast(0f))
            .coerceAtLeast(0f)

        val tailAtEnd = when (layoutDirection) {
            LayoutDirection.Ltr -> outgoing
            LayoutDirection.Rtl -> !outgoing
        }

        val path = if (tailAtEnd) {
            tailAtEndPath(w, h, r, t)
        } else {
            tailAtStartPath(w, h, r, t)
        }
        return Outline.Generic(path)
    }

    private fun tailAtEndPath(w: Float, h: Float, r: Float, t: Float): Path {
        return Path().apply {
            moveTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
            lineTo(w, h - t)
            // Concave scoop: interior control points bow the curve into the bubble.
            cubicTo(w - t * 0.45f, h - t, w - t, h - t * 0.45f, w - t, h)
            lineTo(r, h)
            arcTo(Rect(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
            close()
        }
    }

    private fun tailAtStartPath(w: Float, h: Float, r: Float, t: Float): Path {
        return Path().apply {
            moveTo(0f, r)
            arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
            lineTo(w - r, 0f)
            arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
            lineTo(w, h - r)
            arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
            lineTo(t, h)
            // Mirror of the end-edge scoop.
            cubicTo(t, h - t * 0.45f, t * 0.45f, h - t, 0f, h - t)
            close()
        }
    }
}
