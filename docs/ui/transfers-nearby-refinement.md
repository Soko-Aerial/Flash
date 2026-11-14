# Transfers and Nearby — refine the existing components

**Status: ACCEPTED for 2.2.0** · UI-047 / UI-048 · 2026-11-07

The owner approved the compact Nearby and Transfers screens on 2026-11-07.
Physical-device checks remain TABREF-01–04.

### 2026-11-14 — Compact Transfers rows

File name, peer metadata and status each use one line with an ellipsis when needed.
The file badge and insets are smaller, and pairs of 48 dp actions sit side by side when
there is room. Narrow panes and large system text can stack the actions. The row's
accessible description retains the file name and status. Inspecting a transfer shows
the full wrapping name, the transfer metrics and the full failure reason.
[Compose text layout](https://developer.android.com/develop/ui/compose/text/configure-layout).

## Direction and evidence
The owner rejected the workspace redesign and requested rollback, followed by component-by-component refinement of the previous screens, including their different states. Both production files were restored from `f999b5a5` before this design. The committed Hub remains separate. Historical rejected direction: `transfers-nearby-redesign.md`; original native evidence: `transfers-nearby-baseline/`.

Read the actual shared screens, Android/desktop call sites, existing UI-047/048 docs, current state models and logic tests. Official references checked this session: [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) (48 dp targets, appropriate semantics) and [menus](https://developer.android.com/develop/ui/compose/components/menu). The chosen refinement keeps the existing visible actions; the rejected overflow menu is removed.

## Component plan
| Component | Existing design retained | Refinement |
|---|---|---|
| Transfers header | Title, aggregate speed roll, failed/cancelled chips | Heading semantics; chips wrap under large fonts |
| Transfer sections | Incoming, Active, Failed, Cancelled/Declined, History; original ordering and labels | Legible section text; align and size Clear / Pause all / Clear history to 48 dp; headings wrap next to actions |
| File row | Compact badge/content/inline-icon-control anatomy | Name up to two lines; peer and state text readable; multiline failure reasons; actions stack at narrow widths/large fonts to protect content width |
| File badge and progress | Category colors, circles, state-specific progress colors, shimmer, existing interpolation | Keep visual language; no tall neutral cards, new metadata bands or text action rail |
| Nearby header | Title, scan indicator, scan and manual-connect controls in header | Keep Scan network and Connect by IP beside Nearby when the full labels fit. The link icon is optional at narrow widths. Wrap only when the labels need more space. |
| Identity card | Existing device medallion, name and connection details visible | Text takes available width; details wrap with readable contrast |
| Discovered peer | Existing medallion, transport/device/colleague badges and Pair/Chat/Message button | Badges wrap; names and CTA have space; accent fill uses the audited pair of Flash tokens |
| Trusted peer | Verified/encryption icon, device kind and explicit Chat / Call / Verify / Revoke | Owner follow-up: paired tiles with identity above explicit rectangular Chat/Call/Verify/Revoke buttons; radius12 card/radius8 buttons; responsive grid; no overflow menu |
| Loading | Existing component skeletons, transfer brand mark | Placeholders contrast with app background, loading copy and scrollability at short heights |
| Empty / network unavailable / errors | Existing reason-specific panels and available actions | Text contrast, padding, 48 dp buttons, scrollability with large text; no invented retry action |
| Scan outcomes / quiet network hint | Existing running / done / refusal copy and delayed hint | Wrapping actions, preserve delay and result lifetime |
| Consent / confirmation | Existing pairing and send/group cancellation dialogs | Retain implementation and state/action rules; include them in regression matrix |

## State matrix
Transfers: incoming offer; queued with/without swarm wait reason; active send/receive; paused; offline-safe group send; multiple holders; retryable failure; cancelled/declined without Retry; verified/unverified completed; empty; loading; load error; populated while loading; zero-byte and long filenames. Keep bytes/progress math, batch behavior and host callbacks.

Nearby: scanning/populated; paused/populated; no devices scanning/paused; initializing; radios unavailable; discovered untrusted/trusted/colleague; known/unknown device kinds; verified/unverified saved pairings with optional callbacks absent; scan running/done/each unavailable reason; delayed quiet-network hint; pairing request/awaiting/paired/declined/expired. Saved pairing is not a presence signal.

## Layout, accessibility, motion and performance
Keep original app shell, list placement, surfaces, spacing scale and typography family. No new page navigation, counts dashboard, persistent header or theme. Use FlashSpacing/Shapes/Dimensions, no new dependencies. 48 dp controls, heading semantics, full accessible file/device names. Native phone/desktop, dark, narrow and 200% font captures; real TalkBack/keyboard/RTL and network interactions remain a device backlog item. Retain functional progress and original motion with existing reduced-motion handling. Keyed lazy rows, throttling and layout-phase progress reads stay intact.

## Validation and acceptance
Baseline native capture passed before refinement. After implementation: targeted shared JVM + Android-host logic, pairing/cancel semantics, host transfer mapper, APK assembly and desktop native render checks. Keep original vs refined captures for owner comparison. Record exact results in progress/handoff and owed device tests in TEST-BACKLOG. This work is not ACCEPTED until owner review.


## Implementation record
The plan was DESIGNED before edits. The previous broad workspace redesign was restored before applying this pass. `FlashTransfersScreen.kt` retains its header roll/chips, compact row/card layout, category badge colors, progress/shimmer, section order, inline icon controls and confirmation paths. Local refinements wrap names and failure text, raise status readability, stack only the trailing icons at narrow widths/large fonts, make section controls 48 dp, and expose loading placeholders against the background.

`FlashNearbyScreen.kt` retains its identity medallion/details, discovered cards, trust/encryption glyph, device-kind and organization badges, explicit action labels, scan pulse and consent flow. Local refinements protect names from action crowding, wrap badges/actions, give names body typography, and allow short-height/large-font empty panels to scroll. Nearby count now uses discovered peers; saved pairing count is separate. Hosts and domain APIs are unchanged. Header/actions remain inline where width permits, wrapping as a component when needed. The later owner-requested paired tiles use a responsive one/two/three-column grid with explicit rectangular buttons (see owner steering below).

### Native review
Baseline: `transfers-nearby-baseline/`; current: `transfers-nearby-refined/`; rejected historical direction: `workspace-tabs-renders/`. All images render actual Compose screens with fictional data and the existing shell. No network, real organization, phone or database is used. Fixture: `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt`; opt in with `FLASH_TABS_RENDER_DIR=<absolute output directory>`.

Cases include phone/desktop/light/dark/200% text, 320 dp width, short height, optional callbacks absent, all seven queued wait reasons, group offline-safe/multiple-holder states, cancelled and zero-byte completed files, populated while loading, all four scan refusal reasons, running/done scans, delayed quiet hint, and the five visible pairing phases. The fixture waits through the actual quiet-hint delay in that case; no production clock or test-only state override was added.

The original and refined phone layouts were compared. Transfer filenames and errors now wrap while keeping the compact composition; Nearby names no longer compete with four actions. Large text preserves original controls and can scroll. The shared shell still clips bottom-navigation labels at 200% text (HUBWORK-04); it is outside these component edits. Manual behavior, screen-reader, keyboard, RTL, resize during action and performance checks remain TABREF-01..03. No full app lint or real transfer/discovery session was run.

### Acceptance
This is an uncommitted refinement for owner review. Hub remains committed as `f999b5a5`. The rejected broad redraw is historical only. No dependency, storage, transport, service, permission or protocol change; no architectural ADR needed. Keep the existing components as the baseline for follow-up refinements.


## Owner steering — paired-device tiles (DESIGNED before tile implementation)
2026-11-06: owner requested paired devices as tiles and Chat/Call/Revoke as rectangular tile buttons with slightly rounded corners. Apply only to the paired component: Flash surface card, radius12, subtle border, identity/verification/device kind above an explicit action footer. Buttons use radius8, 48 dp minimum height, primary Chat and restrained secondary/ destructive text. Verify remains explicit when applicable. A single column on phones becomes two/three columns when each tile can retain about 360 dp of width; large fonts reduce columns. Preserve the rest of the existing screen refinement and all callbacks. No overflow menu. Grid rows stay lazy and keyed, and missing cells keep the same tile width.


Tile implementation: paired devices alone now render as lazy keyed tile rows. Each card uses `backgroundSurface`, `borderSubtle` and radius12; each action uses radius8 and a 48 dp minimum target. Chat uses accentFill/onAccentFill; secondary controls use a neutral tile surface. Verification/device-kind data and optional Call/Verify gating remain from the original component. Columns reduce as font scale rises. Transfer extension markers now cap their decorative text scaling at 120% so fixed circles do not clip; filename and status text continue to scale normally.


### Build and automated evidence
Locked full gate: `:ui:chat:jvmTest` and `:ui:chat:testAndroidHostTest` filtered to FlashNearby, FlashTransfers, FlashPairingLogicTest and FlashFileTapActionTest; `:app:testDebugUnitTest --tests '*TransfersUiMapperTest*'`; `:app:assembleDebug`; `:desktop:jvmTest --tests '*FlashWorkspaceTabsRenderTest*'`. Full tile/transfer gate: **BUILD SUCCESSFUL in 4m14s, 328 tasks**, shared JVM 45/0/0, Android-host 45/0/0, app mapper 12/0/0, no skips; native fixture passed all 47 cases. Counts read from JUnit XML. Source-edit encoding repair is ERROR-992, tile-row receiver repair ERROR-993. Later native review found button word wrapping in the narrow four-action and 200% states; the final paired sizing pass is recorded below. `git diff --check` passed. Existing deprecation/native-access warnings remain.


Final tile review: phone, desktop, 320 dp and 200% captures now show whole action labels. Normal tiles share a consistent header height; narrow four-action and large-font footers use natural button widths so a whole control wraps instead of splitting Verify/Revoke. Buttons expose the Button role and device-specific action labels. Final paired subset: JVM 26/0/0 and Android-host 26/0/0, zero skips; APK assembled. The final native gate completion and duration are recorded in progress/handoff. The fixture supports `FLASH_TABS_RENDER_PREFIX=nearby` to recapture only the affected tab.


Final paired sizing gate **PASSED: 3m5s, 319 tasks**, exit 0; JVM 26 + Android-host 26, zero failures/errors/skips, APK assembly and native fixture green. All Nearby captures were regenerated after the final width/heading/semantics changes. Phone, desktop, 320 dp and 200% button labels visually checked. Transfers captures remain from the green full gate because its production code did not change after that run. Both tabs remain uncommitted for owner review; HUBWORK/TABREF device limitations remain as documented.


## 2026-11-07 — cross-screen proportion audit and compact tiles (DESIGNED)

The owner found the 124 dp tiles oversized on a laptop and requested fewer visible actions. Compared the current desktop native captures with Hub captures and source sizing across Chats, conversation headers, Hub, Settings and the shared shell. The shared header is 56 dp, Hub people rows use a 64 dp minimum, and the chat list uses 72 dp. Settings uses small group headings and standard 48 dp controls. The paired footer alone consumed 60 dp and transfer section controls added 48 dp plus two separate gaps. Nearby put discovery actions on their own line even at ordinary phone widths. This explains the size difference without a global scale change.

Keep the tile grid. Put identity, rectangular Chat and the three-dot button in one row at normal widths. A normal tile is about 64 dp, with 48 dp interaction areas and a smaller visible Chat fill. Large fonts can put actions below the name. Call stays available in chat. The menu contains Copy device ID, existing Verify pairing where supported, and Revoke pairing. Proposed additions for owner review are Device details, a local nickname and shared files scoped to the device. Use existing Flash icon/menu styling and native menu behavior. Compose menu and Android touch-target guidance checked during the redesign.

Transfers retains its compact file rows and sections. Tighten row/section gaps, keep the 48 dp action targets and add a short description with real moving speed where available. Nearby adds a purpose line above its actual discovery/paired state. Keep scan actions inline when they fit. Make the identity card and device rows use compact icon and padding tokens. Confirm desktop, narrow phone, large fonts and dark theme with actual Compose renders.


| Surface | Source or capture reviewed | Size reference |
|---|---|---|
| Chats and conversation | Chat redesign captures, `FlashChatListRow`, `FlashDimensions` | 72 dp row, 56 dp header, 48 dp composer controls |
| Hub | `hub-workspace-renders/`, `FlashHubPeople` | 64 dp people rows, 48 dp actions |
| Settings and navigation | Android/desktop shell and Settings components | Shared spacing tokens, separate group headings and tap targets |
| Radio and calls | Radio v3 native captures, Gateway components, call overlays | 56 dp headers, 56–64 dp radio controls, 48 dp regular call actions |
| Transfers and Nearby | Previous and current native fixture | Compact row surfaces, 4 dp list gaps, same 48 dp interaction areas |

The compact grid keeps the shared typography and colors. Tiles retain their verification icon, device kind and unverified text. Desktop sees aligned Chat and More actions in a single row. Source and capture review covered the shared screens on Android and desktop. The preview uses fictional devices and does not verify system font settings on physical hardware.

The compact pass is implemented. Native desktop shows a 64 dp tile at normal font scale. The menu was opened by a pointer event on the unverified tile in `transfers-nearby-compact/nearby-menu.png`. The 48 captures cover both tabs and their representative states. Android debug assembly and 83 targeted tests passed. Real laptop scaling, TalkBack, clipboard, and destructive pairing interactions remain TABREF-04. The old full-width action footer is historical evidence from the previous iteration.
