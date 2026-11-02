# Stream E — reviewed decisions (ADR-300..309)

Date: 2026-11-01. Actual base `baf77753`, delivered tip `00a7a51f`. These corrected decisions supersede the delivered
account preserved in Git/progress. Nine action groups cover ten audit IDs; `INTEGRATOR-REVIEW.md` records six
repaired defects. Performance experiments remain NOT MEASURED.

## ADR-300 — Window-owned DropTarget with an updated whole sender (OPT-O19-01)

### Decision / context
Retain `DisposableEffect(window)` and updated navigation/header/pane/chat state. Also retain
`rememberUpdatedState(::sendFileToPeer)` and invoke the current function positionally with all arguments/defaults.
The local sender captures the current chat repository: pre-boot `EmptyFlashChatRepository` is not a forwarding
proxy. Updating only peer/header values leaves the DropTarget using that obsolete repository (`ERROR-948`).
ERROR-941's direct-call workaround is superseded: named arguments are illegal for function-type invocation,
but positional invocation preserves the updated closure without that compiler error.

### Verification / limits
Structural ownership regression passes. Actual AWT drop/navigation/pre-boot behavior: `PERF-E06` TODO.
Registration lifecycle is narrowed, not a measured frame/allocation gain.

---

## ADR-301 — Distinct notification-relevant transfer snapshots (OPT-O19-02)

### Decision
Filter toast rows to actionable Offered/retryable Failed transfers, then distinct relevant fields. Notifications
distinct id/state/fileName/peerName/direction/errorMessage, ignoring byte/speed/ETA churn. This is distinctness,
not timed throttling/debouncing. No measured 100 Hz emission rate or comparator defect was established.

### Verification / limits
Host suite passes; actual prompt/action/notification behavior remains `PERF-E02` TODO.

---

## ADR-302 — Narrow size-class invalidation, not all resize recompositions (OPT-O19-03)

### Context / decision
Base already read `LocalWindowInfo.containerSize` and density with an AWT first-frame fallback, not raw window.width
as its live width source. Remember a derived width-size class; class consumers invalidate at 600/840 dp boundaries.
Full pane geometry still observes actual width and may recompose/layout within a class. No zero-root-recomposition
claim. Compile/host checks pass; resize profiling remains `PERF-E02` NOT MEASURED.

---

## ADR-303 — Settings-local collection without moving controller lifetime (OPT-O03-10)

### Decision
Collect Settings-only flows in `DesktopSettingsDestination`; enterprise sheet values in their consumers. Preserve
radio controller ownership at application root, above the conditional window, using engine.scope; pass it down.
Stored ON initializes without visiting Settings; tabs/minimization/tray do not recreate/dispose it (`ERROR-944`).
Moving StateFlow collection does not authorize moving an operational owner's initialization/lifetime.

### Verification / limits
Structural ownership regression passes. Radio startup/tab/tray hardware check: `PERF-E07` TODO. No driver change.

---

## ADR-304 — Background crypto warming and non-initializing composition view (OPT-O19-06, OPT-O01-05)

### Decision
Lazy crypto/ephemeral key/pairing initialize during background assembly before readiness/discovery. Unconditional
composition uses nullable readyPairing, returning null before readiness, with keyed flows/effects/fingerprints.
Premature lazy access can generate on UI or wait on another thread's lazy monitor: by lazy alone was insufficient
(`ERROR-945`). Ready user actions can use the coordinator. Key/vault/pairing formats remain unchanged.

### Verification / limits
Boot regression proves constructor/pre-ready view do not initialize pairing and post-ready view does. First-frame
latency is NOT MEASURED; no immediate-frame guarantee. `PERF-E01` TODO.

---

## ADR-305 — Guard GHOST and every workload edge in desktop idle downscaling (OPT-O19-07)

### Decision
Use existing screen-active seam for window visibility/minimization. Active window, selected GHOST, Transferring/
Verifying row, non-ENDED call or non-Idle PTT prevents downscale. Reevaluate workload START and FINISH and mode
changes while inactive. Restore selected mode when active. Minimized GHOST must never become advertising ECO
(`ERROR-943`). Hub REST presence poll remains ungated (ADR-298).

### Inherited limits
Desktop connectionPolicy still reads selected mode; JmDNS/multicast honor advertising but not all Composite ECO
browse knobs. Mode transitions alone prove neither quieter sweeps, reduced power, fewer sessions nor desktop
disconnection. The delivered "downscale drops sessions" explanation is not established by these callers.

### Verification
Real host-engine tests cover idle/restore/GHOST and transfer/call START/FINISH; helper truth table covers PTT.
Packet/liveness/power and real PTT checks remain `PERF-E03` TODO.

---

## ADR-306 — One owner for asynchronous desktop output and shutdown (OPT-O20-03)

### Decision
DesktopFileLogSink producers offer raw Entry records to bounded queue 4096; overflow drops newest/counts it. One
worker formats, writes console/file, flushes and closes. Actual file writer buffer is 16,384 characters, not 8 KB.
The old FileWriter also had internal buffering; the entire baseline was not unbuffered.
Shutdown closes admission and joins that worker. Timeout returns false, never writes/closes under an in-flight
record or starts a second writer (`ERROR-946`). Output failures remain best-effort diagnostics.

### Verification / limits
Latch tests cover dequeued record, overflow and timeout without premature close. Raw records are temporarily
retained. No all-lines guarantee under overflow/disk failure/forced kill. Real process checks: `PERF-E04` TODO.

---

## ADR-307 — Host coroutine concurrency bounds, not total JVM-thread cap (OPT-O14-06)

### Decision
If unset, set kotlinx.coroutines.io.parallelism=16 before initialization; engine IO view uses limitedParallelism(8).
Networking/native/other JVM pools remain. These operation-concurrency bounds are not a cap of 16/20 total threads.
No measured 28–34 to under-20 saving is established; `PERF-E05` TODO.

---

## ADR-308 — Documented observable Windows reduced motion (OPT-O24-08)

### Decision
Replace unsupported AWT property names/non-observable atomic preference with SystemParametersInfoW,
SPI_GETCLIENTAREAANIMATION=0x1042, 32-bit BOOL output using existing JNA. Only successful query with output zero
means animations disabled. Failure is unknown. Cold IO Flow polls every five seconds, distincts values and preserves
last learned value on failure. Collect Compose state above conditional window; main window/toast combine with
Flash's setting. No new dependency. Mac/Linux OS detection is not implemented; in-app setting still works.

### Source / verification
Verified official source, checked 2026-11-01:
https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-systemparametersinfow
BOOL output, Vista+ API, zero uiParam/flags for read-only query. Mapping/observable-update tests and real read-only
Windows query pass. Runtime visual toggle/window/toast check: `PERF-E08` TODO. ERROR-947 supersedes AWT/macOS claims.

---

## ADR-309 — Lazy common and large taskbar icon caches (OPT-O19-06, OPT-O01-05)

### Decision / limits
Common 16/24/32/48/64 and large 96/128/256 caches are both lazy. Production getBaseIcons requests common sizes.
getAllBaseIcons can request large sizes but has no caller: high-DPI selection is not integrated/proven. Counts/cache
identity tests pass; DPI/clarity/startup profiling remains `PERF-E01`/`PERF-E02` TODO.
