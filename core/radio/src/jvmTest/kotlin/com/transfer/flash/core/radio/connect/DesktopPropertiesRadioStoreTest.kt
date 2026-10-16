package com.transfer.flash.core.radio.connect

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopPropertiesRadioStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun radlnkU11_roundtripRememberedRadioAndAuthorisationAtomic() = runTest {
        val stateDir = tempFolder.newFolder("flash-state")
        val store = DesktopPropertiesRadioStore(stateDir)

        // Initially empty
        assertNull(store.rememberedStore.load())
        assertNull(store.authorisationStore.load())

        // Save remembered radio
        val radio = RememberedRadio(
            bluetoothAddress = "38:D2:00:01:35:B5",
            displayName = "VR-N76",
            lastKnownPortName = "COM16"
        )
        store.rememberedStore.save(radio)

        val loadedRadio = store.rememberedStore.load()
        assertNotNull(loadedRadio)
        assertEquals(radio.bluetoothAddress, loadedRadio?.bluetoothAddress)
        assertEquals(radio.displayName, loadedRadio?.displayName)
        assertEquals(radio.lastKnownPortName, loadedRadio?.lastKnownPortName)

        // Save authorization confirmation
        val auth = AuthorisationConfirmation(
            confirmedAtEpochMs = 1729000000000L,
            operatorCallsign = "N0CALL"
        )
        store.authorisationStore.save(auth)

        val loadedAuth = store.authorisationStore.load()
        assertNotNull(loadedAuth)
        assertEquals(auth.confirmedAtEpochMs, loadedAuth?.confirmedAtEpochMs)
        assertEquals(auth.operatorCallsign, loadedAuth?.operatorCallsign)

        // Verify radio survives restart (new store instance pointing to same dir)
        val secondStore = DesktopPropertiesRadioStore(stateDir)
        assertEquals(radio, secondStore.rememberedStore.load())
        assertEquals(auth, secondStore.authorisationStore.load())

        // Clear remembered
        store.rememberedStore.clear()
        assertNull(store.rememberedStore.load())
        // Authorisation preserved
        assertNotNull(store.authorisationStore.load())

        // Clear authorization
        store.authorisationStore.clear()
        assertNull(store.authorisationStore.load())
    }

    @Test
    fun radlnkU12_corruptedPropertiesFileYieldsNullWithoutCrashing() = runTest {
        val stateDir = tempFolder.newFolder("flash-state-corrupt")
        val propFile = File(stateDir, "radio.properties")

        // Write non-properties binary garbage
        propFile.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0xFE.toByte(), 0x12, 0x5A))

        val store = DesktopPropertiesRadioStore(stateDir)
        // Must return null, never throw or crash
        assertNull(store.rememberedStore.load())
        assertNull(store.authorisationStore.load())

        // Write properties with negative or non-numeric epoch
        propFile.writeText(
            """
            authorisation.confirmed_at_epoch_ms=not-a-number
            remembered.bluetooth_address=
            """.trimIndent()
        )

        assertNull(store.rememberedStore.load())
        assertNull(store.authorisationStore.load())
    }

    @Test
    fun radlnkU13_noCryptographicKeysStoredInProperties() = runTest {
        val stateDir = tempFolder.newFolder("flash-state-keys")
        val store = DesktopPropertiesRadioStore(stateDir)

        store.rememberedStore.save(
            RememberedRadio("38:D2:00:01:35:B5", "VR-N76")
        )
        store.authorisationStore.save(
            AuthorisationConfirmation(1729000000000L, "W1AW")
        )

        val propFile = File(stateDir, "radio.properties")
        assertTrue(propFile.isFile)
        val text = propFile.readText()

        assertFalse(text.contains("private", ignoreCase = true))
        assertFalse(text.contains("secret", ignoreCase = true))
        assertFalse(text.contains("key", ignoreCase = true))
        assertFalse(text.contains("cipher", ignoreCase = true))
    }
}
