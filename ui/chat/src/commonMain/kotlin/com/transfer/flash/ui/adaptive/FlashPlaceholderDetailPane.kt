package com.transfer.flash.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Branded detail pane empty state for two-pane layout on Desktop and wide-screen Android
 * (tablets, foldables, landscape, Samsung DeX).
 *
 * Displays a subtle Flash icon medallion with descriptive copy inviting the user
 * to select a conversation or discover nearby devices, matching WhatsApp/Telegram desktop.
 */
@Composable
fun FlashPlaceholderDetailPane(
    modifier: Modifier = Modifier,
    onFindDevices: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
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
                    .background(colors.accentPrimary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Chat,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    size = FlashDimensions.iconLg,
                )
            }
            FlashText(
                text = "Select a chat",
                style = typography.headingSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            FlashText(
                text = "Choose a conversation from the list to start messaging, or find nearby devices to connect.",
                style = typography.metadataDefault,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (onFindDevices != null) {
                Spacer(modifier = Modifier.height(FlashSpacing.space4))
                Box(
                    modifier = Modifier
                        .clip(FlashShapes.avatar)
                        .background(colors.accentPrimary)
                        .clickable(onClick = onFindDevices)
                        .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space8),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = "Find devices",
                        style = typography.bodyDefault.copy(fontWeight = FontWeight.Medium),
                        color = colors.textOnAccent,
                    )
                }
            }
        }
    }
}
