# PHASE-32 — Desktop settings (09B-3, and the desktop's most visible lie)

**Status:** AUTHORED (2026-09-13, planning only — **no code written**).
**BLOCKED ON A HUMAN DECISION** (the settings-tier ABI option, 09B-3).
**Risk:** MEDIUM — touches the persistence tier and a DataStore-backed store that shipped installs
already use. The Android path must not change.
**Decisions relied on:** D1 = B, R2, R5, R6, R7.

---

## What this phase is for

`FlashSettingsScreen` renders on desktop (`DesktopShell.kt:327`) — the same composable the phone
calls (`MainActivity.kt:1376`). But the desktop passes **six** callbacks and only one of them does
anything:

| Callback (`DesktopShell.kt`) | Desktop body | |
|---|---|---|
| `onThemeModeSelected` (329) | `{ /* no persisted settings tier on desktop (09B-3) */ }` | no-op |
| `onDynamicAccentChanged` (330) | `{ }` | no-op |
| `onHapticsChanged` (331) | `{ }` | no-op |
| `onBackgroundTransfersChanged` (332) | `{ }` | no-op |
| `onRefreshStorageUsage` (333) | `{ }` | no-op |
| `onClearReceivedFiles` (334–338) | `scope.launch { DesktopHelpers.clearReceivedFiles(engine) }` | **real** |

Everything else in the signature (`onAutoDownload*`, `onPrioritiseVoiceQualityChanged`,
`onPerformanceModeSelected`, `onEditDisplayName`, `onOpenEncryption`, `onOpenTrustedPeers`,
`onPickSaveLocation`, `onOpenBatterySettings` — `FlashSettingsScreen.kt:219–240`) is left to its
`= {}` default, i.e. also inert.

**So on desktop the Settings screen is a control panel connected to nothing.** Every switch flips,
nothing changes, and nothing persists. That is worse than an absent screen: it is a screen that
reports success.

Android, by contrast, persists every one of them through `onSettingsChange`
(`MainActivity.kt:476–505`) into **`FlashSettingsDataStore`**
(`core/persistence/src/androidMain/.../settings/FlashSettingsDataStore.kt`), whose setters
(`setThemeMode`, `setDynamicAccent`, `setHapticsEnabled`, `setBackgroundTransfers`,
`setAutoDownload*`, `setPrioritiseVoiceQuality`, `setPerformanceMode`, `setDisplayName`) are the exact
list the desktop no-ops. Display-name also writes `AndroidPreferencesIdentityStore` and calls
`DiscoveryEngineHolder.updateFriendlyName`.

## Why it is blocked, and what the decision actually is

`:core:persistence` carries this comment in its own build file:

> `androidMain` until 09B-3: it uses `java.io.File` and `androidx.datastore`.

Both facts are real constraints, and they are different constraints:

- **`androidx.datastore:datastore-preferences`** — the API `FlashSettingsDataStore` is written
  against has a **JVM/desktop artifact** (`datastore-preferences-core` over okio), but the
  convenience tier differs and the Android `Context`-based factory does not exist there. So the
  *storage* is portable; the *construction* is not.
- **`java.io.File`** is legal in `jvmMain` (R6 only forbids `java.*` in `commonMain`), so it is not
  a blocker for a JVM actual — only for putting the class in `commonMain` unchanged.

That means 09B-3 is the same shape of choice as 09B-2 (Phase 29), and the recorded "ABI option (a)
or (b)" is a decision about *how the shared settings type is surfaced*, not about whether desktop
can persist at all. **This phase cannot start until that is picked.**

## What is already shared, and must not be rebuilt

- **`FlashSettingsModel`** and the pure math around it — `:ui:chat`'s `FlashSettingsScreen` takes a
  `FlashSettingsModel`, and `FlashStorageMath` / `FlashSettingsLogicTest` are already commonMain and
  already tested.
- **The screen itself.** No parameter changes are needed; the desktop call site simply has nothing to
  pass.
- **`DesktopEngine`'s `stateDir`** (`DesktopEngine.kt:89`) — settings on desktop belong beside
  `identity.properties` and `trust.properties` under `~/.flash/`, following the Phase 21/26
  precedent, not in a second location.

## Design sketch

1. **A settings-store seam.** A target-free `FlashSettingsStore` interface in a common location,
   with the Android actual delegating to today's `FlashSettingsDataStore` **unchanged** and a JVM
   actual reading/writing `~/.flash/settings.properties` (or the chosen ABI's storage — per the
   decision). The `Properties`-file approach already has precedent on desktop
   (`DesktopIdentityStores.kt:29`, `:92`).
2. **Wire the six desktop callbacks** to it, then the remaining eight to whatever subset desktop
   can honour — and for the ones it cannot (haptics on a desktop with no vibrator; battery settings
   on a machine with no battery), **hide them rather than leave them as no-ops.** A visible control
   that does nothing is the defect, not the missing feature.
3. **`onRefreshStorageUsage`** is implementable immediately: `DesktopHelpers` already owns the
   received-files root (`receivedRoot`, `DesktopEngine.kt:87`), so walking it is one function.
4. **`onPickSaveLocation`** — desktop gets real value from choosing the received-files directory.
5. **Theme mode must actually apply.** `FlashTheme` already exists in `:ui:theme`; desktop currently
   hardcodes it in `DesktopMain.kt`. Persisting a theme the app ignores would be the same lie in a
   new place.

## Do NOT

- **Do NOT change `FlashSettingsDataStore` or `DiscoveryModeSetting`'s Android behaviour.** Shipped
  installs read them. R8-adjacent.
- **Do NOT add `androidx.datastore` to `commonMain`** — it is not a multiplatform API surface, and
  R2 forbids moving code to `commonMain` to make a compile succeed.
- **Do NOT leave a control rendered-but-inert.** Remove it from desktop or implement it; the
  current `{ }` bodies are the thing this phase exists to delete.
- **Do NOT make the desktop write to the phone's preferences schema** or reuse
  `AndroidPreferencesIdentityStore`. Display-name on desktop is `~/.flash/identity.properties`
  (`DesktopIdentityStore`, already written) — a rename must update *that*, exactly as Android's
  updates its own store.
- **Do NOT touch the 12 known `:core:persistence` NTFS test failures** —
  `FlashSettingsDataStoreTest` and `DiscoveryModeSettingTest` fail with
  `IOException: Unable to rename …preferences_pb.tmp` on Windows. That is DataStore's atomic-rename
  against Windows file locking, an environment issue that passes on Linux; "fixing" it in this phase
  would mask a real signal.

## Sub-step plan

| # | Sub-step | Content |
|---|---|---|
| 32-0 | **Decision (human)** | 09B-3's ABI option. Nothing below runs until it is picked. |
| 32-1 | Settings store seam | Interface + Android actual delegating unchanged + JVM actual. |
| 32-2 | Desktop wiring | The six callbacks become real; the other eight implemented or hidden. |
| 32-3 | Theme application | Desktop honours the persisted theme mode. |
| 32-4 | Storage + save location | Real `onRefreshStorageUsage`; `onPickSaveLocation` via `DesktopHelpers`. |
| 32-5 | Display name | Rename updates `~/.flash/identity.properties` **and** the advertised JmDNS frame. |
| 32-6 | Verification | R3 sweep (especially `:core:persistence`); `:app:assembleDebug`; R6 scan; **human run**: flip every desktop setting, restart the app, confirm each survived. |
| 32-7 | Log + README | Honest entry; close 09B-3's row. |

## The acceptance test this phase should be judged by

Change **every** setting on desktop, quit, relaunch, and find every one of them still where you left
it — and find that the ones that *couldn't* be honoured are not on screen at all.
