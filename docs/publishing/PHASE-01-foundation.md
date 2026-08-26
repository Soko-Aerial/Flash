# Phase 1 — Foundation & Repo Prep

**Goal:** put the non‑code scaffolding in place so later phases and JitPack have
what they need: a license, a single version source, a compatibility baseline, and
a clean repo. No public‑API behavior changes here.

**Prereq:** none. Do this first.

---

## Task 1.1 — Add a LICENSE (BLOCKER)

The repo has **no** license file, but the publishing plan claims Apache‑2.0.
Without a license, others legally cannot use the library.

1. Create `LICENSE` at the repo root with the standard **Apache License 2.0**
   full text (get the canonical text from https://www.apache.org/licenses/LICENSE-2.0.txt).
2. Fill the copyright line at the bottom: `Copyright 2026 <author/owner name>`.
   Ask the user for the name/entity if unknown; do not invent one.
3. Create `NOTICE` at the repo root:
   ```
   Flash Core
   Copyright 2026 <author/owner name>
   ```

**Acceptance:** `LICENSE` and `NOTICE` exist at repo root; `LICENSE` is the
verbatim Apache‑2.0 text (~202 lines).

---

## Task 1.2 — Centralize the library version (single source of truth)

Today all 8 `core/*/build.gradle.kts` hardcode `version = "1.0.0"`. A release
should be one edit + one tag, and JitPack resolves the Git **tag**, so the
published version must be easy to keep in lockstep with tags.

1. In the **root** `build.gradle.kts`, add above the `plugins {}` block:
   ```kotlin
   // Single source of truth for the published library version.
   // A release = bump this, commit, then `git tag vX.Y.Z` (tag must match).
   val flashLibraryVersion = "1.0.0"
   allprojects {
       version = flashLibraryVersion
       group = "com.transfer.flash"
   }
   ```
2. In **each** `core/*/build.gradle.kts`, delete the hardcoded
   `version = "1.0.0"` and `groupId = "com.transfer.flash"` lines inside the
   `register<MavenPublication>("release")` block. The `allprojects {}` block now
   supplies both. Keep the `artifactId = "core-<name>"` line — that stays
   per‑module. Result per module:
   ```kotlin
   publishing {
       publications {
           register<MavenPublication>("release") {
               artifactId = "core-network" // per-module name, unchanged
               afterEvaluate { from(components["release"]) }
           }
       }
   }
   ```

**Acceptance:** no `core/*/build.gradle.kts` contains a literal `version =` or
`groupId =` inside its publication; the version appears exactly once (root).
`./gradlew :core:engine:properties | grep version` (hand off) prints `1.0.0`.

---

## Task 1.3 — Decide & document the compatibility baseline

`compileSdk = 37` + AGP `9.3.1` forces every **consumer** onto AGP 9.3+ and
Gradle 9.5 + Kotlin 2.2. Anyone on AGP 8.x cannot build against the artifact.
This is a real adoption ceiling.

- **Decision to confirm with the user (do not silently change):** either
  (a) keep `compileSdk 37`/AGP 9.3.1 and document the hard floor, or
  (b) lower `compileSdk` to 35 and AGP to the 8.7+ line for far wider reach.
- Whichever is chosen, record it in the README compatibility table (Phase 5):
  minSdk 24, and the exact AGP/Gradle/Kotlin/JDK floor consumers need.
- No code change in this task unless the user picks (b); then update
  `libs.versions.toml` (`agp`) and every `compileSdk`, and re‑run the full build.

**Acceptance:** a one‑line decision is written into `PHASE-05` README notes.

---

## Task 1.4 — `.gitignore` / repo hygiene for JitPack

JitPack clones the repo and builds it clean, so committed build junk or a missing
wrapper breaks the build.

1. Confirm `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, and
   `gradle/wrapper/gradle-wrapper.properties` are **committed** (JitPack invokes
   `./gradlew`). If `.gitignore` excludes the wrapper jar, un‑exclude it.
2. Ensure `.gitignore` covers `.gradle/`, `**/build/`, `local.properties`,
   `.idea/`, `*.iml`.
3. Confirm `local.properties` is **not** committed (it hardcodes an SDK path;
   JitPack sets `ANDROID_HOME` itself).

**Acceptance:** `git ls-files gradle/wrapper` lists `gradle-wrapper.jar`;
`git ls-files local.properties` prints nothing.

---

## Verification (hand off to the user)

```bash
./gradlew :core:engine:publishToMavenLocal --dry-run
```
Confirms the publication wiring still resolves after the version/group move. A
real publish is verified in Phase 6.

