@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HF-C4 on the real file system: the layout, atomic records, positional part writes, and ids that cannot name a path. */
class DiskHubFileStorageTest {

    private val root: File = Files.createTempDirectory("hub-files").toFile()
    private val storage = DiskHubFileStorage(root.path)
    private val id = "0123456789abcdef0123456789abcdef"

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun recordsAreReplacedWholeAndListedById() {
        storage.writeState(HubTransferDirection.UPLOAD, id, "one".encodeToByteArray())
        storage.writeState(HubTransferDirection.UPLOAD, id, "two".encodeToByteArray())
        assertContentEquals("two".encodeToByteArray(), storage.readState(HubTransferDirection.UPLOAD, id))
        assertNull(storage.readState(HubTransferDirection.DOWNLOAD, id), "directions are separate")
        File(root, "up/notes.txt").writeText("junk")
        File(root, "up/ABCDEF0123456789abcdef0123456789.state").writeText("junk")
        assertEquals(listOf(id), storage.stateIds(HubTransferDirection.UPLOAD))
        assertTrue(File(root, "up").listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "no temporary file is left")
        storage.deleteState(HubTransferDirection.UPLOAD, id)
        assertNull(storage.readState(HubTransferDirection.UPLOAD, id))
    }

    @Test
    fun anOversizedRecordIsNotReadIntoMemory() {
        File(root, "down").mkdirs()
        File(root, "down/$id.state").writeBytes(ByteArray(64 * 1024))
        assertNull(storage.readState(HubTransferDirection.DOWNLOAD, id))
    }

    @Test
    fun partsAreWrittenAtTheirOffsetInAnyOrderAndFinishedByAMove() {
        storage.writePart(id, 10, ByteArray(5) { 2 })
        storage.writePart(id, 0, ByteArray(10) { 1 })
        assertContentEquals(ByteArray(10) { 1 } + ByteArray(5) { 2 }, storage.readPart(id, 0, 15))
        assertFailsWith<Exception> { storage.readPart(id, 10, 50) }
        val path = storage.finishPart(id)
        assertTrue(File(path).isFile)
        assertEquals(15L, File(path).length())
        assertFalse(File(root, "down/$id.part").exists())
        assertTrue(storage.usableBytes() > 0)
    }

    @Test
    fun anIdThatIsNotAFileIdNeverBecomesAPath() {
        for (bad in listOf("../../escape", "0123456789abcdef0123456789abcde/", id.uppercase(), "", "$id/../x")) {
            assertFailsWith<IllegalArgumentException> { storage.writeState(HubTransferDirection.UPLOAD, bad, ByteArray(1)) }
            assertFailsWith<IllegalArgumentException> { storage.writePart(bad, 0, ByteArray(1)) }
        }
        assertFalse(File(root.parentFile, "escape.state").exists())
    }

    @Test
    fun aDiskSourceReadsExactRangesAndReportsAMissingFile() {
        val file = File(root, "source.bin").apply { writeBytes(ByteArray(100) { it.toByte() }) }
        val source = DiskHubFileSource(file.path)
        assertEquals(100L, source.size)
        assertContentEquals(ByteArray(10) { (it + 90).toByte() }, source.read(90, 10))
        assertFailsWith<Exception> { source.read(95, 10) }
        assertEquals(-1L, DiskHubFileSource(File(root, "missing.bin").path).size)
    }
}
