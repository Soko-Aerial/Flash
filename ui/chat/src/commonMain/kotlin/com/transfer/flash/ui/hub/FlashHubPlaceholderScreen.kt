package com.transfer.flash.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashTheme

/** Copy of the shell-only Hub screen (UI-058 H3/H4). The real sections arrive in H7. */
object FlashHubPlaceholderCopy {
    const val TITLE = "Hub"
    const val BODY = "Your organisation's news, people and groups will appear here."
    const val BROWSE_GROUPS = "Browse department groups"
    const val REFRESH = "Refresh organisation directory"
}

/**
 * UI-058: stand-in for the Hub tab while the data phases (H6+) are not built. It is honest about
 * being empty and keeps the one action the old chat-list top bar carried for enrolled devices,
 * so moving "Browse department groups" out of that bar (H5) does not strand it.
 *
 * Supports pull-to-refresh and manual refresh via [onRefresh].
 * [onBrowseGroups] null hides the action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashHubPlaceholderScreen(
    modifier: Modifier = Modifier,
    onBrowseGroups: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    isRefreshing: Boolean = false,
) {
    val colors = FlashTheme.colors
    val typography = FlashTheme.typography

    val screenBody: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.backgroundApp)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = FlashSpacing.space16),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
        ) {
            Text(
                text = FlashHubPlaceholderCopy.TITLE,
                modifier = Modifier
                    .heightIn(min = FlashDimensions.headerHeight)
                    .padding(start = FlashSpacing.space12)
                    .semantics { heading() },
                style = typography.headingMedium,
                color = colors.textPrimary,
            )
            Text(
                text = FlashHubPlaceholderCopy.BODY,
                modifier = Modifier.padding(horizontal = FlashSpacing.space12),
                style = typography.bodyDefault,
                color = colors.textSecondary,
            )
            if (onBrowseGroups != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clickable(role = Role.Button, onClick = onBrowseGroups)
                        .padding(horizontal = FlashSpacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    FlashIcon(icon = FlashIcons.Relay, contentDescription = null)
                    Text(
                        text = FlashHubPlaceholderCopy.BROWSE_GROUPS,
                        style = typography.bodyEmphasis,
                        color = colors.textPrimary,
                    )
                }
            }
            if (onRefresh != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FlashDimensions.minTouchTarget)
                        .clickable(role = Role.Button, onClick = onRefresh)
                        .padding(horizontal = FlashSpacing.space12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
                ) {
                    FlashIcon(icon = FlashIcons.Retry, contentDescription = null)
                    Text(
                        text = FlashHubPlaceholderCopy.REFRESH,
                        style = typography.bodyEmphasis,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }

    if (onRefresh != null) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier.fillMaxSize(),
        ) {
            screenBody()
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) {
            screenBody()
        }
    }
}
