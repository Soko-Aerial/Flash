# Architectural Decision Additions — Stream R-ui

Reserved ADR range: ADR-192 through ADR-196.

---

## ADR-192 — Decoupled Radio Gateway UI Controller Port in :ui:chat

### Context
Plan R Revision 3 §6 and §8 describe the Hardware Radio KISS TNC Gateway UI covering settings entry, "Is your radio ready?" checklist, BLE scan, device pairing, encrypted profile authorization, operating modes, and RF transmission warning diagnostics.
The UI needs to be developed in parallel with `:core:radio` without taking a direct module dependency on `:core:radio` or native serial/Bluetooth libraries.

### Decision
1. Define `RadioGatewayController` interface and `RadioGatewayUiState` strictly inside `:ui:chat` (`com.transfer.flash.ui.radio`).
2. `RadioGatewayUiState` carries only plain Kotlin types, enums, and ready-to-show status sentences/steps.
3. Provide `FakeRadioGatewayController` that implements the interface completely with deterministic simulation of all scan stages, devices, authorization checks, and failure states.
4. Provide pure JVM-testable helper objects `FlashRadioCopy` (100% of user-facing strings and accessibility labels) and `FlashRadioMath` (pure calculation of candidate ranking, next step guidance, button enablements).
5. All automated unit and UI tests run against the fake controller. Real hardware testing is preserved for manual owner verification via runbook entries.

### Consequences
- `:ui:chat` remains lightweight, multiplatform, and completely isolated from hardware serial / RFCOMM plumbing.
- Stream R-link or future integration streams can implement `RealRadioGatewayController` backed by `RadioManager` without touching any UI layouts or tests.

### Status
PROPOSED / IMPLEMENTED
