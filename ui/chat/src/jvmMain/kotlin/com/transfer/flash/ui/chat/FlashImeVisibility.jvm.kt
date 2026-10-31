package com.transfer.flash.ui.chat

import androidx.compose.runtime.Composable

/**
 * Desktop actual of [flashImeVisible]: always false.
 *
 * A desktop window has no software keyboard, and the desktop artifact of
 * `androidx.compose.foundation.layout` exposes no IME-visibility API, so there is nothing to report
 * and nothing to emulate.
 */
@Composable
internal actual fun flashImeVisible(): Boolean = false
