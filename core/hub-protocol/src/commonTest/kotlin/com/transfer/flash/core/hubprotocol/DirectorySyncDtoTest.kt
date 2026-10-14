package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.rest.DepartmentSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectoryMemberSyncDto
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.GroupRosterDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class DirectorySyncDtoTest {

    private val sampleStatement = OrgMemberStatement(
        deviceId = "dev-1",
        subjectPublicKey = Base64.encode("pubkey-bytes".encodeToByteArray()),
        departmentId = "dept-eng",
        role = HubRoles.MEMBER,
        issuedAtMs = 1_700_000_000_000L,
        statementSerial = 1L,
        issuerSignature = Base64.encode("sig-bytes".encodeToByteArray()),
    )

    private val sampleSyncDto = DirectorySyncDto(
        directoryEpoch = "epoch-12345678",
        version = 42L,
        fromVersion = 10L,
        fullResync = false,
        nextCursor = "cursor-abc",
        serverTimeMs = 1_700_000_100_000L,
        offlineGraceDays = 30,
        delegationChain = emptyList(),
        members = listOf(
            DirectoryMemberSyncDto(
                deviceId = "dev-1",
                displayName = "Alice Eng",
                departmentId = "dept-eng",
                role = HubRoles.MEMBER,
                statement = sampleStatement,
                online = true,
            ),
        ),
        removedDeviceIds = listOf("dev-revoked"),
        departments = listOf(
            DepartmentSyncDto(
                id = "dept-eng",
                name = "Engineering",
                headDeviceIds = listOf("dev-head"),
                memberCount = 12,
                hidden = false,
            ),
        ),
        removedDepartmentIds = listOf("dept-old"),
        groupRosters = listOf(
            GroupRosterDto(
                groupId = "g-1",
                version = 5L,
                statement = "roster-stmt",
                validUntilMs = 1_700_100_000_000L,
            ),
        ),
        removedGroupIds = listOf("g-old"),
    )

    @Test
    fun directorySyncDtoSerializationRoundtrip() {
        val json = HubRestCodec.encode(sampleSyncDto)
        val decoded = HubRestCodec.decode<DirectorySyncDto>(json)

        assertEquals(sampleSyncDto.directoryEpoch, decoded.directoryEpoch)
        assertEquals(sampleSyncDto.version, decoded.version)
        assertEquals(sampleSyncDto.fromVersion, decoded.fromVersion)
        assertEquals(sampleSyncDto.fullResync, decoded.fullResync)
        assertEquals(sampleSyncDto.nextCursor, decoded.nextCursor)
        assertEquals(sampleSyncDto.serverTimeMs, decoded.serverTimeMs)
        assertEquals(sampleSyncDto.offlineGraceDays, decoded.offlineGraceDays)
        assertEquals(1, decoded.members.size)
        assertEquals("dev-1", decoded.members[0].deviceId)
        assertEquals("Alice Eng", decoded.members[0].displayName)
        assertEquals(sampleStatement.deviceId, decoded.members[0].statement.deviceId)
        assertEquals(sampleStatement.subjectPublicKey, decoded.members[0].statement.subjectPublicKey)
        assertEquals(1, decoded.removedDeviceIds.size)
        assertEquals("dev-revoked", decoded.removedDeviceIds[0])
        assertEquals(1, decoded.departments.size)
        assertEquals("Engineering", decoded.departments[0].name)
    }

    @Test
    fun forwardCompatibilityIgnoresUnknownKeys() {
        val jsonWithUnknownKeys = """
            {
                "directoryEpoch": "epoch-1",
                "version": 100,
                "fromVersion": 0,
                "fullResync": true,
                "serverTimeMs": 1700000000000,
                "offlineGraceDays": 30,
                "unknownFutureField": "future_value",
                "futureObject": { "nested": 123 },
                "members": []
            }
        """.trimIndent()

        val decoded = HubRestCodec.decode<DirectorySyncDto>(jsonWithUnknownKeys)
        assertEquals("epoch-1", decoded.directoryEpoch)
        assertEquals(100L, decoded.version)
        assertTrue(decoded.fullResync)
    }

    @Test
    fun fuzzDecodersWithAtLeast1000RandomAndTruncatedInputsNeverThrowsUnhandled() {
        val validJson = HubRestCodec.encode(sampleSyncDto)
        val random = Random(42)

        // 1. Truncated inputs: slices of validJson from 0 to length
        var truncatedNullCount = 0
        val maxSlice = validJson.length.coerceAtMost(500)
        for (i in 0 until maxSlice) {
            val truncated = validJson.substring(0, i)
            val res = HubRestCodec.decodeOrNull<DirectorySyncDto>(truncated)
            if (res == null) truncatedNullCount++
        }
        // Truncated inputs must almost all be rejected as invalid JSON
        assertTrue(truncatedNullCount > 0, "Truncated JSON should fail decoding")

        // 2. Random byte strings
        var randomNullCount = 0
        var randomValidCount = 0
        for (i in 0 until 1000) {
            val length = random.nextInt(1, 200)
            val randomChars = CharArray(length) { (random.nextInt(32, 127)).toChar() }
            val input = String(randomChars)
            val res = HubRestCodec.decodeOrNull<DirectorySyncDto>(input)
            if (res == null) randomNullCount++ else randomValidCount++
        }
        assertEquals(1000, randomNullCount + randomValidCount)
        assertTrue(randomNullCount > 990, "Random ASCII strings must be rejected")

        // 3. Bit flips on valid JSON
        var mutatedHandledCount = 0
        val chars = validJson.toCharArray()
        for (i in 0 until 500) {
            val mutated = chars.clone()
            val mutations = random.nextInt(1, 5)
            for (m in 0 until mutations) {
                val pos = random.nextInt(mutated.size)
                mutated[pos] = (random.nextInt(32, 127)).toChar()
            }
            val res = HubRestCodec.decodeOrNull<DirectorySyncDto>(String(mutated))
            if (res != null) {
                // If it happened to decode, required fields must be non-null
                assertNotNull(res.directoryEpoch)
            }
            mutatedHandledCount++
        }
        assertEquals(500, mutatedHandledCount)
    }
}
