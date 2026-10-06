package com.transfer.flash.core.transfer.chunked

import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer

/** Outcome of comparing an assembled file with the whole-file digest the sender offered. */
public enum class WholeFileCheck {
    /** The file was read and its SHA-256 equals the offered digest. */
    MATCH,

    /** The file was read and its SHA-256 differs from the offered digest: the bytes on disk are not what was sent. */
    MISMATCH,

    /** No path, no (valid) offered digest, or the file could not be read: nothing can be said either way. */
    UNVERIFIABLE,
}

/**
 * Streams an assembled file through SHA-256 and compares it in constant time with the digest the sender put
 * in `FILE_START` (ADR-068). One implementation for every host; before it, Android and the library facade each
 * carried a private copy and the Windows desktop had none, so a damaged resumed file was reported verified there.
 *
 * Blocking, constant memory (64 KiB buffer). The caller decides on which thread to run it.
 *
 * Closes in an explicit `finally`, not `use { }`: okio 3.4.0's `Closeable` is an `expect interface` and
 * `use` is not in its common surface (see the header of `Chunker.kt`).
 */
public object WholeFileVerifier {

    private const val BUFFER_BYTES: Int = 64 * 1024

    public fun check(
        path: String?,
        expectedSha256Hex: String?,
        fileSystem: FileSystem = FileSystem.SYSTEM,
    ): WholeFileCheck {
        if (path.isNullOrBlank() || expectedSha256Hex.isNullOrBlank() || !Sha256.isValidHex(expectedSha256Hex)) {
            return WholeFileCheck.UNVERIFIABLE
        }
        val actual = try {
            val accumulator = IncrementalSha256()
            val source = fileSystem.source(path.toPath()).buffer()
            try {
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    accumulator.update(buffer, 0, read)
                }
            } finally {
                source.close()
            }
            accumulator.digestHex()
        } catch (_: IOException) {
            return WholeFileCheck.UNVERIFIABLE
        }
        return if (Sha256.hexEqualsConstantTime(actual, Sha256.normalizeHex(expectedSha256Hex))) {
            WholeFileCheck.MATCH
        } else {
            WholeFileCheck.MISMATCH
        }
    }
}
