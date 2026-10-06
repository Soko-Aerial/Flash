# Component Research & Design Document — Launch splash ("Ink")

**Status:** IMPLEMENTED (2026-10-06; unit-tested, desktop frames checked off-screen, not device-verified)

**Component ID:** UI-056
**Last updated:** 2026-10-06
**Owner phase:** owner request 2026-10-06 (outside the UI-001…UI-045 sequence; replaces the UI-001 brand-loop splash)
**Decision:** ADR-080

---

## Component

`FlashInkSplash` (the drawing), `FlashLaunchSplashOverlay` + `FlashLaunchSplashGate` (when it plays), all in
`:ui:theme`. `FlashInkSplashGlyphs` holds the letter data.

## Purpose

The first thing a user sees on a cold start of Flash, on Android and on the Windows desktop app. The pen writes
the word "Flash" by hand, then draws the bolt above it, fills it and sends out one ripple. It covers engine
boot, so it is also the screen the user looks at while transports start.

## Research sources

- The owner's two rounds of feedback on 2026-10-06 (below).
- `hersheytext` 2.0.0 npm package (MIT; github.com/techninja/hersheytextjs): single-line SVG stroke fonts, including
  the Hershey fonts and the EMS fonts by Windell H. Oskay (evilmadscientist.com).
- Android Developers, "Splash screens" (developer.android.com/develop/ui/views/launch/splash-screen) and the
  `core-splashscreen` API: `setKeepOnScreenCondition`, `setOnExitAnimationListener` (with a listener set the app must
  call `SplashScreenViewProvider.remove()`), and that the system splash is drawn from the theme before app code runs.
- The previous splash: `FlashBrandAnimation` (UI-001 brand loop) behind `FlashSplashScreen`, and ERROR-034 (boot outlasts
  the splash on the Belfone SCP810, hence the 6 s ceiling) and EXP-013 (read animation state in the draw phase only).

## Existing approaches studied

1. **Round 1 (rejected by the owner, "not impressed at all"):** six geometric demos: bolt + discovery rings (the
   then-current splash), two phones handshaking, a stroke-drawn bolt, a stream of chunks, a radar sweep, a quiet static
   mark. Abstract motion with no name.
2. **Round 2, five handwritten options:** Ink, Spark, Neon, Marker and Signature. Each one writes the name in a
   single-line font, with the pen following the glyph centre line and a "line boil" wobble. Fonts used: EMS Allure,
   Hershey Script 1-stroke, EMS Readability Italic and EMS Felix.
3. **Outline fonts (rejected):** a normal script font is filled outlines. "Writing" it needs a stroke-reveal mask or a
   hand-traced centre line per letter. Single-line fonts already are the centre line.
4. **Lottie / AVD / video (rejected):** adds a dependency or a per-platform asset. It cannot follow the dynamic accent
   or both themes from one source, and it would not run on desktop Compose.

## What worked

- The owner chose **Ink's composition, written in Spark's font (Hershey Script 1-stroke)**.
- A visible brand name and a pen that visibly writes it.
- A wobble subtle enough to read as ink rather than as an error.

## What did not work

- Abstract geometry (round 1).
- A splash that leaves the moment the engine is ready: a writing animation needs about 2 s, so on a fast phone the old
  rule would cut it off mid-word. Owner rule: it plays to the end.

## Chosen approach

- **Drawing:** a Compose `Spacer` with `drawWithCache`.
  - Geometry, paths and brushes are built once per size.
  - The clock is a `withFrameNanos` loop whose `State` is read only in `onDrawBehind` (EXP-013), so the splash never
    recomposes while it animates.
- **Letters:** the 9 strokes (104 points) of "Flash" from Hershey Script 1-stroke.
  - They are compiled in as `IntArray`s, in a 2173 × 662 box with y pointing down.
  - Each polyline is smoothed with Catmull-Rom (control points at 1/6 of the tangent), 10 samples per segment.
- **Writing:** strokes are drawn one after another at one pen speed, with a 0.08 s pen lift between them
  (`InkWriterSchedule`).
  - Easing: `ease(k) = 0.45k + 0.55(0.5 − 0.5cos πk)`.
  - A nib (a dot with a soft glow) rides the pen head.
- **Wobble:** 2-octave value noise displaces every point by at most 1.1 scene units.
  - There are 4 precomputed patterns, switched 8 times a second (the SVG demo's turbulence seeds).
  - Distance along a stroke is measured on the clean line, so the wobble never changes the pen's pace.
- **Gate (`FlashLaunchSplashGate`):** phases Pending → Playing / Skipped → Finished.
  - The first decision wins.
  - One gate per process on Android and per `application {}` on desktop, so recreation, a warm return or a tray reopen
    never replays it.

## Why it was chosen

- The owner picked it.
- It is technically cheap: no dependency and no asset, and the same code runs on both hosts. It follows light/dark and
  the dynamic accent from `FlashColors`.

## Visual specification

The scene unit is `u = min(w / 200, h / 300)`; everything below scales with it.

| Element | Spec |
|---|---|
| Ground | Radial gradient centred at (w/2, 0.4h), radius 0.7·max(w, h). Light #FFFFFF → #E6EAF0, dark #151A26 → #07080A. Never takes the accent. |
| Word | 160u wide, centred at (cx, cy + 18u), stroke 3.2u, round caps. Horizontal gradient a1 → a2 → a3 → a4. |
| Bolt | `BOLT_POINTS` (the logo bolt), 40u wide, centred at (cx, cy − 58u). Outline 1.4u, then fill a1 → a2 → a3. |
| Halo | Radial a2 glow behind the bolt, r 62u. |
| Ripple | One ring from the bolt, stroke 1.2u, fading out. |
| Nib | Core r 2u in a4 with a glow r 9u. |
| Breathing | After the fill the bolt scales 1 + 0.035·f·sin(2.4(t − 2.9)). |

**Palette:**
- Brand light: a1 #1FB8A6, a2 #0D9488, a3 #0A7A70, a4 #E8950A.
- Brand dark: a1 #4FD1C2, a2 #1FB8A6, a3 #0D9488, a4 #E8950A.
- Dynamic accent: a1 = lerp(accent, white, 0.25), a2 = accent, a3 = lerp(accent, black, 0.2), a4 = accent.
- Dark is detected as `backgroundApp.luminance() < 0.5`.

**System splash (Android):** `flash_splash_bg` is #FFFFFF by default and #171A22 in `values-night`, with the existing
bolt icon. It follows the **system** dark mode, not Flash's own theme setting: Android draws it from resources before any
app code can read the setting (known limitation).

## Interaction specification

- **No skip:** taps are swallowed while the splash is up, so they cannot reach the app underneath (owner: "play to end").
- **Skipped entirely:**
  - when the launch answers a call (`FlashCallActionReceiver.EXTRA_ANSWER_CALL`) or is a PTT press
    (`PttSessionEngine.EXTRA_PTT_PRESS`);
  - when the setting is off;
  - on Android, when reading the setting takes longer than 1 s.
- **Setting:** Settings → Appearance → "Launch animation", on by default.
  - Subtitle on: "Writes the Flash name when the app starts, not when you switch back to it".
  - Subtitle off: "Flash opens straight away".
  - A change applies from the next cold start.

## Animation specification

Seconds from the splash's first frame (`FlashInkSplashTimeline`):

| Phase | Time |
|---|---|
| Writing | 0.3 → 2.2 |
| Bolt outline | 2.4 → 2.9 |
| Bolt fill | 2.75 → 3.15 |
| Ripple | 2.9 → 3.8 |
| Intro end (`INTRO_END`) | 3.45 |
| Fade out | 250 ms |

- **Finish rule:** `finishAt = engineSettled ? introEnd : max(introEnd, 6000 ms)`.
  - It never ends before the intro.
  - It never stays past 6 s unless the intro itself is still playing (ERROR-034).
- **Android handoff:**
  - While the gate is Pending, the system splash stays.
  - When Playing, the system splash fades out over 200 ms into the identical Ink ground.
  - When Skipped, the system splash stays until the engine is ready (the old behaviour).
- An Activity recreated mid-splash resumes at the right moment. A host that returns after the finish time shows nothing.

## Gesture specification

None. All pointer input is consumed while the splash is visible.

## Accessibility requirements

- **Reduced motion** (`FlashTheme.motion.reduceMotion`, including the LOW tier): the finished frame is shown still for
  0.8 s. No wobble, no writing.
- The splash carries no information a screen reader needs. It is decorative and short, and the setting turns it off.

## Responsive behavior

`u` fits the scene to the shorter side. It was checked off-screen at 360 × 720 (phone) and 1000 × 640 (desktop window),
light and dark, at 8 moments. Unit tests check the fit at five screen shapes, including landscape and a desktop window.

## Dark-mode behavior

A deliberate dark ground: a deep navy centre falling to near black, not an inversion. The ink is lighter (a1 #4FD1C2) so
it keeps contrast. The amber a4 accent is shared.

## Performance considerations

- No recomposition while animating.
- Geometry is cached per size. Wobble patterns are computed once (4 × 9 strokes).
- The per-frame work is path building over at most about 1,000 points.
- It runs while the engine boots, so it must stay cheap (ERROR-034). Not yet profiled on a device (`SPLASH-06`).

## Implementation notes

| File | Role |
|---|---|
| `ui/theme/.../FlashInkSplash.kt` | Drawing, scene layout, palette, smoothing, wobble noise |
| `ui/theme/.../FlashLaunchSplash.kt` | Timeline, writer schedule, gate, overlay |
| `ui/theme/.../FlashInkSplashGlyphs.kt` | Letter data and the font licence acknowledgement |
| `app/.../ui/splash/FlashLaunchSplashProcess.kt` | The process-wide gate, `isTimeCriticalLaunch` |
| `app/.../MainActivity.kt` | System splash keep-on-screen / exit listener, setting read, overlay |
| `desktop/.../DesktopMain.kt` | Application-scoped gate, overlay above `DesktopShell` |
| `FlashSettingsDataStore.launchAnimation`, `DesktopSettings.launchAnimation` | The setting (key `launch_animation`) |
| `app/src/main/res/values{,-night}/colors.xml` | System splash ground |

- The old `FlashSplashScreen.kt` was deleted. `FlashBrandAnimation` stays for the transfers loading state.
- **Letter data generator:** `node tools/splash/hershey-strokes.js <path>/svg_fonts/HersheyScript1.svg Flash` (font from
  `npm pack hersheytext@2.0.0`).
  - It reads the SVG font's glyph paths, flips y and lays the letters out by advance width.
  - It shifts the word to (0, 0) and prints the `intArrayOf(...)` literals.
  - Stroke order is the font's own, which is already the writing order.
- **Licence:** Hershey font use is free provided the acknowledgement (Dr. A. V. Hershey, U.S. National Bureau of
  Standards; data format by James Hurt, Cognition, Inc.) ships with the data. Conversion to the NTIS "xxx yyy:" format is
  forbidden.
  - The acknowledgement is in the glyph file's KDoc and in `NOTICE`.
  - It is also in the shipped third-party notices via `config/aboutlibraries/libraries/hersheytext.json` and the
    `hershey-fonts` / `hersheytext-mit` licence entries (checked in the generated notices on both hosts).
- No new dependency.

## Testing checklist

- [x] `FlashLaunchSplashTest`, 21 tests:
  - timeline and easing
  - writer schedule, including the real word
  - gate phases, and a clock that goes back
  - polyline head and wobbled coordinates
  - smoothing through the original points
  - wobble bounds, determinism and smoothness, plus the noise range
  - layout and fit at 5 shapes
  - closed bolt outline
  - brand and dynamic palettes
  - glyph box, and no jumps inside a stroke
- [x] `FlashSettingsLogicTest`: the default is on, and the copy says when it plays.
- [x] Compiles: `:ui:theme` (JVM and Android host tests), `:ui:chat:jvmTest`, `:app:compileDebugKotlin`,
  `:desktop:compileKotlinJvm`.
- [x] Off-screen render on desktop JVM (`ImageComposeScene`, temporary test, not committed), light and dark, phone and
  desktop shapes.
- [ ] Device checks `SPLASH-01`…`SPLASH-08` (`docs/testing/TEST-BACKLOG.md` §4za).
