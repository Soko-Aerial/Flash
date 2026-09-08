package com.transfer.flash.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class FlashTypography(
    val display: TextStyle,
    val headingLarge: TextStyle,
    val headingMedium: TextStyle,
    val headingSmall: TextStyle,
    val bodyDefault: TextStyle,
    val bodyEmphasis: TextStyle,
    val captionDefault: TextStyle,
    val captionEmphasis: TextStyle,
    val metadataDefault: TextStyle,
    val metadataEmphasis: TextStyle,
    val numericDefault: TextStyle,
    val numericEmphasis: TextStyle,
) {
    companion object {

        /**
         * The system-font typography, built once.
         *
         * [default] used to construct a fresh [FlashTypography] plus twelve [TextStyle] objects on
         * every call, and it is called from Kotlin default-argument positions - `FlashText`'s `style`
         * parameter, `FlashTheme`'s `typography` parameter, the `LocalFlashTypography` fallback. A
         * default argument is re-evaluated at every call site that omits it, so the app's universal
         * text composable was allocating thirteen objects and discarding twelve of them per piece of
         * text drawn. The values never varied, so there is nothing to recompute.
         */
        private val System: FlashTypography = build(fontFamily = null)

        /**
         * [fontFamily] `null` means "the platform default", which is the only variant the app ships,
         * so it is served from the shared [System] instance. A caller that supplies its own family
         * still gets a freshly built set.
         */
        fun default(fontFamily: FontFamily? = null): FlashTypography =
            if (fontFamily == null) System else build(fontFamily)

        private fun build(fontFamily: FontFamily?): FlashTypography {
            val tabular = "tnum"
            return FlashTypography(
                display = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    letterSpacing = (-0.2).sp,
                ),
                headingLarge = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    lineHeight = 28.sp,
                ),
                headingMedium = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                ),
                headingSmall = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                bodyDefault = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                bodyEmphasis = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                ),
                captionDefault = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                captionEmphasis = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                ),
                metadataDefault = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                metadataEmphasis = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                ),
                numericDefault = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFeatureSettings = tabular,
                ),
                numericEmphasis = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontFeatureSettings = tabular,
                ),
            )
        }
    }
}
