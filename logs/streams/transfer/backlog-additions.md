# Transfer Stream (T) Backlog Additions

Test prefix: PERF-T

## PERF-T01 — 10 GB Wi-Fi Direct and LAN Throughput Verification on Physical Hardware
- **Area:** Networking / Transfer Throughput
- **Goal:** Verify throughput gains from 1 MB chunks and lock-free streaming on physical Android 14/15 handsets over 5 GHz 802.11ac/ax Wi-Fi and Wi-Fi Direct.
- **Verification Criteria:** Measure average throughput (>60 MB/s target) and observe thermal throttling behavior.

## PERF-T02 — 2 GB Device Heap Profile During Concurrent Multi-Stream Transfer
- **Area:** Memory / ART Garbage Collection
- **Goal:** Profile memory allocation on a constrained 2 GB RAM Android device during an active 8-stream transfer.
- **Verification Criteria:** Assert zero `OutOfMemoryError` events, heap allocation under 32 MB, and no GC pauses exceeding 16 ms.

## PERF-T03 — Resilient AP Roam and Reconnect at 90% Transfer Progress
- **Area:** Transfer Recovery / Resume
- **Goal:** Verify seamless seekable resume on physical devices when Wi-Fi connection drops or switches access points at 90% transfer completion.
- **Verification Criteria:** Assert transfer resumes immediately without re-reading the initial 90% from flash storage.
