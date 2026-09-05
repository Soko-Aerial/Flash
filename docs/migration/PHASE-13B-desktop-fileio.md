# Phase 13B — Desktop file I/O for `:core:transfer`, re-scoped after measurement (D10)

> **Status: 13B-1 EXECUTED (`fafd450`). 13B-2 EXECUTED (`732e7b5`). 13B-3a EXECUTED (`5e4e9a5`).
> 13B-3b EXECUTED (`a3375e3`) — the R8 `ChunkFrame` rewrite, byte-identity proved on both targets and
> against the verbatim old serializer; **that authorisation is now spent and `ChunkFrame` is R8-untouchable
> again**. 13B-3c EXECUTED (`d51206b`) — `ResumeBitVector` off `java.util.BitSet` onto a `LongArray`, no
> library needed and the persisted format proved byte-identical by the same two-artefact method.
> 13B-3d and 13B-3e REMAIN.** All 2026-09-05. Authored
> 2026-09-05 by the agent that reached Phase 13 and found `PHASE-13-desktop-fileio.md` unexecutable;
> the working tree at authoring time was clean at `d8af05c`, and no source or build file had been
> touched for Phase 13 or 13B at that point. **13B-1 has since been executed and verified against all
> seven of its gates** — see `logs/migration.md` § *Phase 13B-1*. Its five steps below are kept as
> written, as the record of what was done; do not re-run them. ~~**13B-2 and 13B-3 remain untouched**,
> and 13B-3 additionally needs an explicit R8 authorisation to rewrite `chunked/ChunkFrame.kt`.~~
> **D10 was answered Option A on 2026-09-05 and enacted as Okio 3.4.0 by 13B-2; the R8 authorisation
> for `ChunkFrame` was granted the same day with byte-identical output as a hard acceptance
> criterion. Nothing in this file is decision-blocked any more.** 13B-3 is being executed in five
> sub-steps — see the CORRECTION in §13B-3, which also fixes two wrong rows in that section's table.
>
> **This file supersedes `PHASE-13-desktop-fileio.md`.** That document is written for **D1 = A**:
> it requires a `jvmAndAndroidMain` source set holding the entire transfer pipeline, which
> `CONVENTIONS.md` R5 forbids creating at all, and it asserts that `FileSourceOpener`,
> `FileRandomAccessSinkHandle` and `ChunkSource` are reachable from `jvmMain`. **They are not** —
> all three are `androidMain`, and a compile probe proves `jvmMain` cannot resolve them (Ground
> truth 1). Do not execute PHASE-13.

## Why this is not one phase

Phase 13 was scoped as "add three small adapter files to `jvmMain`". Under D1 = B that scope is
empty, because the interfaces those adapters would implement are not visible from `jvmMain` and
cannot be made visible without either a new dependency or an edit to an R8-protected wire-format
file. What Phase 13 actually asks for — *"the concrete file-I/O implementations `core:transfer`
needs to run on a desktop JVM"* — is the tail end of a pipeline port, not a prefix.

Three things are true at once:

1. **A decision-free prefix exists and is executable today (13B-1).** Two `androidMain` files are
   pinned to Android by nothing but a `System.currentTimeMillis()` default argument and three
   `@Synchronized` annotations. Both have precedented common replacements already in this repo.
   No decision, no dependency, no ABI change — all six declarations involved are `internal`.
2. **The byte-stream seam is a human decision (13B-2).** Every remaining pin routes through
   `java.io.InputStream` / `java.io.File` / `java.io.RandomAccessFile` in a **published** `public`
   signature. Replacing them means choosing between a new multiplatform I/O dependency and an
   in-repo `expect`/`actual` typealias to `java.*`. That is **D10**, and it changes the shape of
   the project: a new dependency, a changed published ABI, and the viability of the Kotlin/Native
   target the 2026-09-03 amendment promises.
3. **The framing and hashing port needs an explicit R8 instruction (13B-3).** `chunked/ChunkFrame.kt`
   builds the CHUNK wire frame with `java.nio.ByteBuffer`/`ByteOrder`, and `ChunkFrame` is named in
   R8's untouchable list. `chunked/Sha256.kt` uses `java.security.MessageDigest`. Neither can move
   to `commonMain` without rewriting code R8 says not to touch without being told to.
   **Half of this turned out to be over-cautious: `Sha256.kt` is not an R8 file — it is a helper in
   `:core:transfer`, not `core/security/**` and not one of the seven named wire formats — so 13B-3a
   moved it under the ordinary rules. Its digests are wire-visible, which is why byte identity was
   still the acceptance criterion, discharged by FIPS 180-2 known-answer vectors now running on both
   targets. `ChunkFrame` genuinely is an R8 file and did need the instruction.**

~~Execute **13B-1**.~~ **13B-1 is done (`fafd450`), 13B-2 is done (`732e7b5`), 13B-3a is done
(`5e4e9a5`), 13B-3b is done (`a3375e3`), 13B-3c is done (`d51206b`).** ~~Do not start 13B-2 or 13B-3 until D10 is
answered and, for 13B-3, until the human has explicitly authorised touching `ChunkFrame`.~~ **Both
conditions were met on 2026-09-05, and the `ChunkFrame` authorisation has been spent — no further edit
to that file is permitted without a fresh one.** The next executable unit is **13B-3d**, the
concurrency seams: the `java.util.concurrent.atomic` types in `multistream/MultiStreamDispatcher.kt`
and `multistream/TransferCompletionStateMachine.kt`, plus `ConcurrentHashMap` and `UUID` in
`RealFlashTransferRepository.kt`. 13B-3c's finding applies to it directly — see the third correction
below.

---

## Ground truth (measured 2026-09-05, repo at `d8af05c`)

### 1. `jvmMain` cannot see the seam types. Compile-proved, not assumed.

A probe reproducing exactly what PHASE-13 prescribes was written to
`core/transfer/src/jvmMain/kotlin/com/transfer/flash/core/transfer/desktop/ZzPhase13Probe.kt` and
compiled with `:core:transfer:compileKotlinJvm`. Probe deleted afterwards; the tree was clean again
before anything else ran.

```
e: …/ZzPhase13Probe.kt:3:41 Unresolved reference 'FileSourceOpener'.
e: …/ZzPhase13Probe.kt:4:41 Unresolved reference 'chunked'.
e: …/ZzPhase13Probe.kt:5:41 Unresolved reference 'policy'.
e: …/ZzPhase13Probe.kt:6:41 Unresolved reference 'policy'.
e: …/ZzPhase13Probe.kt:7:41 Unresolved reference 'policy'.
e: …/ZzPhase13Probe.kt:14:32 Unresolved reference 'FileSourceOpener'.
e: …/ZzPhase13Probe.kt:15:5  'open' overrides nothing.
e: …/ZzPhase13Probe.kt:18:38 Unresolved reference 'ChunkSource'.
e: …/ZzPhase13Probe.kt:20:33 Unresolved reference 'RandomAccessSinkHandle'.
e: …/ZzPhase13Probe.kt:20:58 Unresolved reference 'FileRandomAccessSinkHandle'.
e: …/ZzPhase13Probe.kt:22:33 Unresolved reference 'DestinationTarget'.
BUILD FAILED in 20s
```

Note lines 4–7: the **packages** `chunked` and `policy` are unresolved, not merely the types.
Those packages exist only under `androidMain`. `androidMain` and `jvmMain` are sibling source sets
with no `dependsOn` edge between them; only `commonMain` is a common ancestor, and under D1 = B
there is no third tier. This is the whole of the blockage.

### 2. The real placement is 5 `commonMain` + 15 `androidMain`, and **not one** of the 15 is Android

`find core/transfer/src -name '*.kt'`, and a census of every `android.*`/`androidx.*` and
`java.*`/`javax.*` reference in each `androidMain` file:

| `androidMain` file | `android.*` / `androidx.*` | `java.*` that pins it |
|---|---|---|
| `RealFlashTransferRepository.kt` | — | `io.InputStream`, `util.Collections.newSetFromMap`, `util.UUID`, `util.concurrent.ConcurrentHashMap` |
| `chunked/ChunkFrame.kt` | — | `io.ByteArrayOutputStream`, `nio.ByteBuffer`, `nio.ByteOrder` |
| `chunked/Chunker.kt` | — | `io.Closeable`, `io.IOException`, `io.InputStream` |
| `chunked/ReceivePipeline.kt` | — | — (same-package `ChunkFrame`) |
| `chunked/ResumeBitVector.kt` | — | ~~`util.BitSet`~~ **cleared by 13B-3c (`d51206b`) — now `commonMain`, no imports at all** |
| `chunked/SendPipeline.kt` | — | — (same-package `ChunkSource`, `Chunker`, `ChunkFrame`) |
| `chunked/Sha256.kt` | — | `security.MessageDigest` |
| `manifest/TransferManifest.kt` | — | — (**`System.currentTimeMillis()`**, line 29) |
| `model/WsTransferModels.kt` | — | — (`:core:network`'s `androidMain` `WsTransferServer`) |
| `multistream/MultiStreamDispatcher.kt` | — | `util.Collections.synchronizedList`, `util.concurrent.atomic.{AtomicBoolean,AtomicInteger,AtomicLong}` |
| `multistream/MultiStreamProgress.kt` | — | — (**3 × `@Synchronized`**, lines 54/70/79) |
| `multistream/MultiStreamReceiver.kt` | — | — (imports `ChunkFrame`, `ReceivePipeline`) |
| `multistream/TransferCompletionStateMachine.kt` | — | `util.concurrent.atomic.AtomicBoolean` |
| `policy/DestinationPolicy.kt` | — | `io.Closeable`, `io.File`, `io.OutputStream`, `io.RandomAccessFile` |
| `policy/RandomAccessChunkSink.kt` | — | — (imports `ChunkSink`, same-package `RandomAccessSinkHandle`) |

The `android.*` column is empty for all fifteen. **`:core:transfer`'s pipeline is not Android code**
— it is JVM code with no shared JVM tier to live in. Seven of the fifteen carry no `java.*` at all
and sit in `androidMain` purely by reference-transitivity; the two flagged in bold are pinned only
by stdlib traps the `java.*` regex cannot see, which is what makes them 13B-1.

### 3. The four seam declarations, as they actually are

```kotlin
// androidMain/…/chunked/Chunker.kt:10          — PHASE-13 claims commonMain
public fun interface ChunkSource { public fun open(): InputStream }

// androidMain/…/RealFlashTransferRepository.kt:40
public fun interface FileSourceOpener { public fun open(fileUri: String): InputStream }

// androidMain/…/chunked/ReceivePipeline.kt:345
public fun interface ChunkSink { public fun write(index: Int, data: ByteArray) }

// androidMain/…/policy/DestinationPolicy.kt:71
public interface RandomAccessSinkHandle : Closeable {
    public fun writeAt(byteOffset: Long, data: ByteArray)
    public fun flush()
    public val isOpen: Boolean
}
public class FileRandomAccessSinkHandle(         // TWO constructor parameters
    private val file: File,
    private val expectedTotalBytes: Long,
) : RandomAccessSinkHandle
```

And the two types PHASE-13 gets wrong in kind, not just in placement:

```kotlin
// androidMain/…/policy/DestinationPolicy.kt:12  — internal, and neither arm is a path string
internal sealed interface DestinationTarget {
    data class FileTarget(val file: File) : DestinationTarget
    data class UriTarget(val uriString: String, val displayName: String) : DestinationTarget
}

// androidMain/…/policy/DestinationPolicy.kt:38  — internal; there is no `createSinkHandle`
internal interface DestinationPolicy {
    suspend fun evaluateOffer(senderDeviceId: String, fileName: String, totalBytes: Long,
                              mimeType: String?, isTrustedPeer: Boolean): TransferAcceptance
    suspend fun openSinkHandle(transferId: String, fileId: String, target: DestinationTarget,
                               totalBytes: Long): RandomAccessSinkHandle
}
```

`DestinationTarget` being `internal` is decisive on its own: PHASE-13's
`public fun File.toDestinationTarget(): DestinationTarget` could not compile even if the package
were visible, because `explicitApi()` (R7) forbids a `public` signature that exposes an `internal`
type.

### 4. First-party consumer counts — the ABI blast radius of re-typing each seam

`grep -rn "\b<type>\b" --include=*.kt app/ ui/ sample/ core/engine/ core/calling/`:

| Type | Consumers outside `:core:transfer` | Where |
|---|---|---|
| `ChunkSource` | **0** | — (re-verified 2026-09-05, see below) |
| `ChunkSink` | ~~**0**~~ **4 lambdas** | see the correction below |
| `FileSourceOpener` | ~~**0**~~ **2 + 5 tests** | see the correction below |
| `Chunker` | **0** | — |
| `DestinationPolicy`, `DestinationTarget` | **0** | (`internal`) |
| `RandomAccessSinkHandle` | 2 | `core/engine/…/Flash.kt:40,138`; `app/…/debug/DiscoveryEngineHolder.kt` |
| `FileRandomAccessSinkHandle` | 2 | `core/engine/…/Flash.kt:38,198`; `app/…/debug/DiscoveryEngineHolder.kt:48,362` |
| `RandomAccessChunkSink` | 2 | `core/engine/…/Flash.kt:39,200`; `app/…/debug/DiscoveryEngineHolder.kt:49,365` |

> **CORRECTION (2026-09-05, from 13B-2's execution).** Two rows above are wrong, and
> the grep at the head of this section is why. All three of `ChunkSource`, `ChunkSink` and
> `FileSourceOpener` are `fun interface`es, so **a consumer SAM-converts a lambda and never writes
> the type name** — `grep <TypeName>` cannot see it. Grepping the *parameter* name instead is what
> the table should have done:
>
> - `FileSourceOpener` → `grep -rn fileSourceOpener` finds `core/engine/…/Flash.kt:230` and
>   `app/…/DiscoveryEngineHolder.kt:469` in product code, plus 5 sites in
>   `RealFlashTransferRepositoryTest.kt`. Its **0** is false; 13B-2 had to edit all seven. (Line
>   numbers are post-`732e7b5`; before the phase they were `:225` and `:468`.)
> - `ChunkSink` → `grep -rnE '\b(sink|sinkFactory) ='` finds four sites outside `:core:transfer`:
>   `Flash.kt:192,193` and `DiscoveryEngineHolder.kt:356,357` (`sink =` is a direct SAM conversion;
>   `sinkFactory =` is a lambda *returning* a `ChunkSink`). Its **0** is false too — it cost 13B-2 no
>   edit only because `ChunkSink`'s signature did not change, not because nothing consumes it. If a
>   later phase re-types `write(index, data)`, those four sites are the blast radius.
> - `ChunkSource` → `grep -rn 'source ='` outside this module returns only unrelated local
>   `val source` declarations (MainActivity, FlashCallScreen, FlashImageGrid, FlashMediaViewer,
>   FlashImageDecoder, FlashWebRtcEngine). Its **0** genuinely holds: every `ChunkSource` lambda is
>   built inside `:core:transfer`, and the two product call sites reach it through
>   `FileSourceOpener`, which is why re-typing *that* seam is what leaked outward.
>
> Rule for later phases: for a `fun interface`, a type-name grep measures nothing. Grep the
> parameter name, and treat the count as a lower bound until the module compiles.

Re-typing the three stream/sink seams ~~with zero consumers~~ is ABI-visible but breaks nothing
first-party (see the correction above: only `ChunkSource` truly had zero consumers; the other two
were SAM-hidden, and 13B-2 edited every site it found). The three `RandomAccess*` types each have
exactly two callers, both Android-side, both
constructing a handle over a `java.io.File` — so a D10 option that keeps a `File`-shaped Android
constructor costs zero consumer edits.

### 5. The desktop artifact today: contract-only, 15 classes

`:core:transfer:jvmJar` → `core/transfer/build/libs/transfer-jvm-1.1.0.jar`, 28,760 bytes,
27 entries, **15 `.class`**, zero `android/` paths:

```
com/transfer/flash/core/transfer/FlashTransferRepository{,$DefaultImpls}.class
com/transfer/flash/core/transfer/model/FlashTransfer{,Direction,Id,State}.class
com/transfer/flash/core/transfer/multistream/StreamChannel{,Factory}.class
com/transfer/flash/core/transfer/protocol/WsTransferMessages{,$Hello,$FileStart,$FileAck,$FileEnd}.class
com/transfer/flash/core/transfer/store/TransferStore{,$ChunkRef}.class
```

Interfaces and data classes only — no chunker, no pipeline, no sink. PHASE-13's expected
"14+3+5 = 22 class entries" is wrong in every term. `FlashTransferState`, `FlashTransferDirection`,
`FlashTransferId` and `StreamChannelFactory` are declarations inside `model/FlashTransfer.kt` and
`multistream/StreamChannel.kt`, not the separate `commonMain` files PHASE-13's tree lists.

### 6. `core/transfer/build.gradle.kts` dependencies, as they actually are

`commonMain`: `api(project(":core:common"))`, `api(libs.kotlinx.coroutines.core)`.
`androidMain`: `implementation(project(":core:network"))`, `implementation(libs.androidx.core.ktx)`,
`implementation(libs.androidx.lifecycle.runtime.ktx)`.

There is **no** `:core:security` and **no** `:core:discovery` edge — PHASE-13's dependency table
lists both. `core/transfer/wslegacy/` no longer exists (deleted by Phase 02), so PHASE-13's "the 4
wslegacy/ files" refers to nothing.

---

## 13B-1 — the decision-free prefix. ~~Executable today.~~ **DONE — `fafd450`, 2026-09-05.**

> Executed and verified against all seven gates. Measured net effect: `androidMain` 15 → 13,
> `commonMain` 5 → 7, `jvmMain` 0 → 1, `transfer-jvm-1.1.0.jar` **15 → 25 classes** (the estimate
> below said "~22"; the difference is `MultiStreamResult`'s two nested `data class`es and the two
> `Companion`/`Sample` inner classes, which the estimate did not count), published ABI unchanged,
> repo tests 961 → **977 / 12 / 0 across 132 XMLs**. Steps 1–5 below are the record of what was done —
> do not re-run them. Full output: `logs/migration.md` § *Phase 13B-1*.

**Goal:** move the two `androidMain` files that are pinned by stdlib traps rather than by `java.*`
into `commonMain`, using seams this repo already has. Net effect: `androidMain` 15 → 13,
`commonMain` 5 → 7, `transfer-jvm-1.1.0.jar` 15 → ~22 classes, published ABI unchanged (all six
declarations involved are `internal`).

This does not give desktop a transfer path — nothing in `jvmMain` consumes these yet. Its value is
that it is the part of the port that needs no decision, it is where the fourth `PlatformLock` lands
(CONVENTIONS.md R2 predicted exactly this), and it puts a `commonTest` suite on `RollingRateMeter`
whose rate-regression KDoc records a field-reported bug with no test guarding it today.

### Step 1 — `:core:transfer`'s own `PlatformLock` (the fourth copy)

Three new files, copied verbatim from `:core:engine`'s Phase 12 trio with the package changed to
`com.transfer.flash.core.transfer.concurrent`:

```
core/transfer/src/commonMain/kotlin/…/transfer/concurrent/PlatformLock.kt         (internal expect class)
core/transfer/src/androidMain/kotlin/…/transfer/concurrent/PlatformLock.android.kt (internal actual)
core/transfer/src/jvmMain/kotlin/…/transfer/concurrent/PlatformLock.jvm.kt         (internal actual)
```

Both `actual`s are the same three lines — `private val monitor = Any()` and
`actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }`. Copy rather than
hoist, for the reason R2 now states: `:core:common`'s copy is `internal`, `internal` does not cross
a Gradle module boundary, and promoting it would put a lock in `core-common`'s published ABI and
edit a second module (R4, R7). Update the KDoc to say *fourth* copy and cite Phase 13B.

### Step 2 — the one build-file edit

`core/transfer/build.gradle.kts` lines 14–18 currently carry a NOTE explaining that the module
*deliberately* has no `-Xexpect-actual-classes` because *"This module declares no expect/actual at
all"*. That stops being true. Replace the NOTE with the flag and the Phase 12 justification:

```kotlin
    compilerOptions {
        // Required because 13B-1 introduces `expect class PlatformLock`. expect/actual CLASSES
        // are still Beta (KT-61573) and warn once per declaration site; CONVENTIONS.md R2 permits
        // a class here because the seam carries per-platform state (a monitor). Fourth copy —
        // :core:common (06), :core:discovery (08), :core:engine (12), here (13B-1).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
```

This is the only build-file change in 13B-1, and it is in one module (R4).

### Step 3 — move `multistream/MultiStreamProgress.kt`, swapping 3 × `@Synchronized`

`git mv core/transfer/src/androidMain/…/multistream/MultiStreamProgress.kt
core/transfer/src/commonMain/…/multistream/MultiStreamProgress.kt`, then edit `RollingRateMeter`
only. `MultiStreamProgress` and `MultiStreamResult` in the same file need no edit at all — they are
already pure Kotlin (`ArrayDeque` is `kotlin.collections`, common since 1.4).

```kotlin
// before                                  // after
internal class RollingRateMeter(           internal class RollingRateMeter(
    private val nowMs: () -> Long,             private val nowMs: () -> Long,
    private val windowMs: Long = …,            private val windowMs: Long = …,
) {                                        ) {
                                               private val lock = PlatformLock()
    @Synchronized
    fun record(cumulativeBytes: Long) {…}      fun record(b: Long) = lock.withLock { … }

    @Synchronized
    fun reset() { samples.clear() }            fun reset() { lock.withLock { samples.clear() } }

    @Synchronized                              fun instantBytesPerSec(atMs: Long): Double =
    fun instantBytesPerSec(atMs: Long)             lock.withLock {
        : Double {                                   …
        if (samples.size < 2) return -1.0            if (samples.size < 2) return@withLock -1.0
        if (dtMs <= 0.0) return -1.0                 if (dtMs <= 0.0) return@withLock -1.0
        if (db <= 0.0) return -1.0                   if (db <= 0.0) return@withLock -1.0
        return db * 1000.0 / dtMs                    db * 1000.0 / dtMs
    }                                            }
```

`withLock` cannot be `inline` on an `expect class`, so **all three** early returns in
`instantBytesPerSec` must become `return@withLock` — this is the same mechanical consequence Phase 12
hit on `AutoConnectGate`, and a plain `return` will not compile. `prune` is `private` and is only
ever called from inside a locked block, so it must **not** take the lock itself (the `actual`s use
`synchronized`, which is reentrant on the JVM, so a nested take would work today — but it would stop
working on any target whose `actual` is not reentrant, so leave `prune` unguarded and keep the
invariant in a comment).

The monitor changes from `this` to a private object. Nothing outside the file can observe that:
`RollingRateMeter` is `internal`, and no code anywhere synchronises on an instance of it.

### Step 4 — move `manifest/TransferManifest.kt`, swapping `System.currentTimeMillis()`

`git mv` androidMain → commonMain, then line 29 only:

```kotlin
// before
val createdAtMs: Long = System.currentTimeMillis(),
// after — :core:common is already an `api` dependency of commonMain
import com.transfer.flash.core.common.time.SystemTimeSource
val createdAtMs: Long = SystemTimeSource.nowMs(),
```

`SystemTimeSource` is a `public object` in `commonMain` of `:core:common` implementing
`FlashTimeSource.nowMs()`, backed by Phase 06's `internal expect fun currentTimeMillisPlatform()`.
Same clock, same value, one indirection. `ManifestItem` needs no edit.

### Step 5 — a `commonTest` suite, so both `actual`s are executed and not merely compiled

R3.1: *"Any phase that writes an `actual` should put at least one behavioural assertion in
`commonTest` so both platforms run it."* `:core:transfer` already has a `commonTest`
(`WsTransferMessagesWireFormatTest`), so no new dependency wiring is needed.

`core/transfer/src/commonTest/kotlin/…/multistream/RollingRateMeterTest.kt`, minimum:

- `rate_isNegativeOne_untilTwoSamples` — one `record`, then `instantBytesPerSec` == -1.0.
- `rate_isBytesPerSecondAcrossWindow` — a fake clock and two samples 1 s / 1 MiB apart.
- `rate_usesOldestSampleInWindow_notFirstEver` — the regression the class KDoc describes
  (field-reported 2026-08-24): drive 5 samples across 3 × the window and assert the reported rate
  stays at the real throughput instead of climbing. **There is no test for this today.**
- `rate_resetsOnBackwardsClock` — `record` at t=1000 then t=500 must not produce a negative rate.
- `rate_isNegativeOne_whenStalled` — samples with no byte delta.
- `reset_clearsWindow` — the pause/resume case its KDoc describes.
- `contention_recordAndReadDoNotCorrupt` — N coroutines racing `record` while another reads;
  assert every reading is either -1.0 or ≥ 0.0 and no exception escapes. This is what makes the
  swapped-in lock's exclusion observable rather than assumed, as `PlatformLockTest` and
  `AutoConnectGateTest` do for copies two and three.

`TransferManifest` gets a `commonTest` case too — `manifest_defaultsCreatedAtToNow` asserting
`createdAtMs` is within a generous window of `SystemTimeSource.nowMs()` — so the clock swap is
executed on both targets rather than only compiled.

### 13B-1 verification gates — **all seven passed, `fafd450`**

R3's full command line, plus these module-local gates. `:core:transfer` is already named on the R3
line (`testAndroidHostTest` + `jvmTest` since Phase 11), so no CONVENTIONS edit is needed for it.

Measured results in **bold**; the pasted output for each is in `logs/migration.md` § *Phase 13B-1*.

1. `:core:transfer:compileKotlinJvm` — **SUCCESSFUL**. The R2 proof task: `jvm()` has no
   `android.jar`, so this certifies the two moved files are free of `android.*`.
   → **PASS, zero `w:` warnings** (which also proves `-Xexpect-actual-classes` took effect).
2. `:core:transfer:compileAndroidMain` — **SUCCESSFUL**. Proves the 13 remaining `androidMain`
   files still resolve the moved declarations from `commonMain`. → **PASS, zero warnings.**
3. `:core:transfer:jvmTest` + `:core:transfer:testAndroidHostTest` — the new `commonTest` cases run
   **twice**, once per target. Count them; R3 requires the arithmetic, so a suite of N cases must
   show +2N. → **PASS. `jvmTest` 8 → 16, `testAndroidHostTest` 94 → 102; 8 cases × 2 targets = +16.
   Repo 961 → 977 tests, 128 → 132 XMLs.**
4. R6.1 both greps, over `core/*/src/commonMain` — expected empty. Use the corrected trap regex;
   **do not** write `\b@Synchronized\b`, which can never match (R6.1's warning box).
   → **PASS. Grep A empty; grep B returns 4 `@Volatile` lines, all proven legal because grep C
   (`@Volatile` without `import kotlin.concurrent.Volatile`) is empty.**
5. `:core:transfer:jvmJar` — class count rises from 15; no `android/` paths.
   → **PASS. 47 905 bytes, 39 entries, 25 classes, 0 `android/` paths.**
6. `publishToMavenLocal` — all three coordinates still emitted, and `transfer-jvm`'s POM still
   carries no androidx and no `:core:network`. → **PASS. `core-transfer`, `core-transfer-android`,
   `core-transfer-jvm`; jvm POM = `core-common-jvm` + `kotlinx-coroutines-core-jvm` + `kotlin-stdlib`.**
7. `ls -1 core/transfer/src` → `androidHostTest androidMain commonMain commonTest jvmMain`, and no
   `java/` language directory anywhere (R5). → **PASS, exactly those five.**

An eighth check was performed that this file did not ask for: a **mutation probe** re-introducing the
2026-08-24 rate bug, to confirm the new guard test actually fails on it. It does, at `t=2000 ms` and
not at `t=1000 ms` — which is why that case asserts at every window boundary. See the log entry.

---

## 13B-2 — the byte-stream seam. ~~**Blocked on D10.**~~ **DONE — `732e7b5`, 2026-09-05.**

Every remaining pin routes through a `java.io` type in a **published** `public` signature:
`ChunkSource.open(): InputStream`, `FileSourceOpener.open(String): InputStream`,
`RandomAccessSinkHandle : Closeable`, `FileRandomAccessSinkHandle(File, Long)`,
`Chunker`'s `Closeable`/`IOException`, `DestinationPolicy`'s `File`/`RandomAccessFile`/`OutputStream`.
There is no way to make these reachable from `jvmMain` without deciding what replaces
`java.io.InputStream` in a `commonMain` signature. That is **D10** in `DECISIONS.md`, and it is
reserved for the human because it adds a dependency, changes the published ABI, and determines
whether a Kotlin/Native target is ever viable.

Once D10 is answered, 13B-2 is: re-type the four seams; split `ChunkSource` out of `Chunker.kt` and
`ChunkSink` out of `ReceivePipeline.kt` into their own `commonMain` files; provide the desktop
implementations PHASE-13 wanted — ~~which at that point genuinely are three small `jvmMain` files~~ —
and keep an Android-side `File`-shaped constructor so the three `RandomAccess*` consumers in
`core/engine/…/Flash.kt` and `app/…/DiscoveryEngineHolder.kt` need no edit.

> **CORRECTION (2026-09-05, from 13B-2's execution).** D10 was answered **Option A**, enacted as
> okio 3.4.0, and okio is itself multiplatform — so the "three small `jvmMain` files" prediction is
> wrong in both the count and the source set. What 13B-2 actually produced is **one `commonMain`
> implementation**, `policy/OkioRandomAccessSinkHandle`, and **zero** new `jvmMain` files (`jvmMain`
> still holds exactly the one file 13B-1 put there). The Android-side `File`-shaped constructor was
> kept as predicted, via interface delegation:
> `class FileRandomAccessSinkHandle(file: File, expectedTotalBytes: Long) : RandomAccessSinkHandle by
> OkioRandomAccessSinkHandle(file.toOkioPath(), expectedTotalBytes)` — so all three `RandomAccess*`
> consumers needed no edit, as this section predicted. One ABI break the section did not predict:
> `RandomAccessSinkHandle`'s supertype moves from `java.io.Closeable` to `kotlin.AutoCloseable`
> (queued for Phase 24's release notes). `RandomAccessChunkSink.kt` also had to move to `commonMain`,
> though this section did not list it — every type in it was already common, and without it
> `commonMain` would hold a handle and a sink with no way to join them.

The `DesktopDestinationPolicy` sketch in PHASE-13 is not reusable as written: it implements a
member (`createSinkHandle`) that does not exist, over target arms (`File`/`Directory`/`Temp`) that
do not exist, on an `internal` type it cannot expose. Design it against `openSinkHandle` and the
real `FileTarget`/`UriTarget` pair, and note that `jvmMain` must be **OS-neutral** per the
2026-09-03 amendment — `System.getProperty("java.io.tmpdir")` is fine, a `C:\` literal or
`%USERPROFILE%` is not.

## 13B-3 — framing, hashing, concurrency. ~~**Blocked on D10 *and* an explicit R8 instruction.**~~ **UNBLOCKED 2026-09-05** (D10 = Option A; R8 exception granted, byte-identical output required). **13B-3a DONE — `5e4e9a5`. 13B-3b DONE — `a3375e3`; the R8 authorisation is now spent. 13B-3c DONE — `d51206b`.**

What is left after 13B-2, with the known common answer for each:

| Pin | File(s) | Common answer |
|---|---|---|
| `java.nio.ByteBuffer` / `ByteOrder` | `chunked/ChunkFrame.kt` | ~~hand-rolled ~~big-endian~~ **little-endian** `ByteArray` arithmetic, as `protocol/WsTransferMessages.kt` already does in `commonMain`~~ **DONE in 13B-3b (`a3375e3`): Okio's `Buffer` with `writeShortLe`/`writeIntLe`/`writeLongLe` — the library D10 chose already *has* the little-endian primitives, so none were hand-rolled. See the second correction below.** |
| `java.security.MessageDigest` | `chunked/Sha256.kt` | ~~`:core:security`'s Phase 07 `PlatformCrypto` seam, or a common SHA-256 — **adds a module edge**, `:core:transfer` does not depend on `:core:security` today~~ **DONE in 13B-3a (`5e4e9a5`): okio's `HashingSink`. No module edge was added — see the correction below.** |
| `java.util.concurrent.atomic.*` | `multistream/MultiStreamDispatcher.kt`, `multistream/TransferCompletionStateMachine.kt` | `kotlin.concurrent.Atomic*` (still `@ExperimentalAtomicApi` at Kotlin 2.2.10), `kotlinx.atomicfu`, or `PlatformLock` + plain vars |
| `ConcurrentHashMap`, `Collections.{newSetFromMap,synchronizedList}` | `RealFlashTransferRepository.kt`, `MultiStreamDispatcher.kt` | `PlatformLock` + plain `MutableMap`/`MutableList` — the pattern already used three times |
| `java.util.UUID` | `RealFlashTransferRepository.kt` | `:core:common`'s Phase 06 `UuidIdGenerator` |
| `java.util.BitSet` | `chunked/ResumeBitVector.kt` | a `LongArray` bitset in common Kotlin — **DONE in 13B-3c (`d51206b`). This is the one row the table got exactly right, and the only one that needed no library at all. See the third correction below for what it still understated.** |

> **CORRECTION (2026-09-05, after executing 13B-3a — `5e4e9a5`).** Two rows of the table above were
> wrong, and the sub-step order this section implies is wrong.
>
> - **Endianness.** The `ByteBuffer` row says "hand-rolled **big-endian**". `ChunkFrame`'s own
>   documented layout is *"all multi-byte scalars LITTLE-endian"*: `PAYLOAD_LENGTH` is a uint32 LE,
>   `string` is a uint16 LE byte-length plus UTF-8, and the header is built with
>   `ByteBuffer.allocate(HEADER_SIZE + body.size).order(ByteOrder.LITTLE_ENDIAN)`. The file already
>   contains a hand-rolled `readI32Le`. An agent that took this row at face value would emit
>   byte-swapped frames and fail the R8 acceptance criterion on the first vector. `WsTransferMessages`
>   is still the right precedent for *how*, just not for *which order*.
> - **The hashing answer.** Neither option in the `MessageDigest` row was usable.
>   `:core:security`'s `PlatformCrypto` declares `internal expect fun sha256(data: ByteArray)` and
>   `internal expect fun constantTimeBytesEqual(...)` — both `internal`, so `:core:transfer` could not
>   call them even with the module edge added, and `sha256` is one-shot so it cannot serve
>   `IncrementalSha256`'s streaming whole-file pass. Making them public is an ABI change to
>   `core/security/**`, which R8 puts outside this phase. What 13B-3a used instead is the library D10
>   already brought in: okio 3.4.0's `HashingSink.sha256(blackholeSink())`, whose JVM/Android
>   implementation holds a `java.security.MessageDigest` (verified with `javap`), so the digest bytes
>   are unchanged on Android. **No module edge, no new dependency, no `expect`/`actual`.**
> - **Order.** This section lists framing before hashing, and 13B-3 was sketched that way.
>   `ChunkFrame` cannot move first: its `init` validation and parse path call `Sha256.isValidHex`,
>   `Sha256.normalizeHex`, `Sha256.HEX_LENGTH` and `Sha256.RAW_LENGTH`. **Hashing must land first**,
>   which is why `5e4e9a5` is 13B-3**a** and the framing rewrite is 13B-3**b**.
>
> Executed order: **a** hashing (`5e4e9a5`) → **b** framing (`ChunkFrame`, R8 — `a3375e3`) → **c** resume
> (`ResumeBitVector` — `d51206b`) → **d** concurrency (atomics, `ConcurrentHashMap`, `UUID`) → **e** the pipelines
> (`Chunker`/`ChunkStream`, `ReceivePipeline`, `SendPipeline`, `MultiStreamReceiver`, which is where
> the two `.buffer().inputStream()` bridges 13B-2 left behind get deleted). `policy/DestinationPolicy.kt`
> and `model/WsTransferModels.kt` stay in `androidMain`; neither is a 13B-3 pin.

> **CORRECTION (2026-09-05, after executing 13B-3b — `a3375e3`).** One more row above was more
> pessimistic than it needed to be, and one thing this section does not mention turned out to matter.
>
> - **"Hand-rolled `ByteArray` arithmetic" was the wrong answer, not just the wrong endianness.** Once
>   D10 = A landed as Okio, `writeShortLe`/`writeIntLe`/`writeLongLe` are `commonMain` primitives, so
>   there was nothing to hand-roll: the `ByteBuffer` scratch was *configured* to be little-endian and
>   Okio's `*Le` writers **are** that. Hand-rolling would have been more code and more risk under a
>   byte-identity criterion. The existing hand-rolled `readI32Le` was left exactly as it was — it was
>   already common Kotlin, and R8's criterion rewards touching less.
> - **`Charsets.US_ASCII` has no Okio equivalent, and `ByteString.utf8()` is not one.** The `sha256hex`
>   field is decoded strictly per byte; a UTF-8 decoder folds a multi-byte sequence into a single
>   replacement char and would change the decoded length of a corrupt field. 13B-3b kept the JDK
>   decoder's behaviour with a four-line loop. Any sub-step that meets a `Charsets.US_ASCII` should
>   expect the same, rather than reaching for `utf8()`.
> - **The acceptance criterion needed two artefacts, not one.** Capture-then-assert alone proves the new
>   implementation matches a *transcription* of the old output. 13B-3b added a temporary differential
>   test holding the pre-rewrite serializer verbatim and asserting it against the new one over the eleven
>   shapes plus 4000 random frames. Both temporary files were deleted; the golden vectors live in
>   `commonTest`. Recorded here because it is the pattern any future R8 authorisation should copy.

> **CORRECTION (2026-09-05, after executing 13B-3c — `d51206b`).** The `BitSet` row was right about the
> answer and silent about the risk, and the sub-step turned up one finding that changes how 13B-3d
> should be read.
>
> - **D10 does not have to cover every `java.util` type.** The sub-step was approached expecting Okio to
>   supply the replacement, as it did for `java.io`, `java.nio` and `java.security`. Okio has no bitset
>   and neither does kotlinx-io; the answer was Kotlin's own `Long.countOneBits()`,
>   `Long.countTrailingZeroBits()` and `LongArray.copyInto()`, and the file ended with an **empty import
>   block**. So D10 = Option A is scoped to I/O and should not be stretched: 13B-3d's atomics are the
>   next test of the same question, and the answer there is `kotlin.concurrent.Atomic*`,
>   `kotlinx.atomicfu` or `PlatformLock` — not Okio.
> - **`java.util.UUID` needs no port either.** `:core:common` has carried `UuidIdGenerator` in
>   `commonMain` since Phase 06 over a `PlatformUuid` `expect`/`actual` seam, and `:core:transfer`
>   already declares `api(project(":core:common"))`, so the row above naming it is correct and 13B-3d's
>   `UUID` work is a call-site swap. `kotlin.uuid.Uuid` exists at 2.2.10 but is `@ExperimentalUuidApi`
>   (verified with `javap`); there is no reason to reach for it.
> - **A data structure can be a wire format without looking like one.** `BitSet.toLongArray()` trims
>   trailing all-zero words — its length is `ceil(length() / 64)` where `length()` is the highest set bit
>   plus one, **not** the capacity. A fixed-size dump of `ceil(totalChunks / 64)` words would have changed
>   the length of every payload `ResumeBitVector` has ever written, and **no round-trip test would have
>   caught it**, because the new reader accepts what the new writer produced. `cardinality()` and
>   `nextSetBit`'s word-skipping were the other two behaviours that had to be reproduced deliberately.
>   The generalisation for 13B-3d/e: when replacing a `java.util` type, ask what its *observable output*
>   is, not just what its API does.
> - **13B-3b's two-artefact discipline was applied to a file R8 does not name.** `ResumeBitVector` is not
>   on the untouchable list, so no authorisation was needed — but `toSerialized()` is reached from
>   `ReceivePipeline.serializedProgress()`, which is `public` API on a published library whose whole
>   purpose is to be written down now and read back by a later *build*. That is a stronger constraint
>   than a wire format, not a weaker one. Seven golden vectors went into `commonTest`; a temporary
>   `androidHostTest` differential test held the verbatim `BitSet` implementation and agreed with the new
>   one over 2080 randomized done-sets, 20 trimming shapes, 320 cross-restores in both directions, 12
>   hostile/padded payloads and randomized `reconcile` sweeps, then was deleted.

**`ChunkFrame` is named in R8's untouchable list** (*"Wire formats: `FlashEnvelope`, `FlashProtocol`,
`ChunkFrame`, …"*), and rewriting its `ByteBuffer` framing is unavoidable here. R8 says such a change
needs an explicit instruction; R2 says a phase that seems to require a forbidden edit must stop and
report. ~~So 13B-3 must not begin until the human has said, in words, that rewriting `ChunkFrame`'s
serialisation in common Kotlin is authorised~~ — **the human said exactly that on 2026-09-05** — with
the obvious acceptance criterion that the emitted bytes stay identical, provable by a `commonTest`
round-trip against frames captured from the current Android implementation. **That criterion is
hard: 13B-3b does not ship if any byte differs.** Note that the authorisation covers `ChunkFrame`
alone; the other six wire formats R8 names are still untouchable.

**SPENT 2026-09-05 (`a3375e3`).** The criterion was met — eleven golden vectors captured before the
rewrite and asserted after it on **both** targets, plus a differential test against the verbatim old
serializer. `ChunkFrame` is back under R8's ordinary protection from this point: 13B-3c, 13B-3d and
13B-3e must not touch it, and any later edit needs a fresh authorisation. **13B-3c (`d51206b`) honoured
that: it touched `ChunkFrame.kt` not at all, and the only reference to it in the commit is a corrected
comment in `ChunkSink.kt` naming it as already done.**

---

## What PHASE-13 asserts, and what is actually true

Recorded here rather than fixed in place, per R1. Every "actual" below was measured 2026-09-05.

| PHASE-13 says | Measured |
|---|---|
| `commonMain` + `jvmAndAndroidMain` split exists (line 3) | Only `commonMain` + `androidMain`. `jvmAndAndroidMain` is forbidden by R5 and was never created. |
| `FileSourceOpener` is `jvmAndAndroidMain` (line 11) | `androidMain`, `RealFlashTransferRepository.kt:40`. Unresolvable from `jvmMain`. |
| `FileRandomAccessSinkHandle` is `jvmAndAndroidMain` (line 11) | `androidMain`, `policy/DestinationPolicy.kt:91`, and it takes **two** parameters, not one. |
| `ChunkSource` is `commonMain` (line 11) | `androidMain`, `chunked/Chunker.kt:10` — it returns `java.io.InputStream`. |
| 14 `jvmAndAndroidMain` files, listing `FlashTransferState.kt`, `FlashTransferDirection.kt`, `FlashTransferId.kt`, `Sha256Impl.kt`, `StreamChannelFactory.kt`, `StreamMetrics.kt`, `MultiStreamResult.kt`, `FlashTransferProtocol.kt`, `DestinationTarget.kt`, `InMemoryTransferStore.kt` | None of those files exist. Four of the names are declarations **inside** `model/FlashTransfer.kt`, `multistream/StreamChannel.kt` and `multistream/MultiStreamProgress.kt`; the rest do not exist at all. Real layout: 5 `commonMain` + 15 `androidMain`. |
| `DestinationTarget.File`, `.Directory`, `.Temp`, each with `absolutePath` | `internal sealed interface` with exactly `FileTarget(file: File)` and `UriTarget(uriString, displayName)`. |
| `DestinationPolicy.createSinkHandle(target, suggestedFileName)` | `openSinkHandle(transferId, fileId, target, totalBytes)`, and the interface is `internal`. |
| `:core:security` (2 files) and `:core:discovery` (1 file) are dependencies | Neither edge exists in `core/transfer/build.gradle.kts`. |
| "the 4 wslegacy/ files" | `core/transfer/wslegacy/` was deleted by Phase 02. |
| `model/WsTransferModels.kt` is an orphan | **Correct**, and worth keeping: 6 `internal` declarations, zero inbound references repo-wide. It is nonetheless pinned to `androidMain` by its own *outbound* import of `:core:network`'s `WsTransferServer.PREFERRED_PORT` (line 3, used line 45) — which is what Phase 11 recorded. Both statements are true; they run in opposite directions. |
| `androidUnitTest` source set | `androidHostTest` (R5/R3.1). |
| `androidLibrary { }`, `libs.plugins.android.library` | `android { }` inside `kotlin { }`, `libs.plugins.android.kotlin.multiplatform.library`. |
| "Do NOT use typed source-set accessors" | Every module since Phase 06 uses them; `core/transfer/build.gradle.kts` uses `commonMain.dependencies { }`, `androidMain.dependencies { }`, `commonTest.dependencies { }`, `jvmTest.dependencies { }`. Only `androidHostTest` needs `getByName`. |
| `getByName("jvmMain") { dependsOn(getByName("jvmAndAndroidMain")) }` | Would fail configuration: no such source set. `jvmMain` already exists implicitly from `jvm()`. |
| `jvmJar` contains 14+3+5 = 22 class entries | 15 today, all from `commonMain`. |
| "`java.io.*` not found → verify `compileOptions` in the `androidLibrary` block" | Misdiagnosis. `java.io` is unavailable in `commonMain` by design (R6), not by a Java-version misconfiguration. |

## Do NOT

- Do **not** create `jvmAndAndroidMain`, and do not add a `dependsOn` edge between `androidMain` and
  `jvmMain` to work around this. R5 forbids it outright; it is what makes the Kotlin/Native half of
  the 2026-09-03 amendment reachable later.
- Do **not** move a `java.*`-using file into `commonMain` to make something compile (R2). If it does
  not compile there, it stays where it is until D10 says what replaces the `java.*` type.
- Do **not** duplicate a pipeline file into `jvmMain`. Two independent implementations of a wire
  format is what R8 exists to prevent, and the repo already carries one such duplicate
  (`app/…/net/AutoConnectGate.kt`) flagged as a Known issue by Phases 08 and 12.
- Do **not** touch `chunked/ChunkFrame.kt` (R8) in 13B-1 or 13B-2.
- Do **not** delete `model/WsTransferModels.kt` even though it is dead. It is a Known issue on the
  Phase 13 log entry; deleting it is not this phase's job (R1).









