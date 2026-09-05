package com.transfer.flash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

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
