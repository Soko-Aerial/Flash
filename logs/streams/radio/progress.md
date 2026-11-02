# Radio stream (R) — progress log

Fragment for `logs/progress.md`. The integrator folds it in at merge time (plan §5.1); this file is the record.

## 2026-11-02 — Optimization wave 5, stream R (radio), findings O26-01..O26-07

### Environment

- Worktree `E:\FlashWork\opt-radio`, branch `agent/opt-radio`, base `7adcf4f5` (the pushed `dev`).
- Windows 11, 8 logical cores, ~2.4 GB free RAM, shared with other agents: every Gradle run went through
  `tools\flash-gradle.cmd` (exit 75 / lock waits happened twice and were waited out, never bypassed).
- JBR 21, Gradle 9.5.0. `:core:radio` targets JVM 11 bytecode and Android API 24+.
- **No radio, phone or desktop session was used.** Every number below is a JVM measurement or arithmetic over shipped
  constants; every device claim is NOT MEASURED and is listed in `backlog-additions.md`.

### What was worked on

The seven `O26-*` findings of `docs/audit/optimization-2026-10-23/O26-bluetooth-radio.md`, each re-verified against the
code first (the audit was written at `4dcc7fad`, before the 2026-10-20 radio link fixes and the 2026-10-23/28 data and
voice rounds).

| Finding | Verdict | Evidence |
|---|---|---|
| `O26-01` 250 ms semi-blocking read loop | **DONE**. `KissTncConfig.readTimeoutMs` default 250 ms -> 60 s heartbeat; loop and cancellation unchanged | EXP-130: **14 401 -> 61** `read` calls per idle hour (measured side by side in one test) |
| `O26-04` framing / AAD / serial buffer churn | **DONE**. `authenticatedBytes` 5 arrays -> 1; `StreamFrameDecoder` O(n^2) -> O(n) via a new `ByteQueue`; `KissDecoderStats` mutation instead of a copy per byte; `JvmSerialLink.write` no longer copies the frame | EXP-131 (JVM allocation counters): AAD **96.5 -> 48.0 B/call**, unsynced-line stats **4 MiB -> 0 per 64 KiB**, 8 KiB message in 1-byte chunks **34.5 MB -> 0.63 MB** |
| `O26-03` unbounded `pendingAcks`, `UNLIMITED` lanes | **DONE**. New `PendingAckTable` (256 entries, 30 min TTL), lanes 32, reader chunk channel 32 with blocking hand-over | ADR-331; tests `PendingAckTableTest` (5) and an engine test that 300 unacknowledged sends stay capped |
| `O26-05` up to 4 s of `Thread.sleep` in Android probing | **DONE**. `openRadioLink`/`openRadioAudioLink` are `suspend`; the 250 ms pause is `delay`; order, pause length and the 25 s budget unchanged | compile gates; behaviour owed to `PERF-R03` |
| `O26-07` JCA lookups + SPKI re-parsing | **DONE**. Provider resolved once per primitive; parsed sender keys cached (16, keyed by SPKI bytes); fresh `Cipher`/`Mac`/`Signature` per call, no keyed ThreadLocals | EXP-132 (in-process A/B): `Cipher.getInstance` **7.1 µs -> 1.2-2.3 µs**; `Signature` 1.4-2.2 -> 0.6-0.9 µs; `Mac`/`MessageDigest` unchanged; SPKI parse 6.5 µs per frame avoided |
| `O26-02` channel occupancy / goodput | **MEASURED, no code change** (the two proposed fixes are a wire change and an on-air behaviour change) | EXP-133: 236-byte frames, 2 080 ms airtime, 3.33 s channel per segment, 56.5 B/s per segment, 20.0 s for 1 KB, 53.3 s for the 3 008-byte maximum |
| `O26-06` voice over the data channel | **OBSOLETE, marked not built**. ADR-210/ADR-225 put audio on the vendor SBC RFCOMM channel; REJ-06 refuses packet-radio voice on Shannon grounds | plan §8, audit `rejected.md` |

### Changed (files)

- `core/radio/src/commonMain/.../driver/KissTncDriver.kt` — `readTimeoutMs` default and its KDoc, a named
  `DEFAULT_READ_HEARTBEAT_MS`, a comment in `readLoop`.
- `core/radio/src/commonMain/.../wire/RadioWire.kt` — `authenticatedBytes()` writes one exact 25-byte array (same bytes).
- `core/radio/src/commonMain/.../wire/StreamFramer.kt` + new `wire/ByteQueue.kt` — the decoder's partial frame is a
  compacting queue.
- `core/radio/src/commonMain/.../kiss/KissFrameCodec.kt` — `KissStreamDecoder` keeps mutable counters and builds the
  `KissDecoderStats` snapshot on demand (public type and fields unchanged).
- `core/radio/src/jvmMain/.../link/JvmSerialPortCatalog.kt` — `writeBytes(data, count, offset)` instead of a copy
  (ERROR-971).
- `core/radio/src/commonMain/.../chat/PendingAckTable.kt` (new) + `RadioChatEngine.kt` — bounded ACK table, bounded lanes,
  `internal pendingAckCount` for the test.
- `core/radio/src/androidMain/.../link/BlockingStreamReader.kt` — bounded chunk channel, blocking hand-over, no dropped byte.
- `core/radio/src/androidMain/.../link/AndroidBluetoothCatalog.kt` — `suspend` probing with `delay` instead of `Thread.sleep`.
- `core/radio/src/{jvmMain,androidMain}/.../wire/JdkRadioCrypto.kt` — provider selection once, parsed-key cache, same
  algorithms, key sizes, nonce and AAD (byte-identical copies kept in sync).
- Tests: new `RadioReadLoopCadenceTest`, `PendingAckTableTest`, `RadioAllocationBenchTest`, `RadioCryptoMicrobenchTest`,
  `RadioChannelOccupancyTest`; one added case in `RadioChatEngineTest`.

### Why

The audit's cost model, checked against the code rather than assumed: the read loop really did tick 4x/s doing nothing;
the AAD really was built from five arrays on every frame; the stream decoder really was quadratic; the ACK table really had
no eviction; the Android probe really held `Dispatchers.IO` threads in `Thread.sleep`; and every frame really paid a
`Cipher.getInstance` provider scan. Two of the audit's claims did **not** hold and are reported as such: `Mac`/
`MessageDigest` provider lookups cost nothing measurable here, and the SPKI cache does not give "2x verify throughput"
(a P-256 verify is 1.3-2.2 ms and the parse is 6.5 µs).

### Verification

Gate runs, all through `cmd /c "tools\flash-gradle.cmd ..."` from the worktree root:

```text
cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --console=plain"
  BUILD SUCCESSFUL in 1m 1s   (baseline, before any change, exit 0)

cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks --no-build-cache --console=plain"
  BUILD SUCCESSFUL in 54s   (6 actionable tasks: 6 executed, exit 0)
  35 suites, 271 tests, 0 failures, 0 errors, 0 skipped
  RadioGoldenFrameTest.goldenFrameForFixedInputs: PASS (on-air bytes unchanged)
  RadioSessionTest (ERROR-870 anti-replay): PASS (untouched, re-run)

cmd /c "tools\flash-gradle.cmd :core:radio:compileAndroidMain --console=plain"
  BUILD SUCCESSFUL (exit 0)
```

Test count went 259 -> 271 (12 new tests: 4 cadence, 5 table, 3 occupancy; plus 4 measurement tests that also assert
behaviour). The `:core:radio` suite is what the integrator's gate 1 gains when this merges.

### Problems

- ERROR-971: jSerialComm's public `writeBytes` is `(byte[], int, int)`, not `(byte[], long, long)`; passing `Long`
  resolved against a private native overload and failed with a misleading "it is private" message.
- ERROR-972: the K2 backend crashes with an internal error on an anonymous `LinkedHashMap` subclass read through an index;
  both new caches use a plain map with explicit eviction instead.
- One self-inflicted test failure, kept in the record: the new `ByteQueue` initially mixed absolute `head` with a relative
  `tail`, so `size` was wrong; **the pre-existing `StreamFramerTest` (chunk=3) caught it on the first run**, which is why
  that test was not touched. Fixed; it passes.

### Remaining / next

- Device checks `PERF-R01`..`PERF-R05` (`backlog-additions.md`), plus the `RADCHAT-DEV03..07` re-runs.
- Deliberately not done: `O26-02` compression (needs a `FLAGS` bit: wire change, owner's call, measured case in EXP-133) and
  inter-segment emergency preemption (an on-air behaviour change that needs two radios to judge; recorded as a proposal).
- `RadioLinkTester.pendingAcks` (the diagnostic tool, `core/radio/.../diag`) is still an unbounded map; it is bounded in
  practice by the burst length the tool is asked to send and is not a product path. Left as is and recorded here so the
  next agent does not "discover" it as a new defect.
