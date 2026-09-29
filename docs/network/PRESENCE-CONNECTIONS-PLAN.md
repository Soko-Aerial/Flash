# Presence & Connections Plan (phases PC0–PC7)

**Status (2026-09-29): PC1–PC5 code done (unit-tested, not device-tested). PC7's platform note written. PC6 is
POSTPONED to `docs/FUTURE-OPTIMIZATION.md` FO-05 (owner decision P9), so **PC7 no longer waits for it** and no phase of this
plan is blocked. PC0 stays a measure-last task (P8).**
Numbers marked *(measure)* are estimates until PC0 (and, one day, PC6) replace them. **They remain estimates and must be
described as such; nothing may cite them as data.**

**Testing order changed (owner, 2026-09-28, decision P8):** implement PC1–PC5 and then group calling first, and
run all device measurements and tests (PC0, PC6, and each phase's device check) at the end. Until then every
phase is gated by unit and loopback tests only, STANDARD must keep today's behaviour, and each phase's
device exit criterion is recorded as **pending** rather than met. The PC0 rig and runbook stay ready; the first R0
attempts on 2026-09-28 found ERROR-074 (Transsion freezes Flash at screen-off), which the final test pass must
handle first.

**Order:** this plan comes **before group calling** (`docs/calling/GROUP-VIDEO-PLAN.md` G1+). G0's measurements can
share PC0's test rig. ADR-044's V3 scale measurement **is** PC6 here. ADR-044's V0 threat review can run in parallel.

## 1. Goals (owner: all three)

1. **Battery:** as little pinging and as few connections as possible, when the user wants that.
2. **Faster, more accurate reconnection:** peers come back quickly after a Wi-Fi drop or roam, and the online status
   is accurate.
3. **Scale:** groups of 20 (then 32) without every phone keeping 19–31 connections alive.

The user chooses the trade-off with the existing discovery modes: **ECO** (battery), **STANDARD** (balanced) and
**BOOST** (instant; battery disregarded).

## 2. Current state (verified in code, 2026-09-24)

| Fact | Where |
|---|---|
| **Every phone dials every discovered Flash device, paired or not**, every 5 s sweep, until a session exists. Both ends dial; `AutoConnectGate` limits it to one attempt per peer per window. | `DiscoveryEngineHolder.runAutoConnectSweep` (app, `:1533`), `Flash.runAutoConnectSweep` (`core/engine`, `:957`), `DesktopEngine.dialIfNeeded`: **three copies** of the same policy. |
| **Each connection runs its own ping timer**, started when the connection opened, so timers aren't aligned. **Both ends ping.** `WsKeepalive.onTick` returns `Ping` on every on-time tick, **even when messages just flowed**. | `WsConnection.kt:134` (androidMain and jvmMain copies), `WsKeepalive.kt:94`. |
| Ping and liveness timing is set by the **hardware tier**, not by any mode: LOW 15 s / 40 s, MEDIUM 12 s / 32 s, HIGH 10 s / 25 s. | `FlashTransportProfile` |
| Discovery modes: STANDARD, GHOST (browse only, not advertised), BOOST (faster discovery restart only), ECO (20 s browse / 100 s idle), RECEIVE_KIOSK. **Modes don't touch connections.** The Quick Settings tile cycles Standard → Ghost → Eco → Off; **Boost isn't on the tile.** | `DiscoveryModePolicy`, `FlashTileService` |
| The engine holds a partial **wake lock** and a `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi lock for its whole lifetime (the ERROR-026 fix). **Android docs (checked 2026-09-24):** the low-latency lock is only active with the screen on, the app in the foreground and a connection to an access point. So the screen-off cost is the CPU wake lock plus our own timer wake-ups. | `DiscoveryEngineHolder` (`:2194`–`:2203`); developer.android.com `WifiManager` reference |
| **The online dot is the session set** (`activeSessions`). The 6 s offline hold is presentation-only. Sends and calls need a live session. | `RealFlashChatRepository.isOnline`, `holdOfflineTransitions` |
| Recovery is asymmetric: the original dialer redials with a 1 s floor; the accepting side has a backup loop with a 4 s floor. | `WsFlashNetwork` (ERROR-026) |
| **Every host caps live sessions at 8** (`SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS`; found 2026-09-28 while preparing PC0). A 9th peer is refused with "session cap reached", and both sides retry every 5 s. So today a phone cannot hold 12 or 19 sessions, and groups above 9 members cannot form a full mesh. PC2 must decide the cap per mode; ADR-044 V2 and the group caps depend on it. **Superseded 2026-09-29 (ADR-057): the ceiling is 24 for every mode and STANDARD/BOOST limit their own dials in a crowd; this row records what was found.** | `SessionHardeningPolicy.kt:76`, `WsFlashNetwork.registerSession:547`, `JvmWsFlashNetwork:427` |

**Invariants this plan must not break** (each one was learned from a real field bug):
- Never judge a peer dead from a wall-clock gap without proving our own scheduler ran (`WsKeepalive` stall rule,
  ERROR-025).
- Never debounce presence at the source. Sends, transfers and calls read raw session truth.
- Power locks are owned by the engine lifetime (`DiscoveryEngineHolder`), never by a Service instance.
- A transport that dedups sightings must emit `Presence`, or the sweeper declares the peer lost.
- Retry budgets are wall-clock based, not attempt counts.

## 3. Design

### 3.1 Three presence states (owner decision)

| State | Meaning | Shown as *(visual in the UI doc)* | Can send/call directly? |
|---|---|---|---|
| **Connected** | I have a live session with this peer myself. | Solid dot | Yes |
| **Online** | No session of mine, but I saw them recently through discovery, or a mutual contact reported them. | Ring dot | Yes, **by dialing on demand** (§3.5) |
| **Offline** | Neither. | None | Messages queue in the outbox as today |

Sends, transfers and calls gate on **Connected**, or dial on demand when **Online**. They never trust a reported
status as proof of reachability. Before PC3, the visuals go into the relevant P2P-status UI component doc (AGENTS
§34: designed before implemented).

### 3.2 Presence sharing (owner's idea)

A phone passes on what it knows: "I'm connected to user 2 (seen 3 s ago), at 192.168.1.20:port."

**Frame:** `FLASH_PRES` (a new text-frame prefix, documented in `docs/protocol.md` in PC4). It is only sent on
sessions with a **paired peer or a fellow group member**.
- `delta`: sent on change (someone connected or dropped).
- `digest`: a full refresh every *presence refresh* interval (§3.4).
- Each entry: `deviceId`, `ageMs` (how long ago, **never a clock time**, because phone clocks disagree), `state`,
  optional `endpoint` (host:port), `hops`.
- The receiver adds its own delay to `ageMs`. Entries older than the mode's *max age* are dropped. At most **2 hops**.

**Who may learn about whom: mutual contacts only (owner decision).**
- **Group members:** rosters are shared already, so presence about a fellow member goes to every member.
- **Paired contacts outside groups:** when a session opens, each side sends a per-session random salt. The asker
  sends `H(salt, id)` for each of its contacts; the reporter answers only for hashes matching peers it knows itself.
  Device ids are random UUIDs, so the reporter learns nothing about contacts it doesn't already know, and the asker
  learns only about mutual contacts. A fresh salt per session prevents linking across sessions.

**Ghost devices are never shared (owner decision).** A Ghost device sets `noShare` in its HELLO/session. Peers
never include it in any `FLASH_PRES`, and it never reports itself. It may still *receive* presence, since browsing
is allowed in Ghost.

**Safety rules:**
- A report **never creates trust**. It only changes the displayed state and when to try dialing.
- An `endpoint` tip is dialed like any discovered address, and the connection still passes the TLS pin and the
  identity binding. A false tip costs one failed dial.
- My own direct observation always beats a report.
- Reports are rate-limited per sender. A sender whose tips repeatedly fail is ignored for the session.

### 3.3 Keepalive efficiency (every mode; no protocol change)

1. **One shared ticker per device.** All sessions are serviced on one aligned timer, so the radio and CPU wake once
   per interval instead of once per connection.
2. **Traffic counts as proof.** No ping on a session that received anything within the interval. The `WsKeepalive`
   stall rule stays exactly as it is.
3. **One pinger per pair.** The side in the more active mode pings (BOOST over STANDARD over ECO); on a tie, the
   lower device id pings. The other side only answers. Old clients keep pinging, which is harmless.

### 3.4 What each mode does with connections

| Knob | **ECO** (battery) | **STANDARD** (balanced) | **BOOST** (instant) |
|---|---|---|---|
| Keeps sessions with | Up to **3 neighbours** among paired/group peers (picked the same way on every phone, from sorted ids, so the group stays joined up), plus peers with activity in the last 10 min, plus anyone in an active call or transfer, plus unpaired peers **only while the Nearby screen is open** | Every discovered device, as today | Every discovered device, redialed aggressively |
| Idle close | A non-neighbour with no traffic for 10 min | Never | Never |
| Ping interval (idle sessions) | 30 s *(measure)* | Tier value (10–15 s) | 5 s *(measure)* |
| Liveness timeout | 75 s *(measure)* | Tier value | 15 s *(must stay > 2 × ping)* |
| Presence refresh / max age | 60 s / 90 s | 30 s / 45 s | 10 s plus instant deltas / 20 s |
| Reconnect floor / cap | 2 s / 30 s | Tier value | 250 ms / 5 s |
| Discovery | Existing ECO duty cycle | Continuous | Continuous, BOOST backoff |
| Wake lock + Wi-Fi lock | **Kept** (owner: ECO may be up to ~1 min late, not 15) | Kept | Kept |
| Target: status accuracy and delivery with the screen off | ≤ ~1 min | A few seconds | ~1 s |

**Notes:**
- **ECO delivery is usually still immediate.** The receiver keeps its listening socket and wake lock, so a sender
  who knows the address dials and delivers straight away. The ~1 min bound covers a *changed* address that
  discovery hasn't seen yet. ECO's existing 100 s discovery idle is longer than that; presence tips from
  neighbours are expected to cover the gap. PC6 checks this, and if it fails, the ECO idle drops to 40 s.
- **Mixed modes:** the side that wants the connection keeps it. An ECO phone always **accepts** incoming sessions
  and never closes one the other side dialed. It answers pings but doesn't send them, per the one-pinger rule.
  This stops a BOOST phone and an ECO phone from churning.
- **GHOST** uses STANDARD's connection policy plus `noShare`. **RECEIVE_KIOSK** uses STANDARD's.
- **The hardware tier still applies.** The mode picks the strategy; a LOW-tier phone in BOOST still gets the full
  mesh, but its liveness timeout never drops below LOW's floor. That floor protects against slow schedulers and
  long roams, not battery.

### 3.5 Dial on demand and faster reconnect

- **Sending to an Online peer** (no session) dials first, with a ~1 s budget on a LAN, then sends. The outbox
  drain takes the same path.
- **Presence tips trigger dials.** "User 2 is back at X" makes me dial now instead of waiting for mDNS. This also
  fixes the accepting-side slowness (ERROR-026), because that side now hears about the peer.
- **Deterministic dialer:** after a link change, the lower id dials immediately; the higher id waits the
  backup-loop floor. This means fewer simultaneous-dial glares.
- **Staggered storms:** after a Wi-Fi drop, each phone delays its reconnect wave by `hash(deviceId) mod 2 s`, so
  190 handshakes don't all land in the same second.

### 3.6 One connection planner instead of three copies

A pure `ConnectionPlanner` in `commonMain` takes the mode, tier, discovered peers, trust, groups, activity and
presence, and outputs which sessions to hold, open or close. The app holder, `core/engine` and the desktop engine
all call it. This follows the migration direction (one shared frame) and makes §3.4 unit-testable.

## 4. Phases

| Phase | Work | Exit criteria |
|---|---|---|
| **PC0 Measure the baseline** | Belfone, a mid-range phone, a Pixel and the desktop, plus a **peer farm** of N headless JVM Flash instances on the PC (no pairing needed, since the auto-connector already dials unpaired peers). Screen-off battery per hour with 0, 1, 4 and 8 idle sessions, plus 19 peers (8 held + 11 refused by the cap: today's churn). CPU time, Wi-Fi traffic and drain from `dumpsys batterystats`; **not wake-ups**, since the engine's partial wake lock keeps the CPU awake throughout. Reconnect after a Wi-Fi toggle: time until everyone is back, and handshake count. Procedure and tools: [`PC0-RUNBOOK.md`](PC0-RUNBOOK.md) (`:core:engine:peerFarm`, `tools/pc0/phone-baseline.ps1`). Logged in `logs/experiments.md`. | Baseline numbers. They say how much connection count actually costs, and therefore how aggressive ECO must be. |
| **PC1 Keepalive efficiency** | §3.3: shared ticker, traffic counts as proof, one pinger per pair, on both the Android and JVM `WsConnection`. | Wake-ups drop measurably against PC0. `WsKeepaliveTest` is green. A 1-hour screen-off test shows no flap. |
| **PC2 Connection planner** | §3.6 plus the deterministic dialer and staggered storms (§3.5). Behaviour otherwise matches today's STANDARD. ADR-045 (modes own the connection policy). | All three hosts use the planner. Unit tests cover every §3.4 rule. Device check: nothing regresses. |
| **PC3 Three states, local only** | Connected / Online / Offline, where Online comes from discovery sightings without a session (no sharing yet). UI component doc first, then the dot visuals. | The states render on Android and desktop. Sends to an Online peer dial on demand and deliver. |
| **PC4 Presence sharing** | §3.2: `FLASH_PRES`, the salted-hash mutual-contact rule, group rosters, Ghost `noShare`, age/TTL/hops, endpoint tips that trigger dials. Documented in `docs/protocol.md`. ADR-046. First verify that old clients ignore an unknown frame prefix. | Tests: a Ghost device is never leaked; a non-mutual contact is never leaked; stale reports expire; a forged tip only causes a failed dial. 3-device check: user 3 sees user 2 as Online through user 1. |
| **PC5 Mode-driven policy** | §3.4 for ECO / STANDARD / BOOST, the mixed-mode rules, **Boost added to the Quick Settings tile**, the desktop setting. | Owner device check in each mode. ECO holds ≤ 3 + active sessions. |
| **PC6 Scale and battery measurement** (= ADR-044 V3) (**POSTPONED 2026-09-29, P9, FO-05**) | 20 peers (farm plus phones) in each mode: battery per hour, reconnect storm, delivery latency to an ECO phone with the screen off. | Numbers replace every *(measure)*. ECO's ~1 min bound is met. Decides whether groups can go to 32. |
| **PC7 Tune and document** | Final numbers in `DiscoveryModePolicy`/`FlashTransportProfile`, the default mode, platform notes, and the handoff. | Owner sign-off. |

## 5. Risks

- **Re-breaking the screen-off fixes** (ERROR-025/026). Mitigation: §2's invariants are acceptance criteria, and
  PC1 and PC5 each end with a 1-hour screen-off device test.
- **ECO closing a session in the middle of use.** Mitigation: activity, calls and transfers pin a session; idle
  close only after 10 min of silence, and only for non-neighbours.
- **Lying reporters.** Mitigation: reports never grant trust and direct observation wins (§3.2).
- **Mixed app versions.** `FLASH_PRES` must be ignored by old clients (verified in PC4 before shipping). The
  one-pinger rule degrades to both-ping, which is today's behaviour.

## 6. Owner decisions (2026-09-24)

| # | Question | Decision |
|---|---|---|
| P1 | Goal | **All three:** battery, fast and accurate reconnection, scale. |
| P2 | How the trade-off is chosen | **Extend the existing discovery modes:** ECO = battery, STANDARD = balanced, BOOST = instant (battery disregarded). |
| P3 | Presence states | **Three:** Connected / Online / Offline. |
| P4 | ECO lateness with the screen off | **Up to ~1 minute.** The wake and Wi-Fi locks are kept, so the screen-off fix stays intact. |
| P5 | Ghost devices in shared presence | **Never shared.** |
| P6 | Whose presence is passed on | **Mutual contacts only** (salted-hash matching; group rosters within groups). |
| P7 | Order | **Its own phase set, before group calling.** |
| P8 | When to test (2026-09-28) | **Last:** implement PC1–PC5, then group calling, then run PC0/PC6 and every device check. |
| P9 | PC6 / MEAS-02 (2026-09-29) | **Postponed to future optimization** (FO-05). It no longer gates PC7 or anything else. Consequences: the group size target is 20 (ADR-044 V2), **32 stays parked** because ADR-044 makes it depend on this measurement; the session cap and ECO's ~1 min bound are designed by reasoning and labelled estimates. ADR-056. |

## 7. Implementation log

### PC1 — code done 2026-09-28 (device check pending, P8)
- `WsKeepaliveTicker` (commonMain): one aligned clock per network; parks with no timer when there are no sessions.
- `WsKeepalive`: `PingRole` (BOTH / PINGER / ANSWERER) from both HELLOs (`resolveRole`: shorter interval pings,
  tie → smaller id); `Verdict.Quiet` when frames went both ways in the last half interval; an answerer probes only
  after half its liveness window of silence. Stall and close rules unchanged.
- HELLO carries `ping=<ms>` (optional, backward compatible; `docs/protocol.md`). Both `WsConnection` copies, both
  clients/servers and both networks (`WsFlashNetwork`, `JvmWsFlashNetwork`).
- Tests: `WsKeepalivePingRoleTest` (13, incl. 1-hour pair simulations: healthy pair, mixed tiers, dead pinger,
  disagreeing roles, older peer), `WsKeepaliveTickerTest` (5), loopback role test in
  `JvmWsFlashNetworkLoopbackTest`. The 15 existing `WsKeepaliveTest` cases pass unchanged.
- Expected effect (unmeasured): idle pings per pair halve (360 → 180/h per side on average), and wake-ups per
  interval drop from one per session to one per device.
- **Pending device checks:** 1-hour screen-off with sessions, no flap (§5 risk); a mixed old/new-version pair.

### PC2 — code done 2026-09-28 (device check pending, P8). ADR-045
- `ConnectionPlanner` (commonMain, pure): the old gate rules (self, live-session clear, reconnect-in-flight, 15 s
  suppression, one in flight), plus the **deterministic first dialer**: the lower id dials at once, and the higher id
  waits 1.5 s per no-session episode. §3.5 said "the backup-loop floor"; that is shortened because pairing waits only
  3 s for a HELLO and one-sided discovery can leave the higher id as the only dialer. Gateway probes are keyed
  `gateway:<host>` and never deferred. State is lock-free (compare-and-set), since core:network has no expect/actual.
- `AutoConnector` drives it on all three hosts: 5 s tick, discovery edges, `sweepNow()`, deferred rechecks,
  call-quiet (app). The three sweep copies and both `AutoConnectGate`s are deleted.
- **Staggered storms** (`ReconnectStagger`, both networks): after at least 4 unexpected drops within 3 s, each
  reconnect loop's first attempt for the next 30 s, including the Wi-Fi-rejoin immediate redial, is delayed by
  `hash(local|peer) mod 2 s`. A per-pair hash instead of §3.5's `hash(deviceId)`, so one phone's own loops spread too.
- STANDARD behaviour otherwise unchanged. The desktop gained the suppression window and the ERROR-031 freshness
  check. The §3.4 ECO/BOOST rules and the **session cap per mode** move to PC5 (ADR-045 item 6), so PC2's "tests
  cover every §3.4 rule" exit criterion is met only for STANDARD.
- Tests: `ConnectionPlannerTest` 18, `AutoConnectorTest` 6 (virtual time), `ReconnectStaggerTest` 5,
  `ConnectionPlannerConcurrencyTest` (JVM threads). `DesktopEngineAutoDialTest` now shows a single dial between two
  engines instead of crossing dials.
- **Pending device checks:** pairing straight after discovery, the hotspot gateway probe, call-quiet, and a Wi-Fi
  toggle with several peers (storm stagger visible in the `Auto-connect`/reconnect timestamps).

### PC3 — code done 2026-09-28 (device check pending, P8)
- UI doc first: `docs/ui/chat-screen.md` **UI-030b** (DESIGNED → IMPLEMENTED). Solid dot = Connected, ring = Online,
  none = Offline, and the labels follow the owner's names. `FlashPeerPresence` gains `Reachable` (= the plan's
  "Online"); `Online` keeps meaning a live session, because the enum is published API and a meaning swap would
  compile silently at missed call sites.
- `RealFlashChatRepository(reachablePeerIds = …)`: discovery's current ids. `PresenceSnapshot.reachable` =
  seen − live − Connecting grace. It is used by the chat list and the direct header; groups are unchanged until PC4.
- `FlashPresenceDot` is shared by the list, header and peer-details sheet. `FlashConnectionHealth.Reachable` shows a
  calm banner, "Online · connects when you send". The desktop header resolver no longer calls a discovered-only
  peer Online/Lan.
- **Dial on demand:** `ConnectionPlanner.planUrgent` (rule 7: skips the first-contact wait and the 15 s window; keeps
  in-flight, reconnect-engine and a 5 s floor) and `AutoConnector.ensureSession(peer, 1 s)`. It is called by all
  three hosts' direct-message sinks for `TextMessage` frames only. Callers share one wait deadline per peer, so an
  advertised-but-unreachable peer (ERROR-073) costs an outbox pass at most 1 s.
- **Not covered yet:** group sends, transfers and calls still need an existing session. Presence is local only
  (sharing is PC4).
- Tests: `PresenceHoldTest` (reachable join), `FlashPresenceDotLogicTest`, `FlashNetworkStatusLogicTest` and
  `FlashNetworkSimLogicTest` (new state), `ConnectionPlannerTest` +4, `AutoConnectorTest` +3, and the desktop header
  tests updated to the new meaning.
- **Pending device checks:** a ring appears for a discovered peer before its session lands; a message sent to a ring
  peer delivers; TalkBack reads "Connected"/"Online".

### PC4 — code done 2026-09-28 (device check pending, P8)
- **Old clients checked first.** The three inbound routers (app holder, `Flash.create`, desktop) match the first token
  exactly (`FlashTextFraming.parseFields`, no `startsWith` on frame prefixes anywhere) and log-and-drop an unknown
  frame. `FLASH_PRES` collides with no existing prefix.
- `core/network/.../presence/`: `PresenceCodec` (four frames, strict validation), `PresenceState` (every rule,
  deterministic) and `PresenceExchange` (one coroutine per host). Wire format in `docs/protocol.md`
  "Presence sharing"; decisions in **ADR-046**.
- **Changes from §3.2 as written:** `noShare` travels in a presence `hello` (share=0), not in `FLASH_WS_HELLO`, with
  **default deny** (a device is reported only after its own hello said share=1), so the session handshake is untouched
  and old clients are never reported. Endpoint tips are dialed only for subjects with a **pinned** key, and with the
  subject named, because a named dial to an unpinned id would let trust on first use record a forged endpoint's key.
- Hosts: the router hands `FLASH_PRES` to the exchange right after calls; `reachablePeerIds` = discovery ids plus
  reported ids; the planner's sightings include tips, and a tip's dial result is reported back; screen-on / manual
  retry also refresh presence. `RealFlashChatRepository.activeGroupRosters()` supplies the rosters.
- Tests: `PresenceCodecTest` 10, `PresenceStateTest` 20 (a simulated mesh: Ghost never leaked, non-mutual never
  leaked, old client never reported, group fellows, stale expiry, refresh, delta withdrawal, 2-hop limit, split
  horizon, forged tip only for pinned peers and silenced after 3 failures, rate limit, new-session salt),
  `PresenceExchangeTest` 5 (three hosts on virtual time). Full suites: network 157 JVM / 255 host, messaging 193,
  engine 4 + 9, desktop 82, app 39.
- **Not covered:** group headers still count live sessions only; ECO/BOOST refresh and max age are PC5
  (`PresenceConfig`).
- **Pending device check:** user 3 sees user 2 as Online through user 1 (three phones, user 3 unable to discover
  user 2, for example a hotspot client); Ghost on user 2 removes the ring on user 3 within about 1 s; a message to the
  ringed peer delivers through the tip dial.

### PC5 — code done 2026-09-28 (device check pending, P8). ADR-048
- `core/network/.../mode/`: `ConnectionModePolicy` (the §3.4 knob table from mode + tier; STANDARD returns the tier's
  profile unchanged), `EcoLinkSelector` (ring neighbours, wanted set, park candidates), `LinkCodec` (`FLASH_LINK`)
  and `ConnectionModeController` (one coroutine per host: dial filter, re-time on change, park handshake).
- **Mode switches re-time live sessions** (`WsConnection.retime`, `WsFlashNetwork.retimeConnections`, both
  networks) instead of reconnecting. The new window starts half used, never full: a simulation over 125 mode/tier
  pairs showed that a switch from ECO to BOOST otherwise closed healthy sessions immediately.
- **Mixed modes** as §3.4 says: ECO closes an idle session it dialed only after the peer answers `park-ok`, and the
  peer stops redialing before answering; STANDARD, BOOST and old clients never agree. ECO's planner dials only its
  wanted set (`ConnectionPlanner.plan(allowed)`); dial on demand is unaffected.
- **Presence per mode:** `PresenceConfig.ECO/BOOST`, the hello's `r=` refresh, per-reporter hold and a relay
  allowance (also fixes a hop-2 flicker in pure STANDARD), rate refill 250 ms.
- `hasLiveSession` staleness is max(45 s, liveness + 5 s). User traffic (not keepalive/presence/link frames) is
  tracked per connection for ECO's 10-minute rules (`linkActivity()`).
- Hosts: the app holder (tier from Settings; Nearby visibility from `nearbyScreenContent`), `Flash.create` (HIGH
  tier; busy peer through a calling-safe lambda on `DefaultFlashEngine`) and `DesktopEngine` (settings tier; Nearby
  from the shell). **Boost added to the Quick Settings tile** (Standard → Ghost → Eco → Boost → Off). The desktop mode
  menu already cycled through Boost.
- Tests: `ConnectionModePolicyTest`, `EcoLinkSelectorTest` (group connectivity for 2–24 peers),
  `ConnectionModeControllerTest` (two hosts on virtual time), `WsKeepalivePingRoleTest` (mode switches never close a
  healthy pair; a dead one is still reaped), presence mixed-mode tests (27 mode mixes, no flicker). Full suites:
  network 190 JVM / 288 host, messaging 193, engine 4 + 9, desktop 82, app 39.
- **Not covered:** a drop that was not a park still redials an unwanted ECO peer until the next park cycle (ADR-048);
  the session cap stays 8 for every mode until PC6. *(Superseded 2026-09-29: ADR-057 sets it to 24 by reasoning; PC6 is
  postponed, FO-05.)*
- **Pending device check:** each mode on two phones and the desktop; ECO holds ≤ 3 + active sessions after 10 min
  idle; switching modes with a call or transfer running drops nothing; a 1-hour screen-off test per §5.

### Session ceiling — code done 2026-09-29 (device check pending, SC-01/SC-02). ADR-057
- `SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS` **8 → 24**, one number for every mode and tier. Reasoning:
  a 20-member group needs 19 sessions, plus a call or transfer peer outside it, a pairing peer and a duplicate being
  replaced. ECO must never refuse an incoming session (ADR-048), so a per-mode admission ceiling would either break that
  or be the same number; a LOW-tier ceiling below the group's need would cause refuse-and-retry churn every 5 s, which
  costs more than the sessions. Each live session pins one `Dispatchers.IO` read thread (pool max(64, cores), shared
  app-wide), so 24 leaves room. **All of this is an estimate, not a measurement (FO-05).**
- **What a mode changes is how many sessions it asks for.** ECO: ring neighbours + active + call (unchanged). STANDARD and
  BOOST: everyone while the devices around fit `DIAL_BUDGET` (20 = ceiling − `DIAL_HEADROOM` 4), so nothing changes in a
  normal room. Above it `DialBudget` keeps held sessions and spends the free slots on busy peers, then contacts (paired
  and group members), then strangers, in device-id order. While Nearby is open the budget is the full ceiling.
- **Known gap (kept on purpose):** strangers that dial *in* are still admitted first-come up to the ceiling, so 24
  strangers dialing one phone can still fill it. The headroom only protects against this device's own dial choices. A
  priority admission (contacts displace strangers) needs the network layer to know contacts; not built.
- Code `e9d1563`. Tests: `DialBudgetTest` (11), `ConnectionModeControllerTest` (+3: crowd, ECO/STANDARD guard, filter log),
  `ConnectionModePolicyTest` (+1), `SessionHardeningPolicyTest` (+1, default 24). Full `core:network` suites: 281 JVM /
  373 host; `:desktop:compileKotlinJvm` green.

### PC7 — platform note written 2026-09-28; tuning no longer waits for PC6 (P9, 2026-09-29)
- `docs/android-platform-notes.md`: OEM freezers (ERROR-074) ignore the foreground service and the battery whitelist,
  and what the user has to change. The final numbers and the default mode need PC6's measurements, which are postponed (FO-05): until then they stay
  labelled estimates.
