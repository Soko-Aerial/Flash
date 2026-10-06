package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-054: Group invite sheet for sharing and copying the invite link.
 */
@Composable
public fun FlashGroupInviteSheet(
    inviteUrl: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    groupName: String? = null,
    onShare: ((String) -> Unit)? = null,
) {
    val colors = FlashTheme.colors
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

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
                .verticalScroll(rememberScrollState())
                .padding(
                    start = FlashSpacing.space20,
                    end = FlashSpacing.space20,
                    bottom = FlashSpacing.space32,
                ),
        ) {
            FlashText(
                text = "Invite to group",
                style = FlashTheme.typography.headingMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = FlashSpacing.space4),
            )
            FlashText(
                text = "Anyone with Flash can use this link to join ${groupName?.ifBlank { "this group" } ?: "this group"}.",
                style = FlashTheme.typography.metadataDefault,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = FlashSpacing.space16),
            )

            // Link Display Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(FlashShapes.bubbleGrouped)
                    .background(colors.backgroundSurfaceSubtle)
                    .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.bubbleGrouped)
                    .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlashText(
                        text = inviteUrl,
                        style = FlashTheme.typography.metadataDefault,
                        color = colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(FlashSpacing.space16))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
            ) {
                // Copy Link Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(FlashShapes.chip)
                        .background(if (copied) colors.statusOnline.copy(alpha = 0.15f) else colors.backgroundSurfaceSubtle)
                        .border(
                            FlashDimensions.borderHairline,
                            if (copied) colors.statusOnline else colors.borderSubtle,
                            FlashShapes.chip,
                        )
                        .clickable {
                            clipboardManager.setText(AnnotatedString(inviteUrl))
                            copied = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                    ) {
                        FlashIcon(
                            icon = if (copied) FlashIcons.Check else FlashIcons.Share,
                            contentDescription = if (copied) "Copied" else "Copy link",
                            tint = if (copied) colors.statusOnline else colors.textPrimary,
                        )
                        FlashText(
                            text = if (copied) "Link copied!" else "Copy link",
                            style = FlashTheme.typography.bodyEmphasis,
                            color = if (copied) colors.statusOnline else colors.textPrimary,
                        )
                    }
                }

                // Share Button (if supported by host)
                if (onShare != null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(FlashShapes.chip)
                            .background(colors.accentPrimary)
                            .clickable { onShare(inviteUrl) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
                        ) {
                            FlashIcon(
                                icon = FlashIcons.Share,
                                contentDescription = "Share link",
                                tint = colors.textOnAccent,
                            )
                            FlashText(
                                text = "Share",
                                style = FlashTheme.typography.bodyEmphasis,
                                color = colors.textOnAccent,
                            )
                        }
                    }
                }
            }
        }
    }
}
