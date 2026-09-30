# ADR-044 phase V2 — vouched trust and groups of 20: build plan

**Status: PLAN 2026-09-30, slices S1–S6 below. No owner decision is open** (ADR-044 #3 owner-only vouching, #4 scope, #6 the limit of
20 were accepted; ADR-056 confirmed 20 and parked 32). V2 builds on V1 (`docs/group/v1-signed-membership-plan.md`, code `c0c7ae8`,
device check GT-02 owed) and on the session ceiling (ADR-057: ceiling 24, dial budget 20). Design source: review
`docs/group/v0-threat-review.md` section 5. Where this file and the review differ, this file wins for V2.

Scope in one line: in a **v2 group** a member no longer has to be paired with every other member. Everyone pairs with the **owner**,
and the owner's signed certificate is the introduction. A vouched member can talk in that group and join its calls, and nothing else.

## What V1 already gives V2 (verified in code 2026-09-30)

- A cert carries the subject's SPKI and is owner-signed, so an unpaired member's key is already known to every receiver.
- `TofuX509TrustManager` only asks `FlashPinVerifier.isPinned`, which is `TofuPinVerifier` over `FlashTrustStore.getPin`. So a vouch
  needs **no TLS change**: it has to put the vouched fingerprint where `getPin` finds it, before the peer connects.
- The planner dials every discovered device in STANDARD and BOOST (`ConnectionPlanner.plan` has no pairing rule), ECO counts group
  rosters as contacts (`EcoLinkSelector.contacts`), and presence eligibility already includes "fellow member of a group"
  (`PresenceState`). Tips are dialed against a *pinned* subject (`hasPin = getPin != null`). Read 2026-09-30; the only V2 work in that
  area is a pin, plus a test that the budget fits 20.
- V1 and V2 ship together (no release tag contains V1 code), so **`gv` stays 2**; there is no field device that has V1 but not V2.

## E1. Pin sources in the trust store

`FlashTrustStore` gains four methods, **abstract, with no default body** (the review, section 5.2: a default no-op would silently turn
vouching off on a host, and the compiler must find every implementer):

```kotlin
public enum class PinSource { PAIRED, VOUCHED, TOFU }
public enum class VouchVerdict { ACCEPT, CONFLICT_PAIRED, CONFLICT_VOUCHED }

public fun pinSource(deviceId: FlashDeviceId): PinSource?          // null: no pin
public fun vouchVerdict(deviceId, fingerprintHex, groupId): VouchVerdict   // pure query
public fun applyVouch(deviceId, fingerprintHex, groupId): VouchVerdict     // installs when ACCEPT
public fun revokeVouch(deviceId, groupId)
```

`pinSource` is derived, not stored: `isTrusted` is PAIRED, else a vouch record is VOUCHED, else a pin alone is TOFU. The only new state
is a per-device **set of vouching group ids**. Precedence when a vouch meets an existing pin (one copy of the rule, `VouchRules`,
shared by both stores):

| Existing state of the id | Same fingerprint | Different fingerprint |
|---|---|---|
| No pin | install, VOUCHED | install, VOUCHED |
| TOFU (pin without pairing or vouch) | becomes VOUCHED (adds the group) | **replaced** (this is what defeats a device that connected first as that id) |
| VOUCHED by this group only | adds nothing new | **replaced** (the owner re-issued the cert after a reinstall) |
| VOUCHED by any other group | adds the group | `CONFLICT_VOUCHED`: two owners disagree, keep the first, refuse the cert |
| PAIRED | accept, pin untouched (the group is still recorded, so unpairing later leaves the member vouched) | `CONFLICT_PAIRED`: pairing wins, refuse the cert |

`revokeVouch(id, group)` removes the group; when the set is empty and the device is not paired, the pin goes too (a PAIRED pin is never
touched by group operations). `revokeTrust` (unpair) keeps the pin while a vouch record exists, so unpairing a member of a group does not
strand them behind a TOFU pin. A fifth verdict, `INVALID`, covers a blank fingerprint or group id. Persistence: Android `vouch_<id>` and desktop `vouch.<id>`, comma-joined group ids (`g2-<hex>` contains
no comma); new keys only, so existing installs need no migration.

## E2. Rules for a cert (`GroupSignatureRules`)

The owner is the trust root, so an owner-issued **active** cert for a device is a vouch for its key. The binding check for a subject
that is neither this device nor the owner becomes:

1. the stored roster already holds this key for the subject: accept (as V1);
2. the subject is paired: the cert key must equal the pin (as V1), otherwise `subject-key-binding` and the row is not stored;
3. otherwise (**new**) an owner-issued active cert is accepted unless `vouchVerdict` says `CONFLICT_*`;
4. a self-issued leave still needs rule 1 or 2: an unpaired stranger cannot tombstone anyone.

The charter still requires the owner to be **paired** with the receiver: trust stays one hop deep. After a merge, `SignedGroups` calls
`applyVouch` for each accepted active cert of an unpaired subject **before** the row is stored (a conflict means the row is not stored)
and `revokeVouch` for each accepted tombstone. `ensureVouches` repeats the install for stored rows whose vouch is missing (a trust store
that was cleared), so the roster and the pins cannot drift apart for long. Leaving or deleting a v2 group revokes every vouch it made.

## E3. The group trust predicate

`isGroupPeerTrusted(groupId, peer)` = `isTrustedPeer(peer)` **or** (the group is v2, the peer is an active member of the stored, verified
roster, **and the peer's live TLS identity key equals that cert's key**). The last clause is the important one: it does not rely on the
pin store at all, so an attacker's TOFU session that was open before the vouch replaced its pin is still not trusted in the group, and a
member cannot be impersonated by a device that only knows their id.

| Path | Predicate |
|---|---|
| Group text, receipt, read, delete, typing, sync, bundle (known group) | `isGroupPeerTrusted` |
| Bundle for an **unknown** group | paired sender only (V1 rule, unchanged; the owner is paired with every invitee) |
| Group call: invite, presence, query, join list | `isGroupPeerTrusted(frame.groupId, peer)` (`CallCoordinator.isGroupTrustedPeer`, suspend) |
| Group media announcement and attachment send | **paired only** (`isActiveTrustedMember`): a vouched member never receives or offers files |
| 1:1 chat, files, 1:1 call invite, push-to-talk | `isTrustedPeer`, unchanged |

## E4. Limits and the receiver's cost

- `MAX_MEMBERS_V2 = 20`. Legacy `MAX_MEMBERS` stays **6**: every shipped codec rejects a longer legacy roster. `MAX_BUNDLE_CERTS` becomes
  84 (about 30 KB as text, far below the 4 MB message limit).
- A first bundle costs at most 21 verifications; the per-peer budget is 120 per minute and stale certs are free, so 19 peers each sending
  the roster at session-up cost one full verification pass in total.
- `GroupPolicy.MAX_MEMBERS_V2 - 1 <= ConnectionModePolicy.DIAL_BUDGET` is a test in `core:engine` (the only module that sees both).

## E5. Owner remove

V1 handled an owner tombstone on receive but had no sender. V2 adds `RealFlashChatRepository.removeGroupMember(groupId, deviceId)`
(owner only; next `seq`, `active = false`, `issuerId = owner`; the bundle goes to every remaining member and to the removed device). It
is what makes "removal revokes the vouch" testable end to end. The UI entry point is S5.

## E6. UI (S5)

A vouched member shows "Added by <owner>" and a **Verify** action that runs ordinary pairing. Wording and placement are decided against
the existing member sheet when S5 starts. Verify is not new logic: pairing already replaces the pin with the paired key and the flag
makes the member PAIRED.

## Slices (each ends green with its own tests; code and docs separate commits)

| Slice | Content | Tests |
|---|---|---|
| **S1** | `core:security`: `PinSource`, `VouchVerdict`, `VouchRules`, the four abstract methods, Android and desktop implementations, every fake in the repo. | table above, both stores, persistence round trip, `revokeTrust` keeping a vouched pin |
| **S2** | `core:messaging`: `GroupVouching` port, rules E2, `SignedGroups` vouch/revoke/ensure, `isVouchedMember`, gate E3 in the repository, `MAX_MEMBERS_V2 = 20`, owner remove. | unpaired members join and talk; TOFU-squat replaced; paired conflict refused; removal revokes; impersonation with the wrong session key refused; 20-member group |
| **S3** | `core:calling` `isGroupTrustedPeer`, `TrustStoreGroupVouching` adapter in `core:engine`, wiring in the three hosts. | `CallCoordinatorSecurityTest` additions; engine wiring test |
| **S4** | `MAX_MEMBERS_V2 - 1 <= DIAL_BUDGET`; confirm by test that a vouched, unpaired discovered member is a dial candidate in each mode and that presence tips dial a vouched pin. | planner / presence / eco tests |
| **S5** | UI labels and Verify. | UI tests where the surface has them |
| **S6** | Docs: protocol (no wire change, stated), security section 8/9, ADR-044, review pointer, TEST-BACKLOG GT-03 (4+ devices, 2 never paired), progress, handoff, memory. | — |

## Known limits carried by V2

- The owner is a single point of trust and failure (unchanged). A lying owner can vouch a key it controls: mitigated by "Added by" and
  Verify, not prevented.
- A vouched member can read and write in the group and join its calls. They cannot send or receive files there and never gain 1:1 rights.
- A removed member keeps what they already received (no per-sender keys).
- An attacker's already-open TOFU session as a vouched id stays a 1:1 peer (review F-8, adjacent debt); it is not trusted in the group
  because its key differs from the cert's.
- Owner and members must all be `gv >= 2`; nothing changes for legacy groups (still at most 6, everyone mutually paired).
