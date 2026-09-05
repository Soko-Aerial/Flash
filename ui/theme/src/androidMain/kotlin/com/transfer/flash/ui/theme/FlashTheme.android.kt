package com.transfer.flash.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The SDK 31 gate and the two `dynamic*ColorScheme` calls, lifted verbatim out of the pre-KMP
 * `FlashTheme()` / `FlashMaterialTheme()` bodies. Both callers used the identical expression, so
 * this is one `actual` for both (PHASE-18 step 1d: "no additional files needed").
 */
@Composable
internal actual fun flashDynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
