package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.avatar.FlashAvatar
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * UI-054 (O-14): An interactive inline invite card rendered in chat when an invite link is shared.
 */
@Composable
public fun FlashInlineInviteCard(
    inviteUrl: String,
    onJoinInvite: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val parsedInvite = remember(inviteUrl) { FlashGroupInviteJoinMath.parseInviteUrl(inviteUrl) }
    if (parsedInvite == null) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(FlashShapes.bubbleGrouped)
            .background(colors.backgroundSurface)
            .border(FlashDimensions.borderHairline, colors.borderSubtle, FlashShapes.bubbleGrouped)
            .padding(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            FlashAvatar(
                initials = parsedInvite.groupName.take(2).uppercase().ifEmpty { "GP" },
                seed = parsedInvite.groupId,
                size = 40.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2),
            ) {
                FlashText(
                    text = parsedInvite.groupName.ifBlank { "Group" },
                    style = FlashTheme.typography.bodyEmphasis,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlashText(
                    text = "Group invite link",
                    style = FlashTheme.typography.metadataDefault,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }

            Box(
                modifier = Modifier
                    .clip(FlashShapes.chip)
                    .background(colors.accentPrimary)
                    .clickable { onJoinInvite(inviteUrl) }
                    .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
                contentAlignment = Alignment.Center,
            ) {
                FlashText(
                    text = "Join",
                    style = FlashTheme.typography.metadataEmphasis,
                    color = colors.textOnAccent,
                )
            }
        }
    }
}
