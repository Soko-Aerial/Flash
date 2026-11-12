package com.transfer.flash.ui.chat

/**
 * Platform capability seam for desktop-specific chat ergonomics (Enter-to-send, click-to-lock
 * voice recording, media viewer prev/next buttons).
 */
internal expect val isDesktopChatPlatform: Boolean
