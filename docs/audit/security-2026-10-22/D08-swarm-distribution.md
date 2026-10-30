# Domain D08: Swarm File Distribution (FSW1, :core:swarm) — White-Box Security Audit

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D08 — Swarm File Distribution (`FSW1`, `:core:swarm`)  
**Auditor:** Flash Security Audit Team (Domain D08 Specialist)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash Swarm (`FSW1`, `:core:swarm`) provides decentralized multi-source file distribution for groups (ADR-070 to ADR-075, SW-0 to SW-8). Content is announced with a cryptographic root derived from a canonical manifest (`ManifestCodec`), pieces are fixed-size chunks (64 KiB to 1 MiB) verified against per-piece SHA-256 hashes, and assembled files undergo whole-file SHA-256 verification before final placement.

The audit verified the cryptographic framing, piece hashing, rate-limiting, and tombstone signature validation. However, critical gaps were discovered in trust gate enforcement:
- **High Severity (`SEC-D08-01`):** `RosterGroupGate` allows vouched members to participate in swarm serving and receiving (`FILE_SERVE` and `FILE_RECEIVE`), directly violating the documented security boundary ("vouched members get no files in either direction", `docs/security.md` §9).
- **Medium Severity (`SEC-D08-02`):** `SwarmDriver` omits the group membership check on inbound `SwarmFrame.Piece` frames, and `TransferScheduler` ingests unsolicited pieces without verifying that the piece was requested from that peer.
- **Low Severity (`SEC-D08-03`):** Swarm cancellation does not immediately purge `.part` files from storage, leaking disk space until system maintenance.

---

## 2. Checklist Item Evaluation

| # | Checklist Item | Evaluation | Summary / Finding ID |
|---|---|---|---|
| 1 | Piece & bitfield validation: out-of-bounds, negative, size mismatch | **Not vulnerable** | Enforced strictly in `SwarmFrameCodec.kt:86-100` and `Bitfield.kt`. Max piece count is capped at 16,384 (`PieceMath.MAX_PIECE_COUNT`). |
| 2 | Piece poisoning & hash verification: hash checks, strike book | **Not vulnerable** | Inbound pieces are verified against `cachedManifests[root].pieceHashes[index]` before writing (`SwarmDriver.kt:433`). Serving re-hashes pieces (`SwarmDriver.kt:489`). Bad pieces record strikes in `StrikeBook.kt`. |
| 3 | Tombstone forgery and replay: origin key binding, (groupId, messageId) | **Not vulnerable** | Cancel frames require author ECDSA signature over `SwarmStatement.cancel` (`SwarmDriver.kt:457`). Forged frames fail signature verification and earn strikes. Replay is idempotent. |
| 4 | Request amplification: per-requester byte budget, serve slots | **Not vulnerable** | `ServePolicy.kt:38` bounds outstanding bytes to 2 MiB per requester; slots are capped by performance profile (`calculateMaxSlots`). Excess requests return `SwarmRejectReason.BUSY`. |
| 5 | Serving unauthorized members: `FILE_SERVE` gate enforcement | **Vulnerable** (`SEC-D08-01`) | `RosterGroupGate.allows` evaluates `isVouchedMember` as true for `FILE_SERVE` and `FILE_RECEIVE`, allowing unvouched/unpaired members to download and serve swarm files. |
| 6 | Content-ID binding to group: cross-group oracle resistance | **Not vulnerable** | All lookups in `SwarmDriver` and `ServePolicy` use compound keys `(groupId, root)`. Requesting a root from another group returns `SwarmRejectReason.UNKNOWN`. |
| 7 | Whole-file validation: assembly hash check | **Not vulnerable** | `JvmPieceStorage.finalize` and `AndroidPieceStorage.finalize` compute whole-file SHA-256 and assert exact equality with `expectedSha256` before moving to destination. |
| 8 | Resource exhaustion: many concurrent peers, RAM exhaustion | **Not vulnerable** | Binary frame sizes, manifest fragments (max 9 of 64 KiB), bitfields, and serve queues are strictly bounded. |
| 9 | Availability information leaks: HAVE/BITFIELD to non-members | **Not vulnerable** | Outbound HAVE/BITFIELD frames are only sent to peers where `isPeerAllowed(groupId, peerId)` returns true. |
| 10 | Cancel propagation abuse: rogue peer broadcasting cancel | **Not vulnerable** | `SwarmDriver.kt:457` checks digital signature of declared origin. Only validly signed cancellations are forwarded across the mesh. |
| 11 | Persistence across restarts: state persistence, partial file resumption | **Vulnerable** (`SEC-D08-03`) | State is persisted in `SwarmStateStore`. However, cancelled partials linger on disk without immediate cleanup. |

---

## 3. Detailed Audit Findings

### SEC-D08-01 — RosterGroupGate Permits Vouched Members to Download and Serve Swarm Files Violating Documented Security Boundary
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** **A2** (Paired peer turned malicious), **A1** (Vouched stranger in group)
- **Component:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/RosterGroupGate.kt:38-47`, `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/swarm/MessagingSwarmGroupContext.kt:22-30`
- **CWE / standard:** CWE-285: Improper Authorization; CWE-863: Incorrect Authorization; MASVS-AUTH-1; CVSS:3.1/AV:A/AC:L/PR:L/UI:N/S:U/C:H/I:L/A:N (7.1 High)
- **What is wrong:** In `docs/security.md` §9 and ADR-044 V2, the project explicitly establishes that vouched group members (members who paired only with the group owner, not with the local device) **"get no files in either direction (group attachments are paired-only in both directions)"**. Furthermore, `PeerTrustPolicy.mayServeSwarm(isPaired, isOrg)` strictly returns `isPaired`. However, in `RosterGroupGate.allows(groupId, peerId, kind)`:
  ```kotlin
  val isTrusted = if (isV2) {
      isTrustedPeer(peerId) || isVouchedMember(groupId, peerId, peerIdentityKey(peerId))
  } else {
      isTrustedPeer(peerId)
  }
  if (!isTrusted) return false
  if (kind == GroupTraffic.FILE_SERVE) {
      return swarmServingEnabled(groupId)
  }
  return true
  ```
  `RosterGroupGate` allows vouched members through for both `GroupTraffic.FILE_RECEIVE` (used by `SwarmGroupContext.isPeerAllowed`) and `GroupTraffic.FILE_SERVE` (used by `SwarmGroupContext.isServeAllowed`).
- **Impact:** Vouched peers whom the user never paired with can download private group files and receive swarm pieces from the user. Because vouched peers have no bilateral pairing session key, chunks travel without `FSEC` application-layer encryption. Additionally, a rogue group owner can vouch an arbitrary key to exfiltrate swarm files from other group members.
- **Evidence:** 
  In [RosterGroupGate.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/RosterGroupGate.kt#L37-L49):
  Both `FILE_RECEIVE` and `FILE_SERVE` pass when `isVouchedMember` is true.
  In [MessagingSwarmGroupContext.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/swarm/MessagingSwarmGroupContext.kt#L22-L30):
  Delegates directly to `groupGate.allows(groupId, peerId, GroupTraffic.FILE_RECEIVE)` and `GroupTraffic.FILE_SERVE`.
- **Why existing guards do not stop it:** `RosterGroupGate` treats vouched trust identically to paired trust for all traffic kinds except where explicitly differentiated.
- **Verifier result:** survived
- **Recommended fix:** In `RosterGroupGate.allows`, if `kind == GroupTraffic.FILE_RECEIVE || kind == GroupTraffic.FILE_SERVE`, require `isTrustedPeer(peerId)` (bilateral pairing) and explicitly disallow unvouched/vouched-only peers.
- **Related:** ADR-044 V2, `docs/security.md` §9.

---

### SEC-D08-02 — SwarmDriver Ingests Inbound Piece Frames Without Peer Membership Validation and Accepts Unsolicited Pieces
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Unauthenticated network peer), **A2** (Removed group member)
- **Component:** `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt:431-445`, `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/TransferScheduler.kt:115-121`
- **CWE / standard:** CWE-345: Insufficient Verification of Data Authenticity; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:L/A:N (4.3 Medium)
- **What is wrong:** In `SwarmDriver.handleFrame`, frames such as `Summary`, `ManifestGet`, `Have`, `HaveAll`, and `Request` explicitly verify `groupContext.isPeerAllowed(frame.groupId, peerId)`. However, `SwarmFrame.Piece` completely omits the `isPeerAllowed` check. Furthermore, in `TransferScheduler.handlePieceArrived`:
  ```kotlin
  val reqKey = e.peerId to e.index
  val reqTime = content.inFlightRequests.remove(reqKey)
  if (reqTime != null) {
      content.inFlightByPiece[e.index]?.remove(e.peerId)
      requestWindow.removeInFlightBytes(pieceLen)
  } else if (content.bitfield.get(e.index)) {
      return
  }
  ```
  If `reqTime == null` (the piece was never requested from `e.peerId`), the engine does not discard it; as long as the piece has not yet been acquired, it verifies the SHA-256 hash against the manifest and writes it to disk.
- **Impact:** Any connected peer (including removed members or non-members) can inject unsolicited pieces into a downloading swarm transfer. While the piece hash matches the manifest, this allows external parties to force disk I/O and participate in transfers without group authorization.
- **Evidence:** [SwarmDriver.kt:431-445](file:///C:/Users/KaliOxygen/Downloads/Flash/core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt#L431-L445), [TransferScheduler.kt:115-121](file:///C:/Users/KaliOxygen/Downloads/Flash/core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/TransferScheduler.kt#L115-L121).
- **Why existing guards do not stop it:** The engine assumed that checking piece hash integrity is sufficient to accept piece data, overlooking transport peer authorization.
- **Verifier result:** survived
- **Recommended fix:** In `SwarmDriver.kt:431`, enforce `if (!groupContext.isPeerAllowed(frame.groupId, peerId)) return`. In `TransferScheduler.kt`, discard unsolicited pieces unless actively requested.
- **Related:** INV-3, GINV-6.

---

### SEC-D08-03 — Swarm Transfer Cancellation Leaves Orphaned Partial Files on Disk
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A2** (Paired group member)
- **Component:** `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ContentLifecycleHandler.kt:276-291`, `core/engine/src/jvmMain/kotlin/com/transfer/flash/core/engine/swarm/JvmPieceStorage.kt:161-166`
- **CWE / standard:** CWE-459: Incomplete Cleanup; CVSS:3.1/AV:L/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:L (3.3 Low)
- **What is wrong:** When a valid `SwarmFrame.Cancel` arrives from the origin, `ContentLifecycleHandler.handleCancelArrived` records the tombstone and transitions state to `CANCELLED`. However, it does not invoke `storage.deletePartial(key)`. The allocated `.part` file remains on disk in `~/.flash/swarm/partial/` or Android `filesDir/swarm/partial/` indefinitely until process restart or maintenance purge.
- **Impact:** Attackers can announce large files (e.g. 1 GiB), encourage peers to accept, and immediately send `Cancel`, stranding large `.part` files on peers' storage and consuming device space.
- **Evidence:** [ContentLifecycleHandler.kt:263-291](file:///C:/Users/KaliOxygen/Downloads/Flash/core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ContentLifecycleHandler.kt#L263-L291).
- **Why existing guards do not stop it:** `deletePartial` is only called during manual dismiss or purge of unreferenced keys.
- **Verifier result:** survived
- **Recommended fix:** Issue `SwarmCommand.DeletePartial(content.groupId, content.root)` when applying a cancellation tombstone.
- **Related:** ADR-071.

---

## 4. Candidate Findings Refuted During Audit

| Candidate | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D08-01: Piece Poisoning via Corrupted Bytes | A hostile peer can serve corrupted piece bytes to corrupt the assembled file. | **Refuted.** `SwarmDriver.kt:433` computes `Sha256.digest(frame.bytes)` and matches against `cachedManifests[root].pieceHashes[index]`. If mismatch, `verified = false`, piece is dropped, and a strike is logged against peer in `StrikeBook.kt`. Assembly in `JvmPieceStorage.finalize` also runs whole-file SHA-256. |
| REJ-D08-02: Forged Swarm Cancellation Tombstones | An unauthorized member can forge a `SwarmFrame.Cancel` to kill a download for all group members. | **Refuted.** `SwarmDriver.kt:448-457` checks `groupContext.verifyStatement(authorKey, stmt, frame.signature)` against the origin's verified public key. Unsigned/forged cancels are dropped and penalized with strikes. |
| REJ-D08-03: Cross-Group Content Disclosure Oracle | An attacker in Group A can probe whether a peer holds content from secret Group B. | **Refuted.** `SwarmDriver.kt:396` and `ServePolicy.kt:85` strictly query content by compound key `(groupId, root)`. If root is not announced in Group A, the engine returns `SwarmRejectReason.UNKNOWN`. |

---

## 5. Files Inspected

- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/api/FlashSwarm.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/codec/SwarmFrameCodec.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/codec/SwarmFrame.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/codec/SwarmStatement.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/codec/ManifestCodec.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmGroupContext.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ServeHandler.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ServePolicy.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/TransferScheduler.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ContentLifecycleHandler.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/StrikeBook.kt`
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/SwarmEngine.kt`
- `core/engine/src/jvmMain/kotlin/com/transfer/flash/core/engine/swarm/JvmPieceStorage.kt`
- `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/swarm/AndroidPieceStorage.kt`
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/swarm/MessagingSwarmGroupContext.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/RosterGroupGate.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicy.kt`
