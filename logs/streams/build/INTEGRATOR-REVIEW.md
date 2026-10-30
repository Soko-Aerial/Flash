# Stream A — integrator review (2026-10-30)

The stream's own logs (`progress.md`, `handoff.md`, `experiments.md` in this folder) were written
before review. This file records what was **accepted into `dev`**, what was **reverted**, and the
numbers that decided it. Read this before trusting any "before/after" in the other files.

Branch: `agent/opt-build` (25 commits) merged into `dev` with `--no-ff`. Base: `86fc9a83`.

## 1. Accepted (21 findings)

| Finding | Change | Why it is safe |
|---|---|---|
| `O21-01` | `org.gradle.parallel=true` | Cross-project parallelism is what made gate 2 faster (535s → 376s). |
| `O21-02` | `org.gradle.caching=true` | Pays off on the normal (non-forced) loop; see §3 for the forced-run caveat. |
| `O21-03` | daemon `-Xmx4096m -XX:+UseG1GC` | Measured, not guessed; the `SoftRefLRUPolicyMSPerMB=50` half was removed (§2). |
| `O21-04` | `:server:test` `maxParallelForks=4` + 1 GB heap | Single task in the build, so four forks cannot oversubscribe a gate. Stream measured 7 m 10 s → 5 m 22 s; not re-measured here (5+ min) and tagged PERF-A05. |
| `O21-05` | CI runs the `test` task | `:server:test` was never run by CI before. |
| `O21-06` | CI JDK 21, `--no-daemon` dropped | Keeps the daemon/JIT between CI steps. |
| `O21-07` | `bcutil-jdk18on` pinned to `1.81.1` | `dev`'s verification metadata already pinned 1.81.1, i.e. the range resolved there too: the pin records today's reality and kills the daily dynamic-version check. |
| `O21-08` | notices only on release variants | 3–8 s off every debug build; the task still runs where the file ships. |
| `O21-09` | `buildSrc` → `build-logic` composite build | Configures in ~1 s; `ThirdPartyNoticesTask` moved verbatim and is still registered by `app`/`desktop`. |
| `O21-10` | duplicate Room KSP processor dropped from `:app` | Verified: zero `@Database`/`@Entity`/`@Dao`/`RoomDatabase` references under `app/src/**`; Hilt KSP untouched. |
| `O22-02` | `StripSqliteTask` (976 KB vs 17.7 MB) | Contained to the desktop runtime; `:desktop:jvmTest` is the check (PERF-A06). |
| `O22-05` | Material 2 dropped from the desktop classpath | Verified: zero `androidx.compose.material.*` imports in `ui/**` and `desktop/**`. |
| `O20-05` | R8 `-assumenosideeffects` for `Log.d`/`Log.v` | Every `Log.d` call site in the repo is under `app/src/debug/**`, and `FlashLog` writes through `Log.println`; the rule prunes dependency calls only. Comment corrected. |
| `O19-04` | desktop JVM flags | Config-level; needs the installer smoke test (PERF-A03) to be meaningful. |
| `O01-03`/`O28-01` | `:benchmark` module + `BaselineProfileGenerator` | Scaffolding only. **Cannot run yet** (no `benchmark` build type on `:app`); the shipped profile was deleted, see §2. |
| `O27-01` | test concurrency | Kept, but 4 forks → **1** for every task (see §2: 2 forks flaked a host suite), see ERROR-873. |
| `O27-02` | `kotlinx.coroutines.debug=off` | A test-runner property. It is **not** virtual time; the stream's label was wrong. |
| `O27-05` | failure reporting (`showStackTraces`, `events("failed")`) | No behaviour change; useful. |

## 2. Reverted or corrected (4 reverted, 4 corrected)

**Reverted** in `11c650a3` (each is a release-shape change or reverses a decision written in the
file being edited):

1. `O22-01` ABI splits — `tools/build-release.ps1:265` reads
   `app/build/outputs/apk/release/app-release-unsigned.apk`, which AGP stops producing once splits
   are on. The release path breaks, and the artifact the owner distributes (universal) stays 52 MiB,
   so the advertised win does not reach them. → owner decision, PERF-A04.
2. `O22-04` jlink module pruning — can only fail at runtime, in an installer nobody ran.
3. `O22-06` narrowed WebRTC keep rules — the rule it replaced documents a release-only
   `NoSuchMethodError` inside `PeerConnectionFactory` init and says no narrower rule is honest.
4. `O22-07` four `:ui:chat` runtime dependencies dropped — the block says they are kept on purpose
   because `ui-chat` is published (`publish = true`) and removing them changes what consumers
   resolve. Reverting restores all four plus that comment.
5. `O19-05` `-Xshare:auto` — the JVM default, with no AppCDS archive shipped. Reverted rather than
   leave a record of a gain that does not exist; `O01-06` is therefore **not** closed.

**Corrected** in `68128d00`:

- `maxParallelForks = cpus/2` on **every** `Test` task → **`1` for every task**. With
  `org.gradle.parallel` already running up to max-workers test tasks at once, four forks each
  multiplied into ~32 JVMs on 8 CPUs. A middle setting of 2 for `*AndroidHost*` measured faster
  (gate 2 376 s) but made two `RealFlashChatRepositoryTest` cases fail under load — they synchronise
  with `delay(100)` — so it was dropped as well: **ERROR-873**. `:server:test` keeps its own 4.
- `-XX:SoftRefLRUPolicyMSPerMB=50` removed from the daemon (it evicts soft-referenced Kotlin
  DSL/compiler state sooner, which is the opposite of a warm daemon).
- `forkEvery = 100` removed (only restarts a worker past 100 classes; buys nothing here).
- `app/src/main/baseline-prof.txt` deleted: hand-written from the source tree, not generated. At
  least one rule cannot match (`RealFlashChatRepository` has no two-argument constructor). The
  generator and `:benchmark` module stay; PERF-A02 regenerates it on a device.

## 3. The numbers the review ran

All forced (`--rerun-tasks`), same machine, same 153 tasks, Windows 11, 8 CPUs / 20 GB, one build at a
time through `tools/flash-gradle.cmd`. "no-cache" means `--no-build-cache` as well, which is what an
evidence run wants: under `--rerun-tasks` nothing can be reused, so the cache only writes.

| Gate | `dev` before the merge | `agent/opt-build` as delivered | `dev` after review |
|---|---|---|---|
| 1 — ten `jvmTest` + 2 compiles | **275 s** (re-measured; plan recorded 283 s) | **467 s** | **204 s** no-cache; **316 s** while also filling the build cache |
| 2 — three host suites + `:app` unit | 535 s (plan baseline) | 429 s | **490 s** at the shipped one fork per task; **376 s** at two forks for host suites, which is 114 s faster but flaked ERROR-873 and is not adopted |

- The stream reported gate 1 at 246 s. That figure is **not reproducible**: its gate runs were
  unforced, so most compile tasks were up-to-date, while the baseline it compared against was
  forced. Recorded as ERROR-872.
- Gate 1's 316 s and 204 s are the same build: filling the cache after each of 153 tasks costs
  ~112 s and buys nothing on the run that fills it. The normal (non-forced) loop uses the cache and
  pays only the reads.
- Gate 2's 490 s at one fork means the win there is mostly `org.gradle.parallel` alone; the extra
  114 s that per-task forks buy is on hold until `RealFlashChatRepositoryTest` stops sleeping
  (ERROR-873 / PERF-A07), after which 2 forks should be re-measured and adopted if they stay green.
- Net after review: gate 1 −26 %, gate 2 −8 %, about 694 s against 810 s for the pair.

## 4. What was run besides the gate

| Check | Result |
|---|---|
| `:app:assembleRelease` (after reverting the splits) | **green, 4 m 48 s**; produced exactly `app-release-unsigned.apk` (52.15 MiB) — the file `tools/build-release.ps1:265` reads — plus the release-only `THIRD_PARTY_NOTICES.txt` (176 KB), so `O21-08`'s release scoping works |
| `:desktop:jvmTest --rerun-tasks` | **243 tests, 0 failures** with the stripped sqlite jar (`O22-02`); the old ERROR-106 failure did not reappear |
| `:server:test --rerun-tasks` | **661 tests, 0 failures** at 4 forks (`O21-04`), run while 7 other test JVMs were live |
| `:core:messaging:testAndroidHostTest` at 1 fork | **688/688** (the same suite flaked twice at higher concurrency — ERROR-873) |
| `:app:compileDebugKotlin`, `:desktop:compileKotlinJvm` | green |
| `projects` (configuration) | 1 s, with `:benchmark` and `:build-logic` included |

Not run, and therefore still claims: the packaged desktop app (PERF-A03), the `:benchmark` module (no `benchmark` build
type on `:app`; PERF-A02), and the per-ABI APK sizes' install behaviour (PERF-A01/A04).
