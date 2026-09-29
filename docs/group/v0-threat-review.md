# ADR-044 phase V0 — threat review of group membership and vouched trust

**Status: COMPLETE 2026-09-29.** No code changed by this review. Its output is (a) the findings F-1 to F-9 about the code as it is
today, (b) the design V1 and V2 must follow, and (c) the phase plan in section 8. ADR-044 carries a short summary; this is the
detail. Method: read the receiving and sending paths in `RealFlashChatRepository`, `GroupWireFrame`/`GroupFrameCodec`,
`TofuPinVerifier`, `TofuX509TrustManager`, `WsFlashNetwork.inboundIdentityFailure`, `CallCoordinator`, `FlashTrustStore` and the
crypto interface, then walked the actors below against them. Everything marked *verified* was read in the code on 2026-09-29;
nothing here was exercised on a device.

## 1. What is trusted today (baseline)

| Layer | Mechanism | Where |
|---|---|---|
| Who is on the other end of a socket | TLS with a self-signed identity certificate. The SHA-256 of its SPKI is pinned per device id. **First sight of an id records the pin (TOFU)**, a later different key is refused. | `TofuPinVerifier`, `TofuX509TrustManager`, `WsFlashNetwork.inboundIdentityFailure` |
| Which id a socket claims | `FLASH_WS_HELLO deviceId=…` is bound to the pinned key before the session is registered. | `inboundIdentityFailure`, `connectManual` (ADR-040) |
| Whether that peer is "trusted" | A separate flag, set only when pairing v2 (commit-then-reveal, 6-digit code covering both TLS identity keys) completes. | `FlashTrustStore.isTrusted`, ADR-042 |
| Group frame authenticity | `frame.from == the socket's peer id` and `isTrustedPeer(peer)` and, for chat/sync, the peer is an **active member in the local roster**. No frame is signed. | `onInboundGroupWireFrame` |
| Group call authenticity | `CallCoordinator.isTrustedPeer(peerId)`, a boolean with no group context. | `CallCoordinator` |

Two facts drive everything below. First, **membership is a set of unsigned rows**, so any frame that is accepted can be forged by
anyone the receiver treats as a member or, for some frames, as merely paired. Second, **a pin exists before trust does**: an
unpaired device that connects first as id X gets its key recorded as X's pin.

## 2. Findings in the code as it is today (before any of this work)

Severity is for the current model, where every member is paired with every other member and there are at most 6.

| ID | Finding | Attacker needs | Effect | Severity now |
|---|---|---|---|---|
| **F-1** | **`Create` overwrites an existing group.** The `Create` branch never checks that the group id is unknown. `conversationDao.upsert` is `@Upsert`, so title, `groupCreatedBy`, `groupCreatedAt` and sort order are replaced, and `applyMembership` takes the attacker's rows if their version is larger. *Verified.* | A device I paired with, who knows the group id (every member and every ex-member does), me in `memberIds`, all listed members paired with me. | Rename or re-own someone else's group on my device; inject rows with the largest version (see F-3). | Medium |
| **F-2** | **`State` is accepted from any paired peer, not only members.** The only checks are: I am in the roster, the sender is in the roster it claims itself, ≤ 6 entries. There is no check against my existing record. The "ids unique" check is a no-op (`roster.map { it.deviceId }.size != roster.size` compares a list's size with itself), so a roster with duplicate ids is accepted and the entries are applied in order. *Verified.* | A paired peer that knows a group id. | Re-insert a member who left, evict members (inactive rows with a huge version), replace the name. An ex-member can rejoin themselves. | High |
| **F-3** | **Membership versions are wall-clock milliseconds chosen by the sender and compared with `>`.** A row carrying a very large version can never be beaten by a legitimate operation. *Verified* (`membershipUpdateWins`). | Any accepted `Create`, `Add` or `State`. | Permanent roster poisoning: a member who cannot be removed or re-added, or a tombstone nobody can undo. | High once F-1 or F-2 is used |
| **F-4** | **`handleSyncPush` ingests any push.** It does not check that this device asked for that round (`sendGroupSyncRequests` records no outgoing `syncId`), nor that the pusher is the peer asked, nor that the push is for the requested group. *Verified.* **Correction 2026-09-29 (found while writing V1a):** the first version of this row said a member could forge messages "from" another member. On the real wire that is not possible: `GroupFrameCodec` does not encode the pushed message's `from` and decodes it as the pusher's id, so `senderId` is always the pusher. What an active member *can* do is push unsolicited messages at any time with any `senderName`, `text` and `sentAt` (see F-6), and flood. | Any active member. | Plant fabricated history under their own id with a chosen display name, unasked, at any time. | Medium, and it grows with the group |
| **F-5** | **`State` carries only active members** (`buildStateFrame` uses `activeMembers`). Leave and removal tombstones therefore never travel by reconcile, only by the one `Leave` frame sent to members online at that moment. *Verified.* | Nothing: this is a convergence gap. | A member offline during a leave keeps treating the leaver as a member (accepts their frames, sends them messages) until they happen to receive a direct frame. There is no "Remove" operation at all today. | Medium |
| **F-6** | **Display names come from the sender.** `Message.senderName`, `RosterEntry.displayName` and the group name are used as received. *Verified.* | Any active member (or any paired peer for `State`). | A member can label their messages as someone else's name; a `State` can rename every member. | Low, cosmetic but social-engineering-relevant |
| **F-7** | **`Add` is allowed to any active member** (ADR-030), with the only guard that every added id is paired with the receiver. *Verified.* | An active member. | Fine while pairing is mutual; it is the rule V2 must replace. | Design, not a bug |
| **F-8** | **Unpaired connected peers already reach the 1:1 paths.** ADR-030 deliberately did not trust-gate 1:1 text, and the pin is recorded for any id that connects. | Any LAN device that completes TLS. | Out of scope here, but it means a vouched-only member gets no *new* 1:1 capability from V2, and the 1:1 exposure exists whether or not vouching is built. Logged as adjacent debt, not part of ADR-044. | Adjacent |
| **F-9** | **A relayed message loses its author on the wire (found 2026-09-29 while verifying F-4).** A holder answers a catch-up request with *every* message in the group after the requester's cursor (`historyAfter` has no sender filter), including other members' and the requester's own. `GroupFrameCodec` encodes `name` but not the message's `from`, and decodes `from` as the pusher. So on the receiver every relayed message gets `senderId = pusher`. Effects: relayed messages of a third member show as the pusher's for alignment/ownership (`isMine`), and `DeleteForEveryone` checks `senderId == frame.from`, so the pusher can delete a message it merely relayed and the real author cannot. *Verified in code; not reproduced on a device.* | Nothing: an existing defect. | Wrong attribution of synced history; delete-for-everyone authority follows the relayer. | Medium, correctness and integrity |

F-1, F-2, F-4 and F-5 are exploitable today by a paired peer and are cheap to fix without signatures. They are scheduled first
(section 8, V1a), because building vouching on top of them would multiply their reach from 6 members to 20. **V1a was built on
2026-09-29**, see section 8. F-9 needs a wire addition and is part of V1.

## 3. Actors

| Actor | Holds | Can do today | Should be able to do (V2 target) |
|---|---|---|---|
| **A. Owner** | The charter key, pairing with every member | Everything a member can | Add, remove, rename; nothing else. Cannot write as another member. |
| **B. Member (paired with me)** | A pin and the trusted flag on my device | See group traffic, send, add (F-7), abuse F-1 to F-6 | Send as themselves. Nothing more. |
| **C. Member vouched only** | A vouched pin scoped to one group | (not built) | Group frames and group-call legs of that group only. Never 1:1 chat, files or 1:1 calls (ADR-044 #4). |
| **D. Ex-member / left / removed** | Old certificates, history they received, the group id | Re-enter through F-2 | Nothing. Frames dropped; vouched pin revoked. History already received stays with them (no E2E rekey, section 7). |
| **E. Paired peer, never a member** | Pin + trusted flag | F-1 and F-2 if they learn a group id | Nothing group-related. |
| **F. Unpaired network attacker** | LAN reach, can connect as any id | Pre-pin a key under any id (TOFU) | Cannot become anyone's vouched member, cannot block a real member by pre-pinning (section 6). |

## 4. Design: signed membership (V1)

### 4.1 Roots and objects

- **The owner is the trust root of a group.** A group has one owner, fixed at creation, identified by device id and identity public
  key. Members trust the group because they paired with the owner. Trust is one hop deep, never a chain (ADR-044 #3, confirmed).
- **Keys.** The identity key is ECDSA P-256, `SHA256withECDSA` (`FlashCrypto.sign`, `PlatformCrypto` verify), the same key TLS
  presents. A signature is verified against a **public key carried in the object** (the SPKI bytes), after checking that
  `SHA-256(spki)` equals the fingerprint pinned for that device id. The trust store keeps only fingerprints, so a bare
  fingerprint in a vouch would not let a relay-only verifier check a signature; the SPKI must travel.
- **`GroupCharter`** (owner-signed, immutable): `groupId`, `name`, `ownerId`, `ownerSpki`, `createdAt`, `proto=2`. Fixes the owner.
  A charter is valid only if `ownerId`'s pinned fingerprint on this device is the owner's `SHA-256(ownerSpki)` **and the owner
  is paired with me** (or I am the owner).
- **`MemberCert`** (one per subject, self-contained): `groupId`, `subjectId`, `subjectSpki`, `label` (owner's name for them),
  `role` (`owner` | `member`), `seq`, `opId`, `active`, `issuerId`, `sig`. Issued by the owner for add and remove, and by the
  subject for their own leave (`active=false`, `issuerId=subjectId`). Canonical signed bytes: a domain tag
  `"flash-gcert-v1"`, then each field length-prefixed, in a fixed order (exact layout is a V1 deliverable in `docs/protocol.md`).
- **Why one cert per member and not one signature per operation.** ADR-044 sketched signing whole `Add` operations. A multi-member
  `Add` cannot be split into rows for relay without the whole operation attached, and rows are how the roster is stored and merged
  (`applyMembership` is per row). Per-subject certificates are verifiable in isolation, merge per row, and relay without extra
  context.
- **`seq`, not wall-clock.** A per-subject counter replaces `membershipVersion` for v2 groups: the owner issues `seq = last + 1`;
  a member's own leave signs `seq = last known + 1`. Order is `(seq, opId)`, strictly greater wins, exactly the existing rule, so
  `membershipUpdateWins` is reused. This closes F-3: an unsigned or non-owner value cannot enter, and the only signers who can
  write a large `seq` are the owner (the trust root) and a member poisoning **their own** record.
- **Signed group messages.** `FLASH_GMSG` (and the message inside `SyncPush`) gains `sig` over
  `"flash-gmsg-v1"` ‖ `groupId` ‖ `msgId` ‖ `from` ‖ `sentAt` ‖ `replyToId` ‖ `replyPreview` ‖ `text`, verified with the author's
  roster key. This is what closes F-4 for v2 groups: a relayed message is verifiable without trusting the relayer. Cost: one
  sign per sent message and one verify per received one; the StrongBox sign latency on the target phones is a measurement owed
  (`GRP-`/`MEAS` backlog entry to add with V1).
- **Names.** A v2 group shows the roster's `label` (owner-signed) for a member, not the frame's `senderName`. This closes F-6 for
  v2 groups. The group name lives in the charter.

### 4.2 Bundles, tombstones and convergence

- `State` is replaced, for v2 groups, by a **bundle**: the charter plus **every** cert including tombstones (F-5). Receivers verify
  each cert independently and merge per subject by `(seq, opId)`. There is no unsigned field in a bundle.
- A bundle is sent when a session comes up (as `reconcileGroupMembership` does today) and by the owner directly to a member it
  just added. Size: about 350 bytes per cert as text, so 20 members is roughly 7 KB, far below the 4 MB WebSocket message limit.
- **Verification cost cap.** A bundle is at most `MAX_MEMBERS` active plus a bounded tombstone count (64 rows per group); larger is
  dropped. Verified certs are remembered by hash, and at most one bundle per group per peer is processed per 30 s, so a hostile
  peer cannot make a phone verify 40 signatures in a loop.

### 4.3 Rules the receiver applies (v2 group)

1. `from == the socket's peer id` and `isGroupTrusted(groupId, peer)` (section 5) and the peer is an active member per **verified**
   certs. Same shape as today, with a stronger predicate.
2. Membership frames from anyone but the owner are dropped, except a member's own leave.
3. **`Create`, charter or bundle for a group id already known locally is ignored.** Fixes F-1 for legacy and v2.
4. A charter for an id that exists locally **only as a legacy (unsigned) record replaces it** (a legacy record can be fabricated by
   any paired peer; a charter needs the owner's key). This is what stops a paired member from squatting a group id on a victim
   before the real charter arrives.
5. **Downgrade.** Once a group id is recorded as v2 (`proto=2`), every unsigned membership frame for it is dropped and logged as
   a security event. A group never returns to legacy.
6. `SyncPush` is accepted only for a `syncId` this device requested, from the peer it asked, for the group it asked about
   (legacy and v2; built in V1a). For a v2 group the pushed message must also carry its author explicitly (F-9: today the wire has
   no author field) and verify against that author's roster key.

### 4.4 Old clients

Text frames ignore unknown `action` values (protocol invariant 1), so v2 frames use **new action names** and an old client drops
them rather than misreading them. Silent dropping would fork the roster, so:

- HELLO gets an additive field `gv=<n>` (group protocol level). `PROTOCOL_VERSION` must **not** change: a mismatch already fails
  the whole handshake, and that would cut off every old device from every purpose.
- The owner may add a member to a v2 group only if that member's last HELLO advertised `gv >= 2`; otherwise the UI says "Update
  Flash on this device".
- **Legacy groups stay legacy** (at most 6, everyone mutually paired, the ADR-030 rules plus the V1a fixes). They are not
  upgraded in place. A user who wants more than 6 creates a new group. A new group is v2 when every invited device advertises
  `gv >= 2`, legacy otherwise.

## 5. Design: scoped trust and the TLS pin (V2)

### 5.1 The predicate

`isGroupTrusted(groupId, peerId)` is true when the peer is paired **or** a verified v2 cert for `(groupId, peerId)` is active.
It replaces the boolean at the **group gates only**: the group frame handler, and the three group frames in `CallCoordinator`
(`GroupInvite`, `GroupPresence`, `GroupQuery`, which carry a `groupId`). Everything 1:1 keeps `isTrusted`:

| Path | Predicate after V2 |
|---|---|
| Group text, receipts, sync, media announcements | `isGroupTrusted(groupId, peer)` and active member |
| Group call legs, invites, presence, query | `isGroupTrusted(frame.groupId, peer)` and active member |
| 1:1 call invite, 1:1 file transfer, 1:1 chat as trusted | `isTrusted(peer)`, unchanged |
| Push-to-talk (ADR-032, "all paired") | `isTrusted(peer)`, unchanged: vouched trust never grants PTT |
| Group attachments (N−1 uploads, FO-04) | Sent only to **paired** members; a vouched-only member is skipped with a visible note, because a vouched member never receives files |
| Presence sharing and endpoint tips (PC4, ADR-046) | Eligibility today is "paired, or a fellow member of one of my groups" (`PresenceState`). Keep it, but feed it from the **verified** v2 roster, not raw rows. Tips are dialed only for a *pinned* subject, and a vouched pin counts as pinned: that is what lets a 20-member mesh form, so it is wanted, not excluded |

### 5.2 How a vouched key becomes a pin

This is the part ADR-044 said might touch `TofuX509TrustManager`. **It does not have to.** The trust manager only asks
`FlashPinVerifier.isPinned(deviceId, fingerprint)`; the seam is the pin store behind it.

- The trust store gains a **pin source** per device id: `PAIRED` (from pairing), `VOUCHED(groupIds)` (from verified certs),
  `TOFU` (recorded on first sight, unverified).
- Lookup precedence: `PAIRED` > `VOUCHED` > `TOFU`.
- **A vouch installs its pin before the peer connects.** So the real X, connecting later, matches it, and an attacker claiming X
  with another key is refused with the existing `PeerKeyChanged` path.
- **Vouch against an existing `TOFU` pin (unpaired id):** the vouch replaces it, live sessions on the old key are closed, and a
  security event is logged. A TOFU pin carries no human verification; an owner-signed vouch does. This is what defeats an
  attacker who connected first as X (actor F).
- **Vouch against a `PAIRED` pin that differs:** the pairing wins, the vouch is not applied, the member is shown as "key differs
  from your pairing" and is not trusted in the group until the user resolves it (re-verify). Fail closed.
- **Removal:** when the last group vouching a key is gone (removed, left, group deleted), the `VOUCHED` pin is deleted. A
  `PAIRED` pin is never touched by group operations.
- **Verify** on a vouched member runs ordinary pairing v2. If the key TLS pinned for that connection differs from the vouched
  key, the flow stops with a key-mismatch warning instead of pairing.
- Trust-store changes need explicit persistence migrations on both hosts (invariant 5 of the group plan), and both stores
  (`AndroidPreferencesTrustStore`, `DesktopTrustStore`) implement the new methods; default no-ops would silently disable vouching
  on a host, which the interface must not allow.

### 5.3 Dialing members

The mode layer and presence already count "fellow members of this device's groups" as contacts (`EcoLinkSelector.contacts`,
`PresenceState` eligibility), so once a vouched member has a pin and a verified roster row they are shown and dialed through
the same paths. V0 did **not** trace `ConnectionPlanner`'s candidate rule for a discovered peer that is unpaired but a group
member; V2 starts by reading `ConnectionPlanner`/`AutoConnector` against exactly that case and the ECO "unpaired peers only while
Nearby is open" rule, because a mesh of 20 depends on it. Raising `SessionHardeningPolicy.maxConcurrentSessions` (8 today) and the
per-mode session cap is the separate prerequisite in section 8.

## 6. Walk-through of the ADR-044 V0 list

| Scenario | Outcome under the design |
|---|---|
| **Malicious owner** | Can add any key (including their own second device) under any label, and can remove anyone. Cannot write as another member (messages are signed by the author), cannot make anyone paired, cannot reach 1:1 paths through a vouch. **Accepted:** the owner is the root; members already chose to trust them. Mitigations: "Added by <owner>" label; one-tap Verify; a later pairing that disagrees with the vouch raises a key-mismatch warning, so a lying owner is detectable per member. |
| **Compromised member device** | Holds a valid key and roster entry: can read and send as themselves, spam, and disclose. Cannot add or remove (owner-only), cannot forge others' messages or names (signed), cannot inject or evict roster rows (certs), cannot use the vouch for 1:1. Residual: everything sent to them, and rate abuse. |
| **Replayed or forged `Add`** | Forged: needs the owner's signature, refused. Replayed old cert: loses to any newer cert for that subject the receiver has seen. **Residual:** a receiver that has never seen the newer tombstone (fresh joiner served by a hostile relay) can be shown an outdated active cert for someone the owner really once added. Bounded by: the owner's own bundle at join is authoritative, every session-up exchanges full cert sets including tombstones, and honest peers hold the newest. An owner-signed "roster head" is a possible later tightening; not built. |
| **Key change of a vouched member** | The pin mismatches, TLS refuses, `PeerKeyChanged` shows. The owner (after pairing with the reinstalled device) issues a new cert with the next `seq` and the new SPKI; receivers apply it (unpaired subject) or warn (paired subject with a different pin). |
| **Removed member reconnecting** | The owner's tombstone makes their frames dropped and their vouched pin deleted. Members who have not yet received it accept them until they do (offline window, closed by tombstones in bundles, F-5). They keep history they already received: no per-sender keys yet, so removal cannot revoke reading of past messages. They can also connect over TLS, as any unpaired device can. |
| **Downgrade to unsigned frames** | Rules 3 to 5 in 4.3: known ids are never re-created, charters beat legacy records, v2 groups drop unsigned membership frames. |
| **TLS pinning path for vouched keys** | Section 5.2. No trust-manager change; the pin store gets sources and precedence. |
| **Clock skew / version poisoning** | Removed by `seq` counters. |
| **Resource abuse** | Bundle size and verification caps, 30 s per-group-per-peer bundle limit, member cap 20. |

## 7. Accepted limits (must appear in the UI copy or docs)

- The owner is a single point of trust and of failure. If the owner's device is lost or reinstalled, its key changes and the group
  can no longer be changed (add, remove, rename); messaging among existing members continues. Ownership transfer is not built.
- Vouched trust is weaker than pairing by design and says so ("Added by <owner>", Verify).
- No forward secrecy or per-sender group keys (`keyEpoch = 0`): a removed member keeps what they already received.
- The 1:1 exposure of unpaired connected peers (F-8) is unchanged by this work.
- The "no admins" rule of the 2026-09-07 group decisions is superseded for v2 groups by ADR-044 #3 (owner-only vouching), already
  accepted by the owner.

## 8. Phases (replaces the V1/V2 sketch in ADR-044; V3 stays parked, FO-05)

| Phase | Scope | Exit |
|---|---|---|
| **V1a Hardening of today's groups** (no wire change, no signatures). **BUILT 2026-09-29** | F-1: ignore `Create` for a known group id. F-2: with a local record, accept `State` only from an active member (no record = bootstrap, as now), reject a roster that repeats an id, keep the locally recorded owner and only ever mark the owner "owner". F-4: `SyncPush` only for a `syncId` this device requested, from the peer asked, for the group asked about, within 10 minutes (`OutgoingSyncRequest`); the earlier idea of also checking `message.from` was dropped because the wire carries no author (F-9). F-5: `State` also carries the newest leave tombstones, but only as many as fit under `MAX_MEMBERS` total rows, because the codec of every shipped client rejects a longer roster. | 8 attack tests failed against the old code and pass against the fix; the honest-flow tests and the multi-device late-join tests stay green. **Limits kept on purpose:** a member this device has not learned about yet cannot teach it the roster (the owner's next session-up `State` does); a full group of six carries no tombstones; F-3, F-6 and F-9 stay until V1. |
| **V1 Signed membership and messages** (v2 groups) | Charter, `MemberCert`, bundle, message `sig`, `seq` merge, tombstones, downgrade rules, HELLO `gv`, `GroupPolicy` limits per protocol level, `docs/protocol.md` in the same commit, golden vectors for the canonical bytes. Persistence migration (cert columns). An explicit, signed `author` on a pushed message (F-9), so relayed history keeps its author and delete-for-everyone authority follows the author. | Forgery, replay, downgrade, poisoning and old-client tests; `docs/protocol.md` updated; StrongBox sign latency measurement logged as owed. |
| **V2 Vouched trust** | `isGroupTrusted`, pin sources and precedence in both trust stores, planner dialing of vouched members, call-frame gates, UI labels and Verify, attachment and tip exclusions, `MAX_MEMBERS = 20` for v2. **Requires the per-mode session cap first** (`SessionHardeningPolicy` allows 8; 20 members need up to 19). | Owner device check with at least 4 devices, 2 of which have never paired (backlog entry added with V2). |
| **V3 Scale measurement** | Parked (ADR-056, FO-05). | — |

## 9. Owner decisions needed

None to proceed with V1a (bug fixes with tests). Two are worth confirming before V1 is built, both with a recommended default:

1. **Sign group messages, not only membership** (section 4.1). Recommended: yes. Without it F-4 stays exploitable by any member at
   20 members, including members the reader never paired with. If declined, v2 groups must stop relaying history through
   `SyncPush`, which loses offline catch-up.
2. **Legacy groups are not upgraded in place** (section 4.4). Recommended: yes. In-place upgrade would need the owner to sign for
   a group whose roster was never authenticated.
