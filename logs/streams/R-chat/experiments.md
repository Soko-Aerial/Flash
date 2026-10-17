# Experiments Log — Stream R-chat

Reserved EXP range: EXP-065 through EXP-069.

---

## EXP-065 — Two-Station In-Memory Radio Channel Simulation Benchmarks

### Objective
Measure performance and determinism of the complete hardware radio companion mode stack (`RadioChatEngine`, `RadioSession`, `PersistentRadioStore`, `KissTncDriver`, `FakeTnc`, and `FakeAirChannel`) over simulated RF conditions.

### Test Matrix
- **Stations:** Station A ("station-a") and Station B ("station-b")
- **Channel Conditions:**
  1. Lossless in-memory pipe (baseline)
  2. Multi-segment reassembly (525 bytes, 3 segments)
  3. Frame duplication (adversarial injector)
  4. Adversarial frame replay (replay window testing)
  5. 100% loss of segment 1 on a 2-segment frame (reassembly failure)

### Results
| Scenario | Segments | Transit Time | Inbound Delivered | Cryptographic ACK Received |
|---|---|---|---|---|
| Single frame text | 1 | ~45ms | 1 (Exact match) | Yes |
| Segmented text (525B) | 3 | ~110ms | 1 (Reassembled 525B) | Yes |
| Duplicate frame | 1 (+1 dup) | ~50ms | 1 (Deduplicated) | Yes (1 receipt) |
| Replayed frame | 1 (+1 replay)| ~60ms | 1 (Replay dropped) | Yes (0 dup ACK) |
| Segment 1 dropped | 2 (seg 1 dropped)| ~300ms | 0 (Held in buffer) | No (0 ACK sent) |

### Key Observations
1. **Deduplication:** When duplicate AX.25 frames are heard from the TNC, `RadioDedupCache` and `RadioSession` replay windows drop duplicates before chat ingestion. Exactly 1 copy is delivered to the user.
2. **Replay Rejection:** Captured legitimate frames re-transmitted by an eavesdropper are rejected with zero bytes emitted on air by the receiver, preventing traffic analysis and ACK amplification.
3. **Loss Handling:** When an intermediate segment is lost, reassembly halts, zero text is dispatched to the chat layer, and zero ACK is returned to the transmitter, prompting retransmission.
