# Group Settings Sheet — UI-053

**Status:** IMPLEMENTED AND VERIFIED  
**Component ID:** UI-053  
**Last updated:** 2026-10-05  
**Owner phase:** Track GM Phase GM-10 (`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` §7B lines 1988–2025, ADR-074)  
**Master plan:** [flash-premium-chat-ui-implementation.md](flash-premium-chat-ui-implementation.md)  
**Template:** [component-doc-template.md](component-doc-template.md)  
**Builds on:** [group-ui.md](group-ui.md) (UI-028, UI-029), [design-system.md](design-system.md) (UI-001), [icon-system.md](icon-system.md) (UI-002)

---

## Component

`FlashGroupSettingsSheet` and `FlashGroupSettingsMath` — group governance sheet presenting signed group rules, device-local file sharing preferences, invite link access, and the owner/admin "Change group code" action.

## Purpose

Groups in Flash require two distinct kinds of configuration:
1. **Signed Group Rules (ADR-074):** Canonical rules signed by an admin/owner with monotonic versioning (`GroupSettings`). Changes propagate across the mesh via `GroupWireFrame.Bundle` and are verified by `GroupSignatureRules.checkSettings`. These control:
   - Who can join (`joinPolicy`: Open vs Approval required)
   - Who can share invite links (`inviteSharers`: All members vs Admins only)
   - Who can add members directly (`membersMayAdd`: Admins only vs Members may add)
   - Whether cooperative file swarming is active for this group (`swarmServing`: On vs Off)
   - Group member capacity (`maxMembers`: integer limit up to 50)
2. **Device-Local Preferences:** Settings that govern this particular phone or PC's participation in the group (`GroupLocalPreferences`). These are *never* transmitted over the wire or stored in bundles:
   - "Help share group files" (`serveToGroup`: whether this device seeds to this group)
   - "Wi-Fi only" (`serveWifiOnly`: conserve cellular data)
   - "Battery threshold" (`batteryThresholdPercent`: pause swarm serving when battery drops below 20%)
   - "Keep finished files" (`keepAvailableDays`: cache retention)
3. **Group Code Rotation (ADR-076):** A button for admins to "Change group code", rotating the group secret to invalidate older invite links while retaining the current active membership.

Users need a clear, unmistakable visual distinction between *rules that apply to everyone in the group* (admin-only) and *preferences that apply only to this physical device*.

---

## Research sources

1. **Telegram Android Group Permissions & Settings:**
   - Clearly separates "What can members of this group do?" from personal chat notifications.
   - Admin-only controls show clear disablement or are hidden for regular members.
   - Invite link section shows current link, revocation option ("Revoke link"), and sharing options.
2. **WhatsApp Group Settings:**
   - "Group settings" accessible from group info.
   - Toggles for "Edit group settings", "Send messages", "Add other members", "Approve new members".
   - Clear explanatory captions below each toggle explaining what it does.
3. **Signal Group Settings:**
   - Group link toggle, "Require admin approval" toggle.
   - Clear distinction between group link management and member permissions.
4. **Slack Channel Settings vs Personal Notifications:**
   - Channel-wide topic/rules separated from "Your notifications for this channel".
5. **Flash Architecture (ADR-074 & ADR-076):**
   - Signed settings require admin signature (`rules.checkSettings`).
   - Non-admin members can inspect the rules in read-only mode so they understand the group policies, but cannot toggle them.
   - Device-local preferences are completely editable by any member.

---

## Existing approaches studied

### Approach A: Flat list of toggles
- Put all settings in one flat list: "Approval required", "Share invites", "Wi-Fi only", "Serve files".
- **Rejected:** Fails to communicate scope. A user toggling "Wi-Fi only" might expect everyone in the group to switch to Wi-Fi only, or a user might wonder why "Wi-Fi only" works without admin rights while "Approval required" requires admin rights.

### Approach B: Multi-tab layout (Tab 1: Group, Tab 2: This device)
- Two tabs in a tab bar inside the bottom sheet.
- **Rejected:** Excessive navigation overhead for ~8 total controls. Users miss device-local preferences hidden behind a second tab.

### Approach C: Two-section single sheet with scope headers (Flash Chosen Approach)
- A single vertically scrolling bottom sheet divided into two clearly captioned sections:
  1. **"Group rules"** with caption *"Signed by admins · Applies to all members"*. If current user is not an admin, toggles are disabled with an explanatory note *"Only group admins can change group rules"*.
  2. **"This device"** with caption *"Stored locally · Applies only to this device"*. Fully editable by all members.
  3. **"Group code & invites"** action section at the bottom: "Share invite link" (if permitted) and "Change group code" (admins only, with confirmation).
- **Selected:** Zero ambiguity about what is shared vs what is local; immediately intuitive for both admins and members.

---

## What worked

- **Scope headers:** Explicitly stating "Applies to all members" vs "Applies only to this device" eliminates support confusion.
- **Read-only state for non-admins:** Showing group rules in disabled state rather than completely hiding the section lets members see group policy (e.g. why their friend couldn't join without approval).
- **Explicit confirmation for "Change group code":** Rotating the secret invalidates all printed/copied invite links. A confirmation dialog prevents accidental rotations.
- **Pure math formatting helper:** `FlashGroupSettingsMath` handles label generation, permission checking, and validation cleanly outside Compose.

---

## What did not work

- **Exposing raw epoch numbers or secret hashes:** Protocol details like `epoch: 3`, `commit: 8f4a...` must never be displayed in user-facing settings.
- **Sliders for boolean flags:** Simple switches/toggles with standard Flash tokens are much clearer than sliders.
- **Unbounded capacity entry:** Text inputs for max members cause parsing errors; an increment/decrement stepper or bounded picker (2..50) prevents invalid settings.

---

## Chosen approach & why

A bottom sheet (`FlashGroupSettingsSheet`) launched from the group info sheet or three-dot menu:
- Uses `FlashSheetHost` matching UI-012/UI-029 styling.
- Section 1: **Group rules** (`joinPolicy`, `inviteSharers`, `membersMayAdd`, `swarmServing`, `maxMembers`).
- Section 2: **This device** (`serveToGroup`, `serveWifiOnly`, `batteryThresholdPercent`, `keepAvailableDays`).
- Section 3: **Invite & Group Code** (Invite link share button + "Change group code" button with `FlashConfirmDialog`).

---

## Visual specification

| Element | Token / value |
|---|---|
| Sheet container | `colors.backgroundSurface`, `radius24` top corners |
| Section header | `FlashTheme.typography.headingSmall`, `colors.textPrimary` |
| Section subtitle | `FlashTheme.typography.metadataDefault`, `colors.textSecondary` |
| Setting row | Handcrafted `Row`, min 56dp touch height, `space16` horizontal padding |
| Setting title | `FlashTheme.typography.bodyDefault`, `colors.textPrimary` |
| Setting description | `FlashTheme.typography.metadataDefault`, `colors.textSecondary` |
| Switch | Custom Flash toggle or themed switch, `colors.accentPrimary` active track |
| Admin badge | `colors.accentPrimary @ 12%` bg pill, `colors.accentPrimary` text |
| Stepper | Minus/Plus buttons with `colors.backgroundSurfaceSubtle` circular background |
| Danger action | `colors.textError`, `FlashIcons.Retry` or `FlashIcons.Close` |

---

## Interaction specification

- **Admins:** Tapping any group rule switch updates the local draft and invokes `onSaveSettings(updatedSettings)`.
- **Members:** Group rule switches are disabled; tapping shows tooltip / explanation "Only group admins can change group rules".
- **Local preferences:** Any user can toggle device-local switches immediately; calls `onUpdatePreferences(updatedPrefs)`.
- **Change group code:** Tapping triggers `showRotateConfirm = true`. Confirming calls `onChangeGroupCode()`.

---

## Accessibility requirements

- Every row provides a merged semantic node describing setting name, current state (On/Off), and whether it is disabled.
- Minimum 48dp touch targets on all interactive toggles and buttons.
- TalkBack announces section titles and scopes clearly.

---

## Testing checklist

- [x] Unit tests for `FlashGroupSettingsMath` (`FlashGroupSettingsMathTest.kt`):
  - `canEditGroupRules(isOwner, isAdmin)`
  - `clampMaxMembers(current, delta)`
  - `joinPolicyLabel(policy)`
  - `inviteSharersLabel(sharers)`
  - `preferenceDescriptions`
- [x] Compose UI implementation in `FlashGroupSettingsSheet.kt`
- [x] Integrated into `FlashConversationScreen.kt`, `MainActivity.kt`, and `DesktopShell.kt`
- [ ] Physical device verification (tracked in `docs/testing/TEST-BACKLOG.md` §4v: `GSET-01`..`03`)
