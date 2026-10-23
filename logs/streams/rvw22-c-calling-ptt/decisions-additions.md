# STREAM C decisions (fragment; integrator folds into `docs/decisions.md` if acceptable)

Small behavioural contracts chosen while fixing the STREAM C findings of
`docs/audit/2026-10-22-comprehensive-brutal-code-review.md`. None of them changes a wire format or a user-visible
sentence; they are recorded because a future reader will otherwise re-litigate them.

## C-D1 — No platform monotonic clock for the calling/PTT cooldowns (F43, F57)

**Decision.** `CallQualityGovernor` and `CallStatusBook` stay wall-clock based, with the clock injected as
`() -> Long` (default `SystemTimeSource.nowMs()`), and a backwards step is handled locally: a negative elapsed reads
as "the cooldown/flood window still applies" (F43) and "no evidence of a flood" (F57) respectively.

**Context.** The audit's F43 fix said "guard with `elapsed in 0 until ...` **and use monotonic time**".

**Why the monotonic half was not taken.** There is no monotonic source in `core:common`; `core:calling` is shared by
the Android and desktop hosts and both call sites pass `SystemTimeSource.nowMs()`. Adding a platform seam
(expect/actual `MonotonicClock`) for two comparisons is real new surface on both hosts, and the guards make the
failure it would prevent impossible anyway: with a backwards step now reading as "still cooling down" / "not a
flood", the worst case is a cooldown that lasts as long as the step, which is bounded and harmless.

**Also rejected:** treating `elapsed == 0` as "in cooldown". Existing tests and non-advancing callers pass a fixed
clock; `0` must keep meaning "no evidence of movement", otherwise recovery never happens for them (it broke
`FlashGroupCallVideoAuditTest`).

**Revisit when** a monotonic source lands in `core:common` for another reason — then both call sites can be switched
without touching the guards.

## C-D2 — PTT jitter buffer works in an unwrapped 64-bit sequence domain (F45)

**Decision.** `PttJitterBuffer` keeps an unwrapped domain anchored on the highest sequence seen (`newestSeq`), and
every `Poll.seq` it reports is in that domain. Duplicate/late tests, the queue keys and `nextSeq` all use it.

**Why not the audit's expression.** `(seq - nextSeq).toInt() < 0` tests the wrong boundary (a difference of exactly
`0x80000000` reads negative) and does not move `nextSeq`, which is what `poll` advances. Anchoring on the highest
sequence seen is the textbook unwrap and leaves `poll` untouched.

**Consequence for consumers.** Two `Poll.seq` values are only comparable inside one buffer's lifetime; nothing may
persist them or compare them to a wire value. Today the only consumer is the playout loop's own bookkeeping.

## C-D3 — PTT jitter buffer overflow drops the newest packet (F46)

**Decision.** On overflow the newest arrival is discarded; the head of the stream (the packet the next `poll` must
return) is never touched.

**Why not the audit's alternative** ("or advance `nextSeq = queue.firstKey()`"): re-basing `nextSeq` silently
teleports playout forward to the newest surviving packet and desynchronises the lost/late accounting from what was
actually played. Dropping the newest packet costs exactly one packet and no extra concealed slot.
