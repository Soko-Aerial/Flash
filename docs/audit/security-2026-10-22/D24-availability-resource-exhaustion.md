# Domain D24 — Availability and Resource Exhaustion

## 1. Domain Overview
This domain analyzes denial-of-service (DoS) vulnerabilities, listener capacities, unauthenticated resource consumption, connection limits, body size boundaries, catastrophic regex backtracking (ReDoS), file descriptor leaks, memory leaks, and battery drain vectors across Android, Desktop, and Hub server listeners.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Listener & Surface Inventory

| Listener / Interface | Port / Protocol | Connection Limits | Size Limits | DoS Safeguards |
|---|---|---|---|---|
| P2P Transfer Server (`WsTransferServer`) | TCP 45822 (TLS + WebSocket) | **None** (`SEC-D24-01`) | Pre-handshake: 64 KiB; Post-handshake: 4 MiB | `HANDSHAKE_TIMEOUT_MS = 8000ms`. No max connection cap. |
| LAN Discovery (`MulticastTransport`) | UDP 45823 (Multicast) | Sockets bounded to network interfaces | 1500 bytes (MTU) | `maxPeers` bounds lease table size. |
| Hub REST API (`FlashServerEngine`) | TCP 18443 (Netty HTTPS) | Managed by Netty worker pool | Bounded per route via `receiveBoundedText()` / `receiveBoundedBinary()` | Bounded reads reject oversized payloads with HTTP 413. |
| Hub Relay WebSocket (`RelayFrameRouter`) | TCP 18443 (Netty WSS) | Managed by Netty | `maxFrameSize = 4 MiB` | Ping/pong keepalive timeout. |
| Hub Admin Console (`AdminConsoleWeb`) | TCP 18444 (Netty HTTPS) | Managed by Netty | 64 KiB request body limit | Admin rate limiting and CSRF protection. |
| Radio Link (`:core:radio`) | Serial / RFCOMM | Single hardware device handle | KISS frame limits | Bounded framing buffers. |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Unauthenticated cost per connection | DEFECT | Inbound P2P TCP connections immediately trigger TLS context initialization without capacity gating (`SEC-D24-01`). |
| Connection limits on listeners | DEFECT | `WsTransferServer` has no global or per-IP concurrent connection limit (`SEC-D24-01`). |
| Handshake timeouts | PASS | Handshake timeouts enforced (8s on P2P WebSocket, standard HTTP timeouts on server). |
| Frame and body size limits | PASS | Ktor routes use `receiveBoundedText()` and `receiveBoundedBinary()`; P2P WebSockets enforce 64 KiB pre-handshake / 4 MiB post-handshake. |
| Decompression bomb vulnerability | PASS | No compressed data streams accepted over wire protocol (no GZIP/DEFLATE). |
| Regular expression catastrophic backtracking (ReDoS) | PASS | All regexes use linear, non-nested quantifiers (`REJ-D24-01`). |
| Unbounded memory maps / caches | DEFECT | Directory miss-limiter map leaks memory monotonically (`SEC-D16-02`). |
| Battery drain & keepalive abuse | DEFECT | Multiple unauthenticated discovery announcements trigger separate reconnection loops (`SEC-D24-02`). |

---

## 4. Files Audited
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt`
- `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransport.kt`
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementText.kt`

---

## 5. Findings

### SEC-D24-01 — Unbounded Concurrent Inbound Sockets and Unthrottled Handshake Coroutines in `WsTransferServer` Enables Resource Exhaustion DoS
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A1 (Unauthenticated local network attacker)
- **Component:** `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt:116-165`, `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt:116-165`
- **CWE / standard:** CWE-400: Uncontrolled Resource Consumption; CWE-770: Allocation of Resources Without Limits or Throttling; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H (6.5)
- **What is wrong:** `WsTransferServer.acceptLoop` runs an unbounded `while` loop accepting incoming TCP connections on port 45822. For every accepted socket, it unconditionally adds the socket to `inFlightSockets` and launches a new coroutine on `Dispatchers.IO` to execute the TLS handshake and WebSocket upgrade. There is no maximum concurrent connection ceiling, nor is there any rate-limiting per remote IP. A single attacker on the local Wi-Fi or LAN can open hundreds or thousands of simultaneous TCP connections without completing the handshake. This floods the host with concurrent coroutines, consumes available socket file descriptors, and exhausts native SSL heap memory, rendering the device incapable of accepting legitimate peer connections or transferring files.
- **Impact:** Complete denial of service for peer-to-peer messaging and file transfers on Android and Desktop.
- **Evidence:**
  `WsTransferServer.kt:116-121`:
  ```kotlin
  private suspend fun acceptLoop(socket: ServerSocket) = withContext(Dispatchers.IO) {
      while (isActive && !socket.isClosed) {
          val client = runCatching { socket.accept() }.getOrElse { break }
          inFlightSockets.add(client)
          launch {
              var activeSocket: Socket = client
              ...
              handshake(activeSocket) { clientLeaf }
  ```
- **Why existing guards do not stop it:** `HANDSHAKE_TIMEOUT_MS` (8,000 ms) eventually times out idle connections, but an attacker can open new connections at a rate far exceeding the timeout reclamation threshold. There is no capacity check before spawning coroutines.
- **Verifier result:** survived. Verified that `inFlightSockets` has no size check and `launch` executes without a semaphore or connection budget.
- **Recommended fix:** Introduce an inbound connection semaphore and per-IP limit:
  ```kotlin
  private const val MAX_CONCURRENT_INBOUND = 32
  private const val MAX_INBOUND_PER_IP = 4
  ```
  If `inFlightSockets.size >= MAX_CONCURRENT_INBOUND`, close the accepted socket immediately before launching the coroutine or initializing TLS.
- **Related:** `SEC-D02-04`.

---

### SEC-D24-02 — Multi-Announcement Reconnection Storm and Radio Wakefulness Battery Drain on Mobile
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A1, A2 (Adversary on local network or Wi-Fi hotspot)
- **Component:** `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt:1080-1110`
- **CWE / standard:** CWE-400: Uncontrolled Resource Consumption (Battery / Mobile Resource Abuse)
- **What is wrong:** When network disconnects occur, `WsFlashNetwork` schedules automatic background reconnection attempts for all devices in `reconnectTargets` and `knownEndpoints`. If an attacker broadcasts dozens of spoofed mDNS/multicast announcements with distinct device IDs and then periodically drops TCP connections, `WsFlashNetwork` registers multiple independent reconnection routines. On mobile devices, this prevents the Wi-Fi/cellular radio from entering low-power sleep states, continuously promoting radio power states and draining battery.
- **Impact:** Accelerated battery consumption and processor wakefulness on Android devices.
- **Evidence:** `WsFlashNetwork.kt:1088`: `(reconnectTargets.keys + knownEndpoints.keys).forEach { deviceId -> ... }` launches redial attempts across all registered keys without an aggregate concurrent reconnection cap.
- **Why existing guards do not stop it:** Backoff is tracked per device ID, so spoofing distinct device IDs creates distinct backoff trackers.
- **Verifier result:** survived. Confirmed that reconnection loops scale with the number of unique device IDs discovered.
- **Recommended fix:** Cap the total number of simultaneous background reconnection attempts (e.g. max 3 concurrent redials) and prioritize paired peers over discovered unauthenticated strangers.
- **Related:** `SEC-D10-01`, `SEC-D23-01`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D24-01: Regular Expression Catastrophic Backtracking (ReDoS)**: Audited all regex patterns in `core/`, `app/`, `desktop/`, and `server/`. All regular expressions utilize linear, non-nested quantifiers. No catastrophic exponential backtracking constructs (`(a+)+`) exist.
- **REJ-D24-02: Unbounded HTTP Request Body Memory Exhaustion on Hub Server**: Audited all REST route handlers in `server/`. Every endpoint utilizes `call.receiveBoundedText()` or `call.receiveBoundedBinary(HubContract.MAX_CHUNK_BYTES)` with explicit size limits (64 KiB default, 4000 chars for announcements, 4 MiB for chunks), rejecting oversized payloads with HTTP 413.
- **REJ-D24-03: Wire Protocol Decompression Bombs**: Audited wire codecs and WebSocket pipelines. Flash does not support or negotiate compressed streams (no gzip/deflate on WebSocket transfer chunks or relay frames). All incoming frames are uncompressed and size-bounded.
