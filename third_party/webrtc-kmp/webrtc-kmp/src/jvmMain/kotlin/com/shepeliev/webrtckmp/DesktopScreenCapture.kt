// Added by the Flash project (Apache-2.0 §4(b)); see third_party/webrtc-kmp/MODIFICATIONS.md (2026-10-09, Flash ADR-102).
package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer

/** A screen or a window the desktop capturer can share. [id] is the capturer's own id. */
public class DesktopCaptureSource(
    public val id: Long,
    public val title: String,
    public val isWindow: Boolean,
)

/**
 * Screen and window capture for the desktop (JVM) backend. `getDisplayMedia()` of this fork's upstream never
 * chose a source and never set a frame rate; this object lists the sources and opens one for real.
 *
 * Every call here is a native call: use it from the call engine's media thread, not from the UI thread.
 */
public object DesktopScreenCapture {

    /**
     * True when the session looks like Wayland. There the capturer shows the system's own picker (xdg-desktop-portal)
     * when it starts, and [listSources] may return little or nothing: the caller shows its own note and opens a
     * default source. Not verified on a device (SHARE-08).
     */
    public val isWayland: Boolean
        get() = System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true) ||
            !System.getenv("WAYLAND_DISPLAY").isNullOrEmpty()

    /** Screens first, then windows. A capturer that fails to enumerate contributes nothing; never throws. */
    public fun listSources(): List<DesktopCaptureSource> {
        val out = ArrayList<DesktopCaptureSource>()
        out += enumerate(ScreenCapturer(), isWindow = false)
        out += enumerate(WindowCapturer(), isWindow = true)
        return out
    }

    private fun enumerate(capturer: DesktopCapturer, isWindow: Boolean): List<DesktopCaptureSource> = try {
        capturer.desktopSources.map { DesktopCaptureSource(it.id, it.title.orEmpty(), isWindow) }
    } catch (t: Throwable) {
        println("[webrtc-jvm] desktop source listing failed (window=$isWindow): ${t.message}")
        emptyList()
    } finally {
        runCatching { capturer.dispose() }
    }

    /**
     * Opens [source] and returns a live track of it. The capturer runs at most [frameRate] frames per second and
     * delivers at most [maxWidth]x[maxHeight].
     */
    public fun open(source: DesktopCaptureSource, frameRate: Int, maxWidth: Int, maxHeight: Int): DesktopCaptureHandle {
        val videoSource = VideoDesktopSource()
        // Configured BEFORE the track exists: DesktopVideoStreamTrack starts the source in its initialiser.
        videoSource.setSourceId(source.id, source.isWindow)
        videoSource.setFrameRate(frameRate)
        videoSource.setMaxFrameSize(maxWidth, maxHeight)
        val native = WebRtc.peerConnectionFactory.createVideoTrack("screen-" + source.id, videoSource)
        val track = try {
            DesktopVideoStreamTrack(native = native, videoSource = videoSource, settings = MediaTrackSettings())
        } catch (t: Throwable) {
            runCatching { videoSource.dispose() }
            throw t
        }
        val stream = MediaStream().apply { addTrack(track) }
        val probe = FrameProbe()
        track.addSink(probe)
        println("[webrtc-jvm] screen capture opened '${source.title}' window=${source.isWindow} fps=$frameRate max=${maxWidth}x$maxHeight")
        return DesktopCaptureHandle(track, stream, probe)
    }
}

/** Counts the frames a capture delivers and remembers their size. Releases every frame (Flash ERROR-078). */
internal class FrameProbe : VideoTrackSink {
    @Volatile var frames: Long = 0L
        private set
    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set
    @Volatile var lastFrameAtNs: Long = 0L
        private set

    override fun onVideoFrame(frame: VideoFrame) {
        try {
            val buffer = frame.buffer
            if (buffer != null) {
                width = buffer.width
                height = buffer.height
            }
            frames++
            lastFrameAtNs = System.nanoTime()
        } finally {
            runCatching { frame.release() }
        }
    }
}

/** An open capture. [close] is idempotent and safe from any thread, but is a native call: not the UI thread. */
public class DesktopCaptureHandle internal constructor(
    private val track: DesktopVideoStreamTrack,
    private val stream: MediaStream,
    private val probe: FrameProbe,
) {
    private val lock = Any()
    private var closed = false

    /** The track to hand to `RtpSender.replaceTrack`. */
    public val videoTrack: VideoStreamTrack get() = track

    /** Frames delivered so far. */
    public val frameCount: Long get() = probe.frames

    /** Size of the latest frame; 0 before the first one. */
    public val width: Int get() = probe.width
    public val height: Int get() = probe.height

    /** Milliseconds since the latest frame; null before the first one. */
    public fun lastFrameAgeMs(): Long? =
        probe.lastFrameAtNs.takeIf { it != 0L }?.let { (System.nanoTime() - it) / 1_000_000L }

    /**
     * Stops the capture. Order matters (Flash ERROR-123): every sink comes off while the native track is alive,
     * then the source stops, then it is disposed. The caller must already have taken this track off every sender.
     */
    public fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        runCatching { track.detachSinks() }
        runCatching { stream.release() } // stops the track: DesktopVideoStreamTrack.onStop stops and disposes the source
        println("[webrtc-jvm] screen capture closed")
    }
}
