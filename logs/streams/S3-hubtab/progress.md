# S3 Hub tab shell: progress (stream S3-hubtab, branch agent/S3-hubtab)

## Explain-back (written before code, contract section on explain-back)

Task: UI-058 / `docs/plans/HUB-TAB-IMPLEMENTATION-PLAN.md` phases H0-H5 only.

- Enrolled devices (signal: hub enrolment credentials present, only trusted once the engine is ready) get a Hub tab in Transfers' slot (ADR-133, D1). Transfers becomes a pushed destination, opened by an icon in the chat-list top bar. Unenrolled devices must see no change at all.
- H1 icons (Hub, Announcement). H2 pure navigation math with tests first. H3 Android host wiring. H4 desktop host wiring. H5 chat-list top bar (Transfers icon with active count, "Browse department groups" leaves the top bar when enrolled).
- Out of scope: H6+ (Hub data screens need B1 snapshot, A2 visibility, A3 announcements). Hub shows an honest placeholder.
- Risks I will test: process-death restore while enrolment is unknown, un-enrol while on Hub, notification/shortcut/share entry points that used `selectTab(Transfers)`, tab highlight while Transfers is pushed, exhaustive `when` over `FlashDestination` in both hosts.
- Files I may not edit: RealFlashChatRepository.kt, Flash.kt, DiscoveryEngineHolder.kt, DesktopEngine.kt, server/*, core/persistence/*.
