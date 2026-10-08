// Modified by the Flash project: see third_party/webrtc-kmp/MODIFICATIONS.md (2026-10-08, Flash ERROR-123).
package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink

internal abstract class RenderedVideoStreamTrack(
    native: VideoTrack
) : MediaStreamTrackImpl(native), VideoStreamTrack {

    // Flash (ERROR-123): every native sink call goes through this lock and this set. A UI sink is
    // unbound by Compose some time AFTER the call engine closed the leg, and webrtc-java's
    // VideoTrack.removeSinkInternal then dereferenced freed native state (SIGSEGV on Linux, 2026-10-08,
    // right after a group-call peer left). Once [detachSinks] ran, add/remove are no-ops in Java and
    // never reach native code.
    private val sinkLock = Any()
    private val sinks = LinkedHashSet<VideoTrackSink>()
    private var detached = false

    override fun addSink(sink: VideoTrackSink) {
        synchronized(sinkLock) {
            if (detached || !sinks.add(sink)) return
            (native as VideoTrack).addSink(sink)
        }
    }

    override fun removeSink(sink: VideoTrackSink) {
        synchronized(sinkLock) {
            if (detached || !sinks.remove(sink)) return
            (native as VideoTrack).removeSink(sink)
        }
    }

    /**
     * Removes every sink while the native track is still alive and refuses new ones. Called when the
     * track ends (its connection is closing); idempotent.
     */
    internal fun detachSinks() {
        synchronized(sinkLock) {
            if (detached) return
            detached = true
            val track = native as VideoTrack
            sinks.toList().forEach { sink -> runCatching { track.removeSink(sink) } }
            sinks.clear()
        }
    }
}
