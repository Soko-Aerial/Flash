# Practical Examples: Standalone Module Usage

Flash modules are designed to be composable and independent. You do not need to adopt the entire stack if you only require specific capabilities. Here are examples of using individual Flash modules in isolation.

---

## 1. Using `:core:security` for Pairwise Key Agreement & Encryption

You can use the security module independently for end-to-end encryption in any Kotlin project:

```kotlin
import com.transfer.flash.core.security.E2eFrameCodec
import com.transfer.flash.core.security.FlashFingerprint

// 1. Peer A generates ephemeral keypair and exports public key
val peerAPublicKeyBytes: ByteArray = ... 
// 2. Peer B computes ECDH shared secret
val peerBPublicKeyBytes: ByteArray = ...
val sharedSecret: ByteArray = ... // 32-byte derived AES key

// 3. Encrypt an arbitrary payload
val plainPayload = "TRANSFER_AUTH_TOKEN: 849204"
val encryptedEnvelope = E2eFrameCodec.encryptFrame(sharedSecret, plainPayload)
println("Encrypted: $encryptedEnvelope")

// 4. Decrypt on peer
val decryptedPayload = E2eFrameCodec.decryptFrame(sharedSecret, encryptedEnvelope)
println("Decrypted: $decryptedPayload")
```

---

## 2. Using `:core:discovery` for Zero-Conf LAN Discovery

If you only need to discover devices on the local network without Flash's transfer or chat engines:

```kotlin
import com.transfer.flash.core.discovery.FlashDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun discoverLocalPeers(discovery: FlashDiscovery, scope: CoroutineScope) {
    scope.launch {
        discovery.startDiscovery()
        
        discovery.discoveredEndpoints.collect { endpoints ->
            println("Currently visible LAN endpoints:")
            endpoints.forEach { ep ->
                println(" - ${ep.displayName} at ${ep.host}:${ep.port}")
            }
        }
    }
}
```

---

## 3. Using `:ui:theme` for Custom Design Tokens

You can import `:ui:theme` to use Flash's design tokens and icons in any Compose Multiplatform application:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.theme.FlashTheme
import com.transfer.flash.ui.theme.FlashIcons

@Composable
fun FlashBrandedBadge() {
    FlashTheme {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(FlashTheme.colors.backgroundSurface, shape = FlashTheme.shapes.squircle)
        ) {
            Icon(
                painter = FlashIcons.Bolt.painter,
                contentDescription = "Flash Bolt",
                tint = FlashTheme.colors.brandPrimary
            )
        }
    }
}
```
