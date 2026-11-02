package com.transfer.flash.ui.calling

import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.MediaStreamTrackState
import com.shepeliev.webrtckmp.MediaTrackConstraints
import com.shepeliev.webrtckmp.MediaTrackSettings
import com.shepeliev.webrtckmp.VideoStreamTrack
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import dev.onvoid.webrtc.media.video.VideoTrackSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * OPT-O11-06: the 1:1 call tile kept its desktop sink bound to the remote track while `FlashRemoteVideoPlaceholder`
 * covered it (data saver on, or the other camera off), so every decoded frame was still copied, scaled and rasterised
 * into a Skia image nobody could see. The fix is the track the tile is given — [mainVideoTrack] returns null in that
 * state, which unbinds the sink without uncomposing the surface.
 *
 * Proven here without a call: a stand-in track forwards frames to whatever sinks are bound to it, exactly as
 * webrtc-java's native track does, so "does the tile still rasterise" is an assertion about counted frames rather
 * than about code that looks right.
 */
class DesktopVideoSinkBindingTest {

    /** A [VideoStreamTrack] that forwards frames to its bound sinks, like webrtc-java's native track. */
    private class FakeVideoTrack(override val id: String = "remote-1") : VideoStreamTrack {
        private val sinks = mutableListOf<VideoTrackSink>()
        override val kind: MediaStreamTrackKind = MediaStreamTrackKind.Video
        override val label: String = "camera"
        override var enabled: Boolean = true
        override val state: StateFlow<MediaStreamTrackState> =
            MutableStateFlow<MediaStreamTrackState>(MediaStreamTrackState.Live(muted = false))
        override val constraints: MediaTrackConstraints = MediaTrackConstraints()
        override val settings: MediaTrackSettings = MediaTrackSettings()
        val boundSinks: Int get() = sinks.size

        /** Scale-conversion attempts across every frame this track handed out. */
        @Volatile
        var scaleAttempts: Int = 0

        override fun addSink(sink: VideoTrackSink) {
            sinks += sink
        }

        override fun removeSink(sink: VideoTrackSink) {
            sinks -= sink
        }

        /** One decoded frame delivered to whoever is bound now (the sink owns the frame's reference). */
        fun deliver(width: Int, height: Int) {
            val buffer = CountingBuffer(width, height) { scaleAttempts++ }
            val frame = VideoFrame(buffer, 0L)
            val bound = sinks.toList()
            if (bound.isEmpty()) {
                frame.release()
                return
            }
            bound.forEach { it.onVideoFrame(frame) }
        }

        override suspend fun switchCamera(deviceId: String?) = Unit

        override fun stop() = Unit
    }

    /**
     * A frame buffer that counts [cropAndScale] — the per-frame scale conversion the hidden tile used to pay for. The
     * conversion itself needs a native I420 buffer (`I420Buffer` has no allocator in webrtc-java 0.19.0), so the
     * assertion is about the work the sink starts, which is the cost the finding is about.
     */
    private class CountingBuffer(
        private val w: Int,
        private val h: Int,
        private val onScale: () -> Unit,
    ) : VideoFrameBuffer {
        override fun getWidth(): Int = w
        override fun getHeight(): Int = h
        override fun toI420(): I420Buffer = throw UnsupportedOperationException()
        override fun cropAndScale(x: Int, y: Int, cw: Int, ch: Int, sw: Int, sh: Int): VideoFrameBuffer {
            onScale()
            return CountingBuffer(sw, sh) { }
        }
        override fun retain() = Unit
        override fun release() = Unit
    }

    private class StubTrack(private val name: String) : VideoStreamTrack {
        override val id: String = name
        override val kind: MediaStreamTrackKind = MediaStreamTrackKind.Video
        override val label: String = "camera"
        override var enabled: Boolean = true
        override val state: StateFlow<MediaStreamTrackState> =
            MutableStateFlow<MediaStreamTrackState>(MediaStreamTrackState.Live(muted = false))
        override val constraints: MediaTrackConstraints = MediaTrackConstraints()
        override val settings: MediaTrackSettings = MediaTrackSettings()
        override fun addSink(sink: VideoTrackSink) = Unit
        override fun removeSink(sink: VideoTrackSink) = Unit
        override suspend fun switchCamera(deviceId: String?) = Unit
        override fun stop() = Unit
    }

    @Test
    fun coveredTileIsGivenNoTrackAtAll() {
        val local = StubTrack("local")
        val remote = StubTrack("remote")

        assertSame(
            "the remote picture is the tile while it is shown",
            remote,
            mainVideoTrack(remoteShown = true, swapped = false, localTrack = local, remoteTrack = remote),
        )
        assertSame(
            "a swapped tile shows this device's camera",
            local,
            mainVideoTrack(remoteShown = true, swapped = true, localTrack = local, remoteTrack = remote),
        )
        assertNull(
            "data saver or the other camera off: the placeholder is what the user sees, so nothing is bound",
            mainVideoTrack(remoteShown = false, swapped = false, localTrack = local, remoteTrack = remote),
        )
        assertNull(
            "and still nothing when the preview had been swapped into the tile",
            mainVideoTrack(remoteShown = false, swapped = true, localTrack = local, remoteTrack = remote),
        )
    }

    @Test
    fun aBoundSinkWorksOnArrivingFramesAndAnUnboundOneDoesNot() {
        val track = FakeVideoTrack()
        val sink = DesktopVideoSink()
        try {
            // A small tile, so a frame really goes through the scale conversion.
            sink.setTargetSize(64, 36)

            sink.bind(track)
            assertEquals(1, track.boundSinks)
            track.deliver(320, 180)
            assertEquals("a shown tile starts a scale conversion per frame", 1, track.scaleAttempts)

            // OPT-O11-06: this is what the fix does when the placeholder covers the tile.
            sink.bind(null)
            assertEquals("the track keeps no sink bound to the hidden tile", 0, track.boundSinks)
            repeat(5) { track.deliver(320, 180) }
            assertEquals(
                "no frame is touched for a picture nobody can see",
                1,
                track.scaleAttempts,
            )
            assertNull("and the covered tile draws nothing", sink.frameForDraw())
        } finally {
            sink.release()
        }
    }

    @Test
    fun unbindingDoesNotTearDownTheSurface() {
        // The renderer-lifetime invariant: track changes swap sinks and never release, so a placeholder appearing
        // and disappearing must not leave every later frame dropped.
        val track = FakeVideoTrack()
        val sink = DesktopVideoSink()
        try {
            sink.setTargetSize(64, 36)
            sink.bind(track)
            track.deliver(320, 180)
            sink.bind(null)
            sink.bind(track)
            sink.frameForDraw()
            val before = track.scaleAttempts
            track.deliver(320, 180)
            assertEquals("a re-bound sink still works on frames", before + 1, track.scaleAttempts)
        } finally {
            sink.release()
        }
    }
}
