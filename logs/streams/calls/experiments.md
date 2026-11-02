# Stream C (calls) — experiment fragment

Reserved block: **EXP-135..139**. Folded into `logs/experiments.md` by the integrator.

## EXP-135 — PTT audio frame allocation per packet (OPT-O12-02)

### Question
How much of a 20 ms PTT packet's allocation is the session-id conversion, and does caching it remove that part?

### Setup
- JVM-only test `core/ptt/src/jvmTest/.../protocol/PttAudioFrameAllocationTest`, `ThreadMXBean.getThreadAllocatedBytes`
  on the calling thread (allocator accounting, not wall clock — a loaded PC does not move these numbers).
- 24-character session id, 640-byte PCM payload (16 kHz mono, 20 ms), 1 000 packets per measurement, 200 warm-up
  iterations. **Both paths are measured in the same run and the same JVM**, so the comparison does not need a second
  baseline run.
- Baseline by construction: `PttAudioFrame.encode(PttAudioFrame(...))` and `decode()` rebuild the session id's UTF-8
  bytes / `String` once per packet; that is what `OPT-O12-02` is about. The "cached" figures come from
  `encode(sessionBytes, …)` and `decodeExpected(data, session, sessionBytes, …)`, which the engine now uses.

### Command line (forced)

```text
cmd /c "tools\flash-gradle.cmd :core:ptt:jvmTest --tests "com.transfer.flash.core.ptt.protocol.PttAudioFrameAllocationTest" --rerun-tasks --no-build-cache --no-configuration-cache --console=plain"
```

`BUILD SUCCESSFUL in 22s`, 10/10 tasks executed.

### Result

| Path | Legacy (per 1 000 packets) | Cached | Per packet | Delta |
|---|---|---|---|---|
| encode | 784 000 B | 704 464 B | 784.0 → 704.5 B | **−79.5 B (−10.1 %)** |
| decode | 760 928 B | 696 328 B | 760.9 → 696.3 B | **−64.6 B (−8.5 %)** |

`PTT_ALLOC encode packets=1000 legacy=784000B cached=704464B`
`PTT_ALLOC decode packets=1000 legacy=760928B cached=696328B`

The cached encode now allocates essentially the frame buffer itself (695 B of bytes + object header), and the cached
decode the PCM copy (640 B) plus the frame object — the floor without changing the public data model.

### What this does NOT cover
The finding's headline was "~550 allocations/sec"; only the framing half is measured here. The rest of the per-packet
churn is `OutPacket`, the inbox `Incoming`, the decoded frame and the jitter buffer's tree nodes — the last of which
`OPT-O12-04` removed by construction (it is not measurable in isolation without a heap profiler run on a device). A
full pipeline number belongs to `PERF-C01`.

### Conclusion
Caching the session id takes 144 B off every packet's round trip (~7.2 kB/s at 50 packets/s, ~430 kB/min of garbage).
Wire bytes are unchanged — the same test asserts the encoded output is byte-identical for normal and u32-wrapping
sequence numbers.

## EXP-136 — PTT jitter buffer: allocation arithmetic after the ring rewrite (OPT-O12-04)

**NOT MEASURED.** The ring removed, per packet: one boxed `Long` key (~24 B), one `TreeMap.Entry` (~40 B) and the
`firstKey()`/`lastKey()` traversal cost of a red-black tree — i.e. ~64 B × 50 packets/s ≈ 3.2 kB/s ≈ 190 kB/min. That
figure is arithmetic from the data structures, not a measurement: `PttJitterBuffer` is exercised only through
`PttPlayoutCore` in tests, and a reliable per-structure allocation number would need a heap profiler on a device.
What IS measured is behavioural: `PttJitterRingTest` (6 tests) proves ordering, concealment, duplicates, u32 wrap, late
packets and head-preserving overflow are unchanged, and `PttJitterDepthTest` (7 tests) proves the learned start gate
stays in 60–160 ms.

## EXP-137 — the call-dock layout pass (OPT-O03-18) and the occluded video surface (OPT-O11-06)

**NOT MEASURED** as wall clock or CPU (both need a profiler on a running call). What was proven instead:
- `DesktopVideoSinkBindingTest` counts the per-frame scale conversions a bound sink starts: 1 per delivered frame while
  the tile shows the remote picture, **0** for the same frames once the placeholder covers it (`OPT-O11-06`).
- `FlashCallControlDockRenderTest` renders the dock at both motion behaviours and in seven call states after the
  `BoxWithConstraints` removal; `FlashCallControlDockTest` pins the button arithmetic (48 dp at 360 dp/6 controls).

## EXP-138 — the Android `AudioTrack` performance mode (OPT-O12-05)

**NOT MEASURED.** `setPerformanceMode(PERFORMANCE_MODE_LOW_LATENCY)` is an advisory request; whether the HAL accepts it
and how much playout latency it removes can only be read on a device (`track.getPerformanceMode()` and a recording of
the received voice). The log line now carries the effective mode, and `PERF-C01` step 5 reads it. The track buffer was
deliberately left at 8 packets: halving it to earn the fast path trades an underrun for latency without a measurement.

## EXP-139 — mesh stats sampling (OPT-O11-04)

**NOT MEASURED.** `PeerConnection` and `RtcStatsReport` are `expect` classes with no test double in this module, so a
mesh `getStats()` tick cannot be driven from a unit test. The change removes 4 list allocations and 3 iterator
allocations per leg per tick by construction (5 passes → 1 pass, reused buffers); the confirmation is a live 4-party
call in `PERF-C01`.
