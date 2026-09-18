package com.transfer.flash.desktop

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
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashWindowSizeClass
import com.transfer.flash.ui.theme.FlashTheme

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
 * Reads `LocalWindowInfo.current.containerSize` directly without `remember(windowInfo, density)`
 * so that state changes to `containerSize` trigger recomposition, and frame 0 defaults to
 * the desktop initial window width (1200dp / Expanded) instead of 0dp (Compact).
 */
@Composable
public fun rememberFlashDesktopWindowWidthDp(window: java.awt.Window? = null): Float {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidth = windowInfo.containerSize.width
    val widthPx = if (containerWidth > 0) {
        containerWidth.toFloat()
    } else {
        // Frame 0 safety net: LocalWindowInfo.containerSize is initialized to (0, 0) before the first
        // layout pass. Fall back to the AWT window's actual width if available, or 1200dp default.
        val awtWidth = window?.width?.toFloat()
        if (awtWidth != null && awtWidth > 0f) awtWidth else (1200f * density.density)
    }
    return widthPx / density.density
}

@Composable
public fun rememberFlashDesktopWindowSize(window: java.awt.Window? = null): FlashWindowSizeClass {
    val widthDp = rememberFlashDesktopWindowWidthDp(window)
    return FlashAdaptiveMath.windowSizeForWidth(widthDp)
}

/**
 * List-detail two-pane for the desktop shell: on [FlashWindowSizeClass.Expanded] the panes sit
 * side by side at `FlashAdaptiveMath`'s tested clamped widths with a hairline divider between them;
 * on Compact/Medium only [listPane] renders and [detailPane] is unused (the shell keeps its
 * bottom-nav single-pane layout there).
 */
@Composable
public fun DesktopTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    window: java.awt.Window? = null,
    sizeClass: FlashWindowSizeClass = rememberFlashDesktopWindowSize(window),
) {
    if (FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)) {
        val widthDp = rememberFlashDesktopWindowWidthDp(window)
        val listWidthDp = remember(widthDp) {
            FlashAdaptiveMath.listPaneWidthDp(widthDp).dp
        }
        Row(modifier = modifier) {
            Box(
                Modifier
                    .width(listWidthDp)
                    .fillMaxHeight(),
            ) {
                listPane()
            }
            // Hairline divider between the panes using the theme's border token
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(FlashTheme.colors.borderSubtle),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                detailPane()
            }
        }
    } else {
        Box(modifier = modifier) {
            listPane()
        }
    }
}
