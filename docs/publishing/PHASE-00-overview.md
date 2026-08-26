# Flash Core — Publishing Plan (GitHub → JitPack → Gradle)

> **Audience:** the implementing AI. This folder turns every publishing blocker
> for the `core:*` LAN‑transfer library into an ordered set of phases, each with
> exact files, code, and acceptance criteria. Do the phases **in order** — later
> phases assume earlier ones landed.

## 1. Goal

Publish the `core:*` modules as a reusable Android/Kotlin library so third‑party
developers consume the LAN peer‑to‑peer transfer engine instead of building it
from scratch. **Distribution channel: GitHub repo → JitPack build → Gradle
dependency.** We are **NOT** targeting Maven Central.

## 2. Why JitPack changes the problem

JitPack builds your Git tag on its servers and serves the resulting `.aar`. That
**removes** three Maven‑Central blockers entirely — do **not** spend effort on
them:

| Maven Central requirement | Needed for JitPack? |
|---|---|
| GPG / PGP signing | ❌ No |
| Sonatype/Central `repositories {}` publish target | ❌ No (JitPack *is* the repo) |
| Strict POM (`licenses`/`developers`/`scm`) validation | ❌ Not enforced (still nice‑to‑have) |
| `withJavadocJar()` | ❌ Not required |

What JitPack **does** require (Phase 6 covers it):
- A working `maven-publish` setup where `publishToMavenLocal` succeeds.
- A JDK new enough for AGP 9.3.1 (JDK 17) declared in `jitpack.yml`.
- A pushed Git **tag** + GitHub **release**.

**The architectural problems remain regardless of host** and are the real work:
dependency‑scope leakage (Phase 2), uncurated public API (Phase 3), and the
SQLCipher/Room payload (Phase 4). A library that "builds on JitPack" but whose
individual artifacts don't compile for consumers is still broken.

## 3. Phase index

| Phase | File | Fixes | Blocking? |
|---|---|---|---|
| 1 | `PHASE-01-foundation.md` | LICENSE, coordinates, compat baseline, `.gitignore` | Yes |
| 2 | `PHASE-02-dependency-scope.md` | `implementation`→`api` leakage (uncompilable artifacts) | **YES — hard blocker** |
| 3 | `PHASE-03-api-surface.md` | `explicitApi()` + hide internals (Chaos harness, codecs, pipelines, DAOs) | Major |
| 4 | `PHASE-04-persistence-decoupling.md` | SQLCipher/Room forced onto transfer‑only consumers | Major |
| 5 | `PHASE-05-consumer-ergonomics.md` | factory/builder, README, permissions, lifecycle/`close()` | Major |
| 6 | `PHASE-06-jitpack-publishing.md` | publishing config, `jitpack.yml`, tag/release, verify | **YES — ships it** |

## 4. Minimum vs full scope

If quota/time is tight, the **minimum shippable** path that produces a genuinely
usable library is: **Phase 1 → Phase 2 → Phase 6**, publishing **only
`:core:engine`** as the single supported artifact (it already uses `api(...)` so
it is internally coherent). Phases 3–5 raise quality (curated ABI, lighter
footprint, good DX) and should follow, but are not required to make `core-engine`
compile and run for a consumer.

Recommendation for the implementing AI: do all six in order. But if forced to
stop early, stop after Phase 2 having wired Phase 6 for `core-engine` only.

## 5. Global conventions (apply in every phase)

- **Group / coordinates.** Once on JitPack, consumers reference
  `com.github.<GitHubUser>.<Repo>:<artifactId>:<TAG>`. The `groupId` inside the
  publication blocks (`com.transfer.flash`) is irrelevant to consumers — do
  **not** waste effort "fixing" it. The consumer‑facing group is always
  `com.github.<user>.<repo>`. Keep the existing `artifactId = "core-<name>"`.
- **Version source of truth.** Today each module hardcodes `version = "1.0.0"`.
  Phase 1 centralizes this into one root property so a release is a one‑line bump
  plus a matching Git tag.
- **Build constraint.** The implementing environment may have **no local JDK**.
  When a change needs compilation to verify, end the phase by handing the user a
  single `./gradlew …` command in a ```bash block and fix any errors reported on
  the next turn. Never claim a build passed without seeing the output.
- **Write‑tool chunking.** If a file body exceeds the tool's size limit, write it
  in chunks silently. Do not switch tools or ask.
- **Do not touch** `:app`, `:ui:*`, or the unrelated `media-downloader-main/`
  project. Only `core:*`, root Gradle files, and `docs/` are in scope.
- **Verify, don't assume.** Before editing a signature, open the file and confirm
  the current declaration. Phase docs cite line numbers as of 2026‑08‑26; they
  may drift.

## 6. Definition of done for the whole plan

1. `./gradlew :core:engine:publishToMavenLocal` succeeds with no signing/POM
   errors.
2. A throwaway consumer project can add the JitPack repo + one dependency and
   call the public API (`FlashEngine` and friends) **without** manually adding
   any hidden transitive dependency.
3. `README.md` shows a copy‑paste Gradle snippet and a "file URI → transferred"
   quick start.
4. A Git tag pushed to GitHub produces a green build on `jitpack.io`.

## 7. Sources (JitPack mechanics, verified 2026‑08‑26)

- JitPack Android build reqs — https://docs.jitpack.io/android/
- JitPack build/version + `jitpack.yml` — https://docs.jitpack.io/building/
- Consumer coordinate & repo snippet — http://jitpack.io/
- Multi‑module submodule coordinate — https://stackoverflow.com/questions/48024138/submodule-to-jitpack

