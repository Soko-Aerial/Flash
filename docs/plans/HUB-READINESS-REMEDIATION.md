# Flash Hub rollout remediation

Opened 2026-11-07 from the [Hub feature and rollout audit](../audit/2026-11-07-hub-feature-readiness.md). The owner requested implementation in phases. Use the [Hub quality contract](HUB-QUALITY-CONTRACT.md) and the repository build lock. An automated pass advances implementation. Device and operator acceptance are tracked independently.

## Order and exit criteria

| Phase | Scope | Acceptance gate | Status |
|---|---|---|---|
| HR1 | Department-head server visibility. Apply scope and invite state before SQL pagination. Fix announcement search for one, many and zero assigned departments and correct scoped overview counts. | Route tests with newer outsiders ahead of in-scope rows, a second page, explicit filters, search and empty scope. Scope-removal mutation must fail the regression. HUBREADY-01 browser pass follows HR4. | Implemented, 79 tests green |
| HR2 | Restore safety. Bind a data-key verifier to the database, refuse missing or mismatched keys on sealed stores, and support first use and upgrades from legacy sealed or unsealed data. Update the restore runbook. | Disposable Hub restarts using matching, missing and wrong keys. Legacy and first-run tests. HUBREADY-02 covers operator restore. | Implemented, 46 tests green |
| HR3 | Enforce live membership for unread group mailbox deliveries. Define sender terminal outcome and remove inaccessible rows so they cannot reappear on rejoin. Preserve direct mailbox and delivery already handed to a recipient. | E2E removal while offline, reconnect, rejoin, normal replay and file-read comparison. HUBREADY-03. | Implemented, 103 tests green |
| HR4 | Complete scoped console. Role-aware navigation and actions, head account creation and reassignment, root-signed revocation import with step-up, Overview audit status, join approval confirmation, and stable announcement browsing across pages. | Admin/head Node and browser paths. APIs remain authoritative. DEPH-01..07, HUBR-03 and CONSOLE-D06/D07. | Implemented, isolated Chrome fixture passed |
| HR5 | Offline LAN revocation. Implement D10 with signed, monotonic, size/rate-limited exchange. Persist the newest verified list per organisation, invalidate trust and roster checks immediately and restore Android library parity where the API exposes Hub behaviour. | Malformed, stale, tampered, wrong-root, concurrent and offline-restart tests, then HUBREADY-04 on three devices. | Implemented, 1,035 tests green |
| HR6 | Device replacement. Revoke the lost device and its sessions, issue a one-use replacement path with fresh keys, scope the new device's department/groups explicitly and audit the result. Keep old enrolment and key revocations enforceable. | Lost key, stolen old key, retry/concurrent redemption, quota, replacement on both hosts and ORGT-14. | Implemented (12 `DeviceReplacementTest` + 4 `devices-replace.test.mjs` green; physical `HR6-DEV-01 / ORGT-14` owed) |
| HR7 | Title-only announcement notifications and opt-out on Android and desktop. Cover reconnect catch-up, first sync, duplicate suppression and cold start. Wire the group relay confidentiality notice. | ANN-01/10/11 and HUBT-12, permission denial, author suppression, mute and desktop activation. Measure background delivery separately from the local notification feature. | Implemented (host & unit tests green; physical `HR7-DEV-01` owed) |
| HR8 | LAN ↔ Hub file continuity. Specify a stable transfer identity, representation of validated completed bytes, ownership of the active route and final verification for both directions. | Disconnect or route switch during a large transfer on both hosts. Resume only compatible bytes, one completion row and one copy, with final integrity verification. | Implemented & wired across hosts (`TransferContinuityTest` + `CrossRouteWiringIntegrationTest` green; physical `HR8-DEV-01` owed) |
| HR9 | Off-LAN calling. Design authenticated, short-lived signalling with media path negotiation and an explicit NAT relay policy. Align deployment claims with the shipped capability. | Two independent networks, direct and relayed media, late/duplicate/replayed signalling, device removal and process/background behaviour. | Implemented (`CallSignalingAndIceConfigTest` + `OffLanCallSignalingTest` green; 2-network `HR9-DEV-01/02` owed) |
| HR10 | End-to-end group relay encryption (FO-27). Design authenticated membership epochs, secure key delivery, rotation/removal, history visibility and mixed-version handling before changing the wire. | Hub cannot decrypt payloads. Removed devices cannot read new messages. Rejoins, out-of-order events, epoch rollover and older clients are exercised. | Implemented (`GroupRelaySealingTest` + `HubGroupE2eeTest` + `GroupE2eeRelayOpacityTest` green; physical `HR10-DEV-01` owed) |
| HR11 | Operations. Expose actionable alerts for unreadable queues, oldest mail, disk use and certificate/delegation expiry. Rehearse restore and signer rotation, then run the real pilot matrix and fleet-size soak. | Isolated production-profile runbook, PERF-H03 and the relevant HUBREADY/HUBWORK/CONSOLE checks. | Automated drills & runbook implemented (`OperationsAndRolloutDrillsTest` green; live VPS/soak `HR11-ENV-01/02` owed) |

## Working decisions

- HR3 uses live group membership as the delivery authority for data still held by the Hub, consistent with existing file downloads. Messages already delivered cannot be recalled. Verify the race between membership mutation and mailbox hand-off as part of the phase.
- HR4 requires an explicit confirmation for join approval. A universal phone signer or a new mandatory co-sign action needs a separate privilege and compatibility design. The existing high-risk co-signing policy remains the baseline for this plan.
- HR8–HR10 are protocol phases. Each needs its own threat model and compatibility record before a wire change. Keep internet calling deployment infrastructure and externally managed notification wake-up behind explicit service choices. Work in isolated environments. This plan does not authorize changes to the live Hub or device data.

## Implementation sequence

1. **HR5** adds durable signed-list acceptance before LAN framing. Route both Hub fetch and LAN receipts through the same verifier and monotonic store. Advertise the new capability only on hosts with the full receiver and sender. Apply learned revocations to directory peers and cached rosters before enabling the new capability.
2. **HR6** builds a replacement ceremony around existing device revocation and one-use invites. Record the old and new bindings for audit and keep enrolment proof of possession. Validate each inherited department/group assignment and require explicit handling of administrator or approver links.
3. **HR7** emits a local notification only after an eligible announcement is stored. Reuse the existing route consumer and host notification managers. Keep the wake-up and delivery objective separate. Add the required disclosure to group chat header/settings before changing group encryption.
4. **HR8** starts with interoperability tests for resumable bytes. Confirm which direct chunks can be trusted on the sealed Hub path and who owns retry, progress, cancellation and destination publication across route changes. Introduce handover only after both engines agree on source identity and final integrity.
5. **HR9** adds expiring authenticated signalling before enabling the off-LAN Call action. Select a deployment-compatible media policy, test candidate negotiation and fallback on two networks, then validate Android permission and background behaviour on supported devices.
6. **HR10** defines a membership-bound group key epoch and a downgrade policy. Test rotation and out-of-order delivery before moving any UI claim to end-to-end encryption. Version any new wire formats so older clients receive an honest compatibility state.
7. **HR11** supplies monitoring rules and an operator drill checklist against the actual deployment profile. Capture queue age, quarantine growth, disk and certificate expiry, then prove recovery and capacity on representative hardware.

## Implementation log

### HR1 explain-back — 2026-11-07

Lists must describe the authorised slice before they paginate. The current console can look empty because newer rows from unrelated departments occupy its first page. The announcement store currently only accepts one caller department, so a multi-department head's permitted posts are invisible. Correct the underlying queries before adding console controls.

Touch `IdentityStore`, `AdminApi`, `AnnouncementStore` and `AnnouncementAdminRoutes`. Reuse the current prepared statement helpers, `ConsoleScope`, stable device/invite ordering, and the announcement store's parameterised FTS and LIKE paths. No new runtime class. Add tests to `DepartmentHeadScopeTest` and `AnnouncementAdminRoutesTest` using the existing authenticated fixtures. First run them against the old code to reproduce the failures.

Watch for a null scope being confused with an empty one, a department filter widening head access, stale scope persisting after an admin edit, an invite state changing at the expiry boundary, and FTS and LIKE disagreeing about caller visibility. Preserve mobile announcement semantics and the existing response shape. Browser usability and announcement cursor changes follow in HR4.

### HR1 result — 2026-11-07

| Change | Evidence | Mutation |
|---|---|---|
| `IdentityStore.listDevices`, `listInvites`, `countInvitesByState`, identity migration 3 and the `AdminApi` callers | `DepartmentHeadScopeTest.departmentAndStateFiltersApplyBeforePagination` was red before the change. It now checks ordered pages, search, explicit and empty scope, invite state precedence, exact expiry and scoped overview. | Passing null for the route's device/invite department scope returned the newer foreign `dev-c1`, which fails the first scoped device assertion. Restored the scope. |
| `AnnouncementAdminRoutes` and `AnnouncementStore.searchAnnouncements` | `AnnouncementAdminRoutesTest.multipleDepartmentHeadListsAndSearchesOnlyAssignedDepartments` was red before the change. It now checks pages, FTS search, explicit department, foreign department, org-only request, empty scope and org admin control. | Removing the SQL department predicate returned the newer foreign `dept-c`. The regression failed. Restored the predicate. |

Locked Gradle `:server:test` filters `DepartmentHeadScopeTest`, `AnnouncementAdminRoutesTest`, `AnnouncementRoutesTest`, `AnnouncementStoreTest`, `AdminConsoleApiTest`, `RevocationAndEnrolmentTest` and `MigratorTest`. `BUILD SUCCESSFUL in 17s`. JUnit XML totals **79 tests, 0 failures, 0 errors, 0 skips**.

Review notes. (1) Scope is read per request, so it is recalculated after edits. (2) Null and empty sets differ, and empty returns zero rows. (3) The explicit department filter can only narrow the SQL scope. (4) The same visibility predicate is used by the FTS and LIKE paths. (5) Device and invite pages keep their existing ID tie-breaker. (6) Invite filtering and DTO state use the same captured request clock. (7) Revocation precedes use and expiry, and equality at expiry remains pending. (8) New department indexes support scoped lookups, and cross-department order can still require a sort. (9) No route/DTO or platform changes. (10) Announcement timestamp-only continuation and browser role affordances remain HR4 work. The upgrade adds indexes only. Back up before switching binaries because older versions reject a newer schema. Physical and browser acceptance remain HUBREADY-01.

### HR2 explain-back — 2026-11-07

Startup must refuse to serve a restored database whose sealed data needs a missing or different key. Bind the key and database with an authenticated verifier in existing `hub_meta` before opening HTTP or changing sealed data. A fresh database may create a key. For a legacy database without a verifier, authenticate an existing sealed row before binding its key. If the legacy store contains only unsealed values, its existing first-run migration can still generate a key and seal them. Keep independent per-row quarantine for isolated corruption after the key has been established.

`HubDataKey` will coordinate the preflight. `SecretBox` provides the existing AES-GCM format, `HubKeyStore` and `SecureFiles` provide owner-only key handling, and `IdentityStore.putMetaIfAbsent` and `ServerDatabase.immediate` provide the model for a single serialized binding. Reuse the mailbox and admin AAD rules. Wire it into `HubServices` and `admin-recovery` so both paths protect the same data. Write the startup regressions first in `HubServicesTest`, and exercise the CLI's existing recovery and mailbox E2E tests.

Watch for a missing key being minted on legacy ciphertext, a wrong but well-formed key getting accepted, a database verifier surviving an empty queue, two bootstraps replacing one another's key, and row corruption being mistaken for key loss. Return a restore-specific startup error with no key or sealed bytes. An interrupted first boot may leave a valid unbound key file, which a retry must reuse. Use no new schema for the binding, and document how to restore a matching pair.

### HR2 result — 2026-11-07

| Change | Evidence | Negative control |
|---|---|---|
| `HubDataKey.loadHubDataKey`, `HubServices`, `SecretBox`, admin and mailbox AAD reuse | `HubServicesTest.aBoundDatabaseRefusesAMissingOrDifferentDataKeyAndCanRecoverWithItsOriginal`, `legacySealedMailboxAuthenticatesItsKeyBeforeBinding`, and `aLegacyUnsealedDatabaseCanCreateAKeyAndRetainItsAuthenticator`. | Ran the three tests with the original unconditional key loader. Both missing-key cases started successfully and the unsealed legacy case had no verifier, so all three failed. The updated tests also replace the key with a different valid-format key and verify that the matching key can restore data. |
| `Main.adminRecovery` and runbook | `MainCommandsTest`, `DataAtRestTest`, `SealedMailboxPayloadTest`, `E2eRestartContractTest` and `AdminConsoleWebTest` passed alongside the new startup tests. | Existing admin reset, per-row quarantine and server restart are covered by the same gate. |

Locked Gradle `:server:test` filters `HubServicesTest`, `DataAtRestTest`, `SealedMailboxPayloadTest`, `MainCommandsTest`, `E2eRestartContractTest`, and `AdminConsoleWebTest`. `BUILD SUCCESSFUL in 54s`. JUnit XML totals **46 tests, 0 failures, 0 errors, 0 skips**.

Review notes. (1) Preflight runs before the admin/mailbox writers or HTTP. (2) Bound stores cannot generate replacement keys. (3) A wrong format-valid key fails AES-GCM authentication. (4) A plaintext verifier is rejected even if its text matches. (5) A pre-sealing schema can still initialise. (6) Legacy sealed rows use the same AAD as the live stores. (7) Samples are capped at sixteen per table, so an unusually corrupt legacy store may require an operator restore even with the right key. (8) A verified key does not certify every row, and mailbox quarantine remains active. (9) An immediate transaction serialises verifier creation for the database. An interrupted first boot can reuse the key file. (10) Errors omit key material and CLI recovery uses the same binding. There is no client wire or platform change. The isolated operator restore remains HUBREADY-02.

### HR3 explain-back — 2026-11-07

Removal must take away access to Hub-held group data while preserving direct mailbox delivery. Retire group rows at the same database write that removes directory membership, including bulk deletion. Guard new group mailbox inserts in their existing transaction. Check a selected group row again before authorising its relay hand-off, so a page fetched before removal cannot bypass the gate. A successful authorization is the hand-off boundary. Existing non-durable frames already accepted by the relay retain their send-time semantics.

Reuse `OrganizationDirectory`'s membership relation, the mailbox's partial deliverable index pattern and existing `DROPPED` queue outcome. Add a cross-component mailbox migration after both stores exist, with a scoped index and a reason distinct from bad ciphertext. Run normal replay, offline removal, remove-then-rejoin, and retirement-notice checks in `E2eGroupAndLimitsContractTest`, `RelayFrameRouterTest` and `MailboxQueueTest` where appropriate. Guard against duplicate IDs, racing writes, a selected but invalidated row, a former member still receiving direct messages, and a rejoin making old queued bytes visible.

### HR3 result — 2026-11-07

| Change | Evidence | Mutation |
|---|---|---|
| `MailboxSchema.migrateGroupAuthorization`, `MailboxQueue.insert` and `mayReplay` | `MailboxQueueTest.groupMembershipInvalidatesPrefetchedRowsAndRefusesLateQueueWrites` covers a legacy orphan, a prefetched row, late enqueue and false ACK after removal, and removal followed by rejoin. | Disabled the trigger's retirement predicate. The already fetched row passed `mayReplay` after removal and the test failed. Restored the trigger. |
| `RelayFrameRouter.drainPages`, `FlashServerEngine` sweep callback and group retirement reason | `E2eGroupAndLimitsContractTest.removingAGroupMemberRetiresQueuedGroupCopiesEvenIfTheyRejoinBeforeDrain` checks group payload suppression, direct delivery, a per-recipient `DROPPED` event and a new post-rejoin delivery. | The old code and the disabled-trigger mutation both replayed the old message. Restored the trigger. |

Locked Gradle `:server:test` filters `E2eGroupAndLimitsContractTest`, `E2eHubFileTransferContractTest`, `E2eRestartContractTest`, `RelayFrameRouterTest`, `MailboxQueueTest`, `MailboxQuarantineTest` and `OrganizationDirectoryTest`. `BUILD SUCCESSFUL in 55s`. JUnit XML totals **103 tests, 0 failures, 0 errors, 0 skips**. The first compile found the sweep row's old Boolean type after switching to a reason string. Corrected the type before the green gate.

Review notes. (1) Directory deletion and retirement share a SQLite transaction. (2) Group deletion follows the same member-delete trigger. (3) A rejoin inserts a membership and cannot revive retired rows. (4) A preselected row is looked up by its exact sequence. (5) A rejected row advances the cursor so direct mail behind it can drain. (6) Retired rows cannot be acknowledged as delivered. (7) The enqueue check shares the immediate write transaction, so an absent membership denies late writes. (8) A partial group/target index supports trigger lookups. (9) Retirement and corrupt data use distinct reasons and metrics. (10) The existing wire reports per-recipient `DROPPED` to a connected sender, and disconnected senders have the existing transient notification limitation. Prior authorisation defines the hand-off boundary. Legacy rows whose former member already rejoined before upgrading cannot be identified from the old schema. Physical acceptance remains HUBREADY-03.

### HR4 explain-back — 2026-11-07

Start by making announcement continuation stable in the server. Then wire the console in three slices. First add current-session scope to the route, rail and actions. Second reuse the Security form and step-up controls for head provisioning, re-scoping and revocation-list import. Third complete join confirmation, Overview audit state and announcement paging. Keep the approved console design, hierarchy, form primitives, focus behaviour and reduced-motion settings. The available actions and the data they govern are the visual changes.

For server continuation, keep the response shape and legacy timestamp-only callers. Bind the ID of the last returned item as the secondary cursor key. Reuse `AnnouncementStore.searchAnnouncements` for recent, FTS and LIKE lists, and `AnnouncementAdminRoutesTest` for a same-timestamp page boundary test. For the UI, reuse `delegation.js`'s signed-JSON import, `security.js`'s one-time credentials and `organization.js`'s existing rejection dialog. Keep the server's existing authorisation and step-up actions as the source of truth.

Check role changes on session refresh, unknown roles, deeplinks into forbidden tabs, empty department sets, duplicate submits, revocation version/type/size errors, expired challenges, FTS and LIKE continuation, and a query change while an older page is still loading. Verify existing routes with Node and real browser sessions at narrow and wide widths. New dialogs reuse existing transitions. Join approval requires explicit confirmation with device/group context.

### HR4 result — 2026-11-07

| Change | Evidence |
|---|---|
| Role-aware shell, route guard, Overview, device actions, organisation, invites and Security | Chrome fixture signed in as a head on two departments at 1440, 768 and 390 px. A direct Audit link returned to Overview. No Audit, Settings or metrics request was sent. Security exposed Account and Sessions only. No viewport overflow or unhandled browser exception. |
| Head creation/reassignment, signed revocation import and join confirmation | Chrome fixture exercised creation with a real browser confirmation key and a synthetic step-up endpoint, one-time credentials, scoped reassignment, malformed JSON rejection, `revocation.install` with `X-Step-Up`, and the absence of a join POST before confirmation. The fixture proves browser wiring, while the server's scope and cryptographic routes have their own tests. |
| Announcement browsing and Overview verification | `AnnouncementAdminRoutesTest.compositeCursorContinuesAcrossPostsWithTheSameCreationTime` covers list and search through tied timestamps, end of results and a missing timestamp. Chrome fixture verified the browser sends `beforeId`, appends older items and reports the displayed count. Overview read and displayed the audit verdict. |

The first Chrome pass found that the approval dialog referenced a group-name helper scoped to list rendering. Shared the existing helper within the view and reran the flow. Node tests **83/83**. The final locked Gradle `:server:test` gate filtered `DepartmentHeadScopeTest`, `AnnouncementAdminRoutesTest`, `AnnouncementRoutesTest`, `AnnouncementStoreTest`, `AdminConsoleApiTest`, `AdminConsoleWebTest`, `HubServicesTest`, `E2eGroupAndLimitsContractTest`, and `MailboxQueueTest`. `BUILD SUCCESSFUL in 45s`. JUnit XML totals **118 tests, 0 failures, 0 errors, 0 skips**.

Browser captures were inspected using the approved console's spacing, contrast and focus patterns. No new motion was added. The review stayed in this chat at the owner's request, so the Impeccable pass used a single context. The source detector flagged four existing accent borders and could not resolve the server-mounted stylesheet URLs in its file scan. Chrome loaded those styles. The focused changes preserve the approved shell.

Review notes. (1) The server remains the access authority. (2) Navigation and actions derive from the current session, and a scope change re-mounts the view. (3) The device name cache is keyed by scope. (4) Missing department access hides actions that would fail. (5) Head account edits reload the current assignments. (6) Import size and shape are checked before step-up, and the Hub verifies the root signature and version. (7) Join confirmation identifies the device and group, and uses existing server approval semantics. (8) A delayed page cannot overwrite newer search results. (9) Equal timestamps have a bound ID tie-breaker. (10) The Overview states how many audit entries were checked on this visit. HUBREADY-05 remains owed with a real Hub; no phone co-signing was added to join approval.

### HR5 handoff

Start with [Plan B §12](HUB-OFFLINE-ORG-TRUST-PLAN.md#12-revocation-gossip-phase-b5-decided-yes). `HubSession.fetchRevocations` already verifies against the enrolled organisation and delegation. `HubRevocationCache` currently holds only in-memory state. The signed-roster exchange uses a separate capability and payload, so use it as a model and keep its on-wire contract independent. Before wiring Android and desktop, define durable monotonic acceptance, revocation authentication after reconnect, and the precedence of revocation over cached organisation trust and stored group rosters. Preserve the existing manual pairing policy. HUBREADY-04 is the device gate.

### HR5 explain-back — 2026-11-07

Hub and LAN updates will share one enrolled-root verifier and one durable version floor. Restore it before loading the offline directory, and consult it at the existing ORG and roster gates so the next decision sees a revocation without a successful HTTP sync. Manual trust pins keep their existing policy.

- Extend `HubRevocationCache` with verified disk restore, serialized acceptance, root scope, and a bounded read/write using the same app-private roots as Hub file and trust state. Reuse `HubSession`'s local delegation trust, `HubFileDisk`'s atomic write, and `OrgSnapshotCoordinator`'s boot sequence.
- Add `HubRevocationGossip` beside the cache. Reuse `FlashTextFraming` for bounded fields, `HubGroupRosters` for signed offline verification conventions, and the roster exchange in `RealFlashChatRepository` for capability gating. Send only on the currently authenticated TLS session after comparing its actual key to the ORG directory binding.
- Wire `DiscoveryEngineHolder`, `DesktopEngine`, and SDK `Flash.kt` at their LAN session collectors. Update `OrgPeerResolver` and `HubGroupRosters` without changing trust pins, the lease clock, or Room schema.
- Exercise stale and conflicting versions, a revoked delegation trying to overwrite a root decision, a scope change during a fetch, restart with saved or unreadable state, and bursts of malformed, unsolicited or cross-root LAN traffic. The transport's post-handshake cap is 4 MiB on both hosts, so the 64 KiB signed payload fits in one Base64 text message.
- Start with signed-cache and loopback exchange regressions, including higher tampered lists, persistent restart, unavailable writes, sender/request binding, capability and live-key mismatch, and rate limiting after reconnect.

### HR5 result — 2026-11-07

The Android app, Android SDK and desktop now exchange signed revocation lists with same-root, directory-bound TLS peers. The durable list overlays cached directory and roster decisions. The repository keeps its existing direct paired-trust policy. On the product paths, the Hub and LAN both call the same cache acceptance gate.

| Area | Evidence |
|---|---|
| Signature, version, scope and restart | `HubRevocationCacheTest` covers stale and equal versions, tampering, wrong signer and audience, expired delegates, permanent root cutoffs, corrupt or unavailable storage, restart and an in-flight Hub fetch during a LAN update and scope change. |
| Wire bounds, peer binding and old-client compatibility | `HubRevocationGossipTest` runs a 5,000-serial signed exchange through offer, request, acceptance and a next-hop offer after restart. It covers wrong root, a missing capability, unauthenticated and unsolicited frames, oversize, reconnect and 1,000 malformed inputs. `OrgPeerResolverTest.learnedRevocationDisablesOrgPathsWithoutTouchingManualTrust` verifies the snapshot key, lease and root gate. |
| Roster decisions | `OrgDirectorySyncTest.cachedRosterStopsGrantingAccessAfterDeviceOrIssuerRevocation` checks a stored roster before and after ID/issuer revocation and after reloading it. `RealFlashChatRepositoryColleagueTest.HR5_linkedGroupRevocationCannotBeBypassedByPairing` proves paired direct access and linked-group denial in the same fixture. |
| Physical and operational acceptance | HUBREADY-04 remains owed on Android and desktop. No real Hub or phone was operated. Source inspection verified both WebSocket hosts advertise the capability and route it after TLS and before normal inbound dispatch. The SDK uses existing persisted enrolment and directory data. |

**Adversarial review**

1. `HubRevocationCache.accept` serializes disk writes. An HTTP request started before an update can neither overwrite a newer LAN version nor cross enrolment roots. The fetch race test exercises both paths.
2. `HubRevocationCache.accept` rejects equal versions, including a different signed body at the same version. An omitted known delegation cutoff is also refused on a higher version, even when the replacement is root-signed.
3. `HubRevocationCache.loadLocked` verifies saved bytes on restart. A corrupt or unreadable state file suspends ORG access and can be investigated through the storage probe. Missing state on a fresh install permits the normal existing directory lease.
4. `HubRevocationCache.accept` flushes and atomically replaces the signed file before publishing the in-memory state. A failing disk write does not change the visible version.
5. `HubRevocationGossip.receive` bounds encoded text before parsing and the decoded list before JSON, depth and signature checks. A negotiated peer can exhaust its own window with an invalid response, with no effect on the saved version.
6. `HubRevocationGossip.window` uses monotonic elapsed time and an opaque session token. Reconnects do not reset the ten-minute budget or reuse outstanding requests. The map is bounded at 5,000 peer/root windows.
7. `HubLanRevocations.bindLanSession` uses the active post-handshake TLS key and the cached statement key. A HELLO claiming an ID without its TLS key, an unnegotiated client, and a superseded socket cannot gossip.
8. `RealOrgPeerResolver.authenticatesRevocationPeer` permits the restricted revocation frame family after a known cutoff so it can propagate a revoked delegation. ORG permissions still use the revocation overlay and the original lease.
9. `HubGroupRosters.current` identifies the signer of the stored roster and evaluates the latest list at read time. Cached signed rows retain their signatures and group marker. A new version also filters revoked device IDs from roster membership.
10. `RealFlashChatRepository.isRevokedForHubGroup` checks linked-group revocation before paired or vouched fallback and before delayed Hub fallback. The direct pair is unaffected. The Hub inbound sink keeps a queued item retryable if local revocation state is unavailable.
11. The remote directory statement serial is independent of the device certificate serial. Using the statement serial for a certificate revocation could deny the wrong peer. Serial-only revocation remains enforced by the Hub and for the local enrolled certificate.
12. Probes record only decision codes, operations and versions. Wire messages and signed list contents are omitted from logs.

**Scope notes.** The existing Hub list cap is 64 KiB. Larger lists need a separately designed format. Devices without an eligible LAN session depend on their next successful Hub sync and the existing lease. Older apps remain on their prior trust behavior. Direct pairing still covers any pairing previously promoted from an organisation contact because the existing trust store treats it as a local pair. SDK support reuses any persisted enrolment and directory state and does not add a new enrolment interface.

| Quality contract | Coverage |
|---|---|
| Failure paths | HTTP failure retains the old version. Disk and malformed-input outcomes are tested. Hub inbound retries when the local floor is unavailable. |
| Hostile input | Root/signature/scope/expiry, stale and unsolicited bodies, size, encoding and randomized text are covered. |
| Concurrency | The state transition and storage use one mutex. Virtual-time and gated-fetch tests cover ordering. |
| Lifecycle | Signed state restores without Hub access or a Room migration. No background wake job was added. Physical restart is owed. |
| Scale | 5,000 synthetic serials tested. The rate map and maximum JSON size are bounded. No throughput claim. |
| Security | Signature, version, live key and linked-group guards have positive and negative checks. |
| Compatibility | Legacy clients do not negotiate the extension. Existing Hub REST and Room schema stay compatible. |
| Both hosts | Shared enterprise code plus Android app/SDK and desktop bindings. |
| Accessibility | No UI surface added. Existing lease/refusal UX remains the point of user feedback. |
| Observability | Applied-version, LAN decision and storage evidence probes documented. |
| Documentation | ADR-359, protocol, security, Android notes, operator guidance and backlog. The generated REST route list has no change. |
| Admin and support | Runbook lists the probes, lease/capability checks and recovery for an unavailable version floor. |

**Additional cases covered.** Same-version conflicting signatures, a wrong audience signed by the correct root, restart after the signing delegation expires, a root-signed successor that omits a known delegation cutoff, a regular file where the cache directory should be, and reconnect during an occupied rate window. The storage and exchange tests exercise these independently of the Hub.

**Mutation gate.** Temporarily removed the strict version, enrolled-signature and linked-group revocation gates. The cache accepted an equal version and an update from an expired delegation, and the paired-group regression failed. Both source files were restored byte for byte. The restored gate below is the verification authority.

**Console hook triage.** The two `side-tab` reports in `redesign.css` were inherited from the approved banner and toast severity design. The shared Impeccable config skips that rule only for this stylesheet. Both reports cleared on rescan with the existing CSS. All other rules still apply.

**Restored verification.** Locked Gradle ran `:core:enterprise:jvmTest`, `:core:enterprise:testAndroidHostTest`, `:core:hub-protocol:jvmTest`, `:core:messaging:jvmTest`, `:core:messaging:testAndroidHostTest` (colleague and Hub bridge filters), `:app:assembleDebug` and `:desktop:compileKotlinJvm`. `BUILD SUCCESSFUL in 1m 43s`. JUnit totals were **1,035 tests, 0 failures, 0 errors, 0 skips** (282 + 171 + 126 + 384 + 72). The earlier full Android-host messaging suite passed all 694 tests. The full JVM suite first exposed a ticket/status fixture race, then passed with the existing bounded wait for the message status. See ERROR-1001..1003.

### HR6 handoff

Start with the enrollment transaction in `IdentityStore.enroll`, the certificate and invite handling in `EnrollmentService`, and the bearer/session and directory effects of `SqliteRevocationService.revokeDevice`. Use HR5's device-ID cutoff when defining identity replacement. A new certificate serial under the same old device ID and key would leave the LAN identity ambiguous. A replacement needs a fresh identity, a one-use scoped enrollment grant, an explicit old-device cutoff, and an operator choice for department and groups. Pin the audit and failure-recovery story before adding the console action. Exercise the regenerated-key case in ORGT-14 and test old-device auth, concurrent redemption, replay, scope, and quota.

### HR6 explain-back — 2026-11-13

Device replacement must allow an authorized operator to select a lost, stolen, broken, or regenerated device and issue a scoped, expiring, one-use replacement grant that revokes the old device binding, active sessions, and directory presence immediately while enrolling a fresh device identity and keypair with proof of possession. A new certificate serial under the same old device ID and key would leave the LAN identity ambiguous, so replacement creates a fresh identity with an explicit old-device cutoff. The revocation must persist across restart and offline LAN trust synchronization (HR5). The replacement requires an explicit authorized choice of department and groups within the operator's scope, enforces quotas at the point of change, handles administrator and approver bindings with step-up and privilege rules, and records old and new bindings in the audit log without recording credentials or tokens.

Files touched:
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt`: `POST /devices/{id}/replace` endpoint handling scope, step-up, old-device revocation, one-use replacement invite generation, and audit logging.
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminApiDtos.kt`: `DeviceReplaceBody`, `DeviceReplaceResultDto`.
- `server/src/main/kotlin/com/transfer/flash/server/admin/StepUpAuth.kt`: `StepUpActions.REPLACE_DEVICE = "device.replace"`.
- `server/src/main/kotlin/com/transfer/flash/server/admin/AdminAuthService.kt`: helper to unbind revoked approver/signer devices.
- `server/src/main/resources/admin-ui/js/views/devices.js`: Replace device button in drawer, replacement dialog with department/groups selection and link presentation.
- `server/src/main/resources/admin-ui/js/i18n/en.js`: replacement strings.
- `server/src/test/kotlin/com/transfer/flash/server/admin/DeviceReplacementTest.kt`: new comprehensive test suite.
- `docs/plans/HUB-READINESS-REMEDIATION.md`: HR6 explain-back, log, and results.

Three most similar existing classes read:
1. `AdminApi.kt` (lines 483-510: `revoke`/`unrevoke` routes and lines 537-574: invite creation routes): Reused `stepUpOk`, `adminSession`, `scopeOf`, `d.revocations.revokeDevice`, `d.bearerTokens?.revokeDevice`, and `createInvite`. Deliberately combined atomic revocation of old device with generation of single-use scoped invite and audit recording in one authorized transaction.
2. `SqliteRevocationService.kt` / `RevocationAndEnrolmentTest.kt`: Reused existing device revocation mechanisms, ensuring durable monotonic version increment and LAN gossip eligibility.
3. `EnrollmentService.kt` / `IdentityStore.kt`: Reused ECDSA proof-of-possession verification (`HubAuth.enrollProofBytes`), invite state validation, and atomic registration.

Five failure modes:
1. Races: Concurrent redemption of the replacement invite or racing requests. (Guarded by SQLite immediate transaction in `IdentityStore.enroll` and single-use `maxUses = 1`).
2. Wire compatibility: Replacement invite URL uses standard `flash://enroll` schema so Android, desktop, and CLI clients enroll without wire breaks.
3. Security & Privilege Escalation: A department head attempting to replace an admin device or assign groups/departments outside their scope. (Guarded by `ConsoleScope` validation and requiring org-admin for admin/approver device replacement).
4. Quota exhaustion: When fleet is at maximum capacity, replacing a device revokes the old device first, freeing quota; edge case where fleet was already over-quota is rejected cleanly.
5. Old device retention: Old device certificate or bearer token reused after replacement. (Guarded by immediate bearer token deletion, directory removal, and `hub_revocations` cutoffs checked on every handshake and LAN gossip).

Tests to write first:
`DeviceReplacementTest.kt` covering:
1. Happy path: operator replaces device -> old revoked, new device enrolls with fresh key and inherits department/groups.
2. Refusal of old device credentials and bearer tokens after replacement.
3. Department head cannot replace out-of-scope device or assign out-of-scope department.
4. Non-org admin cannot replace admin device.
5. Expired and replayed replacement invite rejected.
6. Concurrent redemption of replacement invite allows only one winner.
7. Quota enforcement on replacement when over-quota.
8. Audit log contains old and new device IDs and no secrets.

### Phase HR6 report — 2026-11-13

- **Done:**
  - Server route & DTOs: `POST /admin/api/v1/devices/{id}/replace` (`AdminApi.kt:511-620`, `AdminApiDtos.kt:173-187`), `StepUpActions.REPLACE_DEVICE` (`StepUpAuth.kt:27,37`), `AdminAuthService.clearLinkedDevice` (`AdminAuthService.kt:652`), `EnrollmentService.remainingQuotaAssumingRevoked` (`EnrollmentService.kt:136`).
  - Console UI & tests: `server/src/main/resources/admin-ui/js/views/devices.js` (`openReplaceDialog`, `filterReplaceGroups`, `buildReplacePayload`, `resDlg.close(true)`), `server/src/main/resources/admin-ui/js/strings/en.js`, `server/admin-ui-tests/devices-replace.test.mjs` (4 Node tests).
  - Server & client enrollment tests: `server/src/test/kotlin/com/transfer/flash/server/admin/DeviceReplacementTest.kt` (12 tests covering happy path, stolen credentials, dept head scope, admin/approver/linked device protection, single-use replay, expiry, concurrent redemption race, active-seat & over-quota checks, and real `EnterpriseEnrollmentClient` PoP replacement).
- **Done but only unit/server-tested (`HR6-DEV-01 / ORGT-14` added to `docs/testing/TEST-BACKLOG.md`):** End-to-end replacement of a physical Android phone + Windows Desktop against a live Hub and offline 3-device LAN revocation gossip verification.
- **Not done, and why:** Physical phone/desktop live Hub run deferred (no physical devices attached in host workspace).
- **Surprises:** (1) Earlier Console replacement dialog passed `groups` unfiltered across departments, skipped department-head group restrictions, used the wrong modal close handle, and omitted active `linkedDeviceId` clearing on replacement — all fixed and unit-tested in `1c664631`; (2) When fleet was over quota (`activeDevices >= deviceQuota` + already exceeded), naive quota check needed `remainingQuotaAssumingRevoked(oldDevice.status == DeviceStatus.ACTIVE)` so normal 1-for-1 full-fleet replacement succeeds while an already over-quota replacement is refused (`409 ERR_QUOTA_EXCEEDED`); (3) Replacing a department-head's bound `linkedDeviceId` required org-admin authority just like `approverDeviceId` / `enrolledDeviceId`.
- **Decisions:** Mint a fresh device identity + one-use scoped enrollment invite while atomically revoking the old device/bearer/directory entry and clearing admin bindings rather than re-issuing a cert under the lost device ID (which would leave LAN trust ambiguous).
- **Open risks:** Offline LAN peers that have not yet synced a Hub revocation list or exchanged HR5 LAN revocation gossip remain governed by cached `offlineGraceDays` leases until reconnect (`HR6-DEV-01 / ORGT-14`).

| Step | Files changed (`path:line`) | Tests | Mutation | Commands & Result |
|---|---|---|---|---|
| HR6-server | `AdminApi.kt:511-620`, `AdminApiDtos.kt:173-187`, `StepUpAuth.kt:27,37`, `AdminAuthService.kt:652`, `EnrollmentService.kt:136` | `DeviceReplacementTest` (12 tests) | Bypassing `if (scope !is ConsoleScope.Org)` guard at `AdminApi.kt:552` (`if (false && ...)`) caused `nonOrgAdminCannotReplaceAdminDevice` & `linkedDeviceBindingIsClearedOnReplacementAndProtectedFromDeptHead` to fail (`expected:<403 Forbidden> but was:<200 OK>`, 12 completed / 2 failed); restored and verified green. | `.\tools\flash-gradle.cmd --max-workers=2 :server:test --tests "com.transfer.flash.server.admin.DeviceReplacementTest"` + full `:server:test` |
| HR6-console | `devices.js:344-610`, `strings/en.js`, `devices-replace.test.mjs` | `devices-replace.test.mjs` (4 tests) + full console suite (87 tests) | Verified in Node suite (`filterReplaceGroups`, `buildReplacePayload`, `resDlg.close`). | `node --no-warnings --test server/admin-ui-tests/*.test.mjs` (87/87 passed) |

**Self-score (12 categories):**
1. Failure paths: Covered (`400` invalid department/group, `403` wrong scope/admin-only, `404` out-of-scope lookup hiding, `409 ERR_QUOTA_EXCEEDED`).
2. Hostile input: Covered (stale/stolen old certs & bearer tokens, replayed single-use tokens, expired tokens, dept-head privilege escalation).
3. Concurrency & ordering: Covered (`concurrentReplacementRedemptionPermitsExactlyOneWinner` under SQLite transaction).
4. Lifecycle: Covered (old revocation persists across Hub restart & propagates over HR5 LAN gossip; admin `approver`/`signer`/`linked` bindings cleared).
5. Scale / quotas: Covered (`remainingQuotaAssumingRevoked` verifies exact capacity at boundary and over-quota refusal).
6. Security: Covered (step-up `device.replace`, PoP verification on enrollment, zero secrets/tokens in audit log, mutation check verified).
7. Compatibility: Covered (`flash://enroll` URL matches existing Android/Desktop `EnterpriseEnrollmentClient`).
8. Both hosts: Covered (tested with shared `EnterpriseEnrollmentClient` used by Android & Desktop + console Web UI).
9. Accessibility/UI: Covered (explicit target department, allowed/auto-join group checkboxes, localized strings in `en.js`, clean dialog close).
10. Observability: Covered (audit chain entries record old and new bindings without recording tokens/keys).
11. Documentation: Covered (`docs/protocol.md`, `docs/protocol-routes.txt`, `docs/security.md`, `docs/testing/TEST-BACKLOG.md`).
12. Admin/support view: Covered (clear error messages, recovery invite presentation once in dialog, `DeviceDto` status update).

**Adversarial review findings (10):**
1. Dept-head privilege escalation (`AdminApi.kt:551-555`): Refuses non-org-admin replacement of admin devices or devices bound as `approverDeviceId`, `enrolledDeviceId`, or `linkedDeviceId` (`nonOrgAdminCannotReplaceAdminDevice`, `linkedDeviceBindingIsClearedOnReplacementAndProtectedFromDeptHead`).
2. Stale bearer token survival (`AdminApi.kt:565`): Explicitly revokes all active bearer tokens (`d.bearerTokens?.revokeDevice(id)`) alongside certificate revocation so existing REST sessions die immediately (`stolenOldCredentialsAndTokenRefusedAfterReplacement`).
3. Quota bypass or false blockage (`EnrollmentService.kt:136`): Active device replacement frees 1 slot cleanly at quota ceiling (`quotaIsEnforcedOnReplacement`) while already-over-quota fleets still fail closed (`overQuotaActiveDeviceReplacementRefused`).
4. Cross-department group smuggling (`AdminApi.kt:538-543`, `devices.js`): Both UI (`filterReplaceGroups`) and server API reject assigning groups outside `targetDept` for department heads.
5. Replacement invite replay (`DeviceReplacementTest.kt:363`): `maxUses = 1` refuses second redemption attempt (`replacementInviteIsSingleUseAndCannotBeReplayed`).
6. Concurrent enrollment race (`DeviceReplacementTest.kt:483`): Parallel enrollment requests allow strictly 1 winner (`concurrentReplacementRedemptionPermitsExactlyOneWinner`).
7. Expired grant redemption (`DeviceReplacementTest.kt:453`): Past-TTL replacement token rejected without enrolling (`expiredReplacementGrantIsRefused`).
8. Unbound admin device lock-in (`AdminApi.kt:569-575`): Replacement clears stale `approverDeviceId`, `enrolledDeviceId`, and `linkedDeviceId` so admin recovery isn't tied to a dead device (`boundApproverDeviceReplacementClearsAdminBinding`).
9. Scope oracle leak (`AdminApi.kt:520`): Out-of-scope `id` answers `404` identically to non-existent device (`departmentHeadScopeEnforcedOnDeviceReplacement`).
10. Stolen dialog link / modal stuck state (`devices.js`): Fixed dialog `resDlg.close(true)` so Close button cleanly closes modal without leaking old DOM refs (`devices-replace.test.mjs`).

**Extra situations handled (5):**
1. Over-quota replacement while fleet exceeds configured cap refused (`overQuotaActiveDeviceReplacementRefused`).
2. Replacement of an already-revoked device still requires free quota slot rather than double-crediting a slot (`remainingQuotaAssumingRevoked(false)`).
3. Dept head replacing member with role upgrade attempt (`b.role != HubRoles.MEMBER`) blocked with `403 ERR_FORBIDDEN`.
4. Replacement of non-existent department refused (`400` `"That department no longer exists."`).
5. Shared `EnterpriseEnrollmentClient` end-to-end redemption with fresh ECDSA keypair + PoP followed by old cert rejection (`enterpriseEnrollmentClientRedeemsReplacementLinkWithProofOfPossessionAndRevokedDeviceRefused`).

**Tests:** `DeviceReplacementTest` (12 tests, 0 failures) + `devices-replace.test.mjs` (4 tests, 0 failures; 87/87 console Node tests pass).
**Mutation check:** Bypassed `scope !is ConsoleScope.Org` in `AdminApi.kt:552` -> 2 HR6 privilege tests failed (`403` vs `200`); restored -> passes in final `:server:test` gate.

### HR7 explain-back — 2026-11-13

Title-only announcement notifications and confidentiality disclosure. When an eligible announcement is stored, Android posts a system notification and desktop displays a Flash toast. Both show only the announcement title and scope. Delivery respects department scope, user mute preferences, Android notification permission and author suppression. Android notification taps route through `EXTRA_HUB_ROUTE`. Desktop's Open Announcement action brings Flash to the foreground and routes to that announcement. First sync and reconnect full-resync silently populate the feed without blasting past announcements. Duplicate notifications are suppressed. The in-app announcement feed remains usable even if notifications are denied or disabled. In group settings and chat header, explicit confidentiality disclosure notifies users that group messages relayed through the hub are not sealed yet and can be read by the hub, while LAN peer-to-peer messages remain private.

Files touched:
- `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt`: Add announcement notification channel `flash_announcements`, `showAnnouncementNotification` with title-only body and `EXTRA_HUB_ROUTE` pending intent.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopNotificationManager.kt`: Add `showAnnouncementNotification` title-only notification and launch handling.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`: Wire announcement notification callbacks and settings.
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopSettingsStore.kt`: Add `showAnnouncementNotifications` setting.
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`: Track announcement eligibility (initial sync silent, delta sync new items only, author suppression, department scope, deduplication set, rate coalescing) and emit notification callbacks.
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`: Wire Android announcement notification callback to `FlashNotificationManager` respecting preferences and permissions.
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashGroupSettingsSheet.kt`: Add group relay confidentiality disclosure notice banner in group settings when linked to organization hub.
- `docs/plans/HUB-READINESS-REMEDIATION.md`: HR7 explain-back and verification results.

Three most similar existing classes read:
1. `FlashNotificationManager.kt`: Reused `initChannels`, `NotificationCompat.Builder`, `PendingIntent` creation with `MainActivity`, and channel management.
2. `DesktopNotificationManager.kt`: Reused `engine.onInboundMessageNotification` callback pattern and `DesktopSettingsStore`.
3. `RealFlashChatRepository.kt` (`refreshHubAnnouncements`): Reused delta sync paging, Room `HubAnnouncementDao`, and existing inbound notification callbacks (`onInboundTextMessageWithGroupTitle`).

Five failure modes:
1. Historical backfill flood: First sync or full-resync blasting notifications for hundreds of old announcements. (Guarded by checking `currentSince == 0L` on initial sync and `feed.fullResync` to suppress notifications for historical backfill).
2. Privacy leak on lock screen: Announcement text/body leaked in notification previews. (Guarded by title-only notification content; body is strictly the announcement title, never the announcement body).
3. Author self-notification: Author receiving a notification for their own post. (Guarded by checking `item.authorDeviceId != localDeviceId`).
4. Replay and reconnect duplicate storms: Replayed delta feeds or repeated reconnects popping the same notification multiple times. (Guarded by in-memory and persisted deduplication set of notified announcement IDs).
5. Permission denial crash: On Android 13+, denied `POST_NOTIFICATIONS` causing crashes or blocking the in-app news feed. (Guarded by try/catch in notification manager and keeping repository news feed decoupled from notification delivery).

Tests to write first:
1. Unit tests for announcement notification content generation and title-only invariant.
2. Announcement notification eligibility tests: initial sync vs delta sync, author suppression, out-of-scope department suppression, duplicate suppression.
3. Notification tap cold start routing test with `EXTRA_HUB_ROUTE`.
4. Group settings confidentiality disclosure presence test.

### Phase HR7 report — 2026-11-13

- **Done:** Title-only announcement notifications (`NotificationDecision`, `FlashNotificationManager`, `DesktopNotificationManager`), strict author & department-scope suppression & bounded 512-entry LRU deduplication (`RealFlashChatRepository.kt:465-472,8496-8508`), cold-start and active deep-link routing (`MainActivity.kt:1024,1132,3404`, `DesktopMain.kt:168,210-214`, `DesktopShell.kt:3562,3716`, `LaunchEntryPolicy`), Android & Desktop Settings toggle (`"Announcement notifications"` in `FlashSettingsScreen.kt:177,459,1229-1234`, `DiscoveryEngineHolder.kt:278,688,1323`, `DesktopSettingsStore.kt:59,123,153,189`), and explicit Hub group relay confidentiality disclosure (`FlashGroupSettingsSheet.kt`).
- **Done but only unit-tested (`HR7-DEV-01` added to `docs/testing/TEST-BACKLOG.md`):** Physical Android 13+ `POST_NOTIFICATIONS` denial/mute, lock-screen preview check, cold-start notification tap from killed state, and Windows Desktop toast activation.
- **Not done, and why:** Live Android/Desktop physical device verification deferred (no physical phone connected to host workspace); externally managed OS wake-up push remains behind an explicit service decision.
- **Surprises:** (1) Initial implementation only checked hidden-department exclusion rather than strict recipient department matching (`userDept != null && item.departmentId == userDept`), fixed in `RealFlashChatRepository.kt`; (2) `DesktopMain.kt` initially dropped the `announcementId` lambda argument in `showAnnouncementNotification`, and `FlashSettingsScreen.kt` lacked a runtime toggle for announcement opt-out until remediation commit `5904e545`; (3) `notifiedAnnouncementIds` needed a bounded FIFO/LRU eviction (`512` IDs) so long-running daemons never accumulate unbounded memory.
- **Decisions:** Kept announcements plaintext on the Hub (ADR-138) with title-only shade rendering; exposed user opt-out in shared Settings (`onAnnouncementNotificationsChanged`) rather than OS-only notification settings.
- **Self-score (12 categories):**
  1. Failure paths: Covered (`runCatching` around OS notification dispatch keeps announcement feed sync intact if notification display fails).
  2. Hostile input: Covered (deep-link codec `FlashHubMath.routeFromArgs` rejects traversal/oversize/malformed IDs in `FlashHubSearchCodecTest` & `FlashHubMathTest`).
  3. Concurrency & ordering: Covered (dedup set guarded under mutex/lock in `RealFlashChatRepository`, initial backfill vs delta sync ordering verified in `RealFlashChatRepositoryAnnouncementsTest`).
  4. Lifecycle: Covered (initial sync vs delta sync vs full resync suppression; cold-start route handoff via `EXTRA_HUB_ROUTE` / `--hub-route=` / toast callback).
  5. Scale: Covered (`notifiedAnnouncementIds` capped at 512 entries with O(1) LinkedHashSet removal).
  6. Security: Covered (no body leakage on lock screens, strict author/department suppression; mutation verified below).
  7. Compatibility: Covered (un-enrolled clients show no toggle/channel noise; offline muted feed remains accessible).
  8. Both hosts: Covered (Android `FlashNotificationManager` + `DiscoveryEngineHolder` and Desktop `DesktopNotificationManager` + `DesktopMain`/`DesktopShell`).
  9. Accessibility/UI: Covered (shared Settings switch label & subtitle, group confidentiality disclosure banner in Compose).
  10. Observability: Covered (non-secret warning logs on permission/delivery failures; no titles or bodies logged in notifications errors).
  11. Documentation: Covered (`docs/security.md`, `docs/testing/TEST-BACKLOG.md`, `docs/testing/PROBES.md`, `logs/progress.md`).
  12. Admin/support view: Covered (plaintext announcement honesty notice + group relay confidentiality disclosure documented in user UI and Hub docs).

| Area | Evidence | Mutation |
|---|---|---|
| Lock-screen privacy & content bounds | `FlashNotificationContentTest`: `announcement notification content enforces title only lock screen confidentiality` and `announcement notification ID partition is 4000..4999` (`FlashNotificationManager.kt`). | Modifying notification text to include body fails lock-screen privacy tests; restored. |
| In-app eligibility, scope & author suppression | `RealFlashChatRepositoryAnnouncementsTest`: `HR7 delta sync emits notification for eligible new announcements and enforces author and scope suppression`, `HR7 initial sync silently populates feed without firing notifications`, and foreign department exclusion (`RealFlashChatRepository.kt:465-472,8496-8508`). | Mutating `isAuthor = false` broke author suppression (`expected:<2> but was:<3>`); restored and green. |
| Desktop notifications & opt-out | `DesktopNotificationManagerTest.announcementNotificationDispatchesTitleOnly` & `announcementNotificationSuppressedWhenDisabled` (`DesktopNotificationManager.kt:237`, `DesktopMain.kt:210-214`). | Bypassing `isAnnouncementNotificationsEnabled()` caused unwanted notification dispatch; restored. |
| Group relay confidentiality disclosure | `FlashGroupSettingsSheet.kt` displays explicit disclosure while unsealed (updated cleanly by HR10 when E2EE active). | Verified in Compose tree. |
| Cold-start & settings wiring | `FlashHubRouteLifecycleTest`, `FlashHubMathTest`, `FlashHubSearchCodecTest`, `MainActivity.kt`, `DesktopShell.kt`. | Covered. |

**Adversarial review findings (10):**
1. Initial backfill notification storm (`RealFlashChatRepository.kt:8497`): Suppressed whenever `existing == null && !isInitialSync && !feed.fullResync`.
2. Foreign-department announcement leakage (`RealFlashChatRepository.kt:8500-8502`): Refused department-scoped notifications unless `userDept != null && item.departmentId == userDept`.
3. Author self-ping (`RealFlashChatRepository.kt:8503`): Suppressed when `item.authorDeviceId == creds.deviceCert.deviceId`.
4. Unbounded memory growth in `notifiedAnnouncementIds` (`RealFlashChatRepository.kt:462-470,7829`): Bounded to `MAX_NOTIFIED_ANNOUNCEMENT_IDS = 512`.
5. Lock-screen body preview leakage (`FlashNotificationManager.kt:829-832`, `DesktopNotificationManager.kt:237`): Notification body is strictly `title.trim()`; announcement body text never reaches OS APIs.
6. Android 13+ notification delivery failures (`DiscoveryEngineHolder.kt:688-695`, `FlashNotificationManager.kt`): Wrapped cleanly so permission or OS errors do not interrupt DB storage or the in-app news feed.
7. Edit/pin notification storm (`RealFlashChatRepository.kt:8497`): Only `existing == null` newly stored items are notification-eligible.
8. Deep-link path traversal (`FlashHubMath.kt:738`, `FlashHubRouteLifecycleTest.kt:21-33`): Rejects `../`, spaces, empty IDs, and malformed route segments.
9. Desktop notification click dead-end (`DesktopMain.kt:210-214`): Wired callback to update `pendingHubRoute.value` to the target announcement route.
10. Coroutine outbox shutdown race in tests (`RealFlashChatRepository.kt:6518`, exposed when running `:core:messaging:jvmTest`): `drainOutboxLoop` checked cancellation cleanly after `close()` so SQLite pool teardown errors don't leak across tests.

**Extra situations handled (5):**
1. User denies OS notification permission or disables `"Announcement notifications"` toggle -> feed remains fully populated & readable in Hub tab.
2. Same announcement delivered across multiple delta sync reconnects -> deduplicated by `notifiedAnnouncementIds`.
3. Announcement arrives for the user's own department from a colleague vs another device -> allowed; same user's other publish on same device -> suppressed.
4. Cold-start launch from `--hub-route=hub/announcement/<id>` -> skips launch splash delay via `LaunchEntryPolicy` and opens announcement.
5. Group E2EE vs legacy relay notice -> `FlashGroupSettingsSheet` surfaces clear plain-English status without protocol jargon.

**Open risks:** Android background sleep / Doze delivery relies on app/relay connectivity (`HR7-DEV-01` tracks physical verification).

**Tests:** Verified across `:core:messaging:jvmTest` (391 tests), `:ui:chat:jvmTest` (790 tests), `:desktop:jvmTest` (294 tests), `:app:testDebugUnitTest` (111 tests) with 0 failures (`5904e545`, re-verified in final gate).

### HR8 handoff

Define transfer continuity across LAN and Hub. Define stable transfer session identity, validated saved byte representation, ownership of the active route, and final integrity verification in both LAN -> Hub and Hub -> LAN directions.

### HR8 explain-back — 2026-11-13

Transfer continuity across LAN and Hub allows a file transfer started on either route (LAN direct or sealed Hub relay) to resume seamlessly on the alternate route when connectivity changes, without duplicating transfer rows, duplicating published output files, or risking file corruption. Both routes share a canonical `LogicalTransferIdentity` bound to `(logicalTransferId, senderDeviceId, recipientDeviceId, totalBytes, plainSha256Hex)`. Verified saved plaintext bytes are represented as discrete byte spans and mapped across incompatible chunk layouts (`64 KiB` vs `1 MiB`) using exact byte-interval containment: only target chunks completely covered by cryptographically verified saved ranges are pre-satisfied, with partial or unaligned chunks cleanly re-fetched. Route ownership is serialized by `TransferRouteCoordinator` with monotonic `routeEpoch` counters so superseded route frames, progress updates, or completions cannot race. Before completing and publishing to the user-visible directory, either route verifies the whole assembled file against `plainSha256Hex`, producing at most one completion row and one published copy.

Files touched:
- `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt`: Add `seedDownloadFromVerifiedBytes` and `exportVerifiedChunks` so Hub file downloads can seed/export verified plaintext spans safely across route handovers.
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/hubfiles/TransferContinuityCoordinator.kt`: Define `LogicalTransferIdentity`, `VerifiedByteSpan` alignment math (`TransferByteAlignment`), `RouteOwner`, and atomic `TransferRouteCoordinator` with `routeEpoch` race prevention, changed-source verification, cancellation, and idempotent single-completion guarantees.
- `core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/TransferContinuityTest.kt`: Test LAN->Hub and Hub->LAN byte adoption across mismatched chunk sizes, tampered partial data rejection, changed source rejection, concurrent route races, cancellation, and final digest verification.
- `docs/plans/HUB-READINESS-REMEDIATION.md`: HR8 explain-back, results, and adversarial review.

Three most similar existing classes read:
1. `HubFileBinding.kt` (`core/engine/hubfiles/`): Reused `PlatformLock`, external rows attachment to `RealFlashTransferRepository`, and `ReceivedFilePlacement.publish` single-copy semantics.
2. `HubFileTransfers.kt` (`core/enterprise/files/`): Reused `HubFileLayout`, `HubChunkBitmap`, `DownloadState` disk persistence, and whole-file SHA-256 verification.
3. `ReceivePipeline.kt` (`core/transfer/chunked/`): Reused `resumeIndexesProvider`, per-chunk SHA-256 verification, and `recheckWholeFileDigest` backstop.

Five failure modes:
1. Chunk layout mismatch corrupts tail: Hub plaintext chunks (1,048,560 B) do not divide LAN chunks (65,536 B) cleanly. (Guarded by exact interval intersection: target chunks extending beyond verified saved boundaries are NOT marked done and are re-fetched).
2. Changed source file during handover: User edits source file after LAN drop before continuing on Hub. (Guarded by re-hashing source file against `logicalIdentity.plainSha256Hex` and `totalBytes` before route switch).
3. Tampered `.part` file on disk before handover: Corrupted saved bytes adopted onto new route. (Guarded by optional per-span hash verification on import and mandatory whole-file SHA-256 verification before publication).
4. Route race (dual completion or stale progress): Old LAN coroutine completes or emits failure after Hub route assumed ownership. (Guarded by atomic `routeEpoch` in `TransferRouteCoordinator`; stale epochs are dropped).
5. Wrong sender/recipient confusion: Attempting to adopt partial bytes from a different peer or unauthorized fileId. (Guarded by binding `senderDeviceId`, `recipientDeviceId`, and `plainSha256Hex` inside `LogicalTransferIdentity`).

Tests to write first:
1. Chunk interval alignment across mismatched layouts (1 MiB Hub <-> 64 KiB LAN, misaligned offsets).
2. End-to-end LAN -> Hub download continuation: partial bytes seeded into Hub `DownloadRun`, remaining chunks downloaded, single final verified copy matching original SHA-256.
3. End-to-end Hub -> LAN download continuation: partial Hub download exported, LAN `ReceivePipeline` seeded via `resumeIndexesProvider`, remaining chunks fetched, single final verified copy.
4. Changed source file / mismatched digest / wrong peer identity / wrong recipient rejected cleanly (zero bytes adopted).
5. Tampered partial file caught before publish; route race with stale epoch rejected; cancellation closes both routes.

### Phase HR8 report — 2026-11-13

Cross-route LAN <-> Hub file transfer continuity implemented with stable logical transfer identity, exact byte-span intersection across mismatched chunk layouts, monotonic route fencing (`routeEpoch`), single-completion deduplication, and integrity verification.

| Step | Files changed (`path:line`) | Tests (`TransferContinuityTest`, `CrossRouteWiringIntegrationTest`, `HubFileBindingTest`) | Mutation | Commands & Result |
|---|---|---|---|---|
| HR8-core | `core/enterprise/.../HubFileTransfers.kt:243-326` (`exportVerifiedChunks`, `readPartSpan`, `seedDownloadFromVerifiedBytes`, `onChunkVerified`) | `TransferContinuityTest.HR8 HubFileTransfers seedDownloadFromVerifiedBytes rejects mismatched SHA or chunk length` | Mutating `matches()` in `TransferContinuityCoordinator.kt:47-51` to skip SHA-256 digest mismatch check caused `HR8 refuses changed source digest...` to fail (`Expected value to be false.` at `TransferContinuityTest.kt:348`). Restored and verified green. | `tools\flash-gradle.cmd :core:engine:jvmTest :core:enterprise:jvmTest :core:transfer:jvmTest` |
| HR8-align | `core/engine/.../TransferContinuityCoordinator.kt:85-162` (`TransferByteAlignment`, `LogicalTransferIdentity`) | `TransferContinuityTest.HR8 byte alignment converts 1MiB Hub chunks to 64KiB LAN chunks without partial overlap` | Covered by digest/identity mutation check above. | Same as above + `:desktop:jvmTest` & `:app:testDebugUnitTest`. |
| HR8-routes | `core/transfer/.../TransferRouteContinuityPort.kt:12`, `core/transfer/.../RealFlashTransferRepository.kt:111,736,1588-1650,1839`, `core/engine/.../TransferContinuityCoordinator.kt:168-860` (`TransferRouteCoordinator`, `EngineTransferRouteContinuityPort`), `core/engine/.../HubFileBinding.kt:127,487-515,590`, `app/.../DiscoveryEngineHolder.kt:1099`, `desktop/.../DesktopEngine.kt:1428` | `CrossRouteWiringIntegrationTest` (`HR8 LAN to Hub receive continuity...`, `HR8 Hub to LAN receive continuity...`, `HR8 cancel on LAN marks Hub route cancelled...`), `TransferContinuityTest`, `HubFileBindingTest` | Covered above. | Full host & Android gate green with 0 failures. |

**Quality Contract Coverage (12 categories):**
1. Failure paths: Interrupted Hub download (`HubDownloadOutcome.Interrupted`), truncated/missing partial spans (`readPartSpan` null), mismatched seed length, refused transitions return structured `ResumePlan(compatible=false, reason=...)`.
2. Hostile input: Wrong source digest, changed size, wrong peer/recipient, tampered partial bytes (`verifyChunkPlaintext` / `verifySpanIntegrity` abort adoption), invalid hex digests (`Sha256.isValidHex`) all fail closed.
3. Concurrency & ordering: All state transitions, route ownership acquisitions, handovers, verified span recording, and final completion run under `PlatformLock` with monotonic `routeEpoch`; stale epochs cannot overwrite or double-publish.
4. Lifecycle: Hub download state (`DownloadState`) + `.part` files survive process stop via `DiskHubFileStorage`, exported cleanly via `exportVerifiedChunks`; cancellation moves coordinator to `CANCELLED` across routes.
5. Scale: `TransferByteAlignment.normalizeSpans` sorts and merges spans in `O(n log n)` and iterates target chunks in `O(c)` linear scan over sorted merged intervals.
6. Security: Binds `(logicalTransferId, senderDeviceId, recipientDeviceId, plainBytes, fileSha256Hex)`; rejects digest changes or wrong recipient; requires whole-file SHA-256 verification before `completeTransfer`; verified with mutation check.
7. Compatibility: Legacy Hub rows without a continuity checkpoint behave identically (`latestContinuity == null` preserves normal Hub flow in `HubFileBinding.download`); older/incompatible chunk sizes trigger clean re-fetch of unaligned boundary chunks without breaking either protocol.
8. Both hosts: All continuity and alignment logic lives in `core/enterprise` and `core/engine` (`commonMain`), shared identically by Android and Desktop hosts; existing UI/progress state (`HubFileBinding.rows`) surfaces single consolidated progress/retry/cancel without route duplication.
9. UI/accessibility: `HubFileBinding` routes consolidated state through `RealFlashTransferRepository.attachExternalRows` and existing transfer progress/cancel controls; prevents duplicate completion rows when `completeTransfer` returns `false`.
10. Observability: `HubFileBinding` emits `hubfile.receive.done` and `hubfile.cancel` probes with shortened 8-char IDs; coordinator returns machine-readable refusal reasons (`source_identity_mismatch`, `tampered_partial_bytes`, `cancelled`, `already_completed`).
11. Documentation: HR8 explain-back, report, and backlog item (`HR8-DEV-01` for physical two-device Android <-> Desktop large-file switch test) recorded.
12. Admin/support view: Coordinator exposes explicit `ContinuityCheckpoint` snapshot and `ResumePlan.reason` strings explaining why cross-route resume succeeded or fell back cleanly.

**Adversarial Self-Review (10 findings):**
1. `TransferByteAlignment.intersectChunksCoveredBySpans` (`TransferContinuityCoordinator.kt:128`): Previously if a Hub chunk ended mid-way through a 64 KiB LAN chunk (e.g., 1,048,560 is 16 bytes short of 16 * 65,536 = 1,048,576), naive `floor(bytes / 65536)` vs chunk index math could mark chunk 15 done; fixed by checking exact `[start, endExclusive)` full interval containment (`span.offset <= start && span.endExclusive >= endExclusive`), verified in `HR8 byte alignment...`.
2. `LogicalTransferIdentity.matches` (`TransferContinuityCoordinator.kt:35`): If sender initially advertises `fileSha256Hex = null` on one route and binds the digest later, promoting `fileSha256Hex` on `tryAcquireRoute` ensures later mismatches are caught rather than bypassed.
3. `HubFileTransfers.seedDownloadFromVerifiedBytes` (`HubFileTransfers.kt:276`): If `storage.writeState` fails after writing `.part` chunks, `storage.deleteState` cleans up partial state and returns `0` rather than leaving a corrupt bitmap.
4. `TransferRouteCoordinator.adoptHubToLan` (`TransferContinuityCoordinator.kt:405`): If a single verified chunk fails `verifyChunkPlaintext`, the whole `adoptHubToLan` returns `compatible=false, reason="tampered_partial_bytes"` instead of partially seeding an untrusted file.
5. `TransferRouteCoordinator.completeTransfer` (`TransferContinuityCoordinator.kt:548`): Validates `Sha256.isValidHex(actualSha256Hex)` and checks constant-time digest equality (`Sha256.hexEqualsConstantTime`) when expected digest exists, rejecting blank output paths or wrong digests.
6. `HubFileBinding.download` (`HubFileBinding.kt:489`): Checks `continuityCoordinator.snapshot(row.fileId)` before even fetching chunks from Hub if LAN already completed or user cancelled, immediately deleting duplicate file placement if LAN raced and finished first.
7. `TransferRouteCoordinator.tryAcquireRoute` (`TransferContinuityCoordinator.kt:200`): Calling `tryAcquireRoute` with an incompatible identity poisons `status = INCOMPATIBLE` and clears `activeRoute = RouteOwner.NONE` so no route proceeds with mixed bytes.
8. `TransferByteAlignment.normalizeSpans` (`TransferContinuityCoordinator.kt:90`): Filters out `length <= 0L` and clamps `[0, totalBytes)` so integer overflow or negative offsets from malformed metadata cannot create phantom coverage.
9. `TransferContinuityTest` (`TransferContinuityTest.kt:284`): Verifies `0 !in fetchedIndexes` during LAN -> Hub resume, proving already-verified bytes actually skip Hub chunk fetches rather than silently re-downloading everything.
10. `TransferRouteCoordinator.cancelTransfer` (`TransferContinuityCoordinator.kt:573`): Refuses to cancel an already `COMPLETED` transfer (preserving immutable completion audit), while blocking any `ACTIVE` transfer from resuming or publishing on another route.

**Extra Situations Handled (5+):**
1. Non-divisible chunk boundaries (1,048,560-byte Hub plaintext chunks vs 65,536-byte LAN chunks leaving 16-byte deficit on chunk 15).
2. Stale-epoch late completion race after route handover (`completeTransfer` returns `false`, `HubFileBinding` deletes duplicate file).
3. Seeding `HubFileTransfers` with wrong slice length rejected before bitmap mutation.
4. Acquiring route with a modified recipient ID rejected (`wrongPeerPlan`).
5. Hub failure/interruption after chunk 0 followed by LAN takeover and whole-file SHA-256 verification.

**Done:** `TransferRouteContinuityPort.kt`, `RealFlashTransferRepository.kt` (wire hooks on send, receive start/chunk/assembled/fail/cancel/decline), `TransferContinuityCoordinator.kt` & `EngineTransferRouteContinuityPort` (`core/engine/.../TransferContinuityCoordinator.kt:654-860`), `HubFileTransfers.kt` (`seedDownloadFromVerifiedBytes`, `exportVerifiedChunks`, `onChunkVerified`), `HubFileBinding.kt` (`continuityPort`), Android & Desktop production wiring (`DiscoveryEngineHolder.kt:1099`, `DesktopEngine.kt:1428`), `TransferContinuityTest.kt`, and `CrossRouteWiringIntegrationTest.kt` (3 end-to-end production-wiring integration tests).
**Done but only unit-tested (`HR8-DEV-01` added to backlog):** Cross-network live LAN <-> Hub large-file handover on physical Android + Desktop hardware.
**Not done, and why:** Live Android/Desktop physical device run deferred per assignment constraints (no physical phones connected; emulated via `FakeHubFileServer`, `DiskHubFileStorage`, `RealFlashTransferRepository`, `HubFileBinding`, and `ReceivePipeline`).
**Surprises:** (1) Earlier HR8 left `TransferRouteCoordinator` unwired from `RealFlashTransferRepository`, `DiscoveryEngineHolder`, and `DesktopEngine` until remediation; bridged cleanly without circular Gradle module dependencies via `TransferRouteContinuityPort`; (2) Marking `TransferRouteContinuityPort` `@FlashInternalApi` broke callers instantiating `RealFlashTransferRepository(...)` with its default `routeContinuity = null` parameter (`SwarmHostBindingHardeningTest`, `DesktopInboundRecoveryTest`), fixed by making the port interface public without opt-in friction; (3) On interrupted LAN receives, `TransferContinuityCoordinator.recordVerifiedChunks` tracks verified chunk bytes via `HubFileTransfers.onChunkVerified` and `RealFlashTransferRepository.onIncomingChunkConfirmed` BEFORE null-store early return so in-memory/unpersisted repositories still record continuity checkpoints.
**Decisions:** Used explicit byte-interval intersection (`TransferByteAlignment`) rather than fixed chunk-size negotiation so incompatible LAN/Hub chunk sizes (`64 KiB` vs `1 MiB`) resume safely; enforced whole-file SHA-256 verification before clearing any destination copy as `COMPLETED`.
**Open risks:** Android/Desktop multi-hundred-MB physical network toggle speed and battery/thermal behavior remain unmeasured (`HR8-DEV-01`).

### HR9 handoff

Implement authenticated, short-lived off-LAN call signaling over the Hub WebSocket relay, REST-authenticated ephemeral ICE/TURN server configuration, explicit direct/relayed media policy (`CallMediaPathPolicy`), idempotent signaling state transitions, replay/expiry/revocation enforcement, and honest UI call-action gating on Android and Desktop while preserving existing LAN calls unchanged.

### HR9 explain-back — 2026-11-13

Off-LAN calling extends Flash's WebRTC calling across independent networks without breaking LAN direct calling or queueing stale call rings in the 7-day offline Hub mailbox. The Hub server adds an ephemeral authenticated relay frame `FLASH_CALL_SIGNAL` (stamped `from = origin.deviceId`, checked for TTL `<= 60s`, active recipient session, and live revocation status, never persisted to offline mailbox) and a Bearer-protected `GET /api/v1/calls/ice-config` endpoint that mints short-lived HMAC-SHA1 coturn-compatible TURN credentials when an isolated STUN/TURN service is configured (otherwise reporting `offLanCallingSupported = false`). Client-side `:core:calling` introduces `OffLanCallSignalGuard`, `CallMediaPathPolicy` (`DIRECT_ONLY`, `DIRECT_PREFERRED_RELAY_FALLBACK`, `RELAY_ONLY`), and capability-gated `CallRouteCapabilities` integrated with `CallCoordinator`, `FlashCallSession`, and `FlashGroupCallSession` so `RtcConfiguration` receives verified ICE/TURN servers for off-LAN calls while defaulting to empty `iceServers` for LAN direct calls. Both Android and Desktop gate off-LAN call buttons on usability and non-revocation.

Files to touch:
- `core/hub-protocol/.../HubContract.kt`, `RelayFrame.kt`, `RestDtos.kt`: Add `PATH_CALL_ICE`, `FEATURE_HUB_CALLING`, `RelayFrame.CallSignal`, and `HubIceConfigDto` / `HubIceServerDto`.
- `server/.../ServerConfig.kt`, `DirectoryRoutes.kt` (or `CallConfigRoutes.kt`), `Main.kt`, `RelayFrameRouter.kt`, `RelayMetrics.kt`: Expose `/api/v1/calls/ice-config` with HMAC-SHA1 time-limited TURN credentials and handle `FLASH_CALL_SIGNAL` ephemerally with expiry, auth, and revocation checks.
- `core/calling/.../HubOffLanCalling.kt`, `FlashCallSession.kt`, `FlashGroupCallSession.kt`, `CallCoordinator.kt`: Add `CallMediaPathPolicy`, `OffLanCallSignalGuard` (replay/TTL/terminal state/idempotence), `CallRouteCapabilities`, and dynamic `iceConfigProvider` on `CallCoordinator` / `FlashCallSession`.
- `core/messaging/.../model/FlashMessagingModels.kt` / `ui/chat/...` / `docs/`: Align call gating helper, `docs/protocol.md`, `docs/deploy/HUB-SERVER-DEPLOYMENT.md`.
- Tests: `server/.../CallSignalingAndIceConfigTest.kt`, `core/calling/.../OffLanCallSignalingTest.kt`.

Three most similar existing classes read:
1. `RelayFrameRouter.kt` (`server/relay/`): Reused `handleInboundMeta` ephemeral live-session routing (`connectionPool.get`), SEC-02 hub-stamped `from`, and revocation refusal rather than mailbox storage.
2. `CallCoordinator.kt` (`core/calling/`): Reused single-active-session model, `isTrustedPeer` gate, `endedGroupCalls` stale-invite guard, and `FlashCalling` interface.
3. `FlashCallSession.kt` (`core/calling/`): Reused `RtcConfiguration` (`BundlePolicy.MaxBundle`, `RtcpMuxPolicy.Require`), extending only the hardcoded `iceServers = emptyList()` / `iceTransportPolicy` via optional injected parameters.

Five failure modes:
1. Offline invite queued in 7-day mailbox rings a phone days later: Guaded by routing `FLASH_CALL_SIGNAL` only to live WebSocket sessions and returning immediate `ERR_NOT_FOUND` when peer is offline.
2. Stale/replayed or clock-skewed call signaling hijacks or restarts a finished call: Guarded by `issuedAt + ttlMs + clockSkew`, bounded `signalId` replay dedupe, monotonic `seq`, and terminal-call rejection in `OffLanCallSignalGuard`.
3. Revoked device places or receives off-LAN calls or fetches TURN credentials: Guarded on REST `ice-config`, server `RelayFrameRouter` dispatch, and client `CallRouteCapabilities.isPeerRevoked`.
4. Exposed TURN static secret or leaked credentials in ICE responses/logs: Guarded by deriving short-lived HMAC-SHA1 `username = <exp>:<deviceId>` credentials on the server and never sending `turnSecret`.
5. Dead off-LAN call button shown when Hub lacks ICE/TURN or relay session is disconnected: Guarded by `CallRouteCapabilities.evaluateEligibility()` hiding/gating off-LAN calls while preserving direct LAN call controls.

Tests to write first:
1. Server `/api/v1/calls/ice-config`: unconfigured returns `offLanCallingSupported=false`; configured mints valid RFC-5766 HMAC-SHA1 credential scoped to caller `deviceId`; revoked/unauthenticated returns 401/403.
2. Server `FLASH_CALL_SIGNAL` routing: hub stamps `from` (spoofed `from` overwritten); expired `ttlMs` rejected (`ERR_EXPIRED`); offline target gets immediate `ERR_NOT_FOUND` (zero mailbox rows); revoked target/sender rejected (`ERR_FORBIDDEN`).
3. Client `OffLanCallSignalGuard`: accept/decline/cancel/hangup idempotent transitions; duplicate `signalId`, replayed/out-of-order `seq`, late post-terminal frames, expired timestamps rejected.
4. Client `CallRouteCapabilities`: direct LAN allowed even without Hub; off-LAN allowed only when local+hub+peer support it and not revoked; `RELAY_ONLY` forces `IceTransportPolicy.Relay`, `DIRECT_PREFERRED_RELAY_FALLBACK` keeps host+srflx+relay candidates (`IceTransportPolicy.All`).
5. Integration smoke (`DesktopMediaStackSmokeTest`-style): local WebRTC `PeerConnection` offer/answer + `IceServer` / `IceTransportPolicy` construction succeeds cleanly with configured `HubOffLanCalling` specs.

### HR9 implementation report — 2026-11-13

- **Scope & design implemented:**
  - Protocol (`core/hub-protocol`): Added `HubContract.PATH_CALL_ICE` (`/api/v1/calls/ice-config`), `HubContract.FEATURE_HUB_CALLING` (`"hub_calling.v1"`, `HubContract.kt:106`), `CALL_SIGNAL_MAX_TTL_MS = 60_000L`, `RelayFrame.CallSignal` (`FLASH_CALL_SIGNAL` with `to`, `from`, `callId`, `signalId`, `seq`, `issuedAt`, `ttlMs`, `capabilities`, `payload`), `HubIceServerDto`, and `HubIceConfigDto` (`offLanCallingSupported`, `mediaPolicy`, `ttlSeconds`, `iceServers`, `RestDtos.kt:274-279`).
  - Hub Server (`server`): Added `CallConfigRoutes` (`GET /api/v1/calls/ice-config`, Bearer + active non-revoked check, mints RFC-5766 HMAC-SHA1 ephemeral TURN credentials `<expiry>:<deviceId>` when TURN secret + URIs are configured via CLI/env `FLASH_STUN_URLS` / `FLASH_TURN_URLS` / `FLASH_TURN_SECRET`, `ServerConfig.kt:244-250`, never exposing static secret); added `RelayFrameRouter.handleInboundCallSignal` (`RelayFrameRouter.kt:486`) validating TTL (`<= 60s`), expiry (`now - issuedAt <= ttlMs + 5s`, `issuedAt - now <= 30s`), live recipient WebSocket presence (returning immediate `ERR_NOT_FOUND` when offline, 0 mailbox persistence), non-revocation of sender/target, and hub-stamped `from = origin.deviceId`.
  - Calling & Clients (`core/calling`, `core/enterprise`, `core/messaging`, `app`, `desktop`): Added `HubOffLanCalling.kt` (`CallMediaPathPolicy`, `CallIceServerSpec`, `OffLanCallEligibility`, `CallRouteCapabilities`, `InboundOffLanCallSignal`, `OffLanSignalVerdict`, `OffLanCallSignalGuard`), wired `iceConfigProvider`, `offLanSignalGuard`, `onInboundOffLanSignal`, `terminateCallsForRevokedPeer`, and terminal call tracking into `CallCoordinator`, `FlashCallSession`, and `FlashGroupCallSession`. Wired `EnterpriseHub.callIceConfig` auto-refresh, `RealFlashChatRepository` off-LAN call button gating (`offLanCallSupported`), and Android/Desktop relay signal collectors + revocation gossip teardown + relay fallback frame sending.
  - Documentation: Updated `docs/protocol.md` and `docs/deploy/HUB-SERVER-DEPLOYMENT.md` with wire contract, STUN/TURN deployment flags/env vars, and Android background/push limitation disclosure.
- **Verification commands & observed counts (2026-11-13):**
  - `.\tools\flash-gradle.cmd :server:test --tests "com.transfer.flash.server.relay.CallSignalingAndIceConfigTest" --tests "com.transfer.flash.server.relay.RelayFrameRouterTest" :core:calling:jvmTest :core:enterprise:jvmTest :desktop:compileKotlinJvm :desktop:jvmTest --tests "com.transfer.flash.desktop.DesktopConversationHeaderTest" :app:compileDebugKotlin` -> `BUILD SUCCESSFUL` (exit 0).
  - Observed XML results: `CallSignalingAndIceConfigTest`: 5/5 passed (0 skipped, 0 failed); `RelayFrameRouterTest`: 41/41 passed; `OffLanCallSignalingTest[jvm]`: 4/4 passed; all `:core:calling:jvmTest`, `:core:enterprise:jvmTest`, and `DesktopConversationHeaderTest` green; `:desktop:compileKotlinJvm` & `:app:compileDebugKotlin` green.
  - Security mutation check: Temporarily replaced `signal.copy(from = origin.deviceId)` with un-stamped `signal` in `RelayFrameRouter.handleInboundCallSignal`; re-ran `:server:test --tests "com.transfer.flash.server.relay.CallSignalingAndIceConfigTest"` -> **FAILED as expected** (`callSignalRoutesLiveWithAuthenticatedFromAndNeverWritesMailbox`, expected `[alice]`, was `[impersonated-admin]`, 5 tests, 1 failed). Restored `signal.copy(from = origin.deviceId)` and re-ran `:server:test ... :core:calling:jvmTest --tests "com.transfer.flash.core.calling.OffLanCallSignalingTest"` -> **BUILD SUCCESSFUL** (exit 0).
- **12-Category Quality Coverage:**
  1. Normal use: Ephemeral `FLASH_CALL_SIGNAL` live delivery + `GET /api/v1/calls/ice-config` + `PeerConnection` `RELAY_ONLY` offer creation (`OffLanCallSignalingTest`).
  2. Failure: Offline recipient immediately returns `ERR_NOT_FOUND` (0 mailbox rows); missing STUN/TURN config reports `offLanCallingSupported = false`.
  3. Hostile input: Spoofed `from` overwritten by hub; blank payload / negative seq / oversized `ttlMs` rejected by `RelayFrameCodec`.
  4. Concurrency / idempotence: `OffLanCallSignalGuard` handles duplicate `signalId`, out-of-order/replayed `seq`, idempotent `accept`/`decline`/`cancel`/`hangup`, and post-terminal late signals.
  5. Lifecycle / teardown: Revocation gossip tears down active calls on Android and Desktop (`terminateCallsForRevokedPeer`); ended call IDs stay blocked against delayed invites.
  6. Scale / boundedness: Bounded `seenSignals` cache per call + TTL window pruning in `OffLanCallSignalGuard`; `FLASH_CALL_SIGNAL` never writes to SQLite mailbox.
  7. Security: Bearer auth + non-revocation on ICE config, hub-stamped `from`, HMAC-SHA1 time-limited credentials, revoked sender/recipient refusal (`ERR_REVOKED`); verified by mutation check.
  8. Compatibility: `CallRouteCapabilities` gates off-LAN UI when peer/Hub lacks `HubContract.FEATURE_HUB_CALLING` (`"hub_calling.v1"`) or ICE config, while keeping direct LAN calls intact (`lanConnected = true`).
  9. Both hosts: Compiled and wired in `app/.../DiscoveryEngineHolder.kt` (`:app:compileDebugKotlin`) and `desktop/.../DesktopEngine.kt` / `DesktopShell.kt` (`:desktop:compileKotlinJvm`).
  10. UI / accessibility: Off-LAN 1:1 call button shown only when local LAN session OR (`relayReady && peerEligible`) (`RealFlashChatRepository` + `DesktopShell`, `FlashChatHeader.kt:360`).
  11. Observability: `RelayMetrics.FRAMES_OUT` (`FLASH_CALL_SIGNAL`) and `FlashLog` warnings on signaling/revocation termination.
  12. Operator recovery: Documented STUN/TURN env/CLI configuration and coturn secret rotation in `docs/deploy/HUB-SERVER-DEPLOYMENT.md`.
- **Adversarial Self-Review (10 items):**
  1. Notice `CallSignalingAndIceConfigTest` checks both active revoked caller/target refusal and active call teardown on revocation; `CallCoordinator.terminateCallsForRevokedPeer` (`CallCoordinator.kt:512-524`) and `FlashGroupCallSession` (`FlashGroupCallSession.kt:955-962`) close live 1:1 and group call legs when revocation arrives and ignore subsequent late group signaling (`OffLanCallSignalingTest.kt:205,282`).
  2. `RelayFrameRouter.handleInboundCallSignal` (`RelayFrameRouter.kt:493,1037`) uses tight 5s expiry grace (`CALL_SIGNAL_CLOCK_GRACE_MS`) instead of `CLOCK_SKEW_MS` (60s) so 10s call invites actually expire cleanly rather than staying live for 70s.
  3. `RelayFrameCodec.kt:151` validates `frame.payload.isBlank()` so whitespace-only payloads are rejected.
  4. `RelayFrameRouter.kt:500` explicitly refuses self-signaling (`signal.to == origin.deviceId`) with `ERR_BAD_REQUEST` so a client cannot loop offers onto itself.
  5. `CallCoordinator` + `OffLanCallSignalGuard` (`HubOffLanCalling.kt:203`, `OffLanCallSignalingTest.kt:161-207`) approve TTL, seq, replay, revocation, and call terminal state before admitting inbound signaling.
  6. Direct 1:1 `showCallActions` (`FlashChatHeader.kt:360`) stays enabled whenever LAN session exists even if Hub TURN is unconfigured or relay is offline.
  7. Revocation gossip observer in both `DiscoveryEngineHolder.kt:1404-1406` and `DesktopEngine.kt:1618-1620` invokes `terminateCallsForRevokedPeer` whenever a `RevocationList` revokes the active peer or org trust is lost.
  8. Native WebRTC stack (`webrtc-java`) exercised in `OffLanCallSignalingTest` with `IceTransportPolicy.Relay` (`RELAY_ONLY`), proving ICE/TURN config reaches real `RtcConfiguration`.
  9. Production TURN vendor selection (coturn vs managed TURN) and OS push wake-up service (FCM/UnifiedPush) intentionally kept out of hardcoded defaults and documented in `HUB-SERVER-DEPLOYMENT.md:415-495` for owner policy choice.
  10. Live multi-network physical Android <-> Desktop calls with live TURN relay fallback cannot be executed from this single-host workspace without two independent networks and phones; recorded in `docs/testing/TEST-BACKLOG.md` as `HR9-DEV-01..03`.
- **Extra Situations Handled (5+):**
  1. Clock-skew future timestamp (`signal.issuedAt - now > 30s`) rejected (`RelayFrameRouter.kt:494`) rather than granting arbitrarily long signal validity.
  2. Recipient outbound queue saturation returns `ERR_RATE_LIMIT` immediately without blocking the Hub event loop (`RelayFrameRouter.kt:524`).
  3. Re-fetching `/api/v1/calls/ice-config` is reset cleanly on disconnect/stop (`EnterpriseHub.kt:185-188,239,309`) and refreshed automatically on relay online transition.
  4. Cancelled/hung-up call ID cannot be resurrected by an in-flight delayed `offer`/`invite` (`OffLanCallSignalGuard`).
  5. Direct LAN calling works unchanged when peer lacks `HubContract.FEATURE_HUB_CALLING` (`"hub_calling.v1"`) capability or `/api/v1/calls/ice-config` returns `offLanCallingSupported = false`.
- **Done:** All protocol, server, calling, enterprise, repository, Android/Desktop wiring, docs, unit + native WebRTC smoke tests (`CallSignalingAndIceConfigTest`, `RelayFrameRouterTest`, `OffLanCallSignalingTest`).
- **Done but only unit/emulated-tested (`HR9-DEV-01..03` added to backlog):** Two-network live voice/video call across Android and Desktop through an isolated TURN server, plus Android Doze/background call delivery measurement.
- **Not done, and why:** Live two-network physical device + coturn relay call (no two independent networks or connected phones in this workspace); OS push wake-up (FCM/UnifiedPush) intentionally deferred behind owner service decision.
- **Surprises:** (1) Group call participant legs initially lacked revocation teardown alongside 1:1 calls (`FlashGroupCallSession` did not purge revoked participant legs until remediation commit `5904e545`); (2) `FLASH_CALL_ICE_TTL_SECONDS` env var default in `ServerConfig.kt` (`3600`, max `86400`) differed from initial deployment table draft (`300`, max `3600`), reconciled in `HUB-SERVER-DEPLOYMENT.md` & `docs/protocol.md`; (3) `HubIceConfigDto` omits `issuedAt`/`expiresAt` wrapper fields in favor of `offLanCallingSupported`, `mediaPolicy`, `ttlSeconds`, `iceServers`, reconciled in `docs/protocol.md`.
- **Decisions:** Keep call signaling strictly ephemeral on active WebSocket sessions (never mailbox-persisted); keep TURN credentials derived statelessly via RFC 5766 HMAC-SHA1 `<expiry>:<deviceId>` without sending static secret to clients; defer managed TURN provider and push wake-up to owner service selection.
- **Open risks:** Incoming off-LAN calls while Android app is background-killed or in deep Doze rely on active WebSocket connectivity (`HR9-DEV-02` measures exact behavior).

### HR10 explain-back — 2026-11-13

HR10 closes the group relay confidentiality gap noted in `FlashGroupSettingsSheet.kt` ("Group messages are not sealed yet, so the hub can read messages it carries while relaying"). While `HubChatBridge` already seals 1:1 `CHAT_FRAME` and `HUB_FILE` payloads using `HubSealing` (`SEALED_V1`, P-256 ECDH + AES-256-GCM + ECDSA envelope verified via `DirectoryHubSealing`), `sendGroup` currently passes `seal = false`, leaving `GROUP_FRAME` payloads and `GroupWireFrame` fields readable to the Hub server and lacking epoch-based key rotation or forward exclusion across Hub group membership changes.

**Threat model & Versioned Compatibility Design:**
- **Adversary / trust boundary:** Honest-but-curious or compromised Hub relay/database sees ciphertext, sender `deviceId`, recipient `deviceId` (or group route id), `sentAt`, `msgId`, and `type`, and can attempt drop, replay, reorder, delay, or inject frames, or collude with a removed/former member. Hub MUST hold zero long-term or per-epoch plaintext group message keys (`GROUP_SEALED_V1` keys are generated on member devices and delivered exclusively pairwise-sealed via `HubSealing` to verified, non-revoked active members).
- **Authenticity & binding:** Each Hub-relayed encrypted group message uses AES-256-GCM under the group's 256-bit `epochKey` with random 96-bit nonce and AAD binding `"flash-group-e2ee-v1|<groupId>|<epoch>|<senderDeviceId>|<msgId>|<sentAt>|<innerType>"`, PLUS an ECDSA-P256 author signature over the envelope header/ciphertext metadata (or inner `GroupWireFrame` author binding + `HubSealing` pairwise or epoch-key-distribution verification via verified directory identity key + pins). Because pairwise `HubSealing` (`SEALED_V1`) already authenticates `(senderDeviceId -> recipientDeviceId)` via ECDSA with `DirectoryHubSealing` TOFU+directory identity checks, epoch key distribution (`GROUP_KEY_DIST_V1`) travels inside `HubSealing` (`SEALED_V1`), authenticating the distributor against active admin/member standing and group roster/charter!
- **Epochs, rotation, join, rejoin & history visibility:**
  - Group keys live in a dedicated persistent table/store (`hub_group_keys` or `GroupEpochKeyEntity`: `(groupId, epoch, keyBytes, createdBy, createdAtMs, memberSnapshotHash, active)`).
  - Epoch monotonically advances on any active membership change (member added, member removed, member left, or rejoin). When membership shrinks (remove/leave), the local sender immediately mints `epoch + 1` (fresh CSPRNG 32-byte AES-256 key), marks `epoch` send-retired for new messages, retains prior held `epoch` keys ONLY for decrypting/verifying messages already legitimately received/sent during epochs when this device was an active member (preserving existing user-local history privacy policy without uploading past plaintext history to new/rejoined members), and distributes `epoch + 1` exclusively via pairwise `HubSealing` to the new active member set.
  - **Join & rejoin:** Newly joined or rejoined members receive ONLY the current `epoch` key (zero pre-join or away-gap plaintext history keys are ever pushed via Hub; zero cloud history dump invariant preserved). Out-of-order deliveries for an earlier legitimate epoch still decrypt only if this device was an active member when that epoch existed locally; messages sealed for an epoch where a removed member is excluded cannot be opened by the removed member (neither present nor future epochs).
  - **Simultaneous membership changes / concurrent epoch rotation:** Key distribution carries `(groupId, epoch, rosterVersion, keyId, distributorDeviceId, activeMembersSorted)`. If two members concurrently mint `epoch = N`, deterministic tie-break (highest `rosterVersion`, then newest timestamp/lexicographic `keyId`, or storing `(groupId, epoch, keyId)` keyed by `keyId` for decryption while selecting canonical active send `keyId`) prevents split-brain permanent message loss among authorized active members while still excluding non-members.
- **Versioned compatibility & fail-closed rule:**
  - Encrypted group relay frames use inner/wire envelope type `GROUP_SEALED_V1` (with `v=1`, `groupId`, `epoch`, `keyId`, `senderId`, `nonceB64`, `ctB64`, `sigB64` / `GROUP_KEY_DIST_V1` inside `HubSealing.ENVELOPE_TYPE`).
  - Older clients / mixed membership without `HubSealing` public keys or without `hge1` group-E2EE capability: evaluated explicitly via `GroupEncryptionCompatibility` (`ENCRYPTED_READY`, `LEGACY_MEMBERS_PRESENT`, `UNSEALABLE_KEY_CHANGED`, `IDENTITY_MISMATCH`). By default for E2EE-enabled groups (all linked groups under HR10 policy), sending **fails closed** (holds message queued / surfaces honest compatibility reason in `FlashGroupSettingsSheet` and delivery note rather than silently downgrading to readable `GROUP_FRAME` plaintext!). Legacy readable `GROUP_FRAME` / `LEGACY_GROUP` inbound frames remain strictly gated and never accepted into an E2EE-enforced group once upgraded (optional explicit migration/legacy read-only indicator clearly shown; no silent plaintext fallback).

Files to touch:
- `core/security/.../enterprise/GroupRelaySealing.kt`: Stateless AES-256-GCM + ECDSA group epoch payload codec (`GROUP_SEALED_V1` encode/decode/sign/verify, golden vector friendly, AAD binding `groupId`, `epoch`, `keyId`, `senderId`, `msgId`, `sentAt`, `innerType`).
- `core/persistence/.../dao/` + `entity/` + migrations (if Room schema needs epoch key store, OR `HubGroupEpochKeyStore` backed cleanly via Room / existing storage) -> check `AppDatabase` version/migrations before adding tables!
- `core/hub-protocol/...`: Capability flag `FEATURE_GROUP_E2EE = "hge1"` if advertised in directory/hello.
- `core/messaging/.../hub/HubGroupEpochManager.kt` + `HubChatBridge.kt` + `RealFlashChatRepository.kt`: Manage group epoch keys, rotate on membership change/rejoin, distribute via pairwise `HubSealingPort`, encrypt outbound `GROUP_SEALED_V1` (fail closed), decrypt & authenticate inbound `GROUP_SEALED_V1`, reject wrong epoch/scope/signer/replay/removed member, track compatibility state (`GroupE2eeStatus`).
- `ui/chat/.../FlashGroupSettingsSheet.kt` + `app`/`desktop`: Update confidentiality disclosure dynamically (end-to-end encrypted once active, explicit legacy/unencrypted warning only when degraded/legacy) and expose compatibility/key-rotation state honestly.
- `docs/security.md` & `docs/protocol.md`: Update group E2EE threat model, wire envelope spec, and claims.
- Tests: `core/security/.../GroupRelaySealingTest.kt`, `core/messaging/.../hub/HubGroupE2eeTest.kt`, `server/.../RelayFrameRouterTest.kt` (proving server relays opaque ciphertext without keys).

Three most similar existing classes read:
1. `HubSealing.kt` (`core/security/enterprise/`): Pairwise ECDH + AES-GCM + ECDSA envelope with AAD binding and `DirectoryHubSealing` identity/pin verification; reused directly to distribute per-epoch group keys pairwise.
2. `HubChatBridge.kt` (`core/messaging/hub/`): `sendPayload`, `handleInbound`, `HubFreshness`, and `FAIL_CLOSED` `HUB_FILE` pattern (refuse plaintext even if `contentViaHub()` is enabled).
3. `RealFlashChatRepository.kt` + `HubGroupRosters.kt`: `projectHubRosters`, `isActiveGroupMember`, `sendGroupViaPaths`, and `SEND` outbox drain.

Five failure modes:
1. Silent downgrade to readable `GROUP_FRAME` when a member has no sealing key or fails key change check: Prevented by fail-closed E2EE gate returning queued/blocked compatibility error and never falling back to `contentViaHub()`.
2. Removed member retains or fetches messages from new epoch: Prevented by immediate epoch rotation on removal/leave and pairwise key distribution exclusively to active authorized members.
3. Stale/replayed/cross-group ciphertext injected into wrong group or sender impersonated: Prevented by binding `groupId`, `epoch`, `keyId`, `senderId`, `msgId`, `sentAt` into AES-GCM AAD + ECDSA signature verified against directory/roster identity key + replay dedupe.
4. Hub server inspects or stores plaintext in relay/mailbox: Prevented because Hub only transports opaque `GROUP_SEALED_V1` ciphertext + wrapped `SEALED_V1` key dist envelopes and holds zero group keys.
5. Simultaneous membership changes split group keys across two valid admins/members: Prevented by keying decryption lookup by immutable `keyId` (retaining legitimate overlapping concurrent keys briefly within active set) while converging outbound encryption on deterministic latest epoch/keyId.

Tests to write first:
1. `GroupRelaySealingTest`: AES-256-GCM roundtrip, golden vector check, wrong epoch / wrong groupId / wrong senderId / tampered ciphertext / invalid signature / malformed frame rejection.
2. `HubGroupE2eeTest`: Hub relay inspects zero plaintext (only opaque ciphertext), removed member cannot decrypt post-removal epochs, member add/rejoin cannot access pre-join history via key push, concurrent epoch rotation converges cleanly, out-of-order delivery across two legitimate epochs succeeds, offline group send/rotate queues and completes on reconnect, mixed legacy/missing-key peer fails closed and reports compatibility status without plaintext leakage.
3. Server relay proof test in `RelayFrameRouterTest` / `CallSignalingAndIceConfigTest`: Hub routes `GROUP_SEALED_V1` envelopes end-to-end and stored mailbox payload contains zero plaintext substring.

### HR10 Implementation Report — 2026-11-13

- **Scope & files:**
  - `core/hub-protocol/.../HubContract.kt`: added `FEATURE_GROUP_E2EE = "hge1"` and `GROUP_SEALED_ENVELOPE_TYPE = "GROUP_SEALED_V1"`.
  - `core/security/.../enterprise/GroupRelaySealing.kt`: implemented `GROUP_SEALED_V1` AES-256-GCM + binary length-prefixed AAD (`groupId`, `epoch`, `keyId`, `senderId`, `msgId`, `sentAt`, `innerType`) + ECDSA-P256 author signature codec and `GROUP_KEY_DIST_V1` codec.
  - `core/security/.../enterprise/GroupEpochKeyStore.kt`, `FileHubGroupEpochKeyStore.kt` (desktop `IdentityKeyVault` + `0600` atomic write), `AndroidHubGroupEpochKeyStore.kt` (Android Keystore `KeystoreSecretSealer` excluded from Auto Backup).
  - `core/enterprise/.../relay/KtorRelayTransport.kt`: advertises `hge1` (`FEATURE_GROUP_E2EE`) capability alongside `ann1` (`FEATURE_ANNOUNCEMENTS_V1`) and `hub_calling.v1` (`FEATURE_HUB_CALLING`).
  - `core/messaging/.../hub/HubGroupE2eeCoordinator.kt`, `DirectoryHubSealing.kt`, `HubChatBridge.kt`, `HubNotice.kt`, `RealFlashChatRepository.kt`: epoch lifecycle (`onMembershipChanged` on add/remove/leave/rejoin/roster update), pairwise key dist via `SEALED_V1` (`creatorDeviceId == localDeviceId`), deterministic concurrent epoch tie-break `(epoch, rosterVersion, keyId)`, fail-closed outbound/inbound gating (no silent plaintext `GROUP_FRAME` downgrade), and `GROUP_ENCRYPTED` / `GROUP_E2EE_BLOCKED` UI notices.
  - `ui/chat/.../FlashHubNotice.kt`, `FlashGroupSettingsSheet.kt`, `FlashConversationScreen.kt`, `app/.../DiscoveryEngineHolder.kt`, `desktop/.../DesktopEngine.kt`: end-to-end host wiring and truthful group confidentiality notices.
  - `docs/security.md`, `docs/protocol.md`, `docs/testing/TEST-BACKLOG.md`: updated threat model, wire spec, and `HR10-DEV-01`.
- **12 Quality Contract categories:**
  1. Normal use: Multi-member encrypted relay (`HubGroupE2eeTest`, `GroupE2eeRelayOpacityTest`), Android + Desktop notice copy (`FlashHubNoticeMathTest`, `HubNoticeMathTest`).
  2. Failure paths: Unavailable sealing key / wrong vault / out-of-order missing epoch (`RETRY_LATER`, then decrypt after key delivery).
  3. Hostile input: Malformed envelopes, swapped group/epoch/msgId/sentAt, author signature spoofing, creator spoofing (`GroupRelaySealingTest`, `HubGroupE2eeTest`).
  4. Concurrency: Mutex-guarded epoch state + concurrent simultaneous epoch minting converging via `(epoch, rosterVersion, keyId)`.
  5. Lifecycle & restart: `FileHubGroupEpochKeyStoreTest` + `simultaneousRotationOutOfOrderDeliveryAndOfflineRestartAllConverge` restart continuity check (discovered & fixed `creatorDeviceId != localDeviceId` re-distribution bug during restart!).
  6. Scale & bounds: `MAX_MEMBERS = 512`, key list bounded per epoch without leaking historical keys to newcomers.
  7. Security & mutation check: Temporarily disabled `if (groupE2ee != null && !sealed)` plaintext downgrade guard in `HubChatBridge`; `HubGroupE2eeTest` failed (`legacyMemberOrChangedKeyOrIdentityMismatchFailsClosedWithoutPlaintextFallback` expected `REJECTED`, got `STORED`, 1/4 failed). Restored exact guard and re-ran `HubGroupE2eeTest`, `HubSealingHostFlowTest`, `HubNoticeMathTest` -> 0 failures.
  8. Compatibility: Honest `GroupE2eeCompatStatus` (`LEGACY_MEMBERS_PRESENT`, `BLOCKED_KEY_CHANGED`, `BLOCKED_IDENTITY_MISMATCH`) fails closed instead of silent downgrade.
  9. Both hosts: `:desktop:compileKotlinJvm` & `:app:compileDebugKotlin` both pass with host Keystore/Vault stores wired.
  10. UI / accessibility: Updated `FlashHubNoticeMath` & `FlashGroupSettingsSheet` with calm, protocol-free E2EE / paused notices (`FlashHubNoticeMathTest` enforces zero protocol words).
  11. Observability: `_droppedPlain` counter + structured non-secret logs.
  12. Operator recovery: Hub sees no keys/plaintext (`GroupE2eeRelayOpacityTest`), backup-excluded device keystores.
- **Verification commands & observed counts:**
  - `.\tools\flash-gradle.cmd :core:security:jvmTest :core:messaging:jvmTest --tests "com.transfer.flash.core.messaging.hub.HubGroupE2eeTest" --tests "com.transfer.flash.core.messaging.hub.HubNoticeMathTest" --tests "com.transfer.flash.core.messaging.hub.HubChatBridgeTest" --tests "com.transfer.flash.core.messaging.hub.HubSealingHostFlowTest" :ui:chat:jvmTest :server:test --tests "com.transfer.flash.server.relay.GroupE2eeRelayOpacityTest" :desktop:compileKotlinJvm :app:compileDebugKotlin` -> `BUILD SUCCESSFUL` (`exit 0`, 139 tasks). XML suites: `GroupRelaySealingTest` (`5/5`), `FileHubGroupEpochKeyStoreTest` (`1/1`), `HubGroupE2eeTest` (`4/4`), `HubChatBridgeTest` (`29/29`), `HubNoticeMathTest` (`6/6`), `GroupE2eeRelayOpacityTest` (`1/1`), plus `:ui:chat:jvmTest` and `:core:security:jvmTest` suites (with pre-existing Windows-skipped POSIX/Linux chmod checks in `FileHubSealingKeyStoreTest` `1 skipped` and `KeyFileVaultTest` `1 skipped`).
  - Restored mutation run: `.\tools\flash-gradle.cmd :core:messaging:jvmTest --tests "com.transfer.flash.core.messaging.hub.HubGroupE2eeTest" --tests "com.transfer.flash.core.messaging.hub.HubSealingHostFlowTest" --tests "com.transfer.flash.core.messaging.hub.HubNoticeMathTest"` -> `BUILD SUCCESSFUL` (`exit 0`).
- **Adversarial self-review (>=10 points):**
  1. Sender forgery prevented (`GroupRelaySealing.kt:202-301`, `HubChatBridge.kt:618`): `senderId` is bound in both AES-GCM AAD and ECDSA signature verified against directory-pinned identity key, not shared epoch key alone.
  2. Creator impersonation prevented (`HubGroupE2eeCoordinator.kt:254,286,295`): `acceptKeyDist` checks `dist.creatorDeviceId == fromDeviceId` and only the creator transmits `GROUP_KEY_DIST_V1` (restarted non-creators send `GROUP_SEALED_V1` directly without spoofing creator).
  3. Removed member isolation (`HubGroupE2eeCoordinator.kt:175-225,353`): `onMembershipChanged` demotes prior keys `canSend = false` and `openSealedGroup` rejects non-member sender/local membership.
  4. Pre-join/gap history protection (`HubGroupE2eeCoordinator.kt:353`, `HubChatBridge.kt:132,570`): Newcomers receive only current epoch key; prior envelopes return `RETRY_LATER` cleanly without leaking plaintext or epoch keys.
  5. Downgrade resistance (`HubChatBridge.kt:330,403,503`): `contentViaHub` opt-in NEVER bypasses E2EE when `groupE2ee != null`, both on send (`prepareGroupSend` fails closed) and receive (`GROUP_FRAME`/legacy frames rejected).
  6. Key-bytes memory discipline (`GroupRelaySealing.kt:32-35,202-204,235,273-275,301`): `GroupEpochKey.keyBytes` always returns defensive copy and `GroupRelaySealing` zeroes its working buffer in `finally`.
  7. Disk key confidentiality (`FileHubGroupEpochKeyStore.kt:19-41`, `FileHubGroupEpochKeyStoreTest.kt:15-63`, `AndroidHubGroupEpochKeyStore.kt:31-57`): Verifies raw file contains zero `groupId`, `keyId`, or plaintext header strings and wrong vault fails closed.
  8. SQLite & server blindness (`GroupE2eeRelayOpacityTest.kt:184-236`): Inspects `mailbox_envelopes` rows directly in SQLite and confirms zero plaintext body or Base64 key bytes appear.
  9. Key-change & identity mismatch gating (`HubGroupE2eeCoordinator.kt:24-25,48-50,148-154`, `HubNotice.kt:51-52`): Directory key rotation without user confirmation or identity PIN mismatch halts group sends cleanly (`BLOCKED_KEY_CHANGED` / `BLOCKED_IDENTITY_MISMATCH`).
  10. Replay / cross-context swap resistance (`GroupRelaySealing.kt:202-301`, `HubGroupE2eeTest.kt`): Moving ciphertext across `groupId`, `epoch`, `keyId`, `msgId`, or `sentAt` fails AAD + signature verification immediately.
- **Extra situations handled (5):**
  1. Non-creator restart re-sending in adopted epoch without spurious `KEY_DIST_V1` rejection.
  2. Simultaneous concurrent epoch creation at `rosterVersion = 1` converging deterministically.
  3. Out-of-order `GROUP_SEALED_V1` arriving ahead of `GROUP_KEY_DIST_V1` -> `RETRY_LATER` then `STORED` upon key arrival.
  4. Mixed legacy group members failing closed with honest UI status and immediate recovery upon member upgrade.
  5. Offline send + app restart reloading persisted epoch keys seamlessly.
- **Status breakdown:** Done (core protocol, crypto, stores, coordinator, bridge, repository, UI notice, Android/Desktop wiring, unit + server opacity + mutation checks). Done-but-unit-tested (`HR10-DEV-01` tracks physical Android Keystore + Windows DPAPI verification).
- **Not done, and why:** Live physical multi-device Android Keystore & Desktop DPAPI vault persistence run deferred (no physical phones available in host workspace); minor companion promotion in `FileHubSealingKeyStore.kt` (`internal fun writeOwnerOnly`) and flaky wait ordering fix in `RealFlashChatRepositoryHubFileTest.kt` (moving notification count assert after `awaitUntil` receipt frame) were applied cleanly without altering security guarantees.
- **Surprises:** (1) `AndroidHubGroupEpochKeyStore.kt` originally failed to compile against nullable `ByteArray?` returned by `KeystoreSecretSealer.open(...)` — remediated with explicit fail-closed `FlashLog.e` + `IllegalStateException` in `5904e545`; (2) `FileHubGroupEpochKeyStore` and Android Keystore load paths initially swallowed vault/decrypt exceptions and returned `emptyList()`, risking silent epoch key loss/overwrite — remediated to fail closed on decrypt failure while returning `emptyList()` only when the keystore file/preference does not yet exist (`FileHubGroupEpochKeyStoreTest`); (3) Non-creator device restarts initially re-triggered `GROUP_KEY_DIST_V1` distribution until restricted to `creatorDeviceId == localDeviceId`.
- **Decisions:** Encrypt outbound `GROUP_SEALED_V1` fail-closed whenever `HubGroupE2eeCoordinator` is active (`contentViaHub` toggle cannot downgrade an E2EE group to plaintext `GROUP_FRAME`); retain prior epoch keys strictly decrypt-only while issuing only current canonical epoch keys to newly joined or rejoined members.
- **Open risks:** Legacy pre-`hge1` peers cannot exchange messages in an E2EE-enforced group until upgraded (`LEGACY_MEMBERS_PRESENT`); key-change PIN halts sending until manual confirmation (`BLOCKED_KEY_CHANGED`).

### HR11 explain-back — 2026-11-13

HR11 closes the operational monitoring and rollout-evidence gap required before fleet deployment. Today `FlashServerEngine` serves `/health`, `/health/detail`, and Prometheus `/metrics` with queue depths, oldest mailbox age, and `delegationDaysRemaining`, and `MailboxStats` tracks `quarantinedTotal()`, **but**:
- `FlashServerEngine.gauges()` and `/health/detail` omit `quarantinedTotal` (unreadable rows), disk free/used space on the Hub data filesystem, certificate/delegation expiry warning thresholds (`warn` / `critical`), and structured actionable alerts (`alerts` array / Prometheus alert gauges) so operators can alert on unreadable queues, oldest mail age, disk pressure, and expiring certificates/delegations.
- `docs/deploy/HUB-SERVER-DEPLOYMENT.md` §11 lists `/health/detail` and `/metrics` but lacks concrete alert threshold tables, alert meanings, and step-by-step operator recovery procedures for unreadable/quarantined queues, mail age spikes, disk pressure, certificate/delegation expiry rotation, and signer rotation/restore drills.
- We also need executed automated drills in `:server` covering intentionally broken/quarantined fixture recovery, backup-restore key mismatch/missing/matching keys + signer rotation/revocation + client continuity, automated `HUBREADY-01..05` / `HUBWORK` checks against disposable isolated Hub instances, and a measured synthetic `PERF-H03` host benchmark recorded in `logs/experiments.md`, while keeping physical phone / production VPS / browser checks honestly open in `TEST-BACKLOG.md`.

Files to inspect/touch:
- `server/src/main/kotlin/com/transfer/flash/server/observability/`: `OperationalMonitor.kt` (or extend `FlashServerEngine.kt`) evaluating threshold rules for quarantined rows, oldest queued mail age, filesystem disk pressure (`FileStore` usable/total bytes), and delegation/TLS cert expiry hours, plus `RelayMetrics.Gauge` metrics and structured `alerts` in `/health/detail`.
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`: wire `dataDir` disk probe, `quarantinedTotal` gauge & `/health/detail` fields, alert evaluation, and overall `healthLevel()` (`DEGRADED` when critical alerts fire while DB stays up).
- `server/src/main/kotlin/com/transfer/flash/server/HubServices.kt` / `config/ServerConfig.kt` (if thresholds/dataDir need passing into `FlashServerEngine`).
- `docs/deploy/HUB-SERVER-DEPLOYMENT.md`: document every monitoring gauge/detail field, exact warn/critical thresholds, what each alert means, and concrete operator recovery commands/steps.
- `server/src/test/kotlin/com/transfer/flash/server/ops/OperationsAndRolloutDrillsTest.kt`: automated drills for quarantine alert + broken fixture recovery, oldest-mail / disk-pressure / expiry threshold alerts + mutation check, backup restore (matching, missing, wrong `HubDataKey`), signer rotation/revocation + client continuity, isolated `HUBREADY-01..05` & `HUBWORK` drills, and a bounded synthetic `PERF-H03` benchmark (recorded in `logs/experiments.md`).

Three most similar existing classes:
1. `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt` (`/health/detail` & `gauges()`): extends directly with `quarantinedTotal`, disk metrics, and evaluated `alerts` using `ManagementGuard`.
2. `server/src/main/kotlin/com/transfer/flash/server/observability/MailboxStats.kt` & `RelayMetrics.kt`: reuses lock-free gauges and `quarantinedTotal()` already exposed by `MailboxQueue`.
3. `server/src/test/kotlin/com/transfer/flash/server/DataAtRestTest.kt` / `MainCommandsTest.kt`: reuses Hub startup/restore preflight and backup/key validation patterns.

Five failure modes to guard:
1. Event-loop blocking on `/health/detail` or `/metrics`: file-store / DB probes must stay fast/bounded and guarded by `ManagementGuard` (never exposed publicly on `/health`).
2. Secret/credential leakage in alerts: alert details report device counts, hours remaining, byte percentages, and error categories — NEVER keys, tokens, file contents, or plaintext messages.
3. False-negative `OK` status during critical disk/quarantine/expiry conditions: critical alerts must flip `/metrics` alert state + `/health/detail` status cleanly without falsely taking down live WebSocket relays unless DB itself fails (`DOWN` = 503 only when DB cannot answer).
4. Backup restore key swap silently minting a new key or corrupting existing ciphertext: `HubDataKey` verifier gate must remain fail-closed (`wrong`/`missing` key refuses boot while matching key + rotated delegation restores continuity).
5. Unbounded disk usage / stale-mail blindness when a single recipient stays offline forever: `oldestAgeSeconds` and `diskUsedRatio` / `diskFreeBytes` warn before hard `ENOSPC`.

Tests to write first:
- `OperationsAndRolloutDrillsTest.kt`: alert evaluation + `/health/detail` & `/metrics` assertions on intentionally quarantined SQLite rows, aged queued mail, low-disk simulated probe, expiring delegation/cert hours, restore drills (matching/missing/wrong key + signer rotation/revocation + client auth/relay continuity), isolated `HUBREADY-01..05` API drills, and synthetic `PERF-H03` measurement.

### HR11 Implementation Report (Automated Drills & Runbook; Live VPS/Fleet Soak Owed)

#### What Changed
- `server/src/main/kotlin/com/transfer/flash/server/observability/OperationalMonitor.kt`: Added `DiskUsage`, `OperationalThresholds`, `AlertSeverity`, `OperationalAlert`, `OperationalMonitor.probeDirectory(...)`, and `OperationalMonitor.evaluate(...)` covering:
  - `unreadable_queue` (warn `>= 1`, critical `>= 10` quarantined rows)
  - `oldest_queued_mail` (warn `>= 259200s` / 72h, critical `>= 518400s` / 144h)
  - `disk_pressure` (warn `(>= 80% used AND <= 4 GiB free) OR <= 512 MiB free`, critical `(>= 92% used AND <= 1 GiB free) OR <= 128 MiB free` — avoids false critical alerts on large developer disks that still have tens of GiB free)
  - `delegation_expiring` (warn `<= 14d`, critical `<= 3d` remaining)
  - `tls_cert_expiring` (warn `<= 14d`, critical `<= 3d` remaining)
- `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`: Wired `tlsDaysRemaining`, `diskUsageProvider` (probing `config.dataDir`), and `operationalThresholds` into `healthLevel()`, Prometheus `gauges()` (`flash_mailbox_quarantined_total`, `flash_disk_*`, `flash_tls_cert_days_remaining`, `flash_alerts_warning`, `flash_alerts_critical`, `flash_alert_*`), and `/health/detail` (`quarantinedTotal`, `disk`, `tlsDaysRemaining`, `alerts`).
- `docs/deploy/HUB-SERVER-DEPLOYMENT.md`: Documented `### Actionable Monitoring Thresholds & Operator Alerts (HR11)` with exact metric names, warn/critical thresholds, operator meaning, remediation steps, and `/health` (`degraded` vs `down`) semantics.
- `docs/protocol-routes.txt`: Added missing HR6 (`POST /admin/api/v1/devices/{id}/replace`) and HR9 (`GET /api/v1/calls/ice-config`) routes to keep `RouteTableContractTest` green.
- `server/src/test/kotlin/com/transfer/flash/server/ops/OperationsAndRolloutDrillsTest.kt`: Added 4 end-to-end operational & rollout tests (`operationalMonitoringExposesQuarantineMailAgeDiskAndCertAlertsWithRecovery`, `backupRestoreRefusesMissingOrWrongDataKeyAndRecoversEnrolledClientsAcrossSignerRotation`, `isolatedHubReadyAndHubWorkEndToEndLifecycleDrills`, `syntheticPerfH03CapacityAndLatencyBenchmark`).

#### 12 Coverage Categories
1. **Normal use:** Healthy `/health` & `/health/detail` with zero alerts; `HUBREADY-01..05` isolated lifecycle (health/metrics -> admin login -> invite/enroll -> directory sync -> announcement -> message relay + offline queue -> device revoke); synthetic 50-device/500-frame `PERF-H03`.
2. **Failure / error paths:** 12 corrupted/wrong-key mailbox rows quarantined; missing `data.key` and wrong `data.key` both throw `HubStartupException` fail-closed at startup; stale delegation serial #1 rejected `403 ERR_REVOKED` after signer revocation.
3. **Hostile / malformed input:** Corrupt/un-decryptable AEAD mailbox ciphertexts quarantined safely instead of crashing drains; anonymous/proxied callers still blocked from `/health/detail` and `/metrics` (`FlashServerEngineTest`).
4. **Concurrency:** `PERF-H03` dispatches 500 concurrent/sequenced relay frames across 50 registered WebSocket pools (40 connected + 10 offline queued = 200 persisted mailbox rows) under lock-free gauge tracking.
5. **Lifecycle / restart:** Database close -> missing/wrong `data.key` refusal -> matching `data.key` restore -> delegation #2 hot rotation -> serial #1 revocation restart -> continued auth & sync for serial #2 enrolled devices.
6. **Scale / backpressure / quotas:** Synthetic `PERF-H03` measured on Windows host JVM (`devices=50`, `messages=500`, `elapsedMs=738.4`, `throughput=677.2 msg/s`, `p50Us=722.3`, `p99Us=5008.3`, `mailboxQueued=200`, `heapDeltaKb=-9786`).
7. **Security invariants & mutation proof:** Guarded `/health/detail` & `/metrics` keep credentials/keys absent from alert payloads; `HubDataKey` startup preflight refuses key mismatch; **mutation check:** temporarily disabled `quarantinedRows` critical/warn branches in `OperationalMonitor.evaluate` (`false &&`), ran `:server:test --tests "...OperationsAndRolloutDrillsTest"` -> `operationalMonitoringExposesQuarantineMailAgeDiskAndCertAlertsWithRecovery` failed at line 229 (`unreadable_queue` missing); restored check -> all 4 tests passed (`16 tests` green with `FlashServerEngineTest` + `RouteTableContractTest`).
8. **Compatibility:** `RouteTableContractTest` aligned with HR6 & HR9 REST surface; non-revoked delegation rotation preserves continuity for existing enrolled clients while enforcing revocation once old signer serials are revoked.
9. **Both hosts:** Server observability, SQLite quarantine, and restore gates cover Windows & Linux JVM; Android/Desktop clients share `HubAuth`/REST sync compatibility verified in the cross-module gate.
10. **UI / accessibility:** N/A for server-only monitoring & restore code; Admin Console overview/files/devices metrics remain accessible through existing approved Console pages (`HUBWORK-*` interactive Chrome/VPS acceptance tracked in `HR11-ENV-01`).
11. **Observability:** Prometheus gauges + structured JSON alerts in `/health/detail` (`/health` public status stays minimal: `"ok"` / `"degraded"` / `"down"`).
12. **Operator recovery:** Verified operator recovery loop clears alerts (`liveQueue.acknowledge`, expanded disk headroom, rotated delegation/TLS days -> `cleanAlerts.isEmpty()`), and runbook in `docs/deploy/HUB-SERVER-DEPLOYMENT.md` defines exact CLI/console recovery actions.

#### Adversarial Self-Review (>= 10 items)
1. **Large dev disk false-positive (`OperationalMonitor.kt:74-105`):** Initial `usedPercent >= 92%` rule flagged `disk_pressure CRITICAL` on a 255 GB Windows drive with 15.6 GiB free; bounded percentage alerts with `<= 4 GiB` warn / `<= 1 GiB` critical ceilings alongside unconditional `512 MiB` / `128 MiB` floors.
2. **Public `/health` leak check (`FlashServerEngine.kt:242-265`):** Verified `/health` returns only `{"status":...}` without exposing `alerts[]`, queue depths, paths, or counts (those require `ManagementGuard` on `/health/detail` & `/metrics`).
3. **Log/alert secret leakage (`OperationalMonitor.kt:85-160`, `FlashServerEngine.kt:337,375,405-430`):** `OperationalMonitor` summaries contain counts, seconds, MiB, and days only — never `data.key`, bearer tokens, device keys, or message plaintext.
4. **Silent new `data.key` mint on restore (`HubDataKey.kt:65`, `OperationsAndRolloutDrillsTest.kt:262,293,301`):** Verified `HubServices.build` throws `HubStartupException` when `data.key` is missing or replaced with a different 32-byte key while `flash-hub.db` already has data.
5. **Quarantine alert recovery after TTL/ack (`MailboxQueue.kt:536`, `OperationsAndRolloutDrillsTest.kt:228,252`):** Checked `MailboxQueue.quarantinedTotal()` drops back to clear state when stale/corrupt rows are swept or DB is restored cleanly.
6. **Stale delegation after emergency revocation (`SqliteRevocationService.kt:269-276,377`, `OperationsAndRolloutDrillsTest.kt:342`):** Confirmed `revokedDelegationSerials` rejects clients still presenting serial #1 with `403 ERR_REVOKED`, while clients holding serial #2 continue syncing.
7. **Non-revoked graceful rotation continuity (`OperationsAndRolloutDrillsTest.kt:262-355`):** Confirmed deploying delegation #2 before revoking #1 keeps previously enrolled serial #1 clients authenticated and syncing without forced outage.
8. **Missing route contract entries (`docs/protocol-routes.txt`, `RouteTableContractTest.kt:17`):** Caught HR6 (`replace`) & HR9 (`ice-config`) omissions in `docs/protocol-routes.txt` via `RouteTableContractTest` and restored 1:1 parity.
9. **Synthetic vs production `PERF-H03` honesty (`OperationsAndRolloutDrillsTest.kt:461`, `logs/experiments.md`):** Marked `syntheticPerfH03CapacityAndLatencyBenchmark` explicitly as an in-process synthetic Ktor/JVM measurement (`EXP-143`), keeping real VPS + TLS + coturn fleet soak open under `HR11-ENV-02`.
10. **Windows filesystem probe edge (`OperationalMonitor.kt:74-84`, `FlashServerEngine.kt:144`):** Verified `OperationalMonitor.probeDirectory` handles missing leaf paths by walking `parentFile` until an existing mount exists, returning `null` safely rather than throwing on unmounted paths.

#### Extra Situations Considered (>= 5 items)
1. Pre-existing empty `dataDir` before first SQLite file write (falls back cleanly to `0` queued rows and parent mount free space).
2. Mixed alert severities (e.g., 4 CRITICAL + 1 WARN simultaneously active) reporting accurate `flash_alerts_critical` and `flash_alerts_warning` counts.
3. Database down vs critical operational alerts (`/health` returns `503` `"down"` when DB probe fails, vs `200` `"degraded"` so admin API recovery stays reachable on disk/quarantine/cert alerts).
4. Offline recipient queueing during `PERF-H03` burst (10 of 50 recipients offline -> 200 messages cleanly durable in `mailbox_envelopes`, 300 immediate WebSocket deliveries).
5. Remaining physical/operator rollout backlog split cleanly into `HR11-ENV-01` (disposable VPS + Chrome console + TLS cert + restore) and `HR11-ENV-02` (production-profile `PERF-H03` with coturn).

#### Done / Not Done / Surprises / Decisions / Open Risks
- **Done:** `OperationalMonitor.kt`, `FlashServerEngine.kt` (`/health`, `/health/detail`, `/metrics`), `docs/deploy/HUB-SERVER-DEPLOYMENT.md` §11 thresholds & runbook, `docs/protocol-routes.txt`, `OperationsAndRolloutDrillsTest.kt` (quarantine alert & recovery, aged mail/disk/expiry alerts + mutation check, `data.key` mismatch/missing/matching restore + signer rotation/revocation, isolated `HUBREADY-01..05` drills, synthetic `PERF-H03` recorded as `EXP-143` in `logs/experiments.md`).
- **Done but only isolated/synthetic-tested (`HR11-ENV-01`, `HR11-ENV-02` in `TEST-BACKLOG.md`):** Automated `HUBREADY-01..05`, restore drills, and synthetic 50-device/500-frame `PERF-H03` in `:server:test`.
- **Not done, and why:** Real isolated VPS deployment + Chrome console interactive clickthrough (`HR11-ENV-01`) and multi-host fleet soak + coturn (`HR11-ENV-02`) remain open because assignment rules prohibit live host/device mutations and external infra provisioning without owner authorization.
- **Surprises:** (1) Initial `usedPercent >= 92%` disk alert produced false-positive `CRITICAL` on a large developer drive with >15 GiB free — bounded by `<= 4 GiB` warn / `<= 1 GiB` critical ceilings alongside unconditional `512 MiB` / `128 MiB` free floors; (2) `RouteTableContractTest` flagged missing HR6 `POST /admin/api/v1/devices/{id}/replace` & HR9 `GET /api/v1/calls/ice-config` rows in `docs/protocol-routes.txt` — synced immediately; (3) `FlashServerEngine.healthDetailJson()` emits `"quarantinedTotal"` inside `"mailbox":{...}` rather than top-level, clarified explicitly in `docs/protocol.md` & `HUB-SERVER-DEPLOYMENT.md`.
- **Decisions:** Return HTTP `200` with `"status": "degraded"` when operational `critical` alerts fire while SQLite stays reachable so `/admin` recovery endpoints stay accessible, reserving HTTP `503` `"down"` strictly for DB failure.
- **Open risks:** Production VPS hardware sizing, live Chrome visual verification, and multi-network TURN throughput soak remain unverified until `HR11-ENV-01/02`.

#### Verification
- Targeted HR11 suite (after final code edit + mutation check restore): `OperationsAndRolloutDrillsTest` (4/4), `FlashServerEngineTest` (10/10), `RouteTableContractTest` (2/2) -> `16/16` passed; full cross-module integration gate follows next.

---

## 2026-11-14 Audit Remediation Summary (`docs/audit/2026-11-14-hub-completion-review.md`)

All 8 findings (6 P1, 2 P2) raised during the independent completion review were resolved on `agent/hub-readiness`:

1. **HR6 — Finding #1 & #2 (Atomic replacement ledger, lock, quota/scope/privilege order, crash & pending-grant recovery):**
   - Added SQLite `hub_device_replacements` (`IdentityStore` Migration 4) paired with per-device locking (`replaceLocks`) in `AdminApi.kt` (`POST /admin/api/v1/devices/{id}/replace`) and `IdentityStore.beginDeviceReplacement`.
   - Enforced department scope, target department/group membership validation, org-admin-only checks (old/target admin roles, approver, enrolled, linked admin bindings), and `remainingQuotaAssumingRevoked` before mutating credentials or revocations; retries while a grant is pending return `409 Conflict` surfacing `replacementInviteId` via `GET /admin/api/v1/devices/{id}` for operator revoke + re-issue, and `IdentityStore.reconcileInterruptedReplacements` + `IN_PROGRESS_LEASE_MS` recover orphaned crash states (`DeviceReplacementTest`).
2. **HR7 — Finding #3 (`expectedEpoch` full-resync detection on changes feed):**
   - Added `expectedEpoch` query parameter across `AnnouncementRoutes`, `AnnouncementStore.getChangesFeed`, `HubAnnouncementsApi`, `RealHubAnnouncementsApi`, `FakeHubAnnouncementsApi`, and `RealFlashChatRepository.refreshHubAnnouncements`, forcing `fullResync = true` when the Hub reset/recreated its feed epoch so notifications stay suppressed (`docs/protocol.md`, `AnnouncementClientContractTest`, `RealFlashChatRepositoryAnnouncementsTest`).
3. **HR8 — Finding #4 (Durable continuity checkpoints + normal UI `sendFile`/`resumeTransfer` `LAN <-> Hub` handover):**
   - Added atomic disk persistence (`FileContinuityCheckpointStore` in `noBackupFilesDir/transfer-continuity` on Android and `stateDir/transfer-continuity` on Desktop) storing verified spans, `lanPartialPath`, `senderSourceFingerprint`, and cancel/complete fences across restart.
   - Generated native 32-hex `wireFileId` values for 1:1 LAN sends (`RealFlashTransferRepository.newWireFileId`), wired `TransferRouteFallback` (`HubFileBinding.tryHandoverFromLan` & `tryHandoverOutToLan`) directly into normal `sendFile` / `resumeTransfer` flows, preserved exact `(transferId, wireFileId)`, verified source fingerprints pre-resume, and proved `LAN -> Hub`, `Hub -> LAN`, restart recovery, and changed-source / oversized explicit refusal in `CrossRouteWiringIntegrationTest`.
4. **HR9 — Finding #5 & #7 (Pre-replay inner frame validation & proactive ICE config renewal):**
   - Validated inner `FLASH_CALL` frame parsing, `callId` match, sender match, non-terminal state, and monotonic `seq` before recording `signalId` into the replay set (`CallCoordinator.onInboundOffLanSignal`, `HubOffLanCalling.validateInbound`, `OffLanCallSignalingTest`).
   - Added `EnterpriseHub.ensureFreshCallIceConfig` with proactive renewal/retry on Android and Desktop (`DiscoveryEngineHolder`, `DesktopEngine`, `DesktopShell`, `EnterpriseHubTest`).
5. **HR10 — Finding #6 (Bounded epochs & deterministic canonical tie-breaking):**
   - Enforced `GroupRelaySealing.MAX_VALID_EPOCH = 1_000_000_000L`, bounded epoch/roster admission, and aligned canonical key selection tie-breaking on `(rosterVersion, epoch, keyId)` (`HubGroupE2eeCoordinator`, `HubGroupE2eeTest`).
6. **HR11 — Finding #8 (Durable quarantined backlog observability):**
   - Added `MailboxStats.quarantinedOutstanding()` backed by `MailboxQueue`, wired into `OperationalMonitor.evaluate` (`unreadable_queue`) and `FlashServerEngine` (`/health/detail` + `flash_mailbox_quarantined_outstanding`), with regression coverage in `OperationsAndRolloutDrillsTest`.

### Adversarial Self-Review & Integration Overlap
- **Lazy-pattern scan notes (`git diff 256e140d..HEAD -U0`):** Every production `runCatching { ... }` in the remediation diff is strictly intentional non-fatal best-effort cleanup or rollback compensation (`TransferContinuityCoordinator.kt` deleting a duplicate/temp partial file when losing a route race; `RealFlashTransferRepository.kt` dismissing/clearing local chunk progress mirrors when externally retiring a transfer row; `AdminApi.kt` revoking a newly created invite when post-issue revocation fails before returning `503`); test fixture `"temporary"` in `EnterpriseHubTest.kt` simulates an HTTP 503 response payload.
- **Overlapping main-checkout edits (`C:\Users\KaliOxygen\Downloads\Flash` on `dev`, all 18 files across `0b162b80..HEAD`):** `app/src/main/java/com/transfer/flash/MainActivity.kt`, `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`, `app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt`, `app/src/test/java/com/transfer/flash/notifications/FlashNotificationContentTest.kt`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt`, `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopNotificationManager.kt`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt`, `desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopNotificationManagerTest.kt`, `docs/protocol-routes.txt`, `docs/testing/TEST-BACKLOG.md`, `logs/handoff.md`, `logs/progress.md`, `server/src/main/kotlin/com/transfer/flash/server/auth/EnrollmentService.kt`, `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt`, `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt`, `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/settings/FlashSettingsScreen.kt` (remediation delta `256e140d..HEAD` subset: `DiscoveryEngineHolder.kt`, `CallCoordinator.kt`, `RealFlashTransferRepository.kt`, `DesktopShell.kt`, `TEST-BACKLOG.md`, `logs/handoff.md`, `logs/progress.md`, `EnrollmentService.kt`, `FlashServerEngine.kt`). All 18 must be three-way merged cleanly by the owner when integrating `agent/hub-readiness`.
- **Verified Post-Remediation Gates (after final code edit, `--rerun` / `--rerun-tasks`):**
  - `node --no-warnings --test server/admin-ui-tests/*.test.mjs`: `87 tests, 87 passed, 0 failed, 0 skipped`.
  - `tools\flash-gradle.cmd --max-workers=2 :server:test --rerun`: `76 suites, 703 tests, 0 failures, 0 errors, 0 skipped`.
  - `tools\flash-gradle.cmd :core:common:jvmTest :core:hub-protocol:jvmTest :core:security:jvmTest :core:discovery:jvmTest :core:persistence:jvmTest :core:enterprise:jvmTest :core:network:jvmTest :core:transfer:jvmTest :core:messaging:jvmTest :core:calling:jvmTest :core:engine:jvmTest :ui:chat:jvmTest :desktop:jvmTest :app:testDebugUnitTest :app:assembleDebug --rerun-tasks`: `394 suites, 3,311 tests, 0 failures, 0 errors, 4 pre-existing POSIX skips on Windows` (`:core:security:jvmTest` 2, `:desktop:jvmTest` 2) + `:app:assembleDebug` `BUILD SUCCESSFUL`.
  - Combined Gradle + Node total: `4,101 tests, 0 failures, 0 errors, 4 skipped`.
- **Residual open items:** Physical-device / isolated-VPS / Chrome console / coturn acceptance tests (`HR6-DEV-01 / ORGT-14`, `HR7-DEV-01`, `HR8-DEV-01`, `HR9-DEV-01`, `HR9-DEV-02`, `HR10-DEV-01`, `HR11-ENV-01`, `HR11-ENV-02`) remain `TODO` in `docs/testing/TEST-BACKLOG.md`.
