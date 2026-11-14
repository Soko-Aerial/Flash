package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlinx.coroutines.delay

/**
 * Resolves the usable display bounds of the screen where the pointer or window is located,
 * subtracting taskbars and system docks. Falls back safely if headless or query fails.
 */
internal fun resolveActiveScreenBounds(anchorPoint: Point? = null): Rectangle {
    return try {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val devices = ge.screenDevices
        val targetPoint = anchorPoint ?: try {
            MouseInfo.getPointerInfo()?.location
        } catch (_: Throwable) {
            null
        }
        val targetDevice = if (targetPoint != null) {
            devices.firstOrNull { it.defaultConfiguration.bounds.contains(targetPoint) }
        } else {
            null
        } ?: ge.defaultScreenDevice

        val config = targetDevice?.defaultConfiguration
        if (config != null) {
            val bounds = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                (bounds.width - insets.left - insets.right).coerceAtLeast(0),
                (bounds.height - insets.top - insets.bottom).coerceAtLeast(0),
            )
        } else {
            ge.maximumWindowBounds
        }
    } catch (_: Throwable) {
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        } catch (_: Throwable) {
            Rectangle(0, 0, 1920, 1080)
        }
    }
}

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
 * Unified custom toast window (Phase 3: Unified Custom Notification Center).
 * Displays rich, actionable notifications for offers, completions, failures, chat pings, calls, and pairing.
 * Employs individual per-card ticker-based auto-dismissal to prevent starvation of older toasts.
 */
@Composable
internal fun DesktopTransferToastWindow(
    cards: List<DesktopToastCard>,
    appInForeground: Boolean,
    onAction: (ToastAction, DesktopToastCard) -> Unit,
    onDismiss: ((String) -> Unit)? = null,
) {
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    val displayCards = remember(cards, dismissed, appInForeground) {
        DesktopTransferToastModel.filterActiveCards(cards, dismissed, appInForeground)
    }

    val currentCards by rememberUpdatedState(cards)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(cards) {
        val activeIds = cards.map { it.id }.toSet()
        if (dismissed.any { it !in activeIds }) {
            dismissed = dismissed.filter { it in activeIds }.toSet()
        }
    }

    // Individual ticker-based auto-dismissal: per-card deadline timestamps prevent timer restarts/starvation.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250L)
            if (currentCards.any { it.autoDismissAtMs != null }) {
                val now = System.currentTimeMillis()
                val expired = currentCards.filter { it.autoDismissAtMs != null && it.autoDismissAtMs <= now }.map { it.id }
                if (expired.isNotEmpty()) {
                    dismissed = dismissed + expired
                    expired.forEach { currentOnDismiss?.invoke(it) }
                }
            }
        }
    }

    if (displayCards.isEmpty()) return

    val initialBounds = remember { resolveActiveScreenBounds() }
    val height = DesktopTransferToastModel.windowHeightDp(displayCards)
    val width = DesktopTransferToastModel.WIDTH_DP
    val state = rememberWindowState(
        position = toastWindowPosition(initialBounds, width, height),
        size = DpSize(width.dp, height.dp),
    )
    LaunchedEffect(height) {
        val currentPosition = (state.position as? WindowPosition.Absolute)?.let {
            Point(it.x.value.toInt(), it.y.value.toInt())
        }
        val currentBounds = resolveActiveScreenBounds(currentPosition)
        state.size = DpSize(width.dp, height.dp)
        state.position = toastWindowPosition(currentBounds, width, height)
    }

    Window(
        onCloseRequest = {},
        state = state,
        title = "Flash Notifications",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
    ) {
        window.isAutoRequestFocus = false
        val colors = FlashTheme.colors
        val typography = FlashTheme.typography
        Column(
            modifier = Modifier.fillMaxSize().padding(DesktopTransferToastModel.MARGIN_DP.dp),
            verticalArrangement = Arrangement.spacedBy(DesktopTransferToastModel.GAP_DP.dp),
        ) {
            displayCards.forEach { card ->
                key(card.id) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = DesktopTransferToastModel.CARD_HEIGHT_DP.dp,
                                max = DesktopTransferToastModel.CARD_MAX_HEIGHT_DP.dp,
                            )
                            .background(colors.sheetSurface, RoundedCornerShape(FlashShapes.radius16))
                            .border(FlashDimensions.borderHairline, colors.borderDefault, RoundedCornerShape(FlashShapes.radius16))
                            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space14),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Top row: 40x40 Avatar + Content Column
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val displayInitial = card.peerInitial.ifBlank {
                                card.fileType.take(2).ifBlank {
                                    when (card.kind) {
                                        ToastKind.IncomingCall -> "C"
                                        ToastKind.PairingRequest -> "P"
                                        ToastKind.ChatMessage -> "M"
                                        else -> "F"
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier.size(40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                FlashAvatar(
                                    initials = displayInitial,
                                    size = 40.dp,
                                    seed = card.peerInitial.ifBlank { card.title },
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = card.title,
                                        style = typography.headingSmall,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (card.kind == ToastKind.IncomingCall) {
                                        Box(
                                            modifier = Modifier
                                                .padding(start = 6.dp)
                                                .background(colors.backgroundSurfaceStrong, RoundedCornerShape(FlashShapes.radius4))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = if (card.isVideoCall) "VIDEO" else "VOICE",
                                                style = typography.captionDefault,
                                                color = colors.textSecondary,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = card.detail,
                                    style = typography.bodyEmphasis,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val metadataRow = listOf(card.formattedSize, card.transportLabel)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" • ")
                                if (metadataRow.isNotBlank()) {
                                    Text(
                                        text = metadataRow,
                                        style = typography.captionDefault,
                                        color = colors.textTertiary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                        if (card.isVerifying) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space8),
                                color = colors.accentFill,
                                trackColor = colors.backgroundSurfaceSubtle,
                            )
                        }

                        // Bottom row: Action buttons aligned to the right in ergonomic order
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space8),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            card.actions.forEach { action ->
                                val isPrimary = action.isPrimary
                                val interactionSource = remember { MutableInteractionSource() }
                                val isHovered by interactionSource.collectIsHoveredAsState()
                                val isFocused by interactionSource.collectIsFocusedAsState()

                                val backgroundColor: Color
                                val contentColor: Color

                                if (card.kind == ToastKind.IncomingCall) {
                                    if (action == ToastAction.Decline) {
                                        // [Decline] (red, left)
                                        backgroundColor = if (isHovered) colors.textError.copy(alpha = 0.85f) else colors.textError
                                        contentColor = Color.White
                                    } else if (action == ToastAction.Accept) {
                                        // [Accept] (green, right)
                                        backgroundColor = if (isHovered) colors.textSuccess.copy(alpha = 0.85f) else colors.textSuccess
                                        contentColor = Color.White
                                    } else {
                                        backgroundColor = if (isHovered) colors.backgroundSurfaceStrong else colors.backgroundSurfaceSubtle
                                        contentColor = if (isHovered) colors.textPrimary else colors.textSecondary
                                    }
                                } else {
                                    if (isPrimary) {
                                        backgroundColor = if (isHovered) colors.accentFill.copy(alpha = 0.88f) else colors.accentFill
                                        contentColor = colors.onAccentFill
                                    } else {
                                        backgroundColor = if (isHovered) colors.backgroundSurfaceStrong else colors.backgroundSurfaceSubtle
                                        contentColor = if (isHovered) colors.textPrimary else colors.textSecondary
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 38.dp)
                                        .clip(RoundedCornerShape(FlashShapes.radiusFull))
                                        .background(backgroundColor, RoundedCornerShape(FlashShapes.radiusFull))
                                        .border(
                                            FlashDimensions.borderHairline,
                                            if (isFocused) contentColor else Color.Transparent,
                                            RoundedCornerShape(FlashShapes.radiusFull),
                                        )
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable(
                                            interactionSource = interactionSource,
                                            indication = null,
                                            role = Role.Button,
                                        ) {
                                            if (action == ToastAction.Dismiss) {
                                                dismissed = dismissed + card.id
                                                currentOnDismiss?.invoke(card.id)
                                            } else {
                                                onAction(action, card)
                                            }
                                        }
                                        .padding(horizontal = 18.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = action.label,
                                        style = typography.bodyEmphasis,
                                        color = contentColor,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Backward compatibility overload accepting transfer lists directly.
 */
@Composable
internal fun DesktopTransferToastWindow(
    transfers: List<FlashTransfer>,
    appInForeground: Boolean,
    onAction: (ToastAction, FlashTransferId) -> Unit,
) {
    var dismissed by remember { mutableStateOf(emptySet<FlashTransferId>()) }
    val cards = DesktopTransferToastModel.cards(transfers, dismissed, appInForeground)
    DesktopTransferToastWindow(
        cards = cards,
        appInForeground = appInForeground,
        onAction = { action, card ->
            dismissed = dismissed + card.transferId
            onAction(action, card.transferId)
        },
        onDismiss = { id ->
            dismissed = dismissed + FlashTransferId(id)
        },
    )
}

private const val TOAST_EDGE_MARGIN_DP: Int = 12
