# Critique — chat redesign prototype (5 November 2026)

Method: **dual-agent**. Assessment A was an independent design review (screenshots + source, no
detector). Assessment B was an independent detector and browser-evidence pass (Impeccable
`detect`, Playwright measurements, taste-skill inspector). They did not see each other's output;
the synthesis below is the parent's, and the fixes were applied afterwards.

Target: `design/chat-web` at `http://localhost:4174` (production build), 1440 / 768 / 390.

---

## Design health score

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | Presence, transport, %, speed, ETA and verification are visible; the applied wallpaper scope is not shown after the sheet closes |
| 2 | Match system / real world | 3 | "direct over LAN", "12.4 MB/s · 0:48 left" are right; a behaviour switch sat under "appearance" |
| 3 | User control and freedom | 2 | The sheet applied picks before "Done", so Done could only close; no undo |
| 4 | Consistency and standards | 3 | Tokens and icons cohere; the primary button was a 999px pill against `FlashShapes.button` 12dp |
| 5 | Error prevention | 2 | "All chats" would repaint every conversation with no confirmation or undo (and was inert) |
| 6 | Recognition rather than recall | 2 | Presets preview well; the eight colour dots had no names |
| 7 | Flexibility and efficiency | 2 | One trailing slot is efficient; in-chat search moved from the header into the menu |
| 8 | Aesthetic and minimalist design | 3 | The conversation earns 4; the sheet carried 16 simultaneous options |
| 9 | Error recovery | 2 | No failed / paused / queued transfer state exists in the mock, so the hardest surface cannot be judged |
| 10 | Help and documentation | 2 | "Hold to record" was false in the mock; the lock was title-only; SHA-256 is unexplained |
| **Total** | | **24 / 40** | **Acceptable** |

Cognitive load: failed **chunking**, **grouping**, **visual hierarchy** (in the empty composer and
the filled quote), **one thing at a time**, **minimal choices** (16 in the sheet), and **working
memory** (no "currently: Signal" summary). Single focus and progressive disclosure passed.

Personas: Alex lost one-tap search; Sam met focus rings (B measured 35 of 36 stops) but found a
fake "hold" label, 30px swatches and a dialog with no focus trap or Escape; Casey found 38px header
targets and a clipped, scroll-hidden wallpaper chip strip at 390.

---

## Priority issues and what happened to them

| Severity | Issue | Fixed in this round |
|---|---|---|
| P1 | Accent text failed contrast: white "Done" on `pulse500` 3.74:1; dark `--accent-ink` on `pulse400` ~2.0:1 | **Yes.** Every accent fill now uses `pulse600` + white (5.21:1) light and `pulse400` + `pulse900` (5.85:1) dark, as `--accent-fill` / `--on-fill` |
| P1 | The wallpaper sheet had no coherent apply model: instant preview, inert scope, Done that only closes | **Yes.** Instant apply with a visible status ("Applied · Signal"), an Undo that returns to the wallpaper the sheet opened with, and the scope removed until ADR-355 decides it |
| P2 | The custom colour row was eight unlabeled 30px dots | **Yes.** Six named 44px swatches (Stone, Sage, Sand, Clay, Mint, Sky) with `aria-label`, and the "Yours" tile now carries a plus, not a duplicate swatch |
| P2 | The reply quote rendered as a second bubble | **Yes.** No fill, no panel: a 13dp reply glyph, the accent name, one ellipsised line |
| P2 | The redesign leaked past presentation: search left the header, and behaviour/accessibility switches appeared under "Chat appearance" | **Partly.** The fake "Enter sends" and "Reduce motion" rows are gone; Theme is now a real control. Search stays in the menu (recorded as an owner decision) |
| P3 | Header actions 38px; 34dp avatar; 30px swatches | **Yes.** 44px header targets, 44px swatches |

Other fixes from the evidence pass:

- 390px composer could not stack: a later two-column rule overrode the media query; the pill
  overflowed its row by 141px. Fixed and re-measured (`scrollWidth == clientWidth` at 1440/768/390).
- Four text pairs failed AA (`#1A9B52` 3.59:1, `#6E778A` on the file card 4.06:1, two at 4.498:1).
  Now 5.94–6.76:1; every sampled pair in the app is ≥5.21:1.
- The quote's 3px left accent bar was a detector `side-tab` finding; removed.
- The file block was a card inside a card (detector `nested-cards`); it is now content on the
  bubble, not a nested panel.
- The sheet had three exits with no Escape and no focus trap; it now focuses its close button,
  traps Tab, closes on Escape, and labels itself with `aria-labelledby`.
- "Unread" text sat on the wallpaper, against the design's own rule; it is now a surface pill.
- Paragraph measure was ~93 characters; capped at 68–72ch.
- The desktop chat-list search was a non-focusable `div` with a "Ctrl K" hint; it is now a real
  input that filters the list, with an empty state.
- The mobile wallpaper chips clipped mid-chip inside a hidden scrollbar; they wrap at ≤900px.
- Focus rings: the app surfaces now own their ring (`--focus-ring` = `accent-strong`), including
  the scrollable thread, which previously received the browser default.

Accepted and defended (not changed):

- **Typing dots** (`pulsing-dot`): the indicator is a real state, drawn as the app draws it.
- **Nested quote block**: the quote keeps a reply glyph and name; it no longer carries a panel,
  so the remaining nesting is semantic (a reference inside a message), not decoration.
- **Five corner radii**: two are the phone frame's bezel and screen (44/56dp); the UI itself uses
  16 (cards), 20 (bubbles/sheets) and 12 (controls).
- **Blocks below the fold**: this page is a review document, not a landing page; the first screen
  carries its title, controls and the phones.
- **No data hue**: the first screen is chat chrome, which is neutral by design; colour sits on the
  send key, the verified line and the wallpapers.

---

## Evidence (Assessment B)

- Detector: static scan of `design/chat-web/src` — **1 finding** (`side-tab`), now **0 / exit 0**.
  Live DOM scan before fixes: 29 findings (12 nested cards, 8 long measures, 3 low contrast,
  2 stragglers, 2 tiny text, 1 side-tab, 1 pulsing dot).
- Overflow: 0 at 1440 / 768 / 390 before and after; one child over its parent at 390 (the composer),
  fixed.
- Contrast: 42 failing text elements at 1440 (4 distinct pairs) before; 0 after (sampled pairs
  5.21–17.39).
- Accessible names on icon-only controls: 0 missing at every width.
- Keyboard: 36 stops on `?view=phone`, 13 inside the phone; the input and the send/mic are reachable
  in visual order; the ring is `--focus-ring` on 35 of 36 stops (the thread was the exception and is
  fixed).
- Reduced motion: `data-motion="reduced"` sets 1ms durations and one iteration on the typing dots,
  the recording dot and the motion demos.
- taste-skill inspector: **0 FAIL, 3 WARN** at 1440/768/390 (radii, below-fold, no-data-hue).

---

## Left open

1. **Search moved into the menu** (the header keeps call / video / more). One tap becomes two.
   Keep or revert — the owner's call, because it trades header room for discoverability.
2. **Wallpaper scope** (this chat vs all chats) and its persistence: ADR-355 decision 4.
3. **Reactions, failed/paused transfers and unread counts** are not in the mock; a second review
   state would be needed to judge recovery.
4. **Long contact names** ellipsize; long file names still truncate at the tail in the narrow
   bubble (middle truncation would need a string measured in the layout).

---

## Round 2 — the menu, profile, group and swarm (same day, after the owner's review)

The owner approved the direct chat, the desktop and the icons, and asked for the three-dot menu's
options, group chats and their settings, the profile view and the swarm UI. Those surfaces were
added and reviewed against the same evidence tools.

| Severity | Issue | Fixed |
|---|---|---|
| P1 | Destructive text was 4.38:1 on white (`--danger` is `error500`) | **Yes.** Text uses `--danger-ink` (#B32626, 6.25:1); the error colour stays for borders and icons |
| P1 | Join requests truncated the applicant's name and the subline ("Tessa A…", "Wants to join with an in…") | **Yes.** The actions moved to their own row under the name |
| P2 | Group runs merged two different senders, so the second sender's name disappeared | **Yes.** Runs split on sender as well as direction |
| P2 | The piece map rendered as wide bars in a chat bubble and as a flat strip on the board | **Yes.** A 12-column, two-row matrix with square cells; the legend keeps 9px swatches |
| P2 | The group collage repeated initials (A/K/K/K) and every member avatar had one tint | **Yes.** Collage uses A/K/L/N and members carry the four avatar tones |
| P2 | Rule subtitles contradicted their switch (they described one state only) | **Yes.** State-dependent pairs, using the app's own sentences |
| P3 | The invite sheet opened with a focus ring on its close button | **Yes.** Dialogs focus their container; Tab still rings the controls |
| P3 | "Add members" reused the Group info icon | **Yes.** A plus glyph |
| P3 | The swarm board sat beside the phones and ended 327px short | **Yes.** It spans the canvas under the phones in three columns |

Evidence after the round: `detect` 0 findings; `lint --app` 0 FAIL; `inspect --app` **0 FAIL /
2 WARN** (six radii, three of 45 blocks above the fold) at 1440/768/390; no horizontal overflow;
every sampled text pair >=5.21:1 in both themes.

Not changed, and why:

- The menu keeps the shipped item list (plus the proposed Chat appearance); "Mark as unread" still
  uses the read glyph because that is the app's mapping.
- Non-navigating menu actions acknowledge with a prototype snackbar; real confirmations stay the
  app's existing dialogs.
- The swarm map keeps three data hues (green / cyan / amber) because they encode state, which the
  app already does.
