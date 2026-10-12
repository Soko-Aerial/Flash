@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.security.enterprise.DeviceCert
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmFileEnterpriseTrustStoreTest {

    @Test
    fun testPersistenceAcrossReload() = runTest {
        val tempDir = Files.createTempDirectory("flash_enterprise_test").toFile()
        try {
            val store1 = JvmFileEnterpriseTrustStore(tempDir)
            assertFalse(store1.isEnrolled)

            val cert = DeviceCert(
                deviceId = "dev-desktop-01",
                subjectPublicKey = "pubKeyBase64",
                role = DeviceCert.ROLE_MEMBER,
                department = "Operations",
                validFrom = 1000L,
                validTo = 99999L,
                serial = 42L,
                issuerPublicKey = "rootKey",
                issuerSignature = "sig",
            )

            val creds = EnrollmentCredentials(
                hubUrl = "http://192.168.1.100:8443",
                rootFingerprint = "FINGERPRINT_123",
                rootPublicKey = "rootKey",
                deviceCert = cert,
                enrolledAtMs = 5000L,
            )

            store1.saveCredentials(creds)
            store1.linkGroupToHub("grp-alpha")
            store1.linkGroupToHub("grp-beta")

            assertTrue(store1.isEnrolled)
            assertTrue(store1.isGroupLinkedToHub("grp-alpha"))
            assertTrue(store1.isGroupLinkedToHub("grp-beta"))

            // Reload in new instance
            val store2 = JvmFileEnterpriseTrustStore(tempDir)
            assertTrue(store2.isEnrolled)
            assertEquals("http://192.168.1.100:8443", store2.getCredentials()?.hubUrl)
            assertEquals("Operations", store2.getCredentials()?.department)
            assertTrue(store2.isGroupLinkedToHub("grp-alpha"))
            assertTrue(store2.isGroupLinkedToHub("grp-beta"))

            store2.unlinkGroupFromHub("grp-alpha")
            assertFalse(store2.isGroupLinkedToHub("grp-alpha"))

            val store3 = JvmFileEnterpriseTrustStore(tempDir)
            assertFalse(store3.isGroupLinkedToHub("grp-alpha"))
            assertTrue(store3.isGroupLinkedToHub("grp-beta"))

            store3.clearCredentials()
            assertFalse(store3.isEnrolled)

            val store4 = JvmFileEnterpriseTrustStore(tempDir)
            assertFalse(store4.isEnrolled)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
