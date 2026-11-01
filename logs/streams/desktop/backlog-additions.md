# Stream E (Desktop) — Test Backlog Additions

Every test in this file must be executed on a host test machine or during a desktop profiling session (AGENTS.md §35).
The integrator folds these into `docs/testing/TEST-BACKLOG.md`.

Integration review: 2026-11-01. Corrected procedures supersede the delivered absolute promises. Automated host assertions
passed; all manual statuses remain TODO. Record date, commit, OS/hardware/settings and evidence. Use isolated developer data,
not destructive changes to the owner's install. Measurements go last. Supported Windows checks do not establish macOS support.

> **Threshold discipline.** In accordance with AGENTS.md §23 and ERROR-938, pass criteria are based on
> observable state, relative comparisons, or invariants rather than brittle wall-clock assertions on variable CI hardware.

---

## PERF-E01 — Paired desktop startup, crypto and icon profiling (OPT-O19-06, OPT-O01-05)
- **Setup:** supported Windows desktop, same isolated saved state/settings/JDK on base `baf77753` and repaired tree. Profiler records process start, first Compose frame and readiness separately; repeat cold/warm runs. Do not clear the owner's install.
- **Steps:** inspect EC generation, vault/DPAPI reads, pairing and icon rasterization on EDT/Compose/IO threads. Repeat with isolated fresh and paired state; inspect common icons at available DPI settings. Record distributions, not one fastest launch.
- **Pass:** unconditional composition does not initialize/wait on pre-ready pairing; background assembly warms before readiness. Common icon requests do not eagerly rasterize large sizes. Pairing/discovery still work when ready. Report measured latency/allocations, with no immediate-first-frame or integrated high-DPI guarantee.
- **Superseded delivered requirement:** "first frame immediately" and blanket Windows/macOS/Linux support were not established. Linux needs a separate supported-host result; no macOS OS integration is delivered here.
- **Source:** ADR-304, ADR-309, ERROR-945, EXP-115; `DesktopEngineBootTest`, `DesktopTaskbarBadgeManagerTest`.
- **Status:** TODO

---

## PERF-E02 — Resize, navigation, collection and prompt behavior (OPT-O19-01, OPT-O19-03, OPT-O03-10)
- **Setup:** real desktop Compose/AWT window, recomposition tooling and a peer that can offer a file. Record OS/UI scale and pixel-to-dp conversion; same base/repaired workload for performance comparisons.
- **Steps:** resize within/across 600/840 dp classes, switch Chats/Transfers/Settings/Hub, update radio/hub telemetry and send an offer/retryable failure while progress changes. Inspect taskbar icons at available DPI.
- **Pass:** geometry follows width while class changes only at its thresholds; navigation does not reinstall window-owned DropTarget. Settings-only collection stays local without breaking operational owners. Prompt/notification actions update when relevant fields change, not byte/speed churn. Record class-consumer and full-geometry recompositions separately.
- **Superseded delivered requirement:** zero root recompositions on resize/telemetry was false; geometry and non-Settings consumers remain observable. Distinctness is not timed debounce.
- **Source:** ADR-300..303, ADR-309, EXP-116; `DesktopNotificationManagerTest`, `DesktopTransferToastModelTest`.
- **Status:** TODO

---

## PERF-E03 — Hidden-window GHOST, workload START/FINISH and liveness (OPT-O19-07)
- **Setup:** desktop plus paired peer on real LAN, discovery packet capture/logs and optional CPU/power profiling. Modes STANDARD/BOOST/ECO/GHOST; real transfers/calls/PTT available.
- **Steps:** hide idle STANDARD then restore; while already hidden start/finish transfer, verification, call and PTT separately. Select GHOST hidden; hide/restore when already GHOST. Repeat with hub relay and observe REST presence as another hub peer comes/goes.
- **Pass:** idle non-GHOST effectively downscales ECO; workload START restores/preserves selected mode and FINISH reevaluates. GHOST stays GHOST with no new advertising. Restore returns selected mode; transfers/calls/PTT stay live; hub presence stays ungated. Record packets/sessions/CPU/power separately: connectionPolicy reads selected mode and not all ECO knobs have desktop consumers.
- **Superseded delivered requirement:** mode transitions alone prove neither fewer packets nor power savings nor dropped sessions; completion-only tests missed START while hidden.
- **Source:** ADR-305, ADR-298, ERROR-943, EXP-117; `DesktopDiscoveryDownscaleTest`, activity-helper truth table.
- **Status:** TODO (automated transitions PASS; physical packet/PTT/liveness not run)

---

## PERF-E04 — Real-process logger admission, overflow and shutdown (OPT-O20-03)
- **Setup:** isolated developer desktop process with production sink plus controlled slow Writer/console fixture. Preserve owner's desktop.log; paired base/repaired workload for latency comparisons.
- **Steps:** identifiable records/throwables from eight producers, normal throughput, overflow and blocked output. Graceful exit after a tail record; separately time out close then release writer. Capture admission latency, accepted/dropped counts, order and write/flush/close owner.
- **Pass:** producers never write disk/console; one worker owns output/close. Non-overflow graceful exit drains accepted records in queue order; overflow drops newest/counts it. Timed-out close neither closes under worker nor adds a writer. Actual buffer is 16,384 characters; failures/forced kill remain best-effort, no immediate/all-lines guarantee.
- **Superseded delivered requirement:** all 10,000 lines despite overflow, 8 KB writer, whole baseline unbuffered and another shutdown flusher were inaccurate. Old FileWriter already buffered internally.
- **Source:** ADR-306, ERROR-946, EXP-118; `DesktopFileLogSinkTest` (three latch tests PASS).
- **Status:** TODO

---

## PERF-E05 — Dispatcher concurrency and whole-process thread inventory (OPT-O14-06)
- **Setup:** same desktop/JDK/workloads base `baf77753` and repaired; JMX/thread dumps/RSS, stack settings, IO property and native/network pools recorded. Repeat with user-supplied IO property.
- **Steps:** inventory idle and transfer/call/radio threads. Separately instrument active operations on engine's limited IO view; compare measured concurrency/thread/memory changes, not estimates.
- **Pass:** default IO property 16 only if unset; limited view admits <=8 concurrent operations in that view. User property preserved and workloads complete. Total JVM threads are separate; no cap of 20 or fixed per-thread-memory promise. Investigate growth/leaks rather than expected other pools.
- **Superseded delivered requirement:** 28–34 to under-20 and ~1 MB per thread were unmeasured estimates.
- **Source:** ADR-307, EXP-119.
- **Status:** TODO

---

## PERF-E06 — Actual AWT drop uses current repository/destination
- **Setup:** real desktop window, two paired peers and group, small non-sensitive files, pre-ready interval and send/message logs.
- **Steps:** navigate A/B/group/non-chat tabs, drop into current conversation, repeat just after readiness and minimize/restore; attempt pre-ready/non-chat drop.
- **Pass:** allowed drop inserts/sends to current peer/group, never pre-boot EmptyFlashChatRepository or prior closure. Pre-ready/non-chat drop cannot send silently to obsolete target; DropTargets do not accumulate.
- **Source:** ADR-300, ERROR-941 (superseded workaround), ERROR-948; `DesktopCompositionOwnershipTest`.
- **Status:** TODO

## PERF-E07 — Stored-ON radio survives tabs/window/tray lifetime
- **Setup:** supported desktop/radio hardware in explicitly authorized session; stored master ON in isolated settings, manager/link logs; driver unchanged.
- **Steps:** launch without Settings, observe initialization; switch tabs, open/close Settings, minimize/tray/restore. Deliberately toggle OFF/ON and exit.
- **Pass:** stored ON starts from root without Settings; tab/window lifetime never creates/disables/disposes controller. OFF releases, ON publishes one manager; existing radio messaging/voice behavior preserved.
- **Source:** ADR-303, ERROR-944; composition ownership and radio controller tests.
- **Status:** TODO

## PERF-E08 — Live Windows reduced motion reaches window/toast
- **Setup:** real Windows, Flash reduced motion OFF, visible main window/toast. Use system Animation effects switch manually.
- **Steps:** toggle OS animation OFF/ON without resizing/tab change, repeat hidden/restore; enable Flash reduced motion and repeat. Separately use failed-query fixture; record five-second poll/update latency.
- **Pass:** successful BOOL zero disables motion; window/toast observe changes during polling/update cycle without unrelated event; Flash setting wins by OR. Later unknown preserves learned state; initial unknown safely falls back. No macOS/Linux OS detection inferred.
- **Source:** ADR-308, ERROR-947; motion tests; official SystemParametersInfoW checked 2026-11-01.
- **Status:** TODO (mapping/Flow/read-only Windows query PASS; visual toggle not run)
