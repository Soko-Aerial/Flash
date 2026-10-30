# Domain D18 — Hub Cryptography and Secrets at Rest

Audited Commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

---

## 1. Scope and Files Read

The following files implementing hub cryptographic primitives, at-rest database encryption, key management, permissions, and configuration were audited:
- `server/src/main/kotlin/com/transfer/flash/server/auth/SecretBox.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/SecureFiles.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/DelegationManager.kt`
- `server/src/main/kotlin/com/transfer/flash/server/auth/HubKeyStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/storage/ServerDatabase.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt`
- `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`
- `server/src/main/kotlin/com/transfer/flash/server/KeyTool.kt`
- `core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/certs/HubStepUp.kt`

---

## 2. Checklist Answers

| Checklist Item | Finding / Status | Evidence |
|---|---|---|
| `data.key` generation & storage permissions | Protected | Generated via `SecureRandom` (32 bytes); stored Base64 in `data.key`; restricted to owner-only (`SecureFiles.kt:30-80`). |
| `data.key` rotation mechanism | **VULNERABLE (SEC-D18-02)** | No key rotation / re-encryption tool exists. Changing `data.key` breaks all existing TOTP seeds and queued messages. |
| AEAD usage for sealed rows & TOTP seeds | Correct (AES-256-GCM) | `SecretBox.kt:28-82` uses `AES/GCM/NoPadding`, 96-bit random nonce, 128-bit authentication tag, and UTF-8 AAD. |
| Nonce generation and uniqueness | Protected | Each encryption call allocates fresh 12-byte random nonce from `SecureRandom`. |
| AAD binding to row IDs and contexts | Enforced | TOTP bound to `"admin_users.totp_secret:$username"`; mailbox payload bound to length-prefixed `origin`, `target`, `msgId`. |
| Root / Delegate key handling & `--dev-embedded-root` | Protected | `ServerConfig.kt:101-103` rejects `--dev-embedded-root` unless `FLASH_ALLOW_INSECURE=true` is explicitly configured. |
| Delegation file integrity | Enforced | Loaded via `DelegationManager.kt`; root signature verified before accepting. |
| Signature algorithms & domain separation strings | Enforced | `HubStepUp.kt:12-45` uses length-prefixed `AuthCanonicalWriter` with unique domain tags (`flash-hub-stepup-v1`, `flash-hub-approval-v1`). |
| Constant-time comparisons | Enforced | `AdminAuthService.kt:286` compares tokens via `constantTimeEquals` / `MessageDigest.isEqual`. |
| KDF parameters (PBKDF2) | Enforced | PBKDF2WithHmacSHA256 with 600,000 iterations (`AdminAuthService.kt:792-799`). |
| RNG Source (`SecureRandom`) | Enforced | `java.security.SecureRandom` used exclusively across all cryptographic generators in `:server`. |
| SQLite file permissions & WAL leftovers | Protected | `ServerDatabase.kt:166-188` (`hardenFilesBeforeOpen`) pre-creates main DB file and restricts DB, WAL, and SHM to owner. |
| Secrets in CLI arguments / environment variables | **VULNERABLE (SEC-D18-01)** | `ServerConfig.kt:204-205` parses `setupToken` and `managementToken` from CLI arguments, exposing them in `/proc/*/cmdline`. |

---

## 3. Confirmed Findings

### SEC-D18-01 — Bootstrap Setup and Management Tokens Exposed via Command-Line Parameters
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A6 (Other local user or unprivileged process on the hub server host)
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt:204-205`
- **CWE / standard:** CWE-214 (Invocation of Process Control Mechanism with Sensitive Information); CWE-532 (Insertion of Sensitive Information into Log File / Process Arguments)
- **What is wrong:** `ServerConfig.parseCliArgs` parses `--setup-token` and `--management-token` directly from process command-line arguments. In Unix and Linux systems, command-line arguments are world-readable across process boundaries via `/proc/<pid>/cmdline` and commands such as `ps aux`. While `ServerConfig` supports reading the setup token from a file (`setup-token.txt`) or environment variables, allowing sensitive credentials to be passed on the command line exposes administrators to accidental credential disclosure to unprivileged users on the same host.
- **Impact:** An unprivileged user or monitoring daemon on the same server can capture the first-run administrator setup token or management bearer token and take over initial setup or read protected internal metrics.
- **Evidence:**
  ```kotlin
  // ServerConfig.kt:204-205
  setupToken = text("setup-token", "setupToken", envName = "FLASH_SETUP_TOKEN"),
  managementToken = text("management-token", "managementToken", envName = "FLASH_MANAGEMENT_TOKEN"),
  ```
- **Why existing guards do not stop it:** CLI argument parsing accepts `--setup-token=<value>` without checking whether the process is running on an OS with world-readable process listings.
- **Verifier result:** survived. Verified in `ServerConfig.kt`.
- **Recommended fix:** Deprecate command-line argument passing for tokens; require them to be supplied via the owner-restricted file or environment variables only.
- **Related:** `D12`, `D17`.

---

### SEC-D18-02 — Absence of Cryptographic Key Rotation Mechanism for `data.key`
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A4, A8 (Compromised or expiring data key)
- **Component:** `server/src/main/kotlin/com/transfer/flash/server/auth/SecretBox.kt:98-122`, `server/src/main/kotlin/com/transfer/flash/server/HubServices.kt:249`
- **CWE / standard:** CWE-324 (Use of a Key Past its Expiration Date); CWE-320 (Key Management Errors)
- **What is wrong:** `SecretBox` uses `data.key` (AES-256-GCM) to encrypt administrator TOTP secrets (`admin_users.totp_secret`) and queued mailbox envelopes (`mailbox_envelopes.payload`). There is no operational command, script, or migration utility to rotate `data.key`. If `data.key` is compromised or needs scheduled rotation for regulatory compliance, replacing the key causes all existing TOTP seeds and queued messages to permanently fail authentication (`AEADBadTagException`). All administrators are locked out of TOTP verification and require manual console break-glass resets.
- **Impact:** Inability to rotate data-at-rest encryption keys without operational disruption, data loss of in-flight messages, and mandatory credential invalidation.
- **Evidence:**
  `SecretBox.loadOrCreate` loads a single key. There is no `re-encrypt` or `rotate-data-key` command in `KeyTool.kt` or `Main.kt`.
- **Why existing guards do not stop it:** Quarantining (ADR-223) marks unreadable mailbox rows as quarantined rather than crashing, but the data remains unrecoverable.
- **Verifier result:** survived. Codebase contains no re-encryption facility for `data.key`.
- **Recommended fix:** Add a `keytool rotate-data-key --old-key <path> --new-key <path>` command that decrypts and re-encrypts all rows in `admin_users` and `mailbox_envelopes` within a single transaction.
- **Related:** ADR-130, ADR-223.

---

## 4. Refuted / Rejected Hypotheses

| ID | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D18-01 | AES-GCM Nonce Reuse in `SecretBox` | Every `seal` and `sealBytes` call independently generates a fresh 96-bit random nonce using `SecureRandom`. The probability of a collision in 96 bits under realistic hub data volumes is negligible ($< 2^{-32}$). |
| REJ-D18-02 | Cross-Row Cut-and-Paste of Ciphertext | `SecretBox` strictly binds context strings via `cipher.updateAAD()`. Mailbox payloads bind length-prefixed `origin`, `target`, and `msgId`. TOTP secrets bind `admin_users.totp_secret:$username`. Any transplant fails with `AEADBadTagException`. |
| REJ-D18-03 | Insecure File Permissions on SQLite Database and WAL Files | `ServerDatabase.kt:166-188` (`hardenFilesBeforeOpen`) pre-creates `flash-hub.db` and enforces owner-only permissions (0600 on POSIX, owner-only ACL on Windows) on the directory, main DB, `-wal`, and `-shm` before Hikari initializes. |
| REJ-D18-04 | Accidental Production Enablement of `--dev-embedded-root` | `ServerConfig.kt:101-103` explicitly enforces `require(!devEmbeddedRoot || allowInsecure)`. Attempting to start with `--dev-embedded-root` when `FLASH_ALLOW_INSECURE` is not set immediately aborts startup with an `IllegalArgumentException`. |
