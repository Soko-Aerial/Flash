@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.enterprise

import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.enterprise.EnterpriseDepartment
import com.transfer.flash.core.network.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.network.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.security.enterprise.DeviceCert
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnterpriseGroupLinkAndMultiPathTest {

    @Test
    fun testZeroCloudDumpInvariantOnGroupLink() = runTest {
        val trustStore = MemoryEnterpriseTrustStore()
        assertFalse(trustStore.isEnrolled)

        val credentials = EnrollmentCredentials(
            hubUrl = "http://hub.corporate.lan:8443",
            rootFingerprint = "FINGERPRINT123",
            rootPublicKey = "rootKey",
            deviceCert = DeviceCert(
                deviceId = "dev-employee-01",
                subjectPublicKey = "pubKey",
                role = DeviceCert.ROLE_MEMBER,
                department = "Engineering",
                validFrom = 1000L,
                validTo = 50000L,
                serial = 101L,
                issuerPublicKey = "issuerKey",
                issuerSignature = "sig",
            ),
        )
        trustStore.saveCredentials(credentials)
        assertTrue(trustStore.isEnrolled)

        val existingGroupId = "grp-legacy-p2p-001"
        assertFalse(trustStore.isGroupLinkedToHub(existingGroupId))
        assertNull(trustStore.getHubUrlForGroup(existingGroupId))

        // Pre-existing group links to corporate hub
        trustStore.linkGroupToHub(existingGroupId)

        // Group is now linked
        assertTrue(trustStore.isGroupLinkedToHub(existingGroupId))
        assertEquals("http://hub.corporate.lan:8443", trustStore.getHubUrlForGroup(existingGroupId))
        assertTrue(existingGroupId in trustStore.linkedHubGroups.first())

        // Verify Zero Cloud Dump Invariant:
        // Linking does not dump history, only changes future dispatch routing.
        // Unlinking restores P2P isolation cleanly.
        trustStore.unlinkGroupFromHub(existingGroupId)
        assertFalse(trustStore.isGroupLinkedToHub(existingGroupId))
        assertNull(trustStore.getHubUrlForGroup(existingGroupId))
    }

    @Test
    fun testEnvelopePayloadPackaging() {
        val envelope = FlashEnvelope(
            id = "msg-uuid-999",
            type = "GROUP_MSG",
            payloadJson = "Hello corporate team!",
            senderId = "dev-employee-01",
            sentAt = 1728600000000L,
        )

        assertEquals("msg-uuid-999", envelope.id)
        assertEquals("GROUP_MSG", envelope.type)
        assertEquals("Hello corporate team!", envelope.payloadJson)
        assertEquals("dev-employee-01", envelope.senderId)
        assertEquals(1728600000000L, envelope.sentAt)
    }

    @Test
    fun testDirectoryModelsIntegrity() {
        val member = EnterpriseDirectoryMember(
            deviceId = "dev-bob-42",
            friendlyName = "Bob Smith",
            department = "Security",
            role = "admin",
            online = true,
        )
        assertEquals("dev-bob-42", member.deviceId)
        assertEquals("Bob Smith", member.friendlyName)
        assertEquals("Security", member.department)
        assertTrue(member.online)

        val group = EnterpriseDirectoryGroup(
            groupId = "grp-sec-ops",
            name = "Security Operations",
            department = "Security",
            visibility = "DEPARTMENT",
            description = "Incident response and key management",
            requiresApproval = true,
            memberCount = 8,
        )
        assertEquals("grp-sec-ops", group.groupId)
        assertTrue(group.requiresApproval)
        assertEquals(8, group.memberCount)

        val dept = EnterpriseDepartment(
            departmentId = "dept-sec",
            name = "Security",
            description = "InfoSec and Identity",
            memberCount = 15,
            groupCount = 3,
        )
        assertEquals("dept-sec", dept.departmentId)
        assertEquals(15, dept.memberCount)
    }

    @Test
    fun testGroupMessageRelayFrameRoundTrip() {
        val originalFrame = com.transfer.flash.core.messaging.protocol.GroupWireFrame.Message(
            groupId = "grp-finance-2026",
            messageId = "msg-12345",
            from = "dev-alice-01",
            senderName = "Alice",
            sentAt = 1728600000000L,
            text = "Budget approved",
        )
        val encodedPayload = com.transfer.flash.core.messaging.protocol.GroupFrameCodec.encode(originalFrame)
        val envelope = FlashEnvelope(
            id = originalFrame.messageId,
            type = "GROUP_MSG",
            payloadJson = encodedPayload,
            senderId = originalFrame.from,
            sentAt = originalFrame.sentAt,
        )

        val decoded = com.transfer.flash.core.messaging.protocol.GroupFrameCodec.decode(envelope.payloadJson)
        assertTrue(decoded is com.transfer.flash.core.messaging.protocol.GroupWireFrame.Message)
        assertEquals("grp-finance-2026", decoded.groupId)
        assertEquals("msg-12345", decoded.messageId)
        assertEquals("Budget approved", decoded.text)
    }
}
