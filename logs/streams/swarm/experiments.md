# Experiments — SWARM (S) stream

All numbers below come from a **forced** run (`--rerun-tasks --no-build-cache`) with the command line recorded. Nothing
here is a device measurement: the phone numbers are owed as `PERF-S01`/`PERF-S02`.

Machine: this PC, 8 logical CPUs, Windows, desktop JVM 17. The machine is shared with other agents, so wall-clock
figures are order-of-magnitude evidence unless the work count also agrees.

---

## EXP-125 — Piece scheduler: one 16-slot pass at 4,096 pieces / 20 peers

**Finding:** `OPT-O08-01`. **Test:** `:core:swarm:jvmTest --tests *SwarmSchedulerScanBench*`, forced.

```text
SWARM-SCAN-BENCH pieces=4096 peers=20 slots=16 legacy_ms_per_pass=5.209 scan_ms_per_pass=0.465
                 legacy_piece_peer_tests=1308320 scan_holder_reads=4096 work_ratio=319.4 time_ratio=11.2
```

- Legacy loop (rebuilt peer views + full reference scan per slot): **5.209 ms**, 1,308,320 piece-peer tests.
- Rarity scan (`PieceScan.beginScan` + 16 picks): **0.465 ms**, 4,096 holder reads.
- Same run, later repeat: 5.433 -> 0.350 ms (work ratio identical, time ratio 15.5, machine load varies).
- The audit's 0.2 ms target was **not met** on this JVM; the remainder is the scan build plus `SourceSelector`.
- Correctness is not traded for it: `PieceScanEquivalenceTest` compares the pick sequence with the reference over 250
  randomised topologies.

**Android: NOT MEASURED.**

---

## EXP-126 — Wait-reason holder scan (stalled receiver, 4,096 pieces Ã— 20 peers)

**Finding:** `OPT-O25-08`. **Test:** `:core:swarm:jvmTest --tests *SwarmSchedulerScanBench*`, forced.

```text
SWARM-WAIT-SCAN-BENCH pieces=4096 peers=20 legacy_us_per_call=91.94 word_us_per_call=13.10 time_ratio=7.0
                      legacy_piece_tests=81920 word_reads=1280
```

- The legacy nested loop tests every piece of every peer (81,920 bit tests) before it answers; the word-wise test reads
  1,280 words (64 per peer) — a **64x** work reduction.
- Wall clock: 91.94 us -> 13.10 us per call (7.0x on this JVM). A repeat on a loaded machine measured 4.0x, which is why
  the test asserts the work count and only the *direction* of the time.
- Under the full suite this test once failed its "4x" threshold at exactly 4.0x; the assertion was changed to the work
  count (ERROR-964).

**Android: NOT MEASURED.**

---

## EXP-127 — SHA-256 that the serve path no longer computes

**Finding:** `OPT-O08-02`. **Test:** `:core:swarm:jvmTest --tests *SwarmSchedulerScanBench*`, forced.

```text
SWARM-SERVE-HASH-BENCH sha256_ms_per_mb=3.792 per_4gb_served_to_3_peers_s=46.6
```

- One served MiB cost **3.792 ms** of SHA-256 on this CPU.
- Serving a 4 GB file to three members (12 GiB) therefore removed **46.6 s** of CPU from the origin — the audit's
  estimate was 45-68 s, and this is the measured end of that range on this machine.
- Integrity is unchanged on the receive path (per-piece hash before write, whole-file hash at finalize) and the sender
  still refuses a source whose size no longer matches the announcement (`SwarmDriverServeTest`).

**Android: NOT MEASURED** (a phone core is slower, so the saving is larger, not smaller — but that is an inference, not a
measurement).

---

## EXP-128 — Piece I/O: heap-wrapped NIO versus RandomAccessFile (`OPT-O23-07`, REJECTED)

**Test:** `:core:swarm:jvmTest --tests *SwarmPieceIoBench*`, forced, temp file, 64 reads/writes of 1 MiB each.

```text
op=read  piece_mb=1 rounds=64 nio_us=319.3 raf_us=668.9 direct_us=310.8 ratio=0.48
op=write piece_mb=1 rounds=64 nio_us=371.7 raf_us=781.4                   ratio=0.48
```

- `RandomAccessFile.seek+read/write` — the change the finding asks for — is **2.1x slower** here.
- A reused direct buffer (the finding's other suggestion) is within noise of the current code.
- Conclusion: **rejected**, nothing changed in the piece storages. Android may differ (ART's `IoBridge` makes
  `RandomAccessFile.read(byte[])` a single copy) but an unmeasured change to the receive/serve hot path is not a fix;
  `PERF-S02` records the device recipe.

---

## Measurements that were NOT taken

| Claim | Why not | Where it is owed |
|---|---|---|
| Group completion time / fair-share under `OPT-O08-03` | needs three real phones | `PERF-S01` |
| Swarm throughput and CPU during a real transfer | needs a phone and a profile | `PERF-S01` |
| fsync latency saved by `OPT-O08-04` | needs eMMC/UFS storage | `PERF-S01` |
| Idle wake-ups saved by `OPT-O08-05` | needs `dumpsys` on a phone | `PERF-S01` |
| Thermal step-down under real heat | needs a hot phone | `PERF-S01` |
| Piece-map frame time after `OPT-O03-15` | needs a device profile | `PERF-S01` |
| Android piece I/O of `OPT-O23-07` | rejected; would need `simpleperf` | `PERF-S02` |

