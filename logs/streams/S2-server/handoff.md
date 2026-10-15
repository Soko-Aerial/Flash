# S2-server handoff (fragment; integrator folds into `logs/handoff.md`)

## Current branch
`agent/S2-a3` (worktree `E:\FlashWork\S2-a3`; base `dev`)

## A3 status (2026-10-15)
Plan A3 (Announcements engine, console, persistence step 17, client API & repository) BUILT and unit-tested, NOT device-verified (tests ANN-01..ANN-11 added to `docs/testing/TEST-BACKLOG.md`).
Scope boundary strictly adhered to: Sections 3, 4.1-4.3, 5 built; Hub tab screens and notifications UI left for Plan A4 / H6+.
Plaintext on hub: ADR-138 strictly followed with no claims of encryption anywhere.

## Working (this stream)
- Text sanitization: `AnnouncementText.clean` (Unicode NFC normalization, strips bidi overrides, zero-width chars, control chars).
- Protocol & DTOs: `AnnouncementItemDto`, `AnnouncementFeedDto`, `PostAnnouncementRequestDto`, `UpdateAnnouncementRequestDto`, `AnnouncementEvent` relay frame.
- Server store & routes: `AnnouncementStore.kt` (migration 1, FTS5 with LIKE fallback, `expectedSeq` optimistic locking, pin limits 3 org / 3 dept, changes feed `since=<seq>`, soft delete tombstones, `AnnouncementPurgeJob.kt` retention cleanup). REST endpoints `/api/v1/announcements*` and `/admin/api/v1/announcements*`.
- Admin console UI: `admin-ui/js/views/announcements.js` (DOM textContent only, no innerHTML), navigation, preview drawer, department head scoping, conflict reload handling, Node tests.
- Persistence: Room schema step 17 (`HubAnnouncementEntity`, `HubAnnouncementStateEntity`, `HubAnnouncementDao`, migrations 15->16 placeholder and 16->17, verified via migrations test).
- Client API & Repository: `HubAnnouncementsApi`, `RealHubAnnouncementsApi`, `FakeHubAnnouncementsApi`, `FlashChatRepository`, `RealFlashChatRepository` announcement flow, cached search fallback, unread counters, unenrol data wipe, wired in `DiscoveryEngineHolder` (Android) and `DesktopEngine` (Desktop).
- Routes & Docs: `docs/protocol-routes.txt`, `docs/protocol.md`, `docs/security.md` (ADR-138 plaintext threat model).

## Verified
- `:server:test`: BUILD SUCCESSFUL (20 tasks, 0 failures, 3m 34s).
- `node --test server/admin-ui-tests/announcements.test.mjs`: 6 passed, 0 failures (263ms).
- `:core:messaging:jvmTest`: BUILD SUCCESSFUL (21 tasks executed, 0 failures, 1m 23s).
- `:core:persistence:jvmTest`: BUILD SUCCESSFUL (8 tasks, 0 failures, 10s).
- `:core:enterprise:jvmTest`: BUILD SUCCESSFUL (13 tasks executed, 0 failures, 59s).
- `:desktop:compileKotlinJvm`: BUILD SUCCESSFUL (0 errors).
- `:app:compileDebugKotlin`: BUILD SUCCESSFUL (0 errors).
- Two mutation checks executed and restored:
  1. Disabled `expectedSeq` in `AnnouncementStore.editAnnouncement` -> `optimisticConcurrencyOnUpdate` FAILED. Restored.
  2. Removed department visibility check in `AnnouncementStore.getChangesFeed` -> `visibilityFilterInSearchAndFeed` FAILED. Restored.

## Next Task
Integrate or merge stream S2-a3 into dev, then implement Plan A4 / H6+ (Hub tab screens and notification UI).
Do NOT modify `C:\Users\KaliOxygen\Downloads\Flash`.

## Files most relevant to next task
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/FlashChatRepository.kt`
- `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/HubAnnouncementDao.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/`
