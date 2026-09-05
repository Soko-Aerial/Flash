package com.transfer.flash.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashHaptic
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.rememberFlashHaptics
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Categorized attachment options available in Flash (UI-012).
 */
enum class FlashAttachmentType(
    val label: String,
    val icon: FlashIconSpec,
    val containerColor: Color,
    val iconTint: Color = Color.White,
) {
    Gallery(
        label = "Gallery",
        icon = FlashIcons.Gallery,
        containerColor = Color(0xFF00B4D8),
    ),
    Files(
        label = "Files",
        icon = FlashIcons.Upload,
        containerColor = Color(0xFF4361EE),
    ),
    Camera(
        label = "Camera",
        icon = FlashIcons.Camera,
        containerColor = Color(0xFFF77F00),
    ),
    Audio(
        label = "Audio",
        icon = FlashIcons.Microphone,
        containerColor = Color(0xFF7209B7),
    ),
    FlashTransfer(
        label = "Flash P2P",
        icon = FlashIcons.Device,
        containerColor = Color(0xFF00E5BC),
        iconTint = Color.Black,
    ),
}

/**
 * UI-012 Modal Attachment Sheet.
 *
 * Categorized action palette featuring media picker, document transfer, camera,
 * and high-speed local-first Flash P2P transfers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FlashAttachmentSheet(
    onDismiss: () -> Unit,
    onSelectAction: (FlashAttachmentType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val actions = remember { FlashAttachmentType.values().toList() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.backgroundSurface,
        shape = RoundedCornerShape(
            topStart = FlashShapes.radius24,
            topEnd = FlashShapes.radius24,
        ),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FlashSpacing.space12)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.borderSubtle),
            )
        },
        contentWindowInsets = { WindowInsets.navigationBars },
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    top = FlashSpacing.space8,
                    bottom = FlashSpacing.space32,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space20),
                maxItemsInEachRow = 4,
            ) {
                actions.forEachIndexed { index, action ->
                    FlashAttachmentTile(
                        action = action,
                        index = index,
                        onClick = {
                            onSelectAction(action)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Single animated action tile inside [FlashAttachmentSheet].
 */
@Composable
fun FlashAttachmentTile(
    action: FlashAttachmentType,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val motion = FlashTheme.motion
    val haptics = rememberFlashHaptics()

    var isVisible by remember { mutableStateOf(motion.reduceMotion) }
    LaunchedEffect(Unit) {
        if (!motion.reduceMotion) {
            delay(index * 20L)
            isVisible = true
        }
    }

    val enterScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.6f,
        animationSpec = motion.springDefaultSpec(),
        label = "attachmentTileEnterScale",
    )
    val enterAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = motion.tweenFastSpec(),
        label = "attachmentTileEnterAlpha",
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && !motion.reduceMotion) 0.90f else 1f,
        animationSpec = motion.springSnappySpec(),
        label = "attachmentTilePressScale",
    )

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = enterScale * pressScale
                scaleY = enterScale * pressScale
                alpha = enterAlpha
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics(FlashHaptic.Tick)
                    onClick()
                },
            )
            .semantics {
                role = Role.Button
                contentDescription = "Attach from ${action.label}"
            }
            .padding(FlashSpacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(action.containerColor)
                .border(FlashDimensions.borderHairline, colors.borderSubtle.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FlashIcon(
                icon = action.icon,
                contentDescription = null,
                tint = action.iconTint,
                modifier = Modifier.size(FlashDimensions.iconLg),
            )
        }

        Spacer(modifier = Modifier.height(FlashSpacing.space8))

        Text(
            text = action.label,
            style = typography.captionEmphasis.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            ),
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Preview(name = "Attachment Tile - Gallery", showBackground = true)
@Composable
private fun FlashAttachmentTilePreview() {
    FlashTheme {
        Box(modifier = Modifier.padding(FlashSpacing.space16)) {
            FlashAttachmentTile(
                action = FlashAttachmentType.Gallery,
                index = 0,
                onClick = {},
            )
        }
    }
}
