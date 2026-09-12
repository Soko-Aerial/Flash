package com.transfer.flash.core.transfer.policy

import com.transfer.flash.core.transfer.chunked.Sha256
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DestinationPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `FileRandomAccessSinkHandle writes chunks out of order and produces identical file`() {
        val targetFile = tempFolder.newFile("sparse_test.bin")
        val chunkSize = 16
        val totalChunks = 5
        val expectedBytes = ByteArray(chunkSize * totalChunks) { (it * 7).toByte() }

        val handle = FileRandomAccessSinkHandle(targetFile, expectedBytes.size.toLong())
        val sink = RandomAccessChunkSink(handle, chunkSize)

        // Write chunks in non-sequential order: 3, 0, 4, 1, 2
        val order = listOf(3, 0, 4, 1, 2)
        for (idx in order) {
            val chunkData = expectedBytes.copyOfRange(idx * chunkSize, (idx + 1) * chunkSize)
            sink.write(idx, chunkData)
        }

        handle.flush()
        handle.close()

        assertFalse(handle.isOpen)
        assertEquals(expectedBytes.size.toLong(), targetFile.length())
        val writtenBytes = targetFile.readBytes()
        assertArrayEquals(expectedBytes, writtenBytes)
        assertEquals(Sha256.digestHex(expectedBytes), Sha256.digestHex(writtenBytes))
    }

    @Test
    fun `RandomAccessChunkSink handles trailing partial chunk accurately`() {
        val targetFile = tempFolder.newFile("partial_test.bin")
        val chunkSize = 64
        val totalBytes = 150 // 2 full chunks (64 each) + 1 tail chunk (22 bytes)
        val expectedBytes = ByteArray(totalBytes) { (it * 3).toByte() }

        val handle = FileRandomAccessSinkHandle(targetFile, totalBytes.toLong())
        val sink = RandomAccessChunkSink(handle, chunkSize)

        // Chunk 0: 0..63
        sink.write(0, expectedBytes.copyOfRange(0, 64))
        // Chunk 2 (tail): 128..149
        sink.write(2, expectedBytes.copyOfRange(128, 150))
        // Chunk 1: 64..127
        sink.write(1, expectedBytes.copyOfRange(64, 128))

        handle.close()

        val actualBytes = targetFile.readBytes()
        assertEquals(150, actualBytes.size)
        assertArrayEquals(expectedBytes, actualBytes)
    }

    @Test
    fun `canonical path containment check rejects path traversal escapes outside received root`() {
        val rootDir = tempFolder.newFolder("FlashReceived").canonicalFile
        val validDest = File(File(rootDir, "transfer-1"), "safe.bin").canonicalFile
        assertTrue(
            "Valid child destination must pass canonical containment check",
            validDest.path.startsWith(rootDir.path + File.separator),
        )

        val escapedDest = File(rootDir, "../escaped.bin").canonicalFile
        assertFalse(
            "Escaped path must fail canonical containment check",
            escapedDest.path.startsWith(rootDir.path + File.separator),
        )
    }
}
