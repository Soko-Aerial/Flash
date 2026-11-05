# Flash Hub console — alternative design notes (2026-11-05)

Status: **DESIGN PROPOSAL — prototype only.** Nothing here replaces
`server/src/main/resources/admin-ui/` until the owner approves it.

## Direction dealt

`draw.mjs --app --kind devtools --theme light --style achromatic-ledger`,
seed **1682487600** (2026-11-05), with `--keep palette,type` so the console
speaks the Flash app's palette and type rather than an invented one.

**The move inherited:** the chrome holds no colour. Counts, navigation and
controls are ink on a graphite ground; the one chart hue marks the screen's
main data element; status colour appears only on status.

**Redraws, each with a reason:**

1. **Palette mapped to Flash Pulse, not the sheet's `#1d5fbf`.** The app's
   accent is `pulse500 #0D9488` / `pulse400 #1FB8A6`. Keeping the dealt blue
   would make the console a different product. Teal is used only as the data
   hue and the live mark; the primary button stays ink (the style's move).
2. **Ground is cool graphite, not the sheet's off-white.** The hub is a
   monitor, not a ledger book: `graphite100 #EBEDF2` in light,
   `surface0 #101218` in dark, both taken from `FlashColors`.
3. **Shapes are Flash's, not the tight 8/6 default.** Cards 16, controls 12,
   chips 8 (`FlashShapes`) — 8 px cards would look like a different app.
4. **Dark theme is the primary review theme.** The app follows the system
   theme (`FlashTheme` uses `isSystemInDarkTheme`); this prototype defaults to
   dark so it can be judged next to the app, with a fully designed light theme
   one toggle away.

## The idea

> On this console, **the hub's live signal is the only colour on the page**.

The hub is a relay: the thing an administrator is actually looking at is
whether messages are moving and whether a person is waiting. The interface is
an instrument panel — graphite, hairlines, tabular figures — with exactly one
Pulse-teal element: the 60-minute relay strip. That is the face and the
signature element at once. A second, smaller signature is the **hub line**:
a 1 px rule in the sidebar's brand block that ends in a teal live dot, the
same line that reappears as the chart's baseline.

Nothing else is tinted. No gradient, no glow, no glass, no icon in a coloured
square, no sparkline on a KPI, no purple.

## Screen question and finding (Overview)

- **Question:** *Is the hub healthy, and who is waiting on me?*
- **Finding:** five people have asked to join; the oldest has waited two days.
- It sits directly under the title, and the filled primary action (Review 5
  requests) sits opposite it. The largest block is the relay strip; the largest
  figure is **74 online**, which answers the health half of the question.

## Data inventory (sample, believable, reconciled)

- 138 devices: **128 active, 4 revoked, 6 not enrolled**; 74 of the active
  devices are online. The nine departments' member counts sum to 128.
- 50 invites: **12 waiting, 34 used, 3 expired, 1 revoked**.
- 5 join requests, oldest 2 days; 3 administrators; 14 groups.
- Relay: 1,847 messages delivered in the last 60 minutes (the 60 per-minute
  bars sum to this), 0 queued now, 3 refused today, median 41 ms, p95 118 ms.
- Delegation: active, 9 days left. Audit: 12,840 entries, last verified 28 Oct.
- Files through the hub: 2.1 GB of 5 GB (42%), 37 of 50 files.

`js/data.js` asserts every equality above; totals are computed from rows.

## Layout arithmetic

- 1440 window − 248 sidebar = 1192 main column; 32 px gutters = **1128 canvas**.
- 12 columns × 75.67 + 11 gaps × 20 = **1128**. Overview uses 8/4 and 5/4/3.
- Sidebar 248, nav row 36 on a 40 pitch, group label 12 px with 24 px above.
- Card radius 16, padding 20, row pitch 44, control height 32 (36 in forms).

## What the prototype deliberately is not

- It is not wired to the hub API. Actions mutate in-memory sample state and
  show the same confirmations, step-up wording and honesty notices the shipped
  console uses.
- It does not copy the shipped CSS or JS. It is a standalone design artifact in
  `design/server-console/`; the shipped path is untouched.
