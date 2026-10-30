# Refuted Findings and Rejected Proposals

**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Author:** Senior Performance Engineer  

---

## 1. Overview of Invariant Verification

Per project rules (Prompt §5.3, AGENTS.md §19, §21, §23), performance optimizations must never trade away correctness. An optimization that improves throughput, reduces CPU, or eliminates latency but violates any of the five core invariants—**Security, Wire Compatibility, Durability, Ordering, or Platform Execution Limits**—is a rejected proposal, not a valid optimization.

This document formally records all optimization ideas evaluated during the audit that were rejected, refuted by measurement, or discarded due to invariant violations.

---

## 2. Inventory of Rejected Proposals

### REJ-01: Bypassing TLS / Disabling Ephemeral ECDH in Local LAN Mode
- **Proposed Optimization:** Disable TLS 1.3 encryption on trusted local Wi-Fi networks to save 20–35% CPU overhead during multi-stream file transfers and eliminate handshake latency.
- **Why Considered:** Benchmark showed AES-GCM + TLS double-framing accounts for ~18% of CPU cycles during high-speed 5 GHz Wi-Fi transfers.
- **Why Rejected (Invariant Violation — Security §5.3.1):** Violates AGENTS.md §19 ("Never disable TLS or certificate/identity validation permanently just because development is easier without it") and ADR-001. Local LANs (coffee shops, shared office networks, public hotspots) are untrusted hostile environments subject to ARP spoofing and eavesdropping. All peer communications must remain encrypted and authenticated with TOFU certificates.
- **Revisit Condition:** Never for plaintext; hardware AES acceleration via Conscrypt/BoringSSL is the valid fix.

---

### REJ-02: Omission of Whole-File SHA-256 / BLAKE3 Verification on Transfer Completion
- **Proposed Optimization:** Omit whole-file hashing after the final chunk since every individual chunk is authenticated with AES-256-GCM and verified with a per-chunk SHA-256 hash.
- **Why Considered:** Whole-file hashing on a completed 10 GB transfer requires 37 seconds of sequential flash disk reading at 270 MB/s (`TransferLoopbackBenchmark.kt`).
- **Why Rejected (Invariant Violation — Wire Compatibility & Durability §5.3.3, §5.3.4):** Violates protocol specification (`docs/protocol.md` §7) and ADR-012. Chunk-level validation only guarantees chunk buffer integrity in memory; whole-file hashing protects against disk bit-rot, out-of-order sparse file assembly corruptions, and multi-session file truncation across pause/resume cycles.
- **Revisit Condition:** In-flight streaming hash calculation (computing whole-file hash incrementally as chunks arrive) eliminates the post-transfer read pass without dropping the verification.

---

### REJ-03: Disabling SQLite Write-Ahead Logging (WAL) and Setting `PRAGMA synchronous = OFF`
- **Proposed Optimization:** Set `PRAGMA synchronous = OFF` and disable WAL mode to accelerate SQLite chunk insertions and chat history operations by 10x.
- **Why Considered:** Benchmark (`bench/db_benchmark.py`) showed SQLite transactions account for up to 15% of transfer latency during rapid chunk state updates.
- **Why Rejected (Invariant Violation — Durability §5.3.4):** Violates Durability and Crash Recovery Invariants. `synchronous = OFF` leaves database headers and pages un-flushed to physical flash; an unexpected power collapse, battery pull, or Android low-memory killer (LMK) kill results in irreversible B-tree corruption.
- **Revisit Condition:** `PRAGMA synchronous = NORMAL` in combination with WAL mode satisfies the durability invariant while eliminating 95% of fsync wait time.

---

### REJ-04: Kernel Zero-Copy `sendfile(2)` and `splice(2)` for Encrypted Transfer Chunks
- **Proposed Optimization:** Use Linux/Android kernel zero-copy syscalls (`sendfile` / `splice`) to stream files from disk directly to network sockets without copying pages into JVM/ART user-space heap.
- **Why Considered:** Eliminates heap buffer allocation and CPU memory bus contention entirely.
- **Why Rejected (Architectural Incompatibility):** Kernel `sendfile` requires unencrypted plaintext on the socket. Flash's wire protocol requires every chunk to be framed in user-space (`ChunkFrame`: AES-GCM ciphertext + 16-byte auth tag + 32-byte chunk hash + 12-byte header). Plaintext cannot be streamed directly into the socket without breaking wire encryption.
- **Revisit Condition:** Could only be considered if kernel KTLS (Kernel TLS) is supported across Android and Windows, which is currently unavailable on mobile Android kernels.

---

### REJ-05: Unconstrained 8 MB or 16 MB Chunk Sizes for High-Speed LAN
- **Proposed Optimization:** Increase default chunk size from 256 KB to 8 MB or 16 MB on local Wi-Fi to reduce framing overhead to <0.01% and maximize TCP window utilization.
- **Why Considered:** Loopback benchmark demonstrated 256 KB chunking achieves 48.7 MB/s vs 33.8 MB/s at 64 KB; larger chunks theoretically reduce protocol frame count.
- **Why Rejected (Invariant Violation — Memory Bounds & Fairness §5.3.1):** Violates EXP-001 and ADR-007. On low-end 2 GB Android devices (Infinix X6882B), allocating multiple 8 MB chunks across 4 parallel streams consumes 32–64 MB of contiguous RAM, triggering aggressive Garbage Collection pauses and OOM terminations. Furthermore, 8 MB chunks over flaky 2.4 GHz Wi-Fi cause massive head-of-line blocking and catastrophic 8 MB retransmission penalties on single-packet loss.
- **Revisit Condition:** Dynamic chunk negotiation capped at 1 MB on 5 GHz Wi-Fi with >4 GB RAM, strictly keeping 256 KB on mobile/cellular/2.4 GHz.

---

### REJ-06: Real-Time Digital Audio/Voice Streaming Over 1200 Baud Packet Radio
- **Proposed Optimization:** Transmit compressed Opus voice clips over the primary Bell 202 AFSK radio link using the `RadioKind.VOICE_CLIP` wire frame.
- **Why Considered:** Provide push-to-talk capability over amateur radio links without Wi-Fi.
- **Why Rejected (Physically Refuted by Shannon Capacity & Channel Baud Rate — Domain O26):** Mathematical analysis proved 1200 baud Bell 202 AFSK provides a theoretical ceiling of 150 B/s and an effective goodput of only 56.4 B/s. A 3-second uncompressed PCM clip requires 14.3 minutes of continuous keyup; even a highly compressed 6 kbps Opus clip requires 40 seconds of airtime, violating half-duplex radio regulations and channel duty cycles.
- **Revisit Condition:** Digital voice requires secondary Bluetooth vendor SBC audio streaming (Plan R Mode 3 / ADR-210), not packet data modems.

---

### REJ-07: Removing Android Foreground Service in Favor of WorkManager for Transfers
- **Proposed Optimization:** Eliminate `ForegroundService` and persistent notifications for transfers, relying on WorkManager or JobScheduler to save battery and memory.
- **Why Considered:** Simplify Android lifecycle and eliminate user-visible ongoing transfer notifications.
- **Why Rejected (Invariant Violation — Android Platform Execution Limits §5.3.5):** Violates AGENTS.md §21 and official Android 14+ documentation. Android 14+ terminates non-foreground apps within 30 seconds of screen-off (and Transsion Hiber freezes processes immediately). Long-running transfers exceeding 100 MB cannot survive in the background without an active `dataSync` ForegroundService holding a wake lock.
- **Revisit Condition:** Never on modern Android versions.
