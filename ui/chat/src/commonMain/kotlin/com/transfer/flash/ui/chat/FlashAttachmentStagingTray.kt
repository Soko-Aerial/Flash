package com.transfer.flash.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashImageDecoder
import com.transfer.flash.ui.shims.rememberFlashImageDecoder
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pure logic for attachment staging calculations (JVM-testable). */
object FlashStagingMath {
    const val MAX_STAGED_ATTACHMENTS = 10

    fun canAddMore(currentCount: Int, maxLimit: Int = MAX_STAGED_ATTACHMENTS): Boolean =
        currentCount < maxLimit

    /**
     * Stages each source URI once per draft. A repeat pick is already staged, so it succeeds even
     * at the limit. Returns false only when the draft has no room for a new source.
     */
    fun appendUpToLimit(
        current: List<FlashShareItemUi>,
        incoming: FlashShareItemUi,
        maxLimit: Int = MAX_STAGED_ATTACHMENTS,
    ): Pair<List<FlashShareItemUi>, Boolean> =
        when {
            current.any { it.uri == incoming.uri } -> current to true
            canAddMore(current.size, maxLimit) -> (current + incoming) to true
            else -> current to false
        }

    fun formatTotalBytes(items: List<FlashShareItemUi>): String {
        val total = items.sumOf { it.sizeBytes }
        return FlashShareTargetMath.formatBytes(total)
    }

    /**
     * The composer hint. Files and text travel as separate messages (the file offer has no caption field), so while files
     * are staged the hint says the text follows them instead of implying it is attached.
     */
    fun composerPlaceholder(stagedCount: Int): String =
        if (stagedCount > 0) "Message (sent after the files)..." else "Message..."

    fun formatStagedSummary(count: Int, totalBytes: Long): String {
        return if (count <= 1) {
            "$count item (${FlashShareTargetMath.formatBytes(totalBytes)})"
        } else {
            "$count items (${FlashShareTargetMath.formatBytes(totalBytes)})"
        }
    }
}

/**
 * Horizontal staging carousel displaying pending attachments before sending (Task 3.2).
 */
@Composable
fun FlashAttachmentStagingTray(
    stagedAttachments: List<FlashShareItemUi>,
    onRemove: (FlashShareItemUi) -> Unit,
    onAddMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.composerSurface,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = FlashSpacing.space8,
                vertical = FlashSpacing.space8,
            ),
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(
                items = stagedAttachments,
                key = { it.id },
            ) { item ->
                StagedAttachmentCard(
                    item = item,
                    onRemove = { onRemove(item) },
                )
            }

            if (stagedAttachments.none { it.isSending } && FlashStagingMath.canAddMore(stagedAttachments.size)) {
                item(key = "add_more_tile") {
                    AddMoreTile(onClick = onAddMore)
                }
            }
        }
    }
}

@Composable
private fun StagedAttachmentCard(
    item: FlashShareItemUi,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val isImage = item.mimeType.startsWith("image/")
    val isVideo = item.mimeType.startsWith("video/")
    val icon = when {
        isImage -> FlashIcons.Gallery
        isVideo -> FlashIcons.VideoCall
        item.mimeType.startsWith("audio/") -> FlashIcons.Headphones
        else -> FlashIcons.Attach
    }
    val imageDecoder = rememberFlashImageDecoder()
    val thumbnailState = produceState<ImageBitmap?>(null, item.uri, item.mimeType) {
        if (!isImage && !isVideo) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            imageDecoder.decode(
                source = item.uri,
                isVideo = isVideo,
                maxLongEdge = 240,
                lowColorDepth = true,
                computeInSampleSize = FlashMediaViewerMath::computeInSampleSize,
            )
        }
    }
    val thumbnail = thumbnailState.value

    Surface(
        modifier = modifier
            .width(116.dp)
            .height(74.dp)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(FlashShapes.radius12)),
        color = colors.backgroundSurfaceSubtle,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.68f)),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space4),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    FlashText(
                        text = item.name,
                        style = FlashTheme.typography.captionEmphasis,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlashText(
                        text = FlashShareTargetMath.formatBytes(item.sizeBytes),
                        style = FlashTheme.typography.captionDefault,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(FlashSpacing.space8),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(colors.accentFill.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = icon,
                            tint = colors.accentFill,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        FlashText(
                            text = item.name,
                            style = FlashTheme.typography.captionEmphasis,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlashText(
                            text = FlashShareTargetMath.formatBytes(item.sizeBytes),
                            style = FlashTheme.typography.captionDefault,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (item.isSending) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(FlashSpacing.space8)
                        .size(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = if (thumbnail != null) Color.White else colors.accentFill,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(FlashDimensions.minTouchTarget)
                        .clickable(onClick = onRemove)
                        .padding(FlashSpacing.space4)
                        .semantics {
                            role = Role.Button
                            contentDescription = "Remove ${item.name}"
                        },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (thumbnail != null) Color.Black.copy(alpha = 0.65f)
                                else colors.backgroundSurface,
                            )
                            .border(
                                FlashDimensions.borderHairline,
                                if (thumbnail != null) Color.White.copy(alpha = 0.4f) else colors.borderSubtle,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        FlashIcon(
                            icon = FlashIcons.Close,
                            contentDescription = null,
                            tint = if (thumbnail != null) Color.White else colors.textSecondary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMoreTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors

    Surface(
        modifier = modifier
            .width(64.dp)
            .height(74.dp)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .border(
                border = BorderStroke(FlashDimensions.borderHairline, colors.borderSubtle),
                shape = RoundedCornerShape(FlashShapes.radius12),
            )
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        color = colors.backgroundSurfaceSubtle.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FlashIcon(
                icon = FlashIcons.Attach,
                tint = colors.accentFill,
                modifier = Modifier.size(20.dp),
            )
            FlashText(
                text = "Add",
                style = FlashTheme.typography.captionEmphasis,
                color = colors.accentFill,
            )
        }
    }
}
