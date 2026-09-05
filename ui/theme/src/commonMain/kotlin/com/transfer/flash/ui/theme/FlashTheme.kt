package com.transfer.flash.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

private val LocalFlashColors = compositionLocalOf { FlashColors.light() }
private val LocalFlashTypography = compositionLocalOf { FlashTypography.default() }
private val LocalFlashMotion = compositionLocalOf { FlashMotion(reduceMotion = false) }

/**
 * Access Flash chat design tokens. Do not use MaterialTheme.colorScheme for chat-visible styling.
 */
object FlashTheme {
    val colors: FlashColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFlashColors.current

    val typography: FlashTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalFlashTypography.current

    val motion: FlashMotion
        @Composable
        @ReadOnlyComposable
        get() = LocalFlashMotion.current
}

/**
 * Provides Flash-owned semantic colors and typography for premium chat UI.
 *
 * @param darkTheme Renders the authored Flash Pulse dark palette (UI-035). Not an inversion
 *   of light: graphite/void layered surfaces with brighter accents.
 * @param dynamicAccent UI-036. When true on Android 12+ (SDK 31), tints only
 *   [FlashColors.accentPrimary], [FlashColors.accentSecondary] and [FlashColors.textLink]
 *   from the system wallpaper scheme; surfaces, neutrals, bubbles and all other slots stay
 *   Flash-owned per ADR-005. Default false — Flash Pulse identity first.
 * @param hapticsEnabled UI-039/UI-049 user preference. False silences every
 *   [rememberFlashHaptics] call site in the subtree without touching the call sites.
 */
@Composable
fun FlashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicAccent: Boolean = false,
    hapticsEnabled: Boolean = true,
    colors: FlashColors = if (darkTheme) FlashColors.dark() else FlashColors.light(),
    typography: FlashTypography = FlashTypography.default(),
    motion: FlashMotion = rememberFlashMotion(),
    content: @Composable () -> Unit,
) {
    val dynamicScheme = if (dynamicAccent) flashDynamicColorScheme(dark = darkTheme) else null
    val resolvedColors = remember(colors, dynamicScheme) {
        resolveAccent(
            dynamicAccent = dynamicAccent,
            // Was `Build.VERSION.SDK_INT`. [flashDynamicColorScheme] already returns null below
            // SDK 31, so a non-null scheme *proves* the device is >= 31 and a null one leaves the
            // predicate false either way — the resolved colors are identical for every
            // (dynamicAccent, sdkInt) pair the old code could see. The check stays because
            // [resolveAccent] is the unit-tested seam and its 5 tests drive `sdkInt` directly.
            sdkInt = dynamicScheme?.let { DYNAMIC_ACCENT_MIN_SDK } ?: 0,
            dynamicPrimary = dynamicScheme?.primary,
            dynamicSecondary = dynamicScheme?.secondary,
            fallback = colors,
        )
    }
    val rememberedTypography = remember(typography) { typography }
    val rememberedMotion = remember(motion) { motion }

    CompositionLocalProvider(
        LocalFlashColors provides resolvedColors,
        LocalFlashTypography provides rememberedTypography,
        LocalFlashMotion provides rememberedMotion,
        LocalFlashHapticsEnabled provides hapticsEnabled,
        content = content,
    )
}

/**
 * The system wallpaper-derived Material scheme, or `null` when the platform has none (UI-036).
 *
 * Android returns `dynamicDarkColorScheme`/`dynamicLightColorScheme` on SDK 31+ and `null`
 * below it — so the SDK gate that used to live inline in [FlashTheme] now lives in the `actual`.
 * Desktop always returns `null`: there is no OS wallpaper-palette API behind Compose Desktop, so
 * `dynamicAccent = true` is simply inert there rather than an error.
 *
 * `@Composable` because the Android side needs `LocalContext`. Only accent slots are consumed
 * from the result ([resolveAccent]); Flash never adopts a system scheme wholesale (ADR-005).
 */
@Composable
internal expect fun flashDynamicColorScheme(dark: Boolean): ColorScheme?

/** `android.os.Build.VERSION_CODES.S` — a literal, so [resolveAccent] stays platform-free. */
internal const val DYNAMIC_ACCENT_MIN_SDK = 31

/**
 * Pure decision logic for UI-036 accent tinting; unit-testable without Robolectric.
 *
 * Returns [fallback] untouched unless [dynamicAccent] is enabled, the device SDK meets
 * Android 12 ([DYNAMIC_ACCENT_MIN_SDK]), and a dynamic primary was actually produced.
 * Only accentPrimary/accentSecondary/textLink are re-tinted ([FlashColors.withAccent]);
 * a missing secondary falls back to the primary hue.
 */
internal fun resolveAccent(
    dynamicAccent: Boolean,
    sdkInt: Int,
    dynamicPrimary: Color?,
    dynamicSecondary: Color?,
    fallback: FlashColors,
): FlashColors =
    if (dynamicAccent && sdkInt >= DYNAMIC_ACCENT_MIN_SDK && dynamicPrimary != null) {
        fallback.withAccent(accent = dynamicPrimary, secondary = dynamicSecondary ?: dynamicPrimary)
    } else {
        fallback
    }
