# Evidence probes and the log export header

Status: step 1 + first probe set built 2026-10-06 (ADR-087). Unit-tested; not yet read against a real device log.

## Why

The owner develops feature-first and tests later (AGENTS.md section 35). A device test is only useful if the log of the run can
answer "did it pass, and if not, why". ERROR-117 could not be settled from four real logs because they did not say which peers
were paired, which advertised `sw1`, how far the clocks were apart, or what kind of message a drop was. Probes make those facts
part of every log, so a log taken during normal use is evidence for the tests in `TEST-BACKLOG.md`.

## What a probe is

One log line under the tag `PROBE`, written by `FlashProbe.emit(name, "key" to value, ...)` (`core:common`, `logging` package):

```text
12-09 14:03:11.482 I/PROBE: group.msg.in group=g2-ab12c from=dev-b signed=true skewMs=2917 stored=true
```

Rules (AGENTS.md section 24 applies in full):

- Emitted when state **changes** (a session comes up, a frame is accepted or refused, a file is fanned out). Never per chunk,
  per frame or per tick.
- Values are ids shortened with `FlashProbe.short` (first 8 chars), counts, booleans, enums and **reason codes**. Never a key,
  secret, fingerprint, invite link, file name, file path or message text. `FlashProbe.sanitize` keeps a value on one line, no
  spaces, at most 80 characters; the file sink's `redact()` is a second line of defence, not permission.
- A refusal always carries `reason=<code>` from the table below, so "dropped" is never ambiguous.
- `skewMs` = the author's `sentAt` minus this device's clock at receipt. Positive: the author's clock is ahead.
- `FlashProbe.emit` never throws.

## Session header and snapshot (export)

**Android:** Settings, "Export logs" writes, after the first `Flash log export; ...` line, a block built by
`FlashLogContext` (`app/.../debug/FlashLogContext.kt`) at the moment of export:

| Line | Fields |
|---|---|
| `session.header` | `exportAtMs` (device clock, pins the log timestamps), `app`, `build`, `os`, `api`, `device`, `local` (this device's short id), `tzOffsetMin`, `processUptimeMs`, `swarm` (the switch) |
| `snapshot.sessions` | `count` of live sessions, `paired` count of paired peers |
| `snapshot.session` | one per live session: `peer`, `paired`, `features` (comma list, `-` when none advertised) |
| `snapshot.groups` | `count` |
| `snapshot.group` | `group`, `proto` (`v2` / `legacy`), `me` (this device's role), `members`, `online`, `vouched` |
| `snapshot.member` | one per other member: `group`, `member`, `role`, `paired`, `vouched`, `online`, `session` (a live session exists) |
| `session.snapshot.failed` | `error`; the snapshot threw, the header above it is still valid |

**Desktop:** the log is `~/.flash/desktop.log` (overwritten each launch). It gets `session.header` (`app=desktop`, `os`, `java`,
`local`, `tzOffsetMin`) when the engine starts. No snapshot on desktop yet (no export action exists there).

## Event vocabulary

| Probe | Emitted by | When | Fields |
|---|---|---|---|
| `session.up` | `WsFlashNetwork`, `JvmWsFlashNetwork` | a session is admitted to the registry | `peer`, `dir` (`in`/`out`), `features`, `adopted` (it replaced a glare incumbent) |
| `session.down` | same | a session ended | `peer`, `dir`, `reason` (the close reason text) |
| `group.msg.out` | `RealFlashChatRepository.sendGroupText` | a group text was stored and queued | `group`, `recipients`, `signed` |
| `group.msg.not_sent` | same | nothing was queued | `group`, `reason=no_other_active_member` |
| `group.msg.in` | `...handleGroupFrame` Message | a live group message was accepted | `group`, `from`, `signed`, `skewMs`, `stored` (false = duplicate) |
| `group.msg.drop` | same | a live group message was refused | `group`, `from`, `reason`, `skewMs` |
| `group.media.out` | `beginGroupAttachment` | a group file row was created | `group`, `swarm`, `recipients`, `activeMembers`, `notAnnounced`, `signed` |
| `group.media.in` | GroupMedia | a whole-file offer was parked for the transfer layer | `group`, `from`, `kind` (`whole_file` or `swarm_offer_unusable_parked_as_file`), `skewMs` |
| `group.media.drop` | GroupMedia | a file offer was refused | `group`, `from`, `reason`, `swarm`, `skewMs` |
| `swarm.offer.in` | GroupMedia | a verified swarm offer was accepted | `group`, `from`, `via=live`, `paired`, `skewMs` |
| `swarm.offer.ignored` | GroupMedia | an unpaired sender's offer could not be used | `group`, `from`, `via=live`, `reason` |
| `group.file.fanout` | `GroupFileSender.send` | once per file sent to a group | `group`, `members`, `swarmable`, `swarmOffers`, `wholeFiles`, `notReached`, `noFeatures` |
| `group.sync.in` | `handleSyncPush` | a catch-up message was accepted | `group`, `relay`, `author`, `kind` (`text`, `swarm_offer`, `offer_kept_swarm_off_here`, `offer_unused_unsigned`), `signed`, `skewMs` |
| `group.sync.drop` | `handleSyncPush` | a catch-up message was refused | `group`, `from` (the relay), `reason`, `skewMs`, optional `author`, `hasOffer` |
| `group.catchup.request` | `requestGroupCatchUp` | a bootstrap catch-up request went out | `group`, `to` (members asked), `why` |
| `swarm.offer.shown` | `SwarmRowProbes` (swarm driver) | a group file offer became a row on a receiver | `transfer`, `bytes` |
| `swarm.accepted` | same | the receiver's row left the offer state (a person tapped Accept, or auto-accept) | `transfer`, `afterOfferMs` (how long the offer waited for a person) |
| `swarm.first_byte` | same | the first byte of an accepted file arrived | `transfer`, `sinceAcceptMs` (the start delay the app itself adds) |
| `swarm.recv.done` | same | a receiver finished and verified the file | `transfer`, `bytes`, `sinceAcceptMs`, `avgKBps` |
| `swarm.member.first` | same | sender side: a member's holding first grew | `transfer`, `member`, `sinceSendMs` |
| `swarm.member.done` | same | sender side: a member announced the whole file | `transfer`, `member`, `sinceSendMs`, `done` (members done so far), `seen` (members listed) |

A row first seen already finished (receiver) or with members already holding (sender) was restored after a restart; it produces no `swarm.*` line (ERROR-121), so every `swarm.*` line describes the current run.

### Reason codes

| Reason | Meaning |
|---|---|
| `not_active_member` | the sender has no active roster row in this group on this device |
| `too_long` | text longer than `GroupPolicy.MAX_MESSAGE_TEXT_LENGTH` |
| `bad_signature` | a v2 message / file offer / catch-up copy did not verify against the author's roster key |
| `bad_offer_signature` | the catch-up message verified but its swarm announcement did not |
| `removed_here` | this device was removed from the group |
| `sender_not_allowed` | file offer: the sender is not allowed (whole-file push needs a paired sender; a swarm offer needs an active trusted member) |
| `no_matching_request` | a catch-up push that answers no request this device sent |
| `no_other_active_member` | the group has nobody else to send to |
| `not_a_swarm_offer` / `incomplete_offer` / `swarm_off_here` / `bad_announcement_signature` | why a swarm offer from an unpaired sender was unusable (`swarm.offer.ignored`) |

## Reading a log (what the probes settle)

- "Why did the download take long to start?" On the receiver: `swarm.accepted afterOfferMs` is the time the offer waited for a
  person; `swarm.first_byte sinceAcceptMs` is what the app added after the tap (ERROR-119 aims at a few hundred ms on a LAN, not
  yet measured). On the sender: `swarm.member.first sinceSendMs` per member and `swarm.member.done` when each has the whole file.
- "Did the admin get a swarm offer or a whole file?" `group.file.fanout` on the sender: `swarmOffers` vs `wholeFiles`, and
  `noFeatures` > 0 means that peer's features were not known at send time (not connected, or no `caps` advertised);
  `snapshot.session ... features=` shows what it advertises now.
- "Was it the clock?" `group.msg.drop ... reason=bad_signature skewMs=N` and `group.sync.in ... skewMs=N` next to the
  `session.header ... tzOffsetMin` and `exportAtMs`. A signed message is kept as signed within 5 minutes (ADR-086); beyond that
  it is clamped and cannot be relayed.
- "Who was a member and how were they trusted?" `snapshot.member` (`paired` / `vouched` / `session`).

## Adding a probe

1. Emit it at a state change, with reason codes, no content. Use `FlashProbe.short(id)` for every id.
2. Add it to the table above in the same change. A probe not in this table does not exist for the analyzer.
3. Add a test that captures `FlashLog` (see `SignedGroupsTest` "evidence probes say why ...", `FlashProbeTest`).
4. When a backlog test depends on it, name the probe in that test's Pass line.

## Not built yet (offered, not asked for)

- A mapping file from backlog test ids to the probes that decide them (step 3), and an analyzer script that reads one or two
  exports and proposes PASS / FAIL / NOT-EXERCISED per test (step 4). The analyzer would only propose; results are still
  recorded by the owner (section 35 rule 3).
- A "mark this moment" note for tests only a person can judge (animation smoothness).
- Probes for calls, pairing, discovery and transfer (this first set covers sessions, group messages, group files, swarm offers
  and catch-up only).
- A separate evidence stream with longer retention than the 5 x 1 MB rotating log.
- A desktop export action and a desktop snapshot.
