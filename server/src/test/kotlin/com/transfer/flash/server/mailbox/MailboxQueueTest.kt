package com.transfer.flash.server.mailbox

import com.transfer.flash.server.storage.ServerDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MailboxQueueTest {

    private lateinit var database: ServerDatabase
    private lateinit var queue: MailboxQueue

    @Before
    fun setUp() {
        database = ServerDatabase.inMemory(poolSize = 1)
        queue = MailboxQueue(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testSealedEnvelopeBinarySerializationRoundtrip() {
        val original = SealedEnvelope(
            version = 1,
            originDeviceId = "dev-alice-123",
            msgId = "msg-uuid-9999",
            hops = 2,
            counter = 42L,
            kind = EnvelopeKind.CHAT,
            recipientDeviceIds = listOf("dev-bob-456", "dev-charlie-789"),
            payload = "Hello secure mesh relay world!".toByteArray(Charsets.UTF_8),
            authTagOrSignature = "MEQCIAj3oUe89B/base64sig",
            timestamp = 1728670000000L,
        )

        val bytes = original.toBinary()
        val restored = SealedEnvelope.fromBinary(bytes)

        assertEquals(original, restored)
        assertEquals(original.msgId, restored.msgId)
        assertArrayEquals(original.payload, restored.payload)
        assertEquals(original.recipientDeviceIds, restored.recipientDeviceIds)
    }

    @Test
    fun testEnqueueSuccessAndFetchPending() {
        val now = System.currentTimeMillis()
        val envelope = SealedEnvelope(
            originDeviceId = "dev-alice",
            msgId = "m-001",
            payload = "Test payload 1".toByteArray(Charsets.UTF_8),
            authTagOrSignature = "tag1",
            timestamp = now,
        )

        val result = queue.enqueue(envelope, "dev-bob")
        assertTrue("Enqueue should succeed", result is EnqueueResult.Enqueued)
        val envelopeId = (result as EnqueueResult.Enqueued).envelopeId
        assertTrue(envelopeId.isNotEmpty())

        val pending = queue.fetchPending("dev-bob")
        assertEquals(1, pending.size)
        assertEquals("m-001", pending[0].msgId)
        assertEquals("dev-alice", pending[0].originDeviceId)
        assertEquals(listOf("dev-bob"), pending[0].recipientDeviceIds)
        assertArrayEquals("Test payload 1".toByteArray(Charsets.UTF_8), pending[0].payload)
    }

    @Test
    fun testDeduplicationOfIdenticalOriginAndMsgId() {
        val now = System.currentTimeMillis()
        val envelope1 = SealedEnvelope(
            originDeviceId = "dev-sender",
            msgId = "duplicate-msg-id-1",
            payload = "Original content".toByteArray(Charsets.UTF_8),
            timestamp = now,
        )

        val result1 = queue.enqueue(envelope1, "dev-receiver")
        assertTrue(result1 is EnqueueResult.Enqueued)

        // Attempting to enqueue identical (originDeviceId, msgId) to the same receiver
        val envelope2 = SealedEnvelope(
            originDeviceId = "dev-sender",
            msgId = "duplicate-msg-id-1",
            payload = "Modified content which should be dropped".toByteArray(Charsets.UTF_8),
            timestamp = now + 5L,
        )

        val result2 = queue.enqueue(envelope2, "dev-receiver")
        assertTrue("Duplicate enqueue should return Duplicate status", result2 is EnqueueResult.Duplicate)
        assertEquals((result1 as EnqueueResult.Enqueued).envelopeId, (result2 as EnqueueResult.Duplicate).existingEnvelopeId)

        // Ensure only the original payload is retained
        val pending = queue.fetchPending("dev-receiver")
        assertEquals(1, pending.size)
        assertArrayEquals("Original content".toByteArray(Charsets.UTF_8), pending[0].payload)
    }

    @Test
    fun testQuotaEnforcementPayloadSizeFilter() {
        val customQuota = QuotaManager(maxPayloadSizeBytes = 1024L) // 1 KB max for test
        val customQueue = MailboxQueue(database, quotaManager = customQuota)

        val oversizedEnvelope = SealedEnvelope(
            originDeviceId = "dev-sender",
            msgId = "m-oversized",
            payload = ByteArray(2048), // 2 KB exceeds 1 KB limit
        )

        val result = customQueue.enqueue(oversizedEnvelope, "dev-receiver")
        assertTrue("Oversized payload should be rejected", result is EnqueueResult.QuotaExceeded)
        assertEquals(
            QuotaManager.ERROR_PAYLOAD_EXCEEDS_LIMIT,
            (result as EnqueueResult.QuotaExceeded).reason,
        )
    }

    @Test
    fun testQuotaEnforcementMaxPendingCount() {
        val customQuota = QuotaManager(maxPendingEnvelopesPerDevice = 2)
        val customQueue = MailboxQueue(database, quotaManager = customQuota)

        val env1 = SealedEnvelope(originDeviceId = "dev-sender", msgId = "m-p1", payload = ByteArray(10))
        val env2 = SealedEnvelope(originDeviceId = "dev-sender", msgId = "m-p2", payload = ByteArray(10))
        val env3 = SealedEnvelope(originDeviceId = "dev-sender", msgId = "m-p3", payload = ByteArray(10))

        assertTrue(customQueue.enqueue(env1, "dev-receiver") is EnqueueResult.Enqueued)
        assertTrue(customQueue.enqueue(env2, "dev-receiver") is EnqueueResult.Enqueued)

        val res3 = customQueue.enqueue(env3, "dev-receiver")
        assertTrue(res3 is EnqueueResult.QuotaExceeded)
        assertTrue((res3 as EnqueueResult.QuotaExceeded).reason.contains("maximum pending envelope limit"))
    }

    @Test
    fun testQuotaEnforcementMaxDeviceStorageBytes() {
        val customQuota = QuotaManager(maxDeviceMailboxBytes = 500L)
        val customQueue = MailboxQueue(database, quotaManager = customQuota)

        val env1 = SealedEnvelope(originDeviceId = "dev-sender", msgId = "m-b1", payload = ByteArray(300))
        val env2 = SealedEnvelope(originDeviceId = "dev-sender", msgId = "m-b2", payload = ByteArray(300))

        assertTrue(customQueue.enqueue(env1, "dev-receiver") is EnqueueResult.Enqueued)

        val res2 = customQueue.enqueue(env2, "dev-receiver")
        assertTrue(res2 is EnqueueResult.QuotaExceeded)
        assertTrue((res2 as EnqueueResult.QuotaExceeded).reason.contains("mailbox quota exceeded"))
    }

    @Test
    fun testFetchPendingDeltaCatchUpAndOrder() {
        val baseTime = System.currentTimeMillis()
        val env1 = SealedEnvelope(originDeviceId = "dev-A", msgId = "m-1", payload = ByteArray(10), timestamp = baseTime + 1000L)
        val env2 = SealedEnvelope(originDeviceId = "dev-A", msgId = "m-2", payload = ByteArray(10), timestamp = baseTime + 2000L)
        val env3 = SealedEnvelope(originDeviceId = "dev-A", msgId = "m-3", payload = ByteArray(10), timestamp = baseTime + 3000L)

        queue.enqueue(env1, "dev-B")
        queue.enqueue(env2, "dev-B")
        queue.enqueue(env3, "dev-B")

        // Fetch first batch of 2
        val batch1 = queue.fetchPendingWithMetadata("dev-B", limit = 2)
        assertEquals(2, batch1.size)
        assertEquals("m-1", batch1[0].envelope.msgId)
        assertEquals("m-2", batch1[1].envelope.msgId)

        // Acknowledge delivery of first batch
        val ackCount = queue.markDelivered(batch1.map { it.envelopeId })
        assertEquals(2, ackCount)

        // Fetch remaining delta
        val batch2 = queue.fetchPendingWithMetadata("dev-B", limit = 2)
        assertEquals(1, batch2.size)
        assertEquals("m-3", batch2[0].envelope.msgId)
    }

    @Test
    fun testDeliveryAcknowledgementByTargetAndMsgId() {
        val env = SealedEnvelope(originDeviceId = "dev-A", msgId = "m-target-ack", payload = ByteArray(10))
        queue.enqueue(env, "dev-B")

        assertEquals(1, queue.fetchPending("dev-B").size)

        val acked = queue.markDelivered(targetDeviceId = "dev-B", msgId = "m-target-ack")
        assertTrue(acked)

        assertEquals(0, queue.fetchPending("dev-B").size)
    }

    @Test
    fun testTtlExpirationAndSweeper() = runTest {
        val now = System.currentTimeMillis()
        // Envelope created in the past with short TTL so it is already expired
        val expiredEnv = SealedEnvelope(
            originDeviceId = "dev-A",
            msgId = "m-expired",
            payload = ByteArray(10),
            timestamp = now - 100_000L,
        )
        // Set TTL to 1000ms, making expires_at = now - 99_000L
        queue.enqueue(expiredEnv, "dev-B", ttlMs = 1000L)

        // Envelope that is still valid
        val validEnv = SealedEnvelope(
            originDeviceId = "dev-A",
            msgId = "m-valid",
            payload = ByteArray(10),
            timestamp = now,
        )
        queue.enqueue(validEnv, "dev-B", ttlMs = 60_000L)

        // fetchPending should ignore the expired envelope
        val pending = queue.fetchPending("dev-B")
        assertEquals(1, pending.size)
        assertEquals("m-valid", pending[0].msgId)

        // TtlSweeper sweeps expired records from the database
        val sweeper = TtlSweeper(queue, sweepIntervalMs = 500L, backgroundScope)
        val deletedCount = sweeper.sweepOnce()
        assertEquals(1, deletedCount)

        // Valid envelope remains intact
        assertEquals(1, queue.fetchPending("dev-B").size)

        sweeper.start()
        sweeper.stop()
        sweeper.close()
    }
}
