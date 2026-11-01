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
- **OPT-O17-02 (`0f8ac330`):** Wrote physical chunk files to disk outside SQLite transaction in `FileStore`.
- **OPT-O25-02 / OPT-O25-06 / OPT-O25-09 (`db3a529c`):** O(M+D) directory sync, O(1) group roster checks, UTF-8 budget without string allocation, and preallocated collections.
- **OPT-O16-01 (`83700104`):** Bounded concurrent chunk transfer pipeline (`CHUNK_CONCURRENCY = 3`) for sealed file transfers.
- **OPT-O16-03 (`0504a5f2`):** Suppressed 30-second presence REST poll while relay WebSocket is connected.
- **OPT-O02-03 (`ccf238b7`):** Lightweight member summaries projection in peer resolver to eliminate transient heap spikes.
- **OPT-O27-03 (`56420cb0`):** Shared HTTP/WS client in `E2eDevice` and optimized restart retry backoff.
- **OPT-O17-03 (`8e55053b`):** Reused single pre-encoded JSON frame across group fan-out recipients.
- **OPT-O18-03 (`64672286`):** Immediately rendered first page of devices in admin console before background multi-page fetch.
- **OPT-O16-04 (`ffccd137`):** Batched mailbox receipt acknowledgments (`RelayFrame.RecvAck.ids` and JDBC batch deletes).
- **OPT-O16-02 (`5ea4e6bb`):** Streamed sync pages and projected `OrgDirectoryMemberEntity` lazily into Room to eliminate 40 MB heap spike.
- **OPT-O02-07 / OPT-O17-01 (Document-only):** Documented 5,000-connection hub server memory profile and baseline in `ADR-299` and deployment docs.

## In progress
None — all allocated findings are implemented, tested, and committed.

## Broken
None.

## Last change
`perf(directory): stream sync pages to reduce heap staging spike (OPT-O16-02)` (`5ea4e6bb`) and log additions.

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
Integrator review and merge `agent/opt-hub` into `dev`.

## Files most relevant to next task
- `server/src/main/kotlin/com/transfer/flash/server/files/FileStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt`
- `server/src/main/kotlin/com/transfer/flash/server/relay/MailboxStore.kt`
- `server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/enterprise/directory/OrgDirectorySync.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/enterprise/files/HubFileTransfers.kt`
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/enterprise/EnterpriseHub.kt`
- `logs/streams/hub/`
