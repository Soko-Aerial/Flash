# Progress Log — Stream A (rvw22-a-messaging)

Scope: the messaging / search / security findings of
`docs/audit/2026-10-22-comprehensive-brutal-code-review.md` assigned to stream A:
**F34, F35, F36, F37, F54, F55, F65, F66**. Branch `agent/rvw22-a-messaging`,
worktree `E:\FlashWork\rvw22-a-messaging`. Prefix `R22A-`, ERROR range 760..769.

Out of scope by instruction: every CRITICAL/HIGH finding, and the files the owner
was editing (`HubChatBridge`, `HubFileTransfers`, `RealFlashChatRepository` group
secret / outbox / text-inbound paths). Nothing was pushed or merged; `dev` and
`main` were not touched.

---

## 2026-10-22 — Findings F34, F36, F37, F54, F65 fixed; F35, F55, F66 reported

### Worked on

Verified all eight assigned findings against the code first, then fixed the six that
were real, with a test that fails without the fix and a mutation check that proves it.
Two findings are deferred with the patch and the decision they need written out
(`deferred-and-residual.md`), not guessed at.

### Result per finding

| Finding | Severity | Status | Commit`*` |
|---|---|---|---|
| F34 late delivery receipt rewrites READ | MEDIUM | **FIXED** | `0e6a2d18` |
| F35 a former member's signed history is dropped on catch-up | MEDIUM | **FIXED** (removal-time residual open) | `18bd6275` |
| F36 voice/call markers are searchable text | MEDIUM | **FIXED** | `05bb7758` |
| F37 the 28-byte empty-plaintext frame is rejected | LOW | **FIXED** | `37058be5` |
| F54 the Links search scope reads only the first link | LOW | **FIXED** | `0451e507` |
| F55 read receipts and reactions have no radio path | LOW | **DEFERRED** (was wrongly NOT-A-BUG) | — |
| F65 sealing throws on a malformed published key | MEDIUM (security) | **FIXED** | `c0a76fd7` |
| F66 key material is not zeroed in memory | LOW | **DEFERRED** | — |

`*` hashes as of the final rebase onto `dev` `93212c7f`. The branch is rebased
whenever `dev` moves, so **match a fix by its `(R22A-NN)` subject suffix, not by
hash.**

### Corrections to my own earlier reading (read this before trusting a summary)

My first pass produced three one-line summaries that were wrong or too strong. The
audit's own text is the authority; `deferred-and-residual.md` now carries it verbatim
for the three:

1. **F35 describes the opposite of what I first wrote.** The audited defect is that
   *legitimate history* from a member who has left is **dropped**, not that a removed
   member can backdate `sentAt`. Confirmed and fixed here (`18bd6275`). The
   backdating worry is real, but it is the *residual* of that fix (catch-up trusts the
   holder it asked), and it needs a signed removal time — a follow-up, not a reason to
   leave history broken.
2. **F55 was labelled NOT-A-BUG; that was too strong.** Delivery really is covered by
   the radio link-layer ACK, but **read receipts and reactions are not** — they have
   no radio encoding at all, so a radio-only peer never sees read state or reactions.
   It is **DEFERRED** because the fix is a radio wire-format decision, not a fallback
   line (`RadioChatBridge.sendDirect` takes a concrete `TextMessage`).
3. **F66's deferral stands, its stated reason did not.** `GroupSecret` is *not*
   cached in `RoomGroupSecretStore` (it re-decodes per call). The real blockers are
   multiple owners, copy-on-read APIs, and Base64 `String` round trips that cannot be
   zeroed.

### Rebase history

The branch was rebased twice while the work was in progress, because other streams
kept landing on `dev`:

1. `8bbcbdda` (original base) → `f3385069` (the server/mailbox stream), clean except
   for `DirectoryHubSealing`/`HubChatBridge`, where `dev` had added
   `openFailureMayClear` to the port — different regions, auto-merged.
2. `f3385069` → `93212c7f`, which brought `081b1ddb` (RVW22 F12: a group message is
   no longer sent to a removed member) and the CRITICAL/HIGH log commit. F12 edits
   the same `RealFlashChatRepository.kt` and its test file as F34/F36; auto-merged,
   and the whole suite was re-run afterwards (numbers below).

F12 is worth noting next to F35: it filters *future* sends with
`GroupMemberEntity.isActive`, which is the right answer to "who still gets a message".
F35 is the other half — what happens to what a member already wrote — and the two
together are why the `isActive` requirement belongs on the live lanes only.



### Changed

**F34 — a receipt can no longer walk an acknowledged row backwards.**
`MessageDao.updateStatusIfUnacknowledged` is now used at *both* receipt sites:
the LAN/hub frame handler (`RealFlashChatRepository` ~6023) and the radio
gateway ACK callback (~655). `MessageDao.updateStatus` is kept with KDoc saying
it must not be used for a delivery transition (no production caller remains).
`outboxDao.delete` stays unconditional on purpose: retiring the outbox row is the
commit point of a receipt, whatever the bubble shows.
Tests: `RealFlashChatRepositoryTest` (`a delivery receipt arriving after a read
receipt leaves the row READ`, `… on the radio path …`, plus
`a failed row that is acknowledged becomes DELIVERED` so the guard is not
over-tightened) and a `FakeRadioOutboxPort` to drive the ACK flow.

**F36 — the internal marker rows are no longer searchable content.**
New `MessageTextMarkers` (`VOICE = "vmsg:"`, `CALL = "cmsg:"`) in
`:core:persistence` next to the queries that must ignore them; the repository
aliases the same constants so encoder and SQL cannot drift. Both search queries
(global `searchMessages`, in-chat `searchConversationMessages`) exclude a marker
row from the **text** match while the attachment-name match is untouched — a
voice note is still findable by its file name. `RealFlashChatRepository.searchChats`
additionally refuses to build a message hit from such a row when it came back
because its *name* matched, otherwise the waveform blob would still become the
snippet. The two in-memory `MessageDao` fakes (`RealFlashChatRepositoryTest`,
`GroupTestFakes`) mirror the SQL rule so their results stay comparable.

**F37 — the empty plaintext frame is accepted.** `E2eFrameCodec`'s length guard
was `frame.size > nonce + tag`, which rejected exactly the one legal size for an
empty body (12 + 16 = 28 bytes). Now `>=`; anything smaller still cannot hold a
tag and is still refused with the same message.

**F54 — the Links scope reads every link.** `FlashSearchText` now scans every
`URL_REGEX` match through one private `links()` sequence shared by `firstUrl`
(still the first link, for the "contains a link" badge) and `matchesLink` (any
link). So `read https://a.example and https://b.example` is found by searching
for `b.example`, in `:core:messaging` and through the real `:ui:chat` Links scope.

**F35 — a former member's signed history survives catch-up.**
`SignedGroups.verifiedAuthorLabel` required the author to be an *active* member, and
`handleSyncPush` uses it as its only per-author check, so every message signed by
someone who had since left came back as `bad_signature` and was dropped. The lookup
now accepts a tombstoned row and verifies against the certified key the tombstone
still carries; an unknown author and a bad signature are still refused. Requiring an
active author was redundant on every live lane (each gates membership before the
signature check: `isActiveGroupMember` / `isActiveTrustedMember` / `isRemovedHere`) and
destructive only on catch-up. The KDoc now says the method answers *who wrote this*,
not *who may speak now*, and names the residual: catch-up trusts the holder it asked,
so bounding a former member's *late* message needs a signed removal time the roster
does not have yet (`deferred-and-residual.md` §1).
Test: `SignedGroupsTest` — *history a member signed before leaving still reaches a
newcomer* (dev-b speaks, leaves, dev-d is added and receives it with the tombstone's
label and signature), while the existing *later messages are dropped* test still
passes.

**F65 — an unusable sealing key is never a plaintext fallback.**
`HubSealing.seal` returns `String?`: the ephemeral key pair, the ECDH and the
signature live in one `runCatching`, so a key that is not a P-256 point (or a
Keystore refusal) yields null instead of throwing `InvalidKeySpecException` out
of the send path. `DirectoryHubSealing` passes the null through and its status is
honestly still `AVAILABLE` — the directory *did* serve a correctly signed key;
the binding signature covers the bytes, it does not validate them. The decisive
change is in `HubChatBridge.sendPayload`: `AVAILABLE` + null envelope now logs and
returns false (message stays queued), instead of falling through to the
readable-content branch — which, with the opt-in on, used to put a message the
user chose to seal on the hub **in plaintext**. Test call sites use
`requireNotNull`/`assertNotNull` so a well-formed key that stops sealing is a
failure, not a weaker assertion.

### Verification

All on the final rebased branch (parent `93212c7f`, i.e. current `dev`), one Gradle
invocation through `tools\flash-gradle.cmd`:

| Task | Tests | Failures |
|---|---|---|
| `:core:persistence:jvmTest` | 84 (4 in `MessageSearchMarkersJvmTest`, re-run on their own too) | 0 |
| `:core:security:jvmTest` | 166 | 0 |
| `:core:messaging:jvmTest` | 367 | 0 |
| `:core:messaging:testAndroidHostTest` | 666 (102 in `SignedGroupsTest`) | 0 |
| `:ui:chat:jvmTest` | 698 | 0 |
| `:server:test` (3 sealing e2e classes) | 15 | 0 |

The same set was run three times: before the first rebase, after it, and after the
second one (which brought dev's F12 edit to the same `RealFlashChatRepository.kt`).
The numbers above are the run after the F35 fix, which was the last change that
touches `:core:messaging`. `:server:compileTestKotlin` is included in the run because
two server e2e tests call `HubSealing.seal` and had to follow the nullable return.


**Mutation checks** (production code reverted to its pre-fix form, only the new
tests present, then restored):

- F34: both `updateStatusIfUnacknowledged` calls back to `updateStatus` →
  the two "leaves the row READ" tests fail; the FAILED→DELIVERED test still
  passes (the guard's other half is intact).
- F36: SQL marker filter removed → `MessageSearchMarkersJvmTest` fails at 2
  assertions; the repository guard removed → `RealFlashChatRepositorySearchMarkersTest`
  fails. Both layers are load-bearing.
- F37: `>=` back to `>` → 3 of the 4 new cases fail.
- F54: `links(text).any` back to `links(text).take(1).any` → the new
  `matchesLink` case fails (`FlashSearchTextTest.kt:71`).
- F35: `takeIf { it.isActive }` restored in `verifiedAuthorLabel` → the new
  newcomer-history test fails (`SignedGroupsTest.kt:492`) and **only** that test —
  the live *later messages are dropped* case still passes, which is the proof the
  live gate is independent.
- F65 (twice): `runCatching{…}.getOrNull()` → `.getOrThrow()` reproduces the
  audit's `InvalidKeySpecException` in **both** key-level tests, and the
  plaintext fall-through restored in `sendPayload` fails the bridge test.

Nothing device-verified: no phone, no deploy, no network probing was used.

### Problems met

- `:core:messaging` `commonTest` cannot construct a `FlashCrypto`
  (`SoftwareFlashCrypto` is `internal` to `:core:security`, and
  `KeystoreFlashCrypto` needs a `Context`). The port-level F65 test therefore
  lives in the existing `androidHostTest` `HubSealingPairedIdentityTest`, which
  already owns a JCA crypto fixture for `DirectoryHubSealing`.
- PowerShell here-strings mangle backticks and `>` inside a commit message (the
  shell wrapper is cmd-like): commit messages are now written to a temp file and
  passed with `git commit -F`. For the same reason `Get-Content | -replace |
  Set-Content -Encoding UTF8` corrupted a log file's em dashes; files are rewritten
  with the editor instead of round-tripped through PowerShell.
- Two findings (F34, F36) touch `RealFlashChatRepository.kt` and its test file,
  so the "one commit per finding" rule needed hunk-level staging: a small
  throwaway helper splits a `git diff` per file into a patch of only the matching
  hunks, applied with `git apply --cached --recount`. No production file was ever
  left in a half-state.
- `SignedGroupsTest` is slow (102 tests, ~3 min); the F35 mutation run costs a full
  cycle. Budget for it.

### Remaining

- **F35's residual** — the catch-up lane trusts the holder, so a former member's
  *late* message can be relayed into history. Needs a signed removal time on the
  roster (+ a schema column); decision and patch sketch in
  `deferred-and-residual.md` §1.
- **F55 (DEFERRED)** — read receipts and reactions have no radio encoding; it is a
  radio wire-format decision, not a fallback line (`deferred-and-residual.md` §2).
- **F66 (DEFERRED)** — no single owner for `clear()`; the real win is reducing copies
  and the Base64 `String` round trips (`deferred-and-residual.md` §3).
- Device checks owed: `R22A-34`, `R22A-35`, `R22A-36`, `R22A-54` (see
  `backlog-additions.md`); `R22A-37` and `R22A-65` are unit-only by nature and
  recorded as such.
- A sentence for the user when the hub cannot seal (currently only a log line and
  a queued message) — backlog item, not invented here because `HubWaitReason`
  has a fixed vocabulary owned by another area.

### Next AI

Review `agent/rvw22-a-messaging` (8 commits on top of `93212c7f`: six fixes plus two
log commits) and merge the two `*-additions.md` files into the canonical logs.
Then pick one of the three deferred items — each has its evidence, its patch sketch
and the decision it needs in `deferred-and-residual.md`. Do **not** "fix" F35's
residual by adding a wall-clock comparison: `membershipVersion` mixes a certificate
`seq` (v2) with wall-clock `now` (legacy), so that check would be theatre.
