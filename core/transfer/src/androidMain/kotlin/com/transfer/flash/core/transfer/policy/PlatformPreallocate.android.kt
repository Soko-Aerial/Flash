package com.transfer.flash.core.transfer.policy

import android.os.Build
import android.system.Os
import okio.Path
import java.io.RandomAccessFile

internal actual fun platformPreallocate(path: Path, expectedTotalBytes: Long) {
    if (expectedTotalBytes <= 0L) return
    runCatching {
        val file = path.toFile()
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < expectedTotalBytes) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        Os.posix_fallocate(raf.fd, 0L, expectedTotalBytes)
                    } catch (_: Throwable) {
                        try {
                            Os.ftruncate(raf.fd, expectedTotalBytes)
                        } catch (_: Throwable) {
                            raf.setLength(expectedTotalBytes)
                        }
                    }
                } else {
                    raf.setLength(expectedTotalBytes)
                }
            }
        }
    }
}
