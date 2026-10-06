package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashMessageUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Task 3.4: Slim Pinned Message Banner directly below chat header.
 *
 * Renders pin icon, sender name, 1-line text preview, and dismiss/unpin button.
 * Tapping smoothly scrolls to the message and triggers the highlight pulse.
 */
@Composable
public fun FlashPinnedMessageBanner(
    message: FlashMessageUi,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    val contentSummary = remember(message) {
        com.transfer.flash.core.messaging.util.flashMessageContentSummary(message)
    }
    val preview = remember(contentSummary) {
        FlashPinnedMessageMath.previewSnippet(contentSummary)
    }
    val headerTitle = remember(message.senderName, message.isMine) {
        FlashPinnedMessageMath.senderHeader(message.senderName, message.isMine)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(colors.backgroundSurfaceStrong)
            .border(
                width = FlashDimensions.borderHairline,
                color = colors.borderSubtle,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space8)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$headerTitle: $preview"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashIcon(
            icon = FlashIcons.Pin,
            contentDescription = null,
            tint = colors.accentPrimary,
            size = FlashDimensions.iconSm,
        )

        Column(modifier = Modifier.weight(1f)) {
            FlashText(
                text = headerTitle,
                style = typography.captionEmphasis,
                color = colors.accentPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlashText(
                text = preview,
                style = typography.bodyDefault,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(
            onClick = onUnpin,
            modifier = Modifier.size(FlashDimensions.minTouchTarget),
        ) {
            FlashIcon(
                icon = FlashIcons.Close,
                contentDescription = "Unpin message",
                tint = colors.textSecondary,
                size = FlashDimensions.iconSm,
            )
        }
    }
}
