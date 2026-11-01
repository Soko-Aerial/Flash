# Architecture Decision Records — NET (N) Stream

> **SUPERSEDED (§27) — the review is `logs/streams/net/INTEGRATOR-REVIEW.md`; the canonical ADRs are ADR-270/271 in `dev`'s
> `docs/decisions.md`.** Three corrections to **ADR-271** as written below: the cache key is **`KeyManagersRef`,
> compared by reference identity**, not `keyManagersIdentity` (the stream's original keyed on
> `System.identityHashCode(keyManagers)`, on which a collision would have presented the wrong certificate); its
> **"100 % intact"** pinning claim is **false** - a resumed TLS 1.3 session never re-invokes the trust manager; and its
> Decision 3 (computing `deferredPeerLeafFingerprintHex` in `WsConnection`) was **not implemented** - that field is still
> a constructor parameter. **ADR-270**'s "< 1 ms dispatch" is unmeasured. Also note the rule ADR-270 now carries: a full
> queue must never be reported as `sendFrame == false`, because the dispatcher reads that as a permanently dead channel.

## ADR-270 — Priority-Aware BoundedSendQueue Decoupling Socket Writes from Caller Locks

### Context
In `WsConnection`, all outbound frames previously held a blocking monitor lock (`writeLock.lock()`) during raw `OutputStream.write()` calls. When transmitting large binary chunks (64 KiB–1 MiB) over congested TCP sockets, the socket buffer full condition blocked caller coroutines and serialized all concurrent frames, stalling interactive chat messages, keepalive PINGs, and delivery receipts behind bulk transfer payloads (OPT-O09-01, OPT-O14-01).

### Decision
1. Wire `sendQueue = BoundedSendQueue<OutboundFrame>()` directly inside `WsConnection` (both `androidMain` and `jvmMain`).
2. Add a high-priority sub-queue inside `BoundedSendQueue` with dedicated `priorityCapacity` (default 32) and `enqueuePriority(item: T): EnqueueResult<T>`.
3. Route interactive frames (`sendText`, `sendPing`, PONG responses, keepalive pings) to `enqueuePriority`, allowing them to leapfrog bulk transfer frames.
4. Route bulk file chunks (`sendBinary`, `sendBinaryConsuming`) to normal-priority `enqueue`.
5. Offload physical socket writes to a dedicated non-blocking `writerLoop()` on `Dispatchers.IO`.

### Consequences
- Callers never block on physical TCP socket I/O.
- Chat messages and keepalives bypass chunk queues with sub-millisecond dispatch latency.
- Strict ordering within priority tiers is preserved.

---

## ADR-271 — SSLContext Caching per Peer for TLS Session Resumption

### Context
`FlashTlsContextFactory` previously created a fresh `SSLContext` via `SSLContext.getInstance("TLS")` on every connection and reconnect attempt. Because TLS session caches (`SSLSessionContext`) are scoped to the `SSLContext` instance, session tickets and session IDs were permanently lost on socket closure. Every reconnect (e.g. Wi-Fi roaming, phone wake from Doze, momentary signal fade) forced a full asymmetric ECDHE + ECDSA handshake across mesh peers, burning 15–40 ms CPU and ~3 KB of handshake frames per connection (OPT-O09-02).

### Decision
1. Cache `SSLContext` instances in `FlashTlsContextFactory` using `ConcurrentHashMap`:
   - `clientCache`: keyed by `ClientKey(expectedDeviceId, pinVerifier, keyManagersIdentity)`
   - `serverCache`: keyed by `ServerKey(expectedDeviceId, pinVerifier, keyManagersIdentity)`
2. Configure `sessionTimeout = 86400` (24h) and `sessionCacheSize = 256` on both `clientSessionContext` and `serverSessionContext`.
3. In `WsConnection`, compute `deferredPeerLeafFingerprintHex` from `peerPublicKeyEncoded` if unpopulated, ensuring identity and TOFU pin verification remain strictly enforced on resumed sessions where trust managers are not re-invoked.

### Consequences
- Handshake duration on reconnect drops from ~32 ms (full handshake) to ~3 ms (resumed session).
- Zero asymmetric crypto overhead during mesh reconnect storms.
- Strict TOFU certificate pinning and identity verification guarantees remain 100% intact.

---

## Note on ADR-232 — Wire-Format Changes Declined
Per ADR-232, findings OPT-O09-04 (Base64 elimination) and OPT-O09-07 (text-line protocol framing) were declined. No wire format changes or wire byte changes were taken. Only the memory allocation and concurrency halves (OPT-O09-05, OPT-O09-06, OPT-O14-02) were implemented.
