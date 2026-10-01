# Chat and app edge-case audit — 2026-10-01

**Why this exists.** The owner asked what happens to a group when its creator is gone (deleted the app), and then for a wide edge-case
audit of the chat app: generate every edge case a chat app meets, check which ones Flash is ready for. This file is that list, with a
verdict per case, the evidence for the verdicts that were checked, and the cases that still need a test.

**What was and was not done.** Everything marked ✅ ⚠️ ❌ was read in the code in this session (file and symbol named). Nothing was run
on a device and no new unit test was written, so a ❌ is "the code shows the gap", not "reproduced". Cases marked ❔ were *generated and
not checked*: they are the to-do list, not findings. Nothing in the product code was changed by this audit.

| Mark | Meaning |
|---|---|
| ✅ | Handled: the code (or an existing test / device result) shows it. |
| ⚠️ | Partly handled or a documented, accepted limit. The limit is stated. |
| ❌ | Gap: the code shows the case is not handled. Each ❌ has an `ERROR-` entry or is a feature gap named as such. |
| ❔ | Not checked. Needs a unit test, a device test or a code read. |

Result log, tests and errors created from this audit: `logs/errors.md` ERROR-089…094, `docs/testing/TEST-BACKLOG.md` section 4k
(`EDGE-01`…`EDGE-18`), `logs/progress.md` and `logs/handoff.md` (2026-10-01).

---

## 1. The owner question: a v2 group whose creator is gone

### 1.1 What happens today (verified in code)

A v2 group (`g2-…`, up to 20 members, ADR-044) is rooted in its creator: the group id is a hash of the owner's key and a nonce, every
member certificate is signed by the owner, and only the owner may add, remove or rename (`SignedGroups.addMembers`,
`removeMember`; `RealFlashChatRepository.addV2MembersLocked`, `removeGroupMemberLocked` refuse a non-owner). The design documents
already list this as an accepted limit ("the owner is a single point of trust and of failure … ownership transfer is not built",
`docs/group/v0-threat-review.md` §7, `docs/security.md` §8–9, ADR-044). This audit adds what that limit does in practice.

**Owner loss is not rare here.** A device identity is a random id in SharedPreferences plus a Keystore key, and both are excluded from
backup and device transfer on purpose (`data_extraction_rules.xml`, audit S4; `AndroidPreferencesIdentityStore`). So a **new phone, a
reinstall, "clear data" or a factory reset gives the owner a new, unrelated identity**. The old owner is gone for good, exactly as if
the app had been deleted.

| Scenario | Chat among the other members | Add / remove / rename | The owner's row | Sender's message status | Calls |
|---|---|---|---|---|---|
| **Owner deleted the app, never left** (also: new phone, reinstall, clear data, lost phone) | Keeps working. Messages are signed by each member's own key and every member holds the roster; no handler I read needs the owner online (`Message`, `Receipt`, sync, `onBundle` for a known group only needs an active member sender). Not run with the owner absent: `EDGE-01`. | **Frozen for good.** Nobody can add, remove or rename. The owner cannot come back as a member: a reinstalled device is a stranger and only the owner can add. | Stays **active** and offline forever, and counts toward the 20 cap. | **Every message ends FAILED 30 minutes after it was sent**, although all other members have it, and the delivery tick never completes (ERROR-090 below). | Work; the owner's tile reads "Not reachable yet". |
| **Owner left on purpose** | Keeps working. | Frozen (the owner row is a tombstone, so nobody can issue certs). | Tombstone, so delivery is not blocked. | Fine. | Fine. |
| **Owner removed** | n/a | `removeMember` refuses the owner, so this cannot happen. | n/a | n/a | n/a |
| **Some other member is dead, owner alive** | Keeps working. | Owner can remove them. | n/a | Same FAILED effect until the owner removes that member. | Fine. |
| **A vouched member (paired only with the owner) gets a new identity, owner gone** | Cut off from every member it is not paired with: a vouch exists only as an owner-signed cert. Members paired with it can pair again. | Cannot be re-certified. | n/a | n/a | n/a |
| **Legacy group (≤ 6), owner gone** | Keeps working. | Any active member may add (forgeable, ERROR-082, accepted by the owner). There is no remove. | n/a | Same FAILED effect for a dead member. | Fine. |
| **The "Add members" button, any member, owner gone** | n/a | The button is shown to non-owners and **fails silently** (known gap in `AGENTS.md` §29; the check is in the repository, not the UI). | n/a | n/a | n/a |
| **The owner leaves** | n/a | `FlashLeaveGroupDialog` takes no role: the owner gets the same text as a member and **no warning that the group will become unchangeable**, and is not asked to choose a successor. | n/a | n/a | n/a |

### 1.2 Is there a fix? Not yet. The options, and one recommendation

Constraints from the current design: the group id binds the *first* owner's key; trust is one hop deep (a receiver trusts a vouched
key because it paired with the owner); an unpaired member's key can only enter through an owner-signed cert.

| Option | What it is | Fixes an already-orphaned group? | Fixes a sudden loss (app deleted)? | Cost and risk |
|---|---|---|---|---|
| **D. Continue in a new group** | A member (any) taps "Continue in a new group". The app creates a new v2 group owned by that member with the old active members (minus the dead owner) using the existing `createV2GroupLocked`. The old group stays as read-only history. | **Yes** | **Yes** (by starting over) | Small, **no wire change, no trust-model change**. Limits: the new owner must be paired with every invitee (`createGroupLocked` requires `isTrustedPeer`, and `inviteeKeys` needs a live session whose key matches the pin), so vouched-only members must pair first; the group id changes. |
| **A. Co-owners** | The owner may promote members to admin (an owner-signed `role=admin` cert). An admin may add, remove and rename. On owner Leave the UI requires naming a successor. | **No** (only the owner can appoint) | Only if an admin was appointed beforehand | Medium. New role in the cert rules and canonical bytes, two-hop trust (owner → admin → member) which changes ADR-044 decision 3 and `docs/security.md` §9, a UI for roles, tests. Needs an ADR and the owner's decision. |
| **B. Voluntary hand-off only** | The owner signs a charter hand-over to a named member. | No | **No** (the owner has to be present) | Medium. Does not cover the common case. Covered by A's "name a successor on Leave". |
| **C. Succession by majority** | When the owner has been silent for N days, a majority of active members sign a `Succession` naming a deterministic successor; an epoch counter orders competing claims and a returning owner is superseded. | No | **Yes**, with no preparation | Large. A mutable owner key (the id binds the first one), an epoch chain, new frame, vote UI, convergence problems like removal's, and a coup surface. Needs an ADR and a threat review (V0-style). |

**Recommendation:** do **D first** (it is the only option that rescues groups that are already orphaned, it needs no protocol or trust
change, and it is also the answer to a lost phone), together with two cheap changes that make the owner's absence visible and harmless:
(1) the Leave dialog warns the owner and, until A exists, offers D first; (2) the "Add members" button is hidden for a non-owner of a v2
group. Then decide **A** (admins, with "pick a successor when you leave") as the long-term fix through an ADR, because it changes the trust
model. Treat **C** as a later option only if the owner wants a group to survive with no preparation at all. Whatever is chosen, fix
the dead-member side effects of section 2 (ERROR-090 part 2), because they hurt any group with one dead member, owner or not.

*(Decision needed from the owner before any code: D only, or D then A. Recorded in `logs/handoff.md`.)*

---

## 2. Findings that matter most (all code-read; none reproduced on a device)

| # | Finding | Severity | Where |
|---|---|---|---|
| **F1** | A v2 group cannot survive its owner: roster frozen, the owner can never be re-added, no warning anywhere. Section 1. | High, common | ERROR-090 |
| **F2** | A group with one permanently dead member (the owner or anyone): every message goes **FAILED after 30 min** though everyone else has it, the delivery tick never completes, and there is no retry. | High | ERROR-090 |
| **F3** | **A 1:1 message to someone offline for more than 30 minutes is lost for good.** `OUTBOX_GIVE_UP_AFTER_MS` = 30 min marks it FAILED and deletes the outbox row; the failed-status icon has no retry wired in the bubble (`FlashMessageBubble` calls `FlashDeliveryStatusIcon(status)` with no `onRetry`); the repository has no retry method; the direct protocol has no catch-up frame (`MessageWireFrame` has text, receipts, typing, delete, reaction only). | High | ERROR-089 |
| **F4** | No send-side size cap. A group text over 16,384 characters is silently dropped by every receiver (`GroupPolicy.MAX_MESSAGE_TEXT_LENGTH`, checked on receive only) and then FAILs after 30 min. A 1:1 text near 4 MiB makes the receiver's WebSocket read throw (`WebSocketCodec` "exceeds size guard"), which closes the session; the outbox resends it on every reconnect for 30 min. | Medium | ERROR-091 |
| **F5** | Message order, unread and the outbox's give-up trust the clock. `messages` is ordered by the **sender's** `sentAt`, inserted unclamped; a peer with a clock a day ahead sorts after your newer messages forever. The outbox's age is `now - createdAt` on the wall clock, so a clock set forward fails every queued message at once. | Medium | ERROR-092 |
| **F6** | Received file names: every non-ASCII character becomes `_` (Android keeps spaces, desktop turns them into `_` too), `take(120)` can cut the extension, and two names that sanitize to the same string inside one folder transfer share a destination. | Medium | ERROR-093 |
| **F7** | "Delete for everyone" and reactions are one best-effort send, not outboxed: an offline recipient keeps the message. A delete only sets `deletedAt` (the text stays in the database and in every reply preview that quoted it). | Medium (privacy) | ERROR-094 |
| **F8** | No free-space check before receiving, and no handling of a full disk or a full database was found (no `usableSpace`, `StatFs`, `SQLiteFullException`). Not run: `EDGE-11`, `EDGE-13`. | Unknown | `EDGE-11`, `EDGE-13` |
| **F9** | Search uses `LIKE '%' || :query || '%'` with no `ESCAPE`, so `%` and `_` in a query act as wildcards (`MessageDao`). | Low | `EDGE-14` |

---

## 3. The catalog

Row format: ID, the case, mark, evidence or why it is unchecked. IDs are stable; add rows, do not renumber.

### 3.1 Group ownership and roster (GO)

| ID | Case | | Evidence / note |
|---|---|---|---|
| GO-01 | Owner uninstalls, clears data, gets a new phone, loses it | ❌ | §1. Roster frozen; ERROR-090 |
| GO-02 | Owner reinstalls and wants back in | ❌ | New id and key; only the owner can add |
| GO-03 | Owner leaves on purpose | ⚠️ | Allowed; generic dialog, no warning, no successor |
| GO-04 | Owner removed by a member | ✅ | `removeMember` refuses `subjectId == owner`; only the owner removes |
| GO-05 | A member's device is replaced (new id) | ❌ | Old row stays active forever if the owner is gone |
| GO-06 | A member uninstalls without leaving | ⚠️ | Owner alive: owner removes. Owner gone: dead row forever (counts toward 20) |
| GO-07 | Vouched member gets a new key, owner gone | ❌ | A vouch is only an owner-signed cert; unpaired members cannot re-trust the new key |
| GO-08 | Legacy group, owner gone | ⚠️ | Any active member may Add (ERROR-082 accepted); no remove |
| GO-09 | All but one member leave | ❔ | `leave` has no minimum; what the lone member sees is unchecked |
| GO-10 | The last member leaves | ❔ | Rows stay as tombstones; unchecked |
| GO-11 | Re-adding a member who left or was removed | ✅ | `addMembers` issues `seq + 1` from the stored row |
| GO-12 | Adding a device on an old Flash version | ✅ | Refused: "Update Flash on that device before adding it" |
| GO-13 | Adding a member that is offline | ⚠️ | Refused (`V2_KEY_UNAVAILABLE`: needs a live session whose key matches the pin); wording to the user unchecked |
| GO-14 | Adding past 20 | ✅ | `MAX_MEMBERS_V2` checked on send and on merge |
| GO-15 | Member removed while offline | ✅ | ADR-044 removal ripple (ERROR-083): told on reconnect. Device check GT-03 owed |
| GO-16 | Removed member keeps what it already received | ⚠️ | Documented: no per-sender keys |
| GO-17 | An unconverged member still sends to a removed one | ⚠️ | Documented: eventual consistency |
| GO-18 | Deleting a group chat without leaving | ⚠️ | Message and conversation rows are hard-deleted, member rows and vouches stay (documented). What the next group message does is unchecked: `EDGE-15` |
| GO-19 | Group rename | ⚠️ | Not available in v2 (feature gap); a device rename does not change signed labels, which stay as signed (documented) |
| GO-20 | Two groups with the same name | ❔ | Ids differ; list disambiguation unchecked |
| GO-21 | Forged or pre-created group id | ✅ | v2 id = hash(owner key, nonce), `g2-` prefix reserved |
| GO-22 | Owner's clock wrong (v2) | ✅ | `seq` counters, not time (V0 rule 2). Legacy groups still use time (F-3) |
| GO-23 | "Continue in a new group" after the owner is gone | ❌ | Does not exist (option D) |
| GO-24 | Non-owner opens Add members in a v2 group | ❌ | Shown, fails silently (known gap) |
| GO-25 | Two members added concurrently | ✅ | One writer (the owner); `(seq, opId)` order |
| GO-26 | A bundle with a future `createdAt` | ✅ | Arrival time is used for sort order |
| GO-27 | Group of 20 with all members offline except one | ❔ | Dial budget tests exist (ADR-057); behaviour with 19 offline unchecked |

### 3.2 Group messaging and delivery (GM)

| ID | Case | | Evidence / note |
|---|---|---|---|
| GM-01 | One member permanently dead: status of every message | ❌ | `drainGroupMessage` marks FAILED after 30 min; DELIVERED needs every row (`recordGroupDelivery`). ERROR-090 |
| GM-02 | A FAILED group message: can the user retry | ❌ | No retry wired (F3) |
| GM-03 | A member offline for hours returns | ✅ | Catch-up sync on session-up (CGS tests, device owed); a FAILED mark can flip to DELIVERED when the receipt arrives (`updateStatusIfUnacknowledged` allows it) |
| GM-04 | Sending with no sessions at all | ⚠️ | Queued; gives up after 30 min |
| GM-05 | Text over 16,384 characters | ❌ | Receivers drop silently, sender does not check. ERROR-091 |
| GM-06 | Duplicate or replayed message | ✅ | Insert is idempotent on `localId` |
| GM-07 | Receipt or read from a non-member | ✅ | `isActiveGroupMember` |
| GM-08 | Frame whose `from` is not the connection's device | ✅ | `peerDeviceId != frame.from` rejects (group frame entry) |
| GM-09 | Message from a removed member to an unconverged device | ⚠️ | Documented limit |
| GM-10 | Reply to a message the receiver does not have | ❔ | The quote preview travels with the reply; the jump target is unchecked |
| GM-11 | Delete-for-everyone authority | ✅ | `message.senderId == frame.from` |
| GM-12 | Delete-for-everyone arrives before the message | ❌ | Handler returns when the message is absent; no tombstone-first, so a later sync can bring it back |
| GM-13 | Delete-for-everyone, a member offline | ❌ | One send, not durable. ERROR-094 |
| GM-14 | Reaction on a missing message, or from a removed member | ❔ | Sender side checked (`isRemovedHere`); receiver side unchecked |
| GM-15 | Typing indicator stuck after the typist disconnects | ❔ | Frame has a timestamp; expiry unchecked |
| GM-16 | Same millisecond from two members | ✅ | `ORDER BY sentAt, localId` |
| GM-17 | Member returns after months with thousands of messages | ⚠️ | Rounds and `hasMore` exist, banner UI-052; memory and time unmeasured |
| GM-18 | A lying member relays forged history | ✅ v2 / ⚠️ legacy | v2: author signature (V1). Legacy: ERROR-082, accepted |
| GM-19 | Group files to a vouched (unpaired) member | ⚠️ | Not delivered by design (FO-04); the sender's message about it is unchecked |
| GM-20 | Group read ticks with 20 members | ✅ | Chat/group sync audit (CGS), device owed |
| GM-21 | A member renames the device | ⚠️ | Signed labels stay (documented) |
| GM-22 | Group message to a device that left but did not tell | ⚠️ | Same as a dead member (GM-01) |
| GM-23 | Message while this device was removed | ✅ | Composer replaced by a notice; `sendGroupText` refuses |

### 3.3 Identity, pairing and trust (ID)

| ID | Case | | Evidence / note |
|---|---|---|---|
| ID-01 | New phone, reinstall or clear data | ⚠️ | New identity by design; old pairings stay as offline contacts on the peers; "Unpair this device" exists in the peer sheet. What unpair does to the 1:1 chat and the groups is unchecked |
| ID-02 | Same person on two devices | ⚠️ | Two identities; no linking (feature gap) |
| ID-03 | A different device presents the key of a paired id | ✅ | TOFU/pin mismatch is blocked (ADR-042, verified 2026-09-23); the user-facing notice is unchecked |
| ID-04 | Certificate expiry | ✅ | Android 25 years; desktop 25 years with a 1-day backdate; the trust manager does not check validity |
| ID-05 | Device clock wrong when the certificate was made | ✅ | Validity is not checked, so a wrong clock cannot fail a handshake |
| ID-06 | Backup, restore, device-to-device transfer | ✅ | Identity, pins, keys and database all excluded (`data_extraction_rules.xml`, `backup_rules.xml`) |
| ID-07 | Keystore key lost (lock-screen change, wipe) with the database present | ⚠️ | `EncryptedDatabaseRecovery` moves the unreadable database aside and starts empty: history is lost, no crash. Intended; not run |
| ID-08 | Two devices with the same friendly name | ⚠️ | Self-dial fixed (animal/fruit names); list disambiguation unchecked |
| ID-09 | Pairing interrupted: cancel, other side leaves, wrong code | ✅ | Commit-then-reveal (ADR-042); device-verified 2026-09-23 |
| ID-10 | Guessing the pairing code | ❔ | Attempt limits unchecked |
| ID-11 | Unpairing a member of a v2 group | ✅ | The vouch keeps them reachable inside the group (`DesktopIdentityStores` note) |
| ID-12 | Unpairing a member of a legacy group | ⚠️ | They are locked out of group frames (frames need trust) |
| ID-13 | A stranger connecting and holding a session slot | ⚠️ | Pre-handshake cap 64 KB, ceiling 24 sessions, "first come" (ADR-057) |
| ID-14 | Pairing while the other side is mid-call or transfer | ❔ | Unchecked |
| ID-15 | The device is renamed while connected | ✅ | DNAME, unit-tested; device checks owed |
| ID-16 | Device clone apps (Dual Messenger, Second Space) | ❔ | Two instances, one IP, two identities; mDNS name collisions unchecked |

### 3.4 One-to-one messaging (MSG)

| ID | Case | | Evidence / note |
|---|---|---|---|
| MSG-01 | Peer offline more than 30 minutes | ❌ | F3. ERROR-089 |
| MSG-02 | Peer offline less than 30 minutes | ✅ | `notifyPeerSessionUp` resets the outbox (Bug 5) |
| MSG-03 | Process killed with queued messages | ✅ | Outbox row and message row are one transaction (durable); wake-up depends on the handset (EXP-002) |
| MSG-04 | Empty or whitespace message | ✅ | `trim().isEmpty()` returns |
| MSG-05 | Text near or over 4 MiB | ❌ | No send cap; receiver's read throws and the session closes. ERROR-091 |
| MSG-06 | Retry sends the same message twice | ✅ | Idempotent on `localId`; the receiver re-acks |
| MSG-07 | Out-of-order arrival | ⚠️ | Ordered by `sentAt`, so only as good as the sender's clock (ORD) |
| MSG-08 | Two sessions to the same peer | ✅ | Glare resolved (ERROR-023) |
| MSG-09 | Message to an unpaired device | ✅ | Blocked by the trust check |
| MSG-10 | Reaction while the peer is offline | ❌ | Not outboxed (`toggleReaction`); counts diverge. ERROR-094 |
| MSG-11 | Delete-for-everyone while the peer is offline | ❌ | Not outboxed. ERROR-094 |
| MSG-12 | A deleted message's text | ⚠️ | Tombstone only; the text stays in the database and in reply previews |
| MSG-13 | Edit a sent message | ⚠️ | No edit feature (`markEdited` exists in the DAO; no repository method found) |
| MSG-14 | Draft survives process death | ✅ | Drafts are persisted (project notes) |
| MSG-15 | Read receipts after the chat was left | ✅ | ERROR-087 fix; device owed (UNREAD-01…05) |
| MSG-16 | Search with `%` or `_` | ❌ | No `ESCAPE` (F9) |
| MSG-17 | Search over thousands of messages | ❔ | `LIKE` scan, capped by `SEARCH_RESULT_LIMIT`; speed unmeasured |
| MSG-18 | Conversation row deleted locally, then a message arrives | ✅ | Row is refreshed or created in the insert transaction (ADR-062) |
| MSG-19 | Notification for a muted or open chat | ❔ | Unchecked |
| MSG-20 | Forwarding and copying | ❔ | Unchecked |
| MSG-21 | Message sent in the exact second the peer's session drops | ✅ | The row is kept until the peer acknowledges (ERROR-031) |

### 3.5 Time and ordering (ORD)

| ID | Case | | Evidence / note |
|---|---|---|---|
| ORD-01 | Peer clock far ahead | ❌ | Unclamped `sentAt` inserted; sorts last forever. ERROR-092 |
| ORD-02 | Peer clock far behind | ❌ | Sorts into old history; unread count and cursor use the same order |
| ORD-03 | This device's clock steps back | ❌ | New messages sort before older ones |
| ORD-04 | This device's clock steps forward 30+ minutes with queued messages | ❌ | `now - createdAt` fails them all at once |
| ORD-05 | Time zone change, daylight saving, travel | ❔ | Stored as epoch; display formatting unchecked |
| ORD-06 | Midnight while a chat is open (date separators) | ❔ | Unchecked |
| ORD-07 | 12/24 hour and locale formats | ❔ | Unchecked |
| ORD-08 | Retention / old-message deletion | ❔ | `RetentionPolicy` exists; what it deletes is unchecked |
| ORD-09 | Pairing and group `seq` do not depend on time | ✅ | v2 |

### 3.6 Text, names and unicode (TXT)

| ID | Case | | Evidence / note |
|---|---|---|---|
| TXT-01 | Emoji and combined emoji near a length cap | ❔ | `take(n)` counts UTF-16 units and can split a pair |
| TXT-02 | Right-to-left and bidirectional override characters in a name or message | ❔ | No sanitising found |
| TXT-03 | Blank or whitespace-only member label | ✅ | `label()` falls back to the id |
| TXT-04 | Very long display name | ✅ groups / ❔ 1:1 | Group labels capped (`MAX_LABEL_LENGTH`), discovery names capped at 64; 1:1 sender name unchecked |
| TXT-05 | Links and look-alike URLs | ❔ | Unchecked |
| TXT-06 | NUL and other control characters | ❔ | Unchecked |
| TXT-07 | Thousands of newlines or one 16,000-character word | ❔ | UI-043 stress numbers owed |
| TXT-08 | Emoji-only message size | ❔ | Unchecked |
| TXT-09 | Non-ASCII file name (Arabic, Chinese, accents) | ❌ | Stored name becomes underscores. ERROR-093 |
| TXT-10 | A long file name loses its extension | ❌ | `take(120)` after sanitising |
| TXT-11 | Windows reserved names (`CON`, `NUL`, `COM1`…), trailing dot or space, paths over 260 | ⚠️ | Containment check (`canonicalFile` startsWith) stops an escape; the failure the user sees is unchecked |
| TXT-12 | `A.txt` and `a.txt` in one folder transfer on Windows | ❔ | Same file on NTFS; second may overwrite the first |
| TXT-13 | `a b.txt` and `a_b.txt` in one folder transfer | ❌ | Both sanitize to `a_b.txt` on desktop (and `a b.txt` stays on Android) |
| TXT-14 | Path traversal in a file name | ✅ | Both sanitisers and the canonical-path check |

### 3.7 Attachments and transfers (ATT)

| ID | Case | | Evidence / note |
|---|---|---|---|
| ATT-01 | Receiver's storage full | ❔ | No free-space check found; failure path unchecked (`EDGE-11`) |
| ATT-02 | Source file changed or deleted before a resume | ❔ | AGENTS §18 requires an identity check; its implementation was not re-read |
| ATT-03 | Source permission (SAF URI) revoked | ⚠️ | `openSource` throws; the retry path unchecked |
| ATT-04 | Zero-byte file | ❔ | Chunk arithmetic with 0 bytes unchecked (`EDGE-12`) |
| ATT-05 | Files over 4 GB | ❔ | Chunk index type and persistence unchecked |
| ATT-06 | Receiver never answers the offer | ❔ | Accept-gate timeout unchecked |
| ATT-07 | Sender offline mid-transfer | ✅ | Resume on session-up (ERROR-035) |
| ATT-08 | Same file sent both ways at once | ❔ | Separate ids; unchecked |
| ATT-09 | Received file deleted from storage later | ❔ | Card tap behaviour unchecked |
| ATT-10 | Received files live in app-private external storage | ⚠️ | Deleted with the app; users may expect Downloads |
| ATT-11 | Corrupt or huge image or video | ❔ | Decode memory unchecked |
| ATT-12 | Voice message: no permission, interrupted by a call, zero length | ❔ | Unchecked |
| ATT-13 | Multi-file send with one failure | ❔ | Unchecked |
| ATT-14 | Chunk hash mismatch | ✅ | Each chunk's SHA-256 verified before it is written |
| ATT-15 | Delete the chat while a transfer runs | ❔ | `deleteConversations` removes messages and the conversation only |

### 3.8 Calls (CALL)

| ID | Case | | Evidence / note |
|---|---|---|---|
| CALL-01 | Both sides call each other at once (1:1) | ❔ | Setup is "caller offers only" (ADR-025); two crossing calls unchecked (`EDGE-16`) |
| CALL-02 | Call while already in a call | ❔ | ADR-060's busy set only protects participants from ECO parking; whether a second incoming call is refused or queued is unchecked |
| CALL-03 | Cellular call or alarm during a Flash call | ❔ | Only `FlashCallAudioRouter` and `FlashCallRinger` touch audio focus; no telephony listener found (`EDGE-17`) |
| CALL-04 | Bluetooth headset connects or drops | ⚠️ | Audio router exists; device check owed |
| CALL-05 | Microphone or camera permission denied or revoked | ❔ | Unchecked |
| CALL-06 | Network switch mid-call | ⚠️ | ERROR-085; section 4h tests owed |
| CALL-07 | One leg of a group call drops | ✅ | Pruning (ADR-060) |
| CALL-08 | A call nobody answers | ✅ | Ring timeout (ADR-060) |
| CALL-09 | Group call, owner dead | ✅ | Membership comes from the roster (ERROR-088): the dead owner is just "Not reachable yet" |
| CALL-10 | Call from a removed member | ✅ | The call gate needs an active roster row (ERROR-083) |
| CALL-11 | Group call log rows | ❌ | Not built (documented) |
| CALL-12 | Rotation, picture-in-picture, screen lock mid-call | ❔ | Unchecked |
| CALL-13 | Incoming call on a locked screen | ⚠️ | `USE_FULL_SCREEN_INTENT` is declared; Android 14+ needs a user grant for it, so whether the screen lights up is unchecked |
| CALL-14 | Ringing a device whose app was killed | ⚠️ | Inherent: no push service, the app must be alive |
| CALL-15 | A call that cannot be closed | ✅ in code | ERROR-086 fixed in code; GCALL-01 owed |
| CALL-16 | More than 8 video or 12 voice participants | ✅ | ADR-050 |

### 3.9 Network (NET)

| ID | Case | | Evidence / note |
|---|---|---|---|
| NET-01 | Wi-Fi off and on, airplane mode, router restart | ⚠️ | Session recovery (several ADRs); device tests owed |
| NET-02 | Client isolation or guest Wi-Fi | ⚠️ | Inherent; the empty "Nearby" wording is unchecked |
| NET-03 | Different subnets or VLANs | ⚠️ | mDNS does not cross; manual connect exists |
| NET-04 | Devices on different networks (internet) | ⚠️ | Not supported: LAN only, by design |
| NET-05 | VPN active on one device | ❔ | Unchecked |
| NET-06 | IPv6-only or link-local | ❔ | Unchecked |
| NET-07 | Cellular and Wi-Fi both up | ❔ | Unchecked |
| NET-08 | Hotspot host and guests | ✅ | NSD hardening (memory note) |
| NET-09 | Port in use, firewall blocks inbound | ❔ | Unchecked (`EDGE-18`) |
| NET-10 | Captive portal | ❔ | Unchecked |
| NET-11 | Flapping connection | ✅ | ERROR-023, ERROR-085 |
| NET-12 | More than 24 peers | ✅ | Ceiling 24, dial budget (ADR-057) |
| NET-13 | Oversize frame from a peer | ✅ | 64 KB before the handshake, 4 MiB after; closes the session |
| NET-14 | Multicast lock released by battery saver | ⚠️ | Handset-dependent (EXP-002) |

### 3.10 Platform and lifecycle (LIFE)

| ID | Case | | Evidence / note |
|---|---|---|---|
| LIFE-01 | Process death while sending | ✅ | Durable outbox |
| LIFE-02 | Doze, standby, OEM killers | ⚠️ | EXP-002: Samsung 90 %, Infinix 4 % |
| LIFE-03 | Notification permission denied (Android 13+) | ⚠️ | Requested (`MainActivity`); behaviour when denied is unchecked |
| LIFE-04 | Storage nearly full during a database write | ❔ | No handler found (`EDGE-13`) |
| LIFE-05 | Update with a schema change | ✅ | Migrations; destructive fallback exists only for tests |
| LIFE-06 | Downgrade to an older build | ❔ | Unchecked |
| LIFE-07 | Android 14+ foreground-service type rules | ✅ | Memory note on the call FGS type |
| LIFE-08 | Battery saver or data saver | ❔ | Unchecked |
| LIFE-09 | Right-to-left layout, font scale 200 %, display size | ❔ | Unchecked |
| LIFE-10 | Desktop sleep, hibernate, resume | ❔ | Session-up edges exist (CGS); not run (`EDGE-18`) |
| LIFE-11 | Second desktop instance | ✅ | `SingleInstanceController` |
| LIFE-12 | Windows with VPN, Hyper-V and WSL adapters | ✅ | DR5 adapter filter |
| LIFE-13 | Desktop settings file corrupt or read-only | ❔ | Unchecked |
| LIFE-14 | First launch offline | ❔ | Unchecked |
| LIFE-15 | Multi-user, work profile, secondary space | ❔ | Unchecked |

### 3.11 Security and abuse (SEC)

| ID | Case | | Evidence / note |
|---|---|---|---|
| SEC-01 | Malformed frames | ❔ | Size caps exist; parse-error handling per codec unchecked |
| SEC-02 | Flood of group bundles (signature CPU) | ✅ | `VerifyBudget`, stale replays free |
| SEC-03 | Replay of an old bundle | ✅ | Stale `(seq, opId)` skipped |
| SEC-04 | Spoofed `from` | ✅ | Group frames: `peerDeviceId != frame.from` rejects |
| SEC-05 | The LAN sees the name and model | ⚠️ | By design; Ghost mode (ADR-046) |
| SEC-06 | Message text in logs | ❔ | Unchecked against AGENTS §24 |
| SEC-07 | Screenshots and the recents preview | ❔ | No secure-flag option found |
| SEC-08 | Lock-screen notification content | ❔ | Unchecked |
| SEC-09 | Stolen unlocked phone | ⚠️ | No app lock (feature gap) |
| SEC-10 | An established call leg after a removal | ⚠️ | Documented: not re-checked |
| SEC-11 | A lying owner vouches a key it controls | ⚠️ | Documented; mitigated by the "not verified" label and Verify |

### 3.12 UI states (UI)

| ID | Case | | Evidence / note |
|---|---|---|---|
| UI-01 | System back from a chat | ✅ | ERROR-087 fix |
| UI-02 | Rotation and fold | ❔ | AD-6 has no fold posture |
| UI-03 | A 20-member list | ❔ | Unchecked |
| UI-04 | A new message while selecting messages | ❔ | Unchecked |
| UI-05 | A chat list with thousands of rows | ❔ | UI-042 numbers owed |
| UI-06 | Empty states and permission-denied states | ❔ | Unchecked |
| UI-07 | Accessibility (TalkBack, contrast, hit targets) | ❔ | Unchecked |
| UI-08 | A failed message offers a way to retry | ❌ | Not wired (F3) |
| UI-09 | The owner sees why the group cannot change | ❌ | No message anywhere |

---

## 4. Counts

190 cases in section 3: **26 ❌ gaps**, **41 ⚠️ partial or accepted**, **58 ✅ handled**, **63 ❔ not checked**, and 2 rows that are two
verdicts at once (GM-18, TXT-04). By area (❌ / ⚠️ / ✅ / ❔): GO 6/8/9/4, GM 5/6/8/3 (+1 mixed), ID 0/6/7/3, MSG 5/3/10/3, ORD 4/0/1/4,
TXT 3/1/2/7 (+1 mixed), ATT 0/2/2/11, CALL 1/4/6/5, NET 0/5/4/5, LIFE 0/2/5/8, SEC 0/4/3/4, UI 2/0/1/6.
Counted by script from the tables on 2026-10-01; recount after any edit. Roughly a third of the list is unchecked, so "the app is ready
for the rest" is **not** a conclusion this file supports: it supports "these 26 are real, and these 63 are unknown".

## 5. What to do with this file

1. The owner decides section 1.2 (D, or D then A). Until then no group-ownership code is written.
2. Most ❌ rows map to ERROR-089…094 and, for the device-visible ones, a test in backlog section 4k. Two do not: MSG-16 (search
   wildcards, F9) has only `EDGE-14` and no ERROR yet, and CALL-11 (group call log rows) is a documented feature gap. A ❔ becomes a test or a code
   read when someone has time; change its mark here and add the result, do not delete the row.
3. When a case is fixed, mark it ✅ with the commit and keep the old text under it, so the history stays readable (`AGENTS.md` §27).
