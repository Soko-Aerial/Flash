# Domain O26 — Bluetooth Radio Link, Serial Framing & Radio Gateway
**Commit:** `4dcc7fad133a5a97b1de082ab7fdd94638bbbeae`  
**Date:** 2026-10-23  
**Auditor:** White-Box Optimization Agent  

---

## 1. Domain Header & Scope

This audit evaluates the Bluetooth radio link, serial framing, KISS/AX.25 protocol stack, packet radio pacing, and radio gateway subsystem across `:core:radio`, `:desktop` (`com.transfer.flash.desktop.radio` & `radioaudio`), and `:ui:chat` (`com.transfer.flash.ui.radio`).

The radio subsystem integrates Flash with handheld VHF/UHF transceivers (such as the Vero Telecom VR-N76 and BTECH UV-Pro) over Bluetooth Classic Serial Port Profile (SPP / RFCOMM) and hardware KISS TNC modems (1200 baud Bell 202 AFSK) for off-grid, emergency, and tactical text messaging (Plan R / ADR-101 / ADR-173 / ADR-182 / ADR-183).

The audit specifically investigates:
1. **Framing protocol & byte-stuffing efficiency:** KISS framing (`KissFrameCodec`, `KissStreamDecoder`), SLIP escape sequences (`FEND 0xC0`, `FESC 0xDB`, `TFEND 0xDC`, `TFESC 0xDD`), AX.25 v2.2 UI framing (`Ax25FrameCodec`), and framing inflation on encrypted Profile M binary payloads.
2. **Link throughput vs theoretical radio baud rate:** Physical 115,200 baud host serial link vs 1,200 baud on-air Bell 202 AFSK modem speed; packet header overhead ratio; fragmentation into small radio frames; half-duplex CSMA transmit pacing (`TxPacingPolicy`).
3. **Pump thread & stream reader CPU/wake-ups:** `DesktopRadioGatewayController`, `KissTncDriver`, `BlockingStreamReader`, `JvmSerialLink`, and `RfcommLink` reader loops; synchronous `Thread.sleep()` busy-waits; 250 ms semi-blocking read timeouts; coroutine thread pinning and timer churn.
4. **Memory allocation & buffer churn:** Radio frame allocations; byte array slicing and re-copying in chunking/reassembly (`SegmentReassembler`, `StreamFrameDecoder`); unbounded channel queues (`Channel.UNLIMITED`) and memory leak risks in outbox pending ACK tables (`pendingAcks`).
5. **Radio audio & voice frames over radio:** Feasibility and physics of digital voice over 1200 baud AFSK packet radio (`RadioKind.VOICE_CLIP`); PCM downsampling costs; packet drop behavior; half-duplex radio airtime contention; Bluetooth Hands-Free (HFP) vs secondary vendor SBC audio endpoints (`radioaudio` probe).

---

## 2. Files Inspected

### Core Radio Protocol & Codecs (`core/radio`)
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/Ax25FrameCodec.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioWire.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioSession.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/StreamFramer.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioCrypto.kt`
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt`
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt`

### Radio Drivers, Pacing & State Engine (`core/radio`)
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/KissTncDriver.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/TxPacing.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/RadioState.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/gateway/RadioGatewayManager.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngine.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatHost.kt`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatTypes.kt`

### Hardware Link & Platform Discovery (`core/radio`)
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/ByteLink.kt`
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/JvmSerialPortCatalog.kt`
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/WindowsRadioDiscovery.kt`
- `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/windows/WindowsBluetoothApi.kt`
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt`
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/BlockingStreamReader.kt`
- `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt`

### Desktop Radio Integration & Audio Probes (`desktop`)
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radio/DesktopRadioGatewayController.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radio/DesktopRadioManagers.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radio/DesktopRadioOutboxAdapter.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/RadioAudioProbe.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/WindowsAudioEndpointEnumerator.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/AudioEndpointClassifier.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/AudioModels.kt`

### Radio Gateway UI & Presentation (`ui/chat`)
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioGatewayScreen.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioGatewayComponents.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/RadioGatewayUiMapper.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/FlashRadioMath.kt`
- `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/radio/RadioGatewayController.kt`

---

## 3. Hot Paths: Radio Link, Ingest, Framing & Priority Pacing

| Path ID | Entry Point | Trigger / Cadence | Dispatcher / Thread | Operations Executed |
|---|---|---|---|---|
| **HP-RAD-01** | `KissTncDriver.readLoop()` | Continuous loop (wakes every 250 ms at idle; packet burst on RX) | `Dispatchers.IO` / Native COM read thread | Invokes `link.read(buf, 250)`; decodes raw byte stream via `KissStreamDecoder.feed`; emits KISS frames; decodes AX.25 UI headers (`Ax25FrameCodec.decode`); pushes frames to `KissTncDriver.received` SharedFlow. |
| **HP-RAD-02** | `RadioChatEngine.handleInboundFrame()` | Per-received-packet | Engine Coroutine Scope (`Dispatchers.Default` / `IO`) | Extracts AX.25 info field; executes `session.ingest()`; tests rotating tag against all paired peers ($O(N)$ HMAC/SHA-256); verifies AES-256-GCM tag (`crypto.aeadOpen`); checks anti-replay window (`ReplayWindow.check`); persists replay bitmap; routes ACKs, location beacons, or text frames; feeds `SegmentReassembler`. |
| **HP-RAD-03** | `RadioChatEngine.sendText()` | On user message send | Caller Coroutine Scope | Enforces Profile M authorization gate; checks callsign; calculates segmentation (max 16 segments x 188 B); derives AES-256-GCM counter nonces; seals frame bodies; wraps each frame in AX.25 UI headers; enqueues `PriorityTask` into `laneText`. |
| **HP-RAD-04** | `RadioChatEngine.priorityPumpLoop()` | Continuous queue worker | Coroutine Scope | Suspends on `select` across 4 priority lanes (`laneEmergency > laneText > laneAck > laneLocation`); pops `PriorityTask`; loops through all constituent AX.25 frames calling `driver.sendAx25()`. |
| **HP-RAD-05** | `KissTncDriver.txLoop()` | Per-outbound-frame | Driver Coroutine Scope | Pops frame from `queue`; evaluates `TxPacer.waitMs()` based on carrier airtime estimate + min gap (500 ms) + random jitter (0..1500 ms); calls `link.write(bytes)` over serial/Bluetooth; records `evidence.dump()`; triggers completion. |
| **HP-RAD-06** | `AndroidBluetoothCatalog.openRadioLink()` | On link connect / reconnect retry | `Dispatchers.IO` (blocking) | Executes multi-strategy connection probe: cached channel $\rightarrow$ insecure SDP $\rightarrow$ secure SDP $\rightarrow$ insecure channels 1..12 $\rightarrow$ secure ch1. Executes `Thread.sleep(250)` synchronously on every failed attempt. |

---

## 4. Checklist Results

| # | Checklist Item | Status | Finding ID / Code Fact | Evidence Rating |
|---|---|---|---|---|
| 1 | **Framing protocol & byte-stuffing efficiency:** KISS / SLIP framing, escape characters (FEND 0xC0, FESC 0xDB), framing byte inflation on binary payloads. | **Problem** | `OPT-O26-04`. KISS/SLIP byte-stuffing adds 0.78% overhead on average (uniform ciphertext) but up to 100% on pathological binary patterns. Intermediate array cloning in `KissFrameCodec`, `Ax25FrameCodec`, and `RadioWire.authenticatedBytes()` inflates heap allocations. | `STATIC-ARITHMETIC` |
| 2 | **Link throughput vs theoretical radio baud rate:** 115200 baud host vs 1200 baud modem; effective throughput; packet header overhead; fragmentation into small radio frames; pacing. | **Problem** | `OPT-O26-02`. Host serial link (115.2 kbps) is idle >99% of the time. 1200 baud AFSK air modem carries only 150 B/s raw airtime. With 57 bytes of multi-layer headers + pacing (3.33s per frame), effective goodput is **56.4 B/s (0.49% of serial baud)**. A 1 KB text occupies the channel for 20s. | `STATIC-ARITHMETIC` |
| 3 | **Pump thread & stream reader CPU/wake-ups:** DesktopRadioGatewayController, RadioSerialLink, Bluetooth/serial port reader loops, Thread.sleep() busy waits, blocking read timeouts, coroutine thread pinning. | **Problem** | `OPT-O26-01` (250 ms semi-blocking read timeout in `KissTncDriver` and `BlockingStreamReader` forces 14,400 wake-ups/hr at idle); `OPT-O26-05` (Synchronous `Thread.sleep(250)` stalls `Dispatchers.IO` threads for up to 4,000 ms during connection failure). | `STATIC-ARITHMETIC` |
| 4 | **Memory allocation & buffer churn:** Radio frame allocations, byte array copies in chunking/reassembly, outbox queue memory limits. | **Problem** | `OPT-O26-03` (`RadioChatEngine.pendingAcks` has no eviction or TTL, leaking memory on every lost ACK; all 4 priority lanes and `BlockingStreamReader.chunks` use `Channel.UNLIMITED`); `OPT-O26-04` ($O(N^2)$ buffer reallocations in `StreamFrameDecoder`). | `STATIC-ARITHMETIC` |
| 5 | **Radio audio & voice frames over radio:** Radio voice/audio compression, PCM downsampling, packet drop behavior, radio airtime contention. | **Problem** | `OPT-O26-06`. `RadioKind.VOICE_CLIP` is a dead wire constant. Digital voice over 1200 baud AFSK packet modem is mathematically impossible (a 3-second PCM clip requires 857s / 14.3 min airtime; 6 kbps Opus requires 40s airtime and exceeds the 3 KB message cap). Voice requires secondary Bluetooth SBC audio stream (HTCommander-style), not packet modem. | `STATIC-ARITHMETIC` |

---

## 5. Detailed Optimization Findings

### OPT-O26-01 — Semi-Blocking 250 ms Read Loop in KissTncDriver Generates 14,400 Idle Wake-ups/Hour and Prevents OS Power Collapse
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** B, C, T   **Runtime:** Android, Desktop
- **Component:** `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/KissTncDriver.kt:62,318-335`, `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/BlockingStreamReader.kt:37`, `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/JvmSerialPortCatalog.kt:44,57-69`
- **Bottleneck class (transfer/media only):** protocol / CPU
- **What is wasteful:**  
  In `KissTncConfig.kt:62`, `readTimeoutMs` defaults to 250 ms with the explicit rationale: `"How long a single read waits before the loop checks for cancellation"`. In `KissTncDriver.kt:320-327`, `readLoop()` runs an unconditional `while (coroutineContext.isActive)` loop calling `link.read(buf, config.readTimeoutMs)`. When no radio packets are arriving (which represents >99.9% of the time, as 1200 baud packet radio receives only intermittent bursts):
  1. On Android (`BlockingStreamReader.kt:37`), `withTimeoutOrNull(timeoutMs) { chunks.receiveCatching() } ?: return 0` schedules and cancels a timer inside the `kotlinx.coroutines` scheduler every 250 ms.
  2. On Desktop JVM (`JvmSerialLink.kt:60-63`), `port.setComPortTimeouts(TIMEOUT_READ_SEMI_BLOCKING, 250, 2000)` causes native jSerialComm OS calls on `Dispatchers.IO` to time out every 250 ms and return 0.
  3. `readLoop` receives `n == 0` and immediately executes `continue`, looping around to invoke `link.read()` again.
  
  This polling architecture produces **4 wake-ups per second = 14,400 wake-ups per hour** continuously while the radio is connected. Polling `coroutineContext.isActive` is a legacy idiom: modern coroutines are cancelled cooperatively or via socket closure. Indeed, `KissTncDriver.serve()` (lines 294–299) *already* launches a `closer` job that executes `awaitCancellation()` and invokes `link.close()`, which immediately terminates blocking native socket reads. Waking the coroutine engine 14,400 times/hr prevents the Android Application Processor (AP) from entering deep C-state sleep, draining mobile batteries even when the radio is completely quiet.
- **Cost model:** 4 wakeups/sec $\times$ 3,600 s/hr = **14,400 CPU/timer wakes/hour** per connected radio peripheral.
- **User-visible effect:** Unnecessary background battery drain and warm device thermals on Android phones connected to a Bluetooth radio while idle in standby; timer interruption churn on desktop laptops.
- **Expected gain:** 100% elimination of 14,400 idle wake-ups/hr; reader thread suspends indefinitely on incoming socket bytes until data arrives or the socket is closed upon disconnection/cancellation.
- **Proposed change:**  
  Remove the 250 ms timeout-polling loop. In `KissTncConfig`, allow `readTimeoutMs` to default to infinite (0 or `Long.MAX_VALUE`). In `BlockingStreamReader`, call `chunks.receiveCatching()` directly without `withTimeoutOrNull()`. In `JvmSerialLink`, use `TIMEOUT_READ_BLOCKING` or infinite timeout. Rely on the existing `closer` coroutine in `KissTncDriver.serve()` (`awaitCancellation() -> link.close()`) to unblock pending reads cleanly with EOF (-1) or `IOException`.
- **Invariants touched (5.3):** Preserves immediate cancellation responsiveness on disconnect and full wire compatibility.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Profile `:core:radio` test suite and mock link; measure coroutine scheduler timer registrations over a 60-second idle period. Baseline: 240 timer events. Target: 0 timer events.
- **Verifier result:** Survived. Confirmed by `KissTncConfig.kt:62` and `BlockingStreamReader.kt:37`.
- **Related:** OPT-O10-01, OPT-O13-05.

---

### OPT-O26-02 — Extreme Channel Occupancy & Sub-1% Goodput of 1200 Baud AFSK Link Under Multi-Segment Payloads and Half-Duplex Pacing
- **Impact rank:** High        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, T, B, K   **Runtime:** Android, Desktop
- **Component:** `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/TxPacing.kt:13-47`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioWire.kt:25-61`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngine.kt:220,351-370`
- **Bottleneck class (transfer/media only):** network / protocol
- **What is wasteful:**  
  While the host-to-TNC Bluetooth connection operates at 115,200 baud (~11.5 KB/s), the physical on-air transmission uses 1,200 baud Bell 202 AFSK audio modulation (`TxPacingPolicy.airBaud = 1200`), providing a theoretical physical ceiling of only **150 raw bytes/second**.
  
  The protocol stack wraps application payloads in five distinct framing layers:
  1. HDLC flags (2 B) + FCS CRC-16 (2 B) + HDLC bit stuffing (1.1x factor) $\approx$ 9 bytes
  2. AX.25 v2.2 UI header (Destination 7 B + Source 7 B + Control 1 B + PID 1 B) = 16 bytes
  3. Flash `RadioWire` header (`MAGIC`, `VT`, `FLAGS`, `TTL`, `TAG`, `COUNTER`) = 12 bytes
  4. AES-256-GCM authentication tag = 16 bytes
  5. Segment sub-header inside ciphertext (`msgId` 2 B + `index` 1 B + `total` 1 B) = 4 bytes
  
  Total framing overhead is **57 bytes per frame**. With `DEFAULT_INFO_BUDGET = 220 bytes`, the maximum usable payload per segment is only **188 bytes** (a 23.3% protocol overhead penalty on every packet).
  
  Furthermore, half-duplex CSMA transmit pacing (`TxPacingPolicy.airtimeMs`) enforces:
  - On-air transmission time for a 236-byte AX.25 frame: $((236 + 4) \times 8 \times 1.1) / 1200 \times 1000 + 300\text{ ms (txDelay)} + 20\text{ ms (txTail)} = \mathbf{2,080\text{ ms}}$.
  - Post-transmission quiet gap: $\mathbf{500\text{ ms}}$ (`minGapMs`).
  - Collision-avoidance random jitter: $\mathbf{0\text{–}1500\text{ ms}}$ (average $750\text{ ms}$, `jitterMs`).
  
  Total channel cycle time per 188-byte segment is **3,330 ms (3.33 seconds)**. Effective goodput is only:
  $$\text{Goodput} = \frac{188\text{ bytes}}{3.33\text{ seconds}} = \mathbf{56.4\text{ bytes/second}}.$$
  
  Compared to the 11,520 byte/sec Bluetooth baud rate, effective channel utilization is only **0.49%**.
  - A 1 KB text message (1,000 bytes) fragments into $\lceil 1000 / 188 \rceil = 6$ segments, requiring **20.0 seconds** of continuous radio channel occupancy.
  - The maximum allowed 3,008-byte message (16 segments) requires **53.3 seconds** of continuous channel transmission.
  
  During this 20–53 second window, the simplex radio frequency (144.625 MHz) is heavily congested, the transmitter RF power amplifier (PA) runs continuously at high thermal dissipation (1–5 Watts RF drain), and in `RadioChatEngine.priorityPumpLoop`, the `for (frame in task.ax25Frames)` loop transmits all segments sequentially without checking `laneEmergency`, preventing emergency messages from preempting between segments.
- **Cost model:** 1 KB text = 6 frames $\times$ 3.33 s = **20.0 seconds** channel airtime; 3 KB text = 16 frames $\times$ 3.33 s = **53.3 seconds** channel airtime.
- **User-visible effect:** Extreme transmission delays (half a minute for modest messages); heavy RF channel blocking in tactical networks; transmitter thermal throttling and high battery drain on portable handhelds.
- **Expected gain:** 40%–60% reduction in airtime for typical UTF-8 chat messages via lightweight dictionary or deflate compression, reducing a 1 KB message from 6 frames down to 3–4 frames (saving 7–10 seconds of airtime per message). Inter-segment preemption ensures sub-second emergency dispatch.
- **Proposed change:**  
  1. Introduce an adaptive compression pre-pass (e.g. smaz or standard deflate with a 1-byte header flag) for message bodies $> 80$ bytes when Profile M is selected. If compression reduces segment count, transmit compressed body.
  2. In `RadioChatEngine.priorityPumpLoop`, check `laneEmergency.tryReceive()` between individual frame transmissions. If an emergency packet is queued, suspend the active task, transmit the emergency frame, and resume remaining segments.
- **Invariants touched (5.3):** Invariant 2 (wire compatibility): use an unused bit in `RadioWire.FLAGS` (bits 3–7 are currently reserved mask `0xF8`) to flag compressed payloads.
- **Risk / effort:** Medium risk; M effort.
- **Prove it:** Transmit standard 500-character tactical chat messages through `RadioLinkTestHarness`; verify segment count drops from 3 segments (10.0s airtime) to 1–2 segments (3.3s–6.6s airtime).
- **Verifier result:** Survived. Verified from `TxPacing.kt:13-47` and `RadioWire.kt:25-61`.
- **Related:** EXP-023, OPT-O09-04, OPT-O12-06.

---

### OPT-O26-03 — Unbounded Memory Leaks in RadioChatEngine pendingAcks and UNLIMITED Channel Allocations
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M, K   **Runtime:** Android, Desktop
- **Component:** `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngine.kt:110,122-126,281,410-412`, `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/BlockingStreamReader.kt:22`
- **Bottleneck class (transfer/media only):** protocol / memory
- **What is wasteful:**  
  1. In `RadioChatEngine.kt:110`, outbound messages awaiting cryptographic delivery receipts are tracked via:
     ```kotlin
     private val pendingAcks = HashMap<Long, PendingOutbound>()
     ```
     When a message is transmitted (`sendText`, line 281), `pendingAcks[firstCounter] = PendingOutbound(messageId, recipientId, sentAtMs)`. An entry is removed *only* when a matching `RadioKind.ACK` is received (`handleInboundFrame`, line 411). On lossy radio channels in the field (where RF packet loss frequently reaches 20%–50%), ACKs are routinely lost. `pendingAcks` has **no maximum size, no eviction policy, and no TTL sweeper**. Unacknowledged entries accumulate permanently in the heap for the entire lifetime of the process.
  2. In `RadioChatEngine.kt:122-125`, the four priority dispatch channels are configured with unbounded buffers:
     ```kotlin
     private val laneEmergency = Channel<PriorityTask>(Channel.UNLIMITED)
     private val laneText = Channel<PriorityTask>(Channel.UNLIMITED)
     private val laneAck = Channel<PriorityTask>(Channel.UNLIMITED)
     private val laneLocation = Channel<PriorityTask>(Channel.UNLIMITED)
     ```
     Each `PriorityTask` retains a `List<ByteArray>` containing all encoded AX.25 frames. If a radio is disconnected or pacing slowly during high outbox activity, the channels expand without limit.
  3. In `BlockingStreamReader.kt:22`, `private val chunks = Channel<ByteArray>(Channel.UNLIMITED)` similarly lacks capacity bounding between the RFCOMM pump thread and the coroutine reader.
- **Cost model:** 100 lost-ACK messages = 100 permanently retained `PendingOutbound` objects and string identifiers; unbounded channels risk linear heap growth during connection dropouts.
- **User-visible effect:** Gradual memory leak during extended multi-hour tactical operations; risk of OutOfMemory crashes on 2 GB Android devices if outbox retries buffer while the radio peripheral is disconnected.
- **Expected gain:** Strict $O(1)$ upper bound on memory consumption across all queues; automatic cleanup of stale ACK trackers.
- **Proposed change:**  
  1. Replace `HashMap<Long, PendingOutbound>` with a time-bounded, size-capped cache (e.g., `LinkedHashMap` with maximum capacity 256 and a 15-minute expiration, matching `SegmentReassembler`).
  2. Constrain priority lanes to bounded buffers (e.g. capacity 16 or 32); return `TxResult.QueueFull` to apply backpressure to callers when full.
  3. Constrain `BlockingStreamReader.chunks` to a bounded channel (e.g. `Channel(32)`).
- **Invariants touched (5.3):** None. Reassembly bounds and delivery tracking contracts are preserved.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Unit test sending 1,000 unacknowledged frames; verify `pendingAcks` memory remains capped at 256 items.
- **Verifier result:** Survived. Confirmed by `RadioChatEngine.kt:110,122-126`.
- **Related:** OPT-O02-04, OPT-O02-05.

---

### OPT-O26-04 — High Buffer Allocation Churn in Radio Framing, AAD Derivation, and Serial Write Paths
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** M, C   **Runtime:** Android, Desktop
- **Component:** `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioWire.kt:126-129`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/StreamFramer.kt:46,60`, `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/link/JvmSerialPortCatalog.kt:76`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt:62,192,209,254`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/Ax25FrameCodec.kt:82,190`
- **Bottleneck class (transfer/media only):** protocol / memory
- **What is wasteful:**  
  Across the framing, authentication, and I/O pipeline, multiple redundant byte array allocations occur on every packet:
  1. In `RadioWire.authenticatedBytes()` (lines 126–129):
     ```kotlin
     val h = encode()
     return AAD_LABEL + byteArrayOf(h[0], h[1], h[2]) + h.copyOfRange(4, 12)
     ```
     This allocates **5 separate `ByteArray` objects** (`encode()` 12 B, `byteArrayOf` 3 B, `copyOfRange` 8 B, plus 2 intermediate concatenation arrays) to produce a single 18-byte AAD.
  2. In `JvmSerialLink.write()` (`JvmSerialPortCatalog.kt:76`):
     ```kotlin
     val n = port.writeBytes(data.copyOfRange(off, data.size), data.size - off)
     ```
     `data.copyOfRange(off, data.size)` is executed unconditionally on every write loop, creating a full duplicate copy of the outgoing byte array even on the initial iteration (`off = 0`), ignoring the fact that jSerialComm provides `port.writeBytes(buffer, bytesToWrite, offset)`.
  3. In `StreamFrameDecoder.feed()` (`StreamFramer.kt:46,60`):
     ```kotlin
     buf += data.copyOfRange(offset, offset + length)
     ...
     buf = if (at == 0) buf else buf.copyOfRange(at, buf.size)
     ```
     `buf +=` causes quadratic $O(N^2)$ array reallocations on every input chunk.
  4. In `KissFrame` and `Ax25Frame`, primary constructors perform defensive `data.copyOf()` and `info.copyOf()` even when receiving freshly sliced arrays from `closeFrame()` (`buf.copyOfRange(1, len)`), allocating double the necessary payload memory.
  5. In `KissStreamDecoder.feed()` (`KissFrameCodec.kt:209`):
     `stats = stats.copy(garbageBytes = stats.garbageBytes + 1)` allocates an immutable `KissDecoderStats` object on *every single garbage byte* encountered prior to `FEND` synchronization. On a noisy or floating serial line, this creates hundreds of data class allocations per second.
- **Cost model:** ~15–20 object/buffer allocations per transmitted and received packet; up to 1,000 `KissDecoderStats` allocations per second on serial line noise.
- **User-visible effect:** Elevated GC garbage volume and short-lived object allocation churn on memory-constrained devices.
- **Expected gain:** ~65% reduction in heap allocations on the radio framing hot path; elimination of quadratic array reallocation in `StreamFrameDecoder`.
- **Proposed change:**  
  1. Allocate an exact 18-byte array in `authenticatedBytes()` and fill via `System.arraycopy` or indexed writes.
  2. Replace `port.writeBytes(data.copyOfRange(...))` with `port.writeBytes(data, bytesRemaining, off)`.
  3. Implement a fixed or circular buffer in `StreamFrameDecoder` rather than `buf +=`.
  4. Make `KissDecoderStats` counters mutable internally and export an immutable snapshot only when `statistics` property is queried.
- **Invariants touched (5.3):** None. Preserves byte-level serialization and protocol invariants.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Measure heap allocations during encoding and decoding of 100 frames; verify allocated bytes decrease by $>60\%$.
- **Verifier result:** Survived. Verified from `RadioWire.kt:126-129` and `JvmSerialPortCatalog.kt:76`.
- **Related:** OPT-O09-05, OPT-O12-02.

---

### OPT-O26-05 — Synchronous Thread.sleep() Busy-Wait (up to 4,000 ms) Blocking Android Dispatchers.IO During Radio Connection Probing
- **Impact rank:** Medium        **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** T, C, B   **Runtime:** Android
- **Component:** `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt:203-230,260-271`
- **Bottleneck class (transfer/media only):** protocol / CPU
- **What is wasteful:**  
  In `AndroidBluetoothCatalog.openRadioLink()`, when connecting to a paired radio transceiver:
  The method systematically tests multiple RFCOMM connection strategies to find the channel accepted by the radio firmware:
  1. Cached last-good channel
  2. Insecure SDP (`createInsecureRfcommSocketToServiceRecord`)
  3. Secure SDP (`createRfcommSocketToServiceRecord`)
  4. Insecure fixed channels 1 through 12 (via reflection)
  5. Secure fixed channel 1
  
  Total potential connection attempts: $1 + 1 + 1 + 12 + 1 = 16$ attempts.
  On each failed attempt, line 227 executes:
  ```kotlin
  Thread.sleep(ATTEMPT_PAUSE_MS) // ATTEMPT_PAUSE_MS = 250L
  ```
  Because `Thread.sleep(250)` is executed synchronously inside `attempt()` on a `Dispatchers.IO` worker thread, a failing connection probe (e.g. when the radio is powered off, out of range, or unresponsive) blocks the thread for up to **4,000 ms (4.0 seconds)** purely in `Thread.sleep` calls. Because `KissTncDriver` retries connections with exponential backoff up to `maxRetryAttempts = 5` times, repeated connection failures lock and tie up `Dispatchers.IO` threads for up to 20 seconds. This starves the thread pool for other background I/O operations (such as database queries, socket transfers, or disk operations).
- **Cost model:** 16 attempts $\times$ 250 ms = **4,000 ms** synchronous thread stall per connection attempt cycle; up to 20 seconds of thread blocking across 5 retries.
- **User-visible effect:** UI sluggishness or delayed background I/O operations on Android handsets while the radio gateway is attempting to reconnect to an offline radio.
- **Expected gain:** 100% elimination of synchronous thread blocking; non-blocking coroutine suspension frees worker threads immediately for other tasks.
- **Proposed change:**  
  Refactor `openRadioLink()` and inner `attempt()` to be suspending functions (`suspend fun`), replacing `Thread.sleep(ATTEMPT_PAUSE_MS)` with `kotlinx.coroutines.delay(ATTEMPT_PAUSE_MS)`.
- **Invariants touched (5.3):** None. Preserves the 250 ms inter-attempt hardware settling delay required by transceiver firmware.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Benchmark thread state during simulated failed connection; verify worker thread state remains `TIMED_WAITING` on coroutine scheduler rather than blocked in native OS `Thread.sleep`.
- **Verifier result:** Survived. Verified from `AndroidBluetoothCatalog.kt:227`.
- **Related:** OPT-O09-06, OPT-O14-02.

---

### OPT-O26-06 — Inability to Transmit Real-Time Audio or Voice Clips Over 1200 Baud Radio Data Channel (Missing Architecture / Dead VOICE_CLIP Enum)
- **Impact rank:** Low-Medium    **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** N, T, K, D   **Runtime:** Shared
- **Component:** `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/RadioWire.kt:74-75`, `docs/audit/2026-10-20-hub-audio-files-and-radio-investigation.md:232-238`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/radioaudio/RadioAudioProbe.kt:56-76`
- **Bottleneck class (transfer/media only):** network / protocol
- **What is wasteful:**  
  In `RadioWire.kt:74`, `RadioKind.VOICE_CLIP(4)` is defined as:
  ```kotlin
  /** Reserved: a short voice clip. Not implemented. */
  VOICE_CLIP(4)
  ```
  Mathematical analysis of the 1200 baud Bell 202 AFSK radio link proves that transmitting digital audio over the KISS AX.25 packet radio data link is physically impossible within practical operational constraints:
  1. **Uncompressed PCM16 (8 kHz telephone quality):** 8,000 samples/s $\times$ 2 B = 16,000 B/s. A 3-second voice clip is 48,000 bytes. At 56.4 bytes/s effective goodput, transmitting this voice clip over the radio modem requires **857 seconds (~14.3 minutes)** of continuous transmitter keyup!
  2. **Ultra-Low Bitrate Compressed Audio (Opus / SBC @ 6 kbps):** 6,000 bps = 750 B/s. A 3-second voice note is 2,250 bytes. Transmitting 2,250 bytes over 1200 baud AX.25 requires 12 segments $\times$ 3.33s = **40.0 seconds** of continuous radio keyup. This severely violates amateur radio band duty-cycle rules, monopolizes the simplex frequency, and drains portable radio batteries.
  3. **Hard Message Limits:** `RadioWire` caps segmented messages at 16 segments $\times$ 188 B = **3,008 bytes**. Any voice note exceeding 3 KB cannot even be segmented or addressed by the protocol.
  
  As proven by `docs/audit/2026-10-20-hub-audio-files-and-radio-investigation.md` (F4), handheld radios (VR-N76 / UV-Pro) support voice *only* via their secondary Bluetooth audio endpoint (using a vendor 32 kHz mono SBC audio stream on vendor UUID `39144315-32fa-40db-85ed-fbfeba2d86e6`), NOT over the KISS AX.25 serial modem. Retaining `VOICE_CLIP` in the packet radio protocol suggests a viable digital audio feature where none can exist over a 1200 baud modem, diverting developer effort and creating architectural confusion.
- **Cost model:** 3 s PCM voice = 857 s airtime; 3 s 6 kbps Opus = 40.0 s airtime; hard protocol cap = 3,008 bytes max payload.
- **User-visible effect:** Operator confusion regarding voice capabilities over packet radio; unfulfillable feature expectation.
- **Expected gain:** Clear architectural delineation: 1200 baud KISS AX.25 is strictly for text chat, telemetry, and delivery receipts; voice communications require dedicated Bluetooth audio streaming (HTCommander-style SBC channel or WebRTC LAN).
- **Proposed change:**  
  Document explicitly in `docs/network/RADIO-WIRE-FORMAT.md` that voice notes cannot traverse the 1200 baud KISS AX.25 modem. Deprecate or document `RadioKind.VOICE_CLIP` as an unsupported wire reservation, and formally route all radio voice research to the secondary Bluetooth vendor audio channel (ADR-210 / Plan R Mode 3 revival).
- **Invariants touched (5.3):** None. Wire enum value remains reserved for backward compatibility.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Static arithmetic proof demonstrating that 48 KB PCM cannot fit within the 3,008-byte `RadioWire` limit or 1200 baud airtime budget.
- **Verifier result:** Survived. Confirmed by `RadioWire.kt:74` and 2026-10-20 investigation doc.
- **Related:** ADR-210, FO-28.

---

### OPT-O26-07 — Uncached JCA Provider Lookups and SPKI ASN.1 Re-parsing in JdkRadioCrypto and JdkRadioSignatureScheme
- **Impact rank:** Low-Medium    **Evidence:** STATIC-ARITHMETIC
- **Dimension(s):** C, M, T   **Runtime:** Android, Desktop
- **Component:** `core/radio/src/jvmMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt:22,27,30,38,58,67,68`, `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/wire/JdkRadioCrypto.kt:22,27,30,38,58,67,68`
- **Bottleneck class (transfer/media only):** TLS / CPU
- **What is wasteful:**  
  In both `jvmMain` and `androidMain` of `JdkRadioCrypto`:
  - Every HMAC computation calls `Mac.getInstance("HmacSHA256")`.
  - Every SHA-256 digest calls `MessageDigest.getInstance("SHA-256")`.
  - Every AEAD seal and open calls `Cipher.getInstance("AES/GCM/NoPadding")`.
  - In `JdkRadioSignatureScheme.verify`, every signature check calls `KeyFactory.getInstance("EC")`, parses raw SubjectPublicKeyInfo ASN.1 bytes via `X509EncodedKeySpec`, generates a fresh `PublicKey` instance, and looks up `Signature.getInstance("SHA256withECDSA")`.
  
  During frame ingest (`RadioSession.ingestAead`), the session scans all paired peers ($O(N)$) testing rotating tags. If a frame has an invalid tag or during broadcast verification, JCA provider table lookups and reflection parsing occur repeatedly. On Android, `Cipher.getInstance` and `KeyFactory.getInstance` involve synchronized global provider locks (`Security.getProviders()`).
- **Cost model:** 1 JCA provider table lookup + 1 Cipher/Mac allocation per tag check; full ASN.1 SPKI parsing on every signed broadcast verification.
- **User-visible effect:** Unnecessary CPU cycle consumption and microsecond latency during frame ingest.
- **Expected gain:** ~70% reduction in crypto initialization overhead; elimination of ASN.1 key parsing on verified broadcast packets.
- **Proposed change:**  
  Cache `KeyFactory.getInstance("EC")` and pre-parsed `PublicKey` instances in `RadioSession.broadcastSenders` rather than passing raw `ByteArray` and reparsing ASN.1 on every packet. Use thread-local or pooled `Cipher` and `Mac` instances.
- **Invariants touched (5.3):** Invariant 1 (security): cryptographic algorithms and verification math are identical.
- **Risk / effort:** Low risk; S effort.
- **Prove it:** Microbenchmark 1,000 `verify` operations; verify throughput improves by $>2\times$.
- **Verifier result:** Survived. Verified from `JdkRadioCrypto.kt:22-68`.
- **Related:** OPT-O15-01, OPT-O15-03.

---

## 6. "No Problem, Because..." Notes

1. **KissFrameCodec and KissStreamDecoder Bounds Enforcement:**  
   `KissStreamDecoder` enforces `maxFrameBytes = 1024` and drops frames containing invalid escape sequences (`TFEND`/`TFESC` only) without throwing or crashing. It resynchronizes cleanly on the next `FEND`. It never allocates unbounded buffers on malformed input.
2. **Replay Window and Time Floor Protection:**  
   `ReplayWindow` implements a 64-wide bitmask (RFC 4303) and `RadioSendCounter` uses a durable 64-reservation with a 2026 time floor (`secondsSince2026`). Nonce reuse under AES-256-GCM is cryptographically prevented even across process crashes.
3. **Radio Safety & Transmit Protection (Rule 4):**  
   `RadioGatewayManager.requestTestMessage` and `RadioChatEngine.sendText` enforce strict user confirmation, regulatory callsign requirement, and Profile M authorization confirmation. The engine never transmits blindly to "probe" for a TNC, avoiding illegal RF emissions.
4. **Desktop Radio Gateway Lazy Instantiation:**  
   `DesktopRadioGatewayController` does NOT instantiate `RadioGatewayManager` or start Bluetooth scanning if the stored switch (`~/.flash/radio-gateway.properties`) is OFF. When toggled OFF, active managers and jobs are cancelled and released, ensuring 0% background CPU and zero open ports.

---

## 7. Measurements Taken & Conditions

- **Reference Environment:** Windows 11, 8 logical cores, 20 GB RAM.
- **Static Arithmetic Calculations:**
  - 1200 baud Bell 202 AFSK raw bit rate: 1,200 bps = 150 B/s.
  - Multi-layer framing overhead: 57 bytes per frame (HDLC 9 B + AX.25 16 B + RadioWire 12 B + GCM Tag 16 B + Segment 4 B).
  - Usable payload ratio: $188 / (188 + 57) = 76.7\%$ ($23.3\%$ protocol overhead).
  - Paced frame duration: $2,080\text{ ms (airtime)} + 500\text{ ms (gap)} + 750\text{ ms (jitter)} = 3,330\text{ ms}$.
  - Goodput: $188\text{ B} / 3.33\text{ s} = 56.4\text{ B/s}$ ($0.49\%$ of 115,200 baud serial rate).
  - 1 KB message airtime: 6 segments $\times$ 3.33 s = 20.0 seconds.
  - 3 KB message airtime: 16 segments $\times$ 3.33 s = 53.3 seconds.
  - Semi-blocking read wakeups: 250 ms timeout = 4 Hz = 14,400 wakes/hour at idle.
  - Connection probe blocking time: 16 attempts $\times$ 250 ms `Thread.sleep` = 4,000 ms thread stall.
  - 3-second 8 kHz 16-bit PCM voice: 48,000 bytes $/ 56.4\text{ B/s} = 857\text{ seconds (14.3 minutes)}$.

---

## 8. Summary of Findings

| ID | Title | Impact Rank | Dimensions | Evidence Type | Runtime | Effort | Risk | Verifier Result | Status |
|---|---|---|---|---|---|---|---|---|---|
| `OPT-O26-01` | Semi-Blocking 250 ms Read Loop in KissTncDriver Generates 14,400 Idle Wake-ups/Hour and Prevents OS Power Collapse | High | B, C, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| `OPT-O26-02` | Extreme Channel Occupancy & Sub-1% Goodput of 1200 Baud AFSK Link Under Multi-Segment Payloads and Half-Duplex Pacing | High | N, T, B, K | STATIC-ARITHMETIC | Android, Desktop | M | Medium | Survived | OPEN |
| `OPT-O26-03` | Unbounded Memory Leaks in RadioChatEngine pendingAcks and UNLIMITED Channel Allocations | Medium | M, K | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| `OPT-O26-04` | High Buffer Allocation Churn in Radio Framing, AAD Derivation, and Serial Write Paths | Medium | M, C | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
| `OPT-O26-05` | Synchronous Thread.sleep() Busy-Wait (up to 4,000 ms) Blocking Android Dispatchers.IO During Radio Connection Probing | Medium | T, C, B | STATIC-ARITHMETIC | Android | S | Low | Survived | OPEN |
| `OPT-O26-06` | Inability to Transmit Real-Time Audio or Voice Clips Over 1200 Baud Radio Data Channel (Missing Architecture / Dead VOICE_CLIP Enum) | Low-Medium | N, T, K, D | STATIC-ARITHMETIC | Shared | S | Low | Survived | OPEN |
| `OPT-O26-07` | Uncached JCA Provider Lookups and SPKI ASN.1 Re-parsing in JdkRadioCrypto and JdkRadioSignatureScheme | Low-Medium | C, M, T | STATIC-ARITHMETIC | Android, Desktop | S | Low | Survived | OPEN |
