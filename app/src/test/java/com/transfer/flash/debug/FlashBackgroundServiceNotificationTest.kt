package com.transfer.flash.debug

import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
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
}
