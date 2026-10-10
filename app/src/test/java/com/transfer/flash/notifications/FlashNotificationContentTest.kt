package com.transfer.flash.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashNotificationContentTest {

    @Test
    fun `direct message preserves sender title and plain body`() {
        assertEquals(
            FlashNotificationContent("Alex", "Hello"),
            messageNotificationContent("peer-a", "Alex", "Hello", null),
        )
    }

    @Test
    fun `group message uses group title and prefixes sender`() {
        assertEquals(
            FlashNotificationContent("Team", "Alex: Hello"),
            messageNotificationContent("group-a", "Alex", "Hello", "Team"),
        )
    }

    @Test
    fun `direct attachment preserves sender title and attachment body`() {
        assertEquals(
            FlashNotificationContent("Alex", "Photo: image.jpg"),
            attachmentNotificationContent("peer-a", "Alex", "image.jpg", "image/jpeg", null),
        )
    }

    @Test
    fun `group attachment uses group title and prefixes sender`() {
        assertEquals(
            FlashNotificationContent("Team", "Alex: Voice message: voice.m4a"),
            attachmentNotificationContent("group-a", "Alex", "voice.m4a", "audio/mp4", "Team"),
        )
    }

    @Test
    fun `blank names retain established conversation fallback`() {
        assertEquals(
            FlashNotificationContent("peer-a", "Hello"),
            messageNotificationContent("peer-a", "", "Hello", null),
        )
        assertEquals(
            FlashNotificationContent("Alex", "Hello"),
            messageNotificationContent("group-a", "Alex", "Hello", ""),
        )
    }

    @Test
    fun `transfer completed receiving formats as download complete with size`() {
        val content = transferCompletedNotificationContent(
            fileName = "movie.mp4",
            bytesTotal = 1500L * 1024L * 1024L,
            isReceiving = true,
        )
        assertEquals("Download complete", content.title)
        assertEquals("movie.mp4 (1.5 GB)", content.body)
    }

    @Test
    fun `transfer completed sending formats as transfer complete`() {
        val content = transferCompletedNotificationContent(
            fileName = "photo.jpg",
            bytesTotal = 2L * 1024L * 1024L,
            isReceiving = false,
        )
        assertEquals("Transfer complete", content.title)
        assertEquals("photo.jpg (2.0 MB)", content.body)
    }

    @Test
    fun `transfer completed with zero or negative bytes omits size in body`() {
        val content = transferCompletedNotificationContent(
            fileName = "document.pdf",
            bytesTotal = 0L,
            isReceiving = false,
        )
        assertEquals("Transfer complete", content.title)
        assertEquals("document.pdf", content.body)
    }

    @Test
    fun `transfer failed receiving formats as download failed with error message`() {
        val content = transferFailedNotificationContent(
            fileName = "archive.zip",
            errorMessage = "Connection reset by peer",
            isReceiving = true,
        )
        assertEquals("Download failed", content.title)
        assertEquals("Connection reset by peer", content.body)
    }

    @Test
    fun `transfer failed sending with blank error message uses fallback`() {
        val content = transferFailedNotificationContent(
            fileName = "document.pdf",
            errorMessage = "   ",
            isReceiving = false,
        )
        assertEquals("Transfer failed", content.title)
        assertEquals("Failed to transfer document.pdf", content.body)
    }

    @Test
    fun `format notification bytes formats bytes, kb, mb, and gb accurately`() {
        assertEquals("", formatNotificationBytes(0L))
        assertEquals("", formatNotificationBytes(-100L))
        assertEquals("500 B", formatNotificationBytes(500L))
        assertEquals("15 KB", formatNotificationBytes(15L * 1024L))
        assertEquals("2.5 MB", formatNotificationBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", formatNotificationBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `transfer progress receiving formats progress, speed, and eta`() {
        val content = transferProgressNotificationContent(
            fileName = "movie.mkv",
            bytesDone = 500L * 1024L * 1024L,
            bytesTotal = 1000L * 1024L * 1024L,
            speedBytesPerSec = 20L * 1024L * 1024L,
            etaSeconds = 25L,
            isReceiving = true,
        )
        assertEquals("Downloading movie.mkv — 50%", content.title)
        assertEquals("500.0 MB / 1000.0 MB • 20.0 MB/s • 25s left", content.body)
    }

    @Test
    fun `transfer progress sending with canGoOffline shows swarm indicator`() {
        val content = transferProgressNotificationContent(
            fileName = "dataset.tar",
            bytesDone = 750L * 1024L * 1024L,
            bytesTotal = 1000L * 1024L * 1024L,
            speedBytesPerSec = 15L * 1024L * 1024L,
            etaSeconds = 17L,
            isReceiving = false,
            canGoOffline = true,
        )
        assertEquals("Sending dataset.tar — 75%", content.title)
        assertEquals("You can go offline now • 750.0 MB / 1000.0 MB • 15.0 MB/s • 17s left", content.body)
    }

    @Test
    fun `transfer progress receiving with multiple swarm holders shows peer count`() {
        val content = transferProgressNotificationContent(
            fileName = "update.zip",
            bytesDone = 100L * 1024L * 1024L,
            bytesTotal = 500L * 1024L * 1024L,
            speedBytesPerSec = 5L * 1024L * 1024L,
            etaSeconds = 80L,
            isReceiving = true,
            holdersOnline = 3,
        )
        assertEquals("Downloading update.zip — 20%", content.title)
        assertEquals("Getting it from 3 devices • 100.0 MB / 500.0 MB • 5.0 MB/s • 1m 20s left", content.body)
    }

    @Test
    fun `transfer paused formats title and percentage with size`() {
        val content = transferPausedNotificationContent(
            fileName = "backup.iso",
            bytesDone = 200L * 1024L * 1024L,
            bytesTotal = 1000L * 1024L * 1024L,
            isReceiving = true,
        )
        assertEquals("Download paused", content.title)
        assertEquals("backup.iso (20%) • 200.0 MB / 1000.0 MB", content.body)
    }

    @Test
    fun `format notification speed handles MB and KB per second`() {
        assertEquals("", formatNotificationSpeed(0L))
        assertEquals("", formatNotificationSpeed(-50L))
        assertEquals("500 KB/s", formatNotificationSpeed(500L * 1024L))
        assertEquals("12.5 MB/s", formatNotificationSpeed((12.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `format notification eta formats minutes and seconds`() {
        assertEquals("", formatNotificationEta(0L))
        assertEquals("", formatNotificationEta(-10L))
        assertEquals("45s left", formatNotificationEta(45L))
        assertEquals("2m 30s left", formatNotificationEta(150L))
        assertEquals("1m 0s left", formatNotificationEta(60L))
    }

    @Test
    fun `format notification eta formats hours when over 3600 seconds`() {
        assertEquals("1h 1m left", formatNotificationEta(3665L))
        assertEquals("2h 0m left", formatNotificationEta(7200L))
        assertEquals("3h 15m left", formatNotificationEta(11700L))
    }

    @Test
    fun `transfer offered formats title with peer name and body with size`() {
        val content = transferOfferedNotificationContent(
            fileName = "archive.tar.gz",
            bytesTotal = 500L * 1024L * 1024L,
            peerName = "Pixel 8",
        )
        assertEquals("Incoming transfer from Pixel 8", content.title)
        assertEquals("archive.tar.gz (500.0 MB)", content.body)

        val fallbackContent = transferOfferedNotificationContent(
            fileName = "song.mp3",
            bytesTotal = 10L * 1024L * 1024L,
            peerName = "",
        )
        assertEquals("Incoming transfer from Nearby device", fallbackContent.title)
        assertEquals("song.mp3 (10.0 MB)", fallbackContent.body)
    }

    @Test
    fun `transfer progress with zero bytes done shows zero bytes out of total`() {
        val content = transferProgressNotificationContent(
            fileName = "data.bin",
            bytesDone = 0L,
            bytesTotal = 100L * 1024L * 1024L,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            isReceiving = true,
        )
        assertEquals("Downloading data.bin — 0%", content.title)
        assertEquals("0 B / 100.0 MB", content.body)
    }

    @Test
    fun `notification ID ranges are strictly partitioned without overlap`() {
        val dedicatedIds = setOf(
            41, // FlashBackgroundService
            42, // FlashCallService
            43, // PttSessionService
            FlashNotificationManager.PTT_NOTIFICATION_ID, // 310
            FlashNotificationManager.PTT_TAP_TO_TALK_ID, // 311
            FlashNotificationManager.PAIRING_NOTIFICATION_ID, // 320
        )

        // Test sample conversations
        for (i in 0 until 500) {
            val convId = "test-conv-$i"
            val msgId = FlashNotificationManager.notificationIdFor(convId)
            org.junit.Assert.assertTrue("Message ID $msgId must be in 2000..2999", msgId in 2000..2999)
            org.junit.Assert.assertFalse("Message ID $msgId must not collide with dedicated IDs", dedicatedIds.contains(msgId))

            val xferId = "test-transfer-$i"
            val transferId = FlashNotificationManager.notificationIdForTransfer(xferId)
            org.junit.Assert.assertTrue("Transfer ID $transferId must be in 3000..3999", transferId in 3000..3999)
            org.junit.Assert.assertFalse("Transfer ID $transferId must not collide with dedicated IDs", dedicatedIds.contains(transferId))
        }
    }
}
