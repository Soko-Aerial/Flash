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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
 * "Chat appearance" (ADR-354): the approved mock's wallpaper sheet and its appearance page,
 * combined into the one surface the conversation menu opens.
 *
 * Mirrors `design/chat-web/src/flash/Wallpaper.tsx`:
 * - a live preview with a day chip and two bubbles,
 * - the five presets plus **Yours** (a flat colour of the user's own),
 * - the six named colours,
 * - an `Applied · <name>` line with **Undo** back to the value the sheet opened with, and Done.
 *
 * The one deliberate difference: the mock's separate "Chat appearance" page also carried a
 * Light/Dark control. Flash's theme is one app setting already owned by Settings, and this sheet
 * has no host callback for it, so it states "Follows the system" instead of pretending to switch it.
 */
@Composable
public fun FlashChatAppearanceSheet(
    wallpaper: FlashChatWallpaper,
    onSelect: (FlashChatWallpaper) -> Unit,
    onDismiss: () -> Unit,
    custom: String = FlashChatWallpaper.DefaultCustomColour,
    onCustom: (String) -> Unit = {},
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    // What the sheet found when it opened, so Undo is a real revert rather than a second guess.
    val openedWallpaper = remember { wallpaper }
    val openedCustom = remember { custom }

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
                .padding(bottom = FlashSpacing.space20),
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

            // Live preview: the field with its day chip and two bubbles. Text is never placed
            // directly on the pattern — every bubble keeps its own opaque surface.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(FlashShapes.radius16))
                    .flashChatWallpaper(wallpaper, custom)
                    .border(
                        FlashDimensions.borderHairline,
                        colors.borderSubtle,
                        RoundedCornerShape(FlashShapes.radius16),
                    )
                    .padding(FlashSpacing.space12),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.backgroundSurface)
                                .border(FlashDimensions.borderHairline, colors.borderSubtle, CircleShape)
                                .padding(horizontal = FlashSpacing.space8, vertical = 2.dp),
                        ) {
                            FlashText(
                                text = "Today",
                                style = typography.metadataEmphasis,
                                color = colors.textTertiary,
                            )
                        }
                    }
                    PreviewBubble(text = "Checksum matches here too.", incoming = true)
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

            val presetRows = FlashChatWallpaperPresets.chunked(3)
            presetRows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 0.dp else FlashSpacing.space8),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    row.forEach { preset ->
                        PresetTile(
                            label = preset.label,
                            wallpaper = preset.wallpaper,
                            custom = custom,
                            selected = preset.wallpaper == wallpaper,
                            onSelect = { onSelect(preset.wallpaper) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // The sixth slot: "Yours", the mock's custom colour tile.
                    if (index == presetRows.lastIndex) {
                        PresetTile(
                            label = "Yours",
                            wallpaper = FlashChatWallpaper.Custom,
                            custom = custom,
                            selected = wallpaper == FlashChatWallpaper.Custom,
                            onSelect = { onSelect(FlashChatWallpaper.Custom) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            FlashText(
                text = "A colour of your own",
                style = typography.metadataEmphasis,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space20, bottom = FlashSpacing.space8),
            )

            val colourRows = FlashChatWallpaperColours.chunked(3)
            colourRows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 0.dp else FlashSpacing.space4),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                ) {
                    row.forEach { colour ->
                        val colourValue = parseFlashWallpaperColour(colour.hex)
                        val isSelected = wallpaper == FlashChatWallpaper.Custom &&
                            custom.equals(colour.hex, ignoreCase = true)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(FlashShapes.radius12))
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onCustom(colour.hex)
                                        onSelect(FlashChatWallpaper.Custom)
                                    },
                                )
                                .padding(horizontal = FlashSpacing.space4, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(colourValue ?: colors.backgroundSurface)
                                    .border(
                                        width = if (isSelected) 2.dp else FlashDimensions.borderHairline,
                                        color = if (isSelected) colors.accentPrimary else colors.borderSubtle,
                                        shape = CircleShape,
                                    ),
                            )
                            FlashText(
                                text = colour.name,
                                style = typography.metadataDefault,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }

            FlashText(
                text = "Bubbles keep their own colour and their own border, so every wallpaper stays readable. A dark wallpaper does not dim the text.",
                style = typography.metadataDefault,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = FlashSpacing.space16),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FlashSpacing.space20),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlashText(
                    text = "Applied · ${chatWallpaperLabel(wallpaper)}",
                    style = typography.metadataDefault,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = "Undo",
                    style = typography.metadataEmphasis,
                    color = colors.accentPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(FlashShapes.radius8))
                        .clickable {
                            onSelect(openedWallpaper)
                            onCustom(openedCustom)
                        }
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                )
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(44.dp)
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
}

@Composable
private fun PresetTile(
    label: String,
    wallpaper: FlashChatWallpaper,
    custom: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .flashChatWallpaper(wallpaper, custom)
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
                text = label,
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
            .padding(horizontal = FlashSpacing.space12, vertical = 6.dp),
    ) {
        FlashText(
            text = text,
            style = FlashTheme.typography.captionDefault,
            color = if (incoming) colors.chatTextIncoming else colors.chatTextOutgoing,
        )
    }
}
