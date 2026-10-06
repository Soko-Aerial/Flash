# Group membership by group id + secret, and the group swarm: implementation plan, phase by phase

- **Date:** 2026-10-04. **Revised the same day** after the owner's decisions (section 1.3): every member of a group sends, receives
  and serves files **whether or not it is paired with anyone**. Membership is by group id + group secret (this reverses ADR-044's
  rejection), so the plan now has two tracks: **GM** (membership) and **SW** (swarm).
- **Status:** PLAN. Nothing is built. The owner has answered the decisions (section 10); the ADRs are not written yet (SW-0).
- **Relationship to other documents:** `GROUP-SWARM-DESIGN.md` is the design (the *why*). This plan is the *how*. Where they
  differ, this plan wins; section 1.2 lists every difference. `TRANSFER-V2-SWARM-AND-MULTIFILE-PLAN.md` stays the background for
  multi-file and the 1:1 transfer v2.
- **Audience:** any AI or developer, including a less capable model in a fresh chat. Every task names its files. Every phase says
  what to read first, what it can break, how to test it, and when to stop and ask.

## TL;DR for the owner

- **The goal is reliability, not speed.** A group file must reach everyone even when the sender leaves halfway, and the sender's
  cancel stops it everywhere, including on devices that were offline at the time.
- **The swarm is its own module (`:core:swarm`), not part of the transport.** The transport only gets two small, generic hooks
  that never mention the swarm. Someone who imports the library and never turns the swarm on sees no change at all (section 2).
- **Swarm: 13 phases, SW-0 to SW-12** (section 7A); membership: GM-1 to GM-11 (section 7B):
  - The pure parts (codec, engine, simulator) can start as soon as the ADRs are accepted.
  - The host work starts with two refactors that change no behaviour.
  - The error phase (SW-10) works through a catalogue of about 50 failure conditions (section 8). For each one it fixes how
    it is detected, what state and sentence the user sees, and how it recovers automatically.
- **A dangerous ripple, found while planning.** On Android 15+, when the six-hour service limit is reached, the app today
  *cancels* every running transfer (`FlashBackgroundService.onTimeout`). With "sender's cancel = cancel everywhere", that would
  cancel a group file for the whole group because Android stopped a service. Phase SW-2 separates "the user cancelled" from "the
  system stopped us" before any swarm code exists. Section 6 lists 47 ripples of this kind: 30 for the swarm and 17 for membership.
- **Decisions (2026-10-04): taken** (section 10). The owner accepted the recommendations, with one change: **anyone in the group
  receives, regardless of pairing.** That is delivered by group membership through a group id + secret (track GM, section 7B).
  - **D1:** ADR-044's rejection of a group secret is reversed.
  - **D2:** the default join policy is "admin approves".
  - **O-6:** the swarm stays a separate module.
- **Two tracks:**
  - **GM-1 to GM-11:** group membership by secret, the group gate, rotation, upgrading existing v2 groups, settings, UI.
  - **SW-0 to SW-12:** the swarm.
  - They meet at one place, the **group gate** (GM-5). SW-8 waits for it, so the first swarm build already serves unpaired
    members.

---

## 0. How to use this plan

### 0.1 Reading order (every session, every model)
1. `AGENTS.md`, at least sections 4, 6 to 12, 18, 19, 21, 27 to 29 and 35.
2. `logs/handoff.md`, the top entry.
3. This plan:
   - sections 0 to 6 completely;
   - then the phase you work on;
   - then the section 8 entries that phase names.
4. `GROUP-SWARM-DESIGN.md` sections 3, 4 and 11 for the swarm, and sections 1 and 2 for membership, as background. This plan
   overrides them (1.2, 1.3, 5.7).
5. The **Read first** list of your phase. Open every file it names. Line numbers here were right on 2026-10-04 and will
   drift, so **search for the named symbol** rather than trusting a line number.

### 0.2 Glossary
| Word | Meaning here |
|---|---|
| **Origin** | The device that sent the file to the group (the "original sender"). It holds every piece at the start. |
| **Member** | A device in the group's signed roster (v2 group, `g2-` id). After GM it may be paired with nobody. |
| **Group-only peer** | A member this device is not paired with. It may exchange that group's traffic, never 1:1 traffic (GINV-8). |
| **Admin** | The owner or a co-owner (ADR-063). Only admins sign certificates, rotations and settings. |
| **Group secret, epoch** | 32 random bytes per group (`GS`). The epoch is its version, +1 on every rotation. |
| **Invite** | A link carrying the group id, epoch, secret, name and how to reach the inviter (GM-2). It is the trust root for joining without pairing. |
| **Rotation notice** | A signed record "group G now uses epoch e". It carries a commitment to the new secret, never the secret (5.7). |
| **Holder** | Any member that holds at least one verified piece of the file. |
| **Piece** | A fixed-size part of the file (64 KiB to 1 MiB), each with its own SHA-256 in the manifest. |
| **Manifest** | The list of piece hashes plus sizes and the whole-file hash. |
| **Root / content id** | SHA-256 of the canonical manifest bytes. Two files with the same bytes have the same root. |
| **HAVE** | A message saying "I now hold these pieces". |
| **Tombstone** | A signed, stored record "the origin cancelled this content". It is never undone. |
| **Gate** | The check "may this device exchange this group's content with that device right now?" |
| **Sans-IO** | Code that receives events and returns commands, with no sockets, clock, disk or coroutines inside. That makes it testable and simulatable. |
| **Driver** | The coroutine code that feeds events into the sans-IO engine and carries out its commands. |
| **Binding** | The code in `:core:engine` that connects the swarm to a host (network, messaging, storage, database, UI rows). |
| **Wait reason** | Why a download is not moving right now, for example "waiting for the sender". It always has a way back (INV-9). |
| **Host / composition root** | A place that builds the engine. There are three: `Flash.kt` (library facade), `DiscoveryEngineHolder.kt` (the Flash Android app), `DesktopEngine.kt` (Windows). |

### 0.3 Rules for every phase
- **One phase at a time.** Follow the dependency table in 7.0. Never start a phase whose prerequisites are not DONE, and update
  that table when you finish one.
- **Wire before code.** A frame or field is documented in `docs/protocol.md` (byte layout, limits, a golden vector) before the code
  that sends it is merged.
- **ADR before architecture.** SW-0's ADRs must be accepted by the owner before any code phase (SW-1 onwards, GM-1 onwards).
- **No behaviour change unless the phase says so.** In the refactor phases (SW-1, SW-2), write a test that pins today's behaviour
  *first*, then change the code, and keep every existing test green.
- **Never claim device verification.** A phase ends "unit-tested, mutation-checked, device checks owed: SWM-xx". Every owed check
  is in `docs/testing/TEST-BACKLOG.md` section 4w.
- **Never delete docs or tests** (AGENTS 27). Mark them SUPERSEDED or OBSOLETE and say why.
- **Do not commit unless the owner asks.**
- **Log every phase:**
  - `logs/progress.md` and `logs/handoff.md`, newest on top;
  - new bugs in `logs/errors.md` (next free ERROR number);
  - simulator numbers in `logs/experiments.md`, marked "simulator, not devices".
- **Kotlin only.** No C++, no Rust (D7). No new third-party dependency without an ADR. No code copied from Ketch or any other
  project without a `NOTICE` entry and a header comment (D13). Never GPL code.
- **Small files.** Keep each new file under about 400 lines; split by responsibility. This keeps a smaller model accurate.

### 0.4 Stop and ask the owner when
- an owner decision in section 10 that your phase needs is still OPEN;
- a test that was green turns red and the cause is not your change (record it, do not "fix" unrelated code);
- you would need to change a public type in a way this plan does not list: a new value in an existing enum, a reordered
  data-class parameter, a removed or renamed method;
- you would need to change `:core:network` beyond the `caps` seam of SW-2, or change `ChunkFrame`, `FlashTransferState`, or the
  layout of any existing wire frame;
- a phase's exit criterion cannot be met as written;
- a membership change would make a charter trusted without pairing with its owner or an accepted invite (GINV-3), or would let
  a peer exchange group traffic because it knows the secret rather than because the roster names its live key (GINV-2);
- you would need a new `PinSource`, or a change in the TLS pin verifier (GM-4 reuses the vouch instead).

### 0.5 Phase template
Every phase in section 7 has the same parts:
- **Goal** and **Why**;
- **Depends on** and **Read first**;
- **Ripple:** what else the phase touches and how to keep it safe;
- **Tasks:** numbered, with files;
- **Tests** and **Exit criteria**;
- **Device checks owed**;
- **Docs to update**;
- **Do not** (traps);
- **Rollback**.

### 0.6 Build and test commands (this Windows machine)
```bash
cd "C:/Users/KaliOxygen/Downloads/Flash" && export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2" && export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix' && ./gradlew :core:swarm:jvmTest
```
- **Running Gradle.** Do not add `--offline`: a known gap in the offline cache breaks some tasks. Use JBR 21 as above, not the
  JBR 25 inside Android Studio.
- **Red tests that are not yours** (pre-existing, do not fix them inside a swarm phase): `DesktopEngineGroupSessionUpTest`, and
  12 DataStore tests in `:core:persistence:testAndroidHostTest` on Windows.
- **Mutation check** (project practice). For each new guard:
  1. break it on purpose (invert a condition, drop a call);
  2. run its test and confirm the test fails;
  3. restore the guard.

  Name the mutations you tried in the progress entry.

---

## 1. What the owner asked for (2026-10-04) and what it changes

### 1.1 The request as requirements
| # | Owner (paraphrased) | Requirement in this plan |
|---|---|---|
| Q1 | "I want the swarm because I don't want sending a file to fail" | Reliability is the goal; speed is a side effect (section 3). |
| Q2 | "Even if the sender only sends half and goes offline, anyone who got that half syncs it with the others, and when the sender comes back it continues" | R2, R3; origin offer policy (4.1); walk-through 4.2. |
| Q3 | "When the original sender cancels, it should cancel everywhere" | R5; durable signed tombstone (4.3, SW-9). |
| Q4 | "A phase for errors, like a download stopped because the sender is offline that continues when he is back, and a host of other errors" | SW-10 and the catalogue in section 8. |
| Q5 | "Really detailed, a less capable model or a new chat must be rigorous about the ripple effect on other modules" | Sections 0, 6, 7. |
| Q6 | "Is this a module or part of transport? It should be optional for someone importing the library" | Section 2. |

### 1.2 Changes to the earlier documents (mark them there in SW-0, do not delete text)
- **Design D10 (retract semantics) is answered:** an origin cancel stops the transfer everywhere. O-2 settles what happens to
  copies that already finished.
- **Design D12 changes:** the swarm no longer waits for a measured speed gain (M0). Measurements (`SWM-01`..`SWM-05`, `MEAS-*`)
  become tuning inputs for piece size, slots and windows, not a go/no-go gate.
- **Design D11 is superseded:** the swarm runs on every network, hotspots included, because it uses whatever links exist. On a
  hotspot with client isolation it promises no speed-up (members then fetch from the device they can reach), but it still
  resumes and survives the sender leaving.
- **Design 4.4** said reactive gating only "if the simulator shows a need". Now the origin offer policy (4.1 Rule B) is
  **mandatory**: it is what makes the half-sent case work.
- **Design 4.8** said "Origin cancels: holders continue unless retracted". It is now "cancel everywhere" (4.3).
- **Earlier plan section 10.1** asked speed or resilience. The owner answered: resilience.
- ~~**The group-secret membership track (design G1/G2, D1 to D3) is independent.** The swarm works with today's v2 groups, whose
  members are paired or vouched. Do not wait for that track and do not mix it into this work.~~
  **SUPERSEDED 2026-10-04 (owner, section 1.3):** the owner wants every member to receive whether or not it is paired, so the
  membership track is now part of this plan (track GM, section 7B). The swarm's gate *is* the group gate built in GM-5.
- **Design 1.5 condition 1 changes (O-13):** group traffic between roster members does not wait for a per-session secret proof.
  The secret is the door key for joining and is rotated on removal. Reasons in 1.3.

### 1.3 The owner's decisions of 2026-10-04 (second message) and what they mean

The owner wrote: *"i am taking ur recommendation for the decisions but paired only to group leader can receive i have a problem
there anyone in the group should receive regardless of paired state and also it should be a different module and i am
overturning the earlier decision so a group can have an identifier and secret or so modify plan accordingly"*.

| The owner said | What it means in this plan |
|---|---|
| "taking your recommendation for the decisions" | O-1 to O-4 and O-6 to O-10 are accepted as recommended (10.1). D2 to D5, D7 to D9 and D13 are accepted as recommended (10.2). |
| "paired only to group leader can receive: I have a problem there. Anyone in the group should receive regardless of paired state" | **O-5 is replaced:** every active member of a group receives, sends and serves group files, whether or not it is paired with the owner, the sender or anyone. Track GM delivers it. |
| "it should be a different module" | **O-6 is confirmed:** the swarm is its own optional module, `:core:swarm` (section 2). Membership is **not** a new module: it extends the group code that already lives in `:core:messaging` and `:core:security`. Section 2.6 says why, and where the split line is if the owner wants a module after all. |
| "I am overturning the earlier decision, so a group can have an identifier and a secret" | **D1 is accepted:** ADR-044's rejection of a group-wide secret is reversed, with the design's mitigations: the secret is never sent, the device key stays the identity, the roster stays signed, the secret rotates on every removal, and the join policy decides who gets in. |

**What "paired only to the group leader" meant, precisely [code, 2026-10-04].** In today's v2 groups:
1. Members do **not** need to be paired with each other. The owner signs a certificate for each member, and the other members
   accept that certificate as a vouch (ADR-044 V2). Chat and calls already work between members that never paired.
2. Every member must be paired with the **owner**, in two places:
   - The owner can only add devices it is paired with (`createGroup` refuses with "Every group member must be trusted";
     search `memberIds.any { !isTrustedPeer(it) }` in `RealFlashChatRepository`).
   - A device accepts a group only when it is paired with the group's owner (`GroupSignatureRules.checkCharter` refuses with the
     reason `owner-not-paired`).
3. Group **files** go only to members paired with the **sender**:
   - the sender side: `isActiveTrustedMember` and the recipient filter in `sendGroupAttachment`;
   - the receiver side: the `GroupMedia` gate.

My earlier "yes" to O-5 would have removed limit 3 for the swarm only. Limits 2 and 3 would have stayed for everything else.
The owner's requirement removes both limits.

**What the group id + secret changes.** It changes the **trust root for joining**:
- **Today the trust root is pairing with the owner.**
- **New: the trust root is the invite.** The invite carries the group id and the secret, and it arrives out of band (a shared
  link, or a message in a Flash chat). The group id already commits to the owner's key: `GroupCanonical.deriveGroupId(ownerKey,
  nonce)`, which `checkCharter` already checks. So a device that holds an invite can verify the group's charter without
  pairing, the same way pairing verifies a key.
- **After the join, nothing new is needed.** An admin signs the newcomer's member certificate, and the existing vouching
  (ADR-044 V2) introduces it to every member.

**Why traffic does not wait for a per-session secret proof (O-13, my refinement of design 1.5, the owner may overrule).**
- **The roster already authenticates each member.** Each member's certificate names its key. The gate compares that key with the
  key of the live TLS session, and TLS proves that the peer holds it. That is device authentication, and it is stronger than
  knowing a shared secret.
- **A per-session proof between roster members stops no attacker that the roster check misses:**

  | Attacker | Stopped by |
  |---|---|
  | A removed member | Its tombstone. The rotation travels in the same gossip as the tombstone, so a device that knows the new epoch also knows the removal. |
  | Someone holding a leaked secret | Under "admin approves" a secret alone puts no key into the roster. Under "open" it does, and then removal is the remedy in both designs. |
  | A member that missed a rotation | Nobody needs stopping: it is still a member. |

- **What the proof would add is a new failure mode:** group chat and calls, which work today, would go silent on every session
  until a three-message exchange finished.
- **So the secret is used for exactly three things:**
  1. asking to join (only invite holders can);
  2. rotation, which stops a removed member and old invites from getting back in;
  3. the optional beacon (GM-8).

**New decisions** that follow from this (section 10.1, recommended defaults, they apply unless the owner overrules them before
SW-0):
- O-11: upgrading existing v2 groups;
- O-12: who may share the invite (this fills design D6);
- O-13: no per-session proof for traffic;
- O-14: how invites are delivered before QR exists;
- O-15: history for new members.

---

## 2. Module or transport? The answer

**Verdict: a separate, optional module `:core:swarm`, published as its own artifact (`core-swarm`). Not part of the transport, and
not a package inside `:core:transfer`.**

```text
 App (Android) / Desktop (Windows)      UI, permissions, foreground service, settings switch
        |  attachSwarm(config)          (nothing happens without this call)
 :core:engine                           SwarmHostBinding + adapters: transport, group context,
        |                               storage, Room store, transfer-row bridge, GroupFileSender
        |-- api --> :core:swarm (NEW)   sans-IO engine, FSW1 codec, manifest, driver, ports
        |                 |-- depends on --> :core:transfer (FlashTransfer model, Sha256), :core:common
        |-- api --> :core:messaging     (groups, roster, signatures)   <- swarm never imports it
        |-- api --> :core:network       (sessions, TLS, frames)        <- swarm never imports it
        `-- api --> :core:persistence   (Room tables)                  <- swarm never imports it
```

### 2.1 Why not inside the transport (`:core:network`)
1. **Different job.**
   - The transport moves authenticated bytes between two devices.
   - The swarm is a policy across many devices: who has which piece, whom to ask, whom to serve, when to wait. That policy
     needs group membership.
2. **A dependency cycle.** Membership lives in `:core:messaging`, which depends on `:core:network`. A swarm inside the network
   module would need messaging, so the network would depend on messaging and messaging on the network.
3. **Risk containment.**
   - The transport is device-verified (2026-09-23) and carries chat and calls. A swarm bug inside it could break both.
   - In its own module, a swarm bug can only break group files, and switching the swarm off restores today's behaviour.
4. **Testability.** The swarm core must be sans-IO so that a simulator can run 20 devices on a virtual clock (design 5.2). A
   transport owns sockets and real time, so it cannot be simulated that way.
5. **Optional for library users.** Someone who wants only chat or 1:1 transfer pays nothing for the swarm.
6. **Replaceable on either side.**
   - If the transport changes (a Rust core, iroh, Wi-Fi Direct), the swarm stays.
   - If the swarm is ever ported, the transport stays.

### 2.2 Why not a package inside `:core:transfer`
- `:core:transfer` is the 1:1 push engine. It is device-verified and every consumer uses it.
- The swarm reuses its `FlashTransfer` model and its `Sha256` object, but it needs group context and a different (pull) wire.
- Inside `:core:transfer` it would ship to every transfer consumer, and it would invite shortcuts into the 1:1 path.

### 2.3 What the lower layers get: two generic seams that never mention the swarm
1. **A generic capability list in the HELLO (SW-2).**
   - A `caps` field, exposed as `FlashDevice.features: Set<String>`.
   - Today every capability is its own HELLO field (`ping`, `gv`) and its own `FlashDevice` property (`groupProtocol`) **[code:
     `WsFlashNetwork.kt` ~371/552/1139, `JvmWsFlashNetwork.kt` ~293/461/791, `FlashDevice.kt:24`]**.
   - A generic list means future optional modules never touch the transport again.
2. **A binary-frame route by 4-byte magic (SW-2), in `:core:engine`.**
   - PTT already routes its "PTT1" frames this way **[code: `Flash.kt` `handleInboundBinary` ~887]**.
   - The swarm registers "FSW1".

### 2.4 How it stays optional
| Layer | Mechanism |
|---|---|
| Gradle | Own artifact `core-swarm`. `:core:engine` depends on it with `api`, the way it depends on `:core:ptt` (ADR-058). See O-6 for the `compileOnly` alternative. |
| Runtime | Nothing runs until the host calls `attachSwarm(config)`. `Flash.create` does not attach. |
| Wire | The device puts `sw1` into `caps` only while the swarm is attached. **No device ever sends an FSW1 frame to a peer whose HELLO lacked `sw1`.** |
| Fallback | The shared group sender (SW-1) uses today's per-member push for every member without `sw1`, and for everyone when the swarm is not attached. |
| Proof | A test (SW-8, task 13) checks that an engine without `attachSwarm`: advertises no `sw1`, drops stray FSW1 frames, and sends a group file exactly as today. |

**Why `api` + attach and not `compileOnly` (recommendation for O-6):**
- **Why calling uses `compileOnly` (ADR-033).** Calling pulls in WebRTC native libraries and needs camera and microphone
  permissions and a foreground service.
- **The swarm needs none of that.** It is pure Kotlin (a few thousand lines), with no permission and no native code.
- **`compileOnly` has a runtime trap.** Any code path that names a swarm type while the artifact is missing throws
  `NoClassDefFoundError` at runtime, and a less capable model will trip it.
- **The cost of `api` is small.** With `api`, R8 strips an unused swarm from an app.

If the owner wants engine consumers not to download the jar at all, choose `compileOnly` and follow ADR-033's rules exactly.

### 2.5 Dependency rules (a test enforces them, SW-3 task 9)
| | Rule |
|---|---|
| `:core:swarm` may depend on | `:core:common`, `:core:transfer` (model and `Sha256`), kotlinx-coroutines (driver only) |
| `:core:swarm` must not depend on | `:core:network`, `:core:messaging`, `:core:persistence`, `:core:engine`, `:core:calling`, `:core:ptt`, any `ui:*`, any platform API in `commonMain` |
| Must never import `com.transfer.flash.core.swarm` | `:core:common`, `:core:network`, `:core:transfer`, `:core:messaging`, `:core:persistence`, `:core:discovery`, `:core:security` |
| Depends on `:core:swarm` | `:core:engine` (binding and adapters); the app and desktop only through `:core:engine` |

### 2.6 Where group membership by secret lives (not a new module)
**Verdict:** membership extends the existing group code. It is not a module of its own, and it is not part of the transport.

**Why:**
1. **Group trust already lives in two modules.**
   - `:core:messaging`: `SignedGroups`, `GroupSignatureRules`, the roster tables and every group gate.
   - `:core:security`: pins, `VouchRules` and the crypto primitives.
2. **A separate membership module would create a cycle.** It would need messaging's roster, and messaging would need its
   gate.
3. **The other way out is a big refactor.** Moving all group code out of messaging is the kind of refactor already rejected for
   now (MSG-2, ADR-058).
4. **Membership is core group behaviour, not an optional extra.** A library user who never creates a secret group pays a few
   classes and one table.

**Placement:**
| Module | What goes there |
|---|---|
| `:core:security` commonMain, package `...security.group` (new) | Pure primitives with golden vectors: `GroupSecret` (redacting holder), `GroupSecretKdf` (HKDF labels), `GroupProof` (sans-IO three-message proof), `GroupSecretCommit` (the rotation commitment, 5.7), `GroupInviteCodec`, `GroupBeacon` (optional). No sealing (5.7, "Rotation without sealing"). |
| `:core:messaging`, package `...messaging.group` (new) | `GroupGate` (5.7), `GroupSecretStore` (port), `GroupJoinFlow`, `GroupRekey`, the new `GroupWireFrame` types, the invite trust root that `checkCharter` reads. |
| `:core:persistence` | The tables `group_secret` (the wrapped secret per group and epoch), `group_invite` (accepted invites, which are trust roots) and `group_join_request` (pending joins on admins and on the joiner). One schema step, coordinated with SW-6 and FA-4 (ripple 17). |
| `:core:engine` and the hosts | The adapter that wraps and unwraps the secret on each host. The gate is handed to calls and to the swarm. |
| `:core:network` | Nothing new. The `gs1` token rides the generic `caps` field of SW-2 part A. |

**If the owner wants membership as its own artifact later:**
- The split line is the package `...messaging.group`, moved to a module `:core:groups`.
- `:core:messaging` would then see it only through `GroupGate` and a `GroupMembershipExtension` port.
- **Not recommended now:** it adds a port layer and gives no benefit until a second consumer exists.

---

## 3. The reliability contract

These are the promises. Each one has tests in sections 7 to 9.

| # | Promise |
|---|---|
| **R1** | Every member that stays in the group gets the whole file, as long as every piece exists on at least one device that comes online again before the retention time ends (O-1). There is no attempt cap and no other time limit. |
| **R2** | The sender going offline never fails a download. Members keep exchanging what they hold. When nobody online has the missing pieces, the row says whom it is waiting for and continues by itself. |
| **R3** | What the sender delivered before leaving is spread as widely as possible. The sender serves pieces nobody holds yet first (4.1 Rule B), so "half the file uploaded" means half the file held by the group, not the same quarter held twice. |
| **R4** | No restart loses verified data. Any device, the sender included, can be killed, rebooted or updated and continues from what it had. |
| **R5** | The sender's cancel stops the transfer on every member, including members that were offline when it happened. They learn of it before they can ask anyone for a piece. |
| **R6** | Nothing unverified is written or served. Each piece is checked against its hash before it is written and again before it is served, and the whole file is checked at the end (ADR-068). |
| **R7** | Only current members are served. The gate is checked on every request, so a removed member is refused from its next request on. |
| **R8** | Nothing fails silently. Every stop has a reason, a sentence written for people (FA-3 style), and an automatic way back when one exists. |
| **R9** | With the swarm off, or with a peer that lacks it, behaviour is exactly today's. |

**What cannot be promised** (the UI and the docs must say so honestly):
- **Pieces that exist nowhere.** The sender deleted or changed the file before anyone held them, and no copy exists anywhere (E-19,
  E-20).
- **Frozen devices.** Devices that the OS freezes (Transsion HiOS, EXP-002) neither serve nor download while frozen.
- **Android 15+ service limit.** Android 15+ allows a `dataSync` foreground service six hours per 24 hours, then calls
  `Service.onTimeout`
  ([developer.android.com/develop/background-work/services/fg-service-timeout](https://developer.android.com/develop/background-work/services/fg-service-timeout)).
  After that the device pauses until the service may run again; the timer resets when the user brings the app to the
  foreground (E-30).
- **Retention.** The retention time can run out (O-1).
- **Hotspot client isolation.** Members cannot reach each other, so everything comes from the device that can reach them. It
  still works, only slower.
- **Members who join later.** A member who joins after the announcement does not get earlier files. That is today's rule too.

---

## 4. How the owner's scenarios work

### 4.1 The two rules that make "half sent" work
- **Rule A, on every receiver: pull, rarest first, and spare the sender.**
  1. A receiver asks for the pieces that the fewest connected devices hold, breaking ties at random.
  2. When another member holds a piece, the receiver asks that member, not the origin.
  3. The origin is asked only for pieces no other connected member holds.
- **Rule B, the origin offer policy (origin only; INV-7):**
  1. The origin keeps `copies[p]`, the number of members it knows hold piece `p` (from HAVE), and `pending[p]`, the requests
     for `p` it is serving right now.
  2. It serves requests for pieces with `copies[p] + pending[p] == 0` before anything else.
  3. While any such request waits, or while its slots are full, a request for a piece that already has a copy is answered
     `REJECT(ELSEWHERE, retryAfterMs)`.
  4. Two members are never given the same new piece at the same time.

  **Effect:** every byte the origin uploads is a byte the group did not have yet.

### 4.2 Walk-through: 100 pieces, origin A, members B, C, D
| Time | What happens | Pieces held |
|---|---|---|
| t0 | A announces the file (signed, with the root). B, C and D accept (or auto-accept), fetch the manifest from A and verify it against the root. | A: 100 |
| t1 | B, C and D request pieces. Rule B makes A give each of them different pieces. | |
| t2 | A has uploaded 50 **distinct** pieces, then goes offline. This is the half-sent case. | B 20, C 18, D 12, union 50 |
| t3 | B, C and D exchange. Each asks the others for the pieces of the 50 that it lacks. | B, C, D: 50 each |
| t4 | Every row reads "Waiting for A to come online, 50 of 100 MB here" (`WAITING_FOR_SENDER`). **Nothing is sent in a loop:** nobody online has the other 50, so nobody asks. | unchanged |
| t5 | A comes back: a session comes up and A's SUMMARY says it holds everything. Each member requests the missing 50 pieces, Rule B makes A give B, C and D different ones, and they exchange again. A uploads about 50 more pieces, not 150. | growing |
| t6 | All three finish. Each runs the whole-file check, then sends `HAVE_ALL`. A's bubble reads "Delivered to 3 of 3". | all 100 |

Variants that must also work (each is a simulator scenario in SW-5):
- **B was offline during t3.** B later gets the 50 pieces from C or D, and the rest from anyone.
- **A was killed or rebooted at t2 instead of leaving.** On restart A loads its origin record from the database and reopens the
  file (persisted URI permission). It checks the file is unchanged (size and modified time, and each piece is re-hashed when it is
  served), then serves again (E-22).
- **A deleted the file at t2.**
  1. B, C and D complete the 50 pieces they share.
  2. When A comes back and cannot read the file, it sends a signed `SOURCE_STATUS(LOST)`.
  3. Pieces that nobody has become impossible to get, and the rows end with "The sender's file is no longer available" (E-19).
  4. If A picks the same file again, the root matches and serving resumes (`SOURCE_STATUS(RESTORED)`, O-3).
- **A removed D from the group at t3.** D's next request is refused. D stops, deletes its partial file and shows "You are no
  longer in this group" (E-34).

### 4.3 Walk-through: the sender cancels
1. **A taps Cancel on its own bubble** and confirms "Cancel for everyone?". A signs a tombstone, stores it, stops serving, and
   sends `CANCEL` to every connected swarm member. The tombstone holds the group id, the root, the message id, reason `USER` and
   the time.
2. **Each member that receives it:**
   1. verifies the signature against **the origin key stored with the announcement** (INV-6);
   2. stores the tombstone;
   3. stops downloading and serving that content, and deletes its partial file;
   4. marks its row Cancelled ("Cancelled by A");
   5. answers `CANCEL_ACK`;
   6. forwards the tombstone to every connected member that has not acknowledged it.
3. **D was offline.** When D next connects to *any* member that holds the tombstone, that member's first swarm frame (`SUMMARY`)
   carries it. D applies it **before** it can send or answer any request for that content (INV-5).
4. **A member that already finished** keeps its file (O-2, recommended) but never serves it for this announcement again.
5. **A receiver that taps Cancel** cancels only for itself (local cancel). Nobody else is affected.
6. **Retention.** A tombstone is kept for the retention time plus 7 days, then dropped (O-1).
7. **What never creates a tombstone:** a system stop (service timeout, battery saver, process death) and a receiver's own cancel
   (INV-10).

### 4.4 Walk-through: a download stopped because the sender is offline
- The row never goes to Failed for this. It goes to `Queued` with wait reason `WAITING_FOR_SENDER`.
- It is moved on by **events, not timers**:
  - a session comes up with any device that holds a missing piece;
  - a HAVE or SUMMARY arrives;
  - the network comes back;
  - the app starts;
  - the 15-minute keepalive wake runs.
- Section 8 lists about 50 such conditions, each with its way back.

---

## 5. Architecture (target state)

### 5.1 Components
```text
:core:swarm  (KMP: android + jvm, explicitApi, package com.transfer.flash.core.swarm)
  model/    ContentRoot, SwarmManifest, ManifestBuilder, PieceMath, Bitfield, SwarmTombstone,
            SwarmWaitReason, SwarmRole, SwarmRejectReason, FileIdentity
  codec/    SwarmFrame (sealed), SwarmFrameCodec, ManifestCodec, SwarmStatement (canonical bytes to sign)
  engine/   SwarmEngine (sans-IO), SwarmEvent, SwarmCommand, SwarmConfig, PiecePicker, SourceSelector,
            ServePolicy (with the origin offer policy), RequestWindow (AIMD), Endgame, StrikeBook,
            WaitClassifier, SwarmSnapshot
  driver/   SwarmDriver (single coroutine actor), ports: SwarmTransport, SwarmGroupContext,
            PieceStorage, SwarmStateStore
  api/      FlashSwarm (public facade), FlashSwarmConfig, FlashSwarmStatus
  commonTest/  unit, property, golden-vector tests;  sim/ SwarmSimulator + scenarios (SW-5)

:core:engine
  commonMain/swarm/   SwarmHostBinding (the ONE place that wires the swarm into a host),
                      NetworkSwarmTransport, MessagingSwarmGroupContext, RoomSwarmStateStore,
                      SwarmTransferRowBridge
  commonMain/group/   GroupFileSender (SW-1; chooses swarm or today's push per member)
  commonMain/         MagicFrameRouter (SW-2)
  androidMain/swarm/  AndroidPieceStorage (ContentResolver file descriptors)
  jvmMain/swarm/      JvmPieceStorage (RandomAccessFile / FileChannel)

:core:persistence     SwarmContentEntity, SwarmTombstoneEntity, SwarmDao, one schema step
:core:messaging       additive GroupMedia fields, domain-separated swarm statements in GroupCanonical /
                      SignedGroups, insert the bubble from a swarm announcement, an announcement
                      listener port (GroupSwarmAnnouncementListener)
:core:network/common  `caps` HELLO field -> FlashDevice.features (SW-2)
```

### 5.2 Ports and the engine (proposed signatures; the ADR may rename them, the shape is binding)
```kotlin
// :core:swarm commonMain, package com.transfer.flash.core.swarm.driver
public interface SwarmTransport {
    /** Sends one encoded FSW1 frame. false = the session is gone; the driver turns that into PeerDown. */
    public suspend fun send(peerId: String, frame: ByteArray): Boolean
    /** Asks the host's connection planner for a session. The swarm never dials by itself (ADR-057 ceiling, DialBudget). */
    public fun requestSession(peerId: String)
    /** Connected peers and the features each advertised in its HELLO. */
    public val connectedPeers: StateFlow<Map<String, Set<String>>>
}

public interface SwarmGroupContext {
    /** The gate. True only when [peerId] may exchange content of [groupId] with this device now (INV-3). */
    public suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean
    public suspend fun isLocalActiveMember(groupId: String): Boolean
    /** Signs a domain-separated statement with this device's group key; null when this device cannot sign in [groupId]. */
    public suspend fun signStatement(groupId: String, statement: ByteArray): String?
    /** Verifies against [authorKey] (stored with the announcement), so a departed origin's cancel still verifies. */
    public fun verifyStatement(authorKey: String, statement: ByteArray, signature: String): Boolean
    public suspend fun authorKey(groupId: String, authorId: String): String?
    /** Emits a group id whenever that group's roster or trust changed. */
    public val membershipChanges: Flow<String>
}

public interface PieceStorage {
    public suspend fun openSource(uri: String): SourceHandle?               // origin: random-access read
    public suspend fun openPartial(key: String, size: Long): PartialHandle? // receiver: app-private .part file
    public suspend fun freeBytesFor(key: String): Long
    public suspend fun finalize(key: String, fileName: String, mime: String): FinalizeResult // move to the destination
    public suspend fun deletePartial(key: String)
}
public interface SourceHandle {
    public suspend fun readAt(offset: Long, into: ByteArray, length: Int): Int
    public suspend fun identity(): FileIdentity?   // size + last-modified; null when unreadable
    public fun close()
}
public interface PartialHandle : SourceHandle {
    public suspend fun writeAt(offset: Long, bytes: ByteArray, length: Int)
    public suspend fun sync()                      // bytes durable before bits are persisted (INV-4)
}

public interface SwarmStateStore {
    public suspend fun loadAll(): List<SwarmContentRecord>
    public suspend fun upsert(record: SwarmContentRecord)
    public suspend fun setBits(root: ContentRoot, groupId: String, bits: ByteArray, bytesDone: Long)
    public suspend fun putTombstone(tombstone: SwarmTombstone)
    public suspend fun tombstones(): List<SwarmTombstone>
    public suspend fun delete(root: ContentRoot, groupId: String)
    public suspend fun purgeExpired(nowMs: Long)
}

// :core:swarm commonMain, package com.transfer.flash.core.swarm.engine
public class SwarmEngine(config: SwarmConfig, localDeviceId: String, seed: Long) {
    /** No I/O, no clock, no coroutines; randomness only from [seed]. Same events -> same commands (INV-11). */
    public fun handle(event: SwarmEvent): List<SwarmCommand>
    public fun snapshot(): SwarmSnapshot
}
```

**Events** (each carries `nowMs`):
- **Content:** `Announced`, `Accepted`, `ManifestPart`, `PieceArrived(peer, root, index, verified)`, `PieceStored`,
  `PieceStoreFailed`, `PieceRead(ok | changed | gone)`, `FinalizeResult`, `SourceStatus`.
- **Peers and frames:** `PeerUp(peer, features)`, `PeerDown`, `Summary`, `Have`, `HaveAll`, `Request(peer, root, pieces,
  allowed)`, `Unrequest`, `Reject(reason, retryAfterMs)`, `Cancel(tombstone, signatureValid)`, `CancelAck`.
- **Local actions:** `LocalCancel(root, asOrigin)`, `LocalPause`, `LocalResume`, `TombstoneSigned`.
- **Environment:** `MembershipChanged(groupId, allowedPeers, localActive)`, `NetworkUp`, `NetworkDown`, `SpaceChanged(freeBytes)`,
  `SystemSuspend`, `SystemResume`, `ServingEnabled(bool)`, `CallActive(bool)`, `Tick`.

**Commands:**
- **Frames and sessions:** `Send(peer, frame)`, `RequestSession(peer)`.
- **Storage:** `ReadPiece(root, index, forPeer)`, `WritePiece(root, index, bytes)`, `SyncAndPersistBits(root)`,
  `Finalize(root)`, `DeletePartial(root)`.
- **Persistence and signing:** `PersistRecord(root)`, `SignTombstone(root)`, `PersistTombstone`.
- **Output:** `PublishRow(root)`, `Log(line)`.

**Rules:**
- The driver does all hashing, reading, writing and signing, and reports the result back as an event. CPU and disk work never
  run inside the engine.
- The driver computes `allowed` from `SwarmGroupContext.isPeerAllowed` at the moment each inbound request arrives, and passes it
  in the event. That way the gate is applied per request (INV-3) and stays testable inside the engine.

### 5.3 Wire summary (the final layout goes in `docs/protocol.md` in SW-0)
> **SW-0 (2026-10-04):** the final layout is `docs/protocol.md` "Group swarm wire FSW1 v1" and ADR-071, which govern over this summary. Four changes were made there: every frame except `SUMMARY` names its **group id** as well as its root (the gate is per group); `CANCEL_ACK` names the message id and `SOURCE_STATUS` carries the origin, the message id and a time `atMs` (tombstones are per announcement, see INV-5); FSW1 rides the WebSocket session only, because Android's data-channel frames are capped at 512 KiB (`DataChannelFraming.MAX_FRAME_BYTES`); and the manifest's exact byte layout is fixed (at most 524,341 bytes, 9 fragments).

- **Envelope:**
  - magic `"FSW1"` (4 bytes, disjoint from `"FLSH"` and `"PTT1"`);
  - `u8 version = 1`, `u8 type`, `u16 flags`, `u32 bodyLength`, then the body;
  - little-endian, like framing v2.
- **Carriage:**
  - sent as binary WebSocket frames through **the same `SecureBinaryFrameCodec` layer as transfer chunks**;
  - the magic is checked after decryption.

| Type | Name | Body | Notes |
|---|---|---|---|
| 1 | `SUMMARY` | groupId; list of (root, state `NONE/PARTIAL/ALL`, servingEnabled); full signed tombstones for shared groups | First swarm frame after a session comes up and after a membership change (anti-entropy). Tombstones are applied before anything else (INV-5). |
| 2 | `MANIFEST_GET` | root, fragment index | |
| 3 | `MANIFEST_PART` | root, fragment index, fragment count, bytes (≤ 64 KiB) | The whole manifest is verified against the root before use. |
| 4 | `HAVE` | root, run-length piece ranges | At most once per second per peer per root. |
| 5 | `HAVE_ALL` | root | Also means "I finished": the origin counts deliveries from it. |
| 6 | `REQUEST` | root, up to 64 piece indexes | |
| 7 | `PIECE` | root, index, bytes (one whole piece; the last piece may be shorter) | |
| 8 | `REJECT` | root, pieces or ALL, reason, retryAfterMs | Reasons: `BUSY`, `ELSEWHERE`, `UNKNOWN`, `GONE`, `NOT_MEMBER`, `CANCELLED`, `UNSUPPORTED`. |
| 9 | `CANCEL` | tombstone: groupId, root, originId, messageId, reason (`USER` / `DELETED`), cancelledAtMs, signature | Only the origin can produce one (INV-6). |
| 10 | `CANCEL_ACK` | root | Stops re-forwarding to that peer. |
| 11 | `SOURCE_STATUS` | root, status (`LOST` / `RESTORED`), reason (`DELETED` / `CHANGED` / `PERMISSION`), signature | Origin only. Not a cancel: members keep exchanging what exists. |
| 12 | `UNREQUEST` | root, pieces | Endgame loser, or a requester that no longer needs the pieces. |

**Limits** (anything over a limit is dropped and counted as malformed):
- **Piece size:** a power of two from 64 KiB to 1 MiB, and never larger than the largest binary frame the session already carries
  (read it in SW-3).
- **Piece count:** at most 16,384, so the manifest is at most 512 KiB plus a header.
- **Request size:** at most 64 pieces per `REQUEST`.
- **Bytes per requester:** outstanding requested bytes per requester stay within the serve budget (by profile).
- **Malformed frames:** after 3 malformed FSW1 frames from one peer within a minute, ignore that peer's FSW1 frames for 10
  minutes. **Never disconnect the session for this, because it also carries chat and calls.**

**Content id.**
- **Formula:** `root = SHA-256( "FSWM" ‖ u8 version ‖ u32 pieceSize ‖ u64 totalSize ‖ 32-byte whole-file SHA-256 ‖ u32 pieceCount ‖
  pieceCount × 32-byte piece SHA-256 )`.
- **The file name is not part of the root.** The same bytes under another name are the same content. The name lives in the
  signed announcement.
- **What is not swarmed:**
  - zero-length files (FA-6 keeps its visible Failed row through the legacy path);
  - files that would need more than 16,384 pieces at the largest allowed piece size.

  Both use the legacy path, and the limit is recorded.

**Announcement.**
- **New fields.** `GroupWireFrame.GroupMedia` gains optional fields:
  - `root` (hex), `pieceSize`, `swarm = 1`;
  - `rootSig`, the origin's signature over the statement
    `"flash-swarm-v1/announce" ‖ groupId ‖ messageId ‖ originId ‖ root ‖ sizeBytes ‖ fileName ‖ mimeType ‖ sentAt`.
- **Signatures are domain-separated.** Every swarm statement starts with its own prefix (`"flash-swarm-v1/announce"`, `"…/cancel"`,
  `"…/source"`). A chat-message signature can never verify as a swarm statement, and the reverse is also impossible (tested in
  SW-8).

### 5.4 Persistence (one schema step, SW-6)
- **`swarm_content`**, primary key `(root, groupId)`. Columns:
  - identity: `messageId`, `role` (`ORIGIN`/`RECEIVER`), `originId`, `originKey`;
  - file: `fileName`, `mime`, `totalSize`, `pieceSize`;
  - progress: `manifest` (BLOB, null until fetched), `bits` (BLOB), `bytesDone`;
  - status: `state` (`OFFERED`, `ACTIVE`, `PAUSED_BY_USER`, `COMPLETE`, `CANCELLED`, `FAILED`), `waitReason`, `failReason`;
  - local links: `localTransferId`, `sourceUri`, `sourcePersistent`, `partialKey`, `finalPath`, `identitySize`,
    `identityModifiedMs`;
  - origin's view: `deliveredTo` (TEXT, comma-separated device ids);
  - timestamps: `createdAtMs`, `lastProgressAtMs`, `expiresAtMs`.
- **`swarm_tombstone`**, primary key `(root, groupId)`. Columns: `originId`, `messageId`, `reason`, `cancelledAtMs`,
  `signature`, `receivedAtMs`, `expiresAtMs`.
  - **SW-0 (2026-10-04, ADR-072 rule 5):** use primary key **`(groupId, messageId)`**, with `root` as an indexed column. A tombstone cancels one announcement. Keyed by content, a replayed tombstone would cancel a later re-send of the same file, or another member's announcement of the same bytes. `swarm_content` stays keyed by `(root, groupId)`; it needs a way to know which announcements of it are live (SW-6 decides: a child table or a count).
- **Schema step.** The next `FlashDatabase.DATABASE_VERSION`: 6 → 7, unless FA-4 has taken 7 by then. Add it to
  `FlashSchemaSteps.ALL`, the one SQL list used by Android and desktop **[code: `FlashSchemaSteps.kt`]**, and export the schema
  JSON.

### 5.5 What the UI sees (no breaking API change)
- **Swarm rows are ordinary `FlashTransfer` objects.** The id is:
  - on a receiver, the per-recipient transferId from the announcement (exactly as today, so the bubble finds its row);
  - on the origin, the shared message id (exactly as today's sender bubble).
- **They appear in the same `activeTransfers` list the UI already observes,** through the bridge (SW-8 task 8).
- **State mapping**, with no new enum values (exhaustive `when`s elsewhere must not break):

  | Swarm situation | `FlashTransferState` |
  |---|---|
  | announced, not yet accepted | `Offered` |
  | downloading | `Transferring` |
  | any wait reason | `Queued` |
  | paused by the user | `Paused` |
  | final whole-file check | `Verifying` |
  | finished | `Completed` |
  | failed | `Failed` |
  | cancelled | `Cancelled` |
- **One additive field:** `FlashTransfer.waitReason: FlashTransferWaitReason? = null`, appended last. It is a **new** enum, so no
  existing `when` breaks. Precedent: `isEncrypted` and others were appended the same way.
- **Binary compatibility.** Appending a data-class parameter changes the constructor and `copy()` signatures for consumers that
  are already compiled, but source stays compatible. Note it in the release notes and bump the minor version.
- **Swarm details for the UI** (holders online, "delivered to k of n", "you can go offline now") come from
  `FlashSwarm.status(transferId)`, not from `FlashTransfer`.

### 5.6 Invariants (each must be pinned by a test)
| # | Invariant |
|---|---|
| INV-1 | **No relaying on demand.** A device serves only pieces it holds and has verified; it never fetches for someone else. |
| INV-2 | **Verify before write and before serve.** A piece is hash-checked before `writeAt`. A piece read from disk to serve is re-hashed before it is sent, which catches disk corruption and a changed source. |
| INV-3 | **Gate per request.** Every inbound `REQUEST`, `HAVE`, `SUMMARY` and `MANIFEST_GET` is checked against `isPeerAllowed` at that moment. No cached "allowed" may outlive a `membershipChanges` event. |
| INV-4 | **Durability order:** bytes written → `sync()` → bits persisted → `HAVE` sent. A bit is never persisted before its bytes are synced, and a `HAVE` is never sent before its bit is persisted. Batching is allowed: sync and persist at most every 1 s or every 16 pieces. |
| INV-5 | **Tombstone first.** On `SUMMARY`, tombstones are applied before any `REQUEST` is sent or served for that root. A tombstoned root is never requested, served or re-announced. **SW-0 (2026-10-04, ADR-072 rule 5):** a tombstone cancels the announcement `(groupId, messageId)` it names; the content `(groupId, root)` counts as tombstoned only when **every** announcement of it in that group is. |
| INV-6 | **Only the origin cancels.** A tombstone or `SOURCE_STATUS` is applied only if its signature verifies against the origin key stored with the announcement. |
| INV-7 | **The origin offer policy** (4.1 Rule B). |
| INV-8 | **Cross-group privacy.** Root X is served to a member of group G only if this device holds X's announcement *in G*. Otherwise the answer is `REJECT(UNKNOWN)`, the same answer as for unknown content. |
| INV-9 | **No wait without a way back.** Every wait reason has at least one event that re-evaluates it (table in 8.1). |
| INV-10 | **A system stop is never a cancel.** Only a user action on the origin, or the origin's Delete for everyone, creates a tombstone. |
| INV-11 | **Determinism.** The engine has no clock; randomness comes only from the seed; no output depends on hash-map iteration order. |
| INV-12 | **Bounded memory.** In-flight requested bytes stay within the profile budget, queued serve bytes stay within the serve budget, and the manifest stays within 512 KiB plus a header. |

### 5.7 The group gate and membership (track GM)
**One predicate for all v2 group traffic.** Today four predicates in `RealFlashChatRepository` overlap, and one of them requires
pairing:
- `isActiveTrustedMember`: paired and active;
- `isActiveGroupMember`;
- `isGroupCallPeer`;
- `isGroupCallMember`.

GM-5 puts them behind one interface. Proposed signatures; the ADR may rename them, the shape is binding:
```kotlin
// :core:messaging commonMain, package com.transfer.flash.core.messaging.group
public enum class GroupTraffic { ROSTER, CHAT, CALL, FILE_SEND, FILE_RECEIVE, FILE_SERVE }

public interface GroupGate {
    /** May the live session of [peerId] exchange [kind] traffic of [groupId] with this device right now? Read per frame/request. */
    public suspend fun allows(groupId: String, peerId: String, kind: GroupTraffic): Boolean
    /** Is [deviceId] somebody this group's traffic is addressed to? Needs no live session (who to dial and invite). */
    public suspend fun isMember(groupId: String, deviceId: String): Boolean
    /** Emits a group id whenever its roster, its epoch or a member's trust changed. */
    public val changes: Flow<String>
}
```

**The rule for a v2 group (`g2-`).** `allows` is true only when all of these hold:
1. this device's own roster row is active (it has not left and was not removed);
2. the peer's roster row is active;
3. **one of:**
   - the peer is paired with this device (today's `isTrustedPeer`), **or**
   - the key of the peer's live TLS session equals the key its roster certificate names (`SignedGroups.isVouchedMember`).

   How the certificate came to exist does not matter: the owner after pairing, an admin after a secret join, or a vouch.
4. **for `FILE_SERVE` only:** the group setting "swarm serving" is on, and the device-local preference "serve files to this group"
   is on. Both default to on until GM-9 exists.

`isMember` is rules 1 and 2, plus "the roster names a key for the peer, or it is paired". This is today's `isGroupCallMember`.

**Legacy groups (`g-`)** keep today's rule, unchanged: paired and active (D5).

**What each existing predicate becomes (GM-5):**
| Today | Becomes | Behaviour change |
|---|---|---|
| `isActiveGroupMember` (text, receipts, reads, deletes, typing, sync) | `allows(CHAT)` | None: it is the same rule. |
| `isGroupCallPeer` | `allows(CALL)` | None. |
| `isGroupCallMember` | `isMember` | None. |
| `isActiveTrustedMember` on a v2 group (`GroupMedia` receive, `beginGroupAttachment`) | `allows(FILE_RECEIVE)` / `allows(FILE_SEND)` | **Yes:** a member that is not paired with the sender now receives files (the owner's requirement). |
| The recipient filter in `sendGroupAttachment` (search `files are paired-only`) | `isMember` | **Yes:** same as above. |
| `onBundle` (roster) | `allows(ROSTER)` is **not** used. A bundle is believed by its signatures, not by who relayed it (today's rule: "sender-not-member"). | None. |
| The swarm's `isPeerAllowed` (SW-8) | `allows(FILE_SERVE)` for serving and `allows(FILE_RECEIVE)` for fetching | New. |

**Membership invariants (each pinned by a test):**
| # | Invariant |
|---|---|
| GINV-1 | **The secret never leaves the device except in two ways:** inside a live TLS session to a peer that passes `allows(CHAT)` at that moment (`GsSecret`, GM-6), and in an invite the user chose to share. It is never in any log, crash report, chat text or notification. |
| GINV-2 | **The device key is the identity.** No frame is accepted from, or sent to, a peer because it knows the secret. Only the roster and the live key decide (5.7 rule 3). |
| GINV-3 | **A charter is trusted through pairing with its owner or through an invite this device accepted.** Never through a bundle alone: an unknown group cannot be pushed onto a device. |
| GINV-4 | **Every removal is followed by a rotation.** The device that signs the removal also signs the rotation notice, in the same database transaction. A crash in between is caught on the next start, which rotates again. |
| GINV-5 | **A previously removed key is never approved automatically.** Under the "open" policy, a join from a key that this group tombstoned waits for an admin's explicit approval. |
| GINV-6 | **Per frame, not per session.** `allows` is read for each inbound frame and request, and no cached answer outlives a `changes` event. The ERROR-088 rule stays: a frame is checked when it is sent or received, not when the session started. |
| GINV-7 | **The proof is bound to both live TLS keys** (design 1.3): a relay holding two sessions cannot complete it. |
| GINV-8 | **The 1:1 world is untouched** (D4). A peer that is only a group member never becomes a 1:1 contact, a pairing, a PTT recipient or a 1:1 caller. |

**Rotation without sealing (changes design 1.6, recorded in the membership ADR).**

*Design 1.6 said:* an admin seals the new secret to every member's key (ECDH + HKDF + AES-GCM), and anyone relays the sealed
bundle.

*This plan does instead:*
1. **The rotation notice.** The admin signs a **rotation notice**:
   `GroupRotation(groupId, newEpoch, prevEpoch, commit, removedIds, reason, adminId, rotationId, signature)`.
   - The notice contains **no secret**.
   - `commit = SHA-256("flash-gs-commit-v1" ‖ groupId ‖ u32 newEpoch ‖ secret)`, so a receiver can check a secret it is
     handed.
2. **The handover.** The secret itself moves only as a `GsSecret` frame inside a live TLS session, from any member that holds it
   to a peer for which `allows(CHAT)` is true right now. That is an active roster member whose live key is its certified key.

**Why this replaces sealing:**
- **Every member holds the secret anyway.** Sealing only hides it from the members that relay it, and they already have it.
- **Sealing needs key agreement with each member's identity key.** On Android that key lives in the Keystore. Whether a Keystore
  key can do key agreement on every supported API level was **not verified** (it may need `PURPOSE_AGREE_KEY`, API 31+; Flash's
  minSdk is 24). Sealing would otherwise force a second, per-device agreement key.
- **The removed device never gets the new secret.** The notice travels with the removal tombstone, so a device that holds the new
  epoch also knows about the removal, and its gate refuses the removed device.

**What a member that missed the rotation can still do (O-13):**
- it chats, calls and exchanges files normally;
- it cannot share a valid invite until it holds the current secret;
- it asks any connected member for the secret (`GsSecretRequest`).

---

## 6. Ripple map: how the swarm and membership touch the rest of Flash

Every row is a place where a careless change breaks something that works today. The phase column says where it is handled.
**[code]** marks facts read in the repository on 2026-10-04; search for the symbol, line numbers drift.

| # | Area | Today [code] | Swarm effect and the rule | Phase |
|---|---|---|---|---|
| 1 | Group send loop | Duplicated in `MainActivity.kt` (`onSendFile`, search `beginGroupAttachment`, ~1446) and `DesktopShell.kt` (~639/662): `beginGroupAttachment` → `sendFile` per member → `sendGroupAttachment`. | One shared `GroupFileSender` must make the swarm-or-push choice. Move the loop first with **no** behaviour change. | SW-1 |
| 2 | Sender's bubble cancel | `MainActivity` `onCancelTransfer` (~1666) and `DesktopShell` (~1124) map the sender's bubble to every recipient transfer through `getRecipientTransferIds`. | For swarm content, the origin's bubble cancel becomes `FlashSwarm.cancelAsOrigin` (cancel everywhere, with a confirmation). A receiver's bubble cancel stays local. | SW-9 |
| 3 | **Foreground-service timeout** | `FlashBackgroundService.onTimeout` (~111-121) calls `cancelTransfer` on every `Transferring` transfer. | **Would become a group-wide cancel.** SW-2 adds "pause for the system". Swarm rows get `SystemSuspend` and `WAITING_FOR_SYSTEM`. O-7 decides whether 1:1 transfers change too. | SW-2, SW-9 |
| 4 | Notification Cancel action | `FlashBackgroundService.onStartCommand` `ACTION_CANCEL_TRANSFER` (~81-86) calls `cancelTransfer`. | A user action. On an **origin** swarm row it cancels for everyone, so the notification button must read "Cancel for everyone". On a receiver row it is local. | SW-9, SW-11 |
| 5 | Every other `cancelTransfer` caller | `MainActivity` ~1673, 1676, 1936, 2246; `DesktopShell` ~1124, 1127, 1360, 1608; the interop harness. | Classify each one as USER or SYSTEM in SW-2 (a table in the progress log). The bridge routes swarm ids to the swarm. | SW-2, SW-8 |
| 6 | Receiving `GroupMedia` | `RealFlashChatRepository` (~2236-2252) parks the frame **in memory** (`pendingGroupMedia`, ~2319) until a FILE_START arrives (~1857); only then is the bubble created. | A swarm announcement has no FILE_START. The bubble must be inserted **from the announcement** and persisted at once, so a restart cannot lose it. Without the swarm, keep today's path. | SW-8 |
| 7 | Who may receive group files | `beginGroupAttachment` requires `isActiveTrustedMember`, meaning paired and active (~2836). `isGroupPeerTrusted` (~2848) also accepts vouched members with a live key. | ~~The swarm gate is roster-active plus `isGroupPeerTrusted` (O-5), so vouched members get files. That closes the "vouched members get no files" gap (AGENTS 29, FO-04). The legacy push keeps its own rule.~~ **Updated 2026-10-04 (O-5 replaced):** the swarm uses the group gate of GM-5 / ADR-075 (`allows(FILE_SERVE)` and `allows(FILE_RECEIVE)`), and the push path for v2 groups switches to the same gate in GM-5, so every member gets files whether or not it is paired. Legacy `g-` groups keep the paired rule. | GM-5, SW-8 |
| 8 | Accept gate (#5) | `Flash.kt` `requireReceiverAcceptance = true`; `FlashConfig.autoAcceptIncoming` defaults to false; the bubble's Accept/Decline (`onAcceptOffer`, ~1610). | A swarm row starts `Offered` unless auto-accept is on. Nothing is fetched before Accept, and the origin is never parked (pull model). Decline is a local cancel. | SW-8 |
| 9 | FA-2 space gate (ADR-069) | A receive that cannot fit is refused by name (`TransferFailureText.notEnoughSpace`). | Checked at Accept and before each start. If space can be freed, the swarm uses `WAITING_FOR_SPACE` instead of a final failure (E-23). | SW-7, SW-10 |
| 10 | FA-6 empty file | An empty file is a visible Failed row. | Zero-size content is never swarmed; `GroupFileSender` keeps the legacy path for it. | SW-8 |
| 11 | ADR-068 whole-file check | Every host checks the whole received file; a mismatch fails the transfer on both ends. | The manifest carries the whole-file SHA-256, checked during `Verifying`. On a mismatch: re-verify every piece, re-fetch the bad ones once, then Failed with the ADR-068 wording (E-14). | SW-4, SW-7 |
| 12 | Reconnect resume of 1:1 | `TransferReconnectResumePolicy` makes the sender resume outbound 1:1 transfers when a session comes up. | Swarm rows must be **excluded** from its input, or it would call `resumeTransfer` on them. Pull is receiver-driven and needs no such policy. | SW-8 |
| 13 | `FlashTransferState` `when`s | Exhaustive `when`s in `ui:chat`, the app and desktop. | No new enum values (5.5). `waitReason` is a new enum. | SW-8 |
| 14 | Three composition roots | `handleInboundBinary` in `Flash.kt` ~887, `DiscoveryEngineHolder.kt` ~2071, `DesktopEngine.kt` ~1334: PTT magic first, then decryption, then the transfer route. Android's data-channel path (`Flash.kt` ~344) goes through the same function. | All three call `MagicFrameRouter` after decryption and before the transfer route. "FSW1" is **always recognised and dropped** when no handler is registered, so a stray frame never reaches the transfer pipeline. | SW-2 |
| 15 | HELLO capability | `ping` and `gv` fields: client and server hello in `WsFlashNetwork.kt` and `JvmWsFlashNetwork.kt` (four places), parsed into `FlashDevice.groupProtocol`. | A generic `caps` field in all four places, `FlashDevice.features`. When the set is empty, the HELLO bytes must be unchanged (golden test). | SW-2 |
| 16 | Desktop persistence | `DesktopEngine.kt` ~688: `RealFlashTransferRepository(store = null)`, so desktop 1:1 rows do not survive a restart. The chat database is opened ~784. | The swarm store on desktop uses that same `FlashDatabase` (which gains the swarm tables), so desktop swarm rows **do** survive a restart. Desktop 1:1 persistence stays a separate item (D5 = C, 09B-2). | SW-6, SW-8 |
| 17 | Database migrations | `FlashSchemaSteps` (one SQL list for both platforms), `FlashSchemaStepsTest`, `FlashJvmMigrationsTest`. FA-4 also wants a schema step. | Whichever lands first takes version 7. Never edit a step that is already committed. | SW-6 |
| 18 | Connection planner | Session ceiling 24 with `DialBudget` (ADR-057); ECO parks sessions (ADR-040). | The swarm never dials; it calls `requestSession`, which goes to the planner. At most 4 to 6 data peers per content. **In ECO a device does not serve (D9) but still downloads** from connected holders (reliability). | SW-8 |
| 19 | Calls | Group calls share the Wi-Fi; there is a per-leg voice-priority governor (ADR-066). | During an active call, the swarm drops to 1 serve slot and halves its windows; it restores them when the call ends. The host supplies `isCallActive`, as for PTT. Measure it (`MEAS-*`). | SW-8 |
| 20 | Background work | Service types `connectedDevice|dataSync`; Android 15+ six-hour `dataSync` limit; 15-minute `FlashKeepaliveWorker`; Transsion freezer. | Downloading rows keep the foreground service, and so does an origin while it is the only holder of a piece someone still needs. Serving after completion does **not** keep it (battery) unless the device setting "keep available" is on. The 15-minute wake re-evaluates waits. | SW-9, SW-10 |
| 21 | Removal ripple | ADR-044 V2 (1701fc3): a removed device is told on reconnect; a device that is out cannot send or call. | `membershipChanges` reaches the engine. A removed device stops its own downloads, deletes its partial files and shows text. Others stop serving it from its next request (INV-3). | SW-4, SW-9 |
| 22 | Delete for everyone | `deleteMessageForEveryone` (~3630). | When the origin deletes its own file message for everyone, it also cancels the swarm (tombstone reason `DELETED`). | SW-9 |
| 23 | Group catch-up | ADR-059 re-delivers missed messages, announcements included. | A late announcement is handled like a fresh one. If a tombstone already exists, the bubble shows Cancelled at once. | SW-8 |
| 24 | Notifications | One per transfer. | One per downloading swarm row; none for serving; the origin's says "Cancel for everyone". | SW-11 |
| 25 | Publication | `jitpack.yml` publishes 14 modules; the README lists the published modules. | Add `:core:swarm` (15 modules) and do the release dry-run (run the JitPack install line before tagging). The sample consumers must still build and run without attaching. | SW-8 |
| 26 | Logging | Structured tags (AGENTS 24). | New tag `SWARM`. Never log full signatures, key material or file contents. | all |
| 27 | Golden vectors | Framing v2 vectors. | They must stay green; FSW1 gets its own vectors. | SW-3 |
| 28 | Legacy `g-` groups | Up to 6 members, forgeable membership (ERROR-082). | No swarm (O-4): announcements there cannot be signed. | SW-8 |
| 29 | Co-owners / successor | ADR-063. | No effect: only the origin's own key cancels. An owner removing a member is the gate's business (row 21). | — |
| 30 | Performance mode | `FlashPerformanceMode` LOW / MEDIUM / HIGH. | Sets the serve slots (1/2/4) and the in-flight byte budget (8/16/32 MiB, to be tuned by `MEAS-*`). | SW-4 |

**Membership ripples (track GM, added 2026-10-04 after the owner's decisions):**

| # | Area | Today [code] | Membership effect and the rule | Phase |
|---|---|---|---|---|
| 31 | Trusting a group's charter | `GroupSignatureRules.checkCharter` refuses `owner-not-paired` / `owner-key-pin` when this device is not paired with the owner. It already checks `id-derivation` (the group id commits to the owner key). | Add a second trust root: an accepted invite for exactly this group id (GINV-3). Keep the pairing root. **Never** weaken the check for a group id with no accepted invite. Test both roots and the refusal. | GM-4 |
| 32 | Creating a group | `createGroup` refuses unless every invitee is paired ("Every group member must be trusted", two places, search `memberIds.any { !isTrustedPeer(it) }`). | Keep that path (the owner can still add paired contacts directly). Add "create, then share the invite": a v2 group with only the creator is valid. Check `GroupPolicy.validMemberIds` (it requires 2 or more members today) and change it only for the invite path. | GM-4, GM-10 |
| 33 | Adding members, roles | `SignedGroups.addMembers` (owner or admin); a role check with `isTrustedPeer(member.deviceId)` near the group-member mapping (search `member.role == "owner" \|\| isTrustedPeer`). | Read and classify that check in GM-5: it decides who is shown or trusted, so it must use the gate. Approving a join is a new `addMembers`-like operation signed by an admin (owner or co-owner, ADR-063). | GM-4, GM-5 |
| 34 | Files to members not paired with the sender | Sender: `beginGroupAttachment` uses `isActiveTrustedMember`; `sendGroupAttachment` filters recipients by `isTrustedPeer`. Receiver: the `GroupMedia` branch uses `isActiveTrustedMember`. | v2 groups switch to the gate (5.7). Legacy groups keep the paired rule. The FILE_START that follows a `GroupMedia` matches the parked frame as today. | GM-5 |
| 35 | App-layer encryption of binary frames | `SecureBinaryFrameCodec` uses the pairing session key and otherwise "falls back to plain FLSH frames (opportunistic encryption)". `isChannelEncrypted` is true only with a session key. | A peer that is only a group member has no pairing session key. Its file chunks (legacy push and FSW1) are protected by the TLS session only, like vouched members today. Record this in `docs/security.md`. No group session key is planned (section 11). | GM-5, SW-0 |
| 36 | The 1:1 world | `isTrustedPeer` gates 1:1 chat, 1:1 calls (`CallCoordinator` `isTrustedPeer`), PTT (`PttSessionEngine`, whose recipients are connected **paired** peers) and pairing. | **Unchanged (D4, GINV-8).** Test that a 1:1 frame from a group-only peer is dropped, that it never becomes a PTT recipient, and that a 1:1 call from it is refused. | GM-5 |
| 37 | Dialing the inviter (named-dial TOFU trap) | Naming an unpinned id in `connectManual` pins whoever answers. | The invite carries the inviter's key fingerprint. The first dial is accepted only if the answering key matches it, and nothing is pinned before the match. A mismatch is M-04. | GM-4 |
| 38 | Pin conflicts | `installVouches` refuses a vouch with `CONFLICT_PAIRED` or `CONFLICT_VOUCHED`, and that member stays out of the roster on this device. | Unchanged rule. Show it (M-11) instead of failing silently. | GM-4, GM-10 |
| 39 | Session ceiling | 24 sessions (ADR-057), `DialBudget` in crowds. | Group-only members go through the planner like vouched members. No new dialing path. | GM-5 |
| 40 | Old builds in a secret group | A device without `gs1` cannot accept an invite. | It still accepts roster bundles for groups it already holds (its own membership came through pairing with the owner), so it trusts secret-joined members through vouching. **Test this explicitly with today's `onBundle`:** a cert signed by an admin, for a subject this device never paired with. | GM-4 |
| 41 | Existing v2 groups | They have no secret. | GM-7 gives them one when an admin device on the new build is online (O-11). Until then, they work exactly as today. | GM-7 |
| 42 | Removal, leave, successor | `removeMember`, `leave(successorId)` (ADR-063), `removalNoticeFor` (offline removed device is told). | Each removal adds a signed rotation notice and a new secret (GINV-4, 5.7). A leave does **not** rotate unless an admin chooses "Change group code" (the leaver left voluntarily; recommended in the ADR). Co-owners are admins and may approve and rotate. | GM-6 |
| 43 | History for new members | A new v2 member asks for catch-up on join (`requestGroupCatchUp`, ADR-059). | A secret-joined member gets the same catch-up (O-15: keep today's behaviour). | GM-4 |
| 44 | The secret at rest | The identity key is stored per host (Android Keystore / desktop store). | Wrap the group secret the same way per host. Check the Android backup and data-extraction rules so the secret is excluded from backups. Never log it (AGENTS 24). | GM-2 |
| 45 | Notifications | None for groups except messages and calls. | A join request notifies admins once per request (collapsed per group). The joiner gets no notification spam while it waits. | GM-10 |
| 46 | Call legs after a removal | Documented gap: "a call leg already established is not re-checked after a removal" (AGENTS 29). | On `GroupGate.changes`, the call layer re-checks `allows(CALL)` for each live leg of that group and drops the legs that fail (`GSEC-08`). | GM-5 |
| 47 | Discovery records | DR1 to DR5, the JmDNS empty-TXT trap. | The beacon (GM-8) is optional. Without it, a newcomer finds the group through the inviter's address hint, and members find each other as today. | GM-8 |

---

## 7. Phases

### 7.0 Order, size and status (update this table when a phase finishes)
Sizes are for ordering only: S = days, M = weeks, L = more than a month.

There are two tracks:
- **7A, the swarm (SW):** below.
- **7B, membership (GM):** after SW-12.

SW-0 writes the ADRs for both tracks. The tracks meet at **GM-5, the group gate**: SW-8 needs it, so the first swarm build
already serves members that are not paired with anyone.

| Phase | Name | Size | Depends on | Can run alongside | Status |
|---|---|---|---|---|---|
| SW-0 | ADRs (swarm **and** membership), protocol sections, owner decisions | S | owner answers (given 2026-10-04; O-11..O-15 default unless overruled) | — | **DOCS WRITTEN 2026-10-04:** ADR-070..ADR-075 PROPOSED, both protocol sections, security section 10, UI-053..055 reserved. **Awaiting the owner's acceptance of the six ADRs** (exit criterion) |
| GM-1 | Membership crypto primitives (`:core:security`) | M | SW-0 | SW-1..SW-7, GM-5 | TODO |
| GM-2 | Invite format and secret storage | M | GM-1 | SW-*, GM-5 | TODO |
| GM-3 | Proof exchange, `gs1` capability, membership frames | M | GM-1, SW-2 part A | SW-*, GM-5 | TODO |
| GM-4 | Joining: invite → proof → request → approval → certificate | L | GM-2, GM-3 | SW-* | TODO |
| GM-5 | **The group gate** (and files to members not paired with the sender) | M | SW-0 | GM-1..GM-4, SW-1..SW-7 | TODO |
| GM-6 | Removal → rotation (notice + secret handover), "Change group code" | M | GM-4 | SW-* | TODO |
| GM-7 | Give existing v2 groups a secret (O-11) | S | GM-6 | SW-* | TODO |
| GM-8 | Finding members: address hint, optional beacon | S | GM-4 | SW-* | TODO |
| GM-9 | Group settings (signed and device-local) | M | GM-4 | SW-* | TODO |
| GM-10 | Membership UI (UI doc first) | M | GM-4, GM-6, GM-9; its UI doc DESIGNED | SW-* | TODO |
| GM-11 | Membership device checks | M (owner's device time) | GM-10 | SW-12 | TODO |
| SW-1 | Shared group sender (refactor, no behaviour change) | S | SW-0 | SW-3, SW-4, SW-5 | TODO |
| SW-2 | Seams: `caps`, magic router, user vs system stop | M | SW-0 | SW-3, SW-4, SW-5 | TODO |
| SW-3 | `:core:swarm` module, model, manifest, codec | M | SW-0 | SW-1, SW-2 | TODO |
| SW-4 | Sans-IO engine | L | SW-3 | SW-1, SW-2, SW-6, SW-7 | TODO |
| SW-5 | Simulator and scenarios | M | SW-4 (grows with it) | SW-6, SW-7 | TODO |
| SW-6 | Persistence | M | SW-3 | SW-4, SW-5, SW-7 | **DONE (2026-10-04)** (Room schema v7, migration step 6_7, RoomSwarmStateStore) |
| SW-7 | Storage I/O | M | SW-3 | SW-4, SW-5, SW-6 | **DONE (2026-10-04)** (PieceStorage, SourceHandle, PartialHandle, JvmPieceStorage, AndroidPieceStorage, picker persistable) |
| SW-8 | Driver and host integration, behind a switch | L | SW-1, SW-2, SW-4, SW-5 exit, SW-6, SW-7, **GM-5** | — | **DONE (2026-10-04)** (Driver actor, SwarmHostBinding, MagicFrameRouter FSW1, RealFlashTransferRepository merge, 3-engine SwarmInteropTest) |
| SW-9 | Cancel everywhere and lifecycle | M | SW-8 | SW-11 | **DONE (2026-10-05)** (Origin cancel confirmation, tombstone verify/propagate, delete-for-everyone, TimeoutStopPlan suspend/resume, retention cleanup) |
| SW-10 | Errors and recovery (the catalogue) | M | SW-9 | SW-11 | **DONE (2026-10-05)** (Wake-up wiring across hosts, TransferFailureText friendly sentences, SwarmWaitReasonRecoveryTest for all 7 wait reasons, catalogue verified) |
| SW-11 | UI | M | SW-8; its UI doc must be DESIGNED | SW-9, SW-10 | **DONE (2026-10-05)** (UI-055 research doc IMPLEMENTED, FlashSwarmUiMath receiver/sender formatters, FlashFileMessageCard bubble detail lines & safe-to-leave styling, FlashTransfersScreen swarm status lines, notification origin cancel & detail lines, settings toggles, all unit tests green) |
| SW-12 | Device checks, tuning, default-on decision | M (owner's device time) | SW-10, SW-11 | — | TODO |

---

## 7A. Track SW: the swarm

SW-0 is shared: it writes the ADRs and protocol sections of both tracks.

### SW-0: ADRs, protocol, decisions (docs only)
- **Goal:** every contract is on paper and accepted before any code.
- **Why:** AGENTS 8 and 17. The wire must not emerge from code.
- **Depends on:** the owner's answers (section 10). Given on 2026-10-04. O-11 to O-15 use the recommended default unless the owner
  overrules them before this phase ends.
- **Read first:**
  - this plan, sections 1 to 6;
  - `GROUP-SWARM-DESIGN.md` sections 1 to 4 and 11;
  - `docs/protocol.md` (framing v2, group frames, `GroupMedia`);
  - ADR-024, ADR-033, ADR-044, ADR-057, ADR-058, ADR-059, ADR-068 and ADR-069 in `docs/decisions.md`.
- **Tasks:**
  1. Record the owner's answers in section 10 of this plan, with the date.
  2. Write six ADRs, using the next free numbers (ADR-070 was the next free one on 2026-10-04; check with
     `grep "^## ADR-" docs/decisions.md | tail -1`):
     1. **"Group swarm as an optional module":** section 2, the dependency rules, attach, `caps`, the fallback, O-6.
     2. **"Swarm wire FSW1, content id and announcement":** 5.3, the limits, domain-separated statements, golden vectors to come
        in SW-3.
     3. **"Swarm reliability: origin offer policy, wait reasons, cancel everywhere, retention":** sections 3, 4, 5.6 and 8.1.
     4. **"Group membership by group id + secret (reverses ADR-044's rejection of a group-wide secret)":**
        - design 1.2 to 1.7, with this plan's 1.3, 5.7 and O-13 replacing design 1.5 condition 1;
        - GINV-1 to GINV-8;
        - the invite as a trust root (ripple 31);
        - join policies (D2 = admin approves by default);
        - rotation on every removal: the signed rotation notice and the secret handover (5.7, which replaces design 1.6's sealing);
        - what is **not** provided: no forward secrecy, removed members keep what they received, chunks to group-only peers are
          protected by TLS only (ripple 35).

        Append "Superseded in part by ADR-0xx" to ADR-044. Do not edit its text.
     5. **"Group settings":** design 2.1 (signed vs device-local), versioning, who may change what.
     6. **"The group gate":** 5.7, including the mapping table and the per-frame rule.
  3. **`docs/protocol.md`:**
     - Add a section "Swarm wire FSW1 v1" with the byte layout of every type in 5.3, the limits, the reject reasons, the version
       rule ("an unknown type is ignored; an unknown version gets `REJECT(UNSUPPORTED)`") and the new `GroupMedia` fields.
     - Leave the vectors as "TODO SW-3".
  4. **Check how `GroupMedia` is decoded** (search `GroupWireFrame` codec / `decode`). Find out whether unknown fields are ignored
     and record the answer in the protocol section.
     - If they are **not** ignored, the origin must send the old frame (without the new fields) to members without `sw1`.
       Record that in the ADR.
  5. **`docs/security.md`, threat rows:**
     - **Swarm:** forged cancel, forged announcement, cross-group oracle, request flood, corrupt piece, removed member, replayed
       tombstone, hostile manifest sizes, malformed-frame flood.
     - **Membership:** leaked invite (per join policy), relay during the proof, replayed or reflected proof, offline guessing of
       a weak secret (why the app generates 256 bits), stale epoch, removed member rejoining, forged or replayed rotation notice, a handed secret that does not match its commit,
       join-request flood, an unknown group pushed onto a device, group-only peers without app-layer chunk encryption.

     Each row names its invariant (INV-x or GINV-x).
  5a. **`docs/protocol.md`, membership:** a section "Group membership v1" with:
     - the invite format (GM-2);
     - every new `GroupWireFrame` type (GM-3, GM-4, GM-6, GM-9), with field limits;
     - the proof transcript bytes;
     - the rotation notice and `GsSecret` layouts;
     - the `gs1` token;
     - golden vectors, left as "TODO GM-1/GM-2".
  6. **`docs/architecture.md`:** the layer diagram from section 2.
  7. **`GROUP-SWARM-DESIGN.md`:** add "Superseded by `GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2" notes at D10, D11, D12, 4.4 and 4.8,
     and "Changed by plan 1.3 / O-13" at 1.5 condition 1. **Do not delete their text.**
  8. **The UI ids.** Reserve UI ids in `docs/ui/ui-research-index.md` for:
     - the group settings sheet;
     - the invite and join flow;
     - the swarm availability UI (SW-11).

     Check the next free id there; UI-053 was the next free one on 2026-10-03.
- **Exit criteria:** the owner has accepted the six ADRs, and both protocol sections exist (vectors pending).
- **Do not:** write Kotlin, or edit the text of accepted ADRs (append "Superseded in part by ADR-0xx" lines only).
- **Rollback:** not applicable.
- **Status (2026-10-04): docs written, awaiting the owner.**
  - Task 1: section 10 carries the answers and the date. Task 2: ADR-070 to ADR-075 written as PROPOSED; ADR-044 has the "Superseded in
    part by ADR-073" line. Tasks 3, 4 and 5a: `docs/protocol.md` "Group swarm wire FSW1 v1" and "Group membership v1" (vectors TODO SW-3,
    GM-1, GM-2); the task 4 answer is recorded there and in ADR-071 rule 10 (old builds ignore unknown keys, so the new fields are safe,
    and they are sent only to `sw1` peers). Task 5: `docs/security.md` section 10. Task 6: `docs/architecture.md`. Task 7: notes in
    `GROUP-SWARM-DESIGN.md` at 1.2, 1.5, 1.6, 4.4, 4.8 and D10 to D12. Task 8: UI-053, UI-054 and UI-055 reserved.
  - **Refinements made while writing the contracts** (each also noted where it applies): tombstones are per announcement (INV-5, 5.4);
    every FSW1 frame names its group (5.3); FSW1 rides the WebSocket only (5.3); a proof responder does not reveal membership (GM-3 task 6);
    a secret is never handed to an id named in a rotation notice (GM-6 task 3); the group name is not a v1 setting (GM-9).
  - **Exit criterion not yet met:** the owner has not accepted the ADRs. No code may start before that.

---

### SW-1: Shared group sender (refactor, no behaviour change)
- **Goal:** one function sends a file to a group for both hosts, behaving exactly as today.
- **Why:** the swarm-or-push choice must exist in one place. Today the loop is duplicated, so a fix to one copy is easily missed
  in the other (ripple 1).
- **Depends on:** SW-0.
- **Read first:**
  - `MainActivity.kt` `onSendFile`, the group branch (search `beginGroupAttachment`);
  - `DesktopShell.kt`, the same (search `beginGroupAttachment`);
  - in `RealFlashChatRepository`: `beginGroupAttachment`, `sendGroupAttachment`, `getRecipientTransferIds`;
  - `FlashTransferRepository.sendFile`.
- **Ripple:**
  - the sender's bubble is keyed by the shared message id, with `transferId = messageId`;
  - each recipient gets a **distinct** transferId and the **same** `wireFileId`;
  - members whose `beginGroupAttachment` returns false are skipped;
  - the hosts look up the transport type from discovered endpoints.
- **Tasks:**
  1. **Compare the two loops line by line.** Write the differences in the progress log. If the desktop loop differs, keep
     *both* behaviours (behind a parameter) and do not unify them silently.
  2. **Write a characterisation test first**, in `core/engine/src/commonTest/.../group/GroupFileSenderTest.kt`, with fakes. For a
     group of self plus three, it asserts:
     - `beginGroupAttachment` is called 3 times, with the same messageId and wireFileId and three distinct transferIds;
     - `sendFile` is called only for the members that were announced;
     - `sendGroupAttachment` is called once, with `transferId = messageId`;
     - the order is announce-then-send for each member.
  3. **Create `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/group/GroupFileSender.kt`.**
     - It takes narrow function-type dependencies: announce, sendFile, sendGroupAttachment, groupMembers, localDeviceId, an id
       factory, and `deviceFor(memberId, name)`.
     - Narrow dependencies keep it testable, and keep the wide interfaces out of it.
     - It exposes `suspend fun send(groupId, uri, displayName, sizeBytes, mime)`.
  4. **Replace both loops with calls to it.** Keep `guessMimeType` and the endpoint lookup in the hosts; pass them in as lambdas.
- **Tests:**
  - the characterisation test passes against the new class;
  - every existing app and desktop test stays green;
  - build `:app:compileDebugKotlin` and the desktop module.
- **Exit criteria:** both hosts call `GroupFileSender`, and the progress log names any desktop difference that was kept.
- **Device checks owed:** `SWM-28` (group-send regression on Android and Windows).
- **Docs to update:** progress, handoff.
- **Do not:**
  - add swarm code;
  - change any id or the announce-then-send order;
  - change the IO dispatcher choice.
- **Rollback:** revert. It is a pure refactor.

---

### SW-2: Seams: `caps`, magic router, user vs system stop
- **Goal:** the three generic hooks the swarm needs, with zero behaviour change while nothing uses them.
- **Depends on:** SW-0.
- **Read first:**
  - the HELLO building and parsing in `core/network/src/androidMain/.../ws/WsFlashNetwork.kt` and
    `core/network/src/jvmMain/.../ws/JvmWsFlashNetwork.kt` (search `"gv"`);
  - `core/common/.../model/FlashDevice.kt`;
  - `handleInboundBinary` in all three roots;
  - `FlashBackgroundService.onTimeout` and `onStartCommand`;
  - every `cancelTransfer(` caller (ripple 5).
- **Ripple:**
  - HELLO compatibility with old builds;
  - the PTT route, which runs **before** decryption and must stay there;
  - the transfer route;
  - the 1:1 behaviour on service timeout.
- **Tasks, part A (`caps`):**
  1. **`FlashDevice`:** append `val features: Set<String> = emptySet()`, documented as "tokens the peer advertised in HELLO `caps`;
     unknown tokens are ignored".
  2. **Network constructors:** give both network classes a `localFeatures: () -> Set<String>` (default `{ emptySet() }`). It is
     read each time a HELLO is built, in all four places.
     - Add `"caps" to tokens.sorted().joinToString(",")` **only when the set is non-empty**, so today's HELLO bytes stay unchanged.
  3. **Parsing:** each token must match `[a-z0-9]{1,16}`; keep at most 32 tokens; drop anything else; never throw. Put the result
     in `FlashDevice.features`.
  4. **Tests** (`core/network` commonTest/jvmTest):
     - a HELLO without `caps` gives an empty set;
     - `"sw1,ab"` gives `{sw1, ab}`;
     - malformed input is ignored;
     - **a golden test shows the HELLO bytes are identical when the set is empty.**
  5. **Document:** a feature added after the network started applies to *new* sessions only. Peers that are already connected
     keep the old set until they reconnect, and the swarm treats them as legacy (never sends them FSW1).
- **Tasks, part B (magic router):**
  6. **`MagicFrameRouter`:** create `core/engine/src/commonMain/.../MagicFrameRouter.kt`.
     - `register(magic: ByteArray, handler)` and `unregister(magic)`.
     - `dispatch(peerId, frame, reply): Boolean` returns true when the frame was consumed.
     - It holds a set of **reserved magics** (`"FSW1"`) that are always consumed: dropped with one rate-limited log line when no
       handler is registered.
  7. **Call it in all three `handleInboundBinary`s,** after decryption and before `transferImpl.onInboundFrame`. Leave PTT where
     it is.
  8. **Tests:**
     - with no handler, an FLSH frame still reaches the transfer route;
     - an FSW1 frame is dropped;
     - a registered handler consumes its magic;
     - frames shorter than 4 bytes never throw.
- **Tasks, part C (user vs system stop):**
  9. **`FlashTransferRepository`:** add
     `suspend fun pauseForSystem(transferId: FlashTransferId, reason: String): FlashResult<Unit>`, with a default implementation
     that calls `pauseTransfer`. It is additive, so implementations need no change.
  10. **Make the timeout decision testable.** Extract it from `FlashBackgroundService.onTimeout` into a pure function in the app,
      `TimeoutStopPlan.of(transfers, isSwarmRow): (cancel: List, pause: List)`.
      - Today `isSwarmRow` is always false, so the cancel list equals today's behaviour.
      - Pin that with a test.
      - Only O-7 may change the 1:1 behaviour.
  11. **Classify every `cancelTransfer` call site** as USER or SYSTEM, in a table in the progress log. Only the timeout is SYSTEM
      today.
- **Exit criteria:**
  - every test is green;
  - the HELLO golden test is green;
  - all three roots call the router;
  - the timeout plan is extracted and pinned.
- **Device checks owed:** `SWM-29` (mixed old/new build: chat, call, PTT, 1:1 file, group file).
- **Docs to update:**
  - `docs/protocol.md`: the `caps` field and the reserved magic list;
  - progress and handoff.
- **Do not:**
  - move the PTT check;
  - send `caps` when the set is empty;
  - change `cancelTransfer`'s meaning;
  - add a `FlashTransferState` value.
- **Rollback:** each part is a separate commit; revert any one of them.

---

### SW-3: `:core:swarm` module, model, manifest, codec
- **Goal:** the module exists, and its data model and wire codec are complete, pure and fully tested.
- **Depends on:** SW-0.
- **Read first:**
  - `core/ptt/build.gradle.kts` (the KMP template to copy);
  - `ChunkFrame.kt` (style of the binary codec, little-endian helpers);
  - `Sha256.kt` in `:core:transfer`;
  - the max binary message size the session accepts (search the WebSocket config for a max frame or message size, and the
    largest chunk size the performance profiles use);
  - the SW-0 protocol section.
- **Ripple:**
  - `settings.gradle.kts`;
  - `jitpack.yml` (later, in SW-8);
  - explicit-API rules (the memory note "explicitApi rollout": visibility classification).
- **Tasks:**
  1. **Create the module.**
     - Add `include(":core:swarm")` to `settings.gradle.kts`.
     - Create `core/swarm/build.gradle.kts` by copying `core/ptt/build.gradle.kts`, with:
       - namespace `com.transfer.flash.core.swarm`;
       - `commonMain` dependencies `api(project(":core:common"))`, `api(project(":core:transfer"))` and coroutines;
       - **no** messaging.
     - Keep `explicitApi()`, compileSdk 35, minSdk 24, JVM 11 and `maven-publish`.
     - Add an empty `consumer-rules.pro` with a comment.
  2. **Write `model/`:**
     - `ContentRoot`: a value class over 64 lowercase hex characters, validated.
     - `PieceMath`: `pieceCount`, `pieceOffset(i)`, `pieceLength(i)`, `choosePieceSize(totalSize, maxPiece)`. The result is the
       smallest power of two ≥ 64 KiB with count ≤ 16,384, capped at `maxPiece`, or null when the file is not swarmable.
     - `Bitfield`: a `LongArray`, with `set`/`get`/`count`, missing-iteration and run-length range encode/decode.
     - `SwarmTombstone`, `SwarmWaitReason`, `SwarmRejectReason`, `FileIdentity`.
  3. **`ManifestBuilder`.** It is streaming: feed byte blocks in, get piece hashes plus the whole-file hash out. It never holds
     more than one piece. It also has `ManifestCodec.canonicalBytes`, `root()`, `parse(bytes, expectedRoot)`, and fragmenting
     into 64 KiB parts plus bounded reassembly.
  4. **`SwarmFrameCodec`: encode and decode all 12 types.**
     - Decoding returns `Decoded(frame)`, `Unknown(type)` or `Malformed(reason)`. **It never throws.**
     - It enforces every limit in 5.3.
  5. **`SwarmStatement`:** the canonical bytes for the announce, cancel and source statements, each with its domain prefix.
  6. **Golden vectors first.**
     1. For SUMMARY, HAVE, HAVE_ALL, REQUEST, PIECE (a 3-byte piece), REJECT, CANCEL, SOURCE_STATUS and a 2-piece manifest root,
        write the expected hex by hand from the documented layout.
     2. Then write the codec until the vectors pass.
     3. Then paste the vectors into `docs/protocol.md`.
  7. **Property tests:**
     - random frames round-trip;
     - 10,000 random byte arrays never throw;
     - every truncation of a valid frame gives `Malformed`.
  8. **Piece math edges:** sizes 1, `pieceSize`, `pieceSize + 1`, `16,384 × 1 MiB`, and that plus 1 (not swarmable).
  9. **`LayeringTest`** (jvmTest). It reads the source trees of the modules listed in 2.5 (relative to the module directory) and
     fails if any file imports `com.transfer.flash.core.swarm`.
     - It also fails if `core/swarm/src/commonMain` imports `com.transfer.flash.core.network`, `.messaging` or `.persistence`.
- **Exit criteria:**
  - `:core:swarm:jvmTest` and the Android host tests are green;
  - the vectors are in `docs/protocol.md`;
  - the limits are mutation-checked (raise a limit by one, a test fails).
- **Do not:**
  - use `java.*` in `commonMain`;
  - use kotlinx-serialization for the wire (hand-written binary, like `ChunkFrame`);
  - hold a whole file in memory;
  - change `ChunkFrame`.
- **Rollback:** the module is unused until SW-8, so delete the include line.

---

### SW-4: Sans-IO engine
- **Goal:** all swarm behaviour, as a pure state machine with table-driven tests.
- **Depends on:** SW-3.
- **Read first:**
  - this plan, sections 4, 5.2, 5.6 and 8;
  - design 4.5 and 4.6;
  - Ketch's `TorrentV2RarityPicker`, `TorrentPieceScheduler` and `TorrentBufferBudget` as a **reference only** (design 11.2; no
    copying without D13).
- **Ripple:** none outside the module. That is the point of sans-IO.
- **Structure:**
  - `SwarmEngine` delegates to small classes, one file each, every one under about 400 lines.
  - Build in this order; each step has its own tests before the next one starts.

| Step | Class / area | Behaviour |
|---|---|---|
| 4a | Content registry | `Announced` creates content in `OFFERED` (or `ACTIVE` when auto-accepted). The manifest is fetched with `MANIFEST_GET`: from any allowed connected peer whose SUMMARY says PARTIAL/ALL, the origin last. On `ManifestComplete`, a root mismatch strikes that peer and fetches again elsewhere. |
| 4b | Peer state | Per (peer, root): bitfield, in-flight set, window, throughput EWMA (from request-to-arrival times), strikes, backoff-until, `servingEnabled`. |
| 4c | `PiecePicker` (Rule A) | The first piece is random among the available ones. After that: rarest-first among pieces that allowed connected peers hold, random tie-break from the seed, skipping in-flight pieces. Endgame starts when missing ≤ min(32, 2 % of pieces): each missing piece goes to at most 2 sources, and the loser gets `UNREQUEST`. |
| 4d | `SourceSelector` | Non-origin holders first. Among them: the highest EWMA, then the fewest in flight. The origin only when no other allowed connected holder has the piece. Never a peer in backoff or banned for this root. |
| 4e | `RequestWindow` | AIMD: start at 4, +1 per completed piece, cap 64, halve on `BUSY` or a timeout. A global in-flight byte budget per profile (LOW 8 MiB, MEDIUM 16 MiB, HIGH 32 MiB; tune in SW-12). |
| 4f | `ServePolicy` | Slots per profile (LOW 1, MEDIUM 2, HIGH 4; the origin gets at least 2), round-robin across requesters, outstanding bytes capped per requester. **Origin offer policy (INV-7).** `allowed = false` gives `REJECT(NOT_MEMBER)`. Unknown root or not announced in that group gives `REJECT(UNKNOWN)` (INV-8). Tombstoned gives `REJECT(CANCELLED)`. Serving disabled (ECO, user setting, call-active floor) gives `REJECT(BUSY, 60 s)`, and SUMMARY carries `servingEnabled = false` so peers do not ask. |
| 4g | HAVE / SUMMARY | `HAVE` is batched to at most once per second per peer per root, and only after `PieceStored` **and** the bits are persisted (INV-4). `SUMMARY` is sent on `PeerUp` and on `MembershipChanged`; tombstones in a received SUMMARY are applied first (INV-5). |
| 4h | Completion | All bits set leads to `Finalize`. On `FinalizeResult(ok)`: `COMPLETE`, then `HAVE_ALL` to connected peers. On a mismatch: the driver re-verifies every piece and reports the bad ones; those bits are cleared and fetched again **once**; a second mismatch gives `FAILED(DAMAGED)`. |
| 4i | `WaitClassifier` | A pure function from content state to a wait reason or null (table 8.1). Recomputed after every event that touches that content. |
| 4j | Cancel | `LocalCancel(asOrigin = true)` leads to `SignTombstone`, then on `TombstoneSigned`: persist, send `CANCEL` to all connected allowed peers, mark the content cancelled, delete the partial if any. `Cancel(signatureValid = true)` leads to: apply, ACK, forward to connected peers that have not acked. `signatureValid = false` leads to: ignore, strike the sender, log. `LocalCancel(asOrigin = false)` is local only: stop, delete the partial, never serve this root again, no frame sent. |
| 4k | Membership | `MembershipChanged(groupId, allowedPeers, localActive)` drops peers that are no longer allowed: their in-flight pieces return to the pool and their queued serves are dropped. If `localActive` is false, every content of that group stops, its partials are deleted, and the reason is `NOT_MEMBER`. |
| 4l | `StrikeBook` | A piece that fails its hash strikes (peer, root); 3 strikes ban that peer for that root. Bans do not persist across restarts (a fresh start is a fresh chance). |
| 4m | `Tick` | Request timeout = max(5 s, 4 × the piece time expected from the EWMA); backoff expiry; HAVE flush; retention expiry (`expiresAtMs`) gives `FAILED(EXPIRED)` and deletes the partial. Ticks only while some content is active; the driver stops the timer when idle. |
| 4n | System | `SystemSuspend` stops requesting and serving but keeps all state (reason `WAITING_FOR_SYSTEM`); `SystemResume` re-evaluates. `CallActive(true)` drops to 1 slot and halves windows. |
| 4o | Snapshot | Per content: bytesDone, piecesDone, holdersOnline, `distributedCopies` (the minimum, over pieces, of the number of *other* online holders), `canGoOffline` (origin: `distributedCopies ≥ 1`), `deliveredTo`, waitReason. |

- **Tests:**
  - table-driven unit tests per step;
  - property tests over random event sequences (seeded), checking that:
    1. a piece already held is never requested;
    2. a piece that is not held and verified is never served;
    3. nothing is served when `allowed = false`;
    4. a tombstoned root is never requested or served;
    5. in-flight bytes stay within budget;
    6. same seed plus same events gives the same commands (run twice, compare);
    7. a HAVE never precedes its persisted bits.
  - mutation-check every guard in 4f and 4j.
- **Exit criteria:** every step is tested and the property tests pass for 1,000 seeds.
- **Do not:**
  - use coroutines, `Clock`, `System.currentTimeMillis`, unseeded `Random` or platform APIs;
  - iterate a `HashMap` or `HashSet` where order changes output (use sorted keys or insertion-ordered maps);
  - hash, read or write inside the engine.
- **Rollback:** the module is unused until SW-8.

---

### SW-5: Simulator and scenarios
- **Goal:** prove the reliability contract on a virtual clock, with many devices, without hardware.
- **Depends on:** SW-4 (build it alongside; every engine step adds scenarios).
- **Location:** `core/swarm/src/commonTest/kotlin/.../sim/`. Move it to `jvmTest` only if it is too slow.
- **Parts:**
  - a virtual clock and an event queue (ordered by time, ties by a sequence number);
  - `SimNode`: engine plus fake storage (with crash semantics: unsynced writes are lost) plus a fake store;
  - `SimLink`: per-node up and down rate, latency, optional shared-cell airtime (design 4.1), disconnect and reconnect events;
  - a scenario DSL;
  - a report: completion times, origin bytes uploaded, frames sent while waiting, corrupt writes (must be 0).

| ID | Scenario | Pass condition |
|---|---|---|
| SIM-01 | Origin + 9, equal rates, 1,000 pieces | All complete. Origin upload ≤ 1.5 × file size. Zero unverified writes. |
| SIM-02 | **Half sent:** origin leaves after uploading 50 % | Within the bound, every member holds the union of what was uploaded. Then all wait with `WAITING_FOR_SENDER`. The origin returns, and all complete. The origin's total upload ≤ 1.2 × the file size. |
| SIM-03 | Origin leaves at 0 % | All wait. **No REQUEST frames are sent while waiting** (count them). All resume when the origin returns. |
| SIM-04 | Origin and a member holding unique pieces both leave; every order of return | All complete after both return; nobody fails. |
| SIM-05 | Churn: random leave/join every 1 to 10 s for 10 nodes | All complete. Progress resumes within the bound after the last churn event (no deadlock). |
| SIM-06 | A corrupt server flips bytes | It is banned after 3 strikes, everyone completes, zero corrupt writes. |
| SIM-07 | A member is removed mid-transfer | Its next request gets `NOT_MEMBER`. It stops and deletes its partial. Nobody sends it a piece after the removal event. |
| SIM-08 | Origin cancels while 2 nodes are offline | Online nodes cancel within one round trip. Offline nodes cancel on reconnect **before sending any REQUEST** (check the frame order). |
| SIM-09 | A non-origin forges a cancel | Ignored; the forger is struck; the transfer completes. |
| SIM-10 | A receiver crashes mid-download (unsynced writes dropped) and restarts | It completes, and never sends a HAVE for a piece it lost. |
| SIM-11 | The origin crashes and restarts | It serves again after restart; members complete. |
| SIM-12 | Heterogeneous rates (design 4.1 example) | **Report** the time against the direct-push model (a number for `logs/experiments.md`, not pass/fail). |
| SIM-13 | An ECO node | It never serves and still completes. |
| SIM-14 | A node without `sw1` | It gets the simulated legacy push from the origin; no node ever sends it an FSW1 frame. |
| SIM-15 | Cross-group: a node in G1 and G2 | Never serves a G1 root to a G2-only member (`UNKNOWN`). |
| SIM-16 | Request flood from one node | Serve caps hold; the others' completion time rises ≤ 25 % (tune). |
| SIM-17 | Scale: 20 nodes, 16,384 pieces | Finishes in under 60 s of real CI time; peak engine memory is bounded (report it). |
| SIM-18 | A node runs out of space at 30 %, then frees it | `WAITING_FOR_SPACE`, then it completes. |
| SIM-19 | The origin is suspended by the system (service timeout) | **No tombstone.** Members wait; the origin resumes. |
| SIM-20 | The origin's source changes at 40 % | `SOURCE_STATUS(LOST, CHANGED)`. Members complete whatever the union holds; the rest end `FAILED(SOURCE_LOST)`. A re-pick of the original file restores serving. |

- **Exit criteria:**
  - every scenario passes for seeds 1 to 20;
  - the scenario report is pasted into `logs/experiments.md` as a new EXP entry marked "simulator, not devices".
- **Do not:** sleep or use real time; let a scenario depend on thread scheduling.

---

### SW-6: Persistence
- **Goal:** swarm state and tombstones survive restarts on Android and desktop.
- **Depends on:** SW-3.
- **Read first:**
  - `FlashDatabase.kt`, `FlashSchemaSteps.kt` (its "Adding a schema version" steps);
  - `FlashMigrations.kt` (Android) and `FlashJvmMigrations.kt`;
  - an existing commonMain DAO (`GroupDeliveryDao.kt`);
  - `core/engine/src/androidMain/.../store/RoomTransferStore.kt` (the adapter style);
  - the memory note "Persistence decoupling" (ADR-024: adapters live in the engine).
- **Ripple:**
  - FA-4 wants a schema step too (whoever lands first takes 7);
  - desktop opens the same database (ripple 16);
  - the pre-existing red DataStore tests.
- **Tasks:**
  1. **Entities and DAO in `:core:persistence` commonMain:** `SwarmContentEntity` and `SwarmTombstoneEntity` as in 5.4, and a
     `SwarmDao` with upsert, set bits, load all, tombstones, delete, and purge expired.
  2. **Register them** in `FlashDatabase`, bump `DATABASE_VERSION`, build, and let Room export the new schema JSON.
  3. **Append the step to `FlashSchemaSteps.ALL`.** Copy the CREATE TABLE statements from the exported JSON exactly; Room
     validates them.
  4. **Write `RoomSwarmStateStore`** in `core/engine/src/commonMain/.../swarm/`, implementing `SwarmStateStore`.
- **Tests:**
  - `FlashSchemaStepsTest` and `FlashJvmMigrationsTest` are green;
  - **a migration test:** a version-6 database with chat rows upgrades, and the rows are intact;
  - DAO tests;
  - adapter round-trip tests;
  - a bits-write performance check: 16,384-bit blobs written once per second are fine.
- **Exit criteria:** an old database and a new database both open on Android (host test) and on the JVM.
- **Do not:**
  - alter existing tables;
  - use destructive migration;
  - put Room types in `:core:swarm`.
- **Rollback:** a schema step cannot be un-shipped once released. Before release, revert the commit and the schema JSON together.

---

### SW-7: Storage I/O
- **Goal:** random-access reads of the origin's source, partial files for receivers, identity checks, finalize and space checks.
- **Depends on:** SW-3.
- **Read first:**
  - how a received 1:1 file gets its final path today (`FlashConfig.receivedFilesDir`, the desktop received-files setting, the
    name-collision rule; search `receivedPaths`, `localPath`);
  - `FileSourceOpener` (sequential only today);
  - `RandomAccessSinkHandle` (write-only);
  - `FlashFilePicker.android.kt` (`takePersistableUriPermission` inside `runCatching`).
- **Ripple:**
  - storage permissions (AGENTS 20);
  - the FA-2 space gate;
  - where users find received files (the same place as today).
- **Tasks:**
  1. **`AndroidPieceStorage.openSource`:**
     - open with `ContentResolver.openFileDescriptor(uri, "r")`, then read positionally from the descriptor's channel
       (`FileChannel.read(ByteBuffer, position)`);
     - identity = size plus `COLUMN_LAST_MODIFIED` when the provider gives one;
     - **`JvmPieceStorage`:** use `FileChannel`.
  2. **Persistable permission.** Make the picker report whether `takePersistableUriPermission` succeeded, and store
     `sourcePersistent` on the origin record. If it failed, apply O-3.
  3. **Partial files.**
     - Location: app-private storage, in `files/swarm/partial/<root>-<sha256(groupId) first 8 hex>.part` (Android) or
       `~/.flash/swarm/partial/...` (desktop).
     - `writeAt` uses `RandomAccessFile("rw")`; `sync` uses `FileChannel.force(false)`.
  4. **`finalize`:**
     1. whole-file SHA-256 read pass;
     2. move to the same destination and with the same collision rule as a 1:1 receive;
     3. record `finalPath` and the identity after the move.
  5. **Serving after completion** reads from `finalPath`. If the identity changed (the user edited, moved or deleted the file),
     reply `REJECT(GONE)` and clear the serve flag.
  6. **Space.** `freeBytesFor` measures the volume of the partial directory. The check needs the remaining bytes plus a 1 %
     margin, and is made at Accept and at every start.
  7. **Delete the partial** on cancel, removal, expiry and local decline.
- **Tests (JVM, with temp directories):**
  - random-offset writes and read-back;
  - overlapping writes;
  - an identity change detected;
  - a finalize name collision;
  - a crash simulation (unsynced data is not trusted after restart, because the bits were not persisted).
- **Device checks owed:** content-URI reads on Android are part of `SWM-13`.
- **Do not:**
  - read a whole file into memory;
  - use `java.io.File` for `content://` URIs;
  - write into the final destination before the whole-file check.

---

### SW-8: Driver and host integration, behind a switch (off by default)
- **Goal:** the swarm works end to end on Android and desktop when the switch is on, and nothing changes when it is off.
- **Depends on:** SW-1, SW-2, SW-4, the SW-5 exit, SW-6, SW-7 and **GM-5** (the group gate). The swarm does **not** need GM-1
  to GM-4: with GM-5 alone it already serves vouched members, and secret-joined members arrive through the same gate when GM-4
  lands.
- **Read first:**
  - this plan, section 6, every row marked SW-8;
  - `FlashEngine.kt` (`attachPtt` / `attachCalling` and their docs);
  - `RealFlashChatRepository` (the `GroupMedia` receive path, the `pendingGroupMedia` park, `onIncomingOffered`);
  - `SignedGroups` and `GroupCanonical`;
  - `RealFlashTransferRepository.activeTransfers` and its control methods;
  - `DesktopInteropHarness`.
- **Tasks:**
  1. **`FlashSwarm`** (`:core:swarm` `api/`):
     - `status(transferId): StateFlow<FlashSwarmStatus?>`;
     - `rows: StateFlow<List<FlashTransfer>>`;
     - `accept`, `decline`, `pause`, `resume`, `cancelLocal`, `cancelAsOrigin(transferId)`;
     - `reevaluate()`, called by the wake hooks.
  2. **`SwarmDriver`:**
     - one actor (`Channel<SwarmEvent>`, one consumer coroutine);
     - a worker dispatcher for hashing, reads and writes;
     - a 250 ms `Tick` only while content is active;
     - it loads all records from the store at start, before any frame is handled.
  3. **`SwarmHostBinding`** (`:core:engine` commonMain):
     - builds the driver from the ports;
     - registers `"FSW1"` with `MagicFrameRouter`;
     - provides `sw1` to `localFeatures`;
     - turns `activeSessions` changes into `PeerUp`/`PeerDown` (features from `FlashDevice.features`, and **only peers with
       `sw1`**);
     - turns network state into `NetworkUp`/`NetworkDown`, `membershipChanges` into `MembershipChanged`, the connection mode into
       `ServingEnabled`, and `isCallActive` into `CallActive`.
  4. **`FlashEngine.attachSwarm(config): FlashSwarm?`, `detachSwarm()` and `val swarm: FlashSwarm?`.**
     - `Flash.create` does not attach.
     - The app (`DiscoveryEngineHolder`) and desktop (`DesktopEngine`) attach **before network start** when the setting "Group
       file sharing (swarm, experimental)" is on.
     - **The setting takes effect after an app restart.** That avoids a device that advertised `sw1` and then stopped serving
       mid-session.
  5. **`MessagingSwarmGroupContext`:**
     - `isPeerAllowed` delegates to the `GroupGate` of GM-5 (5.7):
       - `allows(groupId, peer, FILE_SERVE)` when this device would serve;
       - `allows(groupId, peer, FILE_RECEIVE)` when it would fetch from the peer.

       The gate already holds the v2 rule, the local-active check and the "paired **or** live key equals roster key" rule. That
       way a member paired with nobody gets and serves files (owner decision, 1.3).
     - **Do not re-implement the rule here.** A second copy of the rule is how the two drift apart.
     - `membershipChanges` is `GroupGate.changes`.
     - *(Superseded text, kept per AGENTS 27: "v2 group and the local device is active and the peer is active in the roster and
       `isGroupPeerTrusted` (O-5) and not `isRemovedHere`".)*
     - sign and verify statements through new `GroupCanonical` / `SignedGroups` functions with the domain prefixes;
     - **test that a message signature never verifies as a statement, and the reverse.**
  6. **Messaging, announcement fields:** add them to `GroupMedia` and to its codec, and test that an older decoder still accepts
     the frame (SW-0 task 4).
     - When a `GroupMedia` with `swarm = 1`, a valid `rootSig` and an attached swarm arrives: insert the bubble immediately (with
       `attachmentTransferId = frame.transferId`), then call the `GroupSwarmAnnouncementListener`, which the binding sets.
     - Without the swarm, or with a bad signature: today's path, unchanged.
  7. **`GroupFileSender`, the swarm branch.** When swarm is attached **and** the group is v2 **and** the file is swarmable:
     1. **Manifest pass.** The origin row shows `Queued` with "Preparing…", and `ManifestBuilder` reads the file once.
     2. **Persist** the origin record.
     3. **Announce per member,** with that member's transferId:
        - members with `sw1` get the swarm fields;
        - members without it get today's `GroupMedia` plus today's `sendFile` push.

     Otherwise, today's path.
  8. **`SwarmTransferRowBridge`:**
     - **Merge.** Add an additive method on `RealFlashTransferRepository`,
       `attachExternalRows(rows: StateFlow<List<FlashTransfer>>, control: ExternalTransferControl)`. It merges the external rows
       into `activeTransfers`, and routes accept, decline, pause, resume, cancel and `pauseForSystem` for ids it owns to `control`.
     - **Exclude** external rows from `TransferReconnectResumePolicy` input (ripple 12).
     - **Timeout.** `TimeoutStopPlan` gets the real `isSwarmRow` (ripple 3).
  9. **Origin bubble.** "Delivered to k of n" and "You can go offline now" come from `FlashSwarm.status(messageId)`. The UI words
     are final in SW-11; here, plain text.
  10. **Connection mode (ECO).** In ECO, serving is disabled and downloading continues (ripple 18).
  11. **Calls.** While a call is active, the swarm uses the 1-slot floor (ripple 19).
  12. **Log lines (tag `SWARM`, structured):**
      `SWARM: event=request peer=… root=ab12… pieces=4 allowed=true`,
      `SWARM: event=wait root=… reason=WAITING_FOR_SENDER missing=50/100`,
      `SWARM: event=cancel root=… origin=… applied=true via=summary`.
  13. **Zero-change proof test** (`core:engine` jvmTest). Without `attachSwarm`:
      - the HELLO has no `caps`;
      - FSW1 frames are dropped;
      - a group send makes exactly today's calls (reuse the SW-1 test).
  14. **Interop test** (`DesktopInteropHarness`, three JVM engines with the swarm attached) — the most valuable automated test
      of this work:
      1. send a 5 MB file to a group of three;
      2. disconnect the origin at about 50 %;
      3. assert the two members converge on the same union;
      4. reconnect the origin;
      5. assert all three complete and the whole-file checks pass.
  15. **Publication.**
      - Add `:core:swarm:publishToMavenLocal` to `jitpack.yml`, add it to the README "Published modules" list, and update the
        comment counting modules.
      - Run the JitPack install line locally (release dry-run).
      - Check the sample consumers build without attaching.
- **Exit criteria:**
  - the interop scenario passes;
  - the zero-change test passes;
  - app and desktop build;
  - all earlier tests are green.
- **Device checks owed:** `SWM-09`, `SWM-10`, `SWM-16`, `SWM-20`, `SWM-21`, `SWM-23`.
- **Docs:**
  - `docs/protocol.md` (the `GroupMedia` fields);
  - `docs/architecture.md`;
  - `AGENTS.md` section 29 (one line: built, switch off by default, not device-verified);
  - progress and handoff.
- **Do not:**
  - send FSW1 to a peer without `sw1`;
  - let the swarm dial directly;
  - cancel instead of pause on a system stop;
  - make a legacy `g-` group use the swarm;
  - turn the switch on by default.
- **Rollback:** switch it off (it takes effect after restart). Every peer then sees no `sw1`, and the legacy path takes over.

---

### SW-9: Cancel everywhere and lifecycle
- **Goal:** the origin's cancel reaches everyone durably, and every lifecycle event (leave, removal, delete, timeout, expiry) does
  the right thing.
- **Depends on:** SW-8.
- **Tasks:**
  1. **Origin cancel in the UI.**
     - The sender's bubble, the transfers screen and the origin's notification call `cancelAsOrigin`, after a confirmation
       ("Cancel for everyone? Members who already have the file keep it." — word it from O-2).
     - The receiver's bubble, transfers screen and notification call `cancelLocal`. There is no confirmation beyond today's.
  2. **Tombstone propagation:** `CANCEL` to connected peers, `CANCEL_ACK` tracking, re-sending on each `PeerUp`, and tombstones
     inside `SUMMARY` (already in the engine; wire and test it in the hosts).
  3. **Delete for everyone.** When the origin deletes its own file message for everyone, it also calls
     `cancelAsOrigin(reason = DELETED)`.
  4. **Leave, removal, group deleted locally:**
     - local downloads of that group stop and their partials are deleted;
     - the origin's own records in that group stop serving (O-9).
  5. **Service timeout.** `TimeoutStopPlan` sends swarm rows to `pauseForSystem` (`SystemSuspend`), and the next service start
     sends `SystemResume`.
  6. **Retention cleanup.**
     - Runs on app start and on every 15-minute wake (Android `FlashKeepaliveWorker`, a 15-minute timer on desktop).
     - It purges expired contents and tombstones (`purgeExpired`) and deletes orphaned `.part` files that have no record.
  7. **Completed copies after a cancel** follow O-2.
- **Tests:**
  - binding unit tests;
  - interop: cancel while one engine is offline, then reconnect it; assert the tombstone is applied before any REQUEST (frame
    order in the harness log);
  - a forged cancel through the harness;
  - the delete-for-everyone path;
  - a timeout produces no tombstone.
- **Device checks owed:** `SWM-11`, `SWM-12`, `SWM-15`, `SWM-17`.
- **Do not:**
  - create a tombstone for any system reason;
  - let anyone but the origin cancel for everyone;
  - delete a tombstone before its expiry.

---

### SW-10: Errors and recovery
- **Goal:** every condition in section 8 has detection, a state, a sentence, an automatic way back, and a test.
- **Depends on:** SW-9.
- **Tasks:**
  1. **Wake-up wiring (INV-9).** Each of these must call `FlashSwarm.reevaluate()` or feed the matching event:
     - a session comes up;
     - a HAVE or SUMMARY arrives;
     - the network comes up;
     - the app or engine starts (records loaded);
     - Android's `FlashKeepaliveWorker` runs;
     - the desktop's 15-minute timer fires;
     - the foreground service starts;
     - the space re-check finds free space, on each wake.

     Test each trigger.
  2. **Sentences.** Add every swarm sentence to `TransferFailureText`, in the style FA-3 established (`notEnoughSpace`,
     `friendly`), with a test per sentence. Never show a raw exception.
  3. **Sweep the catalogue.** For each E-entry still missing a test after SW-4 to SW-9, add one, and write the test name into the
     catalogue's Tests column.
  4. **New conditions.** Anything found during the sweep becomes a new E-entry (E-51 onwards). Real bugs get an `ERROR-1xx` entry
     in `logs/errors.md`.
  5. **Per-wait-reason test (INV-9).** For every `SwarmWaitReason` value, a test shows an event that moves the content out of it.
- **Exit criteria:** no empty Tests cell in section 8, and the INV-9 test is green.
- **Device checks owed:** `SWM-13`, `SWM-14`, `SWM-18`, `SWM-19`, `SWM-22`, `SWM-25`, `SWM-26`, `SWM-27`.

---

### SW-11: UI (DONE 2026-10-05)
- **Goal:** people understand what is happening without seeing protocol details (AGENTS 22).
- **Depends on:** SW-8.
- **AGENTS 34 applies.**
  1. First write `docs/ui/group-file-availability.md` with the component template (the next free UI id), research at least 3
     approaches, and get it to **DESIGNED**.
  2. Only then implement.
- **Contents:**
  - **Receiver bubble:** a detail line ("Waiting for Alex to come online · 48 of 100 MB", "Getting it from 3 devices").
  - **Sender bubble:**
    - "Delivered to 7 of 9";
    - "You can go offline now";
    - "2 devices still need parts only you have";
    - "Your file is no longer available. Pick it again to keep sharing" (O-3).
  - **Transfers screen:** the same lines.
  - **Notifications:** the origin's says "Cancel for everyone".
  - **Settings, device-local** (never sent on the wire):
    - "Help share group files" (serve on/off);
    - "Keep finished files available for others" (follows O-1);
    - "Group file sharing (swarm, experimental)", the switch, which applies after a restart.
  - Accessibility (content descriptions for the detail line) and dark mode.
- **Device checks owed:** `SWM-24`, `SWM-07` (existing).
- **Do not:** show piece numbers, roots or peer ids in the normal UI. They belong in logs and debug screens.

---

### SW-12: Device checks, tuning, default-on decision (PENDING OWNER DEVICE TESTING)
- **Goal:** evidence from real phones and Windows, then the owner's decision to turn it on by default (O-8).
- **Tasks:**
  1. **Run section 4w of the backlog in this order:**
     1. `SWM-23`, `SWM-28`, `SWM-29` (nothing broke);
     2. `SWM-09`, `SWM-10` (the owner's scenario);
     3. `SWM-11`, `SWM-12` (cancel);
     4. `SWM-13`, `SWM-14` (restarts);
     5. the rest.
  2. **Record each result** as AGENTS 35 says: the status line, the results log, and the source documents updated. A FAIL gets a
     new ERROR.
  3. **Tune** the piece size, slots, windows and budgets from the `MEAS-*` and `SWM-01..05` runs. Record each change with its
     measurement in `logs/experiments.md`.
  4. **The owner decides O-8.** If yes, flip the default and update `AGENTS.md` section 29.

---

## 7B. Track GM: group membership by group id + secret

**Read this before any GM phase:**
- sections 1.3, 2.6, 5.7 and rows 31 to 47 of section 6;
- design 1.2 to 1.7, remembering two changes:
  - 1.5 condition 1 is replaced by O-13;
  - 1.6's sealing is replaced by "Rotation without sealing" (5.7);
- `docs/group/v2-vouched-trust-plan.md`, ADR-042 (pairing v2), ADR-044, ADR-061, ADR-063 and ADR-064;
- in code:
  - `SignedGroups` and `GroupSignatureRules` (`checkCharter`, `checkCert`);
  - `GroupVouching` and `TrustStoreGroupVouching`;
  - `VouchRules` / `PinSource`;
  - the four predicates near `isActiveTrustedMember` in `RealFlashChatRepository`.

**The order:**
- GM-5 has no GM prerequisite, so start it first. It is the part the swarm needs.
- GM-1 to GM-4 can run at the same time as the swarm phases.

**Every GM phase follows the rules of section 0.3.** One more rule applies here: **write no ownership-transfer code.** The
owner-loss decision ("D then A") is the owner's to make (AGENTS 29, ERROR-089). Use ADR-063's admins as they exist.

---

### GM-1: Membership crypto primitives (DONE 2026-10-05)
- **Goal:** pure, deterministic primitives with golden vectors that pass on Android and on the desktop JVM.
- **Why:** everything after this phase relies on them. They must be correct before any frame exists.
- **Depends on:** SW-0 (the membership ADR accepted).
- **Read first:**
  - `core/security/src/commonMain/.../Hkdf.kt`, `PlatformCrypto`, `FlashCrypto`;
  - the pairing v2 code that binds a code to both TLS fingerprints (search `ADR-042` or `commit` in `:core:security`): reuse its
    transcript style;
  - the `VerifyBudget` pattern.
- **Ripple:** none outside the new package `...security.group`. Do not change the pairing primitives.
- **Tasks:**
  1. **`GroupSecret`:** a holder for exactly 32 bytes from the platform's secure random.
     - `toString()` returns `GroupSecret(redacted)`.
     - No `equals` that compares early-out. Provide `constantTimeEquals`.
  2. **`GroupSecretKdf.authKey(secret, groupId, epoch)`:** `HKDF-SHA256(ikm = secret, salt = empty, info = "flash-gsa-v1" ‖ u16
     length ‖ groupId UTF-8 ‖ u32 epoch)`, 32 bytes. Add `beaconKey` with info `"flash-gbeacon-v1"`, for GM-8.
  3. **`GroupProof`:** a sans-IO state machine for the three messages of design 1.3.
     - **Transcript:** `"flash-gsp-v1" ‖ role ‖ lp(fpInitiator) ‖ lp(fpResponder) ‖ lp(groupId) ‖ u32 epoch ‖ nonceI(16) ‖
       nonceR(16)`.
       - `role` is `"I"` or `"R"`.
       - `fp` is the SHA-256 of the live TLS key.
       - `lp` is a u16 length prefix.
     - **MAC:** HMAC-SHA-256 with `authKey`.
     - **Verification:** constant-time.
     - **One shot:** an instance that finished or failed refuses any further input.
     - **Nonces:** they come from an injected random source, so tests are deterministic.
  4. **`GroupSecretCommit.of(groupId, epoch, secret)`:** `SHA-256("flash-gs-commit-v1" ‖ lp(groupId) ‖ u32 epoch ‖ secret)`
     (5.7).
  5. **Golden vectors** for 2, 3 and 4, in `commonTest`. Run them on both targets.
- **Tests:**
  - the vectors;
  - **relay:** the two sessions have different fingerprints, so the proof fails;
  - **reflection:** the responder's MAC replayed as the initiator's fails;
  - **replay:** a new nonce makes an old MAC fail;
  - a wrong epoch fails, and a wrong group id fails;
  - `toString` never prints the bytes.
- **Exit criteria:** all of the above are green on `jvmTest` and the Android host tests, and mutation-checked (flip the role
  label, drop a fingerprint from the transcript, compare with `==`).
- **Device checks owed:** none (pure code). `GSEC-03` covers it on devices later.
- **Docs to update:** fill the vectors in `docs/protocol.md` "Group membership v1".
- **Do not:**
  - accept a secret of any length other than 32;
  - derive anything from a user-typed string (D3: app-generated only).
- **Rollback:** delete the package. Nothing else uses it yet.

### GM-2: Invite format and secret storage (DONE 2026-10-05)
- **Goal:** an invite can be made, shared as text and read back. The secret is stored safely on both hosts.
- **Depends on:** GM-1.
- **Read first:**
  - how each host protects its identity key and its database (search `KeystorePassphraseProvider` and `FlashCertMaker` on
    Android; on desktop, find where the identity key and `~/.flash` are written);
  - the Android `allowBackup` / data-extraction rules in `AndroidManifest.xml`;
  - `FlashSchemaSteps` (ripple 17).
- **Ripple:** the schema version is shared with SW-6 and FA-4 (whichever lands first takes the next number). The Android backup
  rules also apply here.
- **Tasks:**
  1. **`GroupInviteCodec`** (`:core:security`). Version 1 is binary, then base64url, and shown as `flash://g/1/<payload>`.

     | Field | Encoding |
     |---|---|
     | version | `u8` = 1 |
     | `groupId` | lp |
     | epoch | `u32` |
     | secret | 32 bytes |
     | group name | lp, at most the group-name limit |
     | inviter `deviceId` | lp |
     | inviter key fingerprint | 32 bytes |
     | address hints | at most 3 `host:port` strings, each lp |
     | `issuedAtMs` | `u64`, display only |

     - **Size:** about 250 bytes, about 340 characters.
     - **Decoding never throws.** Unknown versions, oversize fields and trailing bytes give `null`.
     - **No signature:** whoever can change an invite already holds the secret.
     - **Golden vector.**
  2. **Storage tables** (one schema step):
     - `group_secret(groupId, epoch, secretWrapped, commit, source, receivedAtMs)`, primary key `(groupId, epoch)`.
       `source` is one of `CREATED`, `INVITE`, `HANDOVER`, `ROTATED`.
     - `group_invite(groupId, inviterId, inviterFingerprint, acceptedAtMs, state)`. `state` is one of `PENDING_CONTACT`,
       `PENDING_APPROVAL`, `JOINED`, `REFUSED`, `ABANDONED`. This table is the trust root that `checkCharter` will read (GM-4).
     - `group_join_request(groupId, subjectId, subjectKey, label, requestSig, viaPeerId, requestedAtMs, state, decidedBy,
       decidedAtMs)`, primary key `(groupId, subjectId, subjectKey)`.
  3. **Wrapping.** First find out whether the chat database is already encrypted at rest on each host (search `SupportFactory`,
     `SQLCipher` and the passphrase provider).
     - **If it is,** storing the secret in its column is enough. Write down why.
     - **If it is not,** wrap the secret with the same host key that protects the identity key or the database passphrase. **Do
       not invent a new key store.**
  4. **`GroupSecretStore`** port (messaging) with the adapters per host in `:core:engine` / the app / desktop. Only
     `current(groupId)`, `put`, `forget(groupId)`. Nothing returns the secret as a string.
  5. **Backups.** Make sure Android's backup and data-extraction rules exclude the database, or at least the secret. Record what
     was found in `docs/android-platform-notes.md`, with the official page and the date.
- **Tests:**
  - the invite round trip and the hostile-input table (truncated, oversize, wrong version, extra bytes);
  - the migration on both platforms (`FlashSchemaStepsTest`, `FlashJvmMigrationsTest`);
  - the store adapter round trip;
  - **no `toString` or log line contains the secret:** grep the test logs for a known secret value.
- **Exit criteria:** green, mutation-checked, the schema JSON exported.
- **Device checks owed:** `GMB-11` (the secret never appears in logs) after GM-4.
- **Do not:**
  - put the secret in a `FlashTransfer`, a notification, a chat message row or `SharedPreferences` in clear;
  - log invites (log `groupId` and `epoch` only).
- **Rollback:** the schema step stays (never edit a committed step). The code can be removed.

### GM-3: Proof exchange, `gs1`, membership frames — COMPLETE (2026-10-05)
- **Goal:** two devices can prove to each other that both hold the secret of group G at epoch e, over their live session.
- **Depends on:** GM-1, and SW-2 part A (the `caps` field).
- **Read first:**
  - the `GroupWireFrame` codec and how unknown types and fields are handled (SW-0 task 4 recorded the answer);
  - how `peerIdentityKey` reaches `RealFlashChatRepository` (the hosts pass the live session key);
  - session-down handling in messaging.
- **Ripple:** group frames already in use must decode exactly as before (golden tests). Peers without `gs1` never receive a
  `GS*` frame.
- **Tasks:**
  1. Advertise `gs1` in `localFeatures` (SW-2) on every build that has GM-3.
  2. **New `GroupWireFrame` types, as documented in SW-0 task 5a:**

     | Frame | Fields | Introduced by |
     |---|---|---|
     | `GsHello` | `groupId, epoch, nonce` | GM-3 |
     | `GsChallenge` | `groupId, epoch, nonce, mac` | GM-3 |
     | `GsProof` | `groupId, epoch, mac` | GM-3 |
     | `GsResult` | `groupId, ok, reason` | GM-3 |
     | `GsStale` | `groupId, currentEpoch, rotationNotice` | GM-6 |
     | `GsSecretRequest` | `groupId, epoch` | GM-6 |
     | `GsSecret` | `groupId, epoch, secret` | GM-6 |
     | `GsJoinRequest` | | GM-4 |
     | `GsJoinDecision` | | GM-4 |
     | `GsRosterPreview` | | GM-4 |
     | `GroupRotation` | | GM-6 |
     | `GroupSettings` | | GM-9 |

     **Every field has a size limit, and decoding never throws.**
  3. **`GroupProofSessions`** (messaging): one `GroupProof` per `(peer, group)` in flight; a 20-second timeout.
     - On success, the live-session fact `provedGroups[peer] += groupId`.
     - It is cleared on session down, and **never persisted**.
  4. **Fingerprints** come from the live session's key (the same source as `peerIdentityKey`). If that key is missing, the
     proof fails.
  5. **Rate limit:** at most 5 failed proofs per peer per 10 minutes (the `VerifyBudget` pattern). Over the limit, ignore `GsHello`
     from that peer until the window passes. **Never drop the session** (it carries chat and calls).
  6. **Epoch mismatch:**
     - The side with the newer epoch answers `GsStale`.
     - It attaches the rotation notice **only** when the other key is an active roster member (design 1.3).
     - Otherwise `GsResult(ok = false, reason = "stale")` and nothing else.
     - **SW-0 refinement (2026-10-04, protocol "Group membership v1", ADR-073 rule 14):** answer `reason = "stale"` only after a
       valid proof at an older epoch this device still holds. A device that does not hold the group, or cannot verify the epoch,
       answers with a random challenge and then `reason = "failed"`, so a stranger who knows a group id cannot learn who is in it.
       No device changes state because of an epoch number in a `GsHello`.
- **Tests:**
  - a loopback with two in-memory repositories: success;
  - wrong secret, wrong epoch (both directions), relay (two sessions with different keys), timeout, the rate limit;
  - the session dropping mid-proof clears the state;
  - `gs1` missing means no frame is sent;
  - the old group-frame golden vectors are unchanged.
- **Exit criteria:** green, mutation-checked (skip the fingerprint check, persist `provedGroups`, send to a peer without `gs1`).
- **Device checks owed:** `GSEC-02`, `GSEC-03` (after GM-4 gives them a use).
- **Do not:**
  - let `provedGroups` grant chat, calls or files (GINV-2). Its only uses are the join request (GM-4) and the roster preview.
- **Rollback:** stop advertising `gs1`. Peers then never send `GS*` frames.

### GM-4: Joining: invite → proof → request → approval → certificate (DONE 2026-10-05)
- **Goal:** a device that is paired with nobody joins a group with an invite and becomes a full member.
- **Built & Verified:**
  - Wire frame types: `GsJoinRequest`, `GsJoinDecision`, `GsRosterPreview` in `GroupWireFrame.kt`.
  - Canonical signatures: `JOIN_REQUEST_TAG` ("flash-gjoin-v1"), `JOIN_DECISION_TAG` ("flash-gdecision-v1"), `joinRequestBytes`, `joinDecisionBytes` in `GroupCanonical.kt`.
  - Codec: safe bounds-checked encode and decode in `GroupFrameCodec.kt`.
  - Charter trust root seam: `hasInvite: (groupId: String) -> Boolean` accepted in `GroupSignatureRules.checkCharter` and `SignedGroups` (GINV-3).
  - Scoped vouch: pre-installs inviter's key in `GroupVouching.vouch(inviterId, fingerprint, groupId)` upon `acceptInvite`.
  - Policy & member bounds: relaxed `validMemberIds(..., minMembers = 1)` for `createGroupForInvite`.
  - Mutual proof integration: on session up to peer advertising `gs1`, joiner triggers `initiateProof` for pending invites; upon success, issues signed `GsJoinRequest`.
  - Inbound join request handling: validates mutual proof (`hasProved`), validates direct vs forwarded request, verifies signature over `joinRequestBytes`, emits `GsRosterPreview`, records request.
  - Decision handling: admins can auto-approve (policy "open") unless tombstoned (GINV-5) or full, or manually approve via `approveJoinRequest(groupId, subjectId)` / `refuseJoinRequest(groupId, subjectId, reason)`. Refusal cleans up secret and revokes scoped vouch.
  - Forwarding: non-admin members forward join requests to all active admins on session up.
  - Tests passing: `GroupJoinTest` (7 scenarios) and `GroupSignatureRulesTest` (4 charter invite scenarios).
- **Depends on:** GM-2, GM-3, GM-5 (GroupGate GM-5 already built in SW-8).
- **Read first:**
  - `SignedGroups.onBundle`, `addMembers`, `installVouches`, `ensureVouches`;
  - `GroupSignatureRules.checkCharter`;
  - `RealFlashChatRepository.createGroup` (both paired-only checks) and `GroupPolicy.validMemberIds`;
  - the manual-dial API (search `connectManual`) and the named-dial TOFU trap in the project notes;
  - ADR-063 (who is an admin).
- **Ripple:** rows 31, 32, 33, 37, 38, 40 and 43. This phase changes how a charter is trusted. A mistake here lets a stranger push
  a group onto a device. Read GINV-3 twice.
- **Tasks:**
  1. **Creating a group to share.** `createGroupForInvite(name)` makes a v2 group with only the creator, generates epoch 1, and
     stores it (`source = CREATED`).
     - Relax `validMemberIds` **only** for this path.
     - The paired-invitee path stays as it is.
  2. **Making an invite.** `inviteFor(groupId)` is allowed when:
     - this device holds the current secret;
     - the group setting allows this device to share (GM-9; until then, any member, per O-12).

     It reads the address hints from the network's current addresses (at most 3).
  3. **Accepting an invite.** `acceptInvite(text)` does, in this order:
     1. decode it;
     2. refuse if this device is already an active member (open that group instead);
     3. store `group_invite(state = PENDING_CONTACT)` and the secret (`source = INVITE`);
     4. **pre-install the inviter's key as a vouch scoped to this group id** (`GroupVouching.vouch(inviterId, fingerprint,
        groupId)`). The TLS layer then refuses any other key at that id, and nothing is TOFU-pinned (ripple 37). This reuses the
        existing vouch machinery and its conflict rules, so no new `PinSource` and no network change are needed. Update the
        `PinSource.VOUCHED` doc: "or named by an invite this device accepted for that group".
     5. ask the planner for a session to the inviter, dialing the hints.
  4. **On a session with a peer that is not yet known in this group:** start `GroupProof` as the initiator, for every group in
     `PENDING_CONTACT` / `PENDING_APPROVAL`.
     - This applies to the inviter, and to any discovered device that advertises `gs1`.
     - A failed proof leaves the state unchanged and is rate-limited.
  5. **The join request.** After the proof, the joiner sends
     `GsJoinRequest(groupId, epoch, subjectId, subjectKey, label, requestedAtMs, signature)`, signed by its identity key over
     `"flash-gjoin-v1" ‖ ...`.

     The member that receives it checks four things:
     - the session proved the group;
     - `subjectId` is the transport peer;
     - `subjectKey` is the live key;
     - the signature verifies.

     Then:
     - It replies `GsRosterPreview`: the charter, plus a member count and names. The charter is verified by the joiner through
       its invite root, and the preview is not stored as a roster.
     - It stores the request in `group_join_request`, and forwards the signed request to every admin it has a session with. It
       re-forwards on each new admin session until a decision is seen. Requests expire after 7 days.
  6. **The admin's decision.**
     - **Policy "approve"** (D2 default): admins get a notification and a list (GM-10). Approving signs a `MemberCert`
       (`addMembers`, admin path, ADR-063) and gossips the bundle as today. Refusing signs `GsJoinDecision(refused)`.
     - **Policy "open":** an online admin signs automatically, **except** for a key this group has tombstoned (GINV-5: that one
       waits for an explicit approval).
     - **The group is full** (20): refuse with reason `full`.
  7. **The charter trust root** (ripple 31). `checkCharter` gets a second accepted path: `isPaired(owner)` **or** a
     `group_invite` row exists for exactly this `groupId` with a state other than `REFUSED` / `ABANDONED`.
     - Keep `id-derivation` and the signature checks for both paths.
     - Add the refusal test: a valid bundle for a group with no invite and an unpaired owner is still refused.
  8. **The joiner receives a bundle with its own active certificate.** `onBundle` takes the `!known` path through the invite root.
     The group is created locally as today, `group_invite.state = JOINED`, and catch-up runs as today (row 43).
  9. **Refused or abandoned.**
     - On `GsJoinDecision(refused)`, verified against an admin's key, or when the user cancels: revoke the inviter's vouch for
       this group (unless the roster now vouches the inviter) and forget the secret.
     - The invite stays visible as "declined" or "cancelled".
  10. **Old members** (row 40): no change is needed. Write the test that today's `onBundle` accepts an admin-signed certificate for
      a subject this device never paired with.
- **Tests:**
  - **The end-to-end loopback:**
    - three repositories, A (owner), B (member, paired with A only) and C (paired with nobody);
    - C joins with A's invite while only B is online;
    - B forwards the request;
    - A comes online and approves;
    - C is a member on all three;
    - B and C exchange group chat.
  - **Policy "open":** auto-approval.
  - **GINV-5:** a tombstoned key is not auto-approved.
  - **Full group:** the join is refused.
  - **Hostile bundle:** an unknown group pushed with no invite is refused (GINV-3).
  - **Impostor at the inviter's id:** refused at TLS, and nothing pinned.
  - **Declined:** revokes the vouch.
- **Exit criteria:** green, mutation-checked. Mutations to try:
  - accept a charter when no invite exists;
  - skip the `subjectKey == live key` check;
  - auto-approve a tombstoned key.
- **Device checks owed:** `GSEC-01`, `GSEC-05`, `GSEC-07`, `GMB-04`, `GMB-05`, `GMB-06`, `GMB-10`, `GMB-12`.
- **Docs to update:** the protocol section, and the security threat rows (leaked invite, unknown group pushed).
- **Do not:**
  - trust a charter because a bundle arrived;
  - let a non-admin sign a certificate;
  - give `PENDING` devices chat, call or file access (they only see the preview);
  - dial the inviter without the pre-installed pin.
- **Rollback:** hide the invite UI. Joined members stay members (they hold valid certificates), and nothing else depends on the
  invite path.

### GM-5: The group gate (DONE 2026-10-04 as part of SW-8)
- **Goal:** one predicate for v2 group traffic (5.7). Every v2 member receives and sends files whether or not it is paired with
  the sender.
- **Why:**
  - It is the owner's requirement.
  - The swarm (SW-8) needs it.
  - It closes the "vouched members get no files" gap (AGENTS 29, FO-04) **even before GM-4 exists**.
- **Depends on:** SW-0. It needs nothing else, so it can be the first code phase.
- **Read first:**
  - `RealFlashChatRepository`: `isActiveTrustedMember`, `isGroupPeerTrusted`, `isActiveGroupMember`, `isGroupCallPeer`,
    `isGroupCallMember`, `beginGroupAttachment`, `sendGroupAttachment` (search `files are paired-only`), and the `GroupMedia`
    branch;
  - the `CallCoordinator` group gate lambda;
  - how the three hosts pass the predicates (search `isGroupCallPeer` in `Flash.kt`, `DiscoveryEngineHolder.kt` and
    `DesktopEngine.kt`);
  - the host group-send loops (ripple 1).
- **Ripple:** rows 7, 34, 35, 36, 39 and 46. This phase touches the gates that chat and calls rely on today.
- **Tasks:**
  1. **Pin today's behaviour first.** Write a table test over the four predicates. The matrix is:
     - the peer is paired / vouched with a live key / vouched with another key / unknown;
     - the peer's row is active / inactive;
     - this device is active / left / removed;
     - the group is v2 / legacy.

     Commit the test (when the owner allows commits) **before** the refactor.
  2. **Add `GroupGate` and `RosterGroupGate`** (5.7). The old predicates delegate to it; keep their public names for the hosts.
     - `allows(CHAT)`, `allows(CALL)` and `isMember` must equal the old answers on the whole matrix.
     - Only `FILE_SEND` / `FILE_RECEIVE` / `FILE_SERVE` differ, and only on v2 groups, exactly as the 5.7 table says.
  3. **Files.**
     - `beginGroupAttachment`, the recipient filter in `sendGroupAttachment` and the `GroupMedia` receive branch use the gate on
       v2 groups.
     - Legacy groups keep `isActiveTrustedMember`.
     - Update the comment "files are paired-only" to say what is true now.
  4. **The file that follows.** Verify the inbound transfer for a group member that is not paired with the sender:
     - the FILE_START that matches a parked `GroupMedia` is accepted, as for paired senders today;
     - a FILE_START from the same peer that matches nothing follows the 1:1 rules: refused, or whatever today does for an
       unpaired peer (record which).

     `DestinationPolicy.evaluateOffer` has an `isTrustedPeer` parameter but no production caller was found on 2026-10-04.
     Confirm that.
  5. **The hosts.** Check that no host loop re-filters group recipients by `trustStore.isTrusted`. If one does, route it through
     the gate.
  6. **Call legs** (ripple 46). Add `groupChanges: Flow<String> = emptyFlow()` to the group gate wiring of `CallCoordinator`. On an
     event for a group with a live group session, re-check `allows(CALL)` for each leg and end the legs that fail, with the
     reason "removed".
  7. **The 1:1 isolation tests** (ripple 36, GINV-8):
     - a 1:1 text from a group-only peer is dropped;
     - it is not in the PTT recipients;
     - a 1:1 call from it is refused.
  8. **`docs/security.md`:** record ripple 35 (chunks to group-only peers are protected by TLS only).
- **Tests:**
  - the matrix test (before and after);
  - a file to a vouched member not paired with the sender, sent and received end to end in `DesktopInteropHarness`;
  - the legacy group is unchanged;
  - the call leg is dropped after a removal;
  - the isolation tests.
- **Exit criteria:** the matrix is identical for CHAT/CALL/member, the file path works for vouched members, green, and
  mutation-checked. Mutations to try:
  - use `isTrustedPeer` for FILE on v2;
  - skip the local-active check;
  - skip the live-key check.
- **Device checks owed:** `GMB-01`, `GMB-02`, `GMB-03` / `GSEC-08`.
- **Do not:**
  - change the 1:1 gates;
  - read the pin store inside the gate (GINV-2: a vouch pin is not membership);
  - cache gate answers across `changes`.
- **Rollback:** point the file paths back at `isActiveTrustedMember`. The gate class can stay.

### GM-6: Removal → rotation, "Change group code" (DONE 2026-10-05)
- **Status:** Complete. GroupRotationEntity/Dao, schema 9, canonical signing, wire frames (GsStale, GsSecretRequest, GsSecret), tombstone+rotation in one transaction, startup recovery of unrotated tombstones, gate+removedIds secret handover, concurrent rotation tie-breaker & re-rotation broadcast, "Change group code", all 7 tests in GroupRotationTest green, removal ripple green.
- **Goal:** every removal changes the group's secret. The new secret reaches every remaining member, including members that were
  offline, and never reaches the removed device.
- **Depends on:** GM-4.
- **Read first:**
  - `SignedGroups.removeMember`, `leave`, `removalNoticeFor`, `bundleFor`;
  - 5.7 "Rotation without sealing";
  - ADR-063.
- **Ripple:** row 42. Removal is device-verified behaviour (ADR-044 V2 removal ripple), so its existing tests must stay green.
- **Tasks:**
  1. **`GroupRotation`.**
     - The rotation notice is signed by an admin with the domain `"flash-grot-v1"` and validated like a certificate (the admin
       lookup of `checkCert`).
     - It is stored per group as the latest notice.
     - `bundleFor` and the removal bundles carry the latest notice. If old decoders reject unknown fields, send it as its own
       frame (SW-0 task 4).
  2. **Removal = tombstone + rotation in one transaction** (GINV-4). On start, if a tombstone this device signed is newer than
     the latest rotation it signed, rotate again.
  3. **Handover.**
     - On a session with a peer where `allows(CHAT)` is true, a member whose current epoch is older than the latest notice it
       holds sends `GsSecretRequest`.
     - A member holding that epoch answers `GsSecret`, after checking `allows(CHAT)` **at that moment**.
     - **SW-0 (2026-10-04):** never to a device id named in `removedIds` of any rotation notice it holds, even if that device's
       tombstone has not reached it yet (protocol "Secret handover", security 10.2).
     - The receiver checks the commit, then stores the secret (`source = HANDOVER`).
  4. **`GsStale`:** when a peer proves an older epoch (GM-3), send the notice if the peer is an active member. The peer then asks
     for the secret as in task 3.
  5. **Two admins rotate at the same time.**
     - For the same `newEpoch`, the notice with the smaller `rotationId` (16 random bytes, hex) wins, and every device adopts it.
     - An admin whose own rotation lost rotates once more (`newEpoch + 1`), listing its removals, so no removal is left
       without a rotation.
  6. **"Change group code":** an admin action (reason `MANUAL`). Old invites stop working; current members are unaffected.
  7. **Leave:** does not rotate by default (row 42). Write that down in the ADR.
- **Tests:**
  - remove → rotate → an offline member gets the secret from a **non-admin** member;
  - the removed device asks for the secret and is refused;
  - a secret that does not match the commit is refused;
  - two admins rotate concurrently and both converge;
  - a crash between the tombstone and the rotation is recovered on start;
  - an old invite after a rotation is refused (`GsStale` to a non-member gives no notice);
  - the existing removal-ripple tests stay green.
- **Exit criteria:** green, mutation-checked. Mutations to try:
  - hand over without the gate check;
  - skip the commit check;
  - invert the concurrency winner.
- **Device checks owed:** `GSEC-02`, `GSEC-04`, `GSET-04`, `GMB-07`, `GMB-08`, `GMB-13`.
- **Do not:**
  - put a secret in a notice, a bundle or a log;
  - make chat or calls wait for the new secret (O-13).
- **Rollback:** stop creating rotations. Removal keeps working as today (only the "old invite rejoins" protection is lost).

### GM-7: Give existing v2 groups a secret (O-11) — DONE
- **Goal:** groups created before GM can invite too, without a new group.
- **Depends on:** GM-6.
- **Tasks:**
  1. When an admin device on the new build holds a v2 group with no rotation notice, it creates epoch 1 with a notice (reason
     `UPGRADE`, `prevEpoch = 0`). The secret then spreads by handover (GM-6).
  2. Concurrent upgrades by two admins resolve with the GM-6 rule.
  3. Until a group has a secret, "Invite" says "An admin needs to open this group on the new version first".
  4. Legacy `g-` groups are never upgraded (D5).
- **Tests:**
  - upgrade, handover, an invite from a non-admin member after the upgrade;
  - a member on an old build is unaffected (it ignores the notice).
  - verified in `GroupUpgradeTest.kt` (all 4 scenarios passed).
- **Device checks owed:** `GMB-09`.
- **Rollback:** skip the upgrade. Those groups simply cannot invite.

### GM-8: Finding members: address hints, optional beacon — DONE
- **Goal:** a newcomer reaches a member quickly. Members can find each other without pairing.
- **Depends on:** GM-4.
- **Tasks:**
  1. **Hints.** The invite's hints are dialed first (GM-4 task 3). If none answers within 30 s, wait for discovery. Show M-03.
  2. **Any member, not only the inviter.** A discovered device that advertises `gs1` is tried with the proof. Its key is unknown,
     so the session is an ordinary discovered (not named) session, and the TOFU trap does not apply.
  3. **GM-8b, the beacon (design 1.7): optional.** Postponed unless owner requests, per design 1.7.
- **Tests:**
  - `GroupMembershipStatusTextTest` verifies Table 8.3 user-facing sentences (M-01 to M-22).
  - `GroupDiscoveryTest` verifies:
    - `testAddressHintsDialedInStrictOrder`: first hint tried first, on failure second tried, on success remaining skipped;
    - `testFallbackToDiscoveryAndTimeoutSentenceM03`: when hints fail/timeout, invite stays in PENDING_CONTACT and displays M-03;
    - `testProofWithDiscoveredMemberNotInviter`: newcomer discovers an ordinary member advertising gs1, proves membership over unnamed session, sends join request, forwarded to admin and approved;
    - `testUnrelatedDiscoveredPeerDoesNotBreakPendingInvite`: unrelated peer safely fails proof without corrupting pending invite;
    - `testInvalidInviteReturnsM01`: malformed invite link returns M-01 error sentence and stores nothing.
- **Device checks owed:** `GMB-04` (hints).

### GM-9: Group settings (DONE — 2026-10-05)
- **Goal:** the signed and device-local settings of design 2.1, used by the gate and the join flow.
- **Depends on:** GM-4.
- **Tasks:**
  1. **The signed `GroupSettings` object.**
     - Fields: `groupId`, `version` (monotonic), `joinPolicy` (`APPROVE` / `OPEN`), `inviteSharers` (`ALL` / `ADMINS`),
       `maxMembers` (at most 20), `swarmServing`, `membersMayAdd`, `opId`, signer, signature (`flash-gset-v1` domain).
     - Carried in roster bundles (`GroupWireFrame.Bundle`).
     - The highest valid `version` wins; on a tie, the smaller `opId` (lexicographical).
     - Only admins (owner or co-owner) sign it.
     - **The group name is not a setting** (ADR-074 rule 4): it is part of the charter, which must stay equal to the stored one.
  2. **Device-local preferences:** `GroupLocalPreferences` (`serveToGroup`, `serveWifiOnly`, `batteryThresholdPercent`, `keepAvailableDays`, `autoAcceptSizeBytes`).
     Stored in Room `group_preferences` table (migration 9→10). They never appear in any frame.
  3. **Wire them in:**
     - GM-4 reads `joinPolicy` (`isGroupJoinOpen`) and `inviteSharers` (restricts non-admin invite generation);
     - `GroupGate` (`RosterGroupGate`) evaluates `settings.swarmServing && prefs.serveToGroup` for `FILE_SERVE`;
     - `addMembers` (`addGroupMembers` and `SignedGroups.addMembers`) reads `membersMayAdd` (allowing active non-admin members to add `ROLE_MEMBER`);
     - `maxMembers` enforced on join requests and `addV2MembersLocked`.
- **Tests verified in `GroupSettingsTest`:**
  - version ordering (v2 overwrites v1; v1 rejected against v2);
  - forged update rejection (non-admin signer rejected);
  - tie-break on equal version (smaller opId wins);
  - local preferences persist and never appear in any frame;
  - old build compatibility (bundle without settings decodes null; forward-compatible unknown fields ignored);
  - gate restriction on `FILE_SERVE` (both flags must be on);
  - `inviteSharers` restriction (admin-only sharing enforced);
  - `membersMayAdd` restriction (non-admin adding members permitted when on);
  - `maxMembers` restriction enforced on group capacity.
- **Device checks owed:** `GSET-01` to `GSET-03`.

### GM-10: Membership UI (COMPLETE)
- **Goal:** people can invite, join, approve and see what is happening, without protocol words.
- **Depends on:** GM-4, GM-6, GM-9, and the UI docs at **DESIGNED** (AGENTS 34, ids reserved in SW-0 task 8).
- **Contents built & verified:**
  - **Group header and members sheet:** "Invite people" (share sheet + copy link) via `FlashGroupInviteSheet`, guarded by `canShareInvite`.
  - **Join:**
    - paste link dialog via `FlashJoinGroupDialog` (Android & Desktop);
    - Android deep link intent filter for `flash://g/*` in `AndroidManifest.xml`, handled via `GroupInviteCodec` safely;
    - confirmation dialog with group name, inviter name, and "Join".
  - **Pending screen:** Table 8.3 plain-English status sentence via `GroupMembershipStatusText`, member preview, and "Cancel request".
  - **Admins:** "Join requests" section in `FlashGroupMembersSheet` with Approve / Decline; collapsed notifications per group in `FlashNotificationManager` and `DesktopNotificationManager`; previously removed applicant marked with amber "Previously removed" warning badge.
  - **Settings sheet:** `FlashGroupSettingsSheet` presenting signed group settings (`GroupSettings`) and device-local preferences (`GroupLocalPreferences`), plus admin-only "Change group code" confirmation.
  - **Errors in plain words:** Table 8.3 sentences without protocol jargon.
  - **Inline preview (O-14):** `FlashInlineInviteCard` rendering rich preview with direct Join action in 1:1 and group chats when an invite URI is present.
- **Tests verified:**
  - `FlashGroupSettingsMathTest`: pure math, member bounds clamping, descriptions, permission gating.
  - `FlashGroupInviteJoinMathTest`: invite URI parsing, title formatting, inviter caption, removed status detection.
  - All 340 tests in `:ui:chat:jvmTest` passing.
  - `:desktop:compileKotlinJvm` and `:app:compileDebugKotlin` clean build.
- **Device checks owed:** `GMB-04`, `GMB-14`, plus the UI checks of the new component docs (tracked in `docs/testing/TEST-BACKLOG.md`).

### GM-11: Membership device checks
- **Tasks:**
  1. **Run the checks in this order:**
     1. `GMB-02` and `GSEC-07` (nothing broke);
     2. `GMB-01` (files to unpaired members);
     3. `GSEC-01` (the owner's scenario);
     4. then `GSEC-02` to `GSEC-05`, `GSEC-08`, and `GMB-03` to `GMB-14`;
     5. then `GSET-01` to `GSET-04`.
  2. **Record each result** as AGENTS 35 says. A FAIL gets a new ERROR entry.

---

## 8. Error and recovery catalogue

- **Columns:**
  - **State** is the `FlashTransferState` plus the wait reason or fail reason;
  - **Back when** names the event that resumes it;
  - **Tests** lists unit (U), simulator (SIM) and device (SWM) checks.
- **Sentences** are examples; SW-10 makes them final in `TransferFailureText`.
- **The `Tests` column must have no empty cell at the end of SW-10.**

### 8.1 Wait reasons and what moves each one (INV-9)
Every wait reason and its unblocking event is verified in `SwarmWaitReasonRecoveryTest` (`:core:swarm`). User-facing sentences are verified in `TransferFailureTextTest` (`:core:transfer`).

| Wait reason | Meaning | Moved on by | Tests |
|---|---|---|---|
| `WAITING_FOR_SENDER` | Missing pieces exist only at the origin, and the origin is not connected. | The origin's session comes up; `SUMMARY`/`HAVE` from anyone who has them; the 15-minute wake. | `SwarmWaitReasonRecoveryTest`, U, SIM-02, SIM-03 |
| `WAITING_FOR_HOLDERS` | The origin cannot serve them (left the group, or its source is lost), and no connected member has the missing pieces. | Any holder's session comes up; `HAVE`; `SOURCE_STATUS(RESTORED)`; retention expiry ends it. | `SwarmWaitReasonRecoveryTest`, SIM-04 |
| `WAITING_FOR_NETWORK` | No usable network. | Network up. | `SwarmWaitReasonRecoveryTest`, U |
| `WAITING_FOR_SPACE` | Not enough free space. | The space re-check on each wake or app start; the user taps Retry. | `SwarmWaitReasonRecoveryTest`, SIM-18 |
| `WAITING_FOR_STORAGE` | The partial or destination location is unusable (permission, folder gone). | The user chooses a location; app start; user taps Retry. | `SwarmWaitReasonRecoveryTest`, U |
| `WAITING_FOR_SYSTEM` | The OS stopped us (service time limit, battery saver). | The service starts; app start; the 15-minute wake; SystemResume. | `SwarmWaitReasonRecoveryTest`, SIM-19 |
| `WAITING_FOR_SESSION` | Holders are known but no session is up yet (planner, ceiling, dial budget). | A session comes up; the planner's next round. | `SwarmWaitReasonRecoveryTest`, U |

### 8.2 Catalogue
| ID | Condition | Detected by | State | Sentence | Automatic action | Back when | Tests |
|---|---|---|---|---|---|---|---|
| E-01 | Origin offline before anyone holds anything | Classifier: missing pieces held only by the origin, origin not connected | Queued / `WAITING_FOR_SENDER` | "Waiting for Alex to come online" | Nothing is sent while waiting | Origin session up | U, SIM-03, SWM-10 |
| E-02 | Origin offline mid-send (half sent) | Same, after the members exchanged the union | Queued / `WAITING_FOR_SENDER` | "Waiting for Alex · 50 of 100 MB here" | Members exchange the union first | Origin session up | U, SIM-02, SWM-09 |
| E-03 | Origin offline, but the union is complete | Classifier: every piece available from members | Transferring | — | Finish from members | — | SIM-02, SWM-09 |
| E-04 | A holder leaves mid-piece | `PeerDown` | Transferring | — | In-flight pieces return to the pool; choose another source | — | U, SIM-05 |
| E-05 | Origin and every holder of the missing pieces are offline | Classifier | Queued / `WAITING_FOR_HOLDERS` or `_SENDER` | "Waiting for a device that has the missing parts" | none | Any holder up | SIM-04 |
| E-06 | This device loses the network | `NetworkDown` | Queued / `WAITING_FOR_NETWORK` | "Waiting for Wi-Fi" | Return in-flight pieces | `NetworkUp` | U, SWM-26 |
| E-07 | A peer has no `sw1` (old build) | Features | — | — | The origin sends that peer today's push; others never send it FSW1 | — | SIM-14, SWM-16 |
| E-08 | A known holder cannot be reached (hotspot isolation, dial fails) | `requestSession` gives no session within 30 s | Transferring or a wait | — | Try other holders and the origin; retry that peer after a backoff | A session comes up | U, SWM-22 |
| E-09 | Session ceiling or dial budget full | The planner declines | Queued / `WAITING_FOR_SESSION` | "Connecting to group members…" | Use the sessions that exist | A session comes up | U |
| E-10 | This device is in ECO | Mode | (own downloads unaffected) | — | Do not serve; `SUMMARY.servingEnabled = false` | Mode change | U, SIM-13, SWM-21 |
| E-11 | A peer answers `REJECT(BUSY)` | Frame | Transferring | — | Halve the window, retry after `retryAfterMs`, try others | Backoff ends | U |
| E-12 | A request times out | Tick | Transferring | — | Return the pieces, halve the window, back off that peer | — | U, SIM-05 |
| E-13 | A piece fails its hash | Driver verify | Transferring | — | Discard, strike the source, re-request elsewhere; 3 strikes ban that source for this content | — | U, SIM-06, SWM-19 |
| E-14 | The whole-file hash fails after all pieces passed | Finalize | Verifying, then Transferring | "Checking the file… re-downloading damaged parts" | Re-verify every piece, re-fetch the bad ones once; a second failure gives `FAILED(DAMAGED)` with the ADR-068 wording | — | U, SWM-19 |
| E-15 | The manifest does not hash to the root | Manifest parse | Transferring | — | Strike the peer, fetch the manifest from another | — | U |
| E-16 | Invalid announcement signature | Messaging verify | (no row) | — | Ignore; log `SWARM: event=announce-rejected` | — | U |
| E-17 | Hostile manifest or frame sizes | Codec limits | — | — | Drop and count as malformed; after 3 per minute, ignore that peer's FSW1 for 10 minutes (the session stays up) | — | U |
| E-18 | A stored piece fails re-verification on serve (disk corruption) | Re-hash on serve (INV-2) | Transferring (local) | — | Do not send; clear the bit; re-fetch | — | U |
| E-19 | The origin's source was deleted or moved | `openSource` null or identity gone | Members: complete the union, the rest `FAILED(SOURCE_LOST)` | "Alex's file is no longer available" | The origin sends a signed `SOURCE_STATUS(LOST)` | The origin re-picks the same file (root matches), sends `RESTORED` | U, SIM-20, SWM-25 |
| E-20 | The origin's source changed (a piece re-hash fails on read) | INV-2 at the origin | Same as E-19, reason `CHANGED` | "Alex changed the file after sending it" | `SOURCE_STATUS(LOST, CHANGED)`; never serve the changed bytes | Re-pick of the original content | U, SIM-20 |
| E-21 | The origin lost its URI permission after a restart | `openSource` throws SecurityException | Origin: "Pick the file again to keep sharing" | as stated | `SOURCE_STATUS(LOST, PERMISSION)`; O-3 fallback | The user re-picks; the root matches | U, SWM-13 |
| E-22 | Origin process death or reboot | App start | Origin rows restored | — | Load records, reopen the source, check identity, serve | App start | SIM-11, SWM-13 |
| E-23 | Not enough space at Accept | `freeBytesFor` | Queued / `WAITING_FOR_SPACE` | "Needs 1.2 GB, 300 MB free" (FA-2 wording) | none | Space found on a re-check | U, SIM-18, SWM-18 |
| E-24 | The disk fills mid-download | Write error or space check | Queued / `WAITING_FOR_SPACE` | same | Keep the verified pieces | Space found on a re-check | U, SWM-18 |
| E-25 | The partial file was deleted (cleaner app, user) | Start-up check: `.part` missing or smaller | Transferring | — | Reset bits for missing ranges, re-fetch | — | U |
| E-26 | The partial file was corrupted | Re-hash on serve, and finalize | Transferring | — | Clear the bad bits, re-fetch | — | U |
| E-27 | Destination or partial location unusable (desktop folder removed) | Finalize or open fails | Queued / `WAITING_FOR_STORAGE` | "Choose where to save files" | none | The user picks a location; app start | U |
| E-28 | An I/O error on write | Exception | Transferring | — | Retry the piece once, then `WAITING_FOR_STORAGE` | as E-27 | U |
| E-29 | Receiver process death | App start | rows restored | — | Load records; only persisted bits count (INV-4) | App start | SIM-10, SWM-14 |
| E-30 | Android 15+ `dataSync` six-hour limit | `onTimeout` | Queued / `WAITING_FOR_SYSTEM` | "Paused by Android; continues automatically" | `SystemSuspend`, **no tombstone** (INV-10) | Service start; the user opens the app (the platform resets the six-hour timer when the app comes to the foreground); wake | U, SIM-19, SWM-17 |
| E-31 | OS freezer (Transsion HiOS) | Not detectable in code | — | (help text per the HIB guide) | none | The device is unfrozen | SWM-26 (document only) |
| E-32 | App update mid-swarm | Schema migration | rows restored | — | Migration keeps rows; FSW1 version rule | App start | U (migration), SWM-14 |
| E-33 | Device reboot | Same as E-22 / E-29 | | | | | SWM-14 |
| E-34 | This device was removed from the group | `MembershipChanged(localActive = false)`, or `REJECT(NOT_MEMBER)` | `Failed(NOT_MEMBER)` | "You are no longer in this group" | Stop, delete partials, never serve that group's content | — | U, SIM-07, SWM-15 |
| E-35 | A serving peer was removed | `MembershipChanged` | Transferring | — | Stop using it; its pieces no longer count | — | U, SIM-07 |
| E-36 | The origin left or was removed after announcing | Roster | Members: continue from holders; missing pieces give `WAITING_FOR_HOLDERS`, then `FAILED(EXPIRED)` at retention | "Alex left the group before everyone had the file" | The departed origin stops serving (O-9); its old cancel still verifies with the stored key | A holder appears | U |
| E-37 | The group was deleted on this device | Messaging | `Cancelled` (local) | — | Stop, delete partials and records | — | U |
| E-38 | A vouched member's key is not live | Gate false | (peer not used) | — | Treat it as not allowed until its key is live | `membershipChanges` | U |
| E-39 | The origin cancelled while this device was offline | `SUMMARY` tombstone | `Cancelled` | "Cancelled by Alex" | Apply before any REQUEST (INV-5); delete the partial | — | SIM-08, SWM-11 |
| E-40 | Forged cancel (signature not the origin's) | Verify | unchanged | — | Ignore, strike, log | — | U, SIM-09 |
| E-41 | Cancel arrives after this device completed | Tombstone applied to `COMPLETE` content | `Completed` (kept, O-2) | — | Stop serving it for this announcement | — | U |
| E-42 | A receiver cancels for itself | User | `Cancelled` (local) | — | Delete the partial; send nothing; never serve it | — | U, SWM-12 |
| E-43 | The origin deletes the message for everyone | Messaging | `Cancelled` | "Deleted by Alex" | Tombstone reason `DELETED` | — | U |
| E-44 | Unknown FSW1 version or type | Codec | — | — | Unknown type: ignore. Unknown version: `REJECT(UNSUPPORTED)`; treat that peer as legacy for this session | — | U |
| E-45 | Malformed-frame flood | Codec counter | — | — | As E-17 | — | U |
| E-46 | Request flood from a member | `ServePolicy` caps | — | — | `REJECT(BUSY)`; log the rate | — | U, SIM-16 |
| E-47 | Retention expired before completion | Tick | `Failed(EXPIRED)` | "Expired: the missing parts were not available for 7 days" | Delete the partial | — | U |
| E-48 | Clocks disagree between devices | Not applicable | — | — | Never order by remote time; remote times are display only | — | U (review rule) |
| E-49 | The same root is announced twice (re-send, or another member sends the same file) | Store lookup | `Completed` ("already on this device") or joins the existing content | "Already on this device" | Dedup; become a source at once | — | U, SWM-06 (existing) |
| E-50 | The same root in two groups | Announcement held per group | — | — | INV-8: serve each group only what was announced in it | — | U, SIM-15, SWM-08 (existing) |

### 8.3 Membership catalogue (track GM)
Same columns, except that **State** is the invite or membership state. Sentences are examples; GM-10 makes them final.

| ID | Condition | Detected by | State | Sentence | Automatic action | Back when | Tests |
|---|---|---|---|---|---|---|---|
| M-01 | Invite is malformed, truncated or an unknown version | `GroupInviteCodec` returns null | — | "This invite is not valid. Ask for a new one." | Nothing stored | — | U (GM-2), GMB-04 |
| M-02 | Invite for a group this device is already in | `acceptInvite` | — | "You are already in <group>." | Open the group | — | U |
| M-03 | No member reachable (hints fail, nobody discovered) | 30 s without a session | `PENDING_CONTACT` | "Waiting for a member of <group> to be nearby." | Keep trying hints on each network change; try discovered `gs1` devices | A member's session comes up | U, GMB-04 |
| M-04 | The device at the inviter's id presents another key | TLS refuses against the pre-installed vouch | `PENDING_CONTACT` | "The device that answered is not the one that shared this invite." | Nothing pinned; log `SECURITY` | The real inviter connects | U, GMB-05 |
| M-05 | Proof fails (wrong secret) | `GroupProof` | unchanged | "This invite is no longer valid." (after 3 failures with different members) | Rate-limit that peer | — | U, GSEC-02 |
| M-06 | Invite from before a rotation | `GsResult(stale)` (the joiner is not on the roster, so no notice) | `PENDING_CONTACT` | "This invite was replaced. Ask for a new one." | Stop dialing for it | A new invite | U, GSEC-02, GSEC-04 |
| M-07 | Waiting for approval, no admin online | Request forwarded, no decision | `PENDING_APPROVAL` | "Waiting for an admin of <group> to approve." | Members re-forward on each admin session | An admin decides | U, GSEC-05, GMB-06 |
| M-08 | An admin declines | `GsJoinDecision(refused)` verified | `REFUSED` | "Your request to join <group> was declined." | Revoke the inviter vouch; forget the secret | — | U |
| M-09 | The group is full (20, or the `maxMembers` setting) | Admin check | `REFUSED` | "<group> is full." | as M-08 | — | U |
| M-10 | The peer has no `gs1` (old build) | Features | — | "Update Flash on <device> to use invites." | Never send `GS*` frames to it | It updates | U, GSEC-07 |
| M-11 | Pin conflict: the id is known here under another key | `installVouches` verdict `CONFLICT_*` | member missing on this device | "<device> is known to this device under a different key." | Keep the existing pin (pairing wins) | The user resolves pairing | U |
| M-12 | This member missed a rotation | It holds a notice newer than its secret, or receives `GsStale` | member (active) | none in chat; "Invite" says "Getting the new group code…" | `GsSecretRequest` to connected members | A member hands it over | U, GMB-07 |
| M-13 | A removed device tries to rejoin with an old invite | Stale epoch, not on the roster | refused | (on that device) M-06 | — | — | U, GSEC-04 |
| M-14 | A removed device rejoins with a new invite, policy "open" | GINV-5 | `PENDING_APPROVAL` | Admins see "<device> was removed before" | Never auto-approve | An admin decides | U, GMB-12 |
| M-15 | A handed secret does not match the notice's commit | `GroupSecretCommit` | unchanged | — | Refuse, strike the sender, log `SECURITY`, ask another member | Another member hands the right one | U |
| M-16 | Two admins rotate at once | Two notices, same epoch | — | — | Smaller `rotationId` wins; the losing admin rotates again | — | U, GMB-08 |
| M-17 | The secret store is lost (reinstall, Keystore reset) | `current(groupId)` is null | member (active) | "Invite" says "Getting the group code…" | `GsSecretRequest` (O-13: chat, calls and files continue) | A member hands it over | U, GMB-13 |
| M-18 | A leaked invite under policy "open" | Admins see an unknown joiner | member | — | An admin removes it and the group rotates (GM-6) | — | GSEC-05 |
| M-19 | Join-request flood | Per-peer and per-group rate limits | — | — | Drop over-limit requests; collapse notifications | — | U |
| M-20 | The joiner disconnects while pending | Session down | `PENDING_APPROVAL` | unchanged | The request stays stored on members and admins | Any session with a member | U |
| M-21 | Members on an old build | They accept the certificate through vouching (row 40) | member | — | — | — | U (GM-4 task 10), GMB-10 |
| M-22 | No admin exists any more (owner gone, no co-owner) | No admin key in the roster | `PENDING_APPROVAL` forever | "No admin of <group> is available, so nobody can approve new members." | None. The ownership decision belongs to the owner (ERROR-089, D then A) | An ownership fix lands | U, EDGE-* |

---

## 9. Test strategy summary
| Level | Where | What |
|---|---|---|
| Golden vectors | `:core:swarm` commonTest | Every FSW1 type, the manifest root, the statements. Framing v2 vectors stay green. |
| Unit / table | `:core:swarm` commonTest | Every engine step (SW-4), every E-entry. |
| Property | `:core:swarm` commonTest | INV-1 to INV-12 over random seeded event sequences. |
| Simulator | `:core:swarm` commonTest `sim/` | SIM-01 to SIM-20. |
| Persistence | `:core:persistence`, `:core:engine` | Migration 6 → 7, DAO, adapter. |
| Storage | `:core:engine` jvmTest | Random I/O, identity, finalize, crash ordering. |
| Integration | `:core:engine` jvmTest `DesktopInteropHarness` | Half-sent, cancel while offline, forged cancel, zero-change proof. |
| Mutation | every phase | Break each guard, see a test fail, restore. |
| Device | `docs/testing/TEST-BACKLOG.md` 4w | `SWM-09` to `SWM-29`, plus `SWM-06..08` (4v) and `SWM-01..05` (4u). |
| Membership vectors and loopback | `:core:security` and `:core:messaging` commonTest | GM-1 vectors (KDF, proof, commit, invite); the GM-3/GM-4/GM-6 loopbacks with in-memory repositories (join, relay, stale, rotation, concurrency). |
| Gate matrix | `:core:messaging` commonTest | GM-5: the before/after table over paired / vouched / unknown × active / inactive × local state × v2 / legacy. |
| Membership device checks | `docs/testing/TEST-BACKLOG.md` 4v and 4x | `GSEC-01..08`, `GSET-01..04` (4v), `GMB-01..14` (4x). |

---

## 10. Owner decisions

### 10.1 New decisions for this plan
**Status 2026-10-04 (second owner message, section 1.3):**
- O-1 to O-4 and O-6 to O-10 are **ACCEPTED as recommended**.
- O-5 is **REPLACED** by the owner's rule.
- O-11 to O-15 arose from that rule. Their recommended default applies unless the owner overrules it before SW-0 ends.
- **SW-0 (2026-10-04):** the answers above are recorded in ADR-070 to ADR-075 (PROPOSED). O-11 to O-15 are written into ADR-073 with their recommended defaults; the owner accepts or amends them together with the ADRs.

| # | Question | Recommendation |
|---|---|---|
| **O-1** | Retention: how long an unfinished group file waits for missing parts, and how long a finished copy stays available to others. | 7 days for both; tombstones kept 14 days. |
| **O-2** | After the origin cancels, what happens to copies that already finished? | Keep them (a delivered file is the receiver's), but stop serving them. "Delete for everyone" stays the way to remove the message. |
| **O-3** | The origin's persistable URI permission failed (rare provider). | No staging copy (it doubles storage). Serve while the app runs; if access is lost, ask the sender to pick the file again, and resume when the root matches. |
| **O-4** | Swarm only in v2 signed groups (`g2-`)? | Yes. Legacy groups keep today's push (their membership is forgeable, ERROR-082). |
| **O-5** | ~~Vouched members (paired with the owner only) get files through the swarm?~~ | ~~Yes. Group chat already trusts them (ADR-044 V2), and it closes the FO-04 gap.~~ **REPLACED by the owner (2026-10-04):** every active member of a group receives, sends and serves group files **whether or not it is paired with anyone**, through the swarm and through today's push. Delivered by GM-5 (the gate) and GM-4 (joining without pairing). |
| **O-6** | `:core:engine` depends on `:core:swarm` with `api` + attach (PTT style) or `compileOnly` (calling style)? | `api` + attach (2.4). |
| **O-7** | On Android's six-hour service limit, should 1:1 transfers also pause instead of cancel? | Yes, but as a separate small change after SW-2; record it as its own ADR line. |
| **O-8** | Turn the swarm on by default in the Flash app, and when? | Only after SW-12 shows `SWM-09..15` PASS on at least 3 devices including Windows. |
| **O-9** | An origin that left or was removed from the group: may it still serve its own file? | No. It follows the removal rules; its cancel still counts. |
| **O-10** | Group files with auto-accept off: does each member tap Accept? | Same rule as today's group files (no new policy). |
| **O-11** | Do groups created before GM get a secret automatically? | **Yes.** The first admin device on the new build upgrades the group (GM-7). Members on old builds are unaffected. |
| **O-12** | Who may share the invite? (This fills design D6.) | **Every member, by default.** Under "admin approves", an invite alone admits nobody. The setting `inviteSharers = ADMINS` (GM-9) can restrict it. It is advisory only: whoever holds the code can pass it on. |
| **O-13** | Must group traffic wait for a per-session secret proof (design 1.5 condition 1)? | **No.** The roster key plus the live TLS key already authenticate the device; the secret is for joining and rotation (1.3). |
| **O-14** | How are invites delivered before QR exists? | **A share/copy link** (`flash://g/1/...`), pasted on desktop and opened by tap on Android. Optionally, a "Join" button when the link arrives in a 1:1 Flash chat. QR waits for DR4, which is postponed. |
| **O-15** | Does a new member see the group's earlier messages? | **Yes, as today** (catch-up on join, ADR-059). A setting can come later if wanted. |

### 10.2 Status of the design's decisions
| Decision | Status for this plan |
|---|---|
| ~~D1, D2, D3, D5, D6 (group secret)~~ | ~~Not needed for the swarm; separate track.~~ **Superseded 2026-10-04:** see the rows below. |
| D1 (reverse ADR-044's rejection of a group secret) | **ACCEPTED by the owner (2026-10-04).** It needs an ADR in SW-0. 5.7 refines it: no per-session proof for traffic (O-13), and rotation without sealing. |
| D2 (default join policy) | **ACCEPTED as recommended:** admin approves. |
| D3 (secret format) | **ACCEPTED as recommended:** app-generated 256-bit secret in a link. No typed codes, so no PAKE. |
| D4 (1:1 and pairing unchanged) | **ACCEPTED.** GINV-8; tested in GM-5. |
| D5 (legacy `g-` groups not migrated) | **ACCEPTED.** Existing **v2** groups are upgraded (O-11). |
| D6 (who may share the invite) | Filled by O-12: every member, restrictable by a setting. |
| D7 (Kotlin, no C++) | **ACCEPTED as recommended.** |
| D8 (keep the transport) | **ACCEPTED.** Only the `caps` seam changes it. |
| D9 (ECO does not serve) | **ACCEPTED.** ECO still downloads. |
| D10 (retract) | **Answered by the owner:** cancel everywhere (O-2 for finished copies). |
| D11 (routed networks only) | **Superseded:** the swarm runs everywhere, without speed claims on hotspots. |
| D12 (order) | **Changed:** no speed gate; measurements tune. |
| D13 (third-party code policy) | **ACCEPTED as recommended (2026-10-04):** reference freely; copy only small Apache-2.0 pieces, each with a `NOTICE` entry and a header comment; never GPL. |

---

## 11. Risks and honest limits
- **Size of the work.** This is the largest feature since the call stack: about 3,000 to 5,000 lines of new code, and roughly as
  much test code **[estimate]**. The pure parts (SW-3 to SW-5) are the safest to give to a smaller model. SW-8 (host integration)
  needs the most care.
- **Three hosts.** Any host left unwired is a silent gap. The binding concentrates the logic, but each root still needs its
  three calls (router, features, attach). The exit criteria list them.
- **The shared Wi-Fi cell.** Swarming costs airtime twice per delivery (design 4.1). With equal rates it is not faster than
  today; its value is reliability. Do not promise speed.
- **Battery.** Serving keeps the radio busy. Serving after completion therefore does not keep the foreground service unless the
  user opts in.
- **Android background limits** (six-hour `dataSync`, OEM freezers) bound what "continues automatically" means on phones.
  Windows has no such limit.
- **Removed members keep what they already received** (as with chat). There is no per-sender encryption of pieces; the session
  TLS and the gate protect them.
- **Mixed versions** reduce the benefit: an old member is fed by the origin only.
- **API.** One additive data-class parameter (`waitReason`) is binary-incompatible for already-compiled consumers; release notes
  and a minor version bump.

**Membership (track GM):**
- **This reverses a security decision on purpose (D1).** What it does **not** give:
  - no forward secrecy;
  - a removed member keeps what it already received;
  - under policy "open", a leaked invite admits a stranger until an admin removes it.

  "Admin approves" (the default) is the real control.
- **File chunks to group-only peers are protected by TLS only** (ripple 35). There is no app-layer key without pairing. A per-pair
  key derived from the group secret was considered and **not planned**: every member holds the secret, so it would add little
  against the members themselves. Revisit only if a threat needs it.
- **The joining path changes how a charter is trusted** (GINV-3). A bug there could let a stranger push a group onto a device.
  GM-4 has the refusal test and its mutation for exactly this.
- **Admin availability.** Approvals need an online admin (owner or co-owner). A group whose owner is gone and that has no
  co-owner cannot admit anyone (M-22) until the owner-loss decision lands (ERROR-089).
- **Group-only peers on old builds** cannot join. Members on old builds keep working.
- **Not verified:**
  - Android Keystore key agreement (the reason sealing was dropped);
  - whether the chat database is encrypted at rest on each host (GM-2 task 3 finds out);
  - whether a FILE_START from an unpaired peer that matches no parked frame is refused today (GM-5 task 4).

## 12. Not verified, and sources
- **Facts read in code on 2026-10-04 [code]:** every ripple-map row, the HELLO fields, the three `handleInboundBinary` functions,
  `FlashBackgroundService.onTimeout`, the `pendingGroupMedia` park, `store = null` on desktop, `FlashSchemaSteps`, the
  `:core:engine` dependency styles, `jitpack.yml`.
- **Membership facts read in code on 2026-10-04 [code]:**
  - `GroupSignatureRules.checkCharter`, with its reasons `owner-not-paired`, `owner-key-pin` and `id-derivation`;
  - `SignedGroups.onBundle` (`no-own-cert`, `sender-not-member`, `installVouches` before storing), `isVouchedMember`,
    `hasVouchedRosterKey`;
  - the four predicates in `RealFlashChatRepository` and the paired-only file paths;
  - `PinSource` (`PAIRED`, `VOUCHED`, `TOFU`) and `GroupVouching`;
  - `SecureBinaryFrameCodec`'s plain-frame fallback;
  - PTT recipients are connected **paired** peers (`PttSessionEngine`, `Flash.kt` `snapshotMembers`);
  - `CallCoordinator`'s separate group gate lambda;
  - `DestinationPolicy.evaluateOffer` has no production caller (a grep found none).
- **Not checked yet (tasks say where):**
  - whether the `GroupMedia` decoder ignores unknown fields (SW-0 task 4);
  - the largest binary frame the session accepts (SW-3);
  - how the desktop group-send loop differs from Android's (SW-1 task 1);
  - whether `connectedDevice` together with `dataSync` changes the timeout behaviour on the app's service: the platform document
    says the limit applies to the `dataSync` type, and `FlashBackgroundService` already handles `onTimeout`.
- **Sources:**
  - [Android: foreground service timeouts](https://developer.android.com/develop/background-work/services/fg-service-timeout) —
    `dataSync` and `mediaProcessing`, 6 hours per 24 hours, `onTimeout(int, int)`, `stopSelf()` within seconds, the timer resets
    when the user brings the app to the foreground, and the test commands
    `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <package>` and
    `adb shell device_config put activity_manager data_sync_fgs_timeout_duration <ms>`; checked 2026-10-04. The page does not
    say how one service declared with several types (`connectedDevice|dataSync`) is treated; `SWM-17` observes it.
  - BEP 16 super-seeding (reported, through design 4.4).
  - Ketch (design 11.2), reference only.

## 13. Mapping to earlier plans
| Earlier item | Where it lands now |
|---|---|
| Design G3 (manifest, held index, dedup) | SW-3 (manifest), SW-6 (records), E-49 (dedup). The full held-content index for 1:1 stays with FA-4/FA-5. |
| Design G4 (sans-IO engine and simulator) | SW-4, SW-5 |
| Design G5 (wire integration, gate, persistence, ECO, hotspot) | SW-2, SW-6, SW-7, SW-8 |
| Design G6 (UI) | SW-11 |
| Design M0 / `SWM-01..05` | SW-12 tuning (no longer a gate) |
| ~~Design G1 / G2 (group secret, settings)~~ | ~~Independent track, unchanged~~ **Superseded 2026-10-04:** G1 → GM-1 to GM-8 (with O-13 and rotation without sealing); G2 → GM-9 and GM-10. |
| Design G0 (ADRs) | SW-0 (six ADRs: three for the swarm, three for membership, settings and the gate) |
| AGENTS 29 gap "vouched members get no files" | GM-5 |
| AGENTS 29 gap "a call leg already established is not re-checked after a removal" | GM-5 task 6 |
| AGENTS 29 gap "a non-owner in a v2 group still sees Add members and it fails silently" | GM-9 task 3 |
| FA-4 (persist transfer identity) | Independent. Shares the schema-version rule (ripple 17). |
| FA-5 (multi-file / folder) | Independent. A later multi-entry manifest can reuse SW-3. |
| FO-04 (group attachment fan-out) | Replaced by this plan for v2 groups |
| FO-06 (hotspot isolation) | E-08; no speed claim |
