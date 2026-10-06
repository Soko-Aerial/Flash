package com.transfer.flash.core.security.group

import com.transfer.flash.core.security.crypto.hmacSha256
import com.transfer.flash.core.security.crypto.toHexLower
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden vector test asserting exact byte compatibility for GM-1 primitives (ADR-073, protocol.md).
 *
 * Verifies key derivation, commitment, and proof transcripts against fixed reference vectors.
 */
class GroupMembershipGoldenVectorTest {

    private val secretBytes = ByteArray(32) { it.toByte() }
    private val secret = GroupSecret.fromBytes(secretBytes)
    private val groupId = "g2-6f01129f28cff84f8ef67cb9d1970499"
    private val epoch = 1L

    private val fpI = ByteArray(32) { (0x10 + it).toByte() }
    private val fpR = ByteArray(32) { (0x30 + it).toByte() }
    private val nonceI = ByteArray(16) { (0xa0 + it).toByte() }
    private val nonceR = ByteArray(16) { (0xb0 + it).toByte() }

    @Test
    fun verifyGoldenAuthKey() {
        val authKey = GroupSecretKdf.authKey(secret, groupId, epoch)
        assertEquals(
            "b0d2233864667368d2cb1403155bfc24d419fc76afe1622af35c260760ae05c6",
            authKey.toHexLower(),
        )
    }

    @Test
    fun verifyGoldenBeaconKey() {
        val beaconKey = GroupSecretKdf.beaconKey(secret, groupId, epoch)
        assertEquals(
            "273172fb813d6be217a75698449672e003cdea570e000bc07b7664d3890fafcd",
            beaconKey.toHexLower(),
        )
    }

    @Test
    fun verifyGoldenCommitment() {
        val commitHex = GroupSecretCommit.ofHex(groupId, epoch, secret)
        assertEquals(
            "316155ba2ffa797afffc435dbba71497d1146322565fdc77e22bf66d9a9f728e",
            commitHex,
        )
    }

    @Test
    fun verifyGoldenProofTranscriptsAndMacs() {
        val authKey = GroupSecretKdf.authKey(secret, groupId, epoch)

        // Responder transcript and MAC
        val transcriptR = GroupProofTranscript.build(
            role = GroupProofTranscript.ROLE_RESPONDER,
            fpInitiator = fpI,
            fpResponder = fpR,
            groupId = groupId,
            epoch = epoch,
            nonceInitiator = nonceI,
            nonceResponder = nonceR,
        )
        val expectedTranscriptRHex =
            "666c6173682d6773702d7631520020101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f002367322d366630313132396632386366663834663865663637636239643139373034393900000001a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
        assertEquals(expectedTranscriptRHex, transcriptR.toHexLower())

        val macR = hmacSha256(authKey, transcriptR)
        assertEquals(
            "afa3ae3ad3da3a316785d560d96da2f137f3bf0ad635820c16723481a9f3431c",
            macR.toHexLower(),
        )

        // Initiator transcript and MAC
        val transcriptI = GroupProofTranscript.build(
            role = GroupProofTranscript.ROLE_INITIATOR,
            fpInitiator = fpI,
            fpResponder = fpR,
            groupId = groupId,
            epoch = epoch,
            nonceInitiator = nonceI,
            nonceResponder = nonceR,
        )
        val expectedTranscriptIHex =
            "666c6173682d6773702d7631490020101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f0020303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f002367322d366630313132396632386366663834663865663637636239643139373034393900000001a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
        assertEquals(expectedTranscriptIHex, transcriptI.toHexLower())

        val macI = hmacSha256(authKey, transcriptI)
        assertEquals(
            "1880e5348e5f0ef6135d0a4742f44fcc2442bbe390fc403a99744eefc236b367",
            macI.toHexLower(),
        )
    }

    @Test
    fun verifyGoldenInviteVector() {
        val inviterFp = ByteArray(32) { (0x20 + it).toByte() }
        val invite = GroupInvite(
            version = 1,
            groupId = groupId,
            epoch = epoch,
            secret = secret,
            groupName = "Flash Core Team",
            inviterDeviceId = "alpha-tester-device",
            inviterKeyFingerprint = inviterFp,
            addressHints = listOf("192.168.1.100:8765", "10.0.0.1:8765"),
            issuedAtMs = 1728123456789L,
        )

        val binary = GroupInviteCodec.encodeToBinary(invite)
        val expectedBinaryHex =
            "01002367322d366630313132396632386366663834663865663637636239643139373034393900000001000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f000f466c61736820436f7265205465616d0013616c7068612d7465737465722d646576696365202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f0200123139322e3136382e312e3130303a38373635000d31302e302e302e313a38373635000001925c2f4d15"
        assertEquals(expectedBinaryHex, binary.toHexLower())
        assertEquals(188, binary.size)

        val uri = GroupInviteCodec.encode(invite)
        val expectedUri =
            "flash://g/1/AQAjZzItNmYwMTEyOWYyOGNmZjg0ZjhlZjY3Y2I5ZDE5NzA0OTkAAAABAAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8AD0ZsYXNoIENvcmUgVGVhbQATYWxwaGEtdGVzdGVyLWRldmljZSAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4_AgASMTkyLjE2OC4xLjEwMDo4NzY1AA0xMC4wLjAuMTo4NzY1AAABklwvTRU"
        assertEquals(expectedUri, uri)

        val decoded = GroupInviteCodec.decode(uri)
        assertEquals(invite, decoded)
    }
}
