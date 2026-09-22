package com.transfer.flash.core.common.perf

/**
 * File-transfer chunking, multi-stream concurrency, and memory-buffer bounds for one [FlashPerformanceMode].
 *
 * Designed to pace memory, CPU, and socket count to device constraints:
 * - [LOW]: Constrained memory and CPU (<512MB RAM, 2.4GHz b/g/n, IoT/POS). Single stream to eliminate
 *   context switching and multi-socket overhead; 32KB bounded chunks; shallow queue depths (2 / 4 frames)
 *   capping in-flight heap footprint to <250 KB; video frame thumbnail extraction disabled.
 * - [MEDIUM]: Balanced desktop/mid-tier mobile. 2 streams, 64KB base chunks, standard queue depths (8 / 16).
 * - [HIGH]: Modern workstations and flagship mobile. 4 streams (scalable), 128KB base chunks (adaptive
 *   up to 1MB), deep queues (16 / 64) to saturate multi-gigabit and 5GHz LAN links.
 */
public data class FlashTransferProfile(
    /** Number of parallel stream sockets used for chunked transfer. */
    public val streamCount: Int,
    /** Base chunk size in bytes planned for outgoing files. */
    public val chunkSizeBytes: Int,
    /** Per-worker feed queue depth. */
    public val feedBufferFrames: Int,
    /** Shared redistribution queue depth across all workers. */
    public val sharedBufferFrames: Int,
    /** Upper ceiling for adaptive chunk sizing on high-speed links. */
    public val maxAdaptiveChunkSizeBytes: Int,
    /** Whether to decode heavy video thumbnail frames on received media cards. */
    public val allowVideoThumbnails: Boolean,
    /** Maximum image downsample dimension for media message bubbles. */
    public val maxImagePreviewDimension: Int,
    /** Target SQLite memory page cache size in kilobytes. */
    public val sqliteCacheSizeKb: Int,
) {
    public companion object {
        /**
         * Ultra-low / low resource constraints: single-stream, 32KB chunks, tiny queues, no video thumbs.
         */
        public val LOW: FlashTransferProfile = FlashTransferProfile(
            streamCount = 1,
            chunkSizeBytes = 32 * 1024, // 32 KB
            feedBufferFrames = 2,
            sharedBufferFrames = 4,
            maxAdaptiveChunkSizeBytes = 64 * 1024,
            allowVideoThumbnails = false,
            maxImagePreviewDimension = 256,
            sqliteCacheSizeKb = 1024, // 1 MB
        )

        /**
         * Standard balanced configuration: 2 streams, 64KB chunks, standard queue depths.
         */
        public val MEDIUM: FlashTransferProfile = FlashTransferProfile(
            streamCount = 2,
            chunkSizeBytes = 64 * 1024, // 64 KB
            feedBufferFrames = 8,
            sharedBufferFrames = 16,
            maxAdaptiveChunkSizeBytes = 256 * 1024,
            allowVideoThumbnails = true,
            maxImagePreviewDimension = 512,
            sqliteCacheSizeKb = 4096, // 4 MB
        )

        /**
         * High-performance configuration: 4 streams, 128KB chunks, deep queues for pipe saturation.
         */
        public val HIGH: FlashTransferProfile = FlashTransferProfile(
            streamCount = 4,
            chunkSizeBytes = 64 * 1024, // 64 KB standard base chunk
            feedBufferFrames = 16,
            sharedBufferFrames = 64,
            maxAdaptiveChunkSizeBytes = 1024 * 1024, // 1 MB
            allowVideoThumbnails = true,
            maxImagePreviewDimension = 1024,
            sqliteCacheSizeKb = 16384, // 16 MB
        )
    }
}
