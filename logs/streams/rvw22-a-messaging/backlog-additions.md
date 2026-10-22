# Test Backlog Additions — Stream A (rvw22-a-messaging)

For the owner to merge into `docs/testing/TEST-BACKLOG.md` (§35). IDs `R22A-*` are
this stream's series; each carries the source ERROR. Nothing here was run on a
device: the work is **unit-tested, not device-verified**.

Section suggestion: a new `## 4zzzzzzzzzzzzzzzzzzz (R22A — messaging, search and hub
sealing, 2026-10-22)` block, or append to the existing RVW22 block.

---

### R22A-34 — A late delivery receipt does not un-read a message (ERROR-760)

**Setup:** two phones paired, chat open on phone A, both on the same Wi-Fi.
**Steps:**
1. Send a message from A to B, wait until A shows the delivered tick.
2. On B, open the chat so the read receipt is sent; A shows the blue read tick.
3. Put B in airplane mode for ~30 s and back on, then send another message from B
   to A (forces a reconnect and a re-delivery of pending frames).
4. Repeat while the hub is enabled for the same conversation (LAN + hub paths for
   one message).
**Pass:** A's first message never leaves the read state; no grey tick returns.
`adb logcat -s TRANSFER | grep -i "status="` shows no `DELIVERED` write for a row
already `READ`; the row stays `READ` in `Transfers`/bubble ticks for 60 s.
**Fail:** any read tick reverting to delivered.
**Source:** ERROR-760 / F34 / ADR-PROPOSED-A2 in `decisions-additions.md`.
**Status:** TODO

---

### R22A-36 — Internal markers are not searchable (ERROR-761)

**Setup:** one phone with at least one voice note (about 4 s) and one call event in a
chat, plus a message whose text really contains the digits searched for.
**Steps:**
1. Record a 4 s voice note; note the duration (`4200` etc.).
2. Global search for the duration number, e.g. `4200`.
3. Global search for a fragment you know is in the marker text, e.g. `vmsg` and
   `cmsg`.
4. In-chat search (the search bar inside the conversation) with the same queries.
5. Search the voice note's file name — it must still be found.
**Pass:** steps 2-4 return **no** voice note or call event; step 5 returns the voice
note with its file name as the snippet, never a `vmsg:`/`cmsg:` string.
**Fail:** any `vmsg:`/`cmsg:` text visible in a result snippet, or a call event in the
results of a digit query.
**Source:** ERROR-761 / F36.
**Status:** TODO

---

### R22A-54 — The Links scope finds a message by its second link (ERROR-763)

**Setup:** one phone. In a chat, send a message with two links:
`read https://first.example/a and https://second.example/b`.
**Steps:**
1. Global search, Links scope, query `second.example`.
2. Same query with `first.example`.
3. Same two queries in the in-chat search bar's Links scope.
4. Query a word from the prose between the links (e.g. `and`) in the Links scope.
**Pass:** steps 1 and 2 both return the message; step 4 returns nothing (the prose is
not a link).
**Fail:** `second.example` returns nothing while the message clearly shows the link.
**Source:** ERROR-763 / F54.
**Status:** TODO

---

### R22A-37 — An empty-plaintext frame round-trips (ERROR-762)

**Setup:** none by hand — a 28-byte frame (12-byte nonce + 16-byte tag) can only be
produced by a conformant sender with an empty payload, and no current UI path sends
one.
**Coverage instead:** `E2eFrameCodecEmptyPlaintextTest` (4 cases, JVM + Android host),
proven load-bearing by reverting the guard.
**Pass:** the unit suite stays green; if a future feature sends an empty payload, two
devices must exchange it without a "too short" error in the `TLS`/`TRANSFER` logs.
**Source:** ERROR-762 / F37.
**Status:** NOT-PRACTICAL-BY-HAND (unit-covered; revisit if an empty payload becomes
reachable)

---

### R22A-65 — A tampered peer cannot make this device send readable text (ERROR-764)

**Setup:** needs a **modified** client (or a hand-seeded hub directory row) that
publishes a sealing key which is not a P-256 point but is signed by its own identity
key. A stock phone cannot produce this.
**Steps (lab):**
1. On the hub console (or a patched client), set a peer's `sealingPublicKey` to 65
   bytes of `0x42` with a valid `sealingSignature` from that peer's identity key.
2. From the stock phone, with "readable content through the hub" **on**, send a 1:1
   message to that peer while no LAN path exists (so the hub path is used).
3. Watch the hub's message store / console log for the plaintext body.
**Pass:** nothing is stored for that message; the phone logs
`HUB: sealing key unusable, not sending readable id=...` and the bubble stays queued
(sending/clock icon), and the app does **not** crash.
**Fail:** the message text appears unsealed in the hub, or the app crashes with
`InvalidKeySpecException`.
**Coverage now:** `HubSealingTest`, `HubSealingPairedIdentityTest`,
`HubChatBridgeTest`, mutation-checked in both directions, plus the server's sealing
e2e classes.
**Source:** ERROR-764 / F65 / ADR-PROPOSED-A1 in `decisions-additions.md`.
**Status:** BLOCKED (needs a modified client; unit + e2e covered). Recorded so the
plaintext-fallback rule is not silently lost.

---

### R22A-F35 / R22A-F66 — not owed as tests

F35 (a removed group member backdates `sentAt`) and F66 (key material not zeroed) are
**DEFERRED** on the data model / an owner decision, not on a test. No test can pass
before the fix exists; see `errors.md` and the stream report.
