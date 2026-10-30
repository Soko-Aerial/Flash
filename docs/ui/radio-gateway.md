# Component Research & Design Document — Radio Gateway (UI-070..UI-074)

**Status:** DESIGNED (2026-10-16) · **visual redesign v2 IMPLEMENTED 2026-10-28** (see “Redesign v2” at the end; the behaviour sections below still hold)  
**Component IDs:**  
- **UI-070**: Radio gateway settings entry, checklist, and overview  
- **UI-071**: Find my radio: candidate detection, live nearby scan list, in-app pairing  
- **UI-072**: Security profile selector and one-time regulatory authorisation sheet  
- **UI-073**: Connection status chip and diagnostic details  
- **UI-074**: Operational mode selector (Field Companion, Relay, Radar, All-in-One)  
**Last updated:** 2026-10-16  
**Owner phase:** Plan R (Revision 3, `docs/plans/RADIO-GATEWAY-PLAN.md` §6, §8; ADR-173, ADR-178, ADR-182, ADR-183)

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
   - `docs/migration/ADAPTIVE-UI-PLAN.md` and `docs/ui/responsive-layout.md`: Breakpoints (Compact <600dp, Medium 600–839dp, Expanded ≥840dp), desktop padding and typography scaling.

---

## Existing Approaches Studied

### Approach 1: Multi-Step Linear Wizard (Pager / Dialog Flow)
- **Description:** A step-by-step wizard presenting: Step 1 (Checklist) $\rightarrow$ Step 2 (Scan Devices) $\rightarrow$ Step 3 (Pairing) $\rightarrow$ Step 4 (Profile Selection) $\rightarrow$ Step 5 (Connection Active).
- **Pros:** Guides a novice user through initial setup sequentially.
- **Cons:** Rigid and frustrating for experienced radio operators who toggle power or switch channels frequently in the field. When a link drops or the radio battery dies, navigating backwards through 4 screens to trigger a reconnect is unacceptable. On wide desktop screens, modal wizards leave vast horizontal empty space.

### Approach 2: System Settings Delegation (OS-Only Scan & Pair)
- **Description:** Flash displays only a dropdown of already-paired OS devices. For new radios, it displays an "Open OS Bluetooth Settings" button and relies on the user pairing in Android Settings or Windows Settings before returning to Flash.
- **Pros:** Minimal application code; avoids managing Bluetooth scanning permissions.
- **Cons:** Specifically rejected by the project owner (Plan R Rev 3 §6, ADR-183). Breaks workflow immersion, provides no RF-specific candidate ranking (e.g., distinguishing a VR-N76 from an office printer or headphones), gives no in-app feedback when pairing fails, and creates friction during emergency field deployment.

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
4. **Honest "Coming Later" Indicators:** Rendering unbuilt modes as disabled informational cards with clear "Coming later" labels guarantees zero "dead clicks" (meeting quality contract §3.9).
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
On tablets and desktop windows (Medium ≥600dp and Expanded ≥840dp), the layout splits into a two-column adaptive grid:
- **Left Column (Hardware & Transport):** Master switch, Readiness Checklist, Candidate Confirmation, Remembered Radio, Connection Status, and "Find my radio" scan list.
- **Right Column (Security, Modes & Diagnostics):** Security Profile selector & Authorisation state, Mode Selector (Modes 1, 2, 4, 5), and "Send a test message" diagnostics.

---

## Why It Was Chosen

1. **Aligns with Plan R Revision 3:** Implements all requirements of §6 ("Find, pair and connect") and §8 ("Security and regulation").
2. **Honours Owner Decisions:** Adopts ADR-173 (Profile M default with authorisation gate), ADR-182 (Windows JNA/registry workflow), and ADR-183 (Android in-app scan list).
3. **Complies with Rule 4:** Ensures zero silent transmissions; all RF emission requires deliberate, warned user actions.
4. **Complies with AGENTS.md §34:** Uses 100% Flash design system tokens (`FlashTheme`, `FlashColors`, `FlashTypography`, `FlashShapes`, `FlashSpacing`, `FlashMotion`, `FlashIcons`). No generic Material 3 controls.

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

## State Flow & Error Sentences (Plan R §6 Flow)

Every state presents **one plain sentence** and **one actionable next step**:

| State | Status Sentence (`sentence`) | Next Step / Subtitle (`nextStep`) | Actions Enabled |
|---|---|---|---|
| `OFF` | "Radio gateway is off." | "Turn on to discover or connect to your radio." | Enable switch |
| `CHECKLIST` | "Radio gateway is on." | "Make sure your radio is on and KISS TNC is enabled." | Find radio, Toggle off |
| `DETECTING` | "Looking for paired radios…" | "Checking known devices." | Cancel, Stop |
| `CONFIRM_CANDIDATE` | "Found <Radio Name>." | "Use this radio for your gateway?" | Confirm, Scan other |
| `SCANNING` | "Scanning for nearby radios…" | "Make sure your radio is discoverable." | Stop scan, Open BT settings |
| `PAIRING` | "Pairing with <Radio Name>…" | "Confirm the pairing request on your screen if prompted." | Cancel |
| `CONNECTING` | "Connecting to <Radio Name>…" | "Opening KISS TNC serial link." | Cancel |
| `CONNECTED` | "Connected to <Radio Name>." | "Ready to send and receive radio chat." | Disconnect, Forget, Test Msg |
| `LINK_LOST` | "Radio disconnected, trying again…" | "Keeping link open. Check radio battery and range." | Reconnect now, Forget |
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
- **Medium (600dp–839dp, Tablet / Foldable):**
  Two-column layout with 20dp padding. Left column: Hardware & Connection (Cards 1–3). Right column: Security, Modes, Diagnostics (Cards 4–6).
- **Expanded (≥840dp, Desktop Windows):**
  Two-column layout constrained to maximum content width 1100dp, centered horizontally. Desktop keyboard shortcuts supported (e.g. `Esc` to close scan/sheets).

---

## Dark Mode Behavior

- Designed dark-first with tactical aesthetics: deep slate backgrounds (`#0D1117`), dark elevation cards (`#161B22`), crisp hairline borders (`#30363D`), and glowing accent indicators.
- Light mode uses crisp off-white canvas (`#F6F8FA`), pure white card surfaces (`#FFFFFF`), subtle cool-grey borders (`#E1E4E8`), and darkened text tokens for high readability outdoors.

---

## What Makes This Flash?

Flash is not a generic consumer chat app or an ad-hoc clone of APRS tools. The Radio Gateway interface embodies Flash's tactical core: high contrast, zero unnecessary visual noise, complete transparency about what the radio is doing, strict enforcement of regulatory authorization before encryption, and an uncompromising safety guarantee that hardware RF transmitters are never keyed silently.

---

## Redesign v2 — “one station plate, then calm rows” (2026-10-28, IMPLEMENTED)

### Why

The owner reported the page “looks weird”. Reading the implementation explained it: the screen was **eight
outlined boxes stacked** (master switch, status, permission, candidate, remembered radio, voice, checklist,
find/scan, then security, modes, tests), every one the same `backgroundSurface` fill with the same 1 dp
`borderDefault`, every one titled with `headingMedium` — no hierarchy at all, so the page read like a
wireframe form. On top of that:

- status dots and permission/warning colours were **hardcoded** (`0xFF3FB950`, `0xFFD29922`, `0xFFF85149`,
  `0xFFF0883E`) instead of the theme’s status tokens, so they clashed with the palette;
- the controls were **stock Material**: `Switch` (twice), `AlertDialog` (twice), `OutlinedTextField`,
  `CircularProgressIndicator`, `TextButton`;
- the dialogs used raw `AlertDialog`, which on Compose Desktop renders through `DialogWindow` — the
  separate native window that `FlashSheetHost`’s KDoc records as the “sheets open somewhere else” bug;
- the desktop layout was **two columns of very unequal length** (left long, right short) inside a 1100 dp
  cap, so a wide window showed a large empty area, and the 56 dp title bar with a back chevron duplicated
  the shell’s own navigation;
- the same facts were said three times (title, switch subtitle, status sentence) and the voice card sat
  between the remembered radio and the scan list, pushing “Find my radio” below the fold.

### The rule the redesign follows

> Borders and fills are **information**, not decoration. A thing is a box only when it is (a) the station
> itself or (b) something that needs an answer right now. Everything else is a section label and rows.

So the page now uses the **Hub tab’s language** (UI-058) rather than inventing a second one: `HubShapes`
(16 dp card, 12 dp control, 6 dp chip), `hubCapsStyle()` section labels with a hairline running to the edge,
`hubRuleBelow` hairlines, `FlashHubSwitch`, `FlashHubButton`, `FlashHubTextAction`, `FlashHubChip`,
`HubLiveDot`, `HubTone` (so every colour is a token: `textSuccess`, `accentSecondary`, `textError`,
`accentPrimary`) and `hubClickable` for the Flash press response. No new primitives, no second visual system.

### Layout

One column at **every** size, capped at **720 dp and centred** (`widthIn(max = 720.dp)` on the list itself,
header content capped the same way). This replaces both the old phone stack and the old two-column desktop
split: no lopsided column and no 1100 dp cap leaving a void. Rows breathe (20 dp between sections, 16 dp page
padding), which is what makes the page look “designed” instead of stacked.

Order, top to bottom:

1. **Header** — back chevron + “Radio gateway (beta)” (the chevron stays on desktop too: the demo host uses
   it to leave the page; the shell’s own navigation is untouched).
2. **Station plate** (`FlashRadioStationBlock`) — the *only* hero: a mark in the state’s tone, the radio’s
   name (or “No radio set up yet”), the live state dot and sentence, the next step, and the master switch at
   the right edge of the same row. When the gateway is on, the plate carries a 1 dp tone-tinted edge and a
   `hubHeroBrush` wash; when it is off it is a flat `backgroundSurfaceSubtle`. Reconnect appears here (as a
   quiet `FlashHubButton`) because it is the one action a broken link needs.
3. **Permission banner** / **candidate banner** — full-width *strips* (tone wash + hairline under), never
   boxes: the two things that genuinely need an answer. The candidate strip holds “Use this radio” plus a
   “Different radio” text action.
4. **This link** — plain fact rows (radio, address, profile, callsign) with a `Forget` action row. Replaces
   the old “Connected radio” card.
5. **Set up your radio** — the three readiness steps as numbered rows (no card), shown while nothing is set
   up, and while the gateway is off (so the page is never blank).
6. **Nearby radios** — the scan action sits in the section header (“Scan for radios” / “Stop (12 s)”), the
   discovered devices are rows with a category chip and a `Pair` / `Use this radio` action, and the battery
   note is one tertiary line at the end.
7. **Security profile** — two selection rows with a ring mark, subtitle and the `Authorised` /
   `Required for military` chip. Profile M still routes to the authorisation sheet when not yet authorised.
8. **Operational mode** — selection rows with `Active` / `Coming later` chips.
9. **Radio voice** — the same content (listen switch, phase sentence, level bar, hold-to-talk, callsign
   note) as section rows instead of a card.
10. **Radio diagnostics** — a single row: “Send a test message” with a `Send` text action and, when the
    button is unavailable, the existing “needs a live link” sentence as tertiary text.

### Dialogs

Both keep their exact behaviour and validation; the frame changes from `AlertDialog` to the house
`FlashConfirmHost` seam (real M3 dialog on Android, in-window overlay on desktop — no separate native
window), with a Flash text field (hairline box, `composerInputBackground` fill) and `FlashHubSwitch` for the
acknowledgement. The RF-transmission warning uses the theme’s `accentSecondary` instead of `0xFFF0883E`.

### What did **not** change

Every `RadioGatewayController` call, every state field, every sentence in `FlashRadioCopy`, the
`FlashRadioAction` table (RADUI-U04), the authorisation and transmit-warning gates, and the plan’s
constraints (`FlashBackHandler`, `stopScan()` + `setVoiceListening(false)` on dispose, the `bottomInset`
padding). `FlashRadioMath` is untouched, so all its unit tests still describe the page’s behaviour. This was
purely visual, as the owner asked.

### Verification

`:ui:chat:compileKotlinJvm` (and the Android host compile) plus the radio tests in `:ui:chat:jvmTest`
(`FlashRadioNoDeadActionsTest`, `FlashRadioCopyA11ySourceTest`, `FlashRadioMathTest`,
`RadioGatewayUiMapperTest`, `FlashRadioSettingsEntryTest`). **Not device-verified**: `RADUI-DEV01` in
`docs/testing/TEST-BACKLOG.md` covers the phone and desktop walk-through.
