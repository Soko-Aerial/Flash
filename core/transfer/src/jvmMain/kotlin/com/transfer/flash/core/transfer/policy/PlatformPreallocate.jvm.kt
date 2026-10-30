package com.transfer.flash.core.transfer.policy

import okio.Path
import java.io.RandomAccessFile

internal actual fun platformPreallocate(path: Path, expectedTotalBytes: Long) {
    if (expectedTotalBytes <= 0L) return
    runCatching {
        val file = path.toFile()
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < expectedTotalBytes) {
                raf.setLength(expectedTotalBytes)
            }
        }
    }
}
