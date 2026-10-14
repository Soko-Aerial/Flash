# Adversarial Review: Plan B Phase B2 (Organisation Trust Gate & 1:1 Chat)

**Reviewer:** Subagent B (Adversarial Reviewer — S1 Org-Trust Team)  
**Worktree:** `E:\FlashWork\S1-org-trust`  
**Branch:** `agent/S1-org-trust`  
**Target Commit:** `4f1991cb` ("feat(org-trust): Plan B B0 probes, B1 directory sync and snapshot, B2 gate in review")  
**Review Standards:** `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md` (Sections 3, 4 Phase B2, 6, 7, 9) & `docs/plans/HUB-QUALITY-CONTRACT.md`  
**Date:** 2026-10-14  

---

## Executive Verdict: REJECTED (Multiple Critical Vulnerabilities & Architectural Contradictions)

The Phase B2 implementation in commit `4f1991cb` is **REJECTED**. While unit tests in `:core:messaging` and `:core:enterprise` were reported green, adversarial inspection reveals:
1. **Critical Security Bypass:** A TOFU pin mismatch branch in `RealOrgPeerResolver` authoritatively returns `bindingOk = true`, granting full ORG trust to an attacker's active session who claimed a colleague's deviceId on first contact.
2. **Dead Security Policy / Ungated Inbound Chat:** `PeerTrustPolicy.mayChat` has **zero** calls in production code. Inbound 1:1 direct messages (`TextMessage`, `DeliveryReceipt`, `ReadReceipt`, `TypingFrame`) in `RealFlashChatRepository.onInboundWireFrame` are completely ungated by trust or org status, allowing any stranger on the LAN to inject text messages.
3. **Broken Offline Discovery on Enterprise Wi-Fi:** `RememberedRoutes` host wiring in all three engine entry points (`DiscoveryEngineHolder`, `DesktopEngine`, `Flash.kt`) filters remembered addresses strictly by `isPaired`, completely dropping offline colleagues when multicast mDNS is filtered.
4. **Performance Violation:** Database reads (`outboxDao`, `messageDao`, and potentially `conversationDao`) are executed inside the per-frame connection mode snapshot controller loop, violating the non-blocking frame ingestion invariant.
5. **Contract Falsification:** Several call sites listed as "VERIFIED" using `PeerTrustPolicy` in the plan's audit table (`RealFlashChatRepository.kt` lines 1948, 2867, 3128, 3155, 3531, 5269, 5320) were never actually migrated to `PeerTrustPolicy` and still invoke legacy `isTrustedPeer`.

---

## Part 1: Detailed Concrete Findings (14 Findings)

### Finding 1: Critical Security Bypass — TOFU Pin Mismatch Authoritatively Grants ORG Trust to Attacker's Live Session
- **Path & Line:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt:211-223` (specifically line 218)
- **Concrete Failure Scenario:**
  1. Attacker Mallory connects over LAN TLS to Alice's device claiming `deviceId = "bob"`. Bob has never paired with Alice and has not connected on this LAN before.
  2. The TLS handshake accepts Mallory's connection under Trust-On-First-Use (TOFU) and records Mallory's certificate public key fingerprint in `trustStore.savePin("bob", "ATTACKER_FP")`.
  3. Mallory now transmits a forged message or frame as Bob.
  4. Alice's gate evaluates `isOrgPeer("bob")`, which calls `resolve("bob")`.
  5. `resolve("bob")` fetches Bob's verified statement from the snapshot (`peer.keyFingerprint = "BOB_REAL_FP"`).
  6. The resolver reads `storedPin` (`"ATTACKER_FP"`). Because `"ATTACKER_FP" != "BOB_REAL_FP"`, it checks `source == PinSource.TOFU`.
  7. In lines 215–218, the resolver overwrites the pin in `trustStore` with Bob's real key, and then **returns `bindingOk = true`**!
  8. Mallory's active live session—which proved knowledge of `ATTACKER_FP`, *not* `BOB_REAL_FP`—is treated as authenticated ORG! Mallory's forged message is accepted and presented to Alice with the Colleague badge!
  9. *Note:* The explain-back in `logs/streams/S1-org-trust/plan-b-implementation-log.md:47` explicitly warned against this bug and stated that `bindingOk` must be `false` on mismatch ("Mismatch -> not ORG"). However, the implementer wrote line 218 as `true` and wrote test `OrgPeerResolverTest.kt:234` asserting `assertTrue(resolved.bindingOk)`.
- **Recommended Fix:**
  When `storedPin != peer.keyFingerprint`, `bindingOk` MUST be `false` for the current evaluation, even if the stored pin is updated in `trustStore` for subsequent TLS connections:
  ```kotlin
  val source = trustStore.pinSource(FlashDeviceId(deviceId))
  if (source == PinSource.TOFU) {
      trustStore.savePin(deviceId, peer.keyFingerprint)
      FlashLog.i(TAG, "Replaced TOFU pin for peer ${FlashProbe.short(deviceId)} with statement fingerprint")
      FlashProbe.emit("org.pin.replaced", "peer" to FlashProbe.short(deviceId))
      false // MUST FAIL CURRENT SESSION BINDING
  } else {
      false
  }
  ```
- **Proving Test:** `com.transfer.flash.core.enterprise.OrgPeerResolverTest.ORGT_U22_tofu_pin_mismatch_fails_binding_and_replaces_pin_for_future_connections` asserting `assertFalse(resolved.bindingOk)`.

---

### Finding 2: Dead Security Policy — `PeerTrustPolicy.mayChat` Is Never Called in Production Code; Inbound Direct Messages Are Ungated
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/PeerTrustPolicy.kt:12` & `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5353-5417`
- **Concrete Failure Scenario:**
  1. `PeerTrustPolicy.mayChat(isPaired: Boolean, isOrg: Boolean): Boolean = isPaired || isOrg` was created in `PeerTrustPolicy.kt` and tested in `PeerTrustPolicyTest.kt`.
  2. However, a grep across the entire codebase proves that `PeerTrustPolicy.mayChat` has **zero callers** in production code.
  3. In `RealFlashChatRepository.onInboundWireFrame`, the handler for `MessageWireFrame.TextMessage` (lines 5355–5417) checks `frame.text.length` and `transportPeerId != null && frame.senderId != transportPeerId`, but **never** performs any trust check on the sender (`isTrustedPeer`, `effectiveIsOrgPeer`, or `mayChat`).
  4. Any unauthenticated LAN stranger who establishes a raw TLS connection can send direct `TextMessage` frames. These are inserted into Room SQLite, trigger user-facing system notifications (`onInboundTextMessageWithGroupTitle`), create conversation rows, and receive `DeliveryReceipt`s.
  5. The claim in `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md` §6 call-site table line 294 ("VERIFIED: PeerTrustPolicy.mayChat allows 1:1 frames from ORG or paired peers") is false.
- **Recommended Fix:**
  In `RealFlashChatRepository.onInboundWireFrame` for `TextMessage`, `DeliveryReceipt`, `ReadReceipt`, and direct `TypingFrame`, enforce `PeerTrustPolicy.mayChat`:
  ```kotlin
  val peerId = transportPeerId ?: frame.senderId
  if (!PeerTrustPolicy.mayChat(isPaired = isTrustedPeer(peerId), isOrg = effectiveIsOrgPeer(peerId))) {
      FlashLog.w("CHAT", "Dropping 1:1 frame from untrusted peer ${FlashProbe.short(peerId)}")
      return
  }
  ```
- **Proving Test:** `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.textMessageGate_acceptsOrgAndPaired_rejectsStranger`.

---

### Finding 3: Lifecycle & StateFlow Stale Emission — `OrgPeerResolver.lease` Never Emits Expiration While App Is Running
- **Path & Line:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt:130-139, 253-261`
- **Concrete Failure Scenario:**
  1. `RealOrgPeerResolver.lease` delegates `collect(collector)` directly to an internal `_lease: MutableStateFlow<OrgLease>`.
  2. `_lease` is updated **only** when `evaluateLeaseLocked` is invoked. `evaluateLeaseLocked` is only invoked from `refreshFromSnapshot()`, `clear()`, `resolve()`, and the getter of `lease.value`.
  3. If a long-running app (e.g. desktop engine or background Android service) remains running across the 30-day lease expiration boundary without an active frame arriving to trigger `resolve()`, `_lease` will **never** emit `OrgLease.Expired`.
  4. UI components collecting `lease` will continue displaying "Valid" indefinitely.
  5. Furthermore, when a new collector starts collecting `lease.collect { ... }`, Kotlin `StateFlow` immediately emits the current `_lease.value` without invoking the `override val value` getter, emitting stale `Valid` state even if the lease expired hours ago.
- **Recommended Fix:**
  1. In `RealOrgPeerResolver`, launch a coroutine timer when lease is valid that delays until `lease.untilMs` and re-evaluates `evaluateLeaseLocked(clock())` to trigger emission and `FlashProbe.emit("org.lease.expired", ...)`.
  2. In `override suspend fun collect`, ensure an initial evaluation occurs before collection:
     ```kotlin
     override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<OrgLease>): Nothing {
         synchronized(lock) { evaluateLeaseLocked(clock()) }
         _lease.collect(collector)
     }
     ```
- **Proving Test:** `com.transfer.flash.core.enterprise.OrgPeerResolverTest.ORGT_U24b_lease_flow_emits_expired_at_time_boundary_without_explicit_resolve_call`.

---

### Finding 4: Incomplete Host Wiring — `RememberedRoutes` Filters Out ORG Colleagues Across All Three Hosts
- **Path & Line:**
  - `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:1967`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1704`
  - `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:444`
- **Concrete Failure Scenario:**
  1. In all three engine hosts, `RememberedRoutes` is instantiated as:
     `isPaired = { id -> trustStore.isTrusted(FlashDeviceId(id)) }`.
  2. On enterprise Wi-Fi networks where multicast / mDNS is blocked by network administrators (a standard enterprise AP configuration), NSD discovery cannot discover colleague devices.
  3. Colleague endpoints stored in SQLite table `remembered_endpoints` from previous connections are queried via `remembered.sightings()`.
  4. In `RememberedRoutes.kt:121`, `rows.partition { isPaired(it.deviceId) }` discards all unpaired endpoints. Because colleagues are ORG (not paired), their remembered addresses are discarded!
  5. The auto-connector is starved of sightings and cannot dial colleagues on enterprise networks without mDNS, breaking offline LAN colleague chat for the primary target enterprise environment.
  6. This directly violates Plan B §4 Step B2.4 and Step B0.7 ("In Phase B2/B3, an ORG candidate set ... must be added to RememberedRoutes.sightings and the connection planner dial filter").
- **Recommended Fix:**
  Pass a candidate predicate to `RememberedRoutes` allowing paired peers OR active org dial candidates:
  ```kotlin
  isPaired = { id ->
      trustStore.isTrusted(FlashDeviceId(id)) ||
          (enterpriseTrustStore.isEnrolled && chatImpl?.isOrgDialCandidate(id) == true)
  }
  ```
- **Proving Test:** `com.transfer.flash.core.network.remembered.RememberedRoutesColleagueTest.sightings_includes_active_org_candidate_when_unpaired`.

---

### Finding 5: Performance Violation — Database I/O Executed Inside Per-Frame Connection Mode Snapshot Loop
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6934-6945` & `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:1132`
- **Concrete Failure Scenario:**
  1. `ConnectionModeController.runStep()` executes on every inbound frame batch and timer tick.
  2. Inside `runStep()`, line 127 calls `val view = snapshot()`.
  3. In `DiscoveryEngineHolder.kt:1132`, `DesktopEngine.kt:1282`, and `Flash.kt:418`, the `snapshot` lambda calls `presenceChat?.orgDialCandidates().orEmpty()`.
  4. In `RealFlashChatRepository.orgDialCandidates()`, lines 6934–6945 query SQLite:
     - `outboxDao.dueForDelivery(Long.MAX_VALUE, limit * 2)`
     - Loop calling `messageDao.getByLocalId(row.localId)`
     - Fallback query `conversationDao.observeAll().first()`
  5. Under continuous frame ingestion (e.g. streaming file chunks or rapid chat messages), SQLite queries are triggered on every frame batch, causing UI jank, thread pool exhaustion, and battery drain.
  6. This violates Plan B §4 Step B2.1 ("resolve must be O(1) and never block on the database (it is called from frame handlers)") and Plan B §7 Situation 13 ("Memory/battery: no per-frame database reads").
- **Recommended Fix:**
  Maintain an in-memory cached set of candidate device IDs in `RealFlashChatRepository` (invalidated when outbox rows change, when active chat changes, or on a 15–30s debounced timer), avoiding SQLite queries during `LinkView` snapshot generation.
- **Proving Test:** `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.ORGT_U29b_orgDialCandidates_does_not_hit_database_on_consecutive_calls`.

---

### Finding 6: Logic Bug — `startColleagueConversation` Fails When Lease Is Expired Even If Hub Relay Is Online
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6877-6879`
- **Concrete Failure Scenario:**
  1. An employee is away from the local office network for 31 days. The offline lease has expired (`resolver.lease.value is OrgLease.Expired`).
  2. The employee is connected to the company Hub server over the Internet/cellular relay (`hubBridge?.port?.usable == true`).
  3. The employee taps "Message" on a colleague in the Hub tab.
  4. `startColleagueConversation` immediately aborts:
     `FlashResult.Failure(FlashError.Unknown("Reconnect to your company network to start a chat with this colleague."))`.
  5. The employee is prevented from chatting with their colleague even though the Hub relay is online and capable of carrying the message!
  6. Plan B §2.3 explicitly requires: "Errors: not enrolled; not in directory; lease expired and hub offline; peer is yourself; peer revoked/removed." And §2.5: "When EXPIRED: ORG peers revert to refusal on the LAN; hub chat unaffected."
- **Recommended Fix:**
  Check whether the Hub relay is online before refusing on expired lease:
  ```kotlin
  is OrgLease.Expired -> {
      if (hubBridge?.canCarry(deviceId) != true) {
          return@withContext FlashResult.Failure(FlashError.Unknown("Reconnect to your company network to start a chat with this colleague."))
      }
  }
  ```
- **Proving Test:** `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.startColleagueConversation_succeeds_when_lease_expired_but_hub_online`.

---

### Finding 7: UX Defect — Unenrolled User Receives Misleading Error in `startColleagueConversation`
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6870-6872`
- **Concrete Failure Scenario:**
  1. A user who has never enrolled in an organisation (or who unenrolled) attempts to start a colleague conversation (e.g. from an intent, deep link, or test).
  2. `orgPeerResolver` is null or `enterpriseTrustStore?.isEnrolled == false`.
  3. Line 6871 returns: `"Connect to the company network once to load your colleagues."` (`org_trust_never`).
  4. An unenrolled user is told to "connect to the company network" as if they are enrolled, rather than being told they are not enrolled in an organisation.
  5. Plan B §2.3 and §9 require distinct errors: "not enrolled" vs "never synced".
- **Recommended Fix:**
  Inspect enrollment state first:
  ```kotlin
  if (enterpriseTrustStore?.isEnrolled != true || orgPeerResolver == null) {
      return@withContext FlashResult.Failure(FlashError.Unknown("You are not enrolled in an organisation."))
  }
  ```
- **Proving Test:** `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.startColleagueConversation_fails_with_not_enrolled_when_unenrolled`.

---

### Finding 8: Lifecycle Bug — `OrgPeerResolver` In-Memory Index Is Never Refreshed or Cleared on Enrollment Lifecycle Changes
- **Path & Line:**
  - `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:1192-1194`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1346-1348`
  - `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:485-487`
- **Concrete Failure Scenario:**
  1. In all three engine startup sequences, `orgPeerResolver.refreshFromSnapshot()` is launched only once at app boot.
  2. If an unenrolled user starts the app, `refreshFromSnapshot()` indexes 0 members.
  3. The user then enrols in an organisation and completes their initial directory sync. `OrgDirectorySync` commits members to Room SQLite.
  4. However, neither `OrgDirectorySync` nor the enrollment coordinator calls `orgPeerResolver.refreshFromSnapshot()`.
  5. `snapshotIndex` remains empty in memory. The user cannot chat with any colleagues offline until they kill and restart the application!
  6. Conversely, when the user unenrols, Room rows are cleared, but `orgPeerResolver.clear()` is never invoked, leaving stale employee names and device IDs in memory.
- **Recommended Fix:**
  1. In `OrgDirectorySync`, after `applySyncBatch` succeeds, invoke `orgPeerResolver.refreshFromSnapshot()`.
  2. In `EnterpriseTrustStore` or unenrolment flows, invoke `orgPeerResolver.clear()`.
- **Proving Test:** `com.transfer.flash.core.enterprise.OrgPeerResolverTest.ORGT_U32b_sync_and_unenrol_updates_resolver_in_memory_state_without_restart`.

---

### Finding 9: Policy Contract Violation — Audit Table Call Sites Never Migrated to `PeerTrustPolicy`
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:1948, 2491, 2867, 3128, 3155, 3531, 5269, 5320`
- **Concrete Failure Scenario:**
  1. Plan B §2.2 and §6 mandate replacing raw `isTrustedPeer` checks across capability call sites with explicit `PeerTrustPolicy` methods (`mayAdministerGroup`, `mayServeSwarm`, etc.) to document intent and avoid privilege drift.
  2. The author implemented `PeerTrustPolicy.kt`, but failed to migrate the majority of audited call sites in `RealFlashChatRepository.kt`:
     - Line 1948 (`known = isTrustedPeer(req.subjectId)`) still calls raw `isTrustedPeer`.
     - Line 2867 (`isTrustedPeer(it.deviceId)`) still calls raw `isTrustedPeer` instead of `PeerTrustPolicy.mayServeSwarm`.
     - Lines 3128, 3155 (`frame.memberIds.any { !isTrustedPeer(it) }`) still call raw `isTrustedPeer` instead of `PeerTrustPolicy.mayCreateGroupWith`.
     - Line 3531 (`if (!isTrustedPeer(frame.from))`) still calls raw `isTrustedPeer`.
     - Line 5269 (`isActiveTrustedMember`) still calls raw `isTrustedPeer`.
  3. The implementer recorded in `plan-b-implementation-log.md` that these were "VERIFIED: Gated by PeerTrustPolicy.mayAdministerGroup / mayServeSwarm", which is factually inaccurate.
- **Recommended Fix:**
  Refactor each identified call site to route through the corresponding method on `PeerTrustPolicy`.
- **Proving Test:** Parameterised matrix test verifying all call sites delegate to `PeerTrustPolicy`.

---

### Finding 10: UI Incompleteness — Missing Presentation Text and Banner Formatters in `FlashHubStatusMath.kt`
- **Path & Line:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashHubStatusMath.kt:319-357`
- **Concrete Failure Scenario:**
  1. `FlashHubStatusMath.kt` only defines `OrgTrustState` and the calculation `orgTrustState()`.
  2. There is no helper function to map `OrgTrustState` to the user-facing strings specified in Plan B §9 (`org_trust_valid`, `org_trust_expiring`, `org_trust_expired`, `org_trust_never`, `org_trust_unsupported`, `org_trust_clock`).
  3. There is no helper to determine the `FlashHubDot` color token for colleague trust.
  4. Neither Settings nor Hub tab can display the colleague trust row without writing ad-hoc string formatting, violating Quality Contract §5 Rule 10 ("A user-facing string that is not exactly the plan's string").
- **Recommended Fix:**
  Add `orgTrustStatusText(state: OrgTrustState, dateFormatter: (Long) -> String): String` and `orgTrustDot(state: OrgTrustState): FlashHubDot` directly in `FlashHubStatusMath.kt`.
- **Proving Test:** `com.transfer.flash.ui.settings.FlashHubStatusMathTest.orgTrustStatusText_formats_all_states_with_exact_strings`.

---

### Finding 11: Boundary Calculation Bug — Ceiling Math in `FlashHubStatusMath.orgTrustState` Misclassifies 3-Day Boundary
- **Path & Line:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashHubStatusMath.kt:348-352`
- **Concrete Failure Scenario:**
  1. Line 348 calculates:
     `val daysLeft = ((msLeft.coerceAtLeast(0L) + 86_399_999L) / 86_400_000L).toInt()`.
  2. When `msLeft` is exactly 3 days (`3 * 86_400_000L = 259_200_000L`):
     `(259_200_000 + 86_399_999) / 86_400_000 = 345_599_999 / 86_400_000 = 4`.
  3. `daysLeft` evaluates to 4. Line 351 checks `daysLeft in 1..3`, which evaluates to `false`!
  4. Exactly at the 3-day warning boundary, the function returns `OrgTrustState.Valid` instead of `OrgTrustState.Expiring(3)`. The user receives no expiring warning until time drops to 2 days, 23 hours, 59 minutes, 59 seconds.
  5. Furthermore, when `msLeft == 1L` (1 millisecond before expiration), `daysLeft` evaluates to 1, displaying "Connect within 1 days" when the lease is about to expire in milliseconds.
- **Recommended Fix:**
  Use precise millisecond interval boundaries or exact integer division:
  ```kotlin
  val daysLeft = ((msLeft + 86_400_000L - 1L) / 86_400_000L).toInt()
  when {
      msLeft <= 0L -> OrgTrustState.Expired
      msLeft <= 3L * 86_400_000L -> OrgTrustState.Expiring(lease.untilMs, maxOf(1, (msLeft / 86_400_000L).toInt() + 1))
      else -> OrgTrustState.Valid(lease.untilMs)
  }
  ```
- **Proving Test:** `com.transfer.flash.ui.settings.FlashHubStatusMathTest.boundary_at_exact_3_days_returns_expiring_3`.

---

### Finding 12: Scale & Memory Risk — `refreshFromSnapshot` Loads Full Statement BLOBs for Entire Directory
- **Path & Line:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt:149` & `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/OrgDirectoryDao.kt:29`
- **Concrete Failure Scenario:**
  1. `dao.listAllMembers()` executes `SELECT * FROM org_directory_members`.
  2. This queries all columns, including `statementBytes: ByteArray` (canonical binary statement, ~300–500 bytes per member).
  3. For an enterprise with 5,000 to 20,000 employees, this query allocates 20,000 byte arrays totaling ~10MB of heap memory during engine initialization, competing with audio/video buffers and WebRTC.
  4. `RealOrgPeerResolver` only uses `deviceId`, `displayName`, `departmentId`, `role`, and `subjectPublicKeyFingerprint`—it discards `statementBytes`, `statementSerial`, and `updatedAtMs`.
- **Recommended Fix:**
  Introduce a lightweight Room projection entity and query in `OrgDirectoryDao`:
  `@Query("SELECT deviceId, displayName, departmentId, role, subjectPublicKeyFingerprint FROM org_directory_members") suspend fun listMemberIndexEntries(): List<OrgMemberIndexEntry>`
- **Proving Test:** `com.transfer.flash.core.enterprise.OrgPeerResolverTest.refreshFromSnapshot_uses_lightweight_projection`.

---

### Finding 13: Dial Budget Misallocation — `orgDialCandidates` Prioritizes Idle Open Chat Over Pending Outbox Messages
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6925-6938`
- **Concrete Failure Scenario:**
  1. `orgDialCandidates` evaluates `limit = 8`.
  2. Step 1 adds `activeConversationId` unconditionally if it is an active colleague direct chat.
  3. Step 2 then iterates through `outboxDao.dueForDelivery`.
  4. If the user leaves an idle chat open with colleague Alice (who has no pending messages and is not on the LAN), Alice consumes 1 of the 8 precious dial slots on every planning cycle.
  5. Furthermore, `orgDialCandidates` filters out group conversations (`!isGroup` and `!GroupPolicy.isV2GroupId`), meaning hub-linked group chats with colleagues (Phase B4) can never be dialed via candidate sets.
- **Recommended Fix:**
  Prioritize peers with due outbox messages first so urgent outgoing communications are never blocked by an idle open chat screen.
- **Proving Test:** `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.orgDialCandidates_prioritizes_outbox_over_idle_open_chat`.

---

### Finding 14: Lease Bypass — `effectiveIsOrgPeer` Fallback Evaluates Parameter Lambda Even When Resolver Rejects Lease
- **Path & Line:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:374-375`
- **Concrete Failure Scenario:**
  1. `effectiveIsOrgPeer` is defined as:
     `private fun effectiveIsOrgPeer(deviceId: String): Boolean = (orgPeerResolver?.isOrgPeer(deviceId) == true) || isOrgPeer(deviceId)`
  2. If `orgPeerResolver` is provided and determines that the lease is `Expired` (or clock rollback occurred), `orgPeerResolver.isOrgPeer(deviceId)` returns `false`.
  3. However, the evaluation then falls back to `|| isOrgPeer(deviceId)`.
  4. If a host or caller provided an `isOrgPeer` lambda that performs a static check or uses an unexpired snapshot, the caller-provided lambda overrides the resolver's lease expiration verdict!
- **Recommended Fix:**
  When `orgPeerResolver` is non-null, its verdict must be authoritative:
  ```kotlin
  private fun effectiveIsOrgPeer(deviceId: String): Boolean =
      orgPeerResolver?.isOrgPeer(deviceId) ?: isOrgPeer(deviceId)
  ```
- **Proving Test:** `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.effectiveIsOrgPeer_resolver_expired_overrides_lambda`.

---

## Part 2: Situations Not Spelled Out in the Plan (6 Situations)

The following 6 concrete failure modes and edge cases are not explicitly covered in `HUB-OFFLINE-ORG-TRUST-PLAN.md` and must be handled:

### Situation 1: Inbound File Attachment Offer from Unpaired Colleague (`FLASH_XFER action=offer`)
- **Failure Mode:**
  In `DiscoveryEngineHolder.kt:2338` and `RealFlashTransferRepository.kt`, when a file transfer offer arrives over LAN from an unpaired peer, the transfer layer checks whether the peer is trusted. Today, `trustStore.isTrusted(peerId)` returns `false`. Neither `RealFlashTransferRepository` nor `DiscoveryEngineHolder` was wired with `isOrgPeer`.
  Consequently, file offers from verified colleagues are either dropped as stranger spam or fail to display the "Colleague" verified trust chip in the transfer notification card.
- **Required Handling:**
  Wire `isOrgPeer` into `RealFlashTransferRepository` (or the transfer offer ingress gate). If the sender is an ORG colleague, allow receiving the offer (pending user accept) and badge the transfer UI with `org_chip_colleague`.

### Situation 2: Concurrent Direct Conversation Upsert Race
- **Failure Mode:**
  If the local user taps "Message" on a colleague in the directory at the exact millisecond an inbound frame from that same colleague arrives over LAN, both coroutines invoke `upsertDirectConversation(deviceId, ...)`.
  `upsertDirectConversation` executes `conversationDao.get(conversationId)` followed by `conversationDao.upsert(...)`. Depending on the interleaving of coroutine dispatch, the title can race between the snapshot's canonical `displayName` and the wire frame's unverified `senderName`.
- **Required Handling:**
  Synchronize `upsertDirectConversation` inside a repository mutex or ensure the Room database transaction prioritizes canonical `displayName` from `orgPeerResolver` over the unverified wire `senderName`.

### Situation 3: Re-enrolled Colleague with Fresh Clean Install (New Device ID and Key)
- **Failure Mode:**
  An employee gets a replacement phone or performs a clean app reinstall. The platform generates a brand-new `deviceId` (`bob-phone-new`) and a new identity keypair. The employee re-enrols with the Hub. The Hub assigns a new statement and tombstones `bob-phone-old`.
  On Alice's phone, the existing conversation history is keyed by `bob-phone-old`. When Alice opens the conversation, `bob-phone-old` is no longer in the snapshot (`startColleagueConversation` says "This person is no longer part of your organisation"). If Alice messages `bob-phone-old`, messages remain permanently stuck in the outbox.
- **Required Handling:**
  When a directory sync tombstones an old device ID belonging to an employee and introduces a new device ID with the same display name/account, the UI should display an informational banner in the old thread: "This colleague is now using a new device" with a button to transition to the new thread without data loss.

### Situation 4: Colleague Multi-Device Presence Ambiguity (FO-16)
- **Failure Mode:**
  An employee uses both Flash Desktop on their laptop and Flash Android on their phone. Both devices have distinct `deviceId`s and distinct identity keys, but identical `displayName` ("Bob Smith") and department ("Engineering").
  Both appear in the snapshot. When Alice taps "Bob Smith" or searches for Bob, `startColleagueConversation` requires a specific `deviceId`. If Alice picks Bob's desktop while Bob is walking around with his phone, messages sent over LAN will never reach Bob's phone.
- **Required Handling:**
  The directory search and member list UI must disambiguate multi-device colleagues by displaying device kind icons (Desktop vs Mobile) or short device ID suffixes, allowing the user to select the appropriate endpoint.

### Situation 5: Outbox Drain to Colleague Revoked While Message Was Queued
- **Failure Mode:**
  Alice drafts and enqueues a message to colleague Bob while Bob is offline. The message is stored in `OutboxDao` with status `PENDING`.
  Before Bob comes online, Alice syncs with the Hub (or receives revocation gossip), learning that Bob was revoked for security reasons. `OrgDirectoryDao` tombstones Bob's row.
  However, `drainOutboxOnce()` only checks `queuedForMs >= OUTBOX_GIVE_UP_AFTER_MS`. It does not re-verify whether the recipient is still an active organisation member!
  When Bob connects to Alice over LAN TLS, Alice's outbox drain loop would attempt to deliver the queued confidential message to the revoked employee!
- **Required Handling:**
  In `drainOutboxOnce()`, before dispatching a direct frame, verify `isTrustedPeer(target) || effectiveIsOrgPeer(target)`. If the target is an unpaired colleague who is no longer active in the organisation snapshot or has been revoked, abort delivery, mark the message as `FAILED`, and delete the outbox row.

### Situation 6: Device Clock Rollback During Active Live Session
- **Failure Mode:**
  Alice and Bob are actively exchanging messages on LAN with a valid lease.
  Alice's system clock jumps backwards by 2 hours (manual user adjustment or network time sync glitch).
  On the next frame, `evaluateLeaseLocked` detects `now < lastSyncSuccessMs - SKEW_TOLERANCE_MS` and transitions the lease to `OrgLease.Expired(reason = ExpiredReason.CLOCK)`.
  Subsequent incoming frames from Bob are silently dropped by Alice's gate (`resolve("bob")` returns `null`), and outgoing messages cannot be dialed.
  Bob's live WebSocket connection remains open at the transport layer, but all messages disappear silently.
- **Required Handling:**
  When the lease transitions to `Expired(reason = CLOCK)`, emit an immediate system alert / banner: `org_trust_clock` ("Your device clock looks wrong. Colleague trust may fail until it is fixed."), and gracefully notify active colleague chat threads that the session is paused due to clock error.

---

## Part 3: Recommended Action Items Before Resubmission

1. **Fix Pin Overwrite Binding in `RealOrgPeerResolver.kt`:** Ensure that when a TOFU pin mismatch occurs, `bindingOk` is returned as `false` for the current session.
2. **Wire `PeerTrustPolicy.mayChat` into `onInboundWireFrame`:** Enforce capability gating on inbound `TextMessage`, `DeliveryReceipt`, `ReadReceipt`, and `TypingFrame` in `RealFlashChatRepository.kt`.
3. **Wire `RememberedRoutes` for ORG Candidates:** Update `DiscoveryEngineHolder.kt`, `DesktopEngine.kt`, and `Flash.kt` to allow `RememberedRoutes` to dial active ORG candidates.
4. **Cache `orgDialCandidates`:** Eliminate SQLite database queries in `snapshot()` by caching candidates in memory.
5. **Support Hub Online Fallback in `startColleagueConversation`:** Allow starting colleague chats when offline lease is expired if `hubRelay.usable == true`.
6. **Implement Presentation Helpers in `FlashHubStatusMath.kt`:** Add status text and dot mapping functions with the mandatory Section 9 strings.
7. **Fix Expiration Event Scheduling in `RealOrgPeerResolver`:** Add a timer to ensure `lease` StateFlow emits `OrgLease.Expired` when the grace period elapses in real time.
