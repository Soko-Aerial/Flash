# Domain D14 — Hub Authentication & Authorisation

## 1. Domain Overview
Domain D14 audits the authentication and authorization architecture of the Flash Enterprise Hub server. This encompasses device enrollment (`EnrollmentService`), certificate lifecycle and delegation management (`DelegationManager`, `ChallengeResponseAuth`), bearer token issuance and resolution (`BearerTokenStore`, `BearerPrincipalResolver`), API gating (`HubApiGate`), registry-derived principal resolution (ADR-222 / DEV-01), client address and reverse proxy verification (`ClientAddressResolver`, `TrustedProxies`), and department scoping (`ConsoleScope`).

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

Files examined:
- `server/src/main/kotlin/com/transfer/flash/server/auth/EnrollmentService.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/AuthRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/ChallengeResponseAuth.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/BearerTokenStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/ApiGate.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/DelegationManager.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/ClientAddress.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/SqliteRevocationService.kt`
- `server/src/main/kotlin/com/transfer/flash/server/identity/IdentityStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/ConsoleScope.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/DirectoryRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/files/FileRoutes.kt`
- `server/src/main/kotlin/com/transfer/flash/server/announcements/AnnouncementRoutes.kt`

---

## 2. Findings Ledger

| ID | Title | Severity | CWE | CVSS v3.1 | Attacker Model | Status |
|---|---|---|---|---|---|---|
| `SEC-D14-01` | Unpinned first delegation permits Trust-On-First-Use (TOFU) root key hijacking on startup | Medium | CWE-347 | 6.8 | A5, A6 | CONFIRMED |
| `SEC-D14-02` | Enrollment invites allow arbitrary client-chosen `deviceId`, enabling prospective device ID pre-emption and lockout | Medium | CWE-284 | 5.3 | A3 | CONFIRMED |
| `SEC-D14-03` | In-memory bearer token store eviction policy allows noisy enrolled clients to prematurely invalidate active user sessions | Low | CWE-400 | 3.1 | A3 | CONFIRMED |

---

## 3. Detailed Findings

### SEC-D14-01: Unpinned First Delegation Permits Trust-On-First-Use Root Key Hijacking on Startup
- **File & Line:** `server/src/main/kotlin/com/transfer/flash/server/auth/DelegationManager.kt:152-160`
- **Attacker Model:** A5 (Network attacker if file dropped via mount/upload) / A6 (Local user / attacker with write access to data directory) / A10 (Supply chain/deployment)
- **CWE:** CWE-347: Improper Verification of Cryptographic Signature / CWE-287: Improper Authentication
- **CVSS v3.1 Score:** 6.8 (`CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:N`)
- **Vulnerability Description:**
  In `DelegationManager.kt`, when `requireRootPin` is not explicitly set to true in configuration and no initial root pin exists (`rootPin() == null`), the hub automatically adopts the root key of the first valid delegation certificate it encounters on the filesystem:
  ```kotlin
  if (rootPin() == null) {
      onRootAdopted(rootFingerprint(cert))
      log.warn(
          "TRUST ON FIRST USE: the hub adopted root key {} from the first delegation it saw. Compare it with the fingerprint " +
              "'keytool init-root' printed; set FLASH_ROOT_FINGERPRINT (and FLASH_REQUIRE_ROOT_PIN=true) so this can not happen.",
          rootFingerprint(cert),
      )
  }
  ```
  If an attacker on a shared host, deployment environment, or via container volume manipulation plants a `delegation.cert` before the legitimate administrator deploys the official delegation, the hub binds itself to the attacker's organization root key. The server will subsequently accept only attacker-signed delegations and issue certificates under the attacker's authority.
- **Proof of Concept:**
  1. An attacker generates their own offline root keypair and issues a delegation certificate for the server's public key.
  2. The attacker drops `delegation.cert` into the server state directory before first boot.
  3. The server starts without `FLASH_ROOT_FINGERPRINT`.
  4. The hub adopts the attacker's root key and logs a warning. The attacker now controls the root of trust for all subsequent enrolments.
- **Guard Analysis:** While `requireRootPin` exists as a flag (`requireRootPin: Boolean = false`), it defaults to false, permitting insecure TOFU adoption out of the box.
- **Remediation:**
  Require `FLASH_ROOT_FINGERPRINT` to be specified during server initialization, or fail startup if no root fingerprint is configured instead of silently adopting the first delegation file found.

---

### SEC-D14-02: Enrollment Invites Allow Arbitrary Client-Chosen `deviceId`, Enabling Prospective Identity Pre-emption
- **File & Line:** `server/src/main/kotlin/com/transfer/flash/server/auth/EnrollmentService.kt:216-240`, `server/src/main/kotlin/com/transfer/flash/server/identity/IdentityStore.kt:209-245`
- **Attacker Model:** A3 (Malicious Hub member / colleague holding a valid invite token)
- **CWE:** CWE-284: Improper Access Control / CWE-400: Uncontrolled Resource Consumption
- **CVSS v3.1 Score:** 5.3 (`CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:N/I:L/A:L`)
- **Vulnerability Description:**
  When an administrator creates an enrollment invite (`createInvite`), the invite specifies scope (`department`, `allowedGroups`, `role`, `expiry`, `maxUses`), but **does not bind an expected or authorized `deviceId`**.
  When enrolling (`/api/v1/enroll`), the client submits `deviceId` alongside `subjectPublicKey` and proof-of-possession.
  In `IdentityStore.kt:209-245`, if the specified `deviceId` is not currently in the database (or is marked `UNENROLLED`), the hub registers `deviceId` with the caller's `subjectPublicKey`.
  If a malicious colleague discovers or anticipates the device ID of another employee (e.g., from LAN peer discovery sightings, prior transfers, or debug logs), the attacker can use their own valid invite token to register the victim's `deviceId` with the attacker's own public key.
  When the legitimate employee subsequently attempts to enroll their device with their own invite token, `IdentityStore.kt:215` encounters:
  ```kotlin
  if (existing.subjectPublicKey != subjectPublicKey) {
      return@immediate EnrollOutcome.Refused(EnrollRefusal.DEVICE_CONFLICT)
  }
  ```
  The legitimate device's enrollment is permanently blocked with HTTP 409 Conflict (`ERR_CONFLICT`).
- **Proof of Concept:**
  1. Alice is a newly hired executive whose device ID `dev-alice-uuid` was seen during local office mDNS discovery.
  2. Mallory, a junior member with an unused invite token, calls `/api/v1/enroll` specifying `deviceId = "dev-alice-uuid"` and `subjectPublicKey = MalloryKey`.
  3. The Hub registers `dev-alice-uuid` -> `MalloryKey`.
  4. Alice attempts to enroll with her official invite token using `deviceId = "dev-alice-uuid"` and `subjectPublicKey = AliceKey`.
  5. The Hub rejects Alice's enrollment with `EnrollRefusal.DEVICE_CONFLICT`. Alice cannot join the hub.
- **Guard Analysis:** No device ID reservation or pre-assignment mechanism exists in `InviteScope`. Device ID ownership is determined strictly by first-to-claim.
- **Remediation:**
  Support optional `boundDeviceId` in `InviteScope` so administrators can issue invites strictly locked to a specific device ID. Alternatively, generate `deviceId` server-side during enrollment rather than accepting arbitrary client strings, or allow administrators to resolve device ID conflicts through the console.

---

### SEC-D14-03: In-Memory Bearer Token Eviction Policy Allows Noisy Enrolled Clients to Prematurely Invalidate Active User Sessions
- **File & Line:** `server/src/main/kotlin/com/transfer/flash/server/auth/BearerTokenStore.kt:33-37`
- **Attacker Model:** A3 (Enrolled hub member)
- **CWE:** CWE-400: Uncontrolled Resource Consumption
- **CVSS v3.1 Score:** 3.1 (`CVSS:3.1/AV:N/AC:H/PR:L/UI:N/S:U/C:N/I:N/A:L`)
- **Vulnerability Description:**
  `BearerTokenStore` maintains active REST authentication tokens in memory with a hard limit of `maxTokens = 20_000`.
  When `entries.size >= maxTokens`, the store purges expired tokens. If the store remains full of active tokens, it evicts the 2,001 oldest tokens:
  ```kotlin
  if (entries.size >= maxTokens) {
      entries.entries.sortedBy { it.value.expiresAt }.take(maxTokens / 10 + 1).forEach { entries.remove(it.key) }
  }
  ```
  An enrolled member holding a valid device certificate can repeatedly call `/api/v1/auth/verify` up to their rate limit to mint thousands of active tokens. When the store reaches capacity, this forces the eviction of tokens belonging to other users across the organization, causing legitimate client REST requests to unexpectedly fail with HTTP 401 Unauthorized before their 15-minute validity expires.
- **Proof of Concept:**
  An enrolled device loops `/api/v1/auth/verify` creating thousands of tokens. As `maxTokens` is reached, existing valid tokens of other devices are evicted, causing their directory sync and file operations to fail with 401 until they perform a full challenge-response re-authentication.
- **Guard Analysis:** While per-device rate limiting exists on `/auth/verify` (`limits.verify`), multiple enrolled devices or compromised credentials can generate enough tokens to trigger the global 10% eviction threshold.
- **Remediation:**
  Enforce a per-device maximum active token limit in `BearerTokenStore` (e.g., maximum 5 active bearer tokens per `deviceId`), evicting only the older tokens of the *same* device rather than evicting tokens belonging to other devices globally.

---

## 4. Refuted / Rejected Hypotheses

### REJ-D14-01: Single-Use Enrollment Token Race Condition
- **Hypothesis:** Two concurrent enrollment requests presenting the same single-use invite token can both successfully enroll two different devices.
- **Refutation:** `IdentityStore.enroll` runs within an SQLite immediate transaction (`db.immediate { conn -> ... }`). In SQLite, `BEGIN IMMEDIATE` acquires a reserved write lock. The first request validates `invite.state == PENDING`, increments `uses = uses + 1`, and commits. The second concurrent request is blocked until the first commits, whereupon it re-reads `uses = 1`, evaluates `invite.state == USED`, and is rejected with `EnrollRefusal.INVITE_USED`.

### REJ-D14-02: Revoked Device Certificate Renewal Bypass
- **Hypothesis:** A revoked device can bypass revocation checks and obtain a fresh certificate by calling `/api/v1/enroll/renew`.
- **Refutation:** `EnrollmentService.renew()` explicitly calls `revocations.isRevoked(presented.serial, presented.deviceId)` on line 335. Furthermore, `store.renew` verifies that the device's registry status is `DeviceStatus.ACTIVE`; if revoked, it returns `RenewRefusal.NOT_ACTIVE` and rejects the renewal with HTTP 403.

### REJ-D14-03: Cross-Department IDOR via Client-Asserted Department in REST API
- **Hypothesis:** An enrolled device in Department A can read or modify Department B's groups or members by specifying `departmentId=dept-b` in query parameters or headers.
- **Refutation:** Per ADR-222, all REST directory endpoints resolve the caller's role and department strictly from the authenticated `HubPrincipal` (`principal.toActor()`). In `DirectoryRoutes.kt:45-48`, client-supplied department headers or parameters are discarded. `hideFromStrangers()` verifies whether the group is visible to the caller's department and throws `DirectoryNotFoundException` (HTTP 404) if it belongs to another department and the caller is not a member.
