# Stream G (Android) — Errors and Fixes Log

---

## ERROR-921 — Compiler Error: Unresolved reference 'state' on pttEngine in DiscoveryEngineHolder

### Date
2026-11-01

### Area
Android Host / PTT Engine integration

### Symptoms
Compilation failure in `:app:compileDebugKotlin` at `DiscoveryEngineHolder.kt:2995`:
`Unresolved reference 'state'` on `pttEngine?.state?.value`.

### Environment
- Gradle 8.13 / AGP 8.9.1 / Kotlin 2.1.10
- Branch: `agent/opt-android`

### Root cause
`PttFloorState` package mismatch. The file imported `com.transfer.flash.core.ptt.state.PttFloorState` instead of `com.transfer.flash.core.ptt.floor.PttFloorState`, causing compiler type mismatch on `FlashPtt.state: StateFlow<PttFloorState>`.

### Working fix
Changed package reference in `DiscoveryEngineHolder.kt` to `com.transfer.flash.core.ptt.floor.PttFloorState.Idle`.

### Verification
`:app:compileDebugKotlin` succeeded with exit code 0.

### Status
RESOLVED

---

## ERROR-922 — Compiler Error: FlashDatabase Room instance type mismatch with Closeable

### Date
2026-11-01

### Area
Android Host / Keepalive Worker SQLite check

### Symptoms
Compilation failure in `:app:compileDebugKotlin` at `FlashKeepaliveWorker.kt:63`:
`Argument type mismatch: actual type is 'FlashDatabase', but 'Closeable?' was expected`.

### Environment
- Room 2.7.0-alpha13 / Kotlin 2.1.10
- Branch: `agent/opt-android`

### Root cause
Room's `RoomDatabase` in KMP / Android does not implement `java.io.Closeable` directly, so Kotlin's `.use { }` extension is not resolved.

### Working fix
Replaced `.use { }` with standard `try { ... } finally { runCatching { db.close() } }`.

### Verification
`:app:compileDebugKotlin` succeeded with exit code 0.

### Status
RESOLVED

---

## ERROR-923 — Compiler Warning: Deprecated TRIM_MEMORY constants in FlashApplication

### Date
2026-11-01

### Area
Android Host / Application lifecycle

### Symptoms
Compiler warning in `:app:compileDebugKotlin`: `'static field TRIM_MEMORY_RUNNING_CRITICAL: Int' is deprecated. Deprecated in Java.`

### Environment
- Android SDK 35 (API 35) / Kotlin 2.1.10
- Branch: `agent/opt-android`

### Root cause
API 35 deprecates some `ComponentCallbacks2` memory constants in favour of broader tiers. The constants are still what
the pre-API-35 path maps from, so the deprecation is not something to remove.

### Working fix
**Nothing was changed for this.** The warning stays: `FlashApplication.kt` is byte-identical to `dev` on this branch, and
the deprecated constants are still what the mapping needs on older releases. (The first version of this entry claimed a
fix — "retained standard mapping … while suppressing obsolete warnings or using standard when branches" — that does not
exist in the diff; corrected here so nobody looks for it.)

### Verification
`:app:compileDebugKotlin` builds cleanly *with the warning present*.

### Status
NO CHANGE — accepted warning, not a defect
