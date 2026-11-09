# UI-058 — Organizational workspace redesign

**Status: IMPLEMENTED — 2026-11-06.** Designed before implementation; host tests/builds passed. Physical-device and interactive host checks remain owed (HUBWORK-01..05).
**Scope:** the enrolled Android/desktop Hub tab, within the existing Flash visual system.
**Mode:** Operate. Employees find colleagues and updates; heads and administrators use the actions their account permits.

## Research and product truth

Read `flash-hub-tab.md`, the premium UI master plan, research index, current Hub screen/parts/people/news/org/status,
`FlashHubActions`, `FlashHubMath`, the route and model contracts. The original main plan is under
`Project Goal and Blueprint/`, not the root path named in AGENTS.md. Latest baseline: `dev` at `1d164366`.
The previous alternative visual identity was rejected (`hub-web-atlas-prototype.md`); retain FlashTheme and FlashIcons.

Verified official guidance on 2026-11-06:
- https://developer.android.com/develop/ui/compose/accessibility/api-defaults — Foundation semantics, minimum 48 dp targets.
- https://developer.android.com/develop/adaptive-apps/guides/list-detail — list/detail adaptation to available width.

Existing supported features: searchable people/departments/news/groups; person Message, conditional LAN Call and pairing;
department members/heads/groups/news; group Open/Join/Request/Pending and host-gated request management; announcement
read/unread, authoring, edit, pin, delete, link confirmation and persisted failed-post drafts; refresh, cached/offline
states, revoked sign-out, connection/trust details. No new admin console, invitations, files library, activity feed,
reporting lines, calendar or analytics is implied by this design.

## Approaches compared

1. Summary dashboard above every section: preserves the incumbent but repeats metrics, delays the work and makes trust
   mechanics look like employee tasks. Rejected.
2. Separate organizational home with shortcut cards: useful for a larger product, but duplicates the four supported
   destinations and adds navigation. Rejected.
3. Persistent organization identity, four clear sections and contextual detail: selected. It puts the employee's task
   directly under its context and leaves room for lists and announcements.

## Chosen design

- Compact organization header: name, own role/department, search and refresh. A text-labeled connection control opens
  the existing status sheet. Connection truth must distinguish connecting/authenticating from offline.
- News / People / Departments / Groups remain the persisted segments. The unread badge retains its source.
- Each section has a title and concise purpose. No repeated summary tiles, counting animation or department-size bars.
- Publishing remains scoped by `HubPostingRights`, not the display role. Members see updates, heads get only their
  authorized audiences, administrators keep organization-wide publishing. Request management keeps its current gate.
- Details stay in phone sheets/readers; wide screens show a detail pane only after selection. No permanent empty pane.
- Search keeps its existing data source, filtering, grouped results and deep links.

## Visual, motion and accessibility specification

Use existing FlashTheme graphite surfaces, textPrimary/textSecondary, accentFill/onAccentFill for primary buttons,
Flash typography and the redrawn FlashIcons. No new fonts, palette or dependency. Spacing follows 8/12/16/24 dp tokens.
Flat surfaces, subtle rules, stable numerals and natural-height text. Controls have at least 48 dp targets. Headings
carry heading semantics; sections retain Tab/selected semantics. Status changes use a polite live region and words,
never color alone. Organization names and role text wrap. Keep existing focusable clicks and keyboard search submission.
No decorative motion added. Frequent section switches are immediate; existing modal transitions respect FlashMotion.
Dark mode uses the same semantic slots, not hardcoded colors. Large font needs a manual device check.

## States and responsive behavior

Loading: organization context and skeleton rows; unknown counts are not presented as real zeros. Refresh: retained content
with updating feedback. Offline/cached: readable content plus the existing recovery explanation. Never synced: retry
empty state. Permission/revocation, announcement unsupported, failed publication, failed group loading and hidden
department states retain their existing actions. Ordinary empty states explain the section without invented controls.

Phone content uses available width. Wide content is centered and bounded for reading; selected details share the width
with the list only at the existing expanded breakpoint. No transport, service, storage or authentication changes.
Lazy list keys, read-marking, saved selection and single-flight actions remain intact.

## Implementation / testing

Targets: `ui/chat/.../hub/FlashHubScreen.kt`, workspace header, parts, people/news/org presentation and previews.
Build through `tools/flash-gradle.cmd`; run affected Hub JVM tests, Android compile and desktop compile. Inspect native
rendered light/dark, phone/wide, offline and role cases where tooling permits. Physical-device checks are owed separately
and must be added to TEST-BACKLOG; compile alone is not visual or device verification.

## What makes this Flash

The organization is one context inside Flash's local-first communication app. Its green accent, custom icons, role-aware
actions and honest offline states carry that identity; no fictional organizational capabilities or separate dashboard
theme is introduced.


## Implementation and verification — 2026-11-06

- Organization/role/connection header replaces the repeated overview. Search, refresh and connection details remain
  available; healthy status is no longer repeated in a separate strip. Warnings/cached-data explanations retain actions.
- Section headings keep real counts, omit unavailable counts and explain the task. New announcement is a primary
  button only for authorized posters. Permission calculations and all host callbacks are retained.
- Desktop uses a centered 880 dp column before selection and the existing list/detail split after selection. Existing
  app navigation remains owned by the hosts. Phone details retain sheets/readers.
- Removed department-size bars, repeated overview tiles, decorative empty-state pulse and profile/announcement gradients.
  Primary buttons and text actions use the contrast-corrected accentFill tokens. At large font scales refresh becomes a
  labeled icon control, and colleague badges move below naturally wrapping names.
- Connection progress, expired credentials and server mismatch now have distinct labels, also used in the status sheet.
  A connecting cached directory no longer contradicts the header with an Offline strip; CERT_EXPIRING remains connected.
- No dependencies, transport, schema, service or repository contracts changed.

Gate: `tools/flash-gradle.ps1 :ui:chat:jvmTest --tests '*FlashHub*' :ui:chat:testAndroidHostTest --tests '*FlashHub*'
:app:assembleDebug :desktop:jvmTest --tests '*FlashHubWorkspaceRenderTest*' --console=plain`.
Result: **BUILD SUCCESSFUL, 3m 14s, 323 tasks; JVM 205/0/0, Android-host 196/0/0**, zero skips in those Hub suites.
Desktop compile and opt-in native render fixture passed. Counts are from JUnit XML, not inferred from compilation.
Existing deprecation/native-access warnings remain. `git diff --check` is the static whitespace check; full app lint
was not run. The initial verification-scaffold compile errors and repair are ERROR-991.

Native render evidence is in `hub-workspace-renders/`: 390x844 light/dark, employee directory, admin announcements,
loading, empty, offline, group failure and 200% font; 1200x800 departments and selected person with the real app rail.
These are the actual composables with explicitly fictional `HubSampleData`, not screenshots of a live organization.
Reproduce with `FLASH_HUB_RENDER_DIR=<absolute output directory>` and
`tools/flash-gradle.cmd :desktop:jvmTest --tests '*FlashHubWorkspaceRenderTest*'`. The fixture is skipped without that
variable; it never opens the real database, contacts a server or operates a device.

### Visual review and limitations

The revised layout brings people and announcements into the first phone viewport, keeps the organization/role visible
above sections, and avoids a reserved empty desktop detail pane. The first 200% font capture exposed header/name
crowding; the final layout wraps colleague text and uses an icon for refresh at that size. Dark and offline states
remain legible and use explicit text. Checked the ten native capture cases; no claim of TalkBack or device verification.
The existing shared bottom navigation truncates labels at 200% font and its fixed-size avatar initials can clip at that
scale; these are outside the Hub layout and remain tracked in HUBWORK-04. Keyboard interaction, long real organization
names, runtime role changes, phone landscape, real error recovery and list-position behavior still require HUBWORK-01..05.
The implementation is **not ACCEPTED/device-verified** until those checks and owner visual review are recorded.
