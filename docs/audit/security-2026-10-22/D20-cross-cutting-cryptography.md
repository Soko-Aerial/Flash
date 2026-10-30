# Domain D20 — Cryptography Review (Cross-Cutting)

## 1. Domain Overview
This domain performs a comprehensive, cross-cutting cryptographic audit of primitive selection, key derivation, nonce discipline, authenticated data binding, signature verification, timing side-channels, and post-compromise / forward-secrecy claims across all Flash modules (`:core:security`, `:core:messaging`, `:core:radio`, `:server`, and platform bindings).

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Cryptographic Primitive Inventory

| Primitive / Operation | Algorithm / Parameter | Implementation Call Site | Usage Context | Evaluation |
|---|---|---|---|---|
| Symmetric Encryption | AES-256-GCM (96-bit nonce, 128-bit tag) | `PlatformCrypto.kt`, `E2eFrameCodec.kt`, `SecretBox.kt`, `JdkRadioCrypto.kt` | Wire frame encryption, sealed mailbox rows, TOTP seeds, radio frames | Standard NIST SP 800-38D AEAD. |
| Asymmetric Identity & Signatures | ECDSA over NIST P-256 (`secp256r1`) with SHA-256 | `PlatformCrypto.kt`, `KeystoreFlashCrypto.kt`, `CryptoSupport.kt` | Device identity, certificates, pairing auth, hub approval, audit logs | Standard FIPS 186-4 curve & signature algorithm. |
| Key Agreement | ECDH over NIST P-256 | `PlatformCrypto.kt` (`KeyAgreement.getInstance("ECDH")`) | Ephemeral session key derivation | Standard Diffie-Hellman on P-256. |
| Key Derivation | HKDF-SHA256 (RFC 5869) | `Hkdf.kt` | Deriving 256-bit AES session keys from ECDH shared secret | RFC-compliant extract-and-expand with test vectors. |
| Password Hashing | PBKDF2WithHmacSHA256 (600,000 iterations default) | `AdminAuthService.kt` | Hub console admin passwords | Compliant with OWASP Password Storage Cheat Sheet. |
| Message Digest | SHA-256 | `MessageDigest.getInstance("SHA-256")`, `Sha256.kt` | Identity fingerprints, chunk checksums, audit hash chain | Standard collision-resistant hash. |
| Message Authentication | HMAC-SHA256 / HMAC-SHA1 | `PlatformCrypto.kt`, `AdminAuthService.kt` | HKDF primitive, radio frame MAC, TOTP (RFC 6238) | Standard MAC constructions. |
| Random Number Generation | CSPRNG (`SecureRandom`) | `PlatformCrypto.kt`, `AdminAuthService.kt`, `CryptoSupport.kt` | Ephemeral keys, nonces, session tokens, salts | Cryptographically secure random sources used exclusively for security materials. |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Primitive selection appropriateness | PASS | No broken primitives (no MD5, SHA-1 used strictly for RFC 6238 TOTP compatibility, no ECB/CBC mode). |
| AES-GCM IV/Nonce construction | PARTIAL | 96-bit CSPRNG nonces used. Complies with NIST random nonce limits (< 2^32 invocations). However, no sequence counter in AAD. |
| Key derivation & context separation | DEFECT | Ephemeral ECDH HKDF does not bind peer identity public keys, ephemeral keys, or nonces in salt/info (`SEC-D20-02`). |
| Directional key separation | DEFECT | Symmetrical session key used in both directions without directional tag in AAD, allowing frame reflection (`SEC-D20-01`). |
| AAD coverage | PARTIAL | `E2eFrameCodec` includes protocol version in AAD, but lacks session ID, sender/receiver roles, or sequence numbers. |
| Signature input canonicalisation | PASS | Signatures use structured binary/byte-array serialization with domain-separation headers. |
| Verification fail-closed behavior | PASS | Signature verification wraps in `try { ... } catch (_: Exception) { false }`. No silent pass on error. |
| Constant-time comparisons | PASS | All MAC, token, fingerprint, and pin checks use `MessageDigest.isEqual` or direct byte-level constant-time equivalent. |
| Golden vector test coverage | PASS | RFC 5869 test vectors verify HKDF (`HkdfTest.kt`); P-256 parity tests verify Android/JVM parity (`PlatformCryptoParityTest.kt`). |
| Homemade cryptography | PASS | No custom block ciphers, stream ciphers, or non-standard hash functions in use. |
| Forward secrecy / post-compromise | DOCUMENTED | Ephemeral ECDH provides forward secrecy for frame sessions. Identity keys provide authentication. |

---

## 4. Files Audited
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashCrypto.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/Hkdf.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashFingerprint.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.jvm.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.android.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/KeystoreFlashCrypto.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/SoftwareFlashCrypto.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/CryptoSupport.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/SecretBox.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt`
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt`
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Sha256.kt`

---

## 5. Findings

### SEC-D20-01 — Symmetrical Session Key and Missing AAD Direction/Counter in `E2eFrameCodec` Allows Frame Reflection and In-Session Replay
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A1, A2 (Unauthenticated network attacker or rogue peer capable of reflecting/replaying frames on the transport)
- **Component:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt:52,69,94`
- **CWE / standard:** CWE-323: Replay of Subsequence; CWE-300: Channel Accessible by Non-Endpoint; CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:N/I:H/A:N (5.9)
- **What is wrong:** When two peers establish an encrypted session via ephemeral ECDH, both derive an identical symmetric 32-byte AES-256 session key (`sessionKey`). `E2eFrameCodec.encrypt` and `decrypt` invoke AES-GCM with static `aad = "flash-e2e-v${FlashProtocol.VERSION}"`. Neither the transmission direction (e.g., `initiator -> responder` vs `responder -> initiator`), nor a session identifier, nor a monotonic frame sequence counter is included in the AAD or the authenticated wire frame. Consequently, an on-path attacker or transport proxy can reflect a valid frame sent by Alice back to Alice on the same connection; Alice will authenticate and process the frame as if it originated from Bob. Furthermore, identical valid ciphertext frames can be replayed repeatedly within the session.
- **Impact:** Integrity violation: an attacker can induce a device to re-process its own sent frames as incoming peer messages (reflection) or duplicate previous state-modifying frames (replay).
- **Evidence:**
  `E2eFrameCodec.kt:52`:
  ```kotlin
  private fun aad(): ByteArray = "flash-e2e-v${FlashProtocol.VERSION}".encodeToByteArray()
  ```
  `E2eFrameCodec.kt:66-73`:
  ```kotlin
  val nonce = secureRandomBytes(NONCE_BYTES)
  val ciphertext = aesGcmSeal(
      key = sessionKey,
      nonce = nonce,
      aad = aad(),
      plaintext = payloadJson.encodeToByteArray(),
  )
  return nonce + ciphertext
  ```
- **Why existing guards do not stop it:** The GCM authentication tag authenticates `nonce`, `ciphertext`, and `aad`. Because `sessionKey` and `aad` are identical for both Alice and Bob, a ciphertext generated by Alice satisfies Bob's verification AND Alice's verification.
- **Verifier result:** survived. Verifier confirmed that `aad()` does not include direction flags, session nonces, or sequence counters. If a WebSocket frame is reflected, `E2eFrameCodec.decryptWireFrame` successfully returns the plaintext.
- **Recommended fix:**
  1. Derive two separate keys via HKDF: `client_to_server_key` and `server_to_client_key` (or `initiator_to_responder` and `responder_to_initiator`), or include a direction byte in the AAD (`0x01` = Outbound, `0x02` = Inbound relative to session role).
  2. Include a monotonically incrementing 64-bit sequence counter in the AAD and reject out-of-order/replayed counters at the recipient.
- **Related:** `SEC-D04-03`, `SEC-D05-02`.

---

### SEC-D20-02 — Ephemeral ECDH Key Derivation Lacks Transcript & Identity Binding (UKS Vulnerability)
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A1, A2, A4 (Adversary attempting Unknown Key-Share in multi-party or relayed context)
- **Component:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashCrypto.kt:80-85,110-117`
- **CWE / standard:** CWE-322: Key Exchange without Entity Authentication; CWE-325: Missing Required Cryptographic Step
- **What is wrong:** In `FlashCrypto.kt`, `EcP256Ops.sessionKeyFromSharedSecret` derives the AES session key using `Hkdf.derive(sharedSecret, salt = ByteArray(0), info = "flash-e2e-v1")`. In standard authenticated key exchange protocols (e.g. Noise Protocol Framework, SIGMA, TLS 1.3), key derivation explicitly incorporates the static identity public keys of both parties, the ephemeral public keys, and session nonces into the HKDF `salt` or `info` parameters. In Flash, the HKDF derives key material strictly from the raw ECDH shared secret with empty salt and static info. If ephemeral public keys are forwarded or relayed without transcript authentication binding, an Unknown Key-Share (UKS) condition can occur wherein Alice agrees on a session key with Charlie believing Charlie is Bob.
- **Impact:** Cryptographic design weakness: lacking cryptographic binding of communicating identities in the KDF allows UKS attacks if the outer transport does not guarantee continuous identity authentication.
- **Evidence:**
  `FlashCrypto.kt:80-85`:
  ```kotlin
  public val EMPTY_SALT: ByteArray = ByteArray(0)
  public val SESSION_INFO: ByteArray = "flash-e2e-v${FlashProtocol.VERSION}".encodeToByteArray()
  ```
  `FlashCrypto.kt:111-117`:
  ```kotlin
  fun sessionKeyFromSharedSecret(sharedSecret: ByteArray): ByteArray =
      Hkdf.derive(
          ikm = sharedSecret,
          salt = FlashCrypto.EMPTY_SALT,
          info = FlashCrypto.SESSION_INFO,
          outLength = FlashCrypto.SESSION_KEY_SIZE_BYTES,
      )
  ```
- **Why existing guards do not stop it:** The underlying ECDH calculation `ecP256SharedSecret(priv, pub)` only computes the curve point scalar multiplication. Without binding the identities into HKDF `info`, the resulting AES key is purely a function of the ephemeral key exchange.
- **Verifier result:** survived. Code inspection verifies that `FlashCrypto.EMPTY_SALT` is empty and `SESSION_INFO` is a static constant.
- **Recommended fix:** Update session key derivation to bind the transcript:
  ```kotlin
  fun deriveSessionKey(
      sharedSecret: ByteArray,
      initiatorPublicKey: ByteArray,
      responderPublicKey: ByteArray,
      initiatorEphemeral: ByteArray,
      responderEphemeral: ByteArray,
  ): ByteArray = Hkdf.derive(
      ikm = sharedSecret,
      salt = sha256(initiatorEphemeral + responderEphemeral),
      info = "flash-e2e-v1:".encodeToByteArray() + initiatorPublicKey + responderPublicKey,
      outLength = FlashCrypto.SESSION_KEY_SIZE_BYTES,
  )
  ```
- **Related:** `SEC-D01-04`, `SEC-D03-01`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D20-01: Non-standard / Homemade Ciphers**: Audited all encryption and signing call sites. Flash uses standard NIST P-256 (`secp256r1`), SHA-256, HMAC-SHA256, and AES-256-GCM via platform providers (AndroidKeyStore, Conscrypt, Java Cryptography Architecture). No homemade ciphers are implemented.
- **REJ-D20-02: Timing Oracles on Cryptographic Comparisons**: Audited all MAC, pin, and token comparisons (`FlashFingerprint`, `TofuPolicy`, `Sha256`, `AdminAuthService`). All comparisons use constant-time equality checks (`MessageDigest.isEqual` or bitwise accumulating loops).
- **REJ-D20-03: Insecure Random Number Generation for Cryptographic Keys**: Audited all usages of `Random` vs `SecureRandom`. `Random.Default` is restricted to UI IDs, retry jitter, and tests. All keypairs, nonces, and session tokens strictly use `SecureRandom` / `secureRandomBytes`.
- **REJ-D20-04: Fail-Open Signature Verification**: Verified `PlatformCrypto.jvm.kt` and `PlatformCrypto.android.kt`. Exception handling blocks return `false` on all malformed signatures, ensuring fail-closed security.
