package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * No-op. A desktop window has no back gesture and no back button, so there is nothing to intercept.
 *
 * The parameters are read by nobody on purpose — `onBack` is *not* invoked, and `enabled` is not
 * inspected. Binding this to Esc or to the window-close request would give desktop a dismissal path
 * Android does not have, and would fire `onBack` for all three overlays simultaneously when several
 * are stacked. Whichever keyboard dismissal desktop ends up with belongs to the shell phase that can
 * see the whole window, not to a shim that only sees one overlay.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
public actual fun FlashBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Intentionally empty.
}
