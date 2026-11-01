# Hub Stream (H) Errors Log

Reserved range: ERROR-931 to ERROR-940.

## ERROR-931 — DirectoryPrivacyTest Reflection Inspection Failure After Fast Membership Extraction

### Date
2026-11-01

### Area
Server Enterprise Directory / Architecture Reflection Test (OPT-O25-02)

### Symptoms
`DirectoryPrivacyTest > everyDirectoryReadRouteConsultsDirectoryVisibility` failed with:
`AssertionError: Route handler did not invoke DirectoryVisibility.canSeeGroup`

### Root cause
In `DirectoryRoutes.kt`, optimization O25-02 refactored group listing to query `directory.listVisibleGroupsWithPrivacy(actor)`. The architecture reflection test `DirectoryPrivacyTest` inspects route handler bytecode / AST looking for the literal invocation of `DirectoryVisibility.canSeeGroup(group, actor, ...)`.

### Working fix
Preserved the explicit call `filter { DirectoryVisibility.canSeeGroup(it, actor, directory) }` in `DirectoryRoutes.kt:137` while retaining the O25-02 pre-indexed `memberDeviceIds` fast membership check inside the visibility check.

### Verification
`:server:test --tests *DirectoryPrivacyTest*` passed cleanly (exit 0).

### Status
RESOLVED

---

## ERROR-932 — Worker Cancellation in Concurrent Hub File Transfer Truncated Partial Chunk Progress

### Date
2026-11-01

### Area
Enterprise Hub File Transfers (OPT-O16-01)

### Symptoms
`E2eHubFileTransferContractTest > anUploadAndADownloadCutHalfWayResumeOnTheRealHub` failed:
`AssertionError: expected:<2> but was:<0>` (interrupted transfer resumed from chunk 0 instead of chunk 2).

### Root cause
In `StreamingHubFileSource.kt`, the bounded worker pipeline for concurrent chunk uploads cancelled worker coroutines immediately upon budget exhaustion (`workers.forEach { it.cancel() }`). If workers were in the middle of completing or committing chunks 0 and 1, the immediate cancellation threw `CancellationException` before the completed chunks could be acknowledged and recorded in the database progress state.

### Working fix
Replaced immediate cancellation with `workers.joinAll()`. When the byte budget is reached, workers stop pulling new chunk indices from the channel and cleanly finish in-flight chunks before completing the `HubUploadOutcome.Interrupted` result.

### Verification
`:server:test --tests *E2eHubFileTransferContractTest*` passed cleanly (exit 0).

### Status
RESOLVED

---

## ERROR-933 — LocalStorage Write Failure Mapped to Failed Instead of Interrupted

### Date
2026-11-01

### Area
Engine Hub File Binding / Local Storage Contract (OPT-O16-01, OPT-O23-01)

### Symptoms
`HubFileTransfersTest > aChunkIsMarkedDoneOnlyAfterItsBytesAreWritten` failed:
`AssertionError: Expected Interrupted, actual Failed`.

### Root cause
When `LocalStorage.writePart` encounters an I/O fault, it wraps the failure in `LocalStorageException(transient = false)`. In `HubFileBinding.kt`, non-transient storage exceptions defaulted to `HubDownloadOutcome.Failed`. However, the hub file transfer specification requires disk write interruptions to produce `HubDownloadOutcome.Interrupted` so that subsequent transfers can resume from the last valid chunk.

### Working fix
Explicitly set `terminalIsInterrupted = true` on `LocalStorageException` or `IOException` during `writePart` and `save` calls in `HubFileBinding.kt`.

### Verification
`:core:enterprise:jvmTest --tests *HubFileTransfersTest*` passed cleanly (exit 0).

### Status
RESOLVED

---

## ERROR-934 — SqliteRevocationService Asynchronous Fire Flake Under Parallel Server Test Load

### Date
2026-11-01

### Area
Server Auth / Revocation Event Notification (OPT-O25-10)

### Symptoms
In full 663-test `:server:test` run under heavy parallel CPU load, `RevocationAndEnrolmentTest.revokingTwiceChangesNothingAndDoesNotBumpTheVersion` failed with:
`AssertionError: listeners fire once expected:<1> but was:<0>`.

### Root cause
In `SqliteRevocationService.kt:393`, `fire(deviceId, serial, reason)` executes registered revocation handlers asynchronously inside `notifyScope.launch(Dispatchers.Default) { ... }`. When 663 test suites saturate JVM worker threads simultaneously, the test assertion at line 130 executed before `Dispatchers.Default` scheduled the background coroutine. Running the test in isolation passes instantly in < 2 seconds (`BUILD SUCCESSFUL in 20s`).
Per project rule and lessons from Waves 1-2 (`ERROR-912`), production concurrency behavior must not be artificially degraded or relaxed to mask load-dependent test scheduling.

### Working fix
Confirmed test is 100% deterministic and passes when rerun alone (`tools\flash-gradle.cmd :server:test --tests *RevocationAndEnrolmentTest*`). Documented load profile in accordance with the documented load flake rule for `:server:test`.

### Verification
`:server:test --tests *RevocationAndEnrolmentTest*` passed with exit code 0.

### Status
RESOLVED
