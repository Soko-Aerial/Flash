# ADR-044 phase V1 — signed membership and signed messages: build plan

**Status: PLAN 2026-09-30, slices S1–S5 below. Owner decisions taken 2026-09-29 (chat): sign group messages YES; legacy groups
are not upgraded in place YES.** The design (roots, objects, rules) is `docs/group/v0-threat-review.md` §4. This file records what
V1 turns that design into: concrete layouts, storage, ports, slice order, and **one change to the review** (D1). Where this file and
the review disagree, this file wins for V1 and the review carries a pointer.

Scope of V1 in one line: a **new** group between updated devices is a **v2 group**: owner-rooted, every membership fact and every
chat message carries a signature, and nothing in it can be forged by a member. Legacy groups are untouched (V1a rules). The size
limit stays **6** until V2 (vouched trust), because V1 still requires every member to be paired with every other member.

## D1. Group id is bound to the owner key (changes review rule 4)

The review's rule 4 said a charter for an id that exists locally only as a legacy record **replaces** it. Reading the receive path
again shows that rule is wrong in one direction and insufficient in the other:

- **Hijack.** Any paired peer who knows the id of my *legacy* group (every member and ex-member does) could send a v2 charter with
  themselves as owner for that id. Under "charter replaces legacy" my genuine legacy group becomes theirs: renamed, re-rostered.
  That reopens F-1 (which V1a closed) under a new name.
- **Squat by charter.** A member `M` of the owner `O`'s new v2 group knows its id. `M` sends me a validly signed charter with `M` as
  owner for the same id before `O`'s bundle arrives. My "known id is never re-created" rule then locks in `M`'s version.

**Fix: the id of a v2 group is derived from the owner's key, so an id names exactly one possible owner.**

```
groupId = "g2-" + lowercase-hex( SHA-256( "flash-gid-v1" ‖ len(ownerSpki) ‖ ownerSpki ‖ len(nonce) ‖ nonce ) )[0 .. 32)
```

`nonce` is 16 random bytes carried in the charter. A charter is valid only if its `groupId` equals this derivation from its own
`ownerSpki` and `nonce`. Consequences:

1. `M` cannot create a charter for `O`'s id: that needs `O`'s key in the preimage, and `M`'s charter would name `M`'s key.
2. **Namespace rule:** an id starting with `g2-` is a v2 id. Every legacy membership frame (`create`, `add`, `leave`, `state`) for a
   `g2-` id is dropped and logged as a security event, and a charter for a non-`g2-` id is dropped. So a legacy record can never
   pre-empt a v2 id, and a v2 charter can never take over a legacy id. There is **no** replace-legacy rule; the review's rule 4 is
   replaced by this namespace rule.
3. Old clients treat the id as an opaque string (`GroupFrameCodec.required` only checks non-blank), and v2 frames are unknown
   actions to them, so nothing breaks; they simply never see a v2 group (D9).

## D2. Objects

| Object | Fields | Signed by | Immutable |
|---|---|---|---|
| **`GroupCharter`** | `groupId`, `name`, `ownerId`, `ownerSpki`, `createdAt`, `nonce` (16 bytes), `proto = 2` | the owner key (`ownerSpki`) | yes: a known id's charter never changes; no rename in V1 |
| **`MemberCert`** | `groupId`, `subjectId`, `subjectSpki`, `label`, `role` (`owner`/`member`), `seq`, `opId`, `active`, `issuerId` | owner (add/remove) **or** the subject (own leave: `active=false`, `issuerId = subjectId`) | superseded per subject by a strictly greater `(seq, opId)` |

Cert validity (all must hold): signature verifies with the issuer's key (owner: `charter.ownerSpki`; subject-leave: the cert's own
`subjectSpki`); `issuerId` is `charter.ownerId`, or equals `subjectId` **and** `active = false`; `role = owner` iff
`subjectId = charter.ownerId`; `label` is 1–80 characters; `seq ≥ 1`. **Key binding (V1):** when `subjectId` is another device the
local pin store must hold a pin for it whose fingerprint equals `SHA-256(subjectSpki)`, and the subject must be paired with me;
otherwise that cert is dropped (V2 relaxes this to "vouched"). For my own cert, `subjectSpki` must equal my key.

## D3. Canonical bytes (what is signed)

Every signed byte string is `tag ‖ field ‖ field …` where **each field is a 4-byte big-endian length followed by its bytes**.
Text is UTF-8; a `long` is 8 bytes big-endian; a boolean is one byte (`0x00`/`0x01`); an absent optional string is the empty string.
The tag is itself the first field. Fixed order, no optional fields, no re-ordering: a parser that disagrees is a different protocol.

| Signed thing | Tag | Fields in order |
|---|---|---|
| charter | `flash-gcharter-v1` | groupId, name, ownerId, ownerSpki, createdAt, nonce, proto |
| member cert | `flash-gcert-v1` | groupId, subjectId, subjectSpki, label, role, seq, opId, active, issuerId |
| group message | `flash-gmsg-v1` | groupId, msgId, from, sentAt, replyToId, replyPreview, text |
| group id | `flash-gid-v1` | ownerSpki, nonce (hash input, not signed) |

Golden vectors (fixed inputs → expected hex) live in `GroupCanonicalTest` and are quoted in `docs/protocol.md`; a change to any of
them is a wire break and needs a new tag (`…-v2`).

## D4. Wire (text frames, additive; `PROTOCOL_VERSION` is **not** bumped)

- **HELLO** gets `gv=<n>` (group protocol level; this build sends `2`; absent = 1). Both networks send and parse it; the peer's
  value is `FlashDevice.groupProtocol` (default 1), exposed by the session's `peer`.
- **Bundle** — `FLASH_GROUP action=bundle`, the one v2 membership frame. It carries the charter (`cName cOwner cOwnerKey cCreated
  cNonce cSig`, base64 for byte fields, `cProto=2`) and `certCount` certificates as `c<i>s` subject, `c<i>k` spki, `c<i>l` label,
  `c<i>r` role, `c<i>q` seq, `c<i>o` opId, `c<i>a` active, `c<i>i` issuer, `c<i>g` sig. `groupId`, `from`, `opId`, `version`
  (unused, `0`) keep the common header, so a legacy decoder that reaches the action sees an unknown action and drops it.
  A bundle may carry any subset of certs: **create** = charter + every cert; **add/remove** = charter + the changed certs (and the
  owner sends the full set to the newly added device); **leave** = charter + the member's own tombstone; **reconcile on session-up**
  = charter + every cert including tombstones (this is what fixes F-5 for v2). Cap: at most `MAX_MEMBERS_V2 + 64` certs, else the
  frame does not decode.
- **`FLASH_GMSG`** gains `sig` (base64 of the signature over the `flash-gmsg-v1` bytes). The frame's `from` is the author.
- **`FLASH_GSYNC op=push`** gains `author` and `sig`. The pushed message's `from` is `author` (fixes F-9 for v2), it must verify
  with the author's roster key, and the pusher is only a relay. Legacy pushes (no `author`) keep today's behaviour.
- Everything else (`FLASH_GRCPT`, `FLASH_GREAD`, `FLASH_GACT delete`, `FLASH_GMEDIA`, sync request/claim/ack) is unchanged: it is
  authenticated by the TLS session (`frame.from == peer`) and, for delete, by `message.senderId == frame.from`, which is now
  correct because relayed messages keep their true author.

## D5. Receiver rules for a v2 group (review §4.3 with D1 applied)

1. Common gate as today: `frame.from == the socket's peer` and the peer is paired.
2. **Bundle for an unknown `g2-` group:** accept iff the charter is valid (D1 derivation, owner signature, owner paired with me and
   pinned to `ownerSpki`) **and my own cert in the bundle is valid, active and for my key**. The sender may be any paired peer: a
   bundle is self-authenticating, so relaying is harmless.
3. **Bundle for a known v2 group:** the charter must be identical to the stored one (else ignore + security log); each cert is
   verified independently and merged per subject with `membershipUpdateWins((seq, opId))`; a cert that fails verification is dropped
   and does not stop the others. The sender must be an active member in the *verified* roster (or the owner).
4. Membership authority: only the owner's certs add, remove or relabel; a member may only tombstone **themselves**.
5. Legacy membership frame for a `g2-` id, or a bundle for a non-`g2-` id: dropped, logged as a security event (D1). A group never
   goes back to legacy (no downgrade).
6. **Message (direct or pushed):** author is an active member in the verified roster, author paired, and `sig` verifies with the
   roster's `subjectKey` for the author. The stored `senderName` is the roster `label`, never the frame's name (fixes F-6 for v2).
   A missing or bad signature drops the message and logs it; no receipt is sent.
7. `SyncPush` also needs the V1a solicitation checks; for v2 the nested message additionally needs `author` + `sig`.
8. **Verification budget.** A cert equal (same `sig`) to the stored row costs nothing. New certs cost a verification each; a
   per-peer budget (`120` verifications per minute) drops further bundles from a peer that exceeds it. Bundle size is capped at
   decode. Messages cost one verification each; a `SyncPush` round is already bounded by `GroupPolicy.DEFAULT_MAX_TOTAL`.

## D6. Sending (v2)

- **Create:** v2 iff a group crypto port is wired **and** every invitee's live session advertised `gv ≥ 2`; otherwise a legacy group
  exactly as today (an invitee with no live session counts as unknown = legacy; recorded limit). The owner generates the nonce,
  derives the id, signs the charter and one cert per member (seq 1), stores them, sends the bundle to each invitee.
- **Add:** owner only (a non-owner gets a clear failure). Each added device must advertise `gv ≥ 2`, else "Update Flash on that
  device". The owner issues `seq = last known + 1`, sends the changed certs to existing members and the full set to newcomers.
- **Leave:** a self-signed tombstone (`seq = last known + 1`). The owner may leave; the group then can no longer change (review §7).
- **Message:** sign at send time, store the signature in the row (`messages.groupSig`) because the row is later relayed by sync. A
  message row without a signature is never relayed in a v2 group (attachment rows are not signed and were never really
  syncable; this is stated in the protocol doc, not a regression).
- **No remove/rename UI in V1.** The receiver handles an owner-issued tombstone (tested with crafted bundles); the sender API for
  it lands with V2's owner tools.

## D7. Ports (the messaging module gets no dependency on `core:security`)

```kotlin
public interface GroupCrypto {
    public val publicKey: ByteArray                 // local identity, X.509 SPKI
    public fun sign(data: ByteArray): ByteArray
    public fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    public fun sha256(data: ByteArray): ByteArray
}
```

`RealFlashChatRepository` gets `groupCrypto: GroupCrypto? = null` (null = every group is created legacy and v2 frames are ignored),
`pinnedFingerprint: (String) -> String?` (the trust store's TOFU/pairing pin for a device, normalised hex) and
`peerGroupProtocol: (String) -> Int` (1 when unknown). The three hosts (`DiscoveryEngineHolder`, `Flash.create`, `DesktopEngine`)
wire them from `FlashCrypto`, `FlashFingerprint` and the live session's `peer.groupProtocol`.

## D8. Storage (schema v6, one step, `STEP_5_6`)

All additions are nullable or defaulted, so existing rows read as legacy and nothing is rewritten:

| Table | Added columns |
|---|---|
| `conversations` | `groupProto INTEGER NOT NULL DEFAULT 1`, `groupOwnerKey TEXT`, `groupNonce TEXT`, `groupCharterSig TEXT` |
| `group_members` | `subjectKey TEXT`, `certSig TEXT`, `issuerId TEXT` |
| `messages` | `groupSig TEXT` |

`membershipVersion` holds `seq` for v2 rows, `displayName` holds the signed `label`, `operationId` holds `opId`, `isActive` holds
`active`. `joinedAt` is local (time first seen) and is not signed. Room exports `schemas/6.json`; `FlashSchemaStepsTest` and
`FlashJvmMigrationsTest` gain the step.

## D9. Old clients and mixed groups

An old client never appears in a v2 group (creation and add both require `gv ≥ 2`). A v2 device receiving a legacy membership frame
for a legacy id behaves exactly as today; for a `g2-` id it drops it (D5.5). A v2 device and an old device still chat 1:1 and can
still share a legacy group.

## Slices (each ends green with its own tests; code and docs are separate commits)

| Slice | Content | Tests |
|---|---|---|
| **S1** | Pure protocol layer in `core:messaging/.../protocol/`: `GroupCrypto`, `GroupCanonical` (writer + the three byte layouts + id derivation), `GroupCharter`, `MemberCert`, `GroupCertRules` (validation given a pin lookup), `VerifyBudget`. No wire, no DB. | canonical golden vectors; sign/verify round trips with `SoftwareFlashCrypto`; every invalid-cert rule; id derivation; budget |
| **S2** | Wire: `GroupWireFrame.Bundle`, `sig` on `Message`, `author`+`sig` on `SyncPush`, codec + caps, HELLO `gv` in both networks and `FlashDevice.groupProtocol`. `docs/protocol.md` in the same commit. | codec round trips, malformed and oversize bundles, old-decoder behaviour, HELLO parse/absent |
| **S3** | Persistence v6: columns, DAO methods, `STEP_5_6`, `schemas/6.json`, migration tests. | `FlashSchemaStepsTest`, `FlashJvmMigrationsTest` |
| **S4** | Repository: v2 create/add/leave, bundle receive/merge, signed send, signed receive/relay, label names, rules D5, host wiring (3 hosts). | forgery, replay, downgrade, poisoning, squat, hijack, old-client, budget, F-9 relay-authorship, mixed legacy/v2 |
| **S5** | Docs: protocol, security, decisions (ADR-044 status, D1 amendment), review pointers, ERROR-082 → RESOLVED, TEST-BACKLOG GT-02 + sign-latency measurement, progress, handoff, memory. | — |

## Known limits carried by V1 (to appear in `docs/security.md`)

- Every v2 member must still be paired with every other member (V2 lifts this).
- Creating a group with an invitee that has no live session yields a legacy group (their `gv` is unknown).
- No rename, no owner-side remove UI, no ownership transfer; owner loss freezes the roster.
- A member can tombstone themselves with an arbitrarily large `seq`, which only they can suffer (the owner cannot re-add them
  without a higher `seq`, and issues `last known + 1`): a self-inflicted denial, not an attack on others.
- A relay that never saw a newer tombstone can show a receiver an outdated active cert (review §6 residual); bounded by full-set
  reconcile on every session-up.
- Verification cost is unmeasured on the target phones (StrongBox sign latency, ECDSA verify per message): owed as a measurement.
