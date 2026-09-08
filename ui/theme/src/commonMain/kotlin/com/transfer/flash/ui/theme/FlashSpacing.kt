package com.transfer.flash.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Flash spacing scale. Use these tokens instead of raw dp values in chat UI.
 */
object FlashSpacing {
    val space2 = 2.dp
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space20 = 20.dp
    val space24 = 24.dp
    val space32 = 32.dp
    val space40 = 40.dp

    /** @deprecated Use [space2] — kept for provisional chat migration. */
    @Deprecated("Use FlashSpacing.space2", ReplaceWith("FlashSpacing.space2"))
    val spacing3xs get() = space2

    @Deprecated("Use FlashSpacing.space4", ReplaceWith("FlashSpacing.space4"))
    val spacing2xs get() = space4

    @Deprecated("Use FlashSpacing.space8", ReplaceWith("FlashSpacing.space8"))
    val spacingXs get() = space8

    @Deprecated("Use FlashSpacing.space12", ReplaceWith("FlashSpacing.space12"))
    val spacingSm get() = space12

    @Deprecated("Use FlashSpacing.space16", ReplaceWith("FlashSpacing.space16"))
    val spacingMd get() = space16

    @Deprecated("Use FlashSpacing.space20", ReplaceWith("FlashSpacing.space20"))
    val spacingLg get() = space20

    @Deprecated("Use FlashSpacing.space24", ReplaceWith("FlashSpacing.space24"))
    val spacingXl get() = space24

    @Deprecated("Use FlashSpacing.space32", ReplaceWith("FlashSpacing.space32"))
    val spacing2xl get() = space32

    @Deprecated("Use FlashSpacing.space40", ReplaceWith("FlashSpacing.space40"))
    val spacing3xl get() = space40
}
