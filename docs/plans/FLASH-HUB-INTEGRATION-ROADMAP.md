# Flash Hub integration — master roadmap (read this first)

**Written:** 2026-10-14 by Claude Sonnet 5.5. **Status of everything below: PLANNED. Nothing is built, nothing is verified.**
**Audience:** an implementing AI agent that will do each plan step by step without the author around. Read this whole file, then the
plan you are assigned. Do not skip sections because they look obvious.

---

## 0. How to use this plan set

| Order | File | What | Size |
|---|---|---|---|
| 1 | `docs/plans/HUB-OFFLINE-ORG-TRUST-PLAN.md` | **Plan B.** Colleagues stay trusted on the LAN when the hub is unreachable; hub groups keep working locally | Largest. Phased (the owner review gate was cleared 2026-10-14: ADR-135 accepted) |
| 2 | `docs/plans/HUB-DEPARTMENT-HEADS-AND-INVITES-PLAN.md` | Plan A1. Department head logins, invite roles, scoped console | Medium |
| 3 | `docs/plans/HUB-DIRECTORY-PRIVACY-PLAN.md` | Plan A2. Option C: org-wide directory, hidden departments | Small–medium |
| 4 | `docs/plans/HUB-ANNOUNCEMENTS-PLAN.md` | Plan A3. Announcements (plaintext on the hub), push, search, console | Large |
| 5 | `docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md` | Plan A4. The Hub tab itself (UI-058, `docs/ui/flash-hub-tab.md`) | Large; needs 2–4 for its content |
| - | `docs/plans/HUB-QUALITY-CONTRACT.md` | **Mandatory quality contract** for every phase of every plan (the minimum is a failure) | Read before coding |
| - | `docs/plans/HUB-PLANS-PROMPTS.md` | Copy-paste kickoff prompt for the implementer and verifier prompt for a second AI | For the owner |

Dependency graph (an arrow means "must be finished and tested first"):

```
Plan B (offline trust) ──┐
                         ├──► Hub tab (A4) needs: directory sync (B), privacy scoping (A2), announcements (A3)
A2 privacy scoping ──────┤
A1 dept heads ───────────┼──► A3 announcements (department-head posting needs A1's roles)
                         │
A3 announcements ────────┘
```

The owner's chosen order (2026-10-14, D4): **B first, then A.** Inside A the natural order is A2 → A1 → A3 → A4 because A2 fixes the directory
visibility that B's sync and the People list both rely on. Do not start A4 before A2, B phase 1 and A3 are at least "built and unit-tested".

## 1. Owner decisions (2026-10-14) — binding

| ID | Decision |
|---|---|
| D1 | A "Flash Hub" tab appears **only when the device is enrolled** and **replaces the Transfers tab**. Transfers becomes a button in the chat-list top bar. Unenrolled users keep the four tabs they have. The "Browse department groups" top-bar action moves into the Hub tab (Groups segment). |
| D2 | Directory privacy **option C**: org-wide names, departments and online dots are visible by default; an admin can mark a department **hidden** (findable only by its own members and admins). Keys stay restricted as today. Direct messages stay unscoped (ADR-127 addendum: inter-department messaging is wanted). |
| D3 | A department may have **several heads** (`adminDeviceIds` is already a list). |
| D4 | Order: **Plan B first**, then Plan A. |
| D5 | Announcements: posted by org admins (company-wide or any department) and department heads (their own department only), from the phone/desktop app **and** from the hub console. **Stored as plain text on the hub**; server-side search and console previews are wanted. |
| D6 | Multi-organisation (one user in two organisations / org switcher) is **deferred**: FO-18 in `docs/FUTURE-OPTIMIZATION.md`. FO-16 (one device per member) still stands. |
| D7 | Console invite roles: **Employee** (`member`) and **Department head** (`dept_admin`). Creating a department offers "invite its first head". |
| D9 (owner, 2026-10-14, answer to question 10) | **Keys are org-visible for non-hidden departments** (identity public key and sealing public key); keys of hidden departments are delivered only to that department's members/admins, through the lookup by known id, and in the Plan B Layer 2 handshake. This **refines D2** ("keys restricted as today"): the owner accepted the author's recommendation. The server constant `DIRECTORY_KEYS_ORG_WIDE` therefore ships **on (`true`)**. |
| D10 (owner, 2026-10-14, answer to question 2) | **Phones pass revocations to each other** over the LAN (revocation gossip, Plan B phase B5 is **in scope, not optional**). Roster-statement gossip (Plan B §10.4) follows the same pattern if ADR-135 is accepted. |
| D11 (owner, 2026-10-14, "all recommended") | Every remaining question of section 6 is answered with the author's recommendation: offline lease default 30 days (admin 1-365); **ADR-135 accepted** (hub-signed roster statement for hub groups on the LAN, the FO-17 secret escrow is dropped); announcement title 120 / body 4,000 characters, no retention by default, optional expiry per announcement; notifications on for company-wide and own-department announcements (title only); a department head cannot create another head, rename a department or revoke a leaver in v1; a hidden department's members are not found by name through Layer 2; no per-department presence switch in v1. |
| D8 (owner, same day) | "When paired online or joined a group online, it should still work on the local network if the server is not available." This is Plan B's goal statement. |

Owner statements to honour literally: the user is on a company Wi-Fi without internet and expects to **continue chatting with a colleague** they
paired with or met through the hub, and **hub groups they joined online must keep working locally**.

## 2. Rules for the implementing agent (the author expects these to be followed)

1. Obey `AGENTS.md` (repo root). Especially: read before editing (§4), log progress/errors/decisions (§6–8), add every test you could not run to
   `docs/testing/TEST-BACKLOG.md` **in the same session** (§35), never delete docs (§27), never log secrets (§24), UI work follows §34
   (the component doc `docs/ui/flash-hub-tab.md` is DESIGNED; you may implement it).
2. **Do not use subagents** (the owner asked for this explicitly in earlier sessions). Work in small commits **only when the owner asks you to commit**.
3. **The plans name files, classes and line numbers that were true on 2026-10-14. They may have drifted.** Before editing anything a plan names:
   open it, confirm the symbol exists, and if it does not, search for its replacement; if you cannot find the intended place, **stop and ask**, do not invent.
   Anything in a plan marked **VERIFY** is an assumption the author could not prove. Prove it (read code or write a test) and record the answer in
   the plan's "Implementation log" section at the end of that plan file.
4. **Gradle:** use only the working command documented in the memory/handoff (`logs/handoff.md`, search "gradle"); the daemon needs the AF_UNIX
   loopback fix and JBR 21, not the drive-E JBR 25. If unsure, search `logs/handoff.md` and `docs/troubleshooting.md` for "gradlew".
5. Every phase ends with: (a) the unit tests the plan lists, written and green; (b) a **mutation check** for security-relevant logic (change the
   code so it is wrong, see the test fail, restore); (c) `:app:compileDebugKotlin` and `:desktop:compileKotlinJvm` compile; (d) a progress entry;
   (e) backlog entries for the device tests. Do not declare a phase done on "it compiles".
6. **Never weaken an existing test** to make a change pass. If an existing test contradicts a plan, stop and ask. Known flaky tests are listed in
   `logs/handoff.md`; a flaky failure must be re-run once and recorded, not "fixed".
7. **Never commit** a secret, a key, or a `keystore.properties`. Never log keys, tokens, passwords, certificate private parts, announcement bodies
   at INFO level (log ids and sizes), or full device ids (use the first 8 characters, as existing code does; follow the existing helper).
8. **Wire changes are contracts.** Any new/changed REST route, relay frame, signed structure or cert field requires, in the same change:
   `docs/protocol.md` text, `docs/protocol-routes.txt` (the `RouteTableContractTest` header explains how to regenerate), golden test vectors for every
   signed byte layout, and a compatibility statement (what an older client or older hub does). Old clients must keep working with a new hub
   unless the plan says otherwise. Add a capability/feature string rather than bumping a version when possible.
9. **Schema changes:** Room schema number and the server `Migrator` are shared with other agents. Before adding a step, `git log -5 -- core/persistence`
   and read `FlashSchemaSteps.kt`; if the number you planned is taken, take the next one and note it. Each migration needs a migration test
   (existing migration tests are the pattern). Never edit an existing migration step.
10. **Stop-and-ask list (do not guess these):** anything touching the TLS trust manager's accept rule beyond what a plan spells out; any change to
    pairing, vouching or group-gate semantics not listed in the plan; deleting data; changing what a hub operator can read; adding a dependency;
    anything that makes an unenrolled user's app behave differently.
11. If a plan step turns out to be impossible or wrong, **write that down** (plan "Implementation log", `logs/errors.md` if it is a defect, an ADR if it
    changes a decision) and stop. A silent workaround is worse than a stopped agent.
12. Think beyond the plan: each plan has a section "Situations the plan does not spell out" with the author's expectations. They are requirements.
    If you meet another situation (an error, a race, an unusual input) that no plan mentions, **handle it, test it, and add it to the plan's
    implementation log.** The author prefers "handled and documented" over "left out because not in the plan".
13. **The quality contract is binding** (`docs/plans/HUB-QUALITY-CONTRACT.md`): explain-back before code, the 12 coverage categories, the evidence table, the banned shortcuts, the adversarial self-review (>= 10 specific findings, >= 5 extra situations) and the phase report. Every plan's "Beyond the minimum" section is required work. A phase report that lacks evidence is treated as not done.
14. **No shortcuts on the owner's decisions:** D9 (keys org-visible for non-hidden departments), D10 (revocation gossip), D11 (all recommended answers) are decided; implement them fully, do not ask again and do not weaken them. Honesty rule for wording about encryption: chat between phones on the LAN is encrypted in transit (TLS with pinned identities); one-to-one hub messages are sealed end to end when the recipient has a sealing key; group messages and announcements through the hub are readable by the hub (no group key exists, FO-27), and the UI says exactly that and no more.

## 3. Ground truth: what exists today (verified by reading code, 2026-10-14)

Mark of confidence: **[read]** = the author read the code; **[infer]** = deduced, VERIFY.

### 3.1 Client enrolment and hub wiring
- Enrol link `flash://enroll?server=&token=&root=` (`EnrollmentInvite`), `EnterpriseHub.enroll`. **[read]**
- Settings has a Hub card (`FlashHubCard`, `FlashHubStatusMath`), content opt-in, approvals dialog (ADR-131), `FlashHubNotice`. **[read]**
- Relay chat: sealed 1:1 via `HubChatBridge`, `HubRelayPort`, `DirectoryHubSealing`; delivery acknowledged (ADR-122); LAN tried first. **[read]**
- Browse organisation groups: `FlashBrowseOrgGroupsSheet`, opened from the chat-list top bar (`onBrowseOrgGroupsClick`, Relay icon) only when
  `settings.isEnterpriseEnrolled`; MainActivity around lines 2775/3007; DesktopShell around 1137/2121. **[read]**
- Joining a hub group creates a **legacy** group conversation (`upsertLegacyGroupConversation` inside `RealFlashChatRepository.joinEnterpriseGroup`,
  around line 6784) and calls `trust.linkGroupToHub`; **no roster rows** are written. **[read]**
- `fetchEnterpriseDirectoryMembers` (`FlashChatRepository.kt:514`, impl in `RealFlashChatRepository` around 6749) has **no UI caller**. There is no
  People, Departments or announcements UI. **[read]**
- There is no UI to start a chat with a directory colleague you have not paired with. `upsertDirectConversation` (around line 5996) exists. **[infer]** VERIFY
  whether a conversation can be created for an unpaired peer and what the chat gate does with it (ADR-122 says a hub sender must be paired or a directory member).

### 3.2 Navigation
- `FlashDestination` {ChatList, Conversation, Transfers, NearbyDevices, Settings}. `FlashNavigationMath.TabOrder` (ui/chat/.../navigation/FlashNavigation.kt ~line 60)
  is a fixed list that must stay in sync with the host tab lists: `bottomNavTabs` (MainActivity ~681), `liveBottomNavTabs` (~1835), `navigationRailTabs`
  (~1850), desktop `DesktopSideBar.kt` (~55-58), DesktopShell `bottomNavTabs` (~2484). `transitionFor` / `isTabRoot` assume Conversation is the only
  non-tab-root. Transfers references (must all be reviewed): MainActivity ~464/475 (shortcut routing), 1081, 2834, 2930, 2946, 3068, 3090; DesktopMain ~228;
  DesktopShell ~1620, 1915, 2016, 2348. Saved navigation state (`FlashNavigationStateSaver`) drops invalid entries. **[read]**
- Chat-list top bar `FlashChatListTopBar(onSearchClick, title, onLanClick, onNewGroupClick, onJoinWithLinkClick, onBrowseOrgGroupsClick)`. **[read]**
- Icons exist: Chat, Transfer, Nearby, Settings, Group, Device, Relay, Verified, Search, Pin, Flag, Bolt, Tray, Share. **No Hub / announcement icon.** **[read]**

### 3.3 Server and protocol
- Roles in `HubRoles` (`core/hub-protocol/.../certs/HubRoles.kt`): `member`, `dept_admin`, `admin`. Directory REST roles `ROLE_ORG_ADMIN / DEPT_ADMIN / MEMBER`. **[read]**
- Invites (`AdminApi.kt` ~447-540) already take `department`, `role`, `allowedGroups`, `autoJoinGroups`, `ttlMinutes`, `maxUses`, `note`. Non-member roles require
  step-up `StepUpActions.ADMIN_CREATE`. Bulk invites: members only, CSV `name[,department]`, max 200. **[read]**
- Console logins: `admin_users.role` exists (default "ORG_ADMIN"), `createAdmin(username, password, createdBy, role)` exists, **but only ORG_ADMIN is ever used and no admin
  endpoint filters by department.** **[read]**
- Directory (`DirectoryRoutes.kt` ~104-124): `GET /api/v1/directory/members` returns only the caller's own department unless the caller is an org admin or that
  department's admin (else 403). `GET /api/v1/directory/departments` lists only the caller's own department unless admin. **[read]** Consequence **[infer]**: the
  client's `HubPeerDirectory` knows only own-department members, so a message from **another department's** employee is probably rejected as UNKNOWN. VERIFY with a test
  before relying on it; Plan A2 fixes it.
- `OrganizationDirectory.kt`: `Department(id, name, adminDeviceIds)`, `GroupCharter(id, name, departmentId, visibility LISTED|HIDDEN, joinMode OPEN_IN_SCOPE|REQUEST_APPROVAL|ADMIN_ONLY,
  adminDeviceIds, memberDeviceIds)`, `DepartmentMember`, `DirectoryActor`. Only an org admin may create/change/delete departments and org-wide groups (D-E13); a department admin may
  create groups in their own department. **[read]**
- DTOs in `core/hub-protocol/.../rest/RestDtos.kt`: `DepartmentDto(id, name, adminDeviceIds, memberCount, groupCount)`, `MemberDto(deviceId, departmentId, displayName, role, online,
  subjectPublicKey, sealingPublicKey, sealingSignature)`. Client models: `EnterpriseDirectoryMember`, `EnterpriseDepartment`, `EnterpriseDirectoryGroup` in `EnterpriseModels.kt`. **[read]**
- Route list guard: `docs/protocol-routes.txt` + `RouteTableContractTest`. Relay frames in `RelayFrame.kt`: Challenge, Hello, Welcome, Error, Send, Deliver, RecvAck, Ack,
  QueueState, Meta, Ping, Pong, Revoked, Kick. **[read]**
- Console is static JS in `server/src/main/resources/admin-ui/` (views: audit, delegation, devicepicker, devices, invites, login, organization, overview, security, settings, setup),
  strings in `js/strings/en.js`, Node tests in `server/admin-ui-tests/*.test.mjs`, doc `docs/ui/hub-admin-console.md`. **[read]**

### 3.4 LAN trust (what Plan B changes)
- LAN sessions trust only pins in `FlashTrustStore`. Pin sources `PinSource` PAIRED / VOUCHED / TOFU (`VouchRules`); `applyVouch`, `revokeVouch`, `vouchingGroups` exist. **[read]**
- The TLS layer (`TofuX509TrustManager`, duplicated in androidMain and jvmMain; read 2026-10-14) checks the leaf (or any chain cert) SPKI SHA-256 against
  `TofuPinVerifier.isPinned(deviceId, fp)`. In `TofuPinVerifier` a **known pin must match; an unknown device id is accepted and its pin is recorded
  (trust on first use)** because every host passes `recordPin` (Flash.kt:277, DiscoveryEngineHolder.kt:724, DesktopEngine.kt:1161: `trustStore.savePin`). A mismatch
  rejects and fires `onKeyChanged(leafFp)`. **Therefore an unpaired colleague can already complete a LAN TLS handshake today; what blocks them is the application
  gate (`isTrustedPeer` = paired).** Plan B consequently needs **no TLS change** — it adds an *organisation tier* to the application gate (see Plan B §3). It must
  also close the TOFU hole for organisation peers: a first-use pin recorded for a colleague's id must be compared with the fingerprint derived from that colleague's
  organisation certificate, and a mismatch must never be trusted. Nothing in `core:network` knows about the organisation. **[read]**
  VERIFY: where the pin's source (PAIRED / VOUCHED / TOFU) is stored (`AndroidPreferencesTrustStore`, `DesktopIdentityStores`) and whether `savePin` called from `recordPin`
  stores source TOFU.
- The chat repository's gate is the injected predicate `isTrustedPeer` (= paired; `trustStore.isTrusted` in hosts), used at about 17 sites in
  `RealFlashChatRepository.kt` (213, 380, 513, 582, 1938, 1977, 2079, 2481, 2857, 3093-3145, 3454, 3521, 5259, 5268, 5310, 5498). **[read]**
- Group gate seam: `GroupGate` / `RosterGroupGate(localDeviceId, members, isTrustedPeer, isVouchedMember, hasMemberKey, peerIdentityKey, swarmServingEnabled)` in
  `core/messaging/.../group/`. v2 groups (`g2-`) use group secret + proofs (GM track); legacy groups use rosters. Hub-linked groups: `EnterpriseTrustStore.linkedHubGroups`;
  group frames travel via the hub only for linked groups; group messages from unpaired hub members are rejected by the group gate (CLI-16, ADR-123); group messages through the hub are not sealed. **[read]**

### 3.5 Identity and certificates
- The LAN TLS key is the enrolment identity key (`KeystoreFlashCrypto.identityPublicKeyEncoded`; `selfSignedCertificate()` wraps it; enrolment sends it as `subjectPublicKey`). So
  `sha256(SPKI of DeviceCert.subjectPublicKey)` should equal the TLS leaf fingerprint. **[infer] VERIFY with a test on both Android and desktop** (desktop: `PersistedFlashCrypto`).
  This is the foundation of Plan B; if it is false, stop and report.
- `DeviceCert(deviceId, subjectPublicKey, role, department, validFrom, validTo, serial, issuerPublicKey, issuerSignature)`; default validity **7 days**; renewal issues a new serial.
  `checkChain(delegation, rootPublicKey, verifier, timestamp, revocations, clockSkew = 60 s, expiryGraceMs)` returns `ChainStatus`. `EnrollmentCredentials(hubUrl, rootFingerprint,
  rootPublicKey, deviceCert, delegationChain, enrolledAtMs, hubName)`. `RevocationList` v2/v3 (hashed ids, audience, `verifyTrusted`, `revokesDevice`, `supersedes`);
  client fetch/verify in `HubSession.fetchRevocations()` (`core/enterprise/.../HubSession.kt` ~341), today only used to confirm a `FLASH_REVOKED` frame. **[read]**
- `IdentityKeyCheck` REGENERATE (Android) would replace the identity key and invalidate the org certificate. **[read]**

## 4. Cross-cutting expectations (apply to every plan)

### 4.1 Error and edge situations every feature must have an answer for
For each user-visible action the implementer must be able to point to code **and a test** for: no network; hub unreachable but LAN fine; hub reachable but returns 401/403/404/409/413/429/5xx;
malformed or oversized response; slow response (cancel and timeout, no UI freeze); device clock wrong (± hours, ± days); device revoked or unenrolled mid-operation; token/cert expired mid-operation;
process killed mid-operation (restart recovers, nothing half-written); database write failure (disk full) — degrade, never crash; two devices/admins acting at once (409 or last-writer rules stated);
duplicate delivery; reordering; empty states; very large lists (5,000 members, 200 departments, 10,000 announcements); names with emoji, RTL text, 300-character names, control characters, zero-width
characters; a hub that restarts or is restored from backup (versions go backwards — detect it, do not corrupt the cache); a hub whose root key changed (never mix organisations: refuse and tell the user).

### 4.2 Security expectations
- A hub **cannot forge** trust: everything the client uses to trust a colleague must be verifiable against the pinned root key offline.
- **Fail closed** on any parse/verify error, never "accept and log".
- No trust escalation: an ORG-trusted peer is **not** a paired peer; admin, vouching and v2-group owner trust never derive from the org tier.
- Everything the hub sends is **untrusted input**: bound sizes, counts, string lengths, nesting; reject, do not truncate silently, unless the plan says to.
- Rate-limit server endpoints that take bodies (`AbuseControlsTest` shows the pattern) and client sync loops (backoff with jitter).
- Constant-time comparison for any secret/fingerprint comparison (`TofuPinVerifier.constantTimeEquals` exists).
- No new log line may contain bodies of messages/announcements, keys, tokens, passwords. Use ids and counts. Add/extend `LogHygieneTest` style checks for new server logs.
- Honest UI: whenever the hub operator can read something (announcements, unsealed group messages, direct messages without opt-in sealing), the UI says so in plain words.

### 4.3 Compatibility
Old app + new hub, new app + old hub, new app + new hub, and desktop + phone mix must all keep working at their **existing** level; new features turn on by capability. Specify and test the
degraded behaviour (for example: hub without `directory/sync` → client falls back to the old per-department fetch and Plan B shows "limited offline trust").

### 4.4 Documentation to produce per plan (checklist at the end of every plan)
`docs/protocol.md`, `docs/protocol-routes.txt`, `docs/security.md` (threat statements), `docs/decisions.md` (ADR status moves PROPOSED → ACCEPTED only with the owner), `docs/android-platform-notes.md`
(if a platform API is touched), `logs/progress.md`, `logs/handoff.md`, `docs/testing/TEST-BACKLOG.md`, `AGENTS.md` §29 line, `docs/ui/ui-research-index.md` status, the console doc `docs/ui/hub-admin-console.md`.

## 5. Ideas the owner has NOT decided (do not build; parked list)

Parked in `docs/FUTURE-OPTIMIZATION.md` as FO-19..FO-26 (IDEA, not scheduled): acknowledge-required announcements ("read and understood" with a report), scheduled announcements, announcement
attachments (rides the hub transfer path), reactions/comments on announcements, sealed announcements (end-to-end for a department), organisation chart / reporting lines, "file drop" to a department
over the hub, remote wipe of a lost company phone, presence "out of office". Each can be pulled in by the owner later; the plans leave hooks only where the plan says so (for example the announcement
table has `kind` and `flags` columns so acknowledgements can be added without a rewrite).

## 6. Open questions for the owner (the implementer must not decide these)

1. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** Plan B: default **offline grace** (the author proposes 30 days; admin-configurable 1–365). Accept or change.
2. ~~Plan B: revocation gossip between phones over the LAN.~~ **ANSWERED 2026-10-14 (D10): yes, build it (phase B5).**
3. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** ADR-135 is ACCEPTED and its review gate is cleared. Plan B: hub groups on LAN use a **hub-signed roster statement** (author's recommendation) instead of the FO-17 "secret via sealed messages". **Owner review gate before any group-trust code** (ADR-134).
4. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** A4: should announcement notifications default **on** for every employee or opt-in per department? (author proposes on for company-wide, on for own department).
5. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** A3: maximum announcement size (author proposes title 120 chars, body 4,000 chars) and retention (author proposes none by default, optional expiry per announcement).
6. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** A1: may a department head create a *second* department head? (author proposes **no**: only an org admin creates heads; heads invite employees).
7. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** A1: may a department head rename his department? (author proposes **no** in v1, D-E13 stays).
8. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** A1: may a department head revoke a leaver of his own department from the console? (author proposes **no** in v1: org admin only; revisit after the heads feature is used).
9. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** A2: may a hidden department's members be found by name by the whole organisation through Layer 2 (Plan B §13)? (author proposes **no**).
10. ~~**A2/B conflict**~~ **ANSWERED 2026-10-14 (D9): the author's recommendation was accepted.** Original text kept for history: D2 said keys stay restricted "as today", but Plan B's offline trust distributes each colleague's *identity public key* org-wide, and sealed inter-department chat needs the *sealing public key* org-wide. Author recommends: keys of non-hidden departments are org-visible, keys of hidden departments are not (Plan A2 §3.4). (Resolved: the flag ships on.)
11. **ANSWERED 2026-10-14 (D11, "all recommended"): the author's proposal was accepted.** (No: not built in v1.) A2: is a separate per-department "hide online status" switch wanted (Plan A2 §3.5)? (author: optional, default no).

## 7. Where things get recorded

- Decisions: `docs/decisions.md` ADR-133..ADR-138 (all accepted by the owner on 2026-10-14, D11; details still unbuilt).
- Tests owed: `docs/testing/TEST-BACKLOG.md` section "4zzzzzzzzz" (prefixes `ORGT-`, `DEPH-`, `PRIV-`, `ANN-`, `HUBT-`).
- Parked: `docs/FUTURE-OPTIMIZATION.md` FO-17 (absorbed by Plan B), FO-18 (multi-org), FO-19..FO-26.
- Progress and handoff: `logs/progress.md`, `logs/handoff.md`.
