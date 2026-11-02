# Test backlog additions — SWARM (S) stream

To be folded into `docs/testing/TEST-BACKLOG.md` by the integrator. Every entry follows §35: setup, steps, a concrete
pass condition, the source, and `Status: TODO`. **Nothing in this stream is device-verified.**

---

## PERF-S01 — three-device group swarm: throughput, fairness, heat, idle wake-ups

**Setup.** Three Android phones on one 5 GHz access point (or two phones + the Windows desktop, which shares the engine).
A group of the three. A 2 GB file in the group chat. Install a build that contains the Wave 5 swarm changes.

**Steps.**
1. Before sending, leave the app open with no swarm content for 10 minutes and capture
   `adb shell dumpsys batterystats --charged <pkg> | grep -A4 "Wakeups"` and
   `adb shell dumpsys power | grep -i "wake lock"`.
2. Send the file from phone A. Let all three download it.
3. Repeat with the file cancelled and re-sent while phone C is offline, then bring C back.
4. While a transfer runs, log with the `PROBE` tag and keep the output.
5. Warm the phones (two back-to-back 2 GB transfers) and repeat once.

**Pass.**
- `swarm.recv.done` on every receiver reports an average speed **≥ 60 % of the 1:1 speed** measured on the same pair
  (the swarm must not be slower than a direct transfer on 3 nodes).
- **Fairness (OPT-O08-03):** `swarm.member.first` appears for **all three** members within the first 20 % of the
  transfer — before the change the third member's first line appeared only after the other two had finished.
- `swarm.sched.stall` is absent during a healthy transfer, and `swarm.piece.timeout` stays under 5 % of the piece count.
- All three `swarm.member.done` lines exist and every receiver's file has the same SHA-256 as the source.
- **Idle (OPT-O08-05):** with no swarm content the process shows **no** `SWARM` wakeups in a 10-minute idle window
  (before: 4 Hz = 2,400 ticks). The app still reacts to a new group file within 2 s of sending it.
- **Heat (OPT-O08-06):** during the warm run, once `dumpsys thermalservice` reports `THERMAL_STATUS_SEVERE`, uploads stop
  (`swarm.*` shows no new serves) and resume after the status falls back to `MODERATE`/`NONE`.
- **Resume/retention (OPT-O08-04, O08-08):** killing the app mid-transfer and reopening it resumes from the persisted
  bits (no restart from zero), and a group that has had more than 64 cancellations still exchanges Summaries (no
  `IllegalArgumentException` in the log).

**Source.** OPT-O08-01..08, O02-04, O02-05, O25-08. **Status: TODO.**

---

## PERF-S02 — Android piece I/O: heap-wrapped NIO versus `RandomAccessFile`

**Setup.** One phone, the swarm piece storage as it is (`FileChannel` + `ByteBuffer.wrap`). A 2 GB swarm file.

**Steps.**
1. `adb shell simpleperf record -p <pid> -g --duration 30 -o /data/local/tmp/swarm.perf` during a transfer that mostly
   *serves* (this device is the origin, two members downloading).
2. `adb shell simpleperf report -i /data/local/tmp/swarm.perf --sort dso,symbol | head -40`.
3. Compare against a patched build that swaps `readAt`/`writeAt` to `RandomAccessFile.seek + read/write` (the change
   `OPT-O23-07` proposed). Same file, same peers.

**Pass.** The share of samples in `libcore` I/O helpers (`IoBridge`, `NioUtils`, `memcpy`) plus `SimpleCopy`-style paths
drops and total transfer time does not get worse. **Only if** that is true, revisit `OPT-O23-07`; on the desktop JVM the
same change measured **2.1x slower**, so this must be decided by the phone, not by the audit's estimate.

**Source.** OPT-O23-07 (rejected with EXP-128). **Status: TODO — optional, only if the swarm is ever I/O bound on a phone.**

---

## PROBE-S01 — register the new probe names

Not a device test but a documentation step: `swarm.piece.timeout`, `swarm.sched.stall`, `swarm.recv.wasted` and
`swarm.send.dropped` must be added to `docs/testing/PROBES.md` (OPT-O28-04) with their fields:

```text
swarm.piece.timeout  transfer=<id> peer=<id> piece=<idx> elapsedMs=<ms> speedBps=<n>
swarm.sched.stall    transfer=<id> reason=window_full inFlightBytes=<n> budgetBytes=<n> piecesDone=<n> totalPieces=<n> peers=<n>
swarm.recv.wasted    transfer=<id> peer=<id> piece=<idx> wastedBytes=<n> receivedBytes=<n>
swarm.send.dropped   peer=<id> type=piece dropped=<n> deferred=<n>
```

**Status: TODO (integrator, canonical docs).**
