# Library-compliance review

**Date:** 2026-09-12
**Tree:** `dev` at `e012bed` + the uncommitted working tree
**Scope:** the published `core-*` / `ui-*` artifacts and the app as a consumer of them
**Method:** read-only inspection of the build files, publication config, published metadata (local
throwaway repos), public API surface, docs, CI config and test harnesses. No behaviour was changed by
the review itself.

## Verdict

The library is **well above average for a pre-release project** and matches industry practice on the
things that are hardest to retrofit later: a strict, explicitly-declared public API, a written API
specification, consumer-shaped test modules that reproduce a downstream classpath, sources jars,
published ProGuard rules and a documented dependency-closure discipline.

The gaps are almost all **release-process**, not API design: the CI gate does not run on the branch
that is actually used, nothing verifies the *published* artifacts, and there is no ABI-stability check
between releases. Both release blockers hit on 2026-09-11 (`ui-platform-shims` missing from the
publication set, and `api(project(":core:ptt"))` on the wrong target) were invisible to the current
CI, which is the strongest argument for fixing the process gaps first.

## What already meets industry practice

| Practice | Where |
| --- | --- |
| Published artifact set with stable coordinates | `jitpack.yml`; per-module `artifactId` renames in place (`docs/publishing/PHASE-06-jitpack-publishing.md`) |
| Single source of truth for the version | `build.gradle.kts` `flashLibraryVersion`, with a rule that modules must not set `version`/`groupId` |
| Strict explicit API on the library tier | `explicitApi()` in every `core/*` module (ADR-023) |
| Internal-but-cross-module API kept opt-in | `@FlashInternalApi` (opt-in level ERROR) from `:core:common` |
| Written public API specification | `docs/architecture/public-api.md` (1.3.0) |
| Consumer-classpath test modules | `:sample:consumer` (umbrella-only) and `:sample:consumer-granular` (single artifact) |
| Facade contract test on a genuinely calling-free classpath | `sample/consumer/src/test/.../UmbrellaFacadeContractTest.kt` |
| Sources jars on published artifacts | KMP default for KMP modules; explicit `withSourcesJar()` for the AGP-only ones |
| Consumer ProGuard rules | `consumer-rules.pro` in every published module except `ui/platform-shims` |
| Licence hygiene | `LICENSE` (Apache-2.0), `NOTICE` |
| Dependency-scope discipline, documented | `api` vs `implementation` with rationale in each module and `docs/publishing/PHASE-02-dependency-scope.md` |
| Stability signalling | "Supported" / "Experimental" labels in README, `@FlashInternalApi`, ADR trail (023/025/032/033) |
| Normalized line endings / binary handling | `.gitattributes` |

## Findings

Severity reflects risk to a downstream consumer or to release safety.

### F1 - CI never runs on the working branch (High, process)

`.github/workflows/ci.yml` triggers on `push: branches: [ main ]` and on pull requests. All work in
this repository happens on `dev`, so the gate runs only when something reaches `main`. A PR-based flow
would cover it, but direct pushes to `dev` are the actual practice.

**Fix:** add `dev` to the push branches (or use a branch-list filter that includes every long-lived
integration branch).

### F2 - Nothing verifies the published artifacts (High)

CI runs `testDebugUnitTest assembleDebug`. Nothing publishes the module set to a local repository and
then resolves it as a consumer would.

Every release blocker found on 2026-09-11 was of exactly this shape and none of them could be seen by
the current CI:

- `ui-platform-shims` was a runtime dependency written into `ui-chat`'s POM but absent from the
  `jitpack.yml` install line and the README table, so every `ui-chat` consumer would have failed to
  resolve;
- `core-ptt` was a new published module missing from the same list;
- `api(project(":core:ptt"))` sat on `:core:engine`'s commonMain, which resolved for the Android
  target but broke variant selection for the JVM target and for `publishToMavenLocal`.

The `:sample:*` modules cannot catch this class of bug: they depend on `project(...)`, not on published
coordinates, so they never exercise the POM or Gradle Module Metadata.

**Fix:** a CI job that runs the `jitpack.yml` install line into a throwaway `maven.repo.local`, then
compiles a minimal consumer project against `com.transfer.flash:core-engine:<version>` resolved from
that repository, and fails if any declared dependency is missing from it.

### F3 - No ABI-stability gate between releases (High)

`kotlinx-binary-compatibility-validator` was removed with a documented rationale: under AGP 9's
built-in Kotlin plugin it registered no tasks for Android library variants, so it was inert
(see the comment in the root `build.gradle.kts`, ADR-023). `explicitApi()` guarantees that every
symbol is a deliberate decision, but it does **not** detect a signature change that breaks binary
compatibility between `1.1.0` and the next tag, and nothing else does either.

**Fix (needs a decision):** either re-introduce BCV where it does work (the KMP modules publish a JVM
target, so `apiDump`/`apiCheck` on that target is meaningful), or replace it with an explicit
"published ABI snapshot + diff in CI" check. Do not simply leave it absent and assume
`explicitApi` covers it: those two tools answer different questions.

### F4 - Version and documentation drift (Medium)

`public-api.md` is stamped `1.3.0`, the README install snippets target `v1.1.0`, and
`flashLibraryVersion` is `1.1.0`. The doc version has been bumped by documentation passes without a
corresponding release. A consumer reading the docs cannot tell which API version they are looking at.

**Fix:** decide the next tag, set `flashLibraryVersion` in the same commit that bumps the API doc, and
make the README snippets reference the released tag only.

### F5 - No CHANGELOG (Medium)

Release history lives in git tags, `logs/progress.md` and the ADR trail. For a published library, a
`CHANGELOG.md` is the conventional place a consumer looks for "what changed and does it affect me".

**Fix:** start one at the next release; backfill the `1.1.0` section from `logs/` if the history
matters.

### F6 - No static analysis and no editor config (Medium)

There is no detekt, no ktlint and no `.editorconfig`. Formatting and small-defect consistency
therefore depends on reviewer attention. This repository has unusually thorough prose-level
conventions (AGENTS.md, KDoc style, log formats) that tooling cannot enforce.

**Fix:** add ktlint (or detekt) with a minimal ruleset and an `.editorconfig`, then wire it into the
same CI job. Expect a large first pass of formatting churn; scope it to the published modules first.

### F7 - KDoc coverage on the public API is thin (Medium)

`explicitApi()` forces every declaration to declare its visibility, but documentation is optional and
often absent: a mechanical count of declarations against a preceding KDoc block gives roughly
**32%** for `core/messaging` commonMain and **49%** for `core/calling` (the measure is approximate -
it counts declarations mechanically, not semantic API surface). `public-api.md` compensates for
consumers who read the specification, but IDE hover and generated documentation do not.

**Fix:** require KDoc for declarations that appear in `public-api.md`, enforced by review rather than
tooling; consider Dokka if generated HTML docs are wanted for the release.

### F8 - The app is not a consumer of its own facade (Medium, architectural)

`grep -rn "FlashEngine" app/src` returns nothing: the app wires `DiscoveryEngineHolder`/`AppEngine` by
hand and never calls `Flash.create`. The published entry point is exercised only by
`:sample:consumer`'s contract test. This is a deliberate, documented position (ADR-033 point 6), but
it means the app cannot feel a public-API regression, and the facade has exactly one consumer - a test.

**Fix (needs a decision):** either migrate the app onto the facade incrementally (one subsystem at a
time, starting where the wrapper is thinnest), or accept the sample as the contract consumer and keep
the facade's test coverage deliberately thick. Do not leave it implicit: today the only thing keeping
the facade honest is the sample test.

### F9 - No dependency verification (Low)

`gradle/verification-metadata.xml` is absent, so nothing pins or verifies the checksums of the
third-party graph. Standard supply-chain hardening for a library, low urgency for a JitPack-distributed
pre-release.

**Fix:** `./gradlew --write-verification-metadata sha256 help` plus the needed task set; commit the
file and expect to update it when dependencies change.

### F10 - Small hygiene items (Low)

- `ui/platform-shims` ships no `consumer-rules.pro` although every other published module does
  (the module needs none today, but the asymmetry is unexplained).
- No `CONTRIBUTING.md`, `SECURITY.md` or `CODE_OF_CONDUCT.md`. Acceptable for a solo project; worth
  adding if external contributors are expected.

## What CI would and would not catch today

| Class of defect | Caught by current CI? |
| --- | --- |
| Compile errors across modules | Yes (`assembleDebug`) |
| Unit tests (including the KMP host tests and the consumer sample) | Yes (`testDebugUnitTest`) |
| `implementation`-vs-`api` leaks on a consumer's *compile* classpath | Yes - the `:sample:*` modules exist for this |
| Missing module on the publication list | **No** |
| Wrong target for a dependency (`commonMain` vs `androidMain`) | **No** - it breaks only the JVM target and publishing |
| POM / Gradle Module Metadata errors | **No** |
| ABI break between releases | **No** |
| Dependency-scope decision that is correct but undocumented | **No** |

## Recommended order

1. **F2 - publish-then-consume CI job.** Highest value: it covers the defect class that has already
   produced two release blockers, and it makes the `jitpack.yml` install line self-checking.
2. **F1 - run CI on `dev`.** One-line change; without it F2 never executes.
3. **F4 + F5 - release discipline.** Decide the next tag, align `flashLibraryVersion`, the API doc and
   the README, and start a CHANGELOG.
4. **F3 - ABI gate.** Needs a decision (BCV on the JVM target vs a published-ABI snapshot diff).
5. **F6 + F7 - static analysis and KDoc on the documented API surface.**
6. **F8 - facade adoption.** Needs a decision; the status quo is defensible only while the sample test
   stays thick.
7. **F9 + F10 - hygiene.**

## Limits of this review

- No device was involved, so nothing here speaks to runtime behaviour.
- The KDoc figure is a mechanical count, not a semantic measurement of the documented API surface.
- Dependency-scope choices were inspected for *consistency with their stated rationale*, not
  re-derived from first principles for each module.
- JitPack itself was not run; publication evidence comes from local repositories
  (`-Dmaven.repo.local=...`) and from inspecting the generated POM / Gradle Module Metadata.
- The app/UI tiers were reviewed as consumers of the library, not as product code.
