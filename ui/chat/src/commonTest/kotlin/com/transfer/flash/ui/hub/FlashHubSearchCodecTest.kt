@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.ui.hub

import com.transfer.flash.core.messaging.model.HubAnnouncementUi
import com.transfer.flash.core.messaging.model.HubDepartmentUi
import com.transfer.flash.core.messaging.model.HubGroupMembership
import com.transfer.flash.core.messaging.model.HubGroupUi
import com.transfer.flash.core.messaging.model.HubPersonRole
import com.transfer.flash.core.messaging.model.HubPersonUi
import com.transfer.flash.core.messaging.model.HubReachability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HUBT-U60..: read marking geometry, combined search, and the saved selection of the route. */
class FlashHubSearchCodecTest {

    private fun dept(id: String, name: String, heads: List<String> = emptyList()) =
        HubDepartmentUi(id, name, 3, 1, false, heads.map { "id-$it" }, heads, false)

    private fun ann(id: String, title: String, body: String = "b", author: String = "A") = HubAnnouncementUi(
        id, "org", null, title, body, author, "admin", 1, 1, false, false, null, 1, false,
    )

    private fun group(id: String, name: String, dept: String) =
        HubGroupUi(id, name, dept, 4, null, false, HubGroupMembership.NOT_JOINED, null)

    private fun person(id: String) = HubPersonUi(id, id, "d1", "Finance", HubPersonRole.PERSON, null, HubReachability.UNKNOWN, false, false, false)

    // --- visibleFraction (read marking) -------------------------------------------------------------------

    @Test fun fullyVisibleItemIsOne() = assertEquals(1f, FlashHubMath.visibleFraction(100, 200, 0, 1000))

    @Test fun halfClippedAtTheTop() = assertEquals(0.5f, FlashHubMath.visibleFraction(-100, 200, 0, 1000))

    @Test fun halfClippedAtTheBottom() = assertEquals(0.5f, FlashHubMath.visibleFraction(900, 200, 0, 1000))

    @Test fun itemOutsideTheViewportIsZeroOnBothSides() {
        assertEquals(0f, FlashHubMath.visibleFraction(2000, 200, 0, 1000))
        assertEquals(0f, FlashHubMath.visibleFraction(-500, 200, 0, 1000))
    }

    @Test fun zeroAndNegativeSizesAreNeverVisible() {
        assertEquals(0f, FlashHubMath.visibleFraction(10, 0, 0, 1000))
        assertEquals(0f, FlashHubMath.visibleFraction(10, -5, 0, 1000))
    }

    @Test fun itemLargerThanTheViewportIsClampedToOne() {
        assertEquals(0.5f, FlashHubMath.visibleFraction(0, 2000, 0, 1000))
    }

    // --- Combined search ---------------------------------------------------------------------------------

    @Test fun blankQueryMatchesNothingInEveryBlock() {
        val d = listOf(dept("1", "Finance"))
        assertTrue(FlashHubMath.matchDepartments(d, "  ").isEmpty())
        assertTrue(FlashHubMath.matchNews(listOf(ann("1", "x")), "").isEmpty())
        assertTrue(FlashHubMath.matchGroups(listOf(group("1", "x", "y")), "   ").isEmpty())
    }

    @Test fun departmentMatchIsAccentAndCaseBlindAndFindsHeads() {
        val d = listOf(dept("1", "Ressources Humaines", listOf("Zoë Baker")), dept("2", "Finance"))
        assertEquals(listOf("1"), FlashHubMath.matchDepartments(d, "ressources").map { it.id })
        assertEquals(listOf("1"), FlashHubMath.matchDepartments(d, "ZOE").map { it.id })
        assertEquals(listOf("2"), FlashHubMath.matchDepartments(d, "fin").map { it.id })
    }

    @Test fun newsMatchLooksAtTitleBodyAndAuthor() {
        val n = listOf(ann("1", "Holiday", body = "closed Friday"), ann("2", "Other", author = "Renée Park"))
        assertEquals(listOf("1"), FlashHubMath.matchNews(n, "friday").map { it.id })
        assertEquals(listOf("2"), FlashHubMath.matchNews(n, "renee").map { it.id })
    }

    @Test fun groupMatchLooksAtNameAndDepartment() {
        val g = listOf(group("1", "All hands", "Engineering"), group("2", "Close", "Finance"))
        assertEquals(listOf("2"), FlashHubMath.matchGroups(g, "fina").map { it.groupId })
        assertEquals(listOf("1"), FlashHubMath.matchGroups(g, "hands").map { it.groupId })
    }

    @Test fun firstHitFollowsThePeopleDepartmentsNewsGroupsOrder() {
        val p = listOf(person("p1")); val d = listOf(dept("d1", "A")); val n = listOf(ann("n1", "t")); val g = listOf(group("g1", "a", "b"))
        assertEquals(FlashHubMath.SearchHit.Person("p1"), FlashHubMath.firstHit(p, d, n, g))
        assertEquals(FlashHubMath.SearchHit.Department("d1"), FlashHubMath.firstHit(emptyList(), d, n, g))
        assertEquals(FlashHubMath.SearchHit.News("n1"), FlashHubMath.firstHit(emptyList(), emptyList(), n, g))
        assertEquals(FlashHubMath.SearchHit.Group("g1"), FlashHubMath.firstHit(emptyList(), emptyList(), emptyList(), g))
        assertNull(FlashHubMath.firstHit(emptyList(), emptyList(), emptyList(), emptyList()))
    }

    @Test fun queryIsTrimmedAndBounded() {
        assertEquals("abc", FlashHubMath.normaliseQuery("  abc \n"))
        assertEquals(FlashHubMath.QUERY_MAX, FlashHubMath.normaliseQuery("x".repeat(FlashHubMath.QUERY_MAX + 500)).length)
    }

    // --- Saved selection ---------------------------------------------------------------------------------

    @Test fun everyFilterRoundTrips() {
        listOf(FlashHubPeopleFilter.All, FlashHubPeopleFilter.Mine, FlashHubPeopleFilter.Department("dept:with:colons")).forEach {
            assertEquals(it, FlashHubSelectionCodec.decodeFilter(FlashHubSelectionCodec.encodeFilter(it)))
        }
    }

    @Test fun damagedFilterTextGivesAll() {
        assertEquals(FlashHubPeopleFilter.All, FlashHubSelectionCodec.decodeFilter(null))
        assertEquals(FlashHubPeopleFilter.All, FlashHubSelectionCodec.decodeFilter("dept:"))
        assertEquals(FlashHubPeopleFilter.All, FlashHubSelectionCodec.decodeFilter("whatever"))
    }

    @Test fun fullSelectionRoundTrips() {
        val s = FlashHubSelection(FlashHubSegment.DEPARTMENTS, true, "ann", FlashHubPeopleFilter.Department("d9"), "p1", "a1", "d9")
        assertEquals(s, FlashHubSelectionCodec.decode(FlashHubSelectionCodec.encode(s)))
    }

    @Test fun wrongSizedOrWronglyTypedSavedListsNeverCrash() {
        assertNull(FlashHubSelectionCodec.decode(emptyList()))
        assertNull(FlashHubSelectionCodec.decode(listOf("NEWS")))
        val odd = FlashHubSelectionCodec.decode(listOf(42, "yes", 7, null, 5, 6, 7))
        assertEquals(FlashHubSelection(), odd)
    }

    @Test fun unknownSegmentRestoresToPeopleAndEmptyIdsToNull() {
        val s = FlashHubSelectionCodec.decode(listOf("GONE", false, "", "all", "", "", ""))
        assertEquals(FlashHubSegment.PEOPLE, s?.segment)
        assertNull(s?.personId); assertNull(s?.announcementId); assertNull(s?.departmentId)
    }

    @Test fun oversizedSavedQueryIsClipped() {
        val s = FlashHubSelectionCodec.decode(listOf("PEOPLE", true, "q".repeat(5_000), "all", null, null, null))
        assertEquals(FlashHubMath.QUERY_MAX * 2, s?.query?.length)
    }

    // --- Group request review (administrators only) --------------------------------------------------------

    @Test fun reviewRequestsIsOfferedOnlyToAdministratorsWithTheHook() {
        val plain = group("1", "a", "b")
        val admin = plain.copy(pendingRequests = 0)
        assertTrue(FlashHubMath.showManageRequests(listOf(plain, admin), hookAvailable = true))
        assertTrue(!FlashHubMath.showManageRequests(listOf(plain), hookAvailable = true))
        assertTrue(!FlashHubMath.showManageRequests(listOf(admin), hookAvailable = false))
        assertTrue(!FlashHubMath.showManageRequests(emptyList(), hookAvailable = true))
    }

    // --- Call gate (H8) -----------------------------------------------------------------------------------

    private fun callee(paired: Boolean, org: Boolean, reach: HubReachability, self: Boolean = false) =
        person("x").copy(paired = paired, orgVerified = org, reachability = reach, isSelf = self)

    @Test fun callNeedsTrustLanReachAndTheHostGate() {
        assertTrue(FlashHubMath.callAllowed(callee(true, false, HubReachability.LAN), true))
        assertTrue(FlashHubMath.callAllowed(callee(false, true, HubReachability.LAN), true))
        assertTrue(!FlashHubMath.callAllowed(callee(false, false, HubReachability.LAN), true), "an unverified stranger on the LAN may not ring")
        assertTrue(!FlashHubMath.callAllowed(callee(true, true, HubReachability.HUB), true), "through the hub is not a LAN call")
        assertTrue(!FlashHubMath.callAllowed(callee(true, true, HubReachability.OFFLINE), true))
        assertTrue(!FlashHubMath.callAllowed(callee(true, true, HubReachability.UNKNOWN), true))
        assertTrue(!FlashHubMath.callAllowed(callee(true, true, HubReachability.LAN), false), "the host gate (already in a call) wins")
        assertTrue(!FlashHubMath.callAllowed(callee(true, true, HubReachability.LAN, self = true), true), "no calling yourself")
    }

    // --- Routes from notifications and cold starts (H9) ---------------------------------------------------------

    @Test fun routeWaitsUntilEnrolmentIsKnownThenOpensTheHubOrSaysNotEnrolled() {
        val r = FlashHubMath.routeAnnouncement("a1")
        assertEquals(FlashHubMath.RouteDecision.Wait, FlashHubMath.routeDecision(r, enrolmentKnown = false, enrolled = false))
        assertEquals(FlashHubMath.RouteDecision.Wait, FlashHubMath.routeDecision(r, enrolmentKnown = false, enrolled = true))
        assertEquals(FlashHubMath.RouteDecision.OpenHub(r), FlashHubMath.routeDecision(r, enrolmentKnown = true, enrolled = true))
        assertEquals(FlashHubMath.RouteDecision.NotEnrolled, FlashHubMath.routeDecision(r, enrolmentKnown = true, enrolled = false))
    }

    @Test fun damagedOrForeignRoutesAreDroppedImmediately() {
        listOf(null, "", "hub/announcement/", "hub/person/" + "x".repeat(129), "chat/abc", "hub/person/a b", "hub/announcement/../x").forEach {
            assertEquals(FlashHubMath.RouteDecision.Drop, FlashHubMath.routeDecision(it, enrolmentKnown = true, enrolled = true), "route=$it")
        }
    }

    @Test fun routesRoundTripThroughTheBuildersAndParser() {
        assertEquals(FlashHubMath.Route.Announcement("a-1_2:3"), FlashHubMath.parseRoute(FlashHubMath.routeAnnouncement("a-1_2:3")))
        assertEquals(FlashHubMath.Route.Person("dev.01"), FlashHubMath.parseRoute(FlashHubMath.routePerson("dev.01")))
    }
}
