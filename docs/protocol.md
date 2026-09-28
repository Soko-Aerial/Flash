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
FLASH_CALL action=ginvite   callId=<uuid> groupId=<uuid> from=<id> name=<escaped> video=<true|false> members=<id,id,…> [band=<b>]
FLASH_CALL action=gaccept   callId=<uuid> groupId=<uuid> from=<id> [band=<b>]
FLASH_CALL action=gdecline  callId=<uuid> groupId=<uuid> from=<id>
FLASH_CALL action=gjoin     callId=<uuid> groupId=<uuid> from=<id> name=<escaped> [band=<b>]
FLASH_CALL action=ghangup   callId=<uuid> groupId=<uuid> from=<id>
FLASH_CALL action=gpresence callId=<uuid> groupId=<uuid> from=<id> name=<escaped> video=<true|false> count=<n> [band=<b>]
FLASH_CALL action=gquery    callId=<uuid> groupId=<uuid> from=<id>
```

- `ginvite`: initiator -> every invited member. `members` is the comma-separated id list the
  initiator is inviting (omitted when empty); receivers seed their known-member set from it, which
  is what lets a third device mesh with the others without having witnessed the original invite.
- `gaccept`: an invited member -> the initiator. The initiator then opens one leg to that member.
- `gdecline`: an invited member -> the initiator. No leg is opened.
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

### Chat and receipt frames

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
