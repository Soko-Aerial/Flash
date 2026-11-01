# Hub Stream (H) Backlog Additions

Test prefix: PERF-H

## PERF-H01 — 20,000-Member Directory Enrollment on 2 GB Android Phone
- **Area:** Enterprise Directory Sync / Memory Footprint
- **Goal:** Verify that a full 20,000-member initial directory sync completes on an entry-level Android device (2 GB RAM, 128 MB heap ceiling) without triggering `OutOfMemoryError` or GC pauses exceeding 100 ms.
- **Verification Criteria:** Peak process heap remains below 32 MB throughout the multi-page sync pass; database commit succeeds with 100% member records verified and searchable.

## PERF-H02 — High-Latency WAN Hub File Upload and Download Throughput
- **Area:** Sealed File Transfer / WAN Throughput
- **Goal:** Benchmark pipelined chunk transfer of a 500 MB sealed file between Android client and remote Hub server over real WAN connection (50–80 ms RTT, 100+ Mbps link).
- **Verification Criteria:** Effective transfer throughput exceeds 35 MB/s; sliding window worker pool maintains exactly 3 in-flight chunk requests without stalling or dropping connection.

## PERF-H03 — 5,000 Concurrent WebSocket Relay Connection Scale Soak Test
- **Area:** Server Scalability / Netty Buffer Retention
- **Goal:** Establish 5,000 concurrent TLS WebSocket sessions against a standard Linux production profile (`-Xmx2g`, `-XX:+UseG1GC`) using the load generator.
- **Verification Criteria:** Server memory stabilizes under 1.8 GB resident heap; GC pause times remain below 50 ms; zero socket dropouts over 1-hour soak test.

## PERF-H04 — Mailbox Reconnect Drain Under Network Jitter
- **Area:** Mailbox Relay / Batched Acknowledgment
- **Goal:** Queue 1,000 offline messages for a recipient, reconnect client over a flaky 3G/4G cellular connection with 5% packet loss, and observe drain completion.
- **Verification Criteria:** Batched `FLASH_RECV_ACK` frames drain all 1,000 messages in under 2 seconds; zero duplicate message redeliveries on subsequent reconnect.
