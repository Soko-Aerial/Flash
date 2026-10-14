@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ORGT-U50..U55: the caller that makes B1 live (verifier defect 4). Lambdas record the order of every effect. */
class OrgSnapshotCoordinatorTest {
    private val events = mutableListOf<String>()
    private var now = 1_000_000L
    private var outcome: OrgSyncOutcome = OrgSyncOutcome.Success(0, 0, 0, 0, false)

    private fun coordinator(
        root: kotlinx.coroutines.flow.Flow<String?> = MutableStateFlow(null),
        relay: kotlinx.coroutines.flow.Flow<Boolean> = MutableStateFlow(false),
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job()),
        periodMs: Long = 1_000_000L,
    ) = OrgSnapshotCoordinator(
        credentialRoot = root,
        relayConnected = relay,
        syncOnce = { force -> events += "sync(force=$force)"; outcome },
        clearSnapshot = { events += "clearSnapshot" },
        refreshResolver = { events += "refresh" },
        clearResolver = { events += "clearResolver" },
        scope = scope,
        clock = { now },
        periodMs = periodMs,
        minIntervalMs = 60_000L,
    )

    @Test
    fun `ORGT-U50 enrolment syncs at once and refreshes the resolver only after a success`() = runBlocking {
        val c = coordinator()
        c.onEnrolmentChanged("ROOT-A")
        assertEquals(listOf("refresh", "sync(force=true)", "refresh"), events)
        events.clear()
        outcome = OrgSyncOutcome.Offline
        c.onEnrolmentChanged("ROOT-A")
        assertEquals(listOf("sync(force=true)"), events, "a failed sync must not refresh from a half state")
    }

    @Test
    fun `ORGT-U51 not enrolled wipes the snapshot and never syncs`() = runBlocking {
        val c = coordinator()
        c.onEnrolmentChanged(null)
        assertEquals(listOf("clearSnapshot", "clearResolver"), events)
        c.requestSync("hub-tab", force = true)
        assertEquals(listOf("clearSnapshot", "clearResolver"), events, "no sync while not enrolled")
    }

    @Test
    fun `ORGT-U52 unenrol and a different organisation both delete the snapshot before anything else`() = runBlocking {
        val c = coordinator()
        c.onEnrolmentChanged("ROOT-A")
        events.clear()
        c.onEnrolmentChanged(null)
        assertEquals(listOf("clearSnapshot", "clearResolver"), events)
        events.clear()
        c.onEnrolmentChanged("ROOT-A")
        events.clear()
        c.onEnrolmentChanged("root-b")
        assertEquals(listOf("clearSnapshot", "clearResolver", "sync(force=true)", "refresh"), events)
        events.clear()
        c.onEnrolmentChanged("ROOT-B")
        assertEquals(listOf("sync(force=true)", "refresh"), events, "the same root in another case is the same organisation")
    }

    @Test
    fun `ORGT-U53 requests inside the minimum interval are skipped, force and time pass them`() = runBlocking {
        val c = coordinator()
        c.onEnrolmentChanged("ROOT-A")
        events.clear()
        now += 10_000
        c.requestSync("relay")
        assertTrue(events.isEmpty(), "within 60 s")
        c.requestSync("pull-to-refresh", force = true)
        assertEquals(listOf("sync(force=true)", "refresh"), events)
        events.clear()
        now += 61_000
        c.requestSync("periodic")
        assertEquals(listOf("sync(force=false)", "refresh"), events)
    }

    @Test
    fun `ORGT-U54 a sync backwards clock does not stall the loop`() = runBlocking {
        val c = coordinator()
        c.onEnrolmentChanged("ROOT-A")
        events.clear()
        now -= 3_600_000
        c.requestSync("relay")
        assertEquals(listOf("sync(force=false)", "refresh"), events)
    }

    @Test
    fun `ORGT-U55 start syncs when the relay comes up and on the periodic tick, and stop ends both`() = runBlocking {
        val root = MutableStateFlow<String?>("ROOT-A")
        val relay = MutableStateFlow(false)
        val job = Job()
        val c = coordinator(root = root, relay = relay, scope = CoroutineScope(Dispatchers.Default + job), periodMs = 200L)
        c.start()
        waitFor { synchronized(events) { events.contains("sync(force=true)") } }
        synchronized(events) { events.clear() }
        now += 61_000
        relay.value = true
        waitFor { synchronized(events) { events.contains("sync(force=false)") } }
        c.stop()
        delay(300)
        val settled = synchronized(events) { events.size }
        delay(500)
        assertEquals(settled, synchronized(events) { events.size }, "nothing runs after stop")
        job.cancel()
    }

    private suspend fun waitFor(cond: () -> Boolean) = withTimeout(5_000) {
        while (!cond()) delay(20)
    }
}
