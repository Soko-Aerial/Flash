# Flash Security Audit 2026-10-22 — Attack Chains

## 1. Overview
This document analyzes end-to-end exploit chains where individual vulnerabilities—often rated Medium or Low in isolation—combine to yield severe systemic compromise (arbitrary remote code execution, unauthenticated LAN MITM, hub takeover, or enterprise-wide identity theft).

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Top Attack Chains

### Chain 1: Unauthenticated LAN Impersonation, Pre-Emptive Key Poisoning, and Arbitrary Ingestion
- **Attacker Model:** **A1** (Unauthenticated device on the same Wi-Fi / LAN network)
- **Primary Impact:** Complete impersonation of trusted peers, denial of service against honest contacts, and unsolicited phishing/file delivery without user interaction.
- **Vulnerabilities Chained:**
  1. `SEC-D02-01` (Critical): `TofuX509TrustManager` iterates across the entire presented certificate chain and accepts if *any* certificate matches the target pin (`certs.any { checkCertificate(it) }`).
  2. `SEC-D02-02` (High): Inbound TLS connections immediately persist the presenter's SPKI fingerprint into `FlashTrustStore` under TOFU without user consent or pairing completion.
  3. `SEC-D05-01` (High): Inbound 1:1 direct chat text frames (`FLASH_CHAT_TEXT`) in `RealFlashChatRepository` are processed and saved without verifying `isTrustedPeer()` or `PeerTrustPolicy.mayChat`.
  4. `SEC-D07-03` (Medium): Android `Flash.kt` auto-accepts inbound file transfer requests from unauthenticated LAN strangers without checking pairing status.
- **Step-by-Step Scenario:**
  1. Attacker A1 joins the local Wi-Fi network and discovers Alice's phone via mDNS (`SEC-D23-01`).
  2. A1 connects to Alice on TCP port 45822. A1 presents an arbitrary newly generated identity key.
  3. Alice's node processes the inbound connection. Before any pairing flow begins, `TofuPinVerifier.kt` immediately writes A1's unverified fingerprint into Alice's SQLite trust store as the pinned key for A1's claimed device ID (`SEC-D02-02`). If A1 claims the device ID of an honest peer Bob who has not connected yet, Bob's device ID is pre-emptively poisoned. When the real Bob connects later, Alice's trust manager rejects Bob with a fatal `KEY_CHANGED` SSLException.
  4. If Bob is already pinned, A1 generates an ephemeral leaf certificate and appends Bob's public certificate to the TLS certificate chain. Because `TofuX509TrustManager` checks if *any* certificate in the chain matches the pin (`SEC-D02-01`), Alice's TLS handshake succeeds.
  5. Once connected, A1 sends unsolicited chat messages containing malicious links. Alice's repository ingests and renders the messages (`SEC-D05-01`).
  6. A1 dispatches an inbound file transfer. `Flash.kt` auto-accepts and writes the file to device storage (`SEC-D07-03`).
- **Remediation That Breaks the Chain:**
  - Fix `SEC-D02-01`: Verify only `chain[0]` (the leaf certificate) in `TofuX509TrustManager`.
  - Fix `SEC-D02-02`: Do not persist TOFU pins on inbound connections until Pairing v2 completes.
  - Fix `SEC-D05-01`: Enforce `PeerTrustPolicy.mayChat` on all inbound wire frames.

---

### Chain 2: Compromised Hub Server Colleague Impersonation and Fleet-Wide Surveillance
- **Attacker Model:** **A4** (Malicious or compromised hub operator)
- **Primary Impact:** Invisible man-in-the-middle of corporate communications, colleague identity theft on local office networks, and real-time social graph surveillance.
- **Vulnerabilities Chained:**
  1. `SEC-D01-04` (Medium): Hub server delegate private key stored in plaintext Base64 on disk (`data/delegate.key`).
  2. `SEC-D14-01` (Medium): Hub server trusts first-run delegation without pinning, permitting TOFU root key hijacking.
  3. `SEC-D27-01` (High): `RealOrgPeerResolver` grants direct LAN capabilities (`mayChat`, `mayRing`, `mayReact`) to any peer whose key matches a hub-signed directory statement without out-of-band verification.
  4. `SEC-D23-02` (Low): Real-time conversational metadata, typing indicators, and timestamps exposed in unsealed relay frames.
  5. `SEC-D15-02` (Medium): Hub relay envelopes lack monotonic sequence counters and freshness windows.
- **Step-by-Step Scenario:**
  1. An adversary compromises the hub host machine and reads the unencrypted delegate signing key from `data/delegate.key` (`SEC-D01-04`).
  2. Using the delegate key, A4 creates synthetic `OrgMemberStatement` entries in the organization directory, substituting an attacker-controlled public key for target employees (e.g. CEO or CFO) (`SEC-D27-01`).
  3. Employees' devices sync the directory. The signatures verify successfully against the enrolled Hub root key.
  4. When an employee connects to the CFO over the office Wi-Fi, the attacker intercepts or establishes the connection. The employee's client checks `OrgPeerResolver`, finds the attacker's key in the verified snapshot, and establishes an encrypted TLS session (`mayChat = true`, `mayRing = true`). The employee communicates directly with the attacker under the CFO's identity.
  5. Concurrently, the attacker monitors unsealed typing packets on the relay to map active conversations (`SEC-D23-02`) and replays past envelopes to manipulate conversation state (`SEC-D15-02`).
- **Remediation That Breaks the Chain:**
  - Fix `SEC-D01-04`: Encrypt the delegate private key on disk using `SecretBox` derived from `data.key`.
  - Fix `SEC-D27-01`: Implement a cryptographic Key Transparency Log (ADR S-15) and visually badge directory-only peers as "Unverified Colleague" until confirmed out-of-band.

---

### Chain 3: Local File Sandbox Escape to Host Code Execution via Malicious Swarm Distribution
- **Attacker Model:** **A2** / **A6** (Malicious group member or rogue swarm peer)
- **Primary Impact:** Arbitrary file overwrite outside the application download directory, leading to host code execution on Desktop.
- **Vulnerabilities Chained:**
  1. `SEC-D08-01` (High): `RosterGroupGate` permits vouched group members to download and upload swarm files, bypassing the documented PAIRED security boundary.
  2. `SEC-D07-01` (High): Desktop & dev console path containment checks `destDir` rather than resolving canonical roots, allowing symlink and junction escapes.
  3. `SEC-D04-01` (High): `FlashPathSanitizer` omits Unicode Bidi formatting control characters (`\u202E`), enabling right-to-left file name spoofing.
  4. `SEC-D13-01` (High): Desktop engine logs chat bodies, transfer metadata, and file paths to world-readable `desktop.log`.
- **Step-by-Step Scenario:**
  1. Attacker A2 gains admission to a group via voucher invitation (`SEC-D08-01`).
  2. A2 distributes a malicious executable camouflaged with Unicode Bidi overrides (`test\u202Ecod.exe` rendered in UI as `testexe.doc`) (`SEC-D04-01`).
  3. When transmitting file manifests or swarm pieces, A2 specifies a relative path traversing through pre-existing filesystem symlinks or junctions (`SEC-D07-01`).
  4. `DesktopEngine.kt:1813` verifies the file against `destDir.canonicalPath`, but because path resolution handles symlinks inconsistently across operating systems, the file is written outside `Downloads` directly into the user's startup folder (`~/.config/autostart/` or `Start Menu\Programs\Startup`).
  5. Upon the user's next login, the attacker's binary executes with user privileges.
- **Remediation That Breaks the Chain:**
  - Fix `SEC-D07-01`: Verify that the fully canonicalized destination path starts with the canonical root folder.
  - Fix `SEC-D04-01`: Strip all Unicode formatting and Bidi override control code points in `FlashPathSanitizer`.
  - Fix `SEC-D08-01`: Restrict swarm upload and distribution strictly to `PAIRED` members.

---

### Chain 4: Admin Console Privilege Escalation via Step-Up Cross-Session Replay and SQL Injection
- **Attacker Model:** **A8** / **A9** (Scoped department administrator or session token stealer)
- **Primary Impact:** Unauthorized full administrator privileges, execution of restricted management commands, and database query manipulation.
- **Vulnerabilities Chained:**
  1. `SEC-D17-01` (Medium): `StepUpChallenge` is bound only to `adminUsername`, allowing cross-session reuse across multiple browser logins.
  2. `SEC-D16-01` (Medium): `AnnouncementStore.kt` dynamically interpolates caller department strings into SQL queries instead of using prepared statement placeholders.
  3. `SEC-D18-01` (Low): Setup tokens and management tokens exposed via command-line arguments in `/proc/<pid>/cmdline`.
- **Step-by-Step Scenario:**
  1. An attacker holding a scoped department administrator account (A9) or a stolen session cookie (A8) logs into the admin console.
  2. When the primary administrator completes a Step-Up authentication challenge (e.g. confirming a sensitive deletion or key rotation), the challenge is marked approved in `StepUpAuth`.
  3. Because `StepUpAuth` indexes approved challenges by `adminUsername` rather than `sessionId` (`SEC-D17-01`), the attacker injects `X-Step-Up: <challengeId>` on their concurrent session. The server accepts the token, elevating the attacker's session to execute high-privilege operations.
  4. The attacker accesses `AnnouncementStore` search endpoints, supplying crafted department strings with SQL meta-characters (`SEC-D16-01`). Because the string is concatenated directly into `$visibilityClause`, the attacker executes arbitrary SQLite queries to read sensitive database records.
- **Remediation That Breaks the Chain:**
  - Fix `SEC-D17-01`: Bind `StepUpChallenge` strictly to the initiating `sessionId` and destroy it on session logout.
  - Fix `SEC-D16-01`: Parameterize all SQL queries using prepared statement `?` placeholders.
