# NectarLink ↔ Flash comparative review

**Date:** 2026-11-03
**Status:** REVIEW ONLY — no product code changed, no build run, no device session.
**Flash tree:** `dev` at `ba1aea19`.
**NectarLink tree:** upstream `thtbee/nectarlink` at `feee39a` (the owner's fork `Kali452345/nectarlink` is a copy with no releases).
**Constraint:** Flash is wireless-first. USB/ADB/cable transport is explicitly out of scope and is not recommended anywhere in this document.

## Method

Six read-only reviewers worked in parallel on separate scopes:

1. wireless transport, discovery, connection lifecycle
2. transfer engine (chunking, backpressure, resume, integrity)
3. wire protocol, pairing, trust, security, capability negotiation
4. Windows desktop (Compose/JVM) architecture, performance, packaging
5. Android runtime, background execution, battery, Compose performance
6. design system, documentation, CI and release engineering

They read both trees and verified claims in code where possible. Line numbers drift; symbols and file paths are the durable references. Every number below is either (a) read from code, (b) taken from a project's own published measurement and labelled as such, or (c) marked **UNVERIFIED**. Nothing here was measured on a Flash device.

## Executive summary

Flash is **ahead on transfer correctness, Android liveness engineering, identity binding, authorization, discovery breadth and test memory**. NectarLink is **ahead on transport multiplexing, protocol compatibility discipline, capability advertisement, desktop resource discipline, design-token generation, and release/CI gates**.

The highest-value improvements are not "become NectarLink". They are:

- close one real security hole (revoked peers can resume TLS);
- stop bulk transfer from parking the only reader that carries control frames;
- bind resume state to the source file so a changed file fails early;
- make the protocol survive mixed versions and advertise capabilities honestly;
- measure Android and desktop resource use, then tune what the numbers show;
- adopt two cheap process assets: generated design tokens and a release gate.

## Priority table

| # | Area | Change worth considering | Why | Effort | Risk |
|---|---|---|---|---|---|
| 1 | Security | Evict the per-peer TLS context when trust is revoked or a pin is replaced | A resumable TLS 1.3 session does not re-run the trust manager (already documented), and `clearCache()` has no production caller, so "Forget" can leave transport alive for up to 24 h | S | Low |
| 2 | Transport | Never park the single read loop on a full bulk channel; byte-bound intake or give bulk its own connection | A slow disk/chunk consumer delays chat, call signalling and PING behind it | M | Medium |
| 3 | Transfer | Persist source identity (size + mtime + prefix digest) with the resume row; fail early when it changed | Current resume seeks past the prefix and only detects a changed file at the end, then deletes all progress | S | Low |
| 4 | Protocol | Accept a version range, refresh capabilities after attach/permission change, answer unknown frames with a typed error | Current exact-equality HELLO and one-shot caps make the next wire addition strand older installs | M | Medium |
| 5 | Android | Release the splash at first composition instead of waiting for `ready`; measure cold start, idle battery, memory | Perceived startup equals full engine boot today; wake lock is held whenever any peer is connected (deliberately, but unmeasured) | S/M | Medium |
| 6 | Desktop | Scope state collection to the tab that needs it; stop rebuilding the whole shell on every transfer tick | ~30 `collectAsState` flows live in the shell that also owns the tab `when`; transfers tick at 75 ms | M | Medium |
| 7 | Transfer | One throughput path: 256 KiB chunks, pooled seal output, hash-on-receive, coalesced writes; keep SHA-256 | 64 KiB × per-frame AES-GCM × copies, a full pre-hash, and a second full read after receive all cost throughput | M | Medium |
| 8 | Protocol/Security | Reject CR/LF in text frames, reject blank device ids, add pairing attempt limits and a revoke frame | Text framing can be terminated early by a hostile name; pairing guessing and silent unpair remain possible | S | Low |
| 9 | Transport | Put transport/path on the session, hand over instead of tear-down, and design the Wi-Fi Direct GO/address rules | `transportType` is hardcoded LAN; a replacement session cancels calls; Wi-Fi Direct has no written design | M/L | Medium |
| 10 | Android | Coalesce transfer notifications, gate them on foreground, add a group summary; reconsider the dataSync timeout stop | Percent updates defeat the 500 ms floor; timeout stops the whole service including `connectedDevice` | M | Medium |
| 11 | Desktop | Trim the UI on hide, wire the image-cache clear, ship one installer format, drop dead JVM modules | Tray hides the window but keeps composition and caches; 174 MB installed, 117 MB MSI + 118 MB EXE | S/M | Low |
| 12 | Design/Docs | One status line per component doc + index check; tokens drift check; split stale protocol sections; add a capability table | The index contradicts the docs it indexes; 34 hardcoded colours outside the theme; protocol.md says shipped features are "not implemented" | S/M | Low |
| 13 | Process | Tag-triggered release job: build, checksum, smoke-install; add contributing/security/conduct files | CI is test-only; the release path is manual and unsmoked | M | Medium |

## A. Wireless transport and discovery

### A1. Bulk can stall control (High)

Flash has text priority in the outbound queue (`WsConnection.kt`, `send(isPriority=…)`), but the writer holds the connection for a whole frame, and inbound is stricter: `readLoop` is `suspend` and awaits `listener.onBinaryMessage`, which sends into a bounded per-session binary channel (`WsSession.onBinaryReceived`). While that channel is full, **no text frame is read**. A slow disk therefore delays chat, call signalling and the peer's PING. NectarLink's comment in `session.rs` records exactly this failure and keeps control on its own QUIC stream at higher priority.

NectarLink is not automatically right for Flash: its 5 s keepalive / 15 s idle in code (`node.rs`) differ from its own docs (1 s / 8 s), so trust the constants, not the prose. The lesson is the independence of lanes, not the numbers.

**Recommendation:** keep the existing priority queue, but make the read loop unable to park indefinitely on bulk: bounded intake with drop + re-request via the existing resume bitfield, or a second connection for bulk. Do not merge control into the bulk queue (ADR-331's lesson).

### A2. Version equality and one-shot capabilities (High)

`peerVersion != PROTOCOL_VERSION` closes the session on all hosts. Capabilities are four tokens (`sw1, gs1, hrs1, cv1`) computed once at engine creation; `hrs1` is emitted by the two host engines but omitted by `Flash.kt` and undocumented in `protocol.md`. There is no `hello.update`, no typed rejection, and no documented additive-compatibility rule. NectarLink has `proto`/`min`, `VERSION_TOO_OLD`, `hello.update`, per-operation versions and `UNSUPPORTED`.

**Recommendation:** accept `[min, current]`, send a typed error with the supported range, allow a capability refresh after swarm attach/permission changes, and publish one registry token → meaning → "unlocked by". Keep fail-closed: no silent downgrade.

### A3. Transport is a constant, not a path (Medium)

`WsSession.transportType` is hardcoded LAN and `FlashDevice` is built as LAN; the richer ranking exists in `SessionHardeningPolicy` but nothing supplies it. A replacement session tears the old one down, which cancels calls and resumes roaming-killed sends. At 20 peers one Wi-Fi switch costs 19 handshakes and 19 down/up events; `ReconnectStagger` softens the storm but loses state.

NectarLink's `ConnectionPath` (LAN/relay; USB excluded here) and in-place session handle update are the model. Flash needs the same seam for Wi-Fi Direct and for any future relay path.

**Recommendation:** transport as a constructor input, path on `FlashSession`, hand-over instead of down/up. This is a seam change first, a transport change second.

### A4. Roam recovery (Medium)

`onLinkChanged` probes every session with a 2 s-class budget and reaps silent ones; `onAvailable` only sweeps peers with **no** live session. So a socket bound to the old network can wait out the 10 s/25 s liveness watchdog. The code comments explain why probing is preferred to tearing down, and that is sound. The gap is that `onAvailable` does not run the probe.

**Recommendation:** call the existing probe from `onAvailable` too. Small, low risk, and it uses code that already exists.

### A5. Inbound routes and listen port (Medium)

Only authenticated outbound dials record a route; HELLO carries no listen port, so an inbound session teaches nothing. If the dialer restarts on a multicast-filtered network, the pair may not re-form. NectarLink records addresses on both sides and reuses a stable bound port.

**Recommendation:** put listen port + transport in HELLO under the version-range change, and persist it on inbound. Wireless-only; no new transport.

### A6. Wi-Fi Direct is undesigned where it matters (Medium)

`WifiP2pManager` has no references; `WIFI-DIRECT-PLAN.md` is unwritten, although discovery and session ranking already prefer the path. NectarLink has no Wi-Fi Direct; what transfers is the path-as-connection-property model and cached addresses when discovery is blocked.

**Wireless-only design notes:** map the lower-id dial rule to `groupOwnerIntent` (higher id requests GO) but always trust `WifiP2pInfo.groupOwnerAddress`; never hardcode `192.168.49.1`; emit the GO address as a sighting with transport `WIFI_DIRECT` and the HELLO port; bind mDNS/multicast to `p2p0`; use `NEARBY_WIFI_DEVICES` (API 33+), location only where required, `connectedDevice` FGS, multicast lock. Re-form on the both-GO case.

## B. Transfer engine

### B1. Chunk size and per-frame cost (High)

Flash's `FlashTransferProfile`: LOW 32 KiB, MEDIUM 64 KiB, HIGH 64 KiB base with a 1 MiB adaptive ceiling (the HIGH KDoc still says 128 KiB). Each chunk is serialized into a new array, sealed with AES-256-GCM using a fresh CSPRNG nonce (two more arrays), and decrypted with `copyOfRange`. That is ~16k frames/GiB and three copies + a seal each. NectarLink reads 256 KiB, coalesces into 1 MiB blocks and a 4 MiB write buffer, and has only TLS underneath.

**Recommendation:** measure 64 → 256 KiB on 5 GHz and hotspot (the wire already negotiates `FILE_START.chunkSize` and accepts up to 1 MiB), pool the seal output, and put counter-derived nonces on the security review list only if the measurement justifies the change.

### B2. Whole-file pre-hash before the offer (High)

`MultiStreamDispatcher` calls `chunker.hashOnly(source)` before `FILE_START`; no production caller supplies a digest, so a 4 GiB send reads the file once to hash, then again to send. The receiver re-verifies at the end anyway. NectarLink has the same shape, so this is a shared cost, not a NectarLink win — but Flash can remove it because the receiver is authoritative.

**Recommendation:** offer without the digest and carry it in the trailing `COMPLETE`, or hash in parallel with the first stream. Version the frame. Record the before/after.

### B3. Resume is not bound to source identity (High)

`TransferEntity` stores no size/mtime/hash; `TransferChunkEntity` stores only `(transferId, chunkIndex, done)`. The one digest cross-check is skipped when `initialIndex > 0`, and a seekable resume seeks past the prefix. A changed file produces a full-length mismatch only at the end, then deletes the file and all progress. NectarLink binds the digest to the offer and re-hashes the prefix when a `.part` is already full-size.

**Recommendation:** store size + mtime + first/last 64 KiB digest with the row; on resume, verify cheaply and restart from zero early with a clear sentence. This is the cheapest high-value correctness fix in the review.

### B4. Receive writes inline on each reader (Medium-High)

`onFrame` runs on the data-channel reader; hashing and `sink.write` are outside the pipeline lock but still on that thread; `writeAt`/`flush` hold one per-transfer handle lock and seek/write 64 KiB per chunk. A slow disk becomes socket backpressure for every stream. NectarLink has one reader per stream and coalesces writes.

**Recommendation:** a bounded per-transfer write queue drained by one writer; coalesce sequential writes. This also reduces the A1 stall.

### B5. Failure detection is blind and late (Medium)

The sender has no in-flight window or progress ping; when workers exit it waits 2 s settle + 200 ms ACK grace + 3 s drain before failing, and the endgame can re-send an unACKed tail. NectarLink's receiver answers `ACCEPT{have}` with an exact resume offset.

**Recommendation:** receiver pings highest contiguous index every 200–500 ms; stall timer under 1 s; endgame rescans real holes only. **PERF-T09/T10** are already owed and are the right place to record this.

### B6. No durable queue for an offline peer (Medium)

Flash creates the row `Queued` and starts immediately; recovery is the session-up edge, capped at three zero-progress attempts, no expiry. NectarLink has a persistent queue with per-peer retry and expiry.

**Recommendation:** persist send intents with a visible expiry and one policy for both hosts. Never ship an unexpiring queue.

### B7. No end-to-end large-file benchmark (Medium)

Flash's only throughput test is an in-memory microbenchmark (`Channel.UNLIMITED`, `MemorySink`, no socket/disk/multi-stream). NectarLink measured 4 GiB in 16.0 s with two pause/resumes and 1.74 s BLAKE3 (its own published number; likely loopback — **UNVERIFIED**). Flash's structural gap (B1, B2, B8) predicts a lower number, but nobody has measured it.

**Recommendation:** two phones on 5 GHz, 4 GiB, 0 and 2 pauses; wall time, sender/receiver CPU, peak RSS, allocations. Then a 1 GiB 2.4 GHz run.

### B8. Every completed receive reads the whole file again (Medium, measure first)

`WholeFileVerifier.check` streams the finished file through SHA-256 after every chunk was already verified before write. NectarLink hashes during receive and never re-reads.

**Recommendation:** skip the second pass when the receive was single-stream, non-resumed and flushed cleanly; keep it for resumes/multi-stream. The second pass is currently the only net under B3, so fix B3 first.

## C. Protocol, pairing and security

### C1. Revocation cannot evict a resumable TLS session (High)

`FlashTlsContextFactory.clearCache()` exists on both hosts but has **no production caller** (only `TofuTlsHandshakeTest`). `revokeTrust` removes the pin; a resumed TLS 1.3 session does not re-run the trust manager, so transport can survive up to the 24 h resumption window. Only the app-layer `isTrustedPeer` gate remains, and the v2 group gate's "live TLS key equals cert key" test needs a leaf a resumed session never presents. NectarLink re-checks `is_paired` before any control stream.

**Recommendation:** call the existing `clearCache()` (per-peer or all) inside `revokeTrust`, vouch revocation and pin replacement; add a test that a revoked peer cannot resume.

### C2. No pairing failure limit (Medium)

Flash has expiry but no failed-attempt counter; NectarLink has a 5-minute TTL and 5 attempts. Unbounded prompting and unbounded guessing against a user who keeps tapping "compare" is a real weakness.

**Recommendation:** per-peer counter with backoff, plus a `PAIRING` security log line.

### C3. Text framing accepts control characters and blank ids (Medium)

`FlashTextFraming` escapes only `%`, space and `=`; `\r`/`\n` pass through and break the two-line `FLASH_DATA` envelope, so a hostile name can terminate a frame early. A HELLO with no `deviceId` is bound to the literal `"unknown"`. NectarLink bounds this structurally with a 1 MiB cap, LEB128 limit, CBOR failure → `BAD_MESSAGE`.

**Recommendation:** reject C0 controls at encode and parse; reject a HELLO whose id is blank. Keep the existing duplicate-key rejection and escape order.

### C4. Capabilities are thin and host-inconsistent (Medium)

Four tokens, one of them undocumented, one omitted by `Flash.kt`. Flash cannot express "can receive files", "has a microphone", "serves hub files"; the UI infers from host/version. NectarLink has ~50 registry IDs with "unlocked by" and a matrix that drives both UIs.

**Recommendation:** one `FlashFeatures` registry, one token per user-visible feature, published in `protocol.md`, refreshed after attach/permission change.

### C5. No revoke frame; hub plaintext fallback underspecified (Low-Medium)

Flash defines no revoke frame, so after A forgets B, B keeps sending into silence. `protocol.md` says the host apps do not publish a hub sealing key, but they do; what happens when a recipient has published nothing is **UNVERIFIED** (a sealed message may be held rather than acked). NectarLink degrades by path and blocks sensitive capabilities on relay.

**Recommendation:** authenticated, idempotent `PAIR_REVOKE` (or at minimum a "peer no longer trusts you" failure reason); state the no-key behaviour exactly and forbid plaintext whenever a key exists.

### C6. Protocol documentation is stale in the optimistic direction (Medium)

`protocol.md` still marks group swarm and group membership "PROPOSED 2026-10-04, not implemented" while AGENTS §29 says swarm is on by default and GM-1…GM-10 are built. A third party would implement a protocol the app no longer matches. NectarLink publishes 20 per-feature specs with draft banners.

**Recommendation:** correct status banners now; split only the sections that changed status; add the capability table. Do not rewrite the whole document for symmetry.

## D. Android runtime

### D1. Startup waits for the whole engine (High, perceived)

`MainActivity` keeps the system splash until `appEngine.ready` (Keystore TLS identity, SQLite, WS server, NSD). The boot itself is already off-main, so the blocker is the splash condition, not threading. NectarLink paints first and defers `core.start()` past the first frame with a 120 ms fallback.

**Recommendation:** release the splash at first composition and let `ready` drive content; keep the start-error path from trapping the user. Measure `am start -W` before/after.

### D2. Wake lock follows every connected peer (High, unmeasured)

`updateCpuWakeLockState` treats a live session as work, takes a 10-minute partial wake lock and refreshes it every 5 minutes while any session exists. The code comments defend this: a suspended CPU runs no timer, so the peer would time this device out. That is a real constraint, not a bug. But no idle-connected battery measurement exists, and NectarLink takes no wake lock at all.

**Recommendation:** one hour of `batterystats`, screen off, one paired peer, no transfers. If the cost is material, test a lower keepalive cadence under screen-off instead of removing the lock. Do not remove it blindly.

### D3. Notifications are not coalesced or foreground-gated (Medium)

`FlashBackgroundService` posts when `now-lastPost >= 500 || percent != lastPercent`, so the percent branch defeats the 500 ms floor; `FlashNotificationManager` has no `appForeground` gate for transfers (chat has one). There is no `setGroup` for message notifications, and `clearAllTransfers` issues up to 1000 binder cancels.

**Recommendation:** gate on foreground/open Transfers screen, use the 500 ms floor only, add a group summary, and batch cancels.

### D4. dataSync timeout stops the entire service (Medium)

`onTimeout` pauses rows then `stopSelf()`, including the `connectedDevice` role; promotion is retried only from `start`/`onStartCommand` and may be refused from background. B-02 is otherwise fixed (pause, not cancel). NectarLink declares only `connectedDevice`.

**Recommendation:** use `dataTransfer` for user-initiated sends, or stop only when nothing else needs the service; re-promote on screen-on.

### D5. Engine-lifetime pollers (Medium)

`CallCoordinator` sweeps every 5 s for the process lifetime, the relay client idles at 5 s, hub presence at 15 s, swarm environment sampling on a timer. NectarLink is status-flow driven.

**Recommendation:** schedule per-entry expiry, feed call state by flow, drop the relay idle poll when a wake channel exists. Measure wakeups first.

### D6. No Android baseline (Medium)

`MEAS-*` items are still TODO and the numeric headlines are withdrawn. The cheapest four numbers (cold/warm start, PSS, gfxinfo, 1 h battery) would make every other Android finding decidable.

### D7. Transsion keep-alive is expensive by design (Low-Medium)

A continuous low-power BLE scan, a 1×1 overlay, or a silent AudioTrack with audio focus, plus `mediaPlayback` in the FGS types. Requesting focus at screen-off can pause the user's music. This is a documented owner workaround (ERROR-074); measure and disclose, do not remove without a replacement.

### D8. Hub tick and lifecycle collection (Low)

The hub route republishes `nowMs` every 30 s and re-reads unread counts; the approvals dialog polls at 1 s while open; the shell collects ~25 flows with `collectAsState` rather than `collectAsStateWithLifecycle`. Impact **UNVERIFIED** without a gfxinfo run.

## E. Windows desktop

### E1. Whole-app recomposition scope (High)

`DesktopShell` collects ~30 flows — chat list, whole conversation state, discovery, sessions, sweep, pairing, hub, radio, transfers throttled to 75 ms — inside the one composable that also owns the tab `when`. Any off-tab event invalidates the shell body and re-runs unrelated screens. NectarLink subscribes by interest bitmask with at most one queued refresh.

**Recommendation:** move each tab's collection inside its branch (or `movableContentOf`/`key`), keep only readiness/nav at shell level. Verify with JFR during a 1 GB transfer.

### E2. Time-to-interactive is unbounded and unmeasured (Medium-High)

The window paints fast, but the ink splash swallows input and cannot finish before `introEnd`, waiting to a 6 s ceiling if the engine is not settled. `DesktopEngine` already logs `[bring-up] <stage> (+Nms)`; add a first-frame probe once and the cold-start budget is free.

### E3. Tray throws away the UI but keeps the JVM (Medium)

Hiding destroys the composition (`if (isWindowVisible) { Window(...) }`), so reopening re-queries Room and re-decodes thumbnails; nothing trims the process. The image decoder is bounded by **count** (32, "not bytes") and `clear()` has no production caller. NectarLink unloads the window, then `gc()` + `trimWorkingSet()`, and reaches 14.2 MB tray.

**Recommendation:** a public `clearThumbnails()` seam on the hide edge, a bytes-based cap, and one measurement of tray memory before deciding rebuild-vs-retain. A realistic Compose/JVM target is ~180–250 MB tray and ~350–500 MB open (**UNVERIFIED estimate**).

### E4. No update path, no crash handler (Medium)

No updater, no `setDefaultUncaughtExceptionHandler`, no file association; installers are not Authenticode-signed. NectarLink has a GitHub-release updater that verifies the release's SHA-256 before running the installer (its installer is also unsigned). Cheapest first step: signing + a manual "check for a newer version" link; a silent updater is a separate project.

### E5. Packaging (Low-Medium)

Measured from the portable zip: 174.4 MB installed / 265 files; 117.6 MB MSI and 118.2 MB EXE both shipped. The JVM module list is already 16 modules; only `java.instrument` and `java.management` (~1 MB) look unused. The weight is `modules` 54.5 MB, `skiko-windows-x64.dll` 12.1 MB, `icudtl.dat` 10 MB, `bcprov` 8.5 MB, webrtc native 8.4 MB, Compose jars ~11.5 MB. NectarLink: 106 MB installed → 36.7 MB NSIS, helped by `windeployqt` exclusions and a strip that already saved 16.7 MB.

**Recommendation:** ship one installer format, drop the two dead modules, then an LZMA/NSIS-style stage if download size matters. **PERF-A03** (installer smoke test) is still owed, so no trimmed module list has been validated against the packaged image.

### E6. Engine construction does file I/O in composition (Low)

`remember { DesktopEngine() }` opens the identity store, settings and trust store during composition. Boot itself is correctly off-thread. Move the store constructors into `assemble()`; the shell already renders an empty state.

### E7. No desktop performance evidence (Medium)

`docs/performance.md` does not exist. The five cheapest budgets: cold start and T2I, idle CPU, working set tray/open, heap/native split after a fixed chat fixture, and 20-step resize + tray reopen with JFR. Record command lines and mark anything not run NOT MEASURED.

## F. Design system, documentation, CI and release

### F1. The design gate is unenforceable (High)

The rule is doc ≥ DESIGNED before code, but the index contradicts the docs: accessibility says NOT STARTED while its doc says IMPLEMENTED; UI-025/026/027 say IMPLEMENTED while their docs say DESIGNED; UI-057 says DESIGNED although the feature shipped; avatar says NOT STARTED while code and tokens ship; UI-031 appears twice. "Was it designed first?" cannot be answered from the repo. NectarLink has a recorded direction decision with losers named and a spike enforced in CI.

**Recommendation:** one machine-readable `status:` line per component doc; generate or check the index from it. Fix plumbing, not the rule.

### F2. Tokens are hand-authored with no drift gate (Medium-High)

Flash has no generated tokens: `FlashColors.kt` is 241 lines of literals, `ui/chat` has 34 hardcoded `Color(0x…)` (8 in `FlashFileMessageCard`, 5 in `FlashAttachmentSheet`), and there is a parallel `HubTone` family. The `data class` and the WCAG test mitigate this, but they do not prevent drift. NectarLink generates QML + Kotlin from one `tokens.json` and fails CI on drift (`cargo xtask tokens --check`).

**Recommendation:** add a drift **check** and a lint rule banning `Color(0x` outside `ui/theme` now (S); defer full codegen (M) until a second theme lands or the two hosts diverge again.

### F3. Performance numbers are unauditable (High)

71 EXP entries, 49 NOT MEASURED, 22 withdrawn; `docs/performance.md` missing. NectarLink's table has budget/measured/verification-method but no per-row commit or date, so it can also rot. The minimal fix is six fields per number: commit, date, device+OS build, probe/command, metric with units, verdict (MEASURED / NOT MEASURED / WITHDRAWN), reusing `docs/testing/PROBES.md`.

### F4. CI is test-only; the release path is ungated (High)

Flash CI runs `allTests testDebugUnitTest test assembleDebug` + `:app:lintDebug`. Absent: generated-file drift, dependency/licence audit, Kotlin formatter/static analysis, release build, installer smoke, signing. NectarLink runs fmt, `clippy -D warnings`, tests, QML tests, release build, package + installer smoke, UI warnings check, drift, headers, `cargo-deny`, shellcheck and package install/remove.

**Recommendation:** a tag-triggered job calling `tools/build-release.ps1` plus checksum upload, and a no-new-hex/drift check. Do not import the whole NectarLink matrix at once.

### F5. Engineering memory is append-only monoliths (Medium)

`logs/handoff.md` 7,178 lines with the §10 template repeated 87 times; `logs/errors.md` 12,538 lines; `logs/progress.md` 16,720; `docs/decisions.md` 6,770 with 245 ADR numbers under max 350 and 105 gaps, no reserved-number index. Four files AGENTS §5 promises are missing: `docs/testing.md`, `docs/performance.md`, `docs/known-issues.md`, `docs/troubleshooting.md`.

**Recommendation:** archive by month, keep ≤200 lines current, publish the ADR reserved ranges. This is a documentation change, not a product change.

### F6. Release engineering and contribution hygiene (Medium)

Flash builds MSI + EXE + APKs with SHA-256 from a workstation; no updater; no `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` or `TRADEMARKS.md`; no SPDX header check; no dependency-licence policy. NectarLink has all of those, plus a tag↔version check and `apksigner verify`. Flash's `NOTICE` already records the webrtc-kmp modification and the Hershey acknowledgement, and `ThirdPartyNoticesTask` generates the notices file — the gap is the surrounding policy, not provenance.

**Recommendation:** add the four short files and a dependency-licence check; add per-component provenance notes for ideas taken from GPL/MPL projects. For ideas only, nothing legal is missing.

## Where Flash is already ahead

Do not regress these while adopting anything above.

1. **Per-chunk verify-before-write with implicit NACK and bitfield repair** (`ReceivePipeline`). NectarLink has only a whole-file hash, so one corrupt block on flaky Wi-Fi costs the whole file.
2. **Durable resume**: fsync then atomic rename, process-death recovery, explicit memory bounds per performance tier, thermal stream cap, storage gate and FAT32 4 GiB refusal. NectarLink uses bare `rename` and sweeps partials after an hour.
3. **Android liveness engineering**: monotonic clock, bounded stall forgiveness, one pinger per pair, traffic-as-proof, one ticker for N sessions; plus `ReconnectStagger`, deterministic first dialer and dial budget.
4. **Discovery breadth**: NSD, persisted routes, directed broadcast, /24 sweep, gateway probe, manual IP, hub mailbox, radio.
5. **Identity binding**: claimed `deviceId` must match the pinned TLS leaf, with a deferred-leaf path for manual IP; `PeerKeyChanged` fails closed without discarding the contact; own-key-poisoning heal.
6. **Authorization lattice**: `PeerTrustPolicy` separates chat/ring from PTT/swarm-serve/vouch; group gate refuses a TOFU session after a vouch; hub payloads are client-sealed above the relay.
7. **Test memory**: `TEST-BACKLOG.md` (6,043 lines, ID/steps/pass/status, 148 TODO), `PROBES.md`, honest NOT MEASURED retractions, error journal format, multi-agent build lock.
8. **Shared code**: one `:ui:chat` and one `:core:*` across Android and desktop. NectarLink has two UI codebases and pays for it.
9. **Android packaging**: universal APK **and** per-ABI splits. NectarLink ships one universal APK with two ARM cores.

## Rejected or not recommended

- **ADB/USB/cable transport.** Owner decision; also not a NectarLink feature we need. Wireless only.
- **Qt/Electron rewrite or a core-language switch.** NectarLink's 165 MB open / 36.7 MB installer is not a reason to abandon KMP's shared UI and one language. Adopt the resource discipline, not the stack.
- **Copying NectarLink app code.** Its apps are GPL-3.0; Flash is Apache-2.0. Ideas and the CC-BY protocol specification are usable with attribution; code is not.
- **Silent protocol downgrade.** Add a typed error and a compatibility matrix, keep fail-closed.
- **Removing the Android wake lock without measurement.** The code explains why it exists; measure the cost first.
- **Swapping SHA-256 for BLAKE3 as a drive-by.** ADR-010/ADR-014 chose SHA-256 deliberately; fix the structural costs first, then measure.
- **Making the hub a call relay without a media plan.** Signalling alone is insufficient without relayed media or TURN.
- **Adding a second installer format** or more UI frameworks.

## Measurement backlog (cheapest first)

These are the numbers that make the rest decidable. None were run for this review.

1. **Android baseline:** `am start -W` cold/warm, `dumpsys meminfo`, `dumpsys gfxinfo` 5× scroll, 1 h `batterystats` screen-off with one paired peer.
2. **Transfer:** 4 GiB on 5 GHz, 0 and 2 pauses; wall time, sender/receiver CPU, peak RSS. Then 1 GiB on 2.4 GHz. Then the same with 256 KiB chunks.
3. **Desktop:** `[bring-up]` timestamps + first frame (T2I), `Get-Process` working set tray/open, idle CPU 60 s, JFR during 1 GB transfer, MSI/EXE size.
4. **Security:** a revoked-peer resume test; a mixed-version HELLO matrix (v2 ↔ v3 stub).
5. **Protocol:** one malformed-frame corpus (CR/LF, blank id, oversize, duplicate keys) with a pass/fail table.

Record each with commit, date, device+OS, command and verdict. Anything not run stays NOT MEASURED.

## Suggested sequence (review-level, no implementation)

**Wave 1 — safety and correctness (days):** TLS cache eviction on revoke/unpair/pin change; CR/LF and blank-id rejection; resume source identity; pairing attempt limit; protocol status banners. These are small, testable and independent.

**Wave 2 — measurement-led performance (weeks):** Android baseline and 1 h battery; 4 GiB transfer benchmark; 64 vs 256 KiB; hash-on-receive; desktop JFR and tray memory. Change only what the numbers justify.

**Wave 3 — compatibility and polish (weeks):** version range + capability registry; transport/path seam; per-tab state scoping; notification coalescing; tokens drift check; CI release gate.

## Not verified

No build, no device, no packet capture, no profile. Statements about Flash behaviour are code reads; statements about NectarLink performance are its own published measurements; the 4 GiB figure is likely loopback and is labelled as such. The Wi-Fi Direct design notes are design claims, not code. Hub no-key behaviour and the relay's ability to carry call frames unchanged are UNVERIFIED.

## Sources

- Flash: `AGENTS.md`, `docs/protocol.md`, `docs/security.md`, `docs/decisions.md`, `docs/performance.md` (absent), `docs/testing/TEST-BACKLOG.md`, `docs/testing/PROBES.md`, `core/network`, `core/transfer`, `core/security`, `core/persistence`, `app`, `desktop`, `ui/theme`, `ui/chat`.
- NectarLink: `README.md`, `docs/PLAN.md`, `docs/technical/architecture.md`, `docs/technical/security-model.md`, `docs/technical/performance-budgets.md`, `docs/protocol/`, `docs/design/`, `core/nectarlink-core`, `core/nectarlink-protocol`, `desktop/app`, `android/app`, `.github/workflows/`.
