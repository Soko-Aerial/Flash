package com.transfer.flash.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Task 3.6: Swarm Transfer Block Availability Grid Map.
 *
 * Renders a high-performance Canvas-drawn micro-block matrix of the engine's real piece state
 * ([blocks], see `FlashTransfer.pieceBlocks`); callers draw it only when [blocks] is not empty:
 * - Verified & Saved: accent/green
 * - In-flight downloading: pulsing cyan
 * - Available on swarm peers: amber outline
 * - Missing: subtle dark surface/gray
 */
@Composable
public fun FlashSwarmPieceMap(
    blocks: List<Int>,
    holdersOnline: Int,
    isDownloading: Boolean,
    modifier: Modifier = Modifier,
    columns: Int = FlashSwarmPieceMapMath.DEFAULT_COLUMNS,
) {
    val colors = FlashTheme.colors
    val motion = FlashTheme.motion

    val statuses = remember(blocks) { FlashSwarmPieceMapMath.statusesFromBlocks(blocks) }
    val totalBlocks = statuses.size

    val summary = remember(statuses) {
        FlashSwarmPieceMapMath.summaryText(statuses)
    }

    val pulseTransition = rememberInfiniteTransition(label = "swarm_piece_pulse")
    val pulseAlpha by if (isDownloading && !motion.reduceMotion) {
        pulseTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "piece_pulse_alpha",
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1.0f) }
    }

    val verifiedColor = colors.statusOnline
    val inFlightColor = Color(0xFF06B6D4) // Cyan
    val swarmColor = Color(0xFFF59E0B)    // Amber
    val missingColor = colors.borderSubtle.copy(alpha = 0.35f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlashShapes.radius8))
            .background(colors.backgroundSurfaceSubtle)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, RoundedCornerShape(FlashShapes.radius8))
            .padding(FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                contentDescription = "Swarm piece availability map: $summary"
            },
    ) {
        // Top status summary line
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
            ) {
                FlashIcon(
                    icon = FlashIcons.Group,
                    contentDescription = null,
                    tint = if (holdersOnline > 0) colors.accentPrimary else colors.textTertiary,
                    size = 12.dp,
                )
                FlashText(
                    text = "Piece Map",
                    style = FlashTheme.typography.captionEmphasis,
                    color = colors.textPrimary,
                )
            }
            FlashText(
                text = summary,
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(FlashSpacing.space8))

        // Canvas micro-block grid
        val rows = ((totalBlocks + columns - 1) / columns).coerceAtLeast(1)
        val blockSpacingPx = 4f
        val cornerRadiusPx = 3f

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            val totalGapsX = (columns - 1) * blockSpacingPx
            val blockWidth = (size.width - totalGapsX) / columns
            val totalGapsY = (rows - 1) * blockSpacingPx
            val blockHeight = (size.height - totalGapsY) / rows
            val cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)

            statuses.forEachIndexed { index, status ->
                val col = index % columns
                val row = index / columns

                val x = col * (blockWidth + blockSpacingPx)
                val y = row * (blockHeight + blockSpacingPx)
                val blockSize = Size(blockWidth, blockHeight)
                val offset = Offset(x, y)

                when (status) {
                    FlashPieceStatus.VerifiedSaved -> {
                        drawRoundRect(
                            color = verifiedColor,
                            topLeft = offset,
                            size = blockSize,
                            cornerRadius = cornerRadius,
                        )
                    }
                    FlashPieceStatus.InFlightDownloading -> {
                        drawRoundRect(
                            color = inFlightColor.copy(alpha = pulseAlpha),
                            topLeft = offset,
                            size = blockSize,
                            cornerRadius = cornerRadius,
                        )
                    }
                    FlashPieceStatus.AvailableOnPeers -> {
                        // Amber border / outline to indicate available on swarm peers
                        drawRoundRect(
                            color = swarmColor.copy(alpha = 0.25f),
                            topLeft = offset,
                            size = blockSize,
                            cornerRadius = cornerRadius,
                        )
                        drawRoundRect(
                            color = swarmColor,
                            topLeft = offset,
                            size = blockSize,
                            cornerRadius = cornerRadius,
                            style = Stroke(width = 1.5f),
                        )
                    }
                    FlashPieceStatus.Missing -> {
                        drawRoundRect(
                            color = missingColor,
                            topLeft = offset,
                            size = blockSize,
                            cornerRadius = cornerRadius,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(FlashSpacing.space8))

        // Legend chips row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendIndicator(color = verifiedColor, label = "Verified")
            LegendIndicator(color = inFlightColor, label = "In-flight")
            LegendIndicator(color = swarmColor, label = "Swarm")
            LegendIndicator(color = missingColor, label = "Missing")
        }
    }
}

@Composable
private fun LegendIndicator(
    color: Color,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        FlashText(
            text = label,
            style = FlashTheme.typography.captionDefault,
            color = FlashTheme.colors.textSecondary,
        )
    }
}
