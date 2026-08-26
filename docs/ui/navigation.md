# navigation

**Status:** IMPLEMENTED
**Component ID:** UI-033
**Last updated:** 2026-08-25
**Owner phase:** Premium chat UI — component sequence

---

## Component

`FlashNavigationState` / `FlashDestination` / `FlashBackStackState` / `FlashAnimatedScreen`
(`ui/chat/src/main/java/com/transfer/flash/ui/navigation/FlashNavigation.kt`).

## Purpose

Provides the screen-switching contract for the Flash showcase app: which screens exist
(ChatList → Conversation, plus Transfers, NearbyDevices and Settings per AGENTS.md §22 primary
flows), how pushes/back/tab hops form a stack, and how the visible screen transition animates
using the UI-037 motion tokens reserved for exactly this component (`screenPushEnter()` /
`screenPopEnter()` / `tabEnter()` and their exits). It has **replaced** the ad-hoc boolean
flags in `MainActivity` (`showConversation`, `showLanHome`, …) with a single source of truth;
`showDevConsole` is the one remaining flag, and it drives an overlay layer rather than a
screen.

## Research sources

- [Add support for the predictive back gesture](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture) — `BackHandler` vs `PredictiveBackHandler`; innermost enabled handler wins; intercepting back at root disables back-to-home animation.
- [Set up Predictive back | Jetpack Compose](https://developer.android.com/develop/ui/compose/system/predictive-back-setup) — predictive-back is default-on Android 15+; Navigation Compose 2.8+ needed for built-in predictive support; `popEnterTransition`/`popExitTransition` for distinct back animations.
- [Provide custom back navigation](https://developer.android.com/guide/navigation/custom-back) — Compose `BackHandler` semantics; enable/disable driven by observable UI state, not conditionals after the fact.
- [Add custom in-app transitions (PredictiveBackHandler)](https://developer.android.com/guide/navigation/custom-back/support-animations) — gesture-progress-driven animation pattern.
- [Type safety in Kotlin DSL and Navigation Compose](https://developer.android.com/guide/navigation/design/type-safety) — `@Serializable` route objects/data classes (Navigation 2.8+).
- [Navigation with Compose](https://developer.android.com/develop/ui/compose/navigation) — NavHost/NavController model, dependency footprint (`androidx.navigation:navigation-compose` + kotlinx-serialization plugin).
- [Encapsulate your navigation code](https://developer.android.com/guide/navigation/design/encapsulate) — keeping nav code out of screens; screens stay stateless via callbacks.
- [Conditional navigation with Navigation Compose (programminghard.dev)](https://programminghard.dev/compose-navigation/) — pitfalls of mixing reactive sealed-class state with imperative `NavController`: two sources of truth; argues for a single NavHost when using the library.

## Existing approaches studied

1. **Navigation-Compose library** (`NavHost` + type-safe `@Serializable` routes, 2.8+).
   Full-featured: back stack management, saved-state restoration, deep links, predictive
   back transitions, argument passing. Cost: new dependency (`navigation-compose`,
   `kotlinx-serialization` plugin), graph boilerplate for 4 destinations, and a second
   mental model alongside Compose state.
2. **Sealed-class destination + manual `when` rendering** (the current MainActivity pattern,
   formalized). Zero deps but booleans/scoped flags scale badly, no stack semantics, easy to
   create two sources of truth (the pitfall documented by programminghard.dev).
3. **Plain state-holder class + generic `AnimatedContent` wrapper** (chosen): an explicit
   tiny state machine (`FlashNavigationState`) owning the stack, rendered through one
   generic `FlashAnimatedScreen`. No new dependencies; logic is pure JVM and unit-testable;
   single source of truth preserved because UI derives entirely from `state.current`.

## What worked

- Single-source-of-truth principle (official docs + programminghard.dev): render *only* from
  `current`; never mix imperative jumps with reactive flags.
- Back handling as observable state: `canGoBack` lets the host enable/disable `BackHandler`
  declaratively instead of conditional interception (official best practice).
- Duplicate-consecutive-push guard mirrors what NavController effectively does when you
  double-tap a list row; making it explicit prevents stacked duplicates of Conversation.
- Generic `AnimatedContent(targetState)` wrapper keeps transition policy in one place and
  reuses the already-shipped UI-037 tokens (reduce-motion aware for free).

## What did not work

- **Boolean-flag switching (current MainActivity showcase)** — rejected as the end state:
  N flags → 2^N implicit states, back handling duplicated per flag, no depth cap.
- **Adopting navigation-compose now** — deferred (see decision below), not rejected forever.
- Mixing reactive screen-state selection *inside* a NavHost (two sources of truth) —
  documented failure mode in research; our design avoids it by having exactly one authority.

## Chosen approach

Dependency-free contract:

```text
FlashDestination (enum, titles)
FlashBackStackState (destination + conversationId, isValid())
FlashScreenTransition (None | Push | Pop | TabForward | TabBackward)
FlashNavigationMath (pure helpers: MAX_STACK_DEPTH=10, TabOrder, HOME_TAB, tabIndex,
                     isTabRoot, nextStackSize, shouldIgnorePush, resolveConversationTitle,
                     pushed, popped, transitionFor)
FlashNavigationState (ONE immutable List<FlashBackStackState> in mutableStateOf;
                      navigate / back / selectTab / current / entries / canGoBack)
FlashNavigationStateSaver + rememberFlashNavigationState() (rememberSaveable)
FlashAnimatedScreen(FlashBackStackState) (AnimatedContent, direction-aware ContentTransform)
```

**The stack must live in snapshot state.** It originally held a plain `mutableListOf`, on the
reasoning that keeping Compose types out of the class made the logic testable. That was the
single defect behind four separate device symptoms: chat-row taps, header back, and every
bottom-nav tap all mutated the stack correctly and *never recomposed*, and
`BackHandler(enabled = nav.canGoBack)` latched `false` at first composition so system back
never reached `back()`. Because `showDevConsole` *was* real snapshot state, closing the Dev
Console forced the first recomposition since those taps and rendered the already-mutated
`current` — the "close drops me on the chat screen" report was a symptom of the same bug, not
a second one. Testability is preserved by moving the list transforms into pure
`FlashNavigationMath.pushed`/`popped` and exposing `entries` read-only.

One immutable list assigned wholesale, rather than a `mutableStateListOf`, because
`selectTab` replaces the entire stack: `clear(); add(root)` on an observable list publishes a
transient empty stack, and `current = entries.last()` would throw on that intermediate frame.

Host wiring (`app/src/main/java/com/transfer/flash/MainActivity.kt`):

```kotlin
val nav = rememberFlashNavigationState()

// Console first: while it is up it owns back, and the shell handler stays disabled so one
// press never both closes the console and pops the stack.
BackHandler(enabled = showDevConsole) { showDevConsole = false }
BackHandler(enabled = nav.canGoBack && !showDevConsole) { nav.back() }

FlashAnimatedScreen(targetState = nav.current) { entry ->
    when (entry.destination) {
        ChatList      -> FlashChatListScreen(onConversationClick = { id ->
            chatRepository.openConversation(id)
            nav.navigate(Conversation, conversationId = id)
        }, listState = chatListScroll, bottomInset = tabBottomInset, …)
        Conversation  -> FlashConversationScreen(
            onBack = { chatRepository.closeConversation(); nav.back() }, …)
        Transfers, NearbyDevices, Settings -> … // listState + bottomInset per tab
    }
}
FlashBottomNav(
    selectedTab = nav.current.destination,
    onTabSelected = nav::selectTab,
    onTabReselected = { /* animateScrollToItem(0) on that tab's hoisted state */ },
)
```

Per-tab `LazyListState`s are hoisted in the host, not remembered inside the pages:
`AnimatedContent` disposes the outgoing page, so a page-local scroll state dies on every tab
switch and silently jumps back to the top.

### Back resolution (three steps)

1. `entries.size > 1` → pop, consume the press.
2. else if `current.destination != HOME_TAB` → `selectTab(HOME_TAB)`, consume the press.
   Android/Telegram convention: back from Transfers/Nearby/Settings returns to Chats.
3. else return `false` → the host does not consume → the system plays back-to-home.

`canGoBack` mirrors exactly these three cases, so `BackHandler` enablement stays truthful.

## Why it was chosen

Flash has **4 flat destinations and one parameterized edge** (Conversation(id)). The
nav-library value-adds we would actually use today are stack bookkeeping and a transition —
~60 lines of pure Kotlin here, zero dependencies, fully unit-testable without instrumentation.
This matches the project rule against adding frameworks without necessity (§3) while still
giving predictive-back-compatible semantics: `back()` returning false at root lets system
back fall through so the OS back-to-home animation plays (official guidance). Honest
trade-off acknowledged: we give up deep links and gesture-progress-driven predictive
animations — both acceptable for the showcase phase. Back-stack survival across
configuration change and process death is *not* given up: `rememberFlashNavigationState()`
is a `rememberSaveable` with a `listSaver` encoding each entry as
`"DestinationName|conversationId"`, and `decodeNavigationEntry` drops unknown or invalid
entries instead of crashing (a stack that restores empty falls back to the ChatList root).

**Decision note:** `androidx.navigation:navigation-compose` was evaluated and **deferred**,
not rejected. Revisit triggers: (a) more than ~6 destinations or nested graphs, (b) deep-link
requirements (e.g., opening a conversation from a notification), (c) need for per-destination
`SavedStateHandle`/ViewModel scoping (our saver restores the stack, not each screen's own
state), (d) multi-module nav graphs. Migration path is clean:
each `when` branch becomes a `composable<FlashBackStackState>` route.

## Visual specification

None of its own — this component draws no chrome. Titles come from
`FlashDestination.title` / `FlashNavigationMath.resolveConversationTitle()` and must be
rendered by screens via `FlashText`.

## Interaction specification

- Forward: caller invokes `navigate(destination, conversationId?)`.
- Lateral: `selectTab(destination)` replaces the whole stack with that tab's root, so tab
  hops never accumulate depth and back from a tab root is never "pop".
- Back: host exposes `BackHandler(enabled = nav.canGoBack && !showDevConsole) { nav.back() }`,
  with a higher-priority `BackHandler(enabled = showDevConsole)` above it so a single press
  never both closes the Dev Console and pops the stack.
- `back()` at root returns `false` → host must NOT consume back → system handles exit.
- Pushing the exact current `(destination, conversationId)` is a no-op (double-tap safe), and
  `pushed()` returns the *same list instance* in that case so the snapshot write is free.
- Pushing `Conversation` without a valid id is rejected (`isValid()` guard).

## Animation specification

`FlashAnimatedScreen` is direction-aware: `FlashNavigationMath.transitionFor(from, to)`
classifies every entry pair as `Push`, `Pop`, `TabForward`, `TabBackward`, or `None`, and the
`transitionSpec` builds an explicit `ContentTransform` per case (`sizeTransform = null`
throughout, `label = "flashNavigation"`). Direction is derivable from the pair alone because
`Conversation` is the only non-tab-root destination.

| Case | Enter | Exit | zIndex |
| --- | --- | --- | --- |
| `Push` (tab root → Conversation) | `screenPushEnter()` — fade + slide in from the trailing edge (30% width, slowMillis, Standard) | `screenPushExit()` — fade + slide out leading (slowMillis, Accelerate) | `1f`: the opening screen rides over the one it covers |
| `Pop` (Conversation → tab root) | `screenPopEnter()` — mirror: slide in from the *leading* edge | `screenPopExit()` — slide out *trailing* | `0f`: the closing screen slides off the top of the revealed one |
| `TabForward` / `TabBackward` | `tabEnter(towardEnd)` — short 10%-width hop, normalMillis | `tabExit(towardEnd)` | default |
| `None` | `EnterTransition.None` | `ExitTransition.None` | default |

Tab direction follows the sign of the `FlashNavigationMath.tabIndex` delta, so the pages read
as a pager rather than as four screens opening on top of each other.

Chrome animates alongside the pages, not with them: the hanging bottom-nav capsule enters and
leaves on `shellBarEnter()` / `shellBarExit()` as `isTabRoot(current.destination)` flips, and
the Dev Console is a sibling overlay layer on `sheetEnter()` / `sheetExit()`.

Under reduce-motion every token above collapses to `None`/no-offset on its own, so no caller
branches on `reduceMotion` for these. Host-side `tween*Spec()` animations do **not**
self-collapse (see the bottom-nav and Dev Console chip insets, which gate explicitly).
Interruptible by construction — `AnimatedContent` retargets mid-flight.

## Gesture specification

System back / gesture handled via `BackHandler` at the host level (innermost enabled wins;
none enabled at root → predictive back-to-home plays). Gesture-progress-driven predictive
animation (per `PredictiveBackHandler`) is intentionally out of scope for this phase — see
Known limitations.

## Accessibility requirements

- Reduce-motion respected automatically through UI-037 tokens.
- Screen content swaps should be announced by the platforms' focus move into the new
  screen's header (screens own this); `FlashAnimatedScreen` adds no semantics of its own.
- No tap targets introduced by this component.

## Responsive behavior

Contract is layout-agnostic. For future adaptive layouts (UI-034): list-detail on
tablets can bypass the Conversation push entirely (render both panes); the state holder
does not assume phone-only stacking. `conversationId` on the entry is what enables that.

## Dark-mode behavior

Nothing drawn by this component; colors come from `FlashTheme.colors` inside screens.

## Performance considerations

- Stack ops are O(1) amortized list add/remove; depth capped at 10 entries (memory trivially bounded).
- Only the current screen composes (AnimatedContent composes target during transition, old
  screen leaves composition after exit) — no offscreen screen retention cost.
- Transition cost = one fade + one translation per side; no blurs/shadows.

## Implementation notes

- Files:
  - `ui/chat/src/main/java/com/transfer/flash/ui/navigation/FlashNavigation.kt`
  - `ui/chat/src/test/java/com/transfer/flash/ui/navigation/FlashNavigationLogicTest.kt`
  - `app/src/main/java/com/transfer/flash/MainActivity.kt` (host wiring: both `BackHandler`s,
    the four hoisted `LazyListState`s, `tabBottomInset`, the Dev Console overlay layer)
- Dependencies added: **none** (uses compose-animation foundation `AnimatedContent` already
  available transitively; explicitly **no androidx.navigation**).
- Pure logic deliberately free of Compose runtime types; only the bottom of the file
  (`FlashNavigationStateSaver`, `rememberFlashNavigationState`, `FlashAnimatedScreen`) touches
  Compose. `decodeNavigationEntry` is `internal` and reachable from the unit-test source set
  through Gradle friend paths, so saver decoding is tested without instrumentation.
- The shell's `bottomNavTabs` list must stay in sync with `FlashNavigationMath.TabOrder` —
  order drives both the capsule's indicator index and the tab-hop direction.

## Testing checklist

- [x] JUnit4 pure-logic tests: push/back/canGoBack, duplicate-push guard, depth cap with root
      preservation, conversation switch = distinct push, invalid-entry rejection, math helpers
      (`FlashNavigationLogicTest`)
- [x] `back()` falls through a non-home tab to Chats before giving up, and pops before it
      falls through
- [x] `canGoBack` truth table matches what `back()` actually consumes
- [x] `transitionFor` resolves the full direction matrix (push / pop / tab forward / tab
      backward / none)
- [x] `TabOrder` matches the shell bar and drives `isTabRoot`
- [x] `pushed` returns the same instance when the push is ignored; caps depth by dropping the
      entry above the root; `popped` never removes the root
- [x] Saver round-trips the whole stack; decoding drops unknown/invalid entries; a fully
      invalid stack falls back to the ChatList root
- [ ] Physical device: forward/back transitions between ChatList ↔ Conversation (pop mirrors
      the push)
- [ ] Physical device: back at root exits app with system animation
- [ ] Physical device: back from Transfers/Nearby/Settings lands on Chats
- [ ] Physical device: rotate while on Conversation → still on Conversation
- [ ] Physical device: Dev Console close returns to the screen you were on, not to Chats
- [ ] Reduce-motion ON: transitions snap
- [ ] Double-tap a chat row: single push
- [ ] Performance spot-check: transition jank-free during scroll-heavy list handoff

## Known limitations

- No deep links, no nested graphs, no multi-pane coordination yet (UI-034 will define).
- The saver restores the *stack*, not per-screen state: a restored Conversation re-reads the
  repository from scratch, and scroll positions live in the host's `rememberLazyListState`
  (which survives rotation but not process death).
- Gesture-progress-driven predictive back (`PredictiveBackHandler`) is not wired; the pop
  animation plays on release, not under the finger.

## Future improvements

- Optional predictive-back progress integration (`PredictiveBackHandler`) so the mirrored pop
  follows the finger instead of playing on release.
- `SharedTransitionLayout` chat-row avatar → conversation-header avatar as a true shared
  element (experimental API; needs the scope plumbed across `app` and `ui/chat`).
- Re-evaluate navigation-compose adoption at any revisit trigger listed above.

## What makes this Flash?

Navigation is invisible when done right — its Flash identity shows in the details: a
motion-token-driven slide-fade shared with every other surface, reduce-motion honored by
default, a deliberate duplicate-push guard so fast P2P chat-list taps never stack screens,
and a zero-dependency core that stays honest about what a four-screen app actually needs
instead of importing a framework's complexity wholesale.
