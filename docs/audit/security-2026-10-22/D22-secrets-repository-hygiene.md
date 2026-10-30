# Domain D22 — Secrets and Repository Hygiene

## 1. Domain Overview
This domain performs an exhaustive scan of the repository working tree and full git commit history for hardcoded secrets, private keys, API tokens (`ghp_`, `AKIA`, `xox`, `tvly-`), passwords, setup tokens, TOTP seeds, recovery codes, debug signing configurations, and sensitive media/artifacts.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Secrets Scan Methodology & Findings Summary

| Secret Category | Target Pattern / Extension | Search Scope | Result | Status |
|---|---|---|---|---|
| Signing Keystores & Keys | `*.jks`, `*.keystore`, `*.p12`, `*.pk8`, `*.pem`, `*.key` | Working tree & git log (`--diff-filter=A`) | Zero private keys or keystore binaries committed. | PASS |
| Access Tokens | `ghp_`, `AKIA`, `xox`, `tvly-`, JWT | Full git commit history (`git grep` across all commits) | Only pattern citations in security prompts; zero actual tokens found. | PASS |
| Passwords & Seeds | `storePassword=`, `keyPassword=`, TOTP seeds, recovery codes | Tree & git commit log | Template placeholders only in `keystore.properties.example`. | PASS |
| Environment & Local Config | `local.properties`, `.env`, `keystore.properties` | Full git commit history | Zero occurrences committed. Properly ignored. | PASS |
| Media & Screenshots | Root `*.png`, admin console mockups | Working tree | UI screenshots containing test recovery codes & QR codes committed (`SEC-D22-01`). | DEFECT |
| Gitignore Completeness | `.gitignore` rules for crypto key extensions | `.gitignore` | Omits `*.pem`, `*.key`, `*.p12`, `*.pk8` (`SEC-D22-02`). | DEFECT |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Private keys in repository (`.pk8`, `.pem`, `.jks`, `.p12`) | PASS | None committed in working tree or git history. |
| Configuration files with credentials (`local.properties`, `.env`) | PASS | Verified absent from git history. |
| Cloud & service API tokens (`ghp_`, `AKIA`, `tvly-`, etc.) | PASS | Zero active credentials committed. |
| Passwords and recovery seeds in code | PASS | Server code hashes passwords via PBKDF2; no default admin passwords committed. |
| Developer tool key security (`tools/tavily_search.py`) | PASS | API key file `.tavily_api_key` properly ignored in `tools/.gitignore`. |
| Repository hygiene (.gitignore rules) | DEFECT | Root `.gitignore` ignores `*.jks` and `*.keystore`, but omits `*.pem`, `*.key`, `*.p12`, and `*.pk8` (`SEC-D22-02`). |
| Sensitive artifacts in documentation | DEFECT | UI design screenshots display valid format recovery codes and authenticator QR codes (`SEC-D22-01`). |

---

## 4. Files Audited
- `.gitignore`
- `tools/.gitignore`
- `media-downloader-main/keystore.properties.example`
- `tools/build-release.ps1`
- `tools/tavily_search.py`
- `docs/ui/hub-admin-console/*.png`
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt`
- `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`

---

## 5. Findings

### SEC-D22-01 — Admin Console Documentation Screenshots Expose TOTP Authenticator Seeds and Recovery Codes
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A7, A10 (Public repository observer / code leak reader)
- **Component:** `docs/ui/hub-admin-console/setup-3-authenticator-light-1440.png`, `docs/ui/hub-admin-console/setup-4-recovery-light-1440.png`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information to an Unauthorized Actor; CWE-532: Insertion of Sensitive Information into Log File
- **What is wrong:** The documentation directory `docs/ui/hub-admin-console/` contains full-resolution browser screenshot artifacts (`setup-3-authenticator-light-1440.png` and `setup-4-recovery-light-1440.png`) captured during admin console test walkthroughs. These images display fully readable authenticator QR codes, the underlying Base32 TOTP secret string, and 10 plaintext recovery codes. If the repository is made public or shared with third parties, any persistent test or staging hub instance retaining this setup state is immediately compromised.
- **Impact:** Information leakage: exposure of authentication secrets and recovery codes if the captured instance is accessible or reused.
- **Evidence:** Inspection of `docs/ui/hub-admin-console/setup-3-authenticator-light-1440.png` reveals an unredacted TOTP setup QR code and secret text.
- **Why existing guards do not stop it:** Binary images are not scanned by standard text-based linters or git hooks.
- **Verifier result:** survived. Confirmed screenshots exist in the repository with unblurred credential fields.
- **Recommended fix:** Redact or blur QR codes, secret strings, and recovery codes in all documentation screenshot assets using generic placeholder text (e.g. `XXXX-XXXX-XXXX`).
- **Related:** `SEC-D17-01`.

---

### SEC-D22-02 — Root `.gitignore` Omits Common Cryptographic Key File Extensions (`*.key`, `*.pem`, `*.p12`, `*.pk8`)
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A10, A7 (Accidental committer / repository observer)
- **Component:** `.gitignore:25-33`
- **CWE / standard:** CWE-552: Files or Directories Accessible to External Parties
- **What is wrong:** The repository `.gitignore` explicitly ignores `*.jks` and `*.keystore`, but omits standard key extensions widely produced by OpenSSL, Java keytool, and Ktor server tooling: `*.key`, `*.pem`, `*.p12`, `*.pk8`, and `*.crt`. If an operator or developer generates TLS certificates, server encryption keys (`data.key`), or signing keypairs outside `data/` in the working directory, git marks them as untracked files, creating a significant risk of accidental commit via `git add .`.
- **Impact:** Risk of accidental private key and TLS credential leakage into version control.
- **Evidence:** `.gitignore` contains only:
  ```
  dist/
  keystore.properties
  *.jks
  *.keystore
  ```
- **Why existing guards do not stop it:** Git does not ignore files unless an explicit matching pattern exists in `.gitignore`.
- **Verifier result:** survived. Confirmed `.gitignore` lacks `*.pem`, `*.key`, `*.p12`, `*.pk8` entries.
- **Recommended fix:** Append standard secret and key patterns to `.gitignore`:
  ```gitignore
  *.key
  *.pem
  *.p12
  *.pk8
  *.crt
  *.der
  ```
- **Related:** `SEC-D18-01`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D22-01: Active Cloud / Service API Tokens in Git History**: Scanned full git commit history across all branches using `git grep` and commit diff filters for `ghp_`, `AKIA`, `xox`, `tvly-`. Zero active credentials exist in commit history.
- **REJ-D22-02: Committed Android / JVM Signing Keystores**: Searched git history for `.jks` and `.keystore` additions. No release keystores or private certificates were ever committed.
- **REJ-D22-03: Committed Production Database Files**: Searched git history for SQLite databases (`*.db`, `*.sqlite`). Zero database binaries exist in git tracking; `data/` is strictly ignored.
