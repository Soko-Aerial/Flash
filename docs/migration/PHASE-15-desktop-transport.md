# Phase 15 — Desktop transport (`core:network`)

> **CORRECTION — 2026-09-06. Read this before anything below it.**
>
> This file was written on 2026-08-31, before **D1 was answered as Option B**. Its plan is built on
> an intermediate source set that does not exist and must never be created, so **Steps 3–12 as
> written are void**. Everything in the correction below was measured in the repo at `ff1d36a`; the
> phase's *goal* and its *Do NOT* list both survive intact.
>
> **Defect 1 — the whole seam strategy is `jvmAndAndroidMain`-based, and that tier is forbidden.**
> The header requires the shared WS plumbing to be in *"`commonMain` or `jvmAndAndroidMain`"*; the
> placement table puts `WsLog.kt` and a new `WsFlashNetworkBase.kt` there and moves
> `WsTransferServer`/`WsConnection`/`WsSession`/`WsTransferClient` there; Seams 1–3 are all
> "extract into `jvmAndAndroidMain`". **`jvmAndAndroidMain` has never existed in this repo**, and
> **R5 plus D1 = Option B forbid creating it** (`git log --diff-filter=A -- '*/jvmAndAndroidMain/*'`
> is empty; `find . -name jvmAndAndroidMain -type d` finds nothing). Its own precondition 4 —
> *"D1 = A in `DECISIONS.md` (jvmAndAndroidMain intermediate source set exists)"* — is therefore not
> merely unmet but **unachievable**. Under D1 = B there are exactly two destinations for a file:
> `commonMain` (and then it may use neither `java.*` nor `android.*`) or one platform source set
> (and then the other platform gets nothing). There is no shared-JVM middle.
>
> **Defect 2 — the "Phase 10 correction note" is already satisfied and describes a state that never
> occurred.** Lines 15–18 say Phase 10 placed `LanProbeServer.kt` in `jvmAndAndroidMain` and that
> this is a `compileKotlinJvm` error needing an in-place fix. `LanProbeServer.kt` is in
> **`androidMain`** today and has been since Phase 10; `compileKotlinJvm` is green and has been for
> five phases. There is nothing to correct. The underlying observation is still true and still
> useful — its `onPeerProbed` callback references `LanSession`, which is `androidMain` — so the file
> cannot move to `commonMain` while that signature stands. That is a fact about a future sub-step,
> not a defect to repair.
>
> **Defect 3 — the census in "Why `core:network` needs a new `jvmMain` class" is wrong in every
> column.** It claims 13 `commonMain`, 9 `jvmAndAndroidMain`, 12 `androidMain`. Measured at
> `ff1d36a`:
>
> | Source set | This file claims | Actually (2026-09-06) |
> |---|---|---|
> | `commonMain` | 13 | **14** |
> | `jvmAndAndroidMain` | 9 | **does not exist** |
> | `androidMain` | 12 | **21** |
> | `jvmMain` | (1 new) | **0** |
> | `commonTest` | — | **7** (was 1 before Phase 15-1) |
> | `androidHostTest` | — | **15** (was 21; 14 suites + `SoftwareCertMaker`) |
> | `jvmTest` | — | **0** |
>
> The nine files it wanted in `jvmAndAndroidMain` are all in `androidMain`. That is not a placement
> error: it is the only legal place for them under D1 = B, because every one of them needs `java.*`.
>
> **Defect 4 — two of the four task names in its verification block do not exist.** It calls
> `:core:network:compileKotlinAndroid` and `:core:network:androidHostTest`. Per **R3.1** the real
> names are `compileAndroidMain`, `testAndroidHostTest`, `compileKotlinJvm` and `jvmTest`. A copy of
> its verification block will fail with *"Task 'compileKotlinAndroid' not found"* — and, worse, its
> baseline claim of *"102 tests across 19 classes"* via `testDebugUnitTest` is from the pre-KMP
> build. The measured baseline for this module is in the table above and in Phase 15-1's log entry.
>
> **What survives the correction, unchanged and binding:** the phase's purpose (give the desktop a
> `FlashNetwork` that speaks the *same* WS wire protocol as Android's `WsFlashNetwork`), and the whole
> **Do NOT** list — do not change `PROTOCOL_VERSION`, `HELLO_PREFIX`, port 45822, the handshake or the
> frame encoding; do not modify `WebSocketCodec.kt`, `SecureSocketUpgrader.kt`,
> `FlashTlsContextFactory.kt`, `TofuX509TrustManager.kt` or `FlashPinVerifier.kt` (R8-sensitive); do
> not touch `DataChannelClient.kt`/`DataChannelServer.kt`; do not delete `LanProbeServer.kt`; do not
> introduce new external dependencies; do not wire the engine composition root (that is Phase 21); do
> not change `WsFlashNetwork`'s public API, because `Flash.kt:170` must keep compiling.
>
<!-- CORRECTION-BLOCK-CONTINUES -->

**Blocked by:** Phases 13 (desktop file I/O) and 14 (desktop discovery). Phase 10 (core:network KMP)
must be complete — the commonMain `FlashNetwork` contract, `WsConnection.Listener`, `TlsOptions`,
`WebSocketCodec`, `SecureSocketUpgrader`, and all resilience policies must be in `commonMain` or
`jvmAndAndroidMain` so the new desktop code can import them.

**Risk: HIGH.** This is the first desktop-side implementation of a concrete `FlashNetwork` transport
that must interoperate wire-protocol-compatibly with Android's existing `WsFlashNetwork`. Unlike
Phases 13/14 (which add new platform implementations behind existing interfaces), Phase 15 must
**split** several deeply Android-bound files, introduce a log seam (reserved by Phase 10), and
create a `jvmMain` `WsFlashNetwork` equivalent — all without changing the Android-side wire
behavior.

**Phase 10 correction note:** `LanProbeServer.kt` was placed in `jvmAndAndroidMain` by Phase 10
(line 5 of the placement table), but its callback type `onPeerProbed: (LanProbeHello, String, LanSession) -> Unit`
references `LanSession` (androidMain). This is a compile error for `compileKotlinJvm`. See the
"Correction note" in the Steps section below.

---

## What this phase is actually for

Give the desktop JVM a concrete `FlashNetwork` implementation that uses the **same WebSocket wire
protocol** as Android's `WsFlashNetwork` — RFC 6455 WebSockets with `FLASH_WS_HELLO` handshake,
`PROTOCOL_VERSION = 2`, port 45822, TOFU-pinned TLS on the same `SecureSocketUpgrader` — so that
desktop and Android peers discover each other and transfer files over a single protocol.

The desktop transport is **not** a port or rewrite of `WsFlashNetwork`. It is a new `jvmMain`
implementation (`JvmWsFlashNetwork`) that reuses the shared WS plumbing classes (after moving them
to `jvmAndAndroidMain` with a log seam) and implements the `FlashNetwork` contract using
`java.net.NetworkInterface` for local address enumeration instead of Android's `ConnectivityManager`.

**What this phase does NOT do:**
- Does NOT change the existing Android `WsFlashNetwork` implementation (it stays in `androidMain`
  and continues to work exactly as before).
- Does NOT implement the full engine composition root (`Flash.create()`) — that is deferred to
  Phase 21 (desktop app shell). This phase only creates the `FlashNetwork` transport that the
  engine will consume.
- Does NOT implement desktop discovery integration — `CompositeDiscovery` (jvmAndAndroidMain)
  already accepts any `FlashRadioTransport`; Phase 14's `JmmsFlashDiscovery` connects to
  `JvmWsFlashNetwork` via the same `FlashNetwork.connect()`/`FlashNetwork.connectManual()` API.
- Does NOT implement `LanController`/`DefaultFlashNetwork` for desktop — those are dead code
  (confirmed: no production instantiation exists anywhere in the current tree).
- Does NOT delete `LanProbeServer` from `jvmAndAndroidMain` — it corrects the placement (see
  correction note). The server is harmless dead code on desktop.

---

## Why `core:network` needs a new `jvmMain` class

Post-Phase 10, the module has:

```
core:network/src/
├── commonMain/kotlin/          (13 files — contracts, state models, policies)
│   ├── FlashNetwork.kt
│   ├── FlashSession.kt
│   ├── FlashConnectionHealth.kt
│   ├── FlashConnectionState.kt
│   ├── FlashNetworkState.kt
│   ├── bridge/DiscoveryRouteBinder.kt
│   ├── resilience/ (ConnectionHealthAggregator, HeartbeatPolicy, HeartbeatTracker,
│   │                ReconnectPolicy, SessionHardeningPolicy)
│   ├── tcp/LanProbeMessages.kt
│   └── tls/FlashPinVerifier.kt
│
├── jvmAndAndroidMain/kotlin/   (9 files — JDK-backed, no android.*)
│   ├── datachannel/DataChannelFraming.kt
│   ├── resilience/BoundedSendQueue.kt, ChaosNetworkHarness.kt, ChaosSession.kt
│   ├── tcp/LanProbeServer.kt        ← CORRECTION NEEDED (see below)
│   ├── tls/FlashTlsContextFactory.kt, SecureSocketUpgrader.kt, TofuX509TrustManager.kt
│   └── ws/WebSocketCodec.kt
│
├── androidMain/kotlin/         (12 files — android.* imports)
│   ├── DefaultFlashNetwork.kt
│   ├── resilience/AndroidNetworkWatcher.kt
│   ├── tcp/LanConnectionProbe.kt
│   ├── util/LocalNetworkAddresses.kt
│   ├── ws/WsFlashNetwork.kt          ← STAYS androidMain (Context + AndroidNetworkWatcher)
│   ├── ws/WsTransferClient.kt        ← MOVES to jvmAndAndroidMain (with seam)
│   ├── ws/WsConnection.kt            ← MOVES to jvmAndAndroidMain (with seam)
│   ├── ws/WsSession.kt               ← MOVES to jvmAndAndroidMain (with seam)
│   ├── ws/WsTransferServer.kt        ← MOVES to jvmAndAndroidMain (already has WsLog)
│   ├── datachannel/DataChannelClient.kt, DataChannelServer.kt
│   └── tcp/LanSession.kt
│
├── jvmMain/                    (DOES NOT EXIST YET — this phase creates it)
│   └── JvmWsFlashNetwork.kt, JvmNetworkWatcher.kt
```

The gap: Android has `WsFlashNetwork` (614 lines, deeply tied to `Context` and `AndroidNetworkWatcher`).
Desktop needs a `FlashNetwork` implementation that uses the same WS protocol but without `android.*`
imports. The shared WS plumbing (`WsConnection`, `WsSession`, `WsTransferServer`, `WsTransferClient`)
can be lifted to `jvmAndAndroidMain` with a deliberate log seam; a new `JvmWsFlashNetwork.kt`
reimplements the orchestration layer in `jvmMain`.

---

## Preconditions — do not start until all are true

1. **Phase 10 (core:network KMP) complete and logged.** The `commonMain`/`jvmAndAndroidMain`/`androidMain`
   split exists and `:core:network:compileKotlinJvm` passes. `FlashNetwork`, `FlashSession`,
   `TlsOptions`, `WebSocketCodec`, `SecureSocketUpgrader`, `FlashTlsContextFactory` are accessible
   from `jvmAndAndroidMain` or `jvmMain`.
2. **Phase 13 (desktop file I/O) complete.** Desktop has `FileSystemOpener` / `FileSink` / `FileSource`
   implementations for the transfer engine.
3. **Phase 14 (desktop discovery) complete.** Desktop has `JmmsFlashDiscovery` (jvmMain) that
   discovers Android peers advertising `_flash-transfer._tcp.`.
4. **D1 = A** in `DECISIONS.md` (jvmAndAndroidMain intermediate source set exists).
5. **Clean working tree** on the migration branch.

---

## Verified starting state (read this, do not assume) — confirmed 2026-08-31

### Current state of the WS stack files

| File | Location | android.* imports | Lines | Notes |
|---|---|---|---|---|
| `WsFlashNetwork.kt` | `androidMain/ws/` | `Context`, `AndroidNetworkWatcher` | 614 | STAYS — too deeply interleaved |
| `WsTransferServer.kt` | `androidMain/ws/` | `android.util.Log` only (WsLog shim) | 134 | Already has `WsLog` shim (lines 24–35) |
| `WsConnection.kt` | `androidMain/ws/` | `android.util.Log` only (raw) | ~160 | Raw `Log.w` (line 130), `Log.d` (line 155) |
| `WsSession.kt` | `androidMain/ws/` | `android.util.Log` only (raw) | ~120 | Raw `Log.w` (lines 110, 117) |
| `WsTransferClient.kt` | `androidMain/ws/` | `Context`, `ConnectivityManager`, `Network`, `NetworkCapabilities` | 107 | `findLanNetwork()` is Android-only |

### Key protocol constants (confirmed in code)

All in `WsFlashNetwork.kt` companion object:
- `PROTOCOL_VERSION = 2` (line 609)
- `HELLO_PREFIX = "FLASH_WS_HELLO"` (private, line 610)
- `HANDSHAKE_TIMEOUT_MS = 6000` (line 611)

In `WsTransferServer.kt`:
- `PREFERRED_PORT = 45822` (line 134)

These are already in `androidMain` — the desktop implementation must duplicate (or share) them.

### Dead code confirmation

- `LanController(` — defined only at `LanController.kt:44`; **zero instantiations** in production code.
- `DefaultFlashNetwork(` — defined as `internal class` at `DefaultFlashNetwork.kt:45`; instantiated **only** in `DefaultFlashNetworkTest.kt` (test file).
- `WsFlashNetwork(` — the **only** production `FlashNetwork` instantiation, at `Flash.kt:170` (Android composition root).

**Conclusion:** The LAN-probe stack (`LanProbeServer`/`LanSession`/`LanController`/`DefaultFlashNetwork`)
is entirely dead code. No desktop transport should reference it. The correction note below handles
the only compile-time issue (`LanProbeServer` references `LanSession`).

### Phase 10 correction — `LanProbeServer.kt` placement

Phase 10 placed `LanProbeServer.kt` in `jvmAndAndroidMain` (row 5 of the placement table). However,
its callback type is:

```kotlin
onPeerProbed: (LanProbeHello, String, LanSession) -> Unit
```

`LanSession` is in `androidMain` (imports `android.util.Log`). This means `compileKotlinJvm` fails
because `jvmAndAndroidMain` cannot see `androidMain` types.

**Fix:** Move `LanProbeServer.kt` to `androidMain`. The server is dead code anyway — it has no
production callers. This does not affect any desktop functionality. Document the move as a
Phase 10 correction.

---

## The seam strategy (Phase 10's reserved decision, now executed)

Phase 10 (lines 200–225) explicitly reserved four seam decisions for Phase 15. They are now
resolved:

### Seam 1: Log seam — `WsLog` promoted to a shared internal object

**Decision:** Move `WsLog` (currently defined inside `WsTransferServer.kt`) to a standalone
`internal object WsLog` in `jvmAndAndroidMain`. All four WS stack files use `WsLog` instead of
direct `android.util.Log`.

**Files affected:**
- `WsTransferServer.kt` — already uses `WsLog`; extract the shim to its own file.
- `WsConnection.kt` — replace `import android.util.Log` and `Log.w(...)` / `Log.d(...)` with `WsLog.w(...)` / `WsLog.d(...)`.
- `WsSession.kt` — replace `import android.util.Log` and `Log.w(...)` with `WsLog.w(...)`.
- `WsTransferClient.kt` — already uses `WsLog`; no change needed.

**Why this is safe:** The `WsLog` shim already exists and works in production (`WsTransferServer.kt`
lines 24–35). It silently no-ops when `android.jar` stubs are unmocked (plain JVM unit tests).
Extracting it to a shared file and using it in the other three files is a mechanical replacement
that changes no runtime behavior.

### Seam 2: `WsTransferClient` — split `findLanNetwork()` into platform-specific socket resolution

**Decision:** Move the WS connect logic (HTTP upgrade, TLS wrap, `WsConnection` creation) to
`jvmAndAndroidMain`. The `findLanNetwork()` method (which uses `ConnectivityManager`) is extracted
into an `expect fun` in `jvmAndAndroidMain` with `actual` implementations:

- `actual` in `androidMain` — uses `ConnectivityManager` (current behavior, unchanged).
- `actual` in `jvmMain` — returns `null` (plain `Socket()`, which is the existing fallback when
  `context` is null). Desktop connectivity is simpler: no mobile-network binding required.

**Why this is safe:** The `context: Context?` parameter is already nullable (line 33 of the current
`WsTransferClient.kt`). When `context` is null (the JVM case), `connectivityManager` is null and
`findLanNetwork()` returns null, which causes `Socket()` to be created directly. The `expect`/`actual`
just formalizes this existing behavior path.

### Seam 3: `WsFlashNetwork` — shared orchestration in `jvmAndAndroidMain`, platform watchers in `actual`

**Decision:** Extract the platform-neutral orchestration logic from `WsFlashNetwork.kt` into a
`WsFlashNetworkBase` class in `jvmAndAndroidMain`. The `WsConnection.Listener` implementation,
session management, HELLO protocol, reconnect engine, and state flows are all JDK-only
(`java.util.concurrent.*`, `kotlinx.coroutines.*`). The `AndroidNetworkWatcher` wiring stays in
`androidMain`.

**Structure:**
- `WsFlashNetworkBase` (jvmAndAndroidMain) — contains shared fields, `WsConnection.Listener` impl,
  session registration/deregistration, HELLO handshake, reconnect scheduling, `EndpointMemory`.
- `WsFlashNetwork` (androidMain) — extends `WsFlashNetworkBase`, adds `Context` constructor parameter
  + `AndroidNetworkWatcher`.
- `JvmWsFlashNetwork` (jvmMain) — extends `WsFlashNetworkBase`, adds `JvmNetworkWatcher` (no-op or
  `java.net.NetworkInterface`-based connectivity listener).

**Why this is safe:** The `context` field in the current `WsFlashNetwork` is used for exactly two
things: (1) creating `WsTransferClient` (which now takes `null` on desktop or a platform socket
factory), and (2) creating `AndroidNetworkWatcher` (which is guarded by `context ?: return`).
Extracting the shared 90% of the class while keeping the 10% Android-specific wiring in `androidMain`
is a clean separation that does not change Android behavior.

### Seam 4: `WsConnection.Listener` — no change needed

`WsConnection.Listener` is defined inside `WsConnection.kt` (which moves to `jvmAndAndroidMain`).
Both `WsFlashNetwork` (androidMain) and `JvmWsFlashNetwork` (jvmMain) implement it. No
`expect`/`actual` needed.

---

## Placement table

### Corrections to Phase 10

| File | Old placement | New placement | Reason |
|---|---|---|---|
| `LanProbeServer.kt` | `jvmAndAndroidMain` | `androidMain` | References `LanSession` (androidMain) in callback type; `compileKotlinJvm` fails otherwise. Dead code, no production impact. |

### New files created by this phase

| New file | Source set | Reason |
|---|---|---|
| `WsLog.kt` | `jvmAndAndroidMain` | Shared `internal object WsLog` — extracted from `WsTransferServer.kt`, used by all WS stack files. Thin wrapper around `android.util.Log` that no-ops when android.jar is unmocked. |
| `WsFlashNetworkBase.kt` | `jvmAndAndroidMain` | Platform-neutral 90% of `WsFlashNetwork` orchestration — session management, HELLO handshake, reconnect engine, `EndpointMemory`, `WsConnection.Listener` implementation. |
| `JvmWsFlashNetwork.kt` | `jvmMain` | Desktop `FlashNetwork` implementation extending `WsFlashNetworkBase`. No `Context`, no `AndroidNetworkWatcher`. Uses `JvmNetworkWatcher` for connectivity monitoring. |
| `JvmNetworkWatcher.kt` | `jvmMain` | Desktop connectivity watcher — uses `java.net.NetworkInterface` to detect network changes, or no-op if interface monitoring is not supported. |
| `WsTransferClientSocketResolver.kt` | `jvmMain` | `actual fun findPreferredNetworkSocket(): Socket?` returning `null` (plain socket, default routing). |

### Files moved to new source sets

| File | From | To | Changes |
|---|---|---|---|
| `WsTransferServer.kt` | `androidMain/ws/` | `jvmAndAndroidMain/ws/` | Remove `import android.util.Log` (WsLog shim extracted to its own file). Already uses `WsLog`. |
| `WsConnection.kt` | `androidMain/ws/` | `jvmAndAndroidMain/ws/` | Replace `import android.util.Log` + raw `Log.w`/`Log.d` with `WsLog.w`/`WsLog.d`. |
| `WsSession.kt` | `androidMain/ws/` | `jvmAndAndroidMain/ws/` | Replace `import android.util.Log` + raw `Log.w` with `WsLog.w`. |
| `WsTransferClient.kt` | `androidMain/ws/` | `jvmAndAndroidMain/ws/` | Replace `android.*` imports + `findLanNetwork()` with `expect fun findPreferredNetworkSocket()` call. |
| `LanProbeServer.kt` | `jvmAndAndroidMain/tcp/` | `androidMain/tcp/` | Move only (correction). No content change. |

### Files staying in `androidMain`

| File | Why it stays |
|---|---|
| `WsFlashNetwork.kt` | Imports `Context` (line 5), `AndroidNetworkWatcher` (line 20). Constructor parameter is `context: Context?`. After this phase, it extends `WsFlashNetworkBase` and adds only the constructor + watcher setup. |
| `AndroidNetworkWatcher.kt` | `ConnectivityManager` — Android-only platform service. |
| `LocalNetworkAddresses.kt` | `ConnectivityManager` — Android-only. |
| `LanConnectionProbe.kt` | `ConnectivityManager` — Android-only. |
| `DefaultFlashNetwork.kt` | `Context` — Android-only. Dead code, but kept for the test file. |
| `DataChannelClient.kt` | `android.util.Log` — could be moved to jvmAndAndroidMain with a WsLog seam, but data channel is out of scope for this phase. |
| `DataChannelServer.kt` | Same as above. |
| `LanSession.kt` | `android.util.Log` — out of scope. |

---

## Dependency table

### New dependencies for `jvmMain`

| Dependency | Scope | Reason |
|---|---|---|
| `:core:network` (same module, `jvmMain` depends on `jvmAndAndroidMain`) | `implementation` (implicit) | `WsFlashNetworkBase`, `WsConnection`, `WsTransferServer`, `WebSocketCodec`, `SecureSocketUpgrader`, `TlsOptions` all in `jvmAndAndroidMain`. |
| `:core:common` | `implementation` (transitive) | `FlashDevice`, `FlashDeviceId`, `FlashResult`, `FlashTextFraming`. |
| `kotlinx.coroutines.core` | `implementation` (transitive) | `Flow`, `StateFlow`, `CoroutineScope`. |

No new external dependencies. The desktop WS transport uses only JDK standard library
(`java.net.*`, `java.util.concurrent.*`, `javax.net.ssl.*`) which is already available to `jvmMain`.

### New dependencies for `jvmAndAndroidMain`

| Dependency | Scope | Reason |
|---|---|---|
| None | — | The four WS files moving to `jvmAndAndroidMain` already use only JDK + coroutines + `:core:common`, all of which are already declared in `commonMain` or `jvmAndAndroidMain`. |

---

## Steps

### Step 1 — Record the baseline

Before touching anything, confirm the current state:

```bash
./gradlew :core:network:testDebugUnitTest --no-configuration-cache
```

Record the exact `@Test` count. The verified baseline from Phase 10 is **102 tests across 19
classes**. If it differs, stop — the inventory drifted.

Also verify compileKotlinJvm fails (it should, because `LanProbeServer` is in `jvmAndAndroidMain`
but references `LanSession` in `androidMain`):

```bash
./gradlew :core:network:compileKotlinJvm --no-configuration-cache
```

Expected: **FAIL** — `LanProbeServer` cannot resolve `LanSession`. This is the exact error this
phase corrects.

- [ ] Baseline test count recorded (expected: 102 `@Test`).
- [ ] `compileKotlinJvm` failure confirmed (LanProbeServer → LanSession).

---

### Step 2 — Create the jvmMain source set directory structure

```bash
cd core/network/src
mkdir -p jvmMain/kotlin/com/transfer/flash/core/network/ws
```

- [ ] `jvmMain/kotlin/com/transfer/flash/core/network/ws/` exists.

---

### Step 3 — Extract `WsLog` to its own file in `jvmAndAndroidMain`

Create `src/jvmAndAndroidMain/kotlin/com/transfer/flash/core/network/ws/WsLog.kt`:

```kotlin
@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import android.util.Log

/**
 * JVM-test shim around [Log]: identical behaviour in production, silently no-ops when the
 * android.jar stubs are unmocked (plain unit tests). Never logs secrets (AGENTS.md §24).
 *
 * Extracted from [WsTransferServer]'s local shim (Phase 10 seam decision) — now shared
 * by all WS stack files in jvmAndAndroidMain.
 */
internal object WsLog {
    fun i(tag: String, message: String) = safe { Log.i(tag, message) }
    fun w(tag: String, message: String, error: Throwable? = null) = safe { Log.w(tag, message, error) }
    fun d(tag: String, message: String) = safe { Log.d(tag, message) }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
```

- [ ] `WsLog.kt` created in `jvmAndAndroidMain/ws/`.

---

### Step 4 — Update `WsTransferServer.kt` to use the shared `WsLog`

In `src/jvmAndAndroidMain/kotlin/.../ws/WsTransferServer.kt` (after `git mv`):

1. Remove the local `WsLog` object (lines 24–35 of the current file).
2. Remove `import android.util.Log` (line 5).
3. Keep all `WsLog.i(...)` / `WsLog.w(...)` / `WsLog.d(...)` calls — they now resolve to the
   shared `WsLog` in the same package.

- [ ] Local `WsLog` object removed from `WsTransferServer.kt`.
- [ ] `import android.util.Log` removed.
- [ ] `WsLog.*` calls resolve to the shared `internal object`.

---

### Step 5 — Update `WsConnection.kt` to use `WsLog`

In `src/jvmAndAndroidMain/kotlin/.../ws/WsConnection.kt` (after `git mv`):

1. Replace `import android.util.Log` with `import com.transfer.flash.core.network.ws.WsLog`.
2. Replace `Log.w(TAG, ...)` with `WsLog.w(TAG, ...)`.
3. Replace `Log.d(TAG, ...)` with `WsLog.d(TAG, ...)`.

- [ ] `import android.util.Log` removed.
- [ ] All `Log.*` calls replaced with `WsLog.*`.

---

### Step 6 — Update `WsSession.kt` to use `WsLog`

In `src/jvmAndAndroidMain/kotlin/.../ws/WsSession.kt` (after `git mv`):

1. Replace `import android.util.Log` with `import com.transfer.flash.core.network.ws.WsLog`.
2. Replace `Log.w(logTag, ...)` with `WsLog.w(logTag, ...)`.

- [ ] `import android.util.Log` removed.
- [ ] All `Log.*` calls replaced with `WsLog.*`.

---

### Step 7 — Update `WsTransferClient.kt`: replace `Context`/`ConnectivityManager` with a socket factory

The `context` parameter in `WsTransferClient` exists for exactly one purpose: `findLanNetwork()`
returns a `ConnectivityManager`-bound `Socket` so the connection lands on the same Wi-Fi/Ethernet
interface as the peer. On desktop there is no `ConnectivityManager` — plain `Socket()` with default
routing is the correct, existing fallback (the code already takes this path when `context` is null).

**Replace `Context` with an injected socket factory.** This is a smaller, more honest seam than
`expect`/`actual` here: the class is otherwise pure JVM, and the platform difference is a single
`Socket` creation.

**In `src/jvmAndAndroidMain/kotlin/.../ws/WsTransferClient.kt`:**

1. Remove the `android.*` imports (lines 5–8 of current file: `Context`, `ConnectivityManager`,
   `Network`, `NetworkCapabilities`).
2. Change the constructor to take a `socketFactory` lambda instead of `context`:

```kotlin
@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.transfer.flash.core.network.tls.SecureSocketUpgrader
import com.transfer.flash.core.network.tls.TlsOptions

/**
 * Opens an outbound WebSocket connection to a peer's [WsTransferServer].
 *
 * Pass a non-null [tls] to upgrade the CONNECT socket to TOFU-pinned TLS via
 * `SecureSocketUpgrader.wrapClient` BEFORE the WebSocket handshake is sent, so the
 * HTTP upgrade itself travels encrypted ([TlsOptions.expectedDeviceId] is the peer's
 * stable id; null fails every handshake closed). The plain streams are never touched
 * before the wrap (clean-boundary rule, see `SecureSocketUpgrader` KDoc).
 *
 * @param socketFactory returns the [Socket] to use for the outbound connect. Android
 * passes a factory that binds to the active Wi-Fi/Ethernet network (see
 * [com.transfer.flash.core.network.util.LocalNetworkAddresses] / ConnectivityManager);
 * desktop passes `{ null }` — meaning plain default routing (equivalent to the old
 * null-context path, which is what pure-JVM loopback tests already exercise).
 */
public class WsTransferClient(
    private val connectionListener: WsConnection.Listener,
    private val tls: TlsOptions? = null,
    private val socketFactory: () -> Socket? = { null },
) {
    public suspend fun connect(host: String, port: Int): WsConnection = withContext(Dispatchers.IO) {
        var socket: Socket = socketFactory() ?: Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            tls?.let { options ->
                val tracked = SecureSocketUpgrader.withPlainStreamTracking(socket)
                socket = tracked
                socket = SecureSocketUpgrader.wrapClient(
                    tracked,
                    options.expectedDeviceId,
                    options.pinVerifier,
                    options.keyManagers,
                    options.handshakeTimeoutMs,
                ).getOrElse { error -> throw error }
                WsLog.i(TAG, "TLS established cipher=${(socket as javax.net.ssl.SSLSocket).session.cipherSuite}")
            }
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val key = WebSocketCodec.newClientKey()
            val request = buildString {
                append("GET /flash-ws HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val (statusLine, headers) =
                WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(socket.getInputStream()))
            if (!statusLine.contains(" 101")) {
                throw IOException("WebSocket upgrade refused: $statusLine")
            }
            val accept = headers["sec-websocket-accept"]
                ?: throw IOException("Missing Sec-WebSocket-Accept header")
            if (accept != WebSocketCodec.acceptKey(key)) {
                throw IOException("Bad Sec-WebSocket-Accept header")
            }
            socket.soTimeout = 0
            WsConnection(socket, maskOutboundFrames = true, remoteLabel = "$host:$port", listener = connectionListener)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    public companion object {
        public const val TAG: String = "WS"
        public const val CONNECT_TIMEOUT_MS: Int = 4_000
        public const val HANDSHAKE_TIMEOUT_MS: Int = 8_000
    }
}
```

3. **Android socket factory** — a small helper in `androidMain` that preserves the current
   `findLanNetwork()` behavior (bind to Wi-Fi/Ethernet). Put it in
   `src/androidMain/kotlin/.../network/util/LocalNetworkAddresses.kt` (already androidMain) or a
   new `ws/WsAndroidSocketFactory.kt`:

```kotlin
// src/androidMain/.../network/ws/WsAndroidSocketFactory.kt
package com.transfer.flash.core.network.ws

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Socket

/**
 * Android socket factory for [WsTransferClient] — binds the outbound socket to the
 * active Wi-Fi/Ethernet network so it lands on the same interface as the peer.
 * This is the exact behavior previously inlined in `WsTransferClient.findLanNetwork()`.
 */
internal fun androidWsSocketFactory(context: Context): () -> Socket? {
    val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    return {
        val network = cm?.allNetworks?.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        network?.socketFactory?.createSocket()
    }
}
```

4. **Call-site update (androidMain):** `WsFlashNetwork` (and `WsFlashNetworkBase`) constructs
   `WsTransferClient(connectionListener = this, tlsOptions = tlsOptions, socketFactory = androidWsSocketFactory(context))`
   when `context != null`, else `WsTransferClient(connectionListener = this, tlsOptions = tlsOptions)`.
   On desktop, `JvmWsFlashNetwork` uses the default (`{ null }`).

- [ ] `WsTransferClient.kt` moved to `jvmAndAndroidMain` — zero `android.*` imports.
- [ ] `androidWsSocketFactory.kt` created in `androidMain` (preserves ConnectivityManager binding).
- [ ] `WsFlashNetworkBase` builds the client via the injected factory.
- [ ] Pure-JVM loopback path unchanged (default `{ null }` factory = old null-context path).

---

### Step 8 — Create `WsFlashNetworkBase.kt` in `jvmAndAndroidMain`

Extract the platform-neutral core from `WsFlashNetwork.kt` into a new base class:

```kotlin
@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.bridge.EndpointMemory
import com.transfer.flash.core.network.resilience.ConnectionHealthAggregator
import com.transfer.flash.core.network.resilience.DuplicateSessionDecision
import com.transfer.flash.core.network.resilience.ReconnectPolicy
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy
import com.transfer.flash.core.network.tls.TlsOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Platform-neutral base for WebSocket mesh [FlashNetwork] implementations.
 *
 * Contains the session management, HELLO handshake protocol, reconnect engine,
 * [EndpointMemory], and [WsConnection.Listener] implementation — all driven by
 * JDK-only types ([java.util.concurrent.*], [kotlinx.coroutines.*]).
 *
 * Platform-specific subclasses (in `androidMain` / `jvmMain`) add:
 * - [WsTransferClient] construction with platform socket resolver
 * - Network connectivity watcher (Android: [AndroidNetworkWatcher]; desktop: [JvmNetworkWatcher])
 */
public abstract class WsFlashNetworkBase(
    protected val localDeviceId: String,
    protected val localFriendlyName: String,
    protected val tlsOptions: TlsOptions? = null,
    protected val hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    protected val healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : FlashNetwork, EndpointMemory, WsConnection.Listener {
    // ... ALL the fields and methods from WsFlashNetwork that do NOT reference
    // android.content.Context or AndroidNetworkWatcher ...
}
```

The actual extraction is a mechanical move: copy the `WsFlashNetwork.kt` content, remove:
- `import android.content.Context`
- `import AndroidNetworkWatcher`
- The `context` constructor parameter
- The `networkWatcher` field
- The `startNetworkWatcher()` / `stopNetworkWatcher()` methods (make them abstract)
- The `client = WsTransferClient(context, ...)` instantiation (make it a protected abstract factory)

Then in `androidMain`, `WsFlashNetwork` becomes:

```kotlin
public class WsFlashNetwork(
    context: Context?,
    localDeviceId: String,
    localFriendlyName: String,
    tlsOptions: TlsOptions? = null,
    hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : WsFlashNetworkBase(
    localDeviceId = localDeviceId,
    localFriendlyName = localFriendlyName,
    tlsOptions = tlsOptions,
    hardeningPolicy = hardeningPolicy,
    healthAggregator = healthAggregator,
    scope = scope,
) {
    private val networkWatcher = context?.let { AndroidNetworkWatcher(it, onAvailable = { ... }) }
    // ... startNetworkWatcher/stopNetworkWatcher override ...
}
```

- [ ] `WsFlashNetworkBase.kt` created in `jvmAndAndroidMain/ws/`.
- [ ] `WsFlashNetwork.kt` in `androidMain` refactored to extend `WsFlashNetworkBase`.

---

### Step 9 — Create `JvmWsFlashNetwork.kt` in `jvmMain`

```kotlin
@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.bridge.EndpointMemory
import com.transfer.flash.core.network.resilience.ConnectionHealthAggregator
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy
import com.transfer.flash.core.network.tls.TlsOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop [FlashNetwork] implementation using the WebSocket wire protocol.
 *
 * Interoperates with Android's [WsFlashNetwork] on the same LAN — same protocol
 * version (2), same HELLO prefix, same port (45822), same TOFU-pinned TLS.
 *
 * Unlike Android's implementation, this does not use [android.content.Context] or
 * [AndroidNetworkWatcher]. Network interface enumeration uses [java.net.NetworkInterface].
 * Socket creation uses default routing (no `ConnectivityManager` binding).
 */
public class JvmWsFlashNetwork(
    localDeviceId: String,
    localFriendlyName: String,
    tlsOptions: TlsOptions? = null,
    hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : WsFlashNetworkBase(
    localDeviceId = localDeviceId,
    localFriendlyName = localFriendlyName,
    tlsOptions = tlsOptions,
    hardeningPolicy = hardeningPolicy,
    healthAggregator = healthAggregator,
    scope = scope,
) {
    // Desktop-specific: no AndroidNetworkWatcher, no Context.
    // Network availability is monitored via java.net.NetworkInterface polling
    // or a simple no-op (the reconnect engine's backoff is sufficient).
}
```

- [ ] `JvmWsFlashNetwork.kt` created in `jvmMain/ws/`.

---

### Step 10 — Create `JvmNetworkWatcher.kt` in `jvmMain`

```kotlin
@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import java.net.NetworkInterface

/**
 * Desktop connectivity watcher — replaces Android's [AndroidNetworkWatcher].
 *
 * Uses [NetworkInterface.getNetworkInterfaces()] to detect when a LAN-capable
 * interface comes up. When interface monitoring is not available or not needed,
 * this is a no-op (the reconnect engine's backoff timer handles reconnection).
 */
internal class JvmNetworkWatcher(
    private val onAvailable: () -> Unit,
    private val onLost: () -> Unit = {},
) {
    // ... implementation using java.net.NetworkInterface ...
}
```

- [ ] `JvmNetworkWatcher.kt` created in `jvmMain/ws/`.

---

### Step 11 — Move `LanProbeServer.kt` to `androidMain` (Phase 10 correction)

```bash
cd core/network
git mv src/jvmAndAndroidMain/kotlin/.../tcp/LanProbeServer.kt \
       src/androidMain/kotlin/.../tcp/LanProbeServer.kt
```

- [ ] `LanProbeServer.kt` moved to `androidMain/tcp/`.

---

### Step 12 — Update `core/network/build.gradle.kts`

Add the `jvmMain` source set to the post-Phase 10 build file:

```kotlin
sourceSets {
    // ... existing commonMain, jvmAndAndroidMain, androidMain blocks ...

    getByName("jvmMain") {
        dependsOn(getByName("jvmAndAndroidMain"))
        dependencies {
            // No new external dependencies — JDK + coroutines transitive from jvmAndAndroidMain
        }
    }
}
```

No changes needed to `commonMain`, `jvmAndAndroidMain`, or `androidMain` dependency blocks — the
four WS files moving to `jvmAndAndroidMain` use only types already available in that tier.

- [ ] `jvmMain` source set registered in `build.gradle.kts`.

---

### Step 13 — Verify compilation

```bash
# Desktop compile — must succeed
./gradlew :core:network:compileKotlinJvm --no-configuration-cache

# Android compile — must not regress
./gradlew :core:network:compileKotlinAndroid --no-configuration-cache

# Tests still pass
./gradlew :core:network:androidHostTest --no-configuration-cache

# App still builds
./gradlew :app:assembleDebug --no-configuration-cache
```

- [ ] `:core:network:compileKotlinJvm` green.
- [ ] `:core:network:compileKotlinAndroid` green.
- [ ] `:core:network:androidHostTest` green (same count: 102 `@Test`).
- [ ] `:app:assembleDebug` green.

---

### Step 14 — Verify no `android.*` imports leak into `jvmAndAndroidMain` or `jvmMain`

```bash
# jvmAndAndroidMain should have zero android.* imports after the seam
grep -rn "^import android\." core/network/src/jvmAndAndroidMain/ || echo "Clean: no android.* in jvmAndAndroidMain"

# jvmMain should have zero android.* imports (it's pure JVM)
grep -rn "^import android\." core/network/src/jvmMain/ || echo "Clean: no android.* in jvmMain"
```

- [ ] No `android.*` imports in `jvmAndAndroidMain`.
- [ ] No `android.*` imports in `jvmMain`.

---

## Verification gate

- [ ] `:core:network:compileKotlinJvm` passes — desktop compile.
- [ ] `:core:network:compileKotlinAndroid` passes — Android not regressed.
- [ ] `:core:network:androidHostTest` passes — same test count (102).
- [ ] `:app:assembleDebug` passes — app still builds.
- [ ] No `android.*` imports in `jvmAndAndroidMain` or `jvmMain`.
- [ ] `LanProbeServer.kt` moved to `androidMain` (Phase 10 correction).
- [ ] `WsLog` extracted to `jvmAndAndroidMain` and used by all four WS stack files.
- [ ] `WsTransferClient` no longer imports `android.*` (uses `expect`/`actual` socket resolver).
- [ ] `WsFlashNetworkBase` created in `jvmAndAndroidMain` with no `android.*` imports.
- [ ] `JvmWsFlashNetwork` created in `jvmMain` implementing `FlashNetwork`.

---

## Do NOT

- **Do NOT change the WebSocket wire protocol** (`PROTOCOL_VERSION`, `HELLO_PREFIX`, port, handshake
  format, frame encoding). The whole point of this phase is wire-compatible interop with Android.
- **Do NOT modify `WebSocketCodec.kt`**, `SecureSocketUpgrader.kt`, `FlashTlsContextFactory.kt`,
  `TofuX509TrustManager.kt`, or `FlashPinVerifier.kt` — these are R8-sensitive and already in
  their correct source sets (jvmAndAndroidMain or commonMain).
- **Do NOT touch `DataChannelClient.kt` or `DataChannelServer.kt`** — they are out of scope.
- **Do NOT delete `LanProbeServer.kt`** — just move it to `androidMain`. Dead code removal is a
  separate cleanup phase.
- **Do NOT introduce new external dependencies.** The desktop WS transport needs only JDK standard
  library + coroutines (already transitive).
- **Do NOT wire the engine composition root** — that is Phase 21. This phase produces only the
  `FlashNetwork` transport implementation.
- **Do NOT change the `WsFlashNetwork` public API** — Android callers (`Flash.kt:170`) must
  continue to compile without changes.

---

## Rollback

If the seam extraction or `LanProbeServer` move breaks the Android build:

```bash
# Revert the LanProbeServer move
git checkout -- core/network/src/jvmAndAndroidMain/.../tcp/LanProbeServer.kt
git checkout -- core/network/src/androidMain/.../tcp/LanProbeServer.kt  # if it was moved

# Revert all WS file moves and modifications
git checkout -- core/network/src/androidMain/ws/
git checkout -- core/network/src/jvmAndAndroidMain/ws/  # if it exists (shouldn't before this phase)

# Delete the new source sets
rm -rf core/network/src/jvmMain/
```

If the build file edit is wrong, restore from git:

```bash
git checkout -- core/network/build.gradle.kts
```

---

## Log entry (mandatory)

Append one entry to `docs/migration/logs/migration.md` using the template format. Include:

- The exact seam strategy applied (log seam, `WsTransferClient` `expect`/`actual`, `WsFlashNetworkBase` extraction).
- The `LanProbeServer` correction (Phase 10 error, moved to `androidMain`).
- The `compileKotlinJvm` / `compileKotlinAndroid` / `androidHostTest` / `assembleDebug` results.
- The grep verification that `jvmAndAndroidMain` and `jvmMain` have no `android.*` imports.
- Any deviations from the phase file.