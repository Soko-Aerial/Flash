# Domain O09 — WebSocket Mesh and Wire Efficiency Performance Audit
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Domain:** O09 — WebSocket Mesh and Wire Efficiency  
**Target Runtimes:** Android (ART / Conscrypt TLS / OkHttp & Custom WS Codec), Desktop (JVM 21 / SunJSSE), Server (JVM 21 / Ktor WebSockets)

---

## 1. Files Read & Audited

The following WebSocket mesh, connection management, protocol codecs, TLS context, resilience, and framing implementations were read and audited in detail:

### Network Mesh & Connection Core
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsTransferClient.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsTransferClient.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/DefaultFlashNetwork.kt`

### Keepalive, Planning, and Resilience
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/ws/WsKeepalive.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/ws/WsKeepaliveTicker.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/ws/WsKeepaliveTiming.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/mode/ConnectionModePolicy.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/mode/DialBudget.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/ConnectionPlanner.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueue.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/SessionHardeningPolicy.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/ReconnectStagger.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/resilience/ReconnectPolicy.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt`
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashTransportProfile.kt`

### TLS & Security Upgrades
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/tls/FlashTlsContextFactory.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/tls/FlashTlsContextFactory.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/tls/SecureSocketUpgrader.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/tls/SecureSocketUpgrader.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt`

### Protocol Framing & Serialization
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/protocol/FlashTextFraming.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/MessageWireFrame.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupWireFrame.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubJson.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/relay/RelayFrameCodec.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/relay/RelayFrame.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallFrameCodec.kt`
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/protocol/PttWireFrame.kt`

---

## 2. Hot Paths with Execution Frequency & Dispatchers

1. **Outbound Frame Serialization and Socket Transmission (`Dispatchers.IO` / Calling Threads)**
   - *Path:* `WsSession.sendText` / `send(ByteArray)` -> `WsConnection.send` -> `writeLock.lock()` -> `WebSocketCodec.writeFrame` -> `OutputStream.write` -> `OutputStream.flush()`.
   - *Frequency:* 10 Hz to 500 Hz during active file transfers (64 KiB chunk bursts), 1–10 Hz during active chat/signaling.
   - *Cost:* Blocks synchronously on Java `writeLock` and blocking network `output.write()`. BoundedSendQueue is bypassed, leading to complete head-of-line blocking across multiplexed data (file chunks) and control (keepalive PINGs, typing, receipts, call signaling).

2. **Inbound WebSocket Frame Read, Demasking, and Channel Ingestion (`Dispatchers.IO`)**
   - *Path:* `WsConnection.readLoop` -> `WebSocketCodec.MessageReader.read` -> `readFrameHeader` -> `readFramePayload` -> `WsSession.onTextReceived` / `onBinaryReceived` -> `Channel.trySend` (with `Thread.sleep(25)` fallback).
   - *Frequency:* 10 Hz to 500 Hz per connection.
   - *Cost:* Each frame header reads 2–10 bytes via individual single-byte `input.read()` stream calls; each received payload <= 32 KB checks out a pooled buffer only to immediately discard it via `pooled.copyOf(length)`. Channel backpressure busy-sleeps via `Thread.sleep(25)` on worker threads.

3. **Shared Keepalive Tick and Liveness Evaluation (Single Global Coroutine on `Dispatchers.IO`)**
   - *Path:* `WsKeepaliveTicker` ticker loop -> `tickTarget.tick()` -> `WsConnection.keepaliveTick` -> `WsKeepalive.onTick` -> `send(OPCODE_PING)` or `Quiet`.
   - *Frequency:* Periodic: every 30 s (ECO), 10–25 s (STANDARD based on hardware tier), or 5 s (BOOST).
   - *Cost:* Excellent single-timer design. One coroutine wakes up all registered connections simultaneously, avoiding N distinct alarm wake-ups.

4. **Peer Reconnect & TLS Upgrade Sweep (`Dispatchers.IO`)**
   - *Path:* `WsFlashNetwork.reconnectJobs` / `onUsableNetwork` -> `WsTransferClient.connect` -> `SecureSocketUpgrader.wrapClient` -> `FlashTlsContextFactory.clientContext` -> `startHandshake()`.
   - *Frequency:* On Wi-Fi network regain, process unfreeze, or unexpected socket drop.
   - *Cost:* Recreates a fresh `SSLContext` on every dial, wiping TLS session resumption cache. In a 20-member mesh, triggers up to 19 concurrent full asymmetric TLS handshakes.

---

## 3. Checklist Table

| # | Item | Status | Finding / Evidence / Reason |
|---|---|---|---|
| 1 | Session lifecycle cost: TLS handshake count per reconnect, TLS session resumption, 24-session ceiling enforcement (ADR-057) | **Problem** | Finding **OPT-O09-02**. Every reconnect creates a fresh `SSLContext`, completely breaking TLS session caching/resumption and forcing full ECDHE+ECDSA handshakes across up to 19 mesh peers. Session ceiling (24) is properly enforced in `registerSession` under `registryLock`. |
| 2 | Keep-alive / ping intervals vs battery and airtime across modes: ECO (30s), STANDARD (10–25s), BOOST (5s) | **No problem** | Shipped numbers in `ConnectionModePolicy.kt:92-99` and `FlashTransportProfile.kt:66-130`: ECO uses 30 s ping (75 s liveness); STANDARD uses 10 s (HIGH), 12 s (MEDIUM), 15 s (LOW), 25 s (ULTRA_LOW); BOOST uses 5 s ping (15 s liveness). Single shared `WsKeepaliveTicker` wakes up all sessions on one tick; single-pinger negotiation (`PingRole.PINGER`/`ANSWERER`) cuts airtime in half. |
| 3 | Send queue design: BoundedSendQueue status (is it wired or bypassed?), socket write blocking behavior | **Problem** | Finding **OPT-O09-01**. `BoundedSendQueue` is completely bypassed. `WsConnection.send()` blocks synchronously on `writeLock.lock()` and raw `OutputStream.write()`. Stalled TCP sends block caller coroutines and connection close. |
| 4 | synchronized(writeLock) / mutex contention on socket writes across multiplexed streams (chat + chunks + calling) | **Problem** | Finding **OPT-O09-01**. Global `ReentrantLock writeLock` serializes all frames. When a 64 KiB–1 MiB chunk blocks in TCP send buffer, concurrent chat, keepalive PINGs, delivery receipts, and WebRTC signaling frames are stalled behind it. |
| 5 | Frame header overhead and text vs binary framing | **Problem** | Findings **OPT-O09-04**, **OPT-O09-05**, **OPT-O09-07**. RFC 6455 header adds 2–14 bytes. Small interactive frames (receipts, pings, reactions) use verbose ASCII text key-value framing (`FLASH_MSG`, `FLASH_RCPT`) instead of compact binary framing. Encrypted frames are sent as TEXT frames via Base64. |
| 6 | JSON encode/decode per message: memory allocation per frame, reflection vs generated serializers, repeated Json {} construction, String round-trips of binary data via Base64 | **Problem** | Findings **OPT-O09-03** & **OPT-O09-04**. `RelayFrameCodec.decodeResult` performs a two-pass parse (`parseToJsonElement` AST tree + `decodeFromJsonElement`). Encrypted `FLASH_SEC` frames convert binary ciphertext to Base64 String (+33% wire bloat). Serializers are compile-time generated and `HubJson` is a singleton (no repeated `Json {}` instantiation). |
| 7 | Compression status: is permessage-deflate enabled or absent? Bandwidth implications on text/receipt frames | **Already Fine / Documented** | `permessage-deflate` is absent by design (`WebSocketCodec.kt:21`). For small receipt/control frames (180 B), DEFLATE with sync-flush offers poor compression without context takeover, and context takeover costs 64 KB memory per connection (1.5 MB for 24 sessions) plus CPU overhead. Encrypted ciphertext (`FLASH_SEC`) cannot be compressed. Binary framing is far superior to DEFLATE for this workload. |
| 8 | Per-message overhead for typing / receipt / presence frames: byte size on wire, frequency, typing indicator rate limiting | **Problem** (Framing) / **No problem** (Rate Limiting) | Finding **OPT-O09-07**. Wire size: Delivery receipts = 181 B, Read receipts = 180 B, Typing frames = 160–200 B. Rate limiting: UI typing indicator (`FlashConversationScreen.kt:918-923`) fires strictly on state transitions (empty -> non-empty -> empty), never on keystrokes. |
| 9 | Reconnect storm behavior and backoff: exponential backoff, jitter, thundering herd on network reconnect | **No problem** | `ReconnectStagger.kt` detects storms (>= 4 drops in 3 s) and deterministically offsets the first redial attempt across peers (`hash(local, peer) mod 2000ms`). `ReconnectPolicy.kt` implements AWS full jitter with a hard floor (`baseMs + random01 * (bound - baseMs)`). Network watcher collapses pending backoff cleanly. |
| 10 | Dial budgets in crowds, connection planner churn, duplicate session / glare resolution cost | **No problem** | `DialBudget.kt` enforces ADR-057 (headroom 4, budget 20) prioritizing busy/call peers, then contacts, then strangers. `ConnectionPlanner.kt` uses deterministic first-dialer delay (`firstContactDeferMs = 1200ms`). Glare is resolved deterministically by comparing session originator IDs (`WsFlashNetwork.kt:763-788`). |
| 11 | WsFlashNetwork event fan-out: listeners, SharedFlow replay buffers, channel capacities | **Problem** | Findings **OPT-O09-06** & **OPT-O09-08**. `WsSession` generates an uncollected `UUID.randomUUID()` on every sent frame into `_frameAcks` (an uncollected SharedFlow). Inbound channel backpressure uses `Thread.sleep(25)` busy-sleep loop on `Dispatchers.IO`. |
| 12 | Quantify bytes per chat message end-to-end: for a 20-character text message, calculate exact wire bytes exchanged | **Evaluated** | Exact arithmetic breakdown in Section 6: Delivering a 20-byte text message generates **609 bytes of application payloads**, **633 WebSocket bytes**, **699 TLS bytes**, and **819 TCP/IP wire bytes** (~939 bytes including TCP ACKs). That is a **47x expansion ratio**. |

---

## 4. Detailed Optimization Findings

### OPT-O09-01 — BoundedSendQueue Bypassed: Unbuffered Blocking Socket Writes Under Global writeLock
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, J, C, B, N   **Runtime:** Android, Desktop
- **Component:**  
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt:335-357`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt:335-357`
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueue.kt:30-80`
- **Bottleneck class (transfer/media only):** network
- **What is wasteful:**  
  The project contains a fully tested, hardened queue implementation (`BoundedSendQueue`) specifically designed to decouple socket writes from calling coroutines, absorb traffic bursts, apply fail-fast reject-newest backpressure, and prevent head-of-line blocking. However, `BoundedSendQueue` is **completely bypassed** in production code.  
  Instead, `WsConnection.send()` directly acquires a single synchronous `ReentrantLock` (`writeLock.lock()`) and synchronously writes raw frame bytes to `socket.getOutputStream()`. When multiple multiplexed streams share the same WebSocket connection (such as high-throughput 64 KiB chunk file transfers, WebRTC signaling via `FLASH_CALL`, chat messages, read/delivery receipts, and keepalive PINGs):
  1. A bulk chunk write stalls on the TCP socket output buffer when the network or receiving disk slows down.
  2. While stalled, `writeLock` is held continuously.
  3. All interactive chat messages, delivery receipts, and latency-critical call signaling frames block waiting for `writeLock`.
  4. Keepalive PING frames cannot be transmitted; the remote peer's watchdog observes silence and may falsely tear down the connection.
  5. Connection closure (`close()`) attempts `writeLock.tryLock(200, TimeUnit.MILLISECONDS)`. If a large chunk write is stalled in the TCP buffer, `close()` fails to send a clean `OPCODE_CLOSE` frame.
- **Cost model:**  
  - *Frequency:* 10 Hz to 500 Hz during active file transfers; 1–10 Hz during active chat.
  - *Unit cost:* 15 ms to 2,000 ms blocking latency per blocked control frame when TCP send window saturates.
  - *Multiplier:* Multiplied across all multiplexed channels (chat, chunks, calls) sharing the connection.
- **User-visible effect:**  
  During file transfers, chat messages fail to send or show prolonged spinning progress; voice/video call signaling packets drop or arrive seconds late; active connections flap due to delayed keepalive pings.
- **Expected gain:**  
  Decoupling callers via an asynchronous sender loop and `BoundedSendQueue` eliminates synchronous thread blocking on `writeLock`, enables priority multiplexing (control/signaling frames leapfrog bulk chunk frames), and prevents transfer backpressure from stalling interactive messaging.
- **Proposed change:**  
  Wire `BoundedSendQueue` into `WsConnection`. Run a dedicated coroutine consumer that pulls frames from the queue and writes them to the socket `OutputStream`. Provide two queues or priority levels: high-priority (signaling, receipts, keepalive pings) and normal-priority (file transfer chunks).
- **Invariants touched (5.3):** Invariant 2 (Durability & Ordering: chat outbox retains rejected writes; FIFO ordering maintained per stream); Invariant 3 (Streaming large files: backpressure propagated without dropping frames).
- **Risk / effort:** Medium risk; M effort (adjusting WsConnection write pipeline, verifying KMP compatibility).
- **Prove it:** Run a 100 MB file transfer while concurrently sending 10 chat messages and WebRTC signaling frames. Measure message latency under transfer saturation: currently >500 ms (or blocked); with decoupled queue < 15 ms.
- **Verifier result:** Survived. Confirmed by `docs/audit/2026-09-28-architectural-audit-and-tasks.md:237`: *"send() and close() both hold writeLock around a blocking writeFrame... BoundedSendQueue is unused."*
- **Related:** TASK-CORE-NET-2, ADR-016, EXP-001.

---

### OPT-O09-02 — Missing TLS Session Resumption & Repeated SSLContext Recreation on Every Connection
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, C, B, N   **Runtime:** Android, Desktop
- **Component:**  
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/tls/FlashTlsContextFactory.kt:47-97`
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/tls/SecureSocketUpgrader.kt:126,204`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/tls/SecureSocketUpgrader.kt:126,204`
- **Bottleneck class (transfer/media only):** TLS
- **What is wasteful:**  
  In `FlashTlsContextFactory.kt`, `clientContext` and `serverContext` both call `createContext()`, which executes:
  ```kotlin
  val context = SSLContext.getInstance("TLS")
  context.init(keyManagers, arrayOf<javax.net.ssl.TrustManager>(trustManager), SecureRandom())
  return context
  ```
  `SecureSocketUpgrader.wrapClient` and `wrapAccepted` invoke this on **every single connection and reconnect attempt**.  
  In Java / JSSE / Conscrypt:
  1. The TLS session cache (`SSLSessionContext`) lives within the `SSLContext` instance.
  2. Because a new `SSLContext` is created for every dial and every accepted socket, the session cache is always empty.
  3. TLS session resumption (via session IDs or RFC 5077 / RFC 8446 session tickets) is **100% impossible**.
  4. Every single reconnect—such as when waking from Android Doze, switching between Wi-Fi access points, or recovering from a temporary signal fade—must execute a full asymmetric TLS handshake from scratch (generating ephemeral ECDH keys, transmitting full certificate chains, and computing ECDSA signatures).
  5. In a 20-member mesh (ADR-057), a network rejoin causes up to 19 peers to reconnect simultaneously. Each peer performs 19 full TLS handshakes, burning significant CPU and airtime.
- **Cost model:**  
  - *Frequency:* On cold start, Wi-Fi rejoin, or mesh reconnect (up to 19 outbound/inbound sessions).
  - *Unit cost:* 15–40 ms CPU/crypto time + 2 network round trips + ~3,000 bytes of handshake frames per connection.
  - *Multiplier:* 19 peers in a mesh = **285 ms – 760 ms CPU time and ~57 KB uncompressed handshake bytes** per device on every Wi-Fi transition.
- **User-visible effect:**  
  Sluggish reconnection after phone wake-up or Wi-Fi roaming; elevated battery consumption and thermal dissipation during mesh reconnect storms; delayed message delivery upon returning to the app.
- **Expected gain:**  
  Reusing a singleton `SSLContext` across connections enables TLS 1.3 / 1.2 session resumption. Resumed handshakes reduce round trips from 2 to 1 (or 0-RTT), eliminate asymmetric ECDSA signing and verification, save ~2 KB per handshake, and cut reconnect CPU latency by **>75%**.
- **Proposed change:**  
  Maintain shared client and server `SSLContext` singletons (re-initialized only when identity keys or root trust change). Inject a shared `TofuX509TrustManager` that performs per-connection peer verification via TLS socket callbacks or `SSLEngine`/`HandshakeCompletedListener`.
- **Invariants touched (5.3):** Invariant 1 (Security: Pin verification and TOFU guarantees must remain strictly enforced across resumed sessions).
- **Risk / effort:** Low risk; M effort (refactoring SSLContext lifecycle).
- **Prove it:** Benchmark handshake duration on Android handset: Full handshake (~32 ms) vs Resumed handshake (~4 ms).
- **Verifier result:** Survived. Verified at `FlashTlsContextFactory.kt:93-97` where `SSLContext.getInstance("TLS")` is called unconditionally on every connection.
- **Related:** ADR-057, ERROR-070.

---

### OPT-O09-03 — Double JSON Tree Parsing & Intermediate AST Allocation in RelayFrameCodec
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M, T   **Runtime:** Android, Desktop, Server
- **Component:**  
  - `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/relay/RelayFrameCodec.kt:57-74`
- **Bottleneck class (transfer/media only):** protocol
- **What is wasteful:**  
  In `RelayFrameCodec.decodeResult(text, maxBytes)`:
  ```kotlin
  val element = HubJson.frames.parseToJsonElement(text)
  val obj = element as? JsonObject ?: return Decoded.Rejected(RejectReason.MALFORMED)
  val type = (obj["t"] as? JsonPrimitive)?.takeIf { it.isString }?.content
  if (type == null || type !in KNOWN_TYPES) return Decoded.Rejected(RejectReason.UNKNOWN_TYPE)
  val frame = HubJson.frames.decodeFromJsonElement(RelayFrame.serializer(), obj)
  ```
  This implementation executes a **two-pass parse**:
  1. It parses the entire JSON text into an intermediate `JsonElement` AST tree (allocating `JsonObject`, `JsonPrimitive`, and Map entries for every field in the frame).
  2. It inspects the `"t"` discriminator.
  3. It then re-traverses the allocated AST tree via `decodeFromJsonElement` to produce the actual `RelayFrame` instance.  
  `RelayFrame` already has `@Serializable` with `@SerialName` annotations on each subclass and `classDiscriminator = "t"` configured in `HubJson.frames`. `kotlinx.serialization` can deserialize directly from the JSON string in a single streaming pass (`HubJson.frames.decodeFromString(RelayFrame.serializer(), text)`).
- **Cost model:**  
  - *Frequency:* 1 to 50 Hz per client connected to the Hub or relay server.
  - *Unit cost:* ~1–4 KB of temporary `JsonElement` AST allocations and double CPU parsing time per frame.
  - *Multiplier:* Every message, delivery receipt, ack, and presence frame sent through the Hub relay.
- **User-visible effect:**  
  Garbage collection churn and increased memory pressure on both mobile clients and the hub server under high message throughput.
- **Expected gain:**  
  Replacing two-pass AST decoding with direct streaming deserialization eliminates 100% of intermediate `JsonElement`/`JsonObject` allocations and cuts decoding CPU time by **40%–50%**.
- **Proposed change:**  
  Directly call `HubJson.frames.decodeFromString(RelayFrame.serializer(), text)` inside a `try-catch (SerializationException)`. Let `kotlinx.serialization`'s polymorphic discriminator handle the `"t"` dispatch in a single streaming pass.
- **Invariants touched (5.3):** Invariant 1 (Wire compatibility: exact same JSON wire structure preserved).
- **Risk / effort:** Low risk; S effort (simplifying decode logic).
- **Prove it:** JMH benchmark of `decodeResult` with single-pass `decodeFromString` vs two-pass `parseToJsonElement` + `decodeFromJsonElement`. Expect ~2x throughput gain.
- **Verifier result:** Survived. Confirmed in `RelayFrameCodec.kt:57-74`.
- **Related:** SEC-15, ADR-109.

---

### OPT-O09-04 — Excessive Base64 Inflation and Triple String-Escape Roundtrips for E2E Encrypted Frames
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, M, C, T   **Runtime:** Android, Desktop
- **Component:**  
  - `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt:104-120`
  - `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/protocol/FlashTextFraming.kt:16-57`
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt:57-109`
- **Bottleneck class (transfer/media only):** protocol
- **What is wasteful:**  
  When an end-to-end encrypted chat message is transmitted, it traverses an extraordinary chain of string-to-binary-to-string conversions:
  1. Plaintext frame fields are escaped via `FlashTextFraming.escape` (allocating new strings for spaces, `=`, and `%`) and concatenated into an ASCII string.
  2. The string is encoded to UTF-8 bytes (`encodeToByteArray()`).
  3. AES-256-GCM encrypts the bytes into ciphertext + auth tag.
  4. The encrypted binary payload is **converted to a Base64 string** (`Base64.encode(encryptedBytes)`), inflating the wire payload by **33.3%**.
  5. The Base64 string is wrapped in a `FLASH_SEC payload=<base64>` text line.
  6. In `WsConnection.sendText`, that string is encoded back into a UTF-8 `ByteArray`.
  7. It is transmitted as a WebSocket **TEXT frame** (`OPCODE_TEXT = 0x1`).
  8. On receive: WebSocket reads UTF-8 string -> splits fields -> extracts Base64 -> decodes Base64 to `ByteArray` -> decrypts AES-GCM -> decodes UTF-8 string -> splits plaintext fields -> parses values.
  Transmitting encrypted binary ciphertext through WebSocket text framing via Base64 wastes **33% extra wire bandwidth** and causes 8–12 intermediate String/ByteArray allocations per frame.
- **Cost model:**  
  - *Frequency:* Every direct message, reaction, and receipt.
  - *Unit cost:* +33% wire size expansion + ~2 KB temporary allocations per frame.
  - *Multiplier:* Multiplied across all end-to-end encrypted direct chat frames.
- **User-visible effect:**  
  Increased mobile data/airtime consumption, unnecessary battery drain, and elevated GC overhead on low-memory devices during chat sync.
- **Expected gain:**  
  Transmitting encrypted payloads directly as WebSocket **BINARY frames** (`OPCODE_BINARY = 0x2`) with a 1-byte subtype header completely eliminates Base64 encoding/decoding, saving **25% of wire bytes** (the 33% inflation) and eliminating 6 intermediate String/ByteArray allocations per frame.
- **Proposed change:**  
  Define a binary envelope for encrypted frames: `[1-byte subtype (0x01 = SEC_FRAME) | 12-byte nonce | ciphertext + tag]`. Send directly via `sendBinaryConsuming(bytes)`. Skip Base64 and text framing on the encrypted wire path.
- **Invariants touched (5.3):** Invariant 1 (Wire compatibility: requires protocol versioning / capability negotiation `caps=bin-sec` before switching from text `FLASH_SEC`).
- **Risk / effort:** Medium risk; M effort (versioned capability negotiation).
- **Prove it:** Compare wire byte size for 1,000 chat messages: Base64 text framing vs binary framing. Expect ~25% wire size reduction and 50% fewer allocations.
- **Verifier result:** Survived. Confirmed in `E2eFrameCodec.kt:104-120`.
- **Related:** ADR-016, EXP-001.

---

### OPT-O09-05 — Allocation Churn & Immediate ByteArrayPool Copy Discard in WebSocketCodec
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M, C, J   **Runtime:** Android, Desktop
- **Component:**  
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:111,132,137,363-375,404-408`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt:111,132,137,363-375,404-408`
- **Bottleneck class (transfer/media only):** protocol
- **What is wasteful:**  
  `WebSocketCodec` exhibits multiple inefficient allocation and I/O patterns on the hot path:
  1. **Immediate `ByteArrayPool` Copy Discard on Receive:** In `readFramePayload` (line 367–375), payloads <= 32 KB check out a buffer from `ByteArrayPool.use(length)`, read into it, unmask it, and then call:
     ```kotlin
     pooled.copyOf(length)
     ```
     The copy is immediately allocated on the heap, and the pooled buffer is returned to the pool! The pool therefore achieves **zero reduction in heap allocations** for every frame <= 32 KB (which includes all text messages, receipts, typing indicators, and small chunks).
  2. **Double Header Array Allocation on Send:** In `writeFrame` (line 111–132), it allocates a `ByteArrayOutputStream(14)` and calls `output.write(header.toByteArray())`, allocating two separate objects just to write 2 to 14 header bytes.
  3. **Unnecessary Payload Duplication on Client Masking:** For non-consumed payloads (all text, receipts, signaling), it calls `ByteArray(payload.size) { payload[it] }` before masking, duplicating the entire payload array.
  4. **Single-Byte Virtual Method Calls for Header Parsing:** `readFrameHeader` reads each byte of the frame header via `readByte(input)` -> `input.read()`, executing 2 to 10 individual method calls and native JNI hops per frame instead of reading the 2–10 byte header in a single read.
- **Cost model:**  
  - *Frequency:* 10 Hz to 500 Hz on all active WebSocket traffic.
  - *Unit cost:* 3–5 temporary heap allocations + 2–10 single-byte stream reads per frame.
  - *Multiplier:* Across all frames received and sent across up to 24 concurrent sessions.
- **User-visible effect:**  
  GC pauses and frame drops on budget handsets (e.g. BelFone SCP810) during active file transfer or high-frequency messaging.
- **Expected gain:**  
  Using a fixed stack/reusable 14-byte buffer for frame headers, reading headers with a single read call, and avoiding redundant array copies saves **3 to 4 allocations per frame** and eliminates JNI boundary crossing overhead.
- **Proposed change:**  
  (1) Replace `ByteArrayOutputStream(14)` with a preallocated thread-local or stack-allocated 14-byte array `ByteArray(14)` in `writeFrame`. (2) Read frame headers into a small 14-byte stack buffer with a single `input.read(headerBuf, 0, 2)` call. (3) In `readFramePayload`, for messages consumed immediately or decoded to String, decode directly from the pooled buffer before releasing it, or pass the pooled slice downstream.
- **Invariants touched (5.3):** None. Internal codec implementation detail.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Run allocation profiler on 10,000 WebSocket frames. Verify that header writing and reading generate zero garbage allocations.
- **Verifier result:** Survived. Verified at `WebSocketCodec.kt:111,132,374`.
- **Related:** EXP-001, ADR-016.

---

### OPT-O09-06 — Synchronous Thread.sleep(25) Busy-Poll in WsSession Inbound Channel Backpressure
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, B, T, J   **Runtime:** Android, Desktop
- **Component:**  
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt:115-149`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt:115-149`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:**  
  In `WsSession.kt`, when inbound frames arrive faster than the consumer can process them:
  ```kotlin
  public fun onTextReceived(text: String) {
      while (_connectionState.value != FlashConnectionState.Disconnected) {
          val result = textChannel.trySend(text)
          if (result.isSuccess || result.isClosed) return
          connection.touchLiveness()
          try {
              Thread.sleep(25)
          } catch (_: InterruptedException) {
              Thread.currentThread().interrupt()
              return
          }
      }
  }
  ```
  `onBinaryReceived` uses the identical pattern.  
  Because `WsConnection.Listener` was declared with synchronous callbacks (`onTextMessage`, `onBinaryMessage`) rather than suspending functions, `WsSession` cannot call the non-blocking suspending `channel.send(item)`. Instead, it executes a **synchronous busy-poll loop with `Thread.sleep(25)`**.  
  This has severe drawbacks:
  1. The underlying thread on `Dispatchers.IO` is held and blocked in sleep, preventing the thread pool from performing useful work.
  2. The thread repeatedly wakes up 40 times per second per stalled session, burning CPU and battery.
  3. Backpressure is jerky rather than smooth: frame ingestion is delayed in coarse 25 ms increments.
- **Cost model:**  
  - *Frequency:* Whenever disk write queues or Room database writes back up (e.g. bulk chunk writes or batch message sync).
  - *Unit cost:* 25 ms thread stall + 40 Hz CPU wakeups per blocked session.
  - *Multiplier:* Multiplied across all active sessions experiencing backpressure.
- **User-visible effect:**  
  Thread pool exhaustion on `Dispatchers.IO`; increased battery drain and delayed UI updates when the app is processing incoming transfers or message sync in the background.
- **Expected gain:**  
  Changing the listener callback to `suspend fun` and calling `channel.send(item)` allows the coroutine to suspend cooperatively without holding or sleeping any OS thread, saving CPU cycles and enabling instant wakeup when buffer space becomes available.
- **Proposed change:**  
  Update `WsConnection.Listener` methods to `suspend fun onTextMessage` and `suspend fun onBinaryMessage`. In `WsSession`, replace `trySend` + `Thread.sleep(25)` with `channel.send(item)`.
- **Invariants touched (5.3):** Invariant 3 (Streaming large files: backpressure is preserved and suspended cleanly).
- **Risk / effort:** Low risk; S effort (converting callback to suspend).
- **Prove it:** Simulate a stalled consumer. Observe thread state in visualvm/Android Studio profiler: threads transition from `TIMED_WAITING` spin to suspended coroutine state with 0% CPU consumption.
- **Verifier result:** Survived. Confirmed in `WsSession.kt:115-149`.
- **Related:** BUG-03, ADR-016.

---

### OPT-O09-07 — High Overhead of Text-Line Protocol Framing for Small Interactive Frames
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, B, C   **Runtime:** Android, Desktop
- **Component:**  
  - `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/protocol/FlashTextFraming.kt:30-57`
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/ChatTextFrameCodec.kt:57-109`
- **Bottleneck class (transfer/media only):** protocol
- **What is wasteful:**  
  Direct chat frames (`FLASH_MSG`, `FLASH_RCPT`, `FLASH_READ`, `FLASH_REACT`, `FLASH_TYPING`) use an ASCII key-value text framing format where every field key is spelled out on every frame:
  - Delivery receipt (`FLASH_RCPT`): `FLASH_RCPT messageId=<uuid> conversationId=<uuid> memberId=<uuid> deliveredAt=<timestamp>` -> **181 bytes**.
  - Read receipt (`FLASH_READ`): `FLASH_READ conversationId=<uuid> memberId=<uuid> upToMessageId=<uuid> readAt=<timestamp>` -> **180 bytes**.
  - Reaction (`FLASH_REACT`): `FLASH_REACT messageId=<uuid> conversationId=<uuid> memberId=<uuid> emoji=<emoji> isAdded=<bool>` -> **160–170 bytes**.
  - Typing indicator (`FLASH_TYPING`): `FLASH_TYPING conversationId=<uuid> memberId=<uuid> memberName=<name> isTyping=<bool> timestampMs=<timestamp>` -> **170–200 bytes**.
  For delivery and read receipts, which convey only a UUID, timestamp, and status, carrying 180 bytes of text payload (which expands to 250 bytes at the TCP/IP level) is an 80%+ waste of wire bytes. Binary framing (e.g. fixed 1-byte opcode, 16-byte binary UUIDs, 8-byte timestamp) would convey the exact same data in **33 to 41 bytes** (an **80% reduction** in wire bytes).
- **Cost model:**  
  - *Frequency:* Every incoming and outgoing message triggers delivery and read receipts; group chats multiply this by member count.
  - *Unit cost:* ~140 redundant ASCII bytes per receipt frame.
  - *Multiplier:* In a group of 20 members, one message generates 19 delivery receipts + 19 read receipts = 38 receipts x 140 B = **5.3 KB of pure text framing overhead** per message.
- **User-visible effect:**  
  High airtime and battery consumption during active group chats on mobile radios.
- **Expected gain:**  
  Adopting binary framing for receipts and signaling reduces wire bytes by **~80%** and avoids all string parsing, substring splitting, and unescaping allocations.
- **Proposed change:**  
  Introduce versioned binary framing for high-frequency control messages (`OPCODE_BINARY` with 1-byte header tags for `RCPT`, `READ`, `TYPING`). Keep text framing for backwards compatibility via capability negotiation.
- **Invariants touched (5.3):** Invariant 1 (Wire compatibility: negotiate via HELLO `caps` before switching).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Arithmetic verification: Binary receipt (1B opcode + 16B messageId UUID + 16B conversationId UUID + 8B timestamp = 41 bytes) vs Text receipt (181 bytes) -> 77.3% byte reduction.
- **Verifier result:** Survived. Confirmed in `ChatTextFrameCodec.kt:71-88`.
- **Related:** ADR-025, ADR-044.

---

### OPT-O09-08 — Unconditional UUID.randomUUID() Generation for Uncollected frameAcks Flow
- **Impact rank:** Low-Medium   **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M, T   **Runtime:** Android, Desktop
- **Component:**  
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt:91,101`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsSession.kt:95,105`
- **Bottleneck class (transfer/media only):** CPU
- **What is wasteful:**  
  In `WsSession.kt`:
  ```kotlin
  override suspend fun send(message: ByteArray): FlashResult<Unit> {
      val ok = connection.sendBinary(message)
      return if (ok) {
          _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
          FlashResult.Success(Unit)
      } ...
  }
  ```
  And identically in `sendText()`.  
  On **every single frame sent** across every `WsSession`:
  1. `UUID.randomUUID()` is called. Under the hood, this queries `SecureRandom` system entropy, which acquires an internal synchronization lock and generates 16 secure random bytes.
  2. It formats the UUID into a 36-character string.
  3. It allocates a new `FrameAck` object.
  4. It calls `_frameAcks.tryEmit()`.  
  `_frameAcks` has **zero collectors in production code** (verified by repo-wide search; it is only collected in isolated test harnesses).  
  During a 1 GB file transfer (16,384 chunks):
  - **16,384 `SecureRandom` calls** are executed.
  - **16,384 UUID strings** and **16,384 `FrameAck` objects** are allocated on the critical I/O path.
  - All emitted objects are immediately dropped because `_frameAcks.subscriptionCount.value == 0`.
- **Cost model:**  
  - *Frequency:* 1 per file chunk (thousands of Hz during file transfer) and 1 per text frame.
  - *Unit cost:* ~1.5 µs CPU time (SecureRandom entropy) + 2 heap allocations per sent frame.
  - *Multiplier:* 16,384 times per 1 GB transfer.
- **User-visible effect:**  
  Unnecessary CPU overhead and minor throughput throttling during high-speed multi-megabyte/gigabyte LAN transfers.
- **Expected gain:**  
  Guarding `tryEmit` with `if (_frameAcks.subscriptionCount.value > 0)` completely eliminates all `UUID.randomUUID()` calls and object allocations during production file transfers.
- **Proposed change:**  
  ```kotlin
  if (_frameAcks.subscriptionCount.value > 0) {
      _frameAcks.tryEmit(FrameAck(UUID.randomUUID().toString(), FrameAckStage.SocketWritten, System.currentTimeMillis()))
  }
  ```
- **Invariants touched (5.3):** None. Test harness behavior preserved when subscribed.
- **Risk / effort:** Low risk; S effort (one-line check).
- **Prove it:** Profile chunk send loop: verify that `UUID.randomUUID` no longer appears in CPU flame graphs during file transfers.
- **Verifier result:** Survived. Confirmed in `WsSession.kt:91,101`.
- **Related:** ADR-016, EXP-001.

---

## 5. "Already Fine" / "No Problem, Because..." Notes

1. **Keep-alive Ping Intervals & Shared Ticker Across Modes (Checklist #2):**
   - *Status:* **No problem.**
   - *Reason:* The keepalive subsystem was thoroughly overhauled in PC1 and PC5. The intervals in `ConnectionModePolicy.kt:92-99` and `FlashTransportProfile.kt` are well-calibrated:
     - ECO uses 30 s ping and 75 s liveness.
     - STANDARD uses 10 s (HIGH), 12 s (MEDIUM), 15 s (LOW), 25 s (ULTRA_LOW) based on device hardware capabilities.
     - BOOST uses 5 s ping and 15 s liveness.
     Most importantly, `WsKeepaliveTicker` unifies all connections onto a single periodic clock, so N sessions trigger only one timer wakeup per period rather than N staggered wakeups. In addition, `WsKeepalive.resolveRole` negotiates a single pinger per pair (`PingRole.PINGER` vs `ANSWERER`), cutting idle keepalive airtime across the mesh by 50%.

2. **Permessage-Deflate Compression (Checklist #7):**
   - *Status:* **Already Fine / Documented Choice.**
   - *Reason:* `WebSocketCodec.kt:21` intentionally omits `permessage-deflate`. While compression might seem desirable, evaluating the actual workload reveals that omitting it is optimal:
     - Direct messages and receipts are small (160–250 B). Without context takeover, DEFLATE adds header overhead and often expands small packets.
     - With context takeover, each connection must maintain a 32 KB sliding window buffer for inflation and deflation (64 KB per connection; 1.5 MB total for 24 sessions), plus constant CPU compression work on every frame.
     - All user data and signaling is end-to-end encrypted (`FLASH_SEC`), resulting in high-entropy ciphertext that **cannot be compressed** by DEFLATE.
     - Binary framing (OPT-O09-04 and OPT-O09-07) achieves far greater wire byte savings (>75%) with zero CPU or memory compression cost.

3. **Typing Indicator Rate Limiting (Checklist #8):**
   - *Status:* **No problem.**
   - *Reason:* The chat composer (`FlashConversationScreen.kt:918-923`) does not transmit typing indicators on keystrokes. Instead, it tracks `isTypingSignalled` and emits a typing frame only on **state transitions**:
     - When the draft transitions from blank to non-blank (`isTyping = true`).
     - When the draft is cleared or the message is sent (`isTyping = false`).
     The receiving side (`RealFlashChatRepository.kt:6072`) sets a local timer to auto-expire the typing indicator. This design keeps the wire quiet and prevents keystroke typing storms.

4. **Reconnect Storms, Backoff, and Jitter (Checklist #9):**
   - *Status:* **No problem.**
   - *Reason:* Thundering herd protection is robustly implemented:
     - `ReconnectStagger.kt` monitors session drops; when >= 4 drops occur within 3 seconds, it activates storm mode for 30 seconds. In storm mode, the first redial attempt to each peer is deterministically staggered by `hash(localDeviceId, peerDeviceId) mod 2000ms`, spreading handshakes across a 2-second window.
     - `ReconnectPolicy.kt` uses AWS full jitter with a hard floor (`baseMs + random01 * (bound - baseMs)`), preventing synchronized retry waves.
     - Network watcher reconnects are decoupled from discovery sweeps.

5. **Dial Budgets, Connection Planner Churn, and Glare Resolution (Checklist #10):**
   - *Status:* **No problem.**
   - *Reason:* 
     - `DialBudget.kt` implements ADR-057: STANDARD and BOOST dial everyone when total devices <= 20 (`DIAL_BUDGET`). Above 20, slots are allocated strictly to active/busy peers, then contacts, then strangers.
     - `ConnectionPlanner.kt:27-33` uses a deterministic first-dialer delay (`firstContactDeferMs = 1200ms`): the device with the lower ID dials immediately, while the higher ID waits, avoiding connect glare in the common case.
     - When glare does occur (both devices dial simultaneously), `WsFlashNetwork.resolveGlareTie` deterministically breaks the tie using `originator id` (lexicographical comparison), ensuring both sides converge on the exact same TCP socket pair without churning.

6. **24-Session Ceiling Enforcement (Checklist #1):**
   - *Status:* **No problem.**
   - *Reason:* The 24-session ceiling decided in ADR-057 is strictly enforced under `synchronized(registryLock)` in `WsFlashNetwork.registerSession:667` and `JvmWsFlashNetwork:547`. If `!isReplacement && !hardeningPolicy.canAcceptSession(sessionsById.size)`, the candidate session is rejected immediately, bounding socket descriptors and thread pool usage.

7. **JSON Serializer Architecture (Checklist #6):**
   - *Status:* **No problem.**
   - *Reason:* `HubJson` defines singleton `Json` configurations (`HubJson.frames` and `HubJson.rest`), avoiding repeated runtime instantiation. All DTOs use compile-time generated `kotlinx.serialization` serializers without runtime reflection.

---

## 6. End-to-End Chat Wire Byte Quantification

We calculate the exact wire bytes exchanged when Device A sends a 20-character text message to Device B over a direct WebSocket connection with TLS 1.3 encryption on an IPv4 LAN:

### 1. Message Text Payload
- Message: `"Hello, how are you?"` (20 ASCII characters, 3 spaces).
- `FlashTextFraming.escape` converts `' '` to `"%20"`, yielding `"Hello,%20how%20are%20you?"` (26 bytes).
- Standard fields encoded by `ChatTextFrameCodec.encode`:
  - `MSG_PREFIX` = `"FLASH_MSG"` (9 B)
  - `localId=<36-char uuid>` (44 B)
  - `conversationId=<36-char uuid>` (51 B)
  - `senderId=<36-char uuid>` (45 B)
  - `senderName=Alice` (16 B)
  - `sentAt=1729670000000` (20 B)
  - `text=Hello,%20how%20are%20you?` (31 B)
  - `replyToId=` (10 B)
  - `replyToPreview=` (15 B)
  - Separated by spaces (8 B).
- Total text frame payload: **248 bytes**.
- *WebSocket framing (client-masked):* 1 B (Fin+Opcode) + 3 B (Mask bit + 2-byte length) + 4 B (Mask key) + 248 B = **256 bytes**.
- *TLS 1.3 Record:* 5 B (Header) + 256 B + 1 B (Content type) + 16 B (Auth tag) = **278 bytes**.
- *TCP/IP packet (IPv4):* 20 B (IP) + 20 B (TCP) + 278 B = **318 bytes**.

### 2. Delivery Receipt (`FLASH_RCPT`)
- Sent by Device B to Device A upon message receipt.
- Fields: `FLASH_RCPT messageId=<uuid> conversationId=<uuid> memberId=<uuid> deliveredAt=<timestamp>`.
- Total text payload: **181 bytes**.
- *WebSocket framing:* 1 B + 3 B + 4 B + 181 B = **189 bytes** (185 bytes if server unmasked).
- *TLS 1.3 Record:* 5 B + 189 B + 1 B + 16 B = **211 bytes**.
- *TCP/IP packet (IPv4):* 40 B + 211 B = **251 bytes**.

### 3. Read Receipt (`FLASH_READ`)
- Sent by Device B to Device A when the user opens the conversation.
- Fields: `FLASH_READ conversationId=<uuid> memberId=<uuid> upToMessageId=<uuid> readAt=<timestamp>`.
- Total text payload: **180 bytes**.
- *WebSocket framing:* 1 B + 3 B + 4 B + 180 B = **188 bytes**.
- *TLS 1.3 Record:* 5 B + 188 B + 1 B + 16 B = **210 bytes**.
- *TCP/IP packet (IPv4):* 40 B + 210 B = **250 bytes**.

### 4. TCP Acknowledgments
- Device B sends TCP ACK for message packet: **40 bytes**.
- Device A sends TCP ACK for delivery receipt: **40 bytes**.
- Device A sends TCP ACK for read receipt: **40 bytes**.

### Total Summary Table
| Stage | App Data | Wire Text | WS Frame | TLS 1.3 | IP + TCP | Total Wire Bytes |
|---|---|---|---|---|---|---|
| **Text Message** | 20 B | 248 B | 256 B | 278 B | 318 B | **318 B** |
| **Delivery Receipt** | 0 B | 181 B | 189 B | 211 B | 251 B | **251 B** |
| **Read Receipt** | 0 B | 180 B | 188 B | 210 B | 250 B | **250 B** |
| **TCP ACKs (x3)** | 0 B | 0 B | 0 B | 0 B | 120 B | **120 B** |
| **Grand Total** | **20 B** | **609 B** | **633 B** | **699 B** | **819 B** | **~939 bytes** |

**Conclusion:** Delivering a **20-byte text message** exchanges **609 bytes of application payloads** and **~939 bytes of total wire traffic** (a **47x expansion ratio**). Adopting binary framing for receipts and direct messages would reduce this total to ~250 bytes (>70% overall wire savings).

---

## 7. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk |
|---|---|---|---|---|---|---|---|
| **OPT-O09-01** | BoundedSendQueue Bypassed: Unbuffered Blocking Socket Writes Under Global writeLock | High | T, J, C, B, N | STATIC-ARITHMETIC | Android, Desktop | M | Medium |
| **OPT-O09-02** | Missing TLS Session Resumption & Repeated SSLContext Recreation on Every Connection | High | T, C, B, N | STATIC-ARITHMETIC | Android, Desktop | M | Low |
| **OPT-O09-03** | Double JSON Tree Parsing & Intermediate AST Allocation in RelayFrameCodec | Medium | C, M, T | STATIC-ARITHMETIC | Android, Desktop, Server | S | Low |
| **OPT-O09-04** | Excessive Base64 Inflation and Triple String-Escape Roundtrips for E2E Encrypted Frames | High | N, M, C, T | STATIC-ARITHMETIC | Android, Desktop | M | Medium |
| **OPT-O09-05** | Allocation Churn & Immediate ByteArrayPool Copy Discard in WebSocketCodec | Medium | M, C, J | STATIC-ARITHMETIC | Android, Desktop | S | Low |
| **OPT-O09-06** | Synchronous Thread.sleep(25) Busy-Poll in WsSession Inbound Channel Backpressure | Medium | C, B, T, J | STATIC-ARITHMETIC | Android, Desktop | S | Low |
| **OPT-O09-07** | High Overhead of Text-Line Protocol Framing for Small Interactive Frames | Medium | N, B, C | STATIC-ARITHMETIC | Android, Desktop | M | Medium |
| **OPT-O09-08** | Unconditional UUID.randomUUID() Generation for Uncollected frameAcks Flow | Low-Medium | C, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low |
