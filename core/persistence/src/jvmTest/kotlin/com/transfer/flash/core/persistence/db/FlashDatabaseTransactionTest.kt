package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import com.transfer.flash.core.persistence.db.entity.OutboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Proves [runInWriteTransaction] is a real transaction for DAO calls made inside it: the outgoing
 * message row and its outbox row commit together, and a failure after the first write rolls both
 * back. Same in-memory BundledSQLiteDriver setup as [FlashDatabaseJvmTest].
 */
class FlashDatabaseTransactionTest {

    private var open: FlashDatabase? = null

    @AfterTest
    fun closeDatabase() {
        open?.close()
        open = null
    }

    private fun openDatabase(): FlashDatabase =
        Room.inMemoryDatabaseBuilder<FlashDatabase>(factory = FlashDatabaseConstructor::initialize)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
            .also { open = it }

    private fun message(id: String) = MessageEntity(
        localId = id,
        conversationId = "peer-a",
        senderId = "self",
        senderName = "Me",
        text = "hello",
        sentAt = 1_000L,
        status = "PENDING",
    )

    private fun outbox(id: String) =
        OutboxEntity(localId = id, nextAttemptAt = 1_000L, payloadJson = "hello", createdAt = 1_000L)

    @Test
    fun `message and outbox rows commit together`() = runBlocking {
        val db = openDatabase()

        db.runInWriteTransaction {
            db.messageDao().insert(message("m1"))
            db.outboxDao().enqueue(outbox("m1"))
        }

        assertNotNull(db.messageDao().getByLocalId("m1"))
        assertEquals(listOf("m1"), db.outboxDao().dueForDelivery(now = 2_000L, limit = 10).map { it.localId })
    }

    @Test
    fun `a failure after the message insert rolls the message back`() = runBlocking {
        val db = openDatabase()

        assertFailsWith<IllegalStateException> {
            db.runInWriteTransaction {
                db.messageDao().insert(message("m2"))
                error("process died before the outbox write")
            }
        }

        assertNull(db.messageDao().getByLocalId("m2"), "orphan PENDING message must not survive")
        assertEquals(emptyList(), db.outboxDao().dueForDelivery(now = 2_000L, limit = 10))
    }
}
