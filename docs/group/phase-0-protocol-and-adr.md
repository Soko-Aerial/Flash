# Phase 0 — Group Protocol + ADR (no code)

**Gate:** owner sign-off. Output is `docs/protocol.md` group section + one ADR + trust-gap
closure. No implementation starts without all three.

> **STATUS 2026-09-08: LANDED.** The frozen contract is `docs/protocol.md` §Groups +
> ADR-030 in `docs/decisions.md`. Differences from the draft below (owner-confirmed):
> membership frames carry `opId` + `version` and merge as a leave-wins tombstone log, not a
> set union; member lists are `memberCount` + indexed `memberN` fields, not CSV; maximum size
> is 6 including the creator; trust is fail-closed for every group frame, and the 1:1 CALL
> trust gate is implemented (`CallCoordinator.isTrustedPeer`). The draft text below is kept
> as the historical proposal.



## 1. Wire frames

All frames use existing `FlashTextFraming` field rules. Every group frame carries
`groupId + senderId + msgId`. Unknown `action` values are ignored by all receivers.

### 1.1 Membership (`FLASH_GROUP`)

```text
FLASH_GROUP action=create groupId=<uuid> from=<id> name=<escaped> members=<csv-ids>
FLASH_GROUP action=add    groupId=<uuid> from=<id> members=<csv-ids>
FLASH_GROUP action=leave  groupId=<uuid> from=<id>
```

- Ad-hoc v1: no roles, no kick, no accept step — `create`/`add` recipients auto-join.
- Membership merges are idempotent set-unions keyed by `groupId`, **never order-dependent**:
  concurrent `add`s from A and B merge by `(timestamp, deviceId)` last-writer-wins; both end
  up members, no fork.
- `leave`: members mark the row left (history kept, sending stops).

### 1.2 Chat (extends existing `FLASH_MSG` family)

```text
FLASH_GMSG groupId=<uuid> msgId=<uuid> from=<id> name=<escaped> sentAt=<ms> text=<escaped> [replyTo=<id>]
FLASH_GRCPT groupId=<uuid> msgId=<uuid> from=<id> deliveredAt=<ms>
FLASH_GREAD groupId=<uuid> from=<id> upTo=<msgId> readAt=<ms>
```

- `FLASH_GMSG` reuses `MessageWireFrame.TextMessage` shape + `groupId`; threading key is
  `groupId` (not `senderId` — the 1:1 rule that must be branched).
- Delivery status per message derives from the quorum side table (§Phase 1), rendered as
  "delivered to M of N".
- `keyEpoch=<n>` field reserved on every frame from day one (always `0` in v1) — the E2E
  hook, so Phase 3 crypto needs no wire-format break.

### 1.3 Catch-up sync (`FLASH_GSYNC`, one `syncId` UUID per round)

```text
FLASH_GSYNC op=request groupId=<uuid> syncId=<uuid> from=<id> since=<ms> tier=<low|medium|high> maxPerSec=<n> maxTotal=<n>
FLASH_GSYNC op=claim   groupId=<uuid> syncId=<uuid> from=<id> msgIds=<csv>
FLASH_GSYNC op=push    (unicast) groupId=<uuid> syncId=<uuid> from=<id> + full GMSG payload
FLASH_GSYNC op=ack     groupId=<uuid> syncId=<uuid> from=<id> msgIds=<csv> [hasMore=<0|1>]
```

- Claim window: 300 ms (500 ms when requester is LOW). Election per message over claimants
  sorted by `(tierRank, hash(deviceId + msgId))`: rank 0 pushes unicast paced to `maxPerSec`,
  rank 1 arms a 2 s backup timer, ranks 2+ stand down. Max 2 copies/message/round.
- `ack` is broadcast so all holders cancel backups and clear pending (holder-to-holder sync
  without a holder channel). `hasMore=1` opens a follow-up round when the returner is ready.
- Budgets: LOW returner `5/sec, 100/round`; HIGH `20/sec, 500/round`. TTL 24 h, 100 pending
  msgs/member cap (bitchat/Aether-class bounds, phone-fit).

### 1.4 Group call signaling (extends `FLASH_CALL`, Phase 2)

```text
FLASH_CALL action=ginvite groupCallId=<uuid> groupId=<uuid> from=<id> name=<escaped>
FLASH_CALL action=gaccept groupCallId=<uuid> from=<id>
FLASH_CALL action=gdecline groupCallId=<uuid> from=<id>
FLASH_CALL action=gjoin   groupCallId=<uuid> from=<id>
FLASH_CALL action=ghangup groupCallId=<uuid> from=<id>
```

- Caller-offers-only generalizes **per leg** (glare-free per pair, same proof as 1:1).
- Late join: any in-call member may offer; first offer wins, losers get `gdecline`.
- Call starts at ≥2 accepts; one member remaining shows "waiting…" for the grace window.

## 2. ADR contents (new entry in `docs/decisions.md`)

1. Topology table (mesh ≤6 → mesh+constraints ≤12 → designated-forwarder spike beyond;
   no MCU anywhere — nothing to run it on).
2. Receipt-quorum rule (all-reachable-acked; give-up window preserves liveness).
3. Offline rule: sender-responsible + eager holder push with claim/elect/batch-ack.
4. E2E phasing: v1 inherits transport trust → pairwise fan-out → sender keys → MLS
   **deferred until relay/ordering infrastructure exists** (concurrent-commit problem).
5. Trust-gap closure: calling ignores trust today (ADR-025 open item) — groups must not
   inherit it; close the 1:1 gap first or gate group join on `isTrusted`.

## 3. Risks recorded at Phase 0 (not discovered later)

- No total order across senders: display sorts `(sentAt, senderId, localId)`; membership
  uses idempotent state, never order.
- Sender-absent + all-holders-absent: message waits; UI shows "waiting for members", never
  silent loss; TTL bounds it.
- Battery: 6-member mesh voice ≈ 5× 1:1 cost; ADR-026 ECO-during-call is the counterweight.
