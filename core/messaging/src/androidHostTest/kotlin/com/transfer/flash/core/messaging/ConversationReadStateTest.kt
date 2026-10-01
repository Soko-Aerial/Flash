package com.transfer.flash.core.messaging

import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.persistence.db.dao.ConversationDao
import com.transfer.flash.core.persistence.db.entity.ConversationEntity
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERROR-087 (audit 2026-10-01): a chat showed "unread" after it had been opened. Two causes are covered here.
 *
 *  1. The repository wrote a freshly built `ConversationEntity` on every inbound or outbound direct text and on every legacy
 *     group `State` frame. `@Upsert` replaces the whole row, so `lastReadCursor` went back to NULL (a NULL cursor counts
 *     every message the peer ever sent as unread) and `pinned` / `muted` / `archived` were reset. It now refreshes the
 *     stored row. The real-Room side of this (unread arithmetic, transaction serialisation) is
 *     `ConversationRowReadStateJvmTest` in `:core:persistence`; the in-memory DAOs here replace the row exactly like
 *     `@Upsert`, but their unread count ignores the cursor, so these tests assert the ROW, not a badge.
 *  2. The open chat publishes a message to the screen first and writes the cursor second; a close (or opening another chat)
 *     in between cancelled the write and left a message the user was shown unread. Closing now finishes it.
 */
class ConversationReadStateTest {
    private val io = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    @After
    fun shutdown() = io.close()

    /** Holds every cursor write back until [gate] opens, so a test can close the chat between the render and the write. */
    private class GatedCursorConversationDao(val d: InMemoryConversationDao) : ConversationDao by d {
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val cursorWrites = CopyOnWriteArrayList<Pair<String, String>>()

        override suspend fun updateLastReadCursor(id: String, cursor: String) {
            gate?.await()
            cursorWrites += id to cursor
            d.updateLastReadCursor(id, cursor)
        }
    }

    private class RecordingSink : MessageTransportSink {
        val frames = CopyOnWriteArrayList<Pair<String, MessageWireFrame>>()
        override suspend fun send(targetDeviceId: String, frame: MessageWireFrame): Boolean {
            frames += targetDeviceId to frame
            return true
        }
    }

    private fun repo(
        conversations: ConversationDao,
        messages: InMemoryMessageDao,
        sink: MessageTransportSink? = null,
        outbox: InMemoryOutboxDao = InMemoryOutboxDao(),
        members: InMemoryGroupMemberDao? = null,
    ) = RealFlashChatRepository(
        localDeviceId = "me",
        localDisplayName = "Me",
        messageDao = messages,
        conversationDao = conversations,
        outboxDao = outbox,
        receiptDao = NoopReceiptDao(),
        draftDao = NoopDraftDao(),
        recentSearchDao = NoopRecentSearchDao(),
        reactionDao = NoopReactionDao(),
        groupMemberDao = members,
        isTrustedPeer = { true },
        transportSink = sink,
        ioDispatcher = io,
    )

    private fun text(id: String, at: Long, from: String = "peer") = MessageWireFrame.TextMessage(
        localId = id,
        conversationId = "me",
        senderId = from,
        senderName = from,
        text = id,
        sentAt = at,
    )

    private fun waitFor(what: String, block: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            if (block()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun `a closed chat keeps its read cursor when a new message arrives`() = runBlocking {
        val conversations = InMemoryConversationDao()
        val repo = repo(conversations, InMemoryMessageDao())
        repo.onInboundWireFrame(text("m1", 1_000L))
        conversations.updateLastReadCursor("peer", "m1")
        repo.onInboundWireFrame(text("m2", 2_000L))
        assertEquals("m1", conversations.get("peer")!!.lastReadCursor)
    }

    @Test
    fun `an inbound text keeps the chat pinned and muted and still moves it to the top`() = runBlocking {
        val conversations = InMemoryConversationDao()
        val repo = repo(conversations, InMemoryMessageDao())
        repo.onInboundWireFrame(text("m1", 1_000L))
        conversations.setPinned("peer", true)
        conversations.setMuted("peer", true)
        conversations.setArchived("peer", true)

        repo.onInboundWireFrame(text("m2", 2_000L))

        val row = conversations.get("peer")!!
        assertTrue("pinned", row.pinned)
        assertTrue("muted", row.muted)
        assertEquals("a new message brings an archived chat back", false, row.archived)
        assertEquals(2_000L, row.sortOrder)
    }

    @Test
    fun `an inbound text for a chat this device has no row for yet creates it`() = runBlocking {
        val conversations = InMemoryConversationDao()
        val repo = repo(conversations, InMemoryMessageDao())
        repo.onInboundWireFrame(text("m1", 1_000L))
        val row = conversations.get("peer")!!
        assertEquals("peer", row.title)
        assertEquals(false, row.isGroup)
        assertNull(row.lastReadCursor)
    }

    @Test
    fun `sending a text keeps the read cursor pin and mute of the chat`() = runBlocking {
        val conversations = InMemoryConversationDao()
        val messages = InMemoryMessageDao()
        val outbox = InMemoryOutboxDao()
        val repo = repo(conversations, messages, outbox = outbox)
        repo.onInboundWireFrame(text("m1", 1_000L))
        conversations.setPinned("peer", true)
        conversations.setMuted("peer", true)
        repo.openConversation("peer")
        waitFor("cursor m1") { conversations.conversations["peer"]?.lastReadCursor == "m1" }

        repo.sendText("hello")
        waitFor("the outgoing row") { outbox.queue.isNotEmpty() }

        val row = conversations.get("peer")!!
        assertTrue("pinned", row.pinned)
        assertTrue("muted", row.muted)
        // the cursor is NOT reset by the send (it moves to the new head only once the open chat acknowledges it)
        assertTrue("the cursor survives the send", row.lastReadCursor != null)
        repo.closeConversation()
    }

    @Test
    fun `a legacy group state frame keeps the groups pin mute archive and read cursor`() = runBlocking {
        val conversations = InMemoryConversationDao()
        val members = InMemoryGroupMemberDao()
        val repo = repo(conversations, InMemoryMessageDao(), members = members)
        val now = 10_000L
        fun state(name: String, version: Long) = GroupWireFrame.State(
            groupId = "g-legacy",
            from = "owner",
            operationId = "op-$version",
            membershipVersion = version,
            name = name,
            creatorId = "owner",
            members = listOf(
                GroupWireFrame.RosterEntry("owner", "Owner", "owner", now, now, "op-c", true),
                GroupWireFrame.RosterEntry("me", "Me", "member", now, now, "op-c", true),
            ),
        )
        repo.onInboundGroupWireFrame("owner", state("Team", now))
        conversations.setPinned("g-legacy", true)
        conversations.setMuted("g-legacy", true)
        conversations.setArchived("g-legacy", true)
        conversations.updateLastReadCursor("g-legacy", "last-read")
        val before = conversations.get("g-legacy")!!

        // the owner re-sends the roster on every session-up, here with a new name
        repo.onInboundGroupWireFrame("owner", state("Team renamed", now + 1))

        val row = conversations.get("g-legacy")!!
        assertEquals("the rename is applied", "Team renamed", row.title)
        assertTrue("pinned", row.pinned)
        assertTrue("muted", row.muted)
        assertTrue("archived", row.archived)
        assertEquals("last-read", row.lastReadCursor)
        assertEquals("the list position and provenance are kept", before.sortOrder, row.sortOrder)
        assertEquals(before.groupCreatedBy, row.groupCreatedBy)
        assertEquals(before.groupCreatedAt, row.groupCreatedAt)
    }

    @Test
    fun `an open chat follows its newest message with the read cursor`() = runBlocking {
        val conversations = InMemoryConversationDao()
        val sink = RecordingSink()
        val repo = repo(conversations, InMemoryMessageDao(), sink = sink)
        repo.onInboundWireFrame(text("m1", 1_000L))
        repo.openConversation("peer")
        waitFor("cursor m1") { conversations.conversations["peer"]?.lastReadCursor == "m1" }

        repo.onInboundWireFrame(text("m2", 2_000L))

        waitFor("cursor m2") { conversations.conversations["peer"]?.lastReadCursor == "m2" }
        waitFor("a Read receipt up to m2") {
            sink.frames.any { (_, f) -> f is MessageWireFrame.ReadReceipt && f.upToMessageId == "m2" }
        }
        repo.closeConversation()
    }

    @Test
    fun `closing a chat right after a message was shown still marks it read and tells the sender`() = runBlocking {
        val inner = InMemoryConversationDao()
        val conversations = GatedCursorConversationDao(inner)
        val sink = RecordingSink()
        val repo = repo(conversations, InMemoryMessageDao(), sink = sink)
        repo.onInboundWireFrame(text("m1", 1_000L))

        // The cursor write is held back: the message is on screen but not yet acknowledged when the user leaves.
        val gate = CompletableDeferred<Unit>()
        conversations.gate = gate
        repo.openConversation("peer")
        waitFor("m1 on screen") { repo.conversationState.value.messages.any { it.id == "m1" } }
        assertNull("not acknowledged yet", inner.conversations["peer"]?.lastReadCursor)

        conversations.gate = null // writes made after the close go through
        repo.closeConversation()

        waitFor("the flush writes the cursor") { inner.conversations["peer"]?.lastReadCursor == "m1" }
        waitFor("the flush sends the Read receipt") {
            sink.frames.any { (to, f) -> to == "peer" && f is MessageWireFrame.ReadReceipt && f.upToMessageId == "m1" }
        }
        gate.cancel()
    }

    @Test
    fun `opening another chat straight after the first acknowledges the first`() = runBlocking {
        val inner = InMemoryConversationDao()
        val conversations = GatedCursorConversationDao(inner)
        val repo = repo(conversations, InMemoryMessageDao())
        repo.onInboundWireFrame(text("m1", 1_000L))
        repo.onInboundWireFrame(text("o1", 1_500L, from = "other"))

        val gate = CompletableDeferred<Unit>()
        conversations.gate = gate
        repo.openConversation("peer")
        waitFor("m1 on screen") { repo.conversationState.value.messages.any { it.id == "m1" } }
        conversations.gate = null
        repo.openConversation("other")

        waitFor("peer's cursor written when it was left") { inner.conversations["peer"]?.lastReadCursor == "m1" }
        repo.closeConversation()
        gate.cancel()
    }

    @Test
    fun `a chat that is closed and has nothing outstanding writes nothing on close`() = runBlocking {
        val inner = InMemoryConversationDao()
        val conversations = GatedCursorConversationDao(inner)
        val repo = repo(conversations, InMemoryMessageDao())
        repo.onInboundWireFrame(text("m1", 1_000L))
        repo.openConversation("peer")
        waitFor("cursor m1") { inner.conversations["peer"]?.lastReadCursor == "m1" }
        val writes = conversations.cursorWrites.size

        repo.closeConversation()
        Thread.sleep(300)

        assertEquals("the head was already acknowledged", writes, conversations.cursorWrites.size)
    }
}
