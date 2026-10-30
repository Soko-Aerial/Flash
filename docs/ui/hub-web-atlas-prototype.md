# Flash Hub — Atlas (web prototype)

> **SUPERSEDED 2026-10-25 — REJECTED BY THE OWNER.** The owner's verdict: *"the design is too bad,
> you did not conform to how Flash looks right now on a desktop and mobile. I wanted you to design
> for the hub tab on mobile and on desktop, but you just did your own thing. And I wanted you to use
> the web to build, then if I am satisfied, replicate it in Kotlin exactly. And you did not take into
> consideration the sidebar in desktop and the bottom nav on Android. And there is too much things
> and information where the hub is a simple thing. Redesign it."*
>
> Four specific failures, kept here so they are not repeated: (1) it invented its own shell instead
> of living inside Flash's desktop sidebar (68dp `FlashNavigationRail`) and Android bottom nav
> (UI-046 hanging capsule); (2) it invented its own colour system (amber accent) instead of
> FlashPalette's Pulse teal; (3) five full screens + Control room was far too much chrome for what
> the hub is; (4) it was designed standalone rather than as *the Hub tab* the owner reviews before a
> Kotlin port. The replacement is [`docs/ui/hub-web-redesign-2026-10-25.md`](hub-web-redesign-2026-10-25.md).
> This document is preserved as historical memory (§27); the sections below describe the rejected
> build and its §7 open decisions are moot.

**Status (original, 2026-10-24):** BUILT as a standalone web prototype; type-checked, production build
green, and screenshot-verified in 12 page/state/viewport combinations with a clean console. Not device-
verified and not committed. It was not UI-058, it was not product code, and it was not part of the
Gradle build.
**Kind:** design prototype + function inventory, in the same spirit as the three Android competition
entries. It exists so the owner can view the Hub in a browser and read the code to see which functions
the Hub needs.

**Code:** [`design/hub-web/`](../../design/hub-web/), entry `src/App.tsx`, worlds
`src/data/world.ts`, kit `src/components/primitives.tsx`, store `src/state/hub.tsx`.
**Read first:** [`design/hub-web/README.md`](../../design/hub-web/README.md) — §4 there is the
function-by-function inventory this document does not repeat.

---

## 1. Why this exists

The owner ran a design competition for the next Hub experience and then asked for a **web version** of
the Hub that (a) they can look at in a browser and (b) they can read as code, to extract the list of
functions the real Hub needs. The three entries are Android/Compose and dead code by design; the web
prototype is the same material with the best of each entry recombined, plus the states the product
specification requires but no entry showed.

It changes no production file. `FlashHubScreen.kt`, `docs/ui/flash-hub-tab.md` and UI-058 are
untouched.

---

## 2. Sources, and what each entry contributed

| Source | Taken |
|---|---|
| **Apex Zenith** (`ui/chat/.../hub/competition/apex/`, `docs/ui/hub-competition-apex-zenith.md`) | The "Command centre" reading of the Hub: identity hero, telemetry with sparks, latency/throughput chart, trust chain as first-class surface, relay event table, cryptographic honesty. |
| **NOVA** (`.../competition/novahub/`, code only — no design doc) | Tone washes, staggered entrance, stat tiles, the seven-world scenario model, the offline-lease ring, the "how a message reaches them" honesty, relay bridge stats, pinned/unread announcement handling. |
| **Command Deck** (`.../competition/opencode/`, `docs/ui/hub-competition-command-deck.md`) | The five-destination shell and the "judge's control bar" (here: the Control room), directory master/detail with key verdicts, enrolment as four checks, revocation page, join requests, composer validation, empty-state discipline. |
| `docs/ui/flash-hub-tab.md` (UI-058) | The function baseline for People / Departments / Announcements / Groups, and the product's honesty rules. |
| `docs/ui/hub-review-and-redesign-2026-10-20.md` | The "too roundy" critique → flatter, denser, ledger-like surfaces; the missing-features list (lease, revocation, sealing, relay visibility). |
| `docs/protocol.md`, `docs/security.md` §10, ADR-138 | The wording of every privacy sentence: what the hub can read, what a removed member keeps, what rotation changes. |

---

## 3. The seven worlds

The competition entries shipped six scenarios each (Active/Sealed, Relay Degraded, Enrolling,
Unenrolled, Revoked, Empty-Offline). This prototype keeps them as *one* immutable fixture plus
overrides (`src/data/world.ts`), and splits "offline" into the two states the product's state table
actually distinguishes:

```text
1 Active & sealed      hub current, keys published, lease healthy
2 Relay degraded       hub slow, backoff + queue, nothing lost
3 Enrolling            invite verified, keys in flight, everything else empty
4 Unenrolled           no organisation on this device
5 Removed (revoked)    CRL names this device; groups gone, paired chats intact
6 Empty / offline      enrolled, never synced
7 Offline · cached     snapshot readable, lease counting down, outbox waiting
```

Per page, that produces:

| Page | 1 | 2 | 3 | 4 | 5 | 6 | 7 |
|---|---|---|---|---|---|---|---|
| Command | full telemetry | slow chart, banners, retry | empty-ish, session progress | "not enrolled" strip | red identity, 0 throughput | dashes | cached values, queue |
| People | 13 people | 13, sync stale | empty + "loads next" | empty + "connect once" | verified rows only | empty | 13 cached |
| News | 8 announcements | read marks paused | empty | empty | 2 cached rows | empty | 8 cached |
| Trust | valid chain | valid chain | enrolment wizard | enrolment wizard | revoked node + own CRL entry | 2 chain nodes, no lease | valid chain, lease 9 d |
| Groups | 4 rosters, 2 requests | 4 rosters | empty | empty | empty (closed) | empty | 4 cached rosters |

---

## 4. What the prototype proves about the functions

Full inventory: [`design/hub-web/README.md`](../../design/hub-web/README.md) §4 (~48 functions across
five screens plus global). The headlines:

- **The Hub needs a connection page.** Nine separate facts (link state, snapshot age, lease, sealing
  pins, session table, backoff, pending requests, unread, unverified keys) are today spread across the
  Hub header, Settings and logcat. One page with one actionable list is the strongest idea any of the
  three entries had, and the product has no equivalent.
- **"How would a message reach this person" belongs on the person.** Direct, sealed through the hub,
  or readable at the hub — three sentences that decide whether the user should send something. NOVA
  had this; UI-058 does not.
- **Trust deserves one page, not a settings sub-tree.** Certificate chain, sealing key rotation,
  revocation freshness, offline lease and leaving the organisation are one story; splitting them makes
  each one look optional.
- **The lease is a first-class number.** A ring in days, in the rail, on Command and on the person is
  what tells a user whether offline colleague trust still works.
- **Honesty is copy, not a badge.** Every screen carries the limit of the design in plain words (the
  hub can read group messages and announcements while they wait; rotation changes what it can read; a
  removed member keeps what it already received).

---

## 5. Verification

| Check | Result |
|---|---|
| `npm run build` (tsc + vite) | green, 48 modules, ~368 kB JS / ~38 kB CSS |
| Console/page errors while screenshotting every page and state | none |
| Screenshots, desktop 1440×950 | Command ×3 states, light theme, People, News + reader, Trust, Trust-enrolling, Groups, Control room open |
| Screenshots, phone 414×896 | Command, person sheet |
| By-eye fixes made after the first pass | telemetry label truncation; duplicate "Connected" in the hero; department strip wrapping a lone card; stray close button in the person pane; default selection (a colleague, not yourself; the roster with pending requests); duplicate lease ring on the certificate card; light-theme faint text contrast; revoked world counters now describe the cache, not the organisation |

Not verified: real browsers other than headless Chromium, touch devices, screen readers, keyboard-only
navigation end to end, or anything on the Android/desktop app. Manual checks owed: `HUBWEB-01..06` in
`docs/testing/TEST-BACKLOG.md`.

---

## 6. Deliberately not built

- Any real data path, persistence of actions across reloads, or protocol behaviour.
- Transfers, chat, calls, radio: the prototype is the Hub tab; Transfers remains a top-bar action.
- Multi-organisation, department-head console, server admin console (parked in the roadmap).
- The product's own navigation model. The rail/bottom bar here comes from the entries; UI-058 keeps the
  app's existing rail + Hub section list.

## 7. Owner decisions this raises

1. **Does Command become a real surface?** It is the one screen with no product equivalent. Options:
   a Hub landing page, a "Connection" card inside the existing Hub header, or a debug screen only.
2. **Should Trust get one page?** Today enrolment lives in Settings → Hub and the trust facts live in
   stores and logs. Merging them is a navigation change, not a visual one.
3. **Which navigation wins for the Hub at ≥600 dp?** The entries use a five-destination rail; UI-058
   uses the app's existing rail with a Hub section list.
4. **Port the copy?** The three honesty sentences (sealing path per person, plaintext announcements,
   removed-member retention) can be lifted into UI-058 without any of the layout work.

None of these are answered here; the prototype is the argument, the owner makes the choice.
