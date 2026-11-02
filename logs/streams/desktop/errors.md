# Stream E (Desktop) — Errors & Fixes Log

## ERROR-943 — Minimized GHOST advertises, and hidden workload START is not reevaluated

### Date / area / environment
2026-11-01; desktop discovery caller; delivered `00a7a51f`, base `baf77753`, Windows/JBR 21.

### Symptoms / evidence
Selected GHOST with an inactive window became effective ECO, enabling advertising that GHOST suppresses. Delivered
completion-only observers also failed to restore selected mode when transfer/call/PTT work STARTED while already hidden.
Review and real-engine fixtures identified these caller errors; no measured power/session-drop diagnosis is claimed.

### Root cause / failed approach
Idle-window state was applied without GHOST privacy protection and without all workload edges/mode-change reevaluation.

### Working fix
Guard active window, selected GHOST, Transferring/Verifying, non-ENDED call and non-Idle PTT. Reevaluate START/FINISH and
selected-mode changes while inactive. Desktop policy still reads selected mode; not every ECO knob has a desktop caller.

### Verification / related files / status
Idle/restore/GHOST/hidden mode change and transfer/call START/FINISH host fixtures pass; PTT truth table passes in 259-test
suite. `DesktopEngine.kt`, `DesktopDiscoveryActivity.kt`, `DesktopDiscoveryDownscaleTest.kt`; ADR-305, PERF-E03.
**OPEN — code fixed/host-tested; physical packet/PTT/liveness checks TODO.**

## ERROR-944 — Settings-local radio creation changes operational lifetime

### Date / area / environment
2026-11-01; Compose desktop application ownership; delivered `00a7a51f`, Windows host.

### Symptoms / evidence
Moving the radio controller into the conditional Settings destination made stored ON depend on visiting that tab and
made navigation/window lifetime affect an operational owner. Flow-collection extraction alone did not authorize this.

### Root cause / failed approach
Operational initialization/lifetime was moved together with Settings-only StateFlow collection.

### Working fix
Keep controller at application root above the conditional main window, using engine.scope, and pass it down. Stored ON
initializes without Settings; intentional OFF/application shutdown retain existing release behavior. Driver unchanged.

### Verification / related files / status
Structural ownership and controller tests pass; `DesktopMain.kt`, `DesktopShell.kt`, `DesktopCompositionOwnershipTest.kt`;
ADR-303, PERF-E07. **OPEN — code fixed/host-tested; hardware startup/tab/tray check TODO.**

## ERROR-945 — Lazy pairing is still initialized or awaited by unconditional composition

### Date / area / environment
2026-11-01; desktop startup/composition; delivered `00a7a51f`, Windows/JBR 21.

### Symptoms / evidence
Composition read engine.pairing before readiness. By-lazy can generate on the UI thread or block on background assembly's
lazy monitor. A lazy property by itself does not prove off-UI initialization or an immediate frame.

### Root cause / failed approach
Unconditional composition kept a direct lazy read after background warming was introduced.

### Working fix
Expose nullable non-initializing readyPairing; key nullable UI flows/effects/fingerprints on ready coordinator. Assembly
warms it before readiness. Ready user actions may use pairing normally.

### Verification / related files / status
Boot regression checks lazy remains uninitialized through constructor/pre-ready view, then exists at readiness.
`DesktopEngine.kt`, `DesktopShell.kt`, `DesktopEngineBootTest.kt`; ADR-304, PERF-E01.
**RESOLVED (host lifetime regression verified); first-frame profiling TODO/NOT MEASURED.**

## ERROR-946 — Async logger shutdown competes with an in-flight output writer

### Date / area / environment
2026-11-01; desktop log output/shutdown; delivered `00a7a51f`, Windows host.

### Symptoms / evidence
Delivered output/close ownership allowed a shutdown path to operate while the worker had a dequeued record. Queue
emptiness did not prove output complete; competing close/write could truncate or write after close.

### Root cause / failed approach
Shutdown/producer paths were allowed to become output owners instead of joining the sole worker.

### Working fix
DesktopFileLogSink queues raw bounded records; one worker formats/writes console/file, flushes and closes. Shutdown closes
admission and joins. Timeout returns false without closing under worker or adding a writer. Overflow drops newest/counts
it; failures remain best-effort. Actual BufferedWriter capacity 16,384 characters; old FileWriter also internally buffered.

### Verification / related files / status
Three latch tests check in-flight record, overflow and timeout with write/flush/close ownership; 259-test suite passes.
`DesktopFileLogSink.kt`/Test, `DesktopMain.kt`; ADR-306, PERF-E04.
**OPEN — code fixed/host-tested; real-process exit/output profiling TODO.**

## ERROR-947 — Unsupported AWT motion properties are not observable Windows preference

### Date / area / environment
2026-11-01; desktop accessibility; delivered `00a7a51f`, Windows 11/JBR 21, existing JNA.

### Symptoms / evidence
The delivered AWT property names were not the documented OS preference API; non-observable state also could not drive
Compose updates without another event. The report's Windows/macOS detection was not established.

### Root cause / failed approach
Undocumented property names/atomic state were treated as OS integration plus observable UI state.

### Working fix
SystemParametersInfoW SPI_GETCLIENTAREAANIMATION (0x1042), 32-bit BOOL via IntByReference; only successful zero means off.
Cold Flow polls on IO every five seconds, distincts and preserves last learned value on failure. Collect above conditional
window and combine OS/in-app preferences for window/toast. No Linux/macOS OS detection/new dependency.

### Verification / related files / status
Mapping, Flow updates/failure retention and actual read-only Windows query pass. Official Microsoft source checked
2026-11-01 in ADR-308. `DesktopOsReduceMotion.kt`/Test, DesktopMain.kt; PERF-E08.
**OPEN — host/native query verified; live visual toggle/window/toast check TODO.**

## ERROR-948 — Window-owned DropTarget retains a pre-boot repository sender

### Date / area / environment
2026-11-01; desktop drag-and-drop closure ownership; delivered `00a7a51f`, Windows host.

### Symptoms / evidence
The sender captures current chats, which before boot is EmptyFlashChatRepository (not a forwarding proxy). A long-lived
window DropTarget directly capturing that local sender can retain an obsolete repository despite current header/peer state.
ERROR-941's compiler workaround removed the updated function and therefore left this stale capture.

### Root cause / failed approach
Only target/header states were refreshed; the entire repository-capturing function was wrongly assumed stable.

### Working fix
Keep rememberUpdatedState(::sendFileToPeer); invoke current function positionally with all arguments/defaults. This both
preserves the closure and avoids illegal named function-type arguments. The actual sender has more than two arguments;
ERROR-941's Function2 shorthand was not its true type.

### Verification / related files / status
Structural regression verifies current whole sender in window-owned effect; compilation/259-test suite pass.
`DesktopShell.kt`, `DesktopCompositionOwnershipTest.kt`; ADR-300, PERF-E06.
**OPEN — code fixed/host-tested; actual AWT drop/navigation/pre-ready check TODO.**

## Preserved delivered error history

## ERROR-941 — Kotlin Function Type Prohibits Named Arguments in DesktopShell

### Date
2026-11-01

### Area
Desktop Compose UI / Drag-and-Drop Optimization (OPT-O19-01)

### Symptoms
Kotlin compiler error in `DesktopShell.kt:935`:
`Named arguments are prohibited for function types. No parameter with name 'peerId' found.`

### Environment
- Kotlin 2.1.0 / Compose Multiplatform
- Module: `:desktop`
- Target: JVM Desktop

### Error
```text
e: file:///E:/FlashWork/opt-desktop/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:935:25
Named arguments are prohibited for function types. No parameter with name 'peerId' found.
```

### Root cause
`val currentSendFileToPeer by rememberUpdatedState(::sendFileToPeer)` coerced the local member function reference into a Kotlin `Function2<...>` lambda type. In Kotlin, invoking a function type variable using named parameters (`currentSendFileToPeer(peerId = ...)` is prohibited by the language specification because parameter names are not preserved on function interfaces.

### Failed attempts
1. Invoking `currentSendFileToPeer(peerId = peerId, paths = paths)`.

### Delivered compiler workaround — SUPERSEDED by ERROR-948
Removed the `rememberUpdatedState` wrapper for the stable local function and called `sendFileToPeer(peerId = peerId, paths = paths)` directly.

Integration correction (2026-11-01): the actual sender has more than two parameters, and it captures the current repository;
the `Function2`/stable-function shorthand above was inaccurate. Retain an updated whole function and invoke it positionally.
Compiler success did not verify the lifetime of the captured repository.

### Verification
`:desktop:compileKotlinJvm` compiled successfully.

### Status
SUPERSEDED by ERROR-948 — the compiler error was resolved, but its direct-call workaround introduced a stale sender.

---

## ERROR-942 — Unresolved Hub Telemetry and Security Types During Shell Decoupling

### Date
2026-11-01

### Area
Desktop Compose UI / StateFlow Decoupling (OPT-O03-10)

### Symptoms
Compilation error during extraction of `DesktopSettingsDestination`:
`Unresolved reference 'hubStatus'` and `Unresolved reference 'EnterpriseCredentials'`.

### Environment
- Kotlin 2.1.0 / Compose Multiplatform
- Module: `:desktop`
- Target: JVM Desktop

### Error
```text
e: file:///E:/FlashWork/opt-desktop/desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt:1714:21
Unresolved reference 'hubStatus'.
```

### Root cause
`hubStatus` was needed by `FlashDestination.Hub` in the main shell body as well as in settings. Additionally, enterprise credentials in `DesktopEngine` use type `com.transfer.flash.core.security.enterprise.EnrollmentCredentials?` rather than `EnterpriseCredentials`.

### Working fix
Retained `hubStatus` StateFlow collection at the top of `DesktopShell` for `FlashDestination.Hub` and forwarded it to `DesktopSettingsDestination`; imported `EnrollmentCredentials` from `com.transfer.flash.core.security.enterprise`.

### Verification
`:desktop:compileKotlinJvm` and all desktop tests compiled and passed.

### Status
RESOLVED
