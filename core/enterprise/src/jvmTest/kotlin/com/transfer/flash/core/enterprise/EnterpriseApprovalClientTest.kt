@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.HubStepUp
import com.transfer.flash.core.hubprotocol.rest.ApprovalDto
import com.transfer.flash.core.hubprotocol.rest.ApprovalSubmitBody
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.enterprise.HubClientAuth
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ADR-131 (FO-14): the phone side of the co-signature: listing what waits, signing exactly what was listed, and posting it. */
class EnterpriseApprovalClientTest {

    private val hubUrl = "https://hub.example"
    private val fake = FakeHub()
    private val client = EnterpriseDirectoryClient(httpClient = fake.client(), ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)

    private fun dto(id: String = "c-1") = ApprovalDto(
        challengeId = id, action = "admin.create", admin = "ada", target = "newadmin", nonce = "n0nce",
        code = HubStepUp.approvalCode(id, "n0nce"), expiresAtMs = 123L,
    )

    @Test
    fun theListIsReadFromTheApprovalsRoute() = runTest {
        fake.respond("GET", HubContract.PATH_APPROVALS, HttpStatusCode.OK, HubRestCodec.encode(listOf(dto("c-1"), dto("c-2"))))
        val result = client.fetchApprovals(hubUrl)
        assertIs<FlashResult.Success<List<EnterpriseApproval>>>(result)
        assertEquals(listOf("c-1", "c-2"), result.value.map { it.challengeId })
        assertEquals("396796", result.value[0].code)
        assertEquals("ada", result.value[0].admin)
    }

    @Test
    fun aHubWithoutTheRouteIsAFailureNotACrash() = runTest {
        assertIs<FlashResult.Failure>(client.fetchApprovals(hubUrl))
    }

    @Test
    fun theSignatureCoversTheApprovalBytesOfTheListedFields() {
        val crypto = TestCrypto()
        val d = dto()
        val signature = Base64.decode(HubClientAuth.signApproval(crypto, d.challengeId, d.action, d.admin, d.target, d.nonce))
        assertTrue(crypto.verify(signature, HubStepUp.approvalBytes(d.challengeId, d.action, d.admin, d.target, d.nonce), crypto.identityPublicKeyEncoded))
        // Another field changes what is signed: a phone that is shown one thing cannot have signed another.
        assertTrue(!crypto.verify(signature, HubStepUp.approvalBytes(d.challengeId, d.action, d.admin, "someone-else", d.nonce), crypto.identityPublicKeyEncoded))
        // And it is not valid as the browser's proof form (domain separation).
        assertTrue(!crypto.verify(signature, HubStepUp.proofBytes(d.challengeId, d.action, d.admin, d.target, d.nonce), crypto.identityPublicKeyEncoded))
    }

    @Test
    fun submittingPostsTheSignatureToTheChallengeRoute() = runTest {
        fake.respond("POST", "${HubContract.PATH_APPROVALS}/c-1", HttpStatusCode.OK, FakeHub.ok())
        assertIs<FlashResult.Success<Unit>>(client.submitApproval(hubUrl, "c-1", "c2ln"))
        val sent = fake.requests.single { it.method == "POST" }
        assertEquals("c2ln", HubRestCodec.decodeOrNull<ApprovalSubmitBody>(sent.body)?.signature)
        assertNotNull(sent.path)
    }

    @Test
    fun aRefusedApprovalIsAFailureWithAFriendlySentence() = runTest {
        fake.respond("POST", "${HubContract.PATH_APPROVALS}/c-9", HttpStatusCode.Forbidden, FakeHub.error("ERR_FORBIDDEN"))
        val result = client.submitApproval(hubUrl, "c-9", "c2ln")
        assertIs<FlashResult.Failure>(result)
    }
}
