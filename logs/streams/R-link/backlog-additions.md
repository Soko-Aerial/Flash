# Backlog Additions — Stream R-link

All device tests owed on physical hardware for Phase R1 & R2 (`runbook-R1-R2.md`).

### RADLNK-D01 — Windows Physical Radio Cold Start & Checklist
- **Setup:** Windows 11 PC, VR-N76 powered off. Flash Desktop launched.
- **Steps:** Navigate to Settings -> Radio Gateway, toggle switch ON.
- **Pass:** Checklist screen appears; serial COM port is NOT opened by Flash.
- **Source:** Plan R §6 Step 1; `runbook-R1-R2.md` Step W1.
- **Status:** TODO

### RADLNK-D02 — Windows Physical Radio Rule 3 Confirmation Gate
- **Setup:** Windows 11 PC, VR-N76 paired in Windows, KISS TNC enabled in radio menu.
- **Steps:** Acknowledge checklist; observe proposed candidate.
- **Pass:** VR-N76 displayed with "Confirm" prompt; port is NOT opened until confirmed.
- **Source:** Plan R §6 Rule 3; `runbook-R1-R2.md` Step W2.
- **Status:** TODO

### RADLNK-D03 — Windows Physical Radio Dynamic Registry COM Mapping
- **Setup:** Windows 11 PC, VR-N76 connected. Port renumbered in Device Manager or re-paired.
- **Steps:** Confirm connection in Flash Desktop.
- **Pass:** Flash interrogates `HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM` and connects to the new virtual COM port.
- **Source:** Plan R §6 (Windows); ADR-182; `runbook-R1-R2.md` Step W3.
- **Status:** TODO

### RADLNK-D04 — Windows Physical Radio Power-Cycle Auto-Recovery
- **Setup:** Connected to VR-N76.
- **Steps:** Turn radio power OFF; observe status change to reconnecting; turn radio back ON; wait 10s.
- **Pass:** Flash reconnects automatically; status returns to "Connected to VR-N76".
- **Source:** Plan R §6 Reconnecting; `runbook-R1-R2.md` Step W4.
- **Status:** TODO

### RADLNK-D05 — Windows Physical Radio Reconnect Retry Limit & Reconnect Button
- **Setup:** Connected to VR-N76.
- **Steps:** Turn radio power OFF and leave off past 5 exponential backoff attempts.
- **Pass:** Retries cease; UI displays "Reconnect" action button.
- **Source:** Plan R §6 Reconnecting; `runbook-R1-R2.md` Step W5.
- **Status:** TODO

### RADLNK-D06 — Windows test message gates, then one real transmit (THIS TRANSMITS)
- **Setup:** Connected to VR-N76 over physical Bluetooth serial port; legal frequency and Profile A (amateur band: never Profile M).
- **Steps:** (1) without authorisation, request a test message with the warning confirmed; (2) with authorisation, request without confirming; (3) confirm and send.
- **Pass:** (1) and (2) do not key the radio (probe `radio.tx.test_message result=NOT_AUTHORISED` / `NOT_CONFIRMED`); (3) keys once (`result=SENT`).
- **Source:** Plan R §6 Step 6 & §8; `runbook-R1-R2.md` Step W6.
- **Status:** TODO

### RADLNK-D07 — Android Physical Radio Nearby Devices Permission at Point of Use
- **Setup:** Android 12+ phone, Flash app without Bluetooth permissions.
- **Steps:** Toggle Radio Gateway switch ON -> tap Permissions.
- **Pass:** System dialog prompts for "Nearby devices" only; NO location prompt.
- **Source:** Plan R §6 Android permissions; `runbook-R1-R2.md` Step M1.
- **Status:** TODO

### RADLNK-D08 — Android Physical Radio In-App Discovery & Bonding
- **Setup:** Android phone, VR-N76 unpaired and in discoverable state.
- **Steps:** Tap "Scan Radios" -> tap "Pair" on discovered VR-N76 candidate.
- **Pass:** Radio discovered with 12s countdown; in-app bonding succeeds without opening system settings.
- **Source:** Plan R §6 Android discovery; `runbook-R1-R2.md` Step M2.
- **Status:** TODO

### RADLNK-D09 — Android Physical Radio FGS `connectedDevice` Notification Lifecycle
- **Setup:** Android phone connected to VR-N76.
- **Steps:** Background Flash app; observe system notification shade.
- **Pass:** Ongoing `connectedDevice` notification displayed; process survives backgrounding.
- **Source:** Plan R §4 & §6; `runbook-R1-R2.md` Step M3.
- **Status:** TODO

### RADLNK-D10 — Android Physical Radio Switch Off Cleanup
- **Setup:** Android phone with active RadioLinkService.
- **Steps:** Open Flash and toggle Radio Gateway switch OFF.
- **Pass:** `RadioLinkService` stops immediately; notification dismissed; RFCOMM link cleanly closed.
- **Source:** Plan R §6 Rule 3 & §4; `runbook-R1-R2.md` Step M4.
- **Status:** TODO
