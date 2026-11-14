package com.transfer.flash.ui.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlin.math.absoluteValue

@Composable
fun FlashAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = FlashDimensions.avatarXs,
    seed: String = initials,
    background: Color? = null,
    foreground: Color? = null,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography.metadataEmphasis
    val paletteIndex = seed.hashCode().absoluteValue % colors.avatarPaletteBackgrounds.size
    val resolvedBackground = background ?: colors.avatarPaletteBackgrounds[paletteIndex]
    val resolvedForeground = foreground ?: colors.avatarPaletteForegrounds[paletteIndex]
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val badgeTextScale = fontScale.coerceAtMost(1.2f) / fontScale
    val fontSize = flashAvatarInitialFontSize(size) * badgeTextScale
    val initialStyle = remember(typography, fontSize) {
        typography.copy(
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.Bold,
        )
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(resolvedBackground),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = initials.take(2).uppercase(),
            style = initialStyle,
            color = resolvedForeground,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun flashAvatarInitialFontSize(size: Dp): TextUnit =
    (size.value * AVATAR_INITIAL_FONT_RATIO).coerceAtLeast(MIN_AVATAR_FONT_SIZE_SP).sp

private const val AVATAR_INITIAL_FONT_RATIO = 0.38f
private const val MIN_AVATAR_FONT_SIZE_SP = 6f

fun avatarSeed(name: String): String = name

/**
 * Seeded background/foreground pair from the shared avatar palette — same hashing as
 * [FlashAvatar], exposed for composite avatars (UI-028 group collage tiles).
 */
@Composable
fun flashAvatarColorsFor(seed: String): Pair<Color, Color> {
    val colors = FlashTheme.colors
    val paletteIndex = seed.hashCode().absoluteValue % colors.avatarPaletteBackgrounds.size
    return colors.avatarPaletteBackgrounds[paletteIndex] to colors.avatarPaletteForegrounds[paletteIndex]
}
