# Flash Transfer Protocol

## Version
Protocol version: `1`

## Current LAN Session

The initial LAN milestone does not yet transfer files. It implements a persistent TCP session after NSD discovery so two devices can verify that the advertised address and port are connectable and keep that connection alive.

The probe server prefers TCP port `45821`. If that port is unavailable on the device, it falls back to a dynamic port and advertises the selected port through NSD. The stable preferred port reduces failures from stale mDNS/NSD cache entries pointing at an old random port.

### Client hello

```text
FLASH_HELLO version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

### Server response

```text
FLASH_OK version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

### Disconnect notification

```text
FLASH_DISCONNECT version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

### Heartbeat

```text
FLASH_PING version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
FLASH_PONG version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

After a successful `FLASH_HELLO` / `FLASH_OK` exchange, both phones keep the TCP socket open. Each side sends periodic `FLASH_PING` messages and responds to received pings with `FLASH_PONG`. `FLASH_DISCONNECT` closes the live session and clears connected state on the peer.

**Dead-peer detection (C4.3, 2026-08-23):** ping cadence is driven by `HeartbeatPolicy` — default interval **10 s**, missed threshold **3**, so a silent peer is declared dead after ~30 s and the session is closed with reason `heartbeat timeout` (closing the socket from the tracker coroutine is what unblocks the peer-side blocked `readLine()`; see JDK `Socket.close()` contract). The legacy fixed 3 s cadence remains available via constructor parameter.

### Delivery ACK (C4.8, additive)

Acked frames use a two-line envelope:

```text
FLASH_DATA version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name> frameId=<sender-uuid>
<single-line UTF-8 payload>
```

The receiver immediately echoes an acknowledgment for the envelope's `frameId` (before/after processing the next-line payload) and delivers the payload line to its incoming-frame surface:

```text
FLASH_ACK version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name> frameId=<echoed-uuid>
```

Rules:
- Correlation is by sender-chosen UUID (`frameId`), echoed verbatim.
- Senders wait at most a bounded timeout per frame; on timeout the frame send fails (`ConnectionTimeout`) but the session stays open — liveness is owned solely by the heartbeat tracker.
- Semantics are per-call at-most-once: no automatic retransmission on this transport layer; durable outbox retry (C6) supplies at-least-once with dedup by `frameId`.
- Duplicate `FLASH_ACK` lines for one `frameId` are idempotent no-ops on the sender.
- Frames sent without the envelope (plain lines) keep the pre-C4.8 wire format byte-for-byte; peers that never send `FLASH_DATA` need no changes.

### Escaping
- `%` becomes `%25`
- space becomes `%20`
- `=` becomes `%3D`

## Experimental WebSocket Transfer Track (side track — not the main protocol)

Added 2026-08-20 at owner request (see ADR-007). Independent of the session above; uses minimal RFC 6455 WebSocket framing over TCP, cleartext `ws://` on trusted LAN only.

- Every device runs a WebSocket server (preferred port `45822`, dynamic fallback) and can open any number of client connections, so 3+ devices can fully mesh.
- Upgrade handshake: standard RFC 6455 (`GET /flash-ws`, `Sec-WebSocket-Key` / `Sec-WebSocket-Accept`, version 13); client frames are masked, server frames are not.
- Pairing: both sides send a text frame immediately after upgrade:

```text
FLASH_WS_HELLO version=1 deviceId=<escaped-device-id> name=<escaped-friendly-name>
```

> **Stale section, partly superseded (noted 2026-09-28):** the live stack runs protocol `version=2` over TLS
> (TOFU pin bound to the HELLO id, audit S1) with WebSocket PING/PONG keepalive (ADR-016, ERROR-025/031/033).
> The keepalive subsection below is current; the rest of this section describes the 2026-08-20 original.

#### Keepalive and the `ping` HELLO field (PC1, 2026-09-28)

```text
FLASH_WS_HELLO version=2 deviceId=<id> name=<name> ping=<ping-interval-ms>
```

- `ping` is the sender's idle keepalive interval in milliseconds (its hardware tier: 10000 / 12000 / 15000).
  **Optional**: receivers that predate it ignore unknown fields, and a HELLO without it means "this peer pings on
  its own schedule" — both sides then ping, which is the pre-PC1 behaviour. No version bump.
- **One pinger per pair.** From both HELLOs each side computes the same answer: the side with the **shorter**
  interval pings; on equal intervals the lexicographically smaller `deviceId` pings. The other side (the
  answerer) replies with PONG and sends a PING of its own only when it has heard nothing for half its liveness
  window, so two sides that disagree fall back to both pinging.
- **Traffic counts as proof.** A side skips a scheduled PING when frames went both ways within the last half
  interval. One direction is not enough: the peer's watchdog is fed by what this side sends.
- The watchdog rules (liveness timeout, stall forgiveness, ERROR-025/031) are unchanged; any inbound frame still
  proves the peer alive. Code: `WsKeepalive`, `WsKeepaliveTicker` (one aligned clock per network),
  `WsConnection.applyPeerPingInterval`.

- Peers are keyed by `deviceId`; if a pair holds one connection per direction, the outbound one is primary and the inbound one is fallback.
- File transfer (one active transfer per connection; messages are ordered):

```text
FLASH_FILE_START version=1 transferId=<id> name=<escaped-file-name> size=<bytes-or--1>
<binary frames: raw file bytes, 64 KiB each, in order>
FLASH_FILE_END version=1 transferId=<id> bytes=<bytes-sent>
FLASH_FILE_ACK version=1 transferId=<id> received=<bytes-received> ok=<true|false>
```

- Receiver saves to `filesDir/ws-received/` (deduplicated names) and verifies the byte count before `ok=true`.
- Escaping matches the session protocol (`%25`, `%20`, `%3D`).
- Known limits: no TLS, no trust/verification UX, no resume, no hash verification, no app-level heartbeat (relies on TCP failure surfacing).

## Calling (C7, 2026-09-02)

1:1 voice/video calls ride the WS mesh as text frames under the `FLASH_CALL` prefix,
encoded with the same `FlashTextFraming` field rules as chat/pairing frames. Media itself
travels over WebRTC (SRTP/DTLS, see ADR-025); these frames carry only signaling.

All frames share `callId=<uuid>` (caller-generated) and `from=<escaped-device-id>`.
Conversation identity is implicit: the WS session's peer device id *is* the conversation.

### Call control frames

```text
FLASH_CALL action=invite callId=<uuid> from=<id> video=<true|false> name=<escaped-name>
FLASH_CALL action=accept callId=<uuid> from=<id>
FLASH_CALL action=decline callId=<uuid> from=<id>
FLASH_CALL action=hangup callId=<uuid> from=<id>
```

- `invite`: caller -> callee. `video` declares audio-only vs video intent. Caller enters
  `dialing`; callee enters `ringing` and shows the incoming-call UI/notification.
- `accept`: callee -> caller after the user taps accept. Both sides proceed to SDP.
- `decline`: callee -> caller (user tapped decline or auto-declined a second concurrent
  call). Call ends on both sides.
- `hangup`: either side, any state. Call ends on both sides. Also sent on local teardown
  errors so the peer does not wait on a dead session.

### SDP frames

```text
FLASH_CALL action=offer callId=<uuid> from=<id> sdp=<escaped-sdp>
FLASH_CALL action=answer callId=<uuid> from=<id> sdp=<escaped-sdp>
```

- The caller sends `offer` immediately after `accept` arrives (caller is the offerer;
  glare is impossible because only the caller offers).
- SDP is the full session description string (type is implied by the action). As of
  ERROR-024/ADR-027 the `sdp` field is **base64-encoded** (RFC 4648, no whitespace, no
  `=`/`%`/space characters that collide with the text-framing escape rules), so the
  multi-line, whitespace-sensitive SDP survives the framing layer byte-for-byte.
  `CallFrameCodec.decodeSdp` tries base64 first and falls back to raw escaped text for
  legacy pre-hardening peers (a real SDP starts with `v=0`, which is not valid base64, so
  the fallback is unambiguous in practice). Offers are ~4-8 KB - within text-frame norms.

### ICE frames (trickle)

```text
FLASH_CALL action=ice callId=<uuid> from=<id> mid=<escaped-mid> index=<n> candidate=<escaped-candidate>
```

- Trickled as local candidates appear. Receivers buffer candidates until the remote
  description is set (signaling-state check), then apply - the webrtc-kmp sample pattern.
- `iceServers` is empty on both sides: Flash is LAN/hotspot-only, host candidates connect
  peer-to-peer on-link. No STUN/TURN.

### Ordering and failure rules

- Frames for one call are ordered by the single WS session (TCP); no reordering occurs.
- If the WS session dies mid-call, the call is **held, not failed** — the loss opens a recovery
  window of `FlashTransportProfile.callDisconnectGraceMs` (25 s at LOW; ERROR-033). The window is
  spent working rather than waiting: the caller loops ICE-restart offers until the transport
  underneath comes back, because the device that roamed lost signaling and media at the same
  instant. The host closes the window with `onSignalingRestored` (called whenever a session comes
  up, not only after a loss); if it expires with signaling still down the call ends `DISCONNECTED`
  locally **without** a wire frame (`notifyPeer = false`) — there is nobody left to tell. Ending the
  call the moment signaling drops was the pre-ERROR-033 behavior and made a two-second radio outage
  indistinguishable from a hang-up.
- The same budget governs a dead media path: `Disconnected`/`Failed` arm the ICE-recovery loop, and
  a return to `Connected` cancels it.
- Unknown `action` values are ignored (forward compatibility).
- A device supports at most one active call; a second incoming `invite` while busy is
  auto-declined with `reason` omitted (plain `decline`).

### Group call frames (N participants)

Group calls reuse the `FLASH_CALL` prefix, the same `callId`/`from` fields and the same two host
seams; the frame set is additive, so a peer that predates group calling simply ignores the new
actions (unknown actions decode to null). `groupId` is the conversation, while `callId` is the call
instance — one group can host a second call later under a new `callId`.

```text
FLASH_CALL action=ginvite   callId=<uuid> groupId=<uuid> from=<id> name=<escaped> video=<true|false> members=<id,id,…> [band=<b>] [vr=1]
FLASH_CALL action=gaccept   callId=<uuid> groupId=<uuid> from=<id> [band=<b>] [vr=1]
FLASH_CALL action=gdecline  callId=<uuid> groupId=<uuid> from=<id>
FLASH_CALL action=gfull     callId=<uuid> groupId=<uuid> from=<id> max=<n>
FLASH_CALL action=gjoin     callId=<uuid> groupId=<uuid> from=<id> name=<escaped> [band=<b>] [vr=1]
FLASH_CALL action=ghangup   callId=<uuid> groupId=<uuid> from=<id>
FLASH_CALL action=gpresence callId=<uuid> groupId=<uuid> from=<id> name=<escaped> video=<true|false> count=<n> [band=<b>] [vr=1] [vfree=<n>]
FLASH_CALL action=gquery    callId=<uuid> groupId=<uuid> from=<id>
```

- `ginvite`: initiator -> every invited member. `members` is the comma-separated id list the
  initiator is inviting (omitted when empty); receivers seed their known-member set from it, which
  is what lets a third device mesh with the others without having witnessed the original invite.
- `gaccept`: an invited member -> the initiator. The initiator then opens one leg to that member.
- `gdecline`: an invited member -> the initiator. No leg is opened.
- `gfull` (G7, 2026-09-29, ADR-050): a participant -> a device that just sent `gaccept`/`gjoin` (or was named in a
  relayed `gjoin`) while the call already held `max` people, this participant included (8 in a video call, 12 in a
  voice call). The participant opens no leg to it. The receiver, if the sender is one of its call's participants,
  sends `ghangup` to everyone and ends with reason FULL ("Call is full"). Only a device that is in the call (media
  up) sends it; a ringing device never does. A participant it already counts (a reconnect) is never turned away. An
  old client drops the unknown action and stays in CONNECTING toward the members that turned it away.
- `gjoin`: **broadcast** — a participating member announces that it joined an active call, so every
  other participant can open a leg to it. This is what makes the mesh converge without a central
  mixer. The name is the joiner's display name (defaulted to "Group Member" when absent).
- `ghangup`: a participant -> the others. Closes only that participant's legs; the call continues
  for everyone else.
- `gpresence`: announces a live call for the group. Re-announced every 4 s by every participant
  while the call is dialing/connecting/active, and sent once as the answer to a `gquery`. The `name`
  field carries the **group name** (not a person's), `video` the media intent, and `count` the
  participant count including the sender. Receivers store it under `groupId` and surface it as an
  "ongoing call / rejoin" banner; an entry not re-announced within 12 s — three missed
  announcements — is pruned locally, so a banner cannot outlive the call it points at.
- `gquery`: asks the group whether a call is running. Fans out to the group's trusted members; a
  member with a live session for that `groupId` answers with `gpresence`, everyone else stays
  silent. Used when presence was missed (a member that was offline when the call started, or a
  fresh join to an old group).
- The group call itself is a full mesh: each pair negotiates its own WebRTC leg (SDP `offer`/
  `answer` and trickled `ice` under the same `callId`), and offer glare is resolved by a
  deterministic election rather than by a fixed offerer — unlike the 1:1 case, where only the caller
  offers. A member that leaves closes only its own legs, and the last remaining participant gets a
  solo-grace window before the call ends.
- `band` (G2, 2026-09-28, optional): how the **sender** is attached to the network: `eth` (Ethernet), `6g`, `5g`,
  `2g` (Wi-Fi band) or `unk` (the sender cannot tell, e.g. a phone hosting the hotspot). Absent from an old client
  (decoded as null); a value this build does not know decodes as `unk`, so a future band does not break decoding.
  A receiver records it for that participant only when `from` is the authenticated peer, so a relayed `gjoin` cannot
  set another participant's band. The link's band is the slower of its two ends; an end that is `unk` or absent defers
  to the other end. Informational in G2 (shown in the call stats); G4 uses it for per-link video budgets.
- **Video by request (G3, 2026-09-28, ADR-049).** In a group *video* call a device sends its video on a leg only
  after that participant asks for it. Four frames, each sent only to the peer involved:

  ```text
  FLASH_CALL action=vreq   callId=<uuid> from=<id> seq=<n> q=<720|540|360> focus=<0|1>
  FLASH_CALL action=vgrant callId=<uuid> from=<id> seq=<n> q=<height>
  FLASH_CALL action=vdeny  callId=<uuid> from=<id> seq=<n> reason=<camera|busy|thermal>
  FLASH_CALL action=vrel   callId=<uuid> from=<id> seq=<n>
  ```

  - **Capability.** `ginvite`, `gaccept`, `gjoin` and `gpresence` carry `vr=1` from a client that speaks this
    protocol. The four frames are sent **only** to a peer whose own frame (never a relayed `gjoin`) said `vr=1`; an
    older client would decode them as an unknown action. A peer whose own frame lacked `vr=1` is an old client: its
    leg is sent video exactly as before G3. A peer that has not announced anything yet is sent no video until it does.
  - `gpresence` also carries `vfree=<n>` in a video call: how many more watchers the sender would take now (0 while
    its camera is off or it is at its send cap).
  - `vreq`: the receiver asks for the sender's video at up to `q` (picture height). `focus=1` marks a request the
    user pinned. Re-sent with a new `seq` when the pin or the height changes; a repeat is idempotent.
  - `vgrant`: the sender switched that leg's encoding on. `q` is the height it sends: the lower of the requested
    `q` and the sender's own level (G4, 2026-09-29). On 2.4 GHz a sender steps every copy down to 360 when its
    watchers no longer fit at 540 and back up after 5 s of fitting; it does not send a new `vgrant` for that change
    (the receiver sees it in the picture). A G3 build ignores `q` and is unaffected.
  - `vdeny`: `camera` (camera off), `busy` (at the send cap), `thermal` (the sender is hot, thermal SEVERE or
    worse; G6, 2026-09-29: it keeps its current watchers and turns new ones down, announcing `vfree=0`). Also sent, with the
    watcher's granted `seq`, when a talking sender drops that watcher to make room (owner decision Q5). An unknown
    reason decodes as `busy`. The receiver skips that sender until a `gpresence` from it says `vfree` > 0; a sender
    that gains room sends that `gpresence` straight away to the peers it turned down.
  - `vrel`: the receiver no longer wants the video; the sender switches that leg's encoding off.
  - **Sequence numbers.** A receiver's `seq` grows with every `vreq`/`vrel` it sends to anyone and is at least the
    wall clock in ms, so a rejoined session's numbers are higher than its previous session's. A sender ignores a
    `vreq`/`vrel` whose `seq` is not greater than the last one it accepted from that peer. A receiver ignores a
    `vgrant`/`vdeny` whose `seq` is not its current request's.
  - **Lifetime.** Grants survive a leg's peer connection being rebuilt; they end on `vrel`, `vdeny`, `ghangup`/
    `gdecline` or the 15 s signaling timeout. The switch is `RTCRtpEncodingParameters.active` on that leg's video
    sender, with no renegotiation.
  - All four are checked like every call frame: `from` must equal the transport peer, and the sender must be a
    participant of this call.
- **Call status (ADR-067, 2026-10-02).** One frame, in 1:1 and group calls, for what a participant's controls say:

  ```text
  FLASH_CALL action=status callId=<uuid> from=<id> [mic=<1|0>] [cam=<1|0>] [hand=<1|0>] [rv=<1|0>] [vu=1] [react=<like|love|wow> rseq=<n>]
  ```

  - Every field is optional and a missing field means "not stated / unchanged". Anything other than `1` or `0` in a flag reads as not stated.
    `mic=0` is muted; `cam=0` is camera off (omitted on an audio call); `hand=1` is a raised hand; `rv=0` means the sender wants no video
    from the receiver (data saver). An unknown `react` name is not a reaction.
  - **Compatibility.** An older client decodes the action as unknown and ignores the frame, so nothing breaks; it simply shows no badges.
    A peer that never sent a status reads as mic on, camera on, hand down, wanting video.
  - **Sent** on every change of a field, once when media connects (a mute pressed while connecting would otherwise be lost) and when signaling is
    restored. In a group, to every participant who accepted (not to ones only invited); not sent in a call that has ended.
  - **`rv` in 1:1:** the receiver of `rv=0` sets `encoding.active=false` on its video sender (no renegotiation, the camera track is untouched);
    `rv=1` switches it back, subject to the voice-priority governor. In a group `rv` is not sent: data saver is local (nobody is asked for video).
  - **`vu=1` (ADR-078, 2026-10-06):** "I added a camera to this 1:1 call, please offer." Sent by the CALLEE after it adds a camera to a
    connected call; the CALLER answers it with a normal `offer` (only the caller ever offers). A caller that adds its own camera just
    offers. Only sent to a peer whose HELLO `caps` contained `cv1`; an older client ignores the unknown field. Group calls never send it.
    A receiver that sees an `offer` with an `m=video` section on a call that had none treats the call as a video call with its own camera off.
  - **Reactions** are a one-shot: `rseq` is wall-clock based and strictly growing per sender; a receiver shows each number once and ignores an
    older or repeated one. A sender sends at most one per 400 ms and a receiver shows at most one per 400 ms per sender (a dropped one still
    spends its number). Reactions are never stored.
  - Checked like every call frame: `from` must equal the transport peer, and in a group the sender must be a participant with a live leg.
- `FLASH_CALL` frame handling is **fail-closed on the sender**: the frame's `from` must equal the
  authenticated transport peer. For group frames the participant is resolved from that `from` and
  never from the peer the frame arrived through, because a group frame can be relayed along a mesh
  leg that is not the sender's own.

### Call log rows: no wire frame

There is deliberately **no** call-log frame. When a call ends, each device already holds every
field a log row needs - call id, peer, direction, video flag, end reason, duration - so each
writes its own row into the chat thread locally. The row is stored as ordinary message text
under a `cmsg:` marker, which is a *storage* convention inside Flash's own database, not part of
this protocol: a third-party consumer receives the same information as a `FlashCallLogEntry`
callback and is free to persist it however it likes.

The cost is that a locally-derived row only knows what that device observed. `missed` is
therefore defined as "an incoming call that never carried media" rather than read off the wire,
because a callee that declines and a callee whose caller gave up both end the call as `NORMAL` -
`decline()` reports NORMAL locally, and an inbound `hangup` while RINGING does too. The
distinction exists on the caller's side (an inbound `decline` ends as DECLINED, a dial timeout as
NO_ANSWER) and is simply not recoverable on the callee's.

#### Group protocol level and the `gv` HELLO field (ADR-044 V1, 2026-09-30)

```text
FLASH_WS_HELLO version=2 deviceId=<id> name=<name> ping=<ms> gv=<group-protocol-level>
```

- `gv` is the group protocol level the sender speaks. This build sends `2` (`FlashProtocol.GROUP_PROTOCOL_LEVEL`): it
  understands **v2 groups** (owner-rooted signed membership and signed messages, see "v2 groups" under Groups).
- **Optional and additive.** A HELLO without `gv`, or with a value that is not a positive integer, means level `1`
  (`parseGroupProtocol`; values above 255 are clamped). `version=` is **not** bumped: it is an exact-match gate
  (`FlashProtocol.isCompatible`) and an old peer must keep connecting.
- Both the dialer and the acceptor learn the peer's level from the peer's HELLO; it is exposed as
  `FlashDevice.groupProtocol` on the session's `peer`. A v2 group is created or extended only with devices whose level
  is at least `2`.

#### Capability list and the `caps` HELLO field (ADR-070, SW-2, 2026-10-04)

```text
FLASH_WS_HELLO version=2 deviceId=<id> name=<name> ping=<ms> [gv=<level>] [caps=<token1,token2,...>]
```

- `caps` is a comma-separated list of ASCII feature tokens advertised by the sender, exposed as `FlashDevice.features: Set<String>`.
- **Formatting and parsing:** Tokens match `[a-z0-9]{1,16}` and are sorted alphabetically. At most 32 tokens are retained; malformed tokens or tokens exceeding 32 are dropped. Parsing never throws.
- **Omitted when empty:** To preserve exact byte equality for clients with no additional features, `caps` is completely omitted when the set is empty.
- **Reserved / defined tokens:**
  - `sw1`: Group swarm wire v1 support (`FSW1` binary frames).
  - `gs1`: Group secret / invite membership support.
  - `cv1`: can take a camera added to a 1:1 call mid-call (`Status` `vu`, an offer that adds an `m=video` section) (ADR-078).
- **Session lifetime:** Features apply to new sessions. Connected sessions retain the features negotiated during handshake until reconnected. Unknown tokens are ignored.

## Groups (Phase 1, 2026-09-08)

Ad-hoc text groups ride the WS mesh as text frames under four new prefixes, encoded with the
same `FlashTextFraming` field rules. Every frame carries `groupId=<uuid>` and `from=<id>`;
receivers MUST verify `from` equals the transport session's peer device id, that the peer is
trusted (paired), and that the sender is an active member — otherwise the frame is dropped.
Unknown `action`/`op` values are ignored (forward compatibility). `keyEpoch=<n>` is reserved on
every message-family frame (always `0` in Phase 1) as the Phase 3 E2E hook.

Membership is an operation log, not a set union: each membership frame carries
`opId=<uuid>` + `version=<ms>`. A member row's state changes only when the candidate
`(version, opId)` compares strictly greater than the stored one — a leave is a tombstone that a
stale/replayed `add` cannot resurrect; only a strictly newer `add` reactivates it. Maximum
membership is **6 including the creator**; only trusted (paired) peers may be added.

### Membership frames

> These four frames are the **legacy** (v1) group membership protocol. A **v2** group uses the signed `bundle` frame instead;
> see "v2 groups" at the end of this section. Legacy frames for a `g2-` group id are dropped.

```text
FLASH_GROUP action=create groupId=<uuid> from=<id> opId=<uuid> version=<ms> name=<escaped> memberCount=<n> member0=<id> …
FLASH_GROUP action=add    groupId=<uuid> from=<id> opId=<uuid> version=<ms> memberCount=<n> member0=<id> …
FLASH_GROUP action=leave  groupId=<uuid> from=<id> opId=<uuid> version=<ms> memberId=<id>
```

- `create`: creator → every initial member. Recipients auto-join if the create passes the
  bound/trust checks (local device in `member*`, ≤ 6 members, all trusted). Roles: creator
  writes `owner` locally; everyone else `member` (Phase 3 activates admin).
- `add`: any active member → all known members. Same versioned merge rule.
- `leave`: a member → all active members. History is kept; the sender stops sending.
- `state` (reconcile and bootstrap): `FLASH_GROUP action=state groupId from opId version name creator memberCount=<n>`
  then, per entry `i`, `m<i>` device id, `n<i>` display name, `r<i>` role, `j<i>` joinedAt, `v<i>` membership version,
  `o<i>` opId, `a<i>` active flag. Sent to a newly added member and to every member on each session-up. `memberCount` is
  1..6 (a longer roster is dropped by the decoder).

**Receiver rules (ADR-044 V1a, 2026-09-29; no wire change).** These close forgery by a paired peer that knows a group id;
see `docs/group/v0-threat-review.md`, findings F-1, F-2, F-4, F-5.

- `create` for a group id the receiver already holds any record of is ignored. A group id is created once.
- `state` for a group the receiver holds a record of is accepted only from a member the receiver already has as active. With
  no record it bootstraps the group, as before. In every case the roster must list the receiver as **active**, list the
  sender as active, and name each device once. The receiver keeps its own recorded owner, and only that owner is stored with
  role `owner`; the frame's `creator` and per-entry `r<i>` do not re-own a group.
- A sender puts every active member in `state` plus the newest inactive rows (leave tombstones) that still fit under 6 rows
  in total. A full group carries no tombstones. Receivers apply an inactive entry by the usual `(version, opId)` merge.
- `FLASH_GSYNC op=push` is ingested only when its `syncId` is one the receiver sent in an `op=request` to that same peer for
  that same group within the last 10 minutes. Anything else is dropped without an ack. Note that the push carries no author
  (`from` is the pusher), so relayed messages are attributed to the pusher until a later version adds one (ERROR-082).

### Chat and receipt frames

> In a v2 group `FLASH_GMSG` also carries a `sig` field (see "v2 groups"). The frames below are shown without it.

```text
FLASH_GMSG  groupId=<uuid> msgId=<uuid> from=<id> name=<escaped> sentAt=<ms> text=<escaped> replyTo=<id> replyPreview=<escaped> keyEpoch=0
FLASH_GRCPT groupId=<uuid> msgId=<uuid> from=<id> deliveredAt=<ms> keyEpoch=0
FLASH_GREAD groupId=<uuid> from=<id> upTo=<msgId> readAt=<ms> keyEpoch=0
```

- A group message is delivered per member: the sender keeps ONE durable outbox row and one
  `group_deliveries` row per recipient. A socket write moves only that member to `SENT`; the
  recipient's `FLASH_GRCPT` moves that member to `DELIVERED`. The message's bubble reads
  DELIVERED only when every active recipient has acknowledged, at which point the outbox row
  retires (ERROR-031's acknowledgement commit rule, generalized).
- Resends/reconnects are idempotent: receivers dedup on `msgId` (IGNORE on conflict) and
  re-ack replays, exactly like 1:1 `FLASH_MSG`.

### Delete-for-everyone actions (F6.2, author-only v1)

```text
FLASH_DACT action=delete messageId=<uuid> conversationId=<peer-id> from=<author-id>
FLASH_GACT action=delete groupId=<uuid> msgId=<uuid> from=<author-id> keyEpoch=0
```

- These are control actions, deliberately distinct from `FLASH_DATA`, `FLASH_MSG`, and
  `FLASH_GMSG`. Both use the existing escaped `FlashTextFraming`; unknown actions are ignored.
- The sender must load the stored row and may emit a delete only when its `senderId` is the local
  device. It tombstones locally and drops the message's durable outbox row before network send.
- Direct receive requires authenticated transport peer == `from`, `conversationId` == that peer,
  and the stored row's conversation/sender to equal that peer/author.
- Group receive requires authenticated transport peer == `from`, trusted active membership in
  `groupId`, and a stored row whose conversation is `groupId` and sender is `from`.
- Accepted actions set the existing tombstone and delete any outbox row. Replays are idempotent.
- Group send fans out only to active trusted members excluding self. v1 grants no owner/admin
  override: only the original author can delete for everyone.

- Attachments are rejected with a logged `unsupported` in Phase 1; group media is Phase 3.

### Offline catch-up (`FLASH_GSYNC`, Phase 1B — wire reserved, not yet sent)

```text
FLASH_GSYNC op=request groupId=<uuid> syncId=<uuid> from=<id> sinceAt=<ms> sinceId=<msgId> tier=<low|medium|high> maxPerSec=<n> maxTotal=<n> keyEpoch=0
FLASH_GSYNC op=claim   groupId=<uuid> syncId=<uuid> from=<id> msgCount=<n> msg0=<id> … keyEpoch=0
FLASH_GSYNC op=push    groupId=<uuid> syncId=<uuid> from=<id> msgId=<uuid> name=<escaped> sentAt=<ms> text=<escaped> replyTo=<id> replyPreview=<escaped> keyEpoch=0
FLASH_GSYNC op=ack     groupId=<uuid> syncId=<uuid> from=<id> hasMore=<0|1> msgCount=<n> msg0=<id> … keyEpoch=0
```

- Cursor is `(sinceAt, sinceId)` — never a bare timestamp, so equal-`sentAt` messages cannot be
  skipped. Holders claim only messages they actually store; deterministic rank over claimants
  is `(tierRank, hash(deviceId + msgId))`; rank 0 pushes paced to `maxPerSec`, rank 1 arms a
  2 s backup, others stand down; a broadcast batch `ack` cancels backups. Budgets: LOW
  returner 5/sec · 100/round; MEDIUM/HIGH 20/sec · 500/round; TTL 24 h; ≤ 2 copies/message.

#### History window, paging and the watermark (ADR-100, 2026-10-09; unit-tested, not device-verified)

All additions are optional keys or a new `op`, so an older build ignores them (a request it cannot parse is simply not answered).

```text
FLASH_GSYNC op=request ... [windowMs=<ms>] [files=<true|false>] [cont=true]
FLASH_GSYNC op=page    groupId=<uuid> syncId=<uuid> from=<id> count=<n> remaining=<n> more=<true|false> lastAt=<ms> lastId=<msgId> keyEpoch=<n>
```

- `windowMs` (omitted when the requester states none, i.e. an older build): how far back the requester wants messages. The holder serves
  `min(windowMs, ceiling)`; an omitted window means today's 24 h of text and 7 days of file offers, still inside the ceiling. `files=false`
  skips file offers; a stated window also limits file offers to inside it (never beyond 7 days, `SwarmConfig.retentionMs`).
- `cont=true`: the requester is continuing a chain after a `page` with `more=true`; the holder serves the next page from the cursor.
- `page` closes one round of pushes (sent after the pushes, only to a requester that sent `windowMs`): `count` rows were pushed, `remaining` more are
  held past this page (capped at 2 000, so a larger history reports "at least"), `more` says another page is available, `lastAt`/`lastId`
  is the last row served (a count-0 page with `more=true` carries the scan end). The requester advances its per-(group, holder)
  **contiguous watermark** to `(lastAt, lastId)` only when it has seen all `count` pushes of the page (even ones refused as
  duplicate or unsigned); a page that lost a frame leaves the watermark where it was and the next session resumes from it. A requester
  starts after the watermark of the asked holder, else after its newest local row (older holders), never earlier than the window allows.
- The ceiling is enforced by every holder, whatever the requester asks for: a window above it shrinks to it, `NONE` serves nothing (no
  messages, no files). A chain is at most 100 pages of 100 rows.
- Requester window: a newly joined member asks for the window it chose on the join card (default 30 days) plus the time since it chose;
  a returning member asks for `min(ceiling, max(7 days, time since last contact))`, never reaching further back than what it chose
  to see; a member that predates the feature is a returning member with files.
- Contact time is **per holder** (sweep G1, 2026-10-09): "time since last contact" is measured against the holder asked, from that
  holder's watermark `updatedAtMs`, not from the group as a whole, so a quiet holder is not asked for a window sized by a chatty one.
- One holder at a time (sweep G3, ADR-106): the requester serialises catch-up per group in a `CatchUpLane`. Holders are tried in order of
  watermark `updatedAtMs` ascending (the one it has heard from least recently first); a holder that sends nothing for 45 s is dropped and the next
  is asked; one episode lasts at most 3 minutes. Nothing changes on the wire.
- The `page` end marker is clamped (sweep G12): the requester never advances a watermark beyond the newest push it actually received, and
  a count-0 page whose end lies more than 5 minutes in the future is refused. A holder answers at most 30 `request` frames per 10 s per
  (group, requester) (sweep G11); further ones are dropped silently.

### v2 groups (ADR-044 V1, 2026-09-30)

Design and rationale: `docs/group/v1-signed-membership-plan.md` (D1–D9) and `docs/group/v0-threat-review.md`. A v2 group is
**owner-rooted**: the owner signs the group's charter and every membership fact; every member signs their own chat messages.
Nothing a member sends can forge another member's message, add or remove anybody, or take the group over. Legacy groups
(sections above) are unchanged and are never upgraded in place.

**Group id namespace.** A v2 id is

```text
groupId = "g2-" + lowercase-hex( SHA-256( "flash-gid-v1" ‖ len(ownerSpki) ‖ ownerSpki ‖ len(nonce) ‖ nonce ) )[0 .. 32)
```

(35 characters; `len` is the 4-byte big-endian field length below). An id therefore names exactly one possible owner. The
prefix `g2-` is reserved: a legacy `create`/`add`/`leave`/`state` frame for a `g2-` id is dropped and logged, a charter for
a non-`g2-` id is dropped, and a group never goes back to legacy.

**Canonical bytes (what is signed).** Every signed byte string is `tag ‖ field ‖ field …`. Each field is a 4-byte big-endian
length followed by its bytes; the tag is itself the first field. Text is UTF-8, a `long` is 8 bytes big-endian, a boolean is one
byte (`0x00`/`0x01`), an absent optional string is the empty string. The order is fixed; there are no optional fields.

| Signed thing | Tag | Fields in order |
|---|---|---|
| charter | `flash-gcharter-v1` | groupId, name, ownerId, ownerSpki, createdAt, nonce, proto |
| member cert | `flash-gcert-v1` | groupId, subjectId, subjectSpki, label, role, seq, opId, active, issuerId |
| group message | `flash-gmsg-v1` | groupId, msgId, from, sentAt, replyToId, replyPreview, text |
| group id | `flash-gid-v1` | ownerSpki, nonce (hash input only; not signed) |

Signatures are ECDSA P-256 / SHA-256 over these bytes with the signer's identity key (the same key whose SHA-256 fingerprint
the trust store pins). Key and signature bytes travel as standard base64; `nonce` is 16 random bytes.

Golden vectors (inputs: owner SPKI = bytes `00..0f`, subject SPKI = bytes `20..2f`, nonce = bytes `a0..af`; produced by an
independent Python reference and asserted in `GroupCanonicalTest`). Group id: `g2-6f01129f28cff84f8ef67cb9d1970499`.

```text
charter  (name "Café Crew", ownerId "owner-1", createdAt 1700000000000, proto 2)
  00000011666c6173682d67636861727465722d76310000002367322d36663031313239663238636666383466386566363763623964313937303439390000000a436166c3a92043726577000000076f776e65722d3100000010000102030405060708090a0b0c0d0e0f000000080000018bcfe5680000000010a0a1a2a3a4a5a6a7a8a9aaabacadaeaf000000080000000000000002

cert  (subject "dev-b", label "Béa", role member, seq 3, opId "op-7", active true, issuer "owner-1")
  0000000e666c6173682d67636572742d76310000002367322d3666303131323966323863666638346638656636376362396431393730343939000000056465762d6200000010202122232425262728292a2b2c2d2e2f0000000442c3a961000000066d656d626572000000080000000000000003000000046f702d370000000101000000076f776e65722d31

message  (msgId "m-1", from "dev-b", sentAt 1700000000123, no reply, text "héllo")
  0000000d666c6173682d676d73672d76310000002367322d3666303131323966323863666638346638656636376362396431393730343939000000036d2d31000000056465762d62000000080000018bcfe5687b00000000000000000000000668c3a96c6c6f
```

A change to any of these is a wire break and needs a new tag (`…-v2`).

**Charter and cert rules.** The charter is immutable: `{groupId, name, ownerId, ownerSpki, createdAt, nonce, proto=2}` signed by
the owner key; it is valid only if `groupId` equals the derivation above, the owner is paired with the receiver, and the owner's
pin fingerprint equals `SHA-256(ownerSpki)`. A member cert `{groupId, subjectId, subjectSpki, label, role, seq, opId, active,
issuerId}` is issued by the owner (add, remove) or by the subject about themselves (leave: `active=false`, `issuerId=subjectId`).
It is valid when: the signature verifies under the issuer's key; `role=owner` iff `subjectId` is the charter owner; `label` is 1–80
characters; `seq ≥ 1`; `opId` is non-blank; and the key is bound: for another device the receiver's pin store holds a pin whose
fingerprint equals `SHA-256(subjectSpki)`, for the receiver itself it is the receiver's own key, for the owner it is the charter's
key. An owner-issued tombstone needs no subject binding. Certs merge per subject by the same `(seq, opId)` rule as legacy
membership; a leave is a tombstone that only a strictly greater `(seq, opId)` from the owner can undo.

**Bundle frame** — the single v2 membership frame; it replaces `create`/`add`/`leave`/`state` for a v2 group:

```text
FLASH_GROUP action=bundle groupId=<g2-id> from=<id> opId=<uuid> version=0
            cName=<escaped> cOwner=<id> cOwnerKey=<b64> cCreated=<ms> cNonce=<b64> cProto=2 cSig=<b64>
            certCount=<n>
            c0s=<subject id> c0k=<b64 spki> c0l=<escaped label> c0r=<owner|member> c0q=<seq> c0o=<opId> c0a=<true|false> c0i=<issuer id> c0g=<b64 sig>
            c1s=… (one group of nine fields per cert, index 0 … certCount-1)
```

- `groupId`, `from`, `opId`, `version` keep the common header so a legacy decoder that reaches the action treats it as an unknown
  action and drops it. `opId` only tags the frame; `version` is unused (`0`).
- A bundle may carry any subset of certs: **create** = charter + every cert; **add/remove** = charter + the changed certs (the
  owner sends the full set to a newly added device); **leave** = charter + the leaver's own tombstone; **reconcile on
  session-up** = charter + every cert including tombstones. Because it is self-authenticating, any paired member may relay one.
- The decoder rejects a bundle with a missing required field, a non-boolean `c<i>a`, a `certCount` outside
  `0..MAX_BUNDLE_CERTS` (`MAX_MEMBERS_V2 + 64` = 84 since V2, was 70), or two certs for the same subject.

**Receiver rules (bundle).**

1. Common gate: `from` equals the transport session's peer and the peer is paired.
2. Unknown `g2-` group: accepted iff the charter is valid and **my own cert in the bundle is valid, active and for my key**.
   The sender may be any paired peer.
3. Known v2 group: the charter must equal the stored one (else ignored and logged); each cert is verified independently and
   merged per subject; a bad cert is dropped without stopping the rest. Only the owner adds, removes or relabels; a member can
   only tombstone themselves.
4. Verification budget: a cert whose `sig` equals the stored row's costs nothing; new certs cost one verification each; a peer
   over `BUNDLE_VERIFICATIONS_PER_WINDOW` (120 per 60 s) has further bundles dropped until the window rolls.
5. A device whose own stored row is **inactive** (it left, or the owner removed it) ignores every bundle that does not carry a valid
   *active* cert for itself (an invitation back), logs `local-not-member`, installs no vouch and re-installs none. When it verifies an
   inactive cert for itself it withdraws every vouch the group made. It also takes no other group frame for that group.

**Removal notice (sender behaviour, no wire change).** On session-up a member sends an *inactive* v2 peer a bundle of the charter plus
that peer's stored tombstone, and only when the tombstone is owner-issued (`issuerId` is the owner and not the subject); a self-issued
leave is never sent. The receiver checks the owner's signature itself, so any member can relay it; a device that already knows treats it
as a stale replay (no verification cost). Members never send a roster to an inactive peer.

**Signed group message.** `FLASH_GMSG` gains an optional `sig=<b64>`: the author's signature over the `flash-gmsg-v1` bytes. `from` is the
author. `sig` is **omitted for a legacy group's message**, so legacy frames are byte-identical to before. In a v2 group the
receiver requires: the author is an active member of the *verified* roster, is paired, and `sig` verifies under the roster key
for the author. The stored sender name is the roster `label`, never the frame's `name`. A missing or bad signature drops the
message and no receipt is sent.

**Signed relay (`FLASH_GSYNC op=push`).** A push gains `author=<id>` and `sig=<b64>`, written only when the pushed message is signed.
The pushed message's `from` is then `author`, not the pusher: the pusher is only a relay and the signature is what proves the
author (this fixes ERROR-082 / finding F-9 for v2). A push without `author` is decoded as before (the pusher stands in) and is
only valid for a legacy group. The V1a solicitation rule (a push is ingested only for a `syncId` this receiver sent in a request
to that same peer for that group in the last 10 minutes) still applies. A message row without a stored signature is never
relayed in a v2 group.

**Unchanged in v2:** `FLASH_GRCPT`, `FLASH_GREAD`, `FLASH_GACT delete`, `FLASH_GMEDIA` and the sync `request`/`claim`/`ack` are
authenticated by the TLS session (`from` equals the peer). Delete additionally needs the stored row's `senderId` to equal the
frame's `from`, which is correct in a v2 group because relayed messages keep their true author.

**Old clients (D9).** An old client is never admitted to a v2 group (create and add require `gv ≥ 2`). It ignores the bundle
action, ignores `sig`/`author`, and still chats 1:1 and in legacy groups.

**Vouched trust and groups of 20 (ADR-044 V2, 2026-09-30): no wire change.** `gv` stays `2`, no frame, field or action name is added
or changed, and the golden vectors are unchanged (no build that carried V1 without V2 was ever released, so there is no device to
keep compatible with). What changes is what a receiver does with frames it already understood:

- `MAX_MEMBERS_V2` is `20` (legacy `MAX_MEMBERS` stays `6`: every shipped codec rejects a longer legacy roster), so a v2 roster has
  at most 20 active certs and 64 tombstones.
- An owner-issued **active** cert for a subject the receiver is not paired with is accepted as an introduction (a *vouch*) unless
  the trust store says another group already vouches a different key for that id, or the receiver is paired with that id under a
  different key. The receiver still requires to be paired with the **owner** (one hop, no chains). A self-issued leave for an
  unpaired subject is still refused.
- The gate for group text, receipts, reads, deletes, typing, sync and bundles of a **known** v2 group, and for group-call invite,
  presence, query and join list, is "paired, or an active member of the stored verified roster whose live TLS identity key equals the
  cert key". A group media announcement and a group attachment stay paired-only, so a vouched member sends and receives no files.
- 1:1 chat, files, calls and push-to-talk are unchanged: a vouch grants nothing outside the group.

Rationale, the pin-store rules and the accepted limits: `docs/group/v2-vouched-trust-plan.md`, `docs/security.md` section 9.

## PTT ping (v1, 2026-09-09)

A hardware push-to-talk button press fans one ping out to every paired + online peer.
Rides the WS mesh as a text frame under a new prefix, encoded with the same
`FlashTextFraming` field rules as chat/pairing frames. Fire-and-forget: no outbox row,
no retry, no persistence — peers missing from `activeSessions` at press time are skipped.

```text
FLASH_PTT action=ping eventId=<uuid> from=<id> senderName=<escaped> sentAt=<ms>
```

- Receivers MUST verify `from` equals the transport session's peer device id AND that the
  peer is trusted (paired); otherwise the frame is dropped (fail-closed, same rule as
  §Groups above).
- Receivers deduplicate on `eventId` (replays are ignored) and surface a tone +
  notification. There is deliberately no chat-row write in v1.
- Unknown `action` values are ignored (forward compatibility).
- The sender listens to the OEM Zello hook `com.zello.ptt.down` ONLY (single-press v1;
  the up action is not observed). On tydtech firmware the same press also emits
  scanner/lowercase/uppercase variants — see `docs/android-platform-notes.md` — which are
  ignored so one press fans out exactly once.

## PTT voice session (ADR-032, Phases 0–3 implemented)

Strict half-duplex floor: one holder transmits, all others only receive. Control rides
the WS mesh as text frames under a new prefix with the same `FlashTextFraming` rules;
audio rides binary frames (Phase 1). Same scope as the ping: fan-out to all
paired+online peers, fire-and-forget session invites (a peer that misses `start` is not
a member; there is no late-join in v1).

```text
FLASH_PTSS action=start sessionId=<uuid> from=<id> name=<escaped> sentAt=<ms> rate=<8000|16000> pms=<20|40|60>
FLASH_PTSS action=stop sessionId=<uuid> from=<id> sentAt=<ms>
FLASH_PTSS action=leave sessionId=<uuid> from=<id> sentAt=<ms>
FLASH_PTSS action=hb sessionId=<uuid> from=<id> seq=<n> sentAt=<ms> rtt=<ms>
FLASH_PTSS action=hb-ack sessionId=<uuid> from=<id> seq=<n> sentAt=<ms>
```

- `start`: holder claims the floor (`rate`/`pms` describe the audio to follow).
- `stop`: ONLY the holder's own stop ends a session (toggle-press, burst cap, deny);
  anyone else's `stop` is ignored, so a forged/stale frame cannot kill a transmission.
- `leave`: receiver-side cancel hint, informational only, never tears anything down.
- `hb` (1 Hz while TALKING): liveness + broadcaster-measured RTT echo (`rtt` omitted
  until the first ack arrives). Receivers compute loss% locally from audio seq gaps.
- `hb-ack`: receiver echo answering `seq`; the holder timestamps these for RTT.
- Receivers MUST verify `from` equals the transport session's peer id AND trust, exactly
  like §Groups and the ping. Unknown `action` values ignored. `rate`/`pms` outside the
  allowlists are rejected (fail-closed: no agreed audio format, no session).
- Liveness: 5 s without a valid holder audio packet or holder heartbeat auto-closes
  orphaned receivers; 60 s max burst with 45 s warning; busy floor denies (no
  preemption in v1). If two trusted peers claim within the 1.5 s collision window, the
  lexicographically lower device id wins deterministically; late Start replays cannot
  preempt an established talk.
- Audio binary framing is implemented in Phase 1. Magic `PTT1` is disjoint from the
  transfer pipeline's `FLSH` magic, so audio is routed before transfer parsing.

### PTT audio binary frames (Phase 1)

One frame per capture packet, little-endian scalars (transfer-pipeline order):

```text
magic      4B  "PTT1" (checked before the transfer pipeline with a 4-byte pre-check)
version    u8  1
sessionLen u16 sessionId UTF-8 length (<= 128)
sessionId  N   session UUID
seq        u32 packet sequence in the session
captureTs  u64 sender capture clock, ms (scheduling + age stats, never a wall clock)
pcm        ..  LE int16 mono samples, even length, <= 4096 B
```

- Rate/duration are NOT repeated per packet: the `start` frame fixes them
  (8000 Hz × 60 ms on LOW or capture fallback, 16000 Hz × 20 ms otherwise).
- Receivers bind strictly: current LISTENING session + holder transport peer, exact PCM
  payload size negotiated by `rate`/`pms`, else drop. Gaps conceal locally (last-frame
  repeat); loss% derives from ready vs concealed counts.
- Senders put `start` on each live member's serialized WS stream before enabling capture
  packet delivery, so no `PTT1` frame can overtake the format/session claim on that leg;
  legs whose `start` write fails are removed before audio fan-out begins.
- Transport order (measured-first amendment, 2026-09-10): WS-binary on the live session
  first — zero setup, sub-ms on LAN for 640–960 B payloads. The TCP data channel stays
  an optimization iff benchmarks show WS framing/jitter cost dominating; do not build it
  on assumption.

## Presence sharing (PC4, ADR-046, 2026-09-28)

A device passes on who it can reach, so a contact it is connected to shows as **Online** (ring dot) on a
mutual contact's screen even when that contact cannot see the device itself. Plaintext over the TLS WebSocket,
like call and group frames. Code: `core/network/.../presence/` (`PresenceCodec`, `PresenceState`,
`PresenceExchange`); plan `docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.2.

```text
FLASH_PRES v=1 t=hello share=<1|0> [salt=<32 hex>] [r=<ms>]
FLASH_PRES v=1 t=want h=<16 hex>,<16 hex>,...
FLASH_PRES v=1 t=digest e=<entry>;<entry>;...
FLASH_PRES v=1 t=delta  e=<entry>;<entry>;...

entry = <deviceId>|<ageMs>|<c|s|x>|<hops>|[<host>:<port>]
```

- **hello**: sent when a session opens, every 30 s (STANDARD), and when Ghost mode flips. `share=0` is a Ghost
  device: nobody may report it, and it sends no salt because it reports nothing. `salt` is fresh random per session.
  `r` (PC5) is the sender's refresh interval: 60 000 in ECO, 30 000 in STANDARD, 10 000 in BOOST. A hello is sent
  again when it changes. A value outside 5 000..60 000 is treated as absent; a pre-PC5 client sends none.
- **want**: the asker's contacts (paired peers and fellow group members), each as
  `H = SHA-256("flash-pres-v1|" || salt bytes || UTF-8 id)` truncated to 8 bytes, under the **reporter's** salt.
  Replaces the previous want list. Sent again whenever the salt or the asker's contacts change. At most 512.
- **digest** replaces everything the sender reported; **delta** upserts, and state `x` withdraws an entry
  (deltas only). A digest goes out every 30 s and when a want arrives; deltas on change, at most one per
  second per peer. An empty digest is not sent unless it withdraws something. At most 128 entries.
- **entry**: `c` = the reporter has a live session with the device, `s` = the reporter's discovery sees it.
  `ageMs` is how old the information is (never a clock time; phone clocks disagree), 0..600 000. `hops` is 1 for
  the reporter's own observation and 2 for a relay; nothing with 2 hops is relayed again. The endpoint is a
  literal unicast IP and a port; host names, loopback, unspecified, multicast and broadcast are rejected. An IPv6
  scope id is escaped by the field framing (`%` becomes `%25`).
- **Versioning**: a frame with `v` other than 1, an unknown `t` or a malformed field is consumed and ignored. A
  malformed entry is dropped on its own. Old clients drop the whole prefix: all three inbound routers (app holder,
  `Flash.create`, desktop) match the first token exactly and log-and-drop unknown frames (verified before PC4).

**Who is told what.** Frames are sent only to, and accepted only from, a peer that is paired or a fellow member of
one of the device's active groups. A peer R hears about device S only when:
1. R and S share one of the reporter's groups, or R's want list contains `H(reporter salt for R, S)`;
2. S said `share=1` in its own hello to the reporter (for a hop-1 entry; default deny, so a pre-PC4 client is never
   reported), or S has not said `share=0` to the reporter (for a relay);
3. S is not R, not the reporter, and was not learned from R.

A Ghost device reports nothing. When it enters Ghost it sends an empty digest to withdraw what it reported.

**What a receiver does with it.** An entry is kept only when its subject is one of the receiver's contacts, and it
expires when `ageMs` plus the receiver's holding time passes its limit. The limit is the larger of the receiver's
own max age (ECO 90 s, STANDARD 45 s, BOOST 20 s) and 1.5 × the reporter's announced `r`, so a BOOST phone does not
drop an ECO reporter's entries between that reporter's refreshes. A relayed (hops 2) entry gets another 90 s
(1.5 × the longest refresh), because the relayer's copy can be up to one origin refresh old before it is replaced.
Frames are rate-limited per sender (burst 10, one more every 250 ms since PC5, so a BOOST sender's 250 ms deltas
fit). A report never changes trust. It shows the subject as Online (ring). A report with
an endpoint becomes a **dial tip** only when the subject has a pinned TLS key, has no session, and is not seen by the
receiver's own discovery. The tip is dialed with the subject's id named, so the TLS handshake checks that pin and a
forged tip fails before any frame. After 3 failed tips in a row, the sender is ignored until its session reopens.

## Link control (PC5, ADR-048, 2026-09-28)

Lets an ECO device close a session it dialed without the peer redialing, and only when the peer does not want the
session either. Plaintext over the TLS WebSocket. Code: `core/network/.../mode/` (`LinkCodec`,
`ConnectionModeController`); plan `docs/network/PRESENCE-CONNECTIONS-PLAN.md` §3.4.

```text
FLASH_LINK v=1 t=park       # "I dialed this session and would like to close it"
FLASH_LINK v=1 t=park-ok    # "I don't need it either; I have stopped redialing you"
FLASH_LINK v=1 t=keep       # "I want it; don't ask for 10 minutes"
```

- **park** is sent only by a device in ECO, only on a session **it dialed**, after 10 minutes without user traffic
  (anything except keepalive, `FLASH_PRES` and `FLASH_LINK` frames), and only when the peer is not one of its 3 ring
  neighbours, an active or busy peer, or an unpaired peer while its Nearby screen is open. At most once per 10 minutes
  per peer.
- The receiver answers **park-ok** only when it is in ECO, did not dial the session, and does not want it by the same
  rules. Before answering it drops the session from its redial targets (`releaseSession`), so the close that follows
  can never start a redial. Otherwise it answers **keep**.
- On **park-ok** the requester closes the session, but only if it asked and the session is still idle and unwanted
  (traffic may have resumed while the answer was in flight). An unsolicited park-ok is ignored.
- **Old clients** drop the unknown prefix and never answer, so the session stays. A device never closes a session the
  other side dialed, and never refuses an incoming one.
- **Versioning**: a frame with `v` other than 1 or an unknown `t` is consumed and ignored.

## Intended Full Protocol


The production transfer protocol will run over TLS and will include:

- `HELLO`
- `HELLO_ACK`
- `PAIR_REQUEST`
- `PAIR_ACCEPT`
- `TRANSFER_REQUEST`
- `TRANSFER_ACCEPT`
- `FILE_START`
- `CHUNK`
- `CHUNK_ACK`
- `FILE_COMPLETE`
- `TRANSFER_COMPLETE`
- `ERROR`
- `CANCEL`
- `DISCONNECT`
- `PAUSE`
- `RESUME`

The wire protocol must stay transport-independent so LAN and Wi-Fi Direct can use the same transfer engine.

## Transfer completion and integrity (ADR-068, 2026-10-02)

Chunk frames (`FLSH` framing v2) are documented in the KDoc of `core/transfer/.../chunked/ChunkFrame.kt`; this section records only the completion rule.

- `FILE_START` carries the whole-file SHA-256 (`fileSha256Hex`) and every `CHUNK` carries its own SHA-256, verified by the receiver before the chunk is written.
- When the receiver has every chunk it reads the assembled file back, hashes it and compares it with `fileSha256Hex`.
  - **Match, or nothing to compare against:** it answers `COMPLETE verified=true` (unchanged wire behaviour).
  - **Mismatch:** it deletes the file, forgets its confirmed chunks, drops the session and answers **`COMPLETE verified=false`**.
- A sender that receives `COMPLETE verified=false` treats the transfer as failed, clears its confirmed chunks and resends everything on retry.
  A sender that receives `verified=true` (every build before this change always did) completes as before.
- Compatibility: an older receiver never sends `verified=false`, so a new sender behaves as before with it; an older sender ignores the flag
  (it completes), so a damaged file between a new receiver and an old sender fails on the receiver only.

## Group swarm wire FSW1 v1 (ADR-071, PROPOSED 2026-10-04, not implemented)

**Status:** module `:core:swarm` and wire codec implemented in SW-3. Plan: `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md`
5.3; behaviour rules in ADR-072.

### Golden vectors (SW-3)

Verified by `SwarmGoldenVectorsTest` in `:core:swarm`. All multi-byte integers are little-endian.

- **`HAVE_ALL` (`groupId="g1"`, `root="01"*32`):**
  `465357310105000024000000020067310101010101010101010101010101010101010101010101010101010101010101`
- **`PIECE` (`groupId="g1"`, `root="01"*32`, `index=7`, `bytes=0x0a,0x0b,0x0c`):**
  `46535731010700002f00000002006731010101010101010101010101010101010101010101010101010101010101010107000000030000000a0b0c`
- **`REQUEST` (`groupId="g1"`, `root="01"*32`, `pieces=[3, 8]`):**
  `46535731010600002d000000020067310101010101010101010101010101010101010101010101010101010101010101020300000008000000`
- **`HAVE` (`groupId="g1"`, `root="01"*32`, `ranges=[(0, 5), (10, 2)]`):**
  `465357310104000036000000020067310101010101010101010101010101010101010101010101010101010101010101020000000000050000000a00000002000000`
- **`REJECT` (`groupId="g1"`, `root="01"*32`, `reason=BUSY(1)`, `retryAfterMs=1500`, `scopeAll=false`, `pieces=[4]`):**
  `46535731010800002f00000002006731010101010101010101010101010101010101010101010101010101010101010101dc050000000104000000`
- **`CANCEL` (`groupId="g1"`, `root="01"*32`, `originId="devA"`, `messageId="msg1"`, `reason=USER(1)`, `cancelledAtMs=10000`, `sig=[0x30,0x06,0x02,0x01,0x01,0x02,0x01,0x02]`):**
  `46535731010900004300000002006731010101010101010101010101010101010101010101010101010101010101010104006465764104006d73673101102700000000000008003006020101020102`
- **`SOURCE_STATUS` (`groupId="g1"`, `root="01"*32`, `originId="devA"`, `messageId="msg1"`, `status=LOST(1)`, `reason=DELETED(1)`, `atMs=5000`, `sig=[0x01,0x02]`):**
  `46535731010b00003e00000002006731010101010101010101010101010101010101010101010101010101010101010104006465764104006d7367310101881300000000000002000102`
- **`SUMMARY` (`groupId="g1"`, `tombstones=[]`, `entries=[root="02"*32, state=PARTIAL(1), servingEnabled=true]`):**
  `46535731010100002a000000020067310000010002020202020202020202020202020202020202020202020202020202020202020101`
- **2-piece manifest canonical bytes (`version=1`, `pieceSize=65536`, `totalSize=100000`, `fileSha256="30"*32`, `pieces=["10"*32, "20"*32]`):**
  `4653574d0100000100a08601000000000030303030303030303030303030303030303030303030303030303030303030300200000010101010101010101010101010101010101010101010101010101010101010102020202020202020202020202020202020202020202020202020202020202020`

### Envelope

```text
magic       4B  "FSW1" (0x46 0x53 0x57 0x31); checked after FSEC decryption, by the engine's magic router (SW-2). Reserved: dropped with rate-limited warning if no handler is registered.
version     u8  1
type        u8  1..12 (table below)
flags       u16 0 in v1; receivers ignore unknown bits
bodyLength  u32 must equal the number of bytes left in the WebSocket binary message
body        ..  per type
```

- **Little-endian**, like framing v2 and `PTT1`. One FSW1 frame per WebSocket binary message; trailing bytes make it malformed.
- **Carriage:** the WebSocket session only, through `SecureBinaryFrameCodec` (an `FSEC` envelope when the peers share a pairing session key,
  plain otherwise, TLS in both cases). Never Android's TCP data channels: their frames are capped at 512 KiB
  (`DataChannelFraming.MAX_FRAME_BYTES`).
- **Who may receive one:** only a peer whose HELLO `caps` contained `sw1`. A build without the swarm attached drops FSW1 in the router; a
  build from before SW-2 never advertises `sw1`, so it is never sent one.
- **Field encodings:**

  | Name | Encoding | Limit |
  |---|---|---|
  | `str` | `u16` length + UTF-8 bytes | group id ≤ 64 bytes; device and message ids ≤ 128 bytes |
  | `root` | 32 raw bytes, the content id | in text frames: 64 lowercase hex characters |
  | `index` | `u32` piece index | `< pieceCount` |
  | `sig` | `u16` length + the ECDSA P-256 / SHA-256 signature bytes as the platform produces them (DER) | ≤ 128 bytes |

### Frame bodies

Every type except `SUMMARY` starts with `str groupId` and `root`, so the gate is always checked for one named group (INV-3, INV-8).

| Type | Name | Body after `groupId`, `root` | Limits and rules |
|---|---|---|---|
| 1 | `SUMMARY` | (no root) `str groupId`, `u16 tombCount`, tombCount × tombstone (the `CANCEL` body), `u16 entryCount`, entryCount × (`root`, `u8 state` 0 NONE / 1 PARTIAL / 2 ALL, `u8 entryFlags` bit 0 = serving enabled) | tombCount ≤ 64, entryCount ≤ 256; every tombstone names this frame's group. A device sends as many `SUMMARY` frames as it needs, **all tombstones before any entry** across them. Sent after a session comes up and after a membership change. |
| 2 | `MANIFEST_GET` | `u16 fragmentIndex` | `fragmentIndex < 9` |
| 3 | `MANIFEST_PART` | `u16 fragmentIndex`, `u16 fragmentCount`, `u32 length`, bytes | fragmentCount 1..9; length 1..65,536; every fragment but the last is exactly 65,536 bytes |
| 4 | `HAVE` | `u16 rangeCount`, rangeCount × (`u32 start`, `u32 count`) | 1..1,024 ranges, sorted, non-empty, not overlapping, inside `pieceCount`; at most one per second per peer per root |
| 5 | `HAVE_ALL` | (nothing) | also means "I finished and verified the whole file" |
| 6 | `REQUEST` | `u8 count`, count × `index` | count 1..64, indexes distinct |
| 7 | `PIECE` | `index`, `u32 length`, bytes | length = the manifest's length of that piece (`pieceSize`, the last may be shorter) |
| 8 | `REJECT` | `u8 reason`, `u32 retryAfterMs`, `u8 scope` (0 = the listed pieces, 1 = all), `u8 count`, count × `index` | count 0..64, and 0 when scope = 1 |
| 9 | `CANCEL` | (tombstone) `str originId`, `str messageId`, `u8 reason` (1 `USER`, 2 `DELETED`), `u64 cancelledAtMs`, `sig` | origin only (INV-6); cancels the announcement `(groupId, messageId)` |
| 10 | `CANCEL_ACK` | `str messageId` | stops re-forwarding that tombstone to that peer |
| 11 | `SOURCE_STATUS` | `str originId`, `str messageId`, `u8 status` (1 `LOST`, 2 `RESTORED`), `u8 reason` (0 none, 1 `DELETED`, 2 `CHANGED`, 3 `PERMISSION`), `u64 atMs`, `sig` | origin only; the newest `atMs` per announcement wins; not a cancel |
| 12 | `UNREQUEST` | `u8 count`, count × `index` | count 1..64 |

`CANCEL_ACK` and the tombstone name the message id because **a tombstone cancels one announcement, not the content** (ADR-072 rule 5): the
content `(groupId, root)` stops being requested and served only when every announcement of it in that group is tombstoned.

**Reject reasons** (an unknown value is treated as `BUSY`):

| Value | Reason | Meaning |
|---|---|---|
| 1 | `BUSY` | Slots or budget full; ask again after `retryAfterMs`. |
| 2 | `ELSEWHERE` | Origin only: another member holds the piece (origin offer policy, INV-7). |
| 3 | `UNKNOWN` | This device holds no announcement of this root **in this group**. Also the answer for content held only in another group (INV-8). |
| 4 | `GONE` | This device had it and can no longer read it. |
| 5 | `NOT_MEMBER` | The requester fails the group gate for this group (ADR-075). |
| 6 | `CANCELLED` | Every announcement of this content in this group is tombstoned. |
| 7 | `UNSUPPORTED` | The envelope version is unknown. Sent with an empty group id and an all-zero root, at most once per peer per 10 minutes. |

### Manifest and content id

```text
magic       4B  "FSWM"
version     u8  1
pieceSize   u32 a power of two, 65,536..1,048,576, and no larger than the largest binary frame the session carries (SW-3 records it)
totalSize   u64 > 0
fileSha256  32B whole-file SHA-256 (the ADR-068 hash)
pieceCount  u32 1..16,384, equal to ceil(totalSize / pieceSize)
pieceHash   pieceCount × 32B, the SHA-256 of each piece

root = SHA-256(every byte above)          at most 53 + 16,384 × 32 = 524,341 bytes, so at most 9 fragments
```

- Little-endian, like the frames. The fragments are consecutive slices of these bytes.
- A receiver uses a manifest only after recomputing the root, and checks `totalSize` and `pieceSize` against the announcement.
- **Not swarmed:** an empty file, and a file that needs more than 16,384 pieces at the largest allowed piece size (more than 16 GiB). Both use
  today's push.

### Signed statements

Built with the **v2 group canonical rules** (section "v2 groups": tag first, every field a 4-byte big-endian length plus bytes, a `long` as
8 bytes big-endian). They are big-endian although the frames are little-endian: a statement is a separate byte string, not a frame. Signed
with the origin's identity key (ECDSA P-256 / SHA-256), verified against the origin key stored with the announcement.

| Statement | Tag | Fields in order |
|---|---|---|
| announce | `flash-swarm-v1/announce` | groupId, messageId, originId, root (hex), sizeBytes, fileName, mimeType, sentAt |
| cancel | `flash-swarm-v1/cancel` | groupId, root (hex), originId, messageId, reason (`USER` / `DELETED`), cancelledAtMs |
| source | `flash-swarm-v1/source` | groupId, root (hex), originId, messageId, status (`LOST` / `RESTORED`), reason (`NONE` / `DELETED` / `CHANGED` / `PERMISSION`), atMs |

The tags differ from every group tag (`flash-gcharter-v1`, `flash-gcert-v1`, `flash-gmsg-v1`), so no signature verifies across kinds.

### The announcement: four optional `FLASH_GMEDIA` fields

```text
FLASH_GMEDIA groupId=… msgId=… transferId=… wireFileId=… from=… name=… fileName=… mime=… size=… sentAt=… sig=…
             root=<64 hex> pieceSize=<n> swarm=1 rootSig=<b64>
```

- The origin adds the four fields only for members whose HELLO had `sw1`. Every other member gets today's frame, unchanged, and today's push.
- `transferId` stays per recipient: it is the receiver's row id, as today. `wireFileId` keeps today's value and is not used by the swarm (no
  FILE_START follows).
- `sig` (today's v2 message signature) stays. `rootSig` signs the announce statement above.
- A receiver drops a `swarm=1` announcement, and creates no bubble, when `rootSig` does not verify against the origin's roster key, `root` is
  not 64 hex characters, `pieceSize` is outside the limits, or `size` is 0 or more than 16,384 × `pieceSize`.

### Version and compatibility

- An unknown `type` is ignored and not counted as malformed. An unknown envelope `version` gets `REJECT(UNSUPPORTED)`.
- Three malformed FSW1 frames from one peer within a minute: ignore that peer's FSW1 frames for 10 minutes. **Never drop the session for this.**
- **What an older build does with the new `FLASH_GMEDIA` fields (SW-0 task 4, [code] 2026-10-04).** `FlashTextFraming.parseFields` turns the
  frame into a key-to-value map, and `GroupFrameCodec.decode` reads only the keys it names, so an older build decodes the frame exactly as
  before and ignores `root`, `pieceSize`, `swarm` and `rootSig`. It would then park the frame and wait for a FILE_START that the swarm never
  sends; that is why the fields go only to `sw1` peers.

## Group membership v1 (ADR-073, ADR-074, ADR-075; PROPOSED 2026-10-04, not implemented)

**Status:** GM-1 and GM-2 invite format implemented (2026-10-05): key derivation, commitment, mutual proof primitives, and invite codec in `:core:security`. Golden vectors below. Plan: `GROUP-SWARM-IMPLEMENTATION-PLAN.md` track GM (7B) and 5.7.

### Byte conventions

- Integers are **big-endian**, like the v2 canonical bytes. (FSW1 is little-endian; do not mix them.)
- `lp(x)` is a `u16` big-endian length followed by the bytes. Text is UTF-8.
- An ASCII tag at the start of a hashed or MACed string has no length prefix: it is fixed and comes first.
- Signed statements use the v2 canonical rules and ECDSA P-256 / SHA-256 with the signer's identity key.
- In text frames, binary values are standard base64 (as in the bundle); in the invite link, base64url without padding.

### Keys and commitment

```text
secret_e   = 32 bytes from the platform's secure random, one per epoch e (e >= 1). Never typed, never derived from text.
K_auth(e)  = HKDF-SHA256(ikm = secret_e, salt = empty, info = "flash-gsa-v1"     ‖ lp(groupId) ‖ u32 e, length 32)
K_beacon(e)= HKDF-SHA256(ikm = secret_e, salt = empty, info = "flash-gbeacon-v1" ‖ lp(groupId) ‖ u32 e, length 32)   GM-8b, optional
commit(e)  = SHA-256("flash-gs-commit-v1" ‖ lp(groupId) ‖ u32 e ‖ secret_e)
```

Golden vectors (inputs: `secret` = bytes `00..1f`, `groupId` = `"g2-6f01129f28cff84f8ef67cb9d1970499"`, `epoch` = 1; produced by independent reference and asserted in `GroupMembershipGoldenVectorTest`):

- `K_auth(1)` = `b0d2233864667368d2cb1403155bfc24d419fc76afe1622af35c260760ae05c6`
- `K_beacon(1)` = `273172fb813d6be217a75698449672e003cdea570e000bc07b7664d3890fafcd`
- `commit(1)` = `316155ba2ffa797afffc435dbba71497d1146322565fdc77e22bf66d9a9f728e`

### Invite

```text
flash://g/1/<base64url(payload), no padding>

payload:
version      u8   1 (the "1" in the link repeats it)
groupId      lp   ≤ 64 bytes, starts with "g2-"
epoch        u32  ≥ 1
secret       32B
groupName    lp   1..80 characters (GroupPolicy.MAX_GROUP_NAME_LENGTH), ≤ 320 bytes
inviterId    lp   ≤ 128 bytes
inviterFp    32B  SHA-256 of the inviter's identity public key (the fingerprint the trust store pins)
hintCount    u8   0..3
hints        hintCount × lp, each "host:port", ≤ 64 bytes
issuedAtMs   u64  display only
```

- About 250 bytes, about 340 characters as a link.
- Decoding never throws: an unknown version, an oversize field, a count over its limit or trailing bytes give no invite.
- Not signed: whoever can change an invite already holds the secret.
- The link is a secret. The app never puts it into a log, a notification or a chat on its own; the user may share it anywhere (GINV-1).

Golden vector (inputs: `secret` = bytes `00..1f`, `groupId` = `"g2-6f01129f28cff84f8ef67cb9d1970499"`, `epoch` = 1, `groupName` = `"Flash Core Team"`, `inviterId` = `"alpha-tester-device"`, `inviterFp` = bytes `20..3f`, `hints` = `["192.168.1.100:8765", "10.0.0.1:8765"]`, `issuedAtMs` = 1728123456789; asserted in `GroupMembershipGoldenVectorTest`):

- Binary payload (188 bytes):
  `01002367322d366630313132396632386366663834663865663637636239643139373034393900000001000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f000f466c61736820436f7265205465616d0013616c7068612d7465737465722d646576696365202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f0200123139322e3136382e312e3130303a38373635000d31302e302e302e313a38373635000001925c2f4d15`
- URI (263 characters):
  `flash://g/1/AQAjZzItNmYwMTEyOWYyOGNmZjg0ZjhlZjY3Y2I5ZDE5NzA0OTkAAAABAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8AD0ZsYXNoIENvcmUgVGVhbQATYWxwaGEtdGVzdGVyLWRldmljZSAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_AgASMTkyLjE2OC4xLjEwMDo4NzY1AA0xMC4wLjAuMTo4NzY1AAABklwvTRU`

### Capability

`gs1` in the HELLO `caps` field (SW-2). No `FLASH_GMEM` frame is ever sent to a peer whose HELLO lacked it.

### Frames: prefix `FLASH_GMEM`

Text frames with the `FlashTextFraming` field rules. Every frame carries `op`, `groupId` and `from`, and `from` must equal the transport
peer, otherwise the frame is dropped. An unknown `op` is ignored. Every field has a limit; decoding never throws, and a frame over a limit is
dropped.

| `op` | Fields after `op`, `groupId`, `from` | Sent by | Phase |
|---|---|---|---|
| `hello` | `epoch`, `nonce` (16 bytes) | the initiator (a joiner) | GM-3 |
| `challenge` | `epoch`, `nonce` (16 bytes), `mac` (32 bytes) | the responder | GM-3 |
| `proof` | `epoch`, `mac` (32 bytes) | the initiator | GM-3 |
| `result` | `ok` (`true`/`false`), `reason` (`ok` / `failed` / `stale`) | the responder | GM-3 |
| `stale` | `epoch` (the responder's current), the rotation notice keys below | a responder with a newer epoch, **only to an active roster member's live key** | GM-6 |
| `secretRequest` | `epoch` | a member that lacks the secret of `epoch` | GM-6 |
| `secret` | `epoch`, `secret` (32 bytes) | a member that holds it (handover rules below) | GM-6 |
| `join` | `epoch`, `subjectId`, `subjectKey` (SPKI, ≤ 256 bytes), `label` (≤ 80 characters), `requestedAt`, `sig` | the joiner; members relay it unchanged except `from` | GM-4 |
| `decision` | `subjectId`, `subjectKey`, `decision` (`refused`), `reason` (`refused` / `full`), `decidedBy`, `decidedAt`, `sig` | an admin; relayed unchanged except `from` | GM-4 |
| `preview` | the charter keys of the bundle (`cName`, `cOwner`, `cOwnerKey`, `cCreated`, `cNonce`, `cProto`, `cSig`), `memberCount` (≤ 20), `n0`..`n19` (labels) | a member, to a joiner whose proof passed | GM-4 |

Approval has no frame of its own: it is the admin-signed member certificate in an ordinary `FLASH_GROUP action=bundle`.

### The proof

```text
T(role) = "flash-gsp-v1" ‖ role ‖ lp(fpI) ‖ lp(fpR) ‖ lp(groupId) ‖ u32 epoch ‖ nonceI(16) ‖ nonceR(16)
role    = 0x49 ('I') for the initiator's MAC, 0x52 ('R') for the responder's
fpI/fpR = SHA-256 of the identity public key each side presents in THIS live TLS session (each side computes both from the session)
```

Golden vectors (inputs: `fpI` = bytes `10..2f`, `fpR` = bytes `30..4f`, `nonceI` = bytes `a0..af`, `nonceR` = bytes `b0..bf`, asserted in `GroupMembershipGoldenVectorTest`):

- `T('R')` (154 bytes):
  `666c6173682d6773702d7631520020101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f002367322d366630313132396632386366663834663865663637636239643139373034393900000001a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf`
- `challenge.mac` = `afa3ae3ad3da3a316785d560d96da2f137f3bf0ad635820c16723481a9f3431c`
- `T('I')` (154 bytes):
  `666c6173682d6773702d7631490020101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f002367322d366630313132396632386366663834663865663637636239643139373034393900000001a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf`
- `proof.mac` = `1880e5348e5f0ef6135d0a4742f44fcc2442bbe390fc403a99744eefc236b367`


1. MACs are compared in constant time. A proof instance is one-shot and times out after 20 seconds per (peer, group).
2. At most 5 failed proofs per peer per 10 minutes; after that `hello` from that peer is ignored until the window passes. The session is never
   dropped for this.
3. **Responder privacy.** A responder that does not hold the group, or not the epoch named in `hello`, still answers `challenge` (random nonce,
   random MAC) and then `result ok=false reason=failed`, so a stranger who knows a group id cannot learn who is in it. It answers
   `reason=stale` only after a valid proof at an older epoch it still holds, and sends `stale` with the notice only to an active roster member.
4. **An epoch number is a claim.** No device changes any state because of the epoch in a `hello`; rotations are learned only from signed
   notices.
5. A passed proof is a fact of the live session, used only for `join` and `preview` (GINV-2). It grants no chat, call or file traffic and is
   never persisted.

### Join request and decision

| Statement | Tag | Fields in order |
|---|---|---|
| join request | `flash-gjoin-v1` | groupId, epoch, subjectId, subjectKey (bytes), label, requestedAt |
| refusal | `flash-gjdec-v1` | groupId, subjectId, subjectKey (bytes), decision, reason, decidedBy, decidedAt |

- The first member to receive a `join` checks: the proof passed on this session for this group; `subjectId` is the transport peer;
  `subjectKey` is the live TLS key; the signature verifies. A relay checks the signature only. Requests are kept per
  `(groupId, subjectId, subjectKey)` and expire after 7 days.
- A `decision` counts only when `decidedBy` is an admin in the stored roster (the admin lookup of `checkCert`, ADR-063).
- Policy `OPEN` never auto-approves a key this group has tombstoned (GINV-5). A full group (20) refuses with `reason=full`.

### Charter trust root (changes bundle receiver rules 1 and 2 in GM-4)

An unknown `g2-` group is accepted when the charter is valid, the device's own certificate in the bundle is valid, active and for its key,
**and** this device is paired with the owner **or** holds an accepted invite for exactly that group id (state not `REFUSED` / `ABANDONED`). With
an invite, the sender of the bundle need not be paired. A bundle for a group with neither root is refused (GINV-3), however valid its
signatures.

### Rotation notice

Carried as extra keys on `FLASH_GROUP action=bundle` (with the removal it belongs to) and on `op=stale`:

```text
rotNew=<epoch> rotPrev=<epoch> rotCommit=<64 hex> rotReason=<REMOVAL|MANUAL|UPGRADE> rotAdmin=<id> rotId=<32 hex>
rotRmCount=<0..64> rotRm0=<id> … rotSig=<b64>
```

- Statement `flash-grot-v1`: groupId, newEpoch, prevEpoch, commit (hex), reason, adminId, rotationId, removedCount, then each removed id as a
  field.
- Valid only when an admin signed it. `newEpoch > prevEpoch`; an upgrade of an existing group has `prevEpoch = 0`, `newEpoch = 1`.
- A device keeps the valid notice with the highest `newEpoch`; for the same `newEpoch`, the smaller `rotId` wins. An admin whose rotation lost
  rotates again with its removals.
- **The notice never contains the secret.**

### Secret handover (`op=secret`)

- Sent only inside a live TLS session, to a peer that passes `allows(CHAT)` at that moment and is not named in `rotRm` of any stored notice,
  and only for an epoch the sender holds.
- The receiver stores it only when it holds a valid notice for that epoch and `commit(epoch)` matches; otherwise it drops the frame and logs
  the group id and epoch only.

### Group settings (ADR-074)

Carried as extra keys on the bundle:

```text
setVer=<n> setPolicy=<APPROVE|OPEN> setSharers=<ALL|ADMINS> setMax=<2..20> setSwarm=<true|false> setMayAdd=<true|false>
setOpId=<uuid> setSigner=<admin id> setSig=<b64>
[setHist=<NONE|H24|D7|D30|ALL> setHistSig=<b64>]      (ADR-105; only when the ceiling is not D30)
```

- Statement `flash-gset-v1` (`setSig`): groupId, version, joinPolicy, inviteSharers, maxMembers, swarmServing, membersMayAdd, opId, signerId.
  **It never covers the history ceiling** (ADR-105), so every build, including one that predates ADR-100, verifies it.
- ADR-100 history ceiling, signed by ADR-105: `setHist=<NONE|H24|D7|D30|ALL>` and `setHistSig`, written **only when the ceiling is not
  the default `D30`**, so default settings stay byte-identical on the wire. `setHistSig` is the same admin's signature over the statement
  `flash-gsethc-v1`: groupId, version, opId, signerId, ceiling name (each length-prefixed like every canonical field). Binding the group,
  version, opId and signer stops a member splicing a genuine ceiling signature onto another settings object. A receiver that knows
  the ceiling requires a valid `setHistSig` by the same key as `setSig` for any non-default ceiling and refuses the whole object
  otherwise; a missing `setHist` means `D30`. A `setHist` name this build does not know (a later ceiling) reads as `D30` without
  discarding the other settings.
- Superseded: the first ADR-100 draft signed a non-default ceiling by swapping `setSig` to a `flash-gset-v2` statement. That made every
  build from before ADR-100 reject the entire object (the join policy, sharers, member cap and swarm switch with it) the moment an admin
  chose a ceiling. The `flash-gset-v2` tag was never released and no build verifies it.
- Mixed fleet (old build = before ADR-100 or ADR-105, new build = this one):

  | Sender | Receiver | Result |
  |---|---|---|
  | new, default ceiling | old | identical bytes to before; applies |
  | new, non-default ceiling | old | v1 `setSig` verifies; the other settings apply; `setHist`/`setHistSig` are ignored, so the old build keeps its old history window |
  | old | new | no `setHist`: the ceiling reads as `D30`; applies |
  | new, non-default ceiling | new | applies, ceiling enforced by the receiver, which also relays it |
  | new relays through an old build | new | the old build re-encodes the stored object without the ceiling; the same operation (equal version and opId) arriving complete wins over the stripped copy (`settingsWins`), so the ceiling is restored |
  | old admin edits after a new admin set a ceiling | all | the edit is version + 1 and carries no ceiling, so the group returns to `D30` until a new-build admin sets it again |
  | member splices a ceiling signature onto another object | new | refused: the ceiling statement binds version, opId and signer |

  The stored form (no schema change, Room stays at 13) is the existing `historyCeiling` column holding `D7~<b64 sig>`.
- Valid only when an admin signed it. The highest `setVer` wins; on a tie, the smaller `setOp`. Device-local preferences never appear on any
  wire.

### Compatibility with older builds ([code], read 2026-10-04)

- **Extra keys on a bundle** (`rot*`, `set*`): `parseFields` builds a map and `decodeBundle` reads only the keys it names (`certCount`, the
  charter keys and `c<i>…`), so an older build applies the bundle exactly as before and ignores the notice and the settings.
- **The `FLASH_GMEM` prefix:** `GroupFrameCodec.decode` returns null for an unknown prefix. On Android the dispatch then logs "Received
  unrecognized text frame (n chars)", the length only, never the text. On the desktop and in the library facade the frame falls through to the
  transfer text decoder; that it is dropped there without effect was not traced line by line, so GM-3 adds a test. None is sent to a peer
  without `gs1` anyway.
- An older member still trusts members who joined with the secret: their admin-signed certificates reach it as ordinary vouches (ADR-044 V2;
  test in GM-4, ripple 40).


## Hub relay wire v2 and hub REST contract v1 (ADR-107, ADR-109, ADR-110; 2026-10-12)

The enterprise hub (`:server`) and its clients share ONE definition of this contract: the Kotlin module `:core:hub-protocol`. This section
is the human-readable form; the code (`HubContract`, `HubErrorCodes`, `RelayFrame`, `RelayFrameCodec`, `rest/RestDtos.kt`, `certs/HubAuth.kt`)
and the golden vectors are authoritative if they ever disagree, and a disagreement is a bug to record in `logs/errors.md`.

`RELAY_WIRE_VERSION = 2`, `REST_API_VERSION = 1`. The v1 relay (identity in the query string, no handshake) is gone; a v1 client cannot connect.

### Canonical bytes (`AuthCanonicalWriter`)

Every signed statement is a sequence of fields, each written as a 4-byte big-endian length followed by the bytes. The first field is always the
domain tag (UTF-8). Text is UTF-8; `long` is 8 bytes big-endian; `int` is 4 bytes; `stringSet` / `longSet` are sorted, then a count field,
then the members. The signature is ECDSA P-256 / SHA-256, DER encoded, base64 on the wire. The signature field is never part of the bytes it signs.

| Statement | Tag | Fields in order | Signed by |
|---|---|---|---|
| Device certificate | `flash-device-cert-v1` | deviceId, subjectPublicKey, role, department, validFrom, validTo, serial, issuerPublicKey | the hub's delegated key |
| Delegation | `flash-delegation-v1` | rootPublicKey, delegatePublicKey, scopes (set), validFrom, validTo, maxDevices (int), serial | the organisation root key |
| Revocation list v3 (ADR-129) | `flash-revocation-v3` | version, audience, revokedSerials, revokedDeviceHashes, revokedDelegationSerials, issuerPublicKey | root, or a delegate with `revoke:member`; clients since 2026-10-14 read it |
| Revocation list | `flash-revocation-v2` | version, revokedSerials (set), revokedDeviceIds (set), revokedDelegationSerials (set), issuerPublicKey | root, or a delegate with `revoke:member` |
| Relay / REST authentication | `flash-hub-auth-v1` | serverId, serverNonce, clientNonce, deviceId, certSerial | the device key |
| Enrolment proof of possession | `flash-hub-enroll-v1` | token, deviceId, subjectPublicKey, serverId | the device key |
| Certificate renewal | `flash-hub-renew-v1` | serverId, serverNonce, clientNonce, deviceId, currentCertSerial, subjectPublicKey | the device key |
| Organisation member statement (Plan B) | `flash-org-member-v1` | deviceId, subjectPublicKey (raw bytes), departmentId, role, issuedAtMs (long), statementSerial (long) | delegate with `enrol:member` / `enrol:admin` scope, chained to root |

`serverId` is the realm: the upper-case hex SHA-256 of the organisation ROOT public key (the same value as the invite's `root=`). It is the
audience of every device signature, so a signature made for one organisation is useless at another. One real signed certificate is pinned as a
golden vector and verified by the shared module, the client verifier and the server verifier.

### Certificate rules (`DeviceCert.checkChain`)

Chain: `DeviceCert` (issued by the delegate key) + `DelegationCert` list (root to delegate). Checked in this order, first failure wins:
well-formed, role in the closed set `member` / `dept_admin` / `admin`, signatures, issuer equals the delegate key, delegation signed by the
pinned root, delegation scope allows the role being issued, leaf window inside the delegation window, revocation list, validity windows
(a clock-skew tolerance of 60 s applies at BOTH ends), then the expiry grace the caller passes. Scope rule (one wildcard rule): a scope
matches exactly, or is `*`, or `prefix:*`; `admin` is never granted by a wildcard and needs an explicit `enrol:admin`. A leaf is issued
with `validFrom = max(now - 10 s, delegation.validFrom)` and `validTo = min(now + 7 days, delegation.validTo)`.

Chain failures map to wire codes: revoked -> `ERR_REVOKED`; expired, not yet valid, delegation expired -> `ERR_EXPIRED`; everything else -> `ERR_AUTH`.

### Organization Member Statement (Plan B)

The offline organization trust architecture (Plan B, ADR-134/135) relies on signed member statements (`OrgMemberStatement`) distributed via directory synchronization (`GET /api/v1/directory/sync`) and cached in Room database schema 14 (`org_directory_member`).

#### Canonical Binary Serialization
Signed bytes are constructed strictly via `AuthCanonicalWriter(tag = "flash-org-member-v1")`. Every field is prefixed with a 4-byte big-endian integer denoting byte length:
1. `tag`: String `"flash-org-member-v1"` (UTF-8, length-prefixed)
2. `deviceId`: String (UTF-8, length-prefixed)
3. `subjectPublicKey`: raw SPKI byte array (length-prefixed)
4. `departmentId`: String (UTF-8, length-prefixed)
5. `role`: String (`member`, `dept_admin`, `admin`, length-prefixed)
6. `issuedAtMs`: 8-byte big-endian Long (epoch milliseconds)
7. `statementSerial`: 8-byte big-endian Long (monotonic per-device serial)

`issuerSignature` is an ECDSA P-256 / SHA-256 signature in DER format (Base64-encoded in REST JSON DTOs) generated by the hub's active delegate signing key over the above canonical bytes. It is not part of the bytes it signs.

#### Verification Rules (`OrgMemberStatement.verify` / `verifyWithDelegation`)
Evaluated in strict fail-closed order:
1. **Role Validity:** `role` must be a member of the closed set `HubRoles.ALL_ROLES` (`member`, `dept_admin`, `admin`).
2. **Malformed Check:** `deviceId`, `subjectPublicKey`, `rootPublicKey`, and `issuerSignature` must be non-blank.
3. **Future Timestamp Guard:** `issuedAtMs` must not exceed `nowMs + 5 * 60 * 1000L` (5-minute maximum server clock skew allowance); if exceeded, fails with `NOT_YET_VALID`.
4. **Delegation Chain Matching:** Scans `delegationChain` for a certificate whose `delegatePublicKey` verifies `issuerSignature` and whose `rootPublicKey` equals the locally pinned organisation root.
5. **Scope Authorization:** The matching delegation must carry scope `enrol:member` (or an explicit `enrol:admin` if `role == admin`).
6. **Issue-Time Validity (B0.5 Resolution):** The delegation must satisfy `delegation.isValidAt(issuedAtMs, clockSkewToleranceMs = 60_000L)`. The statement does not expire when the hub rotates or expires its delegation cert; offline freshness is governed separately by the client's local lease (`offlineGraceDays`, default 30 days).
7. **Revocation Check:** If a `RevocationList` is supplied, asserts that neither the `deviceId` nor the delegation `serial` is revoked.

### Relay handshake (`GET /flash-relay`, WebSocket, no query string)

```text
client                                   hub
  | ---- WebSocket upgrade (no identity) ---> |
  | <--- FLASH_HUB_CHALLENGE {v, serverId, serverNonce, issuedAt}
  | ---- FLASH_HUB_HELLO {v, deviceId, clientNonce, name, deviceCert, delegationChain, signature} (within 10 s)
  | <--- FLASH_HUB_WELCOME {v, principal, serverTime, limits, resumeToken}      (success)
  | <--- FLASH_ERROR {code, message} then close                                 (any failure)
```

- `signature` signs `flash-hub-auth-v1` over (serverId, serverNonce, clientNonce, deviceId, certSerial). The nonce is per socket and single use.
- A client that has a pinned realm refuses to answer a CHALLENGE whose `serverId` differs.
- Before WELCOME the hub accepts only a HELLO (16 KiB max); a silent client gets `ERR_HANDSHAKE_TIMEOUT` and a close at 10 s; a client that
  never completes the handshake can send no application frame. If no authenticator is configured the hub refuses every connection (fail closed).
- `HubPrincipal {deviceId, role, departmentId, certSerial}` in WELCOME is the identity the hub stamped on the session; `limits` carries
  `maxFrameBytes`, `maxPayloadBytes`.

### Relay frames after WELCOME

Frames are JSON text objects with a discriminator member `"t"` (the `FLASH_*` name). Unknown members are ignored; an unknown `t` is dropped,
not fatal. Limits: 256 KiB per frame, 64 KiB payload; larger frames are refused with `ERR_TOO_LARGE`.

| Frame (`t`) | Direction | Members | Meaning |
|---|---|---|---|
| `FLASH_RELAY` | client -> hub | `to`, `id`, `type`, `sentAt`, `payload`, `group` (optional) | Send. There is NO sender member: the hub stamps it. With `group` set, `to` is blank and ignored and the hub fans out to the members (ADR-112). |
| `FLASH_DELIVER` | hub -> client | `from`, `id`, `type`, `sentAt`, `payload`, `replay`, `group` (optional) | A message; `from` is the authenticated sender; `replay` marks a mailbox drain (a redelivery until the client acknowledges); `group` is set for a group message. |
| `FLASH_RECV_ACK` | client -> hub | `msgId` | The client durably stored the message (only this may delete the mailbox row). |
| `FLASH_ACK` | hub -> sender | `id`, `to`, `status`, `ts`, `reason`, `delivered`, `queued`, `failed` | For a group send `to` is the group id and the three counts are filled (old hubs send none: default 0). `status` is `DELIVERED`, `QUEUED`, `OFFLINE_UNDELIVERABLE`, `DUPLICATE_QUEUED`, `QUOTA_EXCEEDED`, `REJECTED`, `ERROR`. |
| `FLASH_QUEUE_STATE` | hub -> sender | `id`, `to`, `state`, `ts` | Later state of a queued message: `DELIVERED`, `EXPIRED`, `DROPPED`, `UNKNOWN`. |
| `FLASH_META` | both | `action`, `conversationId`, `to`, `from`, `ts`, `pad` | Typing / read style signals with padding; the hub overwrites `from`. |
| `FLASH_PING` / `FLASH_PONG` | both | `ts` | Liveness. |
| `FLASH_REVOKED` | hub -> client | `reason`, `serial` | Terminal: this certificate is revoked; the client must not reconnect with it. |
| `FLASH_KICK` | hub -> client | `reason` | The hub is closing this session: `superseded` (the same device connected again), `server_shutdown`, `slow_consumer`, `mailbox_unavailable`, `revoked`. A `superseded` / `server_shutdown` kick is not an error: reconnect with back-off. |
| `FLASH_ERROR` | hub -> client | `code`, `message` | Error with a code from the table below and a fixed sentence. |

### Relay delivery semantics, limits and operations (ADR-112, 2026-10-12)

**Delivery is at-least-once, recipient driven.** A stored message leaves the hub only on the recipient's `FLASH_RECV_ACK`.

1. Recipient online and caught up: the hub hands `FLASH_DELIVER` (`replay=false`) to the session and keeps the message in the session's
   in-flight table; the sender gets `FLASH_ACK DELIVERED` ("handed to the recipient's connection", NOT "stored by the recipient"). On
   `FLASH_RECV_ACK` the hub tells the sender `FLASH_QUEUE_STATE DELIVERED`. If the session ends first, the in-flight messages go to the mailbox.
2. Recipient offline, draining or over its in-flight cap (256): the message is stored (`FLASH_ACK QUEUED`), a duplicate of a stored
   `(sender, recipient, id)` answers `DUPLICATE_QUEUED` and is not stored twice.
3. On connect the hub replays the mailbox in arrival order, 100 per page, `replay=true`, and keeps replaying until each row is acknowledged;
   a live message arriving meanwhile is queued behind the stored ones, so order is preserved.
4. A message not acknowledged within `mailboxTtlDays` (server clock) expires: the row is deleted and an online sender gets
   `FLASH_QUEUE_STATE EXPIRED`; a message dropped for another reason (storage failure after the ack) gets `DROPPED`.

**Client obligations:** deduplicate by the message `id` (the same id arrives again after a lost RECV_ACK or a reconnect), send
`FLASH_RECV_ACK` only after the message is durably stored, treat `DELIVERED` as a hint and `QUEUE_STATE DELIVERED` as the confirmation.

**ACK reasons.** `REJECTED` + `reason` `ERR_UNKNOWN_RECIPIENT` (recipient not enrolled), `ERR_NOT_MEMBER` (group send by a non-member, or a group that
does not exist: both answer identically), `ERR_PROTOCOL`; `QUOTA_EXCEEDED` + `reason` = which quota (recipient, sender, global, payload);
`OFFLINE_UNDELIVERABLE` when the hub has no mailbox. Group send: `status QUEUED` with `reason "partial"` when only some members were reached,
`delivered + queued + failed` = members other than the sender.

**Group send.** `FLASH_RELAY {group: "<id>", to: "", ...}`: the sender must be a member (the hub checks the directory); each member except the sender
gets `FLASH_DELIVER` with `group` set and `from` = the sender; there is ONE aggregate `FLASH_ACK` (`to` = group id). `FLASH_META` with a group
is relayed only for a member (`ERR_NOT_MEMBER` otherwise).

**Frames a client must never send** (hub-originated): `FLASH_ACK`, `FLASH_DELIVER`, `FLASH_QUEUE_STATE`, `FLASH_KICK`, `FLASH_REVOKED`,
`FLASH_HUB_CHALLENGE`, `FLASH_HUB_WELCOME`: the hub answers `FLASH_ERROR ERR_PROTOCOL` ("Unexpected frame.") and does not forward them.

**Limits (defaults, `RelayPolicy`, `MailboxLimits`).**

| Limit | Default | Result when exceeded |
|---|---|---|
| Handshake | 10 s | `ERR_HANDSHAKE_TIMEOUT`, close |
| Frame / payload | 256 KiB / 64 KiB | `ERR_TOO_LARGE` (a WebSocket frame above the engine limit closes the connection) |
| Open connections, per IP | 5000 / 100 | `ERR_RATE_LIMIT`, close, before the handshake |
| Frames per session | 60/s, burst 120 | `ERR_RATE_LIMIT`; after 200 rejected frames the session is closed |
| Outbound queue per session | 256 frames, 10 s send timeout | the session is closed (`slow_consumer`), its messages go to the mailbox |
| Unacknowledged live messages per session | 256 | further messages go through the mailbox |
| Mailbox per recipient | 1000 rows / 50 MiB | `QUOTA_EXCEEDED` |
| Mailbox per sender | 5000 rows / 100 MiB | `QUOTA_EXCEEDED` |
| Mailbox global | 1,000,000 rows / 5 GiB | `QUOTA_EXCEEDED` |
| Mailbox TTL | 7 days (`ServerConfig.mailboxTtlDays`) | expiry + `QUEUE_STATE EXPIRED` |
| Client `sentAt` | clamped to +-5 min of the hub clock for ordering | never used for expiry |

**Revocation of a live connection.** A device revoked by certificate serial OR by device id is refused at the handshake (`ERR_REVOKED`), is
checked again before every inbound frame, and is cut at once when an admin revokes it (`FLASH_REVOKED`, then close).

**One connection per device.** A second connection by the same device supersedes the first (`FLASH_KICK superseded` to the old one). The old
connection's cleanup never removes the new registration.

**Shutdown.** The hub stops accepting connections, sends `FLASH_KICK server_shutdown` to every session, moves unacknowledged live messages to the
mailbox, then closes. A client treats it like any disconnect.

**Operational endpoints.** `GET /health` is public and returns only `{"status":"ok"|"degraded"|"down"}` (HTTP 503 when the database does not
answer). `GET /health/detail` (JSON) and `GET /metrics` (Prometheus text) require the management bearer token or a loopback caller without
proxy headers; otherwise `403 ERR_FORBIDDEN`. Unhandled errors answer `500 ERR_INTERNAL` with a fixed sentence and a `correlationId`.

### Hub REST contract (`/api/v1`)

JSON bodies, UTF-8, at most 64 KiB. Success bodies are the DTOs in `rest/RestDtos.kt`; every error body is
`{"code": "<ERR_*>", "message": "<fixed sentence>", "correlationId": "<id>"}` (`message` is never exception text, SEC-16), and the
`X-Correlation-Id` header is echoed. Bearer: `Authorization: Bearer <token>`.

| Method and path | Auth | Request | Response | State |
|---|---|---|---|---|
| `GET /health` | none | none | `HealthLite` (status only) | served (Phase 2 SERVER-B) |
| `GET /health/detail`, `GET /metrics` | management guard | none | operational JSON / Prometheus text (see above) | served |
| `GET /enroll` | none (rate limited) | `token` query (a `server` query is ignored) | browser landing page; unknown, expired, used and revoked tokens answer identically (SEC-17) | served |
| `GET /api/v1/hub` | none | none | `HubInfo` | served (Phase 2 SERVER-A) |
| `GET` or `POST /api/v1/auth/challenge` | none | none | `AuthChallengeResponse {serverId, serverNonce, issuedAt, ttlSeconds}` (TTL 60 s) | served |
| `POST /api/v1/auth/verify` | signature | `AuthVerifyRequest {serverNonce, clientNonce, deviceId, deviceCert, delegationChain, signature}` | `AuthVerifyResponse {status, token, expiresAt, ttlSeconds, principal}` (token TTL 15 min) | served |
| `POST /api/v1/enroll` | invite token + PoP | `EnrollRequest {token, deviceId, subjectPublicKey, friendlyName, proof}` | `EnrollResponse {status ENROLLED, serverId, rootFingerprint, rootPublicKey, deviceCert, delegationChain, hubName}` | served |
| `POST /api/v1/enroll/renew` | signature | `RenewRequest {serverNonce, clientNonce, deviceId, deviceCert, delegationChain, signature}` | `EnrollResponse {status RENEWED, ...}` with a NEW serial | served (Phase 2 SERVER-A) |
| `GET /api/v1/revocations` | none (rate limited per client address) | none | signed `RevocationList` (`version` only grows; signed by the hub's delegate key, which the client checks against the delegation certificate in its stored chain); `ERR_NOT_FOUND` until the first revocation has been signed. PUBLIC since ADR-124: a revoked device has no bearer and must still be able to confirm its own revocation | served (Phase 2 SERVER-A, ADR-113; public ADR-124) |
| `POST /api/v1/device/unenroll` | bearer | none | the device revokes its own certificates and frees its id | served (Phase 2 SERVER-A) |
| `GET /api/v1/directory/me` | bearer | none | the caller's own `MemberDto` and department | served |
| `GET /api/v1/directory/sync` | bearer | `since` (or `sinceVersion`), `epoch` (or `directoryEpoch`), `limit` (or `pageSize`), `cursor` optional | `DirectorySyncDto`: synchronisation stream of departments, members with signed `OrgMemberStatement`, tombstones, and active delegation chain for offline organization trust; rate-limited per device to a burst of 60 pages then 1 per second (`429 ERR_RATE_LIMIT`; one 20,000-member full sync is 40 pages) | served (Plan B Phase B1) |
| `GET /api/v1/directory/departments` | bearer | none | list of `DepartmentDto` | served |
| `GET /api/v1/directory/groups` | bearer | none (device and department come from the bearer's principal) | list of `GroupDto` | served |
| `GET /api/v1/directory/members` | bearer | `departmentId` or `groupId` query optional | list of `MemberDto`; with `groupId` the members of that group, `404` when the caller may not see the group or its member list | served |
| `POST /api/v1/groups/join-request` | bearer | `JoinRequestBody {groupId, message}` | `JoinResponse {status JOINED, PENDING or REJECTED, ...}`. A group that does not exist, is HIDDEN or is outside the caller's scope answers one constant sentence (`This group is not available to join.`), so the call is not an oracle for group ids (REV-06) | served |
| `GET /api/v1/groups/join-requests` | bearer (group admin) | `groupId` query | list of `JoinRequestDto` | served (Phase 2 SERVER-A) |
| `POST /api/v1/groups/join-requests/decision` | bearer (group admin) | `JoinDecisionBody {requestId, approve, reason}` | `JoinResponse {status JOINED on approve, REJECTED on refuse, groupId, name, requestId, reason}` (ADR-124; it used to be `OkResponse`) | served |
| `PUT /api/v1/device/sealing-key` | bearer (the device itself) | `SealingKeyRequest {sealingPublicKey, signature}`: Base64 P-256 SPKI and the Base64 DER ECDSA signature of the device identity key over `HubAuth.sealingKeyBindingBytes(deviceId, spkiBytes)` | `OkResponse`. `400 ERR_BAD_REQUEST` for a malformed or non-P-256 key or a signature that is not this device's; `403 ERR_FORBIDDEN` when the device is not ACTIVE; `409 ERR_CONFLICT` for a key this device already retired; `429 ERR_RATE_LIMIT` (burst 5, then 1 per 30 s per device); `413 ERR_TOO_LARGE` over 64 KiB. Unchanged republish is `200`; a different key rotates (audited) | served (ADR-124) |
| `DELETE /api/v1/device/sealing-key` | bearer (the device itself) | none | `OkResponse`; the key is also removed when the device is revoked, unenrolled or deleted | served (ADR-124) |
| `POST /api/v1/departments` | bearer (organisation admin) | `CreateDepartmentBody {id, name, adminDeviceIds}` | `201` `DepartmentDto`; `400 ERR_BAD_REQUEST` for an invalid id; audited as `directory.department.create` | served (REV docs round) |
| `POST /api/v1/groups` | bearer (organisation or department admin) | `CreateGroupBody {id, name, departmentId, visibility, joinMode, adminDeviceIds}` | `201` `GroupDto`; `400` for an invalid id, visibility or join mode; `403` outside the caller's scope | served |
| `PUT /api/v1/groups/{id}` | bearer (group admin) | `UpdateGroupBody {name?, visibility?, joinMode?}` | `GroupDto`; a group the caller may not see answers `404` exactly like a missing one | served |
| `DELETE /api/v1/groups/{id}` | bearer (group admin) | none | `OkResponse`; same `404` rule | served |
| `POST /api/v1/groups/members` | bearer (group admin) | `GroupMemberBody {groupId, deviceId, add}` (`add` false removes) | `GroupDto`; audited `directory.group.member.add` / `.remove` | served |

#### Directory privacy v1 (Plan A2, option C, ADR-137; owner D2 + D9)

Names, departments and online dots are org-visible by default; an admin can mark
a department **hidden**. Hidden hides listing only: directory lists, searches,
counts, cursors, group browse lists, member lists, keys and presence never reveal
a hidden department's rows to outsiders. Anyone who knows a person's id, or has
been contacted by them, can still chat (inter-department messaging is wanted).
Devices on the same Wi-Fi still see each other's device names through local
discovery. The hub operator and org admins see everything.

| Method and path | Auth | Request | Response | State |
|---|---|---|---|---|
| `GET /api/v1/directory/people?cursor=&limit=&q=&departmentId=` | bearer | `q` max 64 chars (min 2 when non-empty), `limit` default 100 max 500 (clamped), `cursor` opaque Base64url | `PeoplePageDto {people: PersonDto[deviceId, displayName, departmentId, departmentName, role, online?], nextCursor?}` — NO keys; ordered by `(displayNameFolded, deviceId)`; rate limit 30/min/device (`429`); invalid cursor/limit/q is `400` | served (A2) |
| `GET /api/v1/directory/people/{deviceId}` | bearer | known id | `PersonDto`; hidden-department members resolve (id known = allowed) with blanked department fields; missing AND not-allowed share one `404` body; miss bursts (≥10/min) are `429` + audit | served (A2) |
| `GET /api/v1/directory/departments` (changed) | bearer | none | every `DepartmentDto` the caller `canSeeDepartment` (org-wide for non-hidden); counts are `0` for hidden departments to outsiders (wire keeps non-null Ints so old clients work); `hidden` ships (old clients ignore it) | served (A2 changed) |
| `GET /api/v1/directory/members` (kept, superset) | bearer | `departmentId` or `groupId` | old scoping unchanged for old clients, plus any non-hidden `departmentId` is listable; keys per D9 (`DIRECTORY_KEYS_ORG_WIDE=true`: org-wide for non-hidden, insiders-only for hidden); presence blanked (false) for hidden outsiders | served (A2 changed) |
| `GET /api/v1/directory/groups` (changed) | bearer | none | `listVisibleGroups` AND the hidden-department filter (`canSeeGroup`) | served (A2 changed) |
| Plan B sync `/directory/sync` (changed) | bearer | unchanged | member rows use `canListMember` (hidden never delivered to outsiders); `DepartmentSyncDto.hidden` now carries the flag; every `hidden` change bumps `directoryEpoch` (full resync) | served (A2 changed) |

### Announcements API (Plan A3, ADR-138)

**Honesty Notice (ADR-138):**
Announcements are stored and broadcast in **plain text** on the hub. Hub administrators can read all announcements. Encryption is NEVER claimed in code, UI strings, or documentation.

**Scoping and Hidden Departments:**
- `scope`: `"org"` (organization-wide) or `"department"`.
- Org administrators can post organization-wide announcements or to any department.
- Department heads can post ONLY to their assigned department.
- Hidden departments (Plan A2): announcements belonging to a hidden department are NEVER delivered to outsiders in feeds, search queries, or direct ID lookups.

**Limits & Optimistic Concurrency:**
- Title: max 120 Unicode code points (sanitised via `AnnouncementText`).
- Body: max 4000 Unicode code points (sanitised via `AnnouncementText`).
- Pins: max 3 pinned announcements per scope. Exceeding 3 returns `409 ERR_PIN_LIMIT`.
- Concurrency: Updates specify `expectedSeq`. A mismatch returns `409 ERR_CONFLICT`.
- WebSocket push: When an announcement is created, updated, deleted, or pinned, the hub broadcasts an `AnnouncementEvent` frame to active relay sessions advertising the `ann1` feature (filtered by department scoping).

| Method and path | Auth | Request | Response | State |
|---|---|---|---|---|
| `GET /api/v1/announcements?since=&q=&scope=&departmentId=&before=&limit=` | bearer | `since` seq for delta sync (tombstones returned), or `q` search query (max 64 chars), or filters | `AnnouncementFeedDto` (if `since`) or `AnnouncementSearchResponseDto` | served (A3) |
| `GET /api/v1/announcements/{id}` | bearer | announcement ID | `AnnouncementItemDto` (404 if deleted or caller lacks access) | served (A3) |
| `POST /api/v1/announcements` | bearer | `PostAnnouncementRequestDto` | `201 Created` with `AnnouncementItemDto` | served (A3) |
| `PUT /api/v1/announcements/{id}` | bearer | `UpdateAnnouncementRequestDto` | `AnnouncementItemDto` (409 if `expectedSeq` mismatch) | served (A3) |
| `DELETE /api/v1/announcements/{id}` | bearer | announcement ID | `AnnouncementItemDto` tombstone (soft deleted, seq incremented) | served (A3) |
| `POST /api/v1/announcements/{id}/pin` | bearer | announcement ID | `AnnouncementItemDto` (409 `ERR_PIN_LIMIT` if already 3 pinned) | served (A3) |
| `POST /api/v1/announcements/{id}/unpin` | bearer | announcement ID | `AnnouncementItemDto` | served (A3) |

Keys (D9, `DIRECTORY_KEYS_ORG_WIDE=true`): identity + sealing keys are org-visible
for non-hidden departments and never for hidden ones to outsiders (delivered only
to members/admins of that department, through the lookup by known id, and in the
Plan B Layer 2 handshake). Compatibility: old client + new hub keeps working
(old routes answer as before for the own department; new `hidden` field ignored);
new client + old hub gets `404` on `/directory/people*` and shows "Your company
server is out of date. Some people may not be listed."

Since Phase 2 SERVER-A (SEC-03, ADR-111) EVERY `/api/v1` route except `enroll`, `enroll/renew`, `auth/challenge`, `auth/verify`, `hub` and `revocations` needs a
bearer (`HubApiGate`, installed first in the engine; `GET revocations` joined the public list in ADR-124); the principal is the bearer's device and department, a claimed `X-Device-Id` or
`department` is never read, and a missing or unresolvable bearer is `401 ERR_AUTH`. Bodies above 64 KiB are `413 ERR_TOO_LARGE`. The public
endpoints are rate limited per client address (`429 ERR_RATE_LIMIT`); forwarded headers are believed only from `FLASH_TRUSTED_PROXIES`.

Bearer token: 32 random bytes, base64url, opaque to the client; the hub stores only its SHA-256 and the principal; a token lives 15 minutes
and is not renewable (re-run challenge/verify). The token is re-checked against the revocation service on EVERY use, so it dies the moment its
device is revoked (ADR-113). Renewal window: from 24 h before `validTo` to 72 h after (`RenewalPolicy`); the new
certificate has a new serial, so the revocation of the old serial does not revoke the new one unless the device id is revoked.

### Error codes (`HubErrorCodes`)

| Code | Used for | Terminal for the client |
|---|---|---|
| `ERR_AUTH` | handshake or bearer failed (bad signature, wrong root, malformed chain, unknown token) | no, may retry after fixing |
| `ERR_REVOKED` | certificate or its delegation is revoked | yes |
| `ERR_EXPIRED` | certificate not yet valid or expired | no: renew, then retry |
| `ERR_RATE_LIMIT` | too many requests or rejected frames | no: back off |
| `ERR_TOO_LARGE` | frame, payload or body above the limit | no |
| `ERR_PROTOCOL` | malformed frame, frame out of order, unknown handshake state | no |
| `ERR_UNSUPPORTED_VERSION` | `v` is not 2 | yes |
| `ERR_HANDSHAKE_TIMEOUT` | no HELLO within 10 s | no |
| `ERR_NOT_MEMBER` | not a member of the group addressed | no |
| `ERR_UNKNOWN_RECIPIENT` | recipient id is not enrolled | no |
| `ERR_QUOTA_EXCEEDED` | mailbox or device quota full | no |
| `ERR_ROLE_NOT_ALLOWED` | enrolment: the hub's delegation lacks the `enrol:<role>` scope of the invite (HTTP 403, ERROR-400) | no |
| `ERR_FORBIDDEN` | authenticated but not allowed | no |
| `ERR_NOT_FOUND`, `ERR_CONFLICT`, `ERR_BAD_REQUEST` | REST resource or request problems | no |
| `ERR_INTERNAL` | hub fault; the message is a fixed sentence, details only in the hub's log | no |

Invite refusals on `POST /api/v1/enroll` (ADR-124, ERROR-330): an expired invite is `410 ERR_EXPIRED`, a used-up or revoked-after-use invite is `409 ERR_CONFLICT`, a full device quota is `429 ERR_QUOTA_EXCEEDED`, any other invalid request `400 ERR_BAD_REQUEST`, a forbidden one `403 ERR_FORBIDDEN`; the client maps them to `INVITE_EXPIRED`, `INVITE_USED`, `QUOTA_FULL`.

HTTP mapping used by the hub (`statusFor`; `ERR_EXPIRED` is 410 on `POST /api/v1/enroll` and 401 elsewhere): `ERR_AUTH`/`ERR_EXPIRED` 401, `ERR_REVOKED`/`ERR_FORBIDDEN`/`ERR_NOT_MEMBER` 403,
`ERR_NOT_FOUND`/`ERR_UNKNOWN_RECIPIENT` 404, `ERR_CONFLICT` 409, `ERR_TOO_LARGE` 413, `ERR_RATE_LIMIT`/`ERR_QUOTA_EXCEEDED` 429,
`ERR_BAD_REQUEST`/`ERR_PROTOCOL`/`ERR_UNSUPPORTED_VERSION` 400, `ERR_INTERNAL` 500.

### Enrolment invite link

`flash://enroll?server=<percent-encoded URL>&token=<percent-encoded>&root=<percent-encoded upper-case hex SHA-256 of the root key>`.
There is one parser (`EnrollmentInvite.parseOrNull`) used by Android, desktop and the chat UI; it never throws. A client refuses an invite
whose `root` is blank (it would pin nothing), and, from Phase 3, an invite whose `server` is cleartext `http://` unless the user confirms.

### Hub administration: JSON admin API, CLI and configuration (ADR-111, ADR-113, ADR-114, ADR-115; Phase 2 SERVER-A, 2026-10-13)

The admin API is for the management listener (`FLASH_ADMIN_PORT`, default 8444, bound to `FLASH_ADMIN_HOST`, default `127.0.0.1`); `/admin` answers
404 on the public port unless `FLASH_ADMIN_ON_PUBLIC_PORT=true`. All paths are under `/admin/api/v1`. Authentication is the admin session cookie
(`__Host-flash_admin_session` over TLS, `flash_admin_session` Path=/admin only in insecure mode; Secure, HttpOnly, SameSite=Strict). Every POST, PUT and
DELETE needs the header `X-CSRF-Token` equal to the session's token (returned by `login` and `session`) and a same-origin `Origin` / `Sec-Fetch-Site`.
Errors are `{"code": "...", "message": "<fixed sentence>", "correlationId": "..."}` (codes: `ERR_AUTH`, `ERR_CSRF`, `ERR_STEP_UP` plus the shared `HubErrorCodes`: `ERR_FORBIDDEN`,
`ERR_NOT_FOUND`, `ERR_BAD_REQUEST`, `ERR_RATE_LIMIT`, `ERR_TOO_LARGE`, `ERR_INTERNAL`). Bodies are at most 64 KiB.

| Area | Paths (relative to `/admin/api/v1`) | Notes |
|---|---|---|
| Setup and session | `GET setup/status`, `POST setup/begin`, `POST setup/complete`, `POST login`, `POST logout`, `GET session` | setup needs the setup token (file `<data>/setup-token.txt`) and a confirmed TOTP code; login is username + password + TOTP or a recovery code; attempts are reserved before verification |
| Overview | `GET overview` | device counts and online devices, delegation status and days left, revocation version, mailbox figures |
| Devices | `GET devices`, `GET devices/{id}`, `PUT devices/{id}` (rename, move to a department), `POST devices/{id}/revoke`, `POST devices/{id}/unrevoke` | revoke and unrevoke need step-up; revoke also kills the bearer, removes the directory entry and kicks the socket |
| Invites | `GET invites`, `POST invites`, `POST invites/bulk`, `POST invites/{id}/revoke` | real `EnrollmentService` registry; state `ACTIVE`, `USED`, `EXPIRED`, `REVOKED`; a non-member role needs step-up; the response carries the `flash://enroll` link and the QR as one SVG path |
| Revocations | `GET revocations`, `POST revocations/serial`, `POST revocations/install` | the signed list state and entries; revoking a bare serial needs step-up; `install` takes a root-signed list from `keytool revoke` (step-up `revocation.install`, REV-04) |
| Delegation | `GET delegation`, `POST delegation/reload`, `POST delegation` | install takes a delegation certificate, accepted only under the pinned root, for this hub's key, with a higher serial; both need step-up |
| Settings | `GET settings`, `PUT settings` | a change needs step-up |
| Step-up | `POST stepup/challenge`, `POST stepup/verify` | challenge bound to (admin, action, target); the admin's enrolled device signs `HubStepUp.proofBytes`; a verified challenge is used once as the header `X-Step-Up: <challengeId>` within 5 minutes |
| Directory | `GET/POST departments`, `PUT/DELETE departments/{id}`, `GET/POST groups`, `PUT/DELETE groups/{id}`, `POST groups/{id}/members`, `DELETE groups/{id}/members/{deviceId}`, `GET join-requests`, `POST join-requests/{id}/approve`, `POST join-requests/{id}/reject` | department admins act only inside their department |
| Admins | `GET/POST admins`, `POST admins/{name}/password`, `POST admins/{name}/disable`, `POST admins/{name}/enable`, `POST admins/me/password`, `POST admins/me/recovery-codes`, `POST admins/me/device` | create, reset and disable need step-up; `me/device` binds the admin to an enrolled device for step-up |
| Announcements | `GET/POST announcements`, `GET/PUT/DELETE announcements/{id}`, `POST announcements/{id}/pin`, `POST announcements/{id}/unpin`, `POST announcements/preview` | Plaintext broadcast messages (ADR-138). Org admins act org-wide or per-department; department heads act only inside their department. Preview calculates length and extracted links. |
| Sessions | `GET sessions`, `POST sessions/{id}/revoke` | |
| Audit | `GET audit`, `GET audit/head`, `GET audit/verify`, `GET audit/export` (CSV) | signed hash chain |

**Console additions (Phase 3 UI, ADR-125, 2026-10-13).** The static admin console (`docs/ui/hub-admin-console.md`) needed these, all under the rules above (cookie, CSRF, rate limits):

| Path | Notes |
|---|---|
| `POST admins/me/console-key` | body `{spki, proof, totp}`: registers THIS browser's WebCrypto ECDSA P-256 key as the administrator's step-up signer. `proof` is the signature over `flash-console-key-v1\n<username>\n<spki base64>`; a fresh authenticator code is required. The key id is `console-` + the first 16 hex digits of SHA-256 over the `spki` string. One key per administrator; an older one is dropped. Audited as `admin.consolekey.enrol` |
| `POST setup/complete` | takes an optional `consoleKey {spki, proof}`; a bad proof answers 400 without burning the setup token |
| `POST admins/{name}/totp-reset` | step-up `admin.create` with target `totp:<name>`; answers new secret, `qrSvg` and ten recovery codes, ends the target's sessions |
| `GET metrics` | the figures of `/metrics` for the console (session cookie instead of the management token) |
| `GET audit?order=desc` | newest first; filters `action` (prefix), `actor`, `text`, `from`, `to`, `limit`, `offset` |
| `POST join-requests/{id}/reject` | optional body `{"reason": "..."}`; kept in the audit trail only (the requester is not told the reason) |

Step-up signatures: `stepup/verify` accepts the signature as DER (devices) or as 64-byte `r||s` (browsers; WebCrypto produces this form); the hub converts it (`ConsoleKeys`) and verifies the same bytes. Response fields added: `SessionDto.sessionId` and `serverTimeMs`, `DelegationDto.hubPublicKey`, `qrSvg` (one path, drawn by the hub) on `SetupBeginDto` and `AdminCredentialsDto`.

Static routes (no cookie needed). Every response under `/admin`, `/enroll` and `/enroll-assets/` carries: CSP `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, COOP and CORP `same-origin`, a restrictive Permissions-Policy and `X-Frame-Options: DENY`. `GET /admin/` is the shell (`no-store`); `GET /admin/ui/*` are assets (`no-cache` plus ETag, 304 honoured); `GET /enroll-assets/*` are the invite landing page's own script and style. The old `/admin/login`, `/admin/setup`, `/admin/dashboard`, `/admin/departments`, `/admin/groups`, `/admin/invites` and `/admin/audit` pages answer 302 to the matching console route. When the listener policy closes `/admin` (public port without `FLASH_ADMIN_ON_PUBLIC_PORT`), all console routes answer 404.

Step-up actions (the `action` of a challenge): `device.revoke`, `device.unrevoke`, `delegation.install`, `delegation.reload`, `admin.create`,
`admin.disable`, `settings.change`, `revocation.install`, `recovery.regenerate`.

**Command line** (`server <command>`, the launcher of `:server:installDist` or `java -jar`): `serve` (default); `keytool init-root --out DIR
[--no-passphrase]`; `keytool issue-delegation --root-key FILE --server-pub FILE_OR_BASE64 --scopes enrol:member,enrol:dept_admin,revoke:member [--max-devices N]
[--valid-days N] [--serial N] [--out FILE]`; `keytool rotate`; `healthcheck` (exit 0 only when the hub's own `/health` answers 200); `admin-recovery
--username NAME` (console only, prints a new password, authenticator secret and recovery codes once). Exit codes: 0 ok, 1 refused to start or
failed, 2 bad configuration or usage. `FLASH_ROOT_PASSPHRASE` supplies the keytool pass-phrase non-interactively.

**Configuration** (each is also a `--kebab-case=value` argument; a malformed value is an error naming the variable, never a default):

| Variable | Default | Meaning |
|---|---|---|
| `FLASH_PORT` / `FLASH_HOST` | 8443 / 0.0.0.0 | public listener (relay, REST, enrol, `/health`) |
| `FLASH_ADMIN_PORT` / `FLASH_ADMIN_HOST` | 8444 / 127.0.0.1 | management listener (admin console and API) |
| `FLASH_ADMIN_ON_PUBLIC_PORT` | false | also serve `/admin` on the public port (must be true when the two ports are equal) |
| `FLASH_DATA_DIR` | `./data` | database `flash-hub.db`, `server-identity.pk8/.pub`, `delegation.cert`, `setup-token.txt`, `tls/` |
| `FLASH_TLS_MODE` | proxy | `proxy` (plain HTTP behind a TLS proxy), `self` (the hub terminates TLS), `none` (needs `FLASH_ALLOW_INSECURE`) |
| `FLASH_TLS_KEYSTORE`, `FLASH_TLS_KEYSTORE_PASSWORD` | unset | supply a PKCS12 instead of the generated self-signed certificate |
| `FLASH_EXTERNAL_URL` | `https://<lan-ip>:<port>` | address placed in invites and QR codes; must be https unless insecure |
| `FLASH_ALLOW_INSECURE` | false | permit `tls none`, an `http://` external URL and `--dev-embedded-root`; loud in the log |
| `FLASH_DEV_EMBEDDED_ROOT` / `--dev-embedded-root` | false | DEVELOPMENT ONLY: the hub keeps its own root key |
| `FLASH_DELEGATION_FILE` | `<data>/delegation.cert` | the root-signed delegation |
| `FLASH_ROOT_FINGERPRINT` | adopted at the first delegation | 64 hex digits: pin the organisation root |
| `FLASH_REQUIRE_ROOT_PIN` | false (the Docker image sets true) | refuse the first delegation unless `FLASH_ROOT_FINGERPRINT` is set (no trust on first use) |
| `FLASH_TRUSTED_PROXIES` | none | comma-separated CIDRs or addresses whose `X-Forwarded-*` headers are believed |
| `FLASH_SETUP_TOKEN` | generated file | explicit one-time setup token (at least 16 characters) |
| `FLASH_MANAGEMENT_TOKEN` | none | bearer token for `/health/detail` and `/metrics` (otherwise only a direct loopback caller, no proxy headers) |
| `FLASH_HUB_NAME`, `FLASH_DEVICE_QUOTA` | `Flash Hub`, from the delegation | name shown to devices; device cap (never above the delegation's) |
| `FLASH_CHALLENGE_TIMEOUT_MS`, `FLASH_MAILBOX_TTL_DAYS` | 60000, 7 | challenge lifetime; mailbox TTL |

### Hub review round REV-01..REV-16 (2026-10-13): contract changes

The full list of routes the hub answers is `docs/protocol-routes.txt`; `RouteTableContractTest` fails when a route is added to the code and not to that file.

- **Client address.** Per-IP limits use the address the hub believes (`ClientAddressResolver`): the socket peer, or the rightmost untrusted `X-Forwarded-For` entry when the peer is in `FLASH_TRUSTED_PROXIES`. IPv6 addresses are limited by their /64 prefix. The relay's per-IP connection cap uses the same address, so a reverse proxy does not make all devices one client (REV-01, REV-02). A hub with `FLASH_TLS_MODE=proxy` and no trusted proxies logs a WARN at start.
- **Canonical paths.** A request path that is not canonical (encoded slashes or dots, `//`, a trailing dot segment) is refused with `400 ERR_BAD_REQUEST` before routing, and every response carries the baseline security headers (REV-02).
- **`POST /admin/api/v1/setup/begin`** takes `{token}` (the setup token). `GET setup/status` answers only `{setupOpen}`. At most 16 setups are pending; more are refused with `409` instead of evicting an earlier one (REV-07).
- **Session expiry.** The relay closes a session whose device certificate expired, with `ERR_EXPIRED`, `sessionExpirySkewMs` (60 s) after `notAfter`; the device renews through `POST /api/v1/enroll/renew` and reconnects (REV-05).
- **Delegation revocation.** `keytool revoke` signs a revocation list that revokes delegation serials; `POST /admin/api/v1/revocations/install` (step-up `revocation.install`) installs it; a revoked delegation revokes every certificate it issued, and a hub whose own delegation is revoked refuses to serve (REV-04).
- **Delegation rollback and trust on first use.** The hub persists the highest delegation serial it ever accepted (`delegation.highest-serial`) and refuses an older one after a restart. With `FLASH_REQUIRE_ROOT_PIN=true` the first delegation is refused unless `FLASH_ROOT_FINGERPRINT` is set; otherwise the first delegation is trusted and the hub logs `TRUST ON FIRST USE`. `keytool issue-delegation` takes the highest serial found in its serial file and in `*.cert` files beside the root key, so a lost serial file does not restart the numbering at 1 (REV-16).
- **Mailbox acknowledgement.** `RecvAck {msgId, from?}`: `from` names the sender of the message being acknowledged. With it the hub deletes only that sender's row; without it only the OLDEST row with that id (two senders may reuse a message id). `from` is additive; an old client keeps working (REV-08).
- **META frames.** A direct `Meta` to a device answers nothing whether the recipient is online, offline or unknown: the sender learns nothing about presence. META is not scoped to a department (the direct `Send` between enrolled devices is not either: a cross-department 1:1 chat must work); this is an owner decision recorded as PARTIAL in the audit (REV-09).
- **Step-up.** `settings.change` binds the new value (`<key>=<value>` is the challenge target). A signer (browser key or bound device) is installed with a fresh authenticator code only while the administrator has none; once one exists, replacing it needs one of the administrator's recovery codes in `code` (else `403 ERR_SIGNER_EXISTS`). New recovery codes (`POST admins/me/recovery-codes`) need step-up `recovery.regenerate` when a signer exists (REV-11, REV-14).
- **Own password.** `POST admins/me/password` is limited per administrator: after repeated wrong current passwords the answer is `429` with `Retry-After`, independent of the sign-in lock (REV-10).
- **Audit chain.** Records written from schema step 2 are hashed length-prefixed (`flash-audit-chain-v2`); older rows verify with the original `a|b|c` form (REV-15).

### Hub round 2026-10-14: hashed revocation list v3, phone approvals (ADR-129, ADR-130, ADR-131)

- **Revocation list v3.** `audience` = `HubClientAuth.rootFingerprintHex(rootPublicKey)` (uppercase hex). `deviceHash(audience, deviceId)` = lower-case hex SHA-256 over `AuthCanonicalWriter("flash-revoked-device-v1").text(audience).text(deviceId)`. Golden: `deviceHash("TEST-AUDIENCE","dev-1")` = `5889627c...68c0` (full value in `HashedRevocationListTest`). `GET /api/v1/revocations` serves v3; `keytool revoke` signs v3; the hub republishes its plain list as v3 at startup. A client that does not know v3 ignores the list.
- **Mailbox at rest.** Not a wire change: `mailbox_envelopes.payload` is sealed with AES-256-GCM (`sealed=1`), AAD `mailbox_envelopes.payload|<len>:origin|<len>:target|<len>:msgId`. See ADR-130.
- **Approvals (device bearer).** `GET /api/v1/approvals` -> `[ApprovalDto{challengeId, action, admin, target, nonce, code, expiresAtMs}]` for the calling device (empty when it is nobody's approver). `POST /api/v1/approvals/{challengeId}` body `ApprovalSubmitBody{signature}` (Base64, DER or P1363, at most 256 chars) over `HubStepUp.approvalBytes(challengeId, action, admin, target, nonce)` (domain `flash-hub-approval-v1`) -> `{ok:true}` or 403 `ERR_FORBIDDEN` (expired, already answered, not this device's, not the bound approver). Six-digit code: `HubStepUp.approvalCode(challengeId, nonce)`; golden `("c-1","n0nce")` = `396796`, `("c-2","n0nce")` = `530814`.
- **Console API.** `POST /admin/api/v1/stepup/challenge` now also returns `approvalRequired`, `approvalCode`, `browserKeyRequired` (false for `admin.signer.replace`); `GET /admin/api/v1/stepup/{id}` -> `StepUpStatusDto{verified, approvalRequired, approved, expiresAtMs}` (404 when expired or another admin's); `POST /admin/api/v1/admins/me/approver` `{deviceId, code}` (first phone: fresh TOTP, plus browser step-up `approver.change` when a signer exists; replacing: step-up with the old phone's approval); `POST /admin/api/v1/admins/me/approver/remove` `{code}` (a recovery code). `SessionDto` and `AdminDto` gain `approverDeviceId`. New error codes in challenge creation: `ERR_APPROVER_UNAVAILABLE` (bound phone revoked or no longer an admin device; fail closed), `ERR_NO_APPROVER` (signer replacement needs a bound phone).
- **Actions needing the phone when one is bound:** `delegation.install`, `admin.create`, `admin.signer.replace` (phone only), `approver.change`. Admin schema migration 3 adds `admin_users.approver_device_id`; mailbox migration v3 adds `sealed`.
- The checked route list (`docs/protocol-routes.txt`) has the five new routes.

### Compatibility

Wire v2 is a breaking change from the query-string relay: there is no negotiation. A v2 hub answers a v1 client's upgrade with a CHALLENGE it
cannot answer and closes it on the handshake timeout; a v2 client against a v1 hub never receives a CHALLENGE and times out client-side.
Within v2, members are added but not removed or retyped; receivers ignore unknown members and unknown frame names.


### Hub client behaviour and sealed payloads (ADR-120..123; Phase 2 CLIENT, 2026-10-13)

What a phone or desktop does with the relay wire above. Unit-tested, not device-verified (`HUBC-*` in `docs/testing/TEST-BACKLOG.md`).

- **Enrolment.** The invite must carry the root pin (`rootFingerprint`); an invite without one is refused. A cleartext hub address is refused (a debug switch exists on desktop only: `-Dflash.allowCleartextHub=true`). The hub answers with a credential; the client accepts it only if the answered root's fingerprint equals the pin, the certificate subject is this device id and key, the chain verifies with the shared rules and the validity window holds. Then ONE atomic save of certificate, chain, root, hub URL and hub name.
- **Relay link.** One connection per device. A failure is typed (`RelayFailure`): `REVOKED`, `SERVER_MISMATCH` and `CERT_EXPIRED` are not retried; the others use jittered, capped exponential backoff that resets only after a connection that stayed up. Deliveries reach the collector through a bounded channel; overflow closes the connection and the hub replays unacknowledged deliveries. `FLASH_REVOKED` is believed only when the root-signed revocation list (`GET /api/v1/revocations`) names this certificate. The certificate is renewed through `POST /api/v1/enroll/renew` inside the renewal window.
- **Inbound.** Order is fixed: dedupe by delivery id (LRU 2048) -> sender trust (paired, or directory standing MEMBER; directory unreachable means retry later without acknowledging) -> decode -> the message author must equal the hub-stamped `from` -> durable store -> `RECV_ACK`. A rejected or untrusted delivery is acknowledged so the hub does not replay it for ever.
- **Outbound.** LAN first; the hub path starts after `HUB_FALLBACK_GRACE_MS` (2 s) or at once when LAN fails. The hub is used only for recipients whose directory standing is MEMBER. `FLASH_ACK` / `FLASH_QUEUE_STATE` drive the outbox: `DELIVERED` / `HELD` keep the message SENT (give-up 8 days, `HUB_GIVE_UP_AFTER_MS`; resend 15 min); `UNDELIVERABLE`, `QUOTA`, `REFUSED`, `EXPIRED`, `DROPPED` fail it.
- **Sealed envelope `SEALED_V1`** (ADR-121), used for `payloadJson` of a hub message: `SEALED_V1.<epk>.<nonce>.<ciphertext>.<signature>`, each part Base64. `epk` is a fresh P-256 public key; the key is `HKDF(ECDH(epk, recipient sealing key), info = canon(epk, recipient sealing key))`; the cipher is AES-GCM with AAD = canon(`RELAY_WIRE_VERSION`, sender device id, recipient device id, message id, sentAt). The signature is the sender's identity-key signature over canon(AAD, epk, nonce, ciphertext). A receiver opens it only after the signature verifies. Sealing public keys are published with a binding signature by the device identity key over (deviceId, sealing key). **The hub serves sealing keys since ADR-124**: a device publishes with `PUT /api/v1/device/sealing-key` (the hub verifies the binding before it stores anything, see the endpoint table), and `GET /api/v1/directory/members` returns `sealingPublicKey` / `sealingSignature` on `MemberDto` to authenticated callers in scope of the member, null until the member published. The client MUST verify the binding against the member's `subjectPublicKey` itself. Binding bytes: tag `flash-hub-sealkey-v1`, then the device id, then the raw SPKI bytes (golden vector `SEALING_BINDING_HEX` in `CertGoldenVectorTest`). No version or serial is signed (ADR-121): a rotation is a new key, and the hub refuses to take back a key the device retired. The host apps do not publish a key yet, so plaintext through the hub is still sent only when the user turned "Messages through the hub" on.
- **Frame limits.** A WebSocket frame over 256 KiB is closed by the WebSocket layer (close code 1009) with no error frame; a frame under that whose payload is over 64 KiB gets `FLASH_ERROR ERR_TOO_LARGE` and then the close; more than the frame budget (burst 120, 60 per second) per session gets `FLASH_ERROR ERR_RATE_LIMIT` for the excess frames (ADR-124 contract tests).
- **Group join.** `POST /api/v1/groups/join-request` answers `JOINED`, `PENDING` or `REJECTED`; a repeated request answers `PENDING` until decided; after an admin REJECTED the request, the NEXT request from that device answers `REJECTED` once (status `REJECTED_SEEN` in the hub) and a request after that starts a new `PENDING` row (ADR-124, ERROR-332). Admins list with `GET /api/v1/groups/join-requests` and decide with `POST /api/v1/groups/join-requests/decision`. The client polls pending joins every 60 s.
- **Unenrol.** `POST /api/v1/device/unenroll` with the bearer (`404` counts as already done); when the hub cannot be reached the intention is stored and retried.

## Hub group rosters on the LAN (Plan B Phase B4, ADR-135 section 10)

**Signed roster statement** (`GroupRosterStatement`, domain tag `flash-hub-roster-v1`): fields `groupId`, `groupName`, `departmentId`,
`version`, `memberDeviceIds` (sorted, unique), `adminDeviceIds` (sorted, unique), `issuedAtMs`, `validUntilMs`; signed by a delegate holding
scope `enrol:member`, chained to the root. Verification result `RosterStatus`: OK, MALFORMED, TOO_LARGE (more than 200 members or more than
16 KiB canonical), NOT_YET_VALID, EXPIRED, DELEGATION_INVALID, DELEGATION_EXPIRED, SCOPE_DENIED, REVOKED. The window is the organisation's
`offlineGraceDays`; an expired roster accepts nobody.

**Sync.** `GET /api/v1/directory/sync` carries `groupRosters` (`GroupRosterDto`) on the LAST page only: the caller's full current set of groups
(at most 64 per sync). Groups above 200 members are skipped and counted (`rostersSkippedTooLarge`): "not supported offline". Deviation from
the plan: the server group `version` is the directory version at the group's last change, so it increases but not strictly by 1. A client
stores a roster only if it verifies, and a lower version never replaces a higher one; the set is replaced in the same transaction as the
member batch (Room schema 16, table `hub_group_roster`).

**Gossip.** Feature token `hrs1`. Frame `FLASH_GHUBR` (`GroupWireFrame.HubRosterOffer`: `groupId`, `from`, base64 `st` statement JSON, base64
`chain` delegation chain JSON; caps 14 KiB and 2 KiB). The receiver accepts it only from an ORG peer whose `from` equals the transport peer,
verifies it itself, requires sender and local device to be members, adopts it only if newer, at most one offer per peer and group per 60 s,
and passes it on to at most 24 `hrs1` members. Offers are also sent when a peer session comes up.

**Gate.** `isGroupPeerTrusted(groupId, peer)` additionally accepts a peer of a hub-linked group when the roster is current, both local device
and peer are members, and the peer is ORG. Legacy and `g2-` groups use their own gates, unchanged. ORG-only members never get PTT, group
creation or administration. "Add member" is disabled for hub groups. Roster rows are written with `operationId = "hub-roster-v<version>"`.
