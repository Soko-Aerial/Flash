# Practical Examples: Standalone Module Usage

Flash modules are designed to be composable and independent. You do not need to adopt the entire stack if you only require specific capabilities. Here are examples of using individual Flash modules in isolation.

---

> **Verified against the code 2026-10-09.** The earlier version of this page used APIs that do not exist
> (`E2eFrameCodec.encryptFrame`, `ep.displayName`, `FlashTheme.shapes`, `FlashIcons.Bolt.painter`). The snippets below use the real signatures.
> They are illustrations; only the Android quick start in `sample/consumer` is compiled by CI.

## 1. Using `:core:security` for frame encryption and fingerprints

`E2eFrameCodec` and `FlashFingerprint` are plain public objects that need only a 32-byte AES key and a public key. Getting that key by
ECDH needs a `FlashCrypto`: on Android that is `KeystoreFlashCrypto(context)` (public); on the JVM the only implementation,
`PersistedFlashCrypto`, is `@FlashInternalApi` (opt in with `@OptIn(FlashInternalApi::class)`), because the desktop app owns identity storage.

```kotlin
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.crypto.KeystoreFlashCrypto // Android

// Peer A and peer B each: crypto.generateEphemeralEcdhKeyPair() -> exchange crypto-encoded public keys ->
// val sessionKey = crypto.ecdhSessionKey(selfEphemeral, peerEphemeralPublicKey)   // 32 bytes, HKDF-SHA256
val sessionKey: ByteArray = ...

val wire = E2eFrameCodec.encryptToWireFrame("TRANSFER_AUTH_TOKEN: 849204", sessionKey)   // "FLASH_SEC payload=<base64>"
val back: String? = E2eFrameCodec.decryptWireFrame(wire, sessionKey)                     // null on a bad tag / wrong key
println(back)

// A human-comparable fingerprint of a public key
val fp = FlashFingerprint.fingerprint(peerPublicKeyEncoded)       // SHA-256
println(FlashFingerprint.formatHexGroups(fp))
```

---

## 2. Using `:core:discovery` for zero-conf LAN discovery

Discovery needs a transport. `core-discovery` ships the Android NSD transport (`NsdFlashDiscovery`, needs a `Context`) and the JVM
`JmdnsTransport`; `CompositeDiscovery(transports = listOf(...))` merges them. The simplest way to get a ready `FlashDiscovery` is the
engine (`engine.discovery`); given one, observation looks like this:

```kotlin
import com.transfer.flash.core.discovery.FlashDiscovery

fun discoverLocalPeers(discovery: FlashDiscovery, listenPort: Int, scope: CoroutineScope) {
    scope.launch {
        discovery.startAdvertising(listenPort)   // be visible to others (optional for a browse-only tool)
        discovery.startDiscovery()               // browse

        discovery.discoveredEndpoints.collect { endpoints ->
            println("Currently visible LAN endpoints:")
            endpoints.forEach { ep ->
                println(" - ${ep.friendlyName} at ${ep.hostAddress}:${ep.port}")
            }
        }
    }
}
```

---

## 3. Using `:ui:theme` for design tokens and icons

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.ui.icons.FlashIcon
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.icons.FlashIconState
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashTheme

@Composable
fun FlashBrandedBadge() {
    FlashTheme {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(FlashTheme.colors.backgroundSurface, shape = RoundedCornerShape(FlashShapes.radius12))
        ) {
            FlashIcon(icon = FlashIcons.Send, state = FlashIconState.Active)
        }
    }
}
```

(`FlashIcons` has about 60 drawable-backed icons; see [ui-theme](../modules/ui/ui-theme.md). The icon file lives in the `ui.icons` package, not `ui.theme`.)
