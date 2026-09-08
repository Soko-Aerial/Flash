package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashMessageStatus
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics

/**
 * UI-015 Animated Delivery & Read Status Icon.
 *
 * Provides real-time visual feedback for message transit lifecycle:
 * - [FlashMessageStatus.Pending] -> Clock / sending
 * - [FlashMessageStatus.Sent] -> Single check
 * - [FlashMessageStatus.Delivered] -> Double check
 * - [FlashMessageStatus.Read] -> Double check illuminated in [FlashTheme.colors.accentPrimary]
 * - [FlashMessageStatus.Failed] -> Error alert with 1-tap retry callback
 */
@Composable
fun FlashDeliveryStatusIcon(
    status: FlashMessageStatus,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    size: Dp = 14.dp,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val iconColor by animateColorAsState(
        targetValue = when (status) {
            FlashMessageStatus.Pending,
            FlashMessageStatus.Sent,
            FlashMessageStatus.Delivered,
            -> colors.chatTextTimestampOutgoing

            FlashMessageStatus.Read -> colors.accentPrimary
            FlashMessageStatus.Failed -> colors.textError
        },
        animationSpec = motion.tweenFastSpec(),
        label = "deliveryStatusColor",
    )

    val a11yDescription = when (status) {
        FlashMessageStatus.Pending -> "Sending message"
        FlashMessageStatus.Sent -> "Sent"
        FlashMessageStatus.Delivered -> "Delivered"
        FlashMessageStatus.Read -> "Read by recipient"
        FlashMessageStatus.Failed -> "Failed to send. Double-tap to retry."
    }

    val statusModifier = modifier.semantics {
        contentDescription = a11yDescription
        if (status == FlashMessageStatus.Failed && onRetry != null) {
            role = Role.Button
        }
    }

    if (motion.reduceMotion) {
        // The crossfade spec already snaps under reduce-motion, so a direct swap is visually
        // identical and skips the per-row Transition plus the second layout a crossfade runs.
        Box(modifier = statusModifier, contentAlignment = Alignment.Center) {
            DeliveryStatusGlyph(status, iconColor, size, onRetry, haptics)
        }
    } else {
        AnimatedContent(
            targetState = status,
            transitionSpec = { motion.statusCrossfade() },
            label = "deliveryStatusTransition",
            modifier = statusModifier,
        ) { currentStatus ->
            DeliveryStatusGlyph(currentStatus, iconColor, size, onRetry, haptics)
        }
    }
}

@Composable
private fun DeliveryStatusGlyph(
    status: FlashMessageStatus,
    iconColor: Color,
    size: Dp,
    onRetry: (() -> Unit)?,
    haptics: (FlashHaptic) -> Unit,
) {
    when (status) {
        FlashMessageStatus.Pending -> {
            FlashIcon(
                icon = FlashIcons.Clock,
                contentDescription = null,
                tint = iconColor,
                size = size,
            )
        }

        FlashMessageStatus.Sent -> {
            FlashIcon(
                icon = FlashIcons.Check,
                contentDescription = null,
                tint = iconColor,
                size = size,
            )
        }

        FlashMessageStatus.Delivered -> {
            FlashIcon(
                icon = FlashIcons.Delivered,
                contentDescription = null,
                tint = iconColor,
                size = size,
            )
        }

        FlashMessageStatus.Read -> {
            FlashIcon(
                icon = FlashIcons.Read,
                contentDescription = null,
                tint = iconColor,
                size = size,
            )
        }

        FlashMessageStatus.Failed -> {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = onRetry != null,
                        onClick = {
                            haptics(FlashHaptic.Confirm)
                            onRetry?.invoke()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Failed,
                    contentDescription = null,
                    tint = iconColor,
                    size = size,
                )
            }
        }
    }
}

@Preview(name = "Delivery States - Row", showBackground = true)
@Composable
private fun FlashDeliveryStatusPreview() {
    FlashTheme {
        Row(modifier = Modifier.size(width = 200.dp, height = 40.dp)) {
            FlashDeliveryStatusIcon(status = FlashMessageStatus.Pending)
            FlashDeliveryStatusIcon(status = FlashMessageStatus.Sent)
            FlashDeliveryStatusIcon(status = FlashMessageStatus.Delivered)
            FlashDeliveryStatusIcon(status = FlashMessageStatus.Read)
            FlashDeliveryStatusIcon(status = FlashMessageStatus.Failed, onRetry = {})
        }
    }
}
