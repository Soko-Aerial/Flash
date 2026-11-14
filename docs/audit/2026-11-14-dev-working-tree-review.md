# Flash dev working-tree review — 2026-11-14

Reviewed the uncommitted Android, desktop, shared transfer and UI changes on `dev` at `0b162b80`. The Hub candidate is under its own review. Locations below refer to this working tree. I would fix the P1 findings before shipping the diff.

## Remediation on dev

The follow-up replaces Android transfer slots with per-transfer tags and reconciles OS notifications on startup. Desktop alerts respect the foreground delivery decision, prioritize live requests, validate the displayed call, and open pairing through the existing dialog. Receive finalization now checks whether the attempt survived cancellation before publishing or replying. Settings selectors adapt to measured label widths, compact chat controls reserve separate targets, and voice capture has a keyboard and accessibility start action with interrupted-hold cleanup. The Nearby header uses available width for both actions and wraps for large text. The findings below describe the reviewed revision.

## Findings

### P1 · Android transfer actions can target another transfer

The notification slot allocator resolves collisions, but the new PendingIntent request codes use the raw transfer ID hash. The intents put the transfer ID in extras, which Android excludes from PendingIntent identity. With colliding IDs such as `Aa` and `BB`, the second Cancel intent updates the token held by the first notification. Cancel on the first card can cancel the second transfer. The same identity problem applies to the other per-transfer actions. Separately, clearing an ID absent from the in-memory map cancels its guessed slot. That slot can belong to a different live transfer, and after a process restart it may miss the actual collision-probed slot entirely.

**What if** two simultaneous transfers collide, then one is cancelled, dismissed or restored? Each button must still act on the transfer named on its card. Use the transfer ID in Android's matching intent fields and exact OS notification identity. Test real PendingIntents, including upgrade from the previous notification ID scheme and cold start. [Android PendingIntent reference](https://developer.android.com/reference/android/app/PendingIntent)

Source `FlashNotificationManager.kt:108,524,733,749,817`.

### P1 · Desktop alerts are lost while another Flash chat is open

The manager queues a message from another conversation and a group-file refusal while Flash has focus. The new window filters out every card while Flash is foregrounded. The expiration loop still runs, so the message or refusal can expire unseen. The attachment path also records a notice as sent before it is displayed and uses that record to suppress a subsequent completion. Deduplication is by filename, so two different peers sending the same filename can affect each other's notices.

**What if** chat A is open, chat B sends a message or attachment, and the user keeps typing in A? Show the intended in-app indication or register the event as seen only after actual delivery. A group-file refusal should appear next to the action that caused it. Correlate attachment and completion with transfer or message identity. Verify this against the combined manager and renderer, without the legacy test callback.

Source `DesktopNotificationManager.kt:209,245,319,388,515` and `DesktopTransferToastModel.kt:163`.

### P1 · Desktop pairing and call actions can operate on a different session

The pairing toast displays a code and exposes Confirm without the existing dialog's instruction to compare both devices. Confirm invokes the current pairing coordinator without binding the action to the displayed request. The call toast also carries a call ID but Accept and Decline invoke the current call without using it. The card is dismissed before the action runs, and the outcome is ignored. Call cards only clear on null or ended, leaving an actionable ringing card when a call moves into another phase while the main window stays unfocused.

**What if** an old request expires or a call changes state between display and click? An old card must be unable to act on the current request. Route pairing through the existing code comparison dialog, or give the toast the same explicit consent and session validation. Remove stale call actions as soon as ringing ends. Keep recoverable failures visible.

Source `DesktopNotificationManager.kt:522,557,618` and `FlashPairingFlow.kt:227`.

### P2 · Desktop logs now include pairing verification codes

The new pairing card puts the code in its detail, and the toast dispatcher logs the entire detail at info level. The previous pairing notification logged only the peer name. The desktop log sink writes the new entry to the local diagnostic file, as the focused test output also demonstrates with synthetic codes.

**What if** an operator exports diagnostic logs during a pairing attempt? Record the toast kind and a non-sensitive event identifier. Keep verification codes and message content out of that log path.

Source `DesktopNotificationManager.kt:563,591` and `DesktopMain.kt:518`.

### P1 · Receive cancellation and verification can disagree

The newly visible Verifying notification still offers Cancel. The repository changes the row to Verifying and hashes the file on the receive path. Cancellation can mark the row Cancelled and remove the partial file while hashing has its handle open. The finalization code can then write Completed or Failed over Cancelled and send a completion result to the peer. On a matching digest, it attempts to publish before checking cancellation. The verifier's usual I/O failures are returned as UNVERIFIABLE, which the receiver can still complete. The new generic exception branch returns MISMATCH but does not run the normal mismatch cleanup for the partial file and durable chunk records.

**What if** a user cancels during hashing, or a read fails after the sender supplied a digest? Define one terminal outcome shared by the UI, the saved file and the wire reply. Gate publication and terminal writes on the current attempt. Exercise cancellation during a blocked read and a real I/O failure separately from the synthetic unchecked exception already tested. Also preserve compatibility for peers that supplied no digest.

Source `FlashNotificationManager.kt:245`, `RealFlashTransferRepository.kt:1199,1806,1834,1916`, `WholeFileVerifier.kt:58`, and `FlashInboundRouter.kt:721`.

### P2 · Verification and retry cards can remain after their transfer changes state

Desktop removes stale transfer offers but does not reconcile the other live cards with current rows. A Verifying card has no expiry. If completion is suppressed while the window is focused, outside the deferred replay window, or deduplicated after an attachment notice, the verification card is never replaced. A retryable Failed card can also stay visible after the user retries from Transfers until the card's timer expires.

**What if** an inbound attachment finishes while its Verifying card is up and completion is deduplicated? The verification card should disappear on every exit from Verifying, independently of whether a completion notice is shown. Drop Retry when the row stops being failed and retryable.

Source `DesktopNotificationManager.kt:335,362,388,441`.

### P2 · Desktop bursts can evict actionable requests and some file buttons have no target

The queue keeps the newest six cards and displays the newest three. An incoming call, pending consent request or transfer offer can disappear behind routine chat messages and be evicted while it is still active. It is only added on the initial transition, so the card may stay gone. Completion always exposes Open File and Show in Folder even when the path is absent or unusable, and clicking either removes the card before doing nothing. The toast window itself is non-focusable, so the card actions cannot be reached by keyboard inside that window.

**What if** a call arrives just before a burst of messages, or an incoming file moves before its card is clicked? Pin or prioritize live requests, coalesce chat bursts, and provide an in-app keyboard path to pending actions. Offer file actions only with a usable target and report a missing file if it disappears after rendering.

Source `DesktopNotificationManager.kt:462,601,618`, `DesktopTransferToastModel.kt:163`, and `DesktopTransferToastWindow.kt:177`.

### P2 · The mic has a larger hit box but no accessible activation or interrupted-gesture cleanup

The new 48 dp layout is an improvement. The mic only advertises a button role and label. The custom pointer input supplies no semantic action for TalkBack, Switch Access or keyboard. Its finally block resets drag offsets. If the gesture coroutine is cancelled while the conversation remains mounted, the parent receives neither release nor a cancellation callback, so the recorder and lease can remain live. Leaving the conversation has separate disposal cleanup. The new permission cache also short-circuits a fresh Android permission check after any successful grant.

**What if** the mic is invoked without touch, a press is interrupted, or the permission is no longer available? Provide an accessible start and explicit stop/send/cancel flow, cancel capture on gesture interruption and check actual permission at capture start. Keep the visible mic compact and the larger target stable. [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

Source `FlashVoiceRecording.kt:284,323`, `FlashComposer.kt:178,448`, and `FlashConversationScreen.kt:459,468,1130`.

### P2 · Compact controls trade away labels and space for interaction

Settings removes the explicit Performance mode and Discovery mode headings, then forces all choices onto one fixed-height equal-width track. The one-line labels can truncate at narrow widths or larger fonts, especially Ultra Low and Medium. In chat, the compact lock's outer size overrides the badge's minimum-size layout, and the bubble overflow and connection Retry now measure 20 dp and 28 dp high respectively. Compose can expand some touch hit areas, but the reduced layout no longer reserves space to avoid overlap with neighbors.

**What if** a user runs at 320 dp and 200% text, or taps between two compact controls? Keep the compact visuals with distinct touch targets and test for wrong-control activation. Restore the Settings headings, use a compact track when the full options fit, and fall back to wrapping or a labeled picker when they do not. [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)

Source `FlashSettingsScreen.kt:1572,1642`, `FlashChatHeader.kt:290`, `FlashEncryptionIndicators.kt:176`, `FlashMessageBubble.kt:901`, and `FlashNetworkStatusUi.kt:221`.

### P2 · A restart and history clearing still leave gaps in Android notification lifecycle

The process-local jobs and transfer slot map disappear when the process is killed. Clearing all transfer notifications now enumerates only that map. Restoration tries to clear Failed and Completed rows, but cannot reconcile orphaned, cancelled or no-longer-present transfers, and guesses a slot for restored rows. Clearing transfer history still calls the global notification clear, so active or offered alerts disappear too. An active transfer may be reposted on a later emission, while an unchanged offer is only posted on a state change.

**What if** Android kills Flash during a transfer, or a user clears history while another incoming offer is waiting? Reconcile this app's active OS notifications with live rows after restart and remove only notices owned by cleared history. Include a true process-death test. A service-stop test does not cover this scenario.

Source `FlashBackgroundService.kt:140,354,616`, `FlashNotificationManager.kt:355,524,548`, and `MainActivity.kt:3203`.

## UI polish worth adding

1. **Make toast content answer one question per line.** For verification, use the filename as the title and “Checking file integrity…” as the status. The current title, secondary line and metadata all repeat verification, and the renderer discards the filename in the detail. Use the existing theme tokens and keep one progress cue. Let long errors wrap or expose them in the transfer detail. Check one and three cards at 150% and 200% Windows scaling.
2. **Keep Nearby actions easy to scan.** The 320 dp render preserves Pair and Chat. Long device names can lose the words that distinguish otherwise similar devices. Give the name more width when actions are secondary and ensure the full label appears in the detail and accessibility description. In the offline state, align the heading, explanation and recovery action as one group. The native offline fixture omits the Android host settings callback, so verify that button on a phone before adjusting its placement.
3. **Keep compact chat geometry without shrinking interaction.** Separate each icon's visual size from the control's hit area. Keep the lock, menu and Retry discoverable. Use the existing theme and chat spacing rather than adding a new control style.

## Verification

The focused desktop gate passed 40/40, including `DesktopNotificationManagerTest`, `DesktopTransferToastModelTest`, and the native `FlashWorkspaceTabsRenderTest`. The render fixture produced 27 Nearby PNGs. I inspected the network-off, short 200%-text and 320 dp paired-list renders. Settings and the desktop toast were reviewed from source. No physical Android device was visible over ADB and no live Windows notification interaction or Linux window-manager pass was performed. The implementation source was left untouched.
