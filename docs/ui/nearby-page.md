# Nearby Page (P4 tab)

**Status:** IMPLEMENTED
**Component ID:** UI-048
**Last updated:** 2026-08-25
**Owner phase:** Phase 8 / App Shell & Pages Integration (`../ui-page-plan.md` P4)
**Depends on:** UI-001/002/037 tokens, UI-030 banner/badge family, UI-032 `FlashPairingDialog`, UI-025 states, UI-046 shell

---

## Component

`FlashNearbyScreen` (`ui/chat/src/main/java/com/transfer/flash/ui/nearby/FlashNearbyScreen.kt`)
+ demo-shaped `NearbyUiState`. Consumes engine C3 composite-discovery flows + C2 trust store at Phase-8
wiring; until then renders demo state shaped identically.

## Purpose

The radio room: who am I on this network, which humans/devices are within range right now, who do I
already trust, and one tap to pair. Per ui-page-plan P4 — identity card, discovered peer rows with
transport glyph + Connect/Pair, PAIR_REQUEST → `FlashPairingDialog`, trusted-peers section,
background-mode toggle slot, debug-only sim entry.

## Research sources

- Apple AirDrop mental model (behavioral study): identity card up top ("this device"), nearby devices as
  tappable tiles/rows, explicit acceptance on BOTH sides before any transfer — matches Flash's
  user-approval-before-write security rule (§19).
- Android Nearby Share / Quick Share flow: continuous scan affordance (subtle animated indicator, never a
  blocking spinner), device rows appear/disappear gracefully, availability toggle visible.
- [Material 3 guidance — connection states](https://m3.material.io/foundations/designing/structure): scanning
  communicated by motion, results by appearance animation; empty scan explains WHY (radios off) not just
  "nothing found".
- In-repo: `FlashPairingFlow.kt` (UI-032 dialog with countdown/code/pulsing dot — done), 
  `FlashNetworkStatusUi.kt` (UI-030 `FlashTransportBadge` + `transportIconSpec/transportLabel` — done),
  `NsdTransport.kt` identity TXT advertise (deviceId/port fields the identity card displays).

## Existing approaches studied

1. **Grid of big tiles** (AirDrop sheet): charming on iPad, wastes vertical space on phones inside a tab
   that also hosts trusted-peer lists.
2. **Map/radar visualizations** (radar sweep widgets in OSS P2P apps): decorative noise; positions are fake
   (no ranging data); fails §23 measure-don't-decorate.
3. **Sectioned list with identity card header** (Quick Share lineage): chosen — densest honest layout,
   reuses our row language, scales 0→50 peers.

## What worked

- Identity-first layout: answering "what am I findable as?" before "who's out there?".
- Persistent subtle scan motion (pulsing dot) instead of spinner — communicates liveness without anxiety.
- Transport badges per peer (LAN vs Wi-Fi Direct glyph) — Flash-specific honesty about the path your bytes take.
- Empty-with-reason states ("Wi-Fi is off") vs empty-with-next-step ("looking… keep both screens open").

## What did not work

- Radar sweeps / fake distance ordering — dishonest data.
- Auto-connect to discovered peers — violates §19 (user approval before any connection write path).
- Blocking full-screen scan modals — tab must stay live while chatting.

## Chosen approach

```text
FlashNearbyScreen(state, callbacks)
├── Header "Nearby" + scan status line ("Scanning…" w/ pulsing dot | "N devices")
├── Identity card: Device-icon medallion · display name · "id ab12cd34 · port 4747"
│   (background-mode toggle row pinned here per page-plan)
├── Section DISCOVERED: peer rows — medallion(Device icon) · name · FlashTransportBadge
│   · trailing Connect button (haptic Tick)
├── Section TRUSTED PEERS: Verified-tinted rows · Revoke text-button (Confirm haptic)
└── Overlays: FlashPairingDialog when state.pairingRequest != null
States: isLoading→skeleton rows ; !radiosAvailable→explainer panel (why+how) ;
        scanning&empty→"Looking for nearby devices…" pulsing panel ; peers>0→sections
```

Models:

```kotlin
data class NearbyIdentityUi(name, deviceIdShort, port)
data class NearbyPeerUi(id, name, transport: FlashNetworkTransport, signalSortKey: Int = 0)
data class NearbyTrustedPeerUi(id, name, verifiedSinceMs)
data class NearbyUiState(
    identity, isScanning, radiosAvailable,
    peers: List<NearbyPeerUi>, trustedPeers: List<NearbyTrustedPeerUi>,
    pairingRequest: FlashPairingRequestUi? = null,
    isLoading = false,
)
```

## Why it was chosen

Every element maps to an existing shipped component (badge/dialog/icons/states) — this page is assembly
plus one new row type, honoring §3 (no new deps) and §34 (no whole-screen work before components exist).
Demo-state shape mirrors future C3/C2 outputs so wiring is substitution.

## Visual specification

| Element | Token |
|---|---|
| Cards | `backgroundSurface` radius12 |
| Identity medallion | accentPrimary @10% bg circle, Device icon accentPrimary (matches UI-025 medallion) |
| Peer medallion | `backgroundSurfaceStrong` circle, Device icon textSecondary |
| Scan pulse | accentPrimary dot, gentle spring scale loop, reduce-motion → static |
| Connect button | pill, accentPrimary bg, textOnAccent label, radiusFull |
| Revoke | captionEmphasis textError |

## Interaction specification

- Tap peer Connect: emits `onConnectClick(peer)`; engine decides connect-vs-pair; dialog mounts on event.
- Long-press peer: none v1.
- Revoke: single tap fires callback (trust store confirms internally later; no double dialog v1).
- Background toggle: Switch row, Tick haptic on change.

## Animation specification

| Effect | Spec |
|---|---|
| Branch swap (radios-off ⇄ loading ⇄ empty ⇄ populated) | `AnimatedContent` targeting a private `NearbyPageState` **enum** from a pure `pageState()` helper — targeting the whole `NearbyUiState` would restart the crossfade on every discovery tick |
| Scan indicator | `rememberInfiniteTransition` 1.0↔1.3 scale, `emphasisMillis` Standard, `RepeatMode.Reverse`; the value is read inside `graphicsLayer` so the header does not recompose per frame; not started at all under reduce-motion (static dot) |
| Peer / trusted row appear-disappear | `Modifier.animateItem(motion.messagePlacementSpec(), motion.messageFadeOutSpec())` keyed by device id — the list item animates itself, because an `AnimatedVisibility(visible = true)` on a freshly composed row can never play |
| Connect / Revoke press | `Modifier.flashPressScale(interactionSource)` with `indication = null`; Connect fires `FlashHaptic.Tick`, Revoke `FlashHaptic.Confirm` (it is destructive) |
| Dialog | FlashPairingDialog internal choreography (UI-032, already built) |
| Reduce-motion | tokens self-collapse; the pulse loop is skipped rather than run at zero duration |

## Gesture specification

None beyond taps; list does not swipe (revoke is explicit button). RTL mirrors.

## Accessibility requirements

- Rows merge semantics: "<name>, <transport label>, Connect button".
- Scan state announced via status line text (not color/motion only).
- 48dp targets; section headers as headings; dialog inherits UI-032 a11y (done there).

## Responsive behavior

Phone-first single column; expanded-width policy deferred to UI-034 pass (page-plan step 6).

## Dark-mode behavior

Token-driven; badges/medallions use audited surface/accent pairs from UI-035.

## Performance considerations

Keyed rows by deviceId; discovery churn animates only entering/exiting rows; pulse loop runs in
graphicsLayer (no recomposition per frame).

## Implementation notes

- Files: `ui/nearby/FlashNearbyScreen.kt` (+models/math/demo sampler);
  tests `ui/nearby/FlashNearbyLogicTest.kt`.
- Dependencies added: **none**. Engine wiring (C3/C2) substitutes demo state at checklist step 4.
- Post-UI-046 fix pass (2026-08-25): content sits inside a `statusBarsPadding()` wrapper while the
  pairing dialog stays its **sibling** (its scrim is an in-tree `Box`, not a platform `Dialog`, so
  insetting it would leave the status-bar strip unscrimmed); discovered/trusted rows use
  `Modifier.animateItem(...)` instead of an `AnimatedVisibility(visible = true)` that could never play;
  `PeerRow` gained the missing 12dp end gutter (the Connect pill was flush to the card edge); the scan
  dot's pulse is read inside `graphicsLayer` and sized from a named `8.dp` constant instead of
  `borderHairline * 6`.
- Shell-motion pass (2026-08-25): `listState: LazyListState` and `bottomInset: Dp` are now parameters
  supplied by `MainActivity`. Hoisting the scroll state is mandatory — `FlashAnimatedScreen` disposes
  the outgoing page on a tab hop, so a page-local state would reset scroll every switch and break
  re-select-to-top. `bottomInset` is added to the `LazyColumn` `contentPadding` bottom so rows scroll
  under the hanging capsule.

## Testing checklist

- [x] JVM: sorting/dedup/count labels/state derivation
- [ ] Compose preview light/dark × empty/scanning/populated/dialog
- [ ] Physical device: live NSD feed post-wiring; pairing round-trip
- [ ] Physical device: discovery churn — rows glide in/out, no full-page crossfade per tick
- [ ] Physical device: scroll position survives a tab hop; re-selecting Nearby scrolls to top
- [ ] Reduced motion: static scan dot, rows snap

## Known limitations

- Demo data until C3/C2 wiring; port/device-id strings placeholder-formatted.
- No permission pre-flight explainer yet (page-plan open item) — radios-off panel covers the common case.
- Scroll position survives rotation but not process death (host `remember`, see UI-046 limitations).

## Future improvements

- Permission pre-flight cards (Nearby Wi-Fi devices / location / notifications).
- Signal-strength ordering once RSSI exposed; distance stays out (no ranging data).

## What makes this Flash?

Other transfer apps make discovery a carnival — radar sweeps, confetti, giant buttons. Flash treats it
like presence in a messenger: quiet identity card, calm rows that fade in like messages, a transport
glyph that tells the truth about the path ahead, and the same teal pulse from the nav bar breathing
under "Scanning…" — the P2P heartbeat made visible.
