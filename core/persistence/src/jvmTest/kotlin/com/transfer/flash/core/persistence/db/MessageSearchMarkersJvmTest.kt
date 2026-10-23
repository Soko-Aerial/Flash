package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.MessageEntity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * F36 of the 2026-10-22 review: the `text` column carries two internal markers ([MessageTextMarkers]) whose
 * digits and commas match ordinary queries, so a search for "45" used to return a voice note and render
 * `…vmsg:4500:10,24,50,88…` as its snippet. The DAO must not match a marker row by its `text`, at either
 * scope, while still matching ordinary text and still finding such a row by its attachment file name.
 *
 * The queries take the prefixes from [MessageTextMarkers] (the same constants the repository encodes with),
 * so this test is the cross-check that the SQL and the encoders agree.
 */
class MessageSearchMarkersJvmTest {

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

    private fun row(
        localId: String,
        text: String,
        attachmentName: String? = null,
        conversationId: String = "bob",
        sentAt: Long = 1_000L,
    ) = MessageEntity(
        localId = localId,
        conversationId = conversationId,
        senderId = "alice",
        senderName = "Alice",
        text = text,
        sentAt = sentAt,
        status = "SENT",
        attachmentName = attachmentName,
    )

    @Test
    fun `a voice waveform is never matched by its digits, at either scope`() = runBlocking {
        val dao = openDatabase().messageDao()
        dao.insert(row("voice", MessageTextMarkers.VOICE + "4500:10,24,50,88", attachmentName = "voice-1.m4a"))
        dao.insert(row("text", "call me at 4500 sharp", sentAt = 2_000L))

        assertEquals(listOf("text"), dao.searchMessages("4500", 50).map { it.localId })
        assertEquals(listOf("text"), dao.searchConversationMessages("bob", "4500", 50).map { it.localId })

        // The marker itself is not searchable either: the blob never becomes a snippet.
        assertTrue(dao.searchMessages("vmsg:", 50).isEmpty(), "the raw marker must not be a text match")
        assertTrue(dao.searchMessages("10,24", 50).isEmpty(), "waveform samples must not be a text match")
    }

    @Test
    fun `a call event marker is never matched by its encoded fields`() = runBlocking {
        val dao = openDatabase().messageDao()
        dao.insert(row("call", MessageTextMarkers.CALL + "Missed:0:30000"))
        dao.insert(row("text", "we talked for 30000 ms", sentAt = 2_000L))

        assertEquals(listOf("text"), dao.searchMessages("30000", 50).map { it.localId })
        assertTrue(dao.searchMessages("cmsg:", 50).isEmpty())
        assertTrue(dao.searchConversationMessages("bob", "Missed", 50).isEmpty())
    }

    @Test
    fun `a marker row is still found by its file name and normal text still matches`() = runBlocking {
        val dao = openDatabase().messageDao()
        dao.insert(row("voice", MessageTextMarkers.VOICE + "4500:10,24,50,88", attachmentName = "Budget_note.m4a"))
        dao.insert(row("plain", "the budget is agreed", sentAt = 2_000L))

        assertEquals(setOf("voice", "plain"), dao.searchMessages("budget", 50).map { it.localId }.toSet())
        // In-conversation search too: a voice note's own file name is searchable content.
        assertEquals(setOf("voice", "plain"), dao.searchConversationMessages("bob", "budget", 50).map { it.localId }.toSet())
    }

    @Test
    fun `an empty text row still matches every query, as SQL always did`() = runBlocking {
        val dao = openDatabase().messageDao()
        dao.insert(row("empty", ""))
        dao.insert(row("named", "", attachmentName = "notes.txt"))

        // Only the file-name match may pull a bodyless row in; the empty text itself is not a match.
        assertEquals(listOf("named"), dao.searchMessages("notes", 50).map { it.localId })
    }
}
