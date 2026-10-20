package com.transfer.flash.core.enterprise.files

/**
 * The file-system primitives the sealed-file store needs, as the smallest `expect` surface (the [PlatformCrypto]
 * pattern of `:core:security`: logic in `commonMain`, thin duplicated JVM actuals, no `jvmAndAndroidMain`, CONVENTIONS R5).
 *
 * Paths are built by [DiskHubFileStorage] from a host-supplied root and a validated file id only. Every function either
 * does what it says or throws an I/O exception; the callers turn a throw into [HubFileError.LocalStorage].
 */
internal expect object HubFileDisk {
    /** Creates [path] and its parents when missing. */
    fun ensureDirectory(path: String)

    /** The whole file, or null when it does not exist or is longer than [maxBytes]. */
    fun readAll(path: String, maxBytes: Int): ByteArray?

    /** Writes [bytes] to a sibling temporary file, syncs it, then replaces [path] with it. */
    fun replaceAtomically(path: String, bytes: ByteArray)

    /** Deletes [path] when present. */
    fun delete(path: String)

    /** Names (not paths) of the regular files directly inside [directory]; empty when it does not exist. */
    fun list(directory: String): List<String>

    /** Writes [bytes] at [offset] of [path] (created when missing) and syncs before returning. */
    fun writeAt(path: String, offset: Long, bytes: ByteArray)

    /** Exactly [length] bytes at [offset]; throws when the file is shorter. */
    fun readAt(path: String, offset: Long, length: Int): ByteArray

    /** Length of [path], or -1 when it does not exist. */
    fun length(path: String): Long

    /** Moves [from] to [to], replacing [to]. */
    fun move(from: String, to: String)

    /** Bytes still free for this app on the volume holding [directory]. */
    fun usableSpace(directory: String): Long
}
