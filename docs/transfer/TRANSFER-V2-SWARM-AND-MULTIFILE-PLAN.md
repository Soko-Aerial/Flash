# Transfer engine v2, swarm group sending (FO-04) and multi-file / folder sending (FA-5): research and plan

**Status: PLAN ONLY (written 2026-10-03). Nothing here is implemented, no ADR has been written, no wire format has been changed.**
Owner request: research whether a library can give us torrent-style group file sending, plan it together with FA-5 (multi-file and
folder selection), consider upgrading the transport engine, and be honest about whether it will work. Basis: the code in this repository
and the research in section 3. FO-04 (`docs/FUTURE-OPTIMIZATION.md`) stays POSTPONED (ADR-056) until the owner opens it; this document is
what "opens" it on paper. **It needs its own ADR before any code** (FO-04 and AGENTS section 8).

Every claim below is tagged where it matters: **[code]** read in this repository, **[verified]** read on the cited page,
**[reported]** a search-result summary I did not open the source of, **[reasoning]** my inference, not measured, **[unknown]** not found.

**Follow-up (2026-10-03):** the owner's revised direction (group-exclusive membership by group id + secret, group settings, content already held by a member, own swarm module, language choice, how the web of transfers grows) is designed in `docs/transfer/GROUP-SWARM-DESIGN.md`. Where the two differ (piece size is a manifest field; serving is gated by group secret + roster instead of pairing), that document governs.

---

## 0. Verdict in one table

| Question | Answer |
|---|---|
| Can we reuse a torrent library (libtorrent4j, Bt) for group sending? | **No, not as the transport.** It brings its own sockets, identity and peer rules that bypass our pinned-identity TLS mesh and our group gate, an Android `minSdk` conflict (28 vs our 24), no iOS, and a native blob. It is useful as a **design reference** (rarest-first, endgame, BEP 52 hash tree). Section 5. |
| Can we reuse a modern library (iroh)? | **Not now.** Technically the closest match (BLAKE3 verified streaming, ranges, multi-provider, authenticated QUIC), but its Android artifact is not published (build from source), it would replace discovery, transport and trust at once, and it is a Rust-core decision, not a transfer feature. Park as the long-term "Rust core" option. Section 5. |
| Will a swarm make a group send **faster**? | **Probably not on one Wi-Fi network, and not at all on a phone hotspot.** Every member still has to receive the whole file over the same shared radio channel, whoever serves it. It helps (a) when the sender's own link is slow compared with the others, (b) when the sender must leave early, (c) when a member drops, (d) to relieve the sender's battery. Section 4. **[reasoning, needs measurement `SWM-01`..`SWM-03`]** |
| Will it work at all? | **Yes, technically**, for groups of up to 20 on a router network where members can reach each other, **if** we build it in-house on our existing frames. It is a large, risky feature whose benefit is narrower than the request suggests. Section 4 and 6. |
| What should we build first? | **Not the swarm.** Build the shared foundation that FA-5, resume, cheap group fixes and a later swarm all need: a **content manifest with a chunk-hash list** and a **receiver-pull wire**. Then multi-file / folder (FA-5). Decide the swarm after measuring. Section 9. |
| Should we replace the transport engine (QUIC etc.)? | **No, not on current evidence.** The only baseline we have (EXP-001) says the radio is the limit, not the engine. Fix the known engine weaknesses first (desktop has one socket, probing for the data port, double encryption) and measure. QUIC stays a conditional spike. Section 7. |

---

## 1. Scope

1. **Swarm group sending** (FO-04, the owner's 2026-10-02 idea): the sender hands the file to two members, they pass pieces on, a member
   takes pieces from several members at once, a dropped source is replaced, the sender may go offline after sending only part.
2. **FA-5**: choose several files and folders to send (today one file per pick).
3. **Transport engine upgrade**: what in the transfer path should change, and whether to change the protocol underneath.

Out of scope here: Wi-Fi Direct (FO-03), BLE (FO-02), persisting transfer identity (FA-4, but this plan depends on it, see 6.6).

---

## 2. What the code does today (facts)

### 2.1 Wire and engine

- **Framing v2** (`core/transfer/.../chunked/ChunkFrame.kt`): four frames, `FILE_START` (transferId, fileId, fileName, totalBytes, totalChunks,
  chunkSize, **whole-file SHA-256**), `CHUNK` (index, data, **its own SHA-256 inside the same frame**), `ACK_BATCH`, `COMPLETE`. Chunk size 16 KiB to
  256 KiB (`Chunker.kt`); the performance profiles use 32 KiB with 1 stream (LOW), 64 KiB with 2 streams (MEDIUM) and 64 KiB with 4 streams (HIGH) (`FlashTransferProfile.kt`). **[code]**
- **It is a push protocol.** The sender statically assigns chunk `idx % N` to a worker and pushes; the receiver only verifies, writes and ACKs. There is
  **no request frame and no "which chunks do you hold" frame.** (`MultiStreamDispatcher` header: "Static assignment (`idx % N`)", single
  materializer.) **[code]**
- **Trust of a chunk comes from the chunk itself.** The per-chunk hash travels with the chunk, so it proves transport integrity, not authenticity
  against a relay. The only value fixed *before* the data arrives is the whole-file digest, which cannot check a single chunk. ADR-068 now checks the
  assembled file against it. A relay could today hand over a chunk with its own matching hash and only the final whole-file check would notice. **[code]**
- **Resume**: a persisted done-set per transfer (`TransferChunkDao`); the sender resumes by reading and discarding the already-confirmed prefix
  (ADR-014: "random-access seek deferred, `SeekableSource` reserved"). The receiver writes at offsets (`RandomAccessSinkHandle.writeAt`) but the handle has
  **no read**; a member serving chunks would need read access to what it has received. **[code]**
- **One file per transfer, one transfer per recipient.** `ReceivePipeline` holds one session per `transferId`. `TransferManifest` (multi-file) exists in
  `core/transfer/.../manifest/TransferManifest.kt` but is `internal` and referenced only by its own test. **[code]**
- **Zero bytes are not representable** (`Chunker` needs `totalBytes > 0`; `FILE_START` says `> 0`). After FA-6 an empty file is a visible refusal, so an
  empty file **inside a folder** needs a manifest entry that carries no chunks. **[code]**
- **Encryption**: the WebSocket mesh is TLS with pinned device identities, **and** binary frames to a paired peer are sealed again with AES-256-GCM
  (`SecureBinaryFrameCodec`, "FSEC", session key from pairing). So a transfer is encrypted twice. Whether the second layer costs measurable CPU or throughput is
  **unknown** (`SWM-04`). **[code]**

### 2.2 Transport paths

- **Android to Android** tries a raw TCP "data channel" on the peer's WebSocket port **+1 to +20** and falls back to the WebSocket. The probe sweep was
  once up to 20 x 4 s per channel (ERROR-062), now short-circuited by a 10-minute negative cache. **[code]**
- **A desktop peer has no data channel by construction**, so anything involving Windows runs over **one WebSocket TCP connection** with a shared write lock (EXP-001 saw
  210-965 ms lock waits). "Multi-stream" there means interleaved scheduling over one socket. **[code]**
- **The only throughput baseline we have** is EXP-001: 10 MB, Samsung to Infinix over a phone hotspot, about **2.0 MB/s on the wire (1.4 MB/s confirmed)**, "plausibly near a 2.4 GHz hotspot ceiling, NOT yet evidence of an engine bottleneck". The 5 GHz and N-socket follow-ups were listed and are not in the log. **[code]**

### 2.3 Group attachments

- Sending in a group: for each active member the UI calls `beginGroupAttachment` (announces a `GroupMedia` intro with a per-recipient transfer id and one shared
  file id), then `transfers.sendFile(...)`, so **N-1 independent transfers**, each with its own dispatcher, **its own read of the source file, its own per-chunk
  hashing and its own encryption** (`MainActivity.kt` around line 1450, `DesktopShell.kt` 639-662). `sendGroupAttachment` writes one `GroupDeliveryEntity` per recipient. **[code]**
- **Files go to paired members only** (ADR-044 V2 E3, `isTrustedPeer` in `sendGroupAttachment`): a vouched member that never paired with the sender gets the message
  but never the bytes. A swarm could change that, which is a trust decision (section 4.4). **[code]**
- **The group message signature does not bind the file.** `GroupSigning.signMessage(groupId, messageId, from, sentAt, replyToId, replyPreview, text)`
  is called with an empty text for attachments, so nothing in a signed group message commits to the file's hash or size. **[code]**

### 2.4 FA-5 starting point

- Android picker: `ActivityResultContracts.OpenDocument()` (one file), `ui/platform-shims/.../FlashFilePicker.android.kt`; desktop: `JFileChooser` with `FILES_ONLY` and no
  multi-selection. **[code]**
- The Android **share target accepts `SEND_MULTIPLE`** (`AndroidManifest.xml`, `MainActivity.handleIncomingIntent`, `resolvePendingShare`), so several files can already be shared in; what the
  chat does with them was not traced for this plan. **[unknown, `FA-05` records it]**
- Receive side: `sanitizeRelativePath` exists on Android (`DiscoveryEngineHolder.kt`) and desktop (`DesktopEngine.kt`) and keeps sub-folders under the transfer's own folder. **[code]**

---

## 3. Research

### 3.1 BitTorrent v2 (BEP 52) **[verified: bittorrent.org/beps/bep_0052.html]**
- Per-file **SHA-256 Merkle tree over 16 KiB blocks**; piece length is a power of two, at least 16 KiB; the metainfo carries the **file's pieces root** and, for files larger than a piece, a "piece layers" string.
- A downloader checks a piece from an **untrusted peer** against the root it already trusts, and may ask peers for the intermediate hashes ("hash request" / "hashes") to prove a piece's place in the tree.
- Relevance: **our hash is already SHA-256 and our smallest chunk is already 16 KiB**, so this design drops in without changing hash function (ADR-010 kept SHA-256; BLAKE3 stays deferred). We do not need the proof messages: a group file is at most a few GB, so the full chunk-hash list is small (64 KiB chunks: 32 B per 64 KiB = 0.05 %, about 2 MB for a 4 GB file) and can be sent up front.

### 3.2 libtorrent4j **[verified: github.com/aldenml/libtorrent4j and its releases page]**
- SWIG Java bindings over C++ libtorrent, **MIT**, Maven Central `org.libtorrent4j:libtorrent4j`; Android arm64/armv7/x86/x86_64, Windows x86_64, Linux x86_64, macOS arm64. **No iOS.**
- Release **2.1.0-39 "raised the minimum Android API requirement to 28"**. Our app's `minSdk` is **24** (`app/build.gradle.kts`), so adopting it means dropping Android 7.0 to 8.1 or pinning an older release.
- The release page showed "February 7, 2025" but lists NDK r29 and OpenSSL 3.5.5, which look newer than that date, so **treat the date as unreliable**. **[unknown: native size, exact current version]**
- It speaks the standard BitTorrent protocol on its own listen socket. SSL torrents exist, but they authenticate with a per-torrent certificate, not with our pinned device identities. **[reasoning from the feature list; not tested]**

### 3.3 Bt (Java) **[reported]**
- Pure Java, Apache-2.0, last Maven release 1.9 in **December 2021**, 69 open issues: maintenance mode. Not a basis for a new feature.

### 3.4 iroh **[verified: iroh.computer/blog/the-road-to-iroh-1-0, docs.iroh.computer Kotlin and blobs pages]**
- **iroh 1.0 was released 2026-07-09** (per its own blog): authenticated, encrypted QUIC connections addressed by public key, hole punching, relays; wire format and API declared stable.
- **iroh-blobs**: content addressed by **BLAKE3**, **verified streaming** (a receiver can verify any byte range while streaming), range requests, collections (ordered hash sequences), and a downloader that "can coordinate multiple downloads from multiple peers".
- **Kotlin**: Maven `computer.iroh:iroh` 1.0.0, JVM desktop incl. Windows x86_64, but **the published artifact is single-platform and Android (aarch64, armv7) "requires building from source"**.
- The blog does not mention mDNS for 1.0; address lookup is pkarr on the mainline DHT or a central service, with relays. **Whether it can run LAN-only without internet is [unknown].** Our app is LAN and hotspot first and works with no internet.
- It would replace our identity (ed25519 endpoint ids vs our pinned TLS identities), discovery, transport and the blob format in one move.

### 3.5 QUIC options for a transport upgrade **[reported]**
- **Kwik**: pure Java QUIC, client and server (server since May 2021), no server connection migration; one source describes it as **blocking, one thread per connection**. Maturity on Android unknown.
- **Cronet** is Chromium's stack as an Android library: HTTP/3 **client**; no evidence of a server mode.

### 3.6 Hotspots and shared radio **[reported / reasoning]**
- **[reported]** Phone hotspots commonly enable **client isolation**, so phones joined to one phone cannot reach each other (varies by phone and OS). This is already FO-06 in this repo.
- **[verified in general terms]** Wi-Fi is a half-duplex shared medium: stations on one channel take turns. I searched for a source stating that a client-to-client transfer through an access point costs airtime twice (uplink then downlink) and found none, so the airtime argument in 4.1 is **[reasoning]** and is turned into a measurement (`SWM-02`).

### 3.7 Not researched (be aware)
Google Nearby Connections (P2P_CLUSTER), Wi-Fi Aware, Syncthing's block exchange, WebRTC data-channel meshes, and UDP/multicast distribution. Each could be a reference or an alternative; none was read for this plan.

---

## 4. Will a torrent-style group send work? An honest analysis

### 4.1 It will probably not be faster on one Wi-Fi network **[reasoning]**
All members, the sender included, share one radio channel and take turns. Every member must receive every byte, and every delivered byte crosses the air twice
when it passes through an access point (sender to AP, AP to member). A swarm changes **who** transmits, not **how many** deliveries are needed, so the total airtime
for a 20-member group is about the same as the sender sending 19 copies. The "19x upload from one phone" in FO-04 is real, but it is a cost the radio pays no matter who serves.

When it **does** help:
- **The sender's link is slower than the others'** (weak signal, far from the access point, older phone): airtime is shared, so a slow sender's bytes are expensive; moving most bytes onto faster members' links gives a real gain.
- **Members are on different links** (a wired desktop as a seed, a second access point, a mesh): real parallelism.
- **The sender must leave early**, or **a member drops** mid-transfer (resilience, not speed).
- **The sender's battery and heat**: 19 uploads, 19 hash passes and 19 encryptions (2.3) become one upload plus a share.
- **Not fan-out speed but fan-out CPU**: much of the sender's cost today is avoidable without any swarm (section 6.1, "stage 0").

### 4.2 On a phone hotspot a swarm is pointless
With client isolation (FO-06), joined phones reach only the host. The topology is a star; the only possible relay is the host. A swarm degenerates to "the host
forwards", which is the **designated-forwarder** idea, and it only works if the sender is not the host or the host is willing to forward. The hotspot is also the
network the only baseline was taken on (EXP-001).

### 4.3 Members must reach each other, and our groups do not guarantee it
ERROR-088 and ERROR-095 already showed that a member may not be reachable from every other member (and a call needed fixes for it). A swarm needs member-to-member sessions between
up to 190 pairs for 20 members, but the session ceiling is **24 per device** (ADR-057) and the planner decides who dials whom (ADR-045). A member that cannot reach another simply has fewer sources; the design must treat
the source graph as partial and never assume full mesh.

### 4.4 Trust has three new holes the current design does not have
1. **No content commitment.** The group message signature covers no file hash or size (2.3). A relay could substitute data, and a member that never talked to the sender has nothing to check against. A **sender-signed chunk-hash manifest** bound into the signed message is required (section 6.2), which changes the canonical bytes of the group message (a wire change with a version flag).
2. **Who may receive.** Today files go to paired members only. Through a swarm, a **vouched** (unpaired) member could receive the file from a paired relay. Whether that is allowed is an **owner decision**, not an implementation detail.
3. **Who may serve.** A serving member learns the file and spends the user's data, battery and uplink. Serving needs the same group gate as receiving (a removed member must stop and be refused, ADR-044 V2) and **needs a user-visible setting** ("let group members download from me"). Default is a decision for the owner.

### 4.5 Liveness: a relay phone that sleeps stalls the swarm silently
EXP-002 (Samsung 90 % alive vs Infinix 4 %) and the Transsion freezer note mean some members will not serve when their screen is off. A swarm that depends on those members stalls without
a failure signal; the scheduler needs a liveness and fallback rule (return to the sender, which today is trivially "the sender is the source").

### 4.6 The sender-leaves case has a hard limit
If half the file left the sender and the **only** holder of that half goes offline, the rest of the group waits until a holder returns. Piece **replication** (every piece held by at least two members before the sender is
allowed to leave) prevents it but costs the extra uploads the swarm was meant to save. The UI must say plainly: "the sender is offline, N pieces are only on offline devices". This is a property of any swarm, not a bug.

### 4.7 Verdict
- **Works technically:** yes, on frames and tools we already have, for groups of 20, on a routed LAN.
- **Delivers the headline benefit (faster group send):** unproven and probably small on a single access point; **very likely nothing on a hotspot**.
- **Delivers the secondary benefits (sender can leave, resilience, sender battery):** yes, but only after a large amount of work: a manifest, a pull wire, a scheduler, a trust change, holder persistence, UI and a lot of device testing.
- **Recommendation:** treat the swarm as a **late, flagged option** gated by measurement, and build the shared foundation first because FA-5, resume and cheap group fixes need it anyway.

---

## 5. Options compared

| | A. libtorrent4j / Bt | B. iroh (blobs + QUIC) | C. In-house swarm on Flash frames | D. Relay tree / chain on the existing push path | E. "Stage 0": cheap fan-out fixes only |
|---|---|---|---|---|---|
| What it is | Embed a BitTorrent client | Replace transport with iroh | Add pull + have + manifest to our wire; receiver-driven scheduler | Sender to 2 members, each forwards (store-and-forward or cut-through) as ordinary transfers | Hash and read once per file, cap concurrent uploads, per-member resume |
| Speed on one AP | not better ([4.1]) | not better | not better | not better | not better, **but** lowers sender CPU / battery |
| Sender can leave early | yes | yes | yes | only after its subtree has the whole file | no |
| Multi-source, source failover | yes (mature) | yes (downloader) | we build it | no | no |
| Fits our trust (pinned TLS identity, group gate, removal) | **No**: own sockets and peer rules; mapping peers to device ids and revoking a removed member needs custom hooks | **No**: second identity system and discovery | **Yes**, runs on existing sessions and gate | Yes | Yes |
| KMP / future iOS | **No iOS** | Kotlin binding, Android from source | **Yes**, common code | Yes | Yes |
| `minSdk` 24 | **Conflict** (28 in 2.1.0-39) | unknown | fine | fine | fine |
| Native binary / build burden | large native lib (size unknown) | Rust native, Android build from source | none | none | none |
| Works without internet | yes if DHT/trackers off | unknown | yes | yes | yes |
| Hash format | v2 uses SHA-256 (matches us) but its own metainfo | BLAKE3 (ADR-010 deferred it) | SHA-256 manifest (matches us) | n/a | n/a |
| Effort | M to integrate, **L to make it obey our trust rules** | XL (replaces three subsystems) | L to XL | M | S |
| Risk | high (two trust systems, JNI on Windows/Android) | high (maturity of Android path, scope) | high (new scheduler and wire) | medium (chain latency, slowest-link stalls the subtree) | low |
| Verdict | **Reference only** | **Park (long-term Rust-core option)** | **The only fit for a swarm; do it late and behind a flag** | **Cheapest honest version of the owner's idea; candidate first step** | **Do first** |

Notes:
- **D** is the owner's idea reduced to what the push path can do: a binary tree of forwards (sender to 2 members, each to 2 more). It gives "sender can leave once its two children have the whole file" and halves the sender's uploads, with **no** new wire. Its weakness is the chain: a slow or frozen member stalls everything below it, and it does not give "receive different pieces from several members".
- **A** is rejected as the transport but its **concepts** are the plan for C: rarest-first selection, endgame mode, per-peer request windows, a hash tree that verifies each piece from an untrusted peer.
- **B** is the only library that matches the verified-streaming requirement out of the box, so keep a note: if the project later chooses a Rust core (AGENTS section 2 mentions a possible Rust implementation), iroh-blobs is the first thing to evaluate. That is a separate ADR about the whole stack, not about group sending.

---

## 6. Recommended architecture: Transfer v2, one foundation for FA-5, resume and a later swarm

### 6.1 Stage 0: fixes that need no new wire (do first, they pay off in the current model)
Based on 2.3, a group send of one file to N-1 members does N-1 source opens, N-1 hash passes, N-1 encryptions.
1. **Compute the chunk-hash list once per file** (a single streaming pass) and hand it to every recipient's dispatcher; each dispatcher still reads and sends, but stops hashing. The same list becomes the manifest in 6.2, so this is not throwaway work.
2. **Cap concurrent uploads** per sender (for example 3 to 4 recipients at a time, queue the rest by tier and battery as FO-04 already suggests), so a 20-member send does not open 19 sessions at once.
3. **Share the file read** between recipients served at the same time where the source is a seekable file (a small read-through cache of the current chunk window).
4. Measure before keeping any of it (`SWM-01`, `SWM-04`).

### 6.2 Content manifest (the shared object)
A **manifest** describes content as: manifest id, a list of entries `(relativePath, size, mime?)`, a piece size, and the **list of SHA-256 hashes of every piece** over the concatenation of the entries in order (BitTorrent style: pieces may span files), plus `manifestRoot = SHA-256(canonical bytes of the above)`.
- A single file is a manifest with one entry, so **1:1 transfers use the same object**. Whole-file SHA-256 stays as the final check (ADR-068).
- **Empty files and empty folders** are entries with size 0 (they carry no pieces); this is how FA-5 folders keep empty files that FA-6 refuses as standalone sends.
- **Trust**: the sender signs `(manifestRoot, total size, groupId, messageId)`. For v2 groups the existing group message signature gets the manifest root added to its canonical bytes behind a version flag; a legacy group (forgeable membership, ERROR-082) must **not** be allowed to relay, only the direct per-recipient path.
- **Size**: the hash list is 32 B per piece, about 0.05 % at 64 KiB. For a 4 GB file that is about 2 MB, sent in pieces of 64 KiB over the same session, resumable.
- **Compatibility**: a new capability field in `FLASH_WS_HELLO` (the existing `gv` and `ping` fields set the pattern: optional, unknown fields ignored, no version bump). A peer without it gets today's `FILE_START` / `CHUNK` flow unchanged.

### 6.3 Pull-capable wire (additive frame types)
Framing v2 reserves four types; add, behind the capability above:
- `MANIFEST` (the entries and root; the hash list in fragments), `HAVE` (a bitfield or range list of pieces held, same shape as `ResumeBitVector`), `REQUEST` (piece indexes, up to a window), `REJECT` / `CHOKE` (cannot serve now, with a reason), and the existing `CHUNK` and `ACK_BATCH`.
- `CHUNK` stays byte-identical; the per-chunk hash inside it is checked against the **manifest**, no longer only against itself. A piece that fails is dropped, the source is penalised, the piece is requested elsewhere.
- The golden-vector tests for framing v2 (`ChunkFrameGoldenVectorTest`) already exist and must stay green; new frames get their own vectors before any implementation.
- Everything is documented in `docs/protocol.md` before code (AGENTS section 17).

### 6.4 Receiver-driven scheduler (new, in `core:transfer`)
- Per peer: an in-flight window; the receiver picks which piece to ask whom. **Rarest-first lite** (members are few, so a simple "fewest holders first, random tie-break"), **endgame** (the last pieces are requested from several sources and cancelled on first arrival), **source failover** (a piece in flight from a dropped source returns to the pool, no restart).
- **Sources** come from `HAVE` messages. The sender is always a source with every piece.
- **Fan-in limit**: a member serves at most K concurrent requesters; a battery and tier rule decides K (reuse `FlashPerformanceMode` profiles).
- **For 1:1 transfers** the same scheduler with one source behaves like today's push (this is the compatibility test: the v2 path must match v1 throughput on a single pair; `TV2-01`).

### 6.5 Serving: the gate
A member serves a piece only if: the requester is an **active member right now**, a live session key exists for it (the rule the group call gate uses), and the **user setting** allows serving. A removal, a leave, or a cancelled transfer stops serving at once. Serving also needs read access to received data (a `readAt` on the sink handle or a file read), and the **persistence of "pieces I hold and may serve"** for a member that is not the receiver of record. That is the FA-4 problem (6.6).

### 6.6 Persistence: this plan depends on FA-4
Resume, a swarm holder map, and folder transfers all need the transfer's identity to survive a restart (file name, peer, direction, source, manifest). FA-4 (explained to the owner on 2026-10-03, not implemented) is therefore **a prerequisite** for the swarm and for folders with resume. It needs an ADR and a Room schema change.

### 6.7 What stays unchanged
Framing v2 magic, version and the four existing frames; SHA-256; the `FSEC` envelope and TLS pinning; chunk verify-before-write; the persisted done-set; ADR-068's whole-file check; the group gate.

---

## 7. Transport engine upgrade

### 7.1 What the code says is weak (candidates, none measured yet)
1. **Desktop has one TCP connection per peer.** Windows to anything runs on a single WebSocket with a shared write lock; there is no desktop data channel. (EXP-001's own "next experiments" list N real sockets; it is not recorded as done.)
2. **Data-port discovery is a guess**: probing `+1..+20`, with a negative cache. The port could be **advertised** (in the mDNS TXT record or the `HELLO`) instead of probed.
3. **Double encryption** (TLS plus per-frame AES-GCM) on a phone's CPU. Might be negligible on hardware AES; **unknown**.
4. **Per-recipient hashing and reading** in a group (6.1).
5. **Push model**: no way to resume from random positions, no multi-source (6.3).
6. **Static `idx % N` assignment**: a slow stream holds back the tail until the endgame; a pull model fixes this naturally.

### 7.2 Recommended order
1. **Measure the current engine properly first** (AGENTS section 23): the EXP-001 follow-ups (5 GHz router, chunk-size sweep, 2/4 sockets), on at least two phone pairs and phone/Windows. Classify the bottleneck: network, storage, CPU, TLS, hashing, protocol. `SWM-01`, `SWM-04`, `TV2-02`.
2. **Desktop N sockets** and **advertised data port** (small, removes the sweep and the Windows single-socket limit), subject to the measurement showing the socket is a limit.
3. **Pull wire** (6.3) because it is what a multi-file/folder and a swarm need.
4. **QUIC is a conditional spike, not a plan.** Only if measurement shows TCP head-of-line blocking or Wi-Fi roaming drops as a real cost. Candidate: Kwik (pure Java; blocking per connection, immature on Android [reported]); Cronet is client-only. A UDP path can also be blocked or throttled on some networks, and we would have to re-prove pinned-identity TLS on it. A spike measures: throughput vs WebSocket on the same pair, CPU, battery, behaviour on a Wi-Fi roam, and whether identity pinning works. **Decision after the numbers, with its own ADR.**
5. **Not recommended:** swapping to iroh or libtorrent for transport (section 5), and adding Wi-Fi Direct work to this plan (FO-03 stays separate).

### 7.3 What an upgrade cannot fix
The 2.4 GHz hotspot ceiling and client isolation are physical and policy limits (EXP-001, FO-06). A better engine does not beat them.

---

## 8. FA-5: multiple files and folders

### 8.1 Today
One file per pick on both hosts (2.4). The Android share target accepts several (`SEND_MULTIPLE`); what the app does with them has not been traced (`FA-05` records the result).

### 8.2 Two designs
- **Batch of independent transfers (quick win).** The picker returns N files; the app calls `sendFile` N times with a shared batch id. Reuses everything; one chat bubble and one Transfers row per file; each file resumes on its own. Weakness: 200 photos is 200 bubbles and 200 offers to accept; no folders.
- **Manifest transfer (the foundation, 6.2).** One offer ("12 files, 340 MB"), one accept, aggregated progress, per-file results, paths preserved, **empty files kept**. Needs the manifest wire (6.3) and `ReceivePipeline` to handle a piece space over several files.

### 8.3 Recommendation
1. **Phase 1 (independent of everything else):** multi-file selection with the **batch** design (Android `OpenMultipleDocuments`, desktop `setMultiSelectionEnabled(true)`), a **batch id** so the UI can group the rows and the accept step can be "accept all", and a cap on files per batch with a clear message. Same for the share target.
2. **Phase 4:** folders and big batches as a **manifest transfer**, after the manifest exists.

### 8.4 Folder-specific problems to design for (none solved by a picker)
- **Android folder access** is a SAF tree (`OpenDocumentTree`) walked with `DocumentFile`; that walk is slow for thousands of files and needs a persisted permission; symlinks and cloud-only placeholders need a rule (skip and report).
- **Path safety**: the receiver must treat every path as hostile (`..`, absolute paths, drive letters, reserved names). The existing `sanitizeRelativePath` is a start; the manifest needs a validator with tests (path traversal is an AGENTS section 19 requirement).
- **Windows vs Android names**: `: ? * " < > |`, trailing dots and spaces, reserved names (`CON`, `NUL`), case-insensitive collisions, and the 260-character path limit. The rename rule must be deterministic and shown to the user.
- **Many small files** (10,000 files): per-file offers, rows and sessions would swamp the UI and the database; this is the strongest reason for the manifest design over the batch design.
- **Empty files and empty folders** are entries without pieces (tie-in to FA-6).
- **Collisions with existing files** at the destination: today a transfer folder per id avoids it; a manifest keeps that (a per-transfer root folder).
- **Group sending of a batch or folder** multiplies cost by N-1; this is where Stage 0 and later the swarm matter most.
- **Progress, ETA and "Verified"** must aggregate across files; a file that failed verification (ADR-068) must not hide inside "12 of 12".

---

## 9. Phased roadmap with gates

Effort: S = days, M = a few weeks, L = more than a month of focused work (no promises; sizes are for ordering only).

| Phase | What | Size | Needs | Exit gate |
|---|---|---|---|---|
| **M0** | **Measure**: fan-out baseline at 3 / 6 / 10 devices, airtime share of the sender, hotspot reachability, double-encryption cost (`SWM-01`..`SWM-04`); the EXP-001 follow-ups (`TV2-02`) | S (owner device time) | devices | numbers in `logs/experiments.md`; decides whether P5 and P6 happen |
| **P1** | FA-5 part 1: multi-file pick + batch id + "accept all" | S to M | none | `FA-05` re-run passes on both hosts |
| **P2** | Stage 0: hash once, cap concurrent uploads, per-member resume state | M | M0 numbers | group send CPU / battery lower than M0 baseline, no regression on 1:1 |
| **P3** | **FA-4** persist transfer identity | M | ADR | `FA-04` passes |
| **P4** | **Transfer v2 foundation**: manifest + chunk-hash list + pull wire + receiver scheduler, **1:1 first**; folders via manifest (FA-5 part 2) | L | ADR (protocol), `docs/protocol.md` first, golden vectors | v2 matches v1 throughput on a pair (`TV2-01`); resume from random position; folder round-trips with empty files and hostile paths |
| **P5** | **Swarm for groups, behind a flag** (D first if cheaper, then C): serving gate, holder persistence, replication rule, UI for "serving" and for "stuck" | L to XL | ADR (trust: vouched members, serving setting), P3 and P4, M0 shows a gain | beats the direct fan-out on a router LAN with 6+ devices (`SWM-05`), removed member stops serving, sender-leaves case behaves as documented |
| **P6** | Transport spike (QUIC, desktop sockets) | M | M0 shows a transport limit | numbers vs WebSocket, own ADR |

Gates are deliberate: **P5 does not start unless M0 shows a gain**, because 4.1 says the headline gain is doubtful.

---

## 10. Owner decisions needed (nothing starts without them)

1. **Is the goal speed, or "the sender can leave" and resilience?** If speed, run M0 first and expect a small gain; if the other two, option D (relay tree) may be enough.
2. **May a vouched (unpaired) member receive files through a relay?** (4.4 point 2.)
3. **May members serve each other, and is it opt-in or opt-out?** (4.4 point 3.)
4. **Is it acceptable to change the group message signature's canonical bytes** (behind a version flag) to bind the manifest root?
5. **Order**: FA-4 (persistence) before the swarm is assumed here; confirm.
6. **Hotspot**: is the swarm only for routed networks (recommended), with hotspot groups keeping direct sending?
7. **Library**: confirm A and B are rejected for now (section 5).

---

## 11. Risks (summary)

- A large feature whose main benefit may not materialise (4.1). **Mitigation:** M0 gate.
- New trust surface (content commitment, serving, vouched members). **Mitigation:** manifest signed into the message, same group gate, user setting, ADR first.
- Device liveness (frozen relays). **Mitigation:** the sender is always a source; failover; visible "stuck" state.
- Protocol compatibility. **Mitigation:** capability flag in `HELLO`, framing v2 byte-identical for old peers, golden vectors.
- Persistence and resume complexity (holders, partial files, manifests across restart). **Mitigation:** FA-4 first.
- Scope creep into a transport rewrite. **Mitigation:** transport is P6, conditional.

## 12. What was not verified

- Nothing was run or measured for this plan; no device was used.
- The airtime argument (4.1, 4.2) is reasoning; no source found for "twice" (3.6).
- libtorrent4j's native size and current version; iroh's LAN-only operation; Kwik's Android behaviour; Bt's activity beyond what a search summary said.
- What the Android app does with a multi-file share today.
- Whether TLS plus AES-GCM double encryption costs anything measurable.
- Nearby Connections, Wi-Fi Aware and other alternatives were not read (3.7).

## 13. Sources

- BEP 52 (BitTorrent v2): <https://bittorrent.org/beps/bep_0052.html> [verified]
- libtorrent4j: <https://github.com/aldenml/libtorrent4j> and <https://github.com/aldenml/libtorrent4j/releases> [verified, date unreliable]
- Bt (Java BitTorrent): <https://github.com/atomashpolskiy/bt>, via search summary [reported]
- iroh 1.0 post: <https://iroh.computer/blog/the-road-to-iroh-1-0> [verified]; Kotlin: <https://docs.iroh.computer/languages/kotlin.md> [verified]; blobs: <https://docs.iroh.computer/protocols/blobs.md> [verified]
- Kwik: <https://github.com/ptrd/kwik> [reported]; Cronet: <https://developer.android.com/develop/connectivity/cronet> [reported]
- Hotspot client isolation: community reports found by search (for example the Roku community thread) [reported]; the repo's own FO-06.

## 14. Device and measurement tests owed (also in `docs/testing/TEST-BACKLOG.md` section 4u)

`SWM-01` fan-out baseline; `SWM-02` airtime and sender share; `SWM-03` hotspot reachability between joined phones; `SWM-04` double-encryption and hashing cost; `SWM-05` swarm vs direct fan-out
(only after P5); `TV2-01` v2 vs v1 on one pair (after P4); `TV2-02` the EXP-001 follow-ups (5 GHz, chunk size, 2/4 sockets); `FA-05` (existing) for P1.
