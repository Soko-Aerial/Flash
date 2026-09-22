# Core Security Module (`:core:security`)

The `:core:security` module provides the cryptographic engine, device identity generation, mutual pairing coordination, and end-to-end wire encryption for Flash.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.transfer.flash:core-security:2.0.0-beta")
}
```

---

## 2. Cryptographic Architecture

Flash implements a zero-trust, authenticated security layer:

1. **Hardware-Backed Device Identity:**
   * **Android:** Keys generated inside AndroidKeyStore using ECDSA P-256 with `DIGEST_NONE` and `DIGEST_SHA256..512` authorized ([`KeystoreFlashCrypto.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/androidMain/kotlin/com/transfer/flash/core/security/KeystoreFlashCrypto.kt)). Private keys never leave the secure hardware enclave.
   * **Desktop / JVM:** Software-backed EC P-256 keypair generated via standard JCA providers and stored in a secure local keystore file (`~/.flash/identity.p12`).
2. **Device Pairing & Key Exchange:**
   * Uses Elliptic Curve Diffie-Hellman (ECDH) over SECP256r1 to negotiate a 256-bit pairwise shared secret.
   * Derives a symmetric AES-256-GCM key using HKDF-SHA256.
   * Out-of-band verification via 6-digit SAS verification codes or 64-hex cryptographic fingerprint comparisons ([`FlashFingerprint`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/commonMain/kotlin/com/transfer/flash/core/security/FlashFingerprint.kt)).
3. **End-to-End Wire Framing (`E2eFrameCodec`):**
   * Encrypts outgoing text frames into opaque `FLASH_SEC payload=<base64>` envelopes.
   * Uses AES-256-GCM authenticated encryption with a fresh 12-byte cryptographically secure random nonce per frame.
   * Fails closed if message tampering or decryption authentication tag failure occurs.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashCrypto`
Abstracts asymmetric signing, verification, and ephemeral key agreement:

```kotlin
public interface FlashCrypto {
    public fun getLocalPublicKey(): ByteArray
    public fun signData(data: ByteArray): ByteArray
    public fun verifySignature(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    public fun computeSharedSecret(peerPublicKey: ByteArray): ByteArray
}
```

### 3.2 `FlashTrustStore`
Manages paired device fingerprints and session keys:

```kotlin
public interface FlashTrustStore {
    public fun isPeerTrusted(peerId: String): Boolean
    public fun getPeerPublicKey(peerId: String): ByteArray?
    public fun getSessionKey(peerId: String): ByteArray?
    public fun storePairing(peerId: String, publicKey: ByteArray, sessionKey: ByteArray)
    public fun revokePairing(peerId: String)
}
```

### 3.3 `E2eFrameCodec`
Located in [`com.transfer.flash.core.security.E2eFrameCodec`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/commonMain/kotlin/com/transfer/flash/core/security/E2eFrameCodec.kt):

```kotlin
public object E2eFrameCodec {
    public fun encryptFrame(sessionKey: ByteArray, plainTextFrame: String): String
    public fun decryptFrame(sessionKey: ByteArray, encryptedWireFrame: String): String
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.security.E2eFrameCodec
import com.transfer.flash.core.security.FlashFingerprint

// Displaying human-verifiable security fingerprint
val peerPublicKeyBytes: ByteArray = ...
val fingerprint = FlashFingerprint.fromPublicKey(peerPublicKeyBytes)
println("Peer Cryptographic Fingerprint:")
println(fingerprint.formattedHexGroups) // Format: "ABCD 1234 EF56 7890 ..."

// Encrypting a protocol frame
val sessionKey: ByteArray = ... // 32-byte AES key derived from ECDH
val wireFrame = E2eFrameCodec.encryptFrame(
    sessionKey = sessionKey,
    plainTextFrame = "FLASH_MSG id=msg_100 body=Confidential"
)
println("Encrypted wire output: $wireFrame") // Format: "FLASH_SEC payload=..."
```
