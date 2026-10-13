@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.enterprise.EnterpriseJoinStatus
import com.transfer.flash.core.network.enterprise.EnrollmentOutcome
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest
import com.transfer.flash.core.hubprotocol.rest.AuthVerifyResponse
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.enterprise.HubClientAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenario (b) of the client <-> server contract (ADR-124): bearer session, directory scope and the join workflow, with
 * the real `EnterpriseDirectoryClient` (through `EnterpriseHub`) against the real routes.
 */
class E2eDirectoryContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private class World(val hub: E2eHub, val alice: E2eDevice, val bob: E2eDevice, val carol: E2eDevice)

    /** alice, bob in `eng`; carol in `ops`. alice administers `g-approve`. */
    private suspend fun world(): World {
        val hub = E2eHub().provision().start().also { hubs += it }
        val dir = hub.services.directory
        dir.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        dir.createDepartment("ops", "Operations", emptyList(), DirectoryActor.CONSOLE)
        fun group(id: String, dept: String, visibility: GroupVisibility, mode: JoinMode, admins: List<String> = emptyList()) =
            dir.createGroup(id, "Group $id", dept, visibility, mode, admins, DirectoryActor.CONSOLE)
        group("g-open", "eng", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE)
        group("g-approve", "eng", GroupVisibility.LISTED, JoinMode.REQUEST_APPROVAL, listOf("alice"))
        group("g-hidden", "eng", GroupVisibility.HIDDEN, JoinMode.REQUEST_APPROVAL)
        group("g-ops", "ops", GroupVisibility.LISTED, JoinMode.OPEN_IN_SCOPE)

        suspend fun enrolled(id: String, department: String): E2eDevice {
            val device = E2eDevice(id, hub).also { devices += it }
            assertIs<EnrollmentOutcome.Enrolled>(device.client.enroll(hub.invite(department = department)))
            return device
        }
        return World(hub, enrolled("alice", "eng"), enrolled("bob", "eng"), enrolled("carol", "ops"))
    }

    private fun <T> FlashResult<T>.ok(): T = (this as? FlashResult.Success)?.value
        ?: error("expected success but got $this")

    @Test
    fun theBearerHandshakeReturnsAPrincipalAndTheTtlTheClientCounts() = runBlocking<Unit> {
        val w = world()
        val bearer = w.alice.client.session.bearer(forceRefresh = true)
        assertNotNull(bearer, "the real challenge/verify round trip yields a token")
        assertNull(w.alice.client.session.authFailure.value)

        // ASSUMPTION "AuthVerifyResponse.ttlSeconds is present": read the raw JSON the hub sends for a verify.
        val creds = assertNotNull(w.alice.trust.credentials.value)
        val http = HttpClient()
        try {
            val challenge = HubRestCodec.decode<AuthChallengeResponse>(http.get("${w.hub.url}${HubContract.PATH_AUTH_CHALLENGE}").bodyAsText())
            val clientNonce = HubClientAuth.newNonce()
            val verify = http.post("${w.hub.url}${HubContract.PATH_AUTH_VERIFY}") {
                contentType(ContentType.Application.Json)
                setBody(
                    HubRestCodec.encode(
                        AuthVerifyRequest(
                            challenge.serverNonce, clientNonce, creds.deviceId, creds.deviceCert, creds.delegationChain,
                            HubClientAuth.signAuth(w.alice.crypto, challenge.serverId, challenge.serverNonce, clientNonce, creds.deviceId, creds.deviceCert.serial),
                        ),
                    ),
                )
            }
            val raw = verify.bodyAsText()
            assertEquals(200, verify.status.value, raw)
            assertTrue(raw.contains("\"ttlSeconds\":${HubContract.BEARER_TTL_SECONDS}"), "ttlSeconds travels on the wire: $raw")
            val parsed = HubRestCodec.decode<AuthVerifyResponse>(raw)
            assertEquals("alice", parsed.principal.deviceId)

            val me = http.get("${w.hub.url}${HubContract.PATH_DIRECTORY_ME}") { header("Authorization", "Bearer ${parsed.token}") }
            assertEquals(200, me.status.value)
            assertEquals(401, http.get("${w.hub.url}${HubContract.PATH_DIRECTORY_ME}").status.value, "no bearer, no directory")
            assertEquals(401, http.get("${w.hub.url}${HubContract.PATH_DIRECTORY_ME}") { header("Authorization", "Bearer nope") }.status.value)
        } finally {
            http.close()
        }
    }

    @Test
    fun eachDeviceSeesOnlyItsScopeAndAHiddenGroupDoesNotExistForStrangers() = runBlocking<Unit> {
        val w = world()
        val url = w.hub.url
        val aliceGroups = w.alice.client.directory.fetchVisibleGroups(url, "alice").ok().map { it.groupId }.toSet()
        assertEquals(setOf("g-open", "g-approve"), aliceGroups, "listed groups of her department; the hidden one is invisible")
        val carolGroups = w.carol.client.directory.fetchVisibleGroups(url, "carol").ok().map { it.groupId }.toSet()
        assertEquals(setOf("g-ops"), carolGroups)

        val depts = w.alice.client.directory.fetchDepartments(url).ok().map { it.departmentId }
        assertEquals(listOf("eng"), depts, "a member sees its own department only")
        val members = w.alice.client.directory.fetchDepartmentMembers(url, "eng").ok()
        assertEquals(setOf("alice", "bob"), members.map { it.deviceId }.toSet())
        assertTrue(members.all { !it.subjectPublicKey.isNullOrBlank() }, "subjectPublicKey is on MemberDto for in-scope callers")
        assertTrue(w.alice.client.directory.fetchDepartmentMembers(url, "ops") is FlashResult.Failure, "another department's roster is refused")
    }

    @Test
    fun joinOpenPendingRepeatedPendingApprovedAndTheHiddenGroupIsRefused() = runBlocking<Unit> {
        val w = world()
        val url = w.hub.url
        val bob = w.bob.client.directory

        assertEquals(EnterpriseJoinStatus.JOINED, bob.requestJoin(url, "g-open").ok().status)

        val first = bob.requestJoin(url, "g-approve").ok()
        assertEquals(EnterpriseJoinStatus.PENDING, first.status)
        val requestId = assertNotNull(first.requestId)
        val again = bob.requestJoin(url, "g-approve").ok()
        assertEquals(EnterpriseJoinStatus.PENDING, again.status, "a repeated request answers PENDING until decided")
        assertEquals(requestId, again.requestId, "and does not create a second row")

        val hidden = bob.requestJoin(url, "g-hidden").ok()
        assertEquals(EnterpriseJoinStatus.REJECTED, hidden.status, "a hidden group is reported like a missing one")

        // The admin (alice) sees the request and approves; bob then sees the decision.
        val pending = w.alice.client.directory.fetchJoinRequests(url, "g-approve").ok()
        assertEquals(listOf(requestId), pending.map { it.requestId })
        assertEquals("bob", pending.single().deviceId)
        val decided = w.alice.client.directory.decideJoinRequest(url, requestId, approve = true).ok()
        assertEquals(EnterpriseJoinStatus.JOINED, decided.status, "the decision answer is a JoinResponse (docs/protocol.md)")
        assertEquals(EnterpriseJoinStatus.JOINED, bob.requestJoin(url, "g-approve").ok().status, "bob's next poll sees JOINED")
        assertTrue("bob" in w.hub.services.directory.getGroup("g-approve")!!.memberDeviceIds)
    }

    @Test
    fun aRejectedJoinIsSeenByTheDeviceThatAskedAndANonAdminCannotDecide() = runBlocking<Unit> {
        val w = world()
        val url = w.hub.url
        val requestId = assertNotNull(w.bob.client.directory.requestJoin(url, "g-approve").ok().requestId)

        // bob is not a manager: neither the list nor a decision.
        assertTrue(w.bob.client.directory.fetchJoinRequests(url, "g-approve") is FlashResult.Failure, "GET join-requests is admin-only")
        assertTrue(w.bob.client.directory.decideJoinRequest(url, requestId, approve = true) is FlashResult.Failure, "a non-admin cannot approve")
        assertTrue("bob" !in w.hub.services.directory.getGroup("g-approve")!!.memberDeviceIds, "nothing changed")

        // carol (another department, not an admin) cannot even see that the request exists.
        assertTrue(w.carol.client.directory.decideJoinRequest(url, requestId, approve = true) is FlashResult.Failure)

        val rejected = w.alice.client.directory.decideJoinRequest(url, requestId, approve = false, reason = "not this time").ok()
        assertEquals(EnterpriseJoinStatus.REJECTED, rejected.status)

        // The device that asked polls its pending join: it must see REJECTED once, then may ask again.
        val seen = w.bob.client.directory.requestJoin(url, "g-approve").ok()
        assertEquals(EnterpriseJoinStatus.REJECTED, seen.status, "the rejection reaches the requester (it used to be a fresh PENDING)")
        val fresh = w.bob.client.directory.requestJoin(url, "g-approve").ok()
        assertEquals(EnterpriseJoinStatus.PENDING, fresh.status, "after the rejection was delivered a new request is allowed")
        assertTrue(requestId != fresh.requestId)
    }
}
