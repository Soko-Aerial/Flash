package com.transfer.flash.ui.chat

/**
 * Platform capability seams for interactions on chat list rows:
 * - [isSwipeToDismissSupported]: Android true (horizontal drag archives/unarchives a row), Desktop false.
 * - [isRowRightClickMenuSupported]: Desktop true (secondary/right click opens Pin/Mute/Archive/Delete menu),
 *   Android false (long-press enters selection mode).
 */
internal expect val isSwipeToDismissSupported: Boolean

internal expect val isRowRightClickMenuSupported: Boolean
