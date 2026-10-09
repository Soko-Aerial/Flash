# Core Calling Module (`:core:calling`)

The `:core:calling` module implements decentralized, real-time peer-to-peer audio and video calling over the local network (LAN or hotspot; there is no Wi-Fi Direct code). It is powered by WebRTC (via a vendored multiplatform fork of `webrtc-kmp`) and supports both 1:1 direct calls and full-mesh group calling without any signaling server.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-calling:v2.1.0-beta")
}
```

*Note: In the Flash architecture, calling is isolated behind an optional compile-time seam in `FlashEngine`. If an app only needs file transfer and chat, this module can be omitted completely.*

---

## 2. Calling Architecture

1. **Signaling over the existing session.** SDP offers and answers and ICE candidates travel as `FLASH_CALL` frames (`CallFrameCodec` / `CallWireFrame`) over the WebSocket session the network layer already has. WebRTC runs with **empty ICE servers**: host candidates connect on-link, so there is no STUN or TURN and no internet is needed (ADR-025).
2. **Mesh group calls.** `FlashGroupCallSession` opens one peer connection per other participant (a full mesh), so a sender encodes once per watcher. Limits in code: 12 participants for voice, 8 for video (`FlashGroupCallLimits`); the owner's tests found 3 to 4 devices realistic for group video. Membership and media state are exchanged with presence / query frames, and a call's members come from the group roster (ADR-061).
3. **Adaptation and recovery.** `CallQualityGovernor` and `GroupVideoTuning` step video and voice down under load (voice keeps priority); `RtpSenderTuning` applies the sender settings; `CallHealthMonitor` watches stats. A network roam triggers an ICE restart within `callDisconnectGraceMs` (from `FlashTransportProfile`). Video targets come from `FlashPerformanceMode` for 1:1 calls (LOW 360p, MEDIUM / HIGH more); **group video is capped lower** (HIGH 540p, MEDIUM and LOW 360p, ADR-098).
4. **Screen share (ADR-102).** `listShareSources`, `startScreenShare`, `stopScreenShare`, `setShareQuality`, `dismissShareNotice` on `FlashCalling`. Every receiver can watch; the **presenter exists on desktop only** (the Android presenter is not built). Because of the mesh, a presenter pays the encode per watcher. Not device-verified (`SHARE-*` tests).
5. **Media seam.** `FlashCallMedia` (platform WebRTC wrapper), `MediaAcquire` (mic/camera acquisition with explicit end reasons `MIC_DENIED` / `MIC_UNAVAILABLE` and an audio-only retry when the camera is refused) and the `PlatformMonitor` live in the module; the WebRTC native code comes from the vendored `webrtc-kmp` fork, published as `com.transfer.flash:webrtc-kmp[-android|-jvm]` (ADR-103). A desktop consumer must add a `dev.onvoid.webrtc:webrtc-java` native classifier.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashCalling`

Located in [`com.transfer.flash.core.calling.FlashCalling`](../../../../core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCalling.kt) (abridged; the interface is large and most members past the first block default to no-ops):

```kotlin
public interface FlashCalling {
    public val activeCall: StateFlow<FlashCallUiState?>                      // null = no call
    public val ongoingGroupCalls: StateFlow<Map<String, OngoingGroupCallUi>>
    public val stats: StateFlow<FlashCallStats?>
    public val media: FlashCallMedia?

    public suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean
    public suspend fun startGroupCall(groupId: String, groupName: String, memberIds: List<String>, video: Boolean): Boolean
    public suspend fun joinGroupCall(groupId: String, callId: String, memberIds: List<String>, video: Boolean = false): Boolean
    public suspend fun accept(audioOnly: Boolean = false): Boolean
    public suspend fun decline(): Boolean
    public suspend fun hangUp(): Boolean

    public fun toggleMute(): Boolean
    public fun toggleCamera(): Boolean
    public suspend fun switchCamera()
    public suspend fun upgradeToVideo(): Boolean   // 1:1 voice -> video mid-call (cv1), caller offers; group not built
    public fun setSpeaker(on: Boolean)

    // Routing hooks the host calls from its transport
    public suspend fun onInboundText(peerId: String, text: String): Boolean
    public fun onSignalingLost(peerId: String)
    public fun onSignalingRestored(peerId: String)
}
```

`FlashCallUiState` carries `callId`, `peerId`, `peerName`, `direction`, `video`, `state`
(`DIALING`, `RINGING`, `CONNECTING`, `ACTIVE`, `ENDED`), `endReason` (`NORMAL`, `DECLINED`, `NO_ANSWER`, `DISCONNECTED`,
`ERROR`, `MIC_DENIED`, `MIC_UNAVAILABLE`, ...), `connectedAt`, `micMuted`, `cameraOff`, `speakerOn`, `isGroup` and `groupId`.
The engine facade forwards inbound `FLASH_CALL` text to `onInboundText` (`FlashEngine.onInboundCallText`).

### 3.2 `CallCoordinator`

The class that actually implements calling for a host:

```kotlin
val coordinator = CallCoordinator(
    localDeviceId = myId,
    localName = myName,
    scope = scope,
    sendFrame = { frame, peerId -> myTransport.send(peerId, CallFrameCodec.encode(frame)) },
    // isTrustedPeer, media factory, performance mode ... have defaults or are supplied by the host
)
```

Both Flash apps build their own `CallCoordinator` and route `FLASH_CALL` frames to it themselves (`DiscoveryEngineHolder`,
`DesktopEngine`) rather than going through `engine.attachCalling`; `attachCalling(engine)` is the seam for a third-party host.

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallDirection

fun handleCalls(calling: FlashCalling) {
    scope.launch {
        calling.activeCall.collect { call ->
            when {
                call == null -> println("No call")
                call.state == FlashCallState.RINGING && call.direction == FlashCallDirection.INCOMING -> {
                    println("Incoming ${if (call.video) "video" else "voice"} call from ${call.peerName}")
                    // to answer: calling.accept(audioOnly = !call.video)   (or calling.decline())
                }
                call.state == FlashCallState.ACTIVE -> println("Call connected")
                call.state == FlashCallState.ENDED -> println("Call ended: ${call.endReason}")
            }
        }
    }
}

// outgoing: calling.startCall(peerId = "...", peerName = "Alice", video = true)
```
