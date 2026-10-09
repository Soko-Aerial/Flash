# Scenario 1: Ultra-Low Resource & Severely Constrained Devices

> **Verified against the code 2026-10-09.** The numbers in the blueprint are the real `FlashPerformanceMode.LOW` profile values
> (`FlashTransferProfile.LOW`, `FlashVoiceProfile.LOW`, `FlashTransportProfile.LOW`). Two corrections to the earlier text:
> (1) the tier is **chosen by the host**, not through `FlashConfig`: `Flash.create` currently plans connections as `HIGH` and has no
> performance-mode option, while the Android app's `DiscoveryEngineHolder` holds a `performanceMode` it sets from the device classifier
> or the user's setting; (2) the benchmark table in section 4 was **never measured** and is kept only as history.

This guide explains how to configure, tune, and strip down Flash when building for devices that are **far more resource-constrained than standard mobile phones** (e.g. devices with 128MB–512MB RAM, slow single/dual-core low-clock ARM CPUs, 2.4 GHz b/g/n radios, smartwatches, Point-of-Sale (POS) terminals, or solar/battery-powered embedded IoT boards).

---

## 1. What Happens to Standard Stacks on Severely Constrained Devices

Standard file transfer and messaging apps fail on constrained hardware in specific, catastrophic ways:
1. **Out-of-Memory (OOM) via Buffers:** Multi-megabyte file chunking, large in-flight channel queues, and pre-allocating heap byte arrays instantly trigger system process termination on a 256MB/512MB RAM budget.
2. **CPU Starvation via Media:** High-resolution image/thumbnail extraction (e.g., parsing JCodec video keyframes) and WebRTC 1080p/720p software video encoders consume 100% of all available CPU cores, starving the operating system scheduler and dropping the Wi-Fi radio connection.
3. **Radio Exhaustion via Aggressive Keepalives:** Sending high-frequency WebSocket pings (every 5–10s) prevents the radio baseband from entering low-power sleep mode, draining battery reserves in hours.

Flash addresses these issues through strict, audited tiering.

---

## 2. Ultra-Low Resource Configuration Blueprint

When deploying Flash to a constrained device, apply the following optimizations:

```text
┌───────────────────────────────────────────────────────────┐
│               Ultra-Low Resource Profile                  │
├───────────────────────┬───────────────────────────────────┤
│ Concurrency & Streams │ streamCount = 1 (single-stream)   │
│ Transfer Chunk Size   │ 32 KB base, adaptive to 64 KB     │
│ Buffer Queue Depths   │ feedBuffer = 2, sharedBuffer = 4  │
│ Media & Video Calling │ Omit :core:calling (or voice only)│
│ Opus Voice Frame Size │ 60ms with DTX enabled             │
│ Thumbnail Extraction  │ Completely disabled (file icons)  │
│ SQLite Cache Memory   │ PRAGMA cache_size = -1024 (1 MB)  │
│ Keepalive Interval    │ 15,000 ms (LOW tier ping)         │
└───────────────────────┴───────────────────────────────────┘
```

---

## 3. Step-by-Step Implementation Guide

### Step 1: Know which tier you are in

`FlashPerformanceMode` (`LOW`, `MEDIUM`, `HIGH`) carries the profiles; `FlashPerformanceClassifier` rates a `FlashDeviceProfile` (RAM, cores, radio) and the Android app stores an `auto` / `low` / `medium` / `high` choice (`FlashPerformanceMode.Keys`). To read the numbers a tier implies:

```kotlin
import com.transfer.flash.core.common.perf.FlashPerformanceMode

val tier = FlashPerformanceMode.LOW
println("Reduce Motion: ${tier.reduceMotion}")                      // true
println("Minimal Chrome: ${tier.minimalChrome}")                     // true
println("Opus packet: ${tier.voice.ptimeMs} ms, DTX=${tier.voice.useDtx}")   // 60 ms, true
println("Ping interval: ${tier.transport.pingIntervalMs} ms")        // 15000
println("Streams: ${tier.transfer.streamCount}, chunk: ${tier.transfer.chunkSizeBytes}")  // 1, 32768
```

A library consumer that needs LOW behaviour today must apply the profile values itself where it builds the pieces (see Step 2), because the engine factories do not take a tier. A `FlashConfig` performance field would be a sensible addition; it does not exist yet.

### Step 2: Single-stream, bounded transfer queues

`FlashTransferProfile.LOW` is: `streamCount = 1`, `chunkSizeBytes = 32 KiB` (adaptive ceiling 64 KiB), `feedBufferFrames = 2`, `sharedBufferFrames = 4`, `allowVideoThumbnails = false`, `maxImagePreviewDimension = 256`, `sqliteCacheSizeKb = 1024`. The (internal) `MultiStreamDispatcher` is given `feedBufferFrames` and `sharedBufferFrames` by the transfer repository, so a bounded queue is about `(2 + 4) frames x 32 KiB = ~192 KiB` of in-flight heap per transfer. (The chunk size bounds in `Chunker` are 16 KiB to 256 KiB, so 32 KiB is within range.)

### Step 3: SQLite cache

On Android, `FlashDatabaseOpener` runs `PRAGMA cache_size = -<sqliteCacheSizeKb>` from the active profile (1 MB for LOW). The other pragmas that earlier revisions of this page listed (`temp_store`, `journal_mode = WAL`, `wal_autocheckpoint`) are **not** set by the library; if you want them, set them in your own database callback and measure first.

### Step 4: Leave out calling and thumbnails

1. **Omit calling:** do not depend on `:core:calling` or `:ui:callui`. `core-engine` only sees calling as `compileOnly`, so omitting it removes the WebRTC native libraries from the app (the size saving depends on ABIs; measure your own build).
2. **No thumbnails:** respect `allowVideoThumbnails = false` and `maxImagePreviewDimension` when you render media. `FlashImageDecoder` decodes images (and, on Android, a video frame) at the size you ask for; for a file list, draw an icon from `FlashIcons` (`com.transfer.flash.ui.icons`) instead of decoding.

---

## 4. Benchmarking Ultra-Low Mode on 256MB RAM Device — NOT MEASURED (kept as history, 2026-10-09)

> **Status: UNVERIFIED.** No log, experiment entry or test in the repository produced these figures (searched `logs/`, `docs/`). Do not quote them. Real numbers belong in `logs/experiments.md` after the `MEAS-*` tests in `docs/testing/TEST-BACKLOG.md` are run on a constrained device.

| Metric | High/Default Mode | Ultra-Low Mode | Reduction |
|---|---|---|---|
| **Peak Heap RAM (500MB File Transfer)** | 48 MB | **4.2 MB** | **-91%** |
| **Active Sockets** | 4 parallel TCP streams | **1 stream** | **-75%** |
| **CPU Utilization (Transferring)** | 35% – 50% | **8% – 12%** | **-72%** |
| **Idle Radio Battery Drain** | 8% / hour | **1.2% / hour** | **-85%** |
| **App Binary Size (No WebRTC)** | ~53 MB | **~8 MB** | **-84%** |
