# Error & Fix Log — Stream A (rvw22-a-messaging)

Source: `docs/audit/2026-10-22-comprehensive-brutal-code-review.md`, stream A findings
(F34, F35, F36, F37, F54, F55, F65, F66). ERROR range 760..769, prefix `R22A-`.
All five entries below are **RESOLVED in unit tests on the branch, NOT device-verified**.

Not entered as ERRORs, and why (details in `../../audit/…` and the stream report):

- **F35** — a removed group member can backdate `sentAt` because the roster has no
  removal time. Real, but nothing to fix safely yet: `GroupMemberEntity` carries
  `joinedAt`, `membershipVersion`, `operationId` and an `isActive` tombstone, but
  **no removal timestamp**. `membershipVersion` is a certificate `seq` on the v2
  roster paths and plain wall-clock `now` on the legacy write
  (`RealFlashChatRepository:2342`), so it cannot order a message against a removal;
  and neither `MemberCert` nor `GroupCharter` carries `issuedAt`. Any check would
  compare against a value the removed device supplies. **DEFERRED on an owner
  decision** (signed removal timestamp + schema column). Note `dev` `081b1ddb`
  (RVW22 F12) now filters *future* sends with `isActive`, which is the right check
  for who gets a message — it does not help with a message already stamped before
  the removal, which is F35's case.
- **F55** — "the radio path bypasses receipt handling". Not a defect: radio
  delivery is a link-layer cryptographic ACK (`RadioChatEngine.queueAckFrame`,
  `_deliveryReceipts`), not a chat `DeliveryReceipt`, and `RadioChatBridge.sendDirect`
  accepts only `TextMessage`, so no receipt or reaction path can exist there.
  **NOT-A-BUG.**
- **F66** — `HubFileKey` / `GroupSecret` are not zeroed. Real, but there is no safe
  owner for `clear()`: `GroupSecret` instances are cached in `RoomGroupSecretStore`
  and reused for proofs, and `HubFileKey` is re-decoded from `keyText` per step by
  `HubFileTransfers`/`HubFileBinding` (an owner-edited file) and shared by live
  transfers. Zeroing one copy would break a running transfer or a proof, not
  shorten the secret's life. **DEFERRED.**

---

## ERROR-760 — A late delivery receipt walked an acknowledged row back to DELIVERED (R22A-34, F34)

### Date
2026-10-22

### Area
Messaging / delivery status (`:core:messaging`)

### Symptoms
A message that had been read on the peer could show grey ticks again. The bubble
lost its read tick with no user action and no network loss.

### Environment
- Android app, desktop app, unit host tests (JVM + Android host).
- Compose SDK / AGP as in the repository at `dev` `f3385069`.

### Error
No exception. Observable as a status regression: `READ` → `DELIVERED`.

### Root cause
Both receipt handlers wrote the status with the **unconditional**
`MessageDao.updateStatus`. A `DeliveryReceipt` can arrive after a `ReadReceipt`:
as a duplicate, over a second path (LAN frame + hub relay), or as a re-delivery
after a reconnect. ERROR-031 had already established the guarded write
(`updateStatusIfUnacknowledged`) for the drain path, but the two live receipt
sites were never switched over.

### Failed attempts
None — the defect was found by inspection and reproduced directly in a unit test.

### Working fix
`RealFlashChatRepository` line ~6023 (LAN/hub frame handler) and ~655 (radio
gateway ACK callback) now call `messageDao.updateStatusIfUnacknowledged(...)`.
`outboxDao.delete` stays unconditional: the receipt is the outbox commit point.
`MessageDao.updateStatus` is kept for tests/seeding with KDoc stating it must not
be used for a delivery transition.

### Verification
Three new tests in `RealFlashChatRepositoryTest`: read-then-late-delivery stays
READ (LAN path), the same on the radio path through `FakeRadioOutboxPort`, and
`FAILED → DELIVERED` still happens (the peer proved possession). Mutation: restoring
both unguarded writes fails the two READ tests and leaves the FAILED test green.

### Related files
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt`
- `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryTest.kt`

### Status
RESOLVED (unit-tested; device check `R22A-34` owed)

---

## ERROR-761 — Voice-note and call-event marker rows were returned as search results (R22A-36, F36)

### Date
2026-10-22

### Area
Search (`:core:persistence` SQL, `:core:messaging` repository, `:ui:chat` results)

### Symptoms
Searching for plain digits returned voice notes and call events whose bubble shows
a waveform or "Call ended"; the result snippet was a raw blob such as
`vmsg:4200:0.13,0.42,…`.

### Environment
As ERROR-760.

### Error
No exception; wrong rows in `MessageDao.searchMessages` /
`searchConversationMessages`.

### Root cause
A voice note stores `vmsg:<durationMs>:<amplitudes>` and a call event
`cmsg:<kind>:<video>:<durationMs>` in the `text` column, and both search queries
matched the whole column with `LIKE`. The `text` match therefore returned internal
encoding as if it were message content.

### Failed attempts
Not applicable — verified by reading the encoder (`VOICE_META_PREFIX` /
`CALL_META_PREFIX` in the repository) and the two queries.

### Working fix
Two prefixes moved into one place, `MessageTextMarkers` in `:core.persistence`,
next to the queries that must ignore them (the repository aliases the constants, so
encoder and SQL cannot drift). Both queries exclude marker rows from the **text**
match via `text NOT LIKE '<marker>%'`; the attachment-name match is untouched, so a
voice note is still findable by its file name. `searchChats` additionally guards the
message-hit construction with `isInternalMarkerText`, so a row returned by a *name*
match cannot leak the blob as a snippet. Room/KSP accepts constant string
concatenation inside `@Query` (proven by the test). The two in-memory `MessageDao`
fakes mirror the rule.

### Verification
`MessageSearchMarkersJvmTest` (4 tests through the real Room database) and
`RealFlashChatRepositorySearchMarkersTest` (2 tests, repository + database).
Mutation A: drop the SQL filter → `MessageSearchMarkersJvmTest:57` and `:71` fail.
Mutation B: drop the repository guard → `RealFlashChatRepositorySearchMarkersTest:79`
fails. Both restored.

### Related files
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/MessageTextMarkers.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt`
- `core/messaging/.../RealFlashChatRepository.kt` (search guard)
- `core/persistence/src/jvmTest/.../MessageSearchMarkersJvmTest.kt`
- `core/messaging/src/jvmTest/.../RealFlashChatRepositorySearchMarkersTest.kt`

### Status
RESOLVED (unit-tested; device check `R22A-36` owed)

---

## ERROR-762 — The one legal frame size for an empty plaintext was rejected as too short (R22A-37, F37)

### Date
2026-10-22

### Area
Security / `E2eFrameCodec` (`:core:security`)

### Symptoms
A conformant sender's frame for an empty payload was refused with the codec's
"too short" error before authentication ran.

### Environment
All targets (commonTest runs on JVM and Android host).

### Error
`IllegalArgumentException`/the codec's own length rejection, not a tag mismatch.

### Root cause
The guard was `require(frame.size > NONCE_BYTES + TAG_BITS / 8)`. An empty body
seals to exactly `nonce (12) + tag (16) = 28` bytes, and `28 > 28` is false, so the
smallest legal frame was rejected. GCM authenticates an empty plaintext like any
other and nothing in the protocol promises a non-empty payload.

### Failed attempts
None — a boundary test reproduced it immediately.

### Working fix
`>=` instead of `>`. Frames smaller than 28 bytes still cannot hold a tag and are
still rejected with the same message.

### Verification
`E2eFrameCodecEmptyPlaintextTest`, 4 cases: empty round trip, empty over the
`FLASH_SEC` wire, 27 bytes still rejected, 28 bytes reaches GCM. Mutation: back to
`>` fails 3 of the 4.

### Related files
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt`
- `core/security/src/commonTest/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodecEmptyPlaintextTest.kt`

### Status
RESOLVED (unit-tested on every target; not practical to trigger by hand)

---

## ERROR-763 — The Links search scope only examined the first link of a message (R22A-54, F54)

### Date
2026-10-22

### Area
Search (`:core:messaging` `FlashSearchText`, `:ui:chat` `FlashChatSearchHits`)

### Symptoms
Searching for a URL that appears as the *second* link of a message returned
nothing under All → Links, although the message shows both links.

### Environment
As ERROR-760.

### Error
No exception; a missing result.

### Root cause
`matchesLink` delegated to `firstUrl(text)`, which by contract returns the first
`URL_REGEX` match only. The second and later links were never compared.

### Failed attempts
None — the call site made the cause obvious.

### Working fix
One private `links(text)` sequence over `URL_REGEX.findAll`, shared by `firstUrl`
(still the first link, used by the "contains a link" badge) and `matchesLink`
(any link). The trailing-punctuation trimming rule now has a single definition.
`matchesLink` stops at the first link containing the query.

### Verification
`:core:messaging` `FlashSearchTextTest` (second link matches, first still matches,
prose between links does not, `firstUrl` unchanged — 9/9 green) and a `:ui:chat`
case through the real Links scope (`the Links scope finds a message by its second
link too`). Mutation: `links(text).take(1).any` fails `FlashSearchTextTest.kt:71`.

### Related files
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/model/FlashSearchText.kt`
- `core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/model/FlashSearchTextTest.kt`
- `ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashChatSearchHitsTest.kt`

### Status
RESOLVED (unit-tested; device check `R22A-54` owed)

---

## ERROR-764 — A malformed published sealing key threw from the send path, and a null envelope fell back to plaintext (R22A-65, F65)

### Date
2026-10-22

### Area
Security / hub sealing (`:core:security` `HubSealing`, `:core:messaging`
`DirectoryHubSealing`, `HubChatBridge`) — **security relevant**

### Symptoms
Two symptoms with one cause:
1. Sending to a peer whose published sealing key is corrupt crashed with
   `java.security.spec.InvalidKeySpecException` (JVM) out of `ecP256SharedSecret`,
   i.e. the app died in the send path.
2. Even if the throw were merely caught, the payload would have been sent **in the
   clear**: `sendPayload` treated "no envelope" as "the peer published no key" and
   fell through to the readable-content branch.

### Environment
As ERROR-760. The malformed key is reachable from a modified client only.

### Error
```text
java.security.spec.InvalidKeySpecException
  at com.transfer.flash.core.security.crypto.<ecP256SharedSecret>
  <- HubSealing.seal
  <- DirectoryHubSealing.seal
  <- HubChatBridge.sendPayload
```

### Root cause
`DirectoryHubSealing` accepts a recipient key only when the identity key the hub
certified for that device signed it — but that signature covers the key's **bytes**;
it does not validate them as a P-256 point. A modified client can therefore publish
garbage under a perfectly valid binding signature, the status is legitimately
`AVAILABLE`, and the ECDH throws. The bridge then had a second, worse defect: its
`when` treated `sealed == null` as "readable content allowed?" instead of "cannot
seal now", so with the readable-content opt-in on, a message the user chose to seal
would travel readable through the hub.

### Failed attempts
None taken from the audit's literal advice. The audit said "wrap in `runCatching` and
return null"; doing only that would have converted a crash into a silent plaintext
leak at this call site, because null already means "peer has no key" to the caller.

### Working fix
- `HubSealing.seal` now returns `String?`; the ephemeral key pair, the ECDH and the
  signing are inside one `runCatching`, so a Keystore refusal is handled the same
  way as a bad key. KDoc states that null is **not** "no key for this peer" and that
  the callers must never fall back to readable content.
- `DirectoryHubSealing.seal` passes the null through, with a comment that the status
  is honestly still `AVAILABLE`.
- `HubChatBridge.sendPayload`: `AVAILABLE` + null → log
  (`HUB: sealing key unusable, not sending readable id=…`) and `return false`; the
  message stays queued and is retried when the directory serves a usable key. The
  readable branch is now reachable only from `NOT_PUBLISHED`/`CHANGED`, which is what
  the opt-in actually means.
- Test call sites use `requireNotNull`/`assertNotNull` so a key that stops sealing is
  a loud failure.

### Verification
Three levels, all green: `HubSealingTest` (mangled 65-byte point and empty key both
return null), `HubSealingPairedIdentityTest` (the **real** `DirectoryHubSealing` with
garbage signed by its own identity key: status `AVAILABLE`, seal null, plus the
"ordinary sealing still works" companion), `HubChatBridgeTest` (scripted
AVAILABLE-but-unusable port: nothing reaches the relay while the opt-in is on, and
the message goes out as `ENVELOPE_TYPE` once the key works). Mutation 1 replaced
`.getOrNull()` with `.getOrThrow()` and reproduced the audit's
`InvalidKeySpecException` in both key tests; mutation 2 restored the plaintext
fall-through and failed the bridge test. Server sealing e2e
(`E2eSealedMessageContractTest`, `E2eHostSealingLifecycleTest`,
`E2eSealingKeyContractTest`, 15 tests) re-run green after the signature change.

### Related files
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubSealing.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/DirectoryHubSealing.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubChatBridge.kt`
- `core/security/src/commonTest/.../HubSealingTest.kt`, `HubSealingKeyStoreTest.kt`
- `core/messaging/src/androidHostTest/.../HubSealingPairedIdentityTest.kt`
- `core/messaging/src/commonTest/.../HubChatBridgeTest.kt`, `HubFileTestFakes.kt`
- `server/src/test/.../E2eSealedMessageContractTest.kt`, `E2eHostSealingLifecycleTest.kt`

### Status
RESOLVED (unit-tested at three levels + server e2e; no by-hand device test is
possible without a modified client — see `backlog-additions.md` `R22A-65`)
