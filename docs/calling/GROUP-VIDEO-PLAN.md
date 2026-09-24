# Group calls: request-based video, tier and band budgets, size caps

**Status: PLAN, owner decisions recorded 2026-09-24. Nothing implemented.** Written 2026-09-23 from the owner's
requirements and a code review of `core/calling` and `ui/callui`.

**Order (owner, 2026-09-24):** `docs/network/PRESENCE-CONNECTIONS-PLAN.md` (PC0–PC7) comes **before** G1+. G0 may
share PC0's measurement rig. Numbers marked *(measure)* are estimates until
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

### 4.6 Compression and codecs (owner suggestion, assessed 2026-09-24)

The owner asked whether adding compression to audio or video would help. Assessment: **a separate compression
layer does not; a more efficient codec per connection might, and G0 will measure it (C1–C3).**

**What is already compressed** (`FlashVoiceProfile`, `FlashVideoProfile`, `CallSdp`):

| | Raw | Sent today | Ratio |
|---|---|---|---|
| Voice (Opus) | 768 kbps (48 kHz, 16-bit mono PCM) | 20 / 24 / 32 kbps (LOW / MEDIUM / HIGH), DTX on, in-band FEC | about 30–40× |
| Video (VP8, 540p at 24 fps) | about 149 Mbps (960 × 540 × 1.5 bytes × 24 frames) | at most 900 kbps (MEDIUM profile) | about 165× |

**Rejected: a general compressor (zip / zstd / deflate) on media.**
- Opus ends in a range coder and VP8 in a boolean entropy coder. Their output is already close to random, so a
  second compressor gains about 0% and can grow the data.
- Media leaves the device SRTP-encrypted, and ciphertext does not compress. Compressing before encryption would
  need a frame-transform hook we don't have, and would still gain nothing, for the first reason.
- It adds CPU per packet, which is exactly what LOW devices lack.

**Rejected: squeezing audio further.** In an 8-person call, voice is about 1.3 Mbps worst case across the whole
group (56 streams × 24 kbps, and much less with DTX, since only talkers send). Video is about 40 Mbps (§4.2), so
audio is about 3% of the traffic. Lower Opus bitrates would audibly hurt speech and save almost nothing. The
voice profiles stay as they are.

**Rejected for the mesh: simulcast and SVC.** One encode serving several quality levels only pays off when a
server forwards the layers. In a mesh, every connection already has its own encoder, and request-based video
(§4.1) is the bigger saving.

**Candidate: a better codec, chosen per connection.** Video is forced to VP8 everywhere (`CallSdp.enforceVp8Only`)
because the desktop's `webrtc-java` 0.17.0 is recorded as lacking the other codecs' native libraries. Each mesh leg
is its own PeerConnection, so the codec can differ per leg:

| Leg | Codec | Expected gain | Cost / risk |
|---|---|---|---|
| Phone ↔ phone, both with a hardware H.264 encoder | H.264 | **CPU, heat and battery**, not bandwidth (H.264 is about the size of VP8). If a hardware copy is cheap, LOW's send budget of 1 copy (§4.2) may rise. | Hardware encoder quality and bugs vary by chipset. Needs a fallback to VP8 when an encoder fails. |
| Phone ↔ phone, both HIGH, both with a hardware VP9 encoder | VP9 | Commonly reported as about 30–40% fewer bits than VP8 for the same picture *(reported, not measured here)*. On 2.4 GHz that could lift HIGH's budget from 3 × 540p towards 4. | Software VP9 encoding is much heavier than VP8, so it's never used without hardware. |
| Any leg with the desktop | VP8 | none (today's behaviour) | none |
| Any leg | AV1 | best compression | Encoding is too heavy for phones apart from the newest flagships. **Not pursued.** |

Doubt to settle first: the `CallSdp` comment groups VP9 with H.264 and AV1 as "not bundled" on the desktop. VP9
comes from the same libvpx library as VP8, which does work there, so the claim may be wrong for VP9 (C1).

Rules if it's built (phase G4b):
- **Codec choice per leg from both ends' capabilities**, exchanged in `GroupJoin` / `GroupPresence` alongside the
  band (G2): which hardware encoders and decoders each device has. Any leg that includes the desktop, or where either
  side lacks the hardware, stays VP8. It is never *less* compatible than today.
- **Fallback.** If a hardware encoder fails or its stats report a software implementation, that leg renegotiates to
  VP8. A codec failure must never end the call.
- **Budgets change only by measurement.** A hardware-codec leg gets a bigger §4.2 budget only if C3 shows the
  saving on the BelFone.

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
| **G0 Measure** | Belfone, a mid-range phone and the desktop. **Video:** 3-way call; CPU, temperature and dropped frames; reported decoder instances (`getMaxSupportedInstances`); `decoderImplementation`. **Voice:** 4 / 8 / 12 connections. **Network:** 2.4 GHz vs 5 GHz throughput. **Codecs (§4.6):** C1, does desktop `webrtc-java` 0.17.0 encode and decode VP9 in a desktop ↔ phone call (check `encoderImplementation` / `decoderImplementation` in stats); C2, the BelFone's and the mid-range phone's hardware encoders and decoders (VP8 / VP9 / H.264, from `MediaCodecList`); C3, two phones, 540p for 10 min, VP8 software vs H.264 hardware vs VP9 hardware (where present): CPU, temperature, battery drain, bitrate at matched quality. Logged in `logs/experiments.md`. | Numbers replace every *(measure)* in this doc. The owner confirms the budget tables. C1–C3 decide whether G4b is built. |
| **G1 One video per participant** | Session keeps a video track per participant; the grid renders them. Sending behaviour unchanged. | 3 devices each see both other videos. |
| **G2 Network band** | Detect Wi-Fi band and Ethernet on Android and desktop; exchange it in `GroupJoin`/`GroupPresence`. | The band shows in the debug stats on each platform, and a hotspot host reports "unknown". |
| **G3 Request protocol** | The 4 frames; encodings start inactive; request, grant, deny and release; send cap; release on leave; sequence numbers. JVM 3-way loopback tests. | With nobody focused, no video is sent (check the stats). A tap delivers video in ≤ 1 s. The send cap is enforced. |
| **G4 Budgets** | The tier × band tables; per-connection tuning replaces the full-bitrate-everywhere behaviour. | Measured bitrates match the table. |
| **G4b Codec per connection (conditional)** | **Only if C3 shows a clear CPU, heat or bitrate win on the BelFone.** Exchange hardware-codec capabilities; choose H.264 or VP9 per phone ↔ phone leg (§4.6), VP8 for everything else; fall back to VP8 on encoder failure; raise the §4.2 budgets for hardware legs by the measured amount. If C3 shows no win, record the numbers and skip this phase. | A phone ↔ phone leg negotiates the hardware codec, and a desktop leg in the same call stays VP8. A forced encoder failure falls back without dropping the call. The C3 saving is reproduced in a real 3-way call. |
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

| Q10 | Compression for audio/video (owner suggestion) | **No separate compressor and no further audio squeezing (§4.6: already codec-compressed, encrypted, audio ≈ 3% of traffic). Instead, the codec experiment C1–C3 goes into G0, and per-connection H.264 / VP9 (G4b) is built only if the BelFone numbers justify it.** |

No open questions remain. Next: G0 measurements (now including C1–C3), and ADR-044's threat review.
