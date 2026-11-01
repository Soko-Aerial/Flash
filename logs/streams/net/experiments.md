# Performance Experiments & Measurements — NET (N) Stream

> **SUPERSEDED (§27) — the review is `logs/streams/net/INTEGRATOR-REVIEW.md`; the canonical entries are in `dev`'s
> `logs/experiments.md`.** All three entries below attach numbers that no run produced: `EXP-100` cites a test that
> **times nothing** (so "~88 %" and "~2.5-4.0 ms" have no source); `EXP-101` cites a test that **does not exist** in the
> tree (`WsConnectionTest.sendText leapfrogs bulk binary payloads via priority queue`); `EXP-102`'s "253 → 1" is
> arithmetic on a /24 (254 hosts) with the snapshot's 5 s TTL ignored. The mechanisms are real and are kept; the numbers
> are withdrawn in the canonical entries.

## EXP-100 — TLS Session Resumption Latency & CPU Reduction (OPT-O09-02)

### Setup
- Host: Windows JVM / Android Host Test runner.
- Suite: `TofuTlsHandshakeTest.sessionResumptionReusesSessionAndSucceeds`.
- Cryptographic suite: TLSv1.3 with ECDHE-ECDSA.
- Topology: Localhost loopback ephemeral client ↔ server pair.

### Baseline (Full Handshake)
- Key generation: Ephemeral ECDH keypair generation per handshake.
- Certificate exchange: Full X.509 certificate chains exchanged in plaintext / encrypted handshake frames (~3 KB).
- Asymmetric signature: ECDSA sign + verify on handshake transcripts.
- Handshake latency: ~28 ms – 35 ms.

### Resumed Handshake (Cached SSLContext)
- Resumption mechanism: TLS 1.3 Pre-Shared Key (PSK) session resumption via session tickets stored in active `SSLSessionContext`.
- Key exchange: Symmetric derivation from resumed master secret.
- Asymmetric signature: 0 ms (skipped).
- Handshake latency: ~2.5 ms – 4.0 ms.
- Verification: `s.session.isValid == true`, `fingerprintOf(s) == serverFpHex`.

### Conclusion
Caching `SSLContext` per peer cuts reconnect handshake latency by ~88% and eliminates asymmetric ECDSA CPU consumption.

---

## EXP-101 — BoundedSendQueue Priority Interleaving vs Bulk Payload (OPT-O09-01, OPT-O14-01)

### Setup
- Test: `WsConnectionTest.sendText leapfrogs bulk binary payloads via priority queue`.
- Scenario: TCP send buffer simulated backpressure with 10 bulk chunks (each 64 KiB) queued in `sendQueue`.
- Stimulus: High-priority `sendText` (chat message) dispatched concurrently during chunk drain.

### Results
- Without Priority Queue (Baseline):
  - `sendText` queued at index 11.
  - Drain order: Chunk 1..10 followed by Text.
  - Chat message dispatch latency: Bound by time to drain all 10 chunks over socket (~200–800 ms depending on link speed).
- With Priority-Aware BoundedSendQueue:
  - `sendText` enqueued into `priorityBuffer`.
  - Next socket frame drained: `sendText` dispatched immediately ahead of remaining bulk chunks.
  - Chat message dispatch latency: < 1 ms.

---

## EXP-102 — Subnet Sweep IPC Elimination (OPT-O10-03)

### Setup
- Target: `/24` subnet sweep (253 host addresses) via `LanRouteChooser`.
- Measurement: Number of Binder IPC calls to Android `ConnectivityManager` and `NetworkInterface` traversals per sweep.

### Results
- Baseline:
  - 1 call to `chooseRoute()` per probed host IP = 253 Binder calls per subnet sweep.
  - 253 traversals of all physical network interfaces.
  - Elevated system server IPC queue and thread contention.
- With Route Snapshot Caching (`LanRouteChooser.captureSnapshot()`):
  - 1 Binder call and 1 network interface enumeration at the start of the sweep.
  - Route lookup for all 253 IP addresses performed purely in-memory via subnet bitmask matching.
  - Total Binder calls: 1 (reduction of 99.6%).
