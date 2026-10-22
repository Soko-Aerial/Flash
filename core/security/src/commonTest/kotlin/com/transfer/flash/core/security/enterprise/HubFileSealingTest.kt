@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.security.crypto.sha256
import com.transfer.flash.core.security.crypto.toHexLower
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HF-C3: the sealed-file chunk format. The golden vectors were produced OUTSIDE Kotlin, with Python's `cryptography`
 * (AESGCM) and `hashlib`, from the same key material, file id, AAD layout and plaintext; the script is recorded in
 * `docs/plans/HUB-FILES-PLAN.md` section 9 (P2). If a refactor changes one byte of the format, these fail on every target.
 */
class HubFileSealingTest {

    private val material = ByteArray(40) { it.toByte() }
    private val key = HubFileKey(material)
    private val fileId = "00112233445566778899aabbccddeeff"
    private val plain = "Flash sealed hub file golden vector #001".encodeToByteArray()
    private val layout = HubFileLayout.of(plain.size.toLong(), cipherChunkBytes = 32)!!

    private val goldenChunks = listOf(
        "608bd23e73154c63a66467ca6d7d39d954a7b5b0f045fde4b5efc313173c3a01",
        "0cdf2964ddde9cbcaf0448133612a4f577e41c177ca85933bfa539b05dfc0a11",
        "991d97ceb0878c334a82123b3d4b6031b513e76cafefe6b6",
    )

    private fun plainChunk(i: Int): ByteArray =
        plain.copyOfRange(layout.plainOffset(i).toInt(), layout.plainOffset(i).toInt() + layout.plainLength(i))

    @Test
    fun sealingMatchesTheIndependentGoldenVectors() {
        assertEquals(3, layout.chunkCount)
        assertEquals(88L, layout.cipherBytes)
        val hasher = HubFileHasher()
        for (i in 0 until layout.chunkCount) {
            val sealed = HubFileSealing.sealChunk(key, fileId, layout, i, plainChunk(i))
            assertEquals(goldenChunks[i], sealed.toHexLower(), "chunk $i")
            hasher.update(sealed)
        }
        assertEquals("25a51661cff911373b8d981f0cb5493c5f0a5c159b6d2ed0f0c3329bf66a22d0", hasher.digestHex())
    }

    @Test
    fun theKeyEncodingMatchesTheGoldenVectorAndRoundTrips() {
        val encoded = key.encode()
        assertEquals("hfk1.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJw==", encoded)
        val back = assertNotNull(HubFileKey.decode(encoded))
        assertEquals(encoded, back.encode())
        assertFalse(key.toString().contains("AAEC"), "toString must never print the material")
    }

    @Test
    fun openingReturnsThePlaintextOfEveryChunk() {
        for (i in 0 until layout.chunkCount) {
            val opened = HubFileSealing.openChunk(key, fileId, layout, i, goldenChunks[i].hexToBytes())
            assertContentEquals(plainChunk(i), opened, "chunk $i")
        }
    }

    @Test
    fun aChunkThatWasTamperedMovedOrCutDoesNotOpen() {
        val chunk0 = goldenChunks[0].hexToBytes()
        // one flipped bit anywhere
        for (bit in listOf(0, 7, 100, chunk0.size * 8 - 1)) {
            val flipped = chunk0.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
            assertNull(HubFileSealing.openChunk(key, fileId, layout, 0, flipped), "bit $bit")
        }
        // the same bytes claimed for another index (a hub reordering chunks)
        assertNull(HubFileSealing.openChunk(key, fileId, layout, 1, chunk0))
        // another file id (a hub serving another file's chunk)
        assertNull(HubFileSealing.openChunk(key, "f".repeat(32), layout, 0, chunk0))
        // a file the hub claims is longer or shorter (count and length are in the AAD)
        val longer = HubFileLayout.of(plain.size + 16L, cipherChunkBytes = 32)!!
        assertNull(HubFileSealing.openChunk(key, fileId, longer, 0, chunk0))
        val sameCountOtherLength = HubFileLayout.of(plain.size - 1L, cipherChunkBytes = 32)!!
        assertEquals(layout.chunkCount, sameCountOtherLength.chunkCount)
        assertNull(HubFileSealing.openChunk(key, fileId, sameCountOtherLength, 0, chunk0))
        // truncated, empty, oversized
        assertNull(HubFileSealing.openChunk(key, fileId, layout, 0, chunk0.copyOf(chunk0.size - 1)))
        assertNull(HubFileSealing.openChunk(key, fileId, layout, 0, ByteArray(0)))
        assertNull(HubFileSealing.openChunk(key, fileId, layout, 0, chunk0 + byteArrayOf(0)))
        // out-of-range index never throws
        assertNull(HubFileSealing.openChunk(key, fileId, layout, -1, chunk0))
        assertNull(HubFileSealing.openChunk(key, fileId, layout, 3, chunk0))
    }

    @Test
    fun aDifferentKeyCannotOpenAChunk() {
        val other = HubFileKey(ByteArray(40) { (it + 1).toByte() })
        assertNull(HubFileSealing.openChunk(other, fileId, layout, 0, goldenChunks[0].hexToBytes()))
        // same key bytes, different nonce prefix
        val otherPrefix = HubFileKey(material.copyOf().also { it[39] = 99 })
        assertNull(HubFileSealing.openChunk(otherPrefix, fileId, layout, 0, goldenChunks[0].hexToBytes()))
    }

    @Test
    fun resealingIsDeterministicSoAResumedUploadRepeatsItsBytes() {
        val fresh = HubFileKey.generate()
        val first = HubFileSealing.sealChunk(fresh, fileId, layout, 1, plainChunk(1))
        val again = HubFileSealing.sealChunk(fresh, fileId, layout, 1, plainChunk(1))
        assertContentEquals(first, again)
        // ...while two fresh keys never collide
        assertNotEquals(HubFileKey.generate().encode(), HubFileKey.generate().encode())
    }

    @Test
    fun malformedKeyTextsAreNullNeverAThrow() {
        val bad = listOf(
            "", "hfk1.", "hfk2.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJw==", "hfk1.!!!!",
            "hfk1.AAECAw==", "hfk1." + "A".repeat(10_000), "hfk1.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJyg=",
            "hfk1.‮AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJw==",
        )
        for (text in bad) assertNull(HubFileKey.decode(text), "'${text.take(20)}'")
        val random = kotlin.random.Random(7)
        repeat(1_000) {
            val text = "hfk1." + CharArray(random.nextInt(0, 80)) { (random.nextInt(32, 127)).toChar() }.concatToString()
            // Never throws. A random text that happens to be valid Base64 of exactly 40 bytes is a (useless) key; it must
            // then survive its own round trip, which proves decode only ever builds 40-byte keys.
            HubFileKey.decode(text)?.let { k -> assertNotNull(HubFileKey.decode(k.encode())) }
        }
    }

    @Test
    fun theLayoutBoundariesAreExact() {
        val full = HubContract.MAX_CHUNK_BYTES
        val plainChunk = full - HubFileSealing.TAG_BYTES
        assertNull(HubFileLayout.of(0))
        assertNull(HubFileLayout.of(-1))
        assertNull(HubFileLayout.of(Long.MAX_VALUE), "RVW22 F15: the chunk count must not wrap negative")
        assertNull(HubFileLayout.of(Long.MAX_VALUE - plainChunk + 2))
        assertNull(HubFileLayout.of(10, cipherChunkBytes = HubFileSealing.TAG_BYTES))
        assertNull(HubFileLayout.of(10, cipherChunkBytes = full + 1))
        assertEquals(1, HubFileLayout.of(1)!!.chunkCount)
        assertEquals(1, HubFileLayout.of(plainChunk.toLong())!!.chunkCount)
        assertEquals(2, HubFileLayout.of(plainChunk + 1L)!!.chunkCount)
        assertEquals(1, HubFileLayout.of(plainChunk + 1L)!!.plainLength(1))
        assertEquals(17, HubFileLayout.of(plainChunk + 1L)!!.cipherLength(1))
        // every full chunk is exactly the hub's maximum body
        assertEquals(full, HubFileLayout.of(plainChunk * 3L)!!.cipherLength(2))
        // the most chunks a hub allows, then one more
        val most = plainChunk.toLong() * HubContract.MAX_FILE_CHUNKS
        assertEquals(HubContract.MAX_FILE_CHUNKS, HubFileLayout.of(most)!!.chunkCount)
        assertNull(HubFileLayout.of(most + 1))
    }

    @Test
    fun aFiftyMegabyteFileFitsTheFiftyMegabyteLimitOnceTheTagsAreAllowed() {
        val fifty = HubContract.DEFAULT_MAX_FILE_BYTES
        val layout = HubFileLayout.of(fifty)!!
        // The hub's rule (FileStore.create): totalBytes <= maxFileBytes + chunkCount * SEALED_CHUNK_OVERHEAD_BYTES.
        assertTrue(layout.cipherBytes <= fifty + layout.chunkCount.toLong() * HubContract.SEALED_CHUNK_OVERHEAD_BYTES)
        assertTrue(layout.cipherBytes > fifty, "the ciphertext really is larger than 50 MB; that is why the allowance exists")
        // And the hub's own chunk arithmetic agrees with the client's.
        val hubCount = (layout.cipherBytes + layout.cipherChunkBytes - 1) / layout.cipherChunkBytes
        assertEquals(layout.chunkCount.toLong(), hubCount)
    }

    @Test
    fun theHubsChunkCountAgreesWithTheLayoutForEveryLastChunkLength() {
        for (plainBytes in 1L..200L) {
            val l = HubFileLayout.of(plainBytes, cipherChunkBytes = 32)!!
            val hubCount = (l.cipherBytes + 31) / 32
            assertEquals(l.chunkCount.toLong(), hubCount, "plain=$plainBytes")
            val sum = (0 until l.chunkCount).sumOf { l.cipherLength(it).toLong() }
            assertEquals(l.cipherBytes, sum, "plain=$plainBytes")
        }
    }

    @Test
    fun theStreamingHashEqualsTheOneShotHashAtEverySplit() {
        val data = ByteArray(1000) { (it * 31).toByte() }
        val expected = sha256(data).toHexLower()
        for (split in listOf(0, 1, 63, 64, 65, 500, 999, 1000)) {
            val h = HubFileHasher()
            h.update(data, 0, split)
            h.update(data, split, data.size - split)
            assertEquals(expected, h.digestHex(), "split $split")
        }
        // and the plaintext digest of the golden file, computed with Python's hashlib
        val p = HubFileHasher().also { it.update(plain) }.digestHex()
        assertEquals("012d9df4c36a53f9e1ef39e25d5124b1b3d73edec7ed0c2424b304174dd25ac4", p)
    }

    @Test
    fun newFileIdsHaveTheShapeTheHubRequiresAndDoNotRepeat() {
        val ids = List(200) { HubFileSealing.newFileId() }
        assertTrue(ids.all(HubFileIds::isFileId))
        assertEquals(ids.size, ids.toSet().size)
    }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
