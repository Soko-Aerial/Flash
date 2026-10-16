# Current Handoff — Stream R-link

## Current branch
`agent/R-link`

## Last verified build
`d1a8b2d9` (plus uncommitted changes verified via `:core:radio:jvmTest --rerun-tasks` and `:desktop:compileKotlinJvm :app:compileDebugKotlin`)

## Current phase
Phase R1 / R2 — Hardware Radio KISS TNC Gateway (Find, pair and connect)

## Working features
- Spike Bluetooth code promoted into `:core:radio`:
  - Windows Win32 JNA (`WindowsBluetoothApi`), models, error mapper, device classifier, registry BTHENUM COM-port mapper in `core/radio/src/jvmMain/.../link/windows/`.
  - Android Bluetooth discovery scan, bonding, and bonded-device query in `AndroidRadioDiscovery` (`core/radio/src/androidMain/.../link/android/`).
  - Desktop `RadioSpikeCli` and debug `RadioSpikeActivity` refactored as thin harnesses delegating to `:core:radio`.
- Common discovery port `RadioDeviceDiscovery` in `core/radio/src/commonMain/.../link/` with implementations:
  - `WindowsRadioDiscovery`: dynamic BTHENUM query on every connect to handle COM renumbering; listener ports (`000000000000`) strictly ignored.
  - `AndroidRadioDiscovery`: lifecycle-aware scan cancellation via `awaitClose`, `neverForLocation` scanning, SSP bonding.
  - `FakeRadioDeviceDiscovery` for unit tests.
- High-level gateway facade `RadioGatewayManager` in `core/radio/src/commonMain/.../gateway/`:
  - Reactive `StateFlow<RadioGatewayState>` with human-readable status sentences and next actionable steps.
  - Plan R §6 Rule 3 mechanical enforcement: zero links opened prior to user candidate confirmation.
  - Auto-reconnect to remembered radio when gateway switch is enabled.
  - Scanning strictly on demand; switch OFF cancels all scans and closes all links.
  - Auto-detect preselects only when exactly one candidate matches.
  - Retry limit (5 attempts) -> transitions to "Reconnect" prompt state.
  - Recovers automatically when a power-cycled radio comes back online.
  - Rule 4 Transmission Safety: `requestTestMessage()` checks link type; real RF / serial ports strictly suppressed with `FlashProbe.emit("radio.tx.suppressed_on_real_rf", ...)`.
- Real atomic persistence:
  - Desktop: `DesktopPropertiesRadioStore` (`radio.properties.tmp` -> `Files.move(ATOMIC_MOVE)`).
  - Android: `AndroidSharedPreferencesRadioStore` (`flash_radio_settings` with `commit()`).
  - Strict corruption safety: unreadable/corrupted files yield null ("nothing remembered") without crashing.
  - Addresses and display names stored; zero keys or frame contents.
- Android background execution:
  - Researched and documented official Android 14/15 `connectedDevice` FGS rules in `platform-notes-additions.md`.
  - Implemented pure policy `RadioServicePolicy` in `core/radio/.../service/`.
  - Created thin `RadioLinkService` in `app/src/main/` with `foregroundServiceType="connectedDevice"` and honest notifications.
- Test suite:
  - 17 unit/host tests (`RADLNK-U01..U17`) in `:core:radio` covering all gateway flows, stores, discovery, and policies.
  - 157 total passing tests in `:core:radio:jvmTest`.
- Owner Hardware Runbook & Backlog:
  - `logs/streams/R-link/runbook-R1-R2.md` with tests W1-W6 (Windows) and M1-M4 (Android).
  - 10 owed device tests `RADLNK-D01..D10` recorded in `logs/streams/R-link/backlog-additions.md`.

## In progress
- None (Phase R1 / R2 implementation and verification complete).

## Broken
- None.

## Last change
Implemented `RadioGatewayManager`, `DesktopPropertiesRadioStore`, `AndroidSharedPreferencesRadioStore`, `RadioServicePolicy`, `RadioLinkService`, test suites `RADLNK-U01..U17`, and owner runbook `runbook-R1-R2.md`.

## Last test
- `tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks` — BUILD SUCCESSFUL (157 tests executed, 0 failed).
- `tools\flash-gradle.cmd :desktop:compileKotlinJvm :app:compileDebugKotlin` — BUILD SUCCESSFUL.

## Known blockers
- None. Physical RF verification is owed by owner on hardware per `logs/streams/R-link/runbook-R1-R2.md`.

## Recommended next task
- Stream R-ui can now proceed with Phase R-ui (Radio Settings & Gateway UI in Compose Multiplatform) connecting the UI to `RadioGatewayManager`.
- Owner executes hardware runbook `logs/streams/R-link/runbook-R1-R2.md` when radios are available.

## Files most relevant to next task
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayState.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioCandidateRanker.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/connect/RadioAuthorisationStore.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/RadioDeviceDiscovery.kt`
- `logs/streams/R-link/runbook-R1-R2.md`
