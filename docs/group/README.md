# Group Chat + Group Voice Calls — Plan Index

Owner decisions (locked 2026-09-07): phased sizes (small mesh now, large later),
voice-only v1, ad-hoc groups with no admins, sender-responsible + eager-holder-push
offline delivery with holder coordination, LOW-tier protected, text-first Phase 1.

**Status 2026-09-08:** Phase 0 LANDED (protocol frozen in `docs/protocol.md` §Groups,
ADR-030 in `docs/decisions.md`, call trust gate implemented). Phase 1A LANDED (code +
unit tests; 3-device physical gate pending owner run) — see the status note at the top
of `phase-1-group-chat-text.md`. Owner-confirmed deltas from the draft: max 6 members
total, trusted peers only, leave-wins tombstone membership.

## Research basis (read these first)

- WebRTC topology 2026 consensus (VideoSDK, RTMA, Fora Soft, Ant Media, nat.io):
  mesh ceiling ~4–6 for video, **~10–12 for voice-only** (Opus 24–32 kbps;
  11 outbound streams < 400 kbps; audio encode never binds). Voice-first is the only
  topology that fits a serverless LAN app — an SFU needs a server, an MCU needs a
  box that does not exist here.
- Group E2E lineage (USENIX Security '26 Sender-Key analysis, Soatok 2025 Signal
  review, Haven 2026 comparison, OpenMLS/mls-rs docs): pairwise fan-out → sender
  keys → MLS. **MLS needs a Delivery Service to order commits** — correctly deferred
  until relay infrastructure exists.
- Offline DTN pattern (bitchat whitepaper, Kabootar, Aether): sender outbox +
  reconnect retransmit + receiver dedup by ID + bounded TTL/copy-count + end-to-end acks.

## Repo starting position (verified in code, 2026-09-07)

- Reusable as-is: `MessageEntity.senderId`, `ReceiptEntity(messageId, memberId)`,
  `ReactionEntity` reactor sets, `isGroup` plumbed to UI, WS mesh already full-meshes 3+ devices.
- Pairwise knots to cut: `conversationId == peer deviceId` (6 sites), one-row
  one-recipient outbox with first-ACK-wins delete, single read cursor, one-session
  one-PeerConnection calling with busy-decline, pairwise-only ECDH keys, plaintext
  on the wire (TLS itself still future — groups inherit 1:1 transport trust in v1).

## Files

| File | Phase | Gate |
|---|---|---|
| `phase-0-protocol-and-adr.md` | Wire frames, ADR, trust-gap closure | Owner sign-off, no code |
| `phase-1-group-chat-text.md` | Membership, fan-out + quorum, GSYNC catch-up, UI | 3-device test incl. 5-min offline rejoin, LOW ≤ 5 msgs/sec from logs |
| `phase-2-group-voice.md` | 3–6 mesh calls, session map, quiet-hook extension | 4-device LOW-tier call vs 1:1 latency baseline |
| `phase-3-hardening-and-scale.md` | E2E fan-out, admin roles, attachments, 12-member tuning, forwarder spike | Per-item ADRs |

## Cross-phase invariants (do not violate)

1. Unknown wire `action` values are ignored (forward-compat, same rule as `FLASH_CALL`).
2. Receiver dedups by `localId` on every path (exactly-once at the edge).
3. No HIGH pixel/tier regression (ADR-026 rule carries forward).
4. `docs/protocol.md` is updated in the same commit as any wire change (AGENTS.md §17).
5. New tables get explicit non-destructive migrations (no destructive fallback).
