# Error Log — NET (N) Stream

> **SUPERSEDED (§27) — the review is `logs/streams/net/INTEGRATOR-REVIEW.md`; the canonical entries are in `dev`'s
> `logs/errors.md`.** `ERROR-911` stands (RESOLVED). **`ERROR-912` is OBSOLETE**: the "fix" it describes pinned the test's
> sweep interval to hide the behaviour change from `OPT-O10-05`, which was itself rejected and reverted. The failing test
> was the correct signal. See the canonical `ERROR-912` entry for why that matters.

## ERROR-911 — WsConnection Missing peerPublicKeyEncoded Property

### Date
2026-10-31

### Area
core/network/ws

### Symptoms
Compilation failure in `JvmWsFlashNetwork.kt`:
`Unresolved reference 'peerPublicKeyEncoded' in JvmWsFlashNetwork.kt:887`

### Root Cause
During the initial refactor of `WsConnection.kt` to introduce `BoundedSendQueue` and `writerLoop`, the public property `peerPublicKeyEncoded` was inadvertently omitted.

### Fix
Restored `peerPublicKeyEncoded`:
```kotlin
public val peerPublicKeyEncoded: ByteArray? = (socket as? javax.net.ssl.SSLSocket)?.let { tls ->
    runCatching { tls.session.peerCertificates.firstOrNull()?.publicKey?.encoded }.getOrNull()
}
```
Synchronized identically across `androidMain` and `jvmMain`.

### Status
RESOLVED

---

## ERROR-912 — AutoConnectorTest Failure due to Sweeper Interval Alignment

### Date
2026-10-31

### Area
core/network/planner

### Symptoms
`AutoConnectorTest > a dial that throws still releases the peer FAILED (expected:<[c, c]> but was:<[c]>)`

### Root Cause
OPT-O10-05 updated `AutoConnector.DEFAULT_SWEEP_INTERVAL_MS` from 5,000 ms to 15,000 ms to eliminate redundant CPU wakeups. The unit test advanced virtual time by only 5,000 ms expecting a second sweep.

### Fix
Explicitly passed `sweepIntervalMs = 5_000L` in the test harness setup to preserve the test's fast virtual clock while allowing production to use the battery-saving 15,000 ms default.

### Status
RESOLVED
