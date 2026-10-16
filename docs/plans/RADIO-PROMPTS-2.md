# Radio gateway: prompts for the remaining phases (R1 to R6)

**Written:** 2026-10-16. **Status: NOT STARTED.** Nothing here has been run. Follows `docs/plans/RADIO-PROMPTS.md` (the first two streams, R-move and
R-spike, are merged into `dev` as of `b7940512`). Plan: `docs/plans/RADIO-GATEWAY-PLAN.md` (revision 3, PROPOSED).

## 0. The waves, and what you do between them

```text
Wave 1  (parallel)   R-link  : production find/pair/connect, RadioGatewayManager, reconnect, Android service question, hardware runbook (plan R1 + software of R2)
                     R-ui    : research doc + the Settings screens against a controller interface and a fake (UI-070..074)
Wave 1b (after both) R-wire  : connect the screens to the manager on Android and desktop
        --- owner: hardware runbook of R-link / R-wire on the real radio (BT-R03, BT-R04, RADCON-*), then say "merge" ---
Wave 2  (parallel)   R-chat  : companion mode, chat over the radio (plan R3). Needs your yes to O-4 (engine/planner hooks)
                     R-audio : walkie-talkie audio spike (plan R4, BT-R10). Independent: may start together with Wave 1
Wave 3  (parallel)   R-relay : base-station relay (plan R5)
                     R-radar : location reports + offline radar + finished mode selector (plan R6)
```

Per stream the cycle is the same as before: paste the prompt into an Antigravity session started in `C:\Users\KaliOxygen\Downloads\Flash`
(each prompt creates its own worktree on E: as STEP 0), the agent reports, optionally run the verifier prompt of `RADIO-PROMPTS.md` section 3
(replace the stream name; the verifier checks the scope rules written in the prompt), then say "merge" and the integrator merges, runs the gate
and folds the log fragments. Waves 2 and 3 prompts assume the earlier waves are merged; their STEP 0 checks that and stops otherwise.

## 1. Assumptions to confirm (the prompts depend on them)

| # | Assumption | If you disagree |
|---|---|---|
| 1 | ADR-182 and ADR-183 (Win32 via JNA + registry mapping on Windows; Flash's own scan/pair list on Android) are ADOPTED. They are still marked PROPOSED in `docs/decisions.md`; the BT-R11 device evidence supports both. | Say so before Wave 1; R-link would change. |
| 2 | ADR-173 as amended is approved (O-5), and the plan stays PROPOSED until you say otherwise. | Wave 1 can still run, it only builds the connection layer. |
| 3 | O-4: starting the R-chat prompt is your yes to touching `FlashTransportType`, one planner hook, one outbox port and host hooks. | Do not start R-chat. |
| 4 | O-2: a second radio exists for BT-R05, BT-R07, BT-R08, BT-R12. Without it those stay design-only / simulated and cannot be called verified. | Streams still build and simulate; the runbooks stay TODO. |
| 5 | Radio tests that transmit are run by you only, on a frequency and under an authorisation that allows the profile you test. The agents never transmit. | n/a |
| 6 | UI ids UI-070..UI-079 and the number ranges below are free (they were, on 2026-10-16). | The integrator renumbers. |

## 2. Reserved numbers

| Stream | ADR | ERROR | EXP | Other |
|---|---|---|---|---|
| R-link | 187-191 | 640-659 | 050-054 | `RADLNK-` |
| R-ui | 192-196 | 660-679 | 055-059 | UI-070..074, `RADUI-` |
| R-wire | 197-199 | 680-689 | 060 | `RADWIRE-` |
| R-chat | 200-209 | 700-729 | 065-069 | `RADCHAT-`, BT-R05, BT-R08, BT-R09 |
| R-audio | 210-214 | 730-749 | 070-074 | `RADAUD-`, BT-R10 |
| R-relay | 215-219 | 750-769 | 075-079 | `RADRLY-`, BT-R07 |
| R-radar | 220-224 | 770-789 | 080-084 | UI-075..079, `RADRDR-`, BT-R12 |


---

## 3. Prompt: Wave 1, stream R-link: production find/pair/connect layer and the gateway manager (plan R1 and the software side of R2)

Prerequisite for STEP 0: the merge commits 'merge: agent/R-move' and 'merge: agent/R-spike' (dev at or after b7940512).

```text
PARALLEL MODE. You are stream R-link, working ONLY in E:\FlashWork\R-link on branch agent/R-link. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-link, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-link is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-link -b agent/R-link dev
  (if the branch already exists: git worktree add E:\FlashWork\R-link agent/R-link)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-link\local.properties
Then cd E:\FlashWork\R-link; confirm `git branch --show-current` prints agent/R-link, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-link.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. The spike code you will promote: desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radiospike/ and
   app/src/debug/java/com/transfer/flash/debug/radiospike/, and the moved radio stack in core/radio/ (ByteLink, SerialPortCatalog,
   KissTncDriver, the connect package: RadioConnectStateMachine, RadioCandidateRanker, RememberedRadioStore, RadioAuthorisationGate).

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-link. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-link/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 187-191, ERROR 640-659, EXP 050-054, test prefix RADLNK-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-link/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-link/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commits 'merge: agent/R-move' and 'merge: agent/R-spike' (dev at or after b7940512).

ASSIGNMENT
Facts already proven on the owner's hardware (BT-R11, 2026-10-16, ADR-182/183): the VR-N76 is found by a Windows inquiry in about 5 s and
by an Android discovery scan with BLUETOOTH_SCAN + neverForLocation (no location permission); it pairs with no PIN (Secure Simple Pairing)
both through BluetoothAuthenticateDevice (Windows, Wizard mode) and BluetoothDevice.createBond (Android); a Windows COM port maps to the
radio's Bluetooth address through HKLM\SYSTEM\CurrentControlSet\Enum\BTHENUM (Device Parameters: PortName and Bluetooth_UniqueID;
all-zero address = incoming listener port to ignore). Nothing about SENDING data to the radio from the app is proven yet.

TASK 1: EXPLAIN-BACK (see working standard).

TASK 2: promote the spike into :core:radio. The spike code is isolated and removable on purpose; now it becomes the production catalogue.
a. Windows: `WindowsBluetoothApi` (interface + JNA implementation), the models, the error mapper, the registry COM-port mapper and the
   device classifier move into core/radio/src/jvmMain (package com.transfer.flash.core.radio.link.windows). The desktop `radioSpike` task
   and its CLI stay (they are the owner's diagnostic) but become thin callers of the :core:radio classes; delete the duplicate code in
   desktop/.../radiospike, keep its tests (moved with the code). Keep the jar exclusion for the CLI classes.
b. Android: the scan (timeout, stop on cancel), the bond request with the bond-state result and the bonded-device list move out of
   RadioSpikeActivity into core/radio/src/androidMain (`AndroidRadioDiscovery`), next to the existing AndroidBluetoothCatalog. The debug
   activity stays as a thin harness over it. Permissions: reuse BluetoothPermissions; BLUETOOTH_SCAN keeps neverForLocation (as in the main
   manifest); permissions are asked at the point of use, never at app start.
c. A common port `RadioDeviceDiscovery` in core/radio commonMain: `pairedDevices()`, `scan(timeoutMs): Flow<DiscoveredDevice>` (cancelling the
   flow stops the scan), `pair(device): PairResult`, `openLink(remembered): ByteLink`. Windows and Android implement it; a fake
   implements it for tests. The Windows implementation resolves the CURRENT port of a remembered Bluetooth address from the registry on
   every connect (so renumbered COM ports do not matter); the remembered port name is only the documented fallback hint of plan section 6.
   Ports whose registry address is all zeros are never offered or opened.

TASK 3: `RadioGatewayManager` (commonMain facade, plan section 3 layout).
a. Owns the RadioConnectStateMachine, a RadioDeviceDiscovery, the RememberedRadioStore, the authorisation record and one KissTncDriver.
   Exposes `StateFlow<RadioGatewayState>` (connect state, link state, the remembered radio's name, the current plain-sentence message and
   next step, scan results) and a small command set (enable, disable, startScan, stopScan, confirmCandidate, pair, connectRemembered,
   forget, setProfile, recordAuthorisation, requestTestMessage). No UI types, no Android or desktop classes.
b. Rules from plan section 6 stay mechanically enforced and tested: nothing is opened before the user confirms a candidate once; after that
   the remembered radio reconnects by itself when the switch is on; a scan runs only while a screen asked for it and stops on cancel or
   timeout; while the switch is off nothing opens, scans or polls; auto-detect preselects only when exactly one candidate matches.
c. Real persistence: `RememberedRadioStore` and the authorisation record (date included) get real implementations. Find how the project
   already stores settings (desktop: the properties files in the desktop state directory; Android: look at how existing settings are
   stored) and reuse that mechanism; atomic writes; an unreadable file means "nothing remembered", never a crash. Store the Bluetooth
   address and display name, never keys.
d. Reconnect: use the driver's existing backoff; after a limit stop retrying and show "Reconnect"; a power-cycled radio comes back by itself
   (test with the fake link: close, reopen, state follows). The manager never sends a data frame on its own; the only transmitting command
   is requestTestMessage, which requires the authorisation record.
e. Parameter frames (TXDELAY 300 ms, persistence 0x40, slot time 100 ms, TXTAIL 20 ms) are KISS COMMAND frames, not on-air data; confirm in the
   driver code that sending them does not key the transmitter and write that finding down as UNPROVEN until the owner's runbook (Task 6).

TASK 4: Android background question (AGENTS section 21). Research the CURRENT official rules for holding a Bluetooth link while the
screen is off (foreground service types, the connectedDevice type and its permissions, Android 14/15 restrictions), cite URL and date in
platform-notes-additions.md. Implement a `RadioLinkService` only if the rules and the plan justify it: started only while the gateway
switch is on AND a radio is connected, with an honest notification, stopped on disable. Keep the pure logic (when to start/stop) in
:core:radio and unit-test it. Never claim background reliability: it is owed as RADCON-06 and the Transsion freezer (see the HIB notes in
the memory index and docs) can freeze it.

TASK 5: tests (ids RADLNK-U01..). Cover: remembered-address reconnect when the COM number changed; ignored incoming-listener ports;
nothing opens before confirmation (mutation-check); scan stops on cancel and on timeout (mutation-check); switch off means no activity
(mutation-check: leave a poll running); unreadable store; pairing refused / wrong state sentences; two Bluetooth serial devices (the wrong
one is never auto-selected); the manager under a flapping fake link; permission missing on Android. Run on JVM and Android host.

TASK 6: owner runbook `logs/streams/R-link/runbook-R1-R2.md` (docs only; the owner runs it with the radio): BT-R04 (switch the radio off and on,
the link comes back by itself), RADCON-01/02 (find the radio by address while another serial port exists; renumber the COM port by
re-pairing), RADCON-07..09 (auto-detect, wrong-device safety, scan-and-pair without Bluetooth settings), RADCON-06 (screen off), and BT-R03
(pacing under many queued frames; THIS TRANSMITS: state that the owner needs a legal frequency/authorisation and Profile A or a dummy load).
Each step has a concrete Pass line and a results table like logs/streams/R-spike/runbook-BT-R11.md.

Stop-and-ask: any change to a frame format or golden vector, to pairing/trust semantics, :core:engine, the connection planner,
RealFlashChatRepository, Room, a new dependency, anything that transmits.
```

---

## 4. Prompt: Wave 1, stream R-ui: the Settings screens for the radio gateway (UI-070 and up)

Prerequisite for STEP 0: the merge commits 'merge: agent/R-move' and 'merge: agent/R-spike' (dev at or after b7940512).

```text
PARALLEL MODE. You are stream R-ui, working ONLY in E:\FlashWork\R-ui on branch agent/R-ui. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-ui, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-ui is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-ui -b agent/R-ui dev
  (if the branch already exists: git worktree add E:\FlashWork\R-ui agent/R-ui)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-ui\local.properties
Then cd E:\FlashWork\R-ui; confirm `git branch --show-current` prints agent/R-ui, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-ui.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. docs/ui/flash-premium-chat-ui-implementation.md, docs/ui/ui-research-index.md, docs/ui/component-doc-template.md,
   docs/ui/settings-page.md, docs/ui/flash-hub-tab.md (a recent worked example of research-first UI), docs/ui/design-system.md,
   docs/ui/accessibility.md, docs/ui/error-states.md, docs/ui/empty-states.md, and the code of the Settings screen
   (ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt) and the Hub copy/math pattern
   (ui/chat/.../ui/hub/FlashHubCopy.kt, FlashHubMath.kt, FlashHubNoDeadActionsTest.kt).

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-ui. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-ui/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 192-196, ERROR 660-679, EXP 055-059, UI ids UI-070..UI-074, test prefix RADUI-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-ui/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-ui/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commits 'merge: agent/R-move' and 'merge: agent/R-spike' (dev at or after b7940512).

ASSIGNMENT
You build the screens only. You do NOT touch :core:radio, the hosts' engines or any wiring (stream R-link builds the manager; a later
step wires them). You work against a controller interface you define in :ui:chat, with a fake.

AGENTS section 34 applies: research first, then DESIGNED, then implement. No generic Material 3 end state: use FlashTheme, FlashColors,
FlashTypography, FlashShapes, FlashSpacing, FlashMotion, FlashIcons; the final composer-style inputs and icon set are the Flash ones.

TASK 1: EXPLAIN-BACK.

TASK 2: research and design doc `docs/ui/radio-gateway.md` (write it into your worktree; it is a new file, you own it) following the
component template: at least three layout approaches compared for the connect flow, the decision, states (every state of the plan's
section 6 flow and every failure sentence), motion with reduce-motion, accessibility (TalkBack and keyboard on desktop, focus order,
state descriptions), tablet and desktop layouts (see docs/migration/ADAPTIVE-UI-PLAN.md before any layout work), dark mode, and an
honest "beta, for radio operators" tone. Mark it DESIGNED only when complete; the screens come after. Reserve UI-070 (Radio gateway
settings entry and overview), UI-071 (Find my radio: candidates, scan list, pair), UI-072 (security profile and authorisation sheet),
UI-073 (connection status chip/row), UI-074 (mode selector). Put the proposed rows for docs/ui/ui-research-index.md into
logs/streams/R-ui/ui-index-additions.md (do not edit the index).

TASK 3: the controller port, in ui/chat/src/commonMain/.../ui/radio/:
a. `RadioGatewayController` (interface): `val state: StateFlow<RadioGatewayUiState>` and actions (setEnabled, startScan, stopScan,
   confirmCandidate(id), pair(id), reconnect, forget, selectProfile, confirmAuthorisation, selectMode, sendTestMessage,
   openSystemBluetoothSettings, requestNearbyDevicesPermission). `RadioGatewayUiState` carries only plain Kotlin types and ready-to-show
   strings (sentence + next step); :ui:chat must not depend on :core:radio.
b. `FakeRadioGatewayController` that can play every state and failure (for tests, previews and the demo).
c. `FlashRadioCopy` (all user-facing strings in one object, like FlashHubCopy; no inline literals, including accessibility strings) and
   `FlashRadioMath` (pure view logic: ordering, which actions are enabled, which sentence shows, step progress), both tested.

TASK 4: the screens (Compose Multiplatform, shared by Android and desktop):
a. Settings entry "Radio gateway (beta)" in the existing Settings structure (smallest hunk in FlashSettingsScreen), screen with the
   on/off switch, the "Is your radio ready?" checklist (radio on; KISS TNC enabled in the radio menu: Menu, General Settings, KISS TNC,
   Enable; radio can be found), automatic candidate confirmation ("Use <name>?"), the live list of nearby devices (radios ranked first,
   Pair button, scan time limit and stop, "Open Bluetooth settings" as a fallback), connection status, the remembered radio with Forget,
   and the "Send a test message" button with its transmit warning.
b. The security profile selector and the one-time authorisation sheet of plan section 8 (exact intent: the user confirms they are authorised
   to transmit encrypted traffic on the frequency in use; until then only the plaintext profile can be chosen). Encryption is required for
   military use: make the encrypted profile the clearly presented primary option.
c. Mode selector (UI-074): show Modes 1, 2, 4 and 5 as defined in the plan, but a mode whose engine is not built yet must not be selectable
   and must not be a dead button: show it as "Coming later" without an action. Mode 3 (walkie-talkie bridge) shows only after the audio spike
   decision; hide it now. Never show a control that does nothing.
d. Permissions asked at the point of use through the controller's request action, with the one-sentence reason; the "denied" state has
   its own sentence and a settings button.
e. Both hosts: an Android entry point and a desktop entry point that use FakeRadioGatewayController behind a debug-only or demo path so the
   owner can look at every state without a radio (follow how the Hub demo is exposed; do not wire the real manager, that is the next step).

TASK 5: tests (RADUI-U01..): FlashRadioMath truth tables; a no-dead-actions test like FlashHubNoDeadActionsTest (every enabled action calls
its controller method; every disabled one is not clickable); every failure sentence and next step present and non-empty; authorisation gate
view logic (encrypted profile not selectable without the confirmation; mutation-check it); scan stops when the screen is left
(controller call asserted; mutation-check); accessibility state descriptions come from FlashRadioCopy (no inline literals: add a source
scan test as ui-hub did, if that pattern exists, else a reflection-free guard you design). Run ui:chat on JVM and Android host and compile both hosts.

TASK 6: owed device tests in backlog-additions.md: RADUI-01.. (look of each state on a phone and on the PC, TalkBack walk-through, keyboard-only
walk-through on the PC, 200% font scale, dark mode, reduce motion, narrow window).

Stop-and-ask: a new dependency, a change to Settings structure beyond one entry, anything that needs :core:radio, anything that touches
Room or engine files.
```

---

## 5. Prompt: Wave 1b, stream R-wire: connect the screens to the gateway manager on both hosts (after R-link and R-ui are merged)

Prerequisite for STEP 0: the merge commits for agent/R-link and agent/R-ui (the owner says when both are merged).

```text
PARALLEL MODE. You are stream R-wire, working ONLY in E:\FlashWork\R-wire on branch agent/R-wire. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-wire, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-wire is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-wire -b agent/R-wire dev
  (if the branch already exists: git worktree add E:\FlashWork\R-wire agent/R-wire)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-wire\local.properties
Then cd E:\FlashWork\R-wire; confirm `git branch --show-current` prints agent/R-wire, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-wire.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. logs/streams/R-link/ and logs/streams/R-ui/ (handoff, progress, runbook-R1-R2.md), docs/ui/radio-gateway.md,
   core/radio's RadioGatewayManager and RadioGatewayState, ui/chat's RadioGatewayController and RadioGatewayUiState, the host entry points
   (app MainActivity.kt and Application/engine holder; desktop DesktopMain.kt, DesktopShell.kt, DesktopEngine.kt).

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-wire. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-wire/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 197-199, ERROR 680-689, EXP 060, test prefix RADWIRE-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-wire/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-wire/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commits for agent/R-link and agent/R-ui (the owner says when both are merged).

ASSIGNMENT
Small stream, mostly glue. Do it carefully because it touches shared host files; hunks stay minimal.

TASK 1: EXPLAIN-BACK.
TASK 2: Android: `AndroidRadioGatewayController` (app module, new file) mapping RadioGatewayState to RadioGatewayUiState and the actions to
RadioGatewayManager, including the Nearby-devices permission request through an Activity result hook (smallest hunk in MainActivity or the
existing permission helper), opening system Bluetooth settings, and the manager's lifetime: created lazily when the screen or the stored
switch needs it, closed when the switch is off, never started at app launch unless the switch is on. Manifest: only what the radio needs
(permissions already exist); a foreground service entry only if R-link built RadioLinkService.
TASK 3: Desktop: `DesktopRadioGatewayController` (desktop module, new file), same mapping, the Windows discovery implementation, the
state-directory persistence; entry in the Settings screen the same way as Android; the lifetime rules as above.
TASK 4: replace the demo/fake entry from R-ui by the real controller in normal builds (keep the fake path for the demo/tests).
TASK 5: tests (RADWIRE-U01..): the controller adapters against the fake discovery and the in-memory link end to end (enable, auto-detect,
confirm, connect, link loss, reconnect, disable) on JVM; mapping tests for every failure sentence; lifetime tests (nothing created while the
switch is off; mutation-check). Compile :app:compileDebugKotlin, :desktop:compileKotlinJvm, :desktop:compileTestKotlinJvm and run the
named test classes.
TASK 6: owed device tests (backlog-additions.md): the whole flow on the owner's phone and PC with the radio (find, pair, connect, status,
disable, kill the app, reopen), 'Send a test message' (transmits; owner authorisation required), upgrade install (adb install -r only) keeps the
remembered radio.

Stop-and-ask: any change to engine/planner/repository files, a new dependency, anything that transmits.
```

---

## 6. Prompt: Wave 2, stream R-chat: companion mode, chat over the radio (plan R3). REQUIRES the owner's yes to O-4

Prerequisite for STEP 0: the merge commits of agent/R-link, agent/R-ui and agent/R-wire (Wave 1 complete).

```text
PARALLEL MODE. You are stream R-chat, working ONLY in E:\FlashWork\R-chat on branch agent/R-chat. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-chat, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-chat is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-chat -b agent/R-chat dev
  (if the branch already exists: git worktree add E:\FlashWork\R-chat agent/R-chat)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-chat\local.properties
Then cd E:\FlashWork\R-chat; confirm `git branch --show-current` prints agent/R-chat, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-chat.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. docs/protocol.md (pairing, FSEC, transports), docs/security.md, docs/network/RADIO-WIRE-FORMAT.md in full, ADR-101 and ADR-042/ADR-044
   in docs/decisions.md, the Wave 1 stream logs, and the code you will connect to: RadioSession/RadioWire/RadioCrypto in core/radio,
   FlashTransportType (core/common/.../model/FlashTransportType.kt), the connection planner, RealFlashChatRepository's outbox and
   delivery-state code (read before editing; it is 6,000+ lines: new logic goes in new files with a few-line hook), and the engine hosts.

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-chat. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-chat/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 200-209, ERROR 700-729, EXP 065-069, test prefix RADCHAT-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-chat/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-chat/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commits of agent/R-link, agent/R-ui and agent/R-wire (Wave 1 complete).

ASSIGNMENT
OWNER GATE: this stream edits shared engine files (O-4 in the plan). Starting this prompt is the owner's yes to O-4: you may add
`FlashTransportType.BLUETOOTH` (or the name your ADR justifies), one planner hook, one outbox port in :core:messaging with its adapter, and
three-line hooks in the hosts. Nothing else in those files. Everything else stays as it is.

TASK 1: EXPLAIN-BACK, including: which files you will touch in shared code and the hunk plan.

TASK 2: pairing-key adapter. Derive the radio session keys from an EXISTING Flash pairing (never a new trust path): domain-separated HKDF as
RADIO-WIRE-FORMAT.md and ADR-101 require; separate send/receive keys or direction bytes so the two stations never use the same key and
nonce space; the rotating station tag from the identity fingerprint as specified. Persist the send counter so a restart or crash can
NEVER reuse a counter with the same key: store a high-water mark ahead of use, and on restart jump past it. Test with a simulated
crash between 'counter used' and 'counter stored' (mutation-check: remove the jump, see the test fail). No key or tag value in logs.

TASK 3: radio as a transport for messages (plan section 7). The radio is used only when the recipient has no live LAN or hub path and is a
paired contact known to be radio-reachable; Wi-Fi and hub always win. Implement as an outbox port plus adapter, not as a change to
the repository's behaviour for LAN peers (a user who never enables the radio sees no change; prove it with a test). Rules:
a. Text messages only, segmented per RADIO-WIRE-FORMAT.md up to its limit; longer than the limit: refused with a sentence.
b. Photos, files, voice messages and calls: refused with one plain sentence ("Photos and files cannot be sent by radio"), not queued.
c. Delivery states: 'sent by radio, waiting' after the driver confirmed the transmit; 'delivered' ONLY when a cryptographic acknowledgement
   frame returns; retry with backoff and a hard limit, then a clear failed state. Never show delivered without the acknowledgement
   (mutation-check).
d. Inbound: authenticate and decrypt, replay window, dedup, reassemble segments with a timeout for missing segments, then hand the message
   to the normal inbound path exactly like a LAN text (reactions/receipts out of scope). Unknown tags and failed authentication are dropped
   silently and counted in a probe (no response: it would give a stranger a signal).
e. Priority order: emergency flag, text, acknowledgements, location reports (the last two classes are defined here, used by Wave 3).
f. A message to a radio-only contact when the gateway is off or disconnected: queued with the honest state 'radio not connected', never
   transmitted later without the user having switched the gateway on.

TASK 4: security profiles on the air. Profile M: only ciphertext leaves; Profile A: signed clear text with the callsign set in settings
(add the callsign field to the controller port ONLY via a note for the UI owner; you do not edit :ui:chat). The transmit path checks the
authorisation gate: Profile M refused without the recorded confirmation. Test with the fake air channel recording every transmitted byte:
under Profile M no plaintext substring of the message appears; under Profile A the callsign appears (BT-R08 logic, automated half).

TASK 5: tests (RADCHAT-U01..) with the in-memory two-station simulation (fake air channel with loss, duplication, reordering and a third
listener): round trip; loss of a segment; duplicate frames; replay of a captured frame; wrong key; counter persistence after crash;
attachment refusal; no change for LAN peers; delivered-only-on-ack; gateway off. Mutation-check at least five of the rules above. Run
:core:radio, :core:messaging and the hosts' compile on JVM and Android host.

TASK 6: owner runbook `logs/streams/R-chat/runbook-R3.md` for BT-R05 (two Flash stations over two real radios: text arrives, ack returns,
restart does not reuse a counter), BT-R08 (second receiver sees only ciphertext under M, plaintext + callsign under A; THIS TRANSMITS),
BT-R09 (photo to a radio-only contact is refused). State clearly: needs a second radio (plan O-2) and a legal frequency/authorisation.

Stop-and-ask: a change to the frame format or any golden vector (propose it as an ADR and wait), anything that changes behaviour for users
who do not enable the radio, trust/pairing semantics, Room schema changes (reserve through the owner), a new dependency.
```

---

## 7. Prompt: Wave 2, stream R-audio: walkie-talkie audio bridge spike (plan R4, BT-R10). Tools and runbook only

Prerequisite for STEP 0: the merge commits of agent/R-move and agent/R-spike (Wave 2 can start as soon as those are in dev; it does not need Wave 1).

```text
PARALLEL MODE. You are stream R-audio, working ONLY in E:\FlashWork\R-audio on branch agent/R-audio. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-audio, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-audio is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-audio -b agent/R-audio dev
  (if the branch already exists: git worktree add E:\FlashWork\R-audio agent/R-audio)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-audio\local.properties
Then cd E:\FlashWork\R-audio; confirm `git branch --show-current` prints agent/R-audio, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-audio.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. Plan section 9 and section 2 (the audio profile row), EXP-023 in logs/experiments.md (the Windows audio connection failed because no
   call was active), docs/decisions.md ADR-025/032 (calls, PTT), the PTT module core/ptt and docs/ui/ptt-session-overlay.md.

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-audio. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-audio/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 210-214, ERROR 730-749, EXP 070-074, test prefix RADAUD-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-audio/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-audio/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commits of agent/R-move and agent/R-spike (Wave 2 can start as soon as those are in dev; it does not need Wave 1).

ASSIGNMENT
This is a spike like BT-R11: build diagnostic tools and a runbook, answer four questions, recommend; you build no feature.

The four questions (plan section 9): (1) does the phone or PC get a working Bluetooth Hands-Free audio connection to the radio, and in
which role? (2) how is the transmitter keyed from Flash (radio-side voice activation, a Bluetooth command, nothing)? (3) what is the audio
quality of the narrow Bluetooth voice channel and does incoming radio audio reach the app? (4) how does analog radio traffic fit Flash's
push-to-talk turn-taking?

TASK 1: EXPLAIN-BACK.
TASK 2: research with the current official documentation and manufacturer material (URL, date, verified / reported / community-claim for
each fact; tools/tavily_search.py): the Hands-Free profile roles, Android's APIs for Bluetooth SCO / communication device routing
(AudioManager, BluetoothHeadset, setCommunicationDevice on newer Android), Windows' audio endpoint model for Hands-Free devices, and
what the VR-N76 manual says about its Bluetooth audio, PTT over Bluetooth and VOX. Everything about the radio that is not in a primary
source stays UNPROVEN.
TASK 3: tools (new files only; debug/diagnostic builds; nothing in release): Windows `radioAudioProbe` Gradle task listing audio render/
capture endpoints with their Bluetooth association READ-ONLY (use what the JDK and the already-shipped JNA allow; no new dependency) and
reporting whether a Hands-Free endpoint for the radio exists and is connectable; Android debug screen that reads (no connect) the
Bluetooth headset-profile state and the audio routing devices and, only on the owner's explicit tap, starts SCO / sets the communication
device to the radio and reports success or the error; NO microphone capture is sent anywhere, no tone is played into the radio, no PTT is
keyed. Anything that could key the transmitter (playing audio to the radio, voice activation) is a runbook step for the OWNER with a stated
legal-frequency caution, never an action of your code by default.
TASK 4: runbook `logs/streams/R-audio/runbook-BT-R10.md` with a results table for the four questions (PASS / FAIL / UNKNOWN + evidence), the
steps the owner takes on PC and phone, what to record (latency by ear, quality, whether VOX keys), and a decision template.
TASK 5: ADR drafts (PROPOSED) in decisions-additions.md: recommend build / rescope / drop for Modes 3 and 5 with the evidence level of each
claim, and the minimal design if 'build' (where the code would live: a new package in :core:radio or in :core:ptt; how the PTT floor and
the analog channel interact; half-duplex rules). Tests: pure-logic parts (endpoint classification, state mapping) with fakes (RADAUD-U01..).
Backlog: BT-R10 and RADAUD-* device tests.

Stop-and-ask: anything that plays or records audio to or from the radio by default, any pairing change, a new dependency.
```

---

## 8. Prompt: Wave 3, stream R-relay: base-station relay between Wi-Fi and radio (plan R5, mode 2)

Prerequisite for STEP 0: the merge commit of agent/R-chat (companion mode).

```text
PARALLEL MODE. You are stream R-relay, working ONLY in E:\FlashWork\R-relay on branch agent/R-relay. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-relay, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-relay is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-relay -b agent/R-relay dev
  (if the branch already exists: git worktree add E:\FlashWork\R-relay agent/R-relay)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-relay\local.properties
Then cd E:\FlashWork\R-relay; confirm `git branch --show-current` prints agent/R-relay, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-relay.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. The R-chat stream logs and runbook, core/radio's RadioSession/RadioWire/segmentation and the outbox port and adapter R-chat added,
   docs/protocol.md (how LAN messages are addressed and acknowledged), docs/security.md (what a relaying node may and may not read).

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-relay. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-relay/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 215-219, ERROR 750-769, EXP 075-079, test prefix RADRLY-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-relay/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-relay/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commit of agent/R-chat (companion mode).

ASSIGNMENT
A base station is a laptop that is on the local Wi-Fi/LAN AND connected to a radio. People in camp use normal Flash; people out of
camp are reachable only by radio. The gateway forwards between them. It forwards opaque, end-to-end protected messages: it never decrypts
them and never needs a key for either end.

TASK 1: EXPLAIN-BACK, including the threat model in your own words (a captured gateway, a loop, flooding the 1200-baud channel).
TASK 2: check RADIO-WIRE-FORMAT.md for how a hop count / TTL and a message id can travel WITHOUT being inside the authenticated payload in a
way that lets an attacker alter them harmlessly or replay forever. If the existing frame has no usable field, STOP and write an ADR
proposal for a format change (versioned, with golden vectors, backwards compatible) and wait for the owner; do not change the format
yourself. Everything below assumes a field exists.
TASK 3: `RadioRelayEngine` (commonMain, sans-IO) in :core:radio: route table (which Flash devices are reachable by radio, learned from
authenticated frames and from paired contacts with a station tag, entries expire), a dedup cache keyed by message id with a one-hour
window and a size bound, TTL/hop decrement and drop at zero, a size cap, and an airtime budget (for example a maximum share of each minute
spent forwarding, configurable, default conservative) so bulk Wi-Fi chat can never saturate the channel; priority: acknowledgements and
emergency first. Rules to enforce and mutation-check: no forwarding loop between two gateways (test with three simulated nodes and a
ring), a message is forwarded at most once per id, nothing is forwarded for a destination not in the table, nothing over the size cap
(refused to the Wi-Fi sender with a sentence), a message the gateway cannot authenticate is not forwarded.
TASK 4: the Wi-Fi side: a port in :core:messaging implemented by a small adapter that takes messages addressed to a radio-reachable device
from the normal outbox and hands them to the relay engine, and delivers received radio messages to the camp user through the normal
inbound path. Same rules as R-chat: new files, minimal host hooks, no behaviour change for a user who does not enable mode 2. Settings:
the mode is chosen in the existing mode selector; add the controller action through a note to the UI owner, do not edit :ui:chat.
TASK 5: tests (RADRLY-U01..) with the multi-node simulation (camp node A on LAN, gateway G, field node F on radio; plus a second gateway for
the loop test); mutation-check five rules; JVM and Android host; hosts compile.
TASK 6: runbook `logs/streams/R-relay/runbook-BT-R07.md`: BT-R07 (a Wi-Fi-only phone to a distant patrol through the base: arrives once, no
loop). Needs a gateway laptop with a radio, a field radio, and a legal frequency/authorisation; THIS TRANSMITS.

Stop-and-ask: a frame-format change, forwarding anything that is not end-to-end protected, a new dependency, behaviour changes for non-radio users.
```

---

## 9. Prompt: Wave 3, stream R-radar: tactical location reports and the offline radar (plan R6, mode 4) and the finished mode selector

Prerequisite for STEP 0: the merge commit of agent/R-chat (the location-report frame class) and of agent/R-ui (the screens).

```text
PARALLEL MODE. You are stream R-radar, working ONLY in E:\FlashWork\R-radar on branch agent/R-radar. You are an implementer on the Flash
project (Android + Windows desktop Kotlin Multiplatform chat/transfer app). Work alone: no subagents. Commit only to agent/R-radar, small
focused commits (AGENTS.md section 11); never to dev or main; do not merge, rebase or switch branches.

STEP 0 (first, before reading anything). You start in the integration tree C:\Users\KaliOxygen\Downloads\Flash. Do not edit files there and
do not switch its branch. Create your own worktree on drive E: and work only there:
  git worktree list                      (if E:\FlashWork\R-radar is already listed, reuse it and skip the add)
  git worktree add E:\FlashWork\R-radar -b agent/R-radar dev
  (if the branch already exists: git worktree add E:\FlashWork\R-radar agent/R-radar)
  copy C:\Users\KaliOxygen\Downloads\Flash\local.properties E:\FlashWork\R-radar\local.properties
Then cd E:\FlashWork\R-radar; confirm `git branch --show-current` prints agent/R-radar, and that `git log --oneline` contains the merge commits named under
"Prerequisite" below. If the prerequisite is missing, stop and tell the owner. Every later command runs from E:\FlashWork\R-radar.

Read completely, in this order, before anything else:
1. AGENTS.md (root), especially sections 3, 4, 12, 13, 21, 24, 27, 35 and 36.
2. docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md sections 3, 4, 7 and 8, and docs/plans/RADIO-PROMPTS.md section 0.
3. docs/plans/RADIO-GATEWAY-PLAN.md (revision 3, every section).
4. ADR-173 (with its amendment), ADR-177/178 (R-move), ADR-182/183 (R-spike) in docs/decisions.md; logs/streams/R-move/ and logs/streams/R-spike/ (handoff.md and progress.md of each; the runbook results in
   logs/streams/R-spike/runbook-BT-R11.md); docs/network/RADIO-WIRE-FORMAT.md; docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md.
5. docs/plans/HUB-QUALITY-CONTRACT.md.
6. docs/ui/radio-gateway.md and the R-ui logs (the screens and the mode selector you extend), R-chat's logs (priority classes, frame
   types), docs/ui/flash-premium-chat-ui-implementation.md, docs/ui/component-doc-template.md, docs/migration/ADAPTIVE-UI-PLAN.md, and
   the official Android documentation for location and the rotation-vector sensor (fetch now; record URL and date).

MACHINE RULES (non-negotiable)
- Gradle ONLY through `tools\flash-gradle.cmd <args>` from E:\FlashWork\R-radar. Never gradlew directly. Exit code 75 = another agent is building: do other
  work, ask again later, no polling loop. Do NOT run the whole :desktop:jvmTest (ERROR-106 hangs): use `--tests <class>` or
  :desktop:compileTestKotlinJvm. End the session with `tools\flash-gradle.cmd --stop`.
- Evidence runs must show tests EXECUTED, not UP-TO-DATE / FROM-CACHE (use --rerun-tasks for the one module when needed).
- Write logs ONLY into logs/streams/R-radar/ (progress.md, handoff.md, errors.md, experiments.md, backlog-additions.md,
  decisions-additions.md, plus platform-notes-additions.md / ui-index-additions.md when relevant), in the canonical formats, using only
  the numbers reserved for you: ADR 220-224, ERROR 770-789, EXP 080-084, UI ids UI-075..UI-079, test prefix RADRDR-. Never edit logs/*.md, docs/decisions.md, docs/testing/TEST-BACKLOG.md, AGENTS.md, the plan
  docs/plans/RADIO-GATEWAY-PLAN.md, or any other stream's files. Plan deviations go into decisions-additions.md as proposals.
- New logic goes in NEW files; shared files get the smallest possible hunk (no reformatting, no import reordering, no moving code).
  No new dependency and no new module without asking (AGENTS section 3).
- Never touch the phone, adb, the owner's running desktop app, ~/.flash, keystore.properties, any .jks, or local.properties contents.
- THE RADIO IS THE OWNER'S AND IT TRANSMITS. Never send any byte to a real serial port or Bluetooth link from a test or a tool you run.
  Never pair, unpair or remove Bluetooth devices, never change the radio's menu. Every automated test uses the in-memory link, the
  fake TNC and the fake air channel. Anything that needs the real radio becomes a numbered runbook step for the OWNER with a concrete Pass
  line, and a backlog entry (setup, steps, Pass, source, Status TODO). Never write "device-verified" about your own work.
- Honour the security and regulatory rules of plan section 8: encryption is required (Profile M), the encrypted profile is selectable
  only after the recorded authorisation confirmation, no key or frame plaintext in logs (AGENTS section 24), PROBE lines carry counts
  and short hashes only.

WORKING STANDARD
- Start with the explain-back: write half a page in your own words into logs/streams/R-radar/progress.md (what you will build, what must
  not change, the files you will touch, how you keep the rules above). No code before it exists.
- Standard of "done": docs/plans/HUB-QUALITY-CONTRACT.md (apply its spirit): tests first for pure logic and every security rule,
  mutation-check every security rule (break it, see the named test fail, restore it), both hosts where the task touches both, the
  situations the plan lists plus five you find yourself.
- Every symbol and line number in the plan may have drifted: open it and confirm before editing. Anything not proven is marked
  UNPROVEN, never written as fact. If the plan is wrong or impossible, stop and write it down; do not work around it silently.
- FINISH with an adversarial self-review (at least 10 specific findings, each path:line + failure scenario + fix, and at least 5 extra
  situations handled), then the report in logs/streams/R-radar/progress.md and handoff.md: what exists per task, the exact gate commands
  and their last lines, the shared files touched with hunk sizes, what is NOT done and why, and the owed device tests.
- If you catch yourself writing "for now", "simplified", "basic version" or "left as future work", stop: do it properly or record it as
  Not done with the reason. There is no time limit.

Prerequisite: the merge commit of agent/R-chat (the location-report frame class) and of agent/R-ui (the screens).

ASSIGNMENT
Location is sensitive: it is OFF by default, opt-in per user, visibly indicated while sharing, and never sent in clear text.

TASK 1: EXPLAIN-BACK.
TASK 2: wire definition. A location report is a small authenticated, encrypted frame of the class 'location report' (lowest priority).
Define its payload (position quantised to a documented precision, timestamp, optional status code) in a versioned section appended to
docs/network/RADIO-WIRE-FORMAT.md as a PROPOSAL in your decisions-additions.md; if it needs a new frame type in the existing codec,
that is a format extension: stop and ask the owner for it as an ADR with golden vectors. Duty cycle: default one report every 5 minutes,
user-adjustable between limits, jitter, never more than the airtime budget R-relay/R-chat defined; stationary devices send less.
TASK 3: pure logic in :core:radio (commonMain): distance and bearing (great-circle, tested with known city pairs and near-pole and
antimeridian cases), stale-position ageing (older than a threshold shows as stale, then disappears), the member list, privacy rules
(sharing off means nothing is read from the location provider; mutation-check that).
TASK 4: location source per host: Android via the platform location provider with the runtime permission asked at the point of use (its
own sentence, separate from the Bluetooth permission); the compass heading from the rotation-vector sensor only while the radar screen is
open; desktop has no GPS: the radar shows others and lets the user enter their own position manually (and states that it is manual).
Follow the repo's host pattern; new files, minimal hunks.
TASK 5: UI (AGENTS section 34 research-first): `docs/ui/radar.md` from the template with at least three approaches (north-up radar rings,
heading-up compass, list with distance and bearing), DESIGNED before implementation, UI ids UI-075.. ; build the radar screen in :ui:chat
against a controller port + fake as R-ui did (distance rings, member dots with names and age, tap for details, reduce-motion, TalkBack
reads 'Alex, 2.3 kilometres north-east, 4 minutes ago', keyboard on desktop, empty state 'No positions received yet', stale state);
offline: no map tiles, no network call. Finish the mode selector: Mode 4 and Mode 5 become selectable when their engines exist; remaining
'Coming later' entries stay non-actions. All strings in a Copy object, math pure and tested, no-dead-actions test.
TASK 6: tests (RADRDR-U01..), JVM and Android host, both hosts compile; owner runbook for BT-R12 (two stations: reports arrive, radar shows
distance and bearing within the stated tolerance against known positions, stops when sharing is switched off; THIS TRANSMITS) and device
UI checks (backlog-additions.md).

Stop-and-ask: a frame-format change, sharing location by default, a map or tile dependency, a new dependency.
```

---

## 10. After all waves

1. Verifier per stream (optional but recommended before Waves 2 and 3: R-chat touches shared engine files), then "merge" per stream; the integrator merges, runs the gate on the merged result, folds the fragments and records the owner's runbook results.
2. The plan file is updated by the integrator only (phase status, ADR numbers adopted, tests passed). Plan status moves from PROPOSED only when you say so.
3. Open points that remain regardless of the streams: Linux Bluetooth pairing (separate Linux plan), other radios than the VR-N76 family, iOS, voice notes (removed), photos/files over radio (not planned).
