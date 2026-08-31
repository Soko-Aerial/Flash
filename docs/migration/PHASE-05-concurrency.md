# Phase 05 — Concurrency Primitives Audit

**Blocked by:** Phase 00. Phase 02 should be done first (removes `wslegacy`, which has
25 `synchronized` blocks that would otherwise pollute the inventory).
**Risk:** low **if you follow the scope**. This phase is 90% analysis. The default
outcome is that you change **nothing** except the log.
**Decisions needed:** none to execute. This phase produces the evidence that answers
**D1** — read its output before answering D1 in `DECISIONS.md`.

## Read this first

You are **not** refactoring concurrency in this phase. Flash is a networking and file
transfer engine; its locking is load-bearing and its bugs would be intermittent races
that pass CI and fail on a real device under load. Rewriting synchronization is the
highest-risk change available in this codebase and the lowest-value one.

Your job is to produce an accurate inventory, state the cost of D1 = B, and stop.

## The portability facts (verified against the Kotlin API reference)

The project is on **Kotlin 2.2.10** (`gradle/libs.versions.toml`). Do not bump it
(CONVENTIONS.md R10). Against that version:

| Primitive used in Flash | Available in `commonMain`? | Notes |
|---|---|---|
| `synchronized(lock) { }` | **No** | JVM-only intrinsic. `kotlin.jvm.Synchronized` is a JVM annotation. There is no common equivalent. |
| `@Volatile` (bare, as written today) | **No** | Resolves to `kotlin.jvm.Volatile`, a default import only in JVM source sets. |
| `kotlin.concurrent.Volatile` | **Yes** | Common `expect annotation class`, **Since Kotlin 1.9**, **no opt-in required**. On JVM it is `actual typealias Volatile = kotlin.jvm.Volatile` — byte-for-byte identical semantics. |
| `java.util.concurrent.atomic.Atomic*` | **No** | JVM-only. |
| `kotlin.concurrent.atomics.AtomicInt` / `AtomicLong` / `AtomicBoolean` / `AtomicReference` | Yes, **but experimental** | Since Kotlin 2.1. Marked `@ExperimentalAtomicApi` with `RequiresOptIn.Level.ERROR` — using them without `@OptIn` is a compile **error**, and the API may change in a future Kotlin release. |
| `ConcurrentHashMap` | **No** | No common equivalent exists in the stdlib. Replacement is a `Mutex`-guarded `MutableMap`, or `kotlinx.collections.immutable`. |
| `ConcurrentHashMap.newKeySet()` / `Collections.newSetFromMap` | **No** | Same. |
| `ConcurrentLinkedQueue` | **No** | Same. |
| `CopyOnWriteArrayList` | **No** | Same. |
| `Collections.synchronizedList` | **No** | Same. |
| `ReentrantLock`, `Lock.withLock` | **No** | JVM-only extensions. |
| `ThreadLocalRandom` | **No** | JVM-only. `kotlin.random.Random` is the common replacement. |
| `java.util.concurrent.Executor` | **No** | JVM-only. |
| `kotlinx.coroutines.*` (`Job`, `Mutex`, `CompletableDeferred`, `StateFlow`) | **Yes** | Already multiplatform. Flash uses these heavily already. |

Sources: [kotlin.concurrent](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.concurrent/),
[kotlin.concurrent.atomics](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.concurrent.atomics/).

**All of the "No" rows are available on both Android and desktop JVM.** They are only a
problem in a strict `commonMain`.

## Steps

### Step 1 — Regenerate the inventory

```bash
grep -rn --include=*.kt -E "java\.util\.concurrent|ConcurrentHashMap|CopyOnWriteArray|Atomic(Integer|Long|Boolean|Reference)|ReentrantLock|Executors|@Volatile|synchronized|Collections\.synchronized|ThreadLocal|Thread\(" core/*/src/main ui/*/src/main app/src/main
```

Paste the full raw output into your log entry. It is long. Paste it anyway — Phase 07
and Phase 09 both work from it, and D1 is answered from it.

### Step 2 — Count it and compare

Fill in this table in your log. The right-hand column is what the inventory showed when
this phase file was written (post-Phase-02, so `wslegacy` excluded).

| Module | Production files using JVM-only concurrency | Heaviest file |
|---|---|---|
| `core/network` | 11 | `ws/WsFlashNetwork.kt` — 7 `ConcurrentHashMap` fields, 3 `synchronized(registryLock)` blocks, `AtomicBoolean`, `AtomicInteger`, `ConcurrentLinkedQueue`, `ThreadLocalRandom` |
| `core/transfer` | 5 | `multistream/MultiStreamDispatcher.kt` — 12 `synchronized(terminalLock)` blocks, 9 `@Volatile` fields, `AtomicBoolean`/`AtomicInteger`/`AtomicLong`, `Collections.synchronizedList` |
| `core/discovery` | 4 | `nsd/NsdTransport.kt` — 9 `@Volatile` fields, 4 `synchronized`, a `java.util.concurrent.Executor` |
| `core/engine` | 2 | `Flash.kt` — 6 `ConcurrentHashMap`, 3 `@Volatile`, `Collections.newSetFromMap` |
| `core/messaging` | 1 | `RealFlashChatRepository.kt` — nested `ConcurrentHashMap` |
| `core/common` | 1 | `logging/FlashLogger.kt` — 3 `synchronized(lock)` |
| `ui/theme` | 1 | `FlashSounds.kt:236` — 1 `synchronized(lock)` |
| `app` | 2 | `debug/DiscoveryEngineHolder.kt`, `pairing/PairingCoordinator.kt` |
| **Total production files** | **~27** | |

If your numbers differ materially, the code moved. Record both numbers.

### Step 3 — Write the D1 cost statement into the log

Copy this into your log entry, correcting any number that your Step 2 count contradicts:

> **Concurrency cost of D1 = B (strict `commonMain`).** ~27 production files use
> JVM-only concurrency primitives. Converting them requires:
> (a) `synchronized(lock) { }` → `Mutex.withLock { }`, which is a **`suspend`**
> function. Every enclosing function becomes `suspend`, and that propagates up the call
> chain. `MultiStreamDispatcher.deadChannelsSnapshot()`,
> `TransferCompletionStateMachine.currentPhase`, and `CompositeDiscovery.refreshState()`
> are currently synchronous property getters and cannot become `suspend` without
> changing their callers' signatures too.
> (b) ~20 `ConcurrentHashMap` fields → `Mutex`-guarded maps, losing lock-free reads on
> the frame hot path (`WsFlashNetwork.kt:125` documents that this is deliberate).
> (c) atomics → `kotlin.concurrent.atomics`, which is `@ExperimentalAtomicApi` at
> `RequiresOptIn.Level.ERROR` on Kotlin 2.2.10.
> **Assessment: high risk, no functional benefit for an Android + desktop-JVM product.**
> Recommend **D1 = A**.

### Step 4 — The one optional code change, gated on D1

**If D1 is still unanswered, or D1 = A: skip this step entirely.** Record
"Step 4 skipped — D1 = A / unanswered" in the log and go to Verification.

**Only if D1 = B:** replace bare `@Volatile` with the multiplatform annotation. This is
the single zero-risk portability change available, because on JVM
`kotlin.concurrent.Volatile` is a `typealias` to `kotlin.jvm.Volatile` — the generated
bytecode is identical.

For each file containing `@Volatile`, add the import:

```kotlin
import kotlin.concurrent.Volatile
```

The `@Volatile` usages themselves do not change. Affected files (~35 sites):

```
core/discovery/.../nsd/NsdTransport.kt          (9 sites)
core/discovery/.../nsd/NsdFlashDiscovery.kt     (1)
core/discovery/.../core/CompositeDiscovery.kt   (1)
core/engine/.../Flash.kt                        (3)
core/network/.../DefaultFlashNetwork.kt         (1)
core/network/.../datachannel/DataChannelServer.kt (1)
core/network/.../resilience/AndroidNetworkWatcher.kt (1)
core/network/.../resilience/BoundedSendQueue.kt  (1)
core/network/.../resilience/ChaosSession.kt      (2)
core/network/.../tls/SecureSocketUpgrader.kt     (1)
core/network/.../ws/WsConnection.kt              (1)
core/network/.../ws/WsTransferServer.kt          (1)
core/transfer/.../multistream/MultiStreamDispatcher.kt (9)
core/transfer/.../policy/DestinationPolicy.kt    (1)
app/.../debug/DiscoveryEngineHolder.kt           (7)
app/.../pairing/PairingCoordinator.kt            (1)
```

Note `kotlin.concurrent.Volatile` has `@Target(AnnotationTarget.FIELD)` only. Every
current usage in Flash is on a `var` property backing field, so all are valid. If the
compiler rejects one, that site was annotating something else — leave it as
`kotlin.jvm.Volatile` and record it.

## Verification

```bash
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```

Must pass. If you skipped Step 4 this is a no-op confirmation that you changed nothing;
run it anyway so the log has a substantiated PASS.

The real deliverable of this phase is in the log, not the diff. Confirm your log entry
contains:
- the full Step 1 grep output
- the completed Step 2 table with your own counts
- the Step 3 cost statement

## Do NOT

- **Do not convert any `synchronized` block to `Mutex`.** Not one. Not even "just the
  easy ones in `FlashLogger`". `Mutex.withLock` is `suspend`; the conversion is viral.
- **Do not replace `ConcurrentHashMap`.** `WsFlashNetwork.kt:125` documents the
  lock-free-read requirement explicitly; `MultiStreamDispatcher`'s comment at line 43
  documents the "tiny synchronized blocks guard snapshots" design. Both are deliberate.
- **Do not adopt `kotlin.concurrent.atomics`.** It is `@ExperimentalAtomicApi` at ERROR
  level. Adding an experimental stdlib API to a published 1.0.0 library is not
  acceptable here.
- **Do not add `atomicfu`.** It is a compiler plugin with its own build integration and
  it changes the ABI. Out of scope, and it would need its own ADR.
- Do not "clean up" `app/.../debug/DiscoveryEngineHolder.kt`. It duplicates `Flash.kt`
  logic and looks like dead debug scaffolding, but confirming that is a separate task —
  record it under **Known issues** instead.
- Do not touch `core/network/.../resilience/ChaosNetworkHarness.kt` or `ChaosSession.kt`
  beyond the inventory. They are test infrastructure that happens to live in `src/main`;
  note that fact in the log.

## Completion checklist

- [ ] Step 1 grep output pasted into the log in full
- [ ] Step 2 table filled with your own counts, differences noted
- [ ] Step 3 D1 cost statement recorded verbatim (with corrected numbers)
- [ ] Step 4 either skipped-and-recorded, or done with the import added per file
- [ ] Build + tests pass
- [ ] Zero `synchronized`/`ConcurrentHashMap`/atomic conversions performed
- [ ] Log entry appended

## Rollback

If Step 4 was skipped there is nothing to roll back. If it was performed,
`git revert <sha>` — it is import-only.

