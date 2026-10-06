@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
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
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import kotlin.math.roundToInt

/**
 * Desktop actual — renders video frames directly into Compose via Skia, no AWT/Swing overlay.
 *
 * Same lifetime contract as the Android actual: the sink is created once and released only on
 * composable disposal, while track changes swap sink bindings and never release.
 *
 * **Memory and CPU (2026-09-29, ERROR-075).** The first version made two native Skia copies of
 * every frame at full size (`Image.makeRaster`, then `toComposeImageBitmap`) and never closed
 * either: the JVM heap only saw small wrappers, so the garbage collector had no reason to run
 * and native memory grew until the call ended. It also converted and uploaded every frame at
 * the sender's full resolution, however small the tile, even when the screen had not drawn the
 * previous one. Now:
 * - each frame is scaled down to the tile's pixel size first (`cropAndScale`, libyuv), so a
 *   1080p picture in a 320 px strip tile costs a 320 px conversion;
 * - a frame arriving while the previous one has not been drawn yet is dropped before any work;
 * - at most two Skia images exist per surface (the one on screen, the one waiting), and each is
 *   closed as soon as it's replaced;
 * - the frame is read inside the draw lambda, so a new frame redraws the canvas without
 *   recomposing anything.
 *
 * **Every frame is released (2026-09-29, ERROR-078).** webrtc-java's native sink hands each
 * frame over as a fresh full-size I420 *copy* with one reference taken for the Java side
 * (`VideoTrackSink::OnFrame`: `I420Buffer::Copy` + `AddRef`); the sink owns that reference and
 * must call [VideoFrame.release]. Nothing did, so every decoded and every camera frame leaked:
 * ~50 MB/s of native memory in a four-person call, with a flat JVM heap. [onVideoFrame] now
 * releases the frame on every path, including the ones that skip it.
 *
 * WebRTC frames carry a clockwise [VideoFrame.rotation] (0, 90, 180, 270): a phone held upright
 * sends landscape frames tagged 90 or 270. The canvas turns them, so the picture is upright, and
 * the fit is computed on the turned size: [CallVideoFit.Balanced] fills the box only when the
 * picture and the box have the same orientation, and otherwise shows the whole picture (a
 * portrait phone in a landscape window is complete, with bars at the sides).
 *
 * Every [STATS_PERIOD_MS] a surface that received frames logs one `CALL_RENDER` line (frames
 * in, drawn, dropped, source and output size, average conversion time) for device-test runs.
 */
@Composable
internal actual fun FlashCallVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    modifier: Modifier,
    zOrderMediaOverlay: Boolean,
    mirror: Boolean,
) {
    val holder = remember { DesktopVideoSink() }

    SideEffect { holder.fillWhenMatching = fit == CallVideoFit.Balanced }

    DisposableEffect(holder, track) {
        holder.bind(track)
        onDispose { }
    }

    DisposableEffect(holder) {
        onDispose { holder.release() }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { holder.setTargetSize(it.width, it.height) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Read in the draw phase: a new frame invalidates this draw only.
            holder.frameTick.longValue
            val frame = holder.frameForDraw() ?: return@Canvas
            val image = frame.image
            val rotation = frame.rotation
            val rawW = image.width.toFloat()
            val rawH = image.height.toFloat()
            if (rawW <= 0f || rawH <= 0f || size.width <= 0f || size.height <= 0f) return@Canvas

            val isRotated = rotation == 90 || rotation == 270
            val effectiveW = if (isRotated) rawH else rawW
            val effectiveH = if (isRotated) rawW else rawH
            val fill = fit == CallVideoFit.Balanced && sameOrientation(effectiveW, effectiveH, size.width, size.height)
            val scale = if (fill) {
                maxOf(size.width / effectiveW, size.height / effectiveH)
            } else {
                minOf(size.width / effectiveW, size.height / effectiveH)
            }
            if (!scale.isFinite() || scale <= 0f) return@Canvas

            val dstW = rawW * scale
            val dstH = rawH * scale
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.clipRect(Rect.makeWH(size.width, size.height))
                native.translate(size.width / 2f, size.height / 2f)
                if (mirror) native.scale(-1f, 1f)
                if (rotation != 0) native.rotate(rotation.toFloat())
                native.drawImageRect(
                    image,
                    Rect.makeWH(rawW, rawH),
                    Rect.makeXYWH(-dstW / 2f, -dstH / 2f, dstW, dstH),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
                native.restore()
            }
        }
    }
}

/** Whether a [w]×[h] picture and a [boxW]×[boxH] box are both portrait or both landscape. */
private fun sameOrientation(w: Float, h: Float, boxW: Float, boxH: Float): Boolean = (h > w) == (boxH > boxW)

/** One converted frame: a Skia image this surface owns and must close, plus its rotation. */
internal class ConvertedFrame(val image: SkiaImage, val rotation: Int)

/**
 * Binds/unbinds a [VideoStreamTrack]'s JVM sink and hands converted frames to the canvas.
 * Track changes swap sinks and never release.
 *
 * Threads: [onVideoFrame] runs on WebRTC's decode (or capture) thread; [frameForDraw],
 * [bind] and [release] on the UI thread. The two frame slots are guarded by `this`.
 */
internal class DesktopVideoSink : VideoTrackSink {

    /** Bumped for every frame ready to draw. */
    val frameTick = mutableLongStateOf(0L)

    /** Balanced: fill the box when the picture has its orientation (see [sameOrientation]). */
    @Volatile var fillWhenMatching: Boolean = true

    @Volatile private var targetW = 0
    @Volatile private var targetH = 0

    private var bound: VideoStreamTrack? = null
    private var boundLabel: String = "-"
    private var byteBuffer: ByteArray? = null

    // Guarded by `this`.
    private var pending: ConvertedFrame? = null
    private var displayed: ConvertedFrame? = null
    private var released = false

    // Stats, written on the frame thread (drawn on the UI thread).
    private var statsSince = 0L
    private var framesIn = 0
    private var framesConverted = 0
    private var framesDropped = 0
    private var convertNanos = 0L
    @Volatile private var framesDrawn = 0
    private var lastSource = ""
    private var lastOutput = ""

    fun setTargetSize(width: Int, height: Int) {
        targetW = width
        targetH = height
    }

    fun bind(track: VideoStreamTrack?) {
        if (track === bound) return
        bound?.let { previous ->
            runCatching { previous.removeSink(this) }
                .onFailure { FlashLog.w(TAG, "removeSink failed: ${it.message}") }
        }
        bound = track
        clear()
        if (track == null) return
        boundLabel = track.id.take(8)
        resetStats()
        runCatching { track.addSink(this) }
            .onFailure { FlashLog.w(TAG, "addSink failed: ${it.message}") }
    }

    fun release() {
        bound?.let { track -> runCatching { track.removeSink(this) } }
        bound = null
        synchronized(this) {
            released = true
            closeFrames()
        }
    }

    private fun clear() {
        synchronized(this) { closeFrames() }
        frameTick.longValue++
    }

    /** Must hold `this`. */
    private fun closeFrames() {
        pending?.image?.close()
        pending = null
        displayed?.image?.close()
        displayed = null
    }

    /**
     * The frame to draw now: the waiting one if there is one (the one it replaces is closed —
     * a picture Compose already recorded holds its own reference), else the one on screen.
     */
    fun frameForDraw(): ConvertedFrame? = synchronized(this) {
        val next = pending
        if (next != null) {
            pending = null
            displayed?.image?.close()
            displayed = next
            framesDrawn++
        }
        displayed
    }

    override fun onVideoFrame(frame: VideoFrame) {
        // This sink owns the frame's reference (ERROR-078): release it on every path. Nothing
        // here keeps the buffer — the Skia image holds its own copy of the pixels.
        try {
            renderFrame(frame)
        } finally {
            runCatching { frame.release() }
        }
    }

    private fun renderFrame(frame: VideoFrame) {
        val buffer = frame.buffer ?: return
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return
        val now = System.nanoTime()
        if (statsSince == 0L) statsSince = now
        framesIn++
        val busy = synchronized(this) { released || pending != null }
        if (busy) {
            // The screen hasn't drawn the previous frame yet (or the tile is off screen):
            // skip this one before paying for its conversion.
            framesDropped++
            logStatsIfDue(now)
            return
        }
        val rotation = (frame.rotation % 360 + 360) % 360
        val (outW, outH) = outputSize(width, height, rotation)
        val scaled = if (outW < width || outH < height) {
            runCatching { buffer.cropAndScale(0, 0, width, height, outW, outH) }
                .onFailure { FlashLog.w(TAG, "cropAndScale failed: ${it.message}") }
                .getOrNull()
        } else {
            null
        }
        try {
            val source = scaled ?: buffer
            val w = source.width
            val h = source.height
            val size = w * h * 4
            var bytes = byteBuffer
            if (bytes == null || bytes.size < size) {
                bytes = ByteArray(size)
                byteBuffer = bytes
            }
            VideoBufferConverter.convertFromI420(source, bytes, FourCC.ARGB)
            val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            val image = SkiaImage.makeRaster(info, bytes, w * 4)
            val stale = synchronized(this) {
                if (released) {
                    image
                } else {
                    val old = pending
                    pending = ConvertedFrame(image, rotation)
                    old?.image
                }
            }
            stale?.close()
            framesConverted++
            convertNanos += System.nanoTime() - now
            val src = "${width}x$height"
            val out = "${w}x$h"
            if (src != lastSource || out != lastOutput) {
                FlashLog.i(TAG, "render track=$boundLabel source=$src output=$out rotation=$rotation")
                lastSource = src
                lastOutput = out
            }
            frameTick.longValue++
        } catch (t: Throwable) {
            FlashLog.w(TAG, "frame conversion failed: ${t.message}")
        } finally {
            scaled?.let { runCatching { it.release() } }
        }
        logStatsIfDue(now)
    }

    /**
     * The size to convert a [width]×[height] frame to: the tile's pixel size (for the rotated
     * picture and the current fit), never larger than the frame, rounded to even numbers for
     * the I420 scaler. The full frame when the tile size isn't known yet or is close enough.
     */
    private fun outputSize(width: Int, height: Int, rotation: Int): Pair<Int, Int> {
        val tw = targetW
        val th = targetH
        if (tw <= 0 || th <= 0) return width to height
        val rotated = rotation == 90 || rotation == 270
        val effW = if (rotated) height else width
        val effH = if (rotated) width else height
        val sx = tw.toFloat() / effW
        val sy = th.toFloat() / effH
        val fill = fillWhenMatching && sameOrientation(effW.toFloat(), effH.toFloat(), tw.toFloat(), th.toFloat())
        val scale = if (fill) maxOf(sx, sy) else minOf(sx, sy)
        if (!scale.isFinite() || scale >= DOWNSCALE_THRESHOLD) return width to height
        val w = even((width * scale).roundToInt()).coerceIn(MIN_SIDE, width)
        val h = even((height * scale).roundToInt()).coerceIn(MIN_SIDE, height)
        return w to h
    }

    private fun even(v: Int): Int = v and 1.inv()

    private fun logStatsIfDue(now: Long) {
        val elapsedNs = now - statsSince
        if (elapsedNs < STATS_PERIOD_MS * 1_000_000L) return
        val seconds = elapsedNs / 1e9
        val drawn = framesDrawn
        val avgMs = if (framesConverted > 0) convertNanos / framesConverted / 1e6 else 0.0
        FlashLog.i(
            TAG,
            "render stats track=$boundLabel in=${fps(framesIn, seconds)}fps " +
                "converted=${fps(framesConverted, seconds)}fps drawn=${fps(drawn, seconds)}fps " +
                "dropped=$framesDropped source=$lastSource output=$lastOutput " +
                "convertMs=${(avgMs * 10).roundToInt() / 10.0} target=${targetW}x$targetH",
        )
        resetStats(now)
    }

    private fun fps(frames: Int, seconds: Double): Double =
        if (seconds <= 0.0) 0.0 else (frames / seconds * 10).roundToInt() / 10.0

    private fun resetStats(now: Long = 0L) {
        statsSince = now
        framesIn = 0
        framesConverted = 0
        framesDropped = 0
        convertNanos = 0L
        framesDrawn = 0
    }

    private companion object {
        const val TAG = "CALL_RENDER"
        const val STATS_PERIOD_MS = 5_000L

        /** Scale only when the tile needs under 90 % of the frame's size. */
        const val DOWNSCALE_THRESHOLD = 0.9f
        const val MIN_SIDE = 16
    }
}
