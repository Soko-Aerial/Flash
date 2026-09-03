# Migration Conventions — MANDATORY

Every agent working on this migration MUST follow these rules. They exist because
this migration is executed incrementally by multiple agents, some of which have
limited context. Violating them silently breaks later phases.

> ## AMENDMENT 2026-09-03 — D1 = B, and the target is every platform
>
> The human's stated target is **Linux and all platforms**, not Android + Windows desktop.
> **D1 = Option B** (strict `commonMain`) is reaffirmed and is now load-bearing rather than
> merely chosen. Two rules below were written assuming D1 = A and are hereby **void**:
>
> - **R2 step 2 ("Put it in `jvmAndAndroidMain`") is void.** That source set is not created.
>   The escalation is now: (1) leave it where it is, (2) `expect`/`actual` with per-target
>   `actual`s. There is no JVM-only shared tier to hide `java.*` in.
> - **The `jvmAndAndroidMain` row in R5's table is void.** Do not create the directory.
>
> Everything else in R5 stands, plus: `jvm()` is one target that covers **Windows, Linux and
> macOS desktop** — there is no separate Linux target and no `linuxMain`. Desktop code in
> `jvmMain` must therefore be OS-neutral: no hardcoded `C:\` paths, no backslash path
> literals, no `%USERPROFILE%`. Use `java.nio.file.Path`, `File.separator`, and
> `System.getProperty("user.home")`. Phases 13–15 and 21 are written with Windows examples;
> read them as "desktop JVM", and treat a Windows-only assumption in them as a bug in the
> phase file (report it under **Known issues**, per R1).
>
> Kotlin/Native targets (iOS, and any others added later) get their own `actual`s. A phase that
> writes an `expect` must not assume the only `actual`s are JVM ones.

## R1 — One phase per session, one commit per phase

Do exactly the phase you were asked to do. Do not "also fix" things you notice.
If you notice a problem outside your phase, write it under **Known issues** in your
log entry and stop there.

## R2 — Never move code to `commonMain` to make something compile

If a file does not compile in `commonMain`, the correct actions are, in order:
1. Leave it where it is and move on.
2. ~~Put it in `jvmAndAndroidMain`~~ — **void.** D1 = Option B; that source set does not
   exist. See R5.
3. Put it in `androidMain` + `jvmMain` with an `expect`/`actual` seam.

Prefer an `expect`/`actual` **function** over an `expect`/`actual` **class**: functions are
stable, classes are still Beta (KT-61573) and emit a warning per declaration site. Use a class
only when the seam must carry per-platform state, as `PlatformLock` does, and suppress the
warning with `-Xexpect-actual-classes` in the module's `kotlin { compilerOptions { } }`.

When a seam replaces the *body* of an already-published declaration, keep the declaration
itself in `commonMain` and let it delegate to an `internal expect fun`. Phase 06 did this for
`SystemTimeSource` and `UuidIdGenerator`: their published FQNs and shapes are unchanged, so
cross-module callers such as `app`'s `PairingCoordinator` needed no edit at all. Turning them
into `expect object`s per target would have been an ABI change for no benefit.

Deleting an API, weakening encryption, or stubbing a function to force a
`commonMain` compile is **forbidden**. If a phase seems to require it, stop and
report it as blocked.

## R3 — Android must build and pass tests after every phase

**Phases 00–05 (before any module becomes KMP):**

```bash
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```

**Phase 06 onward:** `testDebugUnitTest` **stops existing** in any module converted to
`com.android.kotlin.multiplatform.library`. That plugin has **no build variants**, so
there is no `debug` variant and therefore no `testDebugUnitTest` task in that module.
A root-level `testDebugUnitTest` invocation will then silently run **fewer** tests than
before while still reporting success.

This is the single most dangerous failure mode in this migration: green build, tests
quietly not running.

Phase 06 discovered the replacement task name empirically and recorded it in R3.1 below
and in `logs/migration.md`. From Phase 06 onward the verification command is:

```bash
./gradlew --stop >/dev/null 2>&1; sleep 8; ./gradlew :app:assembleDebug testDebugUnitTest :core:common:testAndroidHostTest --no-configuration-cache --continue --max-workers=2 --console=plain
```

Every converted module must be **named explicitly** on that command line, because the
unqualified `testDebugUnitTest` no longer reaches it. Add one `:module:testAndroidHostTest`
per conversion as phases 07–12 land. `--continue` is load-bearing: without it the 12 known
`:core:persistence` `FlashSettingsDataStoreTest` failures abort the run before later
modules execute, and the total silently drops.

Every phase must additionally paste the **test count** from
`*/build/test-results/**/TEST-*.xml` compared against the Phase 00 baseline
(`BASELINE_TEST_TOTAL = 863 / 12 failures / 0 skipped`). A phase that cannot show its
test count matches or exceeds baseline is not verified.

`--no-configuration-cache` is required because this project enables the
configuration cache in `gradle.properties`, and KMP source-set wiring is a known
source of cache invalidation noise mid-migration. Re-enable it and re-verify at
Phase 24, not before.

If verification fails, the phase is **not done**. Do not commit. Do not proceed.

### R3.1 — Verified test task name (filled in by Phase 06)

```
ANDROID_UNIT_TEST_TASK    = testAndroidHostTest
ANDROID_MAIN_COMPILE_TASK = compileAndroidMain
JVM_COMPILE_TASK          = compileKotlinJvm
```

Discovered 2026-09-03 on `:core:common` (Gradle 9.5.0 / AGP 9.3.1 / Kotlin 2.2.10).
`testAndroidHostTest` is created by the `withHostTest { }` block inside `android { }` and
writes its XML to `build/test-results/testAndroidHostTest/`. Note the deliberate asymmetry
in the KMP task names: the Android target's compile task is `compileAndroidMain`, while the
JVM target's is `compileKotlinJvm` — there is no `compileKotlinAndroid`.

`compileKotlinJvm` is the **proof task** for R2: the `jvm()` target has no `android.jar` on
its compile classpath, so a green `compileKotlinJvm` is what certifies `commonMain` is
genuinely free of Android APIs. Run it on every converted module.


## R4 — Never edit two modules' build files in one commit unless the phase says to

Build-file changes are the highest-risk edits in this migration. Keeping them
isolated means `git revert` of a single commit always restores a working build.

## R5 — Source-set naming is fixed

Use these exact names. Do not invent variants. **Corrected by Phase 06** — the two rows
struck below were written before D1 was settled and before the real AGP 9.3.1 task/source-set
names were measured.

| Source set | Contains |
|---|---|
| `commonMain` | Pure Kotlin. stdlib + coroutines + kotlinx only. No `java.*`. |
| `androidMain` | `android.*`, `androidx.*`, and `java.*` used only on Android |
| `jvmMain` | Desktop/Linux/CI JVM code, including its own `java.*` |
| `commonTest`, `androidHostTest`, `androidDeviceTest`, `jvmTest` | Test mirrors |

- **`jvmAndAndroidMain` does not exist and must not be created.** D1 = Option B (strict
  `commonMain`, iOS/Kotlin-Native in scope) rules it out: a shared JVM-only parent would let
  `java.*` leak back into code that Kotlin/Native has to compile. The cost is that one-line
  `actual`s get duplicated in `androidMain` and `jvmMain` — that duplication is intentional,
  not an oversight. R2 step 2 and the `jvmAndAndroidMain`/`jvmAndAndroidTest` rows are void.
- **The Android unit-test source set is `androidHostTest`, not `androidUnitTest`.**
  `androidUnitTest` was the name under the older KMP Android plugin;
  `com.android.kotlin.multiplatform.library` 9.3.1 creates `androidHostTest` (host JVM tests,
  task `testAndroidHostTest`) and `androidDeviceTest` (instrumented, task
  `connectedAndroidDeviceTest`). Neither is called `androidTest`.

The desktop target is declared as plain `jvm()`, giving `jvmMain`/`jvmTest`.
Do **not** use `jvm("desktop")` — it would give `desktopMain` and every path in
these phase files would be wrong.

The language directory is `kotlin/`, never `java/`: e.g.
`core/common/src/commonMain/kotlin/com/transfer/flash/core/common/`.

## R6 — `java.*` is not available in `commonMain`

This is the single most common mistake. `java.util.UUID`, `java.io.File`,
`java.security.MessageDigest`, `ConcurrentHashMap` — none of these exist in
`commonMain`, even though both of our current targets are JVM. They only become
available in `androidMain` and `jvmMain`.

Watch for the JVM-only parts of the **Kotlin** stdlib too, which look common but are not:
`kotlin.synchronized`, `kotlin.text.Charsets`, `ByteArray.toString(Charset)`,
`String(bytes, Charset)`, `String.format`, `@kotlin.jvm.Volatile`. The common replacements are
`ByteArray.decodeToString()`, `String.encodeToByteArray()`, and `kotlin.concurrent.Volatile`
(Phase 05 already migrated all 66 `@Volatile` sites). `kotlin.synchronized` has no common
equivalent — Phase 06 added `core/common`'s `internal expect class PlatformLock` for it.

## R7 — Preserve `explicitApi()`

Every `core/*` module runs `explicitApi()` in strict mode (ADR-023). Every new
declaration needs an explicit `public`/`internal`/`private`. For `expect`/`actual`
pairs, the visibility modifier must be present and identical on both sides.

## R8 — Do not touch these without an explicit instruction

- `core/security/**` cryptographic logic — behaviour must be bit-identical
- Wire formats: `FlashEnvelope`, `FlashProtocol`, `ChunkFrame`, `MessageWireFrame`,
  `WsTransferMessages`, `TxtCodec`, `FlashPairingFrames`
- Room entities, DAOs, and `FlashMigrations`
- Anything under `docs/decisions.md` (append-only; add a new ADR, never edit one)

If a phase changes a wire format, it is a bug in the phase. Report it.

## R9 — Report honestly

If tests fail, paste the failure. If you skipped a step, say so. If you could not
verify something, say you could not verify it. A log entry that claims success
without the verification output is worse than no log entry.

## R10 — Do not upgrade dependency versions opportunistically

Versions change only in phases that explicitly say to change them. Every version
in `gradle/libs.versions.toml` is currently known-good with AGP 9.3.1 / Kotlin
2.2.10 / Gradle 9.5.0.

## R11 — Grep hygiene: exclude `media-downloader-main/`

The repo contains a **vendored, unrelated Android project** at
`media-downloader-main/`. It is committed to git (116 files) but is **not** in
`settings.gradle.kts` and is **not** part of this build. It has its own root
`build.gradle.kts`, its own `app/` module, and it uses `kotlin-android`, `kapt`-era
patterns, and `buildConfigField` — none of which apply to Flash.

Every grep in every phase must exclude it. A bare `grep -r ... .` will return hits
from it and send you editing files that do not affect the product.

Safe default form:

```bash
grep -rn --include=*.kt "PATTERN" core/ ui/ app/ sample/
```

If you must search from the repo root, exclude explicitly:

```bash
grep -rn --include=*.kt "PATTERN" . \
  --exclude-dir=media-downloader-main --exclude-dir=build --exclude-dir=.git
```

Also exclude `build/` (generated sources) and `docs/` (these phase files quote code,
so they match their own patterns). Never edit anything under `media-downloader-main/`.

Related trap: the repo root holds ~15 stale `*.log` files and several `*.png`
screenshots from earlier sessions. They are not inputs to any phase. Ignore them.

