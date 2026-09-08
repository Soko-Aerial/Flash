package com.transfer.flash.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Flash elevation tokens. Flash chat UI prefers borders and surface layering over shadows.
 * Use elevation sparingly — sheets and menus only.
 */
object FlashElevation {
    /** Flat surfaces: bubbles, list rows, composer chrome. */
    val none = 0.dp

    /** Subtle lift for bottom sheets and context menus. */
    val sheet = 2.dp

    /** Floating overlays (reaction bar, jump-to-latest). */
    val overlay = 4.dp

    /** Modal scrim-backed dialogs only. */
    val modal = 8.dp

    /** UI-046 hanging app-shell bar: reads as detached chrome above the page. */
    val floating = 10.dp
}
