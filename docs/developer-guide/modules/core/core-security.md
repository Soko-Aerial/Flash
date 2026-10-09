# Core Security Module (`:core:security`)

The `:core:security` module provides the cryptographic engine, device identity generation, mutual pairing coordination, and end-to-end wire encryption for Flash.

---

## 1. Gradle Dependency Coordinates

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-security:v2.1.0-beta")
}
```

---

## 2. Cryptographic Architecture

Source layout (`core/security/src`): `crypto/` (identity, ECDH, frame codecs), `pairing/`, `trust/`, `identity/`, `group/` (group secrets and invites, GM track).

1. **Device identity.**
   * **Android:** an EC P-256 key generated inside AndroidKeyStore (`KeystoreFlashCrypto`), StrongBox-backed when the device has it. The private key is not exportable.
   * **Desktop / JVM:** a software EC P-256 key (`PersistedFlashCrypto`) whose blob is sealed at rest by an `IdentityKeyVault`: Windows DPAPI on Windows, a Secret Service keyring on Linux (provided by the desktop app, `DesktopVaults`, ADR-092; not device-verified), an owner-only key file otherwise. This is weaker than a hardware key (code running as the same user can unseal it, ADR-035).
2. **Pairing (v2, ADR-042).** A commit-then-reveal numeric comparison: each side commits to a nonce, the responder reveals first, and the 6-digit code is `H(fp_I, fp_R, epk_I, epk_R, N_I, N_R) mod 10^6`. A man-in-the-middle cannot steer the code (a false match has probability about 10^-6), and each side requires the peer's fingerprint to equal the key TLS pinned for that connection. The classes are `PairingV2`, `NumericComparisonCode`, `PairingSessionStateMachine`, `PairingWireCodec`/`FlashPairingFrames` (desktop adds `FlashPairingCoordinator`). The 64-hex fingerprint (`FlashFingerprint`, SHA-256 of the encoded public key) is the manual fallback.
3. **Session key.** After pairing, an ephemeral ECDH (P-256) shared secret goes through HKDF-SHA256 (`Hkdf`, info string `flash-e2e-v<protocol version>`) to a 32-byte AES-256 key.
4. **Wire framing.**
   * `E2eFrameCodec`: AES-256-GCM, a fresh random 12-byte nonce per frame, 128-bit tag; text frames become `FLASH_SEC payload=<base64>`. `decrypt` throws on a tag failure.
   * `SecureBinaryFrameCodec`: the same cipher for binary frames (`decryptOrNull` returns null instead of throwing).
5. **Trust and pinning.** `FlashTrustStore` holds paired devices, session keys and TLS identity pins, plus **vouches** (a group can vouch a member's key so a group of up to 20 needs each member paired only with the owner, ADR-044). `VouchRules` decides pin sources; the TOFU policy refuses to pin the device's own key.
6. **Group secrets (Track GM).** `group/` has `GroupSecret`, `GroupSecretKdf` (auth and beacon keys per group id and epoch), `GroupSecretCommit`, `GroupProof` (mutual challenge proof) and `GroupInvite`/`GroupInviteCodec` (`flash://g/1/...` links). Details: `docs/security.md` section 10.

---

## 3. Key Public Interfaces and Classes

### 3.1 `FlashCrypto`

`com.transfer.flash.core.security.crypto.FlashCrypto`. Signing and ephemeral key agreement:

```kotlin
public interface FlashCrypto {
    public val identityPublicKeyEncoded: ByteArray
    public fun sign(data: ByteArray): ByteArray                                   // SHA256withECDSA
    public fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean
    public fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair
    public fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray // 32 bytes
}
```

### 3.2 `FlashTrustStore`

`com.transfer.flash.core.security.trust.FlashTrustStore` (abridged; most methods also have a `String` overload):

```kotlin
public interface FlashTrustStore {
    public fun isTrusted(deviceId: FlashDeviceId): Boolean
    public fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit>
    public fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit>
    public fun getTrustedPeers(): Map<FlashDeviceId, String>
    public fun saveSessionKey(deviceId: FlashDeviceId, key: ByteArray): FlashResult<Unit>
    public fun getSessionKey(deviceId: FlashDeviceId): ByteArray?
    public fun savePin(deviceId: FlashDeviceId, fingerprintHex: String): FlashResult<Unit>
    public fun getPin(deviceId: FlashDeviceId): String?
    public fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit>
    public fun isVerified(deviceId: FlashDeviceId): Boolean
    public fun vouchingGroups(deviceId: FlashDeviceId): Set<String>
    public fun applyVouch(deviceId: FlashDeviceId, fingerprintHex: String, groupId: String): VouchVerdict
    public fun revokeVouch(deviceId: FlashDeviceId, groupId: String)
    public fun pinSource(deviceId: FlashDeviceId): PinSource?
}
```

### 3.3 `E2eFrameCodec`

`com.transfer.flash.core.security.crypto.E2eFrameCodec`:

```kotlin
public object E2eFrameCodec {
    public const val SEC_PREFIX: String = "FLASH_SEC"
    public fun isSecuredFrame(text: String): Boolean
    public fun encrypt(payloadJson: String, sessionKey: ByteArray): ByteArray
    public fun decrypt(frame: ByteArray, sessionKey: ByteArray): String
    public fun encryptToWireFrame(plainText: String, sessionKey: ByteArray): String // "FLASH_SEC payload=<base64>"
}
```

---

## 4. Practical Code Example

```kotlin
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.security.crypto.FlashFingerprint

// Showing a human-verifiable fingerprint
val peerPublicKeyBytes: ByteArray = ...
val fp = FlashFingerprint.fingerprint(peerPublicKeyBytes)       // SHA-256 of the encoded key
println(FlashFingerprint.formatHexGroups(fp))                    // grouped hex for display

// Encrypting a protocol frame
val sessionKey: ByteArray = ... // 32-byte AES key from FlashCrypto.ecdhSessionKey
val wireFrame = E2eFrameCodec.encryptToWireFrame(
    plainText = "FLASH_MSG id=msg_100 body=Confidential",
    sessionKey = sessionKey,
)
println("Encrypted wire output: $wireFrame") // "FLASH_SEC payload=..."
```
