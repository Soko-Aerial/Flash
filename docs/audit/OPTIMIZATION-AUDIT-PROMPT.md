# Flash — Deep Optimization Audit Prompt (for an auditing AI agent)

> **How to use:** give the agent repository access and paste: *"Read `docs/audit/OPTIMIZATION-AUDIT-PROMPT.md` completely and execute it exactly."*
> If the agent has no repository access, paste this whole file. Written 2026-10-23 for the owner of this project. The agent is authorised to
> audit **this repository and a local copy of its server**, nothing else (section 2). This is an **audit**: it finds, measures and ranks
> optimization opportunities and writes them up so another engineer can implement them. It does not change production code.

---

## 0. Your role

You are a senior performance engineer (Android/Compose, Kotlin coroutines, SQLite, networking, media/real-time audio-video, JVM server) performing a
**white-box optimization audit** of the Flash project: an Android + Windows/Linux (Kotlin Multiplatform) peer-to-peer messenger / file-transfer /
calling app, plus an optional **enterprise hub** (a Ktor + SQLite server with a vanilla-JS admin console).

The owner wants to know, with evidence, **where the project wastes CPU, memory, battery, bytes, storage, startup time, frame time, server capacity
and build time; how much; and what to change first.** Not generic advice: concrete defects and hot spots in **this** code, each with `file:line`, a
cost model, a measurement (or an honest statement that it is an estimate), the expected gain, the risk of changing it, and how to prove the gain.

You are an **orchestrator**. You do not do the whole audit in one pass. You split it into the domains in section 6 and run them **one at a time, in
order, each in its own sub-agent**, verify what comes back, and only then start the next (section 4).

**You are auditing, not fixing.** Do not change production code. Findings, scratch benchmarks and measurement notes go to the places named in section 8.

## 1. Mindset (this project's own rules, `AGENTS.md` section 23)

- **Measure first. Never optimize by intuition.** Every finding says how it was established: `MEASURED` (you ran something and have numbers),
  `STATIC-ARITHMETIC` (a count you can derive from the code, e.g. "O(n) per event with n = chunk count; 100 events/s; n = 12,800 for a 100 MB file"),
  or `PLAUSIBLE` (reasoned, needs a device or a profiler). Never present an estimate as a measurement. A number marked *(measure)* in the docs is not a number.
- **Name the bottleneck class** for every transfer/network/media finding, exactly as `AGENTS.md` does: network-limited, storage-limited, CPU-limited,
  TLS-limited, hashing-limited, protocol-limited. Do not assume the network is the bottleneck.
- **Cost = frequency x unit cost x number of devices/users.** A 5 ms cost once at start-up is not a finding; a 0.2 ms cost on every audio packet,
  every recomposition, every chunk, every message or every server request is. Always find the multiplier first.
- **Hot-path thinking.** For each domain, start by listing what runs *per frame / per audio packet / per chunk / per message / per tick / per
  recomposition / per connection / per request / per query* and audit those first. Cold paths come last.
- **Battery and heat are first-class** (Transsion phones freeze the app at screen-off; low-end handsets have 2 GB of RAM; the project already has a
  thermal governor and a memory governor). Wake-ups, timers, polling loops and unnecessary radio use are findings.
- **Do not trade away correctness.** Section 5.3 lists the invariants an optimization may never break (security properties, wire compatibility,
  durability, ordering). A finding that is fast but breaks one is a **rejected idea**, written up as such, not a recommendation.
- **Confirmed small gains are worth less than one confirmed large gain, but a long tail of confirmed small ones on a hot path is real.** Rank by
  user-visible impact, not by cleverness.
- **"Already fine" is a result.** For every checklist item say what you found, including "no problem, because ...". An unanswered item fails the audit.
- Do not report generic advice ("use a profiler", "add caching") as a finding. Report concrete defects.
- **Do not re-find what is fixed.** Section 5.2 lists optimizations already done. Re-test that they still hold (a regression is a finding); do not list
  them as new.

## 2. Rules of engagement (hard limits — breaking any of these fails the audit)

1. **Scope:** this repository (all modules, docs, scripts, build files, git history) and a **local instance** of `:server` you start yourself on
   loopback with a throw-away data directory. Anything else is out of scope.
2. **Never attack remote systems or load them.** The owner's test hub on `myserver`, its Cloudflare tunnel URL, JitPack, GitHub, Maven repositories
   and any other host are **off limits** for load, benchmarking or scanning. Load and stress tests run on **loopback only**.
3. **No destructive actions:** do not delete or rewrite repository files, history or logs; do not push, tag, publish or deploy; do not change production
   code; do not run `git reset/clean/checkout -- .`; do not delete data you did not create.
4. **The connected Android phone is untouchable** unless the owner names you for a device session. Never uninstall or clear the app. Without a device
   session, anything that needs a phone (frame timing, battery, thermals, startup on real hardware) is `PLAUSIBLE` or `STATIC-ARITHMETIC` and goes into
   the device-test backlog as an owed measurement (section 8.4).
5. **Gradle only through the lock script:** `tools\flash-gradle.cmd <args>` (never `gradlew` directly). Exit code 75 means another agent is building:
   wait and retry. The PC has 8 CPUs and about 20 GB RAM and cannot run two builds at once, **and a benchmark run while another agent builds is
   invalid**: do not trust timings taken during a build; repeat them when the machine is quiet and say so.
6. **Do not upload anything.** No Gradle `--scan` (it uploads to a build-scan service); use `--profile` (local HTML) instead. No online profilers or
   paste sites. Looking up public documentation is fine (`python tools/tavily_search.py` is available; a search result is context, not evidence about this app).
7. **Secrets:** never copy a secret value (key, token, password, keystore) into a report.
8. Follow `AGENTS.md` (read it first): log in `logs/progress.md` and `logs/handoff.md`, never delete documentation. **Do not commit unless the owner asks.**
9. If a command is refused by a permission system, do not work around it; record that you could not do it and move on.
10. Scratch benchmarks live only under `docs/audit/optimization-2026-10-23/bench/` as plain files **not wired into any Gradle build** (or, when a test is
    the only way to run code, a temporary test file that you delete afterwards and copy into `bench/` first). Never leave a benchmark in `src/`.

## 3. What "optimization" means here — the cost dimensions (use these names in every finding)

| ID | Dimension | Typical symptoms in this project |
|---|---|---|
| **T** | Time / latency | cold start, time to first chat, message send-to-display, transfer start, pairing, call setup, console page load |
| **J** | Jank / frame time | dropped frames in lists, call screen, animations, sheet open, desktop resize |
| **C** | CPU | sustained %CPU in calls, transfers, idle; hashing, TLS, JSON, image decode, video encode |
| **M** | Memory | heap growth, retained caches, bitmap and native memory (WebRTC, Skia), OOM on 2 GB phones, server RSS per connection |
| **B** | Battery / wake-ups | timers, polling loops, wake locks, radio keep-alive, Doze/Hiber interaction, screen-off behaviour |
| **N** | Network bytes / airtime | chatty protocols, redundant frames, uncompressed payloads, resend storms, keep-alive cost, 2.4 GHz airtime |
| **S** | Storage / I/O | DB write amplification, fsync cost, temp files, cache size, log volume, file copies, SAF overhead |
| **K** | Capacity / scalability | hub server connections, mailbox size, directory at 5,000 members, group of 20, swarm piece maps, query plans |
| **Z** | Size | APK/AAB, MSI/exe, JVM runtime image, dependency weight, resources, native libraries |
| **D** | Developer-time | build time, test-suite time, CI flakes caused by timing, Gradle configuration time |

## 4. Method — how to run the audit

### 4.1 Orientation (you, the orchestrator, before any sub-agent)

1. Read: `AGENTS.md`, `logs/handoff.md` (top), `logs/experiments.md` (all `EXP-` entries: previous measurements and static findings), `docs/architecture.md`,
   `docs/PHASE-HARDENING-AIRTIME-THERMALS.md`, `docs/FUTURE-OPTIMIZATION.md` (parked work: **do not start it**; you may add evidence to it), `docs/protocol.md`,
   `docs/decisions.md` (search `ADR-0`, `ADR-1`, `ADR-2` for performance, thermal, memory, cache, chunk, stream, codec, bitrate entries), `docs/testing/PROBES.md`,
   `docs/audit/2026-10-22-comprehensive-brutal-code-review.md` (its performance findings F69–F71 and the Android-lifecycle ones) and `docs/calling/*`, `docs/transfer/*`.
   Note: `AGENTS.md` lists `docs/performance.md` but the file **does not exist** yet — you will draft its content (section 8.2).
2. Record the commit you audit: `git rev-parse HEAD` and `git status --short`. Every finding cites this commit.
3. **Hot-path map.** Build `00-hot-path-map.md`: for every runtime (Android app, desktop app, hub server, admin console) list the code that runs per frame,
   per audio packet, per chunk, per message, per tick/timer, per recomposition, per connection, per request, per query, and at start-up. Include the
   dispatcher/thread each runs on and its frequency (cite the constant or the arithmetic). Sub-agents get this map as input; it is the single most useful artifact.
4. **Inventory of periodic work.** Grep every `delay(`, `while (true)`, `Timer`, `ScheduledExecutor`, `AlarmManager`, `WorkManager`, `Handler.postDelayed`,
   `tickerFlow`, `repeat`, `withTimeout` loop, `setInterval` (console) and write `00-periodic-work.md`: owner, period, what it does when idle, whether it
   stops when nothing needs it. (`EXP-015` and `EXP-016` were exactly this kind of finding.)
5. **Baseline.** Run `tools\flash-gradle.cmd :server:test` and the module tests of the domain once, through the lock, when the machine is quiet, and record
   wall-clock per module in `00-baseline.md` (this is also the data for domain O21/O27). Known flakes (pass alone): `RelayFrameRouterTest...ExactlyOnce`,
   `RelayLoadTest`, `E2eRelayContractTest...ReconnectStorm`, `OrgDirectorySyncTest` ORGT-U16, `CachedMailboxStatsTest...FailingRefresh...`,
   `RealFlashChatRepositoryTest > delete for everyone...`, `WsFlashNetworkTest...RedialLoop...`.
6. Reference hardware (for estimates; never hard-code): Android phones in use are an Infinix X6882B (Android 14, Transsion "Hiber" freezer, low RAM) and a
   Samsung SM-G986U1; the owner's PC is Windows 11, 8 CPUs, about 20 GB RAM; the test hub is a small VPS behind a tunnel. A finding that only matters on
   a 2 GB phone says so.

### 4.2 One domain, one sub-agent, strictly in order

For each domain O01 … O28 in section 6, **in that order**:

1. Spawn **one** sub-agent. If your environment has no sub-agents, emulate it: do the domain as an isolated pass, write its file, summarise it in five
   lines, and drop the detail from working memory before the next domain.
2. Give it a **self-contained brief** (it has no memory of this conversation): repository path, commit, the dimensions table (section 3), the rules of
   engagement (section 2), the invariants (5.3), what is already optimized (5.2), the hot-path map and periodic-work inventory, the domain's checklist
   (section 6), the evidence standard (section 7), the output file path and the finding template (8.1). Tell it to **read the actual code, not only the
   docs**, to list the files it read, and to answer every checklist item.
3. **Wait for it to finish.** Do not run domains in parallel (the PC cannot build or benchmark two things at once).
4. **Verify its report yourself** (4.3). Reject, downgrade or send it back for more evidence. Append accepted findings to the ledger.
5. Only then start the next domain. Tell the owner, in one line, which domain just finished and how many findings it produced.

### 4.3 Verification pass (mandatory for every High finding and every claim of >20% gain)

For each such finding spawn a **separate verifier sub-agent** whose only job is to **disprove it**: find the caller that never reaches the code at that
frequency, the cache that already absorbs the cost, the dispatcher that makes it irrelevant, the arithmetic error, the measurement that was taken under
load or on a warm JIT. A finding survives only if the verifier fails to refute it. Record the verifier's conclusion in the finding. Refuted findings go
to `rejected.md` with the reason — never silently dropped, so nobody re-investigates them.

### 4.4 Final passes (after O28)

- **Cross-domain compounding:** one sub-agent reads the whole ledger and writes `compounding.md`: places where several findings multiply (for example
  "100 Hz progress tick x full-thread re-map x recomposition x log line") and which single fix removes the most.
- **Regression of earlier optimizations:** re-test the claims in `EXP-008`..`EXP-016` and `PHASE-HARDENING-AIRTIME-THERMALS.md` (chunk pool, memory governor,
  thermal governor, SQLite cache bounds, progress throttling, outbox wake-up, `onTrimMemory`, shell-scope recomposition, draw-phase reads). A fix that no
  longer holds is a finding.
- **Documentation vs reality:** every performance claim in the docs (`(measure)` placeholders, throughput numbers, "low overhead") that the code or a
  measurement contradicts or that has no number behind it.
- Write `docs/audit/optimization-2026-10-23/REPORT.md` (8.3).

## 5. Architecture primer, what is already done, and what you may never break

### 5.1 Primer (verify it; it may be out of date)

- **Mesh:** each device runs a TLS WebSocket server (port 45822); peers pin identities. Chat and **chunked file transfers both ride the same WebSocket
  mesh** (ADR-016). Transfer: framing v2, per-chunk SHA-256 verified before write, whole-file hash at the end, resume from a persisted done-set,
  multi-stream dispatch, pause/resume. Group file distribution: **swarm** (`:core:swarm`, sans-IO). Session ceiling 24 (ADR-057).
- **Discovery:** NSD/mDNS (Android), JmDNS (desktop), remembered routes, subnet sweep, beacon; one connection planner with ECO / STANDARD / BOOST modes.
- **Calls:** WebRTC through a vendored fork (`third_party/webrtc-kmp`), 1:1 and group voice/video (group realistic limit 3–4 devices, measured by the owner),
  screen share (desktop presenter), governors for data saver / thermal / voice priority, push-to-talk (`:core:ptt`, Android + JVM).
- **Persistence:** Room (Android; SQLCipher), a desktop Room/SQLite with DPAPI-wrapped key, schema step 19+, DAOs per aggregate, FTS5 deliberately deferred (ADR-218).
- **UI:** Compose (Android) and Compose Desktop (Skia) sharing `:ui:chat`, `:ui:callui`, `:ui:theme`, `:ui:platform-shims`; the `Flash*` design system.
- **Hub:** `:server` (Ktor, SQLite, relay router with mailbox, directory sync, announcements, sealed hub files, admin console in vanilla JS under
  `server/src/main/resources`), `:core:hub-protocol` (wire), `:core:enterprise` (client).
- **Hosts:** `:app` (Android), `:desktop` (Windows/Linux, jpackage MSI/exe).

### 5.2 Already optimized — re-test, do not re-report

Read the full text in `logs/experiments.md` and the ADRs before listing anything near these:
send-side resume bookkeeping made linear (EXP-008); receiver done-set retention (EXP-009, one half owner-gated); attachment progress throttling to the
conversation mapper (EXP-010); shell-level progress collection (EXP-011); shell-scope `remember` derivations (EXP-012); per-frame animations moved to
the draw phase (EXP-013); `onTrimMemory` and the thumbnail cache share (EXP-014); the outbox retry loop that woke every second (EXP-015); the 1 Hz
pairing ticker (EXP-016); desktop video native-memory leaks (ERROR-078, EXP-017); `ChunkBufferPool`, `MemoryGovernor`, SQLite page-cache bounds and the
Android thermal governor (`PHASE-HARDENING-AIRTIME-THERMALS.md`); hub directory sync at 5,000 members (EXP-022, EXP-030, EXP-C1); sealed hub file
transfer of 50 MiB on the test host (EXP-075); comprehensive-review fixes F7/F8/F27/F28/F31/F69/F70/F71 (PTT per-packet coroutine, call-screen
recomposition, swipe-to-reply flood, speaker ring) — verify they hold.

### 5.3 Invariants an optimization may never break (a "fast" change that violates one is a rejected idea)

1. **Security:** per-chunk and whole-file integrity verification, TLS identity pinning, pairing binding, group gates and `PeerTrustPolicy`, sealed hub
   messages/files, signature verification of certificates/statements/rosters, rate limits and quotas on the server. Caching a verification result is
   only acceptable if the cache key includes everything the verdict depends on (key, epoch, time window, revocation state) and it is invalidated on every
   trust change.
2. **Wire compatibility:** `docs/protocol.md` wire versions; older peers must keep working unless a version bump with a negotiation is part of the proposal
   (and then the proposal says so and the finding is rated by the cost of that bump).
3. **Durability and ordering:** the outbox commit rule (ERROR-031), exactly-once ingestion on `localId`, read cursors, resume correctness, atomic
   transactions that already exist for a reason. Batching is fine; losing or reordering is not.
4. **Large files never live in RAM** (`AGENTS.md` 18). Streaming I/O only.
5. **Background-execution rules** (`AGENTS.md` 21): do not "save battery" by dropping a foreground service the platform requires.
6. **User-visible behaviour:** no feature removed or silently degraded without it being the stated trade-off of the finding and flagged as an owner decision.
7. **Accessibility and reduce-motion** behaviour of the UI.

## 6. Domains (run O01 → O28, one sub-agent each)

For every domain: list the files read; first list the **hot paths** of the domain with frequency; answer every checklist item with **Problem (finding id)**,
**No problem (why, with the number or the code fact)** or **Not applicable**. For every item that is a hot path, say whether it is `MEASURED`,
`STATIC-ARITHMETIC` or `PLAUSIBLE`.

### O01 — Start-up and cold start (Android and desktop)
Everything between process start and the first usable chat list: `Application.onCreate`, engine bring-up (`DiscoveryEngineHolder`, `AppEngine`,
`DesktopEngine`), Room/SQLCipher open and key unwrap (Keystore/DPAPI cost), migrations at start, dependency construction order and eager vs lazy,
class-loading and reflection, JSON/serialization initialization, font/resource loading, the launch splash (UI-056: is it gated on work or only decorative —
it plays about 3.45 s: does it *delay* usable UI?), first composition cost, work done on the main thread, work that could be deferred until after first frame,
startup restore of transfers/outbox/swarm/hub files (`resume from disk at start`), initial network sockets and discovery start, baseline profile / startup
profile / R8 effect (is there a baseline profile? should there be?), desktop JVM start flags and CDS/AppCDS, jpackage runtime image, time to window.

### O02 — Heap and native memory
Retained caches and their bounds (thumbnail/bitmap cache, message mapper caches, decoded video frames, waveforms, search results, directory snapshot — 5,000
members in memory?), per-conversation state kept after leaving the screen, `StateFlow`/`SharedFlow` replay buffers, unbounded collections (`done-set`,
tombstones, probes ring, seen-id sets, session registries), listeners never removed, `MemoryGovernor` and `onTrimMemory` behaviour (does everything that
can shrink actually shrink?), native memory (WebRTC frames/encoders, Skia images, direct ByteBuffers, `ChunkBufferPool` sizing), 2 GB-phone behaviour,
JVM heap defaults for the desktop and the server (`-Xmx`, GC choice), per-connection memory on the server, leak candidates (inner classes capturing
`Activity`/`Context`, coroutine scopes that outlive their owner, `remember`ed lambdas capturing large objects).

### O03 — Compose: recomposition, draw phase and stability
For `:ui:chat`, `:ui:callui`, `:ui:theme` and the two hosts: state reads in the composition phase that belong in layout or draw (`graphicsLayer {}`, `drawBehind`,
`Modifier.offset { }`, `derivedStateOf`), unstable parameters (lambdas and `List`/data classes without stability configuration; is the Compose compiler
stability config file present? strong skipping?), lambdas allocated per item, `remember` keys that defeat caching, `LaunchedEffect(Unit)` capturing stale or
re-launching, `collectAsState` at too high a scope (shell-scope reads, as EXP-011/012), `animate*AsState` running when nothing is visible, infinite transitions
that never stop (typing dots, shimmer, pulse, breathing red dot) and whether they pause when off-screen or when reduce-motion is on, `Modifier` chains rebuilt each
recomposition, text layout cost (`buildHighlightedMessageText`), `Canvas` allocations per frame (Paths, Brushes, Paints), layout passes (nested `weight`,
`IntrinsicSize`, `SubcomposeLayout` use), `BoxWithConstraints` use. Use the Compose compiler reports (`-P plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=...`)
locally if you can enable them without editing committed files (pass them on the command line or in a scratch copy).

### O04 — Chat list and conversation rendering
The message list: `LazyColumn` keys and `contentType`, item height variance, `reverseLayout`, pagination (`historyBefore/After`), how rows are mapped
(`FlashChatRepository.conversationState`: a `combine` of how many flows? what re-runs when one input changes? is the whole thread re-mapped on a single
receipt/typing/progress event?), diffing, throttling windows (`UiPacing.throttleLatest`), day separators, grouping, link detection, markdown/highlight,
unread-count queries per conversation, chat-list previews (`observeLatestPreviews`), search (LIKE scans of the message table: `searchMessages` — EXPLAIN QUERY PLAN,
index use, `attachmentName` scan), scroll-to-message cost, jump/pulse animations, typing indicator, drafts persistence frequency (write per keystroke?),
reactions and receipts fan-out in a group of 20.

### O05 — Media pipeline
Image decode (sampling to the displayed size, `inSampleSize`, off-main-thread, cache keys, memory per thumbnail, desktop `JvmImageDecoder` and its cache key),
EXIF handling, video thumbnails and playback (`VideoView` vs ExoPlayer vs vlc rejected; preparing cost; multiple players alive at once), voice notes (recording
format/bitrate, waveform extraction cost and storage, playback progress ticks), attachment staging and copying (do files get **copied** into app storage before
send? how many full-file reads per send: hash pass, send pass, thumbnail pass?), image compression/"send smaller", gallery picker thumbnails, camera capture
(rotation, process death), animated content, GIF/WebP handling.

### O06 — Database: Room, SQLite, SQLCipher
Every DAO query and every `Flow`-returning query: index coverage (read the `@Entity` indices and run `EXPLAIN QUERY PLAN` in a scratch JVM test against the
real schema for the top 20 queries: conversation message paging, unread counts, previews, outbox due, group deliveries, search, directory lookups, swarm
tables, hub_file), **Flow re-query amplification** (a `Flow<List<...>>` over a big table re-queries on every write to that table: which tables are written
at high frequency — messages status updates, transfer progress, receipts, presence — and which observed queries sit on them?), transaction boundaries and
write amplification (one row update per chunk? per progress tick?), `ON CONFLICT`/upsert patterns, WAL settings, `synchronous` level, page size and cache size,
`journal_size_limit`, SQLCipher KDF iterations and per-page cost, migration cost on big databases, VACUUM/`auto_vacuum`, blob columns inside hot rows, JSON
columns parsed on every read, the FTS5 deferral (ADR-218): at what message count does `LIKE '%x%'` stop being acceptable? Estimate from the table sizes and
measure with a synthetic 100,000-message database.

### O07 — File-transfer throughput and per-chunk cost
The send and receive pipelines in `:core:transfer` (`chunked`, `concurrent`, `multistream`, `store`, `protocol`): chunk size and why, buffer allocation per
chunk vs `ChunkBufferPool`, number of copies between disk and socket (read -> hash -> encrypt(TLS) -> frame -> write; any `ByteArray.copyOf`, `toByteArray`,
`String` conversions, `ByteString` copies), SHA-256 implementation and whether it is hardware-accelerated (JCA provider, `MessageDigest` reuse), hashing on the
same thread as I/O, ack/window design (stop-and-wait vs sliding window, in-flight bytes, effect of latency on a Wi-Fi hop), per-chunk persistence of the
done-set (batching?), progress callback frequency, resume bookkeeping complexity, whole-file verification second pass (re-read of the full file after
receive — cost on slow storage), temp file + rename strategy, storage-limited vs network-limited vs TLS-limited vs hashing-limited analysis with numbers from
`EXP-001`, `EXP-075` and a **local loopback benchmark you write** (send 200 MB through the real pipeline in a JVM test or a scratch harness; report MB/s,
CPU time, allocated bytes via `ThreadMXBean.getThreadAllocatedBytes`; vary chunk size and streams). `AGENTS.md` 23 lists the benchmark matrix: say which cells
exist, which you filled, which are owed.

### O08 — Group swarm (`:core:swarm`, `SwarmDriver`, `FlashSwarm`)
Scheduler complexity per tick (piece selection with 20 peers and a 4 GB file: bitfield size, rarest-first computation, requests in flight), piece-map memory
(UI-055 `pieceBlocks`), per-piece hash verification cost, who serves what (`ServePolicy` budget, BUSY behaviour), simulation scale (`SIM-01..20`; run the
largest and record time/memory), persistence write frequency (`SwarmDao`), wake-up/ticker loops, interaction with the thermal governor, behaviour at 3–4 and 20
members, tombstone growth. Compare with the star-topology investigation (`docs/SWARM-STAR-TOPOLOGY-INVESTIGATION.md`) so you do not repeat it.

### O09 — WebSocket mesh and wire efficiency
Session lifecycle cost (TLS handshake count per reconnect; session resumption; 24-session ceiling), keep-alive/ping intervals vs battery and airtime (ECO/
STANDARD/BOOST), send queue design (`BoundedSendQueue` is documented as not wired), `synchronized(writeLock)` blocking writes, frame header overhead, text
vs binary framing, **JSON encode/decode per message** (allocation per frame, reflection-based vs generated serializers, repeated `Json {}` construction, `String`
round-trips of binary data via Base64), compression (permessage-deflate? none?), per-message overhead for typing/receipt/presence frames (how many bytes and
how often; typing indicator rate limiting), reconnect storm behaviour and backoff (jitter), dial budgets in crowds, planner churn, duplicate sessions,
`WsFlashNetwork` event fan-out. Quantify bytes per chat message end to end (text of 20 chars: how many bytes on the wire including receipts?) with a
loopback harness.

### O10 — Discovery and presence
NSD/JmDNS/sweep/beacon: wake-ups per hour, multicast lock use, `resolveService` serial queue cost, sweep cost on a /24 (connections attempted, timeouts,
parallelism, battery), beacon period, `restartBrowsing` frequency, retry loops (`retryMonitorSoon`), presence flaps, hotspot paths, desktop adapter filtering,
cache of remembered routes. What runs while the screen is off and no one is looking at Nearby?

### O11 — Calls, WebRTC and media governors
Encoder/decoder choices and settings (VP8 software on desktop, hardware on Android?), resolution/bitrate/FPS ladders (ADR-098: 540p/360p), `CallQualityGovernor`,
`CallHealthMonitor`, voice-priority governor per leg, Opus settings (frame size, DTX, FEC, bitrate), stats polling frequency (`getStats()` cost), renderer
lifetime and surface count, group-call mesh topology cost (N-1 uploads per device: bandwidth and CPU at 3, 4, 8), simulcast/SVC use or absence, screen-share
capture cost (frame rate, resolution, scaling on the CPU?), audio route switching, echo cancellation source (VOICE_COMMUNICATION probe), what stays alive after
a call ends (threads, native objects, wake locks), CPU of an idle call screen (EXP-017/021), the 120% CPU claim in ERROR-097 (documented as not a bug: re-check
the measurement basis).

### O12 — Push-to-talk and real-time audio
Per-packet path (capture -> encode -> frame -> send; receive -> jitter buffer -> decode -> playout) allocations and threads, wake-ups of `tickLoop`,
`commandMutex` contention, jitter-buffer sizing and latency, PLC cost, AudioTrack/AudioRecord buffer sizes and latency (low-latency flags, performance mode),
desktop `javax.sound.sampled` cold-open latency (EXP-018), end-to-end talk-start latency (press -> first audio at listener), CPU and battery of a 60 s session,
what runs when idle.

### O13 — Background execution, battery and wake-ups
Foreground services (types, when started and stopped), WorkManager 15-minute wake-up, wake locks (partial, transfer-scoped), alarms, `START_STICKY`, network
callbacks registered but unused, `ConnectivityManager` churn, timers/tickers inventory (from 00-periodic-work.md: each one: period, idle cost, stops when
unneeded?), screen-off behaviour (ECO mode parking, keep-alive agreement), Doze and Transsion Hiber (measured EXP-002, HIB-10: do not move the engine), Android
15 `dataSync` timeout handling, battery cost of discovery and sessions per hour (estimate from wake-ups x CPU wake cost; list what must be measured on a
device: `dumpsys batterystats`, Battery Historian — as owed tests).

### O14 — Concurrency, dispatchers and locks
Dispatcher usage (`Dispatchers.Default` vs `IO` vs custom single-thread lanes), blocking calls on `Default`/main (`runBlocking`, `Thread.sleep`, blocking socket
or file reads, `synchronized` held across I/O), mutex granularity (`commandMutex`, `drainMutex`, `SessionRegistry`), coroutine-per-event patterns (the PTT
per-packet coroutine; look for others: per frame, per chunk, per receipt, per tick), unbounded `launch` fan-out, channel capacities and conflation, `Flow`
operators that buffer unbounded, `shareIn/stateIn` strategies (`WhileSubscribed` vs `Eagerly`), thread counts at idle (list threads of the running desktop
app and the server from a thread dump taken locally), scope lifetimes and leaks, `StateFlow` equality cost for large objects, structured-concurrency
violations that leave work running after cancel.

### O15 — Serialization, crypto and hashing cost
JSON/CBOR/protobuf use and codec construction (`Json {}` per call?), `ByteArray` <-> `String` conversions, Base64 usage, Ed25519/ECDSA verification counts per
second in normal operation (group message signatures in a group of 20, directory statements, delegation chains — verified once or per message? caches and
their keys: see invariant 1), HKDF/KDF per message or per session, `SecureRandom` instantiation and blocking entropy, AES-GCM nonce/cipher object reuse,
SHA-256 `MessageDigest.getInstance` per call, Conscrypt vs JCA providers on desktop, TLS cipher selection and session tickets, certificate generation cost at first
run, constant-time comparisons that are accidentally O(n^2). Microbenchmark the top five primitives in a scratch JVM harness (warm-up, several iterations,
report ops/s and allocated bytes/op).

### O16 — Hub client (`:core:enterprise`, `:core:hub-protocol`)
Directory sync (full vs delta, page size, `applySyncBatch` transaction cost, memory for 5,000–20,000 members, statement verification count), `OrgPeerResolver`
snapshot refresh cost and lock use, announcements sync and FTS, relay client reconnect/backoff, heartbeat period, mailbox ack batching, hub file transfer chunk size
and parallelism, `HubRestExecutor` connection reuse (HTTP client pooling, keep-alive, TLS session reuse), polling vs push, `/auth/verify` call frequency and the
single-flight client refresh, bearer caching, upload/download resume cost, behaviour on flaky mobile data.

### O17 — Hub server (`:server`)
Capacity model: connections per GB of RAM (measure idle and active relay sessions on loopback with a scripted client: 100/1,000/5,000), per-connection buffers
and coroutines, `RelayFrameRouter` routing cost per frame and fan-out, mailbox queue (`MailboxQueue` inserts, sweeps, stats caching), SQLite settings (WAL, busy
timeout, pragmas, connection pool size, `BEGIN IMMEDIATE` contention), top queries with `EXPLAIN QUERY PLAN`, indices on `device_registry`, `org_member_statement`,
announcements and FTS5, file store (per-chunk file creation: inode/`fsync` cost; 50 MB upload through the real Ktor host: MB/s, CPU, heap), audit log hash chain cost
per request, rate limiter data structures (unbounded maps?), session store, background sweepers' period and cost, JSON handling per request, Ktor engine config
(Netty/CIO, worker threads, request body limits), JVM flags in the deploy scripts, startup time, memory at idle, GC pauses (JFR locally), log volume. Produce a
simple load curve on loopback.

### O18 — Admin console (vanilla JS, `server/src/main/resources/admin-ui`)
Payload sizes (JS/CSS/fonts/images; gzip/brotli? caching headers, ETag), number of requests on page load and per view, waterfalls, DOM building cost for large
lists (devices, people, audit log: is there pagination/virtualization?), `innerHTML` vs DOM creation (the code deliberately uses `textContent` — keep it),
re-render on every poll, polling intervals, event listener leaks on view change, WebCrypto step-up key cost, memory growth when switching views, behaviour with
5,000 devices. Measure with a local server and Node-based DOM tests or a scripted fetch of the static assets (transfer size, count).

### O19 — Desktop app (Compose Desktop, Skia, JVM, jpackage)
Idle CPU and idle wake-ups of the window (is a frame produced when nothing changes? infinite animations?), GPU/software render path (Skia backend choice),
window resize cost (relayout of two panes), HiDPI scaling, video decode path (hardware? ADR-052), JVM flags and heap defaults in the jpackage config, AppCDS,
runtime image size (`jlink` modules list), logs written, tray/notification overhead, DPAPI calls on the hot path, startup time to window, memory after 1 hour
(use a local run; JFR or `jcmd`/`jstat` are fine locally), native memory (`NativeMemoryTracking`) for video frames (ERROR-078), thread count at idle.

### O20 — Logging, probes and diagnostics overhead
Log volume per minute at idle and during a transfer/call (count lines produced by a loopback run), string interpolation cost of log arguments built even when
the level is disabled, the file sink (synchronous write per line? buffering? rotation cost), `FlashProbe` emission rate (rule: only on state change — verify there
is no per-chunk/per-frame probe), `redact()` regex cost per line, stack-trace logging in loops, export cost, `Log.d` left in hot paths, release-build log
stripping (R8 `-assumenosideeffects`?).

### O21 — Build, Gradle and CI performance
Configuration time, configuration cache health (is it reused? what invalidates it?), module graph (can KMP targets be skipped per task?), annotation processing
vs KSP, kapt remnants, `kotlin.incremental` settings, daemon heap (2 GB per daemon, 8 CPUs; `org.gradle.parallel`, `workers.max`), `--profile` report for
`:app:assembleDebug`, `:desktop:compileKotlinJvm`, `:server:test`, `:core:messaging:testAndroidHostTest`; the slowest tasks; unnecessary task dependencies;
the lock script's effect on total throughput (queue wait vs build time); R8/lint time; dependency resolution (offline cache gap), the vendored webrtc-kmp
included build rebuilt when it need not be; what a one-line change in `:core:common` triggers (count the recompiled modules).

### O22 — Size: APK/AAB, desktop installer, dependencies
Build a release variant through the lock (`:app:assembleRelease`) and analyse: APK size by component (dex, resources, native libs per ABI — are ABI splits used?
WebRTC native libs are large), R8 effectiveness (`mapping.txt`, keep rules that keep too much, `-dontoptimize`?), resource shrinking, unused fonts/locales/
drawables/Lottie-like assets, duplicate classes, dependency weight (`dependencies` insight: Room, Ktor, BouncyCastle?, Conscrypt, Compose Material 3 vs actual
use; the Hershey font data), `third_party/webrtc-kmp` binaries, MSI size and what is inside the jlink runtime image. List the ten biggest contributors and what
each would save; remember every dependency needs a documented reason (`AGENTS.md` 3).

### O23 — Storage and file I/O
Where files are copied/duplicated (receive path -> temp -> final -> gallery? send path copying a picked file?), SAF `ContentResolver` cost for large files
and many files (query per file, `openFileDescriptor` vs streams), cache directories and their size limits and cleanup policy (thumbnails, voice notes, temp
chunks, swarm content, hub files, logs), retention jobs (`SwarmTombstone`, hub_file pruning, mailbox TTL), disk usage growth over a month of use (estimate
from message and attachment rates), `fsync`/`force` calls per chunk, memory-mapped files, `RandomAccessFile` seeks, antivirus-sensitive patterns on Windows
(many small files), temp-file naming collisions and cleanup after crash.

### O24 — UI responsiveness and perceived performance
Input latency (send button -> bubble appears: outgoing spring launch physics cost; optimistic insert before DB?), sheet/dialog open cost, navigation transitions,
list scroll under load (a running transfer + a call), image placeholder strategy (blur-hash/shimmer cost), skeleton screens vs blocking spinners, "loading" states
that wait on slow work needlessly, prefetching, `reduceMotion` honoured everywhere (also as an energy saving), haptics frequency, animation durations vs frame
budget (16.6 ms at 60 Hz, 8.3 ms at 120 Hz), main-thread work per frame in the conversation screen while the keyboard animates, IME insets handling cost.

### O25 — Algorithmic complexity sweep
Systematically grep the core modules for patterns that hide O(n^2) or worse: `list.contains/indexOf/remove` inside loops, `filter` + `first` repeated, `sorted()` on
every emission, `toList()/toMutableList()` copies of large collections per event, `distinct()` on growing lists, string concatenation in loops, regex compiled
per call (`Regex(` inside functions), `Pair`/`Triple` boxing in hot loops, `Map<String,...>` with expensive keys, repeated `subList` copies, recursion without bounds,
data structures that should be sets/maps/priority queues/ring buffers. For each hit: the realistic n, the frequency, the cost. Report only those that matter at
the project's real sizes (chunks per file up to 100k, messages per chat up to 100k, group 20, directory 5,000, swarm 4 GB / 1 MB pieces).

### O26 — Bluetooth radio link and gateway
Pump thread cost (`BlockingStreamReader`), KISS/frame codec allocation, polling vs blocking reads, reconnect/attempt budget (25 s) and its radio use, memory per
frame, rate limits to the handheld, log volume of the link. Small domain: keep it short.

### O27 — Test-suite performance and determinism
Time per module test task, the ten slowest tests (read the XML reports under `build/test-results`), tests using real `delay`/sleep instead of virtual time
(`runTest`, `TestScope`), tests that wait on wall-clock timeouts (the known flakes: why are they flaky — timing assumptions, shared ports, load?), tests that
build a full database or server per test method, duplicated heavy setup, parallelism settings, whether the `androidHostTest` and `jvmTest` variants run the same
shared tests twice (the shared test harness in `:core:enterprise`: is double execution needed?). Goal: what to change to cut the full test time and the flake rate
without reducing coverage. **Do not delete tests** (`AGENTS.md` 27).

### O28 — Observability gap and the benchmark plan
What can the project measure about itself today (probes, EXP numbers, `FlashLogContext` snapshot, `MemoryGovernor` stats, transfer speed fields), and what can it
not (startup time, frame timing/jank stats, per-screen recomposition counts, bytes on the wire per feature, battery drain per hour, server per-route latency
histograms, DB query timing)? Propose — do not build — a minimal, low-overhead measurement set: which counters/probes to add (obeying the probe rules in
`docs/testing/PROBES.md`: state changes only, no secrets), whether Macrobenchmark/Baseline-Profile/`JankStats`/Perfetto, JMH or a plain timing harness is
justified (each dependency needs a documented reason), and a **benchmark matrix** matching `AGENTS.md` section 23 (file sizes x chunk sizes x streams x LAN vs
hotspot x 2.4 vs 5 GHz x phones x storage speed, CPU, thermals) with each cell marked *owed (device)* or *runnable on the PC now*. Draft the content of the missing
`docs/performance.md` as a file inside your output folder (do not create it in `docs/` yet).

## 7. Evidence standard

| Rating | Required evidence |
|---|---|
| **High** (user-visible regression risk, >20% on a hot path, OOM/ANR/battery drain, or a server capacity cliff) | `MEASURED` numbers from a scratch benchmark or profiler run (before-state; repeat >= 5 runs, report median and spread, warm-up excluded, machine quiet), **or** a complete `STATIC-ARITHMETIC` derivation with the multiplier cited from code, **plus** a verifier pass (4.3). |
| **Medium** | Cost model with the multiplier and a call-chain trace; a micro-measurement if cheap. |
| **Low / Info** | Precise `file:line` and the reasoning; say why it is low (cold path, small n). |

Rules for numbers:
- Say **what machine, what load, what JVM/Android build, what data size**. A number without its conditions is not evidence.
- Distinguish **wall time**, **CPU time** and **allocated bytes** (`ThreadMXBean.getCurrentThreadCpuTime`, `getThreadAllocatedBytes`; JFR allocation profiling locally).
- A JVM benchmark on the PC says nothing precise about an ART phone: state the translation honestly ("order-of-magnitude relative cost only").
- Never report a gain you did not measure as measured. Express expected gain as a range with the assumption stated ("expect 30–50% fewer allocations on this path,
  assuming X").
- **Before/after protocol:** for every High/Medium finding write the exact command or harness that will prove the gain after the fix, and the pass threshold,
  so the implementer and a later verifier can check it.

How to run things safely:
- Server locally: build `:server:installDist` through the lock script, start it on `127.0.0.1` with a fresh temp data directory and the documented dev flags;
  never reuse real data; load only on loopback.
- Client code: prefer JVM benchmarks and tests in the module (`:core:...:jvmTest`, scratch harness) over device runs. No device unless the owner names you.
- Profilers that are fine locally: JFR (`-XX:+FlightRecorder`), `jcmd`, `jstat`, `jmap -histo`, `async-profiler` if already installed, Gradle `--profile`, SQLite
  `EXPLAIN QUERY PLAN`, `apkanalyzer`/`bundletool` if present, Node's `--cpu-prof` for console tests.

## 8. Deliverables (the only files you create or modify)

All under `docs/audit/optimization-2026-10-23/` unless stated.

### 8.1 Per-finding template (use exactly this)

```markdown
### OPT-<domain>-<nn> — <short title>
- **Impact rank:** High | Medium | Low | Info        **Evidence:** MEASURED | STATIC-ARITHMETIC | PLAUSIBLE
- **Dimension(s):** T/J/C/M/B/N/S/K/Z/D   **Runtime:** Android | Desktop | Server | Console | Build
- **Component:** `path/to/File.kt:line` (+ every file on the call chain)
- **Bottleneck class (transfer/media only):** network | storage | CPU | TLS | hashing | protocol
- **What is wasteful:** one paragraph in plain language.
- **Cost model:** frequency x unit cost x multiplier (with the numbers and where each comes from). Conditions of any measurement.
- **User-visible effect:** what a person or the server operator would notice (jank, heat, battery %, seconds, MB, max users).
- **Expected gain:** a range, with assumptions. Say which part is measured and which is estimated.
- **Proposed change:** concrete, smallest sufficient change. Alternatives considered and why not.
- **Invariants touched (5.3):** none | list, and how the change preserves them.
- **Risk / effort:** Low|Med|High risk; S/M/L effort; wire/schema/behaviour changes (owner decision needed?).
- **Prove it:** exact before/after command or harness and pass threshold; the unit/benchmark test that would pin it.
- **Verifier result:** survived | refuted (why)         (High, and any claim >20%)
- **Related:** EXP/ERROR/ADR/audit ids; findings it compounds with.
```

### 8.2 Files

- `00-hot-path-map.md`, `00-periodic-work.md`, `00-baseline.md` (section 4.1).
- `O01-….md … O28-….md` — one per domain: hot-path list with frequencies, checklist table (every item answered), files read, findings, "no problem, because …" notes, measurements taken (with conditions).
- `findings-ledger.md` — one table: id, title, impact rank, dimensions, evidence type, runtime, effort, risk, verifier result, status (OPEN).
- `compounding.md`, `rejected.md` (refuted findings **and** rejected-because-it-breaks-an-invariant ideas, with reasons).
- `bench/` — the scratch benchmarks and the raw outputs (plain files, not wired into any build), each with a README line: what, how to run, machine.
- `performance-doc-draft.md` — the proposed content of `docs/performance.md` (section O28).
- `REPORT.md` — see 8.3.

### 8.3 `REPORT.md` structure

1. Audited commit, date, tools, hardware, what was and was not covered, what needs a device and was therefore only estimated.
2. **Executive summary** for a non-engineer: the five biggest wastes, what a user or the server operator would feel today, and what fixing each buys.
3. Findings by impact rank (links to domain files), counts per domain, per dimension and per runtime.
4. **Top ten by (gain x frequency) / effort**, each with the single change and the proof command.
5. **Fix plan:** ordered batches an implementer can take one at a time ("quick wins, one commit each", "medium refactors", "needs owner decision", "needs device
   measurement first"), naming finding ids and the tests to add. Mark which batches can run in parallel streams without touching the same files.
6. **Do-not-do list:** tempting optimizations rejected because they break an invariant or gain too little, with the reason.
7. Capacity statement: measured or estimated limits (hub connections per GB, mailbox rows, group size, directory size, simultaneous transfers) and what limits each.
8. Documentation corrections (claims in the docs the code or the numbers contradict) and the missing-metrics list from O28.
9. Coverage matrix: domain x dimension, with the finding count (zero is a valid and informative entry).

### 8.4 Repository bookkeeping (per `AGENTS.md`)

- Append a dated entry to `logs/progress.md` and update `logs/handoff.md` (newest entry directly under `# Current Handoff`).
- Record measurements as `EXP-` entries in `logs/experiments.md` (next free number; re-read the file first, other agents may add entries) with devices/PC,
  conditions, numbers and the bottleneck class, exactly as `AGENTS.md` section 9 asks. Never turn one machine's benchmark into a universal assumption.
- Log each **High** finding in `logs/errors.md` as an `ERROR-nnn` entry with `Status: OPEN` (next free number; re-read before writing) — performance defects are defects.
- Add every device measurement or manual test the fixes and estimates owe to `docs/testing/TEST-BACKLOG.md` (prefix `OPT-`, setup, steps, a concrete **Pass**
  threshold, source, `Status: TODO`). Measurements go last (backlog section 5): do not treat an estimate as measured.
- Do **not** delete or rewrite existing documentation. Do not commit unless the owner asks.

## 9. Quality bar — the audit is not finished until

- Every domain O01–O28 has its file, with its hot-path list and **every checklist item answered**.
- Every High finding and every claim of >20% gain has a verifier result; every High has measured numbers or a complete cited derivation.
- The earlier optimizations were re-tested (4.4), and the result (still holds / regressed) is in the report.
- Every number states its conditions; every estimate is labelled as one.
- `REPORT.md` exists with the top-ten list, the fix plan, the do-not-do list, the capacity statement and the coverage matrix; ledger totals match the domain files.
- You told the owner, after each domain, what finished (one line), and at the end you listed the **three highest-value changes** and what you could not measure
  without a device.

If you run out of context or time, stop cleanly: write the state (which domains are done, which is in progress, what the next sub-agent should be told, which
benchmarks are half-run) into `docs/audit/optimization-2026-10-23/PROGRESS.md` so another agent can continue from domain N+1 without redoing earlier work.
