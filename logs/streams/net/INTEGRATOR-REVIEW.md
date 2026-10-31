# Integrator review — NET (N) stream, `agent/opt-net`

Reviewer: integration session, 2026-10-31. Scope: `dev..5ac82529` (13 commits, 15 findings), verified
against **the code and the tests**, not against the stream's reports. Every claim below was checked by
reading the branch's commits, the surrounding call sites and the tests they cite; where a test is cited
as evidence I read it and recorded what it actually asserts.

Test evidence I produced myself on the branch tree (after my fixes, see §3):

| Command | Result |
|---|---|
| `:core:network:jvmTest` | **339 tests, 0 failures** |
| `:core:network:testAndroidHostTest` | **425 tests, 0 failures** |
| `:core:discovery:jvmTest` | **106 tests, 0 failures** |
| `:core:discovery:testAndroidHostTest` | **169 tests, 0 failures** |
| `:core:hub-protocol:jvmTest` | **126 tests, 0 failures** |
| `:core:engine:compileKotlinJvm` | compiles |

## 1. Verdict table

| # | Finding | Commit | Verdict |
|---|---|---|---|
| 1 | OPT-O09-01 + OPT-O14-01 priority queue + `writerLoop` | `ab1d4334` | **KEEP, FIXED** (integrator) |
| 2 | OPT-O09-02 `SSLContext` caching / session resumption | `7adfa4b4` | **KEEP, FIXED** (integrator); ADR corrected |
| 3 | OPT-O09-03 single-pass `RelayFrameCodec` decode | `809f7755` | **KEEP** |
| 4 | OPT-O09-05 codec header + pool reuse | `6143819f` | **KEEP, FIXED** (integrator) |
| 5 | OPT-O09-06 + OPT-O14-02 suspending backpressure | `e4ec3e98` | **KEEP, FIXED** (integrator) |
| 6 | OPT-O09-08 skip uncollected UUID | `e4ec3e98` | **KEEP** |
| 7 | OPT-O10-01 `setScreenActive` | `3114dd40` | **KEEP, UNWIRED** (Stream G owes the caller) |
| 8 | OPT-O10-02 multicast-lock lease | `1585d5d9` | **KEEP, FIXED** (integrator) |
| 9 | OPT-O10-03 route snapshot cache | `019bd312` | **KEEP** (claim requantified) |
| 10 | OPT-O10-04 resolve timeout 6 s → 2 s | `3d15594e` | **KEEP, risk recorded** |
| 11 | OPT-O10-05 sweep interval 5 s → 15 s | `d3152b1e` | **REJECTED, REVERTED** |
| 12 | OPT-O10-06 debounce duplicate restarts | `4f86f1de` | **KEEP, FIXED** (integrator) |
| 13 | OPT-O10-07 adaptive broadcast suppression | `ac715136` | **REJECTED, REVERTED** |
| 14 | OPT-O14-03 partition `drainMutex` | — | **NOT IMPLEMENTED** (Stream D owns the file) |
| 15 | OPT-O09-04, OPT-O09-07 wire-format changes | — | **DECLINED** per ADR-232 — unchanged |

Ledger: 11 kept (6 of them repaired by the integrator), 2 rejected and reverted, 1 handed off, 2 stayed
declined. The rejected findings are marked as rejected in the source files themselves (§27: nothing is
deleted silently) — see `MulticastTransport`'s KDoc for the full reasoning on both.

## 2. Why the two rejections are rejections

### OPT-O10-07 — the health signal cannot see what it claims to measure

The optimisation skipped the directed-broadcast copy of an announcement while "multicast is healthy",
where healthy meant *an accepted announcement arrived within 60 s* (`lastMulticastSeenAtMs`, set in
`handleDatagram`).

That signal cannot distinguish a multicast-borne datagram from a broadcast-borne one. The receiving
sockets are bound to the wildcard address, so they accept directed broadcast on the announce port too
(`MulticastSocketFactory` documents exactly this), and Java exposes no destination address for a
datagram the way it does for an explicitly bound socket. Broadcasting therefore refreshed the very
counter that suppressed broadcasting:

```
broadcast → counter refreshed → broadcast suppressed for 60 s
          → nothing heard → peer lease (also 60 s) expires → Lost/Found flap every ~60-80 s
```

On exactly the networks the fallback exists for (multicast filtered, broadcast delivered) every device
behaves this way, and they all flap together. Making the signal trustworthy needs a wire-format path
tag ("this datagram arrived by broadcast"), which is a protocol change and therefore an owner decision
(§8/§17; ADR-232 already declined wire changes on this branch). Reverted: `announceNow()` is
unconditional again and `BROADCAST_FALLBACK_THRESHOLD_MS` is gone.

### OPT-O10-05 — it inverts a documented invariant and buys nothing measured

`DEFAULT_SWEEP_INTERVAL_MS` 5 s → 15 s in `MulticastTransport`, `CompositeDiscovery` and
`AutoConnector`. Three reasons, in the order they matter:

1. `CompositeDiscovery`'s `browseWatchdogMs` KDoc requires the watchdog to "exceed `sweepIntervalMs` so
   a transient false (state event racing a start) cannot trigger a pointless radio teardown" and calls
   the 10 s value "two sweep intervals". 15 s > 10 s inverts that condition, so the watchdog can now
   tear down a browse that is merely waiting for its next sweep. Raising the watchdog to 30 s instead
   would stretch dead-radio recovery from ~10 s to ~30 s.
2. The saving is unmeasured. These loops do real work per tick (`startSweepLoop` runs
   `recoverBindingsIfNeeded()` and `sweepLeases()`); three loops at 5 s is ~0.6 wakeups/s. No entry on
   the branch measures it, and ">2,500 uncoordinated wakeups per hour" in the stream's progress log is
   arithmetic on a per-loop count, not a measurement.
3. It costs the user: `presenceGraceMs` is `peerLeaseMs + sweepIntervalMs`, and `CompositeDiscovery`
   ages a departed peer over its own sweep interval, so a peer that walked away lingers ~10 s longer.

The finding itself ("unsynchronised polling sweepers") is real and is **not** fixed by three
independently larger periods; what it needs is one shared ticker for the three sweepers. Reverted in
all three files, with the reasoning recorded in `MulticastTransport`'s KDoc and in `AutoConnector`.

`ERROR-912` (the stream's own error entry) is the tell: raising the constant broke
`AutoConnectorTest > a dial that throws still releases the peer`, and the "fix" was to pin
`sweepIntervalMs = 5_000L` in the test — i.e. the change silently altered production behaviour and the
test was adjusted to hide it. Both pins are removed with the revert. `ERROR-912` is therefore
**OBSOLETE**, not RESOLVED.

## 3. What I changed on top of the branch (integrator fixes)

Six of the eleven kept findings needed work before they were safe to merge. All of it is in the two
`androidMain`/`jvmMain` copies where those exist, kept byte-identical apart from the duplicate note.

### 3.1 The queue's rejection was read as "this stream is dead" (O09-01 / O14-01)

`StreamChannel.sendFrame(frameBytes): Boolean`'s contract is that **`false` (or a throw) means
permanent death of that channel**: the transfer dispatcher marks the lane dead and returns its un-ACKed
chunks to the shared pool. The branch made `WsConnection.send()` return `false` when the 64-slot queue
was full, so a writer that was merely *behind* would have had a healthy stream killed and its chunks
thrown away — under load, which is exactly when the transfer is running.

The class's own KDoc says rejection is correct "when the caller has a fallback"; the WebSocket write
path is the one caller with **no** fallback. Fixed by adding a bounded waiting enqueue
(`BoundedSendQueue.enqueueBlocking(item, timeoutMs, priority)`, `QUEUE_OFFER_TIMEOUT_MS = 5_000L`) and
using it on the `RejectReason.QueueFull` branch. Waiting is also strictly *less* blocking than what it
replaced: the pre-queue write path took the write lock and wrote the frame itself, with no bound at
all. Timeouts still report `QueueFull`, and the KDoc states the bound in bytes (~4 MB at the default
64 KB chunk; ~64 MB if the chunker has adapted to its 1 MB ceiling).

Supporting fixes in the same class: `close()` now signals `notFull` so producers parked in the bounded
wait wake at once and get the truthful `RejectReason.Closed` instead of sleeping out their whole bound;
`poll()`/`takeOrNull()` signal `notFull` when they free a slot. Four new tests in
`BoundedSendQueueTest` (16 tests in the class on both targets, was 12) cover wait-then-succeed,
bound-expiry-without-eviction, prompt `Closed`, and the priority lane's own capacity.

### 3.2 Backpressure must not trip the liveness watchdog (O09-06 / O14-02)

The original code polled `trySend` and stamped `connection.touchLiveness()` every 25 ms while the
inbound channel was full — the read coroutine is the only thing that can stamp liveness, and it is
parked while it waits for the consumer. Replacing the poll with a plain suspending `send` removed that
stamping: a consumer slower than `DEFAULT_LIVENESS_TIMEOUT_MS` (25 s) would have had the connection
closed as silent while it was healthy but backpressured.

Restored as `sendKeepingLiveness`, which is cooperative rather than a poll:

```kotlin
private suspend fun <T> Channel<T>.sendKeepingLiveness(value: T) {
    while (true) {
        val sent = withTimeoutOrNull(BACKPRESSURE_TOUCH_INTERVAL_MS) { send(value) }
        if (sent != null) return
        connection.touchLiveness()
    }
}
```

It holds no thread, wakes exactly when the consumer takes an item, and stamps the watchdog once a
second while parked. Both receive paths use it; `tryEnqueueEarlyText/Binary` log their drop rather than
failing silently.

### 3.3 The `SSLContext` cache key was an identity hash code (O09-02)

`ClientKey`/`ServerKey` carried `System.identityHashCode(keyManagers)` as an `Int`. Two problems, both
real: identity hash codes collide, so an unrelated key-manager array could hit a cached context built
for a **different local identity** and present the wrong certificate to a peer; and the code is derived
from an address of an otherwise-unreferenced array, so it can be reused after collection. Replaced with
`KeyManagersRef`, whose `equals` is reference equality on the array itself (the hash code stays an
identity hash — a collision is now just a bucket collision). `clearCache()` drops both together.

### 3.4 The multicast-lock lease raced its own renewal (O10-02)

`setMulticastLock` runs on the transport's coroutine lane; the lease expiry runs on the main looper.
Both did check-then-act on the same unsynchronised `multicastLock` field, and the expiry runnable read
whatever field was current rather than the instance it was armed for. The failure modes are the bad
ones: a lost release leaves a multicast lock held indefinitely (battery), a lost acquire leaves
discovery listening with no lock. Fixed with one mutex around the field plus an expiry `Runnable` that
closes over its own lock instance and acts only while that instance is still current (Handler calls
deliberately made *outside* the mutex — they take the message-queue lock). Also wrapped `presenceTick`
in `runCatching` (rethrow `CancellationException`): that heartbeat is the only thing that renews the
lease, re-affirms presence and drains the sweep, and one throwing tick used to end it silently with
`browsing` still true.

The KDoc tellingly said the lease was the "maximum duration the lock may be held ... prevents
indefinite battery drain". It is not: the lock is re-armed every heartbeat while browsing, so the timer
fires only when nothing renews *and* nothing releases. Rewritten to say it is a last-resort leak guard,
not a power policy. No claim about "83.3% MulticastLock release" survives — nothing measures lock
duty-on time, and the ECO release it refers to is a code path, not a measurement.

### 3.5 The duplicate-restart skip dropped the only new-network notification (O10-06)

The skip was applied to both paths. `onConnectivityChanged(immediate = true)` means "a new network
became available", and the caller cancels the debounced job on the way in, so that launch is the *only*
notification of the transition; skipping it — because *we* restarted within the debounce window — left
discovery bound to the interface that had just gone away until the next interface poll. The skip now
lives inside `if (!immediate)`. The window itself was also moved from `System.currentTimeMillis()` to
`System.nanoTime()`: it is compared against a `delay`-waited debounce, and an NTP correction or date
change made the elapsed value negative, suppressing every network-change restart until the clock caught
up.

### 3.6 Codec hygiene (O09-05)

The pooling rewrite takes a buffer from `ByteArrayPool` before the opcode is validated, so an unknown
opcode leaked a 16/32 KB pooled buffer instead of returning it wiped (not a pool drain — `acquire`
allocates when empty — but a per-frame garbage drop a peer can trigger at will). Released on that path.
Also removed an import the rewrite left unused in `androidMain`. Checked the empty-payload path cannot
loop (`chunkSize = minOf(0, 4096) = 0`, loop body never entered).

## 4. Corrections owed to the branch's own documents

These are corrected in the canonical logs when the branch is folded into `dev`; the stream copies stay
as they were written, marked superseded (§27).

| Document | What was wrong | Correction |
|---|---|---|
| **ADR-271** | "Strict TOFU certificate pinning and identity verification guarantees remain 100% intact" | False as written. On a resumed TLS 1.3/1.2 session the trust manager is **not** re-invoked, so the pin is not re-evaluated. The true safety argument is that resumption is authenticated by the PSK from a handshake that *did* pass the pin, and a peer without the pinned key cannot resume. Residual gap to record: a peer whose certificate rotated while its server `SSLContext` (and ticket key) persisted could be resumed without the new leaf being checked, for up to `sessionTimeout` (24 h) or until the 256-entry session cache evicts. Paths with `deferPinWhenDeviceIdUnknown` stay uncached, so that check never bakes in a per-dial lambda. |
| **ADR-271** | Decision 3: "In `WsConnection`, compute `deferredPeerLeafFingerprintHex` from `peerPublicKeyEncoded` if unpopulated" | Not implemented. `deferredPeerLeafFingerprintHex` is still the constructor parameter (populated only by the ADR-040 deferred-dial lambda in `WsTransferClient`), and `peerPublicKeyEncoded` is used only for the HELLO `identityKey` (`JvmWsFlashNetwork.kt:887`). Also, the ADR lists no "Decision 3" position matching the numbering used elsewhere in the file (decisions 1-3 are renumbered at fold time). |
| **EXP-100** | Handshake "~28-35 ms" → "~2.5-4.0 ms", "~88% reduction", "0 ms asymmetric" | Unmeasured. The cited test, `TofuTlsHandshakeTest.sessionResumptionReusesSessionAndSucceeds`, asserts `assertEquals(clientCtx1, clientCtx2)`, `session.isValid` and fingerprint equality — it **times nothing**. Kept as: what is proven (the context is reused and the resumed session is valid and pinned to the same leaf) and what is not (any latency or CPU figure). |
| **EXP-101** | Cites "`WsConnectionTest.sendText leapfrogs bulk binary payloads via priority queue`", "< 1 ms" | The test does not exist — there is no test containing "leapfrog" anywhere in the tree. The two tests actually added to `WsConnectionTest` are `send methods return false when closed and do not throw` and `text frames are sent as priority and non-blocking`; the latter asserts only that `sendText` returns promptly, which the enqueue path makes true regardless of priority. The leapfrog property is real but is proven by `BoundedSendQueueTest > priority items are drained before normal items` plus `sendText → enqueuePriority` — by construction, in virtual time, not by a latency measurement. |
| **EXP-102** | "253 Binder calls → 1 (99.6%)" | Arithmetic on a /24 (254 host addresses), per sweep, not a measurement; and the 5 s snapshot TTL means it is one capture per TTL, not one per sweep. Kept as a count of avoided cache misses with the arithmetic shown. |
| **Progress log** | ":core:network:jvmTest ... 319 tests" | The branch tree is 339 tests on `jvmTest` and 425 on `testAndroidHostTest`, measured after my additions. |
| **Progress log / handoff** | ":core:engine:compileKotlinJvm: Passed", gate command 1 "43s" | Fine, but the gate must be re-run forced on the merged tree (§6.3); "up-to-date" is not evidence. |

## 5. Findings carried forward unwired or unowned

- **OPT-O10-01** (`setScreenActive`) is implemented but **nothing calls it** — verified: no
  screen-state receiver in `app` or `desktop`, and the interface default is a silent no-op. Stream G
  owes the wiring (wave 3). Two caveats are now in its KDoc because a caller that gets them wrong is
  worse than no caller: ECO is a *whole-mode* switch (`ConnectionModePolicy`/`ConnectionStrategy.ECO`,
  `limitsSessions = true`, so idle sessions get parked), so the caller must not fire it mid-call or
  mid-transfer; and the restore path re-applies the user's last `setMode` choice, not a fixed default.
- **OPT-O14-03** (`drainMutex` serialising all peers) is in `RealFlashChatRepository`, owned by Stream
  D. Not touched. Handoff recorded (partition the mutex by destination peer id).
- **OPT-O10-04** (resolve timeout 6 s → 2 s) is kept but is a real trade: it bounds head-of-line
  blocking behind a dead mDNS service, and it also fails a slow-but-working one sooner. Its test only
  asserts the constant, so it proves the value changed, not that the behaviour is better.
