# Stream C (calls) — probe additions

Stream C does **not** register a new probe. It adds fields to the existing `CALL_DIAG` line (tag `CALL_DIAG`, not
`PROBE`), because a `call.*` probe namespace does not exist yet and creating one is the integrator's/owner's call
(plan §4.3 rule 2: "no new probe namespace").

## Delivered: `CALL_DIAG` `ain` quality fields (`OPT-O28-03`)

Every connection line already carries an `ain` section. It now also carries, when the backend reports the ingredients:

```text
I/CALL_DIAG: leg=dev-a1b2 CONNECTED rtt=4ms bwe=2400kbps path=host/host | vin 640x360 24.0fps … | ain 31kbps lost=0
  jitter=4ms concealed=0 jb=42ms plc=0.4% avDrift=8ms | aout 29kbps
```

| Field | Meaning | Computed by |
|---|---|---|
| `jb` | average time a played sample waited in this endpoint's jitter buffer, ms (`Δ jitterBufferDelay / Δ jitterBufferEmittedCount`) | `CallQualityMath.jitterBufferDelayMs` |
| `plc` | share of played samples the decoder had to conceal, % (`Δ concealedSamples / Δ totalSamplesReceived`) | `CallQualityMath.concealmentPercent` |
| `avDrift` | audio/video playout skew, ms (`Δ estimatedPlayoutTimestamp` audio − video) | `CallQualityMath.avDriftMs` |

A field is omitted when its counters are absent, and is never rendered as 0 for missing data.

## Proposed, not emitted: `call.quality`

The audit's `OPT-O28-03` asks for a structured probe so a test can assert thresholds instead of parsing text. Proposed
shape, **for the integrator to register in `docs/testing/PROBES.md` before it is emitted** (it would be emitted by the
two call sessions' stats loop once per `CALL_DIAG` period — 5 s — never per frame):

```text
PROBE: call.quality peer=<8> role=<in|out> rtt=<ms> jbDelayMs=<ms> plcPct=<n.n> avDriftMs=<ms> lossPct=<n.n> tier=<HIGH|MEDIUM|LOW|ULTRA_LOW>
```

Rules it would obey: ids shortened to 8 characters, no media content, no keys, enum values only. A device test would
then be able to fail on `plcPct > 5` or `|avDriftMs| > 50` without reading prose. Not built in this stream because the
namespace is not ours to create.
