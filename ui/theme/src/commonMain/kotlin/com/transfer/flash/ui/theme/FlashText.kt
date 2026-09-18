package com.transfer.flash.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
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
 *
 * [color] is handed to [BasicText] as a [ColorProducer] rather than folded into [style] with a
 * `copy`. `TextStyle.copy` rebuilds the whole `SpanStyle`/`ParagraphStyle` pair, and virtually every
 * call site in the app passes an explicit colour — so the old form paid for a fresh `TextStyle` per
 * piece of text per recomposition. A `ColorProducer` is read in the draw phase instead, which is
 * exactly what it exists for. [textAlign] has no draw-phase equivalent (it is a paragraph property
 * and feeds measurement), so it still copies, but only when it is actually set.
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
    val resolvedColor = if (color.isSpecified) {
        color
    } else if (style.color.isSpecified) {
        style.color
    } else {
        FlashTheme.colors.textPrimary
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign == null) style else style.copy(textAlign = textAlign),
        maxLines = maxLines,
        overflow = overflow,
        color = rememberFlashTextColor(resolvedColor),
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
    val resolvedColor = if (color.isSpecified) {
        color
    } else if (style.color.isSpecified) {
        style.color
    } else {
        FlashTheme.colors.textPrimary
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign == null) style else style.copy(textAlign = textAlign),
        maxLines = maxLines,
        overflow = overflow,
        color = rememberFlashTextColor(resolvedColor),
    )
}

/**
 * `null` for [Color.Unspecified] so [BasicText] falls through to `style.color`, otherwise a producer
 * cached per colour — the lambda would otherwise be a fresh allocation on every recomposition.
 */
@Composable
private fun rememberFlashTextColor(color: Color): ColorProducer? =
    if (color.isSpecified) remember(color) { ColorProducer { color } } else null
