// Modified by the Flash project: see third_party/webrtc-kmp/MODIFICATIONS.md (2026-10-09, Flash ADR-102).
package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.media.video.VideoDesktopSource
import dev.onvoid.webrtc.media.video.VideoTrack

internal class DesktopVideoStreamTrack(
    native: VideoTrack,
    private val videoSource: VideoDesktopSource,
    override val settings: MediaTrackSettings,
) : RenderedVideoStreamTrack(native), VideoStreamTrack {

    init {
        videoSource.start()
    }

    override suspend fun switchCamera(deviceId: String?) {}

    override fun onSetEnabled(enabled: Boolean) {
        native.isEnabled = enabled
    }

    override fun onStop() {
        // Flash (ERROR-123 lesson): sinks come off while the native track is alive, before the source goes.
        detachSinks()
        videoSource.stop()
        videoSource.dispose()
    }
}
