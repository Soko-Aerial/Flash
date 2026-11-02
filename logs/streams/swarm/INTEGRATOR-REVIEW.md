# Stream S (swarm) — integrator review

**Reviewed:** `agent/opt-swarm`, 14 commits off `7adcf4f5` (+1 integrator style commit `73431508`).
**Reviewer:** integrator session, 2026-11-02. **Status: merged after the gates (see §7).**
**Not device-verified.** Every number in the stream's log is desktop-JVM.

Scope of the branch: `core/swarm/**`, four swarm files under `core/engine`, `ui/chat/.../FlashSwarmPieceMap.kt`, and
`logs/streams/swarm/**`. `git diff --name-only 7adcf4f5..agent/opt-swarm` filtered against the owned paths returns
**nothing**, so there is no stray edit to a module the stream does not own.

---

## 1. Disposition

**13 of 14 findings delivered; 1 rejected on measurement.**

| Finding | Verdict | Note |
|---|---|---|
| `O08-01` piece scheduler | kept | `PieceScan` bit-sliced rarity buckets rebuilt per pass; `PiecePicker` kept as the semantic oracle and compared slot-by-slot over **250 randomised topologies** (`PieceScanEquivalenceTest`) |
| `O25-08` wait reason | kept | word-wise `hasBitOutside`; work count 64× down (EXP-126) |
| `O08-07` HAVE storm | kept | publishes only when the request window has room; deferred to the tick |
| `O08-02` serve re-hash | kept, **with the integrity question checked** — see §2 |
| `O08-04` fsync/DAO batching | kept | drain up to 256 commands, record write stops the batch so FIFO holds |
| `O08-05` 4 Hz ticker | kept | 250 ms active / 5 s idle + conflated kick; kicks on announcement, inbound, accept, resume, reevaluate, network-up |
| `O08-06` thermal | kept | samples the existing `ThermalGovernor` singleton; MODERATE one notch down, SEVERE+ `LOW` **and** no serving; a person's "do not serve" is a separate flag cooling cannot overwrite |
| `O08-08` / `O02-05` tombstones | kept | 37-day in-memory expiry; Summary capped at the codec's **real** 64; verified-key set claimed only after a successful signature check |
| `O08-03` fairness (ADR-232) | kept | deficit round robin with an age gate; **no wire change** |
| `O02-04` channels | kept | `SwarmSendQueue` 16 frames/peer; event 32; persist 64 with actor backpressure — see §3 |
| `O23-07` NIO buffers | **REJECTED** | `RandomAccessFile.seek` measured **2.1× slower** (read 669 vs 319 µs/MiB); the reused direct buffer was within noise. Nothing changed; EXP-128 records the recipe for a phone |
| `O03-15` piece map | kept | both layers use `drawWithCache`; the stream also **corrected the finding's premise** — Compose geometry/colour are inline value classes, so the "1,200–6,000 allocations/s" did not exist; only `PieceGrid` and `Stroke` did |
| `O28-04` telemetry | kept | four probes, registered in `docs/testing/PROBES.md` by this review |

## 2. `O08-02`: is it still safe to trust the bitfield?

This is the one change that removes a check, so it was verified rather than accepted:

- The **receiving** peer still hashes every piece against the manifest before storing it (`SwarmDriver.kt`, ingest path:
  `expectedHash != null && Sha256.digest(frame.bytes).contentEquals(expectedHash)`). A corrupted serve is therefore rejected
  and re-requested, and the sender is struck. Integrity is unchanged on the path that matters.
- The **sending** side still refuses a source whose whole-file size no longer matches the announcement, rate-limited to one
  `identity()` per 2 s, which is what ERROR-109's signed `LOST` needs.
- The documented, deliberate gap: a **same-size** edit of an origin's file is no longer caught before it is served. It is
  caught by the receiver's hash. Recorded in ADR-324 rather than glossed.

## 3. `O02-04`: the drop rule

`SwarmSendQueue.offer` drops a **piece** frame only; a control frame (HAVE, HAVE_ALL, SUMMARY, REQUEST, UNREQUEST, REJECT,
CANCEL, CANCEL_ACK, SOURCE_STATUS, MANIFEST_*) is handed to the queue in a background sender and never dropped. That is the
right asymmetry: a lost `CANCEL` is a cancelled file that keeps downloading. The consequence — a control frame can overtake a
dropped piece frame — is tolerable because frames are independent and the requester re-asks.

## 4. `O08-03`: the yield is only a slot

`ServeHandler` acts on `yieldPeerId` by removing the victim from `activeRequesters` and dropping its tenure counter. Its
`outstandingServeBytes` are **not** touched, so in-flight reads finish; only admission to new work stops, and the peer gets
the same `BUSY` a newcomer used to get. One slot is yielded per admission, so the slot count never grows. `SwarmServeFairness`
tests the starvation model, the 1 s age gate, the named victim and the profile floor.

## 5. Defects the stream found in its own work (kept as their records)

- **ERROR-961** — the first `PieceScan` draft dropped every piece that had a request in flight when the gate was null, and
  `exhaustPeer()` double-decremented holders for pieces the peer was already requesting. Caught by the equivalence test, not
  by the benchmark: exactly why the stream kept `PiecePicker` as an oracle.
- **ERROR-963** — the audit said the Summary cap was 256; the codec says **64**, so a group that cancelled 65 files could not
  send a Summary at all. Capped and covered by `SwarmTombstoneRetentionTest`.
- **ERROR-964** — the stream's own benchmark asserted a 4× wall-clock ratio and failed at exactly 4.0× under suite load. The
  assertion is now the load-independent work count; the timing is printed and recorded. This is the same mistake ERROR-959/960
  keep making elsewhere, caught by the stream itself.

## 6. Tooling damage — found and repaired, then verified

The stream's editing tooling introduced a UTF-8 BOM and double-encoded punctuation into `SwarmDriver.kt`; they caught it in
their own `79f39848` repair commit. This review verified the result rather than trusting the commit message: no BOM at the
start of any changed file, and no `Â§`/`â€`/`Ã` sequences anywhere in `core/swarm`, `core/engine` or `ui/chat`. Two
line-joining artifacts survived that repair and were fixed here (`): ByteArray? {        val pieceSize` and
`toPieceRanges(...) {        if (...)`), plus one `Ã—` in the copied EXP-126 heading. Behaviour unchanged.

## 7. Gate

Branch tip `73431508`: `:core:swarm:jvmTest` **191/0**, `:core:engine:jvmTest` **175/0**, `:ui:chat:jvmTest` **730/0**,
`:desktop:compileKotlinJvm` and `:app:compileDebugKotlin` green, forced, 134 tasks, `BUILD SUCCESSFUL in 7m 37s`. The
expanded gates run on the merged tree and their numbers are in `logs/progress.md`.

## 8. What is NOT proven

Everything device-side: group throughput and completion time, the fairness rotation's cost in real airtime, thermal
step-down under real heat, idle wake-ups on Android, piece I/O on ART, and the piece-map frame time. All are `PERF-S01`
(plus `PERF-S02` if `O23-07` is ever reopened) in `docs/testing/TEST-BACKLOG.md`.
