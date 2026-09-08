package com.transfer.flash.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-012 Custom Attachment Button.
 *
 * Stateful composer attachment trigger with 45° rotation micro-interaction,
 * tactile press physics, and active accent color state.
 */
@Composable
fun FlashAttachmentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val rotation by animateFloatAsState(
        targetValue = if (isExpanded && !motion.reduceMotion) 45f else 0f,
        animationSpec = motion.springSnappySpec(),
        label = "attachButtonRotation",
    )

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !motion.reduceMotion) 0.88f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "attachButtonPressScale",
    )

    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled -> colors.textTertiary
            isExpanded -> colors.accentPrimary
            else -> colors.textPrimary
        },
        animationSpec = motion.tweenFastSpec(),
        label = "attachButtonTint",
    )

    Box(
        modifier = modifier
            .size(FlashSpacing.space40)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                rotationZ = rotation
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics(FlashHaptic.Tick)
                    onClick()
                },
            )
            .semantics {
                role = Role.Button
                contentDescription = if (isExpanded) {
                    "Close attachment options"
                } else {
                    "Attach media or files"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = FlashIcons.Attach,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(FlashDimensions.iconMd),
        )
    }
}

@Preview(name = "Attachment Button - Inactive", showBackground = true)
@Composable
private fun FlashAttachmentButtonInactivePreview() {
    FlashTheme {
        Box(modifier = Modifier.size(FlashDimensions.minTouchTarget), contentAlignment = Alignment.Center) {
            FlashAttachmentButton(
                onClick = {},
                isExpanded = false,
            )
        }
    }
}

@Preview(name = "Attachment Button - Active Expanded", showBackground = true)
@Composable
private fun FlashAttachmentButtonActivePreview() {
    FlashTheme {
        Box(modifier = Modifier.size(FlashDimensions.minTouchTarget), contentAlignment = Alignment.Center) {
            FlashAttachmentButton(
                onClick = {},
                isExpanded = true,
            )
        }
    }
}
