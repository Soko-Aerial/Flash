@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise.files

import com.transfer.flash.core.security.enterprise.HubFileHasher
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HF-C4: the on-disk transfer record. A record the engine cannot trust in full must decode to null, never to a guess. */
class HubFileStateCodecTest {

    private fun upload(done: IntArray = intArrayOf(0, 2, 9)) = UploadState(
        fileId = "0123456789abcdef0123456789abcdef",
        hubUrl = "https://hub.example:8443/",
        keyText = "hfk1.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJw==",
        toDeviceId = null,
        groupId = "g2-team=\nline", // newlines in a value must not break the line format
        nameHint = "réunion.pdf",
        plainBytes = 480,
        cipherChunkBytes = 64,
        chunkCount = 10,
        cipherSha256 = "a".repeat(64),
        created = true,
        completed = false,
        expiresAtMs = 0,
        done = HubChunkBitmap(10).also { map -> done.forEach(map::set) },
    )

    private fun download() = DownloadState(
        fileId = "0123456789abcdef0123456789abcdef",
        hubUrl = "https://hub.example",
        cipherSha256 = "b".repeat(64),
        plainBytes = 4096L * 1048560,
        cipherChunkBytes = 1048576,
        chunkCount = 4096,
        verifyFailures = 1,
        done = HubChunkBitmap(4096).also { it.set(0); it.set(4095) },
    )

    @Test
    fun anUploadRecordRoundTrips() {
        val decoded = assertNotNull(HubFileStateCodec.decodeUpload(HubFileStateCodec.encode(upload())))
        assertEquals("g2-team=\nline", decoded.groupId)
        assertEquals("réunion.pdf", decoded.nameHint)
        assertNull(decoded.toDeviceId)
        assertEquals(listOf(0, 2, 9), (0 until 10).filter { decoded.done[it] })
        assertTrue(decoded.created)
        assertEquals(upload().keyText, decoded.keyText)
    }

    @Test
    fun aDownloadRecordOfTheLargestFileRoundTrips() {
        val decoded = assertNotNull(HubFileStateCodec.decodeDownload(HubFileStateCodec.encode(download())))
        assertEquals(2, decoded.done.count())
        assertEquals(1, decoded.verifyFailures)
        assertTrue(HubFileStateCodec.encode(download()).size < 16 * 1024, "fits the storage's record limit")
    }

    @Test
    fun aRecordOfOneKindIsNotReadAsTheOther() {
        assertNull(HubFileStateCodec.decodeDownload(HubFileStateCodec.encode(upload())))
        assertNull(HubFileStateCodec.decodeUpload(HubFileStateCodec.encode(download())))
    }

    @Test
    fun everyTornWriteIsRefused() {
        val bytes = HubFileStateCodec.encode(upload())
        for (cut in 0 until bytes.size) {
            assertNull(HubFileStateCodec.decodeUpload(bytes.copyOf(cut)), "torn at $cut")
        }
    }

    @Test
    fun anyFlippedByteIsRefused() {
        val bytes = HubFileStateCodec.encode(download())
        val random = Random(11)
        repeat(1000) {
            val copy = bytes.copyOf()
            val at = random.nextInt(copy.size)
            copy[at] = (copy[at].toInt() xor (1 shl random.nextInt(8))).toByte()
            assertNull(HubFileStateCodec.decodeDownload(copy), "flip at $at")
        }
        repeat(1000) { assertNull(HubFileStateCodec.decodeUpload(random.nextBytes(random.nextInt(0, 600)))) }
    }

    /** Rewrites a record's body and re-signs its check line, as a later version (or a careful attacker on disk) would. */
    private fun resigned(bytes: ByteArray, edit: (String) -> String): ByteArray {
        val text = bytes.decodeToString()
        val body = edit(text.substring(0, text.lastIndexOf("check=")))
        val check = HubFileHasher().also { it.update(body.encodeToByteArray()) }.digestHex()
        return "${body}check=$check\n".encodeToByteArray()
    }

    @Test
    fun unknownFieldsFromALaterVersionAreIgnored() {
        val later = resigned(HubFileStateCodec.encode(upload())) { it + "priority=high\n" }
        assertNotNull(HubFileStateCodec.decodeUpload(later))
    }

    @Test
    fun valuesOutsideTheirShapeAreRefusedEvenWithAValidCheck() {
        val good = HubFileStateCodec.encode(upload())
        val edits = listOf<(String) -> String>(
            { it.replace("fileId=0123456789abcdef0123456789abcdef", "fileId=../../etc/passwd") },
            { it.replace("chunkCount=10", "chunkCount=11") }, // the bitmap no longer fits
            { it.replace("chunkBytes=64", "chunkBytes=16") },
            { it.replace("chunkBytes=64", "chunkBytes=2097152") },
            { it.replace("plainBytes=480", "plainBytes=-1") },
            { it.replace("created=true", "created=yes") },
            { it.replace("key=hfk1.", "key=hfk2.") },
            { it.replace("sha256=" + "a".repeat(64), "sha256=" + "A".repeat(64)) },
            { it + "fileId=0123456789abcdef0123456789abcdef\n" }, // a duplicated key
            { it.replace("to=\n", "") }, // a missing field
            { it.replace("to=\n", "to=!!notbase64\n") },
            { it.replace("kind=upload", "kind=download") },
        )
        for ((i, edit) in edits.withIndex()) {
            assertNull(HubFileStateCodec.decodeUpload(resigned(good, edit)), "edit $i")
        }
    }

    @Test
    fun aBitmapRefusesStrayBitsPastItsSize() {
        val map = HubChunkBitmap(10).also { it.set(9) }
        assertNotNull(HubChunkBitmap.decode(10, map.encode()))
        assertNull(HubChunkBitmap.decode(9, map.encode()), "bit 9 is past a 9-chunk file")
        assertNull(HubChunkBitmap.decode(17, map.encode()), "wrong length")
        assertNull(HubChunkBitmap.decode(0, ""))
        assertFalse(HubChunkBitmap(8).also { it.set(7) }.let { m -> (0 until 7).any { m[it] } })
        assertEquals(7, HubChunkBitmap(8).also { (0 until 7).forEach(it::set) }.firstMissing())
        assertEquals(-1, HubChunkBitmap(3).also { (0 until 3).forEach(it::set) }.firstMissing())
    }
}
