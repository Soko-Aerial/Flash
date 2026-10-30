# Flash Hub tab — redesign (web mock, 2026-10-25)

A **design prototype of the Hub tab inside the real Flash shell**, for both hosts at once:

- **Android**: the real hanging bottom-nav capsule (UI-046) with Chats · Hub · Nearby · Settings.
- **Desktop**: the real 68dp icon rail with the brand medallion, badges and profile avatar.

It exists because the owner rejected the first prototype ("Flash Hub — Atlas", 2026-10-24) for not
conforming to how Flash actually looks on desktop and mobile, and for carrying too much information
for what "the hub is — a simple thing". This rebuild sits inside Flash's actual navigation and uses
Flash's actual design system. `docs/ui/hub-web-atlas-prototype.md` is the preserved record of the
rejected attempt (SUPERSEDED).

Standalone React app: no Gradle wiring, no changes to `FlashHubScreen.kt` (UI-058), no network calls,
no webfonts, one static world in `src/flash/data.ts`.

---

## 1. Run it

```bash
cd design/hub-web
npm install            # once
npm run dev            # http://localhost:5180
```

```bash
npm run build          # type-check + production bundle into dist/
npm run preview        # serve that bundle on :4173
node scripts/shots.mjs # screenshot every state into shots/ (needs the preview running)
```

Deps: React 19, Tailwind 4, Vite 7, Playwright (screenshots only). State is in-memory; URL params
pin a view for review links: `?state=offline&theme=light&view=phone&segment=people&open=p3`.

**Controls (page chrome only, not the app design):** App state · Theme · which frames are visible.

---

## 2. What changed against the shipped 2026-10-20 Hub ("too much information" brief)

1. **Four segments become three** — News · People · Groups. Departments stop being a segment;
   they are filter chips inside People (All · My department · each department). Nothing is lost:
   department reachability is one tap in the People segment instead of one screen more.
2. **The hero card is gone.** No gradient, no stat tiles, no count-ups, no trust ring, no animated
   dot. One calm org row answers the two questions the hero answered: *which organisation, is it
   connected* (monogram, name + presence dot, counts line). Trust/lease wording lives in the strip.
3. **Calm rows everywhere.** Cards keep the 2026-10-20 soft-layered shapes (16dp cards, 12dp
   controls, 6dp chips, hairline-tabs with the 3dp gliding underline) but lose the per-card edge
   washes, department share bars and decoration.
4. **Same honesty**, fewer places: the plaintext-at-the-hub notice appears once per surface (News
   list footer + reader), the unpaired "how a message reaches them" sentence lives in the person
   detail, and the reachability strip is plain tinted text.

Kept from the product: the exact `FlashHubCopy.kt` sentences, the D1 tab sets, the revoked /
never-synced / offline-cached / unenrolled states, the detail-pane (desktop) / bottom-sheet (phone)
contract, the pinned-card teal outline and unread dot.

## 3. States

| State | What it proves |
|---|---|
| **Fresh** | Enrolled, connected: org row live dot, unread badge on the Hub tab, news dots. |
| **Offline · cached** | Neutral strip "Offline · showing information from 12 min", grey dot, content readable. |
| **Never synced** | Caution strip + empty state + Retry (Retry animates a 1.1 s skeleton into Fresh). |
| **Loading** | Shimmer skeleton rows (`flashShimmer` pattern, no spinner). |
| **Removed** | The Hub tab becomes the removal notice with "Sign out of organisation"; paired chats unaffected. |
| **Not enrolled** | The Tab set is the consumer four (Chats · Transfers · Nearby · Settings) — the Hub item disappears on both hosts. |

## 4. Functions this mock needs the Hub to have (what the Kotlin port must provide)

Each line maps to a piece of the production repository the port can reuse; this is the reading list
for the owner. `src/flash/hub.tsx` shows every one of them on screen.

| Function | Backing it in production |
|---|---|
| Org row: name, live dot, counts line | `FlashHubStatusMath` / hub info from `FlashChatRepository` |
| Offline-cached strip with data age | last successful sync time (already stored) |
| News: pinned-first feed, scope chip, unread dot, `Share an update` for heads/admins | announcements API + roles the Hub tab already has |
| Reader + plaintext notice (ADR-138) | existing announcement store |
| Composer (title + body, audience = your department) | existing composer validation |
| People: department filter chips, presence without colours-alone (D2: withheld = no dot) | directory snapshot `OrgDirectoryDao` search |
| Person detail: reach sentence (Wi-Fi vs through the hub), Message / Call gating, unpaired honesty | `OrgPeerResolver` reachability + `PeerTrustPolicy` |
| Groups: yours / other-department split, open vs Join vs Requested | group store + `GroupGate` |
| Search: people / news / groups grouped results, Enter opens first hit | repository search + sort order shown here |
| Refresh path: Retry → skeleton → fresh | the existing refresh mechanism |

## 5. Files

```text
src/
  index.css        ALL tokens, copied from the Compose sources (file paths cited per block):
                   FlashColors, FlashTypography, FlashShapes + HubShapes, FlashSpacing,
                   FlashBottomNav geometry, FlashNavigationRail geometry. Theme = one class/frame.
  flash/icons.tsx  Flash glyphs with path data copied 1:1 from flash_ic_*.xml drawables.
  flash/atoms.tsx  FlashAvatar with the Kotlin String.hashCode palette mapping (same person,
                   same colours as the app).
  flash/data.ts    The one world: org, people, news, groups + COPY (FlashHubCopy sentences).
  flash/state.tsx  Global store (scenario/theme/view + URL params) + per-frame store.
  flash/hub.tsx    THE Hub tab: every screen and state in one readable file.
  flash/tabs.tsx   Chats / Nearby / Settings / Transfers, deliberately light.
  flash/shells.tsx Phone frame + desktop window, both real chrome.
scripts/shots.mjs  Screenshot harness (12 page/state/viewport combinations).
```

## 6. What this deliberately does not do

- No protocol, no TLS, no persistence; read-marking survives only per reload.
- The other tabs are skeletal: their job is to prove the shell, not to be redesigned here.
- No admin console, no multi-org, no calls/transfer screens — parked as before.
- Not yet the Kotlin port: after the owner approves this design, `FlashHubScreen.kt` (UI-058)
  is restyled to match, then `docs/ui/flash-hub-tab.md` is updated to describe what shipped.
