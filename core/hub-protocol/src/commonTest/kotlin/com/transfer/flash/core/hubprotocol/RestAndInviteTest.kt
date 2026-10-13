package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.EnrollRequest
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.GroupDto
import com.transfer.flash.core.hubprotocol.rest.HubDirectoryValues
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class RestAndInviteTest {

    @Test
    fun serverCurrentGroupJsonDecodesWithTheContractFieldNames() {
        // The exact shape the server emitted before the contract was extracted (CLI-03: the client must read it).
        val json = """[{"id":"g1","name":"Eng","departmentId":"d1","visibility":"LISTED","joinMode":"REQUEST_APPROVAL","adminDeviceIds":["a"],"memberDeviceIds":["a","b"]}]"""
        val groups = HubRestCodec.decodeList<GroupDto>(json)
        assertEquals(1, groups.size)
        assertEquals("d1", groups[0].departmentId)
        assertEquals(HubDirectoryValues.JOIN_REQUEST_APPROVAL, groups[0].joinMode)
        assertEquals(listOf("a", "b"), groups[0].memberDeviceIds)
        assertNull(groups[0].pendingCount)
    }

    @Test
    fun serverCurrentMemberJsonDecodes() {
        val m = HubRestCodec.decodeList<MemberDto>("""[{"deviceId":"x","departmentId":"d1","displayName":"Ann","role":"DEPT_ADMIN"}]""").single()
        assertEquals("Ann", m.displayName)
        assertEquals(HubDirectoryValues.ROLE_DEPT_ADMIN, m.role)
        assertEquals(false, m.online)
    }

    @Test
    fun dtosRoundTrip() {
        val cert = FakeSigs.device()
        val resp = EnrollResponse(serverId = "AB", rootFingerprint = "AB", rootPublicKey = "cm9vdA==", deviceCert = cert, delegationChain = listOf(FakeSigs.delegation()), hubName = "Hub")
        assertEquals(resp, HubRestCodec.decode<EnrollResponse>(HubRestCodec.encode(resp)))
        val verify = AuthVerifyResponse(token = "t", expiresAt = 5, principal = HubPrincipal("d", "member"))
        assertEquals(verify, HubRestCodec.decode<AuthVerifyResponse>(HubRestCodec.encode(verify)))
        val ch = AuthChallengeResponse("S", "N", 1)
        assertTrue(HubRestCodec.encode(ch).contains("\"ttlSeconds\":60"))
        val req = EnrollRequest("tok", "dev", "pk", "Phone", "sig")
        assertEquals(req, HubRestCodec.decode<EnrollRequest>(HubRestCodec.encode(req)))
        val err = ErrorBody("ERR_AUTH", "Authentication failed.", "c-1")
        assertEquals(err, HubRestCodec.decode<ErrorBody>(HubRestCodec.encode(err)))
    }

    @Test
    fun clientAdditionsToTheContractDecodeAndStayOptional() {
        // PHASE 2 (CLIENT): the unenrol route, the member key for sealing and the `{ok:true}` body.
        assertEquals("/api/v1/device/unenroll", HubContract.PATH_DEVICE_UNENROLL)
        val bare = HubRestCodec.decodeList<MemberDto>("""[{"deviceId":"x"}]""").single()
        assertNull(bare.subjectPublicKey)
        assertNull(bare.sealingPublicKey)
        assertNull(bare.sealingSignature)
        val rich = HubRestCodec.decodeList<MemberDto>("""[{"deviceId":"x","subjectPublicKey":"cGs=","sealingPublicKey":"c2s=","sealingSignature":"c2ln"}]""").single()
        assertEquals("cGs=", rich.subjectPublicKey)
        assertEquals("c2s=", rich.sealingPublicKey)
        assertEquals(true, HubRestCodec.decode<com.transfer.flash.core.hubprotocol.rest.OkResponse>("""{"ok":true}""").ok)
    }

    @Test
    fun decodeNeverLeaksParserText() {
        val e = assertFailsWith<HubDecodeException> { HubRestCodec.decode<ErrorBody>("{ nope") }
        assertEquals("malformed body", e.message)
        assertNull(HubRestCodec.decodeOrNull<ErrorBody>("[]"))
    }

    @Test
    fun oversizedBodyIsRefusedBeforeParsing() {
        val body = """{"code":"X","message":"${"m".repeat(HubContract.MAX_REST_BODY_BYTES)}"}"""
        assertFailsWith<HubDecodeException> { HubRestCodec.decode<ErrorBody>(body) }
    }

    // ---- invite ---------------------------------------------------------------------------------------------

    @Test
    fun parsesFlashLinkAndNormalisesServer() {
        val i = EnrollmentInvite.parseOrNull("flash://enroll?server=hub.corp.example:8443&token=abc123&root=ab12cd")!!
        assertEquals("http://hub.corp.example:8443", i.serverUrl)
        assertEquals("abc123", i.token)
        assertEquals("AB12CD", i.rootFingerprint)
        assertEquals(false, i.usesTls)
    }

    @Test
    fun parsesPercentEncodedHttpsLinkAndStripsTrailingSlash() {
        val i = EnrollmentInvite.parseOrNull("flash://enroll?server=https%3A%2F%2Fhub.example%2F&token=a%2Bb%20c&root=")!!
        assertEquals("https://hub.example", i.serverUrl)
        assertEquals("a+b c", i.token)
        assertEquals("", i.rootFingerprint)
        assertTrue(i.usesTls)
    }

    @Test
    fun parsesPlainHttpLandingLink() {
        val i = EnrollmentInvite.parseOrNull("https://hub.example/enroll?server=https://hub.example&token=t1&root=ff")!!
        assertEquals("t1", i.token)
        assertEquals("FF", i.rootFingerprint)
    }

    @Test
    fun rejectsLinksWithoutServerOrToken() {
        assertNull(EnrollmentInvite.parseOrNull(""))
        assertNull(EnrollmentInvite.parseOrNull("hello world"))
        assertNull(EnrollmentInvite.parseOrNull("flash://enroll?server=h"))
        assertNull(EnrollmentInvite.parseOrNull("flash://enroll?token=t"))
        assertNull(EnrollmentInvite.parseOrNull("flash://enroll?server=&token=t"))
    }

    @Test
    fun linkBuilderRoundTripsAwkwardValues() {
        val original = EnrollmentInvite("https://hub.example:8443", "t&k=%é ", "AB12")
        assertEquals(original, EnrollmentInvite.parseOrNull(original.toLink()))
    }
}
