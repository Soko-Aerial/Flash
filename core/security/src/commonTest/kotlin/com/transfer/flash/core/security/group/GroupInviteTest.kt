package com.transfer.flash.core.security.group

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupInviteTest {

    private val sampleSecret = GroupSecret.fromBytes(ByteArray(32) { (it + 1).toByte() })
    private val sampleFp = ByteArray(32) { (it + 0x20).toByte() }

    private fun createValidInvite(
        groupId: String = "g2-6f01129f28cff84f8ef67cb9d1970499",
        epoch: Long = 1L,
        secret: GroupSecret = sampleSecret,
        groupName: String = "Flash Dev Crew",
        inviterId: String = "dev-device-alpha",
        inviterFp: ByteArray = sampleFp,
        hints: List<String> = listOf("192.168.1.50:8765", "10.0.0.5:8765"),
        issuedAtMs: Long = 1728123456789L,
    ): GroupInvite {
        return GroupInvite(
            version = 1,
            groupId = groupId,
            epoch = epoch,
            secret = secret,
            groupName = groupName,
            inviterDeviceId = inviterId,
            inviterKeyFingerprint = inviterFp,
            addressHints = hints,
            issuedAtMs = issuedAtMs,
        )
    }

    @Test
    fun roundtrip_encode_decode_uri() {
        val invite = createValidInvite()
        val uri = GroupInviteCodec.encode(invite)
        assertTrue(uri.startsWith("flash://g/1/"), "URI must start with flash://g/1/: $uri")

        val decoded = GroupInviteCodec.decode(uri)
        assertNotNull(decoded, "Invite should decode cleanly")
        assertEquals(invite, decoded)
        assertEquals(invite.inviterFingerprintHex, decoded.inviterFingerprintHex)
    }

    @Test
    fun roundtrip_with_various_hint_counts() {
        for (hintCount in 0..3) {
            val hints = (1..hintCount).map { "192.168.1.$it:8080" }
            val invite = createValidInvite(hints = hints)
            val uri = GroupInviteCodec.encode(invite)
            val decoded = GroupInviteCodec.decode(uri)
            assertNotNull(decoded)
            assertEquals(invite, decoded)
            assertEquals(hintCount, decoded.addressHints.size)
        }
    }

    @Test
    fun decode_accepts_raw_payload_or_case_insensitive_uri() {
        val invite = createValidInvite()
        val uri = GroupInviteCodec.encode(invite)
        val payload = uri.removePrefix("flash://g/1/")

        // Raw base64url payload without prefix
        val decodedRaw = GroupInviteCodec.decode(payload)
        assertEquals(invite, decodedRaw)

        // Upper-case prefix
        val upperUri = "FLASH://G/1/$payload"
        val decodedUpper = GroupInviteCodec.decode(upperUri)
        assertEquals(invite, decodedUpper)
    }

    @Test
    fun toString_never_leaks_secret() {
        val rawSecretBytes = byteArrayOf(
            0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte(),
            0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        )
        val secret = GroupSecret.fromBytes(rawSecretBytes)
        val invite = createValidInvite(secret = secret)
        val str = invite.toString()

        assertTrue(str.contains("GroupSecret(redacted)"), "toString must redact secret: $str")
        assertFalse(str.contains("deadbeef"), "toString must not leak secret hex")
        assertFalse(str.contains("cafebabe"), "toString must not leak secret hex")
    }

    @Test
    fun hostile_input_table() {
        val valid = createValidInvite()
        val binary = GroupInviteCodec.encodeToBinary(valid)

        // 1. Truncation at every single prefix length
        for (len in 0 until binary.size) {
            val truncated = binary.copyOf(len)
            assertNull(
                GroupInviteCodec.decodeFromBinary(truncated),
                "Truncated input of length $len must return null",
            )
        }

        // 2. Extra trailing bytes
        val withTrailing = binary + byteArrayOf(0x00)
        assertNull(
            GroupInviteCodec.decodeFromBinary(withTrailing),
            "Binary with trailing bytes must return null",
        )

        // 3. Wrong version
        val badVersion = binary.copyOf()
        badVersion[0] = 2.toByte()
        assertNull(GroupInviteCodec.decodeFromBinary(badVersion))

        val badVersionZero = binary.copyOf()
        badVersionZero[0] = 0.toByte()
        assertNull(GroupInviteCodec.decodeFromBinary(badVersionZero))

        // 4. GroupId does not start with "g2-"
        val binaryBadPrefix = binary.copyOf()
        binaryBadPrefix[4] = 'x'.code.toByte()
        assertNull(
            GroupInviteCodec.decodeFromBinary(binaryBadPrefix),
            "Non-g2 group must be rejected",
        )

        // 5. Epoch = 0
        val binaryEpochZero = binary.copyOf()
        // Epoch is at offset 1 + 2 + groupId.length = 1 + 2 + 35 = 38
        // Let's modify epoch bytes to 0
        val epochOffset = 1 + 2 + valid.groupId.encodeToByteArray().size
        binaryEpochZero[epochOffset] = 0
        binaryEpochZero[epochOffset + 1] = 0
        binaryEpochZero[epochOffset + 2] = 0
        binaryEpochZero[epochOffset + 3] = 0
        assertNull(GroupInviteCodec.decodeFromBinary(binaryEpochZero), "epoch = 0 must return null")

        // 6. Hint count > 3
        val binaryBadHints = binary.copyOf()
        val hintCountOffset = epochOffset + 4 + 32 + (2 + valid.groupName.encodeToByteArray().size) +
            (2 + valid.inviterDeviceId.encodeToByteArray().size) + 32
        binaryBadHints[hintCountOffset] = 4.toByte()
        assertNull(GroupInviteCodec.decodeFromBinary(binaryBadHints), "hintCount > 3 must return null")

        // 7. Invalid base64url string
        assertNull(GroupInviteCodec.decode("flash://g/1/invalid!characters*"))
        assertNull(GroupInviteCodec.decode("flash://g/1/SGVsbG8")) // length mod 4 == 1
    }
}
