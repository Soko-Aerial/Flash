@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
) {
    val scaling = when (fit) {
        CallVideoFit.Balanced -> RendererCommon.ScalingType.SCALE_ASPECT_BALANCED
        CallVideoFit.Fit -> RendererCommon.ScalingType.SCALE_ASPECT_FIT
    }
    val holder = remember { FlashVideoSink() }

    AndroidView(
        factory = { context -> SurfaceViewRenderer(context).also { holder.attach(it, scaling) } },
        modifier = modifier,
        update = { holder.bind(track) },
        onRelease = { holder.release() },
    )

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

    fun attach(renderer: SurfaceViewRenderer, scalingType: RendererCommon.ScalingType) {
        view = renderer
        bound = null
        runCatching {
            renderer.init(WebRtc.rootEglBase.eglBaseContext, null)
            renderer.setScalingType(scalingType)
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
