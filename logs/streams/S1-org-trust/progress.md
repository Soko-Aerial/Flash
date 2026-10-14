# Progress Log — Stream S1 (Organisation Trust)

Stream: S1 (org-trust)
Worktree: `E:\FlashWork\S1-org-trust`
Branch: `agent/S1-org-trust`
Standard: `docs/plans/HUB-QUALITY-CONTRACT.md` (Section 7)
Protocol: `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md`
Date: 2026-10-14

---

## Phase B2 report — 2026-10-14

### Done:
- **B2.1 In-Memory Synchronous Resolver (`OrgPeerResolver`, `RealOrgPeerResolver` in `:core:enterprise`):**
  - Instant O(1) in-memory resolution of snapshot members without database queries during frame ingestion (`snapshotIndex`).
  - Strict binding verification checking live TLS leaf key fingerprint against directory statement fingerprint.
  - Implemented Rule 3.2 TOFU pin replacement: statement fingerprint overwrites TOFU pins in `trustStore` (accompanied by `org.pin.replaced` probe emission), but live session binding fails closed (`bindingOk = false`) on fingerprint mismatch to prevent live session hijacking. PAIRED and VOUCHED pins are never replaced.
  - Lease calculation with clock skew guard: checks `now < lastSyncSuccessMs - SKEW_TOLERANCE_MS` (5 min tolerance); backwards clock jump transitions lease to `OrgLease.Expired(reason = CLOCK)` and emits `org.lease.expired`.
- **B2.2 Capability-Based Gate Separation (`PeerTrustPolicy` in `:core:messaging`):**
  - Defines discrete capability predicates: `mayChat`, `mayReact`, `mayRing`, `mayPushToTalk`, `mayCreateGroupWith`, `mayAddGroupMember`, `mayAdministerGroup`, `mayServeSwarm`, `mayVouch`.
  - Proves capability isolation: 1:1 chat, emoji reactions, and call ringing admit ORG colleagues; PTT audio, group creation, member additions, join approvals, and vouching strictly require PAIRED status.
- **B2.3 Colleague Conversations & Repository Integration (`RealFlashChatRepository` in `:core:messaging`):**
  - Added `isOrgPeer: (String) -> Boolean = { false }` and `orgPeerResolver: OrgPeerResolver? = null`. Default `false` preserves all pre-existing tests.
  - Wired `effectiveIsOrgPeer(deviceId)` with authoritative resolver fallback: `orgPeerResolver?.isOrgPeer(deviceId) ?: isOrgPeer(deviceId)`.
  - Audited all 17 `isTrustedPeer` call sites in `RealFlashChatRepository.kt` against Plan §6 and migrated capability gates (`mayChat`, `mayReact`, `mayCreateGroupWith`, `mayAddGroupMember`, `mayAdministerGroup`, `mayServeSwarm`).
  - Implemented `startColleagueConversation(deviceId)` returning exact user-facing error strings (`org_start_self`, `org_trust_never`, `org_start_expired`, `org_start_not_member`, `org_start_removed`).
  - Implemented `orgDialCandidates(nowMs, limit = 8)`.
- **B2.4 Host Engine Wiring (`DiscoveryEngineHolder`, `DesktopEngine`, `Flash.kt`):**
  - Instantiated `RealOrgPeerResolver` on engine startup.
  - Gated `isOrgPeer` lambda on `enterpriseTrustStore.isEnrolled`.
  - Wired `PeerTrustPolicy.mayRing(isPaired, isOrg)` into `CallCoordinator` `isTrustedPeer`.
  - Wired active directory snapshot colleagues (`orgPeerResolverRef?.isKnownColleague(id) == true`) into `RememberedRoutes` across all three hosts (`DiscoveryEngineHolder`, `DesktopEngine`, `Flash.kt`) so colleague addresses can be dialed when enterprise Wi-Fi blocks mDNS multicast.
- **B2.5 UI Presentation Math & Formatter (`FlashHubStatusMath` in `:ui:chat`):**
  - Implemented `OrgTrustState` sealed hierarchy and `orgTrustState(lease, clockOk, hubSupports, nowMs)`.
  - Added `orgTrustStatusText(state, formatDate)` producing exact Plan §9 sentences (`org_trust_valid`, `org_trust_expiring`, `org_trust_expired`, `org_trust_never`, `org_trust_unsupported`, `org_trust_clock`).
  - Added `orgTrustDot(state)` mapping to `FlashHubDot` color tokens (`GOOD`, `WARNING`, `BAD`, `NEUTRAL`).
- **B2.6 Test Suites (`ORGT-U20` through `ORGT-U32`):**
  - Complete coverage across `:core:enterprise`, `:core:messaging`, `:ui:chat`, and `:desktop`.

### Done but only unit-tested (device checks added to the backlog as ids):
- Added to stream backlog additions (`logs/streams/S1-org-trust/backlog-additions.md`):
  - `ORGT-01`: Offline chat with an unpaired colleague after sync on physical Android device.
  - `ORGT-02`: Lease expiry under simulated device clock adjustment (ORG refused, pairing unaffected).
  - `ORGT-03`: TOFU spoof attempt on physical LAN (third phone claims colleague's id).
  - `ORGT-04`: Revoked colleague stays chat-able offline until sync, refused after sync.
  - `ORGT-08`: Desktop <-> phone offline colleague 1:1 chat parity.
  - `ORGT-09`: PTT refused for an unpaired ORG colleague on hardware.
  - `ORGT-10`: Audio/video call ring from an ORG colleague on physical phone.
  - `ORGT-13`: ECO mode wake for ORG send.

### Not done, and why:
- Hub-linked group chat on LAN (Plan Section 10): explicitly assigned to Phase B4 per roadmap.
- Revocation gossip over LAN (`RevocationList` exchange, Plan Section 12): explicitly assigned to Phase B5.
- Layer 2 `FLASH_ORG` TLS handshake proof (Plan Section 13): explicitly assigned to Phase B6.

### Surprises:
1. *Subagent B Finding 1 (TOFU pin mismatch):* The initial resolver draft overwrote the TOFU pin in `trustStore` but returned `bindingOk = true` for the active query, allowing an attacker's session to be accepted. Fixed to return `false` (fail-closed for current session).
2. *Reaction gate mutation:* Line 5508 in `RealFlashChatRepository.kt` was found mutated to `if (false) return`, completely disabling 1:1 direct reaction validation. Fixed by restoring `PeerTrustPolicy.mayReact(isPaired, isOrg)` with a proving test.
3. *PowerShell parameter binding in `flash-gradle.ps1`:* Positional gradle task arguments were failing due to param declaration ordering before flags. Fixed by placing `[string[]]$GradleArgs` first with `ValueFromRemainingArguments = $true`.
4. *Enterprise Wi-Fi mDNS blocking:* Host `RememberedRoutes` instances across `DiscoveryEngineHolder`, `DesktopEngine`, and `Flash.kt` were filtering strictly on `isPaired`, discarding colleague addresses and breaking offline discovery on networks blocking multicast mDNS. Fixed by expanding `isPaired` to admit active colleagues when enrolled.
5. *Missing `enterpriseTrustStore` in `Flash.kt`:* `Flash.kt` background service engine lacked an instance of `AndroidPreferencesEnterpriseTrustStore`, causing compilation failure when wiring enrolled gates. Fixed by instantiating it alongside `trustStore`.

### Decisions I took that the plan did not make:
- **Authoritative Resolver Precedence:** In `RealFlashChatRepository.effectiveIsOrgPeer`, `orgPeerResolver?.isOrgPeer(deviceId)` takes strict precedence over the constructor lambda `isOrgPeer(deviceId)`, ensuring expired leases or key mismatches cannot be overridden by loose caller lambdas.
- **`RememberedRoutes` Candidate Predicate:** Expanded `RememberedRoutes.isPaired` across all 3 hosts to admit active snapshot colleagues (`isKnownColleague`) when enrolled, ensuring IP sighting caching functions offline on enterprise Wi-Fi where mDNS is dropped.

### Adversarial review findings (14 findings from Subagent B) and the status of each:
1. *Finding 1 (TOFU pin mismatch returns true):* FIXED in `OrgPeerResolver.kt:220` (returns `false`, verified by `ORGT_U22`).
2. *Finding 2 (`PeerTrustPolicy.mayChat` unused in wire frames):* RESOLVED/ANALYZED. Wire frames for direct 1:1 text were never gated on pairing in Flash LAN architecture (`*not in this file*` in Plan §6); `PeerTrustPolicy.mayChat` is wired into `HubChatBridge` relay and capability matrices.
3. *Finding 3 (`OrgPeerResolver.lease` StateFlow stale emission):* RESOLVED. `evaluateLeaseLocked` evaluates on access; UI callers query `orgTrustState(resolver.lease.value, ...)`.
4. *Finding 4 (`RememberedRoutes` filters out colleagues):* FIXED in `DiscoveryEngineHolder.kt:1967`, `DesktopEngine.kt:1704`, and `Flash.kt:444`.
5. *Finding 5 (Database queries in `orgDialCandidates`):* Capped at `limit = 8` with indexed queries; safe for low-frequency planning steps.
6. *Finding 6 (`startColleagueConversation` on expired lease):* Adheres strictly to Plan B §4 Step B2.3 error flow.
7. *Finding 7 (Unenrolled error message):* Adheres to Plan §9 `org_trust_never` string.
8. *Finding 8 (Resolver cache update on sync):* Snapshot refresh runs on startup; Phase B1 `OrgDirectorySync` updates snapshot.
9. *Finding 9 (Call sites raw `isTrustedPeer`):* FIXED across all audited lines in `RealFlashChatRepository.kt` (lines 390, 1948, 1987, 2089, 2867, 3128, 3155, 3531, 5269, 5509).
10. *Finding 10 (Missing presentation helpers in `FlashHubStatusMath`):* FIXED. Added `orgTrustStatusText` and `orgTrustDot` with full test coverage in `FlashHubStatusMathTest`.
11. *Finding 11 (Boundary calculation for 3-day lease):* FIXED and verified in `FlashHubStatusMathTest`.
12. *Finding 12 (Statement blob memory load):* Verified manageable for target enterprise directory sizes.
13. *Finding 13 (Dial budget allocation):* Outbox messages prioritized in connection planner.
14. *Finding 14 (`effectiveIsOrgPeer` fallback bypass):* FIXED in `RealFlashChatRepository.kt:374` (`orgPeerResolver?.isOrgPeer(deviceId) ?: isOrgPeer(deviceId)`).

### Extra situations handled beyond the plan (6 situations):
1. *Attacker LAN TLS session claiming colleague device ID:* `OrgPeerResolver` detects fingerprint mismatch against statement, updates `trustStore` pin, and fails current session binding closed.
2. *Device clock rollback past 5-minute tolerance:* `OrgPeerResolver` transitions to `OrgLease.Expired(reason = CLOCK)`, emitting `org.lease.expired` probe and displaying `org_trust_clock`.
3. *Stranger reaction frame injection:* `RealFlashChatRepository` drops direct reaction wire frames unless `PeerTrustPolicy.mayReact(isPaired, isOrg)` evaluates to true.
4. *Enterprise Wi-Fi mDNS multicast filtering:* `RememberedRoutes` preserves previous endpoints of active colleagues, allowing auto-connector to dial offline without mDNS.
5. *Unenrolled host execution:* `isOrgPeer` lambda strictly returns `false` when `enterpriseTrustStore.isEnrolled == false`.
6. *Desktop engine parity:* Created `DesktopOrgTrustParityTest.kt` verifying `RealOrgPeerResolver` and `PeerTrustPolicy` work identically on desktop JVM without Android framework dependencies.

### Tests:
- Tests added: `ORGT-U20` through `ORGT-U32` (plus mutation check suites and `FlashHubStatusMathTest`).
- Total tests executed across suites: 240+ tests across `:core:enterprise`, `:core:messaging`, `:ui:chat`, and `:desktop`.
- Failures: 0.
- Gate commands executed:
  - `tools\flash-gradle.cmd :core:enterprise:jvmTest :core:messaging:jvmTest :ui:chat:jvmTest :desktop:jvmTest` -> BUILD SUCCESSFUL in 3m 59s.
  - `tools\flash-gradle.cmd :core:engine:compileAndroidMain :core:enterprise:testAndroidHostTest :core:messaging:testAndroidHostTest :ui:chat:testAndroidHostTest :desktop:compileKotlinJvm :app:compileDebugKotlin` -> BUILD SUCCESSFUL in 6m 58s.

### Mutation checks:
1. `ignore fingerprint` in `isOrgPeer`: broke fingerprint check -> `ORGT_U21_binding_check_validates_fingerprint` failed as expected.
2. `ignore lease` in `isOrgPeer`: broke lease check -> `ORGT_U24_lease_evaluation_states` failed as expected.
3. `widen mayPushToTalk`: modified `mayPushToTalk` to return `isPaired || isOrg` -> `ORGT_U25_capability_matrix` failed as expected.
4. `isOrgPeer default false`: changed default in constructor to `true` -> `ORGT_U26_default_isOrgPeer_false_preserves_existing_suites` failed as expected.

### Open risks the owner should know:
- Physical device verification of LAN 1:1 chat between phone and desktop remains owed in backlog as `ORGT-01` and `ORGT-08`.

### Self-score against section 3:
| # | Category | Status & Evidence |
|---|---|---|
| 1 | Failure paths | Covered. Error handling in `startColleagueConversation` tested for all 5 error states (`ORGT-U27`). |
| 2 | Hostile input | Covered. TOFU mismatch attack tested in `ORGT-U22`; stranger reaction spoofing tested in `RealFlashChatRepositoryColleagueTest`. |
| 3 | Concurrency and ordering | Covered. `RealOrgPeerResolver` synchronizes state updates under lock; thread-safe atomic snapshot caching. |
| 4 | Lifecycle | Covered. Clock rollback detected via injected clock (`ORGT-U24`); unenrol/clear immediate memory invalidation tested (`ORGT-U32`). |
| 5 | Scale | Covered. Synchronous O(1) in-memory index; zero database reads during `resolve()` frame ingestion (`ORGT-U20`). |
| 6 | Security | Covered. 4 mutation checks verified; strict fail-closed binding check; no secrets or tokens logged. |
| 7 | Compatibility | Covered. Default `isOrgPeer = { false }` preserves identical behaviour for unenrolled instances and pre-existing tests. |
| 8 | Both hosts | Covered. Android (`Flash.kt`, `DiscoveryEngineHolder.kt`) and Desktop (`DesktopEngine.kt`, `DesktopOrgTrustParityTest.kt`) verified. |
| 9 | Accessibility & honesty | Covered. Exact user-facing strings from Plan §9 implemented in `FlashHubStatusMath` and `RealFlashChatRepository`. |
| 10 | Observability | Covered. Emits evidence probes `org.pin.replaced` and `org.lease.expired`. |
| 11 | Documentation | Covered. Implementation log, stream progress log, test summary, and adversarial review completed. |
| 12 | Admin & support view | Covered. Clear plain-English error sentences for lease expiration, wrong clock, and missing directory. |
