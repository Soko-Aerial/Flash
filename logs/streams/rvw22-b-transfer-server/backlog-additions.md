# Test Backlog Additions — Stream B (rvw22-b-transfer-server)

## R22B-01 — File Overwrite Truncation on Android Device

- **Area:** Transfer Engine / Storage (`F40`, `ERROR-770`)
- **Setup:** Two physical Android devices (Device A sender, Device B receiver) connected via LAN. On Device B, pre-populate destination file path with a 10 MB dummy file.
- **Steps:**
  1. From Device A, transfer a 2 MB file with the same destination name to Device B.
  2. Accept transfer on Device B, allowing overwrite.
  3. Wait for transfer completion.
  4. Inspect the resulting file on Device B storage.
- **Pass condition:** The file size on Device B is exactly 2 MB (not 10 MB), and its sha256 checksum matches Device A's file exactly.
- **Source:** F40 audit finding
- **Status:** TODO

---

## R22B-02 — Retransmitted / Duplicate Chunk ACK under Packet Loss

- **Area:** Transfer Engine / Network Protocol (`F41`, `ERROR-771`)
- **Setup:** Two physical Android devices connected via LAN or Wi-Fi Direct. Introduce synthetic network packet duplication / high latency between sender and receiver.
- **Steps:**
  1. Transfer a 50 MB file from Device A to Device B.
  2. Cause network hiccups or retransmissions during transfer.
  3. Observe transfer progress and logcat tags `CHUNK`, `TRANSFER`.
- **Pass condition:** Duplicate chunk arrivals immediately emit ACK frames without stalls; transfer finishes at full throughput without hanging on sender retry timers.
- **Source:** F41 audit finding
- **Status:** TODO

---

## R22B-03 — UI Notification on Whole-File Hash Mismatch

- **Area:** Engine / UI Notification (`F56`, `ERROR-772`)
- **Setup:** Two physical Android devices on LAN.
- **Steps:**
  1. Initiate file transfer from Device A to Device B.
  2. Simulate corrupted destination byte during in-flight write.
  3. Observe Transfer UI on Device B upon completion verification.
- **Pass condition:** Device B displays transfer failure / verification error toast or card; it must NEVER display "Transfer successful" or mark `verified = true`.
- **Source:** F56 audit finding
- **Status:** TODO

---

## R22B-04 — Relay Server Spill Metric Verification

- **Area:** Relay Server (`F60`, `ERROR-773`)
- **Setup:** Hub server deployed in test environment. Two clients connected via WebSocket relay.
- **Steps:**
  1. Client A sends messages to Client B while Client B's in-flight queue overflows or Client B disconnects mid-flight with pending messages that exceed mailbox limits.
  2. Query Prometheus / internal relay metrics `/metrics`.
- **Pass condition:** Dropped messages counter `relay_messages_dropped_total` increments by exactly the number of dropped messages without 2x overcounting.
- **Source:** F60 audit finding
- **Status:** TODO

---

## R22B-05 — Group Meta Id Enumeration Protection

- **Area:** Relay Server Security (`F64`, `ERROR-774`)
- **Setup:** Client connected to Hub Relay via WebSocket.
- **Steps:**
  1. Send `FLASH_GROUP_META` request for an existing group where the client is not a member.
  2. Send `FLASH_GROUP_META` request for a non-existent random group UUID.
  3. Record response frame types and latency.
- **Pass condition:** Both requests return identical `RelayFrame.Refused(msgId, "ERR_NOT_MEMBER")` response with indistinguishable timing, leaking no information about group existence.
- **Source:** F64 audit finding
- **Status:** TODO

---

## R22B-06 — Mailbox Quarantine & Expiration Lifecycle on Deployed Hub

- **Area:** Mailbox Storage (`F39`, `ERROR-775`)
- **Setup:** Hub server with SQLite mailbox queue.
- **Steps:**
  1. Insert a quarantined message row (undeliverable reason set).
  2. Advance server clock past the message's expiration timestamp.
  3. Trigger `deleteExpired()`.
  4. Observe emitted event streams and database disk footprint.
- **Pass condition:** The expired quarantined row is deleted from the database disk, but no `EXPIRED` event is dispatched to sender (as it was already notified as `DROPPED`).
- **Source:** F39 audit finding
- **Status:** TODO

---

## R22B-07 — Relay Session Scope Disposal & Heap Retention Benchmark

- **Area:** Relay Server Memory Lifecycle (`F78`, `ERROR-776`)
- **Setup:** Hub server running on test Linux host with JVM profiling (e.g. async-profiler or JXRay heap dump analysis).
- **Steps:**
  1. Connect 1,000 WebSocket relay sessions.
  2. Disconnect all 1,000 clients abruptly.
  3. Trigger JVM GC and take heap snapshot.
  4. Inspect heap retention for `RelaySession`, `SupervisorJob`, and coroutine contexts.
- **Pass condition:** Zero leaked `RelaySession` instances or orphaned `SupervisorJob` nodes retained in memory after disconnect; heap overhead returns to baseline.
- **Source:** F78 audit finding
- **Status:** TODO

---

## R22B-08 — Hub File Upload Resume after Storage Interruption on Device

- **Area:** Enterprise Hub Files (`F38`, `ERROR-777`)
- **Setup:** Android device enrolled with test enterprise hub.
- **Steps:**
  1. Start uploading a 100 MB sealed file to the hub.
  2. After 50 MB is uploaded, simulate transient I/O error or temporary storage unavailability (e.g. unmount external SD card or trigger app background storage restriction).
  3. Observe upload outcome.
  4. Restore storage availability and resume upload.
- **Pass condition:** The upload pauses/interrupted without sending `api.cancel` to the hub; local state is preserved; resuming finishes uploading from chunk 51 to 100 without restarting from chunk 0.
- **Source:** F38 audit finding
- **Status:** TODO

---

## R22B-09 — Bounded Response Reading Socket Cleanliness under Load

- **Area:** Enterprise HTTP Client (`F79`, `ERROR-778`)
- **Setup:** Android client and Desktop client connecting to Enterprise Hub REST endpoints under concurrent load.
- **Steps:**
  1. Trigger 500 requests against endpoints returning responses exceeding `MAX_REST_BODY_BYTES`.
  2. Monitor open file descriptors (`lsof` on Linux, `/proc/net` or handle counts on Windows).
- **Pass condition:** No leaking socket descriptors or hung CIO connections; connections cleanly terminate on early body cutoff.
- **Source:** F79 audit finding
- **Status:** TODO
