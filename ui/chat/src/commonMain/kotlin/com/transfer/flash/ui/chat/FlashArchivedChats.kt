package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.flashPressScale
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Entry row at the top of the chat list leading into the archived chats screen.
 * Shows the total archived count and highlights any unread messages among archived chats.
 */
@Composable
fun FlashArchivedChatsRow(
    archivedCount: Int,
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .flashPressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = "Archived chats, $archivedCount conversations" +
                    if (unreadCount > 0) ", $unreadCount unread" else ""
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(
                    horizontal = FlashSpacing.space16,
                    vertical = FlashSpacing.space12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Archive icon medallion
            Box(
                modifier = Modifier
                    .size(FlashDimensions.avatarSm)
                    .clip(FlashShapes.avatar)
                    .background(colors.accentSecondary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                FlashIcon(
                    icon = FlashIcons.Archive,
                    contentDescription = null,
                    tint = colors.accentPrimary,
                    size = FlashDimensions.iconMd,
                )
            }

            Spacer(modifier = Modifier.width(FlashSpacing.space16))

            FlashText(
                text = "Archived",
                modifier = Modifier.weight(1f),
                style = typography.bodyEmphasis,
                color = colors.textPrimary,
            )

            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .clip(CircleShape)
                        .background(colors.accentPrimary)
                        .padding(horizontal = FlashSpacing.space8),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = typography.captionEmphasis,
                        color = colors.textOnAccent,
                    )
                }
                Spacer(modifier = Modifier.width(FlashSpacing.space8))
            }

            FlashText(
                text = archivedCount.toString(),
                style = typography.metadataDefault,
                color = colors.textTertiary,
            )
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = FlashSpacing.space16 + FlashDimensions.avatarSm + FlashSpacing.space16),
                color = colors.borderSubtle,
            )
        }
    }
}

/**
 * Top bar displayed when navigating into archived conversations.
 */
@Composable
fun FlashArchivedChatsTopBar(
    onBackClick: () -> Unit,
    /** Null hides the action — same contract as `FlashChatListTopBar`. */
    onSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.backgroundSurface)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(FlashDimensions.headerHeight)
                .padding(horizontal = FlashSpacing.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(FlashDimensions.minTouchTarget),
            ) {
                FlashIcon(icon = FlashIcons.Back, contentDescription = "Back to chats")
            }
            FlashText(
                text = "Archived Chats",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FlashSpacing.space4),
                style = typography.headingMedium,
                color = colors.textPrimary,
            )
            if (onSearchClick != null) {
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(icon = FlashIcons.Search, contentDescription = "Search archived chats")
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun FlashArchivedChatsRowPreview() {
    FlashTheme {
        Column {
            FlashArchivedChatsRow(
                archivedCount = 4,
                unreadCount = 2,
                onClick = {},
            )
            FlashArchivedChatsRow(
                archivedCount = 1,
                unreadCount = 0,
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun FlashArchivedChatsTopBarPreview() {
    FlashTheme {
        FlashArchivedChatsTopBar(
            onBackClick = {},
            onSearchClick = {},
        )
    }
}
