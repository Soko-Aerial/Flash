package com.transfer.flash.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme

@Composable
fun FlashMessageActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onReactionSelected: (String) -> Unit = {},
    onActionSelected: (String) -> Unit = {},
) {
    if (!visible) return

    val colors = FlashTheme.colors

    FlashSheetHost(
        onDismiss = onDismiss,
        containerColor = colors.sheetSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = FlashSpacing.space20),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FlashSpacing.space20, vertical = FlashSpacing.space8),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                ReactionIcon(FlashIcons.ThumbUp, onReactionSelected)
                ReactionIcon(FlashIcons.Heart, onReactionSelected)
                ReactionIcon(FlashIcons.Bolt, onReactionSelected)
                ReactionIcon(FlashIcons.Sliders, onReactionSelected)
                ReactionIcon(FlashIcons.ThumbDown, onReactionSelected)
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = FlashSpacing.space8),
                color = colors.borderSubtle,
            )

            MessageActionRow(FlashIcons.Reply) { onActionSelected("reply"); onDismiss() }
            MessageActionRow(FlashIcons.Thread) { onActionSelected("thread"); onDismiss() }
            MessageActionRow(FlashIcons.Flag) { onActionSelected("flag"); onDismiss() }
            MessageActionRow(FlashIcons.Pin) { onActionSelected("pin"); onDismiss() }
        }
    }
}

@Composable
private fun ReactionIcon(
    icon: FlashIconSpec,
    onSelected: (String) -> Unit,
) {
    IconButton(
        onClick = { onSelected(icon.contentDescription) },
        modifier = Modifier.size(FlashSpacing.space40),
    ) {
        FlashIcon(icon = icon)
    }
}

@Composable
private fun MessageActionRow(
    icon: FlashIconSpec,
    onClick: () -> Unit,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space24, vertical = FlashSpacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space20),
    ) {
        FlashIcon(icon = icon, tint = colors.textSecondary)
        Text(
            text = icon.contentDescription,
            style = typography.bodyDefault,
            color = colors.textPrimary,
        )
    }
}
