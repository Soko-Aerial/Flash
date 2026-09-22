# Scenario 2: Custom Implementations & Architecture Extensions

Flash's clean interface-based architecture allows developers to swap, extend, or augment any major layer. This guide covers how to build custom transports, plug in alternative persistence engines, integrate hardware security modules, and customize the user interface.

---

## 1. Building a Custom Transport (e.g., Bluetooth / BLE / LoRa)

Flash's transfer and messaging engines do not depend on WebSockets or TCP directly. They communicate over the abstract `StreamChannelFactory` and `FlashNetwork` interfaces.

### Step 1: Implement a Custom Stream Channel

To stream chunked file frames over an alternative transport (e.g., Bluetooth RFCOMM or serial USB OTG):

```kotlin
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.multistream.StreamChannelFactory
import java.io.InputStream
import java.io.OutputStream

class BluetoothStreamChannel(
    override val id: Int,
    private val inStream: InputStream,
    private val outStream: OutputStream
) : StreamChannel {

    override suspend fun sendFrame(frame: ByteArray): Boolean {
        return try {
            outStream.write(frame)
            outStream.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun close() {
        inStream.close()
        outStream.close()
    }
}
```

### Step 2: Implement the Factory

```kotlin
class BluetoothStreamChannelFactory(
    private val bluetoothSocketProvider: (peerId: String) -> BluetoothStreamChannel
) : StreamChannelFactory {

    override suspend fun open(channelIndex: Int, peerDeviceId: String?): StreamChannel? {
        if (peerDeviceId == null) return null
        return bluetoothSocketProvider(peerDeviceId)
    }
}
```

Now, pass this factory into [`MultiStreamDispatcher`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt). All file chunking, bit-vector verification, and pause/resume logic will operate over your Bluetooth link without any code changes to the transfer engine.

---

## 2. Implementing Custom Storage & Sink Handles

By default, Flash writes incoming files directly to disk using [`RandomAccessSinkHandle`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/sink/RandomAccessSinkHandle.kt). You can write a custom sink handle to stream chunks directly to an in-memory buffer, an S3/cloud bucket, or an encrypted virtual disk:

```kotlin
import com.transfer.flash.core.transfer.sink.RandomAccessSinkHandle
import java.util.concurrent.ConcurrentHashMap

class InMemorySinkHandle(private val targetMap: ConcurrentHashMap<Long, ByteArray>) : RandomAccessSinkHandle {
    private var isOpen = true

    override fun writeAt(offset: Long, source: ByteArray, sourceOffset: Int, byteCount: Int) {
        if (!isOpen) return
        val chunk = source.copyOfRange(sourceOffset, sourceOffset + byteCount)
        targetMap[offset] = chunk
    }

    override fun flush() {}

    override fun close() {
        isOpen = false
    }
}
```

---

## 3. Integrating Hardware Security Modules (HSM)

If your enterprise or embedded hardware requires hardware-backed private keys (e.g. YubiKey, TPM 2.0, or PKCS#11 smart cards), implement [`FlashCrypto`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security):

```kotlin
import com.transfer.flash.core.security.FlashCrypto

class HsmFlashCrypto(private val hsmSession: HsmSession) : FlashCrypto {
    override fun getLocalPublicKey(): ByteArray {
        return hsmSession.exportEcPublicKey()
    }

    override fun signData(data: ByteArray): ByteArray {
        return hsmSession.signDigest("SHA256withECDSA", data)
    }

    override fun verifySignature(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return HsmUtils.verifyEcSignature(publicKey, data, signature)
    }

    override fun computeSharedSecret(peerPublicKey: ByteArray): ByteArray {
        return hsmSession.deriveEcdhSecret(peerPublicKey)
    }
}
```

---

## 4. Customizing UI & Whitelabeling

To re-skin the application for custom brand guidelines, pass customized [`FlashColors`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashColors.kt) to [`FlashTheme`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTheme.kt):

```kotlin
import androidx.compose.ui.graphics.Color
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.FlashColors

val CustomEnterpriseColors = FlashColors(
    brandPrimary = Color(0xFF0284C7), // Sky Blue instead of Teal
    brandSecondary = Color(0xFF38BDF8),
    backgroundCanvas = Color(0xFFF8FAFC),
    backgroundSurface = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF64748B),
    borderSubtle = Color(0xFFE2E8F0),
    bubbleIncoming = Color(0xFFF1F5F9),
    bubbleOutgoing = Color(0xFF0284C7),
    textOnBubbleOutgoing = Color(0xFFFFFFFF)
)

@Composable
fun EnterpriseApp() {
    CompositionLocalProvider(LocalFlashColors provides CustomEnterpriseColors) {
        FlashTheme {
            // Your custom branded screens
        }
    }
}
```
