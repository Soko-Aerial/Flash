# Phase 3 — Hardening & Scale (each item its own ADR + gate)

## 1. Group E2E: pairwise fan-out first

- Reuse existing ECDH-HKDF-AES-GCM per-member keys: N encrypts per message, zero new
  protocol (the `keyEpoch` field reserved in Phase 0 stays `0` until here, then `1`).
- Quorum/receipt logic is unchanged (receipts authenticate under the same keys).
- **Then** sender keys (one chain key per sender, distributed over pairwise channels —
  the Signal/WhatsApp design, USENIX '26 trade-offs apply: weaker PCS than Double
  Ratchet, documented at decision time).
- **MLS explicitly not here**: needs a Delivery Service to order commits; revisit only
  with relay infrastructure (concurrent-commit fork problem, see README research basis).

## 2. Creator-admin roles + kick

- `group_members.role` (`member` → `admin`) activates — no schema change (reserved in Phase 1).
- `FLASH_GROUP action=remove groupId=… target=… by=<admin-id>`: targets clear state, rotate
  sender keys on next send (post-remove secrecy). Disputed removals resolve by admin-set
  union; non-admin `remove` frames are dropped + logged.

## 3. Group attachments

- Lift the Phase 1 "unsupported" rejection: attachment rows thread under `groupId`;
  transfer pipeline targets the intended recipient set (multi-peer send already routes per
  `peerDeviceId` — extend to member iteration with per-member resume state).
- Catch-up for attachments: metadata syncs via GSYNC; bytes re-request sender-direct
  (no chunk relay in v1 of this item).

## 4. Twelve-member tuning

- Mesh + constraints: cap concurrent decodes (n/a voice-only), dominant-speaker render,
  forced LOW `ptime` 60 ms, quiet cadences retuned against EXP-007 measurements.
- Numbers to beat are recorded in Phase 2 validation; retune, don't redesign.

## 5. Designated-forwarder spike ("poor-man's SFU", design only until demanded)

- Elect one HIGH-tier, charging member to forward (not mix) voice legs past 12 members.
- Election inputs: tier, battery, charging state, measured uplink. Fallback is always full
  mesh — forwarder loss degrades to mesh, never drops the call.
- A real SFU (server) is out of scope for a serverless app; this spike exists so the
  12+ question has a studied answer instead of an assumption.
