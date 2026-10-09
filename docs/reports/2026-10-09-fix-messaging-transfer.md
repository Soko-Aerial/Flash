# Fix report: messaging, transfer, persistence, security findings (2026-10-09)

Scope: the findings of `2026-10-09-review-overnight-code.md` (G*), `2026-10-09-sweep-modules.md` (R-*) and `2026-10-09-release-readiness.md`
that sit in `core/messaging`, `core/transfer`, `core/persistence`, `core/security`, `ui/chat` and the engine seams they need.
Rules kept: no commit, push, clean or delete; no shared log edited (paste-ready blocks are at the end); **no Room schema change** (stays at 13);
one wire addition (G2), optional and backward compatible, described below.

Status words: FIXED = a test fails without the change and passes with it (mutation-checked unless said otherwise). DEFERRED = not done, exact
steps given. NOT-A-BUG = evidence given. Nothing here is device-verified.

STATE OF THIS REPORT: final; every suite listed in section 7 was observed green after the last edit.

## 1. Findings table

| Id | Sev | Status | Files (main) | Test |
|---|---|---|---|---|
| G1 per-holder contact time | Medium | FIXED | `RealFlashChatRepository.kt`, `GroupLocalPreferences.kt` | `GroupHistorySyncTest.g1AHolderAskedAfterAnotherOneFinishedStillGetsTheWindowTheMemberChose` |
| G2 non-default ceiling blinds old builds | Medium | FIXED (ADR-105) | `GroupCanonical.kt`, `GroupSettings.kt`, `GroupSigning.kt`, `GroupSignatureRules.kt`, `GroupFrameCodec.kt`, `GroupPolicy.kt` | `GroupCanonicalTest`, `GroupSettingsTest` (v1 bytes unchanged, ceiling signature, splice refused, tie rule, unknown name) |
| G3 N holders push the same rows | Medium | FIXED (ADR-106); the G8 re-pull part is DEFERRED | `CatchUpLane.kt` (new), `RealFlashChatRepository.kt` | `CatchUpLaneTest`, `GroupHistorySyncTest.g3ANewMemberOfATwentyMemberGroupGetsEachRowFromOneHolderNotNineteen`, `g3AHolderWithoutASessionIsSkippedAndTheNextOneIsAsked`, `g3AHolderThatNeverAnswersIsReplacedAfterTheStallTime` |
| G4 marker holds the session's inbound pipeline 5 s | Low | FIXED in code (marker handled in its own coroutine, `RealFlashChatRepository.kt` ~3189); no dedicated test, covered only indirectly by the G12 tests | `RealFlashChatRepository.kt` | indirect |
| G5 `syncPushSeen` leaks / O(n^2) | Low | FIXED in code (`PushTally` lives in the request and is swept with it, one counter, no map copy per push); indirect tests only | `OutgoingSyncRequest.kt` | indirect (`g12*`) |
| G6 tombstoned rows count as usable in the scan | Low | FIXED | `RealFlashChatRepository.kt` | `g6TombstonedRowsDoNotMakeAPageShortOrTheChainLonger` |
| G7 legacy `State` handler always `newlyJoined = true` | Low | FIXED | `RealFlashChatRepository.kt` | `g7AReturningMemberWithNoRowsIsNotShownTheJoinCardAndAsksAtOnce` |
| G8 "Load older" lowers the window / re-pulls everything | Low | PARTLY FIXED: the stored window is never lowered (`g8LoadOlderNeverLowersTheStoredWindow`). The re-pull of rows already held is DEFERRED: it needs a new optional `FLASH_GSYNC` request key (`before=<sentAt:id>`) so the holder starts after what the requester has. Steps: add the key to `OutgoingSyncRequest` + codec, honour it in `handleSyncRequest`, test in `GroupHistorySyncTest`, document in `docs/protocol.md` | | `g8*` |
| G9 "Not now" is permanent | Low (UX) | FIXED: card now says how to change your mind (`SKIP_NOTE`) | `FlashGroupHistoryCard.kt` | `FlashGroupHistoryMathTest` |
| G10 admin check duplicated | Low | NOT-A-BUG today (the report itself says "No bug today"); a co-admin change must edit `updateGroupSettings` and `GroupAdminPolicy.isAdmin` together, noted in the code comment | | none |
| G11 no rate limit on `SyncRequest` | Low | FIXED: `VerifyBudget`, 30 requests per 10 s per (group, requester), excess dropped silently | `RealFlashChatRepository.kt` | `g11ARequesterThatHammersAHolderIsAnsweredOnlyUpToTheBudget` |
| G12 marker controls the requester's watermark | Low | FIXED: end clamped to the newest push actually received; a count-0 page whose end is > 5 min in the future is refused | `OutgoingSyncRequest.kt` (`PushTally`), `RealFlashChatRepository.kt` | `g12AMarkerCannotMoveTheWatermarkPastTheRowsItActuallySent`, `g12AFutureScanEndWithNoRowsIsNotBelievedAndNoContinuationFollows` |
| G13 equal-ms refresh trigger | Low | FIXED: `maxOf(now, previous + 1)` | `RealFlashChatRepository.kt` | `g13TwoRefreshesInTheSameMillisecondStillChangeTheTrigger` |
| R-01 chunk rows never deleted, quadratic preload | High | FIXED | `RealFlashTransferRepository.kt`, `TransferStore.kt`, `RoomTransferStore.kt`, `TransferChunkDao.kt` | `RealFlashTransferRepositoryTest` (completed receive leaves no rows and a late confirmation does not bring them back; declined receive; startup purge; 100k preload linear; 3000 one-by-one), `FlashDatabaseJvmTest` "R-01 purgeFinishedChunks ..." |
| R-02 outbound dial does not bind HELLO id | Medium | OUTSIDE (core/network), not touched | | |
| R-03 bare `GsResult(ok)` accepted | Medium | FIXED | `GroupProofSessions.kt` | `GroupProofSessionsTest.r03_*` (2) |
| R-04 length-equal file counted complete | Medium | FIXED: the whole-file hash must match, length alone is not enough | `FlashInboundRouter.kt` | `FlashInboundRouterTest.r04*` (2) |
| R-05 share target trusts any URI | Medium | OUTSIDE (app), not touched | | |
| R-06 swallowed write failure | Medium | FIXED: `RejectReason.WRITE_FAILED`; the router fails the row, closes the sink, tells the sender (`xfer cancel`) and shows a worded failure | `ReceivePipeline.kt`, `FlashInboundRouter.kt`, `TransferFailureText.kt` | `ReceivePipelineTest` (write failure), `FlashInboundRouterTest.r06AFailedStorageWriteFailsTheRowClosesTheSinkAndCancelsTheSender` |
| R-07 unbounded pre-accept allocation, O(n) `isComplete` | Medium | FIXED: `MAX_TOTAL_CHUNKS = 16,777,216`, O(1) received counter in `ResumeBitVector` | `ReceivePipeline.kt`, `ResumeBitVector.kt` | `ResumeBitVectorTest`, `ReceivePipelineTest` (cap) |
| R-08 `updateGroupContext` re-attribution / PK collision | Medium | FIXED: the query is now scoped to the sender and to an existing row, a miss logs a warning | `MessageDao.kt`, `RealFlashChatRepository.kt` | `FlashDatabaseJvmTest` R-08 (2) |
| R-09 Keystore identity deleted on any probe exception | Medium | FIXED: only a definite verdict (missing key, digests read and lack NONE) regenerates; an inconclusive probe retries 3 times then fails loudly with the key untouched | `IdentityKeyCheck.kt` (new), `KeystoreFlashCrypto.kt` | `IdentityKeyCheckTest` (8) |
| R-10 unbounded `sentAt` | Low | FIXED: media `sentAt` goes through `storedSentAt`; swarm expiry is `swarmExpiryFor(sentAt, now)`, base capped at `now + 5 min`, saturating | `RealFlashChatRepository.kt`, `SwarmHostBinding.kt` | `SwarmExpiryTest` (5) |
| R-11 interleaved control frame discards a fragmented message | Low | OUTSIDE (core/network), not touched | | |
| R-12 proof session maps unbounded | Low | FIXED for the responder map (8 open hellos per peer, expired ones swept, the 9th is refused and counts as a rate-limit failure). The initiator/in-flight maps are already bounded 1 per (peer, group) | `GroupProofSessions.kt` | `GroupProofSessionsTest.r12_*` |
| R-13 admin can demote another admin / relabel owner | Low | FIXED: an admin-issued cert cannot target the owner or another admin (`issuer-privilege`); self-issued admin certs still allowed | `GroupSignatureRules.kt` | `GroupSignatureRulesTest.r13*` (2) |
| R-14 repeated signature verification | Low | FIXED: `membersMayAddNow()` memoised per admin-key version | `SignedGroups.kt` | `SignedGroupsTest` "R-14 ... verified once per bundle" |
| R-15 FileProvider exposes the private tree | Low | OUTSIDE (app), not touched | | |
| R-16 invalid UTF-8 accepted by `GroupInviteCodec` | Low | FIXED: strict decoding of group id, name, inviter, hints | `GroupInviteCodec.kt` | `GroupInviteTest.r16_*` (5) |
| R-17 first-run persist failure on desktop | Low | FIXED: continues with an in-memory identity, deletes the `.tmp`, logs "IDENTITY NOT PERSISTED" loudly | `PersistedFlashCrypto.kt` | `PersistedFlashCryptoTest` "R-17 ..." |
| R-18 memory-trim listener per database open | Low | FIXED: `DatabaseTrimRegistration` unregisters the previous listener | `FlashDatabaseOpener.kt` | `DatabaseTrimRegistrationTest` (4) |
| R-19 static pairwise key, no direction/counter in AAD | Low | DOC NOTE ONLY (as ordered). The pairwise key is static, nonces are random 96-bit, and the AAD carries neither a direction nor a counter, so a ciphertext sent A to B could be replayed B to A if both sides derive the same key and the receiver has no replay window. Remedy for a future protocol revision (needs a wire version, so not done now): derive per-direction keys (HKDF info = "a2b"/"b2a") and bind a message counter into the AAD. Record as a known limitation in `docs/security.md` | | none |
| R-20 desktop helper details | Low | OUTSIDE (desktop), not touched | | |
| 12 `:core:persistence:testAndroidHostTest` DataStore failures | harness | FIXED, root cause found (below) | `HostSdkIntRule.kt` (new), `FlashSettingsDataStoreTest.kt`, `DiscoveryModeSettingTest.kt` | the 12 tests themselves |

### DataStore failures: root cause

The tests ran on the Gradle test JVM, which is **JDK 25** here. DataStore's `File.atomicMoveTo` uses `Files.move(REPLACE_EXISTING)` only when
`Build.VERSION.SDK_INT >= 26`, otherwise `File.renameTo`. In a host test the stub `android.jar` reports `SDK_INT == 0`, so `renameTo` runs, and
**on JDK 25 / Windows `File.renameTo` no longer replaces an existing file** (15-line Java program: JDK 21 returns true, true, true; JDK 25
returns true, false, false). The first write worked and every later one threw "Unable to rename preferences_pb.tmp". Not a product bug: on a
device `SDK_INT` is real and `Files.move` is used. Fix: `HostSdkIntRule` (JUnit `ExternalResource`) sets `SDK_INT` to 34 for these two classes
and restores it. The tests are unchanged apart from the rule line. If a later Gradle toolchain change moves the test JVM back to 21, the rule is
harmless.

## 2. G2 wire design (ADR-105)

Problem: the first ADR-100 draft replaced `setSig` with a `flash-gset-v2` signature whenever the history ceiling was not D30. A build that
predates ADR-100 could not verify it and dropped the whole object (join policy, sharers, member cap, swarm switch).

Design:
- `sig` (`setSig`) **always** covers the v1 statement `flash-gset-v1`, which never includes the ceiling. Every build verifies it.
- A non-default ceiling adds two optional keys `setHist=<NONE|H24|D7|D30|ALL>` and `setHistSig=<b64>`; `setHistSig` is the same admin's signature
  over statement `flash-gsethc-v1` = groupId, version, opId, signerId, ceiling name (length-prefixed). Default (D30) settings are
  byte-identical to before.
- A receiver that knows the ceiling requires a valid `setHistSig` by the signer of `setSig` for any non-default ceiling, else refuses the object.
- An unknown ceiling name decodes as the default (D30) without discarding the other settings.
- Storage without a schema change: the existing `historyCeiling` column holds `D7~<b64sig>` for a non-default ceiling.
- Tie rule in `settingsWins`: for equal version and opId, the copy that carries a non-default ceiling beats a copy that lost it (an old build
  re-encodes without the ceiling), so the ceiling is restored.

Compatibility (old = before ADR-100 or before ADR-105; new = this build):

| Sender | Receiver | Result |
|---|---|---|
| new, default ceiling | old | identical bytes; applies |
| new, non-default ceiling | old | `setSig` verifies, other settings apply, `setHist`/`setHistSig` ignored; old build keeps its own window |
| old | new | no `setHist`: ceiling reads D30; applies |
| new, non-default | new | applies; ceiling enforced and relayed |
| new relays via old | new | old build re-encodes without ceiling; the complete copy of the same (version, opId) wins at the receiver |
| old admin edits after a new admin set a ceiling | all | edit is version + 1 without a ceiling: group returns to D30 until a new-build admin sets it again (**documented limitation**) |
| member splices a genuine ceiling signature onto another object | new | refused: statement binds version, opId, signer |

## 3. G3 design (ADR-106)

`CatchUpLane` serialises catch-up per group: one holder at a time. Holders are ordered by their watermark `updatedAtMs` ascending (heard from
least recently first). A holder that sends nothing for `CATCH_UP_STALL_MS` = 45 s is dropped and the next is asked; one episode lasts at most
`CATCH_UP_EPISODE_MS` = 3 min. No wire change. The 45 s / 3 min values are estimates: **measure on devices (HARD-03)**.

## 4. Mutation checks

Each mutation applied, the named test observed failing, the code restored byte for byte. See the results table below; "not run" means the
mutation was not performed in this session because of the usage cut-off, and then the finding is FIXED only on the strength of the test being
written against the failing behaviour first.

| Mutation (applied, then restored byte for byte) | Finding | Failing test observed |
|---|---|---|
| `onResult` no longer requires a VERIFIED initiator | R-03 | `GroupProofSessionsTest.r03_an_ok_result_before_the_challenge_was_verified_...` |
| strict UTF-8 switched off in `GroupInviteCodec` | R-16 | 4 `GroupInviteTest.r16_*` on jvm and 4 on the Android host |
| inconclusive probe returns REGENERATE instead of throwing | R-09 | 2 `IdentityKeyCheckTest` R-09 tests |
| `forgetChunks` removed from `onIncomingCompleted` | R-01 | `RealFlashTransferRepositoryTest` "R-01 a completed receive leaves no chunk rows ..." |
| `alreadyCompleted` back to length only | R-04 | `FlashInboundRouterTest.r04AFileOfTheRightLengthButOtherContentIsNotAnAlreadyCompletedTransfer` (jvm and host) |
| marker end not clamped to the newest push | G12 | `GroupHistorySyncTest.g12AMarkerCannotMoveTheWatermarkPastTheRowsItActuallySent` |
| request budget switched off | G11 | `GroupHistorySyncTest.g11ARequesterHammersAHolder...` |
| lane bypassed, every holder asked at once | G3 | `GroupHistorySyncTest.g3ANewMemberOfATwentyMember...` |
| `swarmExpiryFor` back to `sentAt + TTL` | R-10 | 2 `SwarmExpiryTest` tests (jvm and host) |
| `AND senderId = :senderId` removed from `updateGroupContext` | R-08 | `FlashDatabaseJvmTest` "R-08 updateGroupContext moves the senders own provisional row and nobody elses" |
| per-peer hello cap raised to 100000 | R-12 | FIRST NOT caught: the test derived its count from the constant. Test changed to the literal 8 plus `assertEquals(8, MAX_OPEN_HELLOS_PER_PEER)`; the mutation then fails `r12_open_hellos_per_peer_are_bounded_and_expired_ones_are_swept` |

Also found by running the suite before the mutations: `swarmExpiryFor` overflowed when `now` itself was near `Long.MAX_VALUE` (test
`theSumSaturatesEvenWhenNowItselfIsHuge`); fixed by saturating `now + skew`.

**Not mutation-checked (usage cut-off):** G1, G2, G6, G7, G9, G13, R-06, R-07, R-13 (the relabel-the-owner half; the demote-an-admin half has a
failing-first test but the mutation script did not match the file and was skipped), R-14, R-17, R-18. Their tests were written against the
failing behaviour but a vacuous-test risk remains; do the mutations before trusting them (the R-12 case shows it happens).

## 5. Outside my area (listed, not fixed)

- R-02 and R-11 (core/network), R-05 and R-15 (app), R-20 (desktop).
- S1..S10 (calling, screen share).
- `app/.../DiscoveryEngineHolder.kt` (~2418): the duplicate `Rejected` handler only logs; add a `WRITE_FAILED` case (show the worded failure) or
  delete the duplicate. The shared router already handles WRITE_FAILED before the host hook.
- Host `onRejected` hooks (`core/engine/.../Flash.kt` ~953, `desktop/.../DesktopEngine.kt` ~1941) only log; same note.
- Library agent deferrals taken over here: R-10, R-12, R-13, R-14, R-16, R-17, R-18, R-19 (all in the table above).

## 6. Remaining risk

- R-09: an unrecoverable (not just unverifiable) Keystore key now fails loudly instead of silently resetting. A device whose key is truly broken
  cannot start until the user clears app data. This is deliberate (a silent reset loses every pairing) but needs a UI path (HARD-04).
- R-01: legacy receive chunk rows that have no `transfers` row (receives from before this change) cannot be told from a live partial and are
  kept; they load linearly. They disappear only when the transfer is next completed or cancelled.
- R-04: the hash of a length-matching file is computed inline on the receive thread (rare re-offer path; large file = a pause).
- G2: an old-build admin edit resets the ceiling to D30.
- G3: 45 s stall / 3 min episode are guesses. A slow but alive holder may be dropped.
- G8 re-pull (deferred) still re-requests rows the member already holds when "Load older" is used.
- R-12: the per-peer cap records a rate-limit failure, so a peer that legitimately opens 9 proofs in a minute is throttled.
- Nothing was device-verified.

## 7. Verification

Final run (one Gradle invocation, `--continue`, JDK 21 JBR for Gradle): `:core:messaging:jvmTest :core:messaging:testAndroidHostTest
:core:transfer:jvmTest :core:transfer:testAndroidHostTest :core:persistence:jvmTest :core:persistence:testAndroidHostTest :core:security:jvmTest
:core:security:testAndroidHostTest :core:swarm:jvmTest :ui:chat:jvmTest :core:engine:testAndroidHostTest :core:engine:jvmTest
:desktop:compileKotlinJvm :app:compileDebugKotlin` -> **BUILD SUCCESSFUL**. Counts from the result XMLs (tests / failures / skipped):

| Suite | Tests | Fail | Skip |
|---|---|---|---|
| core:messaging jvmTest | 240 | 0 | 0 |
| core:messaging testAndroidHostTest | 552 | 0 | 0 |
| core:transfer jvmTest | 135 | 0 | 0 |
| core:transfer testAndroidHostTest | 205 | 0 | 0 |
| core:persistence jvmTest | 55 | 0 | 0 |
| core:persistence testAndroidHostTest | 52 | 0 | 0 (was 12 failing) |
| core:security jvmTest | 110 | 0 | 1 (pre-existing) |
| core:security testAndroidHostTest | 202 | 0 | 0 |
| core:swarm jvmTest | 139 | 0 | 0 |
| ui:chat jvmTest | 398 | 0 | 0 |
| core:engine jvmTest | 148 | 0 | 0 |
| core:engine testAndroidHostTest | 123 | 0 | 0 |

`:desktop:compileKotlinJvm` and `:app:compileDebugKotlin` compiled with the other agents' edits in the tree at that moment. The tree is
consistent: no half-applied schema (Room stays 13) or wire change; G2 and R-01 are complete. Unrelated: the log line "Could not connect to
Kotlin compile daemon" is harmless; transient `classes.jar` file locks from the concurrent agents were retried.
One run (before the last fixes) also showed `FlashDatabaseInvariantTest.groupDeliveryRowsOfOneMessageAreObservableAndScopedToIt` timing out
once under load ("test body did not run to completion after 1m"); it passed in the final run. Treat as a load-sensitive flake, not investigated.
Test-fixture change worth knowing: `GroupHistorySyncTest.setCeiling` stores a non-default ceiling as `D7~c2ln` because, by ADR-105, an
unsigned stored non-default ceiling reads as D30.

## 8. Paste-ready blocks

### logs/progress.md

```markdown
## 2026-10-09 - Fixes for the overnight review and module sweep (messaging, transfer, persistence, security)

### Worked on
G1..G13 (group history sync), R-01, R-03, R-04, R-06..R-10, R-12..R-14, R-16..R-18, the DataStore host-test failures. Report:
`docs/reports/2026-10-09-fix-messaging-transfer.md`.

### Changed
- ADR-105: history ceiling signed separately (`setHist` + `setHistSig`), `setSig` stays v1; ADR-106: one-holder-at-a-time catch-up lane.
- Receive: write failures fail the transfer (WRITE_FAILED), chunk cap and O(1) counter, whole-file hash before "already completed".
- Transfer chunk rows deleted on terminal states; receives now get a `transfers` row; startup purge (no schema change).
- Identity key no longer deleted on a failed probe; desktop first-run persist failure continues in memory.
- Host-test harness: `HostSdkIntRule` (JDK 25 on Windows: `File.renameTo` no longer replaces files, DataStore falls back to it when SDK_INT = 0).

### Verification
See the report section 7. Not device-verified.

### Remaining
G8 re-pull (needs a new optional request key), S1..S10 and R-02/05/11/15/20 belong to other owners, `app` and host `Rejected` handlers need a
WRITE_FAILED case.

### Next AI
Run the device tests GSY-13.. and HARD-01.. in `docs/testing/TEST-BACKLOG.md`; do not start the G8 re-pull before ADR-105/106 are accepted.
```

### logs/handoff.md (replace the relevant lines)

```markdown
## Messaging / transfer sweep fixes (2026-10-09, uncommitted, unit-tested only)
- G1..G13 fixed except the G8 re-pull; ADR-105 (ceiling signature) and ADR-106 (catch-up lane) are PROPOSED.
- R-01 chunk-row cleanup, R-03, R-04, R-06, R-07, R-08, R-09, R-10, R-12, R-13, R-14, R-16, R-17, R-18 fixed in code with tests.
- No schema change (Room 13). One optional wire key pair (`setHist`/`setHistSig`) for G2.
- Next: hosts must show WRITE_FAILED (`DiscoveryEngineHolder` ~2418, `Flash.kt` ~953, `DesktopEngine.kt` ~1941).
- Files: `docs/reports/2026-10-09-fix-messaging-transfer.md`, `RealFlashChatRepository.kt`, `CatchUpLane.kt`, `FlashInboundRouter.kt`,
  `RealFlashTransferRepository.kt`, `IdentityKeyCheck.kt`.
```

### logs/errors.md

```markdown
## ERROR-148 - Non-default history ceiling made old builds drop all group settings
Date 2026-10-09. Area group settings / ADR-100. Root cause: `setSig` switched to `flash-gset-v2` for a non-D30 ceiling. Fix: ADR-105 (separate
`setHistSig`). Verified by `GroupCanonicalTest`/`GroupSettingsTest`. Status: OPEN until device-verified (GSY-13).

## ERROR-149 - Catch-up asked every holder for the same rows and used a group-wide contact time
Area group history sync (G1, G3). Fix: per-holder contact time; `CatchUpLane` (ADR-106). Status: OPEN until GSY-14, GSY-15.

## ERROR-150 - Group sync marker could move the watermark past rows never sent; no request rate limit
Area group history sync (G11, G12). Fix: clamp to the newest push received; 30 requests / 10 s per (group, requester). Status: OPEN until GSY-16.

## ERROR-151 - transfer_chunks rows never deleted; quadratic preload
Area transfer / persistence (R-01). Fix: delete on terminal states, `transfers` row for receives, `purgeFinishedChunks` at start, linear preload.
Status: OPEN until HARD-01.

## ERROR-152 - A failed storage write was swallowed, the transfer looked live
Area receive pipeline (R-06). Fix: `RejectReason.WRITE_FAILED` handled in `FlashInboundRouter` (fails row, closes sink, cancels sender). Status: OPEN
until HARD-02. Hosts still only log `Rejected`.

## ERROR-153 - A file of the right length but other content was reported already completed
Area inbound router (R-04). Fix: whole-file hash must match. Status: OPEN until HARD-02.

## ERROR-154 - Unbounded pre-accept allocation and O(n) isComplete
Area receive pipeline (R-07). Fix: `MAX_TOTAL_CHUNKS`, O(1) counter. Status: OPEN until HARD-02.

## ERROR-155 - Keystore identity key deleted on any probe exception
Area security (R-09). Fix: `IdentityKeyCheck`; only a definite verdict regenerates. Status: OPEN until HARD-04 (needs a device).

## ERROR-156 - Group proof / certificate hardening (R-03, R-08, R-10, R-12, R-13, R-14, R-16)
Fixes as in the report table. Status: OPEN until HARD-05.

## ERROR-157 - Host test failures: "Unable to rename preferences_pb.tmp" (12 tests)
Root cause: Gradle test JVM is JDK 25; on Windows `File.renameTo` no longer replaces an existing file; DataStore falls back to it when
`SDK_INT < 26` (stub android.jar: 0). Fix: `HostSdkIntRule`. Failed attempt: suspected a concurrent reader or antivirus lock (a JDK 21 program and a
`Files.move` program both succeeded). Status: RESOLVED (suite observed green 2026-10-09, report section 7).
```

### docs/decisions.md

```markdown
## ADR-105 - History ceiling signed separately; setSig stays v1 (PROPOSED)
Decision: `setSig` always covers `flash-gset-v1`. A non-default ceiling adds `setHist` and `setHistSig` (statement `flash-gsethc-v1` over group,
version, opId, signer, ceiling). Stored as `D7~<sig>` in the existing column. Tie: the copy with a non-default ceiling beats a stripped copy.
Alternatives: a `flash-gset-v2` signature (rejected: blinds every older build, never released); an unsigned ceiling (rejected: any member could lower it).
Revisit when: a settings v2 with a version negotiation exists. Limitation: an old-build admin edit resets the ceiling to D30.

## ADR-106 - One holder at a time for group catch-up (PROPOSED)
Decision: `CatchUpLane` per group; holders ordered by watermark `updatedAtMs` ascending; stall 45 s; episode 3 min; per-holder contact time.
Alternatives: ask all holders (rejected: N-fold duplicate pushes); split the window across holders (rejected: needs a wire key, G8 follow-up).
Revisit when: device numbers (HARD-03) show the stall or episode values are wrong.
```

### docs/testing/TEST-BACKLOG.md

```markdown
### GSY-13...GSY-16, HARD-01...HARD-05 - Sweep fixes (2026-10-09, unit-tested, none device-verified)

- **GSY-13 (ceiling and old build):** admin on this build sets ceiling "7 days"; a phone on a build from before ADR-100 receives it. Pass: that phone
  still shows the new join policy / member cap and keeps its own history window; no "settings refused" log. Source: ERROR-148, ADR-105. Status: TODO
- **GSY-14 (one holder):** new member joins a group of 4+ online members. Pass: log shows one holder asked at a time, each message arrives once.
  Source: ERROR-149, ADR-106. Status: TODO
- **GSY-15 (dead holder):** same, but the first-asked holder is switched to airplane mode mid-catch-up. Pass: the next holder is asked after about
  45 s and the history completes within 3 min. Source: ADR-106. Status: TODO
- **GSY-16 (hammering requester):** repeat the catch-up button quickly 40 times. Pass: no crash, holder log shows dropped requests after 30 in 10 s.
  Source: ERROR-150. Status: TODO
- **HARD-01 (chunk rows):** send and receive a 1 GB file, restart the app. Pass: `transfer_chunks` has no rows for the finished transfer and the
  start-up log shows the purge line. Source: ERROR-151. Status: TODO
- **HARD-02 (write failure):** receive a file into a full or removed storage location. Pass: the row is Failed with the "could not be saved" text,
  the sender stops. Source: ERROR-152..154. Status: TODO
- **HARD-03 (measure):** stall 45 s / episode 3 min on a slow holder. Source: ADR-106. Status: TODO
- **HARD-04 (identity key):** reboot a device and open the app immediately several times. Pass: pairings survive, no regenerate log. Source: ERROR-155.
  Status: TODO
- **HARD-05 (group hardening):** admin removes/demotes via crafted cert is not reproducible on device; check that join via invite, member add by a
  member and proof still work. Source: ERROR-156. Status: TODO
```
