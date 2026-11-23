# Module-by-module 100-if review

**Date:** 2026-11-17
**Review run:** 2026-11-18 on local `dev` at `364109bd` and the existing working tree.
**Status:** Source and saved-test review across all 27 modules. Runtime and device checks remain open.

[Module summary](#review-summary) · [Findings](#findings-to-act-on) · [Earlier Hub group findings](#earlier-hub-groups-audit-against-current-source)

The matrix preserves the findings from the audited checkout. Follow-up implementation and acceptance are recorded in [ADR-365](../decisions.md#adr-365--enforce-hub-group-policy-before-delivery-and-create-the-initial-roster-atomically) and [IFREV-01..06](../testing/TEST-BACKLOG.md#2026-11-18--module-if-review-follow-up).

## Scope and method

Every root project in `settings.gradle.kts` is included, plus the vendored WebRTC composite build. Each module receives the same 100 questions. Verdicts are `PASS`, `FAIL`, `PARTIAL`, `N/A`, or `UNVERIFIED`; each non-UNVERIFIED verdict must cite source, test, runtime, or device evidence. Compilation and unit tests alone do not prove device, security, lifecycle, accessibility, or production support.

## Module inventory

| Module | Classification |
|---|---|
| `:app` | shipped host |
| `:core:common` | shared library |
| `:core:persistence` | shared library |
| `:core:security` | shared library |
| `:core:hub-protocol` | shared library |
| `:core:discovery` | shared library |
| `:core:network` | shared library |
| `:core:enterprise` | shared library |
| `:core:transfer` | shared library |
| `:core:messaging` | shared library |
| `:core:calling` | shared library |
| `:core:ptt` | shared library |
| `:core:radio` | shared library |
| `:core:swarm` | shared library |
| `:core:engine` | shared library |
| `:ui:theme` | shared library |
| `:ui:platform-shims` | shared library |
| `:ui:chat` | shared library |
| `:ui:callui` | shared library |
| `:ui:hub-competition-claude` | consumer/dev or included build |
| `:desktop` | shipped host |
| `:server` | shipped host |
| `:sample:consumer` | consumer/dev or included build |
| `:sample:consumer-granular` | consumer/dev or included build |
| `:sample:consumer-desktop` | consumer/dev or included build |
| `:benchmark` | consumer/dev or included build |
| `third_party/webrtc-kmp` | consumer/dev or included build |

## The 100 if questions

Q01. If the module encounters null input, does it handle the case explicitly, safely, and with evidence?
Q02. If the module encounters empty input, does it handle the case explicitly, safely, and with evidence?
Q03. If the module encounters malformed input, does it handle the case explicitly, safely, and with evidence?
Q04. If the module encounters unknown fields, does it handle the case explicitly, safely, and with evidence?
Q05. If the module encounters unsupported versions, does it handle the case explicitly, safely, and with evidence?
Q06. If the module encounters duplicate requests, does it handle the case explicitly, safely, and with evidence?
Q07. If the module encounters concurrent requests, does it handle the case explicitly, safely, and with evidence?
Q08. If the module encounters post-shutdown requests, does it handle the case explicitly, safely, and with evidence?
Q09. If the module encounters cancellation, does it handle the case explicitly, safely, and with evidence?
Q10. If the module encounters timeouts, does it handle the case explicitly, safely, and with evidence?
Q11. If the module encounters storage outage, does it handle the case explicitly, safely, and with evidence?
Q12. If the module encounters network outage, does it handle the case explicitly, safely, and with evidence?
Q13. If the module encounters network change, does it handle the case explicitly, safely, and with evidence?
Q14. If the module encounters peer disconnect, does it handle the case explicitly, safely, and with evidence?
Q15. If the module encounters peer reconnect, does it handle the case explicitly, safely, and with evidence?
Q16. If the module encounters authentication failure, does it handle the case explicitly, safely, and with evidence?
Q17. If the module encounters authorization change, does it handle the case explicitly, safely, and with evidence?
Q18. If the module encounters credential revocation, does it handle the case explicitly, safely, and with evidence?
Q19. If the module encounters key expiry, does it handle the case explicitly, safely, and with evidence?
Q20. If the module encounters clock jumps, does it handle the case explicitly, safely, and with evidence?
Q21. If the module encounters process kill, does it handle the case explicitly, safely, and with evidence?
Q22. If the module encounters device restart, does it handle the case explicitly, safely, and with evidence?
Q23. If the module encounters interrupted transaction, does it handle the case explicitly, safely, and with evidence?
Q24. If the module encounters interrupted migration, does it handle the case explicitly, safely, and with evidence?
Q25. If the module encounters corrupt persistence, does it handle the case explicitly, safely, and with evidence?
Q26. If the module encounters full storage, does it handle the case explicitly, safely, and with evidence?
Q27. If the module encounters unsafe path, does it handle the case explicitly, safely, and with evidence?
Q28. If the module encounters changing file, does it handle the case explicitly, safely, and with evidence?
Q29. If the module encounters huge file, does it handle the case explicitly, safely, and with evidence?
Q30. If the module encounters zero-byte file, does it handle the case explicitly, safely, and with evidence?
Q31. If the module encounters huge collections, does it handle the case explicitly, safely, and with evidence?
Q32. If the module encounters Unicode text, does it handle the case explicitly, safely, and with evidence?
Q33. If the module encounters bidi/control text, does it handle the case explicitly, safely, and with evidence?
Q34. If the module encounters missing text, does it handle the case explicitly, safely, and with evidence?
Q35. If the module encounters long labels, does it handle the case explicitly, safely, and with evidence?
Q36. If the module encounters 200% text, does it handle the case explicitly, safely, and with evidence?
Q37. If the module encounters RTL locale, does it handle the case explicitly, safely, and with evidence?
Q38. If the module encounters accessibility services, does it handle the case explicitly, safely, and with evidence?
Q39. If the module encounters reduced motion, does it handle the case explicitly, safely, and with evidence?
Q40. If the module encounters narrow width, does it handle the case explicitly, safely, and with evidence?
Q41. If the module encounters resize, does it handle the case explicitly, safely, and with evidence?
Q42. If the module encounters backgrounding, does it handle the case explicitly, safely, and with evidence?
Q43. If the module encounters foregrounding, does it handle the case explicitly, safely, and with evidence?
Q44. If the module encounters repeated lifecycle, does it handle the case explicitly, safely, and with evidence?
Q45. If the module encounters out-of-order callback, does it handle the case explicitly, safely, and with evidence?
Q46. If the module encounters missing callback, does it handle the case explicitly, safely, and with evidence?
Q47. If the module encounters racing producers, does it handle the case explicitly, safely, and with evidence?
Q48. If the module encounters full queue, does it handle the case explicitly, safely, and with evidence?
Q49. If the module encounters slow consumer, does it handle the case explicitly, safely, and with evidence?
Q50. If the module encounters worker exception, does it handle the case explicitly, safely, and with evidence?
Q51. If the module encounters logging failure, does it handle the case explicitly, safely, and with evidence?
Q52. If the module encounters debug logging, does it handle the case explicitly, safely, and with evidence?
Q53. If the module encounters metrics emission, does it handle the case explicitly, safely, and with evidence?
Q54. If the module encounters reported errors, does it handle the case explicitly, safely, and with evidence?
Q55. If the module encounters retry backoff, does it handle the case explicitly, safely, and with evidence?
Q56. If the module encounters partial retry, does it handle the case explicitly, safely, and with evidence?
Q57. If the module encounters unsafe retry, does it handle the case explicitly, safely, and with evidence?
Q58. If the module encounters terminal retry, does it handle the case explicitly, safely, and with evidence?
Q59. If the module encounters pause race, does it handle the case explicitly, safely, and with evidence?
Q60. If the module encounters resume, does it handle the case explicitly, safely, and with evidence?
Q61. If the module encounters late cancel events, does it handle the case explicitly, safely, and with evidence?
Q62. If the module encounters remote cancel, does it handle the case explicitly, safely, and with evidence?
Q63. If the module encounters successful completion, does it handle the case explicitly, safely, and with evidence?
Q64. If the module encounters failed completion, does it handle the case explicitly, safely, and with evidence?
Q65. If the module encounters duplicate construction, does it handle the case explicitly, safely, and with evidence?
Q66. If the module encounters missing dependency, does it handle the case explicitly, safely, and with evidence?
Q67. If the module encounters missing platform capability, does it handle the case explicitly, safely, and with evidence?
Q68. If the module encounters API version changes, does it handle the case explicitly, safely, and with evidence?
Q69. If the module encounters denied permission, does it handle the case explicitly, safely, and with evidence?
Q70. If the module encounters revoked permission, does it handle the case explicitly, safely, and with evidence?
Q71. If the module encounters lost connectivity, does it handle the case explicitly, safely, and with evidence?
Q72. If the module encounters stale discovery, does it handle the case explicitly, safely, and with evidence?
Q73. If the module encounters duplicate discovery, does it handle the case explicitly, safely, and with evidence?
Q74. If the module encounters identity collision, does it handle the case explicitly, safely, and with evidence?
Q75. If the module encounters unexpected identity, does it handle the case explicitly, safely, and with evidence?
Q76. If the module encounters truncated frame, does it handle the case explicitly, safely, and with evidence?
Q77. If the module encounters oversized frame, does it handle the case explicitly, safely, and with evidence?
Q78. If the module encounters reordered frame, does it handle the case explicitly, safely, and with evidence?
Q79. If the module encounters replayed frame, does it handle the case explicitly, safely, and with evidence?
Q80. If the module encounters bad checksum/signature, does it handle the case explicitly, safely, and with evidence?
Q81. If the module encounters encryption failure, does it handle the case explicitly, safely, and with evidence?
Q82. If the module encounters persisted keys, does it handle the case explicitly, safely, and with evidence?
Q83. If the module encounters trust change, does it handle the case explicitly, safely, and with evidence?
Q84. If the module encounters missing database row, does it handle the case explicitly, safely, and with evidence?
Q85. If the module encounters conflicting rows, does it handle the case explicitly, safely, and with evidence?
Q86. If the module encounters newer schema, does it handle the case explicitly, safely, and with evidence?
Q87. If the module encounters two database owners, does it handle the case explicitly, safely, and with evidence?
Q88. If the module encounters stale cache, does it handle the case explicitly, safely, and with evidence?
Q89. If the module encounters invalidated authorization, does it handle the case explicitly, safely, and with evidence?
Q90. If the module encounters unsupported UI action, does it handle the case explicitly, safely, and with evidence?
Q91. If the module encounters backend failure, does it handle the case explicitly, safely, and with evidence?
Q92. If the module encounters loading state, does it handle the case explicitly, safely, and with evidence?
Q93. If the module encounters empty state, does it handle the case explicitly, safely, and with evidence?
Q94. If the module encounters offline state, does it handle the case explicitly, safely, and with evidence?
Q95. If the module encounters partial state, does it handle the case explicitly, safely, and with evidence?
Q96. If the module encounters public API change, does it handle the case explicitly, safely, and with evidence?
Q97. If the module encounters platform divergence, does it handle the case explicitly, safely, and with evidence?
Q98. If the module encounters flaky test, does it handle the case explicitly, safely, and with evidence?
Q99. If the module encounters unimplemented feature, does it handle the case explicitly, safely, and with evidence?
Q100. If the module encounters missing security coverage, does it handle the case explicitly, safely, and with evidence?

## Review summary

### Evidence and verdicts

Areas were assessed against the responsibility of each module. `PASS` means the linked path handles the stated case at the boundary described. `FAIL` means a source trace establishes a gap. `PARTIAL` means some handling exists and a relevant path, host, or condition remains uncovered. `N/A` means the case belongs outside this module. `UNVERIFIED` means no scenario-specific evidence was matched during this pass.

The review used working-tree source, test assertions, existing XML, and prior device logs where indicated. Saved report totals are historical, can count the same common test on multiple runners, and are not proof that every cited test ran against this checkout. The external drive was disconnected, so Gradle, the test runners, and device and Hub tests were left idle. No product source was changed during the review.

| Module | Pass | Fail | Partial | N/A | Unverified | Result |
|---|---:|---:|---:|---:|---:|---|
| `:app` | 7 | 4 | 23 | 9 | 57 | Android entry and service paths have host coverage. Group policy scoping and real Android lifecycle are still open. |
| `:core:common` | 16 | 0 | 1 | 57 | 26 | Shared validators and diagnostic queues have scoped coverage. Some filesystem and time discontinuity behavior is consumer-specific. |
| `:core:persistence` | 11 | 0 | 6 | 51 | 32 | Duplicate rows, transactional rollback and regular upgrades have coverage. Mid-migration interruption and simultaneous owners need evidence. |
| `:core:security` | 16 | 0 | 3 | 36 | 45 | Pairing and crypto rejection cases have evidence. Device vault loss and platform-backed key behavior need additional coverage. |
| `:core:hub-protocol` | 15 | 0 | 2 | 77 | 6 | Parsing and forward compatibility have direct tests. Stateful replay and session policy are owned by server and clients. |
| `:core:discovery` | 17 | 1 | 4 | 39 | 39 | Stale callbacks and reconnect are modeled. Native Wi-Fi Direct is absent and real interface transitions remain to verify. |
| `:core:network` | 17 | 0 | 5 | 30 | 48 | Queue saturation, frame boundaries and identity mismatch have coverage. Physical reconnect across an active transfer remains open. |
| `:core:enterprise` | 26 | 0 | 5 | 13 | 56 | Token-backed relay, cache and signed directory have fixture evidence. Live policy change and multi-host roster propagation remain open. |
| `:core:transfer` | 19 | 0 | 10 | 24 | 47 | Receiver retry, no-space write and integrity have fixture evidence. Device retry and large-file storage pressure remain open. |
| `:core:messaging` | 7 | 7 | 11 | 21 | 54 | Attachment dedup and outbox durability have tests. Partial or repeated group creation and Hub membership invites remain gaps. |
| `:core:calling` | 15 | 0 | 8 | 28 | 49 | Auth, replay and denied-media transitions are covered in host fixtures. Phone/desktop TURN and Doze remain open. |
| `:core:ptt` | 14 | 0 | 5 | 34 | 47 | Floor arbitration and audio jitter bounds are covered. Mic permission loss and real capture cleanup need platform evidence. |
| `:core:radio` | 13 | 1 | 6 | 22 | 58 | Companion text, retry and ACK mechanics have fixtures. Other radio modes remain pending. |
| `:core:swarm` | 15 | 0 | 6 | 21 | 58 | Cancellation, piece validation and restart are modeled. Real multi-peer recovery and full storage remain open. |
| `:core:engine` | 17 | 0 | 6 | 11 | 66 | Loopback LAN/Hub handover and path handling have tests. Installed and device loss/restart acceptance remains open. |
| `:ui:theme` | 6 | 0 | 4 | 69 | 21 | Fallback colors and motion are checked at the token/splash boundary. Accessibility depends on the consuming screens and remains a screen-level check. |
| `:ui:platform-shims` | 12 | 0 | 6 | 53 | 29 | Permission arbitration and media fallbacks have tests. Actual OS revocation and installed media behavior remain open. |
| `:ui:chat` | 15 | 1 | 12 | 25 | 47 | Transfer mapping, staging and adaptive behavior have test coverage. Double-submit and partial-create handling remain gaps. |
| `:ui:callui` | 9 | 0 | 8 | 53 | 30 | Control math and frame release are covered. Native accessibility and window/OS behavior need verification. |
| `:ui:hub-competition-claude` | 5 | 0 | 6 | 83 | 6 | Unknown showcase entries open the picker. Launcher-specific keyboard, RTL and scaling evidence is absent. |
| `:desktop` | 12 | 3 | 17 | 6 | 62 | Single-instance and transfer disconnect paths have tests. Group policy and installed restart and video checks remain open. |
| `:server` | 21 | 3 | 9 | 9 | 58 | Restart, certificate and directory tests have evidence. Required group encryption is not enforced at relay and group invite issuance is absent. |
| `:sample:consumer` | 3 | 0 | 3 | 93 | 1 | Umbrella classpath checks exist. QuickStart illustrates types and lifecycle, with no live peer wait bound. |
| `:sample:consumer-granular` | 0 | 0 | 2 | 98 | 0 | Pure compile probe. Inspect direct public exports after SDK changes. |
| `:sample:consumer-desktop` | 0 | 0 | 3 | 97 | 0 | Pure JVM publication/variant probe. Latest package resolution is unverified. |
| `:benchmark` | 0 | 1 | 8 | 89 | 2 | Cold startup is scripted. The baseline generator stops at app launch and misses documented Compose paths. |
| `third_party/webrtc-kmp` | 0 | 1 | 12 | 60 | 27 | Source includes native resource safeguards. Composite-build test records and platform parity remain open. |
| **Total** | 308 | 22 | 191 | 1208 | 971 | **2,700 scenarios** |

## Per-module matrix

See the review summary above for module totals. `UNVERIFIED` rows are open checks.

### `:app`

**Audit scope.** Android host for entry points, permissions, foreground execution, shared library wiring, and notifications. [MainActivity.kt](../../app/src/main/java/com/transfer/flash/MainActivity.kt), [AndroidManifest.xml](../../app/src/main/AndroidManifest.xml), [DiscoveryEngineHolder.kt](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt), [FlashBackgroundService.kt](../../app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt)

**Existing test reports.** testDebugUnitTest 119. Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Android share intake rejects file URIs and private providers. OpenableReceivedPathTest also checks received-root containment. [ShareUriPolicyTest.kt:22](../../app/src/test/java/com/transfer/flash/ShareUriPolicyTest.kt#L22) |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | PARTIAL | Host entry wiring exists. Invite parser and authenticated sender checks are owned by shared modules. Camera-to-confirmation remains INVITE-LINK-01. [AndroidEntryShellWiringTest.kt:171](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L171) |
| Q06 | PARTIAL | Transfer notification IDs have distinct actions in the fixture; the Android group sheet also allows repeat submission while the first request runs (IF-06). [FlashCreateGroupSheet.kt:342](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashCreateGroupSheet.kt#L342) |
| Q07 | PARTIAL | Transfer notification IDs have distinct actions in the fixture; the Android group sheet also allows repeat submission while the first request runs (IF-06). [FlashCreateGroupSheet.kt:342](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashCreateGroupSheet.kt#L342) |
| Q08 | PARTIAL | Source checks cover stop cleanup and startup reconciliation. Check actual service lifecycle against FGS-01 and NOTIF-04. [FlashBackgroundServiceNotificationTest.kt:86](../../app/src/test/java/com/transfer/flash/debug/FlashBackgroundServiceNotificationTest.kt#L86) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | PASS | Host timeout plan pauses transferring and verifying rows. Android OS timeout delivery is separate. [TimeoutStopPlanTest.kt:33](../../app/src/test/java/com/transfer/flash/debug/TimeoutStopPlanTest.kt#L33) |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | UNVERIFIED | No scenario-specific evidence reviewed for peer disconnect at this module boundary. |
| Q15 | UNVERIFIED | No scenario-specific evidence reviewed for peer reconnect at this module boundary. |
| Q16 | UNVERIFIED | No scenario-specific evidence reviewed for authentication failure at this module boundary. |
| Q17 | FAIL | Cached Hub group policy is only replaced by a successful asynchronous fetch, with no binding at assignment to the current enrollment. See IF-02 below. [DiscoveryEngineHolder.kt:1460](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1460) |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PARTIAL | Source checks cover stop cleanup and startup reconciliation. Check actual service lifecycle against FGS-01 and NOTIF-04. [FlashBackgroundServiceNotificationTest.kt:86](../../app/src/test/java/com/transfer/flash/debug/FlashBackgroundServiceNotificationTest.kt#L86) |
| Q22 | PARTIAL | Source checks cover stop cleanup and startup reconciliation. Check actual service lifecycle against FGS-01 and NOTIF-04. [FlashBackgroundServiceNotificationTest.kt:86](../../app/src/test/java/com/transfer/flash/debug/FlashBackgroundServiceNotificationTest.kt#L86) |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted migration at this module boundary. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | PASS | Android share intake rejects file URIs and private providers. OpenableReceivedPathTest also checks received-root containment. [ShareUriPolicyTest.kt:22](../../app/src/test/java/com/transfer/flash/ShareUriPolicyTest.kt#L22) |
| Q28 | UNVERIFIED | No scenario-specific evidence reviewed for changing file at this module boundary. |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | PASS | Notification mapper supplies blank-name and failure copy and labels completion. [FlashNotificationContentTest.kt:45](../../app/src/test/java/com/transfer/flash/notifications/FlashNotificationContentTest.kt#L45) |
| Q35 | UNVERIFIED | No scenario-specific evidence reviewed for long labels at this module boundary. |
| Q36 | PARTIAL | Host two-pane and back routing are checked in source. Large font, RTL and TalkBack need device acceptance. [AndroidEntryShellWiringTest.kt:213](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L213) |
| Q37 | PARTIAL | Host two-pane and back routing are checked in source. Large font, RTL and TalkBack need device acceptance. [AndroidEntryShellWiringTest.kt:213](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L213) |
| Q38 | PARTIAL | Host two-pane and back routing are checked in source. Large font, RTL and TalkBack need device acceptance. [AndroidEntryShellWiringTest.kt:213](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L213) |
| Q39 | UNVERIFIED | No scenario-specific evidence reviewed for reduced motion at this module boundary. |
| Q40 | PARTIAL | Host two-pane and back routing are checked in source. Large font, RTL and TalkBack need device acceptance. [AndroidEntryShellWiringTest.kt:213](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L213) |
| Q41 | PARTIAL | Host two-pane and back routing are checked in source. Large font, RTL and TalkBack need device acceptance. [AndroidEntryShellWiringTest.kt:213](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L213) |
| Q42 | PARTIAL | Source checks cover stop cleanup and startup reconciliation. Check actual service lifecycle against FGS-01 and NOTIF-04. [FlashBackgroundServiceNotificationTest.kt:86](../../app/src/test/java/com/transfer/flash/debug/FlashBackgroundServiceNotificationTest.kt#L86) |
| Q43 | PARTIAL | Source checks cover stop cleanup and startup reconciliation. Check actual service lifecycle against FGS-01 and NOTIF-04. [FlashBackgroundServiceNotificationTest.kt:86](../../app/src/test/java/com/transfer/flash/debug/FlashBackgroundServiceNotificationTest.kt#L86) |
| Q44 | PARTIAL | Source checks cover stop cleanup and startup reconciliation. Check actual service lifecycle against FGS-01 and NOTIF-04. [FlashBackgroundServiceNotificationTest.kt:86](../../app/src/test/java/com/transfer/flash/debug/FlashBackgroundServiceNotificationTest.kt#L86) |
| Q45 | PARTIAL | Distinct cancel action identity has a device test. Current run pending under NOTIF-02/04. [TransferNotificationIdentityInstrumentedTest.kt:14](../../app/src/androidTest/java/com/transfer/flash/TransferNotificationIdentityInstrumentedTest.kt#L14) |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | PASS | Host timeout plan pauses transferring and verifying rows. Android OS timeout delivery is separate. [TimeoutStopPlanTest.kt:33](../../app/src/test/java/com/transfer/flash/debug/TimeoutStopPlanTest.kt#L33) |
| Q60 | PARTIAL | Transfer Retry appears through Android host mapping. Durable both-peer behavior is still XFER-RETRY-02. [TransfersUiMapperTest.kt](../../app/src/test/java/com/transfer/flash/TransfersUiMapperTest.kt) |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | PASS | Notification mapper supplies blank-name and failure copy and labels completion. [FlashNotificationContentTest.kt:45](../../app/src/test/java/com/transfer/flash/notifications/FlashNotificationContentTest.kt#L45) |
| Q64 | PASS | Notification mapper supplies blank-name and failure copy and labels completion. [FlashNotificationContentTest.kt:45](../../app/src/test/java/com/transfer/flash/notifications/FlashNotificationContentTest.kt#L45) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PARTIAL | Notification rationale/settings and call permission ordering are wired. Live denial, later revocation and API-level coverage remain open. [AndroidEntryShellWiringTest.kt:63](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L63) |
| Q68 | PARTIAL | Notification rationale/settings and call permission ordering are wired. Live denial, later revocation and API-level coverage remain open. [AndroidEntryShellWiringTest.kt:63](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L63) |
| Q69 | PARTIAL | Notification rationale/settings and call permission ordering are wired. Live denial, later revocation and API-level coverage remain open. [AndroidEntryShellWiringTest.kt:63](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L63) |
| Q70 | PARTIAL | Notification rationale/settings and call permission ordering are wired. Live denial, later revocation and API-level coverage remain open. [AndroidEntryShellWiringTest.kt:63](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L63) |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | UNVERIFIED | No scenario-specific evidence reviewed for stale discovery at this module boundary. |
| Q73 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate discovery at this module boundary. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | PARTIAL | Host entry wiring exists. Invite parser and authenticated sender checks are owned by shared modules. Camera-to-confirmation remains INVITE-LINK-01. [AndroidEntryShellWiringTest.kt:171](../../app/src/test/java/com/transfer/flash/AndroidEntryShellWiringTest.kt#L171) |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | UNVERIFIED | No scenario-specific evidence reviewed for conflicting rows at this module boundary. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | FAIL | Cached Hub group policy is only replaced by a successful asynchronous fetch, with no binding at assignment to the current enrollment. See IF-02 below. [DiscoveryEngineHolder.kt:1460](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1460) |
| Q89 | FAIL | Cached Hub group policy is only replaced by a successful asynchronous fetch, with no binding at assignment to the current enrollment. See IF-02 below. [DiscoveryEngineHolder.kt:1460](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1460) |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | PARTIAL | Android has a group creation failure toast. Partial member adds are reported as success by the repository (IF-05). [MainActivity.kt:3951](../../app/src/main/java/com/transfer/flash/MainActivity.kt#L3951) |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | PARTIAL | Android has a group creation failure toast. Partial member adds are reported as success by the repository (IF-05). [MainActivity.kt:3951](../../app/src/main/java/com/transfer/flash/MainActivity.kt#L3951) |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | FAIL | Native Wi-Fi Direct and the other radio modes remain unimplemented as documented by the feature inventory and radio plan. [2026-11-15-feature-implementation-inventory.md](2026-11-15-feature-implementation-inventory.md) |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:common`

**Audit scope.** Shared values, clocks, framing, bounded caches and diagnostic sinks with no product sessions or UI. [FlashTextFraming.kt](../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/protocol/FlashTextFraming.kt), [FlashTimeSource.kt](../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/time/FlashTimeSource.kt), [RotatingFileLogSink.kt](../../core/common/src/androidMain/kotlin/com/transfer/flash/core/common/logging/RotatingFileLogSink.kt), [FlashResult.kt](../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/result/FlashResult.kt)

**Existing test reports.** jvmTest 21, testAndroidHostTest 149. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | PASS | Blank required envelope fields are rejected. Empty payload is explicitly allowed. [FlashEnvelopeTest.kt:29](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/FlashEnvelopeTest.kt#L29) |
| Q03 | PASS | Malformed pairs are skipped, repeat keys make a frame invalid, and embedded controls are rejected at this framing seam. [FlashTextFramingTest.kt:54](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/FlashTextFramingTest.kt#L54) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Malformed pairs are skipped, repeat keys make a frame invalid, and embedded controls are rejected at this framing seam. [FlashTextFramingTest.kt:54](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/FlashTextFramingTest.kt#L54) |
| Q07 | PASS | Search folding preserves Unicode results under Turkish default locale and concurrent cache eviction. [SearchFoldingConcurrencyTest.kt:20](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/SearchFoldingConcurrencyTest.kt#L20) |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | PASS | Fingerprint changes trigger notification and burst changes update the baseline without repeated callbacks. [LinkChangeTrackerTest.kt:52](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/net/LinkChangeTrackerTest.kt#L52) |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | PARTIAL | Fake clock is controllable and rejects negative advance. A worker reacting safely to wall-clock jumps is a consumer question. [FlashTimeSourceTest.kt:33](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/FlashTimeSourceTest.kt#L33) |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe path at this module boundary. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | PASS | Search folding preserves Unicode results under Turkish default locale and concurrent cache eviction. [SearchFoldingConcurrencyTest.kt:20](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/SearchFoldingConcurrencyTest.kt#L20) |
| Q32 | PASS | Search folding preserves Unicode results under Turkish default locale and concurrent cache eviction. [SearchFoldingConcurrencyTest.kt:20](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/SearchFoldingConcurrencyTest.kt#L20) |
| Q33 | PASS | Malformed pairs are skipped, repeat keys make a frame invalid, and embedded controls are rejected at this framing seam. [FlashTextFramingTest.kt:54](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/FlashTextFramingTest.kt#L54) |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | PASS | User preference overrides platform on high tier and constrained tiers disable motion. [FlashMotionPolicyTest.kt:11](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/perf/FlashMotionPolicyTest.kt#L11) |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | PASS | Search folding preserves Unicode results under Turkish default locale and concurrent cache eviction. [SearchFoldingConcurrencyTest.kt:20](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/SearchFoldingConcurrencyTest.kt#L20) |
| Q48 | PASS | Queue saturation drops diagnostics with accounting and log tests assert redaction and bounded rotation. [RotatingFileLogSinkTest.kt:62](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/RotatingFileLogSinkTest.kt#L62) |
| Q49 | PASS | Queue saturation drops diagnostics with accounting and log tests assert redaction and bounded rotation. [RotatingFileLogSinkTest.kt:62](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/RotatingFileLogSinkTest.kt#L62) |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | PASS | Queue saturation drops diagnostics with accounting and log tests assert redaction and bounded rotation. [RotatingFileLogSinkTest.kt:62](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/RotatingFileLogSinkTest.kt#L62) |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | PASS | The common result utility captures an exception and exposes a typed failure path. [FlashResultTest.kt:95](../../core/common/src/androidHostTest/kotlin/com/transfer/flash/core/common/FlashResultTest.kt#L95) |
| Q55 | PASS | Backoff doubles within cap and respects the configured attempt budget. [ReconnectPolicyTest.kt:70](../../core/common/src/commonTest/kotlin/com/transfer/flash/core/common/resilience/ReconnectPolicyTest.kt#L70) |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | PASS | Backoff doubles within cap and respects the configured attempt budget. [ReconnectPolicyTest.kt:70](../../core/common/src/commonTest/kotlin/com/transfer/flash/core/common/resilience/ReconnectPolicyTest.kt#L70) |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | N/A | Outside this module’s responsibility as described above. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:persistence`

**Audit scope.** Database and preferences storage, schema, transaction, and key-backed opening boundaries. [FlashDatabase.kt](../../core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashDatabase.kt), [FlashSchemaSteps.kt](../../core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/FlashSchemaSteps.kt), [JdbcCipherSQLiteDriver.kt](../../core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherSQLiteDriver.kt)

**Existing test reports.** jvmTest 87, testAndroidHostTest 56. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | PASS | JVM public encrypted seam rejects an empty key and the resulting file lacks a plaintext SQLite header. [FlashDatabaseOpenSeamTest.kt:70](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpenSeamTest.kt#L70) |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | PASS | Room ignores duplicate message and receipt rows. Group and delivery conflict rules have scoped assertions in the same suite. [FlashDatabaseInvariantTest.kt:83](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseInvariantTest.kt#L83) |
| Q07 | PASS | Concurrent metadata reads on the same statement are asserted. [JdbcCipherStatementConcurrencyTest.kt:105](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherStatementConcurrencyTest.kt#L105) |
| Q08 | PASS | Repeated open/close owns one trim listener and closes it rather than retaining stale handles. [DatabaseTrimRegistrationTest.kt](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/db/DatabaseTrimRegistrationTest.kt) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PARTIAL | Reopen retains a row under the same key. Process termination during a flush and physical restart remain unverified. [FlashDatabaseOpenSeamTest.kt:54](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpenSeamTest.kt#L54) |
| Q22 | PARTIAL | Reopen retains a row under the same key. Process termination during a flush and physical restart remain unverified. [FlashDatabaseOpenSeamTest.kt:54](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpenSeamTest.kt#L54) |
| Q23 | PASS | An exception between message and outbox inserts rolls the message back. [FlashDatabaseTransactionTest.kt:65](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseTransactionTest.kt#L65) |
| Q24 | PARTIAL | Registered migration chain and old data preservation are exercised. Kill mid-upgrade and downgrade from a newer schema need separate evidence. [FlashJvmMigrationsTest.kt:232](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashJvmMigrationsTest.kt#L232) |
| Q25 | PASS | Corrupt Android DataStore preferences reset to defaults and allow subsequent saves. Corrupt encrypted Room database recovery is a separate host check. [FlashSettingsDataStoreTest.kt:202](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/settings/FlashSettingsDataStoreTest.kt#L202) |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | PASS | Keyset pagination traverses message history without gaps or duplicates in the DAO fixture. [FlashDatabaseInvariantTest.kt:212](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseInvariantTest.kt#L212) |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | PASS | Repeated open/close owns one trim listener and closes it rather than retaining stale handles. [DatabaseTrimRegistrationTest.kt](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/db/DatabaseTrimRegistrationTest.kt) |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | PASS | Concurrent metadata reads on the same statement are asserted. [JdbcCipherStatementConcurrencyTest.kt:105](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/JdbcCipherStatementConcurrencyTest.kt#L105) |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | PASS | Persisted chunk coverage round-trips for resume computation. [FlashDatabaseInvariantTest.kt:291](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseInvariantTest.kt#L291) |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | PARTIAL | Empty encryption key fails closed and test reads no plaintext header. Fault injection during encryption is uncovered. [FlashDatabaseOpenSeamTest.kt:70](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseOpenSeamTest.kt#L70) |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | PASS | Room ignores duplicate message and receipt rows. Group and delivery conflict rules have scoped assertions in the same suite. [FlashDatabaseInvariantTest.kt:83](../../core/persistence/src/androidHostTest/kotlin/com/transfer/flash/core/persistence/db/FlashDatabaseInvariantTest.kt#L83) |
| Q86 | PARTIAL | Registered migration chain and old data preservation are exercised. Kill mid-upgrade and downgrade from a newer schema need separate evidence. [FlashJvmMigrationsTest.kt:232](../../core/persistence/src/jvmTest/kotlin/com/transfer/flash/core/persistence/db/FlashJvmMigrationsTest.kt#L232) |
| Q87 | PARTIAL | Shared opener and host lifetime exist. Concurrent independent owners and recovery may differ by platform. [JvmFlashDatabaseOpener.kt](../../core/persistence/src/jvmMain/kotlin/com/transfer/flash/core/persistence/db/JvmFlashDatabaseOpener.kt) |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:security`

**Audit scope.** Pairing, identity, key storage, trust and secure codecs with transport/UI lifecycles owned by callers. [FlashIdentityStore.kt](../../core/security/src/commonMain/kotlin/com/transfer/flash/core/security/identity/FlashIdentityStore.kt), [FlashPairingProtocol.kt](../../core/security/src/commonMain/kotlin/com/transfer/flash/core/security/pairing/FlashPairingProtocol.kt), [PlatformCrypto.kt](../../core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/PlatformCrypto.kt), [GroupInviteCodec.kt](../../core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupInviteCodec.kt)

**Existing test reports.** jvmTest 183 (0 failed/errors, 2 skipped), testAndroidHostTest 270. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | PASS | Blank rename is rejected at the identity store. [FlashIdentityStoreTest.kt:68](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/FlashIdentityStoreTest.kt#L68) |
| Q03 | PASS | Malformed group-invite input is rejected, with invalid UTF-8 checks in the same suite. [GroupInviteTest.kt:98](../../core/security/src/commonTest/kotlin/com/transfer/flash/core/security/group/GroupInviteTest.kt#L98) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | PASS | Pairing completes with matching codes. The same suite rejects v1 and busy duplicate requests and expires incomplete handshakes. [DefaultFlashPairingProtocolTest.kt:92](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L92) |
| Q06 | PASS | Pairing completes with matching codes. The same suite rejects v1 and busy duplicate requests and expires incomplete handshakes. [DefaultFlashPairingProtocolTest.kt:92](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L92) |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | PASS | Pairing completes with matching codes. The same suite rejects v1 and busy duplicate requests and expires incomplete handshakes. [DefaultFlashPairingProtocolTest.kt:92](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L92) |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | PASS | Pairing refuses TLS-pinned identity mismatch and tampered commitment or confirmation data. [DefaultFlashPairingProtocolTest.kt:186](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L186) |
| Q17 | UNVERIFIED | No scenario-specific evidence reviewed for authorization change at this module boundary. |
| Q18 | PASS | Revocation writes a tombstone that blocks repinning until a new trust grant. [FlashTrustStoreTest.kt:68](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/FlashTrustStoreTest.kt#L68) |
| Q19 | PASS | Identity certificate issuance respects the year-2049 ceiling. Leaf/delegation expiry at authentication belongs to hub-protocol and consumers. [IdentityCertValidityTest.kt:12](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/crypto/IdentityCertValidityTest.kt#L12) |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe path at this module boundary. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | PASS | Malformed group-invite input is rejected, with invalid UTF-8 checks in the same suite. [GroupInviteTest.kt:98](../../core/security/src/commonTest/kotlin/com/transfer/flash/core/security/group/GroupInviteTest.kt#L98) |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | PASS | Repeated failures lock the peer out temporarily and successful pairing clears its record. [PairingAttemptLimiterTest.kt:11](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/PairingAttemptLimiterTest.kt#L11) |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | PASS | Pairing completes with matching codes. The same suite rejects v1 and busy duplicate requests and expires incomplete handshakes. [DefaultFlashPairingProtocolTest.kt:92](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L92) |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | PASS | Pairing refuses TLS-pinned identity mismatch and tampered commitment or confirmation data. [DefaultFlashPairingProtocolTest.kt:186](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L186) |
| Q76 | PASS | Wrong-key and truncated binary frames fail at the codec boundary. [SecureBinaryFrameCodecTest.kt:29](../../core/security/src/commonTest/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodecTest.kt#L29) |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | PASS | Pairing refuses TLS-pinned identity mismatch and tampered commitment or confirmation data. [DefaultFlashPairingProtocolTest.kt:186](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/pairing/DefaultFlashPairingProtocolTest.kt#L186) |
| Q81 | PASS | Wrong-key and truncated binary frames fail at the codec boundary. [SecureBinaryFrameCodecTest.kt:29](../../core/security/src/commonTest/kotlin/com/transfer/flash/core/security/crypto/SecureBinaryFrameCodecTest.kt#L29) |
| Q82 | PARTIAL | Local vault restart, tampering and wrong-key paths are tested in JVM source. POSIX permission audit remains platform-specific. [KeyFileVaultTest.kt:45](../../core/security/src/jvmTest/kotlin/com/transfer/flash/core/security/identity/KeyFileVaultTest.kt#L45) |
| Q83 | PASS | Revocation writes a tombstone that blocks repinning until a new trust grant. [FlashTrustStoreTest.kt:68](../../core/security/src/androidHostTest/kotlin/com/transfer/flash/core/security/FlashTrustStoreTest.kt#L68) |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | Local vault restart, tampering and wrong-key paths are tested in JVM source. POSIX permission audit remains platform-specific. [KeyFileVaultTest.kt:45](../../core/security/src/jvmTest/kotlin/com/transfer/flash/core/security/identity/KeyFileVaultTest.kt#L45) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | PARTIAL | Known-answer vectors and tamper checks exist. Device keystore and protection against OS-specific key loss require separate evidence. [PlatformCryptoParityTest.kt:65](../../core/security/src/commonTest/kotlin/com/transfer/flash/core/security/crypto/PlatformCryptoParityTest.kt#L65) |

### `:core:hub-protocol`

**Audit scope.** Pure Hub wire, DTO, signature and certificate contracts with no I/O or runtime session ownership. [RelayFrameCodec.kt](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/relay/RelayFrameCodec.kt), [HubContract.kt](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/HubContract.kt), [HubRestCodec.kt](../../core/hub-protocol/src/commonMain/kotlin/com/transfer/flash/core/hubprotocol/rest/HubRestCodec.kt)

**Existing test reports.** jvmTest 126, testAndroidHostTest 120. Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | PASS | The relay codec rejects malformed, unknown-version and blank identifiers and tolerates unknown fields. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q03 | PASS | The relay codec rejects malformed, unknown-version and blank identifiers and tolerates unknown fields. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q04 | PASS | The relay codec rejects malformed, unknown-version and blank identifiers and tolerates unknown fields. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q05 | PASS | The relay codec rejects malformed, unknown-version and blank identifiers and tolerates unknown fields. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | N/A | Outside this module’s responsibility as described above. |
| Q09 | N/A | Outside this module’s responsibility as described above. |
| Q10 | N/A | Outside this module’s responsibility as described above. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | PASS | Chain verification rejects expired or revoked credentials, untrusted roots and forged signatures, and validates skew bounds. [CertChainTest.kt:124](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/CertChainTest.kt#L124) |
| Q17 | PARTIAL | Signature and eligibility validation exist at the codec. Publishing and applying change events are client/server responsibilities. [GroupRosterStatementTest.kt:99](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/GroupRosterStatementTest.kt#L99) |
| Q18 | PASS | Chain verification rejects expired or revoked credentials, untrusted roots and forged signatures, and validates skew bounds. [CertChainTest.kt:124](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/CertChainTest.kt#L124) |
| Q19 | PASS | Chain verification rejects expired or revoked credentials, untrusted roots and forged signatures, and validates skew bounds. [CertChainTest.kt:124](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/CertChainTest.kt#L124) |
| Q20 | PASS | Caller-time skew is explicitly bounded at the certificate verifier. It does not reset or heal the caller's clock. [CertChainTest.kt:131](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/CertChainTest.kt#L131) |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | PASS | Wire size and handshake caps are checked before decoding and in UTF-8 bytes. [RelayFrameCodecTest.kt:145](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L145) |
| Q32 | PASS | Payload chunking splits on code-point boundaries and reconstructs the message. [RelayFrameCodecTest.kt:205](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L205) |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | N/A | Outside this module’s responsibility as described above. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | N/A | Outside this module’s responsibility as described above. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | N/A | Outside this module’s responsibility as described above. |
| Q66 | N/A | Outside this module’s responsibility as described above. |
| Q67 | N/A | Outside this module’s responsibility as described above. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | PASS | Chain verification rejects expired or revoked credentials, untrusted roots and forged signatures, and validates skew bounds. [CertChainTest.kt:124](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/CertChainTest.kt#L124) |
| Q76 | PASS | Directory DTO decoder has tests with random and truncated inputs and no unhandled exception. [DirectorySyncDtoTest.kt:118](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/DirectorySyncDtoTest.kt#L118) |
| Q77 | PASS | Wire size and handshake caps are checked before decoding and in UTF-8 bytes. [RelayFrameCodecTest.kt:145](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L145) |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | PASS | Chain verification rejects expired or revoked credentials, untrusted roots and forged signatures, and validates skew bounds. [CertChainTest.kt:124](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/CertChainTest.kt#L124) |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | PARTIAL | Signature and eligibility validation exist at the codec. Publishing and applying change events are client/server responsibilities. [GroupRosterStatementTest.kt:99](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/GroupRosterStatementTest.kt#L99) |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | PASS | Previous JSON shapes still decode while added fields remain optional in the checked contract. [RestAndInviteTest.kt:60](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RestAndInviteTest.kt#L60) |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:discovery`

**Audit scope.** Advertising, browsing, candidate directory and peer-resolution behavior; discovery is not the authority for trust. [NsdTransport.kt](../../core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt), [MulticastTransport.kt](../../core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransport.kt), [JmdnsTransport.kt](../../core/discovery/src/jvmMain/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsTransport.kt), [StandardEndpointDirectory.kt](../../core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/core/StandardEndpointDirectory.kt)

**Existing test reports.** jvmTest 106, testAndroidHostTest 169. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Malformed announcements are refused and unknown fields discarded. [MulticastProtocolTest.kt:72](../../core/discovery/src/commonTest/kotlin/com/transfer/flash/core/discovery/multicast/MulticastProtocolTest.kt#L72) |
| Q04 | PASS | Malformed announcements are refused and unknown fields discarded. [MulticastProtocolTest.kt:72](../../core/discovery/src/commonTest/kotlin/com/transfer/flash/core/discovery/multicast/MulticastProtocolTest.kt#L72) |
| Q05 | PASS | Explicit version mismatch is dropped before directory insertion while absent version is treated as legacy. [NsdTransportLogicTest.kt:710](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L710) |
| Q06 | PASS | Tests cover debounced refind, stale loss of an old name, stop clearing directory and repeated advertising replacing registration. [NsdTransportLogicTest.kt:508](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L508) |
| Q07 | PASS | Concurrent datagrams and sweeps serialize directory mutation in the JVM fixture. [MulticastTransportConcurrencyTest.kt:97](../../core/discovery/src/jvmTest/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransportConcurrencyTest.kt#L97) |
| Q08 | PASS | Resolve budget caps requests per window and shutdown suppresses new requests during restart close. [JmdnsResolveStormTest.kt:349](../../core/discovery/src/jvmTest/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsResolveStormTest.kt#L349) |
| Q09 | PASS | Disconnect cancels active group sends and clears state in the host fixture. [FlashPeerGroupSessionTest.kt:292](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/group/FlashPeerGroupSessionTest.kt#L292) |
| Q10 | PASS | Discovery backoff doubles to cap and reaches a terminal attempt limit. [DiscoveryRetryPolicyTest.kt:10](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/core/DiscoveryRetryPolicyTest.kt#L10) |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | PARTIAL | Fake NSD exercises Link changes, re-registering and pre/post API 34 code. Check Wi-Fi/AP transitions on real devices. [NsdTransportLogicTest.kt:1052](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L1052) |
| Q14 | PASS | Disconnect cancels active group sends and clears state in the host fixture. [FlashPeerGroupSessionTest.kt:292](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/group/FlashPeerGroupSessionTest.kt#L292) |
| Q15 | PARTIAL | Fake NSD exercises Link changes, re-registering and pre/post API 34 code. Check Wi-Fi/AP transitions on real devices. [NsdTransportLogicTest.kt:1052](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L1052) |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | PASS | Tests cover debounced refind, stale loss of an old name, stop clearing directory and repeated advertising replacing registration. [NsdTransportLogicTest.kt:508](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L508) |
| Q45 | PASS | Tests cover debounced refind, stale loss of an old name, stop clearing directory and repeated advertising replacing registration. [NsdTransportLogicTest.kt:508](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L508) |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | PASS | Concurrent datagrams and sweeps serialize directory mutation in the JVM fixture. [MulticastTransportConcurrencyTest.kt:97](../../core/discovery/src/jvmTest/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransportConcurrencyTest.kt#L97) |
| Q48 | PASS | Resolve budget caps requests per window and shutdown suppresses new requests during restart close. [JmdnsResolveStormTest.kt:349](../../core/discovery/src/jvmTest/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsResolveStormTest.kt#L349) |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | PASS | Discovery backoff doubles to cap and reaches a terminal attempt limit. [DiscoveryRetryPolicyTest.kt:10](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/core/DiscoveryRetryPolicyTest.kt#L10) |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | PASS | Discovery backoff doubles to cap and reaches a terminal attempt limit. [DiscoveryRetryPolicyTest.kt:10](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/core/DiscoveryRetryPolicyTest.kt#L10) |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | PARTIAL | Fake NSD exercises Link changes, re-registering and pre/post API 34 code. Check Wi-Fi/AP transitions on real devices. [NsdTransportLogicTest.kt:1052](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L1052) |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | PASS | Tests cover debounced refind, stale loss of an old name, stop clearing directory and repeated advertising replacing registration. [NsdTransportLogicTest.kt:508](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L508) |
| Q73 | PASS | Tests cover debounced refind, stale loss of an old name, stop clearing directory and repeated advertising replacing registration. [NsdTransportLogicTest.kt:508](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L508) |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | Fake NSD exercises Link changes, re-registering and pre/post API 34 code. Check Wi-Fi/AP transitions on real devices. [NsdTransportLogicTest.kt:1052](../../core/discovery/src/androidHostTest/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransportLogicTest.kt#L1052) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | FAIL | Planned native Wi-Fi Direct peer formation is absent. Existing LAN discovery cannot serve that scenario alone. [2026-11-15-feature-implementation-inventory.md](2026-11-15-feature-implementation-inventory.md) |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:network`

**Audit scope.** Connection establishment, TLS transport, sessions, frame limits, routing and queues. [WsFlashNetwork.kt](../../core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt), [BoundedSendQueue.kt](../../core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueue.kt), [WebSocketCodec.kt](../../core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WebSocketCodec.kt)

**Existing test reports.** jvmTest 339, testAndroidHostTest 428. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Duplicated and reordered frames are deduplicated in the tested network fixture. [ChaosResilienceTest.kt:65](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/resilience/ChaosResilienceTest.kt#L65) |
| Q07 | PASS | Closed queues refuse new sends. Tests cover racing producers, no eviction on full, and bounded blocking until room exists. [BoundedSendQueueTest.kt:57](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueueTest.kt#L57) |
| Q08 | PASS | Closed queues refuse new sends. Tests cover racing producers, no eviction on full, and bounded blocking until room exists. [BoundedSendQueueTest.kt:57](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueueTest.kt#L57) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | PASS | Missing ACK times out without shutting a live session. [LanSessionHardenedTest.kt:218](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/tcp/LanSessionHardenedTest.kt#L218) |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | PARTIAL | Host watcher tests are available. Device interface loss and recovery across a real transfer still need acceptance. [JvmNetworkWatcherTest.kt](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/JvmNetworkWatcherTest.kt) |
| Q13 | PARTIAL | Host watcher tests are available. Device interface loss and recovery across a real transfer still need acceptance. [JvmNetworkWatcherTest.kt](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/JvmNetworkWatcherTest.kt) |
| Q14 | PASS | Hard disconnect during drain requeues frames and reports typed failure. The stable reconnect scenario resets delay. [ChaosResilienceTest.kt:236](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/resilience/ChaosResilienceTest.kt#L236) |
| Q15 | PARTIAL | Host watcher tests are available. Device interface loss and recovery across a real transfer still need acceptance. [JvmNetworkWatcherTest.kt](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/JvmNetworkWatcherTest.kt) |
| Q16 | PASS | Named dials reject mismatched identity. InboundIdentityBindingTest adds shared-ID proof checks. [DialedIdBindingTest.kt:79](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/DialedIdBindingTest.kt#L79) |
| Q17 | UNVERIFIED | No scenario-specific evidence reviewed for authorization change at this module boundary. |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | PASS | Bounds are enforced at the frame header and across fragments. Truncation returns pool storage and Unicode round trips. [WebSocketCodecTest.kt:50](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WebSocketCodecTest.kt#L50) |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | PASS | Ping/pong can interleave with data fragments and invalid interleaved data messages are refused. [WebSocketFragmentControlTest.kt:33](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WebSocketFragmentControlTest.kt#L33) |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | PASS | Closed queues refuse new sends. Tests cover racing producers, no eviction on full, and bounded blocking until room exists. [BoundedSendQueueTest.kt:57](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueueTest.kt#L57) |
| Q48 | PASS | Closed queues refuse new sends. Tests cover racing producers, no eviction on full, and bounded blocking until room exists. [BoundedSendQueueTest.kt:57](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueueTest.kt#L57) |
| Q49 | PASS | Closed queues refuse new sends. Tests cover racing producers, no eviction on full, and bounded blocking until room exists. [BoundedSendQueueTest.kt:57](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/BoundedSendQueueTest.kt#L57) |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | PASS | Hard disconnect during drain requeues frames and reports typed failure. The stable reconnect scenario resets delay. [ChaosResilienceTest.kt:236](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/resilience/ChaosResilienceTest.kt#L236) |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | PARTIAL | Host watcher tests are available. Device interface loss and recovery across a real transfer still need acceptance. [JvmNetworkWatcherTest.kt](../../core/network/src/jvmTest/kotlin/com/transfer/flash/core/network/resilience/JvmNetworkWatcherTest.kt) |
| Q72 | UNVERIFIED | No scenario-specific evidence reviewed for stale discovery at this module boundary. |
| Q73 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate discovery at this module boundary. |
| Q74 | PASS | Named dials reject mismatched identity. InboundIdentityBindingTest adds shared-ID proof checks. [DialedIdBindingTest.kt:79](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/DialedIdBindingTest.kt#L79) |
| Q75 | PASS | Named dials reject mismatched identity. InboundIdentityBindingTest adds shared-ID proof checks. [DialedIdBindingTest.kt:79](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/DialedIdBindingTest.kt#L79) |
| Q76 | PASS | Bounds are enforced at the frame header and across fragments. Truncation returns pool storage and Unicode round trips. [WebSocketCodecTest.kt:50](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WebSocketCodecTest.kt#L50) |
| Q77 | PASS | Bounds are enforced at the frame header and across fragments. Truncation returns pool storage and Unicode round trips. [WebSocketCodecTest.kt:50](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WebSocketCodecTest.kt#L50) |
| Q78 | PASS | Ping/pong can interleave with data fragments and invalid interleaved data messages are refused. [WebSocketFragmentControlTest.kt:33](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/ws/WebSocketFragmentControlTest.kt#L33) |
| Q79 | PARTIAL | Fixture deduplication is checked. End-to-end replay safety depends on the application and crypto message-family rules. [ChaosResilienceTest.kt:65](../../core/network/src/androidHostTest/kotlin/com/transfer/flash/core/network/resilience/ChaosResilienceTest.kt#L65) |
| Q80 | UNVERIFIED | No scenario-specific evidence reviewed for bad checksum/signature at this module boundary. |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:enterprise`

**Audit scope.** Hub authentication, relay connection, signed directory/cache and Hub file and policy client. [OrgDirectorySync.kt](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySync.kt), [OrgSnapshotCoordinator.kt](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/OrgSnapshotCoordinator.kt), [EnterpriseDirectoryClient.kt](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseDirectoryClient.kt), [FlashRelayClient.kt](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt), [EnterpriseHub.kt](../../core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseHub.kt)

**Existing test reports.** jvmTest 283, testAndroidHostTest 171. Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Hub URL gate refuses credentials and bad input and restricts cleartext to its documented exceptions. [HubUrlPolicyTest.kt:21](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/HubUrlPolicyTest.kt#L21) |
| Q04 | PASS | Client decodes the documented Hub-group and directory field names on its REST contract. [EnterpriseEnrollmentAndDirectoryTest.kt:94](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/EnterpriseEnrollmentAndDirectoryTest.kt#L94) |
| Q05 | PARTIAL | Negotiated limits are observable from the relay facade. Test policy fallback with a Hub version that lacks the new fields. [FlashRelayClientTest.kt:71](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientTest.kt#L71) |
| Q06 | PASS | Racing starts open one relay connection and an older stop cannot disconnect the new one. [FlashRelayClientStartRaceTest.kt:51](../../core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientStartRaceTest.kt#L51) |
| Q07 | PASS | Racing starts open one relay connection and an older stop cannot disconnect the new one. [FlashRelayClientStartRaceTest.kt:51](../../core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientStartRaceTest.kt#L51) |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | PASS | Failed presence read yields no authoritative result. Tests also bound each poll and preserve coroutine cancellation. [HubPresenceTest.kt:44](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubPresenceTest.kt#L44) |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | PASS | A private key must be stored successfully before any public key is published. [HubSealingKeyManagerTest.kt:75](../../core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/HubSealingKeyManagerTest.kt#L75) |
| Q12 | PASS | Relay tests cover dropped links, jittered retry, URL switch sequencing and terminal credential revocation. [FlashRelayClientRecoveryTest.kt:82](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientRecoveryTest.kt#L82) |
| Q13 | PASS | Relay tests cover dropped links, jittered retry, URL switch sequencing and terminal credential revocation. [FlashRelayClientRecoveryTest.kt:82](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientRecoveryTest.kt#L82) |
| Q14 | PASS | Relay tests cover dropped links, jittered retry, URL switch sequencing and terminal credential revocation. [FlashRelayClientRecoveryTest.kt:82](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientRecoveryTest.kt#L82) |
| Q15 | PASS | Relay tests cover dropped links, jittered retry, URL switch sequencing and terminal credential revocation. [FlashRelayClientRecoveryTest.kt:82](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientRecoveryTest.kt#L82) |
| Q16 | UNVERIFIED | No scenario-specific evidence reviewed for authentication failure at this module boundary. |
| Q17 | PARTIAL | Unit source rejects revoked roster access. The server-to-device delivery and offline switch remain HR5/HUBREADY acceptance. [OrgDirectorySyncTest.kt:1383](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt#L1383) |
| Q18 | PASS | Verified revocation history survives reopen, expired signer cannot publish, and corrupt or unavailable storage fails closed. [HubRevocationCacheTest.kt:55](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubRevocationCacheTest.kt#L55) |
| Q19 | PASS | Verified revocation history survives reopen, expired signer cannot publish, and corrupt or unavailable storage fails closed. [HubRevocationCacheTest.kt:55](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubRevocationCacheTest.kt#L55) |
| Q20 | PASS | Lease fixture exercises valid, expiring, expired and wall-clock rollback states. Android and Windows UI integration remains separate. [OrgPeerResolverTest.kt:396](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgPeerResolverTest.kt#L396) |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | PASS | Verified revocation history survives reopen, expired signer cannot publish, and corrupt or unavailable storage fails closed. [HubRevocationCacheTest.kt:55](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubRevocationCacheTest.kt#L55) |
| Q24 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted migration at this module boundary. |
| Q25 | PASS | Verified revocation history survives reopen, expired signer cannot publish, and corrupt or unavailable storage fails closed. [HubRevocationCacheTest.kt:55](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubRevocationCacheTest.kt#L55) |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe path at this module boundary. |
| Q28 | UNVERIFIED | No scenario-specific evidence reviewed for changing file at this module boundary. |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | PASS | Failed presence read yields no authoritative result. Tests also bound each poll and preserve coroutine cancellation. [HubPresenceTest.kt:44](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubPresenceTest.kt#L44) |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | PASS | Racing starts open one relay connection and an older stop cannot disconnect the new one. [FlashRelayClientStartRaceTest.kt:51](../../core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientStartRaceTest.kt#L51) |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | PASS | Relay tests cover dropped links, jittered retry, URL switch sequencing and terminal credential revocation. [FlashRelayClientRecoveryTest.kt:82](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientRecoveryTest.kt#L82) |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | PASS | Relay tests cover dropped links, jittered retry, URL switch sequencing and terminal credential revocation. [FlashRelayClientRecoveryTest.kt:82](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClientRecoveryTest.kt#L82) |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | UNVERIFIED | No scenario-specific evidence reviewed for resume at this module boundary. |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | PASS | Hub URL gate refuses credentials and bad input and restricts cleartext to its documented exceptions. [HubUrlPolicyTest.kt:21](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/HubUrlPolicyTest.kt#L21) |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | UNVERIFIED | No scenario-specific evidence reviewed for bad checksum/signature at this module boundary. |
| Q81 | PASS | A private key must be stored successfully before any public key is published. [HubSealingKeyManagerTest.kt:75](../../core/enterprise/src/jvmTest/kotlin/com/transfer/flash/core/enterprise/HubSealingKeyManagerTest.kt#L75) |
| Q82 | PASS | Verified revocation history survives reopen, expired signer cannot publish, and corrupt or unavailable storage fails closed. [HubRevocationCacheTest.kt:55](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubRevocationCacheTest.kt#L55) |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | PASS | Failed second page leaves the prior snapshot intact. Stale serials and epoch rollback have companion cases. [OrgDirectorySyncTest.kt:250](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt#L250) |
| Q86 | UNVERIFIED | No scenario-specific evidence reviewed for newer schema at this module boundary. |
| Q87 | UNVERIFIED | No scenario-specific evidence reviewed for two database owners at this module boundary. |
| Q88 | PASS | Failed second page leaves the prior snapshot intact. Stale serials and epoch rollback have companion cases. [OrgDirectorySyncTest.kt:250](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt#L250) |
| Q89 | PARTIAL | Unit source rejects revoked roster access. The server-to-device delivery and offline switch remain HR5/HUBREADY acceptance. [OrgDirectorySyncTest.kt:1383](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/OrgDirectorySyncTest.kt#L1383) |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | PASS | Failed presence read yields no authoritative result. Tests also bound each poll and preserve coroutine cancellation. [HubPresenceTest.kt:44](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubPresenceTest.kt#L44) |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | PARTIAL | Lack of a presence result preserves unknown state at this boundary. Check host UI truthfulness while stale and reconnecting. [HubPresenceTest.kt:44](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubPresenceTest.kt#L44) |
| Q95 | PARTIAL | Lack of a presence result preserves unknown state at this boundary. Check host UI truthfulness while stale and reconnecting. [HubPresenceTest.kt:44](../../core/enterprise/src/sharedTest/kotlin/com/transfer/flash/core/enterprise/HubPresenceTest.kt#L44) |
| Q96 | PASS | Client decodes the documented Hub-group and directory field names on its REST contract. [EnterpriseEnrollmentAndDirectoryTest.kt:94](../../core/enterprise/src/commonTest/kotlin/com/transfer/flash/core/enterprise/EnterpriseEnrollmentAndDirectoryTest.kt#L94) |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:transfer`

**Audit scope.** Direct transfer state, chunk messages, storage, integrity and timeout, with layout and discovery delegated. [RealFlashTransferRepository.kt](../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/RealFlashTransferRepository.kt), [ReceivePipeline.kt](../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt), [MultiStreamDispatcher.kt](../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt), [TransferCompletionStateMachine.kt](../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/TransferCompletionStateMachine.kt)

**Existing test reports.** jvmTest 145, testAndroidHostTest 261. Newest file timestamp 2026-11-15.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | PASS | Rejects bad starting metadata, corrupt chunks, and unknown transfer IDs. Retransmission is ACKed without a duplicate write. [ReceivePipelineTest.kt:63](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L63) |
| Q03 | PASS | Rejects bad starting metadata, corrupt chunks, and unknown transfer IDs. Retransmission is ACKed without a duplicate write. [ReceivePipelineTest.kt:63](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L63) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Rejects bad starting metadata, corrupt chunks, and unknown transfer IDs. Retransmission is ACKed without a duplicate write. [ReceivePipelineTest.kt:63](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L63) |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | PASS | Cancel during verification abandons finalization and retains the cancelled row. [IncomingIntegrityTest.kt:290](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/IncomingIntegrityTest.kt#L290) |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | PARTIAL | Fixtures establish multi-stream flow. Real peer loss, bearer change, and user pause/resume races must be verified across both peers. [MultiStreamDispatcherTest.kt:252](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcherTest.kt#L252) |
| Q13 | PARTIAL | Fixtures establish multi-stream flow. Real peer loss, bearer change, and user pause/resume races must be verified across both peers. [MultiStreamDispatcherTest.kt:252](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcherTest.kt#L252) |
| Q14 | PARTIAL | Fixtures establish multi-stream flow. Real peer loss, bearer change, and user pause/resume races must be verified across both peers. [MultiStreamDispatcherTest.kt:252](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcherTest.kt#L252) |
| Q15 | PASS | Reconnect resumes only eligible failed sends. Repeated no-progress attempts stop at the configured cap. [TransferReconnectResumePolicyTest.kt:42](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/policy/TransferReconnectResumePolicyTest.kt#L42) |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PARTIAL | Persisted running row restores as retryable failure in host fixture. OS kill and durable bytes across installed host restart remain DESK-RESUME-01 and XFER-RETRY-02. [TransferRestoreTest.kt:112](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/TransferRestoreTest.kt#L112) |
| Q22 | PARTIAL | Persisted running row restores as retryable failure in host fixture. OS kill and durable bytes across installed host restart remain DESK-RESUME-01 and XFER-RETRY-02. [TransferRestoreTest.kt:112](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/TransferRestoreTest.kt#L112) |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | PASS | A simulated no-space write is handled as a transfer failure with no successful chunk ACK. [ReceivePipelineTest.kt:591](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L591) |
| Q27 | PARTIAL | Destination policy is unit-tested. Host root, SAF and junction/symlink behavior extend beyond these fixtures. [DestinationPolicyTest.kt](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/policy/DestinationPolicyTest.kt) |
| Q28 | PASS | Resume identity detects size, modification time, and prefix differences, including mismatched prefixes at equal size and time. [SourceIdentityTest.kt:9](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/SourceIdentityTest.kt#L9) |
| Q29 | PARTIAL | Rechecks restored sources before retry. XFER-RETRY-01 covers one phone empty case, and large-file/disk pressure needs broader device evidence. [TransferRefusalAndWordingTest.kt:287](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/TransferRefusalAndWordingTest.kt#L287) |
| Q30 | PARTIAL | Rechecks restored sources before retry. XFER-RETRY-01 covers one phone empty case, and large-file/disk pressure needs broader device evidence. [TransferRefusalAndWordingTest.kt:287](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/TransferRefusalAndWordingTest.kt#L287) |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | PASS | Non-ASCII file names round-trip through the offer frame. [WsTransferMessagesWireFormatTest.kt:77](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/protocol/WsTransferMessagesWireFormatTest.kt#L77) |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | PASS | A simulated no-space write is handled as a transfer failure with no successful chunk ACK. [ReceivePipelineTest.kt:591](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L591) |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | PASS | Known transfer failures receive specific user copy and unknown or missing cases use a fallback. [TransferRefusalAndWordingTest.kt:106](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/TransferRefusalAndWordingTest.kt#L106) |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | PASS | Retry re-announces durable coverage, including unchanged coverage after lost ACK and newly arrived chunks. [ReceivePipelineTest.kt:310](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L310) |
| Q57 | PASS | Resume identity detects size, modification time, and prefix differences, including mismatched prefixes at equal size and time. [SourceIdentityTest.kt:9](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/SourceIdentityTest.kt#L9) |
| Q58 | PASS | Reconnect resumes only eligible failed sends. Repeated no-progress attempts stop at the configured cap. [TransferReconnectResumePolicyTest.kt:42](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/policy/TransferReconnectResumePolicyTest.kt#L42) |
| Q59 | PARTIAL | Fixtures establish multi-stream flow. Real peer loss, bearer change, and user pause/resume races must be verified across both peers. [MultiStreamDispatcherTest.kt:252](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcherTest.kt#L252) |
| Q60 | PASS | Retry re-announces durable coverage, including unchanged coverage after lost ACK and newly arrived chunks. [ReceivePipelineTest.kt:310](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L310) |
| Q61 | PASS | Cancel during verification abandons finalization and retains the cancelled row. [IncomingIntegrityTest.kt:290](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/IncomingIntegrityTest.kt#L290) |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | PASS | Sender completion emits once under grace/receiver verification rules. [TransferCompletionStateMachineTest.kt:51](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/multistream/TransferCompletionStateMachineTest.kt#L51) |
| Q64 | PASS | A simulated no-space write is handled as a transfer failure with no successful chunk ACK. [ReceivePipelineTest.kt:591](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L591) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | PARTIAL | Fixtures establish multi-stream flow. Real peer loss, bearer change, and user pause/resume races must be verified across both peers. [MultiStreamDispatcherTest.kt:252](../../core/transfer/src/androidHostTest/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcherTest.kt#L252) |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | PASS | Rejects bad starting metadata, corrupt chunks, and unknown transfer IDs. Retransmission is ACKed without a duplicate write. [ReceivePipelineTest.kt:63](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L63) |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | PASS | Rejects bad starting metadata, corrupt chunks, and unknown transfer IDs. Retransmission is ACKed without a duplicate write. [ReceivePipelineTest.kt:63](../../core/transfer/src/commonTest/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipelineTest.kt#L63) |
| Q85 | UNVERIFIED | No scenario-specific evidence reviewed for conflicting rows at this module boundary. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:messaging`

**Audit scope.** Conversation, outbox, attachments, signed groups and Hub bridge with product presentation in ui:chat. [RealFlashChatRepository.kt](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt), [HubChatBridge.kt](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubChatBridge.kt), [GroupFrameCodec.kt](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupFrameCodec.kt)

**Existing test reports.** jvmTest 398, testAndroidHostTest 108. Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PARTIAL | Direct attachment rows now have a stable sender-scoped identity. ERROR-1025 records the concurrent insert regression. Group creation still has a second-tap gap (IF-06). Recipient device acceptance for attachment deduplication is UXPOL-17. [RealFlashChatRepositoryTest.kt:1048](../../core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryTest.kt#L1048) |
| Q07 | FAIL | Independent Create calls use fresh IDs and can create multiple directory groups. See IF-06. [RealFlashChatRepository.kt:8292](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8292) |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | PASS | Lost session transitions to Connecting briefly, then absence. Churn of one peer does not extend another peer's window. [PresenceHoldTest.kt:20](../../core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/PresenceHoldTest.kt#L20) |
| Q15 | PARTIAL | Join success requests forced sync and projects the local member in the fixture. Deployed approval and delayed-sync convergence still need evidence. [RealFlashChatRepositoryColleagueTest.kt:1749](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1749) |
| Q16 | PARTIAL | Client tests distinguish sealed from inspectable mode. A live policy change can leave a client using stale mode. See IF-02 below. [HubGroupE2eeTest.kt:486](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/hub/HubGroupE2eeTest.kt#L486) |
| Q17 | PARTIAL | Blocks a new personal link when policy disallows it. Existing links and stale policy after a runtime mode change need coverage. [RealFlashChatRepository.kt:8249](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8249) |
| Q18 | PASS | Linked-group revocation cannot be overridden by pairing in the source regression. [RealFlashChatRepositoryColleagueTest.kt:1388](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1388) |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PASS | Durable outbox survives repository/database reopen in a JVM fixture. [RealFlashChatRepositoryRestartRealDbTest.kt:78](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryRestartRealDbTest.kt#L78) |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | UNVERIFIED | No scenario-specific evidence reviewed for changing file at this module boundary. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | PASS | Tombstoned rows do not artificially shorten a group history page or extend its chain. [GroupHistorySyncTest.kt:562](../../core/messaging/src/androidHostTest/kotlin/com/transfer/flash/core/messaging/GroupHistorySyncTest.kt#L562) |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | PASS | Empty voice text does not make waveform bytes into a search hit. [RealFlashChatRepositorySearchMarkersTest.kt:78](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositorySearchMarkersTest.kt#L78) |
| Q35 | UNVERIFIED | No scenario-specific evidence reviewed for long labels at this module boundary. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | PARTIAL | Epoch rotation convergence is covered by host source. End-to-end on deployed Hub and devices remains HR10-DEV-01. [HubGroupE2eeTest.kt:333](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/hub/HubGroupE2eeTest.kt#L333) |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | FAIL | A failed member add is logged but the row is locally seeded and group creation returns Success. See IF-05 below. [RealFlashChatRepository.kt:8305](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8305) |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | UNVERIFIED | No scenario-specific evidence reviewed for pause race at this module boundary. |
| Q60 | UNVERIFIED | No scenario-specific evidence reviewed for resume at this module boundary. |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | FAIL | A failed member add is logged but the row is locally seeded and group creation returns Success. See IF-05 below. [RealFlashChatRepository.kt:8305](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8305) |
| Q65 | FAIL | Independent Create calls use fresh IDs and can create multiple directory groups. See IF-06. [RealFlashChatRepository.kt:8292](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8292) |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | PARTIAL | Epoch rotation convergence is covered by host source. End-to-end on deployed Hub and devices remains HR10-DEV-01. [HubGroupE2eeTest.kt:333](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/hub/HubGroupE2eeTest.kt#L333) |
| Q79 | PARTIAL | Epoch rotation convergence is covered by host source. End-to-end on deployed Hub and devices remains HR10-DEV-01. [HubGroupE2eeTest.kt:333](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/hub/HubGroupE2eeTest.kt#L333) |
| Q80 | PARTIAL | Client tests distinguish sealed from inspectable mode. A live policy change can leave a client using stale mode. See IF-02 below. [HubGroupE2eeTest.kt:486](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/hub/HubGroupE2eeTest.kt#L486) |
| Q81 | PARTIAL | Client tests distinguish sealed from inspectable mode. A live policy change can leave a client using stale mode. See IF-02 below. [HubGroupE2eeTest.kt:486](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/hub/HubGroupE2eeTest.kt#L486) |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | PASS | Linked-group revocation cannot be overridden by pairing in the source regression. [RealFlashChatRepositoryColleagueTest.kt:1388](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1388) |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | UNVERIFIED | No scenario-specific evidence reviewed for conflicting rows at this module boundary. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | PARTIAL | Group presence now uses Hub-online peers in the regression. Live roster refresh and policy invalidation remain open. [RealFlashChatRepositoryColleagueTest.kt:1692](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1692) |
| Q89 | PARTIAL | Blocks a new personal link when policy disallows it. Existing links and stale policy after a runtime mode change need coverage. [RealFlashChatRepository.kt:8249](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8249) |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | FAIL | A failed member add is logged but the row is locally seeded and group creation returns Success. See IF-05 below. [RealFlashChatRepository.kt:8305](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8305) |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | PASS | Offline announcement search falls back to local Room and reconnect fetch tests exist. [RealFlashChatRepositoryAnnouncementsTest.kt:455](../../core/messaging/src/jvmTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryAnnouncementsTest.kt#L455) |
| Q95 | FAIL | A failed member add is logged but the row is locally seeded and group creation returns Success. See IF-05 below. [RealFlashChatRepository.kt:8305](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8305) |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | FAIL | The invite path consumes a local signed-group credential. Hub directory group invites by link are still missing. See IF-03 below. [RealFlashChatRepository.kt:2283](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L2283) |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:calling`

**Audit scope.** Call signaling, membership, media acquisition and disposal; local-file and database state are outside the call engine. [CallCoordinator.kt](../../core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt), [FlashCallSession.kt](../../core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt), [CallFrameCodec.kt](../../core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallFrameCodec.kt)

**Existing test reports.** jvmTest 246, testAndroidHostTest 396, testDebugUnitTest 72. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Call signaling codec tests valid round trips and rejection cases at parsing boundary. [CallFrameCodecTest.kt](../../core/calling/src/commonTest/kotlin/com/transfer/flash/core/calling/protocol/CallFrameCodecTest.kt) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate requests at this module boundary. |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | PASS | Denied mic ends the attempt without retry, and cancellation propagates. [MediaAcquireTest.kt:57](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/MediaAcquireTest.kt#L57) |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | PARTIAL | Native JVM peer can create relay-only offer. TURN/Doze and end-to-end disconnect/reconnect remain HR9-DEV-01/02. [OffLanCallSignalingTest.kt:218](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L218) |
| Q13 | PARTIAL | Native JVM peer can create relay-only offer. TURN/Doze and end-to-end disconnect/reconnect remain HR9-DEV-01/02. [OffLanCallSignalingTest.kt:218](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L218) |
| Q14 | PARTIAL | Native JVM peer can create relay-only offer. TURN/Doze and end-to-end disconnect/reconnect remain HR9-DEV-01/02. [OffLanCallSignalingTest.kt:218](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L218) |
| Q15 | PARTIAL | Native JVM peer can create relay-only offer. TURN/Doze and end-to-end disconnect/reconnect remain HR9-DEV-01/02. [OffLanCallSignalingTest.kt:218](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L218) |
| Q16 | PASS | Rejects forged sender or unauthorized hangup before dispatch. [CallCoordinatorSecurityTest.kt:59](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/CallCoordinatorSecurityTest.kt#L59) |
| Q17 | PASS | Nonmembers cannot add a tile through an invite or relayed join. [FlashGroupCallMembershipFilterTest.kt:42](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/FlashGroupCallMembershipFilterTest.kt#L42) |
| Q18 | PASS | Host fixtures reject expired, skewed, replayed and stale-sequence signals and tear down active calls after peer revocation. [OffLanCallSignalingTest.kt:99](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L99) |
| Q19 | PASS | Host fixtures reject expired, skewed, replayed and stale-sequence signals and tear down active calls after peer revocation. [OffLanCallSignalingTest.kt:99](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L99) |
| Q20 | PASS | Host fixtures reject expired, skewed, replayed and stale-sequence signals and tear down active calls after peer revocation. [OffLanCallSignalingTest.kt:99](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L99) |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | PASS | Host fixtures reject expired, skewed, replayed and stale-sequence signals and tear down active calls after peer revocation. [OffLanCallSignalingTest.kt:99](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L99) |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | PASS | Camera refusal falls back to audio with reason, and failed mic acquisition reports the microphone. [MediaAcquireTest.kt:34](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/MediaAcquireTest.kt#L34) |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | PASS | Denied mic ends the attempt without retry, and cancellation propagates. [MediaAcquireTest.kt:57](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/MediaAcquireTest.kt#L57) |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | PASS | Camera refusal falls back to audio with reason, and failed mic acquisition reports the microphone. [MediaAcquireTest.kt:34](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/MediaAcquireTest.kt#L34) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PARTIAL | JVM offer and media enumeration covered on an available stack. Linux without audio and Android device behavior need native checks. [DesktopMediaStackSmokeTest.kt:62](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/DesktopMediaStackSmokeTest.kt#L62) |
| Q68 | PARTIAL | JVM offer and media enumeration covered on an available stack. Linux without audio and Android device behavior need native checks. [DesktopMediaStackSmokeTest.kt:62](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/DesktopMediaStackSmokeTest.kt#L62) |
| Q69 | PASS | Camera refusal falls back to audio with reason, and failed mic acquisition reports the microphone. [MediaAcquireTest.kt:34](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/MediaAcquireTest.kt#L34) |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | PARTIAL | Native JVM peer can create relay-only offer. TURN/Doze and end-to-end disconnect/reconnect remain HR9-DEV-01/02. [OffLanCallSignalingTest.kt:218](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L218) |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | PASS | Rejects forged sender or unauthorized hangup before dispatch. [CallCoordinatorSecurityTest.kt:59](../../core/calling/src/androidHostTest/kotlin/com/transfer/flash/core/calling/CallCoordinatorSecurityTest.kt#L59) |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | PASS | Host fixtures reject expired, skewed, replayed and stale-sequence signals and tear down active calls after peer revocation. [OffLanCallSignalingTest.kt:99](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L99) |
| Q79 | PASS | Host fixtures reject expired, skewed, replayed and stale-sequence signals and tear down active calls after peer revocation. [OffLanCallSignalingTest.kt:99](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/OffLanCallSignalingTest.kt#L99) |
| Q80 | UNVERIFIED | No scenario-specific evidence reviewed for bad checksum/signature at this module boundary. |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | JVM offer and media enumeration covered on an available stack. Linux without audio and Android device behavior need native checks. [DesktopMediaStackSmokeTest.kt:62](../../core/calling/src/jvmTest/kotlin/com/transfer/flash/core/calling/DesktopMediaStackSmokeTest.kt#L62) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:ptt`

**Audit scope.** Push-to-talk floor, PCM capture and playout, codecs and lifecycle, with no durable content rows. [PttSessionEngine.kt](../../core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt), [PttFloorMachine.kt](../../core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachine.kt), [PttJitterBuffer.kt](../../core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBuffer.kt)

**Existing test reports.** jvmTest 148, testAndroidHostTest 125, testDebugUnitTest 19. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | PASS | Queued press after stop cannot open capture. Blank IDs, shutdown and missing audio capabilities are also tested across PTT suites. [PttSessionEngineSessionTest.kt:170](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L170) |
| Q03 | PASS | Malformed frame and negotiated PCM limits are enforced by the audio codec. [PttAudioFrameTest.kt:51](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/protocol/PttAudioFrameTest.kt#L51) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Jitter buffer orders, deduplicates and bounds packets, dropping newest on overflow. [PttJitterBufferTest.kt:19](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBufferTest.kt#L19) |
| Q07 | PASS | Explicit collision window resolves racing floor claims. Stale terminal events do not prune active talk. [PttFloorMachineTest.kt:23](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachineTest.kt#L23) |
| Q08 | PASS | Queued press after stop cannot open capture. Blank IDs, shutdown and missing audio capabilities are also tested across PTT suites. [PttSessionEngineSessionTest.kt:170](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L170) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | UNVERIFIED | No scenario-specific evidence reviewed for peer disconnect at this module boundary. |
| Q15 | UNVERIFIED | No scenario-specific evidence reviewed for peer reconnect at this module boundary. |
| Q16 | PASS | Capture is not sent to self or to untrusted peers. [PttSessionEngineSessionTest.kt:230](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L230) |
| Q17 | UNVERIFIED | No scenario-specific evidence reviewed for authorization change at this module boundary. |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | PARTIAL | Core shutdown stops audio and clears state. OS background/permissions need host and hardware checks. [PttSessionEngineSessionTest.kt:327](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L327) |
| Q43 | PARTIAL | Core shutdown stops audio and clears state. OS background/permissions need host and hardware checks. [PttSessionEngineSessionTest.kt:327](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L327) |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | PASS | Explicit collision window resolves racing floor claims. Stale terminal events do not prune active talk. [PttFloorMachineTest.kt:23](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachineTest.kt#L23) |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | PASS | Jitter buffer orders, deduplicates and bounds packets, dropping newest on overflow. [PttJitterBufferTest.kt:19](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBufferTest.kt#L19) |
| Q48 | PASS | Jitter buffer orders, deduplicates and bounds packets, dropping newest on overflow. [PttJitterBufferTest.kt:19](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBufferTest.kt#L19) |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | PASS | JVM output failure is reported once and releases the line. [JvmPttPlayoutTest.kt:81](../../core/ptt/src/jvmTest/kotlin/com/transfer/flash/core/ptt/JvmPttPlayoutTest.kt#L81) |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PARTIAL | A host with no audio lines does not throw in the fixture. Actual device acquisition can differ. [JvmPttWarmUpTest.kt:57](../../core/ptt/src/jvmTest/kotlin/com/transfer/flash/core/ptt/JvmPttWarmUpTest.kt#L57) |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | PARTIAL | Core shutdown stops audio and clears state. OS background/permissions need host and hardware checks. [PttSessionEngineSessionTest.kt:327](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L327) |
| Q70 | PARTIAL | Core shutdown stops audio and clears state. OS background/permissions need host and hardware checks. [PttSessionEngineSessionTest.kt:327](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L327) |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | PASS | Malformed frame and negotiated PCM limits are enforced by the audio codec. [PttAudioFrameTest.kt:51](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/protocol/PttAudioFrameTest.kt#L51) |
| Q77 | PASS | Malformed frame and negotiated PCM limits are enforced by the audio codec. [PttAudioFrameTest.kt:51](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/protocol/PttAudioFrameTest.kt#L51) |
| Q78 | PASS | Jitter buffer orders, deduplicates and bounds packets, dropping newest on overflow. [PttJitterBufferTest.kt:19](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBufferTest.kt#L19) |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | UNVERIFIED | No scenario-specific evidence reviewed for bad checksum/signature at this module boundary. |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | PASS | Capture is not sent to self or to untrusted peers. [PttSessionEngineSessionTest.kt:230](../../core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttSessionEngineSessionTest.kt#L230) |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:radio`

**Audit scope.** Bluetooth/serial gateway, KISS/AX.25, text/ACK reliability and profile state with no general file transfer or screen layout. [RadioGatewayManager.kt](../../core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt), [RadioChatEngine.kt](../../core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngine.kt), [KissFrameCodec.kt](../../core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt)

**Existing test reports.** jvmTest 274, testAndroidHostTest 193. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Codec fixtures cover escaping, framing corruption and parser recovery. [KissFrameCodecTest.kt](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodecTest.kt) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Duplicate text frames deliver once. Retry reuses a frame and replay is suppressed in the simulated link. [RadioChatEngineTest.kt:235](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L235) |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | PASS | Scan cancellation ends discovery and reconnect attempt cap yields a prompt instead of looping. [RadioGatewayManagerTest.kt:249](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L249) |
| Q10 | PASS | Scan cancellation ends discovery and reconnect attempt cap yields a prompt instead of looping. [RadioGatewayManagerTest.kt:249](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L249) |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | PARTIAL | Driver-level reconnect is scripted. Android Bluetooth revocation, serial and Windows hardware need RADCONL/RADCHAT checks. [RadioGatewayManagerTest.kt:283](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L283) |
| Q15 | PARTIAL | Driver-level reconnect is scripted. Android Bluetooth revocation, serial and Windows hardware need RADCONL/RADCHAT checks. [RadioGatewayManagerTest.kt:283](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L283) |
| Q16 | PASS | Simulated wrong-key frames stay silent and are rejected. RadioCryptoVectorsTest covers ciphertext authenticity. [RadioChatEngineTest.kt:278](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L278) |
| Q17 | UNVERIFIED | No scenario-specific evidence reviewed for authorization change at this module boundary. |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | PASS | ACK tracking is bounded, expires entries and refuses receipts for old entries. [PendingAckTableTest.kt:15](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/PendingAckTableTest.kt#L15) |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | PASS | Every enumerated connection failure gets user-facing text and a next step. [RadioConnectLogicTest.kt:157](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/connect/RadioConnectLogicTest.kt#L157) |
| Q55 | PASS | Scan cancellation ends discovery and reconnect attempt cap yields a prompt instead of looping. [RadioGatewayManagerTest.kt:249](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L249) |
| Q56 | PASS | Duplicate text frames deliver once. Retry reuses a frame and replay is suppressed in the simulated link. [RadioChatEngineTest.kt:235](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L235) |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | PASS | Scan cancellation ends discovery and reconnect attempt cap yields a prompt instead of looping. [RadioGatewayManagerTest.kt:249](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L249) |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | PARTIAL | Simulated text and cryptographic ACK round-trip. Durable recipient application receipt is still a separate relay-mode requirement. [RadioChatEngineTest.kt:149](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L149) |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | PARTIAL | Driver-level reconnect is scripted. Android Bluetooth revocation, serial and Windows hardware need RADCONL/RADCHAT checks. [RadioGatewayManagerTest.kt:283](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L283) |
| Q69 | PARTIAL | Driver-level reconnect is scripted. Android Bluetooth revocation, serial and Windows hardware need RADCONL/RADCHAT checks. [RadioGatewayManagerTest.kt:283](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L283) |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | UNVERIFIED | No scenario-specific evidence reviewed for stale discovery at this module boundary. |
| Q73 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate discovery at this module boundary. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | UNVERIFIED | No scenario-specific evidence reviewed for truncated frame at this module boundary. |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | PASS | Duplicate text frames deliver once. Retry reuses a frame and replay is suppressed in the simulated link. [RadioChatEngineTest.kt:235](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L235) |
| Q80 | PASS | Simulated wrong-key frames stay silent and are rejected. RadioCryptoVectorsTest covers ciphertext authenticity. [RadioChatEngineTest.kt:278](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L278) |
| Q81 | PASS | Simulated wrong-key frames stay silent and are rejected. RadioCryptoVectorsTest covers ciphertext authenticity. [RadioChatEngineTest.kt:278](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngineTest.kt#L278) |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | Driver-level reconnect is scripted. Android Bluetooth revocation, serial and Windows hardware need RADCONL/RADCHAT checks. [RadioGatewayManagerTest.kt:283](../../core/radio/src/commonTest/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManagerTest.kt#L283) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | FAIL | Field Companion text exists. Relay, Walkie-Talkie Bridge, Tactical Radar, and All-in-One are pending. [RADIO-MODES-IMPLEMENTATION.md](../plans/RADIO-MODES-IMPLEMENTATION.md) |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:swarm`

**Audit scope.** Swarm protocol, piece scheduling, persisted content state and security validation with no UI layout or device permission owner. [SwarmEngine.kt](../../core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/SwarmEngine.kt), [SwarmDriver.kt](../../core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt), [SwarmFrameCodec.kt](../../core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/codec/SwarmFrameCodec.kt)

**Existing test reports.** jvmTest 191, testAndroidHostTest 172. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Codec fuzz cases reject truncation, trailing bytes and duplicate requested pieces. [SwarmPropertyTest.kt:53](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/codec/SwarmPropertyTest.kt#L53) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Codec fuzz cases reject truncation, trailing bytes and duplicate requested pieces. [SwarmPropertyTest.kt:53](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/codec/SwarmPropertyTest.kt#L53) |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | PASS | A thrown persistence write does not kill the worker for later writes. This does not ensure the failed write was saved. [SwarmDriverPersistFailureTest.kt:88](../../core/swarm/src/jvmTest/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriverPersistFailureTest.kt#L88) |
| Q12 | PARTIAL | Holder-reconnect scenarios exist in the engine fixture. Multi-peer path recovery across real networks remains pending. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q13 | PARTIAL | Holder-reconnect scenarios exist in the engine fixture. Multi-peer path recovery across real networks remains pending. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q14 | PARTIAL | Holder-reconnect scenarios exist in the engine fixture. Multi-peer path recovery across real networks remains pending. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q15 | PARTIAL | Holder-reconnect scenarios exist in the engine fixture. Multi-peer path recovery across real networks remains pending. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q16 | PASS | Nonmember summaries and HaveAll cannot alter state or count delivery. [SwarmNonMemberFramesTest.kt:53](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmNonMemberFramesTest.kt#L53) |
| Q17 | UNVERIFIED | No scenario-specific evidence reviewed for authorization change at this module boundary. |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PASS | Fixture restores partial receiver coverage, resumes against a holder and reruns interrupted finalization. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe path at this module boundary. |
| Q28 | PARTIAL | Unreadable origin announces source loss. Readable files whose content changes in place need separate evidence. [SwarmOriginSourceLostTest.kt:76](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmOriginSourceLostTest.kt#L76) |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | PASS | Modeled demand shows queued members eventually get served instead of permanent displacement. [SwarmServeFairnessTest.kt:204](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmServeFairnessTest.kt#L204) |
| Q50 | PASS | A thrown persistence write does not kill the worker for later writes. This does not ensure the failed write was saved. [SwarmDriverPersistFailureTest.kt:88](../../core/swarm/src/jvmTest/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriverPersistFailureTest.kt#L88) |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | UNVERIFIED | No scenario-specific evidence reviewed for pause race at this module boundary. |
| Q60 | PASS | Fixture restores partial receiver coverage, resumes against a holder and reruns interrupted finalization. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q61 | PASS | Verified cancellation applies a tombstone, forged signatures are refused and completed files survive late cancel. [SwarmCancelLifecycleTest.kt:104](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmCancelLifecycleTest.kt#L104) |
| Q62 | PASS | Verified cancellation applies a tombstone, forged signatures are refused and completed files survive late cancel. [SwarmCancelLifecycleTest.kt:104](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmCancelLifecycleTest.kt#L104) |
| Q63 | PASS | Fixture restores partial receiver coverage, resumes against a holder and reruns interrupted finalization. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q64 | PASS | Swarm broadcasts source loss once and retains it across restart. [SwarmOriginSourceLostTest.kt:85](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmOriginSourceLostTest.kt#L85) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | PARTIAL | Holder-reconnect scenarios exist in the engine fixture. Multi-peer path recovery across real networks remains pending. [SwarmRestartRestoreTest.kt:88](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmRestartRestoreTest.kt#L88) |
| Q72 | UNVERIFIED | No scenario-specific evidence reviewed for stale discovery at this module boundary. |
| Q73 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate discovery at this module boundary. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | PASS | Codec fuzz cases reject truncation, trailing bytes and duplicate requested pieces. [SwarmPropertyTest.kt:53](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/codec/SwarmPropertyTest.kt#L53) |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | PASS | Verified cancellation applies a tombstone, forged signatures are refused and completed files survive late cancel. [SwarmCancelLifecycleTest.kt:104](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmCancelLifecycleTest.kt#L104) |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | PASS | Nonmember summaries and HaveAll cannot alter state or count delivery. [SwarmNonMemberFramesTest.kt:53](../../core/swarm/src/commonTest/kotlin/com/transfer/flash/core/swarm/engine/SwarmNonMemberFramesTest.kt#L53) |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | UNVERIFIED | No scenario-specific evidence reviewed for conflicting rows at this module boundary. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:core:engine`

**Audit scope.** Public SDK facade and composition, inbound routing, storage and transfer handover, with screen layout owned by hosts. [Flash.kt](../../core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt), [FlashEngine.kt](../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt), [FlashInboundRouter.kt](../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt), [TransferContinuityCoordinator.kt](../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/hubfiles/TransferContinuityCoordinator.kt)

**Existing test reports.** jvmTest 189, testAndroidHostTest 130. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Short magic frames do not throw, and unbound reserved magic cannot reach a generic transfer route. [MagicFrameRouterTest.kt:11](../../core/engine/src/commonTest/kotlin/com/transfer/flash/core/engine/MagicFrameRouterTest.kt#L11) |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Unattached optional calling has a safe default. Duplicate attach is ignored and detach stops routing. [DefaultFlashEngineTest.kt:197](../../core/engine/src/androidHostTest/kotlin/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt#L197) |
| Q07 | PASS | Continuity refuses changed digest, wrong peer, tampered partial, route race and cancel. [TransferContinuityTest.kt:325](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/TransferContinuityTest.kt#L325) |
| Q08 | PASS | Unattached optional calling has a safe default. Duplicate attach is ignored and detach stops routing. [DefaultFlashEngineTest.kt:197](../../core/engine/src/androidHostTest/kotlin/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt#L197) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | PARTIAL | Continuity survives fixture recreation and normal Retry. HR8-DEV-01 and DESK-RESUME-01 cover real process/network changes. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q14 | PARTIAL | Continuity survives fixture recreation and normal Retry. HR8-DEV-01 and DESK-RESUME-01 cover real process/network changes. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q15 | PARTIAL | Continuity survives fixture recreation and normal Retry. HR8-DEV-01 and DESK-RESUME-01 cover real process/network changes. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q16 | UNVERIFIED | No scenario-specific evidence reviewed for authentication failure at this module boundary. |
| Q17 | UNVERIFIED | No scenario-specific evidence reviewed for authorization change at this module boundary. |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PARTIAL | Continuity survives fixture recreation and normal Retry. HR8-DEV-01 and DESK-RESUME-01 cover real process/network changes. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q22 | PARTIAL | Continuity survives fixture recreation and normal Retry. HR8-DEV-01 and DESK-RESUME-01 cover real process/network changes. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted migration at this module boundary. |
| Q25 | PASS | Lost passphrase moves encrypted DB and sidecars aside. This preserves files for diagnosis while starting fresh. [EncryptedDatabaseRecoveryTest.kt:30](../../core/engine/src/androidHostTest/kotlin/com/transfer/flash/core/engine/store/EncryptedDatabaseRecoveryTest.kt#L30) |
| Q26 | PASS | Storage failure fixtures simulate failed write and flush. Review corresponding receive router error handling. [FlashInboundRouterTest.kt:847](../../core/engine/src/commonTest/kotlin/com/transfer/flash/core/engine/FlashInboundRouterTest.kt#L847) |
| Q27 | PASS | Normalizes separators and removes parent path segments at file-name sanitization boundary. [FlashPathSanitizerTest.kt:29](../../core/engine/src/commonTest/kotlin/com/transfer/flash/core/engine/FlashPathSanitizerTest.kt#L29) |
| Q28 | UNVERIFIED | No scenario-specific evidence reviewed for changing file at this module boundary. |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | PASS | Unattached optional calling has a safe default. Duplicate attach is ignored and detach stops routing. [DefaultFlashEngineTest.kt:197](../../core/engine/src/androidHostTest/kotlin/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt#L197) |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | PASS | Storage failure fixtures simulate failed write and flush. Review corresponding receive router error handling. [FlashInboundRouterTest.kt:847](../../core/engine/src/commonTest/kotlin/com/transfer/flash/core/engine/FlashInboundRouterTest.kt#L847) |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | PASS | Loopback LAN to Hub and reverse adopt checked coverage and prevent duplicate finalization after handover. [CrossRouteWiringIntegrationTest.kt:170](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L170) |
| Q57 | PASS | Continuity refuses changed digest, wrong peer, tampered partial, route race and cancel. [TransferContinuityTest.kt:325](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/TransferContinuityTest.kt#L325) |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | UNVERIFIED | No scenario-specific evidence reviewed for pause race at this module boundary. |
| Q60 | PARTIAL | Continuity survives fixture recreation and normal Retry. HR8-DEV-01 and DESK-RESUME-01 cover real process/network changes. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q61 | PASS | Loopback LAN to Hub and reverse adopt checked coverage and prevent duplicate finalization after handover. [CrossRouteWiringIntegrationTest.kt:170](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L170) |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | PASS | Loopback LAN to Hub and reverse adopt checked coverage and prevent duplicate finalization after handover. [CrossRouteWiringIntegrationTest.kt:170](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L170) |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | PASS | Unattached optional calling has a safe default. Duplicate attach is ignored and detach stops routing. [DefaultFlashEngineTest.kt:197](../../core/engine/src/androidHostTest/kotlin/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt#L197) |
| Q66 | PASS | Unattached optional calling has a safe default. Duplicate attach is ignored and detach stops routing. [DefaultFlashEngineTest.kt:197](../../core/engine/src/androidHostTest/kotlin/com/transfer/flash/core/engine/DefaultFlashEngineTest.kt#L197) |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | UNVERIFIED | No scenario-specific evidence reviewed for stale discovery at this module boundary. |
| Q73 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate discovery at this module boundary. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | UNVERIFIED | No scenario-specific evidence reviewed for unexpected identity at this module boundary. |
| Q76 | PASS | Short magic frames do not throw, and unbound reserved magic cannot reach a generic transfer route. [MagicFrameRouterTest.kt:11](../../core/engine/src/commonTest/kotlin/com/transfer/flash/core/engine/MagicFrameRouterTest.kt#L11) |
| Q77 | UNVERIFIED | No scenario-specific evidence reviewed for oversized frame at this module boundary. |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | PASS | Continuity refuses changed digest, wrong peer, tampered partial, route race and cancel. [TransferContinuityTest.kt:325](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/TransferContinuityTest.kt#L325) |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | UNVERIFIED | No scenario-specific evidence reviewed for conflicting rows at this module boundary. |
| Q86 | UNVERIFIED | No scenario-specific evidence reviewed for newer schema at this module boundary. |
| Q87 | UNVERIFIED | No scenario-specific evidence reviewed for two database owners at this module boundary. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:ui:theme`

**Audit scope.** Theme tokens, avatar defaults, motion, feedback and splash; feature state and transport are supplied by callers. [FlashTheme.kt](../../ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashTheme.kt), [FlashColors.kt](../../ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashColors.kt), [FlashLaunchSplash.kt](../../ui/theme/src/commonMain/kotlin/com/transfer/flash/ui/theme/FlashLaunchSplash.kt)

**Existing test reports.** jvmTest 69, testAndroidHostTest 58. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | PASS | Null system colors and platforms below the dynamic-color API retain the Flash palette. [FlashDynamicAccentTest.kt:17](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashDynamicAccentTest.kt#L17) |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | N/A | Outside this module’s responsibility as described above. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | PASS | Startup hold is bounded and reduced motion skips the exit fade in splash logic. [FlashLaunchSplashTest.kt:29](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashLaunchSplashTest.kt#L29) |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | UNVERIFIED | No scenario-specific evidence reviewed for long labels at this module boundary. |
| Q36 | PARTIAL | Valid geometry and theme tokens are tested. Real reading order, contrast and font scaling depend on the consuming screen. [FlashThemeTokensTest.kt:31](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashThemeTokensTest.kt#L31) |
| Q37 | PARTIAL | Valid geometry and theme tokens are tested. Real reading order, contrast and font scaling depend on the consuming screen. [FlashThemeTokensTest.kt:31](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashThemeTokensTest.kt#L31) |
| Q38 | PARTIAL | Valid geometry and theme tokens are tested. Real reading order, contrast and font scaling depend on the consuming screen. [FlashThemeTokensTest.kt:31](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashThemeTokensTest.kt#L31) |
| Q39 | PASS | Startup hold is bounded and reduced motion skips the exit fade in splash logic. [FlashLaunchSplashTest.kt:29](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashLaunchSplashTest.kt#L29) |
| Q40 | PARTIAL | Tiny avatar geometry has a positive initial-size floor at the token/math boundary. [FlashAvatarTest.kt:44](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/avatar/FlashAvatarTest.kt#L44) |
| Q41 | UNVERIFIED | No scenario-specific evidence reviewed for resize at this module boundary. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PASS | Null system colors and platforms below the dynamic-color API retain the Flash palette. [FlashDynamicAccentTest.kt:17](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashDynamicAccentTest.kt#L17) |
| Q68 | PASS | Null system colors and platforms below the dynamic-color API retain the Flash palette. [FlashDynamicAccentTest.kt:17](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashDynamicAccentTest.kt#L17) |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PASS | Dynamic accent explicitly falls back on unsupported Android API levels. [FlashDynamicAccentTest.kt:17](../../ui/theme/src/commonTest/kotlin/com/transfer/flash/ui/theme/FlashDynamicAccentTest.kt#L17) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | N/A | Outside this module’s responsibility as described above. |

### `:ui:platform-shims`

**Audit scope.** Platform permission, media, picker, clipboard and back-handler seams; trust and app protocol stay in callers. [FlashPermissions.android.kt](../../ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashPermissions.android.kt), [FlashVideoSurface.android.kt](../../ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.android.kt), [FlashVideoSurface.jvm.kt](../../ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.jvm.kt)

**Existing test reports.** jvmTest 43, testAndroidHostTest 23. Newest file timestamp 2026-11-15.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | PASS | Local-file resolver rejects missing or empty paths and canonicalizes the accepted path. Directory authorization is a separate host responsibility. [FlashMediaDecoderSecurityTest.kt:11](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashMediaDecoderSecurityTest.kt#L11) |
| Q02 | PASS | Local-file resolver rejects missing or empty paths and canonicalizes the accepted path. Directory authorization is a separate host responsibility. [FlashMediaDecoderSecurityTest.kt:11](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashMediaDecoderSecurityTest.kt#L11) |
| Q03 | PASS | Desktop playback classifies missing/invalid sources and treats Android content URIs as unusable. [FlashAudioPlayerJvmTest.kt:32](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayerJvmTest.kt#L32) |
| Q04 | PASS | Desktop picker defines all-files and MIME-family fallbacks with no filter for an empty request. [FlashFilePickerJvmTest.kt:23](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/FlashFilePickerJvmTest.kt#L23) |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | PASS | Concurrent permission requests queue, denial reaches the right caller, and cancel does not steal the next result. [PermissionRequestArbiterTest.kt:36](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiterTest.kt#L36) |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | PASS | Concurrent permission requests queue, denial reaches the right caller, and cancel does not steal the next result. [PermissionRequestArbiterTest.kt:36](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiterTest.kt#L36) |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | PASS | Host fixture restores a saved camera capture path and rejects missing or zero-length captures. [FlashCameraCaptureRestoreTest.kt:35](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashCameraCaptureRestoreTest.kt#L35) |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | PASS | Local-file resolver rejects missing or empty paths and canonicalizes the accepted path. Directory authorization is a separate host responsibility. [FlashMediaDecoderSecurityTest.kt:11](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashMediaDecoderSecurityTest.kt#L11) |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | PASS | Host fixture restores a saved camera capture path and rejects missing or zero-length captures. [FlashCameraCaptureRestoreTest.kt:35](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/FlashCameraCaptureRestoreTest.kt#L35) |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | PARTIAL | Previously recorded host decode, seek, pause and replay. Installed package, unsupported codec and lifecycle behavior remain MEDIA-PLAY-01..04. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | PARTIAL | Previously recorded host decode, seek, pause and replay. Installed package, unsupported codec and lifecycle behavior remain MEDIA-PLAY-01..04. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q45 | PASS | Concurrent permission requests queue, denial reaches the right caller, and cancel does not steal the next result. [PermissionRequestArbiterTest.kt:36](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiterTest.kt#L36) |
| Q46 | PARTIAL | Previously recorded host decode, seek, pause and replay. Installed package, unsupported codec and lifecycle behavior remain MEDIA-PLAY-01..04. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q47 | PASS | Concurrent permission requests queue, denial reaches the right caller, and cancel does not steal the next result. [PermissionRequestArbiterTest.kt:36](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiterTest.kt#L36) |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | PARTIAL | Previously recorded host decode, seek, pause and replay. Installed package, unsupported codec and lifecycle behavior remain MEDIA-PLAY-01..04. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | PARTIAL | Previously recorded host decode, seek, pause and replay. Installed package, unsupported codec and lifecycle behavior remain MEDIA-PLAY-01..04. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | PASS | Concurrent permission requests queue, denial reaches the right caller, and cancel does not steal the next result. [PermissionRequestArbiterTest.kt:36](../../ui/platform-shims/src/androidHostTest/kotlin/com/transfer/flash/ui/shims/PermissionRequestArbiterTest.kt#L36) |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | Previously recorded host decode, seek, pause and replay. Installed package, unsupported codec and lifecycle behavior remain MEDIA-PLAY-01..04. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:ui:chat`

**Audit scope.** Shared screens, composer, media viewer, transfer state mapping and action bindings; cryptography and disk integrity stay with owners. [FlashConversationScreen.kt](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt), [FlashTransfersScreen.kt](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/transfers/FlashTransfersScreen.kt), [FlashCreateGroupSheet.kt](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashCreateGroupSheet.kt), [FlashMediaViewer.kt](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMediaViewer.kt)

**Existing test reports.** jvmTest 802, testAndroidHostTest 777. Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | PASS | Missing, empty and damaged startup route arguments have explicit treatment in the route decision. [FlashHubRouteLifecycleTest.kt:23](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubRouteLifecycleTest.kt#L23) |
| Q02 | PASS | Missing, empty and damaged startup route arguments have explicit treatment in the route decision. [FlashHubRouteLifecycleTest.kt:23](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubRouteLifecycleTest.kt#L23) |
| Q03 | PASS | Missing, empty and damaged startup route arguments have explicit treatment in the route decision. [FlashHubRouteLifecycleTest.kt:23](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubRouteLifecycleTest.kt#L23) |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | FAIL | Hub create stays actionable while the host call is in flight. Attachment staging has separate URI deduplication coverage. See IF-06 below. [FlashCreateGroupSheet.kt:342](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashCreateGroupSheet.kt#L342) |
| Q07 | PARTIAL | Hub workspace actions refuse repeated clicks and release on exception. Group creation does not use that latch (IF-06). [FlashHubSingleFlightTest.kt:10](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSingleFlightTest.kt#L10) |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | UNVERIFIED | No scenario-specific evidence reviewed for peer disconnect at this module boundary. |
| Q15 | UNVERIFIED | No scenario-specific evidence reviewed for peer reconnect at this module boundary. |
| Q16 | PASS | Hub action modeling obeys trust and capability gates and does not offer unsupported actions. [FlashHubSituationsTest.kt:35](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSituationsTest.kt#L35) |
| Q17 | PASS | Hub action modeling obeys trust and capability gates and does not offer unsupported actions. [FlashHubSituationsTest.kt:35](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSituationsTest.kt#L35) |
| Q18 | UNVERIFIED | No scenario-specific evidence reviewed for credential revocation at this module boundary. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | UNVERIFIED | No scenario-specific evidence reviewed for process kill at this module boundary. |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe path at this module boundary. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | PASS | Composer staging has a cap tested with a list longer than the limit. [FlashStagingMathTest.kt:63](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashStagingMathTest.kt#L63) |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | PARTIAL | Existing narrow, large-font and platform captures are documented. Native screen readers and full RTL states remain open in CHATUI and UXPOL. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q35 | PARTIAL | Common labels have friendly variants but long translated names and labels still need rendered coverage. [FlashDetailPanesLogicTest.kt:45](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/adaptive/FlashDetailPanesLogicTest.kt#L45) |
| Q36 | PARTIAL | Existing narrow, large-font and platform captures are documented. Native screen readers and full RTL states remain open in CHATUI and UXPOL. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q37 | PARTIAL | Existing narrow, large-font and platform captures are documented. Native screen readers and full RTL states remain open in CHATUI and UXPOL. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q38 | PARTIAL | Existing narrow, large-font and platform captures are documented. Native screen readers and full RTL states remain open in CHATUI and UXPOL. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q39 | PARTIAL | Existing narrow, large-font and platform captures are documented. Native screen readers and full RTL states remain open in CHATUI and UXPOL. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q40 | PASS | Shared sizing logic covers phone breakpoints and pane transitions. [FlashAdaptiveLogicTest.kt:17](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt#L17) |
| Q41 | PASS | Shared sizing logic covers phone breakpoints and pane transitions. [FlashAdaptiveLogicTest.kt:17](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/adaptive/FlashAdaptiveLogicTest.kt#L17) |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | PARTIAL | Hub workspace actions refuse repeated clicks and release on exception. Group creation does not use that latch (IF-06). [FlashHubSingleFlightTest.kt:10](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSingleFlightTest.kt#L10) |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | PARTIAL | Hub workspace actions refuse repeated clicks and release on exception. Group creation does not use that latch (IF-06). [FlashHubSingleFlightTest.kt:10](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSingleFlightTest.kt#L10) |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | PASS | Presentation groups active, failed and history rows and clamps zero-total progress. [FlashTransfersLogicTest.kt:50](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/transfers/FlashTransfersLogicTest.kt#L50) |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | UNVERIFIED | No scenario-specific evidence reviewed for pause race at this module boundary. |
| Q60 | PASS | Forward seek clamps without auto-play, and replay resets position and playback state. [FlashVideoPlayerMathTest.kt:11](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashVideoPlayerMathTest.kt#L11) |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | PASS | Forward seek clamps without auto-play, and replay resets position and playback state. [FlashVideoPlayerMathTest.kt:11](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashVideoPlayerMathTest.kt#L11) |
| Q64 | PASS | Presentation groups active, failed and history rows and clamps zero-total progress. [FlashTransfersLogicTest.kt:50](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/transfers/FlashTransfersLogicTest.kt#L50) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | UNVERIFIED | No scenario-specific evidence reviewed for stale cache at this module boundary. |
| Q89 | UNVERIFIED | No scenario-specific evidence reviewed for invalidated authorization at this module boundary. |
| Q90 | PASS | Hub action modeling obeys trust and capability gates and does not offer unsupported actions. [FlashHubSituationsTest.kt:35](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSituationsTest.kt#L35) |
| Q91 | PARTIAL | Hosts surface request failure, but the partial-member case looks like success and can briefly display a wrong roster (IF-05). [FlashCreateGroupSheet.kt:342](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashCreateGroupSheet.kt#L342) |
| Q92 | PASS | Fast loads suppress the spinner and loading skeleton count stays bounded in the presentation tests. [FlashStatesLogicTest.kt:18](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/chat/FlashStatesLogicTest.kt#L18) |
| Q93 | PASS | Presentation groups active, failed and history rows and clamps zero-total progress. [FlashTransfersLogicTest.kt:50](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/transfers/FlashTransfersLogicTest.kt#L50) |
| Q94 | PARTIAL | State math distinguishes online, offline and permitted LAN actions. Deployed directory and routing state need integration evidence. [FlashHubSituationsTest.kt:46](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSituationsTest.kt#L46) |
| Q95 | PARTIAL | State math distinguishes online, offline and permitted LAN actions. Deployed directory and routing state need integration evidence. [FlashHubSituationsTest.kt:46](../../ui/chat/src/commonTest/kotlin/com/transfer/flash/ui/hub/FlashHubSituationsTest.kt#L46) |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:ui:callui`

**Audit scope.** Call and PTT presentation, control semantics and video sink lifecycle, with protocol, storage and trust owned elsewhere. [FlashCallControlDock.kt](../../ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallControlDock.kt), [FlashGroupVideoGrid.kt](../../ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGrid.kt), [DesktopVideoSinkBindingTest.kt](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt)

**Existing test reports.** jvmTest 113, testAndroidHostTest 90, testDebugUnitTest 5. Newest file timestamp 2026-11-17.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | PASS | Unbound sink refuses arrivals and unbinding preserves the surface in JVM fixture. [DesktopVideoSinkBindingTest.kt:134](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt#L134) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | UNVERIFIED | No scenario-specific evidence reviewed for peer disconnect at this module boundary. |
| Q15 | UNVERIFIED | No scenario-specific evidence reviewed for peer reconnect at this module boundary. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | N/A | Outside this module’s responsibility as described above. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | PASS | No-camera and device errors map to specific copy plus appropriate recovery actions. [FlashCallMediaTextTest.kt:15](../../ui/callui/src/commonTest/kotlin/com/transfer/flash/ui/calling/FlashCallMediaTextTest.kt#L15) |
| Q35 | PARTIAL | JVM render covers compact controls and large font and semantics labels exist. RTL, screen readers and native resizing need host checks. [FlashCallControlDockRenderTest.kt:143](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/FlashCallControlDockRenderTest.kt#L143) |
| Q36 | PARTIAL | JVM render covers compact controls and large font and semantics labels exist. RTL, screen readers and native resizing need host checks. [FlashCallControlDockRenderTest.kt:143](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/FlashCallControlDockRenderTest.kt#L143) |
| Q37 | PARTIAL | JVM render covers compact controls and large font and semantics labels exist. RTL, screen readers and native resizing need host checks. [FlashCallControlDockRenderTest.kt:143](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/FlashCallControlDockRenderTest.kt#L143) |
| Q38 | PARTIAL | JVM render covers compact controls and large font and semantics labels exist. RTL, screen readers and native resizing need host checks. [FlashCallControlDockRenderTest.kt:143](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/FlashCallControlDockRenderTest.kt#L143) |
| Q39 | PASS | Constrained render tiers settle without the HIGH animation across mute interaction. [FlashCallControlDockRenderTest.kt:81](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/FlashCallControlDockRenderTest.kt#L81) |
| Q40 | PASS | Grid math fits tall/wide views and keeps tiles inside the box without overlap. [FlashGroupVideoGridTest.kt:19](../../ui/callui/src/commonTest/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGridTest.kt#L19) |
| Q41 | PASS | Grid math fits tall/wide views and keeps tiles inside the box without overlap. [FlashGroupVideoGridTest.kt:19](../../ui/callui/src/commonTest/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGridTest.kt#L19) |
| Q42 | PARTIAL | Covered tile can detach its track. Real minimize, Android media release, and platform divergence need host scenarios. [DesktopVideoSinkBindingTest.kt:109](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt#L109) |
| Q43 | PARTIAL | Covered tile can detach its track. Real minimize, Android media release, and platform divergence need host scenarios. [DesktopVideoSinkBindingTest.kt:109](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt#L109) |
| Q44 | PASS | Unbound sink refuses arrivals and unbinding preserves the surface in JVM fixture. [DesktopVideoSinkBindingTest.kt:134](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt#L134) |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | PASS | A skipped or failed conversion releases the video frame. [DesktopVideoSinkReleaseTest.kt:29](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkReleaseTest.kt#L29) |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | PASS | No-camera and device errors map to specific copy plus appropriate recovery actions. [FlashCallMediaTextTest.kt:15](../../ui/callui/src/commonTest/kotlin/com/transfer/flash/ui/calling/FlashCallMediaTextTest.kt#L15) |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | PASS | No-camera and device errors map to specific copy plus appropriate recovery actions. [FlashCallMediaTextTest.kt:15](../../ui/callui/src/commonTest/kotlin/com/transfer/flash/ui/calling/FlashCallMediaTextTest.kt#L15) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PARTIAL | Covered tile can detach its track. Real minimize, Android media release, and platform divergence need host scenarios. [DesktopVideoSinkBindingTest.kt:109](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt#L109) |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | UNVERIFIED | No scenario-specific evidence reviewed for partial state at this module boundary. |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | Covered tile can detach its track. Real minimize, Android media release, and platform divergence need host scenarios. [DesktopVideoSinkBindingTest.kt:109](../../ui/callui/src/jvmTest/kotlin/com/transfer/flash/ui/calling/DesktopVideoSinkBindingTest.kt#L109) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:ui:hub-competition-claude`

**Audit scope.** Development-only desktop launcher for simulated Hub screens; product data and policy are absent. [ShowcaseLauncherApp.kt](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt), [settings.gradle.kts](../../settings.gradle.kts)

**Existing test reports.** No saved module test XML found.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | PASS | Absent or unrecognized showcase route opens the entry picker, and Escape returns to the picker. [ShowcaseLauncherApp.kt:92](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L92) |
| Q02 | PASS | Absent or unrecognized showcase route opens the entry picker, and Escape returns to the picker. [ShowcaseLauncherApp.kt:92](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L92) |
| Q03 | PASS | Absent or unrecognized showcase route opens the entry picker, and Escape returns to the picker. [ShowcaseLauncherApp.kt:92](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L92) |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | N/A | Outside this module’s responsibility as described above. |
| Q09 | N/A | Outside this module’s responsibility as described above. |
| Q10 | N/A | Outside this module’s responsibility as described above. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | PARTIAL | Desktop chooser and resizable window are wired. No launcher-specific keyboard/screen-reader and scaling results saved. [ShowcaseLauncherApp.kt:103](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L103) |
| Q36 | PARTIAL | Desktop chooser and resizable window are wired. No launcher-specific keyboard/screen-reader and scaling results saved. [ShowcaseLauncherApp.kt:103](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L103) |
| Q37 | PARTIAL | Desktop chooser and resizable window are wired. No launcher-specific keyboard/screen-reader and scaling results saved. [ShowcaseLauncherApp.kt:103](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L103) |
| Q38 | PARTIAL | Desktop chooser and resizable window are wired. No launcher-specific keyboard/screen-reader and scaling results saved. [ShowcaseLauncherApp.kt:103](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L103) |
| Q39 | UNVERIFIED | No scenario-specific evidence reviewed for reduced motion at this module boundary. |
| Q40 | PARTIAL | Desktop chooser and resizable window are wired. No launcher-specific keyboard/screen-reader and scaling results saved. [ShowcaseLauncherApp.kt:103](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L103) |
| Q41 | PARTIAL | Desktop chooser and resizable window are wired. No launcher-specific keyboard/screen-reader and scaling results saved. [ShowcaseLauncherApp.kt:103](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L103) |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | N/A | Outside this module’s responsibility as described above. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | N/A | Outside this module’s responsibility as described above. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | N/A | Outside this module’s responsibility as described above. |
| Q66 | N/A | Outside this module’s responsibility as described above. |
| Q67 | N/A | Outside this module’s responsibility as described above. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | PASS | Absent or unrecognized showcase route opens the entry picker, and Escape returns to the picker. [ShowcaseLauncherApp.kt:92](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L92) |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | PASS | Absent or unrecognized showcase route opens the entry picker, and Escape returns to the picker. [ShowcaseLauncherApp.kt:92](../../ui/hub-competition-claude/src/jvmMain/kotlin/com/transfer/flash/ui/hub/competition/claude/ShowcaseLauncherApp.kt#L92) |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | N/A | Launcher is development-only, never published and absent from app product dependencies. [settings.gradle.kts](../../settings.gradle.kts) |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | N/A | Outside this module’s responsibility as described above. |
| Q100 | N/A | Outside this module’s responsibility as described above. |

### `:desktop`

**Audit scope.** Installed desktop host, native capabilities, window/notification state, secure storage and activation wiring. [DesktopEngine.kt](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt), [DesktopShell.kt](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt), [SingleInstanceController.kt](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/SingleInstanceController.kt), [DesktopMain.kt](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt)

**Existing test reports.** jvmTest 314 (0 failed/errors, 2 skipped). Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | PASS | Second activation is forwarded to the running instance without constructing another shell in the fixture. [SingleInstanceControllerTest.kt:29](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/SingleInstanceControllerTest.kt#L29) |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | PASS | Drop marks only that peer's inbound transfers failed and preserves progress. [DesktopInboundRecoveryTest.kt:26](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopInboundRecoveryTest.kt#L26) |
| Q15 | UNVERIFIED | No scenario-specific evidence reviewed for peer reconnect at this module boundary. |
| Q16 | PASS | Local activation requires the existing instance token, including exact matching. [SingleInstanceControllerTest.kt:61](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/SingleInstanceControllerTest.kt#L61) |
| Q17 | FAIL | Group policy can lag after setting changes and the cached response is not bound to the current enrollment at assignment. See IF-02 below. [DesktopEngine.kt:1675](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L1675) |
| Q18 | PASS | Revocation, re-pin prevention and sealed session keys persist across vault reopen. [DesktopTrustStoreTest.kt:64](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopTrustStoreTest.kt#L64) |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PARTIAL | Shared continuity restart fixture and desktop recovery source exist. Installed desktop sender/receiver recovery is DESK-RESUME-01. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q22 | PARTIAL | Shared continuity restart fixture and desktop recovery source exist. Installed desktop sender/receiver recovery is DESK-RESUME-01. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q23 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted transaction at this module boundary. |
| Q24 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted migration at this module boundary. |
| Q25 | UNVERIFIED | No scenario-specific evidence reviewed for corrupt persistence at this module boundary. |
| Q26 | UNVERIFIED | No scenario-specific evidence reviewed for full storage at this module boundary. |
| Q27 | PASS | Clearing received content leaves unrelated files in the same save directory intact. ReceivedPathContainmentTest additionally checks roots. [DesktopReceivedStorageTest.kt:85](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopReceivedStorageTest.kt#L85) |
| Q28 | UNVERIFIED | No scenario-specific evidence reviewed for changing file at this module boundary. |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | UNVERIFIED | No scenario-specific evidence reviewed for huge collections at this module boundary. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | UNVERIFIED | No scenario-specific evidence reviewed for long labels at this module boundary. |
| Q36 | PARTIAL | Existing host renders cover selected cases. Installed keyboard, assistive tech and resize checks remain UXPOL-16/19 and TABREF. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q37 | PARTIAL | Existing host renders cover selected cases. Installed keyboard, assistive tech and resize checks remain UXPOL-16/19 and TABREF. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q38 | PARTIAL | Existing host renders cover selected cases. Installed keyboard, assistive tech and resize checks remain UXPOL-16/19 and TABREF. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q39 | PARTIAL | Existing host renders cover selected cases. Installed keyboard, assistive tech and resize checks remain UXPOL-16/19 and TABREF. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q40 | PARTIAL | Existing host renders cover selected cases. Installed keyboard, assistive tech and resize checks remain UXPOL-16/19 and TABREF. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q41 | PARTIAL | Existing host renders cover selected cases. Installed keyboard, assistive tech and resize checks remain UXPOL-16/19 and TABREF. [FlashWorkspaceTabsRenderTest.kt](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/FlashWorkspaceTabsRenderTest.kt) |
| Q42 | PASS | Notification policy distinguishes visible current chat from hidden or other chat under host fixtures. [DesktopNotificationManagerTest.kt:37](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopNotificationManagerTest.kt#L37) |
| Q43 | PASS | Notification policy distinguishes visible current chat from hidden or other chat under host fixtures. [DesktopNotificationManagerTest.kt:37](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopNotificationManagerTest.kt#L37) |
| Q44 | PARTIAL | Idle-ECO lifecycle test had a documented timeout in prior ERROR-1026. Saved results are historical. Controlled discovery retest needed. [DesktopDiscoveryDownscaleTest.kt:64](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopDiscoveryDownscaleTest.kt#L64) |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | UNVERIFIED | No scenario-specific evidence reviewed for metrics emission at this module boundary. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | PARTIAL | Shared continuity restart fixture and desktop recovery source exist. Installed desktop sender/receiver recovery is DESK-RESUME-01. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | UNVERIFIED | No scenario-specific evidence reviewed for pause race at this module boundary. |
| Q60 | PARTIAL | Shared continuity restart fixture and desktop recovery source exist. Installed desktop sender/receiver recovery is DESK-RESUME-01. [CrossRouteWiringIntegrationTest.kt:718](../../core/engine/src/jvmTest/kotlin/com/transfer/flash/core/engine/hubfiles/CrossRouteWiringIntegrationTest.kt#L718) |
| Q61 | UNVERIFIED | No scenario-specific evidence reviewed for late cancel events at this module boundary. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | UNVERIFIED | No scenario-specific evidence reviewed for failed completion at this module boundary. |
| Q65 | PASS | Second activation is forwarded to the running instance without constructing another shell in the fixture. [SingleInstanceControllerTest.kt:29](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/SingleInstanceControllerTest.kt#L29) |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PARTIAL | Host native video evidence exists. Installed x64 package and missing-media fallback remain MEDIA-PLAY-03; Linux backend differs. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q68 | UNVERIFIED | No scenario-specific evidence reviewed for API version changes at this module boundary. |
| Q69 | UNVERIFIED | No scenario-specific evidence reviewed for denied permission at this module boundary. |
| Q70 | UNVERIFIED | No scenario-specific evidence reviewed for revoked permission at this module boundary. |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | PARTIAL | Idle-ECO lifecycle test had a documented timeout in prior ERROR-1026. Saved results are historical. Controlled discovery retest needed. [DesktopDiscoveryDownscaleTest.kt:64](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopDiscoveryDownscaleTest.kt#L64) |
| Q73 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate discovery at this module boundary. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | PASS | Local activation requires the existing instance token, including exact matching. [SingleInstanceControllerTest.kt:61](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/SingleInstanceControllerTest.kt#L61) |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | UNVERIFIED | No scenario-specific evidence reviewed for encryption failure at this module boundary. |
| Q82 | PASS | Revocation, re-pin prevention and sealed session keys persist across vault reopen. [DesktopTrustStoreTest.kt:64](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopTrustStoreTest.kt#L64) |
| Q83 | PASS | Revocation, re-pin prevention and sealed session keys persist across vault reopen. [DesktopTrustStoreTest.kt:64](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopTrustStoreTest.kt#L64) |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | UNVERIFIED | No scenario-specific evidence reviewed for conflicting rows at this module boundary. |
| Q86 | UNVERIFIED | No scenario-specific evidence reviewed for newer schema at this module boundary. |
| Q87 | UNVERIFIED | No scenario-specific evidence reviewed for two database owners at this module boundary. |
| Q88 | FAIL | Group policy can lag after setting changes and the cached response is not bound to the current enrollment at assignment. See IF-02 below. [DesktopEngine.kt:1675](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L1675) |
| Q89 | FAIL | Group policy can lag after setting changes and the cached response is not bound to the current enrollment at assignment. See IF-02 below. [DesktopEngine.kt:1675](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L1675) |
| Q90 | PASS | Drop requires a visible chat and direct share requires a trusted peer or group in host logic. [DesktopShellBehaviorTest.kt:43](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopShellBehaviorTest.kt#L43) |
| Q91 | PARTIAL | Desktop handles a total creation failure, while a partly enrolled roster still looks successful (IF-05). [DesktopShell.kt:2555](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L2555) |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | PARTIAL | Desktop handles a total creation failure, while a partly enrolled roster still looks successful (IF-05). [DesktopShell.kt:2555](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L2555) |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | PARTIAL | Host native video evidence exists. Installed x64 package and missing-media fallback remain MEDIA-PLAY-03; Linux backend differs. [WindowsVideoPlayerTest.kt:13](../../ui/platform-shims/src/jvmTest/kotlin/com/transfer/flash/ui/shims/WindowsVideoPlayerTest.kt#L13) |
| Q98 | PARTIAL | Idle-ECO lifecycle test had a documented timeout in prior ERROR-1026. Saved results are historical. Controlled discovery retest needed. [DesktopDiscoveryDownscaleTest.kt:64](../../desktop/src/jvmTest/kotlin/com/transfer/flash/desktop/DesktopDiscoveryDownscaleTest.kt#L64) |
| Q99 | UNVERIFIED | No scenario-specific evidence reviewed for unimplemented feature at this module boundary. |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:server`

**Audit scope.** Hub HTTP and WebSocket, identity, directory, mailbox and file storage plus browser console; no native app lifecycle policy. [FlashServerEngine.kt](../../server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt), [RelayFrameRouter.kt](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt), [OrganizationDirectory.kt](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt), [AdminApi.kt](../../server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt)

**Existing test reports.** test 710. Newest file timestamp 2026-11-16.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | PASS | Shared wire guards are used at the server boundary. RelayHandshakeContractTest covers server handshake refusal. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q04 | PASS | Shared wire guards are used at the server boundary. RelayHandshakeContractTest covers server handshake refusal. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q05 | PASS | Shared wire guards are used at the server boundary. RelayHandshakeContractTest covers server handshake refusal. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q06 | PASS | Replacement proof, duplicate pending grant, invalidated old credentials and recovery have server tests. [DeviceReplacementTest.kt:210](../../server/src/test/kotlin/com/transfer/flash/server/admin/DeviceReplacementTest.kt#L210) |
| Q07 | PASS | Announcement-store concurrency regression exercises racing mutations. Evidence is scoped to this store. [AnnouncementStoreConcurrencyTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/announcements/AnnouncementStoreConcurrencyTest.kt) |
| Q08 | UNVERIFIED | No scenario-specific evidence reviewed for post-shutdown requests at this module boundary. |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | UNVERIFIED | No scenario-specific evidence reviewed for network outage at this module boundary. |
| Q13 | UNVERIFIED | No scenario-specific evidence reviewed for network change at this module boundary. |
| Q14 | UNVERIFIED | No scenario-specific evidence reviewed for peer disconnect at this module boundary. |
| Q15 | UNVERIFIED | No scenario-specific evidence reviewed for peer reconnect at this module boundary. |
| Q16 | PASS | Relay stamps authenticated sender and refuses client-supplied server frame types. [RelayFrameRouterTest.kt:104](../../server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt#L104) |
| Q17 | PASS | Signed roster versions grow and remove the departed member. Directory mutations also validate admin IDs and clear preauthorization. [GroupRosterSyncTest.kt:61](../../server/src/test/kotlin/com/transfer/flash/server/directory/GroupRosterSyncTest.kt#L61) |
| Q18 | PASS | Replacement proof, duplicate pending grant, invalidated old credentials and recovery have server tests. [DeviceReplacementTest.kt:210](../../server/src/test/kotlin/com/transfer/flash/server/admin/DeviceReplacementTest.kt#L210) |
| Q19 | PASS | An expired certificate is refused during real server handshake fixture. [RelayHandshakeContractTest.kt:209](../../server/src/test/kotlin/com/transfer/flash/server/relay/RelayHandshakeContractTest.kt#L209) |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PASS | Fixture restart retains enrollment, roster, revocations and mailbox. [E2eRestartContractTest.kt:37](../../server/src/test/kotlin/com/transfer/flash/server/e2e/E2eRestartContractTest.kt#L37) |
| Q22 | PASS | Fixture restart retains enrollment, roster, revocations and mailbox. [E2eRestartContractTest.kt:37](../../server/src/test/kotlin/com/transfer/flash/server/e2e/E2eRestartContractTest.kt#L37) |
| Q23 | PASS | Replacement proof, duplicate pending grant, invalidated old credentials and recovery have server tests. [DeviceReplacementTest.kt:210](../../server/src/test/kotlin/com/transfer/flash/server/admin/DeviceReplacementTest.kt#L210) |
| Q24 | UNVERIFIED | No scenario-specific evidence reviewed for interrupted migration at this module boundary. |
| Q25 | PARTIAL | Unreadable mailbox rows are quarantined. Whole database corruption and operator recovery are separate cases. [MailboxQuarantineTest.kt:62](../../server/src/test/kotlin/com/transfer/flash/server/mailbox/MailboxQuarantineTest.kt#L62) |
| Q26 | PASS | Simulates a failed SQLite write and preserves operator-visible behavior at the mailbox quarantine seam. [MailboxQuarantineTest.kt:128](../../server/src/test/kotlin/com/transfer/flash/server/mailbox/MailboxQuarantineTest.kt#L128) |
| Q27 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe path at this module boundary. |
| Q28 | UNVERIFIED | No scenario-specific evidence reviewed for changing file at this module boundary. |
| Q29 | UNVERIFIED | No scenario-specific evidence reviewed for huge file at this module boundary. |
| Q30 | UNVERIFIED | No scenario-specific evidence reviewed for zero-byte file at this module boundary. |
| Q31 | PASS | Page byte caps hold at the maximum requested rows. Explicit roster truncation prevents treating incomplete lists as complete. [DirectorySyncTransportTest.kt:76](../../server/src/test/kotlin/com/transfer/flash/server/directory/DirectorySyncTransportTest.kt#L76) |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | UNVERIFIED | No scenario-specific evidence reviewed for bidi/control text at this module boundary. |
| Q34 | UNVERIFIED | No scenario-specific evidence reviewed for missing text at this module boundary. |
| Q35 | UNVERIFIED | No scenario-specific evidence reviewed for long labels at this module boundary. |
| Q36 | PARTIAL | Existing tests cover assets and browser surface, and node DOM tests exist. Live keyboard, RTL and small-screen interaction need CONSOLE-D02..07. [AdminConsoleWebTest.kt:121](../../server/src/test/kotlin/com/transfer/flash/server/admin/AdminConsoleWebTest.kt#L121) |
| Q37 | PARTIAL | Existing tests cover assets and browser surface, and node DOM tests exist. Live keyboard, RTL and small-screen interaction need CONSOLE-D02..07. [AdminConsoleWebTest.kt:121](../../server/src/test/kotlin/com/transfer/flash/server/admin/AdminConsoleWebTest.kt#L121) |
| Q38 | PARTIAL | Existing tests cover assets and browser surface, and node DOM tests exist. Live keyboard, RTL and small-screen interaction need CONSOLE-D02..07. [AdminConsoleWebTest.kt:121](../../server/src/test/kotlin/com/transfer/flash/server/admin/AdminConsoleWebTest.kt#L121) |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | PARTIAL | Existing tests cover assets and browser surface, and node DOM tests exist. Live keyboard, RTL and small-screen interaction need CONSOLE-D02..07. [AdminConsoleWebTest.kt:121](../../server/src/test/kotlin/com/transfer/flash/server/admin/AdminConsoleWebTest.kt#L121) |
| Q41 | PARTIAL | Existing tests cover assets and browser surface, and node DOM tests exist. Live keyboard, RTL and small-screen interaction need CONSOLE-D02..07. [AdminConsoleWebTest.kt:121](../../server/src/test/kotlin/com/transfer/flash/server/admin/AdminConsoleWebTest.kt#L121) |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | UNVERIFIED | No scenario-specific evidence reviewed for repeated lifecycle at this module boundary. |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | PASS | Announcement-store concurrency regression exercises racing mutations. Evidence is scoped to this store. [AnnouncementStoreConcurrencyTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/announcements/AnnouncementStoreConcurrencyTest.kt) |
| Q48 | PASS | Mailbox enforces queue quotas. Separate quarantine fixture proves unreadable quarantined rows do not consume those quotas. [MailboxQueueTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/mailbox/MailboxQueueTest.kt) |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | UNVERIFIED | No scenario-specific evidence reviewed for logging failure at this module boundary. |
| Q52 | UNVERIFIED | No scenario-specific evidence reviewed for debug logging at this module boundary. |
| Q53 | PARTIAL | Host tests expose quarantine and rollout signals. Production condition remains HR11-ENV-01/02. [OperationsAndRolloutDrillsTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/ops/OperationsAndRolloutDrillsTest.kt) |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | UNVERIFIED | No scenario-specific evidence reviewed for partial retry at this module boundary. |
| Q57 | UNVERIFIED | No scenario-specific evidence reviewed for unsafe retry at this module boundary. |
| Q58 | UNVERIFIED | No scenario-specific evidence reviewed for terminal retry at this module boundary. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | UNVERIFIED | No scenario-specific evidence reviewed for remote cancel at this module boundary. |
| Q63 | UNVERIFIED | No scenario-specific evidence reviewed for successful completion at this module boundary. |
| Q64 | PASS | Simulates a failed SQLite write and preserves operator-visible behavior at the mailbox quarantine seam. [MailboxQuarantineTest.kt:128](../../server/src/test/kotlin/com/transfer/flash/server/mailbox/MailboxQuarantineTest.kt#L128) |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | UNVERIFIED | No scenario-specific evidence reviewed for missing platform capability at this module boundary. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | UNVERIFIED | Config tests exist. Runtime denial or revocation of data-directory access remains uncovered. [ServerConfigTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/config/ServerConfigTest.kt) |
| Q70 | UNVERIFIED | Config tests exist. Runtime denial or revocation of data-directory access remains uncovered. [ServerConfigTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/config/ServerConfigTest.kt) |
| Q71 | UNVERIFIED | No scenario-specific evidence reviewed for lost connectivity at this module boundary. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | UNVERIFIED | No scenario-specific evidence reviewed for identity collision at this module boundary. |
| Q75 | PASS | Relay stamps authenticated sender and refuses client-supplied server frame types. [RelayFrameRouterTest.kt:104](../../server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt#L104) |
| Q76 | PASS | Shared wire guards are used at the server boundary. RelayHandshakeContractTest covers server handshake refusal. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q77 | PASS | Shared wire guards are used at the server boundary. RelayHandshakeContractTest covers server handshake refusal. [RelayFrameCodecTest.kt:113](../../core/hub-protocol/src/commonTest/kotlin/com/transfer/flash/core/hubprotocol/RelayFrameCodecTest.kt#L113) |
| Q78 | UNVERIFIED | No scenario-specific evidence reviewed for reordered frame at this module boundary. |
| Q79 | UNVERIFIED | No scenario-specific evidence reviewed for replayed frame at this module boundary. |
| Q80 | UNVERIFIED | No scenario-specific evidence reviewed for bad checksum/signature at this module boundary. |
| Q81 | FAIL | Readable group-frame routing has no server refusal in required mode. See IF-01 below. [RelayFrameRouter.kt:230](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt#L230) |
| Q82 | UNVERIFIED | No scenario-specific evidence reviewed for persisted keys at this module boundary. |
| Q83 | UNVERIFIED | No scenario-specific evidence reviewed for trust change at this module boundary. |
| Q84 | UNVERIFIED | No scenario-specific evidence reviewed for missing database row at this module boundary. |
| Q85 | PASS | Signed roster versions grow and remove the departed member. Directory mutations also validate admin IDs and clear preauthorization. [GroupRosterSyncTest.kt:61](../../server/src/test/kotlin/com/transfer/flash/server/directory/GroupRosterSyncTest.kt#L61) |
| Q86 | UNVERIFIED | No scenario-specific evidence reviewed for newer schema at this module boundary. |
| Q87 | UNVERIFIED | No scenario-specific evidence reviewed for two database owners at this module boundary. |
| Q88 | PARTIAL | Version changes push a directory hint. This event does not cover the group policy write in AdminApi (IF-02). [RelayFrameRouterTest.kt:814](../../server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt#L814) |
| Q89 | FAIL | Readable group-frame routing has no server refusal in required mode. See IF-01 below. [RelayFrameRouter.kt:230](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt#L230) |
| Q90 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported UI action at this module boundary. |
| Q91 | UNVERIFIED | No scenario-specific evidence reviewed for backend failure at this module boundary. |
| Q92 | UNVERIFIED | No scenario-specific evidence reviewed for loading state at this module boundary. |
| Q93 | UNVERIFIED | No scenario-specific evidence reviewed for empty state at this module boundary. |
| Q94 | UNVERIFIED | No scenario-specific evidence reviewed for offline state at this module boundary. |
| Q95 | PARTIAL | Host tests expose quarantine and rollout signals. Production condition remains HR11-ENV-01/02. [OperationsAndRolloutDrillsTest.kt](../../server/src/test/kotlin/com/transfer/flash/server/ops/OperationsAndRolloutDrillsTest.kt) |
| Q96 | UNVERIFIED | No scenario-specific evidence reviewed for public API change at this module boundary. |
| Q97 | UNVERIFIED | No scenario-specific evidence reviewed for platform divergence at this module boundary. |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | FAIL | Static fallback exists, but the current Hub does not issue a membership invite for a Hub directory group. See IF-03 below. [InviteWebRoutesTest.kt:18](../../server/src/test/kotlin/com/transfer/flash/server/invites/InviteWebRoutesTest.kt#L18) |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

### `:sample:consumer`

**Audit scope.** Android umbrella API type and classpath probe with a compile-only lifecycle example. [QuickStart.kt](../../sample/consumer/src/main/java/com/transfer/flash/sample/consumer/QuickStart.kt), [UmbrellaFacadeContractTest.kt](../../sample/consumer/src/test/java/com/transfer/flash/sample/consumer/UmbrellaFacadeContractTest.kt)

**Existing test reports.** testDebugUnitTest 10. Newest file timestamp 2026-11-07.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | N/A | Outside this module’s responsibility as described above. |
| Q02 | N/A | Outside this module’s responsibility as described above. |
| Q03 | N/A | Outside this module’s responsibility as described above. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | PASS | Excluding optional calling is deliberate and tested. Unattached bridge resolves without that dependency. [UmbrellaFacadeContractTest.kt:137](../../sample/consumer/src/test/java/com/transfer/flash/sample/consumer/UmbrellaFacadeContractTest.kt#L137) |
| Q09 | PARTIAL | Example closes Engine in finally on return/cancellation. It is compile-only and no live transfer lifecycle is exercised. [QuickStart.kt:32](../../sample/consumer/src/main/java/com/transfer/flash/sample/consumer/QuickStart.kt#L32) |
| Q10 | PARTIAL | Compile-only sample waits for any nonempty peer list indefinitely unless caller cancels. No timeout or empty-state example. [QuickStart.kt:37](../../sample/consumer/src/main/java/com/transfer/flash/sample/consumer/QuickStart.kt#L37) |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | N/A | Outside this module’s responsibility as described above. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | N/A | Outside this module’s responsibility as described above. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | N/A | Outside this module’s responsibility as described above. |
| Q66 | PASS | Excluding optional calling is deliberate and tested. Unattached bridge resolves without that dependency. [UmbrellaFacadeContractTest.kt:137](../../sample/consumer/src/test/java/com/transfer/flash/sample/consumer/UmbrellaFacadeContractTest.kt#L137) |
| Q67 | N/A | Outside this module’s responsibility as described above. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | PARTIAL | Compile-only sample waits for any nonempty peer list indefinitely unless caller cancels. No timeout or empty-state example. [QuickStart.kt:37](../../sample/consumer/src/main/java/com/transfer/flash/sample/consumer/QuickStart.kt#L37) |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | PASS | Runtime/reflection tests verify that facade classes can be resolved by the umbrella consumer without hidden calling types. [UmbrellaFacadeContractTest.kt:170](../../sample/consumer/src/test/java/com/transfer/flash/sample/consumer/UmbrellaFacadeContractTest.kt#L170) |
| Q97 | N/A | Outside this module’s responsibility as described above. |
| Q98 | N/A | Outside this module’s responsibility as described above. |
| Q99 | N/A | Outside this module’s responsibility as described above. |
| Q100 | N/A | Outside this module’s responsibility as described above. |

### `:sample:consumer-granular`

**Audit scope.** Compile-only downstream network/common API exports with no application execution. [GranularConsumer.kt](../../sample/consumer-granular/src/main/java/com/transfer/flash/sample/consumergranular/GranularConsumer.kt), [build.gradle.kts](../../sample/consumer-granular/build.gradle.kts)

**Existing test reports.** No saved module test XML found.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | N/A | Outside this module’s responsibility as described above. |
| Q02 | N/A | Outside this module’s responsibility as described above. |
| Q03 | N/A | Outside this module’s responsibility as described above. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | N/A | Outside this module’s responsibility as described above. |
| Q09 | N/A | Outside this module’s responsibility as described above. |
| Q10 | N/A | Outside this module’s responsibility as described above. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | N/A | Outside this module’s responsibility as described above. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | N/A | Outside this module’s responsibility as described above. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | N/A | Outside this module’s responsibility as described above. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | N/A | Outside this module’s responsibility as described above. |
| Q66 | PARTIAL | Small direct-dependency compile probe checks exported network/common types. No generated results were present for this module. [GranularConsumer.kt:18](../../sample/consumer-granular/src/main/java/com/transfer/flash/sample/consumergranular/GranularConsumer.kt#L18) |
| Q67 | N/A | Outside this module’s responsibility as described above. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | PARTIAL | Small direct-dependency compile probe checks exported network/common types. No generated results were present for this module. [GranularConsumer.kt:18](../../sample/consumer-granular/src/main/java/com/transfer/flash/sample/consumergranular/GranularConsumer.kt#L18) |
| Q97 | N/A | Outside this module’s responsibility as described above. |
| Q98 | N/A | Outside this module’s responsibility as described above. |
| Q99 | N/A | Outside this module’s responsibility as described above. |
| Q100 | N/A | Outside this module’s responsibility as described above. |

### `:sample:consumer-desktop`

**Audit scope.** Compile-only JVM published dependency/variant exercise without product networking or persistence. [DesktopConsumer.kt](../../sample/consumer-desktop/src/main/kotlin/com/transfer/flash/sample/consumerdesktop/DesktopConsumer.kt), [build.gradle.kts](../../sample/consumer-desktop/build.gradle.kts)

**Existing test reports.** No saved module test XML found.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | N/A | Outside this module’s responsibility as described above. |
| Q02 | N/A | Outside this module’s responsibility as described above. |
| Q03 | N/A | Outside this module’s responsibility as described above. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | N/A | Outside this module’s responsibility as described above. |
| Q09 | N/A | Outside this module’s responsibility as described above. |
| Q10 | N/A | Outside this module’s responsibility as described above. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | N/A | Outside this module’s responsibility as described above. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | N/A | Outside this module’s responsibility as described above. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | N/A | Outside this module’s responsibility as described above. |
| Q43 | N/A | Outside this module’s responsibility as described above. |
| Q44 | N/A | Outside this module’s responsibility as described above. |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | N/A | Outside this module’s responsibility as described above. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | N/A | Outside this module’s responsibility as described above. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | N/A | Outside this module’s responsibility as described above. |
| Q66 | PARTIAL | JVM source exercises variant and public Flow types using the published root coordinate. Consumer publication verification is historical. [DesktopConsumer.kt:15](../../sample/consumer-desktop/src/main/kotlin/com/transfer/flash/sample/consumerdesktop/DesktopConsumer.kt#L15) |
| Q67 | N/A | Outside this module’s responsibility as described above. |
| Q68 | N/A | Outside this module’s responsibility as described above. |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | PARTIAL | JVM source exercises variant and public Flow types using the published root coordinate. Consumer publication verification is historical. [DesktopConsumer.kt:15](../../sample/consumer-desktop/src/main/kotlin/com/transfer/flash/sample/consumerdesktop/DesktopConsumer.kt#L15) |
| Q97 | PARTIAL | JVM source exercises variant and public Flow types using the published root coordinate. Consumer publication verification is historical. [DesktopConsumer.kt:15](../../sample/consumer-desktop/src/main/kotlin/com/transfer/flash/sample/consumerdesktop/DesktopConsumer.kt#L15) |
| Q98 | N/A | Outside this module’s responsibility as described above. |
| Q99 | N/A | Outside this module’s responsibility as described above. |
| Q100 | N/A | Outside this module’s responsibility as described above. |

### `:benchmark`

**Audit scope.** Android startup timing and baseline-profile runner; no feature state or production data processing. [StartupBenchmark.kt](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt), [BaselineProfileGenerator.kt](../../benchmark/src/main/java/com/transfer/flash/benchmark/BaselineProfileGenerator.kt)

**Existing test reports.** No saved module test XML found.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | N/A | Outside this module’s responsibility as described above. |
| Q02 | N/A | Outside this module’s responsibility as described above. |
| Q03 | N/A | Outside this module’s responsibility as described above. |
| Q04 | N/A | Outside this module’s responsibility as described above. |
| Q05 | N/A | Outside this module’s responsibility as described above. |
| Q06 | N/A | Outside this module’s responsibility as described above. |
| Q07 | N/A | Outside this module’s responsibility as described above. |
| Q08 | N/A | Outside this module’s responsibility as described above. |
| Q09 | N/A | Outside this module’s responsibility as described above. |
| Q10 | N/A | Outside this module’s responsibility as described above. |
| Q11 | N/A | Outside this module’s responsibility as described above. |
| Q12 | N/A | Outside this module’s responsibility as described above. |
| Q13 | N/A | Outside this module’s responsibility as described above. |
| Q14 | N/A | Outside this module’s responsibility as described above. |
| Q15 | N/A | Outside this module’s responsibility as described above. |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | N/A | Outside this module’s responsibility as described above. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | PARTIAL | Cold startup fixture declares a home/launch loop using Macrobenchmark. Device execution, compatibility and recorded timings are unverified. [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| Q22 | UNVERIFIED | No scenario-specific evidence reviewed for device restart at this module boundary. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | N/A | Outside this module’s responsibility as described above. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | PARTIAL | Cold startup fixture declares a home/launch loop using Macrobenchmark. Device execution, compatibility and recorded timings are unverified. [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| Q43 | PARTIAL | Cold startup fixture declares a home/launch loop using Macrobenchmark. Device execution, compatibility and recorded timings are unverified. [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| Q44 | PARTIAL | Cold startup fixture declares a home/launch loop using Macrobenchmark. Device execution, compatibility and recorded timings are unverified. [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| Q45 | N/A | Outside this module’s responsibility as described above. |
| Q46 | N/A | Outside this module’s responsibility as described above. |
| Q47 | N/A | Outside this module’s responsibility as described above. |
| Q48 | N/A | Outside this module’s responsibility as described above. |
| Q49 | N/A | Outside this module’s responsibility as described above. |
| Q50 | N/A | Outside this module’s responsibility as described above. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | N/A | Outside this module’s responsibility as described above. |
| Q55 | N/A | Outside this module’s responsibility as described above. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | N/A | Outside this module’s responsibility as described above. |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | N/A | Outside this module’s responsibility as described above. |
| Q66 | PARTIAL | Benchmark module declares target project and variant. Missing setup or API mismatch surfaces through the runner; no current device report was saved. [build.gradle.kts](../../benchmark/build.gradle.kts) |
| Q67 | PARTIAL | Cold startup fixture declares a home/launch loop using Macrobenchmark. Device execution, compatibility and recorded timings are unverified. [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| Q68 | PARTIAL | Cold startup fixture declares a home/launch loop using Macrobenchmark. Device execution, compatibility and recorded timings are unverified. [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | N/A | Outside this module’s responsibility as described above. |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | PARTIAL | Benchmark module declares target project and variant. Missing setup or API mismatch surfaces through the runner; no current device report was saved. [build.gradle.kts](../../benchmark/build.gradle.kts) |
| Q97 | N/A | Outside this module’s responsibility as described above. |
| Q98 | N/A | Outside this module’s responsibility as described above. |
| Q99 | FAIL | Generator traces startup only. Its description mentions critical Compose paths, but the script never navigates them. See IF-04 below. [BaselineProfileGenerator.kt:18](../../benchmark/src/main/java/com/transfer/flash/benchmark/BaselineProfileGenerator.kt#L18) |
| Q100 | N/A | Outside this module’s responsibility as described above. |

### `third_party/webrtc-kmp`

**Audit scope.** Vendored multiplatform WebRTC interop, native media/device bindings and peer connections; Flash signaling and product storage are upstream callers. [MODIFICATIONS.md](../../third_party/webrtc-kmp/MODIFICATIONS.md), [build.gradle.kts](../../third_party/webrtc-kmp/webrtc-kmp/build.gradle.kts), [DesktopScreenCapture.kt](../../third_party/webrtc-kmp/webrtc-kmp/src/jvmMain/kotlin/com/shepeliev/webrtckmp/DesktopScreenCapture.kt)

**Existing test reports.** No saved module test XML found.

| ID | Verdict | Evidence / gap |
|---|---|---|
| Q01 | UNVERIFIED | No scenario-specific evidence reviewed for null input at this module boundary. |
| Q02 | UNVERIFIED | No scenario-specific evidence reviewed for empty input at this module boundary. |
| Q03 | UNVERIFIED | No scenario-specific evidence reviewed for malformed input at this module boundary. |
| Q04 | UNVERIFIED | No scenario-specific evidence reviewed for unknown fields at this module boundary. |
| Q05 | UNVERIFIED | No scenario-specific evidence reviewed for unsupported versions at this module boundary. |
| Q06 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate requests at this module boundary. |
| Q07 | UNVERIFIED | No scenario-specific evidence reviewed for concurrent requests at this module boundary. |
| Q08 | PARTIAL | JVM wrapper guards capture close and late video sink work. Included build has no local saved XML and native shutdown acceptance is still needed. [MODIFICATIONS.md:26](../../third_party/webrtc-kmp/MODIFICATIONS.md#L26) |
| Q09 | UNVERIFIED | No scenario-specific evidence reviewed for cancellation at this module boundary. |
| Q10 | UNVERIFIED | No scenario-specific evidence reviewed for timeouts at this module boundary. |
| Q11 | UNVERIFIED | No scenario-specific evidence reviewed for storage outage at this module boundary. |
| Q12 | PARTIAL | Shared peer connection fixtures exercise setup and close. They do not establish physical ICE recovery on current platforms. [PeerConnectionTest.kt:41](../../third_party/webrtc-kmp/webrtc-kmp/src/commonTest/kotlin/com/shepeliev/webrtckmp/PeerConnectionTest.kt#L41) |
| Q13 | PARTIAL | Shared peer connection fixtures exercise setup and close. They do not establish physical ICE recovery on current platforms. [PeerConnectionTest.kt:41](../../third_party/webrtc-kmp/webrtc-kmp/src/commonTest/kotlin/com/shepeliev/webrtckmp/PeerConnectionTest.kt#L41) |
| Q14 | PARTIAL | Shared peer connection fixtures exercise setup and close. They do not establish physical ICE recovery on current platforms. [PeerConnectionTest.kt:41](../../third_party/webrtc-kmp/webrtc-kmp/src/commonTest/kotlin/com/shepeliev/webrtckmp/PeerConnectionTest.kt#L41) |
| Q15 | PARTIAL | Shared peer connection fixtures exercise setup and close. They do not establish physical ICE recovery on current platforms. [PeerConnectionTest.kt:41](../../third_party/webrtc-kmp/webrtc-kmp/src/commonTest/kotlin/com/shepeliev/webrtckmp/PeerConnectionTest.kt#L41) |
| Q16 | N/A | Outside this module’s responsibility as described above. |
| Q17 | N/A | Outside this module’s responsibility as described above. |
| Q18 | N/A | Outside this module’s responsibility as described above. |
| Q19 | UNVERIFIED | No scenario-specific evidence reviewed for key expiry at this module boundary. |
| Q20 | UNVERIFIED | No scenario-specific evidence reviewed for clock jumps at this module boundary. |
| Q21 | N/A | Outside this module’s responsibility as described above. |
| Q22 | N/A | Outside this module’s responsibility as described above. |
| Q23 | N/A | Outside this module’s responsibility as described above. |
| Q24 | N/A | Outside this module’s responsibility as described above. |
| Q25 | N/A | Outside this module’s responsibility as described above. |
| Q26 | N/A | Outside this module’s responsibility as described above. |
| Q27 | N/A | Outside this module’s responsibility as described above. |
| Q28 | N/A | Outside this module’s responsibility as described above. |
| Q29 | N/A | Outside this module’s responsibility as described above. |
| Q30 | N/A | Outside this module’s responsibility as described above. |
| Q31 | N/A | Outside this module’s responsibility as described above. |
| Q32 | UNVERIFIED | No scenario-specific evidence reviewed for Unicode text at this module boundary. |
| Q33 | N/A | Outside this module’s responsibility as described above. |
| Q34 | N/A | Outside this module’s responsibility as described above. |
| Q35 | N/A | Outside this module’s responsibility as described above. |
| Q36 | N/A | Outside this module’s responsibility as described above. |
| Q37 | N/A | Outside this module’s responsibility as described above. |
| Q38 | N/A | Outside this module’s responsibility as described above. |
| Q39 | N/A | Outside this module’s responsibility as described above. |
| Q40 | N/A | Outside this module’s responsibility as described above. |
| Q41 | N/A | Outside this module’s responsibility as described above. |
| Q42 | UNVERIFIED | No scenario-specific evidence reviewed for backgrounding at this module boundary. |
| Q43 | UNVERIFIED | No scenario-specific evidence reviewed for foregrounding at this module boundary. |
| Q44 | PARTIAL | JVM wrapper guards capture close and late video sink work. Included build has no local saved XML and native shutdown acceptance is still needed. [MODIFICATIONS.md:26](../../third_party/webrtc-kmp/MODIFICATIONS.md#L26) |
| Q45 | UNVERIFIED | No scenario-specific evidence reviewed for out-of-order callback at this module boundary. |
| Q46 | UNVERIFIED | No scenario-specific evidence reviewed for missing callback at this module boundary. |
| Q47 | UNVERIFIED | No scenario-specific evidence reviewed for racing producers at this module boundary. |
| Q48 | UNVERIFIED | No scenario-specific evidence reviewed for full queue at this module boundary. |
| Q49 | UNVERIFIED | No scenario-specific evidence reviewed for slow consumer at this module boundary. |
| Q50 | UNVERIFIED | No scenario-specific evidence reviewed for worker exception at this module boundary. |
| Q51 | N/A | Outside this module’s responsibility as described above. |
| Q52 | N/A | Outside this module’s responsibility as described above. |
| Q53 | N/A | Outside this module’s responsibility as described above. |
| Q54 | UNVERIFIED | No scenario-specific evidence reviewed for reported errors at this module boundary. |
| Q55 | UNVERIFIED | No scenario-specific evidence reviewed for retry backoff at this module boundary. |
| Q56 | N/A | Outside this module’s responsibility as described above. |
| Q57 | N/A | Outside this module’s responsibility as described above. |
| Q58 | N/A | Outside this module’s responsibility as described above. |
| Q59 | N/A | Outside this module’s responsibility as described above. |
| Q60 | N/A | Outside this module’s responsibility as described above. |
| Q61 | N/A | Outside this module’s responsibility as described above. |
| Q62 | N/A | Outside this module’s responsibility as described above. |
| Q63 | PARTIAL | Shared data channel flow has a fixture. Included-build XML is absent. [DataChannelTest.kt:39](../../third_party/webrtc-kmp/webrtc-kmp/src/commonTest/kotlin/com/shepeliev/webrtckmp/DataChannelTest.kt#L39) |
| Q64 | N/A | Outside this module’s responsibility as described above. |
| Q65 | UNVERIFIED | No scenario-specific evidence reviewed for duplicate construction at this module boundary. |
| Q66 | UNVERIFIED | No scenario-specific evidence reviewed for missing dependency at this module boundary. |
| Q67 | PARTIAL | Native enumeration has a fallback for unavailable Linux audio and host MediaStack tests exist in calling. Verify with native backends. [MODIFICATIONS.md:29](../../third_party/webrtc-kmp/MODIFICATIONS.md#L29) |
| Q68 | PARTIAL | Native enumeration has a fallback for unavailable Linux audio and host MediaStack tests exist in calling. Verify with native backends. [MODIFICATIONS.md:29](../../third_party/webrtc-kmp/MODIFICATIONS.md#L29) |
| Q69 | N/A | Outside this module’s responsibility as described above. |
| Q70 | N/A | Outside this module’s responsibility as described above. |
| Q71 | PARTIAL | Shared peer connection fixtures exercise setup and close. They do not establish physical ICE recovery on current platforms. [PeerConnectionTest.kt:41](../../third_party/webrtc-kmp/webrtc-kmp/src/commonTest/kotlin/com/shepeliev/webrtckmp/PeerConnectionTest.kt#L41) |
| Q72 | N/A | Outside this module’s responsibility as described above. |
| Q73 | N/A | Outside this module’s responsibility as described above. |
| Q74 | N/A | Outside this module’s responsibility as described above. |
| Q75 | N/A | Outside this module’s responsibility as described above. |
| Q76 | N/A | Outside this module’s responsibility as described above. |
| Q77 | N/A | Outside this module’s responsibility as described above. |
| Q78 | N/A | Outside this module’s responsibility as described above. |
| Q79 | N/A | Outside this module’s responsibility as described above. |
| Q80 | N/A | Outside this module’s responsibility as described above. |
| Q81 | N/A | Outside this module’s responsibility as described above. |
| Q82 | N/A | Outside this module’s responsibility as described above. |
| Q83 | N/A | Outside this module’s responsibility as described above. |
| Q84 | N/A | Outside this module’s responsibility as described above. |
| Q85 | N/A | Outside this module’s responsibility as described above. |
| Q86 | N/A | Outside this module’s responsibility as described above. |
| Q87 | N/A | Outside this module’s responsibility as described above. |
| Q88 | N/A | Outside this module’s responsibility as described above. |
| Q89 | N/A | Outside this module’s responsibility as described above. |
| Q90 | N/A | Outside this module’s responsibility as described above. |
| Q91 | N/A | Outside this module’s responsibility as described above. |
| Q92 | N/A | Outside this module’s responsibility as described above. |
| Q93 | N/A | Outside this module’s responsibility as described above. |
| Q94 | N/A | Outside this module’s responsibility as described above. |
| Q95 | N/A | Outside this module’s responsibility as described above. |
| Q96 | PARTIAL | Fork publication coordinate, version, and packaging are documented. Verify each target through consumer acceptance. [MODIFICATIONS.md:28](../../third_party/webrtc-kmp/MODIFICATIONS.md#L28) |
| Q97 | PARTIAL | Native enumeration has a fallback for unavailable Linux audio and host MediaStack tests exist in calling. Verify with native backends. [MODIFICATIONS.md:29](../../third_party/webrtc-kmp/MODIFICATIONS.md#L29) |
| Q98 | UNVERIFIED | No scenario-specific evidence reviewed for flaky test at this module boundary. |
| Q99 | FAIL | JVM display capture through upstream getDisplayMedia remains a stub. Flash routes through DesktopScreenCapture for its product path. [MODIFICATIONS.md:26](../../third_party/webrtc-kmp/MODIFICATIONS.md#L26) |
| Q100 | UNVERIFIED | No scenario-specific evidence reviewed for missing security coverage at this module boundary. |

## Findings to act on

| ID | Severity | Matrix | Finding and expected behavior |
|---|---|---|---|
| IF-01 | P1 | server Q81/Q89 | **Required group encryption stops at the clients.** The Hub uses the mode for inspectable-message auditing. Its relay still routes readable group frames when the mode is required. Current clients reject some such traffic at receipt, after relay and mailbox handling. Require the server to refuse incompatible group traffic before delivery or queuing, with an explicit policy response for old clients. [RelayFrameRouter.kt:230](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt#L230) [RelayFrameRouter.kt:259](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt#L259) [HubChatBridge.kt:733](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubChatBridge.kt#L733) |
| IF-02 | P1 | app and desktop Q17/Q88/Q89, messaging Q17/Q89, server Q88 | **Changed Hub policy can leave connected clients using an old policy.** The admin write persists and audits the value. The WebSocket hint follows directory version changes. Client policy fetch runs on a snapshot outcome, updates only on success, and assigns the response without checking the current enrollment. After an inspectable → required change, a connected host can continue sending readable group content while it holds the previous mode. Refresh and apply policy per enrollment, and preserve confidentiality during refresh failure. Define the behavior for existing personal-group links when disabled. [AdminApi.kt:979](../../server/src/main/kotlin/com/transfer/flash/server/admin/AdminApi.kt#L979) [RelayFrameRouter.kt:116](../../server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt#L116) [DiscoveryEngineHolder.kt:1460](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1460) [DesktopEngine.kt:1675](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L1675) [RealFlashChatRepository.kt:8249](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8249) |
| IF-03 | Feature gap | messaging and server Q99 | **A Hub directory group membership invite by link is missing.** The current landing page opens Flash with a signed local-group credential. Hub directory membership uses enrollment, self-service join, or administrator assignment. A shareable off-LAN membership invite needs a Hub-issued credential and acceptance flow. [RealFlashChatRepository.kt:2283](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L2283) [RealFlashChatRepository.kt:8415](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8415) [InviteWebRoutes.kt:14](../../server/src/main/kotlin/com/transfer/flash/server/invites/InviteWebRoutes.kt#L14) |
| IF-04 | P3 | benchmark Q99 | **Baseline profile covers launch only.** The module says it captures critical Compose paths, but the generator presses Home and launches. Include the intended interactions or describe the startup scope. [BaselineProfileGenerator.kt:9](../../benchmark/src/main/java/com/transfer/flash/benchmark/BaselineProfileGenerator.kt#L9) [StartupBenchmark.kt:21](../../benchmark/src/main/java/com/transfer/flash/benchmark/StartupBenchmark.kt#L21) |
| IF-05 | P1 | messaging Q54/Q64/Q91/Q95, host/UI partial evidence | **Partial Hub group creation reports success with an inaccurate local roster.** The group is created first. Add-member failures are logged, then all requested members are locally inserted as active and the call returns success. A later signed roster can reconcile the UI. Surface the failed adds and build or refresh local membership from confirmed responses. [RealFlashChatRepository.kt:8305](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8305) [RealFlashChatRepository.kt:8334](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8334) [MainActivity.kt:3951](../../app/src/main/java/com/transfer/flash/MainActivity.kt#L3951) [DesktopShell.kt:2555](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L2555) |
| IF-06 | P2 | ui:chat Q06, messaging Q06/Q07/Q65, app Q06/Q07 | **A slow Hub group create can accept repeated taps.** The sheet enables Create from title and eligibility, then the hosts launch the request asynchronously. Every repository call generates a fresh ID. Gate the UI action during the request and give retries a stable identity. [FlashCreateGroupSheet.kt:342](../../ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashCreateGroupSheet.kt#L342) [MainActivity.kt:3951](../../app/src/main/java/com/transfer/flash/MainActivity.kt#L3951) [DesktopShell.kt:2555](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt#L2555) [RealFlashChatRepository.kt:8292](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8292) |

The P1/P2 findings come from current source. Failure conditions and pass criteria are in the [test backlog](../testing/TEST-BACKLOG.md#2026-11-18--module-if-review-follow-up). Live radio, native Wi-Fi Direct, and transfer restart acceptance are tracked in the existing plans and backlog.

## Earlier Hub groups audit against current source

The Nov. 16 Hub groups review captured the pre-fix baseline. Client commit 070cd00a and server commit c1afba21 are in this checkout. The tests cited below are source evidence and live acceptance remains open.

| Earlier item | Current source and remaining check |
|---|---|
| C1 — Group presence | Host regression checks list, header and member states for Hub-online peers. Verify on clients connected through the updated Hub. [RealFlashChatRepositoryColleagueTest.kt:1692](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1692) |
| C2 — Join roster | Join projects the local member and requests forced sync. Verify approval and a failed post-join sync. [RealFlashChatRepositoryColleagueTest.kt:1749](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1749) |
| C3 — Client group creation and linking | Dedicated directory group create is wired to both hosts. Personal-group linking remains local. Partial membership and duplicate create are IF-05/06. [RealFlashChatRepositoryColleagueTest.kt:1802](../../core/messaging/src/commonTest/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepositoryColleagueTest.kt#L1802) [RealFlashChatRepository.kt:8238](../../core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt#L8238) |
| C4 — WebSocket sync | Server broadcasts a directory hint and both hosts collect it. Verify cross-client propagation with updated Hub and client builds. [RelayFrameRouterTest.kt:814](../../server/src/test/kotlin/com/transfer/flash/server/relay/RelayFrameRouterTest.kt#L814) [DiscoveryEngineHolder.kt:1474](../../app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1474) [DesktopEngine.kt:1689](../../desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt#L1689) |
| S1 — Enrollment roster version | Auto-join writes the new roster version with the new member, with matching memory and database assertions. [OrganizationDirectory.kt:999](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt#L999) [OrganizationDirectoryTest.kt:341](../../server/src/test/kotlin/com/transfer/flash/server/directory/OrganizationDirectoryTest.kt#L341) |
| S2 — Removed member preauthorization | Member removal deletes stored and cached preauthorization. Check a subsequent join against the group policy. [OrganizationDirectory.kt:1186](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt#L1186) |
| S3 — Invalid group admin IDs | Create and set-admin operations refuse unknown IDs. Console groups with no selected admins still start with zero members by design in the current tests. Confirm whether the product should require an initial member. [OrganizationDirectory.kt:664](../../server/src/main/kotlin/com/transfer/flash/server/directory/OrganizationDirectory.kt#L664) [OrganizationDirectoryTest.kt:387](../../server/src/test/kotlin/com/transfer/flash/server/directory/OrganizationDirectoryTest.kt#L387) |

## Remaining checks

- Run the owed cases in the test backlog against matching client and Hub builds. Prioritize IF-01/02/05/06, DESK-RESUME-01, XFER-RETRY-02, UXPOL-17 and MEDIA-PLAY-01..04.
- Expand the remaining applicable rows with targeted evidence, particularly storage and migration faults, connection loss during active work, OS permission revocation and lifecycle, and assistive technology.
- The current working tree includes an import fix in the colleague regression and a declaration-order fix in DesktopShell. This review used that working tree for source evidence.
