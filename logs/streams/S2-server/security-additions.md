# S2-server security additions (fragment; integrator folds into `docs/security.md`)

## Threat rows — directory privacy (Plan A2)

| Threat | Mitigation (built) | Residual risk |
|---|---|---|
| Directory enumeration by an enrolled device | Single lookup is rate-limited (60/min, 10/min misses); ids are 128-bit random; miss bursts audited; lists/cursors/counts/groups/presence reveal nothing about hidden rows (leak matrix tested). | An insider of a hidden department can still copy what they see (no DRM). |
| Hidden-department membership oracle via error codes | Missing, hidden and unlisted ids share one 404 body (status+code; only per-request correlation id differs). | Response timing is not padded (plan: not a goal). |
| Hub operator / compromised hub reads everything | No mitigation by design: the hub stores plaintext names/departments; hidden is listing-only. | Console help text and protocol doc state this literally; UI must not promise more. |
| Local-network name leakage (NSD device names) | None (out of scope). | Documented in console help + protocol: hidden does not hide LAN names. |
| Stale directory after a hide/unhide | Every `hidden` change bumps `directoryEpoch`; clients fully resync. | A client that never refreshes keeps stale rows until it does (existing conversations intentionally stay). |

## What hidden means (normative, mirrors console help)
"People in other departments will not see this department, its people or its
groups in the directory. They can still message each other if they already know
each other. Devices on the same Wi-Fi may still see each other's device names.
Administrators and the department's own members still see it."
"Applies to everyone's directory at their next update."
