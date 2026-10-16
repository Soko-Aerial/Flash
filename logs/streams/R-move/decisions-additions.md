# Architectural Decision Additions — Stream R-move

Reserved ADR range: ADR-177 through ADR-181.

---

## ADR-177 — Separation of Hardware Radio Codecs and Transports into `:core:radio`

### Context
Plan R (Revision 3, `docs/plans/RADIO-GATEWAY-PLAN.md`) and ADR-173 mandate decoupling amateur / hardware radio framing, drivers, and platform serial/Bluetooth implementations from `:core:network`. Previously, AX.25 and KISS codecs, RFCOMM socket handlers, jSerialComm, and TNC drivers resided inside `com.transfer.flash.core.network`. This caused unnecessary coupling where `:core:network` pulled in desktop serial libraries and hardware-specific framing, violating the principle of distinct network versus peripheral radio boundaries.

Furthermore, `ReconnectPolicy.kt` was co-located in `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/resilience/`. Examination revealed `ReconnectPolicy` contains zero network-specific types; it is a general floored exponential backoff with full jitter.

### Decision
1. Create a dedicated Kotlin Multiplatform module `:core:radio` targeting JVM and Android host (`androidLibrary`), with Kotlin `explicitApi()` enabled and `-Xexpect-actual-classes` compiler argument.
2. Relocate all AX.25, KISS, TNC driver, pacing, link abstractions, and test harnesses from `:core:network` to `com.transfer.flash.core.radio` and subpackages (`kiss`, `link`, `driver`, `wire`, `sim`, `diag`, `connect`).
3. Move `jSerialComm` 2.11.4 dependency exclusively into `:core:radio` `jvmMain`.
4. Relocate `ReconnectPolicy` to `:core:common` under `com.transfer.flash.core.common.resilience`, enabling both `:core:network` and `:core:radio` to use resilient backoff without cross-module dependency.
5. Enforce strict unidirectional dependency: `:core:radio` depends only on `:core:common` and `:core:security`. `:core:network` does not depend on `:core:radio`, and `:core:radio` does not depend on `:core:network`. Verified by `RadioDependencyDirectionContractTest`.
6. Host apps (`:app` and `:desktop`) depend on `:core:radio`. `:core:engine` remains completely isolated from `:core:radio`.

### Status
ACCEPTED (2026-10-16)

---

## ADR-178 — Identity and State Model for Radio Connection and Rule 4 Prohibition

### Context
Plan R §6 and §8 define user experience, state flow, and security constraints for radio gateway connectivity. Unlike LAN or Wi-Fi Direct transports which authenticate ephemeral dynamic IP addresses, hardware radios are peripherals identified either via volatile OS port names (e.g. `COM16`, `/dev/rfcomm0`) or stable Bluetooth hardware addresses (MAC addresses). In addition, physical radios emit RF over amateur or commercial frequencies; sending arbitrary packets or ping frames causes physical transmission.

### Decision
1. **Radio Identity is Bluetooth Hardware Address**: Port names are volatile OS artifacts on Windows/Linux. The primary identifier of a remembered radio is strictly its hardware MAC address (`RememberedRadio.bluetoothAddress`). `lastKnownPortName` is preserved strictly as a Windows fallback hint.
2. **Rule 4 Liveness Transmit Prohibition**: The radio connection state machine (`RadioConnectStateMachine`) shall NEVER emit data frames as liveness probes. Transmit commands are strictly confined to user-initiated traffic or explicit diagnostic test frames (`UserRequestedTestMessage`), which requires explicit authorization at instantiation.
3. **Rule 3 Confirmation Gate**: When auto-detect finds an eligible radio candidate, the state machine enters `ConfirmCandidate` and NEVER opens serial/RFCOMM sockets or emits `OpenLink` before explicit user confirmation (`UserConfirmedCandidate`).
4. **Security Profile M Confirmation Gate**: Encrypted transmission (Profile M) requires explicit user confirmation with a strictly positive epoch timestamp (`confirmedAtEpochMs > 0`). In the absence of recorded confirmation, only Profile A (plaintext signed) is selectable.

### Status
ACCEPTED (2026-10-16)
