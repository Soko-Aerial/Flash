package com.transfer.flash.ui.chat

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable

/** Android actual of [flashImeVisible]: the platform's own IME visibility flag. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun flashImeVisible(): Boolean = WindowInsets.isImeVisible
