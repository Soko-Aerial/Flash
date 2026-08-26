# Phase 5 — Consumer Ergonomics & Documentation

**Goal:** make the library pleasant to adopt. A great API that requires
hand‑assembling six objects and guessing permissions will not get used. Add a
factory, a README quick‑start, a permissions manifest note, and a clean lifecycle.

**Prereq:** Phases 2–4 (the API must be stable/compilable first).

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

