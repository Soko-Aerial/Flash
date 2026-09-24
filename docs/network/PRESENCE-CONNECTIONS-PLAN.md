# Presence & Connections Plan (phases PC0–PC7)

**Status: PLAN, owner decisions recorded 2026-09-24. Nothing implemented.**
Numbers marked *(measure)* are estimates until PC0/PC6 replace them.

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
| **PC0 Measure the baseline** | Belfone, a mid-range phone, a Pixel and the desktop, plus a **peer farm** of N headless JVM Flash instances on the PC (no pairing needed, since the auto-connector already dials unpaired peers). Screen-off battery per hour with 1, 4, 12 and 19 idle sessions. Wake-ups (`dumpsys batterystats`). Reconnect after a Wi-Fi toggle: time until everyone is back, and handshake count. Logged in `logs/experiments.md`. | Baseline numbers. They say how much connection count actually costs, and therefore how aggressive ECO must be. |
| **PC1 Keepalive efficiency** | §3.3: shared ticker, traffic counts as proof, one pinger per pair, on both the Android and JVM `WsConnection`. | Wake-ups drop measurably against PC0. `WsKeepaliveTest` is green. A 1-hour screen-off test shows no flap. |
| **PC2 Connection planner** | §3.6 plus the deterministic dialer and staggered storms (§3.5). Behaviour otherwise matches today's STANDARD. ADR-045 (modes own the connection policy). | All three hosts use the planner. Unit tests cover every §3.4 rule. Device check: nothing regresses. |
| **PC3 Three states, local only** | Connected / Online / Offline, where Online comes from discovery sightings without a session (no sharing yet). UI component doc first, then the dot visuals. | The states render on Android and desktop. Sends to an Online peer dial on demand and deliver. |
| **PC4 Presence sharing** | §3.2: `FLASH_PRES`, the salted-hash mutual-contact rule, group rosters, Ghost `noShare`, age/TTL/hops, endpoint tips that trigger dials. Documented in `docs/protocol.md`. ADR-046. First verify that old clients ignore an unknown frame prefix. | Tests: a Ghost device is never leaked; a non-mutual contact is never leaked; stale reports expire; a forged tip only causes a failed dial. 3-device check: user 3 sees user 2 as Online through user 1. |
| **PC5 Mode-driven policy** | §3.4 for ECO / STANDARD / BOOST, the mixed-mode rules, **Boost added to the Quick Settings tile**, the desktop setting. | Owner device check in each mode. ECO holds ≤ 3 + active sessions. |
| **PC6 Scale and battery measurement** (= ADR-044 V3) | 20 peers (farm plus phones) in each mode: battery per hour, reconnect storm, delivery latency to an ECO phone with the screen off. | Numbers replace every *(measure)*. ECO's ~1 min bound is met. Decides whether groups can go to 32. |
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
