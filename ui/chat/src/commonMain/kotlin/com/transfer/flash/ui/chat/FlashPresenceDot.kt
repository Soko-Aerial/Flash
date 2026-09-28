package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.ui.theme.FlashTheme

/** How [FlashPresenceDot] draws a presence, or [None] when it draws nothing. */
enum class FlashPresenceDotStyle { Solid, Ring, None }

/** Pure mapping for UI-030b, unit-tested in `FlashPresenceDotLogicTest`. */
object FlashPresenceDotMath {
    fun style(presence: FlashPeerPresence): FlashPresenceDotStyle = when (presence) {
        FlashPeerPresence.Online, FlashPeerPresence.Typing -> FlashPresenceDotStyle.Solid
        FlashPeerPresence.Reachable -> FlashPresenceDotStyle.Ring
        FlashPeerPresence.Connecting, FlashPeerPresence.Offline -> FlashPresenceDotStyle.None
    }

    /** Screen-reader text; the owner's names (P3): Connected / Online. */
    fun description(style: FlashPresenceDotStyle): String? = when (style) {
        FlashPresenceDotStyle.Solid -> "Connected"
        FlashPresenceDotStyle.Ring -> "Online"
        FlashPresenceDotStyle.None -> null
    }

    /** Ring stroke; fixed so the ring stays readable at every font scale. */
    val RingStroke: Dp = 1.5.dp
}

/**
 * UI-030b presence dot. A solid dot means Connected (a live session), a ring means Online (seen, no
 * session), and nothing is drawn for Connecting or Offline. The shape carries the meaning, not the
 * colour (WCAG 1.4.1).
 *
 * @param size outer diameter, including [halo].
 * @param halo width of the surface-coloured ring that separates the dot from an avatar under it;
 *   0 for a dot drawn on plain background (the header).
 */
@Composable
fun FlashPresenceDot(
    presence: FlashPeerPresence,
    size: Dp,
    modifier: Modifier = Modifier,
    halo: Dp = 0.dp,
) {
    val style = FlashPresenceDotMath.style(presence)
    if (style == FlashPresenceDotStyle.None) return
    val colors = FlashTheme.colors
    val description = FlashPresenceDotMath.description(style).orEmpty()
    val inner = Modifier
        .clip(CircleShape)
        .let { m ->
            if (style == FlashPresenceDotStyle.Solid) {
                m.background(colors.statusOnline)
            } else {
                m.background(colors.backgroundSurface)
                    .border(FlashPresenceDotMath.RingStroke, colors.statusOnline, CircleShape)
            }
        }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.backgroundSurface)
            .padding(halo)
            .then(inner)
            .semantics { contentDescription = description },
    )
}
