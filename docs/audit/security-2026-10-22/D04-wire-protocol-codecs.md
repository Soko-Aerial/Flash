# Flash Security Audit 2026-10-22 — Domain D04: Wire Protocol, Codecs, and Parsers

**Audit Date:** 2026-10-22  
**Audited Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain Lead:** Domain D04 Auditor  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash relies on a heterogeneous set of wire protocols, codecs, and framing formats across its communication subsystems:
- **WebSocket Framing & Transport:** Minimal RFC 6455 codecs duplicated across `androidMain` and `jvmMain`, managing HTTP upgrade headers, frame masking, fragmentation reassembly, and pre/post-handshake allocation limits.
- **Binary Chunk Framing & E2E Security:** A custom little-endian binary codec (`ChunkFrame`) for file transfer manifests, chunks, ACK batches, and completions, sealed within AES-256-GCM binary envelopes (`SecureBinaryFrameCodec` / `FSEC`).
- **Text-Framed Control Protocols:** Space-separated key-value framing (`FlashTextFraming`) used for pairing v2 (`PairingWireCodec`), 1:1 chat (`ChatTextFrameCodec`), group messaging and membership (`GroupFrameCodec`), hub sealed files (`HubFileFrameCodec`), push-to-talk (`PttFrameCodec`, `PttSessionCodec`), calling (`CallFrameCodec`), and multicast beacons.
- **Swarm Peer-to-Peer:** A structured little-endian binary framing format (`SwarmFrameCodec` / `FSW1`) supporting 12 distinct frame types for distributed file distribution.
- **Enterprise Hub JSON Protocols:** `kotlinx.serialization`-based JSON framing (`RelayFrameCodec`, `HubRestCodec`, `HubJson`) over WebSockets and REST, with wire versioning, fixed payload budgets, and size-capped padding (`P-REF-1`).
- **Cryptographic Canonical Writers:** Deterministic length-prefixed serializers (`GroupCanonical` and `AuthCanonicalWriter`) providing transcript canonicalization for ECDSA P-256 signatures over certificates, delegations, revocations, and group founding statements.
- **Amateur Radio (Packet):** KISS TNC and AX.25 UI link-layer codecs (`KissFrameCodec`, `Ax25FrameCodec`) supporting radio packet transport.
- **Buffer Pooling:** Bounded `ByteArray` pooling (`ByteArrayPool` and `ChunkBufferPool`) designed to eliminate ART Large Object Space (LOS) garbage collection pauses on constrained hardware.

This white-box audit evaluated all parsers and codecs against memory safety, integer overflow, denial-of-service, delimiter injection, unicode ambiguity, canonical signature stability, and buffer reuse hygiene.

**Key Conclusions:**
1. **Mathematical Soundness in Binary & Canonical Encodings:** `ChunkFrame`, `SwarmFrameCodec`, `GroupCanonical`, and `AuthCanonicalWriter` strictly enforce 4-byte big-endian length prefixes, bound array allocations to actual stream bytes, and reject trailing garbage. Field concatenation ambiguity in signed transcripts is effectively prevented.
2. **Critical Unicode Bidi & Camouflage Omission:** While the Hub announcement subsystem (`AnnouncementText.kt`) meticulously strips Unicode bi-directional override controls (`U+202A..U+202E`, `U+2066..U+2069`) and zero-width characters, `FlashPathSanitizer.kt` and `HubFileFrameCodec.kt` only filter ASCII controls (`\u0000-\u001F`, `\u007F`) and path separators. An attacker can send an executable file disguised via Right-to-Left Override (`U+202E`), causing victims to see `invoice_exe.pdf` while executing malicious binaries.
3. **Unbounded Database Ingestion in Direct Reactions:** `ChatTextFrameCodec.kt` decodes reaction emoji without string length or format constraints, and `RealFlashChatRepository.kt` writes `(messageId, emoji)` directly into SQLite via Room. An attacker can exhaust device storage and crash the UI by injecting thousands of arbitrary emoji strings.
4. **Dirty Buffer Reuse in Low-Memory Pools:** Neither `ByteArrayPool` nor `ChunkBufferPool` zeroes pooled byte arrays on acquisition or release. Residual data from prior decrypted transfers or memory structures remains in memory across reuse cycles.
5. **Text Framing Vulnerabilities:** `FlashTextFraming` resolves duplicate keys by silently taking the last token (`.toMap()`) and fails to escape carriage returns (`\r`) or newlines (`\n`), enabling delimiter and line injection if exposed over stream transports.

---

## 2. Audited Files

Every file listed below was inspected line-by-line during this audit:

| Module | File Path | Scope & Focus |
|---|---|---|
| `:core:network` | `core/network/src/commonMain/.../ws/WebSocketCodec.kt` (JVM & Android) | RFC 6455 framing, opcode handling, 64-bit length parsing, masking, fragmentation, HTTP header parser |
| `:core:network` | `core/network/src/jvmMain/.../ws/WsConnection.kt` | Pre/post handshake allocation budgets (64 KiB vs 4 MiB), keepalive parsing |
| `:core:network` | `core/network/src/jvmMain/.../ws/WsTransferServer.kt` | HTTP upgrade parser, WebSocket key accept SHA-1 derivation |
| `:core:common` | `core/common/src/commonMain/.../perf/ByteArrayPool.kt` | 16K/32K bounded buffer pooling, lifecycle, trim listeners, dirty memory zeroing |
| `:core:common` | `core/common/src/commonMain/.../protocol/FlashTextFraming.kt` | Text framing escaping (`%`, space, `=`), delimiter handling, key-value parser |
| `:core:transfer` | `core/transfer/src/commonMain/.../chunked/ChunkFrame.kt` | Little-endian binary framing (FileStart, Chunk, AckBatch, Complete), string & int reader bounds |
| `:core:transfer` | `core/transfer/src/commonMain/.../chunked/ChunkBufferPool.kt` | Reusable chunk array deque pooling, capacity management, clear on trim |
| `:core:transfer` | `core/transfer/src/commonMain/.../chunked/Chunker.kt` | Pull-based streaming chunk iterator, whole-file digest verification, pool interaction |
| `:core:transfer` | `core/transfer/src/commonMain/.../chunked/ReceivePipeline.kt` | Chunk verification before write, ACK batch generation, session limits, total chunk caps |
| `:core:transfer` | `core/transfer/src/commonMain/.../protocol/WsTransferMessages.kt` | Legacy text messages (`FLASH_WS_HELLO`, `FLASH_FILE_START`), size parsing |
| `:core:security` | `core/security/src/commonMain/.../crypto/SecureBinaryFrameCodec.kt` | `FSEC` v2 framing, AES-256-GCM envelope, 12-byte nonce, LE ciphertext length check |
| `:core:security` | `core/security/src/commonMain/.../pairing/PairingWireCodec.kt` | `FLASH_PAIR` wire codec, Base64 decoding, version handling, commitment decoding |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/GroupFrameCodec.kt` | `FLASH_GROUP`, `FLASH_GMSG`, `FLASH_GSYNC`, bundle decoding, member roster indexing |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/ChatTextFrameCodec.kt` | Direct chat framing (`FLASH_MSG`, `FLASH_RCPT`, `FLASH_READ`, `FLASH_REACT`, `FLASH_TYPING`) |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/HubFileFrameCodec.kt` | `FLASH_HUBFILE`, ticket decoding, layout validation, display name safety checks |
| `:core:messaging` | `core/messaging/src/commonMain/.../protocol/GroupCanonical.kt` | Canonical length-prefixed serializer for charters, certs, messages, and settings |
| `:core:messaging` | `core/messaging/src/commonMain/.../RealFlashChatRepository.kt` | Inbound reaction handling, message text capping, verified author label enforcement |
| `:core:hub-protocol` | `core/hub-protocol/src/commonMain/.../relay/RelayFrameCodec.kt` | JSON polymorphic relay wire v2, chunking, wire padding, UTF-8 size bounds |
| `:core:hub-protocol` | `core/hub-protocol/src/commonMain/.../rest/HubRestCodec.kt` | REST body deserialization, size capping, exception translation |
| `:core:hub-protocol` | `core/hub-protocol/src/commonMain/.../HubJson.kt` | Strict Kotlinx JSON instances, discriminator handling, unknown keys policy |
| `:core:hub-protocol` | `core/hub-protocol/src/commonMain/.../certs/AuthCanonicalWriter.kt` | Canonical binary serialization for DeviceCert, DelegationCert, and RevocationList |
| `:core:swarm` | `core/swarm/src/commonMain/.../codec/SwarmFrameCodec.kt` | FSW1 binary frames (12 types), envelope header validation, piece length checks |
| `:core:radio` | `core/radio/src/commonMain/.../kiss/KissFrameCodec.kt` | KISS TNC framing, escape byte transposition (`FESC`, `TFEND`, `TFESC`) |
| `:core:radio` | `core/radio/src/commonMain/.../kiss/Ax25FrameCodec.kt` | AX.25 UI frame parser, address field termination, SSID decoding, PID validation |
| `:core:discovery` | `core/discovery/src/commonMain/.../core/TxtCodec.kt` | Cross-radio DNS-SD TXT record encoding, capability flag truncation |
| `:core:engine` | `core/engine/src/commonMain/.../FlashPathSanitizer.kt` | Path traversal guard, Windows reserved names, regex illegal characters |
| `:server` | `server/src/main/kotlin/.../directory/DirectoryPrivacy.kt` | Opaque cursor codec (`v1|<length>:<name>|<deviceId>`), query normalization |
| `:server` | `server/src/main/kotlin/.../announcements/AnnouncementText.kt` | Announcement sanitization reference: Unicode NFC, Bidi controls, zero-width stripping |

---

## 3. Checklist for D04

Every item from the audit mandate is addressed below with technical justification:

### 1. Length-field parsing & integer overflow
- **Status:** Not vulnerable (minor legacy exception).
- **Analysis:** 
  - `WebSocketCodec.kt`: 7-bit, 16-bit, and 64-bit lengths are parsed strictly. An 8-byte length with MSB set (bit 63) evaluates to a negative `Long`, which is immediately caught by `if (length < 0 || length > maxMessageBytes)` and throws `IOException`, preventing overflow.
  - `ChunkFrame.kt`: Payload lengths are parsed with `readI32Le` and checked with `payloadLength != bytes.size - HEADER_SIZE`. `dataLength` is bounded to `MAX_CHUNK_DATA_BYTES` (1 MiB), and `count * 4` in `AckBatch` cannot overflow 32-bit integers because `count` is bounded by `MAX_ACK_COUNT` (`1 shl 20`).
  - `SecureBinaryFrameCodec.kt`: Declared ciphertext length is verified against `actualCiphertextLength = frame.size - HEADER_SIZE`.
  - `SwarmFrameCodec.kt`: `bodyLength` is compared against `bytes.size - ENVELOPE_HEADER_SIZE` as a `Long`.
  - `ReceivePipeline.kt`: `(frame.totalBytes + frame.chunkSize - 1) / frame.chunkSize` is checked against `frame.totalChunks.toLong()`, which is capped at `MAX_TOTAL_CHUNKS` (16,777,216).
  - Note: Legacy `WsTransferMessages.parseFileStart` does not assert `size > 0`, but modern transfers run exclusively over `ChunkFrame`.

### 2. Allocation bombs / OOM vectors
- **Status:** Vulnerable (see **SEC-D04-02**).
- **Analysis:** 
  - In network framing, allocations are bounded prior to memory acquisition. `WebSocketCodec` enforces `PRE_HANDSHAKE_MAX_MESSAGE_BYTES = 64 KiB` on unauthenticated connections, and `MAX_MESSAGE_BYTES = 4 MiB` post-handshake. `ChunkFrame` strings are capped at 4096 bytes. `RelayFrameCodec` enforces `MAX_FRAME_BYTES = 64 KiB` and `MAX_PAYLOAD_BYTES = 32 KiB`.
  - However, in `ChatTextFrameCodec.kt`, `FLASH_REACT` accepts an unbounded `emoji` string. When passed to `RealFlashChatRepository.kt:applyReactionDelta`, each unique string creates an unconstrained Room entity in the local SQLite database, creating a persistent disk and heap memory exhaustion vector.

### 3. Recursion depth & stack exhaustion
- **Status:** Not vulnerable.
- **Analysis:** 
  - Binary codecs (`ChunkFrame`, `SwarmFrameCodec`, `Ax25FrameCodec`, `KissFrameCodec`) are strictly iterative, flat-structured, and contain zero recursive parsing methods.
  - JSON serialization via `HubJson` and `HubRestCodec` targets fixed-depth DTOs. Delegation cert chains are hard-limited to `MAX_CHAIN = 4` in `RelayFrameCodec.kt:95`. Group bundle rosters are flat lists capped at `MAX_BUNDLE_CERTS = 84`. Deeply nested structures are neither defined nor accepted.

### 4. Trailing / duplicate / unknown fields
- **Status:** Vulnerable (see **SEC-D04-04**).
- **Analysis:** 
  - Binary codecs enforce strict trailing-byte rejection: `ChunkFrame.kt:312, 332, 350, 359` asserts `reader.exhausted()`, returning null on any trailing byte. `SwarmFrameCodec.kt:219` asserts `buffer.exhausted()`, returning `DecodeResult.Malformed` on trailing bytes.
  - Conversely, `FlashTextFraming.parseFields` converts key-value pairs using `.toMap()`. In Kotlin, `toMap()` silently overwrites duplicate keys with the last parsed value. An attacker can inject conflicting keys to override earlier validated values.

### 5. Type confusion & version downgrade
- **Status:** Vulnerable (see **SEC-D04-05**).
- **Analysis:** 
  - Protocol version downgrade is protected across core handshakes: `PairingWireCodec` requires `protocolVersion >= 2`, `GroupCharter.proto` is pinned to `GroupPolicy.V2_PROTOCOL = 2`, `SecureBinaryFrameCodec` requires `VERSION = 2`, and `SwarmFrameCodec` rejects `VERSION != 1`.
  - However, because `FlashTextFraming.escape()` does not sanitize `\r` or `\n`, an attacker who supplies text containing newlines to line-delimited text sockets can inject entire new protocol frames with spoofed prefixes and types.

### 6. Canonical encoding for signed structures
- **Status:** Not vulnerable.
- **Analysis:** 
  - Both `GroupCanonical` (for group charters, certs, and messages) and `AuthCanonicalWriter` (for device certificates, delegations, and revocations) implement length-framed deterministic serialization: every field is preceded by a 4-byte big-endian length. Sets are sorted lexicographically before writing. Concatenation ambiguity and delimiter injection are prevented.
  - In `DeviceCert.canonicalBytes()`, `department.orEmpty()` maps `null` to `""`. While both produce identical canonical representations, department `""` confers no administrative privileges in `OrganizationDirectory.kt`.

### 7. Non-throwing decoders & error suppression
- **Status:** Not vulnerable.
- **Analysis:** 
  - All public codecs follow a fail-closed pattern: `ChunkFrame.parse` returns `null`, `SwarmFrameCodec.decode` returns `DecodeResult.Malformed`, `RelayFrameCodec.decodeResult` returns `Decoded.Rejected`, and `Ax25FrameCodec.decode` returns `Ax25DecodeResult.Error`.
  - None of the decoders swallow fatal syntax errors to return partially populated, corrupted, or unvalidated domain objects.

### 8. String decoding: invalid UTF-8, null bytes, bidi controls, homoglyphs
- **Status:** Vulnerable (see **SEC-D04-01**).
- **Analysis:** 
  - `SwarmFrameCodec.decodeStr` correctly uses `decodeToString(throwOnInvalidSequence = true)`. `AnnouncementText.kt` strips bidi overrides (`U+202A..U+202E`, `U+2066..U+2069`) and zero-width spaces (`U+200B..U+200D`).
  - However, `FlashPathSanitizer.kt`, `HubFileFrameCodec.kt`, `PairingWireCodec.kt`, and `ChatTextFrameCodec.kt` fail to strip Unicode bi-directional override characters or zero-width formatting characters from file names and peer display names, enabling Right-to-Left Override (RLO) camouflage and visual spoofing.

### 9. Buffer pool hygiene (`ByteArrayPool` & `ChunkBufferPool`)
- **Status:** Vulnerable (see **SEC-D04-03**).
- **Analysis:** 
  - Buffer leakage on unexpected disconnect was resolved in commit round 2 (review F3) via `ByteArrayPool.use { }`.
  - However, neither `ByteArrayPool.acquire`/`release` nor `ChunkBufferPool.acquire`/`release` zeroes memory (`fill(0)`). Buffers reused across operations retain sensitive plaintext and cryptographic fragments.

### 10. Fuzzing / boundary analysis
- **Status:** Analyzed & documented in findings.
- **Analysis:** 
  - Codec edge cases (empty strings, surrogate pairs, boundary lengths, maximum chunk caps) were analyzed across all target codecs. Found edge-case vulnerabilities in text framing escaping and reaction payload parsing.

---

## 4. Detailed Security Findings

### SEC-D04-01 — Missing Unicode Bi-directional (Bidi) Control Character and Zero-Width Space Sanitization in File and Peer Names Enables Visual Spoofing and File Extension Camouflage
- **Severity:** High  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN / hotspot peer), A2 (Paired peer turned malicious), A3 (Hub member / colleague)  
- **Component:** `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashPathSanitizer.kt:10`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/HubFileFrameCodec.kt:167`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt:128`  
- **CWE / standard:** CWE-451 (User Interface Misrepresentation of Critical Information); CWE-176 (Improper Handling of Unicode Encoding); MASVS-CODE-2; CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:U/C:H/I:H/A:N (7.5)  
- **What is wrong:**
  While `:server`'s `AnnouncementText.kt:113-120` explicitly documents and implements comprehensive filtering for Unicode bidi override/isolate controls (`U+202A..U+202E`, `U+2066..U+2069`) and zero-width characters (`U+200B..U+200D`, `U+2060`, `U+FEFF`), this protection was completely omitted from client file sanitization and message codecs. `FlashPathSanitizer.kt:10` defines `ILLEGAL_CHARS_REGEX = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")`, which only strips ASCII control characters and standard path separators. Similarly, `HubFileFrameCodec.kt:167` only checks `Char::isISOControl`, which does not encompass Unicode bidi or formatting characters.
- **Impact:**
  An attacker can transmit a malicious executable or script with a Right-to-Left Override (RLO) character (`\u202E`) embedded in the filename: for example, `photo_\u202Efdp.exe`. `FlashPathSanitizer.sanitize()` retains the RLO character and preserves the `.exe` extension. When displayed in the Jetpack Compose chat bubble, file transfer progress card, system notification, or desktop file manager, the text renderer reverses the display string to `photo_exe.pdf`. The recipient believes they are receiving a safe PDF document, but opening the file executes the native executable. Furthermore, display names containing zero-width spaces or bidi overrides allow undetectable visual impersonation of legitimate contacts.
- **Evidence:**
  `FlashPathSanitizer.kt:10`:
  ```kotlin
  private val ILLEGAL_CHARS_REGEX = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
  ```
  `HubFileFrameCodec.kt:167`:
  ```kotlin
  private fun isSafeName(name: String): Boolean =
      name.isNotBlank() &&
          name.length <= MAX_NAME_CHARS &&
          name.trim() != "." && name.trim() != ".." &&
          name.none { it == '/' || it == '\\' || it.isISOControl() }
  ```
  Contrast with `AnnouncementText.kt:113-120`:
  ```kotlin
  if (cp in 0x202A..0x202E || cp in 0x2066..0x2069) continue
  if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF) continue
  ```
- **Why existing guards do not stop it:**
  `FlashPathSanitizer` guards against directory traversal (`..`), path separators (`/`, `\`), Windows reserved device names (`CON`, `PRN`), and ASCII control characters, but does not inspect characters in the general Unicode category `Cf` (Format characters) or the specific bidi control ranges.
- **Verifier result:** Survived. Code inspection confirms `\u202E` passes through `sanitize()` completely unaltered.
- **Recommended fix:**
  Update `FlashPathSanitizer.kt` and `HubFileFrameCodec.kt` to filter all bidi control characters (`\u202A..\u202E`, `\u2066..\u2069`) and invisible/zero-width formatting characters (`\u200B..\u200D`, `\u2060`, `\uFEFF`) before validating file extensions and computing display names.
- **Related:** CVE-2021-42574 (Trojan Source), ADR-138.

---

### SEC-D04-02 — Unbounded Reaction Emoji String Ingestion Enables Database Bloat, Storage Depletion, and Mobile App Denial of Service
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A2 (Paired peer turned malicious), A3 (Hub member / colleague)  
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt:163`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6120, 6724-6739`  
- **CWE / standard:** CWE-400 (Uncontrolled Resource Consumption); CWE-770 (Allocation of Resources Without Limits or Throttling); MASVS-RES-1; CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:H (6.5)  
- **What is wrong:**
  In `ChatTextFrameCodec.kt`, the direct reaction codec decodes the `emoji` field directly from the wire without enforcing a maximum length, code point count, or validating that the string represents an actual emoji glyph (`emoji = f["emoji"] ?: return DecodeResult.RecognizedButInvalid`). When dispatched to `RealFlashChatRepository.kt:6120`, the method calls `applyReactionDelta(messageId, frame.emoji, frame.memberId, frame.isAdded)`. `applyReactionDelta` executes `reactionDao.upsert(ReactionEntity(messageId, emoji, ...))`.
- **Impact:**
  The composite primary key of `ReactionEntity` is `(messageId, emoji)`. Because there is no check on the character length of `emoji` or the maximum number of distinct reactions per message, an authenticated peer or organization colleague can flood a chat thread with tens of thousands of reaction frames carrying random 1 KiB strings. Each frame creates a new persistent row in the encrypted SQLCipher database. This causes catastrophic database bloat, disk storage depletion on mobile devices, query slowdowns, and `OutOfMemoryError` during conversation loading in the UI.
- **Evidence:**
  `ChatTextFrameCodec.kt:157-167`:
  ```kotlin
  FlashTextFraming.parseFields(text, REACT_PREFIX)?.let { f ->
      return DecodeResult.Frame(
          MessageWireFrame.ReactionFrame(
              messageId = f["messageId"] ?: return DecodeResult.RecognizedButInvalid,
              conversationId = f["conversationId"] ?: "",
              memberId = f["memberId"] ?: return DecodeResult.RecognizedButInvalid,
              emoji = f["emoji"] ?: return DecodeResult.RecognizedButInvalid,
              isAdded = f["isAdded"]?.toBooleanStrictOrNull() ?: true,
          ),
      )
  }
  ```
  `RealFlashChatRepository.kt:6724-6739`:
  ```kotlin
  val existing = reactionDao.get(messageId, emoji)
  val reactors = decodeReactorIds(existing?.reactorIdsJson).toMutableSet()
  if (isAdded) reactors.add(reactorId) else reactors.remove(reactorId)
  if (reactors.isEmpty()) {
      reactionDao.remove(messageId, emoji)
  } else {
      reactionDao.upsert(
          ReactionEntity(
              messageId = messageId,
              emoji = emoji,
              count = reactors.size,
              selfReacted = localDeviceId in reactors,
              reactorIdsJson = encodeReactorIds(reactors.toList()),
          ),
      )
  }
  ```
- **Why existing guards do not stop it:**
  `RealFlashChatRepository` enforces `MAX_MESSAGE_TEXT_LENGTH = 16_384` for message bodies, but completely lacks length or count limits for reaction frames.
- **Verifier result:** Survived. Code inspection confirms `emoji` is neither length-capped nor validated against an emoji whitelist.
- **Recommended fix:**
  In `ChatTextFrameCodec.kt`, enforce that `emoji.length <= 16` and `emoji.codePointCount <= 4`. In `RealFlashChatRepository.kt`, cap the maximum distinct reactions per message (e.g., maximum 20 distinct emojis per message) and reject additions once the limit is reached.
- **Related:** CWE-400.

---

### SEC-D04-03 — Dirty Buffer Reuse Without Zeroing in `ByteArrayPool` and `ChunkBufferPool` Exposes Residual Plaintext
- **Severity:** Medium  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (Unauthenticated LAN peer), A2 (Paired peer), A6 (Local application / process inspection)  
- **Component:** `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/ByteArrayPool.kt:40-70`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ChunkBufferPool.kt:41-65`  
- **CWE / standard:** CWE-226 (Sensitive Information in Resource Not Removed Before Reuse); CWE-908 (Use of Uninitialized Resource); MASVS-STORAGE-1; CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N (5.1)  
- **What is wrong:**
  `ByteArrayPool` pools 16 KiB and 32 KiB arrays for WebSocket frame parsing and network I/O, while `ChunkBufferPool` pools buffers up to 1 MiB for file transfers. When a rented buffer is released via `release(buffer)`, it is added directly to internal deques without zeroing its contents (`buffer.fill(0)`). Similarly, `acquire()` returns existing buffers with prior data intact.
- **Impact:**
  Buffers recycled through these pools frequently hold decrypted chat messages, file payloads, certificates, or session keys. If a subsequent caller rents a 32 KiB buffer for a smaller operation (e.g., reading a 256-byte frame) and fails to strictly limit slice operations to the read count, uninitialized tail bytes containing sensitive plaintext from previous connections can be leaked. Furthermore, memory scrapers or crash reports inspecting the heap obtain long-lived plaintext remnants in the pool.
- **Evidence:**
  `ByteArrayPool.kt:57-70`:
  ```kotlin
  public fun release(buffer: ByteArray) {
      when (buffer.size) {
          SIZE_16KB -> lock.withLock {
              if (pool16k.size < MAX_POOL_CAPACITY) {
                  pool16k.add(buffer)
              }
          }
          SIZE_32KB -> lock.withLock {
              if (pool32k.size < MAX_POOL_CAPACITY) {
                  pool32k.add(buffer)
              }
          }
      }
  }
  ```
  `ChunkBufferPool.kt:56-64`:
  ```kotlin
  public fun release(buffer: ByteArray) {
      val size = buffer.size
      lock.withLock {
          val deque = pools.getOrPut(size) { ArrayDeque() }
          if (deque.size < DEFAULT_MAX_POOLED_PER_SIZE) {
              deque.addLast(buffer)
          }
      }
  }
  ```
- **Why existing guards do not stop it:**
  The pool implementations were optimized purely for ART garbage collection latency reduction (mitigating Large Object Space GC pauses) without security consideration for memory scrubbing.
- **Verifier result:** Survived. Code inspection confirms no zeroing or clearing occurs in either pool.
- **Recommended fix:**
  Wipe buffers upon release: add `buffer.fill(0)` in `ByteArrayPool.release()` and `ChunkBufferPool.release()`.
- **Related:** CWE-226, ADR-087.

---

### SEC-D04-04 — Duplicate Field Injection Silently Overwrites Prior Keys in `FlashTextFraming` Decoders
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (LAN device), A2 (Paired peer), A3 (Hub colleague)  
- **Component:** `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/protocol/FlashTextFraming.kt:41-57`  
- **CWE / standard:** CWE-471 (Modification of Assumed-Immutable Data (MAID)); CWE-20 (Improper Input Validation); MASVS-NETWORK-1  
- **What is wrong:**
  `FlashTextFraming.parseFields()` parses space-separated `key=value` pairs into a list and calls `.toMap()`:
  ```kotlin
  return parts.drop(1)
      .mapNotNull { part ->
          ...
          key to unescape(rawValue)
      }
      .toMap()
  ```
  In Kotlin standard library, `Iterable<Pair<K, V>>.toMap()` inserts entries into a map in iteration order. If a key appears multiple times in the frame string, later occurrences overwrite earlier ones without error.
- **Impact:**
  An attacker can append duplicate keys to the end of a frame string (e.g., `FLASH_PAIR t=hello did=Victim ... did=Attacker`). If an intermediate network monitor, inspection proxy, or log parser validates only the first occurrence of a parameter, the backend application silently operates on the attacker's overridden second value. Decoders do not enforce uniqueness of protocol fields.
- **Evidence:**
  `FlashTextFraming.kt:41-57`:
  ```kotlin
  public fun parseFields(text: String, expectedPrefix: String): Map<String, String>? {
      val parts = text.trim().split(' ')
      if (parts.firstOrNull() != expectedPrefix) return null

      return parts.drop(1)
          .mapNotNull { part ->
              val separator = part.indexOf('=')
              if (separator <= 0) {
                  null
              } else {
                  val key = part.substring(0, separator)
                  val rawValue = part.substring(separator + 1)
                  key to unescape(rawValue)
              }
          }
          .toMap()
  }
  ```
- **Why existing guards do not stop it:**
  `parseFields` performs no duplicate key detection or rejection.
- **Verifier result:** Survived. Confirmed by Kotlin `.toMap()` semantics.
- **Recommended fix:**
  In `FlashTextFraming.parseFields`, iterate through pairs and check for duplicates; return `null` (failing closed) if any key appears more than once.
- **Related:** CWE-471.

---

### SEC-D04-05 — Unescaped Carriage Return and Newline Characters in `FlashTextFraming` Permit Line/Command Injection
- **Severity:** Low  
- **Confidence:** CONFIRMED  
- **Attacker:** A1 (LAN device), A2 (Paired peer)  
- **Component:** `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/protocol/FlashTextFraming.kt:16-28`  
- **CWE / standard:** CWE-93 (Improper Neutralization of CRLF Sequences ('CRLF Injection')); CWE-113 (HTTP Response Splitting / Protocol Splitting); MASVS-NETWORK-1  
- **What is wrong:**
  `FlashTextFraming.escape()` only escapes percent (`%` -> `%25`), space (` ` -> `%20`), and equals (`=` -> `%3D`):
  ```kotlin
  public fun escape(value: String): String {
      return value
          .replace("%", "%25")
          .replace(" ", "%20")
          .replace("=", "%3D")
  }
  ```
  It does NOT escape carriage return `\r` (`%0D`), line feed `\n` (`%0A`), or horizontal tab `\t` (`%09`).
- **Impact:**
  When `FlashTextFraming` messages are serialized across line-delimited transports (such as raw TCP streams, local logging, or IPC sockets), an attacker who controls a text field (e.g., chat text, display name, or device model) containing `\r\n` can split the protocol stream and inject arbitrary synthesized protocol commands (e.g. injecting `FLASH_PAIR` or `FLASH_MSG` lines) that are parsed as separate top-level frames by the receiver.
- **Evidence:**
  `FlashTextFraming.kt:16-28`:
  ```kotlin
  public fun escape(value: String): String {
      return value
          .replace("%", "%25")
          .replace(" ", "%20")
          .replace("=", "%3D")
  }

  public fun unescape(value: String): String {
      return value
          .replace("%3D", "=")
          .replace("%20", " ")
          .replace("%25", "%")
  }
  ```
- **Why existing guards do not stop it:**
  `escape` only accounts for delimiters used within a single line (spaces and equals signs), omitting line termination control characters.
- **Verifier result:** Survived. Confirmed by inspection of `escape()` and `unescape()`.
- **Recommended fix:**
  Extend `FlashTextFraming.escape` and `unescape` to escape `\r` -> `%0D`, `\n` -> `%0A`, and `\t` -> `%09`.
- **Related:** CWE-93, CWE-113.

---

## 5. Rejected / Refuted Hypotheses

The following hypotheses were investigated during this domain audit and refuted:

### REJ-D04-01 — Opaque Directory Cursor Parsing Index Out of Bounds via UTF-8 Multi-Byte Discrepancy
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** `DirectoryPrivacy.kt:62-77` calculates name length from an integer in the cursor and slices the string using substring. If multi-byte UTF-8 characters are encoded, length in characters could diverge from length in bytes, causing string index out of bounds or memory disclosure.
- **Why Refuted:** `encodeCursor` calculates length using `foldedName.length` (which is Kotlin `String` character length in UTF-16 code units). In `decodeCursor`, `rest` is already decoded into a `String`, and `rest.substring(start, start + length)` operates on the exact same character indexing unit. Furthermore, `if (length <= 0 || start + length >= rest.length) return null` and `if (rest[start + length] != '|') return null` enforce strict boundary checks and delimiter verification, failing closed with `null` (HTTP 400).

### REJ-D04-02 — 64-bit Integer Overflow in WebSocket 8-Byte Frame Length Decoder
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** In `WebSocketCodec.kt:343-347`, reading an 8-byte frame length with the most significant bit set can overflow signed 64-bit `Long` representation, bypassing maximum message size guards.
- **Why Refuted:** When bit 63 is set, the resulting 64-bit `Long` evaluates to a negative number in two's complement. Line 351 asserts `if (length < 0 || length > maxMessageBytes) throw IOException(...)`. Because `length < 0` evaluates to true, the frame is rejected before any payload buffer allocation occurs.

### REJ-D04-03 — AX.25 Frame Codec Buffer Overrun via Unterminated Address Field
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An AX.25 radio packet with missing end-of-address bits (`0x01` in SSID byte) could cause an out-of-bounds read loop or buffer overrun in `Ax25FrameCodec.decode()`.
- **Why Refuted:** `Ax25FrameCodec.kt:153-161` steps by 7 bytes while `idx < bytes.size`, and strictly limits address counts with `if (count > 2 + MAX_DIGIPEATERS) return Error(TOO_MANY_DIGIPEATERS)`. If `idx` exhausts the buffer without finding the marker, it returns `Error(ADDRESS_FIELD_UNTERMINATED)`. All subsequent byte slices are validated with `bytes.size < addrLen + 1/2` before indexing.

### REJ-D04-04 — Delimiter Injection in Canonical Signature Writers (`GroupCanonical` & `AuthCanonicalWriter`)
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An attacker can craft field values containing delimiter bytes to shift data between adjacent fields in signed group messages or device certificates without invalidating signatures.
- **Why Refuted:** Neither `GroupCanonical.Writer` nor `AuthCanonicalWriter` uses string delimiters. Instead, both serialize every field by emitting a 4-byte big-endian integer representing the exact byte length, immediately followed by the raw field bytes. Field order is hard-coded and immutable. Concatenation ambiguity is mathematically eliminated.

### REJ-D04-05 — ChunkFrame ACK Batch Integer Overflow Allocation Bomb
- **Domain:** D04 (Codecs & Parsers)
- **Hypothesis:** An ACK batch frame claiming a huge `count` could trigger integer overflow in size checks, allocating an undersized buffer that overflows during unpacking.
- **Why Refuted:** `ChunkFrame.kt:346-347` explicitly asserts `if (count < 0 || count > MAX_ACK_COUNT) return null`, where `MAX_ACK_COUNT = 1 shl 20` (1,048,576). `count * 4` cannot exceed 4 MiB, well below `Int.MAX_VALUE`. Furthermore, `if (reader.remaining() != count * 4) return null` mandates that the incoming byte array must physically contain the full byte sequence, preventing virtual allocation amplification.
