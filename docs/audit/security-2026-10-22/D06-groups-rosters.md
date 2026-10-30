# Flash Security Audit 2026-10-22 — Domain D06: Groups, Rosters, Vouching, Secrets, Invites, and Rotation

**Audited Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D06 — Groups (Rosters, v2 Signed Rosters, Vouching, Group Secrets, Invites, Rotation, Settings)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Domain D06 audits the group security architecture of Flash, encompassing:
1. v2 signed group charters and rosters (`flash-gcharter-v1`, `flash-gcert-v1`, `SignedGroups`, `GroupSignatureRules`)
2. Vouched trust mechanics and trust store interactions (`VouchRules`, `TrustStoreGroupVouching`, precedence over TOFU)
3. Group traffic gating via `GroupGate` and `RosterGroupGate` across chat, media, calls, and swarm transfers
4. Group secret derivation, commitments, and mutual proof protocol (`GroupSecret`, `GroupSecretKdf`, `GroupSecretCommit`, `GroupProof`, `GroupProofSessions`)
5. Dummy responder oracle mitigation and rate limiting
6. Secret rotation on member removal, concurrent rotation resolution, and stale-epoch synchronization
7. Group invite links (`flash://g/1/...`, `GroupInvite`, `GroupInviteCodec`, deep linking, and clipboard handling)
8. Join-request processing, applicant verification, auto-approval (`OPEN` vs `APPROVE`), and tombstoned key handling
9. Legacy group isolation and boundaries against historical forgery limits (ERROR-082)
10. Group settings signatures (`flash-gset-v1`), monotonic version enforcement, and conflict resolution

The audit identified **7 findings** (1 High, 2 Medium, 4 Low). A critical vulnerability was confirmed in the `membersMayAdd` permission introduced in ADR-074 / GM-9: member-issued certificates are evaluated as `vouchable`, which allows a regular group member to issue a certificate for an existing member or admin under an attacker-controlled public key, causing receiving nodes to overwrite the victim's public key in the database and trust store, enabling complete identity hijacking within the group. Additional findings include tombstone rollback via pruning beyond the 64-certificate limit, outbox message leakage to removed members, failure to terminate active WebRTC group call legs upon member removal, unbounded join request retention without 7-day pruning, clipboard exposure of unencrypted 256-bit group secrets, and unredacted desktop file logging.

---

## 2. Checklist Answers

| # | Checklist Item | Status | Finding / Analysis |
|---|---|---|---|
| 1 | **v2 roster signatures:** What is signed, canonical encoding, replay of old rosters, rollback after member removal. | **Vulnerable** | **SEC-D06-02**: `bundleFor` truncates tombstones to 64 (`MAX_BUNDLE_TOMBSTONES`). Newcomers who join after a tombstone has been pruned have no record of the former member; replaying an old owner-signed active certificate (`seq = 1, active = true`) resurrects the removed member as active. Replay on devices that already hold the tombstone is prevented by `membershipUpdateWins` comparing `(seq, opId)`. Canonical encoding is length-framed big-endian via `GroupCanonical.Writer`. |
| 2 | **Vouching mechanics:** Can an owner vouch an attacker-controlled key for another member's ID? Precedence (`PAIRED > VOUCHED > TOFU`). Vouch revocation on remove. | **Vulnerable** | **SEC-D06-01**: A paired pin cannot be overridden (`CONFLICT_PAIRED`). If a member is un-paired, an owner can vouch any key (accepted limit in ADR-044 V2). However, under `membersMayAdd = true`, `GroupSignatureRules.checkCert` marks `memberIssued` certs as `vouchable`, allowing regular members to vouch attacker keys for existing members and overwrite their stored keys and pins. Vouch revocation on remove is enforced via `port.revoke(subjectId, groupId)`. |
| 3 | **`GroupGate` coverage:** Audit every call site of `GroupGate` (`allows(CHAT)`, `allows(FILE_SERVE)`, `allows(CALL)`). Are there any ungated code paths? | **Vulnerable** | **SEC-D06-03** & **SEC-D06-04**: `GroupGate.allows(CALL)` has zero call sites in the repository; group call legs are not terminated when members are removed. `drainGroupOutbox` transmits pending group messages to removed members without querying `groupGate.allows(CHAT)`. `FILE_SERVE` is properly gated in `MessagingSwarmGroupContext` against both signed settings and local preferences. |
| 4 | **Membership by ID + secret:** KDF parameters, commit/proof algebra, mutual proof transcript binding to the underlying TLS connection. | **Not vulnerable** | `GroupSecretKdf` uses HKDF-SHA256 with domain prefixes `flash-gsa-v1` and `flash-gbeacon-v1`, binding group ID (2-byte length-framed) and 4-byte big-endian epoch. `GroupProofTranscript` binds both initiator and responder TLS leaf certificate SHA-256 fingerprints, roles (`I` and `R`), 16-byte fresh nonces, group ID, and epoch, preventing relay across connections, reflection, and replay. MACs are compared using `constantTimeBytesEqual`. |
| 5 | **Dummy responder oracle & rate limits:** Does proof failure leak whether the group exists? Are failed proofs rate-limited? | **Not vulnerable** | When a queried node does not hold the `(groupId, epoch)`, `GroupProofSessions` invokes a dummy responder that generates fresh random nonces and MACs, returning `GsChallenge` and later `GsResult(ok=false, reason="failed")`. It answers `"stale"` only after a valid proof of an older epoch known to the responder. Proof failures are strictly rate-limited by `ProofRateLimiter` (max 5 failures per peer per 10 minutes) and max 8 open hellos per peer. |
| 6 | **Rotation races & stale-epoch handling:** Secret rotation on member removal; removal tombstone ordering; can a removed member receive the rotated secret? | **Not vulnerable** | On removal, `removeMember` generates a signed tombstone and rotates the secret in one step (GINV-4). Ties between concurrent admin rotations at the same epoch are deterministically resolved by smaller `rotationId`; the loser re-rotates at `newEpoch + 1`. A removed member cannot obtain the rotated secret because `handleInboundGsSecretRequest` verifies `groupGate.allows(CHAT)` and verifies the peer is not in `removedIds` of any rotation notice. |
| 7 | **Invite links `flash://g/...`:** Link parsing, URL injection, who may mint/share, expiry, leakage in clipboard or notifications. | **Vulnerable** | **SEC-D06-06** & **SEC-D06-07**: `GroupInviteCodec` enforces non-throwing strict binary decoding, rejecting trailing bytes and invalid UTF-8. Only active members (and only admins if `inviteSharers == "ADMINS"`) may generate invites. However, copying the invite places the unencrypted 32-byte group secret into the OS clipboard without `EXTRA_IS_SENSITIVE`, and Desktop's file log sink lacks redaction regexes. |
| 8 | **Join-request flow:** Applicant spoofing, auto-approval under policy `OPEN` vs `APPROVE`, tombstoned key handling. | **Vulnerable** | **SEC-D06-05**: Direct join requests require live session proof (`hasProved == true`), TLS session leaf key match, and cryptographic signature verification over `joinRequestBytes`. Under `OPEN`, tombstoned keys are exempted from auto-approval. However, join requests never expire after 7 days, accumulating unbounded rows in SQLite that are repeatedly forwarded on every reconnection edge. |
| 9 | **Legacy-group forgery limits:** Check legacy group frames (v1) and verify forgery boundaries against ERROR-082. | **Not vulnerable** | Legacy membership frames (`Create`, `Add`, `Leave`, `State`) are strictly dropped for `g2-` group IDs (`RealFlashChatRepository.kt:3456`). Inbound messages for `g2-` groups require valid author signatures (`verifiedAuthorLabel`). For legacy `g-` groups, SyncPush attribution behaves according to the owner-accepted limits of ERROR-082 without crossing into v2 groups. |
| 10 | **Group settings signatures:** `flash-gset-v1`, monotonic version enforcement, tie-breaking `opId` manipulation. | **Not vulnerable** | Settings statements are canonically encoded via `GroupCanonical.settingsBytes` and signed by owner or active admin. Monotonic versioning is enforced (`candidate.version > existing.version`). In version ties, smaller `opId` wins. While an admin can select a lexicographically small `opId`, any admin can subsequently publish a higher version. Non-default history ceilings are separately signed under `flash-gsethc-v1` (ADR-105). |

---

## 3. Findings

### SEC-D06-01 — Member-Issued Certificate Key Substitution and Vouch Escalation Enables Non-Admin Identity Takeover
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** A2, A3 (any active group member when `membersMayAdd = true`)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSignatureRules.kt:111`
- **CWE / standard:** CWE-290: Authentication Bypass by Spoofing; CWE-287: Improper Authentication; ASVS V2.1.1; MASVS-AUTH-1; CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H (8.8 High)
- **What is wrong:** When `membersMayAdd` is enabled in group settings (ADR-074, GM-9), `GroupSignatureRules.checkCert` permits any active member to issue member certificates. Line 111 marks all active member-issued certificates as `vouchable`: `val vouchable = (ownerIssued || adminIssued || memberIssued) && cert.active`. In `subjectKeyIsBound`, if `vouchable` is true, it queries `vouching?.verdict(subjectId, fingerprint(subjectKey), groupId)`. Under `VouchRules.decide` line 73, when a device is already vouched by that group (`vouchingGroups == setOf(groupId)`), it returns `VouchVerdict.ACCEPT` based on the historical assumption that "the owner re-issued the cert". Furthermore, `checkCert` does not verify whether `cert.subjectId` is already an existing member or an admin. An attacker member can forge an updated certificate for an existing member (or an admin) with a higher `seq` and an attacker-generated public key. Receiving devices accept the cert, replace the victim's public key in `group_members`, and update the vouched pin in the trust store.
- **Impact:** An attacker member can completely hijack the identity of any non-owner member or admin in the group, author arbitrary messages signed under the victim's name, demote admins to plain members, join group calls as the victim, and cause receiving devices to reject subsequent connections from the legitimate member due to pinned key mismatch (Denial of Service).
- **Evidence:**
  `GroupSignatureRules.kt:111-113`:
  ```kotlin
  val vouchable = (ownerIssued || adminIssued || memberIssued) && cert.active
  if (!subjectKeyIsBound(charter, cert.subjectId, subjectKey, knownKey, vouchable)) return "subject-key-binding"
  ```
  `GroupSignatureRules.kt:237`:
  ```kotlin
  (vouchable && vouching?.verdict(subjectId, GroupCanonical.fingerprintHex(crypto, subjectKey), charter.groupId) == GroupVouchVerdict.ACCEPT)
  ```
  `VouchRules.kt:73`:
  ```kotlin
  vouchingGroups == setOf(groupId) -> VouchVerdict.ACCEPT // "the owner re-issued the cert"
  ```
  `SignedGroups.kt:1084`:
  ```kotlin
  port.vouch(cert.subjectId, GroupCanonical.fingerprintHex(crypto, key), groupId)
  ```
- **Why existing guards do not stop it:** `checkCert` restricts regular members only with `if (cert.role != MemberCert.ROLE_MEMBER || !cert.active) return "issuer-privilege"`. It does not check if `cert.subjectId` already exists in `knownKey`, does not check if `cert.subjectId` is an admin (`adminLookup(cert.subjectId) != null`), and marks `memberIssued` as `vouchable`, triggering the pin replacement path in `VouchRules`.
- **Verifier result:** Survived. Confirmed by code trace: an active member can issue a certificate for an existing subject ID with a new key and higher `seq`, passing all validation rules on the receiver.
- **Recommended fix:**
  1. In `GroupSignatureRules.kt:111`, restrict `vouchable` so that `memberIssued` certificates can NEVER vouch new pins or replace existing pins:
     ```kotlin
     val vouchable = (ownerIssued || adminIssued) && cert.active
     ```
  2. For `memberIssued` certificates, enforce that `knownKey == null` (members may only introduce completely new members, never re-certify or update existing members) and enforce that `adminLookup?.invoke(cert.subjectId) == null` and `cert.subjectId != charter.ownerId`.
  3. In `subjectKeyIsBound`, if `knownKey != null`, mandate that `subjectKey.contentEquals(knownKey)` unless the issuer is the group founder/owner.
- **Related:** ADR-044, ADR-074, ERROR-112.

---

### SEC-D06-02 — Tombstone Cap Truncation (64) Permits Rollback Replay of Former Member Certificates on Newcomers
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A2, A3 (malicious member or removed former member)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/SignedGroups.kt:452, 805`
- **CWE / standard:** CWE-294: Authentication Bypass by Capture-replay; ASVS V2.1.2; CVSS:3.1/AV:N/AC:H/PR:L/UI:N/S:U/C:H/I:H/A:N (6.8 Medium)
- **What is wrong:** `SignedGroups.bundleFor` caps the number of tombstones included in full roster reconcile bundles to `GroupPolicy.MAX_BUNDLE_TOMBSTONES = 64`. If a group experiences more than 64 member removals/leaves over its lifetime, older tombstones are dropped from subsequent bundles. When a new member joins the group, its initial roster state contains only active members and the 64 most recent tombstones. If an attacker re-transmits an older owner-signed active certificate (`seq = 1, active = true`) for a previously removed member whose tombstone was pruned, the newcomer checks `val current = rows[cert.subjectId]`. Because `current == null`, `membershipUpdateWins(1, null)` returns `true`. The newcomer validates the owner's signature on the old cert and installs the removed member back as an active member in its local roster.
- **Impact:** A removed former member is resurrected as an active member on newly joined devices. The newcomer will accept messages from the former member, accept incoming call legs from them, and transmit outbound group messages to them.
- **Evidence:**
  `SignedGroups.kt:451-456`:
  ```kotlin
  val (active, tombstones) = certs.partition { it.active }
  val capped = active + tombstones.sortedByDescending { it.seq }.take(GroupPolicy.MAX_BUNDLE_TOMBSTONES)
  if (capped.isEmpty()) return null
  ```
  `SignedGroups.kt:805-811`:
  ```kotlin
  val candidates = frame.certs.filter { cert ->
      val current = rows[cert.subjectId]
      membershipUpdateWins(
          GroupMembershipVersion(cert.seq, cert.opId),
          current?.let { GroupMembershipVersion(it.membershipVersion, it.operationId) },
      )
  }
  ```
  `GroupPolicy.kt:121-124`:
  ```kotlin
  public fun membershipUpdateWins(
      candidate: GroupMembershipVersion,
      existing: GroupMembershipVersion?,
  ): Boolean = existing == null || candidate > existing
  ```
- **Why existing guards do not stop it:** `membershipUpdateWins` returns `true` whenever `existing == null`. Because the newcomer's database never received the pruned tombstone, `existing` evaluates to `null`.
- **Verifier result:** Survived. Traced through `bundleFor` -> `onBundle` -> `membershipUpdateWins`.
- **Recommended fix:**
  1. Maintain a monotonic roster epoch or minimum sequence watermark in `GroupCharter` or `GroupSettings` that defines the lowest acceptable active member certificate sequence number, or
  2. Maintain a cryptographic accumulator (e.g. hash tree or bloom filter of removed subject IDs) signed by the owner, or preserve tombstones persistently without truncation in `bundleFor` for all removed member IDs.
- **Related:** ADR-044 V1, `docs/security.md` §9.

---

### SEC-D06-03 — Outbox Drain Transmits Enqueued Group Messages to Removed Members Without Active Membership Check
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** A2 (removed former group member)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6413`
- **CWE / standard:** CWE-863: Incorrect Authorization; ASVS V4.1.1; MASVS-AUTH-2; CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N (6.5 Medium)
- **What is wrong:** When a message is sent in a group chat, `sendGroupText` generates delivery entries in `group_deliveries` for all currently active members. If an admin or owner subsequently removes a member from the group via `removeGroupMemberLocked`, the removal logic removes the member from the roster and trust store but fails to cancel or delete pending delivery rows in `group_deliveries`. When the background outbox drain worker executes (`drainGroupOutbox`), it retrieves `pending = deliveries.pendingForMessage(item.localId)` and transmits the group message frame via `sendGroupViaPaths` directly to `delivery.memberId`. The drain path fails to check `groupGate.allows(groupId, delivery.memberId, GroupTraffic.CHAT)` or verify whether `delivery.memberId` remains an active member.
- **Impact:** Messages enqueued prior to or concurrently with a member's removal are transmitted over the wire to the removed member after their removal has been executed, violating post-removal confidentiality.
- **Evidence:**
  `RealFlashChatRepository.kt:6394-6420`:
  ```kotlin
  val pending = deliveries.pendingForMessage(item.localId)
  ...
  val results = coroutineScope {
      pending.map { delivery ->
          async {
              val sent = sendWithTimeout(item.localId, delivery.memberId) {
                  sendGroupViaPaths(sink, isLinkedToHub, delivery.memberId, item.localId, message.sentAt, frame)
              }
              delivery.memberId to sent
          }
      }.awaitAll()
  }
  ```
  `RealFlashChatRepository.kt:2518-2537` (`removeGroupMemberLocked`):
  No call to `groupDeliveryDao?.deleteForMember(groupId, deviceId)` or outbox delivery cleanup.
- **Why existing guards do not stop it:** `drainGroupOutbox` assumes that membership validation performed at enqueue time in `sendGroupText` remains valid indefinitely throughout all subsequent retry attempts.
- **Verifier result:** Survived. Traced in `RealFlashChatRepository.kt:6394-6420`.
- **Recommended fix:** In `drainGroupOutbox`, before dispatching `sendGroupViaPaths`, check:
  ```kotlin
  if (!groupGate.allows(message.conversationId, delivery.memberId, GroupTraffic.CHAT)) {
      deliveries.delete(item.localId, delivery.memberId)
      continue
  }
  ```
  Additionally, in `removeGroupMemberLocked`, explicitly purge pending delivery records for `deviceId` in `groupId`.
- **Related:** ADR-075, GINV-6.

---

### SEC-D06-04 — Call Legs for Removed Members Are Not Terminated Due to Unimplemented `GroupGate.allows(CALL)`
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A2 (removed group member during active call)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/RosterGroupGate.kt:45` and `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt`
- **CWE / standard:** CWE-863: Incorrect Authorization; ASVS V4.1.1; MASVS-AUTH-2; CVSS:3.1/AV:N/AC:H/PR:L/UI:N/S:U/C:L/I:L/A:N (4.2 Medium/Low)
- **What is wrong:** `GROUP-SWARM-IMPLEMENTATION-PLAN.md` §5.7 and Table item 46 specified that on `GroupGate.changes`, the calling layer must re-check `allows(CALL)` for each live WebRTC leg of that group and drop legs that fail (INV-3, GINV-6). However, in the audited codebase, `core:calling` contains zero references to `GroupGate`, does not subscribe to `groupGate.changes`, and `groupGate.allows(..., GroupTraffic.CALL)` is never called anywhere in the project. When a member is removed from a group during an ongoing call, their active audio/video call legs remain established until the call terminates naturally.
- **Impact:** A removed group member retains real-time audio and video eavesdropping and transmission capabilities in active group calls after their removal from the group roster.
- **Evidence:**
  `git grep "GroupGate" core/calling/` yields 0 matches.
  `git grep "\.allows("` yields no occurrences of `GroupTraffic.CALL`.
  `docs/security.md` §9 line 202:
  > "An already-established group call leg is not re-checked: a member removed mid-call stays connected until the call ends (pre-existing behaviour of call legs, unchanged by V2)."
- **Why existing guards do not stop it:** `FlashGroupCallSession` checks membership only when processing initial call invitations (`isGroupCallPeer`). Once WebRTC peer connections are negotiated, no background observer monitors roster membership changes.
- **Verifier result:** Survived. Confirmed that `GroupGate.allows(..., CALL)` is completely unimplemented in `core:calling`.
- **Recommended fix:** In `CallCoordinator` or `FlashGroupCallSession`, collect `groupGate.changes`. When a change event is emitted for `groupId`, evaluate `groupGate.allows(groupId, participantId, GroupTraffic.CALL)` for all active call legs and invoke `dropPeer(participantId)` for any leg that returns `false`.
- **Related:** ADR-075, GINV-6, `GROUP-SWARM-IMPLEMENTATION-PLAN.md` §5.7.

---

### SEC-D06-05 — Unbounded Join Request Retention in SQLite and Absence of 7-Day Expiry Pruning
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A1, A2
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:4210, 4275`
- **CWE / standard:** CWE-400: Uncontrolled Resource Consumption; ASVS V13.1.1; MASVS-STORAGE-1; CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L (5.3 Medium/Low)
- **What is wrong:** `docs/security.md` §10.2 states that join requests "expire after 7 days" to prevent join-request flooding and state bloat. However, inspection of `RealFlashChatRepository`, `GroupJoinRequestDao`, and Room database migrations reveals that no pruning routine, TTL eviction, or timestamp check exists for join requests. Each incoming join request is stored in `group_join_request`. An applicant can generate ephemeral keys to insert thousands of pending requests. Furthermore, in `onPeerSessionUp`, `RealFlashChatRepository` queries `groupJoinRequestDao.getAll().filter { it.state == "PENDING" }` without filtering by age, repeatedly forwarding years-old unapproved join requests to connecting admins across the network.
- **Impact:** Database bloat on mobile devices and recurring network bandwidth consumption caused by re-forwarding stale, unapproved join requests on every session connection.
- **Evidence:**
  `RealFlashChatRepository.kt:4210`: `groupJoinRequestDao?.upsert(entity)` with no eviction.
  `RealFlashChatRepository.kt:4275`:
  ```kotlin
  val pendingRequests = groupJoinRequestDao?.getAll()?.filter { it.state == "PENDING" } ?: emptyList()
  val members = groupMemberDao ?: return
  for (req in pendingRequests) { ... }
  ```
- **Why existing guards do not stop it:** No timer, work manager job, or read-time timestamp filtering checks `timeSource.nowMs() - req.requestedAtMs > 7 * 86400 * 1000L`.
- **Verifier result:** Survived. Confirmed by inspecting all usages of `groupJoinRequestDao`.
- **Recommended fix:**
  1. In `RealFlashChatRepository`, filter pending requests by TTL: `val cutoff = timeSource.nowMs() - 7L * 24 * 3600 * 1000L`, ignoring and deleting requests older than `cutoff`.
  2. Add a `deleteExpired(cutoff: Long)` query to `GroupJoinRequestDao` executed on repository startup and periodic cleanup.
- **Related:** `docs/security.md` §10.2.

---

### SEC-D06-06 — Group Secret Exfiltration Risk via Unflagged Sensitive Clipboard Copies
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A6 (malicious local applications, keyboard clipboard history, cloud clipboard sync)
- **Component:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashGroupInviteSheet.kt:138`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information to an Unauthorized Actor; MASVS-STORAGE-1; ASVS V8.3.1; CVSS:3.1/AV:L/AC:L/PR:L/UI:R/S:U/C:H/I:N/A:N (5.0 Medium/Low)
- **What is wrong:** In `FlashGroupInviteSheet`, tapping "Copy link" invokes `clipboardManager.setText(AnnotatedString(inviteUrl))`. The copied URI string (`flash://g/1/<base64url>`) directly embeds the raw 32-byte (256-bit) group encryption secret in base64url format. The copy action is performed using Compose's generic `LocalClipboardManager` without marking the Android `ClipData` with `ClipDescription.EXTRA_IS_SENSITIVE = true`. As a result, Android 13+ displays the full URL in visual clipboard overlays, third-party keyboards (e.g. Gboard, Samsung Keyboard, SwiftKey) and Windows Clipboard History (`Win+V`) persist the secret indefinitely in plain text, and cloud clipboard sync transmits the group secret to external cloud servers.
- **Impact:** Exposure of the group's 256-bit secret to unauthorized local apps, clipboard history databases, and cloud clipboard synchronizers.
- **Evidence:**
  `FlashGroupInviteSheet.kt:138`:
  ```kotlin
  clipboardManager.setText(AnnotatedString(inviteUrl))
  copied = true
  ```
  `git grep "EXTRA_IS_SENSITIVE"` returns 0 matches in the entire repository.
- **Why existing guards do not stop it:** Generic Compose `LocalClipboardManager` lacks Android-specific sensitive clipboard tagging.
- **Verifier result:** Survived. Traced in `FlashGroupInviteSheet.kt`.
- **Recommended fix:** On Android platforms via a platform shim, set `ClipData` with `ClipDescription.EXTRA_IS_SENSITIVE = true` on `ClipData.newPlainText(...)`, and on Windows, set `CanIncludeInClipboardHistory = 0` / `CanUploadToCloudStore = 0` via Win32 clipboard APIs.
- **Related:** MASVS-STORAGE-1, Android Developers Clipboard Security Guidance.

---

### SEC-D06-07 — Unredacted Plaintext Logging of Group Secrets and Invite Links in Desktop File Log Sink
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** A6, A7 (local user / forensic inspection of `~/.flash/desktop.log`)
- **Component:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt:534-546`
- **CWE / standard:** CWE-532: Insertion of Sensitive Information into Log File; MASVS-STORAGE-3; ASVS V7.1.1; CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N (3.3 Low)
- **What is wrong:** While Android's `RotatingFileLogSink` incorporates multi-pass regular expression redactions (`INVITE_LINK = Regex("""flash://g/\S+""")`, `SECRET_ASSIGN`, `BASE64_BLOB`), the Desktop engine's `installDesktopLogSink()` directly prints log messages and exception stack traces to `~/.flash/desktop.log` without applying any sanitization or redaction filters. Any log event containing an invite URL or secret parameter is written to disk in plain text.
- **Impact:** Plaintext storage of invite links and group secret parameters in unencrypted desktop log files.
- **Evidence:**
  `DesktopMain.kt:534-546`:
  ```kotlin
  FlashLog.installSink(
      FlashLogSink { level: FlashLogLevel, tag: String, message: String, throwable: Throwable? ->
          writer.println("${LOG_TIME.format(java.time.LocalTime.now())} ${level.name.first()}/$tag: $message")
          throwable?.printStackTrace(writer)
      }
  )
  ```
  Compare with Android's `RotatingFileLogSink.kt:164-175` which replaces `INVITE_LINK` with `"flash://g/<redacted>"`.
- **Why existing guards do not stop it:** Desktop logging bypassed `RotatingFileLogSink` and implemented an ad-hoc `PrintWriter` without redaction filters.
- **Verifier result:** Survived. Traced in `DesktopMain.kt:520-548`.
- **Recommended fix:** Extract the redaction logic from `RotatingFileLogSink` into `:core:common` and apply it identically in `installDesktopLogSink()`.
- **Related:** REJ-D01-02, `RotatingFileLogSinkTest`.

---

## 4. Files Inspected

- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/SignedGroups.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/GroupGate.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/RosterGroupGate.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/GroupProofSessions.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/GroupSecretStore.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupCanonical.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupCharter.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupCrypto.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupFrameCodec.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupHistoryPolicy.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupPolicy.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupRotation.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSettings.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSignatureRules.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSigning.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupWireFrame.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/MemberCert.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/trust/VouchRules.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupInvite.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupInviteCodec.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupProof.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupSecret.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupSecretCommit.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupSecretKdf.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/group/RoomGroupSecretStore.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/group/TrustStoreGroupVouching.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/swarm/MessagingSwarmGroupContext.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupMemberEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupRotationEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupSettingsEntity.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/entity/GroupJoinRequestEntity.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashGroupInviteJoinMath.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashGroupInviteSheet.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashJoinGroupDialog.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`
- `docs/security.md` (sections 8, 9, 10)
- `docs/protocol.md` ("v2 groups", "The proof", "Keys and commitment")
- `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` (§5.7, GINV-1..GINV-8)
