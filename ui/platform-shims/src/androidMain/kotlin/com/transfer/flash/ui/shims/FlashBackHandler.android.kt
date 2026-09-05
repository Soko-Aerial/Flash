package com.transfer.flash.ui.shims

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Delegates straight to `androidx.activity.compose.BackHandler`, which is what the three `:ui:chat`
 * call sites used before this shim existed. Behaviour on Android is unchanged by construction.
 *
 * No default argument here: defaults live on the `expect` declaration only.
 */
@Composable
public actual fun FlashBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
