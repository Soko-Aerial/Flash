# Verified Audit — Ground Truth

Produced by direct source inspection on 2026-08-28 against branch `dev` at `5ffd7b2`.
**This file supersedes every factual claim in the old single-file plan.**

Nothing here was compiled or run. Claims are "what the source says", not
"what resolves at build time". Items marked ⚠️ are unverified assumptions that a
phase must confirm.

## Build facts

| Fact | Value |
|---|---|
| AGP | 9.3.1 |
| Kotlin | 2.2.10 |
| Gradle | 9.5.0 |
| KSP | 2.3.11 |
| Compose BOM | 2025.12.00 |
| Compose compiler plugin | `org.jetbrains.kotlin.plugin.compose`, version = Kotlin |
| compileSdk | 37 (`app`), 35 (`core/*`) |
| minSdk / targetSdk | 24 / 36 |
| Java bytecode target | 11 |
| Configuration cache | **enabled** in `gradle.properties` |
| KMP plugin | **not present** |
| `org.jetbrains.compose` plugin | **not present** |
| `expect`/`actual` declarations | **zero, anywhere** |
| `commonMain` source sets | **zero** — everything is `src/main/java/` |

All 11 modules use `com.android.library` or `com.android.application`.
Root `build.gradle.kts` notes this project uses **AGP 9.3.1 built-in Kotlin**, with no
classic `kotlin.android` plugin. `explicitApi()` strict is on in every `core/*` module.

## CORRECTION 1 — the build change is the hardest part, not an afterthought

The old plan §18 said the migration "may require adjusting Gradle configuration".
This understates it severely.

**Under AGP 9.0+, `com.android.library` and `com.android.application` are
incompatible with `org.jetbrains.kotlin.multiplatform` in the same module.** The
replacement is a different plugin, `com.android.kotlin.multiplatform.library`, which:

- replaces the top-level `android { }` block with `kotlin { androidLibrary { } }`
- **does not support build variants at all**
- therefore breaks the `buildTypes { release { ... } }` block in all 8 `core/*` modules
- therefore breaks `android { publishing { singleVariant("release") } }` in all 8
- requires `debugImplementation(...)` → `androidRuntimeClasspath(...)`

Source: [Updating multiplatform projects with Android apps to use AGP 9](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)

This is why Phase 06 pilots the conversion on **one module only**.

## CORRECTION 2 — the core is already mostly portable

215 `.kt` files in `core/`. Only 54 reference `android.*`/`androidx.*` at all.

| Module | files | Android-coupled | notes |
|---|---|---|---|
| messaging | 8 | **0** | fully clean |
| common | 22 | 1 | `FlashLogger.kt` only |
| engine | 6 | 2 | |
| transfer | 38 | 3 | 2 are dead code — see CORRECTION 3 |
| discovery | 23 | 4 | all confined to `nsd/` |
| security | 30 | 4 | |
| network | 54 | 12 | 6 are `android.util.Log` only |
| persistence | 34 | **28** | Room + DataStore |

Old-plan Steps 3–5 ("extract models", "extract protocol", "extract transfer engine")
are therefore **not extraction work** — those files already have zero Android imports.
They are source-set relocation plus a build-file change.

## CORRECTION 3 — `core/transfer/wslegacy/` is dead code

`wslegacy/` contains `WsTransferManager.kt`, `WsDiscovery.kt`, `WsPairingStore.kt`,
`LegacyDiscoveredDevice.kt`. Grep for these symbols across the whole repo returns
**only files inside that package plus its own test**. Nothing else references it.

Two of the three Android-coupled files in `core/transfer` are here. Deleting the
package removes them. Handled in Phase 02.

## CORRECTION 4 — `android.util.Log` is ~15% of all Android coupling

Eight files are Android-coupled *only* because of logging:
`core/network/{datachannel/DataChannelServer, datachannel/DataChannelClient,
ws/WsTransferServer, ws/WsConnection, ws/WsSession, tcp/LanSession}.kt`,
plus `core/common/logging/FlashLogger.kt` and
`core/transfer/RealFlashTransferRepository.kt`.

`FlashLogger.kt` already contains a JVM fallback seam (see its own comments at
lines 13, 29, 112). Routing the other seven through it clears them mechanically.
Handled in Phase 03.

⚠️ `RealFlashTransferRepository.kt` uses **fully-qualified** `android.util.Log` at
lines 121, 133, 349, 496 with **no import statement**. An import-only grep misses
it. Assume more of this pattern exists; grep call sites, not imports.

## CORRECTION 5 — Material 3 IS used pervasively, and it does not matter much

Old plan §14 claimed the UI "uses custom Compose components rather than generic
Material 3". **False.** 62 `material3` references across 28 files in `ui/`, plus 10
in `app/`: `Text` ×17, `ModalBottomSheet` ×6, `IconButton` ×5, `HorizontalDivider`
×5, `Scaffold` ×3, `SwipeToDismissBox`, both progress indicators, `MaterialTheme`,
`Typography`.

Old plan §23's "do not introduce Material 3" instruction is therefore incoherent and
is **struck**. M3 is already load-bearing.

Cost impact is small: nearly all of these exist in
`org.jetbrains.compose.material3`, so they are **import rewrites**. Genuine
exceptions, needing real work:
- `dynamicDarkColorScheme` / `dynamicLightColorScheme` (Android 12+ Monet, no common
  equivalent) in `ui/theme/Theme.kt` and `ui/theme/FlashTheme.kt`
- `WindowInsets.navigationBars` on 7 bottom-sheet `contentWindowInsets` call sites

## CORRECTION 6 — the 51 icon drawables need NO conversion

The UI audit claimed Android vector XML "is not consumable in CMP commonMain" and
that all 51 must be rewritten as `ImageVector`. **That is wrong.**

Compose Multiplatform's resource system supports Android XML vector drawables in
`composeResources/drawable/`, loaded as `VectorPainter`. The only caveat is that
they must not contain external references to Android resources.

I grepped all 51 files in `ui/theme/src/main/res/drawable/` for `@color/`, `@dimen/`,
`?attr/`, `@android:`, `@drawable/`, `aapt:attr`, and `android:tint`.
**Zero matches.** They are all self-contained.

So Phase 17 is a directory move plus one signature change in `FlashIcons.kt` —
not 51 rewrites. Source: [Using multiplatform resources](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-usage.html)

## CORRECTION 7 — `BackHandler` is already multiplatform

`androidx.activity.compose.BackHandler` is imported in 4 files
(`FlashConversationScreen`, `FlashMediaViewer`, `FlashMessageContextMenu`,
`FlashPairingFlow`). Compose Multiplatform has shipped common `BackHandler` and
`PredictiveBackHandler` since **1.8**. These are import swaps, not shims.

## CORRECTION 8 — several old-plan §16 "blockers" are false positives

`LocalDensity` (6 files), `LocalViewConfiguration` (2), and `LocalHapticFeedback` (2)
all exist in CMP `commonMain`. No change needed.

Confirmed absent from `ui/` entirely: `AndroidView`, `hiltViewModel`, `viewModel()`,
`stringResource`, `R.string`, `androidx.navigation`, `MediaStore`, `DocumentFile`,
`systemBarsPadding`, `LocalConfiguration`, `LocalSoftwareKeyboardController`.

## CORRECTION 9 — navigation and adaptive layout are already done

- **Navigation**: hand-rolled in `ui/chat/.../navigation/FlashNavigation.kt` on
  `AnimatedContent` + `rememberSaveable`, with a pure-function `FlashNavigationMath`
  object. No navigation library anywhere in the repo. Only non-common dependency was
  `BackHandler`, which per CORRECTION 7 is free.
- **Adaptive layout**: `FlashAdaptiveLayouts.kt` already does exactly what old plan
  §15 prescribes — `BoxWithConstraints` + own 600/840dp breakpoints in
  `FlashAdaptiveMath`. No `LocalConfiguration`, no `WindowSizeClass`. Already
  common-safe.

Old plan Step 11 ("make the UI adaptive") is therefore mostly already satisfied.

## CORRECTION 10 — zero string resources in the UI

No `strings.xml`, no `stringResource`, no `R.string`, no fonts, no density buckets
anywhere in `ui/`. All labels are hardcoded Kotlin strings. This removes what is
normally the largest UI migration category. (It is also a latent localization
problem, but out of scope.)

## The real work: JVM-only, not Android-only

None of the following are Android problems. They are `commonMain` problems, and they
are what decision **D1** is about.

| Area | Files | Blocking API |
|---|---|---|
| TLS / TOFU pinning | 5 | `javax.net.ssl.*`, `java.security.cert.*` |
| Blocking sockets | 10 | `java.net.Socket`, `ServerSocket` |
| Crypto | ~9 | full JCA: `java.security.*`, `javax.crypto.*` |
| Concurrency | ~8 | `ConcurrentHashMap`, `ReentrantLock` |
| Cheap stdlib | many | `UUID`, `Locale`, `Date`, `SimpleDateFormat`, `BitSet` |

Under a true stdlib-only `commonMain`, all of it is rewritten. Under
`jvmAndAndroidMain`, **none of it is** — both targets are JVM.

## UI file inventory

| Module | main | test | note |
|---|---|---|---|
| `ui:chat` | 46 | 31 | worst file: `FlashConversationScreen.kt` |
| `ui:theme` | 18 | 5 | chokepoints: `FlashIcons.kt`, `Theme.kt`, `FlashTheme.kt` |
| `app` | 18 | 6+1 | `MainActivity.kt`, splash |

36 `*LogicTest.kt` files test Compose-free pure helper objects and are already
platform-agnostic — they port as-is.

`FlashConversationScreen.kt` concentrates most UI coupling: `Toast` ×13,
`rememberLauncherForActivityResult` ×2 (`OpenDocument`, `RequestPermission`),
`ContextCompat`, `Intent.FLAG_GRANT_READ_URI_PERMISSION`, `contentResolver` ×2.
`LocalContext` appears at 8 sites repo-wide across 6 files.

⚠️ **Scope gap**: old plan §14/§15 describe sharing "device cards / transfer cards"
and mock up a desktop devices+transfers layout. The shipped UI is a **chat app**
(`ui:chat` + `ui:theme`). Those desktop screens may not exist in shareable form.
Phase 22 treats this as **new UI work**, not sharing. Confirm with the human.
