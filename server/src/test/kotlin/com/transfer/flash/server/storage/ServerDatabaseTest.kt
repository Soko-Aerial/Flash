package com.transfer.flash.server.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerDatabaseTest {

    private lateinit var database: ServerDatabase

    @Before
    fun setUp() {
        database = ServerDatabase.inMemory(poolSize = 1)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDatabaseInitializationAndTableCreation() {
        database.withConnection { conn ->
            val tables = mutableSetOf<String>()
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")
            while (rs.next()) {
                tables.add(rs.getString("name"))
            }

            assertTrue("mailbox_envelopes table should exist", tables.contains("mailbox_envelopes"))
            assertTrue("device_registry table should exist", tables.contains("device_registry"))
            assertTrue("audit_log table should exist", tables.contains("audit_log"))
        }
    }

    @Test
    fun testIndexValidation() {
        database.withConnection { conn ->
            val indexes = mutableSetOf<String>()
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")
            while (rs.next()) {
                indexes.add(rs.getString("name"))
            }

            assertTrue(
                "idx_mailbox_target_delivered_expires index should exist",
                indexes.contains("idx_mailbox_target_delivered_expires"),
            )
            assertTrue(
                "idx_mailbox_origin_msg index should exist",
                indexes.contains("idx_mailbox_origin_msg"),
            )
        }
    }

    @Test
    fun testMailboxEnvelopesColumns() {
        database.withConnection { conn ->
            val columns = mutableMapOf<String, String>()
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("PRAGMA table_info(mailbox_envelopes)")
            while (rs.next()) {
                columns[rs.getString("name")] = rs.getString("type")
            }

            assertEquals("TEXT", columns["id"]?.uppercase())
            assertEquals("TEXT", columns["origin_device_id"]?.uppercase())
            assertEquals("TEXT", columns["target_device_id"]?.uppercase())
            assertEquals("TEXT", columns["msg_id"]?.uppercase())
            assertEquals("TEXT", columns["kind"]?.uppercase())
            assertEquals("INTEGER", columns["hops"]?.uppercase())
            assertEquals("INTEGER", columns["counter"]?.uppercase())
            assertEquals("BLOB", columns["payload"]?.uppercase())
            assertEquals("TEXT", columns["auth_tag"]?.uppercase())
            assertEquals("INTEGER", columns["created_at"]?.uppercase())
            assertEquals("INTEGER", columns["expires_at"]?.uppercase())
            assertEquals("INTEGER", columns["delivered"]?.uppercase())
        }
    }

    @Test
    fun testDeviceRegistryCrudOperations() {
        val now = System.currentTimeMillis()
        val device = DeviceRegistryRecord(
            deviceId = "dev-alpha-001",
            friendlyName = "Alpha Pixel 9",
            role = "FIELD_OPERATOR",
            department = "LOGISTICS",
            certSerial = 100245L,
            enrolledAt = now,
            lastSeenAt = now,
        )

        database.registerDevice(device)

        val retrieved = database.getDevice("dev-alpha-001")
        assertNotNull(retrieved)
        assertEquals("Alpha Pixel 9", retrieved?.friendlyName)
        assertEquals("FIELD_OPERATOR", retrieved?.role)
        assertEquals("LOGISTICS", retrieved?.department)
        assertEquals(100245L, retrieved?.certSerial)

        val updatedTime = now + 5000L
        val updated = database.updateDeviceLastSeen("dev-alpha-001", updatedTime)
        assertTrue(updated)

        val updatedRecord = database.getDevice("dev-alpha-001")
        assertEquals(updatedTime, updatedRecord?.lastSeenAt)

        assertNull(database.getDevice("non-existent-device"))
    }

    @Test
    fun testAuditLogChaining() {
        val entry1 = AuditLogRecord(
            timestamp = 1000L,
            actor = "admin-root",
            action = "DEVICE_ENROLLED",
            target = "dev-alpha-001",
            details = "Enrolled via offline token",
            prevHash = "GENESIS_HASH",
            entryHash = "HASH_ENTRY_1",
        )
        val id1 = database.insertAuditLog(entry1)
        assertTrue(id1 > 0)

        val entry2 = AuditLogRecord(
            timestamp = 2000L,
            actor = "admin-root",
            action = "DEVICE_REVOKED",
            target = "dev-alpha-001",
            details = "Compromised credential reported",
            prevHash = "HASH_ENTRY_1",
            entryHash = "HASH_ENTRY_2",
        )
        val id2 = database.insertAuditLog(entry2)
        assertTrue(id2 > id1)

        val latest = database.getLatestAuditLog()
        assertNotNull(latest)
        assertEquals(id2, latest?.id)
        assertEquals("DEVICE_REVOKED", latest?.action)
        assertEquals("HASH_ENTRY_1", latest?.prevHash)
        assertEquals("HASH_ENTRY_2", latest?.entryHash)
    }
}
