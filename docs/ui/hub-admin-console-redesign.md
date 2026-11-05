# Hub admin console — alternative design record (2026-11-05)

**Status: APPROVED, PORTED AND MERGED on 2026-11-05 (`dev`, merge `ec6c3724`; local, not pushed). The shipped console now uses this design.**

This record exists because the owner asked for an alternative to
`server/src/main/resources/admin-ui/` that conforms to the Flash app, and asked
for the current implementation to stay untouched until the design is approved.

- Review build: [`design/server-console/`](../../design/server-console/)
- Build notes and data: [`DESIGN-NOTES.md`](../../design/server-console/DESIGN-NOTES.md)
- Run and review: [`README.md`](../../design/server-console/README.md)
- Final screenshots: [`shots/final/`](../../design/server-console/shots/final/)
- Old console record (historical, superseded): [`hub-admin-console.md`](hub-admin-console.md)

---

## 1. Why an alternative

The shipped console is functionally strong but visually reads as a generic
admin template: warm off-white ground, indigo accent, 4 px controls, a top bar
above a second top bar, four KPI tiles, and boxed cards for every block. It
does not share the app's authored identity — graphite, Pulse teal, soft 16/12/8
shapes, one type scale — and the owner did not find it convincing as a
professional product.

The alternative keeps the product truth (the same jobs, the same security
story, the same plain sentences) and replaces the visual world and the
composition.

## 2. Direction

`draw.mjs --app --kind devtools --theme light --style achromatic-ledger`,
seed **1682487600**, with `--keep palette,type` to take the palette and type
from the Flash app.

**The idea:** *the hub's live signal is the only colour on the page.*

The console is an instrument panel. The chrome is graphite and ink; the only
hue is Pulse teal on the 60-minute relay strip, its live dot, and the status
chips. No gradient, glow, glass, icon-in-a-tinted-square, sparkline on a KPI,
purple, or floating card. The Flash bolt and the hub line carry the mark; the
domain vocabulary carries the identity.

**The face:** a cool graphite ground (not warm paper), a single system-ui
grotesk, Cascadia Mono for identifiers and invite codes, and the relay strip as
the signature element. **The redraws** (Pulse instead of the dealt blue,
graphite instead of off-white, Flash 16/12/8 shapes instead of 8/6) are
recorded with reasons in `DESIGN-NOTES.md`.

## 3. Information architecture

```text
Flash Hub · Meridian Works · Production
├── Hub
│   ├── Overview
│   ├── Devices
│   └── Invites
├── People
│   ├── Join requests           (badge: 5)
│   ├── Organization            (departments, groups)
│   └── Announcements
├── Trust
│   ├── Audit
│   └── Security                (administrators, sessions, delegation, revocations, trust)
└── Settings                    (foot of the rail)
```

The change from the shipped console is deliberate: **Join requests is its own
queue**, not a tab inside Organization, because a queue needs a noun, a count
and a destination. Invites (outbound) and Join requests (inbound) are the two
halves of the same lifecycle; the Overview is where they meet.

## 4. Composition

- **Open plane, no top bar on desktop.** A 248 px labelled rail on the page
  ground, a single hairline, a live session row and the administrator at the
  foot. The page begins 30 px below the top of the canvas.
- **One question first.** The Overview lede is the answer; the finding is the
  second line; the filled primary is **Review 5 requests**. The 60-minute relay
  strip is the largest object, but the human backlog is above the fold at
  every width.
- **A health strip, not KPI tiles.** One panel divided by hairlines: Online now
  (30 px lead), Active devices, Relay queue, Uptime. No icons, no sparklines.
- **A decision queue, not a banner.** Join requests, delegation expiry and
  audit-chain state are three rows with counts and destinations. The note at
  the foot is a reassurance, not a second call to action.
- **One ledger, not three equal cards.** Devices online, Invites waiting and
  Latest activity share one surface and three dividers, so their bottoms align.
- **One left edge, one right edge.** 1440 − 248 rail = 1192; 32 gutters =
  1128 canvas; 12 × 75.67 + 11 × 20 = 1128. Overview uses 8/4 and 5/4/3.
- **Tables are rules and alignment.** 12 px sentence-case headers, 48 px rows,
  right-aligned tabular figures, one status mark, one trailing affordance.
- **Devices and audit use one tab stop per table**, with j/k row navigation;
  role/department/certificate move into the identity subline on smaller
  screens rather than disappearing without a trace.

## 5. Design system

Mapped from `ui/theme` rather than invented:

| Role | Dark | Light |
|---|---|---|
| Ground | `surface0 #101218` | `graphite100 #EBEDF2` |
| Surface | `surface1 #181C26` | `#FFFFFF` |
| Raised | `surface2 #222836` | `graphite50 #F5F6F8` |
| Ink | `graphite50 #F5F6F8` | `graphite900 #171A22` |
| Secondary | `graphite300 #A8B0C0` | `graphite700 #454C5C` |
| Tertiary | lifted `#8B94A6` | `#5F6876` |
| Data hue | `pulse400 #1FB8A6` | `pulse500 #0D9488` |
| Primary button | inverted ink `#F5F6F8` | ink `#171A22` |
| Control outline | `#6E778A` (≥3:1) | `#8B94A6` (≥3:1) |
| OK / warn / bad | app status pair | app status pair |

- **Type:** `FlashTypography`'s system face; 24/32 title, 16/22 section,
  14/20 body, 12/16 meta, 11/14 ticks, 30/34 lead figure. Tabular figures on
  every number; one mono face for IDs, serials, invite codes and commands.
- **Shape:** 16 card, 12 control, 8 chip, pill for status; the app's soft
  language, not the 8/6 default.
- **Space:** 4 px base; 20 px card gap/padding; 32 px gutter; 44 px table
  rows; 34 px nav pitch.
- **Colour budget:** the chrome holds none. The only hue is the relay data
  (teal), status chips, and the focus ring. The primary action is ink.

## 6. Screen notes

| Screen | Its one question | What makes it specific |
|---|---|---|
| Overview | Is the hub healthy, and who is waiting on me? | Relay strip, decision queue, ledger; the finding is a sentence, not a banner. |
| Devices | Which device is this, and should it still have access? | 138-row table, drawer with TLS identity, certificate and address, audited revoke. |
| Invites | Who have I invited, and which code still works? | Code-once dialog, state legend, revoke; no re-copy from the list. |
| Join requests | Who is asking, and can I stand behind the approval? | Not-paired context, phone-code confirmation gate, audit entry. |
| Organization | Who is in which department or group? | Two tables, drawers with the head and member sample; totals follow revocations. |
| Announcements | What has the organisation been told, and what should I publish? | Plaintext notice, inline composer on desktop / dialog on mobile, reader with pin. |
| Audit | Can I prove what happened? | Chain-intact strip, verify action, filters, entry drawer; new actions append rows. |
| Security | Who can administer this hub, and can they still? | Administrators, sessions with immediate sign-out, delegation with the offline command, revocations, trust chain. |
| Settings | What is this hub actually doing? | Read-only rows with a consequence preview (session end, invite expiry, file limit, retention). |
| Login | Can I get in without leaking anything? | A single 400 px gate, honest "loads nothing outside your network", recovery-code path. |

## 7. Critique result

Run as two isolated assessments, as the Impeccable method requires.

**Assessment A (design review, no detector): 26/40, Good.**
Strengths: the overview answers its question first; domain-authentic copy;
disciplined fundamentals (focus, reduced motion, tabular figures, light theme).
Priority findings: a P0 overflow on Security → Delegation/Trust; a mobile select
that displayed the wrong current view; promises the prototype could not keep
(code-once vs list copy, audit entries that never appeared, pin/revoke actions
missing); 138 row tab stops; advertised shortcuts that did not exist; 11 px body
text; and an under-filled Trust page.

**Assessment B (detector + browser evidence).**
- Source detector: 6 `side-tab` warnings, all false positives (severity edges
  on toasts/banners/audit strips, not decorative ranks).
- Browser: 0 console errors, 0 unlabelled icon buttons, 0 horizontal overflow
  on 39 default route runs; one real contrast defect (the `·` separator at
  1.34:1) and 5 tiny-text nodes on Overview.

**Fixed after the critique:** the P0 overflow (wrap the fingerprint text and
keep the copy button visible); mobile select values (set after options exist);
tab ARIA (a pressed-button group with accessible names, transform-only
underline); the separator and control-border contrast; 12 px supporting copy;
code-once invites with revoke; the approval gate; pin/unpin; audit rows for
every write; session tab count; department totals; `g` shortcuts, `/`, `?`,
`j/k`, roving row focus; per-route titles; section h2s; Production in the
mobile brand; toast offset above the bottom nav; and 40 px phone controls.
The formal record is in `README.md` §5.

## 8. Verification

- **Direction:** `draw.mjs --app` sheet, seed 1682487600; redraws documented.
- **Impeccable:** dual-agent critique, then fixes; re-measured P0/deep-link/
  contrast/tiny-text items.
- **Taste inspector:** Overview `0 FAIL, 1 WARN (tiny-text)`; Requests,
  Organization, Announcements, Audit `0 FAIL, 0 WARN`; Devices, Invites,
  Security, Settings `0 FAIL` with tiny-text only; Login `0 FAIL, 2 WARN`
  (tiny-text and a deliberately sparse auth gate, defended in the README).
  The app-screen rules allow the 12 px metadata the tiny-text rule flags.
- **54-page sweep** (18 routes × 1440/768/390): 0 document overflows, 0 unnamed
  controls, 0 console/page errors, 0 computed text-contrast failures.
- **Reduced motion:** Chrome computes ~0.01 ms transitions/animations; dialogs
  close immediately.
- **Final screenshots** reviewed at every breakpoint, dark and light, plus
  drawer, approve dialog, invite-created dialog, help and More sheet.

## 9. Known limitations

This is a design prototype, not a release candidate:

- no hub API, persistence, real step-up/WebCrypto, real audit chain, QR/print,
  invite redemption, file browser or lockout state;
- sample data is a single believable world; actions are in-memory;
- Chrome only (no Safari/Firefox);
- no Gradle, server or shipped asset changes.

## 10. Port (approved 2026-11-05)

The owner approved the design. The shipped console now uses it. The port was
deliberately **not** a wholesale copy of the prototype:

- **New `css/redesign.css`** loads after the functional base styles. The real
  `components.css`/`pages.css` stay underneath for the feature classes; the
  redesign supplies the palette, type, shapes, shell, auth and Overview.
- **`js/ui/shell.js`** was rewritten to the open-plane sidebar / mobile bar /
  bottom bar. It keeps `createShell`'s full API (`setActive`, `setBadge`,
  `setHub`, `setUser`, `setCountdown`, `refreshTheme`), the real theme cycle,
  help, session countdown, sign-out and the route table.
- **`js/views/overview.js`** was rebuilt against the real endpoints. The relay
  strip is built from live console samples because the hub deliberately keeps
  no history (`MetricsDto` and the old console both state this); it says so
  until the strip fills. No fabricated bars.
- **`js/views/login.js` `authFrame`** carries the approved brand/hub line/card;
  the real password, authenticator, recovery, lockout, expired-session and
  sign-out paths are untouched and were exercised.
- **Join requests** became `#/requests` (the real join-request screen); the
  Organization page now only has Departments and Groups. Old
  `#/organization?tab=requests` links redirect.
- **All other screens keep their real tables/forms/drawers/step-up.** The
  design system applies; the prototype’s simplified invite form and
  announcement composer were not allowed to remove existing features.
- **No fictional org/environment:** the sidebar shows the name from
  `/overview`. “Meridian Works” and “Production” were review data and are not
  in the shipped console.
- **Verification:** console Node tests 83/83; `AdminConsoleWebTest` 15/15;
  `AdminConsoleApiTest` 10/10; browser review at 1440/768/390 across the
  routes; sign-in and sign-out exercised.

Owed owner/browser checks remain in `docs/testing/TEST-BACKLOG.md` as
`CONSOLE-D02..D07`.
