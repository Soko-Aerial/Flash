# What this stream deliberately did not fix — and what it corrected

Three findings were not fixed, and two of my earlier one-line summaries were wrong in
a way worth recording, because the wrong version would send the next AI down the
wrong path. This file is the long form: each item now carries the audit's own words,
the code as it actually is (`file:line` at `dev` `93212c7f` plus this branch), the
verdict, the precise reason a fix is not in this branch, the patch a fix would be, and
the tests that would prove it.

Statuses here are the final ones:

| Finding | Final status | Where |
|---|---|---|
| F35 history from a former member dropped | **FIXED** (`18bd6275`) — the *removal time* half is a named follow-up | below, §1 |
| F55 receipts and reactions have no radio path | **DEFERRED** (was wrongly called NOT-A-BUG) | §2 |
| F66 key material is not zeroed | **DEFERRED** (rationale corrected) | §3 |

---

## §1. F35 — Fixed, and the follow-up it leaves open

### What the audit says

> **[F35] [MEDIUM] Historical Group Messages From Former Members Dropped on Catch-up / SyncPush**
> Location: `SignedGroups.kt:1057`, `RealFlashChatRepository.kt:5356-5367`
> `val row = members.member(groupId, authorId)?.takeIf { it.isActive } ?: return null`
> During historical catch-up sync, messages sent by members who later left or were
> removed return null from `verifiedAuthorLabel` because `it.isActive` is false,
> dropping valid history.
> Fix: Verify signatures against the author's key for the message epoch rather than
> requiring active membership today.

### What the code did

`verifiedAuthorLabel` (`SignedGroups.kt:1057`) required `isActive`, and the
catch-up lane uses it as its **only** per-author check:

- `RealFlashChatRepository.kt:5333` `handleSyncPush` accepts a push only in answer
  to a request this device sent, from the peer it asked (`5337-5342`);
- then `5360` verifies the *author* through `verifiedAuthorLabel`;
- a null label drops the message with `bad_signature` (`5364-5369`).

So after a member left, every message they had signed became unrelayable, and a
device that had not yet caught up lost that part of the conversation. Nothing to do
with signatures being wrong — the key was right, the row was a tombstone.

### Why the fix is safe (the part that mattered)

`isActive` in that lookup was doing double duty. Every **live** lane gates
membership itself, *before* the signature check:

| Lane | Own gate before the signature check |
|---|---|
| live group message (`~3604`) | `isActiveGroupMember(...)` → drop reason `not_active_member` |
| live group media (`~3775`) | `isActiveTrustedMember` / `isRemovedHere` |
| hub-file group ticket (`~5905`) | `isActiveGroupMember` |
| catch-up / SyncPush (`~5360`) | **none per author** — it trusts the holder it asked |

So removing the requirement does not let a removed member talk on any live lane; it
only lets *signed history* through the lane that has no other gate.

### What was changed

`SignedGroups.verifiedAuthorLabel` looks the row up without `takeIf { it.isActive }`
(an unknown author is still refused) and verifies against the certified key the
tombstone still carries. The KDoc now says explicitly that this method is about *who
wrote this*, not *who may speak now*, and names the residual below. The comment at
`RealFlashChatRepository.kt:5355` was corrected to match.

Test: `SignedGroupsTest` — *history a member signed before leaving still reaches a
newcomer* (dev-b speaks, leaves, dev-d is added, dev-d receives dev-b's message with
the tombstone's roster label and its signature). Mutation: restoring
`takeIf { it.isActive }` fails that test and **only** that test — the existing
*a member who leaves is a tombstone everywhere and their later messages are dropped*
keeps passing, which is the proof that the live gate is independent.

### The residual this fix does not close (follow-up, needs an owner decision)

The catch-up lane trusts the holder. A current member could therefore relay a former
member's **late** message into another device's history, and the receiver cannot tell
it was written after the removal. Bounding that requires a removal **time** on the
roster, and the data model has none:

- `GroupMemberEntity` (`:core.persistence`): `joinedAt`, `membershipVersion`,
  `operationId`, `isActive`, and the v2 fields `subjectKey` / `certSig` / `issuerId` —
  no `removedAt`.
- `membershipVersion` cannot stand in for time: on v2 roster paths it is the
  certificate `seq` (`RealFlashChatRepository:5756`, `:5813` use `roster.version`),
  while the legacy removal writes wall-clock `now` (`:2593`) and legacy add writes
  wall-clock `now` (`:2342`) — two different scales in one column.
- `MemberCert` (`:core.messaging.protocol`) carries `seq`, `opId`, `active`, `sig` —
  no issue/removal timestamp. `GroupCharter` carries `createdAt` for the group only.
- `GroupWireFrame.Leave` / `RosterEntry` carry `operationId` + `membershipVersion`,
  no time.

**What a decision has to settle:** either (a) add a signed `removedAtMs` (or a
monotonic, verifiable reference such as a signed hash of the removal op) to the
removal cert and a `removedAt` column to `group_members`, so catch-up can accept a
tombstoned author only for messages stamped before the removal; or (b) accept the
residual as a known, documented gap (it requires a cooperating current member, and
the message is visibly attributed to a member everyone can see has left).

Option (a) is a schema + wire change (Room migration, `MemberCert` field, codec,
roster sync), i.e. its own plan, not a fix inside this stream.

---

## §2. F55 — DEFERRED. My earlier "NOT-A-BUG" was too strong

### What the audit says

> **[F55] [LOW] Direct Delivery Receipts & Reactions Cannot Be Sent Over Radio Transport**
> Location: `RealFlashChatRepository.kt:695-704, 6003-6005, 7500-7503`
> Evidence: `transportSink` falls back to `hubBridge`, but has no fallback to `radioBridge`.
> Why it matters: Radio-only peers cannot send delivery receipts or reactions.
> Fix: Add `radioBridge` fallback to `transportSink`.

### What the code does (confirmed)

`RealFlashChatRepository.kt:698-707`:

```kotlin
private val transportSink: MessageTransportSink? = if (hubBridge == null) transportSink else MessageTransportSink { target, frame ->
    val lan = lanTransportSink?.send(target, frame) == true
    if (lan) return@MessageTransportSink true
    ...                       // typing / read / receipt / reaction frames
    if (!hubBridge.canCarry(target)) return@MessageTransportSink false
    hubBridge.sendDirect(target, hubFrameId(frame), timeSource.nowMs(), frame)
}
```

LAN first, then hub — **never** radio. The frames the outbox does not drive go
through this sink: `DeliveryReceipt` (`:6008`), `ReadReceipt` (`:1889`),
`ReactionFrame` (`:6697-6708`). Text messages take a different route
(`sendDirectViaPaths`) which *does* include radio.

### What I got wrong earlier

I told you "radio delivery is a link-layer cryptographic ACK, not a chat
`DeliveryReceipt`, and `RadioChatBridge.sendDirect` accepts only `TextMessage`, so no
receipt/reaction path can exist" and called it **NOT-A-BUG**. The second half is
true and important; the conclusion was not.

- *True:* a radio delivery acknowledgement really is link-layer
  (`RadioChatEngine.queueAckFrame`, `_deliveryReceipts`), so the peer's
  `DELIVERED` state is not lost; a `DeliveryReceipt` frame is not needed for that.
- *Wrong:* **read receipts and reactions** are not covered by any link-layer ACK.
  They have no radio path at all, so a radio-only peer never learns that its message
  was read, and a reaction sent to a radio-only peer never arrives. That is the
  user-visible gap the audit names.

### Why the audit's one-line fix cannot be applied

"Add a `radioBridge` fallback" does not type-check against the radio wire:

- `RadioChatBridge.sendDirect(recipientId, frame: MessageWireFrame.TextMessage,
  emergency)` takes a **concrete** `TextMessage`, not a `MessageWireFrame`
  (`radio/RadioChatBridge.kt:81-85`).
- The port underneath it is `port.sendTextMessage(recipientId, messageId, text,
  sentAt, emergency)` — there is no frame-kind parameter, so a receipt or reaction
  has no encoding.
- The radio wire (`docs/network/RADIO-WIRE-FORMAT.md`) is a text frame in an AX.25 UI
  payload with an AEAD envelope; it has no chat-control frame type, and its own
  version/compat rules (§13) apply.

So the options are:

1. **Extend the radio wire** with a control frame kind (receipt / reaction / read
   cursor), versioned and negotiated. Real work: wire format, codec, both hosts,
   `RadioOutboxPort`, and the format document. That is the honest fix.
2. **Tunnel them as text** with an inline marker — rejected: the marker would be
   user-visible text on any non-updated peer, and it needs the same negotiation.
3. **Declare radio text-only** and say so in the UI/ADR, keeping the link-layer ACK
   as the only delivery signal. Cheapest, and defensible for a companion-mode link,
   but it must be a documented decision rather than a silent gap.

Not fixed here because it is a wire-format change (owner decision, ADR-101 territory
in `docs/decisions.md`), not a fallback line. Recommended: option 3 now + option 1 as
a plan, because a radio-only peer silently losing read state is worse than a stated
limit.

---

## §3. F66 — DEFERRED. Why `clear()` has no safe owner here

### What the audit says

> **[F66] [LOW] Sensitive Key Material Lacks Memory Zeroing (`HubFileKey`, `GroupSecret`)**
> Location: `core/security/.../HubFileSealing.kt:21`, `core/security/.../group/GroupSecret.kt:13`
> Evidence: `HubFileKey` and `GroupSecret` store raw `ByteArray` without implementing
> `clear()` or `Destroyable`.
> Why it matters: Keys linger in heap memory until GC.
> Fix: Provide explicit `clear()` method filling byte array with zeros.

### What the code does (confirmed)

- `GroupSecret` (`group/GroupSecret.kt:13-22`): `private val bytes: ByteArray`, no
  `clear()`, no `Destroyable`; `toByteArray()` returns `bytes.copyOf()`, and
  `fromBytes(bytes)` stores `bytes.copyOf()`. Every holder therefore owns a *copy*.
- `HubFileKey` (`enterprise/HubFileSealing.kt:19-29`): `private val material: ByteArray`
  (itself a `material.copyOf()`), with `key`/`noncePrefix` also returning copies.

### Correction to what I said earlier

I said the instances are "cached in `RoomGroupSecretStore` and reused for proofs".
They are **not cached**: `RoomGroupSecretStore.current/get` re-reads the row and
re-decodes (`GroupSecret.fromBytes(secretWrapped)`) on every call
(`core/engine/.../group/RoomGroupSecretStore.kt:24-42`). The deferral is right; that
particular reason was wrong.

### Why a `clear()` would not do what it promises here

1. **There is no single owner to clear.** `GroupSecret` is consumed by the proof
   session, the rotation path and the handover path, each of which may still be using
   its own copy when another finishes. `HubFileKey` is decoded fresh from the stored
   text at *every* step — `HubFileTransfers.kt:243` (validate), `:257` (send start),
   `:493` (resume) — and `HubFileBinding.kt:487` (incoming) — then held for the life
   of a `HubFileTicket` (`:490`) across a resumable upload/download. Zeroing the
   instance one caller holds would break a live transfer rather than shorten the
   secret's life, and the other decoded instances stay untouched.
2. **Copies are handed out by design.** `toByteArray()`/`fromBytes()`/`key`/
   `noncePrefix` all copy, so a `clear()` on the holder cannot reach the arrays the
   callers already have.
3. **The strongest lever is not a `clear()` at all.** `HubFileKey.encode()` /
   `decode()` move the key through Base64 **Strings**, which are immutable and cannot
   be zeroed, and `GroupSecretEntity.secretWrapped` is the same story. Any honest
   effort has to reduce the number of copies and String round trips first (e.g. keep
   one decoded instance per transfer, pass it explicitly, avoid re-encoding per
   step), and only then add `clear()` for the holders.
4. **Platform reality:** on the JVM/ART a `ByteArray` may have been copied by the GC
   or scalar-replaced by the JIT, so zeroing is best-effort, not a guarantee. The
   benefit is real but small and probabilistic — which is why the audit rates this
   LOW.

### What a decision has to settle

Whether to spend effort on (a) a `clear()`-only change (cheap, weak, and the audit's
literal ask), or (b) the copy/String reduction above plus `clear()` (real benefit,
touches `HubFileTransfers`/`HubFileBinding`, both owner-edited files), or (c) accept
the residual and record it in `docs/security.md` as a known limitation of a
GC-collected runtime. Recommendation: (c) now, (b) as its own item — and never (a)
alone, because it would let a future reader believe the key is erased when copies
and Base64 Strings still hold it.

---

## Summary of what was corrected for the record

1. **F35 was described backwards in my interim summary** ("a removed member can
   backdate `sentAt`"). The audited defect is that legitimate history is *dropped*,
   and it is now fixed (`18bd6275`). The backdating concern is real but is the
   *residual* of that fix, and it needs a signed removal time (§1).
2. **F55 was called NOT-A-BUG** because delivery is ACKed at the link layer. That
   covers `DELIVERED` only; read receipts and reactions genuinely have no radio path,
   so it is **DEFERRED**, with the wire-format decision named (§2).
3. **F66's deferral stood, its reason did not**: no cache exists; the blockers are
   multiple owners, copy-on-read APIs and Base64 Strings (§3).
