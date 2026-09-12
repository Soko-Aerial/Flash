package com.transfer.flash.desktop

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
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashWindowSizeClass

/**
 * Phase 22, sub-steps 22-1/22-2 — the desktop's width-class source and two-pane container,
 * built from the adaptive math that SURVIVED ERROR-033.
 *
 * `ui:chat`'s `FlashAdaptiveTwoPane` and `rememberFlashWindowSize` were **deleted**
 * (ERROR-033): the former had no call site, and the latter wrapped a `BoxWithConstraints` —
 * a `SubcomposeLayout` — writing Compose state from inside the layout pass, so every resize
 * recomposed through a deferred subcomposition. The surviving
 * `FlashAdaptiveLayouts.kt` KDoc explicitly recommends reading the width class from
 * `LocalWindowInfo.containerSize` in composition instead. That is exactly what
 * [rememberFlashDesktopWindowSize] does; [DesktopTwoPane] then applies the tested
 * `FlashAdaptiveMath` weights — breakpoints, `isTwoPaneAllowed`, and the 0.38/0.62 split stay
 * the shared, already-tested part (`FlashAdaptiveLogicTest`, 13 cases, runs on both targets).
 */

/**
 * The current window width class, read in composition (no `SubcomposeLayout`).
 *
 * `LocalWindowInfo.containerSize` is in pixels; [FlashAdaptiveMath.windowSizeForWidth] takes
 * dp. `remember` keyed on nothing is fine here: `containerSize.width` is read as state, so a
 * resize invalidates this function directly.
 */
@Composable
public fun rememberFlashDesktopWindowSize(): FlashWindowSizeClass {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return remember(windowInfo, density) {
        val widthDp = windowInfo.containerSize.width / density.density
        FlashAdaptiveMath.windowSizeForWidth(widthDp)
    }
}

/**
 * List-detail two-pane for the desktop shell: on [FlashWindowSizeClass.Expanded] the panes sit
 * side by side at `FlashAdaptiveMath`'s tested weights with a hairline divider between them;
 * on Compact/Medium only [listPane] renders and [detailPane] is unused (the shell keeps its
 * bottom-nav single-pane layout there).
 */
@Composable
public fun DesktopTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    sizeClass: FlashWindowSizeClass = rememberFlashDesktopWindowSize(),
) {
    if (FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)) {
        Row(modifier = modifier.fillMaxSize()) {
            Box(
                Modifier
                    .weight(FlashAdaptiveMath.listPaneWeight(sizeClass))
                    .fillMaxHeight(),
            ) {
                listPane()
            }
            // Hairline divider between the panes (the deleted FlashAdaptiveTwoPane's behaviour
            // per the Phase 22 file; kept as a 1.dp divider using the theme's border token).
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight(),
            )
            Box(
                Modifier
                    .weight(FlashAdaptiveMath.detailPaneWeight(sizeClass))
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
