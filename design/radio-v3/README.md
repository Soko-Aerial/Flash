# Radio gateway — redesign v3 review page

A static review page for the Radio Gateway screen (Android + Windows, Compose Multiplatform). It exists so
the layout, states and words can be looked at in a browser and measured before the same structure is built
in Compose. It is **not** part of the Gradle build and ships nothing.

- Design record: `docs/ui/radio-gateway.md`, section "Redesign v3".
- Tokens: the real Flash dark palette (`ui/theme/.../FlashColors.kt`), copied into `styles.css` `:root`
  with the Compose token name beside each value.
- Copy: the shipped sentences from `FlashRadioCopy` / `FlashRadioVoiceCopy`.

## Run

```bash
node design/radio-v3/serve.mjs 5191      # any free port
```

Then open `http://127.0.0.1:5191/index.html` and switch state with the query string:

| State | URL | What it shows |
|---|---|---|
| connected (default) | `/index.html` | station plate, talk dock, paired radios |
| off | `?state=off` | the off plate, intro, preparation steps |
| empty | `?state=empty` | gateway on, nothing paired, the pair action |
| lost | `?state=lost` | link lost, reconnect, the dock disabled |
| permission | `?state=permission` | the nearby-devices strip |
| candidate | `?state=candidate` | "paired radio found — use it?" strip |
| scan | `?state=scan` | the pair sheet (scanning) |
| settings | `?state=settings` | the radio settings sheet |

`?voice=off` renders the dock with listening off; pressing and holding the big control shows the talking state.

## Checks run

- `node <taste-skill>/scripts/lint.mjs --app design/radio-v3` → 0 FAIL, 0 WARN.
- `node <taste-skill>/scripts/inspect.mjs --app "http://127.0.0.1:<port>/index.html?state=<state>" --out .taste-skill/<name>`
  → 0 FAIL, 0 WARN at 390 / 768 / 1440 for connected, off, empty, lost, permission, candidate, scan and settings.
- The only automated detector finding is the scan dot's pulse (`pulsing-dot`); it is a live-scan indicator and is
  capped by `prefers-reduced-motion`, so it is kept.

Screenshots land in `.taste-skill/radio-v3-<state>/` (git-ignored). The `desktop-fold.jpg`,
`tablet-fold.jpg` and `mobile-fold.jpg` files are the ones to look at.
