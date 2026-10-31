# Stream T — integrator review (2026-10-31)

The stream's own logs (`progress.md`, `handoff.md`, `experiments.md`, `decisions-additions.md`) were written
before review. This file records what was **accepted into `dev`**, what was **corrected**, and what was
**claimed but not delivered**. Read this before trusting any before/after in the other files.

Branch: `agent/opt-transfer` (11 commits, 35 files) merged into `dev` with `--no-ff`; follow-up fixes in
the commit after it. Base: `6f8fe180`. Note the stream's report cites `docs/streams/transfer/`; the real
fragments are `logs/streams/transfer/`.

## 1. Accepted

| Finding | Change | Why it is safe |
|---|---|---|
| `O07-07` | 1 MB chunk plan no longer clamped to 256 KB | The clamp was the bug. |
| `O07-09`/`O02-10` | `ResumeBitVector` word-wise operations, `reconcile`, `forEachMissingIndex` | Same semantics, no boxing; the growth path only grows and `copyWordsFrom` is only reachable when `source.totalChunks ≤ size`. |
| `O25-01` | `pendingIndexes` init without the N·M scan | Pure. |
| `O07-05` | thread-local `MessageDigest` instead of a provider lookup per chunk | `reset()` before every use, so nothing leaks between calls; same digests. |
| `O23-04` | orphaned hub/partial/voice/camera file purge | Prefix-scoped (`flash-voice-`) and age-bounded (24 h). |
| `O05-04`/`O05-05`/`O05-09` | single EXIF read before decode, rotate without a second matrix, bounded decode retries | Equivalent output; a non-OOM `null` is a corrupt file, and an OOM still escalates the sample. |
| `O05-08` | camera temp file deleted when the capture is discarded; `camera_captures` pruned at 24 h | Dedicated directory. |
| `O02-08`/`O02-09` | `JvmImageDecoder` cache trim hook; `applicationContext` instead of the `Activity` | Leak fixes. |
| `O02-01` | inbound frames take their payload from `ChunkBufferPool` and release it | Every parse site releases on all paths (reject, hash mismatch, write failure, cancel, success) and no caller retains `frame.data` across `onFrame`. Zero-length chunks skip the pool, and `release` tolerates a zero-length array. |
| `O07-04` | hashing/disk work moved out of the pipeline lock | Session bookkeeping stays under the lock, the duplicate check is taken there, and the sink's own positional write is internally locked, so a concurrent duplicate writes the same bytes at the same offset. The final (late-duplicate) re-check inside the lock still ACKs exactly once. Nuance the stream's wording hides: writes still serialise on the sink lock, so the win is the hashing, not the disk. |
| `O07-06` | endgame retransmission (2 attempts, 200 ms apart) | Recovers a lost chunk without the 15 s teardown; the wait is far below the historical tail failure. Corrected: a bare 200 ms look counts queued frames as missing **ERROR-894**, so the endgame now settles first. |
| `O07-08` | 100 ms progress/watch poll | 10 Hz is enough for a list row; `ProgressSample` keeps its window. |
| `O28-02` | disk/wire flight telemetry | Local counters plus `FlashProbe`. |
| `O25-07` | sort bypass in `ChunkFrame.serialize` | Ascending order is preserved for unsorted input. |
| `O05-02`/`O05-03`/`O05-10` | desktop AAC decode off the UI thread; direct WAV writer with a patched header | The 44-byte header is written as a placeholder and patched on stop; a recording under one sample is deleted. |
| `O07-10` | recorded as blocked by REJ-01 | Matches `docs/security.md`. |

## 2. Corrected by the integrator

### 2.1 **Blocking** — a resumed send misaligned every frame (`MultiStreamDispatcher`)

The materializer changed from "open at 0, skip defensively up to the first pending index" to
"`startIndex = firstIndex`, `pos = firstIndex`". That is only true for a source that can open at an
offset — and **no production source is a `SeekableChunkSource`** (`ChunkSource { fileSourceOpener.open(uri) }`
is a plain lambda, so `source is SeekableChunkSource` is false; the only implementation lives in
`PipelineEndToEndTest`).

With a non-seekable source the stream opened at byte 0 while `ChunkStream` was told `nextIndex = 90`, so
the first frame carried chunk 0's bytes under index 90, the second chunk 1's bytes under 91, and so on.
Each frame's own SHA-256 matches its payload, so per-chunk verification passed, the byte counters added
up, the end-of-stream length check passed and (because `initialIndex > 0`) the whole-file digest compare
was skipped — the receiver then assembled the wrong file and only the receiver's own whole-file check
could catch it, as a MISMATCH failure. **Every pause → resume send of a file with done chunks was
affected.** The rule now lives in one place, `Chunker.openChunkStream`: a `startIndex > 0` opens a
seekable source at the offset and otherwise opens at 0 and skips the prefix with `BufferedSource.skip`
(read-and-discard, the prefix is deliberately not hashed); the dispatcher's own `startIndex`/`pos` stay
at the first pending index, so label and position agree. The stream's existing resume test only ever
asserted indexes and counters, never payloads, so it stayed green. New test:
`MultiStreamDispatcherTest.resume past a done prefix keeps each chunk's own payload` (position-dependent
payload, pre-seeded assembler).

Fixing that exposed **ERROR-894** in the same test: with the payloads finally correct the resume could
finish, and it finished with 22 frames for 8 chunks because the endgame treated queued-but-unsent frames
as lost and re-sent the whole tail, twice. The endgame now waits (bounded 2 s) for every chunk to reach a
wire once, plus a 200 ms ACK-batching grace, before retransmitting.

### 2.2 `SendPipeline` counted the seeked prefix twice

`chunksSkipped`/`bytesSkipped` were pre-counted for `startIndex` **and** counted again by the loop for
every `resumeSet` member (the loop still started at `startIndex`). The counters are now filled once from
`startIndex` and the loop starts at `firstMissing`.

### 2.3 `JvmAudioPlayer.play()` could decode twice and leak a clip

`play()` called `ensurePrepared()` on the UI thread while the constructor's own launch was decoding: two
`Clip.open` calls, two assignments, one orphaned clip, and `pendingPlay` was a plain field across threads.
Preparation is single-flight now (`preparing` flag set synchronously), `play()` only starts a clip that is
already prepared and otherwise leaves the tap to the preparation that is in flight, `pendingPlay`/`clip`
are `@Volatile`, and `release()` cancels the scope.

## 3. Claimed but not delivered

### 3.1 `O05-01`/`O07-01` — the one-pass hash does not happen (ADR-250 is wrong)

`MultiStreamDispatcher.send()` still begins with
`resolvedDigest = fileSha256Hex?.let(..) ?: chunker.hashOnly(source)` — a full pre-read of the file — and no
caller ever passes `fileSha256Hex` (neither `executeSend` call site does). So an outgoing transfer is still
read end to end **before** the first chunk is read again and sent, exactly as on `dev`.

The code change is real but only inside the stream: `ChunkStream` skips the prefix digest check on resume
and `hashOnly` uses a 1 MB buffer. **The architectural reason:** `FILE_START` carries the whole-file digest
and is announced on every channel before any chunk, so a single pass would need either a new frame that
carries the digest after the fact, or buffering. ADR-250 describes the incremental digest as if the value
were already known up front. **A single pass is a wire-shape change**
(§8 of the plan requires an owner answer for wire changes); the honest current statement is "reads the file
twice, with a 1 MB hash buffer". `PERF-T04`.

### 3.2 `O23-02` — the seek is inert in production

No production `SeekableChunkSource` exists (§2.1). Resume now reads and discards the prefix (`skipTo` on a
non-seekable stream) but no longer hashes it; the gigabytes of I/O ADR-252 promises still happen.
`PERF-T05` (implement `openAt` for the filesystem path, keep the byte-stream fallback for `content://`).

### 3.3 `O07-02` — the `.part` staging never fires

`ReceivePipeline` renames only when `localPath.endsWith(".part")`, but nothing in the 1:1 receive path ever
names the destination that way — the host passes the final display name. The `.part` users in the tree are
the swarm piece storage and the hub file store, both unrelated. `PERF-T06` (host-side staging name) or
delete the block.

### 3.4 `O23-03` — the FAT32 check is inert

`isFat32Volume: () -> Boolean = { false }` is never injected by a host, so the 4 GiB refusal can never
trigger. The pre-allocation half is wired and correct (it only grows, never truncates). `PERF-T07`.

### 3.5 `O23-06` — buffered `saveToGallery` not done

Not in the diff and not in the progress fragment. `saveToGallery` still copies byte by byte. `PERF-T08`.

## 4. Test gaps this review leaves open

- The stream's `resume seeding` test asserts counters only (§2.1). Any new resume work must assert payloads.
- `ACK_DRAIN_GRACE_MS` 15 s → 3 s is not measured over a real link; a slow Wi-Fi tail could now end a
  session that merely ACKed late. The endgame makes that recoverable, not free. `PERF-T09`.
- The endgame sends into feeds whose workers may all have exited; that path has no dedicated test.
