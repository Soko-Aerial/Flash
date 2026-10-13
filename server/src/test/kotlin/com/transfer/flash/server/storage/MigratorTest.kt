package com.transfer.flash.server.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigratorTest {

    private fun tableExists(db: ServerDatabase, name: String): Boolean = db.withConnection { c ->
        c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { s ->
            s.setString(1, name)
            s.executeQuery().use { it.next() }
        }
    }

    private fun step(version: Int, sql: String) = Migration(version, "step $version") { c ->
        c.createStatement().use { it.execute(sql) }
    }

    @Test
    fun stepsApplyInOrderPerComponentAndAreRecorded() {
        ServerDatabase.inMemory().use { db ->
            val m = Migrator(db)
            m.register("alpha", listOf(step(1, "CREATE TABLE a1(x INTEGER)"), step(2, "CREATE TABLE a2(x INTEGER)")))
            m.register("beta", listOf(step(1, "CREATE TABLE b1(x INTEGER)")))
            m.migrate()
            assertEquals(2, m.currentVersion("alpha"))
            assertEquals(1, m.currentVersion("beta"))
            assertTrue(tableExists(db, "a2") && tableExists(db, "b1"))
        }
    }

    @Test
    fun migrateIsIdempotentAndOnlyRunsNewSteps() {
        ServerDatabase.inMemory().use { db ->
            var runs = 0
            val counting = Migration(1, "count") { c ->
                runs++
                c.createStatement().use { it.execute("CREATE TABLE c1(x INTEGER)") }
            }
            Migrator(db).apply { register("c", listOf(counting)); migrate(); migrate() }
            // A fresh migrator over the same database (a restart) must not re-run version 1.
            Migrator(db).apply { register("c", listOf(counting, step(2, "CREATE TABLE c2(x INTEGER)"))); migrate() }
            assertEquals(1, runs)
            assertEquals(2, Migrator(db).currentVersion("c"))
        }
    }

    @Test
    fun aFailingStepRollsBackItsOwnWorkAndItsVersionBumpAndAbortsStartup() {
        ServerDatabase.inMemory().use { db ->
            val m = Migrator(db)
            val bad = Migration(2, "half applied") { c ->
                c.createStatement().use { it.execute("CREATE TABLE half(x INTEGER)") }
                error("boom")
            }
            m.register("d", listOf(step(1, "CREATE TABLE d1(x INTEGER)"), bad))
            assertFailsWith<MigrationFailedException> { m.migrate() }
            assertEquals(1, m.currentVersion("d"), "the failed step must not bump the version")
            assertTrue(!tableExists(db, "half"), "the failed step's DDL must be rolled back with it")
        }
    }

    @Test
    fun aDatabaseWrittenByANewerBuildIsRefused() {
        ServerDatabase.inMemory().use { db ->
            Migrator(db).apply { register("e", listOf(step(1, "CREATE TABLE e1(x INTEGER)"), step(2, "CREATE TABLE e2(x INTEGER)"))); migrate() }
            val older = Migrator(db).apply { register("e", listOf(step(1, "CREATE TABLE IF NOT EXISTS e1(x INTEGER)"))) }
            assertFailsWith<MigrationFailedException> { older.migrate() }
        }
    }

    @Test
    fun versionsMustIncreaseAndAComponentRegistersOnce() {
        ServerDatabase.inMemory().use { db ->
            val m = Migrator(db)
            assertFailsWith<IllegalArgumentException> { m.register("f", listOf(step(2, "SELECT 1"), step(2, "SELECT 1"))) }
            m.register("g", emptyList())
            assertFailsWith<IllegalArgumentException> { m.register("g", emptyList()) }
        }
    }

    @Test
    fun theVersionSurvivesARealFileDatabaseRestart() {
        val dir = Files.createTempDirectory("flash-migrator")
        val url = "jdbc:sqlite:${dir.resolve("m.db")}"
        ServerDatabase(url).use { db ->
            Migrator(db).apply { register("h", listOf(step(1, "CREATE TABLE h1(x INTEGER)"))); migrate() }
        }
        ServerDatabase(url).use { db ->
            assertEquals(1, Migrator(db).currentVersion("h"))
        }
    }
}
