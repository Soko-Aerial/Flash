package com.transfer.flash.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * UI-001 design-system text primitive.
 *
 * Foundation-level [BasicText] (same non-Material tier as the composer's `BasicTextField`)
 * styled exclusively through [FlashTypography] tokens — chat UI never renders bare
 * `material3.Text`, keeping all visible identity Flash-owned.
 */
@Composable
fun FlashText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = FlashTypography.default().bodyDefault,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    var resolved = style
    if (color.isSpecified) {
        resolved = resolved.copy(color = color)
    }
    if (textAlign != null) {
        resolved = resolved.copy(textAlign = textAlign)
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/**
 * Annotated-string overload — used for in-message search highlighting (UI-023) and any
 * future inline spans. Same token contract as the plain-text variant.
 */
@Composable
fun FlashText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = FlashTypography.default().bodyDefault,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    var resolved = style
    if (color.isSpecified) {
        resolved = resolved.copy(color = color)
    }
    if (textAlign != null) {
        resolved = resolved.copy(textAlign = textAlign)
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = resolved,
        maxLines = maxLines,
        overflow = overflow,
    )
}
