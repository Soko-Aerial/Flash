package com.transfer.flash.core.persistence.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.transfer.flash.core.persistence.db.entity.TrustedPeerEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [FlashDatabase] actually OPENS and WORKS on the desktop `jvm()` target (Phase 09B-1).
 *
 * This suite is the point of 09B-1. Relocating 24 entity/DAO files to `commonMain` only proves they
 * compile without `android.jar`; CONVENTIONS.md R3.1 is explicit that "an `actual` that is only
 * compiled is not verified". What is unverified without this file is the whole generated tier: that
 * Room's KSP processor ran for `kspJvm`, that it emitted `actual object FlashDatabaseConstructor`,
 * that the `FlashDatabase_Impl` it wrote targets the multiplatform `androidx.sqlite` driver
 * interfaces rather than the Android Support ones, and that its `InvalidationTracker` works off-Android.
 *
 * Two details are load-bearing and must not be "simplified":
 *
 * 1. **`factory = FlashDatabaseConstructor::initialize` is passed explicitly.** `Room`'s jvm
 *    `inMemoryDatabaseBuilder` declares `factory` with a DEFAULT of
 *    `{ findAndInstantiateDatabaseImpl(T::class.java) }` — i.e. reflection. Omitting the argument
 *    would make every test here pass even if `@ConstructedBy` did nothing and no `actual` object
 *    existed, because Room would find `FlashDatabase_Impl` by name instead. Passing the constructor
 *    reference is what routes the open through the generated-constructor seam under test.
 * 2. **`runBlocking`, not `runTest`.** These are real I/O against a real SQLite build; `runTest`'s
 *    virtual clock would make the real-time `withTimeout` waits in
 *    [flow re-emits after a write, proving InvalidationTracker runs on jvm] expire instantly.
 *
 * **The driver is [BundledSQLiteDriver], which is UNENCRYPTED.** PHASE-09B permits it here and
 * nowhere else, and only in memory: `inMemoryDatabaseBuilder` passes `name = null`, so no file path
 * — and no `":memory:"` string literal — is involved at all. Putting this driver in `jvmMain`, or
 * giving it a path, is "B without C" under D5's charter and is forbidden. The encrypted desktop
 * driver is 09B-2's problem and needs a human decision first.
 */
class FlashDatabaseJvmTest {

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

    @Test
    fun `trusted peer round-trips through the generated jvm _Impl`() = runBlocking {
        val dao = openDatabase().trustedPeerDao()

        assertFalse(dao.isPinned(DEVICE_ID, FINGERPRINT), "empty db should not report a pin")

        val rowId = dao.insert(peer(DEVICE_ID))
        assertTrue(rowId > 0L, "insert should return a real rowid, got $rowId")
        assertTrue(dao.isPinned(DEVICE_ID, FINGERPRINT), "pin should be visible after insert")

        val stored = dao.observeAll().first().single()
        assertEquals(DEVICE_ID, stored.deviceId)
        assertEquals("peer-$DEVICE_ID", stored.name)
        assertEquals(FINGERPRINT, stored.fingerprintHex)
        assertEquals(TRUSTED_AT, stored.trustedAt)

        dao.revoke(DEVICE_ID)
        assertFalse(dao.isPinned(DEVICE_ID, FINGERPRINT), "pin should be gone after revoke")
        assertTrue(dao.observeAll().first().isEmpty(), "table should be empty after revoke")
    }

    @Test
    fun `IGNORE conflict strategy returns minus one on a duplicate primary key`() = runBlocking {
        // OnConflictStrategy.IGNORE is compiled into the generated _Impl as `INSERT OR IGNORE`, so
        // this checks the jvm code generator honours the annotation, not just that SQL runs.
        val dao = openDatabase().trustedPeerDao()
        assertTrue(dao.insert(peer(DEVICE_ID)) > 0L)
        assertEquals(-1L, dao.insert(peer(DEVICE_ID).copy(name = "should-not-win")))
        assertEquals("peer-$DEVICE_ID", dao.observeAll().first().single().name)
    }

    @Test
    fun `all eleven tables exist on the jvm target`() = runBlocking {
        // One read per @Dao. A missing table makes SQLite raise, so these calls are existence
        // probes; the returned emptiness is secondary. Five DAOs have no argument-free read, hence
        // the "absent" keys. Reads were chosen over writes so nothing here depends on entity shape.
        val db = openDatabase()
        assertTrue(db.conversationDao().observeAll().first().isEmpty(), "conversations")
        assertNull(db.messageDao().getByLocalId("absent"), "messages")
        assertEquals(0, db.receiptDao().countForMessage("absent"), "receipts")
        assertTrue(db.outboxDao().dueForDelivery(now = 0L, limit = 1).isEmpty(), "outbox")
        assertNull(db.transferDao().observe("absent").first(), "transfers")
        assertTrue(db.transferChunkDao().allDoneChunks().isEmpty(), "transfer chunks")
        assertTrue(db.recentSearchDao().observeRecent(limit = 1).first().isEmpty(), "recent searches")
        assertTrue(db.trustedPeerDao().observeAll().first().isEmpty(), "trusted peers")
        assertNull(db.reactionDao().get("absent", "+1"), "reactions")
        assertNull(db.draftDao().observeDraft("absent").first(), "drafts")
        assertNull(db.readCursorDao().get("absent", "absent"), "read cursors")
    }

    @Test
    fun `flow re-emits after a write, proving InvalidationTracker runs on jvm`() = runBlocking {
        // Room's Flow queries are driven by InvalidationTracker, which on Android leans on the
        // Support stack. If it silently no-ops on jvm, every observe* DAO function returns a Flow
        // that emits once and then goes dead — the kind of failure that compiles, passes a
        // round-trip test, and breaks the desktop UI. So: subscribe FIRST, assert the initial
        // emission is empty (that is what proves the subscription predates the write), then write
        // and require a SECOND emission. Awaiting `isNotEmpty()` without the empty assertion first
        // would pass on the initial emission alone and prove nothing.
        val dao = openDatabase().trustedPeerDao()
        val emissions = Channel<List<TrustedPeerEntity>>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.IO) {
            dao.observeAll().collect { emissions.send(it) }
        }
        try {
            assertTrue(
                withTimeout(AWAIT_MS) { emissions.receive() }.isEmpty(),
                "first emission should be the empty table, i.e. we subscribed before the insert",
            )
            dao.insert(peer(DEVICE_ID))
            val afterWrite = withTimeout(AWAIT_MS) { emissions.receive() }
            assertEquals(listOf(DEVICE_ID), afterWrite.map { it.deviceId })
        } finally {
            collector.cancel()
        }
    }

    private fun peer(deviceId: String) = TrustedPeerEntity(
        deviceId = deviceId,
        name = "peer-$deviceId",
        fingerprintHex = FINGERPRINT,
        trustedAt = TRUSTED_AT,
    )

    private companion object {
        const val DEVICE_ID = "jvm-peer-1"
        const val FINGERPRINT = "0A1B2C3D4E5F"
        const val TRUSTED_AT = 1_700_000_000_000L

        /** Real milliseconds — see the class KDoc on why this suite uses `runBlocking`. */
        const val AWAIT_MS = 15_000L
    }
}
