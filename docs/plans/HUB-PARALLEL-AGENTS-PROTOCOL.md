# Running up to three implementer agents at once (Plan B, Plans A1–A4)

Written 2026-10-14. Status: **PROPOSED, waiting for the owner's three answers in section 9**. Nothing here is built except `tools/flash-gradle.ps1` (tested with a stand-in command only).
Read `FLASH-HUB-INTEGRATION-ROADMAP.md` and `HUB-QUALITY-CONTRACT.md` first. This file changes **how** the phases are run, never **what** they must contain.

## 1. Facts measured on this PC (2026-10-14)

| Fact | Value | Why it matters |
|---|---|---|
| CPU / RAM | 8 logical CPUs, 19.9 GB, only 3.9 GB free at the time of the check (two Java processes of about 2 GB each already running) | A second concurrent Gradle build swaps or dies with OutOfMemory. |
| Disk | C: 15.4 GB free of 238 GB (94 % full). **E: 73.7 GB free.** D: 3.4 GB free | A fully built tree is about 3–4 GB of `build/` output. Three trees on C: would fill it. Worktrees go on **E:**. |
| Gradle | one 2 GB daemon per JVM-option set, plus a Kotlin daemon and test workers; `org.gradle.parallel` is off; configuration cache is per project directory | One build at a time is affordable. Daemons are shared between trees, but the first build in every new tree is cold (full configure and compile). |
| Ports | server tests pick a free port (`ServerSocket(0)` in `E2eHarness`, `FlashServerEngineTest`, `MainCommandsTest`); the desktop engine reports the port its network layer returned at start (`DesktopEngine.boundPort`, read by `DesktopEngineTlsBindingTest`). The Android `DataChannelServer.start(preferredPort, portSpan = 20)` shows a "preferred port, then up to 20 higher" pattern; **I did not confirm the desktop side does the same**, and nothing was run against the owner's open desktop app | Server tests cannot collide. Desktop-engine tests probably cannot either, but that is **unproven**: if one fails with "address in use" while the owner's desktop app is open, close the app and record it as a finding. |
| Phone | **one** Android phone, one adb server | Two agents installing or forwarding at once overwrite each other's APK and port forwards. |
| Tree state | Roughly 35 modified and 25 untracked files, **nothing committed**: B0, B1, the six plan documents, ADR-133..138 and the hardening-round leftovers are mixed in one working tree | A git worktree starts from a commit. Untracked plans and uncommitted B1 code do not exist in it. |

## 2. Blockers that must be cleared before ANY second agent starts (stop-the-line)

1. **B1 is built (per `logs/handoff.md`; `logs/progress.md` says "remediation complete") but I have seen no ACCEPT verdict for it.** Every later phase stands on its wire contract, its Room entities and its sync engine. If the verifier has not ACCEPTED the current B1, run it first; if it has, record the verdict here and in section 10. Starting B2/A2 on an unverified B1 means rework in three places at once.
2. **Checkpoint commit.** After B1 is ACCEPTED, the owner asks for one commit on `dev` (B0 + B1 + the plans + this file). Until then worktrees cannot contain the work. I do not commit unasked.
3. **Test baseline.** Run the integration gate (section 6) once on the checkpoint and write the result into this file's section 10, including the **known failures that exist without any change** (ERROR-106 `:desktop:jvmTest`, and any DataStore rename failure). Without a baseline an agent cannot tell "I broke it" from "it was already red", and three agents will each invent a story.
4. **Handoff drift.** B1's handoff names B2 as "Layer 1 Admission Gate & Handshake Binding (`PeerTrustStore.lookupOrgPeer`, TLS fingerprint matching…)". The plan's B2 is different: an `OrgPeerResolver` port in a low module with an adapter in the engine, a `PeerTrustPolicy` object, an `isOrgPeer` constructor parameter that defaults to `false`, and the §6 call-site table. **The plan wins.** Every kickoff prompt must say "follow plan §4 Phase B2, not the handoff's description of it".

## 3. Machine rules (apply to every agent and verifier)

- **Gradle only through `tools/flash-gradle.ps1`**, run from your own worktree root:
  `powershell -File tools\flash-gradle.ps1 :core:enterprise:jvmTest`
  It takes a machine-wide lock (`Global\FlashGradleBuild`), sets JBR 21 and the AF_UNIX fix, runs, and releases the lock even on failure or a killed process. Waiting up to 45 minutes is normal; exit code 75 means "someone else is still building, ask again". **Never call `gradlew` directly. Never start a second build "just to be fast".**
- While waiting for the lock an agent does useful non-build work (writing tests, docs, adversarial review). It must not poll in a loop.
- Evidence runs (the numbers that go into reports) must say **executed**, not `FROM-CACHE` / `UP-TO-DATE`. If a task shows `FROM-CACHE`, rerun that module with `--rerun-tasks` for the single module. Do not enable `--build-cache` for evidence runs. (It is allowed to speed up a first cold compile in a new tree.)
- When a stream finishes a session, run `powershell -File tools\flash-gradle.ps1 --stop` so an idle daemon does not hold 2–4 GB.
- `local.properties` is git-ignored: copy it from the main tree into each worktree. `keystore.properties` and the `.jks` are **never** copied or touched (release signing is out of scope).
- Worktrees live on **E:**: `E:\FlashWork\S1-org-trust`, `S2-server`, `S3-hubtab`. The main tree `C:\Users\KaliOxygen\Downloads\Flash` is the **integration tree**: no stream agent edits it.
- The owner's Windows desktop Flash app may stay open (see the ports row in section 1 for the one unproven case). Stream tests must use temp state directories like `DesktopEngineTlsBindingTest`, never `~/.flash`.

## 4. The phone is a single-owner resource

- **No stream agent touches the phone** unless the owner names exactly one agent for a device session. That agent also takes the lock `Global\FlashDevice` (same pattern as `flash-gradle.ps1`; ask the integrator for a copy before the first device run) and uses `adb install -r` only. The safety rules of the ORGT-B01 prompt stay in force: never uninstall, never `pm clear`, never print key bytes.
- Everything that needs a phone goes to `docs/testing/TEST-BACKLOG.md` (as today). Device-owed items are not a reason for an agent to wait.
- Instrumented tests (`app/src/androidTest`) count as device work. Compile them (`:app:compileDebugAndroidTestKotlin`) but do not run them without the device session.

## 5. Which phases may run side by side

```text
B1 (built) ──verify──► CHECKPOINT COMMIT ──┬──► S1: B2 ──► B3 ──► B4 ──► B5          (messaging, hosts, one agent for the whole chain)
                                           ├──► S2: A2 ──► A1 ──► A3                  (server, console, hub client DTOs, announcements)
                                           └──► S3: Hub tab H0 → H5 ──(wait)──► H6 → H10   (UI shell now; data screens after B1+A2+A3 are merged)
B6: only after the owner passes ORGT device tests (owner-gated, not parallel work).
```

| Stream | Owns (may edit) | Must not touch | Waits for |
|---|---|---|---|
| **S1** org trust | `:core:messaging` (`RealFlashChatRepository` 6,847 lines, `PeerTrustPolicy`, resolver port), `:core:security` trust store, engine hosts `Flash.kt`, `DiscoveryEngineHolder.kt`, `DesktopEngine.kt` wiring, connection planner, Plan B strings/docs | `:server`, console, Room schema numbers not reserved for S1 | B1 accepted |
| **S2** server | `:server` (directory, admin, announcements), console web assets, `:core:hub-protocol` **new files**, `:core:enterprise` DTO/client additions, `docs/protocol-routes.txt` | `RealFlashChatRepository`, hosts, `ui/*` | B1 accepted (A2 uses B1's `DirectoryVisibility`); A1 after A2; A3 after A2+A1 |
| **S3** hub tab | `ui/chat/.../navigation`, `ui/hub/` (new), `MainActivity.kt`, `DesktopShell.kt`, `DesktopSideBar.kt`, `DesktopMain.kt`, icons, strings of the tab | `RealFlashChatRepository` and hosts (H6's repository facade is added only after S1 has merged B2, in one small reviewed hunk) | none for H0–H5; H6–H10 wait for B1 + A2 + A3 merged |

Why not more parallelism inside Plan B: B2 needs B1's snapshot; B3 needs B2's policy; B4 edits the same `RealFlashChatRepository` group gates and adds a frame type next to B5's (`hrs1` and `rvg1` live in the same frame codec), so **B4 and B5 are sequential in S1**. B4's server half (roster statements) edits the same `OrganizationDirectory.kt` and `directory` migration component as A2, so B4 starts only after S2 has merged A2.

Two agents is the comfortable number until B1 is checkpointed (only B1 verification and S3's H0–H5 can run before that). Three is workable after the checkpoint **if the owner can keep up with three reports**: the human relaying reports and verifier prompts is the real bottleneck, not the CPU.

## 6. Integration protocol (one integrator, phase boundaries only)

1. A stream finishes a phase and its session report; the owner runs the **verifier prompt** (read-only, same worktree, builds only through the lock). REJECT → the same stream fixes; nothing merges.
2. On ACCEPT the integrator (the owner's chosen session; not a stream agent) does, in the main tree:
   `git merge --no-ff agent/<stream>` → resolve → run the **integration gate** under the lock → fix → merge the stream's log fragments (section 8) into the canonical logs → tag `hub-int-<n>`.
3. The other streams then `git merge dev` into their branch **before their next phase** (merge, not rebase, so verified history is not rewritten). Never integrate mid-phase.
4. **Integration gate** (all through `flash-gradle.ps1`; add every module a merged phase touched):
   `:core:hub-protocol:jvmTest`, `:server:test`, `:core:persistence:jvmTest`, `:core:persistence:testAndroidHostTest`, `:core:enterprise:jvmTest`, `:core:enterprise:testAndroidHostTest`, `:core:messaging` tests (the tasks named in `logs/handoff.md`), `:desktop:compileKotlinJvm`, `:app:compileDebugKotlin`, `:app:compileDebugAndroidTestKotlin`.
   Compare with the baseline of section 10. A failure that is not in the baseline blocks the merge.
5. After a merge that touched Room, server migrations or `docs/protocol-routes.txt`, run the migration chain tests (`FlashMigrationsChainTest`, `FlashJvmMigrationsTest`, the server `MigratorTest`) **again on the merged result**; two individually green migrations can still collide.
6. Git permissions: stream agents commit **only to their own branch `agent/<stream>`**, small focused commits (AGENTS §11), never to `dev` or `main`, never force-push, never `git stash` in the main tree. Needs the owner's yes (section 9, question 2).

## 7. Reservations (numbers that two agents would otherwise both take)

Planning table; the integrator renumbers if the merge order differs and re-runs the migration tests.

| Resource | Used now | Reserved |
|---|---|---|
| Room schema (`FlashDatabase`, `FlashSchemaSteps`, `FlashMigrations`, `schemas/*.json`) | 14 (B1) | **15** B4 (`HubGroupRosterEntity`), **16** A3 (announcement cache). Anything else: ask. |
| Server migrations, component `directory` | 2 (B1) | **3** A2 (`departments.hidden`), **4** B4 (group roster statements) |
| Server migrations, component `admin` | 3 (approver phone) | **4** A1 (department head role + scope) |
| Server migrations, new component `announcements` | – | **1** A3 |
| ADR numbers (last used 138) | – | S1 **139–148**, S2 **149–158**, S3 **159–168**, integrator 169+ |
| ERROR numbers (last used 399) | – | S1 **400–429**, S2 **430–459**, S3 **460–489**, integrator 490+ |
| EXP numbers (last used 024) | – | S1 025–029, S2 030–034, S3 035–039 |
| UI ids (last used UI-058) | – | S3 only: UI-059 and up |
| Test id prefixes | – | S1 `ORGT-`; S2 `PRIV-`, `DEPH-`, `ANN-`; S3 `HUBT-` (each stream appends only to its own prefix) |
| Probe names (`docs/testing/PROBES.md`) | `org.*` planned | S1 `org.*`; S2 `dir.*`, `ann.*`; S3 `hub.*` |
| Feature caps in HELLO | `gs1`, `cv1`, … | S1 `org1` (B6), `hrs1` (B4), `rvg1` (B5). Nobody else adds one without asking. |

## 8. The shared files that will conflict, and the rule for each

| Shared file | Hazard | Rule |
|---|---|---|
| `logs/progress.md` (15.8k lines, CRLF), `logs/handoff.md`, `logs/errors.md`, `logs/experiments.md`, `docs/testing/TEST-BACKLOG.md`, `docs/decisions.md`, `AGENTS.md` §29, `docs/security.md`, `docs/testing/PROBES.md`, `docs/android-platform-notes.md` | Every agent inserts at the same place (top of progress, end of backlog). Agents here rewrite whole files with scripts, so the last writer silently deletes the others' entries. This is the most likely way work gets lost. | **Temporary exception to AGENTS §6–§10 (needs the owner's yes, question 3):** a stream agent writes only to its own fragment folder `logs/streams/<stream>/` (`progress.md`, `handoff.md`, `errors.md`, `experiments.md`, `backlog-additions.md`, `decisions-additions.md`, `security-additions.md`, `probes-additions.md`, `platform-notes-additions.md`) in the **same format** the canonical files use, with the reserved numbers of section 7. It never edits the canonical files above. The integrator moves the fragments into the canonical files at each merge and clears the fragment. A fragment that is not merged is still the record (nothing is lost). |
| `RealFlashChatRepository.kt` (6,847), `DiscoveryEngineHolder.kt` (3,013), `DesktopEngine.kt` (2,255), `Flash.kt` (1,116) | Large files, several phases touch them | Only S1 edits them (plus the one S3/H6 facade hunk). New logic goes in **new files**; the host gets a three-line hook. No reformatting, no import reordering, no moving code. |
| `FlashSchemaSteps.kt`, `FlashDatabase.kt`, `FlashMigrations.kt` | Same list, appended by two streams | Reserved numbers (section 7). Only the stream holding the number edits the schema; entities and DAOs are new files. |
| `server/.../storage/Migrations.kt` | Two components add steps | Reserved numbers; each component's steps live in their own list or file where the code allows. |
| `docs/protocol-routes.txt` + `RouteTableContractTest`, `docs/protocol.md` | Sorted list; adding a route fails the contract test until the list is regenerated; merges conflict | Only S2 adds routes. The list is regenerated by the stream, and by the integrator again after every merge. `protocol.md` additions go into a new subsection per feature (anchor text differs, so merges are textual-clean). |
| `RestDtos.kt`, `HubContract.kt` (`:core:hub-protocol`) | Append-only but shared | New DTO groups go in **new files** (for example `AnnouncementDtos.kt`). Edit the two shared files only to register something that cannot live elsewhere. |
| `build.gradle.kts` of modules, `settings.gradle.kts`, `gradle/libs.versions.toml` | One new dependency changes every stream's build | **No new dependency, no new module** in parallel mode without asking the integrator (AGENTS §3 already requires documenting every dependency). |
| String resources (Android `strings.xml`, shared Compose strings) | Everyone appends at the end | New strings in a per-feature resource file when the project's structure allows; otherwise a clearly named block per feature at a stream-specific anchor. |
| `docs/plans/*.md` implementation logs (§14 of each plan) | Only the owning stream writes its plan's log | Plan B log: S1. A1/A2/A3 logs: S2. Hub tab log: S3. Nobody edits another stream's plan. |

## 9. Owner decisions needed before the first parallel start

1. **Worktrees on E: (73.7 GB free), integration tree stays on C:** yes / no. (Recommended: yes. C: has 15.4 GB.)
2. **Stream agents may commit to their own `agent/<stream>` branches** (never to `dev`/`main`), and the owner asks for the **checkpoint commit** on `dev` after B1 is accepted. Your standing rule is "commit only when asked"; this is that ask for the branch model, or answer "no commits" and the integrator copies files from the worktrees by hand (slower and riskier).
3. **Temporary per-stream log fragments** instead of editing the canonical logs (section 8). Recommended: yes, recorded as a documented exception and folded back at every integration. If no: run **one** stream at a time.

## 10. Baseline (fill after the checkpoint, before the first stream starts)

| Gate task | Result at the checkpoint | Known pre-existing failures |
|---|---|---|
| (to be filled by the integrator) | | |

## 11. What each kickoff prompt must add (paste under the standard kickoff of `HUB-PLANS-PROMPTS.md`)

```text
PARALLEL MODE. You are stream <S1|S2|S3> working ONLY in E:\FlashWork\<stream> on branch agent/<stream>.
Read docs/plans/HUB-PARALLEL-AGENTS-PROTOCOL.md completely before anything else, and obey sections 3, 4, 7 and 8.
1. Build and test only through `powershell -File tools\flash-gradle.ps1 <gradle args>`. Never call gradlew directly. Never touch the phone.
2. Write logs only into logs/streams/<stream>/ (the canonical logs are the integrator's). Use only the numbers reserved for your stream.
3. Edit only the files your stream owns (section 5 table). New code goes in new files; hosts get a minimal hook. No reformatting of existing files.
4. Commit only to agent/<stream>, small commits. Do not merge, rebase or switch branches.
5. Follow the plan's phase text, not any other agent's description of it (the handoff can be wrong).
6. Report using the contract section 7 template and add: the exact gate commands you ran and their last lines, and the list of shared files you touched with the hunk sizes.
```
