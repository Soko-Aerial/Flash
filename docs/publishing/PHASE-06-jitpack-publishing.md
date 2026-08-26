# Phase 6 — JitPack Publishing & Release

**Goal:** turn the prepared repo into a resolvable Gradle dependency via JitPack,
and verify a real consumer can pull it. This is the phase that actually ships it.

**Prereq:** Phases 1–2 at minimum (a compilable umbrella artifact). Phases 3–5
strongly recommended before a public tag.

---

## How JitPack works (mental model)

1. You push a Git **tag** (e.g. `v1.0.0`) and cut a GitHub **release**.
2. JitPack clones the tag on its servers, runs your Gradle build with
   `publishToMavenLocal`, and captures the resulting `.aar` + POM from `~/.m2`.
3. Consumers add the JitPack Maven repo and request
   `com.github.<user>.<repo>:<artifactId>:<TAG>`.

Requirements JitPack enforces: a working `maven-publish` publication (present),
`publishToMavenLocal` must succeed, and the build JDK must satisfy AGP 9.3.1
(**JDK 17**). Android SDK + `ANDROID_HOME` are provided by JitPack. No signing,
no `repositories{}` publish target, no Central POM validation.

---

## Task 6.1 — Confirm the publication blocks are JitPack‑ready

Each `core/*/build.gradle.kts` already has `maven-publish` +
`singleVariant("release") { withSourcesJar() }` + a `register<MavenPublication>`.
After Phase 1 the `version`/`group` come from root. Confirm per module:
- `from(components["release"])` inside `afterEvaluate` (present).
- `artifactId = "core-<name>"` retained.
- No `signing {}` block, no `repositories {}` inside `publishing {}` (not needed;
  remove any that were added for Central).

Optionally add lightweight POM metadata (JitPack doesn't require it, but it shows
in the POM and is cheap):
```kotlin
pom {
    name.set("Flash ${'$'}{project.name}")
    description.set("Offline LAN peer-to-peer transfer engine for Android")
    url.set("https://github.com/<user>/<repo>")
    licenses { license { name.set("Apache-2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
}
```

**Acceptance:** `./gradlew publishToMavenLocal` publishes every supported module
into `~/.m2/repository/com/transfer/flash/…` with matching version.

## Task 6.2 — Add `jitpack.yml` (pins the JDK)

JitPack's default JDK is too old for AGP 9.3.1. Create `jitpack.yml` at the repo
root:
```yaml
jdk:
  - openjdk17
install:
  - ./gradlew :core:common:publishToMavenLocal
      :core:security:publishToMavenLocal
      :core:discovery:publishToMavenLocal
      :core:network:publishToMavenLocal
      :core:transfer:publishToMavenLocal
      :core:persistence:publishToMavenLocal
      :core:engine:publishToMavenLocal
      -x test -x lint
```
Notes:
- List **only** the supported modules from Phase 4 Task 4.3 (add `:core:messaging`
  if published). `-x test -x lint` keeps JitPack builds fast and avoids test‑only
  deps (e.g. bouncycastle, robolectric) failing the publish.
- If the multi‑line `install:` gives YAML trouble, put the whole gradle invocation
  on one line.
- Keep the wrapper committed (Phase 1 Task 1.4) — JitPack calls `./gradlew`.

**Acceptance:** `jitpack.yml` exists and lists the supported modules under
`openjdk17`.

## Task 6.3 — Version/tag discipline

JitPack serves artifacts under the requested **tag**. The published `version`
(root `flashLibraryVersion`, Phase 1) should equal the tag to avoid confusion.
Release procedure:
```bash
# 1. set flashLibraryVersion = "1.0.0" in root build.gradle.kts, commit
git commit -am "release: 1.0.0"
# 2. tag exactly matching the version
git tag v1.0.0
git push origin main --tags
# 3. create a GitHub Release for tag v1.0.0
```
Then trigger/watch the build at `https://jitpack.io/#<user>/<repo>` — the first
resolve of a new tag builds it. A green "log" link = success; a red one shows the
Gradle error to fix.

**Acceptance:** `jitpack.io` shows a green build for the tag; the "Get it" snippet
lists `core-engine`.

## Task 6.4 — Verify as a real consumer (the true acceptance gate)

In a **separate** throwaway Android project (not this repo):
```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
// app/build.gradle.kts
dependencies {
    implementation("com.github.<user>.<repo>:core-engine:v1.0.0")
}
```
Then paste the README quick‑start (`Flash.create(context)` → `sendFile`) and build
the app. It must compile and resolve **without** manually adding any `core:*`
transitive dependency.

**Acceptance:**
- The external app resolves `core-engine` from JitPack and compiles against the
  public API with zero manual transitive deps (proves Phase 2 on the *published*
  POM, not just locally).
- (Optional) a granular consumer of `core-network:v1.0.0` also compiles.

## Task 6.5 — Finalize README

Fill the `<user>`, `<repo>`, `<TAG>` placeholders in the Phase 5 README with the
real values and add the JitPack badge:
```markdown
[![](https://jitpack.io/v/<user>/<repo>.svg)](https://jitpack.io/#<user>/<repo>)
```

**Acceptance:** README install snippet is copy‑paste‑correct against the live tag.

---

## Done means

1. `jitpack.io` green build for `v1.0.0`.
2. A fresh external project resolves `core-engine:v1.0.0` and transfers a file
   using only the documented public API.
3. README badge + snippet reflect the published tag.

