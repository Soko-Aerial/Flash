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
    modifier: Modifier,
) {
    // Desktop stub — in-app video playback is provided on Android via VideoView.
    // Report once (LaunchedEffect, not direct: onError flips caller state and a direct call
    // would loop recompositions) so the player shows its error banner with the "open in
    // system player" fallback instead of a black surface. Real desktop playback needs a
    // player dependency (JavaFX/VLC/ffmpeg) — an R10 decision, asked separately.
    LaunchedEffect(uri) {
        onError("In-app video playback is not available on desktop yet")
    }
}
