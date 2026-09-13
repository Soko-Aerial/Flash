@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.calling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.common.logging.FlashLog
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * Desktop actual — Phase 25 S3b. The Android path renders through `SurfaceViewRenderer` (an AAR
 * type); the JVM path renders through **webrtc-java's own sink contract** into a Swing panel,
 * hosted in Compose by [SwingPanel]. Same lifetime contract as the Android actual (see the
 * expect declaration): the sink is created once and released only with the composable, while
 * track changes swap sink bindings and never release.
 *
 * Conversion: [VideoBufferConverter.convertFromI420] produces ARGB in one native call — no
 * hand-rolled YUV math — which is copied into a [BufferedImage] and painted scaled.
 */
@Composable
internal actual fun FlashCallVideoSurface(
    track: VideoStreamTrack?,
    fit: CallVideoFit,
    modifier: Modifier,
) {
    val panel = remember { FlashVideoPanel() }
    val holder = remember { DesktopVideoSink(panel) }
    panel.fit = fit

    SwingPanel(
        factory = { panel },
        modifier = modifier,
        update = { holder.bind(track) },
    )

    // Mirrors the Android actual: disposal is the ONLY release point.
    DisposableEffect(holder) {
        onDispose { holder.release() }
    }
}

/** Binds/unbinds a [VideoStreamTrack]'s JVM sink. Track changes swap sinks and never release. */
private class DesktopVideoSink(private val panel: FlashVideoPanel) {

    private var bound: VideoStreamTrack? = null

    fun bind(track: VideoStreamTrack?) {
        if (track === bound) return
        bound?.let { previous ->
            runCatching { previous.removeSink(panel) }
                .onFailure { FlashLog.w(TAG, "removeSink failed: ${it.message}") }
        }
        bound = track
        if (track == null) {
            panel.clear()
            return
        }
        runCatching { track.addSink(panel) }
            .onFailure { FlashLog.w(TAG, "addSink failed: ${it.message}") }
    }

    fun release() {
        bound?.let { track -> runCatching { track.removeSink(panel) } }
        bound = null
        panel.clear()
    }

    private companion object {
        const val TAG = "CALLUI"
    }
}

/**
 * A Swing panel that IS a webrtc-java [VideoTrackSink]: frames arrive on WebRTC's own thread,
 * are converted to an ARGB image, and repaint the panel. Scaling happens at paint time (the
 * image stays at native resolution), which keeps window resizes cheap.
 */
private class FlashVideoPanel : JPanel(), VideoTrackSink {

    private var image: BufferedImage? = null

    /** How the frame fills the panel. Set by the composable; read at paint time. */
    var fit: CallVideoFit = CallVideoFit.Balanced

    init {
        background = Color.BLACK
        preferredSize = Dimension(640, 360)
    }

    /** Called on a WebRTC thread — only the image reference and the repaint cross to the EDT. */
    override fun onVideoFrame(frame: VideoFrame) {
        val buffer = frame.buffer ?: return
        val width = buffer.width
        val height = buffer.height
        if (width <= 0 || height <= 0) return
        val next = try {
            val argb = ByteArray(width * height * 4)
            VideoBufferConverter.convertFromI420(buffer, argb, FourCC.ARGB)
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(
                    0,
                    0,
                    width,
                    height,
                    IntArray(width * height) { i ->
                        val o = i * 4
                        ((argb[o + 3].toInt() and 0xFF) shl 24) or
                            ((argb[o].toInt() and 0xFF) shl 16) or
                            ((argb[o + 1].toInt() and 0xFF) shl 8) or
                            (argb[o + 2].toInt() and 0xFF)
                    },
                    0,
                    width,
                )
            }
        } catch (t: Throwable) {
            FlashLog.w(TAG, "frame conversion failed: ${t.message}")
            return
        }
        image = next
        repaint()
    }

    fun clear() {
        image = null
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val current = image ?: return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            // Fit: letterbox the whole frame. Balanced: scale to FILL the box (cropping the
            // overflow) — the desktop analogue of Android's SCALE_ASPECT_BALANCED, so a resized
            // window never shows pillarbox bars on the full-screen surface.
            val scale = when (fit) {
                CallVideoFit.Fit -> minOf(
                    width.toDouble() / current.width,
                    height.toDouble() / current.height,
                )
                CallVideoFit.Balanced -> maxOf(
                    width.toDouble() / current.width,
                    height.toDouble() / current.height,
                )
            }
            val dw = (current.width * scale).toInt()
            val dh = (current.height * scale).toInt()
            g2.drawImage(current, (width - dw) / 2, (height - dh) / 2, dw, dh, null)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val TAG = "CALLUI"
    }
}
