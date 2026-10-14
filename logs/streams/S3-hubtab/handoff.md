# S3 handoff (stream S3-hubtab, branch agent/S3-hubtab)

**State.** H0-H5 built and unit-tested; both host apps compile. Not device-verified. H6+ NOT started (needs B1 snapshot, A2 visibility, A3 announcements).
**Next.** H6 (repository facade) and H7 (screens in `ui/chat/.../ui/hub/`): replace `FlashHubPlaceholderScreen`, move its copy to resources, give Hub a two-pane detail on wide windows, move "Browse department groups" into the Groups segment and then delete the placeholder's button.
**Known gaps.** Top bar may overflow at 320 dp (HUBT-16); cold-start flicker for enrolled devices (ADR-159); ADR-160 awaiting the owner.
**Build recipe in a cloud sandbox.** Android SDK from dl.google.com + Gradle init script redirecting `repo.maven.apache.org` to the Google mirror (see progress.md).
**Files.** `ui/chat/.../navigation/FlashNavigation.kt`, `shell/FlashShellTabs.kt`, `hub/FlashHubPlaceholderScreen.kt`, `chat/FlashChatListTopBar*.kt`, `app/.../MainActivity.kt`, `desktop/.../DesktopShell.kt`, `DesktopSideBar.kt`, `DesktopMain.kt`.
