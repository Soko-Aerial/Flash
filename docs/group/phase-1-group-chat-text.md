# Phase 1 — Group Chat Text (text-first; attachments explicitly deferred)

**Gate:** 3-device real-chat test including one device offline 5 min then rejoining:
exactly-once catch-up asserted from DB + logs, LOW returner never exceeds 5 msgs/sec.

> **STATUS 2026-09-08 — Phase 1A LANDED (code + unit tests; 3-device gate pending owner run).**
> Shipped in 1A: DB v4 (`group_members`, `group_deliveries`, conversation provenance;
> non-destructive `MIGRATION_3_4`), `GroupWireFrame`/`GroupFrameCodec`/`GroupPolicy`
> (`:core:messaging/protocol`), additive repository API (`createGroup`/`addGroupMembers`/
> `leaveGroup`/`groupMembers`), per-member quorum delivery wired through the existing durable
> outbox, trust fail-closed on every group path, `CallCoordinator.isTrustedPeer` calling gate,
> both hosts (app holder + `Flash.create`) wired. Unit-tested: codec round-trips/unknown-action
> tolerance, six-member bound + creator inclusion, untrusted/non-member rejection, leave
> tombstone vs stale add vs newer re-add, per-member fan-out, full-quorum outbox retirement.
> Deferred to 1B: §3 (FLASH_GSYNC catch-up — codec and docs are ready; no sender yet).
> Deferred to UI pass: create-group surface, "delivered to M of N" rendering. The original
> plan text below stands for 1B.

## 1. Storage (DB v4, explicit non-destructive migration)

- New `group_members(groupId, deviceId, role, joinedAt)` + index on `groupId`. Role column
  exists from day one (all `member`), so Phase 3 admin needs no schema change.
- New `group_deliveries(messageId, memberId, state, attempts)` — per-member delivery state;
  the outbox keeps one row per message (unit of retry), quorum derives SENT→DELIVERED.
- Per-reader cursors `(groupId, memberId, upToMessageId)` — replaces the single
  `lastReadCursor` for groups. `ConversationEntity.isGroup=true` finally stamped;
  add `groupCreatedBy`, `groupCreatedAt`.
- Group messages with attachments are **rejected with a logged "unsupported"** until
  Phase 3 — never silently dropped.

## 2. Send path (`RealFlashChatRepository` + holder)

- Resolve `groupId → member deviceIds` at the `MessageTransportSink.send` seam; fan out one
  `sendText` per live `WsSession`. Keep the `false` = "stays queued" contract per member.
- Branch the six `conversationId == peer deviceId` sites: routing, inbound threading (thread
  by `groupId`, not `senderId`), receipt routing, presence aggregation over the member set,
  title (`group name` + member count), transport label.
- Membership check on inbound: drop frames from non-members (log, same class as trust gate).

## 3. Catch-up (`FLASH_GSYNC` implementation)

- `syncRequest` handler: holders compute held-newer-than-`since`, broadcast `syncClaim`
  within the window (300/500 ms by requester tier).
- Deterministic election `(tierRank, hash(deviceId + msgId))`: rank 0 unicast-pushes paced
  to `maxPerSec`; rank 1 backup timer 2 s; rest stand down. All constants named + pinned
  by unit tests (house style: `CLAIM_WINDOW_MS`, `BACKUP_DELAY_MS`, `LOW_MAX_PER_SEC`, …).
- Batch `syncAck` broadcast drives cancellation + pending-clear on every holder.
- Receiver inserts by `localId` IGNORE-on-conflict (existing rule) — double-pushes never
  double-render.

## 4. UI (`:ui:chat`)

- Group row (name, member count, group affordance), member list, "delivered to M of N"
  ticks, "waiting for members" pending state, per-reader read state.
- Flash-owned components only (§34); no M3 chrome as final UI.

## 5. Tests (or logged-as-impractical)

- Unit: election determinism (same claims → same rank-0 on all devices), budget pacing math,
  quorum transitions, idempotent membership merge, TTL/cap bounds.
- Integration: 3-device matrix incl. the 5-min-offline rejoin; sender-absent catch-up via
  holders; backup-timer fire path (rank 0 leaves mid-round).
- Timing/lifetime properties (claim windows, backup timers) use the EXP-016 rationale if
  not directly asserted: unchanged suite + log evidence is the regression check.
