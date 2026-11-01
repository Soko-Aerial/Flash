# Kernel Stream Backlog Additions (PERF-K*)

## Test Backlog Items

### PERF-K01 — Low-End Device Transfer UI FPS Under FlashLog.d Disabled Path
- **Finding:** `OPT-O20-01`
- **Target Device:** BelFone SCP810 (Android 8.1, 1GB RAM) or Transsion Spark Go (Android 12 Go)
- **Procedure:**
  1. Initiate high-speed 100 MB/s Wi-Fi Direct file transfer.
  2. Emit high-frequency debug logging (`FlashLog.d`) on transfer worker threads (1,000 msg/sec).
  3. Measure UI frame rate via `adb shell dumpsys gfxinfo` on `TransfersScreen`.
  4. Verify that UI jank / frame drop does not exceed 1 FPS in the disabled debug logging state (verifying 11 FPS jank elimination).
- **Status:** BACKLOG (needs physical hardware)

---

### PERF-K02 — Device-Level Org Directory Sync CPU and Thermal Benchmark (5,000 Members)
- **Finding:** `OPT-O15-01`
- **Target Device:** Pixel 8 / Samsung Galaxy A14
- **Procedure:**
  1. Trigger 5,000-member directory synchronization from Hub.
  2. Measure elapsed wall-clock sync duration and CPU energy consumption via Batterystats.
  3. Verify that total verification time remains under 20 seconds on physical ARM64 hardware with SPKI cache active.
- **Status:** BACKLOG (needs physical hardware)

---

### PERF-K03 — Diagnostic Log Gzip Export Flash Wear and Latency Test
- **Finding:** `OPT-O20-06`
- **Target Device:** Android physical device (API 28+)
- **Procedure:**
  1. Fill rotating file log to maximum retention (5 rotated files, ~2.5 MB).
  2. Invoke `RotatingFileLogSink.exportGzip`.
  3. Measure elapsed export duration and verify zero temporary uncompressed file creation in app storage.
- **Status:** BACKLOG (needs physical hardware)
