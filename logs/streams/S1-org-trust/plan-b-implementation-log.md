# Plan B Implementation Log — Stream S1 (Org Trust)

Stream: S1 (org-trust)
Worktree: `E:\FlashWork\S1-org-trust`
Branch: `agent/S1-org-trust`
Quality Contract: `docs/plans/HUB-QUALITY-CONTRACT.md`
Protocol: `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md`
Date: 2026-10-14

---

## 1. Phase B2 Explain-Back (Quality Contract §2)

### What Phase B2 must achieve and why
Phase B2 binds the verified offline directory snapshot (built and persisted in Phase B1) to live application gates and communication flows for 1:1 colleague chat. An enrolled device on a local network without internet connectivity holds a Room snapshot of signed colleague statements, but would reject all communication if gated behind `isTrustedPeer` (= paired). Phase B2 implements:
1. In-memory, O(1), zero-database resolver `OrgPeerResolver` tracking active snapshot members and validating TLS public-key SPKI fingerprint binding against the live session;
2. Lease evaluation `OrgLease` enforcing the organization grace period (`now <= lastSuccessfulSync + offlineGraceDays`) and guarding against backward clock tampering;
3. Explicit capability gate `PeerTrustPolicy` replacing blanket `isTrustedPeer` checks across all call sites in `RealFlashChatRepository`, ensuring colleague chat (`mayChat`), reactions (`mayReact`), and call rings (`mayRing`) are admitted while strictly preserving paired-only isolation for high-privilege operations (Push-To-Talk audio, group administration, legacy group creation, vouching);
4. Repository API `startColleagueConversation` allowing conversation creation with colleagues without modifying pairing status;
5. Colleague dial candidates capped by `orgDialCap = 8` and `DialBudget` for active conversations;
6. Honest lease UX status calculations in `FlashHubStatusMath`.

### Files touched and precedents read
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt`:
  - *Precedents read:* `HubPeerDirectory.kt`, `EnterpriseTrustStore.kt`, `VouchRules.kt`.
  - *Reused:* `StateFlow`, atomic reference caching, `FlashProbe`, and `OrgDirectoryDao`.
  - *Deliberate difference:* Synchronous O(1) in-memory lookup with zero database reads during message ingestion; dynamically evaluates SPKI fingerprint binding against `trustStore.getPin(deviceId)` (never caches `bindingOk`); implements Rule 3.2 TOFU pin replacement (statement fingerprint overwrites TOFU pins, but NEVER overwrites PAIRED or VOUCHED pins; mismatch returns not ORG for the current session); evaluates lease state and clock rollback, emitting `org.lease.expired`.
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicy.kt`:
  - *Precedents read:* `GroupPolicy.kt`, `VouchRules.kt`, `RosterGroupGate.kt`.
  - *Reused:* Predicate-based capability segregation pattern.
  - *Deliberate difference:* Replaces binary `isTrustedPeer` with distinct capability gates: `mayChat(peer)` (paired || org), `mayReact(peer)` (paired || org), `mayRing(peer)` (paired || org), `mayPushToTalk(peer)` (paired ONLY), `mayAdministerGroup(peer)` (paired ONLY), `mayCreateGroupWith(peer)` (paired ONLY), `mayServeSwarm(peer)` (paired || vouched/roster).
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`:
  - *Precedents read:* `RealFlashChatRepository.kt` constructor and call sites.
  - *Reused:* `upsertDirectConversation`, `messageDao`, `outboxDao`, `timeSource`.
  - *Deliberate difference:* Adds `isOrgPeer: (String) -> Boolean = { false }` constructor parameter (defaulting to false so all pre-existing tests and unenrolled instances remain completely unchanged); classifies all 17 call sites according to Section 6 policy; adds `startColleagueConversation(deviceId: String): FlashResult<String>` with validated error handling.
- Hosts (`Flash.kt`, `DiscoveryEngineHolder.kt`, `DesktopEngine.kt`):
  - *Precedents read:* Host engine startup and repository instantiation.
  - *Reused:* Passing `trustStore` and active sessions.
  - *Deliberate difference:* Wires `isOrgPeer = { peerId -> if (enterpriseTrustStore.isEnrolled) orgPeerResolver.isOrgPeer(peerId) else false }` only when enrolled. Also wires `CallCoordinator` `isTrustedPeer` with `PeerTrustPolicy.mayRing`.
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/FlashHubStatusMath.kt`:
  - *Precedents read:* `FlashHubStatusMath.kt`.
  - *Reused:* Status enum mapping.
  - *Deliberate difference:* Adds `orgTrustState(lease, clockOk, hubSupports, nowMs)` mapping `NOT_SUPPORTED`, `NEVER_SYNCED`, `VALID(until)`, `EXPIRING(<=3 days)`, `EXPIRED`, `CLOCK_WRONG`.

### Five ways this phase can go wrong
1. *Privilege Creep / Accidental Widening:* If `isTrustedPeer` is naively modified to return true for org members or if capability methods bleed into high-privilege domains, an unpaired colleague could trigger PTT audio injection, vouch for unknown devices, or alter group administration. Mitigation: `PeerTrustPolicy` strictly isolates each capability; PTT, group admin, and vouching strictly require `paired == true`; verified with parameterised matrix tests across all call sites.
2. *TOFU Pin Poisoning vs Live Session Binding:* If an attacker connects claiming a colleague's deviceId before the real colleague, an unverified TOFU pin is created. If `OrgPeerResolver` replaces the pin and immediately returns `bindingOk = true` on the mismatch, the attacker's active live session is treated as an authenticated ORG peer! Mitigation: On fingerprint mismatch with a TOFU pin, the pin in `trustStore` is replaced with the statement fingerprint for subsequent TLS handshakes, but `bindingOk` for the live session is `false` (Rule 3.2: "Mismatch -> not ORG").
3. *Database Lock Contention in Frame Ingestion:* If `isOrgPeer` executes a SQLite read on every inbound WebSocket frame or receipt, mobile performance will degrade under concurrent traffic. Mitigation: `OrgPeerResolver` maintains an atomic in-memory index; lookups are pure O(1) memory reads.
4. *Clock Rollback & Lease Tampering:* If a user or malicious attacker rolls back the device clock to extend an expired 30-day lease, a naive check `now <= lastSync + grace` would evaluate as valid. Mitigation: Clock skew tolerance check (`now >= lastSuccessfulSync - 5 min`); backwards clock jumps past tolerance trigger `OrgLease.Expired(reason = CLOCK)`.
5. *Unenrolled Leakage / Host Drift:* If hosts pass `isOrgPeer` without verifying `enterpriseTrustStore.isEnrolled`, unenrolled devices could execute resolver lookups against stale database rows. Mitigation: Hosts gate `isOrgPeer` lambda on `enterpriseTrustStore.isEnrolled`.

### Tests written first
1. `PeerTrustPolicyTest.ORGT_U25_capability_matrix`: parameterised truth table verifying capability separation across all capabilities.
2. `RealFlashChatRepositoryColleagueTest.reactionGate_acceptsOrgColleagueAndRejectsStranger`: verifying reaction gating on 1:1 direct conversations.
3. `OrgPeerResolverTest.ORGT_U22_tofu_pin_replacement`: verifying TOFU pin is overwritten with statement fingerprint while mismatch denies ORG for the mismatched session.
4. Mutation checks:
   - Mutation 1: ignore fingerprint in `isOrgPeer`
   - Mutation 2: ignore lease in `isOrgPeer`
   - Mutation 3: widen `mayPushToTalk` to include ORG
   - Mutation 4: `isOrgPeer` default must stay `false`

---

## 2. Call-Site Classification & Audit Table (Plan Section 6)

Every site of `isTrustedPeer` in `RealFlashChatRepository.kt` and related hosts/modules audited:

| # | Site (File:Line) | What it gates | Policy | Verified Result | Proving Test |
|---|---|---|---|---|---|
| 1 | `RealFlashChatRepository.kt:215` | Constructor parameter default | Default `{ false }` | VERIFIED: default false preserves exact pre-existing behaviour for all callers and suites | `RealFlashChatRepositoryColleagueTest.defaultIsOrgPeerFalsePreservesExistingSuites` |
| 2 | `RealFlashChatRepository.kt:390` | `HubChatBridge.isPaired` | PAIRED or ORG | VERIFIED: `isPaired = { isTrustedPeer(it) \|\| effectiveIsOrgPeer(it) }` allows 1:1 relay and direct messages | `RealFlashChatRepositoryColleagueTest.hubBridgeRelayAllowsOrgColleague` |
| 3 | `RealFlashChatRepository.kt:523` | `SignedGroups.isPaired` | PAIRED only | VERIFIED: `isPaired = isTrustedPeer` retains strict pairing requirement for v2 groups | `RealFlashChatRepositoryColleagueTest.groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague` |
| 4 | `RealFlashChatRepository.kt:592` | `RosterGroupGate.isTrustedPeer` | PAIRED only | VERIFIED: `isTrustedPeer = { isTrustedPeer(it) }` preserves paired requirement for non-hub roster gates (hub groups handled in B4) | `RealFlashChatRepositoryColleagueTest.groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague` |
| 5 | `RealFlashChatRepository.kt:1948` | `getPendingJoinRequests` (`known = isTrustedPeer(req.subjectId)`) | PAIRED only | VERIFIED: join request labels use paired trust store name; unpaired colleague displays requested wire label | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` (`mayAdministerGroup`) |
| 6 | `RealFlashChatRepository.kt:1987` | `createGroupLocked` (`memberIds.any { !PeerTrustPolicy.mayCreateGroupWith(...) }`) | PAIRED only | VERIFIED: Gated by `PeerTrustPolicy.mayCreateGroupWith(isPaired, isOrg)`. Rejects group creation with unpaired colleague. | `RealFlashChatRepositoryColleagueTest.groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague` |
| 7 | `RealFlashChatRepository.kt:2089` | `addGroupMembersLocked` (`memberIds.any { !PeerTrustPolicy.mayAddGroupMember(...) }`) | PAIRED only | VERIFIED: Gated by `PeerTrustPolicy.mayAddGroupMember(isPaired, isOrg)`. Rejects member addition of unpaired colleague. | `RealFlashChatRepositoryColleagueTest.groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague` |
| 8 | `RealFlashChatRepository.kt:2491` | `introducedByOf` (`isTrustedPeer(member.deviceId)`) | PAIRED only | VERIFIED: ADR-044 v2 introduction label requires paired owner; org status does not bypass introducer label | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` |
| 9 | `RealFlashChatRepository.kt:2867` | Swarm / file push recipients (`isTrustedPeer(it.deviceId) \|\| ...`) | PAIRED only | VERIFIED: Whole-file push remains paired-only per ADR-044 v2 E3; swarm offers require active group membership | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` (`mayServeSwarm`) |
| 10 | `RealFlashChatRepository.kt:3128` | `GroupWireFrame.Create` member validation | PAIRED only | VERIFIED: Legacy group creation frames require all members paired | `RealFlashChatRepositoryColleagueTest.groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague` |
| 11 | `RealFlashChatRepository.kt:3155` | `GroupWireFrame.Add` member validation | PAIRED only | VERIFIED: Legacy group member addition requires all members paired | `RealFlashChatRepositoryColleagueTest.groupGate_rejectsCreationAndMemberAdditionWithUnpairedColleague` |
| 12 | `RealFlashChatRepository.kt:3464` | Swarm offer probe logging (`"paired" to isTrustedPeer(...)`) | Telemetry probe | VERIFIED: Accurately reports paired status in telemetry probe | `RealFlashChatRepositoryColleagueTest` |
| 13 | `RealFlashChatRepository.kt:3531` | Non-swarm file push fallback | PAIRED only | VERIFIED: Refuses whole-file push from non-paired senders | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` |
| 14 | `RealFlashChatRepository.kt:5269` | `isActiveTrustedMember` | PAIRED only | VERIFIED: Group membership gate requires pairing for legacy groups | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` |
| 15 | `RealFlashChatRepository.kt:5278` | `isGroupPeerTrusted` | PAIRED or vouched | VERIFIED: Paired or vouched; reserved for B4 hub-group roster gate | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` |
| 16 | `RealFlashChatRepository.kt:5320` | `isGroupCallMember` | PAIRED or vouched roster | VERIFIED: Active call participation requires paired or vouched key | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` |
| 17 | `RealFlashChatRepository.kt:5508` | `ReactionFrame` in 1:1 conversation | PAIRED or ORG | **DEFECT FOUND & FIXED**: Line was mutated to `if (false) return`. Fixed to: `val peerId = transportPeerId ?: frame.memberId; if (!PeerTrustPolicy.mayReact(isPaired = isTrustedPeer(peerId), isOrg = effectiveIsOrgPeer(peerId))) return`. | `RealFlashChatRepositoryColleagueTest.reactionGate_acceptsOrgColleagueAndRejectsStranger` |
| 18 | `CallCoordinator.kt:57, 222, 457` | 1:1 Audio/Video Call Ringing | PAIRED or ORG (`mayRing`) | VERIFIED: Host wiring in `DiscoveryEngineHolder.kt:1498` and `DesktopEngine.kt:1540` updated to pass `PeerTrustPolicy.mayRing(isPaired, isOrg)` so colleagues may ring without auto-answer | `CallCoordinatorSecurityTest` |
| 19 | `PttSessionEngine.kt:67, 207, 231, 255` | Push-to-Talk immediate audio | Strictly PAIRED only (`mayPushToTalk`) | VERIFIED: PTT audio plays immediately; `PeerTrustPolicy.mayPushToTalk(isPaired, isOrg)` returns false for org-only peers; host wiring retains `trustStore.isTrusted` | `PeerTrustPolicyTest.ORGT_U25_capability_matrix` |
| 20 | `DiscoveryEngineHolder.kt:1200` & `DesktopEngine.kt:1353` & `Flash.kt:493` | Host engine `isOrgPeer` wiring | Gated on `isEnrolled` | **DEFECT FOUND & FIXED**: Hosts unconditionally wired `orgPeerResolver.isOrgPeer`. Fixed to gate on `enterpriseTrustStore.isEnrolled`. | `RealFlashChatRepositoryColleagueTest.unenrolledHostReturnsFalse` |

---
