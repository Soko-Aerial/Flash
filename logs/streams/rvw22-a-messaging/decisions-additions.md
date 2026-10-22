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
