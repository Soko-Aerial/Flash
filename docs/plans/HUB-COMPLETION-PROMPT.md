# Flash Hub completion prompt

You are the lead implementer for Flash Hub. Work in the Flash repository and finish the remaining Hub readiness phases, HR6 through HR11, on Android, desktop, and the Hub server and console wherever each phase applies. Implement and verify the features through their normal user and operator entry points. After implementation, spawn an independent verifier subagent, fix its blocking findings, and have it review the corrected result before you report completion.

## Start from the actual repository state

1. Read `AGENTS.md`, `logs/handoff.md`, the relevant progress and error entries, `docs/plans/HUB-READINESS-REMEDIATION.md`, and `docs/plans/HUB-QUALITY-CONTRACT.md`. Read `docs/plans/HUB-PLANS-PROMPTS.md` §2 for the verifier procedure. Treat the November readiness plan as the authority for HR6–HR11. The October Hub tab phases H6–H10 are a different workstream.
2. Inspect `git status`, the current branch, recent Hub commits, and relevant source and tests. The last documented baseline is HR1–HR5 implemented, with HR6 as the next phase. Verify that baseline and any work done since. Preserve department scoping, restore-key validation, retirement of queued group deliveries, the approved console workflows, and signed LAN revocation. Reuse existing code and tests.
3. The main checkout may contain unrelated uncommitted transfer, notification, or UI work. Preserve it. Use an isolated Hub worktree from the correct committed `dev` baseline when needed. Identify overlapping files before editing and record any integration that must wait for other work. Use `tools\flash-gradle.cmd` or `tools\flash-gradle.ps1` for Gradle on this machine and respect the single-build lock. Keep Git publication, deployments, live Hub mutations, and device-data changes outside this assignment unless the owner separately authorizes them. Run acceptance on disposable instances and explicitly available devices.
4. Work in the roadmap order. Before each phase, record the short explain-back required by the quality contract, including failure modes and tests. Where a phase needs an external service choice or an owner policy decision, make the concrete design and remaining decision reviewable, then continue independent work. Avoid unrelated redesigns and speculative refactors. The owner authorizes an independent review subagent for this assignment. That supersedes the older roadmap restriction on using subagents for this review.

## Required functionality and acceptance

### HR6 — Secure device replacement

- An authorized operator can select a lost device and issue a scoped, expiring, one-use replacement grant. Revoke the old binding and active sessions through the existing revocation flow. Preserve the revocation across restart and offline LAN trust synchronization. Enroll a fresh device identity and keys with proof of possession. Expose the outcome and any recovery action through the console and supported client enrollment paths.
- Require an explicit authorized choice of department and groups. Check scope and quotas at the point of change. Treat administrator and approver bindings separately under the existing privilege and step-up rules. Record old and new bindings in the audit trail without recording credentials or tokens.
- Prove the happy path and refusal of old or stolen credentials, wrong scope, expired or replayed grants, and concurrent redemption. Test interrupted enrollment, retry and restart, quota exhaustion, and Android and desktop replacement. Tie the device check to `ORGT-14` and the HR6 handoff.

### HR7 — Announcement notifications and confidentiality disclosure

- After an eligible announcement is stored, Android and desktop can show a **title-only** system notification using their existing notification managers. Respect scope, the user’s notification preference, permissions, and author suppression. Tapping a notification opens the correct announcement from a running or cold-started app. First sync, reconnect catch-up, replay, and restart follow the documented eligibility policy and avoid duplicates. The in-app feed remains usable when notifications are denied or muted.
- Wire the required group relay confidentiality notice into the existing group UI while the Hub can read group relays. Keep announcements server-readable as designed. Describe any background delivery limitations using measured results. Keep externally managed wake-up or push behind a separate service decision.
- Exercise `ANN-01`, `ANN-10`, `ANN-11`, and `HUBT-12`, plus cross-department exclusion, denied permissions, mute, duplicate suppression, author suppression, cold start and desktop activation.

### HR8 — Continue a file transfer across LAN and Hub

- Define and test a stable logical transfer identity, a compatible representation of verified saved bytes, and ownership of the active route before changing the wire. Preserve source identity, recipient authorization, encryption and integrity on each route. Allow LAN → Hub and Hub → LAN to resume the same transfer using only bytes that the destination can verify against the same file. Surface progress, retry and cancellation through the existing transfer UI.
- Prove safe behavior on route races, retries, cancellation, process restart where supported, changed source, incompatible chunk layouts, tampered data, and unavailable or older peers. Exercise interruption partway through a large file in both directions with Android and desktop. Require one logical transfer, one completion row, one published copy, and a matching final digest. If compatible resume is impossible, make the fallback explicit and preserve integrity and user intent.

### HR9 — Off-LAN voice and video calling

- Specify and implement authenticated, short-lived call signaling, authorized participants, idempotent call transitions, capability negotiation, and an explicit direct/relayed media policy. Use isolated test infrastructure for direct media and relay fallback. Record any production relay service choice separately. Show off-LAN call actions only when that path is supported and usable. Keep the existing LAN call path working.
- Exercise incoming and outgoing audio/video on two independent networks across Android and desktop. Cover accept, decline, cancel, hang-up, direct media, relay fallback, timeouts, duplicates, late and replayed signaling, revoked devices, reconnect, and relevant process/background behavior. Verify audio and video actually flow. Align the UI, protocol documentation, and deployment guide with the verified capability.

### HR10 — End-to-end encryption for Hub-relayed group messages

- Write the threat model and a versioned compatibility design before implementation. Use authenticated group membership epochs, scoped key delivery, and rotation on membership changes. Define joining, rejoining and history visibility explicitly. Preserve the privacy policy for existing history and present an honest compatibility state for older clients. An encrypted group must fail closed rather than silently sending plaintext.
- Prove the Hub receives ciphertext and lacks the keys needed to read protected payloads. A removed member cannot decrypt new messages. Exercise simultaneous membership changes, stale epochs, replay, wrong scope or signer, out-of-order delivery, rejoin, history access, offline operation, and old/new combinations. Keep the existing encryption and announcement claims accurate throughout migration.

### HR11 — Operations and rollout evidence

- Expose actionable monitoring for unreadable or quarantined queues, age of the oldest queued mail, disk pressure, and certificate/delegation expiry. Define thresholds, what each alert means, and the operator response in the runbook. Verify monitoring from the actual metrics and validate recovery with intentionally broken disposable fixtures.
- Rehearse backup restore with matching, missing and wrong keys, signer rotation/revocation, and continuity for enrolled clients. Run `HUBREADY-01..05` against an isolated Hub and appropriate browser/device fixtures. Cover applicable `HUBWORK-*`, console and feature acceptance tests. Run the pilot matrix and `PERF-H03` using the documented production-style profile and intended fleet assumptions. Record the hardware, profile, duration, limits, and observed results. If a required environment is unavailable, record the exact remaining drill and keep rollout acceptance open.

## Evidence required for each phase

- Apply all 12 coverage categories in `HUB-QUALITY-CONTRACT.md`. Cover normal use, failure, hostile input, concurrency, lifecycle, scale, security, compatibility, both hosts, UI/accessibility, observability, and operator recovery as the contract specifies. Give a concrete reason for any inapplicable item. Follow the repo’s UI approval and accessibility requirements where relevant.
- For each new security rule, show positive and negative tests and a mutation that breaks the rule and makes the relevant test fail. Restore the source and prove the normal test passes. Test new wire formats with golden vectors and malformed input. Cover older client/new Hub and newer client/older Hub behavior. Keep existing trust, visibility and revocation guarantees in force.
- Run affected server, client and console gates, plus Android and desktop compilation where required. Run focused checks while iterating, then the required phase and final integration gates. Record actual commands, results, relevant counts and evidence paths. Treat physical, browser and operator acceptance separately from automated checks. Preserve the owner's device sign-off process and add any unrun check to the backlog with an exact pass condition.
- Update the remediation implementation log, progress, handoff, and test backlog. Update protocol and route contracts, threat and compatibility notes, security probes, and operator or UI docs where the phase changes them. Keep published claims aligned with actual behavior. Follow the contract’s phase report, including adversarial self-review, extra situations handled, and residual risks.

## Mandatory independent review after implementation

Spawn a fresh verifier subagent after the implementation and integration gate. Give it this prompt, the roadmap and quality contract, the exact base and candidate, the diff, and the phase reports. Tell it to independently assess **whether a user or operator can actually complete each accepted workflow**. Give it this review brief:

> Review the final HR6–HR11 candidate independently. Follow the repository verifier procedure in `docs/plans/HUB-PLANS-PROMPTS.md` §2 and the Hub quality contract. Trace each acceptance item through its normal client, console, API, and persistence paths where applicable. Check at least five evidence claims per phase against source or real test output. Re-run focused gates through the build lock and independently mutation-check at least two security rules in an isolated checkout, then restore the mutations. Examine hostile input, authorization, revocation, races, retries, lifecycle, accessibility, older clients, and regressions against HR1–HR5. Report findings with severity, exact file and line, reproduction or evidence, and affected acceptance item. For every phase return `PASS`, `FAIL`, or `BLOCKED` for implementation and separately for real-environment acceptance. Unverified device or operator results stay open. Do not fix the candidate yourself.

Read the report and address every blocking implementation finding. Re-run affected tests and ask the verifier to recheck the revised candidate. Final sign-off must refer to the revision actually reviewed. If the agent environment cannot launch a verifier, report that independent review is blocked and leave sign-off open.

## Final handoff

Give the owner a phase-by-phase table with functionality delivered, exact files or commits, test evidence, verifier verdict, and outstanding device/operator checks. Clearly distinguish implemented and host-tested work, work independently accepted, and rollout acceptance. Include any unresolved decision, limitation or blocker with a concrete next step. Report the Hub as implementation complete only when every in-scope feature is usable through its normal entry point and the independent verifier accepts the final candidate. Report rollout readiness only after its separate acceptance checks pass.
