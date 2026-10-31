package com.transfer.flash.ui.chat

import androidx.compose.runtime.Composable

/**
 * Platform seam for "is the software keyboard up right now" (OPT-O24-04).
 *
 * It exists because the two platforms disagree about the API: Android's
 * `WindowInsets.isImeVisible` lives in the Android artifact of
 * `androidx.compose.foundation.layout`, and the desktop artifact of the same artifact does not
 * contain it at all — a `WindowInsets.ime`-based fallback is not visible from `commonMain` either.
 * The same seam shape is already used by [isSwipeToDismissSupported].
 *
 * - Android: the real keyboard visibility.
 * - Desktop: always false — a desktop window has no software keyboard, so the OPT-O24-04 scroll
 *   stays a phone behaviour and the desktop code path never runs.
 */
@Composable
internal expect fun flashImeVisible(): Boolean
