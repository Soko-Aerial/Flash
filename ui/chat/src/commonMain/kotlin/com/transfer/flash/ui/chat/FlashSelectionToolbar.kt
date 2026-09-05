package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * UI-007 Contextual Selection Action Bar.
 *
 * Appears when multiple messages are selected, showing selection count
 * and batch actions (Close, Reply, Copy, Forward, Delete).
 */
@Composable
fun FlashSelectionToolbar(
    selectedCount: Int,
    onClose: () -> Unit,
    onCopy: () -> Unit,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
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
                .padding(horizontal = FlashSpacing.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .size(FlashSpacing.space40)
                .semantics { contentDescription = "Exit selection mode" },
        ) {
            FlashIcon(
                icon = FlashIcons.Close,
                contentDescription = null,
                tint = colors.textPrimary,
            )
        }

        Spacer(modifier = Modifier.width(FlashSpacing.space8))

        Text(
            text = "$selectedCount selected",
            style = typography.headingSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )

        // Reply (single item only)
        if (selectedCount == 1) {
            IconButton(
                onClick = onReply,
                modifier = Modifier
                    .size(FlashSpacing.space40)
                    .semantics { contentDescription = "Reply to selected message" },
            ) {
                FlashIcon(
                    icon = FlashIcons.Reply,
                    contentDescription = null,
                    tint = colors.textPrimary,
                )
            }
        }

        IconButton(
            onClick = onCopy,
            modifier = Modifier
                .size(FlashSpacing.space40)
                .semantics { contentDescription = "Copy selected messages" },
        ) {
            FlashIcon(
                icon = FlashIcons.Edit,
                contentDescription = null,
                tint = colors.textPrimary,
            )
        }

        IconButton(
            onClick = onForward,
            modifier = Modifier
                .size(FlashSpacing.space40)
                .semantics { contentDescription = "Forward selected messages" },
        ) {
            FlashIcon(
                icon = FlashIcons.Forward,
                contentDescription = null,
                tint = colors.textPrimary,
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .size(FlashSpacing.space40)
                .semantics { contentDescription = "Delete selected messages" },
        ) {
            FlashIcon(
                icon = FlashIcons.Delete,
                contentDescription = null,
                tint = colors.textError,
            )
        }
    }
}
}

@Preview(name = "Selection Toolbar", showBackground = true)
@Composable
private fun FlashSelectionToolbarPreview() {
    FlashTheme {
        FlashSelectionToolbar(
            selectedCount = 2,
            onClose = {},
            onCopy = {},
            onReply = {},
            onForward = {},
            onDelete = {},
        )
    }
}
