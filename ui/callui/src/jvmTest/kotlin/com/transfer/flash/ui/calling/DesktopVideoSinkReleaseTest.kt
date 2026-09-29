package com.transfer.flash.ui.calling

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ERROR-078: webrtc-java hands every frame to the sink as a full-size copy holding one reference for Java.
 * A sink that doesn't release it leaks the whole frame (~50 MB/s in a four-person call).
 */
class DesktopVideoSinkReleaseTest {

    private class CountingBuffer(private val w: Int, private val h: Int) : VideoFrameBuffer {
        var releases = 0
        override fun getWidth(): Int = w
        override fun getHeight(): Int = h
        override fun toI420(): I420Buffer = throw UnsupportedOperationException()
        override fun cropAndScale(x: Int, y: Int, cw: Int, ch: Int, sw: Int, sh: Int): VideoFrameBuffer =
            throw UnsupportedOperationException()
        override fun retain() = Unit
        override fun release() {
            releases++
        }
    }

    @Test
    fun framesSkippedBeforeConversionAreReleased() {
        val sink = DesktopVideoSink()
        val empty = CountingBuffer(0, 0)
        sink.onVideoFrame(VideoFrame(empty, 0L))
        assertEquals(1, empty.releases)

        // A released surface (tile gone) drops frames still in flight: they are released too.
        sink.release()
        val late = CountingBuffer(640, 360)
        sink.onVideoFrame(VideoFrame(late, 0L))
        assertEquals(1, late.releases)
    }

    @Test
    fun framesThatFailConversionAreReleased() {
        val sink = DesktopVideoSink()
        // toI420/cropAndScale throw here, standing in for any failure inside the conversion.
        val buffer = CountingBuffer(640, 360)
        sink.onVideoFrame(VideoFrame(buffer, 0L))
        assertEquals(1, buffer.releases)
    }
}
