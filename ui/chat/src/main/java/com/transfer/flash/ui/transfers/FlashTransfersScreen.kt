package com.transfer.flash.ui.transfers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.chat.fileCategoryColorFor
import com.transfer.flash.ui.chat.formatFileSize
import com.transfer.flash.ui.chat.FlashEmptyState
import com.transfer.flash.ui.chat.FlashStateCopy
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import com.transfer.flash.ui.theme.rememberFlashHaptics

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
)

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

    const val PROGRESS_THROTTLE_MS = 250L

    /** Fraction 0..1 clamped; zero total bytes never divides. */
    fun progressFraction(bytesDone: Long, bytesTotal: Long): Float =
        if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024L * 1024L -> "${(bytesPerSec / (1024f * 1024f) * 10).toInt() / 10.0} MB/s"
        bytesPerSec > 0L -> "${bytesPerSec / 1024L} KB/s"
        else -> ""
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
        .filter { it.state == FlashTransferState.Active }
        .sumOf { it.speedBytesPerSec.coerceAtLeast(0L) }

    fun directionLabel(item: FlashTransferItemUi): String = when (item.direction) {
        FlashTransferDirection.Send -> "To"
        FlashTransferDirection.Receive -> "From"
    }

    fun statusLine(item: FlashTransferItemUi): String = when (item.state) {
        FlashTransferState.Offered -> "Wants to send you this file"
        FlashTransferState.Queued -> "Queued"
        FlashTransferState.Paused -> "Paused"
        FlashTransferState.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: "Failed"
        FlashTransferState.Completed -> if (item.verified) "Verified" else "Completed"
        FlashTransferState.Active -> listOfNotNull(
            formatSpeed(item.speedBytesPerSec).takeIf { it.isNotEmpty() },
            formatEta(item.etaSeconds).takeIf { it.isNotEmpty() },
        ).joinToString(" · ").ifBlank { "Transferring" }
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
) {
    // Every branch clears the status bar: this page is its own top-level surface and has no
    // top bar of its own to own that inset (Chats/Conversation do it in their headers).
    val surface = modifier.fillMaxSize().statusBarsPadding()
    val statusSwap = FlashTheme.motion.statusCrossfade()
    // Crossfade on the *branch*, not on `state`: the populated branch re-emits on every
    // progress tick and must not restart a transition.
    AnimatedContent(
        targetState = state.pageState(),
        transitionSpec = { statusSwap },
        label = "transfersPageState",
    ) { page ->
        when (page) {
            TransfersPageState.Error -> ErrorPanel(surface)
            TransfersPageState.Loading -> LoadingRows(surface)
            TransfersPageState.Empty -> FlashEmptyState(
                kind = FlashStateCopy.EmptyKind.TransfersFirstRun,
                modifier = surface,
                onAction = onFindDevices,
            )
            TransfersPageState.Populated -> PopulatedSections(
                state = state,
                onPauseResumeClick = onPauseResumeClick,
                onCancelClick = onCancelClick,
                onRetryClick = onRetryClick,
                onHistoryOpen = onHistoryOpen,
                onHistoryShare = onHistoryShare,
                onAcceptOffer = onAcceptOffer,
                onDeclineOffer = onDeclineOffer,
                modifier = surface,
                listState = listState,
                bottomInset = bottomInset,
            )
        }
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
private fun ErrorPanel(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(top = FlashSpacing.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlashText(
            text = "Transfers unavailable",
            style = FlashTheme.typography.headingSmall,
            color = FlashTheme.colors.textPrimary,
        )
        Spacer(Modifier.height(FlashSpacing.space4))
        FlashText(
            text = "Something went wrong while loading transfer state.",
            style = FlashTheme.typography.metadataDefault,
            color = FlashTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LoadingRows(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = FlashSpacing.space16)) {
        repeat(4) {
            Box(
                Modifier
                    .padding(vertical = FlashSpacing.space8)
                    .fillMaxWidth()
                    .height(FlashDimensions.chatListRowHeight)
                    .clip(RoundedCornerShape(FlashShapes.radius12))
                    .background(FlashTheme.colors.backgroundSurfaceSubtle),
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
    modifier: Modifier,
    listState: LazyListState,
    bottomInset: Dp,
) {
    val statusSwap = FlashTheme.motion.statusCrossfade()
    val motion = FlashTheme.motion
    // Rows are keyed by transfer id and hop between sections as state changes
    // (Active → Failed → History), so placement is animated rather than snapping.
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = FlashSpacing.space16,
            end = FlashSpacing.space16,
            top = FlashSpacing.space12,
            bottom = FlashSpacing.space12 + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        item(key = "header") { HeaderWithChips(state) }
        if (state.offers.isNotEmpty()) {
            item(key = "label-offers") { SectionLabel("INCOMING OFFERS") }
            items(state.offers, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    modifier = Modifier.animateItem(
                        placementSpec = motion.messagePlacementSpec(),
                        fadeOutSpec = motion.messageFadeOutSpec(),
                    ),
                    trailing = {
                        RowIcon(
                            icon = FlashIcons.Check,
                            description = "Accept",
                            onClick = { onAcceptOffer(item) },
                        )
                        Spacer(Modifier.width(FlashSpacing.space8))
                        RowIcon(
                            icon = FlashIcons.Close,
                            description = "Decline",
                            onClick = { onDeclineOffer(item) },
                        )
                    },
                )
            }
        }
        if (state.active.isNotEmpty()) {
            item(key = "label-active") { SectionLabel("ACTIVE") }
            items(state.active, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    modifier = Modifier.animateItem(
                        placementSpec = motion.messagePlacementSpec(),
                        fadeOutSpec = motion.messageFadeOutSpec(),
                    ),
                    trailing = {
                        AnimatedContent(
                            targetState = item.state == FlashTransferState.Paused,
                            transitionSpec = { statusSwap },
                            label = "pauseResumeSwap",
                        ) { isPaused ->
                            RowIcon(
                                icon = if (isPaused) FlashIcons.Play else FlashIcons.Pause,
                                description = if (isPaused) "Resume" else "Pause",
                                onClick = { onPauseResumeClick(item) },
                            )
                        }
                        Spacer(Modifier.width(FlashSpacing.space8))
                        RowIcon(
                            icon = FlashIcons.Close,
                            description = "Cancel",
                            onClick = { onCancelClick(item) },
                        )
                    },
                )
            }
        }
        if (state.failed.isNotEmpty()) {
            item(key = "label-failed") { SectionLabel("FAILED") }
            items(state.failed, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    modifier = Modifier.animateItem(
                        placementSpec = motion.messagePlacementSpec(),
                        fadeOutSpec = motion.messageFadeOutSpec(),
                    ),
                    trailing = {
                        RowIcon(
                            icon = FlashIcons.Retry,
                            description = "Retry",
                            onClick = { onRetryClick(item) },
                        )
                    },
                )
            }
        }
        if (state.history.isNotEmpty()) {
            item(key = "label-history") { SectionLabel("HISTORY") }
            items(state.history, key = { it.id }) { item ->
                TransferRow(
                    item = item,
                    modifier = Modifier.animateItem(
                        placementSpec = motion.messagePlacementSpec(),
                        fadeOutSpec = motion.messageFadeOutSpec(),
                    ),
                    trailing = {
                        RowIcon(
                            icon = FlashIcons.Share,
                            description = "Share",
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
private fun HeaderWithChips(state: TransfersUiState) {
    val motion = FlashTheme.motion
    // Aggregate throughput ROLLS to its new value instead of snapping, so a burst reads as
    // acceleration and a pause reads as a drop. animateFloatAsState is a single value animation
    // (not a per-row one), and the label it produces is re-read only when the rounded text
    // actually changes.
    val rolledSpeed by animateFloatAsState(
        targetValue = FlashTransfersMath.aggregateSpeed(state.active).toFloat(),
        animationSpec = if (motion.reduceMotion) snap() else motion.tweenNormalSpec(),
        label = "transfersThroughputRoll",
    )
    val throughputLabel = FlashTransfersMath.formatSpeed(rolledSpeed.toLong())

    Column {
        FlashText(
            text = "Transfers",
            style = FlashTheme.typography.headingMedium,
            color = FlashTheme.colors.textPrimary,
        )
        AnimatedVisibility(visible = throughputLabel.isNotEmpty()) {
            FlashText(
                text = "$throughputLabel total",
                style = FlashTheme.typography.numericDefault,
                color = FlashTheme.colors.textSecondary,
            )
        }
        AnimatedVisibility(visible = state.failed.isNotEmpty()) {
            Box(
                Modifier
                    .padding(top = FlashSpacing.space8)
                    .clip(FlashShapes.chip)
                    .background(FlashTheme.colors.textError.copy(alpha = 0.12f))
                    .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space2),
            ) {
                FlashText(
                    text = "${state.failed.size} failed",
                    style = FlashTheme.typography.captionDefault,
                    color = FlashTheme.colors.textError,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    FlashText(
        text = text,
        style = FlashTheme.typography.captionEmphasis,
        color = FlashTheme.colors.textTertiary,
        modifier = Modifier.padding(top = FlashSpacing.space8),
    )
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
    val fraction by animateFloatAsState(
        targetValue = FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal),
        animationSpec = FlashTheme.motion.tweenNormalSpec(),
        label = "transferProgress",
    )
    val fillTint = when (item.state) {
        FlashTransferState.Offered -> colors.statusTransfer
        FlashTransferState.Paused, FlashTransferState.Queued -> colors.statusTransfer
        FlashTransferState.Failed -> colors.textError
        else -> colors.accentPrimary
    }

    Row(
        modifier
            .fillMaxWidth()
            .flashPressScale(interactionSource)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .background(colors.backgroundSurface)
            .let { base ->
                if (onRowClick != null) {
                    base.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClickLabel = "Open",
                        onClick = onRowClick,
                    )
                } else {
                    base
                }
            }
            .padding(FlashSpacing.space12)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(item.fileName)
                    append(", ")
                    append(when (item.direction) {
                        FlashTransferDirection.Send -> "Sending"
                        FlashTransferDirection.Receive -> "Receiving"
                    })
                    append(", ${(fraction * 100).toInt()} percent")
                    append(", ${FlashTransfersMath.statusLine(item)}")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransferBadge(
            fileName = item.fileName,
            bytesTotal = item.bytesTotal,
        )
        Spacer(Modifier.width(FlashSpacing.space12))
        Column(Modifier.weight(1f)) {
            FlashText(
                text = item.fileName,
                style = FlashTheme.typography.bodyDefault,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = "${FlashTransfersMath.directionLabel(item)} ${item.peerName}" +
                    item.transportLabel?.let { " · $it" }.orEmpty(),
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(top = FlashSpacing.space4)
                    .fillMaxWidth()
                    .height(FlashDimensions.borderHairline * 4)
                    .clip(FlashShapes.bubbleGrouped)
                    .background(colors.backgroundSurfaceSubtle),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxSize()
                        .clip(FlashShapes.bubbleGrouped)
                        .background(fillTint),
                )
            }
            FlashText(
                text = FlashTransfersMath.statusLine(item),
                style = FlashTheme.typography.metadataDefault,
                color = if (item.state == FlashTransferState.Failed) colors.textError else colors.textTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(FlashSpacing.space8))
        Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
private fun RowIcon(icon: com.transfer.flash.ui.icons.FlashIconSpec, description: String, onClick: () -> Unit) {
    val haptics = rememberFlashHaptics()
    Box(
        Modifier
            .size(FlashDimensions.minTouchTarget)
            .clickable(onClickLabel = description) {
                haptics(FlashHaptic.Tick)
                onClick()
            }
            .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        FlashIcon(
            icon = icon,
            contentDescription = description,
            tint = FlashTheme.colors.textSecondary,
            size = FlashDimensions.iconMd,
        )
    }
}

/**
 * Static 48dp extension badge reusing UI-016's color language (fileCategoryColorFor) without
 * the in-bubble interactive overlays — row-level controls own the actions on this surface.
 */
@Composable
private fun TransferBadge(fileName: String, bytesTotal: Long) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val extension = remember(fileName) { extensionOf(fileName) }
    val categoryColor = remember(extension) { fileCategoryColorFor(extension) }

    Box(
        Modifier
            .size(FlashDimensions.avatarLg)
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
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
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
