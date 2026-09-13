# PHASE-30 — Desktop transfers (the desktop can receive but cannot send)

**Status:** AUTHORED (2026-09-13, planning only — **no code written**). **Blocked by Phase 27.**
**Risk:** MEDIUM — no protocol change, but it adds the first *outbound* path on desktop, which the
Phase 16 gate has only ever exercised through the headless harness.
**Decisions relied on:** D8 = A, R5, R6.

---

## What this phase is for

The good news first, because it is most of the screen: **Transfers is already at call-site parity
between the two hosts.** Both pass the same seven callbacks.

| | Android `MainActivity.kt:1304–1345` | Desktop `DesktopShell.kt:241–291` |
|---|---|---|
| `onPauseResumeClick` / `onCancelClick` / `onRetryClick` | repo `pauseTransfer`/`resumeTransfer`/`cancelTransfer` | **identical** |
| `onAcceptOffer` / `onDeclineOffer` | `acceptIncoming` / `declineIncoming` | **identical** |
| `onHistoryOpen` | `openAttachment(context, path, mime)` | `DesktopHelpers.openAttachment(path, mime)` — plus a detail pane in two-pane mode |
| `onHistoryShare` | `shareTransferredFile(context, item)` | `DesktopHelpers.shareTransferredFile(item)` |

`FlashTransfersScreen`'s signature (`ui/chat/.../FlashTransfersScreen.kt`) takes no platform types.
Nothing about the screen is desktop-shaped, and both hosts already give it the same behaviour.

**The gap is not in the screen. It is that there is no way to start a transfer.**

Verified by grep: `desktop/src/**` contains **zero** references to `JFileChooser`, `FileDialog`,
`FlashFilePicker`, `sendFile`, `enqueue` or any send-side repository call. The desktop app can:

- **receive** files (the Phase 16 stack, `RealFlashTransferRepository` with
  `requireReceiverAcceptance = true` — `DesktopEngine.kt:235`), and
- **not send** one, at all, through any UI.

On Android, sending starts in a **conversation** — the composer's attachment button
(`FlashAttachmentButton`) → `FlashAttachmentSheet` → `FlashFilePickerLauncher`. Desktop has no
conversation screen (Phase 29) and no attachment affordance, so the entire send path is unreachable.

This matters for hardware day: **phone → desktop transfers will work; desktop → phone will not**,
even though Phase 16's G3 proved the underlying stack does both directions. The harness proved the
engine; nothing has wired the app.

## The pieces that already exist

Do not rebuild these:

- **`rememberFlashFilePickerLauncher`** — `:ui:platform-shims`, common declaration +
  **a complete JVM actual** (`ui/platform-shims/src/jvmMain/.../FlashFilePicker.jvm.kt`) using
  `javax.swing.JFileChooser`, with extension filters translated from Android MIME wildcards, and a
  documented reason for rejecting AWT `FileDialog` (Windows ignores its filter). It is
  **already tested** (`jvmTest/FlashFilePickerJvmTest.kt`). `:desktop` simply never calls it.
- **`FileSourceOpener`** — `DesktopEngine.kt:236` already wires
  `FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }`, and the JVM picker emits
  `file:/C:/…` URIs (`FlashPickedFile.uri`), the same shape the opener expects.
- **`RealFlashTransferRepository`** — already constructed and exposed as `engine.transfers`.
  The send entry point exists on the repository; only a caller is missing.

So this phase is **wiring plus one new affordance**, not new machinery.

## Design

1. **A send affordance on the Transfers screen.** The cleanest shape is a header action ("Send
   file…") that opens the picker and enqueues to a chosen peer. **A peer must be chosen**, which
  means either a peer picker dialog or entry from the Nearby list (a "Send file" action on a
   *trusted* peer row). The second is smaller and matches the trust model — sending is trust-gated
   on the phone, and `FlashNearbyScreen` already has trusted-peer rows with an action
   (`onChatTrustedClick`).
2. **A drop target on the window.** Compose Desktop supports drag-and-drop, but `:desktop` has
   **no** `dropTarget`/`onDrop`/`DragData` usage today (grep: zero). This is genuinely new code and
   the first pointer-drag handling in the repo — it should reuse the Phase 28 seam's shape rather
   than introduce a second platform-check idiom.
3. **Detail-pane actions.** Phase 22's `TransferDetailPane` (`DesktopDetailPanes.kt:29`) is
   informational today: open and reveal only. It should gain the row's own actions so a user in
   two-pane mode does not have to go back to the list.
4. **Reveal-in-folder** is currently a side effect of `shareTransferredFile` opening the *parent*
   directory (`DesktopHelpers.kt:24–29`). That is a share action being used as a reveal; it deserves
   to be an explicit action with its own label.

## Do NOT

- **Do NOT modify `FlashTransfersScreen`'s parameter list** to add a desktop-only send button. It is
  shared; a send affordance is wanted on both hosts, so it gets a shared parameter with a default.
- **Do NOT change `RealFlashTransferRepository`** — the #5 accept gate
  (`requireReceiverAcceptance = true`, sender parks until `FLASH_XFER` RESUME) is load-bearing and is
  the one behaviour a naive send path breaks. Any new caller must go through `acceptIncoming` on the
  receiving side, not around it.
- **Do NOT reuse the interop harness's send path** (`core/engine/src/jvmTest/.../DesktopInteropHarness.kt`).
  It is a test artifact with its own argument parsing; wiring the app to it would drag test code
  into production.
- **Do NOT add a file picker dependency** — R10, and the seam already exists precisely so no library
  is needed (see its KDoc on why FileKit was rejected).
- **Do NOT implement drag-and-drop in Phase 28 as well.** One drop target, here.

## Sub-step plan

| # | Sub-step | Content |
|---|---|---|
| 30-1 | Choose the send entry point | Nearby trusted-peer action vs a peer-picker dialog. Record the choice; (Nearby) is recommended. |
| 30-2 | Send wiring | Picker → `FlashPickedFile` → repository send → navigate/select on the Transfers tab so progress is visible. |
| 30-3 | Drop target | Window-level drop → same send path as 30-2. |
| 30-4 | Detail-pane actions | Pause/resume/cancel/retry/open/reveal on `TransferDetailPane`. |
| 30-5 | Verification | Compile gates + R6 scan; **human run**: desktop → phone send of a large file, pausing mid-flight and resuming, and the phone-side accept gate (the #5 behaviour must still park the sender). |
| 30-6 | Log + README | Honest entry, and update the Phase 16 row: G3-reverse becomes app-runnable, not just harness-runnable. |

## Honest limitation

A transfer needs a **trusted** peer. Desktop trust is established by Phase 26's pairing
(`~/.flash/trust.properties`), so this phase is only testable end-to-end **after a successful
pairing** — which makes the pairing runbook (`PAIRING-GATE-RUNBOOK.md`) its precondition in
practice, not just on paper.
