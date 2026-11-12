package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.ui.theme.FlashTheme
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlinx.coroutines.delay

/**
 * Computes the bottom-right anchor position for the toast window inside [bounds],
 * keeping the toast clear of taskbars and never off the top/left edge.
 */
internal fun toastWindowPosition(
    bounds: Rectangle,
    windowWidthDp: Int,
    windowHeightDp: Int,
    edgeMarginDp: Int = TOAST_EDGE_MARGIN_DP,
): WindowPosition.Absolute = WindowPosition.Absolute(
    x = (bounds.x + bounds.width - windowWidthDp - edgeMarginDp).coerceAtLeast(bounds.x).dp,
    y = (bounds.y + bounds.height - windowHeightDp - edgeMarginDp).coerceAtLeast(bounds.y).dp,
)

/**
 * The actionable transfer toast (open-items tracker C-06): a small always-on-top, non-focus-stealing card in the
 * bottom-right corner with Accept / Decline for an offer and Retry / Dismiss for a failed send, shown only while Flash
 * is not in front. Must be called inside the app's theme.
 */
@Composable
internal fun DesktopTransferToastWindow(
    transfers: List<FlashTransfer>,
    appInForeground: Boolean,
    onAction: (ToastAction, FlashTransferId) -> Unit,
) {
    var dismissed by remember { mutableStateOf(emptySet<FlashTransferId>()) }
    val cards = DesktopTransferToastModel.cards(transfers, dismissed, appInForeground)

    // A failure card is a courtesy, not an obligation: it goes away by itself after a while. An offer stays until answered.
    val failureIds = cards.filter { ToastAction.Retry in it.actions }.map { it.transferId }
    LaunchedEffect(failureIds) {
        if (failureIds.isNotEmpty()) {
            delay(FAILURE_CARD_MS)
            dismissed = dismissed + failureIds
        }
    }

    if (cards.isEmpty()) return

    val initialBounds = remember { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }
    val height = DesktopTransferToastModel.windowHeightDp(cards.size)
    val width = DesktopTransferToastModel.WIDTH_DP
    // On Java 9+ the AWT screen bounds are in logical (scaled) units, the same unit as dp, so no density conversion.
    val state = rememberWindowState(
        position = toastWindowPosition(initialBounds, width, height),
        size = DpSize(width.dp, height.dp),
    )
    LaunchedEffect(height) {
        val currentBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        state.size = DpSize(width.dp, height.dp)
        state.position = toastWindowPosition(currentBounds, width, height)
    }

    Window(
        onCloseRequest = {},
        state = state,
        title = "Flash",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val colors = FlashTheme.colors
        val typography = FlashTheme.typography
        Column(
            modifier = Modifier.fillMaxSize().padding(DesktopTransferToastModel.MARGIN_DP.dp),
            verticalArrangement = Arrangement.spacedBy(DesktopTransferToastModel.GAP_DP.dp),
        ) {
            cards.forEach { card ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(DesktopTransferToastModel.CARD_HEIGHT_DP.dp)
                        .background(colors.sheetSurface, RoundedCornerShape(14.dp))
                        .border(1.dp, colors.borderDefault, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(card.title, style = typography.bodyEmphasis, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(card.detail, style = typography.captionDefault, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                        card.actions.forEachIndexed { index, action ->
                            val primary = index == 0
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (primary) colors.accentFill else colors.backgroundSurfaceStrong,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(role = Role.Button) {
                                        // Answered or dismissed: gone from the toast at once, whatever the repository does next.
                                        dismissed = dismissed + card.transferId
                                        if (action != ToastAction.Dismiss) onAction(action, card.transferId)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    action.label,
                                    style = typography.bodyEmphasis,
                                    color = if (primary) colors.onAccentFill else colors.textPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val TOAST_EDGE_MARGIN_DP: Int = 12
private const val FAILURE_CARD_MS: Long = 30_000L
