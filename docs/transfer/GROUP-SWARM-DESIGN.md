# Group-exclusive membership, group settings and a group swarm: design and plan

**Status: DESIGN ONLY (written 2026-10-03). Nothing is implemented, no ADR is written yet, no wire format changed.**
This document answers the owner's second request on swarm sending (2026-10-03): make a group exclusive to **group id + group secret**
(pairing no longer matters), add group settings, let any member that already has a file serve it, build our own torrent-like module, and
plan how many devices asking each other make the web of transfers grow. It builds on, and where stated corrects,
`docs/transfer/TRANSFER-V2-SWARM-AND-MULTIFILE-PLAN.md` (the "earlier plan"; its sections are cited as "plan 4.1" etc.).
**It needs ADRs before any code** (AGENTS section 8, FO-04, and a reversal of ADR-044, see 1.1).

**Update 2026-10-04:** the swarm part is now planned phase by phase in `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md`, which governs where the two differ. The owner made reliability the goal, so D10 is answered (the sender's cancel stops the transfer everywhere), D11 is superseded (the swarm runs on every network, without speed claims) and D12 changes (the measurements tune, they no longer gate). ~~The group-secret sections (1, 2, G1, G2) are unchanged and independent of the swarm.~~

**Update 2026-10-04 (b):** the owner accepted D1 (group id + secret, reversing ADR-044's rejection) and wants every member to
receive regardless of pairing.
- Sections 1 and 2 are now planned as track GM in `GROUP-SWARM-IMPLEMENTATION-PLAN.md` section 7B. That plan governs.
- **Two changes to this design:**
  - **1.5 condition 1:** group traffic does not wait for a per-session proof (plan O-13).
  - **1.6:** the new secret is not sealed per member. A signed rotation notice with a commitment, and a handover inside live TLS
    sessions, replace the sealed `RekeyBundle` (plan 5.7).
- **Decisions:** D2–D5, D7–D9 and D13 are accepted as recommended. D6 is filled by plan O-12.

Tags used: **[code]** read in this repository, **[verified]** read on the cited page, **[reported]** a search-result summary whose source
I did not open, **[reasoning]** my inference, not measured, **[unknown]** not found.

---

## 0. Verdict

| Question | Answer |
|---|---|
| Can a group be exclusive to "group id + secret", paired or not? | **Yes, and it is a real improvement** (it removes the whole "unpaired member cannot be reached" family, ERROR-088 / 095). It **reverses ADR-044's rejection of a group-wide shared secret**, so it needs a new ADR and four mitigations: a device-bound proof (the secret never travels and is bound to both TLS keys), an epoch that rotates on every removal, an owner/admin-signed roster that still decides roles and removal, and a join policy (open with code / admin approves). Section 1. |
| Does the secret give forward secrecy or stop a removed member reading old files? | **No.** A removed member keeps what it already received (same as today, `keyEpoch = 0`). Rotation only stops *future* access. Section 1.6. |
| Group settings? | **Yes**: a small owner/admin-signed settings object plus device-local serving preferences. There is **no group settings UI today** (only the members sheet, `FlashGroupMembersSheet`). Section 2. |
| "If a device already has it, it should serve it"? | **Yes**, through content addressing (`manifestRoot`) and a held-content index; it also lets a receiver skip a download it already has. Section 3. |
| Build our own torrent-like library? | **Yes, build the swarm layer ourselves**, because its value is policy that depends on our group trust, not a generic engine. **Do not build another transport**: the transport exists and is device-verified; the swarm only needs a reliable pipe to a peer. Section 5. |
| C++ or Rust? | **Neither first. Kotlin commonMain, written as a sans-IO state machine**, with a deterministic swarm simulator. **Never C++.** Rust becomes the right answer only if the whole core is moved to Rust for iOS/Linux (a separate multi-year decision); the sans-IO core ports mechanically because the wire vectors are the contract. Section 5. |
| Will it be faster? | **Only when the sender's own link is slower than the members' links** (or a member is wired). The honest model is in 4.1. It always helps "the sender can leave" and resilience. Run the measurements (`SWM-01`..`SWM-03`) before building the swarm; the **membership and settings work does not depend on them**. Section 4 and 6. |

---

## 1. Group-exclusive membership by group id + group secret

### 1.1 What exists today and what the owner's direction changes
- **[code]** A v2 group is `g2-<hex>`; its id is derived from the founder's key and a nonce by an owner-signed `GroupCharter`. Members are an
  owner/admin-signed roster; a member is trusted when its **live TLS key equals the key in its cert** (`isGroupPeerTrusted`). Pin sources are
  `PAIRED` / `VOUCHED` / `TOFU` (`VouchRules`). Files go only to **paired** members (`isTrustedPeer` in `sendGroupAttachment`).
- **ADR-044 rejected a group-wide shared secret:** "it authenticates *someone in the group*, not a device, so a removed member keeps access
  until the secret rotates" **[code, docs/decisions.md]**. The owner now wants exactly that model.
- That objection is correct, so **the design must not use the secret as the identity**. The secret is only a *door key* that proves "this key
  was given the invite"; the device identity (its TLS key) stays the thing that is authenticated, recorded, listed and removable.

### 1.2 Concepts
> **Changed by plan GM-4 (SW-0, 2026-10-04):** no `GROUP` pin source is added. The joiner pre-installs the inviter's key as a vouch scoped to the group, which reuses `VouchRules` with no network change (ADR-073).

| Name | What it is |
|---|---|
| `groupId` | unchanged, `g2-...` from the charter. Public-ish (appears in frames). |
| `GS` | the **group secret**, 32 random bytes, generated by the founder at creation. Never sent on any wire. |
| `epoch` | integer, starts at 1, +1 on every rotation. Each epoch has its own `GS_e`. |
| `K_auth(e)` | `HKDF-SHA256(GS_e, info = "flash-gsa-v1" ‖ groupId ‖ epoch)`, the key used for the proof. |
| invite | `(groupId, epoch, GS_e, group name, inviter deviceId + last known address hint)`, shown as **QR, copyable link/code** (and, later, BLE/NFC). |
| `GROUP` pin source | a new `PinSource` meaning "this key proved the group secret for group X". **Scoped to the group, like `VOUCHED`**: it allows group traffic only, never a 1:1 chat or a pairing. |

`HKDF`, HMAC-SHA-256, ECDH and SHA-256 all exist in `:core:security` commonMain **[code: `Hkdf.kt`, `PlatformCrypto`, `FlashCrypto`]**, so no new
dependency is needed.

### 1.3 The proof: a mutual challenge bound to both TLS identities (sans-IO)
Same shape as pairing v2 (ADR-042), which already binds a code to both TLS fingerprints. After TLS (the key presented is whatever the peer has;
**the pin store is not consulted**, exactly as the group call gate already ignores it):

```
A -> B  GS_HELLO     groupId, epoch, nA(16 random bytes)
B -> A  GS_CHALLENGE groupId, epoch, nB(16), macB = HMAC(K_auth, "B" ‖ fpA ‖ fpB ‖ nA ‖ nB)
A -> B  GS_PROOF     macA = HMAC(K_auth, "A" ‖ fpA ‖ fpB ‖ nA ‖ nB)
```
`fpA`, `fpB` are the SHA-256 fingerprints of the keys **of the live TLS session** (length-prefixed, role-ordered, domain-separated).
Properties **[reasoning]**:
- A relay/man-in-the-middle holds two TLS sessions with different fingerprints, so the MACs it would have to forward do not verify.
- Role labels `"A"`/`"B"` and both nonces stop reflection and replay.
- A device that does not know `GS_e` learns nothing it can reuse; constant-time comparison; failed proofs are rate-limited per peer
  (reuse the `VerifyBudget` pattern).
- **Offline guessing:** an observer who records `macB` could brute-force a *weak* secret. So the secret is **128-bit or more, generated by the
  app**, never user-chosen. A short, typable code would need a PAKE (SPAKE2 / OPAQUE-style), which is out of scope here (decision D3).
- Epoch mismatch: the side with the newer epoch answers `GS_STALE(currentEpoch)` and, **only if the other key is on the signed roster**, relays
  the `RekeyBundle` (1.6). Otherwise the connection is refused.
- **Alternative checked and not chosen:** a TLS 1.3 external PSK (RFC 9258 importer) would put the secret into the handshake itself, but
  whether Java's JSSE supports external PSK on both Android and the desktop JVM is **[unknown]** (not found); the application-layer MAC needs
  nothing from the platform.
- The result of a verified proof is a **live-session fact** (`provedGroups` on the session), the thing the gate reads; the persisted `GROUP`
  pin is only a hint for the next dial, like a remembered route (DR1).

### 1.4 Roster: the secret opens the door, the roster still says who is in
Without a roster we could not show members, give roles, remove anyone, or run ADR-063's admin and successor rules. So:
1. **Join** (newcomer N, member M online): after 1.3, N sends `GS_JOIN(label, SPKI, memberCertRequest)` signed by N's identity key.
2. Join policy (a group setting, 2.1):
   - **Open with code:** any online admin/owner device **auto-signs** N's member cert (the same cert vouching uses today, ADR-044 V2) and
     gossips the new roster bundle. Until an admin device has been online, N is `PENDING`: it may read the roster and see "waiting for an admin to
     come online". Chat and file serving need a roster entry (1.5), so `PENDING` is read-only.
   - **Admin approves:** a notification on admin devices; one tap signs or refuses.
3. **Leave / remove** reuse the existing signed tombstones; **a removal is always followed by an epoch rotation** (1.6).
4. Legacy groups (`g-`, forgeable membership, ERROR-082) are **not migrated**: the owner makes a new group (same stance as ADR-064).
   This also fixes the failure mode of ERROR-095 for new groups: there is no trust path to an unpaired member to be missing.
5. Capability: a device advertises the new capability in `FLASH_WS_HELLO` (optional field, unknown fields ignored, like `gv`). A device without it
   cannot join a secret group; the app says "update Flash on that device".

### 1.5 The gate (the single rule that replaces `isTrustedPeer` for group traffic)
A peer P may exchange **group G** chat, receive/serve **G** files and join **G** calls **iff, evaluated at the moment of each request**:
1. P's live session proved `GS_e` for G with `e` = the current epoch, **and**
   > **Changed by plan 1.3 / O-13 (2026-10-04):** this condition is dropped. Traffic between roster members needs no proof of the secret; the roster key matched against the live TLS key authenticates the device (ADR-073, ADR-075). The text above is kept for history.
2. P's key has a roster entry in G (active, not tombstoned), **and**
3. for serving only: group setting `swarmServing` allows it and the device-local preference allows it.

"At each request", not "at session start": **[code]** the documented gap "a call leg already established is not re-checked after a removal"
must not be copied. The scheduler (4.5) re-evaluates the gate on every `REQUEST`.
Pairing, `PAIRED` / `VOUCHED` pins and 1:1 trust are untouched; a `GROUP`-only peer cannot be messaged one-to-one.

### 1.6 Removal and secret rotation
> **Changed by plan 5.7 (2026-10-04):** there is no sealed `RekeyBundle`. An admin signs a rotation notice with a commitment and no secret, and the secret moves only inside live TLS sessions to members that pass the gate (ADR-073 rule 8: the Android identity key is SIGN|VERIFY only, and key agreement below API 31 was not verified). The text below is kept for history.

- On removal (or whenever an admin presses "Change group code"), an admin generates `GS_{e+1}` and signs a **`RekeyBundle`**:
  `(groupId, newEpoch, removedKeys[], sealed[] )` where `sealed[i]` is `GS_{e+1}` encrypted to the i-th remaining member's SPKI
  (ephemeral ECDH P-256 + HKDF + AES-GCM, the primitives pairing already uses) and signed by the admin. Size: about 100 bytes per member, 2 KB at
  20 members.
- **Any member can relay the bundle verbatim** (it rides the same gossip as the roster bundle); each member opens only its own entry. An offline
  member receives it on its next contact with *any* online member. A removed key has no entry, so it cannot get `GS_{e+1}`.
- A device that missed several rotations is handed the **latest** bundle; each bundle names the previous epoch it replaces.
- **Not provided (be explicit):** forward secrecy of past content, and protection against a *current* member who leaks the secret before it is
  rotated. With **join policy = admin approves**, a leaked secret alone admits nobody (the approval step still gates the roster), so that is the
  recommended default (D2). MLS (RFC 9420) or Signal-style sender keys would give per-sender/forward secrecy but are far larger work **[reported]**;
  not proposed here.
- Storage of `GS`: wrapped like the identity key's storage on each host; **never logged** (AGENTS section 24), never in a crash report or chat text.

### 1.7 Discovery of a group when only the secret is known
A newcomer knows the group id and secret but no member's device id. Two cheap routes:
1. **The invite carries the inviter's deviceId and last address hint**, so the first dial is direct (works with a QR or link).
2. **Group beacon** for finding other members later: each member advertises a short tag `HMAC(GS_e, "flash-gbeacon" ‖ epoch ‖ floor(t / 10 min))[0:8]` in
   its discovery record; a device that holds `GS_e` computes the current and previous tag and matches. The tag rotates, so an observer on the LAN
   cannot link devices to a group for longer than ten minutes **[reasoning]**. Clock skew tolerance is one bucket. **[unknown]** whether the
   discovery TXT record can carry the extra 8 bytes on every source (mDNS/JmDNS has an empty-TXT trap, see the project memory); it must be tested
   (`GSEC-06`). The beacon is optional; route 1 alone works.

---

## 2. Group settings

There is **no group settings screen or model today [code: only `FlashGroupMembersSheet`, header and create sheet exist]**.

### 2.1 Two scopes (do not mix them)
| Scope | Stored/enforced as | Fields (v1) |
|---|---|---|
| **Group-signed** (shared, versioned by a monotonic `settingsVersion`, signed by owner/admin, gossiped inside the roster bundle) | everyone applies the highest valid version | name; **join policy** (open with code / admin approves); who may *see and share* the invite (owner / admins / all; **advisory UI control only**: whoever holds the code can technically leak it, which is why approval is the real control); member limit (<= 20, ADR-044); **swarm serving allowed** (on / off for the group); allow members to add members (yes / no, today a non-owner's "Add members" fails silently, a known gap) |
| **Device-local** (never leaves the device) | local preferences | **serve files to the group** (on / off); serve only on Wi-Fi and (charging or battery above a threshold); upload slots; how long to keep a received file available to the group (until I delete it / 7 days / off); auto-accept files from this group up to N MB; mute |
| **Actions** (not settings) | signed operations | change group code (rotate), remove member, promote/demote admin, transfer ownership (ADR-063 exists), leave |

### 2.2 UI
Per AGENTS section 34 a new major component needs its own research doc at DESIGNED before implementation: a **Group settings sheet** component
doc (the next free UI id at the time of writing is UI-053; confirm in `docs/ui/ui-research-index.md`), opened from the group header and the
members sheet, with the invite (QR + copy), pending approvals, and the sections above. This is a UI task and is **not** started here.

---

## 3. Content addressing: "if a device already has it"

- **Content id:** `manifestRoot` from the earlier plan 6.2 (SHA-256 over the canonical manifest: entries, piece size, piece hashes). One file is a
  one-entry manifest. **Refinement of plan 6.2:** the piece size is a **manifest field** (a power of two from 64 KiB to 1 MiB) chosen by the
  sender so the piece count stays <= 16,384 (hash list <= 512 KiB), like BitTorrent's piece-size rule; wire blocks inside a piece stay 16 to 256 KiB
  by performance profile. Verification is **per piece, before the piece is written or served** (the verify-before-write invariant is kept).
- **Announcement:** the group message that carries an attachment includes `(manifestRoot, size, name)` and is signed by the sender's group
  signature; the canonical bytes change behind a version flag (owner decision plan 10.4). **[code]** today's signature binds no file hash.
- **Held-content index** (new table, FA-4's Room migration): `manifestRoot, uri/path, size, fileIdentity (size + mtime + sha), verifiedAt, groups it was announced in, servable`.
  - A receiver that already holds the root (from any earlier announcement) **skips the download**, shows "already on this device", and is
    immediately a source.
  - **Cross-group rule (privacy):** a device serves root X to a member of group G **only if X was announced in G** (it holds that announcement).
    Otherwise `REQUEST` gets `REJECT(UNKNOWN)`. This stops a hash-existence oracle ("do you have the file with hash h?") across groups; within a
    group everyone holding the signed announcement may already fetch it by design.
- File moved/deleted or changed since it was recorded: serve `REJECT(GONE)`, clear `servable` (identity check per AGENTS section 18).

---

## 4. The swarm: precise semantics

### 4.1 When is it faster? An airtime model (to be measured)
On one Wi-Fi cell every delivery of a piece costs airtime **twice**: server to access point, then access point to receiver **[reasoning, no
source found]**. Per delivery of size S: `t = S/R_up(server) + S/R_down(receiver)`. The receiver's down leg is fixed; **the only lever is who
serves** (`R_up(server)`).
- Total time of any strategy ≈ the sum of per-delivery times (airtime is shared).
- **Direct fan-out** (today): all N-1 deliveries use the origin, `Σ = (N-1)·S·(1/R_o + 1/R_r)`.
- **Swarm**: the origin must upload at least one full copy; the rest are served by the best available servers.
- *Illustrative arithmetic, not a measurement:* N-1 = 9 receivers, S = 1000 MB, origin `R_o` = 10 MB/s, everyone else 50 MB/s.
  Direct: `9 × 1000·(1/10 + 1/50) = 1080 s`. Swarm: one origin delivery 120 s + 8 member deliveries `1000·(1/50 + 1/50) = 40 s` each = 320 s → **about 440 s, 2.5× faster**.
  With **equal** rates (all 50): direct `9 × 40 = 360 s`, swarm 360 s → **no gain**.
- Conclusions: the gain exists only with **rate heterogeneity** (far/weak origin, 2.4 GHz origin, or a **wired desktop** as a server: its up leg costs no
  airtime, so the model changes to `S/R_r` per delivery, nearly half). The scheduler must therefore be **rate-aware** (EWMA per source) and prefer
  wired/fast servers, not just spread load.
- The Mundinger/Weber/Weiss result (an ideal broadcast of M pieces to N peers takes about `M + log2 N` rounds when every peer has an independent
  uplink) **[reported]** describes independent links; a shared cell is **not** that case, so it is an upper bound on structure (pipelining), not a
  speed promise.
- **Phone hotspot / client isolation** (FO-06): members cannot reach each other, the swarm degrades to direct fan-out. Detect it (`SWM-03`) and
  say so; never promise a speed-up there.
- **Multicast** (one air transmission for all) would break the airtime bound but is slow/unreliable on Wi-Fi and not available as an Android
  app primitive **[reasoning]**; rejected.

### 4.2 Roles and state
- **Origin:** the sender; holds every piece. **Holder:** any device holding some verified pieces. **Leecher:** wants pieces. A device can be several at once.
- Per `(manifestRoot, peer)` state: peer's **bitfield** (`HAVE_ALL` / `HAVE_NONE` shortcuts), our in-flight requests, our serve slots used, a
  throughput EWMA, a strike count.
- **Invariant 1 (no relaying on demand):** a server answers only from pieces it already holds and has verified; it **never fetches on behalf of a
  requester and never forwards a request**. So there are no request chains, no loops, no amplification trees, and no deadlock: a cycle "A asks B
  while B asks A" is two independent full-duplex streams.
- **Invariant 2 (verified only):** a piece is served only after it passed its piece hash, so corruption cannot spread.
- **Invariant 3 (gate per request):** 1.5, re-evaluated on every `REQUEST`.

### 4.3 Wire (additive; documented in `docs/protocol.md` before code)
`MANIFEST` (entries, piece size, root, hash list in fragments), `HAVE` (batched piece ranges, **at most one per second per peer**; at 20
members and 16,384 pieces the worst case is about 2 KiB per peer per second, not per-piece chatter), `REQUEST(piece list)`,
`REJECT(piece | all, reason: BUSY | UNKNOWN | GONE | NOT_MEMBER, retryAfterMs)`, `CANCEL`, and the existing `CHUNK` / `ACK_BATCH`. Framing-v2
golden vectors stay green; new frames get their own vectors first. Capability-gated in the hello; a peer without it gets today's push flow.

### 4.4 Initial spread (origin has the only copy)
> **Superseded by `GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2 (2026-10-04):** reactive gating is no longer "only if the simulator shows a need". The origin offer policy (plan 4.1 Rule B, INV-7, ADR-072) is mandatory, because it is what makes a half-sent file finish among the members.

The risk is the origin giving the same first pieces to everyone. Two known remedies:
- **Reactive gating (BEP 16 style super-seeding [reported])**: the origin offers a piece to a member only when no other member is known to have it,
  and offers the next only after the piece has been reported as held elsewhere.
- **Deterministic striping**: with N-1 receivers, piece `i` goes first to receiver `i mod (N-1)`.

Because all pieces look equally rare at the start, **rarest-first with a random tie-break already diversifies**: two receivers choosing from
4,000 pieces rarely collide, so no special mode is needed if the origin limits concurrency. **Decision for the design: random-tie rarest-first +
origin serve slots (K = 4) + reactive gating only for the origin;** reject striping (it needs a global assignment and breaks under churn).
The simulator (5.4) must show that the number of origin-uploaded bytes is close to one copy plus a small overhead; if not, add gating.

### 4.5 Scheduler (receiver pull)
- **Piece choice:** *random first piece* (get something to share quickly), then **rarest-first among online holders** (fewest holders, random
  tie-break), skipping pieces already in flight (one source per piece, except endgame).
- **Source choice for a piece:** among unchoked holders, the best throughput EWMA with the fewest requests already in flight; the origin is
  chosen last once any other holder has the piece (saves the origin, plan 4.5).
- **Window per source:** starts at 4 pieces, grows additively and halves on a `REJECT(BUSY)` or timeout (AIMD), cap 64; total in-flight bytes
  capped per device by profile (LOW/MEDIUM/HIGH, `FlashPerformanceMode`).
- **Peer set:** at most **k = 4..6 active data peers per transfer** (not 19), chosen from holders; control/HAVE traffic uses the sessions that already exist
  (the session ceiling is 24, ADR-057, group cap 20).
- **Endgame:** when the missing pieces are <= `min(32, 2 %)`, request each from up to **two** sources and cancel the loser; only if the slowest
  source's ETA is more than twice the median (duplicates cost airtime on a shared cell).
- **Failover:** a piece in flight from a dropped source returns to the pool without restarting the transfer.

### 4.6 Serving policy (many devices asking each other)
- **Slots:** each device serves at most K concurrent requesters (LOW 1 / MEDIUM 2 / HIGH 4; origin 4; a desktop on AC doubles), round-robin among requesters; excess gets `REJECT(BUSY, retryAfter)`.
- **No tit-for-tat:** unlike public torrents, every peer is authenticated and cooperative, so no choking game; fairness is slot-based.
- **Back-pressure:** a cap on outstanding bytes per requester and a global serve budget; refuse when the device is on battery below the user's threshold.
- **ECO connection mode:** ECO parks sessions (ADR-040 "park by agreement"), and a parked session cannot serve. Decision D9: **in ECO a device does
  not serve** and does not join swarms; it downloads from the origin directly.
- **Abuse:** a piece failing its hash gives the source a strike for that piece and a short ban; three strikes ends use of that source for the
  transfer; removed members stop being served on their next request (1.5).

### 4.7 How the web grows
Let the holders of a complete piece be H(p). Each round (one piece time) each holder with a free slot serves one requester, so `|H(p)|` roughly
**doubles per round** for a piece that is requested by enough devices (the `log2 N` in the Mundinger bound, N = 20 gives about 4 doublings),
and with **pipelining** (different pieces in flight at once) the whole file finishes in about `S/R + a few piece-times`, not `log2 N × S/R` **[reasoning]**.
On a shared cell this growth is capped by 4.1: the web grows in *structure* but not necessarily in *speed*. The scheduler's observable outputs
(for the UI and the logs): **distributed copies** (the minimum holder count over all pieces, as in BitTorrent), pieces per holder, and bytes
served by origin vs members.

### 4.8 Stuck and leaving states
- **Origin leaves** before `distributed copies >= 1`: leechers continue from holders; pieces held by nobody ⇒ state `WAITING_FOR_HOLDERS`,
  persisted (FA-4), resumed when any holder reappears (presence). UI: "3 pieces missing, waiting for a device that has them."
- **Headline feature:** once distributed copies >= 1 the sender sees **"You can go offline now"**.
- **Origin cancels** the send: it stops serving; holders continue unless the group message is retracted (retract semantics are an owner
  decision, D10).
  > **Superseded by `GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2 (2026-10-04):** the owner chose "cancel everywhere": a signed tombstone stops the transfer on every member, including members that were offline (plan 4.3, ADR-072).
- **A holder deletes or moves the file:** `REJECT(GONE)`; the receiver treats it as that holder leaving.
- **A removed member's partial transfer:** its sessions stop being served; its partial data stays on its own device.

---

## 5. Build or reuse; which language

### 5.1 What "our own torrent-like library" actually contains
| Layer | Build? | Why |
|---|---|---|
| **Swarm logic**: manifest, bitfield, scheduler, serve policy, piece verification, held index | **Build (this feature)** | Its rules depend on our group gate, our roles and our modes. About 2 to 3 thousand lines **[reasoning]**. No library fits: libtorrent4j needs Android API 28 (our minSdk is 24), has no iOS, has its own sockets and peer rules that bypass our pinned mesh **[earlier plan section 5]**. |
| **Discovery** | already built | NSD/mDNS + DR1..DR5. |
| **Transport / trust** | **already built and device-verified (2026-09-23)**: WebSocket mesh over TLS with pinned identities, Android raw data channel | The owner's argument "we built discovery, so build the transport too": the transport **is** built. What is missing is the *pull/have wire*, which is part of the swarm module. Replacing the engine would discard the device verification and, on current evidence (EXP-001: radio-limited), would not be faster. Transport changes stay evidence-gated (earlier plan section 7; `TV2-02`). |
| **Storage I/O** (random read of verified pieces, SAF) | extend | needs a `readAt` on the sink and a file read for serving. |

### 5.2 Language comparison
| | Kotlin (commonMain, KMP) | Rust (UniFFI/Gobley or own core) | C++ (JNI) |
|---|---|---|---|
| Speed where it matters | enough: SHA-256 is a JVM/Android intrinsic, the swarm logic is O(pieces × peers) | same | same |
| Packaging | zero: same module graph, runs on Android, JVM desktop, later iOS/Linux native via KMP | 4 Android ABIs, desktop is a JVM app so a native DLL/`.so`/`.dylib` per OS to sign and load; bindings via UniFFI (MPL-2.0 Gobley for Kotlin) **[reported]** | worst: NDK + CMake + JNI by hand |
| Untrusted-input safety | memory-safe | memory-safe, strongest | **memory-unsafe parser of attacker-controlled frames** |
| Async / cancellation across the boundary | native coroutines | hard across FFI (runtime + cancellation bridging) | hard |
| Debugging for a team that switches between AI assistants | one language, one debugger | two languages, two toolchains, FFI bugs | worst |
| Fit with a future iOS/Linux plan | ADR-058 already chose KMP as the first step | best if the *whole* core moves | poor |
| Interop with a future independent Rust client | via `docs/protocol.md` + golden vectors (the contract) | direct code reuse | none |

**Recommendation: Kotlin first, as a sans-IO state machine in a new KMP module** (name proposal `:core:swarm`, JVM + Android targets now):
- Pure: **events in** (`PeerUp`, `BitfieldReceived`, `RequestReceived`, `PieceReceived(bytes)`, `Tick(now)`, `PeerGone`), **commands out**
  (`SendHave`, `SendRequest`, `ServePiece`, `Reject`, `Ban`, `Persist`). No sockets, clock, or disk inside, so it is deterministic and
  **simulatable**.
- A **virtual-clock swarm simulator** (20 nodes, heterogeneous rates, loss, churn, an origin that leaves, a removal mid-transfer) is the most
  valuable asset: it tests growth, endgame, failover and the 4.1 model on CI without devices (device runs stay owed, AGENTS section 12).
- **Port to Rust later only if** (a) a measurement shows the logic is the bottleneck (not expected) or (b) the owner decides the whole core moves
  to Rust for iOS/Linux. Because the module is pure and the wire has golden vectors, the port is mechanical. **C++ is not recommended at all**;
  its only argument (libtorrent exists) was already rejected for architecture, not language.
- iroh stays parked as the long-term "Rust core" option (earlier plan section 5); nothing here prevents it.

---

## 6. Phased plan with gates

Sizes (S days, M weeks, L over a month) are for ordering only.

| Phase | What | Size | Needs | Exit gate |
|---|---|---|---|---|
| **G0** | ADRs: (1) group secret membership (**reverses ADR-044's rejection**, with the four mitigations), (2) group settings, (3) swarm module and wire; threat-model section in `docs/security.md`, `docs/group/` plan | S | owner decisions D1..D12 | ADRs accepted |
| **G1** | **Secret membership**: invite (QR/link), `GS_*` frames and proof, `GROUP` pin source, `provedGroups` on the session, gate (1.5), join policy, `RekeyBundle`, rotation on removal, capability in hello, wire doc | L | G0 | unit + loopback tests incl. relay-MITM, replay, reflection, stale epoch, removed-then-rejoin refused; **device checks `GSEC-01`..`GSEC-08`** |
| **G2** | **Group settings**: signed settings object + versioning, device-local preferences, settings sheet (UI doc first) | M | G1, UI doc DESIGNED | `GSET-01`..`GSET-04` |
| **G3** | **Manifest + held-content index + dedup** ("already have it"), shared with FA-5 and earlier plan P3/P4 (FA-4 persistence first) | L | FA-4 ADR | 1:1 v2 matches v1 throughput (`TV2-01`), dedup works (`SWM-06`) |
| **M0** | **Measure** `SWM-01`..`SWM-04`, `TV2-02` (owner device time; can run now in parallel with G0..G2) | S | devices | numbers in `logs/experiments.md`; **G4 starts only if a gain or a resilience need is shown** |
| **G4** | `:core:swarm` sans-IO engine + simulator | M | ADR, M0 | simulator scenarios pass, origin bytes about one copy, mutation-checked |
| **G5** | Wire integration + serving gate + holder persistence + ECO/serving rules + hotspot detection, behind a flag | L | G1, G3, G4 | `SWM-05` beats direct fan-out on a router LAN of 6+, removal stops serving within one request window, sender-leaves works |
| **G6** | UI: availability ("on 7 of 9 devices"), "You can go offline now", stuck state, serving indicator | M | G5 | `SWM-07` |

G1 and G2 are valuable **without** the swarm (group chat, calls and files among unpaired members) and do not wait for M0.

---

## 7. Owner decisions needed (nothing starts without them)

1. **D1** Confirm the reversal of ADR-044's rejection of a group secret, with: device-bound proof, epoch rotation on every removal, signed roster, join policy.
2. **D2** Default join policy: **admin approves (recommended)** or open with code. Open means a leaked code admits anyone until rotated.
3. **D3** Secret format: app-generated >= 128-bit via QR/link (recommended) or a short typable code (needs a PAKE; more work, weaker UX of the proof).
4. **D4** 1:1 chat, pairing and `PAIRED` pins stay exactly as they are; a `GROUP`-only peer cannot be messaged one-to-one (recommended).
5. **D5** Legacy `g-` groups are not migrated; new groups only.
6. **D6** Who may display/share the invite (advisory): owner only / admins / all.
7. **D7** Language: Kotlin sans-IO core first (recommended), Rust only on measurement or a whole-core decision, no C++.
8. **D8** Keep the current transport; transport work only after `TV2-02`.
9. **D9** In ECO connection mode a device does not serve or join swarms.
10. **D10** Retract semantics: when the origin cancels, may holders continue serving?
    > **Superseded by `GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2:** answered by the owner, cancel everywhere; finished copies are kept but not served (O-2).
11. **D11** Swarm only on routed networks; hotspot groups keep direct sending (recommended).
    > **Superseded by `GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2:** the swarm runs on every network, hotspots included, with no speed claim there.
12. **D12** Order: G1 + G2 now (independent of the swarm), M0 in parallel, G4+ only after M0.
    > **Superseded by `GROUP-SWARM-IMPLEMENTATION-PLAN.md` 1.2 and 1.3:** no measurement gate (measurements tune), and membership is track GM of the same plan, meeting the swarm at GM-5.

13. **D13** Third-party code policy (section 11): reference freely, copy only small attributed Apache-2.0 pieces, never depend on GPL code.

---

## 8. Risks

- **Security reversal:** a leaked code admits a joiner under "open" policy; mitigated by approval + rotation; no forward secrecy; documented honestly.
- **Admin availability:** under "open" the roster entry needs an online admin; a `PENDING` state is real UX.
- **Mixed versions:** old devices cannot use secret groups; capability check and a clear message.
- **The swarm may not be faster** (4.1); the plan gates it behind measurement.
- **Serving costs the member:** battery, heat, storage read access (SAF); device-local limits and default off on low battery.
- **Hotspot isolation, ECO parking, Transsion freezing** can make a "holder" unreachable at any moment; the stuck state is designed, not assumed away.
- **Privacy of the beacon** (1.7) and of hash lookup across groups (3); mitigated by rotation and the cross-group rule.
- **Size:** G1 and G5 are each large; each phase is independently shippable.

## 9. What was not verified

- Whether JSSE supports a TLS 1.3 external PSK on Android and desktop (not found); the design does not need it.
- The airtime model in 4.1 (my reasoning; no source), the illustrative numbers, and the rate-heterogeneity claim: **measure** (`SWM-01`..`SWM-03`).
- That the discovery TXT record can carry the group beacon on every source (`GSEC-06`).
- That ECIES-style sealing (ephemeral ECDH + HKDF + AES-GCM) composes from the existing `:core:security` primitives on both hosts without
  a new API (the pieces exist; the composition is untested).
- Random read of a SAF destination (`content://`) at transfer speed; serving from a partially written file.
- BEP 16 and Mundinger et al. were read as search summaries, not from the source pages **[reported]**.
- Nothing here has been run; all device tests are in `docs/testing/TEST-BACKLOG.md` section 4v.

## 10. Sources

- BEP 16 super-seeding: <https://www.bittorrent.org/beps/bep_0016.html> [reported]
- BEP 52 (BitTorrent v2): <https://bittorrent.org/beps/bep_0052.html> [verified, earlier plan]
- Mundinger, Weber, Weiss, "Optimal scheduling of peer-to-peer file dissemination" (M + log2 N rounds) [reported]
- RFC 9258 (TLS 1.3 external PSK importer): <https://www.rfc-editor.org/rfc/rfc9258> [reported]; RFC 9420 (MLS) [reported]
- UniFFI and Gobley (Kotlin bindings for Rust, MPL-2.0) [reported]
- Sans-IO pattern (protocol logic without I/O) [reported]
- In-repo: ADR-042, ADR-044, ADR-057, ADR-058, ADR-063, ADR-064, `docs/group/v2-vouched-trust-plan.md`, FO-04, FO-06, EXP-001

## 11. Reference implementations reviewed (2026-10-04): anitorrent and Ketch

The owner pointed at two projects. Verdict: **neither is imported; Ketch's pure-Kotlin torrent engine is a design reference and a possible source of a few small, attributed adaptations.**

### 11.1 open-ani/anitorrent (and Animeko): rejected
- A Kotlin wrapper around **libtorrent (C++)** with prebuilt native binaries (Android ABIs, desktop JVM) **[verified: its README via fetch, 2026-10-04]**.
- **License GPL-3.0 [verified]**; Flash is Apache-2.0 (`LICENSE`, `NOTICE`). Depending on a GPL-3 library would force the combined work under the GPL, which the owner has not chosen. This alone rules it out.
- The README says it is **not a general-purpose wrapper**: only the features Animeko needed, low-level calls almost straight to libtorrent, high-level code lives in Animeko **[verified]**.
- It also carries every objection already recorded against libtorrent4j (own sockets and peer rules that bypass the pinned-identity mesh and the group gate, native blobs per ABI/OS, no per-request membership check, no iOS), see the earlier plan section 5.

### 11.2 linroid/Ketch (`Ketch-main.zip`, Apache-2.0): read, not imported
Read locally (not built, not run): `README.md`, `docs/torrent.md`, `docs/design/torrent-v2-integrity.md`, `docs/design/torrent-resource-accounting.md`, `library/torrent/build.gradle.kts`, `TorrentTransport.kt`, `TorrentV2RarityPicker.kt`, `TorrentPieceScheduler.kt`, `PeerIdentityHandshake.kt`, `TorrentEngine.kt`, `HttpDownloadSource.kt` (resume validation), `gradle/libs.versions.toml`.

**What it is.** A download manager (HTTP/FTP/BitTorrent, pairing of its own app instances). Its `library:torrent` module is a **pure-Kotlin BitTorrent v1/v2/hybrid engine** (about 89 common-main files, 121 common test files) on Ktor sockets, no native code. It states release-candidate status, and its own design docs say v2 integration, proof acquisition and production resource profiles are **roadmap work** (the README is more optimistic than those docs).

**Why not to import it as a library**
| Reason | Evidence |
|---|---|
| The engine is `internal`; only the Ketch download-source adapter is public | `internal interface TorrentEngine`; the public entry is `TorrentDownloadSource` tied to Ketch's `library:core` / `library:api` (11 of 89 files import them). Importing it means importing the Ketch download manager. |
| Toolchain jump for the whole project | Ketch: Kotlin 2.4.20, Ktor 3.5.2, coroutines 1.11.0, AGP 9.4.0, **Android minSdk 26**. Flash: Kotlin 2.2.10, coroutines 1.10.2, AGP 9.3.1, **minSdk 24** (and a deliberately pinned okio 3.4.0). Taking it would force a project-wide upgrade and drop Android 7.x. |
| Wrong trust model | Peers are found by infohash through trackers, DHT, PEX and magnets; the handshake carries no device identity (`PeerIdentityHandshake` only matches the swarm tag). Anyone who knows the hash can fetch. We need the group gate on **every request** (design 1.5), roster removal, and our pinned TLS identities. |
| Most of it is for the public internet | Trackers, DHT, magnet metadata exchange, peer exchange, private-tracker rules (roughly a third of the files) have no use in a closed group. |
| Not production-proven | Release candidates only; its docs say the resource-accounting and v2 gates are open. |

**What is genuinely useful (and one strong confirmation)**
- **Confirmation:** an independent, test-heavy swarm engine (rarest-first, endgame, bounded memory) runs in **pure Kotlin on Android, JVM and iOS**. That supports the design decision in section 5 (Kotlin first, no C++/Rust needed for the scheduling logic).
- **A clean transport seam.** Its `TorrentNetwork` / `TorrentConnection` (`connect`, `listen`, `readExactly`, `write`) isolates the engine from sockets. In principle a `TorrentNetwork` could be backed by our authenticated sessions. In practice the BitTorrent wire is a plain byte stream, ours is message-framed and gated; the adapter is possible but is the very work the design wants to own, so it is a pattern to copy, not a bridge to cross.
- **Rarity index** (`TorrentV2RarityPicker`: per-peer bitfield, a count per piece, rarest pick, a rotating cursor, peer cap) and **scheduler claims** (`TorrentPieceScheduler`: one owner per piece, duplicate at most twice and only in the endgame) match section 4.5 almost exactly. Caveats seen: both scan every piece per pick (fine for the 16 K pieces we cap, not for a million), and the scheduler allocates lists per claim.
- **Bounded memory by leases** (`TorrentBufferBudget`: every claim, peer snapshot and handshake reserves bytes first and refuses when the budget is gone): worth adopting as a rule for the swarm module, our serve budget and in-flight caps (4.5, 4.6).
- **Checkpoint plus ownership journal** (`TorrentCheckpoint`, `TorrentOwnershipJournal`, hidden sidecars for boundary pieces) is a worked reference for FA-4 and for serving partial files.
- **Differential testing against libtorrent4j** (test-only dependency, behind a flag): a good way to prove our piece-hash and bitfield logic against a reference client.

**"Pause, resume and resume from anywhere" in Ketch** is HTTP range segments: it saves the server's `ETag` / `Last-Modified` and refuses to resume when they changed (`HttpDownloadSource` lines around 166 to 181), then validates the local file. That solves resuming against a server you do not control. **Flash controls both ends and already has the stronger form**: a persisted done-set of verified chunks, per-chunk hashes, the whole-file check (ADR-068), and, once FA-4 and the manifest exist, a signed content id. Nothing in Ketch's HTTP path should be copied; the identity-validation idea is already covered by AGENTS section 18.

### 11.3 What I recommend (feeds decisions D7 and a new D13)
1. **Do not add Ketch or anitorrent as a dependency.**
2. **Build the swarm module as designed (sections 4 and 5), using Ketch as a reference.** Read its rarity picker, scheduler and budget before writing ours; write ours **sans-IO** (events in, commands out) instead of mutex/coroutine actors, so the simulator in section 5.2 can run it on a virtual clock. That property is the main thing Ketch's code does not give us.
3. **If code is adapted** (a few dozen lines, for example the rarity counting), it is allowed by Apache-2.0 but must carry the licence notice: add a Ketch entry to `NOTICE`, a header comment naming the source file and what changed, and record it in the module's ADR. Prefer re-implementing from the behaviour described here when a copy would not save real time.
4. **D13 (owner):** confirm the policy "reference freely, copy only small attributed pieces, never depend on GPL code".

### 11.4 Not verified
- I did not build or run Ketch, read its `PeerWire` / `TorrentSwarm` / storage code, or check its test results; claims about its robustness are limited to what its own docs state.
- Whether `TorrentNetwork` could carry traffic over our sessions without copying data twice (untested).
- The GPL consequence is my reading of the licence terms, not legal advice.
