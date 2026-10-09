# Fix report: screen share (S1-S10) and radio (R1-R7) findings, 2026-10-09

Status: final (written in two passes because of usage limits). Source of findings:
`docs/reports/2026-10-09-review-overnight-code.md`. Nothing committed, pushed, stashed, reset or deleted. No shared log or doc edited
(paste-ready blocks are at the end).

## Findings

Status words: FIXED = a test in this change fails without the fix (mutation checks: see "Mutation checks", most NOT RUN because of the
usage limit, so "test proves" below means "the test asserts the behaviour and was seen failing before/with the defect where stated").

| Id | Sev | Status | Files | Test |
|---|---|---|---|---|
| S1 watchdog cancels its own stop | High | FIXED | `FlashCallSession.kt`, `FlashGroupCallSession.kt` (stop launched on the session scope, native close in `NonCancellable`, `finally { machine.finished() }`) | `FlashScreenShareStopTest`: `1 to 1 a capture that never delivers a frame is stopped to the end by the watchdog`, `group a capture ...` (idle again, `ss=0` sent, camera back on the preview, capture closed once, a new share can start). Other stop/watchdog callers (take-over, user stop, teardown) are separate jobs: no further self-cancel found. |
| S2 teardown order | Med | FIXED (order proven on the pure class; wiring by review) | `ScreenShare.kt` (`ScreenShareRun.closeAfter`), both sessions (`closeShareLocked`, `releaseMediaLocked`) | `ScreenShareTest` closeAfter order tests (senders first, capture second, capture closed even if the sender throws or the caller is cancelled); `FlashScreenShareStopTest` `ending the call while sharing closes the capture even though the sender is gone` (1:1 and group). The session tests cannot observe the order (the test sender throws on every call), so a mutation that bypasses `closeAfter` in the teardown would survive: remaining risk. |
| S3 presenter role held forever | Med | FIXED | `ScreenShare.kt` `ShareArbiter` (bound `max(clock + 24 h, highestSeen + 1)` capped at MAX_START; same bound for the local claim) | `ScreenShareTest` S3 tests (`a member that states the largest start cannot keep the role from a later honest start`, `every device agrees after repeated take-overs on top of an absurd claim`). Existing assertion `nextStart(1L) > MAX_START` was wrong under the new bound and was changed to `NOW + CLOCK_SKEW_MS + 1` (the clamp now happens at the receiver's clock + skew). Residual: any member can still take over by starting a share (no authority model, by design). |
| S4 cancelled start leaves STARTING | Low | FIXED | both sessions `startShareLocked` (rollback in `NonCancellable` on the media thread, rethrow after) | `FlashScreenShareStopTest` `a start cancelled while the capture opens is rolled back` (1:1, group). |
| S5 SOURCE_LOST / "went silent" not implemented | Low | FIXED as documentation only | `ScreenShare.kt` enum comments | none (ADR-102 D8: first frame only, on purpose). ERROR-137 stays OPEN until SHARE-10. |
| S6 MAINTAIN_RESOLUTION sticks | Low | DEFERRED-to-device | `RtpSenderTuning.android.kt` (resets degradation preference to BALANCED when the previous tuning kept MAINTAIN_RESOLUTION and the new one wants neither) | none possible on a host (native `RtpParameters`). Android cannot present yet. Device step: with the Android presenter, share then stop, check the camera tuning. |
| S7 old clients bypass the watcher cap | Low | FIXED | `GroupVideoRouter.kt` `legacyServed()` (pre-G3 peers count against the cap during a share, after request-protocol watchers) | `GroupVideoRouterShareTest` S7 test. |
| S8 session tests never run a share | Low | FIXED | new `FlashScreenShareStopTest.kt` (fake capture handle, `sun.misc.Unsafe`-allocated `RtpSender`, `Proxy` track, test hooks `setVideoSenderForTesting`, `setLocalVideoTrackForTesting`, `setLegVideoSenderForTesting`, `allowShareForTesting`, `shareNowMs` ctor param) | the whole file (9 tests). |
| S9 `shareWatchers` set once | Low | FIXED | `FlashCallSession.kt` `onStatus` | `FlashScreenShareStopTest` `the watcher count follows the peer's data saver during a share (S9)`. |
| S10 lost `ss=0` in a group | Low | FIXED | `FlashGroupCallSession.kt` `onSignalingRestored` ends with `sendStatusTo(peerId)` | `FlashScreenShareStopTest` `group restored signaling to a peer says our status again (S10)`. |
| R1 tester shares an unsynchronised `RadioSession` | Low | FIXED | `RadioLinkTester.kt` (`sessionLock` around session, `pendingAcks`, `seq`) | `RadioLinkTesterTest.concurrentBurstsAndTheReceiveSideShareOneSessionWithoutLosingAcks` (4 concurrent bursts on real threads, all 100 acked). A data race test is probabilistic; see R8. |
| R8 (new, found by the R1 test) a finishing burst cleared the other bursts' pending ACKs | Low | FIXED | `RadioLinkTester.kt` (`ownCounters`) | same test: before the fix 97/100 and 87/100 were acked, after: 100/100. |
| R2 backoff reset on open | Low | FIXED | `KissTncDriver.kt` (`stableAfterMs` = 10 s, reset only if the link stayed up that long or delivered a KISS frame) | `KissTncDriverTest`: `aRadioThatConnectsAndDropsAtOnceIsRetriedWithGrowingBackoff`, `aLinkThatStayedUpLongEnoughStartsTheBackoffOver`, `aLinkThatDeliveredAFrameStartsTheBackoffOver`. |
| R3 RFCOMM write not interruptible | Low | FIXED (logic, with a fake blocking link) | `KissTncDriver.kt` `serve()` (a closer child closes the link the moment the scope is cancelled or either side ends), comment on `RfcommLink.write` in `AndroidBluetoothCatalog.kt` | `KissTncDriverTest.stoppingWhileAWriteIsStuckClosesTheLinkInsteadOfHanging`. A real socket is a device check (BT-16/BT-17 era). |
| R4 reason last-writer-wins | Low | FIXED | `KissTncDriver.kt` `fail()` (first cause wins) | `KissTncDriverTest.theFirstCauseOfALinkFailureIsTheReasonReported`. |
| R5 desktop window blocks its UI thread | Low | FIXED | `RadioLinkTestHarness.kt` (`listPorts` is `suspend` on `Dispatchers.IO`, `catalog.open` in IO), `RadioLinkTestCli.kt` (`runBlocking`), `RadioLinkTestHarnessTest.kt` | `RadioLinkTestHarnessTest.blockingPortCallsNeverRunOnTheCallersThread`. `RadioLinkTestMain.kt` already calls both inside `scope.launch`: no edit needed. |
| R6 manifest / permission note | Low | FIXED as documentation; manifest part DEFERRED | `BluetoothPermissions.kt` KDoc | none. The manifest now declares no Bluetooth permission (another agent's release hygiene), so the shipped state matches the code. Whether `cancelDiscovery()` needs BLUETOOTH_ADMIN on API 30 and lower is UNVERIFIED (the bt-permissions page does not mention that method; the BluetoothAdapter reference was not read in full); the call is in `runCatching`. |
| R7 `ttl` not validated | Low | FIXED | `RadioSession.kt` (`bad_ttl` refusal in `encode` and `encodeSigned`) | `RadioSessionTest.aTtlOutsideTheHeaderRangeIsARefusalNotAnException`. |
| Sweep Lows, `core:calling` | - | NOT-A-BUG / nothing to fix | - | `docs/reports/2026-10-09-sweep-modules.md` lists "none confirmed" for core:calling and did not read ui:callui. No R-* finding lives in core/calling or ui/callui. |
| 4 `NewApi` lint errors | Error | FIXED | `app/src/main/java/com/transfer/flash/calling/FlashCallScreenSupport.kt` (note: the file is in `app/`, not `ui/callui`; see "Outside my area") | `:app:lintReportRelease` after the change: 0 errors, 92 warnings; the 4 NewApi errors are gone (before: 4 errors, 93 warnings). |
| ERROR-167 (jvm `MediaDevices` static init `java.lang.Error`, libpulse missing) | - | FIXED in code, NOT proven by a test (needs a host without the native backend) | `third_party/webrtc-kmp/webrtc-kmp/src/jvmMain/.../MediaDevices.kt` (listener registration and every device enumeration guarded by `nativeList`, a Throwable except VirtualMachineError means "no devices" plus a log line), `third_party/webrtc-kmp/MODIFICATIONS.md` row | none; compiled, `:core:calling:jvmTest` (225) still green incl. `DesktopMediaStackSmokeTest`. Device step in the backlog block (MEDIA-01). |

## Verification

See "Results" below.

## Outside my area (smallest edits)

- `app/src/main/java/com/transfer/flash/calling/FlashCallScreenSupport.kt`: `@RequiresApi(O)` on `params()`, explicit `SDK_INT >= O` check in `enter()`, import `androidx.annotation.RequiresApi`. minSdk unchanged (24). The task named it under ui/callui; the file is in the app module.
- Test hooks in production code are `internal` and named `...ForTesting` (in my area).

## Remaining risk

- Native ordering (replaceTrack before capture dispose, ERROR-123 class) and the camera-restart path after a share cannot be exercised without a device: SHARE-06, SHARE-14.
- S6 (Android degradation preference) is code only.
- Pre-existing, not mine: `core:network` `DialedIdBindingTest` (2 failures, `ws/` handshake work of another agent), `:core:messaging:lintAnalyzeAndroidHostTest` crashes inside lint (K2 bug on `GroupSignatureRulesTest.kt`), so `:app:lintRelease` as one task fails; `:app:lintReportRelease` after `lintAnalyzeRelease` works.
- The coordinator's question about `KissTncDriverTest`: see Results.

## Results (commands run from the repo root with the Gradle env of the project notes)

Env: `JAVA_HOME` = the JBR 21 under `~/.gradle/jdks`, `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix`.

Final run (one invocation, `./gradlew --continue`):
`:core:calling:jvmTest :core:calling:testAndroidHostTest :ui:callui:jvmTest :core:network:jvmTest :core:network:testAndroidHostTest :desktop:compileKotlinJvm :app:compileDebugKotlin`

| Task | Result |
|---|---|
| `:core:calling:jvmTest` | PASS, 225 tests, 0 failures (re-run after the ERROR-167 edit: PASS, 225) |
| `:core:calling:testAndroidHostTest` | PASS, 378 tests, 0 failures (includes `FlashScreenShareStopTest` 9/9, `ScreenShareTest`, `GroupVideoRouterShareTest`) |
| `:ui:callui:jvmTest` | PASS, 81 tests, 0 failures |
| `:core:network:jvmTest` | PASS, 441 tests, 0 failures (includes the 6 new radio tests) |
| `:core:network:testAndroidHostTest` | PASS, 487 tests, 0 failures. An earlier run of this task in the middle of the session had 2 `DialedIdBindingTest` failures (ws handshake, another agent's in-progress `core/network/.../ws` edits). They are gone in the final run. They never touched `KissTncDriverTest`: no `KissTncDriverTest` failure was seen in any of my runs, and the only radio-test failures I saw were my own new tests while I was writing them (test flaws, fixed; R8 was a real defect in `RadioLinkTester`). |
| `:app:compileDebugKotlin` | PASS |
| `:desktop:compileKotlinJvm` | FAIL, not caused by my edits: `desktop/.../DesktopHelpers.kt:154` "This is an internal Flash API and should not be used outside the Flash library modules" (a `FlashLog` call in another agent's uncommitted edit of that file, `git diff --stat` shows 21 insertions there). I did not touch it (outside my area). I did not edit `RadioLinkTestMain.kt`; the only API it uses that changed is `listPorts()`, which it already calls inside `scope.launch`, so my edits should compile there, but that is UNVERIFIED until the other agent's error is fixed and the task re-run. |
| Lint | `:app:lintRelease` as one task fails before reaching the app: `:core:messaging:lintAnalyzeAndroidHostTest` crashes inside lint (K2 "FirDeclaration was not found", `GroupSignatureRulesTest.kt`; not mine, not a source error). Workaround that does produce the report: `:app:lintAnalyzeRelease` then `:app:lintReportRelease` (BUILD SUCCESSFUL): `app/build/reports/lint-results-release.txt` 10:22: "0 errors, 92 warnings", no `NewApi`, no `FlashCallScreenSupport` entry. Before: 4 errors, 93 warnings (stale report from 05:42 still listed the 4). |

Test counts added by this work: `FlashScreenShareStopTest` 9, `ScreenShareTest` (S3 and closeAfter order tests, extra), `GroupVideoRouterShareTest` +1, `KissTncDriverTest` +5, `RadioSessionTest` +1, `RadioLinkTesterTest` +1, `RadioLinkTestHarnessTest` +1.

Failures seen and fixed during the work (all test-side except where noted): `FlashScreenShareStopTest` status assertions ran before the fire-and-forget status frame (added `waitFor`); the "cancelled caller" test cancelled the caller before it started (rewritten to cancel in the middle of the native stop); `ScreenShareTest` S3 attacker-side expectation needed the local claim clamped like a remote one (`setLocal` now bounded: a real fix); `KissTncDriverTest` new tests waited for the first `Connected` which a link that drops at once also produces (now wait for the third open); `RadioLinkTesterTest` stress test used a pacing policy that still charges 1200-baud airtime (now `airBaud = 100_000_000`).

## Mutation checks

NOT RUN. The coordinator cut the work short (usage limit) and asked to skip long runs. A ready tool exists in the session scratchpad (`fcr_mut.py`: apply/restore with sha256 verification), so nobody has to write it again; the mutations it defines are the ones to run, each expected to fail the named test:

| Mutation | Expected to fail |
|---|---|
| watchdog `scope.launch { stopShare(NO_FRAMES) }` to `launch { ... }` (both sessions) | the two watchdog tests in `FlashScreenShareStopTest` |
| remove `NonCancellable` around the native close in `stopShare` / the start rollback | `a stop whose caller is cancelled mid-stop`, `a start cancelled while the capture opens ...` |
| `claimBound()` to `MAX_START`; `setLocal` clamp back to `MAX_START` | the S3 tests in `ScreenShareTest` |
| `closeAfter` closes the capture before the senders | the `closeAfter` order tests |
| `legacyServed` unbounded | `GroupVideoRouterShareTest` S7 test |
| drop `sendStatusTo(peerId)` in `onSignalingRestored`; drop the S9 `shareWatchers` update | the S10 / S9 session tests |
| `if (stable)` to `if (true)`; remove the closer child; `fail()` last-writer-wins | `KissTncDriverTest` R2 / R3 / R4 tests |
| `pendingAcks.clear()` instead of the own counters | `concurrentBurstsAndTheReceiveSideShareOneSessionWithoutLosingAcks` (this defect was observed failing: 97/100 and 87/100 acked) |
| remove the `bad_ttl` check | `aTtlOutsideTheHeaderRangeIsARefusalNotAnException` |
| `listPorts` / `open` without `Dispatchers.IO` | `blockingPortCallsNeverRunOnTheCallersThread` |

Honest status: only R8 has a recorded before/after (97/100 and 87/100 acked before the fix, 100/100 after). For the others "FIXED" means the test asserts the repaired behaviour and passes now; that each test is killed by its mutation is still owed.

## Open questions for the owner

None blocking. The arbiter change (remote and local claims bounded by `max(clock + 24 h, highestSeen + 1)`) refines ADR-102's "latest start wins"; no new ADR (ADR-104 not used). If the owner prefers an addendum line in ADR-102, the text is in the `ShareArbiter` KDoc.

---

# Paste-ready blocks

### logs/progress.md

```markdown
## 2026-10-09 - Fix round: screen share (S1-S10) and radio (R1-R8), NewApi lint, ERROR-167

### Worked on
The review findings of docs/reports/2026-10-09-review-overnight-code.md for streams 2 and 3, the 4 NewApi lint errors, and ERROR-167.

### Changed
- core/calling: the first-frame watchdog no longer cancels its own stop (stop on the session scope, native close in NonCancellable, finally -> machine.finished); the start rollback and the stop are not cancellable; teardown takes the screen off the senders before the capture closes (ScreenShareRun.closeAfter); ShareArbiter bounds remote AND local claims by max(clock + 24 h, highestSeen + 1); legacy peers count against the share cap; group sessions re-send their status when signalling is restored; 1:1 shareWatchers follows the peer's data saver; Android resets MAINTAIN_RESOLUTION; enum comments corrected (S5).
- core/network radio: backoff resets only after a stable link (10 s up or one KISS frame); the driver closes the link as soon as it is cancelled or either side ends (uninterruptible RFCOMM write); first failure cause wins; RadioSession refuses ttl outside 0..255 with bad_ttl; the BT-00 tester serialises RadioSession access and no longer clears other bursts' pending ACKs; the harness lists/opens ports off the caller's thread.
- app: FlashCallScreenSupport.kt PiP calls are API-26 guarded (lint 0 errors).
- third_party/webrtc-kmp jvm MediaDevices: a java.lang.Error from the native backend (no libpulse) now means "no devices" (ERROR-167).

### Verification
core:calling jvmTest 225 and testAndroidHostTest 378, ui:callui jvmTest 81, core:network jvmTest 441 and testAndroidHostTest 487, app:compileDebugKotlin: all green. app:lintReportRelease: 0 errors, 92 warnings. desktop:compileKotlinJvm fails in another agent's DesktopHelpers.kt:154 (internal API), not in this work. Mutation checks not run (usage limit), see docs/reports/2026-10-09-fix-calling-radio.md.

### Remaining
Device checks SHARE-15..19, BT-18..20, MEDIA-01; mutation checks; S6 and ERROR-167 are code-only.

### Next AI
Run the mutation table in docs/reports/2026-10-09-fix-calling-radio.md; re-run :desktop:compileKotlinJvm once DesktopHelpers.kt is fixed.
```

### logs/handoff.md

```markdown
## Fix round calling/radio (2026-10-09)
Built and unit-tested, uncommitted, not device-verified: ERROR-138...147 and ERROR-167 fixes (see docs/reports/2026-10-09-fix-calling-radio.md). Not done: mutation checks of the new tests; the session-level proof that the teardown calls closeAfter (the test sender throws on every call, so the order is only proven on ScreenShareRun.closeAfter). :desktop:compileKotlinJvm is broken by another agent's DesktopHelpers.kt:154 until they fix it. `:app:lintRelease` as one task crashes in :core:messaging:lintAnalyzeAndroidHostTest (lint K2 bug); use :app:lintAnalyzeRelease then :app:lintReportRelease.
```

### logs/errors.md

```markdown
## ERROR-138 - Screen-share first-frame watchdog cancelled its own stop
Date 2026-10-09. Area: core/calling screen share. Symptom (reviewed, not seen on a device): a capture that opens but never delivers a frame left the share stuck in STOPPING: no ss=0, camera not restored, capture open. Root cause: the stop was launched inside the watchdog job and stopShare cancels that job, so the child cancelled itself at its first suspension. Fix: stop launched on the session scope, native close in withContext(NonCancellable), finally { machine.finished() } (both sessions). Verification: FlashScreenShareStopTest watchdog tests (1:1, group). Status: OPEN until SHARE-15.

## ERROR-139 - A presenter claim could hold the role for good
Date 2026-10-09. Area: core/calling ShareArbiter. Root cause: remote claims were clamped to year 2100, local ones were not, and the honest take-over tied with the absurd claim at the ceiling, where the higher id won. Fix: claims bounded by max(clock + 24 h, highestSeen + 1), local claim bounded the same. Verification: ScreenShareTest S3 tests. Residual: any member can still take over by starting a share (no authority, by design). Status: OPEN until SHARE-17.

## ERROR-140 - Call teardown closed the screen capture before the senders let go
Date 2026-10-09. Area: core/calling. Same class as ERROR-123 (native crash when a sink outlives its source). Fix: ScreenShareRun.closeAfter (senders replaceTrack first, then the capture, even if the sender throws or the caller is cancelled), used by stop and teardown in both sessions. Verification: order proven on ScreenShareRun in ScreenShareTest; session wiring only by test of "capture closed with a dead sender". Status: OPEN until SHARE-16.

## ERROR-141 - Screen-share Lows S4, S7, S9, S10
Date 2026-10-09. A cancelled start left STARTING (rollback now NonCancellable on the media thread); pre-G3 peers bypassed the watcher cap (legacyServed); 1:1 shareWatchers was set once; a lost ss=0 in a group was never repaired (status re-sent on signalling restore). Verification: FlashScreenShareStopTest, GroupVideoRouterShareTest. Status: OPEN until SHARE-19.

## ERROR-142 - KissTncDriver reconnect backoff reset on open
Date 2026-10-09. A radio that accepts the open and drops at once was retried every base delay for ever. Fix: stableAfterMs (10 s up, or one KISS frame) before the backoff starts over. Verification: three KissTncDriverTest tests. Status: OPEN until BT-18.

## ERROR-143 - KissTncDriver: uninterruptible write and last-writer-wins reason
Date 2026-10-09. A blocking RFCOMM write ignores coroutine cancellation and the link was closed only after serve() returned; the link-down reason was whichever side finished last. Fix: closer child closes the link on cancel or when either side ends; first cause wins. Verification: KissTncDriverTest stuck-write and first-cause tests (fake links). Status: OPEN until BT-19 (real socket).

## ERROR-144 - BT-00 tester: unsynchronised RadioSession and a burst clearing other bursts' ACKs
Date 2026-10-09. RadioSession is not thread-safe but the receive collector and the callers shared it; a finishing burst did pendingAcks.clear() and lost the other bursts' ACKs (found by the new stress test: 97/100 and 87/100 acked). Fix: sessionLock mutex; bursts remove only their own counters. Verification: RadioLinkTesterTest concurrent bursts (100/100). Status: RESOLVED (test-tool code, unit-tested; no device needed).

## ERROR-145 - BT-00 desktop window blocked its UI thread on serial calls
Date 2026-10-09. listPorts/open are blocking native calls called from the Compose scope. Fix: suspend + Dispatchers.IO in the harness. Verification: RadioLinkTestHarnessTest.blockingPortCallsNeverRunOnTheCallersThread. Status: OPEN until BT-20.

## ERROR-146 - RadioSession.encode did not validate ttl
Date 2026-10-09. A bad ttl threw IllegalArgumentException from the header; now Refused("bad_ttl") in encode and encodeSigned. Verification: RadioSessionTest. BluetoothPermissions KDoc corrected (cancelDiscovery permission on API 30 and lower is UNVERIFIED). Status: RESOLVED.

## ERROR-147 - Four NewApi lint errors in FlashCallScreenSupport.kt
Date 2026-10-09. Picture-in-picture needs API 26, minSdk is 24; the call sites were guarded only by a helper lint cannot see. Fix: @RequiresApi(O) on params(), explicit SDK_INT check in enter(). Verification: :app:lintReportRelease 0 errors. Status: RESOLVED (lint). Device check of PiP unchanged.

## ERROR-167 update
2026-10-09: jvm MediaDevices now guards the listener registration and every device enumeration; a java.lang.Error from the native backend means "no devices" plus a log. Not unit-tested. Status: OPEN until MEDIA-01.
```

### docs/decisions.md

```markdown
No new ADR (ADR-104 unused). Addendum worth one line under ADR-102: the presenter arbitration bound is max(receiver clock + 24 h, largest start seen + 1) for remote and local claims; a fixed ceiling ties an honest take-over with an absurd claim.
```

### docs/testing/TEST-BACKLOG.md

```markdown
### 4zs Fix round: screen share and radio (2026-10-09)
- SHARE-15 No-frame watchdog. Setup: Windows presenter, a share source that produces no frame (a minimised window if the capturer then sends nothing). Steps: start sharing, wait past ShareLadder.FIRST_FRAME_TIMEOUT_MS. Pass: the share ends with the "no picture" notice, the camera returns, the viewer's tile goes back to the camera, a new share can start; log has "no frame within". Source: ERROR-138. Status: TODO
- SHARE-16 Hang up while sharing, 20 times (Windows, then Linux). Pass: no native crash, no `hs_err`, capture closed each time. Source: ERROR-140, ERROR-123 class. Status: TODO
- SHARE-17 Take-over after a skewed clock. Setup: three devices in a call, one with its clock set 30 days ahead. Steps: the skewed device shares, then a second device starts a share. Pass: the second device becomes the presenter on all three, the skewed one stops. Source: ERROR-139. Status: TODO
- SHARE-18 Android presenter tuning (BLOCKED until the Android presenter exists). Steps: share, stop, check the camera sender degradation preference is back to balanced. Source: S6. Status: TODO
- SHARE-19 Lost ss=0 in a group. Setup: three devices; the presenter stops while one viewer's signalling is down, then it recovers. Pass: the viewer drops the presenter claim within one status round. Source: ERROR-141. Status: TODO
- BT-18 Radio that connects and drops. Setup: a serial radio that accepts the open then resets (or a wire pulled right after open). Pass: the Waiting retry delays grow (1 s, 2 s, 4 s ...) in the log. Source: ERROR-142. Status: TODO
- BT-19 Stuck write on Android RFCOMM (BLOCKED: no debug entry yet, see BT-16). Steps: power the radio off mid-write, stop the driver. Pass: stop returns within about 1 s. Source: ERROR-143. Status: TODO
- BT-20 Desktop window stays responsive while listing a hanging Bluetooth virtual COM port (Windows). Pass: the window repaints during Refresh. Source: ERROR-145. Status: TODO
- MEDIA-01 Linux without libpulse (container or after removing it). Steps: start Flash desktop, start a call. Pass: no java.lang.Error, no crash; log says "unavailable ... treating as no devices"; the call reports no microphone. Source: ERROR-167. Status: TODO
```
