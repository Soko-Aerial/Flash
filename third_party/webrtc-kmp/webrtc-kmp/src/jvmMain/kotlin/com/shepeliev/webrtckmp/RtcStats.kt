// Modified by the Flash project (Apache-2.0 §4(b)); see third_party/webrtc-kmp/MODIFICATIONS.md.
package com.shepeliev.webrtckmp

import dev.onvoid.webrtc.RTCStats

actual class RtcStats internal constructor(val native: RTCStats) {
    actual val timestampUs: Long = native.timestamp
    actual val type: String = native.type.name
    actual val id: String = native.id
    actual val members: Map<String, Any> = native.attributes
    actual override fun toString(): String = native.toString()
}
