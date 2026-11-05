package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashChatWallpaper
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * "Chat appearance" (ADR-354): one app-wide wallpaper, chosen by looking at bubbles on the field
 * rather than at colour names. Mirrors the approved mock; the only omission is the mock's free
 * colour, which needs a second stored value the app does not have yet (deviation D-1 in the
 * handoff).
 */
@Composable
public fun FlashChatAppearanceSheet(
    wallpaper: FlashChatWallpaper,
    onSelect: (FlashChatWallpaper) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FlashSpacing.space20)
                .padding(bottom = FlashSpacing.space24),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlashText(
                    text = "Chat appearance",
                    style = typography.headingMedium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        contentDescription = "Close",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(FlashDimensions.iconMd),
                    )
                }
            }

            // Live preview: the field with two bubbles on it, never text directly on the pattern.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clip(RoundedCornerShape(FlashShapes.radius16))
                    .flashChatWallpaper(wallpaper)
                    .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(FlashShapes.radius16))
                    .padding(FlashSpacing.space12),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    PreviewBubble(
                        text = "Checksum matches here too.",
                        incoming = true,
                    )
                    PreviewBubble(
                        text = "Archive both before Friday.",
                        incoming = false,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }

            FlashText(
                text = "Presets",
                style = typography.metadataEmphasis,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space20, bottom = FlashSpacing.space8),
            )

            val rows = FlashChatWallpaperPresets.chunked(3)
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 0.dp else FlashSpacing.space8),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    row.forEach { preset ->
                        PresetTile(
                            preset = preset,
                            selected = preset.wallpaper == wallpaper,
                            onSelect = { onSelect(preset.wallpaper) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlashSpacing.space20),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = "Theme",
                    style = typography.bodyDefault,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                FlashText(
                    text = "Follows the system",
                    style = typography.captionDefault,
                    color = colors.textTertiary,
                )
            }

            FlashText(
                text = "The wallpaper sits behind the messages. Every bubble keeps its own surface, so no preset can dim the text.",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space12),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlashSpacing.space20)
                    .height(48.dp)
                    .clip(RoundedCornerShape(FlashShapes.radius12))
                    .background(colors.accentFill)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Done",
                    style = typography.bodyEmphasis,
                    color = colors.onAccentFill,
                )
            }
        }
    }
}

@Composable
private fun PresetTile(
    preset: FlashChatWallpaperPreset,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .clickable(onClick = onSelect),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .flashChatWallpaper(preset.wallpaper)
                .border(
                    width = if (selected) 2.dp else FlashDimensions.borderHairline,
                    color = if (selected) colors.accentPrimary else colors.borderSubtle,
                    shape = RoundedCornerShape(FlashShapes.radius12),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText(
                text = preset.label,
                style = typography.metadataEmphasis,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (selected) {
                FlashIcon(
                    icon = FlashIcons.Check,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewBubble(
    text: String,
    incoming: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(if (incoming) colors.chatBgIncoming else colors.chatBgOutgoing)
            .then(
                if (incoming) {
                    Modifier.border(
                        FlashDimensions.borderHairline,
                        colors.chatBorderIncoming,
                        RoundedCornerShape(FlashShapes.radius12),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.captionDefault,
            color = if (incoming) colors.chatTextIncoming else colors.chatTextOutgoing,
        )
    }
}
