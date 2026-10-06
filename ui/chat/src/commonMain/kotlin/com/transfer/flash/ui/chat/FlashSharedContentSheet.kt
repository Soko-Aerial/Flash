package com.transfer.flash.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    val mediaItems = remember(messages) { FlashSharedContentMath.extractMedia(messages) }
    val fileItems = remember(messages) { FlashSharedContentMath.extractFiles(messages) }
    val audioItems = remember(messages) { FlashSharedContentMath.extractAudio(messages) }
    val linkItems = remember(messages) { FlashSharedContentMath.extractLinks(messages) }

    var selectedTab by remember { mutableStateOf(FlashSharedContentTab.Media) }

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

            // Tabs row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashSharedContentTab.entries.forEach { tab ->
                    val count = when (tab) {
                        FlashSharedContentTab.Media -> mediaItems.size
                        FlashSharedContentTab.Files -> fileItems.size
                        FlashSharedContentTab.Audio -> audioItems.size
                        FlashSharedContentTab.Links -> linkItems.size
                    }
                    val isSelected = tab == selectedTab

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(FlashShapes.chip)
                            .background(
                                if (isSelected) colors.accentPrimary.copy(alpha = 0.15f)
                                else colors.backgroundSurfaceStrong,
                            )
                            .border(
                                width = FlashDimensions.borderHairline,
                                color = if (isSelected) colors.accentPrimary else colors.borderSubtle,
                                shape = FlashShapes.chip,
                            )
                            .clickable {
                                haptics(FlashHaptic.Tick)
                                selectedTab = tab
                            }
                            .padding(vertical = FlashSpacing.space8),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashText(
                            text = FlashSharedContentMath.tabLabelWithCount(tab, count),
                            style = FlashTheme.typography.captionEmphasis,
                            color = if (isSelected) colors.accentPrimary else colors.textSecondary,
                            maxLines = 1,
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
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(FlashSpacing.space4),
                                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
                                ) {
                                    itemsIndexed(mediaItems, key = { _, item -> item.image.id }) { index, item ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f),
                                        ) {
                                            FlashImageTile(
                                                image = item.image,
                                                shape = RoundedCornerShape(4.dp),
                                                onClick = {
                                                    val viewerList = mediaItems.map {
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
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                ) {
                                    items(fileItems, key = { it.file.id }) { item ->
                                        SharedFileRow(
                                            item = item,
                                            onOpenFile = { onOpenFile(item.file.localUri, item.file.mimeType, item.file.name) },
                                            onDownload = { onDownloadFile(item.file.id) },
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
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                ) {
                                    items(audioItems, key = { it.voice.id }) { item ->
                                        SharedAudioRow(
                                            item = item,
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
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8),
                                    verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                                ) {
                                    items(linkItems, key = { "${it.messageId}_${it.url}" }) { item ->
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

@Composable
private fun SharedFileRow(
    item: FlashSharedFileItem,
    onOpenFile: () -> Unit,
    onDownload: () -> Unit,
    onJumpToMessage: () -> Unit,
) {
    val colors = FlashTheme.colors
    val file = item.file
    val extensionBadge = remember(file.name) { FlashSharedContentMath.badgeForExtension(file.name) }
    val sizeText = remember(file.sizeBytes) { FlashSharedContentMath.formatFileSize(file.sizeBytes) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FlashShapes.attachment)
            .background(colors.backgroundSurfaceStrong)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.attachment)
            .clickable(onClick = onOpenFile)
            .padding(FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        // Extension badge
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            FlashText(
                text = extensionBadge,
                style = FlashTheme.typography.captionEmphasis.copy(fontWeight = FontWeight.Bold),
                color = colors.accentPrimary,
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
                text = "$sizeText • ${item.senderName} • ${item.timeLabel}",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Actions
        if (file.localUri == null && file.transferStatus != FlashFileTransferStatus.Downloaded) {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(
                    icon = FlashIcons.Download,
                    contentDescription = "Download file",
                    tint = colors.accentPrimary,
                    size = FlashDimensions.iconSm,
                )
            }
        }

        IconButton(
            onClick = onJumpToMessage,
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
        ) {
            FlashIcon(
                icon = FlashIcons.Forward,
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
    onJumpToMessage: () -> Unit,
) {
    val colors = FlashTheme.colors
    val voice = item.voice
    val hasAudio = voice.uri != null && voice.transferStatus == FlashFileTransferStatus.Downloaded
    val audioPlayer = rememberFlashAudioPlayer(uri = if (hasAudio) voice.uri else null)

    DisposableEffect(audioPlayer) {
        onDispose { audioPlayer?.release() }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val durationMs = voice.durationMs

    LaunchedEffect(isPlaying) {
        if (audioPlayer != null) {
            if (isPlaying) audioPlayer.play() else audioPlayer.pause()
            while (isPlaying) {
                elapsedMs = audioPlayer.positionMs().coerceAtMost(if (durationMs > 0L) durationMs else Long.MAX_VALUE)
                if (!audioPlayer.isPlaying() && elapsedMs > 0L) {
                    if (durationMs > 0L) elapsedMs = durationMs
                    isPlaying = false
                    break
                }
                delay(FlashVoiceMath.TICK_MS)
            }
        }
    }

    val durationLabel = remember(durationMs) { FlashVoiceMath.formatDuration(durationMs) }

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
        // Play / Pause button
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(colors.accentPrimary)
                .clickable { isPlaying = !isPlaying },
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = if (isPlaying) FlashIcons.Pause else FlashIcons.Play,
                contentDescription = if (isPlaying) "Pause audio" else "Play audio",
                tint = Color.White,
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
                text = "${item.senderName} • ${item.timeLabel}",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }

        IconButton(
            onClick = onJumpToMessage,
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
        ) {
            FlashIcon(
                icon = FlashIcons.Forward,
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
                    if (item.isInvite) colors.accentPrimary.copy(alpha = 0.15f)
                    else colors.borderSubtle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = if (item.isInvite) FlashIcons.Group else FlashIcons.Share,
                contentDescription = null,
                tint = if (item.isInvite) colors.accentPrimary else colors.textSecondary,
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
                color = colors.accentPrimary,
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
                icon = FlashIcons.Forward,
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
