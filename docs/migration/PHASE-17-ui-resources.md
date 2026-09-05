# Phase 17 — UI resources (`ui:theme` icon drawables → CMP `composeResources`)

> ## STATUS: DONE — 2026-09-05, commits `a8d9d0d` + `23267ed`
>
> **Read this box before following anything below it.** Six statements in this file are wrong,
> and one of them makes step 4 impossible to execute as written. Full account in
> `logs/migration.md` → "Phase 17 — `:ui:theme` Compose Multiplatform resources".
>
> 1. **Precondition 1 is false, and step 4a is impossible.** `ui:theme` was **not** a KMP module
>    — Phase 06 converted `:core:common`, not this. Step 4a's `com.android.library` +
>    `org.jetbrains.compose` combination **cannot work at all**: under AGP 9's built-in Kotlin an
>    Android-only module exposes no Kotlin Gradle extension (the ADR-023/BCV fact), and CMP's
>    resource generation hooks `KotlinProjectExtension`, so `composeResources/` is never read.
>    Meanwhile PHASE-18 — the phase that converts the module — declares itself *"Blocked by:
>    Phase 17"*. **Circular deadlock.** Phase 17 broke it by taking the full KMP shell
>    (`android { }` + `jvm()`) now while leaving **every Kotlin file at its pre-KMP path** behind
>    two `srcDir` shims, so PHASE-18's move table still applies verbatim. **PHASE-18 must delete
>    those two shims as part of its move.**
> 2. **The inventory is stale.** 55 drawables, not 51 — it omits the four UI-050 calling glyphs
>    `flash_ic_call_accept`, `flash_ic_camera_flip`, `flash_ic_hangup`, `flash_ic_speaker`.
>    **54** `Res.drawable` references, not 50. **53** unique names. And **two** dead-on-disk
>    files, not one: `flash_ic_arrow_left` **and** `flash_ic_delivered` (unreferenced because
>    `FlashIcons.Delivered` deliberately points at `flash_ic_read`). Neither was deleted. Every
>    "51 / 50" in the gate table below is off by four.
> 3. **Step 3d's import is insufficient.** Its own ⚠️ said to verify against the generated
>    sources; verified, and `import …generated.resources.Res` alone leaves **54 unresolved
>    references**. CMP emits each drawable as an **extension property** on `Res.drawable`
>    (`internal val Res.drawable.flash_ic_send: DrawableResource by lazy { … }`), so the generated
>    package must be star-imported.
> 4. **Two things the phase never mentions are mandatory.** `compose.resources {
>    packageOfResClass = "com.transfer.flash.ui.theme.generated.resources" }` — CMP otherwise
>    defaults to `com.transfer.flash.theme.…`, without the `ui`, and step 3d's import is then
>    wrong. And `implementation(compose.runtime)` in `commonMain` — `compose.components.resources`
>    does not put the Compose runtime on the compile classpath, and the Compose compiler plugin
>    fails **every** compilation in the module without it, even one with zero `@Composable`.
> 5. **`compileKotlinDesktop` (step 5) does not exist.** R5 mandates plain `jvm()`, so the task is
>    **`compileKotlinJvm`**. The `jvm()` target itself had to be declared in this phase rather
>    than 18, because step 5 calls a desktop compile the critical gate and a gate against a
>    nonexistent target is not a gate.
> 6. **CMP is pinned to 1.9.3 and cannot go higher.** CMP 1.11+/1.12 declare `kotlin-stdlib`
>    2.3.20 and a Kotlin 2.2.10 compiler cannot read 2.3 metadata. 1.9.3 maps to Jetpack Compose
>    1.9.4, below the 1.10.0 that `composeBom 2025.12.00` pins, so no Android consumer sees a
>    version change. **A Kotlin bump is the only route to current CMP** — a human decision.
>
> **ABI break to carry into Phase 24:** `FlashIconSpec.drawableRes` `Int` → `DrawableResource` on
> the published `ui-theme` coordinate. No BCV `.api` file exists to record it (ADR-023 removed
> BCV). The publications were also renamed `theme*` → `ui-theme*`.
>
> **Not verified (R9):** icon *rendering*. No device or emulator run happened. Everything short of
> the pixel is verified — the 55 files ship in the APK at exactly the prefix the generated `Res`
> computes, CMP's `DefaultAndroidResourceReader` third fallback branch
> (`ClassLoader.getResourceAsStream`) is what reaches them, and `XmlVectorParserKt`'s recognised
> attribute set covers all 11 attributes the corpus uses. **The first device run of any branch
> containing `23267ed` should eyeball the chat chrome icons.**

**Blocked by:** Phase 06 (KMP pilot) — the `ui:theme` module must be a KMP module with a `commonMain` source set before its resources can be shared across platforms. *(FALSE — see box item 1. It was not, and this phase made it one.)*

**Decisions touched:** D3 (Compose dependency source). This phase requires the `org.jetbrains.compose` plugin and `compose.components.resources`, which are D3's domain. Per DECISIONS.md, D3 is in the "agent may proceed with the recommendation" class. **This phase is written assuming D3 Option A** (CMP `org.jetbrains.compose` plugin, `compose.components.resources`). If D3 is resolved differently, the dependency table below changes but the resource-move and signature-change remain the same.

**Risk: Very low.** AUDIT CORRECTION 6 confirms the 51 `flash_ic_*.xml` vector drawables need **no conversion** — CMP's `composeResources/` system accepts Android XML vector drawables as-is, loaded as `VectorPainter`. The only caveat (no external Android resource references) is already satisfied: zero files reference `@color/`, `@dimen/`, `?attr/`, `@android:`, `@drawable:`, `aapt:attr`, or `android:tint`.

---

## What this phase is actually for

Give the `ui:theme` module a CMP resource directory so its 51 icon vector drawables are accessible from `commonMain` (and therefore from `ui:chat`'s commonMain after Phase 20) via the generated `Res.drawable.xxx` accessors, instead of the current Android-only `R.drawable.flash_ic_xxx` accessors.

The actual change is small:
1. Move 51 XML files from `src/main/res/drawable/` to `src/commonMain/composeResources/drawable/`
2. Change `FlashIconSpec.drawableRes` from `@DrawableRes Int` to `DrawableResource`
3. Update the 50 `FlashIcons.val` lines from `R.drawable.xxx` to `Res.drawable.xxx`
4. Add `compose.components.resources` dependency (gated by D3)
5. Remove the unused `res/` directory (now empty) and `R` import

**What this phase does NOT do:**
- Does NOT change the `ui:chat` module or its 208 `FlashIcons.X` / `FlashIcon(icon=...)` call sites — they are source-compatible with the new `FlashIconSpec` type.
- Does NOT switch the whole module to the `org.jetbrains.compose` plugin (that's Phase 18's job, though this phase assumes the plugin is available).
- Does NOT change any icon appearance, file names, `contentDescription` values, or tinting behavior.
- Does NOT touch test files (5 tests in `ui:theme` — they test color palettes, feedback logic, and sounds, not icon resources).
- Does NOT delete `flash_ic_arrow_left.xml` (the single unreferenced drawable — 50 refs in 51 files). It is moved alongside the rest; deletion is optional cleanup.

---

## Preconditions — do not start until all are true

1. **Phase 06 is complete and logged** — `ui:theme` is a KMP module with `commonMain` source set. If the KMP pilot hasn't been run, the `composeResources/` directory has no container to move into.
2. **D3 is resolved or the agent has proceeded with Option A** — the `org.jetbrains.compose` plugin and `compose.components.resources` must be available. If D3 is still completely unresolved (owner hasn't answered and agent chooses not to proceed), this phase is blocked.
3. **The 51 drawable files are verified** — confirm via `Get-ChildItem ui/theme/src/main/res/drawable/` that all 51 `flash_ic_*.xml` files are present and no other drawable types exist.
4. **`flash_ic_arrow_left.xml` is confirmed dead** — grep for `flash_ic_arrow_left` across the repo; it should match only the file itself and this document. (Confirmed in session grounding: 50 `R.drawable.` refs in FlashIcons.kt, 51 XML files; `arrow_left` is the surplus.)

---

## Verified starting state

### File inventory

| Location | File count | Content |
|---|---|---|
| `ui/theme/src/main/res/drawable/` | **51** | `flash_ic_*.xml` — self-contained Android `<vector>` XML |
| `ui/theme/src/main/res/` | **1** | Only `drawable/` subdirectory — no `values/`, `strings.xml`, `colors.xml`, `AndroidManifest.xml` |
| `ui/theme/.../icons/FlashIcons.kt` | **1** | Icon spec + composables; 50 `R.drawable.flash_ic_*` refs |

### 51 drawable files (sorted)

```
flash_ic_archive.xml     flash_ic_attach.xml      flash_ic_back.xml
flash_ic_bolt.xml        flash_ic_call.xml        flash_ic_camera.xml
flash_ic_chat.xml        flash_ic_check.xml       flash_ic_clock.xml
flash_ic_close.xml       flash_ic_connection.xml  flash_ic_delete.xml
flash_ic_delivered.xml   flash_ic_device.xml      flash_ic_download.xml
flash_ic_edit.xml        flash_ic_encryption.xml  flash_ic_failed.xml
flash_ic_flag.xml        flash_ic_forward.xml     flash_ic_gallery.xml
flash_ic_group.xml       flash_ic_heart.xml       flash_ic_microphone.xml
flash_ic_more.xml        flash_ic_mute.xml        flash_ic_nearby.xml
flash_ic_pause.xml       flash_ic_pin.xml         flash_ic_play.xml
flash_ic_react.xml       flash_ic_read.xml        flash_ic_relay.xml
flash_ic_reply.xml       flash_ic_retry.xml       flash_ic_search.xml
flash_ic_send.xml        flash_ic_settings.xml    flash_ic_share.xml
flash_ic_sliders.xml     flash_ic_stop.xml        flash_ic_thread.xml
flash_ic_thumb_down.xml  flash_ic_thumb_up.xml    flash_ic_transfer.xml
flash_ic_upload.xml      flash_ic_verified.xml    flash_ic_video_call.xml
flash_ic_wifi.xml        flash_ic_wifi_direct.xml
**flash_ic_arrow_left.xml**  ← dead (0 refs outside the file itself)
```

### `FlashIconSpec` current definition

```kotlin
// ui/theme/src/main/java/com/transfer/flash/ui/icons/FlashIcons.kt
@Immutable
data class FlashIconSpec(
    @param:DrawableRes val drawableRes: Int,   // ← the type that changes
    val contentDescription: String,
)
```

### Current `FlashIcons` object pattern (50 lines like this)

```kotlin
val Send = FlashIconSpec(R.drawable.flash_ic_send, "Send message")
```

### Current `FlashIcon` composable (the overload that uses `drawableRes`)

```kotlin
@Composable
fun FlashIcon(icon: FlashIconSpec, ...) {
    FlashIcon(
        painter = painterResource(icon.drawableRes),  // ← Jetpack painterResource(Int)
        ...
    )
}
```

### 208 call sites in `ui:chat` — all use the public API, unaffected by the internals change

They call `FlashIcons.Send` (val access) and `FlashIcon(icon = myIcon, ...)` (composable). Neither references `drawableRes` directly, `R.drawable`, or `@DrawableRes`. Source-compatible.

---

## The change

### Step 1 — Move the 51 drawable XML files

**Action:** `git mv` every file from `ui/theme/src/main/res/drawable/` to `ui/theme/src/commonMain/composeResources/drawable/`.

```powershell
# From repo root
$src = "ui/theme/src/main/res/drawable"
$dst = "ui/theme/src/commonMain/composeResources/drawable"
New-Item -Path $dst -ItemType Directory -Force
Get-ChildItem "$src/flash_ic_*.xml" | ForEach-Object {
    git mv $_.FullName "$dst/$($_.Name)"
}
```

**Post-move check:** confirm `src/main/res/drawable/` is empty; `src/commonMain/composeResources/drawable/` has 51 files.

```powershell
Get-ChildItem ui/theme/src/main/res/drawable -Recurse    # should be empty or absent
Get-ChildItem ui/theme/src/commonMain/composeResources/drawable -Name | Measure-Object
```

### Step 2 — Remove the now-empty `res/` directory tree

Since `ui/theme/src/main/res/` had **only** `drawable/` (no `values/`, `strings.xml`, `colors.xml`, `AndroidManifest.xml`), the entire `res/` tree can be deleted.

```powershell
Remove-Item -Recurse -Force ui/theme/src/main/res
```

### Step 3 — Update `FlashIcons.kt`: change the spec type

**3a — Replace the import that pulls in `@DrawableRes` and `R`:**

```kotlin
// BEFORE:
import androidx.annotation.DrawableRes
import com.transfer.flash.ui.theme.R

// AFTER:
import org.jetbrains.compose.resources.DrawableResource
```

**3b — Change `FlashIconSpec.drawableRes` from `Int` to `DrawableResource`:**

```kotlin
// BEFORE:
@Immutable
data class FlashIconSpec(
    @param:DrawableRes val drawableRes: Int,
    val contentDescription: String,
)

// AFTER:
@Immutable
data class FlashIconSpec(
    val drawableRes: DrawableResource,
    val contentDescription: String,
)
```

**3c — Replace all 50 `R.drawable.xxx` vals with `Res.drawable.xxx`:**

```kotlin
// BEFORE:
val Send = FlashIconSpec(R.drawable.flash_ic_send, "Send message")

// AFTER:
val Send = FlashIconSpec(Res.drawable.flash_ic_send, "Send message")
```

Do this for all 50 icon vals (lines 36–89 of the current file). The `R.drawable.` prefix is replaced by `Res.drawable.` for every entry. The `Res` object is generated by the CMP resource plugin from the `composeResources/` directory.

**3d — Add the `Res` import:**

```kotlin
// Add at the top of FlashIcons.kt, after the package declaration:
import com.transfer.flash.ui.theme.generated.resources.Res
```

> ⚠️ The exact generated package path depends on the module's namespace and the CMP resource plugin configuration. For `ui:theme` with `namespace = "com.transfer.flash.ui.theme"`, the generated path is `com.transfer.flash.ui.theme.generated.resources`. Verify this by checking the generated sources after the build — if the path differs, adjust the import. For a fallback, the `Res` object can also be imported via `import generated.resources.Res` or the fully qualified `Res.drawable.xxx` can be used without an import at each call site.

**3e — Update the `painterResource` import (the `FlashIcon` composable):**

```kotlin
// BEFORE:
import androidx.compose.ui.res.painterResource

// AFTER:
import org.jetbrains.compose.resources.painterResource
```

Both overloads are named `painterResource`; the parameter type changes from `Int` to `DrawableResource`. The composable body stays the same:

```kotlin
@Composable
fun FlashIcon(icon: FlashIconSpec, ...) {
    FlashIcon(
        painter = painterResource(icon.drawableRes),  // ← same call, different overload
        ...
    )
}
```

### Step 4 — Update `ui:theme/build.gradle.kts`

**4a — Add the `org.jetbrains.compose` plugin** (this is D3 Option A being enacted):

```kotlin
// In the plugins block:
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
    // ...existing...
    id("org.jetbrains.compose")                      // ← ADD
    id("org.jetbrains.kotlin.plugin.compose")        // ← ADD (if not already present)
}
```

The `org.jetbrains.compose` plugin brings the CMP compiler plugin, the `composeResources/` directory support, and the `compose.components.resources` artifact.

**4b — Add `compose.components.resources` to dependencies:**

```kotlin
dependencies {
    // ...existing dependencies...
    implementation(compose.components.resources)      // ← ADD
}
```

This replaces the need for `androidx.compose.ui.res.painterResource` — the CMP `painterResource(DrawableResource)` overload comes from this artifact.

**4c — Remove the unused `res/` declaration** — if the `android {}` block contains `sourceSets { main { res.srcDirs(...) } }` that pointed to `src/main/res/`, remove it. If there is no such explicit declaration, nothing to do.

### Step 5 — Build and verify

```powershell
./gradlew :ui:theme:compileKotlinDesktop --no-configuration-cache
./gradlew :ui:theme:compileDebugKotlin --no-configuration-cache
```

Both must succeed. The `compileKotlinDesktop` target is the critical one: it proves the `Res.drawable.xxx` accessors work for the JVM target.

### Step 6 — Verify `ui:chat` still compiles

The `ui:chat` module depends on `ui:theme` via `implementation(project(":ui:theme"))`. After the changes, its 208 `FlashIcons.X` / `FlashIcon(icon=...)` call sites must still compile without changes.

```powershell
./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache
```

---

## Verification gate

| Check | Command | Expected result |
|---|---|---|
| 51 drawables moved | `Get-ChildItem ui/theme/src/commonMain/composeResources/drawable/ -Name | Measure-Object` | Count = 51 |
| Old res/ empty | `Test-Path ui/theme/src/main/res/drawable` | `False` (or empty) |
| FlashIcons.kt type change | `Select-String "DrawableResource" ui/theme/.../FlashIcons.kt` | `DrawableResource` appears in `FlashIconSpec` |
| No `R.drawable` remaining | `Select-String "R\.drawable" ui/theme/.../FlashIcons.kt` | 0 matches |
| `Res.drawable` present | `Select-String "Res\.drawable" ui/theme/.../FlashIcons.kt` | 50 matches |
| ui:theme desktop compiles | `./gradlew :ui:theme:compileKotlinDesktop --no-configuration-cache` | `BUILD SUCCESSFUL` |
| ui:theme android compiles | `./gradlew :ui:theme:compileDebugKotlin --no-configuration-cache` | `BUILD SUCCESSFUL` |
| ui:chat android compiles | `./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache` | `BUILD SUCCESSFUL` |
| flash_ic_arrow_left moved | `Test-Path "ui/theme/src/commonMain/composeResources/drawable/flash_ic_arrow_left.xml"` | `True` |

---

## Do NOT

- Do NOT rewrite any of the 51 XML files. They are already valid CMP resources. AUDIT CORRECTION 6 is explicit.
- Do NOT delete `flash_ic_arrow_left.xml` — mark it as dead in a comment or move it, but do not delete it in this phase. Deletion is optional cleanup and belongs in a separate commit.
- Do NOT touch `ui:chat` — its 208 call sites are source-compatible. If they don't compile, the issue is in `ui:theme`'s public API, not in `ui:chat`.
- Do NOT change the `FlashIcon` composable signatures — the two overloads (`FlashIconSpec` and `Painter`) stay identical. Only the internals of `FlashIconSpec` change.
- Do NOT change `FlashIcons.mvpChatSet` or any other `FlashIcons` object member. Only the val type changes.
- Do NOT add `composeResources/` to `ui:chat` — that module's resources are Phase 20's concern.
- Do NOT change `ui:theme` test files — they test palette/feedback/sounds, not icons.

---

## Rollback

If the build fails, the most likely causes are:

1. **Missing `org.jetbrains.compose` plugin** — add it to `ui:theme/build.gradle.kts` plugins block.
2. **Wrong `Res` import path** — the generated-`Res` package may differ from the expected `com.transfer.flash.ui.theme.generated.resources`. Check the generated sources directory after a failed build to find the actual path.
3. **`DrawableResource` not found** — confirm `compose.components.resources` is in the dependencies block.
4. **`painterResource` overload ambiguity** — if both Jetpack and CMP `painterResource` are importable, the compiler may not resolve which overload to use. Drop the Jetpack import and keep only the CMP one.

If all else fails:
```powershell
git checkout ui/theme/src/main/java/com/transfer/flash/ui/icons/FlashIcons.kt
git checkout ui/theme/build.gradle.kts
git checkout ui/theme/src/main/res/                    # restores the res/ tree
# Phase 17 is not committed — wait for resolution
```

---

## Log entry for `logs/migration.md`

```markdown
## Phase 17 — UI resources (icon drawables → CMP composeResources)

- **Date:** 2026-08-31
- **Agent/model:** Copilot (autopilot)
- **Commit:** <short sha, or "not committed — blocked">
- **Decisions relied on:** D3=A (proceeded with recommendation — CMP resources required)

### Change
Moved 51 `flash_ic_*.xml` vector drawables from `ui/theme/src/main/res/drawable/` to `ui/theme/src/commonMain/composeResources/drawable/`. Changed `FlashIconSpec.drawableRes` from `@DrawableRes Int` to `DrawableResource` (CMP type). Updated all 50 `FlashIcons.val` refs from `R.drawable.xxx` to `Res.drawable.xxx`. Added `compose.components.resources` dependency. Removed now-empty `res/` directory. No changes to `ui:chat` — 208 call sites are source-compatible.

### Files changed
- **Move:** 51 XML files: `ui/theme/src/main/res/drawable/` → `ui/theme/src/commonMain/composeResources/drawable/`
- **Modify:** `ui/theme/src/main/java/.../icons/FlashIcons.kt` (type change, imports, 50 R→Res refs)
- **Modify:** `ui/theme/build.gradle.kts` (add org.jetbrains.compose plugin, compose.components.resources)
- **Delete:** `ui/theme/src/main/res/` (empty directory tree)

### Verification
Command run:
```
./gradlew :ui:theme:compileKotlinDesktop :ui:theme:compileDebugKotlin :ui:chat:compileDebugKotlin --no-configuration-cache
```
Result: PASS / FAIL
<Paste tail of output>

Additional checks:
- `Get-ChildItem ui/theme/src/commonMain/composeResources/drawable/ | Measure-Object` → 51 files
- `Select-String "R\.drawable" ui/theme/.../FlashIcons.kt` → 0 matches
- `Select-String "Res\.drawable" ui/theme/.../FlashIcons.kt` → 50 matches

### Deviations from the phase file
None.

### Known issues
- `flash_ic_arrow_left.xml` is dead (0 refs outside the file itself). Moved alongside the rest; not deleted.
- The exact `Res` import path may differ from `com.transfer.flash.ui.theme.generated.resources` depending on the CMP resource plugin configuration. Verified at build time.

### Next step
Phase 18 — `ui:theme` KMP conversion (D3/D4 gated).
```