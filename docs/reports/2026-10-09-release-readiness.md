# Release readiness audit, 2026-10-09

Auditor: read-only release-readiness agent. Tree: branch `dev`, HEAD `450dcc1d` (== `origin/dev`), plus 145 uncommitted paths (90 modified, 55 untracked).
Nothing was edited, committed, tagged, pushed, signed or published. The only side effects are build outputs under `build/` and
`publishToMavenLocal` writes into `~/.m2` (see "Side effects" at the end).

## 1. Verdicts

| Target | Verdict | One line |
|---|---|---|
| **A. Android app + desktop app** | **READY WITH CONDITIONS** (as a labelled pre-release beta only; **NOT READY** for a stable 2.0.0 / Play) | Release builds succeed (APK, MSI) and nothing dangerous ships, but the release content is uncommitted, there is no release signing key on this machine, versions are not bumped, the lint and CI gates are red, and the shared-engine refactor and schema 13 have never run on a device. |
| **B. Library (15 JitPack modules)** | **NOT READY** | `core-calling` and `ui-callui` POMs name `com.shepeliev:webrtc-kmp-android:0.125.11-flash-1`, which exists nowhere a consumer can fetch it (verified 404 on Maven Central and JitPack). The tree also carries a binary-breaking `FlashConfig` change under an unchanged version number. |

## 2. Blockers (must fix before tagging)

| # | Blocker | Applies to | Evidence | Fix direction |
|---|---|---|---|---|
| B1 | **The release content is not committed.** 90 modified + 55 untracked paths (about +4.4k/-1.3k lines tracked, plus new modules of code) mix five streams: ERROR-125 shared-engine refactor (ADR-099), 540p video heights (ADR-098), group history sync + Room schema 13 (ADR-100), radio/Bluetooth link (ADR-101), screen share (ADR-102). `schemas/.../13.json` is **untracked**. A tag cut from `HEAD` would contain schema 12 code; JitPack builds only committed files. | A, B | `git status`; `DATABASE_VERSION` is 12 at `HEAD` and 13 in the tree; `git show HEAD:...FlashDatabase.kt` | Commit (split in section 7), then build from the clean commit. |
| B2 | **No release signing key.** No `keystore.properties`, no `FLASH_KEYSTORE*` env, no `~/.flash-signing`. `assembleRelease` yields `app-release-unsigned.apk`. The v2.0.0-beta APK was signed with the **Android debug key** (`docs/release-build.md`), so a new release key makes every existing beta install refuse the update (data loss on uninstall). The owner's decision (new key now vs keep debug key through the beta) is still open. | A | file checks; `docs/release-build.md` "The v2.0.0-beta APK was signed with the debug key" | Owner decides; for an in-place update from the beta, sign with the same debug keystore (`~/.android/debug.keystore` exists on this PC) or accept a one-time uninstall. Back the key up. |
| B3 | **Versions not bumped; installer would collide with the installed beta.** `versionCode = 2`, `versionName = "2.0.0-beta"`, `flashLibraryVersion = "2.0.0-beta"` (all identical to the tag `v2.0.0-beta`, which is 138 commits and 827 files behind). Desktop `packageVersion = "2.0.0"` produced `Flash-2.0.0.msi`, the **same file name and MSI version** as the shipped beta installer, so Windows will not treat it as an upgrade. Publishing the dev tree as `2.0.0-beta` would also silently replace the contents of an already-published version. | A, B | `app/build.gradle.kts:19-20`, `build.gradle.kts:8`, `desktop/build.gradle.kts` (`packageVersion`), `dist/2.0.0-beta/` | Section 6 proposes numbers. |
| B4 | **`core-calling` / `ui-callui` are unresolvable for consumers.** Their published POMs depend on `com.shepeliev:webrtc-kmp-android:0.125.11-flash-1` (the vendored fork, `third_party/webrtc-kmp`, an included build). It is not in `jitpack.yml`'s install line and is not on Maven Central (404; only upstream `0.125.11` exists) or JitPack (404). The live `v2.0.0-beta` POM on jitpack.io has the same dependency, so this is a regression since 1.1.0 that the beta already shipped. README advertises both artifacts. | B | `curl` of Central, JitPack and the live beta POM; local `~/.m2/.../core-calling-android/2.0.0-beta/*.pom` | Either publish the fork (add its `publishToMavenLocal` to the install line and relocate the coordinates), or depend on upstream, or remove the two artifacts from the README "Published modules" until solved. Then verify by resolving from a clean `~/.m2` (`sample/consumer`). |
| B5 | **The engine refactor and schema 13 have no device evidence.** The ERROR-125 refactor rewrites `Flash.kt` (-422), Android `FlashEngine.kt` (-415), `DesktopEngine.kt` (+/-1016), `ReceivePipeline`, the inbound router and the path sanitizer: i.e. the receive path of every file and the boot path of both apps. Its 13 device checks (`ENG-01`..`ENG-13`) are all TODO. Group history sync adds a Room migration 12 -> 13 never run on a phone (`MIG-01` TODO; the v2.0.0-beta database is **version 4**, so a beta -> new upgrade runs nine migrations on real data). The results log in the backlog has one entry (2026-10-02, not about these changes); 378 test cases are TODO. | A, B | `docs/testing/TEST-BACKLOG.md` 4zm, 4zq, MIG-01 | Run the gate set in section 8 before the tag, on the **release** artifacts. |
| B6 | **`:app:lintRelease` fails: 4 errors (NewApi).** `FlashCallScreenSupport.kt:27,30,36`: `PictureInPictureParams.Builder`, `setAspectRatio`, `build`, `enterPictureInPictureMode` need API 26, minSdk is 24. The call sites are guarded at run time by `supported()` (`SDK_INT >= O`), so this is probably a missing `@RequiresApi(26)` rather than a crash, but CI's lint step (policy: 0 errors since 2026-09-23) cannot go green. Introduced after the 2026-09-23 clean state (file last touched in `b7d92981`). | A | `rel_lint` exit 1; report `app/build/reports/lint-results-release.html` | Annotate or wrap; re-run `:app:lintRelease`. |

## 3. Should fix (before the release, not strictly blocking)

1. **CI is red on `origin/dev`** (run 37801471807, HEAD `450dcc1d`, and earlier runs): `RotatingFileLogSinkTest` (`files rotate...`, line 79), `PttSessionEngineSessionTest` (line 146), `FlashShimContractTest` (line 53), `DesktopEngineGroupSessionUpTest` (ERROR-106), and three `DesktopMediaDevicesTest` cases (headless runner, no libwebrtc natives). The lint step is skipped because the test step fails first. Locally reproduced: `RotatingFileLogSinkTest` (Windows, `:core:common:testAndroidHostTest`), `FlashShimContractTest` (stale test: `FlashPermission` gained `Camera`, the test still asserts only `Microphone`), ERROR-106. `PttSessionEngineSessionTest` passed locally (Linux-only or timing). Neither `RotatingFileLogSinkTest` nor `FlashShimContractTest` is in the "known pre-existing failures" list given for this audit: **report them as NEW to the lead's list** (both come from committed code, not from the uncommitted streams).
2. **jSerialComm licence election.** The generated desktop notices list `jSerialComm 2.11.4 - Apache License 2.0; GNU Lesser GPL, Version 3` but include **no LGPL-3.0 text and no statement of which licence Flash elects** (JNA has `config/aboutlibraries/licenses/jna-election.json` for exactly this). Add an override like `jna.json` electing Apache-2.0 (or ship the LGPL text and note the relink right). The jar is in the MSI input (`libs-mapping.txt`) and its natives are extracted at run time; it is **not** in the Android APK (jvmMain only; APK notices have no entry, correct). Hershey: `NOTICE` carries the required acknowledgement and the generated notices include "Hershey Fonts use restriction" in both app and desktop files (OK).
3. **Unused Bluetooth permissions ship in the APK.** `BLUETOOTH` (max 30), `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` (neverForLocation) are in the manifest and in the release APK (`aapt2 dump badging`), but nothing requests or uses them (the radio classes are fully removed by R8: 0 hits in `mapping.txt`). They add Play permission scrutiny and an install-time Nearby-devices line for no benefit. Remove from the manifest until the Android radio entry exists.
4. **Radio tooling in shipped artifacts.** `RadioLinkTestApp`/`RadioLinkTestMain.class` are inside the desktop jar and the MSI (launchable with `java -cp`, though not by the app UI); `core-network` publishes the radio/KISS/diag classes as `public` API (e.g. `RadioLinkTester`, `RadioLinkTestHarness`) and `core-network-jvm` pulls jSerialComm as a runtime dependency for every consumer, with none of it in `docs/architecture/public-api.md` or the README. Mark `@FlashInternalApi`/`internal` or document as experimental; consider moving the window to a non-shipped source set.
5. **Library API break is real but only noted in an internal doc.** `FlashConfig` changed from a data class with `receivedFilesDir: File?` to `receivedFilesPath: String?` plus Android shims; `docs/architecture/public-api.md` says "Binary compatibility with 0.x JitPack artifacts is NOT preserved (ADR-099)". Needs a migration note in the release notes and README (the README table row was edited in the tree). `sample/consumer` uses only `FlashConfig(displayName=...)` (unaffected) but the samples were **not compiled** in this audit.
6. **README inaccuracies.** "Supports **Android 8.0 (API 24)**" and "minSdk 24 (Android 8.0)": API 24 is Android 7.0. The install snippets and installer names (`Flash-2.0.0.exe`) must follow the new version. README says nothing about Linux, group swarm, group history, screen share.
7. **No CHANGELOG / release notes file.** Only GitHub release bodies. 138 commits since the tag (47 feat, 43 fix, 41 docs); write notes (section 9 lists the user-visible items and the known limitations: ERROR-074 Transsion freezer, screen share is desktop-presenter only, `ERROR-136/137`).
8. **JitPack on the new tree is verified only locally.** The exact install line passes with JDK 21 and with JDK 17 (JitPack's pinned JDK). A real JitPack build was not triggered (would publish). The `v2.0.0-beta` JitPack module list has no `core-swarm` and no `core-ptt-android/-jvm`, confirming those are first published by the next tag.
9. **GitLab mirror is 232 commits behind** `origin/dev` (`gitlab/dev` = `256b80f2`, `origin/dev` = `450dcc1d` = local). Push order is the owner's call.
10. **Hygiene:** working-copy CRLF in about 30 modified files (git will normalise to LF via `.gitattributes`, produces warnings, not content changes); four screenshots tracked at the repo root (`first-screen-device*.png`, `reconnected-screen.png`); local logs (`*.log`) and `testfile.bin` at the root are untracked/ignored but clutter; `.github/workflows/ci.yml` uses Node-20 actions and `ubuntu-latest` (migrates to Ubuntu 26 on 2026-10-19).
11. **Document the downgrade rule.** Room is forward-only (4 -> 13); installing an older APK over a newer one is unsupported. Say so in the release notes.
12. **Play-store only (not a blocker for sideload/GitHub distribution):** `targetSdk 36` (not verified against the current Play requirement in this audit), `USE_FULL_SCREEN_INTENT`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE_*` types each need a Play declaration/justification.

## 4. Nice to have

- A real `ProGuard` run for the desktop (`packageRelease*`) has never been verified (documented in `docs/release-build.md`); the non-ProGuard installers are what ships.
- Windows installers are not Authenticode-signed (SmartScreen "unknown publisher"). `.deb` can only be built on Linux and was not built here; no Linux artifact was evaluated.
- Lint: 93 warnings (18 GradleDependency, 14 NewerVersionAvailable, 11 UnusedAttribute, 10 UseKtx, 7 UnusedResources, 5 each IconLauncherShape/IconDuplicates, 4 IgnoreWithoutReason, 4 AndroidGradlePluginVersion, 3 InlinedApi, 2 StaticFieldLeak).
- Known flaky `SwarmInteropTest...OriginDropAndRejoin` (7-12 %, ENG-13): did not fail in this run.
- A signed, checksummed `dist/<version>/` produced by `tools/build-release.ps1` (the script was not run; its steps were reproduced with direct Gradle calls).

## 5. Evidence table

All Gradle runs: JDK 21 (Gradle-provisioned JBR 21) with the AF_UNIX fix from `gradle-build-env`, `--no-daemon`, network available, no `clean`.

| Check | Command (abridged) | Result |
|---|---|---|
| Branch / tags / push state | `git branch`, `git tag`, `git rev-parse HEAD origin/dev gitlab/dev` | `dev`; tags `v1.0.0`, `v1.1.0`, `v2.0.0-beta` (2026-09-19, `c2dfd72f`); `HEAD == origin/dev == 450dcc1d`; `gitlab/dev = 256b80f2` (232 behind) |
| Distance from last tag | `git log v2.0.0-beta..HEAD`, `git diff v2.0.0-beta --shortstat` | 138 commits, 827 files, +137k/-12k lines |
| Working tree | `git status --short` | 90 `M`, 55 `??`; unchanged by this audit (145 before and after) |
| Android release build | `./gradlew :app:assembleRelease` | **exit 0**, 9m21s, `app-release-unsigned.apk` 53,777,294 bytes, R8 on, `mapping.txt` produced; `versionCode 2`, `versionName 2.0.0-beta`, `targetSdk 36`, `compileSdk 37`, not debuggable |
| APK permission set | `aapt2 dump badging` | 23 uses-permission entries incl. `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`; no cleartext flag; `allowBackup=true` with data-extraction and full-backup rules excluding databases, shared prefs, `external`, logs |
| Signing | file checks | no `keystore.properties`, no `FLASH_KEY*`, no `~/.flash-signing`; debug keystore present (`~/.android/debug.keystore`) |
| Lint | `./gradlew :app:lintRelease` | **exit 1**: 4 errors (NewApi, section 2 B6), 93 warnings |
| Desktop installer | `./gradlew :desktop:packageMsi` | **exit 0**, 2m25s, `desktop/build/compose/binaries/main/msi/Flash-2.0.0.msi` 115,548,891 bytes, `--app-version 2.0.0`; jSerialComm 2.11.4 jar is in the jpackage input mapping |
| Third-party notices | `./gradlew :app:generateThirdPartyNotices :desktop:generateThirdPartyNotices` | **exit 0**; desktop file lists jSerialComm (no licence text/election, section 3.2) and Hershey; Android file has Hershey, no jSerialComm (correct) |
| Library publication, JDK 21 | exact `jitpack.yml` install line (15 modules, `-x test -x lint`) | **exit 0**, 1m59s; all 15 artifacts at `2.0.0-beta` with `-android`/`-jvm` variants; inter-module POM deps all `2.0.0-beta` (no stale 1.x versions in any 2.0.0-beta POM) |
| Library publication, JDK 17 | same line with `eclipse_adoptium-17` (JitPack's pin) | **exit 0** |
| Published POM check | read `core-calling-android-2.0.0-beta.pom`; `curl` Central/JitPack/live beta POM | depends on `com.shepeliev:webrtc-kmp-android:0.125.11-flash-1`; Central 404, JitPack 404; live beta POM identical (B4) |
| JVM unit tests | `--continue :core:{common,security,discovery,network,transfer,persistence,messaging,calling,ptt,swarm,engine}:jvmTest :ui:{theme,platform-shims,chat,callui}:jvmTest :desktop:jvmTest` | exit 1: **2 failing tasks**. `:ui:platform-shims:jvmTest` 1/39 (`FlashShimContractTest`, **new to the lead's list**); `:desktop:jvmTest` 1/162 (`DesktopEngineGroupSessionUpTest` = ERROR-106, known). Everything else passed (101 tasks up to date or run green) |
| Android host tests | `--continue :core:{common,security,discovery,network,transfer,persistence,messaging,calling,engine}:testAndroidHostTest :ui:platform-shims:testAndroidHostTest :app:testDebugUnitTest` | exit 1: `:core:persistence` 12/48 (the known DataStore rename failures, `FlashSettingsDataStoreTest`/`DiscoveryModeSettingTest`); **`:core:common` 1/114 `RotatingFileLogSinkTest > files rotate and total size stays bounded` (new)**; **`:ui:platform-shims` 1/12 `FlashShimContractTest` (new)**; messaging, calling, network, engine, transfer, security, discovery, app unit tests passed (some `UP-TO-DATE` = passed earlier with identical inputs). `:app:testReleaseUnitTest` does not exist under AGP 9 (used debug) |
| Migration coverage | read `FlashSchemaStepsTest`, `FlashMigrationsChainTest`, `FlashJvmMigrationsTest` | chain 1->13 contiguous and registered on both Android and JVM; JVM tests open v1, v3, v4, v6..v9 databases and let Room validate; **no v12 -> v13 data test and no MigrationTestHelper test**, and `MIG-01` (real device) is TODO |
| Schema collision | `git grep @Database`, `ls schemas/` | one `@Database` (`DATABASE_VERSION = 13`), schemas `1,3..13.json`, `13.json` contains `group_history_state`, `group_sync_watermark`, `historyCeiling`; **no collision in this tree** (HEAD has 12). Risk is only that `13.json` is untracked (B1) |
| CI status | `gh run list/view` | last 6 runs on `dev` all failed in the unit-test step (details section 3.1); lint step never reached |
| Secrets / dev bypasses | `git grep` for passphrase/secret/privateKey in log calls; trustAll / INSECURE / bypass / skipVerify | none found: log lines carry ids, epochs and sizes only; `createInsecureRfcommSocket` exists only behind `secure=false` in the radio helper, which is stripped from the APK; no cleartext config; `FlashStressTestScreen` is not wired (`BuildConfig.DEBUG` mentioned in a comment only) |
| Foreground services | manifest | `FlashBackgroundService connectedDevice|dataSync`, `FlashCallService microphone|camera|connectedDevice`, `PttSessionService microphone|mediaPlayback|connectedDevice`; no MediaProjection service, consistent with "Android presenter not built" |

## 6. Proposed version numbers

Reasoning: the last tag is a pre-release (`2.0.0-beta`), so semver gives no stability promise to break; but the changes add a wire-additive feature set, Room 4 -> 13, new public APIs and one binary-breaking API change, so a **minor** pre-release bump is the honest signal and also fixes the MSI collision.

| Item | Current | Proposed |
|---|---|---|
| `flashLibraryVersion` / git tag | `2.0.0-beta` | **`2.1.0-beta`** / `v2.1.0-beta` |
| `versionName` (Android) | `2.0.0-beta` | **`2.1.0-beta`** |
| `versionCode` | 2 | **3** (must exceed the installed beta) |
| Desktop `packageVersion` (MSI/EXE/deb) | `2.0.0` | **`2.1.0`** (installers become `Flash-2.1.0.msi/.exe`, a genuine upgrade over `2.0.0`) |
| Room `DATABASE_VERSION` | 12 (HEAD), 13 (tree) | 13 |
| Wire | additive keys only (`ss`/`sst` call status, history ceiling and `page` markers, `gs1`, group frames) | no protocol version bump needed; mixed fleets work but old builds ignore a non-D30 ceiling and show a screen-share picture as the presenter's camera |
| Alternative | | `2.0.0-beta.2` + MSI `2.0.1` is also valid but leaves two version schemes in play; do **not** reuse `2.0.0-beta`. Promote to `2.1.0` / `2.0.0` only after the section 8 gates and the ERROR-125 closure |

## 7. Proposed commit split

Nothing is committed yet. Suggested order, each commit buildable on its own (verify with a `git worktree` build or at least `:app:compileDebugKotlin :desktop:compileKotlinJvm` per commit); files that straddle streams need `git add -p`.

1. `refactor(engine): shared FlashEngine for Android and desktop (ERROR-125, ADR-099)`: `core/engine` (commonMain `FlashEngine.kt`, `FlashConfig.kt`, `FlashInboundRouter.kt`, `FlashPathSanitizer.kt`, `swarm/PeerOrderedInbox.kt`, androidMain `Flash.kt`/`FlashEngine.kt`/`store/*`/`PassphraseKeeper.kt`, jvm `FlashDesktop.kt`, swarm storage and `MagicFrameRouter`, `build.gradle.kts`, tests), `EmptyFlashDiscovery/Network/TransferRepository`, `ReceivePipeline`(+test), `core/swarm/SwarmDriver.kt`, `desktop/EngineProxies.kt`, the engine parts of `DesktopEngine.kt`/`DesktopShell.kt`, `FlashDesktopEngineTest`, `DiscoveryEngineHolder.kt`, README `receivedFilesPath` row, `docs/architecture/public-api.md`.
2. `feat(calls): group video 540p/360p heights (ADR-098)`: `GroupVideoRouter(+Test)`, the `groupCaptureProfile` hunks of `FlashGroupCallSession.kt`, `FlashSettingsScreen`(+test), `FlashCallHealthBanner`.
3. `feat(groups): history sync, join card, schema 13 (ADR-100, ERROR-126/127)`: persistence (`FlashDatabase`, `FlashSchemaSteps`, `FlashMigrations`, `GroupHistory*`/`GroupSyncWatermark*`, `GroupSettingsEntity`, **`13.json`**, tests), messaging (`GroupHistoryPolicy`, `RealFlashChatRepository`, group codec/settings/signing/canonical/wire files), `ui/chat` (`FlashGroupHistoryCard`, sync banner, settings sheet, conversation screen), `MainActivity.kt`, `docs/ui/group-history-join-card.md`.
4. `feat(network): KISS/AX.25 radio link groundwork and BT-00 tool (ADR-101)`: `core/network` kiss + radio, `core/network/build.gradle.kts`, `gradle/libs.versions.toml`, `desktop/RadioLinkTestMain.kt` and the `radioLinkTest*` tasks, `docs/network/RADIO-WIRE-FORMAT.md`, `docs/ui/radio-link-test.md`; **leave the manifest Bluetooth permissions out** (3.3).
5. `feat(calls): screen share, desktop presenter (ADR-102)`: `core/calling` `ScreenShare*`/`ScreenCapture*`, `CallCoordinator`, `CallFrameCodec`, `CallStatusBook`, `CallWireFrame`, `FlashCallSession`, `FlashCalling`, `FlashCallModels`, `RtpSenderTuning*`, remaining hunks of `FlashGroupCallSession`/`GroupVideoRouter`, `ui/callui` (`FlashCallShare`, dock, extras, grid, screen), `flash_ic_screen_share.xml`, `FlashIcons.kt`, webrtc-kmp `DesktopScreenCapture.kt` + `DesktopVideoStreamTrack.kt` + `MODIFICATIONS.md`, `docs/calling/SCREEN-SHARE-*.md`.
6. `docs: logs, ADR-099..102, reports, test backlog`: `AGENTS.md`, `logs/*`, `docs/decisions.md`, `docs/testing/*`, `docs/reports/*`, protocol/security/platform notes, Transsion research.
7. `chore(release): 2.1.0-beta`: the version bumps, `jitpack.yml` (add the fork if B4 is solved), jSerialComm licence override, lint fix, stale-test fixes. Tag only after the gates in section 8.

## 8. Proposed release checklist (in order)

1. Owner decisions: signing key (B2), webrtc-kmp resolution (B4), whether screen share and the Bluetooth permissions ship, version numbers (section 6).
2. Commit the streams (section 7). Confirm `13.json` is tracked and `git status` is clean.
3. Fix B6 (lint), the two new test failures (`FlashShimContractTest` is a one-line stale assertion; `RotatingFileLogSinkTest` needs a look), ERROR-106 if cheap; get CI green on `dev`.
4. Bump versions, add jSerialComm election, remove unused BT permissions, correct README, write release notes.
5. Re-run from the clean commit: `:app:assembleRelease`, `:app:lintRelease`, `:desktop:packageMsi packageExe` (or `tools/build-release.ps1 -NoDaemon`), the `jitpack.yml` line on JDK 17, the unit-test sweep.
6. Sign the APK with the decided key (`apksigner verify --print-certs` must print the intended certificate; compare with the beta's if in-place update matters); checksum `dist/<version>/SHA256SUMS.txt`.
7. Resolve-from-scratch test of the library: empty `~/.m2`/Gradle cache, `sample/consumer` and `sample/consumer-desktop` against the published coordinates (this is the only test that proves B4 is fixed).
8. **Device gates (section below)** on the signed APK and the MSI, not on debug builds.
9. Push `dev` (origin, then gitlab), merge to `main` per the owner's flow, tag `v2.1.0-beta`, push the tag. **The tag is the publish** (`release-dry-run` memory): JitPack builds it at once, so step 5 must already be green.
10. Verify JitPack: build log, module list (expect `core-swarm`, `core-ptt-*`), POM scopes (`api` -> compile, `implementation` -> runtime), and a fresh consumer resolve.
11. Create the GitHub pre-release with APK, MSI, EXE, `SHA256SUMS.txt`, notes (known limitations, downgrade rule, schema 13). Record the commit hash in `logs/handoff.md`, the results in the backlog results log.

### Device tests that are real gates (for a labelled beta pre-release)

| Gate | Why |
|---|---|
| `MIG-01` (install over the v2.0.0-beta APK with real chats, same signing key) and `MIG-02` (desktop opens its existing DB) | Nine migrations 4 -> 13 on user data; the only test of the Android migration wrapper on a real database. Irreversible if wrong. |
| `ENG-01`..`ENG-04`, `ENG-06`, `ENG-08`, `ENG-10`..`ENG-12` | The refactor sits on every receive and boot path (resume, disk full, keystore failure not wiping chat, auto-accept trust gate, shutdown). `ENG-05`, `ENG-07`, `ENG-09` are cheap and worth adding. |
| `GSY-01`, `GSY-04`, `GSY-06` | First run of the schema-13 history sync (two phones + one desktop), the owner's own priority list. |
| Smoke of the set the owner verified on 2026-09-23, on the release artifacts: pair, chat, 1:1 call, file transfer Android <-> Windows | Regression net for the refactor; the backlog has no single "release smoke" case. Add one. |
| `SHARE-01`, `SHARE-06` (Windows and Linux stop-share) | Only if screen share is enabled in the release; otherwise ship it labelled experimental or hide it. |
| `LNX-*` | Only if a Linux package is released; none was built or evaluated. |

Everything else (the remaining roughly 330 TODO cases: group calls, swarm, UI polish `UIP-*`, `MEAS-*`, `HIB-*`, radio `BT-*`, discovery `DISC-*`) is nice-to-have for a pre-release, but each unverified area belongs in the release notes as "unit-tested, not device-verified". Open ERRORs worth stating in the notes: 074 (Transsion freezer), 136/137 (screen share), 062/063 (older partial media items), and every "fixed in code, awaiting device check" entry.

## 9. Candidate release-note content (from `git log v2.0.0-beta..HEAD`)

New: signed v2 groups up to 20 with vouched members and owner/co-owner removal; group-secret membership, invite links `flash://g/...`, signed group settings; group swarm file transfer (off by default); group history sync with a join card (30 days default, admin ceiling); screen share from desktop in calls; 1:1 voice-to-video upgrade, typed media failures; push-to-talk on desktop; Linux desktop groundwork (keyring vault, XDG paths, native notifications); launch splash; UI polish roadmap (forwarding, shared-content viewer, pinned banner, staging tray); persistent log and export with evidence probes; discovery resilience (remembered routes, subnet sweep, directed beacon). Fixes: ERROR-073..130 (see `logs/errors.md`). Breaking for library consumers: `FlashConfig` and `FlashEngine` shape (ADR-099).

## 10. What could not be verified

- No device, emulator, radio, Linux machine or second PC was used: every behaviour claim above is build/unit evidence only.
- No JitPack build of the new tree (would publish); only the local equivalent on JDK 21 and 17.
- The signed APK path (`apksigner`, `zipalign`) and `tools/build-release.ps1` were not run; no key exists to test with.
- `.deb`/Linux packaging, EXE packaging (`packageExe`; only MSI was built), `packageRelease*` ProGuard desktop build.
- Whether the MSI upgrades over an installed 2.0.0 (inferred from identical version, not tested).
- Current Google Play policy values for `targetSdk` and permission declarations (not checked online).
- `PttSessionEngineSessionTest` and the three `DesktopMediaDevicesTest` failures on Ubuntu CI were read from the CI log only; the cause of `RotatingFileLogSinkTest` (a missing "newest line kept" at line 79, on Windows and Linux) was not investigated.
- The sample consumers (`sample/consumer*`) were not compiled against the published artifacts.
- The unit-test sweep re-used Gradle results for tasks whose inputs were unchanged (`UP-TO-DATE` means they passed earlier on identical sources, not that they ran now); the overnight report's counts (messaging 223/522, ui:chat 397, network 424/473) were not re-counted.
- Android lint `lintVital` passed inside `assembleRelease`; the full `lintRelease` was the failing one.

## Side effects of this audit

- `publishToMavenLocal` (twice) overwrote `~/.m2/repository/com/transfer/flash/*/2.0.0-beta` with the dev-tree build (an older 2.0.0-beta from 2026-09-18 had been there). Delete those folders if any local consumer should see the tagged beta again.
- Build outputs under `app/build`, `desktop/build`, module `build/` directories and `dist/` untouched; the new MSI is only in `desktop/build/compose/binaries/main/msi/`.
- Scratch logs and scripts are in the session scratchpad, not in the repo.
