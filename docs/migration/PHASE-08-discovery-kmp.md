# Phase 08 — KMP conversion: `core:discovery`

**Blocked by:** Phase 06 (the pilot — it discovers and records the `KMP_*` facts this phase
copies). Depends only on `:core:common`, which Phase 06 already converted, so this phase can
run as soon as 06 is OPEN. It does **not** need Phase 07, but Phase 07 is the closer template:
copy `core/security/build.gradle.kts`, not the AGP-era text of the file this replaces.
**Risk: MEDIUM.** No behaviour change, no wire-format change, no crypto. 16 production +
7 test files. The ways to get this wrong are (a) misplacing a file across source sets,
(b) the Phase-06 silent-test-loss trap, and (c) — new under D1 = B — changing the *semantics*
of `CompositeDiscovery`'s locking while replacing `kotlin.synchronized`. All three are covered
by explicit steps.
**Decisions:** none block this phase. `D6` (desktop discovery — JmDNS) is **Phase 14**, not
here; this phase only *creates the seam* (`FlashRadioTransport`) that Phase 14's desktop
transport will implement. Do not implement any desktop discovery here.

> ## REWRITTEN 2026-09-05 for D1 = B
>
> The previous text of this file was **written for D1 = A** (the
> `commonMain → jvmAndAndroidMain → {androidMain, jvmMain}` model) and carried its own
> stop-gate: *"If `DECISIONS.md` records D1 = B, STOP: under B the two `jvmAndAndroidMain`
> files (`CompositeDiscovery`, `FlashPeerGroupSession`) each need an `expect`/`actual` seam
> for their `java.*` calls, which this phase does not describe."*
>
> D1 **is** B (`DECISIONS.md`, reaffirmed by the 2026-09-03 amendment at the top of
> `CONVENTIONS.md`), so that gate fired and this file has been rewritten to describe those
> seams. What changed:
>
> - **`jvmAndAndroidMain` is gone.** Nothing is created; CONVENTIONS R5 forbids it.
> - **`CompositeDiscovery` and `FlashPeerGroupSession` go to `commonMain`**, with their four
>   `java.*` dependencies removed — three by an identity rewrite, one by a seam.
> - **This phase now adds one `expect` classifier and two `actual`s** (`PlatformLock`), so
>   `-Xexpect-actual-classes` is required and the old "add zero `expect`/`actual`" rule is
>   void. `jvmMain` gets **one** file, not zero — still no desktop *discovery* (that is
>   Phase 14).
> - **This phase now adds a `commonTest` suite**, because CONVENTIONS R3.1 (written by
>   Phase 07) requires that any phase writing an `actual` execute it on both targets rather
>   than merely compiling it.
>
> Everything else in the old file — the placement of the 10 pure files, the 4 `nsd/` files,
> the two dead `androidx.*` deps, the R8 protection of `TxtCodec`, the "don't delete
> `NsdFlashDiscovery`", the "don't touch `android.util.Log`" rules — was independent of D1
> and is preserved.

## What this phase is actually for

Convert `:core:discovery` from `com.android.library` to
`com.android.kotlin.multiplatform.library` + `jvm()`, place its 16 source files into the
KMP source sets by the placement rule, and prove that:

1. the shared discovery contract (`FlashDiscovery`, `FlashRadioTransport`, the policies, the
   `TxtCodec` wire format) **and the whole fan-in layer** (`CompositeDiscovery`,
   `FlashPeerGroupSession`) compile for the desktop `jvm()` target from `commonMain`,
2. the Android NSD implementation (the 4 `nsd/` files) stays isolated in `androidMain`, and
3. Android still builds and every existing test still runs (same count as baseline, plus the
   new `commonTest` suite).

The strategic payoff: `FlashRadioTransport` becomes a `commonMain` interface **and so does its
only consumer**, `CompositeDiscovery`. In Phase 14 a desktop JmDNS transport is written in
`jvmMain` as a plain implementation of that interface — no engine change needed, because the
engine already talks to the interface, not to `NsdManager`. Under D1 = A the composite would
have sat in a JVM-only tier and Kotlin/Native would never have got it; under B it is genuinely
shared, which is the point.

## Why `core:discovery` is a safe early conversion

Verified against the module (grep with R11 exclusions, and reading `build.gradle.kts`):

- **Dependency leaf.** Its only project dependency is `api(project(":core:common"))`, which
  Phase 06 already converted. Nothing here waits on Phase 07.
- **10 of 16 production files are already pure Kotlin** — models, policies, the directory,
  the transport interface, and the `TxtCodec` wire format — so they go straight to
  `commonMain` with no edit at all.
- **Only 2 files touch `java.*`**, and between them only **four** call sites do
  (`java.util.Locale` + `System.currentTimeMillis` in `CompositeDiscovery`,
  `java.util.concurrent.ConcurrentHashMap` in `FlashPeerGroupSession`, plus 18
  `kotlin.synchronized` blocks in `CompositeDiscovery` that no import reveals).
- **All `android.*` is confined to 4 files under `nsd/`** — the NSD adapter. They already
  form a natural `androidMain` cluster.
- **No `AndroidManifest.xml`, no `res/`, no `assets/`, no declared permissions** under
  `core/discovery/src` (verified: `find` returns no non-`.kt` file at all). Nothing
  Android-resource-bound to migrate, so the Compose-resources gotcha and the manifest-merge
  gotcha cannot bite.
- **No KSP, no Room, no Hilt, no Compose.** Plugin swap + source move + four small rewrites.

## A finding you must act on first — two dead dependencies

`core/discovery/build.gradle.kts` declares:

```kotlin
implementation(libs.androidx.core.ktx)
implementation(libs.androidx.lifecycle.runtime.ktx)
```

`core/discovery` contains **no `androidx.*` reference at all**. Verify:

```bash
grep -rn --include=*.kt "androidx" core/discovery/src
```

Expected: **no output.** If confirmed, both dependencies are unused. **Delete them** (do not
relocate them to `androidMain` — relocating a dead dependency just moves noise). Record that
you removed them. This is the discovery analogue of the `androidx.core.ktx` removal Phase 06
did on `core:common`, and it is what makes the module's dependency list honest.

> Divergence from Phase 07: in `core:security` the two `androidx.*` deps were **relocated**
> to `androidMain` because that module's `androidMain` files use them. Here they are
> **provably unused**, so they are **deleted**. Do not blindly copy Phase 07's "relocate"
> instruction — the grep above decides.

## Placement table — all 16 production files, D1 = B

Built by grepping every production file for `^import android.`, `^import androidx.`,
`^import java.`/`javax.`, `System.`, `synchronized`, `@Volatile`, `Charsets`, `String(`,
`.uppercase(`, and reading each file's role. **Re-run the Step 1 greps to confirm before you
move anything** — do not trust this table blindly.

Base path: `core/discovery/src/main/java/com/transfer/flash/core/discovery/`

### commonMain — 12 of 16 (10 move untouched, 2 are rewritten)

| File | Role | Edit needed |
|---|---|---|
| `FlashDiscovery.kt` | Public facade interface | none |
| `FlashDiscoveryState.kt` | State model | none |
| `FlashDiscoveredEndpoint.kt` | Endpoint model | none |
| `core/FlashDiscoveryMode.kt` | Mode enum | none |
| `core/DiscoveryModePolicy.kt` | Mode-selection policy | none |
| `core/DiscoveryRetryPolicy.kt` | Retry/backoff policy | none |
| `core/TxtCodec.kt` | **WIRE FORMAT (R8)** — keys `device_id/name/model/proto/caps/fp8` | **none. Move-only.** |
| `core/FlashRadioTransport.kt` | **THE SEAM** — `FlashRadioTransport` + `FlashAdvertisedIdentity` + `FlashTransportEvent` | none |
| `core/EndpointDirectory.kt` | Directory interface | none |
| `core/StandardEndpointDirectory.kt` | Directory impl | none |
| `core/CompositeDiscovery.kt` | Multi-radio fan-in | **3 rewrites — see below** |
| `group/FlashPeerGroupSession.kt` | Peer-group session state (`internal`) | **1 rewrite — see below** |

### commonMain — 1 new file (the seam this phase adds)

| File | Contents |
|---|---|
| `concurrent/PlatformLock.kt` | `internal expect class PlatformLock() { fun <T> withLock(block: () -> T): T }` |

### androidMain — 4 moved + 1 new

| File | Triggering `android.*` imports (verified) | Role |
|---|---|---|
| `nsd/NsdTransport.kt` | `android.content.Context`, `android.net.nsd.NsdManager`, `android.net.nsd.NsdServiceInfo`, `android.net.wifi.WifiManager`, `android.util.Log` | **the production `FlashRadioTransport` impl** (~1400 lines); contains the nested `NsdManagerBridge` Android isolation point |
| `nsd/NsdResolveQueue.kt` | `android.net.nsd.NsdManager`, `android.net.nsd.NsdServiceInfo`, `android.util.Log` | serializes NSD resolve calls (API constraint) |
| `nsd/NsdApiLevel.kt` | `android.os.Build` | API-level gating helper |
| `nsd/NsdFlashDiscovery.kt` | same set as `NsdTransport` | **legacy standalone, currently unused** — see "do NOT delete it here" |
| `concurrent/PlatformLock.android.kt` | *(new)* | `internal actual class PlatformLock` over `synchronized(monitor)` |

`NsdTransport.kt:39` and `NsdFlashDiscovery.kt:21` also import `java.nio.charset.StandardCharsets`,
and all four use `kotlin.synchronized` and `@Volatile` freely. All of that is legal in
`androidMain`, so it is irrelevant to their placement and **none of these four files is edited**.

### jvmMain — 1 new file, and nothing else

| File | Contents |
|---|---|
| `concurrent/PlatformLock.jvm.kt` | `internal actual class PlatformLock` — byte-identical body to the Android one |

That duplication is **intentional**, not an oversight: CONVENTIONS R5 under D1 = B forbids a
shared JVM-only parent source set, so one-line `actual`s are duplicated on purpose.

**There is still no desktop discovery in this phase.** `PlatformLock.jvm.kt` is a lock, not a
transport. If you find yourself writing anything that mentions JmDNS, mDNS, sockets or
`DatagramSocket` in `jvmMain`, stop — that is Phase 14 (R1).

## The four rewrites — each one must be an identity

This is the only part of the phase that changes source content. Every change below either is
a documented identity on both JVM targets or is a seam that preserves semantics exactly. Nothing
here is allowed to alter observable behaviour: all 97 existing tests must pass **unmodified**,
which is the check that keeps this honest.

### Rewrite 1 — `CompositeDiscovery`: `uppercase(Locale.ROOT)` → `uppercase()`

```kotlin
// core/CompositeDiscovery.kt:18   delete
import java.util.Locale

// core/CompositeDiscovery.kt:139
-    val idx = PRIORITY_ORDER.indexOf(transportName.uppercase(Locale.ROOT))
+    val idx = PRIORITY_ORDER.indexOf(transportName.uppercase())
```

`String.uppercase()` (Kotlin 1.5+) is defined as locale-**independent** case mapping, and its
JVM implementation is literally `(this as java.lang.String).toUpperCase(Locale.ROOT)`. So this
is a compile-time-visible identity, not a behaviour-preserving approximation. It matters here
because `priorityRank` feeds cross-radio dedup: had the old code used the *default* locale, the
Turkish dotless-ı would have broken `"WIFI_DIRECT"` matching on a `tr-TR` device. It did not,
and the replacement keeps that property while dropping the `java.util` import.

### Rewrite 2 — `CompositeDiscovery`: the default clock

```kotlin
// core/CompositeDiscovery.kt:86
-    private val clock: () -> Long = { System.currentTimeMillis() },
+    private val clock: () -> Long = { SystemTimeSource.nowMs() },
```

with `import com.transfer.flash.core.common.time.SystemTimeSource`.
`SystemTimeSource` is `:core:common`'s public `commonMain` object; `nowMs()` delegates to the
`internal expect fun currentTimeMillisPlatform()`, whose `androidMain` **and** `jvmMain`
`actual`s are both exactly `System.currentTimeMillis()` (read them — they are one line each).
Identity on both targets.

The parameter's **type and position are unchanged**, so this is not an API change: it is a
default-argument body. The three test call sites and the two production call sites
(`core/engine/.../Flash.kt:161`, `app/.../DiscoveryEngineHolder.kt:296`) all use named
arguments and none of them needs an edit. Verify that claim rather than trusting it —
`grep -rn "CompositeDiscovery(" core/ ui/ app/ sample/`.

### Rewrite 3 — `CompositeDiscovery`: 18 `synchronized(lock)` → `PlatformLock`

`kotlin.synchronized` is JVM-only (CONVENTIONS R6), and no import reveals it, so grep for the
call rather than for an import. `CompositeDiscovery` has **18** sites; the exact line numbers
before the move are 263, 314, 341, 380, 398, 415, 437, 465, 469, 479, 545, 552, 568, 586, 597,
604, 624, 692.

```kotlin
-    private val lock = Any()
+    private val lock = PlatformLock()
```

then mechanically `synchronized(lock) { … }` → `lock.withLock { … }`, with
`import com.transfer.flash.core.discovery.concurrent.PlatformLock`.

**The one site that is not mechanical is `applySighting` (pre-move line 604).** It contains a
*non-local* `return`:

```kotlin
private fun applySighting(transport: FlashRadioTransport, endpoint: FlashDiscoveredEndpoint) {
    synchronized(lock) {
        …
        when (directoryFor(transport.transportName).applySeen(endpoint, clock())) {
            is EndpointDirectory.Diff.Unchanged -> return   // ← non-local
            else -> Unit
        }
        …
    }
}
```

`kotlin.synchronized` is an **`inline`** function, so that `return` leaves `applySighting`.
`PlatformLock.withLock` is a **member of an `expect class` and therefore cannot be `inline`**,
so the same code will not compile. Write `return@withLock` instead. That is behaviour-identical
here **only because the `withLock` call is the entire function body** — nothing follows it, so
returning from the lambda and returning from the function do the same thing. Check that
condition at every site you convert; do not apply the substitution blind. (This is the same
trap Phase 06 hit converting `FlashLogger.recent()`.)

Two sites sit inside `suspend` functions (`aggregate`, pre-move line 437, and
`watchdogBrowsing`, 586 + 597). Losing `inline` means a suspend call inside the lambda would no
longer compile — verified: neither block contains one. If a later phase adds a `suspend` call
inside a `withLock` block it will fail loudly at compile time, which is the correct outcome:
holding a lock across a suspension point is a bug regardless of platform.

### Rewrite 4 — `FlashPeerGroupSession`: `ConcurrentHashMap` → index-disjoint array

> **Amended during execution.** This section originally specified `mutableMapOf` guarded by a
> local `Mutex`. That works, but it adds a *suspending* call on the child's completion path — and
> `runSend` deliberately ends with `currentCoroutineContext().ensureActive()` so a torn-down
> session never writes a terminal state. Introducing a suspension point immediately after that
> check gives cancellation a second, narrower window to intervene where the original plain map
> write had none. Giving each child its **own index** avoids the question entirely: the writes are
> disjoint, so no mutual exclusion is needed at all, and `parent.join()` remains the single
> happens-before edge for the read. The rationale paragraphs below are kept because they document
> why the shared-key map needed a lock in the first place; the shipped code is the array form.

`sendToAll` collects per-peer results from parallel child coroutines:

```kotlin
-        val results = ConcurrentHashMap<String, Boolean>(targets.size)
+        val results = arrayOfNulls<Boolean>(targets.size)
         val parent = scope.launch(start = CoroutineStart.LAZY) {
-            targets.forEach { endpointId ->
-                launch { results[endpointId] = runSend(endpointId, payloadSizeBytes) }
+            targets.forEachIndexed { index, endpointId ->
+                launch { results[index] = runSend(endpointId, payloadSizeBytes) }
             }
         }
         …
-        return targets.all { results[it] == true }
+        return results.all { it == true }
```

and `import java.util.concurrent.ConcurrentHashMap` is deleted. `targets` is
`_peerStates.value.filterValues { … }.keys.toList()` — distinct keys by construction — so index ↔
endpointId is a bijection and `results.all { it == true }` is the same predicate over the same
values as `targets.all { results[it] == true }`.

Why a lock was considered at all: `parent.join()` establishes happens-before for the *read*, so
visibility alone would be satisfied either way, but parallel writes to a shared **key** would be
an unsynchronised structural mutation of a `HashMap` — the exact race `ConcurrentHashMap` was
there to prevent. Disjoint indices into a fixed-size array have no structural mutation to race
on. Why not `MutableStateFlow<Map<…>>` + `update`: it works, but it allocates a flow per call and
reads less like the rest of the file.

Behaviour is preserved at both edges. On the write side, if `runSend` throws (only
`CancellationException` can — every other `Throwable` is already caught inside it) the slot stays
`null`, exactly as `results[id] = runSend(…)` left the key absent. On the read side, `null == true`
is `false`, matching how a missing key read. The dropped `ConcurrentHashMap(targets.size)`
initial-capacity argument is a performance hint with no semantics.

## Why `PlatformLock` is duplicated instead of reused from `:core:common`

`:core:common` already has exactly this seam at
`core/common/src/commonMain/kotlin/com/transfer/flash/core/common/concurrent/PlatformLock.kt`,
and `:core:discovery` depends on `:core:common` with `api`. It still cannot be reused, because
that declaration is **`internal`** — and deliberately so. Its KDoc records the Phase 06
decision verbatim:

> `internal`: this is module-private plumbing, not published API. Anything in the repo that
> needs a lock across modules should keep using coroutines primitives instead […]

Promoting it to `public` would (a) overturn a recorded decision from another phase, (b) add a
declaration to `core-common`'s published ABI under strict `explicitApi()` (ADR-023), and (c)
edit a second module — all three outside this phase's scope (R1, R4). So this phase copies the
three-file seam into `:core:discovery` under its own package,
`com.transfer.flash.core.discovery.concurrent`, `internal` again.

That is the accepted cost of D1 = B, but it is a cost that will now recur: phases 09–12 convert
`persistence`, `network`, `transfer`, `messaging` and `engine`, and any of them holding a
`synchronized` block will need a fourth, fifth, sixth copy. **Record that under Known issues**
with a recommendation — a single `@FlashInternalApi`-annotated `public` lock in `:core:common`,
introduced by its own small phase, would collapse all of them. Do not do it here.

## The `FlashRadioTransport` seam — the whole point of the module split

`core/FlashRadioTransport.kt` is a **`commonMain` interface**. Its shape (verify by reading
the file — do not edit it):

- `transportName`
- `events: Flow<FlashTransportEvent>`
- `startAdvertising(…)`, `startBrowsing()`, `restartBrowsing()`, `stop()`, `setMode(…)`

`NsdTransport` (androidMain) implements it using `NsdManager`. In **Phase 14** a desktop
JmDNS-backed transport is written in `jvmMain` implementing the **same** interface. Because the
interface, its event types (`FlashTransportEvent`, `FlashAdvertisedIdentity`) **and its
consumer** (`CompositeDiscovery`) all live in `commonMain` after this phase, both
implementations satisfy the same contract and neither the discovery facade nor the engine ever
learns which platform it is on.

The `NsdManagerBridge` nested inside `NsdTransport` is the Android-only isolation point that
makes `NsdTransportLogicTest` runnable on a plain JVM (it passes `context = null` + a
`FakeBridge`). Leave that structure intact; it is why the Android transport's *logic* is
testable without a device, and Phase 14 mirrors the pattern for JmDNS.

## The `android.util.Log` question — do NOT migrate it here

Three `nsd/` files import `android.util.Log` (`NsdTransport`, `NsdResolveQueue`,
`NsdFlashDiscovery`). All three are `androidMain`, where `android.*` is fully available, so
their `Log` calls are **correct as-is** and require no change for KMP placement. Do **not**
route them through `FlashLog` in this phase — that would be a Phase 03 logging change, and
R1 forbids "also fixing" things outside the phase. If the project intended zero direct
`android.util.Log` anywhere, record it under **Known issues** as a Phase 03 gap and move on.

## `NsdFlashDiscovery.kt` — legacy, unused, but do NOT delete it here

The inventory flags `nsd/NsdFlashDiscovery.kt` as a legacy standalone discovery path that is
currently unused. Deleting dead code is a **Phase 02 / hygiene** concern, not a KMP-placement
concern. Under R1, this phase **moves it to `androidMain` unchanged**. If you want it gone, note
it under **Known issues**; do not delete it in this commit (a deletion mixed into a source-set
migration makes `git revert` ambiguous — R4).

## Tests: 7 files move unchanged to `androidHostTest`, plus a new `commonTest` suite

**The 7 existing test files move byte-for-byte unchanged** to `androidHostTest` — no `import`
rewrite, no package change, no assertion touched (R1: this phase is a source-set move, not a
test rewrite). The baseline count comparison is only valid if they are byte-identical.

Why `androidHostTest` and not `commonTest`: they are JUnit 4 (`org.junit.Assert.*`) and they
import `java.util.concurrent.{CompletableFuture, ConcurrentHashMap, CountDownLatch, TimeUnit}`
and `java.util.concurrent.atomic.*` for deterministic pacing. `commonTest` compiles for every
target and cannot see any of that. Rewriting them onto `kotlin.test` + coroutines-test is a
later, opt-in exercise, explicitly out of scope here.

**A new `commonTest` suite is required** — not optional. This phase writes two `actual`s, and
CONVENTIONS R3.1 says: *"Any phase that writes an `actual` should put at least one behavioural
assertion in `commonTest` so both platforms run it."* Without it the `jvmMain` `actual` is only
ever *compiled*, never *executed*, which is the position `:core:common`'s three JVM `actual`s
are still in. Two small files, `kotlin.test` plus `kotlinx-coroutines-test`, no `java.*`:

> **Amended during execution.** This section originally specified four thread-free `PlatformLock`
> invariants and a separate three-assertion `PriorityRankTest`, on the assumption that *"contention
> itself is not assertable in `commonTest` — that needs threads"*. That assumption is wrong:
> `runTest` + `withContext(Dispatchers.Default)` gives real parallelism on both current targets
> from common code, so mutual exclusion **is** assertable and is now asserted. The total is still
> 7 tests; the split moved to 3 + 4 and the second file grew to cover Rewrite 2 as well, so it is
> named for the class it tests rather than for one method.

`src/commonTest/kotlin/…/concurrent/PlatformLockTest.kt` — 3 tests:

1. `withLock` returns the block's value (and the lock is reusable afterwards).
2. The lock is released after a **throwing** block, and the exception propagates unchanged — a
   second `withLock` on the same instance then succeeds instead of deadlocking.
3. **Contention**: 8 coroutines on `Dispatchers.Default` each increment a shared `var` 5 000 times
   under the lock; the total must be exactly 40 000. Unsynchronised, this loses updates on any
   multicore JVM, so it is a real mutual-exclusion assertion rather than a smoke test — and it is
   the only test in the repo that makes one.

Do **not** assert re-entrancy. It happens to hold on both JVM targets (`synchronized` is
reentrant) but is not part of the seam's contract, and a future Kotlin/Native `actual` need not
provide it.

`src/commonTest/kotlin/…/core/CompositeDiscoveryCommonTest.kt` — 4 tests covering Rewrites 1 and 2
directly, on both targets: every known name ranks by its `PRIORITY_ORDER` index for upper-, lower-
and mixed-case input; unknown names (including `""` and `"lan "`) rank `PRIORITY_ORDER.size`;
`SystemTimeSource.nowMs()` returns epoch millis; and it is non-decreasing across two reads. The
clock assertions are on `SystemTimeSource` directly because `CompositeDiscovery` exposes no way to
read its `clock` back — that covers the seam Rewrite 2 introduced, not the wiring of the default
argument, which is compile-visible in the constructor. Say so in the log rather than implying the
suite exercises the default through the class.

Both files run **twice**: once inside `testAndroidHostTest` (which compiles `commonTest`
alongside `androidHostTest`) and once as `jvmTest`. So expect
`testAndroidHostTest` = 97 + 7 = **104** and `jvmTest` = **7**, i.e. +14 to the repo total. Say
both numbers in the log; a single total hides which target ran.

## Build script rewrite — `core/discovery/build.gradle.kts`

Copy the **shape** from `core/security/build.gradle.kts` (Phase 07), which is the closest
precedent: same plugin pair, same `explicitApi()`, same `-Xexpect-actual-classes` need, same
`commonMain`/`commonTest`/`androidHostTest`/`jvmTest` tiering. Do not re-derive the DSL
spellings; they were read off the AGP 9.3.1 API surface in Phase 06 and are recorded there.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    explicitApi()                                    // ADR-023. Must stay.

    compilerOptions {
        // PlatformLock is an expect CLASS (it carries per-platform state), and
        // expect/actual classifiers are still Beta — KT-61573. Same reason as
        // :core:common and :core:security.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.transfer.flash.core.discovery"
        compileSdk = 35
        minSdk = 24                                  // NOT inside defaultConfig any more

        // Was `defaultConfig { consumerProguardFiles("consumer-rules.pro") }`.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }
        // Replaces `buildTypes { release { … } }` — the target is variant-free.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }
        // Replaces `compileOptions { sourceCompatibility/targetCompatibility }`.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        // ***** Omit this and all 7 test files stop compiling AND running while the
        // build still reports SUCCESS. Step 7 exists to catch that. *****
        withHostTest { }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // Public API returns kotlinx Flow/StateFlow (discoveredEndpoints, state,
            // mergedEvents), so coroutines must be `api`: `implementation` would keep those
            // return types off a downstream consumer's compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        // The two androidx.* deps that used to be here are DELETED, not relocated —
        // grep proves zero androidx references in this module. No androidMain block.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Added during execution: PlatformLockTest's contention case needs `runTest`,
            // the only way to launch coroutines from a non-suspend test function in common
            // code. Existing catalog alias, pinned at the same 1.10.2 as coroutines-core,
            // so no version moves (R10).
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

publishing {
    publications {
        // KMP creates its own publications (root metadata + one per target) whose default
        // artifactIds derive from the Gradle project name `discovery`; rename in place to
        // keep the coordinates 1.1.0 consumers already resolve.
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("discovery", "core-discovery")
        }
    }
}
```

Deletions from the old file, and why:

- `alias(libs.plugins.android.library)` → replaced (incompatible with KMP under AGP 9).
- `buildTypes { release { … } }` → **deleted**; no build variants exist. Its
  `proguardFiles(getDefaultProguardFile(…), "proguard-rules.pro")` had no effect anyway
  (`isMinifyEnabled = false`), and `proguard-rules.pro` holds one comment line. Leave both
  `.pro` files on disk untouched.
- `compileOptions { VERSION_11 }` → replaced by the two `compilerOptions { jvmTarget }` blocks.
- `defaultConfig { testInstrumentationRunner }` → moved into `withDeviceTest { }`.
- `publishing { singleVariant("release") { withSourcesJar() } }` → **deleted** (no variants).
- `register<MavenPublication>("release") { … from(components["release"]) }` → **deleted**; KMP
  creates its own publications, renamed by the `configureEach` block.
- `implementation(libs.androidx.core.ktx)` + `implementation(libs.androidx.lifecycle.runtime.ktx)`
  → **deleted** (unused; verified by grep).
- `testImplementation(libs.junit)` → becomes `androidHostTest` + `jvmTest` dependencies.
- trailing `kotlin { explicitApi() }` → folded into the single `kotlin { }` block.

## Steps

### Step 1 — Preconditions. Verify, do not assume.

Confirm **Phase 06 and Phase 07 are OPEN** in `logs/migration.md` and that **D1 = B** in
`DECISIONS.md`. If D1 is `_pending_`, STOP — the agent may not pick it. If it reads A, this
rewritten file is the wrong one; use the git history.

```bash
git status --short
```
Must be clean. This phase does `git mv` on 23 files; do not mix it with other work.

```bash
grep -rn --include=*.kt -E "^import android\." core/discovery/src/main
```
Expected: matches in **exactly** four files — `nsd/NsdTransport.kt`, `nsd/NsdFlashDiscovery.kt`,
`nsd/NsdResolveQueue.kt`, `nsd/NsdApiLevel.kt`. Any **other** file means the placement table is
stale — stop and re-classify it.

```bash
grep -rn --include=*.kt "androidx" core/discovery/src
```
Expected: **no output** (confirms both dead deps can be deleted).

```bash
grep -rnE "^import (java|javax)\.|System\.currentTimeMillis|\bsynchronized\b|Charsets\.|String\.format|\.uppercase\(" --include=*.kt core/discovery/src/main
```
Expected: hits **only** in `core/CompositeDiscovery.kt`, `group/FlashPeerGroupSession.kt`, and
the four `nsd/` files. A hit in any of the other 10 files means it is not pure and the table is
wrong — re-classify it before moving anything, and record the correction.

### Step 2 — Record the test baseline (before touching the build file)

```bash
./gradlew :core:discovery:testDebugUnitTest --no-configuration-cache --rerun-tasks --console=plain
```

`--rerun-tasks` matters: an `UP-TO-DATE` task leaves stale XML on disk and you would be
recording last week's number. Then tally the XML, not the HTML:

```bash
grep -ho 'tests="[0-9]*"' core/discovery/build/test-results/testDebugUnitTest/*.xml
```

Record the per-class table and the total as `DISCOVERY_TEST_BASELINE`. If this task fails to
run *before* you touch anything, stop — you cannot prove non-regression against a baseline you
never captured.

### Step 3 — Rewrite `core/discovery/build.gradle.kts`, alone

Replace the file with the target script above. **Commit nothing else in this step** and do not
touch any other module's build file (R4). Leave `consumer-rules.pro` and `proguard-rules.pro`
untouched.

### Step 4 — Move production sources into KMP source sets (`git mv`)

Run from the repo root in bash. `git mv` (never copy+delete) so history and `git status` stay
legible. The package path is unchanged, so **no import in any other module changes**.

```bash
P=com/transfer/flash/core/discovery
SRC=core/discovery/src/main/java/$P
CM=core/discovery/src/commonMain/kotlin/$P
AM=core/discovery/src/androidMain/kotlin/$P
```

**Group 1 — 12 files → `commonMain`** (the 10 pure ones plus the two that Step 6 rewrites;
move first, edit after, so `git` records a rename rather than a delete+add):

```bash
mkdir -p "$CM/core" "$CM/group" "$CM/concurrent"
git mv "$SRC/FlashDiscovery.kt"                 "$CM/"
git mv "$SRC/FlashDiscoveryState.kt"            "$CM/"
git mv "$SRC/FlashDiscoveredEndpoint.kt"        "$CM/"
git mv "$SRC/core/FlashDiscoveryMode.kt"        "$CM/core/"
git mv "$SRC/core/DiscoveryModePolicy.kt"       "$CM/core/"
git mv "$SRC/core/DiscoveryRetryPolicy.kt"      "$CM/core/"
git mv "$SRC/core/TxtCodec.kt"                  "$CM/core/"
git mv "$SRC/core/FlashRadioTransport.kt"       "$CM/core/"
git mv "$SRC/core/EndpointDirectory.kt"         "$CM/core/"
git mv "$SRC/core/StandardEndpointDirectory.kt" "$CM/core/"
git mv "$SRC/core/CompositeDiscovery.kt"        "$CM/core/"
git mv "$SRC/group/FlashPeerGroupSession.kt"    "$CM/group/"
```

**Group 2 — 4 `android.*` files → `androidMain`:**

```bash
mkdir -p "$AM/nsd" "$AM/concurrent"
git mv "$SRC/nsd/NsdTransport.kt"      "$AM/nsd/"
git mv "$SRC/nsd/NsdResolveQueue.kt"   "$AM/nsd/"
git mv "$SRC/nsd/NsdApiLevel.kt"       "$AM/nsd/"
git mv "$SRC/nsd/NsdFlashDiscovery.kt" "$AM/nsd/"
```

Confirm the old tree is empty, then let `git mv` have removed the directories:

```bash
find core/discovery/src/main -type f
```
Expected: **no output**. If anything remains, it was not in the table — stop and classify it.

### Step 5 — Move the 7 test files into `androidHostTest` (`git mv`, unmodified)

```bash
TST=core/discovery/src/test/java/$P
HT=core/discovery/src/androidHostTest/kotlin/$P
mkdir -p "$HT/core" "$HT/group" "$HT/nsd"

git mv "$TST/FlashDiscoveryModelTest.kt"            "$HT/"
git mv "$TST/core/DiscoveryRetryPolicyTest.kt"      "$HT/core/"
git mv "$TST/core/StandardEndpointDirectoryTest.kt" "$HT/core/"
git mv "$TST/core/TxtCodecTest.kt"                  "$HT/core/"
git mv "$TST/core/CompositeDiscoveryTest.kt"        "$HT/core/"
git mv "$TST/group/FlashPeerGroupSessionTest.kt"    "$HT/group/"
git mv "$TST/nsd/NsdTransportLogicTest.kt"          "$HT/nsd/"
find core/discovery/src/test -type f
```

The `find` must print **nothing**. If a file remains, a test was added since this inventory:
classify it (almost certainly `androidHostTest` too), move it, and note the addition in the log.
Do not leave it behind — that is silent-test-loss trap #2.

### Step 6 — Apply the four rewrites and write the three new seam files

Rewrites 1–4 exactly as specified above, then:

- `commonMain/…/concurrent/PlatformLock.kt` — the `expect` classifier.
- `androidMain/…/concurrent/PlatformLock.android.kt` — `actual`, `synchronized(monitor)`.
- `jvmMain/…/concurrent/PlatformLock.jvm.kt` — `actual`, byte-identical body.

R7: the visibility modifier must be present and **identical** on the `expect` and both
`actual`s (`internal`). Then write the two `commonTest` files.

Sanity-check the tree before building:

```bash
find core/discovery/src -type f -name '*.kt' | sort
```
Expected **28** files: 13 `commonMain` (12 moved + `PlatformLock.kt`), 5 `androidMain`
(4 moved + `PlatformLock.android.kt`), 1 `jvmMain`, 7 `androidHostTest`, 2 `commonTest`.

## Verification gates — seven, all must pass

### Gate 1 — the desktop target compiles (the load-bearing check)

```bash
./gradlew :core:discovery:compileKotlinJvm --no-configuration-cache --console=plain
```

The `jvm()` target has **no `android.jar` on its compile classpath**, so a green
`compileKotlinJvm` is what certifies `commonMain` is genuinely free of Android APIs — including
`CompositeDiscovery` and `FlashPeerGroupSession`, which under D1 = A would never have been
compiled without it. It certifies **nothing about `java.*`** (CONVENTIONS R6.1); that is Gate 6.

If it fails on an unresolved `android.*` symbol, a `commonMain` file still references Android:
push the offender to `androidMain` and record it. If it fails on `java.*`, a rewrite was missed.

### Gate 2 — `compileAndroidMain`

```bash
./gradlew :core:discovery:compileAndroidMain --no-configuration-cache --console=plain
```

Proves the four `nsd/` files and the Android `actual` still resolve.

### Gate 3 — the Android host tests; the count MUST equal baseline + `commonTest`

```bash
./gradlew :core:discovery:testAndroidHostTest --no-configuration-cache --console=plain
grep -ho 'tests="[0-9]*"' core/discovery/build/test-results/testAndroidHostTest/*.xml
```

Expected: **104** = `DISCOVERY_TEST_BASELINE` (97) + the 7 `commonTest` tests, zero failures,
**and the per-class table for the 7 original files identical to Step 2's**. Compare per class,
not just the total — a total that still matches while one class silently stopped running is
exactly the failure mode R3 exists to catch.

- **count == 0 while BUILD SUCCESSFUL** → `withHostTest { }` is missing from the build script
  (silent-failure trap #1). Never record that as PASS (R9).
- **count < expected** → a test did not migrate. Re-check Step 5's empty-tree assertion.
- **any test FAILS** → a real regression in one of the four rewrites. Fix the rewrite, never the
  test.

Then delete the dead results directory the plugin swap orphaned, or Gate 7 double-counts it
(CONVENTIONS R3 — Phase 07 hit this):

```bash
rm -rf core/discovery/build/test-results/testDebugUnitTest core/discovery/build/reports/tests/testDebugUnitTest
```

### Gate 4 — `jvmTest`: the desktop `actual` is executed, not just compiled

```bash
./gradlew :core:discovery:jvmTest --no-configuration-cache --console=plain
grep -ho 'tests="[0-9]*"' core/discovery/build/test-results/jvmTest/*.xml
```

Expected: **7**, zero failures. Without this gate `PlatformLock.jvm.kt` would only ever be
compiled — the position `:core:common`'s three JVM `actual`s are still in (R3.1).

### Gate 5 — published coordinates

```bash
./gradlew :core:discovery:publishToMavenLocal --no-configuration-cache --console=plain
```

Then confirm under `~/.m2/repository/com/github/…/`:

- root umbrella `core-discovery/<v>/` with a `.module` **and** a `.pom`,
- `core-discovery-android/<v>/`, and
- `core-discovery-jvm/<v>/`.

The root artifactId must read `core-discovery`, **not** `discovery`. If it reads bare
`discovery`, the `artifactId.replace(…)` block is missing or wrong — fix it before moving on
(Phase 24 depends on every module carrying the `core-` prefix). Full cross-platform *resolution*
is Phase 24's job; here you prove only the coordinate names and that a `-jvm` variant exists.

### Gate 6 — the R6.1 purity grep (nothing in the build enforces R6 yet)

```bash
grep -rnE '\b(java|javax|android|androidx)\.' --include=*.kt core/*/src/commonMain ui/*/src/commonMain 2>/dev/null | grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'
```

Expected: **no output**. `commonMain` compiles `java.*` green today (Phase 07 measured it), so
this grep is the only thing standing between a `java.*` leak and Phase 16. Re-scan the stdlib
traps by hand too — `kotlin.synchronized`, `Charsets`, `String(bytes, Charset)`, `String.format`,
`@kotlin.jvm.Volatile` — none of which any import line reveals.

### Gate 7 — the repo-wide R3 command

```bash
./gradlew --stop >/dev/null 2>&1; sleep 8; ./gradlew :app:assembleDebug testDebugUnitTest :core:common:testAndroidHostTest :core:security:testAndroidHostTest :core:security:jvmTest :core:discovery:testAndroidHostTest :core:discovery:jvmTest --no-configuration-cache --continue --max-workers=2 --console=plain
```

`:app:assembleDebug` must produce an APK: that is what proves the conversion is invisible to the
app, which consumes `:core:discovery`'s Android variant through the unchanged project
dependency. If it fails with "cannot choose between debug/release", the
`localDependencySelection` block is missing.

Expected repo total: **897 / 12 failures / 0 skipped** — 883 after Phase 07, plus 14 (the 7 new
`commonTest` tests run once per target). The 12 failures are the known pre-existing
`:core:persistence` `FlashSettingsDataStoreTest` DataStore-vs-Windows-locking failures;
`--continue` is what keeps them from aborting the run before later modules execute. Paste the
tally and Gradle's real exit line, not a summary of it (R9).

**Then add `:core:discovery:testAndroidHostTest` and `:core:discovery:jvmTest` to the R3 command
in `CONVENTIONS.md`.** A converted module that is not named explicitly on that command line is
no longer reached by the unqualified `testDebugUnitTest`, and the total silently drops.

## Do NOT

- **Do NOT create `jvmAndAndroidMain`.** D1 = B. CONVENTIONS R5 voids it explicitly. If a file
  will not compile in `commonMain`, the escalation is (1) leave it where it is, (2) `expect`/
  `actual` with per-target `actual`s. There is no shared JVM tier.
- **Do NOT write any desktop discovery.** `PlatformLock.jvm.kt` is the *only* `jvmMain` file this
  phase creates. JmDNS, mDNS sockets, `NetworkInterface` enumeration — all Phase 14 (R1).
- **Do NOT edit `core/TxtCodec.kt`** or any TXT-record key/format. It is the on-wire discovery
  contract (R8); move-only. Changing a key (`device_id/name/model/proto/caps/fp8`) silently
  breaks cross-platform discovery in Phase 16.
- **Do NOT promote `:core:common`'s `PlatformLock` to `public`.** Duplicate it. See the section
  above; that is a recorded Phase 06 decision and a published-ABI change (R1, R4, R7).
- **Do NOT convert `synchronized` blocks to `kotlinx.coroutines.sync.Mutex` in
  `CompositeDiscovery`.** `Mutex.withLock` is `suspend`; `sweep`, `refreshState`,
  `applySighting`, `markBrowsing` and the rest are **not**, and `sweep` is public and directly
  tested. Making them suspend is an API change, not a migration.
- **Do NOT delete `nsd/NsdFlashDiscovery.kt`** even though it is unused. Move it unchanged; note
  it under Known issues.
- **Do NOT route `android.util.Log` through `FlashLog`.** Phase 03's job, not this one's.
- **Do NOT relocate the two dead `androidx.*` deps to `androidMain` — delete them.** This
  diverges from Phase 07 deliberately; the grep decides, not Phase 07's precedent.
- **Do NOT rewrite the 7 existing tests** into `commonTest`, `kotlin.test`, or a different
  assertion style. They move unchanged; the baseline comparison is only valid if they are
  byte-identical.
- **Do NOT modify any other module's `build.gradle.kts`** (R4).
- **Do NOT upgrade a dependency version** (R10). Note that there is no `kotlin-test` alias in
  `gradle/libs.versions.toml`; use `kotlin("test")` directly, as Phase 07 did.

## Completion checklist — every box or the phase is not done

- [ ] Phase 06 + 07 OPEN and **D1 = B**, both verified in the files (not assumed).
- [ ] `DISCOVERY_TEST_BASELINE` recorded from a green pre-migration `--rerun-tasks` run, with
      the per-class table.
- [ ] `build.gradle.kts` rewritten; both dead `androidx.*` deps **deleted**; `explicitApi()`
      retained; `-Xexpect-actual-classes` added; `core-discovery` artifactId rewrite present.
- [ ] 16 production files `git mv`'d: 12 → `commonMain`, 4 → `androidMain/nsd`;
      `find core/discovery/src/main` returns nothing.
- [ ] 7 test files `git mv`'d **unchanged** → `androidHostTest`;
      `find core/discovery/src/test` returns nothing.
- [ ] The four rewrites applied, each argued as an identity in the log.
- [ ] `PlatformLock` seam written: `expect` + 2 `actual`s, all `internal`, tree is 28 `.kt` files.
- [ ] Gate 1 `compileKotlinJvm` **SUCCESS**.
- [ ] Gate 2 `compileAndroidMain` **SUCCESS**.
- [ ] Gate 3 `testAndroidHostTest` = **104**, zero failures, per-class table matches baseline for
      all 7 original classes; orphaned `testDebugUnitTest` results dir deleted.
- [ ] Gate 4 `jvmTest` = **7**, zero failures.
- [ ] Gate 5 root coordinate is **`core-discovery`** with `.module` + `-android` + `-jvm`.
- [ ] Gate 6 R6.1 purity grep: **no output**, pasted.
- [ ] Gate 7 repo-wide R3 command: APK produced, **897 / 12 / 0**, pasted.
- [ ] `CONVENTIONS.md` R3 command updated with the two new `:core:discovery` test tasks.
- [ ] Log entry appended to `logs/migration.md` with pasted command output (R9).

**Every box checked ⇒ record Phase 08 OPEN in `logs/migration.md`. Any box unchecked ⇒ the phase
is not done; do not open it.** If verification fails, do not commit and do not proceed (R3).

## Rollback

Unlike the D1 = A version of this phase, this one *does* edit source, so a revert is not purely
mechanical — but it is still one commit:

```bash
git revert <this-phase-commit>
```

Every moved file moved with `git mv`, so history is preserved and the revert restores the
`com.android.library` script, the `src/main/java` + `src/test/java` layout, the two `androidx.*`
deps, and the four original code shapes. The three new `PlatformLock` files and the two
`commonTest` files disappear with it. Nothing downstream consumed a new coordinate (Phase 24 has
not run), so no published artifact is affected.

Mid-phase, before committing:

```bash
git checkout -- core/discovery/
git clean -fd core/discovery/src
```

If only the build script is wrong but the moves are fine, fix the script alone rather than
reverting — the moves are the expensive part and they are independent of the script.

## Log entry (mandatory)

Append one entry to `docs/migration/logs/migration.md` (append-only, newest at the bottom; never
edit an earlier entry — R1/R9), using `TEMPLATE-phase-log.md`. It must contain:

- **Commit** hash of this phase.
- **Decisions relied on:** D1 = B (this phase resolves nothing); ADR-023 (`explicitApi()`);
  R8 (`TxtCodec` untouched); R10 (no version changes).
- **Change:** `:core:discovery` converted to `com.android.kotlin.multiplatform.library` +
  `jvm()`; 16 sources placed 12/4 across `commonMain`/`androidMain`; the `FlashRadioTransport`
  seam **and its consumer `CompositeDiscovery`** now in `commonMain` for Phase 14; a
  module-private `PlatformLock` seam added; two dead `androidx.*` deps deleted.
- **The four rewrites, and why each is an identity** — one short paragraph each. This is the
  section a reviewer will actually read; do not compress it into "no behaviour change".
- **Files changed:** the 23 `git mv`s (old → new path), the 5 new files, `build.gradle.kts`.
- **Verification:** all seven gates with **pasted output** — both test counts against the
  baseline (per class, not just totals), the coordinate tree, the purity grep, and Gradle's real
  exit line for the repo-wide run.
- **What I could NOT verify (R9):** `androidDeviceTest` never ran (no device); the clock default is
  asserted at the `SystemTimeSource` seam, not through `CompositeDiscovery`'s constructor;
  published-coordinate *resolution* is Phase 24; the release/ProGuard path is unverified because
  R3 builds debug only.
- **Deviations:** the `androidx.*` **deletion** (vs Phase 07's relocation) and why; the
  `PlatformLock` duplication (vs promoting `:core:common`'s) and why; Rewrite 4 shipped as an
  index-disjoint array rather than the `Mutex`-guarded map this file first specified; the
  `commonTest` suite asserts contention (which this file said was unassertable) and therefore
  needed `libs.kotlinx.coroutines.test`.
- **Known issues:** the `PlatformLock` copy that phases 09–12 will keep duplicating, with the
  `@FlashInternalApi` recommendation; `nsd/NsdFlashDiscovery.kt` still dead code in
  `androidMain`; `android.util.Log` in three `nsd/` files not routed through `FlashLog` (Phase 03
  gap); R6.1 still unenforced by the build; no phase in 00–24 adds a Kotlin/Native target.
- **Next step:** Phase 09 (persistence, D5 = C) — or note that Phase 10 (network) may proceed
  first, since both depend only on the now-converted `:core:common`.
