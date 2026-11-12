@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics

/*
 * UI-050h (docs/ui/calling-ui.md): pinch to zoom into a shared screen, and drag to look around it. Watching somebody's
 * presentation on a phone means reading 10 pt text on a 6-inch display, so the receivers need their own magnifier; it
 * changes nothing about what the presenter sends (no renegotiation, no re-tune, the same track).
 *
 * The arithmetic is pure and unit-tested (FlashVideoZoomMath); the state is one per call screen, so a new presentation,
 * a new presenter or the end of the call starts at 1x again.
 */

/** Pure zoom arithmetic (UI-050h), unit-tested without Compose. */
internal object FlashVideoZoomMath {
    /** The most a shared screen can be magnified. Text at 5x is far past readable; beyond it a finger drag moves nothing. */
    const val MAX_SCALE: Float = 5f

    /** 1x is "the whole picture", so a pinch never shrinks it below the fitted size. */
    const val MIN_SCALE: Float = 1f

    /**
     * The scale after a pinch of [factor] (1.0 = no change), kept inside [MIN_SCALE]..[MAX_SCALE]. A pinch that would
     * go below 1x stops at 1x; the caller then resets the pan too.
     */
    fun scaleAfter(current: Float, factor: Float): Float =
        (current * factor).takeIf { it.isFinite() }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: current

    /**
     * The pan after a drag of [panPx] at [scale]: -1 is the top/left edge, +1 the bottom/right one, 0 the middle, measured as
     * a fraction of the room a picture that fills [box] would have at that scale. At 1x nothing moves: the whole picture is
     * already in the box.
     *
     * A fraction, not pixels, because only the surface knows how much of the box the picture really covers — a letterboxed
     * axis has less room than the box suggests, or none at all, so the surface applies this to the travel it really has.
     */
    fun panAfter(current: Offset, panPx: Offset, box: IntSize, scale: Float): Offset {
        val halfX = box.width * (scale - 1f) / 2f
        val halfY = box.height * (scale - 1f) / 2f
        val x = if (halfX <= 1f) 0f else (current.x + panPx.x / halfX).coerceIn(-1f, 1f)
        val y = if (halfY <= 1f) 0f else (current.y + panPx.y / halfY).coerceIn(-1f, 1f)
        return Offset(x, y)
    }
}

/**
 * How far one shared screen is magnified on this device. Held by the call screen (not by the surface), so a tile that is
 * recomposed, re-measured or replaced by another renderer keeps the zoom the person set.
 */
@Stable
internal class FlashVideoZoomState {
    var scale by mutableFloatStateOf(FlashVideoZoomMath.MIN_SCALE)
        private set

    /** Pan as [FlashVideoZoomMath.panAfter] describes it: -1..1 per axis. */
    var pan by mutableStateOf(Offset.Zero)
        private set

    /** True while the picture is bigger than the box: the "Fit to screen" chip is shown and a drag does something. */
    val active: Boolean get() = scale > FlashVideoZoomMath.MIN_SCALE + 0.001f

    /** One pinch/pan frame from [detectTransformGestures]. [box] is the surface's size in pixels. */
    fun onTransform(zoomChange: Float, panChange: Offset, box: IntSize) {
        val next = FlashVideoZoomMath.scaleAfter(scale, zoomChange)
        if (next <= FlashVideoZoomMath.MIN_SCALE + 0.001f) {
            reset()
            return
        }
        scale = next
        pan = FlashVideoZoomMath.panAfter(pan, panChange, box, next)
    }

    /** Back to the whole picture (the chip, a double tap, the end of the presentation). */
    fun reset() {
        scale = FlashVideoZoomMath.MIN_SCALE
        pan = Offset.Zero
    }
}

/** Wording for UI-050h, pure so it is unit-tested like the rest of the share copy. */
internal object CallZoomText {
    const val FIT_TO_SCREEN: String = "Fit to screen"
    const val FIT_TO_SCREEN_DESCRIPTION: String = "Zoom out to see the whole shared screen"
}

/**
 * A shared screen that can be magnified: the picture, the pinch/pan gestures over it, and the way back out.
 *
 * Only the presentation is ever zoomable; a camera picture is not (a face at 3x is not a feature, it is a bug report). A null
 * [zoom] therefore means "this picture cannot be magnified" and leaves the same surface composed without an overlay — the
 * caller passes it for a camera, and for the presentation while the person is looking at their own preview instead. Going
 * through this composable in every case keeps ONE surface instance per box: switching to or from a presentation, or swapping
 * the pictures, must not dispose and rebuild the renderer underneath (the blip would land exactly when a share starts).
 *
 * [enabled] is false while the track is missing, so a stale gesture cannot move a picture that is not there.
 */
@Composable
internal fun FlashZoomableVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    zoom: FlashVideoZoomState?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    enabled: Boolean = track != null && zoom != null,
) {
    Box(modifier = modifier) {
        FlashCallVideoSurface(
            track = track,
            fit = fit,
            modifier = Modifier.fillMaxSize(),
            mirror = mirror,
            zoom = zoom?.scale ?: 1f,
            pan = zoom?.pan ?: Offset.Zero,
        )
        if (enabled && zoom != null) {
            // An overlay, not a modifier on the surface: the tap that swaps the picture and the mini preview keeps working
            // (a transform gesture only consumes once it passes touch slop).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(zoom) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom.onTransform(zoomChange, panChange, size)
                        }
                    },
            )
        }
        if (zoom != null && zoom.active) {
            FlashFitToScreenChip(
                onClick = { zoom.reset() },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = FlashSpacing.space8),
            )
        }
    }
}

/** "Fit to screen": the visible way back to the whole picture, for anyone a pinch does not reach. */
@Composable
private fun FlashFitToScreenChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val haptic = rememberFlashHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = FlashDimensions.minTouchTarget)
            .flashPressScale(interaction)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                haptic(FlashHaptic.Tick)
                onClick()
            }
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = CallZoomText.FIT_TO_SCREEN_DESCRIPTION
            },
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = FlashIcons.ZoomFit,
            contentDescription = null,
            tint = Color.White,
            size = FlashDimensions.iconSm,
            modifier = Modifier.clearAndSetSemantics { },
        )
        FlashText(
            text = CallZoomText.FIT_TO_SCREEN,
            style = FlashTheme.typography.metadataDefault,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
