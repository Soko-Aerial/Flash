package com.transfer.flash.core.transfer.chunked

import java.io.Closeable
import java.io.InputStream
import okio.buffer

// [ChunkSource] — the re-openable byte source this file chunks — moved to
// `commonMain/chunked/ChunkSource.kt` in Phase 13B-2 and its `open()` now returns `okio.Source`.
// The two call sites below bridge it back to the `java.io.InputStream` that [ChunkStream] still
// reads, because [ChunkStream] depends on `ChunkFrame`/`Sha256` and cannot move until 13B-3.

/** Identity + declared size of one outgoing file. */
public data class FileMeta(
    val transferId: String,
    val fileId: String,
    val fileName: String,
    val totalBytes: Long,
) {
    init {
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(fileId.isNotBlank()) { "fileId must not be blank" }
        require(fileName.isNotEmpty()) { "fileName must not be empty" }
        require(totalBytes > 0) { "totalBytes must be > 0, was $totalBytes" }
    }
}

/** Resolved chunking parameters for one transfer. */
public data class ChunkPlan(
    val totalBytes: Long,
    val chunkSize: Int,
    val totalChunks: Int,
)

/**
 * Pure chunking logic (C5.4). Default 64 KB chunks, hard bounds [MIN_CHUNK_SIZE_BYTES]–
 * [MAX_CHUNK_SIZE_BYTES], adaptive sizing from measured throughput ([adaptiveSize]).
 *
 * ## Whole-file hash strategy
 *
 * `FILE_START` carries `fileSha256Hex`, so the digest must exist before the first frame is sent
 * (LocalSend likewise requires sha256 at `/prepare-upload` time:
 * https://github.com/localsend/protocol §4.1). Callers either supply a known hex digest or let
 * [hashOnly] run a cheap streaming pre-pass; [openChunkStream] then recomputes the digest during
 * the single chunking pass and cross-checks both passes agree (`expectFileSha256Hex`), which also
 * validates source identity between resume attempts (§18).
 *
 * ## Adaptive curve (researched)
 *
 * Signals: measured recent throughput windows — the same signal rclone's shared
 * `chunksize.Calculator()` adapts on (https://github.com/rclone/rclone/pull/6138) and rsync's
 * historical "adaptive block size… minimum of 700 and maximum of 16K"
 * (https://lists.samba.org/archive/rsync/2001-November/000595.html). Small chunks bound the
 * retransmit/resume granularity on slow lossy paths (BitTorrent v2 fixed 16 KiB blocks for
 * exactly this: https://blog.libtorrent.org/2020/09/bittorrent-v2/); large chunks amortize
 * per-frame overhead when bandwidth-delay product is high
 * (https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/10/html/network_troubleshooting_and_performance_tuning/tuning-tcp-connections-for-high-throughput).
 *
 * Curve: geometric (log-scale) interpolation between anchors — throughput ≤ 256 KiB/s maps to
 * [MIN_CHUNK_SIZE_BYTES]; ≥ 64 MiB/s maps to [MAX_CHUNK_SIZE_BYTES]; between them size grows
 * exponentially with throughput, rounded down to a 4 KiB multiple. Bounds sanity: 16 KB ≈ 11×
 * typical 1500 B MTU (far above header-amortization floor) yet well below flash erase-block
 * scales; 256 KB keeps worst-case per-chunk buffering trivial on phone RAM.
 *
 * ## Resume seek semantics (documented limitation, v1)
 *
 * Resume skips completed chunks by reading-and-discarding through the linear stream (read+hash,
 * no send). Random-access skipping via a future `SeekableSource` interface is reserved; linear
 * skip is acceptable for v1 because local flash read throughput far exceeds any LAN path.
 */
public class Chunker {

    public companion object {

        public const val MIN_CHUNK_SIZE_BYTES: Int = 16 * 1024

        public const val MAX_CHUNK_SIZE_BYTES: Int = 256 * 1024

        public const val DEFAULT_CHUNK_SIZE_BYTES: Int = 64 * 1024

        /** Sizes snap to this multiple so plans stay page-aligned and diffable. */
        public const val SIZE_GRANULARITY_BYTES: Int = 4 * 1024

        private const val LOW_ANCHOR_BYTES_PER_SEC: Double = 256.0 * 1024 // ~2 Mbit/s

        private const val HIGH_ANCHOR_BYTES_PER_SEC: Double = 64.0 * 1024 * 1024

        /** Clamps an arbitrary requested chunk size into the hard bounds. */
        public fun clamp(requestedChunkSize: Int): Int =
            requestedChunkSize.coerceIn(MIN_CHUNK_SIZE_BYTES, MAX_CHUNK_SIZE_BYTES)

        /** ceil(totalBytes / chunkSize); throws if the chunk count would overflow Int. */
        public fun totalChunks(totalBytes: Long, chunkSize: Int): Int {
            require(totalBytes > 0) { "totalBytes must be > 0, was $totalBytes" }
            require(chunkSize > 0) { "chunkSize must be > 0" }
            // Overflow-safe ceil division: (totalBytes + chunkSize - 1) would wrap for
            // near-Long.MAX_VALUE inputs; subtract-first never overflows.
            val countLong = (totalBytes - 1) / chunkSize + 1
            require(countLong <= Int.MAX_VALUE) {
                "chunk count $countLong overflows Int for totalBytes=$totalBytes chunkSize=$chunkSize"
            }
            return countLong.toInt()
        }

        /**
         * Pure adaptive-size function; see class KDoc for the researched curve.
         * Non-positive/NaN inputs fall back to [MIN_CHUNK_SIZE_BYTES].
         */
        public fun adaptiveSize(recentThroughputBytesPerSec: Double): Int {
            if (recentThroughputBytesPerSec.isNaN() || recentThroughputBytesPerSec <= 0.0) {
                return MIN_CHUNK_SIZE_BYTES
            }
            if (recentThroughputBytesPerSec <= LOW_ANCHOR_BYTES_PER_SEC) return MIN_CHUNK_SIZE_BYTES
            if (recentThroughputBytesPerSec >= HIGH_ANCHOR_BYTES_PER_SEC) return MAX_CHUNK_SIZE_BYTES
            val lowLn = kotlin.math.ln(LOW_ANCHOR_BYTES_PER_SEC)
            val highLn = kotlin.math.ln(HIGH_ANCHOR_BYTES_PER_SEC)
            val fraction = (kotlin.math.ln(recentThroughputBytesPerSec) - lowLn) / (highLn - lowLn)
            val span = MAX_CHUNK_SIZE_BYTES - MIN_CHUNK_SIZE_BYTES
            val raw = MIN_CHUNK_SIZE_BYTES + (span * fraction).toInt()
            val snapped = (raw / SIZE_GRANULARITY_BYTES) * SIZE_GRANULARITY_BYTES
            return snapped.coerceIn(MIN_CHUNK_SIZE_BYTES, MAX_CHUNK_SIZE_BYTES)
        }
    }

    /**
     * Validates metadata against the framing rules and resolves [ChunkPlan]. Throws
     * [IllegalArgumentException] on programmer error (bad size bounds, non-positive totals).
     */
    public fun plan(meta: FileMeta, requestedChunkSize: Int = DEFAULT_CHUNK_SIZE_BYTES): ChunkPlan {
        val chunkSize = clamp(requestedChunkSize)
        val totalChunks = totalChunks(meta.totalBytes, chunkSize)
        return ChunkPlan(
            totalBytes = meta.totalBytes,
            chunkSize = chunkSize,
            totalChunks = totalChunks,
        )
    }

    /** Builds the FILE_START frame once the whole-file digest is known. */
    public fun fileStart(meta: FileMeta, plan: ChunkPlan, fileSha256Hex: String): ChunkFrame.FileStart =
        ChunkFrame.FileStart(
            transferId = meta.transferId,
            fileId = meta.fileId,
            fileName = meta.fileName,
            totalBytes = meta.totalBytes,
            totalChunks = plan.totalChunks,
            chunkSize = plan.chunkSize,
            fileSha256Hex = fileSha256Hex,
        )

    /**
     * Streaming hash-only pre-pass (constant memory). Used when the caller has no pre-computed
     * whole-file digest for `FILE_START`.
     */
    public fun hashOnly(source: ChunkSource): String {
        source.open().buffer().inputStream().use { stream ->
            val digest = IncrementalSha256()
            val buffer = ByteArray(DEFAULT_CHUNK_SIZE_BYTES)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (n > 0) digest.update(buffer, 0, n)
            }
            return digest.digestHex()
        }
    }

    /**
     * Opens the lazy chunk-emitting iterator over [source]: one fresh stream, one pass,
     * per-chunk SHA-256 embedded in every frame, running whole-file digest accumulated as
     * chunks are produced.
     *
     * @param expectFileSha256Hex when non-null, exhaustion verifies the streaming digest matches
     * (throws [IllegalStateException] on mismatch — protects against the source changing between
     * the hash pass and the chunk pass, e.g. across a resume).
     * @throws IllegalStateException if the source yields fewer/more bytes than
     * [ChunkPlan.totalBytes], or the cross-check above fails.
     */
    public fun openChunkStream(
        source: ChunkSource,
        meta: FileMeta,
        plan: ChunkPlan,
        expectFileSha256Hex: String? = null,
    ): ChunkStream =
        ChunkStream(source.open().buffer().inputStream(), meta, plan, expectFileSha256Hex)
}

/**
 * Lazy pull-based CHUNK iterator (constant memory). Consume fully for a clean finish; always
 * [close] when aborting early (send failure, cancellation) to release the underlying stream.
 */
public class ChunkStream internal constructor(
    private val stream: InputStream,
    private val meta: FileMeta,
    private val plan: ChunkPlan,
    private val expectFileSha256Hex: String?,
) : Iterator<ChunkFrame.Chunk>, Closeable {

    private val buffer = ByteArray(plan.chunkSize)
    private val fileDigest = IncrementalSha256()

    private var nextIndex = 0
    private var bytesRead = 0L
    private var buffered: ChunkFrame.Chunk? = null
    private var closed = false
    private var endValidated = false

    override fun hasNext(): Boolean {
        check(!closed) { "ChunkStream already closed" }
        fill()
        return buffered != null
    }

    override fun next(): ChunkFrame.Chunk {
        check(!closed) { "ChunkStream already closed" }
        fill()
        val frame = buffered ?: throw NoSuchElementException("chunk stream exhausted")
        buffered = null
        return frame
    }

    /** Valid only after full consumption (or asserted via [expectFileSha256Hex]). */
    public val observedFileSha256Hex: String
        get() = fileDigest.digestHex()

    public val emittedChunks: Int
        get() = nextIndex

    override fun close() {
        if (!closed) {
            closed = true
            try {
                stream.close()
            } catch (_: java.io.IOException) {
                // Best-effort close; abort paths must not mask the original failure.
            }
        }
    }

    private fun fill() {
        if (buffered != null || endValidated) return
        if (nextIndex >= plan.totalChunks) {
            validateEnd()
            return
        }
        val expectedLength = expectedChunkLength(nextIndex)
        readFully(expectedLength)
        val data = buffer.copyOf(expectedLength)
        bytesRead += expectedLength
        fileDigest.update(data)
        val chunkHash = Sha256.digest(data)
        buffered = ChunkFrame.Chunk(
            transferId = meta.transferId,
            fileId = meta.fileId,
            index = nextIndex,
            data = data,
            chunkSha256 = chunkHash,
        )
        nextIndex++
        if (nextIndex == plan.totalChunks) validateEnd()
    }

    private fun expectedChunkLength(index: Int): Int {
        val lastFull = (index + 1).toLong() * plan.chunkSize
        return if (lastFull <= plan.totalBytes) plan.chunkSize
        else (plan.totalBytes - index.toLong() * plan.chunkSize).toInt()
    }

    private fun readFully(length: Int) {
        var filled = 0
        while (filled < length) {
            val n = stream.read(buffer, filled, length - filled)
            if (n < 0) {
                throw IllegalStateException(
                    "source ended after $bytesRead bytes; expected ${plan.totalBytes} " +
                        "(chunk ${nextIndex}/${plan.totalChunks})",
                )
            }
            filled += n
        }
    }

    private fun validateEnd() {
        if (endValidated) return
        val extra = stream.read()
        check(bytesRead == plan.totalBytes && extra < 0) {
            "source length mismatch: read=$bytesRead expected=${plan.totalBytes} extraByte=${extra >= 0}"
        }
        val observed = fileDigest.digestHex()
        if (expectFileSha256Hex != null &&
            !Sha256.hexEqualsConstantTime(observed, Sha256.normalizeHex(expectFileSha256Hex))
        ) {
            throw IllegalStateException(
                "whole-file digest changed between passes (resume/source-identity guard)",
            )
        }
        endValidated = true
    }
}
