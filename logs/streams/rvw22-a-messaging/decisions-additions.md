# Decision Log Additions — Stream A (rvw22-a-messaging)

For the owner to merge into `docs/decisions.md`. Neither is a unilateral
architecture change: both record behaviour that now exists in code and would be
silently undone by a "cleaner" refactor. Numbers are placeholders — the canonical
file's next free ADR number applies.

---

## ADR-PROPOSED-A1 — Sealing yields “not now”, never “send it readable”

**Status:** implemented in `agent/rvw22-a-messaging` (`9af87bf7`), awaiting the
owner's ADR number.

### Decision
1. `HubSealing.seal(...)` is **total**: it returns `String?` and never throws for a
   key it cannot use.
2. `null` means *this envelope cannot be built right now* (unusable SPKI bytes, or
   the identity key refused to sign). It does **not** mean “the peer published no
   sealing key”.
3. A caller that reached the sealing path because the directory reported
   `HubSealingStatus.AVAILABLE` must treat `null` as a refusal: report it and keep
   the message queued. It must **never** continue into the readable-content branch.
4. `HubSealingStatus.AVAILABLE` keeps its meaning: the directory served a key whose
   binding signature verifies. It is not a claim that the key is usable.

### Context
`DirectoryHubSealing` verifies the recipient's binding signature, and that signature
covers the key's **bytes** — it cannot validate them as a P-256 point. A modified
client can therefore publish and sign garbage. Before this decision:
`ecP256SharedSecret` threw `InvalidKeySpecException` out of the send path (app
crash), and `HubChatBridge.sendPayload`’s `when` treated a null envelope as “is
readable content allowed?”, so with the opt-in on, a message the user chose to seal
could have gone to the hub in plaintext.

### Alternatives considered
- *Audit's literal fix* (“wrap in `runCatching`, return null”) — **rejected**: it
  turns a crash into a plaintext leak at the call site, because the caller already
  used `null` for “no key”.
- *Keep `String` and throw a typed exception* — rejected: the send path is a
  background drain with no user to catch it; a queued message that retries is the
  honest outcome.
- *Extend `HubWaitReason` with a “cannot seal” value* — deferred: that enum's
  vocabulary belongs to the hub UI area, and the plan for this stream was a minimal
  local edit in an owner-edited file. The user-facing sentence is a backlog item.

### Consequences
- A peer with a corrupt published key leaves the sender's message queued until the
  directory serves a usable key. There is no new user-visible sentence yet (log line
  only: `HUB: sealing key unusable, not sending readable id=…`).
- Test call sites must use `requireNotNull`/`assertNotNull`; a test that seals with a
  well-formed key and silently stops asserting on the envelope is now impossible.
- Any future sealing call site inherits the rule: `AVAILABLE` + `null` = stop.

---

## ADR-PROPOSED-A2 — A delivery receipt is the outbox commit point, and the status write is monotonic

**Status:** implemented (`43fe840e`), awaiting the owner's ADR number.

### Decision
Retiring the outbox row and advancing the row's status are **two separate
decisions**:

- `outboxDao.delete(rowId)` is unconditional on a receipt. Once the peer has
  acknowledged the message, the outbox row must go, whatever the bubble shows.
- The status write is `MessageDao.updateStatusIfUnacknowledged(...)`: it advances
  `SENT`/`FAILED` to `DELIVERED` and never moves an already `READ` row back.
  `MessageDao.updateStatus(...)` is for tests and seeding only and is documented as
  such.

### Context
ERROR-031 established the guard for the drain path; the two live receipt sites (the
LAN/hub frame handler and the radio gateway ACK callback) still wrote
unconditionally. A `DeliveryReceipt` legitimately arrives after a `ReadReceipt` — as
a duplicate, over a second path, or as a re-delivery after a reconnect — and the
bubble visibly lost its read tick.

### Alternatives considered
- *“Don't write if the row is READ” inline at each site* — rejected: two copies of
  the rule is exactly how the radio site drifted.
- *Latest-write-wins with a timestamp* — rejected: needs a schema change and does not
  fix the case where the delivery receipt is genuinely newer than the read receipt
  in wall-clock terms but carries older knowledge.
- *Delete the outbox row only when the status actually advanced* — rejected: the
  acknowledgement is real even if the UI value does not change; dropping the delete
  would re-send an acknowledged message.

### Consequences
- `FAILED → DELIVERED` still happens (the peer proved possession), which is why the
  guard is "if unacknowledged", not "only from SENT".
- `MessageDao.updateStatus` has no production caller left. It is kept deliberately
  (tests, seeding, and a future non-delivery status change) with KDoc naming the
  restriction.

---

## ADR-PROPOSED-A3 — “Who wrote this” and “who may speak now” are different questions

**Status:** implemented (`18bd6275`), awaiting the owner's ADR number. It answers F35
of the 2026-10-22 review; its residual is an open decision.

### Decision
1. Signature verification of a group author answers **who wrote this message**: the
   author must be a member of the stored roster *or a former member whose row is a
   tombstone*, because the tombstone keeps the certified key.
2. Whether the author may speak **now** is a separate question, answered only on the
   live lanes by an explicit membership gate (`isActiveGroupMember`,
   `isActiveTrustedMember`, `isRemovedHere`) before the signature check.
3. Catch-up (`SyncPush`) has no per-author membership gate and must not acquire one by
   accident: it exists to deliver history, including the part written by members who
   have since left.

### Context
`verifiedAuthorLabel` carried `takeIf { it.isActive }`. On the four live lanes that
requirement was redundant, because each gates membership already; on catch-up it was
the only per-author check, so it deleted the group's own history the moment a member
left (the audited F35). The audit asked exactly for this split: “verify signatures
against the author's key for the message epoch rather than requiring active
membership today”.

### Alternatives considered
- *Keep the active requirement and store history unsigned* — rejected: it would undo
  F-9/ERROR-082 (an unsigned row in a v2 group must never be accepted or relayed).
- *Accept a tombstoned author only when `messageId` is already known locally* —
  rejected: the newcomer case is exactly the one where it is not known.
- *Bound acceptance by a timestamp* — not possible yet: no removal time exists on the
  roster (see the residual below).

### Consequences and the open decision
The catch-up lane trusts the holder it asked (a request/response, not a broadcast), so
a current member could relay a former member's **late** message into history. Closing
that needs a signed removal time on the roster (`GroupMemberEntity.removedAt` +
a field in `MemberCert`/the leave frame), i.e. a schema and wire change. Until then the
residual is accepted and documented in `deferred-and-residual.md` §1; **do not** paper
over it with a wall-clock comparison, because `membershipVersion` is a certificate
`seq` on v2 paths and wall-clock `now` on the legacy write.

---

## ADR-PROPOSED-A4 — Radio carries text only (decision needed, F55)

**Status:** **PROPOSED, not implemented.** F55 is deferred until this is answered.

### The question
A radio-only peer has no LAN and no hub, so `transportSink` (LAN → hub) never reaches
it: read receipts (`ReadReceipt`) and reactions (`ReactionFrame`) are silently lost.
Delivery is *not* affected — the radio link-layer ACK already covers `DELIVERED`.

### Options
1. **Extend the radio wire** with control frame types (receipt / read cursor /
   reaction), versioned under the existing compatibility rules
   (`docs/network/RADIO-WIRE-FORMAT.md` §13). Correct, and the only option that
   delivers the feature; touches the codec, `RadioOutboxPort`, both hosts and the
   format document.
2. **Tunnel them as text** with an inline marker — rejected: the marker is
   user-visible text on any peer that has not been updated, and it still needs
   negotiation.
3. **Declare radio text-only** and say so where the user can see it, keeping the
   link-layer ACK as the only delivery signal.

### Recommendation
3 now (a stated limit beats silent loss), 1 as its own plan. Whichever is chosen must
be recorded in `docs/decisions.md` and, for option 3, in `docs/known-issues.md` and the
radio UI's own copy.
