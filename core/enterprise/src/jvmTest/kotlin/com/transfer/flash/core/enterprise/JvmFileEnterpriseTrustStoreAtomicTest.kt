@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLI-12: the trust file is replaced atomically, a failed write changes nothing and says so. */
class JvmFileEnterpriseTrustStoreAtomicTest {

    private val org = TestOrg()
    private val device = TestCrypto()

    private fun withDir(block: suspend (File) -> Unit) = runTest {
        val dir = Files.createTempDirectory("hubc-atomic").toFile()
        try {
            block(dir)
        } finally {
            dir.setWritable(true)
            dir.deleteRecursively()
        }
    }

    @Test
    fun aLeftOverTempFileFromACrashedWriteDoesNotAffectLoadingOrTheNextSave() = withDir { dir ->
        val first = JvmFileEnterpriseTrustStore(dir)
        first.saveCredentials(org.credentialsFor("dev-1", device, serial = 1L))
        File(dir, JvmFileEnterpriseTrustStore.FILE_NAME + ".tmp").writeText("garbage that was never moved into place")

        val reloaded = JvmFileEnterpriseTrustStore(dir)
        assertEquals(1L, reloaded.credentials.value?.deviceCert?.serial)
        reloaded.saveCredentials(org.credentialsFor("dev-1", device, serial = 2L))
        assertEquals(2L, JvmFileEnterpriseTrustStore(dir).credentials.value?.deviceCert?.serial)
    }

    @Test
    fun aFailedWriteLeavesTheOldCredentialsInMemoryAndOnDiskAndReportsTheError() = withDir { dir ->
        val store = JvmFileEnterpriseTrustStore(dir)
        store.saveCredentials(org.credentialsFor("dev-1", device, serial = 1L))
        assertNull(store.storageError.value)

        // Make the target un-replaceable: a directory sits where the temp file must be created.
        val tmp = File(dir, JvmFileEnterpriseTrustStore.FILE_NAME + ".tmp")
        tmp.delete()
        tmp.mkdir()
        File(tmp, "blocker").writeText("x")

        assertFailsWith<IOException> { store.saveCredentials(org.credentialsFor("dev-1", device, serial = 2L)) }
        assertNotNull(store.storageError.value)
        assertEquals(1L, store.credentials.value?.deviceCert?.serial, "memory must not run ahead of the disk")
        assertEquals(1L, JvmFileEnterpriseTrustStore(dir).credentials.value?.deviceCert?.serial)

        tmp.deleteRecursively()
        store.saveCredentials(org.credentialsFor("dev-1", device, serial = 3L))
        assertNull(store.storageError.value, "a later good write clears the error")
    }

    @Test
    fun clearingMovesLinkedGroupsIntoThePreviousLinksInOneWrite() = withDir { dir ->
        val store = JvmFileEnterpriseTrustStore(dir)
        store.saveCredentials(org.credentialsFor("dev-1", device))
        store.linkGroupToHub("g-1")
        store.clearCredentials()

        val reloaded = JvmFileEnterpriseTrustStore(dir)
        assertNull(reloaded.credentials.value)
        assertTrue(reloaded.linkedHubGroups.value.isEmpty())
        assertEquals(setOf("g-1"), reloaded.previousHubLinks.value?.groupIds)
        reloaded.clearPreviousHubLinks()
        assertNull(JvmFileEnterpriseTrustStore(dir).previousHubLinks.value)
    }

    @Test
    fun aPendingUnenrolSurvivesARestart() = withDir { dir ->
        val creds = org.credentialsFor("dev-1", device)
        val store = JvmFileEnterpriseTrustStore(dir)
        store.savePendingUnenrol(creds)
        assertEquals(creds, JvmFileEnterpriseTrustStore(dir).pendingUnenrol.value)
        store.savePendingUnenrol(null)
        assertNull(JvmFileEnterpriseTrustStore(dir).pendingUnenrol.value)
    }

    @Test
    fun unreadableStoredCredentialsAreReportedNotSilentlyIgnored() = withDir { dir ->
        File(dir, JvmFileEnterpriseTrustStore.FILE_NAME).writeText("credentials=not json at all\n")
        val store = JvmFileEnterpriseTrustStore(dir)
        assertNull(store.credentials.value)
        assertNotNull(store.storageError.value)
    }
}
