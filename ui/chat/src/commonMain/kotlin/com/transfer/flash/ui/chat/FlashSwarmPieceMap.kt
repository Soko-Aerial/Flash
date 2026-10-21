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
import androidx.compose.ui.graphics.drawscope.DrawScope
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

    // H-25: the LOW tier (minimal chrome) takes the one-line summary too; it used to draw the whole grid every frame it changed.
    if (FlashTheme.ultraLowUi || FlashTheme.minimalChrome) {
        val verifiedCount = remember(blocks) { blocks.count { it == 3 } }
        val totalCount = blocks.size
        val percent = if (totalCount > 0) (verifiedCount * 100) / totalCount else 0
        val text = "[Swarm Pieces: $verifiedCount/$totalCount ($percent%) • $holdersOnline peers]"

        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.backgroundSurfaceSubtle)
                .border(FlashDimensions.borderHairline, colors.borderSubtle)
                .padding(FlashSpacing.space8)
                .semantics(mergeDescendants = true) {
                    contentDescription = text
                },
        ) {
            FlashText(
                text = text,
                style = FlashTheme.typography.metadataEmphasis,
                color = colors.textSecondary,
            )
        }
        return
    }

    val statuses = remember(blocks) { FlashSwarmPieceMapMath.statusesFromBlocks(blocks) }
    val totalBlocks = statuses.size

    val summary = remember(statuses) {
        FlashSwarmPieceMapMath.summaryText(statuses)
    }

    // The pulse follows the DATA, not the transfer state: a group sender's map is all verified and a receiver that has its
    // last piece is still "Active", yet nothing is in flight; animating then re-recorded 64 to 128 rects on every frame.
    val anyInFlight = remember(statuses) { statuses.any { it == FlashPieceStatus.InFlightDownloading } }
    val pulseTransition = rememberInfiniteTransition(label = "swarm_piece_pulse")
    val pulseAlpha by if (isDownloading && anyInFlight && !motion.reduceMotion) {
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

        // Canvas micro-block grid. Two layers: the static grid reads no animated state, so it is recorded only when the
        // blocks change; the pulse layer exists only while a block is in flight and draws just those blocks.
        val rows = ((totalBlocks + columns - 1) / columns).coerceAtLeast(1)
        val gridModifier = Modifier
            .fillMaxWidth()
            .height(44.dp)

        Box {
            Canvas(modifier = gridModifier) {
                val grid = PieceGrid(size.width, size.height, columns, rows, 1.5.dp.toPx(), 1.dp.toPx())
                statuses.forEachIndexed { index, status ->
                    when (status) {
                        FlashPieceStatus.VerifiedSaved -> grid.fill(this, index, verifiedColor)
                        FlashPieceStatus.InFlightDownloading -> Unit // drawn by the pulse layer
                        FlashPieceStatus.AvailableOnPeers -> {
                            // Amber border / outline to indicate available on swarm peers
                            grid.fill(this, index, swarmColor.copy(alpha = 0.25f))
                            grid.stroke(this, index, swarmColor, 1.dp.toPx())
                        }
                        FlashPieceStatus.Missing -> grid.fill(this, index, missingColor)
                    }
                }
            }
            if (anyInFlight) {
                Canvas(modifier = gridModifier) {
                    val grid = PieceGrid(size.width, size.height, columns, rows, 1.5.dp.toPx(), 1.dp.toPx())
                    val color = inFlightColor.copy(alpha = pulseAlpha)
                    statuses.forEachIndexed { index, status ->
                        if (status == FlashPieceStatus.InFlightDownloading) grid.fill(this, index, color)
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

/** Block geometry for the piece grid in one place, so both canvas layers place a block identically. */
private class PieceGrid(
    width: Float,
    height: Float,
    private val columns: Int,
    rows: Int,
    private val spacing: Float,
    cornerRadius: Float,
) {
    private val blockWidth = (width - (columns - 1) * spacing) / columns
    private val blockHeight = (height - (rows - 1) * spacing) / rows
    private val blockSize = Size(blockWidth, blockHeight)
    private val corner = CornerRadius(cornerRadius, cornerRadius)

    private fun offsetOf(index: Int) = Offset(
        x = (index % columns) * (blockWidth + spacing),
        y = (index / columns) * (blockHeight + spacing),
    )

    fun fill(scope: DrawScope, index: Int, color: Color) {
        scope.drawRoundRect(color = color, topLeft = offsetOf(index), size = blockSize, cornerRadius = corner)
    }

    fun stroke(scope: DrawScope, index: Int, color: Color, width: Float) {
        scope.drawRoundRect(
            color = color,
            topLeft = offsetOf(index),
            size = blockSize,
            cornerRadius = corner,
            style = Stroke(width = width),
        )
    }
}
