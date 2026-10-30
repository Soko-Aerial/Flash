# Domain D26 — Time, Clocks and Freshness

## 1. Domain Overview
This domain evaluates time-dependent security decisions, clock skew tolerance, monotonic vs wall-clock usage, certificate and delegation validity windows, offline grace periods, TOTP time-step replays, and the impact of clock tampering across Android, Desktop, and Hub server environments.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Temporal Security Decision Inventory

| Security Decision | Clock Source | Bounds / Validity Window | Skew / Tamper Tolerance | Evaluation |
|---|---|---|---|---|
| P2P Keepalive & Liveness | `System.currentTimeMillis()` (`WsConnection`) | `pingIntervalMs` (15s) / `livenessTimeoutMs` (45s) | **Non-monotonic**: NTP steps cause false drops or stuck connections (`SEC-D26-01`). | DEFECT |
| Org Directory Sync | Server time vs local time | `MAX_ALLOWED_CLOCK_SKEW_MS = 5 minutes` | Flags `_clockSkewDetected = true`; rosters verified with local `now` (`SEC-D26-02`). | DEFECT |
| Org Offline Lease | `System.currentTimeMillis()` (`OrgPeerResolver`) | 30 days default grace | Clock backward jump > 5m expires lease (`ClockWrong`). | PASS |
| TOTP Verification | Server wall clock (`AdminAuthService`) | 30-second steps (window ±1 step) | Monotonic `last_totp_step` prevents replaying within window. | PASS |
| Admin Session & Step-Up | Server wall clock | 8 hours absolute, 30 min idle; Step-Up: 5 min | Revocation and idle checks enforced. | PASS |
| Mailbox Row TTL | Server wall clock (`MailboxQueue`) | 7 days default TTL | Auto-pruned by `HubSweeper` via `expires_at < ?`. | PASS |
| Over-the-Air Radio Epochs | `nowMs` (`RadioSession`) | 15-minute epoch (±1 epoch skew) | Anti-replay sliding window protects against delayed replay. | PASS |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Monotonic vs wall-clock time in networking | DEFECT | `WsConnection` and `LanSession` use wall-clock `System.currentTimeMillis()` for keepalives and liveness timeouts (`SEC-D26-01`). |
| Roster & certificate verification clock source | DEFECT | Group rosters verified against local client clock rather than authoritative server time (`SEC-D26-02`). |
| Clock rollback protection in offline trust | PASS | `OrgPeerResolver.kt:310` invalidates lease with `ExpiredReason.CLOCK` if clock is rolled back > 5 minutes (`REJ-D26-02`). |
| TOTP time window replay resistance | PASS | `AdminAuthService.kt` updates `last_totp_step` in database, preventing same-step reuse (`REJ-D26-01`). |
| Clock skew detection during hub synchronization | PASS | `OrgDirectorySync.kt:249` detects skew > 5 minutes and flags `clockSkewDetected`. |
| Mailbox message TTL pruning | PASS | Server prunes expired envelopes from database based on server time (`REJ-D26-03`). |

---

## 4. Files Audited
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioSession.kt`

---

## 5. Findings

### SEC-D26-01 — Wall-Clock `System.currentTimeMillis()` Used for Keepalive Timeouts and Deadlines in `WsConnection`
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A1, A6, A7 (Local unprivileged attacker adjusting system time or network NTP step)
- **Component:** `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt:73`, `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsConnection.kt:77`
- **CWE / standard:** CWE-670: Always-Incorrect Control Flow Implementation; CWE-361: Time-of-check Time-of-use (TOCTOU) Race Condition; CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H (6.2)
- **What is wrong:** `WsConnection` utilizes `nowMs: () -> Long = System::currentTimeMillis` to drive its keep-alive heartbeat loop, liveness timeout watchdog, and connection ping interval calculations. Wall-clock time is non-monotonic: it changes unpredictably during NTP synchronisation steps, daylight saving time transitions, or manual user clock adjustments. If the system clock steps forward by more than `livenessTimeoutMs` (e.g. 45 seconds during an NTP synchronization jump), `WsKeepalive.onTick` incorrectly concludes that the remote peer timed out and immediately severs active WebSocket connections, terminating active file transfers and calls. Conversely, if the clock steps backward, elapsed interval calculations produce negative values, suppressing keep-alive pings and preventing the app from detecting dropped sockets.
- **Impact:** Denial of service and socket instability: unexpected termination of ongoing peer transfers and calls upon routine NTP time adjustments.
- **Evidence:**
  `WsConnection.kt:77`:
  ```kotlin
  private val nowMs: () -> Long = System::currentTimeMillis,
  ```
  `WsConnection.kt:237-245`:
  ```kotlin
  var verdict = keepalive.onTick(nowMs())
  if (verdict is WsKeepaliveVerdict.Close) {
      logWarn("liveness timeout on $remoteLabel: closing connection")
      closeSocket()
  }
  ```
- **Why existing guards do not stop it:** `WsConnection` has no negative-delta clamp or monotonic guarantee for `nowMs`.
- **Verifier result:** survived. Code verification confirmed `System::currentTimeMillis` is the default clock provider for `WsConnection`.
- **Recommended fix:** Migrate network watchdog timing to a monotonic clock source:
  ```kotlin
  nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
  ```
- **Related:** `SEC-D02-04`.

---

### SEC-D26-02 — Group Roster and Delegation Verification Relies on Local Client Clock Instead of Authoritative Server Time
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A6, A7 (Local attacker manipulating system clock)
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:502`
- **CWE / standard:** CWE-295: Improper Certificate Validation; CWE-613: Insufficient Session Expiration
- **What is wrong:** In `OrgDirectorySync.verifiedRosters`, the validity of received group rosters and department delegations is evaluated against the local client wall-clock time (`now = timeSourceMs()`) rather than the authoritative `serverTimeMs` provided in the signed directory synchronization response. If an attacker on the device rolls back the system clock, expired department delegation certificates or obsolete group rosters continue to evaluate as `RosterStatus.OK`. Conversely, if the device clock is inadvertently set forward, valid delegations and rosters are immediately rejected with `RosterStatus.EXPIRED`, breaking group access.
- **Impact:** Circumvention of delegation expiration or denial of group functionality via client clock tampering.
- **Evidence:** `OrgDirectorySync.kt:502`: `statement.verify(..., nowMs = now, ...)` passes the local timestamp `now` rather than `serverTimeMs`.
- **Why existing guards do not stop it:** While `OrgDirectorySync` flags `_clockSkewDetected = true`, it continues execution and evaluates roster expirations using the skewed local clock.
- **Verifier result:** survived. Confirmed `verifiedRosters` uses local `now` for roster verification.
- **Recommended fix:** Pass `serverTimeMs` (adjusted by round-trip latency) to `statement.verify()` when syncing with an authoritative hub.
- **Related:** `SEC-D06-02`, `SEC-D14-01`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D26-01: TOTP Code Replay Across Validity Steps**: Audited `AdminAuthService.kt:830-860`. The server stores `last_totp_step` in SQLite for the authenticated admin user. Presenting a code corresponding to `step <= last_totp_step` is rejected, preventing replay within the valid window.
- **REJ-D26-02: Offline Org Trust Lease Survival on Clock Rollback**: Audited `OrgPeerResolver.kt:310-312`. The resolver checks `now < lastSyncSuccessMs - SKEW_TOLERANCE_MS`. Rolling the system clock back past 5 minutes immediately trips `OrgLease.Expired(reason = CLOCK)`, failing closed.
- **REJ-D26-03: Mailbox Envelopes Stored Indefinitely on Server**: Audited `MailboxQueue.kt` and `HubSweeper`. Expired messages are deleted from SQLite via `DELETE FROM mailbox_queue WHERE expires_at < ?` using the hub server's database clock.
