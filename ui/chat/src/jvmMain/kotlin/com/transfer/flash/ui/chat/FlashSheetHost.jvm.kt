package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.transfer.flash.ui.shims.FlashBackHandler
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Desktop actuals for [FlashSheetHost] / [FlashConfirmHost]: overlays in the window's dialog layer.
 *
 * ## Why these are drawn by hand at all
 *
 * Desktop's Material3 `ModalBottomSheet`/`AlertDialog` open a **separate native window**, which is
 * the wrong shape for an in-app sheet. The pairing dialog — written as an overlay from the start —
 * has always worked on desktop, so the same visual result is built here.
 *
 * ## Why the overlay sits in a `Dialog` rather than inline
 *
 * Emitting the scrim inline puts it wherever the call site happens to be, and **Compose paints
 * siblings in emission order** — so any content the caller emits *after* the overlay draws on top of
 * it. That is not hypothetical: `FlashSettingsScreen` emits its `ClearReceivedFilesDialog` before its
 * `LazyColumn`, so the whole Settings page painted over the confirmation and the dialog appeared
 * "behind" the screen it was confirming against. The same trap sits under every one of the converted
 * call sites; a single one of them reordering two statements is all it takes.
 *
 * `Dialog` renders into a `ComposeSceneLayer`, a layer above the window's content, so the overlay's
 * z-order no longer depends on where the call site put it. (Note this is `Dialog`, the skiko
 * in-window overlay — *not* `DialogWindow`, which really is a second OS window and is what
 * `ModalBottomSheet` was doing.) The fix lives in this actual alone, so Android keeps its real
 * platform dialogs untouched.
 *
 * ## What is deliberately not reproduced
 *
 * `ModalBottomSheet` also drags to dismiss, animates in and out, and offsets for the IME. None of
 * that is here. Every caller is a tap-to-open, tap-away-to-close surface, so the missing half is
 * polish rather than function — stated rather than implied.
 */

@Composable
actual fun FlashSheetHost(
    onDismiss: () -> Unit,
    containerColor: Color,
    modifier: Modifier,
    dragHandle: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    FlashBackHandler(enabled = true, onBack = onDismiss)
    val colors = FlashTheme.colors
    FlashOverlayLayer(onDismiss = onDismiss) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(colors.scrim)
                // Dismiss on scrim tap. `indication = null` keeps a full-screen scrim from flashing a
                // ripple, exactly as the pairing dialog does.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = FlashSpacing.space24, vertical = FlashSpacing.space32),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 380.dp, max = 540.dp)
                    .fillMaxWidth()
                    // NOT cosmetic. Several sheets put a `LazyColumn` in their content, and an
                    // unbounded Column measures it with an infinite maximum height — which is a crash,
                    // not a layout wobble. `ModalBottomSheet` supplied this bound on Android; the
                    // overlay must supply it here.
                    .heightIn(max = maxHeight * MAX_SHEET_HEIGHT_FRACTION)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* swallow taps inside the sheet */ },
                    )
                    .clip(RoundedCornerShape(FlashShapes.radius24))
                    .background(containerColor)
                    .border(
                        FlashDimensions.borderHairline,
                        colors.borderSubtle,
                        RoundedCornerShape(FlashShapes.radius24),
                    )
                    .padding(top = FlashSpacing.space16),
            ) {
                content()
            }
        }
    }
}

@Composable
actual fun FlashConfirmHost(
    onDismiss: () -> Unit,
    containerColor: Color,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier,
    dismissButton: (@Composable () -> Unit)?,
) {
    FlashBackHandler(enabled = true, onBack = onDismiss)
    val colors = FlashTheme.colors
    FlashOverlayLayer(onDismiss = onDismiss) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(horizontal = FlashSpacing.space24, vertical = FlashSpacing.space32),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 480.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * MAX_CARD_HEIGHT_FRACTION)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { /* swallow taps inside the card */ },
                    )
                    .clip(RoundedCornerShape(FlashShapes.radius24))
                    .background(containerColor)
                    .border(
                        FlashDimensions.borderHairline,
                        colors.borderSubtle,
                        RoundedCornerShape(FlashShapes.radius24),
                    )
                    // Scrolls, unlike the Material3 original which only scrolls long TEXT. These cards
                    // hold a title, one or two sentences and a button row; the bound above is what
                    // matters, and scrolling keeps an unexpectedly long string reachable rather than
                    // clipped.
                    .verticalScroll(rememberScrollState())
                    .padding(FlashSpacing.space24),
                // Mirrors Material3's AlertDialog spacing closely enough that a caller's slots land in
                // the same places on both platforms.
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space16),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides colors.textPrimary,
                ) {
                    title()
                    text()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/**
 * The layer both overlays are drawn in, so neither depends on where its call site sits.
 *
 * Every property here is set to *remove* a platform default rather than add behaviour:
 *
 * - `usePlatformDefaultWidth = false` — without it the layer constrains content to a default dialog
 *   width, so the scrim would cover a centred strip instead of the whole window.
 * - `scrimColor = Color.Transparent` — the scrim above is ours, painted in the theme's colour; the
 *   platform's own dim would stack on top of it. This is the one flag behind
 *   `ExperimentalComposeUiApi`, opted into here rather than guessed around: the alternative is to
 *   leave the platform scrim unspecified and rely on it happening to be invisible, which is exactly
 *   the kind of assumption that shows up later as a "why is the screen twice as dark" bug.
 * - `dismissOnClickOutside = false` — the scrim's own `clickable` is the dismissal path, matching
 *   what `ModalBottomSheet` did on Android. Leaving the platform's version on would give a second,
 *   differently-triggered dismissal for the same tap.
 * - `dismissOnBackPress = false` — see `FlashBackHandler.jvm.kt`: desktop deliberately has no
 *   keyboard dismissal for a single overlay, and that decision belongs to the shell phase that can
 *   see the whole window.
 *
 * ## Why this also supplies `LocalContentColor`
 *
 * `FlashTypography` sets no colour, and **nothing in the codebase provides `LocalContentColor`** —
 * so any `FlashText` that does not pass an explicit colour falls through to `BasicText`'s default of
 * `Color.Black` (`rememberFlashTextColor` returns null for `Color.Unspecified` for exactly that
 * reason). On Android that is invisible because Material3's `AlertDialog` and `ModalBottomSheet`
 * wrap their content in a `Surface`, which supplies a content colour; this overlay is hand-built and
 * had no equivalent, so the clear-received-files confirmation drew its title and body black on the
 * dark surface — legible on light theme, gone on dark.
 *
 * Providing it here rather than at the twelve call sites means an unstyled string is correct on
 * desktop for the same reason it is correct on Android, instead of each call site having to opt in.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FlashOverlayLayer(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = FlashTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            scrimColor = Color.Transparent,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
            content()
        }
    }
}

/** Sheets are centered modal dialogs on desktop; leave the scrim visible around them. */
private const val MAX_SHEET_HEIGHT_FRACTION = 0.85f

/** A centred card must never reach the screen edges — it would stop reading as a card. */
private const val MAX_CARD_HEIGHT_FRACTION = 0.85f
