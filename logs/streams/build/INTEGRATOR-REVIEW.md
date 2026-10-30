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

## 2. Reverted or corrected (5 reverted, 4 corrected)

These five were reverted in `11c650a3`; they were **not** judged wrong, they were judged unproven
*here*. Each entry is what the code does, the evidence that decided it, and the one check that would
let it back in. The general rules they produced are ADR-231.

### 2.1 `O22-01` ABI splits — breaks the release script and can ship a stale APK

What it changed: `splits { abi { isEnable = true; isUniversalApk = true; ... } }` in
`app/build.gradle.kts`, so AGP stops writing `app-release-unsigned.apk` and writes
`app-arm64-v8a-…`, `app-armeabi-v7a-…`, `app-x86_64-…` and `app-universal-…` instead.

Evidence:

- `tools/build-release.ps1:265-268` hard-codes
  `app\build\outputs\apk\release\app-release-unsigned.apk` and then `Fail`s with
  *"If a signingConfig was added to app/build.gradle.kts, remove it"* — a diagnosis that blames the
  wrong thing, so whoever hits it at 2 a.m. chases a non-existent `signingConfig`.
- Worse than a clean failure: the check is `Test-Path`, not a freshness check. On this machine after
  the branch's split build there was still an `app-release-unsigned.apk` from **2026-10-20** beside
  the new per-ABI files, so lines 274-293 would have zipaligned and signed that October APK and
  shipped it as `Flash-2.1.0.apk`. A silent wrong artifact beats a crash for badness.
- The win does not reach a user today: `isUniversalApk = true` means the file the release flow
  packages is the universal APK, 54.63 MB — unchanged. The 17.55 MB arm64 figure only exists if the
  distribution switches to four files, which also touches `docs/release-build.md` and the `REL-*`
  checks.
- Packaging shape is an owner decision, not a build stream's (plan §8; ADR-231 rule 1).

Back in when: `PERF-A04` is answered and `build-release.ps1` + `docs/release-build.md` + `REL-*`
are updated in the same change; sizes are in EXP-80.

### 2.2 `O22-04` jlink module pruning — invisible to every check available here

What it changed: `desktop/build.gradle.kts` `modules("java.sql", "jdk.unsupported")`, dropping
`java.naming`, `java.management`, `java.instrument` and `jdk.crypto.cryptoki` from the packaged JRE.

Evidence:

- The consumers of those modules are **not in our source**: `java.lang.management`
  (JMX/`ManagementFactory`, reached by libraries), `javax.naming` (JNDI), `java.lang.instrument`
  (agents), the SunPKCS11 provider. A grep of our code cannot prove they are unreachable, and this
  is the opposite of the Material 2 case (§4), where a grep *is* proof.
- Where the tests run decides it: `:desktop:jvmTest` and `:desktop:compileKotlinJvm` execute on the
  builder's full JDK, not on the trimmed image. `jlink` does not verify reachability either, so
  `:desktop:packageMsi` succeeding proves only that jlink ran.
- The failure mode is a `ClassNotFoundException`/`NoClassDefFoundError` on first touch, in an
  installed app, at some unrelated moment.

Back in when: `PERF-A03` installs the MSI, opens a chat with history, Settings, a 1:1 call and the Hub
tab on the trimmed image. If it launches clean, the four modules return with the measurement.

### 2.3 `O22-06` narrowed WebRTC keep rules — reverses a comment that records a real crash

What it changed: `core/calling/consumer-rules.pro` from `-keep class org.webrtc.** { *; }` to
`@CalledByNative`-only members plus ten named classes.

Evidence (the replaced rule's own comment, which the change deleted):

> The bundled WebRTC … ships **NO consumer rules of its own** (verified:
> `io.github.webrtc-sdk:android` contains no `proguard.txt`). libwebrtc's JNI is bidirectional —
> native C++ resolves `org.webrtc` classes, methods and fields **by name** through
> `GetMethodID`/`GetFieldID` … R8 sees none of that … a consumer who enables minification gets a
> release-only crash (`NoSuchMethodError` / `UnsatisfiedLinkError` inside `PeerConnectionFactory`
> init) that never reproduces in debug. Hence the blunt keep — the reflection here is native, so
> there is no narrower rule that is honest.

- JNI also looks up names that are *not* `@CalledByNative`: `SdpObserver`, `MediaConstraints`,
  `IceCandidate`, `SessionDescription`, `VideoCapturer`, the encoder/decoder factories,
  `CameraEnumerator`, `DataChannel`, `Metrics`. Renaming any of them compiles, passes every unit
  test, and breaks calls only in a minified release build.
- It is `consumer-rules.pro`, i.e. the only `org.webrtc.**` keep in every app that consumes
  `:core:calling`.
- No test that runs here can see it: `:core:calling:testAndroidHostTest` runs on the JVM, never
  through R8.

Back in when: a release-signed build places a real call on a device. Unmeasured DEX bytes against
calls breaking in release is not a trade worth taking blind.

### 2.4 `O22-07` four `:ui:chat` dependencies — a recorded release decision, in a published module

What it changed: removed four `implementation` entries from `ui/chat/build.gradle.kts`
(`androidMain`).

Evidence (the block's own comment, restored by the revert):

> The next four have ZERO references anywhere in `ui/chat/src` — verified by grep, not assumed. They
> are kept, not dropped, for the reason `:ui:theme` kept its own two: removing a dependency from a
> published POM changes what every 1.2.0 consumer of `ui-chat` resolves at runtime, and that is a
> release decision …

- "Zero references" is true **and irrelevant**: they are `implementation`, so they never appeared on
  a compile classpath, but they were on the runtime classpath transitively via `runtimeElements`. A
  consumer that got `activity-compose`/`core-ktx`/`lifecycle-runtime-ktx` through us starts failing
  at runtime after an upgrade, and the failure is blamed on *their* app.
- `ui/chat/build.gradle.kts` has `maven-publish` and `publish = true`, artifactId `ui-chat`
  (`docs/publishing/` governs POM changes; plan §8 marks this owner-gated).
- Benefit: four classpath entries and a rounding error of build time.

Back in when: a deliberate library version bump drops all four together, with the changelog entry
that says so.

### 2.5 `O19-05` `-Xshare:auto` — a claimed win that does not exist

What it changed: one JVM argument in `desktop/build.gradle.kts`. The commit message also said it
*"closes OPT-O01-06 as duplicate"*.

Evidence:

- `-Xshare:auto` is the JVM **default** class-data-sharing mode: it shares only if an archive is
  present. No archive is generated (`-XX:ArchiveClassesAtExit`) or shipped, and
  `-XX:SharedArchiveFile` is unset, so the flag changes nothing measurable.
- That makes the *record*, not the line, the problem: merging it marks `OPT-O19-05` and `OPT-O01-06`
  done, so nobody ever builds the archive and a later audit "discovers" it closed. A false "done"
  costs more than a no-op.

Back in when: the real version — generate a `.jsa` at packaging time, ship it, point
`-XX:SharedArchiveFile` at it, and measure startup before/after (`O01-06`).

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
