# ADAPTIVE UI PLAN — phone → tablet → desktop (phases AD-1 … AD-8)

**Status:** PLAN — **no code written for any AD phase** (2026-09-15)
**Decisions:** **AD-D1 ANSWERED 2026-09-15 = (B)** — desktop scale policy (OS scale + desktop-only user
UI-scale, `fontScale` untouched), with Android's look preserved or improved, never degraded (§5.1).
AD-D2…AD-D5 are still open, with recommendations recorded.
**Track:** UI / adaptive-layout track. These are **not** migration phases 00–33; they do not
renumber, replace, or reorder them.
**Author of the plan:** Cline session, 2026-09-15 (owner request: *"upgrade the screens from android
small screen to desktop and to tablet optimization … everything looks big on the desktop windows
app, the chat list should go left then the conversation right, all that optimization and resizing"*)

**Related documents**

| Document | Relationship |
|---|---|
| [`../ui/responsive-layout.md`](../ui/responsive-layout.md) | UI-034 research doc — the breakpoint math this plan builds on |
| [`../ui/flash-premium-chat-ui-implementation.md`](../ui/flash-premium-chat-ui-implementation.md) | UI-034 requirements, UI-045 quality gate |
| [`../ui-page-plan.md`](../ui-page-plan.md) | Shell/pages authority (Phase 8) — §"App Shell" already declares the intended two-pane rule |
| [`PHASE-22-adaptive-desktop-screens.md`](PHASE-22-adaptive-desktop-screens.md) | Built the current desktop two-pane container (compile-verified) |
| [`PHASE-27-desktop-shell-unification.md`](PHASE-27-desktop-shell-unification.md) | Shared-shell extraction — needed only by AD-6's *preferred* route |
| [`PHASE-28-desktop-chat-list.md`](PHASE-28-desktop-chat-list.md) | Owns the pointer-idiom seam AD-4 extends — do not duplicate |
| [`DECISIONS.md`](DECISIONS.md) | D1–D13 are taken, the first AD-track entry being **D13** (desktop scale policy, answered 2026-09-15) |
| [`CONVENTIONS.md`](CONVENTIONS.md) | R1–R11 apply to every AD phase exactly as to any migration phase |

---

## 0. What this plan is, and what it is not

**It is** the missing plan for three separate, verifiable problems:

1. **Desktop scale** — component metrics and type sizes designed for a phone held at arm's length
   are rendered unchanged in a Windows window, so the app "looks big".
2. **Resize geometry** — window size, minimum size, pane widths and the split between them were
   never designed; the panes are a fixed 38/62 proportion of whatever the user drags.
3. **Layout arrangement** — the chat list and the conversation do not sit side by side in practice
   (the two-pane container exists, but chat is routed into the wrong pane), and **Android has no
   adaptive layout at all** — no rail, no two-pane, no tablet path.

**It is not**

- A re-run of PHASE-22. That phase's acceptance was *compiles and is wired*; it never claimed the
  arrangement was correct for chat, and its own C3 correction recorded that the conversation pane
  could not render against `EmptyFlashChatRepository`.
- A reason to fork the shared screens. Every AD phase works inside the single set of shared
  composables in `:ui:chat` (Option B / D8 = A).
- A licence to add dependencies. Any new library needs a recorded decision first (§6, AD-D4).

---

## 1. Verified current state (audit)

Every claim below was read out of the working tree on 2026-09-15, branch `dev`. File:line is given
so a later agent can re-verify rather than trust this document.

> **Working-tree caveat (read this before trusting a line number).** The working tree also contains
> **concurrent, uncommitted desktop chat-repository work** ("Phase 2 slice 4": a `:core:persistence`
> dependency added in `desktop/build.gradle.kts`, `DesktopEngine.kt` grown by ~170 lines,
> `DesktopShell.kt` re-commented around `engine.chats`). Line numbers below are from
> 2026-09-15 and **do drift**; every citation also names the symbol or code shape, so re-locate by
> symbol rather than by number. Nothing in this plan depends on that other pass, but AD-3's
> "honest-empty" note (sub-step 5) has already been overtaken by it — see that sub-step.

### 1.1 What already exists

| Thing | Where | State |
|---|---|---|
| Width classes + pane math | `ui/chat/src/commonMain/.../ui/adaptive/FlashAdaptiveLayouts.kt` | Exists, tested (`FlashAdaptiveLogicTest`, 13 cases, commonTest → runs on Android *and* JVM) |
| Breakpoints | `FlashAdaptiveMath.MediumMinWidthDp = 600f`, `ExpandedMinWidthDp = 840f` | Material 3 contract |
| Pane weights | `ListPaneExpandedWeight = 0.38f`, `DetailPaneExpandedWeight = 0.62f` | Proportional only — no min/max |
| Desktop width source | `desktop/.../DesktopAdaptive.kt:40` `rememberFlashDesktopWindowSize()` | Reads `LocalWindowInfo.containerSize` ÷ `LocalDensity` in composition (the post-ERROR-033 shape) |
| Desktop two-pane container | `desktop/.../DesktopAdaptive.kt:56` `DesktopTwoPane` | `Row` + 1.dp hairline; shows `detailPane` only on Expanded |
| Desktop sidebar | `desktop/.../DesktopSideBar.kt:39`, width `200.dp` (`:101`) | Vertical tab bar, expanded only |
| Desktop shell wiring | `desktop/.../DesktopShell.kt:114–115` (`sizeClass`, `twoPane`), `:563–576` (`if (twoPane) Row { DesktopSideBar + DesktopTwoPane }`), else bottom nav | Wired |

### 1.2 Defect A — the chat list / conversation two-pane is wired backwards

`DesktopShell.kt`'s **`listPaneContent`** (line 304 on 2026-09-15) is built as
`FlashAnimatedScreen(targetState = nav.current)` whose `when` includes
**`FlashDestination.Conversation -> FlashConversationScreen(...)`** (line 327).
`DesktopTwoPane(listPane = listPaneContent, detailPane = detailPaneContent, …)` is called at line 571.

`detailPaneContent` (line 518) resolves only three shapes:

```kotlin
selectedTransferItem != null -> TransferDetailPane(...)
selectedNearbyPeer  != null -> NearbyDetailPane(...)
else                        -> PlaceholderDetailPane()
```

**Consequence (this is the owner-visible bug):** pressing a chat row calls
`chatRepository.openConversation(id)` + `nav.navigate(Conversation)` (`:308–311`), and the
conversation is then composed **inside the list pane** — i.e. in the 0.38-weighted left column
(≈319dp of an 840dp window) — while the right-hand 0.62 pane keeps showing the placeholder.
The chat list itself disappears at the same moment, because the nav stack replaced it.

So the arrangement is not "chat list left, conversation right" in any window size. It is
"chat list left *or* a squeezed conversation left, with a placeholder right".

### 1.3 Defect B — nothing anywhere decides how big a desktop should be

| Evidence | File:line |
|---|---|
| Window opens at a hard-coded 1200×800 dp, no persistence, no minimum size | `desktop/.../DesktopMain.kt:47–51` (`rememberWindowState(width = 1200.dp, height = 800.dp)`) |
| **No `LocalDensity` provider anywhere in `desktop/src`, `app/src` or `ui/`** (the only `LocalDensity` *reads* are `DesktopAdaptive.kt:42`, `ui/chat/.../FlashComposer.kt:103`, `FlashMediaViewer.kt:253,458`, `FlashMessageList.kt`) | grep over `desktop/src`, `app/src`, `ui/` |
| Phone-sized metrics are the only metrics that exist | `ui/theme/.../FlashDimensions.kt`: `minTouchTarget = 48.dp`, `headerHeight = 56.dp`, `chatListRowHeight = 72.dp`, `composerMinHeight = 48.dp`, `bubbleMaxWidth = 320.dp`, `iconMd = 24.dp` |
| Phone-sized type is the only type that exists | `ui/theme/.../FlashTypography.kt`: `bodyDefault = 16.sp/22.sp`, `captionDefault = 14.sp`, `metadataDefault = 12.sp` |
| Bubble width is capped at 320dp absolute | `FlashDimensions.bubbleMaxWidth` (with `bubbleMaxWidthFraction = 0.78f` applied at the call site) |

Two mechanisms are consistent with the "everything looks big" report and **both** are in the tree:

1. `LocalDensity` is the OS display scale on Compose Desktop, so every `dp`/`sp` metric is
   multiplied by that scale (a 150% Windows display ⇒ 16sp body text draws at 24px, a 72dp chat row
   is 108px tall, a 48dp target is 72px).
2. The metrics themselves are touch metrics: 48dp minimum targets and 72dp rows are correct for a
   thumb on glass and oversized for a mouse pointer; 320dp message bubbles stay narrow in a wide pane.

**This plan does not assume either number.** AD-1's first sub-step is a measurement that logs the
actual density, `fontScale`, container size in px and width in dp on the owner's machine, and the
result is recorded in this document (§5, "AD-1 results") before any metric is changed.

### 1.4 Defect C — Android has no adaptive layout

`FlashWindowSizeClass` / `FlashAdaptiveMath` are referenced **only** from
`desktop/src/**` and `ui/chat/src/commonTest/**`. The Android host
(`app/src/main/java/com/transfer/flash/MainActivity.kt`, 2,026 lines) has a single-pane shell:
`FlashConversationScreen` at `:972`, `FlashChatListScreen` at `:1432`, `FlashBottomNav` at `:1535`,
and no width-class read anywhere.

The intended rule is already written down and never implemented on Android —
`docs/ui-page-plan.md:40`: *"Adaptive (UI-034): Expanded width renders Chats+Conversation as two
panes"*. On desktop it is half-implemented (§1.2).

### 1.5 Defect D — resize geometry is undefined

- Split is proportional (`0.38f`): at 1920dp the list pane is 730dp; at 2560dp it is 973dp.
- No minimum or maximum pane width; no splitter; no reset; no persistence of the split.
- No minimum window size, so the window can be dragged to a size where a two-pane split cannot fit.
- Crossing 840dp in either direction flips the whole arrangement with **no state-continuity plan**
  (selection, scroll, draft) and no transition (`responsive-layout.md` §Animation explicitly defers it).

### 1.6 Documentation inconsistency this plan corrects

`docs/ui/responsive-layout.md` header says **"DESIGNED → IMPLEMENTED"**, while
`docs/ui/ui-research-index.md:33` lists the same row as **"NOT STARTED"**. Both are wrong in
different ways: the *pure math* is implemented and tested, but the *two-pane layout* it describes
was deleted in ERROR-033 and only re-created desktop-locally in PHASE-22, and no screen has ever
consumed it correctly for chat. Corrected status: **PARTIAL — math IMPLEMENTED/VERIFIED, arrangement
in progress under this plan.**

### 1.7 Platform facts checked against current sources (2026-09-15)

Recorded here because AGENTS §13 forbids trusting recall for platform behaviour. Checked with
`tools/tavily_search.py` (see §2.5) plus direct fetches; **each fact carries its source and its
uncertainty**:

| Fact | Source | Status |
|---|---|---|
| The official desktop window-management guide documents **no density / DPI / scaling control at all** — no `LocalDensity` guidance, no per-monitor DPI guidance | [Top-level windows management — kotlinlang.org](https://kotlinlang.org/docs/multiplatform/compose-desktop-top-level-windows-management.html) (page dated 25 Aug 2026), fetched | **Verified absent.** Consequence: AD-1's measurement sub-step is the only way to establish what density the desktop actually runs at; nothing may be assumed |
| The **experimental v2 window API** provides `minSize`/`maxSize` (`DpSize`) on `Window`/`DialogWindow`, plus `WindowBoundsProvider`, `WindowPositionProvider` (`CenteredOnScreen`, `AlignedToScreen`, `Absolute`, …) and `WindowSizeProvider` (`Fixed`, `Unconstrained`, `Default` = 800×600dp), and `rememberWindowState(initialBoundsProvider = …)` | same page, quoted verbatim | **Verified**, but **v2 + experimental** → AD-2 must confirm availability in the pinned CMP **1.9.3** before relying on it |
| Legacy minimum size is AWT: `window.minimumSize = Dimension(w, h)` — **pixels**, not dp — and a maintainer/community thread reports it misbehaving under display scaling on Windows 11 at 200% (window resizable to half the value) | [JetBrains CMP-2285 / compose-jb#2285](https://youtrack.jetbrains.com/projects/CMP/issues/CMP-2285/Min-Max-size-window) + the GitHub issue's comments surfaced by search | **Reported, not officially resolved** → AD-2 must verify by dragging at 100/125/150%; convert dp→px via density |
| `WindowSizeClass` is **not available in Compose Multiplatform common** as of the 1.9.x line; the Android `material3-window-size-class` artifact is what supplies it, i.e. a new dependency on the shared tier | community report surfaced by search (r/Kotlin thread on CMP 1.9.3) | **Community claim → must be re-verified** before AD-D4 is answered; it supports the plan's zero-dependency default |
| Android's own guidance remains "convert the available window size into a window size class; pass it down as state, or derive state for nested composables" (`currentWindowAdaptiveInfo(...)` is shown in the current docs) | [Support different display sizes — developer.android.com](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes) | **Verified** (this is the AD-6 / AD-D4 decision input) |
| Touch-sized default metrics in a desktop window are a recognised, reported problem ("the default sizes of Compose widgets are too big for Desktop") | community thread surfaced by search | **Corroborates the owner report**; it is *not* evidence about this app's specific numbers — AD-1 measures those |

Two consequences worth stating plainly:

1. **Nobody may claim the cause of "everything looks big" until AD-1 sub-step 1 has run.** The two
   candidate mechanisms (OS display scale multiplying dp metrics; touch-sized metrics being too large
   for a pointer) are both plausible and both visible in the code — measuring separates them.
2. **The desktop window API this project currently uses is the legacy one** (`rememberWindowState` +
   `Window` from `androidx.compose.ui.window`), so AD-2 either adopts the experimental v2 API
   deliberately (recorded decision) or stays on the legacy route with the px/density caveat above.

---

## 2. Ordering, dependencies and standing rules

### 2.1 Ordering with the migration phase series (25–33)

- AD-1, AD-2, AD-3, AD-5, AD-7 are **desktop-local or `:ui:chat`-local** and do **not** depend on
  the shared-shell extraction. They can execute now, in the AD order below. **AD-1 is unblocked
  (AD-D1 = B, §5.1); AD-2 does not depend on AD-1 at all** — its pane math is pure dp geometry, so the
  two may be done in either order (AD-1 if the priority is "it looks too big", AD-2 if it is resizing).
- AD-4 depends on **PHASE-28**'s pointer seam (that phase authored the seam's contract; AD-4
  generalizes it to every screen). If PHASE-28 is deferred, AD-4 is deferred — do not write a
  second pointer seam.
- AD-6 (Android tablet) has two routes: **(a) Android-local** in `MainActivity.kt` now, or
  **(b) after PHASE-27** so one shell serves both hosts. **(b) is preferred** — (a) is the fork this
  whole migration exists to avoid — but (a) is allowed if the owner wants tablet support before
  PHASE-27's A/B/C pick is made. The chosen route is recorded in the phase's log entry.
- AD-8 is the last phase and feeds **UI-045**.

### 2.2 Standing rules for every AD phase

1. **One shared implementation.** No `FlashDesktopXScreen` copies of a shared screen.
2. **No desktop-only parameters** on a shared composable (PHASE-28's rule, applied to all screens).
3. **One source of truth for breakpoints** — `FlashAdaptiveMath` in `:ui:chat` `commonMain`.
   Android and desktop must not grow separate breakpoint constants.
4. **Android's look is a contract — preserve it, or improve it deliberately.** Android's rendering must
   stay **visually identical by default**: the shared defaults (`FlashMetrics.touch()`, typography,
   `FlashDimensions` values) are what Android already draws, and every shared tier change must be proven
   not to alter it (metric-pin test + before/after Android screenshot). A change that *does* reach
   Android is allowed only when it is a **deliberate improvement**, is listed in the phase's
   "Android-affecting changes" section, and is approved — "it looks better now" without a listed,
   approved change is a defect, not a bonus.
5. **No width-class read inside a `SubcomposeLayout`** (`BoxWithConstraints`) — ERROR-033.
6. **No dependency without a decision** (§5) recorded in [`DECISIONS.md`](DECISIONS.md) **and** an
   ADR in `docs/decisions.md` (append-only, R8).
7. **Accessibility is not traded away for looks.** OS display scaling and font scaling stay
   honoured; keyboard reachability is a gate, not a nice-to-have.
8. **Evidence or it did not happen** (R9 / AGENTS §12): phased commands + pasted output + on-disk
   test XML counts + screenshots in `logs/device-screenshots/adaptive/`.
9. **Desktop-only switches stay desktop-only.** Any host-specific input (density multiplier / UI scale,
   pointer metrics, window geometry, splitter ratio) is provided by the **host** — `:desktop`'s window
   root — and must never be reachable from `commonMain` or from the Android host. That is what keeps
   rule 4 mechanically true rather than a promise.

### 2.3 Phase-level status table

| Phase | Title | Depends on | Risk | Status |
|---|---|---|---|---|
| **AD-1** | Desktop scale & metrics policy | — (AD-D1 **answered = B**) | med | **READY — not started** |
| **AD-2** | Window & pane resize geometry | — (AD-D3 recommendation in use) | med | **READY — not started** |
| **AD-3** | Conversation as the detail pane (list left / chat right) | AD-2 | med | not started (needs AD-2's geometry) |
| **AD-4** | Pointer & keyboard idiom layer | PHASE-28; AD-3 | med-high | not started (blocked on PHASE-28) |
| **AD-5** | Wide-screen content design (reading measure) | AD-1, AD-3 | med | not started |
| **AD-6** | Android tablet & foldable | AD-2, AD-3, AD-5; PHASE-27 (route b) | high | not started |
| **AD-7** | State continuity across resize / breakpoint crossing | AD-3, AD-6 | med | not started |
| **AD-8** | Adaptive verification & quality gate (→ UI-045) | all above | med | not started |

### 2.4 Verification commands (real task names — not the phase files' projections)

Run with the repo's known-good JVM (see `logs/handoff.md` 2026-09-12 for the exact JBR 21 +
`JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=…` form):

```bash
# KMP + shared UI (Android + JVM compile, and the JVM-side adaptive tests)
./gradlew :ui:chat:compileKotlinJvm :ui:chat:compileAndroidMain :ui:chat:jvmTest --console=plain

# Desktop module
./gradlew :desktop:compileKotlinJvm :desktop:jvmTest --console=plain

# The window itself (manual gate — needs a human and a phone for anything network-visible)
./gradlew :desktop:run --console=plain

# Android must not regress
./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain

# Full sweep before a phase is called done (--continue, or later failures are masked)
./gradlew allTests testDebugUnitTest assembleDebug --continue --console=plain
```

**Do not** cite `:ui:chat:compileKotlinDesktop` or `:ui:chat:compileDebugKotlin` as gate tasks — those
names do not exist (PHASE-22 correction C5 / CONVENTIONS R5).

### 2.5 Checking platform facts (dev tool, not product code)

AGENTS §13 requires platform behaviour to be checked against current sources, with the source recorded
next to the fact. The repo carries a small helper for that:

```bash
python tools/tavily_search.py "Compose Multiplatform window minSize maxSize desktop"
python tools/tavily_search.py --domains=kotlinlang.org,developer.android.com "window size classes"
python tools/tavily_search.py --json "..."        # raw response
```

- It is a **developer tool**: nothing in `:app`, `:desktop`, `ui/` or `core/` may depend on it.
- The API key is read from `TAVILY_API_KEY`, `--key`, or the git-ignored `tools/.tavily_api_key`;
  **the key must never be committed** (a `tools/.gitignore` covers it).
- Every fact imported from a search into a plan or doc must carry: the URL, the date checked, and
  whether it is *verified*, *reported*, or a *community claim* — §1.7 is the worked example.
- A search result is **not** a substitute for measuring this app on this machine. Where the two can
  disagree (density, DPI, scaling), the measurement wins and the source is context.

---

## 3. Phase details

### AD-1 — Desktop scale & metrics policy ("everything is too big")

**Goal.** Make the desktop render at desktop-appropriate sizes **without breaking OS accessibility scaling
and without degrading Android** — Android's current look is preserved by default, and any Android-facing
change must be a listed, owner-approved improvement (§5.1, §2.2 rules 4/9).

**Why first.** Every later phase (pane widths, splitter, wide-pane content) is expressed in dp. If
the dp→px relationship and the metric set are wrong, every later decision is wrong too.

**Sub-steps**

1. **Measure before changing anything.** Add one probe that logs, on the first desktop composition
   and on every density change:
   `[scale] density=… fontScale=… containerPx=…x… widthDp=… heightDp=… sizeClass=…`
   plus the metric values actually in use. Run `:desktop:run` at **100%, 125% and 150% Windows
   display scale**, and paste the table plus screenshots into §4 "AD-1 results" here and into
   `logs/experiments.md` as an `EXP-` entry. *If the probe shows density is already 1.0 and the
   complaint is purely metric sizing, say so — the fix then moves entirely into sub-step 3 (option (A)
   of AD-D1 becomes the effective policy, and that must be recorded in the log entry).*
2. **AD-D1 is ANSWERED — implement it as written** (decision record: §5.1, answered 2026-09-15):
   **(B)** — honour the OS display scale as the baseline **and** add a user-facing desktop **UI-scale**
   control (0.75–1.5, default **1.00**), applied as a density *multiplier* at the desktop window root
   with **`fontScale` never overridden**. **(C) force-density is rejected.** The owner's added
   constraint is binding: **Android's look must be preserved, or improved — never degraded** (rule 4 +
   rule 9 in §2.2 state how that is enforced).
3. **Introduce a metrics seam — no new dependency, no `expect`/`actual`:**
   - `FlashMetrics` (data class) + `FlashMetrics.touch()` / `FlashMetrics.pointer(...)` /
     `FlashMetrics.scaled(factor)` in `:ui:theme` `commonMain`, carrying exactly the values that are
     today hard-coded in `FlashDimensions` (`minTarget`, `chatListRowHeight`, `headerHeight`,
     `composerMinHeight`, `bubbleMaxWidth`, `iconMd`, …).
   - `FlashTheme(metrics = FlashMetrics.touch())` — **the default equals today's constants**, so
     Android and every existing screenshot are unchanged by construction. `touch()` is the *only*
     default, and no shared composable may branch on which host is running.
   - The **desktop host** passes the pointer set and the density multiplier at its window root — the
     only place AD-D1's two halves are wired:
     `Density(density = systemDensity * uiScale, fontScale = systemFontScale)`, where `uiScale` defaults
     to **1.00** (so a fresh install renders exactly the OS scale) and is clamped to **0.75…1.5**.
     **`fontScale` is never overridden**, so OS font-size accessibility keeps working, and the multiplier
     affects only this host.
   - Why not `expect`/`actual` platform detection: the host already knows which host it is (the
     desktop entry point is a different module), so detection would add a seam and R6 risk for nothing.
4. **Add the desktop UI-scale control** (the AD-D1 = B half; desktop-only, per rule 9):
   - a Settings → Appearance control (desktop host only), plus reset-to-100%, with a live preview;
   - persisted through a **desktop-local** store (same state directory as AD-2's window geometry —
     desktop-only state, so it does not wait on the shared settings ABI of 09B-3 / PHASE-32);
   - it must be discoverable *and* ignorable: default 1.00 means a user who never opens Settings sees
     exactly the OS-scaled UI;
   - record in `docs/ui/settings-page.md` that this control exists on desktop and why it is not on
     Android (Android's equivalent is the system display-size setting).
5. **Android-affecting changes in this phase: none by default.** Keep the list in the phase's log entry;
   it stays empty unless something is a deliberate, owner-approved improvement. Concretely, this phase
   must not change Android's typography, its `FlashDimensions` usage, its default metrics, or its
   layouts — a *smaller-looking desktop* is achieved by the pointer metric set + the desktop-only density
   multiplier, not by shrinking shared values.
6. **Migrate the highest-visibility surfaces, one per commit**, each with before/after screenshots:
   chat list row (`FlashChatListRow.kt`) → conversation header → composer (`FlashComposer.kt`) →
   sidebar (`DesktopSideBar.kt`'s `200.dp`) and bottom-nav inset (`FLASH_BOTTOM_NAV_INSET`, `72.dp`).
   Each migration is a *token swap* (read `FlashMetrics` instead of the constant), so Android's resolved
   numbers cannot move.
7. **Record** the measured numbers, the decision, and the new metric table in
   `docs/ui/design-system.md`, `docs/ui/responsive-layout.md`, `docs/ui/settings-page.md`, this doc,
   `logs/progress.md`, `logs/handoff.md`.

**Tests.** New `FlashMetricsTest` in the module the type lands in:
- `FlashMetrics.touch()` must equal the current `FlashDimensions` values **field by field** (a
  regression pin — this is what makes rule 4 mechanical rather than aspirational);
- `scaled()` clamps to 0.75…1.5 and is **1.00 by default** (an unset scale must be a no-op);
- the pointer set never goes below the pointer floor from AD-D2;
- a test that the *theme default* is the touch set (so a forgotten argument can never hand Android
  pointer metrics).

**Gate (paste all of it).** `:ui:theme` + `:ui:chat` + `:desktop` compile on both targets,
`:desktop:jvmTest`, `:ui:chat:jvmTest`, `:app:assembleDebug`, plus:
- the **Android-unchanged evidence**: a before/after Android screenshot pair (chat list *and*
  conversation, light + dark) plus the metric-pin test output. Android must be pixel-identical apart
  from any change explicitly listed under "Android-affecting changes";
- the desktop screenshot set at **OS scale 100% / 125% / 150% × UI scale 1.00**, and at
  **UI scale 0.75 / 1.00 / 1.25 / 1.50** at one OS scale (extremes must not clip or overlap);
- a recorded note on whether the measurement showed the density hypothesis, the metric hypothesis, or
  both, with the probe output (`[scale] …`) pasted.

**Risk.** MEDIUM — touching metrics touches every screen. Mitigation: shared defaults are the current
values; the desktop-only multiplier starts at 1.00; migrate surface by surface; screenshots per surface
**and** per platform.

**Android-affecting changes (must be listed; empty by default).**
> _None. Any entry added here needs the owner's approval and a one-line reason._

**Do NOT.** Do not force `Density(1f)` (decision C, rejected — it defeats OS low-vision scaling). Do not
override `fontScale`. Do not shrink shared `sp` sizes to make the desktop look smaller (that would drag
Android down with it). Do not put the density multiplier or the UI-scale setting anywhere except
`:desktop`'s window root. Do not add a `commonMain` platform check. Do not change Android's default
metrics, typography or layouts in this phase.

### AD-2 — Window & pane resize geometry

**Goal.** Make resizing a designed behaviour: a sane minimum window, remembered geometry, panes with
minimum/maximum widths, and a real draggable splitter that persists.

**Sub-steps**

1. **Window policy** (`desktop/.../DesktopMain.kt:47–51`) — *API facts checked 2026-09-15, §1.7*:
   - minimum window size. Two routes, both to be evaluated at execution time:
     **(i)** the **v2 window API** — `androidx.compose.ui.window.v2.Window` accepts `minSize`/`maxSize`
     as `DpSize` (docs: *"Where the underlying window manager supports it, the user will not be able to
     resize the window past these bounds"*). It is **experimental**, so first verify that
     `androidx.compose.ui.window.v2` exists in the pinned CMP version (1.9.3) and record the opt-in;
     **(ii)** the **legacy route** — `LocalAwtWindow`/`window.minimumSize = Dimension(px, px)`, whose
     values are **pixels, not dp**, and which has a *reported* scaling interaction problem on Windows 11
     at 200% scale (JetBrains CMP-2285). If (ii) is used, convert dp→px through `LocalDensity` and
     **verify by dragging the window** at 100% and 150% — do not trust the call alone.
   - remember size / position / maximized across restarts: `rememberWindowState(initialBoundsProvider =
     WindowBoundsProvider(positionProvider = …, sizeProvider = …))` in the v2 API, or persist
     `WindowState.bounds`/`placement` yourself in the legacy API. Store it in the desktop state
     directory the other desktop stores already use (`~/.flash/…`, following `DesktopSettingsStore` /
     `DesktopIdentityStores` conventions and the CONVENTIONS 2026-09-03 amendment: OS-neutral paths,
     no `C:\` literals).
   - Window geometry is desktop-only state, so this does **not** wait on 09B-3 / PHASE-32 (those block
     only the *shared settings ABI*).
2. **Pane width math, pure and tested** — extend `FlashAdaptiveMath` (`:ui:chat` `commonMain`):
   - `ListPaneMinWidthDp = 320f`, `ListPaneMaxWidthDp = 480f`, `DetailPaneMinWidthDp = 480f`;
   - `listPaneWidthDp(totalWidthDp, ratio)` → clamped, and reduced further if it would starve the
     detail pane below its minimum;
   - the existing `0.38f/0.62f` weights survive as the **default ratio** at 840–1100dp only;
   - extend `FlashAdaptiveLogicTest` with: 840 / 1100 / 1440 / 1920 / 2560 / degenerate-500 cases,
     the invariant `list + detail + divider ≤ total`, and "detail ≥ its minimum whenever two-pane is
     allowed".
   This becomes the single place both hosts read pane geometry from (§2.2 rule 3).
3. **`DesktopPaneSplitter`** in `:desktop` (same precedent as `DesktopSideBar`: local until Android
   needs it, lift later):
   - drag to resize; the visual stays the existing 1.dp hairline (`FlashDimensions.borderHairline` +
     `borderSubtle`), the **hit area is ≥ 24dp** (WCAG 2.2 AA *Target Size (Minimum)* = 24×24 CSS px);
   - `Modifier.pointerHoverIcon` with an east-resize cursor (JVM-only API — legal in `jvmMain`);
   - double-click resets to the default ratio;
   - the ratio persists (same store as sub-step 1);
   - **keyboard/screen-reader reachable**: `Modifier.semantics { contentDescription = …;
     customActions = listOf(CustomAccessibilityAction("Wider") { … }, …("Narrower") { … }) }`
     (WCAG 2.1.1 — a drag-only control is not accessible).
4. **Sidebar responsiveness.** `DesktopSideBar` is a fixed `200.dp` (`DesktopSideBar.kt:101`).
   Collapse it to an icon-only rail at a threshold (proposal: **below 1100dp**) so an 840–1100dp
   window keeps a usable conversation pane. The threshold is part of AD-D3.
5. **Height class — explicitly deferred.** No `FlashHeightSizeClass` in these phases; the
   composer/tabletop case does not exist yet (`responsive-layout.md` §Future improvements keeps it open).

**Gate.** A recorded resize matrix at **640 / 840 / 1100 / 1440 / 1920 / 2560 dp** with screenshots,
performed as **window drags** (not just launches): splitter drag → reset → restart (both the ratio and
the window geometry restored); the minimum size clamps; nothing clips (header, composer, sidebar);
`:desktop:jvmTest` and `:ui:chat:jvmTest` green with the new math tests.

**Risk.** MEDIUM — geometry bugs are visible but containable; the math is pure and unit-tested, which
is exactly why it goes in `FlashAdaptiveMath` rather than inline in `DesktopShell`.

**Do NOT.** Do not hardcode pixel widths in `DesktopShell`. Do not add a second breakpoint table. Do
not give Android a splitter (touch panes do not have one — AD-6 reuses the math, not the control). Do
not animate pane changes in this phase (measure first; AD-8 owns motion decisions).

### AD-3 — Conversation in the detail pane (chat list left, conversation right)

**Goal.** Fix §1.2: on expanded widths the chat list stays on the left and the selected conversation
renders in the right-hand pane, with correct back/deselect semantics.

**Sub-steps**

1. **Pane routing.** Split `DesktopShell`'s single `listPaneContent` into:
   - `listPane` → `FlashChatListScreen` (chat-list tab) plus the existing transfers/nearby/settings tabs;
   - `detailPane` → `FlashConversationScreen` when a conversation is selected, else the designed
     placeholder (sub-step 3), else the existing `TransferDetailPane` / `NearbyDetailPane`.
   Route by destination, not by the nav-stack branch: the selected conversation is
   `nav.current.conversationId` (read at line 247 in `conversationIdIsTrusted`), so the detail pane has
   one source of truth.
2. **Back / deselect semantics** (state explicitly in `docs/ui/navigation.md`, UI-033):
   - two-pane: system back / Escape **clears the selection** (the list stays, the detail returns to the
     placeholder) instead of popping the nav stack;
   - one-pane: unchanged — back pops to the list.
   Add the rule to `FlashNavigationMath` as a pure function and cover it in
   `FlashNavigationLogicTest`, so both hosts share one rule.
3. **Designed empty-selection placeholder.** Replace `PlaceholderDetailPane()`'s blank surface with
   Flash's empty-state language (UI-025/UI-027 vocabulary): P2P-aware copy ("Select a chat"), the
   Flash icon/illustration language, and a "Find devices" affordance that selects the Nearby tab.
   This is a real design task, not a filler box.
4. **One selection model for all three two-pane surfaces.** Chat, Transfers and Nearby currently use
   three different carriers (`nav.current.conversationId`, `selectedTransferItem`,
   `selectedNearbyPeer`). Define one local `DesktopDetailSelection` (sealed) in `:desktop` so the
   detail pane has exactly one input and the three cannot disagree about what is shown.
5. **Repository reality — re-check it, do not assume the empty stand-in.** When this plan was written,
   `DesktopShell` bound `EmptyFlashChatRepository` until PHASE-29 (the honest-empty rule of ERROR-034).
   **The working tree now contains concurrent, uncommitted work that makes `engine.chats` the real
   durable repository once boot completes** (`DesktopEngine.kt` +~170 lines, `:core:persistence` added
   to `desktop/build.gradle.kts`, `DesktopShell.kt` keying the repository on `ready`). So:
   - AD-2/AD-3 must be re-verified against **whatever repository is bound when the phase runs** — read
     the code, do not trust either this document or the older phase files;
   - the pane arrangement (this phase) is independent of that work and does not need it;
   - if the repository is still the empty stand-in at execution time, say so in the log entry instead
     of claiming a working conversation.
   Do **not** edit the other pass's files to help this one — the two changes are independent.

**Gate.** `:desktop:run`: clicking a chat row shows the conversation **in the right pane** while the
list stays on the left; clicking another row replaces it; back clears the selection to the placeholder;
the sequence repeats after resizing across 840dp in both directions; keyboard focus moves list →
detail. Screenshots at 850 / 1280 / 1920 dp.

**Risk.** MEDIUM — shell restructuring in a ~1,000-line file, plus a back-semantics change that must be
tested, not eyeballed.

**Do NOT.** Do not build a desktop-only conversation screen. Do not change
`FlashConversationScreen`'s parameter list for desktop. Do not touch the Android call sites. Do not
claim the conversation works against a repository that does not exist yet.

---

### AD-4 — Pointer & keyboard idiom layer

**Goal.** A window with a mouse and a keyboard behaves like one — hover, right-click, ctrl/shift-click,
arrow-key traversal, shortcuts — on **every** screen, not just the chat list.

**Relationship to PHASE-28.** PHASE-28 introduces the pointer-idiom seam in `:ui:platform-shims` and
applies it to the chat list + its open selection-mode question. **AD-4 is the generalization**: it
extends that same seam to the conversation, transfers, nearby, settings and media viewer, and must not
create a competing seam. If PHASE-28 has not executed, AD-4 is blocked (do not fork the contract).

**Sub-steps**

1. **Hover states** on rows and controls (chat rows, transfer rows, nearby rows, settings rows, icon
   buttons), using Flash's own tokens — no Material ripple substitute.
2. **Right-click at the cursor** for message context menu (`FlashMessageContextMenu`) and row menus,
   with the cursor-anchored position passed in by the screen (PHASE-28's contract).
3. **ctrl/shift-click** selection, click-empty-space to clear, Escape to exit (the selection *math* is
   already shared and tested — `FlashSelectionLogicTest`).
4. **Keyboard**: arrow traversal, Enter to open/send, Escape to clear/close, `Ctrl+F` search,
   `Ctrl+K`-style quick jump if wanted; focus ring in Flash's visual language; tooltips on icon-only
   controls.
5. **Pointer affordances in shared screens**: the hover/focus/context-menu capability arrives as a
   modifier set from the seam, so `:ui:chat` composables gain it **without** platform checks
   (CONVENTIONS R6).
6. **File drop** (drag a file onto the window/list) is **explicitly out of scope here** — PHASE-30 owns
   the drop target; AD-4 must not add a second one.

**Gate.** Keyboard-only traversal of all four tabs plus a conversation (recorded as a step list, with
the shortcut table added to `docs/ui/accessibility.md`); hover + right-click screenshots; the Android
side compiles against the seam's no-op actual and its tests stay green.

**Risk.** MEDIUM-HIGH — cross-cutting interaction change; mitigated by the seam (Android actual is a
no-op) and by reusing PHASE-28's already-designed contract.

**Do NOT.** Do not put pointer types in `commonMain` (R6). Do not add a second pointer seam. Do not
change Android's touch gestures (long-press, swipe-to-reply stay as they are).

### AD-5 — Wide-screen content design (reading measure & content density)

**Goal.** A wide pane must not be a stretched phone: reading measure, bubble width, grid columns and
empty states all need wide-screen decisions.

**Sub-steps**

1. **Content measure tokens** (in `:ui:theme`/`:ui:chat`, driven by `FlashMetrics` from AD-1):
   - conversation content **max width** (proposal: ~900dp, centred) so a 1,400dp pane does not produce
     1,300dp text lines;
   - message bubble cap that follows the *pane* width, not the 320dp absolute
     (`FlashDimensions.bubbleMaxWidth` today) — e.g. `min(paneWidth × 0.62, 720dp)`;
   - chat-list content max width once the list pane is wide (avoids a 480dp row with a 380dp empty gap).
2. **Per-screen wide behaviour** for the four tabs + overlays:
   - **Chats**: list pane + conversation pane (AD-3); search results panel on the list side.
   - **Transfers**: two-column (list + detail) at expanded — the existing `TransferDetailPane` becomes
     the real detail view rather than a debug card.
   - **Nearby**: two-column (peers + peer detail/pairing) at expanded; identity card in the detail pane.
   - **Settings**: two-column (section list + section content) at expanded — the current single
     scrolling column is the phone shape.
   - **Media viewer / call UI**: width-aware sizing (image max box, video aspect box), no full-screen
     phone assumptions.
3. **Grid/media column counts by width** (`FlashImageGrid`, file lists) as pure, tested math.
4. **Empty / loading / error states at wide sizes**: they must scale (an illustration and a CTA sized
   for 360dp look lost in 2,000dp); UI-025/026/027 vocabulary, not new art.
5. **Pure math + tests** for every new sizing rule (same pattern as `FlashAdaptiveMath`), because that
   is how this repo keeps layout decisions auditable.

**Gate.** Screenshots at **840 / 1280 / 1920 / 2560 dp** for chats, conversation, transfers, nearby,
settings, media viewer; a stated measure ceiling with the measured line length; tests green.

**Risk.** MEDIUM — design judgement plus many screens; mitigated by doing it per screen with
screenshots and by keeping every decision in a token (not inline).

**Do NOT.** Do not introduce a second typography scale silently — **AD-D1 deliberately does not change
the type scale** (it scales density only, and never `fontScale`), so a smaller desktop type scale would
be a *new* decision with its own record, not a side effect of this phase. Do not exceed the agreed
reading measure "because it looks fine". Do not change Android layouts in this phase.

---

### AD-6 — Android tablet & foldable (the same architecture, Android host)

**Goal.** Android gets the same adaptive model the desktop has: a navigation rail on wide screens and a
list-detail two-pane — so a tablet is not a giant phone and a desktop is not a phone in a window.

**Dependencies / route.** Route **(b)** (preferred): after PHASE-27's shared-shell extraction, so
Android and desktop consume one shell and cannot drift. Route **(a)** (allowed if the owner wants
tablets sooner): adopt the same `FlashAdaptiveMath` inside `MainActivity.kt` now, and let PHASE-27
absorb it later. **Record which route was taken** in the log entry — a silently-forked shell is the
worst outcome of this phase.

**Sub-steps**

1. **Width source decision (AD-D4).** Choose between: reading `LocalWindowInfo.containerSize` in
   composition (zero deps; also what the desktop uses — but confirm Android availability in the pinned
   Compose version), or adopting `androidx.compose.material3:material3-window-size-class` /
   `material3-adaptive`'s `currentWindowAdaptiveInfo()`. The dependency option is the only one that
   brings posture (folding hinge) and saved-state pane navigation — and it needs an ADR + a decision
   number before a single import is added.
2. **Navigation rail at ≥600dp.** Lift the desktop's `DesktopSideBar` to a shared home (PHASE-22's own
   note says "if it becomes useful for the Android tablet layout, lift it") — one rail, two hosts.
   Bottom nav stays below 600dp; both read one tab-set source of truth.
3. **Two-pane list-detail at ≥840dp**: chat list left, conversation right, exactly like AD-3, with
   UI-033's back handling and AD-3's pure back/deselect rule (that is why the rule lives in
   `FlashNavigationMath`, not in the desktop shell).
4. **Foldables.** Posture awareness only if AD-D4 adopts the adaptive dependency; otherwise document the
   deferral (as `responsive-layout.md` already does) and verify unfold-by-width still lands correctly.
5. **Tablet verification on real hardware**: tablet or foldable, plus split-screen multi-window and
   rotation; large font and display size; TalkBack traversal order (list → detail).

**Gate.** Physical-device run (AGENTS §12 — LAN/Wi-Fi networking features need real devices; layout
work needs at least one real tablet, not only an emulator): screenshots at compact / medium / expanded
with rotation and split-screen; rail ↔ bottom-nav swap; two-pane behaviour; back semantics; TalkBack
order; `:app:testDebugUnitTest` + `:ui:chat:jvmTest` green.

**Risk.** HIGH — this is the phase most likely to expose that the Android shell cannot host two panes
without PHASE-27's extraction (which is blocked on a human A/B/C pick).

**Do NOT.** Do not reimplement the desktop's pane container on Android. Do not read
`LocalConfiguration.screenWidthDp` (wrong under split-screen — `responsive-layout.md` §What did not
work). Do not add the adaptive dependency without AD-D4's decision. Do not touch Wi-Fi/Wi-Fi Direct
code in this phase.

### AD-7 — State continuity across resize & breakpoint crossing

**Goal.** Crossing 840dp (or 600dp) must never lose what the user was doing.

**Sub-steps**

1. **Selection continuity**: with a conversation open, resize 839 → 841 → 839; the selection must return
   to the same conversation (single source: `DesktopDetailSelection` from AD-3 / the shared rule).
2. **Scroll continuity**: the chat-list and message-list `LazyListState`s are hoisted per tab today
   (`DesktopShell.kt:293–296`); verify they survive an arrangement flip, and hoist whatever is missing
   (same audit on Android in AD-6).
3. **Draft continuity**: half-typed composer text and reply/edit state survive a resize and a
   single-pane ↔ two-pane flip.
4. **Overlay continuity**: media viewer, context menu, pairing dialog, call UI across a resize.
5. **Persistence**: pane ratio, selected conversation id and window geometry through `rememberSaveable`
   / the desktop store, including process death where the platform allows it.
6. **One-frame convergence**: `rememberFlashDesktopWindowSize` reads state in composition; a resize must
   not produce a visible wrong-arrangement frame. If it does, fix it here (and record it).

**Gate.** A recorded matrix of crossings in **both** directions with a chat selected, mid-scroll and
mid-draft; screenshots before/after; no lost state, no crash, no duplicate composition; Android
equivalent recorded in AD-6.

**Risk.** MEDIUM — mostly state plumbing; the failure mode is subtle (state silently reset), which is
why the gate is a recorded matrix rather than one try.

**Do NOT.** Do not add a "state container" framework. Do not move navigation state into the panes.

---

### AD-8 — Adaptive verification & quality gate (feeds UI-045)

**Goal.** Prove the whole adaptive story, with evidence, and hand UI-045 a checklist that is already
green except for what is honestly impossible on the available hardware.

**Sub-steps**

1. **Matrix** (recorded in `docs/ui/responsive-layout.md` §Testing checklist and in this doc):
   width class × theme (light/dark) × OS scale (100/125/150%) × font scale (100/150%) × direction
   (LTR/RTL), on desktop **and** phone/tablet.
2. **Screenshots** into `logs/device-screenshots/adaptive/` with the naming from
   `logs/device-screenshots/README.md` if one exists, else a documented `<phase>-<screen>-<widthdp>-<theme>.png`.
3. **Accessibility**: keyboard-only pass (desktop), TalkBack (Android), Narrator/desktop screen-reader
   result stated honestly (Compose Desktop screen-reader support is limited — say what actually worked),
   contrast unchanged, and the pointer-target floor recorded.
4. **Performance**: resize smoothness (frame times or a documented best-effort observation), first frame,
   list scroll with the UI-043 harness on desktop if it can be made to run, memory with a maximised
   window. Record as an `EXP-` entry in `logs/experiments.md` — including "not measured" where it was
   not measurable.
5. **Update** `docs/ui/responsive-layout.md`, `docs/ui/accessibility.md`,
   `docs/ui/performance.md`, `docs/ui/design-system.md`, and the UI-045 line in
   `docs/ui/ui-research-index.md`.

**Gate.** The full sweep green (`allTests testDebugUnitTest assembleDebug --continue` with only the
known Windows DataStore failures), the screenshot set present on disk, and the honest list of what was
**not** verified (no device attached, no tablet available, etc.).

**Risk.** MEDIUM — it is the phase where claims are checked, so it may discover that AD-1 or AD-6 needs
rework. That is a feature, not a failure.

**Do NOT.** Do not mark UI-045 ACCEPTED from this phase — acceptance is the owner's call. Do not claim
device verification without a device.

---

## 4. Results recorded by executing phases (append-only)

### AD-1 results — measurement table

> _Empty. AD-1 sub-step 1 fills this in with `density`, `fontScale`, container px, width dp and the
> resolved metrics at 100 / 125 / 150% Windows scale, plus the screenshot paths._

### AD-2 results — resize matrix

> _Empty. Filled during AD-2's gate._

### AD-5 results — measure ceiling

> _Empty. Filled during AD-5's gate (actual line length at each screenshot width)._

---

## 5. Decisions required (owner answers — do not guess)

Record each answer in [`DECISIONS.md`](DECISIONS.md) as **D13, D14, …** (D1–D13 are taken, the first AD-track
entry being D13) **and** as an ADR in `docs/decisions.md` (append-only, R8). A phase that depends on an
unanswered decision waits, or executes the stated default with the assumption written in its log entry.
Check the tail of both files before claiming "the next free D-number": the files are edited by
concurrent passes, so **re-verify — do not renumber an existing decision or ADR.**

| # | Decision | Options | Needed by | Status / answer |
|---|---|---|---|---|
| **AD-D1** | Desktop scale policy | **(A)** honour OS scale, fix only the metric set (pointer-sized targets/rows); **(B)** A **plus** a user "UI scale" control (0.75–1.5, default 1.0) applied as a density multiplier with `fontScale` untouched; **(C)** force `Density(1f)` | AD-1 | **✅ ANSWERED 2026-09-15 = (B)**, with the owner's added constraint that **Android's look is preserved or improved, never degraded** — full record in §5.1 |
| **AD-D2** | Pointer target floor & focus visibility | Proposal: normal pointer targets ≥ 24dp (WCAG 2.2 AA), primary actions ≥ 32dp, focus ring always visible on keyboard focus | AD-1 | open (recommendation stands) |
| **AD-D3** | Splitter, rail thresholds & minimum window size | Draggable splitter + dbl-click reset (recommended) vs fixed ratio; sidebar collapse threshold (proposal 1100dp); minimum window size (proposal 720×480 dp) | AD-2 | open (recommendation stands) |
| **AD-D4** | Android width source / adaptive dependency | **(a)** manual (`LocalWindowInfo`, zero deps, same as desktop); **(b)** adopt `material3-window-size-class` + `material3-adaptive` (`currentWindowAdaptiveInfo()`: posture + saved-state pane navigation, new deps); plus: do we support fold-posture layout now? | AD-6 | open (recommendation stands) |
| **AD-D5** | Desktop selection-mode affordance (PHASE-28's open question, answered once for all screens) | **(a)** port the phone's modal selection mode; **(b)** desktop-native (ctrl/shift-click + right-click actions, no top-bar takeover) | AD-4 | open (recommendation stands) |

> **Owed — DONE 2026-09-15 in this same session:** mirrored as **D13** in
> [`DECISIONS.md`](DECISIONS.md) (appended at the end, no existing text touched) and as **ADR-037** in
> `docs/decisions.md` (appended at the end, no existing text touched — verified no ADR-037 and no D13
> existed there first). Either of those numbers was still free, and the appends landed after the other
> pass's ADR-036/Phase-2 material, so nothing was overwritten. The §5.1 record stays authoritative; the
> mirrors are the index entries.

### 5.1 AD-D1 — ANSWERED: desktop scale policy (2026-09-15)

**Decision (owner).** Implement **option B**, and do not let it change Android: *"it should be desktop
scale policy and also do ur recommendation but it should not change the android too much or it should
preserve android's look or should be an improvement."*

**What that means, precisely**

1. **Baseline stays the OS scale.** A fresh desktop install multiplies by **1.00**, so the desktop is as
   accessible as the user's OS settings demand. Nothing is ever rendered below the OS scale by default.
2. **A desktop-only user UI-scale** (0.75–1.5, default 1.00, clamped, reset-to-100% available) lets a
   user who finds the OS-scaled UI too large make it smaller **without** touching OS accessibility and
   **without** affecting any other app or platform.
3. **`fontScale` is never overridden** — text keeps following the OS font-size setting.
4. **Desktop gets pointer metrics; Android keeps touch metrics.** Both come from one shared type
   (`FlashMetrics`), whose default is the touch set, so Android's numbers cannot move by accident.
5. **Android is a contract, not a side effect.** Preserve Android's current look by default; if a change
   does reach Android it must be a listed, owner-approved *improvement* (§2.2 rule 4). Improvements are
   welcome — regressions are not.
6. **The mechanism is host-scoped** (§2.2 rule 9): the density multiplier, the UI-scale setting and the
   pointer metric set are wired only at `:desktop`'s window root. Nothing in `commonMain` and nothing in
   `:app` may read them, so "Android is unaffected" is structural, not a promise.
7. **Rejected:** option **(C)** force `Density(1f)` — it would make the app ignore the OS display scale
   and hurt exactly the users who raised it. Option **(A)** alone is the fallback *only* if AD-1's
   measurement shows `density` is already 1.0 on this machine and the whole problem is the metric set;
   that fallback is recorded in AD-1's log entry, not assumed here.

**Consequences for later phases.** AD-5's content measure and AD-2's pane math are expressed in the
desktop host's dp space, so they inherit the multiplier for free (no per-screen scale work). AD-6
(Android tablet) must **not** consume the UI-scale switch — a tablet's scaling is the OS display-size
setting, and the same AD-1 measurement is re-run on Android only to prove Android did not move.

---

## 6. What "done" means for every AD phase (definition of done)

A phase is complete only when **all** of these are true (AGENTS §30 applies in full):

1. Code exists and compiles on **every** target it touches
   (`:ui:chat:compileKotlinJvm`, `:ui:chat:compileAndroidMain`, `:desktop:compileKotlinJvm`,
   `:app:assembleDebug` as applicable).
2. Unit tests for every new pure rule, with the on-disk XML count pasted (not inferred).
3. The manual gate recorded: screenshots on disk **and** the exact window sizes / scales used.
4. Android behaviour explicitly checked: **unchanged by default** (before/after screenshot + the
   metric-pin test), or, if a change is intentional, **listed and owner-approved** as an improvement
   (§2.2 rule 4) — this applies to every AD phase, not only the Android ones.
5. `docs/ui/responsive-layout.md` (and any other affected UI doc) updated — not just this plan.
6. `logs/progress.md` entry, `logs/errors.md` for any new error, `logs/experiments.md` for any
   measurement, `logs/handoff.md` at the top.
7. Honest "not verified" list, including anything that needed hardware.
8. Commit with a focused message (`feat(adaptive-desktop): …`, `perf(adaptive): …`, `fix(adaptive): …`).

---

## 7. Do NOT (the whole plan)

- **Do NOT fork the shared screens.** One implementation, two hosts. A `FlashDesktop…Screen` copy is the
  failure mode this migration exists to prevent.
- **Do NOT add desktop-only parameters** to shared composables; if a capability is genuinely missing, it
  is missing for both hosts and gets a shared parameter with a default.
- **Do NOT grow a second breakpoint table.** All widths/pane math live in `FlashAdaptiveMath`.
- **Do NOT read width via a `SubcomposeLayout`** (`BoxWithConstraints`) — ERROR-033.
- **Do NOT break OS accessibility scaling** to make the app look smaller (no `Density(1f)`, no
  `fontScale` override, no shrinking text below the typography scale).
- **Do NOT let a desktop fix drag Android down.** Android's look is a contract (rule 4): unchanged by
  default, improved only deliberately and with the change listed and approved. Shared defaults
  (`FlashMetrics.touch()`, typography, `FlashDimensions` values) must keep resolving to exactly what
  Android draws today.
- **Do NOT expose the desktop UI-scale switch, the density multiplier, pointer metrics or window
  geometry to `commonMain` or to Android** (rule 9) — they are `:desktop`'s window-root concern only.
- **Do NOT add a dependency** (adaptive, window-manager, drag-and-drop, splitter library) without an
  ADR + decision number first (CONVENTIONS R10 spirit).
- **Do NOT change any protocol, wire format, Room entity or crypto** — R8; a phase that does is a bug.
- **Do NOT claim a device/tablet result** that was not produced on hardware.
- **Do NOT let these phases edit migration phases 00–33's scope.** If an AD phase needs one of them
  (`27`, `28`, `29`, `30`), it says so and waits.

---

## 8. Logging requirements (same as any phase)

- Every executed AD phase appends one entry using
  [`TEMPLATE-phase-log.md`](TEMPLATE-phase-log.md) to `logs/migration.md` **and** a normal entry to
  `logs/progress.md` (the migration log is the phase record; the progress log is the project record).
- Measurements go to `logs/experiments.md` as `EXP-<n>` with: machine, OS, display scale, window size,
  build hash, what was measured, and the numbers.
- Failures get `ERROR-<n>` entries in `logs/errors.md` with the real root cause, the failed attempts, and
  the working fix — an unresolved one is `Status: OPEN`.
- `logs/handoff.md` gets a new top section at the end of every session: branch, phase, last verified
  build, last test, blockers, recommended next task, files most relevant.
- `AGENTS.md` §29 (Current Phase) is updated when a phase starts or finishes.

---

## 9. One-page summary for a new agent

> The desktop two-pane container exists but chat renders in the wrong pane (AD-3), nothing decides
> desktop sizing so everything looks like a phone blown up (AD-1), resize geometry was never designed
> (AD-2), mouse/keyboard idioms are missing (AD-4), wide screens have no content measure (AD-5), Android
> has no adaptive layout at all (AD-6), state is not proven across breakpoints (AD-7), and none of it has
> been verified as a whole (AD-8).
>
> **AD-D1 is answered = (B)** (§5.1): desktop keeps the OS display scale as its baseline and adds a
> desktop-only UI-scale control (default 1.00, 0.75–1.5), `fontScale` never overridden — and **Android's
> look is a contract: preserved by default, improved only deliberately** (§2.2 rules 4 and 9 make that
> structural rather than a promise).
>
> Execute in order AD-1 → AD-8. **AD-1 and AD-2 are the ones that answer the owner's complaint and both
> can start immediately** (AD-1 begins with its measurement sub-step; AD-2 needs only the existing tested
> math; either order works — they are independent). AD-3 is the "chat list left, conversation right" fix
> and depends only on AD-2's geometry. AD-4 waits on PHASE-28; AD-6 prefers PHASE-27.
>
> Owed before AD-1 is *closed*: mirror AD-D1 into `DECISIONS.md` (next free D-number) + an ADR in
> `docs/decisions.md` — deliberately deferred because another pass is editing those files right now.
