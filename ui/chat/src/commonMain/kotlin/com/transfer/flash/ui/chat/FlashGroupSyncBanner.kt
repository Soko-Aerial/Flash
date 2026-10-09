package com.transfer.flash.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashGroupSyncUi
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-052: the words of the catch-up banner. Pure so the copy is unit-tested ([FlashGroupSyncMathTest]); the banner only
 * lays them out. A total is shown only as "of about N" once a holder has reported how many it still has (ADR-100); before that there is none.
 */
object FlashGroupSyncMath {
    const val TITLE = "Catching up on earlier messages"

    /** Segment width of the sweeping line, as a fraction of the track. */
    const val SEGMENT_FRACTION = 0.35f

    /**
     * The number to show beside the title, or null before anything has been counted (a count of zero is not shown).
     * ADR-100: once a holder has said how many more it has, [expectedCount] makes it "12 of about 40"; the estimate is
     * shown only when it is above what has arrived, so the line never reads "40 of about 38".
     */
    fun countLabel(receivedCount: Int, expectedCount: Int? = null): String? {
        val expected = expectedCount?.takeIf { it > receivedCount && it > 0 }
        return when {
            expected != null -> "${receivedCount.coerceAtLeast(0)} of about $expected"
            receivedCount > 0 -> receivedCount.toString()
            else -> null
        }
    }

    /** "Catching up on earlier messages · 12", or just the title while there is nothing to count. */
    fun label(receivedCount: Int, expectedCount: Int? = null): String =
        countLabel(receivedCount, expectedCount)?.let { "$TITLE · $it" } ?: TITLE

    /** The spoken form: the line under the title is decorative, the count is the news. */
    fun description(receivedCount: Int, expectedCount: Int? = null): String =
        countLabel(receivedCount, expectedCount)?.let { "$TITLE, $it received" } ?: TITLE

    /** Left edge of the sweeping segment for a loop position in 0..1: enters from the left, leaves to the right. */
    fun segmentStart(trackWidth: Float, progress: Float): Float {
        val segment = trackWidth * SEGMENT_FRACTION
        return -segment + (trackWidth + segment) * progress.coerceIn(0f, 1f)
    }
}

/**
 * UI-052: a strip under the header while earlier group messages are arriving. It is indeterminate on purpose (see
 * [FlashGroupSyncMath]) and the host shows it only while [FlashGroupSyncUi] is non-null, so it is true whenever it is seen.
 */
@Composable
fun FlashGroupSyncBanner(
    sync: FlashGroupSyncUi,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val countLabel = FlashGroupSyncMath.countLabel(sync.receivedCount, sync.expectedCount)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurfaceSubtle)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = FlashGroupSyncMath.description(sync.receivedCount, sync.expectedCount)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            modifier = Modifier.padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
        ) {
            FlashText(
                text = FlashGroupSyncMath.TITLE,
                style = typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
            )
            if (countLabel != null) {
                FlashText(
                    text = "· $countLabel",
                    style = typography.metadataEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
        FlashSyncLine()
    }
}

/** A 2 dp line with a segment sweeping left to right (a custom line: no stock progress indicator). Static under reduced motion. */
@Composable
private fun FlashSyncLine() {
    val colors = FlashTheme.colors
    val track = colors.borderSubtle
    val accent = colors.accentPrimary
    val progress: State<Float> = if (FlashTheme.motion.reduceMotion) {
        remember { mutableStateOf(FlashGroupSyncMath.SEGMENT_FRACTION / (1f + FlashGroupSyncMath.SEGMENT_FRACTION)) }
    } else {
        rememberInfiniteTransition(label = "groupSyncLine").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_400, easing = LinearEasing), RepeatMode.Restart),
            label = "groupSyncSweep",
        )
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                drawRect(color = track)
                val segment = size.width * FlashGroupSyncMath.SEGMENT_FRACTION
                val start = FlashGroupSyncMath.segmentStart(size.width, progress.value)
                clipRect {
                    drawRect(color = accent, topLeft = Offset(start, 0f), size = Size(segment, size.height))
                }
            },
    )
}

@Preview(name = "Group sync banner", showBackground = true, widthDp = 390)
@Composable
private fun FlashGroupSyncBannerPreview() {
    FlashTheme {
        Column {
            FlashGroupSyncBanner(sync = FlashGroupSyncUi(receivedCount = 0))
            FlashGroupSyncBanner(sync = FlashGroupSyncUi(receivedCount = 12))
        }
    }
}
