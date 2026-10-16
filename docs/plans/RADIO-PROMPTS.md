# Radio gateway: prompts for two implementing AIs (Antigravity), plus a verifier

**Written:** 2026-10-16. **Status: NOT STARTED.** The owner said "do not start yet"; the plan and these prompts are committed so a worktree contains them. Nothing here has been run.
Plan: `docs/plans/RADIO-GATEWAY-PLAN.md` (revision 3, PROPOSED). Decision record: ADR-173 and its 2026-10-16 amendment.
Machine rules, branch model and log fragments follow `docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md` (still PROPOSED there; the owner's answers to its section 9 apply, and the prompts below say what to do if they were not given).

## 0. Before pasting anything

1. **The plan is committed** (2026-10-16, together with ADR-173, EXP-023 and these prompts), so a new worktree from `dev` contains `docs/plans/RADIO-GATEWAY-PLAN.md`.
2. **Each agent creates its own worktree as its first step** (STEP 0 in its prompt). The owner only starts the Antigravity session in the integration tree `C:\Users\KaliOxygen\Downloads\Flash` and pastes the prompt. Never copy `keystore.properties` or any `.jks`.
3. **Decisions the streams assume (change the prompt if you disagree):** O-3 yes (move `ReconnectPolicy` to `:core:common` if it has no network types); O-4 not yet (no change to `:core:engine`, the planner or `FlashTransportType` in these two streams); O-6 and O-7 are decided by the spike, not by the agents.
4. **Order and parallelism.** Stream R-move changes file locations in `:core:network`; stream R-spike writes only **new** files outside `:core:network`, so the two do not collide. The merge order is R-move first, then R-spike; R-spike then rebases nothing (it merges `dev` after R-move lands, per protocol section 6).
5. **The radio and the phone.** The VR-N76 is the owner's. No agent pairs, unpairs or removes any Bluetooth device, switches the radio's menu, or touches the phone. Spike steps that need those are written as a runbook for the owner (R-spike prompt, task 5).

Reserved numbers (continue the protocol's table; nothing else may be used):

| Resource | R-move | R-spike |
|---|---|---|
| ADR | 177-181 | 182-186 |
| ERROR | 600-619 | 620-639 |
| EXP | 040-044 | 045-049 |
| Test prefixes | `RADMOVE-`, `RADCONL-` (connect logic) | `BT-R11` and `RADSPK-` |
| Probe names | `radio.*` (existing ones keep their names) | `radio.spike.*` |
| Log fragments | `logs/streams/R-move/` | `logs/streams/R-spike/` |

---

## 1. Prompt for stream R-move (R0, then the platform-independent part of R1)

```text
PARALLEL MODE. You are stream R-move, working ONLY in E:\FlashWork\R-move on branch agent/R-move.

STEP 0 (do this first, before reading anything else). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit
files there and do not switch its branch. Create your own worktree on drive E: (the C: drive is nearly full) and work only there:
  git worktree list                      (if E:\FlashWork\R-move is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-move -b agent/R-move dev
  (if the branch agent/R-move already exists: git worktree add E:\FlashWork\R-move agent/R-move)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-move\local.properties
Then cd E:\FlashWork\R-move, confirm `git branch --show-current` prints agent/R-move and `git log -1 --oneline` shows a commit that contains
docs/plans/RADIO-GATEWAY-PLAN.md. If the file is missing, stop and tell the owner (the plan commit is not in dev). Every later command
runs from that directory.

Context: You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-move, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3), every section.
4. docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md (the "Implementation status 2026-10-09" block), docs/network/RADIO-WIRE-FORMAT.md,
   docs/reports/2026-10-09-bluetooth-radio.md, docs/ui/radio-link-test.md, ADR-101 and ADR-173 (with its amendment) in docs/decisions.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md for the standard of "done" (explain-back first, mutation-check every rule you add, an adversarial
   self-review). Where it talks about Hub specifics, apply the spirit.

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` run from E:\FlashWork\R-move. Never gradlew directly. Exit code 75 = another agent is
  building: do other work, ask again later; do not poll in a loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs); run
  `--tests <class>` or use :desktop:compileTestKotlinJvm. When your session ends run `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the single module when needed).
- Write logs ONLY into logs/streams/R-move/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md), in the same format as the canonical logs and with the numbers reserved for you (ADR 177-181, ERROR 600-619,
  EXP 040-044). Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md or any file of another stream.
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- Never send a KISS data frame to a real port and never open a real serial port in a test: every test uses the in-memory link and the
  fake TNC. A data frame makes the owner's radio transmit.
- No new dependency and no new module beyond :core:radio itself without asking; jSerialComm only MOVES (ADR-101 licence note travels with it).
- Do not edit RealFlashChatRepository.kt, :core:engine, the connection planner, FlashTransportType, Room schemas or the server. Not in scope.

TASK 1: EXPLAIN-BACK. Before writing code, write into logs/streams/R-move/progress.md a half-page in your own words: what moves, what
must not change, the dependency direction, the acceptance in plan section 4.4, and what you will do if ReconnectPolicy has network types.

TASK 2: R0, the move (plan section 4). One behaviour-free commit for the move itself.
a. Baseline FIRST: run the radio tests of :core:network on JVM and Android host, record the executed counts per class in
   logs/streams/R-move/progress.md. Hash any golden-vector resources.
b. Read ReconnectPolicy. If it has no network types, move it to :core:common (update its other users, keep its tests); if it does, give the
   driver its own small backoff and record the duplication as technical debt with the reason.
c. Create :core:radio (KMP: android + jvm, explicitApi(), -Xexpect-actual-classes because RadioLock is an expect class, maven-publish like
   :core:ptt). Add it to settings.gradle.kts, the jitpack.yml publish list, .github/workflows/ci.yml where modules are listed, and make
   :app and :desktop depend on it. Do not add it to :core:engine.
d. `git mv` every file in plan section 3 (code and tests) into the layout of plan section 4.1, rename the package to
   com.transfer.flash.core.radio (kiss, link, driver, wire, sim, diag sub-packages as in the plan), fix imports, keep every test unchanged
   except package/import lines. Move jSerialComm from core/network/build.gradle.kts to :core:radio jvmMain.
e. Move the desktop tasks radioLinkTest and radioLinkTestCli to the new classpath (main class name changes with the package; fix the jar
   excludes in desktop/build.gradle.kts). Update RadioLinkTestMain.kt imports.
f. Update docs that name the old path/package (mark, never delete): docs/ui/radio-link-test.md, docs/network/RADIO-WIRE-FORMAT.md,
   docs/reports/2026-10-09-bluetooth-radio.md, the status block of docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
g. Acceptance = plan section 4.4, every line, with the evidence pasted: same executed test counts as the baseline; golden vectors
   byte-identical; core/network has no radio/kiss sources and no jserialcomm; :core:radio does not depend on :core:network (or the
   exception is written down); :app:compileDebugKotlin, :desktop:compileKotlinJvm, :desktop:compileTestKotlinJvm green; the CLI
   `tools\flash-gradle.cmd :desktop:radioLinkTestCli -PradioArgs="--list"` runs and lists ports WITHOUT opening any; the jitpack.yml
   install line (see the release dry-run note) builds.
h. Mutation-check the move's one real rule: break the dependency direction (make :core:network reference :core:radio, or
   :core:radio reference :core:network) and show the build or a guard fails; restore. If there is no automatic guard, add a tiny
   Gradle/test check that fails on that direction, and record it.

TASK 3: platform-independent connect logic in :core:radio commonMain, package com.transfer.flash.core.radio.connect (plan section 6).
Pure Kotlin, no Android and no serial classes, tested with fakes; the UI and the real catalogues are NOT your job.
a. `RadioCandidateRanker`: given a list of devices (name, address or port name, kind, paired flag, signal if known) returns candidates
   ranked: known radio names first (a data-driven list class `KnownRadioNames` with VR-N76 and UV-Pro; matching is case-insensitive and
   tolerant of "(COM16)" style suffixes), then serial-capable devices, then the rest under "other". Rules from the plan: auto-detect
   preselects only when EXACTLY ONE candidate matches; it never selects an "other" device.
b. `RememberedRadio` + a `RememberedRadioStore` port (interface; in-memory fake for tests; no platform storage in this task): identity is
   the Bluetooth address, never the COM name; carries the display name and a nullable last-known port name for the Windows fallback.
c. `RadioConnectStateMachine`: states Off, ChecklistShown, Detecting, ConfirmCandidate, ChooseFromList, Scanning, Pairing, Connecting,
   Connected, Failed(reason). Events from the plan section 6 flow. Failure reasons are an enum with ONE plain English sentence each
   (no adapter, Bluetooth off, permission missing, radio not found, pairing refused, wrong PIN, port in use, KISS TNC probably not
   enabled / no data, link lost) and a next step. The machine never emits "send data" as a liveness probe (plan rule 4); a test asserts
   the machine's command set contains no transmit command except an explicit UserRequestedTestMessage that requires the
   authorisation flag.
d. `RadioAuthorisationGate`: pure rule for plan section 8: encrypted profile selectable only after a recorded confirmation (with date);
   without it only the plaintext profile is selectable. Tests for both and for the date being required.
e. Tests with the ids RADCONL-U01.. (ranking, auto-detect exactly-one, never-open-before-confirm, address-not-port identity, every failure
   sentence present and non-empty, no transmit command, authorisation gate). Mutation-check three of them (for example let auto-detect pick
   when two match; let the machine open a link before confirmation; let the gate allow encryption without the date).

FINISH
- Adversarial self-review: at least 10 specific findings (path:line, failure scenario, fix) and 5 extra situations handled.
- Write the report into logs/streams/R-move/progress.md and handoff.md: what you did per task, the gate commands you ran with the last
  lines, the shared files you touched with hunk sizes, what is NOT done and why, and the device tests owed (put them into
  logs/streams/R-move/backlog-additions.md as RADMOVE-01..03 and RADCONL-* with setup, steps, a concrete Pass, source, Status TODO).
- Never write "device-verified". No code has run against a radio in this stream.
- Stop-and-ask list: anything touching pairing/trust semantics, a new dependency, a change to the frame format or a golden vector,
  deleting data, anything not in this prompt. If the plan is wrong or impossible, stop and write it down; do not work around it.
There is no time limit. If you catch yourself writing "for now", "simplified" or "left as future work", stop: do it or record it as Not done.
```

---

## 2. Prompt for stream R-spike (R1a, the BT-R11 tooling and runbook)

```text
PARALLEL MODE. You are stream R-spike, working ONLY in E:\FlashWork\R-spike on branch agent/R-spike.

STEP 0 (do this first, before reading anything else). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit
files there and do not switch its branch. Create your own worktree on drive E: (the C: drive is nearly full) and work only there:
  git worktree list                      (if E:\FlashWork\R-spike is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-spike -b agent/R-spike dev
  (if the branch agent/R-spike already exists: git worktree add E:\FlashWork\R-spike agent/R-spike)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-spike\local.properties
Then cd E:\FlashWork\R-spike, confirm `git branch --show-current` prints agent/R-spike and `git log -1 --oneline` shows a commit that contains
docs/plans/RADIO-GATEWAY-PLAN.md. If the file is missing, stop and tell the owner (the plan commit is not in dev). Every later command
runs from that directory.

Context: You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-spike, small
focused commits; never to dev or main; do not merge, rebase or switch branches.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3), every section, above all section 6 (Find, pair and connect), section 9 and the BT-R11 row
   of the phases and tests tables.
4. logs/experiments.md EXP-023, docs/ui/radio-link-test.md, docs/reports/2026-10-09-bluetooth-radio.md, tools/vr_n76_kiss_test.py.
5. The current OFFICIAL documentation, fetched now (AGENTS section 13; record URL, date and verified/reported/community-claim for every
   fact): developer.android.com Bluetooth permissions, "Find Bluetooth devices", BluetoothDevice.createBond, Companion Device Manager;
   learn.microsoft.com Win32 Bluetooth functions (BluetoothFindFirstDevice/Next, BluetoothAuthenticateDeviceEx,
   BluetoothSetServiceState, BluetoothGetDeviceInfo, BluetoothEnumerateInstalledServices). Search helper: python tools/tavily_search.py.

MACHINE AND SAFETY RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-spike. Never gradlew directly. Exit code 75 = wait, do other
  work. Do NOT run the whole :desktop:jvmTest (ERROR-106); use `--tests`. End the session with `tools\flash-gradle.cmd --stop`.
- Logs ONLY into logs/streams/R-spike/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md) with your reserved numbers (ADR 182-186, ERROR 620-639, EXP 045-049). Never edit the canonical logs, docs/decisions.md,
  TEST-BACKLOG.md, AGENTS.md, or any file of stream R-move.
- NEVER send a KISS data frame, any byte, to a real port. NEVER pair, unpair, remove or re-pair a Bluetooth device, change the radio's
  menu, or start a scan with the intent to pair, on this PC. The only allowed live actions on this PC are READ-ONLY: listing serial ports,
  listing paired/remembered Bluetooth devices and their services. Anything else is a runbook step for the OWNER.
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, .jks, local.properties contents.
- Do not edit anything under core/network (stream R-move is moving it). Do not add a module. Dependencies: JNA is already in
  gradle/libs.versions.toml (ADR-035) and may be used by the desktop module; any other new dependency needs asking.
- Spike code is isolated and removable. Windows: new files under desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/ and
  desktop/src/jvmTest/... Android: new files under app/src/debug/ only (a debug-only screen or activity with its own manifest entry in
  app/src/debug/AndroidManifest.xml) so nothing ships in a release build; it must not depend on core/network's radio classes (they are
  moving): use android.bluetooth directly. A later step (not yours) moves what works into :core:radio.

TASK 1: EXPLAIN-BACK in logs/streams/R-spike/progress.md: the five questions of BT-R11 in your own words, what each tool will and will not
do, and how you keep the spike read-only.

TASK 2: Windows tool (desktop radiospike package), run through a new Gradle task registered next to radioLinkTest in desktop/build.gradle.kts
(`radioSpike`, JavaExec on the desktop classpath, no effect on the shipped app, jar excludes updated like the radioLinkTest ones).
a. READ-ONLY mode (default, safe to run by you): list serial ports (jSerialComm) with description, and list Bluetooth devices already
   known to Windows with name, address, paired/connected/remembered flags and installed services, through the Win32 Bluetooth API via JNA
   (bluetoothapis.h). Map each Bluetooth COM port to a device address (BT-R11 question 5): try the documented route (device enumeration +
   installed services / registry device enumeration) and report which method worked, or "not found" honestly.
b. ACTIVE mode (inquiry scan, pair, enable the serial service) is implemented but disabled unless the command line has
   `--i-am-the-owner-and-the-radio-is-on`, and still prints exactly what it is about to do and waits for Enter on each step. You do NOT run
   ACTIVE mode. Unit-test it only through a fake of the JNA layer (an interface you define, with a fake implementation).
c. Every Win32 call is wrapped in an interface `WindowsBluetoothApi` so the logic is testable without Bluetooth; tests cover: address-to-port
   mapping with several ports, inquiry results ranking (radios first), error codes mapped to the plan's plain sentences, ACTIVE mode refusing
   to run without the flag, and READ-ONLY mode proving it calls no authenticate/service-state function (the fake records calls).
d. Output a machine-readable report (JSON) and a human summary, with Bluetooth addresses truncated to the last 3 bytes in anything saved.

TASK 3: Android debug-only spike (app/src/debug).
a. A small screen "Radio spike" with: permissions request at the moment of use (BLUETOOTH_CONNECT/BLUETOOTH_SCAN on Android 12+, with
   neverForLocation as in the main manifest; location only on Android 11 and lower), list of bonded devices with their service UUIDs,
   a discovery scan with a time limit that stops on leaving the screen, a Pair button using createBond with a bond-state receiver, and
   a plain-language result line for each step. NO connect/RFCOMM write, NO data sent. (RFCOMM connect stays in the later R1.)
b. A pure-Kotlin classifier in the app module (testable on the JVM) that ranks discovered devices like the plan says; unit tests.
c. Compile with :app:compileDebugKotlin and run its unit tests (:app:testDebugUnitTest --tests ...). Do NOT install or run it; that is the
   owner's device session. Also confirm the release variant does not contain the spike (show the evidence: compileReleaseKotlin source sets
   or a unit check that the class is only in the debug source set).
d. Companion Device Manager comparison: write (do not build a UI) a short, sourced note of what it offers and what it needs, for O-7.

TASK 4: Document the facts. In logs/streams/R-spike/decisions-additions.md propose (do not decide) answers for O-6 and O-7 as ADR drafts
numbered from 182, with the evidence level of each fact (verified from official docs / reported / community claim / UNPROVEN until the owner
runs the runbook). Put the permission and API facts in logs/streams/R-spike/platform-notes-additions.md in the format of
docs/android-platform-notes.md.

TASK 5: The owner runbook, docs-only, in logs/streams/R-spike/runbook-BT-R11.md: numbered steps the OWNER performs with the radio on, on
the PC and on the phone (including how to put the VR-N76 into a discoverable state if its manual says so, with the page cited, otherwise
"unknown: try X, note what happened"), what to run (`radioSpike` read-only first, then ACTIVE with the flag), what to photograph or copy
into the answer table, and a results table with one row per BT-R11 question (1..5) plus the PIN the radio asks for. The table has "PASS /
FAIL / UNKNOWN" and an "evidence" column. Also add BT-R11 and RADSPK-* device tests to logs/streams/R-spike/backlog-additions.md in the
backlog format (setup, steps, Pass, source, Status TODO).

FINISH
- Adversarial self-review: at least 10 specific findings (path:line, failure scenario, fix) and 5 extra situations handled.
- Report in logs/streams/R-spike/progress.md and handoff.md: per task what exists, the gate commands run with last lines, the shared files
  touched with hunk sizes, what is NOT done and why. Never write "device-verified" or "works with the radio": nothing here has talked to
  the radio. Facts about the radio stay UNPROVEN until the owner fills the runbook.
- Stop-and-ask list: anything that would transmit, pair or change OS Bluetooth state; a new dependency; touching core/network; deleting
  data; anything not in this prompt.
There is no time limit. If you catch yourself writing "for now", "simplified" or "left as future work", stop: do it or record it as Not done.
```

---

## 3. Verifier prompt (one per stream, after the stream reports; read-only)

```text
You are an independent verifier for the Flash project. Stream <R-move|R-spike> claims it finished its prompt in
docs/plans/RADIO-PROMPTS.md. Assume the report is optimistic until proven otherwise. Do not fix anything; produce a verdict. No subagents.
Work in the stream's worktree (E:\FlashWork\<stream>), build only through `tools\flash-gradle.cmd`, write only
logs/streams/V-verifier/report-radio-<stream>.md, no commit, no push. Never touch the phone, the radio, Bluetooth pairing state, or any port.

Read: AGENTS.md, docs/plans/RADIO-GATEWAY-PLAN.md, docs/plans/RADIO-PROMPTS.md (the stream's prompt), docs/plans/HUB-QUALITY-CONTRACT.md,
and the stream's logs/streams/<stream>/ fragments.

Steps (write the result of each):
1. Scope: `git diff dev...HEAD --stat`. Flag changes outside the stream's allowed files (for R-move: RealFlashChatRepository, :core:engine,
   the planner, Room schemas, the server; for R-spike: anything under core/network or core/radio, a new module, a new dependency).
2. R-move: re-run the baseline comparison yourself. Recount the radio tests in :core:radio and compare with the baseline in the fragment;
   different numbers = finding. Confirm golden vectors are byte-identical to dev (`git diff dev -- <resource paths>` shows only renames).
   `git log --follow` on three moved files shows history kept. core/network has no radio/kiss sources and no jserialcomm reference;
   :core:radio does not depend on :core:network. Run the jitpack.yml install line. Run `radioLinkTestCli -PradioArgs="--list"` and confirm it opened no port.
3. R-move task 3: spot-check five claims; mutation-check two rules (exactly-one auto-detect, authorisation date) and restore only what you changed;
   confirm no command that transmits exists in the state machine other than the authorised user request.
4. R-spike: confirm READ-ONLY mode never calls authenticate/service-state (read the fake-recorded test and mutate it: add a call, see the test fail);
   confirm ACTIVE mode refuses without the flag; confirm the Android spike is only in app/src/debug and the release variant lacks it; confirm no
   RFCOMM write or data is sent anywhere; confirm Bluetooth addresses are truncated in saved output.
5. Lazy-pattern scan on the diff (TODO, FIXME, HACK, "for now", NotImplemented, @Ignore, @Disabled, empty catch). Every hit is a finding.
6. Facts: each platform fact in the notes has a URL, date and an evidence level; anything about the radio is UNPROVEN, not stated as fact.
7. Docs: backlog-additions use the real format (setup, steps, Pass, source, Status TODO); numbers used are only the reserved ones.
8. Honesty: no "device-verified" anywhere; "Not done" is not suspiciously empty.
Verdict per stream: ACCEPT / ACCEPT WITH FIXES / REJECT, with a ranked defect list (Blocking, Major, Minor) each with path:line,
failure scenario and required fix.
```

---

## 4. After the two streams (not a prompt)

1. Verifier per stream, then the integrator merges `agent/R-move` into `dev` first, runs the gate (protocol section 6) and folds the fragments into the canonical logs; then `agent/R-spike`.
2. The owner runs the runbook (`logs/streams/R-spike/runbook-BT-R11.md`) with the radio. Its answers decide O-6 and O-7 and finish `BT-R11`.
3. Then the plan's R1 (real catalogues + screens in `:ui:chat`/hosts), R2 (driver on hardware) and R3 (companion mode, needs owner decision O-4) are written as the next prompts. Not before.
