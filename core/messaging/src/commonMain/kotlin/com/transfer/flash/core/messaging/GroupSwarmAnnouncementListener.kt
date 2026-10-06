package com.transfer.flash.core.messaging

/**
 * Listener invoked when a verified group media swarm announcement arrives (§7A SW-8 Task 6).
 */
public fun interface GroupSwarmAnnouncementListener {
    public fun onSwarmAnnouncement(
        groupId: String,
        messageId: String,
        transferId: String,
        from: String,
        root: String,
        pieceSize: Int,
        totalSize: Long,
        fileName: String,
        mimeType: String,
        sentAt: Long,
        rootSig: String,
    )
}
