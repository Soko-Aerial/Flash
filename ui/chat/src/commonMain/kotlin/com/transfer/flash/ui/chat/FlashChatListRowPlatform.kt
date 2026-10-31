package com.transfer.flash.ui.chat

/**
 * Platform capability seam for touch swipe-to-dismiss gesture on chat list rows (OPT-O04-05).
 * - Android: true (touch drag gestures supported)
 * - Desktop: false (desktop shell uses context menus; eliminates per-row SwipeToDismissBox overhead)
 */
internal expect val isSwipeToDismissSupported: Boolean
