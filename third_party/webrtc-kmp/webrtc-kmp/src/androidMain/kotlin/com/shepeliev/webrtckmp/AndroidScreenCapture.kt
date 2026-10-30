// Added by the Flash project (Apache-2.0 §4(b)); see third_party/webrtc-kmp/MODIFICATIONS.md (2026-10-24, Flash ADR-102).
package com.shepeliev.webrtckmp

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import org.webrtc.Logging
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Screen capture for the Android backend (Flash ADR-102). `getDisplayMedia()` of this fork's upstream always captured
 * the whole display at the platform's frame rate; this object opens one consented projection at a frame rate and a
 * maximum size, exactly like [DesktopScreenCapture] does on the JVM, and reports the frames it delivers so the call
 * engine can watch for a dead capture.
 *
 * The caller owns the Android 14+ rules: the user's consent (an Activity result), the `mediaProjection` foreground
 * service started before the projection is used, and a status-bar revocation reaching [AndroidCaptureHandle.revoked].
 *
 * Checked against the shipped AAR (`io.github.webrtc-sdk:android:125.6422.05`, decompiled 2026-10-24): `startCapture`
 * obtains the projection, registers the callback passed to the constructor (so `onStop` is ours, not WebRTC's) and only
 * then creates the virtual display; `changeCaptureFormat` recreates it. That version has no `onCapturedContentResize`
 * handling, so a single-app share (Android 14+) is letterboxed inside the full-screen virtual display instead of being
 * tracked to the app's own size; the receiving surface letterboxes it again. Honest, but it wastes pixels — revisit with
 * `changeCaptureFormat` once a device shows how it looks.
 *
 * Every call here is a native call: use it from the call engine's media thread, not from the UI thread.
 */
public object AndroidScreenCapture {

    /**
     * Opens the projection [consent] stands for (the `Intent` from `MediaProjectionManager.createScreenCaptureIntent()`
     * that the user accepted) and returns a live track of the screen.
     *
     * At most [frameRate] frames per second are captured, at most [maxWidth] x [maxHeight] (never larger than the
     * screen). [onStopped] runs when the projection ends outside this app (the Android 15 status-bar chip, the screen
     * locked, another app taking the projection); it is not called for [AndroidCaptureHandle.close].
     *
     * Throws when the capturer cannot start; a half-open capture is closed before the exception leaves.
     */
    public fun open(
        consent: Intent,
        frameRate: Int,
        maxWidth: Int,
        maxHeight: Int,
        onStopped: () -> Unit = {},
    ): AndroidCaptureHandle {
        val videoSource = WebRtc.peerConnectionFactory.createVideoSource(/* isScreencast = */ true)
        val track = WebRtc.peerConnectionFactory.createVideoTrack("screen-" + UUID.randomUUID(), videoSource)
        val holder = HandleState(onStopped)
        val capturer = ScreenCapturerAndroid(consent, holder)
        val helper = SurfaceTextureHelper.create("ScreenCapture", WebRtc.rootEglBase.eglBaseContext)
        try {
            capturer.initialize(helper, WebRtc.applicationContext, videoSource.capturerObserver)
            val (width, height) = captureSize(maxWidth, maxHeight)
            capturer.startCapture(width, height, frameRate)
            val probe = AndroidFrameProbe()
            track.addSink(probe)
            println("[webrtc-android] screen capture opened fps=$frameRate size=${width}x$height")
            return AndroidCaptureHandle(holder, capturer, helper, videoSource, track, probe)
        } catch (t: Throwable) {
            holder.closed = true
            runCatching { capturer.stopCapture() }
            runCatching { helper.dispose() }
            runCatching { videoSource.dispose() }
            runCatching { track.dispose() }
            throw t
        }
    }

    /**
     * The size to capture at: the screen's real size, reduced (aspect kept) until it fits [maxWidth] x [maxHeight].
     * Even numbers in both directions, because an odd virtual display size is refused on some devices.
     *
     * `getMaximumWindowMetrics` (API 30+) is what the official page recommends; `Display.getRealMetrics` is deprecated as
     * of API 31 and returns the app window's bounds for a non-resizable app, so it is only the fallback here.
     */
    internal fun captureSize(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val (screenWidth, screenHeight) = screenSizePx()
        var width = screenWidth.coerceAtLeast(2)
        var height = screenHeight.coerceAtLeast(2)
        if (maxWidth > 0 && width > maxWidth) {
            height = (height.toLong() * maxWidth / width).toInt()
            width = maxWidth
        }
        if (maxHeight > 0 && height > maxHeight) {
            width = (width.toLong() * maxHeight / height).toInt()
            height = maxHeight
        }
        return (width and 1.inv()).coerceAtLeast(2) to (height and 1.inv()).coerceAtLeast(2)
    }

    private fun screenSizePx(): Pair<Int, Int> {
        val windowManager = WebRtc.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
}

/** Counts the frames a capture delivers and remembers their size. */
internal class AndroidFrameProbe : VideoSink {
    @Volatile var frames: Long = 0L
        private set
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set
    @Volatile var lastFrameAtNs: Long = 0L
        private set

    override fun onFrame(frame: VideoFrame) {
        // Deliberately no release(): the track owns the reference it hands to every sink (the same contract
        // SurfaceViewRenderer relies on).
        runCatching {
            width = frame.rotatedWidth
            height = frame.rotatedHeight
        }
        frames++
        lastFrameAtNs = System.nanoTime()
    }
}

/** The projection callback: records a revocation that did not come from [AndroidCaptureHandle.close]. */
internal class HandleState(private val onStopped: () -> Unit) : MediaProjection.Callback() {
    @Volatile var revoked: Boolean = false
        private set
    @Volatile var closed: Boolean = false

    override fun onStop() {
        if (closed) return
        revoked = true
        runCatching { onStopped() }
    }
}

/**
 * An open Android screen capture. [close] is idempotent and safe from any thread, but is a native call: not the UI
 * thread. The caller must already have taken [videoTrack] off every sender.
 */
public class AndroidCaptureHandle internal constructor(
    private val state: HandleState,
    private val capturer: ScreenCapturerAndroid,
    private val helper: SurfaceTextureHelper,
    private val videoSource: VideoSource,
    private val track: VideoTrack,
    private val probe: AndroidFrameProbe,
) {
    private val lock = Any()
    private var closed = false

    /** The local track, as the wrapper's public type, to hand to `RtpSender.replaceTrack` or `PeerConnection.addTrack`. */
    public val videoTrack: VideoStreamTrack = ScreenVideoStreamTrack(track)

    /** Frames delivered so far. */
    public val frameCount: Long get() = probe.frames

    /** Size of the latest frame; 0 before the first one. */
    public val width: Int get() = probe.width
    public val height: Int get() = probe.height

    /** Milliseconds since the latest frame; null before the first one. */
    public fun lastFrameAgeMs(): Long? =
        probe.lastFrameAtNs.takeIf { it != 0L }?.let { (System.nanoTime() - it) / 1_000_000L }

    /**
     * True when the projection was ended outside this app (the Android 15 status-bar chip, the screen locked, another
     * app taking it). The capture delivers no further frames; the caller stops presenting.
     */
    public val revoked: Boolean get() = state.revoked

    /**
     * Stops the capture. Order (Flash ERROR-123): every sink comes off while the native track is alive, the capturer
     * stops, the projection ends (which clears the status-bar chip and is NOT reported as a revocation), then the
     * helper, the source and the track are disposed.
     */
    public fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            state.closed = true
        }
        runCatching { track.removeSink(probe) }
        runCatching { capturer.stopCapture() }
        runCatching { helper.stopListening() }
        runCatching { capturer.mediaProjection?.stop() }
        runCatching { helper.dispose() }
        runCatching { track.dispose() }
        runCatching { videoSource.dispose() }
        println("[webrtc-android] screen capture closed")
    }
}

/**
 * The capture's local track. It reports its own frames through [MediaStreamTrack.state] like every track, and its
 * `stop()` does nothing to the native side: [AndroidCaptureHandle.close] owns the capturer, the source and the track,
 * and a sender that drops the track must not dispose them underneath it.
 *
 * `switchCamera` is refused for the same reason it is refused on a remote track: a screen has no second camera, and a
 * host that offered the control would otherwise get a silent nothing.
 */
internal class ScreenVideoStreamTrack(android: VideoTrack) : RenderedVideoStreamTrack(android) {
    override suspend fun switchCamera(deviceId: String?) {
        Logging.e("ScreenVideoStreamTrack", "switchCamera is not supported for a screen capture")
    }
}
