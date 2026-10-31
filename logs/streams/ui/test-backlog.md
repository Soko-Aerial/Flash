# Stream U Test Backlog

## Device Verification Owed

### PERF-U01 — Active Transfer Message List Fling on Physical Device
- **Priority:** High
- **Target Device:** Infinix X6882B or BelFone SCP810 (Android physical hardware)
- **Scenario:**
  1. Populate conversation with 500 messages spanning attachments, quoted replies, day separators, and voice notes.
  2. Initiate an active 500 MB background file transfer generating 10 Hz progress updates.
  3. Fling the message list at maximum velocity from tail to head and back.
  4. Measure frame pacing and dropped frames via Android Studio GPU Profiler / Macrobenchmark `frameDurationCpuMs`.
- **Expected Outcome:** 90th percentile frame time < 16.6 ms (60 FPS) with zero visual hitching across date boundaries or quoted reply cards.
