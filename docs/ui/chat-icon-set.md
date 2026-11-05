# Flash icon set — chat redesign language (2026-11-05)

The whole icon set is redrawn in the language the owner approved for the chat (mock:
`design/chat-web/src/flash/icons.tsx`). This is the record of the set, its rules and its checks.

Evidence: [24 px comparison](chat-icon-set-24px-2026-11-05.png) (blue = anchors from the mock, orange =
redrawn) and [full sheet](chat-icon-set-sheet-2026-11-05.png) (24/48 on light and dark, 96 px detail).

---

## Rules

- 24 × 24 viewport, `android:width/height="24dp"`; content fitted to **18.0 dp on its longest axis**
  and centred (the 28 anchor glyphs' median ink is 17.10 × 17.65 dp).
- `strokeWidth="1.75"`, round caps and joins, no fills except `play` (filled triangle) and the three
  `more` dots.
- One deliberate asymmetry or squared detail per glyph so the set reads as this hand:
  cut send nose, flat mouthpiece, squared paperclip loop, flat-topped shield, flat-tipped cog teeth.
- Pairs share one construction: `thumb_down` = `thumb_up` rotated 180°; `upload` = `download` mirrored;
  `forward` = `reply` mirrored; `video_off` keeps `video_call`'s body and lens; `wifi_direct` is `wifi`
  with a two-peer base; `stop` is sized to the `play`/`pause` height.
- Names, `contentDescription`s and every `FlashIcons` entry are unchanged; `autoMirrored` is kept only
  on the six files that had it.

## What changed

- **28 glyphs copied byte-for-byte from the approved chat mock:** send, attach, microphone, camera,
  call, video_call, more, back, close, encryption, reply, heart, thumb_up, play, pause, clock, check,
  read, failed, download, search, pin, device, gallery, delete, share, group, bluetooth (plus `wifi`,
  which the mock also carries).
- **39 redrawn:** announcement, archive, bolt, call_accept, camera_flip, chat, chevron_down, connection,
  data_saver, earpiece, edit, flag, forward, hand, hangup, headphones, hub, mic_off, mirror, mute,
  nearby, react, relay, retry, screen_share, settings, sliders, speaker, stop, thread, thumb_down,
  transfer, tray, upload, verified, video_off, wifi, wifi_direct, zoom_fit.

Notes from the pass:

- `settings` no longer reads as a sun at 24 dp: open counter (r 5.6) with eight separate flat-tipped
  trapezoid teeth instead of one merged outline.
- `hangup` is the `call` handset struck through perpendicular to its axis; rotations were tried and
  read as a crescent or an arch at 24 px.
- `camera_flip` is two rotation arrows around the lens, not a smile.
- `tray` (the Windows tray and the hub mailbox glyph) was still the brand bolt; it is now an inbox tray.
  The brand bolt remains `flash_ic_bolt` only.

## Checks

- Every file: one `<vector>`, 24 × 24 viewport, stroke 1.75, round caps/joins, no groups, clips, tints,
  BOM or smart quotes.
- Ink extents measured analytically (arc + cubic extrema, cross-checked by 400-step sampling on all 156
  paths, max delta 0.02 dp); every redrawn glyph fitted to 18.0 dp longest axis.
- Chromium render of the same path data at true 24 px, 48 px and 96 px on light and dark; the 24 px
  nearest-neighbour strip is the committed evidence.
- `:app:processDebugResources` (AAPT) and `:app:compileDebugKotlin` green.

**Not verified:** Compose's own `PathParser` render and a phone at 24/48 dp. Device check `CHATUI-16`.
