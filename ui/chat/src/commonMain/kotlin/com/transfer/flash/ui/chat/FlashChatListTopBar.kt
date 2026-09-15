package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun FlashChatListTopBar(
    /** Null hides the action, matching [onNewGroupClick]/[onLanClick]. */
    onSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "Chats",
    onLanClick: (() -> Unit)? = null,
    /** Group Phase 1A: opens the create-group sheet; null hides the action. */
    onNewGroupClick: (() -> Unit)? = null,
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
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FlashSpacing.space12),
                style = typography.headingMedium,
                color = colors.textPrimary,
            )
            if (onNewGroupClick != null) {
                IconButton(
                    onClick = onNewGroupClick,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(icon = FlashIcons.Group, contentDescription = "New group")
                }
            }
            if (onSearchClick != null) {
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(icon = FlashIcons.Search, contentDescription = "Search chats")
                }
            }
            if (onLanClick != null) {
                IconButton(
                    onClick = onLanClick,
                    modifier = Modifier.size(FlashDimensions.minTouchTarget),
                ) {
                    FlashIcon(icon = FlashIcons.Connection, contentDescription = "LAN and devices")
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun FlashChatListTopBarPreview() {
    FlashTheme {
        FlashChatListTopBar(onSearchClick = {})
    }
}
