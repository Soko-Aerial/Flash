# Stream C (calls) — integrator review

**Reviewed:** `agent/opt-calls`, 13 commits off `7f04632e` plus one merge of `dev` (`5e8138dc`).
**Reviewer:** integrator session, 2026-11-02. **Status: merged after the gates.**
**Not device-verified.** No camera, microphone, second phone or real airtime was available in this session.

Scope: `core/calling/**`, `core/ptt/**`, `ui/callui/**`, the jvm target of `third_party/webrtc-kmp`, `app/**/calling|ptt`
and `logs/streams/calls/**`. `git diff --name-only 7f04632e..agent/opt-calls` filtered against those paths returns
**nothing**.

---

## 1. Disposition

**9 delivered, 1 no longer reproduces, 4 honestly BLOCKED.**

| Finding | Verdict |
|---|---|
| `O11-02` camera keeps capturing while muted | **delivered in the fork** — and **inert until republished** (see §3) |
| `O11-06` occluded tile still rasterising | kept — track unbound while the placeholder covers it, surface stays composed |
| `O11-04` O(N) stats allocation | kept — one pass into reused buffers; NOT MEASURED (no `PeerConnection` test double) |
| `O11-07` uncapped screen share | **no longer reproduces** — `ShareLadder.CROWD_WATCHERS = 3` already steps four watchers to 720p8, below the audit's own 32 Mbps-ish target; nothing changed |
| `O11-05` dead SDP bitrate hints | kept — video section passed through; see §4 |
| `O12-01` 50 Hz stats | kept — ≤10 Hz, tested |
| `O12-02` per-packet session id | kept — see §4 for the wire check |
| `O12-03` 1 s `TargetDataLine` cold open | kept — pre-warmed at engine construction |
| `O12-04` fixed 120 ms jitter | kept — ring buffer + learned start gate (ADR-341) |
| `O12-05` `AudioTrack` mode | kept — `LOW_LATENCY` on API 26+, buffer deliberately not shrunk |
| `O12-06` PCM16 airtime | **BLOCKED** — wire change + 2.4 GHz measurement |
| `O03-18` dock `BoxWithConstraints` | kept — per-slot constant, identical 48 dp at 360 dp |
| `O28-03` call telemetry | kept — `CALL_DIAG` gains `jb`/`plc`/`avDrift`; **no probe registered**, see §5 |
| `O11-01`, `O11-03` | **BLOCKED** — need a live camera / two phones; `O11-03` also needs an ADR because it touches the `NullVideoDecoder` protection |

## 2. `O11-02`: the camera fix, and what it does not yet do

The fork's jvm `LocalVideoStreamTrack` now stops the `VideoDeviceSource` in `onSetEnabled(false)` and starts it again in
`onSetEnabled(true)`, mirroring what `androidMain` already did; `switchCamera` no longer restarts a disabled source. That is
the right shape: before, `native.isEnabled = false` only quietened the RTP sender while the sensor, the driver conversion
and the OS camera handle stayed live.

Two things are recorded rather than glossed:

- **The fix is inert in the shipped app.** The desktop app resolves the fork from Maven, and the stream deliberately did not
  bump the version. Until the fork is republished (`PERF-C03`), the shipped desktop still has the old behaviour. Nobody may
  claim the camera turns off on mute until that is done.
- **`onSetEnabled(true)` after `onStop()` would start a disposed source.** The Android implementation has the same unguarded
  edge, so the fork now mirrors the platform rather than inventing a lifecycle; the state is unreachable through the app's
  own call path (a stopped track is not re-enabled). Noted, not patched blind.

## 3. `O12-02`: the PTT packet must still be the same bytes

The new `PttAudioFrame.encode(sessionBytes, …)` overload writes the identical layout (same magic, version, LE lengths, same
`require`s) and the old `encode(frame)` now delegates to it. `decodeExpected` compares the session id **as bytes** against
the one the listener already holds and rejects exactly the inputs `decodeExpectedSize` rejects (foreign session, bad
magic/version, truncation, wrong PCM length). The engine's listener caches the UTF-8 form per listening session with a
two-volatile publish that can only move forward, and the sender encodes once per burst. A packet for another session is now
rejected before a `String` exists. Verified against their allocation test, which measures both paths in one run.

## 4. `O11-05`: the wire did not change

`enforceVp8Only` strips every video `a=fmtp:` line (it has to: libwebrtc's decoder lookup fails into `NullVideoDecoder` when
a VP8 format carries parameters), so the bitrate hints `tuneVideo` formatted were deleted one call deeper. The video
section is now passed through untouched, audio tuning is unchanged, and the stream added tests pinning the emitted SDP for
every tier. Removal of dead formatting only.

## 5. `O28-03`: telemetry without inventing a namespace

The stream added the derived fields to the existing `CALL_DIAG` line and **did not register a `call.quality` probe**, because
plan §4.3 rule 2 makes a new probe namespace an owner/integrator decision. This review agrees and has added the proposed
shape to `docs/testing/PROBES.md` under **"Proposed namespaces (not registered, not emitted)"** — the proposal is recorded,
not pretended into existence.

## 6. Test-isolation check

`PttJitterDepth` is a process-global object with mutable depth, so order-dependence was the obvious risk: every test that
touches it resets it before/after (`PttPlayoutCoreTest`, `PttJitterBufferTest`, `JvmPttPlayoutTest`). Its adaptation is the
**start gate only** and is bounded 60–160 ms from a 120 ms default, so a wrong guess costs one concealed packet at the start
of the next burst rather than a permanently under-buffered session (ADR-341).

## 7. Gates

Branch tip `38a81158`, forced: `:core:calling:jvmTest` + `:core:ptt:jvmTest` + `:ui:callui:jvmTest` = **494 tests, 0
failures**, `:desktop:compileKotlinJvm` and `:app:compileDebugKotlin` green (`BUILD SUCCESSFUL in 9m 1s`, 138 tasks).
`:core:calling:testAndroidHostTest` **396/0**. The merged-tree expanded gates add `:core:calling:jvmTest` and
`:core:ptt:jvmTest` to gate 1 and `:core:calling:testAndroidHostTest` to gate 2; the numbers are in `logs/progress.md`.

## 8. What is NOT proven

Everything behavioural: the camera LED, flip-while-muted, an occluded tile's CPU, PTT intelligibility and the learned start
gate, the desktop's first press, `getPerformanceMode()` on a device, non-black desktop video, the dock on real screens, and
`CALL_DIAG`'s fields on a degrading link. All are `PERF-C01`/`PERF-C02`, and the fork republish is `PERF-C03`.
