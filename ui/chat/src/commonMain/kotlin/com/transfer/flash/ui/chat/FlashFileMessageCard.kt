package com.transfer.flash.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-016 File Message Card.
 *
 * Rich in-bubble file card featuring color-coded file extension badge,
 * circular transfer progress ring, and real-time LAN/P2P throughput metrics.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FlashFileMessageCard(
    attachment: FlashFileAttachmentUi,
    isParentOutgoing: Boolean,
    onCardClick: () -> Unit,
    onActionClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()
    val isAwaiting = attachment.transferStatus == FlashFileTransferStatus.AwaitingAcceptance

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && !motion.reduceMotion) 0.97f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "fileCardPressScale",
    )

    val surfaceBg = if (isParentOutgoing) {
        colors.chatBgAttachmentOutgoing
    } else {
        colors.chatBgAttachmentIncoming
    }

    val primaryTextColor = if (isParentOutgoing) {
        colors.chatTextOutgoing
    } else {
        colors.chatTextIncoming
    }

    val secondaryTextColor = if (isParentOutgoing) {
        colors.chatTextTimestampOutgoing
    } else {
        colors.chatTextTimestamp
    }

    val extension = remember(attachment.name) {
        attachment.name.substringAfterLast('.', "").take(4)
    }

    val formattedSize = remember(attachment.sizeBytes) {
        formatFileSize(attachment.sizeBytes)
    }

    val statusSubtitle = remember(attachment.transferStatus, attachment.transferSpeedMbps, attachment.etaSeconds) {
        when (attachment.transferStatus) {
            FlashFileTransferStatus.Transferring -> {
                if (attachment.transferSpeedMbps > 0f) {
                    "$formattedSize • ${"%.1f".format(attachment.transferSpeedMbps)} MB/s"
                } else {
                    "$formattedSize • Transferring..."
                }
            }
            FlashFileTransferStatus.NotDownloaded -> "$formattedSize • Tap to download"
            FlashFileTransferStatus.AwaitingAcceptance -> "$formattedSize • Awaiting your acceptance"
            FlashFileTransferStatus.Downloaded -> {
                if (extension.isNotEmpty()) "$formattedSize • ${extension.uppercase()}" else formattedSize
            }
            FlashFileTransferStatus.Failed -> "$formattedSize • Failed (Tap to retry)"
        }
    }

    val a11yDesc = remember(attachment.name, formattedSize, attachment.transferStatus) {
        when (attachment.transferStatus) {
            FlashFileTransferStatus.Transferring -> "Transferring ${attachment.name}, $formattedSize"
            FlashFileTransferStatus.NotDownloaded -> "${attachment.name}, $formattedSize. Double-tap to download."
            FlashFileTransferStatus.AwaitingAcceptance -> "${attachment.name}, $formattedSize. Waiting for you to accept the transfer."
            FlashFileTransferStatus.Downloaded -> "${attachment.name}, $formattedSize. Double-tap to open."
            FlashFileTransferStatus.Failed -> "Failed to transfer ${attachment.name}. Double-tap to retry."
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FlashShapes.attachment)
            .background(surfaceBg)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle.copy(alpha = 0.5f),
                shape = FlashShapes.attachment,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = if (isAwaiting) 1f else pressScale
                    scaleY = if (isAwaiting) 1f else pressScale
                }
                .then(
                    if (!isAwaiting) {
                        Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = {
                                haptics(FlashHaptic.Tick)
                                onCardClick()
                            },
                            onLongClick = {
                                haptics(FlashHaptic.Confirm)
                                onLongPress()
                            },
                        )
                    } else {
                        Modifier
                    }
                )
                .semantics {
                    role = Role.Button
                    contentDescription = a11yDesc
                }
                .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading action badge / progress ring
            FlashFileIconBadge(
                attachment = attachment,
                extension = extension,
                onActionClick = onActionClick,
            )

            Spacer(modifier = Modifier.width(FlashSpacing.space12))

            // Center metadata
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = attachment.name,
                    style = typography.bodyDefault.copy(fontWeight = FontWeight.Medium),
                    color = primaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = statusSubtitle,
                    style = typography.captionEmphasis.copy(fontSize = 11.sp),
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Accept/Decline buttons for inbound offers
        if (isAwaiting) {
            FlashFileOfferActions(
                onAccept = {
                    haptics(FlashHaptic.Tick)
                    onAccept()
                },
                onDecline = {
                    haptics(FlashHaptic.Tick)
                    onDecline()
                },
                colors = colors,
                typography = typography,
            )
        }
    }
}

/**
 * Accept / Decline action row shown on inbound file offers inside the chat bubble.
 */
@Composable
private fun FlashFileOfferActions(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    colors: com.transfer.flash.ui.theme.FlashColors,
    typography: com.transfer.flash.ui.theme.FlashTypography,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FlashSpacing.space12, end = FlashSpacing.space12, bottom = FlashSpacing.space8),
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        // Accept button
        androidx.compose.material3.FilledTonalButton(
            onClick = onAccept,
            modifier = Modifier.weight(1f),
            shape = FlashShapes.attachment,
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.accentPrimary.copy(alpha = 0.15f),
                contentColor = colors.accentPrimary,
            ),
        ) {
            FlashIcon(
                icon = FlashIcons.Check,
                contentDescription = null,
                size = FlashDimensions.iconSm,
            )
            Spacer(modifier = Modifier.width(FlashSpacing.space4))
            Text(
                text = "Accept",
                style = typography.bodyDefault.copy(fontWeight = FontWeight.Medium),
                color = colors.accentPrimary,
            )
        }

        // Decline button
        androidx.compose.material3.FilledTonalButton(
            onClick = onDecline,
            modifier = Modifier.weight(1f),
            shape = FlashShapes.attachment,
            colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.textError.copy(alpha = 0.12f),
                contentColor = colors.textError,
            ),
        ) {
            FlashIcon(
                icon = FlashIcons.Close,
                contentDescription = null,
                size = FlashDimensions.iconSm,
            )
            Spacer(modifier = Modifier.width(FlashSpacing.space4))
            Text(
                text = "Decline",
                style = typography.bodyDefault.copy(fontWeight = FontWeight.Medium),
                color = colors.textError,
            )
        }
    }
}

/**
 * 48dp leading file badge with color-coded extension and circular progress indicator.
 */
@Composable
fun FlashFileIconBadge(
    attachment: FlashFileAttachmentUi,
    extension: String,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val categoryColor = remember(extension) { fileCategoryColorFor(extension) }

    val animatedProgress by animateFloatAsState(
        targetValue = attachment.transferProgress.coerceIn(0f, 1f),
        animationSpec = motion.tweenFastSpec(),
        label = "fileTransferProgress",
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(categoryColor.copy(alpha = 0.9f))
            .border(FlashDimensions.borderHairline, colors.borderSubtle.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (attachment.transferStatus) {
            FlashFileTransferStatus.Transferring -> {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(44.dp),
                    color = colors.accentPrimary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeWidth = 2.5.dp,
                )
                FlashIcon(
                    icon = FlashIcons.Pause,
                    contentDescription = "Pause transfer",
                    tint = Color.White,
                    size = FlashDimensions.iconSm,
                )
            }

            FlashFileTransferStatus.NotDownloaded -> {
                FlashIcon(
                    icon = FlashIcons.Download,
                    contentDescription = "Download file",
                    tint = Color.White,
                    size = FlashDimensions.iconMd,
                )
            }

            FlashFileTransferStatus.AwaitingAcceptance -> {
                FlashIcon(
                    icon = FlashIcons.Clock,
                    contentDescription = "Awaiting acceptance",
                    tint = Color.White,
                    size = FlashDimensions.iconMd,
                )
            }

            FlashFileTransferStatus.Failed -> {
                FlashIcon(
                    icon = FlashIcons.Retry,
                    contentDescription = "Retry transfer",
                    tint = Color.White,
                    size = FlashDimensions.iconMd,
                )
            }

            FlashFileTransferStatus.Downloaded -> {
                if (extension.isNotEmpty() && extension.length <= 4) {
                    Text(
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
    }
}

/**
 * Color-coding resolver for file types.
 */
fun fileCategoryColorFor(extension: String): Color = when (extension.lowercase()) {
    "pdf" -> Color(0xFFE63946)
    "zip", "rar", "7z", "tar", "gz", "apk" -> Color(0xFFF77F00)
    "kt", "java", "py", "js", "json", "html", "rs", "cpp", "c", "xml" -> Color(0xFF4361EE)
    "mp3", "wav", "flac", "m4a", "ogg", "aac" -> Color(0xFF7209B7)
    "mp4", "mkv", "mov", "avi", "webm" -> Color(0xFFD81159)
    "png", "jpg", "jpeg", "webp", "svg", "gif" -> Color(0xFF00B4D8)
    "doc", "docx", "txt", "md", "rtf", "odt" -> Color(0xFF3F37C9)
    else -> Color(0xFF5A6472)
}

/**
 * Human-readable byte size formatter.
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

@Preview(name = "File Card - Downloaded PDF", showBackground = true)
@Composable
private fun FlashFileMessageCardPdfPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashFileMessageCard(
                attachment = FlashFileAttachmentUi(
                    id = "1",
                    name = "Quarterly_Financial_Report_2026.pdf",
                    sizeBytes = 14_500_000,
                    transferStatus = FlashFileTransferStatus.Downloaded,
                ),
                isParentOutgoing = true,
                onCardClick = {},
                onActionClick = {},
            )
        }
    }
}

@Preview(name = "File Card - Transferring ZIP", showBackground = true)
@Composable
private fun FlashFileMessageCardZipPreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashFileMessageCard(
                attachment = FlashFileAttachmentUi(
                    id = "2",
                    name = "Flash_SDK_Source_v2.0.zip",
                    sizeBytes = 128_000_000,
                    transferStatus = FlashFileTransferStatus.Transferring,
                    transferProgress = 0.65f,
                    transferSpeedMbps = 42.8f,
                    etaSeconds = 2,
                ),
                isParentOutgoing = false,
                onCardClick = {},
                onActionClick = {},
            )
        }
    }
}
