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
| FO-06 | Hotspot client isolation (joined phones cannot reach each other) | 2026-10-02 | IDEA (not prioritised) |

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

**Owner's swarm idea (2026-10-02, recorded, not chosen, no code).** The owner described the relay design above more
concretely: the sender hands the file to **only two members**; those two pass pieces on to others, and so on, like a torrent.
Requirements as stated:
- A member can **receive different pieces from several members at once**.
- If a source drops, **another member holding that piece takes its place**; the transfer does not restart.
- The **sender may go offline** after sending only part of the file (for example half to one member): that member shares what it
  has with the next member, who shares with the next.
- Shown as a diagram on slide 9 of `Flash Progress Update (12 slides).pptx` (a presentation, outside the repo).

What already exists and would be reused: numbered chunks, SHA-256 verified per chunk before it is written, resume from the
persisted done-set (`TransferEntity`), multi-stream dispatch, groups of up to 20 with vouched members, session ceiling 24.
What would be new: (1) a per-member map of which chunks it holds; (2) a **sender-signed list of chunk hashes** so a relay cannot
substitute a chunk (a member that never talked to the sender has no other way to trust a piece; vouched members exist only since
ADR-044 V2); (3) choosing which member serves which chunk and re-assigning when a source drops; (4) member-to-member links, which a
group does not guarantee (ERROR-088 / ERROR-095 show a member may not reach every other member); (5) persistence of "pieces I hold
and may serve" for a member that is not the receiver of record.
Honest limit: a file can only complete while **every piece is held by someone who is online**. If half the file left the sender and
the one holder of that half goes offline too, the rest of the group is stuck until a holder returns. Also a trust question: a member
that serves pieces learns the file, so serving needs the same group membership gate as receiving (a removed member must stop serving).
FO-04 stays POSTPONED (ADR-056); nothing here starts it. It needs its own ADR first.

**Research and plan (2026-10-03):** `docs/transfer/TRANSFER-V2-SWARM-AND-MULTIFILE-PLAN.md` (libraries rejected as transport, the swarm's honest limits, the shared manifest + pull-wire foundation, FA-5 and the transport upgrade, phased with measurement gates). Plan only; this item stays POSTPONED until the owner decides.

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
- The session cap (`SessionHardeningPolicy`, 24 since ADR-057; it was 8) is designed from the group size and the mode by reasoning, with the cost per
  session unmeasured; the values chosen are labelled estimates and revisited here.
- ECO's "about 1 minute" delivery bound is a design target, not a verified result.
- MEAS-01 (PC0, the baseline) is **not** parked by this decision. It stays in the measure-last list (owner decision P8).

**Bring it back when.** The owner wants to tune battery, raise the group size above 20, or trust ECO's bound. The rig
exists: [`docs/network/PC0-RUNBOOK.md`](network/PC0-RUNBOOK.md) (`:core:engine:peerFarm`, `tools/pc0/phone-baseline.ps1`).
Expect to handle ERROR-074 (Transsion freezes Flash at screen-off) first.

**Read first.** PC plan §4 PC6, PC7 and decision P8; ADR-044 phase V3; ADR-048 ("session cap stays a uniform 8 until PC6");
TEST-BACKLOG §5.


---

## FO-06 — Hotspot client isolation: phones joined to a phone's hotspot cannot reach each other

**What.** Owner report (2026-10-02): a mobile hotspot isolates the phones connected to it, so they cannot discover or connect to
each other. The repository already holds the same symptom: on an Infinix Hot 50 hotspot devices could not discover or connect to
each other (ERROR entry of 2026-09-30, which names the hotspot's client isolation as the likely cause; **not isolated by a
controlled test**). Earlier hotspot work (memory `nsd-hotspot-discovery`) found that a joined phone can open a connection to the
hotspot phone but the hotspot phone cannot open one to a joined phone. Android's hotspot documentation
(source.android.com, "Wi-Fi hotspot (Soft AP)", checked 2026-10-02) does not mention client isolation, so this looks like
phone-maker behaviour; which makers and models do it is **unknown** (MEAS-07 / HOT-01 measure it).

**Why it is not fixed by discovery.** mDNS, the DR2 beacon and the DR3 subnet sweep all need phone-to-phone packets; isolation
drops them. DR3 and Connect by IP cover networks that block multicast but allow unicast, not this.

**Options that need no server (none chosen, nothing built, owner has not prioritised).**
1. **Relay through the hotspot phone.** Every joined phone can reach it, so it forwards frames between joined phones. End-to-end
   encryption between the two phones exists (ECDH, HKDF, AES-GCM in v2.0.0-beta), so the relay sees ciphertext. Needs a routing
   frame in the protocol and an ADR; cheap for chat and files, heavy for calls (the host would carry every media stream; see the
   3-to-4 video limit).
2. **Wi-Fi Aware** (NAN). Official API from Android 8 (API 26), connects phones with no access point; needs hardware support
   (`FEATURE_WIFI_AWARE`, `WifiAwareManager.isAvailable()`) and may not work while a hotspot or Wi-Fi Direct is in use (official
   doc, checked 2026-10-02). `CompositeDiscovery.PRIORITY_ORDER` already reserves `WIFI_AWARE`; no code exists.
3. **Wi-Fi Direct** is FO-03.
4. **App-made local-only hotspot** (`WifiManager.startLocalOnlyHotspot`). The Android doc says it exists so that applications on
   devices connected to the hotspot can communicate with each other; no internet. Whether Transsion applies isolation to it is
   unknown; joining needs the network name and password and, for a client app, a `WifiNetworkSpecifier` request.
5. **Host the hotspot elsewhere** (a router, a laptop, another make of phone). Today's workaround; which hosts isolate is unchecked.

Always useful whichever is chosen: detect "same subnet, unreachable" and tell the user why instead of failing silently.

**Bring it back when.** The owner wants the hotspot case to work for phones that join each other, or HOT-01 shows which phones isolate.

**Read first.** `docs/network/DISCOVERY-RESILIENCE-PLAN.md` (DR0 matrix); ADR-056; ERROR entry of 2026-09-30; ERROR-079
(the hotspot host and WebRTC); `docs/testing/TEST-BACKLOG.md` HOT-01...HOT-03.
