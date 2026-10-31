package com.transfer.flash.core.transfer.policy

import okio.Path

/**
 * Pre-allocates disk storage space up to [expectedTotalBytes] on the file at [path].
 *
 * Uses OS-level pre-allocation (posix_fallocate/ftruncate on Android, setLength on JVM)
 * to avoid allocating large contiguous buffers on the ART heap (ERROR-067).
 */
internal expect fun platformPreallocate(path: Path, expectedTotalBytes: Long)
