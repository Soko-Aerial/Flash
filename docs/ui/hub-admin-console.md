# Hub admin console (UI design and implementation record)

Status: DESIGNED 2026-10-13, implemented the same day (Phase 3, UI agent). Not device- or browser-matrix-verified: see `HUBU-*` in `docs/testing/TEST-BACKLOG.md`.
Decision record: ADR-125 (`docs/decisions.md`). Security notes: `docs/security.md` "Hub admin console". API contract: `docs/protocol.md` "Hub administration".
Code: `server/src/main/resources/admin-ui/` (static, no build step), served by `server/.../admin/AdminConsoleWeb.kt`.

The old server-rendered HTML console (`AdminUiHtml.kt`, `AdminDashboardRoutes.kt`) was read once only to list behaviours that must survive (setup, login,
dashboard, departments, groups, invites, audit). Nothing of its layout, colours, markup, wording or structure was reused. The old routes are deleted.

## 1. Audience and context

- **Who:** IT administrators of companies with 10 to 5,000 people. One to five admins per hub. Usually not full-time Flash experts: they know "device",
  "invite", "department", "revoke", not "bearer", "SPKI" or "delegation serial".
- **Where:** a laptop (1280 to 1920 px) most of the time, sometimes a tablet or a phone in the server room (360 px), occasionally a 4K monitor. Reached
  over a LAN or VPN; the management listener is loopback by default, so often through an SSH tunnel or a reverse proxy. Frequently air-gapped: **no
  request ever leaves the hub's own origin**, no CDN, no web font, no analytics, no external image.
- **Risk profile:** the console can revoke every device of a company. Every dangerous action asks for a second, device-bound confirmation (step-up),
  states in words what it will do, and leaves a signed audit entry.

## 2. Jobs to be done

| # | Job | Where |
|---|---|---|
| J1 | Bring a new hub to life: first admin, authenticator, recovery codes, delegation | Setup, Overview "awaiting delegation" card |
| J2 | Onboard people: one invite or a pasted list of 200, print a card with a QR | Invites |
| J3 | Offboard fast: find a device, revoke it, know it took effect | Devices |
| J4 | See that the hub is healthy and the delegation is not about to expire | Overview, Settings |
| J5 | Organise: departments, groups, who may join, approve join requests | Organization |
| J6 | Answer "who did what, when" and prove the log was not altered | Audit |
| J7 | Manage admins, sessions, recovery codes, the root fingerprint | Security |
| J8 | Understand the effective configuration (read only) and get help | Settings |

## 3. Information architecture and navigation

Hash routing (`/admin/#/devices`), because the hub serves one HTML shell and every deep link must survive a reload without a server fallback.

```
Overview            #/overview
Devices             #/devices            #/devices/<deviceId>  (drawer)
Invites             #/invites
Organization        #/organization       (tabs: Departments, Groups, Join requests)
Audit log           #/audit
Security            #/security           (tabs: Administrators, Sessions, Delegation, Revocations, Trust)
Settings            #/settings           (tabs: Configuration, Health, About and shortcuts)
(not signed in)     #/login   #/setup
```

Order is by frequency of use. "Join requests" is a tab of Organization and also a badge on the nav item and a card on Overview, because a pending
request is the one thing an admin must not miss.

## 4. Layout alternatives considered

| | Layout | For | Against | Verdict |
|---|---|---|---|---|
| A | **Left navigation rail (collapsing) + top bar + content, detail in a right drawer** | Ten-plus destinations stay visible and labelled; lists keep their context while a row is inspected; maps cleanly to a bottom bar on phones; the pattern is learned once | Costs 220 px of width on small laptops (the rail collapses to icons below 1100 px) | **Chosen** |
| B | Top horizontal tabs | Maximum content width; trivially simple | Only about 6 destinations fit; sub-tabs become a second row; no room for a live badge per item; bad for 360 px | Rejected |
| C | Command-palette-first, one content column | Fastest for experts; tiny chrome | Not discoverable for part-time admins; poor for the "I need to look at the whole picture" job; hard to make accessible quickly | Rejected as the primary model. The keyboard map (section 11) gives experts the speed: `g` then a letter, `/`, `?` |
| D | Master/detail split as the default for every page | Great for devices | Wastes space on pages with no detail (overview, settings); a permanent third column breaks at 1280 px | Rejected; the drawer gives the same on demand |

## 5. Screen inventory and states

Every page implements the same state machine through one helper (`ui/state.js`): `loading` (skeleton, not a spinner), `ready`, `empty` (with the next
action), `error` (plain sentence, Try again, correlation id), `partial` (some panels failed: the others stay), `expired` (session ended: a sign-in dialog
that returns to the same place), `forbidden` (the API said no), `waiting` (hub awaiting its delegation).

| Screen | Ready | Empty | Error / special |
|---|---|---|---|
| Setup wizard | 6 steps: token, account, authenticator (QR + manual key), confirm code, recovery codes (copy, download, "I saved them" gate), enrol this browser | n/a | token wrong or locked (message + wait), setup already complete (go to sign in), no WebCrypto (HTTPS or localhost needed: step is skipped with an explanation, step-up unavailable) |
| Login | username, password, code; "use a recovery code" switch | n/a | wrong credentials (one sentence for all causes), locked (countdown from Retry-After), rate limited, hub not reachable, session expired banner |
| Overview | health strip, delegation card, device status bar, invites, online, queue, relay sparkline, join requests, chain status, recent security events | new hub: "No devices yet. Create the first invite" | any card fails alone (partial); awaiting delegation: the exact `keytool issue-delegation` command with copy; expiring delegation banner |
| Devices | table with search, filters, sort, windowed rows, bulk select, CSV export, row drawer | "No devices match" / "No devices enrolled yet" with a link to Invites | revoke needs step-up (inline flow); revoked rows muted with an Unrevoke action |
| Invites | list with state chips; create form; bulk (paste or file) with preview; result view with QR, link, print card | "No invites yet" | not available until delegation (waiting state); invite secret shown once (warning) |
| Organization | department tree, group list, group detail (members, admins), join-request inbox | each tab has its own empty state | 409 name clash, 404 vanished; join decision with optional reason |
| Audit | filters, newest first, live tail, verify chain, CSV | "Nothing logged for this filter" | chain broken: first bad index in a danger banner |
| Security | admin table and actions, own sessions, delegation, revocations, trust (fingerprint) | single admin: hint to add a second | step-up key missing in this browser: explanation and enrol |
| Settings | configuration table (read only), health, metrics, about, shortcuts | n/a | metrics unavailable: partial |

## 6. Design system

Tokens are CSS custom properties in `css/tokens.css`; components (`css/components.css`) use only tokens. Three theme states: `light`, `dark`, `system`
(default; `prefers-color-scheme`), stored in `localStorage` (try/catch) as `flash.hub.theme`; `prefers-contrast: more` strengthens borders and text in
both themes; `prefers-reduced-motion: reduce` turns every transition and animation off.

### 6.1 Identity

Flash's mark is a lightning bolt. The console is graphite and warm paper with one accent, **volt indigo**, and the bolt used once (nav header, login,
setup, favicon) as a drawn SVG, never as an emoji. Information is carried by type, spacing and rules (hairlines), not by shadows or gradients; cards are
flat with a 1 px line. Status colours are used only for status. No illustration, no stock icon set: about 30 inline SVG glyphs (`ui/icons.js`), 1.75 px
strokes, `currentColor`, drawn for this product.

### 6.2 Colour tokens and contrast (computed, WCAG 2.1 ratios)

| Token | Light | Dark |
|---|---|---|
| `--c-bg` page | `#F4F3EF` | `#121211` |
| `--c-surface` card | `#FFFFFF` | `#1B1B19` |
| `--c-sunken` | `#ECEAE4` | `#0D0D0C` |
| `--c-text` | `#1B1A17` | `#ECEBE5` |
| `--c-muted` | `#57554C` | `#A9A79D` |
| `--c-border` (controls) | `#8B887C` | `#7A786E` |
| `--c-line` (hairlines, decorative) | `#D9D6CC` | `#2E2D29` |
| `--c-accent` (buttons) / `--c-on-accent` | `#3A3FCB` / `#FFFFFF` | `#8E94FF` / `#0B0B1A` |
| `--c-accent-text` | `#2B2FA8` | `#B5B9FF` |
| `--c-accent-soft` | `#E7E8FA` | `#26284F` |
| `--c-ok` / `--c-ok-soft` | `#14702F` / `#E1F2E6` | `#63D18A` / `#16301E` |
| `--c-warn` / `--c-warn-soft` | `#835000` / `#FAEBCF` | `#F0B04B` / `#3A2A0C` |
| `--c-bad` / `--c-bad-soft` | `#B0231B` / `#FBE3E0` | `#FF8C84` / `#401614` |

Contrast ratios (text needs 4.5, UI boundaries and focus rings need 3.0; generated by a script, rerun when a token changes):

| Pair | Light | Dark |
|---|---|---|
| Text on page | 15.67 | 15.69 |
| Text on card | 17.40 | 14.44 |
| Muted text on page | 6.73 | 7.77 |
| Muted text on card | 7.48 | 7.15 |
| Muted text on sunken | 6.21 | 8.06 |
| Input border / card | 3.55 | 3.89 |
| Input border / page | 3.20 | 4.23 |
| Button label on accent | 7.63 | 7.27 |
| Link text on card | 10.12 | 9.32 |
| Accent text on soft accent | 8.35 | 7.57 |
| Focus ring on card | 7.63 | 9.32 |
| Focus ring on page | 6.87 | 10.13 |
| OK text on card | 6.20 | 9.05 |
| OK on soft | 5.33 | 7.47 |
| Warning text on card | 6.75 | 9.05 |
| Warning on soft | 5.73 | 7.27 |
| Danger text on card | 6.77 | 7.67 |
| Danger on soft | 5.53 | 6.95 |

`prefers-contrast: more`: text `#000` on `#FFF` (21.00) and `#FFF` on `#000` (21.00); muted 11.74 / 13.59; control border 9.17 / 10.32; button label on
accent `#1F23A0` 11.63, dark `#C2C6FF` 12.82; danger/ok/warning text 9.2 to 14.7. Colour is never the only carrier: every status has a word and a glyph.

### 6.3 Type, space, radius, elevation, motion

- **Fonts (system only):** `--font-ui: system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`; `--font-mono: ui-monospace,
  SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace`. No `@font-face`, `font-src 'self'` is a formality.
- **Scale (rem, so browser zoom and the user's default size work):** 0.75, 0.8125, 0.875 (body 14 px), 1, 1.25, 1.5, 2; line height 1.45 body, 1.2
  headings; weights 400, 500, 600. Counts and times use `font-variant-numeric: tabular-nums` so columns do not jitter.
- **Space (4 px base):** 2, 4, 8, 12, 16, 24, 32, 48, 64. Page gutter 16 px below 600 px, 24 px up to 1200, 32 px above; content max width 1440 px.
- **Radius:** 4 (controls), 8 (cards), 12 (dialogs, drawer on desktop), 999 (chips).
- **Elevation:** three levels, drawn with a line first and a soft shadow only for floating layers: 0 flat, 1 popover (`0 4px 16px / 12%`), 2 dialog and
  drawer (`0 12px 40px / 24%`) plus a scrim.
- **Motion:** 120 ms for hover/press, 180 ms for drawers and dialogs (`cubic-bezier(.2,.8,.2,1)`), 900 ms shimmer on skeletons. All of it collapses to
  0 under `prefers-reduced-motion`. Nothing animates layout properties of content that is already read.

## 7. Component inventory

App shell (rail, top bar, content, live region), nav item with badge, page header with actions, card, stat tile, status badge/chip, button (primary,
secondary, ghost, danger), icon button (always with an accessible name and a title), text input, select, textarea, checkbox, switch, form field (label,
hint, error wired with `aria-describedby`), tabs, data table (sortable headers with `aria-sort`, selection column, windowed rows with
`aria-rowcount`/`aria-rowindex`), toolbar with filters, empty state, error state with correlation id, banner, skeleton, toast, dialog (modal,
focus trap), confirm dialog (typed confirmation for destructive actions), drawer, step-up flow, copy button, relative time (`<time title>`), sparkline
(inline SVG), segmented bar, fingerprint visual, QR block (sanitised SVG), stepper, definition list, code block, keyboard-key chip.

### Patterns

- **Tables:** header row sticky inside the scroll area; one `<table>` with real `<th scope>`; the row's primary cell is a button that opens the drawer;
  filters live above and are reflected in the URL hash query; a result line (`1,204 devices, 38 selected`) is a live region. Above 200 rows the body is
  windowed: a fixed row height, top and bottom spacer rows whose heights are set through CSSOM, roughly 40 real rows in the DOM.
- **Forms:** labels above fields; the first invalid field gets focus after a failed submit; validation text is plain sentences; submit buttons show
  progress and are disabled while the request runs; Enter submits.
- **Dialogs:** `role="dialog"` + `aria-modal`, labelled by the title, focus moves in, Tab is trapped, Esc closes (unless a step-up is mid-flight), focus
  returns to the opener, the page behind is `inert`.
- **Drawer:** same rules as a dialog, slides from the right (full width below 700 px).
- **Toasts:** a polite live region (`role="status"`); success disappears in 5 s; errors persist until dismissed and carry the correlation id; never the only
  place an error is shown for a form.
- **Destructive actions:** the dialog states the effect in a sentence, names the target, asks to type the target's name (or the word `revoke`) for bulk or
  irreversible actions, then starts the step-up flow.
- **Step-up:** one reusable flow (`ui/stepup.js`): challenge, sign with the browser key, verify, run the action with `X-Step-Up`. The user sees
  "Confirming on this browser" for a moment; failures say what to do (enrol this browser, or use another browser/device).

## 8. Keyboard map

| Keys | Action |
|---|---|
| `Tab` / `Shift+Tab` | normal focus order; a "Skip to content" link is the first stop |
| `g` then `o` / `d` / `i` / `g` / `a` / `s` / `t` | go to Overview / Devices / Invites / Organization (groups) / Audit / Security / Settings |
| `/` | focus the page's search field |
| `?` | shortcut help dialog |
| `Esc` | close dialog, drawer or menu; clear a focused search |
| `j` / `k`, `Enter`, `x` | in tables: next/previous row, open the row, toggle selection |
| `Ctrl/Cmd+Enter` | submit the focused form |

Shortcuts are ignored while typing in a field, with a modifier held (except where listed), or while a dialog is open.

## 9. Accessibility rules

Landmarks (`header`, `nav aria-label`, `main`, `aside` for the drawer); one `h1` per page; skip link; every control reachable and operable by keyboard
with a 2 px focus ring (`:focus-visible`, offset 2 px, 3:1 on both themes); targets at least 36 px (44 px on touch, `pointer: coarse`); status never by
colour alone; table semantics are real; `aria-live` for toasts and result counts; `aria-busy` while loading; reduced motion honoured; text resizes to
200% without loss (rem units, no fixed heights on text containers); no timeouts without a warning (the session countdown warns at 5 minutes and the idle
timeout at 25 minutes of inactivity, both with an "I am still here" button); print stylesheet for the invite card.

## 10. Internationalisation

All user-visible strings live in `js/strings/en.js` and are read through `t(key, params)`, with plural support (`{count, plural, one {...} other {...}}`
implemented as `t.plural(key, n, params)` selecting `key.one` / `key.other` through `Intl.PluralRules`). Numbers, dates and relative times use `Intl`
with the browser's locale. Adding a locale means adding `strings/<lang>.js` and listing it in `i18n.js`; keys missing there fall back to English.

## 11. Copy tone

Plain, short, active. Say what happened and what to do. "This device can no longer connect." not "Certificate serial revoked."; "The code is not right.
Wait for the next one." The words *serial*, *SPKI*, *bearer*, *challenge* appear only inside the drawer's "Technical details" section or the Security
page. Errors always end with a way forward and show `Reference: <correlationId>` in small type for support.

## 12. Implementation notes

- No build step: ES modules loaded by the browser, one shell (`index.html`), `css/tokens.css`, `css/base.css`, `css/components.css`, `css/pages.css`.
- Safe DOM: `dom.js` exposes `h(tag, props, ...children)` (text through `textContent`, allow-listed attributes, events through `addEventListener`) and
  `svg()`. There is no `innerHTML`, no `eval`, no inline script, style or handler anywhere; dynamic sizes use CSSOM (`el.style.setProperty`) or custom
  properties. The server's QR SVG is re-built element by element from an allow-list (`qr.js`), never inserted as markup.
- One fetch wrapper (`api.js`): same-origin, `credentials: same-origin`, `X-CSRF-Token` on every state-changing call, 15 s timeout with `AbortController`,
  central handling of 401 (session expired dialog), 403 `ERR_CSRF` (reload the session token once), 403 `ERR_STEP_UP`, 419, 429 with `Retry-After`, network
  failure; never logs bodies; secrets are never put in a URL or in `localStorage`.
- Step-up key: WebCrypto ECDSA P-256, `extractable: false`, kept in IndexedDB (the `CryptoKey` object), public key (SPKI) registered with the hub with a
  proof of possession. WebCrypto yields 64-byte `r||s`; the hub accepts that and DER (ADR-125). Needs a secure context (HTTPS or localhost).
- Server contract additions made for this console are listed in `docs/protocol.md` "Hub administration" (browser key enrolment, `metrics`, audit order, TOTP reset,
  hub public key in the delegation DTO).

### 12.1 File map (actual)

- `index.html` (shell), `favicon.svg`, `css/{tokens,base,components,pages}.css`.
- `js/main.js` boot; `js/app.js` signed-in frame and route table; `js/state.js` session, CSRF token and org cache; `js/api.js` fetch wrapper;
  `js/stepup.js` + `js/stepup-key.js` (WebCrypto key in IndexedDB) + `js/codec.js` (base64, proof message, console key id); `js/router.js` hash router;
  `js/i18n.js` + `js/strings/en.js`; `js/format.js`, `js/csv.js`, `js/tablemodel.js` (filter, sort, windowing, selection), `js/fingerprint.js`, `js/qr.js`,
  `js/icons.js`, `js/theme.js` + `js/theme-boot.js`, `js/shortcuts.js`, `js/sessionwatch.js`.
- `js/ui/*` (dialog, toast, table, shell, widgets, boundary, keydialog) and `js/views/*` (login, setup, delegation, overview, devices, devicepicker, invites,
  organization, audit, security, settings).
- `enroll-ui/landing.{css,js}` is the enrolment landing page's own code, served at `/enroll-assets/`.
- 45 files, 462 KB on disk, all text; no file over 400 KB, total under 2.5 MB (asserted by a test).

## 13. Verification record

Date: 2026-10-13. Commands and results:

| Check | Command | Result |
|---|---|---|
| Console web layer (headers on every asset, CSP string, traversal, redirects, source scan for forbidden patterns, listener policy closed) | `./gradlew :server:test --tests '*AdminConsoleWebTest*'` | pass |
| Console API additions (console key, P1363 + DER step-up, setup with key, TOTP reset, metrics, audit order, reject reason) | `./gradlew :server:test --tests '*AdminConsoleApiTest*'` | pass (20 tests with the previous class) |
| Front-end logic in node, no dependency (dom safety, api wrapper, csv, table model, router, codec, fingerprint, i18n key coverage) | `node --no-warnings --test "server/admin-ui-tests/*.test.mjs"` | 51 pass |
| Mutation check of the source scan | appended `el.innerHTML = s` to `js/format.js`, ran `AdminConsoleWebTest`, removed it again | the scan test failed as it must |
| Whole server module | `./gradlew :server:test` | 370 tests, 1 failure not caused by this work: `HubServicesTest.revokingADeviceKillsItsBearerAtOnceAndRemovesItFromTheDirectory` expects 401 for `GET revocations` without a bearer, while `ApiGate.DEFAULT_PUBLIC_PATHS` (not touched by this work) lists `PATH_REVOCATIONS` as public. The test and the gate disagree; it belongs to the contract/gate owner. |

Defects found by the real-browser run and fixed (also in `logs/errors.md`): missing `.table-scroll.short` and `.row-bad` rules; links styled as buttons were
underlined; the sign-in page offered "Start the setup" after the setup had finished; the role showed the raw key `role.ORG_ADMIN`; the audit "Contains" filter had
padding for a glyph that does not exist; cards in Security and the relay card in Overview lacked vertical spacing.

## 14. Screenshots and what was exercised

Screenshots: `docs/ui/hub-admin-console/*.png` (Edge 155, headless, driven over the DevTools protocol; hub started with the dev embedded root on localhost).
Per page (`overview`, `devices`, `invites`, `organization`, `audit`, `security`, `settings`) in light and dark at 1440 and 390 px (full-page captures: the fixed
bottom navigation shows mid-image at 390 and the left rail ends at viewport height at 1440 only because of full-page capture). Plus `login-*`,
`login-failure-light-1440`, `setup-1-token` ... `setup-5-done`, `invites-create-dialog`, `invites-created`, `invites-list`, `invites-revoke-confirm`,
`invites-after-revoke`, `security-revocations`, `security-revoke-serial-confirm`, `security-revocations-after`.
`setup-2-account` was captured at the default viewport, not 1440, and cannot be retaken without a fresh data directory.

Exercised end to end against a real hub in a real browser: setup wizard (token, account, authenticator QR and manual key, code, recovery codes, browser key
registered during setup), sign-in with TOTP, failed sign-in (plain message, reference id, spinner), sign-out, all seven pages rendering with no console errors and
no horizontal overflow (28 combinations of page, theme and width, measured `scrollWidth <= clientWidth`), invite creation (QR drawn, link shown once) and
revocation with confirmation, a real WebCrypto step-up (revoke certificate serial 4242, signature accepted, revocation list version 1), the audit trail of all of it,
dialog focus trap and Escape returning focus, the session countdown.

NOT exercised in a browser (tracked as `HUBU-*` in `docs/testing/TEST-BACKLOG.md`): enrolling and revoking a real enrolled device, the device table with data and
5,000 rows, Organization with data, bulk invites, delegation install, password change, recovery-code regeneration, TOTP reset, session expiry mid-action, idle
warning, invite print card, keyboard shortcuts dialog, Chrome / Firefox / Safari, screen readers, 200% zoom, air-gapped load.
