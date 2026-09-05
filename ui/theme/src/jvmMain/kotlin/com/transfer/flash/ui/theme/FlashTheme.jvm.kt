package com.transfer.flash.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/**
 * Desktop has no wallpaper-derived palette API, so UI-036 dynamic accent is inert here and
 * [FlashTheme] falls through to the authored Flash Pulse colors — which ADR-005 says is the
 * correct default anyway, since Flash owns every slot except the three accent ones.
 */
@Composable
internal actual fun flashDynamicColorScheme(dark: Boolean): ColorScheme? = null
