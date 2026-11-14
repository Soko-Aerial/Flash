@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.transfer.flash.ui.transfers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.chat.FlashConfirmHost
import com.transfer.flash.ui.chat.FlashEmptyState
import com.transfer.flash.ui.chat.FlashErrorState
import com.transfer.flash.ui.chat.FlashStateCopy
import com.transfer.flash.ui.chat.fileCategoryColorFor
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashBrandAnimation
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashMotion
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashAnimateItem
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlin.math.roundToInt

/**
 * P3 Transfers tab (UI-047, docs/ui/transfers-page.md): sectioned per-row queue —
 * Active / Failed / History — reusing UI-016 card language. Demo state today; the exact
 * [TransfersUiState] becomes the C5 repository mapping target at Phase-8 wiring.
 */
enum class FlashTransferState { Offered, Queued, Active, Paused, Completed, Failed }

enum class FlashTransferDirection { Send, Receive }

data class FlashTransferItemUi(
    val id: String,
    val fileName: String,
    val direction: FlashTransferDirection,
    val peerName: String,
    val bytesTotal: Long,
    val bytesDone: Long,
    val state: FlashTransferState,
    val speedBytesPerSec: Long = 0,
    val etaSeconds: Long? = null,
    val timestampMs: Long = 0,
    val errorMessage: String? = null,
    val verified: Boolean = false,
    val transportLabel: String? = null,
    /** Local file path/URI for open & share actions (received file, or the sent source). */
    val localPath: String? = null,
    /**
     * Whether a [FlashTransferState.Failed] row can be retried. False for a cancelled/declined
     * transfer, which shares the Failed section but has no session left to resume — showing it a
     * Retry button made the button look broken.
     */
    val retryable: Boolean = true,
    val waitReason: com.transfer.flash.core.transfer.model.FlashTransferWaitReason? = null,
    val canGoOffline: Boolean = false,
    val holdersOnline: Int = 0,
    /** Real swarm piece map (0 missing, 1 on peers, 2 in flight, 3 verified); empty for a 1:1 transfer. */
    val pieceBlocks: List<Int> = emptyList(),
    /** True for the sending side of a group file: its cancel stops it for every member, not for one peer. */
    val isGroupFile: Boolean = false,
    /** True while post-transfer integrity hashing is running ([DomainState.Verifying]). */
    val isVerifying: Boolean = false,
)

private val FlashTransferItemUi.isAwaitingReceiverAcceptance: Boolean
    get() = direction == FlashTransferDirection.Send && state == FlashTransferState.Queued &&
        errorMessage == com.transfer.flash.core.transfer.TransferFailureText.WAITING_FOR_ACCEPT

data class TransfersUiState(
    val offers: List<FlashTransferItemUi> = emptyList(),
    val active: List<FlashTransferItemUi> = emptyList(),
    val failed: List<FlashTransferItemUi> = emptyList(),
    val history: List<FlashTransferItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isError: Boolean = false,
) {
    companion object {
        fun fromItems(items: List<FlashTransferItemUi>): TransfersUiState = TransfersUiState(
            offers = items.filter { it.state == FlashTransferState.Offered },
            active = items.filter {
                it.state == FlashTransferState.Active ||
                    it.state == FlashTransferState.Paused ||
                    it.state == FlashTransferState.Queued
            },
            failed = items.filter { it.state == FlashTransferState.Failed },
            history = items.filter { it.state == FlashTransferState.Completed },
        )
    }
}

/** Pure helpers backing the transfers page (JVM-testable). */
object FlashTransfersMath {

    /**
     * How often the *domain* transfer list should be allowed to reach this screen.
     *
     * The transfer layer publishes progress on a 10 ms watcher tick — a hundred values a second for
     * the whole duration of a transfer, each one a fresh list — and the collector sits at the app
     * shell, so unpaced it invalidates the shell and re-derives a whole [TransfersUiState] on every
     * frame whether or not this tab is even on screen.
     *
     * Nothing on this screen can show that cadence: [formatSpeed] rounds to one decimal, [formatEta]
     * to whole seconds, and both the per-row progress fill and the header throughput roll are
     * `animateFloatAsState` against Compose's frame clock — their smoothness is the animation's, not
     * the emission rate's.
     *
     * **Derived from [FlashMotion.NormalMillis] on purpose, and it must stay below it.** Those two
     * animations run for `NormalMillis`, so a window *longer* than the animation would let each one
     * finish and then sit still until the next value arrived — a periodic dead stop at the HIGH tier,
     * where nothing may be given up. Three quarters of the duration means a new target always lands
     * while the previous animation is still running, so it retargets in flight and the motion is
     * continuous. That is also why this is not tiered: reduce-motion already collapses `normalMillis`
     * to 0, and the HIGH tier keeps the exact animation it had.
     */
    const val PROGRESS_THROTTLE_MS: Long = FlashMotion.NormalMillis * 3L / 4L

    /** Fraction 0..1 clamped; zero total bytes never divides. */
    fun progressFraction(bytesDone: Long, bytesTotal: Long): Float =
        if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)

    /**
     * Width in px of a progress fill occupying [fraction] of a track, measured in the layout phase.
     *
     * This is `Modifier.fillMaxWidth(fraction)`'s own arithmetic, lifted out so it can be asserted:
     * Compose's `FillNode` computes `(maxWidth * fraction).roundToInt().coerceIn(minWidth, maxWidth)`,
     * and the fill in [TransferRow] must match it exactly, because that is what it replaced (EXP-013).
     * Keeping the formula here means a future edit that drifts from `FillNode` fails a test instead of
     * quietly resizing every progress bar by a pixel.
     */
    fun progressBarWidthPx(minWidthPx: Int, maxWidthPx: Int, fraction: Float): Int =
        (maxWidthPx * fraction).roundToInt().coerceIn(minWidthPx, maxWidthPx)

    private fun oneDecimal(value: Long, unit: Long): String {
        val tenths = value * 10L / unit
        return "${tenths / 10L}.${tenths % 10L}"
    }

    /** Decimal SI byte size (`1.4 GB`, `812 KB`, `50.0 MB`), matching [TransferFailureText.formatSize]. */
    fun formatSize(bytes: Long): String {
        val b = bytes.coerceAtLeast(0L)
        return when {
            b < 1_000L -> "$b B"
            b < 1_000_000L -> "${b / 1_000L} KB"
            b < 1_000_000_000L -> oneDecimal(b, 1_000_000L) + " MB"
            else -> oneDecimal(b, 1_000_000_000L) + " GB"
        }
    }

    /** Decimal SI transfer rate (`3.5 MB/s`, `500 KB/s`), consistent with [formatSize]. */
    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec <= 0L -> ""
        bytesPerSec < 1_000L -> "$bytesPerSec B/s"
        bytesPerSec < 1_000_000L -> "${bytesPerSec / 1_000L} KB/s"
        bytesPerSec < 1_000_000_000L -> oneDecimal(bytesPerSec, 1_000_000L) + " MB/s"
        else -> oneDecimal(bytesPerSec, 1_000_000_000L) + " GB/s"
    }

    /** Concise size + percentage summary for transfer rows. */
    fun progressSummary(item: FlashTransferItemUi): String? = when (item.state) {
        FlashTransferState.Offered,
        FlashTransferState.Completed -> formatSize(item.bytesTotal)
        FlashTransferState.Active,
        FlashTransferState.Paused,
        FlashTransferState.Queued -> if (item.bytesTotal > 0L) {
            val pct = (progressFraction(item.bytesDone, item.bytesTotal) * 100).toInt()
            "${formatSize(item.bytesDone)} / ${formatSize(item.bytesTotal)} · $pct%"
        } else {
            null
        }
        FlashTransferState.Failed -> if (item.bytesTotal > 0L && item.bytesDone in 1 until item.bytesTotal) {
            val pct = (progressFraction(item.bytesDone, item.bytesTotal) * 100).toInt()
            "${formatSize(item.bytesDone)} / ${formatSize(item.bytesTotal)} · $pct%"
        } else if (item.bytesTotal > 0L) {
            formatSize(item.bytesTotal)
        } else {
            null
        }
    }

    /**
     * Duration in milliseconds for the velocity shimmer sweep across active progress bars.
     * Higher throughput accelerates the sweep, giving responsive tactical speed feedback.
     */
    fun shimmerDurationMillis(bytesPerSec: Long): Int {
        val speedMb = bytesPerSec / (1024f * 1024f)
        return when {
            speedMb >= 50f -> 600
            speedMb >= 20f -> 800
            speedMb >= 5f -> 1200
            speedMb >= 1f -> 1600
            else -> 2200
        }
    }

    fun formatEta(seconds: Long?): String = when {
        seconds == null || seconds <= 0L -> ""
        seconds < 60L -> "$seconds sec left"
        seconds < 3600L -> "${seconds / 60L} min left"
        else -> "${seconds / 3600L} h ${seconds % 3600L / 60L} min left"
    }

    /**
     * Combined live throughput across the rows that are actually moving bytes. Paused and queued
     * rows contribute nothing, so pausing one transfer visibly drops the header number rather than
     * leaving a stale total behind.
     */
    fun aggregateSpeed(active: List<FlashTransferItemUi>): Long = active
        .filter { it.state == FlashTransferState.Active && !it.isVerifying }
        .sumOf { it.speedBytesPerSec.coerceAtLeast(0L) }

    fun directionLabel(item: FlashTransferItemUi): String = when (item.direction) {
        FlashTransferDirection.Send -> "To"
        FlashTransferDirection.Receive -> "From"
    }

    fun statusLine(item: FlashTransferItemUi): String = when (item.state) {
        FlashTransferState.Offered -> "Wants to send you this file"
        FlashTransferState.Queued -> {
            if (item.isAwaitingReceiverAcceptance) {
                com.transfer.flash.core.transfer.TransferFailureText.WAITING_FOR_ACCEPT
            } else if (item.waitReason != null) {
                com.transfer.flash.ui.chat.FlashSwarmUiMath.receiverStatusLine(
                    waitReason = item.waitReason,
                    holdersOnline = item.holdersOnline,
                    senderName = item.peerName,
                    bytesDone = item.bytesDone,
                    bytesTotal = item.bytesTotal,
                    failureMessage = item.errorMessage,
                ) ?: "Queued"
            } else {
                "Queued"
            }
        }
        FlashTransferState.Paused -> "Paused"
        FlashTransferState.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: "Failed"
        FlashTransferState.Completed -> if (item.verified) "Verified" else "Completed"
        FlashTransferState.Active -> {
            if (item.isVerifying) {
                "Verifying file…"
            } else if (item.direction == FlashTransferDirection.Send && item.canGoOffline) {
                "You can go offline now"
            } else if (item.direction == FlashTransferDirection.Receive && item.holdersOnline > 1) {
                val speed = formatSpeed(item.speedBytesPerSec).takeIf { it.isNotEmpty() }
                val eta = formatEta(item.etaSeconds).takeIf { it.isNotEmpty() }
                listOfNotNull(
                    "Getting it from ${item.holdersOnline} devices",
                    speed,
                    eta,
                ).joinToString(" · ")
            } else {
                listOfNotNull(
                    formatSpeed(item.speedBytesPerSec).takeIf { it.isNotEmpty() },
                    formatEta(item.etaSeconds).takeIf { it.isNotEmpty() },
                ).joinToString(" · ").ifBlank { "Transferring" }
            }
        }
    }
}

private data class TransferSections(
    val active: List<FlashTransferItemUi>,
    val failed: List<FlashTransferItemUi>,
    val history: List<FlashTransferItemUi>,
)

@Composable
fun FlashTransfersScreen(
    state: TransfersUiState,
    onPauseResumeClick: (FlashTransferItemUi) -> Unit,
    onCancelClick: (FlashTransferItemUi) -> Unit,
    onRetryClick: (FlashTransferItemUi) -> Unit,
    /** Row tap on a completed transfer: open the received file in a viewer (ACTION_VIEW). */
    onHistoryOpen: (FlashTransferItemUi) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /** Space the hanging shell bar occupies; content scrolls under it (UI-046). */
    bottomInset: Dp = 0.dp,
    onFindDevices: (() -> Unit)? = null,
    onAcceptOffer: (FlashTransferItemUi) -> Unit = {},
    onDeclineOffer: (FlashTransferItemUi) -> Unit = {},
    /** Trailing share glyph on a completed transfer (ACTION_SEND). Defaults to open. */
    onHistoryShare: (FlashTransferItemUi) -> Unit = onHistoryOpen,
    /** Optional action to clear completed transfer history from the view. */
    onClearHistory: (() -> Unit)? = null,
    /** Hides one Failed or Cancelled row (C-10). A host that passes this gets a dismiss glyph on those rows. */
    onDismissItem: ((FlashTransferItemUi) -> Unit)? = null,
    /** Hides Failed rows (C-10): the "Clear" beside the FAILED section label. */
    onClearFailed: (() -> Unit)? = null,
    /** Hides Cancelled/Declined rows (C-10): the "Clear" beside CANCELLED / DECLINED. */
    onClearCancelled: (() -> Unit)? = null,
    /** Optional row tap in two-pane mode to inspect offers, active, paused, queued, or failed transfers. */
    onItemClick: ((FlashTransferItemUi) -> Unit)? = null,
    /** Retry callback when loading transfers fails. */
    onRetryLoad: (() -> Unit)? = null,
) {
    // Every branch clears the status bar: this page is its own top-level surface and has no
    // top bar of its own to own that inset (Chats/Conversation do it in their headers).
    val surface = modifier.fillMaxSize().statusBarsPadding()
    val statusSwap = FlashTheme.motion.statusCrossfade()
    var pendingCancelItem by remember { mutableStateOf<FlashTransferItemUi?>(null) }
    // Crossfade on the *branch*, not on `state`: the populated branch re-emits on every
    // progress tick and must not restart a transition.
    AnimatedContent(
        targetState = state.pageState(),
        transitionSpec = { statusSwap },
        label = "transfersPageState",
    ) { page ->
        when (page) {
            TransfersPageState.Error -> Box(
                modifier = surface
                    .verticalScroll(rememberScrollState())
                    .padding(top = FlashSpacing.space24, bottom = FlashSpacing.space24 + bottomInset),
                contentAlignment = Alignment.Center,
            ) {
                FlashErrorState(
                    title = "Transfers unavailable",
                    message = "Your transfers could not be loaded.",
                    onRetry = { onRetryLoad?.invoke() },
                )
            }
            TransfersPageState.Loading -> LoadingRows(surface.padding(bottom = bottomInset))
            TransfersPageState.Empty -> Column(modifier = surface.padding(bottom = bottomInset)) {
                // The empty state is still the Transfers page: keep the same page heading the
                // populated list shows (one shared composable, so the two cannot drift apart),
                // then let the empty panel take the remaining height.
                HeaderWithChips(
                    state = state,
                    modifier = Modifier.padding(
                        start = FlashSpacing.space16,
                        end = FlashSpacing.space16,
                        top = FlashSpacing.space12,
                    ),
                )
                FlashEmptyState(
                    kind = FlashStateCopy.EmptyKind.TransfersFirstRun,
                    modifier = Modifier.weight(1f).padding(top = FlashSpacing.space16),
                    onAction = onFindDevices,
                )
            }
            TransfersPageState.Populated -> PopulatedSections(
                state = state,
                onPauseResumeClick = onPauseResumeClick,
                onCancelClick = { item -> pendingCancelItem = item },
                onRetryClick = onRetryClick,
                onHistoryOpen = onHistoryOpen,
                onHistoryShare = onHistoryShare,
                onAcceptOffer = onAcceptOffer,
                onDeclineOffer = onDeclineOffer,
                onClearHistory = onClearHistory,
                onDismissItem = onDismissItem,
                onClearFailed = onClearFailed,
                onClearCancelled = onClearCancelled,
                onItemClick = onItemClick,
                modifier = surface,
                listState = listState,
                bottomInset = bottomInset,
            )
        }
    }

    pendingCancelItem?.let { item ->
        val colors = FlashTheme.colors
        FlashConfirmHost(
            onDismiss = { pendingCancelItem = null },
            containerColor = colors.backgroundSurface,
            title = {
                FlashText(
                    text = FlashCancelCopy.title(item.direction, item.isGroupFile),
                    style = FlashTheme.typography.headingMedium,
                    color = colors.textPrimary,
                )
            },
            text = {
                FlashText(
                    text = FlashCancelCopy.body(item.direction, item.isGroupFile),
                    style = FlashTheme.typography.bodyDefault,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable {
                            pendingCancelItem = null
                            onCancelClick(item)
                        }
                        .semantics { role = Role.Button }
                        .padding(horizontal = FlashSpacing.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = FlashCancelCopy.confirm(item.direction, item.isGroupFile),
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textError,
                    )
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clip(FlashShapes.bubbleGrouped)
                        .clickable { pendingCancelItem = null }
                        .semantics { role = Role.Button }
                        .padding(horizontal = FlashSpacing.space12),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = "Keep transfer",
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textSecondary,
                    )
                }
            },
        )
    }
}

/** Which of the four page branches the current state resolves to (drives the crossfade). */
private enum class TransfersPageState { Error, Loading, Empty, Populated }

private fun TransfersUiState.pageState(): TransfersPageState = when {
    isError -> TransfersPageState.Error
    isLoading && isEmpty() -> TransfersPageState.Loading
    isEmpty() -> TransfersPageState.Empty
    else -> TransfersPageState.Populated
}

private fun TransfersUiState.isEmpty(): Boolean =
    offers.isEmpty() && active.isEmpty() && failed.isEmpty() && history.isEmpty()

@Composable
private fun LoadingRows(modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = FlashSpacing.space16)) {
        // Branded loading mark (Bug 4 reuse): compact FlashBrandAnimation without the dark
        // splash gradient, centered above the skeleton transfer rows.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FlashSpacing.space24, bottom = FlashSpacing.space12),
            contentAlignment = Alignment.Center,
        ) {
            FlashBrandAnimation(
                background = false,
                modifier = Modifier.size(width = 96.dp, height = 96.dp),
            )
        }
        repeat(4) {
            Box(
                Modifier
                    .padding(vertical = FlashSpacing.space8)
                    .fillMaxWidth()
                    .height(FlashDimensions.chatListRowHeight)
                    .clip(RoundedCornerShape(FlashShapes.radius12))
                    .background(FlashTheme.colors.backgroundSurfaceStrong),
            )
        }
    }
}

@Composable
private fun PopulatedSections(
    state: TransfersUiState,
    onPauseResumeClick: (FlashTransferItemUi) -> Unit,
    onCancelClick: (FlashTransferItemUi) -> Unit,
    onRetryClick: (FlashTransferItemUi) -> Unit,
    onHistoryOpen: (FlashTransferItemUi) -> Unit,
    onHistoryShare: (FlashTransferItemUi) -> Unit,
    onAcceptOffer: (FlashTransferItemUi) -> Unit,
    onDeclineOffer: (FlashTransferItemUi) -> Unit,
    onClearHistory: (() -> Unit)?,
    onDismissItem: ((FlashTransferItemUi) -> Unit)?,
    onClearFailed: (() -> Unit)?,
    onClearCancelled: (() -> Unit)?,
    onItemClick: ((FlashTransferItemUi) -> Unit)?,
    modifier: Modifier,
    listState: LazyListState,
    bottomInset: Dp,
) {
    val colors = FlashTheme.colors
    val statusSwap = FlashTheme.motion.statusCrossfade()
    val motion = FlashTheme.motion
    val (retryableFailed, cancelledOrDeclined) = remember(state.failed) {
        state.failed.partition { it.retryable }
    }
    val pausableItems = remember(state.active) {
        state.active.filter {
            (it.state == FlashTransferState.Active && !it.isVerifying) || it.state == FlashTransferState.Paused
        }
    }

    // Rows are keyed by transfer id and hop between sections as state changes
    // (Active → Failed → Cancelled → History), so placement is animated rather than snapping.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = FlashSpacing.space16,
            end = FlashSpacing.space16,
            top = FlashSpacing.space12,
            bottom = FlashSpacing.space12 + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        item(key = "header") { HeaderWithChips(state) }
        if (state.offers.isNotEmpty()) {
            item(key = "label-offers") { SectionLabel("INCOMING OFFERS") }
            items(state.offers, key = { it.id }, contentType = { "offer" }) { item ->
                TransferRow(
                    item = item,
                    modifier = flashAnimateItem(motion),
                    onRowClick = onItemClick?.let { click -> { click(item) } },
                    trailing = {
                        RowIcon(
                            icon = FlashIcons.Check,
                            description = "Accept ${item.fileName}",
                            tint = colors.accentFill,
                            onClick = { onAcceptOffer(item) },
                        )
                        Spacer(Modifier.width(FlashSpacing.space8))
                        RowIcon(
                            icon = FlashIcons.Close,
                            description = "Decline ${item.fileName}",
                            tint = colors.textError,
                            onClick = { onDeclineOffer(item) },
                        )
                    },
                )
            }
        }
        if (state.active.isNotEmpty()) {
            item(key = "label-active") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space4),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashText(
                        text = "ACTIVE",
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textSecondary,
                    )
                    if (pausableItems.size > 1) {
                        val anyActiveRunning = pausableItems.any { it.state == FlashTransferState.Active }
                        Box(
                            modifier = Modifier
                                .heightIn(min = FlashDimensions.minTouchTarget)
                                .clip(RoundedCornerShape(FlashShapes.radius8))
                                .background(colors.backgroundSurfaceSubtle)
                                .clickable(role = Role.Button) {
                                    pausableItems.forEach { item ->
                                        if (anyActiveRunning && item.state == FlashTransferState.Active) {
                                            onPauseResumeClick(item)
                                        } else if (!anyActiveRunning && item.state == FlashTransferState.Paused) {
                                            onPauseResumeClick(item)
                                        }
                                    }
                                }
                                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                            contentAlignment = Alignment.Center,
                        ) {
                            FlashText(
                                text = if (anyActiveRunning) "Pause all" else "Resume all",
                                style = FlashTheme.typography.captionEmphasis,
                                color = colors.accentFill,
                            )
                        }
                    }
                }
            }
            items(state.active, key = { it.id }, contentType = { "active" }) { item ->
                val canPauseResume = (item.state == FlashTransferState.Active && !item.isVerifying) ||
                    item.state == FlashTransferState.Paused
                TransferRow(
                    item = item,
                    modifier = flashAnimateItem(motion),
                    onRowClick = onItemClick?.let { click -> { click(item) } },
                    trailing = {
                        if (canPauseResume) {
                            AnimatedContent(
                                targetState = item.state == FlashTransferState.Paused,
                                transitionSpec = { statusSwap },
                                label = "pauseResumeSwap",
                            ) { isPaused ->
                                RowIcon(
                                    icon = if (isPaused) FlashIcons.Play else FlashIcons.Pause,
                                    description = if (isPaused) "Resume ${item.fileName}" else "Pause ${item.fileName}",
                                    onClick = { onPauseResumeClick(item) },
                                )
                            }
                            Spacer(Modifier.width(FlashSpacing.space8))
                        }
                        RowIcon(
                            icon = FlashIcons.Stop,
                            description = "Cancel ${item.fileName}",
                            onClick = { onCancelClick(item) },
                        )
                    },
                )
            }
        }
        if (retryableFailed.isNotEmpty()) {
            item(key = "label-failed") {
                SectionLabelWithClear(
                    text = "FAILED",
                    onClear = onClearFailed,
                    clearDescription = "Clear failed transfers",
                )
            }
            items(retryableFailed, key = { it.id }, contentType = { "failed" }) { item ->
                TransferRow(
                    item = item,
                    modifier = flashAnimateItem(motion),
                    onRowClick = onItemClick?.let { click -> { click(item) } },
                    trailing = {
                        RowIcon(
                            icon = FlashIcons.Retry,
                            description = "Retry ${item.fileName}",
                            onClick = { onRetryClick(item) },
                        )
                        if (onDismissItem != null) {
                            Spacer(Modifier.width(FlashSpacing.space8))
                            RowIcon(
                                icon = FlashIcons.Delete,
                                description = "Dismiss ${item.fileName}",
                                onClick = { onDismissItem(item) },
                            )
                        }
                    },
                )
            }
        }
        if (cancelledOrDeclined.isNotEmpty()) {
            item(key = "label-cancelled") {
                SectionLabelWithClear(
                    text = "CANCELLED / DECLINED",
                    onClear = onClearCancelled,
                    clearDescription = "Clear cancelled transfers",
                )
            }
            items(cancelledOrDeclined, key = { it.id }, contentType = { "failed" }) { item ->
                TransferRow(
                    item = item,
                    modifier = flashAnimateItem(motion),
                    onRowClick = onItemClick?.let { click -> { click(item) } },
                    trailing = {
                        if (onDismissItem != null) {
                            RowIcon(
                                icon = FlashIcons.Delete,
                                description = "Dismiss ${item.fileName}",
                                onClick = { onDismissItem(item) },
                            )
                        }
                    },
                )
            }
        }
        if (state.history.isNotEmpty()) {
            item(key = "label-history") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space4),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashText(
                        text = "HISTORY",
                        modifier = Modifier.weight(1f).semantics { heading() },
                        style = FlashTheme.typography.captionEmphasis,
                        color = colors.textSecondary,
                    )
                    if (onClearHistory != null) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = FlashDimensions.minTouchTarget)
                                .clip(RoundedCornerShape(FlashShapes.radius8))
                                .background(colors.backgroundSurfaceSubtle)
                                .clickable(role = Role.Button, onClickLabel = "Clear transfer history") { onClearHistory() }
                                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                            contentAlignment = Alignment.Center,
                        ) {
                            FlashText(
                                text = "Clear history",
                                style = FlashTheme.typography.captionEmphasis,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
            }
            items(state.history, key = { it.id }, contentType = { "history" }) { item ->
                TransferRow(
                    item = item,
                    modifier = flashAnimateItem(motion),
                    trailing = {
                        RowIcon(
                            icon = FlashIcons.Share,
                            description = "Share ${item.fileName}",
                            onClick = { onHistoryShare(item) },
                        )
                    },
                    onRowClick = { onHistoryOpen(item) },
                )
            }
        }
    }
}

@Composable
private fun HeaderWithChips(state: TransfersUiState, modifier: Modifier = Modifier) {
    val motion = FlashTheme.motion
    // Aggregate throughput ROLLS to its new value instead of snapping, so a burst reads as
    // acceleration and a pause reads as a drop. animateFloatAsState is a single value animation
    // (not a per-row one), and the label it produces is re-read only when the rounded text
    // actually changes.
    //
    // The `derivedStateOf` is what makes that last sentence true (EXP-013). `Column` is an inline
    // function, so reads inside its content lambda belong to *this* composable's restart scope;
    // formatting the raw animated float here therefore re-executed the header — two texts, two
    // AnimatedVisibility containers and the failed-count chip — on every frame of the roll, for as
    // long as any transfer was moving. Deriving the String means the read happens in the derivation
    // and structural equality drops every frame that formats to the same text, so the header
    // recomposes when the *label* changes ("1.2 MB/s" -> "1.4 MB/s") rather than when the float does.
    val rolledSpeed = animateFloatAsState(
        targetValue = FlashTransfersMath.aggregateSpeed(state.active).toFloat(),
        animationSpec = if (motion.reduceMotion) snap() else motion.tweenNormalSpec(),
        label = "transfersThroughputRoll",
    )
    val throughputLabel by remember(rolledSpeed) {
        derivedStateOf { FlashTransfersMath.formatSpeed(rolledSpeed.value.toLong()) }
    }

    Column(modifier = modifier) {
        FlashText(
            text = "Transfers",
            modifier = Modifier.semantics { heading() },
            style = FlashTheme.typography.headingMedium,
            color = FlashTheme.colors.textPrimary,
        )
        FlashText(
            text = "Track files you send and receive.",
            style = FlashTheme.typography.captionDefault,
            color = FlashTheme.colors.textSecondary,
        )
        AnimatedVisibility(visible = throughputLabel.isNotEmpty()) {
            FlashText(
                text = "$throughputLabel total speed",
                style = FlashTheme.typography.numericDefault,
                color = FlashTheme.colors.textSecondary,
            )
        }
        val (retryableFailed, cancelledOrDeclined) = remember(state.failed) {
            state.failed.partition { it.retryable }
        }
        FlowRow(
            modifier = Modifier.padding(top = FlashSpacing.space8),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
        ) {
            if (retryableFailed.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(FlashShapes.chip)
                        .background(FlashTheme.colors.textError.copy(alpha = 0.12f))
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
                ) {
                    FlashText(
                        text = "${retryableFailed.size} failed",
                        style = FlashTheme.typography.captionDefault,
                        color = FlashTheme.colors.textError,
                    )
                }
            }
            if (cancelledOrDeclined.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(FlashShapes.chip)
                        .background(FlashTheme.colors.backgroundSurfaceSubtle)
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
                ) {
                    FlashText(
                        text = "${cancelledOrDeclined.size} cancelled",
                        style = FlashTheme.typography.captionDefault,
                        color = FlashTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    FlashText(
        text = text,
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textSecondary,
        modifier = Modifier.padding(top = FlashSpacing.space4).semantics { heading() },
    )
}

/** A section label with an optional "Clear" chip at its end (C-10), styled like the History section's. */
@Composable
private fun SectionLabelWithClear(
    text: String,
    onClear: (() -> Unit)?,
    clearDescription: String = "Clear $text",
) {
    if (onClear == null) {
        SectionLabel(text)
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = FlashSpacing.space4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlashText(
            text = text,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = FlashTheme.typography.captionEmphasis,
            color = FlashTheme.colors.textSecondary,
        )
        Box(
            modifier = Modifier
                .heightIn(min = FlashDimensions.minTouchTarget)
                .clip(RoundedCornerShape(FlashShapes.radius8))
                .background(FlashTheme.colors.backgroundSurfaceSubtle)
                .clickable(role = Role.Button, onClickLabel = clearDescription) { onClear() }
                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = "Clear",
                style = FlashTheme.typography.captionEmphasis,
                color = FlashTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun TransferRow(
    item: FlashTransferItemUi,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onRowClick: (() -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    // Stays a `State`; the only consumers are a layout modifier and the fill's width (EXP-013).
    // Unwrapped, this animation ran the whole row's body — badge, two texts, the track, the status
    // line and the trailing controls — once per frame for the whole of every transfer, because
    // `fillMaxWidth(fraction)` is a composition-time argument. The `layout` block below reproduces
    // exactly what `fillMaxWidth(fraction)` computes, but reads the value in the layout phase, so a
    // frame of the tween re-measures one 2dp bar and recomposes nothing.
    val fraction = animateFloatAsState(
        targetValue = FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal),
        animationSpec = FlashTheme.motion.tweenNormalSpec(),
        label = "transferProgress",
    )
    val fillTint = when (item.state) {
        FlashTransferState.Offered -> colors.statusTransfer
        FlashTransferState.Paused, FlashTransferState.Queued -> colors.statusTransfer
        FlashTransferState.Failed -> colors.textError
        else -> colors.accentFill
    }
    val progressSummary = FlashTransfersMath.progressSummary(item)
    val subtitleSuffix = buildString {
        item.transportLabel?.let { append(" · ").append(it) }
        if ((item.state == FlashTransferState.Completed || item.state == FlashTransferState.Offered) &&
            progressSummary != null
        ) {
            append(" · ").append(progressSummary)
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stackActions = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.3f
        Row(
            Modifier
                .fillMaxWidth()
                .flashPressScale(interactionSource)
                .clip(RoundedCornerShape(FlashShapes.radius12))
                .background(colors.backgroundSurface)
                .let { base ->
                    if (onRowClick != null) {
                        base.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClickLabel = if (item.state == FlashTransferState.Completed) "Open" else "Inspect",
                            onClick = onRowClick,
                        )
                    } else {
                        base
                    }
                }
                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(item.fileName)
                        append(", ")
                        append(when (item.direction) {
                            FlashTransferDirection.Send -> "Sending"
                            FlashTransferDirection.Receive -> "Receiving"
                        })
                        // The real progress, not the tween: a screen reader should hear where the
                        // transfer actually is, and reading `item` here (a parameter, not a State) keeps
                        // the animation from re-running this whole `buildString` in the semantics phase
                        // on every frame (EXP-013).
                        append(", ${(FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal) * 100).toInt()} percent")
                        progressSummary?.let { append(", $it") }
                        append(", ${FlashTransfersMath.statusLine(item)}")
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransferBadge(
                fileName = item.fileName,
            )
            Spacer(Modifier.width(FlashSpacing.space8))
            Column(Modifier.weight(1f)) {
                FlashText(
                    text = item.fileName,
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = "${FlashTransfersMath.directionLabel(item)} ${item.peerName}$subtitleSuffix",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.state != FlashTransferState.Offered) {
                    TransferProgressBar(
                        fraction = fraction,
                        fillTint = fillTint,
                        isActive = item.state == FlashTransferState.Active && !item.isVerifying,
                        speedBytesPerSec = item.speedBytesPerSec,
                        reduceMotion = FlashTheme.motion.reduceMotion,
                        modifier = Modifier.padding(top = FlashSpacing.space4),
                    )
                }
                if (progressSummary != null &&
                    item.state != FlashTransferState.Completed &&
                    item.state != FlashTransferState.Offered
                ) {
                    FlashText(
                        text = progressSummary,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FlashText(
                    text = FlashTransfersMath.statusLine(item),
                    style = FlashTheme.typography.metadataDefault,
                    color = if (item.state == FlashTransferState.Failed) colors.textError else colors.textSecondary,
                    maxLines = if (item.state == FlashTransferState.Failed && onRowClick == null) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(FlashSpacing.space8))
            if (stackActions) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { trailing() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        }
    }
}


/**
 * The progress bar with the velocity shimmer: the sweep gets faster as [speedBytesPerSec] grows
 * ([FlashTransfersMath.shimmerDurationMillis]) and stops when the transfer is not [isActive] or motion is reduced.
 * Shared by the Transfers rows and the transfer detail pane.
 */
@Composable
internal fun TransferProgressBar(
    fraction: State<Float>,
    fillTint: Color,
    isActive: Boolean,
    speedBytesPerSec: Long,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    barHeight: Dp = FlashDimensions.borderHairline * 4,
) {
    val ultraLow = FlashTheme.ultraLowUi
    val skipShimmer = ultraLow || reduceMotion

    val shimmerProgress = if (isActive && speedBytesPerSec > 0 && !skipShimmer) {
        val durationMillis = remember(speedBytesPerSec) {
            FlashTransfersMath.shimmerDurationMillis(speedBytesPerSec)
        }
        val transition = rememberInfiniteTransition(label = "transferShimmer")
        transition.animateFloat(
            initialValue = -0.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmerSweep",
        )
    } else {
        null
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(FlashShapes.bubbleGrouped)
            .background(FlashTheme.colors.backgroundSurfaceSubtle),
    ) {
        val baseBarModifier = Modifier
            // Mirrors `fillMaxWidth(fraction)` exactly — same `roundToInt`, same
            // `coerceIn(minWidth, maxWidth)` as Compose's own FillNode — but in the
            // layout phase, so the progress tween never touches composition (EXP-013).
            .layout { measurable, constraints ->
                val width = FlashTransfersMath.progressBarWidthPx(
                    minWidthPx = constraints.minWidth,
                    maxWidthPx = constraints.maxWidth,
                    fraction = fraction.value,
                )
                val placeable = measurable.measure(
                    constraints.copy(minWidth = width, maxWidth = width),
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .fillMaxSize()
            .clip(FlashShapes.bubbleGrouped)
            .background(fillTint)

        val fillModifier = if (skipShimmer || shimmerProgress == null) {
            baseBarModifier
        } else {
            // The gradient is built once per bar size and only translated each frame; building a new Brush (a new shader) per
            // frame for every active row was the heavier cost of this animation.
            baseBarModifier.drawWithCache {
                val barWidth = size.width
                val shimmerWidth = (barWidth * 0.45f).coerceAtLeast(40f)
                val brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.35f),
                        Color.Transparent,
                    ),
                    start = Offset(-shimmerWidth, 0f),
                    end = Offset(shimmerWidth, size.height),
                )
                val band = Size(shimmerWidth * 2f, size.height)
                onDrawWithContent {
                    drawContent()
                    if (barWidth > 0f) {
                        translate(left = barWidth * shimmerProgress.value) {
                            drawRect(brush = brush, topLeft = Offset(-shimmerWidth, 0f), size = band)
                        }
                    }
                }
            }
        }

        Box(fillModifier)
    }
}

@Composable
private fun RowIcon(
    icon: com.transfer.flash.ui.icons.FlashIconSpec,
    description: String,
    tint: Color = FlashTheme.colors.textSecondary,
    onClick: () -> Unit,
) {
    val haptics = rememberFlashHaptics()
    Box(
        Modifier
            .size(FlashDimensions.minTouchTarget)
            .clickable(role = Role.Button, onClickLabel = description) {
                haptics(FlashHaptic.Tick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            contentDescription = description,
            tint = tint,
            size = FlashDimensions.iconMd,
        )
    }
}

/**
 * Compact extension badge reusing UI-016's color language (fileCategoryColorFor) without
 * the in-bubble interactive overlays — row-level controls own the actions on this surface.
 */
@Composable
private fun TransferBadge(fileName: String) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val extension = remember(fileName) { extensionOf(fileName) }
    val categoryColor = remember(extension) { fileCategoryColorFor(extension) }
    // The extension is a decorative marker; keep it inside its fixed circle at large text sizes.
    // The full filename still scales normally and remains in the row semantics.
    val fontScale = LocalDensity.current.fontScale
    val badgeTextScale = fontScale.coerceAtMost(1.2f) / fontScale

    Box(
        Modifier
            .size(FlashDimensions.avatarSm)
            .clip(CircleShape)
            .background(categoryColor.copy(alpha = 0.9f))
            .border(FlashDimensions.borderHairline, colors.borderSubtle.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (extension.isNotEmpty() && extension.length <= 4) {
            FlashText(
                text = extension.uppercase(),
                style = typography.captionEmphasis.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp * badgeTextScale,
                    letterSpacing = 0.5.sp * badgeTextScale,
                ),
                color = Color.White,
            )
        } else {
            FlashIcon(
                icon = FlashIcons.Upload,
                contentDescription = null,
                tint = Color.White,
                size = FlashDimensions.iconMd,
            )
        }
    }
}

private fun extensionOf(fileName: String): String =
    fileName.substringAfterLast('.', "").takeIf { it.length <= 4 && it != fileName } ?: ""
