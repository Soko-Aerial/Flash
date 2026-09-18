package com.transfer.flash.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.transfer.flash.ui.theme.FlashShapes

/**
 * Android actuals for [FlashSheetHost] / [FlashConfirmHost].
 *
 * **These are the real Material3 components, and that is the point of the seam.** Android's
 * `ModalBottomSheet` gives drag-to-dismiss, IME offset, the M3 scrim and animation, and
 * `AlertDialog` gives a genuine dialog window with system back — none of which was broken, and all
 * of which an in-composition overlay would have silently removed. The desktop actual is the one
 * that had to change.
 *
 * Behaviour here is byte-for-byte what each call site did before this seam existed: same
 * `skipPartiallyExpanded = true`, same `FlashShapes.sheet` top-rounded shape (the call sites passed
 * a literal `RoundedCornerShape(topStart = radius24, topEnd = radius24)`, which is that value), same
 * caller-supplied `dragHandle`.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun FlashSheetHost(
    onDismiss: () -> Unit,
    containerColor: Color,
    modifier: Modifier,
    dragHandle: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = containerColor,
        shape = FlashShapes.sheet,
        modifier = modifier,
        dragHandle = dragHandle,
        // Every converted call site passed exactly `{ WindowInsets.navigationBars }`, so it lives
        // here rather than as an `expect` parameter the desktop actual would have to ignore — the
        // desktop has no system bars to inset for. Same value, one place instead of five.
        contentWindowInsets = { WindowInsets.navigationBars },
    ) {
        // A sheet's content is a vertical stack; the call sites relied on ModalBottomSheet's own
        // ColumnScope, so this preserves their `fillMaxWidth` expectations.
        Column(Modifier.fillMaxWidth()) { content() }
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
    val colors = com.transfer.flash.ui.theme.FlashTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        containerColor = containerColor,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        modifier = modifier,
    )
}
