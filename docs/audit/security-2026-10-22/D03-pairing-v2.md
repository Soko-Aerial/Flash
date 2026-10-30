# Flash Security Audit 2026-10-22 — Domain D03: Pairing v2 & Trust Architecture

**Audit Date:** 2026-10-22  
**Audited Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain Lead:** Domain D03 Auditor  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash Pairing v2 (introduced via ADR-042 and audit 2026-09-23 S2) replaces static identity-fingerprint derivation with a commit-then-reveal cryptographic protocol modeled after Bluetooth Secure Simple Pairing (SSP) Numeric Comparison. The wire protocol operates over established TLS 1.3 WebSocket connections and binds ephemeral public keys, 16-byte random nonces, and TLS SubjectPublicKeyInfo (SPKI) fingerprints.

This white-box audit evaluated the cryptographic primitives, the pure finite state machine, client coordinators on Android and Desktop, persistent trust store implementations (`AndroidPreferencesTrustStore` and `DesktopTrustStore`), integration with `RealFlashChatRepository`, and the Jetpack Compose user experience (`FlashPairingDialog`).

**Key Conclusions:**
1. **Cryptographic Core Soundness:** The commit-then-reveal scheme in `PairingV2.kt` is mathematically sound, enforces strict domain separation, lengths-prefixes all inputs, and is bound to TLS-pinned identity public keys. Relay attacks between honest peers are stopped because frames bind TLS leaf fingerprints.
2. **Critical Asymmetry in User Confirmation:** Although Bluetooth Numeric Comparison mandates that users on *both* devices verify and confirm code equality, Flash Pairing v2 only presents an "Accept" button on the *responder* device. On the initiator, receipt of `PAIR_ACCEPT` triggers an automated cryptographic confirmation (`PAIR_CONFIRM`) and completes trust establishment without local user confirmation. An attacker on the LAN can therefore achieve verified pairing against an initiating victim with zero code comparison.
3. **Session Key Hygiene:** The "ephemeral" ECDH keypair is generated once at application startup and reused across all pairing sessions for the entire process lifetime. The HKDF derivation uses empty salt and fails to bind nonces or identity material into its transcript.
4. **Unpairing Semantic Flaws:** Revoking trust deletes the underlying TLS pin if unvouched, reverting the peer to unauthenticated TOFU first-use. Active WebSocket sessions are not disconnected, and if 1:1 chat history exists with an organization colleague, incoming traffic immediately triggers automatic re-pairing without user consent.

---

## 2. Audited Files

Every file listed below was inspected line-by-line during this audit:

| Module | File Path | Scope & Focus |
|---|---|---|
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingV2.kt` | Commit-then-reveal primitives, length-prefix encoding, code derivation, pinned identity matching |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/NumericComparisonCode.kt` | Code length, confirmation hashing, constant-time equality, legacy v1 comparison |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingFrames.kt` | Wire frame definitions, data class equality, protocol versioning |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingSessionStateMachine.kt` | Pure finite state machine reducer, 10 lifecycle phases, timeouts, terminal state absorption |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingProtocol.kt` | Event orchestrator, timeout ticker integration, frame dispatch |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingWireCodec.kt` | Text framing codec (`FLASH_PAIR`), Base64 serialization, type handling |
| `:core:security` | `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingCoordinator.kt` | Desktop pairing driver, hello caching, UI state derivation, trust store integration |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/trust/FlashTrustStore.kt` | Trust store interface, pin storage, vouching contracts |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/trust/VouchRules.kt` | Pure vouching decision logic, pin precedence (PAIRED > VOUCHED > TOFU) |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/trust/pinned/TofuPolicy.kt` | Pure TOFU decision matrix, fail-closed evaluation |
| `:core:security` | `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/trust/AndroidPreferencesTrustStore.kt` | SharedPreferences storage, Keystore secret sealing, vouching persistence |
| `:core:security` | `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashCrypto.kt` | ECDH session key agreement, HKDF derivation, EcP256Ops |
| `:desktop` | `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopIdentityStores.kt` | Properties file storage, DPAPI sealing, cache synchronization |
| `:desktop` | `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt` | Ephemeral key generation, pairing coordinator instantiation, TLS options |
| `:desktop` | `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt` | Desktop UI event binding, unpairing handlers |
| `:app` | `app/src/main/java/com/transfer/flash/pairing/PairingCoordinator.kt` | Android pairing coordinator, single-flight enforcement, trust store wiring |
| `:app` | `app/src/main/java/com/transfer/flash/pairing/PairingUiMapper.kt` | Mapping core state machine phases to UI phases |
| `:app` | `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt` | Android coordinator instantiation, ephemeral key lifetime |
| `:app` | `app/src/main/java/com/transfer/flash/MainActivity.kt` | Global dialog rendering, destination navigation |
| `:core:network` | `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/tls/TofuPinVerifier.kt` | TOFU pin verification and recording |
| `:core:messaging` | `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt` | Unpair/re-pair handling, `maybeAutoPairColleague`, directory pairing |
| `:core:enterprise` | `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt` | Dynamic trust evaluation, TOFU pin replacement |
| `:ui:chat` | `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashPairingFlow.kt` | `FlashPairingDialog` Compose UI, action buttons, countdown bar |
| `:ui:chat` | `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/nearby/FlashNearbyScreen.kt` | Nearby screen pairing integration |

---

## 3. Checklist for D03

Every item from the audit mandate is addressed below with technical justification:

### 1. Commit-then-reveal soundness
- **Status:** Not vulnerable.
- **Analysis:** `PairingV2.kt:44-59` constructs commitments using `sha256(encode(COMMIT_DOMAIN, normalizeHex(fp_I), epk_I, N_I))`. The initiator reveals `N_I` via `PAIR_REVEAL` only after the responder has transmitted its own `N_R` in `PAIR_NONCE`. All fields are length-prefixed with 4-byte big-endian integers (`PairingV2.kt:99-106`), preventing delimiter injection or concatenation ambiguity. Nonces are 16 bytes drawn from the platform CSPRNG (`secureRandomBytes`). The 6-digit comparison code derivation uses SHA-256 over domain-separated inputs binding identities, ephemeral public keys, and nonces in role order (`initiator` then `responder`). The modulus reduction over 5 bytes (40 bits) introduces negligible bias (~5.7 × 10⁻⁷).

### 2. Brute force of the short code
- **Status:** Vulnerable (see **SEC-D03-03**).
- **Analysis:** Timing side-channels in code comparison are properly mitigated: `NumericComparisonCode.hashesEqual` delegates to `PlatformCrypto.constantTimeBytesEqual` (`MessageDigest.isEqual`), operating on normalized 64-character hex strings. However, there are no attempt limits, backoff timers, or lockouts. While a single failed attempt terminates the session with a 2.5-second linger delay, an attacker can repeatedly initiate new pairing attempts indefinitely without restriction.

### 3. Relay / MITM of pairing
- **Status:** Partially vulnerable (see **SEC-D03-01**).
- **Analysis:** Transparent relay between two honest devices is prevented because `PairingSessionStateMachine.kt:206` and `279` strictly enforce `PairingV2.matchesPinnedIdentity(claimedFingerprintHex, pinnedPeerFingerprintHex)`. If a MITM establishes separate TLS connections, the presented fingerprints will not match the pinned transport leaf SPKI fingerprints. However, against an active attacker acting as a rogue endpoint, the protocol fails because the initiator auto-confirms without verifying that the human user compared the codes.

### 4. Replayed pairing messages
- **Status:** Partially vulnerable (see **SEC-D03-05**).
- **Analysis:** `requestId` checks protect against replayed or delayed frames during the initial exchange (`PeerNonce`, `PeerReveal`, `PeerAccepted`). However, `DefaultFlashPairingProtocol.kt:297, 315` strips `requestId` from `PAIR_CONFIRM` and `PAIRED` frames before passing them to the pure state machine, leaving the completion phase unvalidated against `requestId`. Reflected frames are safely dropped because state machine phase guards and role checks enforce initiator/responder symmetry separation.

### 5. Pairing while already paired
- **Status:** Partially vulnerable (see **SEC-D03-03**).
- **Analysis:** Re-pairing a previously paired peer with the same key safely refreshes session keys. Downgrading to protocol v1 is strictly blocked by `event.protocolVersion < 2` checks. However, concurrent pairing sessions (glare condition where both devices initiate simultaneously) result in mutual deadlock: both devices reject the incoming request because they are in `AwaitingPeerNonce` instead of `Idle`, and hang until the 30-second timeout.

### 6. Confirmation UX
- **Status:** Vulnerable (see **SEC-D03-01**, **SEC-D03-06**).
- **Analysis:** The initiator UI never provides an "Accept" or "Confirm" button; it shows "Waiting for [peer] to confirm..." and automatically completes when the peer sends `PAIR_ACCEPT`. The responder UI displays [Accept] (accent color) and [Decline] (gray), but contains zero visible instructional text telling the user to compare numbers with the peer device's screen. Timeout handling safely fails closed (transitions to `Expired` and resets to `Idle`).

### 7. Trust store writes
- **Status:** Not vulnerable (within design constraints; see **SEC-D03-04** for unpair impact).
- **Analysis:** Trust store writes to `savePin` occur via `TofuPinVerifier` (recording TLS fingerprint on first use) and `OrgPeerResolver` (replacing TOFU pins with certified directory fingerprints). `VouchRules.decide` and `OrgPeerResolver` strictly prevent overwriting existing `PAIRED` or `VOUCHED` pins. However, raw `FlashTrustStore.savePin` stores only fingerprints without a persistent source tag.

### 8. Unpair and re-pair semantics
- **Status:** Vulnerable (see **SEC-D03-04**).
- **Analysis:** Calling `revokeTrust` clears the pairing entry, session key, and verified status. However, it deletes the TLS pin (if unvouched), reverting the device to unauthenticated TOFU status. Active WebSocket connections are not terminated upon unpairing. Furthermore, if previous chat history exists with an organization colleague, any incoming 1:1 message triggers `maybeAutoPairColleague()`, immediately re-pairing the contact without user interaction.

---

## 4. Detailed Security Findings

### SEC-D03-01 — Asymmetric Human Verification in Pairing v2 Auto-Confirms on Initiator, Enabling Malicious Peer Impersonation
- **Severity:** High  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN / hotspot device), A2 (Paired peer turned malicious)  
- **Component:** `app/src/main/java/com/transfer/flash/pairing/PairingCoordinator.kt:285-293`, `core/security/src/jvmMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingCoordinator.kt:269-277`, `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingSessionStateMachine.kt:313-333`, `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashPairingFlow.kt:98-100, 218-234`  
- **CWE / standard:** CWE-287 (Improper Authentication); CWE-322 (Key Exchange without Entity Authentication); MASVS-AUTH-1; CVSS:3.1/AV:A/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:N (7.3 High)  
- **What is wrong:** The Pairing v2 state machine and user interface implement human confirmation asymmetrically: only the *responder* is presented with an action button to confirm the 6-digit numeric comparison code. On the *initiator* device, receipt of a `PAIR_ACCEPT` wire frame triggers an automatic transmission of `PAIR_CONFIRM` (carrying the SHA-256 hash of the initiator's derived code) without requiring any local user input or confirmation that the numbers match.  
- **Impact:** An attacker on the local network who advertises a target device's name or who lures the victim into tapping "Pair" can programmatically answer with `PAIR_NONCE`, `PAIR_ACCEPT`, and `PAIRED` frames. The initiator's device auto-accepts, marks the attacker's device as trusted and verified (`isTrusted = true`, `isVerified = true`), derives an ECDH session key, and completes the pairing with zero human verification of the 6-digit code.  
- **Evidence:**  
  1. In `FlashPairingFlow.kt:98-99`:
     ```kotlin
     fun canConfirm(phase: FlashPairingPhase): Boolean =
         phase == FlashPairingPhase.RequestReceived
     ```
     `ActionButtonsRow` (containing [Accept] and [Decline]) is only rendered in `RequestReceived`. The initiator never enters `RequestReceived`; its phase transitions directly from `AwaitingPeerNonce` to `AwaitingPeerConfirmation`.
  2. In `FlashPairingCoordinator.kt:269-277` and `PairingCoordinator.kt:285-293`:
     ```kotlin
     is FlashPairingEvent.PeerAccepted -> {
         val s = protocol.session.value
         val peerId = s.peerDeviceId
         val hash = s.expectedCodeHashHex
         if (peerId != null && hash != null) {
             sendToPeer(peerId, PairingWireCodec.encode(FlashPairingFrame.PairConfirm(event.requestId, hash)))
         }
     }
     ```
     As soon as `PAIR_ACCEPT` is received, the coordinator immediately computes and transmits `PAIR_CONFIRM` via WebSocket without prompting the initiator user.  
  3. In `PairingSessionStateMachine.kt:313-320`: `LocalAccept` only transitions from `RequestReceived` or `AwaitingLocalDecision`. On the initiator, `LocalAccept` is completely unhandled.  
- **Why existing guards do not stop it:** `TofuPinVerifier` records the attacker's TLS leaf key on first connection. `PairingV2.matchesPinnedIdentity` validates that the pairing frame matches the transport TLS key, which succeeds because the attacker controls the transport connection. The cryptographic commitment guarantees that both parties agreed on the same code, but because the attacker's software automatically accepts, the out-of-band verification is completely bypassed.  
- **Verifier result:** Survived. Tracing the state transitions and UI rendering confirms that the initiator user is never prompted to confirm code equality.  
- **Recommended fix:** Implement symmetric confirmation requiring explicit user acceptance on both sides:
  1. Add an `AwaitingLocalDecision` state on the initiator after `PAIR_NONCE` is processed and the code is derived.
  2. Display the 6-digit comparison code with an "Accept" / "Confirm Match" button on the initiator UI.
  3. Transmit `PAIR_CONFIRM` only after `LocalAccept` is triggered by the initiator user.  
- **Related:** ADR-042, UI-032.

---

### SEC-D03-02 — Static Ephemeral Keypair Reused Across Engine Lifetime Lacks Forward Secrecy and Cryptographic Context Binding
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (LAN unauthenticated), A2 (Paired peer), A7 (Thief / forensic examiner)  
- **Component:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:309, 328`, `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:1575-1576, 1582`, `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/FlashCrypto.kt:111-117`  
- **CWE / standard:** CWE-326 (Inadequate Encryption Strength); CWE-330 (Use of Insufficiently Random Values); ASVS 4.0.3-6.2.5; MASVS-CRYPTO-1; CVSS:3.1/AV:A/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N (5.3 Medium)  
- **What is wrong:** The "ephemeral" ECDH keypair utilized during Pairing v2 is not ephemeral. It is generated exactly once during engine initialization (`crypto.generateEphemeralEcdhKeyPair()`) and passed as a static instance to `PairingCoordinator` / `FlashPairingCoordinator`. The same public key is advertised to all peers, and the same private key is used to compute all ECDH shared secrets. Furthermore, the HKDF key derivation function (`EcP256Ops.sessionKeyFromSharedSecret`) uses an empty salt (`FlashCrypto.EMPTY_SALT`) and a static info string (`"flash-e2e-v1"`), failing to bind the pairing nonces (`N_I`, `N_R`) or device identities into the session key.  
- **Impact:** Re-pairing between the same two devices produces identical session keys. Compromise of the process memory holding this single keypair exposes all pairwise session keys negotiated with every peer throughout the lifetime of the application. The lack of nonce and identity binding in the KDF violates standard cryptographic hygiene (e.g., Noise Protocol / TLS 1.3 key schedule standards).  
- **Evidence:**  
  1. `DesktopEngine.kt:309, 327-328`:
     ```kotlin
     private val ephemeralKeyPair = crypto.generateEphemeralEcdhKeyPair()
     ...
     // One ephemeral ECDH key for the engine's lifetime, used to derive AES-256 session keys.
     ephemeralPublicKey = ephemeralKeyPair.publicKeyEncoded,
     ```
  2. `DiscoveryEngineHolder.kt:1574-1576`:
     ```kotlin
     // One ephemeral ECDH key reused for the lifetime of this engine, used to derive AES-256 session keys.
     val ephemeralKeyPair = crypto.generateEphemeralEcdhKeyPair()
     val ephemeralPublicKey = ephemeralKeyPair.publicKeyEncoded
     ```
  3. `FlashCrypto.kt:111-117`:
     ```kotlin
     fun sessionKeyFromSharedSecret(sharedSecret: ByteArray): ByteArray =
         Hkdf.derive(
             ikm = sharedSecret,
             salt = FlashCrypto.EMPTY_SALT,
             info = FlashCrypto.SESSION_INFO,
             outLength = FlashCrypto.SESSION_KEY_SIZE_BYTES,
         )
     ```
- **Why existing guards do not stop it:** `DefaultFlashPairingProtocol` accepted a lambda provider (`ephemeralPublicKeyProvider: () -> ByteArray`), but both host implementations pass a lambda returning the single startup key.  
- **Verifier result:** Survived. Confirmed in both host initialization paths.  
- **Recommended fix:** Generate a fresh ECDH keypair per pairing session inside `beginRequest` and `onFrame(PairRequest)`. Update HKDF session key derivation to salt with `initiatorNonce + responderNonce` and bind `initiatorFingerprint + responderFingerprint` into `info`.  
- **Related:** ADR-042, audit 2026-09-23 S2.

---

### SEC-D03-03 — Absence of Pairing Attempt Rate Limiting, Failure Lockout, and Glare Handling Enables UI Takeover and DoS
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN / hotspot device)  
- **Component:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingSessionStateMachine.kt:189-221`, `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocol.kt:237`, `app/src/main/java/com/transfer/flash/MainActivity.kt:3441-3452`  
- **CWE / standard:** CWE-307 (Improper Restriction of Excessive Authentication Attempts); CWE-400 (Uncontrolled Resource Consumption); MASVS-AUTH-2; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H (6.5 Medium)  
- **What is wrong:** The pairing state machine restricts execution to a single concurrent session across the entire engine and enforces no rate-limiting, failure thresholds, or lockout mechanisms. In addition, when two devices simultaneously tap "Pair" on each other, a protocol glare condition occurs: both enter `AwaitingPeerNonce` and reject each other's `PAIR_REQUEST` without negotiating or resolving priority, deadlocking until timeout. Finally, on Android, `FlashPairingDialog` is presented globally over any active application screen upon receipt of an unsolicited pairing request.  
- **Impact:** An attacker on the local network can flood `PAIR_REQUEST` frames to continuously display modal pairing dialogs on the victim's screen, disrupting application usage. Furthermore, an attacker can keep the victim's pairing engine permanently occupied in `AwaitingPeerReveal`, systematically blocking legitimate pairing attempts with other devices.  
- **Evidence:**  
  1. In `DefaultFlashPairingProtocol.kt:236-237`:
     ```kotlin
     before.phase != PairingPhase.Idle || s.requestId != frame.requestId ->
         emit(FlashPairingEvent.Failed(frame.requestId, "request-rejected"))
     ```
     Any incoming request while a session is active is unconditionally dropped.  
  2. `MainActivity.kt:3441-3444`:
     ```kotlin
     if (nav.current.destination != FlashDestination.NearbyDevices) {
         pairingModel?.request?.let { request ->
             FlashPairingDialog(...)
         }
     }
     ```
     The pairing dialog is rendered as an overlay regardless of what screen the user is currently interacting with.  
  3. No failure attempt counters, backoff intervals, or lockout timestamps exist in `PairingSessionStateMachine.kt` or the coordinators.  
- **Why existing guards do not stop it:** A 2.5-second terminal linger delay exists before `resetProtocol()`, but the state machine immediately returns to `Idle` upon expiration, allowing indefinite re-triggering.  
- **Verifier result:** Survived. Tracing request handling confirms total absence of rate limits and glare resolution.  
- **Recommended fix:**  
  1. Implement per-peer exponential backoff and temporary lockout (e.g., 5-minute block after 3 declined or failed attempts).  
  2. Suppress full-screen modal pairing prompts when the user is not actively on the `NearbyDevices` tab; present unobtrusive system notifications instead.  
  3. Implement tie-breaking glare resolution (e.g., lexicographical comparison of device IDs) when simultaneous `PAIR_REQUEST` frames cross on the wire.  
- **Related:** UI-032.

---

### SEC-D03-04 — Peer Unpairing Deletes Underlying TLS Pin and Fails to Terminate Active Transport Sessions or Inhibit Automatic Re-Pairing
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (LAN device), A2 (Paired peer), A3 (Hub colleague)  
- **Component:** `core/security/src/androidMain/kotlin/com/transfer/flash/core/security/trust/AndroidPreferencesTrustStore.kt:48`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopIdentityStores.kt:267`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:530-555, 5996`  
- **CWE / standard:** CWE-613 (Insufficient Session Expiration); CWE-285 (Improper Authorization); MASVS-AUTH-3; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:H/A:N (7.1 High / Medium)  
- **What is wrong:** When a user revokes trust for a peer, `revokeTrust` deletes the peer's stored TLS pin from the trust store if it is not vouched by a group (`pins.remove(deviceId)`). This contradicts documented security design (`docs/developer-guide/guides/pairing-and-trust.md:270`), which states that revoking trust must preserve the TLS pin. Furthermore, `revokeTrust` fails to close active WebSocket transport sessions. Finally, in `RealFlashChatRepository`, incoming 1:1 text messages do not require paired status, and if conversation history exists with an organization colleague, `maybeAutoPairColleague` immediately re-pairs the peer upon receipt of any message, overriding the user's revocation.  
- **Impact:**  
  1. Deleting the TLS pin allows an attacker taking over an unpaired device ID to connect under TOFU first-use rules without triggering a `KEY_CHANGED` pin-mismatch security alert.  
  2. An unpaired peer retains live transport WebSocket connectivity and can continue exchanging frames until the socket drops naturally.  
  3. A user cannot unpair a compromised or unwanted organization colleague; any new incoming message silently restores paired and verified status in the local trust store.  
- **Evidence:**  
  1. `AndroidPreferencesTrustStore.kt:48` and `DesktopIdentityStores.kt:267`:
     ```kotlin
     if (vouchingGroups(deviceId).isEmpty()) edit.remove(pinKeyFor(deviceId.value))
     ```
  2. `DesktopShell.kt:1983` and `PairingCoordinator.kt:253` call `trustStore.revokeTrust(...)` without notifying the transport layer (`network.closeSession(...)`).  
  3. `RealFlashChatRepository.kt:535-550`:
     ```kotlin
     val page = messageDao.historyBefore(peerId, Long.MAX_VALUE, "\uFFFF", AUTO_PAIR_SCAN)
     val sentByMe = page.any { it.senderId == localDeviceId }
     val sentByThem = page.any { it.senderId == peerId }
     if (!sentByMe || !sentByThem) return@runCatching
     ...
     pairColleagueFromDirectory(peerId)
     ```
     `maybeAutoPairColleague` inspects past historical messages rather than messages exchanged after the unpair event.  
- **Why existing guards do not stop it:** `maybeAutoPairColleague` checks only `!isTrustedPeer(peerId)` and `effectiveIsOrgPeer(peerId)`. Because the database still contains past messages, it triggers auto-pairing immediately.  
- **Verifier result:** Survived. Confirmed in trust store implementations and chat repository lifecycle.  
- **Recommended fix:**  
  1. Retain the TLS identity pin upon `revokeTrust` so future connection attempts with altered keys raise `KEY_CHANGED` warnings.  
  2. Immediately disconnect active WebSocket connections to the revoked peer.  
  3. Record an explicit `unpairedAtMs` timestamp or tombstone in `FlashTrustStore` and require manual user action to re-pair a revoked colleague.  
- **Related:** ADR-044, ADR-174.

---

### SEC-D03-05 — RequestId Field Omitted from State Machine Events for Completion Frames (`PairConfirm` and `Paired`)
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A1, A2  
- **Component:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocol.kt:297, 315`, `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/PairingSessionStateMachine.kt:140, 144, 344-373`  
- **CWE / standard:** CWE-345 (Insufficient Verification of Data Authenticity); MASVS-CODE-4; CVSS:3.1/AV:A/AC:H/PR:N/UI:N/S:U/C:N/I:L/A:N (3.1 Low)  
- **What is wrong:** The wire models `FlashPairingFrame.PairConfirm` and `FlashPairingFrame.Paired` define an explicit `requestId: String` field. However, `DefaultFlashPairingProtocol.kt` strips `requestId` when mapping to `PairingSessionEvent.PeerConfirmed` and `PairingSessionEvent.Paired`. Consequently, the state machine reducer fails to verify that these completion frames match the active session's `requestId`, whereas all preceding frames (`PeerNonce`, `PeerReveal`, `PeerAccepted`) strictly enforce `event.requestId == state.requestId`.  
- **Impact:** While cryptographic hash matching and public key identity checks prevent arbitrary key substitution, omitting `requestId` validation breaks state machine session binding and allows delayed or reordered frames from previous pairing attempts to satisfy completion transitions.  
- **Evidence:**  
  1. In `FlashPairingProtocol.kt:299`:
     ```kotlin
     reduceSession(PairingSessionEvent.PeerConfirmed(frame.codeHashHex))
     ```
     `frame.requestId` is ignored.  
  2. In `FlashPairingProtocol.kt:318`:
     ```kotlin
     reduceSession(
         PairingSessionEvent.Paired(
             frame.peerFingerprintHex,
             frame.peerEphemeralPublicKey,
         ),
     )
     ```
     `frame.requestId` is ignored.  
  3. In `PairingSessionStateMachine.kt:344, 355`: Neither event type contains a `requestId` property.  
- **Why existing guards do not stop it:** Frame validation in the state machine relies solely on the expected code hash and ephemeral key byte equality.  
- **Verifier result:** Survived. Confirmed by signature inspection.  
- **Recommended fix:** Include `requestId` in `PairingSessionEvent.PeerConfirmed` and `PairingSessionEvent.Paired` and enforce `if (event.requestId != state.requestId) return state` in the reducer.  
- **Related:** ADR-042.

---

### SEC-D03-06 — Missing Visual Verification Copy in Pairing Consent UI Bypasses Out-of-Band Comparison Security
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A1, A2  
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashPairingFlow.kt:195-235, 271-282, 368-390`  
- **CWE / standard:** CWE-1021 (Improper Restriction of Rendered UI Layers or Components); MASVS-AUTH-1; CVSS:3.1/AV:A/AC:L/PR:N/UI:R/S:U/C:L/I:L/A:N (4.6 Medium / Low)  
- **What is wrong:** In `FlashPairingDialog`, the UI displays the peer name, "wants to pair", transport glyphs, a 6-digit number, a countdown bar, and prominent [Decline] and [Accept] buttons. However, there is no visible text or instruction prompting the sighted user to compare the displayed digits against the peer device's screen. The sentence "Confirm it matches the other device" is defined exclusively in `contentDescription` for accessibility screen readers and is invisible in the visual layout.  
- **Impact:** Sighted users are not informed of the purpose of the 6-digit number. Users frequently treat the number as an incoming PIN or one-time code and tap the visually prominent [Accept] button without comparing displays, completely negating the out-of-band MITM defense of Numeric Comparison.  
- **Evidence:**  
  1. `FlashPairingFlow.kt:270-282`: Visually renders only the peer name and "wants to pair".  
  2. `FlashPairingFlow.kt:311-345` (`VerificationCodeBlock`): Visually renders only the split 3-digit tiles without explanatory caption.  
  3. `FlashPairingFlow.kt:325`: The instructional text exists only inside Compose semantics:
     ```kotlin
     contentDescription = "Verification code $code. Confirm it matches the other device."
     ```
- **Why existing guards do not stop it:** Visual styling emphasizes the primary call to action ([Accept]) over verification instructions.  
- **Verifier result:** Survived. Verified in visual preview and layout inspection.  
- **Recommended fix:** Add clear, visible explanatory copy beneath the code tiles: "Confirm this code matches the code shown on <peerName>'s screen." Style the buttons so that "Confirm Match" requires an explicit user choice.  
- **Related:** UI-032.

---

## 5. Refuted / Rejected Candidates

The following hypotheses were investigated and formally refuted:

1. **REJ-D03-01 — Pairing Protocol Downgrade to v1 via Version Negotiation**
   - *Hypothesis:* An attacker can manipulate wire fields to downgrade pairing to protocol v1, forcing the static fingerprint derivation that is susceptible to offline key grinding.
   - *Why Refuted:* `PairingSessionStateMachine.kt:198` explicitly rejects any request where `event.protocolVersion < PairingV2.PROTOCOL_VERSION` (2) or where `peerCommitHex` is missing, failing closed into `PairingPhase.Failed(REASON_UPDATE_REQUIRED)`. Furthermore, both `FlashPairingCoordinator.kt:214` and `PairingCoordinator.kt:218` refuse to initiate pairing if the peer's hello frame advertises a protocol version below 2.
2. **REJ-D03-02 — Transparent Relay / Reflection of Pairing v2 Frames Across Disparate TLS Sessions**
   - *Hypothesis:* A network attacker can relay pairing frames between two honest devices pairing simultaneously to establish a cross-connection session.
   - *Why Refuted:* `PairingSessionStateMachine.kt:206, 279` invokes `PairingV2.matchesPinnedIdentity(event.peerFingerprintHex, event.pinnedPeerFingerprintHex)`. The pinned fingerprint is derived directly from the TLS leaf certificate authenticated on that specific WebSocket connection. An attacker forwarding frames across separate TLS sessions will present a leaf certificate whose SPKI fingerprint mismatches the claimed identity, causing an immediate fail-closed `REASON_IDENTITY_MISMATCH`.
3. **REJ-D03-03 — Timing Side-Channel in Numeric Comparison Code Hash Equality Verification**
   - *Hypothesis:* An attacker can measure execution time differences in `NumericComparisonCode.hashesEqual` or `PairingV2.commitMatches` to leak hash bytes or forge `PAIR_CONFIRM`.
   - *Why Refuted:* Both `hashesEqual` and `commitMatches` call `PlatformCrypto.constantTimeBytesEqual`, which delegates to `java.security.MessageDigest.isEqual` on JVM and Android. Hex strings are normalized to fixed-length representations (64 lowercase hex characters for SHA-256) prior to byte extraction, preventing length leakage and byte-by-byte early termination.
