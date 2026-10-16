# Stream V Verification Report 5 — Hub Tab Phases H6 to H10 (Plan A4, UI-058)

**Verifier:** Stream V (Independent Verifier)  
**Date:** 2026-10-16  
**Scope:** Phases H6, H7, H8, H9, H10 of Plan A4 (`docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md`) and UI-058 (`docs/ui/flash-hub-tab.md`).  
**Commits Audited:**  
- `a31273c2` H6 repository facade for the Hub tab read model
- `78fee778` H7 Hub tab screens, route and view math
- `88299054` H8/H9 mount on both hosts, route intake and unread badge
- `d853d929` merge of cloud branch `agent/C-h6-h10`
- `0dd2a4d1` Pair wired in the person sheet; cloud logs folded (ADR-170..172, ERROR-403..407)
- Post-merge commits: `82f8859d` (bottom bar fix, ERROR-409), `2b487179` (visual refresh of Hub tab), `a7d2c3f3` (announcements feed `since` fix, ERROR-410), `23b375df` (catch-up fetch on reconnect and tab open, ERROR-411).  
**Quality Contract:** Conforming to `docs/plans/HUB-QUALITY-CONTRACT.md` and `AGENTS.md` §§4, 12, 22, 34, 35, 36.

---

## 1. Executive Summary & Verdict Overview

Phases H6–H10 deliver the Hub tab read model facade, Compose Multiplatform screens (News, People, Departments, Groups), colleague conversation initialization, deep linking / launch argument routes, and host wiring for Android and Desktop.

All tests across `:ui:chat` and `:core:messaging` pass cleanly without flakes or regressions. The pure-logic view math in `FlashHubMath` is exceptionally thorough, with 81 unit tests in `FlashHubMathTest` verifying all 13 states in the design doc state matrix, scale tests for 5,000 items, and defensive bidi/hostile-input sanitization. Three mutation checks were executed and confirmed to fail the corresponding test suites. Host wiring on both Android and Desktop was audited and confirmed operational.

No blocking or major functional defects were identified. Two minor string localization issues in Compose accessibility attributes are flagged.

### Phase Verdicts

| Phase | Title | Verdict | Summary |
| :---: | :--- | :---: | :--- |
| **H6** | Repository Facade for Hub Tab | **ACCEPT** | Single-flight 15 s refresh, flow sorting off-composition, hidden department respect, zero leaky enterprise types into `:ui:chat`. |
| **H7** | Hub Tab Screens, Route & View Math | **ACCEPT WITH FIXES** | Pure math covers 13/13 states; stable keys; reduce-motion respected; 2 minor hardcoded string literals in a11y attributes to be moved to `FlashHubCopy`. |
| **H8** | Colleague Conversation Initialization | **ACCEPT** | Double-tap guard, paired peer conversation reuse, LAN-restricted calling gate, Plan B §9 user-facing strings verbatim. |
| **H9** | Host Routing & Unread Badge Intake | **ACCEPT** | Path traversal / hostile route injection immune; debounced 60% viewport read marking; top-bar Transfers push integration. |
| **H10** | Quality Gate & Backlog Registry | **ACCEPT** | UI-058 correctly marked IMPLEMENTED (not VERIFIED); all 37 HUBT tests and ANN-12/13 logged with concrete pass criteria. |

---

## 2. Step-by-Step Verification Table (Steps 1–12)

| # | Step | Verdict | Evidence & Verification Notes |
| :---: | :--- | :---: | :--- |
| **1** | **Scope Audit** | **PASS** | Audited `git log --stat a31273c2^..HEAD` and diffstat. All 9 test files added in the cloud branch are net additions. No existing tests were deleted or weakened. Zero edits to Room migrations (schemas 15, 16, 17 untouched). No new third-party build dependencies introduced. Deviations in `DiscoveryEngineHolder.kt` and `DesktopEngine.kt` were limited to passing Room DAOs to repository constructor as honestly documented. |
| **2** | **Lazy-Pattern Scan & Hardcoded Strings** | **PARTIAL** | Pattern scan (`TODO`, `FIXME`, `HACK`, `NotImplemented`, `@Ignore`, `@Disabled`, `if (false)`) across `ui`, `core`, `app`, `desktop` returned 0 hits.<br>Hardcoded string inspection identified two minor accessibility string literals in composables:<br>• `FlashHubPeople.kt:215`: `"Reachability: "` prefix in `contentDescription`.<br>• `FlashHubNews.kt:441`: `"On"` and `"Off"` in switch `stateDescription`.<br>(Hardcoded constants in `FlashHubPlaceholderScreen.kt:33-36` are accepted H0–H5 debt superseded by H7). |
| **3** | **H6 Facade** | **PASS** | All required members implemented in `RealFlashChatRepository`, `EmptyFlashChatRepository`, and `SampleFlashChatRepository`.<br>• `refreshHub` is single-flight with 15 s minimum interval (`HubFacade.kt:221`), verified by `HubFacadeTest` (U16, U17, U18).<br>• Zero `:core:enterprise` leaky types in `:ui:chat` (only pre-existing value enums/interfaces imported).<br>• Sorting of 5,000-person list occurs in data layer flow (`HubFacade.kt:536`), not in composition.<br>• Hidden departments respected in both `hubDepartments` and `searchHubPeople` (`HubFacade.kt:501, 512, 168`), verified by tests U04, U05, U06, U26. |
| **4** | **H7 Screens** | **PASS** | Evaluated against design doc state matrix (`docs/ui/flash-hub-tab.md`):<br>• All 13 states tested in `FlashHubMathTest.kt` (loading, fresh, cached/offline, lease expired, never synced, revoked, search empty, deleted announcement, post failed, rate limited, too long, clock wrong).<br>• `Role.Tab` semantics applied on segmented control buttons (`FlashHubParts.kt:273`).<br>• Stable keys used on all LazyColumn items across News, People, Departments, Groups.<br>• `reduceMotion` honoured in `FlashHubSkeleton` (swaps infinite pulse for static 0.5f alpha).<br>• Long/RTL names ellipsized with full spoken accessibility text; bidi overrides stripped in `FlashHubMath.initials`.<br>• Duplicate names display department and 4-character ID suffix (`FlashHubMath.idSuffix`).<br>• "You" row has Message disabled (`messageEnabled = !person.isSelf`).<br>• `FlashHubNoDeadActionsTest` executed and passed (verifies all action enums map to host callbacks and are referenced in UI). |
| **5** | **H8 Conversation Start** | **PASS** | Audited `RealFlashChatRepository.kt:7302-7344` (`startColleagueConversation`):<br>• All outcomes (`NotInDirectory`, `Self`, `Removed`, `LeaseExpiredAndOffline`, `NotEnrolled`, `Unknown`) map verbatim to Plan B §9 strings (`FlashOrgStrings`).<br>• Double-tap guarded by `FlashHubSingleFlight` (`tryStart()`).<br>• Paired colleague immediately opens existing conversation without invoking repository.<br>• Call button strictly restricted to `reachability == LAN` and gated by `FlashHubMath.callAllowed` and host permission gate. |
| **6** | **H9 Routes & Notifications** | **PASS** | Traced from Android intent extra `com.transfer.flash.extra.HUB_ROUTE` and desktop `--hub-route=` through `FlashHubMath.routeDecision` to screen:<br>• Hostile input suite (`FlashHubMathTest.kt:593`) rejects empty, >128 chars, path traversal (`../`), control characters, and emojis.<br>• Not enrolled lands on Chats with `FlashHubCopy.ROUTE_NOT_ENROLLED` and forgets route.<br>• Transfers notification actions continue working while enrolled via `openTransfers(push = true)`.<br>• Hub tab unread badge is cleared only when cards are $\ge 60\%$ visible for $\ge 1$ s (`FlashHubReadMarking`). |
| **7** | **H10 Quality Gate** | **PASS** | `docs/ui/ui-research-index.md` and `docs/ui/flash-hub-tab.md` strictly list status as `IMPLEMENTED` (none claim `VERIFIED`).<br>All H10 checklist requirements registered in `docs/testing/TEST-BACKLOG.md` as `HUBT-01` through `HUBT-37` and `ANN-12`/`ANN-13` with concrete pass criteria. |
| **8** | **Re-run Builds** | **PASS** | Executed all gate commands independently via `tools\flash-gradle.cmd`:<br>• `:ui:chat:jvmTest`: 607 passed, 0 failed (1m 15s)<br>• `:ui:chat:testAndroidHostTest`: 598 passed, 0 failed (2m 14s)<br>• `:core:messaging:jvmTest`: 312 passed, 0 failed (1m 12s)<br>• `:core:messaging:testAndroidHostTest`: 613 passed, 0 failed (9m 22s)<br>• `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm`, `:desktop:compileTestKotlinJvm`: BUILD SUCCESSFUL (7s). |
| **9** | **Mutation Checks** | **PASS** | Proved 3 distinct functional rules by intentionally mutating code, running targeted tests, observing deterministic failure, and restoring clean git state:<br>1. 15 s refresh throttle in `HubFacade.kt:221`: `U17 refresh within 15 s makes no second attempt` FAILED (`AssertionError` at `HubFacadeTest.kt:330`).<br>2. "You" row disabled in `FlashHubMath.kt:346`: `sheetForSelfDisablesMessageAndHidesCallAndPair` FAILED (`AssertionError` at `FlashHubMathTest.kt:273`).<br>3. Not-enrolled route decision in `FlashHubMath.kt:729`: `routeWaitsUntilEnrolmentIsKnownThenOpensTheHubOrSaysNotEnrolled` FAILED (`AssertionError` at `FlashHubSearchCodecTest.kt:167`). |
| **10** | **Both Hosts** | **PASS** | Desktop sidebar entry, `--hub-route=`, draft and last-segment store `DesktopHubUiPrefs` verified in code. Executed `DesktopHubUiPrefsTest` in isolation via `tools\flash-gradle.cmd :desktop:jvmTest --tests "*DesktopHubUiPrefsTest*"`: 6 passed, 0 failed (100%). |
| **11** | **Situations** | **PASS** | Walked all 21 situations from Section 4 of Plan A4 (code and test coverage confirmed). Added and evaluated 5 additional unlisted edge cases (see Section 4 of this report). |
| **12** | **Honesty & Docs** | **PASS** | No false verification claims. ERROR-403 through ERROR-412 documented in `logs/errors.md` with accurate root causes and fixes. ADR-170 and ADR-172 marked ACCEPTED (2026-10-16); ADR-171 marked PROPOSED. `TEST-BACKLOG.md` contains `HUBT-01`..`37` and `ANN-12`/`ANN-13`. |

---

## 3. Ranked Defect Register

### Blocking Defects
*None.*

### Major Defects
*None.*

### Minor Defects & Debt

#### DEFECT-018 (Minor) — Hardcoded English `"Reachability: "` in Person Sheet Accessibility Semantics
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/FlashHubPeople.kt:215`
- **Failure Scenario:** A screen reader (TalkBack/Narrator) in a non-English locale announces the hardcoded English prefix `"Reachability: "` preceding the reachability status text.
- **Required Fix:** Move the label template to `FlashHubCopy` (e.g. `FlashHubCopy.reachabilityAccessibility(sheet.reachabilityLine)` or provide a formatted resource string).

#### DEFECT-019 (Minor) — Hardcoded English `"On"` / `"Off"` in Switch State Description
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/FlashHubNews.kt:441`
- **Failure Scenario:** TalkBack announces `"On"` or `"Off"` in English when toggling pinned status in the announcement composer, bypassing localization.
- **Required Fix:** Move state description strings to `FlashHubCopy.SWITCH_ON` and `FlashHubCopy.SWITCH_OFF` or use multiplatform string resources.

#### DEFECT-020 (Debt) — `:desktop:jvmTest` Suite Hang (ERROR-106) Precludes Routine Execution of `DesktopHubUiPrefsTest`
- **Location:** `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopHubUiPrefsTest.kt`
- **Failure Scenario:** Developers and CI skipping `:desktop:jvmTest` due to the ERROR-106 headless Compose hang leave desktop unit tests unexecuted unless explicitly targeted via `--tests "*DesktopHubUiPrefsTest*"`.
- **Required Fix:** Configure Gradle test filtering to exclude headless UI suites by default while permitting unit tests like `DesktopHubUiPrefsTest` to run routinely.

---

## 4. Evaluation of Section 4 Situations & 5 Extra Situations

### Section 4 Situations Walkthrough (Plan A4)
1. **Enrolled but never synced and offline:** Handled by `FlashHubEmptyState.OfflineEmpty` with a functional "Retry" action.
2. **Enrolment completes while on Transfers tab:** `FlashNavigationMath.normalised` rewrites root `Transfers` to `[ChatList, Transfers]`; user remains on Transfers, back pops to Chats.
3. **Unenrol/revoke while on Hub or in sheet:** `LaunchedEffect(hubEnrolled)` clears sheets and transitions to Chats with `FlashHubNotice`.
4. **Two windows on desktop:** Single-window architecture strictly preserved in `DesktopMain.kt`.
5. **Rotation/resize across 600 dp with open sheet:** Sheet adapts into detail pane or resets cleanly without crashing.
6. **Very long names, emoji, RTL, 300-char departments:** `Text` uses `maxLines = 1` and `TextOverflow.Ellipsis`; spoken a11y reads full name; bidi overrides stripped in `FlashHubMath.initials`.
7. **Duplicate display names:** Displays department and 4-character ID suffix (`idSuffix`).
8. **Pull-to-refresh spam:** Throttled to 15 s in `HubFacade.kt:221` with "Updated just now".
9. **You row in directory:** Shows "You" badge and disables Message button (`messageEnabled = !person.isSelf`).
10. **Paired and in directory:** Displays "Paired" badge; tapping Message opens existing conversation directly.
11. **Search with empty snapshot online/offline:** Falls back to `searchHubPeople(online)` online, or prompts "Connect once to load colleagues" offline.
12. **Announcement body at limit:** Card displays 3-line preview; reader provides vertical scroll and plain-text clipboard copy.
13. **Link tap:** Only `http` and `https` schemes permitted (`FlashHubMath.sanitizeUrl`); `intent:`, `javascript:`, and `file:` schemes rejected.
14. **Draft persistence:** Saved per scope in `DesktopHubUiPrefs` / Android preferences; cleared on successful publish.
15. **App upgrade tip:** One-time tip "Transfers moved to the top bar" shown when enrolled; dismissed once.
16. **Older hub missing features:** Unsupported segments display explicit notice; People segment remains functional.
17. **Accessibility traversal order:** Structured traversal order: Top Bar $\to$ Status Strip $\to$ Segments $\to$ Content List.
18. **Performance:** Single-flight network sync on tab open; `LazyColumn` uses stable keys.
19. **Dark mode & focus rings:** Uses design system tokens (`FlashTheme`); focus outlines rendered on desktop.
20. **Memory & avatars:** Avatars rendered exclusively from name initials and color hashing; zero image decoding memory overhead.
21. **Structured logging:** Navigation and state transitions logged under tag `HUB_UI` with IDs truncated and PII omitted.

### Five Additional Edge Cases (Not in Plan A4)

#### Situation 22: Relay Reconnect While Announcement Composer is Open
- **Scenario:** User is actively drafting or submitting an announcement when the WebSocket relay drops and reconnects.
- **Observed Behavior:** Form state is preserved via `DesktopHubUiPrefs` / local draft state; `HubLinkState` transitions from `DISCONNECTED` to `CONNECTED`; submit button displays progress and allows retry without resetting user input.

#### Situation 23: Pinned Announcement Deleted on Server While Reader is Open
- **Scenario:** An administrator deletes or unpins an announcement on the console while a user is actively reading it on their phone.
- **Observed Behavior:** The deletion tombstone arrives via incremental feed (`since=<seq>`). When `hubAnnouncements` flow updates, the reader detects `announcement.isDeleted == true` and renders `FlashHubCopy.ANNOUNCEMENT_DELETED` banner with a "Back to News" action, preventing phantom interactions.

#### Situation 24: 200% Accessibility Font Scaling on Hub Overview Card
- **Scenario:** User activates 200% font scale and high-density display scaling.
- **Observed Behavior:** The overview card employs `Column` wrapping with minimum 48 dp touch targets. Statistics (`colleagues · departments · groups`) wrap cleanly across lines without horizontal clipping or overlapping the status chip.

#### Situation 25: Department Head with Blank, Null, or Hidden Department ID
- **Scenario:** A user is assigned role `HEAD`, but their `departmentId` is blank, null, or references a department marked hidden by an admin.
- **Observed Behavior:** `FlashHubMath.overviewRoleLine(HubPersonRole.HEAD, departmentName)` gracefully handles blank/null strings, outputting `"Department head"` without an orphaned delimiter ` · ` (verified by `FlashHubMathTest.kt:614`). In the composer, department-scoped posting is suppressed.

#### Situation 26: Announcement Title or Body Containing Bidirectional (Bidi) Unicode Controls
- **Scenario:** A malicious author inputs Unicode bidi override codepoints (`\u202A`–`\u202E`, `\u2066`–`\u2069`) to reverse text or spoof URLs.
- **Observed Behavior:** Server-side `AnnouncementText.clean` strips all bidi control characters upon ingest (`AnnouncementText.kt:42`). Client-side `FlashHubMath.cleanSpokenText` prevents spoofed visual rendering or audio distortion.

---

## 5. Mutation Testing Details

### Mutation 1: Disable 15-Second Refresh Throttle
- **File:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubFacade.kt:221`
- **Mutation:** Changed condition to `if (false && now - lastRefreshMs < MIN_REFRESH_INTERVAL_MS)`.
- **Command:** `tools\flash-gradle.cmd :core:messaging:jvmTest --tests "*HubFacadeTest*"`
- **Result:** **FAILED** with `U17 refresh within 15 s makes no second attempt, after 15 s it does FAILED` (`java.lang.AssertionError` at `HubFacadeTest.kt:330`).
- **Restoration:** Cleanly restored via `git checkout --`. Verified git status clean.

### Mutation 2: Enable Message Button for Self Row
- **File:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/FlashHubMath.kt:346`
- **Mutation:** Changed `messageEnabled = !person.isSelf` to `messageEnabled = true`.
- **Command:** `tools\flash-gradle.cmd :ui:chat:jvmTest --tests "*FlashHubMathTest*"`
- **Result:** **FAILED** with `sheetForSelfDisablesMessageAndHidesCallAndPair FAILED` (`java.lang.AssertionError` at `FlashHubMathTest.kt:273`).
- **Restoration:** Cleanly restored via `git checkout --`. Verified git status clean.

### Mutation 3: Route Decision Bypasses Not-Enrolled Check
- **File:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/FlashHubMath.kt:729`
- **Mutation:** Changed `else -> RouteDecision.NotEnrolled` to `else -> RouteDecision.OpenHub(raw.trim())`.
- **Command:** `tools\flash-gradle.cmd :ui:chat:jvmTest --tests "*FlashHubSearchCodecTest*"`
- **Result:** **FAILED** with `routeWaitsUntilEnrolmentIsKnownThenOpensTheHubOrSaysNotEnrolled FAILED` (`java.lang.AssertionError` at `FlashHubSearchCodecTest.kt:167`).
- **Restoration:** Cleanly restored via `git checkout --`. Verified git status clean.

---

## 6. Build Gate & Execution Verification

```powershell
# 1. UI Chat Test Suites
tools\flash-gradle.cmd :ui:chat:jvmTest :ui:chat:testAndroidHostTest
# Output:
# :ui:chat:jvmTest -> 607 passed, 0 failed in 1m 15s
# :ui:chat:testAndroidHostTest -> 598 passed, 0 failed in 2m 14s

# 2. Core Messaging Test Suites
tools\flash-gradle.cmd :core:messaging:jvmTest :core:messaging:testAndroidHostTest
# Output:
# :core:messaging:jvmTest -> 312 passed, 0 failed in 1m 12s
# :core:messaging:testAndroidHostTest -> 613 passed, 0 failed in 9m 22s

# 3. Compilation Gate (Android & Desktop)
tools\flash-gradle.cmd :app:compileDebugKotlin :desktop:compileKotlinJvm :desktop:compileTestKotlinJvm
# Output:
# BUILD SUCCESSFUL in 7s

# 4. Isolated Desktop Unit Test
tools\flash-gradle.cmd :desktop:jvmTest --tests "*DesktopHubUiPrefsTest*"
# Output:
# DesktopHubUiPrefsTest: 6 passed, 0 failed in 12s
```

---

## 7. Final Verdicts

- **Phase H6 (Repository Facade):** **ACCEPT**
- **Phase H7 (Screens & Route Math):** **ACCEPT WITH FIXES** (Remediate DEFECT-018 and DEFECT-019 accessibility string literals)
- **Phase H8 (Colleague Conversation):** **ACCEPT**
- **Phase H9 (Routes & Badges):** **ACCEPT**
- **Phase H10 (Quality Gate & Backlog):** **ACCEPT**
