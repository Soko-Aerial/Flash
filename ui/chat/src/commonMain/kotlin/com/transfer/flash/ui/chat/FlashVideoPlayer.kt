package com.transfer.flash.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.messaging.model.FlashImageAttachmentUi
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.shims.FlashVideoSurface
import com.transfer.flash.ui.theme.FlashDimensions
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme

/** Opens a single local video with the same stage and controls as the conversation viewer. */
@Composable
fun FlashVideoPlayer(
    uri: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    onClose: () -> Unit = {},
    onOpenExternally: () -> Unit = {},
) {
    val items = remember(uri) {
        listOf(FlashMediaViewerItem(FlashImageAttachmentUi(uri, uri, isVideo = true), "", ""))
    }
    FlashMediaViewer(
        items = items,
        initialIndex = 0,
        initialPlayVideo = autoPlay,
        onDismiss = onClose,
        onPlayVideo = { onOpenExternally() },
        showActions = false,
        modifier = modifier,
    )
}

@Stable
internal class FlashVideoPlayback(autoPlay: Boolean) {
    var started by mutableStateOf(autoPlay)
        private set
    var playing by mutableStateOf(autoPlay)
        private set
    var firstFrame by mutableStateOf(false)
    var buffering by mutableStateOf(autoPlay)
    var completed by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var durationMs by mutableLongStateOf(0)
        private set
    var positionMs by mutableLongStateOf(0)
        private set
    var dragPositionMs by mutableStateOf<Long?>(null)
    var seekToMs by mutableStateOf<Long?>(null)

    val loading: Boolean
        get() = started && playing && (!firstFrame || buffering)
    val chromeMayHide: Boolean
        get() = playing && firstFrame && !buffering && dragPositionMs == null

    fun toggle() {
        started = true
        val result = FlashVideoPlayerMath.onPlayClick(completed, playing)
        completed = result.isCompleted
        result.seekToTarget?.let {
            seekToMs = it
            positionMs = it
        }
        playing = result.isPlaying
    }

    fun update(duration: Long, position: Long, atEnd: Boolean) {
        durationMs = duration
        positionMs = position
        if (atEnd) {
            playing = false
            completed = true
        }
    }

    fun finishSeek() {
        val target = dragPositionMs ?: return
        dragPositionMs = null
        positionMs = target
        seekToMs = target
        completed = false
    }

    fun pause() {
        playing = false
    }

    fun fail(message: String) {
        error = message
        playing = false
    }
}

@Composable
internal fun FlashMediaVideoSurface(
    uri: String,
    playback: FlashVideoPlayback,
    modifier: Modifier = Modifier,
) {
    FlashVideoSurface(
        uri = uri,
        isPlaying = playback.playing,
        seekToMs = playback.seekToMs,
        onPlaybackStateChanged = playback::update,
        onError = playback::fail,
        onSeekHandled = { playback.seekToMs = FlashVideoPlayerMath.onSeekHandled() },
        onFirstFrame = { playback.firstFrame = true },
        onBuffering = { playback.buffering = it },
        onHostPaused = playback::pause,
        modifier = modifier,
    )
}

/** One material for the dock and floating controls, with a contrast-preserving graphite tint. */
@Composable
internal fun Modifier.mediaGlass(shape: Shape): Modifier {
    val colors = FlashTheme.colors
    val backdrop = colors.mediaViewerBackdrop
    return clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(colors.mediaViewerChromeText.copy(alpha = 0.16f), backdrop.copy(alpha = 0.06f)),
            ),
        )
        .background(backdrop.copy(alpha = 0.85f))
        .border(0.5.dp, colors.mediaViewerChromeText.copy(alpha = 0.28f), shape)
}

@Composable
internal fun FlashMediaPlayControl(
    playback: FlashVideoPlayback,
    modifier: Modifier = Modifier,
) {
    val colors = FlashTheme.colors
    val label = when {
        playback.completed -> "Replay video"
        playback.playing -> "Pause video"
        else -> "Play video"
    }
    Box(
        modifier = modifier
            .size(68.dp)
            .mediaGlass(CircleShape)
            .clickable(role = Role.Button, onClick = playback::toggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (playback.loading && !FlashTheme.motion.reduceMotion) {
            CircularProgressIndicator(
                modifier = Modifier.size(52.dp).semantics { contentDescription = "Loading video" },
                color = colors.accentPrimary,
                strokeWidth = 2.dp,
            )
        }
        FlashIcon(
            icon = if (playback.playing) FlashIcons.Pause else FlashIcons.Play,
            size = FlashDimensions.iconLg,
            tint = colors.mediaViewerChromeText,
            contentDescription = null,
        )
    }
}

@Composable
internal fun FlashMediaTimeline(playback: FlashVideoPlayback) {
    val colors = FlashTheme.colors
    val foreground = colors.mediaViewerChromeText
    val position = (playback.dragPositionMs ?: playback.positionMs).coerceIn(0, playback.durationMs)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
    ) {
        FlashText(
            text = formatVideoDuration(position),
            style = FlashTheme.typography.metadataEmphasis,
            color = foreground,
            maxLines = 1,
        )
        Slider(
            value = if (playback.durationMs > 0) position.toFloat() / playback.durationMs else 0f,
            onValueChange = { playback.dragPositionMs = (it * playback.durationMs.toDouble()).toLong() },
            onValueChangeFinished = playback::finishSeek,
            enabled = playback.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = colors.accentPrimary,
                activeTrackColor = colors.accentPrimary,
                inactiveTrackColor = foreground.copy(alpha = 0.25f),
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Playback position" },
        )
        FlashText(
            text = formatVideoDuration(playback.durationMs),
            style = FlashTheme.typography.metadataDefault,
            color = foreground.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
}

@Composable
internal fun FlashMediaPlaybackError(
    message: String,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val foreground = FlashTheme.colors.mediaViewerChromeText
    Column(
        modifier = modifier.mediaGlass(RoundedCornerShape(16.dp))
            .padding(horizontal = FlashSpacing.space16, vertical = FlashSpacing.space12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FlashText(text = message, color = foreground, style = FlashTheme.typography.bodyEmphasis)
        Box(
            modifier = Modifier
                .sizeIn(minHeight = FlashDimensions.minTouchTarget)
                .clickable(role = Role.Button, onClick = onOpenExternally)
                .padding(horizontal = FlashSpacing.space8),
            contentAlignment = Alignment.Center,
        ) {
            FlashText("Open in system player", color = foreground, style = FlashTheme.typography.metadataEmphasis)
        }
    }
}

/** Formats a duration in milliseconds to m:ss (or h:mm:ss). */
internal fun formatVideoDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

public object FlashVideoPlayerMath {
    public data class PlayClickResult(
        val isCompleted: Boolean,
        val seekToTarget: Long?,
        val isPlaying: Boolean,
    )

    public fun onPlayClick(isCompleted: Boolean, isPlaying: Boolean): PlayClickResult {
        return if (isCompleted) {
            PlayClickResult(isCompleted = false, seekToTarget = 0L, isPlaying = true)
        } else {
            PlayClickResult(isCompleted = false, seekToTarget = null, isPlaying = !isPlaying)
        }
    }

    /**
     * Clears seekToTarget once handled by the platform video surface, so subsequent replay
     * clicks (which also target 0L) trigger a state transition and re-execute LaunchedEffect(seekToMs).
     */
    public fun onSeekHandled(): Long? = null

    /** Playback controls (center Play/Pause and bottom scrubber) are hidden whenever playback errors. */
    public fun showPlaybackControls(errorMessage: String?): Boolean = errorMessage == null

    /** Top bar remains visible on error so the user can always dismiss the viewer. */
    public fun showTopBar(
        errorMessage: String?,
        controlsVisible: Boolean,
        isPlaying: Boolean,
    ): Boolean = errorMessage != null || controlsVisible || !isPlaying
}
