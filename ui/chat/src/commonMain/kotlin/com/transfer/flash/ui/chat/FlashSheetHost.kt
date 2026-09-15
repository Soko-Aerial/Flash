package com.transfer.flash.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Platform seam for the two modal surfaces this module puts over a screen: the bottom sheet and the
 * confirmation dialog.
 *
 * ## Why this is a seam and not one shared implementation
 *
 * Material3's `ModalBottomSheet` and `AlertDialog` are **window-backed**. On Android that is exactly
 * what you want — a real dialog window with system back handling, IME tracking, drag-to-dismiss and
 * the M3 scrim/animation. On Compose Desktop the same components render through
 * `androidx.compose.ui.window.DialogWindow`, a **separate native window** with its own stacking,
 * which puts a sheet outside the app window's z-order and produced the "sheets open somewhere else"
 * behaviour the pairing dialog never had.
 *
 * The obvious fix — replace all twelve call sites with in-composition overlays everywhere — would
 * silently strip drag-to-dismiss, IME offset and the dialog window from **Android**, where none of
 * it was broken. The platforms genuinely want different things here, so this is the `expect`/`actual`
 * case from CONVENTIONS R2: one shared call shape, two platform behaviours.
 *
 * ## The contract
 *
 * - Android: the real Material3 component, unchanged from what each call site used before.
 * - Desktop: an overlay in the window's **dialog layer** — a full-size scrim, tap-away to dismiss,
 *   taps inside swallowed, back handled through
 *   [com.transfer.flash.ui.shims.FlashBackHandler]. Not `DialogWindow` (that is the separate native
 *   window being avoided) but `Dialog`, which renders into a `ComposeSceneLayer` above the window's
 *   content.
 *
 * The layer matters and is not an implementation detail. An overlay emitted *inline* sits wherever
 * its call site put it, and Compose paints siblings in emission order — so a caller that emits the
 * overlay before its own content paints that content straight over it. `FlashSettingsScreen` did
 * exactly that with its clear-received-files confirmation, which is why the sheet appeared "behind"
 * the screen. In the dialog layer the z-order no longer depends on the call site at all.
 *
 * Both dismiss on scrim tap and both swallow taps inside the surface, which is the part callers
 * actually depend on.
 */

/**
 * A bottom sheet hosting [content].
 *
 * [dragHandle] is rendered when supplied. On Android it drives the real drag affordance; on desktop
 * it is **decoration** — there is no drag gesture in the overlay, and showing the handle anyway is a
 * deliberate trade: it keeps the two platforms visually identical, which is the acceptance bar for
 * this module, at the cost of implying a gesture that desktop does not have.
 */
@Composable
expect fun FlashSheetHost(
    onDismiss: () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier,
    /** `null` renders no handle, matching `ModalBottomSheet(dragHandle = null)`. */
    dragHandle: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
)

/**
 * A centred confirmation dialog with Material3's slot shape, so call sites read the same on both
 * platforms.
 *
 * The slots are the ones the existing dialogs already used (`title` / `text` / `confirmButton` /
 * `dismissButton`) rather than a generic content lambda: on Android they map straight onto
 * `AlertDialog`, and on desktop they are laid out in the card in that same order.
 */
@Composable
expect fun FlashConfirmHost(
    onDismiss: () -> Unit,
    containerColor: Color,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
)
