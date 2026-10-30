# Domain D01: Identity Keys and Key Storage — White-Box Security Audit

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D01 — Identity Keys and Key Storage  
**Auditor:** Flash Security Audit Team (Domain D01 Specialist)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash employs an asymmetric cryptographic identity model based on NIST P-256 (`secp256r1`) elliptic curve key pairs across Android, Desktop (Windows, Linux), and Hub Server environments. 

On **Android**, hardware-backed isolation is strongly prioritized: primary identity keys are generated in `AndroidKeyStore` with StrongBox support (API 28+) and silent fallback to the hardware TEE. Keystore keys are non-exportable hardware handles. Secondary software secrets (pairwise session keys, hub sealing keys, SQLCipher passphrases) are encrypted at rest using AES-256-GCM via AndroidKeyStore master keys.

On **Desktop**, where hardware keystores are typically unavailable on standard JVM platforms, Flash uses platform-specific at-rest vaults: Windows DPAPI on Windows and the freedesktop.org Secret Service API over D-Bus on Linux, with an owner-only filesystem fallback (`vault.key`).

On **Server**, the Hub architecture separates the offline root authority from the online server daemon: the root key is passphrase-protected with PBKDF2 (>=600,000 iterations) + AES-256-GCM, while the online server maintains an issued delegate key.

This audit identified **6 findings** across key storage, vault fallbacks, cross-purpose key reuse, and backup configurations:
- **1 High:** Linux fallback stores the master AES key directly alongside encrypted identity blobs in the user's data directory.
- **3 Medium:** Windows DPAPI scope lacks secondary entropy; Linux Secret Service transport sends the master key in cleartext over D-Bus; Server delegate private key is stored unencrypted in Base64 on disk.
- **2 Low:** Identity key reuse across transport TLS and multi-subsystem signing with `DIGEST_NONE` authorized; Android backup rules omit app-private data directories in `filesDir`.

---

## 2. Checklist Item Evaluation

| # | Checklist Item | Evaluation | Summary / Finding ID |
|---|---|---|---|
| 1 | Device identity key generation: algorithm and size, RNG source | **Not vulnerable** | EC NIST P-256 (`secp256r1`). Android uses `AndroidKeyStore` hardware RNG; Desktop JVM uses standard JCA `SecureRandom` (`Windows-PRNG`, `NativePRNG`). |
| 2 | Storage on Android: Keystore, StrongBox, `setUserAuthenticationRequired`, extractability | **Not vulnerable** | Stored under alias `flash_identity` in `AndroidKeyStore`. StrongBox probed on API 28+ with fallback to TEE. Private key is non-exportable (`privateKey.encoded == null`). `setUserAuthenticationRequired` is deliberately disabled to permit background transfers and calls; biometric re-enrollment invalidation is disabled. |
| 3 | Storage on Windows: DPAPI scope (user vs machine), entropy | **Vulnerable** (SEC-D01-02) | DPAPI uses `CRYPTPROTECT_CURRENT_USER` (user scope). However, `pOptionalEntropy` is null, allowing any process in the user session to decrypt keys. |
| 4 | Storage on Linux: keyring / Secret Service fallback to plaintext | **Vulnerable** (SEC-D01-01, SEC-D01-03) | Secret Service session uses `"plain"` transport over D-Bus. When Secret Service is absent, fallback writes `vault.key` alongside `id-key.bin` in `0600` mode, offering zero protection in directory backups or volume copies. |
| 5 | Key export paths: are there any export APIs, backups, intents, or serialization paths | **Not vulnerable** | No UI export APIs, intents, or IPC paths expose private keys. `exportPrivateKeyPkcs8` is internal to `:core:security` and only used by storage codecs. |
| 6 | Key reuse across purposes: TLS identity vs signing vs pairing vs group | **Vulnerable** (SEC-D01-05) | The same EC P-256 key is used for the self-signed TLS 1.3 leaf certificate and for signing group chats, group rosters, hub client proofs, and radio packets. Android Keystore requires `DIGEST_NONE` to support TLS Conscrypt handshakes. |
| 7 | Key rotation and loss handling: what happens when key is lost or rotated | **Not vulnerable** | No automatic rotation protocol exists. Key loss results in fail-closed behaviour: TOFU pins fail, peer connections are refused, and database access is locked until explicit re-pairing or re-enrollment. |
| 8 | Backup and restore: what does Android Auto Backup / Google Drive backup or desktop backup do to keys | **Vulnerable** (SEC-D01-06) | Keystore keys, `sharedpref`, and `database` are properly excluded on Android. However, unencrypted downloaded/cached files in `filesDir` (e.g., `hub-files`, `swarm/partial`) are not excluded from cloud backup. Desktop backups include plaintext `vault.key` when Linux fallback is active. |
| 9 | Private key exposure: can any private key reach a log, probe, crash report, intent extra, clipboard, or screenshot | **Not vulnerable** | Thoroughly inspected. `FlashProbe` strictly sanitizes IDs; `RotatingFileLogSink` applies multi-stage regex redaction; crash handlers redact stack traces; clipboards copy only public fingerprints; no private keys are rendered on screen. |

---

## 3. Detailed Audit Findings

### SEC-D01-01 — Linux Key Vault Fallback Stores Master Key Alongside Encrypted Identity and Database Keys
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** **A7** (Thief / forensic examiner / holder of backup), **A6** (Other local user with read access or container volume share)
- **Component:** `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/KeyFileVault.kt:45-96`, `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/IdentityKeyVault.kt:50-84`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopVaults.kt:27-31`
- **CWE / standard:** CWE-312: Cleartext Storage of Sensitive Information; CWE-320: Key Management Errors; MASVS-STORAGE-1; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:N (7.1 High)
- **What is wrong:** When Flash runs on Linux without an active D-Bus Secret Service (e.g. headless servers, container environments, minimal desktop window managers, SSH sessions, or when a user dismisses the keyring unlock prompt), `DesktopVaults.forCurrentOs` falls back to `FileMasterKey`. This creates a random 32-byte AES key and saves it to `<stateDir>/identity/vault.key` with POSIX permissions `0600`. The device identity key is encrypted with AES-256-GCM using this key and stored in `<stateDir>/identity/id-key.bin`. In addition, the chat database passphrase (`ChatDbKeyStore`) is protected by the same vault and stored in `<stateDir>/chat/db-key.bin`. Because both the encryption key and the encrypted payload reside inside the same state directory (`~/.local/share/flash/`), any backup, archive, or volume copy of the application directory contains both the lock and the key.
- **Impact:** Any entity that gains read access to the state directory (e.g. via system backup archives, cloud drive sync, Docker volume inspection, forensic image, or shared filesystem) can immediately read `vault.key`, decrypt `id-key.bin`, extract the raw PKCS#8 private identity key, and decrypt `db-key.bin` to access all local chat history without needing to crack any password or access an OS keystore.
- **Evidence:** 
  In [KeyFileVault.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/KeyFileVault.kt#L59-L68):
  ```kotlin
  val key = ByteArray(SealedVault.KEY_BYTES).also { random.nextBytes(it) }
  keyFile.parentFile?.mkdirs()
  val path = keyFile.toPath()
  createOwnerOnly(tmp)
  Files.write(tmp, key)
  Files.move(tmp, path)
  ```
  In [DesktopVaults.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopVaults.kt#L27-L31):
  ```kotlin
  val keyFile = File(File(stateDir, "identity"), "vault.key")
  return when {
      osName.startsWith("Windows", ignoreCase = true) -> IdentityKeyVault.Dpapi
      osName.startsWith("Linux", ignoreCase = true) -> IdentityKeyVault.withKeyring(LazyKeyring(keyring), keyFile)
      else -> IdentityKeyVault.forKeyFile(keyFile)
  }
  ```
  In [PersistedFlashCrypto.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PersistedFlashCrypto.kt#L155-L165):
  The identity blob is written to `keyFile(stateDir)`: `<stateDir>/identity/id-key.bin`.
- **Why existing guards do not stop it:** POSIX `0600` permissions only prevent unauthorized read operations by distinct local UID users on an active Linux OS kernel. They offer no protection once the directory is archived, copied, backed up, or accessed offline.
- **Verifier result:** survived
- **Recommended fix:** Do not silently fall back to an unencrypted file master key in the same directory. When no OS keyring is available, require the user to configure a master passphrase (derived via Argon2 or PBKDF2 as done in `RootKeyFile.kt`) to encrypt the master key, or store `vault.key` outside the state directory with distinct access controls.
- **Related:** ADR-035, ADR-092, Linux Port Plan L1.

---

### SEC-D01-02 — Windows DPAPI Protection Lacks Secondary Entropy and User Credential Binding
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Other application or malicious process running under the same Windows user session)
- **Component:** `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/IdentityKeyVault.kt:44-47`
- **CWE / standard:** CWE-522: Insufficiently Protected Credentials; CWE-311: Missing Encryption of Sensitive Data; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N (5.5 Medium)
- **What is wrong:** On Windows desktop installations, `IdentityKeyVault.Dpapi` invokes JNA's `Crypt32Util.cryptProtectData(plain)` to protect the identity private key (`id-key.bin`), the chat database key (`db-key.bin`), and pairwise session keys. JNA's default implementation calls the Win32 `CryptProtectData` function with default flags (0, meaning `CRYPTPROTECT_CURRENT_USER`) and `pOptionalEntropy = null`. Because no secondary application-specific entropy or user credential binding is supplied, any code running under the same Windows user account (including untrusted scripts, macros, or malware) can invoke `CryptUnprotectData` on these files and obtain the plaintext keys.
- **Impact:** Any background process, malware, or unauthorized script running with standard user privileges in the same Windows desktop session can decrypt Flash's private identity key and the SQLCipher database passphrase, enabling full cloning of the user's cryptographic identity and unauthorized decryption of message history.
- **Evidence:**
  In [IdentityKeyVault.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/IdentityKeyVault.kt#L44-L47):
  ```kotlin
  public val Dpapi: IdentityKeyVault = IdentityKeyVault(
      protectFn = { plain -> Crypt32Util.cryptProtectData(plain) },
      unprotectFn = { blob -> Crypt32Util.cryptUnprotectData(blob) },
  )
  ```
  `Crypt32Util.cryptProtectData` calls `CryptProtectData(pDataIn, null, null, null, null, 0, pDataOut)`.
- **Why existing guards do not stop it:** Windows DPAPI `CRYPTPROTECT_CURRENT_USER` scope does not isolate processes within the same user session. Without `pOptionalEntropy`, any process possessing the file bytes can decrypt them via standard Windows API calls.
- **Verifier result:** survived
- **Recommended fix:** Provide an application-specific entropy byte array (`pOptionalEntropy`) to `CryptProtectData` derived from an installation secret or prompt the user for an optional master PIN/password to bind DPAPI decryption.
- **Related:** ADR-035.

---

### SEC-D01-03 — Linux Secret Service Keyring Transport Transmits Master Key in Plaintext over D-Bus
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Other application or local tool monitoring D-Bus session bus)
- **Component:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/DbusSecretKeyStore.kt:37-39, 85-87`
- **CWE / standard:** CWE-319: Cleartext Transmission of Sensitive Information; CWE-200: Exposure of Sensitive Information; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N (5.5 Medium)
- **What is wrong:** When storing and retrieving the 32-byte master encryption key from the Linux Secret Service daemon (GNOME Keyring / KWallet), `DbusSecretKeyStore.openSession` negotiates the `"plain"` session algorithm. Although the D-Bus session bus communicates over Unix domain sockets, all traffic on the session bus is visible to any process running within the desktop session with access to the bus address (e.g. tools executing `dbus-monitor --session` or unconfined desktop applications).
- **Impact:** A local process or script running in the user's graphical session can listen to D-Bus messages and capture the 32-byte master encryption key in cleartext during application startup or key creation, granting full decryption capability for `id-key.bin` and `db-key.bin`.
- **Evidence:**
  In [DbusSecretKeyStore.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/DbusSecretKeyStore.kt#L85-L87):
  ```kotlin
  private fun openSession(transport: SecretServiceTransport): DBusPath =
      transport.service.openSession("plain", Variant("")).session
  ```
  In [DbusSecretKeyStore.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/DbusSecretKeyStore.kt#L37-L39):
  ```text
  - Transport of the secret: the session algorithm is `plain`, so the 32 key bytes cross the per-user session bus
    unencrypted. The bus is private to the login session; the encrypted `dh-ietf1024-sha256-aes128-cbc-pkcs7` mode was
    left out to keep this layer small. Recorded in ADR-092.
  ```
- **Why existing guards do not stop it:** Unix domain socket file permissions isolate across UIDs, but do not provide process-level isolation among applications running under the same user UID on the D-Bus session bus.
- **Verifier result:** survived
- **Recommended fix:** Implement the Secret Service specification's encrypted session mode (`dh-ietf1024-sha256-aes128-cbc-pkcs7`) to encrypt the master key in transit across the D-Bus socket between the application and the keyring daemon.
- **Related:** ADR-092, Linux Port Plan L1.

---

### SEC-D01-04 — Hub Server Delegate Private Key Stored Unencrypted in Base64 on Disk
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A6** (Local process or low-privilege service with read access on hub server), **A7** (Forensic examiner / holder of server data backup)
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/auth/KeyFiles.kt:183-189`, `server/src/main/kotlin/com/transfer/flash/server/auth/HubKeyStore.kt:24-25, 40-47`
- **CWE / standard:** CWE-312: Cleartext Storage of Sensitive Information; ASVS V9.1; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:N (7.1 High / Medium contextual)
- **What is wrong:** The Hub Server maintains an offline root key (`root-identity.pk8`) and an online delegate key (`server-identity.pk8`). While the offline root key is encrypted with PBKDF2-HMAC-SHA256 (>=600,000 iterations) and AES-256-GCM (`RootKeyFile.encrypt`), the online server delegate private key is stored as unencrypted Base64-encoded PKCS#8 bytes in `<dataDir>/server-identity.pk8`. The file relies solely on operating system file permissions (`SecureFiles.writeOwnerOnly`: POSIX mode `0600` or Windows NTFS ACL).
- **Impact:** Anyone who obtains read access to the server's data directory (via backup dumps, unencrypted filesystem snapshots, container exports, or backup operator access) immediately obtains the hub's private delegate key. With this key, an attacker can issue valid device certificates within the delegation's scopes (`enrol:member`, `enrol:dept_admin`, `revoke:member`), decrypt relay payloads, and impersonate the enterprise hub.
- **Evidence:**
  In [KeyFiles.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/server/src/main/kotlin/com/transfer/flash/server/auth/KeyFiles.kt#L187):
  ```kotlin
  SecureFiles.writeOwnerOnly(privFile, Base64.getEncoder().encode(pair.private.encoded))
  ```
  In [HubKeyStore.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/server/src/main/kotlin/com/transfer/flash/server/auth/HubKeyStore.kt#L24-L25):
  ```kotlin
  val delegatePrivFile: Path = dataDir.resolve("server-identity.pk8")
  val delegatePubFile: Path = dataDir.resolve("server-identity.pub")
  ```
- **Why existing guards do not stop it:** Filesystem ACLs and POSIX permissions protect only against unprivileged users on a live running host; they do not protect against backup theft, offline storage analysis, or volume imaging.
- **Verifier result:** survived
- **Recommended fix:** Provide an option to encrypt `server-identity.pk8` using a startup passphrase environment variable (`FLASH_SERVER_KEY_PASSPHRASE`) or integrate with a hardware security module / KMS (PKCS#11 / TPM / cloud KMS).
- **Related:** SEC-04, ADR-111, `docs/security.md`.

---

### SEC-D01-05 — Identity Key Reused for TLS Leaf Certificate and Application Signing with `DIGEST_NONE` Authorized in Keystore
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Unauthenticated network peer), **A2** (Paired peer), **A4** (Compromised hub)
- **Component:** `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/KeystoreFlashCrypto.kt:140-156, 186-194`
- **CWE / standard:** CWE-327: Use of a Broken or Risky Cryptographic Algorithm; NIST SP 800-57 Part 1 §5.2; MASVS-CRYPTO-1
- **What is wrong:** Flash uses the exact same EC P-256 key pair as both the self-signed TLS 1.3 transport certificate and the master application signing key for group chats, group rosters, radio packets, hub client verification, and sealing key bindings. Because Conscrypt TLS requires pre-hashed raw signature operations during the TLS handshake, `KeystoreFlashCrypto` configures the AndroidKeyStore entry with `KeyProperties.DIGEST_NONE`. Furthermore, the self-signed certificate is generated with a 25-year validity period (`CERT_VALIDITY_MS = 25 years`).
- **Impact:** Reusing a key across TLS transport authentication and application-layer digital signatures violates NIST SP 800-57 cryptographic key separation principles. Granting `DIGEST_NONE` allows any compromised component within the app to sign raw arbitrary 32-byte values with the hardware key. A 25-year static TLS certificate exposes the device's public key indefinitely to any network observer scanning port 45822.
- **Evidence:**
  In [KeystoreFlashCrypto.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/KeystoreFlashCrypto.kt#L141-L156):
  ```kotlin
  val builder = KeyGenParameterSpec.Builder(
      FlashCrypto.IDENTITY_KEY_ALIAS,
      KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
  )
      .setAlgorithmParameterSpec(ECGenParameterSpec(FlashCrypto.EC_CURVE))
      .setDigests(
          KeyProperties.DIGEST_NONE,
          KeyProperties.DIGEST_SHA256,
          KeyProperties.DIGEST_SHA384,
          KeyProperties.DIGEST_SHA512,
      )
  ```
  Self-signed cert validity:
  ```kotlin
  private const val CERT_VALIDITY_MS = 25L * 365 * 24 * 60 * 60 * 1000
  ```
- **Why existing guards do not stop it:** Flash uses structured canonical domain tags for application payloads (`flash-hub-auth-v1`, `flash-gmsg-v1`, `flash-radio-v1`), preventing cross-protocol message reinterpretation between internal subsystems. However, transport TLS and identity signatures remain physically bound to the exact same key.
- **Verifier result:** survived
- **Recommended fix:** Decouple transport TLS certificates from identity signing keys. Generate short-lived ephemeral TLS certificates issued or endorsed by the identity key, and restrict the hardware Keystore identity key to `KeyProperties.DIGEST_SHA256` only (removing `DIGEST_NONE`).
- **Related:** ADR-035, ERROR-402, Phase B0.

---

### SEC-D01-06 — Android Cloud Backup Rules Exclude Logs but Permit Unencrypted Cached Hub Files and Swarm Chunks in `filesDir`
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A7** (Holder of Google Drive / cloud backup or recipient device in transfer)
- **Component:** `app/src/main/res/xml/data_extraction_rules.xml:24, 31`, `app/src/main/res/xml/backup_rules.xml:11`
- **CWE / standard:** CWE-538: Insertion of Sensitive Information into Externally-Accessible File or Directory; MASVS-STORAGE-2
- **What is wrong:** In `data_extraction_rules.xml` and `backup_rules.xml`, `sharedpref` and `database` are explicitly excluded (`path="."`). However, under the `file` domain (which maps to `context.filesDir`), only `path="logs"` is excluded. Any files written into subdirectories of `context.filesDir`—including decrypted cached hub files in `filesDir/hub-files`, partial swarm downloads in `filesDir/swarm/partial`, and received transfers in `filesDir/ws-received`—are NOT excluded from Google Auto Backup or device-to-device migration.
- **Impact:** Decrypted organizational files, media attachments, or partial transfer chunks stored in `filesDir` may be backed up to Google Drive cloud storage or copied across devices during phone setup, bypassing local SQLCipher encryption protections.
- **Evidence:**
  In [data_extraction_rules.xml](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/res/xml/data_extraction_rules.xml#L17-L33):
  ```xml
  <cloud-backup>
      <exclude domain="sharedpref" path="." />
      <exclude domain="database" path="." />
      <exclude domain="device_sharedpref" path="." />
      <exclude domain="device_database" path="." />
      <exclude domain="external" path="." />
      <exclude domain="file" path="logs" />
  </cloud-backup>
  ```
  `HubFileStorage.kt:45` places cached files under `filesDir/hub-files`.
- **Why existing guards do not stop it:** Android data extraction rules follow an opt-out model for subpaths; omitting `hub-files`, `swarm`, and `ws-received` causes them to be backed up by default up to the 25 MB quota.
- **Verifier result:** survived
- **Recommended fix:** Update `data_extraction_rules.xml` and `backup_rules.xml` to exclude the entire `file` domain (`<exclude domain="file" path="." />`) or explicitly exclude `hub-files`, `swarm`, and `ws-received`.
- **Related:** Audit 2026-09-23 S4.

---

## 4. Candidate Findings Refuted During Audit

| Candidate | Hypothesis | Refutation Evidence |
|---|---|---|
| Android Keystore Private Key Extraction | An attacker with app-level code execution can export the identity private key via reflection or serialization. | **Refuted.** `KeystoreFlashCrypto.kt` allocates keys with `KeyProperties.PURPOSE_SIGN or PURPOSE_VERIFY` in `AndroidKeyStore`. The returned `PrivateKey` is an opaque hardware handle (`AndroidKeyStorePrivateKey`). Calling `privateKey.encoded` returns `null`. Key material never leaves the TEE/StrongBox. |
| Diagnostic Log / Probe Key Exfiltration | Evidence probes (`FlashProbe`) or diagnostic logs (`FlashLog`) could leak private keys or pairing secrets in log exports. | **Refuted.** `FlashProbe.kt` strictly truncates IDs to 8 characters and only accepts booleans, enums, and counts. `RotatingFileLogSink.kt` enforces multi-pass regex redaction (`SECRET_ASSIGN`, `INVITE_LINK`, `BASE64_BLOB`). `FlashEcKeyPair` maintains `privateKey` as an opaque handle and does not implement a leaking `toString()`. |
| Pairing v2 Numeric Code Manipulation | A network attacker (A1) can manipulate ephemeral keys to force a predictable 6-digit comparison code. | **Refuted.** `PairingV2.kt` uses a commitment scheme where the initiator commits to a 16-byte random nonce before receiving the responder's nonce. The 6-digit code is derived using SHA-256 over domain-separated, length-prefixed inputs binding both ephemeral keys, nonces, and pinned fingerprints. Code collision probability is bounded at 10^-6. |

---

## 5. Files Inspected

- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashCrypto.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashEcKeyPair.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashFingerprint.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/Hkdf.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingV2.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubSealing.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubSealingKeyStore.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/KeystoreFlashCrypto.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/IdentityKeyCheck.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.android.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/enterprise/AndroidHubSealingKeyStore.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/identity/AndroidPreferencesIdentityStore.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/trust/AndroidPreferencesTrustStore.kt`
- `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/trust/SecretSealer.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PersistedFlashCrypto.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.jvm.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/enterprise/FileHubSealingKeyStore.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/IdentityKeyVault.kt`
- `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/identity/KeyFileVault.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/KeystorePassphraseProvider.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/store/PassphraseKeeper.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopVaults.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopPaths.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopIdentityStores.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/ChatDbKeyStore.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/DbusSecretKeyStore.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/linux/SecretServiceApi.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/main/res/xml/backup_rules.xml`
- `server/src/main/kotlin/com/transfer/flash/server/auth/KeyFiles.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/HubKeyStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/SecretBox.kt`
- `server/src/main/kotlin/com/transfer/flash/server/KeyTool.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/logging/FlashProbe.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/logging/FlashLog.kt`
- `core/common/src/androidMain/kotlin/com/transfer/flash/core/common/logging/RotatingFileLogSink.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/certs/HubAuth.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupCanonical.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSigning.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/radio/RadioChatWiring.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioWire.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioSession.kt`
- `docs/testing/PROBES.md`
- `docs/audit/security-2026-10-22/00-system-map.md`
