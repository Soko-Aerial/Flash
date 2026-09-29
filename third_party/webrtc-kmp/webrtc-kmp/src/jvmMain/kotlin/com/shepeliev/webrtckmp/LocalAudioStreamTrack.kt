package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSource

internal class LocalAudioStreamTrack(
    native: AudioTrack,
    private val audioSource: AudioTrackSource,
    override val constraints: MediaTrackConstraints,
) : MediaStreamTrackImpl(native), AudioStreamTrack {

    // Flash (webrtc-java 0.19, #287): the source holds a native reference of its own; the
    // track keeps what it needs, so dropping ours on stop frees the source with the track.
    override fun onStop() {
        audioSource.dispose()
    }
}
