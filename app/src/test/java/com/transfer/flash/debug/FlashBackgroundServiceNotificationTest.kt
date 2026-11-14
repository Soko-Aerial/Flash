package com.transfer.flash.debug

import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for plain-language background service notification copy and branded icons. */
class FlashBackgroundServiceNotificationTest {

    private fun repoFile(relativePath: String): String {
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, relativePath).isFile }
        return File(base, relativePath).readText()
    }

    @Test
    fun `idle notification uses plain language across all discovery modes`() {
        val modes = FlashDiscoveryMode.entries
        for (mode in modes) {
            val content = FlashBackgroundNotificationCopy.idleContent(mode, activeTransfersCount = 0)
            assertTrue(content.title.isNotBlank())
            assertTrue(content.body.isNotBlank())
            assertFalse("$mode title leaked eco jargon", content.title.contains("eco", ignoreCase = true))
            assertFalse("$mode title leaked boost jargon", content.title.contains("boost", ignoreCase = true))
            assertFalse("$mode title leaked kiosk jargon", content.title.contains("kiosk", ignoreCase = true))
            assertFalse("$mode body leaked kiosk jargon", content.body.contains("kiosk", ignoreCase = true))
        }
    }

    @Test
    fun `idle notification formats singular and plural active transfer counts`() {
        val one = FlashBackgroundNotificationCopy.idleContent(FlashDiscoveryMode.STANDARD, activeTransfersCount = 1)
        assertTrue(one.body.contains("1 active transfer"))
        assertFalse(one.body.contains("1 active transfers"))
        assertFalse(one.body.contains("transfer(s)"))

        val three = FlashBackgroundNotificationCopy.idleContent(FlashDiscoveryMode.ECO, activeTransfersCount = 3)
        assertTrue(three.body.contains("3 active transfers"))
    }

    @Test
    fun `background service and notification manager use branded notification icon without legacy stock drawables`() {
        val bgService = repoFile("app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt")
        val notifMgr = repoFile("app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt")

        assertTrue(bgService.contains("R.drawable.ic_notification_flash"))
        assertFalse(bgService.contains("android.R.drawable.stat_notify_sync_noanim"))

        assertTrue(notifMgr.contains("R.drawable.ic_notification_flash"))
        assertFalse(notifMgr.contains("android.R.drawable.stat_sys_download"))
        assertFalse(notifMgr.contains("android.R.drawable.stat_sys_download_done"))
        assertFalse(notifMgr.contains("android.R.drawable.stat_notify_error"))
    }

    @Test
    fun `restoration notification cleanup policy targets restored transfers in terminal states`() {
        val restoredFailed = FlashTransfer(
            id = FlashTransferId("tx-1"),
            peerName = "Peer",
            fileName = "file.zip",
            direction = FlashTransferDirection.Receiving,
            bytesDone = 0L,
            bytesTotal = 100L,
            state = FlashTransferState.Failed,
            restored = true,
        )
        val restoredCompleted = restoredFailed.copy(state = FlashTransferState.Completed)
        val restoredActive = restoredFailed.copy(state = FlashTransferState.Transferring)
        val normalFailed = restoredFailed.copy(restored = false)
        val normalCompleted = restoredFailed.copy(restored = false, state = FlashTransferState.Completed)

        assertTrue(FlashRestorationCleanupPolicy.shouldClearRestoredNotification(restoredFailed))
        assertTrue(FlashRestorationCleanupPolicy.shouldClearRestoredNotification(restoredCompleted))
        assertFalse(FlashRestorationCleanupPolicy.shouldClearRestoredNotification(restoredActive))
        assertFalse(FlashRestorationCleanupPolicy.shouldClearRestoredNotification(normalFailed))
        assertFalse(FlashRestorationCleanupPolicy.shouldClearRestoredNotification(normalCompleted))
    }

    @Test
    fun `background service cleans up foreground notifications in onDestroy and onTimeout`() {
        val bgService = repoFile("app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt")

        // onDestroy must stop foreground and cancel notification 41 + active transfers
        val onDestroySection = bgService.substringAfter("override fun onDestroy()").substringBefore("super.onDestroy()")
        assertTrue(onDestroySection.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(onDestroySection.contains("cancel(NOTIFICATION_ID)"))
        assertTrue(onDestroySection.contains("FlashNotificationManager.clearAllTransfers(this)"))

        // onTimeout must stop foreground and cancel notification 41 + active transfers
        val onTimeoutSection = bgService.substringAfter("override fun onTimeout").substringBefore("super.onTimeout")
        assertTrue(onTimeoutSection.contains("stopForeground(STOP_FOREGROUND_REMOVE)"))
        assertTrue(onTimeoutSection.contains("cancel(NOTIFICATION_ID)"))
        assertTrue(onTimeoutSection.contains("FlashNotificationManager.clearAllTransfers(this)"))
    }

    @Test
    fun `restored transfer cleanup is strictly scoped to uninitialized transfers on startup`() {
        val bgService = repoFile("app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt")

        val loopSection = bgService.substringAfter("previousTransferStates[id] = transfer.state")
            .substringBefore("else if (prevState != transfer.state)")
        assertFalse(
            "shouldClearRestoredNotification must not run unconditionally in transfer loop",
            loopSection.substringBefore("if (prevState == null && transfer.restored)")
                .contains("shouldClearRestoredNotification")
        )
        assertTrue(
            "shouldClearRestoredNotification must be scoped inside prevState == null && transfer.restored block",
            loopSection.substringAfter("if (prevState == null && transfer.restored)")
                .substringBefore("else if (prevState == null)")
                .contains("FlashRestorationCleanupPolicy.shouldClearRestoredNotification(transfer)")
        )
    }

    @Test
    fun `main activity wires current destination to notification manager`() {
        val mainActivity = repoFile("app/src/main/java/com/transfer/flash/MainActivity.kt")
        assertTrue(
            mainActivity.contains("FlashNotificationManager.currentDestination = nav.current.destination")
        )
    }

    @Test
    fun `startup reconciles active Android notifications and removes legacy transfer slots`() {
        val service = repoFile("app/src/main/java/com/transfer/flash/debug/FlashBackgroundService.kt")
        val manager = repoFile("app/src/main/java/com/transfer/flash/notifications/FlashNotificationManager.kt")
        assertTrue(service.contains("FlashNotificationManager.reconcileTransferNotifications("))
        assertTrue(manager.contains("manager.activeNotifications"))
        assertTrue(manager.contains("manager.cancel(tag, notice.id)"))
    }
}
