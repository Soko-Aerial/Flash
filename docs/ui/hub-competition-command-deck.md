# Flash Hub competition entry — "Command Deck" (package `opencode`)

**Status:** BUILT and compile-verified 2026-10-18; a **standalone design entry**, deliberately not wired into the app, not
unit-tested and not device-verified. Previews only. Device/manual checks owed: `HCDECK-01`…`HCDECK-07` in
`docs/testing/TEST-BACKLOG.md`.
**Kind:** competition entry, not a product component. It is not UI-058 and does not replace `docs/ui/flash-hub-tab.md`.
**Code:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/hub/competition/opencode/` (12 files, ~5,300 lines).
**Entry point:** `HubCompetitionShowcaseScreen()` — one composable that shows a floating "Judge's control bar" over five pages.

---

## 1. Why this exists

The owner ran a head-to-head UI/UX design competition for the next Flash Hub experience: several agents each build the best
multi-page Hub design **inside their own package**, and the owner judges them side by side.

The competition rules this entry obeys:

| Rule | How it is kept |
|---|---|
| No production file may be modified | Nothing outside `.../hub/competition/opencode/` is touched. `MainActivity.kt`, `DesktopShell.kt`, `HubUiPrefs.kt`, `DesktopHubUiPrefs.kt`, `FlashHubScreen.kt` and every other production file are untouched. |
| No core-module change | `:core:enterprise`, `:core:messaging`, `:core:hub-protocol`, `:core:persistence`, `:core:network` are read-only for this work. The entry consumes **public** model types only. |
| Self-contained design package | One new package, `com.transfer.flash.ui.hub.competition.opencode`. It compiles with the module and is otherwise dead code: nothing references it, so it cannot affect the shipped app. |
| Design-only, mock state | Every page renders from one immutable `HcDeck` built by `hcDeckFor(scenario)`. No repository, no Room, no relay, no `@FlashInternalApi` type. |
| Follow the existing design system | `FlashTheme` (colours, typography, shapes, spacing, dimensions, elevation), `FlashIcons`, `FlashMotionPolicy` via `FlashTheme.motion`, the performance flags, and the adaptive breakpoints. No new dependency, no new font, no new colour literal. |

"Zero production changes" also means the entry cannot be launched in the running app: the only ways to see it are Android
Studio previews or a scratch host. That is intentional — a competition entry must not be able to break the product.

## 2. What ships in the package

| File | Lines | Responsibility |
|---|---:|---|
| `HcTokens.kt` | 171 | Tone vocabulary (`HcTone`, `HcToneColors`, `hcTone`), `HcDims`, `hcMono`, and the formatters (`hcAgo`, `hcUntil`, `hcElapsed`, `hcCount`, `hcBytes`, `hcFingerprint`, `hcInitials`, `hcPaletteIndex`, `hcNormalise`) + `HC_NOW_MS`, the fixed "now" all scenarios use so nothing ages between two renders. |
| `HcGlyphs.kt` | 81 | `HcGlyph` — a semantic glyph name per meaning, mapped to the existing `FlashIcons` specs, plus `HcGlyphIcon`. No stock Material icon is drawn. |
| `HcMock.kt` | 795 | The competition's own domain model (`HcLink`, `HcPhase`, `HcSealing`, `HcEnrollmentStep`, `HcBanner`, `HcTelemetry`, `HcLease`, `HcCertificate`, `HcDelegation`, `HcStatement`, `HcRevocations`, `HcRoster`, `HcBridge`, `HcPrivacy`), the six scenarios, `HcDeck` and `hcDeckFor(scenario)`. Directory content is seeded from the project's own `HubSampleData` fixture. |
| `HcComponents.kt` | 972 | The visual kit: `HcCard`/`hcCardSurface`, `HcChip`, `HcPulseDot`, `HcButton`, `HcTextAction`, `HcIconAction`, `HcSwitch`, `HcStatTile`/`HcTileGrid`, `HcSparkline`, `HcRingGauge`, `HcMeter`, `HcKeyValue`, `HcFingerprint`, `HcAvatar`, `HcBannerCard`, `HcEmptyState`, `HcSkeleton`, `HcChipFlow`, `HcStepRail`, `HcChevron`, `HcSoftRule`, `HcSegmented`, `HcSectionLabel`, `HcDivider`. |
| `HcPageKit.kt` | 569 | Page furniture shared by all five pages: `HcPageHeader`, `hcIsWide`/`hcColumns`, `HcSplitPage` + `HcDetailBar` + `HcScrollColumn` (the one place that decides list-pane vs detail-pane vs pushed screen), `HcSearchField`, `HcFilterRow`, `HcRefreshRow`, `HcChoiceChip`, `HcTextField`, `HcSignalBars`, `HcBand`, `HcVerdictRow`, `HcChainNode`/`HcChainLink`, `rememberHcBusy`. |
| `HcPageCommandCenter.kt` | 492 | **Page 1**, the status deck: identity/certificate card, six telemetry tiles, the hub-link chart card, the trust chain, privacy switches, and jump rows into the other four pages. Also `hcLeaseTone`/`hcLeaseFraction`/`hcLeaseSentence`. |
| `HcPageDirectory.kt` | 414 | **Page 2**, the organisation directory: search, filter chips with live counts, department cards, the colleague list, and the colleague card (reachability, signed-statement state, pairing, and the honest sentence about *how* the link is sealed). |
| `HcPageAnnouncements.kt` | 481 | **Page 3**, the broadcast feed: unread filter, scope chips, pinned posts, the composer (audience selector, title, body, pin, inline validation and the "what the hub can read" notice) and the reader. |
| `HcPageTrust.kt` | 429 | **Page 4**, the trust vault: device certificate, delegation chain, the sealing key with its two-step confirmation, the revocation list, offline-lease explanation/maintenance, and leave-organisation. Also `hcCertificateTone`/`hcCertificateSentence`. |
| `HcPageRosters.kt` | 433 | **Page 5**, signed group rosters and the relay bridge: roster rows with validity days, member lists with key/pairing state, pending join requests with Approve/Decline, bridge health with adoption state, and the "what a removed member keeps" hint. |
| `HcShowcase.kt` | 297 | `HcPage`, `hcFrameWidth`, the public `HubCompetitionShowcaseScreen`, `HcShowcaseFrame` (device-shaped outline), `HcShowcasePage` (page dispatch), the page/tab top bar with badges, and the Judge's control bar. The stage pans horizontally so a 1180dp frame is never clipped in a narrower window. |
| `HcPreviews.kt` | 236 | 42 `@Preview`s: a complete 5 pages × 6 hub states matrix, a desktop split-pane preview per page, light-theme samples, reduced-motion samples and two whole-harness previews. |

Nothing in the package is `public` except `HubCompetitionShowcaseScreen()`. Everything else is `internal`, so the entry adds no
API surface to `:ui:chat`.

## 3. The five pages

### Page 1 — Command Center

Answers "am I in, and is the organisation's link healthy?" in one screen: the enrolled identity (name, device id, certificate
scope and validity) at the top, then a six-tile telemetry row (state, hub handshake, sealing key, directory, lease, mailbox),
then the link card that plots round-trip latency and throughput over the retained sample window with the retry sentence
underneath, then the trust chain (device certificate → delegation → signed statement → revocation list) drawn as nodes and
links with a verdict row per link, then the three privacy switches, then jump rows into the other pages. It is the only page
that takes an `onOpenPage` callback, so it can double as the app's Hub landing screen.

### Page 2 — Organisation Directory & Peer Explorer

Search by name or device id, with department and reachability filters whose chip labels carry live counts. Departments are
cards that can be opened; the colleague list groups people the same way the real tab does. The colleague card explains
reachability in words per state (on this network / through the hub / not reachable), whether the directory statement matches a
key a session has actually proved, whether the device is paired, and — the part the real design usually hides — **how a
message would be sealed**: directly when nearby, otherwise with the key the colleague published to the hub. Actions (Message,
Call, Pair) are disabled until the key is verified, and the reason is on screen.

### Page 3 — Announcements & Broadcast Feed

The feed shows scope (organisation-wide, department, pinned), author and role, age, an unread filter and pinned posts first.
The composer is shown only when the signed posting rights allow it: the audience selector is derived from
`HubPostingRights` (department head → that department; organisation-wide poster → any department), and it validates before
posting (empty title, empty body, over-length) with an inline error rather than a disabled button and no explanation. Because
announcements are stored **unencrypted on the hub** (ADR-138), the composer and the reader both say so in plain words.

### Page 4 — Enterprise Enrollment, Certificates & Trust Vault

The whole trust story, in the order a person needs it: the device certificate (serial, fingerprint in mono type, scope,
validity with a ring gauge), the delegation chain that signed it, the sealing key and which colleagues it covers — with a
deliberate two-step confirmation before rotating a key, since that changes what the hub can read — the revocation list with
its revision and fetch action, the offline lease ("what still works with no hub", how many days are left and how the lease is
renewed), and leaving the organisation behind a confirm step. In `ENROLLING` the same page becomes the enrolment wizard
(step rail, per-step state, current step detail).

### Page 5 — Verified Group Rosters & Relay Bridge

Two lists that share one gate: signed group rosters and the relay bridge. A roster row shows validity days, member count and
pending requests; the roster detail shows each member's key and pairing state, the roster's own signature verdict, and its
pending join requests as actionable rows with Approve/Decline. The bridge side shows adoption state, latency and signal
strength bars, and the honest note that a member removed from a group keeps whatever it already received. Both lists are
filterable and both explain what "verified" means for the entry you are looking at.

## 4. The Judge's control bar

A floating panel (collapsible to a "Judge" button, so it never blocks the design) with four live switches:

- **Hub state** — the six states: `Unenrolled`, `Enrolling`, `Active & sealed`, `Relay degraded`, `Cert revoked / expired`,
  `Empty / offline`. Each has a one-line blurb and drives every page at once, because all five read the same `HcDeck`.
- **Form factor** — `Mobile (412dp)` / `Desktop split-pane (1180dp)`. This changes the frame width the pages are handed, so
  the real breakpoint (`840dp`, `FlashAdaptiveMath`) decides between one pane and two — the same code path the app uses.
- **Theme & motion** — Dark/Light and High/Reduced motion, applied through `FlashTheme` and `rememberFlashMotion`, so the
  previews and the harness exercise the same `FlashTheme.motion` guards the shipped UI does.
- **Page** — the same five pages as the tab row.

The bar itself is built from the entry's own components and is the only "chrome" in the package.

## 5. Design-system alignment

- **Colour** comes only from `FlashTheme.colors`; every tinted surface is a `FlashColors` token at an alpha, produced by
  `hcTone(tone)` / `hcCardSurface(tone)`. There are no colour literals in the package.
- **Type** comes only from `FlashTheme.typography`, with `hcMono(...)` switching to the numeric/mono style for fingerprints,
  serials, device ids and sizes.
- **Motion** always reads `FlashTheme.motion` first and snaps when `reduceMotion` is set; every animated element (pulse dots,
  ring gauges, crossfades) has a reduced-motion branch. `ultraLowUi` removes the gradient washes and `minimalChrome` removes
  the shadows, so the pages degrade the same way the product does on a weak device.
- **Performance tiering** is not re-invented: the flags above are the ones `FlashPerformanceMode` (ERROR-033) sets for a
  device, surfaced through `FlashTheme.motion`. The entry adds no background work, so there is nothing for `UiScrollGovernor`
  to throttle — that object is reported by the host (`MainActivity`) for `SubnetSweeper`'s benefit, and the entry's job with
  respect to it is simply to be cheap while a list flings (remembered state, plain rows, `Canvas`-drawn charts, no bitmaps,
  no per-frame allocation in the drawing code).
- **Responsive** — `hcIsWide(widthDp)` is the single place that decides one-pane vs two-pane, and it is the real breakpoint,
  not a guess. `HcSplitPage` draws the detail (back bar + one capped scrolling column) in both form factors, so each page
  writes its detail once.
- **Icons** — `HcGlyph` names meanings and maps them to existing `FlashIcons`; glyphs are always decorative (`contentDescription = null`)
  and the meaning is carried by a word next to them.
- **Accessibility** — no state is carried by colour alone: every tone is paired with an icon and a word, chips have labels,
  the pulse dot that means "live" is always next to the word "live", and interactives use `FlashTextAction`/`HcButton`/
  `HcIconAction` with real labels, not bare canvases.
- **Honesty** — the copy is written to the same rules as the product: announcements are described as readable by the hub
  (ADR-138), group content is labelled as it really is, a removed member "keeps what it already received", and every lease,
  delegation and revocation state is spelled out in words rather than left to a colour.

## 6. Fixtures

`hcDeckFor(scenario)` returns one immutable `HcDeck`. The default scenario's directory content is seeded from the project's
own `HubSampleData` fixture (the same people, departments and groups the real tab's previews use), then extended with the
certificate, delegation, statement, revocation, roster and bridge figures this competition needs. Those figures are modelled
on `:core:hub-protocol`'s concepts (`DeviceCert`, `DelegationCert`, `OrgMemberStatement`, revocation list, hub roles) without
depending on the module's internal types. The six scenarios are variations on the base deck, so a page cannot be handed a
half-built deck.

## 7. Verification

| Check | Command | Result |
|---|---|---|
| JVM compile of `:ui:chat`, isolated tree at HEAD + this package only | `tools\flash-gradle.cmd :ui:chat:compileKotlinJvm` | **BUILD SUCCESSFUL**; zero errors and zero warnings from `competition/opencode/` |
| Android compile, same isolated tree | `tools\flash-gradle.cmd :app:compileDebugKotlin` | **BUILD SUCCESSFUL** (this is the variant Android Studio's previews render from) |
| Previews | 42 `@Preview`s, complete page × state matrix | not run (needs Android Studio or a device) |

Verified at HEAD `0b4a4d5c` plus this untracked package; nothing in the package has been committed.

Why the isolated tree: a sibling agent's half-finished entry in the same module (`.../competition/novahub/`) does not compile,
and `:ui:chat` is one compilation unit, so a compile in the shared working tree cannot distinguish their errors from this
package's. Compiling a clean worktree containing only this package proves the package itself is sound. (The scratch
worktree was deleted after the check; the copies were hash-verified against the working tree first.)

**Not verified:** nothing in this entry has been rendered on a device or an emulator, and nothing has been unit-tested. There
is no runtime path to it from the app. The pages are Compose and data-driven, and the decks are complete for all six states,
but "it compiles" is not "it looks right" — that is exactly what `HCDECK-01`…`HCDECK-07` are for.

## 8. Deliberate non-goals

- **Not wired anywhere.** No tab, no navigation entry, no Settings row, no preference. Mounting it would require editing a
  production host file, which the competition forbids.
- **No persistence, no networking, no repository.** Every button either changes local `remember` state or deliberately does
  nothing (it is a design, not a feature).
- **No new dependency, no new font, no new colour.** Reusing `FlashTheme` was a rule, not a convenience.
- **No changes to the real Hub tab.** `docs/ui/flash-hub-tab.md` (UI-058) still describes the shipped design; anything here
  that the owner likes would have to be re-implemented against the real repository API.
