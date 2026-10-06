package com.transfer.flash.core.transfer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferFailureTextTest {

    @Test
    fun swarmConstantsAreUserFriendly() {
        assertEquals("The received file did not match what was sent. Ask for it to be sent again.", TransferFailureText.DAMAGED)
        assertEquals("The file is no longer available from the sender.", TransferFailureText.SOURCE_LOST)
        assertEquals("The file changed while it was being sent. Try sending it again.", TransferFailureText.SOURCE_CHANGED)
        assertEquals("Pick the file again to keep sharing.", TransferFailureText.SOURCE_PERMISSION_LOST)
        assertEquals("Choose where to save files.", TransferFailureText.STORAGE_UNAVAILABLE)
        assertEquals("Paused by Android; continues automatically.", TransferFailureText.SYSTEM_TIMEOUT)
        assertEquals("You are no longer in this group.", TransferFailureText.NOT_MEMBER)
        assertEquals("Expired: the missing parts were not available for 7 days.", TransferFailureText.EXPIRED)
        assertEquals("Already on this device.", TransferFailureText.ALREADY_ON_DEVICE)
        assertEquals("Connecting to group members…", TransferFailureText.CONNECTING_MEMBERS)
        assertEquals("Waiting for Wi-Fi.", TransferFailureText.WAITING_FOR_WIFI)
        assertEquals("Waiting for a device that has the missing parts.", TransferFailureText.WAITING_FOR_MISSING_PARTS)
        assertEquals("Checking the file… re-downloading damaged parts.", TransferFailureText.CHECKING_REDOWNLOADING)
    }

    @Test
    fun helperFormattersFormatExpectedSentences() {
        assertEquals("Waiting for Alex to come online", TransferFailureText.waitingForSender("Alex"))
        assertEquals("Waiting for Alex · 50.0 MB of 100.0 MB here", TransferFailureText.waitingForSenderProgress("Alex", 50_000_000L, 100_000_000L))
        assertEquals("Alex's file is no longer available", TransferFailureText.sourceLost("Alex"))
        assertEquals("Alex changed the file after sending it", TransferFailureText.sourceChanged("Alex"))
        assertEquals("Alex left the group before everyone had the file", TransferFailureText.senderDeparted("Alex"))
        assertEquals("Cancelled by Alex", TransferFailureText.cancelledBy("Alex"))
        assertEquals("Deleted by Alex", TransferFailureText.deletedBy("Alex"))
        assertEquals("Needs 1.2 GB, 300.0 MB free", TransferFailureText.needsSpace(1_200_000_000L, 300_000_000L))
        assertTrue(TransferFailureText.notEnoughSpace(1_000_000L, 500_000L).contains("needs 1.0 MB and only 500 KB is available"))
    }

    @Test
    fun friendlyMapsSwarmReasons() {
        assertEquals(TransferFailureText.DAMAGED, TransferFailureText.friendly("DAMAGED"))
        assertEquals(TransferFailureText.DAMAGED, TransferFailureText.friendly("damaged"))
        assertEquals(TransferFailureText.SOURCE_LOST, TransferFailureText.friendly("SOURCE_LOST"))
        assertEquals(TransferFailureText.NOT_MEMBER, TransferFailureText.friendly("NOT_MEMBER"))
        assertEquals(TransferFailureText.EXPIRED, TransferFailureText.friendly("EXPIRED"))
        assertEquals(TransferFailureText.STORAGE_UNAVAILABLE, TransferFailureText.friendly("STORAGE_FAILED"))
        assertEquals(TransferFailureText.STORAGE_UNAVAILABLE, TransferFailureText.friendly("STORAGE_UNAVAILABLE"))
        assertEquals(TransferFailureText.SYSTEM_TIMEOUT, TransferFailureText.friendly("SYSTEM_TIMEOUT"))
        assertEquals(TransferFailureText.SYSTEM_TIMEOUT, TransferFailureText.friendly("SYSTEM_SUSPEND"))
    }

    @Test
    fun friendlyPreservesExistingMappingsAndFallback() {
        assertEquals(TransferFailureText.FILE_CHANGED, TransferFailureText.friendly("source length mismatch"))
        assertEquals(TransferFailureText.UNREADABLE_FILE, TransferFailureText.friendly("source read failed: ENOENT"))
        assertEquals(TransferFailureText.CANCELLED_BY_PEER, TransferFailureText.friendly("cancelled by peer"))
        assertEquals(TransferFailureText.CONNECTION_LOST, TransferFailureText.friendly("all channels failed"))
        assertEquals(TransferFailureText.PEER_NOT_RESPONDING, TransferFailureText.friendly("timed out"))
        assertEquals(TransferFailureText.GENERIC, TransferFailureText.friendly(null))
        assertEquals(TransferFailureText.GENERIC, TransferFailureText.friendly(""))
        assertEquals(TransferFailureText.GENERIC, TransferFailureText.friendly("some arbitrary unhandled error"))
    }
}
