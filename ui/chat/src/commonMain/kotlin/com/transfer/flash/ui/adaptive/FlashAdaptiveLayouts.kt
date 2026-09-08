package com.transfer.flash.ui.adaptive

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
