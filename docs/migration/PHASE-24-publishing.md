# Phase 24 — Publishing the KMP artifacts

**Blocked by:** Phase 23 (the 4-way interop matrix) — it must be **OPEN**. Nothing here
runs until the product is proven across all four directions with encryption on
(charter principle 7).
**Risk: MEDIUM.** No behaviour changes; the risk is coordinate/metadata mistakes that
break downstream resolution — and coordinates, once published, are effectively permanent.
**Decision:** D9 (sample consumers). The recommendation — keep the existing Android sample
consumers as-is and add a `sample/consumer-desktop` that validates the JVM artifact — may
be applied by the agent; record that it did so in the log.

## What this phase is actually for

PR #1 already set up publishing for the **Android-only** library: `maven-publish`, a single
`release` `MavenPublication` per module, and artifact IDs `core-common`, `core-security`, …
resolved through JitPack. Every KMP conversion phase (06–12, 17–20) then **rewrote** that
single publication into the KMP form (the `artifactId.replace(project.name, "core-…")`
prefix rewrite). This phase does **not** re-architect publishing; it **finishes and
validates** it: confirm every module emits correct KMP coordinates + Gradle module metadata,
tag a release, and prove a *fresh* consumer resolves Flash on **both** Android and desktop
JVM.

### The one structural fact you must understand first

A Kotlin Multiplatform library does not publish one artifact per module. Per the Kotlin
docs, the KMP plugin creates **multiple Maven publications per module**:

- one **per target** — e.g. the `jvm` target → `…:core-security-jvm:<v>`, the Android
  target → `…:core-security-android:<v>`; and
- one **umbrella root** publication, `kotlinMultiplatform`, → `…:core-security:<v>`, which
  carries a Gradle **`.module`** metadata file that *references* the per-target
  publications. ([kotlinlang: multiplatform-publish-lib](https://kotlinlang.org/docs/multiplatform-publish-lib.html))

**Consumers depend on the root coordinate** (`core-security`) and Gradle's module metadata
automatically resolves the right per-target artifact (`-android` for an Android consumer,
`-jvm` for a desktop consumer). That is why the prefix rewrite kept the **root** artifactId
equal to the old single coordinate: existing Android consumers that already wrote
`…:core-security:<v>` keep working unchanged, and desktop consumers get the jvm variant from
the same coordinate. The whole scheme only works if the `.module` files **and every
per-target artifact** are published together — publishing the root alone leaves the targets
unresolved.

## Preconditions

1. **Phase 23 is OPEN** (log verdict). If closed, STOP — publishing a library that fails its
   own interop matrix is the one thing the charter forbids.
2. **Every module that was converted publishes with the `core-…` root coordinate.** Each
   conversion phase's log should record the observed `publishToMavenLocal` coordinates; if
   any module still emits `security`/`common` (no prefix), fix that module's publication
   block before proceeding.
3. **A clean tree on the release branch**, and agreement on the **version string** to tag
   (coordinates + version are permanent once consumed — treat the choice as one-way).
4. **JitPack config from PR #1 is present** (the repo already resolves through JitPack). You
   are extending it to KMP, not introducing it.

## Steps

### Step 1 — Aggregate-publish to Maven Local and inspect the tree

```bash
./gradlew publishToMavenLocal --no-configuration-cache
```

Then inspect `~/.m2/repository/<group-path>/` (the group from the root build; e.g.
`com/transfer/flash/…` or the JitPack `com.github.<owner>.<repo>` group used at resolve
time). For **each converted module** confirm all of:

- a root dir `core-<module>/<v>/` containing `core-<module>-<v>.jar` (the umbrella metadata
  jar — for KMP this is produced automatically, no empty artifact needed), plus
  `core-<module>-<v>.module` (Gradle metadata) and `.pom`;
- `core-<module>-android/<v>/` with the Android artifact (`.aar` or jar-per-KMP-android) +
  `.module` + `.pom`;
- `core-<module>-jvm/<v>/` with the desktop jar + `.module` + `.pom`.

If the `-jvm` variant is **missing**, the desktop target was not published — verify the
module actually declares `jvm()` and that `publishToMavenLocal` (which publishes *all*
publications) was used rather than a single-target publish task. If the `.module` files are
missing, Gradle module metadata is disabled somewhere — it is on by default with
`maven-publish`; do not disable it. ([Gradle module metadata](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html))

### Step 2 — Wire the desktop sample consumer (D9)

Keep the existing `sample/consumer` and `sample/consumer-granular` **Android-only** (they
validate the Android variant, unchanged). Add **`sample/consumer-desktop`**: a tiny plain
`kotlin("jvm")` project that depends on the **root** coordinates
(`…:core-engine:<v>`, `…:core-security:<v>`, …) from `mavenLocal()` and compiles a trivial
use of the public API (e.g. references `Flash`/`FlashEngine` types and the desktop factory).
Its job is to prove **variant-aware resolution picks the `-jvm` artifact from the root
coordinate** — i.e. a desktop consumer writes the *same* coordinate an Android consumer
writes and still compiles.

```bash
./gradlew :sample:consumer-desktop:compileKotlin --no-configuration-cache
./gradlew :sample:consumer:assembleDebug --no-configuration-cache   # Android sample still green
```

Both must succeed against the Maven-Local artifacts from Step 1.

### Step 3 — Tag the release and let JitPack build it

Only after Steps 1–2 are green locally. Coordinates and version are **one-way** once a
downstream build resolves them — do not tag speculatively.

```bash
git tag <v>            # e.g. the agreed version string, no leading garbage
git push origin <v>
```

JitPack builds **on first resolve**, not on push: the first time any consumer requests
`com.github.<owner>.<repo>:core-<module>:<v>`, JitPack checks out the tag, runs the build,
and serves the artifacts. Trigger and watch that build explicitly rather than assuming it
succeeded — open `https://jitpack.io/#<owner>/<repo>/<v>` (or `GET` the "Log" link) and
confirm every `core-*` module reports **`ok`**, not `ERROR`. A JitPack build runs
`./gradlew publishToMavenLocal` inside JitPack's environment, so a module that published
locally in Step 1 should publish there too — but JitPack's JDK/SDK image is not your machine;
if a module fails there, read its JitPack log before touching anything else.
([JitPack docs](https://jitpack.io/docs/))

### Step 4 — Fresh-consumer resolution from JitPack, BOTH platforms

This is the real acceptance test of the phase: a consumer that has **never seen this repo**
resolves Flash from JitPack. Do it from a directory **outside** this repo and with **no
`mavenLocal()`** on the classpath, so nothing resolves from your machine by accident.

- **Android consumer:** a throwaway Android module with `maven { url = "https://jitpack.io" }`
  and `implementation("com.github.<owner>.<repo>:core-engine:<v>")`. It must resolve the
  **`-android`** variant automatically from the root coordinate and `:app`-style
  `assembleDebug` must compile against the public API.
- **Desktop consumer:** a throwaway `kotlin("jvm")` module, same JitPack repo, **same root
  coordinate** `com.github.<owner>.<repo>:core-engine:<v>`. It must resolve the **`-jvm`**
  variant automatically and `compileKotlin` must succeed.

Both consumers writing the **identical coordinate** and each getting the correct per-target
artifact is the whole point of the KMP publication model (the structural fact above). If the
desktop consumer pulls the `-android` variant (or fails with "no matching variant"), Gradle
module metadata did not publish or JitPack stripped it — return to Step 1 and confirm the
`.module` files exist for that module.

### Step 5 — Document the consumer coordinates

Write the resolved, copy-pasteable coordinates into the repo's consumer-facing docs (README
or a `docs/consuming.md`), for **both** an Android and a desktop consumer, including the
JitPack repository line and the root coordinate for every published `core-*` module at
version `<v>`. This is what downstream users read; get it exactly right because the
coordinates are now permanent.

## Verification gate — publish go/no-go

- [ ] Phase 23 verdict is **OPEN** (checked in `logs/migration.md`, not assumed).
- [ ] `publishToMavenLocal` produced, for **every** converted `core-*` module: the root
      `core-<module>` (jar + `.module` + `.pom`), the `core-<module>-android`, and the
      `core-<module>-jvm` publications.
- [ ] Every module's root artifactId is the `core-…` coordinate (no module still emits the
      bare `security`/`common`/… name).
- [ ] `sample/consumer-desktop:compileKotlin` green against Maven Local (jvm variant resolved
      from the root coordinate).
- [ ] `sample/consumer:assembleDebug` still green against Maven Local (android variant
      unregressed).
- [ ] Tag pushed; JitPack build reports **`ok`** for every `core-*` module.
- [ ] Fresh **Android** consumer resolves `com.github.<owner>/<repo>:core-engine:<v>` from
      JitPack and compiles (got the `-android` variant).
- [ ] Fresh **desktop** consumer resolves the **same** coordinate and compiles (got the
      `-jvm` variant).
- [ ] Consumer coordinates documented for both platforms.

**All boxes checked ⇒ Flash is published as a KMP library on Android and desktop JVM from a
single set of root coordinates. The migration is shipped.**
**Any box unchecked ⇒ do not announce the release; fix the failing step.**

## Do NOT

- **Do NOT publish anything while Phase 23 is CLOSED** (charter principle 7). The interop
  matrix gates the release, not the other way around.
- **Do NOT change the root coordinates set up in PR #1** (`core-common`, `core-security`, …).
  Existing Android consumers already resolve them; changing a published coordinate breaks
  every downstream build silently. The prefix rewrite exists precisely to keep them stable.
- **Do NOT hand-edit a `.module` or `.pom` file.** Gradle generates them; a hand-edited
  metadata file that disagrees with the actual artifacts is worse than a missing one. If the
  metadata is wrong, fix the `kotlin {}`/`publishing {}` block that produced it and republish.
- **Do NOT disable Gradle module metadata** to "simplify" the POMs. Without `.module` files,
  variant-aware resolution stops working and desktop consumers can no longer pick the `-jvm`
  artifact from the root coordinate — the entire scheme collapses to Android-only.
- **Do NOT delete or overwrite an already-published tag.** If `<v>` is wrong, publish a new
  version; a consumed coordinate is permanent.
- **Do NOT re-tag to "force" a JitPack rebuild.** Read the JitPack log and fix the real build
  failure; a moved tag pointing at different bytes is the same permanence violation.

## Rollback

Publishing is additive and the source tree is unchanged by this phase, so "rollback" is
narrow:

- **Before the tag is consumed by anyone:** you may delete the local tag and the remote tag
  and re-tag, because nothing has resolved it yet. The moment a downstream build (or JitPack
  cache) has resolved `<v>`, treat it as permanent and roll **forward** to a new version
  instead.
- **A failed JitPack build** is not a rollback situation — no artifacts were served. Fix the
  build (per the JitPack log) and let the next resolve rebuild the same tag.
- **The `sample/consumer-desktop` module** added in Step 2 is ordinary source; if it needs to
  be reverted, `git revert`/remove it like any other module. It ships in the repo, not in the
  library artifact, so removing it never affects published coordinates.

## Log entry (mandatory)

Append one entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom —
never edit an earlier entry):

- The **version string** tagged, and the exact root coordinate for **every** published
  `core-*` module.
- The Maven-Local tree observed in Step 1 (which modules emitted root + `-android` + `-jvm`).
- The JitPack build result per module (`ok`/`ERROR`) and the JitPack build-log URL.
- The two fresh-consumer results: Android resolved `-android`, desktop resolved `-jvm`, both
  compiled — with the exact coordinate string both wrote.
- Whether D9 was applied as recommended (Android samples kept as-is; `sample/consumer-desktop`
  added) — record that the agent proceeded on the recommendation.
- The final statement: **the migration's Definition of Done (Phase 23 matrix) was OPEN and
  the KMP artifacts are published on both platforms**, i.e. the project is shipped.
