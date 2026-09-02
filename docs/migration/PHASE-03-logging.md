# Phase 03 — Logging Abstraction

**Blocked by:** Phase 00. (Do Phase 02 first if convenient — it removes one of the
files listed here.)
**Risk:** low. Mechanical, but touches 8 files across 3 modules.
**Decisions needed:** none.

## Why this phase exists

Eight files are Android-coupled *only* because they call `android.util.Log`. Routing
them through one abstraction collapses that to a single file, which Phase 06 then
splits into `androidMain`/`jvmMain`. See AUDIT.md CORRECTION 4.

## Verified starting state

- `core/common/logging/FlashLogger.kt` is `internal` and is referenced **only by its
  own test** (`FlashLoggerTest.kt`). It is not used in production code anywhere.
- It already contains the Android forwarding logic in `forwardToAndroidLog`, wrapped
  in `try { } catch (_: Throwable) { }` for the JVM unit-test tier.
- The other 7 files call `android.util.Log` directly and do **not** go through it.
- **The module dependencies you need already exist.** `core/network/build.gradle.kts:56`
  has `api(project(":core:common"))` and `core/transfer` depends on `:core:common` too.
  You do not add any dependency in this phase.

  Ignore the comment at `core/network/.../resilience/ReconnectPolicy.kt:30` claiming
  `FlashTimeSource` "is not visible cross-module". That is a statement of *design
  intent* for that one class, not a fact about the dependency graph. It is stale prose;
  leave it alone (out of scope), but do not let it convince you the dependency is
  missing.

## Files in scope

Create:
```
core/common/src/main/java/com/transfer/flash/core/common/logging/FlashLogSink.kt
core/common/src/main/java/com/transfer/flash/core/common/logging/FlashPlatformLogSink.kt
core/common/src/main/java/com/transfer/flash/core/common/logging/FlashLog.kt
```

Modify:
```
core/common/src/main/java/com/transfer/flash/core/common/logging/FlashLogger.kt
core/network/src/main/java/com/transfer/flash/core/network/datachannel/DataChannelServer.kt
core/network/src/main/java/com/transfer/flash/core/network/datachannel/DataChannelClient.kt
core/network/src/main/java/com/transfer/flash/core/network/ws/WsTransferServer.kt
core/network/src/main/java/com/transfer/flash/core/network/ws/WsConnection.kt
core/network/src/main/java/com/transfer/flash/core/network/ws/WsSession.kt
core/network/src/main/java/com/transfer/flash/core/network/tcp/LanSession.kt
core/transfer/src/main/java/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt
```

Out of scope (Android coupling here is NOT logging, later phases handle it):
`core/engine/Flash.kt`, `core/discovery/nsd/*`, `core/network/DefaultFlashNetwork.kt`,
`core/network/resilience/AndroidNetworkWatcher.kt`, and everything in
`core/security` and `core/persistence`.

## Steps

### Step 0 — Promote `FlashLogLevel` (do this first, or Step 1 will not compile)

`FlashLogSink.write` (Step 1) is `public` and takes a `FlashLogLevel` parameter. But
`FlashLogLevel` in `core/common/.../logging/FlashLogEntry.kt` is declared
`internal enum class FlashLogLevel`. A `public` function may not expose an `internal`
type — the compiler rejects it with `EXPOSED_PARAMETER_TYPE: 'public' function exposes
its 'internal' parameter type FlashLogLevel`. This is a hard error, not a lint, and
`@FlashInternalApi` does **not** change it: that annotation is a `@RequiresOptIn`
marker (verified in `annotation/FlashAnnotations.kt`), so the declaration stays `public`
in the visibility sense and the exposure rule still applies.

Fix: open `core/common/src/main/java/com/transfer/flash/core/common/logging/FlashLogEntry.kt`
and change **only** the `FlashLogLevel` enum's visibility — add the opt-in marker and
`public`:

```kotlin
package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/** Severity of a [FlashLogEntry]. Mirrors the i/w/e levels of [FlashLogger]. */
@FlashInternalApi
public enum class FlashLogLevel {
    INFO,
    WARN,
    ERROR,
}
```

Leave `FlashLogEntry` (the data class below it in the same file) exactly as it is —
`internal data class FlashLogEntry(...)`. An `internal` class is allowed to reference a
`public` type, so nothing else in that file changes. Making the enum `public` with
`@FlashInternalApi` matches how `FlashLogSink`/`FlashLog` are gated (R7): cross-module
visible, not part of the published ABI.

Verify the reason this is safe: the enum has no external consumers today.

```bash
grep -rn --include=*.kt "FlashLogLevel" core/ ui/ app/ sample/
```

Expected: hits only inside `core/common` (the enum, `FlashLogEntry`, `FlashLogger`, and
after this phase the three new files). If any *other* module already names
`FlashLogLevel`, stop and record it — that would mean the ABI question is broader than
this phase assumes.

### Step 1 — `FlashLogSink.kt`

Pure Kotlin. No imports beyond the project's own. This is the seam.

```kotlin
package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Destination for Flash log records. One implementation per platform.
 *
 * Implementations MUST NOT throw. A logging failure must never propagate into a
 * caller's code path.
 */
@FlashInternalApi
public fun interface FlashLogSink {
    public fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    )
}
```

### Step 2 — `FlashPlatformLogSink.kt`

**This becomes the only file in `core/common` that references `android.*`.** Phase 06
(the KMP pilot, which converts `core:common`) turns it into an `expect`/`actual` pair
with an `androidMain` and a `jvmMain` side. Nothing here needs to anticipate that; just
keep the file single-purpose.

```kotlin
package com.transfer.flash.core.common.logging

import android.util.Log
import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Forwards records to `android.util.Log`.
 *
 * On the JVM unit-test tier `android.util.Log` methods throw "not mocked", so every
 * call is wrapped — forwarding failures are swallowed by design.
 *
 * Phase 06 of the multiplatform migration splits this into `androidMain` (this
 * implementation) and `jvmMain` (writes to stderr). Do not add non-logging logic here.
 */
@FlashInternalApi
public object FlashPlatformLogSink : FlashLogSink {
    override fun write(
        level: FlashLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val priority = when (level) {
            FlashLogLevel.INFO -> Log.INFO
            FlashLogLevel.WARN -> Log.WARN
            FlashLogLevel.ERROR -> Log.ERROR
        }
        try {
            val full = if (throwable != null) {
                "$message\n${Log.getStackTraceString(throwable)}"
            } else {
                message
            }
            Log.println(priority, tag, full)
        } catch (_: Throwable) {
            // Unit-test tier: android.util.Log is not mocked. Intentionally ignored.
        }
    }
}
```

### Step 3 — `FlashLog.kt`

The facade the other modules call. Marked `@FlashInternalApi` so it does not widen the
published public API (CONVENTIONS.md R7).

```kotlin
package com.transfer.flash.core.common.logging

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * Process-wide log entry point for Flash internals.
 *
 * Replaces direct `android.util.Log` calls so that non-Android targets can install a
 * different sink. Tag values are the structured tags from AGENTS.md §24
 * (DISCOVERY, LAN, CONNECTION, PAIRING, TLS, TRANSFER, CHUNK, STORAGE, ...).
 *
 * Never log secrets, keys, fingerprints, or user message content.
 */
@FlashInternalApi
public object FlashLog {

    @Volatile
    private var sink: FlashLogSink = FlashPlatformLogSink

    /** Replaces the active sink. Used by platform bootstrap and by tests. */
    public fun installSink(newSink: FlashLogSink) {
        sink = newSink
    }

    public fun i(tag: String, message: String, throwable: Throwable? = null) {
        sink.write(FlashLogLevel.INFO, tag, message, throwable)
    }

    public fun w(tag: String, message: String, throwable: Throwable? = null) {
        sink.write(FlashLogLevel.WARN, tag, message, throwable)
    }

    public fun e(tag: String, message: String, throwable: Throwable? = null) {
        sink.write(FlashLogLevel.ERROR, tag, message, throwable)
    }
}
```

### Step 4 — Rewire `FlashLogger.kt`

Two edits, nothing else.

**4a.** Delete the line `import android.util.Log`.

**4b.** Replace the entire body of `forwardToAndroidLog` with the delegation below, and
rename it to `forwardToSink`. Its signature does not change.

```kotlin
    private fun forwardToSink(
        level: FlashLogLevel,
        message: String,
        throwable: Throwable?,
    ) {
        when (level) {
            FlashLogLevel.INFO -> FlashLog.i(tag, message, throwable)
            FlashLogLevel.WARN -> FlashLog.w(tag, message, throwable)
            FlashLogLevel.ERROR -> FlashLog.e(tag, message, throwable)
        }
    }
```

Then update the one call site inside `private fun log(...)`: change
`forwardToAndroidLog(` to `forwardToSink(`. Do not change anything else in `log()` —
the ring-buffer append must still happen before the forward, exactly as it does now.

`FlashLogger` is `internal`, so it needs the opt-in marker to touch `FlashLog`. Add as
the first line of the file, before `package`:

```kotlin
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)
```

The `try { } catch (_: Throwable) { }` that used to wrap the Android call is **not**
needed here any more — it now lives in `FlashPlatformLogSink`. Delete it along with the
old body. Update the KDoc at the old lines 27–30 and 112–115 that describes the JVM
`android.util.Log` fallback: the fallback moved, so point it at `FlashLogSink`.

**`FlashLogger.kt` must end with zero `android.*` references**, and its existing test
`FlashLoggerTest.kt` must still pass unchanged. Do not modify the test.

### Step 5 — Rewrite the 7 call sites

For each of the 6 `core/network` files and `RealFlashTransferRepository.kt`:

1. Delete `import android.util.Log`.
2. Add `import com.transfer.flash.core.common.logging.FlashLog`.
3. Add the file-level opt-in if not already present:
   `@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)`
   — it must be the **first line**, before `package`.
4. Translate calls, preserving the tag and message exactly:

| Was | Becomes |
|---|---|
| `Log.d(TAG, m)` | `FlashLog.i(TAG, m)` |
| `Log.i(TAG, m)` | `FlashLog.i(TAG, m)` |
| `Log.v(TAG, m)` | `FlashLog.i(TAG, m)` |
| `Log.w(TAG, m)` | `FlashLog.w(TAG, m)` |
| `Log.e(TAG, m)` | `FlashLog.e(TAG, m)` |
| `Log.e(TAG, m, t)` | `FlashLog.e(TAG, m, t)` |

`FlashLogLevel` has only INFO/WARN/ERROR, so DEBUG and VERBOSE collapse to INFO.
**Record this in the log** — it is a real behaviour change: previously-filtered
`Log.d`/`Log.v` output now appears at INFO.

⚠️ `RealFlashTransferRepository.kt` uses **fully-qualified** `android.util.Log` at
lines 121, 133, 349, 496 with no import. Search for `android.util.Log` as text, not
just `Log.`, or you will miss them.

### Step 6 — Sweep for stragglers

```bash
grep -rn --include=*.kt "android.util.Log" core/ | grep -v "FlashPlatformLogSink"
```

Everything remaining must be in a file this phase declared out of scope. List what
remains in the log so the next agent knows it is deliberate.

## Verification

```bash
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```

Must pass, including `FlashLoggerTest` unmodified.

Then confirm the coupling dropped — these two greps are the phase's actual deliverable:

```bash
grep -rln --include=*.kt -E "android\.util\.Log" core/network/src/main core/transfer/src/main
```
Expected: **no output**.

```bash
grep -rln --include=*.kt -E "^import android\.|android\.util\.Log" core/common/src/main
```
Expected: **only** `FlashPlatformLogSink.kt`.

## Do NOT

- Do not change any log message text, tag, or level mapping beyond the table above.
- Do not modify `FlashLoggerTest.kt`. If it fails, your `FlashLogger` change is wrong.
- Do not add a logging library (Timber, kotlin-logging, slf4j).
- Do not make `FlashLog` public API without `@FlashInternalApi` — it would widen the
  published ABI.
- Do not convert anything to `expect`/`actual` in this phase. The whole module is still
  a single Android source set; `expect` will not compile. Phase 06 does that.
- Do not touch the out-of-scope files listed above, even though they import `Log`.

## Completion checklist

- [ ] `FlashLogLevel` promoted to `public` + `@FlashInternalApi` (Step 0); `FlashLogEntry` left `internal`
- [ ] 3 new files created exactly as specified
- [ ] `FlashLogger.kt` has no `android.*` reference; its test passes unmodified
- [ ] All 6 `core/network` files converted
- [ ] `RealFlashTransferRepository.kt` converted, including all 4 fully-qualified sites
- [ ] DEBUG/VERBOSE→INFO collapse recorded in the log as a behaviour change
- [ ] Both verification greps produce the expected output
- [ ] Build + tests pass
- [ ] Log entry appended

## Rollback

`git revert <sha>`. Self-contained — no build files change in this phase.
