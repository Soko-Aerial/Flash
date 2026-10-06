package com.transfer.flash.ui.chat

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Pure logic for attachment staging calculations (JVM-testable). */
object FlashStagingMath {
    const val MAX_STAGED_ATTACHMENTS = 10

    fun canAddMore(currentCount: Int, maxLimit: Int = MAX_STAGED_ATTACHMENTS): Boolean =
        currentCount < maxLimit

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
            items(stagedAttachments, key = { it.uri }) { item ->
                StagedAttachmentCard(
                    item = item,
                    onRemove = { onRemove(item) },
                )
            }

            if (FlashStagingMath.canAddMore(stagedAttachments.size)) {
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
    val icon = when {
        item.mimeType.startsWith("image/") -> FlashIcons.Gallery
        item.mimeType.startsWith("video/") -> FlashIcons.VideoCall
        item.mimeType.startsWith("audio/") -> FlashIcons.Microphone
        else -> FlashIcons.Attach
    }

    Surface(
        modifier = modifier
            .width(116.dp)
            .height(74.dp)
            .clip(RoundedCornerShape(FlashShapes.radius12))
            .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(FlashShapes.radius12)),
        color = colors.backgroundSurfaceSubtle,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(FlashSpacing.space8),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.accentPrimary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(
                        icon = icon,
                        tint = colors.accentPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(colors.backgroundSurface)
                        .clickable(onClick = onRemove)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    FlashIcon(
                        icon = FlashIcons.Close,
                        contentDescription = "Remove attachment",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(12.dp),
                    )
                }
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
                tint = colors.accentPrimary,
                modifier = Modifier.size(20.dp),
            )
            FlashText(
                text = "Add",
                style = FlashTheme.typography.captionEmphasis,
                color = colors.accentPrimary,
            )
        }
    }
}
