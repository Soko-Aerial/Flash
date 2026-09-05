package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable

/**
 * Intercepts the platform's "go back" gesture while [enabled].
 *
 * The `androidx.activity.compose.BackHandler` seam. Three `:ui:chat` overlays rely on it — the media
 * viewer, the message context menu and the pairing sheet — and each of them is a full-screen sibling
 * that must swallow back before anything beneath it reacts.
 *
 * On desktop there is no back gesture to intercept, so the `jvm` actual does nothing. That is a
 * deliberate no-op rather than a keyboard binding: mapping this to Esc would *add* behaviour the
 * Android side does not have, and picking the mapping is a UX decision for the desktop shell phase.
 */
@Composable
public expect fun FlashBackHandler(enabled: Boolean = true, onBack: () -> Unit)
