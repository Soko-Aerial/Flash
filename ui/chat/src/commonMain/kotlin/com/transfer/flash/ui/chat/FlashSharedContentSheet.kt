package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.rememberFlashAudioPlayer
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlinx.coroutines.delay

/**
 * Task 3.3: Per-Conversation Shared Content Viewer.
 *
 * Provides a dedicated modal sheet organized into 4 content tabs:
 * - Media: 3-column photo/video thumbnail grid with video play badge; tap opens FlashMediaViewer.
 * - Files: Document list with extension badges, file sizes, and download/open actions.
 * - Audio: Voice notes with duration and inline play controls.
 * - Links: Extracted HTTP and flash:// invite links with jump-to-chat actions.
 */
@Composable
public fun FlashSharedContentSheet(
    title: String,
    messages: List<FlashMessageUi>,
    onDismiss: () -> Unit,
    onOpenMedia: (index: Int, items: List<FlashMediaViewerItem>) -> Unit,
    onOpenFile: (localUri: String?, mimeType: String, fileName: String) -> Unit,
    onJumpToMessage: (messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    onDownloadFile: (transferId: String) -> Unit = {},
    onOpenUrl: (url: String) -> Unit = {},
    onJoinInviteGroup: ((inviteUri: String) -> Unit)? = null,
    onResumeTransfer: (transferId: String) -> Unit = onDownloadFile,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val mediaItems = remember(messages) { FlashSharedContentMath.extractMedia(messages) }
    val fileItems = remember(messages) { FlashSharedContentMath.extractFiles(messages) }
    val audioItems = remember(messages) { FlashSharedContentMath.extractAudio(messages) }
    val linkItems = remember(messages) { FlashSharedContentMath.extractLinks(messages) }
    val messageIsMine = remember(messages) { messages.associate { it.id to it.isMine } }

    var selectedTab by remember { mutableStateOf(FlashSharedContentTab.Media) }

    // UI-023/UI-024 upgrade: the sheet used to be a read-only gallery — finding one file among
    // hundreds meant scrolling. One query now filters whichever tab is open (file names, media
    // captions, link URLs/domains/snippets, and the sender for voice notes).
    var searchQuery by remember { mutableStateOf("") }
    val filteredMedia = remember(mediaItems, searchQuery) {
        FlashSharedContentMath.filterMedia(mediaItems, searchQuery)
    }
    val filteredFiles = remember(fileItems, searchQuery) {
        FlashSharedContentMath.filterFiles(fileItems, searchQuery)
    }
    val filteredAudio = remember(audioItems, searchQuery) {
        FlashSharedContentMath.filterAudio(audioItems, searchQuery)
    }
    val filteredLinks = remember(linkItems, searchQuery) {
        FlashSharedContentMath.filterLinks(linkItems, searchQuery)
    }

    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.backgroundSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 640.dp)
                .padding(bottom = FlashSpacing.space16),
        ) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlashText(
                        text = "Shared Content",
                        style = FlashTheme.typography.headingMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlashText(
                        text = title,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        contentDescription = "Close shared content",
                        tint = colors.textSecondary,
                    )
                }
            }

            // Tabs row (horizontally scrollable so labels never clip on narrow screens)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashSharedContentTab.entries.forEach { tab ->
                    val count = when (tab) {
                        FlashSharedContentTab.Media -> filteredMedia.size
                        FlashSharedContentTab.Files -> filteredFiles.size
                        FlashSharedContentTab.Audio -> filteredAudio.size
                        FlashSharedContentTab.Links -> filteredLinks.size
                    }
                    val isSelected = tab == selectedTab

                    Box(
                        modifier = Modifier
                            .defaultMinSize(minHeight = FlashDimensions.minTouchTarget)
                            .clip(FlashShapes.chip)
                            .background(
                                if (isSelected) colors.accentFill.copy(alpha = 0.15f)
                                else colors.backgroundSurfaceStrong,
                            )
                            .border(
                                width = FlashDimensions.borderHairline,
                                color = if (isSelected) colors.accentFill else colors.borderSubtle,
                                shape = FlashShapes.chip,
                            )
                            .clickable {
                                haptics(FlashHaptic.Tick)
                                selectedTab = tab
                            }
                            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(
                            text = FlashSharedContentMath.tabLabelWithCount(tab, count),
                            style = FlashTheme.typography.captionEmphasis,
                            color = if (isSelected) colors.accentFill else colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Search within this conversation's shared content. Filters whichever tab is open.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space4)
                    .clip(FlashShapes.chip)
                    .background(colors.backgroundSurfaceStrong)
                    .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.chip)
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(
                    icon = FlashIcons.Search,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    size = FlashDimensions.iconSm,
                )
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = FlashTheme.typography.bodyDefault.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accentPrimary),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            FlashText(
                                text = "Search shared content",
                                style = FlashTheme.typography.bodyDefault,
                                color = colors.textTertiary,
                            )
                        }
                        innerTextField()
                    },
                )
                if (searchQuery.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(FlashDimensions.iconLg)
                            .clickable {
                                haptics(FlashHaptic.Tick)
                                searchQuery = ""
                            }
                            .semantics {
                                role = Role.Button
                                contentDescription = "Clear shared-content search"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Close,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            size = FlashDimensions.iconSm,
                        )
                    }
                }
            }

            // Tab Content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(motion.tweenFastSpec()) togetherWith fadeOut(motion.tweenFastSpec())
                    },
                    label = "shared_content_tab_content",
                ) { currentTab ->
                    when (currentTab) {
                        FlashSharedContentTab.Media -> {
                            if (mediaItems.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Gallery,
                                    title = "No media shared yet",
                                    subtitle = "Photos and videos sent in this chat will appear here.",
                                )
                            } else if (filteredMedia.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Search,
                                    title = "No media matches",
                                    subtitle = "Nothing in Media matches \"$searchQuery\".",
                                )
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(FlashSpacing.space4),
                                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                                ) {
                                    itemsIndexed(filteredMedia, key = { _, item -> item.image.id }) { index, item ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f),
                                        ) {
                                            FlashImageTile(
                                                image = item.image,
                                                shape = RoundedCornerShape(4.dp),
                                                onClick = {
                                                    val viewerList = filteredMedia.map {
                                                        FlashMediaViewerItem(
                                                            image = it.image,
                                                            senderName = it.senderName,
                                                            timeLabel = it.timeLabel,
                                                        )
                                                    }
                                                    onOpenMedia(index, viewerList)
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        FlashSharedContentTab.Files -> {
                            if (fileItems.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Attach,
                                    title = "No files shared yet",
                                    subtitle = "Documents, archives, and files will appear here.",
                                )
                            } else if (filteredFiles.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Search,
                                    title = "No files match",
                                    subtitle = "Nothing in Files matches \"$searchQuery\".",
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                ) {
                                    items(filteredFiles, key = { it.file.id }) { item ->
                                        SharedFileRow(
                                            item = item,
                                            isOutgoing = messageIsMine[item.messageId] == true,
                                            onOpenFile = { onOpenFile(item.file.localUri, item.file.mimeType, item.file.name) },
                                            onDownload = { onDownloadFile(item.file.id) },
                                            onResume = { onResumeTransfer(item.file.id) },
                                            onJumpToMessage = { onJumpToMessage(item.messageId) },
                                        )
                                    }
                                }
                            }
                        }

                        FlashSharedContentTab.Audio -> {
                            if (audioItems.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Microphone,
                                    title = "No voice messages yet",
                                    subtitle = "Voice notes and audio clips will appear here.",
                                )
                            } else if (filteredAudio.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Search,
                                    title = "No voice messages match",
                                    subtitle = "Nothing in Audio matches \"$searchQuery\".",
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                ) {
                                    items(filteredAudio, key = { it.voice.id }) { item ->
                                        SharedAudioRow(
                                            item = item,
                                            isOutgoing = messageIsMine[item.messageId] == true,
                                            onDownload = { onDownloadFile(item.voice.id) },
                                            onResume = { onResumeTransfer(item.voice.id) },
                                            onJumpToMessage = { onJumpToMessage(item.messageId) },
                                        )
                                    }
                                }
                            }
                        }

                        FlashSharedContentTab.Links -> {
                            if (linkItems.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Share,
                                    title = "No links shared yet",
                                    subtitle = "Web addresses and group invite links will appear here.",
                                )
                            } else if (filteredLinks.isEmpty()) {
                                EmptySharedState(
                                    icon = FlashIcons.Search,
                                    title = "No links match",
                                    subtitle = "Nothing in Links matches \"$searchQuery\".",
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                ) {
                                    items(filteredLinks, key = { "${it.messageId}_${it.url}" }) { item ->
                                        SharedLinkRow(
                                            item = item,
                                            onOpenUrl = {
                                                if (item.isInvite && onJoinInviteGroup != null) {
                                                    onJoinInviteGroup(item.url)
                                                } else {
                                                    onOpenUrl(item.url)
                                                }
                                            },
                                            onJumpToMessage = { onJumpToMessage(item.messageId) },
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
}

internal enum class FlashSharedTransferAction { Open, Download, Resume, None }

internal fun resolveSharedFileAction(
    status: FlashFileTransferStatus,
    hasLocalUri: Boolean,
    isOutgoing: Boolean,
): FlashSharedTransferAction = when {
    hasLocalUri && status == FlashFileTransferStatus.Downloaded -> FlashSharedTransferAction.Open
    status == FlashFileTransferStatus.Failed || status == FlashFileTransferStatus.Paused -> FlashSharedTransferAction.Resume
    status == FlashFileTransferStatus.NotDownloaded -> FlashSharedTransferAction.Download
    status == FlashFileTransferStatus.AwaitingAcceptance && !isOutgoing -> FlashSharedTransferAction.Download
    else -> FlashSharedTransferAction.None
}

internal fun sharedFileStatusNote(
    status: FlashFileTransferStatus,
    hasLocalUri: Boolean,
    isOutgoing: Boolean,
): String? = when {
    hasLocalUri && status == FlashFileTransferStatus.Downloaded -> null
    status == FlashFileTransferStatus.Transferring -> "Downloading…"
    status == FlashFileTransferStatus.Failed -> "Failed · Tap to retry"
    status == FlashFileTransferStatus.Paused -> "Paused · Tap to resume"
    status == FlashFileTransferStatus.Cancelled -> "Cancelled"
    status == FlashFileTransferStatus.AwaitingAcceptance ->
        if (isOutgoing) "Waiting for recipient" else "Tap to download"
    else -> "Tap to download"
}

internal fun resolveSharedAudioAction(
    status: FlashFileTransferStatus,
    hasUri: Boolean,
    isOutgoing: Boolean,
): FlashSharedTransferAction = when {
    hasUri && status == FlashFileTransferStatus.Downloaded -> FlashSharedTransferAction.Open
    status == FlashFileTransferStatus.Failed || status == FlashFileTransferStatus.Paused -> FlashSharedTransferAction.Resume
    status == FlashFileTransferStatus.NotDownloaded -> FlashSharedTransferAction.Download
    status == FlashFileTransferStatus.AwaitingAcceptance && !isOutgoing -> FlashSharedTransferAction.Download
    else -> FlashSharedTransferAction.None
}

@Composable
private fun SharedFileRow(
    item: FlashSharedFileItem,
    isOutgoing: Boolean,
    onOpenFile: () -> Unit,
    onDownload: () -> Unit,
    onResume: () -> Unit,
    onJumpToMessage: () -> Unit,
) {
    val colors = FlashTheme.colors
    val file = item.file
    val extensionBadge = remember(file.name) { FlashSharedContentMath.badgeForExtension(file.name) }
    val sizeText = remember(file.sizeBytes) { FlashSharedContentMath.formatFileSize(file.sizeBytes) }
    val action = resolveSharedFileAction(
        status = file.transferStatus,
        hasLocalUri = file.localUri != null,
        isOutgoing = isOutgoing,
    )
    val statusNote = sharedFileStatusNote(
        status = file.transferStatus,
        hasLocalUri = file.localUri != null,
        isOutgoing = isOutgoing,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.attachment)
            .background(colors.backgroundSurfaceStrong)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .clickable(enabled = action != FlashSharedTransferAction.None) {
                when (action) {
                    FlashSharedTransferAction.Open -> onOpenFile()
                    FlashSharedTransferAction.Resume -> onResume()
                    FlashSharedTransferAction.Download -> onDownload()
                    FlashSharedTransferAction.None -> Unit
                }
            }
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        // Extension badge
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentFill.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = extensionBadge,
                style = FlashTheme.typography.captionEmphasis.copy(fontWeight = FontWeight.Bold),
                color = colors.accentFill,
            )
        }

        // File info
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = file.name,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = listOfNotNull(sizeText, statusNote, item.senderName, item.timeLabel).joinToString(" • "),
                style = FlashTheme.typography.metadataDefault,
                color = if (file.transferStatus == FlashFileTransferStatus.Failed) colors.textError else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Actions
        if (action == FlashSharedTransferAction.Download || action == FlashSharedTransferAction.Resume) {
            IconButton(
                onClick = { if (action == FlashSharedTransferAction.Resume) onResume() else onDownload() },
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(
                    icon = if (file.transferStatus == FlashFileTransferStatus.Failed) FlashIcons.Retry else FlashIcons.Download,
                    contentDescription = when (file.transferStatus) {
                        FlashFileTransferStatus.Failed -> "Retry download"
                        FlashFileTransferStatus.Paused -> "Resume download"
                        else -> "Download file"
                    },
                    tint = colors.accentFill,
                    size = FlashDimensions.iconSm,
                )
            }
        }

        IconButton(
            onClick = onJumpToMessage,
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
        ) {
            FlashIcon(
                icon = FlashIcons.Chat,
                contentDescription = "Jump to message",
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }
    }
}

@Composable
private fun SharedAudioRow(
    item: FlashSharedAudioItem,
    isOutgoing: Boolean,
    onDownload: () -> Unit,
    onResume: () -> Unit,
    onJumpToMessage: () -> Unit,
) {
    val colors = FlashTheme.colors
    val voice = item.voice
    val action = resolveSharedAudioAction(
        status = voice.transferStatus,
        hasUri = voice.uri != null,
        isOutgoing = isOutgoing,
    )
    val hasAudio = action == FlashSharedTransferAction.Open
    // H-23: a note that is not on this device cannot play; its button downloads/resumes it (or waits, while it is already coming).
    val inFlight = voice.transferStatus == FlashFileTransferStatus.Transferring
    val isCancelled = voice.transferStatus == FlashFileTransferStatus.Cancelled
    val isOutgoingAwaiting = isOutgoing && voice.transferStatus == FlashFileTransferStatus.AwaitingAcceptance
    val canTriggerTransfer = action == FlashSharedTransferAction.Download || action == FlashSharedTransferAction.Resume
    val audioPlayer = rememberFlashAudioPlayer(uri = if (hasAudio) voice.uri else null)

    DisposableEffect(audioPlayer) {
        onDispose { audioPlayer?.release() }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val durationMs = voice.durationMs

    val playbackKey = remember(voice.id) {
        "shared_audio_${voice.id}_${kotlin.random.Random.nextLong()}"
    }
    DisposableEffect(playbackKey) {
        onDispose { FlashVoicePlaybackCoordinator.release(playbackKey) }
    }
    val activePlaybackKey = FlashVoicePlaybackCoordinator.activePlaybackKey
    LaunchedEffect(activePlaybackKey, isPlaying) {
        if (isPlaying && activePlaybackKey != null && activePlaybackKey != playbackKey) {
            isPlaying = false
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            FlashVoicePlaybackCoordinator.claim(playbackKey)
        } else {
            FlashVoicePlaybackCoordinator.release(playbackKey)
        }
        if (audioPlayer != null) {
            if (isPlaying) audioPlayer.play() else audioPlayer.pause()
            while (isPlaying) {
                elapsedMs = audioPlayer.positionMs().coerceAtMost(if (durationMs > 0L) durationMs else Long.MAX_VALUE)
                if (!audioPlayer.isPlaying() && elapsedMs > 0L) {
                    if (durationMs > 0L) elapsedMs = durationMs
                    isPlaying = false
                    FlashVoicePlaybackCoordinator.release(playbackKey)
                    break
                }
                delay(FlashVoiceMath.TICK_MS)
            }
        }
    }

    val durationLabel = remember(durationMs) { FlashVoiceMath.formatDuration(durationMs) }
    val statusNote = when {
        hasAudio -> null
        inFlight -> "Downloading…"
        voice.transferStatus == FlashFileTransferStatus.Failed -> "Failed · Tap to retry"
        voice.transferStatus == FlashFileTransferStatus.Paused -> "Paused · Tap to resume"
        isCancelled -> "Cancelled"
        isOutgoingAwaiting -> "Waiting for recipient"
        else -> "Tap to download"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.attachment)
            .background(colors.backgroundSurfaceStrong)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        // Play / Pause / Transfer action button
        Box(
            modifier = Modifier
                .size(FlashDimensions.minTouchTarget)
                .clip(CircleShape)
                .background(if (hasAudio || canTriggerTransfer || inFlight) colors.accentFill else colors.accentFill.copy(alpha = 0.4f))
                .clickable(enabled = !inFlight && (hasAudio || canTriggerTransfer)) {
                    when (action) {
                        FlashSharedTransferAction.Resume -> onResume()
                        FlashSharedTransferAction.Download -> onDownload()
                        FlashSharedTransferAction.Open -> {
                            val nextPlaying = !isPlaying
                            if (nextPlaying) {
                                FlashVoicePlaybackCoordinator.claim(playbackKey)
                            } else {
                                FlashVoicePlaybackCoordinator.release(playbackKey)
                            }
                            isPlaying = nextPlaying
                        }
                        FlashSharedTransferAction.None -> Unit
                    }
                }
                .semantics { role = Role.Button },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = when {
                    action == FlashSharedTransferAction.Resume ->
                        if (voice.transferStatus == FlashFileTransferStatus.Failed) FlashIcons.Retry else FlashIcons.Download
                    action == FlashSharedTransferAction.Download -> FlashIcons.Download
                    inFlight || isOutgoingAwaiting -> FlashIcons.Clock
                    isCancelled -> FlashIcons.Close
                    isPlaying -> FlashIcons.Pause
                    else -> FlashIcons.Play
                },
                contentDescription = when {
                    action == FlashSharedTransferAction.Resume ->
                        if (voice.transferStatus == FlashFileTransferStatus.Failed) "Retry voice download" else "Resume voice download"
                    action == FlashSharedTransferAction.Download -> "Download voice message"
                    isOutgoingAwaiting -> "Waiting for recipient"
                    inFlight -> "Downloading voice message"
                    isCancelled -> "Voice message cancelled"
                    isPlaying -> "Pause audio"
                    else -> "Play audio"
                },
                tint = colors.onAccentFill,
                size = FlashDimensions.iconSm,
            )
        }

        // Info
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = "Voice message ($durationLabel)",
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
            )
            FlashText(
                text = listOfNotNull(statusNote, item.senderName, item.timeLabel).joinToString(" • "),
                style = FlashTheme.typography.metadataDefault,
                color = if (voice.transferStatus == FlashFileTransferStatus.Failed) colors.textError else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(
            onClick = onJumpToMessage,
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
        ) {
            FlashIcon(
                icon = FlashIcons.Chat,
                contentDescription = "Jump to message",
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }
    }
}

@Composable
private fun SharedLinkRow(
    item: FlashSharedLinkItem,
    onOpenUrl: () -> Unit,
    onJumpToMessage: () -> Unit,
) {
    val colors = FlashTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.attachment)
            .background(colors.backgroundSurfaceStrong)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .clickable(onClick = onOpenUrl)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        // Icon
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (item.isInvite) colors.accentFill.copy(alpha = 0.15f)
                    else colors.borderSubtle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = if (item.isInvite) FlashIcons.Group else FlashIcons.Share,
                contentDescription = null,
                tint = if (item.isInvite) colors.accentFill else colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }

        // Content
        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = item.domain,
                style = FlashTheme.typography.bodyEmphasis,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = item.url,
                style = FlashTheme.typography.metadataDefault,
                color = colors.accentFill,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.snippet.isNotBlank()) {
                FlashText(
                    text = item.snippet,
                    style = FlashTheme.typography.captionDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(
            onClick = onJumpToMessage,
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
        ) {
            FlashIcon(
                icon = FlashIcons.Chat,
                contentDescription = "Jump to message",
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }
    }
}

@Composable
private fun EmptySharedState(
    icon: com.transfer.flash.ui.icons.FlashIconSpec,
    title: String,
    subtitle: String,
) {
    val colors = FlashTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(FlashSpacing.space32),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.backgroundSurfaceStrong),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = icon,
                contentDescription = null,
                tint = colors.textTertiary,
                size = FlashDimensions.iconLg,
            )
        }
        Spacer(modifier = Modifier.height(FlashSpacing.space16))
        FlashText(
            text = title,
            style = FlashTheme.typography.bodyEmphasis,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(FlashSpacing.space4))
        FlashText(
            text = subtitle,
            style = FlashTheme.typography.metadataDefault,
            color = colors.textSecondary,
        )
    }
}
