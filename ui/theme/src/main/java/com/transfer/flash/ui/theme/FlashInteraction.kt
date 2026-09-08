package com.transfer.flash.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Resting → pressed scale of a Flash row; matches the chat-list row press feel. */
private const val PressedScale = 0.98f

/**
 * Shared press feedback for Flash rows and cards (UI-041 family).
 *
 * The animated value is read inside [graphicsLayer] so a press costs a render pass instead of
 * recomposing the row's whole subtree, and it collapses to an instant snap under reduce-motion
 * because the spec comes from [FlashTheme.motion].
 *
 * Pair it with the same [InteractionSource] you hand to `clickable`/`combinedClickable`:
 *
 * ```
 * val interactionSource = remember { MutableInteractionSource() }
 * Row(Modifier.flashPressScale(interactionSource).clickable(interactionSource, null) { ... })
 * ```
 */
@Composable
fun Modifier.flashPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = PressedScale,
): Modifier {
    val motion = FlashTheme.motion
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "flashPressScale",
    )
    return this.graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
