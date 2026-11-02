# Radio stream (R) — experiments log

Fragment for `logs/experiments.md`. Numbers are from **forced** runs (`--rerun-tasks --no-build-cache`) with the exact
command line quoted; a figure that could not be measured says NOT MEASURED rather than an estimate.

Machine for every run below: Windows 11, 8 logical cores, shared with other agents (the build lock serialises Gradle, not
the CPU), JBR 21, Gradle 9.5.0. That is why the wall-clock figures are quoted as before/after pairs from runs minutes
apart rather than as absolute performance numbers.

---

## EXP-130 — Idle read-loop wake-ups of the radio link — MEASURED (loop cadence), device wake-ups NOT MEASURED

### Date
2026-11-02

### Finding
`OPT-O26-01`

### What is measured
How many times `ByteLink.read` is invoked by `KissTncDriver`'s read loop while a radio is connected and the channel is
completely quiet, over one hour of the driver's own clock. The loop is the thing the audit called "14 400 wake-ups per
hour"; this counts its cadence deterministically instead of trusting the arithmetic in the report.

### Method
`core/radio/src/commonTest/.../driver/RadioReadLoopCadenceTest`, on Kotlin coroutine **virtual time**: a `ByteLink` that
returns 0 after the configured timeout, `advanceTimeBy(1 h)`, then count `read` calls. The same test measures both
configurations, so the two numbers come from one binary and one run.

### Result

| Configuration | `read` calls per simulated hour |
|---|---|
| `KissTncConfig(readTimeoutMs = 250)` (the old default) | 14 401 |
| `KissTncConfig()` (default after OPT-O26-01) | 61 |

240x fewer. The remaining 61 are the heartbeat described in `KissTncDriver`; they are not a correctness path (a blocked
read is woken by `close()`).

### Not measured
- **Device wake-ups / battery.** The count above is the loop's cadence, not an Android `AlarmManager`/`epoll` wake-up
  count and not a battery measurement. A phone connected to a radio, idle for an hour, with `dumpsys batterystats` before
  and after, would be the device figure: **NOT MEASURED** (no radio or phone in this session). Device check `PERF-R01`.

---

## EXP-131 — Framing / serial-buffer churn in the radio hot path — MEASURED (JVM allocation counters)

### Date
2026-11-02

### Finding
`OPT-O26-04`

### Method
`core/radio/src/jvmTest/.../wire/RadioAllocationBenchTest`, read from
`com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes()` (bytes allocated by the measuring thread inside the
JVM; native serial buffers are not visible to it). Warm-up runs before each window. No assertion depends on the
numbers, so CPU contention cannot make the test fail.

### Before / after

Two "before" runs and one "after" run, all `--rerun-tasks --no-build-cache`, all on the same machine minutes apart:

```text
cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --tests *RadioAllocationBenchTest* --tests *RadioCryptoMicrobenchTest* --rerun-tasks --no-build-cache --console=plain"   (before, twice)
cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks --no-build-cache --console=plain"                                                                          (after)
```

| Measurement | Before | After | Change |
|---|---|---|---|
| `RadioHeader.authenticatedBytes()` | 84.3 / 96.5 bytes per call | **48.0** bytes per call | **~50% fewer**, 5 arrays -> 1 |
| `KissStreamDecoder`, 1 MiB noisy line (frames complete) | 90 056 bytes per 64 KiB pass | **43 280** | 52% fewer |
| `KissStreamDecoder`, 1 MiB unsynchronised noise | 4 194 368 bytes per 64 KiB pass (one `KissDecoderStats` per byte, 64 B per input byte) | **0** | all of it gone |
| `StreamFrameDecoder`, 8 KiB message in 1-byte chunks | 34 529 592 bytes per message, 5.8 ms | **630 848** bytes, 2.4 ms | **98.2% fewer bytes**, O(n^2) -> O(n) |

The wall-clock figures are indicative only (the machine is shared); the allocation counters are the reproducible part.

### Not measured
A device profile (Android Studio allocation/GC trace on a phone receiving a real packet burst). The JVM allocation counter
is not a device measurement: **NOT MEASURED**.

---

## EXP-132 — Radio crypto wrapper throughput — MEASURED (JVM microbenchmark)

### Date
2026-11-02

### Finding
`OPT-O26-07`

### Method
`core/radio/src/jvmTest/.../wire/RadioCryptoMicrobenchTest`: fixed inputs, warm-up, then the stated number of operations
timed with `System.nanoTime()`. Single thread, no GC tuning.

Two kinds of figure below. **In-process A/B** (both variants timed in the same run, so CPU contention hits both alike) is
the reliable one. **Across-run** figures (before vs after a rebuild) are dominated by machine noise on this shared PC and
are quoted only as a range.

### Result — in-process A/B, forced run

```text
cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --tests *RadioCryptoMicrobenchTest* --rerun-tasks --no-build-cache --console=plain"
```

| Instance creation | Scan (`getInstance(name)`) | Remembered provider | Saving |
|---|---|---|---|
| `Cipher.getInstance("AES/GCM/NoPadding")` | 7 096 / 7 074 ns | **2 350 / 1 238 ns** | **~5 µs per AEAD seal or open** |
| `Signature.getInstance("SHA256withECDSA")` | 2 218 / 1 362 ns | **594 / 887 ns** | ~0.7-1.6 µs per verify |
| `KeyFactory.getInstance("EC")` | 1 158 / 1 615 ns | **654 / 300 ns** | ~0.6-1.3 µs per cache miss |
| `Mac.getInstance("HmacSHA256")` | 1 328 / 929 ns | 1 205 / 1 208 ns | **no clear change** |
| `MessageDigest.getInstance("SHA-256")` | 263 / 516 ns | 269 / 529 ns | **no change** |

So the audit's headline half - "JCA provider lookups" - is real for `Cipher` (the one on every frame), real but small for
`Signature`/`KeyFactory`, and **not** real for `Mac`/`MessageDigest` on this JVM. Reported as measured, not as the audit's
"~70% crypto initialization overhead".

The SPKI half:

| | ns/op |
|---|---|
| `KeyFactory.generatePublic(SPKI)` - what every signed frame re-did | 6 501 |
| `ecdsaVerify` with the key cached | 1 341 165 |
| `ecdsaVerify` re-parsing the key every time | 2 131 361 |

**The 6.5 µs parse is real; the 0.79 ms difference between the two verify rows is not the cache** - it is this machine's
run-to-run variance, which is larger than the saving. A P-256 verify dominates at 1.3-2.2 ms. So the cache removes a
measurable but small cost per signed frame, and the "2x verify throughput" the audit expected is **not supported**.

### Across-run wrapper figures (noise-dominated, for the record)

`aeadSeal(188 B)`: 24.2 -> 11.2 -> 21.4 -> 16.4 µs; `aeadOpen(188 B)`: 22.3 -> 11.0 -> 17.7 -> 27.4 µs;
`hmacSha256`: 5.7 -> 6.1 -> 10.5 -> 9.8 µs; `sha256(91 B)`: 1.4 -> 1.8 -> 5.3 -> 4.2 µs. Four readings of the same code
spread by 2-4x, which is why no before/after claim is made from them.

---

## EXP-133 — 1200-baud channel occupancy and goodput — ARITHMETIC FROM THE CODE, on-air NOT MEASURED

### Date
2026-11-02

### Finding
`OPT-O26-02`

### Method
`core/radio/src/jvmTest/.../wire/RadioChannelOccupancyTest` feeds a 1 000-byte and a 3 008-byte text through the **real**
`RadioSession` encoder, wraps each frame in the **real** AX.25 codec and prices the result with the **real**
`TxPacingPolicy`. Nothing is re-derived from the audit's table.

### Result

From `cmd /c "tools\flash-gradle.cmd :core:radio:jvmTest --rerun-tasks --no-build-cache --console=plain"` (forced), printed by
`RadioChannelOccupancyTest`:

| Body bytes | Segments | AX.25 bytes/frame | Airtime/frame | Channel cycle/frame | Transmitter occupied | Channel window | Segment goodput | Message goodput |
|---|---|---|---|---|---|---|---|---|
| 50 | 1 | 94 | 1 038 ms | 2 288 ms | 1.0 s | 2.3 s | 82.2 B/s | 21.9 B/s |
| 188 | 1 | 232 | 2 050 ms | 3 300 ms | 2.1 s | 3.3 s | 57.0 B/s | 57.0 B/s |
| 500 | 3 | 236 | 2 080 ms | 3 330 ms | 6.2 s | 10.0 s | 56.5 B/s | 50.1 B/s |
| 1 000 | 6 | 236 | 2 080 ms | 3 330 ms | 12.5 s | **20.0 s** | 56.5 B/s | 50.1 B/s |
| 3 008 | 16 | 236 | 2 080 ms | 3 330 ms | **33.3 s** | **53.3 s** | 56.5 B/s | 56.5 B/s |

(`channel cycle` = airtime + 500 ms mandatory gap + the mean of the 0..1500 ms anti-collision jitter. `segment goodput`
counts the 188-byte segment body; `message goodput` counts the user's bytes over the whole message window, which is why
the 50-byte row is much worse — one segment still pays one full cycle.)

The audit's headline arithmetic reproduces exactly from the shipped constants: 236-byte frames, 2 080 ms of transmitter
time each, 3.33 s of channel per segment, 56.4-56.5 B/s per segment, 20 s for a 1 KB text and 53.3 s for the 3 008-byte
maximum. Two conclusions follow, and neither is a code change this stream may make:

1. **Compression would help most messages** (a 1 KB text is 6 segments of UTF-8), but it needs a flag bit in
   `RadioWire.FLAGS` and a decoder fallback - a **wire-format change**, which this stream does not own (`docs/network/RADIO-WIRE-FORMAT.md`,
   plan §8). Left to the owner with this measurement as the case for it.
2. **Inter-segment emergency preemption** (`RadioChatEngine.priorityPumpLoop` transmits all segments of one task before
   looking at the emergency lane again, so an emergency can wait up to 53 s) is a **behaviour change on the air**, not an
   overhead reduction. It needs a two-radio test to show it does not reorder a segmented message into a reassembly timeout
   (`SegmentReassembler` is out-of-order safe, but that is a code reading, not a measurement). Recorded as a proposal for
   the device session, not implemented here.

### Not measured
On-air goodput, bit-stuffing reality, the radio's own TXDELAY/persistence behaviour and collisions: **NOT MEASURED**
(needs two radios and a receiver). Device check `PERF-R02`/`BT-00`.
