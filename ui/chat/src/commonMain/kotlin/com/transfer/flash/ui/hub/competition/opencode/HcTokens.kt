package com.transfer.flash.ui.hub.competition.opencode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **Flash Hub — Command Deck (competition entry: `opencode`).**
 *
 * Design tokens for the isolated competition package. Nothing here is on the production path: the package only reads
 * [FlashTheme] and the public `:core:messaging` read models, so it can be previewed side by side with the shipped Hub
 * tab (UI-058) without touching it.
 *
 * Three rules the whole entry follows:
 *
 * 1. **Flash owns the colour.** Every surface comes from [FlashColors] tokens; the only raw values are alpha
 *    multipliers used to build washes from an existing token. Dark and light come from `FlashColors.dark()` /
 *    `FlashColors.light()` and nothing here is theme-conditional.
 * 2. **Meaning is never colour alone.** Every tone carries an icon, a word, or both; [HcToneColors] exists so callers
 *    reach for the label, not just the hue.
 * 3. **Motion is optional.** Anything that moves asks [FlashTheme.motion] first, so the Judge's control bar can flip
 *    "Reduced motion" and the entire deck stops animating (including the tier floor from `FlashPerformanceMode`).
 */
internal object HcDims {
    /** Cards that group a section. */
    val cardRadius = 20.dp

    /** Smaller tiles inside a card grid. */
    val tileRadius = 16.dp

    /** The rail of the desktop split-pane. */
    val railWidth = 232.dp

    /** Widest a single reading column grows before it centres. */
    val contentMaxWidth = 760.dp

    /** A framed "device" in the showcase harness. */
    val compactFrameWidth = 412.dp
    val desktopFrameWidth = 1180.dp
}

/**
 * Semantic tone of a piece of UI. Kept as a small enum rather than a colour so a tone can be asserted in a preview and
 * reused across a badge, a ring, a banner and a bar without the four drifting apart.
 */
internal enum class HcTone { NEUTRAL, ACCENT, SUCCESS, WARNING, DANGER, MUTED }

internal data class HcToneColors(
    /** Wash behind a filled chip/banner. */
    val soft: Color,
    /** Hairline outline of that wash. */
    val edge: Color,
    /** Text and icon on the wash. */
    val on: Color,
    /** Saturated colour for dots, gauges and bars. */
    val strong: Color,
)

@Composable
@ReadOnlyComposable
internal fun hcTone(tone: HcTone): HcToneColors {
    val c = FlashTheme.colors
    return when (tone) {
        HcTone.NEUTRAL -> HcToneColors(c.backgroundSurfaceStrong, c.borderDefault, c.textPrimary, c.textSecondary)
        HcTone.ACCENT -> HcToneColors(c.accentPrimary.copy(alpha = 0.14f), c.accentPrimary.copy(alpha = 0.34f), c.accentPrimary, c.accentPrimary)
        HcTone.SUCCESS -> HcToneColors(c.statusOnline.copy(alpha = 0.16f), c.statusOnline.copy(alpha = 0.36f), c.textSuccess, c.statusOnline)
        HcTone.WARNING -> HcToneColors(c.statusTransfer.copy(alpha = 0.18f), c.statusTransfer.copy(alpha = 0.38f), c.statusTransfer, c.statusTransfer)
        HcTone.DANGER -> HcToneColors(c.textError.copy(alpha = 0.16f), c.textError.copy(alpha = 0.38f), c.textError, c.textError)
        HcTone.MUTED -> HcToneColors(c.backgroundSurfaceSubtle, c.borderSubtle, c.textTertiary, c.statusOffline)
    }
}

/** Tabular monospace style for fingerprints, serials and hex — so two similar hashes do not look alike. */
internal fun hcMono(style: TextStyle): TextStyle = style.copy(fontFamily = FontFamily.Monospace)

// ---------------------------------------------------------------------------------------------------------------
// Formatting helpers (common-source only: no java.time, no locale)
// ---------------------------------------------------------------------------------------------------------------

private const val MIN_MS = 60_000L
private const val HOUR_MS = 60L * MIN_MS
private const val DAY_MS = 24L * HOUR_MS

internal const val HC_NOW_MS: Long = 1_800_003_600_000L

/** "3 min ago", "5 h ago", "12 d ago", "just now". */
internal fun hcAgo(nowMs: Long, thenMs: Long): String {
    val d = (nowMs - thenMs).coerceAtLeast(0L)
    return when {
        d < MIN_MS -> "just now"
        d < HOUR_MS -> "${d / MIN_MS} min ago"
        d < DAY_MS -> "${d / HOUR_MS} h ago"
        else -> "${d / DAY_MS} d ago"
    }
}

/** "in 29 d", "in 6 h", "expired 2 d ago". Used for lease and certificate countdowns. */
internal fun hcUntil(nowMs: Long, thenMs: Long): String {
    val d = thenMs - nowMs
    if (d <= 0L) return "expired ${hcAgo(nowMs, thenMs)}"
    return when {
        d < HOUR_MS -> "in ${(d / MIN_MS).coerceAtLeast(1)} min"
        d < DAY_MS -> "in ${d / HOUR_MS} h"
        else -> "in ${d / DAY_MS} d"
    }
}

/** "12 m 41 s", "1 h 02 m", "48 s". */
internal fun hcElapsed(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return when {
        h > 0L -> "$h h ${m.toString().padStart(2, '0')} m"
        m > 0L -> "$m m ${sec.toString().padStart(2, '0')} s"
        else -> "$sec s"
    }
}

internal fun hcBytes(bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < (kb * kb).toLong() -> "${(bytes / kb).roundToInt()} KB"
        bytes < (kb * kb * kb).toLong() -> "${(bytes / (kb * kb) * 10).roundToInt() / 10.0} MB"
        else -> "${(bytes / (kb * kb * kb) * 100).roundToInt() / 100.0} GB"
    }
}

/** "AB12 CD34 EF56 7890" — the same shape Settings uses (`HubStatusMath.shortFingerprint`). */
internal fun hcFingerprint(raw: String, groups: Int = 4): String =
    raw.uppercase().filter { it.isLetterOrDigit() }.take(groups * 4).chunked(4).joinToString(" ")

/** "g-eng" → "G-E" monogram for the avatar. Never empty. */
internal fun hcInitials(name: String): String {
    val words = name.trim().split(' ', '-', '_').filter { it.isNotBlank() }
    if (words.isEmpty()) return "?"
    if (words.size == 1) return words[0].take(2).uppercase()
    return (words[0].take(1) + words[1].take(1)).uppercase()
}

/** Deterministic 0..4 index for the Flash avatar palette, so a colleague keeps one colour everywhere. */
internal fun hcPaletteIndex(seed: String, size: Int): Int {
    if (size <= 1) return 0
    var h = 0
    for (ch in seed) h = (h * 31 + ch.code) and 0x7fffffff
    return abs(h) % size
}

/** Clamp a series to 0..1 for sparkline and gauge drawing. A flat series reads as a flat half-height line. */
internal fun hcNormalise(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val lo = values.min()
    val hi = values.max()
    if (hi - lo < 0.0001f) return values.map { 0.5f }
    return values.map { (it - lo) / (hi - lo) }
}
