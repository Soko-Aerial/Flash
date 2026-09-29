// Modified by the Flash project (Apache-2.0 §4(b)); see third_party/webrtc-kmp/MODIFICATIONS.md.
package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.RTCStats

actual class RtcStats internal constructor(val native: RTCStats) {
    actual val timestampUs: Long = native.timestamp
    /**
     * The W3C stats type (`inbound-rtp`, `candidate-pair`, …), as Android and browsers report it.
     * webrtc-java exposes an enum (`INBOUND_RTP`); its bare name matched none of the callers'
     * comparisons, so every desktop call read empty stats (Flash ERROR-078).
     */
    actual val type: String = native.type.name.lowercase().replace('_', '-')
    actual val id: String = native.id
    actual val members: Map<String, Any> = native.attributes
    actual override fun toString(): String = native.toString()
}
