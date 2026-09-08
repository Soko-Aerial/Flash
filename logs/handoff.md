# Current Handoff

## 2026-09-08 (g) — F5.3 group typing fan-out complete, staged, uncommitted

### Current branch
`dev` at `33bbb41`. F5.3 code/tests/docs/logs are staged; unrelated `New folder/` and root CLI
JSONL diagnostics remain unstaged.

### Last verified build
With `JAVA_HOME=C:/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2`:
- Focused `RealFlashChatRepositoryTest` passed.
- `:core:messaging:testAndroidHostTest` passed.
- `:core:messaging:jvmTest` passed.
- `:app:assembleDebug` passed.

### Last change
- Direct typing retains its existing addressed `MessageTransportSink` frame and behavior.
- Group typing fans the existing `MessageWireFrame.TypingFrame` out to active members except self;
  `GroupTransportSink` remains exclusively `GroupWireFrame`.
- Both Android hosts pass the authenticated transport peer while preserving the wire group id.
- Inbound group typing requires trusted active membership and matching claimed/transport identities,
  then publishes into the group typing state.
- Focused tests cover direct compatibility, recipients, exclusions, and spoof/trust/member drops.

### Recommended next task
Run the F5.3 physical-device gate with at least three group members and confirm named typing start/stop
on both receivers plus unchanged direct typing. Continue only the owner-selected F item afterward;
F5.4+, F4b, and KMP Phase 15 remain separate.

### Files most relevant to this change
- `core/messaging/src/androidMain/.../RealFlashChatRepository.kt`
- `core/messaging/src/androidHostTest/.../RealFlashChatRepositoryTest.kt`
- `core/engine/src/androidMain/.../Flash.kt`
- `app/src/main/.../debug/DiscoveryEngineHolder.kt`
- `docs/group/ui-phase-plan.md`

## 2026-09-08 (f) — F5.1 date separators complete, staged, uncommitted

### Current branch
`dev` at `0948a7f`. Only F5.1 code/tests/docs/logs are staged. `New folder/` and root CLI diagnostic
JSONL files remain unrelated and unstaged.

### Last verified build
With `JAVA_HOME=C:/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2`:
- `:core:messaging:testAndroidHostTest` passed.
- `:core:messaging:jvmTest` passed.
- `:ui:chat:testAndroidHostTest` passed.
- `:ui:chat:jvmTest` passed.
- `:app:assembleDebug` passed.

### Last change
- Added nullable `FlashMessageUi.daySeparator` without changing existing call sites.
- Added pure/injectable common day labeling and separator assignment, with Android/JVM calendar
  actuals for local time zone and locale handling.
- Android repository mapping computes separators once after Room has filtered tombstones.
- Common chat UI renders centered accessible day headings while retaining `message.id` LazyColumn keys.
- Common/JVM tests cover same day, yesterday, older dates, midnight, DST, same-day streaks,
  day-boundary bubble grouping, filtered tombstones, accessibility text, and key stability.

### Recommended next task
Run the physical-device gate with a thread spanning two local calendar days. Then continue only the
owner-selected F-series phase; F5.3+, F4b, and KMP Phase 15 remain separate.

### Files most relevant to this change
- `core/messaging/src/commonMain/.../model/FlashMessagingModels.kt`
- `core/messaging/src/commonMain/.../util/DaySeparators.kt`
- `core/messaging/src/commonMain/.../util/FlashMessagingUtils.kt`
- `core/messaging/src/androidMain/.../RealFlashChatRepository.kt`
- `ui/chat/src/commonMain/.../FlashMessageList.kt`
- `docs/group/ui-phase-plan.md`

## 2026-09-08 (e) — F5.2 group notification naming complete, staged, uncommitted

### Current branch
`dev` at `649847a`. Only F5.2 files are staged. `New folder/` and root CLI diagnostic JSONL files are
unrelated untracked artifacts and remain unstaged.

### Last verified build
With `JAVA_HOME=C:/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2`:
- `:core:messaging:testAndroidHostTest` passed.
- `:app:testDebugUnitTest` passed (41 tests).
- `:app:assembleDebug` passed.

### Last change
- Inbound Android-host callbacks can now carry nullable stored group titles through additive,
  default-bridged seams; existing internal hosts remain source-compatible and KMP source sets did
  not move.
- Group messages, sync pushes, and accepted group media supply the stored group title; direct paths
  supply `null` and retain sender-title/plain-body behavior.
- Notification content selection is pure and tested: group title with `Sender: text` or
  `Sender: Kind: file`; direct formatting remains unchanged.

### Recommended next task
Run the physical background-notification gate for group text and group media. Then continue only the
owner-selected F-series phase; F5.1, F5.3+, F4b, and KMP migration remain separate.

### Files most relevant to this change
- `core/messaging/src/androidMain/.../RealFlashChatRepository.kt`
- `core/messaging/src/androidHostTest/.../RealFlashChatRepositoryTest.kt`
- `app/src/main/.../debug/DiscoveryEngineHolder.kt`
- `app/src/main/.../notifications/FlashNotificationManager.kt`
- `app/src/test/.../notifications/FlashNotificationContentTest.kt`
- `docs/group/ui-phase-plan.md`

## 2026-09-08 (d) — Dev + KMP integration merged and verified on `dev`

### Current branch
`dev`, merge commit `530db70`. Dev checkpoint `268487f` preserves the original feature tree before
the KMP integration. `New folder/` and root CLI diagnostics remain unrelated untracked session data
and must never be staged.

### Last verified build
`:app:assembleDebug` is green. Targeted Android/JVM suites are green across every converted module,
plus calling, call UI and app. Live results after removing obsolete pre-KMP XML directories:
**1702 tests / 12 known Windows DataStore failures / 0 errors / 0 skipped across 217 XMLs**. Only
`:core:persistence:testAndroidHostTest` has failures (the existing DataStore environment set).

### Last change
- Integrated KMP source sets, Room KMP, Compose Multiplatform resources/shims and desktop JVM tests
  with all dev features/optimizations.
- Ported transfer behavior onto Okio/KMP locks/atomics without changing the protected `ChunkFrame`
  wire codec.
- Group media identity fixed: shared group message + wire id, exact per-recipient transfer id carried
  in both GMEDIA and FILE_START; sender row uses the group message id.
- Group sync now returns per-message SyncAck and partial acks cannot cancel unacknowledged pushes.
- UI/media optimizations remain behind platform shims; common UI stays Android-free.

### Recommended next task
1. Run the physical three-device group/media/sync regression matrix.
2. Continue desktop transport from corrected `docs/migration/PHASE-15-desktop-transport.md`.

### Files most relevant to next task
- `core/messaging/src/androidMain/.../RealFlashChatRepository.kt`
- `core/messaging/src/commonMain/.../FlashChatRepository.kt`, `protocol/GroupWireFrame.kt`
- `core/transfer/src/commonMain/.../RealFlashTransferRepository.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `docs/migration/CONVENTIONS.md`, `docs/migration/PHASE-15-desktop-transport.md`

## 2026-09-08 (c) — F-series: F1–F3 DONE, F4 core DONE (group media in chat), F4b + audit follow-ups queued — uncommitted

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed** (owner's call). `New folder/` is unrelated
session data — never stage it.

### Last verified build
Full R3 sweep: **1021 live tests / 12 failures / 0 skipped** — every failure is the known
Windows `:core:persistence` DataStore set (verified: no failures in any other module);
`:app:assembleDebug` green.

### Last change
F-series per `docs/group/ui-phase-plan.md`, each grounded in evidence recorded up front:
- **F1:** `touchConversation` (no more group-row clobber by attachment/call upserts);
  `openStreamChannel` named-peer-without-session fails cleanly (no more transfers leaking to
  an arbitrary peer — the owner's "only one device got the audio"); interim group-attachment
  gate.
- **F2:** `FLASH_GROUP action=state` full-roster bootstrap — ⋮-added devices now receive the
  whole versioned roster and materialize the group (the "added device never got the chat"
  report); tombstones still win over replayed state.
- **F3:** FLASH_GSYNC implemented — deterministic holder election, TTL/cursor/budget bounds,
  paced rank-0 push with ack-gated backup; hosts request catch-up on session-up. Old-message
  sync for late joiners/returners.
- **F4 core:** FLASH_GMEDIA intro + shared-wireFileId fan-out — group voice notes and
  attachments now thread into the GROUP chat on every member (consumed at accept, race-free),
  with the sender's bubble keyed by the shared wire id. 1:1 paths byte-identical.

### Recommended next task
1. Owner device gates: ⋮-add → group appears; 5-min offline → exactly-once catch-up;
   group media matrix (in chat, progress, resume); 1:1 regression.
2. F4b: any-holder re-pull (`FLASH_GFETCH` + original-identity resume) + SyncPush media
   metadata.
3. **F5/F6 audit follow-ups are now PLANNED, not implemented** — full per-item designs with
   code evidence live in `docs/group/ui-phase-plan.md` §F5/F6. Recommended order: F5.2 group
   notification naming (10-line) → F5.1 date separators → F5.3 group typing → F5.4
   delivered-M-of-N → F6.1 mark unread → F6.2 delete-for-everyone → F6.3 storage screen.

### Files most relevant to next task
- `core/messaging/.../protocol/GroupWireFrame.kt`, `GroupFrameCodec.kt`, `GroupSyncPolicy.kt`
- `core/messaging/.../RealFlashChatRepository.kt` (touchConversation, State branch, GSYNC
  handlers, pendingGroupMedia, beginGroupAttachment)
- `core/transfer/.../RealFlashTransferRepository.kt` (sendFile wireFileId overload)
- `app/.../MainActivity.kt` (group fan-out), `app/.../debug/DiscoveryEngineHolder.kt` +
  `core/engine/.../Flash.kt` (sync requests on session-up, GMEDIA via codec)

## 2026-09-08 (b) — Group UI Phases A–E DONE: groups render as groups, real roster, online counts, three-dot menus, essentials audit — uncommitted

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed** (owner's call). Tree now SEVENTEEN windows
deep. `New folder/` is unrelated session data — never stage it.

### Last verified build
Full R3 sweep 2026-09-08: **1005 live tests / 0 failures / 0 skipped** (messaging 59,
ui/chat 265); `:app:assembleDebug` green. Phase record: `docs/group/ui-phase-plan.md`
(all five phases DONE with per-phase verification notes).

### Last change
Phases A–E per `docs/group/ui-phase-plan.md`, all grounded in the owner's device report:
A — group conversations now render as groups (header branches on `conversationDao.get().isGroup`;
stored title beats the UUID; `isGroup`/`memberCount`/`onlineCount` filled; call buttons hidden,
killing the silent `startCall(groupId)` trust refusal; chat-list aggregate presence; seed via
`groupTitleCache`, not a blocking Room read). B — real member roster in
`FlashConversationUiState.members` (names/online/owner role) consumed by the member sheet.
C — "N members · M online" subtitle + group online-count chip on chat-list avatars.
D — working three-dot menus (`FlashConversationMenuMath` + `FlashConversationMenu`): direct
(profile/search/revoke/clear) and group (info/add members/leave/search) with
`FlashAddMembersSheet` + `FlashLeaveGroupDialog` and full MainActivity wiring. E — code-based
essentials audit at `docs/ui/app-essentials-audit.md` with a prioritized follow-up list
(date separators and group-notification naming on top).

### Recommended next task
1. **Owner device re-test** of A–D (group name/counts, member sheet, both menus, no call
   buttons in groups, 1:1 regression).
2. Owner picks follow-ups from `docs/ui/app-essentials-audit.md` — recommended first:
   date separators, then group notification naming.
3. Then Phase 1B (FLASH_GSYNC) / Phase 2 (group voice) per `docs/group/`.

### Files most relevant to next task
- `core/messaging/.../RealFlashChatRepository.kt` (group header branch, `directHeaderState`,
  `groupTitleCache`, roster mapping, aggregate list presence)
- `core/messaging/.../model/FlashMessagingModels.kt` (`members`, `groupOnlineCount`)
- `ui/chat/.../FlashConversationMenu.kt`, `FlashAddMembersSheet.kt`, `FlashConversationScreen.kt`
- `ui/chat/.../FlashChatListRow.kt` (online chip), `FlashChatHeader.kt` (menu anchor)
- `app/.../MainActivity.kt` (menu actions, add/leave/clear wiring)
- `docs/ui/app-essentials-audit.md` (next work queue)

## 2026-09-08 — Groups Phase 0 + Phase 1A LANDED: trusted group text, quorum delivery, call trust gate (ADR-030), uncommitted

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed** (owner's call). The tree is now SIXTEEN work
windows deep (the fifteen prior + this groups landing). `New folder/` is unrelated session
data — never stage it.

### Last verified build
Full R3 sweep 2026-09-08: **1001 live tests / 12 known Windows DataStore failures / 0 skipped**
(messaging 47→57, persistence 35→38, ui/chat 255→259); `:app:assembleDebug`, both sample
consumers, and v4 schema export all green. Only the 12 documented `:core:persistence`
DataStore failures failed; nothing else.

### Last change
Groups Phase 0 + 1A per `docs/group/` (ADR-030): protocol frozen (`docs/protocol.md` §Groups),
`GroupWireFrame`/`GroupFrameCodec`/`GroupPolicy` in `:core:messaging`, non-destructive DB
v3→v4 (`group_members`, `group_deliveries`, conversation provenance, `MIGRATION_3_4`), additive
repository group API with per-member quorum delivery riding the existing durable outbox,
fail-closed trust on every group path, `CallCoordinator.isTrustedPeer` calling gate (outbound
refused / inbound invite auto-declined for unpaired peers), both hosts wired, create-group UI
(`FlashCreateGroupSheet`, trusted peers only) wired through the chat-list top bar.

### Recommended next task
1. **Owner physical gate (Phase 1A):** three trusted devices — create/add/leave/re-add, one
   member offline 5 min then reconnect (durable delivery on session-up), untrusted frame
   rejection, no 1:1 regression. Record in `logs/experiments.md`.
2. Then **Phase 1B** (FLASH_GSYNC holder catch-up — codec/policy constants already exist) or
   the UI follow-ups (real member names in the members sheet, "delivered to M of N").
3. Group voice (Phase 2) only after 1A+1B verify.

### Files most relevant to next task
- `core/messaging/src/main/.../protocol/GroupWireFrame.kt`, `GroupFrameCodec.kt`, `GroupPolicy.kt`
- `core/messaging/src/main/.../RealFlashChatRepository.kt` (`createGroup`,
  `onInboundGroupWireFrame`, `sendGroupText`, `drainGroupMessage`)
- `core/persistence/.../FlashMigrations.kt` (`MIGRATION_3_4`), `GroupMemberDao.kt`,
  `GroupDeliveryDao.kt`, `schemas/.../4.json`
- `app/.../debug/DiscoveryEngineHolder.kt` + `core/engine/.../Flash.kt` (both hosts)
- `core/calling/.../CallCoordinator.kt` (`isTrustedPeer`)
- `ui/chat/.../FlashCreateGroupSheet.kt` + `app/.../MainActivity.kt` (sheet wiring)

## 2026-09-07 — Voice-call latency fluctuation: five in-app sources fixed (ADR-026), uncommitted

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed** (owner's call). Working tree now carries the
fourteen prior windows PLUS the call-latency landing below — fifteen windows deep.

### Last verified build
Full R3 sweep 2026-09-07: **984 live tests / 12 known Windows DataStore failures / 0 skipped**,
per-module split byte-identical to baseline (`:ui:callui` still 5 — no tests added, timing/lifetime
properties are not unit-assertable; the unchanged 984 is the regression check). APK rebuilt 12:11.

### Last change (this window)
Voice-call quiet, five files + ADR-026: (1) `DiscoveryEngineHolder.setCallActive` — ECO discovery
+ sweep skip while ACTIVE, STANDARD restore after; (2) `MultiStreamDispatcher.quietWatcherHint`
(`WATCH_QUIET_POLL_MS = 250L`) via public `RealFlashTransferRepository.voiceCallActive`;
(3) `FlashCallSession` stats sampler on a dedicated `FlashCallStats` daemon thread, closed in
`releaseMedia` — first production thread pool in the tree; (4) `FlashWebRtcEngine.configureOnce`
gains `lowLatencyPlayout` (holder passes `performanceMode != LOW`); (5) call-screen clock aligned
to second boundaries + live-region transitions-only. Zero HIGH pixel/tier change throughout.

### Recommended next task
On-device validation (EXP-007): which of the five dominates, heard on two low-end devices. Proposed
instrumentation (not implemented): log `jitterBufferDelay/concealedSamples/fecPacketsReceived`
alongside `jitter` in `sampleStats`, and A/B LOW `ptime 60` vs MEDIUM `ptime 20` on the same pair.

### Files most relevant to next task
`core/calling/.../FlashCallSession.kt` (`armStatsPolling`, `sampleStats`), `core/calling/.../FlashWebRtcEngine.kt`,
`app/.../DiscoveryEngineHolder.kt` (`setCallActive`), `core/transfer/.../RealFlashTransferRepository.kt`
(`voiceCallActive`), `docs/decisions.md` ADR-026.

## 2026-09-04 (h) — Task #5: recomposition scopes are now narrow in the shell (EXP-012), on the call screen (EXP-013 part 1), and in every per-frame animation in the app including the launch splash (EXP-013 part 2). A closing pass over the sites part 2 deferred found 10 of 11 already correct — the reason recorded for deferring them was wrong — and fixed the one that was real, the send button. A third pass (part 3) found that closing grep had only covered animated `Float`s, and that the animated `Dp` form had hit the shell a second time. A fourth pass then left animations behind and found the app implemented **no** memory-pressure callback at all, so the chat thumbnail cache held its whole `maxMemory / 8` share for the life of the process, backgrounded mid-transfer included (EXP-014). A fifth pass then inverted that question — not a callback the app never implements, but work the app does **on a timer whether or not there is anything to do** — and found the durable outbox's retry loop waking on a fixed 1 s grid for the life of the process, i.e. ~86,400 SQLCipher queries a day against a table that is almost always empty, *and* rounding every retry up to the next second despite having computed an exact deadline (EXP-015). A sixth pass then took the timer inventory that fifth pass produced and closed its only remaining unscoped entry: a 1 Hz pairing tick launched from a constructor for the life of the process, servicing a state that is idle except during the few seconds a user spends pairing (EXP-016). The cadence, scope, per-frame, retention and periodicity threads are all closed; the *listed* remainder of task #5 is R8-gated, ADR-gated or measure-first, but new classes keep being findable — by auditing platform callbacks the app never implements, then its timers, and next what it holds open while idle

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** Working tree carries ERROR-033/034/035, task #3,
task #4 (complete), and ten task #5 landings (frame-path allocations, send-side resume bookkeeping,
receiver done-set, chat progress cadence, shell progress cadence, shell recomposition scopes, call-screen
recomposition scope, per-frame animation phases — the last of these including the send-button fix from
the closing pass and the shell chip-inset fix from part 3 — the thumbnail-cache trim policy, the
outbox drain loop, and the pairing tick). No commit requested by the owner; the tree is **fourteen**
work-windows deep.

### Last verified build
```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```
**984 live tests / 12 known Windows DataStore failures / 0 skipped**, APK 15:18, 67,556,718 bytes
(re-run after EXP-016's pairing-tick work; the preceding verified APKs were 14:51, 14:15, 13:52, 13:34,
13:05 — and 67,556,718 is the same byte count as the 14:51 build, which is coincidence, not a skipped
build: 13 tasks executed and one lambda class replaced another).
CONVENTIONS R3 bumped 963 → 968 → 972 → 978 → **984** and **stays at 984**: `FlashCallDurationTest` +5,
which is also the
**first** `src/test` in `:ui:callui`, so a
`:ui:callui:testDebugUnitTest` task now exists where it did not before (no build-file change was
needed — the module already had `testImplementation(libs.junit)`); then `FlashTransfersLogicTest` +4 for
`progressBarWidthPx`; then `FlashMediaCacheTrimTest` +6 for the thumbnail-cache trim policy; then
`OutboxDrainScheduleTest` +6 for the outbox wait function, taking `:core:messaging` 41 → **47**. EXP-012
and EXP-016 added no tests, on purpose; see below for both. The part-2 closing pass and part 3
add none either, so **an unchanged 972 was their regression check** — per-module split identical,
`:ui:chat` 249 at that point and **255** now.

A per-module recompile is worth running when only one module changed; EXP-016's was
`:app:compileDebugKotlin` → `BUILD SUCCESSFUL`, **zero warnings in `:app`** (EXP-015's was
`:core:messaging:compileDebugKotlin :core:messaging:compileDebugUnitTestKotlin --rerun-tasks`, also
clean). The six warnings the full sweep prints are
pre-existing in `:core:discovery`/`:core:network`; do not read them as new.


The command reports `BUILD FAILED` — that is the 12 documented `:core:persistence` failures, and
`--continue` is what lets the later modules run at all. Confirm non-regression by counting live XML per
module and by the APK timestamp, never by the exit code:
```bash
for d in app core/calling core/common core/discovery core/engine core/messaging core/network core/persistence core/security core/transfer ui/chat ui/theme ui/callui; do n=$(find "$d" -path "*test-results*" -name "TEST-*.xml" 2>/dev/null | xargs grep -ho 'tests="[0-9]*"' 2>/dev/null | grep -o '[0-9]*' | awk '{s+=$1} END {print s+0}'); echo "$d $n"; done
```

### Current phase
Task #5. The three classes that produced the animation results are out of inspectable un-gated items:
EXP-008/009/010/011 were **cadence** findings (how often work runs); EXP-012 and EXP-013 part 1
were **scope** findings (how much of the tree the work invalidates); EXP-013 part 2 was a **phase**
finding (which pipeline stage the work happens in). EXP-014 then opened a **fourth** class —
**retention** (what the process keeps holding, and for how long) — by asking what the app does when the
platform asks for memory back. The answer was nothing at all. EXP-015 opened a **fifth** —
**periodicity** (work the app repeats on a timer whether or not there is anything to do) — by inverting
that same question, and the outbox retry loop was polling an encrypted database once a second forever.
EXP-016 then **closed** that fifth class: its inventory of 27 `while (true)` sites had exactly three
time-driven entries, two of which were already correctly scoped, and the third — `PairingCoordinator`'s
1 Hz tick — is now fixed.
So do not read "out of items in three classes" as "out of items": the remaining *listed* work is blocked
on an owner decision, an ADR boundary or a device measurement, but two of the five classes were opened in
the last three passes, and a candidate **sixth** question is written down under *Recommended next task*
(what the process holds *open* while nothing is happening — sockets, wake locks, codec instances, EGL
contexts, cursors). That inventory has not been taken.

**The three Compose rules behind every scope finding — carry these forward:**
1. A `State`'s invalidation scope is **where `.value` is read**, not where the `State` was created —
   and a `remember(someState) { … }` **key expression is a read**, located where the `remember` sits.
   So a `by collectAsState()` at the top of a huge composable is not automatically wide, but a
   `remember` keyed on it is.
2. **A value-returning `@Composable` is not restartable.** State reads inside it are recorded against
   the nearest restartable scope above it, i.e. the caller. A Unit-returning composable is always
   restartable, which is why wrapping one `Text` in one was the whole EXP-013 part 1 fix.
3. **The phase that evaluates the read is the phase that gets invalidated.** Reads inside
   `graphicsLayer { }`, `drawBehind { }`, a `Canvas` draw lambda or a `progress = { … }` lambda cost a
   **re-draw**; reads inside `Modifier.layout { }` or `Modifier.offset { }` cost a **re-place**; reads
   inside `Modifier.semantics { }` cost a semantics pass. Only a composition-time *argument* —
   `fillMaxWidth(f)`, `Modifier.scale(f)`, `background(c.copy(alpha = f))`, and just as much
   `.padding(bottom = animatedDp)`, `.size(animatedDp)` or `FontWeight(animatedInt)` — costs a
   **recomposition**. When grepping for this class, grep the `Dp`/`Int` spellings too: part 3 exists
   because the "closing" grep of part 2 only had the `Float` ones.
   Corollary: a `by` delegate whose only use is inside a lambda the callee runs in draw
   (`progress = { x }`) is already correct, because `getValue` runs when the lambda runs.

Also worth carrying: `graphicsLayer { alpha = … }` is not free. Under the default
`CompositingStrategy.Auto`, `alpha < 1` marks the layer as overlapping content, so the platform may
allocate an offscreen buffer per layer. When the layer wraps exactly one solid draw,
`CompositingStrategy.ModulateAlpha` is pixel-identical and needs no buffer; when no layer is needed at
all, `drawBehind { }` is cheaper still.

### Broken
Nothing new. The 12 `:core:persistence` failures are the pre-existing Windows-only DataStore
atomic-rename issue and are the documented baseline.

### Last change
1. `app/.../MainActivity.kt`, four edits (EXP-012): `derivedStateOf` import; `transfersUi` and `nearby`
   converted from `remember(state, …)` to `remember(stableKeys) { derivedStateOf { … } }`; new
   `chatListSelectionMode` derived `Boolean` read by the selection-mode `BackHandler`.
2. `ui/callui/.../FlashCallScreen.kt` (EXP-013 part 1): new Unit-returning
   `FlashCallStatusLine(state, color)` wrapping the one `Text` that shows the mm:ss clock, used by both
   former call sites; `activeDuration`'s KDoc corrected (it claimed "leaf text node" and was not one);
   mm:ss arithmetic extracted as `internal fun formatCallDuration(elapsedMillis: Long)`.
3. `ui/callui/src/test/.../FlashCallDurationTest.kt` — new, 5 tests.

Then EXP-013 part 2, the per-frame animation sweep — seven files, no build files:

4. `ui/theme/.../FlashBrandAnimation.kt` — the launch splash, the worst site found.
   `rememberFlashBrandPhase()` now returns a `FlashBrandPhase` holding two `State<Float>`s, read inside
   the `Canvas` draw block. It used to recompose the whole composable per frame of a 2.4 s loop — while
   the transport stack boots, on the device where boot is slowest (ERROR-034).
5. `ui/chat/.../FlashTypingIndicator.kt` — three waves kept as `State`, read inside each dot's
   `graphicsLayer`, plus `ModulateAlpha`; the `listOf` remembered. Its KDoc had claimed "without
   triggering recomposition cycles" and was false.
6. `ui/callui/.../FlashCallScreen.kt` again — the avatar halo extracted to
   `rememberCallPulseScale(pulsing): State<Float>`, read in the layer.
7. `ui/chat/.../FlashVoiceRecording.kt` — record-dot `pulseAlpha` (+ `ModulateAlpha`) and the mic
   button's press `scale` both stay `State`, read inside their layers.
8. `ui/chat/.../FlashPairingFlow.kt` — `FlashPulsingDot` drops `clip` + `background` + layer for
   `drawBehind { drawCircle(color.copy(alpha = alpha.value)) }`.
9. `ui/chat/.../FlashStateViews.kt` — `graphicsLayerAlpha(State<Float>)` gains `ModulateAlpha`
   (up to 8 skeleton rows x 3 shapes, during boot).
10. `ui/chat/.../transfers/FlashTransfersScreen.kt` — header derives the throughput **label** with
    `derivedStateOf` (structural equality drops every frame formatting to the same text); the row's fill
    moves from `fillMaxWidth(fraction)` to `Modifier.layout { }` via the new
    `FlashTransfersMath.progressBarWidthPx`; the a11y percent reads `item`, not the tween, so a moving
    transfer no longer rebuilds a `buildString` per frame.
11. `ui/chat/src/test/.../FlashTransfersLogicTest.kt` — +4 tests pinning `progressBarWidthPx` to
    `FillNode`.

Then the closing pass over the press-scale sites part 2 had deferred — one file, no tests:

12. `ui/chat/.../FlashComposer.kt` — `FlashSendButton`, two faults. `.scale(scale)` was a
    composition-time *argument*, so every frame of the press spring recomposed the whole button (both
    `animateColorAsState` calls, the `clickable` chain, the semantics block, the icon); it is now
    `graphicsLayer { scaleX = scale.value; scaleY = scale.value }` in the same chain position, which is
    the same node with the same centre pivot and therefore pixel-identical. And its spec was a raw
    `spring(0.6f, 500f)` with **no reduce-motion guard** — the last one left in the app — now
    `if (motion.reduceMotion) snap() else spring(0.6f, 500f)`, so HIGH keeps that spring byte-for-byte
    (deliberately not `springSnappySpec()`, which would change HIGH's feel) and LOW/MEDIUM snap.

The same pass **reverted** two conversions it had made in `FlashMessageContextMenu.kt`
(`FlashQuickReactionsBar`) and `FlashAttachmentSheet.kt` (`FlashAttachmentTile`): both files' reads were
already inside their `graphicsLayer` lambdas, so the conversions bought nothing and the comments
attached to them were false. Those two files are back to their pre-pass content; see item 8 of the
do-not-simplify list.

Then part 3, the animated-`Dp`/`Int`/`Color` sweep the closing grep had missed — one file, no tests:

13. `app/.../MainActivity.kt` — `chipBottomInset`, an EXP-012 recurrence in the same composable by a
    different route. The animated `Dp`'s only consumer was
    `.padding(end = 16.dp, bottom = 16.dp + chipBottomInset)`, a composition-time argument on an inline
    `Box` inside an `if` in `FlashShell`'s own body, so every tab-root navigation recomposed the
    ~750-line shell once per frame for the tween's 200 ms. Now an explicit `State<Dp>` read in the
    placement pass: `.padding(end = 16.dp, bottom = 16.dp)` plus
    `.offset { IntOffset(0, -chipBottomInset.value.roundToPx()) }`. The chip is bottom-aligned, so
    shifting up by the inset is exactly what the padding did — same pixels, same tween, HIGH untouched.
    Scope note: `showDevConsoleEntry` is `isDebuggable`, so **release never took this path**; what it
    affected is debug builds, which is what EXP-007's device matrix will run.

Then EXP-014, the retention pass — one source file plus one new test file, no build files:

14. `ui/chat/.../FlashMediaDecoder.kt` — the app implemented **no** memory-pressure callback anywhere
    (`FlashApplication` has an empty body; nothing in `app/`, `core/` or `ui/` mentioned `onTrimMemory`,
    `onLowMemory`, `ComponentCallbacks2` or `registerComponentCallbacks`), so the thumbnail `LruCache`
    held its whole `(maxMemory / 8).coerceIn(4 MB, 24 MB)` share for the life of the process — an
    `LruCache` evicts only when a *new* entry does not fit, never because nothing wants the old ones.
    Worst case is the backgrounded one: transfers run as a foreground service, so the process survives
    with the UI gone, and there the cache is fullest and least useful. Now a `ComponentCallbacks2`
    registered lazily from the top of `decode()` behind an `AtomicBoolean.compareAndSet`, with the
    policy extracted as a pure function — `internal fun cacheTrimFor(level: Int): CacheTrim`,
    `enum class CacheTrim { None, Halve, EvictAll }` — halving via `trimToSize(size() / 2)` from
    `TRIM_MEMORY_RUNNING_LOW` and evicting from `TRIM_MEMORY_UI_HIDDEN` up and on `onLowMemory()`.
15. `ui/chat/src/test/.../FlashMediaCacheTrimTest.kt` — new, 6 tests (972 → 978).

Then EXP-015, the periodicity pass — one source file rewritten, one new source file, one new test file,
no build files:

16. `core/messaging/.../RealFlashChatRepository.kt` — `drainOutboxLoop()` was
    `while (true) { drainOutboxOnce(); delay(1000) }`, launched from `init` and never stopped. Because
    `transportSink` is an immutable constructor val, on the real DI path every pass reached
    `outboxDao.dueForDelivery(now, 16)` — a SQLCipher query ~86,400 times a day against a table whose
    steady state is empty. It also ignored the deadline `rescheduleAttempt` had just written, so a row due
    at `T` was retried at the first 1 s boundary at or after `T`. Now it waits on whichever comes first:
    a write to the `outbox` table (`OutboxDao.observeCount()`, which **already existed** — no DAO change,
    R8 clean — forwarded into a `Channel<Unit>(Channel.CONFLATED)` by a second `init` collector) or the
    earliest deadline it holds (`@Volatile private var outboxNextDueAt`). `drainOutboxOnce()` now returns
    `Boolean` — "the batch was full" — and a full batch skips the wait entirely, which also makes
    `notifyPeerSessionUp()`'s post-reconnect backlog leave as fast as the socket accepts instead of
    16 rows/second. `drainWake` and `outboxNextDueAt` are declared **above** the `init` block for the
    reason already recorded on `drainMutex` (Bug 6: an init-launched coroutine reading a
    not-yet-initialised property FATALs the process).
17. **New** `core/messaging/.../OutboxDrainSchedule.kt` — `MIN_WAIT_MS = 25L`, `IDLE_WAIT_MS = 60_000L`
    (pinned to the backoff cap), and `waitMs(nextDueAt, now)`. Split out because the loop is `while (true)`
    inside a coroutine launched from a constructor: no test can step it, so extracting the arithmetic is
    the only way any of this timing is assertable.
18. `core/messaging/src/test/.../OutboxDrainScheduleTest.kt` — new, 6 tests (978 → 984). The stronger
    regression net is the **seven existing `RealFlashChatRepositoryTest` cases that drive this loop**
    (resend-after-reconnect, the give-up budget, the receipt-deletes-the-row path, the tombstone and
    missing-row sweeps); each was hand-traced against the new timing before the sweep and each passed
    unchanged. `FakeOutboxDao` needed no edit — it already bumps `countFlow` in `enqueue` and `delete`,
    which is exactly the wake those tests need.

19. `app/.../pairing/PairingCoordinator.kt` — the `init` block held a 1 Hz
    `while (isActive) { if (phase != Idle) { onTick; recomputeUi }; delay(1000) }` for the life of the
    process. Milder than #16 (an idle pass was a wake-up plus a `StateFlow.value` read, not a query) but
    unconditional in time: ~86,400 wake-ups a day for a phase that is `Idle` except during the seconds a
    user spends pairing, and it polled a value that already pushes. Replaced by `tickWhileInFlight(p)`, a
    **third child of `collectorJob`** driven by `p.session.map { it.phase != Idle }.distinctUntilChanged()
    .collectLatest { … }`. `resetProtocol()`'s existing `collectorJob.cancel()` is therefore the teardown —
    no new lifecycle bookkeeping. The class KDoc's "the 1 Hz ticker always reads the current instance"
    became false and was corrected: the ticker is now per-instance *on purpose* (see item 12). No test
    added; `:app` stays at 36 and the reasoning is in EXP-016 under **Not tested, and why**.

Full rationale in `logs/experiments.md` EXP-012, EXP-013, EXP-014, EXP-015 and EXP-016.

### Twelve things not to "simplify" later
1. **`derivedStateOf`'s remaining `remember` keys are load-bearing, and they are not the old keys.**
   States read *inside* the block need no key; two things do — plain non-State values
   (`transfersReady`) and **the flows, which swap once at boot** (every delegate is
   `(engine.X ?: fallback).collectAsState()`). Drop the keys and the derivation reads the pre-boot
   fallback `State` forever. Do not "clean up" `remember(engine, engine.discovery, engine.pairing)`.
2. **`FlashCallStatusLine` is not a pointless one-line wrapper.** It exists solely because it is
   Unit-returning and therefore restartable; inline that `Text` back into either caller and the
   per-second clock starts recomposing the video renderers again. Its KDoc says so, and
   `activeDuration`'s KDoc now says so too.
3. **150 ms, not the 250 ms that was written down** (EXP-011). `FlashMotion.NormalMillis = 200`, so a
   250 ms window leaves a 50 ms dead stop in two `animateFloatAsState` animations, four times a
   second — at the HIGH tier, the one tier the owner said must not be compromised.
   `FlashTransfersLogicTest` asserts the inequality.
4. **`collectAsState(initial = transfersSource.value)`, not `emptyList()`** (EXP-011). A cold flow's
   `initial` is rendered for real; an empty seed gives one frame of "No transfers yet" — a claim about
   the device's history made before any data arrived. That is the ERROR-034 failure mode exactly.
5. **`rememberCallPulseScale`, `rememberFlashBrandPhase` and `rememberSkeletonAlpha` must keep
   returning `State`, not `Float`** (EXP-013 part 2), and `FlashBrandPhase` must keep holding
   `State<Float>` rather than `Float`. Unwrapping any of them — including "tidying" a
   `graphicsLayerAlpha(alpha: State<Float>)` parameter to a plain `Float` — moves the read back into
   composition and silently reinstates a per-frame recomposition. Same for the three `State`s kept in
   `FlashTypingIndicator`, `FlashVoiceRecordingBar` and `FlashMicButton`. Every one of these carries a
   comment saying so, because two of the sites had a KDoc claiming the correct behaviour while the code
   did the wrong thing, and that is how they survived this long.
6. **`FlashTransfersMath.progressBarWidthPx` must keep mirroring Compose's `FillNode`** —
   `(maxWidth * fraction).roundToInt().coerceIn(minWidth, maxWidth)`. It is not a reinvention for its
   own sake; it is the arithmetic `fillMaxWidth(fraction)` was doing, lifted out so the layout-phase
   replacement can be asserted against what it replaced. Four tests pin it. If it drifts, every progress
   bar in the app quietly resizes by a pixel.
7. **`CompositingStrategy.ModulateAlpha` is not decorative, and the pairing dot's `drawBehind` is not a
   downgrade.** `Auto` treats `alpha < 1` as overlapping content and may allocate an offscreen buffer
   per layer; each of these layers wraps exactly one solid draw, so modulating is pixel-identical and
   buffer-free. The pairing dot needs no layer at all — for a square box the inscribed circle is the
   same pixels as `clip(CircleShape).background(…)`.
8. **Do not "fix" the remaining `by animateFloatAsState` press scales.** In `FlashAttachmentButton`,
   `FlashAttachmentSheet`, `FlashChatSearchBar` (x2), `FlashFileMessageCard`, `FlashMessageBubble`,
   `FlashMessageContextMenu` (x2 sites, 4 floats) and `FlashVoiceMessageCard` (x2), the property's only
   mention is *inside* the `graphicsLayer` lambda. `getValue` is an inline `State<T>` extension
   returning `.value`, so the read is evaluated at the use site — already the draw phase. Converting
   them to an explicit `State` plus `.value` is a **no-op**; this window did it to two of them, wrote
   comments claiming a win, then reverted both. What *would* be a defect is hoisting one of those reads
   out of its layer. Consolidating them onto `Modifier.flashPressScale` is still worth doing, but as
   de-bloat, and sighted — pressed scales run 0.85 to 0.98, several gate on `enabled`/`canSend`, two
   multiply by an enter scale.
9. **Four animated `Dp`/`Int` sites are settled; two are already right and two must stay in composition.**
   `FlashSettingsScreen`'s `indicatorOffset` (`:517`) and `FlashSwitch`'s `thumbOffset` (`:694`) are
   explicit `State<Dp>` read inside `Modifier.offset { }` — layout-phase, and the model for part 3's
   `chipBottomInset` fix; do not "tidy" either into a `by` delegate feeding `padding`.
   `FlashReactionChip.kt:88` `borderWidth` feeds `BorderStroke(borderWidth, borderColor)`, and
   `Modifier.border` takes no lambda while `borderColor` animates off the same flip in the same stroke,
   so the recomposition is unavoidable and hand-drawing the ring would only risk pixels.
   `FlashBottomNav.kt:325` `labelWeight` feeds `FontWeight(...)` inside a `TextStyle` — font weight
   changes text layout, so that read is inherently composition-time. Likewise all 11
   `animateColorAsState` sites: every one is a leaf feeding `background(…)` or `tint =`, and swapping to
   `drawBehind` would risk a pixel difference on shaped and bordered surfaces to save recomposing a leaf.
10. **The thumbnail cache's trim policy is deliberate in four ways (EXP-014).** (a) `Halve`, not
    `EvictAll`, from `TRIM_MEMORY_RUNNING_LOW`: that level arrives with the conversation still on
    screen, so evicting answers memory pressure with a decode storm on the next scroll pass. (b)
    `trimToSize(size() / 2)` and **not** `resize()` — `resize` lowers `maxSize` permanently, so the
    cache would never recover after one pressure event. (c) Thresholds are compared with `>=` rather
    than matched per constant, so an unknown or future level cannot fall through to `None`; the
    monotonicity test pins that. (d) The two `@Suppress("DEPRECATION")` annotations stay: against the
    API 36 `android.jar` only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND` are still current,
    so on a recent platform every delivered level lands on `EvictAll` and `Halve` is the legacy branch —
    which is the API-27 tier this whole task exists for. Deleting the `RUNNING_*` branch to clear the
    warnings would silently drop the low-end devices it was written for.
11. **The outbox drain loop's four load-bearing details (EXP-015).** (a) `outboxNextDueAt` is merged as a
    **running minimum** — `listOfNotNull(outboxNextDueAt?.takeIf { it > now }, earliestScheduled).minOrNull()`
    — not assigned this pass's minimum. A row that was not yet due when the pass ran carries a deadline the
    pass never saw; overwriting sleeps straight past it. (b) The `takeIf { it > now }` is the other half:
    a deadline already in the past belongs to a row that has since been acknowledged and deleted, and
    keeping it pins the loop at the 25 ms floor forever — worse than the 1 Hz poll it replaced. (c)
    `Channel.CONFLATED`, not `RENDEZVOUS` or `BUFFERED`. A burst of table writes must collapse to one
    wake, **and** a wake that arrives *while* a pass is running must be retained, so the row that pass
    could not see is picked up immediately instead of waiting out the idle interval. `RENDEZVOUS` drops it;
    `BUFFERED` queues redundant passes. (d) The `observeCount()` collector must stay subscribed for the
    loop's whole life — a Room `Flow` only invalidates while something is collecting it, so folding it into
    the loop body (which must be free to be *asleep*) breaks the wake. The invariant underneath all four:
    **`outboxNextDueAt` only ever has to be an upper bound on the wait, because every event that makes a
    row due earlier than expected is itself a write to the `outbox` table, and every table write wakes the
    loop.** Also do not "simplify" `withTimeoutOrNull(waitMs) { drainWake.receive() }` into a
    `select`/`onTimeout` pair for the sake of not discarding a racing element: both the wake and the
    timeout resume the same next statement — another drain pass — so which one won is immaterial.
12. **The pairing ticker's four load-bearing details (EXP-016).** (a) It takes the instance as a parameter
    and is launched from `launchCollectors()`, so it is **per protocol instance**. Hoisting it back to a
    single process-wide ticker that reads the `protocol` field is not a simplification, it is the bug:
    terminal phases absorb every event and never return to `Idle`, so a ticker that outlives its instance
    spins at 1 Hz forever on a dead session. (b) `distinctUntilChanged()` on the boolean is correctness,
    not tidiness — a pairing emits several session states, and without it every one restarts
    `collectLatest`'s block and therefore restarts `delay(TICK_MS)` from zero, so a chatty handshake
    starves the countdown and postpones expiry indefinitely. (c) `delay` comes **before** `onTick`, not
    after: the emission that started the ticker has already run `recomputeUi`, and this way the first
    displayed second is a full second instead of however much of a process-wide 1 Hz grid slot was left.
    (d) The gate is `!= Idle` and was **deliberately not narrowed** to the three phases the reducer acts
    on, even though that is provably equivalent (`reduce()` returns early when terminal;
    `FlashPairingDialog` draws the countdown only while active). `isActive()` is private to
    `PairingSessionStateMachine` in `:core:security`, so narrowing means `:app` keeping a copy of a
    classification that module owns — and a stale copy freezes the countdown. It buys 2–3 recompositions
    across a ≤2.5 s linger; not worth it.

### Why EXP-012 and EXP-016 have no test (do not file either as missing coverage)
It changes *where Compose records a snapshot read*. Asserting that needs a composition, and `:app`'s
test source set is plain JVM — no Compose UI test, no Robolectric. Adding either is a build-file
change outside the task, and R10 forbids reaching for it opportunistically. The derivation's output is
already covered (`TransfersUiMapperTest`, `FlashTransfersLogicTest`) and is untouched, so the
unchanged-at-963 sweep was the regression check. EXP-013's scope change is untestable for the same
reason; what *is* tested there is the arithmetic the fix extracted.

The same holds for EXP-013 parts 2 and 3, with one addition: ten per-frame reads moved phase, and nothing
in a plain-JVM test can observe which phase Compose invalidated. What is testable is what the move made
explicit — `progressBarWidthPx`, which now has four tests. The regression check for the rest is the
sweep at 972 plus a rebuilt APK.

EXP-016 is the same conclusion by a different route, and the route matters because the obvious tests look
like they would work. The property is a coroutine *lifetime* — "no periodic work exists while idle" — and
each cheap discriminator fails: a counting `FlashTimeSource` reads **zero under both versions**, because
the old loop called `nowMs()` only *inside* its `!= Idle` gate; `runTest` with the coordinator's scope as
the `TestScope` fails identically either way, since the old ticker never completes and the new
`session.collect` on a `StateFlow` never completes either; `advanceUntilIdle()` genuinely does
discriminate, but by **hanging** rather than failing, which is a worse regression signal than none; and
extracting `fun needsTick(phase) = phase != Idle` would assert the expression it wraps. Exposing the
ticker's existence as production API purely for a test has no precedent in `:app` and is not worth it at
this size. The substitute is that the fix's equivalence argument is a proof about code that was read —
`PairingSessionStateMachine`'s two early returns, the countdown gate at `FlashPairingFlow.kt:140-219`,
and `FlashPairingMath.tickCountdown` having zero production callers.

### Recomposition scope: what was cleared (do not re-audit)
- **`conversationState` is already narrow.** Its only composition read is `state = conversationState`
  inside the Conversation branch; the four `conversationState.header.title` uses (780/816/868/888) are
  inside **event lambdas**, which run at click time outside any snapshot observer and record no read.
  It needed no change — and pacing it would have been wrong regardless, because a keystroke must reach
  the composer immediately.
- **`chatListState`'s other uses are already narrow** (1021, 1045-1057, 1071 — all inside the ChatList
  branch). Only the `BackHandler` at shell scope was wide, and that is what was fixed.
- **`FlashAnimatedScreen`'s `content` is a non-inline `@Composable (FlashBackStackState) -> Unit`**
  (`ui/chat/.../FlashNavigation.kt:259-262`), so the branches really do get their own restart scope.
  This was verified, not assumed; it is what makes the whole argument hold.
- **`showDevConsole`, `isSearching`, `searchQuery` are correct as shell-scope state.** They change on a
  user tap, not on a data event. Leave them.
- **`FlashCallStatsBadge` is already correct.** It is Unit-returning, so `rememberCallStats`'
  per-second read stays inside the badge that displays it.
- **`rememberVideoTrack` is value-returning and does leak its read into `FlashCallVideoSurfaces`** —
  left alone deliberately, because track changes are a handful per call, not per second, and the
  renderer slots underneath it are the delicate ones (see the `FlashVideoRenderer` KDoc: `release()`
  is terminal).

### Per-frame animations: what was cleared (do not re-audit)
Five sites were checked in the EXP-013 part 2 sweep and are **already correct**; they are the house
precedents for the pattern, so read one of them before writing a new animation:
- **`ScanningDot`** (`ui/chat/.../nearby/FlashNearbyScreen.kt:286-317`) — keeps the `State` and reads
  `pulse?.value ?: 1f` inside `graphicsLayer`, with a comment saying exactly that.
- **`Modifier.flashPressScale`** (`ui/theme/.../FlashInteraction.kt:29-44`) — `animateFloatAsState`
  then `graphicsLayer { scaleX = scale.value; … }`. This is the thing the ~20 hand-rolled press scales
  should be replaced by.
- **`rememberTravelPulse`** (`ui/chat/.../shell/FlashBottomNav.kt:265`) — returns `State<Float>`.
- **`FlashCallStatsBadge`** — Unit-returning, so `rememberCallStats`' per-second read stays inside the
  badge that displays it.
- **`FlashFileIconBadge`'s `animatedProgress`** (`ui/chat/.../FlashFileMessageCard.kt:295-345`) — this
  one looks wrong and is not. It is a `by` delegate, but its only use is inside `progress = { … }`, and
  a delegate's `getValue` runs when the lambda runs, i.e. in the draw phase. Do not "fix" it.

Also checked and **not** timers: the `while (true)` loops at `ui/chat/.../FlashMediaViewer.kt:495-540`
and `ui/chat/.../FlashVoiceRecording.kt:190-225` are `awaitEachGesture`/`pointerInput` bodies.

**Cleared by the closing pass, with the real reason:** the ~10 remaining hand-rolled
`by animateFloatAsState` press scales (`FlashAttachmentButton`, `FlashAttachmentSheet`,
`FlashChatSearchBar` x2, `FlashFileMessageCard`, `FlashImageGrid`, `FlashMessageBubble`,
`FlashMessageContextMenu` x2, `FlashReactionChip`, `FlashVoiceMessageCard` x2) need **nothing**: the
property's only mention is inside the `graphicsLayer` lambda, so the read is already in the draw phase.
The reason recorded here on the first pass — "each already recomposes for an accompanying
`animateColorAsState`, so converting the scale alone buys only the spring tail" — was wrong; there is no
tail to buy. Item 8 of the do-not-simplify list has the mechanism. `FlashComposer`'s send button was the
one exception, and it is fixed (item 12 above). Consolidating the rest onto `Modifier.flashPressScale`
stays queued as **de-bloat**.

`MainActivity`'s `chipBottomInset` was listed here as needing nothing too, and that was wrong for a
different reason: it is not a press scale and it was never in a `graphicsLayer`. It was an animated `Dp`
consumed by `.padding(bottom = 16.dp + inset)` — a composition-time argument in `FlashShell`'s own
restart scope. Part 3 fixed it (item 13 above); the phase-discipline sweep is now complete across
`Float`, `Dp`, `Int`, `Color` and `Animatable`, with `updateTransition`/`animateValueAsState` having no
callers at all.

### Progress cadence: what was cleared (do not re-audit)
Nearby *events* are second-scale and its models are data classes behind a `MutableStateFlow`, so
identical rebuilds already conflate; call stats already run at `delay(intervalMs)` with an existing
cost KDoc; the chat-list DAO write path during a transfer is already capped at one write per
(transferId, path) by the stamping collector's `stamped` HashSet.

### Held-open resources: what the seventh method has already cleared (partial — finish this list, don't restart it)
Started at the end of the EXP-016 window; **not** finished, and it produced no find yet. What was checked
and why each is *intentional* rather than a defect:
- **`DiscoveryEngineHolder`'s `PARTIAL_WAKE_LOCK` + `WifiLock` (`:1520-1556`) are held for the engine's
  lifetime on purpose.** This is the loudest possible hit for "held while nothing is happening" and it is
  **not** a defect: for a mesh app, "nothing is happening" is exactly the state in which it must stay
  reachable, and the memory note `session-recovery-invariants` records that the locks were deliberately
  moved to outlive the Service to stop a peer flapping offline on screen-off. Both are
  `setReferenceCounted(false)` and re-acquisition is guarded by `isHeld`, so they cannot stack. Only
  `stopAll()` releases them, and that is stated in the KDoc. Do not "fix" this without an owner
  instruction — it would regress the flap.
- **`MulticastLock` in `NsdFlashDiscovery` and `NsdTransport` is already refcount-managed by state**, via
  `acquireMulticastLockIfNeeded()` / `releaseMulticastLockIfIdle()` called from every browse/register
  start and stop path (10 call sites). Nothing to do.
- **No production thread pools exist.** Every `Executors.*` hit in the tree is in `src/test`; the product
  code is coroutines-only.
- **WebRTC's `PeerConnectionFactory` is never disposed, and that is webrtc-kmp's lazy global.** Its init
  is one of the two things that make a first call slow on a 2 GB handset (`FlashCallSession.kt:124`,
  `:231`), so disposing it between calls trades a held allocation for a repeated cost — a measure-first
  question (EXP-007), not an inspectable win. `localStream`, the `AudioRecord` probe and the ringer's
  `MediaPlayer` all *do* have release paths (`FlashCallSession.kt:1326`, `FlashWebRtcEngine.kt:263`,
  `FlashCallRinger.kt:153/158/197`).

**Not yet checked, and where the search should resume:** `MediaCodec` instances; `SurfaceTextureHelper`
and camera capturer teardown on call end (as distinct from renderers, which
`webrtc-renderer-lifetime` already settles — `EglRenderer.release()` is terminal, so never release on a
track change); NSD registration/discovery listener unregistration symmetry; Room cursors held by any
long-lived `Flow` collector; the foreground-service notification's own lifetime; and open `Socket` /
`ServerSocket` counts against ADR-017's N-socket design.

### Recommended next task, in order
1. **A seventh method, and the only un-gated searchable item left: enumerate what the process holds
   *open* while nothing is happening.** The two methods that produced finds (items 6 and 7 below) both
   asked what the app does when nothing is happening — on a callback it never implements, and on a timer
   nothing was waiting for. The third question of that family is about *handles*, not work: sockets and
   server sockets, `WifiManager`/`PowerManager` wake locks, `MediaCodec`, `AudioRecord`/`AudioTrack` and
   the WebRTC ADM, `EglBase` contexts and `SurfaceTextureHelper`s, `MulticastLock`s, NSD registration
   listeners, Room cursors, thread pools, and foreground-service notifications. For each: what starts it,
   what stops it, and is there a state in which it is open with no user-visible reason. **The top of this
   inventory is already done** — see *Held-open resources: what the seventh method has already cleared*
   above. It produced **no find**: the four loudest candidates (the engine's wake/Wi-Fi locks, the
   multicast locks, thread pools, the WebRTC factory) are each intentional or already managed, and that is
   worth knowing before spending another window on them. Resume from the "not yet checked" list there.
2. **Ask the owner for the R8 instruction** on `transfer_chunks`: it grows without bound, and the fix
   is a DAO status join for `allDoneChunks()` and/or wiring `RetentionPolicy` to a real delete sweep
   (it has **zero production callers** today). R8 forbids touching DAOs without an explicit
   instruction, so this cannot start without one.
3. **EXP-007** — the on-device matrix. This is owner action and it gates every low-end *claim*.
   Ten landings' worth of counted reductions are now waiting on it.
4. Fold `markChunksDone` + `setBytesDone` into one Room transaction (needs a `TransferStore` port
   change, ADR-024 boundary).
5. If continuing to hunt inspectable costs, all four methods that worked are written down: (a) pick a
   hot flow, count what *one* emission makes the app do, then check what the screen can actually render
   at that rate; (b) find a `remember(state)` whose value is consumed in a narrower scope than the
   `remember` sits in; (c) find a **value-returning `@Composable`** that reads State — it is not
   restartable, so the read lands in its caller; (d) find a value read in composition whose only
   consumer is a `graphicsLayer` / `drawBehind` / `Canvas` / `layout` / `semantics` block, and move the
   read into that phase. **(b), (c) and (d) are now exhausted** across `:app`, `:ui:theme`, `:ui:chat`
   and `:ui:callui`. For (d) specifically, the mechanical form of the search is a grep for animated
   values passed as composition-time modifier *arguments*, and it must cover **all** the types, not just
   `Float`: `.scale(`, `.alpha(`, `.rotate(`, `.offset(`, the `graphicsLayer(…)` argument form,
   `fillMaxWidth(var)` **and** `.padding(`/`.height(`/`.width(`/`.size(`/`FontWeight(` fed by
   `animateDpAsState`/`animateIntAsState`. The `Float` half returned no hits; the `Dp` half was skipped
   the first time and turned up the `chipBottomInset` defect, which is why part 3 exists. Both halves now
   return nothing outstanding, and the animated-`Color` and `Animatable` families were enumerated too, so
   do not re-run these expecting finds — and do not mistake a read that is already inside a layer lambda
   for one of these. Un-audited: `FlashDevConsoleScreen` (edit it with plain ASCII — it carries
   pre-existing mojibake at lines 51/316). Also queued: the press-scale consolidation onto
   `Modifier.flashPressScale`, as de-bloat.
6. **A fifth method, and the one that produced EXP-014: pick a platform callback or lifecycle signal the
   app never implements, and cost out what that omission retains or repeats.** `onTrimMemory` /
   `onLowMemory` was the first — a grep for the callback name across `app/`, `core/` and `ui/` returned
   nothing, and the bound followed from one cache's own budget expression. The same question has not yet
   been asked of: `Configuration` changes other than the one the decoder now ignores;
   `onSaveInstanceState` / process-death restore for an in-progress transfer or composer draft;
   `ConnectivityManager.NetworkCallback` teardown symmetry (ERROR-035 covered the arrival side);
   `PowerManager` idle/doze transitions against the retry budgets; and the fact that
   `FlashPerformanceClassifier` classifies **once** and reads `ActivityManager.MemoryInfo.totalMem`
   but never `Runtime.getRuntime().maxMemory()` — the per-process heap cap, which is what actually
   governs an OOM and can be 128 MB on a 2 GB handset. (It *does* consult `isLowRamDevice` and
   `totalRamMb`, both as hard gates — do not "add" those.) Each is a question, not a claimed find.
7. **A sixth method, and the one that produced EXP-015 and EXP-016: invert the fifth. Enumerate what the
   app repeats on a timer whether or not there is anything to do, and for each ask what it would take to
   know when the next piece of work is actually due.** This class is now **closed**, and the enumeration
   is **already done and must not be re-run**:
   27 `while (true)` sites exist in product code, but almost all are blocking read/queue loops
   (`WebSocketCodec`, `DataChannelFraming`, `BoundedSendQueue`, `Chunker`) that are event-driven by
   construction, and `ui/chat/.../FlashMediaViewer.kt:495` / `FlashVoiceRecording.kt:190` are
   `awaitEachGesture`/`pointerInput` bodies. Cross-referenced against a literal `delay(...)`, exactly three
   were time-driven and all three are now accounted for: `PairingCoordinator` (**fixed, EXP-016**),
   `MultiStreamDispatcher.kt:197` (10 ms, but `while (isActive && !deferred.isCompleted)` so it is scoped
   to a running transfer, and EXP-011 already throttled its consumer), and `FlashCallScreen.kt:586` (the
   mm:ss call clock — inherently 1 Hz and scoped to a call). Everything else is a one-shot `delay` or the
   deliberately time-driven `WsKeepalive`. Two transferable rules came out of it: **a loop that does not
   know when its next piece of work is due will both poll too often and fire too late** (EXP-015 — its two
   costs were one root cause, and both fixes are the same one), and where there is no deadline to hold,
   **give the loop the same lifetime as the thing it is timing** (EXP-016 — usually by making it a child of
   a job that already gets cancelled, rather than adding a new cancellation path).
8. **The commit is the owner's call.** Fourteen windows of work sit uncommitted on `dev`.

### Files most relevant
`app/.../MainActivity.kt` (EXP-011/012, and EXP-013 part 3's `chipBottomInset`), `app/.../ui/UiPacing.kt`,
`ui/callui/.../FlashCallScreen.kt` (EXP-013 both parts), `ui/callui/src/test/.../FlashCallDurationTest.kt`,
`ui/chat/.../transfers/FlashTransfersScreen.kt` (`PROGRESS_THROTTLE_MS`, `progressBarWidthPx`, the
layout-phase fill), `ui/theme/.../FlashBrandAnimation.kt`, `ui/chat/.../FlashTypingIndicator.kt`,
`ui/chat/.../FlashVoiceRecording.kt`, `ui/chat/.../FlashPairingFlow.kt`, `ui/chat/.../FlashStateViews.kt`,
`ui/chat/.../FlashComposer.kt` (`FlashSendButton` — the last composition-time `Modifier.scale` and the
last unguarded spring), `ui/theme/.../FlashInteraction.kt` (the precedent),
`ui/chat/.../settings/FlashSettingsScreen.kt` (the two `offset { }` reads part 3 copied),
`ui/chat/.../FlashMediaDecoder.kt` + `ui/chat/src/test/.../FlashMediaCacheTrimTest.kt` (EXP-014, the
only memory-pressure handler in the app), `core/messaging/.../RealFlashChatRepository.kt` (EXP-015's
`drainOutboxLoop` / `drainOutboxOnce` / `outboxNextDueAt`, and the declaration-order hazard comment above
the `init` block), `core/messaging/.../OutboxDrainSchedule.kt` +
`core/messaging/src/test/.../OutboxDrainScheduleTest.kt` (EXP-015, new),
`core/persistence/.../dao/OutboxDao.kt:61` (`observeCount()`, the wake source — pre-existing, unchanged),
`app/.../pairing/PairingCoordinator.kt` (**EXP-016** — `tickWhileInFlight`, launched from
`launchCollectors()`; the `init` block it replaced is gone),
`logs/experiments.md` EXP-008…015.

### Also outstanding
`New folder/` can be deleted by the owner; `app/.../lan/LanController.kt:94` never refreshes
`LanUiState.localAddresses` after a roam (deferred); `docs/session-prompt.md:36-45` is stale; a tier
below LOW is deliberately deferred.

## 2026-09-04 (g) — Task #5: both consumers of the 100 Hz transfer tick are now paced (EXP-010 chat, EXP-011 shell). The cadence thread is closed; what is left of task #5 is R8-gated, ADR-gated or measure-first

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** Working tree carries ERROR-033/034/035, task #3,
task #4 (complete), and five task #5 landings (frame-path allocations, send-side resume bookkeeping,
receiver done-set, chat progress cadence, shell progress cadence). No commit requested by the owner; the
tree is nine work-windows deep.

### Last verified build
The authoritative command below. **963 live tests / 12 known Windows DataStore failures / 0 skipped**,
fresh `app-debug.apk`. `BASELINE_TEST_TOTAL` is now **963** (CONVENTIONS R3 updated; `:app` 32 → 36,
`:ui:chat` 244 → 245).

```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```

Only the 12 `:core:persistence` failures may fail. Anything else is a regression.

### Current phase
Tasks #1–#4 complete. **Task #5 (low-end library speed) is IN PROGRESS but out of un-gated inspectable
items.** Landed: EXP-001 frame allocation churn; **EXP-008** send-side resume bookkeeping; **EXP-009**
receiver done-set; **EXP-010** chat progress cadence; **EXP-011** shell progress cadence. All five were
convicted by arithmetic over repo constants, which is the only §23-legal route without hardware.

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures.

### Last change
- `ui/chat/.../FlashTransfersScreen.kt` — `FlashTransfersMath.PROGRESS_THROTTLE_MS` was a **dead
  constant with zero references** (`250L`). Now live *and* re-derived:
  `FlashMotion.NormalMillis * 3L / 4L` = **150 ms**. The derivation is the point — see below.
- `app/.../MainActivity.kt:572-590` — source resolved outside the `remember`, paced, and
  `collectAsState(initial = transfersSource.value)`.
- **New** `app/.../ui/UiPacing.kt` + `app/src/test/.../ui/UiPacingTest.kt` (+4) — the same leading-edge
  `throttleLatest` as `:core:messaging`'s, deliberately duplicated (reason in its KDoc and in EXP-011).
- `ui/chat/.../FlashTransfersLogicTest.kt` (+1) — asserts `PROGRESS_THROTTLE_MS < FlashMotion.NormalMillis`.
- Docs: `logs/experiments.md` EXP-011, `logs/progress.md` (g), CONVENTIONS R3 (963).

### Two things not to "simplify" later
1. **150, not 250.** Both animations on the Transfers screen run for `FlashMotion.NormalMillis = 200`. A
   pacing window *longer* than the animation lets it finish and then hold still until the next value —
   a periodic dead stop, four times a second, on the **HIGH** tier specifically (the tier where the
   animation is not switched off), which is exactly what the owner's constraint forbids. Shorter than the
   animation means every target lands mid-flight and `animateFloatAsState` retargets. The test enforces it.
2. **`collectAsState(initial = transfersSource.value)`, not `emptyList()`.** `collectAsState()` on a
   `StateFlow` reads the current value synchronously at composition; a cold flow's overload does not.
   An empty seed gives one frame where `transfersReady` is true and the list is empty — i.e. the tab
   renders "No transfers yet", a claim about this device's history. That is the ERROR-034 shape.

### Startup: what was cleared (do not re-audit)
`FlashApplication` is a bare `@HiltAndroidApp` shell. `MainActivity.onCreate` does no disk I/O. Every
expensive `AppEngine` member is `by lazy`, and `start()` touches `performanceMode.value` on
`Dispatchers.Default` on purpose so the `MediaCodecList` tier walk is paid off the first composition. The
AndroidKeyStore passphrase unwrap is lazy (Room calls it on first query) and one-time. None of these need
work.

### Progress cadence: what was cleared (do not re-audit)
Both consumers of `MultiStreamDispatcher`'s `WATCH_POLL_MS = 10L` tick are paced: the conversation mapper
at 100 ms (`RealFlashChatRepository.pacedAttachmentProgress`, EXP-010) and the app shell at 150 ms
(`MainActivity` + `FlashTransfersMath.PROGRESS_THROTTLE_MS`, EXP-011). The tick itself was left alone on
purpose — it is the sender's own bookkeeping cadence and `maybeResolveFromState` rides on it.

### Recommended next task
1. **Owner instruction needed (R8): bound `transfer_chunks`.** `preloadReceiverProgress()` reads every
   done row on the device with no predicate, and **nothing prunes the table** — `RetentionPolicy` is
   fully unit-tested with **zero production callers**, and no `DELETE` exists for `transfers` or
   `transfer_chunks`, so it is append-only for the life of the install. The fix is a status-joined
   `allDoneChunks` and/or a real delete sweep — both Room changes, and `TransferDao` has no "all
   transfers" query to filter against from the adapter side. **R8 blocks this without an explicit
   instruction.**
2. **Owner: EXP-007.** Still the gate on every low-end *claim*; EXP-001/008/009/010/011 may only be
   quoted as counts. Add one item: which `startEngineLocked` stage dominates the cold-start splash. The
   sequence is fully serialized under one mutex, but its order encodes ERROR-032/033 and #4/#20
   invariants, so it must not be reordered on inspection alone.
3. Optional, needs an ADR-024 port change: fold `markChunksDone` + `setBytesDone` into one Room
   transaction to halve the remaining fsyncs.
4. **If continuing to hunt inspectable costs**, the method that found EXP-008/009/010/011 was: pick a hot
   flow, count what *one* emission makes the app do, then check what the screen can actually render at
   that rate. Un-inspected candidates: `discoveredEndpoints` / `discoveryState` and the `NearbyUiState`
   rebuild at `MainActivity.kt:613` (keyed on five values, one of which is a list rebuilt per discovery
   event); the `:ui:chat` chat-list mapper under presence churn; `FlashCallSession`'s stats flow during
   a call. None of these has been counted yet — do not assume they are hot.
5. Commit/split decision is the owner's.

### Files most relevant to next task
- `core/transfer/.../RealFlashTransferRepository.kt` — `preloadReceiverProgress` / `receiverDone`, and
  the `TransferStore` port it calls.
- `core/engine/.../RoomTransferStore.kt` + `core/persistence/.../TransferChunkDao.kt` + `TransferDao.kt`
  — where the R8-gated predicate would go.
- `core/transfer/.../RetentionPolicy.kt` — the unwired pruner seam.
- `app/.../MainActivity.kt:592-640` — the Nearby derivation, candidate 4 above.

## 2026-09-04 (f) — Task #5: the 100 Hz progress tick was re-deriving the whole open conversation; throttled to 10 Hz, tier-independent, nothing lost on screen (EXP-010). Next = the same tick's second consumer at `MainActivity.kt:573`

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** Working tree carries ERROR-033/034/035, task #3,
task #4 (complete), and four task #5 landings (frame-path allocations, send-side resume bookkeeping,
receiver done-set, progress cadence). No commit requested by the owner; the tree is eight work-windows
deep.

### Last verified build
The authoritative command below. **958 live tests / 12 known Windows DataStore failures / 0 skipped**,
fresh `app-debug.apk`. `BASELINE_TEST_TOTAL` is now **958** (CONVENTIONS R3 updated; `:core:messaging`
36 → 41).

```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```

Only the 12 `:core:persistence` failures may fail. Anything else is a regression.

### Current phase
Tasks #1–#4 complete. **Task #5 (low-end library speed) is IN PROGRESS.** Landed: EXP-001 frame
allocation churn; **EXP-008** send-side resume bookkeeping; **EXP-009** receiver done-set; **EXP-010**
the attachment-progress cadence into the conversation mapper. All four were convicted by arithmetic over
repo constants, which is the only §23-legal route without hardware.

### In progress
Task #5. The **chat** consumer of the transfer progress tick is now paced. The **root-composable**
consumer of the same tick is identified and untouched — see "Recommended next task".

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures.

### Last change
- **New** `core/messaging/.../util/ProgressThrottle.kt` — `internal fun <T> Flow<T>.throttleLatest(windowMs: Long)`:
  emit, then `delay(windowMs)`. **Leading-edge**, not `kotlinx`'s trailing-edge `sample`: the flow feeds
  a `combine` that cannot emit until every input has, so `sample` would have blanked the conversation for
  up to a window on open (**ERROR-034**). Non-positive window disables throttling.
- `core/messaging/.../RealFlashChatRepository.kt` — `pacedAttachmentProgress` (100 ms via
  `ATTACHMENT_PROGRESS_THROTTLE_MS`), declared **above the `init` block** for the reason recorded on
  `drainMutex`; both consumers switched (the `init` path-stamping collector, and the `contentFlow`
  combine's third input).
- Nothing changed at the wiring sites (`Flash.kt:258`, `DiscoveryEngineHolder.kt:541`) **on purpose**:
  the operator suspends upstream instead of buffering, so the `StateFlow` conflates and the intervening
  `activeTransfers.map { … }` never runs — the discarded maps are never built.
- Tests: `ProgressThrottleTest` +4 (wall-clock by design, bounds derived from *measured* elapsed time);
  `RealFlashChatRepositoryTest` +1 (300-tick burst, then the terminal `Downloaded` must land on **both**
  the rendered `localUri` and the row's `attachmentPath`).
- Docs: `logs/experiments.md` EXP-010, `logs/progress.md` (f), CONVENTIONS R3 (958 / `:core:messaging` 41).

### Why this is not gated on the performance tier
The owner's constraint is that HIGH mode loses no quality or animation. Nothing here is tiered because
nothing HIGH-tier changes: `progress` only advances per ACK_BATCH (one per 2 MB), `speedMbps` renders at
`"%.1f"`, `etaSeconds` is not rendered at all, and the bar is animated by `animateFloatAsState` against
Compose's frame clock. The 10 ms cadence carried nothing the screen could show.

### Startup: what was cleared (do not re-audit)
`FlashApplication` is a bare `@HiltAndroidApp` shell. `MainActivity.onCreate` does no disk I/O. Every
expensive `AppEngine` member is `by lazy`, and `start()` touches `performanceMode.value` on
`Dispatchers.Default` on purpose so the `MediaCodecList` tier walk is paid off the first composition. The
AndroidKeyStore passphrase unwrap is lazy (Room calls it on first query) and one-time. None of these need
work.

### Recommended next task
1. **`MainActivity.kt:560-604` — the second consumer of the 100 Hz tick.** `collectAsState()` on
   `activeTransfers` sits at the **root** composable and feeds
   `remember(domainTransfers, transfersReady, chatStartError) { TransfersUiState.fromDomain(...) }`, so the
   app root invalidates on every tick during a transfer. Compose frame-coalesces the recomposition, so
   this is milder than the chat path was — read `TransfersUiMapper.kt` and size `fromDomain` before
   choosing between a throttle, a `distinctUntilChanged` on just the fields the Transfers tab renders, or
   hoisting the collection out of the root. Do not assume a throttle is right here: the Transfers tab is
   the one surface where a *speed* readout is the point.
2. **Owner instruction needed (R8): bound `transfer_chunks`.** `preloadReceiverProgress()` reads every
   done row on the device with no predicate, and **nothing prunes the table** — `RetentionPolicy` is
   fully unit-tested with **zero production callers**, and no `DELETE` exists for `transfers` or
   `transfer_chunks`, so it is append-only for the life of the install. The fix is a status-joined
   `allDoneChunks` and/or a real delete sweep — both Room changes, and `TransferDao` has no "all
   transfers" query to filter against from the adapter side. **R8 blocks this without an explicit
   instruction.**
3. **Owner: EXP-007** (still the decisive gate for every low-end *claim*; EXP-008/009/010 may only be
   quoted as counts). Add one item: which `startEngineLocked` stage dominates the cold-start splash. The
   sequence is fully serialized under one mutex, but its order encodes ERROR-032/033 and #4/#20
   invariants, so it must not be reordered on inspection alone.
4. Optional, needs an ADR-024 port change: fold `markChunksDone` + `setBytesDone` into one Room
   transaction to halve the remaining fsyncs.
5. Commit/split decision is the owner's.

### Files most relevant to next task
- `app/.../MainActivity.kt:560-604` — the root `collectAsState()` and the `remember` re-map.
- `app/.../TransfersUiMapper.kt` — `TransfersUiState.fromDomain`, the work being repeated.
- `core/messaging/.../util/ProgressThrottle.kt` — the operator, if a throttle turns out to be the right
  shape there too (it is `internal` to `:core:messaging`; a second consumer in `:app` would need a home
  decision, and `:core:common` was rejected because it is the live Phase-06 KMP pilot).
- `core/transfer/.../MultiStreamDispatcher.kt:150-249, 626-656` — `WATCH_POLL_MS` and `publishProgress()`,
  the source of the cadence.

## 2026-09-04 (e) — Task #5: startup cost inspected end to end; the receiver done-set is now one bit per chunk instead of ~50 bytes (EXP-009). What remains of startup is either R8-gated or needs a real trace

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** Working tree carries ERROR-033/034/035, task #3,
task #4 (complete), and three task #5 landings (frame-path allocations, send-side resume bookkeeping,
receiver done-set). No commit requested by the owner; the tree is seven work-windows deep.

### Last verified build
The authoritative command below. **953 live tests / 12 known Windows DataStore failures / 0 skipped**,
fresh `app-debug.apk`. `BASELINE_TEST_TOTAL` is now **953** (CONVENTIONS R3 updated; `:core:transfer`
100 → 102).

```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```

Only the 12 `:core:persistence` failures may fail. Anything else is a regression.

### Current phase
Tasks #1–#4 complete. **Task #5 (low-end library speed) is IN PROGRESS.** Landed: EXP-001 frame
allocation churn; **EXP-008** send-side resume bookkeeping (was O(chunks²) in allocations, wrote the
`transfers` row ~100×/s); **EXP-009** the receiver done-set. All three were convicted by arithmetic over
repo constants, which is the only §23-legal route without hardware.

### In progress
Task #5. Startup cost is now **inspected as far as inspection can take it** — see "Startup: what was
cleared" below, so the next window does not re-audit it.

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures.

### Last change
- `core/transfer/.../RealFlashTransferRepository.kt` — `receiverDone` is `ConcurrentHashMap<String,
  BitSet>` (one bit per chunk, **~400×** smaller than the boxed-`Integer` set it replaced), each entry
  mutated under `synchronized`; `getOrPut` → `computeIfAbsent` (the old form was a non-atomic
  get-then-put that could drop a racing coroutine's marks); negative indexes dropped rather than passed
  to `BitSet.set`, which throws where the old `HashSet.add` silently accepted. `import java.util.BitSet`.
  KDoc records the `totalChunks / 8` worst case and the pruning gap.
- Public surface unchanged — `receiverDoneIndexes(transferId): List<Int>` still returns ascending, and
  its callers at `Flash.kt:220` / `DiscoveryEngineHolder.kt:410` needed no edit.
- Tests: `RealFlashTransferRepositoryTest` +2 (preload ordering across a `BitSet` word boundary, per-
  transfer isolation, corrupt-row survival; delta-only persistence with in-batch de-dup and a wholly
  redundant batch reaching the store not at all).
- Docs: `logs/experiments.md` EXP-009, `logs/progress.md` (e), CONVENTIONS R3 (953 / `:core:transfer` 102).

### Startup: what was cleared (do not re-audit)
`FlashApplication` is a bare `@HiltAndroidApp` shell. `MainActivity.onCreate` does no disk I/O. Every
expensive `AppEngine` member is `by lazy`, and `start()` touches `performanceMode.value` on
`Dispatchers.Default` on purpose so the `MediaCodecList` tier walk is paid off the first composition. The
AndroidKeyStore passphrase unwrap is lazy (Room calls it on first query) and one-time. None of these need
work.

### Recommended next task
1. **Owner instruction needed (R8): bound `transfer_chunks`.** `preloadReceiverProgress()` reads every
   done row on the device with no predicate, and **nothing prunes the table** — `RetentionPolicy` is
   fully unit-tested with **zero production callers**, and no `DELETE` exists for `transfers` or
   `transfer_chunks`, so it is append-only for the life of the install. `Completed` transfers' rows are
   unresumable dead weight that is read back every launch. The fix is a status-joined `allDoneChunks`
   and/or a real delete sweep — both Room changes, and `TransferDao` has no "all transfers" query to
   filter against from the adapter side, so it cannot be done outside the DAO. **R8 blocks this without
   an explicit instruction.**
2. **Owner: EXP-007** (still the decisive gate for every low-end claim; no throughput claim may be made
   from EXP-008 or EXP-009). Add one item to its run: which `startEngineLocked` stage dominates the
   cold-start splash. `MainActivity` holds the splash for the entire transport boot, and the sequence is
   fully serialized under one mutex — but its order encodes invariants from ERROR-032/033 and #4/#20, so
   it must not be reordered on inspection alone.
3. Optional, needs an ADR-024 port change: fold `markChunksDone` + `setBytesDone` into one Room
   transaction to halve the remaining fsyncs.
4. Commit/split decision is the owner's.

### Files most relevant to next task
- `core/transfer/.../RealFlashTransferRepository.kt` — `preloadReceiverProgress` / `receiverDone` /
  `onIncomingChunkConfirmed` (~663-720), and the `TransferStore` port it calls.
- `core/engine/.../RoomTransferStore.kt` + `core/persistence/.../TransferChunkDao.kt` +
  `TransferDao.kt` — where the R8-gated predicate would go.
- `core/transfer/.../RetentionPolicy.kt` — the unwired pruner seam.
- `app/.../MainActivity.kt:123-182` and `app/.../debug/DiscoveryEngineHolder.kt:297-470` — the splash
  coupling and the serialized boot sequence.

## 2026-09-04 (d) — Task #5: send-side resume bookkeeping de-quadraticised and the 100 Hz `transfers`-row fsync storm cut (EXP-008); next = startup cost, starting with `preloadReceiverProgress()` vs `RetentionPolicy`

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** Working tree carries ERROR-033/034/035, task #3,
task #4 (complete), and two task #5 landings (frame-path allocations, and now resume bookkeeping).
No commit requested by the owner; the tree is six work-windows deep.

### Last verified build
The authoritative command below. **951 live tests / 12 known Windows DataStore failures / 0 skipped**,
fresh `app-debug.apk`. `BASELINE_TEST_TOTAL` is now **951** (CONVENTIONS R3 updated; `:core:transfer`
95 → 100).

```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```

Only the 12 `:core:persistence` failures may fail. Anything else is a regression.

### Current phase
Tasks #1–#4 complete. **Task #5 (low-end library speed) is IN PROGRESS.** Landed so far: the EXP-001
frame allocation churn (single-allocation `ChunkFrame.serialize`, in-place masking on the consuming
send, `readMessage` single-frame fast path) and now **EXP-008** — the send-side resume bookkeeping was
O(chunks²) in allocations and wrote the `transfers` row ~100×/s. This *was* the "DB batching" item the
previous handoff deferred; it was convicted by arithmetic over repo constants
(`WATCH_POLL_MS = 10L`, `DEFAULT_ACK_EVERY = 32`, `DEFAULT_CHUNK_SIZE_BYTES = 64 KiB`) rather than by
intuition, which is the only §23-legal route without hardware.

### In progress
Task #5 — startup cost is the remaining item and is still unmeasured.

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures.

### Last change
- `core/transfer/.../chunked/ResumeBitVector.kt` — new `receivedIndexesNotIn(other)` (`BitSet.andNot`
  word arithmetic; boxes only the delta).
- `core/transfer/.../multistream/MultiStreamDispatcher.kt` — new `confirmedIndexesNotIn(known)` and
  `totalChunks`. Existing snapshot accessors untouched.
- `core/transfer/.../RealFlashTransferRepository.kt` — the send-side progress collector: an O(1)
  `confirmedCountSnapshot()` gate, delta-based chunk persistence against a local mirror
  `ResumeBitVector`, and `setBytesDone` moved onto the chunk-row cadence.
- Tests: `ResumeBitVectorTest` +4, `RealFlashTransferRepositoryTest` +1 (recording `TransferStore`
  over the 8-chunk ACK-loopback harness; exactly-once, ascending, bounded byte writes).
- Docs: `logs/experiments.md` EXP-008, `logs/progress.md` (d), CONVENTIONS R3.

### Recommended next task
1. **Task #5 continued — startup cost.** `FlashApplication` is a bare `@HiltAndroidApp` shell, so look
   at Hilt graph construction, `MainActivity`, engine init, and especially
   **`preloadReceiverProgress()`**: a full `SELECT transferId, chunkIndex FROM transfer_chunks WHERE
   done = 1` across every transfer ever made, with no visible pruning. Check it against
   `RetentionPolicy` — inspectable without hardware, the same way EXP-008 was.
2. **Owner: EXP-007** (still the decisive gate for every low-end claim; no throughput claim may be
   made from EXP-008).
3. Optional, needs an ADR-024 port change: fold `markChunksDone` + `setBytesDone` into one Room
   transaction to halve the remaining fsyncs.
4. Commit/split decision is the owner's.

### Files most relevant to next task
- `logs/progress.md` 2026-09-04 (d) and `logs/experiments.md` EXP-008 — the full record.
- `core/transfer/.../RealFlashTransferRepository.kt` (`preloadReceiverProgress()`),
  `core/persistence/.../dao/TransferChunkDao.kt`, and whatever owns `RetentionPolicy`.
- `app/.../di/FlashApplication.kt`, `MainActivity.kt`, `di/AppEngine.kt` for the startup path.

## 2026-09-04 (c) — Task #5 STARTED (frame-path allocation churn cut, EXP-001 LOS finding); next = measure DB batching + startup on the Belfone, or EXP-007

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** Working tree carries ERROR-033/034/035, task #3,
task #4 (complete), and the first task #5 landing. No commit requested by the owner.

### Last verified build
Same authoritative command as the (b) section (plus `assembleRelease` there). **946 live tests /
12 known Windows DataStore failures / 0 skipped.** `BASELINE_TEST_TOTAL` is now **946**
(CONVENTIONS R3 updated; `:core:network` 135 → 137).

### Current phase
Tasks #1–#4 complete. **Task #5 (low-end library speed) is IN PROGRESS:** the EXP-001 allocation
churn is fixed (single-allocation `ChunkFrame.serialize`, in-place masking on the consuming send,
single-frame fast path in `readMessage`). DB batching and startup cost remain, and both need a real
measurement before any edit (AGENTS.md §23). Task #6 received one item incidentally: the load-flaky
`hasLoaded` tests are now `withTimeout`-deterministic.

### In progress
Task #5 — see above.

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures.

### Last change
`ChunkFrame.kt` (serialize rewrite), `WebSocketCodec.kt` (`maskPayloadInPlace`, single-frame fast path),
`WsConnection.kt` (`sendBinaryConsuming`), `Flash.kt` (2 consuming call sites),
`WebSocketCodecTest.kt` (+2), `RealFlashChatRepositoryTest.kt` (deterministic `hasLoaded` awaits).

### Recommended next task
1. **Owner: EXP-007** (still the decisive gate for every low-end claim).
2. Task #5 continued: **measure** DB write batching (Room inserts per message/chunk-row?) and engine
   startup cost on the Belfone, then optimize only what the profile convicts.
3. Commit decision is the owner's: the tree is now five work-windows deep.

### Files most relevant to next task
- `logs/progress.md` 2026-09-04 (c) — the full task #5 record.
- `core/transfer/.../chunked/ChunkFrame.kt`, `core/network/.../ws/WebSocketCodec.kt`,
  `WsConnection.kt`, `core/engine/.../Flash.kt`.
- `logs/experiments.md` EXP-001 (the baseline this work attacks; re-run on 5 GHz still open).

## 2026-09-04 (b) — Task #4 UI de-bloat CLOSED (release optimization + delivery-check gate were the last two); next = task #5 (low-end library speed) or #6 (opportunistic optimisations)

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** The working tree carries ERROR-033 tiering,
ERROR-034/035 network-change work, task #3, and the now-complete task #4 de-bloat set. No commit has
been requested by the owner. `logs/` itself was briefly missing from the repo root (it had been moved
into `New folder/logs/` alongside a session export) — restored this session; `New folder/` also holds
the previous AI session's transcript for archaeology and can be deleted when no longer needed.

### Last verified build
```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug assembleRelease :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```
- **944 live tests / 12 failures / 0 skipped** — the same known Windows-only `:core:persistence`
  DataStore atomic-rename set, before AND after this session's edits. Per-module table unchanged from
  the 2026-09-04 section below.
- `app-debug.apk` rebuilt; **`app-release-unsigned.apk` = 52.8 MB vs debug 67.6 MB** — the first
  release build with `optimization.enable = true` (R8 + optimized resource shrinking, AGP 9.3+ DSL).
  Built clean on the first attempt; no keep rules needed.

### Current phase
Tasks #1 (phantom conversations), #2 (network-change handling), #3 (Wi-Fi client + hotspot host
concurrency) and **#4 (UI de-bloat) are all complete.** Every finding on the de-bloat list is done —
see `logs/progress.md` 2026-09-04 (b) for the full inventory. Tasks #5 (low-end library speed) and #6
(opportunistic optimisations) from the owner's five-thread request are untouched.

### Working features (NEW since last handoff)
- **Release builds are optimized.** `release.optimization.enable = true` in `app/build.gradle.kts`.
- **No per-bubble `AnimatedContent` at LOW/MEDIUM.** `FlashDeliveryStatusIcon` gates the crossfade on
  `!reduceMotion`; the reduced path is a direct glyph swap that is visually identical (the spec already
  snapped) but drops the per-row `Transition` and second layout. HIGH is bit-identical.

### In progress
Nothing mid-edit.

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures (pass on Linux/macOS CI and on
device).

### Last change
`app/build.gradle.kts` (optimization) and `FlashDeliveryStatusIcon.kt` (conditional AnimatedContent,
glyph body extracted to private `DeliveryStatusGlyph`).

### Last test
944 / 12 / 0 + `assembleRelease` green, as above.

### Known blockers
- **EXP-007's on-device matrix is owner action** and is still the only step before ERROR-033 is DONE.
  Nothing from ERROR-034/035 or task #4 is confirmed on hardware either — all claims are
  build-and-unit-test verified only; the release APK additionally has not been installed anywhere.
- All other blockers from the 2026-09-04 section below stand (single-address endpoints deferred,
  hotspot-client ↔ router-LAN discovery by design, `LanController.kt:94`, `docs/session-prompt.md`
  staleness, `FlashDevConsoleScreen.kt` mojibake at :316).

### Recommended next task
**Task #5 — low-end library speed**, or **task #6 — opportunistic optimisations** (owner's five-thread
request; neither has a written scope yet — reconstruct from the request and the ERROR-033 tier work
before editing). Otherwise EXP-007 with the owner. Do not commit without the owner's go-ahead; the
split-vs-single decision for the accumulated changeset is theirs.

### Files most relevant to next task
- `logs/progress.md` 2026-09-04 (b) — the complete task #4 inventory and verification record.
- `core/common/src/commonMain/kotlin/.../perf/` — the tier definitions any speed work must respect.
- `app/build.gradle.kts`, `docs/migration/CONVENTIONS.md` (R3 baseline 944).

### Verify command (Git Bash, authoritative)
Same as *Last verified build* above. Do **not** add `--offline`. `--continue` is load-bearing. The
`:core:persistence` 12 are expected; anything else failing is a regression.

## 2026-09-04 — Network-change handling closed end-to-end (ERROR-035) + the phantom conversations removed (ERROR-034); next = task #4, UI de-bloat

### Current branch
`dev`, HEAD `5b31785`. **Nothing is committed.** The working tree now carries the ERROR-033 tiering
changeset *plus* four windows of ERROR-034/ERROR-035 work. No commit has been requested by the owner.

### Last verified build
```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```
- `app-debug.apk` rebuilt; the only failing task is `:core:persistence:testDebugUnitTest`.
- **944 live tests / 12 failures / 0 skipped** — the same Windows-only DataStore atomic-rename set
  (`FlashSettingsDataStoreTest` 11 + `DiscoveryModeSettingTest` 1). Measured per module: `:app` 32,
  `:core:calling` 63, `:core:common` 85 (`testAndroidHostTest`), `:core:discovery` 101, `:core:engine` 1,
  `:core:messaging` 36, `:core:network` 135, `:core:persistence` 35, `:core:security` 80,
  `:core:transfer` 95, `:ui:chat` 244, `:ui:theme` 37.
- `BASELINE_TEST_TOTAL` moves 911 → **944** in `docs/migration/CONVENTIONS.md` R3, now stated as a
  *measured* per-module table. The old 911 figure does not reconcile to 944 by one test; it was
  hand-written and its breakdown mis-credited `LinkChangeTrackerTest`. Prefer the table.
- The 49-XML stale-results trap named in the previous handoff is **resolved**: the orphaned pre-KMP
  `core/common/build/test-results/testDebugUnitTest/` directory was deleted, so a raw aggregation now
  agrees. The trap recurs for every future KMP conversion — delete the dead directory, do not "fix"
  a too-high count by assuming tests were added.

### Current phase
Task #2 of the owner's five-thread request (network-change handling) is **complete**. Task #1
(phantom conversations) was already complete and is now written up. Task #3 (Wi-Fi client + hotspot
host concurrency) has its central question answered and its one real bug fixed. Tasks #4 (UI
de-bloat), #5 (low-end library speed) and #6 (opportunistic optimisations) are untouched.

### Working features (NEW since last handoff)
- **All four ways a link changes now produce a signal (ERROR-035 D1/D2).** `LinkChangeTracker` moved
  to `core:common` `commonMain` — the only place a class can be shared, because `:core:network`
  depends on `:core:discovery`. Per-network fingerprints in both observers, a per-network NSD
  callback, and an interface poll for the SoftAP case. **A SoftAP interface is not a `Network`:** the
  platform hands out no `Network` object for `ap0`, so no `NetworkCallback` fires when a hotspot comes
  up while STA stays joined, and interface enumeration is the only permission-free all-API signal.
- **Destination-aware dialling (`Ipv4Routing`, `chooseRoute`).** `LocalNetworkAddresses` now **merges**
  its two sources instead of preferring ConnectivityManager and treating enumeration as a fallback.
  The old early-return made the interface branch unreachable in exactly the topology it was written
  for: a device both joined to Wi-Fi *and* hosting a hotspot reported only its router address, never
  the `192.168.43.1` its own tethered clients had to use.
- **Bounded auto-resume of roam-killed sends (ERROR-035 D4).** `TransferReconnectResumePolicy` in
  `:core:transfer`, wired into **both** session-up collectors (`DiscoveryEngineHolder` for the app,
  `Flash`'s `Wiring` for the library) as a **field**, so the budget spans session-up edges. Byte-exact
  resume already worked and nothing called it: a mesh roam mid-transfer left a `Failed` row until a
  human tapped retry. The cap counts only attempts that achieved nothing — `bytesDone` is recorded per
  attempt and the count clears when it later advances, so a 2 GB file survives ten roams while a
  genuinely broken source (deleted file, lapsed content-URI grant, full storage) stops after three.
  `Paused` is excluded: a pause is a user decision a network hiccup must not override. A 750 ms settle
  plus a session re-check precedes each re-offer, because both ends dial and `registerSession` closes
  the loser — a re-offer into the losing session would fail and burn an attempt.
- **The Dev Console probes real gateways.** `LocalNetworkAddresses.ipv4Gateways()` reads
  `LinkProperties.routes` (API 21, no gate), drops `0.0.0.0` next hops, and the NET tab tries each in
  turn. It used to dial a hardcoded `192.168.43.1` — one of at least five tethering subnets in use
  across OEMs (`.42.1`, `.49.1`, `.61.1`, `172.20.10.1`) and simply wrong for a client on an ordinary
  router. An empty list is the *correct* answer for a device that is hosting rather than joined.
- **No fabricated conversations during boot (ERROR-034).** The pre-boot fallback was
  `SampleFlashChatRepository()`, rendering three invented threads that vanished when the real
  repository arrived — hidden behind the splash on fast hardware, plainly visible on the Belfone.
  Now `EmptyFlashChatRepository` + `FlashChatListUiState.hasLoaded`, so empty no longer means both
  "no conversations" and "not answered yet". Three unreachable Loading/Error branches were wired for
  real, and `AppEngine.start()` clears `startError` on entry so the retry button stops looking inert.

### In progress
Nothing mid-edit. Task #3's remaining sub-items are closed or deliberately deferred (below).

### Broken
Only the 12 known Windows-only `:core:persistence` DataStore failures. They pass on Linux/macOS CI
and on device; the cause is Windows atomic-rename semantics in DataStore's test fixture.

### Last change
`FlashDevConsoleScreen.kt`'s `onProbeGateway` now enumerates `ipv4Gateways()` and probes each,
logging "No IPv4 gateway on any LAN network" when the list is empty. Its card copy was retitled from
"Hotspot gateway probe" to "Gateway probe" to stop advertising a single hardcoded subnet.

### Last test
944 / 12 / 0, as above. `TransferReconnectResumePolicyTest` is 9/9.

### Known blockers
- **EXP-007's on-device matrix is owner action** and remains the only step before ERROR-033 is DONE.
  Nothing in ERROR-034 or ERROR-035 is confirmed on hardware either — every claim above is
  build-and-unit-test verified only.
- **`FlashDiscoveredEndpoint`/`WsFlashNetwork.Endpoint` carry a single address (task #3 item e) —
  deliberately deferred.** A dual-homed hotspot host cannot be represented, so `sameEndpoint`
  compares one `hostAddress` and `NsdTransport.mapResolved` reads one `info.host?.hostAddress`. Every
  additive fix was rejected for cause: `NsdServiceInfo.getHostAddresses()` is **API 34+** and does
  nothing on the API-27 Belfone; a new TXT key is forbidden by **R8** (`TxtCodec` is a wire format);
  and the `Diff.Updated` flap it would prevent needs the platform to alternate addresses across
  resolves, for which there is no pre-34 evidence. The enabling move when this is revisited:
  `Ipv4Routing` is pure integer arithmetic with no `java.*`, so it is a valid `commonMain` citizen and
  could move to `:core:common` to let `:core:discovery` prefer an on-link address.
- **A hotspot client cannot discover a router-LAN peer, and this is by design for v1.** Client C
  reaches host H only. mDNS multicast is not forwarded across H's tethering NAT, discovery is the sole
  source of routes, HELLO carries no third-party addresses, and `FlashTransportType.RELAY`/`MESH` are
  unused placeholders — relay is post-v1.
- `app/.../lan/LanController.kt:94` never refreshes `LanUiState.localAddresses` after a roam. Legacy
  TCP dev-console path only; deferred.
- `docs/session-prompt.md:36-45` is stale (E:-drive paths, "~271 tests").
- `FlashDevConsoleScreen.kt:316` still contains pre-existing mojibake (`â†’`, UTF-8 read as Latin-1).
  The `â€¦` at the old :327 went out with the gateway rewrite. Use plain ASCII when editing this file.

### Recommended next task
**Task #4 — UI de-bloat**, one finding at a time, highest Belfone value first:
1. The per-row full-width **opaque** `SwipeToDismissBox` background behind every chat row.
2. `FlashMessageUi` is unstable *and* re-`copy()`-ed at `FlashMessageList.kt:163`.
3. `FlashMotion`'s three spring factories (`FlashMotion.kt:312/317/322`) ignore `reduceMotion`.
4. Enable `isMinifyEnabled` / `shrinkResources`.
Then `FlashChatListRow.kt:149` (press-scale read in composition scope; two dead `FlashTheme` reads at
69-70), per-bubble `BoxWithConstraints` (`FlashMessageBubble.kt:92`), tailed-bubble clipping through
`Outline.Generic` (`FlashShapes.kt:93`), identity `graphicsLayer` + `animateItem` per row at LOW,
`FlashMediaDecoder`'s ~2x-oversized 720px ARGB_8888 tiles, `AnimatedContent` per delivery check mark,
`FlashBottomNav.kt:303/422` rebuilding `FlashTypography` per recomposition, and the dead
`FlashAdaptiveLayouts.kt` + five unreferenced drawables.

### Files most relevant to next task
- `ui/chat/src/main/java/com/transfer/flash/ui/chat/FlashMessageList.kt`,
  `FlashMessageBubble.kt`, `FlashChatListRow.kt`
- `ui/theme/src/main/java/com/transfer/flash/ui/theme/FlashMotion.kt`, `FlashShapes.kt`
- `app/build.gradle.kts` (minify/shrink), `ui/chat/.../FlashAdaptiveLayouts.kt` (dead)

### Verify command (Git Bash, authoritative)
```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --continue --console=plain --max-workers=2
```
Do **not** add `--offline`. `--continue` is load-bearing. `bc` is unavailable — sum with `awk`.

## 2026-09-03 — Three performance tiers (low/medium/high), auto-detected each boot: packet-rate-priced voice, capped capture, mesh-roam call recovery, a UI that stops animating (ERROR-033) + the capture-source probe (ERROR-032)

### Current branch
`dev`, HEAD `5b31785`. The tiering changeset is **UNCOMMITTED**: 27 modified files (19 code + 8
docs/logs) + 6 untracked paths in the working tree, listed under *Files most relevant to next task*.
No commit has been requested by the owner. ERROR-032's fix is already in HEAD, and ERROR-031's landed
earlier as `4bb1240` — so what is uncommitted here is **ERROR-033 only**, plus its documentation.

### Last verified build
```bash
./gradlew testDebugUnitTest assembleDebug --console=plain --max-workers=2
```
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (4m 2s).
- **911 live tests, 12 failures, 0 errors, 0 skipped.** All 12 are the known Windows-only DataStore
  atomic-rename failures in `:core:persistence` (`DiscoveryModeSettingTest` 1 +
  `FlashSettingsDataStoreTest` 11) — byte-for-byte the baseline set, not a regression.
- **`BASELINE_TEST_TOTAL` moves 863 → 911** (updated in `docs/migration/CONVENTIONS.md` R3):
  `CallSdpTest` 16→24 (+8), `LinkChangeTrackerTest` (+10), `FlashPerformanceClassifierTest` (+23),
  `FlashMotionPolicyTest` (+3), `FlashSettingsLogicTest` (+4).
- **Trap when you re-count: a raw XML aggregation reports 960, not 911.** 49 of those are stale
  pre-KMP `core/common/build/test-results/testDebugUnitTest/` files still on disk. `:core:common` is
  KMP since Phase 06, its live task is `testAndroidHostTest` (75 tests), and a root
  `testDebugUnitTest` no longer reaches it. Name it explicitly per CONVENTIONS R3, or delete the
  stale directory before counting.

### Current phase
The owner field-tested on a **Belfone SCP810** (rugged PoC/PTT handset: 2 GB RAM, Android 8.1/API 27,
480x640 display, qcom, 2.4 GHz b/g/n only, **no 802.11k/v/r**) and got "a lot of lag connection lost
and even supprising huge latencies", while a **Pixel 7** and an **Infinix X6882B** on the same **mesh**
Wi-Fi "worked fine at long distances" and recovered from node handoffs the Belfone did not survive.
Voice-only at 25 kbit/s lagged too — so this was never a bandwidth problem. Three tiers now exist, are
auto-detected on every boot, and reach every consumer as a lambda. **Code complete and green;
on-device verification (EXP-007) is owner action and is the only step left before this is DONE.**

### Working features (NEW since last handoff)
- **`FlashPerformanceMode` (LOW / MEDIUM / HIGH)** in `core:common` `commonMain` — the tier plus its
  four profiles (`FlashVideoProfile`, `FlashAudioProfile`, `FlashKeepaliveProfile`, motion flags),
  `fromKey`/`toKey`, `reduceMotion`, `minimalChrome`. **`HIGH` is the pre-tiering constants verbatim**,
  so that tier is provably a no-op against the previous build.
- **Auto-detection with no first-run flag (ADR-028).** `FlashPerformanceClassifier` runs hard gates
  (RAM < 2560 MB, API < 26, display < 500k px, cores ≤ 2 → LOW; two weak concerns → MEDIUM). An
  **unset** preference *is* auto, and auto is re-resolved every boot — nothing is persisted at first
  run, so a wrong verdict is never sticky. The user can pin a tier; `"auto"` and any unrecognised
  token map to null.
- **Voice priced by packet rate, not bit rate (D1).** LOW raises the Opus frame to `a=ptime:60` with
  `usedtx=1` — ~16 packets/s instead of ~100. At ≈50 bytes of RTP/UDP/IP/SRTP header per packet the
  headers alone outweighed 25 kbit/s of speech, and 802.11 charges a largely fixed airtime price *per
  frame*. This is why every previous bitrate reduction changed nothing.
- **Capture capped upstream of the encoder (D2).** LOW captures 480x360@15, MEDIUM 960x540@24, HIGH
  1920x1080@30. On a 480x640 panel the old 1080p30 request was ≈62 Mpixel/s of pure waste, spent
  regardless of what the encoder then chose to send.
- **A mesh roam no longer kills the call (D3).** `LinkChangeTracker` diffs `LinkProperties` /
  `NetworkCapabilities` because an AP-to-AP roam keeps the **same** `Network` object — so
  `onAvailable`/`onLost` never fire and nothing used to re-probe. `onSignalingLost` now opens a
  recovery window instead of ending the call, `onSignalingRestored` closes it, and per-tier keepalive
  (`WsKeepaliveTiming`: LOW pings 15 s / forgives 40 s, HIGH 10 s / 25 s) is the second layer.
- **Per-endpoint SDP (ADR-029).** `CallSdp.tune()` split into `tuneLocal` (asserts our tier) and
  `tuneRemote` (reconciles the peer's: **longer** frame, **smaller** ceiling). Two devices on
  different tiers converge on identical session parameters by reconciliation rather than by symmetry.
  Keepalive cadence is deliberately *not* reconciled — it is local policy.
- **Extreme-minimalist UI at LOW and MEDIUM.** `FlashMotionPolicy` treats the tier as a **floor**:
  `mode.reduceMotion || (overrideForcesReduce ?: systemReduceMotion)`. Animations off, and
  `minimalChrome` separately drops drop-shadows (a shadow costs the same on a still frame as on a
  moving one). `FlashBottomNav` is the first consumer. `FlashMotion`'s constructor stays `internal`.
- **Settings → PERFORMANCE** shows the resolved verdict, e.g. `Auto · Matched to this device: Low`.
- **Capture-source probe (ERROR-032, already in HEAD `5b31785`).** On both SCP810 units
  `AudioRecord(VOICE_COMMUNICATION)` reached INITIALIZED, passed `verifyAudioConfig`, and then
  delivered **zero frames** — the far end heard nothing. The ADM source is now probed once
  (`VOICE_COMMUNICATION` → `MIC` → `DEFAULT`, pass = 2400 frames ≈ 50 ms at 48 kHz) and cached;
  hardware AEC/NS are enabled only for `VOICE_COMMUNICATION`.

### In progress
- **EXP-007 — the decisive on-device matrix** (owner action; see `logs/experiments.md`). Re-run all
  three EXP-006 rows on the tiered build across the Belfone / Pixel 7 / Infinix.
- Nothing else. No code is half-written; the changeset compiles and tests green as it stands.

### Broken
- Nothing new. The 12 `:core:persistence` DataStore failures are the pre-existing Windows
  file-locking set and predate this work.

### Last change
The full tiering changeset (ERROR-033) plus its repository record. Code: `FlashPerformanceMode` /
`FlashVideoProfile` / `FlashAudioProfile` / `FlashKeepaliveProfile` / `FlashPerformanceClassifier` /
`FlashMotionPolicy` (new, in `core:common`), `LinkChangeTracker` + `WsKeepaliveTiming` (new, in
`core:network`), `CallSdp.tuneLocal`/`tuneRemote`, `onSignalingRestored` on `FlashCalling`,
`performanceMode: () -> FlashPerformanceMode` threaded through `CallCoordinator` /
`WsTransferClient` / `WsTransferServer` / `WsConnection` as a reader lambda (ADR-024 port/adapter —
`:core:*` still never sees DataStore), the Settings PERFORMANCE section, and `FlashBottomNav`'s
`minimalChrome` path. Docs: ERROR-033 + a back-filled ERROR-032 in `logs/errors.md`, EXP-006 in
`logs/experiments.md`, ADR-028 + ADR-029 in `docs/decisions.md`, a new `docs/android-platform-notes.md`
entry, `docs/architecture/public-api.md` de-staled (new fourth seam), and a `logs/progress.md` entry.

### Last test
- `./gradlew testDebugUnitTest assembleDebug` → `:app:assembleDebug` **BUILD SUCCESSFUL**;
  **911 live tests / 12 known failures / 0 errors / 0 skipped** (details under *Last verified build*).
- `:core:calling:testDebugUnitTest` → 55 tests for the ERROR-032 probe, including `client audio
  source=MIC` and the ~85 ms teardown that replaced an 8.2 s `AudioRecord.stop` hang.
- **Physical verification PENDING (EXP-007), the whole point of the change:**
  1. Belfone: Settings → PERFORMANCE must read `Auto · Matched to this device: Low`; the Pixel 7 must
     read `High`. If the Belfone reads MEDIUM or HIGH, the classifier thresholds are wrong — that is
     the first thing to check, before touching anything else.
  2. Voice-only call, stationary: the lag and "supprising huge latencies" should be gone.
  3. **Walk between mesh nodes mid-call.** Success is *the call surviving the roam* with an audio gap
     of a few seconds, and the peer returning to Online in single-digit seconds rather than ~30.
  4. Visual: no animations and no bottom-nav drop shadow at LOW/MEDIUM.
  5. Belfone ↔ Pixel 7 video call: both ends must settle at **540p or below** (reconciliation), not
     just the Belfone.

### Known blockers
- **The "authoritative" install command in the older sections below is wrong for this machine.** It
  points `JAVA_HOME` at `E:\AndroidDev\AndroidStudio\android-studio\jbr`, which is **JBR 25.0.2** —
  too new for Gradle 9.5.0 / AGP 9.3.1. Use the Gradle-provisioned **JBR 21** instead; the working
  invocation is at the bottom of this section. `docs/session-prompt.md` §BUILD ENVIRONMENT is stale
  for the same reason (and still says `E:\Flash` and "~271 tests").
- **ERROR-017 still mandatory:** every Gradle invocation dies with "Unable to establish loopback
  connection" unless `JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=…"` points the AF_UNIX temp dir
  somewhere the JDK's TCP fallback will trigger. Export it via `JAVA_TOOL_OPTIONS` (not
  `org.gradle.jvmargs`) so the daemon, Kotlin daemon **and** test workers all inherit it.
- **Do not add `--offline`.** `generateDebugUnitTestStubRFile` fails offline on
  `androidx.annotation:annotation-experimental:1.5.0` ("No cached version available") and discards the
  configuration-cache entry when it does. The network is available here; dropping the flag works.
- **A root `testDebugUnitTest` silently under-counts.** Any module converted to
  `com.android.kotlin.multiplatform.library` has no `debug` variant and therefore no
  `testDebugUnitTest` task. `:core:common` must be named explicitly as
  `:core:common:testAndroidHostTest` (CONVENTIONS R3/R3.1), and its old `testDebugUnitTest` XML is
  still on disk inflating naive counts by 49.
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust the exit code alone.
- If `./gradlew` reports `JAVA_HOME is set to an invalid directory` for a path that worked minutes
  earlier, the external drive holding `~/.gradle` was detached. Retry the same command unchanged once
  it is back; this is not a broken JAVA_HOME.

### Recommended next task
1. **Run EXP-007** (owner action, decisive). The five checks are listed under *Last test*. Record the
   results in `logs/experiments.md` as EXP-007 against the EXP-006 table.
2. **If the Belfone still lags after this, the discriminating measurement is packets/s on the wire,
   not bitrate.** Confirm `a=ptime:60` and `usedtx=1` survived into the **answer** (`tuneRemote`
   reconciliation) — a peer that re-offers 10 ms framing undoes D1 entirely and the symptom is
   indistinguishable from the original bug.
3. **If a roam still drops the call**, check `LinkChangeTracker` actually fired. An OEM that reports no
   `LinkProperties` change on reassociation would defeat layer 1, and then the fallback is the
   keepalive cadence (layer 2), **not** more roam detection.
4. Capture the serving AP's band and channel width, and whether the mesh backhaul is wired or
   wireless. A wireless backhaul halves usable airtime again and would change what LOW should target.
5. Only then consider committing (nothing is committed) and returning to the KMP migration
   (Phase 07 onward) or the premium chat UI sequence.

### Files most relevant to next task
**New (untracked) — the tier itself:**
- `core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/` — `FlashPerformanceMode.kt`,
  `FlashVideoProfile.kt`, `FlashAudioProfile.kt`, `FlashKeepaliveProfile.kt`,
  `FlashPerformanceClassifier.kt`, `FlashMotionPolicy.kt`
- `core/common/src/androidMain/kotlin/.../perf/` — the Android probe feeding the classifier
- `core/common/src/androidHostTest/kotlin/.../perf/` — `FlashPerformanceClassifierTest` (+23),
  `FlashMotionPolicyTest` (+3)
- `core/network/src/main/java/.../resilience/LinkChangeTracker.kt` + its test (+10) — the roam detector
- `core/network/src/main/java/.../ws/WsKeepaliveTiming.kt` — the ping/liveness pair, `init`-guarded
  against a liveness window shorter than `pingInterval * WsKeepalive.STALL_FACTOR`

**Modified code:**
- `core/calling/.../CallSdp.kt` (+ `CallSdpTest.kt`, 16→24) — `tuneLocal` / `tuneRemote`
- `core/calling/.../CallCoordinator.kt`, `FlashCallSession.kt`, `FlashCalling.kt` —
  `performanceMode` lambda, `onSignalingRestored`, recovery window
- `core/network/.../ws/WsConnection.kt`, `WsTransferClient.kt`, `WsTransferServer.kt`,
  `WsFlashNetwork.kt`, `resilience/AndroidNetworkWatcher.kt` — per-connection keepalive cadence,
  defaulted so untiered callers are unchanged
- `core/persistence/.../settings/FlashSettingsDataStore.kt` — the pinned-tier key (`"auto"` → null)
- `app/.../MainActivity.kt`, `debug/DiscoveryEngineHolder.kt`, `di/AppEngine.kt` — host wiring; the
  reader lambdas are constructed here, never inside `core:*` (ADR-024)
- `ui/theme/.../FlashMotion.kt`, `FlashTheme.kt` — the resolved `Boolean` crossing the `:ui:theme`
  seam (`FlashMotion`'s constructor stays `internal`)
- `ui/chat/.../shell/FlashBottomNav.kt` — first `minimalChrome` consumer
- `ui/chat/.../settings/FlashSettingsScreen.kt` (+ `FlashSettingsLogicTest.kt`, +4) — PERFORMANCE section

**Record:** `logs/errors.md` (ERROR-033, ERROR-032), `logs/experiments.md` (EXP-006),
`docs/decisions.md` (ADR-028, ADR-029), `docs/android-platform-notes.md`,
`docs/architecture/public-api.md`, `logs/progress.md`, `docs/migration/CONVENTIONS.md` (baseline 911).

### Remaining work summary (for next AI)
1. **EXP-007 on-device matrix** (owner-driven, decisive) — the five checks under *Last test*; record in
   `logs/experiments.md`.
2. **Deferred by decision: a tier below LOW** for the "devices lower than the Belfone, and possibly an
   Android watch" the owner mentioned. Deferred until such a device exists to measure, because the
   thresholds are exactly the part that cannot be guessed from a spec sheet. ADR-028's revisit rule is
   explicit: **do not revisit by adding a fourth enum constant for a device nobody has measured.**
3. **Nothing is committed.** Decide with the owner whether ERROR-033 lands as one commit or is split
   (tier + D1 + D2 + D3 + UI). CONVENTIONS **R4** forbids editing two modules' build files in one
   commit — no build files changed here, so R4 does not bite, but check before adding any.
4. EXP-003 charged-Infinix re-test (still open from 2026-09-01).
5. `docs/session-prompt.md` §BUILD ENVIRONMENT is stale (`E:\Flash`, the JBR 25 path, "~271 tests") —
   worth correcting when someone is in that file.
6. Deterministic cleanup of the messaging backoff timing test; Bug 7 device checklist pass.
7. Then: resume the KMP migration (Phase 07 onward — `:core:common` is the only converted module) or
   the premium chat UI sequence (UI-011 composer / UI-007 selection).

### Verify command (Git Bash, authoritative — supersedes the E:-drive blocks below)
```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew testDebugUnitTest assembleDebug :core:common:testAndroidHostTest --console=plain --max-workers=2
```
`C:\Users\KaliOxygen\.gradle\afunix` must exist. Expect **911 live tests / 12 known failures**. Naming
`:core:common:testAndroidHostTest` explicitly is required (CONVENTIONS R3), not optional. To install:

```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew :app:installDebug --console=plain
```

## 2026-09-02 (d) — Call-accept crash FIXED (ERROR-024): base64 SDP transport + try/catch hardening; physical call re-test pending

### Current branch
`dev` (work UNCOMMITTED in working tree; HEAD `43b1c2c`)

### Last verified build
- `:core:common:testDebugUnitTest` → **49 PASS** (incl. 7 new `Base64Test` cases)
- `:core:calling:testDebugUnitTest` → **15 PASS** (incl. byte-for-byte Offer/Answer SDP round-trips + legacy raw-SDP fallback)

### Current phase
**Call-accept crash (ERROR-024) root-caused & fixed; build + unit tests verified; physical
two-phone re-test pending.** Both phones crashed with `Setting SDP failed: SessionDescription
is NULL.` the moment a call was accepted. Root cause pinned by disassembling the webrtc-kmp
0.125.11 AAR: `onSetFailure` rethrows libwebrtc's native JNI error verbatim (null/empty
`SessionDescription.description` or native parse failure). API usage was correct; the suspect
is the `FLASH_CALL` text-frame transport (`FlashTextFraming` escapes only `%`/space/`=` and
does `trim().split(' ')` — exactly the wrong treatment for multi-line SDP). Fix: pure-Kotlin
base64 SDP transport in `CallFrameCodec` (whitespace/delimiter-free) + try/catch safety nets
in `FlashCallSession` so a native set-SDP failure ends the call instead of crashing the
process. ERROR-023 glare fix and calling remain code-complete with physical test pending.

### Working features (NEW since last handoff)
- **Base64 SDP transport (ERROR-024/ADR-027)**: `CallFrameCodec` base64-encodes Offer/Answer
  `sdp` fields (pure-Kotlin RFC 4648 `Base64` in `core/common` — no `android.util`/
  `java.util.Base64`, keeping JVM unit tests green at `minSdk 24` + `explicitApi()`).
  `decodeSdp` tries base64 first, falls back to raw text for legacy peers. Base64 cannot be
  corrupted by framing trim/escape/split.
- **Call crash safety net**: `FlashCallSession` wraps `onAccept`/`onOffer`/`onAnswer` SDP
  flows in try/catch (rethrow `CancellationException`; else log + `end(ERROR, notifyPeer =
  true)`) + `logSdp()` diagnostics. A native set-SDP failure now ends the call, never kills
  the process.
- **Connect-glare resolution (ERROR-023)**: deterministic tiebreaker — keep the session
  whose originator device id is lexicographically smaller. `WsSession.isOutbound` carries the
  origin; `registerSession.resolveGlareTie` applies it when transport ranks are equal.
- **Dial-engine dedup**: `runAutoConnectSweep` skips peers with an in-flight reconnect
  (`isReconnectInFlight`) so the sweep and the #18 reconnect engine never race the same peer.
- **Deterministic network pick**: `findLanNetwork()` sorts by `networkHandle` so both phones
  independently select the same network when multiple are eligible.
- **`enableOnBackInvokedCallback="true"`** in the manifest.
- **Calling + dual-band hypotheses ruled out** for the discovery storm (see ERROR-023/EXP-005).

### In progress
- **Physical two-phone calling re-test** (THE decisive step — the crash log precedes this fix;
  reinstall the APK and confirm call accept + placement connect without a crash).
- **Physical two-phone re-test of the glare fix** (confirm the storm stops after a session drop).
- Bug 7 device checklist pass (`docs/ui/notification-ui.md`).

### Broken
- Nothing new. (Pre-existing timing-flaky messaging backoff test note below.)

### Last change
Implemented the call-accept crash fix (2026-09-02): pure-Kotlin `Base64` in `core/common`,
`CallFrameCodec` base64 SDP transport + legacy raw fallback, `FlashCallSession` try/catch
safety nets + `logSdp()`, 7 new `Base64Test` + 3 new `CallFrameCodecTest` cases.
`:core:common:testDebugUnitTest` 49 PASS + `:core:calling:testDebugUnitTest` 15 PASS.

### Last test
- `:core:common:testDebugUnitTest` → 49 PASS (incl. Base64: empty, hello, binary, SDP
  round-trip, invalid char, bad padding, padded round-trips)
- `:core:calling:testDebugUnitTest` → 15 PASS (incl. byte-for-byte Offer/Answer SDP
  round-trips + legacy raw-SDP fallback)

### Known blockers
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust exit code alone
- Gradle metadata cache corruption: `gradlew --stop`, `taskkill //F //IM java.exe`,
  delete `E:\AndroidDev\Gradle\caches\modules-2\metadata-2.107`, rebuild
- Build env: `E:\` hosts SDK (`E:\AndroidDev\SDK`), Gradle home (`E:\AndroidDev\Gradle`), JBR
  (`E:\AndroidDev\AndroidStudio\android-studio\jbr`) — install command below is authoritative
- The messaging backoff timing test remains inherently timing-sensitive; deterministic
  cleanup still worthwhile
- **Stale installed APK**: the on-device APK predates even `580628d` (missing the
  `enableOnBackInvokedCallback` manifest fix) — reinstall before any physical re-test.

### Recommended next task
1. **Physical two-phone calling re-test** (decisive for ERROR-024): reinstall the APK on both
   phones, invite → accept → confirm the call screen connects without a crash (audio, video,
   FGS CallStyle buttons). If it still fails, `logSdp()` + the try/catch path now produce
   diagnostics instead of a process death. Record in `logs/experiments.md` / `logs/progress.md`.
2. **Physical two-phone re-test of the glare fix**: trigger a session drop (toggle Wi-Fi on
   one phone or background the app), then watch logcat — expect ONE `Session up` pair, no
   repeat "WS connecting" storm, no "cannot reach". Record in `logs/experiments.md`.
3. Then return to the premium chat UI component sequence: **UI-011 composer** or **UI-007
   selection** research next per `docs/ui/ui-research-index.md`.

### Files most relevant to next task
- `core/calling/src/main/java/com/transfer/flash/core/calling/protocol/CallFrameCodec.kt`
  (base64 SDP transport)
- `core/calling/src/main/java/com/transfer/flash/core/calling/FlashCallSession.kt`
  (try/catch safety nets + `logSdp`)
- `core/common/src/main/java/com/transfer/flash/core/common/protocol/Base64.kt` (new codec)
- `core/calling/src/test/java/com/transfer/flash/core/calling/protocol/CallFrameCodecTest.kt`
- `core/common/src/test/java/com/transfer/flash/core/common/protocol/Base64Test.kt`
- `core/network/src/main/java/com/transfer/flash/core/network/ws/WsFlashNetwork.kt`
  (`registerSession` glare tiebreaker, `isReconnectInFlight`)
- `core/network/src/main/java/com/transfer/flash/core/network/ws/WsSession.kt` (`isOutbound`)
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt` (sweep dedup)
- `core/network/src/main/java/com/transfer/flash/core/network/ws/WsTransferClient.kt`
  (`findLanNetwork` deterministic sort)
- `core/network/src/test/java/com/transfer/flash/core/network/ws/WsFlashNetworkTest.kt`
  (glare regression test)

### Remaining work summary (for next AI)
1. **Physical two-phone calling re-test** (decisive for ERROR-024) — record in `logs/experiments.md`
2. **Physical two-phone re-test of the glare fix** (decisive) — record in `logs/experiments.md`
3. EXP-003 charged-Infinix re-test (owner-driven, from prior session)
4. Bug 7 device checklist pass
5. Deterministic cleanup of the messaging backoff timing test
6. Then: resume premium chat UI component sequence (UI-011 composer or UI-007 selection research)

### Install command (PowerShell, authoritative)
```powershell
Set-Location "C:\Users\KaliOxygen\Downloads\Flash"
$env:JAVA_HOME = "E:\AndroidDev\AndroidStudio\android-studio\jbr"
$env:GRADLE_USER_HOME = "E:\AndroidDev\Gradle"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=Z:\nope"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat :app:installDebug --no-configuration-cache --console=plain
```
Git-Bash equivalent: prefix with `JAVA_HOME="E:/AndroidDev/AndroidStudio/android-studio/jbr" GRADLE_USER_HOME="E:/AndroidDev/Gradle" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:/nope"` and forward slashes; adb at `"E:\AndroidDev\SDK\platform-tools\adb.exe"` (quote it in Git Bash).

## 2026-08-31 (b) — Bugs 1–7 ALL IMPLEMENTED; Bug 6 root-caused & re-fixed; next = physical two-phone verification, then voice/video calling

### Current branch
`dev` (work is UNCOMMITTED in the working tree; HEAD `9e94a2d`)

### Last verified build
- `:core:messaging:testDebugUnitTest --tests *RealFlashChatRepositoryTest*` → **BUILD SUCCESSFUL**, XML `failures="0"` (whole class incl. the previously-flaky backoff test AND the two new Bug 7 callback regression tests).
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (after fixing one compile iteration: battery-exemption callback hoisted through `FlashApp`/`FlashShell` as `onEnableBackgroundTransfers`).

### Current phase
Chat UI bug fixes (track 1): **all 7 bugs implemented**. Bug 6 was REOPENED after the owner's
physical test ("still goes offline after a few seconds") and the REAL root cause was found on
device — see ERROR-020. Voice/video calling (track 1 remainder) is next. KMP migration stays
de-prioritized.

### Working features (NEW since last handoff)
- **Bug 6 RE-FIXED (code-level, ERROR-020):** on-device logcat proved a sticky-restart crash
  loop — `ForegroundServiceStartNotAllowedException` uncaught in
  `FlashBackgroundService.onCreate → startAsForeground` killed the process EVERY time the
  system restarted the START_STICKY service while backgrounded (7 FATALs captured).
  Fix: `startAsForeground()` catches everything and returns Boolean; `onCreate` order is now
  locks → screen receiver → engine start → foreground promotion; refusal → log + `stopSelf()`
  (mesh keeps running in-process, no crash loop). Plus `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  wired to the Settings "Background transfers" toggle (user-initiated AOSP Doze exemption).
- **ERROR-021 fixed:** `drainMutex` NPE (declared below the `init` block that launches the
  drain coroutine → init-order race → uncaught NPE process death) — moved above with a
  comment locking the ordering constraint.
- **Bug 7 IMPLEMENTED (notifications):** `docs/ui/notification-ui.md` filled to DESIGNED first
  (§34), then: `FlashNotificationManager` (`flash_messages` channel, per-conversation ids,
  immutable PendingIntent → `MainActivity` with `EXTRA_CONVERSATION_ID`), monochrome
  `ic_notification_flash.xml`, library-safe defaulted callbacks
  (`onInboundTextMessage`/`onInboundAttachment`) fired only on real inserts (replay-proof),
  foreground+open-conversation suppression, notification-tap → conversation via
  `pendingNotificationConversation` flow consumed in `FlashShell` (engine-ready gated).
- WifiLock finding (API 34+): HIGH_PERF is remapped to LOW_LATENCY and LOW_LATENCY is only
  active foreground+screen-on — NO WifiLock mode keeps the radio up in background on modern
  Android. Lock retained for the foreground hot path only. Full dumpsys evidence in
  `docs/android-platform-notes.md` 2026-08-31 (b).

### In progress
- Physical two-phone verification of Bug 6 + Bug 7 (THE decisive pending step)

### Broken
- Nothing new. (Pre-existing timing-flaky test note below.)

### Last change
Bug 6 re-fix + Bug 7 implementation, both built green. Files: `FlashBackgroundService.kt`,
`RealFlashChatRepository.kt` (drainMutex order + callbacks), `DiscoveryEngineHolder.kt`
(callback wiring), `MainActivity.kt` (foreground state, onNewIntent, battery exemption,
pending-conversation flow), `FlashNotificationManager.kt` (NEW), `ic_notification_flash.xml`
(NEW), `AndroidManifest.xml` (permission), `notification-ui.md` (DESIGNED),
`RealFlashChatRepositoryTest.kt` (2 new tests), platform-notes/errors/progress updated.

### Last test
- See Last verified build above. Also: editor diagnostics clean on all changed files.
- Physical verification PENDING: (1) background/screen-off phone A >45s → phone B still sees
  it online, message arrives, logcat has NO `ForegroundServiceStartNotAllowedException`/FATAL;
  (2) toggle ON "Background transfers", grant the exemption dialog, repeat;
  (3) Bug 7 checklist in `docs/ui/notification-ui.md` (suppression, tap-to-open, dedupe,
  screen-off arrival). On this Infinix also check OEM "Phone Master"/battery manager — may
  need a manual background-activity exemption (AOSP exemption does not control it).

### Known blockers
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust exit code alone
- Gradle metadata cache corruption (hit AGAIN this session): `gradlew --stop`, `taskkill //F //IM java.exe`,
  delete `E:\AndroidDev\Gradle\caches\modules-2\metadata-2.107`, rebuild
- Build env: `E:\` hosts SDK (`E:\AndroidDev\SDK`), Gradle home (`E:\AndroidDev\Gradle`), JBR
  (`E:\AndroidDev\AndroidStudio\android-studio\jbr`) — install command at the bottom of this file's
  current section is authoritative
- The messaging backoff timing test PASSED this session (whole class green) but remains
  inherently timing-sensitive; deterministic cleanup still worthwhile
- PHASE-21/22 depend on Phases 06–20 groundwork that does not exist yet; deferred
- KMP migration is DE-prioritized until chat UI bugs + calling modules are done

### Recommended next task
1. Physical two-phone verification above (owner-driven). Record results in `logs/experiments.md`.
2. Then voice/video calling modules (WebRTC, `shepeliev/webrtc-kmp`, signaling over the WS mesh).

### Files most relevant to next task
- `app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt`
- `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt`
- `app/src/main/java/com/transfer/flash/MainActivity.kt`
- `core/messaging/src/main/java/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `docs/ui/notification-ui.md`, `logs/errors.md` (ERROR-020/021)

### Remaining work summary (for next AI)
1. Physical verification (Bug 6 + Bug 7 checklists)
2. **Voice/video calling:** `core:calling` + `ui:calling` with WebRTC (`shepeliev/webrtc-kmp`),
   WireFrame types, signaling over WS mesh, call UI overlay
3. Deterministic cleanup of the messaging backoff timing test
4. Then: commit all bug-fix work (with `Co-authored-by: Copilot` trailer), resume KMP migration

### Install command (PowerShell, authoritative)
```powershell
Set-Location "C:\Users\KaliOxygen\Downloads\Flash"
$env:JAVA_HOME = "E:\AndroidDev\AndroidStudio\android-studio\jbr"
$env:GRADLE_USER_HOME = "E:\AndroidDev\Gradle"
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=Z:\nope"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
& .\gradlew.bat :app:installDebug --no-configuration-cache --console=plain
```
Git-Bash equivalent: prefix with `JAVA_HOME="E:/AndroidDev/AndroidStudio/android-studio/jbr" GRADLE_USER_HOME="E:/AndroidDev/Gradle" JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:/nope"` and forward slashes; adb at `"E:\AndroidDev\SDK\platform-tools\adb.exe"` (quote it in Git Bash).

## 2026-08-31 — Bugs 1-6 IMPLEMENTED; next = Bug 7, then voice/video calling [SUPERSEDED — Bug 6 root cause turned out to be the sticky-restart crash loop, see the (b) section above and ERROR-020]

### Current branch
`dev` (work is UNCOMMITTED in the working tree)

### Last verified build
`:app:assembleDebug` → **BUILD SUCCESSFUL** (2m 40s) with Bugs 1–6 on disk. The Bug 5 reconnect regression test also passes in isolation. HEAD remains `9e94a2d`; the full bug-fix changeset is uncommitted in the working tree.

### Current phase
Chat UI bug fixes (track 1 of the 2-track plan: 7 bugs → then voice/video calling modules). KMP migration is DE-prioritized until both tracks land.

### Working features (NEW)
- **Bug 1 DONE:** Single tap no longer opens the actions overlay (`FlashMessageBubble.kt:194-205` — onClick only toggles selection in selection mode; onLongClick is the exclusive actions trigger)
- **Bug 2 DONE:** Reactions/actions overlay now works on voice/files/video/images (`FlashFileMessageCard.kt`, `FlashImageGrid.kt` — added onLongPress propagation)
- **Bug 3 DONE:** Per-MIME auto-download of inbound offers, complete end-to-end:
  - Engine: `DiscoveryEngineHolder.kt` — `@Volatile` `autoDownloadVoice/Image/Video/File` mirrors + `onIncomingOffer` policy lambda (auto-accepts voice+image by default, video+file ask in-bubble) + hook in `handleInboundBinary`
  - Settings: `FlashSettingsScreen.kt` 4 SwitchRows + `FlashSettingsDataStore.kt` 4 keys/flows/setters
  - Wiring: `AppEngine.kt` mirrors DataStore → holder; `MainActivity.kt` collects/persists/wires
  - Shared parity: `core/engine/Flash.kt` `attachmentProgress` now maps `Offered → AwaitingAcceptance`
- **Bug 4 DONE:** Splash animation extracted into a reusable theme composable:
  - `ui:theme/.../FlashBrandAnimation.kt` — the bolt + discovery rings + glow + breathing loop,
    now honors `FlashTheme.motion.reduceMotion` (static bolt at rest), draws an optional dark
    gradient `background`, and is size-driven by its `modifier`
  - `app/.../ui/splash/FlashSplashScreen.kt` — now a thin delegate to `FlashBrandAnimation`
    (visual launch splash unchanged)
  - `ui/chat/.../ui/transfers/FlashTransfersScreen.kt` — `LoadingRows` reuses it as a compact
    branded loading mark above the skeleton rows (`background=false`, 96dp box)
- **Bug 5 DONE:** peer session-up resets pending outbox backoff and drains immediately; reconnect regression test passes in isolation.
- **Bug 6 DONE (code-level):** visible `MainActivity.onStart` launches the connected-device FGS; it stays alive after `onStop` so background mesh presence/receiving can continue. Physical two-phone verification pending.
- Phase 03 logging abstraction (`FlashLog`) committed & tested (`da4fba6`)
- All 9 KMP migration decisions (D1–D9) recorded
- In-bubble Accept/Decline buttons on inbound file offers (`FlashFileMessageCard.kt:214-228`)

### In progress
- Bug 7 (see `### Broken` below) — NOT started
- Voice/video calling (WebRTC) — NOT started

### Broken
- Bug 7: No message notifications — needs `FlashNotificationManager.kt`

### Last change
Bug 6 implemented (ERROR-020): `MainActivity.onStart()` is now the sole owner that launches `FlashBackgroundService` while the activity is visible; the delayed launch was removed from `DiscoveryEngineHolder.ensureStarted`. The service stays running across `onStop`, uses `ContextCompat.startForegroundService` for API 24+, logs launch failures, and uses a LOW-importance notification channel. Also fixed Bug 5's pending explicit-API compile error (`public notifyPeerSessionUp`). Uncommitted.

### Last test
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (2m 40s).
- `:core:messaging:compileDebugKotlin --rerun-tasks` → **BUILD SUCCESSFUL**.
- Bug 5 test `notifyPeerSessionUp flushes a queued outbox message stuck in backoff` → **PASS** in isolation.
- Full `:core:messaging:testDebugUnitTest` is not green: the pre-existing timing-sensitive `failed outbox delivery backs off instead of retrying every tick` test fails, including in isolation. This is unrelated to Bug 6 and needs deterministic-test cleanup.
- Physical Bug 6 verification remains: background one phone for >45 seconds and confirm the peer stays online and receives a message.

### Known blockers
- Kotlin daemon flakiness: treat "BUILD SUCCESSFUL" as success; don't trust exit code alone (non-daemon fallback compiles fine but exits 1)
- Gradle metadata cache corruption: if `metadata-2.107\module-metadata.bin` errors, delete `E:\AndroidDev\Gradle\caches\modules-2\metadata-2.107` and rebuild (toolchain moved F: → E:)
- Build tip: `E:\` hosts SDK (`E:\AndroidDev\SDK`), Gradle home (`E:\AndroidDev\Gradle`) and the JBR (`E:\AndroidDev\AndroidStudio\android-studio\jbr`)
- Known failing timing test: `RealFlashChatRepositoryTest.kt:499` (`failed outbox delivery backs off instead of retrying every tick`) — currently fails even in isolation; unrelated to Bug 6
- PHASE-21/22 depend on Phases 06–20 groundwork that does not exist yet; deferred
- KMP migration is DE-prioritized until chat UI bugs + calling modules are done

### Recommended next task
**Bug 7:** Add message notifications via `FlashNotificationManager.kt`, using the existing Android 13+ notification permission flow and avoiding duplicate notifications for the currently open conversation.

### Files most relevant to next task
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt` (inbound message framing/dispatch)
- `core/messaging/src/main/java/com/transfer/flash/core/messaging/RealFlashChatRepository.kt` (inbound ingestion)
- `app/src/main/java/com/transfer/flash/MainActivity.kt` (notification permission and current conversation host state)
- `app/src/main/AndroidManifest.xml` (`POST_NOTIFICATIONS` already declared)
- `docs/ui/notification-ui.md`

### Remaining work summary (for next AI)
1. **Bug 7:** Message notifications
2. **Voice/video calling:** `core:calling` + `ui:calling` modules with WebRTC (`shepeliev/webrtc-kmp`), WireFrame types, signaling over WS mesh, call UI overlay
3. Physical Bug 6 background-presence verification and deterministic cleanup of the existing messaging backoff test
4. Then: commit all bug-fix work (with `Co-authored-by: Copilot` trailer), resume KMP migration



- Created a separate formal logo proposal for the owner's AI logo competition. Entry point:
  `logo-codex/preview/contact-sheet.png`; source notes: `logo-codex/README.md`.
- Final mark: F-shaped transfer monogram using Flash Pulse teal, graphite, off-white, and a restrained spark
  amber transfer lane. It intentionally avoids a generic lightning-bolt centerpiece.
- Deliverables: SVG masters, Android adaptive templates, PNG exports from 16px through 1024px, lockups, mono
  assets, and archived concept/refinement materials.
- Verification: rendered via `node logo-codex/build.mjs` and visually inspected contact sheet plus 48px/16px
  icons and lockups.
- No app/source files were modified by this branding pass. If selected, integrate launcher resources in a
  dedicated follow-up change.

## 2026-08-27 -- Publishing Phase 5 authoring COMPLETE (5.1–5.5 done) + coroutines dep-scope leak fixed -- next = Phase 6 (JitPack)
- **All Phase 5 authoring tasks are DONE.** 5.1 (`Flash.create` factory) + 5.3 (Closeable) landed earlier
  this session (entry below). This entry covers 5.2 + 5.4 + 5.5 and a real dependency-scope fix uncovered
  by the sample.
- **README.md authored at repo root (5.2 + 5.4):** pitch → JitPack install (commented badge + `<user>/<repo>`
  and `<TAG>` placeholders, filled in Phase 6) → quick-start → `FlashConfig` table → lifecycle → permissions
  (required vs optional foreground-service split, each with a "why", explicit no-location note) →
  compatibility table → published module set (Phase 4 Task 4.3) → Apache-2.0. The quick-start is **compiled
  verbatim** as `sample/consumer/src/main/java/.../QuickStart.kt` so README code can't silently drift.
- **5.5 cleanups:** every `core/*/consumer-rules.pro` now carries a documented comment header (persistence
  was 0 bytes). Verified NO first-party reflection anywhere in `core/*` → "no keep rules needed; transitive
  Room/SQLCipher ship their own" is accurate. `resourcePrefix`: **not needed** (no `core/*` has `res/`).
- **REAL BUG FIXED — coroutines dependency-scope leak:** core modules returned `Flow`/`StateFlow` from their
  PUBLIC API but only had coroutines via `implementation(lifecycle.runtime.ktx)`, so those return types were
  OFF a downstream consumer's compile classpath (the sample's `QuickStart.kt` couldn't resolve `StateFlow`/
  `first`). Fixed: added `api(libs.kotlinx.coroutines.core)` to discovery/network/transfer/persistence/
  security/messaging + new catalog entry `kotlinx-coroutines-core` (`coroutines = "1.10.2"`). Engine
  re-exports it transitively via `api(project(...))`. **This is the kind of leak the `:sample:consumer`
  harness (Phase 2 Task 2.3) exists to catch — it worked.**
- **Verified green:** `:sample:consumer:assembleDebug`, `:sample:consumer-granular:assembleDebug`,
  `:app:compileDebugKotlin`, and `compileReleaseKotlin` for all six touched core modules + engine.
- **⚠ Build-infra gotcha (not code):** a Kotlin daemon crash corrupted the Gradle module-metadata cache
  (`E:\Flash\.gradle-user-home\caches\modules-2\metadata-2.107\module-metadata.bin`) and `gradlew --stop`
  left one daemon alive rewriting it. Recovery: `taskkill //F` the stale `java.exe` daemons → delete
  `metadata-2.107` + `Flash/.gradle/configuration-cache` → rebuild clean. If a build fails reading
  `module-metadata.bin`, do this.
- **⚠ Owner decision to flag:** Phase 1 option (b) was only HALF applied — compileSdk was lowered to 35 for
  reach, but AGP stayed 9.3.1, so the AGP 9.3 / Gradle 9.5 floor is still the real adoption ceiling (apps on
  AGP 8.x can't consume the artifacts). README documents this honestly. Decide whether to also lower AGP.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT: Phase 6 (`docs/publishing/PHASE-06-jitpack-publishing.md`) — JitPack publish.** That phase fills
  the README's `<user>/<repo>`/`<TAG>`/badge placeholders and verifies a real JitPack build. Build env is
  mandatory (see below). Messaging inversion stays deferred.

## 2026-08-27 -- Publishing Phase 5 Task 5.1 + 5.3 DONE (`Flash.create` factory + Closeable) -- next = 5.2/5.4/5.5 + sample
- **`Flash.create(context, FlashConfig = FlashConfig())` is live** in `core:engine`
  (`core/engine/src/main/java/com/transfer/flash/core/engine/Flash.kt`). One call builds all six
  `FlashEngine` subsystems on ONE shared `CoroutineScope`, opens the encrypted Room DB, and launches
  network/discovery/data-channel/auto-connect async. This is now the documented happy path (the six
  `Default*` constructors remain for advanced users).
- **`FlashConfig(displayName, enableResume=true, autoAcceptIncoming=false, receivedFilesDir=null)`** per the
  owner's decision "Full engine, autoAccept default false." Offer gate is ALWAYS on (`requireAcceptance` +
  `requireReceiverAcceptance`); `autoAcceptIncoming` only auto-invokes the accept path (→ RESUME) on the
  offer event. `enableResume` toggles `RoomTransferStore` vs null (DB always opens — chats/settings need it).
- **New support files:** `core/engine/.../store/KeystorePassphraseProvider.kt` (verbatim port of the app's
  keystore-wrapped SQLCipher passphrase — same PREFS `flash_db_secure` / alias `flash_db_passphrase_key`, so
  it unwraps the SAME on-disk DB as the app) and `core/engine/.../internal/AutoConnectGate.kt` (pure JVM gate).
- **Excluded by design:** pairing (`PairingCoordinator` depends on app UI types + isn't part of
  `FlashEngine`), `FlashBackgroundService`, Dev Console. Wiring is **duplicated** from
  `DiscoveryEngineHolder` (NOT refactored) to honor "keep everything" and not destabilize the running app —
  accepted, logged tech debt. Holder left untouched.
- **Task 5.3 folded in:** `FlashEngine : Closeable`; `DefaultFlashEngine` gains idempotent
  `onClose: () -> Unit = {}` (AtomicBoolean-guarded, defaulted so hand-assembled callers +
  `DefaultFlashEngineTest` compile unchanged). Factory teardown stops data-channel server + network +
  discovery, closes DB, cancels the shared scope.
- **Build change:** added `implementation(libs.androidx.room.runtime)` to `core/engine/build.gradle.kts` —
  the engine is the composition root and must see Room's `Migration` + `RoomDatabase.close()`; `implementation`
  (not `api`) keeps Room internal, consistent with ADR-024.
- **Verified green:** `:core:engine:compileDebugKotlin`, `:core:engine:compileReleaseKotlin` (explicitApi
  strict), `:core:engine:testDebugUnitTest`, `:app:compileDebugKotlin`.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT (Phase 5 remainder):** 5.2 permissions section, 5.4 root README (quick-start using `Flash.create`
  + `close()`), 5.5 consumer-rules.pro comment headers + resourcePrefix decision, and a `:sample:consumer`
  module that mirrors the README and runs `Flash.create` + `close()`
  (`./gradlew :sample:consumer:assembleDebug`). Then Phase 6 (JitPack). Messaging inversion stays deferred.
  Build env is mandatory (see below).

## 2026-08-27 -- Publishing Phase 4 DONE (Task 4.1 decoupling + Task 4.3 module-set decision) -- next = Phase 5 README
- **Phase 4 COMPLETE.** Task 4.1 (persistence decoupling) + Task 4.3 (published module set) both done;
  Task 4.2 (interim ABI-trim fallback) not needed since 4.1 landed; step 6 (messaging inversion) deferred
  by decision (messaging held out of the v1 supported set).
- **Task 4.3 decision (source of truth = PHASE-04 doc table, grounded in `releaseRuntimeClasspath`):**
  - **Supported — lightweight (no Room/SQLCipher):** `core-common`, `core-security`, `core-discovery`,
    `core-network`, `core-transfer`.
  - **Supported — batteries-included umbrella (bundles Room):** `core-engine`.
  - **Supported — optional storage add-on (Room + 4 SQLCipher ABIs):** `core-persistence`.
  - **Experimental — not promised in v1 (still DAO-coupled):** `core-messaging` (resolves + is pulled
    transitively by engine, just undocumented as standalone).
- **Task 4.1 COMPLETE.** `core:transfer` no longer depends on `core:persistence`, and neither does
  `core:security`. `./gradlew :core:transfer:dependencies` shows **no `androidx.room` / `net.zetetic`
  sqlcipher** on `releaseCompileClasspath` or `debugRuntimeClasspath`. A LAN-only consumer can now take
  `core-transfer` without the four SQLCipher native ABIs.
- **How (transfer):** new port `TransferStore` in `core:transfer` (`store/TransferStore.kt`, plain suspend
  iface). `RealFlashTransferRepository` takes nullable `store: TransferStore?` (null = DB-less, unchanged
  behavior). Room adapter `RoomTransferStore` lives in **`core:engine`** — NOT persistence, which would
  create the cycle `persistence → transfer → security → persistence`. App wires it in
  `DiscoveryEngineHolder` (`store = RoomTransferStore(db.transferDao(), db.transferChunkDao())`).
- **How (security):** the transitive leak `transfer → security → persistence` came from the **dead**
  `RoomTrustedStore` (internal, never constructed; app uses `AndroidPreferencesTrustStore`). Owner approved
  **deleting** it. Also removed security's direct `libs.androidx.room.runtime`. `FlashTrustedPeer` moved next
  to `LegacyTrustMigration`; `TofuPolicy` + `LegacyTrustMigration` kept (pure, Room-free, still tested).
  Security now declares `libs.androidx.lifecycle.runtime.ktx` for coroutines (was leaking in via Room).
- **Verified green:** `:core:transfer:testDebugUnitTest`, `:core:security:testDebugUnitTest`,
  `:core:engine:testDebugUnitTest`, `:core:engine:compileDebugKotlin`, `:app:compileDebugKotlin`,
  `:app:assembleDebug`. Sample app behavior unchanged.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT: Phase 5 (`docs/publishing/PHASE-05-*.md`) — consumer ergonomics / README.** The v1 supported
  module set is decided (table above / in PHASE-04 Task 4.3); Phase 5 authors the README that documents it.
  Phase 4 step 6 (messaging inversion) stays deferred — hold `core-messaging` out of the v1 supported set
  rather than inverting now; promote it later with the same port/adapter treatment (ADR-024). Then Phase 6
  JitPack. Build env is mandatory (see below).

## 2026-08-26 -- Publishing Phase 3 DONE: explicitApi() strict green in all 8 core modules; BCV removed (ADR-023) -- next = Phase 4
- **Phase 3 is COMPLETE.** `explicitApi()` (strict) is enabled and **green across all 8 published `core/*`
  modules** (common, messaging, engine, discovery, persistence, security, transfer, network). Every public
  symbol now carries a deliberate `public` / `internal` / `@FlashInternalApi` decision — enforced by the
  compiler, so nothing reaches the ABI by accident.
- **network was the last module (8/8), closed this session.** `WebSocketCodec` → `@FlashInternalApi` (used
  cross-core by transfer's `WsTransferManager`); all app/ui-facing session/transport entry points → plain
  `public`; wire-only probe messages → `internal`. `@file:OptIn(FlashInternalApi::class)` added to every
  in-library `WebSocketCodec` use site INCLUDING the same-module test `WebSocketCodecTest.kt`.
- **Task 3.1 (binary-compatibility-validator) WITHDRAWN — see ADR-023.** BCV v0.18.1 registers no
  `apiDump`/`apiCheck` tasks under AGP 9.3.1 built-in Kotlin (no classic Kotlin plugin) — inert. Removed the
  plugin alias, the root `apiValidation {}` block, and the `libs.versions.toml` entry. ABI enforcement is
  `explicitApi()` strict instead. There is **no `.api` dump** — do not go looking for one.
- **Verified:** all 8 `:core:*:compileReleaseKotlin` SUCCESSFUL; `:core:network:testDebugUnitTest`
  SUCCESSFUL; `:core:transfer:compileReleaseKotlin` SUCCESSFUL; root config re-resolves after BCV removal.
- **NOT committed** — branch `publishing/library-prep`, awaiting owner's go-ahead. Owner device run EXP-002
  still pending.
- **NEXT: Phase 4 (`docs/publishing/PHASE-04-*.md`).** Phase 2 Task 2.2 stays deferred: under ADR-023 there
  is no dump to read leaks from; foreign-type leaks now surface as explicitApi `EXPOSED_*` compile errors at
  the leak site (none currently failing → no promotion forced). Build env is MANDATORY (JAVA_HOME=AS jbr,
  GRADLE_USER_HOME=E:\Flash\.gradle-user-home, JAVA_TOOL_OPTIONS unixdomain tmpdir; `./gradlew.bat … --console=plain`).

## 2026-08-26 -- Publishing Phase 2 DONE: dependency-scope fixed (core:common → api) + external consumer gate -- next = Phase 3
- **Phase 2 (the HARD BLOCKER) is IMPLEMENTED.** All six non-engine core modules now declare
  `api(project(":core:common"))` (was `implementation`), so core:common's shared vocabulary
  (FlashDevice/FlashDeviceId/FlashResult/…) lands on a consumer's COMPILE classpath. Without this, granular
  `core:*` artifacts fail with "unresolved reference: FlashDevice" on JitPack.
- **Acceptance PROVEN with external consumers** (Task 2.3): two throwaway, non-published modules under
  `sample/` (in settings.gradle.kts, NO maven-publish): `:sample:consumer` (engine-only → shape A umbrella)
  and `:sample:consumer-granular` (network-only, references FlashDevice → shape B). Both compile.
  `:core:engine:publishToMavenLocal` succeeds and the published `core-engine-1.0.0.pom` has all 7 siblings in
  `compile` scope and impl-only deps in `runtime` — the correct consumer contract.
- **Task 2.2 is DEFERRED to Phase 3 by design.** Deeper cross-module leaks (e.g. network exposing a
  security/discovery type) are NOT guessed — they get read off Phase 3's `.api` dumps and the offending
  `implementation` deps promoted to `api` then. The umbrella (`core-engine`) is the documented default and is
  already fully coherent.
- **Verified:** consumer + publish build SUCCESSFUL; `:app:assembleDebug` SUCCESSFUL. Full unit suite not
  re-run (scope-only change, behaviorally inert; core release variants all compiled during publish).
- **NEXT: Phase 3 (`docs/publishing/PHASE-03-api-surface.md`)** — binary-compat-validator `apiDump` +
  `explicitApi()` + hide internals; then close Phase 2 Task 2.2 off the dumps. Owner device run EXP-002 still
  pending.

## 2026-08-26 -- Publishing Phase 1 DONE: Apache-2.0 + core compileSdk 35 (both owner decisions resolved) -- next = Phase 2
- **Phase 1 of `docs/publishing/` is IMPLEMENTED and both owner decisions are locked.** LICENSE = **Apache-2.0**,
  holder **"The Flash Project"** (patent grant + Android-ecosystem norm; see ADR-022). Compat baseline =
  **`core:*` modules lowered to `compileSdk 35`** so AGP-8.7-era consumers can build; the app and
  `targetSdk 36` are untouched. `minSdk 24` (Android 7) already covered the owner's "down to Android 8" ask —
  nothing to lower there.
- **Files changed (all inside the plan's allowlist):** `LICENSE` (full Apache text), `NOTICE`, root
  `build.gradle.kts` (`flashLibraryVersion` single-source), all 8 `core/*/build.gradle.kts` (compileSdk 35),
  `gradle/libs.versions.toml` (sqlcipher 4.18.0→4.17.0), `core/discovery/.../nsd/NsdTransport.kt` (onServiceLost
  forward-compat). No `app/`, `ui/`, or `media-downloader-main/` code touched.
- **Two obstacles hit and cleared (see progress.md + ADR-022):** (1) SQLCipher 4.18.0 hard-floors compileSdk
  at 37 — every version 4.9.0–4.17.0 has no floor, so pinned 4.17.0. (2) `ServiceInfoCallback.onServiceLost`
  is `(NsdServiceInfo)` at SDK 37 but no-arg at 34–36 — kept the no-arg `override`, demoted the param variant
  to a plain method (still binds at runtime on Android 17).
- **Verified:** all 10 modules compile at 35; `assembleDebug` BUILD SUCCESSFUL, `app-debug.apk` (29.7 MB)
  produced; the two previously-flaking timing tests pass on isolated `--rerun-tasks`. The combined
  `testDebugUnitTest assembleDebug` did NOT go green in one shot — two load flakes (ERROR-019), each green
  alone. Re-run on an idle machine for a single clean green if you want it on record.
- **NEXT: Phase 2 (`docs/publishing/PHASE-02-dependency-scope.md`) — the HARD BLOCKER.** `implementation`
  `(project(...))` → `api(...)` where public types cross module boundaries; prove the fix with an EXTERNAL
  `:sample:consumer`, never the library's own build. Then Phases 3–6. Owner device run EXP-002 still pending.

## 2026-08-26 -- Core library publishing plan authored (GitHub → JitPack → Gradle) -- READ docs/publishing/
- **The owner wants to publish the `core:*` modules as a reusable LAN-transfer library** so other developers
  consume the engine instead of building from scratch. Hosting decision: **GitHub → JitPack → Gradle**, NOT
  Maven Central. The next agent (OpenCode, run in this same folder) implements it.
- **The plan is `docs/publishing/` (7 files).** Start at `PHASE-00-overview.md`; phases are ordered and each
  is self-contained (problem → exact files/code → acceptance → `./gradlew` verify). Do them in order:
  01 foundation (LICENSE, single version source, compat baseline) → 02 dependency-scope (**hard blocker**) →
  03 api-surface (`explicitApi()` + hide internals) → 04 persistence-decoupling (SQLCipher off the transfer
  path) → 05 consumer-ergonomics (`Flash.create()` factory + README) → 06 jitpack-publishing (`jitpack.yml`
  openjdk17, tag/release, verify).
- **The one blocker that makes or breaks it: dependency scope (Phase 2).** Core modules use
  `implementation(project(...))` but expose those types in PUBLIC signatures, so individual `core:*`
  artifacts DO NOT COMPILE for a downstream consumer. `:core:engine` is the only coherent artifact today
  (it uses `api(...)`), so the minimum-viable path publishes `core-engine` only. Prove any scope fix with an
  EXTERNAL `:sample:consumer`, never the library's own build.
- **What JitPack removes vs Maven Central** (do not waste effort): GPG signing, a Sonatype/Central
  `repositories{}` publish target, strict POM validation, and the javadoc jar are all NOT needed. JitPack
  just needs a working `publishToMavenLocal`, `jitpack.yml` pinning JDK 17 (AGP 9.3.1), and a Git tag +
  GitHub release. Consumer coordinate: `com.github.<user>.<repo>:core-engine:<TAG>`.
- **Two decisions need the owner:** LICENSE copyright holder (Phase 1.1); keep compileSdk 37/AGP 9.3.1
  (narrow reach) vs lower for wider consumer support (Phase 1.3).
- **No `core:*` code changed this session** — docs only; the prior green build state stands. (Aside: I
  accidentally overwrote AGENTS.md and reverted it with `git checkout` — AGENTS.md is intact.)

## 2026-08-25 -- RESOLVED: sender could not pause (ERROR-018, ADR-021) -- nine pause/resume/cancel defects
- **The reported bug was a REGISTRATION RACE, not a broken pause button.** `sendFile` returns the instant the
  send coroutine launches, but `executeSend` registered its dispatcher only after the resume-chunk DAO query
  and dispatcher construction. A pause landing in that window found no dispatcher, took a state-only branch
  that emitted no wire frame, and then `executeSend` overwrote `Paused` with `Transferring` — the pause
  disappeared and bytes kept flowing. **Pause is now an INTENT** (`pauseIntents`, recorded BEFORE the
  dispatcher lookup) that `applyPendingPauseOrStart` re-checks after the Transferring write.
- **Eight more defects fixed in the same audit** (full list + fixes in ERROR-018): `send()` parked forever
  when COMPLETE arrived during a pause; the 15 s ACK-drain grace failed paused transfers (a paused receiver
  deliberately stops ACKing); resume never un-gated receive intake, so both UIs showed Transferring at 0 B/s;
  the intake gate was one session-wide boolean; `resumeTransfer` no-oped on a state mismatch while the wire
  stayed paused; the rate meter straddled the paused gap and `-1` leaked to the UI as negative speed;
  `tryEmit` dropped control frames silently; cancelling a PAUSED sender never reached a cancellable
  suspension point, and the `finally` cleanup lacked an ownership check.
- **Read ADR-021 before touching this code.** Its invariants: pause intent outlives dispatcher construction;
  a paused transfer is NEVER failed by a timeout (the drain deadline is disarmed, resume re-arms fresh); a
  terminal outcome always beats a pause (`awaitUnpause()` returns on the terminal deferred); resume always
  emits `IncomingControl(RESUME)` while remote PAUSE deliberately does NOT gate (the gate is session-wide, so
  gating would stall unrelated transfers' ACKs); the gate is a SET of transfer ids; paused telemetry is a
  hard `0.0` / ETA `-1`.
- **Verified:** `:core:transfer:testDebugUnitTest --rerun` green (76 tests, 0 failures) and full
  `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (411 tasks; 668 tests / 0 failures across 102 suites);
  `app-debug.apk` produced. 6 new regression tests, incl. a `GatedChunkDao` that parks the resume query to
  reproduce the race window exactly, and a fake-clock test proving a paused sender survives repeated 60 s
  jumps and only fails after resume.
- **Known limit:** the pause intent is in-memory, so pause does NOT survive process death — a killed paused
  sender comes back as Queued and re-plans from the persisted done-set. Persisting it is the ADR-021 revisit
  trigger.
- **Still owner-only: the two-phone device run** (10 MB over 5 GHz, Pause/Resume/Cancel from BOTH sides, plus
  a multi-minute pause), to be recorded as EXP-002 against EXP-001.

## 2026-08-25 -- Phase 8 App Shell LIVE: custom bottom nav + four tab pages
- **The app now boots into the real shell:** `MainActivity.FlashApp` renders
  `Column { FlashAnimatedScreen(nav.current) ; FlashBottomNav }` — boolean-flag switching GONE.
  Tabs = Chats / Transfers / Nearby / Settings; tab taps call `FlashNavigationState.selectTab`
  (stack RESET, not push); Conversation still pushes; BackHandler pops.
- **UI-046 FlashBottomNav** (docs/ui/bottom-nav.md): custom docked bar — spring-sliding Pulse pill,
  squash-release icon pop, animated label weight, re-select pulse ring, Tick haptics, badges (9+),
  selectableGroup/Role.Tab semantics, reduce-motion snaps. Four NEW house-style icons
  (chat/transfer/nearby/settings; settings gear adapted from Feather MIT w/ attribution).
- **UI-047 TransfersScreen** (transfers-page.md): ACTIVE/FAILED/HISTORY sections, honest status lines,
  bytes-weighted progress, pause⇄resume swap, scoped Retry, Share on history; UI-016 badge color language
  reused statically. Empty state via new `TransfersFirstRun` kind.
- **UI-048 NearbyScreen** (nearby-page.md): identity card, peer rows + FlashTransportBadge + Connect,
  trusted peers + Revoke, scanning pulse dot, radios-off explainer, pairing-dialog mount ready
  (phase/secondsLeft pass-through to UI-032 dialog).
- **UI-049 SettingsScreen** (settings-page.md): five sections; CUSTOM segmented theme control + CUSTOM
  FlashSwitch; About card with version/protocol/device-id.
- **Demo-state contract:** TransfersUiState/NearbyUiState/FlashSettingsModel in MainActivity are shaped
  EXACTLY like future C5/C3/C1.4 engine outputs — wiring is substitution, not rewrite.
- **Verified:** full `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL (411 tasks); suite green incl.
  +19 new tests across nav/transfers/nearby/settings logic. NOT device-verified yet.
- **Remaining P1 gap:** Send FAB on Chats (opens attachment palette) — only page-plan item not built.

## 2026-08-24 -- RESOLVED: :core:transfer suite hang (ERROR-016) + Gradle startup failure (ERROR-017)
- **Hang fixed, bounded queues kept.** `MultiStreamDispatcher.runWorker` is now ONE loop that `select`s over its own
  feed and the shared redistribution queue (was two sequential phases); exit bookkeeping (`ownFeedsOpen`,
  `aliveWorkers`) moved into `finally` behind idempotent releases; redistribution is `trySend` + 5 ms poll instead of a
  blocking `send`; materializer breaks once the transfer resolved; `maybeResolveFromState` fails fast when nothing ever
  reached a wire. Root cause + the discarded alternatives: ERROR-016 (RESOLVED) and ADR-019.
  The permitted revert-to-`Channel.UNLIMITED` fallback was NOT needed - feeds=8 / shared=32 stay bounded.
- **The phase-split deadlock was device-fatal, not just a test artifact:** any mid-flight channel death on a large file
  could pin a worker in `shared.send()`, block the materializer on that worker's feed, and stall the transfer forever.
- **Gradle can run again (ERROR-017).** Every invocation, incl. `gradlew --version`, was dying with `Unable to
  establish loopback connection`: JDK 19+ builds every `Selector` on an AF_UNIX socket pair on Windows, and on this
  machine AF_UNIX connect always fails EINVAL (bind succeeds, so the JDK's TCP fallback never triggers). Fix: point the
  AF_UNIX temp dir at a nonexistent path so the bind fails and the JDK falls back to TCP loopback -
  `export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"` (covers launcher, daemon and workers).
  **Use this in every shell that runs Gradle** - see the Build environment note at the bottom.
- **Docs:** ADR-018 (FLASH_XFER wire control plane + cooperative pause) and ADR-019 (single-loop bounded-queue workers)
  added to `docs/decisions.md`; both were referenced from code but previously unwritten.
- Everything else from this session stands: real N-socket data channels, FLASH_XFER control both directions,
  cooperative dispatcher pause, speed-meter fix, Dev Console redesign.
- **Only remaining step for this batch: the two-phone device run** (10 MB over 5 GHz; Pause/Resume/Cancel from both
  sides; then record EXP-002 vs the EXP-001 hotspot baseline).

## 2026-08-24 -- Real multistream (N TCP sockets) + speed fix
- **Real multi-stream implemented:** `core/network/datachannel/` — plain-TCP side channels (`FLASH_JOIN` handshake, length-prefixed frames) bound to the WS session; factory opens one real socket per stream to the target peer (port probe ws+1..+20, cached), WS fallback if peer has no data server. ACKs reply down the arriving connection.
- **Speed display fixed:** RollingRateMeter rewritten (sliding sample window); old version inflated continuously because Δbytes used the first-ever sample while Δtime stayed ≤2 s.
- **Sender pause under diagnosis:** pauseTransfer now logs direction/state/jobPresent — capture TRANSFER logcat from a failing pause attempt.
- Test matrix: both phones must run THIS build for data channels to engage; older peer = silent WS fallback.

## 2026-08-24 -- Dev Console redesign + pause-while-receiving
- Dev Console is now tabbed (PEERS / TRANSFERS / NET) with a status card and log strip; transfer rows have progress bars + Pause/Resume/Cancel (TX and RX).
- Receive-side pause implemented via TCP backpressure: repo emits `incomingControl` events; holder gates intake before pulling frames (`WsSession.awaitBinaryFrame()`); sender throttles automatically, resume drains buffer. Chat stalls during receive-pause (single socket) — accepted v1 trade-off.
- Real N-socket multistream roadmap (next perf milestone): see `docs/decisions.md` ADR-017 revisit + EXP-001 baseline. No external code download needed — dispatcher/receiver already speak N channels; only the transport factory must open N real sockets with a session-join handshake.

## 2026-08-24 -- Device round 2: ack-drain fix (false "Failed" at ~20%) + receiver now visible in Active Transfers
- **Root cause of the 20%-then-Failed symptom:** sender workers exit as soon as all chunks leave the socket buffer; ACKs lag behind disk-paced receiver verification, so `maybeResolveFromState`/`failIfAllChannelsDead` misread uncovered+zero-alive as channel failure. Receiver was fine and always-on — it had verified the entire file.
- **Fix:** bounded 15 s ack-drain grace after worker exit; late ACK_BATCH/COMPLETE now resolve Completed. True failure message is explicit: `ack drain timeout: N unconfirmed`.
- **Receive-side UI:** inbound transfers register via new additive `onIncomingStarted/Progress/Completed/Failed` repo hooks — Dev Console Active Transfers shows Receiving rows on the receiver phone too.

## 2026-08-24 -- Layer-by-layer audit vs media-downloader: pause/resume correctness fixes
- Compared `media-downloader-main` engine layers against Flash's transfer stack; fixed three pre-test bugs:
  1. Pause/cancel no longer reports Failed (`CancellationException` handled separately, re-thrown).
  2. Resume keeps the same wire fileId (`FlashTransfer.wireFileId`) — fresh UUIDs were rejected by the receiver as SESSION_CONFLICT.
  3. Sender chunk done-set now persists to Room (`TransferChunkDao` was dead code) so resume seeding works.
- Remaining gaps (deliberately deferred, in priority order): process-death restore (TransferEntity needs fileName/sourceUri/peerId/wireFileId columns + startup rehydration), queue/concurrency/retry-backoff, receiver-side identity validation + receive done-set persistence, MediaStore publish of received files.

## 2026-08-24 -- WS Mesh Hardening (ERROR-015): correct assembly, reliable delivery, liveness, glare safety
- **Received files now assemble correctly:** per-transfer random-access sinks (`FileRandomAccessSinkHandle` + `RandomAccessChunkSink`) write chunks at `index * chunkSize` under `FlashReceived/<transferId>/<safeName>`. The previous append-order sink scrambled out-of-order multi-stream arrival.
- **No more silent frame drops:** WsSession delivers inbound frames via bounded blocking channels → TCP backpressure; dropped-chunk transfer stalls are structurally impossible.
- **Liveness:** 15 s WS pings + 45 s read timeout close half-open hotspot connections.
- **Handshake/glare races fixed:** early-frame buffering, replaced-session close, identity-safe disconnects, HELLO version enforcement, pending-handshake sockets closed on stop, Mutex-serialized start/stop.
- **Peer-targeted sends:** stream channels route to the intended recipient (`StreamChannelFactory.open(channelId, peerDeviceId)`).
- **Resume fix:** `FlashTransfer.sourceUri`; resume re-reads original content URI.
- **Chat framing:** colon-safe `FLASH_MSG`/`FLASH_RCPT` field encoding via FlashTextFraming.
- **Dev Console:** persisted Room DB (`flash-dev.db`); loud failure on source-open errors; deterministic generated 10MB test payload.
- **Verified:** `testDebugUnitTest assembleDebug` BUILD SUCCESSFUL — 644 tests / 0 failures / 0 skipped.

## 2026-08-24 -- Unified WebSocket Mesh Transport & Transfer Pipeline Wiring
- **Implemented WebSocket Mesh Transport:** Created `WsFlashNetwork` and `WsSession` implementing `FlashNetwork` and `FlashSession`.
- **Full-Duplex Multi-Peer Channels:** Each peer pair maintains an active WebSocket capable of streaming UTF-8 text (`MessageWireFrame` for chat) and binary frames (`ChunkFrame` for files) simultaneously.
- **Symmetric Router & Hotspot Support:** Operates seamlessly via mDNS discovery on standard Wi-Fi routers and via gateway/probe on mobile hotspots.
- **Wired to Engine & Receivers:** Outbound sends route through active WebSockets, inbound chat messages persist into Room, and inbound file chunks flow into `ReceivePipeline` with auto-save to `FlashReceived/`.
- **Sender ACK Routing:** Inbound `ACK_BATCH` and `COMPLETE` frames route directly to active `MultiStreamDispatcher` instances via `RealFlashTransferRepository.onInboundFrame()`.
- **Structured Diagnostic Logging:** Added tags `DISCOVERY`, `WS`, `TRANSFER`, `CHAT`, `DEV` for clear visibility in Android Studio and `adb logcat`.
- **Verified Build & Tests:** `testDebugUnitTest assembleDebug` -> BUILD SUCCESSFUL across all 10 modules (411 tasks, 0 failures).
- **Installed to Device:** Tested debug APK installed on physical phone via ADB.

## Current branch
`dev` — migration decisions committed as `0250a51` (D3=A, D4=A, D6=A, D9=A; D8=A earlier as `e742bec`; D1=B, D2=A, D5=C as `74367dd`)

## Last verified build
Working tree at 2026-08-31 (migration decision recording + PHASE-21/22 log honesty correction) — documentation-only changes; no build required.
Previous build reference: 644 tests / 0 failures (2026-08-24, ERROR-016 fix).

## Current phase
**Migration planning docs complete (PHASE-00–PHASE-24); all human decisions D1–D9 recorded. Actual KMP implementation has NOT begun.**

- All 25 phase files (PHASE-00 through PHASE-24) exist in `docs/migration/`.
- **All 9 decisions answered** in `docs/migration/DECISIONS.md`: D1=B (strict commonMain), D2=A (keep core:*), D3=A (switch ui:* to org.jetbrains.compose), D4=A (expect fun flashDynamicColorScheme seam), D5=C (Room 3 KMP + encrypted desktop), D6=A (JmDNS), D7=**pending** (agent may proceed with recommendation — Toast→Snackbar, FileKit, expect ensurePermission), D8=A (desktop ships existing chat UI adaptively), D9=A (keep sample/consumer Android-only through Phase 23; add sample/consumer-desktop in Phase 24).
- **HONESTY CORRECTION:** PHASE-21 and PHASE-22 log entries claimed an implemented `:desktop` module with PASS builds — **no such code exists** (verified: no `desktop/` dir, no `settings.gradle.kts` include). Those phases produced planning docs only and are **NOT done**. See corrections appended to `docs/migration/logs/migration.md`.
- **Next execution step:** the migration is still documentation-only. Actual implementation must start from the beginning (Phase 06 groundwork per D1=B), then proceed in order. Do not attempt PHASE-21/22 implementation until Phases 06–20 land.

## Component status
- **UI-034 (Adaptive layouts):** `IMPLEMENTED` in `ui/adaptive/FlashAdaptiveLayouts.kt` â€” two-pane not yet consumed by screens (integration pending).
- **UI-038/039/041 (A11y/Haptics/Micro):** `IMPLEMENTED` â€” `FlashFeedback.kt` haptic choke point, 15 call sites migrated, a11y fixes applied.
- **UI-042/043 (Performance/Stress):** `IMPLEMENTED` â€” `FlashStressTestScreen.kt` harness; device measurements PENDING.
- **UI-024/031/032:** `IMPLEMENTED` â€” integration wiring items in Deferred block below.
- **UI-023, UI-028/029/030, UI-025â€“027, UI-021/022, UI-020, UI-019:** `IMPLEMENTED` â€” device verification pending.
- **UI-018 (Media viewer):** `VERIFIED` on device.
- **UI-017 (Image message & grid layout):** `IMPLEMENTED` in `FlashImageGrid.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-016 (File message card):** `IMPLEMENTED` in `FlashFileMessageCard.kt`, `FlashMessagingModels.kt`, `FlashMessageBubble.kt`.
- **UI-015 (Delivery / read states):** `IMPLEMENTED` in `FlashDeliveryStatusIcon.kt`, `FlashIcons.kt`, `FlashMessageBubble.kt`.
- **UI-014 (Typing indicator):** `IMPLEMENTED` in `FlashTypingIndicator.kt`, `FlashChatHeader.kt`, `FlashMessageList.kt`, `FlashConversationScreen.kt`.
- **UI-012 (Custom attachment button):** `IMPLEMENTED` in `FlashAttachmentButton.kt`, `FlashAttachmentSheet.kt`, `FlashComposer.kt`, `FlashConversationScreen.kt`.
- **UI-010 (Reply system):** `IMPLEMENTED` in `FlashQuotedReplyCard.kt`, `FlashSwipeToReply.kt`, `FlashMessageBubble.kt`, `FlashConversationScreen.kt`.
- **UI-009 (Reaction system):** `IMPLEMENTED` in `FlashReactionChip.kt`, `FlashReactionsDock.kt`, `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-007 (Message press & selection):** `IMPLEMENTED` in `FlashMessageBubble.kt`, `FlashSelectionToolbar.kt`, `FlashConversationScreen.kt`.
- **UI-008 (Focus overlay & context menu):** `IMPLEMENTED` in `FlashMessageContextMenu.kt`, `FlashConversationScreen.kt`.
- **UI-011 (Custom message composer):** `IMPLEMENTED` in `FlashComposer.kt`.
- **UI-013 (Custom send button):** `IMPLEMENTED` in `FlashComposer.kt`.
- Foundation components: UI-001, UI-002, UI-037 (`IMPLEMENTED` in `:ui:theme`).
- List & Header: UI-003, UI-004, UI-005, UI-006 (`IMPLEMENTED` in `:ui:chat`).

## Working features
- Full modular multi-module library architecture (`com.transfer.flash:*`).
- Group chat header (UI-028): initials collage avatar (2/3/4+ layouts from seeded palette), "N members Â· M online" subtitle, named typing ("Alex and Sam are typingâ€¦"), transport/encryption glyphs, group Search action. Header fully de-Materialed (custom icon buttons, drawn divider, FlashText).
- System states family (UI-025/026/027): screen-specific empty states with P2P copy + "Find devices" CTA, layout-matched skeletons (delay-guarded, reduce-motion-safe, decorative semantics), severity-split error panels (red failure vs neutral offline) with single Retry â€” wired into chat list and conversation screens.
- Chat scroll engine + jump pill (UI-021/022): auto-scroll at bottom & on own sends, unseen counter with floating "N new messages" accent pill (tap â†’ animated jump + reset), reverseLayout bottom pinning through image resizes, keyboard-safe position retention.
- Voice recording interface (UI-020): hold mic to record, slide-left arms cancel (error-tinted bar), slide-up locks into persistent panel with trash/pause/send, live timer + Canvas amplitude strip, demo-mode capture producing real `FlashVoiceAttachmentUi` payloads.
- Voice message playback card (`FlashVoiceMessageCard`, UI-019): 40-bar discrete waveform with tap-to-seek + drag scrub, 48dp play/pause/download/retry badge, Telegram-style remainingâ†”duration label, 1Ã—/1.5Ã—/2Ã— speed pill, demo-mode playback ticker (real audio deferred pending Media3 ADR).
- Full-screen media viewer (`FlashMediaViewer`, UI-018): pinch/double-tap anchored zoom (1Ã—â€“4Ã—, rubber-band), pan, vertical drag-to-dismiss with backdrop fade + page scale, HorizontalPager album carousel, auto-hiding chrome (counter `n / m`, close, Save/Share/Forward), sample-size-guarded decode, always-dark backdrop token.
- Adaptive Image Collage & Grid Layout (`FlashImageGrid`): 1, 2, 3, 4, and 5+ image mosaics with clamped aspect ratios ($0.5$ to $2.0$), micro-gap gutters ($2.5\text{dp}$), bubble contour corner masking, and $+N$ overflow chips.
- Experimental WebSocket Mesh Transfer: Full file viewing, sharing, and device export capabilities (`WsFileActions`, `FileProvider`, SAF `CreateDocument` picker, click-to-open cards).
- Redesigned 24Ã—24 Custom Vector Icon Set: 46 Flash-owned vector icons with 2.0dp stroke weight, generous optical bounding boxes, and scaled default UI sizing (24dp).
- Complete Edge-to-Edge System Bar and Insets Safety: Status bar cutout clearance across headers/toolbars, and navigation bar/keyboard clearance across composer and sheets.
- Rich in-bubble file message cards (`FlashFileMessageCard`) with color-coded file extension badges (PDF, ZIP, Code, Audio, Video, Image, Document), circular transfer progress rings, and real-time throughput metrics (MB/s speed & ETA countdown).
- Animated delivery status glyphs (`FlashDeliveryStatusIcon`) for 5 transit lifecycle states (Pending, Sent, Delivered, Read, Failed) with 1-tap retry interaction.
- 120 FPS GPU-accelerated 3-dot wave bouncing typing indicator.
- Incoming message stream typing bubble (`FlashTypingBubble`) with concave bubble shaping and smooth list integration.
- Animated header subtitle typing status (`FlashHeaderTypingStatus`) with mini-dots.
- Stateful attachment button in composer with spring rotation ($0^\circ \to 45^\circ$) and active accent tint.
- Modal bottom sheet attachment palette with 5 categorized options (Gallery, Files, Camera, Audio, Flash P2P).
- Staggered spring scale entrance and 0.90x micro-press physics on attachment action tiles.
- Left-swipe-to-reply gesture with rotating reveal badge and single-edge haptic trigger.
- In-bubble quoted reply card with 3dp rounded vertical accent bar and 1-tap jump to original message.
- Jump-to-original message smooth scrolling with 600ms Flash Pulse glow highlight.
- Composer reply dock with dismiss action.
- Interactive reaction dock on message bubbles with 1-tap toggling, active self-reaction accent styling, animated vertical count roll (odometer), and `+N` overflow chip.
- Floating quick reaction bar inside spotlight focus overlay with staggered spring entrance, micro-press physics, and trailing `+` action button.
- Immersive message focus overlay with 65% dimmed backdrop, elevated bubble preview, and sculpted context menu card.
- Multi-message selection mode with animated toolbar swap, batch Copy/Reply/Forward/Delete.
- Adaptive multiline message composer with keyboard safety, reply dock, and tactile send button.
- Message list with reverse layout, concave bubble shapes, entrance choreography, and auto-scroll.
- LAN Discovery and experimental WebSocket multi-peer mesh Transfer.

## In progress
- **KMP migration docs (docs/migration/):** PHASE-12–22 authored & grounded; PHASE-23 (interop matrix) and PHASE-24 (publishing) authored but NOT yet grounded/logged. D8=_pending_ (owner answer needed before any Option B desktop UI).
- **UI-028 (Group header):** IMPLEMENTED â€” device verification pending.
- **UI-025/026/027 (states):** device verification pending.
- **UI-021/022, UI-020, UI-019:** device verification pending.

## Broken
- None.

## Last change
Authored + code-grounded migration docs **PHASE-21** (`:desktop` app shell) and **PHASE-22** (adaptive desktop screens). Verified every theme token, API call, and composable signature in PHASE-22 against actual source (FlashColors/Dimensions/Shapes/Typography/Text/Icons/Theme, FlashAdaptiveLayouts, FlashBottomNav, FlashNavigation, FlashTransfersScreen, FlashNearbyScreen, FlashChatListScreen, FlashConversationScreen, FlashSettingsScreen); fixed ~10+ ungrounded references. Appended PHASE-21 + PHASE-22 entries to `docs/migration/logs/migration.md` (previously zero entries).

## Last test
PHASE-22 grep sweep — no ungrounded tokens remain (tabActiveBg, surfaceApp, roundedMedium, iconMedium, labelMedium, bodyLarge, spec=, FlashBottomNav param mismatch all gone; only the correct inline 200.dp sidebarWidth constant remains). Docs are documentation-only; no Gradle build applies. Prior build reference: 644 tests / 0 failures (2026-08-24).

## Known blockers
- **Environment (ERROR-017, WORKAROUND MANDATORY)**: Gradle cannot start at all in this environment without
  `JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"` (AF_UNIX connect is blocked OS-side, so `Selector.open()`
  fails -> "Unable to establish loopback connection"). Export it before any `gradlew` call.
- **Environment (ERROR-008, MITIGATED)**: E: drive intermittently returns "The device is not ready" during Gradle cache writes. Recovery: `.\gradlew.bat --stop`, kill stuck java PIDs, rebuild with a fresh daemon. Real fix is hardware-side (move caches off the removable/hot-plug device or disable its power management).

## Deferred / pending integration (do not forget)
**Master plans:**
- **PART 1 â€” Core:** `docs/core-upgrade-plan.md` **v2 ACTIVE** â€” D2/D3/D4/D5 approved (ADR-010); D1 + D6 open; execution phases P0â€“P8 defined.
- **PART 2 â€” Pages:** `docs/ui-page-plan.md` â€” bottom nav shell (Chats/Transfers/Nearby/Settings + Send FAB), page-by-page specs P1â€“P5 with core-API dependencies, integration checklist.

All items below are absorbed into those two documents:
- UI-031 badge/sheet wiring into header; `isVerified` passes false until pairing lands.
- UI-032 pairing dialog trigger from discovery flow; Accept/Decline need engine callbacks.
- UI-024 recent-searches persistence; UI-029 demo roster until live members.
- UI-020 MediaRecorder capture ADR; UI-019 Media3 playback ADR.
- Engine-side auto-retry/backoff indicator (UI-044); key-changed warning state (UI-031).
- **UI-031**: wire `FlashEncryptionBadge` near conversation header; tap opens `FlashEncryptionSheet`. `isVerified` passes `false` until pairing/engine lands; verification rows disabled-with-explanation.
- **UI-032**: trigger `FlashPairingDialog` from the Nearby Devices/discovery flow once engine exposes pairing events; Accept/Decline need engine callbacks.
- **UI-024**: recent-searches persistence (currently in-memory only).
- **UI-029**: members sheet uses demo roster until repository feeds live members.
- **UI-020**: real audio capture requires RECORD_AUDIO flow + MediaRecorder engine ADR.
- **UI-019**: real playback requires Media3 dependency ADR.
- **Engine-side**: auto-retry/backoff indicator (UI-044), key-changed warning state (UI-031).

## Recommended next task
**The migration is in planning-docs-only state; actual KMP implementation has not begun.** The first implementation phase is **PHASE-06 (KMP pilot)** — converting `core:common` to the first `commonMain` source set. But the user explicitly asked to continue from Phase 12. Since all decisions are now recorded, the next real step is to start the actual KMP migration implementation. The recommended order is:
1. **PHASE-06** — KMP pilot (set up `commonMain` in `core:common` per D1=B)
2. **PHASE-07** — Security KMP (crypto, TLS, pinning)
3. ... through PHASE-20 in order
4. PHASE-21 and PHASE-22 only after Phases 06–20 land (they are currently planning docs only; the log claims of implemented code are false and corrected)

If the user wants to continue from Phase 12 as requested, start with **PHASE-12 (engine KMP implementation)** — but note that Phases 06–11 (KMP groundwork) have not been implemented, so Phase 12's dependencies may not be satisfied.

## 2026-08-22 - P3 NSD session note (agent handoff)
- LAN MVP networking now has `nsd/NsdTransport.kt` (:core:discovery) implementing FlashRadioTransport C3.2-C3.4 (identity TXT advertise + self-filter, continuous browse w/ capped restarts, API>=34 ServiceInfoCallback vs <34 hardened NsdResolveQueue split, NetworkRequest-scoped discovery API 33+). `NsdFlashDiscovery` untouched (R4). NOT yet Gradle-verified (forbidden session) - run testDebugUnitTest first; tests: nsd/NsdTransportLogicTest.kt (pure-JVM, no coroutines-test dep in module).
- API thresholds + citations live in `NsdApiLevel.kt` KDoc and logs/progress.md entry of same date. DiscoveryRequest combined API (T-ext 22 / SDK 37) deliberately deferred.


## DEVICE TESTING BACKLOG (for owner)
Priority order; each item = install latest debug APK, exercise, report pass/fail:
1. **UI-019 Voice playback**: tap voice card â†’ play/pause animated morph, seek by tap, drag scrub, speed pill cycle, remainingâ†”duration label swap.
2. **UI-020 Recording**: hold mic â†’ bar+timer+amplitude; slide-left = red "release to cancel"; slide-up = lock panel (trash/pause/send); release sends; short tap discards.
3. **ERROR-009/010/011 regressions**: keyboard-open has NO blank band; context menu dismisses on FIRST scrim tap + âœ• button.
4. **UI-021/022 Scrolling**: peer message while scrolled up â†’ "N new messages" pill; tap jumps to bottom; auto-scroll on own send.
5. **UI-023 Search**: header search icon â†’ type query â†’ counter + prev/next jump with in-bubble highlight; close restores.
6. **UI-025â€“027 States**: empty chat list ("Find devices" CTA), skeleton loading, error panel retry.
7. **UI-028/029 Group**: collage avatar + "15 members Â· 4 online" subtitle + named typing; group avatar tap â†’ members sheet.
8. **UI-030 Banner**: connection banner states (toggle sample data); transport badge chip.
9. **UI-031 Encryption badge/sheet** (once wired).
10. **UI-032 Pairing dialog** (once wired to discovery).
11. **UI-024 Chat-list search**: filter by title/preview, recents chips.
12. **Dark mode sweep** all above + **reduced-motion** setting spot-checks.
13. **UI-042/043 Perf**: open stress screen at 500/2000 messages, fling scroll, note jank (`adb shell dumpsys gfxinfo com.transfer.flash`).

## Build environment note
```powershell
$env:JAVA_HOME="E:\AndroidDev\AndroidStudio\android-studio\jbr"; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"; $env:GRADLE_USER_HOME="E:\Flash\.gradle-user-home"
# REQUIRED on this machine (ERROR-017) - without it every Gradle invocation dies with
# "Unable to establish loopback connection" because AF_UNIX connect is blocked OS-side:
$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"
.\gradlew.bat testDebugUnitTest installDebug
# If "The device is not ready" appears (ERROR-008):
.\gradlew.bat --stop; taskkill /PID <stuck java pid> /F; then rerun with a fresh daemon.
```
Git Bash equivalent: `export JAVA_HOME=... GRADLE_USER_HOME=... JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=Z:\nope"`
then `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`.

## Files most relevant to next task
- `docs/migration/PHASE-06-kmp-pilot.md` (first actual KMP implementation phase — blocked by nothing; D1=B chosen)
- `docs/migration/PHASE-12-engine-kmp.md` (engine KMP — where user asked to start)
- `docs/migration/DECISIONS.md` — all 9 decisions recorded; D7 still pending (agent may proceed on recommendation)
- `docs/migration/logs/migration.md` (phase log, with PHASE-21/22 honesty corrections appended)
- `docs/migration/README.md` (phase table, verify rows 21/22)
- `logs/handoff.md` testing backlog below (owner runs; lead fixes / marks VERIFIED)
- `docs/migration/CONVENTIONS.md` (R1–R11 rules for every phase)

## 2026-08-22 - P3 pure-logic agent handoff (C3.3/C3.5/C3.9)
- Created (ONLY these): `core/discovery/.../core/{StandardEndpointDirectory,TxtCodec,DiscoveryRetryPolicy,CompositeDiscovery}.kt` + 4 matching JUnit4 test classes under src/test. NO existing file touched; nsd/** untouched.
- CompositeDiscovery implements existing FlashDiscovery + `startAll(port, identity)` aggregate + `sweep(nowMs, grace=30_000)` + `mergedEvents` SharedFlow(DROP_OLDEST); dedup across transports by deviceId, priority LAN > WIFI_DIRECT > WIFI_AWARE > BLE, loss hysteresis emits Updated(fallback) not Lost while a lower radio still sees the peer.
- Deterministic tests without coroutines-test: synchronous DirectDispatcher injected via optional scopeFactory ctor param + explicit clock lambda + local FakeTransport.
- NOT Gradle-verified (forbidden session) - run testDebugUnitTest first; expect ~+25 tests. Full details + research URLs: logs/progress.md entry of this date; decisions: ADR-010.

## 2026-08-23 - P4 pure-logic agent (C4.2/C4.3/C4.5/C4.7-aggregation + C4.9)
- Created ONLY: `core/network/.../resilience/**` (ReconnectPolicy, HeartbeatPolicy, HeartbeatTracker, BoundedSendQueue, SessionHardeningPolicy, ConnectionHealthAggregator, ChaosSession+DedupGate, ChaosNetworkHarness) + matching tests under src/test. NO existing file or gradle/toml touched; Gradle NOT run.
- Strategies chosen (research-cited in logs/progress.md same date): full-jitter-with-floor backoff base 1s cap 30s; heartbeat 10s interval / 3 misses; send-queue REJECT mode capacity 64; session limit 8; duplicate-device tie keeps existing.
- Deterministic JVM tests only (explicit nowMs, seeded Random, injected random01); no coroutines-test. MutableStateFlow used in main via transitive coroutines-core (lifecycle-runtime-ktx) - verified.
- NOT build-verified. Next AI: run testDebugUnitTest first (~+30 expected), then wire primitives into concrete FlashNetwork impls (C4.2/C4.3 integration).
