package com.transfer.flash.core.swarm.model

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ManifestBuilderTest {

    @Test
    fun `streaming builder matches expected hashes and root`() {
        val pieceSize = 65536
        val builder = ManifestBuilder(pieceSize)

        // 100,000 bytes: piece 0 (65,536 bytes of 0x01), piece 1 (34,464 bytes of 0x02)
        val part1 = ByteArray(65536) { 1 }
        val part2 = ByteArray(34464) { 2 }

        // Feed in smaller chunks to test streaming accumulation
        builder.addBlock(part1, 0, 30000)
        builder.addBlock(part1, 30000, 35536)
        builder.addBlock(part2, 0, 34464)

        val manifest = builder.build()

        assertEquals(1, manifest.version)
        assertEquals(pieceSize, manifest.pieceSize)
        assertEquals(100000L, manifest.totalSize)
        assertEquals(2, manifest.pieceCount)

        // Verify individual piece hashes
        assertEquals(Sha256.hex(Sha256.digest(part1)), Sha256.hex(manifest.pieceHashes[0]))
        assertEquals(Sha256.hex(Sha256.digest(part2)), Sha256.hex(manifest.pieceHashes[1]))

        // Verify whole file sha256
        val combined = ByteArray(100000)
        part1.copyInto(combined, 0)
        part2.copyInto(combined, 65536)
        assertEquals(Sha256.hex(Sha256.digest(combined)), Sha256.hex(manifest.fileSha256))

        // Verify canonical encode/decode round trip
        val canonicalBytes = ManifestCodec.encodeCanonical(manifest)
        val decoded = ManifestCodec.decode(canonicalBytes, manifest.root)
        assertNotNull(decoded)
        assertEquals(manifest, decoded)
    }

    @Test
    fun `fragmenting and reassembly across slices`() {
        val pieceSize = 65536
        val builder = ManifestBuilder(pieceSize)
        // 200,000 bytes creates 4 pieces
        val data = ByteArray(200000) { (it % 251).toByte() }
        builder.addBlock(data)
        val manifest = builder.build()

        val canonicalBytes = ManifestCodec.encodeCanonical(manifest)
        val fragments = ManifestCodec.fragment(canonicalBytes)

        val reassembler = ManifestCodec.Reassembler(manifest.root)
        var assembled: SwarmManifest? = null
        for ((idx, frag) in fragments.withIndex()) {
            assembled = reassembler.addFragment(idx, fragments.size, frag)
        }

        assertNotNull(assembled)
        assertEquals(manifest, assembled)
    }

    @Test
    fun `manifest decode with oversized totalSize returns null instead of throwing`() {
        val badBytes = ByteArray(ManifestCodec.HEADER_SIZE + 32)
        badBytes[0] = 'F'.code.toByte()
        badBytes[1] = 'S'.code.toByte()
        badBytes[2] = 'W'.code.toByte()
        badBytes[3] = 'M'.code.toByte()
        badBytes[4] = 1 // version
        // pieceSize = 65536
        badBytes[5] = 0; badBytes[6] = 0; badBytes[7] = 1; badBytes[8] = 0
        // totalSize = Long.MAX_VALUE
        for (i in 9..16) badBytes[i] = 0x7F.toByte()
        // pieceCount = 1
        badBytes[49] = 1
        val decoded = ManifestCodec.decode(badBytes)
        kotlin.test.assertNull(decoded)
    }
}
