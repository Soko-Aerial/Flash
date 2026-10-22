@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.persistence.db.runInWriteTransaction
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * F36 of the 2026-10-22 review, through the real database and the real repository: a voice note's
 * waveform and a call event's encoded fields live in the `text` column, and their digits used to match
 * ordinary search queries and leak into the result snippet. The row must stay findable by its
 * attachment file name, and ordinary text must match exactly as before.
 *
 * Two layers cooperate and both are exercised here:
 *  - `MessageDao.searchMessages` never matches a marker row by its `text` (the SQL filter), and
 *  - `RealFlashChatRepository.searchChats` refuses to turn such a row into a *message* hit even when
 *    the row came back because its file name matched — otherwise the snippet would be the raw blob.
 */
class RealFlashChatRepositorySearchMarkersTest {

    private lateinit var tempDir: File
    private val dbs = mutableListOf<FlashDatabase>()
    private val repos = mutableListOf<RealFlashChatRepository>()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-search-markers").toFile()
    }

    @AfterTest
    fun tearDown() {
        repos.forEach { it.close() }
        dbs.forEach { it.close() }
        tempDir.deleteRecursively()
    }

    private fun device(): Pair<FlashDatabase, RealFlashChatRepository> {
        val db = openEncryptedFlashDatabase(File(tempDir, "search.db"), "key-search").also { dbs += it }
        val repo = RealFlashChatRepository(
            localDeviceId = "alice",
            localDisplayName = "Alice",
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            isTrustedPeer = { false },
            transportSink = MessageTransportSink { _, _ -> false },
            runInTransaction = { block -> db.runInWriteTransaction(block) },
        ).also { repos += it }
        return db to repo
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 8_000, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
            delay(20)
        }
    }

    private suspend fun rows(db: FlashDatabase) =
        db.messageDao().historyBefore("bob", Long.MAX_VALUE, "\uFFFF", 50)

    @Test
    fun `a voice waveform is never a text hit even when the file name matches the same digits`(): Unit =
        runBlocking {
            val (db, repo) = device()

            // The file name deliberately contains the same "4500" as the waveform: the file may be found
            // by its name, but the waveform must never become the message's snippet.
            repo.sendAttachment(
                conversationId = "bob",
                transferId = "t-voice",
                fileName = "Kazoo_4500.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 12_345L,
                localPath = null,
                voiceDurationMs = 4_500L,
                voiceAmplitudes = listOf(10, 24, 50, 88),
            )
            awaitUntil("the voice row") { rows(db).any { it.attachmentTransferId == "t-voice" } }
            repo.sendTextTo("bob", "the budget is agreed")
            awaitUntil("the text row") { rows(db).any { it.text == "the budget is agreed" } }

            // The audit's example: the waveform's digits are not content. The row comes back from the
            // attachment-name match, so without the snippet guard this produced a `vmsg:4500:10,24,...` hit.
            val byDigits = repo.searchChats("4500")
            assertEquals(1, byDigits.fileHits.size, "the file name is searchable content: $byDigits")
            assertTrue(byDigits.messageHits.isEmpty(), "a waveform must not produce a text hit: $byDigits")

            // Neither the raw marker nor the sample list is matchable at all.
            assertTrue(repo.searchChats("vmsg:").isEmpty, "the raw marker must not be matchable")
            assertTrue(repo.searchChats("10,24").isEmpty, "waveform samples must not be matchable")
            assertTrue(repo.searchConversationMessages("bob", "10,24").isEmpty(), "in-chat search too")

            // Finding it by name still works, at both scopes and with no message hit beside it.
            val byName = repo.searchChats("Kazoo")
            assertEquals(1, byName.fileHits.size, "a voice note is a real attachment: $byName")
            assertTrue(byName.messageHits.isEmpty(), "its body is still not content: $byName")
            assertEquals(1, repo.searchConversationMessages("bob", "Kazoo").size)

            // Ordinary text keeps matching exactly as before.
            val byText = repo.searchChats("agreed")
            assertEquals(listOf("the budget is agreed"), byText.messageHits.map { it.snippet })
            assertTrue(byText.fileHits.isEmpty(), "the sentence is not a file: $byText")
        }

    @Test
    fun `a call event is never matched by its encoded fields`(): Unit = runBlocking {
        val (db, repo) = device()

        repo.recordCallEvent(
            peerDeviceId = "bob",
            callId = "call-1",
            peerName = "Bob",
            outgoing = false,
            video = false,
            durationMs = 30_000L,
            endedAt = System.currentTimeMillis(),
        )
        awaitUntil("the call row") { rows(db).any { it.localId == "call-1" } }
        repo.sendTextTo("bob", "we talked for 30000 ms")
        awaitUntil("the text row") { rows(db).any { it.text == "we talked for 30000 ms" } }

        // The call row is `cmsg:Incoming:0:30000`; only the typed sentence is content.
        val hits = repo.searchChats("30000")
        assertEquals(1, hits.messageHits.size, "only the typed sentence is content: $hits")
        assertEquals("we talked for 30000 ms", hits.messageHits.single().snippet)

        assertTrue(repo.searchChats("cmsg:").isEmpty, "the raw marker must not be matchable")
        assertTrue(repo.searchChats("Incoming").isEmpty, "the encoded kind is not content either")
        assertTrue(repo.searchConversationMessages("bob", "cmsg:").isEmpty(), "in-chat search too")
    }
}
