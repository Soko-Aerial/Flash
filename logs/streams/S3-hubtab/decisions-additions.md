# S3 decisions (numbers from the reserved range 159-168)

## ADR-159 — Enrolment lives in the navigation state and is saved with it
**Decision.** `FlashNavigationState` holds `hubEnrolled`, saved as a marker entry `@hubEnrolled|0/1` in the existing list saver; `normalised(entries, hubEnrolled)` rewrites any stack for the current tab set. Hosts call `updateHubEnrolled` only once the engine is `ready`.
**Why.** Enrolment is unknown until the engine boots; restoring a Hub position on process death must not be lost or crash. Alternatives: host-held flag passed into every call (rejected: nine call sites, easy to forget); persisting in settings (rejected: a second source of truth).
**Revisit when** a cold start of an enrolled device must show Hub on the first frame (limitation: it shows the old tab set until ready).

## ADR-160 — PROPOSED: what counts as an "active" transfer in the top-bar indicator
Offered, Queued, Transferring, Verifying count; Paused, Completed, Failed, Cancelled do not. **Owner to confirm** (a paused transfer currently shows no indicator).

## ADR-161 — Transfers entry points go through `openTransfers()`
Every host path that opens Transfers (notification, shortcut, tray, Ctrl+2, top-bar icon) calls `FlashNavigationState.openTransfers()`: selects the tab when unenrolled, pushes when enrolled. No host calls `selectTab(Transfers)` any more.
