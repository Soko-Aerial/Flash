package com.transfer.flash.core.transfer.policy

import com.transfer.flash.core.transfer.concurrent.PlatformLock
import kotlin.concurrent.Volatile
import okio.FileHandle
import okio.FileSystem
import okio.Path

/**
 * A seekable write handle that can write chunks at arbitrary byte offsets.
 *
 * Phase 13B-2 (D10 = Option A) moved this out of `androidMain/policy/DestinationPolicy.kt`. Two
 * things changed with it:
 *
 * 1. The supertype went from `java.io.Closeable` to [AutoCloseable] (`kotlin.AutoCloseable`,
 *    stable common API since Kotlin 2.0, and an actual typealias for `java.lang.AutoCloseable`
 *    on the JVM). `close()` and `use { }` keep working for every consumer in this repo, which
 *    holds these behind `RandomAccessSinkHandle`-typed maps; a third party who assigned one to a
 *    `java.io.Closeable` variable is the one ABI break here. Recorded for Phase 24's release
 *    notes.
 * 2. `DestinationPolicy.openSinkHandle` — the only producer inside this module — stays in
 *    `androidMain`, because its `DestinationTarget` parameter is `internal` and `explicitApi()`
 *    (R7) forbids a public signature over an internal type. It is not part of 13B-2's four seams.
 */
public interface RandomAccessSinkHandle : AutoCloseable {
    /**
     * Writes [data] at the specified [byteOffset].
     */
    public fun writeAt(byteOffset: Long, data: ByteArray)

    /**
     * Flushes buffered writes to underlying storage.
     */
    public fun flush()

    /**
     * Returns true if the sink handle is valid and ready for writes.
     */
    public val isOpen: Boolean
}

/**
 * Multiplatform filesystem implementation of [RandomAccessSinkHandle] over [okio.FileHandle].
 *
 * This is the whole point of D10 = Option A. `PHASE-13B-desktop-fileio.md` predicted 13B-2 would
 * need "three small `jvmMain` files"; because okio is itself multiplatform, the implementation is
 * **common** instead, so desktop and Android run the same code path rather than two ports of it.
 *
 * Behaviour is byte-for-byte the pre-13B-2 `RandomAccessFile` implementation, verified against
 * okio 3.4.0's bytecode rather than assumed:
 *
 * | this class            | okio 3.4.0 `JvmFileHandle`     | pre-13B-2                     |
 * |-----------------------|--------------------------------|-------------------------------|
 * | `openReadWrite(path)` | `RandomAccessFile(file, "rw")` | `RandomAccessFile(file, "rw")`|
 * | `size()` / `resize()` | `length()` / `setLength()`     | `length()` / `setLength()`    |
 * | `write(off, …)`       | `seek(off)` + `write(…)`       | `seek(off)` + `write(…)`      |
 * | `flush()`             | `fd.sync()`                    | `fd.sync()`                   |
 *
 * `@Synchronized` could not come along — it resolves to `kotlin.jvm.Synchronized` and is JVM-only
 * (CONVENTIONS.md R6). The three guarded methods now take [PlatformLock], the 13B-1 seam that
 * exists for exactly this. `isOpen` keeps its non-blocking `@Volatile` read (`kotlin.concurrent`,
 * which R6.1's third gate scan explicitly sanctions in `commonMain`).
 */
public class OkioRandomAccessSinkHandle(
    path: Path,
    expectedTotalBytes: Long,
    fileSystem: FileSystem = FileSystem.SYSTEM,
) : RandomAccessSinkHandle {

    private val handle: FileHandle = fileSystem.openReadWrite(path)

    private val lock = PlatformLock()

    @Volatile
    private var _isOpen = true

    override val isOpen: Boolean
        get() = _isOpen

    override fun writeAt(byteOffset: Long, data: ByteArray): Unit = lock.withLock {
        if (_isOpen) {
            handle.write(byteOffset, data, 0, data.size)
        }
    }

    override fun flush(): Unit = lock.withLock {
        if (_isOpen) {
            handle.flush()
        }
    }

    override fun close(): Unit = lock.withLock {
        if (_isOpen) {
            _isOpen = false
            try {
                handle.flush()
            } catch (_: Exception) {
            }
            handle.close()
        }
    }
}
