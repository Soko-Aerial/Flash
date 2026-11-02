# Stream C (calls) — decision additions

Reserved block: **ADR-340..349**. Folded into `docs/decisions.md` by the integrator.

## ADR-341 — PTT learns its jitter-buffer start depth across bursts instead of fixing it at 120 ms

### Date
2026-11-02

### Decision
`PttJitterBuffer`'s target depth stays a constructor parameter, but the value the engine passes is now
`PttJitterDepth.targetDepthMs()`: a process-wide value that starts at the shipped 120 ms and moves one 20 ms step per
**finished burst**, inside 60–160 ms. A burst shorter than 50 packets is ignored; concealment above 2 % raises the gate;
a burst of at least 200 packets with no concealment at all lowers it.

### Context
`OPT-O12-04`: the 120 ms gate is pure turn-taking latency on a LAN, where jitter is a few milliseconds. But the same
constant protects a jittery 2.4 GHz link, and this stream has no way to measure either (no device session). Lowering
the default would be the "never trade a drop-out for a lower bitrate without a measurement" mistake.

### Why this shape and not an adaptive in-session depth
The target depth is consulted **only** in `poll()` before playout starts; once started, the host's tick pace governs and
the depth is irrelevant. So an in-session change cannot help, and a cross-burst change cannot hurt: a wrong guess costs
at most one concealed packet at the start of one burst, and the next burst corrects it in either direction. The ordering
that protects the link (raise on concealment) acts first in any bad burst.

### Alternatives considered
- **Leave 120 ms fixed.** Rejected only because the finding's whole point is that the number is unmeasured; the
  mechanism above is strictly better than a constant and starts from it.
- **Lower the default to 60 ms.** Rejected: no measurement, and the first burst of every process would risk a clipped
  syllable.
- **Per-peer memory.** Rejected for v1: `PttPlayoutCore` has no peer identity, and a PTT burst is usually with the same
  peer anyway. Revisit if a mixed population shows the gate oscillating.

### Verification
`PttJitterDepthTest` (7 tests) pins every rule and both bounds; `PttJitterRingTest` (6) pins the queue behaviour the
rewrite had to keep. The end-to-end effect is `PERF-C01` step 5 — nothing here is device-verified.

### Revisit when
`PERF-C01` has recorded start-of-burst latency and any clipped syllable per burst on a real LAN and a real 2.4 GHz link.

---

## ADR-342 — the SDP is not the video bitrate authority; `RtpSenderTuning` is

### Date
2026-11-02

### Decision
`CallSdp` tunes the **audio** section only. The video section is passed through untouched, and the tuner no longer writes
`x-google-{start,min,max}-bitrate`. Video bitrate is set where the stack honours it: `RtpSenderTuning`'s
`setParameters` (`maxBitrateBps`, `minBitrateBps`, `scaleResolutionDownBy`, `degradationPreference`).

### Context
`OPT-O11-05`: `enforceVp8Only` deletes every `a=fmtp:` line in the video section, on purpose and unconditionally
(libwebrtc's `VideoDecoderFactoryTemplate` compares a VP8 supported format's empty parameters against the offered ones;
any fmtp line turns the decoder lookup into `NullVideoDecoder`, i.e. black video). Every description this app installs
goes through `enforceVp8Only`, so the hints `tuneVideo` formatted were dead on the very next call — and, worse, they
made the code read as if the SDP bounded video bitrate.

### Consequences
- Wire output is **unchanged**: the fields were already stripped. A test asserts the sanitized body has no video fmtp for
  every tier, which is the invariant that mattered.
- If `OPT-O11-03` (per-leg codec rule, device-gated) ever stops stripping the video section, video hints would suddenly
  become live. **That change must reconsider this ADR**, not add hints back quietly.
- The tier's video numbers are still pinned, as the ladder `RtpSenderTuning` applies (`theVideoLadderIsMonotonicInEveryDimension`).

---

## ADR-343 — a covered 1:1 video tile is unbound from its track, not merely hidden

### Date
2026-11-02

### Decision
While `FlashRemoteVideoPlaceholder` covers the main call tile (data saver on, or the other camera off), the tile is
composed with `track = null`. The surface composable stays in the tree — the renderer must never be rebuilt — but no sink
is bound to the track, so no frame is converted, rasterised or uploaded.

### Context
`OPT-O11-06`: the tile kept the remote track bound and the desktop `DesktopVideoSink` converted and rasterised every
decoded frame underneath an opaque placeholder (15–30 % of a core by the audit's arithmetic), and Android uploaded each
one to its `SurfaceView`.

### Why not uncompose the surface
`FlashCallVideoSurface`'s contract (both actuals) is that the native renderer is initialised once per composable instance
and never released on a track change; releasing/recreating it on every data-saver toggle is the "two black tiles while
audio works" bug class. `bind(null)` already unbinds cleanly and clears the displayed frame, so hiding is enough.

### Verification
`DesktopVideoSinkBindingTest`: a stand-in track forwards frames to whatever sinks are bound; a shown tile starts one
scale conversion per frame, a covered one starts none, and re-binding still works. Device check `PERF-C01` step 3.
