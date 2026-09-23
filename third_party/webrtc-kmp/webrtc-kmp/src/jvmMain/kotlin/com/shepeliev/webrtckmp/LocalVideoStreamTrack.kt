// Modified by the Flash project (Apache-2.0 §4(b)); see third_party/webrtc-kmp/MODIFICATIONS.md.
package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack

internal class LocalVideoStreamTrack(
    native: VideoTrack,
    private val videoSource: VideoDeviceSource,
    private var currentDevice: VideoDevice? = null,
    override val settings: MediaTrackSettings,
) : RenderedVideoStreamTrack(native), VideoStreamTrack {

    init {
        videoSource.start()
    }

    override suspend fun switchCamera(deviceId: String?) {
        val devices = MediaDevices.getVideoCaptureDevices()
        if (devices.isEmpty()) return

        val targetDevice: VideoDevice? = if (deviceId != null) {
            devices.firstOrNull { it.descriptor == deviceId }
        } else {
            if (devices.size < 2) {
                // If there's only one camera, keep it running rather than stopping it
                return
            }
            val currentIndex = devices.indexOfFirst { it.descriptor == currentDevice?.descriptor }
            val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % devices.size else 0
            devices[nextIndex]
        }

        if (targetDevice != null && targetDevice.descriptor != currentDevice?.descriptor) {
            val capabilities = MediaDevices.getVideoCaptureCapabilities(targetDevice)
            if (capabilities.isNotEmpty()) {
                videoSource.stop()
                videoSource.setVideoCaptureDevice(targetDevice)
                videoSource.setVideoCaptureCapability(capabilities.first())
                videoSource.start()
                currentDevice = targetDevice
            }
        }
    }

    override fun onSetEnabled(enabled: Boolean) {
        native.isEnabled = enabled
    }

    override fun onStop() {
        videoSource.stop()
        videoSource.dispose()
    }
}
