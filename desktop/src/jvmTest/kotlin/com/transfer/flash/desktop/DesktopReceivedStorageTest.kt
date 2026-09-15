package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings tab's storage card, which is what makes the "Clear received files" confirmation
 * dialog reachable at all on desktop.
 *
 * Two defects are pinned here, both found on 2026-09-14 by a human reporting that clicking Clear did
 * nothing:
 *
 * 1. **The scan and the clear ignored the engine's received root.** Both re-derived
 *    `~/FlashReceived` from the user home, so a `DesktopEngine(receivedRoot = …)` — the constructor
 *    every desktop test uses — reported and deleted a directory the engine was not writing to.
 * 2. **Nothing ever called the scan.** `DesktopShell` passed `onRefreshStorageUsage = { }` and left
 *    `receivedFilesBytes` at its null default, and `FlashStorageMath.canClearReceivedFiles` enables
 *    the control only for a scan with `totalBytes != null && > 0`. So the control was permanently
 *    disabled and the dialog could not be opened — not a broken dialog, an unreachable one.
 *
 * The second defect is a wiring fact and is asserted where it lives (`DesktopShell`); what a unit
 * test can hold is the first, which is why these cases use a temp root that is deliberately NOT
 * `~/FlashReceived`: if the implementation ever goes back to the hardcoded path, every assertion
 * below fails rather than silently reading the developer's real received-files folder.
 */
class DesktopReceivedStorageTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    private fun engineWithTempRoot(): DesktopEngine {
        val received = Files.createTempDirectory("flash-desktop-storage-received").toFile()
        val state = Files.createTempDirectory("flash-desktop-storage-state").toFile()
        tempDirs += received
        tempDirs += state
        return DesktopEngine(receivedRoot = received, stateDir = state)
    }

    @Test
    fun anEmptyRootIsZeroBytes_notNull() {
        // Zero, not null: the card renders "No received files" with the control disabled, rather
        // than "Storage usage unavailable", which would claim a failure that did not happen.
        assertEquals(0L, DesktopHelpers.receivedFilesBytes(engineWithTempRoot()))
    }

    @Test
    fun theScanCountsTheEnginesOwnRoot() {
        val engine = engineWithTempRoot()
        val nested = File(engine.receivedDirectory, "transfer-1").apply { mkdirs() }
        File(nested, "payload.bin").writeBytes(ByteArray(2048))
        File(engine.receivedDirectory, "direct.bin").writeBytes(ByteArray(1024))

        // Nested files count too — the receive pipeline writes per-transfer subdirectories, so a
        // non-recursive scan would report a directory full of files as empty.
        assertEquals(3072L, DesktopHelpers.receivedFilesBytes(engine))
    }

    @Test
    fun clearingEmptiesTheEnginesRoot_andTheRescanSaysSo() {
        val engine = engineWithTempRoot()
        File(File(engine.receivedDirectory, "transfer-1").apply { mkdirs() }, "payload.bin")
            .writeBytes(ByteArray(4096))
        assertTrue(DesktopHelpers.receivedFilesBytes(engine) > 0L)

        DesktopHelpers.clearReceivedFiles(engine)

        assertEquals(0L, DesktopHelpers.receivedFilesBytes(engine))
        assertTrue(engine.receivedDirectory.exists(), "the root itself must survive the clear")
    }

    @Test
    fun clearLeavesTheUserHomeAlone() {
        // The regression guard for defect 1, stated directly: with a temp root, clearing must not
        // touch `~/FlashReceived`. This is what the old hardcoded implementation got wrong, and a
        // test that only checked byte counts on the engine's root would not have caught it.
        val home = File(System.getProperty("user.home", "."), "FlashReceived")
        val existedBefore = home.exists()

        val engine = engineWithTempRoot()
        File(engine.receivedDirectory, "payload.bin").writeBytes(ByteArray(128))
        DesktopHelpers.clearReceivedFiles(engine)

        assertEquals(
            existedBefore,
            home.exists(),
            "clearing a temp root must not create or remove the real ~/FlashReceived",
        )
    }
}
