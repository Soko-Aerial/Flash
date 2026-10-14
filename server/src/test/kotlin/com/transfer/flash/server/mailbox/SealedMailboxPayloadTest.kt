package com.transfer.flash.server.mailbox

import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.storage.ServerDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ADR-130 (FO-15a): queued message payloads are AES-256-GCM ciphertext in the database file when the hub has a data key. */
class SealedMailboxPayloadTest {

    private val now = AtomicLong(1_800_000_000_000L)
    private val clock: () -> Long = { now.get() }
    private val databases = mutableListOf<ServerDatabase>()
    private val dirs = mutableListOf<Path>()

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    private fun keyOf(seed: Int) = SecretBox.ofKey(ByteArray(32) { (it + seed).toByte() })

    private fun fileDb(dir: Path): ServerDatabase =
        ServerDatabase("jdbc:sqlite:${dir.resolve("hub.db").toString().replace('\\', '/')}", 2).also { databases.add(it) }

    private fun newDir(): Path = Files.createTempDirectory("flash-sealed-mailbox").also { dirs.add(it) }

    private fun msg(id: String, payload: String = "{\"text\":\"a secret sentence\"}", origin: String = "alice", target: String = "bob") =
        MailboxMessage(origin, target, id, "MSG_TEXT", payload, now.get(), null)

    private fun rawRow(db: ServerDatabase, msgId: String): Pair<ByteArray, Int> = db.withConnection { conn ->
        conn.prepareStatement("SELECT payload, sealed FROM mailbox_envelopes WHERE msg_id = ?").use { st ->
            st.setString(1, msgId)
            st.executeQuery().use { rs ->
                check(rs.next())
                rs.getBytes(1) to rs.getInt(2)
            }
        }
    }

    @Test
    fun aQueuedPayloadIsCiphertextOnDiskAndRoundTrips() = runTest {
        val db = fileDb(newDir())
        val q = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        q.enqueue(msg("m1"))
        val (raw, sealed) = rawRow(db, "m1")
        assertEquals(1, sealed)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("secret sentence"), "the plain text must not be in the stored bytes")
        assertEquals("{\"text\":\"a secret sentence\"}", q.fetchPage("bob", 0, 10).single().payload)
    }

    @Test
    fun theStoredSizeStaysThePlainSizeSoQuotasDoNotChange() = runTest {
        val db = fileDb(newDir())
        val q = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        val payload = "x".repeat(1000)
        q.enqueue(msg("m1", payload))
        val size = db.withConnection { conn ->
            conn.createStatement().use { st -> st.executeQuery("SELECT payload_bytes FROM mailbox_envelopes").use { it.next(); it.getLong(1) } }
        }
        assertEquals(1000L, size)
    }

    @Test
    fun aHubWithoutADataKeyStoresPayloadsAsBefore() = runTest {
        val db = fileDb(newDir())
        val q = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, SecretBox.None)
        q.enqueue(msg("m1"))
        val (raw, sealed) = rawRow(db, "m1")
        assertEquals(0, sealed)
        assertEquals("{\"text\":\"a secret sentence\"}", String(raw, Charsets.UTF_8))
        assertEquals(1, q.fetchPage("bob", 0, 10).size)
    }

    @Test
    fun plainRowsFromBeforeTheKeyAreSealedAtStartupAndStillDeliver() = runTest {
        val dir = newDir()
        val db = fileDb(dir)
        val old = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, SecretBox.None)
        old.enqueue(msg("m1"))
        old.enqueue(msg("m2", "{\"text\":\"second\"}"))
        assertEquals(0, rawRow(db, "m1").second)
        val sealedQueue = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        for (id in listOf("m1", "m2")) {
            val (raw, sealed) = rawRow(db, id)
            assertEquals(1, sealed, id)
            assertFalse(String(raw, Charsets.ISO_8859_1).contains("second") || String(raw, Charsets.ISO_8859_1).contains("secret sentence"))
        }
        assertEquals(listOf("{\"text\":\"a secret sentence\"}", "{\"text\":\"second\"}"), sealedQueue.fetchPage("bob", 0, 10).map { it.payload })
        // A second start does nothing more and loses nothing.
        val again = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        assertEquals(2, again.fetchPage("bob", 0, 10).size)
    }

    @Test
    fun aSealedRowIsBoundToItsOwnRowAndCannotBeMoved() = runTest {
        val db = fileDb(newDir())
        val q = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1))
        q.enqueue(msg("m1", "{\"text\":\"for bob\"}", target = "bob"))
        q.enqueue(msg("m2", "{\"text\":\"for carol\"}", target = "carol"))
        // An attacker with write access to the file copies bob's ciphertext into carol's row.
        db.withTransaction { conn ->
            val bobs = conn.prepareStatement("SELECT payload FROM mailbox_envelopes WHERE msg_id = 'm1'").use { st -> st.executeQuery().use { it.next(); it.getBytes(1) } }
            conn.prepareStatement("UPDATE mailbox_envelopes SET payload = ? WHERE msg_id = 'm2'").use { st -> st.setBytes(1, bobs); st.executeUpdate() }
        }
        assertEquals(listOf("{\"text\":\"for bob\"}"), q.fetchPage("bob", 0, 10).map { it.payload })
        assertTrue(q.fetchPage("carol", 0, 10).isEmpty(), "the moved ciphertext does not authenticate for another row, and is skipped")
        // Skipped, not deleted.
        val count = db.withConnection { conn -> conn.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM mailbox_envelopes").use { it.next(); it.getInt(1) } } }
        assertEquals(2, count)
    }

    @Test
    fun aDifferentDataKeyCannotReadAndOneBadRowDoesNotBlockTheOthers() = runTest {
        val dir = newDir()
        val db = fileDb(dir)
        MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1)).enqueue(msg("m1"))
        val wrong = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(9))
        wrong.enqueue(msg("m2", "{\"text\":\"under the new key\"}"))
        assertEquals(listOf("{\"text\":\"under the new key\"}"), wrong.fetchPage("bob", 0, 10).map { it.payload })
    }

    @Test
    fun aSealedRowWithNoKeyConfiguredIsNeverHandedOutAsGarbage() = runTest {
        val db = fileDb(newDir())
        MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, keyOf(1)).enqueue(msg("m1"))
        val keyless = MailboxQueue(db, MailboxLimits(), clock, DeviceRegistry.AcceptAll, null, SecretBox.None)
        assertTrue(keyless.fetchPage("bob", 0, 10).isEmpty())
    }

    @Test
    fun theBinaryFormRefusesTamperingAndWrongContext() {
        val box = keyOf(3)
        val sealed = box.sealBytes("hello".toByteArray(), "ctx")
        assertEquals("hello", String(box.openBytes(sealed, "ctx")))
        assertFailsWith<SecurityException> { box.openBytes(sealed, "other") }
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<SecurityException> { box.openBytes(flipped, "ctx") }
        assertFailsWith<SecurityException> { SecretBox.None.openBytes(sealed, "ctx") }
        assertTrue(!sealed.contentEquals(box.sealBytes("hello".toByteArray(), "ctx")), "a fresh nonce every time")
    }
}
