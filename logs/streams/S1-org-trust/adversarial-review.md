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


---

# Phase B3 Adversarial Review: Colleague Trust UX Completeness & Desktop Parity

- **Reviewer:** Subagent B (Hostile/Adversarial Reviewer)
- **Review Date:** 2026-10-14
- **Target Implementation:** Phase B3 (Colleague Trust UX Completeness, Accessibility, and Desktop Parity)
- **Specification:** `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md` (§3, §4 Phase B3, §6, §7, §9), `docs/plans/HUB-QUALITY-CONTRACT.md`, and ADR-134.
- **Verdict:** **REJECTED** (Critical security bypass, unwired core requirements, accessibility omissions, and contract violations).

---

## Executive Summary

Phase B3 implementation provides initial Compose UI components (`FlashColleagueTrustChip`, `FlashColleagueBadge`, `FlashOrgTrustStatusRow`), updates Section 9 string tables in both hosts, and adds parity tests. However, a rigorous adversarial audit reveals **12 concrete defects**, including **1 critical spoofing vulnerability**, **2 major specification omissions**, **1 persistent trust leakage past lease expiry**, and **3 accessibility/UX defects**.

| # | Severity | Category | Defect Summary | Impact |
|---|---|---|---|---|
| **F01** | **CRITICAL** | Security / Spoofing | Unauthenticated mDNS/NSD broadcast displays verified "Colleague" chip | Wi-Fi attacker spoofing UUID gets "Colleague" badge and "Message" CTA before TLS |
| **F02** | **HIGH** | Contract / Stub | `FlashColleagueTrustChip` completely ignores `orgName` parameter | Violates Contract §5 Rule 10; drops mandatory "verified by <orgName>" string |
| **F03** | **HIGH** | Spec Omission | Directory snapshot search completely unwired in UI | Chat search never queries `OrgDirectoryDao`; colleague discovery broken |
| **F04** | **HIGH** | Security / Trust | Colleague chip displayed even when offline lease has expired | `isKnownColleague` ignores lease expiry; false trust displayed indefinitely |
| **F05** | **MEDIUM** | UX / Formatting | Raw epoch milliseconds displayed in Enterprise Hub settings | Users see "Colleague trust valid until 1731456000000" instead of formatted date |
| **F06** | **HIGH** | Contract / Truth | Hardcoded `clockOk = true` and `hubSupports = true` in host shells | Silences `ClockWrong` and `NotSupported` states; violates Contract §5 Rule 4 |
| **F07** | **MEDIUM** | Privacy / Test | Missing assertion on lock-screen notification privacy | No test proves notifications omit "(Colleague)" for bystander privacy |
| **F08** | **HIGH** | Logic / Bypassed API | Nearby screen "Message" CTA never calls `startColleagueConversation` | Bypasses all Section 9 error checks; opens unseeded empty thread |
| **F09** | **MEDIUM** | Accessibility | TalkBack users blinded to Colleague badge on Nearby screen | `mergeDescendants = true` with static description suppresses colleague badge |
| **F10** | **LOW** | Test / Mocking | `DesktopNearbyStateTest` tests mock instead of live session gate | Masks unauthenticated mDNS colleague spoofing defect in test suite |
| **F11** | **LOW** | Accessibility / Semantics | Compose semantics on `FlashChatListRow` lacks `mergeDescendants` | Potential fragmented announcements in Android accessibility tree |
| **F12** | **LOW** | UI Layout | Title row clipping with long names and Colleague chip on narrow screens | Layout truncation on 320dp width or 200% font scale |

---

## Part 1: Concrete Adversarial Findings

### Finding 1: Critical Security Bypass — Unauthenticated mDNS Announcements Display Verified "Colleague" Chip
- **Path:Line:**
  - `app/src/main/java/com/transfer/flash/MainActivity.kt:1708-1718`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:2657-2668`
- **Concrete Failure Scenario:**
  1. An attacker connects to the local office Wi-Fi network and broadcasts an unauthenticated mDNS/NSD service advertisement containing a known colleague's `deviceId` (e.g. `colleague-uuid-1`) and a custom friendly name ("Alice Executive").
  2. The local device's discovery stack receives this raw mDNS packet into `discoveredEndpoints` / `discovered`.
  3. In `MainActivity.kt:1708-1718` and `DesktopShell.kt:2657-2668`, `isColleague` is computed directly as:
     ```kotlin
     val peerId = ep.deviceId.value
     val isColleague = engine.isOrgPeer(peerId)
     NearbyPeerUi(
         id = peerId,
         name = ep.friendlyName,
         ...
         isColleague = isColleague,
         orgName = if (isColleague) ... else null,
     )
     ```
  4. The Nearby screen immediately renders the row with a green verified **"Colleague"** badge and a **"Message"** CTA button.
  5. **Failure Mode:** At this stage, NO TLS handshake, NO certificate exchange, and NO cryptographic binding check has occurred! Any rogue actor on the LAN who knows or guesses a colleague's UUID is visually presented to the user as a trusted company colleague before authentication.
  6. **Plan Contradiction:** Violates Plan B §4 Step B2.4 & Phase B3: *"Colleague chip rendered: only after binding check passes; before that nothing is labelled"* and ADR-134 ("Discovered via mDNS/NSD ... neither paired nor attested ... Stranger (Tier 1)").
- **Recommended Fix:**
  In `MainActivity.kt` and `DesktopShell.kt`, do NOT badge an endpoint as `isColleague = true` based solely on unauthenticated discovery. Require that the endpoint has an active, authenticated live session with successful cryptographic SPKI binding (`engine.hasAuthenticatedOrgSession(peerId) == true`). If no live authenticated session is held, display as a standard unauthenticated nearby peer with "Pair" CTA or defer the "Colleague" chip until the session is bound.
- **Proving Test:**
  `com.transfer.flash.ui.nearby.FlashNearbyScreenTest.ORGT_U38_unauthenticated_mdns_announcement_does_not_display_colleague_chip` and `com.transfer.flash.desktop.DesktopNearbyStateTest.ORGT_U38_unauthenticated_discovered_peer_is_not_badged_as_colleague`.

---

### Finding 2: Contract Violation — `FlashColleagueTrustChip` Contains Dead Code & Hardcodes String
- **Path:Line:**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatHeader.kt:454-474`
- **Concrete Failure Scenario:**
  1. `FlashColleagueTrustChip` is declared as:
     ```kotlin
     @Composable
     fun FlashColleagueTrustChip(
         orgName: String?,
         modifier: Modifier = Modifier,
     ) {
         ...
         FlashText(
             text = "Colleague",
             style = FlashTheme.typography.captionEmphasis,
             color = colors.accentPrimary,
         )
     }
     ```
  2. The `orgName` parameter is completely unused. The text is hardcoded to `"Colleague"`.
  3. Plan B §2.3 and §9 explicitly specify: `"Colleague · verified by <orgName>"`, and `FlashOrgStrings.chipText(orgName)` was explicitly created to format this string.
  4. Furthermore, the accessibility semantics content description in line 466 hardcodes `FlashOrgStrings.ORG_CHIP_A11Y` ("Colleague verified by the company server") regardless of `orgName`.
  5. **Contract Violation:** Violates `HUB-QUALITY-CONTRACT.md` §5 Rule 10 ("No dead, unused, or stub parameters") and Plan B §9 exact string specification.
- **Recommended Fix:**
  Render the chip using `FlashOrgStrings.chipText(orgName)` or support both a compact chip ("Colleague") with an expanded label/trust sheet that includes `orgName`, and ensure TalkBack content description includes `orgName` when present.
- **Proving Test:**
  `com.transfer.flash.ui.chat.FlashChatHeaderTest.ORGT_U39_colleagueTrustChip_rendersOrgNameAndAccessibility`.

---

### Finding 3: Specification Omission — Directory Snapshot Colleague Search Is Completely Unwired in UI
- **Path:Line:**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListScreen.kt:125-127`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListSearch.kt:60-74`
- **Concrete Failure Scenario:**
  1. Plan B §4 Phase B3 requires: *"Chat list search incorporates directory snapshot: searching by colleague name or org email surfaces the colleague conversation or offers to start one."*
  2. In `FlashChatListSearch.kt:60-74`, `filterChats` was updated to accept `colleagueMatchIds: Set<String> = emptySet()`. In `FlashChatListSearchLogicTest.kt`, synthetic IDs were passed in unit tests, giving the illusion of colleague search support.
  3. However, in production `FlashChatListScreen.kt:126`:
     ```kotlin
     val displayItems = remember(activeItems, searchQuery, messageBodyMatches) {
         FlashChatListSearchMath.filterChats(activeItems, searchQuery, messageBodyMatches)
     }
     ```
     `colleagueMatchIds` is NOT passed (it defaults to `emptySet()`).
  4. Nowhere in `RealFlashChatRepository.kt` or `FlashChatListScreen.kt` is `OrgDirectoryDao.searchMembers(query)` ever invoked.
  5. If an enrolled user searches for a colleague ("Dr. Bob") who is present in the synced 5,000-member directory snapshot but does not have an existing active chat, search returns 0 results. No conversation is surfaced and no "Start conversation with colleague" offer is presented. Colleague directory discovery from search is 100% missing in production.
- **Recommended Fix:**
  Expose a reactive query flow from `FlashChatRepository` backed by `OrgDirectoryDao.searchMembers(query)`, pass matching colleague conversation IDs into `FlashChatListSearchMath.filterChats`, and render an explicit "Colleagues from company directory" section in `FlashChatListScreen` offering a "Start chat" CTA.
- **Proving Test:**
  `com.transfer.flash.ui.chat.FlashChatListSearchTest.ORGT_U40_searchColleaguesFromDirectorySnapshotFlow`.

---

### Finding 4: Security / Trust Leakage — Chat Header and List Row Show "Colleague" Chip Even When Offline Lease Has Expired
- **Path:Line:**
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:931-933, 1139, 1518`
  - `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolver.kt:188-189`
- **Concrete Failure Scenario:**
  1. In `RealFlashChatRepository.kt:931-933`:
     ```kotlin
     val isColleaguePeer = !entity.isGroup && !isTrustedPeer(entity.id) &&
         (orgPeerResolver?.isKnownColleague(entity.id) == true || effectiveIsOrgPeer(entity.id))
     ```
  2. In `RealOrgPeerResolver.kt:188-189`:
     ```kotlin
     override fun isKnownColleague(deviceId: String): Boolean =
         snapshotIndex.containsKey(deviceId)
     ```
  3. `isKnownColleague` performs an unconditional map lookup against `snapshotIndex` without checking `lease` or `isLeaseValid()`.
  4. If a device has been offline for 90 days past the 30-day grace period, or if the user's system clock was rolled back past the 5-minute skew tolerance, `orgPeerResolver.lease.value` transitions to `OrgLease.Expired`.
  5. Despite the lease being completely expired, `orgPeerResolver.isKnownColleague(entity.id)` continues to return `true`!
  6. The chat list row and conversation header continue to display the green "Colleague" trust chip, falsely attesting to the user that the peer is an active, verified colleague.
  7. **Plan Contradiction:** Violates Plan B §4 Step B2.4 & Phase B3 and ADR-134 ("In both expired states, resolve(deviceId) returns null ... and UI renders honest reconnect notices").
- **Recommended Fix:**
  `isKnownColleague(deviceId)` must check lease validity (`isLeaseValid(clock())`). If the lease is expired or clock is invalid, it must return `false`, or return an explicit trust status so UI components can display an expired badge rather than a verified badge.
- **Proving Test:**
  `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.ORGT_U41_expiredLeaseSuppressesColleagueChipInChatListAndHeader`.

---

### Finding 5: UX Defect — Raw Epoch Milliseconds Displayed in Enterprise Hub Settings
- **Path:Line:**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashHubCard.kt:106-108`
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt:915`
- **Concrete Failure Scenario:**
  1. `FlashOrgTrustStatusRow` in `FlashHubCard.kt:106-108` is declared with default:
     ```kotlin
     formatDate: (Long) -> String = { ms -> ms.toString() }
     ```
  2. In `FlashSettingsScreen.kt:915`, it is called without arguments:
     ```kotlin
     FlashOrgTrustStatusRow(state = model.orgTrustState)
     ```
  3. When an enrolled user opens Settings -> Organization hub, `orgTrustStatusText` maps `OrgTrustState.Valid(validUntilMs)` to:
     `"Colleague trust valid until 1731456000000"`!
  4. Plan B §2.5 explicitly specifies: `"Colleague trust valid until <date> (e.g. 'Valid until 12 Nov')"`, and Plan B §7 Situation 17 requires locale date formatting.
  5. Rendering a raw 13-digit Unix millisecond timestamp is completely incomprehensible to end users.
- **Recommended Fix:**
  Provide a platform-appropriate date formatting lambda in `FlashSettingsScreen.kt` using `java.text.DateFormat` or a shared multiplatform formatter that produces strings like `"12 Nov 2026"`.
- **Proving Test:**
  `com.transfer.flash.ui.settings.FlashSettingsScreenTest.ORGT_U42_orgTrustRowFormatsDateHumanReadable`.

---

### Finding 6: Contract Violation — Hardcoded `clockOk = true` and `hubSupports = true` in Host Shells
- **Path:Line:**
  - `app/src/main/java/com/transfer/flash/MainActivity.kt:797-798`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:1011-1012`
- **Concrete Failure Scenario:**
  1. Both `MainActivity.kt:797-798` and `DesktopShell.kt:1011-1012` instantiate `orgTrustState` as:
     ```kotlin
     FlashHubStatusMath.orgTrustState(
         lease = orgLease,
         clockOk = true,
         hubSupports = true,
         nowMs = System.currentTimeMillis(),
     )
     ```
  2. Because `clockOk` and `hubSupports` are hardcoded to `true`:
     - If the client's clock is skewed backwards >5 minutes (Plan B §7 Situation 3), `orgTrustState` will NEVER evaluate to `OrgTrustState.ClockWrong`.
     - If the enterprise server does not support offline colleague trust (e.g. returns 404 per Plan B §7 Situation 21), `orgTrustState` will NEVER evaluate to `OrgTrustState.NotSupported`.
  3. The hosts fabricate healthy conditions, directly silencing two core user-facing error states mandated by Plan B Section 9 (`org_trust_clock` and `org_trust_unsupported`).
  4. **Contract Violation:** Violates `HUB-QUALITY-CONTRACT.md` §5 Rule 4 ("Truth in State & UI: Never fabricate or hardcode status when underlying state exists").
- **Recommended Fix:**
  Extract `clockOk` from `OrgPeerResolver.lease` (or system clock comparison against directory sync timestamp), and extract `hubSupports` from the enterprise server features / directory sync response. Pass live reactive states to `FlashHubStatusMath.orgTrustState`.
- **Proving Test:**
  `com.transfer.flash.MainActivityOrgTrustTest.ORGT_U43_clockSkewReflectsInOrgTrustState` and `com.transfer.flash.desktop.DesktopShellOrgTrustTest.ORGT_U43_unsupportedHubReflectsInOrgTrustState`.

---

### Finding 7: Privacy & Contract Defect — Missing Test Assertion on Lock-Screen Notification Privacy
- **Path:Line:**
  - `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5405-5417`
  - `core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt`
- **Concrete Failure Scenario:**
  1. Plan B §4 Phase B3 explicitly specifies: *"Lock-screen notification for ORG message: identical to normal message, NO '(Colleague)' in title or body — lock screens are visible to bystanders. Verified by test."*
  2. In `RealFlashChatRepository.kt:5416`, `onInboundTextMessageWithGroupTitle(threadId, frame.senderName, frame.text, null)` is triggered.
  3. However, in `RealFlashChatRepositoryColleagueTest.kt`, there is NO test asserting that the inbound notification parameters strictly omit "(Colleague)", org name, or organizational affiliation tags.
  4. Without an automated regression test, future modifications to `peerNameResolver` or notification formatting could inadvertently append company tags to lock-screen notifications, leaking corporate affiliation to bystanders.
- **Recommended Fix:**
  Add a dedicated unit test in `RealFlashChatRepositoryColleagueTest.kt` capturing `onInboundTextMessageWithGroupTitle` callback invocations and asserting that title and body for colleague messages match standard messages and contain zero "(Colleague)" or company indicators.
- **Proving Test:**
  `com.transfer.flash.core.messaging.RealFlashChatRepositoryColleagueTest.ORGT_U44_inboundColleagueMessageNotificationOmitsColleagueTagForLockScreenPrivacy`.

---

### Finding 8: Bypassed API & Defect — Nearby Screen "Message" CTA Never Calls `startColleagueConversation`
- **Path:Line:**
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:1752-1755`
  - `app/src/main/java/com/transfer/flash/MainActivity.kt:2565-2572`
- **Concrete Failure Scenario:**
  1. In Phase B2, `startColleagueConversation(deviceId)` was implemented in `RealFlashChatRepository` with extensive validation and exact Section 9 error returns (`org_start_self`, `org_trust_never`, `org_start_expired`, `org_start_not_member`, `org_start_removed`).
  2. In both Android and Desktop hosts, clicking "Message" on a nearby colleague executes:
     ```kotlin
     onChatClick = { peer ->
         selectedChatConversationId = peer.id
         chatRepository.openConversation(peer.id)
         nav.navigate(FlashDestination.Conversation, conversationId = peer.id)
     }
     ```
  3. `openConversation` merely updates `activeConversationId.value`. It NEVER invokes `startColleagueConversation`!
  4. If the peer is an unpaired colleague, no conversation row is seeded in SQLite. More critically, if the lease has expired or the colleague was removed from the snapshot, `openConversation` performs ZERO error checks.
  5. The user is navigated to an unseeded blank conversation screen, completely bypassing the error handling and user-facing notifications built in Phase B2.
- **Recommended Fix:**
  In `onChatClick`, check if `peer.isColleague && !peer.isTrusted`. If so, invoke `chatRepository.startColleagueConversation(peer.id)`. On failure, display the exact Section 9 error message via a snackbar or alert dialog; on success, navigate to the newly seeded conversation.
- **Proving Test:**
  `com.transfer.flash.desktop.DesktopShellTest.ORGT_U45_onChatClickColleagueSeedsConversationViaStartColleagueConversation` and `com.transfer.flash.MainActivityNearbyTest.ORGT_U45_onChatClickColleagueInvokesStartColleagueConversation`.

---

### Finding 9: Accessibility Defect — TalkBack Users Blinded to Colleague Badge on Nearby Screen
- **Path:Line:**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/nearby/FlashNearbyScreen.kt:660-662, 686-695`
- **Concrete Failure Scenario:**
  1. In `FlashNearbyScreen.kt:660-662`, `PeerRow` sets:
     ```kotlin
     .semantics(mergeDescendants = true) {
         contentDescription = "${peer.name} nearby, $ctaLabel button"
     }
     ```
  2. Because `mergeDescendants = true` with an explicit `contentDescription` completely overrides and swallows all child semantics, the child `FlashColleagueBadge` (which sets `contentDescription = FlashOrgStrings.ORG_CHIP_A11Y`) is completely silenced.
  3. A visually impaired user navigating Nearby with TalkBack hears:
     `"Alice nearby, Message button"` (or `"Bob nearby, Pair button"`).
  4. There is ZERO indication that Alice is a verified corporate colleague. The TalkBack user cannot distinguish between a verified colleague and an unknown stranger.
  5. **Contract Violation:** Violates `HUB-QUALITY-CONTRACT.md` §5 Rule 8 (Accessibility) and Plan B §4 Phase B3.
- **Recommended Fix:**
  Incorporate colleague verification into the merged `contentDescription`:
  ```kotlin
  val colleagueDesc = if (peer.isColleague) ", ${FlashOrgStrings.ORG_CHIP_A11Y}" else ""
  contentDescription = "${peer.name}$colleagueDesc nearby, $ctaLabel button"
  ```
- **Proving Test:**
  `com.transfer.flash.ui.nearby.FlashNearbyAccessibilityTest.ORGT_U46_peerRowContentDescriptionIncludesColleagueVerification`.

---

### Finding 10: Test Defect — `DesktopNearbyStateTest` Tests Mock Instead of Live Session Gate
- **Path:Line:**
  - `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopNearbyStateTest.kt:220-250`
- **Concrete Failure Scenario:**
  1. In `ORGT_U37_nearbyUiStateOf_preserves_isColleague_and_orgName_for_unpaired_colleagues`:
     ```kotlin
     val state = nearbyUiStateOf(
         trusted = emptyList(),
         discovered = discovered,
         ...
         isOrgPeer = { it == colleagueId },
         orgName = "Acme Corp",
     )
     ```
  2. The test passes a static lambda `isOrgPeer = { it == colleagueId }` and asserts that the discovered endpoint receives `isColleague = true`.
  3. The test suite does not model or test whether the endpoint has established a live TLS session with SPKI fingerprint binding.
  4. Consequently, the test suite actively codifies and protects the spoofing defect described in Finding 1.
- **Recommended Fix:**
  Update `DesktopNearbyStateTest` to assert that discovered mDNS endpoints without an authenticated TLS session have `isColleague = false`.
- **Proving Test:**
  `com.transfer.flash.desktop.DesktopNearbyStateTest.ORGT_U47_unauthenticatedDiscoveredPeerMustNotBeColleague`.

---

### Finding 11: Accessibility / Semantics Risk — `FlashChatListRowContent` Semantics Lacks `mergeDescendants`
- **Path:Line:**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListRow.kt:209`
- **Concrete Failure Scenario:**
  1. In `FlashChatListRow.kt:209`, `.semantics { contentDescription = rowDescription }` is applied to the root container without `mergeDescendants = true`.
  2. The children inside `FlashChatListRowContent` include `FlashColleagueTrustChip` (which has its own `semantics { contentDescription = FlashOrgStrings.ORG_CHIP_A11Y }`), avatar presence dots, and timestamp texts.
  3. Without `mergeDescendants = true`, accessibility services on Android traverse the children individually after reading `rowDescription`, leading to duplicate announcements (e.g. reading "Colleague verified by the company server" twice in a row) or stuttering focus boxes.
- **Recommended Fix:**
  Apply `Modifier.semantics(mergeDescendants = true) { contentDescription = rowDescription }` to consolidate the row into a single accessibility node.
- **Proving Test:**
  `com.transfer.flash.ui.chat.FlashChatListRowAccessibilityTest.ORGT_U48_chatListRowMergesDescendantsWithColleagueDescription`.

---

### Finding 12: UI Layout Risk — Title Row Clipping with Long Colleague Names on Narrow Viewports
- **Path:Line:**
  - `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashChatListRow.kt:254-270`
- **Concrete Failure Scenario:**
  1. In `FlashChatListRowContent`, the title and colleague chip are laid out inside a `Row` with `horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)`.
  2. On small screens (e.g. 320dp width devices) or when the user has set 200% font scaling (accessibility display size), a long colleague name ("Dr. Bartholomew Alexander-Montgomery") combined with the `FlashColleagueTrustChip`, the timestamp ("10:45 AM"), and unread badge leaves less than 30dp for the title text.
  3. The title gets abruptly truncated to 2-3 characters without proper ellipsis breathing room or pushes the timestamp out of view.
- **Recommended Fix:**
  Constrain the colleague chip width or wrap gracefully, ensuring minimum readable character widths and verifying with 200% font scale screenshot/layout tests.
- **Proving Test:**
  `com.transfer.flash.ui.chat.FlashChatListRowLayoutTest.ORGT_U49_colleagueChipLayoutOnNarrowScreenWithLongTitle`.

---

## Part 2: Evaluation of 5 Extra Situations Handled Beyond the Plan

The implementer identified 5 edge cases addressed beyond the basic plan specification. Here is the hostile evaluation of each:

1. **Paired Trust Outranking Colleague Badge (`isColleaguePeer = isColleague && !isTrusted`):**
   - *Evaluation:* **SOUND.** Plan B §6 and ADR-134 establish a strict 3-tier hierarchy: Paired (Tier 3) > Colleague (Tier 2) > Stranger (Tier 1). If a colleague undergoes bilateral SAS pairing (Pairing v2), they possess stronger cryptographic attestation than an org snapshot. Suppressing the colleague badge avoids visual clutter and contradictory dual badges.
2. **Defensive Fallback to `"company server"` in `FlashOrgStrings.chipText`:**
   - *Evaluation:* **SOUND IN THEORY, UNUSED IN PRACTICE.** When `orgName` is null or blank, falling back to "company server" matches Plan B Section 9 string `org_chip_a11y`. However, as revealed in Finding 2, `FlashColleagueTrustChip` hardcodes `"Colleague"` and never even calls `FlashOrgStrings.chipText(orgName)`, rendering this extra situation largely moot.
3. **Searching Existing Chats by Organisation Name in `FlashChatListSearchMath`:**
   - *Evaluation:* **SUPERFICIAL.** While filtering existing conversations by `orgName` is helpful, it only operates on chats already in the conversation database. It completely fails to solve the required directory snapshot search (Finding 3), where users search for un-chatted colleagues.
4. **Colleague Chip Suppression on Group Conversations (`!state.isGroup`):**
   - *Evaluation:* **SOUND.** Hub-linked groups are specified for Phase B4. Gating `FlashColleagueTrustChip` to 1:1 direct conversations prevents misleadingly badging a group containing mixed participants as an org colleague.
5. **Structured Accessibility Ordering in `chatListRowContentDescription`:**
   - *Evaluation:* **SOUND LOGIC, INCOMPLETE INTEGRATION.** Formatting TalkBack announcements as `"<Title>, Colleague verified by the company server, <Preview>, <Timestamp>, <Unread>"` follows Android accessibility best practices. However, as noted in Finding 9, Nearby screen completely overrides and omits this information, making accessibility inconsistent across screens.

---

## Part 3: Contract Compliance Checklist

- [ ] **Contract §2 Explain-Back:** NOT UPDATED. `plan-b-implementation-log.md` only contains Phase B2 explain-back; no Phase B3 explain-back was written.
- [ ] **Contract §4 Verification Commands:** `tools/flash-gradle.ps1` ran cleanly for unit suites, but mutex lock management must remain disciplined.
- [x] **Contract §5 Rule 1 (Honesty):** Section 9 strings matched verbatim in string resources and `FlashOrgStrings.kt`.
- [ ] **Contract §5 Rule 4 (Truth in State & UI):** VIOLATED. Hardcoded `clockOk = true` and `hubSupports = true` in `MainActivity.kt` and `DesktopShell.kt`.
- [ ] **Contract §5 Rule 8 (Accessibility):** VIOLATED. Nearby screen TalkBack description swallows colleague badge.
- [ ] **Contract §5 Rule 10 (No Dead Parameters):** VIOLATED. `orgName` in `FlashColleagueTrustChip` is dead code.
- [x] **Contract §6 Worktree & Git Hygiene:** Checked. Subagent B made zero commits, zero stashes, and touched zero production code files.

---

## Part 4: Required Remediation Plan Before Resubmission

1. **Fix mDNS Colleague Spoofing (Finding 1):** Gate `isColleague` on the Nearby screen on active authenticated live TLS sessions; never badge raw mDNS announcements.
2. **Wire `orgName` in `FlashColleagueTrustChip` (Finding 2):** Use `orgName` in `FlashColleagueTrustChip` and remove the hardcoded string.
3. **Implement Directory Snapshot Search (Finding 3):** Wire `OrgDirectoryDao.searchMembers(query)` into `FlashChatListScreen` and render a "Start chat with colleague" section.
4. **Enforce Lease Expiry on Colleague Badges (Finding 4):** Ensure `isKnownColleague` checks `isLeaseValid()`, suppressing verified badges when the lease has expired.
5. **Format Epoch Timestamps in Settings (Finding 5):** Pass a human-readable date formatter to `FlashOrgTrustStatusRow`.
6. **Eliminate Hardcoded Flags in Host Shells (Finding 6):** Connect `clockOk` and `hubSupports` to live resolver and hub state.
7. **Wire `startColleagueConversation` into Nearby "Message" CTA (Finding 8):** Replace raw `openConversation` calls with `startColleagueConversation` and handle errors cleanly.
8. **Fix Nearby TalkBack Semantics (Finding 9):** Include colleague verification in `PeerRow`'s merged `contentDescription`.

---

## Part 5: Lead Agent Remediation & Resolution Log (All Findings F01–F12 Resolved)

**Remediation Date:** 2026-10-14  
**Remediator:** Lead Agent S1 (Org-Trust)  
**Status:** ALL FINDINGS RESOLVED & VERIFIED

### Resolution Details for Findings F01–F12:

- **F01 (Nearby mDNS Spoofing Gate) — RESOLVED:**
  - *Fix:* Added `hasAuthenticatedOrgSession(deviceId: String): Boolean` to `AppEngine.kt` and `DesktopEngine.kt`, checking `network.activeSessions.value.containsKey(FlashDeviceId(deviceId)) && isOrgPeer(deviceId)`. In `MainActivity.kt:2549` and `DesktopShell.kt:1255`, nearby peer mapping is gated on `engine.hasAuthenticatedOrgSession(peer.id)`. Raw unauthenticated mDNS announcements never receive `isColleague = true`.
  - *Verification:* `DesktopNearbyStateTest.ORGT_U47_unauthenticatedDiscoveredPeerMustNotBeColleague`.

- **F02 (Dead Parameter in `FlashColleagueTrustChip`) — RESOLVED:**
  - *Fix:* In `FlashChatHeader.kt:173`, `FlashColleagueTrustChip` renders `FlashOrgStrings.chipText(orgName)` with `maxLines = 1, overflow = TextOverflow.Ellipsis`. TalkBack accessibility is dynamically configured: `semantics { contentDescription = "Colleague verified by ${orgName ?: FlashOrgStrings.COMPANY_SERVER}" }`.
  - *Verification:* Verified chip text renders `FlashOrgStrings.chipText(orgName)` and ellipsis is enforced.

- **F03 (Directory Snapshot Colleague Search) — RESOLVED:**
  - *Fix:* Added `searchColleagueIds(query, limit)` to `OrgPeerResolver` and `searchColleagues(query, limit)` to `FlashChatRepository` & `RealFlashChatRepository`. In `FlashChatListScreen.kt`, wired `colleagueMatchIds` into `FlashChatListSearchMath.filterChats(...)`. In `MainActivity.kt` and `DesktopShell.kt`, active queries invoke `chatRepository.searchColleagues(q)` to match colleagues even if not previously messaged.
  - *Verification:* `OrgPeerResolverTest.ORGT_U40_searchColleagueIds_queries_snapshot_when_lease_valid` and `RealFlashChatRepositoryColleagueTest.ORGT_U40_searchColleagues_returns_matching_device_ids`.

- **F04 (Lease Expiry Gate on Colleague Verification) — RESOLVED:**
  - *Fix:* In `OrgPeerResolver.kt:145-149`, `isKnownColleague(deviceId)` enforces `evaluateLeaseLocked(clock()) is OrgLease.Valid && snapshotIndex.containsKey(deviceId)`. When the lease expires (either past grace period or clock rollback), `isKnownColleague` returns `false`, immediately suppressing colleague badges across chat headers and chat list rows.
  - *Verification:* `OrgPeerResolverTest.ORGT_U24_lease_evaluation_handles_clock_rollback` and `RealFlashChatRepositoryColleagueTest.ORGT_U41_expiredLease_suppressesColleagueBadge`.

- **F05 (Raw Epoch Milliseconds Formatted to Human Date) — RESOLVED:**
  - *Fix:* In `FlashHubStatusMath.kt:105-115`, added `formatEpochDate(epochMs: Long): String` leveraging `platformDayLabelCalendar().formatDate(...)`. Updated `orgTrustStatusText` and `FlashOrgTrustStatusRow` to display formatted dates (e.g., "12 Nov 2026") instead of raw numeric timestamps.
  - *Verification:* `FlashHubStatusMathTest.formatDate_producesHumanReadableDate`.

- **F06 (Host Flags Connected to Live State) — RESOLVED:**
  - *Fix:* In `MainActivity.kt:1082-1087` and `DesktopShell.kt:1096-1100`, eliminated hardcoded flags: `clockOk` is derived from `(orgLease as? OrgLease.Expired)?.reason != ExpiredReason.CLOCK` (detecting clock rollback), and `hubSupports` is wired from hub registration state.
  - *Verification:* Verified live state propagates to `orgTrustState(lease, clockOk, hubSupports, nowMs)`.

- **F07 (Lock-Screen Notification Privacy) — RESOLVED:**
  - *Fix:* Plan B §4 Phase B3 specifies that notifications for colleague messages must omit corporate branding `(Colleague)` on lock screen notifications to prevent shoulder surfing. Verified that `onInboundTextMessageWithGroupTitle` delivers sender display name without injecting corporate tags into Android notification channels.
  - *Verification:* `RealFlashChatRepositoryColleagueTest.ORGT_U44_lockScreenNotificationOmitsColleagueBadge`.

- **F08 (Nearby "Message" Action Gated with `startColleagueConversation`) — RESOLVED:**
  - *Fix:* In `MainActivity.kt:2575-2595` and `DesktopShell.kt:1270-1286`, when tapping "Message" on an unpaired colleague (`peer.isColleague && !peer.isTrusted`), `startColleagueConversation(peer.id)` is invoked. Success navigates to the conversation; failure surfaces user feedback (`FlashOrgStrings.ORG_START_NOT_MEMBER`, etc.) via Toast or Snackbar.
  - *Verification:* Verified interaction path and error handling for all 5 error conditions.

- **F09 (Nearby TalkBack Accessibility) — RESOLVED:**
  - *Fix:* In `FlashNearbyScreen.kt:390-398`, `PeerRow` semantics integrates colleague status: `val colleagueSuffix = if (peer.isColleague) ", ${FlashOrgStrings.ORG_CHIP_A11Y}" else ""`, producing TalkBack announcement `"${peer.name}$colleagueSuffix nearby, $ctaLabel button"`.
  - *Verification:* `FlashNearbyAccessibilityTest.ORGT_U46_peerRowContentDescriptionIncludesColleagueVerification`.

- **F10 (Nearby Test Suite Gate Verification) — RESOLVED:**
  - *Fix:* Replaced mock assertions in `DesktopNearbyStateTest.kt` with `ORGT_U47_unauthenticatedDiscoveredPeerMustNotBeColleague`, verifying that discovered mDNS endpoints without an authenticated TLS session have `isColleague = false`.
  - *Verification:* `DesktopNearbyStateTest.ORGT_U47_unauthenticatedDiscoveredPeerMustNotBeColleague` passes.

- **F11 (Chat List Row Merged Semantics) — RESOLVED:**
  - *Fix:* In `FlashChatListRow.kt:209`, added `mergeDescendants = true` on `.semantics { contentDescription = rowDescription }`, ensuring accessibility services treat the entire row as a single cohesive announcement without stuttering child traversals.
  - *Verification:* Tested in Compose semantics tree inspection.

- **F12 (Title Row Layout Clipping Prevention) — RESOLVED:**
  - *Fix:* In `FlashChatListRow.kt:256`, applied `Modifier.weight(1f, fill = false)` on the title/chip layout to ensure title and chip cooperate cleanly without clipping or pushing timestamps offscreen on narrow 320dp viewports.
  - *Verification:* Verified layout constraints under narrow viewport calculations.

