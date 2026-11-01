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
The concurrent upload pipeline lives in `HubFileTransfers.kt` (the `Channel<Int>` + `List(concurrency) { launch { ... } }`
block), **not** in `StreamingHubFileSource.kt` — that file is untouched on this branch (`git diff dev..HEAD -- core/engine`
is empty; the first version of this entry named the wrong file). The pipeline originally cancelled the workers the moment one
of them failed (`it.cancel()`), so a worker in the middle of committing chunks 0 and 1 threw `CancellationException` before
those chunks were recorded, and the resume started from chunk 0.

### Working fix
`workers.joinAll()` after the channel is drained: a failing worker sets `terminalError` under `stateMutex`, the others finish
the chunk they hold and then stop pulling new indices, so every committed chunk reaches the state file. (`HubFileTransfers.kt`
now has two such loops — upload and download, lines ~422 and ~688.)

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
A local disk write failure during a download was classified as `Failed` (terminal) rather than `Interrupted` (resumable), so
a transient storage error discarded a partially downloaded file that could have resumed. Again the code is in
`HubFileTransfers.kt`, not `HubFileBinding.kt` — `core/engine` is untouched on this branch; the first version of this entry
named a file the change never touched.

### Working fix
In the download worker, a failed `storage.writePart` or `save()` sets `terminalError` **and** `terminalIsInterrupted = true`,
so the outcome is `HubDownloadOutcome.Interrupted` and the done-set survives for the next attempt.

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
`SqliteRevocationService.fire` (line ~393) runs the registered handlers inside `notifyScope.launch { ... }`, so the list the
test inspects is filled *after* `revokeDevice` returns. Under a saturated JVM the coroutine is scheduled late and the
assertion runs first. Both failing tests in the full run (663 tests, 2 failed) have this shape:
`revokingTwiceChangesNothingAndDoesNotBumpTheVersion` (`expected:<1> but was:<0>`) and
`revokingADeviceRevokesItsIdAndEveryCertificateSerial...` (`expected:<[dev-a]> but was:<[]>`).
Per the project rule and the `ERROR-912` lesson, the production concurrency behaviour must not be degraded to make a test
pass — so the fix belongs in the test.

### Failed attempt
The first version of this entry recorded "nothing was changed" and left the entry OPEN as a documented flake. That was
wrong on the facts: `fire()` deliberately dispatches on `notifyScope` (kicking a relay session must not hold the revocation
write transaction), so a test that reads the handler's list on the next line is **guaranteed** to be racy — it is a
test-design defect that a loaded machine merely exposes, and leaving it OPEN made every future `:server:test` run look
like it might have a revocation bug.

### Working fix
Fixed on the **test** side, production untouched: `RevocationAndEnrolmentTest` gained
`private fun Hub.awaitKicks(expected, settleMs = 100, timeoutMs = 5_000)`, which waits for the handler to fire (bounded),
then holds a 100 ms settle window so "listeners fire **once**" is still asserted rather than merely "at least once". Both
failing tests now use it (`revokingADeviceRevokes...` line 113 and `revokingTwiceChangesNothing...`).

### Verification
`:server:test` in full: see the integrator run recorded in `logs/streams/hub/progress.md`.

### Status
RESOLVED (test-side; the asynchronous notification path is unchanged).
