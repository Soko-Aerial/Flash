# Domain D15 — Hub Relay & Mailbox

## 1. Domain Overview
Domain D15 audits the WebSocket relay and store-and-forward mailbox architecture of the Flash Enterprise Hub (`/flash-relay`). This includes the wire protocol v2 handshake (`FLASH_HUB_CHALLENGE` / `FLASH_HUB_HELLO` / `FLASH_HUB_WELCOME`), sender identity enforcement (`RelayFrameRouter`), durable message queuing (`MailboxQueue`, `MailboxStore`), quota and quarantine behavior (`MailboxLimits`, ADR-222/ADR-223), replay protection (`SealedEnvelope`), frame size limits, and connection session lifecycle (`RelaySession`, `RelayPolicy`).

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

Files examined:
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayPolicy.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayAuth.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayConnectionPool.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxSchema.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxModels.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/SealedEnvelope.kt`
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/relay/RelayFrameCodec.kt`

---

## 2. Findings Ledger

| ID | Title | Severity | CWE | CVSS v3.1 | Attacker Model | Status |
|---|---|---|---|---|---|---|
| `SEC-D15-01` | Mailbox quota exhaustion allows single sender to starve target recipient from receiving messages | Medium | CWE-400 | 5.3 | A3 | CONFIRMED |
| `SEC-D15-02` | Hub relay protocol lacks freshness window or monotonic counters, permitting message replay if client chat history cleared | Medium | CWE-294 | 4.3 | A4, A3 | CONFIRMED |
| `SEC-D15-03` | Group message fanout concurrency permits resource contention during large broadcast bursts | Low | CWE-400 | 3.1 | A3 | CONFIRMED |

---

## 3. Detailed Findings

### SEC-D15-01: Mailbox Quota Exhaustion Allows Single Sender to Starve Target Recipient from Receiving Messages
- **File & Line:** `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt:158-164`, `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxModels.kt:21-24`
- **Attacker Model:** A3 (Enrolled hub member)
- **CWE:** CWE-400: Uncontrolled Resource Consumption / CWE-770: Allocation of Resources Without Limits or Throttling
- **CVSS v3.1 Score:** 5.3 (`CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:N/A:L`)
- **Vulnerability Description:**
  `MailboxLimits` defines two per-entity quotas:
  - `perRecipientRows = 1_000` (maximum queued messages waiting for a recipient across all senders)
  - `perSenderRows = 5_000` (maximum queued messages in transit from a sender across all recipients)
  There is **no per-sender-to-recipient sub-quota**.
  Because a sender's allowance (5,000) exceeds a recipient's allowance (1,000), a single malicious enrolled colleague can transmit 1,000 small offline messages to a target colleague (e.g., `dev-target`).
  In `MailboxQueue.kt:158-161`:
  ```kotlin
  val (recipientRows, recipientBytes) = usage(conn, "target_device_id", m.target, now)
  if (recipientRows >= limits.perRecipientRows) {
      return EnqueueResult.QuotaExceeded(QuotaScope.RECIPIENT, "recipient mailbox holds ${limits.perRecipientRows} messages")
  }
  ```
  Once the attacker fills the 1,000 slots, any subsequent message sent by *other* colleagues (or critical organizational announcements) to `dev-target` while offline is rejected with `EnqueueResult.QuotaExceeded` (`ERR_QUOTA_EXCEEDED`).
  The target recipient is completely denied the ability to receive communications from anyone else in the organization until they connect and acknowledge the attacker's messages.
- **Proof of Concept:**
  1. Mallory (an employee) targets victim Alice.
  2. Alice is offline for the weekend.
  3. Mallory scripts a loop sending 1,000 1-byte messages to Alice via `/flash-relay`.
  4. The hub queues all 1,000 rows.
  5. Alice's manager Bob attempts to send an urgent file or message to Alice.
  6. The hub rejects Bob's message with `AckStatus.QUOTA_EXCEEDED`. Bob cannot reach Alice.
- **Guard Analysis:** Quotas are enforced atomically via SQLite immediate transactions, but only measure aggregate rows per recipient, allowing one sender to consume 100% of the recipient's quota.
- **Remediation:**
  Introduce a per-sender-per-recipient row limit (e.g. maximum 100 queued messages from any single sender to a given recipient) in `MailboxLimits`.

---

### SEC-D15-02: Hub Relay Protocol Lacks Freshness Window or Monotonic Counters, Permitting Replay if Client History Cleared
- **File & Line:** `server/src/main/kotlin/com/transfer/flash/server/mailbox/SealedEnvelope.kt:26-32`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:66-67`
- **Attacker Model:** A4 (Compromised or rogue hub operator) / A3 (Malicious sender replaying old frames)
- **CWE:** CWE-294: Authentication Bypass by Capture-replay / CWE-924: Improper Enforcement of Message Integrity During Transmission
- **CVSS v3.1 Score:** 4.3 (`CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:N`)
- **Vulnerability Description:**
  In `SealedEnvelope.kt`:
  ```kotlin
  * @property counter Reserved (REL-15 decision, ADR-112): replay protection is the recipient's msgId dedupe, the relay wire has
  *   no counter and the mailbox table no longer stores one.
  ```
  The hub relay wire format (`FLASH_RELAY` / `FLASH_DELIVER`) and mailbox storage do not enforce monotonic sequence numbers, ratchet states, or timestamp validity ceilings. Replay protection relies entirely on the recipient client's local SQLite database deduplication (`INSERT OR IGNORE INTO messages WHERE messageId = ?`).
  If a recipient clears a conversation, deletes messages to free disk space, or provisions a new secondary device (e.g. a newly paired desktop that did not receive original messages), a rogue hub operator (A4) or network eavesdropper who recorded past ciphertext can re-deliver an old valid `FLASH_DELIVER` envelope.
  Because the message ID is not present in the recipient's local database, the client accepts the replayed message, decrypts it with the active session key, displays it in the chat timeline, and triggers a user notification.
- **Proof of Concept:**
  1. Alice sends Bob a signed instruction: `"Transfer approved"`.
  2. Bob receives it and later deletes the chat thread.
  3. A compromised hub operator replays the original envelope `FLASH_DELIVER` for that message ID.
  4. Bob's client receives the frame. Because the message ID is no longer in Bob's local database, Bob's client accepts and displays `"Transfer approved"` as a new message.
- **Guard Analysis:** Deduplication by `msgId` works only as long as the recipient retains all historical message IDs indefinitely.
- **Remediation:**
  Include a sender-signed timestamp in the authenticated envelope payload and enforce a maximum acceptable age window (e.g. reject messages whose authenticated timestamp is older than 7 days, or require ratchet-based replay counters).

---

### SEC-D15-03: Group Message Fanout Concurrency Permits Resource Contention During Large Broadcast Bursts
- **File & Line:** `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:261-268`
- **Attacker Model:** A3 (Enrolled member of large groups)
- **CWE:** CWE-400: Uncontrolled Resource Consumption
- **CVSS v3.1 Score:** 3.1 (`CVSS:3.1/AV:N/AC:H/PR:L/UI:N/S:U/C:N/I:N/A:L`)
- **Vulnerability Description:**
  In `RelayFrameRouter.kt:261-268`:
  ```kotlin
  val gate = Semaphore(policy.groupFanoutConcurrency)
  val outcomes = coroutineScope {
      recipients.map { member ->
          async {
              gate.withPermit { routeOne(origin.deviceId, member, send.id, send.type, send.sentAt, send.payload, groupId) }
          }
      }.awaitAll()
  }
  ```
  When a member sends a message to an organization-wide group with hundreds or thousands of members, `RelayFrameRouter` maps all recipients into asynchronous coroutines bounded by `groupFanoutConcurrency = 16`. Each coroutine executes `routeOne`, which performs connection pool lookups, SQLite mailbox queries, and WebSocket frame dispatches.
  A rapid sequence of group messages from multiple senders can saturate the coroutine dispatcher and SQLite write transactions, introducing queuing delays for time-critical 1:1 call signalling and direct messages.
- **Proof of Concept:**
  A member scripts rapid group messages in a large group, driving SQLite transaction latency up and delaying 1:1 relay frames.
- **Guard Analysis:** `withinByteBudget` checks byte limits, but does not throttle the fanout pipeline based on database lock contention.
- **Remediation:**
  Batch group mailbox inserts into a single bulk SQLite transaction (`insertAll`) rather than invoking `routeOne` individually per member.

---

## 4. Refuted / Rejected Hypotheses

### REJ-D15-01: Relay Sender Spoofing via Client-Supplied Frame Fields
- **Hypothesis:** A hostile client can forge the sender device ID in `FLASH_RELAY` or `FLASH_META` to impersonate an administrator or colleague.
- **Refutation:** `RelayFrame.Send` does not contain a sender field. In `RelayFrameRouter.routeOne` (line 311), the router stamps `deliver.from = originId`, where `originId` is `originSession.deviceId` — the authenticated identity cryptographically proved during the challenge-response handshake.

### REJ-D15-02: Unauthorized Recipient Mailbox Message Deletion via `FLASH_RECV_ACK`
- **Hypothesis:** An attacker can guess or observe message IDs and send `FLASH_RECV_ACK` to delete another device's pending mailbox messages.
- **Refutation:** In `RelayFrameRouter.handleRecvAck` and `MailboxQueue.acknowledge`, deletion queries are strictly constrained by `WHERE target_device_id = ? AND msg_id = ?`, passing the authenticated `recipient.deviceId`. A device can only delete rows for which it is the registered recipient.

### REJ-D15-03: Oversized WebSocket Frame Denial of Service
- **Hypothesis:** An attacker can stream a multi-megabyte WebSocket frame to exhaust server RAM or trigger an OutOfMemoryError.
- **Refutation:** Ktor WebSocket feature is configured with `maxFrameSize = HubContract.MAX_FRAME_BYTES` (256 KiB) in `FlashServerEngine.kt:190`. Any frame exceeding 256 KiB causes Ktor to immediately abort the connection with `CloseReason.Codes.TOO_BIG` before allocating buffer memory.
