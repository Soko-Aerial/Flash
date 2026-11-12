package com.transfer.flash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

internal val DarkColorScheme = flashMaterialColorScheme(darkTheme = true)

internal val LightColorScheme = flashMaterialColorScheme(darkTheme = false)

/**
 * Material 3 wrapper for LAN MVP and non-chat screens.
 * Chat UI must use [FlashTheme] tokens — not [MaterialTheme.colorScheme].
 */
@Composable
fun FlashMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Was `dynamicColor && Build.VERSION.SDK_INT >= VERSION_CODES.S`. The SDK gate moved into
        // [flashDynamicColorScheme]'s Android `actual`, so a null result now stands for "the
        // platform has no dynamic scheme" — whether that is an SDK-30 phone or any desktop — and
        // falls through to the same authored scheme the old `when` would have chosen.
        dynamicColor -> flashDynamicColorScheme(dark = darkTheme)
            ?: if (darkTheme) DarkColorScheme else LightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
