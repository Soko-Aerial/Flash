package com.transfer.flash.ui.chat

/**
 * Platform capability seam for the touch swipe-to-dismiss gesture on chat list rows (OPT-O04-05).
 * - Android: true - a horizontal drag archives/unarchives a row.
 * - Desktop: false - no `SwipeToDismissBox` is allocated per row, and **the swipe gesture is gone on Windows**.
 *
 * The desktop keeps archive reachable through long-press -> selection mode -> Archive/Unarchive in the selection
 * action bar (`DesktopShell` wires `onArchiveSelected`/`onUnarchiveSelected`). There is deliberately **no right-click
 * context menu** on a chat row; do not read this as "desktop has another gesture". `PERF-U02` is the check that this
 * path works on a real desktop build.
 */
internal expect val isSwipeToDismissSupported: Boolean
