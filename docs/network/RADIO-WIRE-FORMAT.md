# Radio wire format (Profile M) - exact byte layout

Status: IMPLEMENTED and unit-tested on the JVM and Android host 2026-10-09 (`core/network`, package `...network.kiss` and
`...network.radio`). **Never transmitted by a real radio.** A second implementation (Linux client, Rust) should be able to
reproduce section 11 byte for byte. Part of the plan `docs/network/BLUETOOTH-AND-RADIO-TNC-PLAN.md`; that plan's known errors
are corrected here (section 12). Architecture decision: ADR-101 (see the report for the paste-ready text).

All multi-byte integers are big-endian. "Hex" strings are upper case without spaces.

## 1. Layers

```text
 Flash application message (text / ack / ping)
   -> Flash radio frame (section 5)         12-byte header + AEAD body, at most 220 bytes
   -> AX.25 UI frame (section 4)            addresses + control 0x03 + PID 0xF0 + info = the Flash radio frame
   -> KISS frame (section 3)                FEND, command byte, escaped AX.25 bytes, FEND
   -> serial / Bluetooth RFCOMM byte link   (ByteLink)
   -> TNC adds HDLC flags, bit stuffing, FCS and does the modem (1200 baud AFSK); we never see those
```

## 2. Budget

| Item | Bytes |
|---|---|
| AX.25 information field budget (conservative, plan 6.0 item 1) | 220 |
| Flash header | 12 |
| AES-GCM tag | 16 |
| Overhead of an unsegmented message | 28 |
| Largest unsegmented body (`RadioWire.maxBodyUnsegmented`) | 192 |
| Segment sub-header (inside the protected body) | 4 |
| Largest body per segment (`maxBodyPerSegment`) | 188 |
| Maximum segments per message | 16 (3008 body bytes) |
| Signed clear text (Profile A only) body limit | 220 - 12 - 64 = 144 |
| AX.25 address field (dest 7 + source 7, no digipeaters) + control + PID | 16 |
| Largest AX.25 frame handed to the TNC | 236 |

Airtime (estimate, unmeasured): `(len + 4) * 8 * 1.1 / 1200 s + TXDELAY + TXtail`. A 236-byte frame is about 1.9 s plus keyup.
Goodput of a 192-byte body is therefore at most about 80 bytes per second before gaps; EXP-023 measures the real number.

## 3. KISS (TNC framing)

| Byte | Name | Value |
|---|---|---|
| FEND | frame end | `C0` |
| FESC | escape | `DB` |
| TFEND | escaped FEND | `DC` |
| TFESC | escaped FESC | `DD` |

Frame: `FEND, cmd, data..., FEND` where `cmd = (port << 4) | command`. In `data`, `C0` is sent as `DB DC` and `DB` as `DB DD`.

| Command | Meaning | Payload |
|---|---|---|
| 0 | data frame | the AX.25 frame (without flags or FCS) |
| 1 | TXDELAY | one byte, units of 10 ms |
| 2 | P (persistence) | one byte 0..255 |
| 3 | SlotTime | one byte, units of 10 ms |
| 4 | TXtail | one byte, units of 10 ms |
| 5 | FullDuplex | 0 or 1 |
| 6 | SetHardware | TNC specific |
| FF | Return (leave KISS mode) | none |

Encoder: one FEND pair per frame; the command byte is escaped too if `(port << 4) | command` happens to equal `C0` or `DB`.
Decoder (`KissStreamDecoder`): streaming; tolerates arbitrary fragmentation; consecutive FENDs (`FEND FEND`) are counted as empty
frames and ignored; bytes before the first FEND are counted as garbage; a frame containing `FESC` followed by anything other than
`DC`/`DD` (or `FESC` right before the closing FEND) is **dropped whole** and counted as a bad escape; a frame longer than
`maxFrameBytes` (default 1024) is dropped and counted; a frame holds at least the command byte. It never throws and resynchronises
at the next FEND. The host sends parameters (commands 1 to 5) only if configured (`KissTncConfig`), because whether the radio
menu or the KISS command wins is unverified (BT-00).

## 4. AX.25 UI frame

Version 2.2 UI frame, no digipeaters by default, up to 8 allowed on receive.

```text
 0..6   destination address (7 bytes)
 7..13  source address (7 bytes)
 [14..] optional digipeater addresses (7 bytes each)
 next   control byte      0x03 (UI; 0x13 with the poll/final bit is accepted on receive)
 next   PID byte          0xF0 (no layer 3 protocol)
 rest   information field = the Flash radio frame
```

Address (7 bytes): six callsign characters, space padded, each **shifted left one bit**, then the SSID byte
`C R R S S S S E` : bit 7 = C (command/response or H "has been repeated" for digipeaters), bits 6 and 5 = reserved `11`,
bits 4..1 = SSID 0..15, bit 0 = E (1 on the last address byte of the address field). Flash sends destination C = 1, source C = 0
(a command frame in the v2 convention). The destination is the fixed label `FLASH` (not a registered callsign; a monitoring
radio shows it as is).

PID: always `F0`. **`CC` (ARPA Internet Protocol) and `CD` (ARP) are refused by the encoder**, because a hardware TNC or digipeater
may try to treat the information field as an IP datagram (plan correction E3).

Source address (privacy, plan correction E7): a neutral six-character label derived from the pairing key and the current epoch
(section 9), SSID 0..15. It is not a callsign and changes every 15 minutes. Operating note: transmitting on amateur bands needs a
licence and, in most countries, a callsign in the clear; this build does not decide that for the user. See the plan section 5.

## 5. Flash radio frame

```text
off size field
  0    1  MAGIC    F1
  1    1  VT       (version << 4) | kind        version = 1
  2    1  FLAGS    bit0 SEGMENTED, bits1-2 AUTH (0 = pairwise AEAD, 1 = signed clear text), bits 3-7 must be 0
  3    1  TTL      remaining relay hops; NOT authenticated, clamped by the receiver (default max 3)
  4    4  TAG      rotating sender tag (AEAD) | first 4 bytes of SHA-256(sender SPKI public key) (signed)
  8    4  COUNTER  uint32, per sender and recipient direction (AEAD) | per sender (signed)
 12    n  BODY     AEAD: ciphertext || 16-byte GCM tag      signed: plaintext || 64-byte r||s
```

Kinds (low nibble of `VT`, wire-stable): `1` TEXT (UTF-8), `2` ACK (body = u32 counter of the first frame of the acknowledged
message), `3` PING (diagnostic, answered by ACK), `4` VOICE_CLIP (reserved), `5` BEACON (reserved, Profile A only).
Unknown kind, wrong magic/version, reserved flag bits, or AUTH values 2 and 3 are dropped (`MALFORMED`). A frame whose first byte
is not `F1` is silently ignored (`NOT_FLASH`): the channel carries other traffic.

## 6. Pairwise AEAD (AUTH = 0)

Inputs: the 32-byte pairing session key `K` (today supplied by the caller; wiring the real pairing key is an adapter still to
write), and the two device ids (UTF-8, 1..255 bytes each).

Per direction (sender `S`, receiver `R`):

```text
OKM (68 bytes) = HKDF-SHA256( ikm = K, salt = "flash-radio-v1",
                              info = "flash-radio-v1/dir" || len(S) || S || len(R) || R, L = 68 )
key       = OKM[0..32)      AES-256-GCM key
nonceSalt = OKM[32..36)
tagKey    = OKM[36..68)     for the rotating tag and station label
nonce (12) = nonceSalt(4) || 00 00 00 00 || COUNTER(4)
AAD        = "flash-radio-v1" || header[0] || header[1] || header[2] || header[4..12)     (the TTL byte is excluded)
sealed     = AES-256-GCM(key, nonce, AAD, plaintext)  ->  ciphertext || 16-byte tag
```

`len(x)` is one byte. The two directions have different keys and salts, so a frame cannot be reflected back to its author
(`aReflectedFrameIsNotAcceptedByItsAuthor`). Nonce uniqueness comes from the counter (section 10), never from randomness.
The TTL is excluded from the AAD because a relay must be able to decrement it without a key; the receiver therefore treats TTL
as untrusted and clamps it. An attacker can raise a TTL (bounded by the clamp) or lower it; it cannot change anything else.

Receive procedure (`RadioSession.ingest`): parse header; for each registered peer compute the expected tag for epochs
`now-1, now, now+1`; on a tag match try the AEAD open; **only after authentication** consult and update the replay window
(committing an unauthenticated counter would let an attacker lock the honest sender out); then deliver or reassemble.
Drop reasons: `NOT_FLASH, MALFORMED, UNKNOWN_SENDER (no tag matches), AUTH_FAILED, REPLAY, TOO_OLD, DUPLICATE_MESSAGE,
BAD_SEGMENT, SIGNED_NOT_ENABLED`. (`DUPLICATE_MESSAGE` is reserved for the application-level `DedupStore`; `ingest` does not return it.)

## 7. Segmentation

A body above 192 bytes is split into segments of at most 188 bytes. Each segment is an independent AEAD frame with
`FLAGS.SEGMENTED = 1`, its own counter, and a 4-byte sub-header **inside the encrypted body**:

```text
msgId  u16   low 16 bits of the counter of the first segment
idx    u8    0-based
total  u8    2..16
chunk  bytes
```

Per-segment AEAD (rather than seal-then-split) means a forged or corrupted segment is rejected on arrival and cannot poison a
reassembly. The reassembler (`SegmentReassembler`) keys on `(peer, msgId)`, accepts segments in any order and ignores duplicates,
rejects `idx >= total`, a `total` that differs from the first segment, or `total > maxSegments`, holds at most 8 incomplete messages (oldest evicted) and abandons an incomplete
message after 10 minutes. A lost segment means the message is never delivered (there is no automatic retransmission in this
layer; the application retries). The ACK names the counter of the first segment.

## 8. Signed clear text (AUTH = 1, Profile A only)

For unencrypted broadcast on bands where encryption is not allowed. Header as above with `TAG = SHA256(SPKI public key)[0..4]` and
the sender's broadcast counter. Body = plaintext followed by a 64-byte **raw ECDSA P-256 `r || s` signature** (not DER; plan
correction E1) over `AAD_LABEL || header[0..3) || header[4..12) || body`. Never segmented. Disabled unless the application
registers a broadcast sender and a signature scheme. Profile M never sends it.

## 9. Rotating sender tag and station label

```text
epoch = floor(now_ms / 900000)                                  15-minute epochs, ±1 accepted (clock skew up to a full epoch)
TAG   = HMAC-SHA256(tagKey, "tag" || u64(epoch))[0..4)          (plan correction E2: 4 bytes; it is a lookup hint, not security)
label = HMAC-SHA256(tagKey, "lbl" || u64(epoch))
        callsign chars: 6 x ALPHABET[label[i] mod 32], ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        SSID: label[6] & 0x0F
```

Observers cannot link frames across epochs; the two paired stations compute each other's tag. Four bytes give 1 in 2^32 per peer
per epoch false matches, and a false match only costs one failed AEAD open.

## 10. Counters and replay protection

- Send counter: uint32 per (sender, recipient). Strictly increasing and never reused: the persisted high-water mark is advanced
  64 ahead before use (a crash skips at most 64 values), and the first value after a start is at least
  `max(persisted mark, seconds since 2026-01-01T00:00:00Z)` so even a lost store cannot make a station reuse a counter as long as it
  sends fewer than one frame per second on average (a 1200 baud radio cannot exceed that). On exhaustion the session refuses to
  send and the pairing must be re-keyed.
- Receive: 64-wide sliding window (RFC 4303 style). A counter is accepted once; older than 64 behind the highest is `TOO_OLD`.
  The window is persisted per peer so a restart does not make captured frames replayable.
- Message-level duplicate suppression (`DedupStore`, 512 keys, 1 hour) is separate: it covers the same message arriving over two
  paths (radio and LAN, or two gateways).

## 11. Golden vector

Inputs: `K = 00 01 02 ... 1F` (32 bytes); sender id `alice`, receiver id `bob`; kind TEXT; body `hello` (68 65 6C 6C 6F);
`now = 1800000000000` ms; default TTL 3; AX.25 destination `FLASH`.

```text
counter           32774400   (= seconds since 2026-01-01 at that instant)
source label      6M2CAB-13
Flash frame (33 B)  F1110003E9EB857001F41900D0B7F21FF8707E318AC64FFFA3E190158160077F50
AX.25 frame (49 B)  8C9882A69040E06C9A648682847B03F0F1110003E9EB857001F41900D0B7F21FF8707E318AC64FFFA3E190158160077F50
KISS frame  (52 B)  C0008C9882A69040E06C9A648682847B03F0F1110003E9EB857001F41900D0B7F21FF8707E318AC64FFFA3E190158160077F50C0
```

Reading the Flash frame: `F1` magic, `11` version 1 kind TEXT, `00` flags, `03` TTL, `E9EB8570` tag, `01F41900` counter
(= 32774400), then 5 bytes of ciphertext and the 16-byte tag. This vector is asserted by `RadioGoldenFrameTest`; if it must
change, the version nibble must change with it. The primitives are also checked against RFC 4231 (HMAC-SHA-256), RFC 5869
(HKDF) and NIST GCM test case 16 in `RadioCryptoVectorsTest`.

## 12. Corrections to the plan's draft layout

| Plan item | Plan said | This format |
|---|---|---|
| E1 | Ed25519 identity signatures | ECDSA P-256 (the existing Flash identity), converted to raw `r||s`, 64 bytes (section 8) |
| E2 | `fp8` is an 8-byte fingerprint prefix | `fp8` is 8 hex characters = 4 bytes; the wire tag is 4 bytes and only a lookup hint |
| E3 | PID `0xCC` = Flash custom protocol | `0xCC` is ARPA IP; Flash uses `F0`; `CC`/`CD` refused by the encoder |
| E7 | fixed sender identity on air | tag and AX.25 source label rotate every 15 minutes |
| E9 | refers to a `PeerTransport` abstraction | no such type exists in the code; the radio path is `ByteLink` + `KissTncDriver` + `RadioSession` |
| E11 | voice over the radio | infeasible at 1200 baud; `VOICE_CLIP` is a reserved kind only |

## 13. Compatibility rules

- A receiver must ignore (not answer) frames whose first byte is not `F1`.
- A receiver must drop frames with a version nibble it does not know; it must not guess.
- Reserved FLAGS bits must be sent as 0 and cause a drop when set (so a future flag cannot be silently misread).
- Kinds 4 and 5 are reserved; a receiver that does not implement them drops the frame.
- New fields go behind a new version nibble. The byte layout above is frozen for version 1 by the golden vector test.

## 14. What is not specified or not verified

- Whether a given TNC passes `C0`/`DB` transparently, honours KISS parameters over Bluetooth, or accepts a 236-byte frame at all
  (BT-00 and BT-01 on hardware).
- Compression of text bodies (omitted; to be measured against real message sizes).
- How the real pairing key reaches `RadioSession.addPeer` (adapter to `core:security` still to write).
- Legal and operating constraints for the radio service in use (plan section 5).
