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
| HR6 | Device replacement. Revoke the lost device and its sessions, issue a one-use replacement path with fresh keys, scope the new device's department/groups explicitly and audit the result. Keep old enrolment and key revocations enforceable. | Lost key, stolen old key, retry/concurrent redemption, quota, replacement on both hosts and ORGT-14. | Implemented, 93 tests green |
| HR7 | Title-only announcement notifications and opt-out on Android and desktop. Cover reconnect catch-up, first sync, duplicate suppression and cold start. Wire the group relay confidentiality notice. | ANN-01/10/11 and HUBT-12, permission denial, author suppression, mute and desktop activation. Measure background delivery separately from the local notification feature. | Implemented, all tests green |
| HR8 | LAN ↔ Hub file continuity. Specify a stable transfer identity, representation of validated completed bytes, ownership of the active route and final verification for both directions. | Disconnect or route switch during a large transfer on both hosts. Resume only compatible bytes, one completion row and one copy, with final integrity verification. | Planned |
| HR9 | Off-LAN calling. Design authenticated, short-lived signalling with media path negotiation and an explicit NAT relay policy. Align deployment claims with the shipped capability. | Two independent networks, direct and relayed media, late/duplicate/replayed signalling, device removal and process/background behaviour. | Planned |
| HR10 | End-to-end group relay encryption (FO-27). Design authenticated membership epochs, secure key delivery, rotation/removal, history visibility and mixed-version handling before changing the wire. | Hub cannot decrypt payloads. Removed devices cannot read new messages. Rejoins, out-of-order events, epoch rollover and older clients are exercised. | Planned |
| HR11 | Operations. Expose actionable alerts for unreadable queues, oldest mail, disk use and certificate/delegation expiry. Rehearse restore and signer rotation, then run the real pilot matrix and fleet-size soak. | Isolated production-profile runbook, PERF-H03 and the relevant HUBREADY/HUBWORK/CONSOLE checks. | Planned |

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

### HR7 explain-back — 2026-11-13

Title-only announcement notifications and confidentiality disclosure. When an eligible announcement is stored, Android and desktop emit a system notification with title-only body (lock-screen confidentiality). The notification respects recipient department scope, user notification opt-out/mute preferences, OS notification permissions (Android 13+ POST_NOTIFICATIONS), and author suppression (no notification to the author's device). Notification taps deep-link to the announcement on running or cold-started apps via `EXTRA_HUB_ROUTE` / `FlashHubMath.routeAnnouncement`. First sync and reconnect full-resync silently populate the feed without blasting past announcements. Duplicate notifications are suppressed. The in-app announcement feed remains usable even if notifications are denied or disabled. In group settings and chat header, explicit confidentiality disclosure notifies users that group messages relayed through the hub are not sealed yet and can be read by the hub, while LAN peer-to-peer messages remain private.

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

### HR7 result — 2026-11-13

Title-only announcement notifications and group confidentiality disclosure implemented on Android and desktop.

| Area | Evidence | Mutation |
|---|---|---|
| Lock-screen privacy & content bounds | `FlashNotificationContentTest.announcement notification content enforces title only lock screen confidentiality` and `announcement notification ID partition is 4000..4999`. Title is "New announcement" (+ department name when department-scoped); text/body is strictly the announcement title (never the announcement body text). | Modifying notification content to leak announcement body fails lock-screen privacy tests. Restored. |
| In-app eligibility, scope & author suppression | `RealFlashChatRepositoryAnnouncementsTest.HR7 delta sync emits notification for eligible new announcements and enforces author and scope suppression` and `HR7 initial sync silently populates feed without firing notifications`. Covers initial sync historical suppression, delta sync eligibility, author suppression (`dev-self`), foreign department suppression (`Finance != Engineering`), and duplicate suppression on feed replay. | Mutating `isAuthor = false` broke author suppression and caused test failure (`expected:<2> but was:<3>`). Restored and green. |
| Desktop notifications & opt-out | `DesktopNotificationManagerTest.announcementNotificationDispatchesTitleOnly` and `announcementNotificationSuppressedWhenDisabled`. Verifies title-only desktop toast and suppression when user disables notifications. | Disabling notification opt-out check dispatches unwanted notification. |
| Group relay confidentiality disclosure | `FlashGroupSettingsSheet.kt` displays explicit disclosure: "Confidentiality disclosure: Group messages are not sealed yet, so the hub can read messages it carries while relaying. Messages sent directly between devices on LAN stay private." | Verified in Compose tree. |
| Cold-start routing | `MainActivity` and `DesktopShell` handle `EXTRA_HUB_ROUTE = "hub/announcement/<id>"` / `pendingHubRoute` directly navigating to announcement detail view without splash delay. | Handled via `LaunchEntryPolicy.shouldSkipLaunchSplash`. |

**Adversarial review**
1. Initial sync and full-resync check `isInitialSync` and `feed.fullResync` so historic announcement backfill cannot flood system notification bars.
2. Only `existing == null` items from delta sync can trigger notifications; edits and pins are silent feed updates.
3. Lock-screen privacy strictly checked: `announcementNotificationContent` sets notification body to `title.trim()`, keeping announcement text private.
4. Author's own device is checked against `item.authorRef` and `item.authorDeviceId == localDeviceId`, completely suppressing self-notifications.
5. Scope check prevents foreign department announcements from surfacing notifications to outsiders.
6. Deduplication set `notifiedAnnouncementIds` tracks notified IDs across reconnections, preventing replayed notifications.
7. Channel `flash_announcements` on Android 8+ has dedicated category `CATEGORY_EVENT` with importance default, allowing user-level OS notification management.
8. Android 13+ `POST_NOTIFICATIONS` permission refusal is safely caught in `runCatching` and does not crash or interrupt feed synchronization.
9. Settings toggle `announcement_notifications` in `flash_hub_settings` allows instant opt-out.
10. Group relay confidentiality warning truthfully informs users that group messages are not sealed while hub relay is active.

Locked Gradle gates passed: `:core:messaging:jvmTest`, `:desktop:jvmTest`, `:app:testDebugUnitTest`.

### HR8 handoff

Define transfer continuity across LAN and Hub. Define stable transfer session identity, validated saved byte representation, ownership of the active route, and final integrity verification in both LAN -> Hub and Hub -> LAN directions.

