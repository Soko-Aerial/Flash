package com.transfer.flash.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics

/**
 * Pure visibility rules for the conversation three-dot menu (group Phase D). The app composes
 * them with its own capability flags so the menu stays data-driven and testable.
 */
public object FlashConversationMenuMath {
    /** Items for a 1:1 conversation. */
    public fun directItems(
        canRevokeTrust: Boolean,
    ): List<FlashConversationMenuItem> = buildList {
        add(FlashConversationMenuItem.VIEW_PROFILE)
        add(FlashConversationMenuItem.SEARCH)
        if (canRevokeTrust) add(FlashConversationMenuItem.REVOKE_TRUST)
        add(FlashConversationMenuItem.MARK_UNREAD)
        add(FlashConversationMenuItem.CLEAR_CONVERSATION)
    }

    /** Items for a group conversation. Leave is hidden when the device is the sole member. */
    public fun groupItems(
        canLeave: Boolean,
    ): List<FlashConversationMenuItem> = buildList {
        add(FlashConversationMenuItem.GROUP_INFO)
        add(FlashConversationMenuItem.ADD_MEMBERS)
        add(FlashConversationMenuItem.SEARCH)
        add(FlashConversationMenuItem.MARK_UNREAD)
        if (canLeave) add(FlashConversationMenuItem.LEAVE_GROUP)
    }
}

public enum class FlashConversationMenuItem(
    val label: String,
    val icon: FlashIconSpec,
    val isDestructive: Boolean = false,
) {
    VIEW_PROFILE("View profile", FlashIcons.Device),
    SEARCH("Search in conversation", FlashIcons.Search),
    REVOKE_TRUST("Revoke trust", FlashIcons.Close, isDestructive = true),
    MARK_UNREAD("Mark as unread", FlashIcons.Read),
    CLEAR_CONVERSATION("Clear conversation", FlashIcons.Delete, isDestructive = true),
    GROUP_INFO("Group info", FlashIcons.Group),
    ADD_MEMBERS("Add members", FlashIcons.Group),
    LEAVE_GROUP("Leave group", FlashIcons.Close, isDestructive = true),
}

/**
 * The conversation three-dot menu (group Phase D): custom styled with Flash tokens,
 * elevated surface, icons, destructive styling, press micro-interactions, and anchored
 * to the header's More button.
 */
@Composable
public fun FlashConversationMenu(
    expanded: Boolean,
    items: List<FlashConversationMenuItem>,
    onItemSelected: (FlashConversationMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()

    val menuShape = RoundedCornerShape(FlashShapes.radius16)

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier
            .widthIn(min = 210.dp, max = 280.dp)
            .background(colors.backgroundSurfaceStrong, shape = menuShape)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle,
                shape = menuShape,
            ),
        offset = DpOffset(x = 0.dp, y = FlashSpacing.space4),
        shape = menuShape,
        containerColor = colors.backgroundSurfaceStrong,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(FlashDimensions.borderHairline, colors.borderSubtle),
    ) {
        items.forEachIndexed { index, item ->
            if (item.isDestructive && index > 0 && !items[index - 1].isDestructive) {
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = FlashSpacing.space12,
                        vertical = FlashSpacing.space4,
                    ),
                    thickness = FlashDimensions.borderHairline,
                    color = colors.borderSubtle,
                )
            }

            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.97f else 1f,
                animationSpec = FlashTheme.motion.springSnappySpec(),
                label = "menuItemScale",
            )

            DropdownMenuItem(
                text = {
                    FlashText(
                        text = item.label,
                        style = FlashTheme.typography.bodyDefault,
                        color = if (item.isDestructive) colors.textError else colors.textPrimary,
                    )
                },
                leadingIcon = {
                    FlashIcon(
                        icon = item.icon,
                        tint = if (item.isDestructive) colors.textError else colors.textSecondary,
                        size = FlashDimensions.iconSm,
                    )
                },
                onClick = {
                    haptics(FlashHaptic.Tick)
                    onDismiss()
                    onItemSelected(item)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentPadding = PaddingValues(
                    horizontal = FlashSpacing.space16,
                    vertical = FlashSpacing.space8,
                ),
                interactionSource = interactionSource,
            )
        }
    }
}
