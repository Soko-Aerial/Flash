# Group History Join Card — UI-057

**Status:** DESIGNED  
**Component ID:** UI-057  
**Last updated:** 2026-10-09  
**Owner phase:** Group history sync (`docs/group/GROUP-SYNC-REVAMP-PLAN.md`, ADR-100)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Builds on:** [group-ui.md](group-ui.md) (UI-029 group chat, UI-052 catch-up banner), [group-settings.md](group-settings.md) (UI-053)

---

## Component

`FlashGroupHistoryCard` with its pure helper `FlashGroupHistoryMath`, plus two additions to `FlashGroupSettingsSheet`: an admin-only "How much earlier history members can get" control (the signed `historyCeiling`) and a "Load older messages" row.

## Purpose

A device that has just joined a group has no earlier messages. Before this work it silently received the last 24 hours of text and the file offers of the last 7 days. The owner decided (2026-10-08/09): a new member gets 30 days by default, may choose less, "no history" means no files, and an admin can lower or raise the ceiling for the whole group. The card is where a new member makes that choice once, in plain words, and where a group that shares nothing says so.

The card must:

- appear once per group, for a newly joined device only (the repository creates a `PENDING` `group_history_state` row; a device that already has messages, or a group that predates the feature, never sees it);
- offer only choices inside the ceiling (`GroupHistoryPolicy.messageOptions`);
- for a `NONE` ceiling show "This group does not share earlier history" and nothing to choose;
- leave a later way to ask for more ("Load older messages"), still inside the ceiling.

## Research sources

- Telegram, Signal, WhatsApp and Matrix/Element behaviour for "what a new member sees" (studied from public behaviour and documentation, no code copied):
  - Telegram supergroups: history is visible to all new members by default, an admin switch "Hide history for new members" is the only control; a new member has no choice.
  - Signal groups: a new member sees nothing earlier; there is no choice and no explanation inside the chat.
  - Matrix/Element: room setting "Who can read history" (anyone / members since joining / since invited), an admin choice, shown as a radio list in room settings.
  - WhatsApp communities: the new member sees only what arrives after joining.
- Project sources: ADR-074 (signed group settings), ADR-063 (member roles), `docs/group/GROUP-SYNC-REVAMP-PLAN.md`, `docs/ui/group-ui.md` (UI-052 banner), `docs/ui/group-settings.md`.
- Compose Multiplatform: `AnimatedVisibility`, `FlowRow` (used elsewhere in `ui/chat`, e.g. `FlashAttachmentSheet`). No new dependency.

## Existing approaches studied

1. **Modal dialog at first open.** Blocks the chat until the member chooses. Clear, but a modal on arrival is hostile when the member only wanted to look, it hides the group they just joined, and on desktop it steals focus from a possibly typed message. Rejected.
2. **Inline card at the top of the conversation, pinned above the messages until answered.** Visible without blocking, scrolls with the screen's structure, answers "where did the earlier messages go" at the exact place the question appears. Keyboard and screen-reader friendly. Chosen.
3. **Settings-only (Matrix style): no prompt, a row in the group settings sheet.** Quiet, but a new member never finds it, and the default would apply unseen, which is the very behaviour the owner wants made explicit. Kept only for the later "Load older messages" action, not as the first answer.
4. **Bottom sheet that opens automatically.** Same blocking problem as the dialog, and the sheet already serves group settings. Rejected for the first-run case.
5. **Silent default with a toast.** Cheap, but a toast disappears and cannot carry a choice. Rejected.

## What worked

- Telegram and Matrix: the history rule is a group-level, admin-owned setting, and the member sees the effect, not the mechanism.
- Matrix's plain wording of the options (by time, not by message count).
- Signal's silence for "nothing earlier" is correct for a `NONE` group, but it is made explicit with one sentence.

## What did not work

- A choice by message count ("last 200 messages"): holders cannot promise a count and the wire window is time-based.
- Showing the ceiling as a technical value ("D30"): never visible; the card speaks in days.
- A slider: imprecise for five fixed steps and poor for keyboard and TalkBack.

## Chosen approach

An inline card, a **Flash system card**, rendered above the message list by `FlashConversationScreen` while `FlashConversationUiState.groupHistory` is non-null and `pending`. Two states:

**Choice state** (ceiling above `NONE`)

- Title: "Catch up on this group?"
- Body: "You joined recently. Choose how much earlier conversation to receive from the members who are online."
- A single-select chip rail of message windows, derived from the ceiling: `None`, `24 hours`, `7 days`, `30 days`, `Everything available`. Only options at or below the ceiling are shown, the default selection is 30 days clamped to the ceiling.
- A "Include files" switch row (default on). Off and disabled, with the supporting line "No history means no files", when `None` is selected.
- Primary button "Catch up" (applies the selection), secondary text button "Not now" (applies `None`, which is the same as `skipGroupHistory`).
- Footer line when the ceiling is below the usual 30 days: "This group shares up to 7 days." (days from the ceiling).

**No-history state** (ceiling `NONE`)

- Title: "No earlier history"
- Body: "This group does not share earlier history." (exact line: "This group does not share earlier history")
- Single button "OK", which marks the card answered (equivalent to skip). No chips, no switch.

After the answer the card collapses with a short height/fade transition and is gone for good (the stored state is `DECIDED` or `SKIPPED`). The UI-052 banner then shows progress "N of about M" while the catch-up runs.

**Later: "Load older messages"** is a row in the group settings sheet, "History" section, shown to any member whose ceiling is above what they already hold. It opens an inline choice of the same options, reusing the card's chip rail and clamping to the ceiling (`loadOlderGroupHistory`).

**Admin ceiling control** is a row in the same section, "How much earlier history members can get", shown only when `FlashConversationUiState.canChangeHistoryCeiling` is true (the `GroupAdminPolicy.isAdmin` seam). It is a chip rail of five values: `Nothing`, `24 hours`, `7 days`, `30 days`, `Everything available`, with the supporting line "Applies to people who join or return. Messages already received are kept." A change is a signed settings update (`updateGroupSettings(historyCeiling = ...)`); non-admins see the current value as read-only text.

## Why it was chosen

The inline card is non-blocking, answers the question where it arises and mirrors the owner's wording. The settings row covers the admin and the "later" cases without a second surface. The decision about the signed ceiling and wire is ADR-100.

## Visual specification

All tokens from `FlashTheme`; no stock Material look.

- Card: `FlashShapes` card shape, fill `colors.surfaceElevated` (or the nearest surface token already used by `FlashInlineInviteCard`), 1 dp outline in `colors.outline`, horizontal margin `spacingMd`, vertical padding `spacingMd`, inner spacing `spacingSm`.
- A small bolt/clock glyph from `FlashIcons` in `colors.accent` beside the title (reuse the existing history/clock icon; no new icon system).
- Title: `typography.titleSmall`; body: `typography.bodyMedium` in `colors.textSecondary`; footer line `typography.labelSmall`.
- Chips: `FlashShapes.chip`, 36 dp minimum height, selected chip filled with `colors.accent` at low alpha with accent text, unselected outlined. Minimum touch target 48 dp.
- Primary button: the existing Flash filled button style used by the invite sheet; secondary: text style.
- States: default, selected chip, files switch on/off, files switch disabled (None), submitting (button shows the busy state for the short repository call), answered (collapsing), no-history.

## Interaction specification

- Tap a chip to select; arrow keys move between chips on desktop, Space/Enter select. Tab order: chips, files switch, "Catch up", "Not now".
- "Catch up" calls `onChooseHistory(windowMs, includeFiles)`; "Not now" calls `onSkipHistory()`. Both are idempotent on the repository side.
- The composer stays usable while the card is shown (a message sent from the new member simply goes out).
- Pressing Escape on desktop does nothing (no accidental skip). Back on Android is not intercepted.

## Animation specification

- Enter: `FlashMotion.messageEnter()` style fade plus slide (about `normalMillis`), skipped when the card is present at first composition of a restored screen.
- Chip selection: colour crossfade over `fastMillis`.
- Exit after answering: `AnimatedVisibility` with `shrinkVertically` + `fadeOut` on `normalMillis`.
- Reduced motion (`FlashMotion.reduceMotion`): all of the above are instant.

## Gesture specification

No gestures beyond taps. The card is not swipeable; this avoids an accidental dismissal of a one-time choice. RTL: the chip rail wraps (FlowRow) and mirrors; no directional icons.

## Accessibility requirements

- The card is a labelled region ("Earlier history choice"); the title is a heading.
- Chips are a single-select group: each has `Role.RadioButton`, a selected state and spoken text such as "30 days, selected". The files switch has `Role.Switch` with its state.
- Spoken results from `FlashGroupHistoryMath.description(...)` (pure and unit-tested).
- Contrast: text on the card at least 4.5:1 in both palettes; the selected chip does not rely on colour alone (it also gains a check mark).
- Large text: the chip rail wraps, buttons grow, nothing is clipped; minimum touch targets 48 dp.
- Reduced motion honoured. Desktop: full keyboard operation, visible focus ring.

## Responsive behavior

Phone: full width card above the list. Tablet and desktop: the card is capped at the same 580 dp content width as bubbles (AD-5) and centred in the detail pane. Landscape phone: the chips wrap onto two rows rather than scrolling horizontally.

## Dark-mode behavior

Uses the deliberate dark tokens of `FlashColors`; the selected-chip fill is an accent at low alpha over the dark surface (not an inversion), the outline is the dark outline token.

## Performance considerations

The card is a single composable present only while pending; it is not part of the lazy list. State is a small immutable data class; `FlashGroupHistoryMath` is pure and allocation-light. Chip rail has at most five items.

## Implementation notes

- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashGroupHistoryCard.kt`: the composable and `FlashGroupHistoryMath` (labels, options, default selection, spoken text, no-history sentence).
- `FlashGroupSyncBanner.kt`: `FlashGroupSyncMath` gains the "of about N" label from `FlashGroupSyncUi.expectedCount`.
- `FlashGroupSettingsSheet.kt`: History section (admin ceiling control, Load older row) with new callbacks that have defaults, so existing call sites compile.
- `FlashConversationScreen.kt`: the card above the message list, callbacks `onChooseGroupHistory`, `onSkipGroupHistory`, `onLoadOlderGroupHistory`, `onSetGroupHistoryCeiling`.
- Host wiring: `app/.../MainActivity.kt`, `desktop/.../DesktopShell.kt` map the callbacks to `FlashChatRepository.chooseGroupHistory / skipGroupHistory / loadOlderGroupHistory` and `updateGroupSettings(historyCeiling = ...)`.
- No new dependency.

## Testing checklist

- [x] Unit tests of `FlashGroupHistoryMath` (options per ceiling, default clamped, NONE sentence, spoken text, files-with-none rule)
- [ ] Compose preview
- [ ] Physical device (`GSY-07`, `GSY-08` in `docs/testing/TEST-BACKLOG.md`)
- [ ] Dark mode
- [ ] Large font / display size
- [ ] RTL
- [ ] Reduced motion
- [ ] Performance spot-check

## Known limitations

- A member who joined before this feature never sees the card (the repository treats it as a returning member with the 7-day floor).
- Choosing a window larger than the holders keep returns only what they have; the card does not promise a count.
- The ceiling applies to people asking after the change; messages already received are never removed.
- Old builds do not understand a non-default ceiling (documented in ADR-100); they keep the previous setting.

## Future improvements

- A per-message "before you joined" divider at the point where history ends.
- A size estimate for the file offers.

## What makes this Flash?

The choice is made inline, in time words, by the person who joined, and it is bounded by a rule the group's admins signed; there is no server to hide history behind, so the card says honestly what the group is willing to share. The look is the Flash system-card language (chips, accent, bolt) rather than a Material dialog.
