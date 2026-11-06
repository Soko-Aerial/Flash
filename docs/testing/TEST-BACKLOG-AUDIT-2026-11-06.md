# Test backlog audit - 2026-11-06

Source: `docs/testing/TEST-BACKLOG.md` at `dev` `50b6cadf`. Mechanical audit of every test heading with an ID; it does not claim a test was run. This count predates the `design/chat-redesign` merge (`ca9ada63`), which added `CHATUI-01..16`; those 16 are in the backlog, so the live total is 783 headings. Of the 16, `CHATUI-13/14` are PASS, `CHATUI-15` is PARTIAL and `CHATUI-01..12/16` are TODO, so the live non-pass total is 766.

## Summary

- Test headings found: **767**
- PASS: **15**
- AUTOMATED: **15**
- TODO: **681**
- PARTIAL: **5**
- BLOCKED: **6**
- NO_STATUS: **30**
- OTHER: **14**
- FAIL: **1**

`AUTOMATED` means the status says an automated pass but still names a device/real-hub run. `NO_STATUS` has no recorded result and must be treated as not done/unknown. `OTHER` includes IMPLEMENTED / UNBLOCKED / DECIDED states that still owe a physical or manual run.

## Correction: most “planned / not built” status text is stale

58 entries literally say `PLANNED`, `not built` or `nothing built`, but many were written before the feature landed. Current code
shows these are **built and only owe a device test**, not missing features:

- Group secret/membership (`GMB-01..14`, `GSEC-01..05/07/08`, `SWM-06`): GM-1..GM-10 and the invite/rotation/join UI are in
  `GroupJoinTest`, `GroupRotationTest`, `GroupDiscoveryTest`, `GroupSecretStore` and the group settings sheets.
- Announcements (`ANN-01..11`): `AnnouncementStore`, routes, client API, Room state and console are present (Plan A3 complete).
- Department heads (`DEPH-01..07`): `AdminAuthService` Plan A1 migration, `DepartmentHeadScopeTest`, console role/invite flow.
- Directory privacy (`PRIV-01..05`): `DirectoryPrivacy.kt` and the hidden-department tests/routes are present.
- Hub tab (`HUBT-01..14`, later `HUBT-20..32`): `FlashHubScreen`/`FlashHubRoute` and the later H6–H10 work are present.
- Hub files P3/P4 (`HUBFILE-C/M/G`): the client transport and file-message/group paths landed after those rows were written.
- ORGT-11/12/13: B4 hub-group rosters on the LAN (and the built ORG-send wake work) landed; ORGT-14 identity-key regeneration is
  the remaining unimplemented/undefined case.
- `HUBA-12`/`HUBU-11` are partial, not wholly planned: the browser signer works; the phone-as-step-up path is still blocked.

### Genuinely not built or only partial

- **Transfer v2 / pull transfer**: `SWM-05`, `TV2-01` — plan only.
- **Revocation gossip over LAN (Plan B B5)**: the revocation-list exchange/ripple is not implemented; B4 roster statements are.
- **Identity-key regeneration** (`ORGT-14`): no implemented recovery/definition.
- **Optional group discovery beacon** (`GSEC-06`): not built; the secret/invite path itself is built.
- **Phone as step-up approver** (`HUBA-12`, `HUBU-11`): browser signer exists; phone path blocked.
- **Profile A signed-cleartext chat**: deliberately unsupported until a peer identity-public-key exchange exists.
- **Wi‑Fi Direct transport**: still not implemented.
- **Hub files LAN ↔ hub hand-over**: not built (P3/P4 explicitly left it out).
- **B4 residuals**: `org_group_unsealed` header not wired, no conversation for an admin-added hub group before joining, `Flash.kt`
  not wired, revocations not passed to `HubGroupRosters`.
- **Department-head client gaps**: client scope/provisioning, multi-department paging and revocation-list installer remain open
  from the 2026-11-05 console audit.

These are the only feature-level “not built” items; the other 700+ are tests of built behavior that still need a device, hub,
desktop or measurement run.

## Not done, by area

### 1. Recent call fixes (2026-09-29)

- `TODO` L37: CALL-01 — Calls from the phone hosting the Wi-Fi hotspot (ERROR-079, ADR-054) ? TODO
- `TODO` L49: CALL-02 — Desktop on webrtc-java 0.19.0 (ADR-054) ? TODO
- `TODO` L59: CALL-03 — Desktop memory stays flat, and idle heap is returned (ERROR-075, ERROR-078, EXP-017) ? TODO
- `TODO` L69: CALL-04 — Late joiner connects (ERROR-076) ? TODO
- `TODO` L76: CALL-05 — "Send smaller video in groups" (ADR-053) ? TODO
- `TODO` L86: CALL-06 — Desktop sends 640x360 although 720 was granted (anomaly, EXP-017) ? TODO
- `TODO` L92: CALL-07 — Two desktops pair (ERROR-077) ? TODO

### 2. Group calling plan (`docs/calling/GROUP-VIDEO-PLAN.md`, G1–G7)

- `PARTIAL` L101: GRP-01 — One video per participant (G1, UI-050b) ? PARTIAL (owner-reported 2026-10-02). The owner ran a video call with **3 devices** and another with
- `TODO` L113: GRP-02 — Network band detection (G2) ? TODO
- `TODO` L122: GRP-03 — Video only on request (G3) ? TODO
- `TODO` L131: GRP-04 — LOW device follows the speaker (G3, G5, UI-050c) ? TODO
- `TODO` L139: GRP-05 — Pin on MEDIUM/HIGH (G5, UI-050c) ? TODO
- `TODO` L147: GRP-06 — Old build in the same call (G3, ADR-049) ? TODO
- `TODO` L152: GRP-07 — Budgets by tier and band (G4) ? TODO
- `TODO` L162: GRP-08 — Health warnings (G6, UI-050d) ? TODO
- `BLOCKED` L176: GRP-09 — Call size caps (G7, ADR-050) ? BLOCKED for real group sizes until vouched groups (GV, ADR-044) land; the debug-build check can run
- `TODO` L182: GRP-10 — Where group video stops being usable (owner report 2026-10-02) ? TODO

### 3. Presence & connections plan (`docs/network/PRESENCE-CONNECTIONS-PLAN.md`, PC1–PC5)

- `TODO` L199: PC-01 — Keepalive efficiency (PC1) ? TODO
- `TODO` L206: PC-02 — Connection planner (PC2, ADR-045) ? TODO
- `TODO` L219: PC-03 — Connected / Online / Offline dots and dial on demand (PC3, UI-030b) ? TODO
- `TODO` L227: PC-04 — Presence sharing (PC4, ADR-046) ? TODO
- `TODO` L240: PC-05 — Modes ECO / STANDARD / BOOST (PC5, ADR-048) ? TODO

### 4. Older device checks still owed

- `TODO` L276: MIG-01 — Android install upgraded across an older release keeps its chats (ERROR-080, ADR-055) ? TODO
- `TODO` L291: MIG-02 — Desktop still opens its existing chat database (ERROR-080, ADR-055) ? TODO

### 4b. Discovery resilience plan (`docs/network/DISCOVERY-RESILIENCE-PLAN.md`, DR1)

- `TODO` L300: DR-01 — A paired peer reconnects after a restart with multicast blocked (DR1, ADR-047) ? TODO
- `TODO` L325: DR-02 — A phone finds the desktop through the broadcast beacon when multicast is blocked (DR2, ADR-047) ? TODO
- `TODO` L355: DR-03 — "Scan network" finds a peer that discovery cannot see (DR3, ADR-047) ? TODO
- `TODO` L394: DR-04 — Desktop advertises only reachable adapters, and the quiet-network hint (DR5, ADR-047) ? TODO

### 4c. Group trust model (`docs/group/v0-threat-review.md`, ADR-044)

- `TODO` L438: GT-01 — Honest group flows still work after the V1a hardening (F-1, F-2, F-4, F-5) ? TODO
- `TODO` L470: GT-02 — Signed (v2) groups between real devices (ADR-044 V1) ? TODO
- `TODO` L507: GT-03 — Vouched members: two devices that never paired share a v2 group (ADR-044 V2) ? TODO

### 4d. Session ceiling and dial budget (ADR-057, `docs/network/PRESENCE-CONNECTIONS-PLAN.md` "Session ceiling")

- `TODO` L573: SC-01 — A phone holds a 20-member group's sessions (ceiling 24) ? TODO
- `TODO` L583: SC-02 — Above the ceiling: refuses only the excess, and a crowd does not starve contacts the phone dials itself ? TODO

### 4e. Audit fixes of 2026-09-30 (`docs/audit/2026-09-28-architectural-audit-and-tasks.md` section 7)

- `TODO` L596: AUD-01 — A stopped engine is no longer advertised, and a Wi-Fi reconnect leaves one registration (audit 3.7, ERROR-073) ? TODO
- `TODO` L612: AUD-02 — Sending to a group moves the thread to the top of the chat list (audit 3.6) ? TODO
- `TODO` L619: AUD-03 — Linux CI is green (audit section 7, "CI on Linux") ? TODO

### 4f. Push-to-talk on desktop (ADR-058, `docs/ui/ptt-session-overlay.md` Addendum A)

- `TODO` L635: PTTD-01 — Desktop <-> phone push-to-talk, both directions, on the LAN (ADR-058) ? TODO
- `TODO` L649: PTTD-02 — Audio formats on other hardware (16 kHz / 8 kHz; USB headset; Linux later) ? TODO
- `TODO` L657: PTTD-03 — Windows microphone privacy switch: silent capture ? TODO
- `TODO` L666: PTTD-04 — Cold-open latency: are the first words clipped? ? TODO
- `TODO` L675: PTTD-05 — Android PTT after the engine re-shape ? TODO
- `TODO` L686: PTTD-06 — A call and push-to-talk never share the desktop microphone ? TODO
- `TODO` L696: PTTD-07 — Card, button and hotkey look and behave right on the desktop (UI-051 Addendum A) ? TODO

### 4g. Chat and group sync audit (`docs/audit/2026-09-28-chat-group-sync-audit-and-plan.md`, 2026-09-30)

- `TODO` L715: CGS-01 — A desktop that comes back online learns its groups and catches up (audit area 2, commit `cd3ca0a1`) ? TODO
- `TODO` L726: CGS-02 — A group message the receiver dropped is retried within seconds, not after a minute (commits `cd3ca0a1`, `031b6ab4`) ? TODO
- `TODO` L736: CGS-03 — Read ticks in a group (audit area 1, commit `b9b43ece`) ? TODO
- `TODO` L749: CGS-04 — A member that catches up tells the author (audit area 3, commit `c31b30cd`) ? TODO
- `TODO` L757: CGS-05 — A late member sees a label for an earlier photo or voice note, not an empty bubble (audit area 2, commit `4e843cd9`) ? TODO
- `TODO` L765: CGS-06 — Message Info sheet (UI-051, commit `5060ac82`) ? TODO
- `TODO` L779: CGS-07 — Catch-up banner (UI-052) ? TODO
- `TODO` L790: DNAME-01 — Renaming the desktop shows everywhere on the desktop (audit area 5, commit `3156e400`) ? TODO
- `TODO` L801: DNAME-02 — The phone sees the new desktop name after a reconnect (audit area 5) ? TODO

### 4h. Network switch & stale route recovery (ERROR-085, 2026-09-30)

- `TODO` L811: NET-SW-01 — Automatic reconnection after switching from mobile hotspot to home Wi-Fi (ERROR-085) ? TODO

### 4i. Group call end and recovery (ERROR-086, audit 2026-09-30)

- `TODO` L841: GCALL-01 — Being left alone in a group call does not trap you in it (ERROR-086 root cause) ? TODO
- `TODO` L854: GCALL-02 — A peer rejoining inside the grace window keeps the call (ERROR-086 (b)) ? TODO
- `TODO` L864: GCALL-03 — Invitees stop ringing (ERROR-086 (c)) ? TODO
- `TODO` L875: GCALL-04 — Members who never join are not shown as connecting forever (ERROR-086 (d)) ? TODO
- `TODO` L892: GCALL-05 — A leg whose Wi-Fi blips comes back without rejoining (ERROR-086 (e)) ? TODO
- `TODO` L901: GCALL-06 — A call in ECO mode keeps its participants' sessions (ERROR-086 (k)) ? TODO
- `TODO` L912: GCALL-07 — Joining a call that ended while you were joining does not hang (ERROR-086 (f)) ? TODO

### 4j. Unread state and calls from unpaired members (ERROR-087 / ERROR-088, audit 2026-10-01)

- `TODO` L930: UNREAD-01 — Leaving a chat never leaves it unread (ERROR-087 A) ? TODO
- `TODO` L943: UNREAD-02 — A new message while the list is showing counts as one, and keeps pin and mute (ERROR-087 A) ? TODO
- `TODO` L952: UNREAD-03 — Leaving with the system back gesture closes the thread (ERROR-087 B) ? TODO
- `TODO` L961: UNREAD-04 — A legacy group's rename or member change does not reset its unread state (ERROR-087 A) ? TODO
- `TODO` L969: UNREAD-05 — A message that arrives while the app is in the background with a chat open (ERROR-087, open question) ? TODO
- `TODO` L980: GCALL-08 — A group call from a member the others are not paired with reaches them (ERROR-088) ? TODO
- `TODO` L993: GCALL-09 — A caller is told when members cannot be reached (ERROR-088, after the fix) ? TODO
- `TODO` L1002: GCALL-10 — A member the caller cannot reach at all still sees the call through another participant (ERROR-088) ? TODO

### 4k. Edge-case audit (`docs/audit/2026-10-01-chat-edge-case-audit.md`, ERROR-089…094, 2026-10-01)

- `TODO` L1020: EDGE-01 — The group keeps working when its owner is gone (ERROR-090, Option D) ? TODO
- `TODO` L1030: EDGE-02 — The owner leaves on purpose (ERROR-090, Option A / ADR-063) ? TODO
- `TODO` L1044: EDGE-03 — One dead member and the status of every group message (ERROR-090) ? TODO
- `TODO` L1053: EDGE-04 — A 1:1 message to a peer that is offline for 31 minutes (ERROR-089) ? TODO
- `TODO` L1061: EDGE-05 — Oversize text (ERROR-091) ? TODO
- `TODO` L1070: EDGE-06 — A peer's clock is wrong (ERROR-092) ? TODO
- `TODO` L1078: EDGE-07 — The clock steps forward while messages are queued (ERROR-092) ? TODO
- `TODO` L1084: EDGE-08 — File names (ERROR-093) ? TODO
- `TODO` L1093: EDGE-09 — Delete for everyone while the other side is offline (ERROR-094) ? TODO
- `TODO` L1099: EDGE-10 — A reaction while the other side is offline (ERROR-094) ? TODO
- `TODO` L1104: EDGE-11 — The receiver's storage is full (ATT-01, not yet an ERROR) ? TODO
- `TODO` L1110: EDGE-12 — A zero-byte file (ATT-04) ? TODO
- `TODO` L1115: EDGE-13 — The database cannot write because storage is nearly full (LIFE-04) ? TODO
- `TODO` L1121: EDGE-14 — Search with `%` and `_` (MSG-16) ? TODO
- `TODO` L1126: EDGE-15 — Delete a group chat without leaving, then a message arrives (GO-18) ? TODO
- `TODO` L1132: EDGE-16 — Both phones call each other at the same moment (CALL-01) ? TODO
- `TODO` L1137: EDGE-17 — A cellular call or an alarm during a Flash call (CALL-03) ? TODO
- `TODO` L1142: EDGE-18 — Windows: first-run firewall prompt, sleep and resume (NET-09, LIFE-10) ? TODO

### 4l. Group call from a caller not paired with every member (ERROR-095, 2026-10-01)

- `TODO` L1153: GCALL-11 — A new group made while every member is online is v2 and every member can be called (ERROR-095) ? TODO
- `TODO` L1162: GCALL-12 — The old legacy group: the caller sees who is missing, and the callee's log says why it declined (ERROR-095) ? TODO
- `TODO` L1172: GCALL-13 — Creating a group while a member is offline is refused with the member's name (ERROR-095) ? TODO
- `TODO` L1181: GCALL-14 — An ended call never leaves a device answering "busy" (ERROR-095 follow-up of ERROR-086) ? TODO

### 4m. A late joiner connects to every member already in the group call (ERROR-096, 2026-10-01)

- `TODO` L1195: GCALL-15 — The second callee to accept connects to the first one (ERROR-096) ? TODO
- `TODO` L1205: GCALL-16 — Capture: does the phone send `GroupPresence` after it accepts? (ERROR-096, unexplained) ? TODO
- `TODO` L1214: GCALL-17 — A member who joins late in a larger call, and a full call still turns the extra member away (ERROR-096) ? TODO

### 4y. Group call participant list shows only group members (ERROR-103, 2026-10-06)

- `TODO` L1225: GCALL-18 - No tile for a device that is not in the group ? TODO

### 4y2. Group call leftovers and video feedback (ERROR-104, 2026-10-06)

- `TODO` L1234: GCALL-19 - The "join call" banner goes when the call really ends, and stays while it can be joined ? TODO
- `TODO` L1241: GCALL-20 - A late invite does not ring after the caller hung up ? TODO
- `TODO` L1248: GCALL-21 - No presence or invite is sent after the call ended ? TODO
- `TODO` L1255: GVID-08 - Tapping a tile whose video cannot come says why ? TODO

### 4y3. Call media failures, camera errors, data-saver words and voice-to-video (ERROR-105, ADR-078, ADR-079, 2026-10-06)

- `TODO` L1264: CALLMEDIA-01 - A busy microphone says so ? TODO
- `TODO` L1271: CALLMEDIA-02 - Denying the camera on an incoming video call joins audio-only ? TODO
- `TODO` L1278: CALLMEDIA-03 - A camera taken by another app mid-call shows a banner and can be retried ? TODO
- `TODO` L1285: CALLMEDIA-04 - A flip that cannot happen says so and does not stop the camera ? TODO
- `TODO` L1292: CALLMEDIA-05 - A group call joined without a camera ? TODO
- `TODO` L1299: GVID-09 - Tapping a tile under data saver or Show fewer says why nothing appears ? TODO
- `TODO` L1306: VUP-01 - A voice call becomes a video call (the first real check of the SDP risk) ? TODO
- `TODO` L1313: VUP-02 - A peer without `cv1` is never offered the button ? TODO
- `TODO` L1320: VUP-03 - Upgrading in the background-sensitive path keeps the microphone type ? TODO
- `TODO` L1327: VUP-04 - An upgrade during an ICE restart ? TODO

### 4z. Persistent log, export and swarm restart prompt (ADR-077, 2026-10-06)

- `TODO` L1336: LOG-01 - The log survives without adb ? TODO
- `TODO` L1343: LOG-02 - A crash is recorded ? TODO
- `TODO` L1350: LOG-03 - The log stays small ? TODO
- `TODO` L1356: SWM-34 - Restart prompt for the swarm switch ? TODO

### 4n. Group video audit (ERROR-097 / ADR-066, 2026-10-01)

- `TODO` L1369: GVID-01 — Turning the camera off stops the video to the people already watching (ERROR-097 #1) ? TODO
- `TODO` L1378: GVID-02 — A lost request is asked again (ERROR-097 #4) ? TODO
- `TODO` L1387: GVID-03 — A pinned peer that turns the request down leaves no ghost video on a one-video phone (ERROR-097 #6) ? TODO
- `TODO` L1394: GVID-04 — Native surfaces exist only for tiles with a picture (ERROR-097 #14) ? TODO
- `TODO` L1403: GVID-05 — A bad link costs only that leg its video, and the setting turns it off (ERROR-097 #9) ? TODO
- `TODO` L1414: GVID-06 — A leg that stays Disconnected after a Wi-Fi roam is rebuilt (ERROR-097 #16) ? TODO
- `TODO` L1423: GVID-07 — A muted talker does not evict a watcher; a departed peer holds no slot (ERROR-097 #5, #7) ? TODO

### 4o. Transsion / Infinix screen-off freeze: candidate fixes (owner's list, 2026-10-02)

- `TODO` L1440: HIB-01 — Which Phone Master activity opens the autostart screen on the Infinix? ? TODO
- `TODO` L1455: HIB-03 — First-run guide reaches the right screens (permissions, autostart, pairing) ? TODO (the flow does not exist yet; only the Settings toggle does)
- `TODO` L1461: HIB-04 — Does a Companion Device Manager association keep Flash running with the screen off? (experiment) ? TODO

### 4p. Hotspot client isolation (owner report 2026-10-02, FO-06)

- `TODO` L1473: HOT-01 — Which hotspot hosts isolate their joined phones? ? TODO
- `TODO` L1481: HOT-02 — Does a local-only hotspot avoid the isolation? ? TODO
- `TODO` L1487: HOT-03 — Which of our phones support Wi-Fi Aware? ? TODO

### 4q. In-call control dock: icons, state and motion (UI-050e, 2026-10-02)

- `TODO` L1499: CALLDOCK-01 — Do the new glyphs read correctly on a real screen? ? TODO
- `TODO` L1506: CALLDOCK-02 — HIGH tier: the motion plays and feels right ? TODO
- `TODO` L1513: CALLDOCK-03 — MEDIUM and LOW: no animation, same information ? TODO
- `TODO` L1520: CALLDOCK-04 — TalkBack ? TODO
- `TODO` L1527: CALLDOCK-05 — Five slots fit a small screen ? TODO

### 4r. In-call extras (UI-050f, ADR-067, 2026-10-02)

- `TODO` L1539: CALLX-01 — Audio output list with a headset ? TODO
- `TODO` L1547: CALLX-02 — Remote mic / camera badges (1:1 and group) ? TODO
- `TODO` L1554: CALLX-03 — Raise hand and reactions ? TODO
- `TODO` L1562: CALLX-04 — Data saver, 1:1 ? TODO
- `TODO` L1569: CALLX-05 — Data saver, group ? TODO
- `TODO` L1576: CALLX-06 — Picture-in-picture ? TODO
- `TODO` L1584: CALLX-07 — Keep screen on during video ? TODO
- `TODO` L1590: CALLX-08 — Proximity screen-off ? TODO
- `TODO` L1597: CALLX-09 — Mirror my video ? TODO
- `TODO` L1603: CALLX-10 — Connection chip and Verified shield ? TODO
- `TODO` L1610: CALLX-11 — Dock fits and the More panel on small screens, desktop included ? TODO
- `TODO` L1617: CALLX-12 — Old build compatibility and TalkBack ? TODO

### 4s. Feature-audit checks (docs/audit/2026-10-02-feature-completeness-audit.md, 2026-10-02)

- `TODO` L1629: FA-01 - Whole-file verification on every host (FA-1, ADR-068, ERROR-098) ? TODO
- `TODO` L1643: FA-02 - Out of space (FA-2, ADR-069, ERROR-099) ? TODO
- `TODO` L1654: FA-03 - Failure wording (FA-3, ADR-069, ERROR-099) ? TODO
- `TODO` L1664: FA-04 - Restart during a transfer (FA-4) ? TODO
- `TODO` L1671: FA-05 - Multiple files and a folder from the app (FA-5) ? TODO
- `TODO` L1677: FA-06 - Empty file (FA-6, ADR-069, ERROR-099) ? TODO

### 4t. Discovery module review (ERROR-100, `docs/audit/2026-10-03-discovery-module-review.md`, 2026-10-03)

- `TODO` L1691: DISC-01 - The beacon survives a network change (B1) ? TODO
- `TODO` L1700: DISC-02 - A changed address is shown (B5) ? TODO
- `TODO` L1707: DISC-03 - A restarted peer does not flicker out (B3) ? TODO
- `TODO` L1714: DISC-04 - One browse loop in ECO after a restart (B4) ? TODO
- `TODO` L1721: DISC-05 - A flood of fake devices is bounded (B7) ? TODO
- `TODO` L1730: DISC-06 - Dual-stack peer resolves to IPv4 (B10) ? TODO
- `TODO` L1737: DISC-07 - Does the multicast transport hear peers when NSD is idle on Android 14+? (B8, suspected) ? TODO
- `TODO` L1745: DISC-08 - A multicast-only peer survives one lost datagram (B11) ? TODO

### 4u. Transfer v2 / swarm / multi-file decision measurements (plan only, `docs/transfer/TRANSFER-V2-SWARM-AND-MULTIFILE-PLAN.md`, 2026-10-03)

- `TODO` L1756: SWM-01 - Group fan-out baseline today ? TODO
- `TODO` L1762: SWM-02 - Where the airtime goes ? TODO
- `TODO` L1768: SWM-03 - Can members reach each other? ? TODO
- `TODO` L1773: SWM-04 - What the sender spends per recipient ? TODO
- `BLOCKED` L1778: SWM-05 - Swarm vs direct fan-out (only after P5 exists) ? BLOCKED (nothing built)
- `BLOCKED` L1782: TV2-01 - v2 pull transfer vs v1 push on one pair (only after P4) ? BLOCKED (nothing built)
- `TODO` L1786: TV2-02 - The EXP-001 follow-ups ? TODO

### 4v. Group secret membership, settings and swarm checks (design only, `docs/transfer/GROUP-SWARM-DESIGN.md`, 2026-10-03)

- `TODO` L1803: GSEC-01 - Join with the code, never paired (G1) ? TODO (nothing built)
- `TODO` L1809: GSEC-02 - Wrong or stale code refused (G1) ? TODO (built in GM-6, needs device run)
- `TODO` L1814: GSEC-03 - Relay (man-in-the-middle) fails the proof (G1, mostly a unit/loopback test) ? TODO (nothing built)
- `TODO` L1818: GSEC-04 - Removal rotates the code (G1) ? TODO (built in GM-6, needs device run)
- `TODO` L1824: GSEC-05 - Join policy (G1, G2) ? TODO (nothing built)
- `TODO` L1829: GSEC-06 - Group beacon in the discovery record (G1, optional) ? TODO (nothing built)
- `TODO` L1833: GSEC-07 - Old version meets a secret group (G1) ? TODO (nothing built)
- `TODO` L1837: GSEC-08 - Gate is evaluated per request (G1/G5) ? TODO (nothing built)
- `TODO` L1842: GSET-01..GSET-04 - Group settings (G2) ? TODO (GM-9 built and unit-tested in `GroupSettingsTest`, pending devices)
- `TODO` L1848: SWM-06 - "Already have it" (G3) ? TODO (nothing built)
- `TODO` L1853: SWM-07 - Availability and "You can go offline now" (G6) ? TODO (SW-11 built and unit-tested in `FlashSwarmUiMathTest`, pending devices)
- `TODO` L1857: SWM-08 - Cross-group privacy (G3/G5) ? TODO (SW-4/SW-8 built and unit-tested in `SwarmEngineStepTest`, pending devices)

### 4w. Group swarm reliability checks (plan only, `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md`, 2026-10-04)

- `TODO` L1869: SWM-09 - Half sent, sender goes offline, the others sync, the sender returns (SW-8; the owner's scenario) ? TODO (SW-8 built and unit-tested in `SwarmInteropTest`, pending devices)
- `TODO` L1884: SWM-10 - Sender offline before anyone got anything (SW-8) ? TODO (SW-8 built and unit-tested in `SwarmEngineStepTest`, pending devices)
- `TODO` L1889: SWM-11 - The sender's cancel stops it everywhere, including a member that was offline (SW-9) ? TODO (SW-9 built and unit-tested in `SwarmCancelLifecycleTest`, pending devices)
- `TODO` L1901: SWM-12 - A receiver's own cancel affects only itself (SW-9) ? TODO (SW-9 built and unit-tested in `SwarmCancelLifecycleTest`, pending devices)
- `TODO` L1906: SWM-13 - The sender is killed or rebooted mid-send (SW-10) ? TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices). **2026-10-06:** the restart path itself did not exist until ERROR-102 (item 1) and is now implemented and unit-tested; this test is the first device proof
- `TODO` L1919: SWM-14 - A receiver is killed, rebooted or updated mid-download (SW-10) ? TODO (SW-10 built and unit-tested in `RoomSwarmStateStoreTest`, pending devices)
- `TODO` L1927: SWM-15 - A member removed mid-download (SW-9) ? TODO (SW-9 built and unit-tested in `SwarmHostLifecycleTest`, pending devices)
- `TODO` L1935: SWM-16 - Mixed versions (SW-8) ? TODO (SW-8 built and unit-tested in `ZeroChangeProofTest`, pending devices)
- `TODO` L1943: SWM-17 - Android's six-hour service limit pauses, never cancels (SW-9) ? TODO (SW-9 built and unit-tested in `TimeoutStopPlanTest`, pending devices)
- `TODO` L1959: SWM-18 - Not enough space, then space freed (SW-10) ? TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)
- `TODO` L1967: SWM-19 - A corrupt holder is detected and avoided (SW-10, needs a debug build flag that flips a byte in served pieces) ? TODO (SW-10 built and unit-tested in `StrikeBookTest`, pending devices)
- `TODO` L1975: SWM-20 - Windows as sender and as holder, including a restart (SW-8) ? TODO (SW-8/SW-10 built and unit-tested in `JvmPieceStorageTest`, pending devices)
- `TODO` L1980: SWM-21 - ECO device downloads but never serves (SW-8) ? TODO (SW-8 built and unit-tested in `SwarmHostLifecycleTest`, pending devices)
- `TODO` L1984: SWM-22 - Phone hotspot with client isolation (SW-10) ? TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)
- `TODO` L1989: SWM-23 - Swarm off changes nothing (SW-8; run first) ? TODO (SW-8 built and unit-tested in `ZeroChangeProofTest`, pending devices)
- `TODO` L1994: SWM-24 - What the sender sees (SW-11) ? TODO (SW-11 built and unit-tested in `FlashSwarmUiMathTest`, pending devices)
- `TODO` L1998: SWM-25 - The sender's file changed or deleted after half (SW-10) ? TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)
- `TODO` L2003: SWM-26 - Long run with churn (SW-10) ? TODO (SW-10 built and unit-tested in `SwarmWaitReasonRecoveryTest`, pending devices)
- `TODO` L2009: SWM-27 - A group call during a swarm (SW-10) ? TODO (SW-10 built and unit-tested in `SwarmEngineStepTest`, pending devices)
- `TODO` L2014: SWM-28 - Group send regression after the refactor (SW-1) ? TODO (SW-1 built and unit-tested in `GroupFileSenderTest`, pending devices)
- `TODO` L2018: SWM-29 - Old and new builds after the seams (SW-2) ? TODO (SW-2 built and unit-tested in `MagicFrameRouterTest`, pending devices)
- `OTHER` L2022: SW-4 Sans-IO Swarm Engine Verification (Unit & Property Tests) ? Unit & property verified (2026-10-04). No device checks claimed (device testing owed under SWM-06..SWM-27 in SW-7..SW-10).
- `OTHER` L2026: SW-6 Persistence Verification (Room Migration, DAO & State Store Tests) ? Unit verified (2026-10-04). No device checks claimed.
- `OTHER` L2030: SW-7 Storage I/O Verification (Unit & Functional Tests) ? Unit & functional verified (2026-10-04). Device checks owed under SWM-13 (physical content-URI streaming and restart resumption on Android).
- `OTHER` L2034: SW-8 Driver and Host Integration Verification (Unit & Interop Tests) ? Unit & interop verified (2026-10-04). Device checks owed under SWM-09, SWM-10, SWM-16, SWM-20, SWM-21, SWM-23.
- `OTHER` L2038: SW-9 Cancel Everywhere and Lifecycle Verification (Unit & Functional Tests) ? Unit & lifecycle verified (2026-10-04). Device checks owed under SWM-11, SWM-12, SWM-15, SWM-17.
- `OTHER` L2042: SW-10 Errors and Recovery Verification (Unit & Recovery Tests) ? Unit & recovery verified (2026-10-05). Device checks owed under SWM-13, SWM-14, SWM-18, SWM-19, SWM-22, SWM-25, SWM-26, SWM-27.
- `OTHER` L2046: SW-11 UI & Availability Presentation Verification (Unit & Logic Tests) ? Unit & logic verified (2026-10-05). Device checks owed under SWM-24, SWM-07.
- `TODO` L2050: SWM-30 - Partial downloads survive an app restart (ERROR-102 item 2) ? TODO
- `TODO` L2060: SWM-31 - The group's serve switches take effect (ERROR-102 item 3) ? TODO
- `TODO` L2070: SWM-32 - ECO mode and calls throttle serving (ERROR-102 item 4) ? TODO
- `TODO` L2079: SWM-33 - A cancel while the last piece is in flight, and a paired non-member (ERROR-102 items 6 and 8) ? TODO

### 4x. Group membership by id + secret, implementation plan (`docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` section 7B)

- `TODO` L2099: GMB-01 - Files reach a member that is not paired with the sender (GM-5) ? TODO (nothing built)
- `TODO` L2114: GMB-02 - Group-only peers stay out of 1:1 (GM-5, GINV-8) ? TODO (nothing built)
- `TODO` L2125: GMB-03 - A live group call leg ends when its member is removed (GM-5 task 6) ? TODO (nothing built)
- `TODO` L2136: GMB-04 - Invite link round trip (GM-2, GM-4, GM-8, GM-10) ? TODO (GM-10 UI and deep link handling built & verified; ready for physical device test in GM-11)
- `TODO` L2147: GMB-05 - Impostor at the inviter's id (GM-4, ripple 37) ? TODO (nothing built)
- `TODO` L2156: GMB-06 - Join while no admin is online, approved later by a co-owner (GM-4) ? TODO (nothing built)
- `TODO` L2169: GMB-07 - A member offline during a removal gets the new code from a non-admin (GM-6) ? TODO (built in GM-6, needs device run)
- `TODO` L2181: GMB-08 - Two admins rotate at the same time (GM-6) ? TODO (built in GM-6, needs device run)
- `TODO` L2192: GMB-09 - An existing v2 group gets a secret (GM-7, O-11) ? TODO (GM-7 built, unit/host-verified in GroupUpgradeTest; owed on physical devices)
- `TODO` L2200: GMB-10 - A member on an old build trusts a member who joined by invite (GM-4 task 10, ripple 40) ? TODO (nothing built)
- `TODO` L2206: GMB-11 - The secret never appears in logs (GM-2, GINV-1) ? TODO (nothing built)
- `TODO` L2216: GMB-12 - A removed device asks to rejoin under "open" (GM-4, GINV-5) ? TODO (nothing built)
- `TODO` L2227: GMB-13 - Secret store lost on one device (GM-6, M-17) ? TODO (built in GM-6, needs device run)
- `TODO` L2236: GMB-14 - Invite sent inside a 1:1 Flash chat (GM-10, O-14, optional) ? TODO (built in GM-10 via FlashInlineInviteCard; ready for physical device test in GM-11)

### 4ze. Swarm non-member frames and row naming (ERROR-110, ERROR-111, 2026-10-06)

- `TODO` L2244: SWM-43 - A device outside the group is not counted ? TODO
- `TODO` L2251: SWM-44 - The Transfers row names the group ? TODO

### 4zd. Swarm origin source lost (ERROR-109, 2026-10-06)

- `TODO` L2260: SWM-40 - A deleted source is reported, not silent ? TODO
- `TODO` L2267: SWM-41 - A member that was offline hears the loss ? TODO
- `TODO` L2274: SWM-42 - Restart keeps the loss ? TODO

### 4zc. Swarm file offers and catch-up (ERROR-108, ADR-081, 2026-10-06)

- `TODO` L2282: SWM-36 - A live swarm offer is accepted and the bubble follows the transfer ? TODO
- `TODO` L2289: SWM-37 - A member that was offline gets the file offer ? TODO
- `TODO` L2296: SWM-38 - A member relays the offer while the author is offline ? TODO
- `TODO` L2302: SWM-39 - Upgrade keeps old rows ? TODO

### 4zb. Swarm first contact (ERROR-107, 2026-10-06)

- `TODO` L2311: SWM-35 - A fresh swarm send starts without a reconnect ? TODO

### 4zf. Join by invite and members-may-add (ERROR-112, ERROR-113, ERROR-114, 2026-10-06)

- `TODO` L2322: GJOIN-01 - A device added by a plain member joins ? TODO
- `TODO` L2329: GJOIN-02 - Pasting an invite while already connected to the inviter ? TODO
- `TODO` L2336: GJOIN-03 - Approval while the joiner is disconnected ? TODO
- `TODO` L2343: GJOIN-04 - An old invite link says it is out of date ? TODO
- `TODO` L2350: GJOIN-05 - The invite dialog names the inviter, the admin is warned about strangers ? TODO
- `TODO` L2357: GJOIN-06 - A wrong device at the hinted address is turned away ? TODO

### 4za. Ink launch splash (UI-056, ADR-080, 2026-10-06)

- `TODO` L2368: SPLASH-01 - Android cold start, light and dark ? TODO
- `TODO` L2373: SPLASH-02 - No replay on a warm return ? TODO
- `TODO` L2378: SPLASH-03 - Rotation mid-splash ? TODO
- `TODO` L2383: SPLASH-04 - Setting off (Android and desktop) ? TODO
- `TODO` L2388: SPLASH-05 - Answering a call or pressing PTT skips the splash ? TODO
- `TODO` L2393: SPLASH-06 - Slow device ceiling (Belfone) ? TODO
- `TODO` L2398: SPLASH-07 - Reduced motion ? TODO
- `TODO` L2403: SPLASH-08 - Desktop start, tray reopen, dynamic accent ? TODO

### 4y. UI polish and feature flows (`docs/ui/UI-POLISH-AND-FEATURE-ROADMAP-PLAN.md`, 2026-10-06)

- `TODO` L2412: UIP-01 — Native In-App Forwarding Sheet ? TODO
- `TODO` L2418: UIP-02 — Composer Attachment Pre-Send Staging Tray ? TODO
- `TODO` L2424: UIP-03 — Per-Conversation Shared Content Viewer ? TODO
- `TODO` L2430: UIP-04 — Pinned Messages Banner ? TODO
- `TODO` L2436: UIP-05 — Real-Time Audio Speaking Ripple in Calling UI ? TODO
- `TODO` L2442: UIP-06 — Swarm Transfer Block Availability Grid Map ? TODO
- `TODO` L2448: UIP-07 — Voice Recording Gesture Physics ? TODO
- `TODO` L2454: UIP-08 — Adaptive Detail Panes Redesign ? TODO
- `TODO` L2460: UIP-09 — Transfers Screen Refinements ? TODO
- `TODO` L2466: UIP-10 — Bottom Nav Auto-Hide & Motion Physics ? TODO
- `TODO` L2475: UIP-11 - Swarm piece map shows real state and appears only for a swarm ? TODO
- `TODO` L2481: UIP-12 - Pins are stored, several, and device-local ? TODO
- `TODO` L2487: UIP-13 - 1:1 call ripple follows the peer's voice ? TODO
- `TODO` L2493: UIP-14 - Camera capture asks for permission and says when it fails ? TODO
- `TODO` L2499: UIP-15 - Forward to several chats and to a group ? TODO
- `TODO` L2505: UIP-16 - Settings search and reset; Transfers "Clear history" ? TODO
- `TODO` L2511: UIP-17 - Link tap opens the link ? TODO
- `TODO` L2517: UIP-18 - Unpaired group-call members show their roster names ? TODO
- `TODO` L2523: UIP-19 - Bottom bar hides on a fast flick only ? TODO
- `TODO` L2529: UIP-20 - Detail-pane progress shimmer scales with speed ? TODO
- `TODO` L2535: MIG-12 - Upgrade a v11 install to v12 ? TODO

### 4zg. Group files in a star topology (ERROR-117, 2026-10-06)

- `TODO` L2543: SWM-40 - A vouched member gets a swarm file ? TODO
- `TODO` L2549: SWM-41 - A vouched member that connects later is offered the file ? TODO
- `TODO` L2555: SWM-42 - A member without the swarm switch gets no unusable bubble ? TODO
- `TODO` L2561: SWM-43 - Voice note reaches a member that was offline (signed group) ? TODO
- `TODO` L2567: SWM-44 - Catch-up of a message from a faster clock ? TODO
- `TODO` L2573: SWM-45 - Why a paired admin got a whole-file push instead of a swarm ? TODO
- `TODO` L2579: SWM-46 - A fresh install has the swarm on, and an opt-out sticks ? TODO
- `TODO` L2585: SWM-47 - A group file goes through the swarm on a fresh install without touching settings ? TODO
- `TODO` L2595: RVH-01 - A revoked device sees its revocation through a v3 list ? TODO
- `TODO` L2601: RVH-02 - An older phone build against the new hub ? TODO
- `TODO` L2607: RVH-03 - `keytool revoke` then console install ? TODO
- `TODO` L2612: MBX-01 - Hub restart with legacy plain mailbox rows ? TODO
- `TODO` L2618: MBX-02 - Restore the database without `data.key` ? TODO
- `TODO` L2623: CDK-01 - Desktop chat key migrates on a real Windows profile ? TODO
- `TODO` L2629: CDK-02 - A copied profile does not open ? TODO
- `TODO` L2634: APPR-01 - Bind an approver phone ? TODO
- `TODO` L2640: APPR-02 - Install a delegation with the phone ? TODO
- `TODO` L2645: APPR-03 - Cancel and timeout ? TODO
- `TODO` L2650: APPR-04 - A request someone else raised ? TODO
- `TODO` L2655: APPR-05 - The phone is revoked (fail closed) ? TODO
- `TODO` L2660: APPR-06 - Lost phone: remove it with a recovery code ? TODO
- `TODO` L2665: APPR-07 - Replace the console key with the phone ? TODO
- `TODO` L2670: APPR-08 - Replace the approver phone ? TODO

### 4zzzzzzzzzzzz Search — UI-023 / UI-024 upgrade (2026-10-20, ADR-218)

- `TODO` L2680: SEARCH-01 — Find a file by its name from the chat list ? TODO
- `TODO` L2692: SEARCH-02 — Find a word that only exists deep in a thread ? TODO
- `TODO` L2702: SEARCH-03 — Scope chips narrow and count correctly ? TODO
- `TODO` L2715: SEARCH-04 — "See all" and the three-row preview ? TODO
- `TODO` L2725: SEARCH-05 — Jumping to a hit, and Back afterwards ? TODO
- `TODO` L2735: SEARCH-06 — Recent searches persist and clear ? TODO
- `TODO` L2744: SEARCH-07 — In-chat search finds files, captions and links ? TODO
- `TODO` L2754: SEARCH-08 — The in-chat results drawer ? TODO
- `TODO` L2764: SEARCH-09 — In-chat scope chips ? TODO
- `TODO` L2773: SEARCH-10 — Shared content is searchable per tab ? TODO
- `TODO` L2784: SEARCH-11 — Desktop parity ? TODO
- `TODO` L2794: SEARCH-12 — Long history, accessibility and appearance ? TODO

### 4.W4. Wave 4 desktop/kernel functional checks (reviewed 2026-11-01)

- `TODO` L2811: PERF-E03 — Hidden-window GHOST, workload START/FINISH and liveness (OPT-O19-07) ? TODO (automated transitions pass; physical packet/PTT/liveness not run).
- `TODO` L2824: PERF-E06 — Actual AWT drop uses current repository/destination ? TODO (structural regression/compilation pass; actual AWT event not run).
- `TODO` L2834: PERF-E07 — Stored-ON radio survives tab/window/tray lifetime ? TODO (host checks pass; hardware not run).
- `TODO` L2844: PERF-E08 — Live Windows reduced motion reaches window/toast ? TODO (mapping/Flow/read-only Windows API query pass; visual toggle not run).
- `TODO` L2855: PERF-K05 — Physical Android JCA parity and failure recovery ? TODO (JVM/Android-host parity/concurrency pass; physical providers not run).

### 4zi. Evidence probes and the log export header (ADR-087, 2026-10-06)

- `TODO` L2970: LOGX-01 - The Android export starts with a header and a snapshot ? TODO
- `TODO` L2976: LOGX-02 - Session and group message probes appear during normal chat ? TODO
- `TODO` L2982: LOGX-03 - A file fan-out says who got a swarm offer ? TODO
- `TODO` L2988: LOGX-04 - The desktop log opens with its header ? TODO
- `TODO` L2993: LOGX-05 - Probes contain no secrets or content ? TODO

### 4zj. Group settings review fixes (ERROR-118, ADR-088, 2026-10-07)

- `TODO` L3000: GSUI-01 - Group settings opens from Group Info ? TODO
- `TODO` L3006: GSUI-02 - Invite link failure is visible, and a stale link never shows ? TODO
- `TODO` L3011: GSUI-03 - Desktop has no dead Share button ? TODO
- `TODO` L3016: GSUI-04 - Creating a group above six devices ? TODO
- `TODO` L3022: GSUI-05 - Quick taps on group settings are not lost ? TODO
- `TODO` L3027: GSUI-06 - Resize across 840 dp keeps a sensible detail pane (review claim, not reproduced) ? TODO
- `TODO` L3032: GSUI-07 - TalkBack on the Nearby radar during a scan (review claim, not reproduced) ? TODO

### 4zk. Group file: open, sender's member view, start (ERROR-119, ADR-089, 2026-10-07)

- `TODO` L3039: SWO-01 - A finished received group file opens on every receiver ? TODO
- `TODO` L3045: SWO-02 - The sender sees who has the file ? TODO
- `TODO` L3050: SWO-03 - Leaving once everyone has it ? TODO
- `TODO` L3055: SWO-04 - The first member to accept starts at once ? TODO
- `TODO` L3060: SWO-05 - Offer prefetch does not move file data or leak into the offer card ? TODO
- `TODO` L3065: SWO-06 - Speed and ETA on the receiver, probes in the export ? TODO
- `TODO` L3070: SWO-07 - A swarm-off member is told there is a file ? TODO
- `TODO` L3076: SWO-08 - A member offline for hours gets the offer on reconnect ? TODO
- `TODO` L3081: SWO-09 - Offline more than 24 hours ? TODO
- `TODO` L3086: SWO-10 - Voice note or tiny file to a member that was offline (documents a limit) ? TODO
- `TODO` L3091: SWO-11 - A late offer starts at once ? TODO
- `TODO` L3097: SWO-12 - Desktop started after others finished ? TODO
- `TODO` L3102: SWO-13 - New member of a busy group ? TODO
- `TODO` L3108: SWO-14 - No probe noise on restart ? TODO

### 4zl. Enterprise realm, radio/Bluetooth and Linux plans (docs only, 2026-10-07)

- `TODO` L3124: ENT-01 - Realm gate refuses a stranger (stage E1) ? TODO
- `TODO` L3127: ENT-02 - Enrol a device (stage E2) ? TODO
- `TODO` L3130: ENT-03 - Revoke a device and refuse it on reconnect (stage E2) ? TODO
- `TODO` L3133: ENT-04 - Cross-subnet connection from a roster hint (stage E3) ? TODO
- `TODO` L3136: ENT-05 - Hub authenticates by identity-key challenge (stage E4) ? TODO
- `TODO` L3139: ENT-06 - Sealed 1:1 message through the hub (stage E4) ? TODO
- `TODO` L3142: ENT-07 - LAN-first duplicate shows once (stage E4) ? TODO
- `TODO` L3145: ENT-08 - Mailbox TTL and quotas (stage E4) ? TODO
- `TODO` L3148: ENT-09 - Enrol by link with the root offline (stage E2b) ? TODO
- `TODO` L3151: ENT-10 - Server revokes a member (stage E2b) ? TODO
- `TODO` L3154: ENT-11 - Server-issued admin cert is refused (stage E2b) ? TODO
- `TODO` L3157: ENT-12 - Expired delegation and lapsed cert (stage E2b, D-E9) ? TODO
- `TODO` L3160: ENT-13 - Restricted invite lists only allowed groups (stage E2c) ? TODO
- `TODO` L3163: ENT-14 - Hidden group is never listed (stage E2c) ? TODO
- `TODO` L3166: ENT-15 - Open join (stage E2c) ? TODO
- `TODO` L3169: ENT-16 - Request join waits for a group admin (stage E2c) ? TODO
- `TODO` L3172: ENT-17 - Department admin scope (stage E2c) ? TODO
- `TODO` L3175: ENT-18 - Dashboard web login, step-up and signed audit log (stage E2c) ? TODO
- `NO_STATUS` L3178: GSY-01...GSY-09 - Group sync revamp ? (no status)
- `TODO` L3181: LNX-01...LNX-10 - Linux desktop port (owner confirmed Linux as a target 2026-10-08, D-L1) ? TODO

### 4zo. Screen share in calls (ADR-102, 2026-10-09)

- `NO_STATUS` L3237: SHARE-01 - Windows screen to a phone (1:1) ? (no status)
- `NO_STATUS` L3241: SHARE-02 - Indicator and stop ? (no status)
- `NO_STATUS` L3245: SHARE-03 - Legibility of 10 pt text ? (no status)
- `NO_STATUS` L3249: SHARE-04 - CPU camera vs share at 1, 2 and 4 watchers (EXP-024) ? (no status)
- `NO_STATUS` L3254: SHARE-05 - Watcher cap and strain ? (no status)
- `NO_STATUS` L3258: SHARE-06 - Stop share does not crash (Windows and Linux) ? (no status)
- `NO_STATUS` L3262: SHARE-07 - Camera -> share -> camera ? (no status)
- `NO_STATUS` L3266: SHARE-08 - Wayland picker ? (no status)
- `NO_STATUS` L3270: SHARE-09 - One presenter and take-over ? (no status)
- `NO_STATUS` L3274: SHARE-10 - Closing the shared window ? (no status)
- `NO_STATUS` L3278: SHARE-11 - Resize / move the shared window ? (no status)
- `NO_STATUS` L3281: SHARE-12 - Mixed builds ? (no status)
- `NO_STATUS` L3285: SHARE-13 - Late joiner and rebuilt leg ? (no status)
- `NO_STATUS` L3289: SHARE-14 - Call ends / app closes while sharing ? (no status)

### 4zp. Radio link tooling (BT-00 updated, BT-09..BT-17, 2026-10-09)

- `TODO` L3301: BT-09 - Spike window opens and the simulated radio runs (no hardware) ? TODO
- `TODO` L3304: BT-10 - KISS transparency through the real TNC ? TODO
- `TODO` L3307: BT-11 - A full 220-byte Flash frame is accepted and heard ? TODO
- `TODO` L3310: BT-12 - Burst goodput and round trip at 1200 baud (feeds EXP-023) ? TODO
- `TODO` L3313: BT-13 - Link loss and reconnect ? TODO
- `TODO` L3316: BT-14 - One app at a time ? TODO
- `TODO` L3319: BT-15 - Station label rotation (privacy) ? TODO
- `TODO` L3322: BT-16 - Android RFCOMM to the radio (BLOCKED: needs a debug entry) ? TODO
- `TODO` L3325: BT-17 - Flash-to-Flash over RFCOMM (BLOCKED: not built) ? TODO

### 4zq. Group history sync (ADR-100, 2026-10-09)

- `TODO` L3332: GSY-01...GSY-12 - Group history sync (ADR-100, 2026-10-09; unit-tested, none device-verified) ? TODO

### 4zt. Fixes: group sync, transfer, security (2026-10-09)

- `NO_STATUS` L3361: GSY-13...GSY-16, HARD-01...HARD-05 - Sweep fixes (2026-10-09, unit-tested, none device-verified) ? (no status)

### 4zu. Linux: leave and rejoin a group call (ERROR-168, 2026-10-09)

- `NO_STATUS` L3398: LNXCALL-01 - Rejoin after leaving a group call on Linux ? (no status)

### 4zv. Transsion: daemon-process probe and set-up guide (HIB-10, HIB-03, 2026-10-09)

- `NO_STATUS` L3406: HIB-10 - Does a second process (`:lan_daemon`) survive the Hiber freezer? ? (no status)
- `NO_STATUS` L3423: HIB-11 - Does an inbound network packet thaw Flash while the screen is off? ? (no status)
- `NO_STATUS` L3432: HIB-13 - Does traffic >= 10 KB/s plus the foreground notification keep Flash unfrozen at screen-off? ? (no status)
- `FAIL` L3455: HIB-15 — Does an isolated service with `android:useAppZygote="true"` keep Flash unfrozen without audio? ? FAILED / TRANSIENT ONLY (2026-10-09).
- `OTHER` L3463: HIB-16 — Home Screen AppWidget exemption (`STATUS_WIDGET`, Bit 6 = 64) ? SCREEN-ON ONLY / INSUFFICIENT FOR SCREEN-OFF (2026-10-10).
- `OTHER` L3470: HIB-17 — Floating Overlay Window exemption (`float_window`, Bit 2 = 4) ? SCREEN-ON PASS / SCREEN-OFF FAIL (2026-10-10).

### 4zw. Enterprise hub: relay wire v2, bearer, enrolment proof (ADR-107..110, ERROR-170..174, 2026-10-12)

- `TODO` L3487: HUB-01 - A real phone completes the v2 relay handshake against a real hub ? TODO
- `TODO` L3493: HUB-02 - Message relay between two enrolled devices, sender is stamped ? TODO
- `TODO` L3499: HUB-03 - Offline recipient is queued, then delivered after it reconnects ? TODO
- `TODO` L3504: HUB-04 - An expired or revoked device is refused with the right code ? TODO (revoked case BLOCKED on Phase 2)
- `TODO` L3509: HUB-05 - A client pinned to organisation A refuses hub B ? TODO
- `TODO` L3514: HUB-06 - Directory browse over the bearer ? TODO (the app wiring of the bearer is a Phase 3 item; until then run the `curl` half only)
- `TODO` L3519: HUB-07 - Enrol through a QR/`flash://enroll` link with proof of possession ? TODO
- `TODO` L3524: HUB-08 - Certificate lifetime clamp ? TODO
- `TODO` L3529: HUB-09 - Directory shapes: counts and approval state show in the app ? TODO
- `TODO` L3534: HUB-10 - Non-ASCII payload through the relay ? TODO
- `TODO` L3539: HUB-11 - Silent socket is cut at the handshake deadline ? TODO
- `TODO` L3544: HUB-12 - Sustained background connection survives a bearer and certificate boundary ? TODO
- `TODO` L3549: HUB-13 - A hub-routed 1:1 chat shows the colleague Online (relayed), and its typing indicator ? TODO
- `TODO` L3555: HUB-14 - A presence-hidden colleague claims nothing, and an old hub degrades safely ? TODO

### 4zx. Enterprise hub relay: delivery, mailbox, limits, operations (ADR-112, ERROR-230..237, 2026-10-12)

- `TODO` L3568: HUBB-01 - 500 simulated sessions ? TODO (measurement, backlog section 5 rule)
- `TODO` L3573: HUBB-02 - 100,000 queued rows ? TODO
- `TODO` L3578: HUBB-03 - `kill -9` during a drain ? TODO
- `TODO` L3583: HUBB-04 - Half-open socket by a real network cut ? TODO
- `TODO` L3588: HUBB-05 - Transsion / Hiber reconnect (HIB-10 family) ? TODO
- `TODO` L3593: HUBB-06 - Reconnect race on a real network switch ? TODO
- `TODO` L3598: HUBB-07 - Revoke a connected device ? TODO on a device (2026-10-13: the revoke wiring exists in `HubServices`; the real-client path, kick within the bound, REVOKED status, dead bearer and re-enrol is automated in `E2eLifecycleContractTest`; the phone-in-hand version is HUBE-04)
- `TODO` L3603: HUBB-08 - Group fan-out in a real department group ? TODO (blocked on the client wiring; the server side with 4 members, one offline and a non-member is automated in `E2eGroupAndLimitsContractTest.aGroupMessageReachesLiveMembers...`: aggregate ACK `delivered=1 queued=1 failed=0`, replay carri
- `TODO` L3608: HUBB-09 - Mailbox expiry notice ? TODO (2026-10-13: `HubServices` does wire `mailboxTtlDays` and the stored lifetime is checked in `E2eRelayContractTest.theMailboxLifetimeFollowsTheHubClock...`; the expiry sweep and `QUEUE_STATE EXPIRED` on a device are still owed)
- `TODO` L3613: HUBB-10 - Oversized and fragmented frames ? TODO
- `TODO` L3618: HUBB-11 - Management endpoints behind a reverse proxy ? TODO
- `TODO` L3623: HUBB-12 - Graceful shutdown with live sessions ? TODO

### 4zy. Enterprise hub identity, revocation, TLS, admin (ADR-111, ADR-113..115, ERROR-200..212, 2026-10-13)

- `TODO` L3633: HUBA-01 - Offline root, keytool, delegation, first enrolment ? TODO
- `TODO` L3639: HUBA-02 - Awaiting-delegation mode and hot reload of a replacement ? TODO
- `TODO` L3644: HUBA-03 - Delegation expiry warnings and expiry ? TODO
- `TODO` L3649: HUBA-04 - Real invites, duplicate ids, revocation of an invite ? TODO
- `TODO` L3654: HUBA-05 - Restart persistence ? TODO
- `TODO` L3659: HUBA-06 - Directory over the bearer, scoped by the enrolled department ? TODO
- `TODO` L3664: HUBA-07 - Revoke a connected phone from the console ? TODO
- `TODO` L3669: HUBA-08 - TLS `self` mode with a phone ? TODO
- `TODO` L3674: HUBA-09 - Behind a reverse proxy: forwarded headers, rate limits, management listener ? TODO
- `TODO` L3679: HUBA-10 - Docker image ? TODO
- `TODO` L3684: HUBA-11 - Admin login with a real authenticator ? TODO
- `BLOCKED` L3689: HUBA-12 - Step-up confirmation from the admin's enrolled device ? BLOCKED (client/UI phase). 2026-10-13 UPDATE: the console now has a browser signer (ADR-125, ERROR-340); the real-browser flow signed and revoked serial 4242 (P1363 accepted). A PHONE as signer is not built: see HUBU-11. Re-test with the co
- `TODO` L3694: HUBA-13 - Audit chain survives a restart and detects tampering ? TODO
- `BLOCKED` L3699: HUBA-14 - A phone fetches and honours the signed revocation list ? BLOCKED (client phase consumes the list)

### 4zz. Enterprise hub, phone and desktop client (ADR-120..123, ERROR-260..270, 2026-10-13)

- `TODO` L3708: HUBC-01 - Enrol over wss, Settings card shows the live state ? TODO
- `TODO` L3713: HUBC-02 - Leave the organisation, offline leave, restore groups ? TODO
- `TODO` L3718: HUBC-03 - Hub restart, reconnect spread, revocation, renewal, slow collector ? TODO
- `TODO` L3723: HUBC-04 - LAN first with a 2 s grace, hub-only delivery, hub-held message ? TODO
- `OTHER` L3728: HUBC-05 - Sealed content once the hub publishes sealing keys ? UNBLOCKED IN CODE 2026-10-13 (ADR-126): both hosts now generate, protect and publish a key and build a `HubSealingPort`; unit-tested, tested against the real hub in `E2eHostSealingLifecycleTest`, NOT device-verified: run HUBH-01 (and HUBE-0
- `OTHER` L3734: HUBC-06 - A sealed message that was changed or re-sent is refused ? UNBLOCKED IN CODE 2026-10-13 (ADR-126; the recipient counts a refused message in "Messages not opened", see HUBH-05); NOT device-verified. History: BLOCKED on the HOSTS (see HUBC-05); the byte-flip, re-id and impersonation refusals are auto
- `TODO` L3739: HUBC-07 - Acknowledge only after the message is stored (kill the app mid-delivery) ? TODO
- `TODO` L3744: HUBC-08 - Screen-off redelivery on a Transsion / Samsung phone ? TODO
- `TODO` L3749: HUBC-09 - Join a group that needs approval ? TODO
- `TODO` L3754: HUBC-10 - Admin approves and declines inside Flash ? TODO
- `TODO` L3759: HUBC-11 - Members who are not paired with each other (known limitation) ? TODO

### 4zzz. Real phone + real hub: the client/server contract on a device (ADR-124, ERROR-330..337, 2026-10-13)

- `TODO` L3768: HUBE-01 - Enrol on a phone, one connection, no kick loop ? TODO
- `TODO` L3774: HUBE-02 - Invite refusals and join workflow read correctly on the phone ? TODO
- `TODO` L3779: HUBE-03 - Send to a colleague who is offline, then bring them back ? TODO
- `TODO` L3784: HUBE-04 - Revoke from the console and watch the phone ? TODO
- `TODO` L3789: HUBE-05 - Sealed message between two phones (needs a host that publishes keys) ? TODO (was BLOCKED on host work; host work built 2026-10-13, see HUBH-01)
- `TODO` L3795: HUBE-06 - Hub restart with phones connected ? TODO
- `TODO` L3800: HUBE-07 - Certificate renewal window on a phone ? TODO
- `TODO` L3805: HUBE-08 - Unenrol while the hub is unreachable, then reachable ? TODO

### 4zzzz. Hub admin console (UI agent, 2026-10-13, ADR-125, `docs/ui/hub-admin-console.md`)

- `TODO` L3816: HUBU-01 - Browser matrix ? TODO
- `TODO` L3821: HUBU-02 - Keyboard-only and screen reader ? TODO
- `TODO` L3826: HUBU-03 - Zoom, text size and print ? TODO
- `TODO` L3831: HUBU-04 - Air-gapped load ? TODO
- `TODO` L3836: HUBU-05 - Devices with data and 5,000 rows ? TODO
- `TODO` L3841: HUBU-06 - Invites in full ? TODO
- `TODO` L3846: HUBU-07 - Organization with data ? TODO
- `TODO` L3851: HUBU-08 - Delegation install flow ? TODO
- `TODO` L3856: HUBU-09 - Account flows ? TODO
- `TODO` L3861: HUBU-10 - Session expiry and idle ? TODO
- `BLOCKED` L3866: HUBU-11 - Phone as step-up signer (HUBA-12) ? BLOCKED (owner decision)

### 4zzzzz. Hub host sealing: key store, publication, sealed chat, changed keys (ADR-126, ERROR-360..363, 2026-10-13)

- `TODO` L3876: HUBH-01 - Two devices exchange a sealed message, switch OFF ? TODO
- `TODO` L3882: HUBH-02 - A colleague's key changes after a reinstall ? TODO
- `TODO` L3887: HUBH-03 - Publication retries while offline and survives a rate limit ? TODO
- `TODO` L3892: HUBH-04 - The key is protected at rest (Android Keystore wrapper) and never backed up ? TODO
- `TODO` L3897: HUBH-05 - Notices read calmly and honestly ? TODO
- `TODO` L3902: HUBH-06 - Revoke and unenrol wipe the key ? TODO
- `TODO` L3907: HUBH-07 - Windows desktop key file and DPAPI ? TODO
- `TODO` L3912: HUBH-08 - A held message survives restart and is released by Continue ? TODO

### 4zzzzzz. Hub second-pass review fixes (REV-01..REV-16, ADR-127, ERROR-380..395, 2026-10-13)

- `TODO` L3936: HUBR-09 — Docker image refuses a first delegation without a pin ? TODO
- `TODO` L3942: HUBR-10 — A device's acknowledgement deletes only the named sender's mailbox row ? TODO
- `TODO` L3948: EXTR-01 — After the `:core:enterprise` extraction nothing behaves differently ? TODO

### 4zzzzzzzzz. Hub integration plans: offline organisation trust, department heads, directory privacy, announcements, Hub tab (ADR-133..138, 2026-10-14)

- `TODO` L3962: ORGT-01 — Chat with an unpaired colleague on the LAN with no internet after one online sync ? TODO (B2 engine and gates built and unit/mutation verified in ORGT-U20..U32; physical device check owed)
- `TODO` L3968: ORGT-02 — Lease expiry ? TODO (B2 lease math and expiry gates built and unit verified in ORGT-U24; physical device check owed)
- `TODO` L3974: ORGT-03 — TOFU spoof attempt ? TODO (B2 resolver pin replacement built and unit verified in ORGT-U22/U23; physical device check owed)
- `TODO` L3980: ORGT-04 — Revoked colleague ? TODO (B2 documented snapshot semantics verified in ORGT-U30; physical device check owed)
- `TODO` L3986: ORGT-05 — Hub restored from an older backup ? TODO (B1/B2 epoch and version checks built; physical device check owed)
- `TODO` L3992: ORGT-06 — 5,000-member sync ? TODO (B1 engine built, unit benchmark ORGT-U16 passed in EXP-022; physical device check owed)
- `TODO` L3998: ORGT-07 — Airplane mode in the middle of a sync ? TODO (B1 atomic multi-page staging built, unit verified in ORGT-U10; physical device check owed)
- `TODO` L4004: ORGT-08 — Desktop and phone offline colleague chat ? TODO (B2 desktop wiring complete, unit verified in ORGT-U31; physical device check owed)
- `TODO` L4010: ORGT-09 — Push-to-talk refused for an ORG peer ? TODO (B2 capability separation built, unit verified in ORGT-U25; physical device check owed)
- `TODO` L4016: ORGT-10 — Call ring from an ORG peer ? TODO (B2 mayRing capability built and unit verified in ORGT-U25; physical device check owed)
- `TODO` L4022: ORGT-11 — Hub group on the LAN (after Plan B phase B4 and owner acceptance of ADR-135) ? TODO (PLANNED, not built)
- `TODO` L4028: ORGT-12 — Removal propagation (after B4/B5) ? TODO (PLANNED, not built)
- `TODO` L4034: ORGT-13 — ECO mode wake for an ORG send ? TODO (PLANNED, not built)
- `TODO` L4040: ORGT-14 — Identity key regenerated ? TODO (PLANNED, not built)
- `TODO` L4059: DEPH-01 — Head logs into the console ? TODO (PLANNED, not built)
- `TODO` L4065: DEPH-02 — Head invites an employee ? TODO (PLANNED, not built)
- `TODO` L4071: DEPH-03 — Org admin invites a head ? TODO (PLANNED, not built)
- `TODO` L4077: DEPH-04 — Disable a head while logged in ? TODO (PLANNED, not built)
- `TODO` L4083: DEPH-05 — Two heads in one department ? TODO (PLANNED, not built)
- `TODO` L4089: DEPH-06 — Invite first head after creating a department ? TODO (PLANNED, not built)
- `TODO` L4095: DEPH-07 — Org admin moves a department-less device into a department from the console ? TODO (console change is Node-tested only, `server/admin-ui-tests`)
- `TODO` L4103: PRIV-01 — Outsider cannot find a hidden department ? TODO (PLANNED, not built)
- `TODO` L4109: PRIV-02 — Insider still sees it ? TODO (PLANNED, not built)
- `TODO` L4115: PRIV-03 — Hidden-department member messages an outsider ? TODO (PLANNED, not built)
- `TODO` L4121: PRIV-04 — Toggling hidden propagates ? TODO (PLANNED, not built)
- `TODO` L4127: PRIV-05 — Old app with new hub ? TODO (PLANNED, not built)
- `TODO` L4135: ANN-01 — Admin posts company-wide from the console ? TODO (PLANNED, not built)
- `TODO` L4141: ANN-02 — Head posts to own department from the app ? TODO (PLANNED, not built)
- `TODO` L4147: ANN-03 — Head cannot post company-wide ? TODO (PLANNED, not built)
- `TODO` L4153: ANN-04 — Offline phone catches up ? TODO (PLANNED, not built)
- `TODO` L4159: ANN-05 — Search ? TODO (PLANNED, not built)
- `TODO` L4165: ANN-06 — Edit and delete propagate ? TODO (PLANNED, not built)
- `TODO` L4171: ANN-07 — Pinned order and limit ? TODO (PLANNED, not built)
- `TODO` L4177: ANN-08 — Hub restored from backup ? TODO (PLANNED, not built)
- `TODO` L4183: ANN-09 — Old app with new hub ? TODO (PLANNED, not built)
- `TODO` L4189: ANN-10 — Notification tap on a cold start ? TODO (PLANNED, not built)
- `TODO` L4195: ANN-11 — Background delivery (experiment) ? TODO (PLANNED, not built)
- `TODO` L4203: HUBT-01 — Enrol: Hub replaces Transfers ? TODO (PLANNED, not built)
- `TODO` L4209: HUBT-02 — Unenrol or revoke ? TODO (PLANNED, not built)
- `TODO` L4215: HUBT-03 — 5,000 people ? TODO (PLANNED, not built)
- `TODO` L4221: HUBT-04 — Message an unpaired colleague (hub online) ? TODO (PLANNED, not built)
- `TODO` L4227: HUBT-05 — Same offline on the LAN (needs Plan B) ? TODO (PLANNED, not built)
- `TODO` L4233: HUBT-06 — News flow (needs Plan A3) ? TODO (PLANNED, not built)
- `TODO` L4239: HUBT-07 — Hidden department (needs Plan A2) ? TODO (PLANNED, not built)
- `TODO` L4245: HUBT-08 — Tablet rail and detail pane ? TODO (PLANNED, not built)
- `TODO` L4251: HUBT-09 — Desktop sidebar and Transfers button ? TODO (PLANNED, not built)
- `TODO` L4257: HUBT-10 — Accessibility pass ? TODO (PLANNED, not built)
- `TODO` L4263: HUBT-11 — Process death on Hub ? TODO (PLANNED, not built)
- `TODO` L4269: HUBT-12 — Notification tap to Hub on a cold start ? TODO (PLANNED, not built)
- `TODO` L4275: HUBT-13 — Upgrade from the old layout ? TODO (PLANNED, not built)
- `TODO` L4281: HUBT-14 — Older hub fallback ? TODO (PLANNED, not built)

### PRIV — Directory privacy, option C (Plan A2, S2 built 2026-10-14, unit-tested only)

- `NO_STATUS` L4368: PRIV-01 — outsider cannot find a hidden department ? (no status)
- `NO_STATUS` L4373: PRIV-02 — insider can ? (no status)
- `NO_STATUS` L4378: PRIV-03 — hidden-department member can message an outsider and the outsider's phone accepts it ? (no status)
- `NO_STATUS` L4383: PRIV-04 — toggling hidden updates a phone's list after refresh ? (no status)
- `NO_STATUS` L4388: PRIV-05 — old app + new hub ? (no status)

### Folded from stream S3-hubtab (backlog-additions)

- `TODO` L4401: HUBT-15 — Hub and Announcement icon look ? TODO
- `TODO` L4407: HUBT-16 — Chat-list top bar at 320 dp and 200 % font ? TODO
- `TODO` L4413: HUBT-17 — Transfers indicator and list scroll cost ? TODO
- `TODO` L4419: HUBT-18 — Desktop: leave a pushed Transfers ? TODO
- `TODO` L4425: HUBT-19 — Browse department groups still reachable ? TODO
- `TODO` L4433: DEMO-02 — Department-head invite enrols on the fixed demo hub (ERROR-400) ? TODO

### 4zzzzzzzzz — Announcements (Plan A3, Stream S2, 2026-10-15)

- `TODO` L4454: ANN-01 — Admin posts company-wide from console ? TODO
- `TODO` L4461: ANN-02 — Department head posts to own department from app; outsiders do not see it ? TODO
- `TODO` L4468: ANN-03 — Department head cannot post company-wide ? TODO
- `TODO` L4475: ANN-04 — Offline phone catches up on reconnect ? TODO
- `TODO` L4482: ANN-05 — Search in console and app ? TODO
- `TODO` L4489: ANN-06 — Edit and delete propagate to devices ? TODO
- `TODO` L4496: ANN-07 — Pinned announcements display order and pin limits ? TODO
- `TODO` L4503: ANN-08 — Hub restore from backup / epoch drop ? TODO
- `TODO` L4510: ANN-09 — Old app + new hub interoperability ? TODO
- `TODO` L4517: ANN-10 — Notification tap cold start ? TODO
- `TODO` L4524: ANN-11 — Transsion / background delivery observation ? TODO

### ORGT B4 follow-ups (built 2026-10-15, unit-tested only)

- `TODO` L4560: HUBT-20 - Unread badge on the Hub tab ? TODO
- `TODO` L4566: HUBT-21 - News: post, edit, pin, delete, conflict ? TODO
- `TODO` L4572: HUBT-22 - People: search, filter, letters ? TODO
- `TODO` L4578: HUBT-23 - Message colleague: paired, unpaired, double tap ? TODO
- `TODO` L4584: HUBT-24 - Call from the person sheet ? TODO
- `TODO` L4590: HUBT-25 - Route from a notification or the command line, cold start ? TODO
- `TODO` L4596: HUBT-26 - Route when not enrolled ? TODO
- `TODO` L4602: HUBT-27 - Wide layouts ? TODO
- `TODO` L4608: HUBT-28 - TalkBack and reduce motion ? TODO
- `TODO` L4614: HUBT-29 - Groups segment: join, pending, review requests ? TODO
- `TODO` L4620: HUBT-30 - Status strip honesty ? TODO
- `TODO` L4626: HUBT-31 - Transfers moved tip and last segment ? TODO
- `TODO` L4632: HUBT-32 - Transfers notification actions while enrolled ? TODO

### RADCONL-05 — Radio Link Lost & Recovery Flow

- `TODO` L4970: RADLNK-D01 — Windows Physical Radio Cold Start & Checklist ? TODO
- `TODO` L4977: RADLNK-D02 — Windows Physical Radio Rule 3 Confirmation Gate ? TODO
- `TODO` L4984: RADLNK-D03 — Windows Physical Radio Dynamic Registry COM Mapping ? TODO
- `TODO` L4991: RADLNK-D04 — Windows Physical Radio Power-Cycle Auto-Recovery ? TODO
- `TODO` L4998: RADLNK-D05 — Windows Physical Radio Reconnect Retry Limit & Reconnect Button ? TODO
- `TODO` L5005: RADLNK-D06 — Windows test message gates, then one real transmit (THIS TRANSMITS) ? TODO
- `TODO` L5012: RADLNK-D07 — Android Physical Radio Nearby Devices Permission at Point of Use ? TODO
- `TODO` L5019: RADLNK-D08 — Android Physical Radio In-App Discovery & Bonding ? TODO
- `TODO` L5026: RADLNK-D09 — Android Physical Radio FGS `connectedDevice` Notification Lifecycle ? TODO
- `TODO` L5033: RADLNK-D10 — Android Physical Radio Switch Off Cleanup ? TODO
- `TODO` L5051: RADUI-01 — Radio gateway appearance and layout on Android phone (portrait & landscape) ? TODO
- `TODO` L5061: RADUI-02 — Radio gateway two-pane adaptive layout on Desktop (wide vs narrow window) ? TODO
- `TODO` L5071: RADUI-03 — Android TalkBack accessibility navigation and state announcements ? TODO
- `TODO` L5081: RADUI-04 — Desktop keyboard navigation, focus indicators, and key triggers ? TODO
- `TODO` L5092: RADUI-05 — High font scale (200% system font size / display scaling) ? TODO
- `TODO` L5101: RADUI-06 — Dark mode and high-contrast color validation ? TODO
- `TODO` L5110: RADUI-07 — Reduce motion accessibility setting adherence ? TODO
- `TODO` L5119: RADUI-08 — Real radio pairing and TNC connection runbook (Owner hardware test) ? TODO
- `TODO` L5133: RADUI-09 — Encrypted Profile M authorisation confirmation and enforcement ? TODO
- `TODO` L5144: RADUI-10 — Test transmission warning dialog and transmit action ? TODO

### Radio gateway host wiring (stream R-wire, 2026-10-16)

- `TODO` L5160: RADWIRE-D01 - Complete Gateway Lifecycle on Android Phone with VR-N76 ? TODO
- `TODO` L5177: RADWIRE-D02 - Complete Gateway Lifecycle on Windows Desktop with VR-N76 ? TODO
- `TODO` L5192: RADWIRE-D03 - Send Test Message RF Transmission with Regulatory Warning ? TODO
- `TODO` L5207: RADWIRE-D04 - Remembered Radio Preserved Across Upgrade Install (adb install -r) ? TODO
- `TODO` L5220: RADWIRE-D05 - Profile M Tactical Encryption Authorization Enforcement on Hardware ? TODO
- `TODO` L5233: RADWIRE-D06 - The gateway survives its Activity (rotate, back out) ? TODO
- `TODO` L5245: RADWIRE-D07 - Callsign field in the authorisation dialog (ERROR-684) ? TODO

### 4zzzzzzzzzzz. Radio link fixes (RADFIX, 2026-10-20; see `logs/progress.md` and ADR-214)

- `TODO` L5847: RADFIX-D01 — Android: the radio link holds, and a reconnect skips the channel probe ? TODO
- `TODO` L5855: RADFIX-D02 — The radio's audio entry is ordered last, not offered as the radio ? TODO
- `TODO` L5863: RADFIX-D03 — KISS TNC off: the app says the radio closed the link (Android and Windows) ? TODO
- `TODO` L5871: RADFIX-D04 — Windows: pairing the audio entry is explained, the control entry works ? TODO
- `TODO` L5879: RADFIX-D05 — The disabled test button explains itself (no transmission) ? TODO

### 4zzzzzzzzzzzz. Hub files P2 — client transport (HUBFILE-C, 2026-10-20; see `docs/plans/HUB-FILES-PLAN.md` section 9 and ADR-215)

- `TODO` L5898: HUBFILE-C01 — A sealed file crosses a deployed hub between two real devices ? TODO (needs P3)
- `TODO` L5906: HUBFILE-C02 — An upload cut by a Wi-Fi to mobile-data switch continues where it stopped ? TODO (needs P3)
- `TODO` L5914: HUBFILE-C03 — An app killed mid-upload and mid-download resumes from its record ? TODO (needs P3)
- `TODO` L5922: HUBFILE-C04 — A hub restart in the middle of a transfer costs one retry, not the transfer ? TODO (needs P3)
- `TODO` L5930: HUBFILE-C05 — A phone whose storage is nearly full pauses instead of failing ? TODO (needs P3)
- `TODO` L5938: HUBFILE-C06 — The exact limit and one byte over, against a deployed hub ? TODO (needs P3)
- `TODO` L5946: MEAS-HUBFILE-01 — Hub file throughput and CPU on a phone (measurement, backlog section 5 rules) ? TODO (needs P3)

### 4zzzzzzzzzzzzz. Hub files P3 — the file message, both hosts (HUBFILE-M, 2026-10-20; see `docs/plans/HUB-FILES-PLAN.md` section 9 and ADR-216)

- `TODO` L5963: HUBFILE-M01 — A file to a colleague with no direct path goes through the hub by itself ? TODO
- `TODO` L5970: HUBFILE-M02 — With a direct path, nothing changes ? TODO
- `TODO` L5978: HUBFILE-M03 — An app killed mid-upload and mid-download continues after reopening ? TODO
- `TODO` L5985: HUBFILE-M04 — Wi-Fi to mobile data in the middle, both directions ? TODO
- `TODO` L5992: HUBFILE-M05 — Pause, resume and cancel from the bubble and the Transfers screen ? TODO
- `TODO` L5999: HUBFILE-M06 — A file from an unpaired colleague waits for a tap ? TODO
- `TODO` L6007: HUBFILE-M07 — Too large, no sealing key, an old hub ? TODO
- `TODO` L6014: HUBFILE-M08 — Android background limits ? TODO
- `TODO` L6022: HUBFILE-M09 — Desktop parity ? TODO
- `TODO` L6029: HUBFILE-M10 — A file that expired on the hub before the recipient came online ? TODO

### 4zzzzzzzzzzzzzz. Hub files P4 — group files, pruning, console Files card (HUBFILE-G, 2026-10-20; see `docs/plans/HUB-FILES-PLAN.md` section 9 and ADR-217)

- `TODO` L6043: HUBFILE-G01 — A group file reaches the member the LAN cannot ? TODO
- `TODO` L6050: HUBFILE-G02 — Nobody is reachable directly ? TODO
- `TODO` L6056: HUBFILE-G03 — A member removed from the group cannot fetch ? TODO
- `TODO` L6062: HUBFILE-G04 — A group that is not linked to the hub keeps today's behaviour ? TODO
- `TODO` L6068: HUBFILE-G05 — Forged ticket ? TODO
- `TODO` L6074: HUBFILE-G06 — Old rows disappear; rows a chat still shows do not ? TODO
- `TODO` L6080: HUBFILE-G07 — Console Files card ? TODO
- `TODO` L6086: HUBFILE-G08 — Schema 18 to 19 upgrade keeps hub files ? TODO

### 4zzzzzzzzzzzzzzz. Release 2.1.0 smoke checks (2026-10-20; see `CHANGELOG.md` and `docs/release-build.md`)

- `TODO` L6097: REL-01 — The uploaded APK is the release-signed one ? TODO
- `TODO` L6103: REL-02 — Fresh install of the release APK (R8 build) on two phones ? TODO
- `TODO` L6109: REL-03 — Update over an older release-signed build keeps data (MIG-01 for the release key) ? TODO
- `TODO` L6114: REL-04 — v2.0.0-beta (debug-signed) is refused as an update, as the README says ? TODO
- `TODO` L6118: REL-05 — Windows MSI and EXE install, run, upgrade and uninstall ? TODO
- `TODO` L6125: REL-06 — Settings: Enterprise and Radio entries do not mislead ? TODO
- `TODO` L6130: REL-07 — Manifest permissions behave on a clean install ? TODO

### 4zzzzzzzzzzzzzzzz. Network move and swarm greeting (2026-10-20; ERROR-742, ERROR-743)

- `TODO` L6138: NETMOVE-01 — Reconnect after moving to another Wi-Fi ? TODO
- `TODO` L6144: SWM-48 — Group image after a session swap ? TODO
- `TODO` L6150: DLOG-01 — A second desktop launch leaves the running log alone ? TODO
- `TODO` L6155: TLS-ID-01 — Who dials the phone with a certificate the dialer refuses ? TODO

### 4zzzzzzzzzzzzzzzzz. Hub tab redesign (HUBV, 2026-10-20; see `docs/ui/flash-hub-tab.md` "Redesign 2026-10-20")

- `TODO` L6165: HUBV-01 — Hero overview in both themes ? TODO
- `TODO` L6170: HUBV-02 — Segment control and page slide ? TODO
- `TODO` L6175: HUBV-03 — Reduced motion ? TODO
- `TODO` L6180: HUBV-04 — Low-tier, minimal chrome, ultra low UI ? TODO
- `TODO` L6185: HUBV-05 — Status strip and status page ? TODO
- `TODO` L6190: HUBV-06 — First load skeleton ? TODO
- `TODO` L6195: HUBV-07 — News cards and composer ? TODO
- `TODO` L6200: HUBV-08 — Person sheet ? TODO
- `TODO` L6205: HUBV-09 — Compact overlays and desktop two-pane (D) ? TODO
- `TODO` L6210: HUBV-10 — Large font and TalkBack ? TODO

### 4zzzzzzzzzzzzzzzzzz. Hub "ledger" redesign (HUBL, 2026-10-20; see `docs/ui/hub-review-and-redesign-2026-10-20.md`)

- `TODO` L6219: HUBL-01 — No rounded surfaces left ? TODO
- `TODO` L6224: HUBL-02 — Text tabs ? TODO
- `TODO` L6229: HUBL-03 — Masthead and tick meter ? TODO
- `TODO` L6234: HUBL-04 — Ruled rows and edges ? TODO
- `TODO` L6239: HUBL-05 — Modes ? TODO
- `TODO` L6244: HUBL-06 — Dark and light ? TODO
- `TODO` L6249: HUBL-07 — Touch targets and screen reader ? TODO
- `TODO` L6254: HUBL-08 — Owner look and feel ? TODO
- `NO_STATUS` L6260: CHATFIX-01..08 and HUBFIX-01..09 - chat / chat list / Hub review fixes (2026-10-21, working tree, not committed) ? (no status)
- `NO_STATUS` L6280: CHATFIX-09..16 - open-items H-16..H-26 follow-ups (2026-10-21, working tree, not committed) ? (no status)

### 4zzzzzzzzzzzzzzzzz. Cancel, error, resume ↔ notifications (CNCL, 2026-10-20; see `docs/audit/2026-10-20-cancel-error-resume-notification-audit.md`)

- `TODO` L6296: CNCL-01 — A cancelled send is not re-sent by tapping the chat bubble ? TODO
- `TODO` L6305: CNCL-02 — Android's dataSync timeout pauses a 1:1 transfer instead of cancelling it ? TODO
- `TODO` L6315: CNCL-03 — A late PAUSE/CANCEL cannot rewrite a finished transfer ? TODO
- `TODO` L6323: CNCL-04 — Group cancel says and does the same thing on every surface ? TODO
- `TODO` L6332: CNCL-05 — Cancelled and declined files do not read as failures ? TODO
- `TODO` L6338: CNCL-06 — No dead Retry (fixed in code 2026-10-21 for the hub notification; the 1:1 failed-receive part is still open) ? TODO
- `TODO` L6347: CNCL-07 — A waiting transfer does not leave a frozen progress notification ? TODO
- `TODO` L6355: CNCL-08 — A hub transfer that never progresses eventually says so ? TODO
- `TODO` L6363: CNCL-09 — A call notification can always be dismissed ? TODO
- `TODO` L6370: CNCL-10 — Cancelling from the notification always ends in a visible result ? TODO
- `TODO` L6378: CNCL-11 — A killed app comes back with its interrupted transfers ? TODO
- `TODO` L6386: CNCL-12 — Every cancel/decline message is a sentence ? TODO
- `TODO` L6393: CNCL-13 — A receiver's refusal reaches the sender ? TODO
- `TODO` L6400: CNCL-14 — A group file shows why it stopped ? TODO
- `TODO` L6407: CNCL-15 — A "paused by Android" 1:1 row resumes by itself when the service returns (open-items C-02) ? TODO
- `TODO` L6412: CNCL-16 — Desktop toast: Accept / Decline / Retry (C-06) ? TODO
- `TODO` L6417: CNCL-17 — A hub upload older than 24 h fails with a sentence and can be retried (C-07) ? TODO
- `TODO` L6421: CNCL-18 — A retried receive with no sender fails again within 30 s (C-08) ? TODO
- `TODO` L6426: CNCL-19 — Dismiss, Clear and 30-day history survive a restart (C-09, C-05, C-10) ? TODO
- `TODO` L6431: CNCL-20 — Receiver's refusal names its reason on the sender (C-11) ? TODO

### 4zzzzzzzzzzzzzzzzzzzz. Server & Hub internals audit (SRVHUB, 2026-10-21; see `docs/audit/2026-10-21-server-hub-internals-audit.md`)

- `AUTOMATED` L6446: SRVHUB-01 — `/directory/people` pages past one page (DIR-01) ? AUTOMATED PASS 2026-10-21 (`DirectoryPrivacyTest` walk of 1,203 people at three page sizes, `DirectoryPrivacyClientTest` for the 500-person page; the real app showing a colleague past position 500 online is still owed)
- `AUTOMATED` L6451: SRVHUB-02 — A sealed mailbox row that cannot be opened does not block the queue (RLY-01) ? AUTOMATED PASS 2026-10-21 (`MailboxQuarantineTest` with a real router drain; the real-hub run with a replaced `data.key` and a device is still owed)
- `AUTOMATED` L6456: SRVHUB-03 — Two admins writing at the same time cannot duplicate a `seq` or lose an edit (ANN-01) ? AUTOMATED PASS 2026-10-21 (`AnnouncementStoreConcurrencyTest`, racing threads on a WAL file database, mutation-checked; the two-admins-in-the-console device run is still owed)
- `AUTOMATED` L6460: SRVHUB-04 — A department move takes effect within seconds (DEV-01) ? AUTOMATED PASS 2026-10-21 (`RegistryPrincipalTest`, `AdminConsoleApiTest` move test with a real router; the device run through a real client, the announcement scope and the Hub tab are still owed)
- `AUTOMATED` L6465: SRVHUB-05 — A console edit keeps an announcement's expiry (ANN-02) ? AUTOMATED PASS 2026-10-21 (`AnnouncementRoutesTest.anEditKeepsTheExpiryUnlessItIsClearedOnPurpose`, mutation-checked; the console form itself, expiry field and "No expiry" box, still needs an eyes-on check in a browser)
- `AUTOMATED` L6470: SRVHUB-06 — A hidden department's removed member does not leak (DIR-02) ? AUTOMATED PASS 2026-10-21 (`DirectoryTombstoneTest`; the real two-device run is still owed)
- `AUTOMATED` L6475: SRVHUB-07 — A re-enrolled device appears in the directory again (DIR-03) ? AUTOMATED PASS 2026-10-21 (`DirectoryTombstoneTest`, `OrgDirectorySyncTest` DIR-03; the real revoke / unrevoke / re-enrol run is still owed)
- `AUTOMATED` L6480: SRVHUB-08 — A hub restart does not lock out an office (AUTH-01) ? AUTOMATED PASS 2026-10-21 (`AuthVerifyRateLimitTest`; the real restart with 50 devices behind one address is still owed)
- `AUTOMATED` L6485: SRVHUB-09 — One sender cannot flood a large group (RLY-02) ? AUTOMATED PASS 2026-10-22 (`RelayByteBudgetTest`; the real 100-member flood with a measured uplink is still owed)
- `PARTIAL` L6490: SRVHUB-10 — A hub restart during a transfer costs one sign-in, not many (CLI-01, CLI-02) ? PARTIAL 2026-10-22 (single-flight refresh and jitter, and a bounded sign-in, unit-tested in `HubSessionTest`; a real hub restart during a transfer is still owed)
- `AUTOMATED` L6495: SRVHUB-11 — `/health` answers while the hub is busy (OPS-01) ? AUTOMATED PASS 2026-10-22 (`CachedMailboxStatsTest`; `/health` under a real 20,000-member sync is still owed)
- `AUTOMATED` L6500: SRVHUB-12 — A recipient reading an unfinished blob gets a retryable answer (FL-01) ? AUTOMATED PASS 2026-10-22 (`FileStoreTest`, `FileRoutesTest`, `E2eHubFileTransferContractTest`; a phone downloading a really unfinished file is still owed)
- `AUTOMATED` L6504: SRVHUB-13 — A 300-member organisation syncs through the real client (DIR-04) ? AUTOMATED PASS 2026-10-21 (server unit + `E2eDirectorySyncSizeTest` through the real route and real client + `OrgDirectorySyncTest`; a `syncOnce` with a real DAO against a real hub and a device run are still owed)
- `AUTOMATED` L6509: SRVHUB-14 — A truncated roster list does not delete groups the device is still in (DIR-05) ? AUTOMATED PASS 2026-10-21 (server unit + `E2eDirectorySyncSizeTest` through the real route and real client + `OrgDirectorySyncTest`; a `syncOnce` with a real DAO against a real hub and a device run are still owed)
- `AUTOMATED` L6514: SRVHUB-15 — The device certificate lifetime is 30 days and adjustable (ADR-222 decision 2) ? AUTOMATED PASS 2026-10-21 (`CertificateLifetimeTest`: lifetime, delegation clamp, scaled renewal windows, overrides, old-client window, startup warning; a hub run with a real client renewing is still owed)
- `AUTOMATED` L6519: SRVHUB-16 — Announcement expiry is a tri-state on the wire (ADR-222 decision 6) ? AUTOMATED PASS 2026-10-21 (route test above + `server/admin-ui-tests/announcements.test.mjs` for the composer rules)

### 4zzzzzzzzzzzzzzzzzzzzz. Brutal code review, round 2 (RVW22, 2026-10-22; see `docs/audit/2026-10-22-brutal-code-review.md`)

- `TODO` L6528: RVW22-01 — Quarantined mailbox rows no longer lock a device out (F1, ADR-223) ? TODO (unit tests: `MailboxQuarantineTest`, mutation-checked; the 1,000-row rehearsal on a real hub is owed)
- `TODO` L6533: RVW22-02 — A colleague whose name contains `|` pages once (F2) ? TODO (unit tests: `DirectoryPrivacyTest`, mutation-checked; the phone walk and a real hub upgrade with a cursor in flight are owed)
- `TODO` L6538: RVW22-03 — A long-lived certificate renews instead of expiring (F4) ? TODO (unit tests: `CertChainTest`, `HubSessionTest`, mutation-checked; the clock-shifted device run against a real hub is owed. Note: the leaf may never outlive its delegation, so the hub's delegation must cover lifetime + grace — see the a
- `TODO` L6543: RVW22-04 — Abrupt drops do not starve the buffer pool (F3) ? TODO (unit test: `WebSocketCodecTest` pool-identity test, mutation-checked; the device measurement is owed — see the `MEAS-` series rule in §5)
- `TODO` L6550: CONSOLE-BLK-01 — The admin console renders with a content blocker on (ERROR-746) ? TODO
- `TODO` L6558: CONSOLE-KEY-01 — Replacing the browser key with a recovery code (ERROR-747) ? TODO
- `TODO` L6566: SHARE-FIT-01 — A desktop screen share fits the phone whole (ERROR-748) ? TODO
- `TODO` L6573: FINISH-01 — A finished download leaves no "not finished" row or notification (ERROR-749) ? TODO

### PERF-A01..A07 (2026-10-30) - Stream A (build) after the integrator review

- `TODO` L6787: PERF-A01 - Per-ABI APK sideload (was Stream A's own A01) ? TODO, runnable since 2026-10-31 (ADR-243 answered PERF-A04; approved by ADR-232 item 9 on 2026-10-30).
- `TODO` L6797: PERF-A02 - Regenerate the baseline profile on a device ? TODO.
- `TODO` L6808: PERF-A03 - Desktop installer smoke test ? TODO — scheduled by ADR-232 item 10: **before the next Windows build ships.** This is the only way
- `OTHER` L6819: PERF-A04 - Owner decision: ship per-ABI APKs? ? DECIDED 2026-10-30 (ADR-232 item 9) — **ship per-ABI *and* universal**: `Flash-<version>.apk` stays universal,
- `OTHER` L6848: PERF-A07 - `RealFlashChatRepositoryTest` must stop sleeping ? PARTLY DONE - 2026-10-31: `ef66a56a` replaced both fixed delays with explicit waits (the pin case waits for

### PERF-D01..D04 (2026-10-31) - Stream D (data) after the integrator review

- `TODO` L6866: PERF-D01 - Chat list and history paging on a long thread ? TODO.
- `TODO` L6876: PERF-D02 - Search deep link into a long thread ? TODO.
- `TODO` L6884: PERF-D03 - The summary projections have no consumer ? TODO.
- `TODO` L6891: PERF-D04 - In-memory SQLite for the client tests ? TODO.

### PERF-T04..T09 (2026-10-31) - Stream T (transfer) after the integrator review

- `TODO` L6903: PERF-T04 - A real single pass for the whole-file hash ? TODO — needs an owner/wire decision first.
- `TODO` L6911: PERF-T05 - A production `SeekableChunkSource` (`O23-02`) ? TODO.
- `TODO` L6919: PERF-T06 - `.part` staging (`O07-02`) ? TODO.
- `TODO` L6927: PERF-T07 - Wire the FAT32 4 GiB check (`O23-03`) ? TODO.
- `TODO` L6934: PERF-T08 - Buffered `saveToGallery` (`O23-06`) ? TODO.
- `TODO` L6940: PERF-T09 - Is 3 s of ACK drain grace enough? ? TODO.
- `TODO` L6949: PERF-T10 - A resumed send must not put its whole tail on the wire twice ? TODO.

### PERF-U01..U06 (2026-10-31) - Stream U (UI) after the integrator review

- `TODO` L6964: PERF-U01 - The delivered UI optimisations do not cost fluidity ? TODO.
- `TODO` L6982: PERF-U02 - The desktop can still archive a chat without swiping (behaviour change) ? TODO.
- `TODO` L6994: PERF-U03 - Opening the keyboard at (nearly) the bottom of a chat scrolls to the newest message ? TODO.
- `TODO` L7006: PERF-U04 - An echo whose durable row never arrives (open risk, decide the fix) ? TODO.
- `TODO` L7019: PERF-U05 - A silently-dropped settings write leaves the switch lying ? TODO.
- `TODO` L7031: PERF-U06 - Wiring `compose_compiler_config.conf` (not wired anywhere) ? TODO.

### PERF-N01..N03 (2026-10-31) - Stream N (network/discovery) after the integrator review

- `TODO` L7047: PERF-N01 - Reconnect handshake latency on two real phones ? TODO.
- `TODO` L7060: PERF-N02 - A chat message stays instant while a transfer saturates the uplink ? TODO.
- `TODO` L7072: PERF-N03 - Discovery liveness on a flat/broadcast-only network, and a network switch ? TODO.

### PERF-G01..G08 (2026-11-01) - Stream G (Android) after the integrator review

- `TODO` L7099: PERF-G01 - The CPU wake lock follows the session, not the process ? TODO.
- `TODO` L7114: PERF-G02 - The keepalive worker skips only when the user has not asked to stay reachable ? TODO.
- `TODO` L7128: PERF-G03 - Android 15 FGS timeout during verification pauses instead of killing ? TODO.
- `TODO` L7138: PERF-G04 - Transsion Tier 3 silent audio is gated on the screen being off ? TODO.
- `TODO` L7148: PERF-G05 - Flash does not fight a music player for audio focus ? TODO.
- `TODO` L7158: PERF-G06 - Discovery drops to ECO on screen-off, except while work is live ? TODO.
- `TODO` L7168: PERF-G07 - The automatic subnet sweep waits for cold start to settle ? TODO.
- `TODO` L7179: PERF-G08 - The idle foreground service stops, and the opt-in keeps it up ? TODO.

### PERF-H01..H07 (2026-11-01) - Stream H (hub) after the integrator review

- `TODO` L7200: PERF-H01 - A 20 000-member directory sync on a low-memory Android phone ? TODO.
- `TODO` L7212: PERF-H02 - A 500 MB hub file over WAN, cleanly and after a pause ? TODO.
- `TODO` L7224: PERF-H03 - Relay scale soak with the production JVM profile ? TODO.
- `TODO` L7234: PERF-H04 - Mailbox drain after a long offline period ? TODO.
- `TODO` L7245: PERF-H05 - Chunk writer lock hold time (harness first) ? TODO.
- `TODO` L7254: PERF-H06 - Group fan-out serialises once ? TODO.
- `TODO` L7264: PERF-H07 - A killed hub never leaves a chunk row without its file ? TODO.
- `TODO` L7276: PERF-H08 - What the hub presence poll actually costs (the number behind `OPT-O16-03`'s revisit condition) ? TODO.

### 5.W4. Wave 4 paired profiling (reviewed 2026-11-01; do after functional checks)

- `TODO` L7296: PERF-E01 — Paired desktop startup, crypto and icon profiling ? TODO.
- `TODO` L7307: PERF-E02 — Resize, navigation, collection and prompt behavior/profile ? TODO.
- `TODO` L7318: PERF-E04 — Real-process logger admission, overflow, shutdown and profile ? TODO.
- `TODO` L7330: PERF-E05 — Dispatcher concurrency versus whole-process thread/RSS inventory ? TODO.
- `TODO` L7341: PERF-K01 — Logging/probe caller behavior, allocations and frame impact ? TODO.
- `TODO` L7353: PERF-K02 — Public-key cache and realistic crypto/directory profiling ? TODO.
- `TODO` L7364: PERF-K03 — Physical Android logger/flush/rotation and explicit developer gzip export ? TODO.
- `TODO` L7378: PERF-K04 — Paired NFKD folding semantics and bounded-cache profiling ? TODO (Unicode/locale/concurrency host checks pass; physical/performance not run).
- `NO_STATUS` L7388: PERF-R01..R05 — radio stream (stream R, merged 2026-11-02, ADR-330/331, EXP-130..133) ? (no status)
- `NO_STATUS` L7413: PERF-S01..S02 — swarm stream (stream S, merged 2026-11-02, ADR-320..324, EXP-125..128) ? (no status)
- `NO_STATUS` L7432: PERF-C01..C03 — calls stream (stream C, merged 2026-11-02, ADR-341..343, EXP-135..139) ? (no status)

### 5.W1. Wave 1 safety fixes (2026-11-03; device checks owed)

- `PARTIAL` L7461: W1-TLS-01 — Forget a peer without restarting the app ? PARTIAL (security pass; notice pass; header unit-tested; outbox/tombstone owed)
- `PARTIAL` L7469: W1-REVOKE-02 - The revoked sender must learn, not retry silently ? PARTIAL (notice+header pass; negative ack unit-tested, device owed; old-peer budget/tombstone/title owed)
- `TODO` L7477: W1-NACK-01 — A queued message fails as soon as the revoke is seen ? TODO — unit tests pass (`FlashInboundRouterTest` 55/55 including the no-key resend; `RealFlashChatRepositoryTest` negative ack 1/1), but the device run was blocked on 2026-11-05 because the Samsung left USB before the new APK was installed 
- `TODO` L7483: W1-REVOKE-03 — A revoke stays forgotten: no automatic re-pin, manual Pair still works ? TODO (unit tests on Android and desktop pass; two-phone run owed).
- `TODO` L7489: W1-TITLE-01 — A revoke never turns a chat title into a raw device id ? TODO (unit tests pass; device run owed).

### 5.W2. Wave 2 performance measurements (2026-11-05; host baseline only)

- `TODO` L7501: W2-XFER-01 — 4 GiB transfer, 64 vs 256 KiB, 5 GHz and hotspot ? TODO.
- `PARTIAL` L7510: W2-AND-01 — Android 1 h screen-off baseline with one paired peer ? PARTIAL (`EXP-140` cold/warm/PSS/gfxinfo done;
- `TODO` L7519: W2-DESK-01 — Desktop JFR and tray memory ? TODO.
- `TODO` L7527: W1-FRAME-01 — Malformed HELLO ? TODO (needs the harness)
- `TODO` L7533: W1-PAIR-01 — Five failed pairings lock the peer out ? TODO (needs the harness; the limiter itself is unit-tested)
- `OTHER` L7539: W1-RESUME-01 — A source file changed between sessions ? IMPLEMENTED (Room v22 -> v23, ADR-352); unit and migration tests green; **device run owed**.

### 5zzz Design review ? alternative hub admin console (2026-11-05)

- `TODO` L7567: CONSOLE-D02 — Desktop composition at 1440 ? TODO
- `TODO` L7574: CONSOLE-D03 — Tablet and phone ? TODO
- `TODO` L7583: CONSOLE-D04 — Overlays, keyboard and focus ? TODO
- `TODO` L7590: CONSOLE-D05 — Contrast, type and touch ? TODO
- `TODO` L7597: CONSOLE-D06 — Prototype actions are honest ? TODO
- `TODO` L7606: CONSOLE-D07 — Port regression (only after approval) ? TODO (blocked on owner approval)

## Radio-only block (requires powered/paired radio hardware)

- `TODO` BT-09 - Spike window opens and the simulated radio runs (no hardware)
- `TODO` BT-10 - KISS transparency through the real TNC
- `TODO` BT-11 - A full 220-byte Flash frame is accepted and heard
- `TODO` BT-12 - Burst goodput and round trip at 1200 baud (feeds EXP-023)
- `TODO` BT-13 - Link loss and reconnect
- `TODO` BT-14 - One app at a time
- `TODO` BT-15 - Station label rotation (privacy)
- `TODO` BT-16 - Android RFCOMM to the radio (BLOCKED: needs a debug entry)
- `TODO` BT-17 - Flash-to-Flash over RFCOMM (BLOCKED: not built)
- `TODO` RADLNK-D01 — Windows Physical Radio Cold Start & Checklist
- `TODO` RADLNK-D02 — Windows Physical Radio Rule 3 Confirmation Gate
- `TODO` RADLNK-D03 — Windows Physical Radio Dynamic Registry COM Mapping
- `TODO` RADLNK-D04 — Windows Physical Radio Power-Cycle Auto-Recovery
- `TODO` RADLNK-D05 — Windows Physical Radio Reconnect Retry Limit & Reconnect Button
- `TODO` RADLNK-D06 — Windows test message gates, then one real transmit (THIS TRANSMITS)
- `TODO` RADLNK-D07 — Android Physical Radio Nearby Devices Permission at Point of Use
- `TODO` RADLNK-D08 — Android Physical Radio In-App Discovery & Bonding
- `TODO` RADLNK-D09 — Android Physical Radio FGS `connectedDevice` Notification Lifecycle
- `TODO` RADLNK-D10 — Android Physical Radio Switch Off Cleanup
- `TODO` RADUI-01 — Radio gateway appearance and layout on Android phone (portrait & landscape)
- `TODO` RADUI-02 — Radio gateway two-pane adaptive layout on Desktop (wide vs narrow window)
- `TODO` RADUI-03 — Android TalkBack accessibility navigation and state announcements
- `TODO` RADUI-04 — Desktop keyboard navigation, focus indicators, and key triggers
- `TODO` RADUI-05 — High font scale (200% system font size / display scaling)
- `TODO` RADUI-06 — Dark mode and high-contrast color validation
- `TODO` RADUI-07 — Reduce motion accessibility setting adherence
- `TODO` RADUI-08 — Real radio pairing and TNC connection runbook (Owner hardware test)
- `TODO` RADUI-09 — Encrypted Profile M authorisation confirmation and enforcement
- `TODO` RADUI-10 — Test transmission warning dialog and transmit action
- `TODO` RADWIRE-D01 - Complete Gateway Lifecycle on Android Phone with VR-N76
- `TODO` RADWIRE-D02 - Complete Gateway Lifecycle on Windows Desktop with VR-N76
- `TODO` RADWIRE-D03 - Send Test Message RF Transmission with Regulatory Warning
- `TODO` RADWIRE-D04 - Remembered Radio Preserved Across Upgrade Install (adb install -r)
- `TODO` RADWIRE-D05 - Profile M Tactical Encryption Authorization Enforcement on Hardware
- `TODO` RADWIRE-D06 - The gateway survives its Activity (rotate, back out)
- `TODO` RADWIRE-D07 - Callsign field in the authorisation dialog (ERROR-684)
- `TODO` RADFIX-D01 — Android: the radio link holds, and a reconnect skips the channel probe
- `TODO` RADFIX-D02 — The radio's audio entry is ordered last, not offered as the radio
- `TODO` RADFIX-D03 — KISS TNC off: the app says the radio closed the link (Android and Windows)
- `TODO` RADFIX-D04 — Windows: pairing the audio entry is explained, the control entry works
- `TODO` RADFIX-D05 — The disabled test button explains itself (no transmission)
