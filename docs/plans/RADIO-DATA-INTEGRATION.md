# Radio data integration: text, telemetry and per-chat choice

Status: **text routing + per-chat fallback choice + explicit radio-retry stop built, 2026-10-30; device verification still owed.** Radio voice (audio) is device-verified by the owner
("talking works"). This document covers the *data* half of the radio path: text chat, telemetry/position, and how the radio
is allowed to take part in a conversation. Decision record: ADR-226 in `docs/decisions.md`.

Related: `docs/plans/RADIO-GATEWAY-PLAN.md` (Plan R, approved), `docs/network/RADIO-WIRE-FORMAT.md` (bytes),
`docs/network/RADIO-AUDIO.md` (voice, ADR-225), `docs/testing/TEST-BACKLOG.md` (RADCHAT-DEV01..04, RADVOICE-01..11).

## 1. Where the data path stands

| Piece | State | Evidence |
|---|---|---|
| KISS / AX.25 / Flash radio frame (Profile M AEAD, Profile A signed) | built, unit-tested, **transmitted by a real radio** in the BT-00 spike | `docs/network/RADIO-WIRE-FORMAT.md`, EXP-023 |
| `RadioChatEngine` (text, 16 segments / 3 008 B, ACK-based delivery, four priority lanes) | built, unit-tested | `core/radio/.../chat/RadioChatEngine.kt` |
| Chat wiring: `RadioChatHost` → `RadioChatBridge` → `RealFlashChatRepository` | built, wired on both hosts | ADR-213, commit `0b4a4d5c` |
| Delivery state visible to the user | **NOT built** — `radioDeliveryNotes` is produced and never rendered | this doc, §4 |
| Transport shown to the user | **NOT built** — `FlashTransportType.BLUETOOTH` maps to `Unknown` in both hosts | this doc, §4 |
| Per-chat consent | built, unit-tested, **not device-verified** — direct conversations persist `AUTO` / `NEVER` in Room schema 21 | this doc, §7 |
| Telemetry / position (Mode 4) | **NOT built** — `RadioKind.BEACON` is reserved and dropped on receive | this doc, §8 |
| Two-radio end-to-end text test | **owed** — RADCHAT-DEV01..04 | §9 |

## 2. The decision in one page

**The radio is an optional fallback path, consented to per chat, never a primary transport and never a transport in the
networking sense.**

- It is **not** in `CompositeDiscovery`, **not** in `ConnectionPlanner`, **not** a `PeerTransport` session. There is no
  handshake, no presence and no discovery over 1200 baud; `FlashTransportType.BLUETOOTH` stays unused for routing.
- It carries **one message at a time**, for a **paired** peer, when LAN and hub cannot (unless the chat forbids it).
- There are **three gates**, all of which must be open: the global gateway switch (off by default), the operator
  authorisation for encrypted traffic (Profile M), and the per-chat consent (§7).
- Delivery is **honest**: "transmitted by radio, waiting" until a cryptographic ACK returns. That state must be visible
  in the bubble, not only in the engine.

## 3. Why fallback-only (options considered)

| Option | What it would mean | Verdict |
|---|---|---|
| **A. Radio as a real transport** (planner, presence, session) | treat the TNC like a very slow Wi-Fi; send everything that fits | **Rejected.** 1200 baud is a shared half-duplex channel with a 300 ms key-up; a presence/typing/ACK chatter would occupy it permanently. It also has no discovery, so "presence" would be a lie. |
| **B. Outbox fallback with per-chat consent** (chosen) | the existing send path LAN → hub → radio, gated per chat | **Chosen.** Reuses the durable outbox, keeps LAN/hub first, and the engine already implements the pacing and ACK rules. |
| **C. A separate "Radio chat" screen** | messages typed in a radio-only view, not in conversations | **Rejected.** Two histories for one person, and the user could not reply to a radio message from the normal thread. The Radio screen keeps only setup, security, diagnostics and voice. |

## 4. What the user must see (implemented in this slice)

**2026-10-30 amendment:** an outgoing radio text now offers a visible **Stop** beside its delivery line (and **Stop sending** in
its context menu) while it is awaiting its ACK or held for the gateway. The action removes only its durable outbox row, shows
`Stopped retrying by radio; delivery is unconfirmed`, and keeps
normal manual Retry available. It cannot retract a frame already on air, so a late cryptographic ACK wins and renders Delivered.

1. **A per-message radio line** in the outgoing bubble: `Radio · sent, waiting for acknowledgement (usually a few seconds)`,
   `Radio · delivered`, or the failure sentence (refusal, not connected). Driven by `RadioDeliveryNote`, never invented by the UI.
   The waiting sentence names the measured ACK window (3.4–3.5 s on hardware; resend pacing 25 s) so a message that is simply
   waiting does not look stuck (ADR-228).
2. **The existing refusal sentence** for attachments is no longer log-only: a photo/file to a radio-only peer must say
   "Photos and files cannot be sent by radio" (follow-up, §10).
3. The Radio screen keeps the link, profile, authorisation, diagnostics and voice; it does not become a chat.
4. **The chat header says "Radio only"** instead of Offline when the peer has no session, no sighting, a radio pairing key here and
   a connected gateway (ADR-228, UI-004/UI-030 in `docs/ui/chat-screen.md`). It is a hint, not presence: nothing is offered for
   calls, files, typing or presence sharing, and the planner never reads it.

Probes (evidence, no content): `radio.tx.text`, `radio.tx.ack`, `radio.tx.fail`, `radio.rx.text`, `radio.rx.ack`.
Their absence on a device test means the radio never carried anything.

## 5. What can and cannot go over the link

| Content | Allowed | Why |
|---|---|---|
| Text up to 3 008 bytes (16 segments) | yes | `RadioWire` segment budget |
| Delivery ACK | yes, automatic | 4-byte counter, highest priority behind emergency |
| Position/telemetry | yes, opt-in, rate-limited | §8 |
| Photos, files, voice notes, calls | **no** | ~80 B/s goodput; a 100 KB photo is ~20 minutes of a shared channel. Owner decision 2026-10-28: files over radio are not needed. |
| Presence, typing, read receipts per message, group frames | **no** | chatter that would occupy the channel; group radio is not designed |

## 6. Integration points (keep them narrow)

- `RadioOutboxPort` (`:core:messaging`) is the **only** seam between chat and radio. It already exists.
- `RealFlashChatRepository.sendDirectViaPaths` is the **only** place that chooses the radio (last).
- The host supplies the per-chat policy through a lambda, like the existing `hubContentViaHub: () -> Boolean`, so the
  repository does not read Android/desktop settings.
- The radio engine never sees a conversation: it sees a recipient device id, a message id and text.

## 7. Per-chat choice (ADR-226)

Stored on the conversation row (`conversations.radioPolicy`, schema step 21), because "may this chat use radio" is a
property of the chat, on this device, and it must survive a restart:

| Value | Meaning | Shipped |
|---|---|---|
| `AUTO` | LAN → hub → radio; radio only when the peer is radio-reachable and both global gates are open. **Default.** | yes |
| `NEVER` | This chat is never transmitted over radio (sensitive content, a crowded channel, or a user who does not want their traffic on the air). Incoming radio messages are still accepted. If no other path exists the message stays queued and shows as pending. | yes |
| `PREFER` | Try radio first even when LAN is up. | **no** — deliberate. It would make chats slow and occupy the channel for no benefit; a one-off "Send by radio" action on a single message (long-press) is the better tool if the owner ever needs it. |

The switch lives in the per-chat details sheet ("Radio fallback"). The first built control is a compact `Automatic` / `Never`
row; it stores a device-local policy and does not synchronize a privacy preference to the peer.

## 8. Telemetry and position (phase 2, not built)

Design sketch, to be agreed before code:

- A **position report** is a `BEACON` frame whose body is a small versioned payload (`v`, `latE7`, `lonE7`, `accM`,
  `timeMs`, optional battery). It is sealed per recipient with the existing session keys, so it is unreadable on the air
  and cannot be replayed (the same counter window applies).
- **One frame per peer per interval.** Encryption is pairwise, so "broadcast" is N unicasts; the default interval must be
  minutes, not seconds (each frame costs ~2 s of channel time).
- **Consent is separate and explicit**: a "Share my position over radio" switch (off by default), and a peer list. It is
  not implied by the per-chat text consent.
- **Receiver**: keeps the latest report per peer (in memory + one small table) and shows distance and bearing from this
  device's own position. Android gets position from the platform; on Windows there is no sensor, so the radar needs a
  manual/grid position or it shows reports without a bearing.
- **Privacy**: coarse coordinates by default (about 100 m), no history beyond the last report per peer, and the UI states
  that radio traffic is visible to anyone with a receiver even when the content is sealed (traffic analysis).
- Open questions for the owner: interval (5 vs 15 minutes), whether telemetry is per chat or a separate screen, and
  whether a report may also travel over LAN/hub (cheap there) to keep the radio silent.

## 9. Two-radio test runbook (what the owner asked for)

**Preconditions**

1. Two Flash stations (phone + Windows desktop is the documented pair; two phones work too), each with its own radio.
2. Pair the two stations **in Flash while both are on the same Wi-Fi** (radio frames are sealed with the pairing key).
   A station with no Flash pairing cannot read or answer radio text.
3. Both radios: *Menu → General Settings → KISS TNC: Enable*, same frequency, same channel.
4. Radio gateway on at both stations, radio **Connected**, and **Profile M authorised** at both. Profile A cannot carry chat: its
   signed frames are dropped by a Flash peer as `SIGNED_NOT_ENABLED` (the pairing stores the peer key's fingerprint, not the key,
   so it cannot be registered as a broadcast sender). Chat now says so instead of transmitting (ERROR-869).
5. **Turn Wi-Fi off on both stations** (a LAN path always wins and would carry the message instead of the radio).

**RADCHAT-DEV01 — text and ACK**

1. Station 1: open the conversation with Station 2, send `radio test 1`.
2. Station 1: the bubble shows `Radio · waiting for acknowledgement`, then `Radio · delivered` when the ACK returns
   (1–4 s on a quiet channel). Log: `PROBE radio.tx.text peer=… segments=1` and `PROBE radio.rx.ack peer=… rttMs=…`.
3. Station 2: the text appears in the same conversation; log: `PROBE radio.rx.text peer=…` and `PROBE radio.tx.ack`.
4. Send back. Then restart both apps (counter persistence) and send again: it must still deliver (no counter reuse).

**RADVOICE-04 — coexistence (voice + data on one radio)**

The two halves use **different Bluetooth services on the same radio**: KISS rides Serial Port Profile (`00001101-…`), voice
rides the vendor audio service (`39144315-…`). "KISS is on on both" is the precondition, not the test.

1. Both stations: gateway **Connected**, voice card **Listening**.
2. Station 1: hold to talk, say "coexistence one", release. Station 1's radio transmits analog voice; Station 2 hears it.
3. While Station 2 is still listening, Station 1 (or a third person) sends `radio test 2` **from the chat**.
4. **Pass:** Station 2 hears the speech *and* receives the text; Station 2's ACK transmits without the voice link dropping;
   both station cards stay `Connected` / `Listening`; the data link never shows `RADIO_CLOSED_LINK` or `LINK_LOST`.
5. Reverse the roles. Then, with voice transmitting, check that the ACK gap does not cut the speaker (the radio is
   half-duplex: a data frame keys the transmitter and will clip voice for ~2 s — that is expected and worth recording).

**What to send me after the run:** the two station logs (`PROBE radio.*` lines), whether the bubble reached `delivered`,
and the sentence shown if anything failed.

### 9.1 Results of the first run (2026-10-28) and what the next run must show

The owner ran it: voice worked, Wi-Fi and mobile data off on the phone, and a text from the phone **arrived on the desktop**.
Both logs then showed three defects (ERROR-869, details there):

| What the log showed | What it meant | Now |
|---|---|---|
| `radio.tx.text` three to four times for one 2-3 byte text, `radio.rx.text duplicate=false` each time | every retry was re-encoded with a new counter and the receiver names a text `rad-<counter>` | a retry resends the identical frame; the receiver dedups and only re-acks |
| `radio.rx.ack rttMs=3833/4185/9338` | ACKs arrived, but after the LAN-speed retry (1.4 s, 2.6 s) | a radio-carried message re-arms after 25 s |
| ten `radio.rx.dropped reason=SIGNED_NOT_ENABLED` on the desktop after the phone restarted | the phone was back on Profile A, which a Flash peer must drop | Profile A chat is refused with a sentence |
| no `radio.tx.text` for the desktop's own send | the send never reached the radio (or was outside the capture) | new `radio.path` probe names the reason |

**The pass criteria for the next run are stricter than before:**

1. One text = **one** bubble on the receiving station, however long the ACK takes.
2. The sender's bubble goes `waiting for acknowledgement` -> `delivered` and **stays** delivered (a retry must not walk it back
   into waiting after the ACK).
3. `PROBE radio.tx.text ... retry=false` on the first send; a retry, if it happens, also shows `retry=true` and puts the **same
   frame** on the air (no second bubble).
4. Both directions, both stations. On the sender, `PROBE radio.path ... result=sent`; on the receiver, `radio.rx.text` +
   `radio.tx.ack`; on the sender, `radio.rx.ack`.
5. If a send does nothing at all, `radio.path` says why (`not_reachable`, `no_bridge`, `refused`) instead of silence.

## 10. Owed work (tracked in `docs/testing/TEST-BACKLOG.md`)

1. Explicit stop action is built (2026-10-30); device check `RADCHAT-STOP-01` is still owed.
2. One-off "Send by radio" on a single message (only if the owner wants `PREFER` without its cost).
3. Telemetry/position (§8) after the text test passes.
4. `RADCHAT-POLICY-01`, `RADCHAT-DEV01..06` and `RADVOICE-04` on hardware.
