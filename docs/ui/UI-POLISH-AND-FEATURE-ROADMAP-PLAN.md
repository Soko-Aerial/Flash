# Flash UI Polish, Oddity Fixes & Feature Implementation Plan

**Date:** 2026-10-06  
**Status:** COMPLETED & VERIFIED (Phases 0, 1, 2, 3 All 100% Complete)  
**Related Documents:**
- [`docs/ui/flash-premium-chat-ui-implementation.md`](flash-premium-chat-ui-implementation.md)
- [`docs/ui/ui-research-index.md`](ui-research-index.md)
- [`docs/migration/ADAPTIVE-UI-PLAN.md`](../migration/ADAPTIVE-UI-PLAN.md)
- [`docs/calling/GROUP-VIDEO-PLAN.md`](../calling/GROUP-VIDEO-PLAN.md)
- [`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md`](../transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md)

---

## 1. Overview & Objective

Flash features an independent, custom design system built with Jetpack Compose and Compose Multiplatform, rejecting stock Material 3 widgets in favor of custom tactile tokens ([`FlashTheme.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTheme.kt)), a tokenized motion system ([`FlashMotion.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashMotion.kt)), and custom navigation chrome ([`FlashBottomNav.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/shell/FlashBottomNav.kt)).

This plan outlines the systematic implementation of:
1. **P0 Fixes:** Dead UI interactions and rough edges found during code audit.
2. **P1 Ergonomics:** Modernization and visual uplift of existing screens and adaptive panes.
3. **P2 Motion Polish:** Micro-interactions, spring physics, and animated visual feedback.
4. **P3 Feature Additions:** Missing high-value communication and transfer flows.

---

## 2. Phase Breakdown & Execution Status

```
┌────────────────────────────────────────────────────────┬──────────┐
│ Phase 0: P0 Bug Fixes & Dead UI Action Resolution      │ 100% DONE│
├────────────────────────────────────────────────────────┼──────────┤
│ Phase 1: High-Priority UI Ergonomics & Screen Upgrades │ 100% DONE│
├────────────────────────────────────────────────────────┼──────────┤
│ Phase 2: Micro-Interactions & Fluid Motion Polish      │ 100% DONE│
├────────────────────────────────────────────────────────┼──────────┤
│ Phase 3: Major New Feature Flows & Visual Capabilities │ 100% DONE│
└────────────────────────────────────────────────────────┴──────────┘
```

---

## Phase 0: P0 Bug Fixes & Dead UI Action Resolution — [100% COMPLETE]

Focus: Eliminate dead buttons, unhandled callbacks, ad-hoc string glyphs, and naming glitches.

### Task 0.1: Camera Attachment Capture Integration
* **File:** [`FlashConversationScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt), [`FlashAttachmentSheet.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentSheet.kt), [`MainActivity.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/MainActivity.kt)
* **Problem:** In [`FlashConversationScreen.kt#L956-L963`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt#L956-L963), `FlashAttachmentType.Camera` maps to `onAttachmentClick()`, which routes to `chatRepository.openAttachmentPicker()`, an empty no-op `{}` in `RealFlashChatRepository.kt`.
* **Solution:**
  - Introduce `rememberFlashCameraCaptureLauncher` in platform shims (`:ui:platform-shims`).
  - On Android, launch `ActivityResultContracts.TakePicture()` saving to a temporary cache URI.
  - On Desktop/JVM, launch webcam photo capture or provide an informative "Camera capture not supported on desktop" notification.
  - Route the captured photo URI directly to `onSendFile`.

### Task 0.2: Re-skin Ad-Hoc Unicode "✕" in Adaptive Detail Panes
* **File:** [`FlashDetailPanes.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/adaptive/FlashDetailPanes.kt)
* **Problem:** Lines 46 and 160 use `FlashText(text = "✕", modifier = Modifier.clickable(onClick = onClose))` instead of a themed icon button.
* **Solution:**
  - Replace with `FlashIconButtonChrome(icon = FlashIcons.Close)` or `IconButton` with `FlashIcon(FlashIcons.Close)`.
  - Ensure minimum 48dp touch target (`FlashDimensions.minTouchTarget`).
  - Add accessibility semantics (`Role.Button`, content description "Close details").

### Task 0.3: Wire Whole-Card Tap on Voice Message Bubble
* **File:** [`FlashMessageBubble.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt), [`FlashVoiceMessageCard.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceMessageCard.kt)
* **Problem:** In [`FlashMessageBubble.kt#L367-L368`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt#L367-L368), `FlashVoiceMessageCard` is passed `onCardClick = {}` and `onActionClick = {}`. Tapping anywhere outside the 36dp play badge is completely inert.
* **Solution:**
  - Allow `FlashVoiceMessageCard`'s card body click (`onCardClick`) to toggle audio play/pause (matching WhatsApp / Telegram behavior where tapping anywhere on the voice note starts playback).
  - Preserve waveform scrubbing touch interception without gesture collisions.

### Task 0.4: Resolve Unpaired Call Participant Roster Names
* **File:** [`FlashCallScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallScreen.kt), [`CallCoordinator.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt)
* **Problem:** If a participant in a group call is not 1:1 paired, their tile header displays a raw 32-character hexadecimal device ID.
* **Solution:**
  - Wire group roster profile names into `peerNameResolver` so participants display their registered group display name.
  - If unresolved, format gracefully as `"Member (${peerId.take(4)})"` instead of a long hex hash.

---

## Phase 1: High-Priority UI Ergonomics & Screen Upgrades — [100% COMPLETE]

Focus: Turn placeholder-style screens into rich, cohesive design system components.

### Task 1.1: Complete Redesign of Adaptive Detail Panes [DONE]
* **File:** [`FlashDetailPanes.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/adaptive/FlashDetailPanes.kt)
* **Implemented:** Full modern redesign featuring category color badge, animated progress bar with velocity shimmer, integrity card (SHA-256 verification), peer card, action dock, and quick actions.

### Task 1.2: Refine Transfers Queue Screen [DONE]
* **File:** [`FlashTransfersScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt)
* **Implemented:** Overloaded "FAILED" section split into "FAILED" (with retry) and "CANCELLED" / "DECLINED" (muted, no retry button). Added top-bar "Clear history" action, and bulk pause/resume buttons in the active section header. Verified by `FlashTransfersLogicTest`.

### Task 1.3: Settings Page Categorization [DONE]
* **File:** [`FlashSettingsScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt)
* **Implemented:** Grouped settings into 5 structured expandable category cards (Appearance & Feedback, Storage & Downloads, Network & Discovery, Calling & Video, Advanced & Group Swarm), search filter, animated chevron, reset defaults.

### Task 1.4: Bottom Navigation Auto-Hide on Scroll [DONE]
* **File:** [`FlashBottomNav.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/shell/FlashBottomNav.kt), [`MainActivity.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/MainActivity.kt)
* **Implemented:** Scroll velocity detection on active list state. Slides hanging capsule down out of view when scrolling down rapidly; slides back up with spring physics on scroll stop or upward scroll.

---

## Phase 2: Micro-Interactions & Fluid Motion Polish — [100% COMPLETE]

Focus: Inject tactility, responsive feedback, and visual delight.

### Task 2.1: Reaction Chip Pop & Burst Animation [DONE]
* **File:** [`FlashReactionChip.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashReactionChip.kt)
* **Implemented:** Animated scale bounce `0.5f` -> `1.25f` -> `1.0f` with `FlashHaptic.Tick`, 4-particle radial puff on add, `reduceMotion` instant snap.

### Task 2.2: Outgoing Message Spring Launch [DONE]
* **File:** [`FlashMessageList.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt)
* **Implemented:** Directional spring pop (`0.85f` scale + vertical delta) for outgoing messages in `FlashMessageList`.

### Task 2.3: Voice Recording Gesture Physics (Slide-to-Trash & Lock) [DONE]
* **File:** [`FlashComposer.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashComposer.kt), [`FlashVoiceRecording.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVoiceRecording.kt)
* **Implemented:** Slide-to-cancel with rubber-band resistance and trash icon morph, drag-up lock to hands-free recording with pause/resume and send, breathing red dot indicator.

### Task 2.4: Transfer Progress Bar Velocity Shimmer [DONE]
* **File:** [`FlashTransfersScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt)
* **Implemented:** Diagonal highlight sweep across filled portion scaling with throughput speed, frozen on pause, flash white on complete.

### Task 2.5: Bottom Nav Tab Hop Directional Tilt & Reselect Pulse [DONE]
* **File:** [`FlashBottomNav.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/shell/FlashBottomNav.kt)
* **Implemented:** `±5°` tilt on hop depending on direction, reselect pulse ring expanding from center.

---

## Phase 3: Major New Feature Flows & Visual Capabilities — [100% COMPLETE]

Focus: Deliver comprehensive user communication features.

### Task 3.1: Native In-App Message Forwarding Sheet [DONE]
* **File:** [`FlashShareTargetSheet.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashShareTargetSheet.kt), [`FlashConversationScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt)
* **Implemented:** Modal bottom sheet with search bar, recent chats & groups, nearby peers, multi-select (up to 5 targets), quote snippet preview, send button. Tested in `FlashShareTargetMathTest`.

### Task 3.2: Composer Attachment Pre-Send Staging Tray [DONE]
* **File:** [`FlashComposer.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashComposer.kt), [`FlashAttachmentStagingTray.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentStagingTray.kt)
* **Implemented:** Horizontal carousel with thumbnails/icons, remove button, "+ Add more", caption input support. Tested in `FlashStagingMathTest`.

### Task 3.3: Per-Conversation Shared Content Viewer [DONE]
* **File:** [`FlashSharedContentSheet.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSharedContentSheet.kt), [`FlashSharedContentMath.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSharedContentMath.kt)
* **Implemented:** `FlashSharedContentSheet` with 4 tabs (Media 3-col grid with viewer, Files list with extension badges, Audio inline playback, Links with jump-to-chat). Tested in `FlashSharedContentMathTest`.

### Task 3.4: Pinned Messages Banner [DONE]
* **File:** [`FlashPinnedMessageBanner.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashPinnedMessageBanner.kt), [`FlashPinnedMessageMath.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashPinnedMessageMath.kt), [`FlashConversationScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt)
* **Implemented:** Pin/unpin action in context menu, header banner with pin icon, preview, unpin button, smooth scroll + 700ms pulse glow highlight. Tested in `FlashPinnedMessageMathTest`.

### Task 3.5: Real-Time Audio Speaking Ripple in Calling UI [DONE]
* **File:** [`FlashCallScreen.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallScreen.kt), [`FlashGroupVideoGrid.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGrid.kt), [`FlashCallRippleMath.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallRippleMath.kt)
* **Implemented:** Outer & middle glow waves modulated with audio energy (silence rests at 1.0f..1.06f, speaking expands to 1.22f), active speaker border ring in group video grid. Tested in `FlashCallRippleMathTest`.

### Task 3.6: Swarm Transfer Block Availability Grid Map [DONE]
* **File:** [`FlashSwarmPieceMap.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwarmPieceMap.kt), [`FlashSwarmPieceMapMath.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwarmPieceMapMath.kt), [`FlashFileMessageCard.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashFileMessageCard.kt), [`FlashDetailPanes.kt`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/adaptive/FlashDetailPanes.kt)
* **Implemented:** Canvas micro-block matrix (verified green, downloading pulsing cyan, peer available amber, missing dark gray), integrated into `FlashFileMessageCard` and `FlashDetailPanes`. Tested in `FlashSwarmPieceMapMathTest`.

---

## 3. Testing & Verification Requirements

Per `AGENTS.md` rules:
1. **Unit & Logic Tests:**
   - Every new pure helper (e.g. `FlashDetailPanesMath`, `FlashStagingMath`) must have accompanying JVM tests in `commonTest`.
2. **Build Verification:**
   - `./gradlew :ui:chat:jvmTest`
   - `./gradlew :ui:callui:jvmTest`
   - `./gradlew :app:compileDebugKotlin`
   - `./gradlew :desktop:compileKotlinJvm`
3. **Motion & Accessibility Gates:**
   - Verify every new animation respects `FlashTheme.motion.reduceMotion` (snaps to 0 duration when enabled).
   - Verify minimum touch targets (`48.dp`) and accessibility semantics descriptions.
4. **Device Testing:**
   - Document device tests owed in `docs/testing/TEST-BACKLOG.md`.
