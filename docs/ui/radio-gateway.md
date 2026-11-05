# Component Research & Design Document ‚Äî Radio Gateway (UI-070..UI-074)

**Status:** DESIGNED (2026-10-16) ¬∑ **visual redesign v2 IMPLEMENTED 2026-10-28** ¬∑ **visual redesign v3 IMPLEMENTED
and merged into `dev` as `e76edc71` (pushed as dev `d77b8261`) 2026-11-05; device checks owed** (see ‚ÄúRedesign v3‚Äù at the end; v2 is superseded by v3, the behaviour sections below still hold)  
**Component IDs:**  
- **UI-070**: Radio gateway settings entry, checklist, and overview  
- **UI-071**: Find my radio: candidate detection, live nearby scan list, in-app pairing  
- **UI-072**: Security profile selector and one-time regulatory authorisation sheet  
- **UI-073**: Connection status chip and diagnostic details  
- **UI-074**: Operational mode selector (Field Companion, Relay, Radar, All-in-One)  
**Last updated:** 2026-10-16  
**Owner phase:** Plan R (Revision 3, `docs/plans/RADIO-GATEWAY-PLAN.md` ¬ß6, ¬ß8; ADR-173, ADR-178, ADR-182, ADR-183)

---

## Component

`FlashRadioGatewayScreen` and its subcomponents:
- `FlashRadioOverviewSection` (UI-070): Settings entry "Radio gateway (beta)", on/off switch, hardware readiness checklist, remembered radio card with "Forget", candidate confirmation banner ("Use <name>?").
- `FlashRadioFindSection` (UI-071): "Find my radio" panel, live Bluetooth discovery list, radio candidate ranking cards (VR-N76 / UV-Pro on top), in-app "Pair" action, 12-second scan countdown timer with "Stop scan", and "Open Bluetooth settings" fallback.
- `FlashRadioSecuritySection` & `FlashRadioAuthorisationDialog` (UI-072): Profile M (tactical military encryption) as primary default; one-time legal authorisation confirmation modal requiring explicit timestamped agreement before Profile M can be selected; Profile A (civilian amateur signed plaintext) fallback.
- `FlashRadioStatusRow` & `FlashRadioStatusChip` (UI-073): Real-time connection badge with single-sentence status description and actionable next step.
- `FlashRadioModeSelector` (UI-074): Mode cards for Mode 1 (Field Companion), Mode 2 (Base Station Relay), Mode 4 (Tactical Radar), and Mode 5 (All-in-One). Modes without engine implementation are non-selectable and badged "Coming later" (no dead buttons). Mode 3 (Voice Bridge) is hidden pending audio spike.
- `FlashRadioTestMessageCard`: Diagnostic single-frame transmission with explicit high-visibility RF transmit warning.

---

## Purpose

Provides a unified, responsive interface on Android and Windows Desktop for connecting Flash to handheld transceivers (Vero Telecom VR-N76, BTECH UV-Pro) with built-in KISS TNC over Bluetooth SPP / RFCOMM.

In off-grid, tactical, or emergency scenarios lacking cellular towers, Wi-Fi infrastructure, or LAN connectivity, this interface allows operators to discover and pair a radio, establish an AX.25 KISS data link, select the required cryptographic security profile, and transmit/receive text messages and delivery receipts over VHF/UHF RF channels (1200 baud Bell 202 AFSK).

---

## Research Sources

1. **Project Blueprints & Decisions:**
   - `docs/plans/RADIO-GATEWAY-PLAN.md` (Revision 3): Sections 1, 2, 5, 6, 7, 8, 10, 11, 12.
   - `docs/decisions.md`: ADR-173 (amended), ADR-178 (state model & Rule 4), ADR-182 (Windows Win32 JNA + registry mapping), ADR-183 (Android in-app scan & pairing).
   - `docs/network/RADIO-WIRE-FORMAT.md`: Frame sizes, 220-byte MTU, Profile M AES-GCM vs Profile A AX.25 cleartext.
   - `docs/reports/2026-10-09-bluetooth-radio.md` & `docs/ui/radio-link-test.md`: BT-00 findings.
   - `logs/streams/R-spike/runbook-BT-R11.md`: Device verification on Windows 11 and Android handset with VR-N76.
2. **Platform & Standards Documentation:**
   - Android Developer Guide: *Bluetooth permissions* (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `usesPermissionFlags="neverForLocation"`, API 31+ runtime permissions, API 24-30 location requirement).
   - Microsoft Learn: *Win32 Bluetooth APIs* (`BluetoothFindFirstDevice`, `BluetoothAuthenticateDeviceEx`, `BluetoothSetServiceState`).
   - Material 3 & Expressive Guidelines: Infrastructure semantics, accessible touch targets (48dp), keyboard focus navigation.
3. **Domain & Field Applications Studied:**
   - *APRSdroid*: Android amateur radio APRS client (TNC Bluetooth connection, callsign configuration, KISS frame logging).
   - *Mobilinkd TNC Config*: Peripheral configuration tool (battery, audio levels, KISS parameters, Bluetooth pairing).
   - *Meshtastic*: LoRa mesh client (BLE scan list, channel configuration, encryption keys, radio preset modes).
   - *Winlink Express / Soundmodem*: Desktop packet radio interface (COM port selection, PTT control, strict regulatory callsign prompts).
4. **Adaptive UI Authority:**
   - `docs/migration/ADAPTIVE-UI-PLAN.md` and `docs/ui/responsive-layout.md`: Breakpoints (Compact <600dp, Medium 600‚Äì839dp, Expanded ‚â•840dp), desktop padding and typography scaling.

---

## Existing Approaches Studied

### Approach 1: Multi-Step Linear Wizard (Pager / Dialog Flow)
- **Description:** A step-by-step wizard presenting: Step 1 (Checklist) $\rightarrow$ Step 2 (Scan Devices) $\rightarrow$ Step 3 (Pairing) $\rightarrow$ Step 4 (Profile Selection) $\rightarrow$ Step 5 (Connection Active).
- **Pros:** Guides a novice user through initial setup sequentially.
- **Cons:** Rigid and frustrating for experienced radio operators who toggle power or switch channels frequently in the field. When a link drops or the radio battery dies, navigating backwards through 4 screens to trigger a reconnect is unacceptable. On wide desktop screens, modal wizards leave vast horizontal empty space.

### Approach 2: System Settings Delegation (OS-Only Scan & Pair)
- **Description:** Flash displays only a dropdown of already-paired OS devices. For new radios, it displays an "Open OS Bluetooth Settings" button and relies on the user pairing in Android Settings or Windows Settings before returning to Flash.
- **Pros:** Minimal application code; avoids managing Bluetooth scanning permissions.
- **Cons:** Specifically rejected by the project owner (Plan R Rev 3 ¬ß6, ADR-183). Breaks workflow immersion, provides no RF-specific candidate ranking (e.g., distinguishing a VR-N76 from an office printer or headphones), gives no in-app feedback when pairing fails, and creates friction during emergency field deployment.

### Approach 3: Unified Reactive Card Deck with Adaptive Split (Chosen)
- **Description:** A cohesive, single-screen dashboard organized into clear, accessible card blocks:
  - Header: Global on/off master switch and dynamic status chip (UI-070, UI-073).
  - Contextual Checklist: Expandable "Is your radio ready?" checklist (UI-070).
  - Quick Confirmation: Single-candidate preselection prompt ("Use VR-N76?") when one known radio is bonded (UI-070).
  - In-App Scanner: Collapsible "Find my radio" panel with live ranked scanning and direct "Pair" actions (UI-071).
  - Active Peripheral Card: Remembered radio details with RSSI / port hints and "Forget" action (UI-070).
  - Security Profile Panel: Encrypted Profile M (primary) vs Civilian Profile A, with modal legal authorisation confirmation (UI-072).
  - Operational Mode Grid: Modes 1, 2, 4, 5 with status badges; unbuilt modes marked "Coming later" without dead actions (UI-074).
  - Diagnostics: Dedicated "Send a test message" card with high-contrast transmit warning (UI-070).
- **Pros:** High situational awareness; allows immediate re-connection, status verification, and profile adjustments without losing context. Seamlessly adapts from single-column mobile to two-column desktop/tablet layout.

---

## What Worked

1. **Card Deck Hierarchy:** Grouping related hardware, scanning, security, and mode controls into distinct elevated cards prevents visual clutter while keeping every parameter reachable within one scroll gesture.
2. **Device Ranking Badges:** Color-coded chip badges (Rank 1 `RADIO`, Rank 2 `SERIAL SPP`, `OTHER`) in the scan list immediately draw the operator's eye to compatible transceivers.
3. **Explicit Regulatory Confirmation:** Separating the legal authorization prompt into a dedicated dialog (with timestamped record) makes compliance explicit without confusing civilian amateur operators or military teams.
4. **Honest "Coming Later" Indicators:** Rendering unbuilt modes as disabled informational cards with clear "Coming later" labels guarantees zero "dead clicks" (meeting quality contract ¬ß3.9).
5. **Prominent Transmit Warning Banner:** Placing a bold warning icon and caution text above the "Send test message" action eliminates accidental RF transmissions (complying with Rule 4).

---

## What Did Not Work

1. **Automatic RF Probing:** Attempting to ping the radio to verify connection transmits RF immediately and keys the amplifier. Liveness must rely strictly on socket state and passive observation.
2. **Modal Pagers on Desktop:** Trapping desktop users in small wizard popups felt unnatural on Windows 11; desktop requires side-by-side visibility of connection status and security configuration.
3. **Hidden / Silent Permission Requests:** Prompting for "Nearby devices" at app launch caused user confusion. Moving the request strictly to the "Scan" action at point-of-use with a clear one-sentence rationale resolved this.

---

## Chosen Approach

Adopt **Approach 3: Unified Reactive Card Deck with Adaptive Split**.

On mobile phones (Compact width <600dp), all cards scroll in a single vertical stream.  
On tablets and desktop windows (Medium ‚â•600dp and Expanded ‚â•840dp), the layout splits into a two-column adaptive grid:
- **Left Column (Hardware & Transport):** Master switch, Readiness Checklist, Candidate Confirmation, Remembered Radio, Connection Status, and "Find my radio" scan list.
- **Right Column (Security, Modes & Diagnostics):** Security Profile selector & Authorisation state, Mode Selector (Modes 1, 2, 4, 5), and "Send a test message" diagnostics.

---

## Why It Was Chosen

1. **Aligns with Plan R Revision 3:** Implements all requirements of ¬ß6 ("Find, pair and connect") and ¬ß8 ("Security and regulation").
2. **Honours Owner Decisions:** Adopts ADR-173 (Profile M default with authorisation gate), ADR-182 (Windows JNA/registry workflow), and ADR-183 (Android in-app scan list).
3. **Complies with Rule 4:** Ensures zero silent transmissions; all RF emission requires deliberate, warned user actions.
4. **Complies with AGENTS.md ¬ß34:** Uses 100% Flash design system tokens (`FlashTheme`, `FlashColors`, `FlashTypography`, `FlashShapes`, `FlashSpacing`, `FlashMotion`, `FlashIcons`). No generic Material 3 controls.

---

## Visual Specification

### Color Tokens (`FlashTheme.colors`)
- **Background:** `backgroundSurface` (`#0D1117` in dark, `#F6F8FA` in light).
- **Cards & Surfaces:** `surface` / `surfaceElevated` (`#161B22` in dark, `#FFFFFF` in light).
- **Borders & Dividers:** `cardBorder` (`#30363D` in dark, `#E1E4E8` in light).
- **Text:**
  - Primary: `textPrimary` (`#F0F6FC` dark, `#1F2328` light).
  - Secondary: `textSecondary` (`#8B949E` dark, `#656D76` light).
  - Tertiary / Muted: `textTertiary` (`#484F58` dark, `#8C959F` light).
- **Accent:** `accent` (`#2F81F7` or dynamic brand tint).
- **Status Tones:**
  - `CONNECTED` / Success: `#3FB950` (green).
  - `CONNECTING` / `PAIRING` / Progress: `#D29922` (amber) with animated indicator.
  - `FAILED` / Error: `#F85149` (red).
  - `OFF` / Disconnected: `#8B949E` (neutral grey).
- **RF Transmit Warning:** Warning container with `#F0883E` tint and high-contrast text.

### Typography (`FlashTheme.typography`)
- Screen Title: `titleLarge` (22sp, Bold).
- Section / Card Header: `titleMedium` (16sp, SemiBold).
- Body / Explanations: `bodyDefault` (14sp, Regular).
- Secondary / Captions: `bodySmall` (12sp, Regular).
- Badges / Chips: `metadataDefault` (11sp, Medium).
- Button Labels: `labelBold` (14sp, Medium).

### Spacing & Metrics (`FlashSpacing`)
- Screen outer margin: `FlashSpacing.space16` (16dp mobile, 24dp desktop).
- Card padding: `FlashSpacing.space16` (16dp).
- Item spacing within cards: `FlashSpacing.space12` (12dp).
- Chip & small badge padding: horizontal `FlashSpacing.space8`, vertical `FlashSpacing.space4`.
- Minimum Touch Target: 48dp on Android touch screens; 36dp on Desktop mouse/keyboard environments.

---

## State Flow & Error Sentences (Plan R ¬ß6 Flow)

Every state presents **one plain sentence** and **one actionable next step**:

| State | Status Sentence (`sentence`) | Next Step / Subtitle (`nextStep`) | Actions Enabled |
|---|---|---|---|
| `OFF` | "Radio gateway is off." | "Turn on to discover or connect to your radio." | Enable switch |
| `CHECKLIST` | "Radio gateway is on." | "Make sure your radio is on and KISS TNC is enabled." | Find radio, Toggle off |
| `DETECTING` | "Looking for paired radios‚Ä¶" | "Checking known devices." | Cancel, Stop |
| `CONFIRM_CANDIDATE` | "Found <Radio Name>." | "Use this radio for your gateway?" | Confirm, Scan other |
| `SCANNING` | "Scanning for nearby radios‚Ä¶" | "Make sure your radio is discoverable." | Stop scan, Open BT settings |
| `PAIRING` | "Pairing with <Radio Name>‚Ä¶" | "Confirm the pairing request on your screen if prompted." | Cancel |
| `CONNECTING` | "Connecting to <Radio Name>‚Ä¶" | "Opening KISS TNC serial link." | Cancel |
| `CONNECTED` | "Connected to <Radio Name>." | "Ready to send and receive radio chat." | Disconnect, Forget, Test Msg |
| `LINK_LOST` | "Radio disconnected, trying again‚Ä¶" | "Keeping link open. Check radio battery and range." | Reconnect now, Forget |
| `NO_ADAPTER` | "No Bluetooth adapter found." | "This device cannot connect to Bluetooth radios." | Settings, Toggle off |
| `BLUETOOTH_OFF` | "Bluetooth is turned off." | "Turn Bluetooth on in system settings to connect." | Open BT settings |
| `PERMISSION_MISSING` | "Nearby devices permission required." | "Allow Flash to find nearby radios in settings." | Request perm, Open settings |
| `RADIO_NOT_FOUND` | "No compatible radio was found." | "Verify KISS TNC is enabled in the radio menu and try again." | Scan again, Checklist |
| `PAIRING_REFUSED` | "Pairing was refused or timed out." | "Put the radio into pairing mode and try again." | Retry pair, BT settings |
| `PORT_IN_USE` | "Serial port is in use by another program."| "Close other radio software and tap Reconnect." | Reconnect, BT settings |
| `KISS_NOT_ENABLED` | "Connected, but no KISS data received." | "In the radio menu: Menu -> General Settings -> KISS TNC -> Enable." | Checklist, Reconnect |

---

## Interaction Specification

1. **Master Switch Toggle:**
   - Toggling ON initiates automatic paired candidate detection. If exactly one candidate matches, enters `CONFIRM_CANDIDATE`. If none, expands the checklist and scan section.
   - Toggling OFF immediately aborts any active scan, disconnects the link, and resets state to `OFF`.
2. **Candidate Confirmation ("Use <name>?"):**
   - Clicking "Use <name>" records the radio's Bluetooth address as remembered and attempts connection.
   - Clicking "Different radio" opens the live scan section.
3. **Live Scan ("Find my radio"):**
   - Clicking "Scan" checks permissions. If granted, begins inquiry scan and starts a 12-second countdown timer.
   - Clicking "Stop (Xs)" halts inquiry immediately.
   - Leaving the screen automatically calls `stopScan()` to preserve battery and cancel background receivers.
4. **Pairing Trigger:**
   - Clicking "Pair" on a candidate triggers in-app bonding (`createBond()` on Android, `BluetoothAuthenticateDevice` on Windows).
   - If OS prompts for numeric comparison or PIN, user completes overlay; Flash reflects progress via `PAIRING` state.
5. **Security Profile Selection & Authorisation:**
   - Profile M (Tactical / Military Encrypted) is the default.
   - If not yet legally authorized, tapping Profile M displays the Authorisation Dialog.
   - Authorisation Dialog contains the exact statutory acknowledgement:
     *"I confirm that I am authorised to transmit encrypted traffic on the frequency in use."*
   - Clicking "Confirm Authorisation" records the setting with a positive epoch timestamp and activates Profile M.
   - Clicking "Cancel" leaves Profile A (Signed Cleartext with Callsign) active.
6. **Diagnostic Test Message:**
   - Clicking "Send a test message" displays a high-visibility warning modal:
     *"Warning: Transmits RF on air. Ensure your antenna or dummy load is attached and frequency is clear."*
   - Clicking "Transmit Now" triggers `sendTestMessage()`.

---

## Animation & Motion (`FlashMotion`)

- **State Transitions:** Screen layout changes (expanding scan list, switching between checklist and active radio) use `animateContentSize(animationSpec = FlashMotion.springBouncy)` or smooth cross-fades (`fadeIn(FlashMotion.fast)` + `fadeOut(FlashMotion.fast)`).
- **Scan Timer Indicator:** Pulse animation on the scan indicator using `infiniteRepeatable`.
- **Reduced Motion (`FlashPerformanceMode.reduceMotion` or system setting):**
  When reduced motion is enabled, all animations snap instantly (`snap()`), and spinners use non-moving status dots.

---

## Accessibility Requirements (TalkBack & Keyboard)

1. **Semantic Content Descriptions:**
   - Every icon button has an explicit, copy-backed `contentDescription` (e.g. `FlashRadioCopy.A11Y_STOP_SCAN`, `A11Y_FORGET_RADIO`).
   - Radio status badge has `stateDescription` describing the link condition.
2. **Touch Targets:**
   - Minimum 48dp on Android handsets and tablets; minimum 36dp with clear focus outline on desktop.
3. **Keyboard Focus Order (Desktop):**
   - Focus traversal order: Master Switch $\rightarrow$ Candidate/Remembered actions $\rightarrow$ Scan controls $\rightarrow$ Discovered list $\rightarrow$ Security profile toggle $\rightarrow$ Mode cards $\rightarrow$ Test button.
   - All interactive cards support `Enter` and `Space` to activate.
4. **Large Text & Font Scaling:**
   - Tested up to 200% font scale. Text rows wrap vertically without clipping. Containers use flexible height (`wrapContentHeight`).

---

## Responsive Layout (ADAPTIVE-UI-PLAN Compliance)

- **Compact (<600dp, Phone):**
  Single-column `LazyColumn` with outer padding `16.dp`. Cards stack vertically in logical order: Overview $\rightarrow$ Active/Candidate $\rightarrow$ Scan List $\rightarrow$ Security $\rightarrow$ Modes $\rightarrow$ Diagnostics.
- **Medium (600dp‚Äì839dp, Tablet / Foldable):**
  Two-column layout with 20dp padding. Left column: Hardware & Connection (Cards 1‚Äì3). Right column: Security, Modes, Diagnostics (Cards 4‚Äì6).
- **Expanded (‚â•840dp, Desktop Windows):**
  Two-column layout constrained to maximum content width 1100dp, centered horizontally. Desktop keyboard shortcuts supported (e.g. `Esc` to close scan/sheets).

---

## Dark Mode Behavior

- Designed dark-first with tactical aesthetics: deep slate backgrounds (`#0D1117`), dark elevation cards (`#161B22`), crisp hairline borders (`#30363D`), and glowing accent indicators.
- Light mode uses crisp off-white canvas (`#F6F8FA`), pure white card surfaces (`#FFFFFF`), subtle cool-grey borders (`#E1E4E8`), and darkened text tokens for high readability outdoors.

---

## What Makes This Flash?

Flash is not a generic consumer chat app or an ad-hoc clone of APRS tools. The Radio Gateway interface embodies Flash's tactical core: high contrast, zero unnecessary visual noise, complete transparency about what the radio is doing, strict enforcement of regulatory authorization before encryption, and an uncompromising safety guarantee that hardware RF transmitters are never keyed silently.

---

## Redesign v2 ‚Äî ‚Äúone station plate, then calm rows‚Äù (2026-10-28, IMPLEMENTED)

### Why

The owner reported the page ‚Äúlooks weird‚Äù. Reading the implementation explained it: the screen was **eight
outlined boxes stacked** (master switch, status, permission, candidate, remembered radio, voice, checklist,
find/scan, then security, modes, tests), every one the same `backgroundSurface` fill with the same 1 dp
`borderDefault`, every one titled with `headingMedium` ‚Äî no hierarchy at all, so the page read like a
wireframe form. On top of that:

- status dots and permission/warning colours were **hardcoded** (`0xFF3FB950`, `0xFFD29922`, `0xFFF85149`,
  `0xFFF0883E`) instead of the theme‚Äôs status tokens, so they clashed with the palette;
- the controls were **stock Material**: `Switch` (twice), `AlertDialog` (twice), `OutlinedTextField`,
  `CircularProgressIndicator`, `TextButton`;
- the dialogs used raw `AlertDialog`, which on Compose Desktop renders through `DialogWindow` ‚Äî the
  separate native window that `FlashSheetHost`‚Äôs KDoc records as the ‚Äúsheets open somewhere else‚Äù bug;
- the desktop layout was **two columns of very unequal length** (left long, right short) inside a 1100 dp
  cap, so a wide window showed a large empty area, and the 56 dp title bar with a back chevron duplicated
  the shell‚Äôs own navigation;
- the same facts were said three times (title, switch subtitle, status sentence) and the voice card sat
  between the remembered radio and the scan list, pushing ‚ÄúFind my radio‚Äù below the fold.

### The rule the redesign follows

> Borders and fills are **information**, not decoration. A thing is a box only when it is (a) the station
> itself or (b) something that needs an answer right now. Everything else is a section label and rows.

So the page now uses the **Hub tab‚Äôs language** (UI-058) rather than inventing a second one: `HubShapes`
(16 dp card, 12 dp control, 6 dp chip), `hubCapsStyle()` section labels with a hairline running to the edge,
`hubRuleBelow` hairlines, `FlashHubSwitch`, `FlashHubButton`, `FlashHubTextAction`, `FlashHubChip`,
`HubLiveDot`, `HubTone` (so every colour is a token: `textSuccess`, `accentSecondary`, `textError`,
`accentPrimary`) and `hubClickable` for the Flash press response. No new primitives, no second visual system.

### Layout

One column at **every** size, capped at **720 dp and centred** (`widthIn(max = 720.dp)` on the list itself,
header content capped the same way). This replaces both the old phone stack and the old two-column desktop
split: no lopsided column and no 1100 dp cap leaving a void. Rows breathe (20 dp between sections, 16 dp page
padding), which is what makes the page look ‚Äúdesigned‚Äù instead of stacked.

Order, top to bottom:

1. **Header** ‚Äî back chevron + ‚ÄúRadio gateway (beta)‚Äù (the chevron stays on desktop too: the demo host uses
   it to leave the page; the shell‚Äôs own navigation is untouched).
2. **Station plate** (`FlashRadioStationBlock`) ‚Äî the *only* hero: a mark in the state‚Äôs tone, the radio‚Äôs
   name (or ‚ÄúNo radio set up yet‚Äù), the live state dot and sentence, the next step, and the master switch at
   the right edge of the same row. When the gateway is on, the plate carries a 1 dp tone-tinted edge and a
   `hubHeroBrush` wash; when it is off it is a flat `backgroundSurfaceSubtle`. Reconnect appears here (as a
   quiet `FlashHubButton`) because it is the one action a broken link needs.
3. **Permission banner** / **candidate banner** ‚Äî full-width *strips* (tone wash + hairline under), never
   boxes: the two things that genuinely need an answer. The candidate strip holds ‚ÄúUse this radio‚Äù plus a
   ‚ÄúDifferent radio‚Äù text action.
4. **This link** ‚Äî plain fact rows (radio, address, profile, callsign) with a `Forget` action row. Replaces
   the old ‚ÄúConnected radio‚Äù card.
5. **Set up your radio** ‚Äî the three readiness steps as numbered rows (no card), shown while nothing is set
   up, and while the gateway is off (so the page is never blank).
6. **Nearby radios** ‚Äî the scan action sits in the section header (‚ÄúScan for radios‚Äù / ‚ÄúStop (12 s)‚Äù), the
   discovered devices are rows with a category chip and a `Pair` / `Use this radio` action, and the battery
   note is one tertiary line at the end.
7. **Security profile** ‚Äî two selection rows with a ring mark, subtitle and the `Authorised` /
   `Required for military` chip. Profile M still routes to the authorisation sheet when not yet authorised.
8. **Operational mode** ‚Äî selection rows with `Active` / `Coming later` chips.
9. **Radio voice** ‚Äî the same content (listen switch, phase sentence, level bar, hold-to-talk, callsign
   note) as section rows instead of a card.
10. **Radio diagnostics** ‚Äî a single row: ‚ÄúSend a test message‚Äù with a `Send` text action and, when the
    button is unavailable, the existing ‚Äúneeds a live link‚Äù sentence as tertiary text.

### Dialogs

Both keep their exact behaviour and validation; the frame changes from `AlertDialog` to the house
`FlashConfirmHost` seam (real M3 dialog on Android, in-window overlay on desktop ‚Äî no separate native
window), with a Flash text field (hairline box, `composerInputBackground` fill) and `FlashHubSwitch` for the
acknowledgement. The RF-transmission warning uses the theme‚Äôs `accentSecondary` instead of `0xFFF0883E`.

### What did **not** change

Every `RadioGatewayController` call, every state field, every sentence in `FlashRadioCopy`, the
`FlashRadioAction` table (RADUI-U04), the authorisation and transmit-warning gates, and the plan‚Äôs
constraints (`FlashBackHandler`, `stopScan()` + `setVoiceListening(false)` on dispose, the `bottomInset`
padding). `FlashRadioMath` is untouched, so all its unit tests still describe the page‚Äôs behaviour. This was
purely visual, as the owner asked.

### Verification

`:ui:chat:compileKotlinJvm` (and the Android host compile) plus the radio tests in `:ui:chat:jvmTest`
(`FlashRadioNoDeadActionsTest`, `FlashRadioCopyA11ySourceTest`, `FlashRadioMathTest`,
`RadioGatewayUiMapperTest`, `FlashRadioSettingsEntryTest`). **Not device-verified**: `RADUI-DEV01` in
`docs/testing/TEST-BACKLOG.md` covers the phone and desktop walk-through.

---

## Redesign v3 ó ìyour radios, then the talk barî (2026-11-05, DESIGNED)

**Status:** DESIGNED. No Compose code changed yet in this round; the HTML design review lives in
`design/radio-v3/`.

### Why (the owner's brief, verbatim in spirit)

> When the radio is turned on it should show paired devices and ask whether to pair, or a button to pair a new
> device. Clicking that opens a dialog and starts scanning. Security profile and operational mode belong in
> settings. Find an intuitive place for hold-to-talk.

That is the owner's read of the page after v2, and it is right about one thing v2 did not solve: v2 answered
ìwhat is the state of the gateway?î but never answered **ìwhich radios do I have, and how do I add one?î** It
also kept five peer sections (security, mode, voice, diagnostics, checklist) on the same scroll as the hardware,
so the page was still a list of features rather than a device page.

### The question this screen answers

> **ìCan I talk on this radio right now ó and if not, what is the one thing I do?î**

One finding: the link state and the next step, in the station plate. Everything else on the page is either a
radio you own or a place to add one.

### The idea

> On this page, **the handheld's own front panel** becomes the page's device: one station plate that carries the
> link state, and the **push-to-talk bar** where a handheld's PTT sits ó under the thumb, always in reach.

The competitor test holds: a generic Bluetooth-settings page would list adapters; a generic device page would not
put a keyed-transmitter control at the bottom edge with a level meter beside it.

### Art direction

Dealt with `draw.mjs --app --kind devtools --screens settings,detail --theme dark --keep palette,type`
(**`true-black-flat`**, seed 363242947), then adapted:

- **Kept** ó elevation by lightness alone (no borders or shadows on the station plate: `backgroundSurface` on
  `backgroundApp`), the label going to full ink when selected, and colour confined to data/status plus **one**
  control per state.
- **Redrawn (stated reason)** ó the sheet's *shell* (210ñ240 dp sidebar + 64 dp top bar) does not apply: this is
  a sub-screen inside the existing Flash shell on both hosts, so the kept shell is the app's own header row.
  The sheet's *settings-with-usage* screen pattern does not apply either (there is no usage to report); the
  settings surface is the app's own `FlashSheetHost` sheet.
- **The face taken from the brief** ó the radio's *name and address* are the identity, set at 20/600 in ink with
  the address in tabular digits, and the state dot carries the only hue in the plate.
- **The signature element** ó the **talk bar** (see ìHold-to-talkî).

### Composition (one column, 720 dp cap, centred; 16 dp gutters on a phone, 24 dp on desktop)

| # | Block | Answers |
|---|---|---|
| 1 | Header: back, ìRadio gatewayî, settings gear | where am I, where are the less-used controls |
| 2 | **Station plate** ó name / ìNo radio pairedî, state dot + sentence, facts line (Bluetooth ∑ address ∑ profile), master switch, ìReconnectî when the link is down | can I talk now, and what is wrong |
| 3 | **Attention strip** ó permission (only when blocked) or ìPaired radio found ó use this radio?î (only when a candidate exists) | the two things that need an answer |
| 4 | **Your radios** ó paired radios as rows (active one carries a `Connected` chip; the others a `Use` action), then the one filled primary: **Pair a new radio** | which radios do I own, how do I add one |
| 5 | **Radio not showing up?** ó a disclosure with the three readiness steps | the radio is not in the list |
| 6 | **Talk bar** (docked, only when a radio is remembered and the host has audio) | how do I talk |

`Security profile`, `Operational mode`, `Send a test message` and `Forget this radio` move into the **settings
sheet** (block 1's gear). The checklist is no longer the empty state: the empty state is ìNo radio pairedî plus
the primary action, and the checklist is where a person looks when the scan found nothing.

### Why the talk bar is a dock

The PTT is the page's most frequent physical action and the only one a person uses without looking. Three
placements were considered:

1. **In the voice section, below the fold** (v2): the operator scrolls to talk; wrong for a device you key with a
   thumb while watching the road. Rejected.
2. **A floating circle above the bottom nav**: covers list content, and a circular PTT on a page with a bottom
   nav reads as a call button. Rejected.
3. **A dock across the bottom edge, above the shell's bottom bar** (chosen): stable position at every scroll
   offset, 16 dp gutters, one row ó listen toggle, level meter, hold-to-talk ó so the control sits where the
   thumb rests. It only exists when a radio is remembered and the host has audio, so it is never a dead control.

The dock is not a second hero: it is `backgroundSurfaceStrong`, one tone above the plate, with the PTT as the
only filled teal control on the page while the link is up. The station plate's primary (Pair) is only filled when
there is no radio, so **one filled control is visible per state**.

### Pair sheet (block 4's primary)

`FlashSheetHost` sheet, titled **Pair a radio**:

- opens with `controller.startScan()` (the existing action ó no new controller method);
- scanning row: a pulsing dot, ìScanning for nearby radiosÖî, the countdown as `Stop (9 s)` (existing copy);
- device rows: mark, name, `RADIO / SERIAL SPP / OTHER` chip + dBm, trailing `Pair` / `Use this radio`;
- empty: the existing ìNo nearby devices found yetÖî sentence;
- footer: ìScanning uses extra batteryÖî plus `Open Bluetooth settings`;
- permission missing: the permission strip replaces the list and `Allow Nearby devices` starts the request;
- dismissing the sheet calls `controller.stopScan()`, so closing it never leaves a scan running.

The sheet is the ìpair or scan?î answer the owner asked for: **the page offers the button; the sheet does the
scanning.**

### Settings sheet

Titled **Radio settings**, three groups, in this order:

1. **Security profile** ó the same two choice rows and the `Authorised` / `Authorisation needed` chip; tapping
   Profile M without authorisation still opens the authorisation sheet.
2. **Operational mode** ó the same four rows with `Active` / `Coming later`.
3. **This radio** ó name, address, callsign facts; then **Send a test message** (RF warning sheet unchanged) and
   **Forget this radio** as the last, destructive text action with its consequence in one sentence.

Nothing is hidden: every control that was on the v2 page is one tap away, and the page keeps a `Radio settings`
entry point in the header at all sizes.

### Colour, type, spacing (all from tokens; no literals)

- Ground `backgroundApp`, plate `backgroundSurface`, dock `backgroundSurfaceStrong`, sheet `backgroundSurface`;
  hairlines `borderSubtle` between settings rows only; the paired-radio rows use spacing, not rules.
- Hue: `statusOnline`-family success for ìConnectedî, `accentSecondary` for permission/attention, `textError` for
  a keyed-transmitter failure or Forget, `accentPrimary` on exactly one filled control.
- Type: page title 18/600; plate name 20/600; section labels 13/600 `textSecondary` (sentence case ó the v2
  upper-case tracked labels are gone: they were the strongest ìwireframeî tell); body 16/22; sublines 12ñ13/16ñ18.
- Spacing: 4 dp base, 16 dp page gutters (24 on desktop), 28 dp between blocks, 64 dp minimum row height on
  touch.

### Motion (feedback only; no entrances)

- Press: the PTT and every row scale to 0.98 and shift to their pressed tone (existing `hubClickable`).
- The dock appears the first time a link is up and slides 24 dp up with a 180 ms ease-out (transform + opacity
  only); it never animates its height.
- The pair sheet and settings sheet use the app's sheet transition; the scanning dot pulses only while scanning.
- `reduceMotion` (or `FlashTheme.motion.reduceMotion`): the slide and the pulse become a plain cut/static dot.

### Accessibility

- The PTT is a `Role.Button` with `contentDescription` and a `stateDescription` of ìTalkingÖî while held; it has
  a 56 dp height so it is reachable one-handed.
- The state dot is never colour alone: the state word sits next to it.
- The paired-radio row's trailing action has `Pair with <name>` / `Use <name> as the radio` labels (existing
  `FlashRadioCopy.a11y*` helpers).
- Focus order follows the visual order: back ? settings ? switch ? attention action ? paired rows ? pair ?
  disclosure ? dock (listen ? talk).
- The sheet traps focus on desktop (its host already does) and restores it to the gear / pair button.

### What does not change

`RadioGatewayController` (no new methods), `RadioGatewayUiState`, `FlashRadioMath`, the authorisation gate, the
RF transmit warning, `FlashBackHandler`, the dispose behaviour (`stopScan()` + `setVoiceListening(false)`) and
the `bottomInset` contract. `FakeRadioGatewayController` gains only a **demo-only** `simulateVoice()` helper so the
debug demo and previews can show the dock; no product path gains logic.

### States this design must render

`OFF`, `ON + no radio`, `ON + paired + connected`, `LINK_LOST`, `SCANNING (sheet)`, `PERMISSION_MISSING`,
`CONFIRMING_CANDIDATE`, `SETTINGS (sheet)`, plus the voice dock in its three states (off, listening, transmitting).

### Verification (planned)

HTML review page inspected at 390 / 768 / 1440 (`lint.mjs`, `inspect.mjs`), then Compose: `:ui:chat:jvmTest`
radio suites (no dead actions, a11y source scan, math, mapper, settings entry) and both hosts compile. Device
checks: `RADUI-DEV02` (phone walk-through) and `RADUI-DEV03` (desktop) in `docs/testing/TEST-BACKLOG.md`.

---

## Redesign v3 ó built (2026-11-05)

**Status: BUILT, unit-tested on both hosts, and MERGED into `dev` as `e76edc71` (pushed as dev `d77b8261`); NOT
device-verified.** Branch
`design/radio-gateway-v3` in `E:\FlashWork\radio-ui-v3`. The review page is `design/radio-v3/`
(`node design/radio-v3/serve.mjs 5191`); its README lists the state URLs.

### Files

| File | Change |
|---|---|
| `FlashRadioGatewayScreen.kt` | rewritten: header with the settings gear, station plate, strips, radios, readiness, link facts, and the audio panel (in the flow on a wide window, docked above the shell bar below 900 dp) |
| `FlashRadioGatewayComponents.kt` | rewritten: station plate, strips, radio rows, readiness, link facts, pair sheet, settings sheet, the two existing dialogs |
| `FlashRadioVoiceCard.kt` ? `FlashRadioAudioPanel.kt` | `FlashRadioVoiceSection` became `FlashRadioAudioPanel` (listen switch, five-bar level, hold-to-talk, licence note); the file was renamed because it is no longer a card in the page flow |
| `FlashRadioCopy.kt` | new sentences for the page, the sheets and the talk bar; `SCREEN_TITLE` is now "Radio gateway" (the "Beta" chip carries the maturity marker) |
| `FlashRadioMath.kt` | three pure helpers: `modeTitle`, `modeSubtitle`, `scanStatusText` (no decision changed) |
| `FakeRadioGatewayController.kt` | demo-only voice support (`simulateVoice`, and the three voice actions) so previews and the debug demo can show the talk bar |
| `RadioGatewayDemoActivity.kt` (debug) | opens on a connected VR-N76 with UV-Pro paired and the audio listening, so the owner can see the designed states without a radio |

### Critique round (Impeccable, two isolated assessments) and what it changed

Assessment A (design review, screenshots at 390/768/1440 for eight states) scored the design **26/40**, with
its strongest criticism aimed at the transmit moment and the lost-link state. Assessment B (detector + source
audit) found **one** automated finding in the whole review page (the scan dot's pulse, a live-scan indicator,
kept) and confirmed zero gradients, glows, blur, resting shadows, layout-property animations or text-contrast
failures ó but found non-text contrast and keyboard problems the eye misses. Fixed in the implementation:

1. **A dead link can no longer be keyed (P0).** `FlashRadioAudioPanel(linkLive = Ö)` disables the PTT when the
   link is not `CONNECTED`, keeps it in place so the page does not reflow, and says "Reconnect the radio to
   talk." inside the control.
2. **The off state has a real action.** The plate carries a full-width "Turn on" button instead of leaving a
   40 dp switch in the corner as the only way in.
3. **One filled control per state.** "Pair a new radio" is the filled primary only until a radio is known,
   then it steps back to a quiet outlined button ó while the talk bar is live.
4. **The transmit moment is unmistakable.** The PTT fills teal at rest and turns **warm with "TalkingÖ"** while
   keyed, the state line switches to "Talking", the meter follows `txLevel` and an "On air" chip appears.
5. **The permission strip never contradicts a live link** ó it is suppressed while connected.
6. **Switching radios asks first**: tapping "Use" on another paired radio opens "Use another radio?" instead of
   silently taking the gateway away from a conversation.
7. **Non-text contrast (WCAG 1.4.11)**: the switch now carries a capsule outline in `textTertiary` /
   `accentPrimary` (the track alone was ~1.1:1 on both surfaces), and the unselected radio ring moved from
   `borderStrong` to `textTertiary` (~3.6:1).
8. **Keyboard transmit (WCAG 2.1.1)**: the PTT is focusable and holds on Space/Enter down / releases on key up.
   A screen reader cannot hold a gesture ó that limitation is recorded as `RADUI-DEV04` and is not claimed as
   solved.
9. **Live region on the station state sentence** and `selectableGroup()` around both settings option lists.

Recorded and **not** implemented (each needs controller/engine work, and this round was design-only):

- **Channel / frequency / busy / refused states.** `RadioGatewayUiState` has no channel, no RSSI for the
  active link and no "radio is receiving" refusal reason, so the page cannot answer "am I on the right net, and
  is it clear?" ó the critique's P1. Needs a controller field first.
- **Latch mode, elapsed on-air seconds, haptics on key/unkey, and a slop-tolerant hold** (the hold currently
  cancels when the finger leaves the control, which ends a transmission without feedback). Needs voice-engine
  and test work.
- **Desktop centred dialog instead of a bottom sheet**: the app's own sheet seam (`FlashSheetHost`) is used on
  both hosts, so the radio settings and pairing surfaces match every other sheet in the product.
- **Mode 3** stays hidden by design (`FlashRadioMath.visibleModes`), not renumbered.
- "Use" (switch to this radio) and "Pair" (bond a new one) stay two verbs because they are two acts.

### What did not change

`RadioGatewayController` (no new methods), the authorisation gate and its sheet, the RF transmit warning,
`FlashBackHandler`, `stopScan()` + `setVoiceListening(false)` on dispose, and the `bottomInset` contract. The
demo fake gained voice support; no product path gained logic.

### Verification

- Review page: `lint.mjs --app` 0 FAIL / 0 WARN; `inspect.mjs --app` 0 FAIL / 0 WARN at 390 / 768 / 1440 for
  `connected`, `off`, `empty`, `lost`, `permission`, `candidate`, `scan` and `settings`.
- Compose (2026-11-05, branch `design/radio-gateway-v3`): `:ui:chat:jvmTest --tests "com.transfer.flash.ui.radio.*"` ->
  **44 tests, 0 failures** across 9 suites (no dead actions, a11y source scan, copy, math, chat math, screen logic,
  settings entry, mapper, authorisation gate); `:ui:chat:compileKotlinJvm`, `:app:compileDebugKotlin` and
  `:desktop:compileKotlinJvm` -> **BUILD SUCCESSFUL**. No Compose UI test renders the screen, so the pixels are
  verified only in the review page until `RADUI-DEV02`/`03` run on a device.
- **Device checks owed:** `RADUI-DEV02` (Android, the whole flow with a real radio), `RADUI-DEV03` (desktop),
  `RADUI-DEV04` (screen-reader and keyboard transmit) in `docs/testing/TEST-BACKLOG.md`.

---

## Redesign v3 ó Compose port verified on screen (2026-11-05, later)

The first port was written from the review page; this pass opened the **real** screen and fixed what only a screenshot
shows. Evidence: `design/radio-v3/compose/` (`compose-wide.png`, `compose-narrow.png`, `compose-settings.png`,
`compose-pair-sheet.png`).

### How it was rendered

`powershell -File tools\flash-gradle.ps1 :desktop:radioGatewayDemo` (the demo now opens on a connected VR-N76 with
UV-Pro paired and the audio listening, the same state the review page opens on), then the window was captured at
1180x820 (wide) and 900x820 (narrow, which crosses the 900 dp break and shows the dock), the settings sheet was opened
with the header gear, and the pair sheet with "Pair a new radio".

### What the screenshots changed

| Seen on screen | Fixed in Compose |
|---|---|
| The audio panel had a speaker icon the design does not have | removed; the head is the title and the switch |
| Radio rows had no separator | 1 dp hairline under the active row when another radio follows (the design's rule) |
| "Pair a new radio" used the Hub's near-invisible secondary | a local button: 50 dp, accent when it is the one primary, `backgroundSurfaceStrong` once a radio exists, a 232 dp pill on a wide window and full width on a phone |
| The talk state and the PTT stacked on every width | they share one row on a wide window and stack only in the phone dock; the PTT is 64 dp tall on a wide window |
| The plate darkened when the gateway was off | always `backgroundSurface`, as drawn |
| Blocks were 24 dp apart | 30 dp, matching the review page |
| The column was 720 dp | 780 dp, matching the review page |
| "In use" was a teal chip and anything not connected read "Link lost" (including a stopped gateway) | neutral chip for **In use**, warm chip for **Link lost** only when the link actually failed, no chip otherwise |
| "This link" showed the full mode title and no authorisation | short mode name ("Field Companion"), and the Profile row carries the **Authorised** chip |
| The steps rendered a check icon *and* the "1." that is inside the copy | the copy no longer carries the number; the number is drawn in the accent, as designed |
| The test-message row always said "needs a live link" | reflects sending / sent / failed / the RF warning, like the old diagnostics card |

### Differences kept on purpose

- **Sheets are centred overlays on Windows, bottom sheets on Android.** That is the `FlashSheetHost` seam every other
  sheet in the app uses; forcing a bottom sheet on desktop would make the radio page the odd one out.
- **Text actions are 16 sp**, the app's `FlashHubTextAction`, where the review page drew 14 px.
- **Icons**: `FlashIcons` has no handheld-radio glyph, so the plate and rows use the available Device/Bluetooth marks.
- **The switch carries a 1 dp capsule outline** so its boundary clears WCAG 1.4.11 on both surfaces (the review page's
  switch measured ~1.1:1).
- The demo seeds only UV-Pro in the scan list, because `FakeRadioGatewayController.startScan()` keeps a non-empty
  device list; a real scan shows everything.

### Verification

`:ui:chat:jvmTest --tests "com.transfer.flash.ui.radio.*"` -> 44 tests, 0 failures; `:ui:chat:compileKotlinJvm`,
`:app:compileDebugKotlin` and `:desktop:compileKotlinJvm` -> BUILD SUCCESSFUL. Interaction on a real phone (the listen
switch, PTT hold, pairing with a radio, the sheets' dismiss behaviour) is still owed: `RADUI-DEV02`..`04`.
