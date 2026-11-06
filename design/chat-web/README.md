# Flash chat — redesign prototype

A web prototype of the **conversation screen, composer, icon set and chat appearance**, for both
hosts, on the shipped Flash tokens. Built for the owner to look at before anything in `ui/chat`
changes. Design record: `DESIGN.md`.

Standalone Vite + React app: no Gradle wiring, no network, no app code changed.

---

## Run it

```bash
cd design/chat-web
npm install            # once
npm run dev            # http://localhost:5174
```

```bash
npm run build          # type-check + production bundle into dist/
npm run preview        # serve that bundle on :4174
node scripts/shots.mjs # screenshots into shots/ (dev server or preview must be running)
```

Controls at the top of the page are **review chrome**, not part of the app design: theme, wallpaper,
which frames are visible, and the motion switch. URL params pin a state for review links:

```
?theme=dark&wallpaper=pulse&view=phone&sheet=1&motion=reduced
```

`view` is `all` | `phone` | `desk`.

---

## What to look at

1. **The conversation** (phone). Type in the composer: the mic becomes the send key in place. The
   kebab opens the menu. The tail on the last bubble of a run is the shipped scoop.
2. **The overflow menu and profile.** The menu is the shipped item list, with the destructive items
   below a rule; View profile opens the peer details sheet.
3. **Chat appearance.** Reachable from the menu: wallpaper (opens the sheet), a working light/dark
   control, a live preview, reset.
4. **The group.** A group conversation with the swarm map on the file, the 2×2 collage, sender names
   once per run; group info with roles, admin actions and join requests; group settings with signed
   rules above the device switches.
5. **Invites and the swarm.** The invite sheet, a sent group file ("Delivered to 4 of 6 · You can go
   offline now"), and the board with the piece map, the holders and the four engine sentences.
6. **The message parts.** Photo, transfer in flight, verified file, voice note and reply at real
   size, plus the day chip and unread rule.
7. **The composer** — attach, pill, one trailing control; the measurements beside it, and the
   recording row underneath.
8. **Wallpaper** — five presets and a colour of your own; picks apply at once, with Undo.
9. **The icon set** — twenty-two redrawn glyphs with the detail that distinguishes each.
10. **Motion** — press Replay, then turn Motion off and press it again.
11. **Desktop** — the same conversation in the 68dp rail / 320dp list shell; the list search filters.

At 390px wide the phone frame disappears and the chat renders at full width, which is how it should
be judged on a handset.

---

## Layout

```
src/
  App.tsx                  the review page and its controls
  styles/tokens.css        tokens copied from ui/theme + the wallpaper fields
  styles/app.css           the chat surfaces (header, thread, bubbles, composer, sheets)
  styles/review.css        the review page, the phone/desktop frames, responsive rules
  flash/icons.tsx          Flash Line v2 paths, one component, name union
  flash/data.ts            the conversation, chat list, wallpaper names, colours
  flash/Conversation.tsx   header, thread, bubble renderer, run grouping
  flash/Composer.tsx       the three-part composer with the single trailing slot
  flash/Wallpaper.tsx      the wallpaper sheet and the appearance page
  flash/Shells.tsx         phone frame (status bar, gesture bar) and the desktop window
  assets/east-arcade.jpg   the credited photograph in the thread
scripts/
  shots.mjs                screenshots per state into shots/ (git-ignored)
  repair-encoding.py       repairs a PowerShell text round trip; safe to re-run
```

---

## Verified

- Production build (`npm run build`) and preview.
- `inspect.mjs --app` on the production URL: **0 FAIL**, 3 WARN (device radii, blocks below the
  fold on a review document, no data hue) at 1440 / 768 / 390.
- Impeccable `detect`: **0 findings**.
- `lint.mjs --app`: 0 FAIL.
- No horizontal overflow at any width (`scrollWidth === clientWidth`).
- Every sampled text pair ≥5.21:1; see `DESIGN.md` §6.
- Keyboard focus ring on every app control, 36 stops; every icon control named.
- Reduced motion collapses every animation to 1ms.

The critique that produced these fixes is recorded in `CRITIQUE.md`.

The scoop tail is approximated here with a clipped corner; the Compose port keeps the real
`FlashBubbleShape` cubic and stroke, so the shipped bubble is crisper than this prototype.

---

## Credit

Photograph: [“Sivas Buruciye Medrese 115”](https://commons.wikimedia.org/w/index.php?curid=77688219)
by Dosseman, CC BY-SA 4.0, via Wikimedia Commons. All conversation content is sample data.
