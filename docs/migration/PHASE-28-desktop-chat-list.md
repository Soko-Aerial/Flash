# PHASE-28 — Desktop chat list (pointer idioms + the params desktop never passes)

**Status:** AUTHORED (2026-09-13, planning only — **no code written**). **Blocked by Phase 27**
(the shared shell is where this screen's state has to live).
**Risk:** LOW-MEDIUM — no new screens, no protocol, no crypto. All changes are call-site arguments
and one new platform seam.
**Decisions relied on:** D8 = A, D1 = B, R5, R7.

---

## What this phase is for

`FlashChatListScreen` already renders on desktop (`DesktopShell.kt:207`) and is the **same
composable** the phone calls (`MainActivity.kt:1422`). Nothing needs porting. What is wrong on
desktop is narrower and more specific than "it doesn't work":

### 1. Desktop passes 11 of ~35 parameters; the rest silently take empty defaults

Verified call site, `DesktopShell.kt:207–221`:

```kotlin
FlashChatListScreen(
    state = chatListState,
    onConversationClick = { id -> … },
    onSearchClick = { /* desktop v1: no search chrome */ },   // ← line 213
    onFindDevicesClick = { nav.selectTab(FlashDestination.NearbyDevices) },
    onLanClick = { nav.selectTab(FlashDestination.NearbyDevices) },
    isLoading = …,
    errorMessage = …,
    onRetryLoad = { engine.start() },
    modifier = …,
    listState = chatListScroll,
    bottomInset = tabBottomInset,
)
```

Everything else defaults to `{}` / `emptyList()` / `false`. `FlashChatListScreen.kt:74–84` proves
the screen **already supports** the missing set — it is the desktop call site that is short, not the
screen:

| Unpassed parameter | Android passes (`MainActivity.kt`) | Desktop today |
|---|---|---|
| `isSearching`, `searchQuery`, `onSearchQueryChanged`, `onCloseSearch` | `1429–1440` | absent — search is dead |
| `recentSearches`, `onRecentSearchClick`, `onClearRecentSearches` | — | absent (Android has the chips; see `FlashChatListSearch.kt:198`) |
| `messageBodyMatches` | `1441` | absent — buried matches can never surface |
| selection mode: `onCloseSelection`, `onPinSelected`, `onMuteSelected`, `onMarkSelectedRead`, `onArchiveSelected`, `onUnarchiveSelected`, `onDeleteSelected` | `1409–1420` region | absent — **bulk actions do not exist on desktop** |
| `onNewGroupClick` | `1428` | absent — **no way to create a group on desktop** |
| `isErrorEnvironmental` | `1431` | absent |

`FlashChatListScreen` also reads its own defaults for `state.isSelected`-style flags; desktop's
`chatListState` is built from `EmptyFlashChatRepository` until Phase 29, so most of this is inert
regardless — **but search over an empty repository is still real work**, and the call-site gap is
the thing this phase closes so Phase 29 does not have to.

### 2. The interaction idioms are touch idioms

Desktop gets the phone's gestures, which do not translate to a mouse:

- **`FlashMessageContextMenu`** in the conversation (`ui/chat/.../FlashMessageContextMenu.kt`) is a
  long-press menu. A mouse user expects **right-click**, and expects it *at the cursor*.
- **Selection mode** on the phone is entered by long-press and refined by tap. Desktop expects
  **ctrl-click to toggle** and **shift-click for a range**, plus a **click on empty space to exit**.
- There is **no hover state anywhere** — no row highlight, no affordance that a row is clickable.
- `FlashSwipeToReply` (`ui/chat/.../FlashSwipeToReply.kt`) is a drag gesture with no mouse analogue.

`:ui:platform-shims` owns seven seams (back handling, clipboard, file picking, permissions, image
decode, audio playback, voice capture — README/`:ui:platform-shims`) and **none of them is a pointer
or gesture seam**. This phase adds the first one.

### 3. Keyboard

The phone has no keyboard. Desktop needs: arrow-key list traversal, Enter to open, Escape to clear
search / leave selection, and a discoverable shortcut for search (Ctrl+F). Not in scope for *every*
screen at once — this phase establishes the pattern for the list, and Phases 30–33 reuse it.

## Design

**A pointer-idiom seam in `:ui:platform-shims`**, in the same shape as the seven existing seams:
a common `expect`/`actual` pair that answers *"what is the pointer doing"* without leaking
`java.awt` or `android.view` upward.

Shape (to be finalised during execution, but the contract is fixed):

- A `FlashPointerModifiers` (or equivalent) `@Composable` that returns the modifier set for a
  row — Android actual: no-op (long-press already works). JVM actual: hover + right-click +
  ctrl/shift-click.
- The right-click menu position must be **cursor-anchored on desktop** and **row-anchored on
  Android**; the screen passes a position into `FlashMessageContextMenu` rather than letting the
  menu compute one.

**Why a seam and not an `if (isDesktop)`:** R6 forbids `java.*` outside `jvmMain`, and a
platform check inside `commonMain` is the divergence this whole migration exists to remove. The
existing seven seams are the precedent.

## Open question (needs a product answer, not a technical one)

**Does selection mode belong on desktop at all?** A desktop user's mental model for bulk file ops
is shift-click and a context menu, not a modal selection bar that replaces the top bar. Two shapes:

- **(a) Port the phone's selection mode** — cheapest, guaranteed-consistent, but it will feel like a
  phone app in a window.
- **(b) Desktop-native** — ctrl/shift-click builds the same selection set, actions arrive through a
  right-click context menu, no top-bar takeover.

**(b) is the better product and more work.** Note the selection *math* is already shared and tested
(`FlashSelectionLogicTest`), so (b) changes only the affordance, not the state machine. Pick before
executing; the rest of the phase is the same either way.

## Do NOT

- **Do NOT change `FlashChatListScreen`'s parameter list** to make desktop easier. It is shared;
  adding a desktop-only parameter is the first crack. If a capability is missing, it is missing for
  both hosts and gets a shared parameter with a default.
- **Do NOT change any Android call site's arguments** — Android behaviour is frozen for this phase.
- **Do NOT put pointer types in `commonMain`** (R6).
- **Do NOT wire search to a real repository here.** Phase 29 owns the repository; this phase wires
  the *call site* so search state exists and is exercised against whatever repository is bound.
- **Do NOT implement drag-and-drop of files onto the list** — that is Phase 30 (Transfers) and the
  two must not both introduce a drop target.

## Sub-step plan

| # | Sub-step | Content |
|---|---|---|
| 28-1 | Pointer seam | New seam in `:ui:platform-shims` (expect/actual, Android no-op + JVM real). Unit-testable pure part in `commonTest`. |
| 28-2 | Search plumbing | Desktop call site passes `isSearching`/`searchQuery`/`onSearchQueryChanged`/`onCloseSearch`/`recentSearches`/`messageBodyMatches`. Search state hoisted to the Phase‑27 shell (it must survive navigation). Change the `onSearchClick = { /* desktop v1 */ }` stub at `DesktopShell.kt:213`. |
| 28-3 | Selection | Per the (a)/(b) answer: desktop selection state + whichever affordance was picked. |
| 28-4 | Group creation | `onNewGroupClick` wired (`FlashCreateGroupSheet` is shared already). |
| 28-5 | Hover + keyboard | Row hover state; arrow traversal, Enter, Escape, Ctrl+F. |
| 28-6 | Verification | `:ui:chat:compileKotlinJvm` + `:compileAndroidMain` + `jvmTest`, `:app:assembleDebug`, `:desktop:compileKotlinJvm`, R6 scan, plus a human pass on both hosts. |
| 28-7 | Log + README | Honest entry. |

## Honest limitation to record in the log

Until Phase 29 lands, `:desktop` binds `EmptyFlashChatRepository` (`DesktopEngine.kt:153`), so this
phase's search, selection and group-creation wiring will compile, run, and operate over an **empty
list**. That is not a defect in this phase — it is the same dependency Phase 21 recorded — but the
log entry must say it plainly rather than implying desktop chat works.
