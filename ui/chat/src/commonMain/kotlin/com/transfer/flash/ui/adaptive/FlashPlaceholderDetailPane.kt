package com.transfer.flash.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Pure copy + icon resolution per shell destination so detail placeholders reflect the active tab. */
internal object FlashPlaceholderDetailMath {
    data class PlaceholderCopy(
        val title: String,
        val subtitle: String,
        val icon: FlashIconSpec,
        val showFindDevicesAction: Boolean,
    )

    fun copyFor(destination: FlashDestination): PlaceholderCopy = when (destination) {
        FlashDestination.ChatList, FlashDestination.Conversation -> PlaceholderCopy(
            title = "Select a chat",
            subtitle = "Choose a conversation from the list to start messaging, or find nearby devices to connect.",
            icon = FlashIcons.Chat,
            showFindDevicesAction = true,
        )
        FlashDestination.Transfers -> PlaceholderCopy(
            title = "Select a transfer",
            subtitle = "Choose a file transfer from the list to check its progress, save location, or actions.",
            icon = FlashIcons.Transfer,
            showFindDevicesAction = true,
        )
        FlashDestination.NearbyDevices -> PlaceholderCopy(
            title = "Select a device",
            subtitle = "Choose a nearby or paired device to check its status, pair, or start a chat.",
            icon = FlashIcons.Nearby,
            showFindDevicesAction = false,
        )
        FlashDestination.Hub -> PlaceholderCopy(
            title = "Organization Hub",
            subtitle = "Select an announcement, person, or department from the Hub to view it here.",
            icon = FlashIcons.Hub,
            showFindDevicesAction = false,
        )
        FlashDestination.Settings -> PlaceholderCopy(
            title = "Settings",
            subtitle = "Adjust your device name, appearance, storage, and connection preferences on the left.",
            icon = FlashIcons.Settings,
            showFindDevicesAction = false,
        )
    }
}

/**
 * Branded detail pane empty state for two-pane layout on Desktop and wide-screen Android
 * (tablets, foldables, landscape, Samsung DeX).
 *
 * Displays a subtle Flash icon medallion with destination-aware copy inviting the user
 * to select an item from the list pane or discover nearby devices.
 */
@Composable
fun FlashPlaceholderDetailPane(
    modifier: Modifier = Modifier,
    onFindDevices: (() -> Unit)? = null,
    destination: FlashDestination = FlashDestination.ChatList,
    title: String? = null,
    subtitle: String? = null,
    icon: FlashIconSpec? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val defaults = FlashPlaceholderDetailMath.copyFor(destination)
    val resolvedTitle = title ?: defaults.title
    val resolvedSubtitle = subtitle ?: defaults.subtitle
    val resolvedIcon = icon ?: defaults.icon

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.backgroundApp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            modifier = Modifier.padding(horizontal = FlashSpacing.space32),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colors.accentFill.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = resolvedIcon,
                    contentDescription = null,
                    tint = colors.accentFill,
                    size = FlashDimensions.iconLg,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            FlashText(
                text = resolvedTitle,
                style = typography.headingSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            FlashText(
                text = resolvedSubtitle,
                style = typography.metadataDefault,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (defaults.showFindDevicesAction && onFindDevices != null) {
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
                Box(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.avatar)
                        .background(colors.accentFill)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onFindDevices)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Find devices"
                        }
                        .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = "Find devices",
                        style = typography.bodyDefault.copy(fontWeight = FontWeight.Medium),
                        color = colors.onAccentFill,
                    )
                }
            }
        }
    }
}
