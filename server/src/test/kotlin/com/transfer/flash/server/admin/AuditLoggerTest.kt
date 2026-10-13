package com.transfer.flash.server.admin

import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.storage.ServerDatabase
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SEC-17: the audit log is persisted, hash-chained, signed and bounded. */
class AuditLoggerTest {

    private var now = 1_800_000_000_000L

    private fun signer(): AuditSigner {
        val pair = FlashCrypto.generateEcKeyPair()
        return AuditSigner(pair.private, pair.toBase64Spki())
    }

    @Test
    fun entriesAreChainedAndATamperedRecordIsDetected() {
        val logger = AuditLogger(clock = { now })
        val e1 = logger.log("admin1", "CREATE_DEPARTMENT", "dept-infantry")
        assertEquals(0L, e1.index)
        assertEquals(AuditLogger.GENESIS_HASH, e1.prevHash)
        val e2 = logger.log("admin1", "CREATE_GROUP", "grp-recon")
        assertEquals(e1.hash, e2.prevHash)
        logger.log("admin1", "INVITE_EMPLOYEE", "Captain Mensah")
        assertTrue(logger.verifyIntegrity())
        assertEquals(3, logger.size())

        logger.tamperWithEntryForTesting(1, "TAMPERED_DETAILS_MALICIOUS")
        val verdict = logger.verifyChain()
        assertFalse(verdict.ok)
        assertEquals(1L, verdict.firstBadIndex)
    }

    @Test
    fun aSeparatorInsideAFieldCanNotBeMovedAcrossAFieldBoundary() {
        val a = AuditLogger(clock = { now }).log("a|b", "c", "d")
        val b = AuditLogger(clock = { now }).log("a", "b|c", "d")
        assertTrue(a.hash != b.hash, "the old `|`-joined form hashed both to the same value")
    }

    @Test
    fun recordsWrittenBeforeTheLengthPrefixedHashStillVerifyAndNewOnesChainOntoThem() {
        val db = ServerDatabase.inMemory()
        val logger = AuditLogger(db, clock = { now })
        // A version-1 record exactly as an older hub wrote it.
        val prev = AuditLogger.GENESIS_HASH
        val legacy = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$prev|$now|admin|old.action|old details".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        db.withConnection { conn ->
            conn.prepareStatement("INSERT INTO admin_audit(idx, ts, actor, action, details, prev_hash, hash, sig, chain_ver) VALUES(0,?,?,?,?,?,?,NULL,1)").use { st ->
                st.setLong(1, now); st.setString(2, "admin"); st.setString(3, "old.action"); st.setString(4, "old details"); st.setString(5, prev); st.setString(6, legacy)
                st.executeUpdate()
            }
        }
        val next = logger.log("admin", "new.action", "new details")
        assertEquals(legacy, next.prevHash)
        assertTrue(logger.verifyChain().ok, logger.verifyChain().toString())
        logger.tamperWithEntryForTesting(0, "changed")
        assertEquals(0L, logger.verifyChain().firstBadIndex, "a version-1 record is still protected")
    }

    @Test
    fun theLogSurvivesARestartAndKeepsChaining() {
        val dir = Files.createTempDirectory("flash-audit")
        val url = "jdbc:sqlite:${dir.resolve("audit.db")}"
        val a = AuditLogger(ServerDatabase(url), clock = { now })
        a.log("x", "one", "1")
        val second = a.log("x", "two", "2")
        val b = AuditLogger(ServerDatabase(url), clock = { now })
        assertEquals(2, b.size())
        val third = b.log("x", "three", "3")
        assertEquals(second.hash, third.prevHash)
        assertTrue(b.verifyIntegrity())
    }

    @Test
    fun everyRecordIsSignedAndAForeignKeyDoesNotVerifyTheLog() {
        val s = signer()
        val logger = AuditLogger(signer = s, clock = { now })
        repeat(3) { logger.log("a", "act$it", "d") }
        assertTrue(logger.getEntries().all { it.signature != null })
        assertTrue(logger.verifyChain(s.publicKey).ok)
        val other = FlashCrypto.generateEcKeyPair().toBase64Spki()
        val forged = logger.verifyChain(other)
        assertFalse(forged.ok, "records signed by another key must not verify")
    }

    @Test
    fun anAttackerWhoRewritesTheChainWithoutTheKeyIsCaughtBySignatures() {
        val s = signer()
        val db = ServerDatabase.inMemory()
        val logger = AuditLogger(db, s, clock = { now })
        logger.log("a", "one", "d1")
        logger.log("a", "two", "d2")
        // Replace record 1 with a perfectly chained but unsigned (forged) record.
        val e0 = logger.getEntry(0)!!
        val forgedHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${e0.hash}|$now|a|two|forged".toByteArray()).toHex()
        db.withConnection { c ->
            c.prepareStatement("UPDATE admin_audit SET details = 'forged', hash = ?, sig = NULL WHERE idx = 1").use { it.setString(1, forgedHash); it.executeUpdate() }
        }
        assertFalse(logger.verifyChain().ok)
    }

    @Test
    fun truncatingTheTailIsDetectedThroughASignedAnchor() {
        val s = signer()
        val logger = AuditLogger(ServerDatabase.inMemory(), s, clock = { now }, anchorEvery = 4)
        repeat(8) { logger.log("a", "act", "$it") }
        assertTrue(logger.verifyChain().ok)
        assertEquals(2, logger.anchors().size)
        logger.truncateForTesting(5)
        val verdict = logger.verifyChain()
        assertFalse(verdict.ok, "the head an anchor vouches for is gone")
        assertNotNull(verdict.reason)
    }

    @Test
    fun csvExportNeutralisesSpreadsheetFormulas() {
        val logger = AuditLogger(clock = { now })
        logger.log("=cmd|' /C calc'!A0", "+SUM(1)", "-2+3\twith \"quotes\", and commas")
        val csv = logger.exportCsv()
        val row = csv.lines()[1]
        assertTrue(row.contains("\"'=cmd"), row)
        assertTrue(row.contains("\"'+SUM(1)\""), row)
        assertTrue(row.contains("\"'-2+3"), row)
        assertTrue(row.contains("\"\"quotes\"\""), row)
        assertEquals(7, csv.lines().first().split(',').size)
    }

    @Test
    fun controlCharactersAreFlattenedAndDetailsAreBounded() {
        val logger = AuditLogger(clock = { now })
        val e = logger.log("a\nb", "x\r\ny", "z".repeat(10_000))
        assertFalse(e.actor.contains('\n'))
        assertFalse(e.action.contains('\r'))
        assertEquals(AuditLogger.MAX_DETAILS_CHARS, e.details.length)
    }

    @Test
    fun unauthenticatedEventsAreBudgetedAndSummarised() {
        val logger = AuditLogger(clock = { now }, unauthenticatedBudgetPerMinute = 3)
        val admitted = (1..10).count { logger.logUnauthenticated("anon", "login.failed", "n$it") != null }
        assertEquals(3, admitted)
        assertEquals(3, logger.size())
        now += 61_000
        assertNotNull(logger.logUnauthenticated("anon", "login.failed", "later"))
        val actions = logger.getEntries().map { it.action }
        assertTrue("audit.suppressed" in actions, actions.toString())
        assertTrue(logger.getEntries().first { it.action == "audit.suppressed" }.details.contains("7"))
        // authenticated events are never budgeted
        repeat(20) { logger.log("admin", "device.revoke", "x") }
        assertEquals(20, logger.query(AuditFilter(actionPrefix = "device.")).size)
    }

    @Test
    fun filtersMatchActorActionPrefixTimeAndText() {
        val logger = AuditLogger(clock = { now })
        logger.log("alice", "device.revoke", "dev-1")
        now += 1000
        logger.log("bob", "invite.create", "100%_sure")
        now += 1000
        logger.log("alice", "device.unrevoke", "dev-1")
        assertEquals(2, logger.query(AuditFilter(actor = "alice")).size)
        assertEquals(2, logger.query(AuditFilter(actionPrefix = "device.")).size)
        assertEquals(1, logger.query(AuditFilter(text = "100%_")).size)
        assertEquals(0, logger.query(AuditFilter(text = "100%x")).size, "LIKE wildcards in the filter are escaped")
        assertEquals(1, logger.query(AuditFilter(fromMs = now - 500)).size)
        assertEquals(3, logger.count(AuditFilter()))
        assertNull(logger.getEntry(99))
    }
}
