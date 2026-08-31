# Phase 04 — Time, IDs, Locale

**Blocked by:** Phase 00. Phase 02 must be done first (it deletes the only
`android.os.SystemClock` usage in the repo).
**Risk:** low, but this phase has a **large temptation to over-reach**. Read the
"Do NOT" section before you start, not after.
**Decisions needed:** none to execute. D1 determines how much of this matters — see
"Why this phase is small".

## Why this phase is small

Read this before touching anything.

Both migration targets are **JVM-based**: Android (ART, `java.*` subset) and desktop
JVM. Every API in the inventory below — `System.currentTimeMillis()`, `java.util.UUID`,
`java.util.Locale`, `java.text.SimpleDateFormat`, `java.util.Date`, `java.util.BitSet` —
exists on **both**.

That means:

- If **D1 = A** (`jvmAndAndroidMain` intermediate source set), none of these need to
  change at all. They compile unchanged in shared code.
- If **D1 = B** (strict `commonMain`, no `java.*`), every one of them needs an
  `expect`/`actual` seam — a much larger job, done in Phase 07, not here.

Either way, **this phase does not rewrite call sites.** Its job is to fix the three
things that are wrong regardless of D1, and to produce the inventory Phase 07 works
from. Nothing more.

Also relevant: `kotlin.time.Clock` / `kotlin.time.Instant` — the stdlib multiplatform
clock that would be the natural target — is **`Since Kotlin 2.3`**. This project is on
Kotlin **2.2.10** (`gradle/libs.versions.toml`), so it is **not available**. Do not bump
Kotlin to get it (CONVENTIONS.md R10). Do not add `kotlinx-datetime` either. The project
already has its own seam (`FlashTimeSource`); that is what gets used.

## Verified starting state

Two abstractions already exist in `core/common`:

| File | Visibility | Production consumers |
|---|---|---|
| `core/common/.../time/FlashTimeSource.kt` | `public` interface + `public object SystemTimeSource` | `app/.../pairing/PairingCoordinator.kt:55`, `core/security/.../pairing/FlashPairingProtocol.kt:115`, `core/common/.../logging/FlashLogger.kt:38` |
| `core/common/.../id/FlashIdGenerator.kt` | **`internal`** interface + **`internal object UuidIdGenerator`** | **none** — referenced only by `core/common/src/test/.../FlashIdGeneratorTest.kt` |

So `FlashIdGenerator` has the same defect `FlashLogger` had in Phase 03: it is
`internal`, therefore unusable from `core/network`, `core/transfer`, `core/messaging`,
`core/security`, or `core/engine`. Every one of those modules calls
`UUID.randomUUID()` directly instead.

## Steps

### Step 1 — Make `FlashIdGenerator` cross-module usable

Same pattern as Phase 03. Edit
`core/common/src/main/java/com/transfer/flash/core/common/id/FlashIdGenerator.kt`:

```kotlin
package com.transfer.flash.core.common.id

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Injectable identifier generator (C0.4). Consumers depend on this interface;
 * production wiring uses [UuidIdGenerator], tests inject a deterministic fake.
 *
 * `@FlashInternalApi` rather than plain `public`: cross-module visible without
 * widening the published library ABI (CONVENTIONS.md R7).
 */
@FlashInternalApi
public interface FlashIdGenerator {
    /** Returns a fresh, unique identifier string. */
    public fun newId(): String
}

/**
 * UUID v4 generator backed by [java.util.UUID.randomUUID]. Production default.
 *
 * `java.util.UUID` is available on both Android and desktop JVM, so this is portable
 * as-is under a `jvmAndAndroidMain` source set. Under a strict-`commonMain` layout it
 * becomes the `actual` side of an `expect` — see Phase 07.
 */
@FlashInternalApi
public object UuidIdGenerator : FlashIdGenerator {
    override fun newId(): String = java.util.UUID.randomUUID().toString()
}
```

`FlashIdGeneratorTest.kt` must pass **unmodified**. It is in the same module, so
widening `internal`→`public` cannot break it. Do not edit it.

Do **not** now go and rewire the 13 `UUID.randomUUID()` call sites to use this. See
Step 3.

### Step 2 — Fix the locale-sensitivity bug at `Flash.kt:668`

This is a real correctness bug, not a migration concern, and it is a one-word fix.

`core/engine/src/main/java/com/transfer/flash/core/engine/Flash.kt:668` reads:

```kotlin
val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
```

A **file extension is machine data, not display text.** `Locale.getDefault()` makes the
result depend on the user's locale: in Turkish (`tr`), `"PNG".lowercase()` yields
`"pnğ"`-class surprises — specifically `I` lowercases to `ı` (dotless i), so `"JPI"`,
`"TIFF"`, `"GIF"`, `"MIDI"` and anything else containing `I` produce an extension string
that will not match a lowercase ASCII comparison. On a Turkish-locale device, extension
matching silently misbehaves today.

Change it to:

```kotlin
val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
```

`Locale.ROOT` is the correct choice for locale-insensitive case folding, and it is
identical on Android and desktop JVM.

For comparison, `core/discovery/.../core/CompositeDiscovery.kt:115` already does this
correctly (`transportName.uppercase(Locale.ROOT)`). Match that precedent.

**Leave these alone** — they are user-facing display text where locale sensitivity is
correct behaviour:

- `core/messaging/.../RealFlashChatRepository.kt:1006–1007` — `uppercase(Locale.getDefault())`
  to build avatar initials from a person's display name.
- `core/messaging/.../RealFlashChatRepository.kt:1012, 1021` —
  `SimpleDateFormat("h:mm a", Locale.getDefault())` and `SimpleDateFormat("MMM d", ...)`
  for timestamps shown to the user.

Do not "fix" those to `Locale.ROOT`. That would be a user-visible regression.

### Step 3 — Produce the inventory. Do not act on it.

Run this and paste the raw output into your log entry:

```bash
grep -rn --include=*.kt -E "java\.util\.UUID|UUID\.randomUUID|java\.util\.Locale|Locale\.|java\.text\.SimpleDateFormat|java\.util\.Date|System\.currentTimeMillis|SystemClock|java\.util\.BitSet|java\.util\.Calendar" core/ | grep "/src/main/"
```

The inventory below is what it returned when this phase file was written (after Phase 02
deletes `wslegacy/`). Diff your output against it. If you find a site not listed here,
add it to the log under **Deviations** — it means the code moved since.

**`UUID.randomUUID()` — 13 production sites, 8 files**

| File | Lines |
|---|---|
| `core/transfer/.../RealFlashTransferRepository.kt` | 147, 149, 453 |
| `core/messaging/.../RealFlashChatRepository.kt` | 339, 390, 460, 521 |
| `core/network/.../ws/WsSession.kt` | 83, 93 |
| `core/network/.../tcp/LanSession.kt` | 162, 189 |
| `core/security/.../pairing/FlashPairingProtocol.kt` | 344 |
| `core/security/.../identity/AndroidPreferencesIdentityStore.kt` | 26 |
| `core/engine/.../Flash.kt` | 152 |
| `core/common/.../id/FlashIdGenerator.kt` | 14 (the intended seam) |

**`System.currentTimeMillis()` — production sites**

| File | Lines |
|---|---|
| `core/messaging/.../RealFlashChatRepository.kt` | 312, 338, 389, 442, 459, 518, 592, 655, 773, 844, 1015 |
| `core/messaging/.../FlashChatRepository.kt` | 147, 215 |
| `core/engine/.../Flash.kt` | 423, 437, 448, 472, 659 |
| `core/network/.../ws/WsConnection.kt` | 62, 71, 80, 141 |
| `core/network/.../ws/WsSession.kt` | 83, 93 |
| `core/network/.../tcp/LanSession.kt` | 420 (`private fun nowMs()`) |
| `core/network/.../DefaultFlashNetwork.kt` | 203 |
| `core/transfer/.../manifest/TransferManifest.kt` | 29 (default parameter value) |
| `core/security/.../crypto/KeystoreFlashCrypto.kt` | 110 |
| `core/common/.../time/FlashTimeSource.kt` | 15 (the intended seam) |
| `core/discovery/.../core/CompositeDiscovery.kt` | 85 (already injected as `clock: () -> Long`) |

**Other JVM stdlib**

| API | File | Lines |
|---|---|---|
| `java.util.BitSet` | `core/transfer/.../chunked/ResumeBitVector.kt` | 3 |
| `java.util.Date` | `core/security/.../crypto/KeystoreFlashCrypto.kt` | 17 |
| `java.text.SimpleDateFormat`, `java.util.Date`, `java.util.Locale` | `core/messaging/.../RealFlashChatRepository.kt` | 35–37 |
| `java.util.Locale` | `core/engine/.../Flash.kt`, `core/discovery/.../CompositeDiscovery.kt` | 44, 18 |
| `android.os.SystemClock` | — | **none remaining** (was `wslegacy/` only) |

Confirm that last row yourself:

```bash
grep -rn --include=*.kt "SystemClock" core/ ui/ app/
```

Expected: no output. If there is output, Phase 02 was not completed.

## Verification

```bash
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```

Must pass. `FlashIdGeneratorTest` and `FlashTimeSourceTest` must pass unmodified.

Then:

```bash
grep -rn --include=*.kt "internal interface FlashIdGenerator\|internal object UuidIdGenerator" core/
```
Expected: **no output** (both are now `public` with `@FlashInternalApi`).

```bash
grep -n "Locale" core/engine/src/main/java/com/transfer/flash/core/engine/Flash.kt
```
Expected: the import, plus line 668 now reading `lowercase(Locale.ROOT)`.

## Do NOT

- **Do not rewire the 13 `UUID.randomUUID()` call sites to `UuidIdGenerator`.** That is
  a dependency-injection refactor across 8 files in 5 modules, it changes constructor
  signatures on public classes, and it is not needed for the migration. It is a
  legitimate future improvement; record it under **Known issues** in your log and stop.
- **Do not thread `FlashTimeSource` through the ~25 `System.currentTimeMillis()` call
  sites.** Same reasoning, larger blast radius. `WsConnection`'s heartbeat deadline and
  `DefaultFlashNetwork`'s retry timestamp are load-bearing timing logic; changing how
  they read the clock risks real behaviour change for zero migration benefit.
- Do not add `kotlinx-datetime`.
- Do not bump Kotlin to 2.3 to get `kotlin.time.Clock`.
- Do not convert anything to `expect`/`actual` in this phase. That is Phase 07, and it
  only happens at all if D1 = B.
- Do not touch `TransferManifest.kt:29`. `createdAtMs: Long = System.currentTimeMillis()`
  is a default parameter on a wire-adjacent model; CONVENTIONS.md R8 protects it.
- Do not change `SimpleDateFormat` patterns or `Locale.getDefault()` in
  `RealFlashChatRepository.kt`.

## Completion checklist

- [ ] `FlashIdGenerator` + `UuidIdGenerator` are `public` with `@FlashInternalApi`
- [ ] `FlashIdGeneratorTest.kt` unmodified and passing
- [ ] `Flash.kt:668` uses `Locale.ROOT`, with the reason recorded in the log
- [ ] Full inventory grep output pasted into the log
- [ ] `SystemClock` grep returns nothing
- [ ] Build + tests pass
- [ ] Known issues section lists the two deferred refactors named above
- [ ] Log entry appended

## Rollback

`git revert <sha>`. Three small edits, no build-file changes.

