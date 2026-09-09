package com.transfer.flash.ui.shims

import android.net.Uri
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

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
    val videoViewRef = remember { arrayOf<VideoView?>(null) }
    val isPreparedRef = remember { booleanArrayOf(false) }

    // Handle seek
    LaunchedEffect(seekToMs) {
        val seek = seekToMs ?: return@LaunchedEffect
        val vv = videoViewRef[0] ?: return@LaunchedEffect
        if (isPreparedRef[0]) {
            vv.seekTo(seek.toInt())
        }
    }

    // Handle isPlaying
    LaunchedEffect(isPlaying) {
        val vv = videoViewRef[0] ?: return@LaunchedEffect
        if (!isPreparedRef[0]) return@LaunchedEffect
        if (isPlaying) {
            if (!vv.isPlaying) vv.start()
        } else {
            if (vv.isPlaying) vv.pause()
        }
    }

    // Ticker to report current position and duration while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val vv = videoViewRef[0]
            if (vv != null && isPreparedRef[0]) {
                val cur = vv.currentPosition.toLong().coerceAtLeast(0L)
                val dur = vv.duration.toLong().coerceAtLeast(0L)
                onPlaybackStateChanged(dur, cur, false)
            }
            kotlinx.coroutines.delay(250L)
        }
    }

    DisposableEffect(uri) {
        onDispose {
            videoViewRef[0]?.stopPlayback()
            videoViewRef[0] = null
            isPreparedRef[0] = false
        }
    }

    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                videoViewRef[0] = this

                setOnPreparedListener { mp ->
                    isPreparedRef[0] = true
                    val dur = mp.duration.toLong().coerceAtLeast(0L)
                    val cur = currentPosition.toLong().coerceAtLeast(0L)
                    if (isMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                    onPlaybackStateChanged(dur, cur, false)
                    if (isPlaying) {
                        start()
                    }
                }

                setOnCompletionListener {
                    val dur = duration.toLong().coerceAtLeast(0L)
                    onPlaybackStateChanged(dur, dur, true)
                }

                setOnErrorListener { _, what, extra ->
                    onError("Video playback error ($what, $extra)")
                    true
                }

                val resolved = when {
                    uri.startsWith("content://") -> Uri.parse(uri)
                    uri.startsWith("file://") -> {
                        val path = Uri.parse(uri).path ?: uri.removePrefix("file://")
                        Uri.fromFile(File(path))
                    }
                    uri.startsWith("file:") -> {
                        val path = Uri.parse(uri).path ?: uri.removePrefix("file:").trimStart('/')
                        Uri.fromFile(File(path))
                    }
                    else -> {
                        val f = File(uri)
                        if (f.exists()) Uri.fromFile(f) else Uri.parse(uri)
                    }
                }

                if (resolved.scheme == "file") {
                    setVideoPath(resolved.path ?: uri)
                } else {
                    setVideoURI(resolved)
                }
            }
        },
        modifier = modifier,
    )
}
