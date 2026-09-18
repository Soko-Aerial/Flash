package com.transfer.flash.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashTheme

/**
 * UI-034: Flash window width size classes following the Material breakpoints
 * (compact < 600dp, medium 600-840dp, expanded >= 840dp).
 */
enum class FlashWindowSizeClass {
    Compact,
    Medium,
    Expanded
}

/**
 * Pure breakpoint/pane math for UI-034. Free of Compose runtime types so it is
 * unit-testable on the JVM.
 *
 * No surface consumes this yet — the app is phone-only today, and its two Compose halves were
 * deleted rather than left to rot (ERROR-033):
 * - `FlashAdaptiveTwoPane`, a list/detail container with no call site anywhere in the repo.
 * - `rememberFlashWindowSize`, which wrapped a `BoxWithConstraints` — a `SubcomposeLayout` — and
 *   wrote Compose state from inside the layout pass to publish the width class, so every rotation or
 *   resize recomposed through a deferred subcomposition. `LocalWindowInfo.containerSize` or
 *   `LocalConfiguration.screenWidthDp` answers the same question in composition, without one; a
 *   tablet layout should start from those rather than resurrect the old shape.
 *
 * The breakpoints and weights below stay because they are the tested part and cost nothing until
 * something reads them.
 */
object FlashAdaptiveMath {
    const val MediumMinWidthDp = 600f
    const val ExpandedMinWidthDp = 840f

    /** List pane share of width in two-pane (expanded) mode; detail gets the remainder. */
    const val ListPaneExpandedWeight = 0.38f
    const val DetailPaneExpandedWeight = 0.62f

    /** UI-034 / AD-2: Clamped pane boundaries in dp. */
    const val ListPaneMinWidthDp = 320f
    const val ListPaneMaxWidthDp = 480f
    const val DetailPaneMinWidthDp = 480f

    fun windowSizeForWidth(widthDp: Float): FlashWindowSizeClass = when {
        widthDp >= ExpandedMinWidthDp -> FlashWindowSizeClass.Expanded
        widthDp >= MediumMinWidthDp -> FlashWindowSizeClass.Medium
        else -> FlashWindowSizeClass.Compact
    }

    fun isTwoPaneAllowed(size: FlashWindowSizeClass): Boolean =
        size == FlashWindowSizeClass.Expanded

    fun listPaneWeight(size: FlashWindowSizeClass): Float = when (size) {
        FlashWindowSizeClass.Expanded -> ListPaneExpandedWeight
        else -> 1f
    }

    fun detailPaneWeight(size: FlashWindowSizeClass): Float = when (size) {
        FlashWindowSizeClass.Expanded -> DetailPaneExpandedWeight
        else -> 1f
    }

    /**
     * Computes the width for the list pane in two-pane mode. Clamps between [ListPaneMinWidthDp]
     * and [ListPaneMaxWidthDp], reducing further if it would starve the detail pane below its minimum.
     * Outside expanded width, returns [totalWidthDp].
     */
    fun listPaneWidthDp(
        totalWidthDp: Float,
        ratio: Float = ListPaneExpandedWeight,
    ): Float {
        if (!isTwoPaneAllowed(windowSizeForWidth(totalWidthDp))) return totalWidthDp
        val maxAvailableForList = (totalWidthDp - DetailPaneMinWidthDp).coerceAtLeast(0f)
        val ideal = totalWidthDp * ratio
        val clamped = ideal.coerceIn(ListPaneMinWidthDp, ListPaneMaxWidthDp)
        return if (maxAvailableForList < ListPaneMinWidthDp) {
            clamped.coerceAtMost(totalWidthDp)
        } else {
            clamped.coerceAtMost(maxAvailableForList)
        }
    }
}

/**
 * Returns the current window width in dp, read in composition from [LocalWindowInfo.containerSize].
 */
@Composable
fun rememberFlashAdaptiveWindowWidthDp(): Float {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidth = windowInfo.containerSize.width
    return if (containerWidth > 0) {
        containerWidth / density.density
    } else {
        0f
    }
}

/**
 * Returns the current [FlashWindowSizeClass] (Compact < 600dp, Medium 600-840dp, Expanded >= 840dp).
 */
@Composable
fun rememberFlashAdaptiveWindowSizeClass(): FlashWindowSizeClass {
    val widthDp = rememberFlashAdaptiveWindowWidthDp()
    return FlashAdaptiveMath.windowSizeForWidth(widthDp)
}

/**
 * Multiplatform two-pane list-detail layout for Expanded screens (>= 840dp).
 *
 * Places [listPane] and [detailPane] side by side with a clamped width for the list pane
 * and a subtle hairline divider. On Compact/Medium, renders [listPane] only.
 */
@Composable
fun FlashAdaptiveTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    windowWidthDp: Float = rememberFlashAdaptiveWindowWidthDp(),
) {
    val sizeClass = FlashAdaptiveMath.windowSizeForWidth(windowWidthDp)
    if (FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)) {
        val listWidthDp = remember(windowWidthDp) {
            FlashAdaptiveMath.listPaneWidthDp(windowWidthDp).dp
        }
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(listWidthDp)
                    .fillMaxHeight(),
            ) {
                listPane()
            }
            Box(
                modifier = Modifier
                    .width(FlashDimensions.borderHairline)
                    .fillMaxHeight()
                    .background(FlashTheme.colors.borderSubtle),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                detailPane()
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            listPane()
        }
    }
}
