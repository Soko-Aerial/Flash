@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.hubprotocol.rest.CreateFileRequestDto
import com.transfer.flash.core.hubprotocol.rest.FileStates
import com.transfer.flash.core.hubprotocol.rest.FileStatusDto
import com.transfer.flash.core.hubprotocol.rest.FileTicketDto
import com.transfer.flash.core.hubprotocol.rest.HubFileIds
import com.transfer.flash.core.hubprotocol.rest.HubInfo
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HF-C1: the sealed-file DTOs as the client decodes them. The golden texts are the exact JSON the P1 hub answers (field
 * names are the wire contract in `docs/protocol.md` "Hub files API"); renaming a property breaks these, on purpose.
 */
class HubFileDtosTest {

    private val fileId = "0123456789abcdef0123456789abcdef"
    private val sha = "ab".repeat(32)

    @Test
    fun theCreateRequestEncodesToTheDocumentedFieldNames() {
        val text = HubRestCodec.encode(
            CreateFileRequestDto(fileId, totalBytes = 88, chunkSize = 32, chunkCount = 3, sha256 = sha, toDeviceId = "dev-b"),
        )
        for (field in listOf("\"fileId\"", "\"totalBytes\":88", "\"chunkSize\":32", "\"chunkCount\":3", "\"sha256\"", "\"toDeviceId\":\"dev-b\"")) {
            assertTrue(field in text, "$field missing from $text")
        }
        assertFalse("\"groupId\":\"" in text, "an absent group must not be sent as a value: $text")
    }

    @Test
    fun theHubsAnswersDecodeFromGoldenTexts() {
        val ticket = HubRestCodec.decodeOrNull<FileTicketDto>(
            """{"fileId":"$fileId","uploadExpiresAtMs":1700086400000,"maxChunkBytes":1048576}""",
        )
        assertEquals(FileTicketDto(fileId, 1_700_086_400_000L, 1_048_576), ticket)

        val status = assertNotNull(
            HubRestCodec.decodeOrNull<FileStatusDto>(
                """{"fileId":"$fileId","state":"READY","totalBytes":88,"chunkSize":32,"chunkCount":3,"sha256":"$sha",""" +
                    """"senderDeviceId":"dev-a","recipients":["dev-b"],"createdAtMs":1,"expiresAtMs":2,"receivedChunks":3}""",
            ),
        )
        assertEquals(FileStates.READY, status.state)
        assertEquals(listOf("dev-b"), status.recipients)
        assertEquals(3, status.receivedChunks)
    }

    @Test
    fun anOlderHubWithoutTheFileFieldReadsAsNoFiles() {
        // A hub from before ADR-215 does not send maxFileBytes: the client must read 0 ("this hub carries no files").
        val info = HubRestCodec.decodeOrNull<HubInfo>(minimalHubInfoWithout("maxFileBytes"))
        assertEquals(0L, assertNotNull(info, "fixture must decode").maxFileBytes)
    }

    @Test
    fun aNewerHubsUnknownFieldsAreIgnored() {
        val status = HubRestCodec.decodeOrNull<FileStatusDto>(
            """{"fileId":"$fileId","state":"READY","totalBytes":88,"chunkSize":32,"chunkCount":3,"sha256":"$sha",""" +
                """"senderDeviceId":"dev-a","futureField":{"x":[1,2]}}""",
        )
        assertNotNull(status)
    }

    @Test
    fun wrongTypesAndMissingRequiredFieldsAreRefused() {
        val bad = listOf(
            """{"fileId":"$fileId","state":"READY"}""",
            """{"fileId":"$fileId","state":"READY","totalBytes":"eighty","chunkSize":32,"chunkCount":3,"sha256":"$sha","senderDeviceId":"a"}""",
            """{"fileId":7,"uploadExpiresAtMs":1}""",
            """{"fileId":"$fileId","uploadExpiresAtMs":1.5}""",
            "[]", "null", "", "{", "\u0000",
        )
        assertNull(HubRestCodec.decodeOrNull<FileStatusDto>(bad[0]))
        assertNull(HubRestCodec.decodeOrNull<FileStatusDto>(bad[1]))
        for (text in bad.drop(2)) assertNull(HubRestCodec.decodeOrNull<FileTicketDto>(text), "'$text'")
        // kotlinx.serialization reads a quoted number as that number even with isLenient = false. Harmless (the value is
        // the same, and the client re-checks every size against the sender's ticket), recorded so nobody relies on it.
        val quoted = """{"fileId":"$fileId","state":"READY","totalBytes":"88","chunkSize":32,"chunkCount":3,"sha256":"$sha","senderDeviceId":"a"}"""
        assertEquals(88L, HubRestCodec.decodeOrNull<FileStatusDto>(quoted)?.totalBytes)
    }

    @Test
    fun aBodyAboveTheRestLimitIsRefusedBeforeParsing() {
        val huge = """{"fileId":"$fileId","uploadExpiresAtMs":1,"pad":"${"x".repeat(HubContract.MAX_REST_BODY_BYTES)}"}"""
        assertNull(HubRestCodec.decodeOrNull<FileTicketDto>(huge))
    }

    @Test
    fun theDecodersNeverThrowOnAThousandRandomAndTruncatedInputs() {
        val random = Random(215)
        val golden = """{"fileId":"$fileId","state":"READY","totalBytes":88,"chunkSize":32,"chunkCount":3,""" +
            """"sha256":"$sha","senderDeviceId":"dev-a","recipients":["dev-b"],"receivedChunks":3}"""
        var decodedTruncations = 0
        for (cut in 0..golden.length) {
            if (HubRestCodec.decodeOrNull<FileStatusDto>(golden.substring(0, cut)) != null) decodedTruncations++
        }
        assertEquals(1, decodedTruncations, "only the whole text may decode")
        repeat(1_000) {
            val text = CharArray(random.nextInt(0, 200)) { random.nextInt(0, 0x3000).toChar() }.concatToString()
            HubRestCodec.decodeOrNull<FileStatusDto>(text)
            HubRestCodec.decodeOrNull<FileTicketDto>(text)
            HubRestCodec.decodeOrNull<CreateFileRequestDto>(text)
            // byte mutations of the golden text
            val chars = golden.toCharArray()
            repeat(random.nextInt(1, 4)) { chars[random.nextInt(chars.size)] = random.nextInt(32, 127).toChar() }
            HubRestCodec.decodeOrNull<FileStatusDto>(chars.concatToString())
        }
    }

    @Test
    fun theIdChecksAcceptOnlyLowercaseHexOfTheExactLength() {
        assertTrue(HubFileIds.isFileId(fileId))
        assertTrue(HubFileIds.isSha256(sha))
        val badIds = listOf(
            "", "0".repeat(31), "0".repeat(33), fileId.uppercase(), "../" + "0".repeat(29), "0".repeat(31) + "/",
            "0".repeat(31) + "g", "0".repeat(31) + "٠", "0".repeat(30) + "​0", "‮" + "0".repeat(31),
        )
        for (id in badIds) assertFalse(HubFileIds.isFileId(id), "'$id'")
        assertFalse(HubFileIds.isSha256(sha.dropLast(1)))
        assertFalse(HubFileIds.isSha256(sha.uppercase()))
        assertFalse(HubFileIds.isSha256(fileId))
    }

    private fun minimalHubInfoWithout(field: String): String {
        val full = HubRestCodec.encode(HubInfo(serverId = "hub-1", maxFileBytes = 5))
        val withoutField = Regex(",?\"$field\":\\d+").replace(full, "")
        assertFalse(field in withoutField, "fixture still carries $field: $withoutField")
        return withoutField
    }
}
