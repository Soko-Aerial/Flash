# Flash Hub completion review — 2026-11-14

Reviewed `agent/hub-readiness` at `256e140d` (product code at `6a6e02ff`), against the HR6–HR11 completion prompt and readiness plan. Source references point into the candidate tree. The release decision for this revision is **hold Hub readiness sign-off**. The Node console suite independently passed 87/87. The supplied report records 697 server and 2,751 client tests with four client skips, plus an Android debug build. The findings below come from tracing production code and its tests. The Gradle suites and live environment checks were outside this review run.

## Blocking implementation findings

### P1 · HR6 — A failed replacement can strand the old device

A stepped-up replacement with `ttlMinutes = 4` revokes the device and bearer, removes the directory entry, disconnects the old session and clears applicable admin bindings. It then fails invite validation and answers 400. The invite flow can also refuse an unsupported role, an unavailable delegation scope or an unsuitable Hub URL after the destructive changes. The route has no durable replacement operation to reconcile an interrupted request. One-use redemption limits each individual grant. A retry after a lost response can still issue a second grant for the same old device.

**Acceptance:** Reject invalid requests before any durable change. After a valid request begins, persist enough state to make retries and concurrent requests safe and recoverable. Prove that an invite-write failure and a lost response leave an operator-visible outcome with at most one live replacement grant.

Source: `server/.../admin/AdminApi.kt:184,562,579,735`, `server/.../auth/EnrollmentService.kt:152`, and `DeviceReplacementTest.kt:483`.

### P1 · HR6 — The replacement role check misses an inherited department-head role

The department-head guard checks the optional request field. Omitting `role` passes the guard and then inherits `dept_admin` from a same-department target device. The later organisation-admin guard covers `admin` and bound admin devices. A same-department `dept_admin` device without those bindings falls through, subject to the Hub delegation allowing that enrollment role. The normal invite route explicitly restricts heads to issuing member invites.

**Acceptance:** Authorize the final department, groups and role before revocation. A stepped-up head cannot mint a department-head grant, including by omitting the role.

Source: `server/.../admin/AdminApi.kt:523,546,551,641`, `server/.../auth/EnrollmentService.kt:146`.

### P1 · HR7 — The first new announcement after an empty sync is silent

A fresh Hub starts its feed sequence at zero. After a successful empty sync, the app persists the zero cursor. The next fetch still uses `since=0`, which the Hub labels a full snapshot. The client also treats that zero cursor as first sync. Both checks suppress the notification for the first real announcement, even though the announcement is stored and the recipient is eligible.

**Acceptance:** After a successful empty sync, publish as another user and refresh. The announcement appears in the feed and produces exactly one title-only notification, including if the client restarts between those two syncs. A newly enrolled client with existing posts still gets a quiet bootstrap. Test this with the real feed semantics because changing only the client bootstrap check leaves the server's `fullResync` flag in effect.

Source: `server/.../announcements/AnnouncementStore.kt:60,418,457`, `core/messaging/.../RealFlashChatRepository.kt:8459,8497,8521`.

### P1 · HR8 — The normal sender path cannot continue a transfer on the other route

The initial send interceptor chooses Hub when direct reachability is absent. Its Hub path creates a new file ID. Retry on a direct send relaunches that direct send, while Retry on a Hub row restarts its Hub upload or download. `handoverRoute` has no product caller. The receive integration fixtures arrange a matching file ID and the incoming alternate route, then prove verified bytes can be reused under those conditions. They leave open how the app initiates that alternate route for the existing sender transfer. The coordinator's route ownership and verified-byte checkpoints are process-local.

**Acceptance:** Use the normal send and retry flow, break the active route during a large transfer and make only the alternative route available. Do this in both directions. Prove the same transfer ID, verified offset reuse where compatible, matching final digest, one completion row and one published file. Repeat after process restart for supported cases. Check that Cancel and stale workers cannot revive or publish the transfer.

Source: `core/engine/.../hubfiles/HubFileBinding.kt:218,282,431`, `core/transfer/.../RealFlashTransferRepository.kt:1033,1106`, `core/engine/.../hubfiles/TransferContinuityCoordinator.kt:204,281`, and `CrossRouteWiringIntegrationTest.kt:163,261`.

### P1 · HR9 — TURN expires while the Hub session stays connected

The clients fetch ICE configuration on the transition to a ready Hub session and use the cached result for later calls. The server's default TURN credential lifetime is one hour. On an uninterrupted session the configuration is neither renewed before expiry nor retried after an initial transient fetch failure. A call action can remain available with stale credentials, including where a new call needs TURN.

**Acceptance:** Use a short credential lifetime and keep both relay sockets connected. A later audio or video call on TURN-only networks obtains fresh credentials and connects. Simulate a one-off configuration failure and require recovery without toggling the network.

Source: `core/enterprise/.../EnterpriseHub.kt:180,232`, `server/.../relay/CallConfigRoutes.kt:34,60`, `app/.../DiscoveryEngineHolder.kt:1875,1912`, and `desktop/.../DesktopEngine.kt:1877,2708`.

### P1 · HR10 — An authenticated member can poison group-key rotation

A pairwise-sealed key distribution from an active member with the current member set can supply an arbitrary positive epoch. The decoder accepts `Long.MAX_VALUE`. The coordinator promotes the highest epoch and persists it. After a real membership change, it calculates the next epoch as the highest stored epoch plus one. That wraps to a negative value, and the sealer rejects it. A peer-supplied roster version ahead of the local authoritative roster can also be promoted if the member set matches.

**Acceptance:** Try a key distribution from a trusted ordinary member using the correct roster and an extreme epoch, then remove that member and send again. Reject unauthorized future state and keep legitimate rotation working after restart. Test early and conflicting distributions, history and mixed-version fail-closed behavior separately.

Source: `core/messaging/.../hub/HubChatBridge.kt:700`, `core/messaging/.../hub/HubGroupE2eeCoordinator.kt:227,293,315,348,411`, `core/security/.../enterprise/GroupRelaySealing.kt:199,332`.

### P2 · HR9 — The relay replay guard checks a different call ID from the call handler

The replay and terminal guard keys off the envelope call ID. The coordinator then decodes the inner frame and dispatches by the inner call ID without comparing the two. A trusted peer can send an old 1:1 invite under a new envelope ID with a fresh timestamp and sequence. With no live call, the inner invite can enter the normal ringing path despite its original call being terminal.

**Acceptance:** Wrap a captured invite for an ended call in a fresh envelope with a different call ID. Reject before recording replay state and before dispatch. Confirm valid matching frames and LAN calls still work.

Source: `core/calling/.../HubOffLanCalling.kt:196` and `core/calling/.../CallCoordinator.kt:368,470,496,678`.

### P2 · HR11 — Restart hides an unresolved quarantine

The unreadable queue alert reads a process-local counter. Quarantined rows remain marked in SQLite until TTL cleanup. A restart resets the counter to zero while those rows remain. The existing recovery test constructs a fresh queue on the same database and counts the resulting alert clearance as recovery without resolving the quarantined rows.

**Acceptance:** Leave an unreadable unexpired row in place, restart, and check `/health/detail` and `/metrics`. The alert should reflect outstanding quarantine and clear only when the rows are resolved or expire under the documented policy. Keep the process-lifetime event counter for telemetry.

Source: `server/.../mailbox/MailboxQueue.kt:53,291,536`, `server/.../core/FlashServerEngine.kt:336`, and `OperationsAndRolloutDrillsTest.kt:245`.

## What-if acceptance matrix

| Phase | Ask this before accepting | Verdict for this candidate |
| --- | --- | --- |
| HR6 | If the operator submits a bad request or loses the response, can they safely recover and leave one usable grant? Can a head replace another head's device without issuing elevated credentials? | **Fail.** Invalid TTL revokes first and the inherited role escapes the member-only check. Retry and concurrent grant issuance need dedicated tests. |
| HR6 | If the old device comes back with its saved credentials after server restart or while a client uses cached LAN trust, is it rejected while the replacement can enroll? | **Partial evidence.** Server tests cover refusal of stolen credentials and concurrent redemption. Confirm the Android and desktop enrollment journey and offline LAN revocation with `HR6-DEV-01 / ORGT-14`. |
| HR7 | If the first sync saw no posts, does the next eligible post notify exactly once? If muted, denied, self-authored or out of scope, does the feed still work without a notification? | **Fail for the empty-feed case.** Host coverage exists for title/scope eligibility. Exercise OS permissions, mute, cold-start tap and desktop activation under `HR7-DEV-01`. |
| HR8 | If LAN drops at 35% or Hub becomes unreachable when LAN returns, can a normal Retry continue the same transfer and save one verified copy? What if the sender restarts, cancels, changes the source or uses an older peer? | **Fail for route switching from normal Retry.** Receive-side reuse has integration coverage with matching fixtures. Physical continuity, one history row and the explicit safe fallback belong in `HR8-DEV-01`. |
| HR9 | If TURN credentials expire, the first config fetch fails, or a terminal call signal arrives under a new envelope, does calling recover and stay truthful? If Android is asleep, can a valid incoming call ring? | **Fail for credential lifecycle and the envelope mismatch.** Verify direct and TURN-only audio/video on two networks and measure background behavior in `HR9-DEV-01/02`. |
| HR10 | If a trusted member claims a huge or future epoch, then is removed, can remaining members rotate and send? If a legacy client joins or a member rejoins, are downgrade and history rules enforced? | **Fail for epoch promotion and rotation.** Ciphertext opacity and fail-closed legacy fixtures exist. Confirm removal, rejoin, old/new mixes and history on actual clients with `HR10-DEV-01`. |
| HR11 | If the operator restarts with unreadable rows, or the public reverse-proxy certificate is about to expire, do alerts show the issue through recovery? Can an operator restore a real backup and complete the pilot with coturn? | **Fail for quarantine across restart.** The Hub's certificate check covers direct TLS. Verify external expiry monitoring for proxy deployments, browser recovery and restore with `HR11-ENV-01`. The in-process 50-device/500-frame run leaves the production-style soak in `HR11-ENV-02`. |

## Remediation and integration

The verdicts above describe the reviewed revision `256e140d`. Hub commits `a1ba69ca` and `e3a64a24` record remediation and the independent verifier follow-up. The integration combines the Hub's route-completion checks with the `dev` cancellation guard and sends desktop announcements through the title-only toast and its Open Announcement action. Physical and hosted acceptance remains in the HR6–HR11 test backlog.

## Re-review gate

Fix the findings in the candidate branch and add regression cases that fail on this revision. Run the affected host suites and have a fresh reviewer trace the production entry points, including failure injection for replacement and real sender-driven route changes for transfers. Keep the live acceptance rows open until the corresponding phone, desktop, browser, isolated VPS and coturn evidence is recorded. Integrate with the current `dev` transfer and notification work before running the final release gate.
