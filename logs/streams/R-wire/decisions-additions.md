# Stream R-wire Decision Additions

Reserved ADR range: ADR-197 through ADR-199.

## ADR-197: Host Radio Gateway Controller Mapping and Lazy Lifetime Architecture

### Status
ACCEPTED (Stream R-wire, 2026-10-16)

### Context
Plan R §6 and §8 require connecting the `:ui:chat` presentation port (`RadioGatewayController` / `RadioGatewayUiState`) to the `:core:radio` domain logic (`RadioGatewayManager` / `RadioGatewayState`) across both Android and Windows desktop hosts.
The host integration must satisfy strict platform, power, and security constraints:
1. Under Android execution limits and background policies, the radio subsystem must not run, scan, or bind background services when the feature is turned off.
2. The user experience in Settings must show an active, honest toggle that reflects persistent state without initializing Bluetooth adapters or drivers until requested.
3. Tests must be completely hermetic on JVM with zero RF transmission or hardware access.

### Decision
1. Implement two host controllers:
   - `AndroidRadioGatewayController` in `:app` utilizing `AndroidRadioDiscovery`, `AndroidSharedPreferencesRadioStore`, `RadioLinkService`, and Android Activity Result permissions launcher for `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`.
   - `DesktopRadioGatewayController` in `:desktop` utilizing `WindowsRadioDiscovery`, `DesktopPropertiesRadioStore`, and Win32 Bluetooth Settings URI (`ms-settings:bluetooth`).
2. Lifetime Contract:
   - If the gateway master switch is stored OFF at app launch, `RadioGatewayManager` is NEVER instantiated.
   - When the user turns the switch ON, `RadioGatewayManager` is instantiated lazily, stores are read, and discovery/connection commences.
   - When the user turns the switch OFF, `RadioGatewayManager.disable()` is invoked, the foreground service (on Android) is stopped, active coroutine jobs are cancelled, and the manager reference is nullified.
3. Testability Seam:
   - Both controllers expose factory parameters (`discoveryFactory`, `rememberedStoreFactory`, `authorisationStoreFactory`, `driverFactory`, `isSwitchEnabledStored`, `setSwitchEnabledStored`, `openSettingsHandler`) enabling comprehensive JVM unit test verification without mock frameworks or real hardware.

### Consequences
- Prevents battery drain and unauthorized Bluetooth activity at boot.
- Eliminates hard dependencies on real hardware in CI / unit tests.
- Keeps shared UI modules pure and isolated from platform internals.

### Amendment 2026-10-16 (integrator audit)
- Lifetime: the Android controller is owned by a process singleton (`RadioGatewayHolder`), not by the Activity's composition scope (ERROR-681). "Created lazily" still holds: the manager object is only created when the stored switch is ON or the user turns it ON.
- The foreground service is driven through `RadioServiceTracker` and guarded calls (ERROR-682).
- The desktop switch has its own file (ERROR-683).

