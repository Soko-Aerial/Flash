# Future Optimization — parked work

**Created 2026-09-29 (owner decision, ADR-056).** This file is the single list of work the owner has *deliberately
postponed*. Nothing here is forgotten and nothing here is cancelled; it is simply not next.

**Rules**

1. An item is added here in the same session the owner postpones it, with the reason and what would bring it back.
2. Never delete an item (AGENTS.md §27). When work starts, set its Status to `STARTED` and link the plan/ADR that took it;
   when it ships, `DONE`; when it stops making sense, `OBSOLETE` with the reason.
3. A parked item is **not** a hole in the current work. Each entry states what the rest of the project assumes in its
   absence, so nobody builds on a guess.
4. Device tests/measurements owed for parked work stay in `docs/testing/TEST-BACKLOG.md` (marked `POSTPONED`), so the
   test list stays complete.

| ID | Item | Parked since | Status |
|---|---|---|---|
| FO-01 | QR first contact (DR4) | 2026-09-29 | POSTPONED |
| FO-02 | Bluetooth (BLE) discovery (DR6) | 2026-09-29 | POSTPONED |
| FO-03 | Wi-Fi Direct transport (DR7) | 2026-09-29 | POSTPONED |
| FO-04 | Group file sending: fan-out cost and a revamp of file sending | 2026-09-29 | POSTPONED |
| FO-05 | Scale and battery measurement, PC6 / MEAS-02 (and the choice of 32) | 2026-09-29 | POSTPONED |

---

## FO-01 — QR first contact (DR4)

**What.** A device shows a QR code carrying `flash://<ip>:<port>/<deviceId>/<full identity fingerprint>` plus a one-time
pairing nonce. Scanning it dials that address and pairs with the fingerprint pinned out of band, which is stronger than
the 6-digit code and needs no discovery at all.

**Why parked.** Owner priority (2026-09-29): finish DR2, DR3, DR5 first. The QR path also brings new dependencies
(CameraX and a decoder on Android, QR encoding on desktop) and a new UI component that must be researched first (§34).

**What the project assumes meanwhile.** First contact needs either discovery (mDNS, beacon, and once built the broadcast
beacon and the subnet sweep) or **Connect by IP**. DR5's "No devices found" hint therefore offers *Scan network* and
*Connect by IP* only; the *Show QR* action in the plan's wording is left out until this ships.

**Bring it back when.** Field reports show people who cannot pair because no discovery source reaches the peer, or the
owner wants camera-based pairing for its own sake.

**Read first.** `docs/network/DISCOVERY-RESILIENCE-PLAN.md` §3.3 D and §4 DR4; ADR-047 (needs an amendment for the
dependencies before any is added); ADR-042 (pairing v2: the QR supplies the pin, the commit and reveal still run);
ADR-043 (third-party notices). Owner decision D3 recommends ZXing + CameraX (Apache-2.0, no Play services), not ML Kit.
A UI component doc under `docs/ui/` must reach DESIGNED before implementation.

---

## FO-02 — Bluetooth (BLE) discovery (DR6)

**What.** Advertise a short rotating id and the IP:port over BLE; a scanner that recognises a paired peer hands the
address to the connection planner as a dial hint.

**Why parked.** It was always conditional on DR0's failure matrix (MEAS-07), which is itself an owed device measurement,
and it costs a steady scan/advertise drain plus new permissions. Owner priority (2026-09-29).

**What the project assumes meanwhile.** A network that passes unicast but blocks both multicast and broadcast is covered by
DR3 (the subnet sweep, manual and automatic), not by a radio. A device pair with no shared IP network at all is not
discovered (that is FO-03).

**Bring it back when.** MEAS-07 or field reports find networks where the sweep is unacceptable (larger than /24, client
isolation that still allows unicast to known addresses) and multicast and broadcast both fail.

**Read first.** Plan §3.3 G. Android only (JVM BLE would need WinRT through JNA). `BLUETOOTH_SCAN`/`BLUETOOTH_ADVERTISE`
on API 31+, location below 31: check current Android docs (AGENTS.md §13). Needs its own ADR. Rotating ids tie in with
audit S9. `CompositeDiscovery.PRIORITY_ORDER` already reserves the `BLE` rank.

---

## FO-03 — Wi-Fi Direct transport (DR7)

**What.** The project's original second path (AGENTS.md §2, §15): `WifiP2pManager` discovery and group formation, then IP
connectivity handed to the same `ConnectionManager` and transfer engine. Wi-Fi Aware only after that.

**Why parked.** Owner priority (2026-09-29). It is a transport, not a discovery source, so it needs its own plan
(`docs/network/WIFI-DIRECT-PLAN.md`, **not yet written**) and physical-device testing across group-owner behaviour.

**What the project assumes meanwhile.** Both peers must share an IP network (Wi-Fi, hotspot or Ethernet). `WifiP2pManager`
has no references in the code (verified 2026-09-24). Owner decision D5 ("does Wi-Fi Direct come right after DR?") is
answered: **no, it is parked**.

**Bring it back when.** The owner wants transfers between phones with no shared network as a headline feature.

**Read first.** AGENTS.md §15; plan §3.3 H; ADR-054 (WebRTC on Android already sees local-only links such as a hotspot
and a Wi-Fi Direct group, so calls should follow once the link exists). Do not assume the same device becomes group owner
or the same IP behaviour across devices.

---

## FO-04 — Group file sending: fan-out cost and a revamp of file sending

**What.** Today a group attachment is sent by the sender to each member separately: `sendGroupAttachment` creates one
`GroupDeliveryEntity` per recipient, and each recipient re-requests the bytes sender-direct (no chunk relay; see
`docs/group/phase-3-hardening-and-scale.md` §3). At N members the sender uploads the file N−1 times. The owner also wants
the **sending of files itself revamped** later, not only its group case.

**Why parked.** Owner decision (2026-09-29): the trust model and the session cap come first; the fan-out cost and the
send-path revamp are a later optimization.

**What the project assumes meanwhile.** Group attachments keep the per-recipient model. That is fine at 6 and gets
expensive at 20 (a 100 MB file is about 1.9 GB of upload from one phone). **Consequence for the trust-model work:**
ADR-044 already says to revisit "if attachments in groups land" because of this; that revisit is what this item is. Raising
groups to 20 (ADR-044 V2) does not wait for it, but the cost is known and accepted.

**Ideas on the table (none chosen).**
- A relay design: members that already have the file serve the chunks (swarm-style), with the sender as the seed.
- A designated forwarder (`phase-3-hardening-and-scale.md` §5 sketches this for voice).
- Per-member resume state on the sender, capped concurrent uploads, and queuing by tier and battery.
- A broader revamp of the send path: resume, chunking and per-peer scheduling as one design, not a group patch.

**Bring it back when.** Groups above 6 exist in practice (after ADR-044 V2), or a user reports slow/failed group file
sends, or the owner opens the send-path revamp. It needs its own ADR before code.

**Read first.** ADR-044 "Revisit when" and Consequences; `RealFlashChatRepository.sendGroupAttachment` (~line 1165) and
`beginGroupAttachment` (~1642); `docs/group/phase-3-hardening-and-scale.md` §3 and §5; AGENTS.md §18 (large files, resume).

---

## FO-05 — Scale and battery measurement: PC6 / MEAS-02, and the choice of 32

**What.** 20 peers (the peer farm plus phones) in each discovery mode: battery per hour, the reconnect storm after a Wi-Fi
blip, delivery latency to a screen-off ECO phone. This is PC6 of `docs/network/PRESENCE-CONNECTIONS-PLAN.md` and ADR-044
phase V3, and TEST-BACKLOG **MEAS-02**. It is the measurement that would replace the plan's *(measure)* estimates and
decide PC7's final tuning, the default mode, and whether groups may exceed 20.

**Why parked.** Owner decision (2026-09-29): measurement is future optimization, so the rest of the presence and
connections plan is unblocked for immediate implementation.

**What the project assumes meanwhile (decided here, not measured).**
- Every number tagged *(measure)* in the plans stays an **estimate** and is documented as one. Nobody may cite it as data.
- The group size target for the trust-model work is **20** (ADR-044 V2). **32 stays parked**: ADR-044 makes it depend on V3,
  which is this measurement. Nothing implements or advertises 32 until FO-05 is done.
- The session cap (`SessionHardeningPolicy`, 8) is designed from the group size and the mode by reasoning, with the cost per
  session unmeasured; the values chosen are labelled estimates and revisited here.
- ECO's "about 1 minute" delivery bound is a design target, not a verified result.
- MEAS-01 (PC0, the baseline) is **not** parked by this decision. It stays in the measure-last list (owner decision P8).

**Bring it back when.** The owner wants to tune battery, raise the group size above 20, or trust ECO's bound. The rig
exists: [`docs/network/PC0-RUNBOOK.md`](network/PC0-RUNBOOK.md) (`:core:engine:peerFarm`, `tools/pc0/phone-baseline.ps1`).
Expect to handle ERROR-074 (Transsion freezes Flash at screen-off) first.

**Read first.** PC plan §4 PC6, PC7 and decision P8; ADR-044 phase V3; ADR-048 ("session cap stays a uniform 8 until PC6");
TEST-BACKLOG §5.
