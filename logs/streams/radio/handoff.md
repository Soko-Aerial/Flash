# Radio stream (R) — handoff

Fragment for `logs/handoff.md`. Written at the end of the stream's session; the integrator merges `agent/opt-radio` and
folds this in.

## Branch / worktree
`agent/opt-radio` in `E:\FlashWork\opt-radio`, based on `dev` at `7adcf4f5`. Not merged, not pushed (the integrator
reviews and merges).

## What this stream changed
Six of the seven `O26-*` findings of `docs/audit/optimization-2026-10-23/O26-bluetooth-radio.md` are implemented, one is
recorded as obsolete. Nothing in the radio wire format changed: `RadioGoldenFrameTest` and the `RadioSession` anti-replay
tests (ERROR-870) pass unchanged, and no frame byte, counter, nonce, AAD layout or ARQ behaviour was touched.

| Finding | Verdict | Where |
|---|---|---|
| `O26-01` 250 ms read poll | **DONE**: 60 s heartbeat (14 401 -> 61 reads per idle hour) | `KissTncConfig`, `KissTncDriver.readLoop`, ADR-330, EXP-130 |
| `O26-04` framing/buffer churn | **DONE**: 5 allocations -> 1 for the AAD; O(n^2) -> O(n) stream buffer; stats snapshots; serial write without a full copy | `RadioWire.authenticatedBytes`, `ByteQueue`, `StreamFramer`, `KissFrameCodec`, `JvmSerialPortCatalog`, EXP-131 |
| `O26-03` unbounded ACK table and lanes | **DONE**: 256-entry/30-min `PendingAckTable`, lanes 32, reader chunks 32 | `PendingAckTable`, `RadioChatEngine`, `BlockingStreamReader`, ADR-331 |
| `O26-05` `Thread.sleep` in Android probing | **DONE**: `openRadioLink`/`openRadioAudioLink` are `suspend`, the 250 ms pause is `delay` | `AndroidBluetoothCatalog` + both call sites |
| `O26-07` JCA lookups / SPKI re-parsing | **DONE**: provider resolved once, parsed sender keys cached (16) | `JdkRadioCrypto` (jvmMain + androidMain) |
| `O26-02` channel occupancy | **MEASURED, no code change**: the audit's arithmetic reproduces from the real codecs; the two fixes it proposes are a wire-format change (compression) and a behaviour change needing two radios (inter-segment priority) | EXP-133 |
| `O26-06` voice over the data channel | **OBSOLETE**: ADR-210/ADR-225 put audio on the vendor SBC channel, REJ-06 refuses packet-radio voice. Marked, not built. | audit §8 |

## What a reviewer should look at first
1. `PendingAckTable` + `RadioChatEngine`: the bound's safety argument (an evicted ACK costs one retransmission, never a
   message) and that the retry path is untouched.
2. `RadioTncDriver`/`KissTncConfig` default change: three test rigs pass `readTimeoutMs = 50` explicitly and are
   unaffected; hosts that build `KissTncConfig()` now get 60 s.
3. `AndroidBluetoothCatalog` signature change (`suspend`): two call sites, both already inside `Dispatchers.IO`.
4. `JdkRadioCrypto`: no cached `Cipher`/`Mac`/`Signature` instance and no key retained across a call - only the provider
   and parsed **public** keys.

## Not verified (no hardware in this session)
Everything device-facing: `PERF-R01`..`PERF-R05` in `backlog-additions.md`. In particular the two behaviour questions
this stream raises: does a text still arrive immediately after an idle hour (heartbeat), and does a lossy channel still
deliver every text with a bounded ACK table.

## Recommended next task for whoever picks this up
Run the integrator's expanded gate (which gains `:core:radio:jvmTest`), then have the owner run `PERF-R01`/`PERF-R02` on
the two-radio bench, because the heartbeat and the ACK bound are the two changes whose failure mode only appears on air.
