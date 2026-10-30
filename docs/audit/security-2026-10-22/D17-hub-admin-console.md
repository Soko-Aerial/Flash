# Domain D17 — Hub Admin Console and Web Security

Audited Commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

---

## 1. Scope and Files Read

The following files implementing the Web Admin Console, authentication service, step-up authorization, anti-CSRF, and frontend DOM construction were audited:
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApiDirectory.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminConsoleWeb.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminWeb.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AuditLogger.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/ConsoleKeys.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/ConsoleScope.kt`
- `server/src/main/kotlin/com/transfer/flash/server/admin/StepUpAuth.kt`
- `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`
- `server/src/main/resources/admin-ui/index.html`
- `server/src/main/resources/admin-ui/js/dom.js`
- `server/src/main/resources/admin-ui/js/csv.js`
- `server/src/main/resources/admin-ui/js/stepup.js`
- `server/src/main/resources/admin-ui/js/stepup-key.js`
- `server/src/main/resources/admin-ui/js/app.js`

---

## 2. Checklist Answers

| Checklist Item | Finding / Status | Evidence |
|---|---|---|
| Login password hashing & timing attacks | Not Vulnerable | PBKDF2WithHmacSHA256 (600,000 iterations); constant-time dummy hash on missing user (`AdminAuthService.kt:372`). |
| Account & IP Lockout on failed logins | Enforced (Pre-reserved) | `AdminAuthService.kt:366,432-458` atomically reserves attempts before verification; locks 15 min after 5 fails. |
| TOTP seed storage, window, replay protection | Enforced | Seed stored via AEAD (`SecretBox.kt`); single-use step recorded in `admin_users.last_totp_step` (`AdminAuthService.kt:400`). |
| Recovery codes | Enforced | 10 single-use codes (SHA-256 hashed); consumed atomically (`AdminAuthService.kt:417-427`). |
| Session cookies (`HttpOnly`, `Secure`, `SameSite`) | Enforced | `AdminWeb.kt:116-128` sets `__Host-flash_admin_session` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/`. |
| CSRF on state-changing admin routes | Enforced (Triple layer) | `SameSite=Strict` cookie, `Sec-Fetch-Site` / `Origin` check, and mandatory `X-CSRF-Token` header (`AdminWeb.kt:50-68`). |
| CSP and Security Headers on admin UI | Enforced | Strict CSP (`default-src 'none'; script-src 'self'`), `X-Frame-Options: DENY`, `nosniff`, `no-referrer` (`AdminConsoleWeb.kt:31-50`). |
| DOM XSS in console UI | Not Vulnerable | `dom.js:1-55` forbids `innerHTML`/`outerHTML`/`eval`; text nodes created via `textContent`; strict attribute allow-list. |
| Step-up authorization key design | **VULNERABLE (SEC-D17-01)** | `StepUpAuth.kt:49-62,222-230` binds challenges to `adminUsername` rather than `sessionId`, permitting cross-session replay. |
| Approver phone co-signing flow | Enforced | `StepUpAuth.kt:118-134,188-204` verifies approver phone signature for dangerous actions; refuses fallback to browser key. |
| Setup token & first-run window | Protected | Stored owner-only in `setup-token.txt`; compared constant-time; zeroed and deleted on completion (`AdminAuthService.kt:217-253,342`). |
| Admin port (8444) & public tunnel isolation | Protected | Binds `127.0.0.1` by default; `AdminListenerPolicy.allows()` rejects `/admin` on public port unless explicitly opted in (`AdminWeb.kt:22`). |
| Scoped department admin (A9) authorization | Enforced | `ConsoleScope.kt:21-43` re-reads scope from DB on every request; enforced by route matrix (`DepartmentHeadScopeTest`). |
| Audit log hash chain v2 integrity | Enforced | Records chained with `prev_hash` + SHA-256; signed with ECDSA private key (`AuditLogger.kt:175-191,224-272`). |
| CSV export formula injection | Protected | Spreadsheet formula injection (`=`, `+`, `-`, `@`, `\t`, `\r`) neutralized by prepending `'` in both backend (`AuditLogger.kt:125`) and frontend (`csv.js:94`). |
| Open redirects | Not Vulnerable | Only static local paths in `LEGACY_REDIRECTS` are used (`AdminConsoleWeb.kt:169-177`). |

---

## 3. Confirmed Findings

### SEC-D17-01 — Step-Up Challenges Not Bound to Session ID, Permitting Cross-Session Replay
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A8 (Administrator-session thief with concurrent or stolen session)
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/admin/StepUpAuth.kt:49-62,222-230`, `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt:156-165`
- **CWE / standard:** CWE-384 (Session Fixation); CWE-287 (Improper Authentication); CVSS:3.1/AV:N/AC:H/PR:H/UI:R/S:U/C:H/I:H/A:H (6.4)
- **What is wrong:** `StepUpChallenge` records the administrator's username (`val adminUsername: String`), action, target, and nonce, but does NOT bind the challenge to the specific `sessionId` or session token that initiated it. When `StepUpAuth.authorize` is invoked during a protected operation, it only validates that `challenge.adminUsername == session.username`. If an administrator has multiple active sessions (or if an attacker holds a stolen session cookie for the same admin account), a step-up challenge initiated and approved by the legitimate administrator in Session A can be intercepted or reused by Session B to authorize a privileged action. Furthermore, when an administrator logs out of Session A or an admin session is revoked via `revokeSession`, pending verified challenges in `StepUpAuth` remain active in memory until their 5-minute TTL expires.
- **Impact:** An attacker who has obtained an admin session cookie can execute high-impact administrative actions (such as installing new delegation certificates, modifying server settings, or resetting credentials) by racing or intercepting an approved `challengeId` generated by the victim administrator in a concurrent session.
- **Evidence:**
  ```kotlin
  // StepUpAuth.kt:49-62
  class StepUpChallenge(
      val challengeId: String,
      val adminUsername: String,
      val deviceId: String,
      val action: String,
      val target: String,
      val nonce: String,
      val createdAtMs: Long,
      val expiresAtMs: Long,
      @Volatile var verified: Boolean = false,
      val approverDeviceId: String? = null,
      @Volatile var approved: Boolean = false,
  )

  // StepUpAuth.kt:222-230
  fun authorize(challengeId: String, adminUsername: String, action: String, target: String): Boolean {
      val challenge = challenges.remove(challengeId) ?: return false
      if (challenge.isExpired(clock())) return false
      return challenge.verified &&
          (!challenge.approvalRequired || challenge.approved) &&
          challenge.adminUsername == adminUsername && // Only matches username, not sessionId!
          challenge.action == action &&
          challenge.target == target.take(256)
  }
  ```
- **Why existing guards do not stop it:** `adminSession` verifies the session cookie, but `StepUpChallenge` has no awareness of which session created it.
- **Verifier result:** survived. Code inspection of `StepUpChallenge`, `createChallenge`, and `authorize` confirms that `sessionId` is omitted from challenge metadata and authorization checks.
- **Recommended fix:** Record `sessionId: String` in `StepUpChallenge` upon creation, and require `challenge.sessionId == session.sessionId` in `StepUpAuth.authorize`. Additionally, purge all outstanding challenges for `session.username` or `session.sessionId` upon logout or session revocation.
- **Related:** ADR-125, ADR-131 (Step-up authorization).

---

## 4. Refuted / Rejected Hypotheses

| ID | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D17-01 | DOM-based XSS via user-supplied names or announcement titles in the Admin Console UI | `dom.js:1-55` strictly enforces that all text content is rendered via `el.textContent = String(value)`. Inline styles, `on*` attributes, `innerHTML`, `outerHTML`, `eval`, and `document.write` are strictly forbidden by `dom.js` and blocked by CSP (`script-src 'self'`). |
| REJ-D17-02 | Cross-Site Request Forgery (CSRF) on administrative actions | `AdminWeb` enforces a three-tier defense: (1) `SameSite=Strict` and `__Host-` cookies; (2) `Sec-Fetch-Site: same-origin` / `Origin` check; (3) Mandatory `X-CSRF-Token` header on all non-GET requests matching the session's random CSRF token in constant time. |
| REJ-D17-03 | Timing attacks for username enumeration in Admin login | `AdminAuthService.authenticate` always performs a full PBKDF2 hash verification using `dummyHash` even when the username does not exist or the account is disabled, guaranteeing constant-time behavior. |
| REJ-D17-04 | Exposure of the Admin Console over the public reverse proxy / Cloudflare tunnel | By default, `adminOnPublicPort` is `false` and `adminHost` binds to `127.0.0.1`. Requests to `/admin` arriving on the public port are rejected with HTTP 404 Not Found by `AdminListenerPolicy.allows()`. |
