# Flash Hub console — alternative design (review build)

**Status: PROPOSAL — design prototype only. Only the owner can approve the port.**
The shipped console in `server/src/main/resources/admin-ui/` has **not** been changed.
This folder is a standalone, dependency-free design artifact for review at
`http://localhost:5182/`.

- Design direction and data: [`DESIGN-NOTES.md`](DESIGN-NOTES.md)
- Design record / critique / port plan: [`docs/ui/hub-admin-console-redesign.md`](../../docs/ui/hub-admin-console-redesign.md)
- Final review screenshots: [`shots/final/`](shots/final/)
- Impeccable evidence: [`shots/impeccable/`](shots/impeccable/)

---

## 1. Run it

```bash
cd design/server-console
node scripts/serve.mjs          # http://localhost:5182/
```

No `npm install`, no build step, no CDN, no web font, no external image. The console
needs only a static file server. `scripts/serve.mjs` is a 50-line Node server.

For the screenshot and inspection scripts only, install Playwright once:

```bash
npm install                     # optional: playwright, for scripts/*.mjs
node scripts/final-shots.mjs    # screenshots into shots/final/
node scripts/sweep.mjs          # overflow / contrast / console sweep
```

The prototype itself never depends on Playwright.

---

## 2. What this is

An alternative to the current admin console, built to the same product truth
and the same security model, in the Flash app's visual language rather than a
generic admin template.

**Direction dealt by `draw.mjs --app`:** `achromatic-ledger`, seed
`1682487600`, with `--keep palette,type` so the palette and type come from
`FlashColors.kt` / `FlashTypography.kt`. The chrome is graphite and ink; the
only colour on the page is the live relay signal (Pulse teal) and status.

**The idea:** *the hub's live signal is the only colour on the page.* The
console is an instrument panel, not a card gallery. The 60-minute relay strip
is the signature element; the hairline "hub line" in the sidebar ends in the
same live dot as the chart. The product's own vocabulary — delegation, root
key, approver phone, revocation list, not paired, plaintext on the hub — does
the identity work the visuals refuse to fake.

**The screen question (Overview):** *Is the hub healthy, and who is waiting on
me?* The answer is the lede: healthy, 74 of 128 online, five people waiting;
the finding is the second line; the filled primary is Review 5 requests. The
largest block is the relay strip; the largest figure is 74 online.

Full inventory, reconciliations and layout arithmetic: `DESIGN-NOTES.md`.

---

## 3. Screens and routes

All routes are hash routes, matching the shipped console's deep-link model.

| Route | What it is |
|---|---|
| `#/overview` | Health strip, 60-minute relay strip, decision queue, devices/invites/activity ledger |
| `#/devices` | 138-row searchable/filterable table, roving row focus, device drawer, revoke with audit record |
| `#/devices` → row | TLS identity, certificate, address, recent activity, revoke |
| `#/invites` | Waiting/Used/Expired/Revoked, search, inline create on desktop, dialog on tablet/phone, code-once dialog, revoke |
| `#/requests` | Approval queue, Not paired context, step-up dialog with a real confirmation gate |
| `#/organization` | Departments and Groups, with a department/group drawer |
| `#/announcements` | Feed + composer (inline desktop, dialog tablet/phone), plaintext notice, reader with pin/unpin |
| `#/audit` | Filterable log, verify-chain action, entry drawer, new actions appended |
| `#/security` | Administrators, Sessions, Delegation, Revocations, Trust + trust chain |
| `#/settings` | Access, Files, Relay, Directory, Health, About; read-only configuration with consequences |
| `#/login` | Sign-in gate; recovery-code path; static demo validation |
| `?tab=` | `organization`, `security`, `settings` preserve their view in the URL |
| Mobile `More` | Full nav sheet, settings, shortcuts, sign out |
| `?` | Keyboard shortcut dialog; `g` then `o d i r a s t`; `/`; `j`/`k` row focus |

The UI is a **design demo**: actions mutate in-memory sample data, append audit
rows, show toasts, and update cross-screen totals. It is not wired to the hub
API. The step-up code is illustrative; no crypto runs in the page.

---

## 4. What changed against the shipped console

1. **App palette, not warm paper + indigo.** Graphite ground, Pulse teal data,
   one ink primary. Dark is the review default (the app follows the system
   theme); light is fully designed, not an inversion.
2. **Open-plane shell, not a top bar over a top bar.** A labelled 248 px
   sidebar on the page ground, groups (Hub · People · Trust), a neutral active
   pill, a live session countdown, and no second bar on desktop. Tablet folds
   to a 56 px bar + page sheet; phone gets a 5-cell bottom bar and a More sheet.
3. **Colour on data, not chrome.** No tinted KPI tiles, no coloured nav rows,
   no gradient, no glow. The relay strip is the one hue; the health strip is a
   single panel divided by hairlines; primary actions are inverted ink.
4. **A decision queue, not a banner.** Join requests, delegation expiry and
   audit-chain state are rows with counts and a destination, visible on the
   first screen. Review 5 requests is the one filled action.
5. **One ledger row instead of three identical cards.** Devices online,
   invites waiting and latest activity share one surface and three dividers, so
   the row ends on one line at every width.
6. **Person/device lifecycle made explicit.** Invites (outbound) and Join
   requests (inbound) are separate destinations with clear nouns and badges.
7. **Truthful states.** Codes are shown once and can be revoked, not re-copied
   from the list; new actions append audit entries; session sign-out updates
   the count; department totals follow revocations; empty/filtered states exist.
8. **Responsive intent, not a squeezed desktop table.** Tablet keeps Device,
   Status, Last seen, Certificate and moves Department into the identity
   subline; phone keeps Device + Status + a Department/Last-seen subline and
   moves the rest into the drawer. Long tab sets become labelled selects on
   phone; the active value is correct on deep links.
9. **Accessibility floor.** 2 px focus rings, skip link, one h1 and a section
   h2 per page, 12 px minimum supporting copy, tabular table figures,
   44 px-class phone actions, one tab stop per table with j/k + roving focus,
   reduced-motion fallbacks, and an aria-hidden decorative separator.
10. **Motion is feedback.** 120–200 ms ease-out colour/scale on controls,
    200 ms dialog/drawer enter and 140 ms exit, transform/opacity only, a
    quiet live-dot pulse, and a global `prefers-reduced-motion` stop.

---

## 5. Critique and verification (2026-11-05)

The review followed the strict four phases.

### Phase 1 — Direction
`draw.mjs --app --kind devtools --theme light --style achromatic-ledger`,
seed `1682487600`; two dark alternatives were dealt and rejected on brand
fidelity. Redraws: Pulse palette instead of the dealt blue, graphite ground
instead of off-white, Flash 16/12/8 shapes instead of 8/6. Reason recorded in
`DESIGN-NOTES.md`.

### Phase 2 — Impeccable critique (dual-agent)

**Method:** two isolated sub-agents, A (design review, no detector) and B
(detector + live browser evidence). A scored the first build **26/40** (Good)
and identified one **P0**: the Delegation/Trust root fingerprint was an
unbreakable 595 px row, causing page overflow at 1440/768/390.

**Fixed after the critique:**
- **P0 overflow** in Security → Delegation/Trust: `.copy-row` can wrap, the
  fingerprint text breaks by design, and the copy control stays visible.
  Re-measured: 1440/768/390, all tabs, `scrollWidth === clientWidth`.
- **Mobile select lied on deep links** (`?tab=delegation` displayed
  “Administrators”): the value is now set after the options exist. Same fix
  for Settings and the Devices status select.
- **Tab ARIA** was a tablist with no tabpanels: replaced with a group of
  pressed buttons and explicit accessible names (`Waiting, 12`), plus a
  scaleX underline that is transform-only.
- **Contrast:** the `·` separator was 1.34:1; it is now `aria-hidden` and
  tertiary. Control borders were 1.58:1 on white / 1.98:1 on dark; they are
  now ≥3:1. A full computed-text sweep found 0 AA failures after the fix.
- **Tiny text:** the five 11 px Overview body nodes the overlay flagged are
  now 12 px; announcement-meta no longer uses a 9 px avatar; phone badges and
  annotations are 11 px. The remaining 11–12 px text is metadata/axis ticks,
  which the app-screen rules permit.
- **Honesty gaps:** invite codes are shown once (list copy removed) and a
  pending invite can be revoked; approval is gated on the phone-code
  confirmation; pin/unpin works; approve/decline/revoke/invite/publish/verify
  append audit entries; session sign-out updates its tab count; department
  totals follow revocations; the overview “last verified” date updates.
- **Keyboard/ARIA:** the advertised `g` shortcuts, `/`, `?`, `j`/`k`, roving
  table focus, section h2s and per-route document titles are implemented.
- **Mobile:** Production is visible in the brand; toasts clear the bottom nav;
  the More tab reflects the current page; phone controls are 40 px.

Detector (B): 6 `side-tab` warnings, **all false positives** — each is a
severity edge on a toast/banner/audit strip, not a decorative rank. Browser:
0 console errors, 0 horizontal overflow on 39 default runs (the P0 was only on
the Delegation/Trust tabs), 0 unnamed icon buttons, 1 real contrast defect
(the separator) fixed.

### Phase 3 — Motion
- Buttons: 120 ms colour + 0.985 press scale; icon buttons 0.94.
- Theme icon: 200 ms rotate/scale swap.
- Dialog/drawer: 200 ms enter, 140 ms exit, opacity + translate only;
  Esc, backdrop and Cancel all take the same exit.
- Toasts: 160 ms enter, 140 ms swipe-free fade/rise exit.
- Tabs: scaleX underline, 160 ms.
- Live dot: opacity pulse, 2.4 s.
- `prefers-reduced-motion: reduce` verified in Chrome: computed transition and
  animation durations drop to ~0.01 ms and the dialog closes immediately.
- No transition animates height, width, top, left, margin or padding.
  (The skip link was converted from `top` to `transform`.)

### Phase 4 — Inspection
- `inspect.mjs --app` on Overview: **0 FAIL, 1 WARN** (`tiny-text`: 53% of
  text under 13 px). Defended: the Flash app-screen rules allow 12 px
  metadata and 11 px axis ticks; primary content is 14 px, and every measured
  text contrast passes. The remaining “hero-void” on Login is an auth gate
  with one task, deliberately centred; there is no secondary content to put
  there.
- All other inspected routes: **0 FAIL**. Requests, Organization,
  Announcements, Audit: 0 warnings. Devices/Invites/Security/Settings:
  tiny-text warning only. Login: 0 FAIL, 2 WARN (tiny-text and the defended
  auth-gate void).
- 54-page sweep (18 routes × 3 widths): **0 document overflows, 0 unnamed
  controls, 0 console/page errors, 0 computed text-contrast failures.** The
  only small target is the inline “Audit” text link (27×24; WCAG 2.5.8 AA
  minimum is 24).
- Screenshots reviewed at 1440, 768 and 390 in dark and light, plus dialogs,
  drawer and More sheet.

---

## 6. Known limitations (do not overclaim)

- **Not wired to the hub API.** All writes are in-memory; a reload resets.
- **The step-up code is illustrative.** The checkbox models the phone gate;
  no ECDSA/WebCrypto or approver-phone transport exists in this prototype.
- **No QR/print card flow, no real invite link redemption, no file browser,
  no live tail, no auth lockout state.** Those are deliberate scope cuts, not
  approved product behaviour.
- **No real persistence, no real audit hash chain.** The audit view records
  demo rows; the real console's chain verification remains the authority.
- **Chrome-based verification only.** No Safari/Firefox run; system-ui uses
  Segoe UI on Windows. The shipped console's own browser matrix still applies.
- **No production deployment.** No Gradle, no server change, no build script.

---

## 7. If the owner approves: port plan

Do **not** copy this folder wholesale into `server/`. The shipped console has
a CSP-safe DOM layer, i18n, step-up, error boundaries and print rules that must
survive. Port in this order:

1. **Tokens and shell** — map `css/tokens.css` values onto the existing
   `tokens.css`; port the open-plane sidebar/mobilebar/bottom nav; keep the
   existing router and landmarks.
2. **Overview** — replace the current tiles/cards with the health panel, relay
   strip (real metrics), decision queue and ledger; keep the existing 15 s poll
   and partial-failure behaviour.
3. **Tables and mobile** — apply the table type, column priorities, roving
   focus and select fallbacks; keep windowing above 200 rows.
4. **Dialogs and actions** — port the code-once invite dialog (no list copy),
   invite revoke, pin/unpin, the approval gate and audit-entry refresh; wire
   the real step-up.
5. **Remaining screens** — Devices drawer, Security delegation/trust,
   Settings tabs, About/shortcuts, Login.
6. **Verification** — run the existing `:server:test` and console Node tests;
   add the visual/manual checks in `docs/testing/TEST-BACKLOG.md`; then delete
   this prototype or keep it as the design record.

Files to carry: `css/tokens.css`, `css/shell.css`, the screen classes in
`css/screens.css`, `js/app.js` shell helpers, `js/views/common.js`,
`js/views/overview.js`, and the route-level markup. Do not carry the sample
`data.js` or the Playwright scripts into product code.
