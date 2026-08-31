# PHASE-22 — Adaptive desktop screens (list-detail arrangement)

**Blocked by:** PHASE-21 (desktop app shell), **D8** (answer still `_pending_`)
**Risk:** MEDIUM — no new composables; just layout arrangement of already-shared screens
**Duration estimate:** 1–2 hours (wiring `FlashAdaptiveTwoPane` into `DesktopShell`)

---

## What this phase is for

Desktop windows are wide enough to show **list + detail** side by side, but the current
`DesktopShell` (PHASE-21, Option B) shows only one tab at a time in a narrow single-pane
layout. This phase arranges the existing shared composables (`FlashChatListScreen`,
`FlashConversationScreen`, `FlashNearbyScreen`, `FlashTransfersScreen`,
`FlashSettingsScreen`) into a **list-detail two-pane layout** for expanded windows, using
the already-shipped `FlashAdaptiveTwoPane` composable from `ui:chat` (UI-034).

**What this phase does:**
1. Wraps `DesktopShell`'s tab content in `FlashAdaptiveTwoPane` so wide windows show list
   + detail side by side.
2. Adapts the tab bar — bottom bar on compact/medium, side bar on expanded.
3. Adds `FlashConversationScreen` as the detail pane when a conversation is selected.
4. Adds a `NearbyDetailPane` (peer info + send file) and `TransfersDetailPane` (selected
   transfer details) for parity.
5. Unit-tests the desktop-specific breakpoint/pane logic (if any; the shared math is
   already tested in `FlashAdaptiveLogicTest.kt`).

**What this phase does NOT do:**
- Does NOT create new composable screens — it arranges existing ones.
- Does NOT change the Android `FlashShell` or `MainActivity.kt`.
- Does NOT implement the "devices+transfers" mockup from the old plan §15 (see D8).
- Does NOT add adaptive behavior to `ui:chat`'s `FlashAdaptiveLayouts` — that module
  already ships the breakpoint + two-pane math; this phase only uses it.

---

## Preconditions

1. [ ] PHASE-21 is committed and `DesktopShell` composes the four shared screens in a
      single-tab layout.
2. [ ] `FlashAdaptiveTwoPane`, `FlashWindowSizeClass`, `FlashAdaptiveMath`, and
      `rememberFlashWindowSize` exist in `ui:chat` (UI-034, already shipped).
3. [ ] `FlashAdaptiveLogicTest.kt` passes (already ships breakpoint/pane tests).
4. [ ] Working tree is clean for `desktop/` and `ui/` (no unrelated changes).
5. [ ] `./gradlew :desktop:compileKotlinJvm --no-configuration-cache` succeeds (baseline).

---

## Verified starting state

### `FlashAdaptiveLayouts.kt` (shipped, in `ui:chat`)

See [`ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt`](/C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/main/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLayouts.kt).

Key symbols:

| Symbol | Kind | Purpose |
|---|---|---|
| `FlashWindowSizeClass` | enum | `Compact` (<600dp), `Medium` (600–840dp), `Expanded` (≥840dp) |
| `FlashAdaptiveMath` | object | `windowSizeForWidth(dp)`, `isTwoPaneAllowed(size)`, `listPaneWeight(size)`, `detailPaneWeight(size)` |
| `rememberFlashWindowSize()` | composable | `BoxWithConstraints`-based; returns current `FlashWindowSizeClass` |
| `FlashAdaptiveTwoPane(listPane, detailPane, modifier)` | composable | Expanded: side-by-side with hairline divider; Compact/Medium: single pane |

### `FlashAdaptiveLogicTest.kt` (shipped, 8 test cases)

See [`ui/chat/src/test/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt`](/C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/test/java/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt).

Tests cover:
- Breakpoint boundaries (599.9→Compact, 600→Medium, 840→Expanded, 1280→Expanded)
- Edge cases (0dp, negative → Compact)
- `isTwoPaneAllowed` (Expanded → true, Medium/Compact → false)
- Pane weights (Compact/Medium → 1f/1f; Expanded → 0.38f/0.62f)
- Weight sum = 1.0f

### D8 dependency

**D8** is still `_pending_` in [`DECISIONS.md`](DECISIONS.md#L216). Options:

- **Option A (RECOMMENDED):** Desktop ships the existing chat UI, adaptively laid out.
  Phase 22 becomes "arrange existing composables for wide windows". This is the path
  documented here.
- **Option B:** Build the devices+transfers desktop UI from the old plan §15 mockups.
  This is **new feature work**, not migration, and should be its own project after
  Phase 23.

**The agent must NOT pick Option B.** If D8 is still `_pending_`, this phase proceeds
with Option A (the recommended path) and documents the decision. If D8 is answered B,
this phase is blocked and must be re-scoped.

---

## The change

### Step 1 — Add `FlashAdaptiveTwoPane` to `DesktopShell`

Modify `DesktopShell.kt` (in `:desktop` `jvmMain`) to wrap tab content in
`FlashAdaptiveTwoPane` for wide windows.

```kotlin
// Inside DesktopShell's Scaffold content area, replace the single-tab Box:

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.transfer.flash.ui.adaptive.FlashAdaptiveMath
import com.transfer.flash.ui.adaptive.FlashAdaptiveTwoPane
import com.transfer.flash.ui.adaptive.rememberFlashWindowSize
import com.transfer.flash.ui.icons.FlashIconSpec
import com.transfer.flash.ui.icons.FlashIcons
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.shell.FlashBottomNavItem

// --- Tab model (desktop-wide, defined once in DesktopShell.kt) ---
enum class Tab(val destination: FlashDestination, val label: String, val icon: FlashIconSpec) {
    Chats(FlashDestination.ChatList, "Chats", FlashIcons.Chat),
    Transfers(FlashDestination.Transfers, "Transfers", FlashIcons.Transfer),
    Nearby(FlashDestination.NearbyDevices, "Nearby", FlashIcons.Nearby),
    Settings(FlashDestination.Settings, "Settings", FlashIcons.Settings),
}

// Item shape for both the vertical sidebar and the bottom nav.
// FlashBottomNavItem (ui:chat) already carries destination/icon/label —
// TabDef is the desktop-side vertical variant.
data class TabDef(
    val tab: Tab,
    val destination: FlashDestination = tab.destination,
    val icon: FlashIconSpec = tab.icon,
    val label: String = tab.label,
)

// Maps a FlashDestination (bottom-nav tap) back to a Tab.
fun tabFromDestination(d: FlashDestination): Tab =
    Tab.entries.first { it.destination == d }

var currentTab by remember { mutableStateOf(Tab.Chats) }

val tabs: List<TabDef> = Tab.entries.map { TabDef(it) }

val bottomNavItems: List<FlashBottomNavItem> = Tab.entries.map {
    FlashBottomNavItem(
        destination = it.destination,
        icon = it.icon,
        label = it.label,
        badgeCount = null,
    )
}

// --- State for the selected detail item ---
var selectedConversationId by remember { mutableStateOf<String?>(null) }
var selectedTransferItem by remember { mutableStateOf<FlashTransferItemUi?>(null) }
var selectedNearbyPeer by remember { mutableStateOf<NearbyPeerUi?>(null) }

FlashAdaptiveTwoPane(
    listPane = {
        // Tab content (the current single-tab Box)
        Box(Modifier.fillMaxSize()) {
            // Show the active tab — same logic as the current single-pane DesktopShell
            when (currentTab) {
                Tab.Chats -> FlashChatListScreen(
                    state = chatListState,
                    onConversationClick = { id -> selectedConversationId = id },
                    onSearchClick = {},
                    bottomInset = 0.dp,
                )
                Tab.Transfers -> FlashTransfersScreen(
                    state = transfersUi,
                    onPauseResumeClick = { /* pause/resume from engine */ },
                    onCancelClick = { /* cancel from engine */ },
                    onRetryClick = { /* retry from engine */ },
                    onHistoryOpen = { item -> selectedTransferItem = item },
                    onFindDevices = {},
                    bottomInset = 0.dp,
                )
                Tab.Nearby -> FlashNearbyScreen(
                    state = nearby,
                    onPairClick = { /* no pairing on desktop yet */ },
                    onChatClick = { peer -> selectedConversationId = peer.id },
                    onRevokeClick = {},
                    onChatTrustedClick = { peer -> selectedConversationId = peer.id },
                    bottomInset = 0.dp,
                )
                Tab.Settings -> FlashSettingsScreen(
                    model = settingsModel,
                    onThemeModeSelected = {},
                    onDynamicAccentChanged = {},
                    onHapticsChanged = {},
                    onBackgroundTransfersChanged = {},
                    bottomInset = 0.dp,
                )
            }
        }
    },
    detailPane = when {
        selectedConversationId != null -> {
            { FlashConversationScreen(
                state = /* conversation state from chatRepository */,
                onBack = { selectedConversationId = null },
                onSendText = {},
                onAttachmentClick = {},
            ) }
        }
        selectedTransferItem != null -> {
            { TransferDetailPane(item = selectedTransferItem!!, onClose = { selectedTransferItem = null }) }
        }
        selectedNearbyPeer != null -> {
            { NearbyDetailPane(peer = selectedNearbyPeer!!, onClose = { selectedNearbyPeer = null }) }
        }
        // Default: show a "select an item" placeholder
        else -> { { PlaceholderDetailPane() } }
    }
)
```

> **Note:** The `detailPane` content above is illustrative. The exact composables used
> depend on which shared screens accept detail-mode parameters. For the first iteration,
> only `FlashConversationScreen` is a realistic detail pane; transfers and nearby peers
> get compact info cards until the full detail views are built.

### Step 2 — Adapt tab bar for window width

Replace the fixed bottom bar with a width-aware bar:

```kotlin
val sizeClass = rememberFlashWindowSize()
val currentDestination = currentTab.destination

if (FlashAdaptiveMath.isTwoPaneAllowed(sizeClass)) {
    // Side bar (left edge) — vertical tab list
    DesktopSideBar(
        tabs = tabs,
        selectedTab = currentTab,
        onTabSelected = { currentTab = it },
    )
} else {
    // Bottom bar (existing pattern)
    FlashBottomNav(
        items = bottomNavItems,
            selectedTab = currentDestination,
            onTabSelected = { currentTab = tabFromDestination(it) },
    )
}
```

> **Note:** `DesktopSideBar` is a new composable (see Step 3). `FlashBottomNav` already
> exists in `ui:chat` (`ui/shell/FlashBottomNav.kt`) and is available to `:desktop`.
>
> **Tab model:** `currentTab` is the `Tab` enum (defined in Step 1, matching the
> `when (currentTab)` branches). `tabs` is `List<TabDef>` (also Step 1).
> `bottomNavItems` is `List<FlashBottomNavItem>` keyed by `FlashDestination` — the same
> shape as the Android shell's bottom-nav items. `tabFromDestination(d)` maps a
> bottom-nav tap back to a tab (defined in Step 1).

### Step 3 — Create `DesktopSideBar`

A simple vertical tab bar for the expanded-width layout. Lives in `DesktopShell.kt`
or a new `DesktopSideBar.kt` in the `:desktop` module.

The `Tab` enum, `TabDef`, `tabs`, and `bottomNavItems` are declared in Step 1; the
sidebar only consumes them:

```kotlin
@Composable
fun DesktopSideBar(
    tabs: List<TabDef>,
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sidebarWidth = 200.dp // desktop-only constant; do not add to ui:theme
    Column(
        modifier = modifier
            .width(sidebarWidth)
            .fillMaxHeight()
            .background(FlashTheme.colors.backgroundSurface)
            .padding(FlashSpacing.space8),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4),
    ) {
        tabs.forEach { tabDef ->
            val isSelected = tabDef.tab == selectedTab
            val bg by animateColorAsState(
                targetValue = if (isSelected)
                    FlashTheme.colors.backgroundSurfaceStrong
                else
                    FlashTheme.colors.backgroundSurfaceSubtle,
                label = "sidebarTabBg",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(FlashShapes.button)
                    .background(bg)
                    .clickable { onTabSelected(tabDef.tab) }
                    .padding(FlashSpacing.space12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8),
            ) {
                FlashIcon(icon = tabDef.icon, size = FlashDimensions.iconMd)
                FlashText(
                    text = tabDef.label,
                    style = FlashTheme.typography.bodyDefault,
                    color = if (isSelected) FlashTheme.colors.textPrimary
                        else FlashTheme.colors.textSecondary,
                )
            }
        }
    }
}
```

### Step 4 — `DesktopSideBar` dimension

The sidebar is desktop-only (no mobile equivalent), so its width is defined **inline**
inside `DesktopSideBar` (already in Step 3):

```kotlin
// Inside DesktopSideBar (Step 3)
val sidebarWidth = 200.dp // desktop-only constant; do not add to ui:theme
```

> **Note:** Do not modify `FlashDimensions.kt` (ui:theme) for desktop-only tokens.
> If the sidebar ever becomes a shared cross-platform component, lift it later.

### Step 5 — Create detail pane composables

Minimal detail panes for the three non-chat tabs:

```kotlin
@Composable
private fun TransferDetailPane(
    item: FlashTransferItemUi,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FlashText(text = "Transfer Details", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "×",
                modifier = Modifier.clickable(onClick = onClose),
                style = FlashTheme.typography.bodyDefault,
            )
        }
        FlashText(text = "File: ${item.fileName}")
        FlashText(text = "Peer: ${item.peerName}")
        FlashText(text = "Progress: ${FlashTransfersMath.progressFraction(item.bytesDone, item.bytesTotal)}")
        FlashText(text = "Speed: ${FlashTransfersMath.formatSpeed(item.speedBytesPerSec)}")
    }
}

@Composable
private fun NearbyDetailPane(
    peer: NearbyPeerUi,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FlashText(text = "Peer Details", style = FlashTheme.typography.headingMedium)
            FlashText(
                text = "×",
                modifier = Modifier.clickable(onClick = onClose),
                style = FlashTheme.typography.bodyDefault,
            )
        }
        FlashText(text = "Name: ${peer.name}")
        FlashText(text = "Transport: ${peer.transport}")
    }
}

@Composable
private fun PlaceholderDetailPane() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        FlashText(
            text = "Select an item",
            style = FlashTheme.typography.bodyDefault,
            color = FlashTheme.colors.textSecondary,
        )
    }
}
```

---

## Verification gate

Run **after** implementing Steps 1–5. The `:desktop` module must compile; the existing
Android build must not regress.

| Check | Command | Expected result |
|---|---|---|
| Desktop compiles | `./gradlew :desktop:compileKotlinJvm --no-configuration-cache` | `BUILD SUCCESSFUL` |
| Android still compiles | `./gradlew :app:assembleDebug --no-configuration-cache` | `BUILD SUCCESSFUL` |
| ui:chat desktop compiles | `./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache` | `BUILD SUCCESSFUL` |
| ui:chat Android compiles | `./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache` | `BUILD SUCCESSFUL` |
| Adaptive tests pass | `./gradlew :ui:chat:test --no-configuration-cache` | `FlashAdaptiveLogicTest` passes |
| No `android.*` in desktop src | `Select-String "^import android\." desktop/src -Recurse` | 0 matches |
| No `:app` dependency | `Select-String -Pattern ':app' desktop/build.gradle.kts` | 0 matches |
| `FlashAdaptiveTwoPane` used | `Select-String "FlashAdaptiveTwoPane" desktop/src -Recurse` | ≥1 match |
| `rememberFlashWindowSize` used | `Select-String "rememberFlashWindowSize" desktop/src -Recurse` | ≥1 match |

---

## Do NOT

- **Do NOT** modify `ui/chat/adaptive/FlashAdaptiveLayouts.kt` — the shared adaptive
  primitives are already shipped. This phase only consumes them.
- **Do NOT** add new dependencies to `:desktop` — it already depends on `ui:chat` (which
  transitively provides the adaptive module).
- **Do NOT** change the Android `FlashShell` or `MainActivity.kt` — adaptive desktop
  layout is desktop-only.
- **Do NOT** implement the devices+transfers mockup from old plan §15 unless D8 is
  answered B and the phase is re-scoped.
- **Do NOT** add native distribution packaging (`packageMsi`/`packageDeb`) — that is
  a future polish step.
- **Do NOT** add `desktopTest` source sets — this phase has no tests yet (the shared
  adaptive math is already tested in `FlashAdaptiveLogicTest.kt`).

---

## Rollback

If the adaptive layout breaks compilation:

1. **Revert `DesktopShell.kt`:** `git checkout dev -- desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`
2. **Verify desktop build:** `./gradlew :desktop:compileKotlinJvm --no-configuration-cache`
3. **Verify Android build:** `./gradlew :app:assembleDebug --no-configuration-cache`

The sidebar width is defined inline in `DesktopShell.kt` (Step 4) and is **not** added
to `ui:theme`, so no `ui:theme` rollback is needed. If you ever lift it to `ui:theme`,
revert `FlashDimensions.kt` before verifying:

1. **Revert `FlashDimensions.kt`:** `git checkout dev -- ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashDimensions.kt`
2. **Verify ui:theme build:** `./gradlew :ui:theme:compileKotlinDesktop --no-configuration-cache`

---

## Log entry for `logs/migration.md`

```markdown
## 2026-09-0X — PHASE-22: Adaptive desktop screens (list-detail arrangement)

- **Agent/model:** Copilot (autonomous)
- **Commit:** ecb0c63
- **Decisions relied on:** D8=_pending_ (proceeded with Option A recommendation —
  desktop ships existing chat UI adaptively)

### Change
Wrapped `DesktopShell` tab content in `FlashAdaptiveTwoPane` so expanded windows
(≥840dp) show list + detail side by side. Added `DesktopSideBar` (vertical tab bar
for expanded width), `TransferDetailPane`, `NearbyDetailPane`, and
`PlaceholderDetailPane`. The bottom tab bar is retained for compact/medium widths.
No changes to `ui:chat` adaptive primitives or the Android shell.

### Files changed
- **Modify:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`
  (or `DesktopShell.kt` if extracted) — wrap content in `FlashAdaptiveTwoPane`,
  add width-aware tab bar, add detail pane composables.

### Verification
- `./gradlew :desktop:compileKotlinJvm --no-configuration-cache` — PASS
- `./gradlew :app:assembleDebug --no-configuration-cache` — PASS
- `./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache` — PASS
- `./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache` — PASS
- `./gradlew :ui:chat:test --no-configuration-cache` — PASS (FlashAdaptiveLogicTest)
- No `android.*` or `:app` dependency in `desktop/src/` — PASS
- `FlashAdaptiveTwoPane` and `rememberFlashWindowSize` used in `desktop/src/` — PASS

### Deviations from the phase file
<To be filled in during execution.>

### Known issues
- Detail panes for transfers and nearby peers are minimal info cards, not full
  detail views. Conversation is the only realistic detail pane.
- `DesktopSideBar` is a new composable in `:desktop`; if it becomes useful for
  the Android tablet layout, it should be lifted to `ui:chat`.
- The D8 decision is still `_pending_`. If D8 is answered B, this phase is
  blocked and must be re-scoped to build the devices+transfers desktop UI.

### Next step
PHASE-23 — interop matrix (full 4-way compatibility verification)
```

---

## D8 contingency

### If D8 is answered A (Option A, recommended)
Proceed as documented above. This is the expected path.

### If D8 is answered B (Option B — devices+transfers desktop UI)
**This phase is blocked.** The scope changes from "arrange existing composables" to
"build new devices+transfers UI from the §15 mockups". This is new feature work, not
migration, and should be its own project after Phase 23. The migration continues with
Phase 23 (interop matrix) on the Option A desktop shell, and the new work is tracked
separately.

### If D8 is still `_pending_`
Proceed with Option A as documented. The phase file explicitly states the assumption
and the commit message should note it. Do not wait for the human to answer.