@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.security.enterprise.DeviceCert
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
    fun testParseDeviceCertFromJson() {
        val json = """
            {
              "status": "ENROLLED",
              "rootFingerprint": "AA11BB22",
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
              }
            }
        """.trimIndent()

        val cert = EnterpriseJsonHelper.parseDeviceCert(json)
        assertNotNull(cert)
        assertEquals("dev-phone-42", cert.deviceId)
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A", cert.subjectPublicKey)
        assertEquals("member", cert.role)
        assertEquals("Engineering", cert.department)
        assertEquals(1728600000000L, cert.validFrom)
        assertEquals(1729204800000L, cert.validTo)
        assertEquals(501L, cert.serial)
        assertEquals("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8B", cert.issuerPublicKey)
        assertEquals("sigBase64Example==", cert.issuerSignature)
    }

    @Test
    fun testParseDirectoryGroupsFromJson() {
        val json = """
            [
              {
                "groupId": "grp-core-eng",
                "name": "Core Platform Engineering",
                "department": "Engineering",
                "visibility": "DEPARTMENT",
                "description": "Daily standup and code review coordination",
                "requiresApproval": false,
                "memberCount": 18
              },
              {
                "groupId": "grp-all-hands",
                "name": "Company All Hands",
                "department": "Corporate",
                "visibility": "PUBLIC",
                "description": "Company-wide announcements",
                "requiresApproval": false,
                "memberCount": 120
              }
            ]
        """.trimIndent()

        val groups = EnterpriseJsonHelper.parseDirectoryGroups(json)
        assertEquals(2, groups.size)

        val g1 = groups[0]
        assertEquals("grp-core-eng", g1.groupId)
        assertEquals("Core Platform Engineering", g1.name)
        assertEquals("Engineering", g1.department)
        assertEquals(18, g1.memberCount)
        assertFalse(g1.requiresApproval)

        val g2 = groups[1]
        assertEquals("grp-all-hands", g2.groupId)
        assertEquals(120, g2.memberCount)
    }

    @Test
    fun testParseDirectoryMembersFromJson() {
        val json = """
            [
              {
                "deviceId": "dev-alice-01",
                "friendlyName": "Alice Henderson",
                "department": "Engineering",
                "role": "lead",
                "online": true
              },
              {
                "deviceId": "dev-bob-02",
                "friendlyName": "Bob Jenkins",
                "department": "Engineering",
                "role": "member",
                "online": false
              }
            ]
        """.trimIndent()

        val members = EnterpriseJsonHelper.parseDirectoryMembers(json)
        assertEquals(2, members.size)
        assertEquals("dev-alice-01", members[0].deviceId)
        assertEquals("Alice Henderson", members[0].friendlyName)
        assertTrue(members[0].online)

        assertEquals("dev-bob-02", members[1].deviceId)
        assertFalse(members[1].online)
    }

    @Test
    fun testParseDepartmentsFromJson() {
        val json = """
            [
              {
                "departmentId": "dept-eng",
                "name": "Engineering",
                "description": "Software, Firmware and QA",
                "memberCount": 45,
                "groupCount": 6
              },
              {
                "departmentId": "dept-ops",
                "name": "Operations",
                "description": "Infrastructure and Field Logistics",
                "memberCount": 28,
                "groupCount": 3
              }
            ]
        """.trimIndent()

        val departments = EnterpriseJsonHelper.parseDepartments(json)
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

        val json = EnterpriseJsonHelper.serializeCredentials(credentials)
        val parsed = EnterpriseJsonHelper.parseEnrollmentCredentials(json)

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
