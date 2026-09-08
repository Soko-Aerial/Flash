# Group UI + feature phases — owner-reported work stream (2026-09-08)

Source of truth for this work stream; survives session compaction. Execute phases **strictly
one at a time**; verify + log after each (`logs/progress.md`, `logs/handoff.md`, tick boxes
here). Direct-chat behavior must stay byte-identical.

## Phase A — Groups render as groups (root fix) — DONE 2026-09-08 (verified)
- Header branches on `conversationDao.get(id).isGroup`: stored group title beats the UUID;
  `isGroup`/`memberCount`/`memberInitials`/`onlineCount`/`showCallActions=false` filled;
  direct path extracted verbatim as `directHeaderState`. Chat-list group presence is
  aggregate (≥1 member online ⇒ Online). Seed via `groupTitleCache` (no blocking Room read
  on the caller thread — the `runBlocking` attempt starved the shared test executor).
- Test: `opening a group conversation renders a group header with the real name and roster`.
- Fix en route: seed moved from `runBlocking` to `groupTitleCache` (stamped at local +
  inbound create).

## Phase B — Real group member sheet — DONE 2026-09-08 (code + tests verified)
- `FlashConversationUiState.members` (additive): real roster — names, per-member `isOnline`
  from live sessions, owner role from the stored role column, shared `toMemberUi`.
- `FlashConversationScreen` prefers `state.members`, falls back to `groupMembersFromHeader`.
- Test extended: roster ids + owner/member roles pinned.

## Phase C — Header/count polish — DONE 2026-09-08 (code + tests verified)
- Subtitle "N members · M online" already existed + unit-pinned (`FlashGroupHeaderLogicTest`);
  Phases A/B feed it real counts. Chat-list group rows show an "M online" chip on the avatar
  (`FlashChatListItemUi.groupOnlineCount` + `FlashChatListGroupOnlineBadge`).

## Phase D — Three-dot conversation menus — DONE 2026-09-08 (code + tests verified)
- `FlashConversationMenuMath` + `FlashConversationMenu`; direct: profile/search/revoke
  (paired only)/clear; group: info/add members/leave (hidden when memberCount ≤ 1)/search.
- `FlashAddMembersSheet` + `FlashLeaveGroupDialog`; screen gains additive
  `conversationId`/`addablePeers`/`onAddGroupMembers`/`onLeaveGroup`/`onClearConversation`;
  MainActivity wiring. Test: `FlashConversationMenuMathTest`.

## Phase E — Whole-app essentials audit — DONE 2026-09-08
- `docs/ui/app-essentials-audit.md`: exists/partial/missing with file refs + prioritized
  follow-up list. Feeds F5/F6.

---

# F-series — owner-reported media/late-join defects + audit follow-ups (2026-09-08)

Plan file (approved): `C:\Users\KaliOxygen\.claude\plans\atomic-bubbling-eclipse.md`.
Evidence-backed root causes:
1. `sendAttachment`/`onInboundAttachment`/`recordCallEvent` upsert
   `ConversationEntity(isGroup=false, title=groupId)` — full-row REPLACE **clobbers group
   rows** (why a group voice note turned the header into the chat id + 1:1 details sheet).
2. `openStreamChannel` `?: active.values.firstOrNull()` (holder :585, engine :465) leaks a
   group-addressed transfer to an **arbitrary single peer** (why only one device got it).
3. Inbound attachments thread under the SENDER id; `ChunkFrame.FileStart` carries no group
   context (why it showed in Transfers, not the group chat).
4. The `Add` receiver gate requires local membership rows the added device doesn't have, and
   the Add frame carries no name/roster (why ⋮-added devices never got the group).
5. `relaunchSend` reproduces identical `(transferId, wireFileId)` and the receiver resumes
   from it; `openSource` streams `file://` — any-holder re-pulls work with existing
   machinery (basis for F4).

Execute strictly one at a time; direct 1:1 behavior byte-identical; tick + log after each.

## F1 — Stop the damage — STATUS: DONE 2026-09-08 (code + tests verified)
- [x] `touchConversation` helper replaces the three clobbering upserts (attachment send,
      inbound attachment, call rows): group rows update `sortOrder` only; direct rows keep
      the exact prior derivation.
- [x] `openStreamChannel` (holder + engine): named-peer-without-session → null; anonymous
      fallback only for a null peer id (commented at both sites).
- [x] Interim honest gate: `sendAttachment` rejects group rows with a FlashLog warning.
- [x] Test: `sending an attachment to a group never clobbers the group conversation row`
      (isGroup/title/provenance survive; no attachment row minted).
- Verify: `:core:messaging` tests green (batch + isolated rerun), all three modules compile.
  Device re-test owed by owner (header survives a group voice-note tap; no cross-peer leak).

## F2 — Late-join bootstrap — STATUS: DONE 2026-09-08 (code + tests verified)
- [x] `GroupWireFrame.State` + `RosterEntry` (name, creator, full per-member versioned
      roster); codec `action=state` with indexed `m/n/r/j/v/o/a` fields + `memberCount`,
      oversized roster rejected, round-trip pinned (escaping included).
- [x] Receiver `State` branch: gate = trusted transport peer + sender active in roster +
      this device in roster + unique roster ids + size cap; conversation materialized via
      the versioned merge — a state entry can never resurrect a newer leave tombstone.
- [x] `addGroupMembersLocked`: newcomers receive the `State` bootstrap, existing members
      the `Add` (fixes "⋮-added device never got the group").
- [x] Tests: fresh-device bootstrap (roles/creator/title), untrusted-peer drop, tombstone
      precedence, codec round-trip + oversized-roster rejection. (En route: the first
      "non-roster sender" test modeled an indistinguishable self-consistent roster from a
      trusted peer — rewritten to the enforceable boundary, the untrusted transport peer.)
- Verify: 65 messaging tests green; `:app:assembleDebug` + engine compile clean.
  Device gate owed: ⋮-added device immediately sees the group with the full roster.

## F3 — FLASH_GSYNC catch-up (old messages) — STATUS: DONE 2026-09-08 (code + tests verified; device gate owed)
- [x] `GroupSyncPolicy` (pure): deterministic election `(tierRank, FNV-1a(deviceId+msgId))`,
      TTL/cursor/budget message selection, paced push interval, claim-window check — 7 tests.
- [x] `MessageDao.historyAfter` (mirror of historyBefore, composite cursor) +
      `GroupMemberDao.activeGroupIdsFor`.
- [x] Repository flow: `sendGroupSyncRequests(peer)` (unicast per active group, cursor from
      newest row, tier limits); `handleSyncRequest` (holder computes owned set, records round,
      broadcasts `SyncClaim`, arms backup); `handleSyncClaim` (merge claimants);
      `armBackupPush` (after BACKUP_DELAY_MS, ack-gated, rank-0-only, paced); `handleSyncPush`
      (idempotent insert + notification); `handleSyncAck` (retires round). Round state bounded
      by ack/TTL semantics; `syncRequesters` maps syncId → requester for unicast push/ack.
- [x] Both hosts call `sendGroupSyncRequests(peer)` on every session-up edge.
- [x] Codec: SyncRequest + SyncClaim (now tier-carrying) round-trip tests.
- Verify: `:core:messaging` green (67 tests); persistence 38 with only the 12 known Windows
  DataStore failures; `:app:assembleDebug` green. Device gate (3 devices, one offline 5 min,
  exactly-once catch-up; LOW returner ≤ 5 msg/s from logs) owed by owner.
- Deferred deliberately to F4: SyncPush attachment metadata fields (ships with the media path
  that consumes them).

## F4 — Group media end-to-end — STATUS: core landed 2026-09-08; re-pull (GFETCH) deferred to F4b
- [x] `GroupWireFrame.GroupMedia` + `FLASH_GMEDIA` codec (all identity fields; round-trip test).
- [x] `FlashTransferRepository.sendFile(wireFileId: String?)` overload — group fan-out shares
      ONE wire identity (1:1 default unchanged).
- [x] Receiver: `pendingGroupMedia` map parked by the inbound GMEDIA branch (membership-gated),
      consumed at ACCEPT time in `onInboundAttachment` — rows thread into the GROUP
      conversation under the sender's messageId (re-pull dedup); consult-at-accept removes the
      WS/data-channel arrival race by construction.
- [x] Sender: `beginGroupAttachment` (repository; interface-defaulted) sends the intro per
      member; MainActivity's `onSendFile`/`onSendVoiceMessage` group branches loop active
      members → intro + `sendFile(wireFileId = shared)` each, then mint the sender's own row
      keyed by the shared wire id. F1's interim rejection gate replaced by the real path.
- [x] Test fix en route: the group-header test now awaits the POPULATED header
      (`isGroup && memberCount > 0`) — the title-cache seed is isGroup=true with no roster;
      `first { isGroup }` raced it deterministically after F4's additions.
- [ ] F4b (next window): any-holder re-pull (`FLASH_GFETCH` naming wireFileId → holder
      re-sends with the original identity from its local path) + SyncPush attachment metadata.
- Verify: app assembles; messaging + ui/chat tests green. Device gate owed: image/video/voice
  to group appears in chat on all members with progress; mid-transfer drop resumes.

## F5/F6 — Audit follow-ups (from `docs/ui/app-essentials-audit.md`) — STATUS: F5.1 + F5.2 DONE 2026-09-08 (code + tests verified); remaining items planned

Each item is its own small phase. All evidence below was verified in code this session. Execute
one at a time; direct 1:1 behavior byte-identical; verify + log after each.

### F5.1 — Date separators — STATUS: DONE 2026-09-08 (code + tests verified)
- **Evidence:** no `DateSeparator`/`isSameDay` anywhere in `ui/` or `core/`; bubbles carry only
  per-message time labels (`formatTime` = `h:mm a`, RealFlashChatRepository.kt ~2257);
  `FlashMessageList` maps messages 1:1 into `itemsIndexed` with no header rows.
- **Design:** in the repository's conversation mapping (the `contentFlow` combine), interleave
  day-boundary markers: when a message's local calendar day differs from the previous message's
  (list is newest-first, so compare each entity with the one AFTER it), emit a day label
  ("Today" / "Yesterday" / "d MMM yyyy"). Two implementation shapes, pick at execution:
  (a) additive `FlashMessageUi.daySeparator: String?` flag consumed by `FlashMessageList` to
  render a `item { }`-style divider before that bubble; or (b) a sealed list of rows. Prefer
  (a) — no `FlashMessageUi` schema break, LazyColumn keys stay `message.id`. Day math must be
  pure and injectable (`dayLabelFor(sentAt, nowMs, locale)` in `:core:messaging` util) so it is
  JVM-testable across midnight/DST boundaries. Calendar use stays off the hot path: compute the
  label once per row during the existing mapping, never per recomposition.
- **Files:** `RealFlashChatRepository.kt` (combine), `model/FlashMessagingModels.kt`
  (`daySeparator` field), `FlashMessageList.kt` (render), new util + tests.
- **Tests:** midnight crossing, DST gap, yesterday boundary, same-day streak emits one label,
  tombstoned rows don't shift labels unexpectedly.
- **Verify:** unit tests + device (open a thread with messages from two days).

- **Implemented:** `FlashMessageUi` has an additive nullable `daySeparator`. Common messaging owns
  pure/injectable `dayLabelFor` and separator assignment; Android/JVM actuals provide local calendar,
  time-zone, and locale handling without `java.*` in `commonMain` or a new dependency. The Android
  repository computes labels once in its existing filtered mapping. `FlashMessageList` renders the
  label as a centered accessible heading inside the existing message item, preserving `message.id`
  keys and breaking bubble grouping at day boundaries.
- **Verification:** common tests cover same day, yesterday, older labels, same-day streaks, and
  filtered-row boundaries; JVM tests cover midnight and a DST spring-forward gap. Both
  `:core:messaging` and `:ui:chat` Android/JVM suites passed on 2026-09-08. Physical two-day thread
  confirmation remains owed.

### F5.2 — Group notification naming — STATUS: DONE 2026-09-08 (code + tests verified)
- **Evidence:** the repository fires `onInboundTextMessage(groupId, senderName, text)`
  (group Message branch of `onInboundGroupWireFrame`), and the app wires it straight to
  `FlashNotificationManager.showMessage`, which titles the notification by **sender name**
  (`DiscoveryEngineHolder.kt` ~589, `FlashNotificationManager.showMessage` → `title =
  senderName`). For a group the title should be the group name and the body "Sender: text".
- **Design:** extend the callback to carry the group title — either an additive defaulted
  param `groupTitle: String? = null` on `onInboundTextMessage`/`onInboundAttachment`
  (repository knows the conversation row; pass `conversationDao.get(id)?.title` for groups),
  or a separate `onInboundGroupMessage(groupId, groupTitle, senderName, text)` callback. Prefer
  the additive-param shape (fewer call sites). `FlashNotificationManager.showMessage` gains a
  defaulted `groupTitle: String? = null`: title = groupTitle ?: senderName, body =
  if (groupTitle != null) "$senderName: $text" else text. Attachment equivalent: body
  "$senderName: $kind: $fileName".
- **Files:** `RealFlashChatRepository.kt` (both branches: direct group Message ingestion AND
  the F4 media row), `DiscoveryEngineHolder.kt` wiring, `FlashNotificationManager.kt`.
- **Tests:** notification title/body selection logic (pure part of FlashNotificationManager if
  extractable; else logged per the established rationale).
- **Verify:** unit tests + device (backgrounded group message shows "Team" as title).

- **Implemented:** Android-host callbacks now carry nullable stored group titles without moving any
  KMP source sets or breaking legacy internal callback sites. Group text (including sync push) and
  accepted group media pass the stored title; direct text/media explicitly pass `null` and retain
  their previous sender-title/plain-body behavior. `FlashNotificationManager` uses pure tested
  content selectors: group title + `"Sender: text"` or `"Sender: Kind: file"`.
- **Verification:** `:core:messaging:testAndroidHostTest`, `:app:testDebugUnitTest`, and
  `:app:assembleDebug` passed with the required JDK 21 on 2026-09-08. Physical background-device
  confirmation remains owed; code/test status is complete.

### F5.3 — Group typing fan-out — STATUS: DONE 2026-09-08 (code + focused tests verified; device gate remains)
- **Evidence:** `setTyping` sends `TypingFrame` to `conversationId` — for a group that is the
  groupId, which has no session, so it drops silently (RealFlashChatRepository ~1590); inbound
  group typing is not gated into `typingStates[groupId]` either.
- **Design:** outbound — in `setTyping`, branch on `conversationDao.get(id).isGroup`: iterate
  `groupMemberDao.activeMembers(id)` (minus self) and send the existing `TypingFrame` per
  member via `groupTransportSink` (the frame already carries groupId + memberId + name).
  Inbound — route group typing frames through `onInboundGroupWireFrame`'s membership gate into
  `typingStates[frame.groupId]` keyed by `frame.memberId` (the existing
  `typingByConversation[conversationId]` join in the header combine then renders named typing
  unchanged). Hosts: `FLASH_TYPING` decode currently goes to `onInboundWireFrame` — group
  detection is on `frame.conversationId`; simplest correct route is to keep the direct path for
  direct ids and let the group branch handle group ids (the repository can branch on
  `conversationDao.get`).
- **Files:** `RealFlashChatRepository.kt` (`setTyping`, `onInboundWireFrame` TypingFrame branch
  or a new group branch), host decode unchanged if the repository branches internally.
- **Tests:** pure part (which members receive the frame; membership gating of inbound) via the
  fakes; render path already covered by header tests.
- **Verify:** device (two members see "Alex is typing…" in a group).

- **Implemented:** `setTyping` now reads the stored conversation and preserves the original direct
  `MessageTransportSink` target/frame behavior for direct chats. For groups it sends the existing
  `MessageWireFrame.TypingFrame` once per active member except self, addressed through
  `MessageTransportSink`; `GroupTransportSink` remains restricted to `GroupWireFrame`.
- **Inbound security/routing:** both Android hosts preserve the wire `conversationId` and pass the
  authenticated transport peer id. Group typing is accepted only when the stored conversation is a
  group, the claimed member matches that transport peer, and the member is both trusted and active;
  accepted names publish under `typingStates[groupId]`. Direct inbound typing remains keyed to the
  transport peer exactly as before.
- **Verification:** focused repository coverage for direct send/receive compatibility, group
  recipients, self/inactive exclusion, correct sink use, and inactive/untrusted/spoof drops;
  `:core:messaging:testAndroidHostTest`, `:core:messaging:jvmTest`, and `:app:assembleDebug` passed
  with JDK 21 on 2026-09-08. Physical multi-device confirmation remains owed.

### F5.4 — "Delivered to M of N" (partial; data already persisted)
- **Evidence:** `group_deliveries` rows carry per-member state (Phase 1A); `GroupDeliveryDao`
  has `memberCount`/`deliveredCount`; no UI joins them into the outbound bubble.
- **Design:** repository — the conversation content combine gains a delivery-count flow for the
  active conversation's outbound group messages (`SELECT messageId, COUNT(*) total,
  SUM(state='DELIVERED') done FROM group_deliveries WHERE messageId IN (outbound ids of this
  conversation) GROUP BY messageId` — needs one new DAO query `observeDeliveryCounts`); join
  into `FlashMessageUi` as an additive `deliveredTo: Int?`/`deliveredTotal: Int?` pair (null
  for direct chats). UI — `FlashDeliveryStatusIcon`/`FlashFileMessageCard` language: when both
  non-null and `deliveredTotal > 1`, render the tick with "M/N" or a long-press detail;
  a 0-progress media bubble shows "Waiting for members" per the plan copy.
- **Files:** `GroupDeliveryDao.kt` (+1 query), `RealFlashChatRepository.kt` (join),
  `FlashMessagingModels.kt` (2 additive fields), `FlashDeliveryStatusIcon.kt` or bubble row.
- **Tests:** DAO query semantics; mapping (only outbound group messages get counts); UI math
  (M-of-N label) as a pure function.
- **Verify:** device (send to a 3-member group; ticks advance per member ack).

### F6.1 — Mark as unread (missing; low)
- **Evidence:** no `markUnread` anywhere; unread = `sentAt > lastReadCursor`'s message
  (`observeUnreadCounts`, MessageDao ~121).
- **Design:** conversation menu item (Phase D menu) "Mark as unread" → set
  `lastReadCursor` to the OLDEST inbound message id minus one position — simplest honest
  version: set it to null for the thread (DAO needs a `clearLastReadCursor` query), which
  counts every inbound message unread. UI badge then behaves exactly like any unread thread.
- **Files:** `ConversationDao` (+1 query), `RealFlashChatRepository.kt` (+1 API + menu action),
  `FlashConversationMenuMath` (add item), `MainActivity` wiring.
- **Tests:** DAO clear semantics + unread-count effect via `FlashDatabaseInvariantTest`.
- **Verify:** device (badge appears after marking a read thread unread).

### F6.2 — Delete-for-everyone (partial; low)
- **Evidence:** `deleteMessage` is a local tombstone (`markDeleted`); no wire frame.
- **Design:** new direct text frame `FLASH_DELTE localId=<id>` (name kept ASCII-safe; exact
  prefix decided at execution to avoid colliding with `FLASH_DATA`) sent to the peer for 1:1,
  and a group variant carrying groupId (membership-gated, sender must be the message's author
  or an owner — v1: author-only). Receiver tombstones idempotently (already IGNORE-safe) and
  drops any pending outbox row. Sender path: after local `markDeleted`, emit the frame per
  current conversation type.
- **Files:** `MessageWireFrame` or `GroupWireFrame` (+ frame + codec + round-trip test),
  repository (send + ingest branches), both host decoders (one line each).
- **Tests:** codec round-trip, author-only gate, idempotent receiver tombstone.
- **Verify:** device (deleted message disappears on both phones).

### F6.3 — Storage usage screen (missing; low)
- **Evidence:** no user-facing storage surface; received files accumulate under
  `FlashReceived/<transferId>/` (`DiscoveryEngineHolder` receive path); thumbnail trim exists
  internally (EXP-014) but nothing reports footprint.
- **Design:** Settings → Storage section: compute on demand (IO dispatcher, cached behind a
  refresh action) the size of the received-files root via `File.walkBottomUp`; show total +
  per-conversation breakdown is DEFERRED (needs a DB join of received files ↔ conversations;
  not modeled today — record honestly). Clear action: confirm dialog → delete directories for
  transfers whose rows are Completed AND older than N days, or a blanket "Clear received
  files" (with the same confirmation). Host-owned (app layer) — core never touches disk paths.
- **Files:** `FlashSettingsScreen.kt` (+section), new `FlashStorageMath` (pure size
  formatting + policy), `MainActivity` wiring (directory scan + delete), Settings model.
- **Tests:** `FlashStorageMath` (byte formatting, policy selection).
- **Verify:** device (footprint matches `du`-style reality; clear frees space).

### F5/F6 execution order (owner may reprioritize)
F5.2 (10-line, immediate UX win) → F5.1 → F5.3 → F5.4 → F6.1 → F6.2 → F6.3.
F4b (any-holder re-pull) stays queued ahead of F6.3 if media serving matters more than
storage reporting.

## Verification env (authoritative)
```bash
export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"
export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'
./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```
Baseline 1005 live / 0–12 known Windows `:core:persistence` DataStore failures / 0 skipped;
count live XML per module; `--continue` is load-bearing.
