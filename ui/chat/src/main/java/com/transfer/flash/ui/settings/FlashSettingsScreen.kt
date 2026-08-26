package com.transfer.flash.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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

/**
 * P5 Settings tab (UI-049, docs/ui/settings-page.md): five grouped sections of Flash-owned
 * rows — no Material ListItems/switches. Demo model today; C1.4 DataStore substitutes at wiring.
 */
enum class FlashThemeMode { System, Light, Dark }

data class FlashSettingsModel(
    val displayName: String = "Flash device",
    val themeMode: FlashThemeMode = FlashThemeMode.System,
    val dynamicAccent: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val backgroundTransfers: Boolean = false,
    val trustedPeerCount: Int = 0,
    val saveLocationLabel: String? = null,
    val appVersion: String = "dev",
    val protocolVersion: String = "FLASH_XFER/1",
    val deviceIdShort: String = "00000000",
)

/** Pure helpers backing the settings page (JVM-testable). */
object FlashSettingsMath {

    fun themeModeLabel(mode: FlashThemeMode): String = when (mode) {
        FlashThemeMode.System -> "System"
        FlashThemeMode.Light -> "Light"
        FlashThemeMode.Dark -> "Dark"
    }

    /**
     * Resolves the Appearance selection against the OS setting. The host feeds the result to
     * both `FlashTheme(darkTheme = …)` and `FlashMaterialTheme(darkTheme = …)`, which is what
     * makes the Light/Dark segments actually repaint the app.
     */
    fun resolveDarkTheme(mode: FlashThemeMode, systemDark: Boolean): Boolean = when (mode) {
        FlashThemeMode.System -> systemDark
        FlashThemeMode.Light -> false
        FlashThemeMode.Dark -> true
    }

    fun trustedPeersSubtitle(count: Int): String = when {
        count <= 0 -> "No verified devices yet"
        count == 1 -> "1 device verified"
        else -> "$count devices verified"
    }
}

@Composable
fun FlashSettingsScreen(
    model: FlashSettingsModel,
    onThemeModeSelected: (FlashThemeMode) -> Unit,
    onDynamicAccentChanged: (Boolean) -> Unit,
    onHapticsChanged: (Boolean) -> Unit,
    onBackgroundTransfersChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hanging shell bar occupies; content scrolls under it (UI-046). */
    bottomInset: Dp = 0.dp,
    onEditDisplayName: () -> Unit = {},
    onOpenEncryption: () -> Unit = {},
    onOpenTrustedPeers: () -> Unit = {},
    onPickSaveLocation: () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(
            start = FlashSpacing.space16,
            end = FlashSpacing.space16,
            top = FlashSpacing.space16,
            bottom = FlashSpacing.space16 + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        item(key = "header") {
            StaggerIn(0) {
                FlashText(
                    text = "Settings",
                    style = FlashTheme.typography.headingMedium,
                    color = FlashTheme.colors.textPrimary,
                )
            }
        }
        item(key = "identity-label") { StaggerIn(1) { SectionLabel("IDENTITY") } }
        item(key = "identity") {
            StaggerIn(2) { IdentityRow(model.displayName, onEditDisplayName) }
        }

        item(key = "appearance-label") { StaggerIn(3) { SectionLabel("APPEARANCE") } }
        item(key = "theme-mode") {
            StaggerIn(4) {
                SettingsCard {
                    ThemeModeSegmented(
                        selected = model.themeMode,
                        onSelected = onThemeModeSelected,
                    )
                }
            }
        }
        item(key = "dynamic-accent") {
            StaggerIn(5) {
                SwitchRow(
                    title = "Dynamic accent",
                    subtitle = "Tint Flash with your wallpaper colors where supported",
                    checked = model.dynamicAccent,
                    onCheckedChange = onDynamicAccentChanged,
                )
            }
        }
        item(key = "haptics") {
            StaggerIn(6) {
                SwitchRow(
                    title = "Haptics",
                    subtitle = "Subtle vibration feedback on actions",
                    checked = model.hapticsEnabled,
                    onCheckedChange = onHapticsChanged,
                )
            }
        }

        item(key = "security-label") { StaggerIn(7) { SectionLabel("SECURITY") } }
        item(key = "encryption") {
            StaggerIn(8) {
                ValueRow(
                    iconSpec = FlashIcons.Encryption,
                    title = "Encryption",
                    subtitle = "How Flash protects your transfers",
                    value = null,
                    onClick = onOpenEncryption,
                )
            }
        }
        item(key = "trusted-peers") {
            StaggerIn(9) {
                ValueRow(
                    iconSpec = FlashIcons.Verified,
                    title = "Trusted peers",
                    subtitle = FlashSettingsMath.trustedPeersSubtitle(model.trustedPeerCount),
                    value = null,
                    onClick = onOpenTrustedPeers,
                )
            }
        }

        item(key = "data-label") { StaggerIn(10) { SectionLabel("DATA") } }
        item(key = "save-location") {
            StaggerIn(11) {
                ValueRow(
                    iconSpec = FlashIcons.Download,
                    title = "Save location",
                    subtitle = model.saveLocationLabel ?: "Choose where received files go",
                    value = null,
                    onClick = onPickSaveLocation,
                )
            }
        }
        item(key = "background") {
            StaggerIn(12) {
                SwitchRow(
                    title = "Background transfers",
                    subtitle = "Keep sending when you leave the app",
                    checked = model.backgroundTransfers,
                    onCheckedChange = onBackgroundTransfersChanged,
                )
            }
        }

        item(key = "about-label") { StaggerIn(13) { SectionLabel("ABOUT") } }
        item(key = "about") { StaggerIn(14) { AboutCard(model) } }
    }
}

/**
 * UI-050 entrance: alpha + a short rise driven by the shared stagger progress, read inside
 * `graphicsLayer` so the reveal is a render pass and never recomposes the row.
 */
@Composable
private fun StaggerIn(index: Int, content: @Composable () -> Unit) {
    val progress = FlashTheme.motion.rememberStaggerProgress(index = index, key = Unit)
    Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * StaggerRise.toPx()
        },
    ) { content() }
}

private val StaggerRise = 12.dp

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(FlashTheme.colors.backgroundSurface)
            .padding(FlashSpacing.space12),
    ) { content() }
}

@Composable
private fun IdentityRow(name: String, onEdit: () -> Unit) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = "Edit display name",
                onClick = onEdit,
            )
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = "Display name $name, edit button"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(FlashDimensions.avatarLg)
                .clip(CircleShape)
                .background(colors.accentPrimary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = name.take(1).uppercase().ifBlank { "?" },
                style = FlashTheme.typography.headingSmall,
                color = colors.accentPrimary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(text = name, style = FlashTheme.typography.bodyEmphasis, color = colors.textPrimary)
            FlashText(
                text = "Visible to nearby devices",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        FlashIcon(
            icon = FlashIcons.Edit,
            tint = colors.textSecondary,
            size = FlashDimensions.iconSm,
            contentDescription = null,
        )
    }
}

@Composable
private fun ThemeModeSegmented(
    selected: FlashThemeMode,
    onSelected: (FlashThemeMode) -> Unit,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val modes = FlashThemeMode.entries
    val selectedIndex = modes.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(SegmentTrackHeight)
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.backgroundSurfaceSubtle)
            .selectableGroup(),
    ) {
        val segmentWidth = maxWidth / modes.size
        // Read the animated Dp inside offset { } so the slide is a placement pass only —
        // no recomposition of the three labels on every frame.
        val indicatorOffset = animateDpAsState(
            targetValue = FlashSpacing.space4 + segmentWidth * selectedIndex,
            animationSpec = motion.springSnappySpec(),
            label = "flashThemeSegment",
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        indicatorOffset.value.roundToPx(),
                        FlashSpacing.space4.roundToPx(),
                    )
                }
                .width(segmentWidth - FlashSpacing.space8)
                .height(SegmentTrackHeight - FlashSpacing.space8)
                .clip(FlashShapes.bubbleGrouped)
                .background(colors.accentPrimary),
        )
        Row(Modifier.fillMaxSize()) {
            modes.forEach { mode ->
                val label = FlashSettingsMath.themeModeLabel(mode)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = mode == selected,
                            role = Role.RadioButton,
                            onClick = {
                                if (mode != selected) {
                                    haptics(FlashHaptic.Tick)
                                    onSelected(mode)
                                }
                            },
                        )
                        .semantics { contentDescription = "$label theme" },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = label,
                        style = FlashTheme.typography.captionEmphasis,
                        color = if (mode == selected) colors.textOnAccent else colors.textSecondary,
                    )
                }
            }
        }
    }
}

private val SegmentTrackHeight = 40.dp

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = FlashTheme.colors
    val haptics = rememberFlashHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            // Role.Switch + toggleable gives TalkBack the real on/off state and the
            // "double-tap to toggle" affordance; a clickable + contentDescription pair
            // announced the state only at first read and never on change.
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { next ->
                    haptics(FlashHaptic.Tick)
                    onCheckedChange(next)
                },
            )
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                stateDescription = if (checked) "on" else "off"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FlashText(text = title, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        FlashSwitch(checked = checked)
    }
}

/** Flash-drawn switch: track + spring thumb, no Material Switch. */
@Composable
private fun FlashSwitch(checked: Boolean) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val thumbOffset = animateDpAsState(
        targetValue = if (checked) SwitchThumbTravel else FlashSpacing.space2,
        animationSpec = motion.springSnappySpec(),
        label = "flashSwitchThumb",
    )
    val trackTint by animateColorAsState(
        targetValue = if (checked) colors.accentPrimary else colors.borderStrong,
        animationSpec = motion.tweenFastSpec(),
        label = "flashSwitchTrack",
    )
    val thumbTint by animateColorAsState(
        targetValue = if (checked) colors.textOnAccent else colors.backgroundSurface,
        animationSpec = motion.tweenFastSpec(),
        label = "flashSwitchThumbTint",
    )
    Box(
        Modifier
            .width(SwitchTrackWidth)
            .height(SwitchTrackHeight)
            .clip(FlashShapes.bubbleGrouped)
            .background(trackTint),
    ) {
        Box(
            Modifier
                .offset {
                    IntOffset(thumbOffset.value.roundToPx(), FlashSpacing.space2.roundToPx())
                }
                .size(SwitchTrackHeight - FlashSpacing.space4)
                .clip(CircleShape)
                .background(thumbTint),
        )
    }
}

private val SwitchTrackWidth = 44.dp
private val SwitchTrackHeight = 24.dp
private val SwitchThumbTravel = SwitchTrackWidth - SwitchTrackHeight + FlashSpacing.space2

@Composable
private fun ValueRow(
    iconSpec: com.transfer.flash.ui.icons.FlashIconSpec,
    title: String,
    subtitle: String,
    value: String?,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(title, subtitle, value).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashIcon(
            icon = iconSpec,
            tint = colors.textSecondary,
            size = FlashDimensions.iconMd,
            contentDescription = null,
        )
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(text = title, style = FlashTheme.typography.bodyDefault, color = colors.textPrimary)
            FlashText(
                text = subtitle,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textTertiary,
            )
        }
        if (value != null) {
            FlashText(
                text = value,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun AboutCard(model: FlashSettingsModel) {
    val colors = FlashTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .padding(FlashSpacing.space12),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        AboutLine("Version", model.appVersion)
        AboutLine("Protocol", model.protocolVersion)
        AboutLine("Device id", model.deviceIdShort.take(8))
        FlashText(
            text = "Flash keeps your files on your network — no cloud, no accounts.",
            style = FlashTheme.typography.metadataDefault,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    val colors = FlashTheme.colors
    Row {
        FlashText(
            text = label,
            style = FlashTheme.typography.captionDefault,
            color = colors.textTertiary,
            modifier = Modifier.width(FlashSpacing.space40 * 2),
        )
        FlashText(
            text = value,
            style = FlashTheme.typography.captionDefault,
            color = colors.textSecondary,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    FlashText(
        text = text,
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textTertiary,
        modifier = Modifier.padding(top = FlashSpacing.space8),
    )
}
