# Error & Fix Log — Stream A (rvw22-a-messaging)

Source: `docs/audit/2026-10-22-comprehensive-brutal-code-review.md`, stream A findings
(F34, F35, F36, F37, F54, F55, F65, F66). ERROR range 760..769, prefix `R22A-`.
All six ERROR entries below are **RESOLVED in unit tests on the branch, NOT
device-verified**. Two findings are deliberately not fixed; their long form — the
audit's words, the code, the patch a fix would be, and the decision it needs — is in
`deferred-and-residual.md`, together with the corrections to two earlier summaries.

Not entered as ERRORs, and why:

- **F55 — DEFERRED, not a bug-by-design (corrected).** The audit's claim is that
  `transportSink` falls back to the hub but never to the radio, so a radio-only peer
  never receives read receipts or reactions. That is confirmed
  (`RealFlashChatRepository.kt:698-707`, with `DeliveryReceipt` at `:6008`,
  `ReadReceipt` at `:1889`, `ReactionFrame` at `:6697`). What is genuinely covered is
  *delivery*, by the radio link-layer cryptographic ACK (`RadioChatEngine.queueAckFrame`,
  `_deliveryReceipts`); read state and reactions are not. The audit's one-line fix
  cannot be applied because `RadioChatBridge.sendDirect` takes a concrete
  `MessageWireFrame.TextMessage` and the radio port has no frame-kind field — it needs
  a radio wire-format addition or a documented "radio is text-only" decision.
  **DEFERRED on an owner decision**, see `deferred-and-residual.md` §2.
- **F66 — DEFERRED.** `HubFileKey` / `GroupSecret` are not zeroed. Real, but there is
  no single owner for `clear()`: `GroupSecret` is consumed by the proof, rotation and
  handover paths (each with its own copy — `toByteArray()`/`fromBytes()` copy), and
  `HubFileKey` is decoded fresh from the stored text at every step
  (`HubFileTransfers.kt:243`, `:257`, `:493`; `HubFileBinding.kt:487`) and held for
  the life of a resumable transfer. Zeroing one instance would break a live transfer
  rather than shorten the secret's life, and the Base64 `String` round trips cannot be
  zeroed at all. **DEFERRED**, see `deferred-and-residual.md` §3. (An earlier note
  said the secrets were cached in `RoomGroupSecretStore`; they are not — that store
  re-decodes per call.)


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

---

## ERROR-765 — A former member's signed history was dropped on catch-up (R22A-35, F35)

### Date
2026-10-22

### Area
Groups / signed groups (`:core:messaging` `SignedGroups`, `RealFlashChatRepository`)

### Symptoms
A device that caught up on a group after a member had left was missing that member's
messages. The group's own history silently lost the part written by whoever had
gone; no error was shown to the user.

### Environment
Two or more devices, v2 signed group (`gs1`), catch-up through a member
(`GroupWireFrame.SyncPush`). Reproduced in `:core:messaging` host tests.

### Error
No exception. The evidence probe recorded it as a signature failure, which is what
made it look like a security refusal:

```text
group.sync.drop  group=… from=… reason=bad_signature author=… hasOffer=false
CHAT: SECURITY: group SyncPush message dropped, no valid signature (…)
```

### Root cause
`SignedGroups.verifiedAuthorLabel` looked the author up with
`members.member(groupId, authorId)?.takeIf { it.isActive }`. A member who leaves or is
removed stays as a **tombstone** row (`isActive = false`, key and cert retained), so
the lookup returned null and the caller (`handleSyncPush`, `RealFlashChatRepository`
~5360) reported `bad_signature` and dropped a message whose signature was perfectly
valid. The active requirement was redundant on every live lane — each of those gates
membership itself *before* the signature check (`isActiveGroupMember` /
`isActiveTrustedMember` / `isRemovedHere`) — and destructive only on catch-up, which
has no other per-author gate.

### Failed attempts
None; the defect was confirmed by reading the call sites and then reproduced with a
test that adds a newcomer after a member has left.

### Working fix
`verifiedAuthorLabel` now accepts a tombstoned row and verifies the signature against
the certified key the tombstone still carries; an author with no row at all is still
refused, and a bad signature is still refused. KDoc rewritten to say that the method
answers *who wrote this*, not *who may speak now*, and names the residual below. The
comment at the SyncPush call site was corrected to match.

### Verification
`SignedGroupsTest`: *history a member signed before leaving still reaches a
newcomer* — dev-b speaks, leaves, dev-d is added, and dev-d receives dev-b's message
with the tombstone's roster label and its signature. Mutation: restoring
`takeIf { it.isActive }` fails that test and **only** that test, while
*a member who leaves is a tombstone everywhere and their later messages are dropped*
keeps passing — the proof that live enforcement is independent of this lookup.
`:core:messaging:jvmTest` 367 and `:core:messaging:testAndroidHostTest` 666 green.

### Residual (needs an owner decision)
The catch-up lane trusts the holder it asked, so a current member could relay a
former member's *late* message into another device's history; without a removal time
the receiver cannot tell when it was written. `GroupMemberEntity` has `joinedAt` and a
`membershipVersion` that is a certificate `seq` on v2 paths but wall-clock `now` on the
legacy write (`RealFlashChatRepository:2342`, removal at `:2593`), and neither
`MemberCert` nor `GroupCharter` carries a removal/issue time. Closing it needs a signed
removal time on the roster plus a schema column — its own plan, written up in
`deferred-and-residual.md` §1.

### Related files
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/SignedGroups.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/SignedGroupsTest.kt`

### Status
RESOLVED (unit-tested; device check `R22A-35` owed)
