package com.transfer.flash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class FlashColors(
    val accentPrimary: Color,
    val accentSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textOnAccent: Color,
    val textLink: Color,
    val textError: Color,
    val textSuccess: Color,
    val backgroundApp: Color,
    val backgroundChat: Color,
    val backgroundSurface: Color,
    val backgroundSurfaceSubtle: Color,
    val backgroundSurfaceStrong: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderStrong: Color,
    val chatBgIncoming: Color,
    val chatBgOutgoing: Color,
    val chatBgAttachmentIncoming: Color,
    val chatBgAttachmentOutgoing: Color,
    val chatBorderIncoming: Color,
    val chatTextIncoming: Color,
    val chatTextOutgoing: Color,
    val chatTextTimestamp: Color,
    val chatTextTimestampOutgoing: Color,
    val chatTextUsername: Color,
    val chatTextSystem: Color,
    val composerInputBackground: Color,
    val composerSurface: Color,
    val sheetSurface: Color,
    val scrim: Color,
    /** UI-018: full-screen media viewer backdrop — deliberately near-black in both themes. */
    val mediaViewerBackdrop: Color,
    /** UI-018: media viewer chrome text over the always-dark backdrop. */
    val mediaViewerChromeText: Color,
    val avatarPlaceholderBackground: Color,
    val avatarPlaceholderText: Color,
    val avatarPaletteBackgrounds: List<Color>,
    val avatarPaletteForegrounds: List<Color>,
    val statusOnline: Color,
    val statusOffline: Color,
    val statusTransfer: Color,
) {
    /**
     * UI-036: re-tints only accent tokens from the dynamic scheme; every other slot stays
     * Flash-owned per ADR-005. [secondary] defaults to [accent] so single-color callers
     * keep working (accentSecondary follows the primary hue).
     */
    fun withAccent(accent: Color, secondary: Color = accent): FlashColors = copy(
        accentPrimary = accent,
        accentSecondary = secondary,
        textLink = accent,
    )

    companion object {
        fun light(): FlashColors = FlashColors(
            accentPrimary = FlashPalette.pulse500,
            accentSecondary = FlashPalette.spark500,
            textPrimary = FlashPalette.graphite900,
            textSecondary = FlashPalette.graphite700,
            textTertiary = FlashPalette.graphite500,
            textOnAccent = FlashPalette.white,
            textLink = FlashPalette.pulse600,
            textError = FlashPalette.error500,
            textSuccess = FlashPalette.success500,
            backgroundApp = FlashPalette.graphite50,
            backgroundChat = FlashPalette.graphite100,
            backgroundSurface = FlashPalette.white,
            backgroundSurfaceSubtle = FlashPalette.graphite50,
            backgroundSurfaceStrong = FlashPalette.graphite150,
            borderSubtle = FlashPalette.graphite100,
            borderDefault = FlashPalette.graphite150,
            borderStrong = FlashPalette.graphite300,
            chatBgIncoming = FlashPalette.white,
            chatBgOutgoing = FlashPalette.pulse100,
            chatBgAttachmentIncoming = FlashPalette.graphite150,
            chatBgAttachmentOutgoing = FlashPalette.pulse150,
            chatBorderIncoming = FlashPalette.graphite150,
            chatTextIncoming = FlashPalette.graphite900,
            chatTextOutgoing = FlashPalette.pulse900,
            chatTextTimestamp = FlashPalette.graphite500,
            chatTextTimestampOutgoing = FlashPalette.pulse700,
            chatTextUsername = FlashPalette.graphite700,
            chatTextSystem = FlashPalette.graphite700,
            composerInputBackground = FlashPalette.graphite100,
            composerSurface = FlashPalette.white,
            sheetSurface = FlashPalette.white,
            scrim = FlashPalette.scrimLight,
            mediaViewerBackdrop = FlashPalette.mediaBackdropLight,
            mediaViewerChromeText = FlashPalette.white,
            avatarPlaceholderBackground = FlashPalette.graphite150,
            avatarPlaceholderText = FlashPalette.graphite500,
            avatarPaletteBackgrounds = listOf(
                FlashPalette.pulse150,
                FlashPalette.cyan150,
                FlashPalette.violet150,
                FlashPalette.spark150,
                FlashPalette.success150,
            ),
            avatarPaletteForegrounds = listOf(
                FlashPalette.pulse900,
                FlashPalette.cyan900,
                FlashPalette.violet900,
                FlashPalette.spark900,
                FlashPalette.success900,
            ),
            statusOnline = FlashPalette.success500,
            statusOffline = FlashPalette.graphite500,
            statusTransfer = FlashPalette.spark500,
        )

        fun dark(): FlashColors = FlashColors(
            accentPrimary = FlashPalette.pulse400,
            accentSecondary = FlashPalette.spark400,
            textPrimary = FlashPalette.graphite50,
            textSecondary = FlashPalette.graphite300,
            textTertiary = FlashPalette.graphite500,
            // UI-035 audit fix: was white — only 2.5:1 on pulse400; pulse900 is ~5.9:1.
            textOnAccent = FlashPalette.pulse900,
            textLink = FlashPalette.pulse300,
            textError = FlashPalette.error400,
            textSuccess = FlashPalette.success400,
            backgroundApp = FlashPalette.void,
            backgroundChat = FlashPalette.surface0,
            backgroundSurface = FlashPalette.surface1,
            backgroundSurfaceSubtle = FlashPalette.surface0,
            backgroundSurfaceStrong = FlashPalette.surface2,
            borderSubtle = FlashPalette.surface2,
            borderDefault = FlashPalette.surface3,
            borderStrong = FlashPalette.graphite700,
            chatBgIncoming = FlashPalette.surface2,
            chatBgOutgoing = FlashPalette.pulse800,
            chatBgAttachmentIncoming = FlashPalette.surface3,
            chatBgAttachmentOutgoing = FlashPalette.pulse700,
            chatBorderIncoming = FlashPalette.surface3,
            chatTextIncoming = FlashPalette.graphite50,
            chatTextOutgoing = FlashPalette.pulse100,
            chatTextTimestamp = FlashPalette.graphite500,
            chatTextTimestampOutgoing = FlashPalette.pulse300,
            chatTextUsername = FlashPalette.graphite300,
            chatTextSystem = FlashPalette.graphite300,
            composerInputBackground = FlashPalette.surface2,
            composerSurface = FlashPalette.surface1,
            sheetSurface = FlashPalette.surface1,
            scrim = FlashPalette.scrimDark,
            mediaViewerBackdrop = FlashPalette.mediaBackdropDark,
            mediaViewerChromeText = FlashPalette.white,
            // UI-035 audit fix: graphite500 was 2.8:1 on surface3; graphite300 is ~5.8:1.
            avatarPlaceholderBackground = FlashPalette.surface3,
            avatarPlaceholderText = FlashPalette.graphite300,
            avatarPaletteBackgrounds = listOf(
                FlashPalette.pulse700,
                FlashPalette.cyan700,
                FlashPalette.violet700,
                FlashPalette.spark700,
                FlashPalette.success700,
            ),
            avatarPaletteForegrounds = listOf(
                FlashPalette.pulse100,
                FlashPalette.cyan100,
                FlashPalette.violet100,
                FlashPalette.spark100,
                FlashPalette.success100,
            ),
            statusOnline = FlashPalette.success400,
            statusOffline = FlashPalette.graphite500,
            statusTransfer = FlashPalette.spark400,
        )
    }
}

/** Raw palette — composables must use [FlashColors] semantic tokens. */
internal object FlashPalette {
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)
    val void = Color(0xFF07080A)

    val pulse50 = Color(0xFFE8FAF7)
    val pulse100 = Color(0xFFC8F0EA)
    val pulse150 = Color(0xFF9FE0D6)
    val pulse300 = Color(0xFF4ECCBE)
    val pulse400 = Color(0xFF1FB8A6)
    val pulse500 = Color(0xFF0D9488)
    val pulse600 = Color(0xFF0A7A70)
    val pulse700 = Color(0xFF085F58)
    val pulse800 = Color(0xFF0F3D38)
    val pulse900 = Color(0xFF042F2B)

    val spark100 = Color(0xFFFFF0D6)
    val spark150 = Color(0xFFFFE0A8)
    val spark400 = Color(0xFFFFB84D)
    val spark500 = Color(0xFFE8950A)
    val spark700 = Color(0xFF9A6200)
    val spark900 = Color(0xFF4A3000)

    val cyan100 = Color(0xFFD4F5F8)
    val cyan150 = Color(0xFFA8E8EF)
    val cyan700 = Color(0xFF006970)
    val cyan900 = Color(0xFF002124)

    val violet100 = Color(0xFFEDE8FF)
    val violet150 = Color(0xFFD4CBFF)
    val violet700 = Color(0xFF553BD8)
    val violet900 = Color(0xFF1A114D)

    val success100 = Color(0xFFD4F5E0)
    val success150 = Color(0xFFA8E8C0)
    val success400 = Color(0xFF4ADE80)
    val success500 = Color(0xFF1A9B52)
    val success700 = Color(0xFF0F6B38)
    val success900 = Color(0xFF002213)

    val error400 = Color(0xFFF87171)
    val error500 = Color(0xFFD64545)

    val graphite50 = Color(0xFFF5F6F8)
    val graphite100 = Color(0xFFEBEDF2)
    val graphite150 = Color(0xFFD8DCE4)
    val graphite300 = Color(0xFFA8B0C0)
    val graphite500 = Color(0xFF6E778A)
    val graphite700 = Color(0xFF454C5C)
    val graphite900 = Color(0xFF171A22)

    val surface0 = Color(0xFF101218)
    val surface1 = Color(0xFF181C26)
    val surface2 = Color(0xFF222836)
    val surface3 = Color(0xFF2C3244)

    val scrimLight = Color(0x66000000)
    val scrimDark = Color(0x99000000)
    val mediaBackdropLight = Color(0xFF0A0C0E)
    val mediaBackdropDark = Color(0xFF050607)
}
