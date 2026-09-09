package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
public actual fun FlashVideoSurface(
    uri: String,
    isPlaying: Boolean,
    isMuted: Boolean,
    seekToMs: Long?,
    onPlaybackStateChanged: (durationMs: Long, positionMs: Long, isCompleted: Boolean) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) {
    // Desktop stub — in-app video playback is provided on Android via VideoView
}
