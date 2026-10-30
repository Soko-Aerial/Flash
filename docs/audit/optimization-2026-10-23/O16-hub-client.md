# Domain O16 — Hub Client (:core:enterprise, :core:hub-protocol)
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** Senior Performance Engineer  

---

## 1. Domain Header & Scope

This audit evaluates the enterprise hub client implementation across `:core:enterprise` and `:core:hub-protocol`, covering directory sync, cryptographic statement verification, member snapshots, announcements, relay WebSocket tunnels, mailbox draining, authentication lifecycle, and sealed hub file staging.

---

## 2. Files Inspected

- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseHub.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubSession.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubRestExecutor.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgSnapshotCoordinator.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/AnnouncementsSync.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/KtorRelayTransport.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt`

---

## 3. Hot Paths: Hub Client Operations

| Path ID | Trigger / Cadence | Thread / Dispatcher | Operation / Network | What Runs |
|---|---|---|---|---|
| **HP-HUB-01** | Hub presence poll (every 30s) | Hub coroutine | HTTP GET `/directory/presence` | Periodic REST polling over cellular/Wi-Fi to refresh `hubOnlineDeviceIds` |
| **HP-HUB-02** | Draining incoming mailbox messages | Relay client worker | WebSocket `FLASH_RECV_ACK` | Sends individual `FLASH_RECV_ACK` frame per message to clear server mailbox |
| **HP-HUB-03** | Sealed file upload / download (1 MiB chunks) | File transfer worker | HTTP PUT / GET `/files/{id}/chunks/{i}` | Sequential stop-and-wait HTTP upload/download with per-chunk disk save |
| **HP-HUB-04** | Directory sync round (initial enrollment) | Sync coroutine | Multi-page HTTP GET `/directory/sync` | Paged fetch of 200 members / 2 MiB, accumulates in memory, single SQLite commit |
| **HP-HUB-05** | WebSocket relay tunnel keepalive | Relay coroutine | WebSocket PING (30s) | Sends `FLASH_PING` to maintain tunnel through NAT / Cloudflare |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | Directory sync: full vs delta, page size (200 members/2 MiB), transaction cost | **Problem** | `OPT-O16-02` (All pages for up to 20k members accumulated in heap before commit; 35–45 MB spike) | `STATIC-ARITHMETIC` |
| 2 | Cryptographic statement verification in directory sync: ECDSA count, caching | **No problem** | EXP-022: Delegation verification cached via `preVerifiedDelegations`; delta sync with 0 changes takes 3 ms. | `MEASURED` |
| 3 | OrgPeerResolver snapshot refresh cost and lock use | **No problem** | In-memory atomic snapshot reference swap; read lock avoids holding SQLite open during resolution. | `STATIC-ARITHMETIC` |
| 4 | Announcements sync and FTS integration | **No problem** | Delta sync on version cursor; SQLite FTS5 index used for announcements search. | `STATIC-ARITHMETIC` |
| 5 | Relay client lifecycle: reconnect backoff, heartbeat period (30s) | **No problem** | Jittered exponential backoff (1s–60s) with `HEALTHY_AFTER_MS = 10s` reset; 30s heartbeat. | `STATIC-ARITHMETIC` |
| 6 | Mailbox acknowledgment batching: per-message vs batched | **Problem** | `OPT-O16-04` (`acknowledgeReceipt` sends unbatched individual `FLASH_RECV_ACK` frame per drained message) | `STATIC-ARITHMETIC` |
| 7 | Sealed hub file transfers: chunk size and parallelism | **Problem** | `OPT-O16-01` (1 MiB chunks uploaded/downloaded strictly sequentially with 0 parallelism) | `STATIC-ARITHMETIC` |
| 8 | HubRestExecutor connection reuse and HTTP client pooling | **No problem** | Uses shared `HttpClient(CIO)` with connection keepalive and TLS session caching enabled. | `STATIC-ARITHMETIC` |
| 9 | Polling vs Push: presence polling frequency (30s) | **Problem** | `OPT-O16-03` (Continuous 30s HTTP REST presence polling even while WebSocket relay is actively connected) | `STATIC-ARITHMETIC` |
| 10 | /auth/verify frequency and single-flight client refresh | **No problem** | Protected by `singleFlightMutex`, 45s timeout under lock (CLI-02), and token cached until expiry minus grace window. | `STATIC-ARITHMETIC` |
| 11 | Flaky mobile data resilience: timeouts and retry storms | **No problem** | CLI-01 reauth jitter (0..30s) prevents thundering herd; bounded HTTP response parsing (`boundedText`). | `STATIC-ARITHMETIC` |

---

## 5. Detailed Findings

### OPT-O16-01 — Sequential Stop-and-Wait Chunk Pipeline in Sealed Hub File Transfers
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, N   **Runtime:** Android | Desktop | Server
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt:341-365, 555-580`
- **Bottleneck class:** protocol, network
- **What is wasteful:**  
  `HubFileTransfers` uploads and downloads sealed files strictly one chunk at a time (`for (index in 0 until layout.chunkCount) { putChunk(...) }`). There is zero pipelining or concurrency. On a WAN/cellular link with 50 ms latency, every 1 MiB chunk requires a complete HTTP request-response round-trip before the next chunk is read and encrypted. For a 100 MiB file (100 chunks), this incurs 100 sequential round-trips (5.0 seconds of pure idle network latency wait), capping upload throughput to a fraction of available WAN bandwidth.
- **Cost model:** 100 sequential HTTP roundtrips per 100 MB file. At 60 ms RTT, 6,000 ms is wasted waiting on sequential acks.
- **User-visible effect:** Unnecessarily slow file uploads and downloads through the enterprise hub over internet connections.
- **Expected gain:** 2.5x–3.5x faster hub file transfer throughput by using a sliding window of 2–3 concurrent chunk HTTP requests.
- **Proposed change:** Dispatch chunk uploads/downloads using a bounded coroutine worker pool (e.g., `Channel<Int>(capacity = 3)`) with a sliding window of 3 in-flight chunks.
- **Invariants touched:** Preserves chunk SHA-256 verify-before-write, end-to-end sealing, and done-set bitmap tracking.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Measure 50 MiB upload over synthetic 50 ms WAN link; verify throughput increases from ~15 MB/s to >35 MB/s.
- **Verifier result:** Survived.

---

### OPT-O16-02 — Multi-Page Directory Sync Accumulates All 20,000 Members in Heap Before Commit
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC (backed by EXP-022 and OPT-O02-03)
- **Dimension(s):** M, K, T   **Runtime:** Android | Desktop
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:68-80, 204-260`
- **What is wasteful:**  
  To satisfy failure atomicity, `OrgDirectorySync` fetches all 100 pages of a 20,000-member directory sync and accumulates all `DirectoryMemberSyncDto` and `OrgDirectoryMemberEntity` objects in memory before executing a single Room database transaction. Staging 20,000 member DTOs across 100 pages consumes 35–45 MB of temporary JVM/ART heap, triggering severe GC pauses and risking `OutOfMemoryError` on 2 GB Android phones (128 MB heap ceiling).
- **Cost model:** 20,000 member DTOs * ~1.8 KB heap footprint = ~36 MB transient heap spike.
- **User-visible effect:** App crash (OOM) on entry-level Android devices during initial enterprise directory enrollment.
- **Expected gain:** Flatten memory footprint to < 3 MB by streaming and staging pages incrementally into a temporary staging SQLite table.
- **Proposed change:** Stage verified pages into an unindexed SQLite table `org_member_staging` per page; on final page success, execute an atomic `INSERT INTO org_members SELECT * FROM org_member_staging` in a single transaction.
- **Invariants touched:** Preserves failure atomicity (aborted sync discards staging table; existing snapshot remains untouched).
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Run 20,000 member enrollment on low-memory device profile; observe peak heap under 10 MB vs 45 MB.
- **Verifier result:** Survived.

---

### OPT-O16-03 — Redundant 30s HTTP Presence Polling Over Cellular While WebSocket Relay is Active
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, B, C   **Runtime:** Android | Desktop
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseHub.kt:219, 227-238`
- **What is wasteful:**  
  When enrolled, `EnterpriseHub` runs `pollHubPresence()`, executing an HTTP GET `/directory/presence` request every 30 seconds (`hubPresencePollMs = 30_000L`). This occurs even while the persistent WebSocket relay tunnel (`FlashRelayClient`) is actively connected to the hub. Polling via HTTP generates 120 full TLS/HTTP round-trips per hour on cellular radio, preventing cellular baseband sleep and consuming unnecessary airtime and battery.
- **Cost model:** 120 HTTP requests/hr * 1.2 KB = ~144 KB/hr + 120 cellular radio state promotions (drawing 100–250 mA per promotion).
- **User-visible effect:** Noticeable background battery drain on cellular data when enterprise hub is enabled.
- **Expected gain:** 90% reduction in presence network traffic by pushing presence deltas over the existing WebSocket relay tunnel.
- **Proposed change:** Deliver presence updates via `FLASH_PRESENCE_UPDATE` frames over the existing WebSocket relay; downscale HTTP polling to 10 minutes (or disable it) while relay is connected.
- **Invariants touched:** Preserves presence freshness.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Inspect network log: verify 0 HTTP `/directory/presence` requests while WebSocket is in `CONNECTED` state.
- **Verifier result:** Survived.

---

### OPT-O16-04 — Unbatched FLASH_RECV_ACK WebSocket Frames During Mailbox Queue Drain
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, C, S   **Runtime:** Android | Desktop | Server
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt:222-230`
- **What is wasteful:**  
  When a device reconnects after being offline, the server drains its queued mailbox messages. For every received message, `FlashRelayClient.acknowledgeReceipt(messageId)` sends an individual `FLASH_RECV_ACK` WebSocket frame. For 200 queued messages, this transmits 200 small frames (each ~100 bytes including framing) and triggers 200 individual SQLite delete transactions in `MailboxQueue.ackMessage` on the server.
- **Cost model:** 200 messages = 200 WebSocket frame transmissions + 200 server DB writes.
- **User-visible effect:** Slower mailbox drain completion, higher server write amplification.
- **Expected gain:** 85% fewer wire frames and bulk SQLite delete on server.
- **Proposed change:** Buffer acknowledgments and send batched `FLASH_RECV_ACK_BATCH` containing list of message IDs on a 100 ms flush window or after every 20 messages.
- **Invariants touched:** Exactly-once delivery preserved.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Measure mailbox drain of 500 messages; verify server DB writes drop from 500 to < 10 transactions.
- **Verifier result:** Survived.

---

## 6. Summary of Findings

- **Total Findings:** 4 (2 High, 2 Medium).
- **Primary Bottlenecks:** Stop-and-wait sequential chunk transfer in sealed files; 35 MB heap staging spike during 20k member sync; redundant 30s HTTP presence polling over cellular.
