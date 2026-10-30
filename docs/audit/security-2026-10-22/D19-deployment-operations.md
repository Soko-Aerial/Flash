# Domain D19 — Deployment, Operations and Configuration

Audited Commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`

---

## 1. Scope and Files Read

The following deployment guides, Docker configurations, operational scripts, and management endpoints were audited:
- `docs/deploy/HUB-SERVER-DEPLOYMENT.md`
- `tools/hub-test-server/docker-compose.yml`
- `tools/hub-test-server/README.md`
- `tools/hub-test-server/up.sh`
- `tools/hub-test-server/status.sh`
- `tools/hub-test-server/deploy.ps1`
- `server/Dockerfile`
- `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`
- `server/src/main/kotlin/com/transfer/flash/server/observability/ManagementGuard.kt`
- `server/src/main/kotlin/com/transfer/flash/server/observability/LogSanitizer.kt`
- `server/src/main/kotlin/com/transfer/flash/server/storage/Migrations.kt`

---

## 2. Checklist Answers

| Checklist Item | Finding / Status | Evidence |
|---|---|---|
| Container User & Capabilities | **VULNERABLE (SEC-D19-01)** | `tools/hub-test-server/docker-compose.yml` runs container as `root:root` (UID 0), making `/data` root-owned. (`server/Dockerfile` uses `USER flash:flash`). |
| Published Ports | Protected | Ports bound strictly to loopback (`127.0.0.1:18443` and `127.0.0.1:18444`). Public traffic routes through Cloudflare tunnel. |
| Trusted Proxy Settings (`FLASH_TRUSTED_PROXIES`) | **VULNERABLE (SEC-D19-02)** | Entire subnet `172.31.250.0/24` trusted. Any container on `hubnet` can forge `X-Forwarded-For`. |
| TLS Modes (`proxy`, `self`, `none`) | Enforced | `proxy` requires trusted proxy; `self` terminates TLS; `none` refuses startup without `FLASH_ALLOW_INSECURE=true`. |
| Insecure Flags & Warnings | Protected | `--dev-embedded-root` and `tlsMode=none` require `allowInsecure`; logs loud warning at startup (`Main.kt:80`). |
| Health & Metrics endpoints | Protected | `/health/detail` and `/metrics` guarded by `DefaultManagementGuard.kt:29-38` (requires Bearer token or direct loopback). |
| Log sanitization (CRLF log injection) | Protected | `LogSanitizer.clean()` cleans device IDs, URIs, and reasons before logging (`LogSanitizer.kt:8`). |
| Schema Migration & Downgrade Safety | Protected | `Migrations.kt:65-69` detects schema downgrade and refuses startup with `MigrationFailedException`. |

---

## 3. Confirmed Findings

### SEC-D19-01 — Docker Compose Container Runs as Root, Causing Root Ownership of Persistent State Files
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A6 (Host user / container escape threat model)
- **Component:** `tools/hub-test-server/docker-compose.yml:14-28`
- **CWE / standard:** CWE-250 (Execution with Unnecessary Privileges); CIS Docker Benchmark 4.1 (Ensure that a user for the container has been created)
- **What is wrong:** In `tools/hub-test-server/docker-compose.yml`, the `hub` service uses `image: eclipse-temurin:21.0.5_11-jre-alpine` without specifying a non-root `user:`. The Java JVM process executes with full `root` (UID 0) privileges inside the container namespace. Consequently, all persistent files created in the mounted `./data` directory (including `flash-hub.db`, `data.key`, and `delegation.cert`) are created with `root:root` ownership on the host filesystem. While the repository's production `server/Dockerfile` defines an unprivileged `flash:flash` (UID 10001) user, the actual deployment configuration in `tools/hub-test-server` bypasses this protection.
- **Impact:** Violates the principle of least privilege. In the event of a container breakout or remote code execution defect, the compromised process has root access. Host operators must use `sudo` to inspect, back up, or modify `./data/`.
- **Evidence:**
  `tools/hub-test-server/docker-compose.yml`:
  ```yaml
  hub:
    image: eclipse-temurin:21.0.5_11-jre-alpine
    restart: unless-stopped
    working_dir: /app
    entrypoint:
      - java
      ...
  ```
  Note `README.md:32`: "`data/` is root-owned, so the copy uses `sudo`".
- **Why existing guards do not stop it:** No `user:` directive is specified in the compose file.
- **Verifier result:** survived. Verified in `docker-compose.yml`.
- **Recommended fix:** Specify `user: "10001:10001"` in `docker-compose.yml` and pre-chown `./data` to UID 10001 in `up.sh`.
- **Related:** `server/Dockerfile:22-49`.

---

### SEC-D19-02 — Overly Broad Trusted-Proxy CIDR Subnet Allows Internal IP Spoofing
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A5, A6 (Co-located container or process on the Docker bridge network)
- **Component:** `tools/hub-test-server/docker-compose.yml:40`
- **CWE / standard:** CWE-290 (Authentication Bypass by Spoofing); CWE-345 (Insufficient Verification of Data Authenticity)
- **What is wrong:** `docker-compose.yml` sets `FLASH_TRUSTED_PROXIES: "${HUB_NETWORK_CIDR:-172.31.250.0/24}"`. This instructs the hub's `ClientAddressResolver` to trust `X-Forwarded-For` headers from any IP address within the entire `172.31.250.0/24` subnet. If another container or sidecar is attached to `hubnet` (or if an attacker gains control of a container on the bridge network), it can connect to the hub on port 8443 and forge arbitrary `X-Forwarded-For` IP headers.
- **Impact:** An attacker on the bridge network can spoof client IP addresses to bypass per-IP rate limiters (`TokenBucketLimiter`), evade IP-based lockouts, or intentionally trigger IP lockouts against legitimate corporate external IPs.
- **Evidence:**
  `docker-compose.yml:40`:
  ```yaml
  FLASH_TRUSTED_PROXIES: "${HUB_NETWORK_CIDR:-172.31.250.0/24}"
  ```
  `ClientAddress.kt:45-53`:
  ```kotlin
  fun clientAddress(remoteAddress: String, forwardedFor: String?): String {
      if (!isTrusted(remoteAddress) || forwardedFor.isNullOrBlank()) return remoteAddress
      ...
  }
  ```
- **Why existing guards do not stop it:** `ClientAddressResolver` trusts all addresses matching `FLASH_TRUSTED_PROXIES`.
- **Verifier result:** survived. Confirmed the compose file trusts the full /24 subnet.
- **Recommended fix:** Pin the trusted proxy address to the specific static IP assigned to the reverse proxy/tunnel container rather than the entire subnet.
- **Related:** `ClientAddress.kt`.

---

## 4. Refuted / Rejected Hypotheses

| ID | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D19-01 | Unauthenticated access to `/health/detail` and `/metrics` over the public tunnel | Both routes are guarded by `DefaultManagementGuard.kt:29-38`. Requests originating from non-loopback addresses without `Authorization: Bearer <managementToken>` receive HTTP 403 Forbidden. |
| REJ-D19-02 | Public internet exposure of hub and admin ports | `docker-compose.yml:57-58` explicitly binds host ports to loopback (`127.0.0.1:18443` and `127.0.0.1:18444`), preventing external access from the host's public interfaces. |
| REJ-D19-03 | CRLF Log Injection via User Inputs | `LogSanitizer.clean()` is systematically invoked on all dynamic parameters (device IDs, error reasons, URIs) before logging, stripping newline and control characters. |
| REJ-D19-04 | Database corruption during schema downgrade | `Migrator.migrate()` explicitly checks `if (stored > newest) throw MigrationFailedException(...)`, refusing to run an older binary against a newer schema. |
