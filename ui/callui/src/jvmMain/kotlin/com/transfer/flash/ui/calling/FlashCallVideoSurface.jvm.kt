@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.common.logging.FlashLog
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.ImageInfo

/**
 * Desktop actual — renders video frames directly into Compose via Skia [ImageBitmap], eliminating
 * heavyweight AWT/Swing occlusion.
 *
 * Same lifetime contract as the Android actual: the sink is created once and released only on
 * composable disposal, while track changes swap sink bindings and never release.
 *
 * Conversion: [VideoBufferConverter.convertFromI420] converts the I420 buffer to BGRA in a single
 * native SIMD call into a reused buffer, and [SkiaImage.makeRaster] creates the Skia image without
 * manual pixel copy loops.
 */
@Composable
internal actual fun FlashCallVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    modifier: Modifier,
) {
    val frameState = remember { mutableStateOf<ImageBitmap?>(null) }
    val holder = remember { DesktopVideoSink(frameState) }

    DisposableEffect(holder, track) {
        holder.bind(track)
        onDispose { }
    }

    DisposableEffect(holder) {
        onDispose { holder.release() }
    }

    val bitmap = frameState.value
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = when (fit) {
                    CallVideoFit.Fit -> ContentScale.Fit
                    CallVideoFit.Balanced -> ContentScale.Crop
                },
            )
        }
    }
}

/** Binds/unbinds a [VideoStreamTrack]'s JVM sink. Track changes swap sinks and never release. */
private class DesktopVideoSink(
    private val frameState: MutableState<ImageBitmap?>,
) : VideoTrackSink {

    private var bound: VideoStreamTrack? = null
    private var byteBuffer: ByteArray? = null

    fun bind(track: VideoStreamTrack?) {
        if (track === bound) return
        bound?.let { previous ->
            runCatching { previous.removeSink(this) }
                .onFailure { FlashLog.w(TAG, "removeSink failed: ${it.message}") }
        }
        bound = track
        if (track == null) {
            clear()
            return
        }
        runCatching { track.addSink(this) }
            .onFailure { FlashLog.w(TAG, "addSink failed: ${it.message}") }
    }

    fun release() {
        bound?.let { track -> runCatching { track.removeSink(this) } }
        bound = null
        clear()
    }

    fun clear() {
        frameState.value = null
    }

    override fun onVideoFrame(frame: VideoFrame) {
        val buffer = frame.buffer ?: return
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return
        try {
            val size = width * height * 4
            var bytes = byteBuffer
            if (bytes == null || bytes.size != size) {
                bytes = ByteArray(size)
                byteBuffer = bytes
            }
            VideoBufferConverter.convertFromI420(buffer, bytes, FourCC.BGRA)
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            val skiaImg = SkiaImage.makeRaster(info, bytes, width * 4)
            frameState.value = skiaImg.toComposeImageBitmap()
        } catch (t: Throwable) {
            FlashLog.w(TAG, "frame conversion failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "CALLUI"
    }
}
