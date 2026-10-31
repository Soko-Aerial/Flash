# Current Handoff — Stream U (UI)

## Current branch
`agent/opt-ui` (based on `dev` at `979e9c75`)

## Last verified commit
`3d5fa942` (`perf(voice): decouple 10 Hz waveform updates to draw phase and memoize bucketed waveform (OPT-O05-06, OPT-O05-07)`)

## Current phase
Stream U (UI) Performance Optimization Programme — Batches 1 & 2 Completed

## Verified Gate Test Results
1. `:ui:theme:jvmTest` — PASSED (`BUILD SUCCESSFUL in 12s`, 14 actionable tasks)
2. `:ui:chat:jvmTest` — PASSED (`BUILD SUCCESSFUL in 48s`, 47 actionable tasks)
3. `:ui:chat:testAndroidHostTest` — PASSED (`BUILD SUCCESSFUL in 54s`, 71 actionable tasks, runs full Compose-side logic suites)

## Working Features & Completed Optimizations
- **O03-03**: Draft state hoisted out of root `FlashConversationScreen` scope; 0 root recompositions per keystroke.
- **O03-04**: Compose compiler stability configuration file `ui/chat/compose_compiler_config.conf` created.
- **O03-01 / O03-11 / O03-12**: Draw phase alpha animations with `!reduceMotion` guards in `FlashShareTargetSheet` and `HcComponents`.
- **O03-05**: Memoized per-card callbacks in `FlashMessageBubble` attachment/voice items.
- **O03-06**: Replaced $O(N)$ `HashSet` equality check with `initialHeadId` in `FlashMessageList`.
- **O03-08**: Hoisted quick reactions list to top-level constant.
- **O03-13**: Memoized `BorderStroke` allocations in incoming message bubbles.
- **O03-02**: Deferred reaction chip puff & border reads into draw phase.
- **O04-05**: Eliminated `SwipeToDismissBoxState` overhead on desktop and selection mode.
- **O04-06**: Day separator differentiation (`_sep`) in `flashMessageContentType` for LazyColumn slot reuse.
- **O24-05**: Optimistic switch thumb toggle in `FlashSettingsScreen`.
- **O24-06**: Sheet entrance network load deferral in `FlashBrowseOrgGroupsSheet`.
- **O24-07**: In-memory aspect ratio cache (`FlashImageRatioCache`) to reserve single-image bounds and prevent CLS.
- **O03-14**: Splash canvas allocation elimination via `drawWithCache` and lazy path/brush in `FlashBrandAnimation`.
- **O03-16**: Eliminated `IntrinsicSize.Min` double text measurement pass in `FlashQuotedReplyCard` via `drawBehind`.
- **O03-17**: Replaced `BoxWithConstraints` with custom single-pass `Layout` in `FlashBottomNav`.
- **O25-05**: Memoized `sortedPeers` in `FlashNearbyScreen`.
- **O04-07**: Distance-bounded pre-jump in `FlashConversationScreen.jumpToMessage`.
- **O24-03**: Removed `.animateContentSize()` from root IME-padded Column in `FlashComposer`.
- **O24-04**: Auto-scroll to bottom on keyboard (IME) appearance in `FlashMessageList`.
- **O24-01 & O24-02**: Optimistic local echo overlay (`Pending` outbox row) and staging tray sending spinner (`isSending = true`).
- **O05-06 & O05-07**: Decoupled 10 Hz voice playback progress to draw phase, 1 Hz label updates via `derivedStateOf`, and `FlashWaveformCache` LRU for bucketed amplitudes.

## Out-of-Scope Items for Handoff to Other Streams
1. **OPT-O03-07** (`GroupFileSender` remember key in `app/src/main/java/com/transfer/flash/MainActivity.kt:1670`):
   - Belongs strictly to Stream G (`app/**`). Must remove `discoveredEndpoints` from remember key and supply dynamic provider.
2. **OPT-O03-09** (Trusted peer regex mapping in `app/src/main/java/com/transfer/flash/MainActivity.kt:1495-1508`):
   - Belongs strictly to Stream G (`app/**`). Must wrap `trustedPeerRoster` computation in `remember(trustedPeers)`.
3. **OPT-O25-05 (Video Grid Part)** (`FlashGroupVideoGrid.kt:78-84`):
   - Belongs to Stream C (`ui/callui/**`). Participant sorting at 30 FPS to be memoized in Stream C.
4. **OPT-O05-07 (Repository CSV String Split)** (`RealFlashChatRepository.kt:7557`):
   - Belongs to Stream M (`core/messaging/**`). `decodeVoiceMeta` in repository to be memoized or parsed with zero-allocation integer scanner.
5. **Stability Config Wiring** (`composeCompiler { stabilityConfigurationFiles.add(...) }`):
   - File created at `ui/chat/compose_compiler_config.conf`. Reserved for integrator merge into `build.gradle.kts` per boundary rules.

## Tests Backlog
- `PERF-U01`: Physical device scroll check during active file transfer (Macrobenchmark / Choreographer frame pacing). See `logs/streams/ui/test-backlog.md`.
