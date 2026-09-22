# Core Calling Module (`:core:calling`)

The `:core:calling` module implements decentralized, real-time peer-to-peer audio and video calling over local area networks and Wi-Fi Direct. It is powered by WebRTC (via a vendored multiplatform fork of `webrtc-kmp`) and supports both 1:1 direct calls and full-mesh group calling without requiring an external signaling server.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-calling:2.0.0-beta")
}
```

*Note: In the Flash architecture, calling is isolated behind an optional compile-time seam in `FlashEngine`. If an app only needs file transfer and chat, this module can be omitted completely.*

---

## 2. Calling Architecture

1. **Signaling over Existing Session:**
   * Uses Flash's existing WebSocket/TLS data link to exchange SDP offers, SDP answers, and ICE candidates (`FLASH_CALL` frames). No external STUN or TURN servers are required on LAN.
2. **Mesh Group Calling:**
   * Group calls establish peer-to-peer WebRTC connections between all participants in a mesh topology.
   * Participant membership and media state (muted, camera off) are synchronized using `GroupPresence` and `GroupQuery` frames with strict caller identity validation.
3. **Adaptive Bitrate & Roaming Resilience:**
   * Handles network roams and AP switches with graceful ICE restarts (`callDisconnectGraceMs` up to 25s on low-tier hardware).
   * Dynamically adapts video resolution (360p, 540p, 1080p) and Opus packetization (20ms to 60ms DTX) according to [`FlashPerformanceMode`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt).

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashCalling`
Located in [`com.transfer.flash.core.calling`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling):

```kotlin
public interface FlashCalling {
    /** Reactive state of current incoming or ongoing call. */
    public val callState: StateFlow<FlashCallSessionState>

    /** Initiates an outbound 1:1 voice call. */
    public suspend fun startVoiceCall(peerId: String): FlashResult<Unit>

    /** Initiates an outbound 1:1 video call. */
    public suspend fun startVideoCall(peerId: String): FlashResult<Unit>

    /** Accepts an incoming call invitation. */
    public suspend fun acceptCall(withVideo: Boolean = false)

    /** Rejects or terminates an active call. */
    public suspend fun endCall()

    /** Toggles local microphone mute. */
    public fun setMuted(muted: Boolean)

    /** Toggles local camera video capture. */
    public fun setVideoEnabled(enabled: Boolean)

    /** Switches between front and back camera (Android). */
    public fun switchCamera()
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.calling.FlashCalling
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

fun handleCalls(calling: FlashCalling) {
    scope.launch {
        calling.callState.collect { state ->
            when (state) {
                is FlashCallSessionState.IncomingCall -> {
                    println("Incoming ${if (state.isVideo) "Video" else "Voice"} call from ${state.peerName}")
                    // To accept: calling.acceptCall(withVideo = state.isVideo)
                }
                is FlashCallSessionState.Connected -> {
                    println("Call connected! Audio/video streaming active.")
                }
                is FlashCallSessionState.Ended -> {
                    println("Call ended. Reason: ${state.reason}")
                }
                else -> {}
            }
        }
    }
}
```
