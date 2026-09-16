# AGENTS.md — Android LAN + Wi‑Fi Direct Transfer App

## 1. Purpose of This File

This file is the persistent operating manual and handoff document for AI coding agents working on this project.

The project owner will intentionally switch between different AI coding assistants and different chat sessions. Therefore, **no AI should assume that conversation history is available**.

An AI entering this repository must be able to understand the project by reading this file and the project documentation/logs alone.

### Core rule
**Never rely on chat memory for project state. Record important decisions, progress, errors, fixes, experiments, and unfinished work in the repository.**

The goal is that a new AI can start a fresh chat, inspect the repository, read `AGENTS.md`, `docs/`, and the progress logs, and continue work without repeating previous mistakes.

---

# 2. Project Goal

Build a reliable Android file-transfer application using:

- LAN networking as the primary transport when appropriate.
- Wi‑Fi Direct as an alternative peer-to-peer transport.
- A shared transfer engine independent of the discovery/transport mechanism.
- Reliable large-file transfers.
- Pause/resume.
- Transfer recovery after interruption.
- Progress, speed, ETA, and transfer history.
- File/folder selection.
- Device discovery and pairing.
- Encrypted communication.
- Integrity verification.
- Background transfer support compatible with modern Android restrictions.

The long-term goal is to create a robust transfer protocol/core that can potentially interoperate with a future Windows/Linux client and, where useful, a Rust implementation.

See the main project specification:

`android-lan-wifi-direct-transfer-app-plan.md`

---

# 3. Primary Development Environment

The Android client is developed in **Android Studio**.

Preferred baseline stack:

- Kotlin
- Jetpack Compose
- Android SDK / modern Android APIs
- Room
- Kotlin Coroutines
- Android NSD for LAN service discovery
- `WifiP2pManager` / Wi‑Fi Direct APIs
- TCP initially for the transfer transport
- TLS for encrypted communication
- BLAKE3 or another explicitly documented cryptographic hash for integrity verification
- Foreground Service where background transfer requirements justify it
- Storage Access Framework for user-selected files/directories

Do not introduce extra frameworks or libraries merely because they are fashionable. Every dependency should have a reason and should be documented.

---

# 4. Mandatory First-Run Procedure for Every AI

Before changing code, the AI **must**:

1. Read this `AGENTS.md` completely or at least all sections relevant to the task.
2. Read the main project plan.
3. Inspect the current Git branch and working tree.
4. Inspect recent commits.
5. Read the latest progress log.
6. Read the latest decision log if the task involves architecture or technology choices.
7. Read recent error/fix entries if the task touches an area with known problems.
8. Inspect the actual current implementation before proposing changes.
9. Check current Android documentation for APIs that may have changed since earlier project work.
10. Avoid repeating an approach documented as failed unless there is a clear reason to retest it.

**For premium chat UI work**, also read:

- `docs/ui/flash-premium-chat-ui-implementation.md`
- `docs/ui/ui-research-index.md`
- The specific component doc for the UI-ID being worked on (must be at least **DESIGNED** before implementation)

### Never do this

- Do not assume a file exists because an earlier AI said it existed.
- Do not assume an API works because an older tutorial says it works.
- Do not assume the project builds because a previous AI claimed it built.
- Do not overwrite a working implementation without understanding why it exists.
- Do not erase logs merely to make the repository cleaner.

---

# 5. Repository Documentation Structure

Maintain a structure similar to:

```text
docs/
├── architecture.md
├── protocol.md
├── android-platform-notes.md
├── security.md
├── testing.md
├── performance.md
├── known-issues.md
├── decisions.md
├── troubleshooting.md
└── ui/                          # Premium chat UI — research-first (see §34)
    ├── flash-premium-chat-ui-implementation.md
    ├── ui-research-index.md
    ├── component-doc-template.md
    └── <component>.md           # One research doc per major component

logs/
├── progress.md
├── errors.md
├── experiments.md
└── handoff.md
```

Create missing files when needed.

Do not create unnecessary documentation for trivial changes, but **important work must be recorded**.

---

# 6. Progress Logging — REQUIRED

After meaningful work, update:

`logs/progress.md`

Every entry should answer:

- What was worked on?
- What changed?
- Why was it changed?
- What was verified?
- What remains unfinished?
- What should the next AI do?

Use entries like:

```markdown
## 2026-08-18 — LAN Discovery MVP

### Worked on
Implemented Android NSD service registration and discovery.

### Changed
- Added `LanDiscovery.kt`.
- Added device model used by discovery UI.
- Added lifecycle-safe registration/unregistration.

### Verification
- Tested on two Android phones on the same Wi-Fi network.
- Device A discovered Device B in approximately 2 seconds.
- Repeated discovery after leaving/rejoining the network.

### Problems
- Discovery initially failed after network changes because the NSD listener was not recreated.

### Fix
Recreated discovery state when the network became unavailable and restarted discovery.

### Remaining
- Add pairing.
- Connect to discovered service.

### Next AI
Implement the LAN connection handshake without changing the existing discovery API.
```

### Important
If the AI only made a small change, still record it when it materially affects behavior, architecture, debugging, or future work.

---

# 7. Error and Fix Logging — REQUIRED

Every significant error must be recorded in:

`logs/errors.md`

Do not merely write "fixed Gradle error". Record enough information that another AI can diagnose the same problem quickly.

Use this format:

```markdown
## ERROR-001 — Example: Wi-Fi Direct permission failure

### Date
2026-08-18

### Area
Wi-Fi Direct / Android permissions

### Symptoms
`SecurityException` occurred when attempting peer discovery.

### Environment
- Android version:
- Device:
- Android Studio version:
- Compile SDK:
- Target SDK:

### Error
```text
PASTE_RELEVANT_ERROR_HERE
```

### Root cause
Explain the actual cause, not just the visible exception.

### Failed attempts
1. Attempt A — why it failed.
2. Attempt B — why it failed.

### Working fix
Explain exactly what changed.

### Verification
Explain how the fix was tested.

### Related files
- `app/src/...`
- `docs/...`

### Status
RESOLVED
```

If an error remains unresolved, set:

`Status: OPEN`

Do not mark a problem resolved until it has been tested.

---

# 8. Decision Log — REQUIRED FOR ARCHITECTURAL CHANGES

Record important decisions in:

`docs/decisions.md`

Use Architecture Decision Record style when useful.

Example:

```markdown
## ADR-003 — TCP as the initial transfer transport

### Decision
Use TCP for the first production-capable transfer engine.

### Context
The application needs a reliable transport that can operate over both LAN and Wi‑Fi Direct IP connectivity.

### Alternatives considered
- UDP + custom reliability
- QUIC
- HTTP range-based transfer

### Why TCP was selected
- Mature Android support.
- Reliable ordered delivery.
- Works over both intended network paths.
- Easier to debug during MVP development.

### Revisit when
Reconsider after throughput, latency, CPU, and connection-management benchmarks are available.
```

Do not silently change major architecture. Document the change first or immediately after the change.

---

# 9. Experiment Logging

Networking performance is highly device-dependent. Therefore experiments must be recorded in:

`logs/experiments.md`

Record:

- Phones tested.
- Android versions.
- Wi-Fi chipset/device where known.
- Router/access point.
- Frequency band.
- Channel width where known.
- Connection type.
- File size.
- File type.
- Transfer duration.
- Average speed.
- Peak speed.
- CPU usage if measured.
- Battery/thermal observations.
- Number of parallel streams.
- Chunk size.
- Any anomalies.

Example:

```markdown
## EXP-004 — LAN vs Wi‑Fi Direct throughput

### Devices
Phone A: ...
Phone B: ...

### LAN
Average: 82 MB/s
Peak: 94 MB/s

### Wi‑Fi Direct
Average: 61 MB/s
Peak: 69 MB/s

### Conclusion
LAN was faster on this hardware/configuration. No universal preference should be hard-coded.
```

Never turn one device's benchmark into a universal assumption.

---

# 10. Handoff Document

Maintain:

`logs/handoff.md`

This is the **fastest file for a new AI to read after `AGENTS.md`**.

It should always describe the current state of the project.

Use this structure:

```markdown
# Current Handoff

## Current branch
`dev`

## Last verified build
`<commit hash>`

## Current phase
Phase 2 — Transfer Engine

## Working features
- LAN discovery
- LAN connection
- Basic single-file transfer

## In progress
- Resume support

## Broken
- Wi‑Fi Direct reconnect after group owner change

## Last change
Brief description.

## Last test
Exact test performed and result.

## Known blockers
List only real blockers.

## Recommended next task
One concrete next action.

## Files most relevant to next task
- ...
- ...
```

**Update this file at the end of major work sessions.**

---

# 11. Git Rules

Git history is part of the project's memory.

### Branches

Preferred structure:

```text
main      = stable / known-good
   │
   └── dev = active development
```

For risky features, use a short-lived feature branch when appropriate:

```text
feature/wifi-direct
feature/resume-transfer
feature/pairing
feature/performance
```

### Commit rules

Make focused commits.

Good:

```text
feat(lan): add NSD peer discovery
fix(wifi-direct): handle group removal callback
feat(transfer): add resumable chunk state
perf(transfer): benchmark 8 MB chunk size
```

Avoid:

```text
update stuff
changes
final
working now
```

### Before committing

Run the relevant build/tests.

Do not commit known broken code unless the commit explicitly records experimental/broken status and that is intentional.

---

# 12. Build Verification Rules

Before declaring work complete:

1. Build the relevant Android module.
2. Run unit tests affected by the change.
3. Run lint/static analysis where practical.
4. Install/run on an actual Android device for network features.
5. Verify logs for warnings/errors.
6. Record the result in `logs/progress.md`.

For LAN and Wi‑Fi Direct networking, emulator-only testing is insufficient for final verification.

---

# 13. Android Platform Verification Rule

Android networking APIs and restrictions change over time.

When working with:

- Wi‑Fi Direct
- Wi‑Fi/network permissions
- NSD
- foreground services
- background execution
- notifications
- storage permissions
- scoped storage
- TLS/network security

verify the behavior against current **official Android Developers documentation** when the answer could have changed.

Do not blindly trust an old Stack Overflow answer, blog post, or tutorial.

Record important platform discoveries in:

`docs/android-platform-notes.md`

Include:

- Android version.
- API level.
- API/permission involved.
- Official documentation source.
- Actual project implication.

For **desktop / Compose Multiplatform** platform facts, record them in the plan or doc that depends on
them together with the URL and the date checked — `docs/migration/ADAPTIVE-UI-PLAN.md` §1.7 is the
worked example (including the rule that a source must be marked *verified*, *reported* or *community
claim*).

**Search helper (developer tool, not product code):**

```bash
python tools/tavily_search.py "<query>"
python tools/tavily_search.py --domains=kotlinlang.org,developer.android.com "<query>"
```

Key resolution: `TAVILY_API_KEY`, `--key`, or the git-ignored `tools/.tavily_api_key`. Never commit the
key, and never make product code depend on this tool. A search result is context, not evidence about
this app — measure when the two can disagree.

---

# 14. LAN Discovery Rules

Use Android's Network Service Discovery (NSD/mDNS) for the LAN discovery layer unless a documented reason requires another mechanism.

Discovery should not be tightly coupled to the transfer implementation.

Conceptually:

```text
LanDiscovery
    ↓
DiscoveredDevice
    ↓
ConnectionManager
    ↓
TransferSession
```

Do not make the transfer engine depend directly on NSD classes.

---

# 15. Wi‑Fi Direct Rules

Wi‑Fi Direct is a transport/discovery path, not a separate file-transfer engine.

The intended flow is:

```text
WifiP2pManager
      ↓
P2P discovery / connection
      ↓
IP connectivity
      ↓
ConnectionManager
      ↓
Shared Transfer Engine
```

Important:

- Handle Android permission requirements correctly for the target API level.
- Handle location/services requirements where applicable to the OS version/device.
- Handle peer discovery callbacks.
- Handle group creation/removal.
- Handle connection state changes.
- Do not assume the same device always becomes the group owner.
- Do not assume the same IP behavior across every Android version/device.
- Test on physical phones.

Document device-specific behavior in `logs/experiments.md` rather than hard-coding assumptions.

---

# 16. Transport Abstraction

The transfer engine must not care whether the underlying connection came from LAN or Wi‑Fi Direct.

Use an abstraction conceptually similar to:

```kotlin
interface PeerTransport {
    val type: TransportType
    suspend fun connect(peer: Peer): Connection
    suspend fun disconnect()
}
```

Possible transport types:

```text
LAN
WIFI_DIRECT
```

Later additions could include other transports, but do not build them until needed.

---

# 17. Transfer Protocol Rules

The protocol must be explicitly documented in:

`docs/protocol.md`

At minimum document:

- Protocol version.
- Handshake.
- Device identification.
- Pairing/authentication.
- File metadata.
- Transfer IDs.
- Chunk numbering.
- Chunk sizes.
- Resume state.
- Checksums/hashes.
- Error responses.
- Cancellation.
- Completion.
- Compatibility rules.

Do not let the wire protocol emerge accidentally from ad-hoc Kotlin method calls.

---

# 18. Large File and Resume Rules

The application must be designed for transfers larger than normal phone photos/videos.

Never keep an entire large file in RAM.

Use streaming I/O.

Track transfer state persistently.

A transfer should be resumable after:

- App restart.
- Connection loss.
- Wi-Fi switch.
- Temporary peer unavailability.
- Process restart when platform constraints permit recovery.

A resumed transfer must validate that the source/destination file identity still corresponds to the previous transfer.

---

# 19. Security Rules

Security is part of the architecture, not a later cosmetic feature.

Minimum goals:

- Authenticated peer connection.
- Encrypted transfer channel.
- No unauthenticated arbitrary file writes.
- User approval before accepting a new peer.
- Secure handling of trust/pairing state.
- Validation of received metadata.
- Safe destination paths.
- Protection against path traversal.
- File integrity verification.

Never disable TLS or certificate/identity validation permanently just because development is easier without it.

If a development-only bypass is necessary, mark it loudly and track it as a technical debt item.

---

# 20. Storage Rules

Use Android's supported storage APIs.

Do not assume unrestricted filesystem access.

Where the user chooses files or directories, prefer the appropriate Storage Access Framework APIs.

Never hard-code private or device-specific storage paths.

---

# 21. Background Transfer Rules

Do not assume a normal Android Activity/process can safely perform long-running transfers in the background.

Background transfer design must account for modern Android execution limits.

When using foreground services:

- Declare the correct service type.
- Declare required permissions.
- Provide the required notification.
- Handle modern Android foreground-service restrictions.
- Verify behavior on the target Android versions.

Important: Android platform limits can change. Check current official documentation before changing service behavior.

Do not claim "background transfer is fully supported" unless tested on actual supported Android versions.

---

# 22. UI Rules

The UI should remain simple, fast, and understandable.

**Premium messaging UI** follows additional rules in **§34 Premium Chat UI**. When chat UI work conflicts with this section's simplicity guidance, §34 governs chat-specific components; this section still governs transfer/LAN MVP screens until integrated.

Primary flows:

```text
Home
├── Send
├── Receive
├── Nearby Devices
└── Transfers
```

Transfer UI should show:

- File name.
- File count.
- Total size.
- Bytes transferred.
- Percentage.
- Current speed.
- Average speed where useful.
- ETA.
- Connection type.
- Pause/resume state.
- Error/retry state.

Do not put protocol/debug details in the normal user interface.

Technical diagnostics belong in logs/debug screens.

---

# 23. Performance Rules

Do not optimize based on intuition alone.

Measure first.

Benchmark:

- Different file sizes.
- Different chunk sizes.
- One vs multiple streams.
- LAN vs Wi‑Fi Direct.
- 2.4 GHz vs 5 GHz where available.
- Different phones.
- Slow vs fast storage.
- CPU load.
- Thermal throttling.

Always distinguish:

```text
network-limited
storage-limited
CPU-limited
TLS-limited
hashing-limited
protocol-limited
```

Do not assume the network is the bottleneck.

---

# 24. Debug Logging Rules

Use structured logs.

Recommended tags:

```text
DISCOVERY
LAN
WIFI_DIRECT
CONNECTION
PAIRING
TLS
TRANSFER
CHUNK
STORAGE
DATABASE
SERVICE
PERFORMANCE
```

Good:

```text
TRANSFER: transferId=1234 chunk=42 bytes=8388608 durationMs=174 speedMbps=385
```

Bad:

```text
it worked
```

Never log:

- Private keys.
- Passwords.
- Authentication secrets.
- Sensitive user data unnecessarily.

---

# 25. When Fixing Bugs

A bug-fix cycle should be:

```text
Observe
   ↓
Reproduce
   ↓
Capture evidence
   ↓
Identify root cause
   ↓
Make smallest reasonable fix
   ↓
Test
   ↓
Record result
   ↓
Commit
```

Do not immediately rewrite an entire subsystem because of one error.

Before changing architecture, determine whether the issue is:

- lifecycle-related
- permission-related
- API misuse
- race condition
- state-management error
- networking problem
- device-specific behavior
- build/dependency issue
- actual architectural limitation

---

# 26. Failed Approaches Must Be Preserved

When an approach fails, document it.

Example:

```markdown
## Failed approach — UDP as the initial transfer transport

### Why attempted
Expected lower overhead.

### Why rejected
Reliable ordering, retransmission, congestion control, and resume behavior significantly increased protocol complexity for the initial implementation.

### Revisit condition
Consider only after the TCP baseline has been thoroughly benchmarked.
```

A future AI must be able to see this and avoid repeating the same experiment without a new reason.

---

# 27. AI Must Not Delete Contextual Documentation

Do not delete:

- old error reports
- previous benchmarks
- failed experiments
- architecture decisions
- handoff notes
- useful debugging information

Instead, mark items as:

```text
RESOLVED
SUPERSEDED
OBSOLETE
```

and explain why.

The project documentation is deliberately a historical memory of the development process.

---

# 28. Session-End Procedure

At the end of a meaningful coding session, the AI should:

1. Verify the project state.
2. Run relevant tests/builds.
3. Update `logs/progress.md`.
4. Update `logs/errors.md` for any new issues.
5. Update `docs/decisions.md` for architectural decisions.
6. Update `logs/experiments.md` for benchmarks/experiments.
7. Update `logs/handoff.md` with the exact stopping point.
8. Commit the work when appropriate.
9. Record the commit hash in the handoff if available.

The next AI should be able to continue immediately.

---

# 29. Current Project Status

This section must be updated by the AI as implementation progresses.

## Current Phase

**Adaptive UI — phone → tablet → desktop (planning done 2026-09-15, phases AD-1…AD-8 NOT STARTED; decision AD-D1 = (B) answered).** Premium chat UI component sequence is largely implemented (UI-001–UI-050); LAN MVP networking continues in parallel. Authoritative live status: `logs/handoff.md` + `docs/ui/ui-research-index.md`; authoritative plan for sizing/resize/pane/layout work: `docs/migration/ADAPTIVE-UI-PLAN.md` (AD-D1's desktop-scale decision is recorded in its §5.1: OS scale baseline + a desktop-only UI-scale, Android's look preserved or improved).

**Before any layout, sizing, density, resize or pane work, read `docs/migration/ADAPTIVE-UI-PLAN.md`.** It records three verified defects (the desktop conversation renders in the list pane; no desktop sizing policy exists, so everything looks phone-sized; Android has no adaptive layout) and sequences the fixes.

## Stable Features

- Project architecture defined.
- LAN transport concept defined.
- Wi‑Fi Direct transport concept defined.
- Shared transfer-engine architecture defined.
- Persistent progress/documentation strategy defined.
- Premium chat UI master plan and `docs/ui/` research structure (2026-08-19).
- Provisional conversation UI scaffold (not accepted — pending UI-001+ research).

## In Progress

- Android Studio project implementation.
- LAN discovery MVP and persistent `LanSession`.
- Premium chat UI component sequence (`docs/ui/`) — UI-001–UI-050 largely implemented; device verification and the UI-045 quality gate remain.
- **Adaptive UI upgrade** (`docs/migration/ADAPTIVE-UI-PLAN.md`) — phases AD-1…AD-8 planned 2026-09-15, none executed. AD-1 (desktop scale/metrics), AD-2 (window & pane geometry), AD-3 (conversation in the detail pane) are the owner-visible ones.

## Not Yet Implemented

- Custom composer, selection, context menu, reactions (UI-007–UI-013) per research-first plan.
- **Adaptive layout execution** — desktop scale/metrics policy (AD-1), window & pane resize geometry (AD-2), conversation-in-detail-pane (AD-3), pointer/keyboard idioms (AD-4), wide-screen content measure (AD-5), Android tablet/foldable (AD-6), resize state continuity (AD-7), adaptive quality gate (AD-8). See `docs/migration/ADAPTIVE-UI-PLAN.md`.
- Complete LAN transfer.
- Wi‑Fi Direct transfer path.
- Pairing.
- TLS integration.
- Chunked transfer.
- Resume.
- BLAKE3/integrity verification.
- Persistent transfer state.
- Production background-transfer implementation.
- Performance benchmarking (UI-042/UI-043).

## Known Risks

- Android version differences in Wi‑Fi Direct behavior.
- Permission changes across API levels.
- Background execution restrictions.
- Device-specific Wi-Fi behavior.
- Storage performance becoming the bottleneck.
- Wi‑Fi Direct not always being faster than LAN.

---

# 30. Definition of Done

A feature is not considered complete merely because the code compiles.

A feature is considered complete when:

- Implementation exists.
- Relevant tests exist or testing is explicitly documented as not practical.
- Build succeeds.
- Physical-device behavior is verified when networking is involved.
- Errors are handled.
- Important limitations are documented.
- Progress is logged.
- Handoff state is updated.
- Commit is made where appropriate.

---

# 31. Golden Rules for Every AI

1. **Read before editing.**
2. **Verify assumptions against current Android documentation.**
3. **Do not trust chat history to preserve project state.**
4. **Write important knowledge into the repository.**
5. **Log errors and their real fixes.**
6. **Record failed approaches so they are not repeated.**
7. **Use small, understandable changes.**
8. **Test on physical Android devices for networking features.**
9. **Measure performance instead of guessing.**
10. **Never silently change architecture.**
11. **Keep LAN and Wi‑Fi Direct behind a shared transport abstraction.**
12. **Protect user files and connection credentials.**
13. **Leave the project in a state another AI can immediately continue.**

---

# 32. Fast Start for a New AI Chat

When a new AI begins work, the first response/action sequence should effectively be:

```text
1. Read AGENTS.md
2. Read logs/handoff.md
3. Read logs/progress.md (latest entries first)
4. Read relevant docs (for UI: docs/ui/flash-premium-chat-ui-implementation.md + ui-research-index.md)
5. Inspect git status + recent commits
6. Inspect the implementation
7. State the current project state internally
8. Make the requested change
9. Test it
10. Log the result
11. Update handoff.md
```

The project owner's expectation is **continuity across AI systems**.

A new AI should feel as though it is joining an ongoing engineering team, not starting a project from zero.

---

# 33. Owner Preference: Transparent Engineering Memory

The project owner specifically wants extensive documentation because different AI systems may be used during development.

Therefore, when in doubt about whether something is worth recording, prefer recording it when it could help another developer/AI understand:

- why something was implemented,
- why another approach was rejected,
- what failed,
- how it was fixed,
- what was tested,
- what remains,
- or what the next step should be.

The repository should serve as the project's long-term engineering memory.

---

# 34. Premium Chat UI — Research-First Rules

Flash messaging UI is a **separate, high-quality track** from the LAN MVP home screen. Networking stays independent of UI.

### Authoritative documents

| Document | Role |
|---|---|
| [`docs/ui/flash-premium-chat-ui-implementation.md`](docs/ui/flash-premium-chat-ui-implementation.md) | Master plan: all UI-001–UI-045 requirements |
| [`docs/ui/ui-research-index.md`](docs/ui/ui-research-index.md) | Component order, status, dependencies |
| [`docs/ui/component-doc-template.md`](docs/ui/component-doc-template.md) | Required sections per component |
| [`docs/ui/<component>.md`](docs/ui/) | Per-component research + design specs |

### Core rule

```text
Research → compare → design → document → implement → test → polish → approve → next component
```

**Do not implement a major chat UI component until its `docs/ui/*.md` file is at least DESIGNED.**

### Non-negotiable prohibitions

- No generic Material 3 chat app as the end state
- No default `TextField` / `OutlinedTextField` as the **final** composer
- No default Material message bubbles or `TopAppBar` as finished chat UI
- No stock Material Icons as the final visible icon system (system-mandatory exceptions only)
- No blind cloning of Telegram, WhatsApp, Signal, Slack, or Stream
- No Stream (or other proprietary chat) SDK/source incorporation (see ADR-003)
- No whole-screen implementation before component research

### Technology

- Kotlin + Jetpack Compose
- Material 3 / M3 Expressive as **infrastructure only** (semantics, a11y, layout, adaptive APIs)
- Centralized `FlashTheme`, `FlashColors`, `FlashTypography`, `FlashShapes`, `FlashSpacing`, `FlashMotion`, `FlashIcons`
- Verify current Android/Compose APIs before choosing animation or gesture approaches
- Document every new UI dependency (version, license, alternatives) before adding

### Component sequence (summary)

Implement in order tracked by [`ui-research-index.md`](docs/ui/ui-research-index.md):

1. **UI-001** Visual identity / design system  
2. **UI-037** Motion system (with UI-001)  
3. **UI-002** Icon system  
4. **UI-003** Chat list → **UI-004** Header → **UI-005** Bubbles → animations, selection, menus, reactions, replies  
5. **UI-011** Composer + **UI-012** Attachment + **UI-013** Send  
6. Media, voice, scrolling, search, states, group, P2P status, pairing, navigation, adaptive, dark/dynamic color  
7. **UI-042–UI-045** Performance, stress test, network UI simulation, quality gate  

Full requirements for each ID are in the master implementation doc.

### AI procedure for UI tasks

1. Read §34 documents and `logs/handoff.md`  
2. Identify next component from `ui-research-index.md` (owner may reprioritize)  
3. Fill component research doc (≥3 approaches where practical)  
4. Mark DESIGNED → implement **only that component**  
5. Build, preview, physical-device test, profile if needed  
6. Update component doc, `logs/progress.md`, `logs/handoff.md`, `docs/decisions.md` for architectural choices  
7. Do not start the next component until current one is verified or explicitly deferred  

### Provisional code warning

Exploratory UI under `app/.../ui/design/` and `app/.../ui/chat/` is **not accepted premium UI**. Re-evaluate or replace as each UI-00X component completes research.

### Completion definition (chat UI)

Chat UI is complete only when every major component has research + design docs, the visual/motion/icon/interaction languages are coherent, accessibility and dark mode work, performance is tested on real devices, and UI-045 quality gate passes. **Compiling is not sufficient.**

### Long-term quality target

Telegram-level fluidity + Signal-level clarity + familiar messaging patterns + **Flash-specific identity** + native performance + **P2P-aware UX** — without becoming a visual clone.
