# Scenario 3: Open Protocol Interop (Other-Language Clients)

> **Rewritten 2026-10-09 after checking the code.** The previous revision described a plain `ws://` connection with
> `FLASH_HELLO version=1 ... os=linux`, `FLASH_XFER action=offer`, `FLASH_ACK`, and a 24-byte `'FL'` chunk header, and shipped Python,
> Rust and Go clients that sent a message with no pairing. **None of that is the live protocol.** The Python/Rust/Go samples were
> removed because they could not work against a real Flash peer. What follows is only what the code and `docs/protocol.md` support.
> No third-party client has been built or tested against Flash; the Windows and Linux desktop apps are the only non-Android peers
> that interoperate (shared Kotlin engine).
>
> **Authoritative sources:** `docs/protocol.md` (text frames, calling, groups, swarm), the KDoc of
> `core/transfer/.../chunked/ChunkFrame.kt` (binary chunk frames, golden-vector tested), `E2eFrameCodec.kt` (encryption),
> `docs/security.md`. If this page and those disagree, they win.

---

## 1. Layers of the live stack

| Layer | What the code does |
|---|---|
| Discovery | NSD / mDNS service types `_flash-transfer._tcp.` and `_flashws._tcp.` (`NsdFlashDiscovery`); desktop uses JmDNS and additional discovery-resilience sources (ADR-047) |
| Transport | WebSocket (RFC 6455, path `/flash-ws`) over **TLS**, preferred port **45822** with a dynamic fallback; the older TCP probe uses 45821 |
| Identity | TLS leaf key pinned on first use (TOFU) and bound to the `deviceId` in the HELLO; a pinned id with a different key is refused |
| Pairing | Commit-then-reveal code bound to both TLS identities (ADR-042); a peer that is not paired is not trusted for chat or file offers |
| Hello | `FLASH_WS_HELLO version=2 deviceId=<id> name=<name> ping=<ms> gv=<n> [caps=...]` (values percent-escaped); a version other than 2 is rejected, and a frame before the HELLO is a protocol violation |
| Encryption | Once keyed, chat and control frames are wrapped as `FLASH_SEC` (AES-256-GCM, random 96-bit nonce, AAD `flash-e2e-v<version>`, key from ephemeral ECDH P-256 + HKDF-SHA256) |
| Chat | Text frames `FLASH_MSG`, `FLASH_RCPT`, `FLASH_READ`, `FLASH_REACT`, `FLASH_TYPING`; groups `FLASH_GROUP`, `FLASH_GMSG`, `FLASH_GRCPT`, ...; calling, PTT and swarm have their own prefixes (all in `docs/protocol.md`) |
| Files | Binary `FLSH` v2 frames, see section 3 |

### 1.1 Text framing

`PREFIX key=value key=value ...`, fields separated by a single space; in values `%` becomes `%25`, space `%20` and `=` `%3D`
(`FlashTextFraming`). Unknown fields must be ignored (that is how `ping`, `gv`, `caps` and `cv1` were added without a version bump).

### 1.2 A direct chat message (before encryption)

```text
FLASH_MSG localId=<id> conversationId=<id> senderId=<deviceId> senderName=<name> sentAt=<unix-ms> text=<escaped> replyToId=<id-or-empty> replyToPreview=<escaped-or-empty>
```

(`ChatTextFrameCodec.encode`.) Receipts and the other four frames are in the same file. Delivery is at-least-once with de-duplication
on `localId`, backed by a durable outbox.

---

## 2. Keepalive

WebSocket PING/PONG, not an application frame. The HELLO's `ping=<ms>` is the sender's idle interval (10000 / 12000 / 15000 by
hardware tier); the side with the shorter interval pings, the lexicographically smaller `deviceId` on a tie. Any inbound frame counts
as proof of life. Details: `docs/protocol.md`, "Keepalive and the `ping` HELLO field".

---

## 3. File transfer: `FLSH` framing v2

All multi-byte integers are **little-endian**. Every frame:

```text
offset size field
0      4    MAGIC = 'F','L','S','H'
4      1    VERSION = 2
5      1    TYPE = 1 FILE_START | 2 CHUNK | 3 ACK_BATCH | 4 COMPLETE
6      4    PAYLOAD_LENGTH (uint32)
10     ...  PAYLOAD
```

`string` = uint16 byte length + UTF-8; `sha256hex` = 64 ASCII hex characters; `sha256raw` = 32 digest bytes.

| Type | Direction | Payload |
|---|---|---|
| FILE_START | sender -> receiver | `string transferId, string fileId, string fileName, int64 totalBytes (>0), int32 totalChunks, int32 chunkSize, sha256hex fileSha256Hex` |
| CHUNK | sender -> receiver | `string transferId, string fileId, int32 index, int32 dataLength, bytes data, sha256raw chunkSha256` |
| ACK_BATCH | receiver -> sender | `string transferId, string fileId, int32 count, int32 indexes[count]` (ascending received-and-verified chunks; a missing index is the implicit NACK) |
| COMPLETE | receiver -> sender | `string transferId, string fileId, uint8 verified` |

Rules: `chunkSize` is 16 KiB to 256 KiB (default 64 KiB; the sender's profile may use up to 4 streams); the receiver verifies each
chunk's SHA-256 before writing it; after the last chunk it re-hashes the whole file against `fileSha256Hex` and answers
`COMPLETE verified=0` on a mismatch (ADR-068); a resume sends only the chunks missing from the receiver's ACK set. The receiver gates an
offer behind an accept (the sender parks after `FILE_START` until the receiver sends `RESUME`). `ChunkFrame.parse` returns null for
anything malformed and never throws; do the same.

Hash: **SHA-256**. BLAKE3 is not used anywhere (ADR-010).

---

## 4. What an interoperating client must implement

1. mDNS browse/advertise, or accept a manual `host:port`.
2. TLS with a self-signed certificate per install, pinned on first use, and the pin tied to the HELLO `deviceId`.
3. The HELLO exchange above, rejecting any other version.
4. Pairing (ADR-042) and, after it, the session key and `FLASH_SEC` wrapping; frames from an unpaired peer must be refused.
5. The chat codec for the frames you need, plus `FLSH` v2 for files.
6. Path safety for received file names (the hosts reject any destination that escapes the receive directory).

Items 2 and 4 are the substantial work. The only compiled reference clients are the Kotlin engine itself and its test harness
(`core/engine/src/jvmTest/.../interop/DesktopInteropHarness.kt`). A Rust implementation remains a stated long-term goal (AGENTS.md
section 2), not something that exists.

---

## 5. A handshake sketch (Python, UNTESTED)

Illustrates only steps 2 and 3; it will not get past pairing against a real peer. Treat it as a starting point for a probe tool, not
a client.

```python
import asyncio, ssl, websockets

async def probe(host, port=45822):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE        # real clients must pin the peer's key on first use instead
    async with websockets.connect(f"wss://{host}:{port}/flash-ws", ssl=ctx) as ws:
        await ws.send("FLASH_WS_HELLO version=2 deviceId=probe-01 name=Probe ping=15000 gv=2")
        print(await ws.recv())             # the peer's HELLO (if it accepts an unpaired probe at all)

asyncio.run(probe("192.168.1.150"))
```

`gv` is the group protocol level (`FlashProtocol.GROUP_PROTOCOL_LEVEL`, currently 2).
Whether a peer answers an unpaired, unpinned HELLO depends on its TOFU/pairing state (`docs/security.md`); this sketch has not been run.
