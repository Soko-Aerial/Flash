# Test Backlog Additions — Stream R-ui

Reserved Test Prefix: `RADUI-`

Every test here is owed on real hardware or devices. None of them may be marked PASS by an automated agent.
Status values: `TODO` -> `PASS` / `FAIL (ERROR-NNN)` / `BLOCKED (why)` / `OBSOLETE (why)`.

---

### RADUI-01 — Radio gateway appearance and layout on Android phone (portrait & landscape)
- **Setup:** Android phone (Pixel, Samsung, BelFone, etc.). Open Flash -> Settings -> Radio gateway (beta).
- **Steps:**
  1. Inspect the single-column layout in portrait orientation.
  2. Verify touch targets on all interactive elements (switches, buttons, radio list items) are at least 48x48dp.
  3. Verify typography hierarchy (title, headings, badges, caption texts) against FlashTheme tokens.
  4. Rotate the device to landscape orientation and verify scroll behaviour and card margins.
- **Pass:** Single column fits cleanly without horizontal scrolling or text clipping; touch targets meet minimum 48dp; rotation maintains scroll position and does not clip cards.
- **Status:** TODO

### RADUI-02 — Radio gateway two-pane adaptive layout on Desktop (wide vs narrow window)
- **Setup:** Desktop host run (`tools\flash-gradle.cmd radioGatewayDemo` or portable build `--radio-demo`).
- **Steps:**
  1. Launch the standalone Radio Gateway demo window on Windows.
  2. Maximize or widen the window beyond 840dp width. Verify the two-column layout: left column contains Master Switch, Status, Candidates/Remembered, Checklist, and Discovered Devices; right column contains Security Profiles, Operating Modes, and Diagnostics.
  3. Confirm the content width is constrained to `widthIn(max = 1100.dp)` centered horizontally.
  4. Dynamically resize the window width below 840dp (e.g. 500dp). Verify the transition to single-column layout.
- **Pass:** Smooth adaptive breakpoint at 840dp; two-column layout remains balanced and readable; no text overflow or layout snapping glitches.
- **Status:** TODO

### RADUI-03 — Android TalkBack accessibility navigation and state announcements
- **Setup:** Android phone with Google TalkBack screen reader enabled.
- **Steps:**
  1. Navigate into Flash Settings -> Radio gateway (beta).
  2. Swipe right sequentially to traverse all focusable elements.
  3. Verify semantic descriptions: "Navigate back, button", "Radio gateway master switch, switch, off/on", "Connection status: Disconnected. Check power and KISS TNC", "Profile M (Military / Enterprise), Recommended, Protected", "Operating Mode: Direct peer-to-peer messaging".
  4. Double-tap to activate elements (e.g. toggle master switch, select profiles, trigger scan).
- **Pass:** TalkBack announces clear, descriptive text from `FlashRadioCopy` for every node; no "unlabelled button" or raw variable dumps; state changes announce updated state descriptions immediately.
- **Status:** TODO

### RADUI-04 — Desktop keyboard navigation, focus indicators, and key triggers
- **Setup:** Desktop host demo on Windows PC.
- **Steps:**
  1. Open Radio Gateway screen without mouse interaction.
  2. Use `Tab` and `Shift+Tab` to move focus through all interactive cards, switches, buttons, and radio groups.
  3. Verify focus outlines are clearly visible against surface backgrounds.
  4. Press `Space` or `Enter` to activate the focused control (e.g. toggle switch, expand authorization dialog, start scan).
  5. Press `Escape` or navigate to Back button to dismiss dialogs or navigate back.
- **Pass:** Focus order follows logical top-to-bottom, left-to-right reading order; all controls are operable without mouse; Esc cleanly dismisses modals.
- **Status:** TODO

### RADUI-05 — High font scale (200% system font size / display scaling)
- **Setup:** Android device and Desktop OS set to 200% font size / display scale.
- **Steps:**
  1. Open Radio Gateway screen.
  2. Inspect text inside cards, especially status chips, security profile descriptions, checklist items, and warning modals.
  3. Verify multi-line wrapping and button heights.
- **Pass:** No text truncation, ellipsis overflow on critical instructions, or overlapping lines; modal dialog action buttons stack vertically if horizontal width is constrained.
- **Status:** TODO

### RADUI-06 — Dark mode and high-contrast color validation
- **Setup:** Switch device OS between Dark Theme and Light Theme.
- **Steps:**
  1. Inspect Radio Gateway screen in both light and dark themes.
  2. Verify contrast of status indicators (green connected dot, amber connecting dot, red failure dot).
  3. Verify contrast of Profile M recommendation badge and active selection borders.
- **Pass:** All body text meets WCAG AA contrast (minimum 4.5:1); heading and UI components meet 3:1 contrast; colors respect FlashTheme tokens with zero hardcoded palette values.
- **Status:** TODO

### RADUI-07 — Reduce motion accessibility setting adherence
- **Setup:** Enable "Remove animations" / "Reduce motion" in Android or Windows system accessibility settings.
- **Steps:**
  1. Toggle Radio Gateway master switch.
  2. Trigger discovery scan and observe progress indicators.
  3. Open and close the Security Authorisation dialog and Test Transmit Warning dialog.
- **Pass:** No distracting pulse or bouncing animations; modal dialogs appear/disappear instantly or via subtle opacity fade without spatial movement.
- **Status:** TODO

### RADUI-08 — Real radio pairing and TNC connection runbook (Owner hardware test)
- **Setup:** Real physical radio (e.g. Quansheng UV-K5 / UV-K6 with KISS TNC firmware, or Mobilinkd TNC3/TNC4) paired or discoverable via Bluetooth. Android phone with Bluetooth enabled.
- **Steps:**
  1. In the radio menu, enable KISS TNC mode: `Menu -> General Settings -> KISS TNC -> Enable`.
  2. Open Flash -> Settings -> Radio gateway (beta).
  3. Switch the Master Switch to ON.
  4. Verify the candidate card displays the detected radio name, or tap "Find nearby radios" if scanning.
  5. Tap "Pair" on the detected radio.
  6. Observe status row transition: `Scanning...` -> `Connecting to <radio>...` -> `Connected: Ready to transmit and receive`.
  7. Turn off the radio: verify status transitions to `Disconnected: Connection lost. Reconnecting...` or failure notice.
  8. Turn the radio back on: verify automatic reconnection without app restart.
- **Pass:** Radio successfully connects via Bluetooth SPP / RFCOMM; status card reflects active connection; reconnect works reliably without orphan connections.
- **Status:** TODO

### RADUI-09 — Encrypted Profile M authorisation confirmation and enforcement
- **Setup:** Flash on Android or Desktop connected to Radio Gateway screen.
- **Steps:**
  1. With Master Switch ON, inspect Security profile section. Verify Profile A (Plaintext) is default, and Profile M (Encrypted) shows "Authorisation required" badge.
  2. Tap Profile M. Verify one-time Authorisation Dialog appears explaining regulatory requirements (e.g. authorization to transmit encrypted payloads on the assigned frequency).
  3. Tap "Cancel" or click outside: verify Profile M is NOT selected; Profile A remains selected.
  4. Tap Profile M again, read the text, and tap "Confirm Authorisation".
  5. Verify Profile M is now selected, badge updates to "Active", and the state persists.
- **Pass:** Profile M cannot be selected without explicit user confirmation of legal authorization; once confirmed, Profile M becomes the active primary profile.
- **Status:** TODO

### RADUI-10 — Test transmission warning dialog and transmit action
- **Setup:** Radio connected in KISS TNC mode with antenna attached to a dummy load or authorised frequency.
- **Steps:**
  1. In the Diagnostics card, observe "Send test frame" button is enabled only when radio status is Connected.
  2. Tap "Send test frame".
  3. Verify warning dialog appears: "The radio will transmit on the current frequency. Ensure your antenna is connected and frequency is clear."
  4. Tap "Cancel": verify no transmission is triggered.
  5. Tap "Send test frame", then tap "Transmit now".
  6. Verify button shows transmitting state, followed by "Test frame sent successfully".
- **Pass:** Explicit confirmation is required before any RF transmission; button disables during transit to prevent double-transmission; success message confirms transmission completion.
- **Status:** TODO
