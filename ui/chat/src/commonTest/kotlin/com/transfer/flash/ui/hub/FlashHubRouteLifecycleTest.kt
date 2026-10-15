package com.transfer.flash.ui.hub

import com.transfer.flash.ui.navigation.FlashBackStackState
import com.transfer.flash.ui.navigation.FlashDestination
import com.transfer.flash.ui.navigation.FlashNavigationMath
import com.transfer.flash.ui.navigation.FlashNavigationState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HUBT-U91..: route intake across a process lifetime, launch arguments, and a seeded navigation property test. */
class FlashHubRouteLifecycleTest {

    private fun decide(raw: String?, known: Boolean, enrolled: Boolean) = FlashHubMath.routeDecision(raw, known, enrolled)

    // --- launch arguments --------------------------------------------------------------------------------

    @Test fun launchArgumentIsRead() =
        assertEquals("hub/announcement/a1", FlashHubMath.routeFromArgs(arrayOf("--x", "--hub-route=hub/announcement/a1")))

    @Test fun noArgumentOrAnEmptyOneIsNull() {
        assertNull(FlashHubMath.routeFromArgs(emptyArray()))
        assertNull(FlashHubMath.routeFromArgs(arrayOf("--hub-route=")))
        assertNull(FlashHubMath.routeFromArgs(arrayOf("--hub-route=   ")))
    }

    @Test fun theFirstArgumentWins() =
        assertEquals("hub/person/a", FlashHubMath.routeFromArgs(arrayOf("--hub-route=hub/person/a", "--hub-route=hub/person/b")))

    @Test fun aDamagedArgumentIsDroppedByTheDecisionNotTheParser() {
        val raw = FlashHubMath.routeFromArgs(arrayOf("--hub-route=hub/person/../../etc"))
        assertEquals(FlashHubMath.RouteDecision.Drop, decide(raw, known = true, enrolled = true))
    }

    // --- cold start: the route outlives the unknown-enrolment window ---------------------------------------

    @Test fun coldStartWaitsForEnrolmentThenOpensTheHub() {
        val route = FlashHubMath.routeAnnouncement("a1")
        assertEquals(FlashHubMath.RouteDecision.Wait, decide(route, known = false, enrolled = false))
        assertEquals(FlashHubMath.RouteDecision.OpenHub(route), decide(route, known = true, enrolled = true))
    }

    @Test fun anUnenrolledDeviceLandsOnChatsAndForgetsTheRoute() =
        assertEquals(FlashHubMath.RouteDecision.NotEnrolled, decide(FlashHubMath.routePerson("d1"), known = true, enrolled = false))

    @Test fun anUnknownEnrolmentNeverReportsNotEnrolled() {
        // The cold-start default is "not enrolled"; a route must never be thrown away because of that default.
        assertEquals(FlashHubMath.RouteDecision.Wait, decide(FlashHubMath.routePerson("d1"), known = false, enrolled = false))
    }

    @Test fun routesRoundTripThroughTheirBuilders() {
        assertEquals(FlashHubMath.Route.Announcement("a-1_x"), FlashHubMath.parseRoute(FlashHubMath.routeAnnouncement("a-1_x")))
        assertEquals(FlashHubMath.Route.Person("dev:01.x"), FlashHubMath.parseRoute(FlashHubMath.routePerson("dev:01.x")))
    }

    @Test fun surroundingWhitespaceIsTolerated() =
        assertEquals(FlashHubMath.RouteDecision.OpenHub("hub/person/d1"), decide("  hub/person/d1 \n", true, true))

    // --- navigation property test (seeded, deterministic) ------------------------------------------------

    @Test fun randomNavigationNeverBreaksTheHubInvariants() {
        val rnd = Random(58)
        repeat(300) { run ->
            val nav = FlashNavigationState()
            repeat(80) {
                when (rnd.nextInt(8)) {
                    0 -> nav.updateHubEnrolled(rnd.nextBoolean())
                    1 -> nav.selectTab(FlashDestination.Hub)
                    2 -> nav.selectTab(FlashDestination.Transfers)
                    3 -> nav.selectTab(FlashDestination.entries[rnd.nextInt(FlashDestination.entries.size)])
                    4 -> nav.openTransfers()
                    5 -> nav.navigate(FlashDestination.Conversation, "c${rnd.nextInt(20)}")
                    6 -> nav.back()
                    else -> nav.navigate(FlashDestination.Hub)
                }
                val entries: List<FlashBackStackState> = nav.entries
                assertTrue(entries.isNotEmpty(), "run $run: empty stack")
                assertTrue(entries.size <= FlashNavigationMath.MAX_STACK_DEPTH, "run $run: too deep")
                if (!nav.hubEnrolled) assertTrue(entries.none { it.destination == FlashDestination.Hub }, "run $run: Hub while unenrolled")
                assertTrue(FlashNavigationMath.isTabRoot(nav.shellTab, nav.hubEnrolled), "run $run: shell tab is not a tab")
            }
        }
    }

    @Test fun unenrolWhileTheHubIsOpenOnDetailStillLandsOnChats() {
        val nav = FlashNavigationState()
        nav.updateHubEnrolled(true)
        nav.selectTab(FlashDestination.Hub)
        nav.navigate(FlashDestination.Conversation, "c1")
        nav.updateHubEnrolled(false)
        assertTrue(nav.entries.none { it.destination == FlashDestination.Hub })
        assertEquals(FlashDestination.Conversation, nav.current.destination)
    }
}
