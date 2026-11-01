# Integrator review — Desktop (E)

Reviewed 2026-11-01 against actual base `baf77753`, delivered tip `00a7a51f`, code, callers and tests. Independent read-only
review completed; caller inspection and repairs completed in integration session. Targeted-tested, not yet committed/
merged at this entry. No measured performance or physical-device claim.

## Dispositions

| Finding | Disposition |
|---|---|
| OPT-O19-01 | KEEP, REPAIRED: window-owned DropTarget updates whole sender, ERROR-948 |
| OPT-O19-02 | KEEP: notification-relevant distinct snapshots, not timed debounce |
| OPT-O19-03 | KEEP: derived size class, not zero full-geometry/root recompositions |
| OPT-O03-10 | KEEP, REPAIRED: local collection preserves root radio lifetime, ERROR-944 |
| OPT-O19-06 + OPT-O01-05 | KEEP, REPAIRED: background warming/nullable readyPairing; lazy icon caches, ERROR-945 |
| OPT-O19-07 | KEEP, REPAIRED: GHOST/workload START/FINISH/mode guards, ERROR-943 |
| OPT-O20-03 | KEEP, REPAIRED: one worker owns output/flush/close, ERROR-946 |
| OPT-O14-06 | KEEP: bounded dispatcher operation concurrency, not all JVM threads |
| OPT-O24-08 | KEEP, REPAIRED: documented Windows API plus observable Flow, ERROR-947 |

Nine action groups cover ten audit IDs; six defects repaired. Full rationale: ADR-300..309, ERROR-943..948,
EXP-115..119 and PERF-E01..E08 in this stream's fragments. No entire finding rejected.

## Caller facts

Pre-ready chats is EmptyFlashChatRepository, not a forwarding proxy: update the whole DropTarget sender. Pairing by lazy
can initialize on UI or block on background's monitor; unconditional composition uses readyPairing instead. Stored-ON
radio starts at application root with engine.scope, above conditional window. Active window, GHOST, Transferring/
Verifying, non-ENDED call and non-Idle PTT guard minimized downscale on both workload edges and selected-mode changes.
Desktop connectionPolicy still reads selected mode, and JmDNS/multicast do not honor every Composite ECO browse knob:
mode transitions prove neither quieter packets/power nor dropped desktop sessions. Hub REST polling stays ungated.

## Verification

Forced repaired run through the machine lock:
`powershell.exe -NoProfile -File tools/flash-gradle.ps1 :desktop:jvmTest :desktop:compileKotlinJvm --rerun-tasks --no-build-cache --console=plain`

BUILD SUCCESSFUL in 6m 51s, 66/66 actionable tasks executed, exit 0. Desktop 259 tests in 46 XML suites, zero failures,
errors or skips. No code change followed this run. Real engine fixtures cover idle/restore/GHOST, transfer/call START/
FINISH; pure truth table covers PTT. Boot test checks pairing lazy lifetime; structural tests check root controller and
updated DropTarget sender. Three latch tests cover logger in-flight entry/overflow/timeout ownership. Motion tests cover
mapping/observable updates and a real read-only Windows native query, not the visual system-setting toggle.

## Evidence limits and stopping point

No paired first-frame, frame-rate, packet/power, thread/RSS or allocation measurement delivered. Geometry can recompose
within a size class. Actual BufferedWriter is 16,384 characters; FileWriter already internally buffered. Raw bounded log
queue drops newest/counts overflow; graceful close joins the sole writer, timeout never closes under it, and failures/
forced kill preclude all-lines delivery. IO limits bound operations, not total JVM threads. OS motion detection uses
SystemParametersInfoW/0x1042 and 32-bit BOOL via existing JNA; Windows only, failure unknown, five-second IO Flow preserves
last known state. Official source checked 2026-11-01 is in ADR-308; no new dependency or radio-driver change.

Next: finish/fold records, commit repairs, merge --no-ff and force both gates on merged dev. Manual AWT drop, stored-ON
radio/tray, live Windows motion and performance checks remain TODO. Preserve ERROR-941 compiler history while marking
its direct-call workaround superseded by ERROR-948. No push, deployment, phone access or Wave 5 implementation.
