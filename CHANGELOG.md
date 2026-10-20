# Changelog

All notable changes to Flash are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Flash is a beta: the library API (`core-*`, `ui-*`) may still change between beta tags.

**How to read the status marks.** Flash is developed feature-first and tested on devices later (see `docs/testing/TEST-BACKLOG.md`).
Unless an entry says *device-verified*, it was built, unit-tested and compiled, and **has not yet been run on real phones or
laptops**. The only things the owner has device-verified so far are pairing, chat, 1:1 calls, file transfers and upgrading an
install that has v1 pairings (2026-09-23, `logs/handoff.md`).

## [2.1.0] - 2026-10-20

First official release since `v2.0.0-beta`. Android `versionName 2.1.0` / `versionCode 4`, Windows installers `Flash-2.1.0.msi` and
`Flash-2.1.0.exe`. Source: 337 commits on `dev` since `v2.0.0-beta`.

**This release covers the apps only.** The library (`core-*`, `ui-*`, JitPack, still `2.1.0-beta.1`), the Flash Hub server and the
radio gateway are **not** part of this release and stay experimental; see "Experimental" below.

### Upgrading from v2.0.0-beta

- **Android: uninstall v2.0.0-beta first.** That APK was signed with Android Studio's debug key; 2.1.0 is signed with a real release
  key (`CN=Flash, OU=Release`, certificate SHA-256 `b695f4d6f36117037113a9cab6c1de3a3ab64d63e487c9cc913aa7bc611e89af`). Android
  refuses an update signed with a different key ("App not installed"), so the old install must be removed, which **deletes its chats,
  pairings and device identity**. Every later update will install in place. Back up the release key
  (`~/.flash-signing/flash-release.jks` and its password): without it nobody can update.
- Windows: install the new MSI or EXE over the old one. The installers are not Authenticode-signed, so SmartScreen shows "unknown
  publisher" (More info, Run anyway). The existing chat database is migrated in place by the same migration chain as Android
  (unit-tested; the device check `MIG-02` is still owed).
- Pairings made with v1 builds keep working (device-verified 2026-09-23). The database schema moves from 4 to 19 through migrations
  that are tested in unit tests (`FlashMigrationsChainTest`, `FlashJvmMigrationsTest`); an upgrade over a real old install (`MIG-01`)
  is still owed on a device.

### Added

- **Group swarm file transfer** (`core-swarm`, ADR-070..075, **on by default** since owner decision O-8, 2026-10-19; Settings turns
  it off and an explicit off is kept): a group file is fetched in pieces from every
  member that holds it, verified against the whole-file SHA-256, with cancel-everywhere (signed tombstones), delete-for-everyone,
  Android 15 foreground-service timeout handling and availability UI. Unit-tested; device checks `SWM-07`..`SWM-29` owed.
- **Group membership by group id + secret** (Track GM, ADR-074, ADR-076): invite links (`flash://g/1/...`), mutual group proofs,
  join requests with approval, member removal with secret rotation, "Change group code", signed group settings, and the
  membership UI. Unit-tested; device checks `GMB-*`, `GSET-*` owed.
- **Signed groups and vouched trust** (ADR-044 V1/V2, ADR-063): groups of up to 20 where members need only be paired with the owner,
  co-owners and successor-on-leave, owner "Remove", and a removal ripple that tells an offline removed device.
- **Windows desktop app and Linux groundwork**: shared engine and UI with Android, push-to-talk on desktop (ADR-058), Secret Service
  keyring vault and XDG paths on Linux (ADR-092, plan L0+L1; laptop checks `LNX-*` owed).
- **Voice and video calls**: group calls that reach unpaired members, late joiners, a group video audit (ADR-064..066), typed media
  failures with an audio-only fallback and camera banner, and 1:1 voice-to-video upgrade (ADR-078/079), 540p group video (ADR-098).
- **Group history sync** (ADR-100, UI-057): a signed history limit (default 30 days), a join card, "Load older
  messages", paged catch-up with a progress banner, Room schema 13. Unit-tested; `GSY-01`..`GSY-12` owed.
- **Screen share in calls** (ADR-102): a desktop device can share a screen or window in 1:1 and group video calls;
  every receiver can watch. The Android presenter is **not built**. Unit-tested; `SHARE-01`..`SHARE-14` and `EXP-024` owed.
- **Search** (UI-023/024, ADR-218): global and in-chat search now finds file names and captions as well as text, with scopes (All,
  Chats, Messages, Files, Media, Links), recent searches, structured results (chat, sender, snippet, time) and a tap that opens the
  chat and highlights the message. Unit-tested; `SEARCH-01`..`12` owed.
- **Android keep-alive on Transsion phones** (HIB-12..17): tiered, session-aware keep-alive, a set-up guide, an optional home-screen
  widget and notification overhaul. Live-checked on one Infinix handset; background delivery is still **not** claimed as supported.
- **Ink launch splash** (UI-056): the pen writes "Flash" on a cold start; Settings turns it off. `SPLASH-01`..`08` owed.
- **UI polish roadmap** (phases 0..3): redesigned detail panes, transfers screen, settings screen, message forwarding sheet,
  attachment staging tray, shared-content viewer, pinned-messages banner, motion polish. `UIP-01`..`UIP-10` owed.
- **Diagnostics**: persistent Android file log with export (ADR-077), evidence probes and a session header in every exported log
  (ADR-087).
- **Discovery resilience** (ADR-047, ADR-057): unicast subnet sweep, directed-broadcast beacon, adapter filtering, a session ceiling
  of 24 for every connection mode.
- Chat: Message Info sheet (UI-051), catch-up banner (UI-052), group read ticks, desktop device rename that propagates.

### Changed

- **Library publishing (ADR-103)**: the `webrtc-kmp` fork that `core-calling` and `ui-callui` depend on is now published under
  Flash's own coordinates (`com.transfer.flash:webrtc-kmp`, `-android`, `-jvm`, same version as the library) by the same JitPack
  build. Before this, the published POMs pointed at `com.shepeliev:webrtc-kmp-android:0.125.11-flash-1`, which exists nowhere, so a
  consumer of the calling modules could not resolve it. Local development is unchanged (included-build substitution).
- `FlashConfig` moved to common code and now holds `receivedFilesPath: String?`; Android keeps a source-compatible overload and
  accessor taking a `File` (ERROR-125).
- Group video sizes: HIGH 540p, MEDIUM and LOW 360p (ADR-098).
- Android manifest: `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` (scan flagged `neverForLocation`), `SYSTEM_ALERT_WINDOW` and a
  `mediaPlayback` foreground-service type are declared again. They serve the Transsion keep-alive tiers and the experimental radio
  link; the keep-alive overlay is only used after the user grants "display over other apps".
- Android `FileProvider` roots are limited to the directories Flash shares from; the whole-filesystem `root-path` and the entire
  private `files` tree are no longer exposed (R-15).
- `README.md`: minSdk 24 is Android 7.0 (it said 8.0), new version and installer names, the fork and desktop WebRTC native note.

### Fixed

- **Security**: a dial to a named peer is refused when the HELLO device id differs from the dialed id (R-02, both network twins);
  the exported share target refuses `file:` URIs and Flash's own content providers (R-05).
- **WebSocket**: a ping or pong between the fragments of a message no longer drops the message (R-11, RFC 6455 section 5.4).
- **Desktop**: a hung `netsh` can no longer block the network-band refresh forever; "save image" never overwrites an earlier save
  and logs a failure (R-20).
- Calls: a group call can always be ended (ERROR-086), group calls from unpaired callers, late joiners and video fixes
  (ERROR-088, 095..097), a native crash when a call ended on Linux (ERROR-123).
- Chat: a chat no longer showed unread after being opened (ERROR-087); group sync, read ticks, outbox deadlines, edge cases
  (ERROR-084, 089..094).
- Groups: invite proofs racing, members-may-add certificates, stale invite links, unpaired requesters marked (ERROR-112..115).
- Swarm: offers reach vouched members and are kept for catch-up, signed relays verify, a lost source is reported (ERROR-107..111,
  117).
- Discovery: an mDNS resolve storm on the app's own service name and an out-of-memory after a network change (ERROR-122); one live
  NSD registration across reconnects.
- Transfers: a received file is checked as a whole and a mismatch fails the transfer on both ends; a receive that cannot fit is
  refused by name; failures are worded for people (ADR-068/069).
- Messaging: the 1:1 reaction trust gate was disabled by a leftover `if (false)` and is restored.

### Experimental (not part of this release's promise)

These are in the apps but unfinished and not device-verified. Do not rely on them.

- **Flash Hub** (organisation server, enrolment via `flash://enroll`, directory, announcements, hub-carried chat and sealed files up
  to 50 MB). The server is in beta and is **not released or deployed with 2.1.0**. Settings shows an "Enterprise & Organization"
  section; leave it alone unless you run a hub.
- **Radio gateway (beta)** (Settings, Network): Bluetooth/serial link to a packet radio. Radios have not been tested end to end;
  radio audio is not built.
- **Library publishing** (JitPack `core-*` / `ui-*` modules, tag `v2.1.0-beta.1`): unchanged by this release.

### Known issues and not verified

- **Most of the work above is unit-tested only.** The owner has device-verified pairing, chat, 1:1 calls, file transfers and a v1-pairing
  upgrade (2026-09-23) plus the Transsion keep-alive on one handset. Everything else, including groups, the swarm, group history, group
  calls, screen share, search and the UI polish, still has its device checks owed in `docs/testing/TEST-BACKLOG.md`.
- Background transfer is not claimed as supported: liveness differs by handset (Transsion devices freeze Flash at screen off and
  destroy its sockets; EXP-002, HIB-10).
- Wi-Fi Direct is not implemented (ADR-056). The Android screen-share presenter is not built (an Android device can watch a share,
  not start one).
- The Windows installers are unsigned (SmartScreen warning). The Linux desktop port has code but no release build.
- ERROR-168: on Linux, a member who leaves and rejoins a group call can sit on "Connecting" for about 40 s.

## [2.0.0-beta]

First tagged beta of the Kotlin Multiplatform library set and the Windows desktop app (JitPack tag `v2.0.0-beta`). The earlier tags
are `v1.0.0` and `v1.1.0`. Its published POMs for `core-calling` and `ui-callui` referenced an unpublished WebRTC artifact (fixed in
2.1.0-beta).
