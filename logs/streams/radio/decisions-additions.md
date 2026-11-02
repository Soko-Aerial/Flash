# Radio stream (R) — decision records

Fragment for `docs/decisions.md` (ADR-330..339 are reserved for this stream, plan §7). Format follows the file's own ADR
style.

---

## ADR-330 — The radio read loop waits on a 60 s heartbeat, not a 250 ms poll (2026-11-02)

### Status
IMPLEMENTED in this stream (`OPT-O26-01`), unit-tested. Not device-verified.

### Context
`KissTncDriver.readLoop` called `ByteLink.read(buf, readTimeoutMs)` in a `while (isActive)` loop with
`readTimeoutMs = 250`. The loop does nothing with a 0 result except spin: no probe, no state change, no retry. Its only
real job is to be cancelled, and cancellation is already delivered by `serve()`'s closer coroutine, which calls
`link.close()`; the `ByteLink` contract says close wakes a pending read with -1 or an exception. So the 250 ms timeout was
buying nothing but 4 wake-ups a second (EXP-130 measured 14 401 `read` calls per simulated idle hour).

### Decision
`KissTncConfig.readTimeoutMs` defaults to `DEFAULT_READ_HEARTBEAT_MS = 60_000` and is documented as a liveness **bound**,
not a poll interval. The loop is unchanged (a 0 result still means "nothing yet"), and the heartbeat is kept rather than
made infinite for one reason: a `ByteLink` implementation that neither delivers bytes nor honours `close()` (a wedged
RFCOMM socket, a future platform link) must not be able to hold the read loop for the rest of the process. 60 s bounds
that case and still cuts the timer wakes by 240x.

### Alternatives considered
- **Infinite timeout, as the audit proposes.** Rejected: it makes cancellation depend entirely on every platform link
  honouring `close()` from another thread. `RfcommLink.close()` does (`BluetoothSocket.close()` wakes the pump thread), but
  a 60 s bound costs 60 wakes an hour and removes the reliance. `require(readTimeoutMs > 0)` keeps 0 (infinite)
  unrepresentable.
- **Keep 250 ms and ignore it.** Rejected: it is the actual cost the finding names, and the "check for cancellation" the
  old comment gave as the reason is not what the loop does with the 0.
- **A heartbeat that also emits a probe.** Rejected: one `radio.*` line a minute per connected radio would drown the
  probe log for no diagnostic value.

### Consequences
- Hosts that pass `readTimeoutMs` explicitly (three test rigs, at 50 ms) are unaffected.
- The desktop serial link keeps `TIMEOUT_READ_SEMI_BLOCKING`, so a read still returns as soon as **one** byte arrives;
  only the give-up interval grows. `TIMEOUT_READ_BLOCKING` was deliberately not used: it waits for the *whole* buffer,
  which would delay a received frame until 512 bytes arrived.
- Device behaviour (does a text still arrive after an idle hour, does the phone stop waking 4x/s) is owed as `PERF-R01`.

---

## ADR-331 — Outbound ACK bookkeeping and the priority lanes are bounded, and the bound costs at most one retransmission (2026-11-02)

### Status
IMPLEMENTED in this stream (`OPT-O26-03`), unit-tested. Not device-verified.

### Context
`RadioChatEngine` kept `pendingAcks: HashMap<Long, PendingOutbound>` — one entry per text sent, removed only by the
peer's cryptographic ACK. On a lossy channel an ACK is often lost, so entries lived for the life of the process. The four
priority lanes and `BlockingStreamReader.chunks` were `Channel.UNLIMITED`, so a disconnected or slowly paced radio could
grow them without limit.

### Decision
1. `PendingAckTable` (new file): 256 entries, 30-minute TTL (the outbox gives up on a radio send after 30 minutes), both
   applied on insert; the oldest entry is evicted first.
2. The four priority lanes get `LANE_CAPACITY = 32` and keep using suspending `send`. Backpressure, not dropping: the
   caller (`sendText`/`sendLocationReport`) already awaits the transmit result, and a dropped ACK would be a reliability
   loss.
3. `BlockingStreamReader.chunks` gets 32 chunks (32 KiB) with a *blocking* `send` from the pump thread, so RFCOMM flow
   control pushes back on the radio instead of bytes being dropped.

Because (3) is the only place a *thread* can now park, `BlockingStreamReader.close()` closes the channel, and
`RfcommLink.close()` calls it before closing the socket: closing the socket only wakes a pump blocked in `InputStream.read`,
not one parked handing a chunk over, so without that a link closed while its consumer had stopped would leave the pump
thread waiting for ever. `BlockingStreamReaderTest` guards it ("closing the reader releases a pump parked on a full chunk
queue").

### Why bounding `pendingAcks` cannot lose a delivery
An evicted entry only means the ACK for that message is not matched when it arrives. The outbox's own retry then re-sends
**the identical frame** (ERROR-869: the same counters, so the receiver sees a duplicate) and the receiver re-ACKs it
(ERROR-870: `RadioIngest.Duplicate` is acknowledged without storing the message twice). The cost of an eviction is
therefore one retransmission and a longer "waiting for acknowledgement" line, never a lost message.

### Alternatives considered
- **Unbounded map with a sweep only** (audit's variant): still unbounded between sweeps and needs a timer.
- **Drop the oldest ACK task when the lane is full** (`trySend`): cheaper, but it silently loses an acknowledgement that
  the peer is waiting for, which is exactly the failure ERROR-870 was written to remove.
- **`Channel.UNLIMITED` with a byte budget**: more code, and the frames in one text are already bounded by the 16-segment
  message cap.

### Consequences
- Memory per engine is bounded at about 256 pending entries plus 4 x 32 queued texts (each at most 16 frames of 236 B).
- A pathological receive burst can suspend the rx collector for the ACK lane instead of growing the heap; the frame
  source (`KissTncDriver.received`) has its own 128-frame buffer and drops the oldest, unchanged.
- Device behaviour is owed as `PERF-R04`.
