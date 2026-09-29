# Discovery Resilience Plan (phases DR0–DR7)

**Status: PLAN, written 2026-09-24. DR1 (remembered endpoints), DR2 (broadcast beacon) and DR3 (subnet sweep) IMPLEMENTED 2026-09-29, device checks DR-01, DR-02 and DR-03 pending. ADR-047 is PROPOSED; its DR1 to DR3 parts are built (see the implementation notes in ADR-047). DR5 (hardening) is next.**

**Scope decision (owner, 2026-09-29, ADR-056):** **DR4 (QR), DR6 (BLE) and DR7 (Wi-Fi Direct) are POSTPONED** and live in
[`docs/FUTURE-OPTIMIZATION.md`](../FUTURE-OPTIMIZATION.md) (FO-01, FO-02, FO-03). The active phases are **DR2 (broadcast
beacon), DR3 (subnet sweep) and DR5 (hardening)**, in that order. DR0 (the failure matrix, MEAS-07) stays a measure-last
device task (P8); DR2 and DR3 are built without waiting for it, so its role shrinks to *confirming which network each one
fixes*. Postponed phases keep their text below unchanged; their table rows are marked.

**Order (owner, 2026-09-24), superseded 2026-09-29:** this plan was to start after group calling
(`PRESENCE-CONNECTIONS-PLAN.md` → `docs/calling/GROUP-VIDEO-PLAN.md` → this plan). The owner reordered it on 2026-09-28/29
(DR1 started, then DR2/DR3/DR5 next, then the group trust model and session cap).

## 1. Goal

A paired peer that is reachable over IP must be found even when the network breaks multicast. Common causes:
IGMP snooping, "multicast enhancement" filters, mesh/extender firmware, Android hotspots, several adapters on a
desktop, a restart while multicast was already broken. Discovery must also keep working when the peer's port
or IP changed.

Out of scope: finding peers with **no** shared IP network. That is Wi-Fi Direct (DR7, its own plan).

## 2. Current state (verified in code, 2026-09-24)

| Fact | Where |
|---|---|
| `CompositeDiscovery` merges `FlashRadioTransport`s, dedups by device id and ranks by `PRIORITY_ORDER = LAN, WIFI_DIRECT, WIFI_AWARE, BLE` (unknown names rank last). New sources plug in as transports. | `core/discovery/.../core/CompositeDiscovery.kt:137` |
| mDNS: `NsdManager` on Android (`_flash-transfer._tcp.`, `_flashws._tcp.`); JmDNS on desktop, **one responder per IPv4 interface address**, filtered only by `isUp && !isLoopback && supportsMulticast()`. Virtual adapters (Hyper-V, VPN, VirtualBox) are **not** filtered, so the desktop may advertise an address peers cannot reach. | `NsdFlashDiscovery.kt:294`, `JmdnsBridge.kt:298` |
| Flash's own beacon: `FLASH_MCAST` to `224.0.0.168:45823` on every multicast-capable interface; Android holds a `MulticastLock`. Transport name `multicast`. **No broadcast send.** | `MulticastTransport.kt:593–606`, `AndroidMulticastSocketFactory.kt` |
| Hotspot gateway probe: every auto-connect sweep dials each default IPv4 gateway (Android SoftAP drops mDNS). Android app only. | `DiscoveryEngineHolder.runAutoConnectSweep` (`:1567`) |
| Manual "Connect by IP", with the TOFU pin checked after HELLO. | ADR-040, `connectManual` |
| **(Before DR1) Peer routes live in memory only.** *DR1 (2026-09-29) added persisted routes for paired peers beside this table; `knownEndpoints` itself is unchanged.* `WsFlashNetwork.knownEndpoints` is a `ConcurrentHashMap`; `DiscoveryRouteBinder` calls `forgetEndpoint` as soon as discovery stops advertising a peer. So a peer reached a minute ago is unreachable once multicast breaks, and every route is lost on restart. | `WsFlashNetwork.kt:117`, `DiscoveryRouteBinder.kt` |
| Server ports: WebSocket prefers **TCP 45822** and falls back to an **ephemeral** port if it is taken; Android data channels use 45823–45842; the probe server uses 45821. | `WsTransferServer.PREFERRED_PORT`, `DataChannelServer.start`, `LanProbeServer` |
| Calls use WebRTC with **no ICE servers** (host candidates only), so they need direct L2/L3 reachability without NAT. | `FlashCallSession.kt:833` |
| No persisted endpoint store, no broadcast, no subnet scan, no QR, no BLE, no Wi-Fi Direct code (`WifiP2pManager` has no references). | grep, 2026-09-24 |

## 3. Design

### 3.1 Principle: every new source is a hint, never trust

All sources below only produce *where to dial*. Identity is still proven by the TLS certificate pin plus the HELLO
binding (audit S1, ADR-040, ADR-042). A wrong, stale or forged address costs one failed dial. It can never make the
app talk to the wrong device. So none of this weakens security. The risks are battery, noise on the network and
leaking metadata, and each phase addresses them.

### 3.2 Who decides whether to dial

The PC2 connection planner (`PRESENCE-CONNECTIONS-PLAN.md` §3.6) will exist by the time this plan starts. The new
sources feed **candidates** into it; the planner decides, by discovery mode, whether to dial. This plan does
**not** add a fourth copy of the auto-connect sweep. If PC2 has not landed, stop and ask the owner.

### 3.3 Sources, in value order

**A. Remembered endpoints (DR1).**
- Store `(deviceId, host, port, lastSeenAt, lastConnectedAt, source, failures)` for **paired** peers only, in the
  encrypted Room DB (behind a persistence port, per ADR-024). Up to 4 routes per peer (Wi-Fi, hotspot, Ethernet),
  newest first.
- Write on every successful authenticated session (the address the TLS session actually used), not on sightings
  alone.
- Dial them at startup, on a network change (`AndroidNetworkWatcher` / desktop equivalent) and when discovery has
  not seen a paired peer for N seconds. The planner rate-limits this per mode.
- `forgetEndpoint` stops deleting these; it removes only the *discovery* route. A persisted route is removed on a
  pin mismatch (the address now belongs to someone else), or after K failed dials over at least 7 days.
- Surfaced as a `FlashRadioTransport` named `REMEMBERED`, ranked **below** every live radio, so live sightings win.
  **SUPERSEDED when built (2026-09-29):** not a transport. Transports feed `discoveredEndpoints`, which drives PC3's
  `reachablePeerIds` (Online ring) and the peer list, so a remembered route emitted there would show every paired
  peer as Online whether or not it is reachable. Remembered routes are planner sightings appended after discovery's
  and the tips' (the planner keeps the first sighting per device id, which is what makes a live sighting win). See
  `RememberedRoutes` and ADR-047's DR1 notes.

**B. Broadcast beacon (DR2).**
- Send the existing `FLASH_MCAST` packet to each interface's **directed broadcast** address (e.g. `192.168.1.255`)
  as well as to `224.0.0.168`. Same payload, same port 45823, no protocol version change.
- Receiver: the socket must accept broadcast datagrams on 45823, not only the group. Verify the Android and JVM
  socket factories' bind address first; this is a code check, not an assumption.
  **Checked 2026-09-29:** both factories bind `InetSocketAddress(port)`, the wildcard address with `SO_REUSEADDR`, so the
  receiving side needed no change. A real-socket test on the Windows desktop confirms it
  (`JvmDirectedBroadcastSocketTest`: a datagram sent to `192.168.1.255` arrives on a socket the factory bound). Android
  is **not yet verified on a device** (DR-02).
- Rationale: IGMP snooping and many "multicast" filters do not touch broadcast. To be measured in DR0.
- **Built 2026-09-29 (DR2):** `MulticastSocketBinding.sendBroadcast` / `broadcastTargets`; the transport sends the same
  bytes to the group and then to each interface's directed broadcast, on every announcement including the reply and the
  start-up burst. The address comes from the interface's address and prefix length (`DirectedBroadcast`, pure and
  tested), never from the limited broadcast `255.255.255.255`, which would leave by whichever interface the routing table
  picks. No address is sent to for /31, /32, a prefix wider than /8, a point-to-point interface (VPN) or an unset,
  loopback, multicast or class E address. The transport logs once per bind which addresses each interface uses.
  GHOST stays silent on this path too.

**C. Unicast subnet sweep (DR3).**
- A TCP connect probe to 45822 on every host of the local subnet, **/24 or smaller only**. Wi-Fi and Ethernet
  only, never cellular or metered links. Up to 32 probes in flight, 300 ms timeout. A hit hands `host:45822` to the
  planner, which does the full TLS + HELLO dial.
- Triggers: a user-initiated **"Scan network"** action, plus (owner decision D2) an automatic fallback when the
  device is on Wi-Fi, has paired peers and has discovered nothing for 60 s. At most once per network per 10 min.
- Limitation: it misses a peer whose server fell back to an ephemeral port. DR1 covers that peer once it has been
  reached once.
- Covers hotspot clients, which cannot see each other's multicast either. Take the hotspot prefix from the
  interface; do not hard-code it.
- **Built 2026-09-29 (DR3):** `core/network/.../sweep/` (`SubnetSweepPlan`, `SubnetSweeper`, `SweepPolicy`,
  `SweepController` in `commonMain`; `TcpHostProbe` and the local-subnet source per platform). Hits feed the planner as
  rule 9 (`sweep:<host>` sightings, dialed unnamed like a gateway probe). Nearby has a "Scan network" action
  (`docs/ui/nearby-page.md` addendum). Details and the departures from this text are in ADR-047 "DR3 implementation
  notes". Device check: **DR-03**.

**D. QR first contact (DR4).**
- Desktop (and phone) can show a QR code containing `flash://<ip>:<port>/<deviceId>/<full identity fingerprint>`
  and a one-time pairing nonce. Scanning it dials that address and pairs with the fingerprint pinned out of band,
  which is stronger than the 6-digit code.
- Needs a camera (CameraX) and a decoder (ZXing, Apache-2.0) on Android, and QR encoding on desktop. These are new
  dependencies, so they are documented in ADR-047 before being added, and appear in the generated third-party
  notices (ADR-043). ML Kit is **not** preferred: it depends on Google Play services.
- Pairing interaction: must go through pairing v2 (ADR-042), not around it. The QR provides the pin; the commit
  and reveal still run.

**E. Hardening of the existing sources (DR5).**
1. Desktop: rank or filter virtual adapters (Hyper-V `vEthernet`, VPN, VirtualBox/VMware host-only, WSL) so
   neither JmDNS nor the beacon advertises an address peers cannot reach. Keep a setting to include them.
2. A "No devices found" hint after ~30 s on Wi-Fi with paired peers: *"This network may block device discovery.
   Scan network · Connect by IP · Show QR."* The UI component doc comes first (§34).
3. IPv6 link-local mDNS (`ff02::fb`): only if DR0 finds a network that delivers IPv6 multicast but not IPv4.
   Measure-first; not built by default.
4. A debug-screen line per source showing when it last saw each peer, so field reports say *which* path failed.

**F. Peer-assisted endpoint tips.** Already planned as PC4 (`FLASH_PRES` endpoint tips). Not repeated here. Once
both exist, tips feed the same planner as DR1's remembered routes.

**G. BLE advertisement (DR6, optional).** Advertise a short rotating id and the IP:port. A scanner that recognises a
paired peer hands the address to the planner. Android only (JVM BLE needs WinRT through JNA). Adds
`BLUETOOTH_SCAN`/`BLUETOOTH_ADVERTISE` (API 31+) or location (below 31), and a steady scan cost. Build only if DR0
and field reports show networks that pass unicast but block **both** multicast and broadcast, and the sweep is not
acceptable there. Rotating ids tie in with audit S9.

**H. Wi-Fi Direct (DR7).** A transport, not just discovery; the project's original second path. Gets its own plan
(`docs/network/WIFI-DIRECT-PLAN.md`) when started. Wi-Fi Aware only after that.

## 4. Phases

| Phase | Work | Exit criteria |
|---|---|---|
| **DR0 Failure matrix** | Test today's build on real networks: home router, a mesh (bridge mode), a router with IGMP snooping on, a network with client isolation, an Android hotspot with 2+ clients, a desktop with Hyper-V/VPN adapters. For each: does mDNS work, does the `224.0.0.168` beacon work, does a directed broadcast arrive (a small test sender is enough), is TCP 45822 reachable. Screen on and off (some Android devices filter multicast/broadcast with the screen off. **Reported, verify**). Logged in `logs/experiments.md`. | A table saying which source would have fixed which network. It decides whether DR2, DR3 and DR6 are worth building. |
| **DR1 Remembered endpoints** (**IMPLEMENTED 2026-09-29**; device check DR-01 in `docs/testing/TEST-BACKLOG.md` TODO) | §3.3 A. Persistence port + Room table + migration, the `REMEMBERED` transport, the `forgetEndpoint` change, planner wiring on all hosts. | Tests: a route is written only after an authenticated session; a pin mismatch deletes the route; a live sighting outranks it; restart then reconnect with mDNS disabled. Device check: block multicast (or disable NSD in a debug build), restart both apps, they reconnect. |
| **DR2 Broadcast beacon** (**IMPLEMENTED 2026-09-29**; device check DR-02 in `docs/testing/TEST-BACKLOG.md` TODO) | §3.3 B on the Android and JVM socket factories. | Test: a packet to the broadcast address is parsed like a multicast one. Device check on a DR0 network where multicast failed but broadcast passed. |
| **DR3 Subnet sweep** (**IMPLEMENTED 2026-09-29**; device check DR-03 in `docs/testing/TEST-BACKLOG.md` TODO) | §3.3 C: `SubnetSweeper` in `commonMain` with platform socket actuals; "Scan network" in Nearby; automatic fallback per D2. | Tests: /24 limit, no run on cellular, rate limits, cancellation. Device check: two hotspot clients find each other; a snooping router with multicast and broadcast both blocked. |
| **DR4 QR first contact** (**POSTPONED 2026-09-29**, FO-01) | §3.3 D. ADR-047 amendment for the dependencies; UI component doc; pairing v2 integration. | Desktop shows a QR, phone scans, pairing completes without typing a code, and a tampered fingerprint is refused. Notices regenerated. |
| **DR5 Hardening** | §3.3 E. **Without *Show QR* while DR4 is postponed:** the hint offers *Scan network* and *Connect by IP* only. | Desktop with Hyper-V + VPN adapters advertises only reachable addresses; hint appears and its two actions work. |
| **DR6 BLE** (conditional; **POSTPONED 2026-09-29**, FO-02) | §3.3 G, only if DR0 justifies it. Its own ADR. | Owner decision after DR0. |
| **DR7 Wi-Fi Direct** (**POSTPONED 2026-09-29**, FO-03) | Separate plan. | — |

## 5. Risks

- **A fourth copy of the connection policy.** Mitigation: §3.2. All sources feed the PC2 planner.
- **Battery from re-dialling remembered routes.** Mitigation: the planner's per-mode limits; ECO dials remembered
  routes only on a network change or when the user opens the chat.
- **The sweep looks like a port scan** to corporate or campus security tools. Mitigation: never automatic on
  networks larger than /24; a manual trigger; D2 lets the owner keep it manual-only.
- **Metadata at rest.** Stored IPs reveal where devices have been. They live in the encrypted DB, which is already
  excluded from backups (audit S4). Old routes expire.
- **Broadcast on shared networks.** It reaches every host on the subnet, as multicast already does, carrying the
  same payload. No new information is exposed. Rotating ids (audit S9) would improve both.

## 6. Owner decisions (open)

| # | Question | Recommendation |
|---|---|---|
| D1 | Remember endpoints for paired peers only, or also for unpaired peers? | **Paired only.** Unpaired peers don't need a route after the session ends. |
| D2 | Subnet sweep: manual only, or also an automatic fallback? | **Both**, with the automatic one limited as in §3.3 C. |
| D3 | QR library: ZXing + CameraX, or ML Kit? | **ZXing + CameraX** (no Play services dependency, Apache-2.0). **Moot for now: DR4 is postponed (FO-01); the recommendation stands for when it returns.** |
| D4 | Is BLE (DR6) in scope at all? | **Decide after DR0.** **Answered 2026-09-29: postponed (FO-02).** |
| D5 | Does Wi-Fi Direct (DR7) come right after this plan? | Owner's call. It is in the original project goals. **Answered 2026-09-29: no, postponed (FO-03).** |

D1 (paired only) is **still unconfirmed**: DR1 was built on the recommendation and the owner has not objected, which is not
an answer. D2 (automatic sweep fallback) is also unanswered; DR3 was **built** on the recommendation (manual action plus the
limited automatic fallback). The fallback is one switch (`SweepController(autoEnabled = ...)`, default on, no user setting
yet), so the owner can turn it off without a redesign.
