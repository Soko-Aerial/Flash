# Domain D16 — Hub REST API and Data Handling

Audited Commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

---

## 1. Scope and Files Read

The following files implementing the hub's REST surface, routing, parameter decoding, rate limiting, and persistence were audited:
- `docs/protocol-routes.txt` (checked-in REST route surface)
- `server/src/test/kotlin/com/transfer/flash/server/core/RouteTableContractTest.kt`
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`
- `server/src/main/kotlin/com/transfer/flash/server/core/RequestPathGuard.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/ApiGate.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/AuthRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/ClientAddress.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/HubRateLimits.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryPrivacy.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt`
- `server/src/main/kotlin/com/transfer/flash/server/files/FileRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/files/FileStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementText.kt`
- `server/src/main/kotlin/com/transfer/flash/server/identity/SealingKeyRoutes.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/rest/HubFileDtos.kt`

---

## 2. Checklist Answers

| Checklist Item | Finding / Status | Evidence |
|---|---|---|
| Undocumented REST routes vs `protocol-routes.txt` | Not Vulnerable (Enforced by test) | `RouteTableContractTest.kt:31-45` fails the build if `docs/protocol-routes.txt` diverges from running Ktor routing tree. |
| Authentication & Authorisation on all REST paths | Enforced via `requirePrincipal` / `toActor` | `ApiGate.kt:66-105`, `DirectoryRoutes.kt`, `FileRoutes.kt`. |
| Request body size limits | Enforced (Bounded streaming) | `ApiGate.kt:132-164` (`receiveBoundedText` 64KB max, `receiveBoundedBinary` 4MB max). |
| Pagination cursors (tamper, forge, cross-tenant) | Partially Vulnerable / Validated | `DirectoryPrivacy.kt:37-88` length-framed cursor; DirectorySync base64url cursor checked against actor visibility. |
| SQL Injection (string-built SQL anywhere in `:server`) | **VULNERABLE (SEC-D16-01)** | `AnnouncementStore.kt:525,535` string-interpolates query parameters into SQL clauses. |
| FTS5 `MATCH` syntax injection | Not Vulnerable | `AnnouncementStore.kt:879-885` strips non-alphanumeric chars with `Regex("[\\p{L}\\p{N}]+").findAll(input)`. |
| Directory Privacy (hidden dept enumeration via lookup/timing/paging/tombstones) | **VULNERABLE (SEC-D16-03)** | `DirectoryRoutes.kt:216-248` resolves display names of hidden department members on direct lookup. |
| Announcement text sanitization (XSS, zero-width, bidi) | Sanitized | `AnnouncementText.kt:42-154` normalizes NFC, strips bidi overrides, zero-width chars, and control codes. |
| File ID guessability & Path Traversal | Not Vulnerable | `HubFileIds.isFileId` requires exact 32 lowercase hex characters (`^[0-9a-f]{32}$`). |
| File Quota races & Storage Consistency | Protected by SQLite Immediate Tx | `FileStore.kt:98,197,256,341` uses `BEGIN IMMEDIATE` transaction locking. |
| File recipient authorization on read | Enforced (including live group re-check) | `FileStore.kt:529-558` verifies requester is sender or listed recipient and verifies live group membership. |
| CORS Configuration | Not Vulnerable (SOP Default) | Ktor `CORS` plugin is not installed; `BaselineHeaders.kt:117` applies `Cross-Origin-Resource-Policy: same-origin`. |
| `X-Forwarded-*` Header Trust & Spoofing | Protected | `ClientAddress.kt:45-53` ignores forwarded headers unless remote address matches `TrustedProxies`. |
| Memory leaks / Unbounded Caches | **VULNERABLE (SEC-D16-02)** | `DirectoryRoutes.kt:493` & `DirectoryPrivacy.kt:100` maintain unbounded `ConcurrentHashMap` with no eviction. |

---

## 3. Confirmed Findings

### SEC-D16-01 — Dynamic String Interpolation in Announcement Search SQL Clauses
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A3 (Authenticated hub member / employee)
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementStore.kt:525,535`
- **CWE / standard:** CWE-89 (Improper Neutralization of Special Elements used in an SQL Command); OWASP API3:2023 Broken Object Property Level Authorization; CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N (4.3)
- **What is wrong:** `AnnouncementStore.searchAnnouncements` constructs dynamic SQL filter clauses by directly interpolating `callerDeptId` and the user-supplied query parameter `departmentId` into SQL string literals (`a.department_id = '${escapeSql(departmentId)}'`) rather than using JDBC parameter placeholders (`?`). While a helper `escapeSql` is used to double single quotes (`replace("'", "''")`), relying on custom string sanitization in place of prepared statement parameter binding violates secure database interaction standards and creates architectural fragility.
- **Impact:** An attacker who discovers SQLite string parsing discrepancies or encoding bypasses could alter query logic, enumerate announcements outside their authorized visibility scope, or inject SQL expressions.
- **Evidence:**
  ```kotlin
  // AnnouncementStore.kt:522-536
  val visibilityClause = when {
      isOrgAdmin -> "1=1"
      callerDeptId.isNullOrBlank() -> "a.scope = 'org'"
      else -> "(a.scope = 'org' OR (a.scope = 'department' AND a.department_id = '${escapeSql(callerDeptId)}'))"
  }
  val filterClause = when {
      scope == "org" -> "a.scope = 'org'"
      scope == "department" && !departmentId.isNullOrBlank() -> {
          if (!isOrgAdmin && departmentId != callerDeptId) {
              return@withConnection AnnouncementSearchResponseDto()
          }
          "a.scope = 'department' AND a.department_id = '${escapeSql(departmentId)}'"
      }
      else -> "1=1"
  }
  ```
  These strings are subsequently interpolated into `val sql = """... AND $visibilityClause AND $filterClause ..."""` and executed via `conn.prepareStatement(sql)`.
- **Why existing guards do not stop it:** Existing checks only escape `'` with `''`, but the query is still assembled dynamically via string interpolation.
- **Verifier result:** survived. Code review confirms that parameter binding (`?`) is not used for `department_id`.
- **Recommended fix:** Refactor `searchAnnouncements` to use parameter placeholders (`?`) in both `$visibilityClause` and `$filterClause`, and pass the bound parameters into `stmt.setString(...)`.
- **Related:** ADR-138 (Announcements plan).

---

### SEC-D16-02 — Unbounded Memory Leak in Directory People Lookup Miss-Limiter Cache
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A3 (Enrolled member) or sustained client churn
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt:493-495`, `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryPrivacy.kt:95-115`
- **CWE / standard:** CWE-400 (Uncontrolled Resource Consumption); CWE-770 (Allocation of Resources Without Limits or Throttling)
- **What is wrong:** `DirectoryRoutes.kt` instantiates a global top-level map `private val lookupMissLimiters = ConcurrentHashMap<String, DirectoryPrivacy.LookupMissLimiter>()` keyed by caller `deviceId`. Additionally, each `LookupMissLimiter` maintains an internal `private val windows = ConcurrentHashMap<String, Window>()`. Neither map possesses any size cap, TTL expiration, or eviction mechanism. Every distinct device that performs a lookup retains memory entries permanently for the duration of the server process.
- **Impact:** Over prolonged server uptime and client lifecycle turnover (device unenrollment, re-enrollment, mobile app reinstalls generating new IDs), memory allocations in the JVM heap increase monotonically, eventually leading to heap fragmentation and potential `OutOfMemoryError`.
- **Evidence:**
  ```kotlin
  // DirectoryRoutes.kt:493-495
  private val lookupMissLimiters = java.util.concurrent.ConcurrentHashMap<String, DirectoryPrivacy.LookupMissLimiter>()
  private fun lookupMissLimiterFor(deviceId: String): DirectoryPrivacy.LookupMissLimiter =
      lookupMissLimiters.computeIfAbsent(deviceId) { DirectoryPrivacy.LookupMissLimiter() }

  // DirectoryPrivacy.kt:101
  private val windows = ConcurrentHashMap<String, Window>()
  ```
- **Why existing guards do not stop it:** The timestamp rollover in `LookupMissLimiter.check` resets counters (`w.lookups = 0; w.misses = 0`), but never removes obsolete keys from either map.
- **Verifier result:** survived. Confirmed neither map has eviction logic or bounded capacity.
- **Recommended fix:** Replace unbounded `ConcurrentHashMap` instances with an LRU cache or bounded cache (e.g. `LinkedHashMap` with `removeEldestEntry` or a TTL-evicting map with maximum entries of 10,000).
- **Related:** `SEC-D14-03`.

---

### SEC-D16-03 — Direct Device Lookup Endpoint Reveals Metadata of Hidden-Department Members
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A3 (Any authenticated member of the organization)
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt:216-248`
- **CWE / standard:** CWE-200 (Exposure of Sensitive Information to an Unauthorized Actor); CWE-213 (Exposure of Sensitive Information Due to Incompatible Policies)
- **What is wrong:** `GET /api/v1/directory/people/{deviceId}` allows any enrolled organization member to resolve a specific `deviceId`. When the target device belongs to a member of a hidden department, the endpoint returns HTTP 200 OK containing the member's `displayName` and `role` (only stripping `departmentId` and `departmentName`). An attacker who obtains candidate device IDs (e.g. from network discovery, peer handshakes, or logs) can verify whether the device belongs to an organization member in a hidden department and retrieve their real display name.
- **Impact:** Compromises anonymity of personnel in hidden/confidential departments (e.g. executive staff, security, or legal teams) whose existence is intended to be concealed from regular employees.
- **Evidence:**
  ```kotlin
  // DirectoryRoutes.kt:216-221
  val allowed = member != null && (DirectoryVisibility.canListMember(actor, member, directory) || member.deviceId == actor.deviceId)
  // Hidden-department members ARE resolvable by known id (id known = allowed),
  // but their department fields are blanked for outsiders.
  val visibleMember = if (allowed) member else member?.takeIf {
      DirectoryVisibility.canLookupMember(actor, target)
  }
  ```
- **Why existing guards do not stop it:** `DirectoryVisibility.canLookupMember` explicitly returns `true` for all authenticated members who know the target `deviceId`: `actor.deviceId.isNotBlank() && deviceId.isNotBlank()`.
- **Verifier result:** survived. While documented as an intentional trade-off in code comments, it breaks the privacy boundary for hidden departments.
- **Recommended fix:** Restrict `canLookupMember` so that outsiders querying members of hidden departments receive a 404 Not Found, identical to non-existent device IDs.
- **Related:** ADR-128 (Directory Privacy).

---

## 4. Refuted / Rejected Hypotheses

| ID | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D16-01 | Path traversal via file upload/download endpoints (`/api/v1/files/{fileId}`) | `HubFileIds.isFileId(fileId)` requires exactly 32 lowercase hex characters (`value.length == 32 && value.all(::isLowerHex)`), rejecting any directory traversal characters (`..`, `/`, `\`). `index` is constrained to positive integers. |
| REJ-D16-02 | File quota race conditions allowing senders to exceed quota limits | Every mutating file operation (`create`, `putChunk`, `complete`, `cancel`) executes inside `db.withTransaction { conn -> ... }`, which issues `BEGIN IMMEDIATE` at the SQLite layer, serializing all quota checks and insertions. |
| REJ-D16-03 | FTS5 syntax injection in announcement text search | `AnnouncementStore.kt:879-885` (`escapeFts5Query`) filters input through `Regex("[\\p{L}\\p{N}]+").findAll(input)`, discarding all punctuation, FTS5 operators (`AND`, `OR`, `NOT`, `NEAR`), and quotes. |
| REJ-D16-04 | CORS bypass allowing malicious websites to invoke Hub REST APIs | No Ktor CORS plugin is installed, ensuring browsers enforce standard Same-Origin Policy. Furthermore, `BaselineHeaders.kt:117` enforces `Cross-Origin-Resource-Policy: same-origin`. |
