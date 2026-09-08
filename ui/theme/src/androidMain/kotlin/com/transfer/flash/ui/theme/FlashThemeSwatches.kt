package com.transfer.flash.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun FlashThemeSwatches(modifier: Modifier = Modifier) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(
        modifier = modifier
            .background(colors.backgroundApp)
            .verticalScroll(rememberScrollState())
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Text(
            text = "Flash Pulse — Design System",
            style = typography.headingMedium,
            color = colors.textPrimary,
        )
        Text(
            text = "Semantic color tokens",
            style = typography.captionDefault,
            color = colors.textSecondary,
        )

        SwatchRow("Accent", colors.accentPrimary, colors.textOnAccent)
        SwatchRow("App bg", colors.backgroundApp, colors.textPrimary)
        SwatchRow("Chat bg", colors.backgroundChat, colors.textPrimary)
        SwatchRow("Surface", colors.backgroundSurface, colors.textPrimary)
        SwatchRow("Incoming bubble", colors.chatBgIncoming, colors.chatTextIncoming)
        SwatchRow("Outgoing bubble", colors.chatBgOutgoing, colors.chatTextOutgoing)
        SwatchRow("Composer input", colors.composerInputBackground, colors.textPrimary)

        Spacer(modifier = Modifier.height(FlashSpacing.space8))

        Text(
            text = "Typography hierarchy",
            style = typography.headingSmall,
            color = colors.textPrimary,
        )
        Text(text = "Display 24", style = typography.display, color = colors.textPrimary)
        Text(text = "Heading large 20", style = typography.headingLarge, color = colors.textPrimary)
        Text(text = "Body default 16", style = typography.bodyDefault, color = colors.textPrimary)
        Text(text = "Caption 14", style = typography.captionDefault, color = colors.textSecondary)
        Text(text = "Metadata 12", style = typography.metadataDefault, color = colors.textTertiary)
        Text(text = "12:34:56", style = typography.numericDefault, color = colors.textTertiary)

        Spacer(modifier = Modifier.height(FlashSpacing.space8))

        Text(
            text = "Shape samples",
            style = typography.headingSmall,
            color = colors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            ShapeSample("Bubble", FlashShapes.bubbleGrouped, colors.chatBgOutgoing)
            ShapeSample("Composer", FlashShapes.composerInput, colors.composerInputBackground)
            ShapeSample("Chip", FlashShapes.chip, colors.backgroundSurfaceStrong)
        }
    }
}

@Composable
private fun SwatchRow(label: String, background: Color, labelColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(FlashShapes.radius8))
                .background(background)
                .border(FlashDimensions.borderHairline, FlashTheme.colors.borderDefault, RoundedCornerShape(FlashShapes.radius8)),
        )
        Spacer(modifier = Modifier.width(FlashSpacing.space12))
        Text(
            text = label,
            style = FlashTheme.typography.captionDefault,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShapeSample(label: String, shape: androidx.compose.ui.graphics.Shape, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 40.dp)
                .clip(shape)
                .background(color),
        )
        Spacer(modifier = Modifier.height(FlashSpacing.space4))
        Text(
            text = label,
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textTertiary,
        )
    }
}

@Preview(name = "Flash theme — light", showBackground = true, widthDp = 390)
@Composable
private fun FlashThemeSwatchesLightPreview() {
    FlashTheme(darkTheme = false) {
        FlashThemeSwatches()
    }
}

@Preview(name = "Flash theme — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashThemeSwatchesDarkPreview() {
    FlashTheme(darkTheme = true) {
        FlashThemeSwatches()
    }
}

@Preview(name = "Flash theme — large font", showBackground = true, widthDp = 390, fontScale = 1.5f)
@Composable
private fun FlashThemeSwatchesLargeFontPreview() {
    FlashTheme(darkTheme = false) {
        FlashThemeSwatches()
    }
}

/**
 * UI-035 QA: sweeps every authored dark surface/text pair so contrast gaps are visible.
 * Pure black/white backgrounds are forbidden (graphite/void rule) — see design-system.md.
 */
@Preview(name = "UI-035 dark palette sweep", showBackground = true, widthDp = 390)
@Composable
private fun FlashThemeDarkPaletteSweepPreview() {
    val colors = FlashColors.dark()
    Column(
        modifier = Modifier
            .background(colors.backgroundApp)
            .verticalScroll(rememberScrollState())
            .padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        Text("UI-035 dark audit", style = FlashTypography.default().headingMedium, color = colors.textPrimary)
        SwatchRow("backgroundApp", colors.backgroundApp, colors.textPrimary)
        SwatchRow("backgroundChat", colors.backgroundChat, colors.textPrimary)
        SwatchRow("backgroundSurface", colors.backgroundSurface, colors.textPrimary)
        SwatchRow("surfaceSubtle / Strong", colors.backgroundSurfaceSubtle, colors.textPrimary)
        SwatchRow("surfaceStrong", colors.backgroundSurfaceStrong, colors.textPrimary)
        SwatchRow("chatBgIncoming + text", colors.chatBgIncoming, colors.chatTextIncoming)
        SwatchRow("chatBgOutgoing + text", colors.chatBgOutgoing, colors.chatTextOutgoing)
        SwatchRow("composerInput", colors.composerInputBackground, colors.textPrimary)
        SwatchRow("composerSurface", colors.composerSurface, colors.textPrimary)
        SwatchRow("sheetSurface", colors.sheetSurface, colors.textPrimary)
        SwatchRow("accentPrimary + onAccent", colors.accentPrimary, colors.textOnAccent)
        SwatchRow("accentSecondary", colors.accentSecondary, colors.textOnAccent)
        SwatchRow("mediaViewerBackdrop", colors.mediaViewerBackdrop, colors.mediaViewerChromeText)
        SwatchRow("avatarPlaceholder", colors.avatarPlaceholderBackground, colors.avatarPlaceholderText)
    }
}

/** UI-036 QA: accent tokens re-tinted from the system scheme; neutrals stay Pulse-owned. */
@Preview(name = "UI-036 dynamic accent — dark", showBackground = true, widthDp = 390)
@Composable
private fun FlashThemeDynamicAccentDarkPreview() {
    FlashTheme(darkTheme = true, dynamicAccent = true) {
        FlashThemeSwatches()
    }
}

@Preview(name = "UI-036 dynamic accent — light", showBackground = true, widthDp = 390)
@Composable
private fun FlashThemeDynamicAccentLightPreview() {
    FlashTheme(darkTheme = false, dynamicAccent = true) {
        FlashThemeSwatches()
    }
}
