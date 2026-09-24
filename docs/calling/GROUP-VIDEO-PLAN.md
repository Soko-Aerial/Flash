# Group calls: request-based video, tier and band budgets, size caps

**Status: PLAN, owner decisions recorded 2026-09-24. Nothing implemented.** Written 2026-09-23 from the owner's
requirements and a code review of `core/calling` and `ui/callui`. Numbers marked *(measure)* are estimates until
phase G0 replaces them.

## 1. What the owner asked for (restated)

| # | Requirement |
|---|---|
| R1 | **No sender blasts video to everyone.** A device sends its video to a peer only after that peer asks for it. |
| R2 | Tapping a participant's tile **focuses** it. That sends a request to that one user, who then starts sending to the requester only. Unfocusing stops it. |
| R3 | **LOW devices:** one large video, with the call's other participants (avatars, who is speaking) in a strip under it. |
| R4 | Receive limits: **LOW 1 video, MEDIUM 2, HIGH up to 5.** |
| R5 | The resolution depends on the network. **5 GHz or Ethernet:** MEDIUM 2 × 720p. **2.4 GHz:** MEDIUM 2 × 540p, LOW 1 × 540p. ("520p" is taken to mean the standard 540p, 960×540.) |
| R6 | There is also a **send limit** per tier: how many copies of its own video a device will encode at once. The owner asked what LOW's should be. |
| R7 | **Warn the user** when the phone decodes in software, CPU use climbs or the phone heats up, and suggest receiving fewer videos to save battery. |
| R8 | Size caps: **video calls up to 8 people, voice calls around 15, chat groups 20+**. The owner asked for an opinion. |

## 2. Current state (verified in code, 2026-09-23)

- The mesh, glare rule, ICE queue, wait-when-alone timer, VP8-only, frame authentication and speaking detection all
  exist in `FlashGroupCallSession` (see the review in the 2026-09-23 progress entry).
- **Video today is wasteful:** every connection sends video to every peer (one encoder per connection), but the
  session keeps a single `_remoteVideoStreamTrack` (the last one wins, `FlashGroupCallSession.kt:526`). The group grid
  (`FlashGroupParticipantsGrid`) shows initials and a speaking halo, never video. **So up to 5 videos are encoded and
  up to 5 decoded, and at most one could ever be seen.**
- `tuneVideoSender` gives *every* connection the full tier bitrate. Nothing divides it by the number of connections.
- `GroupPolicy.MAX_MEMBERS = 6` (ADR-030, owner-locked "text-first"; ADR-030 says to revisit when demand exceeds 6).
- `AndroidThermalGovernor` exists (thermal status listener). `AndroidDeviceProfile` only checks whether the radio
  *supports* 5 GHz, not which band the phone is connected on now. Nothing detects band or Ethernet on desktop.

## 3. Feasibility, requirement by requirement

| # | Verdict | How | Risk |
|---|---|---|---|
| R1/R2 | **Feasible, and the right design.** | Every connection is still negotiated with a video track, but its encoding starts **inactive** (`RtpEncodingParameters.active = false`, through the existing `RtpSenderTuning` layer). A request switches that one connection's encoding on, with no renegotiation. libwebrtc sends a keyframe when an encoding turns on, so a new view appears in about 0.3–1 s *(measure)*. | Desktop: confirm that webrtc-java 0.17 respects `active` in `setParameters`. Fallback: `replaceTrack(null)` / `replaceTrack(track)`. |
| R3 | **Feasible.** Pure UI in `:ui:callui`, plus per-participant tracks (G1). | | |
| R4 | **Feasible** once each participant has its own track. | | HIGH on 2.4 GHz: see the math below. |
| R5 | **Feasible on Android and desktop.** | Android: `NetworkCapabilities` (`TRANSPORT_ETHERNET`); for Wi-Fi, `WifiInfo.getFrequency()` from `getTransportInfo()` (API 31+) or `WifiManager.getConnectionInfo()` (older). Frequency needs no location permission *(verify on an API 31+ device)*. Desktop: Windows Native Wifi `WlanQueryInterface` through JNA (already a dependency), or parse `netsh wlan show interfaces`. | **A phone that is hosting the hotspot can't read its own AP band** (`SoftApCallback` is a system API). Rule: a link's band is the *worse* of the two ends' reports. A hotspot host reports "unknown", and the other end's report decides. |
| R6 | **Feasible and essential.** | The send cap is the real limit of a mesh: a popular speaker gets a request from every participant. | See the policy in §4.3. |
| R7 | **Feasible.** | Thermal: the existing `AndroidThermalGovernor` (API 29+), and battery temperature from `ACTION_BATTERY_CHANGED` below that. CPU: own-process CPU time deltas (`Process.getElapsedCpuTime()`). Software decode: the WebRTC inbound stats field `decoderImplementation` (e.g. `libvpx` = software) *(verify the field on M125 Android and webrtc-java)*. | Thresholds must come from G0 measurements, or the warning will cry wolf. |
| R8 | See §5. | | |

## 4. Design

### 4.1 Request protocol (new call frames, sent only to the peer involved)

| Frame | From → to | Meaning |
|---|---|---|
| `VideoRequest(callId, quality)` | receiver → sender | "Send me your video at up to `quality`" (tier and band decide the value). |
| `VideoGrant(callId, quality)` | sender → receiver | Accepted. The sender switches on that connection's encoding at `quality`, which may be lower than asked. |
| `VideoDeny(callId, reason)` | sender → receiver | `CAMERA_OFF`, `SENDER_AT_CAPACITY`, `THERMAL`. The receiver's tile shows the reason. |
| `VideoRelease(callId)` | receiver → sender | Stop sending to me. The sender switches the encoding off. |

- Frames are checked against the connection they arrived on, like every call frame today.
- Leaving the call releases everything, and so does a connection closing.
- A request is **automatic, not a prompt to a person.** The sender's camera on/off switch is the only consent
  control; there is no per-person block (owner decision, Q7).
- Requests are idempotent and carry a sequence number, so a lost or late grant can't switch on a stream after its release.

### 4.2 Budgets

Approximate VP8 bitrates *(measure)*: 720p30 ≈ 1.5–2.5 Mbps, 540p ≈ 0.8–1.2 Mbps, 360p ≈ 0.4–0.6 Mbps.

**Receive** (live video tiles at once):

| Tier | 5 GHz / Ethernet | 2.4 GHz | Notes |
|---|---|---|---|
| LOW | 1 × 540p | 1 × 540p | **Owner decision (Q4): drops to 360p when the device struggles.** Trigger: thermal MODERATE or worse, sustained CPU over the G0 threshold, or frame drops over the G0 threshold. The request is re-sent at 360p; no user action is needed. |
| MEDIUM | 2 × 720p (owner) | 2 × 540p (owner) | 2 × 720p *software* decode on a mid-range phone is heavy. G0 decides. |
| HIGH | 5 × 720p | **3 × 540p (owner decision, Q3)** | Why: 8 people on one 2.4 GHz channel each receiving 5 × 540p is about 40 streams × 1 Mbps = 40 Mbps. Through a hotspot every stream crosses the air twice, about 80 Mbps. A real 2.4 GHz channel delivers roughly 20–50 Mbps, so audio would starve. |

**Send** (copies of its own video a device will encode at once). **Owner decision (Q8, 2026-09-24): a split budget,
not a fixed count.** Each tier has a budget measured in 540p copies. A 360p copy costs about half a 540p copy
(0.23 vs 0.52 megapixels, about 0.5 vs 1 Mbps), so a sender can serve a few watchers at 540p or twice as many at 360p.
360p is the floor; a sender never goes lower to fit another watcher.

| Tier | 5 GHz / Ethernet | 2.4 GHz budget | 2.4 GHz serves |
|---|---|---|---|
| LOW | 1 copy | 1 × 540p | 1 at 540p, or 2 at 360p |
| MEDIUM | 2 copies | 2 × 540p | 2 at 540p, or up to 4 at 360p |
| HIGH | 4 copies (5 on 5 GHz or Ethernet) | 3 × 540p | 3 at 540p, or up to 6 at 360p (an 8-person call: 6 of 7 see the talker) |

Why a budget: because the main tile follows the talker (Q1), everyone requests the same person at once. Halving the
counts (LOW 1, MED 1, HIGH 2; the rejected option) would leave most viewers on "Video busy" for the one video they
all want. The budget keeps airtime and encoder load at the halved level while serving about twice as many people.

Rules:
- When a new watcher doesn't fit at the current resolution, the sender steps **all** its copies down to 360p (one
  resolution per sender keeps it simple) and grants. When watchers leave and 540p fits again, it steps back up
  after 5 s of stability, so it doesn't flap.
- A request still carries the requester's own preferred resolution (§4.2 receive). A copy is sent at the lower of
  what's requested and what the budget allows.
- If the budget is full even at 360p, the §4.3 rules apply.
- *(measure, G0)*: whether a second or third encoder instance falls back to software on the Belfone. If it does, a
  copy costs more than its pixels suggest, and LOW's budget becomes 1 copy at any resolution.
- 5 GHz and Ethernet keep plain counts. The budget applies only on 2.4 GHz, where airtime is the constraint.

### 4.3 When a sender is full

1. **The active speaker comes first (owner decision, Q5).** If the sender is speaking and a new request arrives, it
   drops its least-recently-requested *non-focused* copy.
2. Otherwise `VideoDeny(SENDER_AT_CAPACITY)`. The requester's tile shows "Video busy" and retries automatically when
   the sender announces free capacity (a capacity field in `GroupPresence`).

This is the inherent mesh ceiling. Only a relay peer (a mini media server, option D in the review) removes it, and
that stays out of scope until measurements demand it.

### 4.4 Focus and layout

- **LOW:** one main tile, plus a horizontal strip of every participant (avatar, name, speaking halo, mute icon).
  Tapping a strip tile releases the current video and requests the tapped one.
- **MEDIUM/HIGH:** a grid. Tapping a tile requests its video. At the receive limit, the least-recently-focused
  video is released first.
- **Before anyone taps (owner decision, Q1):** the main tile **follows the active speaker**, with a 2 s delay before
  switching so it doesn't flicker, and still does so by *requesting*. Nothing is ever sent unasked. A tap pins the
  focus; tapping the pinned tile again returns to following the speaker.

### 4.5 Health warnings (R7)

| Signal | Source | Action |
|---|---|---|
| Thermal status MODERATE | `AndroidThermalGovernor` | Banner: "Your phone is warming up. Showing fewer videos saves battery." with a **Show fewer** button. |
| Thermal status SEVERE or worse | same | Automatically drop to 1 received video, **and say so**. Deny new requests with reason `THERMAL`. |
| Software decoding while receiving ≥ 2 videos | `decoderImplementation` | The same banner, once per call. |
| Own-process CPU above X % for 30 s *(X from G0)* | CPU time deltas | The same banner. |

Desktop gets only the CPU and software-decode signals; there is no thermal API.

## 5. Size caps (owner's numbers vs recommendation)

| Cap | Owner | Recommendation | Reasoning |
|---|---|---|---|
| **Video call** | 8 | **8, agreed.** | Workable *only because* of the receive and send limits above. Each device still holds 7 connections (7 audio encoders plus DTLS/ICE). |
| **Voice call** | 15 | **12 at launch (owner decision, Q6); 15 only after G0 and a 12-person Belfone test pass.** | In a mesh each device runs 14 connections and 14 Opus encoders. Silence suppression (DTX) keeps airtime low for people who aren't talking. libwebrtc mixes only the few loudest speakers anyway (a mixer limit of 3; *verify*). The cost is CPU and memory per connection on LOW phones, not the network. |
| **Chat group** | 20+ | **Owner decision (Q9, 2026-09-24): 20, with vouched introductions (ADR-044). 32 only after the reconnect-storm measurement.** | Pairing, not bandwidth, is the blocker: ADR-030 drops every group frame from a peer the receiver hasn't paired with, and an `Add` is dropped if any new member is unpaired with the receiver. A full 20-person group would need 190 pairings. ADR-044 lets the member list vouch for identities instead. Traffic: each message goes to N−1 peers, one delivery row per member, fine on a LAN. The real remaining cost is every phone keeping up to 19 connections alive. |

**The call caps depend on ADR-044.** A call leg is also trust-gated (`CallCoordinator.isTrustedPeer`), so an 8-person
video or 12-person voice call only forms if everyone has paired with everyone (28 or 66 pairings). ADR-044's vouched
identities must also admit call legs between members of the same group; otherwise G7's caps are theoretical.

## 6. Phases

| Phase | Work | Exit criteria |
|---|---|---|
| **G0 Measure** | Belfone, a mid-range phone and the desktop. **Video:** 3-way call; CPU, temperature and dropped frames; reported decoder instances (`getMaxSupportedInstances`); `decoderImplementation`. **Voice:** 4 / 8 / 12 connections. **Network:** 2.4 GHz vs 5 GHz throughput. Logged in `logs/experiments.md`. | Numbers replace every *(measure)* in this doc. The owner confirms the budget tables. |
| **G1 One video per participant** | Session keeps a video track per participant; the grid renders them. Sending behaviour unchanged. | 3 devices each see both other videos. |
| **G2 Network band** | Detect Wi-Fi band and Ethernet on Android and desktop; exchange it in `GroupJoin`/`GroupPresence`. | The band shows in the debug stats on each platform, and a hotspot host reports "unknown". |
| **G3 Request protocol** | The 4 frames; encodings start inactive; request, grant, deny and release; send cap; release on leave; sequence numbers. JVM 3-way loopback tests. | With nobody focused, no video is sent (check the stats). A tap delivers video in ≤ 1 s. The send cap is enforced. |
| **G4 Budgets** | The tier × band tables; per-connection tuning replaces the full-bitrate-everywhere behaviour. | Measured bitrates match the table. |
| **G5 UI** | LOW main tile plus strip; MEDIUM/HIGH grid; "Video busy" and "Camera off" states. | Owner device check. |
| **G6 Health warnings** | Banner, "Show fewer", automatic step-down at SEVERE. | The warning fires on a forced-hot Belfone and not in a normal call. |
| **G7 Caps** | Video 8, voice 12 (15 later, only after measurement). Depends on ADR-044 for groups of unpaired members. | Enforced by the invite and join paths, with a clear message when full. |
| **GV Vouched groups (ADR-044)** | A separate track, sequenced by ADR-044's own phases (threat review first). Not part of G0–G7, but G7's call caps are only reachable in practice once it lands. | See ADR-044. |

## 7. Owner decisions (2026-09-24)

| # | Question | Decision |
|---|---|---|
| Q1 | What the main tile shows before any tap | **Follows the active speaker** (still by request). |
| Q2 | "520p" | **540p** (960×540). |
| Q3 | HIGH receive limit on 2.4 GHz | **3 videos.** |
| Q4 | LOW resolution | **540p, dropping to 360p when the device struggles.** |
| Q5 | Sender at capacity | **Talker first.** |
| Q6 | Voice-call cap | **Start at 12.** |
| Q7 | Per-person video refusal | **No. The camera on/off switch is enough.** |
| Q8 | Send limit on 2.4 GHz | **A split budget (§4.2):** LOW 1 × 540p, MEDIUM 2 × 540p, HIGH 3 × 540p, each spendable as twice as many 360p copies. Halving the counts was rejected because it leaves most viewers on "Video busy" when everyone follows the talker. |
| Q9 | Chat-group size | **20, with vouched introductions (ADR-044); 32 after measurement.** Keeping mandatory pairwise pairing at 20 was rejected (190 pairings); staying at 6 was rejected. |

No open questions remain. Next: G0 measurements, and ADR-044's threat review.
