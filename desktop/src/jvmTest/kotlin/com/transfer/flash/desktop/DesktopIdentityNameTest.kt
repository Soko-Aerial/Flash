package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceNames
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** ERROR-077: every PC used to be "Flash Desktop"; defaults are now an animal or a fruit from the device id. */
class DesktopIdentityNameTest {

    private val dirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun dir(): File = Files.createTempDirectory("flash-identity").toFile().also { dirs += it }

    private fun write(dir: File, text: String) = File(dir, "identity.properties").writeText(text)

    @Test
    fun newInstallGetsAStableAnimalOrFruitName() {
        val dir = dir()
        val first = DesktopIdentityStore(dir).getIdentity()
        assertEquals(FlashDeviceNames.forDeviceId(first.deviceId.value), first.friendlyName)
        assertNotEquals("Flash Desktop", first.friendlyName)
        // A relaunch keeps both.
        val again = DesktopIdentityStore(dir).getIdentity()
        assertEquals(first, again)
    }

    @Test
    fun oldDefaultNameIsReplacedOnceAndTheIdKept() {
        val dir = dir()
        write(dir, "deviceId=2e3f3160-475f-409a-b326-afee9c6c524d\nfriendlyName=Flash Desktop\n")
        val identity = DesktopIdentityStore(dir).getIdentity()
        assertEquals("2e3f3160-475f-409a-b326-afee9c6c524d", identity.deviceId.value)
        assertEquals(FlashDeviceNames.forDeviceId(identity.deviceId.value), identity.friendlyName)
    }

    @Test
    fun chosenNamesAreKept() {
        val dir = dir()
        write(dir, "deviceId=abc\nfriendlyName=Office PC\n")
        assertEquals("Office PC", DesktopIdentityStore(dir).getIdentity().friendlyName)
    }

    @Test
    fun ownerMayChooseTheOldNameAfterTheMigration() {
        val dir = dir()
        val store = DesktopIdentityStore(dir)
        store.getIdentity()
        store.updateFriendlyName("Flash Desktop")
        assertEquals("Flash Desktop", DesktopIdentityStore(dir).getIdentity().friendlyName)
    }

    @Test
    fun namesAreStableAndSpread() {
        assertEquals(FlashDeviceNames.forDeviceId("8adbb08e-1134-4d5e-9998-80e9ca5eac30"),
            FlashDeviceNames.forDeviceId("8adbb08e-1134-4d5e-9998-80e9ca5eac30"))
        val names = (0 until 400).map { FlashDeviceNames.forDeviceId(java.util.UUID.randomUUID().toString()) }.toSet()
        assertTrue(names.all { it.startsWith("Flash ") && it.length > "Flash ".length })
        assertTrue(names.size > 60, "400 ids drew only ${names.size} names")
    }
}
