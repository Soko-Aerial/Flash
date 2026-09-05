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

`PlatformLock` is deliberately **duplicated per module** — `:core:common` (Phase 06),
`:core:discovery` (Phase 08), `:core:engine` (Phase 12) — because it is `internal` and `internal`
does not cross a Gradle module boundary. A phase that needs it in a fourth module should copy it
again rather than hoist: promoting `:core:common`'s copy to `public` would add a lock to
`core-common`'s published ABI under `explicitApi()` (R7) and edit a second module's build file (R4).
The hoist is a legitimate cleanup, but it is its own phase and no phase in the plan performs it.

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
./gradlew --stop >/dev/null 2>&1; sleep 8; ./gradlew :app:assembleDebug testDebugUnitTest :core:common:testAndroidHostTest :core:security:testAndroidHostTest :core:security:jvmTest :core:discovery:testAndroidHostTest :core:discovery:jvmTest :core:network:testAndroidHostTest :core:network:jvmTest :core:transfer:testAndroidHostTest :core:transfer:jvmTest :core:messaging:testAndroidHostTest :core:messaging:jvmTest :core:engine:testAndroidHostTest :core:engine:jvmTest --no-configuration-cache --continue --max-workers=2 --console=plain
```

Every converted module must be **named explicitly** on that command line, because the
unqualified `testDebugUnitTest` no longer reaches it. Add one `:module:testAndroidHostTest`
per conversion as each phase lands — **and one `:module:jvmTest` if the module has a
`commonTest`/`jvmTest` suite**, as `:core:security` does since Phase 07, `:core:discovery`
since Phase 08, `:core:network` since Phase 10, both `:core:transfer` and `:core:messaging`
since Phase 11, and `:core:engine` since Phase 12. `--continue` is load-bearing: without it the
12 known `:core:persistence` failures abort the run before later modules execute, and the total
silently drops. Those 12 are
**11 in `FlashSettingsDataStoreTest` + 1 in `DiscoveryModeSettingTest`** (measured Phase 08;
earlier entries attributed all 12 to the former).

Every phase must additionally paste the **test count** from
`*/build/test-results/**/TEST-*.xml` compared against the Phase 00 baseline
(`BASELINE_TEST_TOTAL = 863 / 12 failures / 0 skipped`). A phase that cannot show its
test count matches or exceeds baseline is not verified. Conversions may legitimately *raise*
the total — Phase 07 took it to **883 / 12 / 0** by adding a 10-test `commonTest` suite that runs
once per target, Phase 08 took it to **897 / 12 / 0** the same way (7 tests × 2 targets), Phase 10
to **913 / 12 / 0**, Phase 11 to **945 / 12 / 0** (two 8-test `commonTest` suites × 2 targets), and
Phase 12 to **961 / 12 / 0 across 128 XMLs** (945 + 8 `jvmTest` + 8 `testAndroidHostTest` + 1 for
`DefaultFlashEngineTest` now counted under `testAndroidHostTest`, − 1 for the stale
`testDebugUnitTest` results directory the plugin swap orphans).
Compare **per module** as well as in total: a total that still matches while one
module's suite has silently stopped running is exactly the failure mode R3 exists to catch.
Show the arithmetic, not just the number — a phase that adds N tests to a `commonTest` suite must
account for **2N**, and a phase that converts a module carrying an existing Android unit test must
account for the orphaned results directory too.

When tallying, delete the dead results directory of any task the conversion removed
(`<module>/build/test-results/testDebugUnitTest/` survives the plugin swap and will be
double-counted otherwise — Phase 07 hit this, and Phase 12 hit it again on `:core:engine`; delete
`build/reports/tests/testDebugUnitTest/` alongside it so the HTML report does not mislead either).

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
JVM_TEST_TASK             = jvmTest          # commonTest + jvmTest, on the desktop target
```

Discovered 2026-09-03 on `:core:common` (Gradle 9.5.0 / AGP 9.3.1 / Kotlin 2.2.10).
`testAndroidHostTest` is created by the `withHostTest { }` block inside `android { }` and
writes its XML to `build/test-results/testAndroidHostTest/`. Note the deliberate asymmetry
in the KMP task names: the Android target's compile task is `compileAndroidMain`, while the
JVM target's is `compileKotlinJvm` — there is no `compileKotlinAndroid`.

`compileKotlinJvm` is the **proof task** for R2: the `jvm()` target has no `android.jar` on
its compile classpath, so a green `compileKotlinJvm` is what certifies `commonMain` is
genuinely free of Android APIs. Run it on every converted module. It certifies **nothing about
`java.*`** — Phase 07 measured that; see **R6.1**.

`jvmTest` runs the module's `commonTest` sources against the desktop target's `actual`s. Without
it, a `jvmMain` `actual` is only ever *compiled*, never *executed*: `:core:common`'s three JVM
`actual`s (`PlatformLock`, `SystemTimeSource`, `UuidIdGenerator`) are in that position today,
because Phase 06 left all its tests in `androidHostTest`. Any phase that writes an `actual`
should put at least one behavioural assertion in `commonTest` so both platforms run it. Phase 07's
parity suite found no divergence — but it is the only thing in the build that *would* have found
one, since Android runs Conscrypt and the desktop JVM runs SunJCE. Phase 08 followed the rule for
`:core:discovery`'s own duplicated `PlatformLock`, and Phase 12 for `:core:engine`'s third copy:
between them, the contention cases in `PlatformLockTest` and `AutoConnectGateTest` are the only
tests in the repo that assert a lock actually excludes, and both run on both targets.


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

### R6.1 — Nothing in the build enforces R6 yet. Grep for it. (measured by Phase 07)

**`java.*` in `commonMain` currently compiles green.** Phase 07 proved this by putting

```kotlin
internal fun zzProbe(): String = java.util.UUID.randomUUID().toString()
```

into `core/common/src/commonMain/` and running
`:core:common:compileCommonMainKotlinMetadata :core:common:compileKotlinJvm --rerun-tasks`.
Result: `compileCommonMainKotlinMetadata` **SKIPPED**, `compileKotlinJvm` **succeeded**,
`BUILD SUCCESSFUL`. (Probe deleted afterwards.) The reason is that with only `android()` and
`jvm()` targets declared, every target sees a JVM classpath, so there is no compilation whose
classpath lacks `java.*` and the metadata compilation that would check common code in isolation
does not run at all.

Consequence: `compileKotlinJvm` (R3.1) certifies only that `commonMain` is free of **`android.*`**.
It says nothing about `java.*`. Until a Kotlin/Native target exists, R6 is enforced by review, so
every converting phase must run this and paste the output:

```bash
grep -rnE '\b(java|javax|android|androidx)\.' --include=*.kt core/*/src/commonMain ui/*/src/commonMain 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
```

Expected output: nothing. The second `grep -v` drops KDoc and comment lines, which legitimately
name platform types when documenting a seam (`PlatformCrypto`'s KDoc cites
`javax.crypto.AEADBadTagException` deliberately).

**The stdlib traps need their own command — they are plain Kotlin and no import line reveals
them.** Phase 12 added it, because "also re-scan the stdlib traps" as prose is not a check and
was in fact performed with a broken regex twice (see below):

```bash
grep -rnE '(@Synchronized|@Volatile|@JvmStatic|@JvmOverloads|@JvmField|@Throws|\bsynchronized[[:space:]]*\(|\bCharsets\b|String\.format|\bcurrentTimeMillis\b|\bputIfAbsent\b|\bcomputeIfAbsent\b|::class\.java|\bConcurrentHashMap\b|\bLocale\b|\bSystem\.)' --include=*.kt core/*/src/commonMain ui/*/src/commonMain 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
```

Expected output: nothing, **except** `@Volatile` lines whose file also carries
`import kotlin.concurrent.Volatile` — that is the legal common form and the annotation is spelled
identically. Verify the import rather than the annotation:

```bash
grep -rln '@Volatile' --include=*.kt core/*/src/commonMain | xargs -r grep -L 'import kotlin.concurrent.Volatile'
```

Expected output: nothing. Any file listed uses the JVM-only `kotlin.jvm.Volatile`. `xargs -r` is
load-bearing: without it, an empty first grep leaves `grep -L` reading stdin and the command hangs.
The check over-reports rather than under-reports — a file that only *mentions* `@Volatile` in a
comment is listed — which is the safe direction.

> **Do not put `\b` before `@`.** `\b@Synchronized\b` can never match: `\b` requires a
> word/non-word transition, and both the preceding space and `@` are non-word characters. Phase 10
> and Phase 11 both ran that form, and it is why the first trap scan of `:core:engine` reported
> zero hits on a file carrying two `@Synchronized` annotations. No leak actually escaped phases
> 06–11 — the corrected command was re-run against every converted `commonMain` in Phase 12 and
> came back clean — but the gate was defective for two phases without anyone noticing.


**This is a hole in the plan, not just in a phase.** Phases 00–24 never add a Kotlin/Native
target, so nothing in the current plan ever makes a `java.*` leak fail the build, and nothing
delivers the Kotlin/Native half of "Linux and all platforms" (the 2026-09-03 amendment above).
A phase that adds one native target — even `iosSimulatorArm64` with no product intent — would
turn R6 from a review rule into a compiler error for every module converted so far. Recommended
as a new phase; not in scope for any existing one (R1).

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

