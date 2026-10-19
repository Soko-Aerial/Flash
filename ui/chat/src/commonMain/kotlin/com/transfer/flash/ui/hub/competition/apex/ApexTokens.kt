package com.transfer.flash.ui.hub.competition.apex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashTheme

/**
 * APEX ZENITH — Flash Hub Design Competition Design Tokens.
 *
 * Ground rules:
 * 1. 100% FlashTheme compliance: Colors, typography, spacing and motion come strictly from FlashTheme.
 * 2. Visual Hierarchy: Subtle layered surfaces, hairline borders, and semantic accent glows.
 * 3. Accessibility first: Never color alone — all tones pair with iconography, typography and semantics.
 * 4. Performance budget: All animations respect [FlashTheme.motion.reduceMotion] and [FlashTheme.minimalChrome].
 */

internal object ApexDims {
    val cardRadius: Dp = 18.dp
    val tileRadius: Dp = 14.dp
    val badgeRadius: Dp = 8.dp
    val pillRadius: Dp = 999.dp
    val sheetRadius: Dp = 24.dp

    val touchTargetMin: Dp = 48.dp
    val railWidth: Dp = 240.dp
    val maxContentWidth: Dp = 760.dp
    val compactFrameWidth: Dp = 412.dp
    val desktopFrameWidth: Dp = 1180.dp
    val drawerWidth: Dp = 380.dp
}

// `public` because the sibling model classes in ApexModels.kt are public and expose this type in
// their properties (Kotlin forbids a public declaration exposing an internal one). A one-word
// visibility fix by the NOVA session 2026-10-18 — it blocked :ui:chat:compileKotlinJvm for both
// competition entries; see logs/progress.md.
public enum class ApexTone {
    ONLINE,
    STANDBY,
    BUSY,
    WARNING,
    DANGER,
    MUTED,
    SEALED,
    SPECIAL,
}

@Immutable
internal data class ApexToneColors(
    val wash: Color,
    val border: Color,
    val text: Color,
    val indicator: Color,
    val glow: Color,
)

@Composable
@ReadOnlyComposable
internal fun apexTone(tone: ApexTone): ApexToneColors {
    val c = FlashTheme.colors
    return when (tone) {
        ApexTone.ONLINE -> ApexToneColors(
            wash = c.statusOnline.copy(alpha = 0.14f),
            border = c.statusOnline.copy(alpha = 0.35f),
            text = c.textSuccess,
            indicator = c.statusOnline,
            glow = c.statusOnline.copy(alpha = 0.25f),
        )
        ApexTone.STANDBY -> ApexToneColors(
            wash = c.accentSecondary.copy(alpha = 0.12f),
            border = c.accentSecondary.copy(alpha = 0.30f),
            text = c.accentSecondary,
            indicator = c.accentSecondary,
            glow = c.accentSecondary.copy(alpha = 0.20f),
        )
        ApexTone.BUSY -> ApexToneColors(
            wash = c.accentPrimary.copy(alpha = 0.15f),
            border = c.accentPrimary.copy(alpha = 0.38f),
            text = c.accentPrimary,
            indicator = c.accentPrimary,
            glow = c.accentPrimary.copy(alpha = 0.28f),
        )
        ApexTone.WARNING -> ApexToneColors(
            wash = c.statusTransfer.copy(alpha = 0.16f),
            border = c.statusTransfer.copy(alpha = 0.38f),
            text = c.statusTransfer,
            indicator = c.statusTransfer,
            glow = c.statusTransfer.copy(alpha = 0.24f),
        )
        ApexTone.DANGER -> ApexToneColors(
            wash = c.textError.copy(alpha = 0.15f),
            border = c.textError.copy(alpha = 0.38f),
            text = c.textError,
            indicator = c.textError,
            glow = c.textError.copy(alpha = 0.25f),
        )
        ApexTone.MUTED -> ApexToneColors(
            wash = c.backgroundSurfaceStrong.copy(alpha = 0.60f),
            border = c.borderSubtle,
            text = c.textTertiary,
            indicator = c.statusOffline,
            glow = Color.Transparent,
        )
        ApexTone.SEALED -> ApexToneColors(
            wash = c.accentPrimary.copy(alpha = 0.18f),
            border = c.accentPrimary.copy(alpha = 0.45f),
            text = c.textLink,
            indicator = c.accentPrimary,
            glow = c.accentPrimary.copy(alpha = 0.32f),
        )
        ApexTone.SPECIAL -> ApexToneColors(
            wash = c.accentSecondary.copy(alpha = 0.18f),
            border = c.accentSecondary.copy(alpha = 0.42f),
            text = c.accentSecondary,
            indicator = c.accentSecondary,
            glow = c.accentSecondary.copy(alpha = 0.30f),
        )
    }
}

internal fun apexMono(style: TextStyle): TextStyle = style.copy(fontFamily = FontFamily.Monospace)

internal fun apexFormatAgo(nowMs: Long, thenMs: Long): String {
    val diff = (nowMs - thenMs).coerceAtLeast(0L)
    val min = 60_000L
    val hour = 60L * min
    val day = 24L * hour
    return when {
        diff < min -> "just now"
        diff < hour -> "${diff / min}m ago"
        diff < day -> "${diff / hour}h ago"
        else -> "${diff / day}d ago"
    }
}

internal fun apexFormatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${(kb * 10).toLong() / 10.0} KB"
    val mb = kb / 1024.0
    if (mb < 1024) return "${(mb * 10).toLong() / 10.0} MB"
    val gb = mb / 1024.0
    return "${(gb * 100).toLong() / 100.0} GB"
}

internal fun apexFormatHex(value: String): String {
    return value.chunked(4).joinToString(" ")
}
