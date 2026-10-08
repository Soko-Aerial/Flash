# Future Optimization — parked work

**Created 2026-09-29 (owner decision, ADR-056).** This file is the single list of work the owner has *deliberately
postponed*. Nothing here is forgotten and nothing here is cancelled; it is simply not next.

**Rules**

1. An item is added here in the same session the owner postpones it, with the reason and what would bring it back.
2. Never delete an item (AGENTS.md §27). When work starts, set its Status to `STARTED` and link the plan/ADR that took it;
   when it ships, `DONE`; when it stops making sense, `OBSOLETE` with the reason.
3. A parked item is **not** a hole in the current work. Each entry states what the rest of the project assumes in its
   absence, so nobody builds on a guess.
4. Device tests/measurements owed for parked work stay in `docs/testing/TEST-BACKLOG.md` (marked `POSTPONED`), so the
   test list stays complete.

| ID | Item | Parked since | Status |
|---|---|---|---|
| FO-01 | QR first contact (DR4) | 2026-09-29 | POSTPONED |
| FO-02 | Bluetooth (BLE) discovery (DR6) | 2026-09-29 | POSTPONED |
| FO-03 | Wi-Fi Direct transport (DR7) | 2026-09-29 | POSTPONED |
| FO-04 | Group file sending: fan-out cost and a revamp of file sending | 2026-09-29 | POSTPONED |
| FO-05 | Scale and battery measurement, PC6 / MEAS-02 (and the choice of 32) | 2026-09-29 | POSTPONED |
| FO-06 | Hotspot client isolation (joined phones cannot reach each other) | 2026-10-02 | IDEA (not prioritised) |
| FO-07 | Group invite link lifetime: expiry or single use | 2026-10-06 | OPEN DECISION (owner) |
| FO-08 | Joining by invite: pending-join row, saved address hints, approval timeout | 2026-10-06 | OPEN DECISION (owner) |
| FO-09 | Organisation realms (private protocol identity, enrolment, revocation) | 2026-10-07 | PLANNED, design only: `docs/ENTERPRISE-HYBRID-PLAN.md` (merged with the hybrid server draft, stages E0-E5) |
| FO-10 | New-member history: ask before syncing, admin rule | 2026-10-07 | DESIGNED 2026-10-08, owner decisions taken (30 days default, no history = no files, returning member 7 days, any admin changes the ceiling): `docs/group/GROUP-SYNC-REVAMP-PLAN.md`. Not built |
| FO-11 | Linux desktop as a committed target | 2026-10-07 | OPEN DECISION (owner, D-L1): `docs/LINUX-PORT-PLAN.md`; only Phase L0 (unbreak build/CI, audit `TASK-CORE-SEC-1`) is justified before it is answered |
| FO-12 | Bluetooth direct transport and VHF/UHF radio (KISS TNC) bridge | 2026-10-07 | POSTPONED until the owner confirms it and hardware spike `BT-00` passes: `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md`. Overlaps FO-02 (BLE discovery) and FO-03 (ADR-056 postponements) |

---

## FO-01 — QR first contact (DR4)

**What.** A device shows a QR code carrying `flash://<ip>:<port>/<deviceId>/<full identity fingerprint>` plus a one-time
pairing nonce. Scanning it dials that address and pairs with the fingerprint pinned out of band, which is stronger than
the 6-digit code and needs no discovery at all.

**Why parked.** Owner priority (2026-09-29): finish DR2, DR3, DR5 first. The QR path also brings new dependencies
(CameraX and a decoder on Android, QR encoding on desktop) and a new UI component that must be researched first (§34).

**What the project assumes meanwhile.** First contact needs either discovery (mDNS, beacon, and once built the broadcast
beacon and the subnet sweep) or **Connect by IP**. DR5's "No devices found" hint therefore offers *Scan network* and
*Connect by IP* only; the *Show QR* action in the plan's wording is left out until this ships.

**Bring it back when.** Field reports show people who cannot pair because no discovery source reaches the peer, or the
owner wants camera-based pairing for its own sake.

**Read first.** `docs/network/DISCOVERY-RESILIENCE-PLAN.md` §3.3 D and §4 DR4; ADR-047 (needs an amendment for the
dependencies before any is added); ADR-042 (pairing v2: the QR supplies the pin, the commit and reveal still run);
ADR-043 (third-party notices). Owner decision D3 recommends ZXing + CameraX (Apache-2.0, no Play services), not ML Kit.
A UI component doc under `docs/ui/` must reach DESIGNED before implementation.

---

## FO-02 — Bluetooth (BLE) discovery (DR6)

**What.** Advertise a short rotating id and the IP:port over BLE; a scanner that recognises a paired peer hands the
address to the connection planner as a dial hint.

**Why parked.** It was always conditional on DR0's failure matrix (MEAS-07), which is itself an owed device measurement,
and it costs a steady scan/advertise drain plus new permissions. Owner priority (2026-09-29).

**What the project assumes meanwhile.** A network that passes unicast but blocks both multicast and broadcast is covered by
DR3 (the subnet sweep, manual and automatic), not by a radio. A device pair with no shared IP network at all is not
discovered (that is FO-03).

**Bring it back when.** MEAS-07 or field reports find networks where the sweep is unacceptable (larger than /24, client
isolation that still allows unicast to known addresses) and multicast and broadcast both fail.

**Read first.** Plan §3.3 G. Android only (JVM BLE would need WinRT through JNA). `BLUETOOTH_SCAN`/`BLUETOOTH_ADVERTISE`
on API 31+, location below 31: check current Android docs (AGENTS.md §13). Needs its own ADR. Rotating ids tie in with
audit S9. `CompositeDiscovery.PRIORITY_ORDER` already reserves the `BLE` rank.

---

## FO-03 — Wi-Fi Direct transport (DR7)

**What.** The project's original second path (AGENTS.md §2, §15): `WifiP2pManager` discovery and group formation, then IP
connectivity handed to the same `ConnectionManager` and transfer engine. Wi-Fi Aware only after that.

**Why parked.** Owner priority (2026-09-29). It is a transport, not a discovery source, so it needs its own plan
(`docs/network/WIFI-DIRECT-PLAN.md`, **not yet written**) and physical-device testing across group-owner behaviour.

**What the project assumes meanwhile.** Both peers must share an IP network (Wi-Fi, hotspot or Ethernet). `WifiP2pManager`
has no references in the code (verified 2026-09-24). Owner decision D5 ("does Wi-Fi Direct come right after DR?") is
answered: **no, it is parked**.

**Bring it back when.** The owner wants transfers between phones with no shared network as a headline feature.

**Read first.** AGENTS.md §15; plan §3.3 H; ADR-054 (WebRTC on Android already sees local-only links such as a hotspot
and a Wi-Fi Direct group, so calls should follow once the link exists). Do not assume the same device becomes group owner
or the same IP behaviour across devices.

---

## FO-04 — Group file sending: fan-out cost and a revamp of file sending

**What.** Today a group attachment is sent by the sender to each member separately: `sendGroupAttachment` creates one
`GroupDeliveryEntity` per recipient, and each recipient re-requests the bytes sender-direct (no chunk relay; see
`docs/group/phase-3-hardening-and-scale.md` §3). At N members the sender uploads the file N−1 times. The owner also wants
the **sending of files itself revamped** later, not only its group case.

**Why parked.** Owner decision (2026-09-29): the trust model and the session cap come first; the fan-out cost and the
send-path revamp are a later optimization.

**What the project assumes meanwhile.** Group attachments keep the per-recipient model. That is fine at 6 and gets
expensive at 20 (a 100 MB file is about 1.9 GB of upload from one phone). **Consequence for the trust-model work:**
ADR-044 already says to revisit "if attachments in groups land" because of this; that revisit is what this item is. Raising
groups to 20 (ADR-044 V2) does not wait for it, but the cost is known and accepted.

**Ideas on the table (none chosen).**
- A relay design: members that already have the file serve the chunks (swarm-style), with the sender as the seed.
- A designated forwarder (`phase-3-hardening-and-scale.md` §5 sketches this for voice).
- Per-member resume state on the sender, capped concurrent uploads, and queuing by tier and battery.
- A broader revamp of the send path: resume, chunking and per-peer scheduling as one design, not a group patch.

**Owner's swarm idea (2026-10-02, recorded, not chosen, no code).** The owner described the relay design above more
concretely: the sender hands the file to **only two members**; those two pass pieces on to others, and so on, like a torrent.
Requirements as stated:
- A member can **receive different pieces from several members at once**.
- If a source drops, **another member holding that piece takes its place**; the transfer does not restart.
- The **sender may go offline** after sending only part of the file (for example half to one member): that member shares what it
  has with the next member, who shares with the next.
- Shown as a diagram on slide 9 of `Flash Progress Update (12 slides).pptx` (a presentation, outside the repo).

What already exists and would be reused: numbered chunks, SHA-256 verified per chunk before it is written, resume from the
persisted done-set (`TransferEntity`), multi-stream dispatch, groups of up to 20 with vouched members, session ceiling 24.
What would be new: (1) a per-member map of which chunks it holds; (2) a **sender-signed list of chunk hashes** so a relay cannot
substitute a chunk (a member that never talked to the sender has no other way to trust a piece; vouched members exist only since
ADR-044 V2); (3) choosing which member serves which chunk and re-assigning when a source drops; (4) member-to-member links, which a
group does not guarantee (ERROR-088 / ERROR-095 show a member may not reach every other member); (5) persistence of "pieces I hold
and may serve" for a member that is not the receiver of record.
Honest limit: a file can only complete while **every piece is held by someone who is online**. If half the file left the sender and
the one holder of that half goes offline too, the rest of the group is stuck until a holder returns. Also a trust question: a member
that serves pieces learns the file, so serving needs the same group membership gate as receiving (a removed member must stop serving).
FO-04 stays POSTPONED (ADR-056); nothing here starts it. It needs its own ADR first.

**Research and plan (2026-10-03):** `docs/transfer/TRANSFER-V2-SWARM-AND-MULTIFILE-PLAN.md` (libraries rejected as transport, the swarm's honest limits, the shared manifest + pull-wire foundation, FA-5 and the transport upgrade, phased with measurement gates). Plan only; this item stays POSTPONED until the owner decides.

**Bring it back when.** Groups above 6 exist in practice (after ADR-044 V2), or a user reports slow/failed group file
sends, or the owner opens the send-path revamp. It needs its own ADR before code.

**Read first.** ADR-044 "Revisit when" and Consequences; `RealFlashChatRepository.sendGroupAttachment` (~line 1165) and
`beginGroupAttachment` (~1642); `docs/group/phase-3-hardening-and-scale.md` §3 and §5; AGENTS.md §18 (large files, resume).

---

## FO-05 — Scale and battery measurement: PC6 / MEAS-02, and the choice of 32

**What.** 20 peers (the peer farm plus phones) in each discovery mode: battery per hour, the reconnect storm after a Wi-Fi
blip, delivery latency to a screen-off ECO phone. This is PC6 of `docs/network/PRESENCE-CONNECTIONS-PLAN.md` and ADR-044
phase V3, and TEST-BACKLOG **MEAS-02**. It is the measurement that would replace the plan's *(measure)* estimates and
decide PC7's final tuning, the default mode, and whether groups may exceed 20.

**Why parked.** Owner decision (2026-09-29): measurement is future optimization, so the rest of the presence and
connections plan is unblocked for immediate implementation.

**What the project assumes meanwhile (decided here, not measured).**
- Every number tagged *(measure)* in the plans stays an **estimate** and is documented as one. Nobody may cite it as data.
- The group size target for the trust-model work is **20** (ADR-044 V2). **32 stays parked**: ADR-044 makes it depend on V3,
  which is this measurement. Nothing implements or advertises 32 until FO-05 is done.
- The session cap (`SessionHardeningPolicy`, 24 since ADR-057; it was 8) is designed from the group size and the mode by reasoning, with the cost per
  session unmeasured; the values chosen are labelled estimates and revisited here.
- ECO's "about 1 minute" delivery bound is a design target, not a verified result.
- MEAS-01 (PC0, the baseline) is **not** parked by this decision. It stays in the measure-last list (owner decision P8).

**Bring it back when.** The owner wants to tune battery, raise the group size above 20, or trust ECO's bound. The rig
exists: [`docs/network/PC0-RUNBOOK.md`](network/PC0-RUNBOOK.md) (`:core:engine:peerFarm`, `tools/pc0/phone-baseline.ps1`).
Expect to handle ERROR-074 (Transsion freezes Flash at screen-off) first.

**Read first.** PC plan §4 PC6, PC7 and decision P8; ADR-044 phase V3; ADR-048 ("session cap stays a uniform 8 until PC6");
TEST-BACKLOG §5.


---

## FO-06 — Hotspot client isolation: phones joined to a phone's hotspot cannot reach each other

**What.** Owner report (2026-10-02): a mobile hotspot isolates the phones connected to it, so they cannot discover or connect to
each other. The repository already holds the same symptom: on an Infinix Hot 50 hotspot devices could not discover or connect to
each other (ERROR entry of 2026-09-30, which names the hotspot's client isolation as the likely cause; **not isolated by a
controlled test**). Earlier hotspot work (memory `nsd-hotspot-discovery`) found that a joined phone can open a connection to the
hotspot phone but the hotspot phone cannot open one to a joined phone. Android's hotspot documentation
(source.android.com, "Wi-Fi hotspot (Soft AP)", checked 2026-10-02) does not mention client isolation, so this looks like
phone-maker behaviour; which makers and models do it is **unknown** (MEAS-07 / HOT-01 measure it).

**Why it is not fixed by discovery.** mDNS, the DR2 beacon and the DR3 subnet sweep all need phone-to-phone packets; isolation
drops them. DR3 and Connect by IP cover networks that block multicast but allow unicast, not this.

**Options that need no server (none chosen, nothing built, owner has not prioritised).**
1. **Relay through the hotspot phone.** Every joined phone can reach it, so it forwards frames between joined phones. End-to-end
   encryption between the two phones exists (ECDH, HKDF, AES-GCM in v2.0.0-beta), so the relay sees ciphertext. Needs a routing
   frame in the protocol and an ADR; cheap for chat and files, heavy for calls (the host would carry every media stream; see the
   3-to-4 video limit).
2. **Wi-Fi Aware** (NAN). Official API from Android 8 (API 26), connects phones with no access point; needs hardware support
   (`FEATURE_WIFI_AWARE`, `WifiAwareManager.isAvailable()`) and may not work while a hotspot or Wi-Fi Direct is in use (official
   doc, checked 2026-10-02). `CompositeDiscovery.PRIORITY_ORDER` already reserves `WIFI_AWARE`; no code exists.
3. **Wi-Fi Direct** is FO-03.
4. **App-made local-only hotspot** (`WifiManager.startLocalOnlyHotspot`). The Android doc says it exists so that applications on
   devices connected to the hotspot can communicate with each other; no internet. Whether Transsion applies isolation to it is
   unknown; joining needs the network name and password and, for a client app, a `WifiNetworkSpecifier` request.
5. **Host the hotspot elsewhere** (a router, a laptop, another make of phone). Today's workaround; which hosts isolate is unchecked.

Always useful whichever is chosen: detect "same subnet, unreachable" and tell the user why instead of failing silently.

**Bring it back when.** The owner wants the hotspot case to work for phones that join each other, or HOT-01 shows which phones isolate.

**Read first.** `docs/network/DISCOVERY-RESILIENCE-PLAN.md` (DR0 matrix); ADR-056; ERROR entry of 2026-09-30; ERROR-079
(the hotspot host and WebRTC); `docs/testing/TEST-BACKLOG.md` HOT-01...HOT-03.

---

## FO-07 — Group invite link lifetime: expiry or single use

**What.** An invite link (`flash://g/1/...`) carries the group's secret in the clear, so whoever holds it can start a join request. It has **no expiry, no single-use rule and no binding to a person**. The only way to kill an old link is a group-code change (`changeGroupCode`, or removing a member), which rotates the secret; since 2026-10-06 (ERROR-115, ADR-083) the joiner is then told "This invite was replaced". With the join policy set to OPEN, anyone holding the link joins automatically; with APPROVE (the default) an admin still decides.

**Options (none chosen, nothing built).**
1. **Expiry** (for example 7 days): the link's `issuedAtMs` is already in the payload but is display-only today (`GroupInviteCodec`). Making it binding changes the wire meaning, so it needs an ADR and a golden-vector update; old builds ignore it.
2. **Single use**: the issuer remembers which links it handed out and refuses a second proof for the same link. Needs a link id in the payload and state on the issuer; breaks "share one link with a whole class".
3. **Per-person links**: an admin makes one link per invitee and the issuer binds it to the first device that proves. Heaviest; best for sensitive groups.
4. **Do nothing**: APPROVE is the default and rotation already revokes everything.

**What the project assumes meanwhile.** The link is a bearer credential; the UI should not call it private. Logs redact it (`flash://g/<redacted>`).

**Bring it back when.** The owner wants links that stop working by themselves, or a group is used where an open link is a problem.

**Read first.** ADR-073, ADR-082, ADR-083; `docs/security.md` section 10; `GroupInviteCodec.kt`; `RealFlashChatRepository.inviteFor` / `acceptInvite`.

---

## FO-08 — Joining by invite: pending-join row, saved address hints, approval timeout

**What.** Three loose ends of the join flow found on 2026-10-06 (investigation of what happens when a user takes an invite link):
1. **No persistent "pending join" row.** After Join the joiner sees a message once (toast / snackbar) and the group is not in the chat list until it is approved. Nothing shows "waiting for admin" afterwards (ERROR-114 known limit).
2. **Address hints are not saved.** `pendingInviteHints` is in memory: after a restart the hint dial is not repeated. The invite row is saved and the proof still starts when the inviter connects through discovery, so joining still works; only the dial-by-hint is lost. Hints are the inviter's LAN IPv4 addresses at link time, so they only help on the same network.
3. **`PENDING_APPROVAL` never expires.** A request nobody answers stays for ever on both sides; `cancelPendingInvite` exists but no screen offers it unless the join dialog is reopened.

**Why parked.** The owner has not chosen (2026-10-06); (1) is UI work, (2) is low value, (3) needs a policy (how long, and what the admin sees).

**What the project assumes meanwhile.** The status sentence (`inviteStatusSentence`) is the only place the state is shown.

**Bring it back when.** A joiner reports "I pressed Join and lost track of it", or the owner picks a timeout.

**Read first.** ERROR-113, ERROR-114, ERROR-115; `docs/testing/TEST-BACKLOG.md` section 4zf; `FlashJoinGroupDialog` (`pendingStatusSentence`, `onCancelPendingJoin`).

---

## FO-09 — Organisation realms: a private protocol identity so only an organisation's devices find and talk to each other

**What.** An organisation would set its own "realm" so that only devices holding the same realm discover and talk to each other (discussion 2026-10-07, nothing built, no ADR yet). Today every Flash install shares one network identity: the mDNS service type `_flash-transfer._tcp` (`JmdnsTransport.DEFAULT_SERVICE_TYPE`, a constructor parameter), the UDP multicast group `224.0.0.168`, the TXT record keys (`TxtCodec`) and `PROTOCOL_VERSION = 1` in the HELLO.

**Three meanings of "change the protocol" (the discussion's conclusion).**
1. **Partition discovery only**: a different service type / multicast group / port per organisation. Cheap, stops accidental mixing, but it is obscurity: anyone with the APK can read the constants. Not a security boundary.
2. **Realm secret (recommended shape)**: an organisation secret set at runtime (managed app config, Windows policy, QR or join code). Discovery advertises only a hashed, rotating realm tag. Before pairing or any chat frame both sides prove they hold the secret, reusing the group-secret proof machinery (`GroupSecret`, `GroupProof`, bound to the TLS session). A strict-mode device refuses everyone else, including inbound dials. A `rm1` feature token in the HELLO lets old builds be refused cleanly.
3. **True wire fork** (different framing or magic bytes): rejected. It adds no isolation beyond option 2, breaks interop with future Windows/Linux/Rust clients and doubles maintenance.

**Staging idea.** R0 make the discovery identifiers configurable. R1 handshake gate with the realm proof plus `rm1`. R2 admin-signed device enrolment and revocation (a shared secret cannot revoke one lost phone; a realm admin key signing device certs, like `MemberCert`, can). R3 scale beyond 24 sessions and across subnets (mDNS does not cross VLANs; needs unicast hints or a directory), which conflicts with ADR-056 / FO-05.

**Open questions that change the design.** Is the threat accidental mixing or a hostile device on the LAN (hostile makes R2 mandatory)? How many devices and are they on one subnet? Must one lost device be removable without rotating everything? Are realm members auto-trusted or do they still pair (auto-trust is a trust-model change needing an ADR)? Who administers a realm (admin phone, desktop tool, MDM)? Can a device be in a realm and also pair personally?

**Update 2026-10-07 (same day, later):** the staged design now lives in `docs/ENTERPRISE-HYBRID-PLAN.md` (E0 = R0, E1 = R1, E2 = R2, E3 = R3), merged with the hybrid server draft, which makes the hub an *optional* stage (E4) and adds bridges (E5). Owner context stated that day: the target is the **Ghana military** (closed administered fleet, possibly no internet). The open questions below are carried as decisions D-E1...D-E7 there. Still nothing built and no ADR accepted.

**Why parked.** The owner asked for it to be recorded here (2026-10-07), not built.

**What the project assumes meanwhile.** One public Flash network; trust comes from pairing (ADR-042) and signed groups (ADR-044, ADR-073/074).

**Bring it back when.** An organisation wants private deployment, or the owner decides to target managed fleets.

**Read first.** ADR-042, ADR-044, ADR-056, ADR-057, ADR-073/074; `docs/security.md` section 10; `JmdnsTransport.kt`, `TxtCodec.kt`, `MulticastTransport.kt`, `WsTransferMessages.kt`; `docs/developer-guide/scenarios/scenario-2-custom-extensions.md` (`FlashCrypto`).


## FO-10 — New-member history: ask before syncing, and an admin rule for how much history a new member gets

**Owner idea 2026-10-07:** when a member is added, offer to sync past messages and the files of the last seven days.

**What exists.** A new member automatically receives what the others still hold: text of the last 24 h (up to 100 rows per request) and swarm file offers of the last 7 days (ERROR-120/121). There is no prompt and no admin rule; the new member cannot decline and the group cannot restrict it.

**Idea.** A signed group setting "history for new members: none / 24 h / 7 days" (the GM-9 signed settings carry it) and, on the new member's side, a one-time card "Catch up on recent messages and files?" with the window. Files still need Accept; text could be hidden until chosen. Privacy gain: an admin can keep older content from newcomers.

**Why not now.** Needs a decision on what "none" means for files already fetchable, and a wire-compatible setting. Built only when the owner asks.

**Read first.** ADR-074 (signed group settings), ADR-090, ADR-091, `RealFlashChatRepository.handleSyncRequest`, `GroupPolicy.SYNC_TTL_MS`.
