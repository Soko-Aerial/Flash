package com.transfer.flash.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 */
object FlashAdaptiveMath {
    const val MediumMinWidthDp = 600f
    const val ExpandedMinWidthDp = 840f

    /** List pane share of width in two-pane (expanded) mode; detail gets the remainder. */
    const val ListPaneExpandedWeight = 0.38f
    const val DetailPaneExpandedWeight = 0.62f

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
}

/**
 * UI-034: current window width class derived from [BoxWithConstraints] incoming
 * constraints — the zero-dependency alternative to material3-window-size-class.
 *
 * The value converges one frame after the first layout pass because
 * [BoxWithConstraints] resolves constraints during the layout phase; callers should
 * tolerate the initial-frame default ([FlashWindowSizeClass.Compact]).
 */
@Composable
fun rememberFlashWindowSize(): FlashWindowSizeClass {
    var sizeClass by remember { mutableStateOf(FlashWindowSizeClass.Compact) }
    BoxWithConstraints(modifier = Modifier) {
        val computed = FlashAdaptiveMath.windowSizeForWidth(maxWidth.value)
        if (computed != sizeClass) sizeClass = computed
    }
    return sizeClass
}

/**
 * UI-034 list-detail container.
 *
 * Expanded width: [listPane] and [detailPane] side by side at token weights,
 * separated by a hairline divider drawn via a token-styled [Box]. When
 * [detailPane] is null on expanded width, an empty placeholder pane keeps the
 * geometry stable (canonical placeholder-detail behavior).
 *
 * Medium/Compact width: single pane — whichever content is non-null, with
 * [detailPane] winning when provided. All styling via FlashTheme tokens.
 */
@Composable
fun FlashAdaptiveTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val sizeClass = rememberFlashWindowSize()
    if (FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)) {
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(FlashAdaptiveMath.listPaneWeight(sizeClass))
                    .fillMaxHeight()
            ) {
                listPane()
            }
            Box(
                Modifier
                    .width(FlashDimensions.borderHairline)
                    .fillMaxHeight()
                    .background(FlashTheme.colors.borderSubtle)
            )
            Box(
                Modifier
                    .weight(FlashAdaptiveMath.detailPaneWeight(sizeClass))
                    .fillMaxHeight()
            ) {
                detailPane?.invoke()
            }
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            if (detailPane != null) detailPane() else listPane()
        }
    }
}
