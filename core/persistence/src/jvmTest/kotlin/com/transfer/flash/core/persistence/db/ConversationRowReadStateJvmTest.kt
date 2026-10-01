package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ERROR-087 (audit 2026-10-01, fixed in code 2026-10-01), real Room + SQLite.
 *
 * `ConversationDao.upsert` is `@Upsert`, a FULL-ROW replace, so a freshly built `ConversationEntity(...)` put
 * `lastReadCursor` back to NULL (a NULL cursor counts every message the peer ever sent as unread) and `pinned` / `muted` /
 * `archived` back to false. The chat repository now reads the stored row and writes a copy of it, inside the same write
 * transaction as the message insert. These tests pin the three facts that fix rests on, none of which an in-memory fake
 * can show: the replace semantics, the unread arithmetic of the copy, and that a transaction really serialises a cursor
 * write against the read-modify-write.
 */
class ConversationRowReadStateJvmTest {
    private var open: FlashDatabase? = null

    @AfterTest
    fun close() {
        open?.close()
        open = null
    }

    private fun db(): FlashDatabase =
        Room.inMemoryDatabaseBuilder<FlashDatabase>(factory = FlashDatabaseConstructor::initialize)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { open = it }

    private fun message(id: String, sender: String, sentAt: Long) = MessageEntity(
        localId = id,
        conversationId = "peer",
        senderId = sender,
        senderName = sender,
        text = id,
        sentAt = sentAt,
        status = "DELIVERED",
    )

    /** The shape of `RealFlashChatRepository.upsertDirectConversation`: read the stored row, write a copy of it. */
    private suspend fun FlashDatabase.refreshDirectRow(sortOrder: Long, afterRead: suspend () -> Unit = {}) {
        val conversations = conversationDao()
        val existing = conversations.get("peer")
        afterRead()
        conversations.upsert(
            existing?.copy(title = "Alex", sortOrder = sortOrder, archived = false)
                ?: ConversationEntity(id = "peer", title = "Alex", isGroup = false, sortOrder = sortOrder),
        )
    }

    @Test
    fun `a full-row upsert resets the read cursor pin and mute - the reason the repository copies the row`() = runBlocking {
        val database = db()
        val conversations = database.conversationDao()
        conversations.upsert(
            ConversationEntity(id = "peer", title = "Alex", isGroup = false, pinned = true, muted = true, lastReadCursor = "m3"),
        )
        conversations.upsert(ConversationEntity(id = "peer", title = "Alex", isGroup = false, sortOrder = 4L))
        val row = assertNotNull(conversations.get("peer"))
        assertEquals(null, row.lastReadCursor, "Room's @Upsert replaces the whole row")
        assertEquals(false, row.pinned)
        assertEquals(false, row.muted)
    }

    @Test
    fun `refreshing the row inside the insert transaction keeps the cursor and counts one new message as one unread`() = runBlocking {
        val database = db()
        val conversations = database.conversationDao()
        val messages = database.messageDao()
        conversations.upsert(
            ConversationEntity(id = "peer", title = "Alex", isGroup = false, pinned = true, muted = true, lastReadCursor = "m3"),
        )
        messages.insert(message("m1", "peer", 1L))
        messages.insert(message("m2", "self", 2L))
        messages.insert(message("m3", "peer", 3L))
        assertEquals(true, messages.observeUnreadCounts("self").first().isEmpty(), "everything read before the new message")

        // exactly what RealFlashChatRepository.onInboundWireFrame does for a TextMessage
        database.runInWriteTransaction {
            messages.insert(message("m4", "peer", 4L))
            database.refreshDirectRow(sortOrder = 4L)
        }

        val row = assertNotNull(conversations.get("peer"))
        assertEquals("m3", row.lastReadCursor, "the cursor survives")
        assertEquals(true, row.pinned, "pinned survives")
        assertEquals(true, row.muted, "muted survives")
        assertEquals(4L, row.sortOrder, "and the chat still moves to the top")
        assertEquals(1, messages.observeUnreadCounts("self").first().single().unread, "one new message is one unread")
    }

    @Test
    fun `a cursor written while the row is being refreshed is never overwritten with the stale copy`() = runBlocking {
        val database = db()
        val conversations = database.conversationDao()
        val messages = database.messageDao()
        conversations.upsert(ConversationEntity(id = "peer", title = "Alex", isGroup = false, lastReadCursor = "old"))
        messages.insert(message("m1", "peer", 1L))

        // The open chat acknowledging the new message races the inbound path's read-modify-write. Whichever order they
        // land in, the cursor must end on the acknowledged id: the write transaction serialises them. (Without the
        // transaction the refresh reads "old", the cursor write lands inside the pause, and the copy puts "old" back.)
        repeat(20) { round ->
            val acked = "ack-$round"
            val refresh = async(Dispatchers.IO) {
                database.runInWriteTransaction {
                    messages.insert(message("n$round", "peer", 10L + round))
                    database.refreshDirectRow(sortOrder = 10L + round, afterRead = { delay(15) })
                }
            }
            val ack = async(Dispatchers.IO) {
                delay(5)
                conversations.updateLastReadCursor("peer", acked)
            }
            awaitAll(refresh, ack)
            assertEquals(acked, assertNotNull(conversations.get("peer")).lastReadCursor, "round $round")
        }
    }
}
