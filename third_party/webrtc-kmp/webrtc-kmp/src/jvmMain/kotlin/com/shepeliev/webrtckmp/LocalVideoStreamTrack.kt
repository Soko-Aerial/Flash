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
    private val captureConstraints: MediaTrackConstraints = MediaTrackConstraints(),
) : RenderedVideoStreamTrack(native), VideoStreamTrack {

    /**
     * Flash (OPT-O11-02): whether [videoSource] is currently capturing. The source is stopped while
     * the track is disabled, so switching camera in that state must not bring the sensor back.
     */
    private var sourceRunning: Boolean = true

    init {
        videoSource.start()
        sourceRunning = true
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
                // Flash: the same choice as the first camera (the call's size), not the new
                // camera's first-listed mode.
                val capability = MediaDevicesImpl.getMatchingCapabilities(targetDevice, captureConstraints).firstOrNull()
                    ?: capabilities.first()
                videoSource.stop()
                videoSource.setVideoCaptureDevice(targetDevice)
                videoSource.setVideoCaptureCapability(capability)
                // Flash (OPT-O11-02): a switched camera stays off while the track is disabled.
                if (sourceRunning) {
                    videoSource.start()
                }
                currentDevice = targetDevice
            }
        }
    }

    override fun onSetEnabled(enabled: Boolean) {
        native.isEnabled = enabled
        // Flash (OPT-O11-02): `isEnabled = false` only silences the RTP sender — the webcam sensor
        // keeps capturing at 30 fps, the driver keeps converting and the OS keeps the camera in use
        // (LED lit). Android's onSetEnabled stops the capturer, so desktop must too.
        if (enabled) {
            if (!sourceRunning) {
                videoSource.start()
                sourceRunning = true
            }
        } else if (sourceRunning) {
            videoSource.stop()
            sourceRunning = false
        }
    }

    override fun onStop() {
        sourceRunning = false
        videoSource.stop()
        videoSource.dispose()
    }
}
