# C-hub-tab backlog additions (merge into docs/testing/TEST-BACKLOG.md, section "HUBT - Hub tab UI-058 (Plan A4)")

Status of existing rows after H6-H10 (BUILT = implemented and unit-tested, NOT device-verified; the Status field stays TODO until the owner runs them):

- HUBT-01, 02, 08, 09, 11, 12, 13, 16, 17, 18, 19: BUILT. HUBT-19 now reads: "Review group join requests" in the Groups segment opens the old sheet (the
  chat-list top bar icon and the placeholder button are gone).
- HUBT-03 (5,000 people): the view maths are measured synthetically (`logs/streams/C-hub-tab/experiments-additions.md`, EXP-C1); scroll and search on a
  phone is still owed.
- HUBT-04 (Message unpaired, hub online), HUBT-05 (offline on the LAN), HUBT-06 (news flow), HUBT-07 (hidden department), HUBT-10 (accessibility),
  HUBT-14 (older hub): BUILT, TODO.

New rows. Every Pass condition is something the owner can see on the screen or in the log.

#### HUBT-20 - Unread badge on the Hub tab
- **Setup:** Enrolled phone A, and an admin console that can post announcements (Plan A3). Phone A on Chats.
- **Steps:** Post two org announcements. Look at the bottom bar (phone) / sidebar and bottom tabs (desktop). Open the Hub, scroll News until both cards are at least 60 % visible for one second.
- **Pass:** Badge shows 2, then 1, then disappears as the cards are read; spoken as "Hub, 2 unread"; the count survives an app restart; no badge while the Hub segment is hidden by a revoked enrolment.
- **Source:** UI-058 H9. **Status:** TODO

#### HUBT-21 - News: post, edit, pin, delete, conflict
- **Setup:** Enrolled phone with posting rights (department head and an org admin variant).
- **Steps:** Post with Audience set to your department. Edit the title. Pin it. Delete it. On a second device edit the same item and then edit it here with the old copy open.
- **Pass:** Each action shows its result in the card without a restart; the conflict shows the Plan A3 sentence and keeps the draft; the draft is still there after a failed post and after the app is killed (`HubUiPrefs` / `DesktopHubUiPrefs` as `FlashHubDraftStore`) and is gone after you unenrol; a person without rights sees no New / Edit / Pin / Delete.
- **Source:** UI-058 H7, Plan A3 section 9 sentences. **Status:** TODO

#### HUBT-22 - People: search, filter, letters
- **Setup:** Hub with at least 200 members including names with accents (for example "Émile", "Zoë") and two people with the same name.
- **Steps:** Type "emile"; filter by a department; clear. Tap a letter header region while scrolling.
- **Pass:** "emile" finds "Émile"; the two same-named people show their department and a 4-character id tail; headers stay sticky; the empty state sentence names the query.
- **Source:** UI-058 H7. **Status:** TODO

#### HUBT-23 - Message colleague: paired, unpaired, double tap
- **Setup:** Phones A, B, C enrolled in one org; A and B paired, A and C not paired.
- **Steps:** On A open People > B > Message. Then People > C > Message and tap Message twice quickly. Then tap Message and immediately open another person.
- **Pass:** B opens the existing conversation (no second one is created). C opens one conversation, not two; the button reads "Opening the conversation" while busy; leaving the person before the answer arrives does not open a chat for the old person; a refused start shows the exact Plan B sentence, never a code.
- **Source:** UI-058 H8, ORGT-U26. **Status:** TODO

#### HUBT-24 - Call from the person sheet
- **Setup:** A and B on the same Wi-Fi; one case unpaired but org-verified, one case on different networks.
- **Steps:** Open the person; look for Call; place the call. Repeat with B off the LAN.
- **Pass:** Call is offered only when B shows "On your network" and the existing call gate allows it; the call starts through the normal call screen; off the LAN there is no Call button; an unverified, unpaired person never has it.
- **Source:** UI-058 H8, `PeerTrustPolicy.mayRing`. **Status:** TODO

#### HUBT-25 - Route from a notification or the command line, cold start
- **Setup:** Debug build, enrolled. Android: app force-stopped. Desktop: app closed.
- **Steps:** Android: `adb shell am start -n com.transfer.flash/.MainActivity -e com.transfer.flash.extra.HUB_ROUTE hub/announcement/<id>` and again with `hub/person/<deviceId>`. Desktop: start with `--hub-route=hub/announcement/<id>`. Repeat with a garbage value (`hub/person/../x`).
- **Pass:** The Hub opens on News with that announcement, or on the person; garbage is ignored and the app opens normally; the route is applied once (rotate / return does not reopen it).
- **Source:** UI-058 H9 (explicit extra, no new manifest filter). **Status:** TODO

#### HUBT-26 - Route when not enrolled
- **Setup:** Unenrolled phone.
- **Steps:** Run the HUBT-25 commands.
- **Pass:** The app lands on Chats and shows "This device is not part of an organisation."; no Hub tab appears; Transfers is unchanged.
- **Source:** UI-058 H9. **Status:** TODO

#### HUBT-27 - Wide layouts
- **Setup:** Tablet or foldable (width at least 840 dp) and the Windows desktop at three window widths.
- **Steps:** Open each segment, open a person, a department, an announcement; resize across 840 dp with a detail open.
- **Pass:** List left, detail right at 840 dp and above; below it the detail is a sheet or full-screen reader; the selection survives the resize; Esc / close returns to the list.
- **Source:** UI-058 H7. **Status:** TODO

#### HUBT-28 - TalkBack and reduce motion
- **Setup:** TalkBack on, then "Remove animations" on.
- **Steps:** Swipe through the Hub: status strip, segments, a person row, an announcement card, a group row, the composer.
- **Pass:** Segments are announced as tabs with selected state; each person row is one sentence (name, department, role, paired, online); the card actions are reachable; status-strip changes are announced politely; no motion with animations removed.
- **Source:** UI-058 H7. **Status:** TODO

#### HUBT-29 - Groups segment: join, pending, review requests
- **Setup:** Org with an open group, an approval group, and an admin phone.
- **Steps:** Join the open group; ask to join the approval group; on the admin phone tap "Review group join requests".
- **Pass:** Open group shows Open after joining; approval group shows a waiting state; the admin sees the old requests sheet and approving updates the member's row; a non-admin never sees the review action.
- **Source:** UI-058 H7 (supersedes the Browse sheet as the front door). **Status:** TODO

#### HUBT-30 - Status strip honesty
- **Setup:** Enrolled phone.
- **Steps:** (1) Airplane mode after a sync. (2) Wait until the lease is near its end (or set the phone clock forward, then restore it). (3) Revoke the device in the console. (4) Use a hub without announcements support.
- **Pass:** (1) cached people still show, strip says the list is the last one loaded; (2) strip asks to reconnect to the company network; (3) Hub shows the revoked state and nothing from the directory; (4) News says the server does not support news, People still works.
- **Source:** UI-058 H6/H7 (`HubFacade`, `newsUnsupported`). **Status:** TODO

#### HUBT-31 - Transfers moved tip and last segment
- **Setup:** Phone that has the unenrolled layout, then enrols.
- **Steps:** Enrol; open the Hub; dismiss the tip; kill and restart; choose People; restart.
- **Pass:** The tip "Transfers moved to the top bar." appears once and never again; the Hub reopens on the last segment you used, or News when unread news exists; unenrolling and re-enrolling does not crash on stale prefs.
- **Source:** UI-058 H9 (`HubUiPrefs`, `DesktopHubUiPrefs`). **Status:** TODO

#### HUBT-32 - Transfers notification actions while enrolled
- **Setup:** Enrolled phone, a transfer running.
- **Steps:** Tap the transfer notification and each of its actions (Pause, Cancel, Open) from the shade, once with the app in the background and once killed.
- **Pass:** Each action works as before; the app shows Transfers pushed over Chats with the Hub tab still present.
- **Source:** UI-058 H9. **Status:** TODO
