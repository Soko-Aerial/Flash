@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging

import com.transfer.flash.core.enterprise.HubPeerDirectory
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.messaging.hub.FakeHubRelay
import com.transfer.flash.core.messaging.hub.HubChatBridge
import com.transfer.flash.core.messaging.hub.HubFileRoute
import com.transfer.flash.core.messaging.hub.HubInbound
import com.transfer.flash.core.messaging.hub.HubSealingStatus
import com.transfer.flash.core.messaging.hub.ListedHubDirectory
import com.transfer.flash.core.messaging.hub.ScriptedHubSealing
import com.transfer.flash.core.messaging.protocol.HubFileFrame
import com.transfer.flash.core.messaging.protocol.HubFileFrameCodec
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.persistence.db.runInWriteTransaction
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials
import com.transfer.flash.core.security.enterprise.HubFileKey
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * HUBFILE-U19..U25 (ADR-216): the ticket message of a file sent through the hub, between two repositories on real
 * encrypted databases. The relay is scripted; what one device sends is handed to the other by [pump].
 */
class RealFlashChatRepositoryHubFileTest {

    private lateinit var tempDir: File
    private val dbs = mutableListOf<FlashDatabase>()
    private val repos = mutableListOf<RealFlashChatRepository>()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("flash-hubfile-chat").toFile()
    }

    @AfterTest
    fun tearDown() {
        repos.forEach { it.close() }
        dbs.forEach { it.close() }
        tempDir.deleteRecursively()
    }

    private class Device(
        val id: String,
        val db: FlashDatabase,
        val relay: FakeHubRelay,
        val repo: RealFlashChatRepository,
        val sealing: ScriptedHubSealing,
        val notified: AtomicInteger,
    ) {
        var forwarded = 0
    }

    private fun credentials(id: String) = EnrollmentCredentials(
        hubUrl = "https://hub.example.test",
        rootFingerprint = "fp",
        rootPublicKey = "root",
        deviceCert = DeviceCert(
            deviceId = id, subjectPublicKey = "pk-$id", role = "member", department = "Ops",
            validFrom = 1L, validTo = Long.MAX_VALUE / 2, serial = 1L, issuerPublicKey = "hub", issuerSignature = "sig",
        ),
    )

    private fun device(id: String, takesHubFiles: Boolean = true): Device {
        val db = openEncryptedFlashDatabase(File(tempDir, "$id.db"), "key-$id").also { dbs += it }
        val relay = FakeHubRelay()
        val sealing = ScriptedHubSealing()
        val notified = AtomicInteger()
        val trust = MemoryEnterpriseTrustStore(credentials(id))
        val directory = ListedHubDirectory(setOf("alice", "bob", "mallory"))
        val repo = RealFlashChatRepository(
            localDeviceId = id,
            localDisplayName = id.replaceFirstChar { it.uppercase() },
            messageDao = db.messageDao(),
            conversationDao = db.conversationDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            transportSink = MessageTransportSink { _, _ -> false },
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            onInboundAttachmentWithGroupTitle = { _, _, _, _, _ -> notified.incrementAndGet() },
            enterpriseTrustStore = trust,
            hubRelay = relay,
            enterpriseDirectoryClient = directory,
            hubPeers = HubPeerDirectory(directory, hubUrl = { "https://hub.example.test" }, minRefreshMs = 0L),
            hubSealing = sealing,
            hubFileDao = if (takesHubFiles) db.hubFileDao() else null,
        ).also { repos += it }
        return Device(id, db, relay, repo, sealing, notified)
    }

    /** Hands everything [from] sent since the last pump to [to]'s relay, as the hub would. */
    private fun pump(from: Device, to: Device) {
        val sent = from.relay.sent
        for (s in sent.drop(from.forwarded)) {
            if (s.to == to.id) to.relay.deliver(HubInbound(from = from.id, id = s.id, type = s.type, sentAt = s.sentAt, payload = s.payload))
        }
        from.forwarded = sent.size
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 8_000, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
            delay(20)
        }
    }

    private val fileId = "0123456789abcdef0123456789abcdef"
    private val outId = HubFileStates.TRANSFER_PREFIX_OUT + fileId
    private val inId = HubFileStates.TRANSFER_PREFIX_IN + fileId
    private val key = HubFileKey.generate()

    /** What the hub-file engine writes when it claims a send (the binding's job; here by hand). */
    private suspend fun claimUpload(
        d: Device,
        peer: String = "bob",
        name: String = "report.pdf",
        mime: String = "application/pdf",
        state: String = HubFileStates.UPLOADING,
    ) {
        d.db.hubFileDao().insert(
            HubFileEntity(
                transferId = outId, direction = HubFileStates.OUT, fileId = fileId, peerId = peer, hubUrl = "https://hub.example.test",
                fileName = name, mime = mime, plainBytes = 3_000_000L, sourceUri = "content://doc/1",
                fileKey = null, cipherSha256 = null, cipherChunkBytes = 0, chunkCount = 0, expiresAtMs = 0L, messageId = null,
                state = state, paused = false, errorText = null, bytesDone = 0L, localPath = null,
                createdAtMs = 1L, updatedAtMs = 1L,
            ),
        )
    }

    private suspend fun finishUpload(d: Device, expiresAtMs: Long = System.currentTimeMillis() + 86_400_000L) {
        assertEquals(1, d.db.hubFileDao().markUploaded(outId, key.encode(), "ab".repeat(32), 1_048_576, 3, expiresAtMs, 2L))
    }

    private suspend fun sentMessage(d: Device) = d.db.messageDao().historyBefore("bob", Long.MAX_VALUE, "￿", 10)
        .singleOrNull { it.attachmentTransferId == outId }

    private fun tickets(d: Device) = d.relay.sent.filter { it.type == HubSealing.ENVELOPE_TYPE && it.payload.startsWith(HubChatBridge.HUB_FILE + "\n") }

    @Test
    fun `HUBFILE-U19 a ticket goes once the upload and the message both exist, and the receipt delivers it`(): Unit = runBlocking {
        val alice = device("alice")
        val bob = device("bob")
        assertEquals(HubFileRoute.READY, alice.repo.hubFileRoute("bob"))

        claimUpload(alice)
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("the message row") { sentMessage(alice) != null }
        assertEquals("PENDING", sentMessage(alice)!!.status, "a hub file waits for the receipt, it is not Sent")
        assertFalse(alice.repo.queueHubFile(outId), "the upload is still running")
        delay(100)
        assertTrue(tickets(alice).isEmpty(), "no ticket before the file is on the hub")

        finishUpload(alice)
        assertTrue(alice.repo.queueHubFile(outId))
        assertFalse(alice.repo.queueHubFile(outId), "queued exactly once")
        awaitUntil("the ticket on the relay") { tickets(alice).isNotEmpty() }
        assertEquals(HubFileStates.QUEUED, alice.db.hubFileDao().get(outId)!!.state)

        pump(alice, bob)
        awaitUntil("bob's hub_file row") { bob.db.hubFileDao().get(inId) != null }
        val row = bob.db.hubFileDao().get(inId)!!
        assertEquals(HubFileStates.OFFERED, row.state)
        assertEquals("alice", row.peerId)
        assertEquals(key.encode(), row.fileKey)
        assertEquals("https://hub.example.test", row.hubUrl, "the recipient fetches from its own hub")
        val received = bob.db.messageDao().getByLocalId(sentMessage(alice)!!.localId)
        assertNotNull(received)
        assertEquals(inId, received.attachmentTransferId)
        assertEquals("report.pdf", received.attachmentName)
        assertEquals(1, bob.notified.get())

        awaitUntil("bob's receipt") { bob.relay.sent.isNotEmpty() }
        pump(bob, alice)
        awaitUntil("alice's message delivered") { sentMessage(alice)?.status == "DELIVERED" }
        awaitUntil("the outbox row retired") { alice.db.outboxDao().observeCount().first() == 0 }
    }

    @Test
    fun `HUBFILE-U20 an upload that finishes before the message exists is queued when the message lands`(): Unit = runBlocking {
        val alice = device("alice")
        claimUpload(alice)
        finishUpload(alice)
        assertFalse(alice.repo.queueHubFile(outId), "no message yet")
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("the ticket on the relay") { tickets(alice).isNotEmpty() }
        assertEquals(sentMessage(alice)!!.localId, alice.db.hubFileDao().get(outId)!!.messageId)
        assertEquals(1, alice.db.outboxDao().observeCount().first())
    }

    @Test
    fun `HUBFILE-U21 a replayed ticket is stored once, notifies once, and is acknowledged every time`(): Unit = runBlocking {
        val alice = device("alice")
        val bob = device("bob")
        claimUpload(alice)
        finishUpload(alice)
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("the ticket on the relay") { tickets(alice).isNotEmpty() }
        val ticket = tickets(alice).first()
        repeat(3) { round ->
            // The hub redelivers under the same id only until acknowledged; a resend from the author has a fresh relay id.
            bob.relay.deliver(HubInbound(from = "alice", id = "${ticket.id}-$round", type = ticket.type, sentAt = ticket.sentAt, payload = ticket.payload))
        }
        awaitUntil("three receipts") { bob.relay.sent.count { it.to == "alice" } >= 3 }
        assertEquals(1, bob.db.messageDao().historyBefore("alice", Long.MAX_VALUE, "￿", 10).size)
        assertEquals(1, bob.notified.get())
        assertEquals(3, bob.relay.recvAcks.size)
    }

    @Test
    fun `HUBFILE-U22 a ticket that contradicts a stored one, or reuses a message id, is refused`(): Unit = runBlocking {
        val bob = device("bob")
        fun frame(sender: String, localId: String, k: HubFileKey = key) = HubFileFrame(
            localId = localId, senderId = sender, senderName = sender, sentAt = System.currentTimeMillis(),
            ticket = HubFileTicket(fileId, k, "ab".repeat(32), 3_000_000L, 1_048_576, 3, System.currentTimeMillis() + 86_400_000L),
            fileName = "report.pdf", mime = "application/pdf", meta = "",
        )
        fun deliver(sender: String, relayId: String, f: HubFileFrame) = bob.relay.deliver(
            HubInbound(from = sender, id = relayId, type = HubSealing.ENVELOPE_TYPE, sentAt = f.sentAt,
                payload = HubChatBridge.HUB_FILE + "\n" + HubFileFrameCodec.encode(f)),
        )

        deliver("alice", "r-1", frame("alice", "m-1"))
        awaitUntil("the first ticket") { bob.db.hubFileDao().get(inId) != null }
        // The same file id from someone else, and the same file id from the author with another key.
        deliver("mallory", "r-2", frame("mallory", "m-2"))
        deliver("alice", "r-3", frame("alice", "m-3", HubFileKey.generate()))
        // A new file id under a message id that already exists.
        val other = frame("mallory", "m-1").let { it.copy(ticket = it.ticket.copy(fileId = "f".repeat(32))) }
        deliver("mallory", "r-4", other)
        awaitUntil("every delivery acknowledged") { bob.relay.recvAcks.size >= 4 }

        val row = bob.db.hubFileDao().get(inId)!!
        assertEquals("alice", row.peerId)
        assertEquals(key.encode(), row.fileKey, "the stored key was not swapped")
        assertNull(bob.db.hubFileDao().byFileId(HubFileStates.IN, "f".repeat(32)))
        assertEquals("alice", bob.db.messageDao().getByLocalId("m-1")!!.senderId)
        assertEquals(1, bob.notified.get())
        assertTrue(bob.relay.sent.none { it.to == "mallory" }, "a refused ticket earns no receipt")
    }

    @Test
    fun `HUBFILE-U23 an upload cancelled after queuing gives the message up and the ticket never goes`(): Unit = runBlocking {
        val alice = device("alice")
        alice.relay.usable = false
        claimUpload(alice)
        finishUpload(alice)
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("queued") { alice.db.hubFileDao().get(outId)?.state == HubFileStates.QUEUED }
        assertEquals(1, alice.db.hubFileDao().moveState(outId, HubFileStates.QUEUED, HubFileStates.CANCELLED, null, 3L))
        alice.relay.usable = true
        awaitUntil("the message failed") { sentMessage(alice)?.status == "FAILED" }
        assertTrue(tickets(alice).isEmpty())
        assertEquals(0, alice.db.outboxDao().observeCount().first())
    }

    @Test
    fun `HUBFILE-U24 a ticket for a file that already left the hub is stored as failed with the sender's name`(): Unit = runBlocking {
        val bob = device("bob")
        val f = HubFileFrame(
            localId = "m-9", senderId = "alice", senderName = "Alice", sentAt = 1_000L,
            ticket = HubFileTicket(fileId, key, "ab".repeat(32), 3_000_000L, 1_048_576, 3, 5_000L),
            fileName = "old.pdf", mime = "application/pdf", meta = "",
        )
        bob.relay.deliver(HubInbound("alice", "r-9", HubSealing.ENVELOPE_TYPE, 1_000L, HubChatBridge.HUB_FILE + "\n" + HubFileFrameCodec.encode(f)))
        awaitUntil("the row") { bob.db.hubFileDao().get(inId) != null }
        val row = bob.db.hubFileDao().get(inId)!!
        assertEquals(HubFileStates.FAILED, row.state)
        assertEquals("This file is no longer on the hub. Ask Alice to send it again.", row.errorText)
    }

    @Test
    fun `HUBFILE-U25 without sealing, or on a device that takes no hub files, nothing is sent or stored`(): Unit = runBlocking {
        val alice = device("alice")
        alice.sealing.status = HubSealingStatus.NOT_PUBLISHED
        assertEquals(HubFileRoute.NEEDS_SEALING, alice.repo.hubFileRoute("bob"))
        alice.sealing.status = HubSealingStatus.AVAILABLE

        val bob = device("bob", takesHubFiles = false)
        assertEquals(HubFileRoute.UNAVAILABLE, bob.repo.hubFileRoute("alice"), "no hub file table, no hub route")
        claimUpload(alice)
        finishUpload(alice)
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("the ticket on the relay") { tickets(alice).isNotEmpty() }
        pump(alice, bob)
        awaitUntil("acknowledged") { bob.relay.recvAcks.isNotEmpty() }
        assertTrue(bob.db.messageDao().historyBefore("alice", Long.MAX_VALUE, "￿", 10).isEmpty())
        assertTrue(bob.relay.sent.isEmpty(), "no receipt for a file this device did not take")
    }

    @Test
    fun `HUBFILE-U41 an upload that ends for good fails its message and retires its ticket, but never a delivered one`(): Unit = runBlocking {
        val alice = device("alice")
        alice.relay.usable = false
        claimUpload(alice)
        finishUpload(alice)
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("queued") { alice.db.hubFileDao().get(outId)?.state == HubFileStates.QUEUED }
        assertEquals(1, alice.db.outboxDao().observeCount().first())
        alice.db.hubFileDao().moveState(outId, HubFileStates.QUEUED, HubFileStates.CANCELLED, null, 3L)
        alice.repo.hubFileEnded(outId)
        assertEquals("FAILED", sentMessage(alice)?.status)
        assertEquals(0, alice.db.outboxDao().observeCount().first())

        // A message the recipient already acknowledged stays delivered.
        alice.db.messageDao().updateStatus(sentMessage(alice)!!.localId, "DELIVERED")
        alice.repo.hubFileEnded(outId)
        assertEquals("DELIVERED", sentMessage(alice)?.status)
        // Unknown and incoming ids are ignored.
        alice.repo.hubFileEnded("hub-o-unknown")
        alice.repo.hubFileEnded(inId)
    }

    @Test
    fun `HUBFILE-U42 a send the hub refused at claim time starts as a failed message, never pending`(): Unit = runBlocking {
        val alice = device("alice")
        claimUpload(alice, state = HubFileStates.FAILED)
        alice.repo.sendAttachment("bob", outId, "report.pdf", "application/pdf", 3_000_000L, null)
        awaitUntil("the message row") { sentMessage(alice) != null }
        assertEquals("FAILED", sentMessage(alice)?.status)
        assertEquals(0, alice.db.outboxDao().observeCount().first(), "nothing to send for a refused file")
    }

    @Test
    fun `HUBFILE-U43 the ticket carries the type the chat message knows, not the neutral one the claim stored`(): Unit = runBlocking {
        val alice = device("alice")
        val bob = device("bob")
        claimUpload(alice, name = "photo.png", mime = "application/octet-stream")
        finishUpload(alice)
        alice.repo.sendAttachment("bob", outId, "photo.png", "image/png", 3_000_000L, null)
        awaitUntil("the ticket on the relay") { tickets(alice).isNotEmpty() }
        pump(alice, bob)
        awaitUntil("bob's row") { bob.db.hubFileDao().get(inId) != null }
        assertEquals("image/png", bob.db.hubFileDao().get(inId)?.mime)
        assertEquals("image/png", bob.db.messageDao().getByLocalId(sentMessage(alice)!!.localId)?.attachmentMime)
    }
}
