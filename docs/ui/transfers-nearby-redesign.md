# Transfers and Nearby — rejected workspace refresh

**Status: REJECTED / ROLLED BACK** · UI-047 / UI-048 · 2026-11-06

> Owner rejected these images on 2026-11-06. Production screen changes were restored to `f999b5a5` before a component-by-component refinement. This document and its renders are retained as historical evidence only. Current direction: `transfers-nearby-refinement.md`.

## Intent and evidence
Continue the Hub workspace visual direction using Flash typography, surfaces, accentFill, spacing and icons. The owner specifically requested minimal professional Transfers and paired devices. Read the existing transfers-page.md, nearby-page.md, UI research index and actual shared Compose implementations plus Android/desktop call sites. Those older docs describe demo-only wiring; both screens are now wired by MainActivity and DesktopShell. This document supersedes their visual specification, not their feature contracts.

Official references checked 2026-11-06: [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults) (48 dp interactive targets, meaningful semantics) and [Compose menus](https://developer.android.com/develop/ui/compose/components/menu) (anchored menu for secondary actions). Existing Hub workspace and FlashConversationMenu are local visual/implementation references. No new networking/platform API, dependency or theme is needed.

## Observations and alternatives
Nearby currently compresses name, verification, device badge, Chat, Call, Verify and Revoke onto one phone row. Saved pairings are counted as nearby despite having no reachability field. Transfers squeezes filename/progress between a colorful medallion and up to two controls; one-line error/status text conceals reasons. Both stretch to the desktop viewport.

Rejected tile grids: waste room and encourage invented status. Rejected dashboard counters: repeat section counts. Chosen: centered sectioned lists with quiet separators, generous names and explicit state; retain current information architecture. Primary actions stay visible, paired-device secondary actions use a labeled overflow menu. Transfer actions remain directly visible because pause/cancel/retry are time-sensitive.

## Design and supported actions
Nearby: title and honest scanning state, scan/manual-connect actions on a separate wrapping row, compact This device identity with optional connection details. Available devices retain Pair/Message/Chat from the existing trust/colleague predicate. Paired devices get full-sized names, explicit Verified / Not verified plus real device kind, Chat primary and More actions (Call only when callback exists, Verify only for unverified supported pairs, Remove pairing via existing revoke callback). No online badge or fake reachability. Preserve pairing dialog, verification haptics, manual connection, quiet hint, scan outcomes, network-off/loading/empty logic.

Transfers: title plus a concise real queue summary and aggregate throughput, sentence-case section labels with counts, neutral file badges, two-line filenames, wrapping status/error text. Put progress and controls below identity so names can use phone width. Show progress only for active/queued/paused work. Keep incoming accept/decline, pause/resume and batch controls, send cancellation confirmation, retry/dismiss with retryable partition, clear history/failures, completed open/share. Do not invent a retry callback for a screen-load error. Preserve byte accounting and progress layout-phase reads.

## Layout, accessibility, motion and states
Phone: 16 dp edges, 12–16 dp row spacing, 48 dp targets, content scrolls above existing shell inset. Desktop: centered maximum 880 dp list, existing rail untouched. Text wraps at large font scales; row controls cannot consume name width. Sections are semantic headings. Tokens handle dark mode. Remove ornamental scan pulse/brand loader and throughput count-up; retain real progress interpolation and keyed list placement. Empty/loading/error states keep page context and actionable existing guidance; no decorative statistics. Accessibility checks owed: keyboard menu dismissal/focus, TalkBack action labels, 200% font, long names, narrow landscape.

## Verification plan
Run existing Nearby/Transfers logic, cancellation and relevant host mapper tests, Android debug assembly and desktop compile. Native opt-in sample-data renders: populated/empty/loading/error/network-off, phone/desktop/dark/large-font. Manually verify paired menus, scan/manual connect, pair dialog, real transfer actions, cancellation and file open/share on devices; record owed cases in TEST-BACKLOG. Document results and limitations here and in progress/handoff.


## Implementation and review — 2026-11-06
The design above was recorded as DESIGNED before source changes. The implementation changes only the two shared screens; host API signatures, transfer engine, discovery/trust storage and permissions are unchanged. Menus use existing Material components with Flash surface/text tokens. Names are readable independently of controls. Nearby identity details are available by expanding This device. The header no longer sums persisted pairings into a nearby count.

Transfers displays actual bytes, total size and percentage alongside the existing speed/ETA/status helper. Incoming offers, paused/queued work, retryable failures and completed files retain their action partitioning. Failed text can wrap; terminal files no longer display a decorative full progress bar. The progress helper keeps layout-phase interpolation and a new optional showShimmer flag (default true) disables the ornamental sweep only on this page; detail-pane callers retain their existing behavior. The shared throughput helper and throttling remain unchanged.

### Native evidence
`workspace-tabs-renders/` holds 16 actual Compose captures with fictional state: each tab in phone light/dark, desktop and 200% font; Nearby empty/loading/network-off; Transfers incoming/empty/loading/error/history. Reproduce with `FLASH_TABS_RENDER_DIR=<absolute directory>` and the locked Gradle `:desktop:jvmTest --tests '*FlashWorkspaceTabsRenderTest*'` task. The fixture is skipped without the environment variable and uses no database, device or network.

Review found and repaired misaligned 48 dp section controls and low-visibility loading placeholders. Large-font content wraps and remains scrollable; the existing shell's 200% bottom-nav label clipping is documented under HUBWORK-04/TABWORK-03. The native renderer establishes visual evidence, not TalkBack, keyboard or live network correctness. Menu actions are code-reviewed for original callback routing; real interaction is owed.

### Accessibility, performance and acceptance
- Normal/light/dark and large-font native layouts reviewed; token surfaces and text retained.
- 48 dp actions; semantic headings and per-device More actions labels; optional Call/Verify gated exactly as before.
- No new gesture, swipe or hover requirement; menus dismiss through their standard Material behavior.
- Stable keyed lazy rows and existing list state retained. Scan pulse, decorative branding and throughput count-up removed; no new recurring animation.
- Physical device, keyboard/TalkBack, RTL, resizing during interaction, long real content and performance spot-checks: TODO, TABWORK-01..03.
- Existing layout and source helpers require no new libraries or license changes. Historical docs/logs are preserved.
- This remains IMPLEMENTED, not device-verified or ACCEPTED. Owner visual review is still owed.

### What makes this Flash?
The workspace uses Flash's existing type, rounded surfaces, teal action color and redrawn icons. It gives the app's real paired-device trust model and resumable transfer states room to be understood, without adding dashboard statistics, fictional presence or decorative connection maps.
