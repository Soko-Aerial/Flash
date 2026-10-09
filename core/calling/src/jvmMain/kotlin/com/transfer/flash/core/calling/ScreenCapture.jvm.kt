@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.DesktopCaptureHandle
import com.shepeliev.webrtckmp.DesktopCaptureSource
import com.shepeliev.webrtckmp.DesktopScreenCapture
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.RtpSender
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.common.logging.FlashLog

/** Desktop actual (ADR-102): webrtc-java's desktop capturer through the vendored `DesktopScreenCapture`. */
internal actual fun defaultScreenCaptureProvider(): ScreenCaptureProvider = DesktopScreenCaptureProvider

internal object DesktopScreenCaptureProvider : ScreenCaptureProvider {
    override val supported: Boolean = true
    override val usesSystemPicker: Boolean get() = DesktopScreenCapture.isWayland

    /** The sources the last listing returned, by the id the UI was shown, so [open] can find the native description. */
    @Volatile
    private var listed: Map<Pair<Long, Boolean>, DesktopCaptureSource> = emptyMap()

    override suspend fun listSources(): List<ShareSource> {
        val native = DesktopScreenCapture.listSources()
        listed = native.associateBy { it.id to it.isWindow }
        // Wayland: the system's own picker opens when the capture starts, and the listing may be empty. One entry
        // stands for "whatever the system dialog lets the user choose" (not verified on a device, SHARE-08).
        if (native.isEmpty() && usesSystemPicker) {
            return listOf(ShareSource(id = 0L, title = "Choose in the system dialog", kind = ShareSourceKind.SCREEN))
        }
        return native.map { src ->
            ShareSource(
                id = src.id,
                title = src.title.ifBlank { if (src.isWindow) "Window" else "Screen" },
                kind = if (src.isWindow) ShareSourceKind.WINDOW else ShareSourceKind.SCREEN,
            )
        }
    }

    override suspend fun open(source: ShareSource, fps: Int, maxWidth: Int, maxHeight: Int): ScreenCaptureHandle {
        val isWindow = source.kind == ShareSourceKind.WINDOW
        val native = listed[source.id to isWindow] ?: DesktopCaptureSource(source.id, source.title, isWindow)
        FlashLog.i("CALL", "screen capture open source=${source.kind} fps=$fps max=${maxWidth}x$maxHeight")
        return DesktopHandle(DesktopScreenCapture.open(native, fps, maxWidth, maxHeight))
    }

    private class DesktopHandle(private val handle: DesktopCaptureHandle) : ScreenCaptureHandle {
        override val previewTrack: VideoStreamTrack get() = handle.videoTrack
        override val width: Int get() = handle.width
        override val height: Int get() = handle.height
        override val frameCount: Long get() = handle.frameCount
        override fun lastFrameAgeMs(): Long? = handle.lastFrameAgeMs()
        override suspend fun sendOn(sender: RtpSender) {
            sender.replaceTrack(handle.videoTrack)
        }
        override fun addTo(pc: PeerConnection, stream: MediaStream): RtpSender = pc.addTrack(handle.videoTrack, stream)
        override fun close() = handle.close()
    }
}
