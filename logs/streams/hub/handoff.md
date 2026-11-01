# Current Handoff — Hub Stream (H)

## Current branch
`agent/opt-hub` (in worktree `E:\FlashWork\opt-hub`, branched from `dev`)

## Last verified build
`5ea4e6bb` (plus documentation commit for `logs/streams/hub/`)

## Current phase
Optimization Programme — Hub Stream (H) Complete (Batch 1, Batch 2, Batch 3, Document-Only)

## Working features / Completed Findings
- **OPT-O23-01 (`8f87ada6`):** Kept open file handle and eliminated per-chunk fsync in `HubFileDisk`.
- **OPT-O25-10 (`954e6d21`):** Replaced N+1 serial query loop with indexed JOIN on delegation revocation in `SqliteRevocationService`.
- **OPT-O18-01 (`a6c19325`):** Served precompressed gzip and deflate static assets in `AdminConsoleServer`.
- **OPT-O18-02 (`835aa54a`):** Added `<link rel="modulepreload">` tags for admin web assets.
- **OPT-O17-04 (`931fc34f`):** Added `-XX:+ExitOnOutOfMemoryError` and `-XX:+UseG1GC` in deploy templates.
- **OPT-O17-02 (`0f8ac330`, repaired on merge):** stages the chunk bytes outside SQLite's single writer and renames them
  into place **inside** the transaction, so a committed chunk row still implies a present chunk file (ADR-290).
- **OPT-O25-02 / OPT-O25-06 / OPT-O25-09 (`db3a529c`):** O(M+D) directory sync, O(1) group roster checks, UTF-8 budget without string allocation, and preallocated collections.
- **OPT-O16-01 (`83700104`):** Bounded concurrent chunk transfer pipeline (`CHUNK_CONCURRENCY = 3`) for sealed file transfers.
- **OPT-O16-03 (`0504a5f2`) — REVERTED (`8b622a9b`, ADR-298):** suppressed the 30-second presence REST poll while the relay
  WebSocket is connected. Rejected on review: the relay carries no presence frame, so the poll is the *only* writer of
  `hubOnline` and the change aged presence to 10 minutes in the ordinary connected case.
- **OPT-O02-03 (`ccf238b7`):** Lightweight member summaries projection in peer resolver to eliminate transient heap spikes.
- **OPT-O27-03 (`56420cb0`):** Shared HTTP/WS client in `E2eDevice` and optimized restart retry backoff.
- **OPT-O17-03 (`8e55053b`):** Reused single pre-encoded JSON frame across group fan-out recipients.
- **OPT-O18-03 (`64672286`):** Immediately rendered first page of devices in admin console before background multi-page fetch.
- **OPT-O16-04 (`ffccd137`):** Batched mailbox receipt acknowledgments (`RelayFrame.RecvAck.ids`, JDBC batch deletes, ids
  split at 100 per frame on the client). **Transport only:** `HubChatBridge` still acks one delivery at a time, so no
  product path sends a batch yet (ADR-296).
- **OPT-O16-02 (`5ea4e6bb`):** Streams sync pages and projects `OrgDirectoryMemberEntity` lazily into Room. The heap spike it
  removes was never measured (`PERF-H01`).
- **OPT-O02-07 / OPT-O17-01 (Document-only):** deployment baseline (`-Xmx2g`, G1GC, `ExitOnOutOfMemoryError`) recorded in
  `ADR-299` and the deployment docs. Its per-connection and 5 000-session figures are estimates, not measurements
  (`PERF-H03`).

## In progress
Nothing — all allocated findings are implemented and committed. Two were changed by the integrator review: `OPT-O16-03`
reverted, `OPT-O17-02` repaired for atomicity.

## Broken
Nothing known. Unverified: the client half of `OPT-O16-04` is unused (no caller), and the deferred-fsync change (ADR-292)
trades per-chunk durability for one sync at the end — `PERF-H07` is the crash-consistency test owed for it.

## Last change
Integrator repairs on the branch: `Revert "perf(presence): suppress REST poll when relay websocket is connected (OPT-O16-03)"`
(`8b622a9b`), the `FileStore` staging/publish repair, the 100-id cap in `FlashRelayClient.acknowledgeReceipts`, the
corrected durability comment in `HubFileTransfers`, `Vary: Accept-Encoding` on 304 asset answers, and the
`CachedMailboxStatsTest` de-flake (ERROR-936/937). The full verdict table is in **`logs/streams/hub/INTEGRATOR-REVIEW.md`**,
which is the file to read before anything else in this directory.

## Last test
- Node admin console tests: `node --no-warnings --test server/admin-ui-tests/*.test.mjs` (83/83 passed, 595 ms).
- Hub file transfer contract: `:server:test --tests *HubFileTransfer*` (BUILD SUCCESSFUL, exit 0).
- Server migrator: `:server:test --tests *Migrator*` (BUILD SUCCESSFUL, exit 0).
- Hub protocol and relay: `:core:hub-protocol:jvmTest :server:test --tests *Relay*` (BUILD SUCCESSFUL, exit 0).
- Route table contract: `:server:test --tests *RouteTableContractTest*` (BUILD SUCCESSFUL, exit 0).
- Enterprise relay client: `:core:enterprise:jvmTest --tests *FlashRelayClient*` (BUILD SUCCESSFUL, exit 0).
- Org directory sync: `:core:enterprise:jvmTest --tests *OrgDirectorySync*` (BUILD SUCCESSFUL, exit 0).
- Revocation & enrolment: `:server:test --tests *RevocationAndEnrolmentTest*` (BUILD SUCCESSFUL, exit 0).

## Known blockers
None.

## Recommended next task
Merge into `dev` and run the plan §6.3 gate on the merged tree. Then, if the programme continues, the honest follow-ups are:
give `HubChatBridge` a batched drain so `OPT-O16-04` actually saves frames, and add the `PERF-H01`..`H07` harnesses so the
percentages in `logs/streams/hub/` can be reinstated with numbers behind them.

## Files most relevant to next task
- `server/src/main/kotlin/com/transfer/flash/server/files/FileStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseHub.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt`
- `logs/streams/hub/`
