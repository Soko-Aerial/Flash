# Optimization programme — parallel streams, worktrees and kickoff prompts

Written 2026-10-30 for the owner. Source of the work: `docs/audit/optimization-2026-10-23/` (211 findings;
ledger = `findings-ledger.md`, evidence = `O01..O28*.md`, compounding model = `compounding.md`, refused ideas =
`rejected.md`).

**Read `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md` §3 (machine rules) and §8 (log fragments) before starting a
stream.** This file adds the *split*; it does not replace that protocol, AGENTS.md §6–§13/§35/§36, or the audit's
own evidence.

---

## 1. What this split is for

Twelve **directory-disjoint** streams. Two streams in the same wave never edit the same file, so a
`git merge --no-ff` of one into `dev` cannot collide with the other — except in the canonical logs, which no
stream edits (§5.1). Where a file *must* be shared by design (`core/engine`, `core/messaging/*`, `ui/chat`,
`app/*`, `desktop/*`), §4.2 names one owning stream **per file** and the other streams work in new files or in a
carved-out subpackage.

A stream is sized for one agent over a few sessions: **batch 1** = quick wins (hours), **batch 2** = the real fix
(days), **batch 3** = the deep item (optional, or gated on a decision).

---

## 2. Facts this plan stands on (checked 2026-10-30, not taken from the audit)

| Fact | Value | Consequence |
|---|---|---|
| Machine | 8 logical CPUs, ~20 GB RAM, one Gradle build at a time (`Global\FlashGradleBuild` lock) | 2 agents comfortable, 3 workable, 4 mostly wait for the lock |
| `E:` free | ~69.9 GB | Worktrees and their `build/` output fit; ~3–4 GB per *built* tree |
| A worktree checkout | 38 MB of tracked files | Creating a worktree is cheap; **building** it is the cost |
| Main tree | `C:\Users\KaliOxygen\Downloads\Flash` — the **integrator's** tree, `dev` at `7dab9a57` when this plan was written (the commit that adds this file is its child) | Streams never edit it |
| Audit base commit | `4dcc7fad` (2026-10-23), **before** the radio data/voice and screen-share work now on `dev` | Every finding must be re-verified against today's code before it is worked |
| Verification style | Most findings are `STATIC-ARITHMETIC`, only ~20 are `MEASURED` | Measure before and after; a finding that no longer reproduces is closed as `NO LONGER REPRODUCES`, not "fixed" |
| Build gate | `cmd /c "tools\flash-gradle.cmd <tasks>"` (AGENTS §36) | Never `gradlew`; exit 75 = someone else is building |

**The audit is a claim, not evidence about this tree.** Several of its recommendations are refused by policy (§8), and
the code has moved since `4dcc7fad`. A stream's first act on a finding is to open the file and confirm the defect
still exists; if it does not, record that and move on.

---

## 3. The twelve streams

Counts are findings from the ledger: the twelve streams sum to **210** and the gated row makes **211**, which is what
the ledger lists. Two gated findings are counted inside their own stream (`O07-10` in T, `O08-03` in S) and are marked
in §9; `OPT-O22-03` is the separate gated row.

| # | Stream | Worktree / branch | Findings | Domain focus | Wave |
|---|---|---|---|---|---|
| A | **build** | `E:\FlashWork\opt-build` / `agent/opt-build` | 25 | Gradle, CI, APK/jpackage size, baseline profiles, test speed | **0 (alone, merges first)** |
| D | **data** | `E:\FlashWork\opt-data` / `agent/opt-data` | 23 | Room/SQLite indexes + PRAGMAs, DAO queries, chat-list state, `RealFlashChatRepository` | 1 |
| T | **transfer** | `E:\FlashWork\opt-transfer` / `agent/opt-transfer` | 28 | `core:transfer`, media pipeline (`ui:platform-shims`), storage I/O | 2 |
| U | **ui** | `E:\FlashWork\opt-ui` / `agent/opt-ui` | 28 | Compose stability, chat/conversation responsiveness, optimistic echo | 2 |
| N | **net** | `E:\FlashWork\opt-net` / `agent/opt-net` | 18 | WebSocket mesh, locks, discovery, presence | 2 — **merged** `8c331229` |
| G | **android** | `E:\FlashWork\opt-android` / `agent/opt-android` | 13 | Wake locks, FGS, background workers, Android start-up | **3 — next** |
| H | **hub** | `E:\FlashWork\opt-hub` / `agent/opt-hub` | 19 | `:server`, console, hub client (`core:enterprise`), hub SQLite | **3 — next** |
| E | **desktop** | `E:\FlashWork\opt-desktop` / `agent/opt-desktop` | 10 | Desktop shell, drop target, toasts, desktop start-up | 4 |
| K | **kernel** | `E:\FlashWork\opt-kernel` / `agent/opt-kernel` | 10 | `core:common` logging/probes/regex, `core:security` crypto | 4 |
| S | **swarm** | `E:\FlashWork\opt-swarm` / `agent/opt-swarm` | 14 | `core:swarm` scheduler, HAVE storms, piece storage | 5 |
| R | **radio** | `E:\FlashWork\opt-radio` / `agent/opt-radio` | 7 | `core:radio` read loop, framing, pendingAcks | 5 |
| C | **calls** | `E:\FlashWork\opt-calls` / `agent/opt-calls` | 15 | WebRTC capture/codec, PTT audio, call UI | 5 |
| — | gated | — | 1 | `OPT-O22-03` (BouncyCastle removal) needs an ADR (§8) | — |

Why these boundaries and not the audit's O01–O28: the audit is organised by *symptom*, the streams by *directory*.
Two audit domains can share a directory (O03 and O24 both live in `ui/chat`), and one audit domain can span five
directories (O25). Merging is decided by files, not by symptoms.

---

## 4. File ownership

### 4.1 The exclusive map

A stream may edit **only** the paths in its row. Anything else is a merge conflict waiting to happen.

| Stream | Owns (may edit) | Must not touch |
|---|---|---|
| **A build** | `gradle.properties`, `settings.gradle.kts`, root `build.gradle.kts`, `gradle/**` (incl. `libs.versions.toml`), `.github/**`, **every** module's `build.gradle.kts`, `buildSrc/**`, `app/proguard-rules.pro`, `tools/**`, new `benchmark/**` module, `ThirdPartyNotices*` | every `src/**` (except a new `benchmark/src/**`) |
| **D data** | `core/persistence/**` (schema step **22** is yours), `core/messaging/**` **except** `hub/**` and `radio/**`, `desktop/src/**/JvmFlashDatabaseOpener*` | `ui/**`, `:server`, `core/transfer`, build files |
| **T transfer** | `core/transfer/**`, `ui/platform-shims/**`, `core/engine/FlashPathSanitizer.kt` | `core/persistence`, `core/swarm`, `core/network`, `ui/chat`, build files |
| **U ui** | `ui/chat/**` except `radio/**` and `FlashSwarm*.kt`, `ui/theme/**`, `ui/hub/**`, `docs/ui/chat-screen.md` | `RealFlashChatRepository.kt`, `ui/callui`, `app/**`, `desktop/**` |
| **N net** | `core/network/**`, `core/discovery/**`, `core/engine/{FlashEngine,FlashBaseEngine,FlashConfig,FlashInboundRouter,MagicFrameRouter,PeerOrderedInbox,PlatformLock}.kt` | `core/messaging`, `ui/**`, hosts |
| **H hub** | `server/**`, `core/enterprise/**`, `core/hub-protocol/**`, `core/messaging/hub/**`, `core/engine/{HubFileBinding,HubFileRows,StreamingHubFileSource}.kt`, `docs/protocol-routes.txt` | client chat/UI, `core/persistence`, build files |
| **G android** | `app/**` except `app/**/radio/**`, `app/**/calling/**`, `app/**/ptt/**`; `app/src/**/ui/splash/**` | `core/**`, `ui/**`, build files |
| **E desktop** | `desktop/**` except `desktop/**/radio/**`, `radioaudio/**`, `radiospike/**` and `**/JvmFlashDatabaseOpener*` (D owns that one file) | `core/**`, `ui/**`, build files |
| **K kernel** | `core/common/**`, `core/security/**` | everything else |
| **S swarm** | `core/swarm/**`, `core/engine/{SwarmHostBinding,RoomSwarmStateStore,NetworkSwarmTransport,GroupFileSender,MessagingSwarmGroupContext}.kt`, `ui/chat/src/**/FlashSwarm*.kt` | `core/persistence`, `core:transfer`, `ui/chat` siblings |
| **R radio** | `core/radio/**`, `core/messaging/radio/**`, `ui/chat/src/**/radio/**`, `app/src/main/java/com/transfer/flash/radio/**`, `desktop/src/**/radio*/**` | `core/messaging` outside `radio/**`, `core/persistence`, `ui/chat` outside `radio/`, build files |
| **C calls** | `core/calling/**`, `core/ptt/**`, `ui/callui/**`, `third_party/webrtc-kmp/**`, `app/src/**/calling/**`, `app/src/**/ptt/**` | `ui/chat`, `core/network` |

### 4.2 The shared files, and the one owner for each

These are the only paths more than one stream could reasonably want. The owner edits; everyone else reads and, if a
change is truly needed there, **writes a request into their own log fragment** and lets the integrator route it.

| File | Who might want it | Owner | Rule |
|---|---|---|---|
| `gradle.properties`, `*.gradle.kts`, `gradle/**`, CI | everyone (a dependency, a flag, a test fork) | **A** | "No new dependency, no new module, no build flag" without A. A applies requests in its own commits. |
| `RealFlashChatRepository.kt` | D (queries, invalidation), U (optimistic echo), R (radio send path) | **D** | U implements optimistic echo as a **UI-local pending overlay** in `ui/chat` (no repository edit). R does not touch it. |
| `core/engine/PlatformLock.kt` | T (write lock), N (O14-01) | **N** | T reads it; if T needs a change, it goes in N's queue. |
| `core/messaging/hub/**`, `core/messaging/radio/**` | H, R | **H / R** | D stays out of both subpackages. |
| `ui/chat/**` | U, S (piece map), R (radio screens) | **U** for everything else | S owns `FlashSwarm*.kt`; R owns `ui/chat/**/radio/**`. |
| `ui/theme/**` (splash composable) | U (canvas allocations), G (splash timing) | **U** | G changes *when* the splash is shown (`FlashLaunchSplashProcess`), U changes *what it draws*. |
| `app/**` | G, R, C | **G** | R owns `app/**/radio/**`; C owns `app/**/calling/**` and `app/**/ptt/**`. |
| `desktop/**` | E, R | **E** | R owns `desktop/**/radio*/**`. |
| `logs/*`, `docs/testing/TEST-BACKLOG.md`, `docs/decisions.md`, `AGENTS.md` §29, `docs/security.md`, `docs/testing/PROBES.md`, `docs/android-platform-notes.md` | everyone | **integrator** | Streams write only `logs/streams/<stream>/*` (§5.1). |
| `docs/protocol.md`, `docs/protocol-routes.txt`, `RouteTableContractTest` | H; N and D for a wire/DB note | **H** | A wire-format change is gated (§8) and H regenerates the route list. |

### 4.3 The standing rules every stream prompt repeats

1. New logic goes in **new files**; an existing large file gets a minimal hook, never a reformat.
2. No new dependency, no new Gradle module, no wire-format change, no Room/schema change (D holds step 22),
   no new permission, no new probe namespace — all of those are **A's**, **H's** or the owner's, and are requested.
3. One build at a time, through `tools\flash-gradle.cmd`.
4. The phone is a single-owner resource: no stream touches it. Everything needing a device goes to the backlog.
5. Commit only to `agent/opt-<stream>`, small commits, never to `dev`/`main`, never force-push, never `git stash`
   in the main tree.

## 5. Keeping merges clean

### 5.1 Logs: fragments, never the canonical files

This is the rule that keeps the most work from being lost. `logs/progress.md` alone is ~16k lines and every agent
inserts at the top; two agents doing that means the last writer deletes the other's entry.

Each stream writes **only** into `logs/streams/<stream>/` in the same format the canonical file uses:

```text
logs/streams/<stream>/progress.md          (one "## YYYY-MM-DD — <title>" block per session)
logs/streams/<stream>/errors.md            (ERROR-NNN from the stream's reserved block)
logs/streams/<stream>/experiments.md       (EXP-NNN from the stream's reserved block, before/after numbers)
logs/streams/<stream>/backlog-additions.md (device/manual checks still owed, PERF-<x>NN)
logs/streams/<stream>/decisions-additions.md (ADR-NNN from the stream's reserved block)
logs/streams/<stream>/probes-additions.md  (only if the stream adds a probe)
```

The integrator folds them into the canonical files at each merge (that is the documented exception to AGENTS §6–§10
and it needs the owner's yes — protocol §9 question 3). A fragment that never gets folded is still the record.

### 5.2 Commits

Small, focused, conventional: `perf(data): index messages(conversationId, readAt) (OPT-O06-01)`. One finding (or one
tight group) per commit, the finding ID in the subject, so the integrator can cherry-pick if a batch must be split.

### 5.3 The conflict-free invariant

Before a stream starts batch 2 it runs:

```text
git -C E:\FlashWork\opt-<stream> fetch origin (or: git -C <main tree> rev-parse dev)
git -C E:\FlashWork\opt-<stream> merge dev        # merge, never rebase: verified history is not rewritten
```

A merge that touches a file the stream has also modified means the ownership map (§4) was breached — stop and ask
the integrator, do not resolve it locally.

---

## 6. Waves, merge order and the gate

### 6.1 The waves

A = build goes **first and alone** because it changes `gradle.properties` and the module build files that every
other tree compiles with. After A merges, every stream merges `dev` once and rebases nothing.

| Wave | Streams (start together) | Why together | Why not more |
|---|---|---|---|
| **0** | **A** | Touches only build files; every later build benefits | Two agents here would both edit `gradle.properties` |
| **1** | **D** + **T** | Disjoint dirs; D is the 1,700× query win, T is the transfer-freeze cascade | Both are heavy builds; the lock serialises them anyway |
| **2** | **U** + **N** | Disjoint dirs; U is the 11 FPS → 60 FPS cascade, N is the WhatsApp-style mesh stalls | Neither needs another stream merged; U's optimistic echo is UI-local (§8) |
| **3** | **G** + **H** | Android battery/background + hub server; no shared file | G's O10 items stay with N — G does not touch `core/discovery`; it only **calls** `FlashDiscovery.setScreenActive`, which N merged with no caller (`OPT-O10-01`) |
| **4** | **E** + **K** | Desktop host + shared kernel utilities; both small | K changes `FlashLog`, which every module calls — land it before S/R/C start, so their measurements run on the new logging cost |
| **5** | **S** + **R** + **C** | The device-gated trio (swarm, radio, calls); each needs phone checks anyway | All three owe device tests, so they interleave with owner test sessions |

**Recommended: two agents.** Three works if the owner can process three reports a week. Four mostly queues on the
build lock (protocol §5 says the same).

### 6.2 Merge order inside a wave

Any order, because the files are disjoint. The integrator merges with `--no-ff`, folds the log fragments, then runs
the gate. If two streams in a wave both change the *same* module's public API (rare by design), merge the one whose
finding is lower-risk first and let the other resolve against it.

### 6.3 The integration gate

Run it on the merged `dev` after every merge, through the lock. It is **two commands**, not one. The current
commands below preserve the coverage added by Waves 2 and 3 and add desktop tests (E) plus common/security Android
actuals (K). Force every verification run; record the command, tested commit, XML counts/skips and exit status.

```text
powershell.exe -NoProfile -File tools/flash-gradle.ps1 :core:common:jvmTest :core:security:jvmTest :core:persistence:jvmTest :core:messaging:jvmTest :core:transfer:jvmTest :core:swarm:jvmTest :core:network:jvmTest :core:discovery:jvmTest :ui:chat:jvmTest :ui:theme:jvmTest :ui:callui:jvmTest :core:enterprise:jvmTest :core:hub-protocol:jvmTest :server:test :desktop:jvmTest :desktop:compileKotlinJvm :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --continue --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

```text
powershell.exe -NoProfile -File tools/flash-gradle.ps1 :core:common:testAndroidHostTest :core:security:testAndroidHostTest :core:messaging:testAndroidHostTest :core:persistence:testAndroidHostTest :ui:chat:testAndroidHostTest :core:enterprise:testAndroidHostTest :app:testDebugUnitTest --continue --rerun-tasks --no-build-cache --no-configuration-cache --console=plain
```

The second command exists because a `jvmTest`-only gate **never runs the data stream's main safety net**:
`RealFlashChatRepositoryTest` lives in `core/messaging/src/androidHostTest` (688 tests with the rest of that source
set at the baseline), and `TransfersUiMapperTest` lives in `app/src/test`. Both were found missing from the first
draft on 2026-10-30. Wave 4 also found the common Android classifier test stale (`ERROR-951`); that suite and security
Android actuals/rotating logger coverage must not be omitted again. No security test is tagged out by K (`ADR-314`).
These task lists are coverage contracts, not a fixed test-count promise or a performance comparison with the narrower
historical baseline in §6.3.1.

H's `:server:test`/`:core:enterprise:jvmTest`, N's `:core:discovery:jvmTest`/`:core:hub-protocol:jvmTest`, G's
`:app:compileDebugAndroidTestKotlin`, and enterprise Android-host coverage are now explicit in the commands. Add
`:core:radio:jvmTest` when R merges and `:core:calling:jvmTest`/`:core:ptt:jvmTest` when C merges; those streams are
not implemented by this integration. After a Room or server-migration merge, run `FlashMigrationsChainTest` +
`FlashJvmMigrationsTest` (+ the server `MigratorTest`) **again on the merged result**: two individually green
migrations can still collide. E and K change no schema.

#### 6.3.1 Baseline — measured 2026-10-30 on `dev` at `54ff4737`

Both commands were run forced (`--rerun-tasks`), on Windows, after this plan's commit, before any stream started.

| Command | Result | Wall clock |
|---|---|---|
| 1 — ten `jvmTest` suites + `:desktop:compileKotlinJvm` + `:app:compileDebugKotlin` | **BUILD SUCCESSFUL**, 2,138 tests, 0 failed, 0 errors, **2 skipped** | 4 m 43 s |
| 2 — three `testAndroidHostTest` suites + `:app:testDebugUnitTest` | 1,526 tests, **2 failed** (both expected, below), 0 errors | 8 m 55 s |

| Suite | Tests | Failed |
|---|---|---|
| `:core:common:jvmTest` | 9 | 0 |
| `:core:security:jvmTest` | 170 | 0 (2 POSIX-only skips) |
| `:core:persistence:jvmTest` | 84 | 0 |
| `:core:messaging:jvmTest` | 382 | 0 |
| `:core:transfer:jvmTest` | 139 | 0 |
| `:core:swarm:jvmTest` | 151 | 0 |
| `:core:network:jvmTest` | 319 | 0 |
| `:ui:chat:jvmTest` | 723 | 0 |
| `:ui:theme:jvmTest` | 58 | 0 |
| `:ui:callui:jvmTest` | 103 | 0 |
| `:core:messaging:testAndroidHostTest` | 688 | 0 |
| `:core:persistence:testAndroidHostTest` | 53 | 0 |
| `:ui:chat:testAndroidHostTest` | 698 | 0 |
| `:app:testDebugUnitTest` | 87 | **2** |

**Expected failures — do not chase, and do not "fix" one without reading ERROR-106 (open):**

- `TransfersUiMapperTest.retryableComesFromTheModelPredicate` — bare `java.lang.AssertionError`.
- `TransfersUiMapperTest.aHubRowSaysSoAndOffersNoRetry` — `AssertionError: a hub failure is final; a dropped network
  keeps the row queued`.

Both are pre-existing on `dev` before any stream exists. A merge that changes the count is the signal; a stream that
believes it fixed them must say so in its report rather than silently inheriting them.

**2026-11-01 - both rows are now fixed, so gate 2 is expected to be fully green.** They asserted the pre-ADR-220
`FlashTransfer.canRetry` predicate, which open item C-07 changed on purpose; `432ceb52` updated the two tests and the KDoc
(ERROR-935, RESOLVED). A `TransfersUiMapperTest` failure after this date is a **new** defect, not the known pair. The
ERROR-106 desktop failure (`:desktop:jvmTest`) is a different test in a different module and is still open.

**Also learned from the baseline:**

- `SwarmInteropTest.testSwarmInteropThreeEndpointsOriginDropAndRejoin` **passed** under `--rerun-tasks`, so the flake
  seen in the 2026-10-30 review did not reproduce: treat it as rare, not fixed.
- The two skips are POSIX-only and expected on Windows:
  `FileHubSealingKeyStoreTest.theKeyFileIsOwnerOnlyWhereTheFileSystemHasPosixModes` and
  `KeyFileVaultTest.master key file is owner-only on a POSIX file system and holds 32 bytes`.

Any **new** failure after a merge belongs to the stream that merged: it gets an `ERROR-NNN` from that stream's reserved
block, and the integrator does not merge the next stream until the gate is back to the baseline above.

At the end of a session a stream runs `cmd /c "tools\flash-gradle.cmd --stop"` so an idle daemon does not hold
2–4 GB.

#### 6.3.2 After the Wave 0 merge (Stream A, 2026-10-30) — and the rule it taught the gate

Stream A changed build configuration only, so its merge is where the gate's own numbers must be re-established. All four
runs below are forced (`--rerun-tasks`), same machine, through the lock, same task set:

| Gate | `dev` before the merge | `agent/opt-build` as delivered | `dev` after review |
|---|---|---|---|
| 1 — ten `jvmTest` + `:desktop:compileKotlinJvm` + `:app:compileDebugKotlin` | **275 s**, 2,138 tests, 0 failed, 2 skips | **467 s**, same tests | **204 s** (`--no-build-cache`), same tests — **316 s** if the run also fills the cache |
| 2 — three host suites + `:app:testDebugUnitTest` | **535 s**, 1,526 tests, 2 expected failures | **429 s** | **490 s** at one fork per task, same 2 failures (two forks for host suites gives 376 s and is 114 s faster, but flaked ERROR-873, so it is not the shipped setting) |

Two rules follow, and they apply to every stream from now on:

1. **A before/after pair must be forced on both sides.** The stream reported gate 1 at 246 s because its own run was
   unforced (almost every compile up-to-date) while the baseline it compared against was forced. Recorded as ERROR-872.
   Paste the command line with every number.
2. **`--rerun-tasks` pays for build-cache *stores* without getting lookups back.** A forced run has nothing to reuse, so
   the cache only writes — about **112 s** across gate 1's 153 tasks. For an evidence run whose only purpose is wall
   clock, add `--no-build-cache`; for the normal loop leave the cache on, which is where it pays.

Net after review: gate 1 −26 %, gate 2 −8 % against the forced baseline (about 694 s against 810 s for the pair). Gate 2's
larger win needs per-task forks, which wait for `PERF-A07` (the two sleep-based tests of ERROR-873).

Stream A's outcome: 21 of 25 findings merged; `O22-01` (ABI splits), `O22-04` (jlink pruning), `O22-06` (WebRTC keep rules)
and `O22-07` (`:ui:chat` dependencies) reverted, `O19-05` (AppCDS) reverted as not delivered, and the fork/daemon/profile
settings corrected. Authority: **ADR-231**, the per-finding table in `logs/streams/build/INTEGRATOR-REVIEW.md`.

---

## 7. Reservations (so two streams never take the same number)

| Resource | Used today | A | D | T | U | N | G | H | E | K | S | R | C |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ADR | 229 | 230–239 | 240–249 | 250–259 | 260–269 | 270–279 | 280–289 | 290–299 | 300–309 | 310–319 | 320–329 | 330–339 | 340–349 |
| ERROR | 870 | 871–880 | 881–890 | 891–900 | 901–910 | 911–920 | 921–930 | 931–940 | 941–950 | 951–960 | 961–970 | 971–980 | 981–990 |
| EXP | 79 | 80–84 | 85–89 | 90–94 | 95–99 | 100–104 | 105–109 | 110–114 | 115–119 | 120–124 | 125–129 | 130–134 | 135–139 |
| Room schema | 21 | – | **22** | – | – | – | – | – | – | – | – | – | – |
| Test prefix | – | `PERF-A` | `PERF-D` | `PERF-T` | `PERF-U` | `PERF-N` | `PERF-G` | `PERF-H` | `PERF-E` | `PERF-K` | `PERF-S` | `PERF-R` | `PERF-C` |
| Probe area | – | – | existing `db.*` | existing `transfer.*` | – | existing net names | existing app names | existing `hub.*` | existing desktop names | existing `log.*` | existing `swarm.*` | existing `radio.*` | existing call names |

Integrator takes 991+ (ERROR), 350+ (ADR), 140+ (EXP). Only D may bump the Room schema; a stream that believes it
needs a schema change stops and asks. Server migrations are H's alone, and it records the component version it took
in its fragment.

## 8. Findings that must NOT be implemented as written

The audit lists 211 defects, not 211 things to do. These are gated, obsolete, or not code at all. A stream that
reaches one of these writes it into its fragment as `BLOCKED — needs owner decision` and moves to the next finding.

> **All of the "owner" rows below were answered on 2026-10-30 — see §12 and ADR-232.** Only the rows marked
> `device session` and `owner (ops)` still block: those need hardware or a deployment, not a decision.

| Finding | Why it is gated | Who decides |
|---|---|---|
| `OPT-O01-01` "remove the 3.7 s launch splash" | **ADR-080 / UI-056** makes the splash play to the end on purpose (about 3.45 s, one gate per process). Only the *wait for the engine* may be trimmed; deleting the animation reverses an owner decision. | owner |
| `OPT-O06-03` FTS5 for search | **ADR-218** deliberately defers FTS5 (LIKE + file names today). D fixes the index/scan findings instead. | owner |
| `OPT-O07-10` drop application-level AES over TLS 1.3 | **REJ-01** refuses weakening transport security; removing the sealed-chunk layer is a security architecture change, not a perf patch. Revisit only with a threat model. | owner + ADR |
| `OPT-O09-04` / `OPT-O09-07` Base64 inflation + text-line framing | Wire format. Both hosts, versioned, with a compatibility window. N may still fix the *allocation* half (`O09-05`) without changing bytes. | owner + ADR |
| `OPT-O06-10` lower SQLCipher PBKDF2 iterations | Weakens the database key derivation. Security decision with a documented trade-off. | owner + `docs/security.md` |
| `OPT-O13-06` drop the `connectedDevice` FGS when no peer is connected | Play policy vs **ADR-041** background-transfer support, and Transsion Hiber freezes the process. Changes when a user can receive. | owner |
| `OPT-O22-03` remove BouncyCastle | It generates the desktop TLS certificate; removal touches handshake code on both hosts and needs an ADR + a device test. | owner |
| `OPT-O04-01` page the conversation history | Behaviour/feature change (what the user can scroll back to), not a pure optimisation. | owner |
| `OPT-O08-03` steal-back / fairness in swarm serve slots | Changes who gets pieces; needs the SW fairness invariant re-read and a multi-device test. | owner |
| `OPT-O02-07` / `OPT-O17-01` server heap cliff | Deployment profile (JVM flags, connection budget), not source. H documents the numbers the owner must deploy. | owner (ops) |
| `OPT-O11-01`, `OPT-O11-03`, `OPT-O12-06` codec changes | Need real devices, and O11 changes the vendored `webrtc-kmp` fork that is republished (ADR-103). | device session |
| `OPT-O26-06` "voice clips over the 1200 baud data channel" | **Obsolete.** ADR-210/ADR-225 put audio on the vendor SBC Bluetooth channel; REJ-06 rejects packet-radio voice on Shannon grounds. Mark it and do not build it. | already decided |
| `OPT-O24-01` optimistic echo | Allowed, **only** as a UI-local pending overlay: the outbox row must still be written before the bubble shows "sent", or a crash loses the message (AGENTS §18). | U implements with that constraint |
| `OPT-O05-01`/`OPT-O07-01` "one pass for the whole-file hash" | **Wire shape.** `FILE_START` carries the digest and is sent before any chunk, so the value must exist up front; a real single pass needs a new frame (digest after the fact) or buffering. T delivered neither — see `logs/streams/transfer/INTEGRATOR-REVIEW.md` §3.1. Revisit with an N/owner answer. | owner + wire ADR |

**Learned in the D/T review (2026-10-31), apply to every later stream:** a feature that needs a *host* to call
it is not done when the module compiles. D's `recordProgress` and summary projections and T's `SeekableChunkSource`,
`.part` rename and `isFat32Volume` were all shipped with no caller and looked finished in the diff; the review
found them by grepping for call sites. **Grep for a caller before writing "implemented" in a fragment.**

**Re-verified on 2026-10-30 (still true unless marked):** `org.gradle.parallel` and `org.gradle.caching` are
absent/comment in `gradle.properties`; `:server` has no `maxParallelForks`. Changed since: `app/build.gradle.kts` has ABI splits since
2026-10-31 (ADR-243, `PERF-A04`), so a packaging check starts from `output-metadata.json`, not `app-release.apk`. **Not yet
re-verified:** every finding D/T/U/N/G/H/E/K/S/R/C touch - that is each stream's first step.
---

## 9. The findings per stream, in batches

Effort/risk are the audit's own (S/M/L, Low/Medium). "Doc" is the evidence file in
`docs/audit/optimization-2026-10-23/`.

### A — build (25)

**Batch 1 — configuration only, no source (each under an hour):**
`O21-01` uncomment `org.gradle.parallel` · `O21-02` `org.gradle.caching=true` · `O21-03` daemon `-Xmx4096m` + modern
GC · `O21-04` `:server:test` `maxParallelForks=4` · `O21-05` CI runs `:server:test` · `O21-06` CI JDK 21 +
`--no-daemon` audit · `O21-07` pin the BouncyCastle version range · `O21-08` notices/AboutLibraries only on release ·
`O21-10` drop the duplicate Room KSP in `:app` · `O22-01` ABI splits (arm64-v8a, armeabi-v7a, x86_64) · `O22-04` dead
JRE modules · `O22-05` drop Material 2 from the desktop umbrella · `O22-06` narrow the `org.webrtc.**` keep rule ·
`O22-07` remove parked `:ui:chat` androidMain dependencies · `O20-05` R8 rules that strip logs in release.

**Batch 2 — one-day items:**
`O21-09` split `buildSrc` · `O19-04` desktop JVM flags (`MaxGCPauseMillis`, `Xss512k`, `ExitOnOutOfMemoryError`) ·
`O19-05` AppCDS archive in jpackage · `O01-06` desktop AppCDS/JVM flags — **the same defect as O19-04/05, close it as a
duplicate** · `O22-02` strip the 24 foreign native SQLite binaries from the installer ·
`O01-03` + `O28-01` a `:benchmark` module with Macrobenchmark and baseline/startup profiles.

**Batch 3 — measurement:**
`O27-01` forks monorepo-wide · `O27-02` virtual time (config + the shared test harness only; per-module test rewrites
belong to the owning stream) · `O27-05` the flake infrastructure.

**Gated:** `O22-03` (remove BouncyCastle) — needs an ADR and a TLS device test (§8). Record it, do not do it.

**Verify:** before/after `:server:test` wall clock, a clean `:app:assembleRelease` APK size per ABI, `:desktop:package*`
installer size, and one cold `:core:messaging:compileKotlinJvm`. Evidence runs must say *executed*, not
`FROM-CACHE` — so for the numbers use `--rerun-tasks` on the single task.

**Status 2026-10-30 — MERGED, reviewed (ADR-231).** 21 of 25 findings landed; see
`logs/streams/build/INTEGRATOR-REVIEW.md` for the per-finding table and §6.3.2 for the gate numbers.

- Landed: `O21-01`…`O21-10`, `O22-02`, `O22-05`, `O20-05`, `O19-04`, `O01-03`/`O28-01` (module only), `O27-01/02/05`. `O22-01` (built 2026-10-31 by the review: ABI splits + the release script reads `output-metadata.json` and fails on a stale or missing output).
- **Not landed — owner decisions, not stream work:** `O22-04` jlink pruning (unverifiable without an installer
  smoke test → PERF-A03), `O22-06` narrowed WebRTC keeps (reverses a documented release-only crash fix), `O22-07`
  `:ui:chat` dependency removal (published POM; the file says why they stay).
- **Not landed — not delivered:** `O19-05` — `-Xshare:auto` is the JVM default and no AppCDS archive is shipped, so
  `O01-06` is **not** closed. Still open.
- **Corrected after measurement:** 4 forks per `Test` task → **1 for every task** (two forks for the Robolectric host
  suites measured faster but flaked `RealFlashChatRepositoryTest` under load, ERROR-873); `SoftRefLRUPolicyMSPerMB=50`
  and `forkEvery=100` removed; hand-written `app/src/main/baseline-prof.txt` deleted (not generated; one rule cannot
  match) — `O01-03`/`O28-01` stay open until PERF-A02 runs on a device. `:benchmark` cannot run yet: `:app` has no
  `benchmark` build type.

### D — data (23)

> **Merged 2026-10-31** (`3b28e5be`, fixes `f3154de0`). Review: `logs/streams/data/INTEGRATOR-REVIEW.md`.
> Delivered: `O06-01`, `O06-05`, `O04-02`, `O25-04`, `O02-06`, `O06-04`, `O06-06`, `O06-02`, `O04-03`,
> `O04-04`, `O06-09`, `O04-01`, `O14-04`, `O14-05` (with the `hashCode` contract fix), `O27-05`, `O25-09`, `O28-05`,
> `O23-05`, `O06-07`/`O07-03` (caller wired by the integrator). Not delivered: `O06-08` (projections now
> real, no consumer — `PERF-D03`) and `O27-04` (never attempted — `PERF-D04`). Device checks `PERF-D01`/`PERF-D02`.

**First step (ERROR-873, added by the integrator after the Wave 0 merge):** `RealFlashChatRepositoryTest` has two cases that
synchronise with `delay(20)`/`delay(100)` and read an empty list under CPU load — `pins are stored per conversation, newest
first…` (line ~522) and `delete for everyone sends direct action only for the local author` (line ~3436). They pass at one
fork, but they are the reason the monorepo cannot use per-task forks. D owns this file: replacing the sleeps with an explicit
wait (poll `conversationState.value` with a timeout, or collect the flow) belongs in D's first commit, before anyone trusts a
gate at more than one fork.

**Batch 1 (the 1,700× win + the indexes):**
`O06-01` index `messages(conversationId, readAt)` and rewrite `observeUnreadCounts` (697 ms → 0.41 ms) · `O06-05`
five missing indexes (`outbox.nextAttemptAt`, `hub_file.createdAtMs`, `conversations(pinned, sortOrder)`, …) ·
`O04-02` the N+1 synchronous queries in the chat-list combine · `O25-04` cache the regex in `decodeReactorIds` ·
`O02-06` + `O06-04` the **desktop** opener `JvmFlashDatabaseOpener` (non-WAL / `synchronous=FULL`, no page-cache
bound) · `O23-05` + `O06-06` `auto_vacuum` + `journal_size_limit` (this is the one Room step **22**).

**Batch 2:**
`O06-02` `observeLatestPreviews` (573 ms, re-run on every write) · `O04-03` receipt/reaction invalidation storm ·
`O04-04` search/unread scans · `O06-07` one transaction for `markChunksDone` + `setBytesDone` · `O06-09` the
`searchMembers` index that a leading wildcard defeats · `O02-02` stop retaining closed conversation state ·
`O14-04` the eager `hubDropped` `stateIn` → `WhileSubscribed(5_000)` (the audit's ledger keeps `sendWithTimeout`
cancellation under the same number; that half stays open — a blocking socket write cannot be cancelled without
truncating a frame) · `O14-05` the structural-equality iteration on every `conversationState` emission ·
`O07-03` 5,120 transactions per 10 GB (with T: T owns the caller, D owns the DAO) · `O28-05` slow-query callbacks.

**Batch 3:**
`O06-08` BLOB inlining (`SwarmContentEntity` 131 KB manifest; coordinate with S) · `O25-09` collection sizing ·
`O27-04` in-memory SQLite for the client tests. **Decided by ADR-232:** `O06-03` **stays on LIKE** (FTS5 only if a real device
shows >~300 ms over ~100k messages); `O06-10` takes the **`mmap_size` half only** — the PBKDF2 iteration count is not lowered;
`O04-01` is **approved** — page the history, and the pager must resolve `FlashBackStackState.targetMessageId` so search
deep-links still land on the right message.

**Verify:** the audit's own `bench/db_benchmark.py` before/after at 100k messages, plus `:core:persistence:jvmTest`
and the two migration tests. The index change is a schema step: bump `FlashSchemaSteps`, add the `22.json`, and prove
the chain.

### T — transfer (28)

> **Merged 2026-10-31**, fixes in the commit after (`5ef86ca5`). Review:
> `logs/streams/transfer/INTEGRATOR-REVIEW.md`. Delivered: `O07-07`, `O07-09`, `O02-10`, `O25-01`, `O07-05`,
> `O23-04`, `O05-04`/`O05-05`/`O05-08`/`O05-09`, `O02-08`/`O02-09`, `O02-01`, `O07-04`, `O07-06`, `O07-08`,
> `O28-02`, `O25-07`, `O05-02`/`O05-03`/`O05-10`, `O07-10` (blocked). Merged but **inert without a host caller**
> (recorded, not reverted): `O23-02` (`PERF-T05`), `O07-02` (`PERF-T06`), `O23-03`'s FAT32 half (`PERF-T07`).
> Not delivered: `O05-01`/`O07-01` (§8), `O23-06` (`PERF-T08`). A resumed send was broken on the branch — fixed
> before the gate, and fixing it exposed an endgame that re-sent merely queued frames (ERROR-894, also fixed);
> `PERF-T09`/`PERF-T10` cover the settle and the ACK grace.

**Batch 1 (cheap, high ROI):**
`O07-07` the 1 MB chunk plan silently clamped to 256 KB · `O07-09` the boxed `ArrayList<Int>` done-set reconcile
(`ResumeBitVector`) · `O25-01` the O(N·M) `pendingIndexes` init · `O07-05` the per-chunk `Sha256.digest` provider
lookup · `O23-04` purge orphaned hub binaries/partials/voice/camera files · `O23-03` pre-allocate + the FAT32 4 GB
check · `O23-06` buffered `saveToGallery` (250,000 syscalls per multi-GB copy) · `O05-04` EXIF header without
re-reading the file · `O05-05` rotate without a second full pixel matrix · `O05-08` trim the camera/voice cache ·
`O05-09` bounded decode retries · `O02-08` the `JvmImageDecoder` 32-bitmap (~64 MB) cache with no trim hook ·
`O02-09` the `Activity` context held by `AndroidImageDecoder` (both files are in `ui/platform-shims`, which T owns).

**Batch 2 (the cascade):**
`O05-01` + `O07-01` one streaming pass for the whole-file hash and the chunk hashes (no `hashOnly` pre-pass; 5.8 GB
→ 1.9 GB of reads for a 2 GB file) · `O02-01` route inbound frames through `ChunkBufferPool` · `O23-02` a seekable source so resume skips to the missing
offsets instead of re-reading the whole file · `O07-08` coalesce the 100 Hz progress flow (with U reading
it) · `O07-06` in-flight chunk retransmission instead of a 15 s timeout + full restart · `O07-02` `.part` + atomic
rename · `O07-04` take the hash/disk work off the lock-bound reader thread · `O28-02` flight telemetry so the
before/after is measurable.

**Batch 3:**
`O02-10` the resume bit vector · `O25-07` `ChunkFrame.serialize` pooling + redundant sort · `O05-02`/`O05-03`/`O05-10`
desktop AAC/JCodec work off the UI thread · `O07-10` **gated** (§8).

**Verify:** `:core:transfer:jvmTest`, `bench/TransferLoopbackBenchmark.kt` before/after, and EXP numbers in the
fragment. The hash single-pass change must keep the whole-file verification (REJ-02 forbids dropping it).

### U — ui (28)

**Batch 1 (each S, each provable by inspection):**
`O03-03` hoist `draft` out of the root `ConversationScreen` scope (every keystroke re-composes the screen) ·
`O03-04` a Compose compiler stability configuration file for the domain models · `O03-01`/`O03-11`/`O03-12` read
animation values in the draw phase and guard `reduceMotion` · `O03-05` hoist the per-card lambdas ·
`O03-06` stop the O(N) set equality check on every `LazyColumn` recomposition · `O03-08` the context-menu list
allocation · `O03-13` hoist the `BorderStroke` · `O03-09` memoise the trusted-peer regex mapping ·
`O03-02` the reaction chip's composition-phase state + puff reads (reading animation state in composition instead of
the draw phase, so every chip frame re-composes) ·
`O04-05` per-row `SwipeToDismissBox` state · `O04-06` day separators out of the inline lambda (slot reuse) ·
`O24-05` the settings toggle's synchronous DataStore write · `O24-06` no infinite transition while a sheet opens ·
`O24-07` reserve the image ratio before decode.

**Batch 2:**
`O03-14` the splash canvas allocations (in `ui/theme`) · `O03-15` is **S's** ·
`O03-16` the double text measurement in the quoted reply · `O03-17`/`O03-18` are `BoxWithConstraints` in the nav bar
(U) and the call dock (**C**) · `O03-07` the re-created `GroupFileSender` remember key ·
`O24-01` optimistic local echo as a **UI-local overlay** (§8) · `O24-02` keep the staging tray visible with a spinner
until `sendFile` returns · `O24-03` the IME `animateContentSize` jitter · `O24-04` auto-scroll when the keyboard
opens · `O05-06`/`O05-07` the 10 Hz voice-note card and its waveform parsing · `O25-05` memoise the sorts in the
nearby and video-grid layouts · `O04-07` the unconstrained `animateScrollToItem`.

**Batch 3:** `O24-08` is E's (desktop reduce-motion).

**Verify:** `:ui:chat:jvmTest`, the Compose compiler metrics for the touched composables (stability report), and a
device check (`PERF-U01`) for anything that changes what a user sees. Do not claim a frame-rate number without a
Macrobenchmark run (A's `:benchmark` module).

### N — net (18)

**Batch 1:**
`O09-06` + `O14-02` the `Thread.sleep(25)` busy-poll in `WsSession` backpressure · `O09-05` the
`ByteArrayPool` copy-and-discard in `WebSocketCodec` · `O09-08` the unconditional `UUID.randomUUID()` for a flow
nobody collects · (`O14-04`/`O14-05` are D's) · `O10-03` the 253 Binder IPC cycles per subnet sweep ·
`O10-06` the duplicated connectivity triggers.

**Batch 2:**
`O09-01` `BoundedSendQueue` bypassed by unbuffered blocking writes under the global write lock · `O14-01` the
monitor lock held across socket I/O · `O14-03` the global `drainMutex` serialising every peer ·
`O09-03` double JSON tree parsing in `RelayFrameCodec` · `O10-04` the NSD resolve queue head-of-line block ·
`O10-05` the unsynchronised polling sweepers · `O10-07` directed broadcast basic-rate penalty · `O28-03` call
telemetry is C's.

**Batch 3:**
`O09-02` TLS session resumption (measure both hosts; it changes handshake behaviour) · `O10-01` screen-off
discovery downscale (**needs G's screen-state signal** — agree on an interface; N owns the discovery side) ·
`O10-02` the permanent `MulticastLock` (N owns `core:discovery`; G calls the new lease API if one is needed).

**Decided by ADR-232:** `O09-04` and `O09-07` are **not taken** — no wire-format change now. N implements only the allocation
half (`O09-05`, `O09-06`, `O14-02`), which changes no bytes. A wire v3 with a negotiated fallback is its own project.

**Verify:** `:core:network:jvmTest`, `:core:discovery` tests, and for the lock change a two-device transfer check
(`PERF-N01`).

### G — android (13)

**Batch 1 (battery, the owner-visible one):**
`O13-01` the perpetual partial wake lock → bounded, ref-counted lease · `O13-02` the keepalive worker that spins the
whole engine for an empty outbox · `O13-03` `Verifying` missing from the Android 15 timeout plan ·
`O13-08` the `delay(300)` poll at service start · `O01-02` synchronous DataStore/SQLite on the main thread ·
`O01-07` the redundant `MediaCodecList` enumeration · `O01-08` sequential PRAGMAs at open.

**Batch 2:**
`O13-05` `ACTION_SCREEN_OFF` handling (polling and tickers in a pocket) · `O13-04` the Transsion Tier 3 silent
`AudioTrack` · `O13-07` audio-focus backoff in `TranssionHiberKeepAlive` · `O01-04` the eager engine assembly +
subnet sweep at launch. (`O02-09` — the `Activity` context held by `AndroidImageDecoder` — is T's, because the file is
in `ui/platform-shims`.)

**Batch 3 / decided by ADR-232:** `O13-06` is **approved** — the `connectedDevice` service stops when there is no peer, no
transfer and no hub connection, and one opt-in setting ("Keep listening in the background") restores today's behaviour with
the honest sentence. `O01-01` is **not taken** — ADR-080 stands and the splash is never dismissed; G may change what happens
*during* the splash, never whether the animation finishes.

**Verify:** `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`, and a device session for the wake-lock
and Doze behaviour (`PERF-G01`, `PERF-G02`) — a battery claim without `batterystats` is not a result.

### H — hub (19)

**Batch 1:**
`O23-01` the per-chunk `fsync` + open/close in `HubFileDisk` (1,600 per 50 MB) · `O25-10` the N+1 revocation cascade
(~2.1 s of exclusive write lock) · `O18-01` gzip/deflate static assets · `O18-02` the 35-request ES module waterfall ·
`O17-04` `ExitOnOutOfMemoryError` in the deploy scripts.

**Batch 2:**
`O17-02` `writeChunkFile` inside `db.withTransaction` · `O25-02` the O(D·M) directory sync + repeated JSON ·
`O25-06` the linear scan over `memberDeviceIds` · `O16-01` the stop-and-wait hub file chunk pipeline ·
`O16-03` the 30 s HTTP presence poll while the relay is up · `O02-03` the 35–45 MB org snapshot spike ·
`O02-07`/`O17-01` the heap cliff (**document only**, §8) · `O27-03` real sockets/Netty in the test suite.

**Batch 3:** `O16-02` multi-page sync (overlaps `O02-03`) · `O16-04` batched mailbox acks · `O18-03` console
preloading · `O25-09` collection sizing · `O17-03` the group fan-out serialisation.

**Verify:** `:server:test` (compare against A's new parallel baseline, not the old 238 s), the hub file e2e test, and
the admin console tests. A migration change also re-runs the server `MigratorTest` on the merged result.

### E — desktop (10)

**Reviewed 2026-11-01**, delivered `00a7a51f`, repaired `b2b61b92`, actual base `baf77753`. Ten audit IDs retained
in nine action groups; six caller/lifetime defects repaired (ERROR-943..948). Current dispositions, not the original
numeric hypotheses: window-owned DropTarget with updated whole sender; notification-relevant distinct snapshots
(not timed debounce); class-only derived invalidation (full geometry still observes width); Settings-local collection
with application-root radio; background warming and nullable readyPairing; lazy common/large icons (large getter
uncalled); guarded GHOST/hidden workload START/FINISH/mode changes; sole-worker output/flush/close; dispatcher
operation bounds (not total threads); documented observable Windows motion. `O19-04`/`O19-05` remain A's.
Hub presence polling unchanged. No immediate frame, zero-root-recomposition, macOS motion, power or memory headline.

**Verify:** forced desktop JVM/compile targeted PASS, 259 tests. Both expanded integration gates after merge;
PERF-E01..E08 owed. EXP-115..119 NOT MEASURED; ADR-300..309 and desktop INTEGRATOR-REVIEW.md are authority.

### K — kernel (10)

**Reviewed 2026-11-01**, delivered `6d9c9227`, repaired `22a53994`, actual base `baf77753`. Ten audit IDs reviewed,
four defects repaired (ERROR-953..956). Current dispositions: INFO-default level gate/inline lambdas with existing eager
APIs; flat 0..8-field/direct-format probes and stable header snapshot; ordinary Android writer formatting with synchronized
flush/export; gzip API only (no app caller); immutable copied-SPKI public-key LRU; shared KeyFileVault RNG (Android
PassphraseKeeper unchanged/outside scope); **Provider selection, not initialized keyed Cipher/Mac caches**; non-keyed
security SHA reuse/independent streaming (transfer helper/wrappers unchanged); NFKD/ASCII/512-entry LRU.
No broad caller migration, zero-total-allocation/FPS/50%-verification headline or portable zeroization guarantee.
**O27-06 retains every security test**; common/security Android actual coverage is explicit in gates, none tagged out.

**Merge order for this wave: K then E**, allowed by §6.2: K's delivered `cbf7b8df` repairs ERROR-951's stale classifier
expectation in the newly covered common Android-host suite. Do not omit coverage or split the correction to hide it.
**Verify:** forced common/security JVM + Android-host targeted PASS, 602 tests (2 documented POSIX-only skips).
Both expanded gates after each merge; PERF-K01..K05 owed. EXP-120..124 WITHDRAWN/NOT MEASURED;
ADR-310..314/350 and kernel INTEGRATOR-REVIEW.md are authority. Public logging API stays source-compatible.

### S — swarm (14)

`O08-01` the O(P·N·W) scheduler → bitset + rarest-first · `O25-08` the discarded O(P·N) holder computation in
`updateWaitReason` · `O08-07` the O(N²) HAVE storm · `O08-02` the redundant full-piece re-hash on serve ·
`O08-04` per-piece fsync + DAO amplification · `O08-05` the 4 Hz idle ticker · `O08-06` thermal governor integration ·
`O08-08` tombstone retention + summary broadcasts · `O02-04` `Channel.UNLIMITED` queues · `O02-05` permanent
tombstones in a `LinkedHashMap` · `O23-07` heap-wrapped NIO buffers in the piece storage · `O03-15` the piece-map
canvas allocations (S owns `FlashSwarm*.kt`) · `O28-04` scheduler telemetry.

**Decided by ADR-232:** `O08-03` is **approved** — age-weighted / deficit round-robin serve slots, no wire change, proved with
the multi-device run S owes anyway. `O06-08` (the BLOB entity) is D's — coordinate, do not edit persistence.

**Verify:** `:core:swarm:jvmTest`, `SwarmInteropTest` (a rare flake that passed in the 2026-10-30 baseline — record it
rather than "fixing" it), and a three-device swarm check (`PERF-S01`).

### R — radio (7)

`O26-01` the 250 ms semi-blocking read loop in `KissTncDriver` (14,400 wake-ups/hour) · `O26-04` framing/AAD/serial
buffer churn · `O26-03` the unbounded `pendingAcks` and `UNLIMITED` channels · `O26-05` the up-to-4 s
`Thread.sleep()` in Android connection probing · `O26-07` JCA provider lookups + SPKI re-parsing in
`JdkRadioCrypto`/`JdkRadioSignatureScheme` · `O26-02` channel occupancy / goodput (measure first: 1200 baud numbers
are easy to get wrong) · `O26-06` **obsolete** (§8).

**Verify:** `:core:radio:jvmTest`, the golden-frame test (the wire must not change), and `RADCHAT-*`/`RADVOICE-*`
device checks. Anything touching `RadioSession` re-runs the anti-replay tests (ERROR-870 lives there).

### C — calls (15)

`O11-01` 1080p30 software VP8 on desktop (**device-gated**) · `O11-02` the camera capturer running when the camera is
off · `O11-03` the unconditional VP8 SDP that strips MediaCodec (**device-gated, fork**) · `O11-04` the O(N) stats
allocation in mesh calls · `O11-05` the dead SDP bitrate tuning · `O11-06` occluded remote frames still rasterising ·
`O11-07` uncapped screen-share encoding to 4 watchers · `O12-01` the 50 Hz playout StateFlow ·
`O12-02` ~550 allocs/sec in the audio pipeline · `O12-03` the 1 s `TargetDataLine` cold open (pre-warm) ·
`O12-04` the non-adaptive 120 ms jitter buffer · `O12-05` Android `AudioTrack` performance mode + attributes ·
`O12-06` PCM16 airtime (**gated**, both hosts) · `O03-18` the call-dock `BoxWithConstraints` · `O28-03` call
telemetry.

**Verify:** `:core:calling:jvmTest`, `:core:ptt:jvmTest`, `:ui:callui:jvmTest`, `:desktop:compileKotlinJvm`, and a
two-device call check. A change to the vendored `webrtc-kmp` fork must be recorded in
`third_party/webrtc-kmp/MODIFICATIONS.md` and needs the fork republished (ADR-103) before the app can use it.

## 10. Tracking

The integrator keeps this table current; a stream's own fragment holds its detail.

| Stream | Worktree | Batch 1 | Batch 2 | Batch 3 | Merged (`git log --oneline -1`) | Gate | Device checks owed |
|---|---|---|---|---|---|---|---|
| A build | `opt-build` | ☑ | ☑ | ☑ | `0cc10902` (merge `4da12c9c`) | **PASS** — gate 1 204 s (2,138 tests), gate 2 490 s (1,526 tests, 2 known); 21 of 25 findings kept, 5 reverted (ADR-231, see §2 of `logs/streams/build/INTEGRATOR-REVIEW.md`) | PERF-A01..A07 (A05/A06 already PASS; **A04 built by the review 2026-10-31 - four APKs, universal 52.15 MiB vs arm64 16.79 MiB, stale-output guard proven, EXP-093 - so A01 is runnable**) |
| D data | `opt-data` | ☑ | ◐ | ◐ | `f3154de0` (merge `3b28e5be`) | forced gate 1 **PASS** on the merged tree (432 tasks, 5 m 34 s, 2 416 tests, 0 failures; two earlier attempts failed on the test fakes the projection change invalidated, updated in the same follow-up); 28-file diff reviewed, gate 2 forced green except the two known ERROR-106 `:app` rows; `O06-08`/`O27-04` not delivered, 6 defects fixed (`logs/streams/data/INTEGRATOR-REVIEW.md`) | PERF-D01 (paging on a long thread), PERF-D02 (search deep link), PERF-D03 (summary consumer), PERF-D04 (in-memory SQLite) |
| T transfer | `opt-transfer` | ☑ | ◐ | ☑ | `01a1d4d9` (merge) | forced gate 1 **PASS** on the merged tree (2 416 tests) and gate 2 forced green except the two expected ERROR-106 `:app` rows (1 673 host tests, `:core:transfer` re-run 375 green after the last edit); a resumed send was misaligned on the branch (fixed, ERROR-893) and the endgame re-sent merely queued frames (fixed, ERROR-894); `O05-01`/`O07-01` not delivered, `O23-02`/`O07-02`/`O23-03` inert without a host caller | PERF-T01 (send a 2 GB file), PERF-T02 (resume), PERF-T04 (one pass), PERF-T05 (seekable source), PERF-T06 (`.part` staging), PERF-T07 (FAT32), PERF-T08 (`saveToGallery`), PERF-T09 (settle/ACK grace), PERF-T10 (no duplicate tail on resume) |
| U ui | `opt-ui` | ☑ | ☑ | ☐ | `b9c2ed60` (merge) | **the branch tip did not compile.** Four break classes (missing `AttachmentUi` imports and a wrong parameter type in the extracted `FlashMessageBubble` composables, `it.fileName`/`state.title` in `FlashConversationScreen`, `WindowInsets.isImeVisible` in `commonMain`); 21 of 26 findings kept, 7 repaired during review (stale per-card closures, echo matching by text alone, an in-flight attachment re-enabling send, two unsynchronised caches), 1 reverted (`O24-06`), 4 handed to G/C/M. So the stream's "verified green" lines describe a tree that is not this one, and its "698 tests" is this plan's pre-branch baseline for `:ui:chat:testAndroidHostTest` (measured 730 jvm / 705 androidHost after repair). Batches 1 + 2 delivered; batch 3 not attempted. Review: `logs/streams/ui/INTEGRATOR-REVIEW.md` | PERF-U01..U06 (`U02` desktop archive still reachable, `U03` keyboard auto-scroll, `U04` an echo whose row never arrives, `U05` a dropped settings write, `U06` wire the stability config) |
| N net | `opt-net` | ☑ | ☑ | ☑ | `8c331229` (merge) | **PASS — gate 1 forced on the merged tree: `BUILD SUCCESSFUL`, 161 of 161 tasks executed, 5 m 5 s, exit 0** (ten `jvmTest` suites incl. `:core:discovery` and `:core:hub-protocol`, `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin`). Gate 2 forced on the same tree: the three host suites green (`:ui:chat` 705, `:core:messaging` 688, `:core:persistence` 53, zero failures) and `:app:testDebugUnitTest` 87 with **only** the two known ERROR-106 rows, so the build exits 1 exactly as the §6.3 baseline predicts; gate 2b after the last KDoc edit 730 `jvmTest` / 705 `testAndroidHostTest`, zero failures. 11 of 15 findings kept (6 repaired), 2 **rejected and reverted** (`O10-05` sweep 5 s→15 s inverted `CompositeDiscovery`'s browse-watchdog invariant and its saving was unmeasured; `O10-07`'s "multicast is healthy" signal cannot see what it claims, so on a broadcast-only network it suppressed the fallback that network needs), `O14-03` handed to D, `O09-04`/`O09-07` declined per ADR-232. Five defects the report did not mention were found and fixed — the worst being that a full send queue returned `sendFrame == false`, which the dispatcher reads as **permanent death of that channel**. The numeric headlines are withdrawn (`EXP-100`..`102` in `logs/experiments.md`). Batches 1-3 delivered. Review: `logs/streams/net/INTEGRATOR-REVIEW.md` | PERF-N01 (two-phone reconnect handshake), PERF-N02 (a message stays instant under a saturating transfer), PERF-N03 (broadcast-only discovery + network switch) |
| G android | `opt-android` | ☑ | ☑ | ☐ | `33e82559` (merge), tip `2f3bd669` | **PASS** - gate 1 forced on the merged tree: `BUILD SUCCESSFUL in 6m 2s`, 243 of 243 tasks executed, 3 234 tests over 13 suites, 0 failures (2 documented POSIX-only skips) plus `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin`. 10 of 12 findings kept, **4 repaired during review**, and three of those repairs were behaviour regressions the stream's tests could not see because each lived in a caller: the bounded wake lock was released while a peer was still connected (ERROR-924), the keepalive fast path returned `Result.success()` regardless of ADR-232's "Keep listening in the background" opt-in (ERROR-925), and the idle foreground-service stop could not fire in the case it was written for and could not re-arm (ERROR-926); plus the Windows settings row that described close-to-tray as an Android opt-in and the storage card that lost its only refresh call (ERROR-927). `O01-07` needed no change (`MediaCodecList` was already behind `by lazy`; ERROR-923 corrected the entry that claimed a fix), `O01-08` deferred to D. **`OPT-O10-01` is closed** - `setScreenActive` has callers. Every figure in the report is withdrawn (`EXP-105`..`108`). Batches 1-2 delivered; batch 3 not attempted. Review: `logs/streams/android/INTEGRATOR-REVIEW.md` | PERF-G01..G08 (`G01` runs with **and** without a connected peer, `G02` with the opt-in off, on, and on after a cold process death) |
| H hub | `opt-hub` | ☑ | ☑ | ☑ | `00f7013b` (merge), tip `200474a4` | **PASS** - the same gate-1 run (3 234 tests, 664 of them `:server:test`, 0 failures). 16 of 18 findings kept, 4 repaired, **`O16-03` rejected and reverted** (`8b622a9b`, ADR-298): presence has no push channel, and the code did not slow the poll down but **skipped the fetch entirely** while the relay was up, so `hubOnline` froze for the whole relay session and `HubFacade` routed on it. Two of the repairs fixed invariants the changes themselves broke - `putChunk` could commit a `file_chunk` row whose chunk file was gone (the rename now happens inside the transaction, ADR-290), and an ack batch above 100 ids would have been rejected whole by `RelayFrameCodec`, turning every ack into a permanent redelivery (ADR-296) - and **`O16-04` is still transport-only: no product path sends a batch yet**. `O02-07`/`O17-01` are documents whose figures are estimates (ADR-299). Two defects the report did not mention: `Vary: Accept-Encoding` missing on 304s (ERROR-936) and the `CachedMailboxStatsTest` wall-clock flake (ERROR-937); `RevocationAndEnrolmentTest` de-flaked (ERROR-934). Every figure withdrawn (`EXP-110`..`114`). Review: `logs/streams/hub/INTEGRATOR-REVIEW.md` | PERF-H01..H07 (`H04` records that the client still acks one message at a time; `H07` kills the hub between the staged write and the commit) |
| E desktop | `opt-desktop` | ☑ | ☑ | ☐ | **MERGED `c5d825f9`** (reviewed tip `b2b61b92`) | Review complete: six caller/lifetime defects repaired; targeted 259 tests PASS. Merged `--no-ff` on 2026-11-02 (clean, 23 files) after both expanded kernel gates passed on `bec221d2`. **Both expanded §6.3 gates PASSED on the merged tree**: gate 1 on the third run (`BUILD SUCCESSFUL in 7m 3s`, 259/259 tasks, exit 0) and gate 2 (`BUILD SUCCESSFUL in 8m 53s`, 218/218 tasks, 2 110 tests, 0 failures, 0 skips). The first two merged-tree gate-1 runs failed on ERROR-959 (relay `ERR_EXPIRED` frame) and ERROR-960 (desktop PTT 0-of-8 frames); across seven measured runs the **unmodified** tree also passes, so they are gate flakiness — the one candidate fix (reverting `limitedParallelism(8)`) was tested, passed its gate, and was discarded because the unmodified tree passed too. No product line reverted, nothing weakened, no measured headline. `logs/streams/desktop/INTEGRATOR-REVIEW.md` | PERF-E01..E08 |
| K kernel | `opt-kernel` | ☑ | ◐ | ☑ | `bec221d2` (merge), repaired `22a53994` | MERGED; first expanded gate 1 FAILED on two load-sensitive tests (ERROR-957/958), both caught alone as passing; **both expanded gates then PASSED on `bec221d2`** - gate 1 `BUILD SUCCESSFUL in 12m 40s`, 259/259 tasks, 3 597 tests, 0 failures, 2 documented POSIX skips; gate 2 `BUILD SUCCESSFUL in 10m 47s`, 218/218 tasks, 2 110 tests, 0 failures, 0 skips. Neither flake repeated; both ERRORs stay OPEN as intermittent, nothing weakened. Four defects repaired; keyed Cipher/Mac ThreadLocals rejected, public-key cache owns immutable encodings. RNG/transfer-hashing targets partial; gzip API-only. No security suite excluded or measured headline. `logs/streams/kernel/INTEGRATOR-REVIEW.md` | PERF-K01..K05 |
| S swarm | `opt-swarm` | ◐ | ☐ | ☐ | | **RUNNING 2026-11-02** — worktree `E:\FlashWork\opt-swarm` on `agent/opt-swarm`, two-agent parallel start from the **pushed** `dev` tip `7adcf4f5`. No merge, no gate yet. | PERF-S01 (three devices) |
| R radio | `opt-radio` | ☑ | ☐ | ☐ | **MERGED `1fa3a2d1`** (reviewed tip `3babf39b`) | **Gate 1 PASS on the second run** (`BUILD SUCCESSFUL in 8m 7s`, 261/261 tasks, exit 0) after the first merged-tree run failed `:server:test` with two `RelayDeliveryContractTest` cases (ERROR-959 recheck) — the class passes alone and R touched neither `server/` nor `core/common`. The merged gate list now includes `:core:radio:jvmTest` (273 tests, 0 failures). **Review:** `O26-01`..`O26-07` kept; AAD verified byte-identical; crypto caches only providers/public keys; the integrator's own `trySend` change **reverted** (ADR-331 had already rejected dropping ACKs; the bounded pause fits `rxFlow`'s 128-frame buffer on a half-duplex medium), and **ERROR-973 repaired** — `BlockingStreamReader.close()` interrupts the pump thread, without which the stream's own test failed 3 of 3 host-suite runs. Branch tip: `:core:radio:testAndroidHostTest` 192/0/0 twice. Nothing radio-side is device-verified. `logs/streams/radio/INTEGRATOR-REVIEW.md` | PERF-R01..R05 (idle-hour wake-ups/battery, on-air latency, UI during a failed probe, 300 texts on a lossy channel, first runtime exercise of the jSerialComm write path) plus `RADCHAT-DEV03..07` re-runs || C calls | `opt-calls` | ☐ | ☐ | ☐ | | | PERF-C01 (call CPU/audio) |

---

## 11. Kickoff prompts

Paste **one** block into a fresh agent session. Each starts by creating its own worktree on `E:`, then re-reads the
plan and the audit evidence. They are written to be used verbatim; only the stream letter changes.

Two things the blocks do not repeat:

- **Resuming a stream?** If `E:\FlashWork\opt-<stream>` already exists, do **not** create a second worktree: run
  `git worktree list` from the main tree, work in the existing one, and `git log --oneline -1` first to see what is
  already committed. `git worktree add` fails if the branch exists, and a second tree on the same branch is not
  possible.
- **The base is `dev` at the commit this plan was committed in.** If `dev` has moved since, `git merge dev` before
  batch 1 (never rebase).

**Rules learned from Wave 0 (2026-10-30, ADR-231) — they apply to every block below:**

1. **Both halves of any before/after must be forced, and the command line goes in the log.** Stream A reported a 13 %
   gate win by comparing an unforced run (almost everything up-to-date) with the plan's forced baseline; the same
   command was really 70 % slower (ERROR-872). For a wall-clock evidence run use
   `--rerun-tasks --no-build-cache` (forced runs only *write* the build cache — 112 s on gate 1 — and reuse nothing).
2. **`maxParallelForks` is 1 for every `Test` task now** (`:server:test` keeps its own 4). Do not raise it to make a
   number look better: two forks for the Robolectric host suites was 114 s faster and flaked two
   `RealFlashChatRepositoryTest` cases (ERROR-873). `PERF-A07` is the fix, and it is Stream D's first step.
3. **A stream may not change what ships** (ABI splits, installer contents, published POMs, jlink modules) and may not
   narrow a keep rule whose comment records a past crash. Those are owner decisions with a named check: propose them
   with numbers, do not merge them (ADR-231 rules 1-2).
4. **"It exists" is not "it works".** A hand-written baseline profile, a flag that turns out to be the JVM default
   (`-Xshare:auto`), or a benchmark module with no build type to run against is not a finding closed. Say what was
   executed; `OPT-O19-05`, `OPT-O01-03`, `OPT-O28-01` are open for exactly this reason.


### 11.A — build

**DONE 2026-10-30 — do not hand this block out again.** The stream ran, the integrator reviewed and merged it
(`0cc10902`): 21 of 25 findings kept, 5 reverted with reasons. Outcome: §9 A "Status 2026-10-30", ADR-231, and
`logs/streams/build/INTEGRATOR-REVIEW.md`. Build-file requests from other streams now go to the integrator.

```text
You are the BUILD (A) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST, before reading anything else:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-build -b agent/opt-build dev
    Copy-Item .\local.properties E:\FlashWork\opt-build\local.properties
    cd E:\FlashWork\opt-build
Do every edit, every build and every commit there. The main tree is the integrator's: never edit it. Never copy
keystore.properties or the .jks.

READ FIRST
1. AGENTS.md sections 6-13, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "A — build" (your findings and batch order).
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 (machine rules) and section 8 (log fragments).
4. docs/audit/optimization-2026-10-23/: O21-build-gradle.md, O22-size-dependencies.md,
   O27-test-suite-performance.md, O01-startup.md (O01-03), O19-desktop-app.md (O19-04/05),
   O20-logging-probes.md (O20-05), O28-observability-benchmarks.md (O28-01).

YOU OWN: gradle.properties, settings.gradle.kts, the root and every module build.gradle.kts, gradle/**, .github/**,
buildSrc/**, app/proguard-rules.pro, ThirdPartyNotices*, tools/**, and a new benchmark/ module.
YOU MUST NOT TOUCH: any src/** file (except benchmark/src/**), and the canonical logs/docs (write logs/streams/build/).

DO IN THIS ORDER
Batch 1 (config only, no source): O21-01 parallel, O21-02 caching, O21-03 daemon heap, O21-04 :server forks,
O21-05 CI runs :server:test, O21-06 CI JDK, O21-07 pin BouncyCastle, O21-08 notices on release only, O21-10 drop the
duplicate KSP, O22-01 ABI splits, O22-04 dead JRE modules, O22-05 drop Material 2, O22-06 narrow the webrtc keep rule,
O22-07 remove parked ui:chat deps, O20-05 R8 log stripping.
Batch 2: O21-09 buildSrc split, O19-04 desktop JVM flags, O19-05 AppCDS, O01-06 (the same defect as O19-04/05 - close it
as a duplicate), O22-02 strip foreign SQLite binaries, O01-03 + O28-01 the :benchmark module with Macrobenchmark + baseline
profiles.
Batch 3: O27-01 forks monorepo-wide, O27-02 virtual time (config/harness only), O27-05 flake infrastructure.
GATED: O22-03 (BouncyCastle removal) needs an ADR - record BLOCKED, do not do it.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew. Exit 75
  means another agent holds the lock: do non-build work and retry.
- For evidence numbers rerun the single task with --rerun-tasks and quote the executed time, not FROM-CACHE.
- Do not change application behaviour, versions of Kotlin/AGP/Compose, or add a dependency without asking.
- Commit only to agent/opt-build, small commits, finding ID in the subject (perf(build): enable Gradle parallel (OPT-O21-01)).
- Log only into logs/streams/build/ in the canonical format, using ADR 230-239, ERROR 871-880, EXP 80-84.

VERIFY: :server:test wall clock before/after; :app:assembleRelease APK size per ABI; :desktop:package* installer size;
one cold :core:messaging:compileKotlinJvm. Then the integration gate of the plan section 6.3.

REPORT (short): what you changed per finding, the before/after numbers you measured, the exact tasks you ran and
their last lines, the shared files you edited and their hunk sizes, what is left, and any finding that no longer
reproduces. Run cmd /c "tools\flash-gradle.cmd --stop" when you finish a session.
```

### 11.D — data

```text
You are the DATA (D) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-data -b agent/opt-data dev
    Copy-Item .\local.properties E:\FlashWork\opt-data\local.properties
    cd E:\FlashWork\opt-data
The main tree is the integrator's: never edit it. Never copy keystore.properties or the .jks.

READ FIRST
1. AGENTS.md sections 6-13, 18, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "D — data".
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3, section 7 (reservations), section 8.
4. docs/audit/optimization-2026-10-23/: O06-database-room-sqlite.md, O04-chat-list-and-conversation.md,
   O02-heap-and-native-memory.md (O02-02/O02-06), O14-concurrency-dispatchers-locks.md, O25-algorithmic-complexity.md
   (O25-04/09), O23-storage-file-io.md (O23-05).

YOU OWN: core/persistence/** (Room schema step 22 is yours), core/messaging/** EXCEPT hub/** and radio/**,
and desktop JvmFlashDatabaseOpener*. You are the only owner of RealFlashChatRepository.kt.
YOU MUST NOT TOUCH: ui/**, server/**, core/transfer, core/swarm, any build.gradle.kts, the canonical logs
(write logs/streams/data/).

DO IN THIS ORDER
Step 0 (added by the integrator after Wave 0, ERROR-873 / PERF-A07): `RealFlashChatRepositoryTest` has two cases that
synchronise with `delay(20)`/`delay(100)` and read an empty list when the machine is loaded - "pins are stored per
conversation, newest first, and survive closing and reopening the chat" (~line 522) and "delete for everyone sends
direct action only for the local author" (~line 3436). You own this file. Replace the sleeps with an explicit wait on
`conversationState.value` (or collect the flow) in your first commit, then run the suite 10 times while a heavy task
(`:server:test`) is running. Until that is done nobody may raise `maxParallelForks` above 1, which costs every stream
114 s per gate-2 run.
Batch 1: O06-01 index messages(conversationId, readAt) + rewrite observeUnreadCounts; O06-05 the five missing indexes;
O04-02 the N+1 queries in the chat-list combine; O25-04 cache the decodeReactorIds regex; O02-06 + O06-04 desktop
opener PRAGMAs (it runs non-WAL/synchronous=FULL);
O23-05 + O06-06 auto_vacuum + journal_size_limit (this is schema step 22).
Batch 2: O06-02 latest previews; O04-03 receipt invalidation storm; O04-04 search/unread scans; O06-07 one transaction
for markChunksDone + setBytesDone; O06-09 the org search index; O02-02 stop retaining closed conversation state;
O14-04 Eagerly -> WhileSubscribed; O14-05 structural equality on every mutation; O07-03 write amplification (DAO side);
O28-05 slow-query callbacks.
Batch 3: O06-08 BLOB inlining (coordinate with S), O25-09 collection sizing, O27-04 in-memory SQLite tests.
DECIDED (ADR-232, do not record BLOCKED): O06-03 stays on LIKE (FTS5 only if a real device shows >~300 ms over ~100k
messages); O06-10 takes the mmap_size half only - do NOT lower the PBKDF2 iteration count; O04-01 IS approved - page the
history, and the pager must resolve FlashBackStackState.targetMessageId so search deep-links still land.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- Any Room change is schema step 22: bump FlashSchemaSteps/FlashDatabase/FlashMigrations, add schemas/22.json, and run
  FlashMigrationsChainTest + FlashJvmMigrationsTest. No other stream may bump the schema.
- Measure with docs/audit/optimization-2026-10-23/bench/db_benchmark.py before and after at 100k messages.
- Keep queries index-friendly; never drop WAL and never synchronous=OFF (rejected.md REJ-03).
- Commit only to agent/opt-data, small commits with the finding ID. Logs only in logs/streams/data/
  (ADR 240-249, ERROR 881-890, EXP 85-89, tests PERF-D*).

VERIFY: :core:persistence:jvmTest, :core:messaging:jvmTest, :core:messaging:testAndroidHostTest (this is where
RealFlashChatRepositoryTest lives - 688 tests, and it is the suite that proves you did not break chat), the two
migration tests, and the db_benchmark before/after numbers. Then the integration gate (plan section 6.3).

REPORT: per finding the change + measured before/after, the gate commands and their last lines, the schema step you
took, what is left, and every finding that no longer reproduces. Stop the daemon when the session ends.
```

### 11.T — transfer

```text
You are the TRANSFER (T) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-transfer -b agent/opt-transfer dev
    Copy-Item .\local.properties E:\FlashWork\opt-transfer\local.properties
    cd E:\FlashWork\opt-transfer
The main tree is the integrator's. Never copy keystore.properties or the .jks.

READ FIRST
1. AGENTS.md sections 6-13, 18, 23, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "T — transfer" (+ compounding.md cascade 1).
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O05-media-pipeline.md, O07-file-transfer-throughput.md,
   O23-storage-file-io.md, O02-heap-and-native-memory.md (O02-01/08/09/10), O25-algorithmic-complexity.md (O25-01/07),
   O28-observability-benchmarks.md (O28-02), rejected.md (REJ-02, REJ-05 - do not break them).

YOU OWN: core/transfer/**, ui/platform-shims/**, core/engine/FlashPathSanitizer.kt.
YOU MUST NOT TOUCH: core/persistence (D owns the DAOs), core/swarm (S), core/network (N), ui/chat, any
build.gradle.kts, the canonical logs (write logs/streams/transfer/).

DO IN THIS ORDER
Batch 1 (cheap): O07-07 the 1 MB chunk clamp; O07-09 the boxed done-set reconcile; O25-01 pendingIndexes O(N*M);
O07-05 per-chunk Sha256 provider lookup; O23-04 purge orphaned hub binaries/partials/voice/camera files; O23-03 disk
pre-allocation + FAT32 4 GB check; O05-04 EXIF without a second full read; O05-05 no post-decode pixel rotation blit;
O05-08 trim the camera/voice cache; O05-09 bounded decode retries; O02-08 + O02-09 the image-decoder cache and
Activity-context leaks in ui/platform-shims.
Batch 2 (the cascade): O05-01 + O07-01 ONE streaming pass for whole-file + chunk hashes (5.8 GB -> ~1.9 GB reads for
2 GB) keeping whole-file verification; O02-01 inbound frames through ChunkBufferPool; O23-02 seekable resume source;
O07-08 coalesce the 100 Hz progress flow; O07-06 in-flight chunk retransmission; O07-02 .part + atomic rename;
O07-04 hash/disk work off the lock-bound reader; O28-02 flight telemetry so the improvement is measurable.
Batch 3: O02-10 the resume bit vector, O25-07 ChunkFrame.serialize pooling, O05-02/03/10 desktop AAC/JCodec off the
UI thread. GATED: O07-10 (REJ-01) - record BLOCKED, do not remove encryption.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- Never keep a whole file in memory; stream (AGENTS section 18). Never drop the whole-file hash (REJ-02).
- No chunk size above 1 MB and no 8/16 MB chunks (REJ-05).
- Commit only to agent/opt-transfer, small commits with the finding ID. Logs only in logs/streams/transfer/
  (ADR 250-259, ERROR 891-900, EXP 90-94, tests PERF-T*).

VERIFY: :core:transfer:jvmTest, bench/TransferLoopbackBenchmark.kt before/after, a resume test that interrupts at 90%,
and the integration gate (plan section 6.3).

REPORT: per finding the change + measured before/after, the gate commands and their last lines, what is left, and the
findings that no longer reproduce. Stop the daemon when the session ends.
```

### 11.U — ui

```text
You are the UI (U) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-ui -b agent/opt-ui dev
    Copy-Item .\local.properties E:\FlashWork\opt-ui\local.properties
    cd E:\FlashWork\opt-ui
The main tree is the integrator's. Never copy keystore.properties or the .jks.

READ FIRST
1. AGENTS.md sections 6-13, 22, 24, 31, 34, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "U — ui" (+ compounding.md cascade 2).
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O03-compose-stability.md, O24-ui-responsiveness.md,
   O04-chat-list-and-conversation.md (O04-05/06/07), O05-media-pipeline.md (O05-06/07),
   O25-algorithmic-complexity.md (O25-05).
5. docs/ui/flash-premium-chat-ui-implementation.md and docs/ui/chat-screen.md before changing a chat surface.

YOU OWN: ui/chat/** EXCEPT radio/** and FlashSwarm*.kt, ui/theme/**, ui/hub/**.
YOU MUST NOT TOUCH: RealFlashChatRepository.kt or any core/** file, ui/callui (C), app/** (G), desktop/** (E), any
build.gradle.kts, the canonical logs (write logs/streams/ui/).

DO IN THIS ORDER
Batch 1: O03-03 hoist draft out of the root ConversationScreen scope; O03-04 stability configuration file;
O03-01/11/12 animation reads in the draw phase + reduceMotion guards; O03-05 hoist per-card lambdas; O03-06 the O(N)
set equality in LazyColumn; O03-08 context-menu list allocation; O03-13 hoist BorderStroke; O03-09 memoise the
trusted-peer regex mapping; O03-02 the reaction chip reading animation state in composition; O04-05 per-row swipe state;
O04-06 day separators (slot reuse); O24-05 settings toggle write; O24-06 sheet entrance transitions; O24-07 reserve
image ratio.
Batch 2: O03-14 splash canvas allocations (ui/theme); O03-16 quoted reply double measurement; O03-17 nav-bar
BoxWithConstraints; O03-07 the GroupFileSender remember key; O24-01 optimistic echo AS A UI-LOCAL OVERLAY ONLY
(the outbox row must exist before "sent"; never bypass durability); O24-02 keep the staging tray visible with a
spinner; O24-03 IME resize jitter; O24-04 auto-scroll on keyboard; O05-06/07 voice-note 10 Hz + waveform parsing;
O25-05 memoise the sorts; O04-07 unconstrained animateScrollToItem.
NOT YOURS: O03-15 (S), O03-18 (C), O03-10 and O24-08 (E).

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- Keep the design language (docs/ui/); no stock Material component may become the final composer/bubble.
- Prefer reading animation state in the draw phase; never add an infinite transition without reduceMotion.
- Commit only to agent/opt-ui, small commits with the finding ID. Logs only in logs/streams/ui/
  (ADR 260-269, ERROR 901-910, EXP 95-99, tests PERF-U*).

VERIFY: :ui:chat:jvmTest, :ui:chat:testAndroidHostTest (698 tests - it runs the Compose-side logic suites),
:ui:theme:jvmTest, the Compose compiler stability report for the touched composables, and
one device scroll check during an active transfer (PERF-U01). Do not claim a frame-rate number without a
Macrobenchmark run.

REPORT: per finding the change, what you measured (or why it is inspection-only), the gate commands and their last
lines, what is left, findings that no longer reproduce. Stop the daemon when the session ends.
```

### 11.N — net

```text
You are the NET (N) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-net -b agent/opt-net dev
    Copy-Item .\local.properties E:\FlashWork\opt-net\local.properties
    cd E:\FlashWork\opt-net
The main tree is the integrator's. Never copy keystore.properties or the .jks.

READ FIRST
1. AGENTS.md sections 6-13, 16, 19, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "N — net".
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O09-websocket-mesh-wire.md, O10-discovery-presence.md,
   O14-concurrency-dispatchers-locks.md, and docs/protocol.md before touching anything on the wire.

YOU OWN: core/network/**, core/discovery/**, and core/engine/{FlashEngine,FlashBaseEngine,FlashConfig,
FlashInboundRouter,MagicFrameRouter,PeerOrderedInbox,PlatformLock}.kt.
YOU MUST NOT TOUCH: core/messaging (D/H), ui/**, app/** (G), desktop/** (E), core/transfer (T), any build.gradle.kts,
the canonical logs (write logs/streams/net/). You are the owner of PlatformLock.kt.

DO IN THIS ORDER
Batch 1: O09-06 + O14-02 the Thread.sleep(25) backpressure poll; O09-05 the ByteArrayPool copy-and-discard;
O09-08 the unconditional UUID.randomUUID(); O10-03 the 253 Binder IPCs per subnet sweep; O10-06 duplicated
connectivity triggers.
Batch 2: O09-01 BoundedSendQueue bypassed by blocking writes under the global write lock; O14-01 the monitor lock held
across socket I/O; O14-03 the global drainMutex serialising peers; O09-03 double JSON tree parsing; O10-04 NSD resolve
queue head-of-line; O10-05 unsynchronised polling sweepers; O10-07 directed broadcast basic-rate penalty.
Batch 3: O09-02 TLS session resumption (measure both hosts); O10-01 screen-off discovery downscale (agree a screen-state
API with G before changing discovery); O10-02 the permanent MulticastLock -> a leased lock with a bound.
DECIDED (ADR-232, do not record BLOCKED): O09-04 and O09-07 are NOT taken - no wire-format change now, no bytes change. Take
only the allocation half (O09-05, O09-06, O14-02), and say in the report that the wire items were declined with this ADR.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- Never weaken TLS, pinning or identity validation (AGENTS section 19). Ordering and delivery guarantees must survive
  any lock change: prove it with a test, not with an argument.
- No wire-format change without an ADR and both hosts.
- Commit only to agent/opt-net, small commits with the finding ID. Logs only in logs/streams/net/
  (ADR 270-279, ERROR 911-920, EXP 100-104, tests PERF-N*).

VERIFY: :core:network:jvmTest, :core:discovery tests, :core:engine:compileKotlinJvm, and a two-device transfer while
the lock change is exercised (PERF-N01). Then the integration gate (plan section 6.3).

REPORT: per finding the change + what you measured, the gate commands and their last lines, what is left, findings
that no longer reproduce, and any interface you need from another stream. Stop the daemon when the session ends.
```

### 11.G — android

```text
You are the ANDROID (G) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-android -b agent/opt-android dev
    Copy-Item .\local.properties E:\FlashWork\opt-android\local.properties
    cd E:\FlashWork\opt-android
The main tree is the integrator's. Never copy keystore.properties or the .jks. You do not touch the phone unless the
owner names you for a device session.

READ FIRST
1. AGENTS.md sections 6-13, 20, 21, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "G — android".
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4 (the phone is single-owner) and 8.
4. docs/audit/optimization-2026-10-23/: O13-background-battery-wakeups.md, O01-startup.md,
   and docs/android-platform-notes.md. (O02-08/O02-09 are the transfer stream's: they live in ui/platform-shims.)

YOU OWN: app/** EXCEPT radio/**, calling/**, ptt/**; app/src/**/ui/splash/**.
YOU MUST NOT TOUCH: core/** (K owns common/security, N owns discovery/network, D owns persistence), ui/** (U),
build.gradle.kts (A), the canonical logs (write logs/streams/android/).

DO IN THIS ORDER
Batch 1 (battery - the owner-visible one): O13-01 the perpetual partial wake lock -> bounded, ref-counted lease;
O13-02 the keepalive worker spinning the whole engine for an empty outbox; O13-03 Verifying missing from the Android 15
timeout plan; O13-08 the delay(300) service poll; O01-02 synchronous DataStore/SQLite on the main thread;
O01-07 redundant MediaCodecList enumeration; O01-08 sequential PRAGMAs at open.
Batch 2: O13-05 ACTION_SCREEN_OFF handling; O13-04 Transsion Tier 3 silent AudioTrack; O13-07 audio-focus backoff;
O01-04 eager engine assembly + subnet sweep at launch.
DECIDED (ADR-232, do not record BLOCKED): O13-06 IS approved - the connectedDevice service stops when there is no peer, no
transfer and no hub connection, plus one opt-in "Keep listening in the background" setting with the honest sentence. O01-01 is
NOT taken - ADR-080 stands and the splash is never dismissed; change what happens during it, never whether it finishes.
NOTE: O10-01/O10-02 were N's and are merged (`8c331229`): `FlashDiscovery.setScreenActive` exists with **no caller** -
wiring it from the Android host is YOURS (`OPT-O10-01`). You still do not edit `core/discovery`; call the API. Its KDoc
warns that ECO is a whole-mode switch, so do not fire it mid-call or mid-transfer, and do not treat it as a presence
source.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- **LESSONS FROM WAVES 1-2 (the integrator paid for these):**
  - **Grep for a caller before you write "implemented".** Three Wave 1 findings and one Wave 2 finding were "done" and
    inert: the code existed, compiled and was tested, and no host called it. `git grep '<symbolName>('` outside the tests
    is the check.
  - **Never make a test agree with a behaviour change.** Wave 2's `O10-05` raised a sweep interval, broke a test, and was
    "fixed" by pinning the interval in the test - the test was right and the change was reverted (`ERROR-912`). If a test
    fails, decide which one is wrong *in the report*, not by editing the assertion.
  - **The tree you report on must be the tree that compiles.** Wave 2's UI stream reported green gates for a branch whose
    tip did not build. Run your own gate last, on the tip, and quote the last lines.
  - **Docs are UTF-8 and stay that way** (`ERROR-013`/`ERROR-913`): edit them with the editor tools, never by round-tripping
    through PowerShell 5.1 `Get-Content`/`Set-Content` without `-Encoding utf8` on both sides.
- Verify every Android platform claim against the current official docs before changing behaviour, and write the fact +
  URL + date into `logs/streams/android/platform-notes-additions.md` (the integrator folds it into
  `docs/android-platform-notes.md`). Never claim background support without a device run.
- Commit only to agent/opt-android, small commits with the finding ID. Logs only in logs/streams/android/
  (ADR 280-289, ERROR 921-930, EXP 105-109, tests PERF-G*).

VERIFY: :app:compileDebugKotlin, :app:testDebugUnitTest, :app:compileDebugAndroidTestKotlin (compile only - running is a
device session), and put the battery/Doze checks in the backlog fragment. :app:testDebugUnitTest already has TWO
pre-existing failures on dev (TransfersUiMapperTest.retryableComesFromTheModelPredicate and
.aHubRowSaysSoAndOffersNoRetry, ERROR-106) - your job is to add no third one, not to fix those unless you say so in the
report. Then the integration gate (plan section 6.3).

REPORT: per finding the change, what still needs a device, the gate commands and their last lines, and any platform
fact you verified with its URL. Stop the daemon when the session ends.
```

### 11.H — hub

```text
You are the HUB (H) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-hub -b agent/opt-hub dev
    Copy-Item .\local.properties E:\FlashWork\opt-hub\local.properties
    cd E:\FlashWork\opt-hub
The main tree is the integrator's. Never copy keystore.properties or the .jks. No deployment from this stream.

READ FIRST
1. AGENTS.md sections 6-13, 19, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "H — hub" (+ compounding.md cascade 3).
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 7 and 8.
4. docs/audit/optimization-2026-10-23/: O17-hub-server.md, O18-admin-console.md, O16-hub-client.md,
   O25-algorithmic-complexity.md (O25-02/06/09/10), O23-storage-file-io.md (O23-01), and docs/protocol.md.

YOU OWN: server/**, core/enterprise/**, core/hub-protocol/**, core/messaging/hub/**,
core/engine/{HubFileBinding,HubFileRows,StreamingHubFileSource}.kt, docs/protocol-routes.txt.
YOU MUST NOT TOUCH: core/persistence, core/messaging outside hub/, ui/**, hosts, build.gradle.kts (A owns :server and
console build config), the canonical logs (write logs/streams/hub/).

DO IN THIS ORDER
Batch 1: O23-01 per-chunk fsync + open/close in HubFileDisk (1,600 per 50 MB); O25-10 the N+1 revocation cascade (~2.1 s
of exclusive write lock); O18-01 gzip/deflate static assets; O18-02 the 35-request ES module waterfall;
O17-04 ExitOnOutOfMemoryError in the deploy scripts.
Batch 2: O17-02 writeChunkFile inside db.withTransaction; O25-02 the O(D*M) directory sync; O25-06 the linear
memberDeviceIds scan; O16-01 stop-and-wait hub file chunks; O16-03 the presence poll while the relay is up (the constant is
`HubPresenceDefaults.POLL_MS` = 15 s, not the "30 s" this batch list first said - and that finding was REJECTED and
reverted, ADR-298, so do not re-attempt it without a presence push channel);
O02-03 the org snapshot heap spike; O27-03 real sockets/Netty in tests.
Batch 3: O16-02 multi-page sync, O16-04 batched mailbox acks, O18-03 console preloading, O25-09 collection sizing,
O17-03 group fan-out. DOCUMENT ONLY: O02-07 / O17-01 (the heap cliff is a deployment profile, not source).
If you need a server migration, take the next version of its component and record it in your fragment.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- **LESSONS FROM WAVES 1-2 (the integrator paid for these):**
  - **Grep for a caller before you write "implemented".** Four "finished" findings in Wave 1-2 had no host caller and were
    inert; `git grep '<symbolName>('` outside the tests is the check.
  - **Never make a test agree with a behaviour change** (`ERROR-912`): a failing test after a change is a question, not an
    obstacle. Decide which side is wrong *in the report*.
  - **The tree you report on must be the tree that compiles.** Quote your own gate's last lines, run on the tip.
  - **`:server:test` has one documented load flake**
    (`RelayFrameRouterTest.messagesSentWhileTheRecipientConnectsAreAllDeliveredInOrderExactlyOnce`), green when rerun alone.
    Rerun it; do not "stabilise" it by relaxing the timing - that is the `ERROR-912` mistake.
  - **Docs are UTF-8 and stay that way** (`ERROR-013`/`ERROR-913`): edit with the editor tools, never by round-tripping
    through PowerShell 5.1 `Get-Content`/`Set-Content` without `-Encoding utf8` on both sides.
- The wire contract is documented: any REST/route change updates docs/protocol-routes.txt (RouteTableContractTest) and
  docs/protocol.md. A hub-file wire change needs an ADR first.
- Never weaken the sealing, the bearer checks or the per-device limits for speed.
- Commit only to agent/opt-hub, small commits with the finding ID. Logs only in logs/streams/hub/
  (ADR 290-299, ERROR 931-940, EXP 110-114, tests PERF-H*).

VERIFY: :server:test (compare with A's parallel baseline), :core:enterprise:jvmTest, the hub file e2e contract test, the
console node tests, and the server MigratorTest. Then the integration gate (plan section 6.3).

REPORT: per finding the change + measured before/after (latency, lock hold time, bytes), the gate commands and their
last lines, the migration versions you took, what is left. Stop the daemon when the session ends.
```

### 11.E — desktop

```text
You are the DESKTOP (E) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-desktop -b agent/opt-desktop dev
    Copy-Item .\local.properties E:\FlashWork\opt-desktop\local.properties
    cd E:\FlashWork\opt-desktop
The main tree is the integrator's. Never copy keystore.properties or the .jks. The owner's desktop app may stay open
during the day; use temp state directories in tests, never ~/.flash.

TREE STATE (2026-11-01): branch from `dev` at `44eed960`. Waves 0-3 are merged and both integration gates (plan section
6.3) are green on that commit - gate 1 `BUILD SUCCESSFUL in 6m 2s`, 243 of 243 tasks executed, 3 234 tests, 0 failures;
gate 2 `BUILD SUCCESSFUL in 9m 23s`, 212 of 212 tasks, 1 701 tests (:app:testDebugUnitTest 90), exit 0. There are no
known-expected test failures on your base, so a failure on your branch is yours: reproduce it on `44eed960` before you
call anything pre-existing, and name the commit you compared against. Two stale instructions in older copies of these
prompts are RETIRED: "note the known ERROR-106 failures, do not fix them here" (that test, `:desktop:jvmTest >
DesktopEngineGroupSessionUpTest`, does not reproduce on `44eed960` - run alone: `BUILD SUCCESSFUL in 34s`, 1 test,
0 failures; ERROR-106 stays OPEN only because its root cause was never established), and the plan's "expected failures"
rows for `TransfersUiMapperTest` (historical: fixed by `432ceb52`, ERROR-935).

READ FIRST
1. AGENTS.md sections 6-13, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "E — desktop".
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O19-desktop-app.md, O03-compose-stability.md (O03-10),
   O01-startup.md (O01-05), O20-logging-probes.md (O20-03), O24-ui-responsiveness.md (O24-08).

YOU OWN: desktop/** EXCEPT radio/**, radioaudio/**, radiospike/**, and desktop JvmFlashDatabaseOpener* (D owns it).
YOU MUST NOT TOUCH: core/** (N owns network, K owns common/security), ui/**, app/**, build.gradle.kts (A owns the JVM
flags and packaging), the canonical logs (write logs/streams/desktop/).

DO IN THIS ORDER
O19-01 the recursive AWT DropTarget registration (key the DisposableEffect on the window only); O19-02 the transfer
toast observing activeTransfers at 100 Hz while minimised; O19-03 the window-width container read during resize;
O03-10 the desktop shell root invalidation from eight StateFlows; O19-06 + O01-05 the blocking constructor (DPAPI, key
generation, disk) - move it off the main thread with a first-frame-safe path; O19-07 idle discovery downscaling when
minimised to tray; O20-03 the synchronous unbuffered console sink; O14-06 the 28-34 threads the desktop spawns at idle
from independent coroutine/OkHttp/Ktor pools (own it here; do not edit core/network); O24-08 OS reduce-motion.
NOT YOURS: O19-04/O19-05 (A), O02-08 (T owns ui/platform-shims), O23 desktop bits owned by D.

WHAT CHANGED UNDER YOU IN WAVE 3 - read this before you touch the desktop host
- `FlashDiscovery.setScreenActive(false|true)` finally has callers, on ANDROID only (stream G, `OPT-O10-01`): the
  `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` receivers plus a re-check when a transfer finishes with the screen already
  off. The TODO left on the interface names YOUR finding: "wire the desktop host (minimised to tray). See O19-07."
- That seam is not a display hint: it downscales discovery to `FlashDiscoveryMode.ECO`, which flows into
  `DiscoveryModePolicy`, `ConnectionModePolicy` and `ConnectionStrategy.ECO` (`limitsSessions = true`). The caller owns
  the guard. Android's callers check for a live call, a live transfer and PTT first; a desktop that downscales during a
  call or a transfer drops a peer. Put the same guard in your caller and write a test for the guard, not only for the
  downscale.
- Hub presence (ADR-298): `EnterpriseHub` asks the hub who is online every `HubPresenceDefaults.POLL_MS` (15 s) over
  REST, reading up to 4 pages of the org people listing, and nothing on the wire pushes presence. Do NOT slow it,
  suppress it or gate it on window/minimised state - ADR-298 rejects exactly that: `HubFacade` picks the hub route from
  `hubOnline`, so a stale set misroutes messages for as long as the suppression lasts.
- The Android background work of wave 3 (bounded wake-lock lease, "Keep listening in the background" honoured in the
  keepalive worker, re-arming idle foreground-service stop) has no desktop equivalent. Do not port its reasoning here.
- THE RULE THIS WAVE EARNED: every behaviour regression the wave-3 review caught lived in a CALLER, not in the changed
  function - a wake lock released while a peer was still connected, an opt-in bypassed behind a worker's fast path, an
  idle stop that could never fire. For each finding you land, open its callers and write down what they now do
  differently. A stream's own green tests cannot see a caller.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- The window must show its first frame without waiting on disk or crypto; keep the DPAPI key handling unchanged in
  behaviour (only move the thread).
- Commit only to agent/opt-desktop, small commits with the finding ID. Logs only in logs/streams/desktop/
  (ADR 300-309, ERROR 941-950, EXP 115-119, tests PERF-E*).

VERIFY: :desktop:compileKotlinJvm, :desktop:jvmTest (green on your base - see TREE STATE, so a failure is a
finding, not a baseline), and a manual profiler note for navigation (PERF-E01). Do not add a wall-clock budget assertion
(ERROR-938: the integration gate runs on a saturated CPU, and an absolute budget there is a coin flip - assert a ratio,
a bounded wait, or a count). Then the integration gate (plan section 6.3). The integrator re-runs both gates on the
merged tree afterwards: a "verified green" line in your report describes YOUR tree, so also state what you did not run.

REPORT: per finding the change + what you measured, the gate commands and their last lines, what is left, findings
that no longer reproduce. Stop the daemon when the session ends.
```

### 11.K — kernel

```text
You are the KERNEL (K) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-kernel -b agent/opt-kernel dev
    Copy-Item .\local.properties E:\FlashWork\opt-kernel\local.properties
    cd E:\FlashWork\opt-kernel
The main tree is the integrator's. Never copy keystore.properties or the .jks.

TREE STATE (2026-11-01): branch from `dev` at `44eed960`. Waves 0-3 are merged and both integration gates (plan section
6.3) are green on that commit - gate 1 `BUILD SUCCESSFUL in 6m 2s`, 243 of 243 tasks executed, 3 234 tests, 0 failures;
gate 2 `BUILD SUCCESSFUL in 9m 23s`, 212 of 212 tasks, 1 701 tests (:app:testDebugUnitTest 90), exit 0. There are no
known-expected test failures on your base, so a failure on your branch is yours: reproduce it on `44eed960` before you
call anything pre-existing, and name the commit you compared against. Two stale instructions in older copies of these
prompts are RETIRED: "note the known ERROR-106 failures, do not fix them here" (that test, `:desktop:jvmTest >
DesktopEngineGroupSessionUpTest`, does not reproduce on `44eed960` - run alone: `BUILD SUCCESSFUL in 34s`, 1 test,
0 failures; ERROR-106 stays OPEN only because its root cause was never established), and the plan's "expected failures"
rows for `TransfersUiMapperTest` (historical: fixed by `432ceb52`, ERROR-935).

READ FIRST
1. AGENTS.md sections 6-13, 19, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "K — kernel" (+ compounding.md cascade 2 for FlashLog).
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O20-logging-probes.md, O15-serialization-crypto-hashing.md,
   O25-algorithmic-complexity.md (O25-03), O27-test-suite-performance.md (O27-06), docs/testing/PROBES.md.

YOU OWN: core/common/** and core/security/**.
YOU MUST NOT TOUCH: every other module (you are the only stream that edits core/common, so a mistake here breaks all
hosts), any build.gradle.kts, the canonical logs (write logs/streams/kernel/).

DO IN THIS ORDER
O20-01 level-gated inline logging (inline fun d(tag, msg: () -> String) + a check before the lambda) - the 11 FPS
contributor; O20-04 FlashProbe vararg/pair churn; O20-02 move the rotating sink's regex redaction + date formatting off
the caller thread; O20-06 compress the diagnostic log export; O15-01 cache the parsed SPKI in ecP256Verify;
O15-02 one SecureRandom; O15-03 cache Cipher per thread; O15-04 drop the okio HashingSink wrapper in Sha256.digest;
O25-03 top-level Regex + cached NFKD in foldForSearch/initials; O27-06 tag the heavy crypto tests out of the default sweep.

WHAT CHANGED UNDER YOU IN WAVE 3 - read this before you touch core/common or core/security
- Every module, both hosts and the `ui:` modules call `FlashLog` / `FlashProbe`. Grep `core/`, `ui/`, `app/` and
  `desktop/` before changing a signature; keep the enabled paths behaviourally identical.
- A `FlashProbe` line name or key spelling is an EVIDENCE CONTRACT: `docs/testing/PROBES.md` is the vocabulary and the
  log-export header, and whoever debugs a device run greps for those exact words. Add a probe or a key; never rename or
  re-spell one that already exists, and update `PROBES.md` in the same commit if the emitted text changes at all.
- `O27-06` (tag the heavy crypto tests out of the default sweep) CHANGES THE GATE. Plan section 6.3 / 6.3.1 names the
  exact commands and the expected counts, so a later green gate would silently cover fewer tests. Update those commands
  and counts IN THE SAME COMMIT as the tag, name the documented task that still runs the tagged tests, and put the
  before/after count in your report. No security test may leave every gate: if you tag one, it must still run somewhere
  the integrator can point at.
- You land BEFORE S, R and C on purpose: their measurements are taken on YOUR logging and hashing cost. "Source
  compatible but slower in the disabled path" is the failure mode this stream exists to catch - that is what the
  microbenchmark note is for. And no unmeasured headline: a number your branch cannot produce is marked NOT MEASURED
  with the `PERF-K*` test that would produce it, exactly as wave 3 had to withdraw every figure in its reports.
- THE RULE THIS WAVE EARNED: every behaviour regression the wave-3 review caught lived in a CALLER, not in the changed
  function. Here that means the disabled-logging path must have no side effects: a message lambda that is no longer
  evaluated must never have been carrying a state change or an expensive argument construction.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- Keep the public API source-compatible: every module calls FlashLog/FlashProbe. If a signature must change, add an
  overload and deprecate; never change behaviour of a log level that is enabled.
- Never log secrets (AGENTS section 24). Never weaken a crypto primitive to save an allocation: cache the provider, not
  the key material.
- Commit only to agent/opt-kernel, small commits with the finding ID. Logs only in logs/streams/kernel/
  (ADR 310-319, ERROR 951-960, EXP 120-124, tests PERF-K*).

VERIFY: :core:common:jvmTest, :core:security:jvmTest, :core:common:testAndroidHostTest, plus one host compile
(:desktop:compileKotlinJvm and :app:compileDebugKotlin) because both call this module. A microbenchmark note for the
regex and hash changes. Do not add a wall-clock budget assertion (ERROR-938: the gate runs on a saturated CPU). Then
the integration gate (plan section 6.3), with the counts in section 6.3.1 updated if `O27-06` changed what runs. The
integrator re-runs both gates on the merged tree afterwards: a "verified green" line in your report describes YOUR
tree, so also state what you did not run.

REPORT: per finding the change + measured before/after (allocs/op, ns/op, or a frame count), the gate commands and
their last lines, and what is left. Stop the daemon when the session ends.
```

### 11.S — swarm

```text
You are the SWARM (S) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-swarm -b agent/opt-swarm dev
    Copy-Item .\local.properties E:\FlashWork\opt-swarm\local.properties
    cd E:\FlashWork\opt-swarm
The main tree is the integrator's. Never copy keystore.properties or the .jks. No phone work unless the owner names you.

READ FIRST
1. AGENTS.md sections 6-13, 18, 23, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "S — swarm" (+ compounding.md cascade 5).
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O08-group-swarm.md, O02-heap-and-native-memory.md (O02-04/05),
   O25-algorithmic-complexity.md (O25-08), O23-storage-file-io.md (O23-07), O03-compose-stability.md (O03-15),
   O28-observability-benchmarks.md (O28-04), and docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md.

YOU OWN: core/swarm/**, core/engine/{SwarmHostBinding,RoomSwarmStateStore,NetworkSwarmTransport,GroupFileSender,
MessagingSwarmGroupContext}.kt, ui/chat/src/**/FlashSwarm*.kt.
YOU MUST NOT TOUCH: core/persistence (D owns SwarmContentEntity and the DAOs - ask), core/transfer (T), the rest of
ui/chat (U), any build.gradle.kts, the canonical logs (write logs/streams/swarm/).

DO IN THIS ORDER
O08-01 the O(P*N*W) piece scheduler -> bitset + rarest-first queue; O25-08 the discarded O(P*N) holder computation in
updateWaitReason; O08-07 the O(N^2) HAVE storm; O08-02 the redundant full-piece re-hash on serve; O08-04 per-piece
fsync + DAO amplification (caller side only); O08-05 the 4 Hz idle ticker; O08-06 thermal governor integration;
O08-08 tombstone retention + summary broadcasts; O02-04 Channel.UNLIMITED queues; O02-05 permanent tombstones;
O23-07 heap-wrapped NIO buffers in the piece storage; O03-15 the piece-map canvas allocations; O28-04 scheduler
telemetry.
DECIDED (ADR-232, do not record BLOCKED): O08-03 IS approved - age-weighted / deficit round-robin serve slots, no wire change,
proved with the multi-device run you owe anyway (PERF-S01). Say in the report what changed about who wins a slot and why.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- The swarm engine is sans-IO and property-tested: every scheduler change keeps its invariants and adds a property
  test. A tombstone/cancel path must never be weakened for speed.
- Commit only to agent/opt-swarm, small commits with the finding ID. Logs only in logs/streams/swarm/
  (ADR 320-329, ERROR 961-970, EXP 125-129, tests PERF-S*).

VERIFY: :core:swarm:jvmTest (incl. SwarmInteropTest - a rare flake: it PASSED in the 2026-10-30 baseline, so record a
failure rather than "fixing" it), and a
three-device swarm run for the throughput claim (PERF-S01). Then the integration gate (plan section 6.3).

REPORT: per finding the change + measured piece/s and CPU, the gate commands and their last lines, what is left.
Stop the daemon when the session ends.
```

### 11.R — radio

```text
You are the RADIO (R) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-radio -b agent/opt-radio dev
    Copy-Item .\local.properties E:\FlashWork\opt-radio\local.properties
    cd E:\FlashWork\opt-radio
The main tree is the integrator's. Never copy keystore.properties or the .jks. Do not touch the owner's radios or phone
unless you are explicitly given a device session.

READ FIRST
1. AGENTS.md sections 6-13, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "R — radio".
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O26-bluetooth-radio.md, plus docs/network/RADIO-WIRE-FORMAT.md,
   docs/network/RADIO-BLUETOOTH-CHANNELS.md, docs/network/RADIO-AUDIO.md and ADR-210 / ADR-225 / ADR-228 / ADR-229.

YOU OWN: core/radio/**, core/messaging/radio/**, ui/chat/src/**/radio/**, app/src/main/java/com/transfer/flash/radio/**,
desktop/src/**/radio*/**.
YOU MUST NOT TOUCH: the rest of core/messaging (D), the rest of ui/chat (U), the rest of app/ (G), any build.gradle.kts,
the canonical logs (write logs/streams/radio/). The wire format is not yours to change.

DO IN THIS ORDER
O26-01 the 250 ms semi-blocking read loop in KissTncDriver (14,400 wake-ups/hour) -> a bounded blocking read with a
heartbeat; O26-04 framing/AAD/serial buffer churn; O26-03 the unbounded pendingAcks and UNLIMITED channels (bound them,
keep the retry guarantee); O26-05 the up-to-4 s Thread.sleep() in Android connection probing; O26-07 the JCA provider
lookups + SPKI re-parsing in JdkRadioCrypto/JdkRadioSignatureScheme; O26-02 channel occupancy/goodput - MEASURE first, do
not guess 1200-baud numbers.
OBSOLETE: O26-06 (voice over the data channel) - ADR-210/ADR-225 put audio on the vendor SBC channel and REJ-06 refuses
the idea. Mark it, do not build it.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- The radio wire format (docs/network/RADIO-WIRE-FORMAT.md) and the golden-frame test must keep passing byte-for-byte.
  Error 870 lived in RadioSession anti-replay: any change there re-runs the anti-replay tests and says so.
- A radio optimisation must not make a delivery less reliable; lost-ACK handling is a correctness path, not overhead.
- Commit only to agent/opt-radio, small commits with the finding ID. Logs only in logs/streams/radio/
  (ADR 330-339, ERROR 971-980, EXP 130-134, tests PERF-R*).

VERIFY: :core:radio:jvmTest (the 250-test suite, golden frames included), :desktop:compileKotlinJvm,
:app:compileDebugKotlin. Device claims go to the backlog fragment (PERF-R01, and re-runs of RADCHAT-DEV03..07).

REPORT: per finding the change + measured wake-ups/goodput, the gate commands and their last lines, what still needs a
radio in hand. Stop the daemon when the session ends.
```

### 11.C — calls

```text
You are the CALLS (C) stream of the Flash performance-optimization programme.

STEP 0 — CREATE YOUR WORKTREE FIRST:
    cd C:\Users\KaliOxygen\Downloads\Flash
    git worktree add E:\FlashWork\opt-calls -b agent/opt-calls dev
    Copy-Item .\local.properties E:\FlashWork\opt-calls\local.properties
    cd E:\FlashWork\opt-calls
The main tree is the integrator's. Never copy keystore.properties or the .jks. No phone or desktop call session unless the
owner names you.

READ FIRST
1. AGENTS.md sections 6-13, 23, 24, 31, 35, 36.
2. docs/plans/OPTIMIZATION-PARALLEL-PLAN.md sections 2-8 and 9 "C — calls".
3. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md section 3 and section 8.
4. docs/audit/optimization-2026-10-23/: O11-calls-webrtc.md, O12-ptt-realtime-audio.md,
   O03-compose-stability.md (O03-18), O28-observability-benchmarks.md (O28-03), plus docs/calling/SCREEN-SHARE-DESIGN.md,
   docs/ui/calling-ui.md, third_party/webrtc-kmp/MODIFICATIONS.md and ADR-103.

YOU OWN: core/calling/**, core/ptt/**, ui/callui/**, third_party/webrtc-kmp/**, app/src/**/calling/**, app/src/**/ptt/**.
YOU MUST NOT TOUCH: ui/chat (U), core/network (N), app/ outside calling/ and ptt/ (G), desktop/ (E), any build.gradle.kts,
the canonical logs (write logs/streams/calls/).

DO IN THIS ORDER
O11-02 the camera capturer still running when the camera is off; O11-04 the O(N) stats allocation in mesh calls;
O11-06 occluded remote frames still rasterising; O11-07 uncapped screen-share encoding to four watchers; O11-05 the dead
SDP bitrate tuning (remove or make it real); O12-01 the 50 Hz playout StateFlow -> a fused rate; O12-02 ~550 allocs/sec in
the audio pipeline; O12-03 the 1 s TargetDataLine cold open (pre-warm); O12-04 the non-adaptive 120 ms jitter buffer;
O12-05 Android AudioTrack performance mode + attributes; O03-18 the call-dock BoxWithConstraints; O28-03 call telemetry.
DEVICE-GATED (do the code only if you can prove it without a phone, otherwise record BLOCKED): O11-01 1080p30 software
VP8 on desktop, O11-03 the unconditional VP8 SDP that strips MediaCodec, O12-06 PCM16 airtime.

RULES
- Build/test only through: cmd /c "tools\flash-gradle.cmd <tasks>" from your worktree root. Never gradlew.
- A change inside third_party/webrtc-kmp must be recorded in third_party/webrtc-kmp/MODIFICATIONS.md and needs the fork
  republished (ADR-103) before the apps can use it; say so in your report instead of bumping the version silently.
- PTT is PAIRED-only and the voice path must stay intelligible; never trade a drop-out for a lower bitrate without a
  measurement.
- Commit only to agent/opt-calls, small commits with the finding ID. Logs only in logs/streams/calls/
  (ADR 340-349, ERROR 981-990, EXP 135-139, tests PERF-C*).

VERIFY: :core:calling:jvmTest, :core:ptt:jvmTest, :ui:callui:jvmTest, :desktop:compileKotlinJvm,
:app:compileDebugKotlin, and put the call-quality checks in the backlog fragment (PERF-C01).

REPORT: per finding the change + measured CPU/allocs/jitter, the gate commands and their last lines, what still needs two
devices, and whether the webrtc fork needs republishing. Stop the daemon when the session ends.
```

---

## 12. Decisions — answered 2026-10-30 (ADR-232)

These were the §8 items that blocked work. The owner delegated them to the integrator's recommendation on 2026-10-30, so all
of them are now decided in **ADR-232**. A stream that reaches one of these **implements the answer**; it no longer records
`BLOCKED — owner decision`. The question text is kept, with the answer under it, so the reasoning stays readable.

| # | Finding | Answer | Who |
|---|---|---|---|
| 1 | `OPT-O01-01` splash | **Keep ADR-080.** No gate change; a shorter splash means a shorter choreography, never a dismissed one | nobody (recorded as "not doing") |
| 2 | `OPT-O06-03` FTS5 | **Stay on LIKE**; FTS5 only if a real device shows >~300 ms over ~100k messages | nobody now; D if the trigger fires |
| 3 | `OPT-O13-06` FGS | **Stop when idle** + one opt-in "Keep listening in the background" setting | G |
| 4 | `OPT-O06-10` PBKDF2 | **Keep 256k**; take only the `mmap_size` half | D (mmap part) |
| 5 | `OPT-O22-03` BouncyCastle | **Keep**, ADR-230 stands (pinned 1.81.1) | nobody (recorded) |
| 6 | `OPT-O04-01` history paging | **Approved**, and it must load around `targetMessageId` | D |
| 7 | `OPT-O09-04`/`O09-07` wire | **No wire change now**; N takes the allocation half (`O09-05`, `O09-06`, `O14-02`) | N |
| 8 | `OPT-O08-03` swarm fairness | **Approved** (age-weighted / deficit round-robin), proved by a multi-device run | S |
| 9 | `OPT-O22-01` per-ABI APKs (PERF-A04) | **Approved**: per-ABI **and** universal, with the release script collecting outputs and failing on a stale one | integrator |
| 9 | `OPT-O22-01` per-ABI APKs (PERF-A04) | **Approved and built 2026-10-31** (ADR-243): per-ABI **and** universal, the release script collects what `output-metadata.json` lists and fails on a stale, missing or duplicate-`versionCode` output | integrator |
| 11 | `PERF-A02` baseline profile | **Deferred** to when G touches startup | — |
| 12 | `PERF-A07` sleep-based tests | **D's first commit**; it is what allows 2 forks again | D |

The original questions, with their answers:

1. **Launch splash (`OPT-O01-01`).** Keep ADR-080 (the animation always plays, ~3.45 s) and let G trim only the engine
   wait, or reverse ADR-080 for a faster cold start? *Recommendation: keep the splash; trim the wait.*
   **Answer: keep.** The engine already runs underneath (`finishAt = engineSettled ? introEnd : max(introEnd, 6000)`), so
   nothing is gained by dismissing it; a cut ink stroke reads as a bug. Shorten the choreography if cold start ever matters.
2. **Search (`OPT-O06-03` FTS5).** Stay on LIKE + file names (ADR-218), or authorise FTS5 as a Room step 23 with a
   migration and a reindex? *Recommendation: stay; D's index work covers the reported slowness.*
   **Answer: stay.** Trigger: >~300 ms on a real device over ~100k messages, then FTS5 is its own item (step 23 + reindex).
3. **`OPT-O13-06`**: may the `connectedDevice` foreground service stop when no peer is connected (Play-policy clean,
   but a phone then cannot receive until it is opened)? **Answer: yes, stop when idle**, and add the opt-in setting so the
   behaviour is the user's choice rather than a silent policy violation.
4. **`OPT-O06-10`**: may the SQLCipher PBKDF2 iteration count be lowered (faster open, weaker key derivation)?
   **Answer: no.** Fix `mmap_size` instead; the iteration count protects the only copy of the chat history.
5. **`OPT-O22-03`**: remove BouncyCastle from the desktop (smaller installer, TLS certificate generation rewritten)?
   **Answer: no** (ADR-230). Every cheaper alternative gives every install the same TLS identity or needs `--add-exports`.
6. **`OPT-O04-01`**: page the conversation history (a behaviour change users see), or leave it?
   **Answer: page it**, and the pager must resolve `FlashBackStackState.targetMessageId` so search deep-links still land.
7. **`OPT-O09-04`/`O09-07`**: is a wire-format breaking change worth it for Base64 inflation and text framing, given
   both hosts must be updated together? **Answer: not now.** Three implementations plus a published library must move
   together; a wire v3 is a project with its own ADR, started only on a measured complaint.
8. **`OPT-O08-03`**: may the swarm change who wins a serve slot (fairness vs throughput)?
   **Answer: yes.** Starvation looks like "my file never downloads"; no wire change; prove it with the multi-device run.

Until an answer existed, a stream recorded `BLOCKED — owner decision`. That is no longer an acceptable outcome for these twelve
items: the answer exists, so a stream that skips one must say which ADR-232 clause it is skipping.


## 13. What the integrator does at each merge

1. `git -C C:\Users\KaliOxygen\Downloads\Flash merge --no-ff agent/opt-<stream>` (never from inside the worktree).
2. Fold `logs/streams/<stream>/*` into `logs/progress.md`, `logs/errors.md`, `logs/experiments.md`,
   `docs/decisions.md`, `docs/testing/PROBES.md` and `docs/testing/TEST-BACKLOG.md`.
3. Move every device/manual check the stream owes into `docs/testing/TEST-BACKLOG.md` with its `PERF-<x>NN` ID.
4. Run the integration gate (§6.3) on the merged tree and record the result in §10.
5. Delete the worktree only after the merge is green: `git worktree remove E:\FlashWork\opt-<stream>`. Keep the branch.
6. Update `logs/handoff.md` with the merged stream, the gate result and the next wave.
