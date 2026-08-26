# Phase 2 — Dependency Scope Correctness (HARD BLOCKER)

**Goal:** make the published artifacts actually **compile** for a downstream
consumer. This is the single most important phase — without it the individual
`core:*` artifacts are broken on JitPack even though the build "succeeds."

**Prereq:** Phase 1.

---

## The problem

Gradle `implementation(project(...))` dependencies are **not** exposed on the
consumer's compile classpath — in the generated POM they land in `runtime`
scope. That is fine only if the depended‑on module's types never appear in your
**public** API. But they do:

- `core:network` — `FlashNetwork.connect(device: FlashDevice)` and
  `FlashSession`/`FlashDeviceId`/`FlashTransportType` are all `core:common` types,
  yet `core:common` is an `implementation` dep.
- `core:transfer` — `FlashTransferRepository.sendFile(targetDevice: FlashDevice…)`
  exposes `core:common`; `RealFlashTransferRepository`'s constructor exposes
  `core:persistence` DAOs.
- `core:discovery` — `FlashDiscoveredEndpoint` / `NsdFlashDiscovery(...)` expose
  `core:common` types.
- `core:messaging` — repository constructors expose `core:persistence` DAOs.

Net effect: a consumer of `com.github.user.repo:core-network:TAG` gets
`FlashDevice` **off** their compile classpath → **"unresolved reference:
FlashDevice"** the moment they call the API. Only `:core:engine` is safe today
because it already uses `api(...)` for everything.

**Rule:** *if a type from module X appears anywhere in module Y's public API
(function params, return types, public constructors, public properties,
supertypes), then Y must declare `api(project(":core:X"))`, not
`implementation`.*

---

## Strategy: two supported shapes

**A. Umbrella artifact (recommended, minimum‑viable).** Tell consumers to depend
on **`core-engine`** only. It already `api`‑exposes every sibling module, so its
public surface is coherent and compilable as‑is. This is the fastest route to a
working library and should be the **documented default** in the README.

**B. Granular artifacts (nice‑to‑have).** Support depending on individual modules
(`core-network`, `core-transfer`, …). This needs the scope flips below.

Do **A** unconditionally (it's free). Do **B** in this phase for `core:common`
leaks (definite), and finish **B** after Phase 3's API dump reveals the exact
remaining cross‑module leaks.

## Task 2.1 — Flip `core:common` to `api` everywhere (definite)

`core:common` holds `FlashDevice`, `FlashDeviceId`, `FlashResult`, `FlashError`,
`FlashTransportType`, `FlashPeerPresence` — the vocabulary types that appear in
essentially every module's public signatures. Flip it in **all six** consumers.

In each of `core/persistence`, `core/security`, `core/discovery`, `core/network`,
`core/transfer`, `core/messaging` `build.gradle.kts`, change:
```kotlin
implementation(project(":core:common"))
```
to:
```kotlin
api(project(":core:common"))
```
(`core:engine` already uses `api` — leave it. `core:common` has no inter‑module
deps — nothing to change there.)

## Task 2.2 — Flip the remaining cross‑module leaks (data‑driven)

Do **not** guess the rest. After Phase 3 generates `.api` dumps, inspect each
module's dump for foreign types and flip exactly those deps. Known candidates to
confirm against the dump:

| Module | Candidate dep to promote to `api` | Trigger to look for in the `.api` |
|---|---|---|
| `core:network` | `security`, `discovery` | public type from those modules in a signature |
| `core:transfer` | `network`, `security`, `discovery` | ditto (`persistence` handled in Phase 4) |
| `core:messaging` | `network`, `security` | ditto (`persistence` handled in Phase 4) |
| `core:security` | `persistence` | public ctor/param exposing a persistence type |

For each confirmed leak, promote that `implementation(project(...))` to
`api(project(...))`. If a foreign type appears **only** in an internal/private
member, do **not** promote — instead it will have been hidden in Phase 3, and the
`implementation` scope is then correct.

## Task 2.3 — Prove it with a throwaway consumer (acceptance gate)

Scope bugs are invisible to the library's own build (it sees everything on the
classpath). The only reliable check is an **external** consumer.

1. Create a tiny sanity module `:sample:consumer` (or a separate throwaway
   project) that declares **only**:
   ```kotlin
   dependencies { implementation(project(":core:engine")) }
   ```
   and a Kotlin file that references the public API — e.g. builds a
   `FlashDeviceId`, calls a `FlashEngine`/`FlashNetwork` method, reads a
   `FlashResult`. If it compiles, the umbrella (shape A) is sound.
2. To validate shape B, repeat with `implementation(project(":core:network"))`
   only, referencing `FlashDevice`. It must compile **without** adding
   `core:common` manually.
3. Keep `:sample:consumer` out of the published set (it's a test harness). It is
   reused by Phase 5's quick‑start.

**Acceptance:**
- Every non‑engine core module uses `api(project(":core:common"))`.
- The `:sample:consumer` referencing only `:core:engine` compiles.
- (Shape B) referencing only `:core:network` compiles without a manual
  `core:common` line.

## Verification (hand off)

```bash
./gradlew :sample:consumer:assembleDebug :core:engine:publishToMavenLocal
```
Fix any "unresolved reference" errors by promoting the offending module dep to
`api` and re‑running.

