# Flash Hub tab redesign — web mock (2026-10-25)

**Status:** BUILT and screenshot-verified (12 page/state/viewport combinations, production build
green, zero console errors). **Built on a separate branch of the conversation after the owner
rejected the Atlas prototype; there were no Gradle changes** — `design/hub-web/` remains outside the
Gradle build, and `FlashHubScreen.kt` (UI-058), `docs/ui/flash-hub-tab.md` are untouched.
**Not device-verified (not applicable — browser prototype), not committed.**
Manual browser checks `HUBWEB-01..09` in [`docs/testing/TEST-BACKLOG.md`](../testing/TEST-BACKLOG.md).

**Code:** [`design/hub-web/`](../../design/hub-web/) — `npm run dev` → `:5180`.
`design/hub-web/README.md` is the primary doc; this file is the repository record.

---

## 1. Why

The owner rejected the Atlas prototype (see the SUPERSEDED banner in
[`hub-web-atlas-prototype.md`](hub-web-atlas-prototype.md)) with four failures: wrong shell (no
desktop sidebar, no Android bottom nav), invented visual language (amber accent instead of Pulse
teal), too much chrome for "the hub is a simple thing", and standalone design instead of *the Hub
tab* designed on mobile **and** desktop for review, with the approved design then replicated exactly
in Kotlin (UI-058).

## 2. What this redesign is

One Hub tab, shown in **both real shells side by side on one review page**:

| Host | Shell, copied from |
|---|---|
| Android | `FlashBottomNav` (UI-046): floating capsule, 60dp, 16/12 margins, hairline ring + soft shadow, sliding accent chip at 16% alpha (`calc(i*(25% - 2px) + 4px)`, width `calc(25% - 10px)`), weight-crossfading labels, badge pill |
| Desktop | `FlashNavigationRail`: 68dp, brand medallion (44dp, radius 12, accent 22%/8% gradient + 35% ring, inner 28dp well), 40dp icon wells with 14% accent fill, 3×22dp left edge bar on active, badge pill, 36dp profile avatar |

Tab sets follow UI-058 D1: enrolled = Chats · Hub · Nearby · Settings (badge: chat unread on Chats,
hub unread on Hub); unenrolled = Chats · **Transfers** · Nearby · Settings, and the Hub item
disappears on both hosts. Revoked keeps the Hub item rendering the removal notice.

Everything visual comes from the Compose sources, cited per block in `src/index.css`:
`FlashColors.light()/dark()` (Pulse teal accent, Spark amber secondary — light **and** dark themed
per frame, shown side by side), `FlashTypography` (the 12 text styles, 1px ≈ 1sp),
`FlashShapes` + `HubShapes` (16dp cards, 12dp controls, 6dp chips, squircle mark),
`FlashSpacing`, plus icon path data copied 1:1 from the `flash_ic_*.xml` drawables, and
`FlashAvatar`'s Kotlin `String.hashCode()` palette mapping so avatars match the app colour-for-colour.

## 3. The simplification (the owner's "too much information" brief)

1. **Four segments → three:** News · People · Groups. Departments become filter chips inside People
   (All · My department · each department) — the data stays reachable, one screen less.
2. **Hero card gone.** No gradient, stat tiles, count-ups, trust ring or animated dot. One calm org
   row: monogram, org name + live dot, "42 colleagues · 5 departments · 5 groups".
3. **Calm surfaces everywhere.** The 2026-10-20 soft-layered geometry is kept; per-card edge washes,
   department share bars and decoration are dropped.
4. **Honesty kept, fewer places:** plaintext-at-the-hub notice once per News surface (ADR-138),
   unpaired "how a message reaches them" honesty in the person detail, reach sentence
   (Wi-Fi now / through the company server / offline) tinted by tone.
5. Presence follows D2: withheld/unknown availability renders **nothing**, not a grey dot.

User-facing sentences are `FlashHubCopy.kt` text (via `COPY` in `src/flash/data.ts`).

## 4. States

Fresh · Offline · cached (neutral strip with data age) · Never synced (caution strip + empty state +
**Retry** that plays the 1.1 s skeleton into Fresh) · Loading (shimmer skeleton) · Removed ·
Not enrolled. Details open as a **bottom sheet on the phone** (over the nav, full scrim) and in the
**desktop detail pane** (reserved two-pane layout, calm empty state when idle) — the same components
render both.

## 5. Chain of custody to the Kotlin port

When (and only when) the owner is satisfied with this design, the port is a **copy of structure,
copy and geometry — not pixels**: `src/index.css` token blocks ↔ `FlashColors`/`FlashTypography`/
`HubShapes`, the shell chrome ↔ `FlashBottomNav`/`FlashNavigationRail` re-used as-is, segment/
card/row layout ↔ `src/flash/hub.tsx` sections. Then `docs/ui/flash-hub-tab.md` is updated to
describe what shipped, per its own consolidation rule.

## 6. Verification (this session)

- `npm run build`: tsc + Vite green (24.2 kB CSS, 257.6 kB JS / 79.4 kB gzip, maps only).
- `scripts/shots.mjs` (Playwright headless Chromium against `npm run preview` on :4173): 12
  screenshots covering fresh (dark/light), offline, never-synced, removed, not enrolled, person
  detail, reader, People segment, Groups segment, phone-only chats tab, phone-only light —
  **zero console errors**.
- Defects found by the screenshots and fixed in this session: desktop-detail/index scrim escaping
  the frame (needed `position: relative` on `.Desk`, and `HubScreen`'s sheet rendering gated to the
  phone layout so the desktop shows only the detail pane), the duplicate desktop sheet, withheld
  presence rendering a grey dot instead of nothing, and the "Share an update" row wrapping its icon
  onto its own line.
