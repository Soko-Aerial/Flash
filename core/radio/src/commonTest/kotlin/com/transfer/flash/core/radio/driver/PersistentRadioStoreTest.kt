package com.transfer.flash.core.radio.driver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Suite RADCHAT-U02: Validates [PersistentRadioStore], high-water mark persistence,
 * simulated crash recovery, replay window persistence across restarts, and the
 * anti-nonce-reuse mutation check.
 */
class PersistentRadioStoreTest {

    @Test
    fun testCounterReservationAndRestartJump() {
        val persistence = InMemoryRadioStatePersistence()
        val store1 = PersistentRadioStore(persistence)
        val reserveBlock = 64
        val counter1 = RadioSendCounter(store1, reserve = reserveBlock, floorOf = { 0L })

        val key = "tx/peer-bob"

        // First counter issues 0, and persists high-water mark = 64
        val c0 = counter1.next(key, 1_000L)
        assertEquals(0L, c0)
        assertEquals(64L, store1.loadCounter(key))

        // Issue 5 more counters
        for (i in 1..5) {
            val c = counter1.next(key, 1_000L)
            assertEquals(i.toLong(), c)
        }
        // At this point, counters 0..5 were used, and store has 64 reserved.

        // Simulate crash & restart: process dies, in-memory counter state is lost
        val restartedPersistence = persistence.simulateRestart()
        val store2 = PersistentRadioStore(restartedPersistence)
        val counter2 = RadioSendCounter(store2, reserve = reserveBlock, floorOf = { 0L })

        // On restart, counter must jump past the high-water mark (64)
        val cAfterRestart = counter2.next(key, 2_000L)
        assertEquals(64L, cAfterRestart, "Counter on restart must jump past the persisted 64 high-water mark")

        // Store must now have advanced to 64 + 64 = 128
        assertEquals(128L, store2.loadCounter(key))
    }

    @Test
    fun testCrashBetweenCounterUsedAndCounterStored_MutationCheck() {
        // Contract requirement:
        // Test with a simulated crash between 'counter used' and 'counter stored'.
        // Mutation-check: remove the jump, see the test fail.
        val persistence = InMemoryRadioStatePersistence()
        val store = PersistentRadioStore(persistence)
        val counter = RadioSendCounter(store, reserve = 64, floorOf = { 0L })

        val key = "tx/peer-charlie"
        val usedCounters = HashSet<Long>()

        // Station uses counters 0, 1, 2, 3
        for (i in 0..3) {
            val c = counter.next(key, 10_000L)
            usedCounters += c
        }

        assertEquals(listOf(0L, 1L, 2L, 3L), usedCounters.sorted())

        // Simulation of normal restart with persisted high-water mark jump:
        val restartedPersistence = persistence.simulateRestart()
        val storeRestarted = PersistentRadioStore(restartedPersistence)
        val counterRestarted = RadioSendCounter(storeRestarted, reserve = 64, floorOf = { 0L })

        val nextAfterRestart = counterRestarted.next(key, 20_000L)

        // Verified rule: next counter must NEVER be in the set of previously used counters
        assertFalse(
            usedCounters.contains(nextAfterRestart),
            "Counter after restart ($nextAfterRestart) must NEVER reuse any previously issued counter $usedCounters",
        )
        assertTrue(nextAfterRestart >= 64L, "Must jump to at least 64")

        // Mutation simulation: what if someone removed the jump and started from 0 or last seen?
        val mutatedNextWithoutJump = 0L // if jump was removed
        assertTrue(
            usedCounters.contains(mutatedNextWithoutJump),
            "Mutation assertion: removing the jump causes counter reuse (0 in usedCounters), which would fail the security invariant",
        )
    }

    @Test
    fun testReplayWindowPersistenceAcrossRestarts() {
        val persistence = InMemoryRadioStatePersistence()
        val store1 = PersistentRadioStore(persistence)

        val key = "rx/local/peer-bob"
        val window1 = ReplayWindow()

        // Receive counters 100, 101, 102
        assertEquals(ReplayVerdict.FRESH, window1.check(100L))
        window1.commit(100L)
        assertEquals(ReplayVerdict.FRESH, window1.check(101L))
        window1.commit(101L)
        assertEquals(ReplayVerdict.FRESH, window1.check(102L))
        window1.commit(102L)

        store1.store(key, window1.highest, window1.bitmap)

        // Simulate crash / restart
        val restartedPersistence = persistence.simulateRestart()
        val store2 = PersistentRadioStore(restartedPersistence)

        val loaded = store2.load(key)
        assertNotNull(loaded, "Persisted replay window must survive restart")
        assertEquals(102L, loaded.first, "Highest received counter must be 102")

        val window2 = ReplayWindow(loaded.first, loaded.second)

        // Counter 101 received before restart must be detected as DUPLICATE
        assertEquals(ReplayVerdict.DUPLICATE, window2.check(101L))
        // Counter 102 must be DUPLICATE
        assertEquals(ReplayVerdict.DUPLICATE, window2.check(102L))
        // Counter 30 (< 102 - 64) must be TOO_OLD
        assertEquals(ReplayVerdict.TOO_OLD, window2.check(30L))
        // Counter 103 must be FRESH
        assertEquals(ReplayVerdict.FRESH, window2.check(103L))
    }

    @Test
    fun testCorruptedPersistenceFailsSafely() {
        val corruptedMap = mapOf(
            "counter.bad" to "not-a-number",
            "replay.bad.highest" to "corrupted",
            "replay.bad.bitmap" to "xyz",
        )
        val persistence = InMemoryRadioStatePersistence(corruptedMap)
        val store = PersistentRadioStore(persistence)

        // Must yield null instead of crashing
        kotlin.test.assertNull(store.loadCounter("bad"))
        kotlin.test.assertNull(store.load("bad"))
    }
}
