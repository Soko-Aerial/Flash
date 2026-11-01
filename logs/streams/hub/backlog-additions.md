# Hub Stream (H) — Test Backlog Additions

Test prefix: `PERF-H`. The integrator folds these into `docs/testing/TEST-BACKLOG.md`.

> **Threshold discipline.** The first version of this file made measured numbers the pass criterion ("peak heap below
> 32 MB", "> 35 MB/s", "drain in under 2 s") that no run had produced. Each test now passes on an **observable outcome**
> (completes, no OOM, no duplicate redelivery, no dropped socket), with the number recorded as the result. A figure that is
> a genuine requirement (a heap ceiling the device enforces) is named as such.

## PERF-H01 — 20 000-member directory sync on a low-memory Android phone
- **Area:** Enterprise directory sync / memory footprint
- **Requires:** an entry-level Android device (2 GB RAM, small per-app heap) enrolled on a hub with a 20 000-member
  directory, plus the hub seeded with that fixture.
- **Steps:**
  1. Fresh install, enrol, let the directory sync run to completion through the UI.
  2. Watch `adb logcat` for `OutOfMemoryError`, `GC for` storm lines and ANR; `adb shell dumpsys meminfo com.transfer.flash`
     at the end of the sync and during it.
  3. Search a member by name afterwards to prove the directory is usable.
- **Pass:** the sync completes on the device without `OutOfMemoryError` or an ANR, the profile shows the full member count,
  and search finds a member. Record peak heap, duration and the longest GC pause — the earlier "below 32 MB / 100 ms" were
  written before any device ran it.
- **Source:** ADR-295, ADR-297, EXP-112

## PERF-H02 — WAN hub file upload and download throughput
- **Area:** Sealed file transfer / WAN throughput
- **Requires:** a hub on the internet, an Android client on 4G/5G (50–80 ms RTT), and a 500 MB file.
- **Steps:**
  1. Send the file through the hub with the relay connected; let it upload and download to a second device.
  2. Record elapsed time, throughput, and the `hubfile.*` probes; watch for retries or stalls.
  3. Repeat with the transfer paused and resumed mid-file.
- **Pass:** the file arrives with a matching hash after upload, after a clean download, and after a pause/resume; throughput
  is recorded (no target is asserted — the "> 35 MB/s" was unmeasured). The three-in-flight pipeline must not deadlock,
  stall for longer than one RTT window, or drop the relay.
- **Source:** ADR-291, EXP-111

## PERF-H03 — Relay scale soak (concurrent WebSocket sessions)
- **Area:** Server scalability / memory
- **Requires:** a Linux host with the production profile (`-Xmx2g`, `-XX:+UseG1GC`, `-XX:+ExitOnOutOfMemoryError`) and the
  load generator.
- **Steps:**
  1. Ramp to the target session count (start at 1 000, then 5 000 — the documented design target).
  2. Hold for one hour while relaying a steady message rate.
  3. Record resident heap, GC pauses, socket drops and relay latency at the tail.
- **Pass:** no `OutOfMemoryError`, no restart, no mass socket drop over the hour, and every session's messages are delivered.
  Record the heap per connection so `ADR-299`'s estimate can be replaced by a measurement.
- **Source:** ADR-299

## PERF-H04 — Mailbox drain after a long offline period
- **Area:** Mailbox relay
- **Requires:** two devices and a hub; one device offline long enough for its mailbox to fill.
- **Steps:**
  1. Queue ~1 000 messages for the offline device.
  2. Reconnect it over flaky cellular (packet loss if the harness allows it).
  3. Watch the drain: `adb logcat | grep -i "mailbox\|recv_ack"`, and the hub's audit lines.
- **Pass:** every message arrives exactly once, a reconnect straight afterwards redelivers nothing, and the drain completes.
  Time it and record the frame count — batching is not yet used by the client (ADR-296), so expect one ack per message today.
- **Source:** ADR-296, EXP-114

## PERF-H05 — Chunk writer lock hold time (needs a harness first)
- **Area:** Server / SQLite write concurrency
- **Requires:** a timed harness in `:server:test` — **not written yet**.
- **Steps:** time `db.withTransaction` for 1 000 sequential 1 MiB `putChunk` calls, then run a two-thread contention case
  (one small mailbox write against a continuous upload) and record the mailbox write's latency distribution.
- **Pass:** the mailbox write is not blocked for the duration of a chunk's disk write on the staged build. Report the hold
  time for both builds; no percentage is assumed.
- **Source:** ADR-290, EXP-110

## PERF-H06 — Group fan-out encode cost
- **Area:** Relay fan-out / CPU
- **Requires:** `:server:test` with 500 fake sessions and Flight Recorder.
- **Steps:** time `RelayFrameRouter.handleGroupSend` and sample allocations with `jdk.ObjectAllocationSample`, once with
  the shared encoded frame and once with per-recipient encoding.
- **Pass:** one encode per fan-out is observed (allocation sample shows a single serialisation), and every recipient still
  receives the frame. Report both timings.
- **Source:** ADR-294, EXP-113

## PERF-H07 — Staged chunk write crash consistency
- **Area:** Server / durability
- **Requires:** a hub on a host where the process can be killed, plus a client uploading a multi-chunk file.
- **Steps:**
  1. Kill the hub process (`kill -9`) mid-upload, several times at different chunk boundaries.
  2. Restart it and resume the upload.
  3. Then complete a file and verify a download byte-for-byte.
- **Pass:** after every kill the hub starts, the file either has its chunk row and a readable chunk file or neither, and no
  row exists whose chunk file is missing (check `GET /api/v1/files/{id}` and a download attempt for every received index). No
  `chunk-*.part` file is left behind after a resumed upload completes.
- **Source:** ADR-290 (integrator repair), ADR-292
