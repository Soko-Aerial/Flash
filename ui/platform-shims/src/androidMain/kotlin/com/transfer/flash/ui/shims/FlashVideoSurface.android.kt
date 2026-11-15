package com.transfer.flash.ui.shims

import android.net.Uri
import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** TextureView keeps the decoded frame in the same compositing hierarchy as the Compose chrome. */
@androidx.annotation.OptIn(UnstableApi::class)
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
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    val mediaUri = remember(uri) { localVideoUri(uri) }
    if (mediaUri == null) {
        LaunchedEffect(uri) { onError("Video isn't available on this device") }
        return
    }

    val playbackChanged by rememberUpdatedState(onPlaybackStateChanged)
    val reportError by rememberUpdatedState(onError)
    val reportFirstFrame by rememberUpdatedState(onFirstFrame)
    val reportBuffering by rememberUpdatedState(onBuffering)
    val reportHostPaused by rememberUpdatedState(onHostPaused)

    key(mediaUri) {
        val player = remember {
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(AudioAttributes.DEFAULT, true)
                setHandleAudioBecomingNoisy(true)
            }
        }
        var aspectRatio by remember { mutableFloatStateOf(0f) }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    reportBuffering(playbackState == Player.STATE_BUFFERING)
                    playbackChanged(
                        player.duration.coerceAtLeast(0),
                        player.currentPosition.coerceAtLeast(0),
                        playbackState == Player.STATE_ENDED,
                    )
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    }
                }

                override fun onRenderedFirstFrame() = reportFirstFrame()

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
                            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                    ) {
                        reportHostPaused()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w("FLASH_VIDEO", "Playback failed (code=${error.errorCode})")
                    reportError("Couldn't play this video")
                }
            }
            player.addListener(listener)
            player.setMediaItem(MediaItem.fromUri(mediaUri))
            player.prepare()
            onDispose {
                player.removeListener(listener)
                player.release()
            }
        }

        DisposableEffect(lifecycle, player) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    player.pause()
                    reportHostPaused()
                }
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }

        LaunchedEffect(player, isPlaying, isMuted, seekToMs) {
            player.volume = if (isMuted) 0f else 1f
            if (seekToMs != null) {
                player.seekTo(seekToMs)
                onSeekHandled()
            }
            player.playWhenReady = isPlaying && lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
        }

        LaunchedEffect(player, isPlaying) {
            while (isPlaying && isActive) {
                if (player.duration != C.TIME_UNSET && player.playbackState != Player.STATE_ENDED) {
                    playbackChanged(player.duration, player.currentPosition.coerceAtLeast(0), false)
                }
                delay(200)
            }
        }

        AndroidView(
            factory = { viewContext -> TextureView(viewContext).also(player::setVideoTextureView) },
            modifier = modifier.layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = constraints.maxHeight
                val ratio = aspectRatio
                val fittedWidth = if (ratio > 0f) minOf(width, (height * ratio).roundToInt()) else width
                val fittedHeight = if (ratio > 0f) minOf(height, (width / ratio).roundToInt()) else height
                val surface = measurable.measure(Constraints.fixed(fittedWidth, fittedHeight))
                layout(width, height) {
                    surface.placeRelative((width - fittedWidth) / 2, (height - fittedHeight) / 2)
                }
            },
            update = { it.keepScreenOn = isPlaying },
        )
    }
}

private fun localVideoUri(source: String): Uri? {
    if (source.isBlank()) return null
    val uri = Uri.parse(source)
    return when (uri.scheme) {
        "content", "file", "android.resource" -> uri
        null -> File(source).takeIf(File::isAbsolute)?.let(Uri::fromFile)
        else -> null
    }
}
