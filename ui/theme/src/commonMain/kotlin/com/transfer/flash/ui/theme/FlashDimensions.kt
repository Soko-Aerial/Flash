package com.transfer.flash.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Flash layout dimensions for chat and shared components.
 */
object FlashDimensions {
    val minTouchTarget = 48.dp
    val iconSm = 18.dp
    val iconMd = 24.dp
    val iconLg = 28.dp

    val avatarXs = 28.dp
    val avatarSm = 32.dp
    val avatarMd = 36.dp
    val avatarLg = 48.dp
    val avatarXl = 64.dp

    /** Max bubble width as fraction of parent — apply at call site. */
    const val bubbleMaxWidthFraction = 0.78f

    /** Absolute cap for very wide screens / tablets. */
    val bubbleMaxWidth = 320.dp

    val composerMinHeight = 48.dp
    val composerMaxHeight = 160.dp
    val headerHeight = 56.dp
    val chatListRowHeight = 72.dp
    val unreadBadgeMinSize = 20.dp
    val borderHairline = 1.dp

    /** UI-006: scroll offset from the newest message that still counts as "at bottom". */
    val chatBottomStickThreshold = 48.dp
}
