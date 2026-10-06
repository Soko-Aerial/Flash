# Group File Availability — UI-055

**Status:** IMPLEMENTED  
**Component ID:** UI-055  
**Last updated:** 2026-10-05  
**Owner phase:** Swarm UI (`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` §7A SW-11, ADR-072)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Builds on:** [file-card.md](file-card.md) (UI-016), [transfers-page.md](transfers-page.md) (UI-047), [settings-page.md](settings-page.md) (UI-049), [error-states.md](error-states.md) (UI-027)

---

## Component

`FlashGroupFileAvailability` and `FlashSwarmUiMath` — availability status formatters, bubble detail captions, transfers screen badges, and device-local swarm settings.

Rendered in:
1. **Chat Message Bubbles:** `FlashFileMessageCard` receiver detail line and sender availability badge.
2. **Transfers Screen:** `FlashTransfersScreen` list row status caption and origin indicator.
3. **Transfer Notifications:** System notification detail line and origin "Cancel for everyone" action.
4. **Settings Screen:** `FlashSettingsScreen` device-local swarm toggles.

## Purpose

In ordinary cloud messaging apps, files sit on a central server: download status is either downloading from the server or done. In Flash peer-to-peer group transfers (`:core:swarm`), file pieces are distributed among group members over local LAN or Wi-Fi Direct. Users need immediate clarity on:
- **Receivers:** Why is a transfer waiting? Who has the missing parts? Are we fetching from the original sender or multiple nearby group members?
- **Sender (Origin):** How many group members have received the file? Is it safe to leave the room or turn off the device? ("You can go offline now" vs "2 devices still need parts only you have").
- **Recovery & Actionability:** Can the sender re-pick a moved file to resume sharing? Does the device need more free storage?

All of this must be communicated in simple, plain human language without exposing protocol details like piece indices, Merkle roots, bitfields, or device IDs (AGENTS 22).

---

## Research sources

1. **BitTorrent Clients (Transmission, qBittorrent, Syncthing):**
   - Track swarm availability, seeds vs peers, piece distribution.
   - What they do poorly: exposes raw technical jargon ("Seeds: 2 (8)", "Availability: 1.482", "Pieces: 1024 x 1MB"). Unusable for mainstream chat.
2. **Resilio Sync / Syncthing Mobile:**
   - Shows "Syncing with 3 devices" and "Waiting for peer".
   - Clear peer count, but lacks contextual wait explanation (e.g. why sync is paused).
3. **AirDrop / Nearby Share Multi-Device:**
   - Clean, direct status: "Sending to 3 people...", "Waiting for acceptance".
   - Minimalist, but lacks swarm cooperative-sharing visibility ("You can go offline now").
4. **Telegram Group Media:**
   - Group media shows download progress ring and file size.
   - No P2P mesh visibility since files are hosted on Telegram MTProto servers.
5. **Flash Swarm Engine (`:core:swarm` and `:core:transfer`):**
   - `FlashSwarmStatus` (`holdersOnline`, `distributedCopies`, `canGoOffline`, `waitReason`, `piecesDone`, `totalPieces`, `bytesDone`, `totalBytes`).
   - `TransferFailureText` friendly failure sentences (`E-01` to `E-50`).
   - `FlashTransferWaitReason` (`WaitingForSender`, `WaitingForHolders`, `WaitingForNetwork`, `WaitingForSpace`, `WaitingForStorage`, `WaitingForSystem`, `WaitingForSession`).

---

## Existing approaches studied

### Approach A: Technical Swarm Dashboard (Torrent Style)
- Display swarm metrics: "Pieces: 48/100 • 3 Seeds • Swarm Availability: 1.8x".
- **Pros:** Precise for network engineers.
- **Cons:** Violates AGENTS §22 ("Do not put protocol/debug details in the normal user interface"). Confuses non-technical users and clutters compact message bubbles.

### Approach B: Generic Cloud-Style State Only
- Treat group files identically to 1:1 transfers: "Downloading... 48%", "Paused", "Failed".
- **Pros:** Zero new UI concepts.
- **Cons:** Complete failure of user expectations in P2P mesh: users cannot understand why a download paused at 48% when the Wi-Fi is working (they don't know the origin stepped out of the room), or whether the sender can safely disconnect their laptop after a meeting.

### Approach C: Contextual Conversational Availability (Flash Chosen Approach)
- Translate swarm state machine events into direct, actionable English phrases matching the user's role (sender vs receiver):
  - **Receiver:**
    - "Waiting for Alex to come online · 48 of 100 MB" (origin offline, union exhausted).
    - "Getting it from 3 devices" (downloading active pieces across multiple holders).
    - "Waiting for a device that has the missing parts" (origin departed/source lost, waiting for someone with missing pieces).
    - "Waiting for Wi-Fi" (network disconnected).
    - "Needs 1.2 GB, 300 MB free" (storage full).
    - "Paused by Android; continues automatically" (Android 15 6-hour service limit).
  - **Sender (Origin):**
    - "Delivered to 7 of 9" (in-flight group delivery count).
    - "You can go offline now" (every piece has at least one distributed copy among members).
    - "2 devices still need parts only you have" (origin is the sole holder of remaining pieces).
    - "Your file is no longer available. Pick it again to keep sharing" (file moved or permission revoked).
- **Pros:** Perfect user mental model; empowers users to take real-world action (e.g. asking Alex to turn on Wi-Fi, or knowing they can safely catch their train without corrupting others' downloads).

---

## What worked

- **Distinguishing Sender vs Receiver perspective:** The sender's core question is "Did they get it? Can I leave?", while the receiver's question is "Who am I getting this from? Why is it waiting?".
- **The "You can go offline now" milestone:** The most empowering UX moment in a P2P swarm. As soon as the union of pieces across peers covers the whole file (`canGoOffline = true`), the sender is notified that the group can complete without them.
- **Dynamic peer count in progress:** "Getting it from 3 devices" reassures the user that the P2P swarm is actively working and faster than 1:1 fan-out.
- **Unified formatters in math helper:** Keeping all string formatting pure in `FlashSwarmUiMath` enables 100% unit test coverage across all edge cases without Compose test harness overhead.

---

## What did not work

- **Showing individual piece blocks/grid:** Visual piece maps (like BitTorrent clients) take excessive space inside chat bubbles and distract from conversational flow.
- **Exposing raw device hashes or IDs:** Showing peer public keys or device identifiers looks like a technical error to regular users.
- **Modal alerts for offline sender:** Popping up dialogs when a peer leaves disrupts reading; unobtrusive in-bubble status lines are far superior.

---

## Chosen approach

### 1. Data Model & Math Engine (`FlashSwarmUiMath`)
Pure formatting functions in `:ui:chat`:
- `receiverStatusLine(status: FlashSwarmStatus?, transfer: FlashTransfer?, senderName: String): String`
- `senderStatusLine(status: FlashSwarmStatus?, totalMembers: Int): String`
- `canGoOfflineBadge(status: FlashSwarmStatus?): Boolean`
- `transfersStatusLine(transfer: FlashTransfer, isOrigin: Boolean, senderName: String?): String`

### 2. Receiver Bubble Detail Line
Inside `FlashFileMessageCard`, below the file name and progress bar:
- When active:
  - If downloading from > 1 peer: `"Getting it from $holdersOnline devices • $speed • $eta"`
  - If downloading from 1 peer: `"$formattedSize • $pct% • $speed • $eta"`
- When queued with wait reason:
  - `WAITING_FOR_SENDER`: `"Waiting for $senderName to come online • $bytesDone of $bytesTotal"`
  - `WAITING_FOR_HOLDERS`: `"Waiting for a device that has the missing parts"`
  - `WAITING_FOR_NETWORK`: `"Waiting for Wi-Fi"`
  - `WAITING_FOR_SPACE`: `"Needs $required, $free free"`
  - `WAITING_FOR_STORAGE`: `"Choose where to save files"`
  - `WAITING_FOR_SYSTEM`: `"Paused by Android; continues automatically"`
  - `WAITING_FOR_SESSION`: `"Connecting to group members…"`

### 3. Sender Bubble Availability & Safe-To-Leave Indicator
- Detail line shows:
  - Active: `"Delivered to $distributedCopies of $recipientCount"`
  - If all pieces distributed (`canGoOffline == true` and not all delivered):
    - Subtitle: `"Delivered to $distributedCopies of $recipientCount • You can go offline now"`
  - If incomplete and origin is sole holder:
    - `"$waitingCount devices still need parts only you have"`
  - If source file moved/deleted:
    - `"Your file is no longer available. Pick it again to keep sharing"`
- Origin Cancel Action:
  - Tapping Cancel shows dialog: `"Cancel for everyone? Members who already have the file keep it."`

### 4. Transfers Screen
- Displays the exact same friendly sentences in `FlashTransfersScreen` for group transfers, with an origin chip/badge `"Group (Sender)"` vs `"Group (Swarm)"`.

### 5. Settings Screen (`FlashSettingsScreen`)
Device-local switches under **Data and storage** → **Group file sharing**:
1. **Help share group files** (Switch, default ON):
   - Subtitle: *"Share received file pieces with other group members on your local network."*
   - Maps to `servingEnabled` in swarm engine / settings.
2. **Keep finished files available for others** (Switch, default ON):
   - Subtitle: *"Keep completed group files available to help members who come online later."*
   - Maps to retention seeding preference (7 days vs immediate).
3. **Group file sharing (swarm, experimental)** (Switch, default OFF):
   - Subtitle: *"Enable multi-device cooperative transfers in groups. Applies after restart."*
   - Maps to `swarmEnabled` in `FlashSettings`.

---

## Visual specification

- **Typography:**
  - Status detail line: `FlashTheme.typography.caption` (12sp / 16sp line height, Medium weight).
  - Safe-to-leave badge / highlight: `FlashTheme.typography.captionBold` in `FlashColors.brandAccent` (or `success` tone).
  - Error/warning captions: `FlashColors.feedbackError` / `FlashColors.feedbackWarning`.
- **Colors:**
  - Incoming bubble secondary text: `colors.chatTextTimestamp`.
  - Outgoing bubble secondary text: `colors.chatTextTimestampOutgoing`.
  - Offline badge / highlight: `colors.statusAccent` / `colors.brandPrimary`.
- **Spacing:**
  - 4dp vertical spacing between progress bar and detail line.
  - 6dp horizontal gap between status icon (if present) and text.

---

## Interaction specification

- **Receiver Tap:**
  - Tapping an active transfer pauses it.
  - Tapping a paused/failed transfer resumes or retries it.
  - Tapping "Choose where to save files" triggers SAF folder picker.
- **Sender Tap:**
  - Tapping "Pick it again to keep sharing" opens file picker to restore source.
  - Tapping Cancel triggers confirmation dialog:
    - Title: `"Cancel for everyone?"`
    - Body: `"Members who already have the file keep it."`
    - Positive: `"Cancel for everyone"` (`cancelAsOrigin`)
    - Negative: `"Keep transfer"`
- **Settings Toggles:**
  - Immediate persistence to `FlashSettingsDataStore` / `DesktopSettingsStore`.
  - Swarm switch shows restart snackbar / toast: *"Takes effect next time Flash starts"*.

---

## Animation specification

- **Status line text transition:** `AnimatedContent` with cross-fade (150ms `FlashMotion.Durations.fast` with `FlashMotion.Easings.standardDecelerate`).
- **Safe-to-leave badge appearance:** Fade-in + slight upward slide (4dp) when `canGoOffline` becomes true.

---

## Accessibility requirements

- **TalkBack / Screen Readers:**
  - Clear `contentDescription` on the entire file card combining: file name, size, transfer progress, and the exact availability sentence (e.g., `"Report.pdf, 100 megabytes, 48% downloaded, waiting for Alex to come online"`).
- **Minimum touch target:** 48dp on all interactive elements (action button, cancel button, settings switches).
- **Contrast:** Status caption contrast exceeds 4.5:1 against incoming and outgoing bubble background surfaces.

---

## Responsive behavior

- **Phone (Compact):** Detail line truncates with ellipsis if width is constrained; primary information (wait reason or peer count) is kept front.
- **Tablet / Desktop (Expanded):** Full sentence displayed without truncation in detail pane and chat bubble.

---

## Dark-mode behavior

- Uses semantic `FlashTheme.colorScheme` tokens:
  - Surface: `colors.chatBgAttachmentIncoming` / `colors.chatBgAttachmentOutgoing`.
  - Text: `colors.chatTextIncoming` / `colors.chatTextOutgoing`.
  - Warning/Error accents maintain readability on deep charcoal dark background surfaces.

---

## Testing checklist

- [x] Pure math unit tests for all receiver and sender states (`FlashSwarmUiMathTest`).
- [ ] Compose preview for receiver wait reasons.
- [ ] Compose preview for sender "You can go offline now" state.
- [ ] Settings screen toggles test.
- [ ] Dark mode and light mode visual verification.
- [ ] TalkBack accessibility string verification.

---

## What makes this Flash?

Instead of treating peer-to-peer transfers as an invisible black box or overwhelming users with torrent jargon, Flash gives users transparent, respectful, human-scale awareness of their local network. Telling a sender "You can go offline now" turns a complex distributed consensus into a moment of pure relief and user delight.
