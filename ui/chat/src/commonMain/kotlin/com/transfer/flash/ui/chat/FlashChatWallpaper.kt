package com.transfer.flash.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashChatWallpaper
import com.transfer.flash.ui.theme.FlashColors
import com.transfer.flash.ui.theme.FlashTheme

/**
 * ADR-355: the chat wallpaper, drawn from the same five presets as the approved mock
 * (`design/chat-web`). The wallpaper is a field BEHIND the bubbles: message text, timestamps and
 * day chips always sit on an opaque surface, so a preset can never cost contrast.
 *
 * Patterns are geometry, not bitmaps: one `drawPoints` call for the dot grid, `drawLine` for the
 * hatch and the paper grid, and four stroked circles for the pulse. Under
 * [FlashTheme.ultraLowUi] every preset falls back to its flat ground, which is also what
 * `minimalChrome` devices render.
 */
public data class FlashChatWallpaperPreset(
    val wallpaper: FlashChatWallpaper,
    val label: String,
    val note: String,
    val lightGround: Color,
    val darkGround: Color,
)

/** Ordered as in the approved sheet: the shipped flat surface first. */
public val FlashChatWallpaperPresets: List<FlashChatWallpaperPreset> = listOf(
    FlashChatWallpaperPreset(
        FlashChatWallpaper.Graphite,
        "Graphite",
        "Flat. The shipped surface.",
        Color(0xFFEBEDF2),
        Color(0xFF101218),
    ),
    FlashChatWallpaperPreset(
        FlashChatWallpaper.Signal,
        "Signal",
        "The device mesh, as a dot grid.",
        Color(0xFFEDF0F4),
        Color(0xFF0D1014),
    ),
    FlashChatWallpaperPreset(
        FlashChatWallpaper.Blueprint,
        "Blueprint",
        "Surveyor’s hatching.",
        Color(0xFFE6EEEF),
        Color(0xFF0A211F),
    ),
    FlashChatWallpaperPreset(
        FlashChatWallpaper.Draft,
        "Draft",
        "Graph paper for long reads.",
        Color(0xFFF2EEE6),
        Color(0xFF171310),
    ),
    FlashChatWallpaperPreset(
        FlashChatWallpaper.Pulse,
        "Pulse",
        "Rings from the corner.",
        Color(0xFFE6F7F4),
        Color(0xFF07211F),
    ),
)

/** One named flat colour for "Yours", exactly the mock's six. All are light by design; the same
 * ground is used in dark mode because the choice is the user's, and every bubble keeps its own
 * surface so contrast never depends on it. */
public data class FlashChatWallpaperColour(val hex: String, val name: String)

public val FlashChatWallpaperColours: List<FlashChatWallpaperColour> = listOf(
    FlashChatWallpaperColour("#E7E9EC", "Stone"),
    FlashChatWallpaperColour("#E4EDE3", "Sage"),
    FlashChatWallpaperColour("#F2E8D8", "Sand"),
    FlashChatWallpaperColour("#F0E1DC", "Clay"),
    FlashChatWallpaperColour("#DFF1EC", "Mint"),
    FlashChatWallpaperColour("#DEE9F2", "Sky"),
)

/** `#RRGGBB` / `#AARRGGBB`; null for anything a stored file might contain that we cannot draw. */
public fun parseFlashWallpaperColour(hex: String?): Color? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    if (raw.length != 6 && raw.length != 8) return null
    val value = raw.toLongOrNull(16) ?: return null
    return if (raw.length == 6) Color(0xFF000000L or value) else Color(value)
}

/** The name shown in the sheet's applied line. */
public fun chatWallpaperLabel(wallpaper: FlashChatWallpaper): String =
    if (wallpaper == FlashChatWallpaper.Custom) "Your colour" else presetFor(wallpaper).label

public fun presetFor(wallpaper: FlashChatWallpaper): FlashChatWallpaperPreset =
    FlashChatWallpaperPresets.firstOrNull { it.wallpaper == wallpaper }
        ?: FlashChatWallpaperPresets.first()

/** The ground for the current theme. Usable outside composition (e.g. a Scaffold colour). */
public fun chatWallpaperGround(
    wallpaper: FlashChatWallpaper,
    dark: Boolean,
    custom: String = FlashChatWallpaper.DefaultCustomColour,
): Color {
    if (wallpaper == FlashChatWallpaper.Custom) {
        val base = parseFlashWallpaperColour(custom)
            ?: parseFlashWallpaperColour(FlashChatWallpaper.DefaultCustomColour)!!
        // The palette is deliberately light (bubbles are opaque and keep their own depth). In dark
        // mode the same colour is carried down towards the surface instead of turning the whole
        // thread into a bright field under light text: the hue survives, the contrast does too.
        return if (dark) lerp(base, Color(0xFF101218), 0.82f) else base
    }
    val preset = presetFor(wallpaper)
    return if (dark) preset.darkGround else preset.lightGround
}

@Composable
public fun flashChatWallpaperGround(
    wallpaper: FlashChatWallpaper,
    custom: String = FlashChatWallpaper.DefaultCustomColour,
): Color = chatWallpaperGround(wallpaper, FlashTheme.isDark, custom)

/**
 * Paints the chosen ground and its pattern. Apply to the conversation surface only; it does not
 * draw bubbles or text.
 */
@Composable
public fun Modifier.flashChatWallpaper(
    wallpaper: FlashChatWallpaper,
    custom: String = FlashChatWallpaper.DefaultCustomColour,
): Modifier {
    val colors = FlashTheme.colors
    val dark = FlashTheme.isDark
    val flat = FlashTheme.ultraLowUi
    val draw = remember(wallpaper, colors, dark, flat, custom) {
        Modifier.drawBehind {
            drawChatWallpaper(wallpaper, colors, dark, flat, custom)
        }
    }
    // `this.then(...)`: a Modifier extension that returns a fresh Modifier instead of chaining the
    // receiver silently drops every earlier size/clip in the chain — that is what made the preset
    // swatches measure 0dp and the appearance sheet's preview lose its 132dp height.
    return this.then(draw)
}

internal fun DrawScope.drawChatWallpaper(
    wallpaper: FlashChatWallpaper,
    colors: FlashColors,
    dark: Boolean,
    flat: Boolean = false,
    custom: String = FlashChatWallpaper.DefaultCustomColour,
) {
    drawRect(chatWallpaperGround(wallpaper, dark, custom))
    if (flat) return

    val ink = colors.textPrimary
    when (wallpaper) {
        FlashChatWallpaper.Graphite, FlashChatWallpaper.Custom -> Unit

        FlashChatWallpaper.Signal -> {
            val pitch = 22.dp.toPx()
            val dot = 2.2.dp.toPx()
            val points = ArrayList<Offset>()
            var y = pitch
            while (y < size.height) {
                var x = pitch
                while (x < size.width) {
                    points += Offset(x, y)
                    x += pitch
                }
                y += pitch
            }
            drawPoints(
                points = points,
                pointMode = PointMode.Points,
                color = ink.copy(alpha = if (dark) 0.055f else 0.07f),
                strokeWidth = dot,
                cap = StrokeCap.Round,
            )
        }

        FlashChatWallpaper.Blueprint -> {
            val pitch = 28.dp.toPx()
            val hairline = 1.dp.toPx()
            val long = ink.copy(alpha = if (dark) 0.13f else 0.09f)
            val short = ink.copy(alpha = if (dark) 0.07f else 0.05f)
            var x = -size.height
            while (x < size.width) {
                drawLine(long, Offset(x, 0f), Offset(x + size.height, size.height), hairline)
                drawLine(short, Offset(x, size.height), Offset(x + size.height, 0f), hairline)
                x += pitch
            }
        }

        FlashChatWallpaper.Draft -> {
            val fine = 24.dp.toPx()
            val coarse = 96.dp.toPx()
            val hairline = 1.dp.toPx()
            val inkFine = ink.copy(alpha = if (dark) 0.04f else 0.05f)
            val inkCoarse = ink.copy(alpha = if (dark) 0.05f else 0.06f)
            var x = fine
            while (x < size.width) {
                drawLine(inkFine, Offset(x, 0f), Offset(x, size.height), hairline)
                x += fine
            }
            var y = fine
            while (y < size.height) {
                drawLine(inkFine, Offset(0f, y), Offset(size.width, y), hairline)
                y += fine
            }
            x = coarse
            while (x < size.width) {
                drawLine(inkCoarse, Offset(x, 0f), Offset(x, size.height), hairline)
                x += coarse
            }
            y = coarse
            while (y < size.height) {
                drawLine(inkCoarse, Offset(0f, y), Offset(size.width, y), hairline)
                y += coarse
            }
        }

        FlashChatWallpaper.Pulse -> {
            val center = Offset(size.width, 0f)
            val stroke = 1.dp.toPx()
            val alpha = if (dark) 0.15f else 0.10f
            listOf(150.dp, 260.dp, 380.dp, 520.dp).forEach { radius ->
                drawCircle(
                    color = ink.copy(alpha = alpha),
                    radius = radius.toPx(),
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
            }
        }
    }
}
