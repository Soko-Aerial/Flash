# S3 backlog additions (merge into docs/testing/TEST-BACKLOG.md section 4zzzzzzzzz)

Existing HUBT-01, 02, 08, 09, 11, 12, 13 are now BUILT for the shell (H0-H5), unit-tested, not device-verified; their Status stays TODO (BUILT, not device-verified).

#### HUBT-15 — Hub and Announcement icon look
- **Setup:** Debug build, enrolled phone (any), light and dark.
- **Steps:** Look at the Hub tab icon selected/unselected; open the Hub screen.
- **Pass:** The building glyph matches the stroke weight and keylines of Chats/Nearby/Settings at 24 dp; readable at 200 % font scale. Announcement (megaphone) is not on a screen yet.
- **Source:** `docs/ui/icon-system.md` addendum 2026-10-14. **Status:** TODO

#### HUBT-16 — Chat-list top bar at 320 dp and 200 % font
- **Setup:** Enrolled phone, display size small, font 200 %.
- **Steps:** Open Chats.
- **Pass:** All five actions (LAN, Search, New group, Join link, Transfers) fit or overflow per `docs/ui/chat-list.md`; Transfers stays tappable at 48 dp and the title is not unreadably truncated. (Known risk: no overflow menu yet.)
- **Source:** UI-058 H5. **Status:** TODO

#### HUBT-17 — Transfers indicator and list scroll cost
- **Setup:** Enrolled phone, start a 2 GB transfer, scroll Chats with 100+ chats.
- **Steps:** Watch the Transfers indicator and the chat-list frame rate.
- **Pass:** Indicator shows the active count (99+ cap), spoken as "Transfers, N active"; no new jank vs an unenrolled phone (EXP-012).
- **Source:** UI-058 H5. **Status:** TODO

#### HUBT-18 — Desktop: leave a pushed Transfers
- **Setup:** Enrolled Windows desktop with a conversation selected.
- **Steps:** Press Ctrl+2, then Esc (twice if a transfer row is selected); also use the tray "Transfers".
- **Pass:** Ctrl+2 opens Transfers over Chats; Esc closes the detail pane first, then returns to Chats; sidebar keeps Chats highlighted.
- **Source:** UI-058 H4. **Status:** TODO

#### HUBT-19 — Browse department groups still reachable
- **Setup:** Enrolled phone and desktop.
- **Steps:** Open the Hub tab, tap "Browse department groups".
- **Pass:** The existing groups sheet opens; the chat-list top bar no longer has that icon.
- **Source:** UI-058 H5 (placeholder keeps the action until H7). **Status:** TODO
