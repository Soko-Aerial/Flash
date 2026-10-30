# Flash Performance Specification and Engineering Manual (Draft)
**Target:** `docs/performance.md` (required by `AGENTS.md` §5 & §23)  
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Status:** DRAFT / AUDIT ARTIFACT  

---

## 1. Purpose & Core Principles

This specification defines the foundational performance engineering contract for the Flash project across Android (`:app`, `:core:*`, `:ui:*`), Desktop (`:desktop`), and Enterprise Hub Server (`:server`).

In accordance with **`AGENTS.md` §23**:
> *"Do not optimize based on intuition alone. Measure first. Benchmark: different file sizes, different chunk sizes, one vs multiple streams, LAN vs Wi-Fi Direct, 2.4 GHz vs 5 GHz, different phones, slow vs fast storage, CPU load, thermal throttling. Always distinguish: network-limited, storage-limited, CPU-limited, TLS-limited, hashing-limited, protocol-limited. Do not assume the network is the bottleneck."*

### Core Rules of Performance Optimization
1. **Never sacrifice Core Invariants for speed:**
   - Security (Invariant 1): TLS 1.3 encryption, certificate/identity pinning, zero plaintext private keys or sensitive payloads.
   - Wire Compatibility (Invariant 2): Wire frames and TLV encodings must remain forward and backward compatible.
   - Durability & Ordering (Invariant 3): SQLite transactional integrity, monotonically increasing sequence IDs, and persistent delivery queues.
   - Streaming Large Files (Invariant 4): Never load whole files into RAM; streaming chunk I/O must hold memory flat regardless of file size.
   - Background Execution Platform Rules (Invariant 5): Foreground service compliance, Doze/App Standby awareness, zero unconstrained wake-locks.
   - Accessibility & Preferences (Invariant 6): Respect system reduce-motion settings and high-contrast accessibility flags.
2. **Every metric must specify Dimension, Evidence Rating, and Cost Model:**
   - Cost model format: $\text{Frequency} \times \text{Unit Cost} \times \text{Multiplier}$.
   - Evidence ratings: `MEASURED`, `STATIC-ARITHMETIC`, or `PLAUSIBLE`.

---

## 2. Multi-Dimensional Performance Budgets

Flash enforces strict performance budgets across all ten engineering dimensions:

| Dimension | Description | Android Target (Low-End 2GB) | Android Target (Flagship) | Desktop Target (x86_64) | Hub Server Target (1 vCPU / 1GB) |
|---|---|---|---|---|---|
| **T (Time / Latency)** | Cold start to interactive UI | $\le 600\text{ ms}$ (no splash freeze) | $\le 250\text{ ms}$ | $\le 350\text{ ms}$ | $\le 500\text{ ms}$ (daemon boot) |
| | Warm start to interactive UI | $\le 150\text{ ms}$ | $\le 60\text{ ms}$ | $\le 80\text{ ms}$ | N/A |
| | Message Send (Optimistic Echo) | $\le 16\text{ ms}$ (immediate) | $\le 8\text{ ms}$ | $\le 8\text{ ms}$ | N/A |
| | Message Wire Relay Latency | $\le 50\text{ ms}$ (LAN RTT) | $\le 15\text{ ms}$ (LAN RTT) | $\le 10\text{ ms}$ (LAN RTT) | $\le 5\text{ ms}$ (queue-to-wire) |
| | WebRTC 1:1 Call Connect | $\le 800\text{ ms}$ | $\le 350\text{ ms}$ | $\le 300\text{ ms}$ | N/A |
| **J (Jank / Frame Time)** | Frame Render Time | $\le 16.6\text{ ms}$ (60 Hz target) | $\le 8.3\text{ ms}$ (120 Hz target) | $\le 16.6\text{ ms}$ (60 Hz) | N/A |
| | Frame Drops in LazyColumn Scroll | $\le 1\%$ missed deadlines | $\le 0.1\%$ missed deadlines | $\le 0.5\%$ missed deadlines | N/A |
| | PTT / Voice Playback Recomposition | 0 Hz full-card recomposition | 0 Hz full-card recomposition | 0 Hz full-card recomposition | N/A |
| **C (CPU Usage)** | Idle in Foreground | $\le 1.0\%$ single core | $\le 0.5\%$ single core | $\le 0.5\%$ single core | $\le 0.2\%$ single core |
| | Background Idle (Keepalive) | $0.0\%$ (suspended in Doze) | $0.0\%$ (suspended in Doze) | $\le 0.1\%$ single core | $\le 0.5\%$ single core |
| | Active File Transfer (50 MB/s) | $\le 35\%$ aggregate cores | $\le 15\%$ aggregate cores | $\le 10\%$ aggregate cores | $\le 20\%$ aggregate cores |
| | WebRTC Group Call (4 peers) | $\le 45\%$ aggregate cores | $\le 20\%$ aggregate cores | $\le 15\%$ aggregate cores | N/A |
| **M (Memory / Footprint)**| Base Heap Allocation (Foreground) | $\le 64\text{ MB}$ JVM heap | $\le 120\text{ MB}$ JVM heap | $\le 90\text{ MB}$ JVM heap | $\le 128\text{ MB}$ JVM heap |
| | Peak Transfer Memory Overhead | $\le 16\text{ MB}$ above base (flat) | $\le 32\text{ MB}$ above base | $\le 48\text{ MB}$ above base | $\le 64\text{ MB}$ above base |
| | Native Memory (WebRTC / Skia) | $\le 60\text{ MB}$ native heap | $\le 120\text{ MB}$ native heap | $\le 150\text{ MB}$ private RSS | N/A |
| | Low-Memory Trim Response | Releases $\ge 50\%$ caches | Releases $\ge 50\%$ caches | Releases $\ge 40\%$ caches | Automatic GC / trim |
| **B (Battery / Wake-ups)** | Background Wake-ups | $\le 4\text{ wake-ups/hour}$ | $\le 4\text{ wake-ups/hour}$ | N/A | N/A |
| | Radio / Bluetooth Link Idle | 0 polling wake-ups (event-driven) | 0 polling wake-ups | 0 polling wake-ups | N/A |
| | Thermal Throttling Mitigation | Auto-throttle to 1 stream on SEVERE | Auto-throttle on SEVERE | N/A | N/A |
| **N (Network / Bandwidth)**| Protocol Framing Overhead | $\le 1.5\%$ above raw payload | $\le 1.5\%$ above raw payload | $\le 1.5\%$ above raw payload | $\le 1.0\%$ above raw payload |
| | Idle WebSocket Airtime | $\le 1.2\text{ KB/min}$ (ping/pong) | $\le 1.2\text{ KB/min}$ | $\le 1.2\text{ KB/min}$ | $\le 1.2\text{ KB/min/conn}$ |
| | Duplicate Chunk Waste (Swarm) | $\le 3\%$ in endgame race | $\le 2\%$ in endgame race | $\le 2\%$ in endgame race | N/A |
| **S (Storage / I/O)** | Hardware fsync Cadence | 0 per chunk (batch at file end) | 0 per chunk (batch at file end)| 0 per chunk (batch at file end)| Periodic WAL checkpoint |
| | SQLite Page Cache Ceiling | $2,048\text{ KB}$ (2 MB) | $4,096\text{ KB}$ (4 MB) | $8,192\text{ KB}$ (8 MB) | $16,384\text{ KB}$ (16 MB) |
| | Storage Pre-Allocation | `fallocate` / `setLength` required | `fallocate` / `setLength` required | `fallocate` / `setLength` required | N/A |
| **K (Capacity / Scale)** | Active Concurrent Transfers | 2 active, 6 queued | 4 active, 16 queued | 8 active, 32 queued | 64 concurrent streams |
| | Chat Message History Capacity | $100,000\text{ rows} \le 50\text{ ms}$ query | $500,000\text{ rows} \le 20\text{ ms}$ query| $1,000,000\text{ rows} \le 15\text{ ms}$| $10,000,000\text{ rows}$ mailbox |
| | Hub Server Concurrent WebSockets | N/A | N/A | N/A | $\ge 2,500\text{ conns / 1GB RAM}$ |
| **Z (Size / Distribution)**| APK / Package Size Budget | $\le 38\text{ MB}$ release APK | $\le 38\text{ MB}$ release APK | $\le 85\text{ MB}$ jpackage image | $\le 45\text{ MB}$ fat JAR |
| | R8 Dead-Code Stripping | Strips debug log formatting | Strips debug log formatting | ProGuard minimized | Stripped debug info |
| **D (Developer-Time)** | CI Benchmark Suite Runtime | $\le 3\text{ minutes}$ | $\le 3\text{ minutes}$ | $\le 2\text{ minutes}$ | $\le 2\text{ minutes}$ |
| | Local Incremental Build Time | $\le 10\text{ seconds}$ | $\le 10\text{ seconds}$ | $\le 8\text{ seconds}$ | $\le 6\text{ seconds}$ |

---

## 3. Bottleneck Classification Framework

Whenever a transfer, audio stream, database query, or UI flow experiences throughput degradation or latency spikes, performance engineers must classify the root bottleneck using the following deterministic taxonomy. **Never assume the Wi-Fi or network transport is the bottleneck without proving it.**

```
+-----------------------------------------------------------------------------------+
|                           BOTTLENECK DIAGNOSTIC DECISION TREE                     |
+-----------------------------------------------------------------------------------+
                                         |
                       [Is CPU Core saturated at 100%?]
                                      / \
                                    YES  NO
                                    /     \
    [Is thread in MessageDigest/Blake3?]   [Are write syscalls or fsync blocking?]
           /            \                               /             \
         YES             NO                           YES              NO
          |               |                            |                |
   +-------------+  [Is thread in Conscrypt/TLS?]  +---------------+  [Is socket queue full
   |  HASHING-   |        /           \            |   STORAGE-    |   or channel waiting?]
   |   LIMITED   |      YES            NO          |    LIMITED    |          /       \
   +-------------+       |              |          +---------------+        YES        NO
                   +-----------+  +-----------+                              |          |
                   |   TLS-    |  |   CPU-    |                        +-----------+ +-----------+
                   |  LIMITED  |  |  LIMITED  |                        | NETWORK-  | | PROTOCOL- |
                   +-----------+  +-----------+                        |  LIMITED  | |  LIMITED  |
                                                                       +-----------+ +-----------+
```

### 3.1 Network-Limited
- **Diagnostic Signature:**
  - Network interface queue is full; socket buffer (`SO_SNDBUF`) fills and pauses the sender thread.
  - Round-trip time (RTT) exceeds $50\text{ ms}$; Wi-Fi packet retransmission rate exceeds $5\%$.
  - TCP Congestion Window (`cwnd`) limits unacknowledged in-flight chunks.
  - Channel throughput matches Shannon capacity of link: e.g. 2.4 GHz Wi-Fi 802.11n hitting physical limit of $50\text{--}70\text{ Mbps}$ (~$6\text{--}8\text{ MB/s}$).
- **Actionable Remediation:**
  - Switch from 2.4 GHz to 5 GHz band or Wi-Fi Direct.
  - Increase multi-stream count from 2 to 4 streams to saturate high-latency links ($BDP = \text{Bandwidth} \times \text{RTT}$).
  - Enable ACK batching (`ackEvery = 32` or `64`) to minimize reverse airtime contention on half-duplex Wi-Fi.

### 3.2 Storage-Limited
- **Diagnostic Signature:**
  - Positional writes (`FileChannel.write`) or `flush()`/`fsync()` take $> 5\text{ ms}$ per call.
  - Storage I/O wait (`wa` in `top`, or `Disk Queue Length` in Windows Resource Monitor) $> 30\%$.
  - Receiver CPU is low ($< 15\%$), socket has data ready, but pipeline buffer pool is exhausted waiting for disk sink writes.
  - Sequential write speed drops on low-end eMMC 5.1 storage or fragmented flash blocks.
- **Actionable Remediation:**
  - Eliminate per-chunk `fsync()` or `flush()`. Buffer writes in memory and issue a single hardware sync upon completion.
  - Pre-allocate destination file space via `fallocate()` or `RandomAccessFile.setLength()` to prevent filesystem metadata lock contention and block fragmentation.
  - Increase write buffer size from 64 KB to 256 KB or 512 KB to coalesce write syscalls.

### 3.3 CPU-Limited
- **Diagnostic Signature:**
  - One or more CPU cores pegged at $100\%$ utilization (`top` shows $100\%$ on worker thread).
  - Profiler flame graph shows non-cryptographic Kotlin/Java overhead: JSON serialization, memory allocations, garbage collection pauses, or state diffing.
  - High GC allocation rate ($> 150\text{ MB/s}$) leading to frequent young-generation GC pauses (`GC_CONCURRENT` / ART pause).
- **Actionable Remediation:**
  - Zero allocation on hot paths: use primitive arrays and reuse pooled buffers via `ChunkBufferPool`.
  - Replace reflection-based serializers with compiled code (`kotlinx.serialization`).
  - Move UI state calculations out of Compose composition phase into draw/layout phases or background coroutines.

### 3.4 TLS-Limited
- **Diagnostic Signature:**
  - Profiler flame graph shows $> 40\%$ of CPU time inside `SSLEngineImpl.wrap()`, `SSLEngineImpl.unwrap()`, or AES-GCM cipher encryption/decryption routines.
  - ART runtime executing on hardware lacking ARMv8 Cryptographic Extensions (e.g. software AES fallback).
  - Throughput plateau at ~$25\text{--}35\text{ MB/s}$ on ARM Cortex-A53 cores despite gigabit Wi-Fi.
- **Actionable Remediation:**
  - Integrate Conscrypt (BoringSSL native assembly) on Android and Desktop JVM to utilize hardware AES-NI and ARMv8 CE instructions.
  - Select AES-128-GCM or ChaCha20-Poly1305 based on hardware acceleration availability.
  - Reuse TLS sessions and SSL session tickets; disable renegotiation.

### 3.5 Hashing-Limited
- **Diagnostic Signature:**
  - Profiler flame graph shows $> 35\%$ of CPU time inside `MessageDigest.update()` (SHA-256) or BLAKE3 hashing.
  - Sender or receiver CPU core saturated during file reads even when network is idle.
  - Double-hashing anti-pattern: hashing full file before transmission (`hashOnly`) and then hashing every chunk again during transmission.
- **Actionable Remediation:**
  - Single-pass inline hashing: compute chunk hashes and whole-file hash tree (Merkle tree / BLAKE3) concurrently during chunk streaming, eliminating the pre-pass.
  - Utilize hardware-accelerated SHA-256 instructions (via Conscrypt/BoringSSL) or SIMD-accelerated BLAKE3 native bindings.

### 3.6 Protocol-Limited
- **Diagnostic Signature:**
  - High network bandwidth available, low CPU usage, low disk wait, yet throughput is low.
  - Ping-pong lockstep: sender transmits one chunk and waits for receiver ACK before sending the next (`windowSize = 1`).
  - Head-of-line blocking on single WebSocket frame streams.
  - High wire overhead from verbose JSON or uncompressed text framing on high-frequency packets.
- **Actionable Remediation:**
  - Implement pipelining with sliding-window flow control: allow $N$ chunks in flight simultaneously.
  - Batch reverse-direction acknowledgments: emit 1 `ACK_BATCH` per 32 or 64 chunks instead of 1 ACK per chunk.
  - Use compact binary framing (`ChunkFrame` binary TLV) instead of JSON for media/data frames.

---

## 4. Reference Device Targets & Verification Profiles

Flash targets five explicit hardware archetypes. All performance optimizations and benchmark validations must evaluate against these environments:

### Target 1: Ultra-Budget Android Mobile (`Infinix X6882B`)
- **OS:** Android 14 (API 34, 32-bit/64-bit hybrid ART).
- **SoC:** MediaTek Helio G36 (8x ARM Cortex-A53: 4x 2.2 GHz, 4x 1.6 GHz). No ARMv8 Crypto Extensions.
- **RAM:** 2.0 GB LPDDR4X (Extreme low-memory pressure; `ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL` frequent).
- **Storage:** 64 GB eMMC 5.1 (Sequential write: ~85 MB/s; Random 4K write: ~12 MB/s).
- **Network:** Wi-Fi 802.11ac (1x1 SISO, max link rate 433 Mbps, realistic LAN throughput: ~150 Mbps).
- **OS Quirks:** Aggressive vendor OEM process freezer (**Transsion Hiber Engine**) freezes background processes within 30 seconds unless keepalive heartbeat runnable is active.
- **Performance Targets:**
  - Cold Start: $\le 600\text{ ms}$.
  - Peak Heap Usage: $\le 128\text{ MB}$.
  - 1:1 LAN Transfer Throughput: $\ge 12\text{ MB/s}$ (bounded by eMMC and software crypto).

### Target 2: Flagship Android Mobile (`Samsung SM-G986U1`)
- **OS:** Android 13 (API 33, 64-bit ART).
- **SoC:** Qualcomm Snapdragon 865 (1x Kryo 585 Prime @ 2.84 GHz, 3x Gold @ 2.42 GHz, 4x Silver @ 1.80 GHz). Full ARMv8 Crypto.
- **RAM:** 12.0 GB LPDDR5.
- **Storage:** 128 GB UFS 3.0 (Sequential write: ~450 MB/s; Random 4K write: ~80 MB/s).
- **Network:** Wi-Fi 6 (802.11ax 2x2 MIMO, max link rate 1200 Mbps, realistic LAN throughput: ~650 Mbps).
- **Performance Targets:**
  - Cold Start: $\le 250\text{ ms}$.
  - UI Frame Rate: Solid 120 Hz ($8.3\text{ ms/frame}$ max).
  - 1:1 LAN Transfer Throughput: $\ge 60\text{ MB/s}$ (~$500\text{ Mbps}$).

### Target 3: Modern Desktop Workstation (`PC Windows 11`)
- **OS:** Windows 11 Pro 64-bit (Build 22631+).
- **Hardware:** 8 Physical x86_64 Cores (16 threads), 20 GB DDR4/DDR5 RAM, NVMe PCIe 3.0/4.0 SSD ($> 2,000\text{ MB/s}$).
- **Runtime:** Compose Multiplatform Desktop (OpenJDK 21, Skia DirectX/Vulkan rasterizer).
- **Network:** Gigabit Ethernet / Wi-Fi 6E (Realistic LAN throughput: $\ge 900\text{ Mbps}$).
- **Performance Targets:**
  - Cold Start to Window Render: $\le 350\text{ ms}$.
  - Transfer Throughput: $\ge 95\text{ MB/s}$ (wire saturation on Gigabit LAN).
  - UI Frame Rate: Solid 60 Hz without Skia draw hitching.

### Target 4: Standard Linux Desktop (`Linux x86_64`)
- **OS:** Ubuntu 24.04 LTS / Debian 12 (Linux Kernel 6.8+).
- **Hardware:** 4–8 Cores, 16 GB RAM, SATA/NVMe SSD.
- **Runtime:** JVM 21, X11 / Wayland, D-Bus Secret Service integration.
- **Performance Targets:**
  - Cold Start: $\le 300\text{ ms}$.
  - Zero background busy-wait loops in KissTnc / Bluetooth radio drivers.

### Target 5: Small Enterprise Cloud VPS (`Hub Server`)
- **OS:** Debian 12 Minimal (Linux 6.1+).
- **Hardware:** 1 vCPU (Shared / KVM), 1.0 GB RAM, 25 GB Cloud SSD (~500 IOPS burstable).
- **Runtime:** Ktor Netty, SQLite 3.45+ with WAL mode and incremental auto-vacuum.
- **Performance Targets:**
  - Memory Footprint: $\le 256\text{ MB}$ RSS under 1,000 active WebSocket connections.
  - SQLite WAL Checkpoint: $\le 50\text{ ms}$ passive checkpoint latency.
  - Relay Message Forwarding Latency: $\le 5\text{ ms}$ (p99).

---

## 5. Telemetry & Observability Architecture

To ensure performance regressions are detected before release, Flash establishes a three-tier observability architecture:

```
[ Tier 1: Real-Time Event Probes ] -> FlashProbe.emit() -> RotatingFileLogSink (Redacted, Structured)
[ Tier 2: Continuous Metrics / SLA ] -> RelayMetrics / Prometheus -> /metrics endpoint & Diagnostic Export
[ Tier 3: Macro/Micro Benchmarking] -> CI Automated Benchmark Suite -> Performance Regression Gates
```

### 5.1 FlashProbe Logging Invariants
- Emitted **only on state changes** (session up/down, transfer start/done, error refusal). Never emitted per-chunk, per-packet, or per-tick.
- Key-value pairs must use pre-allocated static keys and sanitized values (maximum 80 characters, no PII, no secret keys, no file paths).
- Level-gating: debug probes must evaluate lazily via inline lambdas to prevent string formatting on disabled sinks.

### 5.2 Server Prometheus Metric Invariants
- Series label cardinality must remain bounded ($O(1)$). Never use user IDs, message IDs, or arbitrary filenames as labels.
- Lock-free increments using `java.util.concurrent.atomic.LongAdder`.
- Every histogram must declare explicit, logarithmically spaced buckets tuned to expected network/disk latencies.

---

## 6. Continuous Regression Prevention & CI Gates

No pull request or commit on `dev` may introduce performance degradation beyond defined tolerance limits:

1. **Startup Regression Gate:**
   - Any cold-start regression $> 5\%$ on reference Android benchmark fails CI.
2. **Transfer Throughput Gate:**
   - Synthetic loopback pipeline throughput must achieve $\ge 150\text{ MB/s}$ on standard x86_64 test runner.
3. **Database Query Gate:**
   - No query in `FlashDatabase` may execute a table scan (`SCAN TABLE`) on tables with $> 100$ rows without index coverage.
   - 100,000-message chat history query must execute in $\le 25\text{ ms}$.
4. **Memory Allocation Gate:**
   - 100 MB file transfer must not allocate more than $35\text{ MB}$ of cumulative heap objects during transfer pipeline execution.
