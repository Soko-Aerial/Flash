package com.transfer.flash.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/**
 * Material 3 fallback color schemes mapped from Flash Pulse teal/graphite tokens so Material
 * widgets (AlertDialog, OutlinedTextField, ProgressIndicator, text selection) never leak
 * Material template purple when dynamic color is disabled or unavailable (e.g. Desktop).
 */
internal fun flashMaterialColorScheme(darkTheme: Boolean): ColorScheme =
    if (darkTheme) DarkMaterialColorScheme else LightMaterialColorScheme

private val LightMaterialColorScheme: ColorScheme by lazy {
    val light = FlashColors.light()
    lightColorScheme(
        primary = light.accentFill,
        onPrimary = light.onAccentFill,
        primaryContainer = light.chatBgOutgoing,
        onPrimaryContainer = light.chatTextOutgoing,
        inversePrimary = FlashPalette.pulse400,
        secondary = light.textSecondary,
        onSecondary = light.backgroundSurface,
        secondaryContainer = light.backgroundSurfaceStrong,
        onSecondaryContainer = light.textPrimary,
        tertiary = FlashPalette.spark700,
        onTertiary = FlashPalette.white,
        tertiaryContainer = FlashPalette.spark100,
        onTertiaryContainer = FlashPalette.spark900,
        background = light.backgroundApp,
        onBackground = light.textPrimary,
        surface = light.backgroundSurface,
        onSurface = light.textPrimary,
        surfaceVariant = light.backgroundSurfaceStrong,
        onSurfaceVariant = light.textSecondary,
        surfaceTint = light.accentFill,
        inverseSurface = FlashPalette.graphite900,
        inverseOnSurface = FlashPalette.graphite50,
        error = light.textError,
        onError = FlashPalette.white,
        errorContainer = FlashPalette.errorContainerLight,
        onErrorContainer = light.textError,
        outline = FlashPalette.graphite500,
        outlineVariant = light.borderDefault,
        scrim = light.scrim,
        surfaceBright = light.backgroundSurface,
        surfaceDim = light.backgroundSurfaceStrong,
        surfaceContainerLowest = light.backgroundSurface,
        surfaceContainerLow = light.backgroundSurfaceSubtle,
        surfaceContainer = light.backgroundSurface,
        surfaceContainerHigh = light.sheetSurface,
        surfaceContainerHighest = light.backgroundSurfaceStrong,
    )
}

private val DarkMaterialColorScheme: ColorScheme by lazy {
    val dark = FlashColors.dark()
    darkColorScheme(
        primary = dark.accentFill,
        onPrimary = dark.onAccentFill,
        primaryContainer = dark.chatBgOutgoing,
        onPrimaryContainer = dark.chatTextOutgoing,
        inversePrimary = FlashPalette.pulse600,
        secondary = dark.textSecondary,
        onSecondary = dark.backgroundSurface,
        secondaryContainer = dark.backgroundSurfaceStrong,
        onSecondaryContainer = dark.textPrimary,
        tertiary = dark.accentSecondary,
        onTertiary = FlashPalette.spark900,
        tertiaryContainer = FlashPalette.spark700,
        onTertiaryContainer = FlashPalette.spark100,
        background = dark.backgroundApp,
        onBackground = dark.textPrimary,
        surface = dark.backgroundSurface,
        onSurface = dark.textPrimary,
        surfaceVariant = dark.backgroundSurfaceStrong,
        onSurfaceVariant = dark.textSecondary,
        surfaceTint = dark.accentFill,
        inverseSurface = FlashPalette.graphite100,
        inverseOnSurface = FlashPalette.graphite900,
        error = dark.textError,
        onError = FlashPalette.graphite900,
        errorContainer = FlashPalette.errorContainerDark,
        onErrorContainer = FlashPalette.onErrorContainerDark,
        outline = FlashPalette.graphite500,
        outlineVariant = dark.borderDefault,
        scrim = dark.scrim,
        surfaceBright = FlashPalette.surface3,
        surfaceDim = dark.backgroundApp,
        surfaceContainerLowest = dark.backgroundApp,
        surfaceContainerLow = dark.backgroundSurfaceSubtle,
        surfaceContainer = dark.backgroundSurface,
        surfaceContainerHigh = dark.backgroundSurfaceStrong,
        surfaceContainerHighest = FlashPalette.surface3,
    )
}
