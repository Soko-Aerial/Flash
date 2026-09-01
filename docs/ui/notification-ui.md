# notification-ui

**Status:** IMPLEMENTED (code complete, built green; device verification pending — see Testing checklist)  
**Component ID:** Notifications (Bug 7 — incoming message notifications)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)

## Component

System (status-bar) notifications for inbound chat traffic: text messages and
accepted/auto-accepted inbound attachments. This is **not** an in-app component —
it is the platform surface that makes a P2P messenger usable when the app is
backgrounded or the screen is off (Bug 6's user-visible companion).

**Owner:** app-level `FlashNotificationManager` (new, `com.transfer.flash.notifications`).
The messaging library stays notification-free; it exposes inbound-event callbacks
with default no-ops, exactly like `transportSink`.

## Purpose

1. Tell the user a peer sent them something while Flash was not on screen.
2. Make the notification **open that exact conversation** when tapped.
3. Never notify for a message the user is currently reading (open conversation + foreground).
4. Never notify twice for the same wire frame (reconnects replay frames; Room
   `OnConflictStrategy.IGNORE` on `localId` already dedupes rows — the same signal
   gates notifications).
5. Respect Android 13+ runtime `POST_NOTIFICATIONS` (already declared + requested in
   `MainActivity`) and OEM notification settings.

## Research sources

- **Notifications overview** — https://developer.android.com/develop/ui/views/notifications/build-notification
- **Notification channels** — https://developer.android.com/develop/ui/views/notifications/channels
- **NotificationCompat / core** — https://developer.android.com/reference/androidx/core/app/NotificationCompat
- **`Notification.Builder` platform class** — used already by `FlashBackgroundService`
- **Tap-behavior / PendingIntent flags** — https://developer.android.com/develop/ui/views/notifications/navigation
- **App-level best practices** (messaging apps): Telegram FOSS, Signal-Android,
  LocalSend (nearby-transfer app) — reference only for interaction patterns, no code copied.
- Existing app wiring: `MainActivity.maybeRequestNotificationPermission()` (#13),
  `FlashBackgroundService` FGS channel, `RealFlashChatRepository.onInboundWireFrame`.

## Existing approaches studied

1. **Plain `Notification.Builder` (platform)** — what `FlashBackgroundService` uses.
   Fine for a silent ongoing FGS note; verbose for message styles; no compat shims.
2. **`NotificationCompat.Builder` (androidx.core)** — backports channels, Person,
   MessagingStyle, and pending-intent flag constants (`FLAG_IMMUTABLE`) to API 24
   (the project's `minSdk`). No new dependency (androidx.core is already in the graph).
3. **OEM push / FCM-style notification routing** — irrelevant: Flash is LAN/P2P with
   no server, no internet path. Rejected as architecturally impossible for this app.
4. **Foreground-service notification reuse** — reusing the FGS notification as a
   message notifier (some transfer apps do this). Rejected: users must be able to
   dismiss a message without killing the mesh service, and channel semantics
   (importance/sound) differ.

## What worked (from reference apps)

- One **stable channel** created eagerly at app start → notifications always land.
- Tapping opens the **conversation, not the app root** (extra + `CLEAR_TOP|SINGLE_TOP`).
- Suppression while the conversation is visible on screen.
- `MessagingStyle`-like title/body: peer display name as title, message text as body.

## What did not work

- Notifying from inside the **network layer**: reconnects replay frames → duplicate
  notifications; the layer also can't know UI foreground state.
- Relying on the repository's `activeConversationId` alone for suppression — it stays
  set after the activity backgrounds, so notifications would be permanently suppressed
  for the last open thread. Foreground state must be **process-level** (Activity onStart/onStop).
- Using the launcher icon as the small icon — renders as a colored blob in the shade;
  a dedicated monochrome silhouette (`ic_notification_flash`) is required.

## Chosen approach

**Approach 2 — app-local `FlashNotificationManager` on `NotificationCompat`, fed by
library-level inbound callbacks.**

```text
WS collector → RealFlashChatRepository.onInboundWireFrame(TextMessage)
    → Room insert returns rowId != -1  (first sighting of this localId)
    → onInboundTextMessage callback (default no-op; app wires it)
        → FlashNotificationManager.showMessage(...)   [suppress if fg && open conv]
Attachment: accept path → Room insert → onInboundAttachment callback → showAttachment(...)
```

- **Channel:** `flash_messages`, `IMPORTANCE_DEFAULT`, `CATEGORY_MESSAGE`, created lazily
  at first post (idempotent) — plus kept distinct from `flash_discovery_bg`.
- **Dedupe:** `MessageDao.insert` already returns `-1` on `localId` conflict; the callback
  fires only on `rowId != -1L`, so replayed frames (reconnect replay, at-least-once
  redelivery) can never double-notify.
- **Suppression:** `FlashNotificationManager` keeps `@Volatile appForeground` +
  `@Volatile openConversationId`; `MainActivity.onStart/onStop` and the conversation
  open/close path maintain them. Suppress only when
  `appForeground && openConversationId == conversationId`.
- **Tap:** explicit `PendingIntent` to `MainActivity` carrying
  `EXTRA_CONVERSATION_ID`, `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`, activity flags
  `CLEAR_TOP | SINGLE_TOP`; handled in both `onCreate` (cold start) and `onNewIntent`
  (warm resume), then routed through a pending-conversation `MutableStateFlow` into the
  Compose shell, which performs `chatRepository.openConversation(id)` +
  `nav.navigate(FlashDestination.Conversation, conversationId = id)` once the engine is ready.
- **Per-conversation collapse:** notification id is derived from the conversationId
  hash so a chatty peer replaces (updates) their own notification instead of stacking,
  while different peers stack separately.

## Why it was chosen

- **No new dependency** (`androidx.core` already present for `ContextCompat`).
- **Keeps the architecture rule** (`AGENTS.md` §14/§16): the messaging library must not
  depend on Android UI; callbacks invert the dependency the same way `transportSink` does.
- **Backport-safe** to `minSdk 24` where the platform `Notification.Builder` would need
  branching for channels (API 26+).
- **Dedupe and suppression fall out of existing mechanisms** (IGNORE-conflict insert
  result + process lifecycle), so no new bookkeeping structures.

## Visual specification

- **Small icon:** `ic_notification_flash` — white bolt silhouette (system alpha-masks it).
- **Title:** peer display name (trust-store name; falls back to sender name / device id).
- **Body:** message text (text messages) · file name + "File"/"Photo"/"Video"/"Voice"
  (attachments, derived from MIME).
- **Color accent:** none forced; system default per-channel tint. (Avoid hardcoding
  Flash teal into a system surface the user can theme.)
- **No largeIcon, no sound override:** channel default (importance DEFAULT → gentle
  heads-up, no full-screen intent, no lockscreen-private override).

## Interaction specification

- Tap → open Flash directly in that conversation (engine-ready gating as above).
- Dismiss (swipe) → removed; nothing to clean up app-side (rows are already in Room).
- While the app is foregrounded **and** that conversation is open → no notification at all.
- Multiple messages from one peer → single updating notification (same id) showing the
  newest body. Multiple peers → separate notifications.

## Animation specification

N/A — platform notification surface; the system handles enter animation. No custom
animated notifications (Android 13+ requires `Notification.Builder` animation support
only for ongoing-progress styles, out of scope here).

## Gesture specification

N/A — platform-managed (swipe-to-dismiss). No remote input / direct reply in this
pass (tracked under Future improvements).

## Accessibility requirements

- Body text is the actual message content (screen readers announce it); the title is
  the peer name — standard messaging semantics.
- `CATEGORY_MESSAGE` lets Android treat it as a conversation (priority grouping, DND
  conversation rules) without extra APIs.

## Responsive behavior

N/A — the system renders the notification (heads-up, shade, wear/auto mirroring all
platform-driven). No app layout involved.

## Dark-mode behavior

Platform notification shade follows the system theme; the small icon is an alpha mask
so it adapts automatically. Nothing app-side.

## Performance considerations

- Callback fires once per **newly inserted** row; O(1) work, no Flow collection added.
- No notification for the FGS channel is touched (id 41 stays owned by the service).
- No wake-ups are scheduled by notifications themselves.

## Implementation notes

- `RealFlashChatRepository` gains constructor params
  `onInboundTextMessage: (conversationId: String, senderName: String?, text: String) -> Unit = { _, _, _ -> }`
  and
  `onInboundAttachment: (conversationId: String, senderName: String?, fileName: String, mimeType: String) -> Unit = { _, _, _, _ -> }`,
  both `public` (explicit API mode), both defaulted → every existing constructor site
  (tests, shared engine `Flash.kt`) compiles unchanged.
- `DiscoveryEngineHolder` wires them to `FlashNotificationManager` (app side).
- `MainActivity`:
  - `onStart` → `appForeground = true`; `onStop` → `false`;
  - conversation open/close callbacks set/clear `openConversationId` — done via the
    same `openConversation`/`closeConversation` call sites that already exist in
    `FlashShell` (pass-through manager calls, no logic duplication);
  - `onNewIntent` + `onCreate` route `EXTRA_CONVERSATION_ID` into
    `pendingNotificationConversation: MutableStateFlow<String?>`, consumed by the shell.
- Manifest: no change needed for the messages channel (channels need no permission);
  `POST_NOTIFICATIONS` already declared and runtime-requested.
- Attachment caveat honored: **pending (not yet accepted) offers do not notify** — they
  have no chat row until accepted. Only accepted/auto-accepted attachments notify.
  A transfer-offer notification is tracked as a future improvement.

## Testing checklist

- [ ] Text message from peer while app **foregrounded on that conversation** → no notification.
- [ ] Text message while app **foregrounded elsewhere** → notification appears.
- [ ] Text message while **backgrounded** → notification appears; tap opens the conversation.
- [ ] Screen off → notification appears (heads-up) — requires Bug 6 service alive.
- [ ] Peer reconnect replays the same frame → **no duplicate** notification.
- [ ] Attachment (auto-download ON) → notification with file name.
- [ ] Attachment pending acceptance → no notification (bubble Accept still shown in-chat).
- [ ] Two peers message → two separate notifications; same peer twice → one updating.
- [ ] Android 13+ with notifications denied → silently suppressed, app otherwise fine.
- [ ] Tap notification with app dead (cold start) → conversation opens after engine boot.

## Known limitations

- No direct reply / remote input (future).
- No per-conversation mute enforcement yet (the `muted` flag exists on rows but is not
  consulted by the notifier — natural follow-up).
- No notification for **pending** file offers (only accepted ones) in this pass.
- Group conversations: notifications show sender name only; group naming arrives with
  group-messaging support.

## Future improvements

- `MessagingStyle` + conversation shortcuts (Android 11+ conversation space).
- Respect the `muted` conversation flag.
- Transfer-offer notification ("Peer wants to send photo.pdf — Accept/Decline" actions).
- Notification channels settings deep-link from the app's Settings page.
- Sound/vibration policy per-channel documented in Settings.

## What makes this Flash?

The bolt silhouette in the shade is the only brand mark needed here — everything else
obeys platform conventions (that *is* the premium feel for system notifications).
The P2P-specific part is what's **absent**: no server, no push infrastructure, no
internet requirement — the notification fires from a live LAN socket kept alive by the
mesh service, which is exactly the Bug 6 guarantee this component leans on.
