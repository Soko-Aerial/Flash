package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform video rendering surface shim (UI-018 video playback).
 * Hosts the platform decoder's video surface beneath Flash's viewer controls.
 */
@Composable
public expect fun FlashVideoSurface(
    uri: String,
    isPlaying: Boolean,
    isMuted: Boolean = false,
    seekToMs: Long? = null,
    onPlaybackStateChanged: (durationMs: Long, positionMs: Long, isCompleted: Boolean) -> Unit = { _, _, _ -> },
    onError: (String) -> Unit = {},
    onSeekHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
    onFirstFrame: () -> Unit = {},
    onBuffering: (Boolean) -> Unit = {},
    onHostPaused: () -> Unit = {},
)
