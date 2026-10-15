# C-hub-tab decisions additions (merge into docs/decisions.md; numbers ADR-170..179 were reserved for this stream)

## ADR-170 - Hub tab memory lives in the hosts, not in Room (PROPOSED)

### Decision
The last Hub segment, the one-time "Transfers moved" tip and unsent announcement drafts are stored by each host behind two small interfaces in
`ui/chat` (`FlashHubUiPrefs`, `FlashHubDraftStore`): SharedPreferences `flash_hub_ui` on Android (`HubUiPrefs`), `hub-ui.properties` in the state
directory on the PC (`DesktopHubUiPrefs`). Drafts are removed once enrolment is known to have ended. A host that passes nothing gets an in-memory store.

### Context
Plan A4 H9 names `hubLastSegment` "in settings". The settings store is in `core/persistence`, which this stream may not edit, and a Room schema
change for three scalar values and some draft text is out of proportion.

### Alternatives considered
- Add columns to the settings entity (Room schema step 18): rejected for this stream (ownership), also needs a migration test for three scalars.
- Keep it in memory only: rejected, a failed post's draft must survive a restart (Plan A3) and the segment must be remembered.

### Revisit when
The integrator prefers one settings home; moving it means implementing the two interfaces over the settings DAO, nothing else changes.

## ADR-171 - Hub routes enter through an explicit intent extra / launch argument (PROPOSED)

### Decision
`hub/announcement/<id>` and `hub/person/<deviceId>` reach the Hub through the intent extra `com.transfer.flash.extra.HUB_ROUTE` (Android) and
`--hub-route=<route>` (PC), parsed and validated by `FlashHubMath.routeFromArgs/parseRoute/routeDecision`. No new manifest intent filter and no
`flash://` deep link are added. A route waits until enrolment is known; when the device is not enrolled the app lands on Chats with
"This device is not part of an organisation." and the route is forgotten.

### Context
A public deep link into organisation content would be an attack surface (any app or web page could steer the UI) and Plan A4 only requires
notification and cold-start routes. The notification side (Plan A3 push) will set the extra from inside the app's own PendingIntent.

### Alternatives considered
- `flash://hub/...` filter: rejected for now, exported and spoofable; revisit if links must be shared between people.

### Revisit when
Announcement notifications ship (the sender of the PendingIntent must use the same extra), or links to people/announcements must be shareable.

## ADR-172 - The Hub tab owns its two-pane layout (PROPOSED)

### Decision
At 840 dp and above the Hub shows list and detail side by side with its own splitter-less layout (`FlashHubBody`); below that the detail is a
sheet or a full-screen reader. It does not use the shell's shared detail pane.

### Context
The shell's detail pane is selection-driven by chat and transfer items. Reusing it would have touched both host shells in many places. The Hub
selection (person, department, announcement) is local to the Hub and is saved with the route state.

### Revisit when
The integrator wants one detail-pane abstraction for the whole shell (ADAPTIVE-UI-PLAN AD-7).
