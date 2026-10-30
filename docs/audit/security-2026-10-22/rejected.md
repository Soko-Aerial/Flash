# Flash Security Audit 2026-10-22 — Rejected / Refuted Candidates

This document records candidate findings refuted by a verifier pass, with the exact code reasons, guards, or architectural constraints that prevent exploitation.

---

### REJ-D01-01 — Android Keystore Private Key Export via Reflection or Serialization
- **Domain:** D01 (Identity Keys)
- **Hypothesis:** An attacker with local app execution could extract the ECDSA identity private key by reflection or Java serialization.
- **Why Refuted:** `KeystoreFlashCrypto.kt` initializes keys in `AndroidKeyStore`. The private key returned is an opaque `AndroidKeyStorePrivateKey` hardware handle. `privateKey.encoded` returns `null`. Hardware-backed keys never leave the TEE/StrongBox.

### REJ-D01-02 — Key / Secret Exfiltration in Probes, Logs, or Crash Reports
- **Domain:** D01 (Identity Keys)
- **Hypothesis:** Evidence probes (`FlashProbe`), `RotatingFileLogSink`, or uncaught crash handlers could leak private keys or pairing secrets.
- **Why Refuted:** `FlashProbe.kt` strictly accepts only 8-character truncated identifiers, counts, booleans, and enums. `RotatingFileLogSink.kt` enforces multi-pass regex redactions (`SECRET_ASSIGN`, `INVITE_LINK`, `BASE64_BLOB`). `FlashEcKeyPair` exposes only `publicKeyEncoded` and its opaque `privateKey` handle has no custom `toString()` that prints bytes.

### REJ-D01-03 — Pairing v2 Code Manipulation via Ephemeral Key Grinding
- **Domain:** D01 (Identity Keys)
- **Hypothesis:** A network MITM (A1) can manipulate ephemeral keys to force a predictable 6-digit numeric comparison code.
- **Why Refuted:** `PairingV2.kt` enforces a commitment scheme where the initiator commits to a 16-byte random nonce before receiving the responder's nonce. The 6-digit code is derived using SHA-256 over domain-separated inputs binding ephemeral keys, nonces, roles, and pinned TLS fingerprints, bounding collision probability to 10^-6.

### REJ-D02-01 — Downgrade to Plaintext Transport in P2P Mesh
- **Domain:** D02 (Transport Security)
- **Hypothesis:** A network attacker (A1) can strip TLS or force a plaintext TCP connection during P2P dialing or inbound connection.
- **Why Refuted:** `JvmWsFlashNetwork.kt:294` enforces `requireTransportSecurity`, and `tlsOptions` is non-null in all production engines (`Flash.kt:278`, `DesktopEngine.kt:1223`). If `tlsOptions == null`, `dial()` throws `TransportSecurityUnavailableException` (audit S3 fix). Sockets are immediately wrapped in `SSLSocket` via `SecureSocketUpgrader.upgradeClient/upgradeServer` before any WebSocket frames are exchanged. No cleartext fallback path exists in production code.

### REJ-D02-02 — Group Proof Relay / Reflection across Connections
- **Domain:** D02 (Transport Security)
- **Hypothesis:** An attacker can record a valid `GroupProof` or pairing message from one TLS connection and replay it on another connection to authenticate as a legitimate peer.
- **Why Refuted:** `GroupProofTranscript` (`GroupProof.kt:101-118`) explicitly incorporates `fpInitiator` and `fpResponder` (the SHA-256 fingerprints of the TLS leaf certificates of both ends) and random nonces into the HMAC transcript. Any replayed proof presented on a different TLS session (which has different leaf certificate fingerprints) fails HMAC verification with `Invalid group proof`. Similarly, `PairingV2.kt` binds TLS leaf fingerprints into the ephemeral key agreement transcript.

### REJ-D02-03 — Pre-HELLO Memory Exhaustion via Large WebSocket Frames
- **Domain:** D02 (Transport Security)
- **Hypothesis:** An unauthenticated attacker connecting over TLS can send a massive pre-handshake WebSocket frame payload to cause an `OutOfMemoryError` on the target peer before authentication.
- **Why Refuted:** `WebSocketCodec.kt:119-122` enforces `PRE_HANDSHAKE_MAX_MESSAGE_BYTES = 64 * 1024` (64 KiB) for pre-HELLO frames. If a frame header declares a payload exceeding 64 KiB before HELLO completes, `WebSocketCodec.readFrameHeader` immediately throws `ProtocolException("Pre-handshake frame declared length ... exceeds limit of 65536 bytes")` and aborts the connection. Post-handshake frames are capped at `MAX_MESSAGE_BYTES = 4 * 1024 * 1024` (4 MiB).

### REJ-D03-01 — Pairing Protocol Downgrade to v1 via Version Negotiation
- **Domain:** D03 (Pairing v2)
- **Hypothesis:** An attacker can manipulate wire fields to downgrade pairing to protocol v1, forcing the static fingerprint derivation that is susceptible to offline key grinding.
- **Why Refuted:** `PairingSessionStateMachine.kt:198` explicitly rejects any request where `event.protocolVersion < PairingV2.PROTOCOL_VERSION` (2) or where `peerCommitHex` is missing, failing closed into `PairingPhase.Failed(REASON_UPDATE_REQUIRED)`. Furthermore, both `FlashPairingCoordinator.kt:214` and `PairingCoordinator.kt:218` refuse to initiate pairing if the peer's hello frame advertises a protocol version below 2.

### REJ-D03-02 — Transparent Relay / Reflection of Pairing v2 Frames Across Disparate TLS Sessions
- **Domain:** D03 (Pairing v2)
- **Hypothesis:** A network attacker can relay pairing frames between two honest devices pairing simultaneously to establish a cross-connection session.
- **Why Refuted:** `PairingSessionStateMachine.kt:206, 279` invokes `PairingV2.matchesPinnedIdentity(event.peerFingerprintHex, event.pinnedPeerFingerprintHex)`. The pinned fingerprint is derived directly from the TLS leaf certificate authenticated on that specific WebSocket connection. An attacker forwarding frames across separate TLS sessions will present a leaf certificate whose SPKI fingerprint mismatches the claimed identity, causing an immediate fail-closed `REASON_IDENTITY_MISMATCH`.

### REJ-D03-03 — Timing Side-Channel in Numeric Comparison Code Hash Equality Verification
- **Domain:** D03 (Pairing v2)
- **Hypothesis:** An attacker can measure execution time differences in `NumericComparisonCode.hashesEqual` or `PairingV2.commitMatches` to leak hash bytes or forge `PAIR_CONFIRM`.
- **Why Refuted:** Both `hashesEqual` and `commitMatches` call `PlatformCrypto.constantTimeBytesEqual`, which delegates to `java.security.MessageDigest.isEqual` on JVM and Android. Hex strings are normalized to fixed-length representations (64 lowercase hex characters for SHA-256) prior to byte extraction, preventing length leakage and byte-by-byte early termination.

---

### REJ-D04-01 — Opaque Directory Cursor Parsing Index Out of Bounds via UTF-8 Multi-Byte Discrepancy
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An attacker can craft a directory cursor with mismatched length prefix or multi-byte UTF-8 to cause string index out-of-bounds or slice confusion in `DirectoryPrivacy.decodeCursor`.
- **Why Refuted:** `encodeCursor` and `decodeCursor` both operate strictly on Kotlin `String` character indexes (`foldedName.length` in code units and `substring(start, start + length)`). Boundary validations (`if (length <= 0 || start + length >= rest.length) return null`) and delimiter checks (`if (rest[start + length] != '|') return null`) ensure index safety and reject any malformed or out-of-bounds cursor, failing closed with `null` (HTTP 400).

### REJ-D04-02 — 64-bit Integer Overflow in WebSocket 8-Byte Frame Length Decoder
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** Reading an 8-byte frame length with MSB set (bit 63) could wrap to negative, bypassing maximum message size guards.
- **Why Refuted:** When bit 63 is set, the resulting 64-bit `Long` evaluates to a negative number in two's complement. `WebSocketCodec.kt:351` asserts `if (length < 0 || length > maxMessageBytes) throw IOException(...)`. Because `length < 0` evaluates to true, the frame is rejected before any payload buffer allocation occurs.

### REJ-D04-03 — AX.25 Frame Codec Buffer Overrun via Unterminated Address Field
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An AX.25 radio packet with missing end-of-address bits (`0x01` in SSID byte) could cause an out-of-bounds read loop or buffer overrun in `Ax25FrameCodec.decode()`.
- **Why Refuted:** `Ax25FrameCodec.kt:153-161` steps by 7 bytes while `idx < bytes.size`, and strictly limits address counts with `if (count > 2 + MAX_DIGIPEATERS) return Error(TOO_MANY_DIGIPEATERS)`. If `idx` exhausts the buffer without finding the marker, it returns `Error(ADDRESS_FIELD_UNTERMINATED)`. All subsequent byte slices are validated with `bytes.size < addrLen + 1/2` before indexing.

### REJ-D04-04 — Delimiter Injection in Canonical Signature Writers (GroupCanonical & AuthCanonicalWriter)
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An attacker can craft field values containing delimiter bytes to shift data between adjacent fields in signed group messages or device certificates without invalidating signatures.
- **Why Refuted:** Neither `GroupCanonical.Writer` nor `AuthCanonicalWriter` uses string delimiters. Instead, both serialize every field by emitting a 4-byte big-endian integer representing the exact byte length, immediately followed by the raw field bytes. Field order is hard-coded and immutable. Concatenation ambiguity is mathematically eliminated.

### REJ-D04-05 — ChunkFrame ACK Batch Integer Overflow Allocation Bomb
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An ACK batch frame claiming a huge `count` could trigger integer overflow in size checks, allocating an undersized buffer that overflows during unpacking.
- **Why Refuted:** `ChunkFrame.kt:346-347` explicitly asserts `if (count < 0 || count > MAX_ACK_COUNT) return null`, where `MAX_ACK_COUNT = 1 shl 20` (1,048,576). `count * 4` cannot exceed 4 MiB, well below `Int.MAX_VALUE`. Furthermore, `if (reader.remaining() != count * 4) return null` mandates that the incoming byte array must physically contain the full byte sequence, preventing virtual allocation amplification.

---

### REJ-D05-01 — Direct 1:1 Author Device-ID Impersonation on LAN
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** An attacker A2 connected over LAN can forge `senderId` in a direct `FLASH_MSG` frame to impersonate another peer (e.g. Alice).
- **Why Refuted:** `RealFlashChatRepository.kt:5951` strictly enforces `if (transportPeerId != null && frame.senderId != transportPeerId) return`. In direct LAN connections, `FlashInboundRouter.routeInboundText` extracts `peerDeviceId` directly from the authenticated TLS session leaf certificate and passes it as `transportPeerId`. If `frame.senderId != transportPeerId`, the frame is dropped immediately.

### REJ-D05-02 — Unauthorized Group History Extraction via Catch-Up Sync (ADR-100)
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** A non-member or removed member can query `GroupWireFrame.SyncRequest` to extract confidential group history.
- **Why Refuted:** `RealFlashChatRepository.kt:3747` enforces `if (!isActiveGroupMember(members, frame.groupId, frame.from)) return`. `isActiveGroupMember` verifies that the local device is still active, that the peer is trusted in the group, and that `groupMemberDao.member(groupId, deviceId)?.isActive == true`. If the requester is not an active member in the recipient's local database, the request is discarded before reaching `handleSyncRequest`.

### REJ-D05-03 — Remote Message Plaintext Editing via Wire Action
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** A peer can manipulate or edit previously delivered messages via an unauthorized edit frame.
- **Why Refuted:** While `MessageDao.markEdited` exists in Room DAO queries, there is no message edit frame type in `MessageWireFrame`, `GroupWireFrame`, or `ChatTextFrameCodec`. No wire protocol or repository method exists in production code to mutate message text after insertion.

### REJ-D05-04 — Arbitrary Cross-User Message Deletion
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** An attacker can send `DeleteForEveryone` to delete messages authored by other users.
- **Why Refuted:** In 1:1 chat (`RealFlashChatRepository.kt:6077`), the repository verifies: `if (message.conversationId != peerId || message.senderId != frame.from) return`. In group chat (`RealFlashChatRepository.kt:3677`), it verifies: `if (message.conversationId != frame.groupId || message.senderId != frame.from) return`. A peer can only delete messages where `message.senderId == frame.from`.

### REJ-D05-05 — Database Overwrite via Duplicate Message-ID Injection
- **Domain:** D05 (Messaging Semantics)
- **Hypothesis:** An attacker can overwrite existing message text by injecting a duplicate message with identical `localId`.
- **Why Refuted:** `MessageDao.kt:19-20` annotates `insert(message)` with `@Insert(onConflict = OnConflictStrategy.IGNORE)`. When a message with an existing primary key `localId` arrives, SQLite ignores the insert and returns `-1L`. The original message content, timestamp, status, and attachments remain completely intact.

---

### REJ-D06-01 — Forgery of v2 Group Messages Without Private Key
- **Domain:** D06 (Groups & Rosters)
- **Hypothesis:** An attacker A2 connected to a group can spoof messages from another member without possessing their private identity key.
- **Why Refuted:** In `RealFlashChatRepository.kt:3609` and `SignedGroups.kt:1047-1061`, all inbound messages in `g2-` groups require ECDSA verification of `signature` over canonical bytes (`GroupCanonical.messageBytes`) against the verified member public key stored in `group_members`. If verification fails, `verifiedAuthorLabel` returns `null` and the message is dropped (`probeGroupMessageDrop("bad_signature")`).

### REJ-D06-02 — Roster Rollback via Replay of Stale Membership Bundles on Devices Holding Tombstones
- **Domain:** D06 (Groups & Rosters)
- **Hypothesis:** An attacker can replay an old roster bundle carrying an active certificate to roll back a member's removal on devices that already recorded the tombstone.
- **Why Refuted:** `SignedGroups.kt:805` and `GroupPolicy.kt:116-124` enforce `membershipUpdateWins(candidate, existing)`. When a device holds a tombstone with sequence `seq_tombstone`, any replayed older certificate has `seq_old < seq_tombstone`, causing `membershipUpdateWins` to evaluate to `false`. The candidate certificate is discarded before any signature verification or database write occurs.

### REJ-D06-03 — Applicant Identity Spoofing in Direct Join Requests
- **Domain:** D06 (Groups & Rosters)
- **Hypothesis:** An attacker holding an invite link can forge a `GsJoinRequest` claiming another user's device ID or public key.
- **Why Refuted:** In `RealFlashChatRepository.kt:4117-4156`, direct join requests require: (1) `groupProofSessions.hasProved == true` (mutual proof of group secret knowledge on that specific TLS session); (2) `frame.subjectKey == GroupCanonical.encode(liveKey)` (the claimed subject key must match the TLS leaf certificate presented on that connection); and (3) ECDSA verification of `signature` over canonical `joinRequestBytes` against `subjectKeyBytes`. A forwarder cannot alter `subjectKey` or `subjectId` without invalidating the applicant's signature.

### REJ-D06-04 — Cross-Session Relay of Mutual Group Proof Frames
- **Domain:** D06 (Groups & Rosters)
- **Hypothesis:** A network attacker holding two TLS sessions can relay `GsChallenge` or `GsProof` frames across sessions to authenticate without possessing the group secret.
- **Why Refuted:** `GroupProofTranscript.build` (`GroupProof.kt:29-93`) explicitly incorporates both initiator and responder TLS leaf certificate SHA-256 fingerprints (`fpInitiator` and `fpResponder`), fresh 16-byte random nonces from each end, and asymmetric role bytes (`ROLE_INITIATOR` and `ROLE_RESPONDER`). Replaying proof MACs on a different TLS session with distinct certificate fingerprints causes HMAC-SHA256 verification to fail with `Responder MAC verification failed` or `proof.mac mismatch`.

### REJ-D06-05 — Group Existence Oracle via Unsolicited Group Proof Hellos
- **Domain:** D06 (Groups & Rosters)
- **Hypothesis:** An unauthenticated network attacker can probe devices with `GsHello` frames containing arbitrary group IDs to map which groups a device belongs to.
- **Why Refuted:** In `GroupProofSessions.kt:295-310`, when a responder does not hold the queried `(groupId, epoch)`, it enters a dummy responder state: generating a cryptographically secure random nonce and random MAC, returning `GsChallenge`, and subsequently responding with `GsResult(ok=false, reason="failed")`. It only returns `"stale"` after receiving a valid proof for an older epoch that the device actually holds. The exchange is externally indistinguishable from a failed proof for an existing group.

---

### REJ-D07-01 — Path Traversal via Filename or TransferId Traversal Sequences (`..`, `/`, `\`)
- **Domain:** D07 (File Transfer 1:1)
- **Hypothesis:** An attacker can craft a `transferId` or `fileName` containing directory traversal sequences (`..`, `../`, `..\`) or drive colons to write outside the received files folder.
- **Why Refuted:** `FlashPathSanitizer.sanitizeComponent` strips all characters matching `ILLEGAL_CHARS_REGEX = [\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]` by replacing them with `_`. Lone surrogates are replaced, and trailing dots/spaces are stripped (`trimEnd('.', ' ')`). If the resulting string is `.` or `..`, it falls back to `"unnamed"`. Furthermore, `FlashInboundRouter.kt:780` executes `if (FlashPathSanitizer.sanitize(transferId) != transferId) return false`, immediately rejecting any transfer offer whose `transferId` was altered by sanitization before opening the receive pipeline or creating any filesystem path.

### REJ-D07-02 — Heap Memory Exhaustion via Malicious `totalChunks` Declaration in `FILE_START`
- **Domain:** D07 (File Transfer 1:1)
- **Hypothesis:** An attacker can declare an astronomical `totalChunks` value in `FILE_START` to force allocation of a massive `ResumeBitVector`, exhausting process heap memory prior to user acceptance.
- **Why Refuted:** `ReceivePipeline.kt:376` (fix R-07) enforces `if (frame.totalChunks > MAX_TOTAL_CHUNKS) return RejectReason.INVALID_FILE_START` where `MAX_TOTAL_CHUNKS = 16_777_216`. In addition, line 373 verifies that `(frame.totalBytes + frame.chunkSize - 1) / frame.chunkSize == frame.totalChunks.toLong()`, preventing mathematical inconsistencies. The bit-vector allocation is strictly capped at 2 MiB per session, with concurrent sessions capped at 32 (`DEFAULT_MAX_SESSIONS`), bounding maximum pre-acceptance RAM across all concurrent offers to 64 MiB.

### REJ-D07-03 — Cross-Peer Transfer Control Forgery and Hijacking (`FLASH_XFER`)
- **Domain:** D07 (File Transfer 1:1)
- **Hypothesis:** An unauthenticated or unauthorized LAN peer can send `FLASH_XFER` control frames (`cancel`, `pause`, `resume`) to disrupt or hijack ongoing transfers belonging to another peer.
- **Why Refuted:** `FlashInboundRouter.kt:244-252` validates the owner of the `transferId`: `val owner = ownerOf(transferId, incomingOwners, transferRepository); if (owner != null && owner != peerDeviceId) return true` (frame dropped and logged at WARN). Similarly, `admitReceiverFrame` (lines 799-808) enforces that `CHUNK` and `FILE_START` frames are rejected if `owner != peerDeviceId`. A peer cannot affect, pause, or cancel transfers initiated by another device.

### REJ-D07-04 — Chunk Injection Beyond Declared File Bounds
- **Domain:** D07 (File Transfer 1:1)
- **Hypothesis:** An attacker can declare a small `totalBytes` but stream extra or oversized chunks to cause disk-filling or out-of-bounds array writes.
- **Why Refuted:** `ReceivePipeline.kt:273-283` validates that `frame.index in 0 until totalChunks` (`INDEX_OUT_OF_RANGE`) and verifies that `frame.data.size == expectedChunkLength(session, frame.index)` (`CHUNK_SIZE_MISMATCH`), where `expectedChunkLength` accounts for the exact remaining partial bytes in the final chunk. Any chunk deviating from the declared geometry is rejected without write or ACK. Once all chunks are marked, `session.finished` becomes `true` and late chunks are ignored.

### REJ-D07-05 — Automatic Execution / Launch of Untrusted Transferred Files
- **Domain:** D07 (File Transfer 1:1)
- **Hypothesis:** An incoming file with an executable extension (`.exe`, `.bat`, `.apk`, `.vbs`) is automatically launched or opened upon download completion.
- **Why Refuted:** No auto-open mechanism exists in either Android or Desktop hosts. On Android, files remain in `FlashReceived/` and can only be opened when the user explicitly clicks the completed file in chat or transfer history (`MainActivity.openAttachment`), which triggers an explicit `Intent.ACTION_VIEW`. On Desktop, `DesktopHelpers.openAttachment` is only triggered by explicit user clicks on completed items (`onHistoryOpen` or `onOpenAttachment`).

### REJ-D07-06 — Pre-Acceptance Disk Write / Data Leakage Bypassing User Consent
- **Domain:** D07 (File Transfer 1:1)
- **Hypothesis:** A hostile sender can transmit `CHUNK` frames immediately after `FILE_START` to force file creation on disk before the recipient taps "Accept".
- **Why Refuted:** `ReceivePipeline` initializes fresh sessions with `requireAcceptance = true` (Android `Flash.kt:342`, Desktop `DesktopEngine.kt:1824`). The `sinkFactory` is NOT invoked on offer receipt (`resolvedSink = null`). Any `CHUNK` frame arriving while `awaitingAcceptance == true` is dropped with `RejectReason.AWAITING_ACCEPTANCE` (`ReceivePipeline.kt:265-271`) without touching disk or emitting an ACK. Only when the host explicitly calls `acceptSession()` is the sink handle created and writes permitted.

---

### REJ-D08-01 — Piece Poisoning via Corrupted Swarm Bytes
- **Domain:** D08 (Swarm File Distribution)
- **Hypothesis:** A hostile peer can serve corrupted piece bytes to corrupt the assembled file or alter file contents.
- **Why Refuted:** `SwarmDriver.kt:433` computes `Sha256.digest(frame.bytes)` and matches against `cachedManifests[root].pieceHashes[index]`. If there is a mismatch, `verified = false`, the piece is dropped, and a strike is logged against the peer in `StrikeBook.kt`. Furthermore, whole-file reassembly in `JvmPieceStorage.finalize` and `AndroidPieceStorage.finalize` recalculates the SHA-256 hash of the entire file and asserts exact equality with `expectedSha256` before placing the file into user storage.

### REJ-D08-02 — Forged Swarm Cancellation Tombstones
- **Domain:** D08 (Swarm File Distribution)
- **Hypothesis:** An unauthorized member can forge a `SwarmFrame.Cancel` to kill a download for all group members across the mesh.
- **Why Refuted:** `SwarmDriver.kt:448-457` verifies `groupContext.verifyStatement(authorKey, stmt, frame.signature)` against the origin author's verified public key. Unsigned or forged cancels fail signature validation, are discarded, and earn strikes against the transmitting peer.

### REJ-D08-03 — Cross-Group Content Disclosure Oracle
- **Domain:** D08 (Swarm File Distribution)
- **Hypothesis:** An attacker in Group A can probe whether a peer holds content from secret Group B by querying its root.
- **Why Refuted:** `SwarmDriver.kt:396` and `ServePolicy.kt:85` strictly query content by compound key `(groupId, root)`. If the requested root was not announced in Group A, the engine returns `SwarmRejectReason.UNKNOWN`, identical to non-existent content.

---

### REJ-D09-01 — Forced Camera / Microphone Activation
- **Domain:** D09 (Calls, WebRTC, PTT)
- **Hypothesis:** An incoming call can force the receiver's camera or microphone to turn on without user interaction.
- **Why Refuted:** `CallCoordinator.kt:348` places incoming calls in `RINGING`. In `FlashCallSession.kt:1381`, `startMedia()` (which invokes `MediaDevices.getUserMedia`) is called only from `accept()`, which is triggered strictly by user UI button clicks.

### REJ-D09-02 — PTT Floor Hijacking by Strangers
- **Domain:** D09 (Calls, WebRTC, PTT)
- **Hypothesis:** An unauthenticated LAN device can send PTT frames to broadcast audio through the speaker.
- **Why Refuted:** `PttSessionEngine.kt:255, 283` strictly verifies `isTrustedPeer(peerId)` for all incoming control frames. In `onAudioBytes` (line 366), audio packets are rejected unless the session is in `Listening` state and `peerId == listening.holderId`.

### REJ-D09-03 — STUN/TURN Credential Leakage
- **Domain:** D09 (Calls, WebRTC, PTT)
- **Hypothesis:** Flash leaks network credentials or contacts external STUN/TURN servers.
- **Why Refuted:** `FlashCallSession.kt:1465` configures `iceServers = emptyList()`. Flash calling is host/LAN-only and never makes external DNS queries or STUN requests.

---

### REJ-D10-01 — Remembered-Routes Poisoning via Unsolicited Discovery Sightings
- **Domain:** D10 (Discovery and Presence)
- **Hypothesis:** An attacker can send spoofed mDNS or multicast announcements to inject or overwrite routes in `RememberedRoutes`.
- **Why Refuted:** `RememberedRoutes.kt:207` only records routes via `onAuthenticated(deviceId, host, port)`. `onAuthenticated` is strictly called from `WsFlashNetwork.kt:484` and `JvmWsFlashNetwork.kt:403` after a successful TLS handshake that matches the pinned identity key of an already-paired device (`isPaired(deviceId) == true`). Discovery sightings are never written to `RememberedRoutes`.

### REJ-D10-02 — TXT Record Parsing Buffer Overflow / Memory Exhaustion
- **Domain:** D10 (Discovery and Presence)
- **Hypothesis:** An attacker sending oversized TXT records or invalid UTF-8 strings can crash the discovery parser or trigger OOM.
- **Why Refuted:** `TxtCodec.decode()` and `MulticastProtocol.decode()` enforce strict bounds. `MulticastProtocol.MAX_DATAGRAM_BYTES` limits UDP packets to 1400 bytes, `MAX_TEXT_FIELD_CHARS` caps strings to 64 chars, and `TxtCodec.decode` trims and safely parses values without throwing exceptions. Oversized capability sets are truncated at flag boundaries (`MAX_CAPS_VALUE_LENGTH = 120`).

### REJ-D10-03 — DNS-SD / mDNS Spoofed Identity Hijacking on Paired Connections
- **Domain:** D10 (Discovery and Presence)
- **Hypothesis:** A rogue mDNS advertiser claiming a paired peer's `device_id` can impersonate the paired peer and receive confidential messages.
- **Why Refuted:** When dialing a discovered endpoint claiming `deviceId = Alice`, Flash passes `Alice` to `connectManual(host, port, peerDeviceId)`. In `WsFlashNetwork.kt:374-383` and `TofuX509TrustManager.kt:103`, Flash requires the answering TLS certificate to match Alice's pinned fingerprint. The rogue advertiser lacks Alice's private identity key, so the TLS handshake fails immediately with `SSLPeerUnverifiedException` before any protocol frames or chat messages are exchanged.

---

### REJ-D11-01 — Malicious Deep Link Execution via `flash://g/*` and `flash://enroll`
- **Domain:** D11 (Android Application Surface)
- **Hypothesis:** A browsable deep link intent can force Flash to join an arbitrary group or enroll into an arbitrary hub without user consent.
- **Why Refuted:** In `MainActivity.kt:1181-1204`, deep links only set `pendingJoinLink` / `pendingEnrollLink`, which opens `FlashJoinGroupDialog` or `FlashEnrollDialog`. The user must manually review and tap the "Join Group" or "Enroll Device" action buttons.

### REJ-D11-02 — FileProvider Path Traversal via `content://` Authority
- **Domain:** D11 (Android Application Surface)
- **Hypothesis:** An attacker can access arbitrary filesystem files by supplying path traversal (`..`) in `androidx.core.content.FileProvider`.
- **Why Refuted:** `androidx.core.content.FileProvider` canonicalizes file paths against configured XML roots (`<external-files-path>`, `<files-path>`, `<cache-path>`) and throws `IllegalArgumentException` if any path escapes the root directory. Furthermore, `file_paths.xml` was already hardened to remove root-path (`<root-path path="." />`).

### REJ-D11-03 — Service / Receiver Hijacking via Unprotected Exported Components
- **Domain:** D11 (Android Application Surface)
- **Hypothesis:** Core background services (`FlashBackgroundService`, `FlashCallService`, `PttSessionService`, `RadioLinkService`) or action receivers can be triggered by third-party apps.
- **Why Refuted:** All background services and action receivers in `AndroidManifest.xml` are explicitly marked `android:exported="false"`. `FlashTileService` is protected by `android.permission.BIND_QUICK_SETTINGS_TILE`.

---

### REJ-D12-01 — Windows Explorer Context Menu Argument Injection via `%1`
- **Domain:** D12 (Desktop Application)
- **Hypothesis:** An attacker could craft a malicious filename containing command injection characters or quotes to escape the `%1` argument in the Windows registry handler (`HKCU\Software\Classes\*\shell\Flash\command`).
- **Why Refuted:** Windows Explorer passes the selected file path to the command handler enclosed in double quotes. Windows NTFS and FAT32 file systems enforce strict character blacklists at the kernel level; double quotes (`"`) cannot be present in file or directory names on Windows. Furthermore, `WindowsContextMenuManager.kt:165` quotes both the arguments file and `%1` (`"\"$javaw\" \"@${argsFile.absolutePath}\" \"%1\""`), preventing argument splitting.

### REJ-D12-02 — Desktop Path Traversal via Windows Reserved Device Names
- **Domain:** D12 (Desktop Application)
- **Hypothesis:** Inbound transfers on desktop could exploit Windows reserved device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`..`COM9`) to write directly to DOS device streams or crash the JVM process.
- **Why Refuted:** `FlashPathSanitizer.kt:17-23,76-81` comprehensively identifies Windows device names (including trailing spaces and extensions like `CON.txt` or `aux .dat`, as well as superscript numbers `COM¹`) and prefixes them with an underscore (`_CON.txt`), neutralizing any device namespace collision before writing.

### REJ-D12-03 — Plaintext Session and Identity Key Persistence in Desktop State Directory
- **Domain:** D12 (Desktop Application)
- **Hypothesis:** Desktop session keys and identity private keys are persisted as unencrypted plaintext in `trust.properties` or `identity.properties`.
- **Why Refuted:** While properties files themselves are world-readable on Linux (`SEC-D12-03`), session keys stored in `trust.properties` are encrypted with `vault.seal()` using DPAPI on Windows and the Secret Service keyring / `0600` master key on Linux (`DesktopIdentityStores.kt:124,160-175`). Plaintext keys are rejected and migrated to sealed blobs.

---

### REJ-D13-01 — Unencrypted Android SQLite Database Fallback
- **Domain:** D13 (Data at Rest & Local Persistence)
- **Hypothesis:** When SQLCipher initialization fails or the keystore passphrase cannot be unwrapped, Room falls back to an unencrypted plaintext database.
- **Why Refuted:** `FlashDatabaseOpener.openEncrypted()` strictly passes `SupportOpenHelperFactory` to Room. If the passphrase cannot be resolved or SQLCipher native library fails, an exception is thrown and database initialization halts. The unencrypted fallback (`openInMemory`) is confined to unit tests (`fallbackToDestructiveMigration(true)` is only in `openInMemory`).

### REJ-D13-02 — Unencrypted Full-Text Search (FTS) Auxiliary Tables
- **Domain:** D13 (Data at Rest & Local Persistence)
- **Hypothesis:** Full-text message search creates unencrypted auxiliary shadow tables or an unencrypted external Lucene/FTS index.
- **Why Refuted:** Search is performed via SQL `LIKE` queries (`MessageDao.searchMessages` and `searchConversationMessages`) directly on the `messages` table inside `FlashDatabase`. Recent searches are stored in the `recent_searches` table inside the same encrypted database. No external unencrypted search index exists.

### REJ-D13-03 — SQLite Write-Ahead Logging (WAL) Plaintext Page Leakage
- **Domain:** D13 (Data at Rest & Local Persistence)
- **Hypothesis:** SQLite Write-Ahead Logging (`-wal`) leaks unencrypted database pages to disk.
- **Why Refuted:** Both Zetetic SQLCipher (Android) and SQLite3MultipleCiphers (Desktop) operate at the SQLite pager level above the OS filesystem VFS. All pages written to `-wal` are fully encrypted with the database key and per-page initialization vectors. The `-shm` shared memory file contains only integer page indexes and hash tables, not page payloads.

---

### REJ-D14-01 — Single-Use Enrollment Token Race Condition
- **Domain:** D14 (Hub Authentication & Authorisation)
- **Hypothesis:** Two concurrent enrollment requests presenting the same single-use invite token can both successfully enroll two different devices.
- **Why Refuted:** `IdentityStore.enroll` runs within an SQLite immediate transaction (`db.immediate { conn -> ... }`). In SQLite, `BEGIN IMMEDIATE` acquires a reserved write lock. The first request validates `invite.state == PENDING`, increments `uses = uses + 1`, and commits. The second concurrent request is blocked until the first commits, whereupon it re-reads `uses = 1`, evaluates `invite.state == USED`, and is rejected with `EnrollRefusal.INVITE_USED`.

### REJ-D14-02 — Revoked Device Certificate Renewal Bypass
- **Domain:** D14 (Hub Authentication & Authorisation)
- **Hypothesis:** A revoked device can bypass revocation checks and obtain a fresh certificate by calling `/api/v1/enroll/renew`.
- **Refutation:** `EnrollmentService.renew()` explicitly calls `revocations.isRevoked(presented.serial, presented.deviceId)` on line 335. Furthermore, `store.renew` verifies that the device's registry status is `DeviceStatus.ACTIVE`; if revoked, it returns `RenewRefusal.NOT_ACTIVE` and rejects the renewal with HTTP 403.

### REJ-D14-03 — Cross-Department IDOR via Client-Asserted Department in REST API
- **Domain:** D14 (Hub Authentication & Authorisation)
- **Hypothesis:** An enrolled device in Department A can read or modify Department B's groups or members by specifying `departmentId=dept-b` in query parameters or headers.
- **Refutation:** Per ADR-222, all REST directory endpoints resolve the caller's role and department strictly from the authenticated `HubPrincipal` (`principal.toActor()`). In `DirectoryRoutes.kt:45-48`, client-supplied department headers or parameters are discarded. `hideFromStrangers()` verifies whether the group is visible to the caller's department and throws `DirectoryNotFoundException` (HTTP 404) if it belongs to another department and the caller is not a member.

---

### REJ-D15-01 — Relay Sender Spoofing via Client-Supplied Frame Fields
- **Domain:** D15 (Hub Relay & Mailbox)
- **Hypothesis:** A hostile client can forge the sender device ID in `FLASH_RELAY` or `FLASH_META` to impersonate an administrator or colleague.
- **Refutation:** `RelayFrame.Send` does not contain a sender field. In `RelayFrameRouter.routeOne` (line 311), the router stamps `deliver.from = originId`, where `originId` is `originSession.deviceId` — the authenticated identity cryptographically proved during the challenge-response handshake.

### REJ-D15-02 — Unauthorized Recipient Mailbox Message Deletion via `FLASH_RECV_ACK`
- **Domain:** D15 (Hub Relay & Mailbox)
- **Hypothesis:** An attacker can guess or observe message IDs and send `FLASH_RECV_ACK` to delete another device's pending mailbox messages.
- **Refutation:** In `RelayFrameRouter.handleRecvAck` and `MailboxQueue.acknowledge`, deletion queries are strictly constrained by `WHERE target_device_id = ? AND msg_id = ?`, passing the authenticated `recipient.deviceId`. A device can only delete rows for which it is the registered recipient.

### REJ-D15-03 — Oversized WebSocket Frame Denial of Service
- **Domain:** D15 (Hub Relay & Mailbox)
- **Hypothesis:** An attacker can stream a multi-megabyte WebSocket frame to exhaust server RAM or trigger an OutOfMemoryError.
- **Refutation:** Ktor WebSocket feature is configured with `maxFrameSize = HubContract.MAX_FRAME_BYTES` (256 KiB) in `FlashServerEngine.kt:190`. Any frame exceeding 256 KiB causes Ktor to immediately abort the connection with `CloseReason.Codes.TOO_BIG` before allocating buffer memory.

---

### REJ-D16-01 — Path Traversal via File Upload/Download Endpoints
- **Domain:** D16 (Hub REST API & Data Handling)
- **Hypothesis:** An attacker can use `../` or relative paths in `POST /api/v1/files` or chunk upload/download routes to write or read arbitrary filesystem files.
- **Refutation:** `HubFileIds.isFileId(fileId)` validates that `fileId` is strictly composed of exactly 32 lowercase hex characters (`value.length == 32 && value.all(::isLowerHex)`), rejecting all path separators (`/`, `\`, `..`). `index` is strictly parsed as an integer and mapped to `$index.chunk`.

### REJ-D16-02 — File Quota Race Conditions Permitting Quota Overshoot
- **Domain:** D16 (Hub REST API & Data Handling)
- **Hypothesis:** Concurrent chunk uploads or file creation requests can exploit TOCTOU races to exceed per-device or global file storage quotas.
- **Refutation:** `FileStore.create` and `putChunk` execute inside `db.withTransaction { conn -> ... }`, which executes `BEGIN IMMEDIATE` at the SQLite layer. All quota checks and quota insertions are serialized under the write lock.

### REJ-D16-03 — FTS5 Syntax Injection in Announcement Text Search
- **Domain:** D16 (Hub REST API & Data Handling)
- **Hypothesis:** An attacker can craft malicious FTS5 boolean or proximity operators in search queries (`q`) to cause syntax errors or crash SQLite.
- **Refutation:** `AnnouncementStore.kt:879-885` (`escapeFts5Query`) filters the input query through `Regex("[\\p{L}\\p{N}]+").findAll(input)`, stripping all punctuation, operators (`AND`, `OR`, `NOT`, `NEAR`), and quotes, formatting the tokens into safe double-quoted terms (`"$it"*`).

### REJ-D16-04 — Cross-Origin Request Forgery / CORS Bypass on REST API
- **Domain:** D16 (Hub REST API & Data Handling)
- **Hypothesis:** A malicious web page in a browser can invoke Hub REST endpoints via cross-origin requests.
- **Refutation:** The Ktor `CORS` plugin is completely absent from `FlashServerEngine.kt`. Browsers strictly enforce the default Same-Origin Policy. In addition, `BaselineHeaders.kt:117` enforces `Cross-Origin-Resource-Policy: same-origin` on all responses.

---

### REJ-D17-01 — DOM-Based XSS in Admin Console UI
- **Domain:** D17 (Hub Admin Console & Web Security)
- **Hypothesis:** Malicious strings in user display names or announcement bodies can achieve script execution in the Admin Console.
- **Refutation:** `dom.js` strictly renders all text content via `el.textContent = String(value)`. Inline styles, `on*` attributes, `innerHTML`, `outerHTML`, `eval`, and `document.write` are strictly banned by `dom.js` and blocked by strict Content Security Policy (`script-src 'self'`).

### REJ-D17-02 — Cross-Site Request Forgery (CSRF) on Administrative Endpoints
- **Domain:** D17 (Hub Admin Console & Web Security)
- **Hypothesis:** An attacker can forge state-changing admin requests via third-party web pages.
- **Refutation:** `AdminWeb` enforces a three-tier defense: (1) `SameSite=Strict` and `__Host-` cookies; (2) `Sec-Fetch-Site: same-origin` / `Origin` verification; (3) Mandatory `X-CSRF-Token` header on all non-GET requests matching the session's random CSRF token in constant time.

### REJ-D17-03 — Timing Attacks for Admin Username Enumeration
- **Domain:** D17 (Hub Admin Console & Web Security)
- **Hypothesis:** Discrepancies in password verification timing allow an attacker to enumerate valid administrator usernames.
- **Refutation:** `AdminAuthService.authenticate` always executes a complete PBKDF2 hash verification against `dummyHash` even when the target username does not exist or the account is disabled, guaranteeing constant-time behavior.

### REJ-D17-04 — Admin Console Reachable over Public Port or Cloudflare Tunnel
- **Domain:** D17 (Hub Admin Console & Web Security)
- **Hypothesis:** The Web Admin Console can be reached and targeted over the public tunnel port.
- **Refutation:** By default, `adminOnPublicPort` is `false` and `adminHost` binds to `127.0.0.1`. Requests to `/admin` arriving on the public port are rejected with HTTP 404 Not Found by `AdminListenerPolicy.allows()`.

---

### REJ-D18-01 — AES-GCM Nonce Reuse in `SecretBox`
- **Domain:** D18 (Hub Cryptography & Secrets at Rest)
- **Hypothesis:** Inadequate entropy or static counters lead to AES-GCM nonce reuse when sealing mailbox payloads or TOTP seeds.
- **Refutation:** Every `seal` and `sealBytes` call independently generates a fresh 96-bit random nonce using `SecureRandom`. The probability of a collision in 96 bits under realistic hub data volumes is negligible ($< 2^{-32}$).

### REJ-D18-02 — Cross-Row Cut-and-Paste of Ciphertext
- **Domain:** D18 (Hub Cryptography & Secrets at Rest)
- **Hypothesis:** An attacker with database write access can transplant ciphertext between database rows or columns without detection.
- **Refutation:** `SecretBox` strictly binds context strings via `cipher.updateAAD()`. Mailbox payloads bind length-prefixed `origin`, `target`, and `msgId`. TOTP seeds bind `admin_users.totp_secret:$username`. Any transplant fails with `AEADBadTagException`.

### REJ-D18-03 — Insecure File Permissions on SQLite Database and WAL Files
- **Domain:** D18 (Hub Cryptography & Secrets at Rest)
- **Hypothesis:** The SQLite database and WAL files are created with permissive umasks, exposing sensitive data to other host processes.
- **Refutation:** `ServerDatabase.kt:166-188` (`hardenFilesBeforeOpen`) pre-creates `flash-hub.db` and enforces owner-only permissions (0600 on POSIX, owner-only ACL on Windows) on the directory, main DB, `-wal`, and `-shm` before Hikari initializes.

### REJ-D18-04 — Accidental Production Enablement of `--dev-embedded-root`
- **Domain:** D18 (Hub Cryptography & Secrets at Rest)
- **Hypothesis:** An operator can inadvertently start the production hub with `--dev-embedded-root`, creating an insecure on-server root CA.
- **Refutation:** `ServerConfig.kt:101-103` explicitly enforces `require(!devEmbeddedRoot || allowInsecure)`. Attempting to start with `--dev-embedded-root` when `FLASH_ALLOW_INSECURE` is not set immediately aborts startup with an `IllegalArgumentException`.

---

### REJ-D19-01 — Unauthenticated Access to Metrics and Detail Endpoints over Public Tunnel
- **Domain:** D19 (Deployment, Operations & Configuration)
- **Hypothesis:** The `/health/detail` and `/metrics` management endpoints are exposed without authentication to external attackers over the Cloudflare tunnel.
- **Refutation:** Both routes are guarded by `DefaultManagementGuard.kt:29-38`. Requests originating from non-loopback addresses without `Authorization: Bearer <managementToken>` receive HTTP 403 Forbidden.

### REJ-D19-02 — Public Internet Exposure of Hub and Admin Ports
- **Domain:** D19 (Deployment, Operations & Configuration)
- **Hypothesis:** The host machine exposes internal hub REST and administration console ports on 0.0.0.0 to the public WAN.
- **Refutation:** `docker-compose.yml:57-58` explicitly binds host ports to loopback (`127.0.0.1:18443` and `127.0.0.1:18444`), preventing external access from the host's public network interfaces.

### REJ-D19-03 — CRLF Log Injection via User Inputs
- **Domain:** D19 (Deployment, Operations & Configuration)
- **Hypothesis:** Malicious peers can inject newlines and control characters into logs to forge log entries or poison SIEM analyzers.
- **Refutation:** `LogSanitizer.clean()` is systematically invoked on all dynamic parameters (device IDs, error reasons, URIs) before logging, stripping newline and control characters.

### REJ-D19-04 — Database Corruption During Schema Downgrade
- **Domain:** D19 (Deployment, Operations & Configuration)
- **Hypothesis:** Deploying an older release over a newer database schema results in silent database corruption or broken migrations.
- **Refutation:** `Migrator.migrate()` explicitly checks `if (stored > newest) throw MigrationFailedException(...)`, refusing to run an older binary against a newer schema and failing closed.

### REJ-D20-01 — Non-Standard / Homemade Cryptographic Ciphers
- **Domain:** D20 (Cryptography Review)
- **Hypothesis:** Flash implements custom or homebrew encryption/MAC algorithms susceptible to cryptanalytic breaks.
- **Refutation:** Audited all encryption, digest, and signing call sites. Flash exclusively uses standard NIST P-256 (`secp256r1`), SHA-256, HMAC-SHA256, and AES-256-GCM via platform providers (AndroidKeyStore, Conscrypt, Java Cryptography Architecture). No homemade ciphers are implemented.

### REJ-D20-02 — Timing Oracles on Cryptographic MAC / PIN Verification
- **Domain:** D20 (Cryptography Review)
- **Hypothesis:** An attacker can measure execution time differences when verifying cryptographic MACs, tokens, or pairing PINs to iteratively guess secret bytes.
- **Refutation:** Audited all MAC, pin, token, and fingerprint comparisons (`FlashFingerprint`, `TofuPolicy`, `Sha256`, `AdminAuthService`). All comparisons use constant-time equality checks (`MessageDigest.isEqual` or bitwise accumulating loops) without early exit on byte mismatches.

### REJ-D20-03 — Insecure Random Number Generation for Cryptographic Keys
- **Domain:** D20 (Cryptography Review)
- **Hypothesis:** Cryptographic keys or pairing PINs are derived from pseudo-random generators (`kotlin.random.Random` or `Math.random()`), allowing seed predictability.
- **Refutation:** Audited all usages of `Random` vs `SecureRandom`. `Random.Default` is restricted to UI IDs, backoff retry jitter, and test mock data. All keypairs, nonces, session tokens, and pairing PIN salts strictly invoke `SecureRandom` / `secureRandomBytes`.

### REJ-D20-04 — Fail-Open Signature Verification on Malformed Inputs
- **Domain:** D20 (Cryptography Review)
- **Hypothesis:** Signature verification exceptions (e.g. malformed ASN.1 DER encoding) result in silent acceptance (`catch { true }`).
- **Refutation:** Verified `PlatformCrypto.jvm.kt:104-114` and `PlatformCrypto.android.kt:104-114`. Exception handling blocks return `false` on any parsing or verification error, strictly enforcing fail-closed semantics.

### REJ-D21-01 — Cleartext HTTP Repositories
- **Domain:** D21 (Dependencies & Supply Chain)
- **Hypothesis:** Dependencies or plugins are fetched over cleartext HTTP (`http://`), allowing on-path adversary tampering.
- **Refutation:** Audited all `build.gradle.kts` and `settings.gradle.kts` files across all root and subprojects. All repositories and plugin management configurations exclusively use HTTPS URLs.

### REJ-D21-02 — Dynamic / Wildcard Dependency Versions
- **Domain:** D21 (Dependencies & Supply Chain)
- **Hypothesis:** Dependencies use floating versions (`+`) that silently resolve unvetted upstream releases.
- **Refutation:** Audited `gradle/libs.versions.toml`. All external libraries, plugins, and dependencies declare exact, fixed version strings (no `+` or dynamic ranges).

### REJ-D21-03 — Leaked API Keys in Developer Search Tooling
- **Domain:** D21 (Dependencies & Supply Chain)
- **Hypothesis:** Developer tooling (`tools/tavily_search.py`) commits API keys to git or leaks them into build artifacts.
- **Refutation:** Audited `tools/tavily_search.py`. Local key file `tools/.tavily_api_key` is properly ignored in `tools/.gitignore` and has never been committed to git history.

### REJ-D22-01 — Active Cloud / Service API Tokens in Git History
- **Domain:** D22 (Secrets & Repository Hygiene)
- **Hypothesis:** Secret tokens (`ghp_`, `AKIA`, `xox`, `tvly-`) are present in git commit history.
- **Refutation:** Scanned full git commit history across all branches using `git grep` and commit diff filters. Zero active credentials exist in commit history; matched strings were exclusively pattern references inside audit instructions.

### REJ-D22-02 — Committed Android / JVM Signing Keystores
- **Domain:** D22 (Secrets & Repository Hygiene)
- **Hypothesis:** Signing keystores or private key files (`.jks`, `.keystore`) were committed to git.
- **Refutation:** Searched git history for keystore additions. No release keystores or private certificates were ever committed; `dist/` and `keystore.properties` are properly ignored.

### REJ-D22-03 — Committed Production Database Files
- **Domain:** D22 (Secrets & Repository Hygiene)
- **Hypothesis:** SQLite database files containing cleartext user data exist in version control.
- **Refutation:** Searched git history for SQLite databases (`*.db`, `*.sqlite`). Zero database binaries exist in git tracking; `data/` is strictly ignored in `.gitignore`.

### REJ-D23-01 — Third-Party Telemetry and Analytics Tracking
- **Domain:** D23 (Privacy & Data Minimisation)
- **Hypothesis:** User activity, crash logs, or messaging behaviors are reported to external telemetry services (Firebase, Sentry, Crashlytics).
- **Refutation:** Audited all module build files and imports across the entire repository. Flash contains zero analytics, crash reporting, or commercial telemetry SDKs.

### REJ-D23-02 — Hardware Serial / IMEI / Advertising ID Tracking
- **Domain:** D23 (Privacy & Data Minimisation)
- **Hypothesis:** The application queries persistent hardware identifiers that survive app re-installs.
- **Refutation:** Audited `AndroidPreferencesIdentityStore.kt` and `IdentityKeyVault.kt`. Flash never queries `Settings.Secure.ANDROID_ID`, IMEI, MAC address, or Google Advertising ID. Device identity is purely a software-generated random UUID.

### REJ-D23-03 — Hub File Content Decryption by Hub Server
- **Domain:** D23 (Privacy & Data Minimisation)
- **Hypothesis:** The enterprise hub server can read the contents of files transferred between corporate users.
- **Refutation:** Audited `HubFileSealing.kt` and `HubFileTransfers.kt`. File payloads stored on the hub are encrypted with symmetric keys exchanged via sealed 1:1 envelopes. The hub server only stores opaque ciphertext chunks and has no access to decryption keys.

### REJ-D24-01 — Regular Expression Catastrophic Backtracking (ReDoS)
- **Domain:** D24 (Availability & Resource Exhaustion)
- **Hypothesis:** Malicious strings in user profiles, messages, or headers trigger exponential CPU exhaustion in regular expression matchers.
- **Refutation:** Audited all regex patterns in `core/`, `app/`, `desktop/`, and `server/`. All regular expressions utilize linear, non-nested quantifiers without nested repetitions (`(a+)+`).

### REJ-D24-02 — Unbounded HTTP Request Body Memory Exhaustion on Hub Server
- **Domain:** D24 (Availability & Resource Exhaustion)
- **Hypothesis:** An attacker can upload arbitrarily large HTTP request bodies to crash the hub server with OutOfMemoryError.
- **Refutation:** Audited all REST route handlers in `server/`. Every endpoint utilizes `call.receiveBoundedText()` or `call.receiveBoundedBinary(HubContract.MAX_CHUNK_BYTES)` with explicit size limits (64 KiB default, 4000 chars for announcements, 4 MiB for chunks), rejecting oversized payloads with HTTP 413.

### REJ-D24-03 — Wire Protocol Decompression Bombs
- **Domain:** D24 (Availability & Resource Exhaustion)
- **Hypothesis:** An attacker can send tiny compressed zip/gzip payloads that expand into gigabytes of memory.
- **Refutation:** Audited wire codecs and WebSocket pipelines. Flash does not support or negotiate compressed streams (no gzip/deflate on WebSocket transfer chunks or relay frames). All incoming frames are uncompressed and size-bounded.

### REJ-D25-01 — Over-the-Air Radio Frame Replay Attack
- **Domain:** D25 (Bluetooth / Radio Link)
- **Hypothesis:** An adversary can record over-the-air AX.25 packets and replay them to forge messages or trigger state changes.
- **Refutation:** Audited `RadioSession.kt:107, 180-210`. The radio session implements a strict anti-replay sliding window (`ReplayWindow`) and persistent counter store (`RadioCounterStore`). Inbound replayed frames are rejected with `RadioDrop.REPLAY`.

### REJ-D25-02 — Plaintext Over-the-Air Eavesdropping of Pairwise Radio Chat
- **Domain:** D25 (Bluetooth / Radio Link)
- **Hypothesis:** Radio transmissions transmit chat messages in cleartext AX.25 packets.
- **Refutation:** Audited `RadioSession.kt` and `RadioPairingAdapter.kt`. Pairwise radio frames are end-to-end encrypted with AES-256-GCM using directional keys derived via HKDF from the established pairing secret.

### REJ-D25-03 — Plaintext Logging of Radio Frame Contents
- **Domain:** D25 (Bluetooth / Radio Link)
- **Hypothesis:** Radio frame payloads and cleartext packets are written to device logs.
- **Refutation:** Audited `RadioEvidence.kt` and logging calls in `:core:radio`. Logs record packet metrics, signal counters, and 8-character truncated identifiers; message plaintexts are never logged.

### REJ-D26-01 — TOTP Code Replay Across Validity Steps
- **Domain:** D26 (Time, Clocks & Freshness)
- **Hypothesis:** An attacker who intercepts a valid TOTP code can replay it multiple times within the 30-second window.
- **Refutation:** Audited `AdminAuthService.kt:830-860`. The server stores `last_totp_step` in SQLite for the authenticated admin user. Presenting a code corresponding to `step <= last_totp_step` is rejected, preventing replay within the valid window.

### REJ-D26-02 — Offline Org Trust Lease Survival on Clock Rollback
- **Domain:** D26 (Time, Clocks & Freshness)
- **Hypothesis:** Rolling back the system clock extends the 30-day offline organization lease indefinitely.
- **Refutation:** Audited `OrgPeerResolver.kt:310-312`. The resolver checks `now < lastSyncSuccessMs - SKEW_TOLERANCE_MS`. Rolling the system clock back past 5 minutes immediately trips `OrgLease.Expired(reason = CLOCK)`, failing closed.

### REJ-D26-03 — Mailbox Envelopes Stored Indefinitely on Server
- **Domain:** D26 (Time, Clocks & Freshness)
- **Hypothesis:** Expired message envelopes persist indefinitely in the server database, allowing late retrieval.
- **Refutation:** Audited `MailboxQueue.kt` and `HubSweeper`. Expired messages are deleted from SQLite via `DELETE FROM mailbox_queue WHERE expires_at < ?` using the hub server's database clock.

### REJ-D27-01 — Directory Statement Overwriting Out-of-Band PAIRED Pins
- **Domain:** D27 (Trust Model & Business Logic)
- **Hypothesis:** A directory statement from the hub can replace an existing out-of-band PAIRED pin, allowing a malicious hub to hijack trust.
- **Refutation:** Audited `OrgPeerResolver.kt:278-290` and `OrgPeerResolverTest.kt:341`. If `trustStore.pinSource(deviceId)` is `PAIRED` or `VOUCHED`, `storedPin` is strictly protected and never replaced by a directory statement.

### REJ-D27-02 — PTT Audio Injection by Unpaired Colleague
- **Domain:** D27 (Trust Model & Business Logic)
- **Hypothesis:** Organization colleagues who have not paired out-of-band can initiate Push-to-Talk audio sessions.
- **Refutation:** Audited `PeerTrustPolicy.kt:21` and `PttSessionEngine.kt`. PTT transmission and playback strictly require `isPaired == true`. ORG colleagues cannot trigger immediate PTT audio.

### REJ-D27-03 — Unauthorized Group Administration by Unpaired Colleague
- **Domain:** D27 (Trust Model & Business Logic)
- **Hypothesis:** Colleagues in the directory snapshot can manage or invite users to local ad-hoc groups.
- **Refutation:** Audited `PeerTrustPolicy.kt:29-30` and `RealFlashChatRepository.kt:5623`. All administrative actions (`mayAdministerGroup`, `mayAddGroupMember`) strictly require out-of-band PAIRED trust.

### REJ-D28-01 — Debug Backdoors or Verification Bypasses Active in Release Builds
- **Domain:** D28 (Test & Documentation Integrity)
- **Hypothesis:** Release builds contain test backdoors or debug bypass flags that disable cryptographic verification.
- **Refutation:** Audited all dev flags and test hooks. Flags such as `--dev-embedded-root` require `FLASH_ALLOW_INSECURE=true`, log fatal warnings on startup, and fail closed if configured improperly.

### REJ-D28-02 — Swallowed Assertion Failures in Test Suites
- **Domain:** D28 (Test & Documentation Integrity)
- **Hypothesis:** Test suites use try/catch blocks that swallow assertion errors, leading to vacuous green CI passes.
- **Refutation:** Audited unit test suites across all core modules. Test assertions strictly verify expected outcomes and exceptions; no catch-all blocks swallow assertion failures.




























