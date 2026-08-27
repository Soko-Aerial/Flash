# Phase 5 — Consumer Ergonomics & Documentation

**Goal:** make the library pleasant to adopt. A great API that requires
hand‑assembling six objects and guessing permissions will not get used. Add a
factory, a README quick‑start, a permissions manifest note, and a clean lifecycle.

**Prereq:** Phases 2–4 (the API must be stable/compilable first).

---

## STATUS (updated 2026-08-27)

- **Task 5.1 — DONE.** `core:engine` now ships `Flash.create(context, FlashConfig)`
  (`core/engine/src/main/java/com/transfer/flash/core/engine/Flash.kt`). It assembles
  all six `FlashEngine` subsystems on one shared `CoroutineScope`, opens the encrypted
  Room DB via `KeystorePassphraseProvider`, and starts network/discovery/data-channel/
  auto-connect async. `FlashConfig(displayName, enableResume, autoAcceptIncoming,
  receivedFilesDir)` per the "Full engine, autoAccept default false" decision. Pairing,
  `FlashBackgroundService`, and the Dev Console are intentionally excluded (app-UI-coupled;
  not part of the `FlashEngine` facade). Wiring is duplicated from `DiscoveryEngineHolder`
  rather than refactored, to avoid destabilizing the app.
- **Task 5.3 — DONE (folded in).** `FlashEngine : Closeable`; `DefaultFlashEngine` takes an
  idempotent `onClose: () -> Unit = {}` (AtomicBoolean-guarded, defaulted so hand-assembled
  callers compile). The factory wires teardown: stop data-channel server + network + discovery,
  close the DB, cancel the shared scope.
- **Verified:** `:core:engine:compileDebugKotlin`, `:core:engine:compileReleaseKotlin`
  (explicitApi strict), `:core:engine:testDebugUnitTest`, `:app:compileDebugKotlin` all green.
- **Task 5.5 — DONE.** Every `core/*/consumer-rules.pro` now carries a documented comment
  header (persistence was 0 bytes → written). Verified there is **no first-party reflective
  access** across `core/*` (no `Class.forName`/`newInstance`, no `@Serializable`, no
  serialization plugin) — so "no keep rules needed; transitive libs (Room/SQLCipher) ship their
  own" is accurate; engine/persistence headers call out the Room/SQLCipher bundling explicitly.
  **`resourcePrefix` decision: not needed** — no `core/*` module has a `res/` directory, so there
  are no consumer resource-collision risks (recorded here per the acceptance criterion).
- **Task 5.2 — DONE.** Permissions section written into the root `README.md`: required group
  (INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE) vs optional
  foreground-service group (WAKE_LOCK, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE,
  POST_NOTIFICATIONS), each with a one-line "why", plus the explicit "no location permission" note
  (NSD/mDNS, not a Wi-Fi/BLE scan). `RECORD_AUDIO` from the app manifest is app-only (voice), omitted.
- **Task 5.4 — DONE.** `README.md` authored at repo root: pitch, JitPack install (commented badge +
  `<user>/<repo>`/`<TAG>` placeholders to fill in Phase 6), quick-start, `FlashConfig` table, lifecycle,
  permissions, compatibility table, published-module set (Phase 4 Task 4.3), Apache-2.0 line. The
  quick-start is **compiled verbatim** as `sample/consumer/.../QuickStart.kt` so it can't drift.
- **Sample harness (5.4 verification) — DONE.** `:sample:consumer` gains `QuickStart.kt` exercising
  `Flash.create` → observe `discoveredEndpoints` `StateFlow` → `sendFile` → `close()`. Building it
  surfaced a real **dependency-scope leak** (below), now fixed. `:sample:consumer:assembleDebug` +
  `:sample:consumer-granular:assembleDebug` both green.
- **Dependency-scope fix (coroutines).** Core modules exposed `Flow`/`StateFlow` in their PUBLIC API but
  pulled coroutines only via `implementation(lifecycle.runtime.ktx)` → those return types were OFF a
  downstream consumer's compile classpath (`QuickStart.kt` couldn't resolve `StateFlow`/`first`). Added
  `api(libs.kotlinx.coroutines.core)` to discovery, network, transfer, persistence, security, messaging
  (new `kotlinx-coroutines-core` catalog entry, `coroutines = 1.10.2`). Engine re-exports via its
  `api(project(...))`. Verified green on debug + release for all touched modules and `:app`.
- **Compatibility floor recorded (Phase 1 Task 1.3 acceptance):** minSdk 24, compileSdk 35, AGP 9.3+,
  Gradle 9.5+, Kotlin 2.2+, JDK 17 to build, Java 11 bytecode. README flags the AGP 9.3 floor as the real
  adoption ceiling (compileSdk was lowered to 35, but AGP was not lowered — owner may want to revisit).
- **Phase 5 remaining:** none of the authoring tasks — only Phase 6 (JitPack) fills the README's
  `<user>/<repo>`/`<TAG>`/badge placeholders.

---

## Task 5.1 — One‑call factory / builder (Major)

Today `DefaultFlashEngine` forces a consumer to construct six concrete impls
(`DefaultFlashNetwork`, `NsdFlashDiscovery`, the transfer repo, …), each needing a
`Context` and each spinning up its own `CoroutineScope`. There is no
"file URI → transferred" happy path.

Add a single entry point in `core:engine`:
```kotlin
public object Flash {
    /** Builds a fully‑wired engine. [store] is optional; pass null for DB‑less. */
    public fun create(
        context: Context,
        config: FlashConfig = FlashConfig(),
    ): FlashEngine { /* assemble Default* impls, share one scope */ }
}

public data class FlashConfig(
    val enableResume: Boolean = true,      // wires persistence if available
    val displayName: String? = null,
    // …only knobs a consumer realistically sets.
)
```
Rules:
- Internally share **one** parent `CoroutineScope` across the impls and cancel it
  in `FlashEngine.close()` (Task 5.3). Do not let each impl create its own.
- Keep the concrete `Default*` constructors available (advanced users) but make
  `Flash.create` the documented path.

**Acceptance:** the Phase 5 README snippet compiles against `core-engine` and
transfers a file using only `Flash.create(context)` + one `sendFile`‑style call.

## Task 5.2 — Document required permissions (the clean‑manifest tradeoff)

Core modules ship **zero** permissions in their manifests (good — no forced
merge), but that means consumers don't know what the LAN/NSD stack needs. The
consuming app must declare, in **its** `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
```
Plus, if the consumer runs transfers with the screen off (optional): `WAKE_LOCK`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`.
Document each with a one‑line "why" and mark the second group optional.

**Acceptance:** README has a "Permissions" section with the required vs optional
split and the reason for each.

## Task 5.3 — Unified lifecycle / shutdown

The impls are Context‑bound god‑objects that create their own scopes and grab
`NsdManager`/`WifiManager` in constructors, with no coordinated teardown beyond
per‑object `stop()`. Give consumers one clean off‑switch.

- Add `public fun close()` (or make `FlashEngine : Closeable`) that cancels the
  shared scope and calls `stop()`/releases `NsdManager`/`WifiManager` on every
  sub‑component, idempotently.
- Document that `Flash.create` must be paired with `engine.close()` (e.g. in
  `onDestroy`/`ViewModel.onCleared`).
- Prefer not doing NSD/Wifi acquisition in constructors; move side effects into an
  explicit `start()` where feasible. If that's too invasive now, document that
  construction has side effects.

**Acceptance:** calling `close()` stops discovery/network and cancels all
coroutines with no leaked scope; calling it twice is safe.

## Task 5.4 — Author the consumer README (Major)

Create `README.md` at repo root (this is what developers see on GitHub/JitPack).
Include, in this order:
1. One‑paragraph pitch: offline LAN peer‑to‑peer file transfer for Android, no
   server, no internet.
2. **JitPack badge** + Gradle install snippet (fill in after Phase 6):
   ````markdown
   ```kotlin
   // settings.gradle.kts
   dependencyResolutionManagement {
       repositories { maven { url = uri("https://jitpack.io") } }
   }
   // module build.gradle.kts
   dependencies { implementation("com.github.<user>.<repo>:core-engine:<TAG>") }
   ```
   ````
3. Quick start: `Flash.create(context)` → discover peers → `sendFile` → observe
   progress `StateFlow`. ~15 lines, copy‑pasteable, taken from `:sample:consumer`.
4. Permissions section (Task 5.2).
5. **Compatibility table**: minSdk 24; the AGP/Gradle/Kotlin/JDK floor decided in
   Phase 1 Task 1.3; note the SQLCipher/native‑lib footprint (Phase 4) and how to
   avoid it (transfer‑only path or ABI filters).
6. Supported artifact list (Phase 4 Task 4.3).
7. License line (Apache‑2.0) + link to `LICENSE`.

**Acceptance:** a developer can go from README to a working transfer without
reading source.

## Task 5.5 — Minor cleanups

- **`consumer-rules.pro`:** `core/persistence/consumer-rules.pro` is 0 bytes.
  Real risk is low (Room/SQLCipher ship their own consumer rules; no first‑party
  reflection), but add an explanatory comment header to each `consumer-rules.pro`
  stating "no keep rules needed; transitive libs provide their own." If any
  first‑party type is accessed reflectively (verify), add a `-keep` for it.
- **`resourcePrefix`:** core modules have no `res/`, so this is optional. If any
  module later adds resources, set `android { resourcePrefix = "flash_" }` to
  avoid consumer resource collisions.

**Acceptance:** every `consumer-rules.pro` is non‑empty (at least a documented
comment); decision on `resourcePrefix` recorded.

## Verification (hand off)

```bash
./gradlew :sample:consumer:assembleDebug
```
The sample should exercise `Flash.create` + `close()` and mirror the README
quick‑start verbatim.

