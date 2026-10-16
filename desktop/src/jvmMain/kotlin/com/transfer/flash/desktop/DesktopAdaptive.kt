package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
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

/** Width of the icon rail left of the panes (`DesktopSideBar`), which the two panes do not share. */
internal const val DESKTOP_SIDE_RAIL_DP: Float = 68f

/** Hit area of the splitter in dp: wide enough to grab with a mouse, the visible line stays a hairline. */
private const val SPLITTER_HIT_DP = 12

/**
 * List-detail two-pane for the desktop shell: on [FlashWindowSizeClass.Expanded] the panes sit
 * side by side with a draggable splitter between them (AD-2, owner request 2026-10-16); on Compact/Medium only [listPane] renders
 * and [detailPane] is unused (the shell keeps its bottom-nav single-pane layout there).
 *
 * Without a saved preference the width is the tested automatic one (`FlashAdaptiveMath.listPaneWidthDp`). Dragging overrides it
 * within `FlashAdaptiveMath.clampListPaneWidthDp`; double click resets to automatic; Left / Right arrows (when the splitter has
 * focus) and the "Wider" / "Narrower" accessibility actions move it in steps. The width is remembered in [prefs].
 */
@Composable
public fun DesktopTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    window: java.awt.Window? = null,
    sizeClass: FlashWindowSizeClass = rememberFlashDesktopWindowSize(window),
) {
    DesktopTwoPane(listPane, detailPane, modifier, window, sizeClass, prefs = null)
}

@Composable
internal fun DesktopTwoPane(
    listPane: @Composable () -> Unit,
    detailPane: @Composable () -> Unit,
    modifier: Modifier,
    window: java.awt.Window?,
    sizeClass: FlashWindowSizeClass,
    prefs: DesktopPaneLayoutPrefs?,
) {
    if (FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)) {
        val widthDp = rememberFlashDesktopWindowWidthDp(window)
        val density = LocalDensity.current
        val available = (widthDp - DESKTOP_SIDE_RAIL_DP).coerceAtLeast(0f)
        var preferred by remember { mutableStateOf(prefs?.listPaneWidthDp()) }
        val listWidthDp = FlashAdaptiveMath.effectiveListPaneWidthDp(widthDp, available, preferred)

        fun moveTo(newWidth: Float?) {
            preferred = newWidth?.let { FlashAdaptiveMath.clampListPaneWidthDp(it, available) }
        }

        fun save() {
            prefs?.setListPaneWidthDp(preferred)
        }

        Row(modifier = modifier) {
            Box(
                Modifier
                    .width(listWidthDp.dp)
                    .fillMaxHeight(),
            ) {
                listPane()
            }
            // The splitter: a hairline in a wider, draggable strip.
            Box(
                Modifier
                    .width(SPLITTER_HIT_DP.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.E_RESIZE_CURSOR)))
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { deltaPx -> moveTo(listWidthDp + deltaPx / density.density) },
                        onDragStopped = { save() },
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            preferred = null
                            save()
                        })
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                moveTo(listWidthDp - FlashAdaptiveMath.SplitterStepDp)
                                save()
                                true
                            }
                            Key.DirectionRight -> {
                                moveTo(listWidthDp + FlashAdaptiveMath.SplitterStepDp)
                                save()
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable()
                    .semantics {
                        contentDescription = "Chat list width. Drag, or use the left and right arrow keys. Double click to reset."
                        customActions = listOf(
                            CustomAccessibilityAction("Wider chat list") {
                                moveTo(listWidthDp + FlashAdaptiveMath.SplitterStepDp)
                                save()
                                true
                            },
                            CustomAccessibilityAction("Narrower chat list") {
                                moveTo(listWidthDp - FlashAdaptiveMath.SplitterStepDp)
                                save()
                                true
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(FlashTheme.colors.borderSubtle),
                )
            }
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
