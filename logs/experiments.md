# Experiments Log

## EXP-002 — Bug 6 background-liveness differential test (Samsung 90% vs Infinix 4%)

### Date
2026-09-01

### Devices
- Phone A: Samsung SM-G986U1, battery ~90%, NOT battery-optimization-exempted
- Phone B: Infinix X6882B (Transsion), battery ~4%, Android 15/16, targetSdk 36

### Test
Both phones running the same build (Bug 6 re-fix + Bug 7, 2026-08-31 (b) changeset).
Leave app / turn screen off on each phone; observe peer-online status from the other phone
past the 45 s WS liveness window.

### Results
- **Samsung (90%):** stays ONLINE with screen off. Peer sees it online; messages arrive.
  No FGS exceptions reported.
- **Infinix (4%):** goes OFFLINE within seconds of backgrounding/screen-off.

### Conclusion
1. **The Bug 6 fix is physically verified working** on the Samsung — FGS + wake lock +
   crash-proof sticky-restart path keep the mesh alive through screen-off. The
   "background process" architecture the owner asked about is present and functioning.
2. The Infinix failure is **device-specific low-battery power policy**, not our code:
   at 4% the Transsion power manager (and/or AOSP battery-saver) aggressively kills
   background processes regardless of FGS status. Per official power-management docs,
   battery-saver states impose restrictions that supersede app standby buckets and
   FGS priority; OEM low-battery auto-kill is stronger still.
3. No universal assumption should be hard-coded from either device. The correct next
   step is a controlled re-test of the Infinix at healthy battery (>20%) with the
   battery-optimization exemption granted, before attributing anything to the OEM.

### Next experiments
1. **EXP-003 (decisive):** charge the Infinix above ~20%, grant the battery-optimization
   exemption (Settings → Background transfers ON), repeat the same screen-off test.
   - If it stays online → confirmed low-battery policy; document OEM behavior; done.
   - If it still goes offline → OEM auto-kill; needs the manual OEM exemption path
     (Settings → Battery → Flash → allow background activity) and possibly an
     in-app guidance screen.
2. Re-run the Samsung test with the exemption granted to isolate the exemption's effect.

### Status
Bug 6 fix VERIFIED on Samsung. Infinix failure attributed (pending) to low-battery
power policy — EXP-003 will decide between "low battery" vs "OEM auto-kill".

## EXP-001 — First successful 10MB WS mesh transfer (Samsung SM-G986U1 → Infinix X6882B)

### Date
2026-08-24 (build with ERROR-015 fixes + ack-drain grace)

### Setup
- Link: mobile hotspot (Infinix X6882B hosting assumed from peer name; band unknown — likely 2.4 GHz)
- Transport: single WebSocket, 2 logical StreamChannels multiplexed under one write lock
- Payload: 10 MB deterministic test stream; 160 × 64 KiB chunks; ACK every 32 verified chunks

### Results
- Send phase: ~4.9 s (13:33:24.97 → 13:33:29.89) ≈ **2.0 MB/s (~16 Mbps) wire throughput**
- End-to-end incl. final ACK + COMPLETE: ~7.3 s ≈ **1.4 MB/s confirmed**
- Outcome: `Completed`, receiver whole-file `verified=true`; a real 97 KB JPG also transferred and verified in the same session
- Sender monitor-contention warnings on the single WS write lock, waits 210–965 ms (expected: both channels serialize on one socket)
- GC: notable LOS churn (~60 MB large objects) from 64 KB frame arrays

### Interpretation
- Throughput is plausibly near a 2.4 GHz hotspot ceiling (~15–25 Mbps), NOT yet evidence of an engine bottleneck.
- "Multi-stream" currently = interleaved scheduling over ONE socket (ADR-017 revisit condition now met): real parallel streams need N sockets.

### Next experiments
1. Same transfer over 5 GHz router link (same devices).
2. Chunk-size sweep 64/128/256 KB on the faster link.
3. N-socket streams (2/4 real connections) once implemented.

### Status
BASELINE recorded. No optimization conclusions valid until (1) exists.

## Notes
Placeholder for future physical-device networking experiments (see AGENTS.md §9 format).
