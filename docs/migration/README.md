# Flash Multiplatform Migration — Execution Index

This directory replaces the single-file plan at `FLASH_MULTIPLATFORM_MIGRATION_PLAN.md`.
That file is now a short charter. **All executable work lives here.**

## Read these first, in order

| # | File | Purpose |
|---|---|---|
| 1 | [CONVENTIONS.md](CONVENTIONS.md) | Rules every executing agent MUST follow. Non-negotiable. |
| 2 | [AUDIT.md](AUDIT.md) | Verified ground truth about the repo. Supersedes any claim in the old plan. |
| 3 | [DECISIONS.md](DECISIONS.md) | Decisions D1–**D11**. **All eleven are answered** — D1–D9 on 2026-08-31, then **D10 = Option A** and the new **D11 = Option B** on 2026-09-05, together with an explicit **R8 authorisation** for 13B-3's `ChunkFrame` rewrite (byte-identical output required). Ignore any older framing about D1 gating Phase 06 or D10 being `_pending_`. **No phase is blocked on a decision any more**; what remains blocked is blocked on a predecessor phase. Two narrower human inputs are still open and are *not* decisions: D5=C's three implementation sub-answers (09B-2) and the settings-tier ABI option (09B-3). |

## Phases

Execute in numeric order. Do not skip. Do not reorder. Each phase file is
self-contained and states its own preconditions.

The **Status** column below was rebuilt on 2026-09-05 by reading every `## Phase` entry in
`logs/migration.md` and confirming each cited commit exists with `git log -1 <sha>`. It used to be
a "Blocked by" column that recorded *original* preconditions, which meant a done phase whose
precondition happened to be a decision still read as blocked — Phase 14 was misreported that way.
Status is authoritative; each phase file's own preconditions section holds the dependency detail.

| Phase | File | Status (verified 2026-09-05) | Risk |
|---|---|---|---|
| 00 | [PHASE-00-baseline.md](PHASE-00-baseline.md) | **DONE** (`8506036`) | none |
| 01 | [PHASE-01-hygiene.md](PHASE-01-hygiene.md) | **DONE** (`c0c94e2`) | low |
| 02 | [PHASE-02-delete-wslegacy.md](PHASE-02-delete-wslegacy.md) | **DONE** (`275c704`) | low |
| 03 | [PHASE-03-logging.md](PHASE-03-logging.md) | **DONE** (`da4fba6`) | low |
| 04 | [PHASE-04-time-uuid-locale.md](PHASE-04-time-uuid-locale.md) | **DONE** (`254c474`, `e85b3d5`) | low |
| 05 | [PHASE-05-concurrency.md](PHASE-05-concurrency.md) | **DONE** (`2339cb8`) | medium |
| 06 | [PHASE-06-kmp-pilot.md](PHASE-06-kmp-pilot.md) | **DONE** (`83232f4`) — ran under D1=B and D2=A | was **highest** |
| 07 | [PHASE-07-security-kmp.md](PHASE-07-security-kmp.md) | **DONE** (`fe5f9be`) | medium |
| 08 | [PHASE-08-discovery-kmp.md](PHASE-08-discovery-kmp.md) | **DONE** (`b879017`) | medium |
| 09 | ~~[PHASE-09-persistence-kmp.md](PHASE-09-persistence-kmp.md)~~ | **SUPERSEDED** by 09B — its log entry is docs-only, no code | — |
| 09B | [PHASE-09B-persistence-room-kmp.md](PHASE-09B-persistence-room-kmp.md) | **09B-1 DONE** (`328c553`, `24435bd`, `8b5fa5a`) — db tier only. **09B-2 BLOCKED** on D5=C's three unanswered sub-decisions (which encrypted desktop driver; is a commercial licence acceptable; SQLCipher file-format parity). **09B-3 BLOCKED** on the settings-tier ABI option (a) or (b). | 09B-1 done, 09B-2/3 high |
| 10 | [PHASE-10-network-kmp.md](PHASE-10-network-kmp.md) | **DONE** (`428154d`) | high |
| 11 | [PHASE-11-repositories-kmp.md](PHASE-11-repositories-kmp.md) | **DONE** (`f96797e`) — `:core:transfer` + `:core:messaging` | high |
| 12 | [PHASE-12-engine-kmp.md](PHASE-12-engine-kmp.md) | **DONE** (`4ac401b`) | high |
| 13 | ~~[PHASE-13-desktop-fileio.md](PHASE-13-desktop-fileio.md)~~ | **SUPERSEDED** by 13B — its log entry is docs-only, no code | — |
| 13B | [PHASE-13B-desktop-fileio.md](PHASE-13B-desktop-fileio.md) | **13B-1 DONE** (`fafd450`). **13B-2 DONE** (`732e7b5`, 2026-09-05) — D10 = Option A enacted as **Okio 3.4.0**; the four `java.io` seams are now `commonMain`, and `RandomAccessSinkHandle`'s supertype changed `Closeable` → `AutoCloseable` (Phase 24 release note). **13B-3a DONE** (`5e4e9a5`, 2026-09-05) — `Sha256`/`IncrementalSha256` are `commonMain` over Okio's `HashingSink`; no module edge, no new dependency, no ABI break. It went first because `ChunkFrame` calls four `Sha256` members, so the phase file's framing-then-hashing order was impossible; §13B-3 now carries the correction. **13B-3b DONE** (`a3375e3`, 2026-09-05) — the R8-authorised `ChunkFrame` rewrite; **byte-identical output proved twice** (eleven golden vectors captured from the pre-rewrite `ByteBuffer` implementation and asserted in `commonTest` on both targets, plus a temporary differential test running the verbatim old serializer against the new one over 4011 frames, `PARITY|unpairedSurrogatesExercised=2967`). No build file, no ABI change. **That authorisation is now spent — `ChunkFrame` is R8-untouchable again.** **13B-3c DONE** (`d51206b`, 2026-09-05) — `ResumeBitVector` is `commonMain` on a `LongArray`; `java.util.BitSet` was the last non-I/O pin and needed **no library at all**, only Kotlin's common `Long` bit intrinsics, so the file now has an empty import block. The persisted serialization was held to 13B-3b's byte-identity criterion even though R8 does not name the file — `toSerialized()` is reached from `public` API and its whole job is to be read back by a later build — and proved with seven golden vectors in `commonTest` plus a temporary differential test against the verbatim `BitSet` implementation (2080 randomized done-sets, 20 trimming shapes, 320 cross-restores both directions, 12 hostile payloads). The load-bearing subtlety: `BitSet.toLongArray()` **trims trailing zero words**, and no round-trip test can catch a fixed-size dump. **13B-3d DONE** (`293f12b`, 2026-09-05) — the atomics, `ConcurrentHashMap`/`Collections` and `UUID` pins are **gone from `:core:transfer`**. `kotlin.concurrent.atomics` for the dispatcher's per-frame counters (note the package — §13B-3's table said `kotlin.concurrent`, which is wrong; `javap` proves the JVM actuals are typealiases to the `java.util.concurrent.atomic` classes, so Android bytecode is unchanged, and the cost is one `@file:OptIn` with **no build-file edit**); `PlatformLock` for everything that was a monitor or a concurrent collection; `UuidIdGenerator.newId()` for the three `UUID.randomUUID()` calls. `TransferCompletionStateMachine` moved to `commonMain` with a **new 14-test `commonTest` suite** — it had none, which is also why a redundant `AtomicBoolean` CAS had survived in it — while `MultiStreamDispatcher` and `RealFlashTransferRepository` were converted **in place**, because the pipelines still pin them; they move in 13B-3e. Two of four primitives turned out to be doing nothing and were proved so by reachability, not assumed. `kotlinx.atomicfu` rejected on R10. **13B-3e DONE** (`fa95d74`, 2026-09-06) — the pipelines, and with them **the whole of Phase 13B**. `Chunker`/`ChunkStream` re-typed onto `okio.BufferedSource`; all **12** lock sites (`ReceivePipeline`'s 8 `@Synchronized` + `MultiStreamReceiver`'s 4) onto `PlatformLock`; `sortedSetOf` → `HashSet` + an explicit `.sorted()` in `buildAck`, because **`commonMain` has no sorted-set type at all** — that swap moves the ACK-ordering invariant out of the type system and into one call site, pinned by three assertions on both targets. `SendPipeline`, `MultiStreamDispatcher` and `RealFlashTransferRepository` moved **byte-for-byte** (no edit needed once the pipelines landed). Six test suites moved to `commonTest`. The module is now **23 `commonMain` / 3 `androidMain` / 1 `jvmMain`**, and its entire residual `java.*` surface is two `java.io` imports in `policy/DestinationPolicy.kt`, which is `androidMain` by design. **A desktop JVM host can now chunk, hash, frame, send, receive, verify and resume a file end-to-end in common code**, asserted on the `jvm()` target by `PipelineEndToEndTest`. Three constraints were verified against artifacts rather than reasoned about, because `compileCommonMainKotlinMetadata` is SKIPPED here and no build task certifies a dependency's *common* surface: `use { }` does **not** work on an okio `BufferedSource` in common code (`okio.Closeable` is an `expect interface`; `AutoCloseable` is absent from okio 3.4.0's commonMain metadata), `okio.IOException` **is** common, and `read(ByteArray,Int,Int)` still returns −1 at EOF. `ChunkStream`'s supertype changed `Closeable` → `AutoCloseable` — the **second** such ABI break in this phase, so Phase 24 needs a list. **Known gap:** `MultiStreamDispatcherTest` (659 lines) and `RealFlashTransferRepositoryTest` (557 lines) stayed in `androidHostTest` because both build real thread pools via `Executors…asCoroutineDispatcher()`, so the module's two largest `commonMain` files have **no `jvmTest` coverage**. Note §13B-3's "big-endian" claim is **wrong** — the wire format is little-endian. | 13B-1 low, 13B-2/3a/3b/3c/3d medium, 13B-3e high |
| 14 | [PHASE-14-desktop-discovery.md](PHASE-14-desktop-discovery.md) | **DONE** (`75d86ef`) under D6=A (JmDNS). Was previously listed here as "12 + **D6**", which read as blocked; D6 was answered 2026-08-31 and the phase shipped. **Caveat: D6's mandated throwaway spike was never run**, and no real multicast was ever exercised — only a human with two machines on one LAN can discharge that. | high |
| 15 | [PHASE-15-desktop-transport.md](PHASE-15-desktop-transport.md) | **DONE** (15-1 `ff1d36a` retroactively logged; 15-2 `4f22c1e`; 15-3/4/5/6 in the phase's closing commit, 2026-09-12). Executed per the revised D1=B sub-step plan written into the phase file *before* work started: pure helpers (`WsKeepaliveTiming`, `Ipv4Routing`) to `commonMain` with the keepalive constants hoisted (values unchanged); the nine JDK-bound plumbing files **duplicated** into `jvmMain` (androidMain originals byte-identical, untouched — duplication, not `expect`/`actual`, because the Do-NOT-modify list forbids editing the TLS/codec originals); `WsTransferClient`'s desktop twin drops only the `ConnectivityManager` route-picking (plain default routing = the original's null-context path); `JvmWsFlashNetwork` orchestrator with the full reconnect/glare/early-frames engine; 2-test desktop loopback suite in `jvmTest`. Module: 16 commonMain / 23 androidMain / 11 jvmMain; 145 android + 56 jvm tests, 0 failures outside the known persistence set. Desktop TLS is compile-verified only; the real Android↔desktop gate is Phase 16. | high |
| 16 | [PHASE-16-desktop-headless-interop.md](PHASE-16-desktop-headless-interop.md) | **HARNESS BUILT 2026-09-12; GATE CLOSED pending hardware.** The desktop harness lives in `core/engine/src/jvmTest/.../interop/` (`DesktopInteropHarness` main with advertise/discover/send/receive verbs + `DesktopEndpointFixture`, the faithful desktop port of `Flash.kt`'s transfer-half wiring incl. the #5 accept gate and RESUME ordering, + file-backed identity/trust stores). Its self-test **passes on the desktop tier**: two desktop endpoints composed over the full 13B+14+15 stack move a 512 KB file across the WS wire with matching SHA-256 (`:core:engine:jvmTest` 9/0). But G1–G6 need a **physical Android endpoint** (none attached; `adb devices` empty) and the phase's Do-NOT list forbids counting the desktop↔desktop self-test as a gate scenario, so the verdict stands **CLOSED**: **no UI phase (21–22) may merge** until a human runs the G1–G6 scenarios with a phone on the same LAN (script: use the harness verbs on this desktop + the Flash app on the phone; record both SHA-256 lines in the migration log). G7 is additionally BLOCKED ON 09B-2 (D5 = C; `store = null` in the harness until the encrypted desktop driver exists). | **gate** |
| 17 | [PHASE-17-ui-resources.md](PHASE-17-ui-resources.md) | **DONE** (`a8d9d0d`, `23267ed`) | low |
| 18 | [PHASE-18-ui-theme-kmp.md](PHASE-18-ui-theme-kmp.md) | **DONE** (`96e8799`) — read its STATUS box before reusing any of it; 14 of its steps were wrong | medium |
| 19 | [PHASE-19-ui-platform-shims.md](PHASE-19-ui-platform-shims.md) | **DONE** (`94a60a4`) — read its STATUS box before reusing any of it; 12 of its statements were wrong, D7b was overridden on evidence (no FileKit), and there are 7 shims not 8 | medium |
| 20 | [PHASE-20-ui-chat-kmp.md](PHASE-20-ui-chat-kmp.md) | **DONE** (`c5abd5d`) — read its STATUS box before reusing any of it; Steps 1 and 2 must not be executed (Step 2's "CMP requires `jvm("desktop")`" is false and would break R5 across the UI track), Step 5's build file is unbuildable, and the module ended up **100% common** | high |
| 21 | [PHASE-21-desktop-app-shell.md](PHASE-21-desktop-app-shell.md) | **BUILT 2026-09-12 (compile-verified); NOT mergeable until the Phase 16 hardware gate opens.** The `:desktop` Compose Desktop module now exists: `DesktopEngine` (the desktop composition root — NOT a `FlashEngine`; assembles the Phase 16-proven harness stack: JmDNS discovery + `JvmWsFlashNetwork` + `RealFlashTransferRepository` + the #5 accept gate + file-backed identity/trust under `~/.flash/`, with chats binding `EmptyFlashChatRepository` until 09B-2), `DesktopShell` (Option B thin shell over the four shared `:ui:chat` screens), `DesktopMain`, `DesktopHelpers`, `DesktopIdentityStores`. Executed per the correction block (C1–C4) authored into the phase file **before** coding, which voids its R5-forbidden `jvm("desktop")`/`compileKotlinDesktop` references and its stale symbol census (`JmmsFlashDiscovery`/`DesktopFileSourceOpener`/`DesktopDestinationPolicy`/`createDataChannelChannel` don't exist; `compileDebugKotlin` is not a KMP task — `compileAndroidMain` is). Gates: `:desktop:compileKotlinJvm` + `:ui:chat:compileKotlinJvm` + `:ui:chat:compileAndroidMain` + `:app:assembleDebug` all green; R6 scans clean (0 `android.*`, 0 non-Compose `androidx.*`); engine 9 + chat 264 jvmTest re-run 0-fail. **The Phase 16 interop verdict stays CLOSED — no phone was attached — so this module compiles but must not ship until a human runs G1–G6.** Its old 2026-08-31 "log entry" was fabricated (no such commit existed); the real entry is dated 2026-09-12. | medium |
| 22 | [PHASE-22-adaptive-desktop-screens.md](PHASE-22-adaptive-desktop-screens.md) | **BUILT 2026-09-12 (compile-verified); NOT mergeable until the Phase 16 hardware gate opens.** Executed per its correction block (C1–C5, authored before coding): the file's two core symbols **do not exist** — `FlashAdaptiveTwoPane` and `rememberFlashWindowSize` were deleted by ERROR-033 — so the desktop got local equivalents built from the surviving tested math (`DesktopTwoPane` + `rememberFlashDesktopWindowSize` over `LocalWindowInfo`, exactly the shape the surviving `FlashAdaptiveLayouts.kt` KDoc recommends) plus `DesktopSideBar` (inline 200.dp, never ui:theme) and the Step-5 detail panes (conversation pane deferred: desktop binds `EmptyFlashChatRepository` until 09B-2). Expanded windows (≥840dp) get sidebar + list-detail at the tested 0.38/0.62 weights; compact keeps the Phase 21 bottom-nav layout. Gates: `:desktop:compileKotlinJvm`, `:ui:chat:compileKotlinJvm` + `compileAndroidMain`, `:app:assembleDebug`, `:ui:chat:jvmTest` all green; R6 clean; no `:app` dep. Its old 2026-08-31 "log entry" was fabricated; the real entry is dated 2026-09-12. | medium |
| 23 | [PHASE-23-interop-matrix.md](PHASE-23-interop-matrix.md) | **BLOCKED ON HARDWARE + ONE DESKTOP GAP (now scoped).** Its precondition 1 is explicit: "Phase 16 is OPEN… If 16 is closed, stop" — and 16 is CLOSED pending hardware (`adb devices` empty, 2026-09-12). It needs two endpoints of EACH platform driven through the real UI (4 cells × M1–M7), which no Gradle task can provide. **The desktop pairing gap that would fail its desktop M2/M7 even with hardware attached is now DECIDED:** [research/desktop-pairing-gap-scoping.md](research/desktop-pairing-gap-scoping.md) scoped it, and on 2026-09-13 the human picked **P2 — persisted identity under `~/.flash/`, DPAPI-protected (ADR-035, [PHASE-26](PHASE-26-desktop-identity-p2.md))**. Until 26 ships, desktop pairing cells stay DEFERRED; after 26 ships, G2/G6 and phone→desktop transfers become runnable on hardware day. | **gate** |
| 24 | [PHASE-24-publishing.md](PHASE-24-publishing.md) | **Steps 1–2 DONE as local dry-run 2026-09-13; Steps 3–5 (the publish) WAITING ON 23.** Step 1: aggregate `publishToMavenLocal` verified — **all 11 converted modules emit the full three-publication layout at 1.1.0** (root jar+`.module`+`.pom`+sources, `-android`, `-jvm`), Gradle module metadata intact; `core-calling`/`core-ptt` publish their expected unconverted AARs; samples unpublished by design. Step 2: **`sample/consumer-desktop` exists and is green** (D9=A applied) — a plain `kotlin("jvm")` module with zero `project()` deps resolving the ROOT coordinates from mavenLocal(); its dependency graph proves the structural fact: `core-engine:1.1.0 → core-engine-jvm:1.1.0 → core-common-jvm:1.1.0 → coroutines-core-jvm`. Android samples + app regression green. The tag → JitPack → fresh-out-of-repo-consumer steps (3–5) are publish actions and stay with the human while 23 is CLOSED, per the phase's own precondition 1 and Do-NOT list. | medium |
| 25 | [PHASE-25-calling-stack-desktop.md](PHASE-25-calling-stack-desktop.md) — calling stack | **DECIDED 2026-09-13 (D12 / ADR-034): Option B via the VENDORED webrtc-kmp fork** (`aschulz90/webrtc-kmp` → `third_party/webrtc-kmp/` composite build, targets trimmed to Android+JVM, backend bumped 0.8.0→0.17.0 — the authorized R10 exception; fallback to a thin own layer over webrtc-java if the stability gate fails). Research gate discharged 2026-09-12 ([research/calling-stack-desktop-jvm-research.md](research/calling-stack-desktop-jvm-research.md)); fork facts re-verified 2026-09-13 from its actual sources (unpublished anywhere, unmaintained since 2024-11, Android pin stays M125, **desktop screen sharing recorded as a future feature** — native capture exists, the wrapper never exposed it). Execution now authorized, in stage order: Stage 1 = KMP conversion + `org.webrtc` pin abstraction (A1–A5), Stage 2 = vendored fork bring-up + stability gate, Stage 3 = desktop media + `:ui:callui`. | high |
| 26 | [PHASE-26-desktop-identity-p2.md](PHASE-26-desktop-identity-p2.md) — desktop identity + pairing | **DECIDED 2026-09-13: P2 (persist a software keypair under `~/.flash/`), at rest = Windows DPAPI via JNA (ADR-035).** Closes the desktop pairing gap scoped in [research/desktop-pairing-gap-scoping.md](research/desktop-pairing-gap-scoping.md): `PersistedFlashCrypto` + an `IdentityKeyVault` seam (DPAPI actual, test actual, Keychain/keyring future), pairing sessions wired into `DesktopEngine` over the already-commonMain pairing math. Unblocks Phase 16's **G2/G6** and the phone→desktop transfer direction. Security tier stated honestly: better than restart-amnesia, weaker than Android's hardware key. `SoftwareFlashCrypto`/`KeystoreFlashCrypto` untouched (R8). Execution ready: 26-1 vault → 26-2 persisted crypto → 26-3 wiring → 26-4 gate. | med-high |

### Phases 27–33 — the desktop product track (AUTHORED 2026-09-13, none executed)

**Read the framing before the rows: the screens are already shared.** All four tab screens
(`FlashChatListScreen`, `FlashTransfersScreen`, `FlashNearbyScreen`, `FlashSettingsScreen`) and the
pairing dialog are `:ui:chat/commonMain` and are already called by **both** hosts — `:app`'s
`FlashShell` (`MainActivity.kt:584`) and `:desktop`'s `DesktopShell`. These seven phases are
therefore **not a port**. They close the stubs that make the shared screens behave differently on
desktop, and unify the two *frames*. Authoring these did not change any code; each row's claims were
verified against the tree at authoring time and cite file:line.

| Phase | File | Status (2026-09-13) | Risk |
|---|---|---|---|
| 27 | [PHASE-27-desktop-shell-unification.md](PHASE-27-desktop-shell-unification.md) — one shell, two hosts | **AUTHORED, NOT EXECUTION-READY — needs a human pick (A/B/C: where the shared shell + engine facade live).** The prerequisite for 28–33. Extracts the ~1,052-line `FlashShell` out of the 2,016-line `MainActivity.kt` into one shared composable behind a `commonMain` facade that both `AppEngine` and `DesktopEngine` satisfy — `DesktopEngine` is explicitly **not** a `FlashEngine` (Phase 21), which is why this cannot be a pure move. Also consumes the already-tested-but-unconsumed `FlashAdaptiveMath`. Acceptance is "Android looks **identical**", which only a human can check. | med-high |
| 28 | [PHASE-28-desktop-chat-list.md](PHASE-28-desktop-chat-list.md) — chat list | **AUTHORED.** Desktop passes **11 of ~35** parameters to the shared screen; the rest silently default. `FlashChatListScreen.kt:74–84` proves the screen already supports them — search is dead only because `DesktopShell.kt:213` stubs `onSearchClick`. Plus the real desktop work: pointer idioms (right-click at cursor, hover, ctrl/shift-click) behind a **new seam** in `:ui:platform-shims`, which owns seven seams and none for pointers. Open product question: port the phone's selection mode, or go desktop-native. | low-med |
| 29 | [PHASE-29-desktop-conversation.md](PHASE-29-desktop-conversation.md) — conversation | **AUTHORED — BLOCKED ON D5=C's three sub-answers.** Corrects the record: the blocker is **narrower than "09B-2"**. 09B-1 already shipped — `core/persistence` has a `jvm()` target and `FlashDatabaseJvmTest` **proves the whole generated Room tier runs on the JVM** (11 tables, InvalidationTracker, conflict strategies). What is missing is exactly one thing: an **encrypted, file-backed** JVM driver (`BundledSQLiteDriver` is in-memory-only by the 09B charter's explicit prohibition). Two further blockers found: `RealFlashChatRepository` carries 5 `java.*` imports (D1 cleanup) and its ~2,600-line suite lives in `androidHostTest`. | high |
| 30 | [PHASE-30-desktop-transfers.md](PHASE-30-desktop-transfers.md) — transfers | **AUTHORED.** Headline finding: **the desktop can receive but cannot send.** Transfers is already at call-site parity (identical seven callbacks on both hosts), and the JVM file picker seam already exists and is tested — but `desktop/src/**` has **zero** references to it or to any send path. Also: no drag-and-drop anywhere. Small phase, but it is the one that makes G3-reverse app-runnable instead of harness-only. | med |
| 31 | [PHASE-31-desktop-nearby-pairing.md](PHASE-31-desktop-nearby-pairing.md) — Nearby & pairing | **AUTHORED.** Honest scope: Nearby is **already at parity** — Phase 26-3 wired the desktop to the same commonMain protocol, and both hosts reach `FlashPairingDialog` through the same `FlashNearbyScreen` call site. No transformation work exists here. What this phase adds is the **diagnostic** that makes the gate debuggable — every `connectManual` call site in the repo passes an *already-discovered* endpoint's host/port; no host has manual-IP entry — plus the gate run itself. | low |
| 32 | [PHASE-32-desktop-settings.md](PHASE-32-desktop-settings.md) — settings | **AUTHORED — BLOCKED ON 09B-3's ABI option.** Desktop passes six callbacks and **five are no-ops** (`{ }`); the other eight parameters fall through to `= {}` defaults. The Settings screen renders, every control flips, nothing happens and nothing persists — worse than an absent screen. Android persists all of them through `FlashSettingsDataStore`. The recorded blocker is real but split: `androidx.datastore` has a JVM artifact (construction differs), and `java.io.File` is legal in `jvmMain`. Acceptance: flip everything, restart, find it all still there — and find the unhonourable controls **hidden**, not inert. | med |
| 33 | [PHASE-33-desktop-calls.md](PHASE-33-desktop-calls.md) — calls | **AUTHORED. Depends on 27 + 25 (executed).** Largest of the six; **recommended to split into 33a/33b/33c**. The substrate is proven (Phase 25 S3a runs the media stack on a real JVM; S3b ships a real desktop renderer; `CallCoordinator` is already `commonMain` and the `FlashCalling` impl). Missing: dependency edges, a lifecycle owner, and the screen routed as a top-down overlay. `FlashWebRtcEngine` needs almost no desktop twin — its two jobs (low-latency `JavaAudioDeviceModule`, Android OEM capture-source probe) are both Android-only. Open product decision: incoming call while the window is minimised. | high |

**Gate runbook:** [PAIRING-GATE-RUNBOOK.md](PAIRING-GATE-RUNBOOK.md) — the manual ladder (L0–L9) for
Phase 16's **G2/G6** and Phase 23's desktop cells, with a symptom→layer diagnosis table. Written
2026-09-13; **not yet run**.

### Two log entries near the top of `logs/migration.md` are false — do not trust them

`logs/migration.md` opens with `## PHASE-21` and `## PHASE-22` entries dated 2026-08-31, both citing
commit `ecb0c63` and both reporting PASS builds. `ecb0c63` is a **docs-only** commit (33 files, all
under `docs/migration/` and `logs/`), `desktop/` has never existed on any branch
(`git log --all -- desktop` is empty), and `settings.gradle.kts` has no `:desktop` include. One of the
cited PASS tasks, `:ui:chat:compileKotlinDesktop`, cannot exist at all under CONVENTIONS R5. A
**CORRECTION block was appended to each entry** on 2026-08-31 (`0250a51`); the false text is preserved
above it because the log is append-only (R9). If you read that log top-down, read the corrections too.

## Ordering correction (2026-08-30) — read before touching phases 07–12

The core-conversion phases were **reordered** on 2026-08-30 after the module
`build.gradle.kts` dependency edges were read directly (not inferred). The earlier
draft had `07 = messaging+transfer`, `09 = network` before `10 = discovery`, and
`11 = persistence` — all of which violate the build graph:

- `core:messaging` → `implementation(core:security, core:network, core:persistence)`
- `core:transfer`  → `implementation(core:security, core:network, core:discovery)`
- `core:network`   → `implementation(core:security, core:discovery)`
- `core:persistence`, `core:security`, `core:discovery` → only `api(core:common)`
- `core:engine`    → `api(` all seven `)`

A module cannot have its shared code (`commonMain`/`jvmAndAndroidMain`/`jvmMain`)
compile for the `jvm()` target until every module it depends on is already KMP — so
conversion **must** run bottom-up in dependency order. The corrected order is a
topological sort: leaves that need only `core:common` first (security, discovery,
persistence), then `network`, then the two repositories (transfer + messaging), then
`engine` last. `core:transfer` — which the old plan never gave a phase file — is
converted in Phase 11 alongside `core:messaging` (both sit at the same level and do
**not** depend on each other). Do not "restore" the old numeric order; it is wrong.

## Two hard gates

- **Phase 16** — headless desktop↔Android transfer must work before *any* UI work is
  merged. If the protocol cannot cross platforms, shared UI is worthless.
- **Phase 23** — the full 4-way interop matrix. Nothing is published before this passes.

Phases 17–20 (UI) are deliberately parallel-capable with 07–16 (core + desktop),
because they
touch disjoint modules. Phase 17 only needs Phase 06. If you have one agent, do them
in numeric order anyway.

## Modules with no phase file (corrected 2026-09-05) — read before planning any further UI phase

The two modules the plan never covers are **`:core:calling` and `:ui:callui`** — the calling stack.
An earlier version of this section named `:ui:callui` and `:sample:consumer-granular`; the second half
of that was **wrong** and is corrected here.

**Their scope is now decided: `D11 = Option B` (2026-09-05) — they ARE in desktop scope, and the phase
that handles them must open with a WebRTC-for-desktop-JVM research step and report its findings before
proposing any conversion.** The research is done (2026-09-12) and the phase file now exists
([PHASE-25-calling-stack-desktop.md](PHASE-25-calling-stack-desktop.md), written 2026-09-13 on top of the
report) — but its execution is blocked on the human's A–D pick, and it was correctly not inserted ahead of
15/16: D10 = A unblocked the critical path and **the whole of 13B is now done** (13B-2 `732e7b5`,
13B-3a `5e4e9a5`, 13B-3b `a3375e3`, 13B-3c `d51206b`, 13B-3d `293f12b`, 13B-3e `fa95d74`) — 15 is
done and 16's harness is built, and the Phase 16 interop gate still outranks calling.

- **`:core:calling`** — it is still `com.android.library` (correctly, until the A–D pick), and it
  is the WebRTC module, so it is the substantive half of the problem —
  and the reason D11 mandates research before conversion.
- **`:ui:callui`** — depends on `:ui:theme` (`ui/callui/build.gradle.kts:62`) and names
  `FlashIconSpec` (`FlashCallScreen.kt:486`), so it sits inside the blast radius of Phase
  17 (done), 18 (done) and 19 (done), yet no phase converts it or even compiles it as a gate. The
  verification runs for 09B-1, 17, 18 and 19 added `:ui:callui:compileDebugKotlin` by hand for
  exactly that reason — and as of Phase 18 it is compiling against a `:ui:theme` that is now
  multiplatform, so the gap was widening rather than holding still. **The gate wiring D11's answer
  directs is now done (2026-09-13): both compile tasks are on the R3 canonical command line
  (CONVENTIONS.md) and green**, so the gap is measured rather than assumed — whatever the A–D
  pick concludes.

**`:sample:consumer-granular` is not an open question.** `D9 = Option A` (answered 2026-08-31) names
it explicitly: keep `sample/consumer` **and** `sample/consumer-granular` Android-only as-is through
Phase 23, then add a pure-JVM `sample/consumer-desktop` in Phase 24 to validate the desktop artifact.
`PHASE-24-publishing.md:83` carries the same instruction — **and `sample/consumer-desktop` now exists
and is green (2026-09-13, Phase 24 Step 2)**. Any backlog that still lists this module as
"no plan — needs a human scope decision" (including the one in Phase 20's log entry) is repeating this
README's error, not reporting a real gap.

`:app` also has no conversion phase, and that is **by design** — it is the Android application, and
Phase 21 gives the desktop its own `:desktop` module rather than making `:app` multiplatform.

Do not treat the calling stack's absence as "already handled". Phase 17's log entry records it as an
open item; it is repeated here so the next planning pass sees it without reading 7,900 lines of log.

## Module conversion state (after Phase 20, 2026-09-05)

**KMP (12):** `:core:common` (06), `:core:security` (07), `:core:discovery` (08 + 14),
`:core:network` (10), `:core:transfer` + `:core:messaging` (11), `:core:engine` (12),
`:core:persistence` (09B-1, db tier only), `:ui:theme` (17 shell + 18 proper),
`:ui:platform-shims` — created by Phase 19 and the first module in this repo that was **born
KMP**, never having had the `com.android.library` plugin — and **`:ui:chat`** (20).

**Still `com.android.library` / `com.android.application` (5):** `:core:calling`, `:ui:callui`,
`:app`, `:sample:consumer`, `:sample:consumer-granular`. Of these, three are **deliberate**: `:app`
stays the Android application (Phase 21 gives desktop its own module) and both samples stay Android-only
through Phase 23 per D9=A. The two that are **unplanned** are `:core:calling` and `:ui:callui`.

`:ui:chat` is the only module that is **100% common**: all 45 production files in `commonMain`, all 31
test files in `commonTest`, and no `androidMain`, `jvmMain` or platform-specific source of any kind.
That is Phase 19's doing — the seven shims it extracted were the only reason the module ever touched
`android.*`.

**Every unblocked phase in this plan is complete, and as of 2026-09-05 the plan is no longer
decision-blocked at all.** D10 = Option A and D11 = Option B were answered, and the R8 exception for
13B-3's `ChunkFrame` rewrite was granted with byte-identical output as the hard acceptance criterion —
**that exception has now been spent and discharged by `a3375e3`, and `ChunkFrame` is untouchable again.**
**The critical path is open again: ~~13B-2~~ (done, `732e7b5`) → ~~13B-3a~~ (done, `5e4e9a5`) →
~~13B-3b~~ (done, `a3375e3`) → ~~13B-3c~~ (done, `d51206b`) → ~~13B-3d~~ (done, `293f12b`) →
~~13B-3e~~ (done, `fa95d74`) → ~~15~~ (done, 2026-09-12 — 15-1 `ff1d36a`, 15-2 `4f22c1e`, 15-3..15-6
in the phase's closing commit) → **16 (gate)** → ~~21~~ (built 2026-09-12, compile-verified; **merge
stays blocked on the 16 hardware run**) → ~~22~~ (built 2026-09-12, compile-verified; same merge
blocker) → 23 (gate) → 24.**
**Phase 16 — the headless desktop↔Android interop gate — remains the release-line blocker.** It
needs hardware (one Android device + one desktop on one LAN), which no Gradle task can provide:
read `PHASE-16-desktop-headless-interop.md` before starting. What a build *can* prove has been
proven: two `JvmWsFlashNetwork` instances complete the full HELLO→session→frame path over real
sockets on the desktop tier (`JvmWsFlashNetworkLoopbackTest`), every jvmMain duplicate compiles
against the same common contracts, and the Phase 21 desktop shell compiles over the whole
assembled stack. What remains unproven is cross-platform wire interop, desktop TLS with a real
keystore, and the desktop's no-`ConnectivityManager` recovery behaviour under an actual network
interruption.

What is still outstanding is narrower than a decision:

- **09B-2** — D5=C's three implementation sub-answers: which encrypted desktop driver, is a commercial
  licence acceptable, SQLCipher file-format parity.
- **09B-3** — the settings-tier ABI option (a) or (b).
- **D6's throwaway spike** — mandated by D6=A and never run; Phase 14 shipped without it and no real
  multicast has ever been exercised. Needs a human with two machines on one LAN.
- **Five desktop library decisions** with no owner: AAC decode (the one gap a user would notice),
  video-frame extraction, EXIF rotation, reduce-motion detection, sound output.
- **A Kotlin/Native target**, recommended by CONVENTIONS R6.1 since Phase 07 and still unwritten. It
  would turn R6 from a four-times-defective grep into a compiler error, and it is a **precondition** of
  it that the eight allowlisted `.format(` calls be fixed with rounding tests first. D10 = A is what
  makes this reachable for `:core:transfer` at all.
- **Phase 24's release notes** owe six ABI breaks and three new artifacts.

Phase 20's log entry also lists `:sample:consumer-granular` as an open scope question — **that item is
void; D9=A answered it**, and `:core:calling`/`:ui:callui` are now settled by D11.

## Logging

Every phase appends one entry to `logs/migration.md` using
[TEMPLATE-phase-log.md](TEMPLATE-phase-log.md). No exceptions. A phase with no log
entry is treated as not done. **The converse is not true** — an entry is not proof of work: the
`PHASE-21` and `PHASE-22` entries at the top of that file are fabricated and carry appended
corrections. Verify a cited commit with `git log -1 <sha> --stat` before trusting an entry.
