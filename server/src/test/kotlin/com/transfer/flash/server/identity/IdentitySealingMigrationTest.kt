package com.transfer.flash.server.identity

import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR-124: identity schema step 2 (sealing keys) applied to a database that is at step 1 (every hub installed before
 * the sealing-key endpoint). Existing devices must survive and the cleanup triggers must work on them.
 */
class IdentitySealingMigrationTest {

    private fun tableExists(db: ServerDatabase, name: String): Boolean = db.withConnection { c ->
        c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { s ->
            s.setString(1, name)
            s.executeQuery().use { it.next() }
        }
    }

    @Test
    fun aVersionOneDatabaseIsUpgradedKeepingItsDevicesAndGainsWorkingSealingStorage() {
        ServerDatabase.inMemory().use { db ->
            // A hub of the previous release: only step 1 was ever applied.
            Migrator(db).apply { register(IdentityStore.COMPONENT, IdentityStore.MIGRATIONS.take(1)); migrate() }
            assertEquals(1, Migrator(db).currentVersion(IdentityStore.COMPONENT))
            assertTrue(!tableExists(db, "hub_sealing_keys"))
            db.withConnection { c ->
                c.createStatement().use {
                    it.execute(
                        "INSERT INTO hub_devices(device_id, subject_public_key, serial, name, department, role, enrolled_at, cert_expiry, " +
                            "status, status_changed_at) VALUES('dev-old','AAAA',1,'Old phone','eng','member',1,2,'ACTIVE',1)",
                    )
                }
            }

            val store = IdentityStore.open(db) // the new release starts: step 2 runs

            assertEquals(IdentityStore.MIGRATIONS.size, Migrator(db).currentVersion(IdentityStore.COMPONENT))
            assertTrue(tableExists(db, "hub_sealing_keys") && tableExists(db, "hub_sealing_retired"))
            assertNotNull(store.device("dev-old"), "the enrolled device survived the upgrade")
            assertNull(store.sealingKey("dev-old"), "no key until the device publishes one")

            val stored = store.putSealingKey("dev-old", "SPKI", "SIG", "FP1", 10L)
            assertTrue(stored is SealingPutOutcome.Stored)
            assertNotNull(store.sealingKey("dev-old"))
            // the cleanup trigger created by the migration fires for a device that existed BEFORE it
            store.setStatus("dev-old", DeviceStatus.REVOKED, 20L)
            assertNull(store.sealingKey("dev-old"), "revoking a pre-existing device removes its key")
            // and re-running the migration is a no-op
            IdentityStore.open(db)
            assertEquals(IdentityStore.MIGRATIONS.size, Migrator(db).currentVersion(IdentityStore.COMPONENT))
        }
    }
}
