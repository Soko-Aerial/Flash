package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.transfer.flash.core.messaging.model.FlashMessageGroupPosition
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.launch

/**
 * Conversation message list (UI-005 bubbles, UI-006 insertion, UI-007 selection).
 */
@Composable
fun FlashMessageList(
    messages: List<FlashMessageUi>,
    onOpenMessageActions: (FlashMessageUi) -> Unit,
    modifier: Modifier = Modifier,
    selectedMessageIds: Set<String> = emptySet(),
    inSelectionMode: Boolean = false,
    onSelectToggle: (String) -> Unit = {},
    onToggleReaction: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onReplySwipe: (FlashMessageUi) -> Unit = {},
    onJumpToMessage: (String) -> Unit = {},
    onImageClick: (message: FlashMessageUi, index: Int) -> Unit = { _, _ -> },
    onFileClick: (message: FlashMessageUi, file: com.transfer.flash.core.messaging.model.FlashFileAttachmentUi) -> Unit = { _, _ -> },
    highlightedMessageId: String? = null,
    peerTypingName: String? = null,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(
        top = FlashSpacing.space12,
        bottom = FlashSpacing.space16,
    ),
    /** Group chats show sender headers; direct chats keep timestamps in-bubble (UI-005). */
    showSenderHeaders: Boolean = true,
    /** UI-023: active in-chat search query — matching substrings are highlighted in bubbles. */
    searchQuery: String? = null,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val coroutineScope = rememberCoroutineScope()

    val initialMessageIds = remember { messages.mapTo(HashSet()) { it.id } }

    val stickThresholdPx = with(LocalDensity.current) { FlashDimensions.chatBottomStickThreshold.toPx() }
    val atBottom = remember(stickThresholdPx) {
        derivedStateOf {
            isAtBottom(
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                thresholdPx = stickThresholdPx,
            )
        }
    }

    // --- UI-021 unseen tracking: arrivals while scrolled up increment the pill counter. ---
    var previousTailId by remember { mutableStateOf(messages.lastOrNull()?.id) }
    var unseenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(messages) {
        val tailId = messages.lastOrNull()?.id
        if (tailId != null && tailId != previousTailId && tailId !in initialMessageIds) {
            val wasAtBottom = atBottom.value
            unseenCount = FlashChatScrollMath.nextUnseenCount(
                current = unseenCount,
                isNewTailMessage = FlashChatScrollMath.isNewTailMessage(previousTailId, tailId),
                wasAtBottom = wasAtBottom,
                isMine = messages.lastOrNull()?.isMine ?: false,
            )
        }
        if (tailId != null) previousTailId = tailId
    }
    LaunchedEffect(atBottom.value) {
        if (atBottom.value) unseenCount = 0
    }

    val newestMessageId = messages.lastOrNull()?.id
    LaunchedEffect(newestMessageId) {
        if (newestMessageId == null || newestMessageId in initialMessageIds) return@LaunchedEffect
        val newest = messages.last()
        if (shouldAutoScrollToNewMessage(isMine = newest.isMine, atBottom = atBottom.value)) {
            if (motion.reduceMotion) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }

    val ordered = messages.asReversed()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.backgroundChat)
                .padding(horizontal = FlashSpacing.space12),
            state = listState,
            contentPadding = contentPadding,
            reverseLayout = true,
        ) {
        if (peerTypingName != null) {
            item(key = "flash_typing_bubble", contentType = "typingBubble") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FlashSpacing.space8),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    FlashTypingBubble(peerName = peerTypingName)
                }
            }
        }

        itemsIndexed(
            items = ordered,
            key = { _, message -> message.id },
            contentType = { _, _ -> "flashMessage" },
        ) { layoutIndex, message ->
            val rendered = if (showSenderHeaders) message else message.copy(showSenderHeader = false)
            val animateEnter = shouldAnimateMessageEnter(
                messageId = message.id,
                layoutIndex = layoutIndex,
                initialMessageIds = initialMessageIds,
            )
            val enterProgress by motion.rememberMessageEnterProgress(animate = animateEnter)

            val spacingBelow = when (message.groupPosition) {
                FlashMessageGroupPosition.TOP,
                FlashMessageGroupPosition.MIDDLE,
                -> FlashSpacing.space4

                FlashMessageGroupPosition.BOTTOM,
                FlashMessageGroupPosition.SINGLE,
                -> FlashSpacing.space12
            }

            val isSelected = message.id in selectedMessageIds
            val isHighlighted = message.id == highlightedMessageId

            FlashMessageBubble(
                message = rendered,
                onOpenActions = { onOpenMessageActions(message) },
                isSelected = isSelected,
                inSelectionMode = inSelectionMode,
                onSelectToggle = { onSelectToggle(message.id) },
                onToggleReaction = { emoji -> onToggleReaction(message.id, emoji) },
                onReplySwipe = { onReplySwipe(message) },
                onJumpToMessage = onJumpToMessage,
                onImageClick = { index, image -> onImageClick(message, index) },
                onFileClick = { file -> onFileClick(message, file) },
                isHighlighted = isHighlighted,
                searchQuery = searchQuery,
                modifier = Modifier
                    .padding(bottom = spacingBelow)
                    .animateItem(
                        placementSpec = motion.messagePlacementSpec(),
                        fadeOutSpec = motion.messageFadeOutSpec(),
                    )
                    .graphicsLayer {
                        alpha = enterProgress
                        translationY = (1f - enterProgress) * (size.height / 4f)
                        val s = 0.96f + (0.04f * enterProgress)
                        scaleX = s
                        scaleY = s
                    },
            )
        }
    }

        // UI-021 / UI-022 floating "new messages" jump pill (bottom-center, above composer).
        AnimatedVisibility(
            visible = FlashChatScrollMath.shouldShowNewMessagesPill(unseenCount),
            enter = fadeIn(motion.tweenNormalSpec()) +
                slideInVertically(motion.tweenNormalSpec()) { fullHeight -> fullHeight / 4 },
            exit = fadeOut(motion.tweenFastSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FlashSpacing.space16),
        ) {
            FlashNewMessagesPill(
                count = unseenCount,
                onClick = {
                    coroutineScope.launch {
                        if (motion.reduceMotion) {
                            listState.scrollToItem(0)
                        } else {
                            listState.animateScrollToItem(0)
                        }
                        unseenCount = 0
                    }
                },
            )
        }
    }
}

/**
 * UI-022 Floating "N new messages" pill — accent surface with a down-chevron
 * (Flash-owned back glyph rotated −90°; no new icon needed).
 */
@Composable
internal fun FlashNewMessagesPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Row(
        modifier = modifier
            .clip(FlashShapes.avatar)
            .background(colors.accentPrimary)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                contentDescription = "Jump to " + FlashChatScrollMath.pillLabel(count)
                // UI-038: unseen counter updates while the pill is visible — announce politely.
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        FlashIcon(
            icon = FlashIcons.Back,
            contentDescription = null,
            tint = colors.textOnAccent,
            size = FlashDimensions.iconSm,
            modifier = Modifier.graphicsLayer { rotationZ = -90f },
        )
        FlashText(
            text = FlashChatScrollMath.pillLabel(count),
            style = typography.metadataEmphasis,
            color = colors.textOnAccent,
        )
    }
}

/** Pure scroll math for the conversation list (UI-021 / UI-022). Unit-tested. */
object FlashChatScrollMath {
    /** Counter transition for one list update. Own sends auto-scroll → reset; at bottom → reset. */
    fun nextUnseenCount(
        current: Int,
        isNewTailMessage: Boolean,
        wasAtBottom: Boolean,
        isMine: Boolean,
    ): Int = when {
        wasAtBottom || isMine -> 0
        isNewTailMessage -> current + 1
        else -> current
    }

    /** A tail-id change is an arrival; reaction/edit updates keep ids stable. */
    fun isNewTailMessage(previousTailId: String?, currentTailId: String?): Boolean =
        previousTailId != currentTailId && currentTailId != null

    fun shouldShowNewMessagesPill(unseenCount: Int): Boolean = unseenCount > 0

    fun pillLabel(count: Int): String =
        "$count new message${if (count == 1) "" else "s"}"
}

internal fun shouldAnimateMessageEnter(
    messageId: String,
    layoutIndex: Int,
    initialMessageIds: Set<String>,
): Boolean {
    if (messageId in initialMessageIds) return false
    return layoutIndex == 0
}

internal fun shouldAutoScrollToNewMessage(
    isMine: Boolean,
    atBottom: Boolean,
): Boolean {
    return isMine || atBottom
}

internal fun isAtBottom(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    thresholdPx: Float,
): Boolean {
    if (firstVisibleItemIndex != 0) return false
    return firstVisibleItemScrollOffset <= thresholdPx
}
