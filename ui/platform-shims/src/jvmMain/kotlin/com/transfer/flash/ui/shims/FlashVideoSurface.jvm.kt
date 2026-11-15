package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

@Composable
public actual fun FlashVideoSurface(
    uri: String,
    isPlaying: Boolean,
    isMuted: Boolean,
    seekToMs: Long?,
    onPlaybackStateChanged: (durationMs: Long, positionMs: Long, isCompleted: Boolean) -> Unit,
    onError: (String) -> Unit,
    onSeekHandled: () -> Unit,
    modifier: Modifier,
    onFirstFrame: () -> Unit,
    onBuffering: (Boolean) -> Unit,
    onHostPaused: () -> Unit,
) {
    // Desktop exposes the explicit system-player fallback until it has an embedded decoder.
    // Report once (LaunchedEffect, not direct: onError flips caller state and a direct call
    // would loop recompositions) so the player shows its error banner with the "open in
    // system player" fallback instead of a black surface. Real desktop playback needs a
    // player dependency (JavaFX/VLC/ffmpeg) — an R10 decision, asked separately.
    LaunchedEffect(uri) {
        onError("In-app playback is unavailable on desktop")
    }
}
