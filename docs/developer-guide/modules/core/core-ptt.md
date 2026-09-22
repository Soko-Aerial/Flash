# Core Push-To-Talk Module (`:core:ptt`)

The `:core:ptt` module provides a specialized, low-latency Push-To-Talk (PTT) voice streaming engine. It is designed for walkie-talkie style voice communication, rugged handsets (e.g. BelFone devices), half-duplex floor control, and instant audio delivery.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-ptt:2.0.0-beta")
}
```

---

## 2. Architecture and Floor Arbitration

Unlike full-duplex WebRTC calling, PTT operates in half-duplex mode:

1. **Floor Request & Arbitration:**
   * Before streaming voice, a client requests the floor (`REQUEST_FLOOR`).
   * The channel host or consensus coordinator awards the floor (`FLOOR_GRANTED`) to one speaker at a time, rejecting conflicting simultaneous presses (`FLOOR_BUSY`).
   * When the user releases the PTT button, the floor is released (`FLOOR_RELEASED`).
2. **Streaming Audio Frames:**
   * Voice is encoded in compact Opus chunks and streamed directly over low-latency binary frames (`FLASH_PTT_CHUNK`).
   * Recipients play audio immediately through [`FlashAudioPlayer`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims/src/commonMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.kt) with minimal jitter buffering.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashPtt`
Located in [`com.transfer.flash.core.ptt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/ptt):

```kotlin
public interface FlashPtt {
    /** Reactive state of the PTT channel and floor owner. */
    public val pttState: StateFlow<FlashPttState>

    /** Requests the floor to start broadcasting voice. */
    public suspend fun pressFloor(channelId: String): FlashResult<Unit>

    /** Releases the floor when the user lets go of the button. */
    public suspend fun releaseFloor(channelId: String)

    /** Joins a PTT channel or multicast group. */
    public suspend fun joinChannel(channelId: String)

    /** Leaves the PTT channel. */
    public suspend fun leaveChannel(channelId: String)
}

public data class FlashPttState(
    val currentChannel: String?,
    val isFloorHeldByMe: Boolean,
    val currentSpeakerName: String?,
    val isConnecting: Boolean
)
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.ptt.FlashPtt
import kotlinx.coroutines.launch

fun setupPttButton(ptt: FlashPtt, channelId: String) {
    // When physical or UI PTT button is pressed down:
    fun onButtonPressed() {
        scope.launch {
            val result = ptt.pressFloor(channelId)
            result.fold(
                onSuccess = { println("Floor granted! Speaking...") },
                onFailure = { println("Channel busy. Someone else is speaking.") }
            )
        }
    }

    // When button is released:
    fun onButtonReleased() {
        scope.launch {
            ptt.releaseFloor(channelId)
            println("Floor released.")
        }
    }
}
```
