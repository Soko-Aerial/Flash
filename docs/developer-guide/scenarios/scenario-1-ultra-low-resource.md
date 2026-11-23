# Scenario 1: Ultra-Low Resource & Severely Constrained Devices

> **Verified against the code.** The numbers in the blueprint reflect the real `FlashPerformanceMode.ULTRA_LOW` and
> `FlashPerformanceMode.LOW` profile values (`FlashTransferProfile`, `FlashVoiceProfile`, `FlashTransportProfile`). Two points to keep in mind:
> (1) the tier is **chosen by the host**, not through `FlashConfig`: `Flash.create` currently plans connections as `HIGH` and has no
> performance-mode option, while the Android app's `DiscoveryEngineHolder` holds a `performanceMode` it sets from `FlashPerformanceClassifier`
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
┌─────────────────────────────────────────────────────────────────────────┐
│             Ultra-Low vs Low Resource Profiles                          │
├───────────────────────┬──────────────────────────┬──────────────────────┤
│ Setting               │ ULTRA_LOW                │ LOW                  │
├───────────────────────┼──────────────────────────┼──────────────────────┤
│ Concurrency & Streams │ 1 (single-stream)        │ 1 (single-stream)    │
│ Transfer Chunk Size   │ 16 KB (adaptive max 32KB)│ 32 KB (adaptive 64KB)│
│ Buffer Queue Depths   │ feed = 2, shared = 2     │ feed = 2, shared = 4 │
│ UI Rendering          │ ultraLowUi = true        │ minimalChrome = true │
│ Media & Video Calling │ Omit :core:calling       │ Omit (or voice only) │
│ Opus Voice Frame Size │ 60ms, 12 kbps, DTX = true│ 60ms, 20 kbps, DTX   │
│ Thumbnail Extraction  │ Disabled (file icons)    │ Disabled (file icons)│
│ SQLite Cache Memory   │ PRAGMA cache_size = -512 │ PRAGMA cache_size=-1024│
│ Keepalive Interval    │ 25,000 ms                │ 15,000 ms            │
└───────────────────────┴──────────────────────────┴──────────────────────┘
```

---

## 3. Step-by-Step Implementation Guide

### Step 1: Know which tier you are in

`FlashPerformanceMode` (`ULTRA_LOW`, `LOW`, `MEDIUM`, `HIGH`) carries the profiles; `FlashPerformanceClassifier` rates a `FlashDeviceProfile` (RAM, cores, radio, API level) — selecting `ULTRA_LOW` when RAM ≤ 2048 MB and API ≤ 27 (e.g., BelFone SCP810-class devices) — and the Android app stores an `auto` / `ultra_low` / `low` / `medium` / `high` choice (`FlashPerformanceMode.Keys`). To read the numbers a tier implies:

```kotlin
import com.transfer.flash.core.common.perf.FlashPerformanceMode

val tier = FlashPerformanceMode.ULTRA_LOW
println("Is Ultra-Low: ${tier.isUltraLow}")                             // true
println("Ultra-Low UI: ${tier.ultraLowUi}")                             // true (disables saveLayer/clipping/shaders)
println("Reduce Motion: ${tier.reduceMotion}")                          // true
println("Minimal Chrome: ${tier.minimalChrome}")                        // true
println("Opus packet: ${tier.voice.ptimeMs} ms, DTX=${tier.voice.useDtx}") // 60 ms, true
println("Ping interval: ${tier.transport.pingIntervalMs} ms")           // 25000 (LOW=15000, MEDIUM=12000, HIGH=10000)
println("Streams: ${tier.transfer.streamCount}, chunk: ${tier.transfer.chunkSizeBytes}") // 1, 16384
println("Buffers: feed=${tier.transfer.feedBufferFrames}, shared=${tier.transfer.sharedBufferFrames}") // 2, 2
```

When `tier.ultraLowUi` is `true`, pass `ultraLowUi = true` (and `minimalChrome = true`) into `FlashTheme(...)` so Compose components bypass offscreen buffers (`saveLayer`), non-rectangular clipping, graphics layers, gradient shaders, and loop-based canvas drawing.

A library consumer that needs `ULTRA_LOW` or `LOW` behaviour today must apply the profile values itself where it builds the pieces (see Step 2), because the engine factories do not take a tier. A `FlashConfig` performance field would be a sensible addition; it does not exist yet.

### Step 2: Single-stream, bounded transfer queues

* `FlashTransferProfile.ULTRA_LOW` uses: `streamCount = 1`, `chunkSizeBytes = 16 KiB` (adaptive ceiling `32 KiB`), `feedBufferFrames = 2`, `sharedBufferFrames = 2`, `allowVideoThumbnails = false`, `maxImagePreviewDimension = 256`, `sqliteCacheSizeKb = 512` (512 KiB). The (internal) `MultiStreamDispatcher` is given `feedBufferFrames` and `sharedBufferFrames`, bounding in-flight frame heap to `(2 + 2) frames x 16 KiB = ~64 KiB` per transfer (and 16 KiB keeps chunk allocations out of the ART Large Object Space).
* `FlashTransferProfile.LOW` uses: `streamCount = 1`, `chunkSizeBytes = 32 KiB` (adaptive ceiling `64 KiB`), `feedBufferFrames = 2`, `sharedBufferFrames = 4` (`~192 KiB` in-flight), `allowVideoThumbnails = false`, `maxImagePreviewDimension = 256`, `sqliteCacheSizeKb = 1024` (1 MiB). (The chunk size bounds in `Chunker` are 16 KiB to 1 MiB, so both 16 KiB and 32 KiB are within range.)

### Step 3: SQLite cache and pragmas

On Android, `FlashDatabaseOpener.applyPragmas` executes **all five** of these pragmas on database open (`openEncrypted` and `openInMemory`), reading `sqliteCacheSizeKb` from the classified `FlashPerformanceMode` (`512` KiB for `ULTRA_LOW`, `1024` KiB for `LOW`, `4096` KiB for `MEDIUM`, `16384` KiB for `HIGH`):

* `PRAGMA cache_size = -<sqliteCacheSizeKb>;`
* `PRAGMA synchronous = NORMAL;`
* `PRAGMA temp_store = MEMORY;`
* `PRAGMA wal_autocheckpoint = 250;`
* `PRAGMA journal_size_limit = 4194304;` (4 MiB)

In addition, `FlashDatabaseOpener.openEncrypted` sets `PRAGMA auto_vacuum = INCREMENTAL;` on creation (followed by an initial `VACUUM;` on first open) and wires `DatabaseTrimRegistration` to execute `PRAGMA shrink_memory;` and `PRAGMA incremental_vacuum;` when `MemoryGovernor` reports `RUNNING_LOW`, `RUNNING_CRITICAL`, or `COMPLETE`. (`journal_mode = WAL` is Room's default on Android rather than an explicit pragma in `applyPragmas`.)

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
