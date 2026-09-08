# Phase 2 — Group Voice Calls, 3–6 Mesh (voice-only v1)

**Gate:** 4-device call on LOW-tier hardware; latency judged against the 1:1 baseline
(same stats-badge methodology as ADR-026 work).

## 1. Coordinator: session map (1:1 path untouched)

- `CallCoordinator.currentSession` → `Map<groupCallId, Map<peerId, FlashCallSession>>`.
  The 1:1 single-session path keeps its exact behavior (busy-decline generalizes to
  "in any call → auto-decline").
- `sendFrame(frame, peerId)` already supports fan-out — signaling reuses it per member.
- `ginvite/gaccept/gdecline/gjoin/ghangup` per `phase-0` §1.4. Start at ≥2 accepts;
  late-join offers from any in-call member, first wins; one-member remainder shows
  "waiting…" for the existing disconnect-grace window, then ends.

## 2. Media (full mesh of 1:1 legs)

- One `PeerConnection` per accepting member; ICE stays host-candidate LAN-only (unchanged).
- Caller-offers-only **per leg**; per-leg ICE restart reuses the existing caller-only loop.
- No server mix: each device renders N−1 remote audio tracks (ADM mixes playout locally).
  Dominant-speaker indicator from per-track audio levels in the existing `getStats` samples.
- LOW legs force 60 ms `ptime` (ADR-026 quiet work applies per leg, not per call).

## 3. ADR-026 quiet-hook extension (extends, never forks)

- `setCallActive` fires for group ACTIVE too (ECO + sweep skip are leg-count agnostic).
- Transfer watcher quiet is unchanged (one flag, any call shape).
- Stats sampler: **one `FlashCallStats` thread per call, shared by all legs** (legs share
  the sampler loop; per-leg `sampleStats` calls serialize on it).

## 4. UI (`:ui:callui`)

- Tile/grid for N−1 remote peers (audio tiles with speaker highlight — voice-only v1, but
  reserve the tile slot shape for future video), member join/leave toasts, per-leg
  connection state, group hang-up. Follows `docs/ui/calling-ui.md` conventions.

## 5. Tests / validation

- Unit: session-map lifecycle (join/leave/end edges), election-free first-offer-wins,
  one-member-remainder timeout, busy-decline while in group call.
- Device: 4-device LOW-tier call; per-leg RTT/jitter vs 1:1 baseline; `getStats` sampler
  thread count stays 1/call (assert via thread-name log).
- Out of scope: video legs (reserved tile slots only), recording, PSTN-style hold/transfer.
