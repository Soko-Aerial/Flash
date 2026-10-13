package com.transfer.flash.server.mailbox

import com.transfer.flash.server.storage.ServerDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mailbox store (REL-03, REL-08, REL-09, REL-11, REL-12, REL-15, ADR-112). Everything that depends on time uses an
 * injected clock; everything that depends on concurrency uses a real file database with a pool, because an in-memory
 * single-connection pool serialises by construction and would prove nothing.
 */
class MailboxQueueTest {

    private val now = AtomicLong(1_800_000_000_000L)
    private val clock: () -> Long = { now.get() }

    private val databases = mutableListOf<ServerDatabase>()
    private val dirs = mutableListOf<Path>()

    @Before
    fun setUp() {
        now.set(1_800_000_000_000L)
    }

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun memoryDb(): ServerDatabase = ServerDatabase.inMemory(poolSize = 1).also { databases += it }

    private fun tempDir(): Path = Files.createTempDirectory("flash-mailbox-test").also { dirs.add(it) }

    private fun fileDb(dir: Path = tempDir(), pool: Int = 6): ServerDatabase =
        ServerDatabase("jdbc:sqlite:${dir.resolve("hub.db").toString().replace('\\', '/')}", pool).also { databases += it }

    private fun queue(
        db: ServerDatabase = memoryDb(),
        limits: MailboxLimits = MailboxLimits(),
        registry: DeviceRegistry = DeviceRegistry.AcceptAll,
    ) = MailboxQueue(db, limits, clock, registry)

    private fun msg(
        id: String,
        origin: String = "alice",
        target: String = "bob",
        payload: String = "{\"text\":\"hi\"}",
        sentAt: Long = now.get(),
        group: String? = null,
    ) = MailboxMessage(origin, target, id, "MSG_TEXT", payload, sentAt, group)

    // ---- SealedEnvelope (REL-15) ---------------------------------------------------------------------------

    @Test
    fun sealedEnvelopeBinarySerializationRoundtrip() {
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
        val restored = SealedEnvelope.fromBinary(original.toBinary())
        assertEquals(original, restored)
        assertArrayEquals(original.payload, restored.payload)
    }

    @Test
    fun sealedEnvelopeParserRefusesAnImplausibleHeaderInsteadOfAllocatingIt() {
        fun header(recipients: Int, payload: Int): ByteArray {
            val bos = ByteArrayOutputStream()
            DataOutputStream(bos).use { out ->
                out.writeInt(SealedEnvelope(originDeviceId = "a", msgId = "m", payload = ByteArray(0)).toBinary().let {
                    java.nio.ByteBuffer.wrap(it).int
                })
                out.writeInt(1)
                out.writeUTF("a")
                out.writeUTF("m")
                out.writeInt(0)
                out.writeLong(0)
                out.writeUTF("chat")
                out.writeInt(recipients)
                if (recipients == 0) out.writeInt(payload)
            }
            return bos.toByteArray()
        }
        assertFailsWith<IllegalArgumentException> { SealedEnvelope.fromBinary(header(Int.MAX_VALUE, 0)) }
        assertFailsWith<IllegalArgumentException> { SealedEnvelope.fromBinary(header(0, Int.MAX_VALUE)) }
        assertFailsWith<IllegalArgumentException> { SealedEnvelope.fromBinary(header(0, 1_000_000)) } // larger than the buffer
    }

    // ---- enqueue / fetch / ack ----------------------------------------------------------------------------

    @Test
    fun rowsComeBackInArrivalOrderNotClientClockOrder() = runTest {
        val q = queue()
        q.enqueue(msg("late-clock", sentAt = now.get() + 1_000))
        q.enqueue(msg("early-clock", sentAt = now.get() - 100_000))
        q.enqueue(msg("middle", sentAt = now.get()))

        val ids = q.fetchPage("bob", 0, 100).map { it.msgId }
        assertEquals(listOf("late-clock", "early-clock", "middle"), ids)
    }

    @Test
    fun fetchPageIsCursorBasedAndNeverRemovesRows() = runTest {
        val q = queue()
        repeat(7) { q.enqueue(msg("m$it")) }
        val first = q.fetchPage("bob", 0, 3)
        val second = q.fetchPage("bob", first.last().seq, 3)
        val third = q.fetchPage("bob", second.last().seq, 3)
        assertEquals(listOf("m0", "m1", "m2"), first.map { it.msgId })
        assertEquals(listOf("m3", "m4", "m5"), second.map { it.msgId })
        assertEquals(listOf("m6"), third.map { it.msgId })
        assertEquals(7, q.pendingCount("bob"), "reading a page must not delete anything (REL-02)")
    }

    @Test
    fun aDuplicateSendIsReportedAndStoredOnce() = runTest {
        val q = queue()
        val first = q.enqueue(msg("dup-1"))
        val second = q.enqueue(msg("dup-1"))
        assertIs<EnqueueResult.Queued>(first)
        assertIs<EnqueueResult.DuplicateQueued>(second)
        assertEquals(first.seq, second.seq)
        assertEquals(1, q.pendingCount("bob"))
        assertTrue(second.isStored)
    }

    @Test
    fun theSameIdFromAnotherSenderIsADifferentMessage() = runTest {
        val q = queue()
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("same", origin = "alice")))
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("same", origin = "carol")))
        assertEquals(2, q.pendingCount("bob"))
    }

    @Test
    fun concurrentDuplicateEnqueueStoresExactlyOneRow() = runBlocking<Unit> {
        val q = queue(fileDb())
        val results = (1..48).map { async { q.enqueue(msg("race-1")) } }.awaitAll()
        assertEquals(1, results.count { it is EnqueueResult.Queued }, "exactly one winner: $results")
        assertEquals(47, results.count { it is EnqueueResult.DuplicateQueued })
        assertEquals(1, q.pendingCount("bob"))
        assertEquals(1L, q.queuedCount())
    }

    @Test
    fun concurrentSendersCanNotBothSqueezeIntoTheLastQuotaSlot() = runBlocking<Unit> {
        val q = queue(fileDb(), MailboxLimits(perRecipientRows = 5))
        val results = (1..40).map { i -> async { q.enqueue(msg("q$i", origin = "s$i")) } }.awaitAll()
        val stored = results.count { it is EnqueueResult.Queued }
        assertEquals(5, stored, "check-then-insert must be atomic: $stored stored")
        assertEquals(35, results.count { it is EnqueueResult.QuotaExceeded })
        assertEquals(5, q.pendingCount("bob"))
    }

    @Test
    fun payloadLargerThanTheCapIsRefusedWithoutStoring() = runTest {
        val q = queue(limits = MailboxLimits(maxPayloadBytes = 64))
        val result = q.enqueue(msg("big", payload = "x".repeat(65)))
        val refused = assertIs<EnqueueResult.QuotaExceeded>(result)
        assertEquals(QuotaScope.PAYLOAD, refused.scope)
        assertEquals(0, q.pendingCount("bob"))
    }

    @Test
    fun theRecipientRowQuotaIsEnforcedAndNamesItsScope() = runTest {
        val q = queue(limits = MailboxLimits(perRecipientRows = 3))
        repeat(3) { assertIs<EnqueueResult.Queued>(q.enqueue(msg("m$it", origin = "s$it"))) }
        val refused = assertIs<EnqueueResult.QuotaExceeded>(q.enqueue(msg("m4", origin = "s9")))
        assertEquals(QuotaScope.RECIPIENT, refused.scope)
        // Another recipient is unaffected.
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("m5", target = "carol")))
    }

    @Test
    fun theRecipientByteQuotaIsEnforced() = runTest {
        val q = queue(limits = MailboxLimits(perRecipientBytes = 100))
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("a", payload = "x".repeat(60), origin = "s1")))
        val refused = assertIs<EnqueueResult.QuotaExceeded>(q.enqueue(msg("b", payload = "x".repeat(60), origin = "s2")))
        assertEquals(QuotaScope.RECIPIENT, refused.scope)
    }

    @Test
    fun onePushySenderCanNotFillTheHubForEveryone() = runTest {
        val q = queue(limits = MailboxLimits(perSenderRows = 4))
        repeat(4) { assertIs<EnqueueResult.Queued>(q.enqueue(msg("m$it", target = "t$it"))) }
        val refused = assertIs<EnqueueResult.QuotaExceeded>(q.enqueue(msg("m-x", target = "t-new")))
        assertEquals(QuotaScope.SENDER, refused.scope)
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("other", origin = "carol", target = "t-new")))
    }

    @Test
    fun theGlobalCapStopsTheWholeMailbox() = runTest {
        val q = queue(limits = MailboxLimits(globalRows = 3))
        repeat(3) { assertIs<EnqueueResult.Queued>(q.enqueue(msg("m$it", origin = "s$it", target = "t$it"))) }
        val refused = assertIs<EnqueueResult.QuotaExceeded>(q.enqueue(msg("m9", origin = "s9", target = "t9")))
        assertEquals(QuotaScope.GLOBAL, refused.scope)
    }

    @Test
    fun aRecipientThatWasNeverEnrolledIsRefusedAndNothingIsStored() = runTest {
        val q = queue(registry = DeviceRegistry { it == "bob" })
        assertIs<EnqueueResult.Queued>(q.enqueue(msg("ok", target = "bob")))
        assertEquals(EnqueueResult.UnknownRecipient, q.enqueue(msg("typo", target = "bobb")))
        assertEquals(1L, q.queuedCount())
        assertEquals(1L, q.droppedTotal())
    }

    @Test
    fun aStorageFailureIsAnAnswerNotAnException() = runTest {
        val db = memoryDb()
        val q = queue(db)
        db.close()
        val result = q.enqueue(msg("lost"))
        assertIs<EnqueueResult.Failed>(result)
        assertEquals(1L, q.droppedTotal())
    }

    @Test
    fun acknowledgeDeletesOnlyTheRecipientsOwnCopyAndNamesTheSender() = runTest {
        val q = queue()
        q.enqueue(msg("m1", origin = "alice", target = "bob"))
        q.enqueue(msg("m1", origin = "alice", target = "carol"))

        assertEquals(emptyList(), q.acknowledge("mallory", "m1"), "a third party can not acknowledge")
        val acked = q.acknowledge("bob", "m1")
        assertEquals(listOf("alice"), acked.map { it.origin })
        assertEquals(0, q.pendingCount("bob"))
        assertEquals(1, q.pendingCount("carol"), "carol's copy is untouched")
        assertEquals(emptyList(), q.acknowledge("bob", "m1"), "idempotent")
        assertEquals(1L, q.deliveredTotal())
    }

    @Test
    fun twoSendersWithTheSameMessageIdAreAcknowledgedOneAtATime() = runTest {
        val q = queue()
        q.enqueue(msg("same", origin = "alice", target = "bob"))
        q.enqueue(msg("same", origin = "mallory", target = "bob"))

        // naming the sender removes exactly that copy
        assertEquals(listOf("mallory"), q.acknowledge("bob", "same", origin = "mallory").map { it.origin })
        assertEquals(1, q.pendingCount("bob"), "alice's copy survives mallory's acknowledgement")
        // without a sender only the oldest copy goes; a second ack takes the next
        q.enqueue(msg("same", origin = "mallory", target = "bob"))
        assertEquals(listOf("alice"), q.acknowledge("bob", "same").map { it.origin })
        assertEquals(1, q.pendingCount("bob"))
        assertEquals(listOf("mallory"), q.acknowledge("bob", "same").map { it.origin })
        assertEquals(0, q.pendingCount("bob"))
    }

    // ---- time (REL-08, REL-11) ------------------------------------------------------------------------------

    @Test
    fun aRowExpiresExactlyWhenItsTtlRunsOutAndIsReportedToTheSweeper() = runTest {
        val q = queue(limits = MailboxLimits(ttlMs = 10_000))
        q.enqueue(msg("short"))
        now.addAndGet(9_999)
        assertEquals(1, q.fetchPage("bob", 0, 10).size)
        assertEquals(emptyList(), q.deleteExpired(100))

        now.addAndGet(1) // exactly at expires_at
        assertEquals(0, q.fetchPage("bob", 0, 10).size, "an expired row is not delivered")
        val expired = q.deleteExpired(100)
        assertEquals(listOf(ExpiredRow("alice", "bob", "short")), expired)
        assertEquals(0L, q.queuedCount())
        assertEquals(1L, q.expiredTotal())
    }

    @Test
    fun aClientCanNeverExtendTheLifetimeAndNonsenseTtlOverridesAreIgnored() = runTest {
        val q = queue(limits = MailboxLimits(ttlMs = 60_000))
        q.enqueue(msg("max"), ttlOverrideMs = Long.MAX_VALUE) // would overflow now + ttl in the old code
        q.enqueue(msg("neg"), ttlOverrideMs = -5)
        q.enqueue(msg("zero"), ttlOverrideMs = 0)
        q.enqueue(msg("short"), ttlOverrideMs = 1_000)

        val rows = q.fetchPage("bob", 0, 10).associateBy { it.msgId }
        assertEquals(now.get() + 60_000, rows.getValue("max").expiresAt)
        assertEquals(now.get() + 60_000, rows.getValue("neg").expiresAt)
        assertEquals(now.get() + 60_000, rows.getValue("zero").expiresAt)
        assertEquals(now.get() + 1_000, rows.getValue("short").expiresAt)
    }

    @Test
    fun theHubStampsItsOwnTimesAndClampsAbsurdClientTimestamps() = runTest {
        val q = queue()
        q.enqueue(msg("future", sentAt = Long.MAX_VALUE))
        q.enqueue(msg("negative", sentAt = -1))
        q.enqueue(msg("zero", sentAt = 0))
        q.enqueue(msg("honest", sentAt = now.get() - 1_000))

        val rows = q.fetchPage("bob", 0, 10).associateBy { it.msgId }
        assertEquals(now.get(), rows.getValue("future").sentAt)
        assertEquals(now.get(), rows.getValue("negative").sentAt)
        assertEquals(now.get(), rows.getValue("zero").sentAt)
        assertEquals(now.get() - 1_000, rows.getValue("honest").sentAt)
        rows.values.forEach { assertEquals(now.get(), it.createdAt, "created_at is the SERVER clock") }
    }

    @Test
    fun theSweeperRemovesABacklogInBatchesAndTellsTheOnlineSenders() = runTest {
        val q = queue(limits = MailboxLimits(ttlMs = 1_000, perRecipientRows = 100))
        repeat(25) { q.enqueue(msg("e$it", origin = "s$it")) }
        now.addAndGet(2_000)
        val told = mutableListOf<ExpiredRow>()
        val sweeper = TtlSweeper(q, coroutineScope = this, batchSize = 10, onExpired = { told += it })

        assertEquals(25, sweeper.sweepOnce(), "one pass drains the backlog, not one batch per interval")
        assertEquals(25, told.size)
        assertEquals(25, sweeper.expiredCount)
        assertEquals(0L, q.queuedCount())
    }

    @Test
    fun aFailingSweepIsCountedAndTheLoopKeepsRunning() = runBlocking<Unit> {
        val broken = object : MailboxStore by MailboxStore.None {
            override suspend fun deleteExpired(limit: Int): List<ExpiredRow> = error("disk on fire")
        }
        val sweeper = TtlSweeper(broken, sweepIntervalMs = 20, coroutineScope = this)
        sweeper.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (sweeper.failureCount < 3 && System.nanoTime() < deadline) kotlinx.coroutines.delay(10)
        sweeper.stop()
        assertTrue(sweeper.failureCount >= 3, "failures must be counted and retried, got ${sweeper.failureCount}")
    }

    @Test
    fun sweepingWhileSendersKeepEnqueuingLosesNothingAndThrowsNothing() = runBlocking<Unit> {
        val q = queue(fileDb(), MailboxLimits(ttlMs = 50, perRecipientRows = 10_000, perSenderRows = 10_000))
        val sweeper = TtlSweeper(q, coroutineScope = this, batchSize = 20)
        val writers = (1..8).map { w ->
            async {
                repeat(60) { i ->
                    q.enqueue(msg("w$w-$i", origin = "s$w"))
                    if (i % 10 == 0) now.addAndGet(60)
                }
            }
        }
        val sweeps = async { repeat(15) { sweeper.sweepOnce(); kotlinx.coroutines.delay(2) } }
        writers.awaitAll()
        sweeps.await()
        now.addAndGet(1_000)
        sweeper.sweepOnce()
        assertEquals(0L, q.queuedCount(), "everything expired and was swept")
        assertEquals(8L * 60L, sweeper.expiredCount + q.deliveredTotal() + q.droppedTotal())
    }

    // ---- storage (REL-12, REL-15) --------------------------------------------------------------------------

    @Test
    fun theStoredPayloadIsOpaqueAndComesBackByteForByte() = runTest {
        val q = queue(limits = MailboxLimits(maxPayloadBytes = 2 * 1024 * 1024))
        val nasty = listOf(
            "{\"text\":\"she said \\\"hi\\\" and left\"}",
            "{\"a\":{\"b\":[1,2,{\"c\":null}]},\"s\":\"line1\\nline2\"}",
            "raw\nnewlines\r\nand\ttabs",
            "unicode: äöü 中文 😀 \u0000 nul",
            "'; DROP TABLE mailbox_envelopes; --",
            "",
            "x".repeat(1024 * 1024),
        )
        nasty.forEachIndexed { i, p -> assertIs<EnqueueResult.Queued>(q.enqueue(msg("p$i", payload = p))) }
        val back = q.fetchPage("bob", 0, 100).map { it.payload }
        assertEquals(nasty, back)
    }

    @Test
    fun rowsAndTheirOrderSurviveARestartOfTheDatabase() = runBlocking<Unit> {
        val dir = tempDir()
        val first = fileDb(dir)
        val q1 = MailboxQueue(first, MailboxLimits(), clock)
        q1.enqueue(msg("r1")); q1.enqueue(msg("r2")); q1.enqueue(msg("r3"))
        q1.acknowledge("bob", "r2")
        first.close()

        val second = fileDb(dir)
        val q2 = MailboxQueue(second, MailboxLimits(), clock)
        assertEquals(listOf("r1", "r3"), q2.fetchPage("bob", 0, 10).map { it.msgId })
        val next = assertIs<EnqueueResult.Queued>(q2.enqueue(msg("r4")))
        assertTrue(next.seq > q2.fetchPage("bob", 0, 10).first { it.msgId == "r3" }.seq, "seq never goes backwards after a restart")
        assertEquals(3L, q2.queuedCount())
    }

    @Test
    fun theTotalsTrackEveryInsertAndDeleteExactly() = runTest {
        val q = queue()
        q.enqueue(msg("a", payload = "12345"))
        q.enqueue(msg("b", payload = "123"))
        assertEquals(2L, q.queuedCount())
        assertEquals(8L, q.queuedBytes())
        q.acknowledge("bob", "a")
        assertEquals(1L, q.queuedCount())
        assertEquals(3L, q.queuedBytes())
        assertTrue(q.oldestAgeMs("bob")!! >= 0)
        assertNull(q.oldestAgeMs("nobody"))
    }

    // ---- migration from the first hub build (REL-03, REL-15) ----------------------------------------------

    @Test
    fun aDatabaseWrittenByTheFirstHubBuildIsMigratedWithItsUndeliveredRows() = runBlocking<Unit> {
        val dir = tempDir()
        val file = dir.resolve("hub.db").toString().replace('\\', '/')
        DriverManager.getConnection("jdbc:sqlite:$file").use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE mailbox_envelopes (id TEXT PRIMARY KEY, origin_device_id TEXT NOT NULL, target_device_id TEXT NOT NULL, " +
                        "msg_id TEXT NOT NULL, kind TEXT NOT NULL, hops INTEGER NOT NULL DEFAULT 0, counter INTEGER NOT NULL DEFAULT 0, " +
                        "payload BLOB NOT NULL, auth_tag TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, " +
                        "delivered INTEGER NOT NULL DEFAULT 0)",
                )
            }
            fun row(id: String, msg: String, createdAt: Long, expiresAt: Long, delivered: Int) =
                conn.prepareStatement(
                    "INSERT INTO mailbox_envelopes(id, origin_device_id, target_device_id, msg_id, kind, payload, auth_tag, created_at, expires_at, delivered) " +
                        "VALUES (?, 'alice', 'bob', ?, 'MSG_TEXT', ?, '', ?, ?, ?)",
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, msg); ps.setBytes(3, "{\"k\":\"$msg\"}".toByteArray())
                    ps.setLong(4, createdAt); ps.setLong(5, expiresAt); ps.setInt(6, delivered)
                    ps.executeUpdate()
                }
            row("1", "old-2", now.get() - 2_000, 0L, 0) // expires_at = 0: "never expires" in the old build
            row("2", "old-1", now.get() - 3_000, now.get() + 99_000, 0)
            row("3", "old-delivered", now.get() - 4_000, now.get() + 99_000, 1)
        }

        val db = ServerDatabase("jdbc:sqlite:$file", 4).also { databases += it }
        val q = MailboxQueue(db, MailboxLimits(), clock)
        val rows = q.fetchPage("bob", 0, 10)
        assertEquals(listOf("old-1", "old-2"), rows.map { it.msgId }, "undelivered rows kept, oldest first; delivered ones are gone")
        assertEquals("{\"k\":\"old-1\"}", rows.first().payload)
        assertTrue(rows.all { it.expiresAt > now.get() }, "a row with expires_at=0 now expires like any other")
        assertEquals(2L, q.queuedCount(), "the totals row was seeded from the migrated rows")
        // And the new constraints are live on the migrated table.
        assertIs<EnqueueResult.DuplicateQueued>(q.enqueue(MailboxMessage("alice", "bob", "old-1", "MSG_TEXT", "{}", now.get())))
        // Re-opening must not migrate (or duplicate) again.
        db.close()
        val again = MailboxQueue(fileDbAt(file), MailboxLimits(), clock)
        assertEquals(2, again.fetchPage("bob", 0, 10).size)
    }

    private fun fileDbAt(file: String): ServerDatabase = ServerDatabase("jdbc:sqlite:$file", 4).also { databases += it }

    @Test
    fun forTtlDaysConvertsAndRejectsNonsense() {
        assertEquals(3L * 24 * 3600 * 1000, MailboxLimits.forTtlDays(3).ttlMs)
        assertFailsWith<IllegalArgumentException> { MailboxLimits.forTtlDays(0) }
        assertFailsWith<IllegalArgumentException> { MailboxLimits.forTtlDays(-7) }
    }
}
