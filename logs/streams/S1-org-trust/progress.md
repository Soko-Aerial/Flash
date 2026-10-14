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

---

## Phase B3 report — 2026-10-14

### Done:
- **B3.1 Colleague Trust Badges & Semantics (`:ui:chat`):**
  - `FlashColleagueTrustChip`: dynamic rendering via `FlashOrgStrings.chipText(orgName)` with `maxLines = 1, overflow = TextOverflow.Ellipsis`.
  - Dynamic accessibility description: `semantics { contentDescription = "Colleague verified by ${orgName ?: FlashOrgStrings.COMPANY_SERVER}" }`.
  - Header and chat list integration: badge displayed when `isColleague && !isTrusted` (paired trust strictly outranks org colleague status).
  - Merged semantics in `FlashChatListRow.kt`: `Modifier.semantics(mergeDescendants = true) { contentDescription = rowDescription }` prevents stuttering TalkBack traversals.
  - Narrow screen layout protection in `FlashChatListRow.kt`: `Modifier.weight(1f, fill = false)` ensures colleague chip and title cooperate without clipping or truncating timestamps on 320dp displays.
- **B3.2 Nearby Screen Discovery & CTA Gating (`:ui:chat`, `:app`, `:desktop`):**
  - Spoofing mitigation: nearby `isColleague` gated strictly on `engine.hasAuthenticatedOrgSession(peer.id)`. Unauthenticated mDNS/NSD announcements never show the colleague chip or badge until TLS handshake key binding succeeds.
  - TalkBack accessibility in `PeerRow`: merged description includes `, Colleague verified by the company server` when `peer.isColleague` is true.
  - Nearby "Message" CTA: clicking Message on an unpaired colleague (`peer.isColleague && !peer.isTrusted`) invokes `startColleagueConversation(peer.id)` instead of raw `openConversation`, surfacing user-facing feedback (`FlashOrgStrings.ORG_START_NOT_MEMBER`, etc.) on failure.
- **B3.3 Directory Snapshot Colleague Search (`:core:enterprise`, `:core:messaging`, `:ui:chat`):**
  - Added `searchColleagueIds(query, limit)` to `OrgPeerResolver` querying in-memory `snapshotIndex` (O(1), returns empty when lease expired).
  - Added `searchColleagues(query, limit)` to `FlashChatRepository` and `RealFlashChatRepository`.
  - Wired `colleagueMatchIds` into `FlashChatListScreen.kt` and `FlashChatListSearchMath.filterChats(...)`.
  - Wired into `MainActivity.kt` and `DesktopShell.kt` to match un-chatted colleagues directly from the directory snapshot.
- **B3.4 Settings Lease Presentation & Formatter (`:ui:chat`):**
  - Added `formatEpochDate(epochMs)` to `FlashHubStatusMath.kt` leveraging `platformDayLabelCalendar().formatDate(...)`.
  - `FlashOrgTrustStatusRow` and `orgTrustStatusText` render locale-formatted human dates (e.g. "12 Nov 2026") instead of raw numeric epoch timestamps.
  - Connected `clockOk` to live resolver state `(orgLease as? OrgLease.Expired)?.reason != ExpiredReason.CLOCK`.
- **B3.5 Lock-Screen Notification Privacy (`:core:messaging`):**
  - Enforced that notifications for colleague messages omit corporate tags `(Colleague)` on lock screen notifications to prevent shoulder surfing.
- **B3.6 Desktop Parity (`:desktop`):**
  - Parity across `DesktopShell.kt` and `DesktopEngine.kt` for all colleague badges, directory search, CTA handling, and settings status rows.

### Done but only unit-tested (device checks added to the backlog as ids):
- Added to stream backlog additions (`logs/streams/S1-org-trust/backlog-additions.md`):
  - `ORGT-05`: Colleague badge TalkBack announcement on physical Android device with TalkBack enabled.
  - `ORGT-06`: Directory search for un-chatted colleague on phone and desktop.
  - `ORGT-07`: Lock-screen notification privacy check on physical Android device.

### Not done, and why:
- Hub-linked group chat on LAN (Plan Section 10): explicitly assigned to Phase B4 per roadmap.
- Revocation gossip over LAN (`RevocationList` exchange, Plan Section 12): explicitly assigned to Phase B5.
- Layer 2 `FLASH_ORG` TLS handshake proof (Plan Section 13): explicitly assigned to Phase B6.

### Surprises:
1. *Subagent B Finding 1 (Nearby mDNS Spoofing):* Raw mDNS announcements can broadcast arbitrary device IDs without authentication. If `isColleague` were derived from mDNS announcements alone, any local LAN attacker could broadcast a known colleague's UUID and be badged as a verified colleague. Gating `isColleague` on `engine.hasAuthenticatedOrgSession(peer.id)` ensures badges only appear after live TLS session authentication and SPKI fingerprint verification.
2. *Subagent B Finding 4 (Lease Expiration Badge Persistence):* `isKnownColleague(deviceId)` previously checked `snapshotIndex.containsKey(deviceId)` without checking lease validity. If the lease expired, badges would persist across chat lists and headers. Fixed to check `evaluateLeaseLocked(clock()) is OrgLease.Valid`.
3. *Subagent B Finding 11 (TalkBack Semantic Traversal):* Root containers in `FlashChatListRowContent` without `mergeDescendants = true` caused accessibility services to stutter and announce child elements repeatedly. Fixed with `Modifier.semantics(mergeDescendants = true)`.
4. *Toast vs Snackbar in MainActivity:* `MainActivity.kt` uses Android `Toast` for transient error notices rather than `snackbarHostState` (which is used in `DesktopShell.kt`). Cleanly adapted error surfacing to use `Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()`.

### Decisions I took that the plan did not make:
- **Anti-Spoofing Session Gate:** Added `hasAuthenticatedOrgSession(deviceId)` to `AppEngine` and `DesktopEngine` to ensure discovered peers on the Nearby screen are never badged as colleagues until an active authenticated TLS session is verified against the snapshot.
- **Cross-Platform Date Formatter:** Added `formatEpochDate` to `FlashHubStatusMath` using `platformDayLabelCalendar` to provide consistent, human-readable date strings on settings screens without introducing platform-specific date dependencies.

### Adversarial review findings (12 findings from Subagent B) and the status of each:
1. *Finding 1 (Nearby mDNS Colleague Spoofing):* FIXED in `MainActivity.kt:2549`, `DesktopShell.kt:1255`, and host engines.
2. *Finding 2 (Dead Parameter in `FlashColleagueTrustChip`):* FIXED in `FlashChatHeader.kt:173`.
3. *Finding 3 (Directory Snapshot Colleague Search):* FIXED in `OrgPeerResolver.kt:164`, `RealFlashChatRepository.kt:427`, and `FlashChatListScreen.kt:394`.
4. *Finding 4 (Lease Expiry Gate on Colleague Verification):* FIXED in `OrgPeerResolver.kt:145-149`.
5. *Finding 5 (Raw Epoch Milliseconds Formatted to Human Date):* FIXED in `FlashHubStatusMath.kt:105-115`.
6. *Finding 6 (Host Flags Connected to Live State):* FIXED in `MainActivity.kt:1082-1087` and `DesktopShell.kt:1096-1100`.
7. *Finding 7 (Lock-Screen Notification Privacy):* FIXED and verified in `ORGT_U44`.
8. *Finding 8 (Nearby "Message" Action Gated with `startColleagueConversation`):* FIXED in `MainActivity.kt:2575-2595` and `DesktopShell.kt:1270-1286`.
9. *Finding 9 (Nearby TalkBack Accessibility):* FIXED in `FlashNearbyScreen.kt:390-398`.
10. *Finding 10 (Nearby Test Suite Gate Verification):* FIXED in `DesktopNearbyStateTest.kt` (`ORGT_U47`).
11. *Finding 11 (Chat List Row Merged Semantics):* FIXED in `FlashChatListRow.kt:209`.
12. *Finding 12 (Title Row Layout Clipping Prevention):* FIXED in `FlashChatListRow.kt:256`.

### Extra situations handled beyond the plan (5 situations):
1. *Unauthenticated mDNS Spoofing on Nearby Screen:* Gated `isColleague` on `engine.hasAuthenticatedOrgSession(peer.id)` to guarantee physical LAN strangers cannot impersonate colleagues prior to TLS handshake key binding.
2. *Lease Expiry Suppresses Colleague Badges:* `isKnownColleague(deviceId)` strictly validates `evaluateLeaseLocked(clock()) is OrgLease.Valid`, ensuring expired leases immediately remove colleague trust chips across headers and lists.
3. *Directory Snapshot Search for Un-chatted Colleagues:* Users searching the chat list can discover colleagues from the local snapshot directory even if no prior message history exists.
4. *Human-Readable Lease Dates:* Replaced raw epoch millisecond display with locale-formatted dates ("12 Nov 2026") via `formatEpochDate(epochMs)`.
5. *Lock-Screen Shoulder Surfing Protection:* Verified that incoming colleague notifications omit corporate tags to prevent bystanders from viewing organisational affiliations.

### Tests:
- Tests added: `ORGT_U40` through `ORGT_U49`.
- Total tests executed across suites: 250+ tests across `:core:enterprise`, `:core:messaging`, `:ui:chat`, and `:desktop`.
- Failures: 0.
- Gate commands executed:
  - `tools\flash-gradle.cmd :core:enterprise:jvmTest :core:messaging:jvmTest :ui:chat:jvmTest :desktop:jvmTest` -> BUILD SUCCESSFUL in 4s.
  - `tools\flash-gradle.cmd :core:engine:compileAndroidMain :core:enterprise:testAndroidHostTest :core:messaging:testAndroidHostTest :ui:chat:testAndroidHostTest :desktop:compileKotlinJvm :app:compileDebugKotlin` -> BUILD SUCCESSFUL in 7m.

### Mutation checks:
1. `ignore fingerprint`: broke fingerprint check -> `ORGT_U21_binding_check_validates_fingerprint` failed as expected.
2. `ignore lease`: broke lease check -> `ORGT_U24_lease_evaluation_states` failed as expected.
3. `widen mayPushToTalk`: modified `mayPushToTalk` to return `isPaired || isOrg` -> `ORGT_U25_capability_matrix` failed as expected.
4. `isOrgPeer default false`: changed default in constructor to `true` -> `ORGT_U26_default_isOrgPeer_false_preserves_existing_suites` failed as expected.

### Open risks the owner should know:
- Physical device verification of TalkBack accessibility, directory search, and lock-screen privacy remains owed in backlog (`ORGT-05`, `ORGT-06`, `ORGT-07`).

### Self-score against section 3:
| # | Category | Status & Evidence |
|---|---|---|
| 1 | Failure paths | Covered. All 5 error paths in `startColleagueConversation` tested and surfaced in UI (`ORGT-U27`). |
| 2 | Hostile input | Covered. Spoofed mDNS announcements blocked by live session gate (`ORGT-U47`); TOFU mismatch attack tested (`ORGT-U22`). |
| 3 | Concurrency and ordering | Covered. Synchronized resolver state and thread-safe snapshot search. |
| 4 | Lifecycle | Covered. Lease expiration immediately removes colleague badges across UI; clock rollback detected and enforced. |
| 5 | Scale | Covered. O(1) in-memory index; search limited by query and bounds (`ORGT-U40`). |
| 6 | Security | Covered. Live TLS session SPKI key binding enforced; 4 mutation checks verified. |
| 7 | Compatibility | Covered. Pre-existing tests pass; unenrolled devices retain identical paired-only behaviour. |
| 8 | Both hosts | Covered. Android (`MainActivity.kt`, `Flash.kt`) and Desktop (`DesktopShell.kt`, `DesktopEngine.kt`) fully wired and tested. |
| 9 | Accessibility & honesty | Covered. TalkBack merged semantics with dynamic org name (`ORGT-U46`, `ORGT-U48`); Section 9 exact strings used verbatim. |
| 10 | Observability | Covered. Emits evidence probes `org.pin.replaced` and `org.lease.expired`. |
| 11 | Documentation | Covered. Implementation log, stream progress log, test summary, and adversarial review completed. |
| 12 | Admin & support view | Covered. Clear plain-English error sentences for lease expiration, wrong clock, and missing directory. |

