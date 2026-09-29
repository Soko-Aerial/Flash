package com.transfer.flash.core.network.remembered

import com.transfer.flash.core.network.planner.ConnectionPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [RememberedRoutes]: DR1's rules (ADR-047), on virtual time and an in-memory store. */
@OptIn(ExperimentalCoroutinesApi::class)
class RememberedRoutesTest {

    private class FakeStore(rows: List<RememberedRoute> = emptyList()) : RememberedEndpointStore {
        val rows = rows.toMutableList()
        var failWrites = false
        var failLoad = false

        override suspend fun loadAll(): List<RememberedRoute> {
            if (failLoad) error("disk gone")
            return rows.toList()
        }

        override suspend fun save(route: RememberedRoute) {
            if (failWrites) error("disk full")
            rows.removeAll { it.deviceId == route.deviceId && it.host == route.host && it.port == route.port }
            rows += route
        }

        override suspend fun delete(deviceId: String, host: String, port: Int) {
            rows.removeAll { it.deviceId == deviceId && it.host == host && it.port == port }
        }

        override suspend fun deleteForDevice(deviceId: String) {
            rows.removeAll { it.deviceId == deviceId }
        }
    }

    private class Rig(
        private val test: TestScope,
        val store: FakeStore? = FakeStore(),
        val paired: MutableSet<String> = hashSetOf("b", "c"),
    ) {
        var wall = 1_700_000_000_000L
        var mono = 10_000L
        val live = HashSet<String>()
        val logs = ArrayList<String>()
        val routes = RememberedRoutes(
            scope = test.backgroundScope,
            store = store,
            isPaired = { it in paired },
            wallClockMs = { wall },
            hasLiveSession = { it in live },
            monotonicMs = { mono },
            log = { logs += it },
        )

        fun start() {
            routes.start()
            test.runCurrent()
        }

        fun flush() = test.runCurrent()

        fun sighted(): Map<String, String> = routes.sightings().associate { it.deviceId to "${it.host}:${it.port}" }
    }

    @Test
    fun `a route is written only after an authenticated session`() = runTest {
        val rig = Rig(this)
        rig.start()
        assertEquals(emptyMap(), rig.sighted())
        assertEquals(emptyList(), rig.store!!.rows)

        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.flush()

        assertEquals(mapOf("b" to "192.168.1.20:45822"), rig.sighted())
        assertEquals(listOf("192.168.1.20"), rig.store.rows.map { it.host })
    }

    @Test
    fun `an unpaired peer is never remembered`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("stranger", "192.168.1.99", 45822)
        rig.flush()
        assertEquals(emptyMap(), rig.sighted())
        assertEquals(emptyList(), rig.store!!.rows)
    }

    @Test
    fun `a peer that is unpaired later stops being offered even before its rows are deleted`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.paired -= "b"
        assertEquals(emptyMap(), rig.sighted())
    }

    @Test
    fun `at most four routes per peer are kept and the oldest is evicted from storage too`() = runTest {
        val rig = Rig(this)
        rig.start()
        for (i in 1..5) {
            rig.wall += 120_000
            rig.routes.onAuthenticated("b", "10.0.0.$i", 45822)
        }
        rig.flush()

        assertEquals(setOf("10.0.0.2", "10.0.0.3", "10.0.0.4", "10.0.0.5"), rig.store!!.rows.map { it.host }.toSet())
        assertNull(rig.routes.routeFor("b", "10.0.0.1", 45822), "the oldest route must be gone from memory")
        assertEquals("10.0.0.5:45822", rig.sighted()["b"], "the newest route is offered first")
    }

    @Test
    fun `a pin mismatch deletes that route only`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.wall += 120_000
        rig.routes.onAuthenticated("b", "192.168.43.1", 45822)
        rig.flush()

        rig.routes.onIdentityMismatch("b", "192.168.43.1", 45822)
        rig.flush()

        assertEquals(listOf("192.168.1.20"), rig.store!!.rows.map { it.host })
        assertEquals("192.168.1.20:45822", rig.sighted()["b"])
    }

    @Test
    fun `a failed dial backs off, the next route is offered, and the backoff ends`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.wall += 120_000
        rig.routes.onAuthenticated("b", "192.168.43.1", 45822)
        rig.flush()
        val newest = assertNotNull(rig.routes.routeFor("b", "192.168.43.1", 45822))

        rig.routes.onDialFailed(newest)
        assertEquals("192.168.1.20:45822", rig.sighted()["b"], "the route that has not failed goes next")

        val other = assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822))
        rig.routes.onDialFailed(other)
        assertEquals(emptyMap(), rig.sighted(), "both routes are backing off")

        rig.mono += RememberedRoutes.BACKOFF_BASE_MS + 1
        assertEquals(setOf("b"), rig.sighted().keys, "the backoff must end")
    }

    @Test
    fun `the backoff doubles per failure and is capped`() {
        assertEquals(30_000L, RememberedRoutes.backoffMs(1))
        assertEquals(60_000L, RememberedRoutes.backoffMs(2))
        assertEquals(120_000L, RememberedRoutes.backoffMs(3))
        assertEquals(RememberedRoutes.BACKOFF_MAX_MS, RememberedRoutes.backoffMs(40))
    }

    @Test
    fun `resetBackoff lets every route be dialed again at once`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.routes.onDialFailed(assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822)))
        assertEquals(emptyMap(), rig.sighted())

        rig.routes.resetBackoff()

        assertEquals(setOf("b"), rig.sighted().keys)
    }

    @Test
    fun `a dial that only lost a glare race is not a failure`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.live += "b"

        rig.routes.onDialFailed(assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822)))

        assertEquals(setOf("b"), rig.sighted().keys, "no backoff while the peer has a live session")
    }

    @Test
    fun `the first failure is persisted so a restart keeps the expiry clock`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.flush()
        rig.wall += 5_000

        rig.routes.onDialFailed(assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822)))
        rig.flush()

        assertEquals(rig.wall, rig.store!!.rows.single().firstFailureAtMs)
    }

    @Test
    fun `a route expires only after enough failures over at least a week`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.flush()
        fun fail() {
            rig.mono += RememberedRoutes.BACKOFF_MAX_MS + 1
            rig.routes.onDialFailed(assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822)))
            rig.flush()
        }

        repeat(RememberedRoutes.EXPIRE_FAILURES + 3) { fail() }
        assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822), "many failures inside one day must not expire it")

        rig.wall += RememberedRoutes.EXPIRE_AFTER_MS
        fail()

        assertNull(rig.routes.routeFor("b", "192.168.1.20", 45822))
        assertEquals(emptyList(), rig.store!!.rows)
    }

    @Test
    fun `load restores paired rows and deletes the rows of peers that are no longer paired`() = runTest {
        val store = FakeStore(
            listOf(
                RememberedRoute("b", "192.168.1.20", 45822, lastConnectedAtMs = 5),
                RememberedRoute("gone", "192.168.1.30", 45822, lastConnectedAtMs = 6),
            ),
        )
        val rig = Rig(this, store)
        rig.start()

        assertEquals(mapOf("b" to "192.168.1.20:45822"), rig.sighted())
        assertEquals(listOf("b"), store.rows.map { it.deviceId })
    }

    @Test
    fun `a route learned before the load finishes is not overwritten by an older stored row`() = runTest {
        val store = FakeStore(listOf(RememberedRoute("b", "192.168.1.20", 45822, lastConnectedAtMs = 5, firstFailureAtMs = 4)))
        val rig = Rig(this, store)
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.start()

        assertNull(rig.routes.routeFor("b", "192.168.1.20", 45822)!!.firstFailureAtMs)
    }

    @Test
    fun `a storage failure costs persistence but never a dial hint`() = runTest {
        val store = FakeStore().apply { failWrites = true }
        val rig = Rig(this, store)
        rig.start()

        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        rig.routes.onAuthenticated("c", "192.168.1.21", 45822)
        rig.flush()

        assertEquals(setOf("b", "c"), rig.sighted().keys)
        assertTrue(rig.logs.any { "storage write failed" in it })
    }

    @Test
    fun `a load failure leaves the class working without stored routes`() = runTest {
        val store = FakeStore().apply { failLoad = true }
        val rig = Rig(this, store)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        assertEquals(setOf("b"), rig.sighted().keys)
    }

    @Test
    fun `without a store the routes still work for this run`() = runTest {
        val rig = Rig(this, store = null)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        assertEquals(setOf("b"), rig.sighted().keys)
    }

    @Test
    fun `routeFor matches the exact host and port and nothing else`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        assertNotNull(rig.routes.routeFor("b", "192.168.1.20", 45822))
        assertNull(rig.routes.routeFor("b", "192.168.1.20", 45823))
        assertNull(rig.routes.routeFor("b", "192.168.1.21", 45822))
        assertNull(rig.routes.routeFor("c", "192.168.1.20", 45822))
        assertNull(rig.routes.routeFor(null, "192.168.1.20", 45822))
    }

    // ---- the plan's DR1 exit criteria, through the real planner ----

    @Test
    fun `a live discovery sighting outranks the remembered route for the same peer`() = runTest {
        val rig = Rig(this)
        rig.start()
        rig.routes.onAuthenticated("b", "192.168.1.20", 45822)
        val discovered = ConnectionPlanner.Sighting("b", "192.168.1.77", 45822, "Bee")

        val plan = ConnectionPlanner(localDeviceId = "a").plan(
            nowMs = 0,
            sightings = listOf(discovered) + rig.routes.sightings(),
            links = NoSessions,
        )

        assertEquals(listOf("192.168.1.77"), plan.dials.map { it.host })
    }

    @Test
    fun `after a restart the peer is dialed from storage with discovery silent`() = runTest {
        val store = FakeStore()
        val first = Rig(this, store)
        first.start()
        first.routes.onAuthenticated("b", "192.168.1.20", 45822)
        first.flush()

        // A new process: empty memory, the same database, and discovery reports nothing.
        val second = Rig(this, store)
        second.start()
        val plan = ConnectionPlanner(localDeviceId = "a").plan(
            nowMs = 0,
            sightings = second.routes.sightings(),
            links = NoSessions,
        )

        assertEquals(listOf("192.168.1.20" to 45822), plan.dials.map { it.host to it.port })
        assertEquals(listOf("b"), plan.dials.map { it.peerDeviceId })
    }

    private object NoSessions : ConnectionPlanner.Links {
        override fun hasLiveSession(deviceId: String) = false
        override fun isReconnectInFlight(deviceId: String) = false
    }
}
