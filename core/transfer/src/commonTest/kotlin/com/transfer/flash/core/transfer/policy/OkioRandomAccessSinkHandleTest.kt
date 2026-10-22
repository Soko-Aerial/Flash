package com.transfer.flash.core.transfer.policy

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OkioRandomAccessSinkHandleTest {

    @Test
    fun overwritingLargerExistingFileTruncatesStaleTrailingBytes() {
        val tempPath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "flash-sink-test-${Random.nextLong()}.tmp"
        try {
            val initialSize = 5 * 1024 * 1024 // 5 MB
            val newExpectedSize = 1 * 1024 * 1024L // 1 MB

            // 1. Create a 5 MB file on disk
            FileSystem.SYSTEM.write(tempPath) {
                write(ByteArray(initialSize) { 0x55.toByte() })
            }
            assertEquals(initialSize.toLong(), FileSystem.SYSTEM.metadata(tempPath).size)

            // 2. Open with OkioRandomAccessSinkHandle expecting 1 MB and overwrite
            val handle = OkioRandomAccessSinkHandle(tempPath, newExpectedSize)
            val chunk = ByteArray(1024 * 1024) { 0xAA.toByte() }
            handle.writeAt(0, chunk)
            handle.flush()
            handle.close()

            // 3. File size must be truncated to exactly 1 MB, no stale trailing bytes
            val actualSize = FileSystem.SYSTEM.metadata(tempPath).size
            assertEquals(newExpectedSize, actualSize, "File size must be truncated to expected 1 MB on overwrite of larger 5 MB file")
        } finally {
            FileSystem.SYSTEM.delete(tempPath)
        }
    }

    @Test
    fun openingHandleDoesNotGrowFileWhenSmaller() {
        val tempPath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "flash-sink-grow-test-${Random.nextLong()}.tmp"
        try {
            // Create an empty file
            FileSystem.SYSTEM.write(tempPath) { }
            assertEquals(0L, FileSystem.SYSTEM.metadata(tempPath).size)

            val expectedSize = 5 * 1024 * 1024L // 5 MB
            // ERROR-067 guard: must NOT pre-allocate/grow via resize()
            val handle = OkioRandomAccessSinkHandle(tempPath, expectedSize)
            assertEquals(0L, FileSystem.SYSTEM.metadata(tempPath).size, "Handle must not grow file before writes (ERROR-067)")
            handle.close()
        } finally {
            FileSystem.SYSTEM.delete(tempPath)
        }
    }
}
