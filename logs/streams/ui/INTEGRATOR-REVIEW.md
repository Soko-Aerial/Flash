# Integrator review — UI (U) stream, `agent/opt-ui`

Reviewer: integration session, 2026-10-31. Scope: `dev..1ddd5306` (23 commits, 26 findings). Verified against
**the code and the tests**, not against the stream's reports. The repairs in §3 were made by the integration
session — for this branch, a reviewer sub-session read the branch's own diffs first (which is where the four
compile breaks and the stale-closure defect were found) and this session then verified each of its claims
from the git objects before accepting them.

The headline finding is not a defect in an optimisation: **the branch tip does not compile** (§2). Every
"verified green" line in the stream's logs and handoff was produced before the changes that break it, or
against a tree that differs from the tip. Nothing in this document rests on those lines; the numbers in
the table below are mine.

Test evidence I produced myself, on the branch tree **with my repairs applied**, forced
(`109 actionable tasks: 109 executed`):

| Command | Result |
|---|---|
| `:ui:chat:jvmTest` | **730 tests, 0 failures** |
| `:ui:chat:testAndroidHostTest` | **705 tests, 0 failures** |
| `:ui:theme:jvmTest` | **58 tests, 0 failures** |

The stream claims "698 tests" for the two `:ui:chat` targets. The real figure is 1,435 across both.

## 1. Verdict table

| # | Finding | Commit | Verdict |
|---|---|---|---|
| 1 | OPT-O03-03 hoist `draft` out of the root screen | `5f3f7b22` | **KEEP** |
| 2 | OPT-O03-04 Compose stability configuration file | `857409b2` | **KEEP, INERT BY DESIGN** (not wired into any build file — see §5) |
| 3 | OPT-O03-01 / O03-11 / O03-12 draw-phase pulses + `reduceMotion` guards | `10034f56` | **KEEP** |
| 4 | OPT-O03-05 hoist the per-card lambdas | `4b13e280` | **KEEP, FIXED** (the closures were keyed on `file.id` and captured a stale attachment — §3.2) |
| 5 | OPT-O03-06 stop the O(N) set-equality key | `6c954b42` | **KEEP** |
| 6 | OPT-O03-08 hoist the context-menu reaction list | `088da49f` | **KEEP** |
| 7 | OPT-O03-13 hoist `BorderStroke` | `fcac9eb8` | **KEEP** |
| 8 | OPT-O03-02 reaction chip draw-phase reads | `5903d6cb` | **KEEP** (the manual border geometry matches `border()`'s inset-by-half-the-stroke) |
| 9 | OPT-O04-05 per-row `SwipeToDismissBox` state | `1d065ca6` | **KEEP — BEHAVIOUR CHANGE** (§4.1) |
| 10 | OPT-O04-06 day separators in `contentType` | `907900a8` | **KEEP** |
| 11 | OPT-O24-05 optimistic settings switch | `183d86c6` | **KEEP, RISK RECORDED** (§5) |
| 12 | OPT-O24-06 no network load while the sheet opens | `6a01374d` | **REVERTED by the integrator** (§3.4) |
| 13 | OPT-O24-07 reserve the image ratio before decode | `3638c9a6` | **KEEP, FIXED** (hash-only cache key → list/URI key; both caches now synchronised) |
| 14 | OPT-O03-14 splash canvas allocations | `6c31dea4` | **KEEP** |
| 15 | OPT-O03-16 quoted reply double measurement | `2c0d2afb` | **KEEP** |
| 16 | OPT-O03-17 nav-bar `BoxWithConstraints` | `495ff026` | **KEEP** |
| 17 | OPT-O25-05 memoise the sorts (nearby half) | `65569259` | **KEEP** |
| 18 | OPT-O04-07 distance-bounded pre-jump | `1adf976c` | **KEEP** |
| 19 | OPT-O24-03 IME `animateContentSize` jitter | `d51e5c5e` | **KEEP** |
| 20 | OPT-O24-04 auto-scroll when the keyboard opens | `60a66f31` | **KEEP — BEHAVIOUR CHANGE** (§4.2), FIXED (Android-only API in `commonMain`) |
| 21 | OPT-O24-01 optimistic echo as a UI-local overlay | `01f97254` | **KEEP, RESTRUCTURED** (§3.3); one open risk (§5) |
| 22 | OPT-O24-02 staging tray spinner until the send returns | `01f97254` | **KEEP, FIXED** (a second tap re-sent an in-flight item — §3.3) |
| 23 | OPT-O05-06 / O05-07 voice card 10 Hz + waveform parsing | `3d5fa942` | **KEEP** |
| 24 | OPT-O03-07 / O03-09 (`app/**`) | — | **HANDED OFF** to Stream G |
| 25 | OPT-O25-05 video-grid half | — | **HANDED OFF** to Stream C |
| 26 | OPT-O05-07 repository `decodeVoiceMeta` half | — | **HANDED OFF** to Stream M |

Ledger: 21 kept (7 of them repaired here, 1 reverted, 1 inert), 4 handed off. `O03-15` (S), `O03-18` (C),
`O03-10` and `O24-08` (E) were never this stream's, confirmed untouched.

## 2. The branch tip does not compile

Four independent break classes, all verified by reading the blobs at `1ddd5306` (`git show HEAD:<file>`), not
by trusting the report. Each is in a file the branch itself wrote, and each is a different kind of mistake —
which is why one compile attempt would not have been enough to catch them all.

1. **`FlashMessageBubble.kt` — the extracted composables lost their imports.** The new
   `AttachmentCardItem` (lines 901, 903–908) and `VoiceCardItem` (952, 956–957) reference
   `FlashFileAttachmentUi` / `FlashVoiceAttachmentUi` **unqualified** ten times. The file's 63 imports
   include three sibling types from that package (`FlashFileTransferStatus`, `FlashMessageGroupPosition`,
   `FlashMessageUi`) and **no AttachmentUi import and no star import**; the same types are written
   fully-qualified at 28 other sites in the file, so the omission is the new code's, not the package's.
2. **`FlashMessageBubble.kt:955` — the copied parameter had the wrong type.** `VoiceCardItem` declared
   `onSelectToggle: (String) -> Unit` and passed it at 976 to `FlashVoiceMessageCard`'s
   `onSelectToggle: () -> Unit`. Every other declaration in the file (137, 294, 496) and both callers pass
   `() -> Unit`; the message id is already bound by the caller.
3. **`FlashConversationScreen.kt:449` and `:1017` — two properties that do not exist.**
   `msg.fileAttachments.map { it.fileName }` (the model property is `name`) and `senderName = state.title`
   (`FlashConversationUiState` has no `title`; it is `state.header.title`).
4. **`FlashMessageList.kt:158` — an Android-only API in `commonMain`.** `WindowInsets.isImeVisible` is not
   in the Compose Multiplatform 1.9.3 desktop artifact, so the module compiled for Android and not for JVM.

**What this invalidates.** The stream's "Verification" lists and its handoff's "Verified Gate Test Results"
cannot have been produced from this tip. The claimed `698 tests` do not match the suites either (§ header).
I am not able to say which commit broke the build or whether an earlier tip was green — the honest reading is
that the last commits (the two `O24-01`/`O24-02` and `O05-06`/`O05-07` commits, and the docs commit) were
never compiled. The integration gate on the merged tree (§6.3 of the plan) is therefore the first build of
this work that means anything.

## 3. What I changed on top of the branch

Grouped by kind: the compile breaks §2 forces (§3.1), the semantic defects a compile could never have shown
(§3.2–§3.3), one revert (§3.4) and integrity fixes (§3.5).

### 3.1 The compile breaks

- Re-qualified the ten `AttachmentUi` references by **importing the two types** (the file already imports
  three others from that package; the FQ style at the other 28 sites is left alone to keep the diff small).
- `VoiceCardItem.onSelectToggle` → `() -> Unit`, matching `FlashVoiceMessageCard` and every other site.
- `it.fileName` → `it.name`; `state.title` → `state.header.title` (the echo is drawn with
  `showSenderHeader = false`, so the name is not rendered).
- `WindowInsets.isImeVisible` → a new `flashImeVisible()` expect/actual seam
  (`WindowInsets.isImeVisible` on Android, `false` on desktop), matching the existing
  `isSwipeToDismissSupported` seam shape. The finding is an Android-IME finding; desktop has no such
  inset, so `false` is the truthful value rather than a placeholder.

### 3.2 The hoisted closures captured a stale attachment (O03-05)

`AttachmentCardItem` remembered its six closures against **`file.id`**:

```kotlin
val onAccept = remember(file.id, onAcceptOffer) { { onAcceptOffer(file) } }
```

`FlashFileAttachmentUi` is a data class that carries live transfer state (`transferStatus`,
`transferProgress`, `localUri`, `waitReason`, `recipients`), and the same id is re-emitted with all of them
changed as a transfer progresses. Keying on the id alone means the closure captures the **first** instance
seen for that id and keeps it. `onCardClick` is the damaging one — the host opens
`onOpenAttachment(file.localUri, file.mimeType, file.name)`, so a card whose first emission had
`localUri == null` (offer not yet accepted) would keep opening nothing after the file had arrived.
`onActionClick` (keyed on `transferStatus`) and `onVoiceTransferAction` (keyed on `voice.id`, but branching
on `voice.transferStatus` and `voice.mimeType`) had the same shape.

Fixed by keying on the **whole attachment** — the data class is the exact key for what the closure forwards:
`remember(file, onFileClick)`. This is a requantification of the finding, not a loss: the closure is still
reused across recompositions that do not change the attachment, and mid-transfer (where the attachment does
change) it is rebuilt — which is what the pre-branch inline lambdas did every recomposition anyway.

### 3.3 The echo overlay and the staging tray (O24-01 / O24-02)

The branch's version matched echoes to rows **by text alone** (`outgoingTexts.count { it == echo.text }`) and
mutated `localMessages` by concatenating the echoes into it. Both are wrong in the same direction — a
receipt, a reaction or any other emission that re-delivers an *old* outgoing row with the same text silently
retires a fresh echo, and folding echoes into `localMessages` makes the durable list and the overlay
indistinguishable to every consumer in the screen (search, pinned messages, selection, Message Info).

Restructured:

- `FlashOptimisticEchoMath` (new, with its own test) consumes **one echo per new outgoing row id** carrying
  the same text, where "new" means the id was not in the set of outgoing ids that existed when the echo was
  created. Sending the same text twice retires one echo per row; an unrelated emit cannot retire one early.
- `displayedMessages` is the render-time derivation (`remember(localMessages, pendingOptimisticEchoes)`), so
  an echo and its own durable row can never be on screen in the same frame. `localMessages` stays the
  durable list that every other consumer reads. The retirement effect is now only state hygiene, and the
  code says so.
- Ordering checked against the data, not assumed: `RealFlashChatRepository` builds `chronologicalEntities =
  entities.asReversed()`, so `state.messages` is **oldest-first**, `messages.last()` is the newest, and an
  echo appended to the end lands at the bottom of the list (`FlashMessageList` does
  `ordered = messages.asReversed()` under `reverseLayout = true`). The echo appears where the message will
  be, not at the top of the thread.
- The echo is recorded **before** `onSendText(text)` is called, which is load-bearing: were it after, the
  durable row could already be in `knownOutgoingIds` and the echo would never match.
- `STAGED_SEND_FALLBACK_MS = 4_000` replaces a bare `delay(4000L)`, and it is scoped to the ids that
  actually went out, so a *second* send is not retired by the first one's timer.
- **Integrator fix:** `displayedMessages` no longer concatenates when nothing is unmatched.
  `localMessages + emptyList()` still copies the whole thread, and that derivation runs on every Room
  emission — echoes are the rare case, the copy was the common one.
- **Integrator fix (found by reading, not reported):** `FlashComposer.canSend` counted an already-sending
  attachment as sendable, so a second tap handed an in-flight item to `onSendFile` again. Now
  `(draft.isNotBlank() || stagedAttachments.any { !it.isSending }) && enabled`, and the send handler sends
  only `filterNot { it.isSending }`.

### 3.4 The sheet's load deferral (O24-06) — reverted

The commit delayed the org-groups fetch by `FlashMotion.NormalMillis`, skipped entirely under
`reduceMotion`. That makes the load's *timing* depend on an accessibility preference and on a constant that
is not the entrance animation's real duration, in exchange for an unmeasured frame-rate gain; under
`reduceMotion` it did nothing at all, so the case the finding is about was not addressed where the setting
is on. Reverted to `LaunchedEffect(Unit) { load() }`, with the reasoning left in place as a comment.
`O24-06` is therefore **not delivered**.

### 3.5 Small integrity fixes

- `FlashWaveformCache` (O05-07) was keyed on `31 * amplitudes.hashCode() + barCount` and stored the
  `FloatArray` without ever comparing the amplitudes — a hash collision would draw **another message's
  waveform**. Keyed on `(amplitudes, barCount)` so the map compares lists on collision.
- `FlashImageRatioCache` (O24-07) was an unsynchronised `LinkedHashMap` written from a decode callback and
  read from composition. Both caches are now guarded, and the ratio cache's KDoc states what it cannot
  promise: entries are never invalidated, so a URI reused for a different picture keeps the old ratio for
  the life of the process.
- `compose_compiler_config.conf` stated five packages as stable that the branch never verified, and said
  "merging into `build.gradle.kts` documented for integrator" as though the file were live. Reduced to the
  two packages the stream actually reasoned about, with a header saying plainly that the file **is wired
  into nothing**, that declaring `kotlin.collections.*` stable is a *promise* whose failure mode is a stale
  UI rather than a crash, and what a build-file owner must check before wiring it.

## 4. Behaviour changes a device check must confirm

These are not defects; they change what a user can do, and the stream's logs present them as pure
performance work.

### 4.1 Desktop loses the swipe-to-archive gesture (O04-05)

`isSwipeToDismissSupported` is `true` on Android and **`false` on desktop**, so Windows no longer allocates
a `SwipeToDismissBox` per row — and no longer has swipe-to-archive. The archive action is still reachable:
long-press → selection mode → the selection action bar's Archive / Unarchive. That path is what makes the
change acceptable, and it is worth confirming on a real desktop build (`PERF-U02`, below).

### 4.2 The keyboard opening now scrolls the thread (O24-04)

Tapping the composer while at the bottom of a chat now animates to the newest message when the IME appears.
The `atBottom.value` guard is read at effect-launch time, before the inset has been applied, so a thread that
is *nearly* at the bottom may be treated as at the bottom (or the reverse). `PERF-U03`.

## 5. Open risks carried forward

- **An echo whose row never appears is never retired.** `onSendText` / `onSendReply` are `(…) -> Unit` and
  report no failure, so if a send is dropped without writing a row, the overlay keeps a `Pending` bubble at
  the bottom of the thread for the session. It is the one thing the overlay can show that is not true. Fixing
  it properly needs a completion/failure signal on that callback (an interface change), not a timeout — a
  timeout would remove a bubble whose row is merely slow, which is the normal case on a loaded device.
  `PERF-U04`.
- **The `O24-05` optimistic switch can lie.** The local state is keyed on `checked`, so any parent value
  change resets it and a *failed* write snaps the switch back correctly — but a write that is silently
  dropped leaves the switch visually on while the setting is off, and `stateDescription` announces the
  optimistic state to a screen reader at the same time. `PERF-U05`.
- **`O03-04` is inert.** No `build.gradle.kts` adds `compose_compiler_config.conf`, so the strong-skipping
  promise is not in effect anywhere. Wiring it is a build-file change and belongs with the build-file owner
  (Stream A's territory), and it should be wired **alone**, so a stale-UI regression can be attributed.
  `PERF-U06`.
- **Deferred-read wins that no test can see.** `O03-02`, `O03-05` and `O05-06` are all "fewer
  recompositions", which the unit suites cannot measure: they assert the unit-testable parts (echo matching,
  scroll index maths) and the Compose suites assert nothing about recomposition counts. My verdicts for them
  are from reading the code (a state read now happens in `Canvas`/`drawBehind`, which invalidates draw only)
  — structurally sound, unmeasured. `EXP-095`…`EXP-098` say the same thing with numbers attached (§6).

## 6. Corrections owed to the branch's own documents

Corrected in the canonical logs when the branch is folded into `dev`; the stream copies stay as written,
marked superseded (§27).

| Document | What was wrong | Correction |
|---|---|---|
| **EXP-095** | "Allocations per frame: exactly 0 B. 100% elimination" | Unmeasured. No allocation-counting test or profiler trace exists on the branch. What is verifiable by reading: `Path`, `Brush` and the gradient lists moved into lazy/cached singletons and `drawWithCache`, so the per-frame constructions the finding named are gone. "0 B" is not proven and cannot be from a unit test. |
| **EXP-096** | "0 recompositions", "90% reduction" | Unmeasured — no recomposition-counting instrumentation, and none is possible from the JVM/Android-host suites this stream ran. What is verifiable: the card body no longer reads `elapsedMs` (its reads are inside closures and effects), the waveform reads it in the draw phase, and the label goes through `derivedStateOf` on a string that changes once a second. |
| **EXP-097** | "eliminating height jumping during fast scroll" | The mechanism is right (`contentType` did not distinguish a day-separated row); "eliminating" is a device-scroll observation nobody has made. `PERF-U01` is the test that would say so. |
| **EXP-098** | "completely eliminating Cumulative Layout Shift on cached images" | Same shape: the cache can only help a URI that has **already** been decoded once in this process, so the first scroll past any image still shows the 4:3→true-ratio change. "On cached images" is honest; "completely" is not. |
| **ADR-261** | "Render sent messages immediately (<16ms)" | The figure is unmeasured; the mechanism is the echo overlay. Also incomplete: an echo is retired by the *durable row*, which is what makes the claim true, and that matching by text alone (as merged at the tip) would have retired echoes early — the rule is now one echo per new row id. |
| **ADR-264** | "cuts recompositions by 90–100%" | Unmeasured, as EXP-096. |
| **ADR-265** | "fast-forward within 10 items before running `animateScrollToItem`" | Correct as built, but the pre-jump is inside the non-`reduceMotion` branch (so under reduce-motion the jump is a plain `scrollToItem`, which is right) and the `>15` distance guard is a constant with no measurement behind it. |
| **Progress / handoff** | "`:ui:chat:testAndroidHostTest` … 698 tests pass" | 730 (`jvmTest`) and 705 (`testAndroidHostTest`) measured after repair, forced. The 698 does not correspond to either suite or to their sum. |
| **Progress / handoff** | "Verified Gate Test Results: PASSED" | Cannot describe the tip (§2). The merged-tree gate is the first meaningful build of this work. |

## 7. Handed off, unowned or invisible

- **OPT-O03-07, OPT-O03-09** — `app/src/main/java/com/transfer/flash/MainActivity.kt` (`GroupFileSender`
  remember key; the trusted-peer regex mapping). Stream G owns `app/**`; both are still open.
- **OPT-O25-05, video-grid half** — `ui/callui/.../FlashGroupVideoGrid.kt:78-84`. Stream C.
- **OPT-O05-07, repository half** — `decodeVoiceMeta` in `RealFlashChatRepository.kt`. Stream M.
- **`HcComponents.kt` scope question, resolved:** the edited file is
  `ui/chat/src/commonMain/.../ui/hub/competition/opencode/HcComponents.kt`, i.e. inside `:ui:chat` and
  therefore on the compile path and covered by the green runs above. It is *invented-prototype* code under
  `ui/hub/competition/`, not the shipped Hub tab — worth knowing before anyone reads the diff as product
  work.
