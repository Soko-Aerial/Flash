# Current Handoff — SWARM (S) stream

## Current branch
`agent/opt-swarm`, worktree `E:\FlashWork\opt-swarm`, base `7adcf4f5` (`dev`).

## Current phase
Wave 5, stream S. **All fourteen findings are worked.** Thirteen delivered, one rejected on measurement
(`OPT-O23-07`). Nothing is merged, nothing is pushed, nothing is device-verified.

## Last verified build
`:core:engine:jvmTest` — 175 tests, 0 failures (**`SwarmInteropTest` PASSED**, 12.1 s) on the final tree.
`:core:swarm:jvmTest` — 191 tests, 0 failures.
Both green after the last code commit (`03e57cdf`). The full stream gate is recorded in `progress.md` (§ "Gate").

## Working features (this stream)
- Piece scheduling is indexed instead of rescanned (`PieceScan`, `OPT-O08-01`), the wait classifier is word-wise
  (`OPT-O25-08`), a HAVE no longer reruns the scheduler on a full window (`OPT-O08-07`).
- Serving is cheap: no per-piece re-hash (`OPT-O08-02`), fair slots (`OPT-O08-03`), thermal step-down (`OPT-O08-06`).
- Durability writes are batched (`OPT-O08-04`), the ticker idles at 0.2 Hz (`OPT-O08-05`), tombstones are bounded and a
  known one is not re-verified (`OPT-O08-08`/`O02-05`).
- All swarm channels are bounded and no control frame is ever dropped (`OPT-O02-04`).
- The scheduler reports four probes (`OPT-O28-04`); the piece map builds its geometry once per size (`OPT-O03-15`).

## In progress
Nothing. The stream is at a clean stopping point: code committed, logs written, gate being recorded.

## Broken
Nothing known. Two things to be aware of:
1. **`OPT-O23-07` is rejected**, not delivered: `RandomAccessFile` measured 2.1x *slower* than the current heap-wrapped
   NIO on the desktop JVM (EXP-128). Do not "fix" it without a phone measurement (`PERF-S02`).
2. `SwarmSchedulerScanBench` prints timing but must not *assert* a ratio — it already failed once that way (ERROR-964).

## Last change
`OPT-O08-03`: age-weighted deficit round robin for serve slots (ADR-232 approved). A member that has been served two
pieces in its tenure can be displaced by a member refused for at least a second.

## Last test
`:core:swarm:jvmTest` 191 tests + `:core:engine:jvmTest` 175 tests (incl. `SwarmInteropTest`), both 0 failures.

## Known blockers
None for the code. The only real blocker on the *findings* is that `PERF-S01` needs three phones, which this stream does
not have.

## Recommended next task (for the integrator)
1. Review the fourteen commits (`7adcf4f5..HEAD`) **in this order**: `PieceScan` + `TransferScheduler` (the only change
   that alters scheduling decisions), then `ServePolicy`/`ServeHandler` (who wins a slot), then the driver changes
   (batching, channels, ticker, serve trust, telemetry), then `SwarmEngine` (wait reason, tombstones, thermal), then the
   one UI file.
2. Fold `logs/streams/swarm/*` into the canonical logs; register the four new probe names in `docs/testing/PROBES.md`
   (`PROBE-S01`).
3. Move `PERF-S01` (and optionally `PERF-S02`) into `docs/testing/TEST-BACKLOG.md`.
4. Run the expanded gate 1 on the merged tree. `:core:swarm:jvmTest` grows from 151 to 191 tests — do not "clean up" the
   new tests, in particular `PieceScanEquivalenceTest`, `SwarmServeFairnessTest`, `SwarmHaveDeferralTest`,
   `SwarmTelemetryTest` and the four driver tests.

## Files most relevant to the next task
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/PieceScan.kt` (new, the scan)
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/TransferScheduler.kt` (per-pass build, probes)
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ServePolicy.kt` / `ServeHandler.kt` (fairness)
- `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/driver/SwarmDriver.kt` (batching, channels, ticker,
  serve trust, telemetry, tombstone cache)
- `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/swarm/SwarmHostBinding.kt` (thermal sampling)
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwarmPieceMap.kt` (draw cache)
- `logs/streams/swarm/{progress,experiments,errors,decisions,backlog-additions}.md`
