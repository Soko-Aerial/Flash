package com.transfer.flash.server.observability

/**
 * Read-only mailbox numbers for the admin dashboard, `/health/detail` and `/metrics`. Implemented by
 * [com.transfer.flash.server.mailbox.MailboxQueue]. The calls read the database, so call them off the event loop
 * (`Dispatchers.IO`); they never mutate anything.
 *
 * The three `*Total` counters count since the process started (a restart resets them; the queue itself is durable).
 */
interface MailboxStats {
    /** Rows currently stored and not yet acknowledged by their recipient (expired rows still count until swept). */
    fun queuedCount(): Long

    /** Payload bytes of those rows. */
    fun queuedBytes(): Long

    /** Age in ms of the oldest undelivered row for [deviceId], by the server clock, or null when it has none. */
    fun oldestAgeMs(deviceId: String): Long?

    /** The devices with the oldest waiting messages, oldest first: `deviceId -> age ms`. For the dashboard. */
    fun oldestAgesMs(limit: Int = 10): Map<String, Long>

    /** Rows deleted because the recipient acknowledged them (`FLASH_RECV_ACK`). */
    fun deliveredTotal(): Long

    /** Rows deleted because their time to live ran out before the recipient acknowledged them. */
    fun expiredTotal(): Long

    /** Messages the mailbox refused or lost (quota, unknown recipient, storage error). */
    fun droppedTotal(): Long

    /** Stored rows that could not be opened (data key lost or changed) and were quarantined since the process started (RLY-01). */
    fun quarantinedTotal(): Long = 0

    /** Stats of a hub that has no mailbox. */
    object Empty : MailboxStats {
        override fun queuedCount(): Long = 0
        override fun queuedBytes(): Long = 0
        override fun oldestAgeMs(deviceId: String): Long? = null
        override fun oldestAgesMs(limit: Int): Map<String, Long> = emptyMap()
        override fun deliveredTotal(): Long = 0
        override fun expiredTotal(): Long = 0
        override fun droppedTotal(): Long = 0
    }
}
