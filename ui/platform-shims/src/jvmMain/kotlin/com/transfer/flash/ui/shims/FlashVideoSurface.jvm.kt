package com.transfer.flash.ui.shims

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrain
import com.sun.jna.Platform
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

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
    val playing by rememberUpdatedState(isPlaying)
    val muted by rememberUpdatedState(isMuted)
    val seek by rememberUpdatedState(seekToMs)
    val reportPlayback by rememberUpdatedState(onPlaybackStateChanged)
    val reportError by rememberUpdatedState(onError)
    val reportSeek by rememberUpdatedState(onSeekHandled)
    val reportFirstFrame by rememberUpdatedState(onFirstFrame)
    val reportBuffering by rememberUpdatedState(onBuffering)
    var frame by remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri) {
        // Only Windows ships an inline Media Foundation backend in this batch; Linux/macOS
        // truthfully report unavailable so FlashMediaViewer offers "Open in system player".
        if (!Platform.isWindows()) {
            reportError("In-app playback is unavailable on this platform")
            return@LaunchedEffect
        }
        val worker = Executors.newSingleThreadExecutor { work ->
            Thread(work, "Flash Media Foundation").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        var player: WindowsVideoPlayer? = null
        try {
            reportBuffering(true)
            withContext(worker) { player = WindowsVideoPlayer.open(uri) }
            val openPlayer = checkNotNull(player)
            var appliedPlaying: Boolean? = null
            var appliedMuted: Boolean? = null
            var firstFrameReady = false
            var wasBuffering = true
            var wasEnded = false
            var appliedSeek: Long? = null
            var lastReport = 0L
            var activeLoadingNs = 0L
            var lastTickNs = System.nanoTime()
            while (isActive) {
                val requestPlay = playing
                val requestMute = muted
                val requestSeek = seek
                val sample = withContext(worker) {
                    val newSeek = requestSeek?.takeUnless { it == appliedSeek }
                    if (appliedPlaying != requestPlay || appliedMuted != requestMute || newSeek != null) {
                        openPlayer.control(requestPlay, requestMute, newSeek)
                        appliedPlaying = requestPlay
                        appliedMuted = requestMute
                    }
                    openPlayer.poll()
                }
                appliedSeek = requestSeek
                if (requestSeek != null && seek == requestSeek && !sample.buffering) reportSeek()
                if (sample.buffering != wasBuffering) {
                    reportBuffering(sample.buffering)
                    wasBuffering = sample.buffering
                }
                sample.frame?.let { decoded ->
                    val info = ImageInfo(decoded.width, decoded.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                    // A recorded Compose layer can still hold the previous bitmap.
                    frame = Image.makeRaster(info, decoded.bytes, decoded.width * 4).use { it.toComposeImageBitmap() }
                    if (!firstFrameReady) {
                        firstFrameReady = true
                        reportFirstFrame()
                    }
                }
                val now = System.nanoTime()
                val stepNs = (now - lastTickNs).coerceAtLeast(0L)
                lastTickNs = now
                if (!sample.ended) wasEnded = false
                if (seek == null && !sample.buffering &&
                    (if (sample.ended) !wasEnded else now - lastReport >= 200_000_000L)
                ) {
                    reportPlayback(sample.durationMs, sample.positionMs, sample.ended)
                    lastReport = now
                    wasEnded = sample.ended
                }
                if (!firstFrameReady) {
                    if (sample.ended) {
                        reportError("Windows did not produce a video frame")
                        break
                    }
                    if (requestPlay) {
                        activeLoadingNs += stepNs
                        if (activeLoadingNs >= 8_000_000_000L) {
                            reportError("Windows did not produce a video frame")
                            break
                        }
                    } else {
                        activeLoadingNs = 0L
                    }
                }
                delay(if (requestPlay || sample.buffering || !firstFrameReady) 16L else 40L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            reportError("Windows couldn't play this video")
        } catch (missing: LinkageError) {
            reportError("Windows Media Foundation is unavailable")
        } finally {
            try {
                withContext(NonCancellable + worker) { player?.close() }
            } finally {
                worker.close()
                frame = null
            }
        }
    }

    Layout(
        content = {},
        modifier = modifier.drawBehind {
            val image = frame ?: return@drawBehind
            val width = image.width.toFloat()
            val height = image.height.toFloat()
            val scale = minOf(size.width / width, size.height / height)
            if (scale <= 0f) return@drawBehind
            val fittedWidth = (width * scale).roundToInt().coerceAtLeast(1)
            val fittedHeight = (height * scale).roundToInt().coerceAtLeast(1)
            drawImage(
                image = image,
                dstOffset = IntOffset(((size.width - fittedWidth) / 2).roundToInt(), ((size.height - fittedHeight) / 2).roundToInt()),
                dstSize = IntSize(fittedWidth, fittedHeight),
            )
        },
    ) { _, constraints ->
        val size = measureVideoSurfaceSize(
            constraints = constraints,
            frameWidth = frame?.width ?: 0,
            frameHeight = frame?.height ?: 0,
        )
        layout(size.width, size.height) {}
    }
}

internal fun measureVideoSurfaceSize(
    constraints: Constraints,
    frameWidth: Int,
    frameHeight: Int,
): IntSize {
    if (constraints.hasFixedWidth && constraints.hasFixedHeight) {
        return IntSize(constraints.maxWidth, constraints.maxHeight)
    }
    val preferred = if (frameWidth > 0 && frameHeight > 0) {
        IntSize(frameWidth, frameHeight)
    } else {
        val fallbackWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 640
        val fallbackHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else 360
        IntSize(fallbackWidth.coerceAtLeast(1), fallbackHeight.coerceAtLeast(1))
    }
    return constraints.constrain(preferred)
}
