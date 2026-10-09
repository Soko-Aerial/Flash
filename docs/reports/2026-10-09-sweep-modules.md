# Module bug sweep, 2026-10-09

Reviewer: bug-hunting subagent (read-only). Branch `dev`, working tree with many uncommitted edits at the time of reading.
Scope: every Gradle module (core:*, ui:*, app, desktop, third_party, sample, buildSrc), depth on older code, only a light pass
on the 2026-10-09 code (group history sync, radio/serial link, screen share), which another reviewer covers.

No source, test, build, log or doc file other than this report was changed. No Gradle task was run, nothing was built or
executed. Every finding below comes from reading the code, so "CONFIRMED" means "traced through the exact lines and callers",
not "reproduced on a device". Where a magnitude is an estimate it says so.

Labels: CONFIRMED = the defect exists in the code as read and I traced the call path end to end. PLAUSIBLE = the code
supports the failure, but a precondition (a hostile peer, an OEM behaviour, a timing window) was not shown to be reachable.

Honest limits of this pass:

- The earlier part of the sweep was compacted mid-session. Two minor notes from it (labelled F14 and F19 in my working list)
  could not be reconstructed with certainty and are not reported. The numbering below (R-xx) is new.
- Large parts of `core:calling`, `core:ptt`, `core:discovery`, `core:swarm` (engine and driver), all of `ui:*`, `third_party`,
  `sample`, `buildSrc` and the tests were NOT read in depth. See the coverage table. A clean section for those modules means
  "not examined", not "no bugs".

---

## Summary of findings

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | none confirmed |
| High | 1 | R-01 |
| Medium | 8 | R-02 to R-09 |
| Low | 11 | R-10 to R-20 |

Top findings:

1. R-01 (High, CONFIRMED mechanism): `transfer_chunks` rows are never deleted on success or cancel, and every process start
   reloads all of them into a vector that grows one chunk at a time, under a lock the receive path needs. Cost grows with the
   square of the largest persisted transfer.
2. R-02 (Medium, CONFIRMED): the outbound dial `connectManual(host, port, peerId)` never checks that the HELLO device id equals
   the id that was dialed, so a paired peer can be registered as another paired peer.
3. R-03 (Medium, CONFIRMED missing check): the group-proof initiator accepts a bare `GsResult(ok)` without having verified the
   responder, defeating the "mutual" proof.
4. R-04 (Medium, PLAUSIBLE): a partly received file counts as "already completed" when its length equals the offered size, and
   the sender is told `verified = true`.
5. R-05 (Medium, CONFIRMED): the Android share target reads any URI an arbitrary app hands it, including `file://` URIs into
   Flash's own private storage.

---

## Findings

### High

#### R-01 Chunk done-set rows are never cleaned up, and the startup preload is quadratic and holds the receive lock

- Files: `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt:975-993`
  (`preloadReceiverProgress`), `:1003-1025` (`onIncomingChunkConfirmed`), `:516` and `:1290` (the only `clearDoneChunks`
  calls); `core/persistence/.../db/dao/TransferChunkDao.kt` (`allDoneChunks`, no ORDER BY, no filter);
  `core/persistence/.../db/entity/TransferChunkEntity.kt` (PK `transferId, chunkIndex`, no foreign key to the transfer row);
  callers `core/engine/src/androidMain/.../Flash.kt:730` and `app/.../debug/DiscoveryEngineHolder.kt:1824`.
- Status: CONFIRMED for the mechanism. The cost figures are my arithmetic, not measurements.
- Scenario, part 1 (growth): chunk rows are deleted only when a whole-file check fails (`:516`, `:1290`). A transfer that
  completes, is cancelled, is declined, or whose history row is cleared keeps one row per chunk forever. There is no foreign
  key cascade and no other delete call anywhere in `core/`. A 10 GB receive at 64 KiB chunks leaves about 160 000 rows.
- Scenario, part 2 (startup cost): `preloadReceiverProgress` reads every row of every transfer into one list, then for each row
  allocates `ResumeBitVector(row.chunkIndex + 1)` whenever the index exceeds the current vector, copying through
  `existing.doneIndexes()` (a boxed list of every set bit). Rows come back in primary-key order, so each row has a larger
  index than the last: every row reallocates. For one transfer with n chunks this is about n^2 / 2 boxed-int operations
  (about 1.3e10 for n = 160 000). `onIncomingChunkConfirmed` has the same grow-to-max-index pattern per ACK batch.
- Scenario, part 3 (lock): the whole loop runs inside `receiverDoneLock.withLock`, and `receiverDoneIndexes` (the resume seed
  used by `ReceivePipeline.handleFileStart`) and `onIncomingChunkConfirmed` take the same lock. An inbound resume that arrives
  while the preload is still running blocks on it.
- Impact: every cold start after a large received file burns CPU and allocates heavily on a background scope, and can stall
  an inbound resume. With enough history (several million rows) the unbounded `List<ChunkIndexRef>` is also an OOM risk at
  start-up. This affects exactly the large-file use case the app targets.
- Fix: delete the rows when a transfer reaches a terminal state (Completed, Cancelled, Declined) and cascade when a history
  row is deleted; add a startup sweep for orphaned rows; load only rows of non-terminal transfers; size the vector from the
  transfer's `totalChunks` (known from the transfer row or `FILE_START`) instead of growing it, or sort and size once per
  transfer using `MAX(chunkIndex)` in SQL; do the fill outside the lock and swap the finished vectors in.

### Medium

#### R-02 Outbound dial does not bind the HELLO device id to the dialed id

- Files: `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt:311-363` and the identical
  Android twin `core/network/src/androidMain/.../ws/WsFlashNetwork.kt` (`connectManual`, around lines 340-460).
- Status: CONFIRMED (missing check). Exploit needs a paired or key-holding malicious peer.
- Scenario: when the dial names a peer X, TLS pins X's key. The post-HELLO identity check only runs `if (deferredLeaf != null)`
  (the unnamed-dial case, line 335). In the named-dial case nothing compares `peerDevice.id` (from HELLO) with
  `resolvedPeerDeviceId`. The code at lines 355-363 even acknowledges "when HELLO named a different device than the dial did"
  but only skips the route report; it still builds the `WsSession` for `peerDevice` and calls `registerSession`. A paired peer X
  that answers our dial can send `deviceId=Y` in its HELLO and be registered as Y: its chat, group, call and transfer frames
  are then attributed to Y, and it can displace Y's session (glare tiebreak). The inbound side (`inboundIdentityFailure`) does
  enforce the binding, which is why this is an asymmetric gap.
- Fix: after `handshakeOutcome` is known, if `resolvedPeerDeviceId != null && resolvedPeerDeviceId != peerDevice.id.value`,
  close the connection and fail. Apply to both twins together (they are marked "do not edit one copy without the other").

#### R-03 Group-proof initiator accepts `GsResult(ok)` without verifying the responder

- File: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/group/GroupProofSessions.kt:400-422`
  (`onResult`); `core/security/.../group/GroupProof.kt` (`GroupProofInitiator.State` has `VERIFIED`, never consulted here).
- Status: CONFIRMED that the check is missing. The privilege-escalation path in the second scenario is PLAUSIBLE.
- Scenario: the initiator sends `GsHello`. The honest responder answers `GsChallenge` (whose MAC `onChallenge` verifies via
  `receiveChallenge`) and, after the proof, `GsResult`. But `onResult` only looks up `activeInitiators[(peer, group)]` and
  trusts `frame.ok && frame.reason == "ok"`. A peer that never sends a challenge can reply with a bare
  `GsResult(ok = true, reason = "ok")`; the initiator removes its in-flight entry, calls `addProved(peer, group)` and completes
  the deferred with `OK`. `GsResult` is unauthenticated (no MAC).
- Impact: (a) a joiner running `triggerProofForPendingInvites` (`RealFlashChatRepository.kt:3714-3755`) treats any peer that
  advertises `gs1` as having confirmed the group and goes on to `sendJoinRequest`, and a fake peer can then show a fake roster
  preview for the user to confirm; (b) `provedGroups` is also read by `handleInboundJoinRequest`
  (`RealFlashChatRepository.kt:3535`, GINV-2 "session must have proved knowledge of the group secret"). A peer that the local
  device itself initiated a proof towards can therefore mark itself proved without knowing the secret and have a direct
  `GsJoinRequest` accepted for review (and auto-approved under policy "open"). The precondition is that the local device
  initiated a proof towards that peer.
- Fix: in `onResult`, only honour `ok` when the in-flight initiator is in its `VERIFIED` state (challenge MAC checked and our
  proof sent), and keep initiator-side "peer confirmed" separate from the responder-side `provedGroups` set that gates join
  requests (do not call `addProved` from the initiator path unless that is explicitly the intent).

#### R-04 An incomplete file can be reported as already completed and verified

- File: `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt:532-549`
  (`handleSessionStarted`); `fileExistsAndSizeMatches` at `core/engine/src/androidMain/.../Flash.kt:~906` and
  `desktop/.../DesktopEngine.kt:~1891` is only `exists() && length() == bytes`.
- Status: PLAUSIBLE.
- Scenario: the receiver writes chunks at their offsets (random-access sink, multi-stream dispatch). The file's length equals
  the full size as soon as the last chunk is written, even if earlier chunks are still holes. After a disconnect in that state
  the transfer row is Failed/Paused with `localPath` set. The sender re-offers the same transfer id; `alreadyCompleted` is true
  because the length matches; the router answers `Complete(verified = true)` and sends RESUME. The sender marks the transfer
  done while the receiver holds a file with holes, and no hash is checked on this branch. The ADR-068 whole-file check never
  runs because no `Completed` pipeline event follows.
- Fix: treat only `existing.state == Completed` (and a whole-file hash match through `WholeFileVerifier`) as already complete;
  never trust length alone. At minimum require the persisted done-set to be complete.

#### R-05 Android share target trusts any URI an arbitrary app supplies

- Files: `app/src/main/AndroidManifest.xml:99-119` (exported `MainActivity` with `SEND` and `SEND_MULTIPLE` filters for
  `*/*`), `app/src/main/java/com/transfer/flash/MainActivity.kt:355-372` (`extractUrisFromIntent`, which also adds
  `intent.data` for any action), `:374-388` (`resolvePendingShare`), `:3434-3460` (`resolveContentUriNameAndSize`).
- Status: CONFIRMED that no scheme or authority validation exists. Impact depends on the user completing the share.
- Scenario: any installed app can start `MainActivity` with `ACTION_SEND` and `EXTRA_STREAM` set to
  `file:///data/user/0/<package>/files/...` (a `file://` URI into Flash's own private storage; the attacker app only needs to
  target an old SDK or suppress the exposure check on its own side). The activity opens it with its own permissions
  (`ContentResolver` resolves `file:` URIs), `OpenableColumns` returns nothing so the item is shown as "shared_file", and the
  user is offered a normal share sheet. Choosing a peer sends a Flash private file (logs, DataStore settings, received
  files, anything under `files/` or `cache/`). The SQLCipher database and Keystore-wrapped secrets are not usable by a peer,
  so the practical loss is logs, settings and received media, but the pattern is a classic confused deputy.
- Fix: accept only `content:` URIs (and `ACTION_SEND` text); reject `file:` and any `content:` whose authority is Flash's own
  `${applicationId}.fileprovider`. Do not fold `intent.data` into the share list for SEND actions.

#### R-06 Swallowed write failure in the receive pipeline

- File: `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt:289-299`.
- Status: CONFIRMED.
- Scenario: `session.resolvedSink?.write(...)` is wrapped in `catch (_: Throwable) { false }` and a failed write returns
  `emptyList()`: no `Rejected`, no error event, nothing logged. On a full disk, a revoked SAF grant or an I/O error the chunk is
  never ACKed, the sender retries or stalls, and the receiver UI shows no cause. `Throwable` also swallows
  `CancellationException` and `OutOfMemoryError`.
- Fix: emit a typed failure event (e.g. `ReceiveEvent.Rejected(IO_FAILURE)` or a dedicated `SinkFailed`) so the host fails the
  transfer with `TransferFailureText`; rethrow `CancellationException`; log with the transfer id.

#### R-07 Unbounded pre-acceptance allocation, and O(n) work per chunk

- Files: `ReceivePipeline.kt:215` (`ResumeBitVector(frame.totalChunks)`), `:347-358` (`validateFileStart`, no size cap),
  `core/transfer/.../chunked/ResumeBitVector.kt:93` (`isComplete() = receivedCount == totalChunks`, a full popcount).
- Status: PLAUSIBLE (needs a connected peer able to send `FILE_START`; I did not trace which peers may).
- Scenario A: `validateFileStart` only checks that `totalChunks` matches `ceil(totalBytes / chunkSize)` with chunk size at
  least 16 KiB. A frame claiming `totalChunks` near `Int.MAX_VALUE` is accepted and `ResumeBitVector` allocates a `LongArray` of
  about 33.5 million words (roughly 268 MB) before the user has accepted anything. The pipeline admits up to 32 concurrent
  sessions, so one peer can request several GB of heap with 32 frames and crash the process.
- Scenario B (performance, no attacker needed): `handleChunk` calls `session.vector.isComplete()` per chunk, which popcounts
  every word. For a 100 GB file at 64 KiB chunks (1.6 million chunks, 25 000 words) that is about 4e10 word reads in total over
  the transfer, on the receive thread.
- Fix: cap `totalBytes` and `totalChunks` in `validateFileStart` to a documented maximum, allocate the vector lazily after
  acceptance, and keep a running `receivedCount` counter updated in `markReceived`.

#### R-08 `updateGroupContext` can re-attribute an unrelated message or collide on the primary key

- Files: `core/messaging/.../RealFlashChatRepository.kt:3344` (GroupMedia handler, `existsAttachment` branch);
  `core/persistence/.../dao/MessageDao.kt` (`updateGroupContext`: `UPDATE messages SET conversationId, localId = :messageId,
  senderId, senderName WHERE attachmentTransferId = :transferId`); `MessageEntity` (PK `localId` alone, global).
- Status: CONFIRMED for the data model; the hostile trigger is PLAUSIBLE.
- Scenario: a peer sends a `GMEDIA` frame naming a `transferId` that already has an attachment row and a `messageId` of its
  choosing. The update rewrites the existing row's `localId`, conversation and sender from frame fields. If `messageId` already
  exists as another row the statement hits the primary-key constraint and the exception propagates into a repository scope that
  has no exception handler (`scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`, line 189). If it does not collide, the
  existing attachment row is moved to another conversation and attributed to `frame.from`. The caller restricts nothing about
  who owns the transfer id beyond the group gate.
- Fix: only run the update when the existing row's current conversation and sender equal the frame's group and sender, or when
  the row is still unassigned; wrap in `runCatching` and log; use `INSERT OR IGNORE` semantics for the new id.

#### R-09 Keystore identity is deleted on any exception during the capability probe

- File: `core/security/src/androidMain/.../crypto/KeystoreFlashCrypto.kt:86-111`.
- Status: PLAUSIBLE.
- Scenario: the probe is `runCatching { getKey(...); Signature.getInstance("NONEwithECDSA").initSign(key) }.getOrDefault(false)`.
  Any exception (a transient `KeyStoreException` or `ProviderException` right after boot or while the Keystore daemon restarts,
  a StrongBox hiccup, `UnrecoverableKeyException`) yields `false`, after which the code calls
  `keyStore.deleteEntry(IDENTITY_KEY_ALIAS)` and generates a fresh key. The device's identity changes, every peer's TOFU pin no
  longer matches, and all pairings must be redone. The log line blames "DIGEST_NONE" even when the real cause was something
  else.
- Fix: distinguish "initSign threw `InvalidKeyException`" (the key truly lacks DIGEST_NONE) from every other failure; on the
  latter retry and then fail loudly without deleting. Prefer inspecting `KeyInfo.digests` over a signing probe.

### Low

#### R-10 Unbounded sender timestamps on group media and relayed paths

- Files: `RealFlashChatRepository.kt:2731`, `:3258`, `:3363` (`sentAt = frame.sentAt.takeIf { it > 0 } ?: now`), compared with
  `storedSentAt` at `:4630`, which does bound unsigned timestamps. `SwarmHostBinding` derives `expiresAtMs = sentAt + 7 days`
  from the same unbounded value.
- Status: CONFIRMED inconsistency, Low impact.
- Scenario: a group member sends `sentAt` in the far future (or `Long.MAX_VALUE`); the row sorts to the bottom/top forever,
  sits above the unread cursor and, for swarm announcements, the expiry never arrives (or overflows on addition).
- Fix: route all three through `storedSentAt`; use saturating addition for expiry.

#### R-11 Interleaved control frame discards a fragmented WebSocket message

- File: `core/network/src/jvmMain/.../ws/WebSocketCodec.kt:157-220` (and the identical Android twin).
- Status: CONFIRMED.
- Scenario: `messageBuffer` is local to `readMessage`. RFC 6455 allows a PING or CLOSE between fragments; the method returns
  `Message.Ping` immediately and drops the partial data. The next continuation frame then throws "Continuation frame without a
  started message", closing the connection. Flash's own senders never fragment, so it matters only for a non-Flash or future
  client.
- Fix: keep reassembly state across calls (a small state object on the connection) or handle control frames in the loop and
  continue.

#### R-12 Group-proof session maps not bounded

- File: `GroupProofSessions.kt` (`activeResponders`, `DummyResponder`).
- Status: PLAUSIBLE.
- Scenario: each `GsHello` from a peer creates an entry keyed by (peer, group); entries are timed out lazily. The rate limiter
  counts failures, not hellos with varied group ids, so a connected peer can grow the map with distinct random group ids until
  the session ends. Memory per entry is small, so impact is bounded by session lifetime.
- Fix: cap entries per peer and sweep expired ones on insert.

#### R-13 Admin can demote another admin and relabel the owner

- File: `core/messaging/.../protocol/GroupSignatureRules.kt:82-89`, `SignedGroups.kt:850-868`.
- Status: CONFIRMED rule gap; needs a malicious admin.
- Scenario: for `adminIssued` certs the rules block promoting to admin and block deactivating the owner or another admin, but a
  cert with `role = member`, `active = true` for an existing admin passes (it is a demotion), and an active owner-role cert for
  the owner subject is accepted from an admin (a relabel). The sender-side code (`SignedGroups.kt:418`) refuses to remove
  another admin, so the receiver rule is weaker than the documented rule.
- Fix: when `adminIssued`, reject a cert whose subject is currently an admin or the owner unless it is byte-identical in role
  and active state.

#### R-14 Signature verification repeated per certificate per pass in group bundles

- File: `SignedGroups.kt:832-857` (`membersMayAddNow` called inside the cert loop).
- Status: PLAUSIBLE, Low.
- Scenario: `membersMayAddNow()` re-verifies the settings signature on every iteration of every pass, outside the
  `budget.tryConsume` charge (which counts only candidate certs). With up to 84 certs (`MAX_BUNDLE_CERTS`) and a chain that
  resolves one cert per pass, the number of unbudgeted Ed25519 checks is on the order of passes times certs.
- Fix: compute `membersMayAddNow()` once before the loop.

#### R-15 FileProvider exposes the whole private tree and the filesystem root

- File: `app/src/main/res/xml/file_paths.xml` (`files-path "."`, `cache-path "."`, `external-files-path "."`, `root-path "."`);
  `getUriForFile` callers at `MainActivity.kt:488`, `:3113`, `:3207`, `:3389` and
  `ui/platform-shims/.../FlashCameraCapture.android.kt:50`.
- Status: hardening (no exploit shown). The provider is not exported and only grants per-call URI permissions.
- Scenario: any one caller that passes a path derived from peer data would turn into arbitrary-file disclosure, including the
  identity and settings stores, because `root-path "."` and `files-path "."` cover everything.
- Fix: narrow to the specific sub-directories actually shared (`ws-received/`, camera cache) and delete `root-path`.

#### R-16 `GroupInviteCodec` accepts invalid UTF-8

- File: `core/security/.../group/GroupInviteCodec.kt:151`, `:170`, `:177`.
- Status: CONFIRMED, Low.
- Scenario: `runCatching { bytes.decodeToString() }` never fails because `decodeToString()` replaces malformed input with
  U+FFFD by default. A crafted `flash://g/1/...` link can carry a group id, name or inviter id with replacement characters
  that look different from what was signed or compared elsewhere. Group ids are compared by string, so this is a display and
  equality oddity rather than a bypass.
- Fix: use `decodeToString(throwOnInvalidSequence = true)`.

#### R-17 First-run identity persist failure is not handled on desktop

- File: `core/security/src/jvmMain/.../crypto/PersistedFlashCrypto.kt:84-91` (`loadOrGenerate`), `:127-150` (`persist`).
- Status: CONFIRMED, Low.
- Scenario: if `persist` throws on the first run (read-only home, full disk, DPAPI failure), the exception escapes the
  constructor and the desktop app fails to start with no user-facing explanation; the existing-file branch, by contrast,
  falls back to an in-memory identity. Also, on a load failure the code uses a fresh in-memory identity on every start while
  leaving the file, so the pin set the peers hold never matches until the file is readable again (logged, by design).
- Fix: catch the first-run failure, log it, and continue with an in-memory identity plus a visible banner, matching the load
  failure path.

#### R-18 Memory-trim listener registered on every database open

- File: `core/persistence/src/androidMain/.../db/FlashDatabaseOpener.kt:62-85` and `:106-125`.
- Status: CONFIRMED, Low.
- Scenario: `RoomDatabase.Callback.onOpen` can run more than once per process (after a close and reopen, or recovery), and each
  call registers a new `MemoryTrimListener` that captures `db`. Listeners are never removed, so they accumulate and hold closed
  database handles.
- Fix: register once per `FlashDatabase` instance, or unregister in `onClose`.

#### R-19 Static pairwise key with random nonces and no direction or counter binding

- File: `core/security/.../crypto/SecureBinaryFrameCodec.kt` (and `E2eFrameCodec.kt`).
- Status: design note, Low.
- Scenario: a single per-pair AES-256-GCM key is used for the lifetime of the pairing, with 96-bit random nonces and a constant
  AAD (`flash-binary-e2e-v2`). There is no sender-direction or sequence number in the AAD, so a frame can be replayed or
  reflected by anyone able to inject into the (already TLS-protected) stream, and there is no forward secrecy beyond TLS. The
  random-nonce bound (about 2^32 frames per key) is far away at the current chunk sizes (16 KiB minimum chunks give petabytes).
- Fix: record in `docs/security.md`; if the layer is kept, add a direction byte and a counter to the AAD or derive a per-session
  subkey from the TLS exporter.

#### R-20 Desktop helper details

- `desktop/src/jvmMain/.../DesktopNetworkBand.kt:76-82`: `netshInterfaces()` reads the process output with
  `readText()` before calling `waitFor(3, SECONDS)`, so the 3 second timeout never applies; if `netsh` hangs the refresh
  thread blocks forever and `refreshing` stays true (the band is never refreshed again). CONFIRMED. Fix: wait with a timeout
  first, or read on a separate thread.
- `desktop/.../DesktopHelpers.kt:123-140` (`saveImageToGallery`): the target name is `flash_<millis>.<ext>` with
  `REPLACE_EXISTING`, so two saves in the same millisecond overwrite each other, and every failure is swallowed by
  `runCatching` with no feedback to the user. CONFIRMED, trivial.

---

## Findings by module

### app

- Read: `AndroidManifest.xml` (whole), `MainActivity.kt` intent handling (`onCreate`/`onNewIntent` regions, share handling,
  `handleIncomingIntent`), `res/xml/data_extraction_rules.xml`, `res/xml/file_paths.xml`, `DiscoveryEngineHolder.kt` (preload
  call only).
- Findings: R-05, R-15. The deep link `flash://g/*` is BROWSABLE but only fills `pendingJoinLink`; the join then goes through a
  confirmation dialog (per AGENTS.md; I did not read the dialog code), so no auto-join was found. Backup rules exclude
  shared preferences, databases and the log directory from cloud backup and device transfer (reviewed, correct).
  `allowBackup="true"` is acceptable given those exclusions.
- Not read: the remaining ~3 000 lines of `MainActivity.kt`, the services, receivers, Quick Settings tile, notification code.

### desktop

- Read: `WindowsContextMenuManager.kt`, `linux/LinuxNotifier.kt`, `DesktopAutoStartManager.kt`, `DesktopHelpers.kt`,
  `DesktopNetworkBand.kt`; `DesktopEngine.kt` (earlier pass, transfer wiring).
- Findings: R-20. Process execution uses argument lists everywhere I looked (no shell string concatenation), and the
  registry import text is escaped. No injection found.
- Not read: `DesktopShell.kt` (very large), Linux keyring and path classes, identity stores, tests.

### third_party

- Not read. `third_party/webrtc-kmp` is an included build (a fork of an external library); I did not audit it.

### sample

- Not read (three consumer sample apps).

### buildSrc

- Skimmed only: `ThirdPartyNoticesTask.kt` exists. No hard-coded signing material was found in `app/build.gradle.kts` (grep for
  store/key passwords and signing configs found nothing), `local.properties` and `tools/.tavily_api_key` are git-ignored, and
  no keystore, `.pem`, `.p12` or `.env` file is tracked (the `git ls-files` matches for "secret" are source files about group
  secrets, not credentials). Release shrinker configuration was not reviewed here (another agent covers release readiness).

### core:common

- Read: `FlashTextFraming.kt` (key=value with percent-escape), `Base64.kt`, `FlashLogger.kt`, `RotatingFileLogSink.kt`
  (redaction regexes), `SyncCollections.kt`.
- Findings: none confirmed. Redaction of key-like values is regex based, so a new log line that prints a secret under an
  unexpected key name would not be caught; a grep of log calls for secret, passphrase, session key and token found only
  non-secret values.

### core:security

- Read: `KeystoreFlashCrypto.kt`, `PersistedFlashCrypto.kt`, `KeyFileVault.kt`, `IdentityKeyVault.kt`, `SecretSealer.kt`,
  `AndroidPreferencesTrustStore.kt`, `PlatformCrypto.kt` and the JVM actual, `FlashCrypto.kt`, `TofuX509TrustManager.kt`,
  `TofuPinVerifier.kt`, `TofuPolicy.kt`, `FlashTrustStore.kt`, the pairing classes (`FlashPairingProtocol.kt`,
  `PairingSessionStateMachine.kt`, `PairingV2.kt`, `FlashPairingCoordinator.kt`), `E2eFrameCodec.kt`,
  `SecureBinaryFrameCodec.kt`, `GroupProof.kt`, `GroupInviteCodec.kt`, `GroupInvite.kt`.
- Findings: R-09, R-16, R-17, R-19. In `FlashPairingProtocol.kt` several places do `_session.value = _session.value.copy(...)`
  as a read-modify-write on a `MutableStateFlow` (for example around lines 350-362); if two coroutines call in at once an
  update can be lost. Low, unproven, folded into this note rather than a finding. Pairing v2 matches pinned identity
  fail-closed (`matchesPinnedIdentity(..., null)` returns false), so a spoofed pairing id is not exploitable.

### core:network

- Read: `JvmWsFlashNetwork.kt` and `WsFlashNetwork.kt` (connect, handshake, `inboundIdentityFailure`), `WebSocketCodec.kt`,
  `WsTransferClient.kt`, `FlashTlsContextFactory.kt`. The JVM and Android copies were diffed and are identical except
  `WsTransferClient` and `TcpHostProbe`, so a read of one covers both.
- Findings: R-02, R-11. WebSocket length limits are enforced before allocation (64 KiB pre-handshake, 4 MiB after, control
  frames at most 125 bytes, header 16 KiB); this is well done.
- Not read: `WsConnection.kt` keepalive internals beyond skimming, `HelloFeatures.kt`.

### core:persistence

- Read: `FlashSchemaSteps.kt` (steps 1 to 13 are contiguous), `FlashMigrations.kt`, `FlashDatabase.kt`
  (`DATABASE_VERSION = 13`), `FlashDatabaseOpener.kt`, `JvmFlashDatabaseOpener.kt`, `JdbcCipherStatement.kt`,
  `JdbcCipherSQLiteDriver.kt`, `TransferChunkDao.kt`, `TransferChunkEntity.kt`, `MessageDao.kt`, `MessageEntity.kt`.
- Findings: R-01 (no cleanup and no foreign key on chunk rows), R-08 (global PK on `localId`), R-18. No destructive
  migration fallback outside the in-memory test opener (clearly marked test only).
- Not read: the individual migration SQL beyond checking continuity, schema JSON files.

### core:transfer

- Read: `ReceivePipeline.kt`, `ResumeBitVector.kt`, `RealFlashTransferRepository.kt` (receiver bookkeeping, completion and
  failure paths), `RandomAccessSinkHandle.kt`, `DestinationPolicy.kt`, `FlashPathSanitizer.kt`.
- Findings: R-01, R-06, R-07. Path sanitising and canonical-path containment in the sink factory looked correct; I found no
  traversal.
- Not read: `SendPipeline.kt`, multistream and concurrent dispatchers, manifest classes.

### core:engine

- Read: `FlashInboundRouter.kt` (session start, completion, owner gate), `Flash.kt` (Android wiring around transfers and
  sink factory), `SwarmHostBinding.kt`, `RoomTransferStore.kt`.
- Findings: R-04. The owner check (a transfer id belongs to the peer that first used it) and the pre-pipeline path-safety gate
  work as designed.
- Not read: `FlashEngine.kt`, store recovery classes (`EncryptedDatabaseRecovery`, `KeystorePassphraseProvider`), the rest of
  `Flash.kt`.

### core:messaging

- Read: `RealFlashChatRepository.kt` (group media, join request/decision, roster preview, proof triggers, secret handover,
  delete-for-everyone), `GroupProofSessions.kt`, `GroupFrameCodec.kt`, `GroupSignatureRules.kt`, `GroupSigning.kt`,
  `RosterGroupGate.kt`, parts of `SignedGroups.kt` (bundle handling), `PttAudioFrame.kt`.
- Findings: R-03, R-08, R-10, R-12, R-13, R-14. Frame codecs are bounds-checked and non-throwing; the PTT audio frame decoder
  validates lengths before copying; delete-for-everyone checks both conversation and sender.
- Not read: `ChatTextFrameCodec`, most of the 5 000+ line repository (outbox, retry deadlines, typing, reactions, drafts),
  `GroupHistoryPolicy.kt`, `GroupSyncPolicy.kt` (2026-10-09 code, light pass only).

### core:swarm

- Read: `SwarmFrameCodec.kt`, `ManifestCodec.kt`. Both validate sizes, counts and roots before allocation.
- Findings: none confirmed in these files (the unbounded `sentAt` that feeds the expiry is R-10, in messaging).
- Not read: the sans-IO engine, scheduler, storage and driver.

### core:calling

- Read: `CallFrameCodec.kt` (decode), the start of `CallCoordinator.onInboundText`.
- Findings: none confirmed. The coordinator rejects any frame whose `from` differs from the transport peer id, except the
  relayed `GroupJoin`, and it checks group trust for presence and query frames. Numeric fields (`count`, `vfree`, `max`) are
  parsed without an upper bound but are display-only.
- Not read: session classes, router, media, tuning (large uncommitted edits in the working tree were not inspected).

### core:ptt, core:discovery

- Not read beyond one grep: `core/discovery` has one TODO marker (`NsdTransport.kt`). No other TODO, FIXME, HACK or XXX appears
  in `core/*/src/*Main`.

### ui:chat, ui:callui, ui:theme, ui:platform-shims

- Not read, except the FileProvider call in `ui/platform-shims/.../FlashCameraCapture.android.kt:50` (see R-15). UI code was
  out of reach in this pass.

### Tests

- Not reviewed for assert-nothing or flaky tests. I noted from names that `RealFlashTransferRepositoryTest` has a seeded
  `allDoneChunks` fake that never exercises large or out-of-order data, which is why R-01 was not caught; no test covers
  deletion of chunk rows on completion.

---

## Cross-cutting observations

1. Check once, trust twice. Several security checks exist on one path of a symmetric pair and not the other: inbound HELLO is
   bound to the TLS identity but outbound is not (R-02); the responder side of the group proof verifies and the initiator side
   does not (R-03); the sender refuses to remove an admin but the receiver rule allows a demotion (R-13). When adding or
   reviewing a rule, list both directions.
2. Swallowed `Throwable`. `catch (_: Throwable)` and `runCatching { ... }.getOrNull()/getOrDefault(...)` appear in several
   critical spots (R-06, R-09, R-20). They also swallow `CancellationException`. A short rule (`rethrow CancellationException,
   log with the id, surface a typed failure`) would remove most of them.
3. Unbounded state keyed by peer-controlled input: chunk vector size (R-07), proof responder map (R-12), group media
   timestamps (R-10), invite strings (R-16). The wire decoders themselves are well bounded; the weakness is what is built from
   the decoded values.
4. Unbounded persistence growth. `transfer_chunks` is the clear case (R-01); I did not check whether other per-event tables
   (messages by retention, outbox, group sync tables) have a cleanup path.
5. Twin files. The `core/network` and `core/security` JVM and Android copies are identical today, so each fix must be applied
   twice (R-02, R-11). A single shared source set would remove the risk (the repo already records why it has not been done).
6. Tests prove the happy path. The tests I looked at seed small, ordered data and assert the final state, which is why the
   performance cliff and the missing cleanup in R-01 and R-07 are invisible to them. A property test with out-of-order and
   large indexes, and a "completed transfer leaves no chunk rows" test would catch these.
7. Documentation to add if the owner agrees: R-03 and R-02 belong in `docs/security.md`, R-01 and R-04 would each need an
   `ERROR-NNN` entry once the owner decides to track them (I did not edit `logs/errors.md`, per my instructions).

---

## Coverage table

Depth: deep = read the code paths in full and traced callers; light = read selected files or functions, or grep only; not read.

| Module | Files and areas read | Depth |
|---|---|---|
| app | `AndroidManifest.xml`, intent/share handling in `MainActivity.kt`, backup rules, `file_paths.xml` | light |
| desktop | process-execution and helper files (5 files), transfer wiring in `DesktopEngine.kt` | light |
| third_party | none | not read |
| sample | none | not read |
| buildSrc | file listing, signing/secret grep only | light |
| core:common | framing, base64, logger, log sink redaction | deep |
| core:security | crypto, vaults, trust store, TOFU, pairing, group proof, invite codec, E2E codecs | deep |
| core:network | WS client/server/network (JVM and Android twins), codec, TLS factory | deep |
| core:persistence | schema steps, migrations, openers, JDBC cipher driver, chunk and message DAOs | deep |
| core:transfer | receive pipeline, bit vector, repository receive paths, sinks, path sanitiser | deep (send side not read) |
| core:engine | inbound router, Android `Flash.kt` wiring, swarm host binding, Room transfer store | light |
| core:messaging | group repository paths, proof sessions, signature rules, group gate, frame codecs | deep for groups; light for the rest |
| core:swarm | frame and manifest codecs | light |
| core:calling | frame codec, inbound coordinator entry | light |
| core:ptt | none | not read |
| core:discovery | TODO grep only | not read |
| ui:chat, ui:callui, ui:theme | none | not read |
| ui:platform-shims | one `getUriForFile` call site | light |
| Tests | a few repository and pipeline test fakes | not reviewed |
| 2026-10-09 code (sync, radio, screen share) | skimmed only where it crosses the files above | light (as instructed) |
