# Stream R (radio) — integrator review

**Reviewed:** `agent/opt-radio`, 9 commits off `7adcf4f5` (the pushed Wave-4 tip).
**Reviewer:** integrator session, 2026-11-02. **Status: reviewed, one defect repaired, merged after the gates (see §7).**
**Not device-verified.** Nothing here was run against a radio.

Scope of the branch: `core/radio/**` only, plus its own tests and `logs/streams/radio/**`. No build file, no wire
format constant, no `core/messaging` or `ui/chat` file.

---

## 1. What the stream delivered

| Finding | Change | Claimed evidence |
|---|---|---|
| `O26-01` | `KissTncConfig.readTimeoutMs` default 250 ms → `DEFAULT_READ_HEARTBEAT_MS` = 60 s | 14 401 → 61 `read` calls per idle hour, counted side by side in one test (`EXP-130`); device wake-ups NOT MEASURED |
| `O26-04` | one exact 25-byte AAD; `ByteQueue` makes `StreamFrameDecoder` O(n); mutable decoder counters; `JvmSerialLink` offset write | AAD 96.5 → 48.0 B/call, 1 MiB noise 4 194 368 → 0 B, 8 KiB in 1-byte chunks 34 529 592 → 630 848 B (`EXP-131`) |
| `O26-03` | `PendingAckTable` (256 entries, 30-min TTL) + four lanes bounded at 32 + bounded chunk queue | 300 unacked sends stay capped; structural test; memory NOT MEASURED |
| `O26-05` | `openRadioLink`/`openRadioAudioLink` are `suspend`; `Thread.sleep` → `delay` | behaviour NOT MEASURED (static arithmetic only) |
| `O26-07` | JCA providers resolved once; parsed sender keys cached (16, by SPKI content) | `Cipher.getInstance` 7.1 µs → 1.2–2.3 µs; `Mac`/`MessageDigest` unchanged; the audit's "2× verify throughput" **not supported** (`EXP-132`) |
| `O26-02` | **measurement only**, no code change | 236-byte frames, 2 080 ms airtime, 3.33 s/segment, 56.5 B/s, 1 KB = 20.0 s, 3 008 B = 53.3 s (`EXP-133`); on-air NOT MEASURED |
| `O26-06` | OBSOLETE — marked, not built | ADR-210/ADR-225, REJ-06 |

Their report is unusually honest about the limits: the two claims that did not hold (`Mac`/`MessageDigest` unchanged; the
audit's 2× throughput unsupported) are stated rather than buried, and every device-only figure is marked NOT MEASURED.

## 2. Correctness of the changes I checked in detail

- **`O26-01` read timeout.** Safe *because* `serve()` runs `rx`, `tx` and a `closer` in one scope and the closer calls
  `ByteLink.close()` on cancellation, which is what wakes a blocked read. A 60 s read therefore cannot block *writes*
  (separate coroutine) and cannot delay shutdown. The value is a liveness bound, not a poll interval, and the KDoc now
  says exactly that. `JvmSerialPortCatalog` still caches the timeout per port. **Kept.**
- **`O26-04` AAD.** This is wire-adjacent, so I verified the layout instead of trusting "same bytes": `RadioHeader.encode`
  writes `[0]=MAGIC, [1]=VERSION|kind, [2]=flags, [3]=ttl, tag at 4..7 (4 bytes), counter at 8..11`. The new
  `authenticatedBytes()` writes label + MAGIC + VERSION|kind + flags + `tag` (4) + counter (4) = 25 bytes at exactly the
  same offsets. **Byte-identical; kept.**
- **`O26-04` decoder.** `ByteQueue` is bounded in practice by `MAX_PAYLOAD` (the frame length check runs before waiting for
  more bytes) and compacts only when consumed. `StreamFrameDecoder.failed` still clears and ignores. **Kept.**
- **`O26-03` chunk queue.** Bounded to 32 with a *blocking* hand-over (`runBlocking { chunks.send(...) }` in the pump
  thread) — no byte is dropped, which matters because a dropped byte corrupts a frame. `read()` correctly returns -1 on a
  closed channel (`receiveCatching` → null chunk → -1). The stream found and fixed the real hazard here themselves: a pump
  parked on the full queue is not woken by closing the *socket*, so `BlockingStreamReader.close()` exists and
  `RfcommLink.close()` calls it before `socket.close()`. **Verified at the caller; kept.**
- **`O26-03` ACK table.** Eviction is safe by the argument they give and I re-checked it against `ERROR-870`: an evicted
  entry means a later ACK is unmatched, the outbox retransmits identical counters, the receiver reports `Duplicate`,
  re-ACKs, and stores nothing twice. `expire()` relies on insertion order being age order, which `put()` maintains by
  remove-then-insert. **Kept.**
- **`O26-07` crypto.** Only *providers* are remembered; every operation still builds a fresh `Cipher`/`Mac`/`Signature`
  with its own key, so no key material outlives a call — this is the K-stream precedent, correctly followed. The parsed
  public-key cache is `synchronized`, bounded to 16, keyed by SPKI *content* with a private copy, so a caller cannot
  present bytes that do not match the cached key. **Kept.**
- **`O26-05` suspend probing.** `openRadioLink` still contains a blocking `BluetoothSocket.connect()`, so making it
  `suspend` does not make it non-blocking; both call sites are on `Dispatchers.IO`. The win is that the 250 ms settle no
  longer holds an IO thread. **Kept** — with the KDoc saying plainly that it is still blocking inside.

## 3. The one thing I tested and then reverted: dropping ACKs instead of pausing reception

I initially treated this as an unreported defect and repaired it. On closer analysis the stream was right and I was
wrong, so the repair was reverted; the reasoning is recorded here because it is the kind of decision a later AI would
otherwise re-open.

**The mechanism.** `queueAckFrame` runs inside `rxCollectorJob` (`driver.received.collect { handleInboundFrame(it) }`), so
when the ACK lane is full the suspending `laneAck.send` pauses the receive collector. The lane comment's justification —
"the producer is already a suspending send path that waits for the transmit result" — is true of `laneText`,
`laneEmergency` and `laneLocation`, but **not** of `laneAck`. My repair made the ACK lane `trySend`, counted the drops and
emitted `radio.tx.ack_dropped`.

**Why the stream's `send` is the better trade.** ADR-331 already records the consequence (a pathological burst can suspend
the RX collector) and already rejected `trySend`; the missing part was the *bound*, which is what makes it safe:

1. A full ACK lane drains in about one lane's worth of transmissions — 32 frames. Both the ACK backlog and the inbound
   frame rate scale with the same airtime, so the number of inbound frames that accumulate while it drains stays roughly
   constant (~32–40) whatever the baud rate, and that is comfortably inside `KissTncDriver.rxFlow`'s 128-frame buffer with
   `DROP_OLDEST`. Under realistic 1200-baud operation the pause is ~16 s and nothing is dropped.
2. The radio is **half-duplex**: while the pump is transmitting the ACKs, the station is not receiving. A paused receive
   path is the medium's normal state, not a new failure mode.
3. Dropping an ACK is not free. The peer's outbox retransmits the **whole message** — up to 16 segments, ~53 s of air for
   3 KB at 1200 baud (EXP-133) — to recover an acknowledgement that could have been queued for a fraction of that. That is
   the cost ERROR-870's duplicate path exists to *respond* to, not a cost worth manufacturing locally.

So the pause is bounded, matches the medium, and is cheaper than the alternative. **Kept as the stream wrote it.** The only
change is that the lane comment now says the ACK producer is the RX collector, so the next reader does not have to
rediscover it; the behaviour is untouched.

**What would change this verdict:** a link whose inbound rate vastly exceeds its outbound pacing (so the buffer, not the
lane, becomes the limit), or a station that must keep receiving while transmitting (full duplex, or a TNC with deep
buffering). Neither exists in this project today.

## 4. Findings I checked and did **not** change

- Infinite read timeout (the audit's suggestion): correctly rejected by the stream — it would make cancellation depend on
  every link honouring `close()`.
- Keyed `Cipher`/`Mac` ThreadLocals: correctly rejected (K-stream precedent). Only providers and public keys are cached.
- Dropping the defensive copies in `KissFrame`/`Ax25Frame`: correctly rejected — negligible cost at 1200 baud, real
  aliasing guarantee lost.
- `RadioLinkTester.pendingAcks` (diagnostic tool) left unbounded: bounded in practice by the burst length; fine for a tool.
- `O26-02` compression / emergency pre-emption: correctly left as measurements/proposals, because both need a wire flag or
  on-air behaviour change that belongs to the owner.

## 5. What is measured, and what is not

Measured (forced runs, command lines in the stream's logs): read-loop cadence (side-by-side in one test), allocation
figures from JVM counters, crypto microbenchmarks, channel-occupancy arithmetic.
**NOT MEASURED:** every on-air number, device wake-ups/battery, memory on device, delivery latency, and whether a real
radio still keeps its link with a 60 s read heartbeat. Those are `PERF-R01`..`PERF-R05` plus the `RADCHAT-DEV03..07`
re-runs in `logs/streams/radio/backlog-additions.md`.

## 6. Gates run on the merged shape

See §7. The suite grew 259 → 273 `jvmTest` tests, all new ones for this work; the golden-frame and anti-replay tests are
untouched and green.

## 7. Verdict

Kept: `O26-01`, `O26-04`, `O26-05`, `O26-07`, the `O26-03` table/chunk-queue work, the `O26-02` measurement and the
`O26-06` obsoletion. Rejected by the stream and confirmed correct: the four items in §4.

**Repaired (two items, opposite directions):**
1. My own change — dropping ACKs instead of pausing reception — was **reverted** after analysis; §3 explains why the
   stream's design is right. One comment-only clarification remains (the ACK lane's producer is the RX collector).
2. `BlockingStreamReader.close()` was **given a deterministic release** (ERROR-973): it now interrupts the pump thread it
   keeps a reference to, which activates the `InterruptedException` handler that already existed. The stream's own test
   caught this — `close()` alone did not release a pump parked in `runBlocking { chunks.send(...) }`, failing 3 of 3 full
   host-suite runs (and hanging the worker once) while passing alone. The product code changed; **the test did not**.
