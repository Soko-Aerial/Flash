@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.rest.DepartmentDto
import com.transfer.flash.core.hubprotocol.rest.EnrollResponse
import com.transfer.flash.core.hubprotocol.rest.GroupDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.MemberDto
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnterpriseEnrollmentAndDirectoryTest {

    @Test
    fun testParseEnrollmentInviteUri() {
        val uri = "flash://enroll?server=http://192.168.1.50:8443&token=inv_998877&root=A1B2C3D4E5F6"
        val invite = EnrollmentInvite.parseOrNull(uri)

        assertNotNull(invite)
        assertEquals("http://192.168.1.50:8443", invite.serverUrl)
        assertEquals("inv_998877", invite.token)
        assertEquals("A1B2C3D4E5F6", invite.rootFingerprint)
    }

    @Test
    fun testParseEnrollmentInviteUriWithoutSchemeInHost() {
        val uri = "flash://enroll/?server=hub.enterprise.lan:8443&token=tok-corp-1&fingerprint=778899"
        val invite = EnrollmentInvite.parseOrNull(uri)

        assertNotNull(invite)
        assertEquals("http://hub.enterprise.lan:8443", invite.serverUrl)
        assertEquals("tok-corp-1", invite.token)
        assertEquals("778899", invite.rootFingerprint)
    }

    @Test
    fun testParseEnrollmentInviteUriPercentEncoded() {
        val uri = "flash://enroll?server=http%3A%2F%2F10.129.215.82%3A8443&token=tok%2D123&root=EEFF0011"
        val invite = EnrollmentInvite.parseOrNull(uri)

        assertNotNull(invite)
        assertEquals("http://10.129.215.82:8443", invite.serverUrl)
        assertEquals("tok-123", invite.token)
        assertEquals("EEFF0011", invite.rootFingerprint)
    }

    @Test
    fun testParseEnrollResponseFromServerJson() {
        val json = """
            {
              "status": "ENROLLED",
              "serverId": "AA11BB22",
              "rootFingerprint": "AA11BB22",
              "rootPublicKey": "cm9vdA==",
              "deviceCert": {
                "deviceId": "dev-phone-42",
                "subjectPublicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A",
                "role": "member",
                "department": "Engineering",
                "validFrom": 1728600000000,
                "validTo": 1729204800000,
                "serial": 501,
                "issuerPublicKey": "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8B",
                "issuerSignature": "sigBase64Example=="
              },
              "someFutureField": true
            }
        """.trimIndent()

        val response = HubRestCodec.decode<EnrollResponse>(json)
        val cert = response.deviceCert
        assertEquals("dev-phone-42", cert.deviceId)
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A", cert.subjectPublicKey)
        assertEquals("member", cert.role)
        assertEquals("Engineering", cert.department)
        assertEquals(1728600000000L, cert.validFrom)
        assertEquals(1729204800000L, cert.validTo)
        assertEquals(501L, cert.serial)
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8B", cert.issuerPublicKey)
        assertEquals("sigBase64Example==", cert.issuerSignature)
        assertEquals("cm9vdA==", response.rootPublicKey)
    }

    @Test
    fun testDirectoryGroupsUseTheHubsFieldNames() {
        // The shape DirectoryRoutes emits (CLI-03): id / departmentId / joinMode, not groupId / department / requiresApproval.
        val json = """
            [
              {"id":"grp-core-eng","name":"Core Platform Engineering","departmentId":"dept-eng","visibility":"LISTED",
               "joinMode":"REQUEST_APPROVAL","adminDeviceIds":["dev-a"],"memberDeviceIds":["dev-a","dev-b"],"memberCount":18,"onlineCount":4,"pendingCount":2},
              {"id":"grp-all-hands","name":"Company All Hands","departmentId":"dept-corp","visibility":"LISTED",
               "joinMode":"OPEN_IN_SCOPE","adminDeviceIds":[],"memberDeviceIds":["dev-a","dev-b","dev-c"]}
            ]
        """.trimIndent()

        val groups = HubRestCodec.decodeList<GroupDto>(json).map { it.toDirectoryGroup() }
        assertEquals(2, groups.size)

        val g1 = groups[0]
        assertEquals("grp-core-eng", g1.groupId)
        assertEquals("Core Platform Engineering", g1.name)
        assertEquals("dept-eng", g1.department)
        assertEquals(18, g1.memberCount)
        assertTrue(g1.requiresApproval)
        assertEquals(4, g1.onlineCount)
        assertEquals(2, g1.pendingCount)

        val g2 = groups[1]
        assertEquals("grp-all-hands", g2.groupId)
        assertFalse(g2.requiresApproval)
        assertEquals(3, g2.memberCount, "falls back to the member list when the hub sent no count")
        assertEquals(null, g2.onlineCount)
    }

    @Test
    fun testDirectoryMembersUseTheHubsFieldNames() {
        val json = """
            [
              {"deviceId":"dev-alice-01","departmentId":"dept-eng","displayName":"Alice Henderson","role":"DEPT_ADMIN","online":true},
              {"deviceId":"dev-bob-02","departmentId":"dept-eng","displayName":"","role":"MEMBER","online":false}
            ]
        """.trimIndent()

        val members = HubRestCodec.decodeList<MemberDto>(json).map { it.toDirectoryMember() }
        assertEquals(2, members.size)
        assertEquals("dev-alice-01", members[0].deviceId)
        assertEquals("Alice Henderson", members[0].friendlyName)
        assertEquals("dept-eng", members[0].department)
        assertTrue(members[0].online)

        assertEquals("dev-bob-02", members[1].deviceId)
        assertEquals("dev-bob-02", members[1].friendlyName, "a blank display name falls back to the device id")
        assertFalse(members[1].online)
    }

    @Test
    fun testDepartmentsUseTheHubsFieldNames() {
        val json = """
            [
              {"id":"dept-eng","name":"Engineering","adminDeviceIds":["dev-a"],"memberCount":45,"groupCount":6},
              {"id":"dept-ops","name":"Operations","adminDeviceIds":[],"memberCount":28,"groupCount":3}
            ]
        """.trimIndent()

        val departments = HubRestCodec.decodeList<DepartmentDto>(json).map { it.toDepartment() }
        assertEquals(2, departments.size)
        assertEquals("dept-eng", departments[0].departmentId)
        assertEquals("Engineering", departments[0].name)
        assertEquals(45, departments[0].memberCount)
        assertEquals(6, departments[0].groupCount)
    }

    @Test
    fun testTrustStoreLifecycle() = runTest {
        val store = MemoryEnterpriseTrustStore()
        assertFalse(store.isEnrolled)

        val cert = DeviceCert(
            deviceId = "dev-charlie-03",
            subjectPublicKey = "pubKey",
            role = DeviceCert.ROLE_MEMBER,
            department = "Sales",
            validFrom = 1000L,
            validTo = 10000L,
            serial = 1L,
            issuerPublicKey = "issuerKey",
            issuerSignature = "sig",
        )

        val credentials = EnrollmentCredentials(
            hubUrl = "http://192.168.1.10:8443",
            rootFingerprint = "ROOT123456",
            rootPublicKey = "rootKey",
            deviceCert = cert,
            enrolledAtMs = 2000L,
        )

        store.saveCredentials(credentials)
        assertTrue(store.isEnrolled)
        assertEquals("ROOT123456", store.getCredentials()?.rootFingerprint)
        assertEquals("dev-charlie-03", store.getCredentials()?.deviceId)
        assertEquals("Sales", store.getCredentials()?.department)

        store.clearCredentials()
        assertFalse(store.isEnrolled)
    }

    @Test
    fun testCredentialsSerializationAndDeserialization() {
        val cert = DeviceCert(
            deviceId = "dev-charlie-03",
            subjectPublicKey = "pubKey",
            role = DeviceCert.ROLE_MEMBER,
            department = "Sales",
            validFrom = 1000L,
            validTo = 10000L,
            serial = 1L,
            issuerPublicKey = "issuerKey",
            issuerSignature = "sig",
        )

        val credentials = EnrollmentCredentials(
            hubUrl = "http://192.168.1.10:8443",
            rootFingerprint = "ROOT123456",
            rootPublicKey = "rootKey",
            deviceCert = cert,
            enrolledAtMs = 2000L,
        )

        val json = EnterpriseCredentialCodec.serialize(credentials)
        val parsed = EnterpriseCredentialCodec.parse(json)

        assertNotNull(parsed)
        assertEquals("http://192.168.1.10:8443", parsed.hubUrl)
        assertEquals("ROOT123456", parsed.rootFingerprint)
        assertEquals("rootKey", parsed.rootPublicKey)
        assertEquals(2000L, parsed.enrolledAtMs)
        assertEquals("dev-charlie-03", parsed.deviceCert.deviceId)
        assertEquals("Sales", parsed.deviceCert.department)
        assertEquals(DeviceCert.ROLE_MEMBER, parsed.deviceCert.role)
    }
}
