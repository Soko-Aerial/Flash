@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.shepeliev.webrtckmp.WebRtc
import com.transfer.flash.core.common.logging.FlashLog
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Android actual: the original [SurfaceViewRenderer] path, moved here verbatim by S3b. The
 * lifetime rules live in the KDoc on the expect declaration; [FlashVideoSink] implements them.
 */
@Composable
internal actual fun FlashCallVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    modifier: Modifier,
    zOrderMediaOverlay: Boolean,
    mirror: Boolean,
) {
    // The renderer view always fills the box it is measured into and then CROPS the frame to that box's aspect ratio, whatever
    // scaling type it has (ERROR-748: a 16:9 desktop share on a portrait phone lost most of its width). So the scaling type is
    // left at FIT and the VIEW is sized to the picture instead ([callVideoSize]); the box around it supplies the bars.
    val scaling = RendererCommon.ScalingType.SCALE_ASPECT_FIT to RendererCommon.ScalingType.SCALE_ASPECT_FIT
    val holder = remember { FlashVideoSink() }
    // The upright size of the frame being drawn; zero until the first frame (then the view simply fills the box).
    var frame by remember { mutableStateOf(IntSize.Zero) }
    holder.onFrameSize = { width, height -> frame = IntSize(width, height) }

    Layout(
        content = {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).also {
                        if (zOrderMediaOverlay) {
                            it.setZOrderMediaOverlay(true)
                        }
                        holder.attach(it, scaling)
                    }
                },
                update = {
                    holder.bind(track)
                    it.setMirror(mirror)
                },
                onRelease = { holder.release() },
            )
        },
        modifier = if (frame == IntSize.Zero) modifier else modifier.background(Color.Black),
    ) { measurables, constraints ->
        val picture = measurables.first()
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = picture.measure(constraints)
            return@Layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val boxWidth = constraints.maxWidth
        val boxHeight = constraints.maxHeight
        val (width, height) = callVideoSize(fit, frame.width, frame.height, boxWidth, boxHeight)
        val placeable = picture.measure(Constraints.fixed(width, height))
        layout(boxWidth, boxHeight) { placeable.place((boxWidth - width) / 2, (boxHeight - height) / 2) }
    }

    // onRelease covers the view being discarded; this covers the composable leaving the tree
    // (call ended, screen dismissed). Both land on the same idempotent teardown.
    DisposableEffect(holder) {
        onDispose { holder.release() }
    }
}

/**
 * Owns one [SurfaceViewRenderer]'s EGL lifetime and its current sink binding. Confined to the
 * main thread: every entry point is an [AndroidView] callback or a Compose effect disposal.
 */
private class FlashVideoSink {

    private var view: SurfaceViewRenderer? = null
    private var bound: VideoStreamTrack? = null

    /** Told the upright size of each new frame size on the main thread; set by the composable. */
    var onFrameSize: ((width: Int, height: Int) -> Unit)? = null

    fun attach(
        renderer: SurfaceViewRenderer,
        scalingType: Pair<RendererCommon.ScalingType, RendererCommon.ScalingType>,
    ) {
        view = renderer
        bound = null
        runCatching {
            renderer.init(
                WebRtc.rootEglBase.eglBaseContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() = Unit

                    // Called on the render thread: hop to the main thread, and drop it if the view was released meanwhile.
                    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                        val upright = if (rotation % 180 == 0) videoWidth to videoHeight else videoHeight to videoWidth
                        renderer.post { if (view === renderer) onFrameSize?.invoke(upright.first, upright.second) }
                    }
                },
            )
            renderer.setScalingType(scalingType.first, scalingType.second)
            renderer.setEnableHardwareScaler(true)
        }.onFailure { FlashLog.w(TAG, "renderer init failed: ${it.message}") }
    }

    /** Points the surface at [track], detaching whatever it was showing before. */
    fun bind(track: VideoStreamTrack?) {
        if (track === bound) return
        val renderer = view ?: return
        // runCatching on both sides: a track can be stopped and disposed by the session
        // (peer hung up) between the flow emission and this frame's applyChanges.
        bound?.let { previous ->
            runCatching { previous.removeSink(renderer) }
                .onFailure { FlashLog.w(TAG, "removeSink failed: ${it.message}") }
        }
        bound = track
        if (track == null) {
            renderer.clearImage()
            return
        }
        runCatching { track.addSink(renderer) }
            .onFailure { FlashLog.w(TAG, "addSink failed: ${it.message}") }
    }

    /** Terminal: after this the renderer can never draw again. Idempotent. */
    fun release() {
        val renderer = view ?: return
        view = null
        bound?.let { track ->
            runCatching { track.removeSink(renderer) }
        }
        bound = null
        runCatching { renderer.release() }
    }

    private companion object {
        const val TAG = "CALLUI"
    }
}
