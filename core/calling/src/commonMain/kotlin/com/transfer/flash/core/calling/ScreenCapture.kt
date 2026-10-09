package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoStreamTrack

/**
 * One open screen or window capture (ADR-102). The sessions hold it for as long as they present and never touch the
 * platform track themselves, so a test can stand in for it.
 */
public interface ScreenCaptureHandle {
    /** A picture of the capture for a local preview, or null when the platform has none. The sessions do not use it today. */
    public val previewTrack: VideoStreamTrack?

    /** Size of the latest captured frame; 0 before the first one. */
    public val width: Int
    public val height: Int

    /** Frames delivered so far. */
    public val frameCount: Long

    /** Milliseconds since the latest frame, or null before the first one. */
    public fun lastFrameAgeMs(): Long?

    /** Puts the capture on [sender] (`replaceTrack`, no renegotiation). Call on the media thread. */
    public suspend fun sendOn(sender: RtpSender)

    /** Adds the capture to [pc] as its video (a connection built while presenting). Call on the media thread. */
    public fun addTo(pc: PeerConnection, stream: MediaStream): RtpSender

    /**
     * Stops the capture. The track must already be off every sender. Idempotent. A native call: media thread, never the
     * UI thread (ERROR-123).
     */
    public fun close()
}

/** Opens captures. One per process; the host cannot choose another unless it passes its own to a session. */
public interface ScreenCaptureProvider {
    /** False when this platform cannot present at all (the Share button is then not offered). */
    public val supported: Boolean

    /** True when the system asks which screen to share (Wayland portal), so the app's own list may be short or empty. */
    public val usesSystemPicker: Boolean

    /** Screens first, then windows. Empty when the platform cannot list them. A native call: media thread. */
    public suspend fun listSources(): List<ShareSource>

    /** Opens [source]. Throws when the capturer cannot start. A native call: media thread. */
    public suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle
}

/** The provider for this platform: the desktop capturer on the JVM, none on Android yet (ADR-102). */
internal expect fun defaultScreenCaptureProvider(): ScreenCaptureProvider

/** A provider that cannot present. */
internal object NoScreenCapture : ScreenCaptureProvider {
    override val supported: Boolean = false
    override val usesSystemPicker: Boolean = false
    override suspend fun listSources(): List<ShareSource> = emptyList()
    override suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle =
        error("screen capture is not available on this platform")
}
