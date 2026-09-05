package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.avatar.flashAvatarColorsFor
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Pure layout/copy logic for the group chat header (UI-028).
 * Unit-testable without instrumentation (see [FlashGroupHeaderLogicTest]).
 */
object FlashGroupHeaderMath {
    /** Max tiles rendered in the header collage (noise cap at 36dp). */
    const val MAX_COLLAGE_TILES = 4

    enum class CollageLayout { Single, TwoVertical, OneLargeTwoSmall, Quad }

    /** Tile arrangement for a given member-initials count; degenerate input falls back to Single. */
    fun collageLayoutFor(initialsCount: Int): CollageLayout = when {
        initialsCount >= 4 -> CollageLayout.Quad
        initialsCount == 3 -> CollageLayout.OneLargeTwoSmall
        initialsCount == 2 -> CollageLayout.TwoVertical
        else -> CollageLayout.Single
    }

    /** Initials actually rendered, capped at [MAX_COLLAGE_TILES]; blanks dropped. */
    fun visibleInitials(all: List<String>): List<String> =
        all.take(MAX_COLLAGE_TILES).filter { it.isNotBlank() }

    /** "5 members · 2 online" subtitle; singular-safe. Zero members yield null (caller falls back). */
    fun memberStatusLabel(memberCount: Int, onlineCount: Int): String? {
        if (memberCount <= 0) return null
        val memberPart = if (memberCount == 1) "1 member" else "$memberCount members"
        return if (onlineCount > 0) "$memberPart · $onlineCount online" else memberPart
    }

    /**
     * Named typing subtitle: 1 name → "Alex is typing…"; 2 → "Alex and Sam are typing…";
     * 3+ → first two names + "+N more are typing…". Null when nobody is typing.
     */
    fun typingStatusLabel(names: List<String>): String? {
        val clean = names.filter { it.isNotBlank() }.ifEmpty { return null }
        return when {
            clean.size == 1 -> "${clean[0]} is typing…"
            clean.size == 2 -> "${clean[0]} and ${clean[1]} are typing…"
            else -> {
                val extra = clean.size - 2
                "${clean[0]}, ${clean[1]} +$extra more are typing…"
            }
        }
    }

    /** Subtitle precedence: explicit summary wins over computed counts. */
    fun groupSubtitle(memberSummary: String?, memberCount: Int, onlineCount: Int): String? =
        memberSummary ?: memberStatusLabel(memberCount, onlineCount)
}

/**
 * UI-028 Group collage avatar — member-initial tiles arranged by count inside a circle.
 * Tiles use the shared seeded avatar palette so each member keeps their color app-wide.
 */
@Composable
fun FlashGroupAvatar(
    initials: List<String>,
    seed: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val visible = remember(initials) { FlashGroupHeaderMath.visibleInitials(initials) }
    val layout = remember(visible.size) { FlashGroupHeaderMath.collageLayoutFor(visible.size) }
    val gap = 2.dp
    val tileShape = RoundedCornerShape(3.dp)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .semantics { contentDescription = "Group avatar" },
    ) {
        when (layout) {
            FlashGroupHeaderMath.CollageLayout.Single -> {
                CollageTileContent(
                    initials = visible.firstOrNull() ?: seed.take(2).uppercase(),
                    seed = seed,
                    textSizeSp = 13f,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            FlashGroupHeaderMath.CollageLayout.TwoVertical -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CollageTile(initials = visible[0], seed = "$seed-0", shape = tileShape, textSizeSp = 10f, modifier = Modifier.weight(1f))
                    CollageTile(initials = visible[1], seed = "$seed-1", shape = tileShape, textSizeSp = 10f, modifier = Modifier.weight(1f))
                }
            }

            FlashGroupHeaderMath.CollageLayout.OneLargeTwoSmall -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CollageTile(
                        initials = visible[0],
                        seed = "$seed-0",
                        shape = tileShape,
                        textSizeSp = 12f,
                        modifier = Modifier
                            .weight(1.6f)
                            .fillMaxHeight(),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(gap),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        CollageTile(initials = visible[1], seed = "$seed-1", shape = tileShape, textSizeSp = 8f, modifier = Modifier.weight(1f))
                        CollageTile(initials = visible[2], seed = "$seed-2", shape = tileShape, textSizeSp = 8f, modifier = Modifier.weight(1f))
                    }
                }
            }

            FlashGroupHeaderMath.CollageLayout.Quad -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.weight(1f)) {
                        CollageTile(initials = visible[0], seed = "$seed-0", shape = tileShape, textSizeSp = 9f, modifier = Modifier.weight(1f))
                        CollageTile(initials = visible[1], seed = "$seed-1", shape = tileShape, textSizeSp = 9f, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.weight(1f)) {
                        CollageTile(initials = visible[2], seed = "$seed-2", shape = tileShape, textSizeSp = 9f, modifier = Modifier.weight(1f))
                        CollageTile(initials = visible[3], seed = "$seed-3", shape = tileShape, textSizeSp = 9f, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageTile(
    initials: String,
    seed: String,
    shape: RoundedCornerShape,
    textSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    CollageTileContent(
        initials = initials,
        seed = seed,
        textSizeSp = textSizeSp,
        modifier = modifier,
        shape = shape,
    )
}

@Composable
private fun CollageTileContent(
    initials: String,
    seed: String,
    textSizeSp: Float,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(3.dp),
) {
    val (background, foreground) = flashAvatarColorsFor(seed)
    Box(
        modifier = modifier
            .clip(shape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = initials.take(2).uppercase(),
            style = FlashTheme.typography.metadataEmphasis.copy(
                fontSize = textSizeSp.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = foreground,
            maxLines = 1,
        )
    }
}
