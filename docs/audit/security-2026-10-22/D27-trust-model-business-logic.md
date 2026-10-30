# Domain D27 — Trust Model and Business-Logic Abuse

## 1. Domain Overview
This domain evaluates Flash's trust model architecture, capability gating truth tables (`PeerTrustPolicy`), the interactions between the three trust roots (PAIRED, ORG, and TOFU), department visibility controls, and business logic authorization across peer mesh and enterprise hub surfaces.

Audited commit: `75eda19b018948c00947bc08d7288c2d3a0ae6ce`.

---

## 2. Capability Gate Truth Table

Audited against `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicy.kt`:

| Capability Predicate | Intended Scope | PAIRED=T, ORG=T | PAIRED=T, ORG=F | PAIRED=F, ORG=T | PAIRED=F, ORG=F | Production Call Sites |
|---|---|:---:|:---:|:---:|:---:|---|
| `mayChat` | 1:1 direct chat, receipts, typing | TRUE | TRUE | TRUE | FALSE | `HubChatBridge:575` (LAN direct text is UNGATED, `SEC-D05-01`). |
| `mayReact` | Direct 1:1 emoji reactions | TRUE | TRUE | TRUE | FALSE | `RealFlashChatRepository:6115` |
| `mayRing` | 1:1 Audio/Video Call Ringing | TRUE | TRUE | TRUE | FALSE | `DiscoveryEngineHolder:1640`, `DesktopEngine:1540` |
| `mayPushToTalk` | Push-to-Talk immediate audio | TRUE | TRUE | **FALSE** | FALSE | Gated on `isTrusted` (PAIRED only) in PTT engine. |
| `mayCreateGroupWith` | Local ad-hoc group creation | TRUE | TRUE | **FALSE** | FALSE | `RealFlashChatRepository:2289, 3475` |
| `mayAddGroupMember` | Local ad-hoc group invite | TRUE | TRUE | **FALSE** | FALSE | `RealFlashChatRepository:2394, 3502` |
| `mayAdministerGroup` | Group settings & kick | TRUE | TRUE | **FALSE** | FALSE | `RealFlashChatRepository:5623` |
| `mayServeSwarm` | Swarm chunk upload | TRUE | TRUE | **FALSE** | FALSE | `RealFlashChatRepository:3191, 3878` (Bypassed by vouched members, `SEC-D08-01`). |
| `mayVouch` | Vouching for third-party keys | TRUE | TRUE | **FALSE** | FALSE | Enforced strictly for PAIRED peers. |

---

## 3. Checklist & Audit Questions

| Item | Status | Notes |
|---|---|---|
| Truth table implementation accuracy | PASS | Code in `PeerTrustPolicy.kt` strictly matches documented security model. |
| ORG overwriting PAIRED pins | PASS | `OrgPeerResolver.kt:288` strictly prohibits replacing PAIRED or VOUCHED pins (`REJ-D27-01`). |
| Compromised Hub Operator (A4) MITM on LAN | DEFECT | Hub operator can substitute colleague public keys in directory statements, enabling MITM on LAN for unpaired peers (`SEC-D27-01`). |
| Offline Org Trust grace period duration | DEFECT | Offline lease accepts up to 365 days grace, permitting revoked members continued LAN access (`SEC-D27-02`). |
| PTT audio protection from strangers/colleagues | PASS | PTT strictly requires `isPaired == true` (`REJ-D27-02`). |
| Group administration authorization | PASS | Administrative group actions strictly require out-of-band PAIRED trust (`REJ-D27-03`). |
| Direct 1:1 text gating | DEFECT | Inbound direct chat text on LAN is completely ungated by `mayChat` (`SEC-D05-01`). |

---

## 4. Files Audited
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicy.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `docs/security.md`
- `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md`

---

## 5. Findings

### SEC-D27-01 — Compromised Hub Operator (A4) Can Impersonate Unpaired Colleagues on LAN via Synthetic Directory Statements
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** A4 (Compromised hub operator possessing valid delegation signing key)
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/RealOrgPeerResolver.kt:259-291`, `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:308-340`
- **CWE / standard:** CWE-295: Improper Certificate Validation; CWE-322: Key Exchange without Entity Authentication; CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:N (6.5)
- **What is wrong:** Flash's trust model grants organization colleagues (`isOrg == true`) the capability to initiate direct 1:1 chats (`mayChat`), send reactions (`mayReact`), and ring devices (`mayRing`) without prior out-of-band pairing. A colleague's identity is authenticated solely by matching the TLS leaf certificate against public keys recorded in the organization directory snapshot signed by the Hub's delegate key. In the absence of a tamper-evident Key Transparency Log or gossip verification, a compromised hub operator or an attacker holding a valid delegation key can generate synthetic member statements substituting an attacker-controlled public key for any legitimate colleague (e.g. an executive or finance officer). When the victim communicates with that colleague on the LAN, the client verifies the attacker's key against the hub-signed directory snapshot and establishes an encrypted session directly with the attacker without displaying any warning or requiring out-of-band verification.
- **Impact:** Undetected man-in-the-middle and impersonation of any unpaired colleague by a compromised hub server.
- **Evidence:** `RealOrgPeerResolver.kt:274-275`: If `storedPin` matches `peer.keyFingerprint` (or if no pin exists and the snapshot supplies it), `bindingOk = true` and `resolve()` returns `OrgPeer`. The victim has no cryptographic mechanism to verify that the colleague actually generated that keypair without out-of-band Pairing v2.
- **Why existing guards do not stop it:** `OrgPeerResolver` trusts the hub's signed statements. While PAIRED pins cannot be overwritten, contacts that have never undergone Pairing v2 have no PAIRED pin.
- **Verifier result:** survived. Code verification confirms that for any colleague without a pre-existing PAIRED pin, the hub-signed statement is authoritative.
- **Recommended fix:**
  1. Implement a verifiable, append-only Key Transparency Log (ADR S-15).
  2. Visually distinguish "Verified Colleague" (out-of-band paired) from "Unverified Colleague" (directory-only) in the UI, requiring explicit user confirmation before accepting attachments or sensitive calls from unverified colleagues.
- **Related:** `SEC-D01-04`, `SEC-D14-01`.

---

### SEC-D27-02 — Excessive Offline Org Trust Grace Period (Up to 365 Days) Enables Persistent Access by Revoked Devices
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A3 (Revoked employee / compromised device on corporate Wi-Fi)
- **Component:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/RealOrgPeerResolver.kt:148, 197`, `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt:393-396`
- **CWE / standard:** CWE-613: Insufficient Session Expiration; CWE-287: Improper Authentication; CVSS:3.1/AV:A/AC:H/PR:L/UI:N/S:U/C:H/I:H/A:N (5.8)
- **What is wrong:** `OrgDirectorySync.kt:393-396` allows the hub server to configure `offlineGraceDays` up to 365 days (`DirectorySyncDto.MAX_OFFLINE_GRACE_DAYS = 365`). In `RealOrgPeerResolver.kt:197`, this duration is used directly to compute lease validity: `OrgLease.Valid(untilMs = lastSyncSuccessMs + graceMs)`. If an organization configures a lengthy grace period, and a rogue or terminated employee has their device revoked by the administrator, legitimate colleagues who remain offline (or are traveling without hub connectivity) will continue to trust the revoked colleague's device on local Wi-Fi networks for up to a full year without querying the hub for revocations.
- **Impact:** Extended window of vulnerability where revoked colleagues retain unauthorized communication capabilities with offline peers.
- **Evidence:**
  `RealOrgPeerResolver.kt:197`:
  ```kotlin
  graceDays = syncState.offlineGraceDays.coerceIn(1, 365)
  ```
  `OrgDirectorySync.kt:393-396`:
  ```kotlin
  val clampedGraceDays = responseOfflineGraceDays.coerceIn(
      DirectorySyncDto.MIN_OFFLINE_GRACE_DAYS,
      DirectorySyncDto.MAX_OFFLINE_GRACE_DAYS,
  )
  ```
- **Why existing guards do not stop it:** Offline validation deliberately avoids network calls. A 365-day ceiling is excessively long for security-critical enterprise trust.
- **Verifier result:** survived. Verified that `offlineGraceDays` is accepted up to 365 days.
- **Recommended fix:** Reduce `MAX_OFFLINE_GRACE_DAYS` from 365 to a sensible maximum (e.g. 14 or 30 days) and alert the user with an "Offline Trust Warning" when operating on cached snapshots older than 7 days.
- **Related:** `SEC-D26-02`.

---

## 6. Rejected Hypotheses & Not Vulnerable Notes
- **REJ-D27-01: Directory Statement Overwriting Out-of-Band PAIRED Pins**: Audited `OrgPeerResolver.kt:278-290` and `OrgPeerResolverTest.kt:341`. If `trustStore.pinSource(deviceId)` is `PAIRED` or `VOUCHED`, `storedPin` is strictly protected and never replaced by a directory statement.
- **REJ-D27-02: PTT Audio Injection by Unpaired Colleague**: Audited `PeerTrustPolicy.kt:21` and `PttSessionEngine.kt`. PTT transmission and playback strictly require `isPaired == true`. ORG colleagues cannot trigger immediate PTT audio.
- **REJ-D27-03: Unauthorized Group Administration by Unpaired Colleague**: Audited `PeerTrustPolicy.kt:29-30` and `RealFlashChatRepository.kt:5623`. All administrative actions (`mayAdministerGroup`, `mayAddGroupMember`) strictly require out-of-band PAIRED trust.
