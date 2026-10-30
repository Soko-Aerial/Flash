@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import android.content.Intent
import com.shepeliev.webrtckmp.AndroidCaptureHandle
import com.shepeliev.webrtckmp.AndroidScreenCapture
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.common.logging.FlashLog

/**
 * What the Android host must give the capturer (ADR-102). Both halves can only be done by the app: the consent dialog
 * is an Activity result, and Android 14+ refuses to project without a foreground service of type `mediaProjection`
 * already running (docs/calling/SCREEN-SHARE-DESIGN.md section 8).
 */
public interface AndroidScreenCaptureHost {
    /**
     * Shows the system consent dialog (`MediaProjectionManager.createScreenCaptureIntent()`) and returns the intent it
     * grants, or null when the person refused. Suspend: the answer arrives as an Activity result.
     */
    public suspend fun requestConsent(): Intent?

    /**
     * Starts the `mediaProjection` foreground service and returns once it is promoted. False when the platform refused
     * it (then the projection must not be opened at all: Android 14 throws `SecurityException` without it).
     */
    public suspend fun startProjection(): Boolean

    /** Stops the foreground service. Idempotent. */
    public fun stopProjection()
}

/**
 * The one place the app hands the capturer its consent flow and foreground service. Installed once in
 * `MainActivity.onCreate`, before any call; a host that never installs it means this device cannot present (the Share
 * row is not offered at all, and watching a share still works).
 */
public object AndroidScreenCaptureSupport {
    @Volatile
    private var host: AndroidScreenCaptureHost? = null

    /** Installs [host]; on a fresh process nothing is installed until the activity says so. */
    public fun install(host: AndroidScreenCaptureHost) {
        this.host = host
    }

    public fun uninstall() {
        host = null
    }

    internal fun current(): AndroidScreenCaptureHost? = host
}

/** Android actual (ADR-102): MediaProjection through the vendored `AndroidScreenCapture`, a host that can ask for it. */
internal actual fun defaultScreenCaptureProvider(): ScreenCaptureProvider = AndroidScreenCaptureProvider

internal object AndroidScreenCaptureProvider : ScreenCaptureProvider {
    /**
     * Presenting is possible exactly when a host installed its consent flow. Read per call, never cached, so a provider
     * created at process start is right once the activity has installed it.
     */
    override val supported: Boolean get() = AndroidScreenCaptureSupport.current() != null

    /**
     * The system's own dialog decides what is shared (Android 14+: the whole screen or a single app), so the app lists
     * one entry and the picker says the system will ask.
     */
    override val usesSystemPicker: Boolean get() = true

    override suspend fun listSources(): List<ShareSource> =
        if (!supported) {
            emptyList()
        } else {
            listOf(ShareSource(id = 0L, title = "This device's screen", kind = ShareSourceKind.SCREEN))
        }

    override suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle {
        val host = AndroidScreenCaptureSupport.current() ?: error("screen capture is not available on this device")
        // Order required by Android (developer.android.com/media/grow/media-projection, checked 2026-10-24):
        // consent first, then the mediaProjection foreground service, and only then getMediaProjection/createVirtualDisplay
        // (ScreenCapturerAndroid does both inside its startCapture).
        val consent = host.requestConsent() ?: error("screen capture was not allowed")
        if (!host.startProjection()) {
            host.stopProjection()
            error("screen capture needs the projection foreground service")
        }
        FlashLog.i("CALL", "screen capture open fps=$fps max=${maxWidth}x$maxHeight")
        val handle = try {
            AndroidScreenCapture.open(consent, fps, maxWidth, maxHeight)
        } catch (t: Throwable) {
            host.stopProjection()
            throw t
        }
        return AndroidHandle(handle, host)
    }

    private class AndroidHandle(
        private val handle: AndroidCaptureHandle,
        private val host: AndroidScreenCaptureHost,
    ) : ScreenCaptureHandle {
        override val previewTrack: VideoStreamTrack get() = handle.videoTrack
        override val width: Int get() = handle.width
        override val height: Int get() = handle.height
        override val frameCount: Long get() = handle.frameCount
        override val revoked: Boolean get() = handle.revoked
        override fun lastFrameAgeMs(): Long? = handle.lastFrameAgeMs()
        override suspend fun sendOn(sender: RtpSender) {
            sender.replaceTrack(handle.videoTrack)
        }
        override fun addTo(pc: PeerConnection, stream: MediaStream): RtpSender = pc.addTrack(handle.videoTrack, stream)
        override fun close() {
            try {
                handle.close()
            } finally {
                // The foreground service exists only for the projection: it goes away with it (idempotent).
                host.stopProjection()
            }
        }
    }
}
