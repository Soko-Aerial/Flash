# Experiment Log Additions — Stream R-link

## EXP-050 — Simulated Power-Cycle & Flapping Recovery under Backoff

### Date
2026-10-16

### Objective
Measure behavior and thread stability of `KissTncDriver` reconnect loop and `RadioGatewayManager` state machine during link drops, rapid flapping, and power-cycle recovery using simulated in-memory byte links.

### Configuration
- Host: JVM 17
- Link: `InMemoryLink` pair
- Config: `reconnectBaseMs = 1L`, `reconnectCapMs = 5L`, `maxRetryAttempts = 15`
- Test condition 1: Power-cycle (single drop, wait 30ms, re-supply fresh link).
- Test condition 2: Flapping link (10 consecutive immediate link breaks upon connect).

### Observations
1. **Power-cycle:** Driver detected link EOF immediately, entered `Waiting(attempt = 1, retryInMs = 20)`, transitioned smoothly back to `Connected` once the fresh link became available. Recovery completed in ~100ms without state machine desynchronization.
2. **Flapping:** 10 consecutive link drops did not cause thread starvation, busy-wait CPU spiking, or coroutine leakage. Exponential backoff paced attempts cleanly.
3. **Retry limit:** When all attempts failed without link recovery, retries ceased at the configured limit (`maxRetryAttempts`), stopping all background polling until user intervention.

### Conclusion
The combination of `KissTncDriver` exponential backoff with `RadioGatewayManager` retry limit is stable against erratic radio connections and prevents runaway connection loops.
