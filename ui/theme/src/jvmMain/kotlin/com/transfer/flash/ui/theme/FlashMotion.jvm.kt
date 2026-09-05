package com.transfer.flash.ui.theme

import androidx.compose.runtime.Composable

/**
 * Desktop has no OS-level reduce-motion signal that Compose Desktop exposes, so motion stays on.
 *
 * There is no `ANIMATOR_DURATION_SCALE` equivalent in the JVM/AWT stack and no cross-platform
 * accessibility query, so reporting `false` is the honest answer rather than a stub for a value
 * we could compute: the alternative would be reading Windows `SPI_GETCLIENTAREAANIMATION` /
 * macOS `NSWorkspace.accessibilityDisplayShouldReduceMotion` through JNA, which is a platform
 * shim (Phase 19), not a theme concern.
 *
 * Note this is the *platform* signal only. Every [FlashMotion] member still honours its
 * `reduceMotion` flag, so once a desktop shim can answer the question, this `actual` is the
 * single line that has to change.
 */
@Composable
internal actual fun isReduceMotionOnPlatform(): Boolean = false
