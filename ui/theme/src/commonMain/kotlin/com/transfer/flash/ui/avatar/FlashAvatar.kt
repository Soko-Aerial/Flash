package com.transfer.flash.ui.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.transfer.flash.ui.theme.FlashDimensions
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
    val paletteIndex = seed.hashCode().absoluteValue % colors.avatarPaletteBackgrounds.size
    val resolvedBackground = background ?: colors.avatarPaletteBackgrounds[paletteIndex]
    val resolvedForeground = foreground ?: colors.avatarPaletteForegrounds[paletteIndex]

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(resolvedBackground),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials.take(2).uppercase(),
            style = FlashTheme.typography.metadataEmphasis,
            color = resolvedForeground,
            fontWeight = FontWeight.Bold,
        )
    }
}

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
