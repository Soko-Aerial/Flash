package com.transfer.flash.core.engine.swarm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class JvmPieceStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var partialDir: File
    private lateinit var destDir: File
    private lateinit var storage: JvmPieceStorage

    @Before
    fun setUp() {
        partialDir = tempFolder.newFolder("partial")
        destDir = tempFolder.newFolder("dest")
        storage = JvmPieceStorage(partialDir, destDir)
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    @Test
    fun `random-offset writes and read-back`() = runBlocking {
        val totalSize = 1024L
        val handle = storage.openPartial("test-item", totalSize)
        assertNotNull(handle)
        requireNotNull(handle)

        val chunk1 = "Hello, ".toByteArray()
        val chunk2 = "Swarm World!".toByteArray()

        handle.writeAt(0L, chunk1, chunk1.size)
        handle.writeAt(chunk1.size.toLong(), chunk2, chunk2.size)
        handle.sync()

        val buf = ByteArray(chunk1.size + chunk2.size)
        val readCount = handle.readAt(0L, buf, buf.size)
        assertEquals(buf.size, readCount)
        assertEquals("Hello, Swarm World!", buf.decodeToString())

        handle.close()
    }

    @Test
    fun `overlapping writes`() = runBlocking {
        val handle = requireNotNull(storage.openPartial("overlap-test", 100L))

        val first = "AAAAA".toByteArray()
        val overwrite = "BB".toByteArray()

        handle.writeAt(10L, first, first.size)
        // Overwrite middle two bytes: AA[BB]A
        handle.writeAt(12L, overwrite, overwrite.size)
        handle.sync()

        val buf = ByteArray(5)
        handle.readAt(10L, buf, 5)
        assertEquals("AABBA", buf.decodeToString())

        handle.close()
    }

    @Test
    fun `openSource and identity detection on modification`() = runBlocking {
        val sourceFile = tempFolder.newFile("source.bin")
        sourceFile.writeBytes("Initial source content".toByteArray())

        val sourceHandle = storage.openSource(sourceFile.absolutePath)
        assertNotNull(sourceHandle)
        requireNotNull(sourceHandle)

        val id1 = sourceHandle.identity()
        assertNotNull(id1)
        assertEquals(sourceFile.length(), id1?.sizeBytes)

        // Modify source file
        sourceFile.appendBytes(" appended".toByteArray())
        val id2 = sourceHandle.identity()
        assertNotNull(id2)
        assertEquals(sourceFile.length(), id2?.sizeBytes)
        assertTrue(id2!!.sizeBytes > id1!!.sizeBytes)

        sourceHandle.close()
    }

    @Test
    fun `finalize moves file, verifies sha256 and handles name collision`() = runBlocking {
        val content = "This is a completed file payload for finalize test.".toByteArray()
        val contentHash = sha256(content)

        // First download
        val handle1 = requireNotNull(storage.openPartial("download-1", content.size.toLong()))
        handle1.writeAt(0L, content, content.size)
        handle1.sync()
        handle1.close()

        val res1 = storage.finalize("download-1", "doc.txt", "text/plain", contentHash)
        assertTrue(res1.ok)
        assertNotNull(res1.finalPath)
        val file1 = File(res1.finalPath!!)
        assertTrue(file1.exists())
        assertEquals("doc.txt", file1.name)
        assertArrayEquals(content, file1.readBytes())

        // Second download with same file name should resolve collision: doc (1).txt
        val handle2 = requireNotNull(storage.openPartial("download-2", content.size.toLong()))
        handle2.writeAt(0L, content, content.size)
        handle2.sync()
        handle2.close()

        val res2 = storage.finalize("download-2", "doc.txt", "text/plain", contentHash)
        assertTrue(res2.ok)
        val file2 = File(res2.finalPath!!)
        assertTrue(file2.exists())
        assertEquals("doc (1).txt", file2.name)

        // Third download with same file name should resolve collision: doc (2).txt
        val handle3 = requireNotNull(storage.openPartial("download-3", content.size.toLong()))
        handle3.writeAt(0L, content, content.size)
        handle3.sync()
        handle3.close()

        val res3 = storage.finalize("download-3", "doc.txt", "text/plain", contentHash)
        assertTrue(res3.ok)
        val file3 = File(res3.finalPath!!)
        assertTrue(file3.exists())
        assertEquals("doc (2).txt", file3.name)
    }

    @Test
    fun `finalize fails on sha256 mismatch`() = runBlocking {
        val content = "Corrupted data".toByteArray()
        val badHash = sha256("Expected different content".toByteArray())

        val handle = requireNotNull(storage.openPartial("corrupt-item", content.size.toLong()))
        handle.writeAt(0L, content, content.size)
        handle.sync()
        handle.close()

        val res = storage.finalize("corrupt-item", "doc.txt", "text/plain", badHash)
        assertFalse(res.ok)
        assertTrue(res.errorMessage?.contains("mismatch") == true)
        // Partial file should still exist for potential re-fetch
        assertTrue(File(partialDir, "corrupt-item.part").exists())
    }

    @Test
    fun `deletePartial deletes the file`() = runBlocking {
        val handle = requireNotNull(storage.openPartial("to-delete", 100L))
        handle.writeAt(0L, byteArrayOf(1, 2, 3), 3)
        handle.close()

        val partFile = File(partialDir, "to-delete.part")
        assertTrue(partFile.exists())

        storage.deletePartial("to-delete")
        assertFalse(partFile.exists())
    }

    @Test
    fun `path traversal attempt is rejected`() = runBlocking {
        try {
            storage.openPartial("../../../escape", 100L)
            fail("Expected exception for path traversal escape")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `freeBytesFor returns usable space`() = runBlocking {
        val freeBytes = storage.freeBytesFor("any")
        assertTrue(freeBytes > 0)
    }

    @Test
    fun `sanitizeFileName handles Windows reserved names and illegal characters`() {
        assertEquals("_CON.txt", JvmPieceStorage.sanitizeFileName("CON.txt"))
        assertEquals("_aux.dat", JvmPieceStorage.sanitizeFileName("aux.dat"))
        assertEquals("hello_world_.txt", JvmPieceStorage.sanitizeFileName("hello:world?.txt"))
        assertEquals("doc.pdf", JvmPieceStorage.sanitizeFileName("doc.pdf...  "))
        assertEquals("received.bin", JvmPieceStorage.sanitizeFileName("   ..   "))
    }

    @Test
    fun `finalize handles windows reserved names and illegal characters`() = runBlocking {
        val content = "Reserved name test content".toByteArray()
        val contentHash = sha256(content)

        val handle = requireNotNull(storage.openPartial("reserved-item", content.size.toLong()))
        handle.writeAt(0L, content, content.size)
        handle.sync()
        handle.close()

        val res = storage.finalize("reserved-item", "CON.txt", "text/plain", contentHash)
        assertTrue(res.ok)
        assertNotNull(res.finalPath)
        val file = File(res.finalPath!!)
        assertTrue(file.exists())
        assertEquals("_CON.txt", file.name)
    }

    @Test
    fun `readAt returns -1 past EOF`() = runBlocking {
        val content = "Small".toByteArray()
        val handle = requireNotNull(storage.openPartial("eof-test", content.size.toLong()))
        handle.writeAt(0L, content, content.size)
        handle.sync()

        val buf = ByteArray(10)
        val n = handle.readAt(100L, buf, buf.size)
        assertEquals(-1, n)

        handle.close()
    }

    private suspend fun stagePartial(key: String, content: ByteArray) {
        val h = requireNotNull(storage.openPartial(key, content.size.toLong()))
        h.writeAt(0L, content, content.size)
        h.sync()
        h.close()
    }

    @Test
    fun `concurrent finalizes of the same name never overwrite each other`() = runBlocking {
        val n = 16
        val contents = (0 until n).map { "payload number $it".toByteArray() }
        contents.forEachIndexed { i, c -> stagePartial("race-$i", c) }

        val results = (0 until n).map { i ->
            async(Dispatchers.Default) {
                storage.finalize("race-$i", "photo.jpg", "image/jpeg", sha256(contents[i]))
            }
        }.map { it.await() }

        assertTrue(results.all { it.ok })
        val paths = results.map { it.finalPath!! }
        assertEquals("every finalize must get its own file", n, paths.toSet().size)
        // Each file holds exactly the bytes of the transfer that reported it.
        results.forEachIndexed { i, r -> assertArrayEquals("file for transfer $i", contents[i], File(r.finalPath!!).readBytes()) }
        assertEquals(n, destDir.listFiles()!!.size)
    }

    @Test
    fun `finalize never replaces a file the user already has, and leaves no placeholder behind on failure`() = runBlocking {
        val userFile = File(destDir, "doc.txt").apply { writeText("MY OWN DOCUMENT") }
        val content = "incoming".toByteArray()
        stagePartial("incoming", content)

        val ok = storage.finalize("incoming", "doc.txt", "text/plain", sha256(content))
        assertTrue(ok.ok)
        assertEquals("doc (1).txt", File(ok.finalPath!!).name)
        assertEquals("MY OWN DOCUMENT", userFile.readText())

        // A failed finalize (hash mismatch) creates nothing in the destination.
        stagePartial("bad", "x".toByteArray())
        val before = destDir.listFiles()!!.map { it.name }.toSet()
        assertFalse(storage.finalize("bad", "doc.txt", "text/plain", sha256("y".toByteArray())).ok)
        assertEquals(before, destDir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `a long CJK name is cut by bytes, keeps its extension and finalizes`() = runBlocking {
        val name = "文".repeat(100) + ".docx"
        val sanitized = JvmPieceStorage.sanitizeFileName(name)
        assertTrue(sanitized.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(sanitized.endsWith(".docx"))

        val content = "cjk".toByteArray()
        stagePartial("cjk", content)
        val res = storage.finalize("cjk", name, "application/octet-stream", sha256(content))
        assertTrue(res.errorMessage, res.ok)
        assertEquals(sanitized, File(res.finalPath!!).name)
    }

    @Test
    fun `the partial directory is created before it is canonicalized`() = runBlocking {
        val missing = File(tempFolder.root, "not/yet/created/partial")
        assertFalse(missing.exists())
        val s = JvmPieceStorage(missing, destDir)
        val h = s.openPartial("fresh", 10L)
        assertNotNull(h)
        h!!.close()
        assertTrue(File(missing.canonicalFile, "fresh.part").exists())
        assertTrue(s.freeBytesFor("fresh") > 0)
    }

    @Test
    fun `on Windows containment ignores case and accepts a differently spelled partial directory`() = runBlocking {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").lowercase().contains("windows"))
        val odd = File(partialDir.path.uppercase())
        // Same directory, different spelling (NTFS is case-insensitive); canonicalization must agree with the prefix check.
        val s = JvmPieceStorage(odd, destDir)
        val h = s.openPartial("case-test", 10L)
        assertNotNull(h)
        h!!.close()
        assertTrue(File(partialDir, "case-test.part").exists())

        // 8.3 short-name spelling of the temp folder, if the volume has short names enabled.
        val short = runCatching { ProcessBuilder("cmd", "/c", "for %I in (\"${partialDir.path}\") do @echo %~sI").start().inputStream.bufferedReader().readText().trim() }.getOrNull()
        if (!short.isNullOrBlank() && File(short).isDirectory && short != partialDir.path) {
            val s2 = JvmPieceStorage(File(short), destDir)
            val h2 = s2.openPartial("short-test", 10L)
            assertNotNull("8.3 spelled partial dir must still pass containment", h2)
            h2!!.close()
        }
    }

    @Test
    fun `purgeOrphanedPartials deletes unreferenced part files and preserves active ones`() = runBlocking {
        val f1 = File(partialDir, "active_item.part").apply { writeBytes(ByteArray(100)) }
        val f2 = File(partialDir, "orphan_item1.part").apply { writeBytes(ByteArray(200)) }
        val f3 = File(partialDir, "orphan_item2.part").apply { writeBytes(ByteArray(300)) }
        val unrelated = File(partialDir, "other_file.txt").apply { writeBytes(ByteArray(50)) }

        assertTrue(f1.exists())
        assertTrue(f2.exists())
        assertTrue(f3.exists())
        assertTrue(unrelated.exists())

        storage.purgeOrphanedPartials(setOf("active_item"))

        assertTrue("Active partial file must be preserved", f1.exists())
        assertFalse("Orphaned partial file 1 must be deleted", f2.exists())
        assertFalse("Orphaned partial file 2 must be deleted", f3.exists())
        assertTrue("Non-.part file must remain untouched", unrelated.exists())
    }
}
