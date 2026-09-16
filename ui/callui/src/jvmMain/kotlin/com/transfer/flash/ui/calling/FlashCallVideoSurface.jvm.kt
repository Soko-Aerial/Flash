@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * Encapsulates a converted video frame and its WebRTC clockwise rotation in degrees.
 */
internal data class RenderedVideoFrame(
    val bitmap: ImageBitmap,
    val rotation: Int,
)

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
 *
 * WebRTC video frames carry a clockwise [VideoFrame.rotation] (0, 90, 180, 270) indicating device
 * sensor orientation. Mobile phones typically capture in landscape and tag frames with 90° or 270°
 * rotation when held in portrait. Rendering applies the clockwise transformation onto Compose's
 * hardware-accelerated canvas, properly orienting the stream upright and adapting both [CallVideoFit.Fit]
 * and [CallVideoFit.Balanced] to the effective rotated dimensions.
 */
@Composable
internal actual fun FlashCallVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    modifier: Modifier,
) {
    val frameState = remember { mutableStateOf<RenderedVideoFrame?>(null) }
    val holder = remember { DesktopVideoSink(frameState) }
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            filterQuality = FilterQuality.Medium
        }
    }

    DisposableEffect(holder, track) {
        holder.bind(track)
        onDispose { }
    }

    DisposableEffect(holder) {
        onDispose { holder.release() }
    }

    val currentFrame = frameState.value
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (currentFrame != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rotation = (currentFrame.rotation % 360 + 360) % 360
                val bmp = currentFrame.bitmap
                val rawW = bmp.width.toFloat()
                val rawH = bmp.height.toFloat()
                if (rawW <= 0f || rawH <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas

                val isRotated = rotation == 90 || rotation == 270
                val effectiveW = if (isRotated) rawH else rawW
                val effectiveH = if (isRotated) rawW else rawH

                val scale = when (fit) {
                    CallVideoFit.Fit -> minOf(size.width / effectiveW, size.height / effectiveH)
                    CallVideoFit.Balanced -> maxOf(size.width / effectiveW, size.height / effectiveH)
                }
                if (!scale.isFinite() || scale <= 0f) return@Canvas

                drawIntoCanvas { canvas ->
                    canvas.save()
                    canvas.clipRect(0f, 0f, size.width, size.height)
                    canvas.translate(size.width / 2f, size.height / 2f)
                    if (rotation != 0) {
                        canvas.rotate(rotation.toFloat())
                    }
                    val dstW = rawW * scale
                    val dstH = rawH * scale
                    val dstLeft = -dstW / 2f
                    val dstTop = -dstH / 2f
                    canvas.drawImageRect(
                        image = bmp,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bmp.width, bmp.height),
                        dstOffset = IntOffset(Math.round(dstLeft), Math.round(dstTop)),
                        dstSize = IntSize(Math.round(dstW), Math.round(dstH)),
                        paint = paint,
                    )
                    canvas.restore()
                }
            }
        }
    }
}

/** Binds/unbinds a [VideoStreamTrack]'s JVM sink. Track changes swap sinks and never release. */
private class DesktopVideoSink(
    private val frameState: MutableState<RenderedVideoFrame?>,
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
            VideoBufferConverter.convertFromI420(buffer, bytes, FourCC.ARGB)
            val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            val skiaImg = SkiaImage.makeRaster(info, bytes, width * 4)
            frameState.value = RenderedVideoFrame(
                bitmap = skiaImg.toComposeImageBitmap(),
                rotation = frame.rotation,
            )
        } catch (t: Throwable) {
            FlashLog.w(TAG, "frame conversion failed: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "CALLUI"
    }
}
