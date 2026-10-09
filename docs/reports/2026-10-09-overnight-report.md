# Overnight report, 2026-10-09: group sync, Bluetooth/radio link, screen share

Written for the owner, who was away. Three sub-agents worked in parallel on one tree under one rule set (no commit, no push, no edits to
the shared logs; the lead merged those). **Nothing here is committed.** **Nothing here is device-verified.** Every claim below is
"unit-tested, compiled", never "works on a phone/laptop/radio".

Detailed reports (each has every decision with alternatives, limits, and paste-ready log text):
`docs/reports/2026-10-09-group-sync.md`, `docs/reports/2026-10-09-bluetooth-radio.md`, `docs/reports/2026-10-09-screen-share.md`.

## 1. Verification the lead ran at the end

`./gradlew --continue :core:calling:jvmTest :core:calling:testAndroidHostTest :core:messaging:jvmTest :core:messaging:testAndroidHostTest
:core:network:jvmTest :core:network:testAndroidHostTest :ui:chat:jvmTest :ui:callui:jvmTest :core:persistence:jvmTest
:desktop:compileKotlinJvm :app:compileDebugKotlin` -> **BUILD SUCCESSFUL, exit 0.** 153 of 156 tasks were `UP-TO-DATE`: Gradle only marks a
test task up to date when it already passed with the identical sources, so the three streams' own runs are the evidence (counts in each report:
messaging 223 jvm / 522 android host, ui:chat 397, network 424 / 473, calling and callui green). Not run, and known:
- `:desktop:jvmTest`: ERROR-106 (`DesktopEngineGroupSessionUpTest`) fails, unrelated, untouched.
- `:core:persistence:testAndroidHostTest`: 12 failures (`FlashSettingsDataStoreTest`, `DiscoveryModeSettingTest`, "Unable to rename
  preferences_pb.tmp"), a Windows DataStore file-rename problem that touches none of this work. **Unproven** that it fails on a clean tree
  (no stash allowed). Migration, schema and invariant tests pass.

## 2. Group history sync (ADR-100, UI-057)

**You asked:** files 7 days, UI design doc, implement, my recommendation for the rest.

**Done:** the two suspected defects were proven first with failing tests, then fixed: S1 (a device holding a recent row never asked for older
missed rows) and S2 (a fixed 24 h window lost the middle of a long absence); ERROR-126/127. New: signed `historyCeiling`
(NONE/H24/D7/D30/ALL, default D30; admin-only control in the group settings sheet), the join card (once per group; chips clamped to the ceiling;
files switch; "no history means no files"), "Load older messages", a per-holder contiguous watermark, 100-row pages with a `page` marker and
resume, the banner "N of about M", returning-member window, Room schema 13. UI doc `docs/ui/group-history-join-card.md` written first
(DESIGNED, 5 approaches). Wire is additive and documented in `docs/protocol.md`.

**Decisions I took for you** (reverse any of them):
| Item | Decision | Why |
|---|---|---|
| Files | Offered/kept 7 days, swarm retention not raised | your instruction; 30 days of files would cost storage on every holder |
| O2 returning member | `min(ceiling, max(7 d, time away))` | 7 days is a guaranteed floor, never a 30-day pull on every reconnect |
| O3 "any admin" | One seam `GroupAdminPolicy.isAdmin` (owner or an `admin` cert) | **Correction:** co-admins partly exist already (`MemberCert.ROLE_ADMIN`, ADR-063). The check lives in two places, so a future co-admin rule must change both. Owner-loss path A is still your decision |
| O4 retention | No pruner is wired; default 365 days; pinned by a test | a future pruner under 30 days would break the 30-day promise |
| Signing | D30 keeps the v1 signature bytes, others sign `flash-gset-v2` | keeps every stored signature valid |
| Join card | inline card, not a dialog | does not block the chat; reduced-motion aware |

**Limits:** old builds ignore a non-D30 ceiling and the new request keys (mixed fleet is safe but not equal). The ceiling is enforced by honest
holders, not a confidentiality control. **Schema 13 may collide** if another change also bumps the database version: check `DATABASE_VERSION`
before committing. Pre-feature members never see the card. Tests owed: GSY-01..GSY-12 (TEST-BACKLOG 4zq).

## 3. Bluetooth / serial-port radio link (ADR-101, PROPOSED)

**You said:** the serial port carries data (the radio shows as a COM port). **So I built everything that does not need the radio**, plus the
tool that makes tomorrow's test a short run. **No radio, phone or Bluetooth link was used.**

**Built (`:core:network`, desktop, app manifest):** KISS and AX.25 UI codecs (PID F0 only; CC/CD refused, the plan's error E3), the Flash
radio frame for the military profile (AES-256-GCM, counter nonce, replay window, rotating 4-byte tag and station label, segmentation, TTL
clamp), `KissTncDriver` (pacing, reconnect, PROBE evidence lines), a Windows/Linux serial link on jSerialComm 2.11.4, an Android RFCOMM link
with Android 12+ permissions, a simulated radio, and the BT-00 spike tool: `./gradlew :desktop:radioLinkTest` (window) and
`:desktop:radioLinkTestCli` (headless, with `--list` and `--simulated`). Exact bytes: `docs/network/RADIO-WIRE-FORMAT.md`. The 12-step
checklist for tomorrow is section 8 of the radio report. **Today's finding:** this laptop already lists COM15, COM7, COM16 and COM6; nobody
opened them, so which one is the radio is unknown.

**Decisions taken for you:** own compact AEAD instead of FSEC (FSEC leaves too little of a 220-byte frame); per-segment AEAD so a forged
piece cannot poison reassembly; **no change to `FlashTransportType` or the planner** (shared dirty files, an enum change risks every `when`);
Android uses paired devices only (no discovery, no location permission); the tester is launched from Gradle only so it cannot ship by
accident; test frames use a public key (proves the format, not secrecy). New dependency: jSerialComm 2.11.4, Apache-2.0 OR LGPL-3.0, native
library at runtime; **confirm it appears in the generated third-party notices** (not checked).

**Not done:** the adapter from the real pairing key to the radio session, `FlashTransportType.BLUETOOTH` and the planner hook,
phone-to-phone RFCOMM sessions, an Android debug entry (so BT-16 is blocked), chat integration, relay/gateway logic, voice, 9600 baud.
The window layout has never been seen by a person. Tests owed: BT-00 (updated) and BT-09..BT-17 (4zp). ERROR-130 (queue bound) found and fixed.

## 4. Screen share (ADR-102)

**Done:** a desktop device (Windows, Linux) shares a screen or window in 1:1 and group video calls. The camera track is replaced, not added
(no renegotiation); the camera is released during the share and restored after. Wire: `ss`/`sst` on the call Status frame (old builds ignore
them and show the picture as the presenter's camera). One presenter at a time, latest start wins even with clock skew. Own quality ladder
(1080p/10 fps down to 540p/5 fps), watcher cap by tier (HIGH 4, MEDIUM 3, LOW 2). Android and desktop receivers show it letterboxed with
"X is presenting"; the presenter sees "You are sharing - Stop" and a picker in the More panel. ADR-098's 540p/360p work is intact.
Design `docs/calling/SCREEN-SHARE-DESIGN.md`, UI text in `docs/ui/calling-ui.md` (UI-050g).

**Premise corrected (as I told you):** receivers decode one stream each, but the presenter still encodes once per watcher because calls are a
mesh. The cap and the low frame rate are the cost control. The ladder numbers are **first guesses** until EXP-024.

**Not done:** **the Android presenter (MediaProjection)**, with exactly what is missing listed in the report; shared-content audio; picker
thumbnails; closed-window detection. The JVM backend cannot express "keep resolution over frame rate" (ERROR-136, open), and what happens
when the shared window is closed is unknown (ERROR-137, open). Bugs found and fixed: ERROR-134 (a presenter who hung up stayed presenter),
ERROR-135 (a source disposed before its sinks were detached, the ERROR-123 class). Eleven mutation checks, all killed. Tests owed:
SHARE-01..SHARE-14 and EXP-024 (4zo).

## 5. Things only you can decide or do

1. Run the radio checklist (BT-00) and send back the two exported logs; then we wire the pairing key and decide `FlashTransportType.BLUETOOTH`.
2. Device-test screen share (SHARE-01, SHARE-06 first), especially stop-share on Windows **and** Linux; then decide whether to build the Android presenter.
3. Device-test GSY-01/04/06 on two phones and a desktop.
4. Decide owner-loss path A (co-admins), whether 1:1 calls should follow ADR-098's heights, and which Transsion option to pursue (HIB-01 first).
5. Before committing: agree a commit split (the tree mixes this work with the ERROR-125 engine refactor and the 540p work), and check the schema-13 collision and the jSerialComm notice.

## 6. Housekeeping done by the lead

Merged each stream's paste-ready text into `logs/progress.md`, `logs/handoff.md`, `docs/decisions.md` (ADR-100/101/102), `logs/errors.md`
(ERROR-126/127/130/134..137), `logs/experiments.md` (EXP-023/024 templates) and `docs/testing/TEST-BACKLOG.md` (4zo, 4zp, 4zq). Nothing deleted.
The agents wrote their own docs (join-card UI doc, wire-format doc, screen-share design, radio tester UI doc, protocol.md group sections).
Honest process note: the screen-share agent's first mutation run reported all mutants "survived" because its harness could not find
`gradlew.bat`; it fixed that and reran (11 of 11 killed). The group-sync agent was cut off once by a usage limit and was resumed; it
finished and its report states which checks it did not run.
